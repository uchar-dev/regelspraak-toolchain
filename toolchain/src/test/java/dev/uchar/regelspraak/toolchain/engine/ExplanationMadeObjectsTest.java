package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.transpiler.core.Namen;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The explanation names an object a creation made by the name uchar's canonical text gives it (RUNTIME-SEMANTICS
 * ch. 5.12), {@code «"<creatie>"»(<onderwerp>)#1}, with the subject as the explanation names it: an object of the input
 * by its label, a made object by its own name (card explanation-made-object-names). A made object is a subject of the
 * explanation after the objects of the input, in the order of the fixpoint, and a member of a quantifier fold.
 *
 * <p>Two creations give a Jutter a penning: one with his vangst as its bedrag, one of 5 € above a vangst of 20 €. A
 * penning above 10 € gets a kwitantie, a creation whose subject can itself be made. J1 (vangst 30 €) gets two penningen,
 * of which the bonuspenning is not waardevol; J2 (vangst 8 €) gets one, which is not waardevol, beside the penning P1 of
 * the input (15 €). The decision service gives the same sentences on both its routes
 * ({@code ExplanationMadeObjectsServiceTest}).
 */
class ExplanationMadeObjectsTest {

    static final String MODEL = """
            Eenheidsysteem Munt
                de euro (mv: euros) EUR €

            Objecttype de Jutter (mv: Jutters)
                de vangst Numeriek (geheel getal) met eenheid €;
                is beloond kenmerk (bijvoeglijk);
                is rijk kenmerk (bijvoeglijk);

            Objecttype de Penning (mv: Penningen)
                het bedrag Numeriek (geheel getal) met eenheid €;
                is waardevol kenmerk (bijvoeglijk);

            Objecttype de Kwitantie (mv: Kwitanties)
                het bedrag Numeriek (geheel getal) met eenheid €;
                is geldig kenmerk (bijvoeglijk);

            Feittype penningtoekenning
                de houder Jutter
                de penning (mv: penningen) Penning
            Eén houder heeft meerdere penningen

            Feittype kwitering
                de gekwiteerde penning Penning
                de kwitantie Kwitantie
            Eén gekwiteerde penning heeft één kwitantie

            Regel geef een vangstpenning
                geldig altijd
                    Een Jutter heeft een penning met bedrag gelijk aan zijn vangst
                    indien zijn vangst groter is dan 0 €.

            Regel geef een bonuspenning
                geldig altijd
                    Een Jutter heeft een penning met bedrag gelijk aan 5 €
                    indien zijn vangst groter is dan 20 €.

            Regel schrijf een kwitantie
                geldig altijd
                    Een Penning heeft een kwitantie met bedrag gelijk aan zijn bedrag
                    indien zijn bedrag groter is dan 10 €.

            Regel waardevolle penning
                geldig altijd
                    Een Penning is waardevol
                    indien zijn bedrag groter is dan 10 €.

            Regel geldige kwitantie
                geldig altijd
                    Een Kwitantie is geldig
                    indien zijn bedrag groter is dan 0 €.

            Regel beloond
                geldig altijd
                    Een Jutter is beloond
                    indien alle penningen van de Jutter waardevol zijn.

            Regel rijk
                geldig altijd
                    Een Jutter is rijk
                    indien ten minste één van de penningen van de Jutter waardevol is.
            """;

    private static final String SITUATION = """

            Situatie basis:
            een Jutter J1
            - de vangst is 30 €
            een Jutter J2
            - de vangst is 8 €
            een Penning P1
            - het bedrag is 15 €
            - de houder is J2
            Scenario gemaakte penningen
            met peildatum 01-06-2026
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - J1 is niet beloond
            - J1 is rijk
            - J2 is niet beloond
            - J2 is rijk
            """;

    static final String BONUS_J1 = "«\"geef een bonuspenning\"»(J1)#1";
    static final String CATCH_J1 = "«\"geef een vangstpenning\"»(J1)#1";
    static final String CATCH_J2 = "«\"geef een vangstpenning\"»(J2)#1";
    static final String RECEIPT_P1 = "«\"schrijf een kwitantie\"»(P1)#1";
    static final String RECEIPT_CATCH_J1 = "«\"schrijf een kwitantie\"»(" + CATCH_J1 + ")#1";

    private static final String ALL = "aan de voorwaarde \"alle penningen van de Jutter waardevol zijn\"";
    private static final String AT_LEAST_ONE =
            "aan de voorwaarde \"ten minste één van de penningen van de Jutter waardevol is\"";

    /** The sentences about J1, J2 and the objects made for them, without P1 and what was made for it. */
    private static final List<String> MADE_FOR_THE_JUTTERS = List.of(
            BONUS_J1 + " is niet waardevol, want niet geldt: zijn bedrag (5 EUR) is groter dan 10 EUR",
            CATCH_J1 + " is waardevol, omdat zijn bedrag (30 EUR) is groter dan 10 EUR",
            CATCH_J2 + " is niet waardevol, want niet geldt: zijn bedrag (8 EUR) is groter dan 10 EUR");

    static final List<String> EXPECTED = List.of(
            "J1 is niet beloond, want " + ALL + " is niet voldaan: niet voor " + BONUS_J1 + " (1 van de 2)",
            "J1 is rijk, omdat " + AT_LEAST_ONE + " is voldaan: voor 1 van de 2 (" + CATCH_J1 + ")",
            "J2 is niet beloond, want " + ALL + " is niet voldaan: niet voor " + CATCH_J2 + " (1 van de 2)",
            "J2 is rijk, omdat " + AT_LEAST_ONE + " is voldaan: voor 1 van de 2 (P1)",
            "P1 is waardevol, omdat zijn bedrag (15 EUR) is groter dan 10 EUR",
            MADE_FOR_THE_JUTTERS.get(0),
            MADE_FOR_THE_JUTTERS.get(1),
            MADE_FOR_THE_JUTTERS.get(2),
            RECEIPT_P1 + " is geldig, omdat zijn bedrag (15 EUR) is groter dan 0 EUR",
            RECEIPT_CATCH_J1 + " is geldig, omdat zijn bedrag (30 EUR) is groter dan 0 EUR");

    @Test
    void on_the_scenario_route_a_made_object_is_named_by_its_creation_and_its_subject() {
        List<ScenarioRunner.Explained> u =
                ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(MODEL + SITUATION));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        assertEquals(EXPECTED, u.get(0).explanation());
        Part subject = u.get(0).sentences().get(9).parts().get(0);
        assertEquals(new Part(Part.Kind.SUBJECT, RECEIPT_CATCH_J1, Map.of(), List.of()), subject,
                "het onderwerp is het gemaakte object, bij zijn naam");
    }

    /** The test helper that fires beans it set up itself names a made object as the other routes do. */
    @Test
    void in_a_session_of_its_own_a_made_object_is_named_by_its_creation_and_its_subject() throws Exception {
        ExplanationSession engine = ExplanationSession.of(MODEL);
        Object j1 = engine.newInstance("Jutter");
        RegelSpraakRuntime.set(j1, Namen.ident("vangst", false), new BigDecimal(30));
        Object j2 = engine.newInstance("Jutter");
        RegelSpraakRuntime.set(j2, Namen.ident("vangst", false), new BigDecimal(8));
        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("J1", j1);
        beans.put("J2", j2);
        assertEquals(List.of(
                        "J1 is niet beloond, want " + ALL + " is niet voldaan: niet voor " + BONUS_J1 + " (1 van de 2)",
                        "J1 is rijk, omdat " + AT_LEAST_ONE + " is voldaan: voor 1 van de 2 (" + CATCH_J1 + ")",
                        "J2 is niet beloond, want " + ALL + " is niet voldaan: niet voor " + CATCH_J2
                                + ", het enige lid",
                        "J2 is niet rijk, want " + AT_LEAST_ONE + " is niet voldaan: niet voor " + CATCH_J2
                                + ", het enige lid",
                        MADE_FOR_THE_JUTTERS.get(0),
                        MADE_FOR_THE_JUTTERS.get(1),
                        MADE_FOR_THE_JUTTERS.get(2),
                        RECEIPT_CATCH_J1 + " is geldig, omdat zijn bedrag (30 EUR) is groter dan 0 EUR"),
                engine.explain(Naming.CANONICAL, "Jutter", beans));
    }
}
