package dev.uchar.regelspraak.toolchain.cli;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.graph.ProducerConsumer;
import dev.uchar.regelspraak.graph.RuleGraph;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;

/**
 * The distribution's CLI over uchar's frontend and backend. {@code --ir} is uchar's own emitter mode
 * ({@link Resolver#irMode}); {@code --drl}, {@code --facts} and {@code --graph}/{@code --graph-mmd} are
 * output modes this distribution adds on top of uchar's public API: the DRL text of uchar's DRL-text branch, the
 * fact beans uchar's generator writes, and uchar's rule graph.
 */
public final class RegelSpraakCli {

    /** Source files read as one model: each file's text followed by a newline, in the order given, as uchar's --ir
     *  mode reads a chain. With more than one file a diagnostic's line is a line of the concatenation, which no file
     *  has, so {@link #message} names the file and the line in that file instead. */
    record Chain(String source, java.util.List<String> files, java.util.List<Integer> firstLines) {
        static Chain read(java.util.List<String> paths) throws java.io.IOException {
            StringBuilder source = new StringBuilder();
            var firstLines = new java.util.ArrayList<Integer>();
            int line = 1;
            for (String path : paths) {
                String text = java.nio.file.Files.readString(java.nio.file.Path.of(path)) + '\n';
                firstLines.add(line);
                source.append(text);
                line += (int) text.chars().filter(c -> c == '\n').count();
            }
            return new Chain(source.toString(), java.util.List.copyOf(paths), java.util.List.copyOf(firstLines));
        }

        String message(Diagnostic d) {
            if (files.size() == 1 || d.line() <= 0) return d.toString();
            int i = firstLines.size() - 1;
            while (i > 0 && firstLines.get(i) > d.line()) i--;
            return d.severity() + " (" + files.get(i) + ":" + (d.line() - firstLines.get(i) + 1) + "): "
                    + d.message();
        }
    }

    /** Generate the fact-model beans a resolved model implies and write them to {@code outdir}, one file per
     *  class under {@code <outdir>/dev/uchar/regelspraak/facts/<Name>.java} (UTF-8, dirs created), then return the
     *  number written. The caller MUST have checked {@link RegelSpraakModel#isOk()} first: fact generation
     *  reads the resolved model, so a model with errors would emit a bean set derived from a broken model. By
     *  contract this method touches disk ONLY when invoked, so main's error path (which never calls it) writes
     *  nothing. */
    public static int factsToDisk(RegelSpraakModel m, java.nio.file.Path outdir) throws java.io.IOException {
        var sources = FactModelGenerator.generate(m);
        java.nio.file.Path pkgDir = outdir.resolve(FactModelGenerator.PACKAGE.replace('.', '/'));
        java.nio.file.Files.createDirectories(pkgDir);
        for (var e : sources.entrySet())
            java.nio.file.Files.writeString(pkgDir.resolve(e.getKey() + ".java"), e.getValue(),
                    java.nio.charset.StandardCharsets.UTF_8);
        return sources.size();
    }

    /** The --facts mode body: {@code rest} = [outdir, file.rgs...] after flag removal. Returns the process
     *  exit code (0 = beans geschreven, 1 = resolver-fouten, 2 = usage) instead of exiting, so the error
     *  discipline — an unresolvable model writes NOTHING under outdir — is testable as the REAL CLI path. */
    public static int factsMode(java.util.List<String> rest) throws java.io.IOException {
        // --facts <outdir> <file.rgs...>: after --facts is consumed, the FIRST remaining arg is the outdir,
        // the rest the source files of the model chain.
        if (rest.size() < 2) { System.err.println("usage: RegelSpraakCli --facts <outdir> <file.rgs...>"); return 2; }
        java.nio.file.Path outdir = java.nio.file.Path.of(rest.get(0));
        Chain chain = Chain.read(rest.subList(1, rest.size()));
        // one file threads its name into the model, as uchar's --ir mode does; a chain has no one name
        RegelSpraakModel mf = rest.size() == 2 ? Resolver.resolve(chain.source(), rest.get(1))
                : Resolver.resolve(chain.source());
        // Check errors BEFORE touching disk: an unresolvable model writes NOTHING under outdir.
        if (!mf.isOk()) {
            mf.diagnostics.forEach(d -> System.out.println("  " + chain.message(d)));
            return 1;
        }
        int n = factsToDisk(mf, outdir);
        System.out.printf("facts: %d klasse(n) geschreven naar %s/%s%n",
                n, outdir, FactModelGenerator.PACKAGE.replace('.', '/'));
        return 0;
    }

