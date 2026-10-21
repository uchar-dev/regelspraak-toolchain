package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundle;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The scenario runner: the scenariotoetsen of a RegelSpraak test document, run through uchar's scenario layer.
 * uchar's {@link ScenarioResolver} resolves the document; its lowering ({@link CaseEmitter}) writes the IR document
 * the scenarios run against — with a scope, only the forms the scope names — and one case file per scenario; the
 * backend's {@link CaseRunner#report} replays those case files through the same build a consumer gets and reports
 * each one. This class writes the lowering to a scratch directory, hands it to the report and names each report
 * after its scenario. It compares nothing itself: a verdict here is uchar's replay verdict.
 *
 * <p>{@link #runWithExplanation} adds to each report the explanation of its outcome in words. uchar's report carries the
 * verdicts and the firings but not the fired facts (an explanation is presentation, its record says), so the replay
 * plays on a build with readings and hands each session it fired to this class ({@link CaseRunner.Handoff}), right
 * after the fire and before the write-back: the fixpoint, the decisions of the defaults stacks and the readings of the
 * conditions are read off the very session the report judged ({@link CaseSession}), and the {@link Verbaliser} explains
 * it once the report wrote its beans back. A case uchar's replay did not play out has no explanation; its session is
 * given when evaluation faults stand at its fixpoint, the faults the write-back of the replay throws, so that they are
 * there as data.
 */
public final class ScenarioRunner {
    private ScenarioRunner() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A scenario's report with the explanation of its outcome: {@code explanation} one sentence per characteristic an
     *  attribution rule decided, and {@code explanationError} why there is none when a played scenario has none (null
     *  otherwise; a MARKER scenario has no explanation and no reason beside its own diagnose). {@code session} is the
     *  session uchar's replay judged, with uchar's reading of its fixpoint ({@link CaseSession.Outcome#fixpoint}), as the
     *  replay handed it over, and {@code sessionError} why there is none otherwise (both null for a
     *  MARKER scenario and without an explanation asked). */
    public record Explained(ScenarioResult result, List<String> explanation, String explanationError,
                            CaseSession.Outcome session, String sessionError) {}

    /** Resolve {@code source} under the scenario frontend and run every scenario in it. A resolve error throws
     *  {@link ResolveError} carrying the diagnostics (the CLI turns that into exit 1). */
    public static List<ScenarioResult> run(String source) {
        return run(ScenarioResolver.resolve(source));
    }

    /** Run every scenario in an already-resolved bundle, one report per scenario in document order, each named after
     *  its scenario. A document uchar does not carry reports every scenario as MARKER with uchar's reason: a refusal
     *  of its emitter, and every refusal of its backend, the replay's own (a form the loader or the exec-model branch
     *  does not carry) and a document its loader calls malformed. */
    public static List<ScenarioResult> run(ScenarioBundle bundle) {
        List<ScenarioResult> sink = new ArrayList<>();
        for (Explained u : play(bundle, false, null)) sink.add(u.result());
        return sink;
    }

    /** {@link #run(ScenarioBundle)} with the explanation of each played scenario's outcome. */
    public static List<Explained> runWithExplanation(ScenarioBundle bundle) {
        return play(bundle, true, null);
    }

    /** {@link #runWithExplanation(ScenarioBundle)} with the explanation in vocabulary {@code vocabulaire}
     *  ({@link Naming#of}; null or {@link Naming#CANONICAL} for the canonical names). */
    public static List<Explained> runWithExplanation(ScenarioBundle bundle, String vocabulary) {
        return play(bundle, true, vocabulary);
    }

    private static List<Explained> play(ScenarioBundle bundle, boolean withExplanation, String vocabulary) {
        if (!bundle.isOk()) throw new ResolveError(diagnostics(bundle));
        List<ResolvedScenario> scenarios = bundle.scenarios();
        if (scenarios.isEmpty()) return List.of();
        // One document lowers to one IR document, so every scenario carries the scope of the first; the resolver
        // refuses a document whose scenarios differ in scope.
        RegelSpraakModel irModel = CaseEmitter.withScope(bundle.model(), scenarios.get(0).scope());
        // An allocation without a method is left out of the IR document and named only in a warning; replaying the
        // document would test the scenarios without it.
        List<Diagnostic> unwritten = IrEmitter.unwrittenAllocations(irModel);
        if (!unwritten.isEmpty()) return markers(scenarios, "emitter: " + unwritten.get(0).message());
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
                Path c = map.resolve(CaseEmitter.fileName(i + 1, scenarios.get(i)));
                Files.writeString(c, CaseEmitter.emit(scenarios.get(i)), StandardCharsets.UTF_8);
                cases.add(c);
            }
            // what the replay hands over of each session it fired, by case file, read before the write-back
            Map<String, Handed> handed = new LinkedHashMap<>();
            CaseRunner.Handoff handoff = (caseFile, build, session, labels, firing) -> {
                if (firing.noFixpoint() != null) {
                    handed.put(caseFile, new Handed(labels, null, List.of(), List.of(), firing.noFixpoint()));
                    return;
                }
                Map<Object, String> labelOf = new IdentityHashMap<>();
                labels.forEach((label, bean) -> labelOf.put(bean, label));
                Fixpoint fixpoint = DroolsBackend.fixpointOf(build, session, labelOf::get);
                boolean answered = fixpoint.faults().isEmpty();
                handed.put(caseFile, new Handed(labels, fixpoint,
                        answered ? DroolsBackend.decisionsOf(build, session) : List.of(),
                        answered ? DroolsBackend.readingsOf(build, session) : List.of(), null));
            };
            List<ScenarioResult> report;
            try {
                report = CaseRunner.report(ir, cases, withExplanation ? handoff : null);
            } catch (IrLoader.DocumentError e) {
                // the replay reports a refusal of its loader and backend as MARKER itself, but lets a malformed
                // document escape; uchar's emitter wrote it, so uchar does not carry this document either
                return markers(scenarios, "document: " + e.getMessage());
            }
            if (report.size() != scenarios.size())
                throw new IllegalStateException("het rapport telt " + report.size() + " case(s) voor "
                        + scenarios.size() + " scenario('s): een gelagerde case hoort nooit een red-path case te zijn");
            // the explanation reads the document as uchar's loader reads it, with the positions of its nodes, on which
            // the readings lie, and the defaults groups beside the model, into which the engine's decisions point;
            // when the replay played no case (every report is MARKER) there is nothing to explain
            boolean played = report.stream().anyMatch(r -> r.status() != ScenarioResult.Status.MARKER);
            Verbaliser.Document explained = withExplanation && played
                    ? Verbaliser.Document.of(IrLoader.loadWithPositions(JSON.readTree(document))) : null;
            List<Explained> sink = new ArrayList<>();
            for (int i = 0; i < scenarios.size(); i++) {
                ScenarioResult r = report.get(i);
                ScenarioResult named = new ScenarioResult(scenarios.get(i).name(), r.status(),
                        r.expectations(), r.trace(), r.coverage(), r.diagnostic());
                sink.add(withExplanation ? explanation(explained, named,
                        handed.get(cases.get(i).getFileName().toString()),
                        JSON.readTree(Files.readString(cases.get(i))), vocabulary)
                        : new Explained(named, List.of(), null, null, null));
            }
            return sink;
        } catch (IOException e) {
            throw new UncheckedIOException("kon de casebestanden niet schrijven in " + map, e);
        } finally {
            remove(map);
        }
    }

    /** Every scenario as MARKER with {@code reden}: the document is accepted by the frontend and not run. */
    private static List<Explained> markers(List<ResolvedScenario> scenarios, String reason) {
        List<Explained> sink = new ArrayList<>();
        for (ResolvedScenario s : scenarios)
            sink.add(new Explained(new ScenarioResult(s.name(), ScenarioResult.Status.MARKER, List.of(),
                    new ScenarioResult.Trace(List.of()), List.of(), reason), List.of(), null, null, null));
        return sink;
    }

    /** What the replay handed over of the session of one case: the case labels of its input beans, its fixpoint, and
     *  the decisions and readings of a session without a standing fault; or, without a fixpoint, uchar's sentence. */
    private record Handed(Map<String, Object> labels, Fixpoint fixpoint, List<DefaultsDecision> decisions,
                          List<ConditionReading> readings, String noFixpoint) {}

    /** The explanation of report {@code r}, whose case is {@code c} and whose session the replay handed over as
     *  {@code h} (null where its input or fire raised): verbalised in {@code vocabulary} on {@code explained}, what
     *  uchar's loader read of the IR document the build was made from. The document carries uchar's table of origins
     *  (IR-SPEC ch. 2.13), so that model tells the rules uchar wrote. The objects of the session are of the build's
     *  types, named alike in that model, and their types are those the case gives its labels. */
    private static Explained explanation(Verbaliser.Document explained, ScenarioResult r, Handed h, JsonNode c,
                                         String vocabulary) {
        if (r.status() == ScenarioResult.Status.MARKER) return new Explained(r, List.of(), null, null, null);
        boolean playedOut = !(r.expectations().isEmpty() && r.status() == ScenarioResult.Status.FAIL);
        String noExplanation = playedOut ? null : "uchars replay speelde de case niet uit: " + r.diagnostic();
        if (h == null)
            return new Explained(r, List.of(), noExplanation, null, "uchars replay gaf de sessie van deze case niet"
                    + " door: haar invoer of haar vuur gaf een uitzondering");
        if (h.noFixpoint() != null)
            return new Explained(r, List.of(), noExplanation, null, "uchars replay kwam niet op een fixpunt: "
                    + h.noFixpoint());
        RegelSpraakModel model = explained.model();
        Map<String, RegelSpraakModel.ObjectType> types = new LinkedHashMap<>();
        for (JsonNode i : c.get("input").get("instances")) {
            String label = i.get("label").asText(), type = i.get("objectType").asText();
            RegelSpraakModel.ObjectType named = model.objectTypeByName(type);
            if (named == null)
                throw new IllegalStateException("het objecttype " + type + " van " + label + " staat niet in het"
                        + " model dat uchars lader uit hetzelfde document las");
            types.put(label, named);
        }
        CaseSession.Outcome session = new CaseSession.Outcome(new LinkedHashMap<>(h.labels()), types,
                r.trace().firings(), h.fixpoint(), h.decisions(), h.readings());
        if (!playedOut) {
            // the write-back of the replay throws a fault that stands at the fixpoint: that is a case not played out
            if (h.fixpoint().faults().isEmpty())
                return new Explained(r, List.of(), noExplanation, null, "uchars replay kwam zonder evaluatiefout op"
                        + " het fixpunt, en speelde de case toch niet uit");
            return new Explained(r, List.of(), noExplanation, session, null);
        }
        return new Explained(r, new Verbaliser(explained, Naming.of(model, vocabulary), h.readings())
                .verbalise(session.beans(), types, h.decisions()), null, session, null);
    }

    private static void remove(Path map) {
        try (Stream<Path> paths = Files.walk(map)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) {
            throw new UncheckedIOException("kon de werkmap " + map + " niet opruimen", e);
        }
    }

    /** The resolver errors of a bundle rendered as "regel N: message" lines (for the CLI diagnostics). */
    private static List<String> diagnostics(ScenarioBundle bundle) {
        List<String> sink = new ArrayList<>();
        for (var d : bundle.model().errors()) sink.add("regel " + d.line() + ": " + d.message());
        return sink;
    }

    /** Thrown when a bundle does not resolve — the CLI maps it to exit 1. */
    public static final class ResolveError extends RuntimeException {
        private final transient List<String> diagnostics;
        public ResolveError(List<String> diagnostics) { super(String.join("\n", diagnostics)); this.diagnostics = List.copyOf(diagnostics); }
        public List<String> diagnostics() { return diagnostics; }
    }
}
