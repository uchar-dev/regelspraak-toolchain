package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The scenario runner: the scenariotoetsen of a RegelSpraak test document, run through uchar's scenario layer.
 * uchar's {@link ScenarioResolver} resolves the document; its lowering ({@link CaseEmitter}) writes the IR document
 * the scenarios run against — with a scope, only the forms the scope names — and one case file per scenario; the
 * backend's {@link CaseRunner#rapport} replays those case files through the same build a consumer gets and reports
 * each one. This class writes the lowering to a scratch directory, hands it to the report and names each report
 * after its scenario. It compares nothing itself: a verdict here is uchar's replay verdict.
 */
public final class ScenarioRunner {
    private ScenarioRunner() {}

    /** Resolve {@code bron} under the scenario frontend and run every scenario in it. A resolve error throws
     *  {@link ResolveFout} carrying the diagnostics (the CLI turns that into exit 1). */
    public static List<ScenarioResultaat> run(String bron) {
        return run(ScenarioResolver.resolve(bron));
    }

    /** Run every scenario in an already-resolved bundle, one report per scenario in document order, each named after
     *  its scenario. A document uchar does not carry reports every scenario as MARKER with uchar's reason. */
    public static List<ScenarioResultaat> run(ScenarioBundel bundel) {
        if (!bundel.isOk()) throw new ResolveFout(diagnostiek(bundel));
        List<ResolvedScenario> scenarios = bundel.scenarios();
        if (scenarios.isEmpty()) return List.of();
        // One document lowers to one IR document, so every scenario carries the scope of the first; the resolver
        // refuses a document whose scenarios differ in scope.
        RegelSpraakModel irModel = CaseEmitter.metScope(bundel.model(), scenarios.get(0).scope());
        // An allocation without a method is left out of the IR document and named only in a warning; replaying the
        // document would test the scenarios without it.
        List<Diagnostic> ongeschreven = IrEmitter.ongeschrevenVerdelingen(irModel);
        if (!ongeschreven.isEmpty()) return markers(scenarios, "emitter: " + ongeschreven.get(0).message());
        String document;
        try {
            document = IrEmitter.emit(irModel);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            return markers(scenarios, "emitter: " + e.getMessage());
        }
        Path map;
        try {
            map = Files.createTempDirectory("scenariotoets-");
        } catch (IOException e) {
            throw new UncheckedIOException("kon geen werkmap voor de casebestanden maken", e);
        }
        try {
            Path ir = map.resolve("ir.json");
            Files.writeString(ir, document, StandardCharsets.UTF_8);
            List<Path> cases = new ArrayList<>();
            for (int i = 0; i < scenarios.size(); i++) {
                Path c = map.resolve(CaseEmitter.bestandsnaam(i + 1, scenarios.get(i)));
                Files.writeString(c, CaseEmitter.emit(scenarios.get(i)), StandardCharsets.UTF_8);
                cases.add(c);
            }
            List<ScenarioResultaat> rapport = CaseRunner.rapport(ir, cases);
            if (rapport.size() != scenarios.size())
                throw new IllegalStateException("het rapport telt " + rapport.size() + " case(s) voor "
                        + scenarios.size() + " scenario('s): een gelagerde case hoort nooit een red-path case te zijn");
            List<ScenarioResultaat> uit = new ArrayList<>();
            for (int i = 0; i < scenarios.size(); i++) {
                ScenarioResultaat r = rapport.get(i);
                uit.add(new ScenarioResultaat(scenarios.get(i).naam(), r.status(), r.verwachtingen(), r.trace(),
                        r.dekking(), r.diagnose()));
            }
            return uit;
        } catch (IOException e) {
            throw new UncheckedIOException("kon de casebestanden niet schrijven in " + map, e);
        } finally {
            verwijder(map);
        }
    }

    /** Every scenario as MARKER with {@code reden}: the document is accepted by the frontend and not run. */
    private static List<ScenarioResultaat> markers(List<ResolvedScenario> scenarios, String reden) {
        List<ScenarioResultaat> uit = new ArrayList<>();
        for (ResolvedScenario s : scenarios)
            uit.add(new ScenarioResultaat(s.naam(), ScenarioResultaat.Status.MARKER, List.of(),
                    new ScenarioResultaat.Trace(List.of()), List.of(), reden));
        return uit;
    }

    private static void verwijder(Path map) {
        try (Stream<Path> paden = Files.walk(map)) {
            for (Path p : paden.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) {
            throw new UncheckedIOException("kon de werkmap " + map + " niet opruimen", e);
        }
    }

    /** The resolver errors of a bundle rendered as "regel N: message" lines (for the CLI diagnostics). */
    private static List<String> diagnostiek(ScenarioBundel bundel) {
        List<String> uit = new ArrayList<>();
        for (var d : bundel.model().errors()) uit.add("regel " + d.line() + ": " + d.message());
        return uit;
    }

    /** Thrown when a bundle does not resolve — the CLI maps it to exit 1. */
    public static final class ResolveFout extends RuntimeException {
        private final transient List<String> diagnostiek;
        public ResolveFout(List<String> diagnostiek) { super(String.join("\n", diagnostiek)); this.diagnostiek = List.copyOf(diagnostiek); }
        public List<String> diagnostiek() { return diagnostiek; }
    }
}
