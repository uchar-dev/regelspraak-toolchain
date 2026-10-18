package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameters on the scenario route. uchar's scenario layer requires every parameter a carried rule reads to have a
 * value in the scenario's situation; a parameter set outside the document does not reach a scenario. Until the move
 * to uchar the runner accepted parameter sets beside the document and chose a vintage per scenario from its
 * peildatum; that route no longer exists, and a scenario that relied on it is refused by the resolver.
 * <ul>
 *   <li>{@code s1} — a parameter the situation does not give is a {@link ScenarioRunner.ResolveError} naming the
 *       parameter and the scenario;</li>
 *   <li>{@code s4} — the situation gives the parameter, the scenario carries a peildatum: PASS on that value;</li>
 *   <li>{@code s6} — the situation gives the parameter, no peildatum: PASS.</li>
 * </ul>
 */
class ParameterSetScenarioTest {

    // ---------------- s1 : een parameter buiten de situatie ----------------

    @Test void s1_parameter_outside_the_situation_is_a_resolve_error() {
        String source = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Situatie basis:
            een Persoon P1
            Scenario twee-duizend-vijfentwintig
            toetst de regel grensregel
            met peildatum 01-06-2025
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is 18
            """;
        var error = assertThrows(ScenarioRunner.ResolveError.class, () -> ScenarioRunner.run(source));
        String message = String.join("\n", error.diagnostics());
        assertTrue(message.contains("volwassenleeftijd"), () -> "noemt de parameter: " + message);
        assertTrue(message.contains("twee-duizend-vijfentwintig"), () -> "noemt het scenario: " + message);
    }

    // ---------------- s4 : de situatie levert de parameter, met peildatum ----------------

    @Test void s4_situation_value_with_as_of_date() {
        // Het scenario zet de parameter in de situatie op 40: dat is de waarde die de regel leest.
        String source = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Situatie basis:
            een Persoon P1
            de parameter de volwassenleeftijd is 40
            Scenario override
            toetst de regel grensregel
            met peildatum 01-06-2025
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is 40
            """;
        List<ScenarioResultaat> r = ScenarioRunner.run(source);
        assertEquals(1, r.size());
        assertEquals(Status.PASS, r.get(0).status(),
                () -> "verwacht PASS (situatie 40): "
                        + r.get(0).diagnose() + " / " + r.get(0).verwachtingen());
    }

    // ---------------- s6 : bestaand pad zonder sets ----------------

    @Test void s6_situation_value_without_as_of_date() {
        // De parameter staat in de situatie — het gewone run(bron)-pad.
        String source = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Situatie basis:
            een Persoon P1
            de parameter de volwassenleeftijd is 18
            Scenario situatie levert de parameter
            toetst de regel grensregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is 18
            """;
        List<ScenarioResultaat> r = ScenarioRunner.run(source);
        assertEquals(1, r.size());
        assertEquals(Status.PASS, r.get(0).status(),
                () -> "verwacht PASS (situatie-waarde 18, geen sets): "
                        + r.get(0).diagnose() + " / " + r.get(0).verwachtingen());
    }
}
