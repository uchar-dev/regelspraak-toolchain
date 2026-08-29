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

        new RuleChecker(model, tokens).check(doc);
        return model;
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
