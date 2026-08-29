package io.github.yhavinga.regelspraak.cli;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;

/**
 * Toolchain-side CLI wrapper around the vendored, {@code --ir}-only frontend
 * {@link Resolver}. Since the dependency inversion (PRIORITIZED-DEFAULTS-EVOLUTIE §6) the
 * frontend the toolchain consumes emits ONLY the v0 IR; this wrapper re-provides
 * the extra output modes the toolchain has always shipped — {@code --drl},
 * {@code --facts}, {@code --graaf}/{@code --graaf-mmd} — by reusing the vendored
 * parse/resolve pipeline ({@link Resolver#resolve}) and driving the toolchain's
 * own transpiler / fact-model generator / regelgraaf. {@code --ir} is delegated
 * straight to the vendored {@link Resolver#irModus} so the emitter path stays
 * byte-identical to the frontend's (uchar gate 10 is the sole emitter authority).
 *
 * <p>This class lives in a TOOLCHAIN package, never inside the vendored tree:
 * the frontend must keep exactly one output mode.
 */
public final class RegelSpraakCli {

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

    /** The --facts mode body: {@code rest} = [outdir, file.rgs] after flag removal. Returns the process
     *  exit code (0 = beans geschreven, 1 = resolver-fouten, 2 = usage) instead of exiting, so the error
     *  discipline — an unresolvable model writes NOTHING under outdir — is testable as the REAL CLI path. */
    public static int factsModus(java.util.List<String> rest) throws java.io.IOException {
        // --facts <outdir> <file.rgs>: after --facts is consumed, the FIRST remaining arg is the outdir,
        // the SECOND the source. (--allow-markers is meaningless here but tolerated, already removed.)
        if (rest.size() < 2) { System.err.println("usage: RegelSpraakCli --facts <outdir> <file.rgs>"); return 2; }
        java.nio.file.Path outdir = java.nio.file.Path.of(rest.get(0));
        RegelSpraakModel mf = Resolver.resolve(java.nio.file.Files.readString(java.nio.file.Path.of(rest.get(1))),
                rest.get(1));
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

    /** The --graaf / --graaf-mmd mode body: {@code rest} = [out, file.rgs...] after flag removal — the FIRST
     *  remaining arg is the output file, the rest are the source files of the model chain (concatenated in
     *  order). {@code html} picks the self-contained HTML page (embedded Mermaid via CDN) over the bare Mermaid
     *  text. Returns the process exit code (0 = graaf geschreven, 1 = resolver-fouten, 2 = usage) instead of
     *  exiting, so an unresolvable model writes NOTHING — the same disk discipline as --facts. The graph is
     *  model-level, so it deliberately does NOT run the DRL transpiler nor apply the exit-3 marker discipline. */
    public static int graafModus(java.util.List<String> rest, boolean html) throws java.io.IOException {
        if (rest.size() < 2) {
            System.err.println("usage: RegelSpraakCli " + (html ? "--graaf" : "--graaf-mmd") + " <out> <file.rgs...>");
            return 2;
        }
        java.nio.file.Path out = java.nio.file.Path.of(rest.get(0));
        StringBuilder bron = new StringBuilder();
        for (String f : rest.subList(1, rest.size()))
            bron.append(java.nio.file.Files.readString(java.nio.file.Path.of(f))).append('\n');
        RegelSpraakModel m = Resolver.resolve(bron.toString());
        // Check errors BEFORE touching disk: an unresolvable model writes NOTHING (a graph over a broken model
        // would show half-bound edges).
        if (!m.isOk()) {
            m.diagnostics.forEach(d -> System.out.println("  " + d));
            return 1;
        }
        var graaf = io.github.yhavinga.regelspraak.graaf.RegelGraaf.bouw(m);
        java.nio.file.Files.writeString(out, html ? graaf.html() : graaf.mermaid(),
                java.nio.charset.StandardCharsets.UTF_8);
        long invoer = graaf.nodes().stream()
                .filter(n -> n.kind() == io.github.yhavinga.regelspraak.graaf.RegelGraaf.NodeKind.INVOER).count();
        long eind = graaf.nodes().stream().filter(io.github.yhavinga.regelspraak.graaf.RegelGraaf.Node::eindOutput).count();
        System.out.printf("graaf: %d knopen, %d kanten (%d invoer-bronnen, %d eind-outputs) geschreven naar %s%n",
                graaf.nodes().size(), graaf.edges().size(), invoer, eind, out);
        return 0;
    }

    /** CLI: resolve a .rgs file, print a model summary and any diagnostics; exit 1 if any error.
     *  With --drl, print the transpiled Drools (DRL) instead of the summary; a DRL whose coverage
     *  manifest is not fully LOWERED (semantically inert marker rules) exits 3 unless
     *  --allow-markers is given — accepted input with inert output must never look like success.
     *  With --facts &lt;outdir&gt;, generate the implied fact-model beans (§13.3) into &lt;outdir&gt;
     *  and exit 0. --facts is model-level: it deliberately does NOT run the DRL transpiler and does NOT
     *  apply the exit-3 marker discipline (that discipline is about RULE lowering, orthogonal to the
     *  fact-model the beans encode). --facts and --drl are mutually exclusive output modes.
     *  With --graaf &lt;out.html&gt; / --graaf-mmd &lt;out.mmd&gt; over one-or-more source files, emit the
     *  regelgraaf (dataflow regels &harr; gegevens) as a self-contained HTML page (embedded Mermaid via CDN) or
     *  as bare Mermaid text (offline-usable). Also model-level; mutually exclusive with --drl and --facts.
     *  With --ir &lt;out.json&gt; over one-or-more source files, delegate to the vendored frontend's {@link
     *  Resolver#irModus} to emit the FAITHFUL v0 JSON dump. Mutually exclusive with the other modes. */
    public static void main(String[] args) throws java.io.IOException {
        var rest = new java.util.ArrayList<>(java.util.List.of(args));
        boolean drl = rest.remove("--drl");
        boolean allowMarkers = rest.remove("--allow-markers");
        boolean facts = rest.remove("--facts");
        boolean graaf = rest.remove("--graaf");
        boolean graafMmd = rest.remove("--graaf-mmd");
        boolean ir = rest.remove("--ir");
        int uitvoerModi = (drl ? 1 : 0) + (facts ? 1 : 0) + (graaf || graafMmd ? 1 : 0) + (ir ? 1 : 0);
        if (uitvoerModi > 1) { System.err.println("usage: --drl, --facts, --graaf[-mmd] en --ir sluiten elkaar uit"); System.exit(2); }
        if (ir) System.exit(Resolver.irModus(rest));   // the emitter path stays the vendored frontend's own
        if (graaf || graafMmd) System.exit(graafModus(rest, graaf));
        if (facts) System.exit(factsModus(rest));
        if (rest.isEmpty()) { System.err.println("usage: RegelSpraakCli [--drl [--allow-markers] | --facts <outdir> | --graaf <out.html> | --graaf-mmd <out.mmd> | --ir <out.json>] <file.rgs...>"); System.exit(2); }
        RegelSpraakModel m = Resolver.resolve(java.nio.file.Files.readString(java.nio.file.Path.of(rest.get(0))));
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

    private RegelSpraakCli() {}
}
