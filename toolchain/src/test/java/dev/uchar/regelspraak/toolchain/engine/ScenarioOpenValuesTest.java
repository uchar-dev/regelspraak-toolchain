package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios of a rule set that infers nothing from an empty value ({@code Afleiden uit leeg: nee}, uchar's
 * RUNTIME-SEMANTICS ch. 1.5). A scenario has no sentence that expects an open outcome: one that expects a value, leeg or
 * the absence of a characteristic where the outcome is open fails, with uchar's word for it. The explanation refuses
 * such a document; the report and the session stand beside that refusal.
 */
class ScenarioOpenValuesTest {

    private static final String RULES = """
        Afleiden uit leeg: nee

        Objecttype de Vondst (mv: Vondsten)
            de taxatie Numeriek (geheel getal);
            het gewicht Numeriek (geheel getal);
            het vindloon Numeriek (geheel getal);
            is kostbaar kenmerk (bijvoeglijk);

        Regel kostbaarheid
            geldig altijd
                Een Vondst is kostbaar indien zijn taxatie groter is dan 1000.

        Regel vindloon
            geldig altijd
                Het vindloon van een Vondst moet berekend worden als zijn gewicht plus 10.

        """;

    private static List<ScenarioRunner.Explained> play(String scenarios) {
        return ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(RULES + scenarios));
    }

    @Test
    void an_expectation_on_an_open_outcome_fails_with_uchars_word() {
        List<ScenarioRunner.Explained> played = play("""
            Scenario waarde verwacht
            Gegeven is de volgende situatie:
            een Vondst V1
            Met als verwacht resultaat:
            - het vindloon van V1 is 10
            Scenario leeg verwacht
            Gegeven is de volgende situatie:
            een Vondst V1
            Met als verwacht resultaat:
            - het vindloon van V1 is leeg
            Scenario geen kenmerk verwacht
            Gegeven is de volgende situatie:
            een Vondst V1
            Met als verwacht resultaat:
            - V1 is niet kostbaar
            """);
        assertEquals(3, played.size());
        for (ScenarioRunner.Explained p : played) {
            assertEquals(Status.FAIL, p.result().status(), p.result()::diagnostic);
            assertEquals(1, p.result().expectations().size());
            assertEquals("openstaand", p.result().expectations().get(0).actual(), p.result()::diagnostic);
            assertTrue(p.result().diagnostic().contains("openstaand"), p.result()::diagnostic);
        }
    }

    @Test
    void what_stands_is_compared_as_ever_and_the_explanation_refuses() {
        ScenarioRunner.Explained p = play("""
            Scenario gegeven
            Gegeven is de volgende situatie:
            een Vondst V1
            - de taxatie is 1500
            - het gewicht is 5
            Met als verwacht resultaat:
            - het vindloon van V1 is 15
            - V1 is kostbaar
            """).get(0);
        assertEquals(Status.PASS, p.result().status(), p.result()::diagnostic);
        assertEquals(List.of(), p.explanation());
        assertEquals("de regelset leidt uit leeg niets af (Afleiden uit leeg: nee), en de uitleg verwoordt geen open"
                + " uitkomst", p.explanationError());
        assertEquals(0, p.session().standing().objects().get(0).open().size());
    }
}
