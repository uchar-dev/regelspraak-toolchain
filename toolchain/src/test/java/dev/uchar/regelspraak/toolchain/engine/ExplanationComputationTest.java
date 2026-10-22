package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Card explanation-computed-attributes on the scenario route: every value an ordinary rule writes gets one sentence per
 * object and attribute, after the sentences of the defaults groups, from the engine's readings of the session uchar's
 * replay judged. The model and the sentences are those of {@code ExplanationComputationServiceTest}, which gives the
 * same sentences on both starts of the decision service; here the situation and the parameters stand in the scenario.
 * A rule on a timeline target says that it wrote a timeline, without the computation.
 */
class ExplanationComputationTest {

    static final String MODEL = """
            Parameter het percentage vindersloon : Percentage (getal met 1 decimalen)
            Parameter het premiemaximum : Numeriek (getal met 2 decimalen)

            Objecttype de Jutter (mv: Jutters) (bezield)
                de startdag Datum in dagen;
                de meldtermijn Datum in dagen;
                de eerste waardevolle vinddag Datum in dagen;
                de vondstwaarde Numeriek (getal met 2 decimalen);
                de premie Numeriek (getal met 2 decimalen);
                de bonus Numeriek (getal met 2 decimalen);
                het tarief Numeriek (getal met 2 decimalen);
                de toelage Numeriek (getal met 2 decimalen);
                het havengeld Numeriek (getal met 2 decimalen);
                is ingezetene kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                de vinddag Datum in dagen;
                de meldingsdag Datum in dagen;
                de waarde Numeriek (getal met 2 decimalen);
                het vindersloon Numeriek (getal met 2 decimalen);
                de wachttijd Numeriek (geheel getal) met eenheid dagen;
                de jutterstartdag Datum in dagen;
                is waardevol kenmerk (bijvoeglijk);

            Feittype JutterHeeftVondst
                de jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén jutter heeft meerdere vondsten

            Regel waardevol
                geldig altijd
                    Een Vondst is waardevol
                    indien zijn waarde groter is dan 100,00.

            // Bron: Jutterswet art. 10
            Regel vindersloon
                geldig altijd
                    Het vindersloon van een Vondst moet berekend worden als (het percentage vindersloon van zijn waarde) naar beneden afgerond op 2 decimalen
                    indien zijn waarde groter is dan 0.

            Regel wachttijd
                geldig altijd
                    De wachttijd van een Vondst moet berekend worden als de tijdsduur van zijn vinddag tot zijn meldingsdag in hele dagen.

            Regel jutterstartdag
                geldig altijd
                    De jutterstartdag van een Vondst moet gesteld worden op de startdag van de jutter van de Vondst.

            Regel meldtermijn
                geldig altijd
                    De meldtermijn van een Jutter moet berekend worden als zijn startdag plus 14 dagen.

            Regel eerste waardevolle vinddag
                geldig altijd
                    De eerste waardevolle vinddag van een Jutter moet berekend worden als de eerste van de vinddag van alle vondsten van de Jutter die waardevol zijn.

            Regel vondstwaarde
                geldig altijd
                    De vondstwaarde van een Jutter moet berekend worden als de som van de waarde van alle vondsten van de Jutter.

            // Bron: Jutterswet art. 11
            Regel premie
                geldig altijd
                    De premie van een Jutter moet berekend worden als de grondpremie maal 2, met een maximum van het premiemaximum
                    indien hij ingezetene is.
                    Daarbij geldt:
                        de grondpremie is de vondstwaarde van de Jutter gedeeld door 10.

            Regel bonus voor ingezetenen
                geldig altijd
                    De bonus van een Jutter moet gesteld worden op 25,00
                    indien hij ingezetene is.

            Regel bonus zonder voorwaarde
                geldig altijd
                    De bonus van een Jutter moet geïnitialiseerd worden op 5,00.

            Regel toelage
                geldig altijd
                    De toelage van een Jutter moet gesteld worden op zijn tarief
                    indien hij ingezetene is.

            Regel havengeld
                geldig vanaf 2015 t/m 2020
                    Het havengeld van een Jutter moet gesteld worden op 1,00.
                geldig vanaf 2021
                    Het havengeld van een Jutter moet gesteld worden op 2,00.
            """;

    private static final String SCENARIO = """

            Parameterset vindersloon:
            de parameter het percentage vindersloon is 12,5 %
            de parameter het premiemaximum is 50,00

            Scenario drie jutters
            met peildatum 01-06-2026
            met parameterset vindersloon
            Gegeven is de volgende situatie:
            een Jutter J1
            - de startdag is 05-01-2026
            - is ingezetene
            een Jutter J2
            - de startdag is 01-02-2026
            een Jutter J3
            - de bonus is 7,50
            - de toelage is 3,00
            een Vondst V1
            - de waarde is 123,45
            - de vinddag is 01-02-2026
            - de meldingsdag is 11-02-2026
            - de jutter is J1
            een Vondst V2
            - de waarde is 150,00
            - de vinddag is 01-03-2026
            - de meldingsdag is 04-03-2026
            - de jutter is J1
            een Vondst V3
            - de waarde is 0,00
            - de vinddag is 10-02-2026
            - de meldingsdag is 10-02-2026
            - de jutter is J2
            Met als verwacht resultaat:
            - de premie van J1 is 50,00
            - de bonus van J2 is 5,00
            - het vindersloon van V1 is 15,43
            """;

