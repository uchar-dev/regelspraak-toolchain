package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.facts.Persoon;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the shippable path: a RegelSpraak source compiles to an executable-model KieBase and FIRES
 * over real domain facts, mutating them in place — the integration a Quarkus/Drools consumer relies on. Uses ONLY
 * RegelSpraakEngine (the exec-model back-end + the Drools runtime), never the DRL-string oracle.
 */
class RegelSpraakEngineTest {

    static final String MINDERJARIG = """
            Parameter de volwassenleeftijd : Numeriek (geheel getal) met eenheid jr
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is minderjarig indien zijn leeftijd kleiner is dan de volwassenleeftijd.
            """;

    @Test
    void compiles_and_fires_end_to_end() {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(MINDERJARIG);
        assertTrue(engine.overgeslagen().isEmpty(), () -> "rule should fully lower, skipped: " + engine.overgeslagen());

        Persoon minor = new Persoon(); minor.setLeeftijd(new BigDecimal(16));
        Persoon adult = new Persoon(); adult.setLeeftijd(new BigDecimal(40));
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            int fired = s.insertAll(minor, adult).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
            assertTrue(fired >= 1, "at least the minor's rule fires");
        }
        assertTrue(minor.isMinderjarig(), "16 < 18 -> minderjarig");
        assertFalse(adult.isMinderjarig(), "40 >= 18 -> not minderjarig");
    }

    static final String TYPEFOUT = """
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                de naam Tekst;
            Regel R
                geldig altijd
                    De leeftijd van een Persoon moet gesteld worden op de naam van een Persoon.
            """;

    @Test
    void resolver_errors_surface_as_a_compile_exception() {
        RegelSpraakCompileException ex = assertThrows(RegelSpraakCompileException.class,
                () -> RegelSpraakEngine.compile(TYPEFOUT));   // assigning a Tekst to a Numeriek -> type error
        assertFalse(ex.diagnostics().isEmpty(), "a type error must report a diagnostic");
    }
}
