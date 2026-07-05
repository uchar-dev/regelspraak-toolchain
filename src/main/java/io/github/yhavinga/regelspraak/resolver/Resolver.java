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
        RegelSpraakLexer lexer = new RegelSpraakLexer(CharStreams.fromString(source));
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

        new RuleChecker(model).check(doc);
        return model;
    }

    /** Generate the fact-model beans a resolved model implies and write them to {@code outdir}, one file per
     *  class under {@code <outdir>/io/github/yhavinga/regelspraak/facts/<Name>.java} (UTF-8, dirs created), then return the
     *  number written. The caller MUST have checked {@link RegelSpraakModel#isOk()} first: fact generation
     *  reads the resolved model, so a model with errors would emit a bean set derived from a broken model. By
     *  contract this method touches disk ONLY when invoked, so main's error path (which never calls it) writes
     *  nothing. */
    public static int factsNaarDisk(RegelSpraakModel m, java.nio.file.Path outdir) throws java.io.IOException {
        var bronnen = io.github.yhavinga.regelspraak.schema.FactModelGenerator.genereer(m);
        java.nio.file.Path pkgDir = outdir.resolve(
                io.github.yhavinga.regelspraak.schema.FactModelGenerator.PAKKET.replace('.', '/'));
        java.nio.file.Files.createDirectories(pkgDir);
        for (var e : bronnen.entrySet())
            java.nio.file.Files.writeString(pkgDir.resolve(e.getKey() + ".java"), e.getValue(),
                    java.nio.charset.StandardCharsets.UTF_8);
        return bronnen.size();
    }

    /** The --facts mode body: {@code rest} = [outdir, file.rs] after flag removal. Returns the process
     *  exit code (0 = beans geschreven, 1 = resolver-fouten, 2 = usage) instead of exiting, so the error
     *  discipline — an unresolvable model writes NOTHING under outdir — is testable as the REAL CLI path. */
    public static int factsModus(java.util.List<String> rest) throws java.io.IOException {
        // --facts <outdir> <file.rs>: after --facts is consumed, the FIRST remaining arg is the outdir,
        // the SECOND the source. (--allow-markers is meaningless here but tolerated, already removed.)
        if (rest.size() < 2) { System.err.println("usage: Resolver --facts <outdir> <file.rs>"); return 2; }
        java.nio.file.Path outdir = java.nio.file.Path.of(rest.get(0));
        RegelSpraakModel mf = resolve(java.nio.file.Files.readString(java.nio.file.Path.of(rest.get(1))));
        // Check errors BEFORE touching disk: an unresolvable model writes NOTHING under outdir.
        if (!mf.isOk()) {
            mf.diagnostics.forEach(d -> System.out.println("  " + d));
            return 1;
        }
        int n = factsNaarDisk(mf, outdir);
        System.out.printf("facts: %d klasse(n) geschreven naar %s/%s%n",
                n, outdir, io.github.yhavinga.regelspraak.schema.FactModelGenerator.PAKKET.replace('.', '/'));
        return 0;
    }

    /** CLI: resolve a .rs file, print a model summary and any diagnostics; exit 1 if any error.
     *  With --drl, print the transpiled Drools (DRL) instead of the summary; a DRL whose coverage
     *  manifest is not fully LOWERED (semantically inert marker rules) exits 3 unless
     *  --allow-markers is given — accepted input with inert output must never look like success.
     *  With --facts &lt;outdir&gt;, generate the implied fact-model beans (§13.3) into &lt;outdir&gt;
     *  and exit 0. --facts is model-level: it deliberately does NOT run the DRL transpiler and does NOT
     *  apply the exit-3 marker discipline (that discipline is about RULE lowering, orthogonal to the
     *  fact-model the beans encode). --facts and --drl are mutually exclusive output modes. */
    public static void main(String[] args) throws java.io.IOException {
        var rest = new java.util.ArrayList<>(java.util.List.of(args));
        boolean drl = rest.remove("--drl");
        boolean allowMarkers = rest.remove("--allow-markers");
        boolean facts = rest.remove("--facts");
        if (drl && facts) { System.err.println("usage: --drl en --facts sluiten elkaar uit"); System.exit(2); }
        if (facts) System.exit(factsModus(rest));
        if (rest.isEmpty()) { System.err.println("usage: Resolver [--drl] [--allow-markers] <file.rs>"); System.exit(2); }
        RegelSpraakModel m = resolve(java.nio.file.Files.readString(java.nio.file.Path.of(rest.get(0))));
        if (drl) {
            // Transpiling also surfaces lowering-phase diagnostics (the dekking-WARNINGs per marker,
            // plus e.g. a consistentieregel whose violation is not expressible); print them to stderr
            // so they are visible without polluting the DRL on stdout.
            var t = io.github.yhavinga.regelspraak.transpiler.DrlTranspiler.build(m);
            System.out.print(t.drl());
            m.diagnostics.forEach(d -> System.err.println("  " + d));
            if (!m.isOk()) System.exit(1);
            var nietLowered = t.dekking().stream().filter(d -> !d.lowered()).toList();
            if (!nietLowered.isEmpty() && !allowMarkers) {
                System.err.println("  " + nietLowered.size() + " constructie(s) niet gelowered (marker/no-op in de DRL)"
                        + " — exit 3; geef --allow-markers om de DRL toch te accepteren");
                System.exit(3);
            }
            System.exit(0);
        }
        // Run the transpiler (discarding the DRL) so the summary's diagnostics include the lowering-phase
        // ones — a "does my model resolve AND lower cleanly?" check, not resolve-only.
        io.github.yhavinga.regelspraak.transpiler.DrlTranspiler.transpile(m);
        System.out.printf("objecttypen=%d  domeinen=%d  parameters=%d  feittypen=%d  regels=%d  resolved=%d%n",
                m.objecttypen.size(), m.domeinen.size(), m.parameters.size(), m.feittypen.size(),
                m.regels.size(), m.resolvedRegels.size());
        m.resolvedRegels.forEach(r -> {
            System.out.printf("  [%s] %s := %s :: %s%n",
                    r.soort(), r.doel().bron(), r.waarde().bron(), r.waarde().type().naam());
            if (r.voorwaarde() != null) System.out.println("      indien " + r.voorwaarde().bron());
        });
        m.diagnostics.forEach(d -> System.out.println("  " + d));
        System.exit(m.isOk() ? 0 : 1);
    }

    private Resolver() {}
}
