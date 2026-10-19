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
import dev.uchar.regelspraak.transpiler.core.Namen;

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
 *
 * <p>{@link #runWithExplanation} adds to each report the explanation of its outcome in words. uchar's report carries the
 * verdicts and the firings but not the fired facts (an explanation is presentation, its record says), so each played
 * scenario is played once more, in a session of its own on the same build ({@link CaseSession}), and the
 * {@link Verbaliser} explains that session's facts. The explanation is given only when that session fired what
 * uchar's replay reported and reads the values uchar read; otherwise it is withheld with the difference. The session
 * itself, with uchar's reading of its fixpoint, is given under the same condition. A case uchar's replay did not play
 * out has no explanation; its session is played all the same and given when evaluation faults stand at its fixpoint,
 * the faults the write-back of the replay throws, so that they are there as data.
 */
public final class ScenarioRunner {
    private ScenarioRunner() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A scenario's report with the explanation of its outcome: {@code explanation} one sentence per characteristic an
     *  attribution rule decided, and {@code explanationError} why there is none when a played scenario has none (null
     *  otherwise; a MARKER scenario has no explanation and no reason beside its own diagnose). {@code session} is the
     *  case played in a session of its own, with uchar's reading of its fixpoint ({@link CaseSession.Outcome#fixpoint}),
     *  when that session follows uchar's replay, and {@code sessionError} why there is none otherwise (both null for a
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
            List<ScenarioResult> report;
            try {
                report = CaseRunner.report(ir, cases);
            } catch (IrLoader.DocumentError e) {
                // the replay reports a refusal of its loader and backend as MARKER itself, but lets a malformed
                // document escape; uchar's emitter wrote it, so uchar does not carry this document either
                return markers(scenarios, "document: " + e.getMessage());
            }
            if (report.size() != scenarios.size())
                throw new IllegalStateException("het rapport telt " + report.size() + " case(s) voor "
                        + scenarios.size() + " scenario('s): een gelagerde case hoort nooit een red-path case te zijn");
            // the build the explanation plays on: the document the replay played, through the same facade
            // (UcharBuild, this distribution's one way to a build). When the replay played no case (the facade
            // refused the document, every report is MARKER) there is nothing to explain and no build to make.
            boolean played = report.stream().anyMatch(r -> r.status() != ScenarioResult.Status.MARKER);
            DroolsBackend.Build build = withExplanation && played ? UcharBuild.assemble(JSON.readTree(document),
                    DroolsBackend.Branch.EXEC_MODEL) : null;
            List<Explained> sink = new ArrayList<>();
            for (int i = 0; i < scenarios.size(); i++) {
                ScenarioResult r = report.get(i);
                ScenarioResult named = new ScenarioResult(scenarios.get(i).name(), r.status(),
                        r.expectations(), r.trace(), r.coverage(), r.diagnostic());
                sink.add(withExplanation ? explanation(build, named, JSON.readTree(Files.readString(cases.get(i))), vocabulary)
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

    /** The explanation of report {@code r}, whose case is {@code c}: the case played in a session of its own on
     *  {@code build}, checked against what uchar's replay reported, then verbalised in {@code vocabulaire}. The build's
     *  model is the one uchar's loader read from the IR document; the document carries uchar's table of origins
     *  (IR-SPEC ch. 2.13), so that model tells the rules uchar wrote. */
    private static Explained explanation(DroolsBackend.Build build, ScenarioResult r, JsonNode c, String vocabulary) {
        if (r.status() == ScenarioResult.Status.MARKER) return new Explained(r, List.of(), null, null, null);
        if (r.expectations().isEmpty() && r.status() == ScenarioResult.Status.FAIL)
            return notPlayedOut(build, r, c);
        CaseSession.Outcome session;
        try {
            session = CaseSession.play(build, c);
        } catch (CaseSession.NotPlayable e) {
            return new Explained(r, List.of(), "de uitleg speelt deze case niet: " + e.getMessage(), null,
                    "de eigen sessie speelt deze case niet: " + e.getMessage());
        }
        // uchar's replay wrote back without a throw, so no fault stood at its fixpoint
        if (!session.fixpoint().faults().isEmpty()) {
            String z = "de sessie van de uitleg kwam op een evaluatiefout die uchars replay niet gaf: "
                    + session.fixpoint().faults().get(0).message();
            return new Explained(r, List.of(), z, null, z);
        }
        if (!session.firings().equals(r.trace().firings())) {
            String z = "de sessie van de uitleg vuurde " + session.firings() + ", uchars replay "
                    + r.trace().firings();
            return new Explained(r, List.of(), z, null, z);
        }
        String difference = valueDifference(session, r, c);
        if (difference != null) return new Explained(r, List.of(), difference, null, difference);
        return new Explained(r, new Verbaliser(build.model(), session.globals(),
                Naming.of(build.model(), vocabulary))
                .verbalise(session.beans(), session.typePerLabel()), null, session, null);
    }

    /** A case uchar's replay did not play out, its play raised an exception: no explanation, and the session played
     *  for its fixpoint. That session is given when evaluation faults stand at the fixpoint, which the write-back of
     *  the replay throws; a session that reaches its fixpoint without one did not run as the replay ran. The session
     *  raises what the replay raised where the replay raised it before the write-back, and that is its reason. */
    private static Explained notPlayedOut(DroolsBackend.Build build, ScenarioResult r, JsonNode c) {
        String noExplanation = "uchars replay speelde de case niet uit: " + r.diagnostic();
        CaseSession.Outcome session;
        try {
            session = CaseSession.play(build, c);
        } catch (CaseSession.NotPlayable e) {
            return new Explained(r, List.of(), noExplanation, null,
                    "de eigen sessie speelt deze case niet: " + e.getMessage());
        } catch (RuntimeException e) {
            // uchar's report names the same failure as this case's FAIL; the session reports it as well
            return new Explained(r, List.of(), noExplanation, null,
                    "de eigen sessie speelde de case evenmin uit: " + e.getClass().getSimpleName() + ": "
                            + e.getMessage());
        }
        if (session.fixpoint().faults().isEmpty())
            return new Explained(r, List.of(), noExplanation, null, "de eigen sessie kwam zonder evaluatiefout op haar"
                    + " fixpunt, waar uchars replay de case niet uitspeelde");
        return new Explained(r, List.of(), noExplanation, session, null);
    }

    /** The first attribute or characteristic expectation whose value in the explanation's session differs from the
     *  value uchar's replay read, rendered as the replay renders it; null when none differs. */
    private static String valueDifference(CaseSession.Outcome session, ScenarioResult r, JsonNode c) {
        for (ScenarioResult.ExpectationResult v : r.expectations()) {
            JsonNode e = c.get("expected").get(v.position() - 1);
            String kind = e.get("kind").asText();
            Object bean = e.hasNonNull("label") ? session.beans().get(e.get("label").asText()) : null;
            String own = switch (kind) {
                case "attributeValue", "attributeEmpty" -> {
                    Object w = bean == null ? null : CaseSession.read(bean, Namen.getter(e.get("attribute").asText()));
                    yield w == null ? "leeg" : w.toString();
                }
                case "characteristicPresent", "characteristicAbsent" -> bean != null
                        && Boolean.TRUE.equals(CaseSession.read(bean, Namen.getter(e.get("characteristic").asText())))
                        ? "aanwezig" : "afwezig";
                default -> null;
            };
            if (own != null && !own.equals(v.actual()))
                return "de sessie van de uitleg las bij verwachting " + v.position() + " (" + v.source() + ") "
                        + own + ", uchars replay " + v.actual();
        }
        return null;
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
