package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.engine.RegelSpraakCompileException;
import dev.uchar.regelspraak.runtime.Breuk;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.core.Dekking;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt;
import dev.uchar.regelspraak.transpiler.tms.Invoervormfout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the shippable path: a RegelSpraak source compiles through uchar to an executable-model
 * KieBase, FIRES over the generated fact beans through uchar's entry and writes the derived values back onto them —
 * the integration an embedding consumer relies on.
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
        // the build refuses a model with a form its branch does not lower, so its manifest names the rule, lowered
        assertEquals(List.of("regel: R"), engine.coverage().stream().map(Dekking::sleutel).toList());
        assertTrue(engine.coverage().stream().allMatch(Dekking::lowered), engine.coverage()::toString);

        Object minor = persoon(engine, 16);
        Object adult = persoon(engine, 40);
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            int fired = s.insertAll(minor, adult).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
            assertTrue(fired >= 1, "at least the minor's rule fires");
            s.restore();
        }
        assertEquals(Boolean.TRUE, RegelSpraakRuntime.get(minor, "minderjarig"), "16 < 18 -> minderjarig");
        assertEquals(Boolean.FALSE, RegelSpraakRuntime.get(adult, "minderjarig"), "40 >= 18 -> not minderjarig");
    }

    static final String FUTURE = """
            Parameter de volwassenleeftijd : Numeriek (geheel getal) met eenheid jr
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                de toekomstige leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is minderjarig indien zijn leeftijd kleiner is dan de volwassenleeftijd.
            Regel toekomstige leeftijd
                geldig altijd
                    De toekomstige leeftijd van een Persoon moet berekend worden als zijn leeftijd plus 1 jr.
            """;

    /** A session that fires twice with an update in between reads what a fresh session with the changed input reads:
     *  the session fills and fires through uchar's entry, and the derived values are facts of the session until
     *  {@code restore}, so the second fire withdraws the characteristic of the first and derives the value anew. */
    @Test void a_session_fires_twice_with_an_update_in_between_as_a_fresh_session() {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(FUTURE);
        Object p = persoon(engine, 16);
        Fixpunt first;
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            s.insert(p).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
            first = s.read();
            RegelSpraakRuntime.set(p, "leeftijd", new BigDecimal(40));
            s.update(p);
            s.fire();
            s.restore();
        }
        assertEquals(List.of(new BigDecimal(17)), first.waarden().stream()
                .filter(w -> w.object() == p && w.attribuut().equals("toekomstige leeftijd"))
                .map(Fixpunt.AfgeleideWaarde::waarde).toList(), first.waarden()::toString);
        assertTrue(first.kenmerken().stream().anyMatch(k -> k.object() == p && k.kenmerk().equals("minderjarig")),
                first.kenmerken()::toString);

        Object fresh = persoon(engine, 40);
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            s.insert(fresh).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
            s.restore();
        }
        assertEquals(new BigDecimal(41), RegelSpraakRuntime.get(fresh, "toekomstigeLeeftijd"), "40 + 1 in een verse sessie");
        assertEquals(Boolean.FALSE, RegelSpraakRuntime.get(fresh, "minderjarig"), "40 >= 18 in een verse sessie");
        assertEquals(RegelSpraakRuntime.get(fresh, "toekomstigeLeeftijd"), RegelSpraakRuntime.get(p, "toekomstigeLeeftijd"),
                "na de update leest de sessie wat een verse sessie leest");
        assertEquals(RegelSpraakRuntime.get(fresh, "minderjarig"), RegelSpraakRuntime.get(p, "minderjarig"),
                "na de update leest de sessie wat een verse sessie leest");
    }

    static final String TYPE_ERROR = """
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
                () -> RegelSpraakEngine.compile(TYPE_ERROR));   // assigning a Tekst to a Numeriek -> type error
        assertFalse(ex.diagnostics().isEmpty(), "a type error must report a diagnostic");
    }

    @Test void the_fact_class_is_generated() {
        // Until the move to uchar the consumer supplied the fact classes and compile() refused a model whose class
        // was missing from the classpath. uchar's build generates the beans from the model itself, so an objecttype
        // no consumer wrote a class for compiles, and its bean comes from the engine.
        String src = """
            Objecttype het Spookding
                de waarde Numeriek (geheel getal);
                de naam Tekst;
            """;
        RegelSpraakEngine engine = RegelSpraakEngine.compile(src);
        Object ding = engine.newInstance("Spookding");
        RegelSpraakRuntime.set(ding, "naam", "spook");
        assertEquals("spook", RegelSpraakRuntime.get(ding, "naam"));
    }

    @Test void a_non_lowered_rule_fails_at_compiling() {
        // uchar builds no KieBase that lacks a construct: an engine without the rule would answer as if the rule
        // did not exist, so compile() refuses and names the rule.
        String src = """
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (niet-negatief geheel getal);
                is belast kenmerk (bijvoeglijk) voor elke dag;
            Regel heffing markert
            geldig altijd
            De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
            """;
        RegelSpraakCompileException e = assertThrows(RegelSpraakCompileException.class,
                () -> RegelSpraakEngine.compile(src));
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.message().contains("heffing markert")),
                () -> "de weigering hoort de regel te noemen: " + e.diagnostics());
    }

    @Test void cycle_reference_refuses_at_compiling() {
        // A read-write dependency cycle (leeftijd = uitkomst+1; uitkomst = leeftijd+1) never reaches a
        // fixpoint. uchar's frontend refuses it under WF-045 before a KieBase is built, so no session
        // ever fires it; until uchar a7210c19 the resolver only warned and fire() hit its cap.
        String cycle = """
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
                () -> RegelSpraakEngine.compile(cycle));
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.isError() && d.message().contains("(WF-045)")
                        && d.message().endsWith("dependency cycle through no recursive creation: a -> b -> a")),
                () -> "de weigering hoort WF-045 en de kring te noemen: " + e.diagnostics());
    }

    /** An own total without a clip over an attribute on a timeline, which reads the asOfDate-bucket scalar. */
    static final String BEWARING = """
            Eenheidsysteem Munt
                de euro (mv: euros) EUR €

            Eenheidsysteem Bewaartijd
                de maand mnd

            Objecttype de Jutter (mv: Jutters) (bezield)
                de bewaarvergoeding per maand Numeriek (geheel getal) met eenheid €/mnd voor elke maand;
                het bewaargeld Numeriek (geheel getal) met eenheid €;

            Regel bewaargeld van een jutter
                geldig altijd
                    Het bewaargeld van een Jutter moet berekend worden als het totaal van zijn bewaarvergoeding per
                    maand.
            """;

    /** A consumer that sets an attribute on a timeline as the one segment of the period of the peildatum states it as
     *  the asOfDate-bucket scalar ({@link RegelSpraakEngine.Session#bucketScalar}). Without the statement the branch
     *  reads a full timeline of one segment, and the own total faults with an {@code Invoervormfout}; with it the
     *  session gives the value uchar's replay gives on the same case, which states such a scalar itself. */
    @Test
    void a_bucket_scalar_goes_with_the_first_fire_to_the_uchar_entry(@TempDir Path map) throws Exception {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(BEWARING);
        LocalDate asOfDate = LocalDate.of(2026, 3, 15);

        Object without = jutter(engine, 10);
        try (RegelSpraakEngine.Session s = engine.newSession(asOfDate)) {
            s.insert(without).fire();
            assertThrows(Invoervormfout.class, s::restore);
        }

        Object extended = jutter(engine, 10);
        try (RegelSpraakEngine.Session s = engine.newSession(asOfDate)) {
            s.insert(extended).bucketScalar(extended, "bewaarvergoeding per maand").fire();
            assertThrows(IllegalStateException.class, () -> s.bucketScalar(extended, "bewaarvergoeding per maand"));
            s.restore();
        }
        CaseRunner.Result replay = replay(map, engine, """
            {"format":"uchar-case-v0","asOfDate":"2026-03-15",
             "input":{"instances":[{"label":"J1","objectType":"Jutter",
               "attributes":{"bewaarvergoeding per maand":{"number":"10"}},"roles":{},"characteristics":[]}],
              "parameters":{}},
             "expected":[{"kind":"attributeValue","label":"J1","attribute":"bewaargeld","value":{"number":"10"}}]}""");
        assertEquals(List.of(), replay.caseFailures());
        assertEquals("PLAYED", replay.status());
        assertEquals(0, new BigDecimal(10).compareTo((BigDecimal) RegelSpraakRuntime.get(extended, "bewaargeld")),
                () -> "bewaargeld " + RegelSpraakRuntime.get(extended, "bewaargeld"));
    }

    /** A fold over a to-many role whose filter divides by a member's deelnemers. */
    static final String FOLD = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de zware vondsten Numeriek (geheel getal);

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal);
                de deelnemers Numeriek (geheel getal);

            Feittype aanmelding
                de aanmelder Jutter
                de vondst (mv: vondsten) Vondst
            Eén aanmelder heeft meerdere vondsten

            Regel zware vondsten
                geldig altijd
                    De zware vondsten van een Jutter moet berekend worden als het aantal vondsten van de Jutter
                    die aan de volgende voorwaarde voldoen: zijn gewicht gedeeld door zijn deelnemers is groter dan 2.
            """;

    /** J1's vondsten V1 (10 by 2), V2 (4 by 0) and V3 (9 by 0), inserted in that order: the evaluation of the fold stops
     *  at the first member whose filter divides by 0, in the order of the list. Without a statement the list keeps the
     *  order of the inserts, V2's 4; with {@link RegelSpraakEngine.Session#list} as V1, V3, V2 the session faults on
     *  V3's 9, as uchar's replay does on the case where J1 writes its vondsten in that order. */
    @Test
    void the_order_of_a_list_goes_with_the_first_fire_to_the_uchar_entry(@TempDir Path map) throws Exception {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(FOLD);
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            List<Object> j1v1v2v3 = vondsten(engine);
            s.insertAll(j1v1v2v3.toArray()).fire();
            assertTrue(assertThrows(Breuk.DelingDoorNul.class, s::restore).getMessage().contains("4 gedeeld door 0"));
        }
        String sentence;
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            List<Object> j1v1v2v3 = vondsten(engine);
            Object j1 = j1v1v2v3.get(0);
            s.insertAll(j1v1v2v3.toArray())
                    .list(j1, "aanmelder", List.of(j1v1v2v3.get(1), j1v1v2v3.get(3), j1v1v2v3.get(2))).fire();
            assertThrows(IllegalStateException.class, () -> s.list(j1, "aanmelder", j1v1v2v3.subList(1, 4)));
            sentence = assertThrows(Breuk.DelingDoorNul.class, s::restore).getMessage();
        }
        Path ir = map.resolve("ir.json");
        Files.writeString(ir, IrEmitter.emit(engine.model()), StandardCharsets.UTF_8);
        Path testCase = map.resolve("case.json");
        Files.writeString(testCase, """
            {"format":"uchar-case-v0","asOfDate":null,
             "input":{"instances":[
               {"label":"J1","objectType":"Jutter","attributes":{},"roles":{"vondsten":["V1","V3","V2"]},
                "characteristics":[]},
               {"label":"V1","objectType":"Vondst","attributes":{"gewicht":{"number":"10"},"deelnemers":{"number":"2"}},
                "roles":{},"characteristics":[]},
               {"label":"V2","objectType":"Vondst","attributes":{"gewicht":{"number":"4"},"deelnemers":{"number":"0"}},
                "roles":{},"characteristics":[]},
               {"label":"V3","objectType":"Vondst","attributes":{"gewicht":{"number":"9"},"deelnemers":{"number":"0"}},
                "roles":{},"characteristics":[]}],
              "parameters":{}},
             "expected":[{"kind":"consistent"}]}""", StandardCharsets.UTF_8);
        String replay = assertThrows(Breuk.DelingDoorNul.class, () -> CaseRunner.runGolden(ir, List.of(testCase)))
                .getMessage();
        assertTrue(replay.contains("9 gedeeld door 0"), replay);
        assertEquals(replay, sentence);
    }

    /** uchar's replay ({@code CaseRunner.runGolden}) of case {@code testCase} on the document of {@code engine}'s model. */
    private static CaseRunner.Result replay(Path map, RegelSpraakEngine engine, String testCase) throws Exception {
        Path ir = map.resolve("ir.json");
        Files.writeString(ir, IrEmitter.emit(engine.model()), StandardCharsets.UTF_8);
        Path c = map.resolve("case.json");
        Files.writeString(c, testCase, StandardCharsets.UTF_8);
        return CaseRunner.runGolden(ir, List.of(c));
    }

    /** A generated Jutter bean whose bewaarvergoeding per maand is {@code waarde} on the one segment of March 2026, the
     *  period of the peildatum 2026-03-15 on the attribute's monthly grid. */
    static Object jutter(RegelSpraakEngine engine, int value) throws Exception {
        Object j = engine.newInstance("Jutter");
        Object segment = Class.forName(FactModelGenerator.PAKKET + ".Segment", true, engine.beanLoader())
                .getConstructor(LocalDate.class, LocalDate.class, BigDecimal.class)
                .newInstance(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1), new BigDecimal(value));
        RegelSpraakRuntime.set(j, "bewaarvergoedingPerMaand", List.of(segment));
        return j;
    }

    /** J1 and its vondsten V1 (10 by 2), V2 (4 by 0) and V3 (9 by 0), each holding J1 as its aanmelder, in that order. */
    static List<Object> vondsten(RegelSpraakEngine engine) {
        Object j1 = engine.newInstance("Jutter");
        List<Object> sink = new ArrayList<>(List.of(j1));
        for (int[] gd : new int[][]{{10, 2}, {4, 0}, {9, 0}}) {
            Object v = engine.newInstance("Vondst");
            RegelSpraakRuntime.set(v, "gewicht", new BigDecimal(gd[0]));
            RegelSpraakRuntime.set(v, "deelnemers", new BigDecimal(gd[1]));
            RegelSpraakRuntime.set(v, "aanmelder", j1);
            sink.add(v);
        }
        return sink;
    }

    /** A generated Persoon bean with its leeftijd set. */
    static Object persoon(RegelSpraakEngine engine, int leeftijd) {
        Object p = engine.newInstance("Persoon");
        RegelSpraakRuntime.set(p, "leeftijd", new BigDecimal(leeftijd));
        return p;
    }
}
