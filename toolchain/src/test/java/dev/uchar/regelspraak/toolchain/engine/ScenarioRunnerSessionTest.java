package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;
import dev.uchar.regelspraak.transpiler.tms.Fault;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session {@link ScenarioRunner#runWithUitleg} gives beside each report: the case played in a session of its own,
 * with uchar's reading of its fixpoint ({@code DroolsBackend.lees}) taken before the write-back. It is there when the
 * session follows uchar's replay; for a case the replay did not play out it is there when evaluation faults stand at
 * its fixpoint, the faults that made the replay's write-back throw, now as data; a MARKER scenario has none.
 */
class ScenarioRunnerSessionTest {

    private static final String BERGING = """
            Objecttype de Berging (mv: Bergingen)
                de opbrengst Numeriek (geheel getal);
                het helpersaantal Numeriek (geheel getal);
                het helpersdeel Numeriek (geheel getal);
                de premie Numeriek (getal met 2 decimalen);
            Regel premie van een berging
            geldig altijd
            De premie van een Berging moet berekend worden als 20% van de opbrengst van de Berging.
            Regel helpersdeel van een berging
            geldig altijd
            Het helpersdeel van een Berging moet berekend worden als de opbrengst van de Berging gedeeld door het helpersaantal van de Berging.
            """;

    private static List<ScenarioRunner.Explained> play(String source) {
        return ScenarioRunner.runWithUitleg(ScenarioResolver.resolve(source));
    }

    /** A played scenario: the fixpoint carries every derived value, the one without an expectation too, on the bean of
     *  the input it was read for; the session is the one the explanation verbalised. */
    @Test void a_played_scenario_gives_its_fixpoint() {
        List<ScenarioRunner.Explained> u = play(BERGING + """
            Scenario met twee helpers
            Gegeven is de volgende situatie:
            een Berging B
            - de opbrengst is 100
            - het helpersaantal is 2
            Met als verwacht resultaat:
            - de premie van B is 20,00
            """);
        assertEquals(1, u.size());
        ScenarioRunner.Explained s = u.get(0);
        assertEquals(Status.PASS, s.result().status(), s.result()::diagnostic);
        assertNull(s.sessionError(), s::sessionError);
        assertNotNull(s.session());
        Fixpoint f = s.session().fixpoint();
        assertTrue(f.faults().isEmpty(), f.faults()::toString);
        Object b = s.session().beans().get("B");
        List<String> derived = f.waarden().stream().filter(w -> w.object() == b)
                .map(w -> w.attribute() + "=" + w.value()).sorted().toList();
        assertEquals(List.of("helpersdeel=50", "premie=20"), derived, "het helpersdeel heeft geen verwachting");
    }

    /** A division by zero stands at the fixpoint: uchar's replay throws it at the write-back and does not play the case
     *  out, so there is no explanation; the session reads the fault as data and does not write back. */
    @Test void a_standing_evaluation_fault_stands_as_given_in_the_fixpoint() {
        List<ScenarioRunner.Explained> u = play(BERGING + """
            Scenario zonder helpers
            Gegeven is de volgende situatie:
            een Berging B
            - de opbrengst is 100
            - het helpersaantal is 0
            Met als verwacht resultaat:
            - de premie van B is 20,00
            """);
        ScenarioRunner.Explained s = u.get(0);
        assertEquals(Status.FAIL, s.result().status(), s.result()::diagnostic);
        assertTrue(s.result().expectations().isEmpty(), s.result().expectations()::toString);
        assertTrue(s.uitlegError().startsWith("uchars replay speelde de case niet uit: "), s::uitlegError);
        assertTrue(s.uitleg().isEmpty());
        assertNull(s.sessionError(), s::sessionError);
        List<Fault> errors = s.session().fixpoint().faults();
        assertEquals(1, errors.size(), errors::toString);
        assertEquals("helpersdeel van een berging", errors.get(0).regel());
        assertSame(s.session().beans().get("B"), errors.get(0).onderwerp());
        assertTrue(errors.get(0).message().contains("deling door nul"), errors.get(0)::message);
        assertNull(CaseSession.read(s.session().beans().get("B"), "getPremie"),
                "met een staande fout zet de sessie niets terug: de bean houdt de invoer");
    }

    /** A MARKER scenario has no session: uchar's replay did not play it. */
    @Test void a_marker_has_no_session() {
        List<ScenarioRunner.Explained> u = play("""
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (niet-negatief geheel getal);
                is belast kenmerk (bijvoeglijk) voor elke dag;
            Regel heffingsregel
            geldig altijd
            De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
            Situatie basis:
            een Vlucht V1
            - de heffing is 5
            Scenario marker-oefening
            toetst de regel heffingsregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de heffing van V1 is 5
            """);
        assertEquals(Status.MARKER, u.get(0).result().status(), u.get(0).result()::diagnostic);
        assertNull(u.get(0).session());
        assertNull(u.get(0).sessionError());
    }
}
