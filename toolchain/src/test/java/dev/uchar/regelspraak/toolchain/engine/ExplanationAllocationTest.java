package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An allocation writes the share of each recipient and the undivided rest of its source; an ordinary rule on the same
 * target is a writer of the same rank (uchar's RUNTIME-SEMANTICS ch. 7.8). Share and rest get the sentence an ordinary
 * rule gets, with the allocation as their writer and how it divided as its computation, beside the other writers of
 * the target. The explanation reads what a member holds at the fixpoint from uchar's reading of the session
 * ({@code DroolsBackend.standingOf}), where a derived value is told apart from the input, and never from the bean after
 * the write-back, where the two are one.
 */
class ExplanationAllocationTest {

    private static final Path GOLDEN = Path.of("../uchar/corpus/golden");

    /** An allocation of 10 in equal shares over three meldingen, beside a rule on the share and one on the rest
     *  whose conditions do not hold: the shares are 3 and the rest 1, which the input did not give. */
    static final String BESIDE_A_RULE = """
            Objecttype de Wrakvondst (mv: Wrakvondsten)
                het meldgeld Numeriek (geheel getal);
                de meldrest Numeriek (geheel getal);
                de diepte Numeriek (geheel getal);

            Objecttype de Melding (mv: Meldingen)
                de ernst Numeriek (geheel getal);
                het meldaandeel (mv: meldaandelen) Numeriek (geheel getal);

            Feittype vondst heeft melding
                de vondst Wrakvondst
                de melding (mv: meldingen) Melding
            Eén vondst heeft meerdere meldingen

            Regel verdeel het meldgeld
                geldig altijd
                    Het meldgeld van een Wrakvondst wordt verdeeld over de meldaandelen van alle meldingen van de
                    Wrakvondst, waarbij wordt verdeeld:
                    - in gelijke delen,
                    - afgerond op 0 decimalen naar beneden.
                    Als onverdeelde rest blijft de meldrest van de Wrakvondst over.

            Regel vast meldaandeel
                geldig altijd
                    Het meldaandeel van een Melding moet gesteld worden op 99
                    indien zijn ernst groter is dan 10.

            Regel vaste rest
                geldig altijd
                    De meldrest van een Wrakvondst moet gesteld worden op 77
                    indien zijn diepte groter is dan 10.

            Scenario verdeling naast een regel
            Gegeven is de volgende situatie:
            een Wrakvondst W
            - het meldgeld is 10
            - de diepte is 1
            een Melding M1
            - de vondst is W
            - de ernst is 4
            een Melding M2
            - de vondst is W
            - de ernst is 5
            een Melding M3
            - de vondst is W
            - de ernst is 1
            Met als verwacht resultaat:
            - het meldaandeel van M1 is 3
            - de meldrest van W is 1
            """;

    private static final String DIVIDED = ": W verdeelde zijn meldgeld (10) in gelijke delen, afgerond op 0"
            + " decimalen naar beneden, over M1 (3), M2 (3) en M3 (3) (3 leden)";

    @Test
    void a_value_the_allocation_derived_is_not_the_input() {
        assertEquals(List.of(
                "W heeft als meldrest 1 volgens de regel 'verdeel het meldgeld' (onvoorwaardelijk)" + DIVIDED + "; de"
                        + " regel 'vaste rest' gold niet, want niet geldt: zijn diepte (1) is groter dan 10",
                "M1 heeft als meldaandeel 3 volgens de regel 'verdeel het meldgeld' (onvoorwaardelijk)" + DIVIDED
                        + "; de regel 'vast meldaandeel' gold niet, want niet geldt: zijn ernst (4) is groter dan 10",
                "M2 heeft als meldaandeel 3 volgens de regel 'verdeel het meldgeld' (onvoorwaardelijk)" + DIVIDED
                        + "; de regel 'vast meldaandeel' gold niet, want niet geldt: zijn ernst (5) is groter dan 10",
                "M3 heeft als meldaandeel 3 volgens de regel 'verdeel het meldgeld' (onvoorwaardelijk)" + DIVIDED
                        + "; de regel 'vast meldaandeel' gold niet, want niet geldt: zijn ernst (1) is groter dan 10"),
                explanation(BESIDE_A_RULE));
    }

    /** The parts of the sentence of a share: the allocation is a RULE, how it divided a COMPUTATION with the source,
     *  its amount as a READING and the recipients as MEMBERS, each with the share it holds. */
    @Test
    void the_share_has_the_parts_of_a_computed_value() {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(BESIDE_A_RULE));
        Sentence m1 = u.get(0).sentences().get(1);
        assertEquals(List.of(Part.Kind.SUBJECT, Part.Kind.TEXT, Part.Kind.NAME, Part.Kind.TEXT, Part.Kind.VALUE,
                        Part.Kind.TEXT, Part.Kind.RULE, Part.Kind.TEXT, Part.Kind.COMPUTATION, Part.Kind.VARIANT),
                m1.parts().stream().map(Part::kind).toList(), m1.text());
        Part computation = m1.parts().get(8);
        assertEquals(List.of(Part.Kind.TEXT, Part.Kind.SUBJECT, Part.Kind.TEXT, Part.Kind.NAME, Part.Kind.READING,
                        Part.Kind.TEXT, Part.Kind.MEMBERS),
                computation.parts().stream().map(Part::kind).toList(), computation.toString());
        assertEquals("W", computation.parts().get(3).meta().get("object"), computation.toString());
        assertEquals(3, computation.parts().get(6).meta().get("members"), computation.toString());
    }

    /** uchar's golden jutterswet-invoer-naast-verdeling with the situation of its first case: the allocation
     *  writes its rest over the input of G1 and the share of B1 over its input; without recipients it writes no rest
     *  and the input of G2 stands; the rule and the allocation agree on B2; the input of B4 and B5 stands. */
    @Test
    void on_the_golden_the_share_over_an_input_is_no_input_value() throws IOException {
        List<String> sentences = explanation(Files.readString(GOLDEN.resolve(
                "jutterswet-invoer-naast-verdeling/source.rgs")) + INPUT_BESIDE_ALLOCATION);
        String rule = "de regel 'verdeel de opbrengst van een berging' (onvoorwaardelijk)";
        String g1 = ": G1 verdeelde zijn opbrengst (101) in gelijke delen, afgerond op 0 decimalen naar beneden, over"
                + " B1 (50) en B2 (50) (2 leden)";
        assertEquals(List.of(
                "G1 heeft als onverdeelde rest 1 volgens " + rule + g1,
                "G2 houdt als onverdeelde rest de invoerwaarde 9, want de regel 'verdeel de opbrengst van een berging'"
                        + " gaf geen waarde (onvoorwaardelijk) en besliste niets: G2 verdeelde zijn opbrengst (50) in"
                        + " gelijke delen, afgerond op 0 decimalen naar beneden, over geen enkel lid",
                "B1 heeft als bergingsaandeel 50 volgens " + rule + g1 + "; de regel 'vast bergingsaandeel' gaf geen"
                        + " waarde en besliste niets",
                "B2 heeft als bergingsaandeel 50 volgens de regel 'vast bergingsaandeel': zijn vaste aandeel (50), omdat"
                        + " het onderwerp vast is, en volgens " + rule + g1,
                "B3 heeft als bergingsaandeel 30 volgens de regel 'vast bergingsaandeel': zijn vaste aandeel (30), omdat"
                        + " het onderwerp vast is",
                "B4 houdt als bergingsaandeel de invoerwaarde 7, want de regel 'vast bergingsaandeel' gaf geen waarde en"
                        + " besliste niets: zijn vaste aandeel (leeg), omdat het onderwerp vast is",
                "B5 houdt als bergingsaandeel de invoerwaarde 7, want geen regel gold; de regel 'vast bergingsaandeel'"
                        + " gold niet, want niet geldt: het onderwerp vast is"),
                sentences);
    }

    /** uchar's golden jutterswet-wrakmelding, its first case: the meldgeld of W is divided over its three meldingen
     *  and leaves a rest; X has no meldingen, so its meldgeld goes to no member and it gets no rest. */
    @Test
    void on_the_golden_a_share_and_a_rest_get_their_sentence() throws IOException {
        Path golden = GOLDEN.resolve("jutterswet-wrakmelding");
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(
                Files.readString(golden.resolve("source.rgs")) + "\n" + Files.readString(golden.resolve("scenario.rgs"))));
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        String rule = " volgens de regel 'verdeel het meldgeld' (onvoorwaardelijk): W verdeelde zijn meldgeld (10) in"
                + " gelijke delen, afgerond op 0 decimalen naar beneden, over M1 (3), M2 (3) en M3 (3) (3 leden)";
        assertEquals(List.of(
                "W heeft als meldrest 1" + rule,
                "X heeft geen meldrest, want de regel 'verdeel het meldgeld' gaf geen waarde (onvoorwaardelijk): X"
                        + " verdeelde zijn meldgeld (4) in gelijke delen, afgerond op 0 decimalen naar beneden, over geen"
                        + " enkel lid",
                "M1 heeft als meldaandeel 3" + rule,
                "M2 heeft als meldaandeel 3" + rule,
                "M3 heeft als meldaandeel 3" + rule),
                u.get(0).explanation().stream().filter(t -> t.contains("'verdeel het meldgeld'")).toList());
    }

    /** The situation of case1-invoer of uchar's golden jutterswet-invoer-naast-verdeling, as a scenario. */
    static final String INPUT_BESIDE_ALLOCATION = """

            Scenario invoer
            Gegeven is de volgende situatie:
            een Berging G1
            - de opbrengst is 101
            - de onverdeelde rest is 9
            een Berging G2
            - de opbrengst is 50
            - de onverdeelde rest is 9
            een Berger B1
            - de berging is G1
            - is vast
            - het bergingsaandeel is 7
            een Berger B2
            - de berging is G1
            - is vast
            - het vaste aandeel is 50
            - het bergingsaandeel is 7
            een Berger B3
            - is vast
            - het vaste aandeel is 30
            - het bergingsaandeel is 7
            een Berger B4
            - is vast
            - het bergingsaandeel is 7
            een Berger B5
            - het vaste aandeel is 30
            - het bergingsaandeel is 7
            Met als verwacht resultaat:
            - de onverdeelde rest van G1 is 1
            - de onverdeelde rest van G2 is 9
            - het bergingsaandeel van B1 is 50
            - het bergingsaandeel van B2 is 50
            - het bergingsaandeel van B3 is 30
            - het bergingsaandeel van B4 is 7
            - het bergingsaandeel van B5 is 7
            """;

    static List<String> explanation(String document) {
        var bundle = ScenarioResolver.resolve(document);
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(bundle);
        assertEquals(1, u.size(), () -> bundle.model().errors().toString());
        assertEquals(Status.PASS, u.get(0).result().status(),
                () -> u.get(0).result().diagnostic() + " " + u.get(0).result().expectations());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        return u.get(0).explanation();
    }
}
