package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.transpiler.core.Namen;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link Verbaliser} — the UITLEG-generator — over a decision the engine fired: the model is
 * compiled with {@link RegelSpraakEngine}, one generated bean is fired and written back, and the verbaliser explains
 * the fired state (the same wiring the service's {@code ?explanation=true} field drives). Each mini model has a
 * HAND-COMPUTED outcome, so the assertions are EXACT sentences, not a mere "non-empty": the verhaal must justify a
 * kenmerk with the norm AND the evaluated feiten in NL-notatie, and — for a kenmerk that was withheld — name the
 * first failing conjunct with its evaluated value. The golden over the real TOKA minderjarig decision is pinned in the
 * service's {@code ExplanationServiceTest}.
 */
class VerbaliserTest {

    static final String BELAST = """
            Objecttype de Vlucht (mv: Vluchten)
                de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
                is belast kenmerk (bijvoeglijk);
            Regel belastregel
            geldig altijd
            Een Vlucht is belast indien de afstand tot bestemming van de Vlucht groter is dan 100 km.
            """;

    /** Fire one Vlucht with {@code afstand} km and return the verhaal the verbaliser tells about it as "V1". */
    private static List<String> explanationForVlucht(int afstand) {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(BELAST);
        Object v1 = engine.newInstance("Vlucht");
        RegelSpraakRuntime.set(v1, Namen.ident("afstand tot bestemming", false), new BigDecimal(afstand));
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            s.insert(v1).fire();
            s.restore();
        }
        return new Verbaliser(engine.model(), Map.of())
                .verbalise(Map.of("V1", v1), Map.of("V1", engine.model().objectTypeByName("Vlucht")));
    }

    /** A fired bijvoeglijk kenmerk: the verhaal states the kenmerk AND justifies it with the comparison, showing
     *  the read feit (200 km) against the literal grens (100 km) — the norm and the fact, once each. */
    @Test void assigned_characteristic_accounts_for_itself_with_the_facts() {
        assertEquals(List.of(
                "V1 is belast, omdat de afstand tot bestemming van de Vlucht (200 km) is groter dan 100 km"),
                explanationForVlucht(200));
    }

    /** A WITHHELD kenmerk: the guard did not hold, so the verhaal names the failing comparison with its read
     *  feit (50 km) — "niet geldt: …", not a bare "afwezig". */
    @Test void absent_characteristic_names_the_failing_condition() {
        assertEquals(List.of(
                "V1 is niet belast, want niet geldt: de afstand tot bestemming van de Vlucht (50 km)"
                        + " is groter dan 100 km"),
                explanationForVlucht(50));
    }
}
