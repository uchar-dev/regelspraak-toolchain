package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt;
import dev.uchar.regelspraak.transpiler.tms.Fout;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session {@link ScenarioRunner#runMetUitleg} gives beside each report: the case played in a session of its own,
 * with uchar's reading of its fixpoint ({@code DroolsBackend.lees}) taken before the write-back. It is there when the
 * session follows uchar's replay; for a case the replay did not play out it is there when evaluation faults stand at
 * its fixpoint, the faults that made the replay's write-back throw, now as data; a MARKER scenario has none.
 */
class ScenarioRunnerSessieTest {

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

    private static List<ScenarioRunner.Uitgelegd> speel(String bron) {
        return ScenarioRunner.runMetUitleg(ScenarioResolver.resolve(bron));
    }

    /** A played scenario: the fixpoint carries every derived value, the one without an expectation too, on the bean of
     *  the input it was read for; the session is the one the explanation verbalised. */
    @Test void een_gespeeld_scenario_geeft_zijn_fixpunt() {
        List<ScenarioRunner.Uitgelegd> u = speel(BERGING + """
            Scenario met twee helpers
            Gegeven is de volgende situatie:
            een Berging B
            - de opbrengst is 100
            - het helpersaantal is 2
            Met als verwacht resultaat:
            - de premie van B is 20,00
            """);
        assertEquals(1, u.size());
        ScenarioRunner.Uitgelegd s = u.get(0);
        assertEquals(Status.PASS, s.resultaat().status(), s.resultaat()::diagnose);
        assertNull(s.sessieFout(), s::sessieFout);
        assertNotNull(s.sessie());
        Fixpunt f = s.sessie().fixpunt();
        assertTrue(f.fouten().isEmpty(), f.fouten()::toString);
        Object b = s.sessie().beans().get("B");
        List<String> afgeleid = f.waarden().stream().filter(w -> w.object() == b)
                .map(w -> w.attribuut() + "=" + w.waarde()).sorted().toList();
        assertEquals(List.of("helpersdeel=50", "premie=20"), afgeleid, "het helpersdeel heeft geen verwachting");
    }

    /** A division by zero stands at the fixpoint: uchar's replay throws it at the write-back and does not play the case
     *  out, so there is no explanation; the session reads the fault as data and does not write back. */
    @Test void een_staande_evaluatiefout_staat_als_gegeven_in_het_fixpunt() {
        List<ScenarioRunner.Uitgelegd> u = speel(BERGING + """
            Scenario zonder helpers
            Gegeven is de volgende situatie:
            een Berging B
            - de opbrengst is 100
            - het helpersaantal is 0
            Met als verwacht resultaat:
            - de premie van B is 20,00
            """);
        ScenarioRunner.Uitgelegd s = u.get(0);
        assertEquals(Status.FAIL, s.resultaat().status(), s.resultaat()::diagnose);
        assertTrue(s.resultaat().verwachtingen().isEmpty(), s.resultaat().verwachtingen()::toString);
        assertTrue(s.uitlegFout().startsWith("uchars replay speelde de case niet uit: "), s::uitlegFout);
        assertTrue(s.uitleg().isEmpty());
        assertNull(s.sessieFout(), s::sessieFout);
        List<Fout> fouten = s.sessie().fixpunt().fouten();
        assertEquals(1, fouten.size(), fouten::toString);
        assertEquals("helpersdeel van een berging", fouten.get(0).regel());
        assertSame(s.sessie().beans().get("B"), fouten.get(0).onderwerp());
        assertTrue(fouten.get(0).melding().contains("deling door nul"), fouten.get(0)::melding);
        assertNull(CaseSessie.lees(s.sessie().beans().get("B"), "getPremie"),
                "met een staande fout zet de sessie niets terug: de bean houdt de invoer");
    }

    /** A MARKER scenario has no session: uchar's replay did not play it. */
    @Test void een_marker_heeft_geen_sessie() {
        List<ScenarioRunner.Uitgelegd> u = speel("""
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
        assertEquals(Status.MARKER, u.get(0).resultaat().status(), u.get(0).resultaat()::diagnose);
        assertNull(u.get(0).sessie());
        assertNull(u.get(0).sessieFout());
    }
}
