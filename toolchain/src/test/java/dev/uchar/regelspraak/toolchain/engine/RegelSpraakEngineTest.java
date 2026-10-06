package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.RegelSpraakCompileException;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the shippable path: a RegelSpraak source compiles through uchar to an executable-model
 * KieBase and FIRES over the generated fact beans, mutating them in place — the integration an embedding consumer
 * relies on.
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

        Object minor = persoon(engine, 16);
        Object adult = persoon(engine, 40);
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            int fired = s.insertAll(minor, adult).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
            assertTrue(fired >= 1, "at least the minor's rule fires");
        }
        assertEquals(Boolean.TRUE, RegelSpraakRuntime.get(minor, "minderjarig"), "16 < 18 -> minderjarig");
        assertEquals(Boolean.FALSE, RegelSpraakRuntime.get(adult, "minderjarig"), "40 >= 18 -> not minderjarig");
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

    @Test void de_feitklasse_wordt_gegenereerd() {
        // Until the move to uchar the consumer supplied the fact classes and compile() refused a model whose class
        // was missing from the classpath. uchar's build generates the beans from the model itself, so an objecttype
        // no consumer wrote a class for compiles, and its bean comes from the engine.
        String src = """
            Objecttype het Spookding
                de waarde Numeriek (geheel getal);
                de naam Tekst;
            """;
        RegelSpraakEngine engine = RegelSpraakEngine.compile(src);
        Object ding = engine.nieuw("Spookding");
        RegelSpraakRuntime.set(ding, "naam", "spook");
        assertEquals("spook", RegelSpraakRuntime.get(ding, "naam"));
    }

    @Test void een_niet_gelowerde_regel_faalt_bij_het_compileren() {
        // uchar builds no KieBase that lacks a construct: an engine without the rule would answer as if the rule
        // did not exist, so compile() refuses and names the rule.
        String src = """
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
            Regel heffing markert
            geldig altijd
            De heffing van een Vlucht moet berekend worden als het absolute tijdsevenredig deel per maand van 10,00 EUR.
            """;
        RegelSpraakCompileException e = assertThrows(RegelSpraakCompileException.class,
                () -> RegelSpraakEngine.compile(src));
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.message().contains("heffing markert")),
                () -> "de weigering hoort de regel te noemen: " + e.diagnostics());
    }

    @Test void kringverwijzing_weigert_bij_het_compileren() {
        // A read-write dependency cycle (leeftijd = uitkomst+1; uitkomst = leeftijd+1) never reaches a
        // fixpoint. uchar's frontend refuses it under WF-045 before a KieBase is built, so no session
        // ever fires it; until uchar a7210c19 the resolver only warned and fire() hit its cap.
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
        RegelSpraakCompileException e = assertThrows(RegelSpraakCompileException.class,
                () -> RegelSpraakEngine.compile(kring));
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.isError() && d.message().contains("(WF-045)")
                        && d.message().endsWith("dependency cycle through no recursive creation: a -> b -> a")),
                () -> "de weigering hoort WF-045 en de kring te noemen: " + e.diagnostics());
    }

    /** A generated Persoon bean with its leeftijd set. */
    static Object persoon(RegelSpraakEngine engine, int leeftijd) {
        Object p = engine.nieuw("Persoon");
        RegelSpraakRuntime.set(p, "leeftijd", new BigDecimal(leeftijd));
        return p;
    }
}
