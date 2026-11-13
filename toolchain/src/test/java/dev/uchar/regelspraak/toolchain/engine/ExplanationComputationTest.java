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
                    De premie van een Jutter moet berekend worden als de grondpremie maal 2, met een maximum van het premiemaximum rekenkundig afgerond op 2 decimalen
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
    private static final String PREMIE = "de grondpremie maal 2, met een maximum van het premiemaximum rekenkundig"
            + " afgerond op 2 decimalen";
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
                    + " (2 leden met een waarde)",
            "J1 heeft als vondstwaarde 273,45 volgens de regel 'vondstwaarde' (onvoorwaardelijk): " + SUM + " (273,45):"
                    + " over V1 (123,45) en V2 (150,00) (2 leden met een waarde)",
            "J1 heeft als premie 50,00 volgens de regel 'premie': " + PREMIE + ", met de vondstwaarde van de Jutter"
                    + " (273,45), de grondpremie is de vondstwaarde van de Jutter gedeeld door 10 (27,345) en het"
                    + " premiemaximum (50,00); vóór de afronding 50; vóór de begrenzing 54,69, omdat"
                    + " het onderwerp ingezetene is. Grondslag: Jutterswet art. 11",
            "J1 heeft als bonus 25,00 volgens de regel 'bonus voor ingezetenen': 25,00, omdat het onderwerp ingezetene"
                    + " is; verdrongen: de regel 'bonus zonder voorwaarde'",
            "J1 heeft geen toelage, want de regel 'toelage' gaf geen waarde: zijn tarief (leeg), omdat het"
                    + " onderwerp ingezetene is",
            "J1 heeft als havengeld 2,00 " + HAVENGELD,
            "J2 heeft als meldtermijn 15-02-2026 volgens de regel 'meldtermijn' (onvoorwaardelijk): zijn startdag plus"
                    + " 14 dagen, met zijn startdag (01-02-2026)",
            "J2 heeft geen eerste waardevolle vinddag, want de regel 'eerste waardevolle vinddag' gaf geen waarde"
                    + " (onvoorwaardelijk): " + FIRST + " (leeg): geen lid droeg een waarde bij",
            "J2 heeft als vondstwaarde 0,00 volgens de regel 'vondstwaarde' (onvoorwaardelijk): " + SUM + " (0,00):"
                    + " over V3 (0,00), het enige lid met een waarde",
            "J2 heeft geen premie, want geen regel gold; de regel 'premie' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is",
            "J2 heeft als bonus 5,00 volgens de regel 'bonus zonder voorwaarde': 5,00, omdat de bonus in de invoer leeg"
                    + " was; de regel 'bonus voor ingezetenen' gold niet, want niet geldt: het onderwerp ingezetene is",
            "J2 heeft geen toelage, want geen regel gold; de regel 'toelage' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is",
            "J2 heeft als havengeld 2,00 " + HAVENGELD,
            "J3 heeft geen meldtermijn, want de regel 'meldtermijn' gaf geen waarde (onvoorwaardelijk): zijn startdag"
                    + " plus 14 dagen, met zijn startdag (leeg)",
            "J3 heeft geen eerste waardevolle vinddag, want de regel 'eerste waardevolle vinddag' gaf geen waarde"
                    + " (onvoorwaardelijk): " + FIRST + " (leeg): geen lid droeg een waarde bij",
            "J3 heeft geen vondstwaarde, want de regel 'vondstwaarde' gaf geen waarde (onvoorwaardelijk): " + SUM
                    + " (leeg): geen lid droeg een waarde bij",
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

    /** A rule on a timeline target writes a timeline: the sentence names the periods it decided with their values, as
     *  uchar names them per period ({@code DroolsBackend.periodDecisionsOf}), and its computation. The day count
     *  decides every day (uchar's RUNTIME-SEMANTICS ch. 3.1, 3.9): a month without a true day counts 0, open at both
     *  ends, so a jutter that is never borgplichtig has the timeline 0 on all time, not an empty one. */
    @Test
    void a_timeline_target_gets_its_periods_and_its_computation() {
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
                - de borgdagen van J1 is 0 tot 01-01-2024
                - de borgdagen van J1 is 22 van 01-01-2024 tot 01-02-2024
                - de borgdagen van J1 is 13 van 01-02-2024 tot 01-03-2024
                - de borgdagen van J1 is 0 vanaf 01-03-2024
                - de borgdagen van J2 is altijd 0
                """;
        assertEquals(List.of(
                "J1 heeft als borgdagen 0 tot 01-01-2024, 22 van 01-01-2024 tot 01-02-2024, 13 van 01-02-2024 tot"
                        + " 01-03-2024 en 0 vanaf 01-03-2024 volgens de regel 'borgdagen' (onvoorwaardelijk): het aantal"
                        + " dagen in de maand dat hij borgplichtig is",
                "J2 heeft als borgdagen altijd 0 volgens de regel 'borgdagen' (onvoorwaardelijk): het aantal dagen in"
                        + " de maand dat hij borgplichtig is"),
                explanation(document));
    }

    /** The total of a timeline of the subject is no fold over a set: it has no members. The engine reads the timeline
     *  under it whole (RUNTIME-SEMANTICS ch. 5.12), here one the period arithmetic of a time-dependent parameter
     *  wrote, with a 0 open before and after the parameter's periods (ch. 3.4), and the explanation writes it as
     *  RegelSpraak writes periods. The bootheffing names per period it decided the havenheffing in that period, and
     *  the aantal boten, which it read as one value, once. */
    @Test
    void a_total_over_a_timeline_names_its_periods_and_no_members() {
        String document = """
                Parameter de havenheffing : Numeriek (getal met 2 decimalen) voor elke maand;

                Objecttype de Jutter (mv: Jutters) (bezield)
                    het aantal boten Numeriek (geheel getal);
                    de bootheffing Numeriek (getal met 2 decimalen) voor elke maand;
                    de totale bootheffing Numeriek (getal met 2 decimalen);

                Regel bootheffing
                    geldig altijd
                        De bootheffing van een Jutter moet berekend worden als de havenheffing maal zijn aantal boten.

                Regel totale bootheffing
                    geldig altijd
                        De totale bootheffing van een Jutter moet berekend worden als (het totaal van zijn bootheffing) rekenkundig afgerond op 2 decimalen.

                Scenario twee boten
                Gegeven is de volgende situatie:
                de parameter de havenheffing is 2,50 van 01-12-2023 tot 01-01-2024
                de parameter de havenheffing is 3,00 van 01-01-2024 tot 01-03-2024
                een Jutter J1
                - het aantal boten is 2
                Met als verwacht resultaat:
                - de bootheffing van J1 is 0 tot 01-12-2023
                - de bootheffing van J1 is 5,00 van 01-12-2023 tot 01-01-2024
                - de bootheffing van J1 is 6,00 van 01-01-2024 tot 01-03-2024
                - de bootheffing van J1 is 0 vanaf 01-03-2024
                - de totale bootheffing van J1 is 17,00
                """;
        assertEquals(List.of(
                "J1 heeft als bootheffing 0,00 tot 01-12-2023, 5,00 van 01-12-2023 tot 01-01-2024, 6,00 van 01-01-2024"
                        + " tot 01-03-2024 en 0,00 vanaf 01-03-2024 volgens de regel 'bootheffing' (onvoorwaardelijk):"
                        + " de havenheffing maal zijn aantal boten, met zijn aantal boten (2); tot 01-12-2023 met de"
                        + " havenheffing (leeg); van 01-12-2023 tot 01-01-2024 met de havenheffing (2,50); van 01-01-2024"
                        + " tot 01-03-2024 met de havenheffing (3,00); vanaf 01-03-2024 met de havenheffing (leeg)",
                "J1 heeft als totale bootheffing 17,00 volgens de regel 'totale bootheffing' (onvoorwaardelijk): (het"
                        + " totaal van zijn bootheffing) rekenkundig afgerond op 2 decimalen, met het totaal van zijn"
                        + " bootheffing (17,00) en zijn bootheffing (0,00 tot 01-12-2023, 5,00 van 01-12-2023 tot"
                        + " 01-01-2024, 6,00 van 01-01-2024 tot 01-03-2024 en 0,00 vanaf 01-03-2024); vóór de afronding"
                        + " 17"),
                explanation(document));
    }

    /** The timeline the day count, the binder and the Let of the gedeeltelijke maand have alike (ch. 5.12). */
    private static final String COUNTED = "0 tot 01-11-2025, 15 van 01-11-2025 tot 01-12-2025, 12 van 01-03-2026 tot"
            + " 01-04-2026 en 0 vanaf 01-04-2026";

    /** A time-dependent characteristic a rule writes within a window holds in periods (RUNTIME-SEMANTICS ch. 3.10,
     *  3.11), and the sentence names them. Two rules write one timeline on their own days, each where its condition per
     *  period holds, in the periods the engine read, and the sentence gives each rule the periods it decided with their
     *  values (uchar's {@code DroolsBackend.periodDecisionsOf}); the one whose value is a binder of
     *  {@code Daarbij geldt} wrote as well (ch. 10.6: uchar sees such a Let through and reads the value at its definition). This explanation was an
     *  {@code IllegalStateException} on the pin of uchar that carried these forms first. */
    @Test
    void a_characteristic_in_periods_and_two_writers_per_period_name_their_periods() {
        String document = """
                Eenheidsysteem Rentetijd
                    de dag (mv: dagen) dg
                    de maand (mv: maanden) mnd

                Objecttype de Afdracht (mv: Afdrachten)
                    de vervaldatum Datum in dagen;
                    de betaaldatum Datum in dagen;
                    is rentedragend kenmerk (bijvoeglijk) voor elke dag;
                    de rentedagen Numeriek (niet-negatief geheel getal) met eenheid dg/mnd voor elke maand;

                Regel rentedragend
                    geldig altijd
                        Een Afdracht is rentedragend van zijn vervaldatum plus 1 dg tot en met zijn betaaldatum.

                Regel rentedagen in een gehele maand
                    geldig altijd
                        De rentedagen van een Afdracht moet gesteld worden op 30 dg/mnd gedurende de tijd dat hij gedurende de gehele maand rentedragend is.

                Regel rentedagen in een gedeeltelijke maand
                    geldig altijd
                        De rentedagen van een Afdracht moet berekend worden als de getelde dagen gedurende de tijd dat hij niet gedurende de gehele maand rentedragend is.
                        Daarbij geldt:
                            de getelde dagen is het aantal dagen in de maand dat hij rentedragend is.

                Scenario november tot maart
                Gegeven is de volgende situatie:
                een Afdracht A1
                - de vervaldatum is 15-11-2025
                - de betaaldatum is 12-03-2026
                Met als verwacht resultaat:
                - de rentedagen van A1 is 0 dg/mnd tot 01-11-2025
                - de rentedagen van A1 is 15 dg/mnd van 01-11-2025 tot 01-12-2025
                - de rentedagen van A1 is 30 dg/mnd van 01-12-2025 tot 01-03-2026
                - de rentedagen van A1 is 12 dg/mnd van 01-03-2026 tot 01-04-2026
                - de rentedagen van A1 is 0 dg/mnd vanaf 01-04-2026
                """;
        assertEquals(List.of(
                "A1 is rentedragend van 16-11-2025 tot 13-03-2026 volgens de regel 'rentedragend'",
                "A1 heeft als rentedagen 30 dg/mnd van 01-12-2025 tot 01-03-2026 volgens de regel 'rentedagen in een"
                        + " gehele maand': 30 dg/mnd, omdat hij gedurende de gehele maand rentedragend is (van 01-12-2025"
                        + " tot 01-03-2026), en 0 dg/mnd tot 01-11-2025, 15 dg/mnd van 01-11-2025 tot 01-12-2025, 12"
                        + " dg/mnd van 01-03-2026 tot 01-04-2026 en 0 dg/mnd vanaf 01-04-2026 volgens de regel"
                        + " 'rentedagen in een gedeeltelijke maand': de getelde dagen, met het aantal dagen in de maand"
                        + " dat hij rentedragend is (" + COUNTED + ") en de getelde dagen is het aantal dagen in de maand"
                        + " dat hij rentedragend is (" + COUNTED + "), omdat hij niet gedurende de gehele maand"
                        + " rentedragend is (tot 01-12-2025 en vanaf 01-03-2026)"),
                explanation(document));
    }

    /** The engine reads a number exactly (RUNTIME-SEMANTICS ch. 2.1): a value that does not terminate is its fraction,
     *  as uchar's reading writes it (ch. 5.12), here before a rounding and as the value of the unbounded form, with the
     *  unit of its node. A value of the unbounded form keeps every decimal it has: the form declares none. */
    @Test
    void a_value_that_does_not_terminate_is_its_fraction_with_its_unit() {
        String document = """
                Eenheidsysteem Munt
                    de euro (mv: euros) EUR €

                Objecttype de Berging (mv: Bergingen)
                    de opbrengst Numeriek (geheel getal) met eenheid €;
                    het helpersaantal Numeriek (geheel getal);
                    het ruwe deel Numeriek (getal) met eenheid €;
                    het helpersdeel Numeriek (geheel getal) met eenheid €;

                Regel ruwe deel
                    geldig altijd
                        Het ruwe deel van een Berging moet berekend worden als de opbrengst van de Berging gedeeld door het helpersaantal van de Berging.

                Regel helpersdeel
                    geldig altijd
                        Het helpersdeel van een Berging moet berekend worden als de opbrengst van de Berging gedeeld door het helpersaantal van de Berging rekenkundig afgerond op 0 decimalen.

                Scenario twee bergingen
                Gegeven is de volgende situatie:
                een Berging B1
                - de opbrengst is 100 €
                - het helpersaantal is 3
                een Berging B2
                - de opbrengst is 5 €
                - het helpersaantal is 2
                Met als verwacht resultaat:
                - het helpersdeel van B1 is 33 €
                - het ruwe deel van B2 is 2,5 €
                - het helpersdeel van B2 is 3 €
                """;
        String quotient = "de opbrengst van de Berging gedeeld door het helpersaantal van de Berging";
        assertEquals(List.of(
                "B1 heeft als ruwe deel 100/3 EUR volgens de regel 'ruwe deel' (onvoorwaardelijk): " + quotient
                        + ", met de opbrengst van de Berging (100 EUR) en het helpersaantal van de Berging (3)",
                "B1 heeft als helpersdeel 33 EUR volgens de regel 'helpersdeel' (onvoorwaardelijk): " + quotient
                        + " rekenkundig afgerond op 0 decimalen, met de opbrengst van de Berging (100 EUR) en het"
                        + " helpersaantal van de Berging (3); vóór de afronding 100/3 EUR",
                "B2 heeft als ruwe deel 2,5 EUR volgens de regel 'ruwe deel' (onvoorwaardelijk): " + quotient
                        + ", met de opbrengst van de Berging (5 EUR) en het helpersaantal van de Berging (2)",
                "B2 heeft als helpersdeel 3 EUR volgens de regel 'helpersdeel' (onvoorwaardelijk): " + quotient
                        + " rekenkundig afgerond op 0 decimalen, met de opbrengst van de Berging (5 EUR) en het"
                        + " helpersaantal van de Berging (2); vóór de afronding 2,5 EUR"),
                explanation(document));
    }

    /** A writer that fires with an empty value decides nothing, on every scalar target (uchar's card
     *  empty-write-decides-nothing, RUNTIME-SEMANTICS ch. 3.11, 5.7, 7.8): beside a second writer and with a validity
     *  window the input stands, beside a writer that gives 50 the target is 50, and beside a conditional initialisation
     *  the initialisation fills an empty target. The model and the sentences are those of
     *  {@code ExplanationComputationServiceTest.a_rule_that_gives_no_value_decides_nothing_and_the_input_stands}. */
    @Test
    void a_rule_that_gives_no_value_decides_nothing_and_the_input_stands() {
        String document = """
                Objecttype de Vondst (mv: Vondsten)
                    de taxatie Numeriek (geheel getal);
                    de keurwaarde Numeriek (geheel getal);
                    de beoordeling Numeriek (geheel getal);
                    de opgave Numeriek (geheel getal);
                    de schatting Numeriek (geheel getal);
                    is gemeld kenmerk (bijvoeglijk);
                    is erkend kenmerk (bijvoeglijk);

                Regel taxatie uit opgave
                    geldig altijd
                        De taxatie van een Vondst moet gesteld worden op zijn opgave indien hij gemeld is.

                Regel taxatie uit schatting
                    geldig altijd
                        De taxatie van een Vondst moet gesteld worden op zijn schatting indien hij erkend is.

                Regel keurwaarde
                    geldig vanaf 2020
                        De keurwaarde van een Vondst moet gesteld worden op zijn opgave.

                Regel beoordeling uit opgave
                    geldig altijd
                        De beoordeling van een Vondst moet gesteld worden op zijn opgave.

                Regel standaardbeoordeling
                    geldig altijd
                        De beoordeling van een Vondst moet geïnitialiseerd worden op 3 indien hij gemeld is.

                Scenario twee vondsten
                met peildatum 01-07-2025
                Gegeven is de volgende situatie:
                een Vondst V1
                - is gemeld
                - de taxatie is 40
                - de keurwaarde is 40
                een Vondst V2
                - is gemeld
                - is erkend
                - de taxatie is 40
                - de schatting is 50
                - de beoordeling is 9
                Met als verwacht resultaat:
                - de taxatie van V1 is 40
                - de keurwaarde van V1 is 40
                - de beoordeling van V1 is 3
                - de taxatie van V2 is 50
                - de beoordeling van V2 is 9
                """;
        assertEquals(List.of(
                "V1 houdt als taxatie de invoerwaarde 40, want de regel 'taxatie uit opgave' gaf geen waarde en besliste"
                        + " niets: zijn opgave (leeg), omdat het onderwerp gemeld is; de regel 'taxatie uit"
                        + " schatting' gold niet, want niet geldt: het onderwerp erkend is",
                "V1 houdt als keurwaarde de invoerwaarde 40, want de regel 'keurwaarde' gaf geen waarde"
                        + " (onvoorwaardelijk) en besliste niets: zijn opgave (leeg)",
                "V1 heeft als beoordeling 3 volgens de regel 'standaardbeoordeling': 3, omdat het onderwerp gemeld is;"
                        + " de regel 'beoordeling uit opgave' gaf geen waarde en besliste niets",
                "V2 heeft als taxatie 50 volgens de regel 'taxatie uit schatting': zijn schatting (50), omdat het"
                        + " onderwerp erkend is; de regel 'taxatie uit opgave' gaf geen waarde en besliste niets",
                "V2 heeft geen keurwaarde, want de regel 'keurwaarde' gaf geen waarde (onvoorwaardelijk): zijn opgave"
                        + " (leeg)",
                "V2 houdt als beoordeling de invoerwaarde 9, want de regel 'beoordeling uit opgave' gaf geen waarde"
                        + " (onvoorwaardelijk) en besliste niets: zijn opgave (leeg); verdrongen: de regel"
                        + " 'standaardbeoordeling'"),
                explanation(document));
    }

    /** A member whose value is leeg contributes nothing to a value fold (uchar's RUNTIME-SEMANTICS ch. 5.12): the
     *  sentence counts the members with a value, and where none has one it says so, not that there is no member. A
     *  count counts every member. J1 has two vondsten without a gewicht, J2 one with and one without. */
    @Test
    void a_value_fold_counts_the_members_with_a_value() {
        String document = """
                Objecttype de Jutter (mv: Jutters)
                    het totaalgewicht Numeriek (geheel getal);
                    het vondstental Numeriek (geheel getal);

                Objecttype de Vondst (mv: Vondsten)
                    het gewicht Numeriek (geheel getal);

                Feittype vondst van een jutter
                    de jutter Jutter
                    de vondst (mv: vondsten) Vondst
                Eén jutter heeft meerdere vondsten

                Regel totaalgewicht
                    geldig altijd
                        Het totaalgewicht van een Jutter moet berekend worden als de som van het gewicht van alle
                        vondsten van de Jutter.

                Regel vondstental
                    geldig altijd
                        Het vondstental van een Jutter moet berekend worden als het aantal vondsten van de Jutter.

                Scenario lege gewichten
                Gegeven is de volgende situatie:
                een Jutter J1
                een Jutter J2
                een Vondst V1
                - de jutter is J1
                een Vondst V2
                - de jutter is J1
                een Vondst V3
                - de jutter is J2
                - het gewicht is 5
                een Vondst V4
                - de jutter is J2
                Met als verwacht resultaat:
                - het totaalgewicht van J1 is leeg
                - het vondstental van J1 is 2
                - het totaalgewicht van J2 is 5
                """;
        String sum = "de som van het gewicht van alle vondsten van de Jutter";
        String count = "het aantal vondsten van de Jutter";
        assertEquals(List.of(
                "J1 heeft geen totaalgewicht, want de regel 'totaalgewicht' gaf geen waarde (onvoorwaardelijk): " + sum
                        + " (leeg): geen lid droeg een waarde bij",
                "J1 heeft als vondstental 2 volgens de regel 'vondstental' (onvoorwaardelijk): " + count + " (2): over"
                        + " V1 en V2 (2 leden)",
                "J2 heeft als totaalgewicht 5 volgens de regel 'totaalgewicht' (onvoorwaardelijk): " + sum + " (5):"
                        + " over V3 (5), het enige lid met een waarde",
                "J2 heeft als vondstental 2 volgens de regel 'vondstental' (onvoorwaardelijk): " + count + " (2): over"
                        + " V3 en V4 (2 leden)"),
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
