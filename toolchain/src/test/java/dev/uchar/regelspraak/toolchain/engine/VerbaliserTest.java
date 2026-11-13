package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.model.Datatype;
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

    static final String COUNTS = """
            Objecttype de Vlucht (mv: Vluchten)
                de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
                de bezetting Numeriek (positief geheel getal);
                is ruim kenmerk (bijvoeglijk);
                is bescheiden kenmerk (bijvoeglijk);
                is gemengd kenmerk (bijvoeglijk);
            Regel ruim
            geldig altijd
            Een Vlucht is ruim
            indien hij aan ten minste twee van de volgende voorwaarden voldoet:
            - de afstand tot bestemming van de Vlucht is groter dan 1000 km
            - de afstand tot bestemming van de Vlucht is groter dan 100 km
            - de bezetting van de Vlucht is kleiner dan 10.
            Regel bescheiden
            geldig altijd
            Een Vlucht is bescheiden
            indien hij aan ten hoogste één van de volgende voorwaarden voldoet:
            - de afstand tot bestemming van de Vlucht is groter dan 1000 km
            - de bezetting van de Vlucht is kleiner dan 10.
            Regel gemengd
            geldig altijd
            Een Vlucht is gemengd
            indien hij aan precies één van de volgende voorwaarden voldoet:
            - de afstand tot bestemming van de Vlucht is groter dan 100 km
            - de bezetting van de Vlucht is kleiner dan 10.
            """;

    private static List<String> countsForVlucht(int afstand, int bezetting) throws Exception {
        ExplanationSession engine = ExplanationSession.of(COUNTS);
        Object v1 = engine.newInstance("Vlucht");
        RegelSpraakRuntime.set(v1, Namen.ident("afstand tot bestemming", false), new BigDecimal(afstand));
        RegelSpraakRuntime.set(v1, Namen.ident("bezetting", false), new BigDecimal(bezetting));
        return engine.explain(Naming.CANONICAL, "Vlucht", Map.of("V1", v1));
    }

    /** "ten hoogste één van de volgende" holds after its first member already: that member does not hold, so the one
     *  member left cannot exceed the threshold (RUNTIME-SEMANTICS ch. 5.10). The reading states the threshold and the
     *  member read with its own verdict, "niet A", never "A" as if it held. */
    @Test void compound_at_most_states_its_threshold_and_member_verdicts() throws Exception {
        assertEquals("V1 is bescheiden, omdat ten hoogste 1 van de 2 voorwaarden geldt: niet de afstand tot"
                        + " bestemming van de Vlucht (200 km) is groter dan 1.000 km",
                countsForVlucht(200, 5).get(1));
    }

    /** "ten minste twee van de volgende" carries its threshold, where "A of B" would say one suffices; every member
     *  read is stated with its own verdict, holding or failing. */
    @Test void compound_at_least_states_its_threshold_and_member_verdicts() throws Exception {
        assertEquals("V1 is ruim, omdat ten minste 2 van de 3 voorwaarden gelden: niet de afstand tot bestemming van"
                        + " de Vlucht (200 km) is groter dan 1.000 km en de afstand tot bestemming van de Vlucht (200 km)"
                        + " is groter dan 100 km en de bezetting van de Vlucht (5) is kleiner dan 10",
                countsForVlucht(200, 5).get(0));
        assertEquals("V1 is niet ruim, want niet geldt: ten minste 2 van de 3 voorwaarden gelden: niet de afstand tot"
                        + " bestemming van de Vlucht (200 km) is groter dan 1.000 km en de afstand tot bestemming van de"
                        + " Vlucht (200 km) is groter dan 100 km en niet de bezetting van de Vlucht (50) is kleiner dan 10",
                countsForVlucht(200, 50).get(0));
    }

    /** "precies één van de volgende" carries its threshold too, with every member read and its own verdict. */
    @Test void compound_exactly_states_its_threshold_and_member_verdicts() throws Exception {
        assertEquals("V1 is gemengd, omdat precies 1 van de 2 voorwaarden geldt: de afstand tot bestemming van de"
                        + " Vlucht (200 km) is groter dan 100 km en niet de bezetting van de Vlucht (50) is kleiner dan 10",
                countsForVlucht(200, 50).get(2));
    }

    static final String DIGITS = """
            Objecttype de Havenmeester (mv: Havenmeesters) (bezield)
                de code Tekst;
                is gecodeerd kenmerk (bijvoeglijk);
                is ongecodeerd kenmerk (bijvoeglijk);
            Regel codering
            geldig altijd
            Een Havenmeester is gecodeerd
            indien zijn code numeriek is met exact 4 cijfers.
            Regel geen codering
            geldig altijd
            Een Havenmeester is ongecodeerd
            indien hij aan alle volgende voorwaarden voldoet:
            - zijn code is gevuld
            - zijn code is niet numeriek met exact 4 cijfers.
            """;

    /** "numeriek met exact N cijfers" is written as the rule writes it, with its number of digits and its negation:
     *  never the name of the predicate kind, and never a negated test stated as the plain one. */
    @Test void exact_digits_keeps_its_digits_and_its_negation() throws Exception {
        ExplanationSession engine = ExplanationSession.of(DIGITS);
        Object h1 = engine.newInstance("Havenmeester");
        RegelSpraakRuntime.set(h1, Namen.ident("code", false), "1234");
        Object h2 = engine.newInstance("Havenmeester");
        RegelSpraakRuntime.set(h2, Namen.ident("code", false), "12a");
        Map<String, Object> beans = new java.util.LinkedHashMap<>();
        beans.put("H1", h1);
        beans.put("H2", h2);
        assertEquals(List.of(
                "H1 is gecodeerd, omdat zijn code (1234) numeriek is met exact 4 cijfers",
                "H1 is niet ongecodeerd, want niet geldt: zijn code (1234) niet numeriek is met exact 4 cijfers",
                "H2 is niet gecodeerd, want niet geldt: zijn code (12a) numeriek is met exact 4 cijfers",
                "H2 is ongecodeerd, omdat zijn code (12a) gevuld is en zijn code (12a) niet numeriek is met exact 4"
                        + " cijfers"),
                engine.explain(Naming.CANONICAL, "Havenmeester", beans));
    }

    /** The whole-period form of the predicate keeps its period and both its negations, on uchar's golden
     *  jutterswet-registratienummer-gehele-jaar with the situation of its third case: a number of 9 digits for half
     *  of 2026. */
    @Test void exact_digits_keeps_its_whole_period() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../uchar/corpus/golden/jutterswet-registratienummer-gehele-jaar/source.rgs"));
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(
                dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(source + """

                        Scenario half jaar geldig
                        Gegeven is de volgende situatie:
                        een Jutter J1
                        - het registratienummer is "123456789" van 01-01-2026 tot 01-07-2026
                        Met als verwacht resultaat:
                        - J1 is niet doorlopend geregistreerd
                        - J1 is niet doorlopend afwijkend
                        - J1 is onderbroken geregistreerd
                        - J1 is maandelijks geregistreerd
                        """));
        assertEquals(1, u.size());
        assertEquals(dev.uchar.regelspraak.engine.ScenarioResult.Status.PASS, u.get(0).result().status(),
                () -> u.get(0).result().diagnostic());
        String read = "zijn registratienummer (123456789 van 01-01-2026 tot 01-07-2026)";
        assertEquals(List.of(
                "J1 is niet doorlopend geregistreerd, want niet geldt: " + read + " gedurende het gehele jaar numeriek"
                        + " is met exact 9 cijfers",
                "J1 is niet doorlopend afwijkend, want niet geldt: " + read + " gedurende het gehele jaar niet"
                        + " numeriek is met exact 9 cijfers",
                "J1 is onderbroken geregistreerd, omdat " + read + " niet gedurende het gehele jaar numeriek is met"
                        + " exact 9 cijfers",
                "J1 is maandelijks geregistreerd, omdat " + read + " gedurende de gehele maand numeriek is met exact 9"
                        + " cijfers"),
                u.get(0).explanation());
    }

    /** A zero is written without a sign, also where it comes as "-0.00": "-0,00" is no number the engine holds. A
     *  negative value keeps its sign and every decimal the engine read: padded to the declared scale, never rounded,
     *  so a small negative value is never written as zero. */
    @Test void zero_has_no_sign_and_a_negative_value_is_not_rounded() {
        Datatype twoDecimals = new Datatype.Numeric(false, 2, Datatype.Sign.ONBEPAALD, null);
        assertEquals("0,00", Verbaliser.nlNumber(new BigDecimal("-0.00"), twoDecimals));
        assertEquals("0,00", Verbaliser.nlNumber(new BigDecimal("-0"), twoDecimals));
        assertEquals("-0,004", Verbaliser.nlNumber(new BigDecimal("-0.004"), twoDecimals));
        assertEquals("-0,005", Verbaliser.nlNumber(new BigDecimal("-0.005"), twoDecimals));
        assertEquals("-1.234,50", Verbaliser.nlNumber(new BigDecimal("-1234.5"), twoDecimals));
    }

    static final String HALF = """
            Objecttype de Vondst (mv: Vondsten)
                de waarde Numeriek (getal met 2 decimalen);
                is ruim kenmerk (bijvoeglijk);
            Regel ruim
            geldig altijd
            Een Vondst is ruim
            indien zijn waarde gedeeld door 2 groter is dan 0,505.
            """;

    /** An operand stands with the value the engine read, padded to the decimals of its type and never rounded to
     *  them: 1,01 / 2 is 0,505, which is not greater than 0,505, and "0,51" would contradict the verdict. */
    @Test void operand_is_not_rounded_to_its_declared_decimals() throws Exception {
        ExplanationSession engine = ExplanationSession.of(HALF);
        Object v1 = engine.newInstance("Vondst");
        RegelSpraakRuntime.set(v1, Namen.ident("waarde", false), new BigDecimal("1.01"));
        Object v2 = engine.newInstance("Vondst");
        RegelSpraakRuntime.set(v2, Namen.ident("waarde", false), new BigDecimal("1.2"));
        Map<String, Object> beans = new java.util.LinkedHashMap<>();
        beans.put("V1", v1);
        beans.put("V2", v2);
        assertEquals(List.of(
                "V1 is niet ruim, want niet geldt: zijn waarde gedeeld door 2 (0,505) is groter dan 0,505",
                "V2 is ruim, omdat zijn waarde gedeeld door 2 (0,60) is groter dan 0,505"),
                engine.explain(Naming.CANONICAL, "Vondst", beans));
    }
}
