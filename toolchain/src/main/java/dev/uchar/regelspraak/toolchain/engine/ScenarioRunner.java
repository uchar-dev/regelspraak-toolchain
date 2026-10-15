package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import dev.uchar.regelspraak.transpiler.kern.Namen;

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
 * <p>{@link #runMetUitleg} adds to each report the explanation of its outcome in words. uchar's report carries the
 * verdicts and the firings but not the fired facts (an explanation is presentation, its record says), so each played
 * scenario is played once more, in a session of its own on the same build ({@link CaseSessie}), and the
 * {@link Verbalisator} explains that session's facts. The explanation is given only when that session fired what
 * uchar's replay reported and reads the values uchar read; otherwise it is withheld with the difference. The session
 * itself, with uchar's reading of its fixpoint, is given under the same condition. A case uchar's replay did not play
 * out has no explanation; its session is played all the same and given when evaluation faults stand at its fixpoint,
 * the faults the write-back of the replay throws, so that they are there as data.
 */
public final class ScenarioRunner {
    private ScenarioRunner() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A scenario's report with the explanation of its outcome: {@code uitleg} one sentence per characteristic an
     *  attribution rule decided, and {@code uitlegFout} why there is none when a played scenario has none (null
     *  otherwise; a MARKER scenario has no explanation and no reason beside its own diagnose). {@code sessie} is the
     *  case played in a session of its own, with uchar's reading of its fixpoint ({@link CaseSessie.Uitkomst#fixpunt}),
     *  when that session follows uchar's replay, and {@code sessieFout} why there is none otherwise (both null for a
     *  MARKER scenario and without an explanation asked). */
    public record Uitgelegd(ScenarioResultaat resultaat, List<String> uitleg, String uitlegFout,
                            CaseSessie.Uitkomst sessie, String sessieFout) {}

    /** Resolve {@code bron} under the scenario frontend and run every scenario in it. A resolve error throws
     *  {@link ResolveFout} carrying the diagnostics (the CLI turns that into exit 1). */
    public static List<ScenarioResultaat> run(String bron) {
        return run(ScenarioResolver.resolve(bron));
    }

    /** Run every scenario in an already-resolved bundle, one report per scenario in document order, each named after
     *  its scenario. A document uchar does not carry reports every scenario as MARKER with uchar's reason: a refusal
     *  of its emitter, and every refusal of its backend, the replay's own (a form the loader or the exec-model branch
     *  does not carry) and a document its loader calls malformed. */
    public static List<ScenarioResultaat> run(ScenarioBundel bundel) {
        List<ScenarioResultaat> uit = new ArrayList<>();
        for (Uitgelegd u : speel(bundel, false, null)) uit.add(u.resultaat());
        return uit;
    }

    /** {@link #run(ScenarioBundel)} with the explanation of each played scenario's outcome. */
    public static List<Uitgelegd> runMetUitleg(ScenarioBundel bundel) {
        return speel(bundel, true, null);
    }

    /** {@link #runMetUitleg(ScenarioBundel)} with the explanation in vocabulary {@code vocabulaire}
     *  ({@link Naamgeving#van}; null or {@link Naamgeving#CANONIEK} for the canonical names). */
    public static List<Uitgelegd> runMetUitleg(ScenarioBundel bundel, String vocabulaire) {
        return speel(bundel, true, vocabulaire);
    }

    private static List<Uitgelegd> speel(ScenarioBundel bundel, boolean metUitleg, String vocabulaire) {
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
            List<ScenarioResultaat> rapport;
            try {
                rapport = CaseRunner.rapport(ir, cases);
            } catch (IrLoader.Documentfout e) {
                // the replay reports a refusal of its loader and backend as MARKER itself, but lets a malformed
                // document escape; uchar's emitter wrote it, so uchar does not carry this document either
                return markers(scenarios, "document: " + e.getMessage());
            }
            if (rapport.size() != scenarios.size())
                throw new IllegalStateException("het rapport telt " + rapport.size() + " case(s) voor "
                        + scenarios.size() + " scenario('s): een gelagerde case hoort nooit een red-path case te zijn");
            // the build the explanation plays on: the document the replay played, through the same facade
            // (UcharBouw, this distribution's one way to a build). When the replay played no case (the facade
            // refused the document, every report is MARKER) there is nothing to explain and no build to make.
            boolean gespeeld = rapport.stream().anyMatch(r -> r.status() != ScenarioResultaat.Status.MARKER);
            DroolsBackend.Build build = metUitleg && gespeeld ? UcharBouw.bouw(JSON.readTree(document),
                    DroolsBackend.Branch.EXEC_MODEL) : null;
            List<Uitgelegd> uit = new ArrayList<>();
            for (int i = 0; i < scenarios.size(); i++) {
                ScenarioResultaat r = rapport.get(i);
                ScenarioResultaat benoemd = new ScenarioResultaat(scenarios.get(i).naam(), r.status(),
                        r.verwachtingen(), r.trace(), r.dekking(), r.diagnose());
                uit.add(metUitleg ? uitleg(build, benoemd, JSON.readTree(Files.readString(cases.get(i))), vocabulaire,
                        irModel) : new Uitgelegd(benoemd, List.of(), null, null, null));
            }
            return uit;
        } catch (IOException e) {
            throw new UncheckedIOException("kon de casebestanden niet schrijven in " + map, e);
        } finally {
            verwijder(map);
        }
    }

    /** Every scenario as MARKER with {@code reden}: the document is accepted by the frontend and not run. */
    private static List<Uitgelegd> markers(List<ResolvedScenario> scenarios, String reden) {
        List<Uitgelegd> uit = new ArrayList<>();
        for (ResolvedScenario s : scenarios)
            uit.add(new Uitgelegd(new ScenarioResultaat(s.naam(), ScenarioResultaat.Status.MARKER, List.of(),
                    new ScenarioResultaat.Trace(List.of()), List.of(), reden), List.of(), null, null, null));
        return uit;
    }

    /** The explanation of report {@code r}, whose case is {@code c}: the case played in a session of its own on
     *  {@code build}, checked against what uchar's replay reported, then verbalised in {@code vocabulaire}. The build's
     *  model is the one uchar's loader read from the IR document, which does not carry uchar's table of origins;
     *  {@code herkomst}, the frontend's model the document was emitted from, does, and tells the rules uchar wrote. */
    private static Uitgelegd uitleg(DroolsBackend.Build build, ScenarioResultaat r, JsonNode c, String vocabulaire,
                                    RegelSpraakModel herkomst) {
        if (r.status() == ScenarioResultaat.Status.MARKER) return new Uitgelegd(r, List.of(), null, null, null);
        if (r.verwachtingen().isEmpty() && r.status() == ScenarioResultaat.Status.FAIL)
            return nietUitgespeeld(build, r, c);
        CaseSessie.Uitkomst sessie;
        try {
            sessie = CaseSessie.speel(build, c);
        } catch (CaseSessie.NietSpeelbaar e) {
            return new Uitgelegd(r, List.of(), "de uitleg speelt deze case niet: " + e.getMessage(), null,
                    "de eigen sessie speelt deze case niet: " + e.getMessage());
        }
        // uchar's replay wrote back without a throw, so no fault stood at its fixpoint
        if (!sessie.fixpunt().fouten().isEmpty()) {
            String z = "de sessie van de uitleg kwam op een evaluatiefout die uchars replay niet gaf: "
                    + sessie.fixpunt().fouten().get(0).melding();
            return new Uitgelegd(r, List.of(), z, null, z);
        }
        if (!sessie.vuringen().equals(r.trace().vuringen())) {
            String z = "de sessie van de uitleg vuurde " + sessie.vuringen() + ", uchars replay "
                    + r.trace().vuringen();
            return new Uitgelegd(r, List.of(), z, null, z);
        }
        String verschil = waardeVerschil(sessie, r, c);
        if (verschil != null) return new Uitgelegd(r, List.of(), verschil, null, verschil);
        return new Uitgelegd(r, new Verbalisator(build.model(), sessie.globals(),
                Naamgeving.van(build.model(), vocabulaire), herkomst)
                .verbaliseer(sessie.beans(), sessie.typePerLabel()), null, sessie, null);
    }

    /** A case uchar's replay did not play out, its play raised an exception: no explanation, and the session played
     *  for its fixpoint. That session is given when evaluation faults stand at the fixpoint, which the write-back of
     *  the replay throws; a session that reaches its fixpoint without one did not run as the replay ran. The session
     *  raises what the replay raised where the replay raised it before the write-back, and that is its reason. */
    private static Uitgelegd nietUitgespeeld(DroolsBackend.Build build, ScenarioResultaat r, JsonNode c) {
        String geenUitleg = "uchars replay speelde de case niet uit: " + r.diagnose();
        CaseSessie.Uitkomst sessie;
        try {
            sessie = CaseSessie.speel(build, c);
        } catch (CaseSessie.NietSpeelbaar e) {
            return new Uitgelegd(r, List.of(), geenUitleg, null,
                    "de eigen sessie speelt deze case niet: " + e.getMessage());
        } catch (RuntimeException e) {
            // uchar's report names the same failure as this case's FAIL; the session reports it as well
            return new Uitgelegd(r, List.of(), geenUitleg, null,
                    "de eigen sessie speelde de case evenmin uit: " + e.getClass().getSimpleName() + ": "
                            + e.getMessage());
        }
        if (sessie.fixpunt().fouten().isEmpty())
            return new Uitgelegd(r, List.of(), geenUitleg, null, "de eigen sessie kwam zonder evaluatiefout op haar"
                    + " fixpunt, waar uchars replay de case niet uitspeelde");
        return new Uitgelegd(r, List.of(), geenUitleg, sessie, null);
    }

    /** The first attribute or characteristic expectation whose value in the explanation's session differs from the
     *  value uchar's replay read, rendered as the replay renders it; null when none differs. */
    private static String waardeVerschil(CaseSessie.Uitkomst sessie, ScenarioResultaat r, JsonNode c) {
        for (ScenarioResultaat.VerwachtingResultaat v : r.verwachtingen()) {
            JsonNode e = c.get("expected").get(v.positie() - 1);
            String kind = e.get("kind").asText();
            Object bean = e.hasNonNull("label") ? sessie.beans().get(e.get("label").asText()) : null;
            String eigen = switch (kind) {
                case "attributeValue", "attributeEmpty" -> {
                    Object w = bean == null ? null : CaseSessie.lees(bean, Namen.getter(e.get("attribute").asText()));
                    yield w == null ? "leeg" : w.toString();
                }
                case "characteristicPresent", "characteristicAbsent" -> bean != null
                        && Boolean.TRUE.equals(CaseSessie.lees(bean, Namen.getter(e.get("characteristic").asText())))
                        ? "aanwezig" : "afwezig";
                default -> null;
            };
            if (eigen != null && !eigen.equals(v.werkelijk()))
                return "de sessie van de uitleg las bij verwachting " + v.positie() + " (" + v.bron() + ") "
                        + eigen + ", uchars replay " + v.werkelijk();
        }
        return null;
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
