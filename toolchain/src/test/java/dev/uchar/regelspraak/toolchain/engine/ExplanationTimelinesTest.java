package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.transpiler.tms.PeriodDecisions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The explanation of timelines on uchar's goldens, from their case files: a fold over the timelines of members names
 * its members with what each contributed, and a rule with a timeline target names its computation with its operands
 * per period it decided. Everything comes from uchar's reading of the session: the contributors of a fold and the
 * timeline each node of a rule read or computed (RUNTIME-SEMANTICS ch. 5.12), the periods each rule decided
 * ({@code DroolsBackend.periodDecisionsOf}) and the timeline a member holds at the fixpoint ({@code EndState}); the
 * sentence only cuts a read timeline to a period. The scenario layer gives a member no timeline, so the cases are
 * played as uchar's replay plays them, as {@link ScenarioRunner} does with the case files it writes.
 */
class ExplanationTimelinesTest {

    private static final Path GOLDEN = Path.of("../uchar/corpus/golden");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A total over the timelines of the vondsten names each vondst with its total and the timeline it came from;
     *  V3, without a timeline, contributes 0. J2 has no vondsten. */
    @Test
    void a_total_over_member_timelines_names_its_members() throws Exception {
        String rule = " volgens de regel 'bewaarlast van een jutter' (onvoorwaardelijk): het totaal van het"
                + " bewaartarief van alle vondsten van de Jutter";
        assertEquals(List.of(
                "J1 heeft als bewaarlast 48 EUR" + rule + " (48 EUR): over V1 (36 EUR uit 12 EUR/mnd van 01-01-2026"
                        + " tot 01-04-2026), V2 (12 EUR uit 7 EUR/mnd van 01-01-2026 tot 01-02-2026 en 5 EUR/mnd van"
                        + " 01-02-2026 tot 01-03-2026) en V3 (0 EUR) (3 leden met een waarde)",
                "J2 heeft als bewaarlast 0 EUR" + rule + " (0 EUR): geen lid droeg een waarde bij"),
                explanation("jutterswet-tempovouw", "case.json"));
    }

    /** The two readings of "het totaal van" side by side: over a set of scalar members it names its members, as
     *  before; over the subject's own timeline it has no members and names the timeline it totalled. */
    @Test
    void a_total_over_scalar_members_and_an_own_total_stay_as_they_were() throws Exception {
        assertEquals(List.of(
                "J1 heeft als bewaarlast 19 EUR/mnd volgens de regel 'bewaarlast van een jutter' (onvoorwaardelijk):"
                        + " het totaal van het bewaartarief van alle vondsten van de Jutter (19 EUR/mnd): over V1 (12"
                        + " EUR/mnd) en V2 (7 EUR/mnd) (2 leden met een waarde)",
                "J1 heeft als bewaargeld 10 EUR volgens de regel 'bewaargeld van een jutter' (onvoorwaardelijk): het"
                        + " totaal van zijn bewaarvergoeding per maand, met zijn bewaarvergoeding per maand (10 EUR/mnd"
                        + " van 01-03-2026 tot 01-04-2026)"),
                explanation("jutterswet-tempototaal", "case.json").stream()
                        .filter(s -> s.startsWith("J1 heeft als bewaarlast") || s.startsWith("J1 heeft als bewaargeld"))
                        .toList());
    }

    /** A product of two timelines: per period the rule decided, the timeline of each operand in that period. */
    @Test
    void a_rule_on_a_timeline_target_names_its_operands_per_period() throws Exception {
        List<String> sentences = explanation("jutterswet-rekenen-per-dag", "case1-voorbeeld.json");
        assertEquals("J1 heeft als loonproduct 0 tot 01-04-2024, 2.500 van 01-04-2024 tot 01-07-2024 en 0 vanaf"
                        + " 01-07-2024 volgens de regel 'loonproduct van een jutter' (onvoorwaardelijk): zijn strandloon"
                        + " maal zijn stormtoeslag, tot 01-04-2024 met zijn strandloon (100 van 01-01-2024 tot"
                        + " 01-04-2024) en zijn stormtoeslag (leeg); van 01-04-2024 tot 01-07-2024 met zijn strandloon"
                        + " (100) en zijn stormtoeslag (25); vanaf 01-07-2024 met zijn strandloon (leeg) en zijn"
                        + " stormtoeslag (25 van 01-07-2024 tot 01-10-2024)",
                sentences.get(0));
        assertEquals("J1 heeft als nettoloon 100 van 01-01-2024 tot 01-04-2024 en 75 van 01-04-2024 tot 01-07-2024"
                        + " volgens de regel 'nettoloon van een jutter' (onvoorwaardelijk): zijn strandloon verminderd met"
                        + " zijn stormtoeslag, van 01-01-2024 tot 01-04-2024 met zijn strandloon (100) en zijn"
                        + " stormtoeslag (leeg); van 01-04-2024 tot 01-07-2024 met zijn strandloon (100) en zijn"
                        + " stormtoeslag (25)",
                sentences.get(1));
    }

    /** A rule that gave no value on any day names its computation over all time, as a scalar rule does. */
    @Test
    void a_rule_on_a_timeline_target_that_gave_no_value_names_its_computation() throws Exception {
        assertEquals("J1 heeft geen nettoloon, want de regel 'nettoloon van een jutter' gaf geen waarde"
                        + " (onvoorwaardelijk): zijn strandloon verminderd met zijn stormtoeslag, met zijn strandloon"
                        + " (altijd leeg) en zijn stormtoeslag (altijd leeg)",
                explanation("jutterswet-rekenen-per-dag", "case3-zonder-tijdlijnen.json").get(1));
    }

