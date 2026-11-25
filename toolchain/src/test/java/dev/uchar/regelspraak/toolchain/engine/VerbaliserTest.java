package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    static final String DISJUNCTIONS = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                is betwist kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal);
                de taxatie Numeriek (geheel getal);
                de code Tekst;
                is drijfhout kenmerk (bijvoeglijk);
                is gemeld kenmerk (bijvoeglijk);
                is bijzonder kenmerk (bijvoeglijk);
                is meldwaardig kenmerk (bijvoeglijk);
                is keurwaardig kenmerk (bijvoeglijk);

            Feittype vondst van een vinder
                de vinder (mv: vinders) Jutter
                de vondst Vondst
            Eén vondst heeft meerdere vinders

            Regel bijzonder
                geldig altijd
                    Een Vondst is bijzonder
                    indien hij aan ten minste één van de volgende voorwaarden voldoet:
                    - ten minste één van de vinders van de Vondst betwist is
                    - hij is drijfhout
                    - zijn gewicht is groter dan 1000
                    - zijn code is gevuld
                    - zijn taxatie is leeg
                    - hij voldoet aan alle volgende voorwaarden:
                      .. hij is gemeld
                      .. zijn gewicht is groter dan 500
                    - zijn gewicht is groter dan 100.

            Regel meldwaardig
                geldig altijd
                    Een Vondst is meldwaardig
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij is gemeld
                    - hij voldoet aan ten minste één van de volgende voorwaarden:
                      .. hij is drijfhout
                      .. zijn gewicht is groter dan 100.

            Regel keurwaardig
                geldig altijd
                    Een Vondst is keurwaardig
                    indien hij aan ten minste twee van de volgende voorwaarden voldoet:
                    - zijn taxatie is groter dan 10
                    - hij voldoet aan ten minste één van de volgende voorwaarden:
                      .. hij is drijfhout
                      .. zijn gewicht is groter dan 100
                    - zijn code is gevuld.
            """;

    /** Card disjunction-members-in-explanation: a holding "ten minste één van de volgende voorwaarden" names only the
     *  member that holds in uchar's reading, whether the first member holds (V1) or a later member holds after failing
     *  members of every kind — a quantifier fold with 0 members (V2) and with 1 non-betwist vinder (V3), a
     *  characteristic check, a comparison, gevuld and leeg predicates, and a nested "alle" compound — and when nested
     *  inside "alle" or a count ("ten minste twee van de volgende"); when it does not hold (V4), all failing members
     *  stay joined with "of". */
    @Test void holding_disjunction_names_only_the_member_that_holds() {
        List<ScenarioRunner.Explained> explained = ScenarioRunner.runWithExplanation(
                ScenarioResolver.resolve(DISJUNCTIONS + """

                        Scenario disjuncties
                        Gegeven is de volgende situatie:
                        een Jutter J1
                        - is betwist
                        - de vondst is V1
                        een Jutter J2
                        - de vondst is V3
                        een Vondst V1
                        een Vondst V2
                        - het gewicht is 200
                        - de taxatie is 50
                        - is gemeld
                        een Vondst V3
                        - het gewicht is 200
                        - de taxatie is 50
                        - is gemeld
                        een Vondst V4
                        - het gewicht is 50
                        - de taxatie is 50
                        Met als verwacht resultaat:
                        - V1 is bijzonder
                        - V2 is bijzonder
                        - V2 is meldwaardig
                        - V2 is keurwaardig
                        - V3 is bijzonder
                        - V4 is niet bijzonder
                        """));
        assertEquals(1, explained.size());
        ScenarioRunner.Explained run = explained.get(0);
        assertEquals(ScenarioResult.Status.PASS, run.result().status(),
                () -> run.result().diagnostic());
        String fold = "aan de voorwaarde \"ten minste één van de vinders van de Vondst betwist is\"";
        assertEquals(List.of(
                "V1 is bijzonder, omdat " + fold + " is voldaan: voor J1, het enige lid",
                "V1 is niet meldwaardig, want niet geldt: het onderwerp gemeld is",
                "V1 is niet keurwaardig, want niet geldt: ten minste 2 van de 3 voorwaarden gelden: niet zijn taxatie"
                        + " (leeg) is groter dan 10 en niet het onderwerp drijfhout is of zijn gewicht (leeg) is groter"
                        + " dan 100",
                "V2 is bijzonder, omdat zijn gewicht (200) is groter dan 100",
                "V2 is meldwaardig, omdat het onderwerp gemeld is en zijn gewicht (200) is groter dan 100",
                "V2 is keurwaardig, omdat ten minste 2 van de 3 voorwaarden gelden: zijn taxatie (50) is groter dan 10"
                        + " en zijn gewicht (200) is groter dan 100",
                "V3 is bijzonder, omdat zijn gewicht (200) is groter dan 100",
                "V3 is meldwaardig, omdat het onderwerp gemeld is en zijn gewicht (200) is groter dan 100",
                "V3 is keurwaardig, omdat ten minste 2 van de 3 voorwaarden gelden: zijn taxatie (50) is groter dan 10"
                        + " en zijn gewicht (200) is groter dan 100",
                "V4 is niet bijzonder, want niet geldt: " + fold + " is voldaan: er is geen enkel lid of het onderwerp"
                        + " drijfhout is of zijn gewicht (50) is groter dan 1.000 of zijn code (leeg) gevuld is of zijn"
                        + " taxatie (50) leeg is of het onderwerp gemeld is of zijn gewicht (50) is groter dan 100",
                "V4 is niet meldwaardig, want niet geldt: het onderwerp gemeld is",
                "V4 is niet keurwaardig, want niet geldt: ten minste 2 van de 3 voorwaarden gelden: zijn taxatie (50)"
                        + " is groter dan 10 en niet het onderwerp drijfhout is of zijn gewicht (50) is groter dan 100"
                        + " en niet zijn code (leeg) gevuld is"),
                run.explanation());
        for (String s : List.of(run.explanation().get(3), run.explanation().get(6))) {
            assertFalse(s.contains("er is geen enkel lid") || s.contains("is voldaan: niet voor")
                    || s.contains("drijfhout"), s);
        }
        // The outer CONDITION{holds=true, quantifier=atLeast, threshold=1} stays around the single holding member.
        Sentence v2Sentence = run.sentences().get(3);
        Part outer = v2Sentence.parts().get(v2Sentence.parts().size() - 1);
        assertEquals(Part.Kind.CONDITION, outer.kind());
        assertEquals(Map.of("holds", true, "quantifier", "atLeast", "threshold", 1), outer.meta());
        assertEquals(1, outer.parts().size());
        assertEquals(Part.Kind.CONDITION, outer.parts().get(0).kind());
        assertEquals(Map.of("holds", true), outer.parts().get(0).meta());
    }

    /** In a strict rule set ("Afleiden uit leeg: nee"), a holding "ten minste één van" skips both an open member and a
     *  failing member before the deciding member. */
    @Test void strict_disjunction_skips_both_open_and_failing_members_before_deciding_one() {
        List<ScenarioRunner.Explained> explained = ScenarioRunner.runWithExplanation(
                ScenarioResolver.resolve("""
                        Afleiden uit leeg: nee

                        Objecttype de Vondst (mv: Vondsten)
                            het gewicht Numeriek (geheel getal);
                            de taxatie Numeriek (geheel getal);
                            is drijfhout kenmerk (bijvoeglijk);
                            is verkoopbaar kenmerk (bijvoeglijk);

                        Regel verkoop
                            geldig altijd
                                Een Vondst is verkoopbaar
                                indien hij aan ten minste één van de volgende voorwaarden voldoet:
                                - zijn taxatie is groter dan 100
                                - hij is drijfhout
                                - zijn gewicht is groter dan 20.

                        Scenario open en onwaar voor het beslissende lid
                        Gegeven is de volgende situatie:
                        een Vondst V1
                        - het gewicht is 30
                        - is niet drijfhout
                        Met als verwacht resultaat:
                        - V1 is verkoopbaar
                        """));
        assertEquals(1, explained.size());
        assertEquals(ScenarioResult.Status.PASS, explained.get(0).result().status(),
                () -> explained.get(0).result().diagnostic());
        assertEquals(List.of("V1 is verkoopbaar, omdat zijn gewicht (30) is groter dan 20"),
                explained.get(0).explanation());
    }

    /** Fail-fast guard: if a reading claims "ten minste één van" holds without any reached holding member, the
     *  verbaliser refuses instead of emitting an empty CONDITION after "omdat ". */
    @Test void holding_disjunction_without_holding_member_throws() throws Exception {
        String source = """
                Objecttype de Vondst (mv: Vondsten)
                    is drijfhout kenmerk (bijvoeglijk);
                    is beschadigd kenmerk (bijvoeglijk);
                    is bijzonder kenmerk (bijvoeglijk);
                Regel bijzonder
                    geldig altijd
                        Een Vondst is bijzonder
                        indien hij aan ten minste één van de volgende voorwaarden voldoet:
                        - hij is drijfhout
                        - hij is beschadigd.
                """;
        var model = Resolver.resolve(source);
        var tree = new ObjectMapper().readTree(UcharBuild.document(model));
        var build = UcharBuild.assemble(tree, DroolsBackend.Branch.EXEC_MODEL, true);
        var document = Verbaliser.Document.of(IrLoader.loadWithPositions(tree));
        Object v1 = Class.forName("dev.uchar.regelspraak.facts.Vondst", true, build.beanLoader())
                .getConstructor().newInstance();
        String pointer = "/rules/rules/0/versions/0";
        var reading = new ConditionReading(v1, pointer, ConditionReading.Status.HOLDS, List.of(
                new ConditionReading.NodeReading(0, "Compound", true, true, null, null, null, List.of(), null),
                new ConditionReading.NodeReading(1, "CharacteristicCheck", true, false, null, null, v1, List.of(), null),
                new ConditionReading.NodeReading(2, "CharacteristicCheck", true, false, null, null, v1, List.of(), null)));
        var standing = new EndState(
                List.of(new EndState.EndObject(v1, "V1", "Vondst", null, null,
                        List.of(new EndState.Member(true, "bijzonder", null, true, null, true, null)),
                        List.of())),
                List.of(), List.of(), List.of());
        var verbaliser = new Verbaliser(document, Naming.of(document.model(), Naming.CANONICAL),
                List.of(reading), standing);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> verbaliser.verbalise(
                Map.of("V1", v1), Map.of("V1", document.model().objectTypeByName("Vondst")), List.of(), List.of()));
        assertTrue(ex.getMessage().contains("geldt, en de motor bereikte geen lid dat geldt"), ex.getMessage());
    }
}
