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

    @Test void ontbrekende_feitklasse_faalt_snel_met_alle_contract_schendingen() {
        // the fact-model contract: compile() validates the bean contract FIRST and reports EVERY violation at once.
        String src = """
            Objecttype het Spookding
                de waarde Numeriek (geheel getal);
                de naam Tekst;
            """;
        RegelSpraakCompileException e = org.junit.jupiter.api.Assertions.assertThrows(
                RegelSpraakCompileException.class, () -> RegelSpraakEngine.compile(src));
        org.junit.jupiter.api.Assertions.assertTrue(
                e.diagnostics().stream().anyMatch(d -> d.message().contains("feitmodel-contract")
                        && d.message().contains("Spookding")),
                () -> "de ontbrekende feitklasse moet als contract-schending gemeld worden: " + e.diagnostics());
    }

    @org.junit.jupiter.api.Timeout(60)
    @Test void kringverwijzing_wordt_begrensd_niet_gehangen() {
        // A read-write dependency cycle (leeftijd = uitkomst+1; uitkomst = leeftijd+1) never reaches a
        // fixpoint — the resolver WARNs, and fire() must turn the run-away into an exception, never a
        // hung session (the @Timeout converts a regression into a failure instead of a hung build).
        String kring = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal);
                de uitkomst Numeriek (geheel getal);
            Regel a
                geldig altijd
                    De leeftijd van een Persoon moet berekend worden als de uitkomst van de Persoon plus 1.
            Regel b
                geldig altijd
                    De uitkomst van een Persoon moet berekend worden als de leeftijd van een Persoon plus 1.
            """;
        RegelSpraakEngine engine = RegelSpraakEngine.compile(kring);
        assertTrue(engine.overgeslagen().isEmpty(), () -> "beide kring-regels moeten loweren: " + engine.overgeslagen());
        Persoon p = new Persoon(); p.setLeeftijd(new BigDecimal(1));
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            s.insert(p);
            assertThrows(IllegalStateException.class, s::fire,
                    "een kringverwijzing moet begrensd falen, niet hangen");
        }
    }
}
