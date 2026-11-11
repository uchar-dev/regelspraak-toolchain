package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.transpiler.core.Namen;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link Verbaliser} — the UITLEG-generator — over a decision the engine fired: the model is built
 * with readings ({@link ExplanationSession}), one generated bean is fired and written back, and the verbaliser explains
 * the fired state from the engine's readings (the same wiring the service's {@code ?explanation=true} field drives). Each mini model has a
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
    private static List<String> explanationForVlucht(int afstand) throws Exception {
        ExplanationSession engine = ExplanationSession.of(BELAST);
        Object v1 = engine.newInstance("Vlucht");
        RegelSpraakRuntime.set(v1, Namen.ident("afstand tot bestemming", false), new BigDecimal(afstand));
        return engine.explain(Naming.CANONICAL, "Vlucht", Map.of("V1", v1));
    }

    /** A fired bijvoeglijk kenmerk: the verhaal states the kenmerk AND justifies it with the comparison, showing
     *  the read feit (200 km) against the literal grens (100 km) — the norm and the fact, once each. */
    @Test void assigned_characteristic_accounts_for_itself_with_the_facts() throws Exception {
        assertEquals(List.of(
                "V1 is belast, omdat de afstand tot bestemming van de Vlucht (200 km) is groter dan 100 km"),
                explanationForVlucht(200));
    }

    /** A WITHHELD kenmerk: the guard did not hold, so the verhaal names the failing comparison with its read
     *  feit (50 km) — "niet geldt: …", not a bare "afwezig". */
    @Test void absent_characteristic_names_the_failing_condition() throws Exception {
        assertEquals(List.of(
                "V1 is niet belast, want niet geldt: de afstand tot bestemming van de Vlucht (50 km)"
                        + " is groter dan 100 km"),
                explanationForVlucht(50));
    }

    static final String COMPOUNDS = """
            Objecttype de Vlucht (mv: Vluchten)
                de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
                de bezetting Numeriek (positief geheel getal);
                is lang kenmerk (bijvoeglijk);
                is rustig kenmerk (bijvoeglijk);
            Regel lang
            geldig altijd
            Een Vlucht is lang
            indien hij aan alle volgende voorwaarden voldoet:
            - de afstand tot bestemming van de Vlucht is groter dan 100 km
            - de bezetting van de Vlucht is kleiner dan 10.
            Regel rustig
            geldig altijd
            Een Vlucht is rustig
            indien hij aan geen van de volgende voorwaarden voldoet:
            - de afstand tot bestemming van de Vlucht is groter dan 1000 km
            - de bezetting van de Vlucht is groter dan 10.
            """;

    private static List<String> compoundsForVlucht(int afstand, int bezetting) throws Exception {
        ExplanationSession engine = ExplanationSession.of(COMPOUNDS);
        Object v1 = engine.newInstance("Vlucht");
        RegelSpraakRuntime.set(v1, Namen.ident("afstand tot bestemming", false), new BigDecimal(afstand));
        RegelSpraakRuntime.set(v1, Namen.ident("bezetting", false), new BigDecimal(bezetting));
        return engine.explain(Naming.CANONICAL, "Vlucht", Map.of("V1", v1));
    }

    /** "geen van de volgende voorwaarden" holds because no member holds, so every member is stated negated, joined
     *  as "alle" joins its members: "niet A en niet B", never "A en niet B", which says A holds. */
    @Test void compound_with_none_negates_every_member() throws Exception {
        assertEquals(List.of(
                "V1 is lang, omdat de afstand tot bestemming van de Vlucht (200 km) is groter dan 100 km"
                        + " en de bezetting van de Vlucht (5) is kleiner dan 10",
                "V1 is rustig, omdat niet de afstand tot bestemming van de Vlucht (200 km) is groter dan 1.000 km"
                        + " en niet de bezetting van de Vlucht (5) is groter dan 10"),
                compoundsForVlucht(200, 5));
    }

    public static final class Bean {
        public Object getHeld() { return "x"; }
        public Object getEmpty() { return null; }
        public Boolean isMarked() { return true; }
        public Object getBroken() { throw new IllegalStateException("kapot"); }
    }

    /** A getter that returns null reads as an absent value; a getter that throws, or a member the bean has no
     *  getter for, is an error naming the getter and the bean's class, never a silent absent value. */
    @Test void failed_reflective_read_is_an_error_not_an_absent_value() {
        Bean bean = new Bean();
        assertEquals("x", Verbaliser.readRaw(bean, "getHeld"));
        assertNull(Verbaliser.readRaw(bean, "getEmpty"));
        assertEquals(Boolean.TRUE, Verbaliser.readRaw(bean, "getMarked"));
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Verbaliser.readRaw(bean, "getBroken"));
        assertTrue(thrown.getMessage().contains("getBroken") && thrown.getMessage().contains(Bean.class.getName()),
                thrown.getMessage());
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> Verbaliser.readRaw(bean, "getAbsent"));
        assertTrue(missing.getMessage().contains("getAbsent") && missing.getMessage().contains(Bean.class.getName()),
                missing.getMessage());
    }
}
