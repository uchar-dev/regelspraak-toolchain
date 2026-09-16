package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.ContractValidator;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import org.drools.modelcompiler.KieBaseBuilder;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IR-SPEC ch. 2.2 objecttype-INHERITANCE, end to end through the toolchain's own translation layer: the
 * fact-model generator emits a subtype bean that {@code extends} its supertype's and declares only its OWN
 * members; the exec-model back-end composes the ch. 7.10 effective defaults stack per CONCRETE type and
 * replays the six cells of the subtype semantics.
 *
 * <p>The source and the expected values are the uchar golden {@code corpus/golden/jutterswet-wrakrecht}
 * (own-authored Jutterswet material), where the same four cases are pinned THREE times over — the reference
 * interpreter, clingo and uchar's Drools backend. They are taken over verbatim, so this test is an absolute-
 * value pin and not a back-end parity check. It is carried INLINE rather than as an {@code examples/} file
 * because {@link CorpusVuurTest} partitions {@code examples/**.rgs} with exact bucket counts.
 *
 * <p>The six cells, with {@code grondslag = 10,00} throughout. The effective {@code Wrakrecht} stack for
 * {@code uitkering} is, highest first: its own 55-exception, the INHERITED 99-exception, its own base
 * ({@code grondslag maal 2}, which REPLACED the inherited base).
 *
 * <table><caption>subtype semantics</caption>
 * <tr><th>case</th><th>instance</th><th>betwist</th><th>bergingsdiepte</th><th>uitkering</th></tr>
 * <tr><td>1</td><td>S1 Strandrecht</td><td>nee</td><td>—</td><td>10,00</td></tr>
 * <tr><td>1</td><td>W1 Wrakrecht</td><td>nee</td><td>5</td><td>20,00</td></tr>
 * <tr><td>2</td><td>S1</td><td>ja</td><td>—</td><td>99,00</td></tr>
 * <tr><td>2</td><td>W1</td><td>ja</td><td>5</td><td>99,00</td></tr>
 * <tr><td>3</td><td>W1</td><td>nee</td><td>25</td><td>55,00</td></tr>
 * <tr><td>4</td><td>W1</td><td>ja</td><td>25</td><td>55,00</td></tr>
 * </table>
 *
 * <p>Cases 1 and 2 together are the penguin test: a FLAT lowering gives 20,00 for case 2's W1 or 99,00 for
 * case 1's W1, never both.
 */
class OvererfdeSubtypeSemantiekTest {

    private static final String BRON = """
        Objecttype het Strandrecht (mv: Strandrechten)
            de grondslag Numeriek (niet-negatief getal met 2 decimalen);
            de uitkering Numeriek (niet-negatief getal met 2 decimalen);
            de registratiekosten Numeriek (niet-negatief getal met 2 decimalen);
            is betwist kenmerk (bijvoeglijk);

        Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
            de bergingsdiepte Numeriek (niet-negatief geheel getal);

        Objecttype de Kustgemeente (mv: Kustgemeenten) (bezield)
            het uitkeringstotaal Numeriek (niet-negatief getal met 2 decimalen);
            het registertal Numeriek (niet-negatief geheel getal);

        Feittype strandrechtregistratie
            de registrerende kustgemeente Kustgemeente
            het geregistreerd strandrecht (mv: geregistreerde strandrechten) Strandrecht
        Eén registrerende kustgemeente registreert meerdere geregistreerde strandrechten

        Regel registratiekosten van een strandrecht
            geldig altijd
                De registratiekosten van een Strandrecht moet berekend worden als 5,00.

        Regel uitkering van een strandrecht
            geldig altijd
                De uitkering van een Strandrecht moet gesteld worden op zijn grondslag.

        Uitzondering op uitkering van een strandrecht: uitkering van een betwist strandrecht
            geldig altijd
                De uitkering van een Strandrecht moet gesteld worden op 99,00
                indien het Strandrecht betwist is.

        Regel uitkering van een wrakrecht
            geldig altijd
                De uitkering van een Wrakrecht moet gesteld worden op zijn grondslag maal 2.

        Uitzondering op uitkering van een wrakrecht: uitkering van een diep wrakrecht
            geldig altijd
                De uitkering van een Wrakrecht moet gesteld worden op 55,00
                indien zijn bergingsdiepte groter is dan 20.

        Regel uitkeringstotaal van een kustgemeente
            geldig altijd
                Het uitkeringstotaal van een Kustgemeente moet berekend worden als de som van de uitkering van alle geregistreerde strandrechten van de Kustgemeente of 0,00 als die er niet zijn.

        Regel registertal van een kustgemeente
            geldig altijd
                Het registertal van een Kustgemeente moet berekend worden als het aantal geregistreerde strandrechten van de Kustgemeente.
        """;

    @Test void de_subtype_bean_erft_native_en_declareert_alleen_eigen_leden() throws Exception {
        RegelSpraakModel m = resolveer();
        Map<String, String> bronnen = FactModelGenerator.genereer(m);

        String wrakrecht = bronnen.get("Wrakrecht");
        assertNotNull(wrakrecht, () -> "verwachtte een Wrakrecht-bean, kreeg " + bronnen.keySet());
        assertTrue(wrakrecht.contains("public class Wrakrecht extends Strandrecht"),
                () -> "de subtype-bean moet native erven:\n" + wrakrecht);
        // eigen leden ALLEEN: opnieuw declareren zou het veld van de ouder verbergen
        assertFalse(wrakrecht.contains("grondslag"), () -> "geërfd lid opnieuw gedeclareerd:\n" + wrakrecht);
        assertFalse(wrakrecht.contains("registrerendeKustgemeente"),
                () -> "de referentie van de ouder opnieuw gedeclareerd:\n" + wrakrecht);
        assertEquals(1, wrakrecht.split("private ", -1).length - 1,
                () -> "Wrakrecht declareert precies EEN eigen veld (bergingsdiepte):\n" + wrakrecht);

        // de KETEN draagt zes leden: vier van Strandrecht, de referentie, en het eigen attribuut
        ClassLoader cl = FactModelGeneratorTest.compileer(bronnen);
        Class<?> w = Class.forName(FactModelGenerator.PAKKET + ".Wrakrecht", true, cl);
        assertEquals(Class.forName(FactModelGenerator.PAKKET + ".Strandrecht", true, cl), w.getSuperclass());
        for (String getter : List.of("getGrondslag", "getUitkering", "getRegistratiekosten", "isBetwist",
                                     "getRegistrerendeKustgemeente", "getBergingsdiepte"))
            assertNotNull(w.getMethod(getter), () -> "de keten mist " + getter);

        assertTrue(ContractValidator.valideer(m, cl).isEmpty(),
                () -> "de generator moet zijn eigen validator halen:\n  "
                      + String.join("\n  ", ContractValidator.valideer(m, cl)));
    }

    @Test void de_zes_cellen_van_de_subtype_semantiek() throws Exception {
        // case 1 — niets betwist: het supertype op zijn eigen basis, het subtype op de zijne (base override)
        Wereld c1 = nieuweWereld();
        Object s1 = c1.strandrecht("10.00", false), w1 = c1.wrakrecht("10.00", 5, false);
        Object k1 = c1.kustgemeente(s1, w1);
        c1.vuur(k1, s1, w1);
        assertBedrag("10.00", c1.getal(s1, "getUitkering"), "case1 S1 uitkering — de eigen basis van het supertype");
        assertBedrag("20.00", c1.getal(w1, "getUitkering"), "case1 W1 uitkering — base override: specifiek wint van generiek");
        assertBedrag("5.00", c1.getal(s1, "getRegistratiekosten"), "case1 S1 registratiekosten");
        assertBedrag("5.00", c1.getal(w1, "getRegistratiekosten"), "case1 W1 registratiekosten — de kale regel van het supertype vuurt op het subtype");
        assertBedrag("30.00", c1.getal(k1, "getUitkeringstotaal"), "case1 uitkeringstotaal — de aggregatie telt extensioneel");
        assertBedrag("2", c1.getal(k1, "getRegistertal"), "case1 registertal");
        assertEquals(0, c1.inconsistenties(), "case1 is consistent — geen §9.5-feit");

        // case 2 — beide betwist: de uitzondering van het supertype, ook op het subtype (het penguin-paar met case 1)
        Wereld c2 = nieuweWereld();
        Object s2 = c2.strandrecht("10.00", true), w2 = c2.wrakrecht("10.00", 5, true);
        Object k2 = c2.kustgemeente(s2, w2);
        c2.vuur(k2, s2, w2);
        assertBedrag("99.00", c2.getal(s2, "getUitkering"), "case2 S1 uitkering — de eigen uitzondering van het supertype");
        assertBedrag("99.00", c2.getal(w2, "getUitkering"), "case2 W1 uitkering — de geërfde uitzondering slaat de eigen basis");
        assertBedrag("5.00", c2.getal(s2, "getRegistratiekosten"), "case2 S1 registratiekosten");
        assertBedrag("5.00", c2.getal(w2, "getRegistratiekosten"), "case2 W1 registratiekosten");
        assertBedrag("198.00", c2.getal(k2, "getUitkeringstotaal"), "case2 uitkeringstotaal");
        assertBedrag("2", c2.getal(k2, "getRegistertal"), "case2 registertal");
        assertEquals(0, c2.inconsistenties(), "case2 is consistent — geen §9.5-feit");

        // case 3 — eigen uitzondering boven eigen basis
        Wereld c3 = nieuweWereld();
        Object w3 = c3.wrakrecht("10.00", 25, false);
        Object k3 = c3.kustgemeente(w3);
        c3.vuur(k3, w3);
        assertBedrag("55.00", c3.getal(w3, "getUitkering"), "case3 W1 uitkering — de eigen uitzondering boven de eigen basis");
        assertBedrag("5.00", c3.getal(w3, "getRegistratiekosten"), "case3 W1 registratiekosten");
        assertBedrag("55.00", c3.getal(k3, "getUitkeringstotaal"), "case3 uitkeringstotaal");
        assertBedrag("1", c3.getal(k3, "getRegistertal"), "case3 registertal");
        assertEquals(0, c3.inconsistenties(), "case3 is consistent — geen §9.5-feit");

        // case 4 — beide uitzonderingen toepasselijk: de definitie van het KIND wint (laaggrensvoorrang)
        Wereld c4 = nieuweWereld();
        Object w4 = c4.wrakrecht("10.00", 25, true);
        Object k4 = c4.kustgemeente(w4);
        c4.vuur(k4, w4);
        assertBedrag("55.00", c4.getal(w4, "getUitkering"), "case4 W1 uitkering — kind-wint bij botsing");
        assertBedrag("5.00", c4.getal(w4, "getRegistratiekosten"), "case4 W1 registratiekosten");
        assertBedrag("55.00", c4.getal(k4, "getUitkeringstotaal"), "case4 uitkeringstotaal");
        assertBedrag("1", c4.getal(k4, "getRegistertal"), "case4 registertal");

        // en geen §9.5 Inconsistentie: case 4 is een gewone schrijfactie, geen conflict
        assertEquals(0, c4.inconsistenties(), "case4 is een gewone schrijfactie, geen §9.5 conflict");
    }

    /**
     * The composed path the wrakrecht golden does NOT reach: the supertype carries the defaults group, the
     * subtype carries only a BARE ordinary rule. That rule must become the base of the subtype's OWN level
     * (replacing the inherited base) AND must not fire as a rule as well — with native {@code extends} a
     * pattern on the ancestor's class matches the descendant's facts, so a rule that is both a base and a
     * rule writes twice and never reaches a fixpoint.
     */
    @Test void de_kale_regel_van_een_laag_wordt_de_basis_van_die_laag_en_vuurt_niet_apart() throws Exception {
        String bron = """
            Objecttype het Grondrecht (mv: Grondrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);
                de uitkering Numeriek (niet-negatief getal met 2 decimalen);
                is betwist kenmerk (bijvoeglijk);
            Objecttype het Duinrecht is een Grondrecht (mv: Duinrechten)
            Regel uitkering van een grondrecht
                geldig altijd
                    De uitkering van een Grondrecht moet gesteld worden op zijn grondslag.
            Uitzondering op uitkering van een grondrecht: uitkering van een betwist grondrecht
                geldig altijd
                    De uitkering van een Grondrecht moet gesteld worden op 99,00
                    indien het Grondrecht betwist is.
            Regel uitkering van een duinrecht
                geldig altijd
                    De uitkering van een Duinrecht moet gesteld worden op zijn grondslag maal 3.
            """;
        RegelSpraakModel m = Resolver.resolve(bron);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        // de frontend tilt de basis + uitzondering eruit; alleen de kale subtype-regel blijft staan
        assertEquals(1, m.resolvedRegels.size(), () -> "" + m.resolvedRegels);
        assertEquals(1, m.prioritizedDefaults.size());

        Wereld w = nieuweWereld(m);
        Object d1 = w.nieuw("Duinrecht");
        d1.getClass().getMethod("setGrondslag", BigDecimal.class).invoke(d1, new BigDecimal("10.00"));
        int gevuurd = w.vuur(d1);
        assertBedrag("30.00", w.getal(d1, "getUitkering"),
                "de eigen kale regel is de basis van zijn laag en vervangt de geërfde basis");
        assertEquals(1, gevuurd,
                "de geabsorbeerde regel mag niet ook los vuren: één firing, de defaults-run zelf");

        Wereld w2 = nieuweWereld(m);
        Object d2 = w2.nieuw("Duinrecht");
        d2.getClass().getMethod("setGrondslag", BigDecimal.class).invoke(d2, new BigDecimal("10.00"));
        d2.getClass().getMethod("setBetwist", boolean.class).invoke(d2, true);
        int gevuurd2 = w2.vuur(d2);
        assertBedrag("99.00", w2.getal(d2, "getUitkering"),
                "de geërfde uitzondering slaat de eigen basis");
        assertEquals(1, gevuurd2,
                "ook op het uitzonderingspad precies één firing — geen dubbelschrijver");
    }

    // ---------------------------------------------------------------- harnas

    private static RegelSpraakModel resolveer() {
        RegelSpraakModel m = Resolver.resolve(BRON);
        assertTrue(m.errors().isEmpty(), () -> "de wrakrecht-bron hoort te resolven: " + m.errors());
        return m;
    }

    private static Wereld nieuweWereld() throws Exception { return nieuweWereld(resolveer()); }

    /** Resolve → beans genereren → in-memory compileren → exec-model bouwen onder TCCL=child → sessie. */
    private static Wereld nieuweWereld(RegelSpraakModel m) throws Exception {
        ClassLoader cl = FactModelGeneratorTest.compileer(FactModelGenerator.genereer(m));
        ExecModelTranspiler em = ExecModelTranspiler.build(m, cl);
        assertTrue(em.overgeslagen().isEmpty(),
                () -> "de exec-model-tak moet de hele bron lageren: " + em.overgeslagen());
        ClassLoader vorige = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        KieBase kb;
        try { kb = KieBaseBuilder.createKieBaseFromModel(em.model()); }
        finally { Thread.currentThread().setContextClassLoader(vorige); }
        return new Wereld(kb, cl);
    }

    private static final class Wereld {
        private final KieBase kieBase;
        private final ClassLoader cl;
        private java.util.Collection<?> laatsteObjecten = List.of();
        Wereld(KieBase kieBase, ClassLoader cl) { this.kieBase = kieBase; this.cl = cl; }

        Object nieuw(String klasse) throws Exception {
            return Class.forName(FactModelGenerator.PAKKET + "." + klasse, true, cl).getConstructor().newInstance();
        }
        Object strandrecht(String grondslag, boolean betwist) throws Exception {
            Object o = nieuw("Strandrecht");
            o.getClass().getMethod("setGrondslag", BigDecimal.class).invoke(o, new BigDecimal(grondslag));
            o.getClass().getMethod("setBetwist", boolean.class).invoke(o, betwist);
            return o;
        }
        Object wrakrecht(String grondslag, int diepte, boolean betwist) throws Exception {
            Object o = nieuw("Wrakrecht");
            o.getClass().getMethod("setGrondslag", BigDecimal.class).invoke(o, new BigDecimal(grondslag));
            o.getClass().getMethod("setBergingsdiepte", BigDecimal.class).invoke(o, new BigDecimal(diepte));
            o.getClass().getMethod("setBetwist", boolean.class).invoke(o, betwist);
            return o;
        }
        Object kustgemeente(Object... strandrechten) throws Exception {
            Object k = nieuw("Kustgemeente");
            Class<?> kt = Class.forName(FactModelGenerator.PAKKET + ".Kustgemeente", true, cl);
            for (Object s : strandrechten)
                s.getClass().getMethod("setRegistrerendeKustgemeente", kt).invoke(s, k);
            return k;
        }
        int vuur(Object... facts) {
            ClassLoader vorige = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(cl);
            KieSession s = kieBase.newKieSession();
            try {
                for (Object f : facts) s.insert(f);
                int n = s.fireAllRules(10_000);
                assertTrue(n < 10_000, "regel-loop: de fire-cap is bereikt");
                laatsteObjecten = List.copyOf(s.getObjects());
                return n;
            } finally {
                s.dispose();
                Thread.currentThread().setContextClassLoader(vorige);
            }
        }
        int inconsistenties() {
            return (int) laatsteObjecten.stream()
                    .filter(o -> o.getClass().getName().endsWith(".Inconsistentie")).count();
        }
        BigDecimal getal(Object o, String getter) throws Exception {
            return (BigDecimal) o.getClass().getMethod(getter).invoke(o);
        }
    }

    private static void assertBedrag(String verwacht, BigDecimal werkelijk, String wat) {
        assertNotNull(werkelijk, () -> wat + ": verwacht " + verwacht + ", kreeg leeg");
        assertEquals(0, new BigDecimal(verwacht).compareTo(werkelijk),
                () -> wat + ": verwacht " + verwacht + ", kreeg " + werkelijk);
    }
}