    private static final String FIRST = "de eerste van de vinddag van alle vondsten van de Jutter die waardevol zijn";
    private static final String SUM = "de som van de waarde van alle vondsten van de Jutter";
    private static final String PREMIE = "de grondpremie maal 2, met een maximum van het premiemaximum";
    private static final String HAVENGELD = "volgens de regel 'havengeld' (onvoorwaardelijk): 2,00; de regel"
            + " 'havengeld' gold niet, want zij valt op de rekendatum buiten haar geldigheid";
    private static final String VINDERSLOON = "(het percentage vindersloon van zijn waarde) naar beneden afgerond op 2"
            + " decimalen";

    /** The sentences of {@code ExplanationComputationServiceTest}. */
    static final List<String> EXPECTED = List.of(
            "J1 heeft als meldtermijn 19-01-2026 volgens de regel 'meldtermijn' (onvoorwaardelijk): zijn startdag plus"
                    + " 14 dagen, met zijn startdag (05-01-2026)",
            "J1 heeft als eerste waardevolle vinddag 01-02-2026 volgens de regel 'eerste waardevolle vinddag'"
                    + " (onvoorwaardelijk): " + FIRST + " (01-02-2026): over V1 (01-02-2026) en V2 (01-03-2026)"
                    + " (2 leden)",
            "J1 heeft als vondstwaarde 273,45 volgens de regel 'vondstwaarde' (onvoorwaardelijk): " + SUM + " (273,45):"
                    + " over V1 (123,45) en V2 (150,00) (2 leden)",
            "J1 heeft als premie 50,00 volgens de regel 'premie': " + PREMIE + ", met de vondstwaarde van de Jutter"
                    + " (273,45), de grondpremie is de vondstwaarde van de Jutter gedeeld door 10 (27,345) en het"
                    + " premiemaximum (50,00); vóór de begrenzing 54,69, omdat"
                    + " het onderwerp ingezetene is. Grondslag: Jutterswet art. 11",
            "J1 heeft als bonus 25,00 volgens de regel 'bonus voor ingezetenen': 25,00, omdat het onderwerp ingezetene"
                    + " is; verdrongen: de regel 'bonus zonder voorwaarde'",
            "J1 heeft geen toelage, want de regel 'toelage' gaf geen waarde: zijn tarief (onbekend), omdat het"
                    + " onderwerp ingezetene is",
            "J1 heeft als havengeld 2,00 " + HAVENGELD,
            "J2 heeft als meldtermijn 15-02-2026 volgens de regel 'meldtermijn' (onvoorwaardelijk): zijn startdag plus"
                    + " 14 dagen, met zijn startdag (01-02-2026)",
            "J2 heeft geen eerste waardevolle vinddag, want de regel 'eerste waardevolle vinddag' gaf geen waarde"
                    + " (onvoorwaardelijk): " + FIRST + " (onbekend): er is geen enkel lid",
            "J2 heeft als vondstwaarde 0,00 volgens de regel 'vondstwaarde' (onvoorwaardelijk): " + SUM + " (0,00):"
                    + " over V3 (0,00), het enige lid",
            "J2 heeft geen premie, want geen regel gold; de regel 'premie' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is",
            "J2 heeft als bonus 5,00 volgens de regel 'bonus zonder voorwaarde': 5,00, omdat de bonus in de invoer leeg"
                    + " was; de regel 'bonus voor ingezetenen' gold niet, want niet geldt: het onderwerp ingezetene is",
            "J2 heeft geen toelage, want geen regel gold; de regel 'toelage' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is",
            "J2 heeft als havengeld 2,00 " + HAVENGELD,
            "J3 heeft geen meldtermijn, want de regel 'meldtermijn' gaf geen waarde (onvoorwaardelijk): zijn startdag"
                    + " plus 14 dagen, met zijn startdag (onbekend)",
            "J3 heeft geen eerste waardevolle vinddag, want de regel 'eerste waardevolle vinddag' gaf geen waarde"
                    + " (onvoorwaardelijk): " + FIRST + " (onbekend): er is geen enkel lid",
            "J3 heeft geen vondstwaarde, want de regel 'vondstwaarde' gaf geen waarde (onvoorwaardelijk): " + SUM
                    + " (onbekend): er is geen enkel lid",
            "J3 heeft geen premie, want geen regel gold; de regel 'premie' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is",
            "J3 houdt als bonus de invoerwaarde 7,50, want geen regel gold; de regel 'bonus voor ingezetenen' gold"
                    + " niet, want niet geldt: het onderwerp ingezetene is; verdrongen: de regel 'bonus zonder"
                    + " voorwaarde'",
            "J3 houdt als toelage de invoerwaarde 3,00, want geen regel gold; de regel 'toelage' gold niet, want niet"
                    + " geldt: het onderwerp ingezetene is",
            "J3 heeft als havengeld 2,00 " + HAVENGELD,
            "V1 is waardevol, omdat zijn waarde (123,45) is groter dan 100,00",
            "V1 heeft als vindersloon 15,43 volgens de regel 'vindersloon': " + VINDERSLOON + ", met percentage"
                    + " vindersloon (12,5 %) en zijn waarde (123,45); vóór de afronding 15,43125, omdat zijn waarde"
                    + " (123,45) is groter dan 0. Grondslag: Jutterswet art. 10",
            "V1 heeft als wachttijd 10 dag volgens de regel 'wachttijd' (onvoorwaardelijk): de tijdsduur van zijn vinddag"
                    + " tot zijn meldingsdag in hele dagen, met zijn vinddag (01-02-2026) en zijn meldingsdag"
                    + " (11-02-2026)",
            "V1 heeft als jutterstartdag 05-01-2026 volgens de regel 'jutterstartdag' (onvoorwaardelijk): de startdag van"
                    + " de jutter van de Vondst (05-01-2026)",
            "V2 is waardevol, omdat zijn waarde (150,00) is groter dan 100,00",
            "V2 heeft als vindersloon 18,75 volgens de regel 'vindersloon': " + VINDERSLOON + ", met percentage"
                    + " vindersloon (12,5 %) en zijn waarde (150,00); vóór de afronding 18,75, omdat zijn waarde"
                    + " (150,00) is groter dan 0. Grondslag: Jutterswet art. 10",
            "V2 heeft als wachttijd 3 dag volgens de regel 'wachttijd' (onvoorwaardelijk): de tijdsduur van zijn vinddag"
                    + " tot zijn meldingsdag in hele dagen, met zijn vinddag (01-03-2026) en zijn meldingsdag"
                    + " (04-03-2026)",
            "V2 heeft als jutterstartdag 05-01-2026 volgens de regel 'jutterstartdag' (onvoorwaardelijk): de startdag van"
                    + " de jutter van de Vondst (05-01-2026)",
            "V3 is niet waardevol, want niet geldt: zijn waarde (0,00) is groter dan 100,00",
            "V3 heeft geen vindersloon, want geen regel gold; de regel 'vindersloon' gold niet, want niet geldt: zijn"
                    + " waarde (0,00) is groter dan 0",
            "V3 heeft als wachttijd 0 dag volgens de regel 'wachttijd' (onvoorwaardelijk): de tijdsduur van zijn vinddag"
                    + " tot zijn meldingsdag in hele dagen, met zijn vinddag (10-02-2026) en zijn meldingsdag"
                    + " (10-02-2026)",
            "V3 heeft als jutterstartdag 01-02-2026 volgens de regel 'jutterstartdag' (onvoorwaardelijk): de startdag van"
                    + " de jutter van de Vondst (01-02-2026)");