    /** The sum per period over the vondsten, times a timeline of the subject: per period the members that
     *  contributed there, each with its timeline in that period; a scalar operand of a total over member timelines
     *  names its members once. */
    @Test
    void a_fold_per_period_names_the_members_of_each_period() throws Exception {
        List<String> sentences = explanation("jutterswet-rekenen-per-dag", "case7-vondsten.json");
        String sum = "de som van de opbrengst van alle vondsten van de Jutter";
        assertEquals("J1 heeft als vondstheffing 0 EUR/dg tot 15-01-2024, 60 EUR/dg van 15-01-2024 tot 01-02-2024, 180"
                        + " EUR/dg van 01-02-2024 tot 15-02-2024, 120 EUR/dg van 15-02-2024 tot 01-03-2024 en 0 EUR/dg"
                        + " vanaf 01-03-2024 volgens de regel 'vondstheffing van een jutter' (onvoorwaardelijk): (" + sum
                        + ") maal zijn afdrachttarief, tot 15-01-2024 met " + sum + " (leeg): geen lid droeg een waarde"
                        + " bij en zijn afdrachttarief (2 1/dg van 01-01-2024 tot 15-01-2024); van 15-01-2024 tot"
                        + " 01-02-2024 met " + sum + " (30 EUR): over V2 (30 EUR), het enige lid met een waarde en zijn"
                        + " afdrachttarief (2 1/dg); van 01-02-2024 tot 15-02-2024 met " + sum + " (90 EUR): over V1 (60"
                        + " EUR) en V2 (30 EUR) (2 leden met een waarde) en zijn afdrachttarief (2 1/dg); van 15-02-2024"
                        + " tot 01-03-2024 met " + sum + " (60 EUR): over V1 (60 EUR), het enige lid met een waarde en"
                        + " zijn afdrachttarief (2 1/dg); vanaf 01-03-2024 met " + sum + " (60 EUR van 01-03-2024 tot"
                        + " 01-04-2024): over V1 (60 EUR van 01-03-2024 tot 01-04-2024), het enige lid met een waarde"
                        + " en zijn afdrachttarief (leeg)",
                sentence(sentences, "J1 heeft als vondstheffing "));
        assertEquals("J1 heeft als opbrengstheffing 9.060 EUR/dg volgens de regel 'opbrengstheffing van een jutter'"
                        + " (onvoorwaardelijk): (het totaal van de opbrengst van alle vondsten van de Jutter) maal zijn"
                        + " heffingsfactor, met het totaal van de opbrengst van alle vondsten van de Jutter (4.530 EUR):"
                        + " over V1 (3.600 EUR uit 60 EUR van 01-02-2024 tot 01-04-2024) en V2 (930 EUR uit 30 EUR van"
                        + " 15-01-2024 tot 15-02-2024) (2 leden met een waarde) en zijn heffingsfactor (2 1/dg)",
                sentence(sentences, "J1 heeft als opbrengstheffing "));
    }

    private static String sentence(List<String> sentences, String start) {
        List<String> found = sentences.stream().filter(s -> s.startsWith(start)).toList();
        assertEquals(1, found.size(), () -> start + " in " + sentences);
        return found.get(0);
    }

    /** What the replay handed over of the session of one case, read before the write-back. */
    private record Handed(Map<String, Object> labels, EndState standing, List<DefaultsDecision> decisions,
                          List<PeriodDecisions> periodDecisions, List<ConditionReading> readings) {}

    /** The explanation of case file {@code caseFile} of golden {@code golden}, of the session uchar's replay played
     *  on a build with readings, in the canonical names, verbalised after the replay as {@link ScenarioRunner} does. */
    private static List<String> explanation(String golden, String caseFile) throws Exception {
        Path ir = GOLDEN.resolve(golden).resolve("ir.json");
        Path c = GOLDEN.resolve(golden).resolve(caseFile);
        List<Handed> handed = new ArrayList<>();
        CaseRunner.Handoff handoff = (file, build, session, labels, firing) -> {
            if (firing.noFixpoint() != null) throw new IllegalStateException(firing.noFixpoint());
            Map<Object, String> labelOf = new IdentityHashMap<>();
            labels.forEach((label, bean) -> labelOf.put(bean, label));
            handed.add(new Handed(labels, DroolsBackend.standingOf(build, session, labelOf::get),
                    DroolsBackend.decisionsOf(build, session), DroolsBackend.periodDecisionsOf(build, session),
                    DroolsBackend.readingsOf(build, session)));
        };
        List<ScenarioResult> report = CaseRunner.report(ir, List.of(c), handoff);
        assertEquals(ScenarioResult.Status.PASS, report.get(0).status(), report.get(0)::diagnostic);
        assertEquals(1, handed.size(), "the replay handed over one session");
        Handed h = handed.get(0);
        assertEquals(List.of(), h.standing().faults(), "a fault stands at the fixpoint");
        Verbaliser.Document document = Verbaliser.Document.of(IrLoader.loadWithPositions(
                JSON.readTree(Files.readString(ir))));
        RegelSpraakModel model = document.model();
        Map<String, RegelSpraakModel.ObjectType> types = new LinkedHashMap<>();
        for (JsonNode i : JSON.readTree(Files.readString(c)).get("input").get("instances"))
            types.put(i.get("label").asText(), model.objectTypeByName(i.get("objectType").asText()));
        return new Verbaliser(document, Naming.of(model, Naming.CANONICAL), h.readings(), h.standing())
                .verbalise(h.labels(), types, h.decisions(), h.periodDecisions());
    }
}