    /** The --graph / --graph-mmd mode body: {@code rest} = [out, file.rgs...] after flag removal — the FIRST
     *  remaining arg is the output file, the rest are the source files of the model chain (concatenated in
     *  order). {@code html} picks the self-contained HTML page (embedded Mermaid via CDN) over the bare Mermaid
     *  text. Returns the process exit code (0 = graaf geschreven, 1 = resolver-fouten, 2 = usage) instead of
     *  exiting, so an unresolvable model writes NOTHING — the same disk discipline as --facts. The graph is
     *  model-level, so it builds no KieBase and applies no exit-3 marker discipline. A resolver's model carries its
     *  defaults groups itself, and a navigated characteristic check reaches the type of the role it names
     *  ({@link ProducerConsumer#sourceNavigation}). */
    public static int graphMode(java.util.List<String> rest, boolean html) throws java.io.IOException {
        if (rest.size() < 2) {
            System.err.println("usage: RegelSpraakCli " + (html ? "--graph" : "--graph-mmd") + " <out> <file.rgs...>");
            return 2;
        }
        java.nio.file.Path out = java.nio.file.Path.of(rest.get(0));
        Chain chain = Chain.read(rest.subList(1, rest.size()));
        RegelSpraakModel m = Resolver.resolve(chain.source());
        // Check errors BEFORE touching disk: an unresolvable model writes NOTHING (a graph over a broken model
        // would show half-bound edges).
        if (!m.isOk()) {
            m.diagnostics.forEach(d -> System.out.println("  " + chain.message(d)));
            return 1;
        }
        var graph = RuleGraph.build(m, m.prioritizedDefaults, ProducerConsumer.sourceNavigation(m));
        java.nio.file.Files.writeString(out, html ? graph.html() : graph.mermaid(),
                java.nio.charset.StandardCharsets.UTF_8);
        long input = graph.nodes().stream()
                .filter(n -> n.kind() == RuleGraph.NodeKind.INPUT).count();
        long end = graph.nodes().stream().filter(RuleGraph.Node::endOutput).count();
        System.out.printf("graaf: %d knopen, %d kanten (%d invoer-bronnen, %d eind-outputs) geschreven naar %s%n",
                graph.nodes().size(), graph.edges().size(), input, end, out);
        return 0;
    }

    /** CLI: resolve one or more .rgs files as one model (concatenated in order), print a model summary and any
     *  diagnostics, then build it on uchar's exec-model branch; exit 1 on a resolver error, 3 when uchar does not
     *  carry the model (a construct the branch does not lower among them: accepted input with inert output must never
     *  look like success), else 0.
     *  With --drl, print the DRL text of uchar's DRL-text branch instead of the summary, or exit 3 with uchar's
     *  refusal; that branch builds no document with a construct it does not lower, so there is no DRL with markers.
     *  A DRL text the Drools compiler rejects is a fault of uchar's rendering: exit 1 with uchar's sentence.
     *  With --facts &lt;outdir&gt;, generate the implied fact-model beans (§13.3) into &lt;outdir&gt;
     *  and exit 0. --facts is model-level: it builds no KieBase and does NOT apply the exit-3 marker discipline
     *  (that discipline is about RULE lowering, orthogonal to the fact-model the beans encode).
     *  With --graph &lt;out.html&gt; / --graph-mmd &lt;out.mmd&gt; over one-or-more source files, emit the
     *  regelgraaf (dataflow regels &harr; gegevens) as a self-contained HTML page (embedded Mermaid via CDN) or
     *  as bare Mermaid text (offline-usable). Also model-level.
     *  With --ir &lt;out.json&gt; over one-or-more source files, delegate to uchar's {@link Resolver#irMode} to emit
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
        boolean graph = rest.remove("--graph");
        boolean graphMmd = rest.remove("--graph-mmd");
        boolean ir = rest.remove("--ir");
        int outputModes = (drl ? 1 : 0) + (facts ? 1 : 0) + (graph || graphMmd ? 1 : 0) + (ir ? 1 : 0);
        if (outputModes > 1) { System.err.println("usage: --drl, --facts, --graph[-mmd] en --ir sluiten elkaar uit"); System.exit(2); }
        if (ir) System.exit(Resolver.irMode(rest));   // the emitter path is uchar's own
        if (graph || graphMmd) System.exit(graphMode(rest, graph));
        if (facts) System.exit(factsMode(rest));
        if (rest.isEmpty()) { System.err.println("usage: RegelSpraakCli [--drl | --facts <outdir> | --graph <out.html> | --graph-mmd <out.mmd> | --ir <out.json>] <file.rgs...>"); System.exit(2); }
        Chain chain = Chain.read(rest);
        RegelSpraakModel m = Resolver.resolve(chain.source());
        if (drl) {
            m.diagnostics.forEach(d -> System.err.println("  " + chain.message(d)));
            if (!m.isOk()) System.exit(1);
            try {
                System.out.print(UcharBuild.assemble(m, DroolsBackend.Branch.DRL_TEXT).drl());
            } catch (UcharBuild.NotCarried e) {
                System.err.println("  niet uitvoerbaar in uchar (exit 3): " + e.getMessage());
                System.exit(3);
            } catch (IllegalStateException e) {
                // uchar's build of this branch throws this for a DRL text the Drools compiler rejects: a fault of its
                // rendering, not a form the branch does not carry, so no exit 3; uchar's sentence carries Drools' error
                System.err.println("  fout van uchar (exit 1): " + e.getMessage());
                System.exit(1);
            }
            System.exit(0);
        }
        System.out.printf("objecttypen=%d  domeinen=%d  parameters=%d  feittypen=%d  regels=%d  resolved=%d%n",
                m.objecttypen.size(), m.domeinen.size(), m.parameters.size(), m.feittypen.size(),
                m.regels.size(), m.resolvedRegels.size());
        m.resolvedRegels.forEach(r -> {
            System.out.printf("  [%s] %s := %s :: %s%n", r.category(), r.target().source(), r.value().source(),
                    r.value().type() == null ? "(geen type)" : r.value().type().naam());
            if (r.condition() != null) System.out.println("      indien " + r.condition().source());
        });
        m.diagnostics.forEach(d -> System.out.println("  " + chain.message(d)));
        if (!m.isOk()) System.exit(1);
        // "does my model resolve AND run?" — the build on the exec-model branch the engine and the service fire
        try {
            UcharBuild.assemble(m, DroolsBackend.Branch.EXEC_MODEL);
        } catch (UcharBuild.NotCarried e) {
            System.out.println("  niet uitvoerbaar in uchar (exit 3): " + e.getMessage());
            System.exit(3);
        }
        System.exit(0);
    }

    private RegelSpraakCli() {}
}