    @Test
    void every_computed_value_gets_a_sentence() {
        assertEquals(EXPECTED, explanation(MODEL + SCENARIO));
    }

    /** A rule on a timeline target writes a timeline: the sentence says so, and gives no computation. */
    @Test
    void a_timeline_target_gets_a_timeline_and_no_computation() {
        String document = """
                Objecttype de Jutter (mv: Jutters) (bezield)
                    is borgplichtig kenmerk (bijvoeglijk) voor elke maand;
                    de borgdagen Numeriek (niet-negatief geheel getal) voor elke maand;

                Regel borgdagen
                    geldig altijd
                        De borgdagen van een Jutter moet berekend worden als het aantal dagen in de maand dat hij borgplichtig is.

                Scenario twee jutters
                Gegeven is de volgende situatie:
                een Jutter J1
                - is borgplichtig van 10-01-2024 tot 14-02-2024
                een Jutter J2
                Met als verwacht resultaat:
                - de borgdagen van J1 is 22 van 01-01-2024 tot 01-02-2024
                - de borgdagen van J1 is 13 van 01-02-2024 tot 01-03-2024
                - de borgdagen van J2 is altijd leeg
                """;
        assertEquals(List.of(
                "J1 heeft als borgdagen een tijdlijn volgens de regel 'borgdagen' (onvoorwaardelijk)",
                "J2 heeft geen borgdagen, want de regel 'borgdagen' gaf geen waarde (onvoorwaardelijk)"),
                explanation(document));
    }

    private static List<String> explanation(String document) {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(document));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        return u.get(0).explanation();
    }
}
