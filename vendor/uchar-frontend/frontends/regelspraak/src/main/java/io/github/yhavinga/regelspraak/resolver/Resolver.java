package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.parser.RegelSpraak;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.RegelSpraakDocumentContext;
import io.github.yhavinga.regelspraak.parser.RegelSpraakLexer;
import org.antlr.v4.runtime.*;

/**
 * Front-end entry point: parse a RegelSpraak source, build the typed model
 * (§13.3), then type-check the rules against the typeringen matrix (§13.4 / the
 * typeringen specification). Syntax errors and type errors land in
 * {@link RegelSpraakModel#diagnostics}.
 */
public final class Resolver {

    public static RegelSpraakModel resolve(String source) {
        // IntStream.UNKNOWN_SOURCE_NAME ("<unknown>") is exactly what CharStreams.fromString(source)
        // already assigns, so this delegation is behaviour-preserving for the nameless callers.
        return resolve(source, IntStream.UNKNOWN_SOURCE_NAME);
    }

    /** As {@link #resolve(String)}, but with the source file name threaded onto the char stream so every
     *  {@link io.github.yhavinga.regelspraak.ir.Bron} anchors back to {@code bestandsnaam} (uitleg can then
     *  cite the file/regel); otherwise identical. */
    public static RegelSpraakModel resolve(String source, String bestandsnaam) {
        RegelSpraakLexer lexer = new RegelSpraakLexer(CharStreams.fromString(source, bestandsnaam));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RegelSpraak parser = new RegelSpraak(tokens);

        var syntax = new java.util.ArrayList<Diagnostic>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col,
                                              String msg, RecognitionException e) {
                syntax.add(new Diagnostic(Severity.ERROR, "syntaxfout: " + msg, line));
            }
        };
        lexer.removeErrorListeners(); lexer.addErrorListener(listener);
        parser.removeErrorListeners(); parser.addErrorListener(listener);

        RegelSpraakDocumentContext doc = parser.regelSpraakDocument();
        RegelSpraakModel model = new DefinitionResolver().build(doc);
        model.diagnostics.addAll(syntax);
        if (!syntax.isEmpty()) kopvormHints(tokens, model);

        new RuleChecker(model, tokens).check(doc);
        return model;
    }

    /** Two mis-spellings of the uchar 'is een' head form that ANTLR can only report as "no viable
     *  alternative", which sends the author looking at the wrong thing. Raised only alongside a real syntax
     *  error, over the token stream rather than the (failed) parse tree, and additively: the raw message
     *  stays, this names the fix.
     *
     *  <p>(a) The article dropped — {@code Objecttype het Wrakrecht is Strandrecht}. An IS after the
     *  object-type name is otherwise a §13.3.2 kenmerk declaration, but that one continues with a
     *  {@code lowerNaam}, so an IS followed by a CAPITALISED name can only be the head form missing its
     *  {@code een}.
     *
     *  <p>(b) The clause behind the mv-group — {@code Objecttype het Wrakrecht (mv: …) is een Strandrecht}.
     *  The mutual order of the head annotations is fixed by the grammar and always has been; the extension
     *  inserts one more element and the author has to know where. */
    private static void kopvormHints(CommonTokenStream tokens, RegelSpraakModel model) {
        var lijst = tokens.getTokens().stream().filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL).toList();
        for (int i = 0; i < lijst.size(); i++) {
            if (lijst.get(i).getType() != RegelSpraakLexer.OBJECTTYPE) continue;
            boolean naMeervoud = false;
            // Walk the HEAD only: the first token no head can contain ends it. Without that bound the
            // scan would run on into rule text, where "is een" is ordinary Dutch and no mistake at all.
            for (int j = i + 1; j < lijst.size() && kopToken(lijst.get(j).getType()); j++) {
                Token t = lijst.get(j);
                if (t.getType() == RegelSpraakLexer.MV_START) { naMeervoud = true; continue; }
                if (t.getType() != RegelSpraakLexer.IS) continue;
                Token na = j + 1 < lijst.size() ? lijst.get(j + 1) : null;
                if (na == null) break;
                if (na.getType() == RegelSpraakLexer.UPPER_ID)
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "kopvorm: 'is " + na.getText() + "' mist het lidwoord — een supertype wordt "
                            + "geschreven als 'is een " + na.getText() + "'", t.getLine()));
                else if (na.getType() == RegelSpraakLexer.EEN && naMeervoud)
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "kopvorm: de 'is een'-clausule staat achter de (mv: …)-groep — zij hoort direct "
                            + "na de objecttypenaam, vóór (mv: …) en (bezield)", t.getLine()));
                break;                                  // the head carries at most one such clause
            }
        }
    }

    /** The token types an object-type HEAD can consist of: its articles and name words, the mv-group, the
     *  animacy marker, and the extension's own two tokens. Anything else — a datatype, KENMERK, a new
     *  construct keyword — is already a member or the next definition. */
    private static boolean kopToken(int type) {
        return type == RegelSpraakLexer.DE || type == RegelSpraakLexer.HET
                || type == RegelSpraakLexer.UPPER_ID || type == RegelSpraakLexer.LOWER_ID
                || type == RegelSpraakLexer.MV_START || type == RegelSpraakLexer.RPAREN
                || type == RegelSpraakLexer.BEZIELD
                || type == RegelSpraakLexer.IS || type == RegelSpraakLexer.EEN
                || type == RegelSpraakLexer.EN;
    }

    /** The --ir mode body: {@code rest} = [out.json, file.rgs...] after flag removal — the FIRST remaining arg is
     *  the output file, the rest are the source files of the model chain (concatenated in order). Writes a
     *  FAITHFUL v0 JSON dump of the complete resolved model (the uchar "mirror" artifact — Dutch v0 field names
     *  exactly as the Java records name them, warts included) via {@link io.github.yhavinga.regelspraak.emit.IrEmitter}.
     *  Returns the process exit code (0 = ir geschreven, 1 = resolver-fouten, 2 = usage) instead of exiting, so the
     *  disk discipline is testable as the REAL CLI path: an unresolvable model writes NOTHING and exits non-zero. */
    public static int irModus(java.util.List<String> rest) throws java.io.IOException {
        if (rest.size() < 2) { System.err.println("usage: Resolver --ir <out.json> <file.rgs...>"); return 2; }
        java.nio.file.Path out = java.nio.file.Path.of(rest.get(0));
        StringBuilder bron = new StringBuilder();
        for (String f : rest.subList(1, rest.size()))
            bron.append(java.nio.file.Files.readString(java.nio.file.Path.of(f))).append('\n');
        // a single source file threads its name into the Bron coordinates; a
        // concatenated chain deliberately does not (per-file line offsets
        // would lie about every file after the first)
        RegelSpraakModel m = rest.size() == 2
                ? resolve(bron.toString(), rest.get(1))
                : resolve(bron.toString());
        // Check errors BEFORE touching disk: an unresolvable model writes NOTHING (a mirror of a broken model
        // would carry half-bound references and misleading diagnostics as if authoritative).
        if (!m.isOk()) {
            m.diagnostics.forEach(d -> System.out.println("  " + d));
            return 1;
        }
        java.nio.file.Files.writeString(out, io.github.yhavinga.regelspraak.emit.IrEmitter.emit(m),
                java.nio.charset.StandardCharsets.UTF_8);
        System.out.printf("ir: objecttypen=%d feittypen=%d resolvedRegels=%d geschreven naar %s%n",
                m.objecttypen.size(), m.feittypen.size(), m.resolvedRegels.size(), out);
        return 0;
    }

    /** CLI: with --ir &lt;out.json&gt; over one-or-more source files, emit a FAITHFUL v0 JSON dump of the
     *  complete resolved model (the uchar "mirror" artifact). This frontend carries exactly one output mode —
     *  the IR is the product; a bare invocation prints usage and exits 2. */
    public static void main(String[] args) throws java.io.IOException {
        var rest = new java.util.ArrayList<>(java.util.List.of(args));
        boolean ir = rest.remove("--ir");
        if (!ir) { System.err.println("usage: Resolver --ir <out.json> <file.rgs...>"); System.exit(2); }
        System.exit(irModus(rest));
    }

    private Resolver() {}
}
