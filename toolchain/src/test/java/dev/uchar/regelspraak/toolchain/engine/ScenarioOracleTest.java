package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.cases.Invoer;
import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
import dev.uchar.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.tms.Rekenfout;
import org.junit.jupiter.api.Test;
import org.kie.api.runtime.KieSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The DIFFERENTIAL orakel of the scenario route. Each @Test lifts one case from the former execution suites and
 * proves the {@link ScenarioRunner} produces IDENTICAL values to the direct Java/Drools API path.
 *
 * <p>Per triple, TWO independent legs close the loop:
 * <ol>
 *   <li><b>Java-API ground truth</b> — {@link #world} resolves the SAME inline model and builds it through uchar
 *       on the exec-model branch ({@link UcharBuild}, the build the engine and the service fire), instantiates the
 *       generated {@link FactModelGenerator} beans directly, sets globals, puts the beans in, fires and writes the
 *       derived values back through uchar's entry, and READS the target getter(s).
 *       This is the ground-truth value asserted with an exact comparator (BigDecimal.compareTo==0 / equals /
 *       boolean read / null⇔leeg).</li>
 *   <li><b>Scenario path</b> — the SAME model + facts + expectation as a {@code .rgs} scenario document, run
 *       through {@link ScenarioRunner#run(String)}: uchar's scenario resolver, its lowering to case files, and
 *       the backend's replay. We assert status == PASS (which means every expectation's EXACT comparator held
 *       INSIDE the replay: scenario-expectation-value == replay-actual), AND we assert
 *       directly that the value we WROTE into the scenario expectation equals the Java-API reading. Together:
 *       runner-actual == scenario-expectation == java-API-value — genuinely differential, not merely "passes".</li>
 * </ol>
 * Every triple is labelled with the surface facet it covers and the source method it was lifted from.
 */
class ScenarioOracleTest {

    // ---------------- Java-API ground-truth harness (generated beans, uchar's build) ----------------

    /** A session on uchar's build of a model, with the class loader of its generated beans. */
    private record World(DroolsBackend.Build build, KieSession session, ClassLoader cl) {
        Object newInstance(String clazz) throws Exception {
            return Class.forName(FactModelGenerator.PAKKET + "." + clazz, true, cl).getConstructor().newInstance();
        }
        void set(Object o, String setter, Object value, Class<?> type) throws Exception {
            o.getClass().getMethod(setter, type).invoke(o, value);
        }
        void number(Object o, String setter, int value) throws Exception {
            set(o, setter, new BigDecimal(value), BigDecimal.class);
        }
        void number(Object o, String setter, String value) throws Exception {
            set(o, setter, new BigDecimal(value), BigDecimal.class);
        }
        void characteristic(Object o, String setter, boolean value) throws Exception {
            set(o, setter, value, boolean.class);
        }
        void role(Object o, String setter, Object target) throws Exception {
            o.getClass().getMethod(setter, target.getClass()).invoke(o, target);
        }
        Object read(Object o, String getter) throws Exception { return o.getClass().getMethod(getter).invoke(o); }
        BigDecimal num(Object o, String getter) throws Exception { return (BigDecimal) read(o, getter); }
        boolean bool(Object o, String getter) throws Exception {
            // a kenmerk exposes isX; an attribuut Boolean exposes getX — try both.
            try { return (Boolean) read(o, getter); }
            catch (NoSuchMethodException e) { return (Boolean) read(o, "is" + getter.substring(3)); }
        }
        long inconsistencies() throws Exception {
            Class<?> inc = Class.forName(FactModelGenerator.PAKKET + ".Inconsistentie", true, cl);
            return session.getObjects(inc::isInstance).size();
        }
    }

    private World world(String src) {
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "model hoort te resolven: " + m.errors());
        DroolsBackend.Build b = UcharBuild.assemble(m, DroolsBackend.Branch.EXEC_MODEL);
        return new World(b, b.kieBase().newKieSession(), b.beanLoader());
    }

    /** Put {@code facts} in, fire and write the derived values back through uchar's entry, with the bean class loader
     *  as the thread's context class loader, as uchar's replay fires. */
    private void fire(World w, Object... facts) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(w.cl());
        try {
            DroolsBackend.voegIn(w.build(), w.session(), new Invoer().beans(List.of(facts)));
            DroolsBackend.Vuring firing = DroolsBackend.vuur(w.build(), w.session());
            assertNull(firing.geenFixpunt(), "regel-loop bereikt geen fixpunt");
            DroolsBackend.zetAfgeleideWaarden(w.build(), w.session());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    // ---------------- scenario-path harness ----------------

    private ScenarioResultaat één(String source) {
        List<ScenarioResultaat> r = ScenarioRunner.run(source);
        assertEquals(1, r.size(), () -> "verwachtte precies één scenario, kreeg " + r.size());
        return r.get(0);
    }

    /** Assert the scenario reached PASS (its OWN exact comparator held for every expectation). */
    private void pass(ScenarioResultaat r) {
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS: " + r.diagnose() + " / " + r.verwachtingen());
    }

    /** The runner's rendered werkelijk for the expectation on line {@code lineFragment}'s bron (first matching). */
    private String actual(ScenarioResultaat r, String sourceFragment) {
        return r.verwachtingen().stream().filter(v -> v.bron().contains(sourceFragment)).map(VerwachtingResultaat::werkelijk)
                .findFirst().orElseThrow(() -> new AssertionError("geen verwachting met '" + sourceFragment + "' in " + r.verwachtingen()));
    }

    private void assertNumber(String expected, BigDecimal actual, String what) {
        assertNotNull(actual, () -> what + ": verwacht " + expected + ", kreeg leeg");
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> what + ": verwacht " + expected + ", kreeg " + actual);
    }

    // =====================================================================================================
    // rekenkunde + eenheden
    // =====================================================================================================

    /** FACET rekenkunde+eenheden (also parameter-gedreven, voorwaarden). Lifted from
     *  DroolsExecutionTest.berekening_met_voorwaarde_vuurt_en_zet_attribuut (L154): 10 < 100 fires 10+100=110.
     *  The rule reads a basisprijs and writes the totaalprijs: a rule that reads its own target is a dependency cycle
     *  uchar's frontend refuses (WF-045). */
    @Test void arithmetic_parameter_condition_summing() throws Exception {
        String model = """
            Parameter de grens : Numeriek (geheel getal)
            Objecttype de Vlucht
                de basisprijs Numeriek (geheel getal);
                de totaalprijs Numeriek (geheel getal);
            Regel verhoog
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op de basisprijs van een Vlucht plus de grens
                    indien de basisprijs van een Vlucht kleiner is dan de grens.
            """;
        World w = world(model);
        w.session().setGlobal("grens", new BigDecimal("100"));
        Object v = w.newInstance("Vlucht"); w.number(v, "setBasisprijs", 10);
        fire(w, v);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("110", ground, "java-api totaalprijs");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de basisprijs is 10
            de parameter de grens is 100
            Scenario optellen
            toetst de regel verhoog
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 110
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("110")), "scenario-verwachting == java-api");
        assertEquals(ground.toPlainString(), actual(r, "totaalprijs"), "runner render == java-api render");
    }

    /** FACET rekenkunde+eenheden (rate unit €/jr ↔ €/mnd). Lifted from
     *  DroolsExecutionTest.samengestelde_eenheid_rekent_de_rate_om (L1873): 10 €/mnd = 120 €/jr. */
    @Test void arithmetic_rate_unit_conversion() throws Exception {
        String model = """
            Eenheidsysteem Geld
                de euro EUR
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Heffing
                de prijs Numeriek (geheel getal) met eenheid EUR/jr;
                de basis Numeriek (geheel getal) met eenheid EUR/mnd;
            Regel r
                geldig altijd
                    De prijs van een Heffing moet gesteld worden op de basis van een Heffing.
            """;
        World w = world(model);
        Object h = w.newInstance("Heffing"); w.number(h, "setBasis", 10);
        fire(w, h);
        BigDecimal ground = w.num(h, "getPrijs");
        assertNumber("120", ground, "java-api prijs (10 EUR/mnd = 120 EUR/jr)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Heffing H1
            - de basis is 10 EUR/mnd
            Scenario rate
            toetst de regel r
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de prijs van H1 is 120 EUR/jr
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("120")), "scenario == java-api");
    }

    /** FACET rekenkunde+eenheden (cross-unit optelling). Lifted from
     *  DroolsExecutionTest.cross_unit_optelling_wordt_omgerekend_niet_rauw_geteld (L3359): 2 jr + 3 mnd = 27 mnd.
     *  The rule reads a beginduur and writes the duur: a rule that reads its own target is a dependency cycle uchar's
     *  frontend refuses (WF-045). */
    @Test void arithmetic_cross_unit_addition() throws Exception {
        String model = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Periode
                de beginduur Numeriek (geheel getal) met eenheid mnd;
                de duur Numeriek (geheel getal) met eenheid mnd;
                de looptijd Numeriek (geheel getal) met eenheid jr;
            Regel tel
                geldig altijd
                    De duur van een Periode moet gesteld worden op de beginduur van een Periode plus de looptijd van een Periode.
            """;
        World w = world(model);
        Object p = w.newInstance("Periode"); w.number(p, "setLooptijd", 2); w.number(p, "setBeginduur", 3);
        fire(w, p);
        BigDecimal ground = w.num(p, "getDuur");
        assertNumber("27", ground, "java-api duur (2 jr + 3 mnd)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Periode P1
            - de beginduur is 3 mnd
            - de looptijd is 2 jr
            Scenario cross-unit
            toetst de regel tel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de duur van P1 is 27 mnd
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("27")), "scenario == java-api");
    }

    /** FACET rekenkunde+eenheden (begrenzing/maximum met eenheidsomrekening). Lifted from
     *  DroolsExecutionTest.begrenzing_grens_wordt_omgerekend_naar_de_eenheid_van_de_waarde (L1690): min(20 mnd, 1 jr). */
    @Test void arithmetic_clamp_maximum() throws Exception {
        String model = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Periode
                de duur Numeriek (geheel getal) met eenheid mnd;
                de netto Numeriek (geheel getal) met eenheid mnd;
                de looptijd Numeriek (geheel getal) met eenheid jr;
            Regel netto
                geldig altijd
                    De netto van een Periode moet gesteld worden op de duur van een Periode, met een maximum van de looptijd van een Periode.
            """;
        World w = world(model);
        Object p = w.newInstance("Periode"); w.number(p, "setDuur", 20); w.number(p, "setLooptijd", 1);
        fire(w, p);
        BigDecimal ground = w.num(p, "getNetto");
        assertNumber("12", ground, "java-api netto (min(20 mnd, 12 mnd))");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Periode P1
            - de duur is 20 mnd
            - de looptijd is 1 jr
            Scenario begrenzing
            toetst de regel netto
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de netto van P1 is 12 mnd
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("12")), "scenario == java-api");
    }

    /** FACET rekenkunde+eenheden (macht met verplichte afronding). Lifted from
     *  DroolsExecutionTest.macht_afronding_rondt_de_macht_af_en_compileert_als_bigdecimal (L1974): 5^2 = 25. */
    @Test void arithmetic_power() throws Exception {
        String model = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
            Regel macht
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de basis van een Heffing tot de macht 2 naar beneden afgerond op 0 decimalen.
            """;
        World w = world(model);
        Object h = w.newInstance("Heffing"); w.number(h, "setBasis", 5);
        fire(w, h);
        BigDecimal ground = w.num(h, "getNetto");
        assertNumber("25", ground, "java-api netto (5^2)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Heffing H1
            - de basis is 5
            Scenario macht
            toetst de regel macht
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de netto van H1 is 25
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("25")), "scenario == java-api");
    }

    /** FACET rekenkunde+eenheden (wortel met verplichte afronding). Lifted from
     *  DroolsExecutionTest.wortel_rondt_de_root_af_via_zijn_verplichte_afronding (L1997): sqrt(20) floor 2dec = 4.47. */
    @Test void arithmetic_square_root() throws Exception {
        String model = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (getal met 2 decimalen);
            Regel w
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de wortel van de basis van een Heffing naar beneden afgerond op 2 decimalen.
            """;
        World w = world(model);
        Object h = w.newInstance("Heffing"); w.number(h, "setBasis", 20);
        fire(w, h);
        BigDecimal ground = w.num(h, "getNetto");
        assertNumber("4.47", ground, "java-api netto (sqrt 20 floor 2dec)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Heffing H1
            - de basis is 20
            Scenario wortel
            toetst de regel w
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de netto van H1 is 4,47
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("4.47")), "scenario == java-api");
    }

    /** FACET rekenkunde+eenheden (division ABS, 5-dec toward zero). Lifted from
     *  DroolsExecutionTest.gedeeld_door_abs_rondt_af_op_vijf_decimalen_richting_nul (L2065): 5 / |3| = 1.66666. */
    @Test void arithmetic_divided_by_abs() throws Exception {
        String model = """
            Objecttype de Kandidaat
                de a Numeriek (getal met 5 decimalen);
                de b Numeriek (getal met 5 decimalen);
                de c Numeriek (getal met 5 decimalen);
            Regel deel abs
                geldig altijd
                    De c van een Kandidaat moet gesteld worden op de a van een Kandidaat gedeeld door (ABS) de b van een Kandidaat.
            """;
        World w = world(model);
        Object k = w.newInstance("Kandidaat"); w.number(k, "setA", 5); w.number(k, "setB", 3);
        fire(w, k);
        BigDecimal ground = w.num(k, "getC");
        assertNumber("1.66666", ground, "java-api c (5/|3|)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Kandidaat K1
            - de a is 5
            - de b is 3
            Scenario deel-abs
            toetst de regel deel abs
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de c van K1 is 1,66666
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("1.66666")), "scenario == java-api");
    }

    /** FACET rekenkunde+eenheden (macht met grote exponent, exact BigInteger). Lifted from
     *  DroolsExecutionTest.macht_met_gehele_exponent_is_exact_boven_de_double_grens (L2042): 7^19. */
    @Test void arithmetic_power_large_exact() throws Exception {
        String model = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
            Regel macht
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de basis van een Heffing tot de macht 19 naar beneden afgerond op 0 decimalen.
            """;
        World w = world(model);
        Object h = w.newInstance("Heffing"); w.number(h, "setBasis", 7);
        fire(w, h);
        BigDecimal ground = w.num(h, "getNetto");
        assertNumber("11398895185373143", ground, "java-api netto (7^19)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Heffing H1
            - de basis is 7
            Scenario macht-groot
            toetst de regel macht
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de netto van H1 is 11398895185373143
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("11398895185373143")), "scenario == java-api");
    }

    /** FACET percentage (§6.8 "% van"). Lifted from ExecModelDifferentialTest Phase-H percentage fixture (L5889):
     *  basistarief 50 % van bodem 200 = 100. */
    @Test void percentage_of() throws Exception {
        String model = """
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                de bodem Numeriek (geheel getal);
            Regel R
                geldig altijd
                    De prijs van een passagier moet gesteld worden op zijn basistarief % van zijn bodem.
            """;
        World w = world(model);
        Object p = w.newInstance("Passagier"); w.number(p, "setBasistarief", 50); w.number(p, "setBodem", 200);
        fire(w, p);
        BigDecimal ground = w.num(p, "getPrijs");
        assertNumber("100", ground, "java-api prijs (50% van 200)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Passagier P1
            - het basistarief is 50
            - de bodem is 200
            Scenario percentage
            toetst de regel R
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de prijs van P1 is 100
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("100")), "scenario == java-api");
    }

    /** FACET rekenkunde+eenheden (begrenzing na afronding, floor at minimum 0). Lifted from
     *  DroolsExecutionTest.afronding_na_begrenzing_rondt_het_geheel_af_niet_de_grens (L1813): 7/2 min0 floor = 3. */
    @Test void arithmetic_clamp_minimum_rounding() throws Exception {
        String model = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
            Regel netto
                geldig altijd
                    De netto van een Heffing moet berekend worden als de basis van een Heffing gedeeld door 2, met een minimum van 0 naar beneden afgerond op 0 decimalen.
            """;
        World w = world(model);
        Object h = w.newInstance("Heffing"); w.number(h, "setBasis", 7);
        fire(w, h);
        BigDecimal ground = w.num(h, "getNetto");
        assertNumber("3", ground, "java-api netto (7/2 floor)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Heffing H1
            - de basis is 7
            Scenario floor
            toetst de regel netto
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de netto van H1 is 3
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("3")), "scenario == java-api");
    }

    /** FACET rekenkunde+eenheden (arith in condition drives a kenmerk). Lifted from
     *  DroolsExecutionTest.rekenkundige_voorwaarde_gate_vuurt_in_echte_drools (L2566): somMet*2 == somZonder. */
    @Test void arithmetic_condition_gate_characteristic() throws Exception {
        String model = """
            Objecttype de Vlucht
                de somMet Numeriek (geheel getal);
                de somZonder Numeriek (geheel getal);
                is vol kenmerk (bijvoeglijk);
            Regel m
                geldig altijd
                    Een Vlucht is vol indien de somMet van een Vlucht maal 2 gelijk is aan de somZonder van een Vlucht.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht"); w.number(v, "setSomMet", 5); w.number(v, "setSomZonder", 10);
        fire(w, v);
        boolean ground = w.bool(v, "isVol");
        assertTrue(ground, "java-api vol (5*2 == 10)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de somMet is 5
            - de somZonder is 10
            Scenario gate
            toetst de regel m
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - V1 is vol
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    // =====================================================================================================
    // parameter-gedreven
    // =====================================================================================================

    /** FACET parameter-gedreven (drempel-parameter met eenheid). Lifted from
     *  DroolsExecutionTest.parameter_numeriek_zonder_getalspecificatie_vuurt_als_drempel (L231): 70 > 67 → gepensioneerd. */
    @Test void parameter_threshold_characteristic() throws Exception {
        String model = """
            Eenheidsysteem Tijd
                het jaar           jr
            Parameter de grens : Numeriek met eenheid jr
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is gepensioneerd kenmerk (bijvoeglijk);
            Regel pensioencheck
                geldig altijd
                    Een Persoon is gepensioneerd
                    indien zijn leeftijd groter is dan de grens.
            """;
        World w = world(model);
        w.session().setGlobal("grens", new BigDecimal("67"));
        Object p = w.newInstance("Persoon"); w.number(p, "setLeeftijd", 70);
        fire(w, p);
        boolean ground = w.bool(p, "isGepensioneerd");
        assertTrue(ground, "java-api gepensioneerd (70 > 67)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Persoon P1
            - de leeftijd is 70 jr
            de parameter de grens is 67 jr
            Scenario drempel
            toetst de regel pensioencheck
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - P1 is gepensioneerd
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET parameter-gedreven (decimaal-parameter naar onbeperkt getal). Lifted from
     *  DroolsExecutionTest.onbeperkt_getal_doel_neemt_een_decimaalwaarde_aan_en_vuurt (L263): basis 12,34. */
    @Test void parameter_decimal() throws Exception {
        String model = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (getal);
            Parameter de basis : Numeriek (getal met 2 decimalen)
            Regel zet
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op de basis.
            """;
        World w = world(model);
        w.session().setGlobal("basis", new BigDecimal("12.34"));
        Object v = w.newInstance("Vlucht");
        fire(w, v);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("12.34", ground, "java-api totaalprijs");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            de parameter de basis is 12,34
            Scenario decimaal
            toetst de regel zet
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 12,34
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("12.34")), "scenario == java-api");
    }

    /** FACET parameter-gedreven (parameter override via een latere waarde). Lifted from the runner's own
     *  override contract; the Java-API leg fires only the FINAL parameter (25,00), the scenario overrides in-line. */
    @Test void parameter_override() throws Exception {
        String model = """
            Objecttype de Vlucht
                de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
            Parameter het basistarief : Numeriek (getal met 2 decimalen) met eenheid EUR;
            Regel heffingsregel
            geldig altijd
            De heffing van een Vlucht moet berekend worden als het basistarief.
            """;
        World w = world(model);
        w.session().setGlobal("basistarief", new BigDecimal("25.00"));   // the winning (later) value
        Object v = w.newInstance("Vlucht"); w.set(v, "setHeffing", new BigDecimal("0.00"), BigDecimal.class);
        fire(w, v);
        BigDecimal ground = w.num(v, "getHeffing");
        assertNumber("25.00", ground, "java-api heffing (winnende parameter)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            de parameter het basistarief is 10,00 EUR
            Scenario override
            toetst de regel heffingsregel
            gebaseerd op de situatie basis
            Gegeven is de volgende situatie:
            een Vlucht V1
            - de heffing is 0,00 EUR
            de parameter het basistarief is 25,00 EUR
            Met als verwacht resultaat:
            - de heffing van V1 is 25,00 EUR
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("25.00")), "scenario == java-api");
    }

    // =====================================================================================================
    // aggregatie (som / aantal / totaal / filter)
    // =====================================================================================================

    /** FACET aggregatie (som over de rol). Lifted from
     *  DroolsExecutionTest.aggregatie_accumuleert_over_de_join (L1577): 30 + 70 = 100. */
    @Test void aggregation_sum_over_role() throws Exception {
        String model = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel totaal
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        Object a = w.newInstance("Passagier"); w.number(a, "setPrijs", 30); w.role(a, "setReis", v);
        Object b = w.newInstance("Passagier"); w.number(b, "setPrijs", 70); w.role(b, "setReis", v);
        fire(w, v, a, b);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("100", ground, "java-api totaalprijs (30+70)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de totaalprijs is 0
            een Passagier A
            - de prijs is 30
            - de reis is V1
            een Passagier B
            - de prijs is 70
            - de reis is V1
            Scenario som
            toetst de regel totaal
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 100
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("100")), "scenario == java-api");
    }

    /** FACET aggregatie (som met subselectie-filter). Lifted from
     *  DroolsExecutionTest.vergelijking_subselectie_som_vuurt_met_filter (L592): 30 + 40 (70 excluded) = 70. */
    @Test void aggregation_sum_subselection() throws Exception {
        String model = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel R
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die kleiner zijn dan 50.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        Object a = w.newInstance("Passagier"); w.number(a, "setPrijs", 30); w.role(a, "setReis", v);
        Object b = w.newInstance("Passagier"); w.number(b, "setPrijs", 70); w.role(b, "setReis", v);
        Object c = w.newInstance("Passagier"); w.number(c, "setPrijs", 40); w.role(c, "setReis", v);
        fire(w, v, a, b, c);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("70", ground, "java-api totaalprijs (30+40)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de totaalprijs is 0
            een Passagier A
            - de prijs is 30
            - de reis is V1
            een Passagier B
            - de prijs is 70
            - de reis is V1
            een Passagier C
            - de prijs is 40
            - de reis is V1
            Scenario subselectie
            toetst de regel R
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 70
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("70")), "scenario == java-api");
    }

    /** FACET aggregatie (aantal via de rol, niet alle facts). Lifted from
     *  DroolsExecutionTest.aantal_telt_via_de_rol_niet_alle_facts (L1611): 2 bestemmingen van de reiziger. */
    @Test void aggregation_count_via_role() throws Exception {
        String model = """
            Objecttype de Reiziger
                het aantal bestemmingen Numeriek (geheel getal);
            Objecttype de Luchthaven
            Objecttype de Bestemming
            Feittype Reiziger met bestemmingen
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger betreft meerdere bestemmingen
            Regel tel
                geldig altijd
                    Het aantal bestemmingen van een Reiziger moet gesteld worden op het aantal bestemming van de reiziger.
            """;
        World w = world(model);
        Object r0 = w.newInstance("Reiziger");
        Object l1 = w.newInstance("Luchthaven"); w.role(l1, "setReiziger", r0);
        Object l2 = w.newInstance("Luchthaven"); w.role(l2, "setReiziger", r0);
        fire(w, r0, l1, l2);
        BigDecimal ground = w.num(r0, "getAantalBestemmingen");
        assertNumber("2", ground, "java-api aantal bestemmingen");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Reiziger R1
            - het aantal bestemmingen is 0
            een Luchthaven L1
            - de reiziger is R1
            een Luchthaven L2
            - de reiziger is R1
            Scenario aantal
            toetst de regel tel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - het aantal bestemmingen van R1 is 2
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("2")), "scenario == java-api");
    }

    /** FACET aggregatie (lege totaal-van is nul, niet leeg). Lifted from
     *  DroolsExecutionTest.lege_totaal_van_is_nul_niet_leegwaarde (L4436): geen passagiers → totaal 0. */
    @Test void aggregation_empty_total_is_zero() throws Exception {
        String model = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel totaal
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het totaal van de prijs van alle passagiers van de reis.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        fire(w, v);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("0", ground, "java-api totaal (geen passagiers)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            Scenario lege-totaal
            toetst de regel totaal
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 0
            """);
        pass(r);
        assertEquals(0, ground.compareTo(BigDecimal.ZERO), "scenario == java-api");
    }

    /** FACET aggregatie (lege sommatie → leegwaarde). Lifted from
     *  DroolsExecutionTest.lege_sommatie_geeft_leegwaarde_of_de_opgegeven_fallback (L4394): som zonder fallback → leeg. */
    @Test void aggregation_empty_sum_is_empty() throws Exception {
        String model = """
            Objecttype de Vlucht
                de somZonder Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel zonder
                geldig altijd
                    De somZonder van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        fire(w, v);
        Object ground = w.read(v, "getSomZonder");
        assertNull(ground, "java-api somZonder leeg (geen passagiers, geen fallback)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            Scenario lege-som
            toetst de regel zonder
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de somZonder van V1 is leeg
            """);
        pass(r);
        assertNull(ground, "scenario == java-api (beide leeg)");
    }

    /** FACET aggregatie (som over geschreven meervoud attribuut). Lifted from
     *  DroolsExecutionTest.geschreven_meervoud_vuurt_de_som_over_het_gedeclareerde_attribuut (L6631): 30+12=42. */
    @Test void aggregation_sum_written_plural() throws Exception {
        String model = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier (mv: passagiers)
                de leeftijd Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel R
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als de som van de leeftijden van alle passagiers van de reis.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        Object p1 = w.newInstance("Passagier"); w.number(p1, "setLeeftijd", 30); w.role(p1, "setReis", v);
        Object p2 = w.newInstance("Passagier"); w.number(p2, "setLeeftijd", 12); w.role(p2, "setReis", v);
        fire(w, v, p1, p2);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("42", ground, "java-api totaalprijs (30+12)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de totaalprijs is 0
            een Passagier P1
            - de leeftijd is 30
            - de reis is V1
            een Passagier P2
            - de leeftijd is 12
            - de reis is V1
            Scenario meervoud
            toetst de regel R
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 42
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("42")), "scenario == java-api");
    }

    /** FACET aggregatie (aantal met kenmerk-filter "die een kind zijn"). Lifted from
     *  DroolsExecutionTest.getelde_die_kenmerk_filter_telt_alleen_de_gefilterde_elementen (L5424): 2 kinderen. */
    @Test void aggregation_count_characteristic_filter() throws Exception {
        String model = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                is kind kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die een kind zijn.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        Object a = w.newInstance("Passagier"); w.characteristic(a, "setKind", true); w.role(a, "setReis", v);
        Object b = w.newInstance("Passagier"); w.characteristic(b, "setKind", true); w.role(b, "setReis", v);
        Object c = w.newInstance("Passagier"); w.characteristic(c, "setKind", false); w.role(c, "setReis", v);
        fire(w, v, a, b, c);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("2", ground, "java-api aantal kinderen");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de totaalprijs is 0
            een Passagier A
            - is kind
            - de reis is V1
            een Passagier B
            - is kind
            - de reis is V1
            een Passagier C
            - de reis is V1
            Scenario filter
            toetst de regel tel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 2
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("2")), "scenario == java-api");
    }

    /** FACET aggregatie (som met kenmerk-filter). Lifted from
     *  DroolsExecutionTest.som_met_die_kenmerk_filter_telt_alleen_de_gefilterde_elementen (L5481): 30+70=100. */
    @Test void aggregation_sum_characteristic_filter() throws Exception {
        String model = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                is kind kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel som
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die een kind zijn.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        Object a = w.newInstance("Passagier"); w.number(a, "setPrijs", 30); w.characteristic(a, "setKind", true); w.role(a, "setReis", v);
        Object b = w.newInstance("Passagier"); w.number(b, "setPrijs", 70); w.characteristic(b, "setKind", true); w.role(b, "setReis", v);
        Object c = w.newInstance("Passagier"); w.number(c, "setPrijs", 999); w.characteristic(c, "setKind", false); w.role(c, "setReis", v);
        fire(w, v, a, b, c);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("100", ground, "java-api som kinderen (30+70)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de totaalprijs is 0
            een Passagier A
            - de prijs is 30
            - is kind
            - de reis is V1
            een Passagier B
            - de prijs is 70
            - is kind
            - de reis is V1
            een Passagier C
            - de prijs is 999
            - de reis is V1
            Scenario som-filter
            toetst de regel som
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 100
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("100")), "scenario == java-api");
    }

    /** FACET aggregatie (telling × parameter buiten de telling). Lifted from
     *  DroolsExecutionTest.telling_maal_parameter_bindt_de_maal_buiten_de_telling (L2751): 2 × 10 = 20. */
    @Test void aggregation_count_times_parameter() throws Exception {
        String model = """
            Parameter de factor : Numeriek (geheel getal)
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis maal de factor.
            """;
        World w = world(model);
        w.session().setGlobal("factor", new BigDecimal("10"));
        Object v = w.newInstance("Vlucht");
        Object a = w.newInstance("Passagier"); w.role(a, "setReis", v);
        Object b = w.newInstance("Passagier"); w.role(b, "setReis", v);
        fire(w, v, a, b);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("20", ground, "java-api totaalprijs (2 × 10)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de totaalprijs is 0
            een Passagier A
            - de reis is V1
            een Passagier B
            - de reis is V1
            de parameter de factor is 10
            Scenario telling-maal
            toetst de regel tel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 20
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("20")), "scenario == java-api");
    }

    /** FACET aggregatie (union van twee gefilterde sets). Lifted from
     *  DroolsExecutionTest.union_telling_telt_de_vereniging_van_de_gefilterde_sets (L5518): kind ∪ student = 3. */
    @Test void aggregation_union_filter() throws Exception {
        String model = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                is kind kenmerk (bijvoeglijk);
                is student kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die een kind zijn en passagiers van de reis die een student zijn.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        Object a = w.newInstance("Passagier"); w.characteristic(a, "setKind", true); w.characteristic(a, "setStudent", false); w.role(a, "setReis", v);
        Object b = w.newInstance("Passagier"); w.characteristic(b, "setKind", false); w.characteristic(b, "setStudent", true); w.role(b, "setReis", v);
        Object c = w.newInstance("Passagier"); w.characteristic(c, "setKind", true); w.characteristic(c, "setStudent", true); w.role(c, "setReis", v);
        Object d = w.newInstance("Passagier"); w.characteristic(d, "setKind", false); w.characteristic(d, "setStudent", false); w.role(d, "setReis", v);
        fire(w, v, a, b, c, d);
        BigDecimal ground = w.num(v, "getTotaalprijs");
        assertNumber("3", ground, "java-api union (kind ∪ student)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de totaalprijs is 0
            een Passagier A
            - is kind
            - de reis is V1
            een Passagier B
            - is student
            - de reis is V1
            een Passagier C
            - is kind
            - is student
            - de reis is V1
            een Passagier D
            - de reis is V1
            Scenario union
            toetst de regel tel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaalprijs van V1 is 3
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("3")), "scenario == java-api");
    }

    // =====================================================================================================
    // navigatie via rol
    // =====================================================================================================

    /** FACET navigatie-via-rol (navigated numeric read via de to-one rol {@code reis}). Lifted from the
     *  DroolsExecutionTest navigated-read family: prijs = basistarief van de reis (77). A reflexive/wederkerig
     *  partner-role read is NOT liftable — FactModelGenerator does not materialize a self-referential role as a
     *  bean field, so the generated-bean pipeline (which the runner shares) cannot navigate it. */
    @Test void navigation_numeric_via_reis() throws Exception {
        String model = """
            Objecttype de Vlucht
                de basistarief Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel R
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op de basistarief van de reis.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht"); w.number(v, "setBasistarief", 77);
        Object p = w.newInstance("Passagier"); w.role(p, "setReis", v);
        fire(w, v, p);
        BigDecimal ground = w.num(p, "getPrijs");
        assertNumber("77", ground, "java-api prijs (basistarief van de reis)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de basistarief is 77
            een Passagier P1
            - de reis is V1
            Scenario nav-num
            toetst de regel R
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de prijs van P1 is 77
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("77")), "scenario == java-api");
    }

    /** FACET navigatie-via-rol (navigated kenmerk-negatie via {@code reis}). Lifted from the navigated
     *  kenmerk family: prijs = 1 indien de reis geen paaskorting is. */
    @Test void navigation_characteristic_negation_via_reis() throws Exception {
        String model = """
            Objecttype de Vlucht
                is reis met paaskorting kenmerk;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel neg
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op 1
                    indien de reis van een Passagier geen reis met paaskorting is.
            """;
        World w = world(model);
        Object ordinary = w.newInstance("Vlucht");   // geen paaskorting
        Object p = w.newInstance("Passagier"); w.role(p, "setReis", ordinary);
        fire(w, ordinary, p);
        BigDecimal ground = w.num(p, "getPrijs");
        assertNumber("1", ground, "java-api prijs (reis is geen paaskorting)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht GEWOON
            een Passagier P1
            - de reis is GEWOON
            Scenario nav-neg
            toetst de regel neg
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de prijs van P1 is 1
            """);
        pass(r);
        assertEquals(0, ground.compareTo(BigDecimal.ONE), "scenario == java-api");
    }

    /** FACET navigatie-via-rol (botsende rolnaam: bind de GESCHREVEN rol, niet een andere die toevallig verbindt).
     *  Lifted from DroolsExecutionTest.botsende_naam_bindt_de_geschreven_rol_niet_een_andere_die_toevallig_verbindt
     *  (L3822): Reiziger reads de afstand van de bestemming (Luchthaven=250), NIET van de plek (Bestemming=999). */
    @Test void navigation_colliding_role_name() throws Exception {
        String model = """
            Objecttype de Reiziger
                de afstand tot doel Numeriek (geheel getal) met eenheid km;
            Objecttype de Bestemming
                de afstand Numeriek (geheel getal) met eenheid km;
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Feittype bezoekt
                de bezoeker Reiziger
                de plek Bestemming
            Eén bezoeker bezoekt één plek
            Regel afstand overnemen
                geldig altijd
                    De afstand tot doel van een Reiziger moet gesteld worden op de afstand van de bestemming.
            """;
        World w = world(model);
        Object target = w.newInstance("Luchthaven"); w.number(target, "setAfstand", 250);
        Object site = w.newInstance("Bestemming"); w.number(site, "setAfstand", 999);
        Object rz = w.newInstance("Reiziger"); w.role(rz, "setBestemming", target); w.role(rz, "setPlek", site);
        fire(w, target, site, rz);
        BigDecimal ground = w.num(rz, "getAfstandTotDoel");
        assertNumber("250", ground, "java-api afstand tot doel (via de bestemming, niet de plek)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Luchthaven DOEL
            - de afstand is 250 km
            een Bestemming PLEK
            - de afstand is 999 km
            een Reiziger R1
            - de bestemming is DOEL
            - de plek is PLEK
            Scenario botsende-naam
            toetst de regel afstand overnemen
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de afstand tot doel van R1 is 250 km
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("250")), "scenario == java-api");
    }

    /** FACET navigatie-via-rol (attribuut overnemen via rol, hoofdletterongevoelig). Lifted from
     *  DroolsExecutionTest.attribuutverwijzing_bindt_hoofdletterongevoelig (L3507): afstand 250 van de bestemming. */
    @Test void navigation_attribute_overnemen() throws Exception {
        String model = """
            Objecttype de Reiziger
                de afstand tot doel Numeriek (geheel getal) met eenheid km;
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Regel afstand overnemen
                geldig altijd
                    De afstand tot doel van een Reiziger moet gesteld worden op de Afstand van de bestemming.
            """;
        World w = world(model);
        Object target = w.newInstance("Luchthaven"); w.number(target, "setAfstand", 250);
        Object rz = w.newInstance("Reiziger"); w.role(rz, "setBestemming", target);
        fire(w, target, rz);
        BigDecimal ground = w.num(rz, "getAfstandTotDoel");
        assertNumber("250", ground, "java-api afstand tot doel");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Luchthaven DOEL
            - de afstand is 250 km
            een Reiziger R1
            - de bestemming is DOEL
            Scenario overnemen
            toetst de regel afstand overnemen
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de afstand tot doel van R1 is 250 km
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("250")), "scenario == java-api");
    }

    /** FACET navigatie-via-rol (zelfstandig kenmerk van de reis via to-one). Lifted from
     *  DroolsExecutionTest.navigatie_eenvorm_kenmerkcheck_bindt_alleen_een_zelfstandig_kenmerk (L5974). */
    @Test void navigation_independent_characteristic_reis() throws Exception {
        String model = """
            Objecttype de Vlucht
                is reis met paaskorting kenmerk;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel zelfstandig
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op 1
                    indien de reis van een Passagier een reis met paaskorting is.
            """;
        World w = world(model);
        Object paas = w.newInstance("Vlucht"); w.characteristic(paas, "setReisMetPaaskorting", true);
        Object a = w.newInstance("Passagier"); w.role(a, "setReis", paas);
        fire(w, paas, a);
        BigDecimal ground = w.num(a, "getPrijs");
        assertNumber("1", ground, "java-api prijs (reis is paaskorting)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht PAAS
            - is een reis met paaskorting
            een Passagier A
            - de reis is PAAS
            Scenario paas
            toetst de regel zelfstandig
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de prijs van A is 1
            """);
        pass(r);
        assertEquals(0, ground.compareTo(BigDecimal.ONE), "scenario == java-api");
    }

    /** FACET navigatie-via-rol (self-subject reference: {@code de leeftijd van de persoon} on a lidmaatschap where
     *  the subject Persoon plays the {@code deelnemer} rol — the read must bind the SUBJECT, not a navigation).
     *  Lifted from DroolsExecutionTest.botsende_naam_kaapt_geen_eigen_subjectreferentie (L3750): gekopieerd = 30. */
    @Test void navigation_self_subject_reference() throws Exception {
        String model = """
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
                de gekopieerd Numeriek (geheel getal);
            Objecttype de Organisatie
                de omvang Numeriek (geheel getal);
            Feittype lidmaatschap
                de persoon Organisatie
                de deelnemer Persoon
            Eén persoon heeft één deelnemer
            Regel kopieer
                geldig altijd
                    De gekopieerd van een Persoon moet gesteld worden op de leeftijd van de persoon.
            """;
        World w = world(model);
        Object p = w.newInstance("Persoon"); w.number(p, "setLeeftijd", 30);
        fire(w, p);
        BigDecimal ground = w.num(p, "getGekopieerd");
        assertNumber("30", ground, "java-api gekopieerd (= eigen leeftijd)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Persoon P1
            - de leeftijd is 30
            Scenario self-subject
            toetst de regel kopieer
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de gekopieerd van P1 is 30
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("30")), "scenario == java-api");
    }

    // =====================================================================================================
    // voorwaarden
    // =====================================================================================================

    /** FACET voorwaarden (ten minste N van de criteria). Lifted from
     *  DroolsExecutionTest.ten_minste_n_van_de_criteria_telt_de_vervulde_voorwaarden (L4468): 2 of 3 → gekozen. */
    @Test void condition_at_least_n() throws Exception {
        String model = """
            Objecttype de Kandidaat
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
                de c Numeriek (geheel getal);
                is gekozen kenmerk (bijvoeglijk);
            Regel kies
                geldig altijd
                    Een Kandidaat is gekozen
                    indien hij aan ten minste 2 van de volgende voorwaarden voldoet:
                    - de a van een Kandidaat is groter dan 0
                    - de b van een Kandidaat is groter dan 0
                    - de c van een Kandidaat is groter dan 0.
            """;
        World w = world(model);
        Object k = w.newInstance("Kandidaat"); w.number(k, "setA", 5); w.number(k, "setB", 5); w.number(k, "setC", 0);
        fire(w, k);
        boolean ground = w.bool(k, "isGekozen");
        assertTrue(ground, "java-api gekozen (2 van 3)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Kandidaat K1
            - de a is 5
            - de b is 5
            - de c is 0
            Scenario ten-minste
            toetst de regel kies
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - K1 is gekozen
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET voorwaarden (kenmerk-check gate). Lifted from
     *  DroolsExecutionTest.kenmerk_check_in_voorwaarde_gateert_de_regel (L5179): oud → bonus. */
    @Test void condition_characteristic_gate() throws Exception {
        String model = """
            Objecttype de Meting
                is oud kenmerk (bijvoeglijk);
                is bonus kenmerk (bijvoeglijk);
            Regel geefbonus
                geldig altijd
                    Een Meting is bonus
                    indien de Meting oud is.
            """;
        World w = world(model);
        Object o = w.newInstance("Meting"); w.characteristic(o, "setOud", true);
        fire(w, o);
        boolean ground = w.bool(o, "isBonus");
        assertTrue(ground, "java-api bonus (oud)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Meting M1
            - is oud
            Scenario bonus
            toetst de regel geefbonus
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - M1 is bonus
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET voorwaarden (meerwoordig kenmerk in definiete vorm). Lifted from
     *  DroolsExecutionTest.meerwoordig_kenmerk_check_in_definiete_vorm_vuurt (L5324): bereikbaar per trein → bonus. */
    @Test void condition_multi_word_characteristic() throws Exception {
        String model = """
            Objecttype de Meting
                is bereikbaar per trein kenmerk (bijvoeglijk);
                is bonus kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Meting is bonus
                    indien de Meting bereikbaar per trein is.
            """;
        World w = world(model);
        Object reachable = w.newInstance("Meting"); w.characteristic(reachable, "setBereikbaarPerTrein", true);
        fire(w, reachable);
        boolean ground = w.bool(reachable, "isBonus");
        assertTrue(ground, "java-api bonus (bereikbaar per trein)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Meting M1
            - is bereikbaar per trein
            Scenario meerwoord
            toetst de regel r
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - M1 is bonus
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET voorwaarden + eenheden (cross-unit vergelijking). Lifted from
     *  DroolsExecutionTest.cross_unit_vergelijking_wordt_omgerekend_niet_rauw_vergeleken (L3389): 25 mnd > 2 jr → oud. */
    @Test void condition_cross_unit_comparison() throws Exception {
        String model = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Meting
                de duurmnd Numeriek (geheel getal) met eenheid mnd;
                de grensjr Numeriek (geheel getal) met eenheid jr;
                is oud kenmerk (bijvoeglijk);
            Regel oud
                geldig altijd
                    Een Meting is oud
                    indien de duurmnd van een Meting groter is dan de grensjr van een Meting.
            """;
        World w = world(model);
        Object above = w.newInstance("Meting"); w.number(above, "setDuurmnd", 25); w.number(above, "setGrensjr", 2);
        fire(w, above);
        boolean ground = w.bool(above, "isOud");
        assertTrue(ground, "java-api oud (25 mnd > 24 mnd)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Meting M1
            - de duurmnd is 25 mnd
            - de grensjr is 2 jr
            Scenario cross-unit-vgl
            toetst de regel oud
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - M1 is oud
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET voorwaarden + rekenkunde (begrenzing in een voorwaarde). Lifted from
     *  DroolsExecutionTest.begrenzing_in_een_voorwaarde_klemt_via_rsBegrens_en_vuurt (L2594): clamp naar bodem 60 > 50 → duur. */
    @Test void condition_clamp() throws Exception {
        String model = """
            Objecttype de Passagier
                de basistarief Numeriek (geheel getal);
                de bodem Numeriek (geheel getal);
                de plafond Numeriek (geheel getal);
                is duur kenmerk (bijvoeglijk);
            Regel cond
                geldig altijd
                    Een Passagier is duur indien de basistarief van een Passagier, met een minimum van de bodem van een Passagier en een maximum van de plafond van een Passagier groter is dan 50.
            """;
        World w = world(model);
        Object lift = w.newInstance("Passagier"); w.number(lift, "setBasistarief", 10); w.number(lift, "setBodem", 60); w.number(lift, "setPlafond", 100);
        fire(w, lift);
        boolean ground = w.bool(lift, "isDuur");
        assertTrue(ground, "java-api duur (clamp naar 60 > 50)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Passagier P1
            - de basistarief is 10
            - de bodem is 60
            - de plafond is 100
            Scenario clamp
            toetst de regel cond
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - P1 is duur
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET voorwaarden (membership: rolvervulling). Lifted from
     *  DroolsExecutionTest.membership_rolcheck_gate_de_regel_op_rolvervulling (L4740): p met reis → minderjarig. */
    @Test void condition_membership_role() throws Exception {
        String model = """
            Objecttype de Passagier
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk;
            Objecttype de Vlucht
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel rolgate
                geldig altijd
                    Een passagier is minderjarig indien hij een passagier is.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        Object p1 = w.newInstance("Passagier"); w.role(p1, "setReis", v);
        fire(w, v, p1);
        boolean ground = w.bool(p1, "isMinderjarig");
        assertTrue(ground, "java-api minderjarig (vervult de passagier-rol)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            een Passagier P1
            - de reis is V1
            Scenario rolgate
            toetst de regel rolgate
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - P1 is minderjarig
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET voorwaarden/navigatie (far-side "heeft een passagier" → exists join). Lifted from
     *  DroolsExecutionTest.membership_far_side_heeft_een_rol_lowert_naar_een_exists_join (L4822): bezet → vol. */
    @Test void condition_far_side_exists() throws Exception {
        String model = """
            Objecttype de Passagier
            Objecttype de Vlucht
                is vol kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel r
                geldig altijd
                    Een Vlucht is vol indien hij een passagier heeft.
            """;
        World w = world(model);
        Object occupied = w.newInstance("Vlucht");
        Object p = w.newInstance("Passagier"); w.role(p, "setReis", occupied);
        fire(w, occupied, p);
        boolean ground = w.bool(occupied, "isVol");
        assertTrue(ground, "java-api vol (heeft een passagier)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht BEZET
            - de passagier zijn P
            een Passagier P
            Scenario far-side
            toetst de regel r
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - BEZET is vol
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    // =====================================================================================================
    // kenmerktoekenning
    // =====================================================================================================

    /** FACET kenmerktoekenning (bezittelijk kenmerk drijft een berekening). Lifted from
     *  DroolsExecutionTest.eenvorm_heeft_een_bezittelijk_kenmerk_vuurt_de_voorwaarde (L1456): heeft recht → 5. */
    @Test void characteristic_possessive_drives_calculation() throws Exception {
        String model = """
            Objecttype de Persoon (mv: Personen) (bezield)
                het recht op duurzaamheidskorting kenmerk (bezittelijk);
                de uitkomst Numeriek (geheel getal);
            Regel R
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 5 indien hij een recht op duurzaamheidskorting heeft.
            """;
        World w = world(model);
        Object has = w.newInstance("Persoon"); w.characteristic(has, "setRechtOpDuurzaamheidskorting", true);
        fire(w, has);
        BigDecimal ground = w.num(has, "getUitkomst");
        assertNumber("5", ground, "java-api uitkomst (heeft recht)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Persoon P1
            - heeft het recht op duurzaamheidskorting
            Scenario bezit
            toetst de regel R
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de uitkomst van P1 is 5
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("5")), "scenario == java-api");
    }

    /** FACET kenmerktoekenning (zelfstandig kenmerk uit een ander zelfstandig kenmerk). Lifted from
     *  DroolsExecutionTest.membership_eenvorm_kenmerkcheck_bindt_alleen_een_zelfstandig_kenmerk (L4988): student → kind.
     *  The Java-API leg builds the model without the een-form over the bijvoeglijk kenmerk (rule bijvoeglijk), and the
     *  scenario, scoped to zelfstandig, carries only its own rule. Until uchar b536bbc9 the exec-model branch refused
     *  rule bijvoeglijk, which the test asserted. The truth-maintenance branch lowers its unresolved condition to a
     *  rule whose evaluation faults (WF-044), and the last leg asserts that fault on the whole model, in the Java-API
     *  leg and in uchar's replay of the same input. */
    @Test void characteristic_nominal_from_characteristic() throws Exception {
        String declarations = """
            Objecttype de Passagier
                is student kenmerk;
                is minderjarig kenmerk (bijvoeglijk);
                is kind kenmerk;
            Regel zelfstandig
                geldig altijd
                    Een passagier is kind indien hij een student is.
            """;
        String model = declarations + """
            Regel bijvoeglijk
                geldig altijd
                    Een passagier is kind indien hij een minderjarig is.
            """;
        World w = world(declarations);
        Object student = w.newInstance("Passagier"); w.characteristic(student, "setStudent", true); w.characteristic(student, "setMinderjarig", false);
        fire(w, student);
        boolean ground = w.bool(student, "isKind");
        assertTrue(ground, "java-api kind (is student)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Passagier P1
            - is student
            Scenario student-kind
            toetst de regel zelfstandig
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - P1 is kind
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");

        // rule bijvoeglijk, the een-form over the bijvoeglijk kenmerk: the written name binds no characteristic and no
        // role of the subject, so the resolver leaves the condition unresolved, with a warning, and the truth-maintenance
        // branch lowers it to a rule whose evaluation faults (WF-044) where the old branch refused the rule. The whole
        // model builds, and the Java-API leg gives the fault uchar's replay gives on the same input.
        World complete = world(model);
        Object minderjarige = complete.newInstance("Passagier");
        complete.characteristic(minderjarige, "setStudent", false); complete.characteristic(minderjarige, "setMinderjarig", true);
        Rekenfout error = assertThrows(Rekenfout.class, () -> fire(complete, minderjarige));
        assertEquals("fout in de regel \"bijvoeglijk\": Unresolved condition reached in evaluation", error.getMessage());

        ScenarioResultaat rb = één(model + """
            Situatie bijvoeglijk:
            een Passagier P2
            - is minderjarig
            Scenario minderjarig-kind
            toetst de regel bijvoeglijk
            gebaseerd op de situatie bijvoeglijk
            Met als verwacht resultaat:
            - P2 is kind
            """);
        assertEquals(Status.FAIL, rb.status(), () -> "verwacht FAIL: " + rb.diagnose());
        assertTrue(rb.diagnose().contains("Rekenfout: " + error.getMessage()), () -> "uchars replay: " + rb.diagnose());
    }

    /** FACET kenmerktoekenning (bezittelijk positief met lidwoord, subjectvorm). Lifted from
     *  DroolsExecutionTest.bezittelijk_positief_meerwoordig_met_lidwoord_vuurt_voor_de_personen_met_het_recht (L6079). */
    @Test void characteristic_possessive_subject_article() throws Exception {
        String model = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                het recht op duurzaamheidskorting kenmerk (bezittelijk);
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1
                    indien de Persoon het recht op duurzaamheidskorting heeft.
            """;
        World w = world(model);
        Object extended = w.newInstance("Persoon"); w.characteristic(extended, "setRechtOpDuurzaamheidskorting", true);
        fire(w, extended);
        BigDecimal ground = w.num(extended, "getUitkomst");
        assertNumber("1", ground, "java-api uitkomst (heeft het recht)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Persoon P1
            - heeft het recht op duurzaamheidskorting
            Scenario lidwoord
            toetst de regel r
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de uitkomst van P1 is 1
            """);
        pass(r);
        assertEquals(0, ground.compareTo(BigDecimal.ONE), "scenario == java-api");
    }

    // =====================================================================================================
    // kenmerk-negatie
    // =====================================================================================================

    /** FACET kenmerk-negatie (is niet een <zelfstandig kenmerk>, compound alle). Lifted from
     *  DroolsExecutionTest.is_niet_een_zelfstandig_kenmerk_negatie_vuurt (L123): ¬student ∧ ¬minderjarig → kind. */
    @Test void characteristic_negation_compound() throws Exception {
        String model = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de student kenmerk;
                is minderjarig kenmerk (bijvoeglijk);
                is kind kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een passagier is kind indien hij aan alle volgende voorwaarden voldoet:
                    - hij is niet een student
                    - hij is niet minderjarig.
            """;
        World w = world(model);
        Object none = w.newInstance("Passagier"); w.characteristic(none, "setStudent", false); w.characteristic(none, "setMinderjarig", false);
        fire(w, none);
        boolean ground = w.bool(none, "isKind");
        assertTrue(ground, "java-api kind (geen student, niet minderjarig)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Passagier P1
            Scenario negatie-compound
            toetst de regel R
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - P1 is kind
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET kenmerk-negatie (indien niet <kenmerk>). Lifted from
     *  DroolsExecutionTest.negatie_kenmerk_voorwaarde_vuurt_voor_de_niet_instanties (L6014): niet oud → 1. */
    @Test void characteristic_negation_not() throws Exception {
        String model = """
            Objecttype de Meting
                de uitkomst Numeriek (geheel getal);
                is oud kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    De uitkomst van een Meting moet gesteld worden op 1 indien de Meting niet oud is.
            """;
        World w = world(model);
        Object jong = w.newInstance("Meting"); w.characteristic(jong, "setOud", false);
        fire(w, jong);
        BigDecimal ground = w.num(jong, "getUitkomst");
        assertNumber("1", ground, "java-api uitkomst (niet oud)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Meting M1
            Scenario niet-oud
            toetst de regel r
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de uitkomst van M1 is 1
            - M1 is geen oud
            """);
        pass(r);
        assertEquals(0, ground.compareTo(BigDecimal.ONE), "scenario == java-api");
        assertFalse(w.bool(jong, "isOud"), "M1 is geen oud (kenmerk-afwezig)");
    }

    /** FACET kenmerk-negatie (geen bezittelijk kenmerk, subjectvorm). Lifted from
     *  DroolsExecutionTest.bezittelijk_geen_negatie_vuurt_voor_de_personen_zonder_het_recht (L6048). */
    @Test void characteristic_negation_no_possessive() throws Exception {
        String model = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                het recht op duurzaamheidskorting kenmerk (bezittelijk);
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1
                    indien de Persoon geen recht op duurzaamheidskorting heeft.
            """;
        World w = world(model);
        Object without = w.newInstance("Persoon");
        fire(w, without);
        BigDecimal ground = w.num(without, "getUitkomst");
        assertNumber("1", ground, "java-api uitkomst (geen recht)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Persoon P1
            Scenario geen-recht
            toetst de regel r
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de uitkomst van P1 is 1
            - P1 heeft geen recht op duurzaamheidskorting
            """);
        pass(r);
        assertEquals(0, ground.compareTo(BigDecimal.ONE), "scenario == java-api");
    }

    /** FACET kenmerk-negatie (geen zelfstandig kenmerk, rol-woord). Lifted from
     *  DroolsExecutionTest.noch_noch_is_geen_kenmerk_negatie_gate_op_de_afwezige_kenmerken (L6148): geen reiziger → 1. */
    @Test void characteristic_negation_no_nominal() throws Exception {
        String model = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                de reiziger kenmerk;
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1
                    indien de Persoon geen reiziger is.
            """;
        World w = world(model);
        Object other = w.newInstance("Persoon"); w.characteristic(other, "setReiziger", false);
        fire(w, other);
        BigDecimal ground = w.num(other, "getUitkomst");
        assertNumber("1", ground, "java-api uitkomst (geen reiziger)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Persoon P1
            Scenario geen-reiziger
            toetst de regel r
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de uitkomst van P1 is 1
            - P1 is geen reiziger
            """);
        pass(r);
        assertEquals(0, ground.compareTo(BigDecimal.ONE), "scenario == java-api");
    }

    // =====================================================================================================
    // consistentie / inconsistentie
    // =====================================================================================================

    /** FACET consistentie/inconsistentie (moet ongelijk — rondvlucht). Lifted from
     *  DroolsExecutionTest.consistentieregel_markeert_alleen_de_schendende_instantie (L3865). */
    @Test void consistency_not_equal_inconsistent() throws Exception {
        String model = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
            Regel rondvlucht
                geldig altijd
                    De vertrek van een Route moet ongelijk zijn aan de bestemming van een Route.
            """;
        World w = world(model);
        Object roundTrip = w.newInstance("Route"); w.set(roundTrip, "setVertrek", "AMS", String.class); w.set(roundTrip, "setBestemming", "AMS", String.class);
        fire(w, roundTrip);
        boolean ground = w.inconsistencies() > 0;
        assertTrue(ground, "java-api inconsistent (AMS == AMS)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Route R1
            - de vertrek is "AMS"
            - de bestemming is "AMS"
            Scenario rondvlucht
            toetst de regel rondvlucht
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de gegevens zijn inconsistent
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api (beide inconsistent)");
    }

    /** FACET consistentie/inconsistentie (moet ongelijk — consistent tak). Same rule, verschillende endpoints. */
    @Test void consistency_not_equal_consistent() throws Exception {
        String model = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
            Regel rondvlucht
                geldig altijd
                    De vertrek van een Route moet ongelijk zijn aan de bestemming van een Route.
            """;
        World w = world(model);
        Object single = w.newInstance("Route"); w.set(single, "setVertrek", "AMS", String.class); w.set(single, "setBestemming", "JFK", String.class);
        fire(w, single);
        boolean ground = w.inconsistencies() > 0;
        assertFalse(ground, "java-api consistent (AMS != JFK)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Route R1
            - de vertrek is "AMS"
            - de bestemming is "JFK"
            Scenario enkele reis
            toetst de regel rondvlucht
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de gegevens zijn consistent
            """);
        pass(r);
        assertFalse(ground, "scenario == java-api (beide consistent)");
    }

    /** FACET consistentie/inconsistentie + leeg-operand (ordening met leeg). Lifted from
     *  DroolsExecutionTest.consistentieregel_ordening_markeert_ook_de_lege_operand (L4327): leeg limiet-operand → schending. */
    @Test void consistency_ordering_empty_operand() throws Exception {
        String model = """
            Objecttype de Konto
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
            Regel saldocheck
                geldig altijd
                    De saldo van een Konto moet kleiner zijn dan de limiet van een Konto.
            """;
        World w = world(model);
        Object empty = w.newInstance("Konto"); w.number(empty, "setLimiet", 50);   // saldo leeg → schending
        fire(w, empty);
        boolean ground = w.inconsistencies() > 0;
        assertTrue(ground, "java-api inconsistent (lege saldo-operand)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Konto K1
            - de limiet is 50
            Scenario leeg-operand
            toetst de regel saldocheck
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de gegevens zijn inconsistent
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET consistentie/inconsistentie (samengestelde alle-criteria). Lifted from
     *  DroolsExecutionTest.samengestelde_consistentie_alle_criteria_markeert_elke_schending (L4358): binnen → consistent. */
    @Test void consistency_compound_consistent() throws Exception {
        String model = """
            Objecttype de Konto
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
                de minimum Numeriek (geheel getal);
            Regel grenzen
                geldig altijd
                    Een Konto moet voldoen aan alle volgende criteria:
                        - het saldo van een Konto is kleiner dan de limiet van een Konto.
                        - het saldo van een Konto is groter dan het minimum van een Konto.
            """;
        World w = world(model);
        Object within = w.newInstance("Konto"); w.number(within, "setSaldo", 50); w.number(within, "setLimiet", 100); w.number(within, "setMinimum", 0);
        fire(w, within);
        boolean ground = w.inconsistencies() > 0;
        assertFalse(ground, "java-api consistent (0 < 50 < 100)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Konto K1
            - de saldo is 50
            - de limiet is 100
            - de minimum is 0
            Scenario binnen-grenzen
            toetst de regel grenzen
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de gegevens zijn consistent
            """);
        pass(r);
        assertFalse(ground, "scenario == java-api");
    }

    /** FACET deling-door-leeg-inconsistentie (§4.8: V / leeg meldt een Inconsistentie). Lifted from
     *  DroolsExecutionTest.gedeeld_door_leeg_meldt_een_inconsistentie_maar_leeg_door_iets_niet (L2398). */
    @Test void division_by_empty_inconsistency() throws Exception {
        String model = """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de deelResultaat Numeriek (getal met 2 decimalen);
            Regel deel
                geldig altijd
                    De deelResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom gedeeld door de aftrek van een Rekensom.
            """;
        World w = world(model);
        Object valueEmpty = w.newInstance("Rekensom"); w.number(valueEmpty, "setGrondslag", 12);   // 12 / leeg → Inconsistentie
        fire(w, valueEmpty);
        boolean ground = w.inconsistencies() > 0;
        assertTrue(ground, "java-api inconsistent (12 / leeg)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Rekensom R1
            - de grondslag is 12
            Scenario deel-door-leeg
            toetst de regel deel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de gegevens zijn inconsistent
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET deling-door-leeg (leeg / iets is GEEN inconsistentie, geeft 0). Same rule, leeg grondslag / 4 = 0. */
    @Test void division_empty_by_value_consistent() throws Exception {
        String model = """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de deelResultaat Numeriek (getal met 2 decimalen);
            Regel deel
                geldig altijd
                    De deelResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom gedeeld door de aftrek van een Rekensom.
            """;
        World w = world(model);
        Object emptyRecht = w.newInstance("Rekensom"); w.number(emptyRecht, "setAftrek", 4);   // leeg / 4 = 0, geen Inconsistentie
        fire(w, emptyRecht);
        boolean groundInconsistent = w.inconsistencies() > 0;
        assertFalse(groundInconsistent, "java-api consistent (leeg / 4)");
        BigDecimal groundValue = w.num(emptyRecht, "getDeelResultaat");
        assertNumber("0", groundValue, "java-api deelResultaat (leeg / 4 = 0)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Rekensom R1
            - de aftrek is 4
            Scenario leeg-door-waarde
            toetst de regel deel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de deelResultaat van R1 is 0,00
            - de gegevens zijn consistent
            """);
        pass(r);
        assertFalse(groundInconsistent, "scenario == java-api (consistent)");
        assertEquals(0, groundValue.compareTo(BigDecimal.ZERO), "scenario == java-api (0)");
    }

    // =====================================================================================================
    // initialisatie / leeg-guard
    // =====================================================================================================

    /** FACET initialisatie/leeg-guard (§9.6: berekening wint van initialisatie; guard mist → init). Lifted from
     *  DroolsExecutionTest.initialisatie_vult_alleen_leeg_en_de_berekening_wint_ongeacht_declaratievolgorde (L2658). */
    @Test void initialisation_empty_guard() throws Exception {
        String model = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het basistarief Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
            Regel initialiseer prijs
                geldig altijd
                    De prijs van een Passagier moet geïnitialiseerd worden op 10.
            Regel bereken prijs
                geldig altijd
                    De prijs van een Passagier moet berekend worden als zijn basistarief plus 5
                    indien zijn basistarief groter is dan 50.
            """;
        World w = world(model);
        Object low = w.newInstance("Passagier"); w.number(low, "setBasistarief", 40);   // guard mist → init 10
        fire(w, low);
        BigDecimal ground = w.num(low, "getPrijs");
        assertNumber("10", ground, "java-api prijs (guard mist → init 10)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Passagier P1
            - het basistarief is 40
            Scenario init
            toetst de regels initialiseer prijs en bereken prijs
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de prijs van P1 is 10
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("10")), "scenario == java-api");
    }

    /** FACET initialisatie/leeg-guard (berekening wint als de guard raakt). Same rules, basistarief 100 → 105. */
    @Test void initialisation_calculation_wins() throws Exception {
        String model = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het basistarief Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
            Regel initialiseer prijs
                geldig altijd
                    De prijs van een Passagier moet geïnitialiseerd worden op 10.
            Regel bereken prijs
                geldig altijd
                    De prijs van een Passagier moet berekend worden als zijn basistarief plus 5
                    indien zijn basistarief groter is dan 50.
            """;
        World w = world(model);
        Object high = w.newInstance("Passagier"); w.number(high, "setBasistarief", 100);   // guard raakt → 100 + 5
        fire(w, high);
        BigDecimal ground = w.num(high, "getPrijs");
        assertNumber("105", ground, "java-api prijs (berekening wint: 100+5)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Passagier P1
            - het basistarief is 100
            Scenario bereken-wint
            toetst de regels initialiseer prijs en bereken prijs
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de prijs van P1 is 105
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("105")), "scenario == java-api");
    }

    /** FACET initialisatie/leeg-guard (leegwaarde: wortel/macht van leeg blijven leeg). Lifted from
     *  DroolsExecutionTest.wortel_en_macht_van_leeg_zijn_leeg_door_de_null_veilige_afronding (L2625). */
    @Test void empty_fallback_square_root_power_empty() throws Exception {
        String model = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de wortelr Numeriek (geheel getal);
                de machtr Numeriek (geheel getal);
            Regel w
                geldig altijd
                    De wortelr van een Heffing moet gesteld worden op de wortel van de basis van een Heffing naar beneden afgerond op 0 decimalen.
            Regel m
                geldig altijd
                    De machtr van een Heffing moet gesteld worden op de basis van een Heffing tot de macht 2 naar beneden afgerond op 0 decimalen.
            """;
        World w = world(model);
        Object empty = w.newInstance("Heffing");   // basis leeg → beide leeg
        fire(w, empty);
        Object gSquareRoot = w.read(empty, "getWortelr");
        Object gPower = w.read(empty, "getMachtr");
        assertNull(gSquareRoot, "java-api wortelr leeg");
        assertNull(gPower, "java-api machtr leeg");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Heffing H1
            Scenario leeg-wortel-macht
            toetst de regels w en m
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de wortelr van H1 is leeg
            - de machtr van H1 is leeg
            """);
        pass(r);
        assertNull(gSquareRoot, "scenario == java-api (wortelr leeg)");
        assertNull(gPower, "scenario == java-api (machtr leeg)");
    }

    /** FACET initialisatie/leeg-guard (leegwaarde-rekenkunde: leeg telt als 0 voor min/plus/maal). Lifted from
     *  DroolsExecutionTest.leegwaarde_verminderd_met_verschilt_van_min_en_plus_maal_tellen_leeg_als_nul (L2319). */
    @Test void empty_fallback_arithmetic() throws Exception {
        String model = """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de verminderdResultaat Numeriek (geheel getal);
                de minResultaat Numeriek (geheel getal);
                de plusResultaat Numeriek (geheel getal);
                de maalResultaat Numeriek (geheel getal);
            Regel rv
                geldig altijd
                    De verminderdResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom verminderd met de aftrek van een Rekensom.
            Regel rm
                geldig altijd
                    De minResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom min de aftrek van een Rekensom.
            Regel rp
                geldig altijd
                    De plusResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom plus de aftrek van een Rekensom.
            Regel rx
                geldig altijd
                    De maalResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom maal de aftrek van een Rekensom.
            """;
        World w = world(model);
        Object filled = w.newInstance("Rekensom"); w.number(filled, "setGrondslag", 10); w.number(filled, "setAftrek", 4);
        fire(w, filled);
        BigDecimal gReduced = w.num(filled, "getVerminderdResultaat");
        BigDecimal gMin = w.num(filled, "getMinResultaat");
        BigDecimal gPlus = w.num(filled, "getPlusResultaat");
        BigDecimal gTimes = w.num(filled, "getMaalResultaat");
        assertNumber("6", gReduced, "java-api verminderd (10-4)");
        assertNumber("6", gMin, "java-api min (10-4)");
        assertNumber("14", gPlus, "java-api plus (10+4)");
        assertNumber("40", gTimes, "java-api maal (10*4)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Rekensom R1
            - de grondslag is 10
            - de aftrek is 4
            Scenario leegwaarde-rekenkunde
            toetst de regels rv, rm, rp en rx
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de verminderdResultaat van R1 is 6
            - de minResultaat van R1 is 6
            - de plusResultaat van R1 is 14
            - de maalResultaat van R1 is 40
            """);
        pass(r);
        assertEquals(0, gReduced.compareTo(new BigDecimal("6")), "scenario == java-api verminderd");
        assertEquals(0, gTimes.compareTo(new BigDecimal("40")), "scenario == java-api maal");
    }

    // =====================================================================================================
    // datum / tijd
    // =====================================================================================================

    /** FACET datum/tijd (dd.-literal met tijd behoudt DatumTijd; en datum in dagen). Lifted from
     *  DroolsExecutionTest.dedato_literal_met_tijd_behoudt_het_tijdstip_als_datumtijd (L1545). */
    @Test void date_literal_with_time() throws Exception {
        String model = """
            Objecttype de Gebeurtenis (mv: Gebeurtenissen)
                het tijdstip Datum en tijd in millisecondes;
                de geldigheid Datum in dagen;
            Regel zet tijdstip
                geldig altijd
                    Het tijdstip van een Gebeurtenis moet gesteld worden op dd. 12-3-2023 11:34:42.679.
            Regel zet geldigheid
                geldig altijd
                    De geldigheid van een Gebeurtenis moet gesteld worden op dd. 12-3-2023.
            """;
        World w = world(model);
        Object g = w.newInstance("Gebeurtenis");
        fire(w, g);
        Object gTijdstip = w.read(g, "getTijdstip");
        Object gValid = w.read(g, "getGeldigheid");
        assertEquals(LocalDateTime.of(2023, 3, 12, 11, 34, 42, 679_000_000), gTijdstip, "java-api tijdstip");
        assertEquals(LocalDate.of(2023, 3, 12), gValid, "java-api geldigheid");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Gebeurtenis G1
            Scenario datumtijd
            toetst de regels zet tijdstip en zet geldigheid
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - het tijdstip van G1 is 12-03-2023 11:34:42.679
            - de geldigheid van G1 is 12-03-2023
            """);
        pass(r);
        assertEquals(LocalDateTime.of(2023, 3, 12, 11, 34, 42, 679_000_000), gTijdstip, "scenario == java-api tijdstip");
        assertEquals(LocalDate.of(2023, 3, 12), gValid, "scenario == java-api geldigheid");
    }

    /** FACET datum/tijd (eerste van twee datums = de vroegste). Lifted from
     *  DroolsExecutionTest.eerste_van_concatenatie_kiest_de_vroegste_datum (L1750): min(10 mrt, 5 mrt) = 5 mrt. */
    @Test void date_first_of() throws Exception {
        String model = """
            Objecttype de Vlucht
                de verwacht vertrek Datum in dagen;
                de daadwerkelijk vertrek Datum in dagen;
                de vroegste vertrek Datum in dagen;
            Regel vroegste
                geldig altijd
                    De vroegste vertrek van een Vlucht moet gesteld worden op de eerste van de verwacht vertrek van een Vlucht en de daadwerkelijk vertrek van een Vlucht.
            """;
        World w = world(model);
        Object v = w.newInstance("Vlucht");
        w.set(v, "setVerwachtVertrek", LocalDate.of(2025, 3, 10), LocalDate.class);
        w.set(v, "setDaadwerkelijkVertrek", LocalDate.of(2025, 3, 5), LocalDate.class);
        fire(w, v);
        Object ground = w.read(v, "getVroegsteVertrek");
        assertEquals(LocalDate.of(2025, 3, 5), ground, "java-api vroegste vertrek");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Vlucht V1
            - de verwacht vertrek is 10-03-2025
            - de daadwerkelijk vertrek is 05-03-2025
            Scenario vroegste
            toetst de regel vroegste
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de vroegste vertrek van V1 is 05-03-2025
            """);
        pass(r);
        assertEquals(LocalDate.of(2025, 3, 5), ground, "scenario == java-api");
    }

    /** FACET datum/tijd (eerste paasdag via de computus). Lifted from
     *  DroolsExecutionTest.eerste_paasdag_berekent_paaszondag_via_de_computus (L2263): 2024 → 31-03-2024. */
    @Test void date_first_easter_sunday() throws Exception {
        String model = """
            Objecttype de Klok
                de jaartal Numeriek (geheel getal);
                de gemaakt Datum in dagen;
            Regel p
                geldig altijd
                    De gemaakt van een Klok moet gesteld worden op de eerste paasdag van de jaartal van een Klok.
            """;
        World w = world(model);
        Object k = w.newInstance("Klok"); w.number(k, "setJaartal", 2024);
        fire(w, k);
        Object ground = w.read(k, "getGemaakt");
        assertEquals(LocalDate.of(2024, 3, 31), ground, "java-api eerste paasdag 2024");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Klok K1
            - de jaartal is 2024
            Scenario paasdag
            toetst de regel p
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de gemaakt van K1 is 31-03-2024
            """);
        pass(r);
        assertEquals(LocalDate.of(2024, 3, 31), ground, "scenario == java-api");
    }

    /** FACET datum/tijd (datumdelen: jaar/maand uit; datum met; uur uit). Lifted from
     *  DroolsExecutionTest.scalaire_builtins_absolute_waarde_datumdelen_en_datum_met (L2093). */
    @Test void date_parts_and_date_with() throws Exception {
        String model = """
            Objecttype de Klok
                de saldo Numeriek (geheel getal);
                de absval Numeriek (geheel getal);
                de reisdatum Datum in dagen;
                de jaartal Numeriek (geheel getal);
                de maandnr Numeriek (geheel getal);
                de bouwjaar Numeriek (geheel getal);
                de gemaakt Datum in dagen;
            Regel abs
                geldig altijd
                    De absval van een Klok moet gesteld worden op de absolute waarde van (de saldo van een Klok).
            Regel jaar
                geldig altijd
                    De jaartal van een Klok moet gesteld worden op het jaar uit de reisdatum van een Klok.
            Regel maand
                geldig altijd
                    De maandnr van een Klok moet gesteld worden op de maand uit de reisdatum van een Klok.
            Regel bouw
                geldig altijd
                    De gemaakt van een Klok moet gesteld worden op de datum met jaar: de bouwjaar van een Klok, maand: 3 en dag: 15.
            """;
        World w = world(model);
        Object k = w.newInstance("Klok");
        w.number(k, "setSaldo", -7);
        w.set(k, "setReisdatum", LocalDate.of(2021, 6, 15), LocalDate.class);
        w.number(k, "setBouwjaar", 2000);
        fire(w, k);
        BigDecimal gAbs = w.num(k, "getAbsval");
        BigDecimal gYear = w.num(k, "getJaartal");
        BigDecimal gMonth = w.num(k, "getMaandnr");
        Object gMade = w.read(k, "getGemaakt");
        assertNumber("7", gAbs, "java-api absval");
        assertNumber("2021", gYear, "java-api jaartal");
        assertNumber("6", gMonth, "java-api maandnr");
        assertEquals(LocalDate.of(2000, 3, 15), gMade, "java-api gemaakt");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Klok K1
            - de saldo is -7
            - de reisdatum is 15-06-2021
            - de bouwjaar is 2000
            Scenario datumdelen
            toetst de regels abs, jaar, maand en bouw
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de absval van K1 is 7
            - de jaartal van K1 is 2021
            - de maandnr van K1 is 6
            - de gemaakt van K1 is 15-03-2000
            """);
        pass(r);
        assertEquals(0, gAbs.compareTo(new BigDecimal("7")), "scenario == java-api absval");
        assertEquals(LocalDate.of(2000, 3, 15), gMade, "scenario == java-api gemaakt");
    }

    /** FACET datum/tijd + voorwaarde (datumliteral gelijkheid gate). Lifted from
     *  DroolsExecutionTest.datumliteral_met_voorloopnul_bouwt_en_vuurt_decimaal (L6371): geboortedatum == 08-09-2024 → duur. */
    @Test void date_equality_gate() throws Exception {
        String model = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de geboortedatum Datum in dagen;
                is duur kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is duur indien zijn geboortedatum gelijk is aan 08-09-2024.
            """;
        World w = world(model);
        Object touch = w.newInstance("Passagier"); w.set(touch, "setGeboortedatum", LocalDate.of(2024, 9, 8), LocalDate.class);
        fire(w, touch);
        boolean ground = w.bool(touch, "isDuur");
        assertTrue(ground, "java-api duur (geboortedatum == 08-09-2024)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Passagier P1
            - de geboortedatum is 08-09-2024
            Scenario datum-gate
            toetst de regel R
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - P1 is duur
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    // =====================================================================================================
    // tekst / enum
    // =====================================================================================================

    /** FACET tekst/enum (tekst-gelijkheid gate). Lifted from
     *  DroolsExecutionTest.voorwaarde_niet_numerieke_gelijkheid_beide_leeg_vuurt_niet (L1906): "A" == "A" → gemarkeerd. */
    @Test void text_equality_gate() throws Exception {
        String model = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
                is gemarkeerd kenmerk (bijvoeglijk);
            Regel mark
                geldig altijd
                    Een Route is gemarkeerd
                    indien de vertrek van een Route gelijk is aan de bestemming van een Route.
            """;
        World w = world(model);
        Object equal = w.newInstance("Route"); w.set(equal, "setVertrek", "A", String.class); w.set(equal, "setBestemming", "A", String.class);
        fire(w, equal);
        boolean ground = w.bool(equal, "isGemarkeerd");
        assertTrue(ground, "java-api gemarkeerd (A == A)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Route R1
            - de vertrek is "A"
            - de bestemming is "A"
            Scenario tekst-gelijk
            toetst de regel mark
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - R1 is gemarkeerd
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    /** FACET tekst/enum (tekstreeks-concatenatie met «...» interpolatie). Lifted from
     *  DroolsExecutionTest.tekstreeks_vuurt_een_geconcateneerde_tekstwaarde (L6391), with a text part where the source
     *  interpolated a number ("nr 528 jaar"): since uchar b536bbc9 the exec-model branch refuses a number in a text
     *  composition, whose text RUNTIME-SEMANTICS ch. 1.2 does not decide. */
    @Test void text_concatenation() throws Exception {
        String model = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de voornaam Tekst;
                de roepnaam Tekst;
            Regel R
                geldig altijd
                    De roepnaam van een Persoon moet gesteld worden op "nr «de voornaam van een Persoon» jaar".
            """;
        World w = world(model);
        Object p = w.newInstance("Persoon"); w.set(p, "setVoornaam", "Ada", String.class);
        fire(w, p);
        Object ground = w.read(p, "getRoepnaam");
        assertEquals("nr Ada jaar", ground, "java-api roepnaam");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Persoon P1
            - de voornaam is "Ada"
            Scenario tekstreeks
            toetst de regel R
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de roepnaam van P1 is "nr Ada jaar"
            """);
        pass(r);
        assertEquals("nr Ada jaar", ground, "scenario == java-api");
    }

    /** FACET tekst/enum (tekst-predicaat "numeriek met exact 9 cijfers"). Lifted from
     *  DroolsExecutionTest.numeriek_met_exact_predicaat_vuurt_voor_negen_cijfers (L5292). */
    @Test void text_predicate_nine_digits() throws Exception {
        String model = """
            Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)
                de bsn tekst Tekst;
                is geldig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Natuurlijk persoon is geldig indien zijn bsn tekst numeriek is met exact 9 cijfers.
            """;
        World w = world(model);
        Object nine = w.newInstance("NatuurlijkPersoon"); w.set(nine, "setBsnTekst", "123456789", String.class);
        fire(w, nine);
        boolean ground = w.bool(nine, "isGeldig");
        assertTrue(ground, "java-api geldig (9 cijfers)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Natuurlijk persoon NP1
            - de bsn tekst is "123456789"
            Scenario negen-cijfers
            toetst de regel R
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - NP1 is geldig
            """);
        pass(r);
        assertTrue(ground, "scenario == java-api");
    }

    // =====================================================================================================
    // peildatum / geldigheid
    // =====================================================================================================

    /** FACET peildatum/geldigheid (§4.2: de versie in wier venster de peildatum valt vuurt). Lifted from
     *  DroolsExecutionTest.rule_versie_vuurt_alleen_binnen_zijn_geldigheidsvenster (L4504): peildatum 2024 → versie #1 (100). */
    @Test void as_of_date_validity_window() throws Exception {
        String model = """
            Objecttype de Heffing
                de prijs Numeriek (geheel getal);
            Regel verhoog
                geldig vanaf 2024 t/m 2024
                    De prijs van een Heffing moet gesteld worden op 100.
                geldig vanaf 2025
                    De prijs van een Heffing moet gesteld worden op 200.
            """;
        World w = world(model);
        w.session().setGlobal("rekendatum", LocalDate.of(2024, 6, 1));
        Object h = w.newInstance("Heffing");
        fire(w, h);
        BigDecimal ground = w.num(h, "getPrijs");
        assertNumber("100", ground, "java-api prijs (2024 → versie #1)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Heffing H1
            Scenario venster-2024
            toetst de regel verhoog
            met peildatum 01-06-2024
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de prijs van H1 is 100
            """);
        pass(r);
        assertEquals(0, ground.compareTo(new BigDecimal("100")), "scenario == java-api");
    }

    /** FACET peildatum/geldigheid (buiten alle vensters → geen versie vuurt → leeg). Same rule, peildatum 2023. */
    @Test void as_of_date_outside_window_empty() throws Exception {
        String model = """
            Objecttype de Heffing
                de prijs Numeriek (geheel getal);
            Regel verhoog
                geldig vanaf 2024 t/m 2024
                    De prijs van een Heffing moet gesteld worden op 100.
                geldig vanaf 2025
                    De prijs van een Heffing moet gesteld worden op 200.
            """;
        World w = world(model);
        w.session().setGlobal("rekendatum", LocalDate.of(2023, 6, 1));
        Object h = w.newInstance("Heffing");
        fire(w, h);
        Object ground = w.read(h, "getPrijs");
        assertNull(ground, "java-api prijs leeg (2023 buiten alle vensters)");

        ScenarioResultaat r = één(model + """
            Situatie basis:
            een Heffing H1
            Scenario venster-2023
            toetst de regel verhoog
            met peildatum 01-06-2023
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de prijs van H1 is leeg
            """);
        pass(r);
        assertNull(ground, "scenario == java-api (leeg)");
    }
}
