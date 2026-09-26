package dev.uchar.regelspraak.toolchain.cli;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.graaf.ProducentConsument;
import dev.uchar.regelspraak.graaf.RegelGraaf;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.engine.UcharBouw;

/**
 * The distribution's CLI over uchar's frontend and backend. {@code --ir} is uchar's own emitter mode
 * ({@link Resolver#irModus}); {@code --drl}, {@code --facts} and {@code --graaf}/{@code --graaf-mmd} are
 * output modes this distribution adds on top of uchar's public API: the DRL text of uchar's DRL-text branch, the
 * fact beans uchar's generator writes, and uchar's rule graph.
 */
public final class RegelSpraakCli {

    /** Generate the fact-model beans a resolved model implies and write them to {@code outdir}, one file per
     *  class under {@code <outdir>/dev/uchar/regelspraak/facts/<Name>.java} (UTF-8, dirs created), then return the
     *  number written. The caller MUST have checked {@link RegelSpraakModel#isOk()} first: fact generation
     *  reads the resolved model, so a model with errors would emit a bean set derived from a broken model. By
     *  contract this method touches disk ONLY when invoked, so main's error path (which never calls it) writes
     *  nothing. */
    public static int factsNaarDisk(RegelSpraakModel m, java.nio.file.Path outdir) throws java.io.IOException {
        var bronnen = FactModelGenerator.genereer(m);
        java.nio.file.Path pkgDir = outdir.resolve(FactModelGenerator.PAKKET.replace('.', '/'));
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
                n, outdir, FactModelGenerator.PAKKET.replace('.', '/'));
        return 0;
    }

    /** The --graaf / --graaf-mmd mode body: {@code rest} = [out, file.rgs...] after flag removal — the FIRST
     *  remaining arg is the output file, the rest are the source files of the model chain (concatenated in
     *  order). {@code html} picks the self-contained HTML page (embedded Mermaid via CDN) over the bare Mermaid
     *  text. Returns the process exit code (0 = graaf geschreven, 1 = resolver-fouten, 2 = usage) instead of
     *  exiting, so an unresolvable model writes NOTHING — the same disk discipline as --facts. The graph is
     *  model-level, so it builds no KieBase and applies no exit-3 marker discipline. A resolver's model carries its
     *  defaults groups itself, and a navigated characteristic check reaches the type of the role it names
     *  ({@link ProducentConsument#bronNavigatie}). */
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
        var graaf = RegelGraaf.bouw(m, m.prioritizedDefaults, ProducentConsument.bronNavigatie(m));
        java.nio.file.Files.writeString(out, html ? graaf.html() : graaf.mermaid(),
                java.nio.charset.StandardCharsets.UTF_8);
        long invoer = graaf.nodes().stream()
                .filter(n -> n.kind() == RegelGraaf.NodeKind.INVOER).count();
        long eind = graaf.nodes().stream().filter(RegelGraaf.Node::eindOutput).count();
        System.out.printf("graaf: %d knopen, %d kanten (%d invoer-bronnen, %d eind-outputs) geschreven naar %s%n",
                graaf.nodes().size(), graaf.edges().size(), invoer, eind, out);
        return 0;
    }

    /** CLI: resolve a .rgs file, print a model summary and any diagnostics, then build it on uchar's exec-model
     *  branch; exit 1 on a resolver error, 3 when uchar does not carry the model (a construct the branch does not
     *  lower among them: accepted input with inert output must never look like success), else 0.
     *  With --drl, print the DRL text of uchar's DRL-text branch instead of the summary, or exit 3 with uchar's
     *  refusal; that branch builds no document with a construct it does not lower, so there is no DRL with markers.
     *  With --facts &lt;outdir&gt;, generate the implied fact-model beans (§13.3) into &lt;outdir&gt;
     *  and exit 0. --facts is model-level: it builds no KieBase and does NOT apply the exit-3 marker discipline
     *  (that discipline is about RULE lowering, orthogonal to the fact-model the beans encode).
     *  With --graaf &lt;out.html&gt; / --graaf-mmd &lt;out.mmd&gt; over one-or-more source files, emit the
     *  regelgraaf (dataflow regels &harr; gegevens) as a self-contained HTML page (embedded Mermaid via CDN) or
     *  as bare Mermaid text (offline-usable). Also model-level.
     *  With --ir &lt;out.json&gt; over one-or-more source files, delegate to uchar's {@link Resolver#irModus} to emit
     *  the FAITHFUL {@code uchar-ir-v1} JSON dump. The output modes are mutually exclusive. */
    public static void main(String[] args) throws java.io.IOException {
        var rest = new java.util.ArrayList<>(java.util.List.of(args));
        boolean drl = rest.remove("--drl");
        if (rest.contains("--allow-markers")) {
            System.err.println("--allow-markers: uchars DRL-tak bouwt geen document met een constructie die hij niet "
                    + "lowert, dus er is geen DRL met markers om te tonen");
            System.exit(2);
        }
        boolean facts = rest.remove("--facts");
        boolean graaf = rest.remove("--graaf");
        boolean graafMmd = rest.remove("--graaf-mmd");
        boolean ir = rest.remove("--ir");
        int uitvoerModi = (drl ? 1 : 0) + (facts ? 1 : 0) + (graaf || graafMmd ? 1 : 0) + (ir ? 1 : 0);
        if (uitvoerModi > 1) { System.err.println("usage: --drl, --facts, --graaf[-mmd] en --ir sluiten elkaar uit"); System.exit(2); }
        if (ir) System.exit(Resolver.irModus(rest));   // the emitter path is uchar's own
        if (graaf || graafMmd) System.exit(graafModus(rest, graaf));
        if (facts) System.exit(factsModus(rest));
        if (rest.isEmpty()) { System.err.println("usage: RegelSpraakCli [--drl | --facts <outdir> | --graaf <out.html> | --graaf-mmd <out.mmd> | --ir <out.json>] <file.rgs...>"); System.exit(2); }
        RegelSpraakModel m = Resolver.resolve(java.nio.file.Files.readString(java.nio.file.Path.of(rest.get(0))));
        if (drl) {
            m.diagnostics.forEach(d -> System.err.println("  " + d));
            if (!m.isOk()) System.exit(1);
            try {
                System.out.print(UcharBouw.bouw(m, DroolsBackend.Branch.DRL_TEXT).drlTranspiler().drl());
            } catch (UcharBouw.NietGedragen e) {
                System.err.println("  niet uitvoerbaar in uchar (exit 3): " + e.getMessage());
                System.exit(3);
            }
            System.exit(0);
        }
        System.out.printf("objecttypen=%d  domeinen=%d  parameters=%d  feittypen=%d  regels=%d  resolved=%d%n",
                m.objecttypen.size(), m.domeinen.size(), m.parameters.size(), m.feittypen.size(),
                m.regels.size(), m.resolvedRegels.size());
        m.resolvedRegels.forEach(r -> {
            System.out.printf("  [%s] %s := %s :: %s%n", r.soort(), r.doel().bron(), r.waarde().bron(),
                    r.waarde().type() == null ? "(geen type)" : r.waarde().type().naam());
            if (r.voorwaarde() != null) System.out.println("      indien " + r.voorwaarde().bron());
        });
        m.diagnostics.forEach(d -> System.out.println("  " + d));
        if (!m.isOk()) System.exit(1);
        // "does my model resolve AND run?" — the build on the exec-model branch the engine and the service fire
        try {
            UcharBouw.bouw(m, DroolsBackend.Branch.EXEC_MODEL);
        } catch (UcharBouw.NietGedragen e) {
            System.out.println("  niet uitvoerbaar in uchar (exit 3): " + e.getMessage());
            System.exit(3);
        }
        System.exit(0);
    }

    private RegelSpraakCli() {}
}
