package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.facts.Bemanningslid;
import io.github.yhavinga.regelspraak.facts.Bestelling;
import io.github.yhavinga.regelspraak.facts.Bravo;
import io.github.yhavinga.regelspraak.facts.Contingent;
import io.github.yhavinga.regelspraak.facts.Deelnemer;
import io.github.yhavinga.regelspraak.facts.Ding;
import io.github.yhavinga.regelspraak.facts.Factuur;
import io.github.yhavinga.regelspraak.facts.Heffing;
import io.github.yhavinga.regelspraak.facts.Inconsistentie;
import io.github.yhavinga.regelspraak.facts.Route;
import io.github.yhavinga.regelspraak.facts.Schijf;
import io.github.yhavinga.regelspraak.facts.Klok;
import io.github.yhavinga.regelspraak.facts.Konto;
import io.github.yhavinga.regelspraak.facts.Pot;
import io.github.yhavinga.regelspraak.facts.Meting;
import io.github.yhavinga.regelspraak.facts.NatuurlijkPersoon;
import io.github.yhavinga.regelspraak.facts.Passagier;
import io.github.yhavinga.regelspraak.facts.Persoon;
import io.github.yhavinga.regelspraak.facts.Rekensom;
import io.github.yhavinga.regelspraak.facts.Segment;
import io.github.yhavinga.regelspraak.facts.Tarief;
import io.github.yhavinga.regelspraak.facts.Tijdmeter;
import io.github.yhavinga.regelspraak.facts.Vliegtuig;
import io.github.yhavinga.regelspraak.facts.Vlucht;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.kern.Dekking;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import org.drools.modelcompiler.KieBaseBuilder;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE MIGRATION SAFETY NET (roadmap 20260624). For each fixture, build BOTH back-ends — the GOLDEN DRL path
 * ({@link DrlTranspiler} via {@link KieHelper}, interpreted) and the EXECUTABLE-MODEL path
 * ({@link ExecModelTranspiler} via {@link KieBaseBuilder#createKieBaseFromModel}, the shippable lambda path) —
 * fire each over identical facts, and assert they reach the SAME result. This replaces the DRL byte-identity
 * gate (which the model back-end has no analog for): behaviour-equivalence by firing, with DrlTranspiler as the
 * oracle. A fixture whose constructs the model back-end has not migrated yet (overgeslagen non-empty) is an
 * explicit, logged skip — so the harness tracks migration progress instead of failing on un-migrated constructs.
 */
class ExecModelDifferentialTest {

    /** A differential fixture: a rule source, a fresh-facts supplier (every call returns a NEW list of NEW beans,
     *  so the DRL fire and the model fire each get pristine facts), session globals, and what to read back from
     *  the fired facts (a single value, or a {@link #vals} list when a fixture must check more than one bean). */
    record Fixture(String naam, String src, Supplier<List<Object>> facts, Map<String, Object> globals, Function<List<Object>, Object> uit) {}

    /** A §13.4.6 objectcreatie fixture: the observable is the SET of NEW objects the run INSERTED (those in
     *  getObjects() not among the seeded facts), each mapped to a canonical descriptor (which may reference the seed
     *  facts for a stable back-reference index) — mirroring {@link FoutFixture} for inserted Inconsistentie. */
    record ObjFixture(String naam, String src, Supplier<List<Object>> facts, Map<String, Object> globals,
                      java.util.function.BiFunction<Object, List<Object>, String> canon) {}

    /** A single-Passagier fixture: the common case — one fresh Passagier, read one field back. */
    static Fixture single(String naam, String src, Supplier<Passagier> fact, Map<String, Object> globals, Function<Passagier, Object> uit) {
        return new Fixture(naam, src, () -> List.of(fact.get()), globals, l -> uit.apply((Passagier) l.get(0)));
    }

    // ---- Phase A fixtures: comparison conditions (vs global / vs literal) + gelijkstelling + kenmerktoekenning ----
    static final Fixture[] PHASE_A = {
        single("kenmerk vs global (minderjarig)",
            """
            Parameter de volwassenleeftijd : Numeriek (geheel getal) met eenheid jr
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien zijn leeftijd kleiner is dan de volwassenleeftijd.
            """,
            () -> passagier(p -> p.setLeeftijd(new BigDecimal(16))),
            Map.of("volwassenleeftijd", new BigDecimal(18)),
            Passagier::isMinderjarig),

        single("kenmerk vs literal",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien zijn leeftijd kleiner is dan 18 jr.
            """,
            () -> passagier(p -> p.setLeeftijd(new BigDecimal(20))),
            Map.of(),
            Passagier::isMinderjarig),

        single("gelijkstelling: own-attribuut kopie",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal) met eenheid EUR;
                het basistarief Numeriek (geheel getal) met eenheid EUR;
            Regel R
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op zijn basistarief.
            """,
            () -> passagier(p -> p.setBasistarief(new BigDecimal(42))),
            Map.of(),
            Passagier::getPrijs),

        single("gelijkstelling: own-attribuut plus literal",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal) met eenheid EUR;
                het basistarief Numeriek (geheel getal) met eenheid EUR;
            Regel R
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op zijn basistarief plus 5 EUR.
            """,
            () -> passagier(p -> p.setBasistarief(new BigDecimal(42))),
            Map.of(),
            Passagier::getPrijs),
    };

    // ---- PHASE VW-EQ: the two exec condition-lowering lifts that let two golden TOKA shapes leave the SKIP set.
    //      LIFT 1 (toka-39 "belaste reis"): an own Boolean ATTRIBUTE == a boolean literal (waar/onwaar). A null Boolean
    //      prop reads leeg -> GELIJK onwaar / ONGELIJK WAAR (§5.5.5 + Tabel 44), mirroring DRL's mvel `prop == true|false`.
    //      LIFT 2 (toka-27 "paaskorting"): a datum attribute == a datum-typed builtin EXPRESSION ("de eerste paasdag van
    //      (het jaar uit (...))"), lowered via scalarWaarde. Fresh Ding beans so the fixtures own their facts. ----
    static final String VW_EQ_BOOL_OT = """
            Objecttype de Ding (mv: Dingen)
                bereikbaar per trein Boolean;
                is belaste reis kenmerk;
            """;
    static final String VW_EQ_BOOL_EQ = VW_EQ_BOOL_OT + """
            Regel belaste reis
                geldig altijd
                    Een Ding is een belaste reis indien bereikbaar per trein van de Ding gelijk is aan waar.
            """;
    static final String VW_EQ_BOOL_NE = VW_EQ_BOOL_OT + """
            Regel belaste reis
                geldig altijd
                    Een Ding is een belaste reis indien bereikbaar per trein van de Ding ongelijk is aan waar.
            """;
    static final String VW_EQ_DATUM_OT = """
            Objecttype de Ding (mv: Dingen)
                de vluchtdatum Datum in dagen;
                is reis met paaskorting kenmerk;
            """;
    static final String VW_EQ_DATUM_PAAS = VW_EQ_DATUM_OT + """
            Regel paaskorting
                geldig altijd
                    Een Ding is een reis met paaskorting indien de vluchtdatum van de Ding gelijk is aan de eerste paasdag van (het jaar uit (de vluchtdatum van de Ding)).
            """;
    static Fixture ding(String naam, String src, Consumer<Ding> setup, Function<Ding, Object> uit) {
        return new Fixture(naam, src, () -> { Ding d = new Ding(); setup.accept(d); return List.<Object>of(d); },
                Map.of(), l -> uit.apply((Ding) l.get(0)));
    }
    static final Fixture[] PHASE_VW_EQ = {
        // LIFT 1: Boolean-attribute equality vs a boolean literal
        ding("bool-attr == waar, attr=true -> kenmerk true",  VW_EQ_BOOL_EQ,
            d -> d.setBereikbaarPerTrein(Boolean.TRUE),  Ding::isBelasteReis),
        ding("bool-attr == waar, attr=false -> kenmerk false", VW_EQ_BOOL_EQ,
            d -> d.setBereikbaarPerTrein(Boolean.FALSE), Ding::isBelasteReis),
        ding("bool-attr == waar, attr=leeg -> GELIJK onwaar (Tabel 44)", VW_EQ_BOOL_EQ,
            d -> {},                                     Ding::isBelasteReis),
        ding("bool-attr != waar, attr=leeg -> ONGELIJK WAAR (Tabel 44)", VW_EQ_BOOL_NE,
            d -> {},                                     Ding::isBelasteReis),
        // LIFT 2: datum equality vs the eerste-paasdag builtin EXPRESSION
        ding("vluchtdatum == eerste paasdag (2024-03-31 IS Easter 2024) -> kenmerk true", VW_EQ_DATUM_PAAS,
            d -> d.setVluchtdatum(LocalDate.of(2024, 3, 31)), Ding::isReisMetPaaskorting),
        ding("vluchtdatum == eerste paasdag (2024-04-01 is NOT Easter) -> kenmerk false", VW_EQ_DATUM_PAAS,
            d -> d.setVluchtdatum(LocalDate.of(2024, 4, 1)),  Ding::isReisMetPaaskorting),
    };
    // The hand-verified spec outcomes (Easter 2024 = 2024-03-31 via the §6.13 Gauss algorithm rsEerstePaasdag).
    static final Boolean[] PHASE_VW_EQ_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE,
    };

    // ---- PHASE TEKST-ATTR (LIFT B): a NON-numeriek (Tekst) GELIJK/ONGELIJK equality where BOTH operands are the
    //      subject's OWN attributes ("zijn vertrek gelijk/ongelijk is aan zijn bestemming") lowers in the exec CONDITION
    //      path (leafPredicate). §5.3/Tabel 38 (GELIJK): WAAR only when both present and equal (both-leeg/one-leeg ->
    //      onwaar). Tabel 44 (ONGELIJK): WAAR for exactly-one-leeg OR both-present-unequal (both-leeg -> onwaar). Route
    //      has two Tekst attributes (vertrek, bestemming) + the "gemarkeerd" kenmerk the rule sets. ----
    static final String TEKST_ATTR_OT =
        "Objecttype de Route\n    de vertrek Tekst;\n    de bestemming Tekst;\n    is gemarkeerd kenmerk (bijvoeglijk);\n";
    static final String TEKST_ATTR_GELIJK = TEKST_ATTR_OT
        + "Regel R\n    geldig altijd\n        Een Route is gemarkeerd indien zijn vertrek gelijk is aan zijn bestemming.\n";
    static final String TEKST_ATTR_ONGELIJK = TEKST_ATTR_OT
        + "Regel R\n    geldig altijd\n        Een Route is gemarkeerd indien zijn vertrek ongelijk is aan zijn bestemming.\n";
    static Fixture routeCond(String naam, String src, String vertrek, String bestemming) {
        return new Fixture(naam, src,
            () -> { Route r = new Route(); if (vertrek != null) r.setVertrek(vertrek); if (bestemming != null) r.setBestemming(bestemming);
                    return List.<Object>of(r); },
            Map.of(), l -> ((Route) l.get(0)).isGemarkeerd());
    }
    static final Fixture[] PHASE_TEKST_ATTR = {
        routeCond("GELIJK a==b (A==A) -> kenmerk true",  TEKST_ATTR_GELIJK, "A", "A"),
        routeCond("GELIJK a!=b (A!=B) -> kenmerk false", TEKST_ATTR_GELIJK, "A", "B"),
        routeCond("GELIJK one-leeg (A vs leeg) -> onwaar (Tabel 38)", TEKST_ATTR_GELIJK, "A", null),
        routeCond("GELIJK both-leeg -> onwaar (Tabel 38)",           TEKST_ATTR_GELIJK, null, null),
        routeCond("ONGELIJK a!=b (A!=B) -> kenmerk true", TEKST_ATTR_ONGELIJK, "A", "B"),
        routeCond("ONGELIJK a==b (A==A) -> kenmerk false", TEKST_ATTR_ONGELIJK, "A", "A"),
        routeCond("ONGELIJK one-leeg (A vs leeg) -> WAAR (Tabel 44)", TEKST_ATTR_ONGELIJK, "A", null),
        routeCond("ONGELIJK both-leeg -> onwaar (Tabel 44)",         TEKST_ATTR_ONGELIJK, null, null),
    };
    static final Boolean[] PHASE_TEKST_ATTR_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,   // GELIJK: only both-present-equal
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE,    // ONGELIJK: one-leeg or both-present-unequal
    };

    // ---- §13.2.5 RATIONAL NUMBER LITERAL in a VALUE (maal) position. The exec bd() used to read only the leading
    //      integer of a §13.2.5 rationeelgetal, dropping the denominator ("4/2" -> 4), so "grondslag maal 4/2" fired
    //      40 in the exec but 20 in DRL (which lowers via the shared Rationaal.van). A SILENT DRL-vs-exec divergence
    //      no fixture exercised. bd() now shares Rationaal.van (exact when the quotient terminates). Each fixture
    //      asserts the ABSOLUTE spec value (grondslag 10 maal the fraction), not just parity. ----
    static Fixture ratMaal(String naam, String frac) {
        String src =
            "Objecttype de Rekensom (mv: Rekensommen)\n" +
            "    de grondslag Numeriek (geheel getal);\n" +
            "    de maalResultaat Numeriek (getal met 4 decimalen);\n" +
            "Regel R\n" +
            "    geldig altijd\n" +
            "        De maalResultaat van een Rekensom moet berekend worden als de grondslag van een Rekensom maal " + frac + ".\n";
        return new Fixture(naam, src,
            () -> { Rekensom r = new Rekensom(); r.setGrondslag(new BigDecimal(10)); return List.<Object>of(r); },
            Map.of(),
            l -> ((Rekensom) l.get(0)).getMaalResultaat());
    }
    static final Fixture[] PHASE_RAT = {
        ratMaal("grondslag 10 maal 4/2 = 20", "4/2"),          // terminating exact -> 20 (was exec 40)
        ratMaal("grondslag 10 maal 3/2 = 15", "3/2"),          // terminating exact -> 15 (was exec 30)
        ratMaal("grondslag 10 maal 1_1/2 = 15", "1_1/2"),      // mixed a_b/c = 1.5 -> 15 (was exec 10)
        ratMaal("grondslag 10 maal 5 = 50 (control)", "5"),    // plain integer -> 50 (already parity)
    };
    static final BigDecimal[] PHASE_RAT_EXPECTED = {
        new BigDecimal(20), new BigDecimal(15), new BigDecimal(15), new BigDecimal(50),
    };
    @Test void phase_RAT_rational_literal_value_is_spec_correct() {
        for (int i = 0; i < PHASE_RAT.length; i++) {
            Fixture f = PHASE_RAT[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_RAT_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ---- §389 (gedeeld door) / §486 (percentage van): the Precisie clauses say a division/percentage result is
    //      "altijd een geheel getal of een breuk … dit zal dan altijd het exacte getal zijn". The engine computes in
    //      DECIMAL64 BigDecimal, so a NON-terminating rational diverged: "de basis maal 1/3" with basis 9 fired
    //      2.9999999999999997 (not 3), and "(1/3)% van 600" ~1.9999998 (not 2). Computed intermediates are now EXACT
    //      rationals (io.github.yhavinga.regelspraak.runtime.Breuk), collapsing to decimal only at an afronding or a terminating
    //      boundary. Each fixture asserts the ABSOLUTE spec value on BOTH back-ends (the pre-fix value is shown in the
    //      comment as the RED evidence). basis is a whole getal; the doel carries an explicit afronding (§6.1.2). ----
    static Fixture breukHeffing(String naam, String waarde, java.util.function.Consumer<Heffing> seed) {
        String src =
            "Objecttype de Heffing (mv: Heffingen)\n" +
            "    de basis Numeriek (geheel getal);\n" +
            "    de netto Numeriek (geheel getal);\n" +
            "Regel R\n" +
            "    geldig altijd\n" +
            "        De netto van een Heffing moet berekend worden als " + waarde + ".\n";
        return new Fixture(naam, src,
            () -> { Heffing h = new Heffing(); seed.accept(h); return List.<Object>of(h); },
            Map.of(),
            l -> ((Heffing) l.get(0)).getNetto());
    }
    // a numeric-comparison-gated write: netto := 7 iff the comparison holds (a Breuk-vs-Breuk exact compare).
    static Fixture breukCmp(String naam, String voorwaarde, java.util.function.Consumer<Heffing> seed) {
        String src =
            "Objecttype de Heffing (mv: Heffingen)\n" +
            "    de basis Numeriek (geheel getal);\n" +
            "    de netto Numeriek (geheel getal);\n" +
            "Regel R\n" +
            "    geldig altijd\n" +
            "        De netto van een Heffing moet gesteld worden op 7 indien " + voorwaarde + ".\n";
        return new Fixture(naam, src,
            () -> { Heffing h = new Heffing(); seed.accept(h); return List.<Object>of(h); },
            Map.of(),
            l -> ((Heffing) l.get(0)).getNetto());
    }
    static final Fixture[] PHASE_BREUK = {
        // (1) THE card's own case: 9 · 1/3 rounded to 0 dec = EXACTLY 3 (pre-fix DECIMAL64: 2.9999999999999997 -> floor 2).
        breukHeffing("9 maal 1/3 afgerond op 0 dec = 3 (was floor 2.999…)",
                "de basis van een Heffing maal 1/3 naar beneden afgerond op 0 decimalen", h -> h.setBasis(new BigDecimal(9))),
        // (2) percentage: (1/3)% van 600 rounded to 0 dec = EXACTLY 2 (pre-fix ~1.9999998 -> floor 1).
        breukHeffing("1/3 % van 600 afgerond op 0 dec = 2 (was floor 1.999…)",
                "1/3 % van 600 naar beneden afgerond op 0 decimalen", h -> {}),
        // (3) exact-terminating chain (10 gedeeld door 4 = 2.5) stays exact and unchanged — no Breuk churn. The doel
        //     is a 1-decimal getal so the 2.5 lands without an afronding (a terminating quotient collapses exactly).
        new Fixture("10 gedeeld door 4 = 2.5 (terminating, unchanged, no afronding)",
                "Objecttype de Heffing (mv: Heffingen)\n" +
                "    de basis Numeriek (geheel getal);\n" +
                "    de netto Numeriek (getal met 1 decimalen);\n" +
                "Regel R\n    geldig altijd\n" +
                "        De netto van een Heffing moet berekend worden als de basis van een Heffing gedeeld door 4.\n",
                () -> { Heffing h = new Heffing(); h.setBasis(new BigDecimal(10)); return List.<Object>of(h); },
                Map.of(), l -> ((Heffing) l.get(0)).getNetto()),
        // (4) a non-terminating intermediate feeding a COMPARISON compares EXACTLY: basis 3, 3·1/3 = 1 -> WAAR -> 7
        //     (pre-fix 3·0.3333… = 0.9999… != 1 -> ONWAAR -> no fire -> null).
        breukCmp("basis 3 maal 1/3 gelijk aan 1 -> WAAR -> netto 7 (was onwaar/null)",
                "de basis van een Heffing maal 1/3 gelijk is aan 1", h -> h.setBasis(new BigDecimal(3))),
        // (4b) the negative control: basis 4, 4·1/3 = 4/3 != 1 -> ONWAAR -> no fire -> null (still correct).
        breukCmp("basis 4 maal 1/3 gelijk aan 1 -> ONWAAR -> null",
                "de basis van een Heffing maal 1/3 gelijk is aan 1", h -> h.setBasis(new BigDecimal(4))),
    };
    static final Object[] PHASE_BREUK_EXPECTED = {
        new BigDecimal(3), new BigDecimal(2), new BigDecimal("2.5"), new BigDecimal(7), null,
    };
    @Test void phase_BREUK_exact_rational_arithmetic_is_spec_correct() {
        for (int i = 0; i < PHASE_BREUK.length; i++) {
            Fixture f = PHASE_BREUK[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_BREUK_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec (exact-rational) on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ---- §13.2.17 NUMERIC LITERAL carrying a COMPOSITE (rate) unit with a "/"-denominator ("120 EUR/jr",
    //      "10 EUR/mnd"). The literal-with-unit surface admits {@code <getal> (<afk>+ "/" <afk>+)?} (§13.2.17
    //      syntaxdiagram); the resolver used to read only eenheidMacht(0), typing "120 EUR/jr" as bare EUR and
    //      DROPPING the /jr — a EUR/mnd attribute compared/assigned against it then FAILED with a spurious
    //      "'EUR/mnd' en 'EUR' zijn niet om te rekenen" unit error (or, past that, restated the wrong unit). The
    //      literal now resolves its composite through the SAME helper the attribute datatype uses
    //      (DefinitionResolver.eenheidVan), so §4 unit checking and the EXACT cross-unit restatement
    //      (Eenheid.exacteHerschaalFactoren) apply to the LITERAL exactly as to an attribute value. Each fixture
    //      asserts the ABSOLUTE spec value in the EXACT (×N -> smaller-unit) direction: 10 EUR/mnd = 120 EUR/jr.
    static final String RATE_HDR = """
            Eenheidsysteem Geld
                de euro EUR
            Eenheidsysteem Tijd
                de maand mnd
                het jaar jr = 12 mnd
            """;
    // (c) ASSIGNMENT: 10 EUR/mnd literal INTO a EUR/jr attribute -> exact ×12 = 120 (no 1e-16 drift).
    static final String RATE_ASSIGN = RATE_HDR + """
            Objecttype de Konto (mv: Kontos)
                de saldo Numeriek (geheel getal) met eenheid EUR/jr;
            Regel R
                geldig altijd
                    De saldo van een Konto moet gesteld worden op 10 EUR/mnd.
            """;
    // (rev) ASSIGNMENT the other way: 120 EUR/jr literal INTO a EUR/mnd attribute -> ÷12 = 10 (parity-clean).
    static final String RATE_ASSIGN_REV = RATE_HDR + """
            Objecttype de Konto (mv: Kontos)
                de saldo Numeriek (geheel getal) met eenheid EUR/mnd;
            Regel R
                geldig altijd
                    De saldo van een Konto moet gesteld worden op 120 EUR/jr.
            """;
    // (b) ARITHMETIC operand: 10 EUR/mnd literal (=120 EUR/jr) plus a 30 EUR/jr attribute -> 150 EUR/jr.
    static final String RATE_ARITH = RATE_HDR + """
            Objecttype de Konto (mv: Kontos)
                de saldo Numeriek (geheel getal) met eenheid EUR/jr;
                de limiet Numeriek (geheel getal) met eenheid EUR/jr;
            Regel R
                geldig altijd
                    De saldo van een Konto moet gesteld worden op 10 EUR/mnd plus de limiet van een Konto.
            """;
    // (a) COMPARISON grens: a EUR/jr attribute > a 10 EUR/mnd literal (=120 EUR/jr). saldo 130 > 120 -> fire.
    static final String RATE_CMP = RATE_HDR + """
            Objecttype de Konto (mv: Kontos)
                de saldo Numeriek (geheel getal) met eenheid EUR/jr;
                de limiet Numeriek (geheel getal) met eenheid EUR/jr;
            Regel R
                geldig altijd
                    De limiet van een Konto moet gesteld worden op 5 EUR/jr indien zijn saldo groter is dan 10 EUR/mnd.
            """;
    static Fixture kontoFix(String naam, String src, java.util.function.Consumer<Konto> seed, Function<Konto, Object> uit) {
        return new Fixture(naam, src,
            () -> { Konto k = new Konto(); seed.accept(k); return List.<Object>of(k); },
            Map.of(),
            l -> uit.apply((Konto) l.get(0)));
    }
    static final Fixture[] PHASE_RATE_LITERAL = {
        kontoFix("(c) assign 10 EUR/mnd -> EUR/jr = 120 (exact ×12)", RATE_ASSIGN, k -> {}, Konto::getSaldo),
        kontoFix("(rev) assign 120 EUR/jr -> EUR/mnd = 10 (÷12)",      RATE_ASSIGN_REV, k -> {}, Konto::getSaldo),
        kontoFix("(b) arith 10 EUR/mnd + 30 EUR/jr = 150",            RATE_ARITH, k -> k.setLimiet(new BigDecimal(30)), Konto::getSaldo),
        kontoFix("(a) cmp saldo 130 EUR/jr > 10 EUR/mnd(=120) -> fire limiet 5", RATE_CMP, k -> k.setSaldo(new BigDecimal(130)), Konto::getLimiet),
        kontoFix("(a) cmp saldo 100 EUR/jr > 10 EUR/mnd(=120) -> no fire",       RATE_CMP, k -> k.setSaldo(new BigDecimal(100)), Konto::getLimiet),
    };
    static final Object[] PHASE_RATE_LITERAL_EXPECTED = {
        new BigDecimal(120), new BigDecimal(10), new BigDecimal(150), new BigDecimal(5), null,
    };
    @Test void phase_RATE_LITERAL_composite_unit_denominator_is_spec_correct() {
        for (int i = 0; i < PHASE_RATE_LITERAL.length; i++) {
            Fixture f = PHASE_RATE_LITERAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_RATE_LITERAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ---- §6/§13.4.16 DATE ARITHMETIC: a datum/datumtijd +/- a numeric-with-time-unit -> a new date(time) via
    //      rsDatumReken (.plus/.minus keyed on the ChronoUnit). Both back-ends call the byte-identical helper.
    //      Covers: LocalDate + MONTHS (later), - MONTHS (earlier); the DatumTijd + MINUTES spec example (the
    //      time-unit-needs-datum-tijd path); and Tabel 15 leegwaarde — leeg DATUM -> leeg, leeg DURATION -> 0
    //      (date unchanged). ----
    static final String DATUM_SRC_PLUS = """
            Objecttype de Vlucht (mv: Vluchten)
                de vluchtdatum Datum in dagen;
                de uitstelduur Numeriek (geheel getal) met eenheid mnd;
                de vroegste vertrek Datum in dagen;
            Regel R
                geldig altijd
                    De vroegste vertrek van een Vlucht moet berekend worden als de vluchtdatum van de Vlucht plus de uitstelduur van de Vlucht.
            """;
    static final String DATUM_SRC_MIN = DATUM_SRC_PLUS.replace(" plus de uitstelduur", " min de uitstelduur");
    static final String DATUM_SRC_KWARTAAL = DATUM_SRC_PLUS.replace("met eenheid mnd", "met eenheid kwartaal");   // §3.7 1 kwartaal = 3 mnd
    static final String DATUM_SRC_LIT = """
            Objecttype de Vlucht (mv: Vluchten)
                de vluchtdatum Datum in dagen;
                de vroegste vertrek Datum in dagen;
            Regel R
                geldig altijd
                    De vroegste vertrek van een Vlucht moet berekend worden als de vluchtdatum van de Vlucht plus 3 mnd.
            """;
    static final String DATUM_SRC_LITDATE = """
            Objecttype de Vlucht (mv: Vluchten)
                de uitstelduur Numeriek (geheel getal) met eenheid mnd;
                de vroegste vertrek Datum in dagen;
            Regel R
                geldig altijd
                    De vroegste vertrek van een Vlucht moet berekend worden als 15-06-2022 plus de uitstelduur van de Vlucht.
            """;
    static final String DATUMTIJD_SRC = """
            Objecttype de Vlucht (mv: Vluchten)
                de vertrekmoment Datum en tijd in millisecondes;
                de reisduur Numeriek (geheel getal) met eenheid minuut;
                de aankomstmoment Datum en tijd in millisecondes;
            Regel R
                geldig altijd
                    De aankomstmoment van een Vlucht moet berekend worden als de vertrekmoment van de Vlucht plus de reisduur van de Vlucht.
            """;
    // date arithmetic as a CONDITION operand (a shared-binairTerm path DRL lowers; the model now lowers it too via
    // leafPredicate's datum-expression branch). threshold = vluchtdatum + uitstelduur months.
    static final String DATUM_COND_SRC = """
            Objecttype de Vlucht (mv: Vluchten)
                de vluchtdatum Datum in dagen;
                de daadwerkelijk vertrek Datum in dagen;
                de uitstelduur Numeriek (geheel getal) met eenheid mnd;
                is laat kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Vlucht is laat indien de daadwerkelijk vertrek van de Vlucht later is dan de vluchtdatum van de Vlucht plus de uitstelduur van de Vlucht.
            """;
    // a datum-TIJD LITERAL operand in value-side date arithmetic (the model now parses the datetime literal).
    static final String DATUMTIJD_LIT_SRC = """
            Objecttype de Vlucht (mv: Vluchten)
                de reisduur Numeriek (geheel getal) met eenheid minuut;
                de aankomstmoment Datum en tijd in millisecondes;
            Regel R
                geldig altijd
                    De aankomstmoment van een Vlucht moet berekend worden als 15-06-2022 09:30:00.000 plus de reisduur van de Vlucht.
            """;
    static final String DATUM_COND_EQ_SRC = DATUM_COND_SRC.replace("later is dan", "gelijk is aan");
    static Fixture condFix(String naam, java.time.LocalDate daadwerkelijk) { return condFix(naam, DATUM_COND_SRC, daadwerkelijk); }
    static Fixture condFix(String naam, String src, java.time.LocalDate daadwerkelijk) {
        return new Fixture(naam, src,
                () -> { Vlucht v = new Vlucht(); v.setVluchtdatum(java.time.LocalDate.of(2022, 6, 15)); v.setUitstelduur(new BigDecimal(3)); v.setDaadwerkelijkVertrek(daadwerkelijk); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).isLaat());
    }

    static Fixture datumFix(String naam, String src, java.time.LocalDate datum, BigDecimal maanden) {
        return new Fixture(naam, src,
                () -> { Vlucht v = new Vlucht(); v.setVluchtdatum(datum); v.setUitstelduur(maanden); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).getVroegsteVertrek());
    }

    static final Fixture[] PHASE_DATUM = {
        datumFix("datum plus N maanden -> later (2022-06-15 + 3mnd)", DATUM_SRC_PLUS, java.time.LocalDate.of(2022, 6, 15), new BigDecimal(3)),
        datumFix("datum min N maanden -> earlier (2022-06-15 - 3mnd)", DATUM_SRC_MIN, java.time.LocalDate.of(2022, 6, 15), new BigDecimal(3)),
        // §3.7 a kwartaal = 3 maanden (no ChronoUnit.QUARTERS): 2022-06-15 + 2 kwartaal = +6 mnd = 2022-12-15.
        datumFix("datum plus N kwartalen -> +3mnd/kwartaal (2022-06-15 + 2 = 2022-12-15)", DATUM_SRC_KWARTAAL, java.time.LocalDate.of(2022, 6, 15), new BigDecimal(2)),
        datumFix("leeg duration -> date unchanged (Tabel 15)", DATUM_SRC_PLUS, java.time.LocalDate.of(2022, 6, 15), null),
        datumFix("leeg datum -> leeg (Tabel 15)", DATUM_SRC_PLUS, null, new BigDecimal(3)),
        new Fixture("datum-tijd plus N minuten (spec §6 example)", DATUMTIJD_SRC,
                () -> { Vlucht v = new Vlucht(); v.setVertrekmoment(java.time.LocalDateTime.of(2022, 6, 15, 9, 30, 0)); v.setReisduur(new BigDecimal(95)); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).getAankomstmoment()),
        new Fixture("datum plus LITERAL duration (3 mnd)", DATUM_SRC_LIT,
                () -> { Vlucht v = new Vlucht(); v.setVluchtdatum(java.time.LocalDate.of(2022, 6, 15)); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).getVroegsteVertrek()),
        new Fixture("datum LITERAL operand (15-06-2022) plus attr", DATUM_SRC_LITDATE,
                () -> { Vlucht v = new Vlucht(); v.setUitstelduur(new BigDecimal(3)); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).getVroegsteVertrek()),
        datumFix("month-end overflow (2022-01-31 + 1mnd -> 02-28)", DATUM_SRC_PLUS, java.time.LocalDate.of(2022, 1, 31), new BigDecimal(1)),
        condFix("CONDITION date-arith TRUE (10-01 later than 06-15+3mnd)", java.time.LocalDate.of(2022, 10, 1)),
        condFix("CONDITION date-arith FALSE (08-01 not later than 06-15+3mnd)", java.time.LocalDate.of(2022, 8, 1)),
        condFix("CONDITION date-arith GELIJK TRUE (09-15 == 06-15+3mnd)", DATUM_COND_EQ_SRC, java.time.LocalDate.of(2022, 9, 15)),
        condFix("CONDITION date-arith GELIJK FALSE (09-16 != 06-15+3mnd)", DATUM_COND_EQ_SRC, java.time.LocalDate.of(2022, 9, 16)),
        new Fixture("datum-tijd LITERAL operand (15-06-2022 09:30) plus minuten", DATUMTIJD_LIT_SRC,
                () -> { Vlucht v = new Vlucht(); v.setReisduur(new BigDecimal(95)); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).getAankomstmoment()),
        // date arithmetic whose DURATION is a Parameter-GLOBAL, as a CONDITION operand: the model binds the global
        // (voorwaarde's date-arith-with-global branch). threshold = vluchtdatum + marge (3 mnd).
        new Fixture("CONDITION date-arith with GLOBAL duration (later than vluchtdatum + marge)",
                """
                Parameter de marge : Numeriek (geheel getal) met eenheid mnd
                Objecttype de Vlucht (mv: Vluchten)
                    de vluchtdatum Datum in dagen;
                    de daadwerkelijk vertrek Datum in dagen;
                    is laat kenmerk (bijvoeglijk);
                Regel R
                    geldig altijd
                        Een Vlucht is laat indien de daadwerkelijk vertrek van de Vlucht later is dan de vluchtdatum van de Vlucht plus de marge.
                """,
                () -> { Vlucht v = new Vlucht(); v.setVluchtdatum(java.time.LocalDate.of(2022, 6, 15)); v.setDaadwerkelijkVertrek(java.time.LocalDate.of(2022, 10, 1)); return List.of(v); },
                Map.of("marge", new BigDecimal(3)), l -> ((Vlucht) l.get(0)).isLaat()),
        // the SAME date-arith-vs-global comparison branch with the marge global LEFT UNSET (null): rsDatumReken treats a
        // null duration as 0 (Tabel 15) -> vluchtdatum unchanged (06-15), daadwerkelijk vertrek 10-01 is later -> laat=true.
        // The exec must bind the null global without NPE (bindGlobal, not Map.of) — a sibling of the dagsoort-global fix.
        new Fixture("CONDITION date-arith with UNSET global duration (null -> 0)",
                """
                Parameter de marge : Numeriek (geheel getal) met eenheid mnd
                Objecttype de Vlucht (mv: Vluchten)
                    de vluchtdatum Datum in dagen;
                    de daadwerkelijk vertrek Datum in dagen;
                    is laat kenmerk (bijvoeglijk);
                Regel R
                    geldig altijd
                        Een Vlucht is laat indien de daadwerkelijk vertrek van de Vlucht later is dan de vluchtdatum van de Vlucht plus de marge.
                """,
                () -> { Vlucht v = new Vlucht(); v.setVluchtdatum(java.time.LocalDate.of(2022, 6, 15)); v.setDaadwerkelijkVertrek(java.time.LocalDate.of(2022, 10, 1)); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).isLaat()),
    };

    // ---- §8.1.5 / §9.8 DAGSOORT: a §9.8 dagsoortdefinitie ("Een dag is een kerstdag indien …") compiles to a
    //      per-date predicate; §8.1.5 dagsoortcontrole ("<datum> is een kerstdag") calls it. Both back-ends must
    //      agree (DRL `function boolean rsDagsoort_Kerstdag` <-> exec-model Predicate<LocalDate>). §5.15: a leeg
    //      datum is NEVER a dagsoort -> onwaar. kerstdag = maand 12 AND dag in {25, 26}. ----
    static final String DAGSOORT_SRC =
            """
            Dagsoort de kerstdag (mv: kerstdagen)
            Regel Kerstdag
                geldig altijd
                    Een dag is een kerstdag
                    indien de dag aan alle volgende voorwaarden voldoet:
                    - de maand uit (de dag) is gelijk aan 12
                    - de dag voldoet aan ten minste één van de volgende voorwaarden:
                        .. de dag uit (de dag) is gelijk aan 25
                        .. de dag uit (de dag) is gelijk aan 26.
            Objecttype de Vlucht
                de vluchtdatum Datum in dagen;
                is laat kenmerk (bijvoeglijk);
            Regel Kerstvlucht
                geldig altijd
                    Een Vlucht is laat
                    indien de vluchtdatum van de Vlucht een kerstdag is.
            """;
    static Fixture dagsoortFix(String naam, java.time.LocalDate datum) {
        return new Fixture(naam, DAGSOORT_SRC,
                () -> { Vlucht v = new Vlucht(); if (datum != null) v.setVluchtdatum(datum); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).isLaat());
    }
    static final Fixture[] PHASE_DAGSOORT = {
        dagsoortFix("§8.1.5 dagsoortcontrole: 25-12 is een kerstdag -> waar", java.time.LocalDate.of(2024, 12, 25)),
        dagsoortFix("§8.1.5 dagsoortcontrole: 26-12 is een kerstdag -> waar", java.time.LocalDate.of(2024, 12, 26)),
        dagsoortFix("§8.1.5 dagsoortcontrole: 24-12 is geen kerstdag -> onwaar", java.time.LocalDate.of(2024, 12, 24)),
        dagsoortFix("§8.1.5 dagsoortcontrole: 25-11 (juiste dag, foute maand) -> onwaar", java.time.LocalDate.of(2024, 11, 25)),
        dagsoortFix("§5.15 dagsoortcontrole op een leeg datum -> onwaar", null),
    };

    // §8.1.5 + Tabel 16 (spec L3224) the NEGATED dagsoortcontrole "is geen <dagsoort>" = ¬(datum is een <dagsoort>).
    // "geen" (a LOWER_ID) is greedily absorbed into the operand and recovered by the resolver -> a negated DAGSOORT
    // predicaat (NOT mis-folded into the §3.5 kenmerkcheck). §5.15-based leegwaarde: a leeg/non-kerstdag datum IS
    // "geen kerstdag" = WAAR. Lowered to "!( o != null && rsDagsoort(o) )" / a neg-XOR in both back-ends.
    static final String DAGSOORT_NEG_SRC = DAGSOORT_SRC.replace(
            "indien de vluchtdatum van de Vlucht een kerstdag is.",
            "indien de vluchtdatum van de Vlucht geen kerstdag is.");
    static Fixture dagsoortNegFix(String naam, java.time.LocalDate datum) {
        return new Fixture(naam, DAGSOORT_NEG_SRC,
                () -> { Vlucht v = new Vlucht(); if (datum != null) v.setVluchtdatum(datum); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).isLaat());
    }
    // the negated form also reaches a §6 DATE-ARITHMETIC operand ("de vluchtdatum plus 1 dag geen kerstdag is"): there
    // "geen" stays on the kaleNaam (the operand is a Binair, not an AttribuutRef), recovered by the resolver's case (a).
    static final String DAGSOORT_NEG_ARITH_SRC = DAGSOORT_SRC.replace(
            "indien de vluchtdatum van de Vlucht een kerstdag is.",
            "indien de vluchtdatum van de Vlucht plus 1 dag geen kerstdag is.");
    static Fixture dagsoortNegArithFix(String naam, java.time.LocalDate datum) {
        return new Fixture(naam, DAGSOORT_NEG_ARITH_SRC,
                () -> { Vlucht v = new Vlucht(); if (datum != null) v.setVluchtdatum(datum); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).isLaat());
    }
    static final Fixture[] PHASE_DAGSOORT_NEG = {
        dagsoortNegFix("§8.1.5 is geen kerstdag: 25-12 IS kerstdag -> onwaar", java.time.LocalDate.of(2024, 12, 25)),
        dagsoortNegFix("§8.1.5 is geen kerstdag: 24-12 geen kerstdag -> waar", java.time.LocalDate.of(2024, 12, 24)),
        dagsoortNegFix("§5.15 is geen kerstdag op een leeg datum -> WAAR (¬'nooit een dagsoort')", null),
        dagsoortNegArithFix("§6+§8.1.5 (vluchtdatum + 1 dag) geen kerstdag: 24-12+1=25-12 IS -> onwaar", java.time.LocalDate.of(2024, 12, 24)),
        dagsoortNegArithFix("§6+§8.1.5 (vluchtdatum + 1 dag) geen kerstdag: 23-12+1=24-12 NOT -> waar", java.time.LocalDate.of(2024, 12, 23)),
        dagsoortNegArithFix("§5.15 date-arith geen kerstdag op een leeg datum -> WAAR", null),
    };
    static final Boolean[] PHASE_DAGSOORT_NEG_EXPECTED = { Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE };

    // ---- §8.1.5 + §5.15 + Tabel 16 NAVIGATED dagsoortcontrole "de vluchtdatum van zijn reis (geen) kerstdag is" (NDS-1):
    //      a Passagier navigates the to-one role "reis" (a Vlucht). POSITIVE: present kerstday -> WAAR, present
    //      non-kerstday -> ONWAAR, ABSENT relation -> ONWAAR (een leegwaarde is nooit een dagsoort, §5.15). NEGATED
    //      (Tabel 16, two-valued = niet(is een)): present kerstday -> ONWAAR, present non-kerstday -> WAAR, ABSENT
    //      relation -> WAAR. Both back-ends FIRE: DRL `(neg?!:)(reis!.vluchtdatum != null && rsDagsoort(reis!.vluchtdatum))`
    //      (the `!.` short-circuits an absent relation to non-match -> negated WAAR); exec navComparisonRole leaf. ----
    static final String NDS_BASE =
        "Dagsoort de kerstdag (mv: kerstdagen)\n" +
        "Regel Kerstdag\n    geldig altijd\n        Een dag is een kerstdag\n" +
        "        indien de dag aan alle volgende voorwaarden voldoet:\n" +
        "        - de maand uit (de dag) is gelijk aan 12\n" +
        "        - de dag voldoet aan ten minste één van de volgende voorwaarden:\n" +
        "            .. de dag uit (de dag) is gelijk aan 25\n            .. de dag uit (de dag) is gelijk aan 26.\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n    is duur kenmerk (bijvoeglijk);\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    de vluchtdatum Datum in dagen;\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel Duur\n    geldig altijd\n        Een Passagier is duur indien de vluchtdatum van zijn reis %s.\n";
    static final String NDS_POS_SRC = String.format(NDS_BASE, "een kerstdag is");
    static final String NDS_NEG_SRC = String.format(NDS_BASE, "geen kerstdag is");
    static Fixture ndsFix(String naam, String src, java.time.LocalDate vd) {
        return new Fixture(naam, src,
                () -> { Passagier p = new Passagier();
                        if (vd != null) { Vlucht v = new Vlucht(); v.setVluchtdatum(vd); p.setReis(v); }
                        return List.of(p); },
                Map.of(), l -> ((Passagier) l.get(0)).isDuur());
    }
    static final Fixture[] PHASE_NDS = {
        ndsFix("NDS pos: reis.vluchtdatum 25-12 IS kerstdag -> waar",         NDS_POS_SRC, java.time.LocalDate.of(2024, 12, 25)),
        ndsFix("NDS pos: reis.vluchtdatum 24-12 geen kerstdag -> onwaar",     NDS_POS_SRC, java.time.LocalDate.of(2024, 12, 24)),
        ndsFix("NDS pos: ABSENT reis -> §5.15 leegwaarde nooit dagsoort -> onwaar", NDS_POS_SRC, null),
        ndsFix("NDS neg: reis.vluchtdatum 25-12 IS kerstdag -> geen -> onwaar",  NDS_NEG_SRC, java.time.LocalDate.of(2024, 12, 25)),
        ndsFix("NDS neg: reis.vluchtdatum 24-12 geen kerstdag -> waar",         NDS_NEG_SRC, java.time.LocalDate.of(2024, 12, 24)),
        ndsFix("NDS neg: ABSENT reis -> §5.15+Tabel16 geen kerstdag -> WAAR",    NDS_NEG_SRC, null),
    };
    static final Boolean[] PHASE_NDS_EXPECTED = { Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE };

    // ---- §6.11 datum ± N <dagsoort> ("plus de uitstelduur" in kerstdagen): step day-by-day counting the §9.8
    //      kerstdag days. Both back-ends share the loop (DRL rsDatumPlus_Kerstdag <-> RegelSpraakRuntime.
    //      rsDatumPlusDagsoort). X never counts; 0/leeg -> X unchanged; leeg datum -> leeg. ----
    static final String DAGSOORT_DUUR_SRC =
            """
            Dagsoort de kerstdag (mv: kerstdagen)
            Regel Kerstdag
                geldig altijd
                    Een dag is een kerstdag
                    indien de dag aan alle volgende voorwaarden voldoet:
                    - de maand uit (de dag) is gelijk aan 12
                    - de dag voldoet aan ten minste één van de volgende voorwaarden:
                        .. de dag uit (de dag) is gelijk aan 25
                        .. de dag uit (de dag) is gelijk aan 26.
            Objecttype de Vlucht
                de uitstelduur Numeriek (geheel getal) met eenheid kerstdagen;
                de vluchtdatum Datum in dagen;
                de vroegsteVertrek Datum in dagen;
            Regel Bereken
                geldig altijd
                    De vroegsteVertrek van een Vlucht moet berekend worden als de vluchtdatum van de Vlucht plus de uitstelduur van de Vlucht.
            """;
    static Fixture dagsoortDuurFix(String naam, java.time.LocalDate vd, int n) {
        return new Fixture(naam, DAGSOORT_DUUR_SRC,
                () -> { Vlucht v = new Vlucht(); if (vd != null) v.setVluchtdatum(vd); v.setUitstelduur(new BigDecimal(n)); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).getVroegsteVertrek());
    }
    static final Fixture[] PHASE_DAGSOORT_DUUR = {
        dagsoortDuurFix("§6.11 +2 kerstdagen (20-12 -> 26-12)", java.time.LocalDate.of(2024, 12, 20), 2),
        dagsoortDuurFix("§6.11 +1 kerstdag, X telt niet mee (25-12 -> 26-12)", java.time.LocalDate.of(2024, 12, 25), 1),
        dagsoortDuurFix("§6.11 +1 kerstdag over de jaargrens (26-12 -> volgend jaar)", java.time.LocalDate.of(2024, 12, 26), 1),
        dagsoortDuurFix("§6.11 +0 kerstdagen -> datum ongewijzigd", java.time.LocalDate.of(2024, 12, 20), 0),
        dagsoortDuurFix("§6.11 leeg datum -> leeg", null, 2),
    };

    // ---- §6.11 datum-EN-TIJD ± N <dagsoort>: §4.1/§4.2 Tabel 2/5 rule 2 — adding/subtracting a time value to a
    //      datum-EN-tijd left operand ALWAYS yields a datum-en-tijd result, so step over the DATE part (counting the
    //      §9.8 kerstdag days) and RE-ATTACH the original time-of-day. Was a symmetric marker (DrlTranspiler §6.11
    //      "vereist een datum in dagen", ExecModel returned null); now DRL rsDatumTijdPlus_Kerstdag (wraps
    //      rsDatumPlus_Kerstdag) <-> RegelSpraakRuntime.rsDatumTijdPlusDagsoort. Time-of-day preservation is the new
    //      behavior; the +0 / X-never-counts / leeg / MIN / year-boundary corners ride the shared date loop. ----
    static final String DAGSOORT_DUUR_DTD_SRC =
            """
            Dagsoort de kerstdag (mv: kerstdagen)
            Regel Kerstdag
                geldig altijd
                    Een dag is een kerstdag
                    indien de dag aan alle volgende voorwaarden voldoet:
                    - de maand uit (de dag) is gelijk aan 12
                    - de dag voldoet aan ten minste één van de volgende voorwaarden:
                        .. de dag uit (de dag) is gelijk aan 25
                        .. de dag uit (de dag) is gelijk aan 26.
            Objecttype de Vlucht
                de uitstelduur Numeriek (geheel getal) met eenheid kerstdagen;
                de vertrekmoment Datum en tijd in millisecondes;
                de aankomstmoment Datum en tijd in millisecondes;
            Regel Bereken
                geldig altijd
                    De aankomstmoment van een Vlucht moet berekend worden als de vertrekmoment van de Vlucht plus de uitstelduur van de Vlucht.
            """;
    static final String DAGSOORT_DUUR_DTD_MIN_SRC = DAGSOORT_DUUR_DTD_SRC.replace("plus de uitstelduur", "min de uitstelduur");
    static Fixture dagsoortDuurDtdFix(String naam, String src, java.time.LocalDateTime vm, int n) {
        return new Fixture(naam, src,
                () -> { Vlucht v = new Vlucht(); if (vm != null) v.setVertrekmoment(vm); v.setUitstelduur(new BigDecimal(n)); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).getAankomstmoment());
    }
    static final Fixture[] PHASE_DAGSOORT_DUUR_DTD = {
        dagsoortDuurDtdFix("§6.11/§4 rule2: +2 kerstdagen, tijd behouden (20-12 14:30:15 -> 26-12 14:30:15)",
                DAGSOORT_DUUR_DTD_SRC, java.time.LocalDateTime.of(2024, 12, 20, 14, 30, 15), 2),
        dagsoortDuurDtdFix("§6.11/§4 rule2: X (25-12) telt niet mee, tijd behouden (25-12 23:59:59 -> 26-12 23:59:59)",
                DAGSOORT_DUUR_DTD_SRC, java.time.LocalDateTime.of(2024, 12, 25, 23, 59, 59), 1),
        dagsoortDuurDtdFix("§6.11/§4 rule2: +1 kerstdag over de jaargrens, tijd behouden (26-12-2024 12:00 -> 25-12-2025 12:00)",
                DAGSOORT_DUUR_DTD_SRC, java.time.LocalDateTime.of(2024, 12, 26, 12, 0), 1),
        dagsoortDuurDtdFix("§6.11/§4 rule2: +0 kerstdagen -> datum-tijd ongewijzigd (tijd behouden)",
                DAGSOORT_DUUR_DTD_SRC, java.time.LocalDateTime.of(2024, 12, 20, 8, 0), 0),
        dagsoortDuurDtdFix("§6.11/§4 rule2: leeg datum-tijd -> leeg", DAGSOORT_DUUR_DTD_SRC, null, 2),
        dagsoortDuurDtdFix("§6.11/§4 rule2: MIN 1 kerstdag (stap terug), tijd behouden (27-12 10:15 -> 26-12 10:15)",
                DAGSOORT_DUUR_DTD_MIN_SRC, java.time.LocalDateTime.of(2024, 12, 27, 10, 15), 1),
    };
    static final Object[] PHASE_DAGSOORT_DUUR_DTD_EXPECTED = {
        java.time.LocalDateTime.of(2024, 12, 26, 14, 30, 15),
        java.time.LocalDateTime.of(2024, 12, 26, 23, 59, 59),
        java.time.LocalDateTime.of(2025, 12, 25, 12, 0),
        java.time.LocalDateTime.of(2024, 12, 20, 8, 0),
        null,
        java.time.LocalDateTime.of(2024, 12, 26, 10, 15),
    };

    // ---- §6.10 tijdsduur van A tot B in <dagsoort> ("in hele kerstdagen"): count the §9.8 kerstdag days in the
    //      half-open interval (van, tot] (the §6.11-consistent reading; signed; absolute when asked). Both back-ends
    //      share the loop (DRL rsTijdsduur_Kerstdag <-> RegelSpraakRuntime.rsTijdsduurDagsoort). ----
    static final String DAGSOORT_TDS_SRC =
            """
            Dagsoort de kerstdag (mv: kerstdagen)
            Regel Kerstdag
                geldig altijd
                    Een dag is een kerstdag
                    indien de dag aan alle volgende voorwaarden voldoet:
                    - de maand uit (de dag) is gelijk aan 12
                    - de dag voldoet aan ten minste één van de volgende voorwaarden:
                        .. de dag uit (de dag) is gelijk aan 25
                        .. de dag uit (de dag) is gelijk aan 26.
            Objecttype de Vlucht
                de vluchtdatum Datum in dagen;
                de daadwerkelijk vertrek Datum in dagen;
                de totaalprijs Numeriek (geheel getal) met eenheid kerstdagen;
            Regel Tel
                geldig altijd
                    De totaalprijs van een Vlucht moet berekend worden als de tijdsduur van de vluchtdatum van de Vlucht tot de daadwerkelijk vertrek van de Vlucht in hele kerstdagen.
            """;
    static final String DAGSOORT_TDS_ABS_SRC = DAGSOORT_TDS_SRC.replace("de tijdsduur van", "de absolute tijdsduur van");
    static Fixture dagsoortTdsFix(String naam, String src, java.time.LocalDate van, java.time.LocalDate tot) {
        return new Fixture(naam, src,
                () -> { Vlucht v = new Vlucht(); if (van != null) v.setVluchtdatum(van); if (tot != null) v.setDaadwerkelijkVertrek(tot); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).getTotaalprijs());
    }
    static final Fixture[] PHASE_DAGSOORT_TDS = {
        dagsoortTdsFix("§6.10 20-12..31-12 = 2 kerstdagen", DAGSOORT_TDS_SRC, java.time.LocalDate.of(2024, 12, 20), java.time.LocalDate.of(2024, 12, 31)),
        dagsoortTdsFix("§6.10 (van,tot]: 25-12..26-12 = 1 (start exclusief)", DAGSOORT_TDS_SRC, java.time.LocalDate.of(2024, 12, 25), java.time.LocalDate.of(2024, 12, 26)),
        dagsoortTdsFix("§6.10 omgekeerd 31-12..20-12 = -2 (negatief)", DAGSOORT_TDS_SRC, java.time.LocalDate.of(2024, 12, 31), java.time.LocalDate.of(2024, 12, 20)),
        dagsoortTdsFix("§6.10 leeg interval 20-12..20-12 = 0", DAGSOORT_TDS_SRC, java.time.LocalDate.of(2024, 12, 20), java.time.LocalDate.of(2024, 12, 20)),
        dagsoortTdsFix("§6.10 absolute omgekeerd = 2", DAGSOORT_TDS_ABS_SRC, java.time.LocalDate.of(2024, 12, 31), java.time.LocalDate.of(2024, 12, 20)),
        dagsoortTdsFix("§6.10 leeg operand -> leeg", DAGSOORT_TDS_SRC, java.time.LocalDate.of(2024, 12, 20), null),
    };

    // ---- §6.10 tijdsduur in <dagsoort> over datum-EN-TIJD operands: typeringen §4.16 L676 admits datum-en-tijd
    //      operands (both the SAME precision) and the result is a COUNT (no time carried), so both back-ends count over
    //      the DATE parts (DRL rsTijdsduurTijd_Kerstdag wraps rsTijdsduur_Kerstdag <-> RegelSpraakRuntime.
    //      rsTijdsduurTijdDagsoort). Was a symmetric marker ("vereist datum in dagen"); the day-granular count makes the
    //      time-of-day irrelevant. MIXED precision (a datum-in-dagen with a datum-en-tijd) stays a symmetric marker. ----
    static final String DAGSOORT_TDS_DTD_SRC =
            """
            Dagsoort de kerstdag (mv: kerstdagen)
            Regel Kerstdag
                geldig altijd
                    Een dag is een kerstdag
                    indien de dag aan alle volgende voorwaarden voldoet:
                    - de maand uit (de dag) is gelijk aan 12
                    - de dag voldoet aan ten minste één van de volgende voorwaarden:
                        .. de dag uit (de dag) is gelijk aan 25
                        .. de dag uit (de dag) is gelijk aan 26.
            Objecttype de Vlucht
                de vertrekmoment Datum en tijd in millisecondes;
                de aankomstmoment Datum en tijd in millisecondes;
                de totaalprijs Numeriek (geheel getal) met eenheid kerstdagen;
            Regel Tel
                geldig altijd
                    De totaalprijs van een Vlucht moet berekend worden als de tijdsduur van de vertrekmoment van de Vlucht tot de aankomstmoment van de Vlucht in hele kerstdagen.
            """;
    static final String DAGSOORT_TDS_DTD_ABS_SRC = DAGSOORT_TDS_DTD_SRC.replace("de tijdsduur van", "de absolute tijdsduur van");
    static Fixture dagsoortTdsDtdFix(String naam, String src, java.time.LocalDateTime van, java.time.LocalDateTime tot) {
        return new Fixture(naam, src,
                () -> { Vlucht v = new Vlucht(); if (van != null) v.setVertrekmoment(van); if (tot != null) v.setAankomstmoment(tot); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).getTotaalprijs());
    }
    static final Fixture[] PHASE_DAGSOORT_TDS_DTD = {
        dagsoortTdsDtdFix("§6.10/§4.16: datum-en-tijd 20-12..27-12 = 2 kerstdagen (25,26)",
                DAGSOORT_TDS_DTD_SRC, java.time.LocalDateTime.of(2024,12,20,9,0), java.time.LocalDateTime.of(2024,12,27,23,0)),
        dagsoortTdsDtdFix("§6.10/§4.16: time-of-day irrelevant (day-granular) = 2",
                DAGSOORT_TDS_DTD_SRC, java.time.LocalDateTime.of(2024,12,20,23,59,59), java.time.LocalDateTime.of(2024,12,27,0,0,1)),
        dagsoortTdsDtdFix("§6.10/§4.16: signed reversed 27-12..20-12 = -2",
                DAGSOORT_TDS_DTD_SRC, java.time.LocalDateTime.of(2024,12,27,9,0), java.time.LocalDateTime.of(2024,12,20,23,0)),
        dagsoortTdsDtdFix("§6.10/§4.16: absolute reversed = 2",
                DAGSOORT_TDS_DTD_ABS_SRC, java.time.LocalDateTime.of(2024,12,27,9,0), java.time.LocalDateTime.of(2024,12,20,23,0)),
        dagsoortTdsDtdFix("§6.10/§4.16: leeg operand -> leeg",
                DAGSOORT_TDS_DTD_SRC, null, java.time.LocalDateTime.of(2024,12,27,23,0)),
    };
    static final Object[] PHASE_DAGSOORT_TDS_DTD_EXPECTED = {
        new BigDecimal(2), new BigDecimal(2), new BigDecimal(-2), new BigDecimal(2), null,
    };

    // ---- §9.8 CROSS-DAGSOORT (the spec's central feestdag example: feestdag = kerstdag OF paasdag) + a §8.1.5
    //      control over a non-attribute (date-arithmetic) datum operand. Both exercise paths the review found
    //      untested: cross-dagsoort predicate-calling-predicate, EERSTE_PAASDAG in a §9.8 body, and the exec-model
    //      §8.1.5 scalarWaarde fallback. Both back-ends must agree. ----
    static final String DAGSOORT_FEEST_SRC =
            """
            Dagsoort de kerstdag (mv: kerstdagen)
            Dagsoort de paasdag (mv: paasdagen)
            Dagsoort de feestdag (mv: feestdagen)
            Regel Kerstdag
                geldig altijd
                    Een dag is een kerstdag
                    indien de dag aan alle volgende voorwaarden voldoet:
                    - de maand uit (de dag) is gelijk aan 12
                    - de dag voldoet aan ten minste één van de volgende voorwaarden:
                        .. de dag uit (de dag) is gelijk aan 25
                        .. de dag uit (de dag) is gelijk aan 26.
            Regel Paasdag
                geldig altijd
                    Een dag is een paasdag
                    indien de dag gelijk is aan de eerste paasdag van het jaar uit (de dag).
            Regel Feestdag
                geldig altijd
                    Een dag is een feestdag
                    indien de dag aan ten minste één van de volgende voorwaarden voldoet:
                    - de dag een kerstdag is
                    - de dag een paasdag is.
            Objecttype de Vlucht
                de vluchtdatum Datum in dagen;
                is laat kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Vlucht is laat indien de vluchtdatum van de Vlucht een feestdag is.
            """;
    static final String DAGSOORT_ARITH_SRC =
            """
            Dagsoort de kerstdag (mv: kerstdagen)
            Regel Kerstdag
                geldig altijd
                    Een dag is een kerstdag
                    indien de dag aan alle volgende voorwaarden voldoet:
                    - de maand uit (de dag) is gelijk aan 12
                    - de dag voldoet aan ten minste één van de volgende voorwaarden:
                        .. de dag uit (de dag) is gelijk aan 25
                        .. de dag uit (de dag) is gelijk aan 26.
            Objecttype de Vlucht
                de vluchtdatum Datum in dagen;
                is laat kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Vlucht is laat indien de vluchtdatum van de Vlucht plus 1 dag een kerstdag is.
            """;
    static Fixture laatFix(String naam, String src, java.time.LocalDate vd) {
        return new Fixture(naam, src,
                () -> { Vlucht v = new Vlucht(); if (vd != null) v.setVluchtdatum(vd); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).isLaat());
    }
    static final Fixture[] PHASE_DAGSOORT_X = {
        laatFix("§9.8 cross-dagsoort: 25-12 is een feestdag (via kerstdag)", DAGSOORT_FEEST_SRC, java.time.LocalDate.of(2024, 12, 25)),
        laatFix("§9.8 cross-dagsoort: 31-03-2024 is een feestdag (via paasdag/EERSTE_PAASDAG)", DAGSOORT_FEEST_SRC, java.time.LocalDate.of(2024, 3, 31)),
        laatFix("§9.8 cross-dagsoort: 15-06-2024 is geen feestdag", DAGSOORT_FEEST_SRC, java.time.LocalDate.of(2024, 6, 15)),
        laatFix("§8.1.5 over date-arith: (24-12 + 1 dag)=25-12 is een kerstdag", DAGSOORT_ARITH_SRC, java.time.LocalDate.of(2024, 12, 24)),
        laatFix("§8.1.5 over date-arith: (23-12 + 1 dag)=24-12 is geen kerstdag", DAGSOORT_ARITH_SRC, java.time.LocalDate.of(2024, 12, 23)),
    };

    // ---- §8.1.5 dagsoortcontrole over a datum operand reading a Parameter-GLOBAL (the duration is a parameter):
    //      "de vluchtdatum van de Vlucht plus de marge een kerstdag is". DRL reads the global as a free name in the
    //      rsDagsoort_<N> constraint; the exec model binds it as a pattern variable (voorwaarde's dagsoort-over-global
    //      branch). Closed-form (rsDatumReken + the §9.8 predicate): assert the ABSOLUTE value too. ----
    static final String DAGSOORT_GLOBAL_SRC =
            """
            Parameter de marge : Numeriek (geheel getal) met eenheid dagen
            Dagsoort de kerstdag (mv: kerstdagen)
            Regel Kerstdag
                geldig altijd
                    Een dag is een kerstdag
                    indien de dag aan alle volgende voorwaarden voldoet:
                    - de maand uit (de dag) is gelijk aan 12
                    - de dag voldoet aan ten minste één van de volgende voorwaarden:
                        .. de dag uit (de dag) is gelijk aan 25
                        .. de dag uit (de dag) is gelijk aan 26.
            Objecttype de Vlucht
                de vluchtdatum Datum in dagen;
                is laat kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Vlucht is laat indien de vluchtdatum van de Vlucht plus de marge een kerstdag is.
            """;
    static Fixture dagsoortGlobalFix(String naam, java.time.LocalDate vd, int marge) {
        return new Fixture(naam, DAGSOORT_GLOBAL_SRC,
                () -> { Vlucht v = new Vlucht(); if (vd != null) v.setVluchtdatum(vd); return List.of(v); },
                Map.of("marge", new BigDecimal(marge)), l -> ((Vlucht) l.get(0)).isLaat());
    }
    static Fixture dagsoortGlobalNullFix(String naam, java.time.LocalDate vd) {   // marge LEFT UNSET -> a leeg/null global
        return new Fixture(naam, DAGSOORT_GLOBAL_SRC,
                () -> { Vlucht v = new Vlucht(); if (vd != null) v.setVluchtdatum(vd); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).isLaat());
    }
    static final Fixture[] PHASE_DAGSOORT_GLOBAL = {
        dagsoortGlobalFix("§8.1.5 over global date-arith: (23-12 + 2 dagen)=25-12 is een kerstdag", java.time.LocalDate.of(2024, 12, 23), 2),
        dagsoortGlobalFix("§8.1.5 over global date-arith: (23-12 + 1 dag)=24-12 is geen kerstdag", java.time.LocalDate.of(2024, 12, 23), 1),
        dagsoortGlobalFix("§8.1.5 over global date-arith: (24-12 + 2 dagen)=26-12 is een kerstdag", java.time.LocalDate.of(2024, 12, 24), 2),
        // §13.3.8 a leeg/UNSET numeric Parameter global: rsDatumReken treats a null duration as 0 (Tabel 15) -> the datum
        // is UNCHANGED. Both back-ends must AGREE (and the exec must not NPE on Map.of(k,null) — the bindGlobal fix).
        dagsoortGlobalNullFix("§8.1.5 over UNSET global: 25-12 + leeg marge = 25-12 is een kerstdag", java.time.LocalDate.of(2024, 12, 25)),
        dagsoortGlobalNullFix("§8.1.5 over UNSET global: 23-12 + leeg marge = 23-12 is geen kerstdag", java.time.LocalDate.of(2024, 12, 23)),
    };
    static final Boolean[] PHASE_DAGSOORT_GLOBAL_EXPECTED = { Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE };

    // ---- §6.10 GENERAL (non-dagsoort) tijdsduur "de [absolute] tijdsduur van A tot B in [hele] <tijdseenheid>" =
    //      the COMPLETE units between two dates (ChronoUnit.<U>.between, signed; absolute when asked; a leeg operand ->
    //      leeg, Tabel 14). DRL lowered it (rsTijdsduur) but the exec model was UNWIRED (only the dagsoort eenheid);
    //      both now share RegelSpraakRuntime.rsTijdsduur, byte-identical to the DRL function body. Reuses the
    //      DroolsExecutionTest oracle scenario (23 jr / 281 mnd); closed-form -> assert the ABSOLUTE spec values too. ----
    static final String GENERAL_TDS_SRC =
            """
            Eenheidsysteem Tijd
                de maand            mnd
                het jaar            jr       =    12 mnd
            Objecttype de Klok
                de reisdatum Datum in dagen;
                de peildatum Datum in dagen;
                de jaartal Numeriek (geheel getal) met eenheid jr;
                de maandnr Numeriek (geheel getal) met eenheid mnd;
            Regel j
                geldig altijd
                    De jaartal van een Klok moet gesteld worden op de tijdsduur van de reisdatum van een Klok tot de peildatum van een Klok in hele jaren.
            Regel m
                geldig altijd
                    De maandnr van een Klok moet gesteld worden op de absolute tijdsduur van de peildatum van een Klok tot de reisdatum van een Klok in hele maanden.
            """;
    static Fixture generalTdsFix(String naam, java.time.LocalDate reis, java.time.LocalDate peil) {
        return new Fixture(naam, GENERAL_TDS_SRC,
                () -> { Klok k = new Klok(); if (reis != null) k.setReisdatum(reis); if (peil != null) k.setPeildatum(peil); return List.of(k); },
                Map.of(), l -> vals(((Klok) l.get(0)).getJaartal(), ((Klok) l.get(0)).getMaandnr()));
    }
    static final Fixture[] PHASE_TDS = {
        generalTdsFix("§6.10 2000-01-15 -> 2023-06-20 = 23 hele jaren / 281 mnd (abs)", java.time.LocalDate.of(2000, 1, 15), java.time.LocalDate.of(2023, 6, 20)),
        generalTdsFix("§6.10 omgekeerd = -23 jaren (signed) / 281 mnd (abs)", java.time.LocalDate.of(2023, 6, 20), java.time.LocalDate.of(2000, 1, 15)),
        generalTdsFix("§6.10 zelfde datum = 0 jr / 0 mnd", java.time.LocalDate.of(2024, 6, 14), java.time.LocalDate.of(2024, 6, 14)),
        generalTdsFix("§6.10 lege peildatum -> leeg", java.time.LocalDate.of(2000, 1, 15), null),
    };
    static final Object[] PHASE_TDS_EXPECTED = {
        vals(new BigDecimal(23), new BigDecimal(281)),
        vals(new BigDecimal(-23), new BigDecimal(281)),   // jaartal signed (negative), maandnr absolute (positive)
        vals(BigDecimal.ZERO, BigDecimal.ZERO),
        vals(null, null),
    };

    // §6.10 general tijdsduur as a CONDITION operand — the shared scalarFunctie lowering reaches the numeric-
    // expression-LHS comparison context too ("laat iff de tijdsduur ... in maanden groter is dan 3"). DRL lowers the
    // same expression as the constraint operand; the parity sweep must confirm the condition context agrees.
    static final String GENERAL_TDS_COND_SRC =
            """
            Objecttype de Vlucht (mv: Vluchten)
                de vluchtdatum Datum in dagen;
                de daadwerkelijk vertrek Datum in dagen;
                is laat kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Vlucht is laat indien de tijdsduur van de vluchtdatum van de Vlucht tot de daadwerkelijk vertrek van de Vlucht in hele maanden groter is dan 3.
            """;
    static Fixture generalTdsCondFix(String naam, java.time.LocalDate van, java.time.LocalDate tot) {
        return new Fixture(naam, GENERAL_TDS_COND_SRC,
                () -> { Vlucht v = new Vlucht(); v.setVluchtdatum(van); v.setDaadwerkelijkVertrek(tot); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).isLaat());
    }
    static final Fixture[] PHASE_TDS_COND = {
        generalTdsCondFix("§6.10 cond: 5 maanden > 3 -> laat", java.time.LocalDate.of(2024, 1, 15), java.time.LocalDate.of(2024, 6, 20)),
        generalTdsCondFix("§6.10 cond: 2 maanden niet > 3 -> niet laat", java.time.LocalDate.of(2024, 1, 15), java.time.LocalDate.of(2024, 3, 20)),
    };
    static final Boolean[] PHASE_TDS_COND_EXPECTED = { Boolean.TRUE, Boolean.FALSE };

    // ---- §12 BESLISTABEL fixtures: a captured pipe-table desugars (BeslistabelDesugar) to one regel per row, fed
    //      through the SAME resolver -> both back-ends fire the rows. Mutually-exclusive bands keep the result
    //      deterministic regardless of row firing order. Exercises: one-conditie, multi-conditie AND + n.v.t. omission,
    //      and a §5.7 multi-value "of" cell -> a "tenminste één" OR-list. ----
    static final Fixture[] BESLISTABEL = {
        // multi-conditie AND with n.v.t. open bands (the §12 reisduur shape): prijs 150 lands in band 2 -> 20.
        single("AND band table (prijs 150 -> tarief 20)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
            Beslistabel Tarief
              geldig altijd
              |   | het basistarief van een Passagier moet gesteld worden op | indien zijn prijs groter is dan | indien zijn prijs kleiner of gelijk is aan |
              |---|---|---|---|
              | 1 | 10 | n.v.t. | 100 |
              | 2 | 20 | 100 | 200 |
              | 3 | 30 | 200 | n.v.t. |
            """,
            () -> passagier(p -> p.setPrijs(new BigDecimal(150))),
            Map.of(),
            Passagier::getBasistarief),

        // open lower band (n.v.t. on the '>' side): prijs 40 -> band 1 -> 10.
        single("AND band table, open first band (prijs 40 -> tarief 10)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
            Beslistabel Tarief
              geldig altijd
              |   | het basistarief van een Passagier moet gesteld worden op | indien zijn prijs groter is dan | indien zijn prijs kleiner of gelijk is aan |
              |---|---|---|---|
              | 1 | 10 | n.v.t. | 100 |
              | 2 | 20 | 100 | 200 |
              | 3 | 30 | 200 | n.v.t. |
            """,
            () -> passagier(p -> p.setPrijs(new BigDecimal(40))),
            Map.of(),
            Passagier::getBasistarief),

        // §5.7 multi-value "of" cell -> an OR-list; text equality on a Tekst attribute. roepnaam "Maria" -> 1.
        new Fixture("OR-list text equality (roepnaam Maria -> 1)",
            """
            Objecttype de Persoon (mv: Personen) (bezield)
                de roepnaam Tekst;
                de uitkomst Numeriek (geheel getal);
            Beslistabel Indeling
              geldig altijd
              |   | de uitkomst van een Persoon moet gesteld worden op | indien zijn roepnaam gelijk is aan |
              |---|---|---|
              | 1 | 1 | "Tom" of "Maria" |
              | 2 | 2 | "Wilma" |
            """,
            () -> { Persoon p = new Persoon(); p.setRoepnaam("Maria"); return List.of(p); },
            Map.of(),
            l -> ((Persoon) l.get(0)).getUitkomst()),

        // §8.1.2 leeg/gevuld CONDITIE header (Oracle B-1): a one-sided predicate in the header, cell "waar" = it
        // holds. prijs null -> "zijn prijs leeg is" true -> basistarief 7. The model lowers LEEG/GEVULD (Phase B).
        single("leeg/gevuld conditie header (prijs leeg -> tarief 7)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
            Beslistabel Tarief
              geldig altijd
              |   | het basistarief van een Passagier moet gesteld worden op | indien zijn prijs leeg is |
              |---|---|---|
              | 1 | 7 | waar |
            """,
            () -> passagier(p -> {}),   // prijs stays null
            Map.of(),
            Passagier::getBasistarief),

        // §13.2.14 enumeratiewaarde cells (single-quoted) — the spec's actual §12 woonregio modelling. The enum
        // compares by value equality on its label, lowered as string equality in both back-ends. roepnaam 'Maria' -> 1.
        new Fixture("enumeratiewaarde OR-list (roepnaam 'Maria' -> 1)",
            """
            Domein Naam is van het type Enumeratie
                'Tom'
                'Maria'
                'Wilma'
            Objecttype de Persoon (mv: Personen) (bezield)
                de roepnaam Naam;
                de uitkomst Numeriek (geheel getal);
            Beslistabel Indeling
              geldig altijd
              |   | de uitkomst van een Persoon moet gesteld worden op | indien zijn roepnaam gelijk is aan |
              |---|---|---|
              | 1 | 1 | 'Tom' of 'Maria' |
              | 2 | 2 | 'Wilma' |
            """,
            () -> { Persoon p = new Persoon(); p.setRoepnaam("Maria"); return List.of(p); },
            Map.of(),
            l -> ((Persoon) l.get(0)).getUitkomst()),

        // enumeratiewaarde on the VALUE side: a gelijkstelling SETTING an enum attribute. Both back-ends store the
        // String label (DRL setRoepnaam("Maria"); the model lowers tekstOfEnumLabel + a non-numeric-safe scaledBy).
        new Fixture("enumeratiewaarde conclusie (set roepnaam -> 'Maria')",
            """
            Domein Naam is van het type Enumeratie
                'Tom'
                'Maria'
            Objecttype de Persoon (mv: Personen) (bezield)
                de roepnaam Naam;
                de uitkomst Numeriek (geheel getal);
            Beslistabel Naamzetting
              geldig altijd
              |   | de roepnaam van een Persoon moet gesteld worden op | indien zijn uitkomst groter is dan |
              |---|---|---|
              | 1 | 'Maria' | 0 |
            """,
            () -> { Persoon p = new Persoon(); p.setUitkomst(new BigDecimal(5)); return List.of(p); },
            Map.of(),
            l -> ((Persoon) l.get(0)).getRoepnaam()),
    };

    // ---- Phase B fixtures: ALLE-compound (AND), kenmerkcheck, LEEG/GEVULD as in-pattern constraints ----
    static final Fixture[] PHASE_B = {
        single("ALLE compound (AND of two comparisons)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan alle volgende voorwaarden voldoet:
                      • zijn leeftijd is groter dan 5 jr
                      • zijn leeftijd is kleiner dan 18 jr.
            """,
            () -> passagier(p -> p.setLeeftijd(new BigDecimal(16))),
            Map.of(),
            Passagier::isMinderjarig),

        single("kenmerkcheck (hij kind is)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                is minderjarig kenmerk (bijvoeglijk);
                kind kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij kind is.
            """,
            () -> passagier(p -> p.setKind(true)),
            Map.of(),
            Passagier::isMinderjarig),

        single("LEEG predicate (leeftijd leeg is)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien zijn leeftijd leeg is.
            """,
            () -> passagier(p -> { /* leeftijd null -> leeg */ }),
            Map.of(),
            Passagier::isMinderjarig),
    };

    // ---- Phase B2: the §13.4.14.4 quantifier family (geen / ten minste / ten hoogste / precies) + the same-attribute
    //      collision guard. Each onderdeel is a fact-only leaf, so the whole compound lowers (no skip); the model
    //      folds the leaves by COUNTING and must reach the DRL oracle's boolean for every fact. ----
    static final String QUANT_OBJ = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
                kind kenmerk (bijvoeglijk);
            """;
    static final Fixture[] PHASE_B2 = {
        // GEEN = none of the bullets hold. {>18, <5} are both false in [5,18], so a mid-range passagier fires and an
        // out-of-range one does not. A dropped constraint would make GEEN trivially false -> caught by the >18 fact.
        new Fixture("GEEN (none of the bullets hold)",
            QUANT_OBJ + """
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan geen van de volgende voorwaarden voldoet:
                      • zijn leeftijd is groter dan 18 jr
                      • zijn leeftijd is kleiner dan 5 jr.
            """,
            () -> List.of(pass(10), pass(20)), Map.of(),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig())),

        // TEN MINSTE 1 (the OR) over a comparison and a kenmerk leaf — exercises mixed leaf kinds in the count.
        new Fixture("TEN MINSTE 1 (OR of comparison + kenmerk)",
            QUANT_OBJ + """
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan ten minste 1 van de volgende voorwaarden voldoet:
                      • zijn leeftijd is groter dan 18 jr
                      • hij kind is.
            """,
            () -> List.of(pass(20, false), pass(10, false), pass(10, true)), Map.of(),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig(), ((Passagier) l.get(2)).isMinderjarig())),

        // TEN HOOGSTE 1: at most one of {>5, <18} holds. A mid-range passagier (both hold -> count 2) does NOT fire;
        // an extreme one (one holds -> count 1) does. A dropped constraint would lower the count and wrongly fire mid-range.
        new Fixture("TEN HOOGSTE 1 (at most one bullet holds)",
            QUANT_OBJ + """
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan ten hoogste 1 van de volgende voorwaarden voldoet:
                      • zijn leeftijd is groter dan 5 jr
                      • zijn leeftijd is kleiner dan 18 jr.
            """,
            () -> List.of(pass(10), pass(20)), Map.of(),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig())),

        // PRECIES 1: exactly one of {>5, <18}. Mid-range (count 2) no; extreme (count 1) yes.
        new Fixture("PRECIES 1 (exactly one bullet holds)",
            QUANT_OBJ + """
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan precies 1 van de volgende voorwaarden voldoet:
                      • zijn leeftijd is groter dan 5 jr
                      • zijn leeftijd is kleiner dan 18 jr.
            """,
            () -> List.of(pass(10), pass(20)), Map.of(),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig())),

        // The same-ATTRIBUTE collision guard: an ALLE of two constraints on `leeftijd`. If the two .expr ids collided
        // (Drools shares by id), one constraint would be dropped — a leeftijd=3 passagier would wrongly fire (3<18).
        // With unique ids it must NOT fire (3 is not >5). leeftijd=10 fires (both hold).
        new Fixture("ALLE same-attribute (id-collision guard)",
            QUANT_OBJ + """
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan alle volgende voorwaarden voldoet:
                      • zijn leeftijd is groter dan 5 jr
                      • zijn leeftijd is kleiner dan 18 jr.
            """,
            () -> List.of(pass(3), pass(10)), Map.of(),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig())),
    };

    // ---- Phase B3: relational conditions — to-one navigated kenmerk, near-side rolcheck, far-side exists/not joins.
    //      These need a multi-fact session (the subject plus its related instances), which the generalized harness now supports. ----
    static final String FAR_SIDE_SRC = """
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
    static final Fixture[] PHASE_B3 = {
        // §8.1.8 to-one navigated kenmerk "zijn partner heeft een <k>": null-safe through the partner reference.
        // metHouder (partner holds the kenmerk) -> uitkomst 7; the holder itself and a partnerless persoon -> null.
        new Fixture("navigated kenmerk (zijn partner heeft een recht)",
            """
            Objecttype de Persoon (mv: Personen) (bezield)
                het recht op duurzaamheidskorting kenmerk (bezittelijk);
                de uitkomst Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel R
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 7 indien zijn partner een recht op duurzaamheidskorting heeft.
            """,
            () -> {
                Persoon houder = new Persoon(); houder.setRechtOpDuurzaamheidskorting(true);
                Persoon metHouder = new Persoon(); metHouder.setPartner(houder);
                Persoon zonder = new Persoon();
                return List.of(metHouder, houder, zonder);
            },
            Map.of(),
            l -> vals(((Persoon) l.get(0)).getUitkomst(), ((Persoon) l.get(1)).getUitkomst(), ((Persoon) l.get(2)).getUitkomst())),

        // §8.1.7 near-side rolcheck "hij een passagier is" -> "reis != null": fires only for a passagier with a reis.
        // (§8.3.1 enkele "indien" voorwaarde = VRAGENDE vorm, verb at the back; the stellende "hij is een passagier"
        // is the §8.3.2 samengestelde-bullet register. Both parse; the vragende form is canon for an enkele indien.)
        new Fixture("near-side rolcheck (hij een passagier is)",
            """
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
            """,
            () -> {
                Vlucht v = new Vlucht();
                Passagier p1 = new Passagier(); p1.setReis(v);   // fills the role
                Passagier p2 = new Passagier();                  // no reis
                return List.of(v, p1, p2);
            },
            Map.of(),
            l -> vals(((Passagier) l.get(1)).isMinderjarig(), ((Passagier) l.get(2)).isMinderjarig())),

        // §8.1.7 near-side NEGATION "hij geen passagier is" -> "reis == null": fires for a passagier WITHOUT a reis
        // (the inverse of the positive form). "geen" has no standalone token, so it rides the non-EEN path that
        // explicitly preserves the negation -> Rollidmaatschap(negatie=true). (§8.3.1 vragende vorm, verb at the back.)
        new Fixture("near-side negation rolcheck (hij geen passagier is)",
            """
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
                    Een passagier is minderjarig indien hij geen passagier is.
            """,
            () -> {
                Vlucht v = new Vlucht();
                Passagier p1 = new Passagier(); p1.setReis(v);   // fills the role -> "geen passagier is" FALSE -> not flagged
                Passagier p2 = new Passagier();                  // no reis -> "geen passagier is" TRUE -> minderjarig
                return List.of(v, p1, p2);
            },
            Map.of(),
            l -> vals(((Passagier) l.get(1)).isMinderjarig(), ((Passagier) l.get(2)).isMinderjarig())),

        // §8.1.7 far-side "hij heeft een passagier" -> exists Passagier(reis == $subject): fires for a non-empty Vlucht.
        new Fixture("far-side membership (heeft een passagier)",
            FAR_SIDE_SRC,
            () -> {
                Vlucht bezet = new Vlucht();
                Vlucht leeg = new Vlucht();
                Passagier p = new Passagier(); p.setReis(bezet);
                return List.of(bezet, leeg, p);
            },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isVol(), ((Vlucht) l.get(1)).isVol())),

        // §8.1.7 far-side negation "hij heeft geen passagier" -> not Passagier(reis == $subject): fires for an EMPTY Vlucht.
        new Fixture("far-side negation (heeft geen passagier)",
            FAR_SIDE_SRC.replace("hij heeft een passagier", "hij heeft geen passagier"),
            () -> {
                Vlucht leeg = new Vlucht();
                Vlucht bezet = new Vlucht();
                Passagier p = new Passagier(); p.setReis(bezet);
                return List.of(leeg, bezet, p);
            },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isVol(), ((Vlucht) l.get(1)).isVol())),

        // §13.4.14 far-side membership NESTED in an ALLE: the hoisted exists must ride up as a Gate extra and AND with
        // an in-pattern kenmerk leaf. Fires only for a Vlucht that BOTH is internationaal AND has a passagier.
        new Fixture("far-side exists carried up through ALLE + kenmerk leaf",
            """
            Objecttype de Passagier
            Objecttype de Vlucht
                is vol kenmerk (bijvoeglijk);
                is internationaal kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel r
                geldig altijd
                    Een Vlucht is vol indien hij aan alle volgende voorwaarden voldoet:
                      • hij heeft een passagier
                      • hij internationaal is.
            """,
            () -> {
                Vlucht v1 = new Vlucht(); v1.setInternationaal(true);   // internationaal + passagier -> fires
                Vlucht v2 = new Vlucht(); v2.setInternationaal(false);  // passagier but not internationaal -> no
                Vlucht v3 = new Vlucht(); v3.setInternationaal(true);   // internationaal but no passagier -> no
                Passagier p1 = new Passagier(); p1.setReis(v1);
                Passagier p2 = new Passagier(); p2.setReis(v2);
                return List.of(v1, v2, v3, p1, p2);
            },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isVol(), ((Vlucht) l.get(1)).isVol(), ((Vlucht) l.get(2)).isVol())),
    };

    // ---- Phase NEG: §8.1.8 KENMERKCHECK negation matrix. "geen"/"niet" has no standalone token (only GEEN_VAN_DE),
    //      so the negator lexes into the kaleNaam and the resolver de-negates it VERBATIM-FIRST (RuleChecker.bindKenmerk:
    //      a kenmerk literally named "geen …" still binds positively; only after the whole name fails is a leading
    //      niet/geen stripped to a Conditie.Kenmerk(negatie=true) == "== !negatie"). This is intentionally a RESOLVER
    //      construct, not a grammar one — the grammar cannot consult the symbol table to tell a "geen X" NAME from the
    //      negation. Both back-ends lower the negated kenmerk identically (DRL `== false`; model leafPredicate
    //      `wanted = !k.negatie()`). The rolcheck negation is covered by Phase B3; this phase pins the kenmerkcheck
    //      soorts (bijvoeglijk/bezittelijk/zelfstandig) + the stellende bullet register. ----
    private static final String NEG_OT = "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
            + "    de student kenmerk;\n"                       // no marker -> ZELFSTANDIG (the EEN-form "is een student")
            + "    is minderjarig kenmerk (bijvoeglijk);\n"
            + "    het duur kenmerk (bezittelijk);\n"
            + "    is kind kenmerk (bijvoeglijk);\n";           // the head the rule sets
    private static Object negKind2(List<Object> l) {
        return vals(((Passagier) l.get(0)).isKind(), ((Passagier) l.get(1)).isKind());
    }
    static final Fixture[] PHASE_NEG = {
        // §8.1.8.1 bijvoeglijk negation "hij niet minderjarig is" -> minderjarig == false (vragende, under 'indien').
        new Fixture("§8.1.8.1 bijvoeglijk negatie (hij niet minderjarig is -> ==false)",
            NEG_OT + """
            Regel r
                geldig altijd
                    Een passagier is kind indien hij niet minderjarig is.
            """,
            () -> List.of(passagier(p -> p.setMinderjarig(true)), passagier(p -> p.setMinderjarig(false))),
            Map.of(), ExecModelDifferentialTest::negKind2),   // minderjarig -> not kind; not-minderjarig -> kind: [false, true]
        // §8.1.8.3 zelfstandig EEN-form negation "hij geen student is" -> student == false.
        new Fixture("§8.1.8.3 zelfstandig negatie (hij geen student is -> ==false)",
            NEG_OT + """
            Regel r
                geldig altijd
                    Een passagier is kind indien hij geen student is.
            """,
            () -> List.of(passagier(p -> p.setStudent(true)), passagier(p -> p.setStudent(false))),
            Map.of(), ExecModelDifferentialTest::negKind2),   // [false, true]
        // §8.1.8.2 bezittelijk negation "hij geen duur heeft" -> duur == false.
        new Fixture("§8.1.8.2 bezittelijk negatie (hij geen duur heeft -> ==false)",
            NEG_OT + """
            Regel r
                geldig altijd
                    Een passagier is kind indien hij geen duur heeft.
            """,
            () -> List.of(passagier(p -> p.setDuur(true)), passagier(p -> p.setDuur(false))),
            Map.of(), ExecModelDifferentialTest::negKind2),   // [false, true]
        // §8.3.2 stellende (bullet) negation: BOTH members negated under ALLE -> kind iff (¬minderjarig ∧ ¬student).
        new Fixture("§8.3.2 stellende bullet negatie (is niet minderjarig + is geen student)",
            NEG_OT + """
            Regel r
                geldig altijd
                    Een passagier is kind indien hij aan alle volgende voorwaarden voldoet:
                    - hij is niet minderjarig
                    - hij is geen student.
            """,
            () -> List.of(passagier(p -> { p.setMinderjarig(false); p.setStudent(false); }),   // both false -> kind
                          passagier(p -> { p.setMinderjarig(true); p.setStudent(false); })),   // minderjarig -> not kind
            Map.of(), ExecModelDifferentialTest::negKind2),   // [true, false]
        // §8.1.8.3 Tabel 16 stellende zelfstandig negation "is niet een <k>" (the explicit NIET EEN form, which used
        // to break the parse — "een" could not follow the "niet" lexed into the kaleNaam). A stellende/bullet form, so
        // exercised under ALLE; lowers to student == false in BOTH back-ends. kind iff (¬student ∧ ¬minderjarig).
        new Fixture("§8.1.8.3 stellende bullet negatie (is niet een student)",
            NEG_OT + """
            Regel r
                geldig altijd
                    Een passagier is kind indien hij aan alle volgende voorwaarden voldoet:
                    - hij is niet een student
                    - hij is niet minderjarig.
            """,
            () -> List.of(passagier(p -> { p.setStudent(true);  p.setMinderjarig(false); }),   // student -> NOT kind
                          passagier(p -> { p.setStudent(false); p.setMinderjarig(false); })),  // neither -> kind
            Map.of(), ExecModelDifferentialTest::negKind2),   // [false, true]
        // §8.1.8 NAVIGATED negation "zijn reis niet internationaal is" -> reis != null && reis.internationaal == false
        // (the greedy possessive onderwerp absorbs the "niet" into the role; the resolver splits it back onto the
        // kenmerk). A NULL reis -> onwaar (the navigated predicate needs the relation, mirroring the affirmative;
        // standing "null nav read/check -> onwaar" decision). Both back-ends: DRL `reis != null && reis.x == false`,
        // model leafPredicate `nav != null && nav.x == !negatie`.
        new Fixture("§8.1.8 navigated negatie (zijn reis niet internationaal is)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                is kind kenmerk (bijvoeglijk);
            Objecttype de Vlucht (mv: Vluchten)
                is internationaal kenmerk (bijvoeglijk);
            Feittype reis van passagier
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel r
                geldig altijd
                    Een passagier is kind indien zijn reis niet internationaal is.
            """,
            () -> {
                Vlucht intl = new Vlucht(); intl.setInternationaal(true);
                Vlucht binnen = new Vlucht(); binnen.setInternationaal(false);
                Passagier p1 = new Passagier(); p1.setReis(intl);     // reis IS internationaal -> niet -> NOT kind
                Passagier p2 = new Passagier(); p2.setReis(binnen);   // reis NIET internationaal -> kind
                Passagier p3 = new Passagier();                        // geen reis -> reis==null -> onwaar -> NOT kind
                return List.of(intl, binnen, p1, p2, p3);
            },
            Map.of(),
            l -> vals(((Passagier) l.get(2)).isKind(), ((Passagier) l.get(3)).isKind(), ((Passagier) l.get(4)).isKind())),
    };
    /** §8.1.8 negation is a closed-form transform (== false); assert the ABSOLUTE firing (a shared ==true-instead-of-
     *  ==false bug would pass a pure differential) AND that both back-ends agree. */
    static final Object[] PHASE_NEG_EXPECTED = { vals(false, true), vals(false, true), vals(false, true), vals(true, false),
                                                 vals(false, true),          // §8.1.8.3 "is niet een student": ¬student ∧ ¬minderjarig
                                                 vals(false, true, false) };
    @Test void phase_NEG_negation_is_spec_correct() {
        for (int i = 0; i < PHASE_NEG.length; i++) {
            Fixture f = PHASE_NEG[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_NEG_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ---- Phase ELFPROEF: §8.1.3 elfproef + §8.1.4 numeriek-met-exact predicates. Both lower to a runtime helper
    //      (rsElfproef / rsNumeriekMetExact) byte-identical to the DRL inline function -> a closed-form transform, so
    //      assert the ABSOLUTE spec value AND back-end parity (a shared bug would pass a pure differential). ----
    static final String NP_PRED = "Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)\n"
            + "    het burgerservicenummer Numeriek (geheel getal);\n"
            + "    de bsn tekst Tekst;\n"
            + "    is geldig kenmerk (bijvoeglijk);\n";
    private static NatuurlijkPersoon npNum(String bsn) { NatuurlijkPersoon p = new NatuurlijkPersoon(); p.setBurgerservicenummer(new BigDecimal(bsn)); return p; }
    private static NatuurlijkPersoon npTekst(String bsn) { NatuurlijkPersoon p = new NatuurlijkPersoon(); p.setBsnTekst(bsn); return p; }
    private static Object geldig3(List<Object> l) {
        return vals(((NatuurlijkPersoon) l.get(0)).isGeldig(), ((NatuurlijkPersoon) l.get(1)).isGeldig(), ((NatuurlijkPersoon) l.get(2)).isGeldig());
    }
    static final Fixture[] PHASE_ELFPROEF = {
        // §8.1.3 elfproef over a Numeriek (geheel) bsn: 111222333 valid (weighted sum 66), 192837465 invalid (205,
        // rest 7), 12 has weighted sum 0 (the quotient must be a POSITIEF geheel getal -> invalid).
        new Fixture("§8.1.3 elfproef (Numeriek bsn): geldig iff valid",
            NP_PRED + "Regel r\n    geldig altijd\n        Een Natuurlijk persoon is geldig indien zijn burgerservicenummer aan de elfproef voldoet.\n",
            () -> List.of(npNum("111222333"), npNum("192837465"), npNum("12")),
            Map.of(), ExecModelDifferentialTest::geldig3),   // [true, false, false]
        // §8.1.4 numeriek met exact 9 cijfers (Tekst), vragende form under indien: 9 digits -> true; 8 digits and a
        // non-digit char -> false (length / digit check).
        new Fixture("§8.1.4 numeriek-met-exact 9 (Tekst, vragende)",
            NP_PRED + "Regel r\n    geldig altijd\n        Een Natuurlijk persoon is geldig indien zijn bsn tekst numeriek is met exact 9 cijfers.\n",
            () -> List.of(npTekst("123456789"), npTekst("12345678"), npTekst("1234x6789")),
            Map.of(), ExecModelDifferentialTest::geldig3),   // [true, false, false]
        // §8.1.4 leading zeros count as cijfers (Tekst preserves them): "012345678" is exactly 9 digits -> true.
        new Fixture("§8.1.4 numeriek-met-exact 9: leading-zero Tekst still 9 cijfers",
            NP_PRED + "Regel r\n    geldig altijd\n        Een Natuurlijk persoon is geldig indien zijn bsn tekst numeriek is met exact 9 cijfers.\n",
            () -> List.of(npTekst("012345678"), npTekst("0123456789"), npTekst("01234567")),
            Map.of(), ExecModelDifferentialTest::geldig3),   // [true, false, false]
        // §8.1.4 NEGATED (stellende bullet) "is niet numeriek met exact 9 cijfers": fires for the NON-9-digit value.
        new Fixture("§8.1.4 negatie numeriek-met-exact (is niet ... met exact 9)",
            NP_PRED + "Regel r\n    geldig altijd\n        Een Natuurlijk persoon is geldig indien hij aan alle volgende voorwaarden voldoet:\n"
            + "        - zijn bsn tekst is niet numeriek met exact 9 cijfers\n        - zijn bsn tekst is gevuld.\n",
            () -> List.of(npTekst("12345678"), npTekst("123456789"), npTekst("1234x6789")),
            Map.of(), ExecModelDifferentialTest::geldig3),   // [true, false, true]
        // §8.1.3 VRAGENDE negated elfproef "indien X niet aan de elfproef voldoet" — the leading "niet" is absorbed
        // into the operand and recovered by the resolver. geldig iff INVALID. Fires identically in both back-ends.
        new Fixture("§8.1.3 vragende negatie elfproef (indien X niet aan de elfproef voldoet)",
            NP_PRED + "Regel r\n    geldig altijd\n        Een Natuurlijk persoon is geldig indien zijn burgerservicenummer niet aan de elfproef voldoet.\n",
            () -> List.of(npNum("111222333"), npNum("192837465"), npNum("12")),
            Map.of(), ExecModelDifferentialTest::geldig3),   // [false, true, true]
        // §8.1.3 NAVIGATED negated elfproef "de luchthaven van zijn reis voldoet niet aan de elfproef". A NULL reis
        // makes the DRL `reis!.luchthaven` short-circuit the whole constraint to non-match (onwaar both polarities);
        // the exec must mirror that (rel == null -> false), NOT compute rsElfproef(null). geldig iff (reis present AND
        // invalid). [reis==null -> false, invalid -> true, valid -> false].
        new Fixture("§8.1.3 navigated negatie elfproef over a null reis (onwaar both polarities)",
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n    is student kenmerk (bijvoeglijk);\n    is kind kenmerk (bijvoeglijk);\n"
            + "Objecttype de Vlucht (mv: Vluchten)\n    het luchthaven Numeriek (geheel getal);\n"
            + "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n"
            + "Regel r\n    geldig altijd\n        Een passagier is student indien hij aan alle volgende voorwaarden voldoet:\n"
            + "        - de luchthaven van zijn reis voldoet niet aan de elfproef\n        - hij kind is.\n",
            () -> {
                Vlucht invalid = new Vlucht(); invalid.setLuchthaven(new BigDecimal("192837465"));
                Vlucht valid = new Vlucht();   valid.setLuchthaven(new BigDecimal("111222333"));
                Passagier pNull = passagier(p -> p.setKind(true));                                  // reis==null -> onwaar
                Passagier pInv  = passagier(p -> { p.setKind(true); p.setReis(invalid); });         // niet voldoet -> student
                Passagier pVal  = passagier(p -> { p.setKind(true); p.setReis(valid); });           // voldoet -> niet student
                return List.of(invalid, valid, pNull, pInv, pVal);
            },
            Map.of(), l -> vals(((Passagier) l.get(2)).isStudent(), ((Passagier) l.get(3)).isStudent(), ((Passagier) l.get(4)).isStudent())),
    };
    static final Object[] PHASE_ELFPROEF_EXPECTED = {
        vals(true, false, false), vals(true, false, false), vals(true, false, false), vals(true, false, true),
        vals(false, true, true), vals(false, true, false),
    };
    @Test void phase_ELFPROEF_predicates_are_spec_correct() {
        for (int i = 0; i < PHASE_ELFPROEF.length; i++) {
            Fixture f = PHASE_ELFPROEF[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_ELFPROEF_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ---- Phase B4: comparison correctness — mvel null semantics for != (a leeg operand DIFFERS from a number) and
    //      §5.1 Tabel 32 cross-unit restatement. These are the two divergences the adversarial review caught in the
    //      first Phase B drop; each AGREES with the DRL oracle only after the bothNonNull->vergelijk + restatement fix. ----
    static final Fixture[] PHASE_B4 = {
        // A bare ONGELIJK leaf with a NULL attribute: mvel reads `null != 0` as TRUE, so the rule fires for a
        // leeftijd-less passagier. (Before the fix the model read it as no-match -> divergence.)
        new Fixture("ONGELIJK vs literal with a null attribute (mvel null != x)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien zijn leeftijd ongelijk is aan 0.
            """,
            () -> List.of(passagier(p -> { /* leeftijd null */ }), passagier(p -> p.setLeeftijd(BigDecimal.ZERO))),
            Map.of(),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig())),

        // GEEN over ONGELIJK leaves with a null operand — the negated-quantifier polarity flip (the flagship finding).
        new Fixture("GEEN over ONGELIJK with a null operand (polarity flip)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan geen van de volgende voorwaarden voldoet:
                      • zijn leeftijd is ongelijk aan 18
                      • zijn leeftijd is groter dan 99.
            """,
            () -> List.of(passagier(p -> { /* null */ }), passagier(p -> p.setLeeftijd(new BigDecimal(18)))),
            Map.of(),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig())),

        // TEN MINSTE 2 of 3 ONGELIJK where the null operand is exactly the one mvel counts as satisfied (count tips 1<->2).
        new Fixture("TEN MINSTE 2 of 3 ONGELIJK, one operand null (count vs threshold)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan ten minste 2 van de volgende voorwaarden voldoet:
                      • zijn leeftijd is ongelijk aan 0
                      • zijn prijs is ongelijk aan 0
                      • zijn basistarief is ongelijk aan 0.
            """,
            () -> List.of(passagier(p -> { p.setPrijs(new BigDecimal(5)); p.setBasistarief(BigDecimal.ZERO); /* leeftijd null */ })),
            Map.of(),
            l -> ((Passagier) l.get(0)).isMinderjarig()),

        // PRECIES 1 ONGELIJK with a null operand (exactly-one fold over the != null-asymmetry).
        new Fixture("PRECIES 1 ONGELIJK with a null operand",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan precies 1 van de volgende voorwaarden voldoet:
                      • zijn leeftijd is ongelijk aan 0
                      • zijn prijs is ongelijk aan 0.
            """,
            () -> List.of(passagier(p -> p.setPrijs(BigDecimal.ZERO) /* leeftijd null */)),
            Map.of(),
            l -> ((Passagier) l.get(0)).isMinderjarig()),

        // Control: GEEN over ORDERING comparisons with a null operand — mvel orders null as no-match (false), which the
        // model already matched. Confirms the != fix did not perturb the ordering null semantics.
        new Fixture("GEEN over ordering comparisons with a null operand (control)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien hij aan geen van de volgende voorwaarden voldoet:
                      • zijn leeftijd is groter dan 18 jr
                      • zijn leeftijd is kleiner dan 5 jr.
            """,
            () -> List.of(passagier(p -> { /* null */ }), pass(10), pass(20)),
            Map.of(),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig(), ((Passagier) l.get(2)).isMinderjarig())),

        // §5.1 Tabel 32 cross-unit comparison vs a LITERAL: leeftijd in mnd vs "1 jr" must restate 1 jr -> 12 mnd first,
        // so 6 mnd < 12 mnd fires and 18 mnd < 12 mnd does not (a raw 6<1 / 18<1 would be wrong).
        new Fixture("cross-unit comparison vs literal (mnd attr vs jr literal)",
            """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid mnd;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien zijn leeftijd kleiner is dan 1 jr.
            """,
            () -> List.of(pass(6), pass(18)),
            Map.of(),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig())),

        // §5.1 Tabel 32 cross-unit comparison vs a GLOBAL: same restatement on the 2-var (fact vs global) expr.
        new Fixture("cross-unit comparison vs global (mnd attr vs jr global)",
            """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Parameter de drempel : Numeriek (geheel getal) met eenheid jr
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid mnd;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien zijn leeftijd kleiner is dan de drempel.
            """,
            () -> List.of(pass(6), pass(18)),
            Map.of("drempel", new BigDecimal(1)),
            l -> vals(((Passagier) l.get(0)).isMinderjarig(), ((Passagier) l.get(1)).isMinderjarig())),
    };

    // ---- Phase B5: the THEN-side value referencing a Parameter-GLOBAL (the last remaining Phase B construct).
    //      The global is bound on the consequence (DSL.on(sVar, g)); a value over >1 distinct global stays an honest
    //      skip; value-side unit restatement converts the value into the doel attribute's unit (§4). ----
    static final Fixture[] PHASE_B5 = {
        // THEN value IS a global: prijs := de toeslag.
        new Fixture("THEN value is a global (prijs := toeslag)",
            """
            Parameter de toeslag : Numeriek (geheel getal) met eenheid EUR
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal) met eenheid EUR;
            Regel R
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op de toeslag.
            """,
            () -> List.of(new Passagier()),
            Map.of("toeslag", new BigDecimal(7)),
            l -> ((Passagier) l.get(0)).getPrijs()),

        // THEN value = own-attr + global: prijs := basistarief plus de toeslag.
        new Fixture("THEN value own-attr plus global",
            """
            Parameter de toeslag : Numeriek (geheel getal) met eenheid EUR
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal) met eenheid EUR;
                het basistarief Numeriek (geheel getal) met eenheid EUR;
            Regel R
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op zijn basistarief plus de toeslag.
            """,
            () -> List.of(passagier(p -> p.setBasistarief(new BigDecimal(42)))),
            Map.of("toeslag", new BigDecimal(5)),
            l -> ((Passagier) l.get(0)).getPrijs()),

        // §4 value-side unit restatement: a jr-typed global assigned to a mnd attribute restates 2 jr -> 24 mnd.
        new Fixture("THEN value-side unit restatement (jr global -> mnd attr)",
            """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Parameter de looptijd : Numeriek (geheel getal) met eenheid jr
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid mnd;
            Regel R
                geldig altijd
                    De leeftijd van een Passagier moet gesteld worden op de looptijd.
            """,
            () -> List.of(new Passagier()),
            Map.of("looptijd", new BigDecimal(2)),
            l -> ((Passagier) l.get(0)).getLeeftijd()),

        // Honesty boundary: a value over >1 distinct global is NOT yet bound -> the model SKIPS the rule
        // §13.3.8 a THEN value over TWO Parameter-globals — Phase H closed the >1-global limit (consequenceOf binds
        // [subject, g0, g1]; the body rebuilds the name->value map). toeslag 5 + korting 3 = 8 (now AGREES, not a skip).
        new Fixture("THEN value over two globals (toeslag 5 + korting 3 = 8)",
            """
            Parameter de toeslag : Numeriek (geheel getal) met eenheid EUR
            Parameter de korting : Numeriek (geheel getal) met eenheid EUR
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal) met eenheid EUR;
            Regel R
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op de toeslag plus de korting.
            """,
            () -> List.of(new Passagier()),
            Map.of("toeslag", new BigDecimal(5), "korting", new BigDecimal(3)),
            l -> ((Passagier) l.get(0)).getPrijs()),   // 8
    };

    // ---- Phase C: aggregations over related elements — an accumulate CE binds the result. SOM (empty -> leegwaarde),
    //      TOTAAL (empty -> 0), AANTAL (count, with a §13.4.2 die-filter), and over-set MIN/MAX. ----
    static final String AGG_FEIT = """
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            """;
    static Passagier pas(Vlucht reis, int prijs) { Passagier p = new Passagier(); p.setReis(reis); p.setPrijs(new BigDecimal(prijs)); return p; }
    // A Passagier whose prijs is NOT seeded but DERIVED (from basistarief) by a rule in the same pass — so the max/min/som
    // accumulate over prijs sees each element modify-fired after it entered the window (the reverse-crash reproducer).
    static Passagier tarief(Vlucht reis, int basistarief) { Passagier p = new Passagier(); p.setReis(reis); p.setBasistarief(new BigDecimal(basistarief)); return p; }
    static final Fixture[] PHASE_C = {
        // §5.8.2 SOM "de som van de prijs van alle passagiers": non-empty sums; an EMPTY reis -> leegwaarde (null).
        new Fixture("SOM over the join (empty -> leegwaarde null)",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel totaal\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis.\n",
            () -> { Vlucht v = new Vlucht(); Vlucht leeg = new Vlucht(); return List.of(v, leeg, pas(v, 30), pas(v, 70)); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).getTotaalprijs(), ((Vlucht) l.get(1)).getTotaalprijs())),

        // §5.8.2 SOM with vs without an "of 0 als die er niet zijn" fallback — empty -> 0 vs leegwaarde (null).
        new Fixture("SOM met/zonder leegwaarde-fallback",
            """
            Objecttype de Vlucht
                de somMet Numeriek (geheel getal);
                de somZonder Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            """ + AGG_FEIT + """
            Regel met
                geldig altijd
                    De somMet van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis of 0 als die er niet zijn.
            Regel zonder
                geldig altijd
                    De somZonder van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis.
            """,
            () -> { Vlucht vol = new Vlucht(); Vlucht leeg = new Vlucht(); return List.of(vol, leeg, pas(vol, 30), pas(vol, 70)); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).getSomMet(), ((Vlucht) l.get(0)).getSomZonder(),
                      ((Vlucht) l.get(1)).getSomMet(), ((Vlucht) l.get(1)).getSomZonder())),

        // §4.20 TOTAAL "het totaal van …": an empty fold is unconditionally 0 (no leegwaarde branch).
        new Fixture("TOTAAL over the join (empty -> 0)",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel totaal\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als het totaal van de prijs van alle passagiers van de reis.\n",
            () -> { Vlucht vol = new Vlucht(); Vlucht leeg = new Vlucht(); return List.of(vol, leeg, pas(vol, 30), pas(vol, 70)); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).getTotaalprijs(), ((Vlucht) l.get(1)).getTotaalprijs())),

        // §5.8.1 AANTAL with a §13.4.2 die-filter "die een kind zijn": count only the kind passagiers (2 of 3).
        new Fixture("AANTAL with die-filter (count only kind)",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    is kind kenmerk (bijvoeglijk);\n" + AGG_FEIT
            + "Regel tel\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die een kind zijn.\n",
            () -> {
                Vlucht v = new Vlucht();
                Passagier a = new Passagier(); a.setReis(v); a.setKind(true);
                Passagier b = new Passagier(); b.setReis(v); b.setKind(true);
                Passagier c = new Passagier(); c.setReis(v); c.setKind(false);
                return List.of(v, a, b, c);
            },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),

        // §13.4.17 over-set MAXIMUM "de maximale waarde van de prijs van alle passagiers": 70; empty -> null.
        new Fixture("MAXIMUM over the join (empty -> null)",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de maximale waarde van de prijs van alle passagiers van de reis.\n",
            () -> { Vlucht v = new Vlucht(); Vlucht leeg = new Vlucht(); return List.of(v, leeg, pas(v, 30), pas(v, 70)); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).getTotaalprijs(), ((Vlucht) l.get(1)).getTotaalprijs())),

        // §13.4.17 over-set MINIMUM "de minimale waarde van …": 30.
        new Fixture("MINIMUM over the join",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de minimale waarde van de prijs van alle passagiers van de reis.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, pas(v, 30), pas(v, 70)); },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),

        // §13.4.17 aggregation over a source MUTATED in the SAME fireAllRules pass: the aggregated attribute (prijs) is
        // itself DERIVED by another rule ("De prijs ... als zijn basistarief"), so each Passagier joins the accumulate
        // with prijs==null, then is modify-fired once prijs is derived. Without a `!= null` source guard the null->value
        // transition triggers a phreak REVERSE of the null prior contribution, crashing with "Reversing a not existing
        // accumulated object" (LambdaAccumulator.tryReverse). The guard makes it an INSERT. Final max over {30,70} -> 70.
        new Fixture("MAXIMUM over a source derived in the same cycle (reverse-crash regression)",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel prijs\n    geldig altijd\n        De prijs van een Passagier moet berekend worden als zijn basistarief.\n"
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de maximale waarde van de prijs van alle passagiers van de reis.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, tarief(v, 30), tarief(v, 70)); },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 70

        // As above, MINIMUM over a same-cycle derived source: min over {30,70} -> 30.
        new Fixture("MINIMUM over a source derived in the same cycle (reverse-crash regression)",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel prijs\n    geldig altijd\n        De prijs van een Passagier moet berekend worden als zijn basistarief.\n"
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de minimale waarde van de prijs van alle passagiers van de reis.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, tarief(v, 30), tarief(v, 70)); },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 30

        // As above, SOM over a same-cycle derived source: sum over {30,70} -> 100. (BigDecimalSum already reverses
        // cleanly, but the fixture pins the same shape so a future non-reversing SOM swap stays parity-checked.)
        new Fixture("SOM over a source derived in the same cycle (reverse-crash regression)",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel prijs\n    geldig altijd\n        De prijs van een Passagier moet berekend worden als zijn basistarief.\n"
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als het totaal van de prijs van alle passagiers van de reis.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, tarief(v, 30), tarief(v, 70)); },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 100

        // §5.6 getalpredicaat subselectie "die kleiner zijn dan 50": the comparison filters the AGGREGATED attribute
        // (prijs) on the accumulate source pattern. SOM of prijs over {30,70,40} keeping prijs<50 -> 30+40=70.
        new Fixture("SOM with a comparison die-filter (prijs < 50)",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die kleiner zijn dan 50.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, pas(v, 30), pas(v, 70), pas(v, 40)); },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 70

        // §5.6 the same comparison filter on a MAXIMUM value-fold: max of {30,40} (70 excluded) -> 40.
        new Fixture("MAXIMUM with a comparison die-filter (prijs < 50)",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de maximale waarde van de prijs van alle passagiers van de reis die kleiner zijn dan 50.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, pas(v, 30), pas(v, 70), pas(v, 40)); },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 40

        // (adversary lock) two feittypen join Luchthaven to Reiziger; the count NAMES its element role ("aantal
        // VERTREK"), so verbindendeRolViaElementnaam must pick vertrekkendeReiziger — NOT the by-pair ambiguity.
        new Fixture("AANTAL element-named role disambiguates >1 feittype",
            """
            Objecttype de Reiziger
                het aantal vertrekluchthavens Numeriek (geheel getal);
            Objecttype de Luchthaven
            Feittype Reiziger en vertrekluchthaven
                de vertrekkende reiziger Reiziger
                de vertrek Luchthaven
            Eén vertrekkende reiziger betreft meerdere vertrekken
            Feittype Reiziger en aankomstluchthaven
                de aankomende reiziger Reiziger
                de aankomst Luchthaven
            Eén aankomende reiziger betreft meerdere aankomsten
            Regel tel
                geldig altijd
                    Het aantal vertrekluchthavens van een Reiziger moet gesteld worden op het aantal vertrek van de reiziger.
            """,
            () -> {
                io.github.yhavinga.regelspraak.facts.Reiziger r = new io.github.yhavinga.regelspraak.facts.Reiziger();
                io.github.yhavinga.regelspraak.facts.Luchthaven v1 = new io.github.yhavinga.regelspraak.facts.Luchthaven(); v1.setVertrekkendeReiziger(r);
                io.github.yhavinga.regelspraak.facts.Luchthaven v2 = new io.github.yhavinga.regelspraak.facts.Luchthaven(); v2.setVertrekkendeReiziger(r);
                io.github.yhavinga.regelspraak.facts.Luchthaven a = new io.github.yhavinga.regelspraak.facts.Luchthaven(); a.setAankomendeReiziger(r);  // an arrival, not counted
                return List.of(r, v1, v2, a);
            },
            Map.of(),
            l -> ((io.github.yhavinga.regelspraak.facts.Reiziger) l.get(0)).getAantalVertrekluchthavens()),   // expect 2

        // (adversary lock) SOM with a die-filter kenmerk AND a literal fallback: sum only the kind passagiers; an
        // empty (no-kind) reis -> the literal 0 fallback.
        new Fixture("SOM with die-filter AND literal fallback",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    is kind kenmerk (bijvoeglijk);\n" + AGG_FEIT
            + "Regel r\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die een kind zijn of 0 als die er niet zijn.\n",
            () -> {
                Vlucht vol = new Vlucht(); Vlucht leeg = new Vlucht();
                Passagier a = pas(vol, 30); a.setKind(true);
                Passagier b = pas(vol, 70); b.setKind(true);
                Passagier c = pas(vol, 999); c.setKind(false);   // not a kind -> excluded
                Passagier d = pas(leeg, 50); d.setKind(false);   // leeg has only non-kind -> empty SOM -> fallback 0
                return List.of(vol, leeg, a, b, c, d);
            },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).getTotaalprijs(), ((Vlucht) l.get(1)).getTotaalprijs())),   // 100, 0

        // §5.8.2 SOM with a NON-LITERAL empty-set fallback ("of zijn vertrektijd als die er niet zijn" — the reis's
        // OWN attribute, not a numeric literal). vol -> sum 100; empty reis -> fallback = its vertrektijd 42.
        new Fixture("SOM with a non-literal (own-attr) fallback",
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\n    de vertrektijd Numeriek (geheel getal);\n"
            + "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis of zijn vertrektijd als die er niet zijn.\n",
            () -> { Vlucht vol = new Vlucht(); Vlucht leeg = new Vlucht(); leeg.setVertrektijd(new BigDecimal(42));
                    return List.of(vol, leeg, pas(vol, 30), pas(vol, 70)); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).getTotaalprijs(), ((Vlucht) l.get(1)).getTotaalprijs())),   // 100, 42
    };

    // ---- §5.6 the spec's worked-example comparison subselectie RHS "die kleiner zijn dan de volwassenleeftijd" — a
    //      NUMERIC Parameter-GLOBAL RHS (here "de grens"). DRL reads the global as a free name in the accumulate source
    //      ("prijs < grens"); the exec binds it on the source pattern (a 2-var expr). Was an honest marker (the literal-
    //      only restriction); now FIRES in both. SOM/MAX of prijs over {30,70,40} keeping prijs < grens(50). ----
    static final String SUB_PARAM_HDR =
            "Parameter de grens : Numeriek (geheel getal)\nObjecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\n"
            + "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT;
    static final Fixture[] PHASE_C_PARAM_RHS = {
        new Fixture("SOM with a PARAMETER-RHS comparison die-filter (prijs < de grens)",
            SUB_PARAM_HDR + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die kleiner zijn dan de grens.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, pas(v, 30), pas(v, 70), pas(v, 40)); },
            Map.of("grens", new BigDecimal(50)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 30 + 40 = 70
        new Fixture("MAXIMUM with a PARAMETER-RHS comparison die-filter (prijs < de grens)",
            SUB_PARAM_HDR + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de maximale waarde van de prijs van alle passagiers van de reis die kleiner zijn dan de grens.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, pas(v, 30), pas(v, 70), pas(v, 40)); },
            Map.of("grens", new BigDecimal(50)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // max{30,40} = 40
    };
    static final Object[] PHASE_C_PARAM_RHS_EXPECTED = { new BigDecimal(70), new BigDecimal(40) };

    /** §4.10 Tabel 21 lists the som-van operand strictly as Numeriek (decided like MIN/MAX Tabel 22/23; see
     *  grammar-divergences). The former review-FSD-1 fixture here summed a PERCENTAGE attribute end-to-end —
     *  a shape the resolver now rejects, so it converts to a resolver-ERROR pin. (The exec filterSource's
     *  isGetalachtig guard the fixture once exercised stays as harmless robustness; every aggregation operand
     *  reaching it is now Numeriek by construction.) */
    @Test void som_van_percentage_operand_is_een_resolver_fout() {
        RegelSpraakModel m = Resolver.resolve(
            "Parameter de grens : Percentage (geheel getal)\nObjecttype de Vlucht\n    de totaalprijs Percentage (geheel getal);\nObjecttype de Passagier\n    de prijs Percentage (geheel getal);\n" + AGG_FEIT
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die kleiner zijn dan de grens.\n");
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("som van vereist numerieke")),
                () -> "een Percentage som-operand moet een Tabel-21-fout geven, kreeg: " + m.errors());
    }

    // ---- §13.4.13.4 the COMPOUND subselectie "die aan alle volgende voorwaarden voldoen: - … - …" — subject-FUL
    //      bullets (the spec EBNF form) resolved against the ELEMENT, an ALLE compound AND-ed on the accumulate
    //      source. DRL lowers it to `(leeftijd < 18) && (minderjarig == true)` (conditieInline already handles ALLE);
    //      the exec filterSource ANDs each bullet as a successive .expr. SOM of prijs over passengers matching BOTH. ----
    static final String COMPOUND_HDR =
            "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\n"
            + "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    de leeftijd Numeriek (geheel getal);\n    is minderjarig kenmerk (bijvoeglijk);\n" + AGG_FEIT;
    static Passagier paxLM(Vlucht reis, int prijs, int leeftijd, boolean minderjarig) {
        Passagier p = pas(reis, prijs); p.setLeeftijd(new BigDecimal(leeftijd)); p.setMinderjarig(minderjarig); return p;
    }
    static final Fixture[] PHASE_C_COMPOUND = {
        new Fixture("SOM with a 2-bullet ALLE compound subselectie (leeftijd<18 && minderjarig)",
            COMPOUND_HDR + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan alle volgende voorwaarden voldoen:\n        - zijn leeftijd is kleiner dan 18\n        - hij is minderjarig.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v,
                        paxLM(v, 30, 10, true),    // <18 AND minderjarig -> kept
                        paxLM(v, 70, 20, true),    // leeftijd 20 -> excluded
                        paxLM(v, 40, 15, false),   // not minderjarig -> excluded
                        paxLM(v, 25, 12, true)); },// kept
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 30 + 25 = 55
        // an ALLE compound with a PARAMETER-global bullet FIRES in both (the ALLE arm recurses through filterSource,
        // which binds the global on the source pattern) — unlike a COUNTING quantifier + global (a marker in both).
        new Fixture("SOM with an ALLE compound + a PARAMETER-global bullet (leeftijd<de grens && minderjarig)",
            "Parameter de grens : Numeriek (geheel getal)\n" + COMPOUND_HDR + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan alle volgende voorwaarden voldoen:\n        - zijn leeftijd is kleiner dan de grens\n        - hij is minderjarig.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v,
                        paxLM(v, 30, 10, true), paxLM(v, 70, 20, true), paxLM(v, 40, 15, false), paxLM(v, 25, 12, true)); },
            Map.of("grens", new BigDecimal(18)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // leeftijd < grens(18) AND minderjarig -> 30 + 25 = 55
    };
    static final Object[] PHASE_C_COMPOUND_EXPECTED = { new BigDecimal(55), new BigDecimal(55) };

    // ---- §13.4.13.4 the INLINE single-comparison predicate tail — "… die aan de volgende voorwaarde
    //      voldoet: <één niet-gebullete vergelijking>." (the §5.6 worked example "zijn leeftijd is kleiner
    //      dan de volwassenleeftijd"). Resolves as a 1-onderdeel DE-compound on the accumulate source; the
    //      EBNF names the verb-trailing register but the worked example writes verb-leading, so both fire. ----
    static Supplier<List<Object>> inlineFacts() {
        return () -> { Vlucht v = new Vlucht(); return List.of(v,
            paxLM(v, 30, 10, true),     // leeftijd<18, minderjarig
            paxLM(v, 70, 20, true),     // leeftijd 20, minderjarig
            paxLM(v, 40, 15, false)); };// leeftijd<18, niet minderjarig
    }
    static final Fixture[] PHASE_INLINE = {
        new Fixture("AANTAL, inline tail verb-leading (§5.6 shape: zijn leeftijd is kleiner dan 18)",
            COMPOUND_HDR + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die aan de volgende voorwaarde voldoet:\n        zijn leeftijd is kleiner dan 18.\n",
            inlineFacts(), Map.of(), l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // leeftijden 10, 15 -> 2
        new Fixture("SOM, inline tail verb-trailing (EBNF register: zijn leeftijd kleiner is dan 18)",
            COMPOUND_HDR + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan de volgende voorwaarde voldoet:\n        zijn leeftijd kleiner is dan 18.\n",
            inlineFacts(), Map.of(), l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 30 + 40 = 70
        new Fixture("SOM, inline kenmerk-tail (hij is minderjarig)",
            COMPOUND_HDR + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan de volgende voorwaarde voldoet:\n        hij is minderjarig.\n",
            inlineFacts(), Map.of(), l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 30 + 70 = 100
    };
    static final Object[] PHASE_INLINE_EXPECTED = { new BigDecimal(2), new BigDecimal(70), new BigDecimal(100) };

    // ---- §8.1.9 / §13.4.15.66 regelpredicaat "regelversie <X> (altijd) is inconsistent": an exists-join on
    //      the §9.5 Inconsistentie flag fact of the named consistentieregel. The predicate has no subject of
    //      its own, so when the flag exists EVERY instance of the conditioned rule's subject fires. ----
    static final String REGELVERSIE_HDR =
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    is duur kenmerk (bijvoeglijk);\n"
            + "Regel controleer de prijs\n    geldig altijd\n        De prijs van een Passagier moet kleiner zijn dan 100.\n";
    static final Fixture[] PHASE_REGELVERSIE = {
        new Fixture("is inconsistent: een schending -> de conditie geldt (ook voor de niet-schender)",
            REGELVERSIE_HDR + "Regel duur\n    geldig altijd\n        Een Passagier is duur indien regelversie controleer de prijs (altijd) is inconsistent.\n",
            () -> List.of(passagier(p -> p.setPrijs(new BigDecimal(150))),   // schendt (>=100) -> Inconsistentie
                          passagier(p -> p.setPrijs(new BigDecimal(50)))),
            Map.of(), l -> ((Passagier) l.get(1)).isDuur()),   // true — the flag is global, not per-subject
        new Fixture("is inconsistent: geen schending -> de conditie geldt niet",
            REGELVERSIE_HDR + "Regel duur\n    geldig altijd\n        Een Passagier is duur indien regelversie controleer de prijs (altijd) is inconsistent.\n",
            () -> List.of(passagier(p -> p.setPrijs(new BigDecimal(50)))),
            Map.of(), l -> ((Passagier) l.get(0)).isDuur()),   // false/null — no Inconsistentie inserted
    };
    static final Object[] PHASE_REGELVERSIE_EXPECTED = { Boolean.TRUE, Boolean.FALSE };

    // ---- §13.4.13.4 a COUNTING-quantifier compound subselectie ("die aan ten minste 1 / geen / precies 1 / ten
    //      hoogste 1 van de volgende voorwaarden voldoen: …") whose bullet reads a SINGLE Parameter-global ("zijn
    //      leeftijd is kleiner dan de grens"). The exec binds that one global on the accumulate source (a 2-var
    //      count-fold over element + global); DRL reads it as a free name in the bullet OR/count. Now FIRES in both
    //      (only a >1-distinct-global counting compound stays a symmetric marker, asserted below). The four fixtures
    //      sweep the whole §13.4.14.4 quantifier family. SOM of prijs over passengers whose bullet-count meets it. ----
    static Supplier<List<Object>> countFacts() {
        return () -> { Vlucht v = new Vlucht(); return List.of(v,
            paxLM(v, 30, 10, true),     // leeftijd<grens(18)=T, minderjarig=T -> count 2
            paxLM(v, 70, 20, true),     // F, T -> count 1
            paxLM(v, 40, 15, false),    // T, F -> count 1
            paxLM(v, 25, 30, false)); };// F, F -> count 0
    }
    static String countSrc(String quantor) {
        return "Parameter de grens : Numeriek (geheel getal)\n" + COMPOUND_HDR
            + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan "
            + quantor + " van de volgende voorwaarden voldoen:\n        - zijn leeftijd is kleiner dan de grens\n        - hij is minderjarig.\n";
    }
    static final Fixture[] PHASE_C_COUNT_GLOBAL = {
        new Fixture("SOM, ten-minste-1 compound + single global (count>=1)",
            countSrc("ten minste 1"), countFacts(), Map.of("grens", new BigDecimal(18)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // count>=1 -> 30+70+40 = 140
        new Fixture("SOM, geen-van-de compound + single global (count==0)",
            countSrc("geen"), countFacts(), Map.of("grens", new BigDecimal(18)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // count==0 -> 25
        new Fixture("SOM, precies-1 compound + single global (count==1)",
            countSrc("precies 1"), countFacts(), Map.of("grens", new BigDecimal(18)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // count==1 -> 70+40 = 110
        new Fixture("SOM, ten-hoogste-1 compound + single global (count<=1)",
            countSrc("ten hoogste 1"), countFacts(), Map.of("grens", new BigDecimal(18)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // count<=1 -> 70+40+25 = 135
    };
    static final Object[] PHASE_C_COUNT_GLOBAL_EXPECTED = {
        new BigDecimal(140), new BigDecimal(25), new BigDecimal(110), new BigDecimal(135) };

    // §13.4.13.4 a COUNTING compound reading TWO DISTINCT globals — DRL lowers it (free-name globals in the bullet
    //   OR); the exec count-fold binds at most one source-global and SKIPS honestly (the resolver is the naming authority: an explicit divergence).
    static final String COUNT_TWO_GLOBALS_SRC =
        "Parameter de ondergrens : Numeriek (geheel getal)\nParameter de bovengrens : Numeriek (geheel getal)\n" + COMPOUND_HDR
        + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:\n        - zijn leeftijd is kleiner dan de ondergrens\n        - zijn prijs is groter dan de bovengrens.\n";
    // §13.4.13.4 a NESTED PURE-LEAF compound bullet under a counting quantifier — now FIRES in BOTH back-ends (DRL
    //   conditieInline's nested &&; exec leafCompound folds it into ONE Predicate1). Locked by PHASE_C_COUNT_NESTED.
    static final String COUNT_NESTED_BULLET_SRC =
        "Parameter de grens : Numeriek (geheel getal)\n" + COMPOUND_HDR
        + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:\n        - zijn leeftijd is kleiner dan de grens\n        - hij voldoet aan alle volgende voorwaarden:\n            - hij is minderjarig\n            - zijn prijs is groter dan 50.\n";
    // §13.4.13.4 a NESTED compound bullet that itself READS A GLOBAL ("zijn leeftijd is kleiner dan de grens" inside the
    //   nested ALLE) — DRL lowers the nested &&/|| with the global as a free name; the exec leafCompound folds into ONE
    //   Predicate1, cannot bind the global, and SKIPS honestly (the resolver is the naming authority: an explicit divergence).
    static final String COUNT_NESTED_GLOBAL_SRC =
        "Parameter de grens : Numeriek (geheel getal)\n" + COMPOUND_HDR
        + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:\n        - zijn prijs is groter dan 50\n        - hij voldoet aan alle volgende voorwaarden:\n            - hij is minderjarig\n            - zijn leeftijd is kleiner dan de grens.\n";
    // §13.4.14.6 TWO consecutive NESTED-compound bullets (col 8) under a counting quantifier — the indentation-blind
    //   grammar absorbs the second into the first's ALLE (col 12), but the COLUMNS re-nest cleanly, so the resolver
    //   rebuilds the intended SIBLINGS (depth-aware re-nesting) and BOTH back-ends FIRE the spec-correct OR/count:
    //   "ten minste 1 van {(minderjarig && prijs>50), (prijs<45)}". Closed-form SOM -> ABSOLUTE spec value.
    static final String COUNT_NESTED_TWICE_SRC =
        COMPOUND_HDR
        + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:\n        - hij voldoet aan alle volgende voorwaarden:\n            - hij is minderjarig\n            - zijn prijs is groter dan 50\n        - hij voldoet aan alle volgende voorwaarden:\n            - zijn prijs is kleiner dan 45.\n";
    // §13.4.14.6 a NESTED compound bullet (col 8) FOLLOWED BY a dedented sibling LEAF ('zijn leeftijd is kleiner dan 30',
    //   col 8) — the grammar absorbs the leaf into the nested ALLE (col 12), but the columns disambiguate, so re-nesting
    //   lifts the leaf back to a SIBLING and BOTH back-ends FIRE the spec OR: "ten minste 1 van {(minderjarig &&
    //   prijs>50), leeftijd<30}". Closed-form SOM -> ABSOLUTE spec value.
    static final String COUNT_NESTED_THEN_LEAF_SRC =
        COMPOUND_HDR
        + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:\n        - hij voldoet aan alle volgende voorwaarden:\n            - hij is minderjarig\n            - zijn prijs is groter dan 50\n        - zijn leeftijd is kleiner dan 30.\n";
    // §13.4.13.4 a COUNTING-quantifier compound subselectie with a NESTED PURE-LEAF compound bullet, now FIRING in BOTH
    //   back-ends (DRL conditieInline's nested &&; exec leafCompound folds it into ONE Predicate1) at parity. The
    //   top-level fold binds the SINGLE source-global (grens); the nested compound is global-free. Closed-form SOM ->
    //   ABSOLUTE spec value. "ten minste 1 van {leeftijd<grens, (minderjarig && prijs>50)}".
    static final Fixture[] PHASE_C_COUNT_NESTED = {
        new Fixture("SOM, ten-minste-1 with a nested ALLE compound bullet, grens=18",
            COUNT_NESTED_BULLET_SRC,
            () -> { Vlucht v = new Vlucht(); return List.of(v,
                paxLM(v, 30, 10, false),   // leeftijd 10 < grens 18 -> match -> +30
                paxLM(v, 60, 40, true),    // nested: minderjarig && 60>50 -> match -> +60
                paxLM(v, 40, 40, true),    // nested: minderjarig but 40<=50, and 40>=18 -> no match
                paxLM(v, 60, 40, false)); }, // nested: not minderjarig, and 40>=18 -> no match
            Map.of("grens", new BigDecimal(18)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 30 + 60 = 90
        new Fixture("SOM, ten-minste-1 nested compound, grens=5 (only the nested-compound match)",
            COUNT_NESTED_BULLET_SRC,
            () -> { Vlucht v = new Vlucht(); return List.of(v,
                paxLM(v, 30, 10, false),   // 10<5 false, not minderjarig -> no match
                paxLM(v, 60, 40, true),    // nested: minderjarig && 60>50 -> match -> +60
                paxLM(v, 40, 40, true), paxLM(v, 60, 40, false)); },
            Map.of("grens", new BigDecimal(5)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 60
    };
    static final Object[] PHASE_C_COUNT_NESTED_EXPECTED = { new BigDecimal(90), new BigDecimal(60) };
    // §13.4.14.6 TWO sibling nested-compound bullets re-nested by column -> "ten minste 1 van {(minderjarig && prijs>50),
    //   (prijs<45)}". paxLM(v, prijs, leeftijd, minderjarig). Fires the spec-correct OR (was a symmetric marker).
    static final Fixture[] PHASE_C_COUNT_NESTED_TWICE = {
        new Fixture("SOM, ten-minste-1 of two sibling nested compounds (both members contribute)",
            COUNT_NESTED_TWICE_SRC,
            () -> { Vlucht v = new Vlucht(); return List.of(v,
                paxLM(v, 60, 30, true),    // (minderjarig && 60>50) -> match -> +60
                paxLM(v, 40, 30, false),   // (prijs 40 < 45)        -> match -> +40
                paxLM(v, 70, 30, false),   // 70>=45, not minderjarig -> no match
                paxLM(v, 45, 30, true)); }, // (minderjarig but 45<=50) & 45>=45 -> no match
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 60 + 40 = 100
        new Fixture("SOM, ten-minste-1 of two sibling nested compounds (only the first member)",
            COUNT_NESTED_TWICE_SRC,
            () -> { Vlucht v = new Vlucht(); return List.of(v,
                paxLM(v, 60, 30, true),    // (minderjarig && 60>50) -> match -> +60
                paxLM(v, 50, 30, false),   // 50>=45, not minderjarig -> no match
                paxLM(v, 100, 30, false)); }, // 100>=45, not minderjarig -> no match
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 60
    };
    static final Object[] PHASE_C_COUNT_NESTED_TWICE_EXPECTED = { new BigDecimal(100), new BigDecimal(60) };
    // §13.4.14.6 a nested compound bullet + a dedented sibling LEAF re-nested by column -> "ten minste 1 van
    //   {(minderjarig && prijs>50), leeftijd<30}". Fires the spec-correct OR (was a symmetric marker).
    static final Fixture[] PHASE_C_COUNT_NESTED_THEN_LEAF = {
        new Fixture("SOM, ten-minste-1 of {nested compound, leaf leeftijd<30} (both contribute)",
            COUNT_NESTED_THEN_LEAF_SRC,
            () -> { Vlucht v = new Vlucht(); return List.of(v,
                paxLM(v, 60, 40, true),    // (minderjarig && 60>50) -> match -> +60
                paxLM(v, 20, 25, false),   // (leeftijd 25 < 30)     -> match -> +20
                paxLM(v, 80, 50, false)); }, // 80>=.., 50>=30, not minderjarig -> no match
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 60 + 20 = 80
        new Fixture("SOM, ten-minste-1 of {nested compound, leaf leeftijd<30} (only the leaf)",
            COUNT_NESTED_THEN_LEAF_SRC,
            () -> { Vlucht v = new Vlucht(); return List.of(v,
                paxLM(v, 30, 20, false),   // (leeftijd 20 < 30) -> match -> +30
                paxLM(v, 70, 40, false)); }, // 40>=30, not minderjarig -> no match
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 30
    };
    static final Object[] PHASE_C_COUNT_NESTED_THEN_LEAF_EXPECTED = { new BigDecimal(80), new BigDecimal(30) };
    // §13.4.13.4 a CROSS-UNIT global bullet (leeftijd jr vs grens mnd) — DRL emits an unguarded (leeftijd).multiply(12)
    //   in the accumulate-source constraint that NPE-aborts on a leeg operand; the exec scale() is null-safe -> a masked
    //   DRL-crashes/exec-fires divergence -> the resolver markers the convertible-but-different-unit global (both decline).
    static final String COUNT_CROSS_UNIT_SRC =
        "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\nParameter de grens : Numeriek (geheel getal) met eenheid mnd\n"
        + "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\n"
        + "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    de leeftijd Numeriek (geheel getal) met eenheid jr;\n    is minderjarig kenmerk (bijvoeglijk);\n" + AGG_FEIT
        + "Regel R\n    geldig altijd\n        De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:\n        - zijn leeftijd is kleiner dan de grens\n        - hij is minderjarig.\n";

    // ---- §5.1 CROSS-UNIT comparison restatement in a CONSTRAINT — now null-safe (was an unguarded (operand).multiply
    //      that NPE-aborted the session on a leeg operand; the value-side null-guard ternary mis-parses in a pattern
    //      constraint). DRL restates via the rsHerschaal helper (the mirror of the exec scale: null -> null), so a
    //      cross-unit comparison with a leeg operand reads as no-match in BOTH back-ends. Covers the standalone
    //      comparison AND the cross-unit global counting subselectie (which therefore no longer markers). ----
    static final String CROSSUNIT_VERG_SRC =
        "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\nParameter de grens : Numeriek (geheel getal) met eenheid mnd\n"
        + "Objecttype de Passagier\n    de leeftijd Numeriek (geheel getal) met eenheid jr;\n    is duur kenmerk (bijvoeglijk);\n"
        + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn leeftijd groter is dan de grens.\n";
    static final Fixture[] PHASE_CROSSUNIT = {
        // standalone cross-unit comparison incl. a LEEG leeftijd (the former NPE): 2jr=24mnd>18 -> duur; 1jr=12<18 -> not;
        // leeg -> not (no crash). Both back-ends restate null-safely and agree.
        new Fixture("cross-unit comparison in voorwaarde, incl. leeg operand (no NPE)",
            CROSSUNIT_VERG_SRC,
            () -> { Passagier p1 = new Passagier(); p1.setLeeftijd(new BigDecimal(2));
                    Passagier p2 = new Passagier(); p2.setLeeftijd(new BigDecimal(1));
                    Passagier p3 = new Passagier();   // leeg leeftijd
                    return List.of(p1, p2, p3); },
            Map.of("grens", new BigDecimal(18)),
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
        // the cross-unit global ten-minste-1 subselectie now FIRES (was a marker): SOM over passengers whose leeftijd
        // (jr) < grens (18 mnd, i.e. < 1.5 jr) OR minderjarig. A leeg-leeftijd minderjarig pax is kept via the second
        // bullet (the leeftijd bullet restates to leeg -> no-match, no crash). 30 + 70 + 40 + 10 = 150.
        new Fixture("cross-unit global ten-minste-1 subselectie (SOM, incl. leeg leeftijd)",
            COUNT_CROSS_UNIT_SRC,
            () -> { Vlucht v = new Vlucht();
                    Passagier leeg = new Passagier(); leeg.setReis(v); leeg.setPrijs(new BigDecimal(10)); leeg.setMinderjarig(true);
                    return List.of(v, paxLM(v, 30, 1, true), paxLM(v, 70, 2, true), paxLM(v, 40, 1, false), paxLM(v, 25, 5, false), leeg); },
            Map.of("grens", new BigDecimal(18)),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),
    };
    static final Object[] PHASE_CROSSUNIT_EXPECTED = { vals(true, false, false), new BigDecimal(150) };

    // ---- §8.1.1 a comparison whose operand is a NUMERIC EXPRESSION (arithmetic Binair / Functie / begrenzing /
    //      afronding) vs an ATTRIBUTE (or another expression) — NOT a literal. DRL lowered both constraint operands via
    //      binairTerm; the exec only handled expr-vs-LITERAL, so "de a plus de b gelijk is aan de c" FIRED in DRL but the
    //      exec SKIPPED it (a masked DRL-fires/exec-skips, hidden by overgeslagen). The exec now lowers BOTH sides via
    //      scalarWaarde (already at parity with binairTerm on the value side). A cross-unit arithmetic comparison fires
    //      too (DRL omrekenConditie restates via the null-safe rsHerschaal; the exec scalarWaarde restates internally). ----
    static final String EXPR_CMP_HDR =
            "Objecttype de Passagier\n    het basistarief Numeriek (geheel getal);\n    de bodem Numeriek (geheel getal);\n    de prijs Numeriek (geheel getal);\n    is duur kenmerk (bijvoeglijk);\n";
    static Passagier paxBBP(Integer bt, Integer bo, Integer pr) {
        Passagier p = new Passagier();
        if (bt != null) p.setBasistarief(new BigDecimal(bt));
        if (bo != null) p.setBodem(new BigDecimal(bo));
        if (pr != null) p.setPrijs(new BigDecimal(pr));
        return p;
    }
    static final Fixture[] PHASE_EXPR_CMP = {
        // arithmetic expr == attr, incl. a LEEG operand (leeg -> 0 per §6.2): 10+14==24 -> WAAR; 10+10==24 -> ONWAAR;
        // leeg+14==14 -> WAAR. The whole class FIRED in DRL but the exec USED to skip.
        new Fixture("arith expr == attr (basistarief + bodem == prijs), incl. leeg",
            EXPR_CMP_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn basistarief plus zijn bodem gelijk is aan zijn prijs.\n",
            () -> List.of(paxBBP(10, 14, 24), paxBBP(10, 10, 24), paxBBP(null, 14, 14)),
            Map.of(),
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
        // expr (MAAL) op attr: basistarief maal 2 > prijs. 30*2=60>50 -> WAAR; 20*2=40>50 -> ONWAAR.
        new Fixture("arith expr (maal) > attr (basistarief maal 2 > prijs)",
            EXPR_CMP_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn basistarief maal 2 groter is dan zijn prijs.\n",
            () -> List.of(paxBBP(30, 0, 50), paxBBP(20, 0, 50)),
            Map.of(),
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur())),
    };
    static final Object[] PHASE_EXPR_CMP_EXPECTED = { vals(true, false, true), vals(true, false) };
    // §5.1/§4 a COMPOSITE (rate) cross-unit +/- in an arithmetic comparison stays a SYMMETRIC marker: the EUR/jr→EUR/mnd
    //   restatement keeps the single (lossy) ratio (its numerator/denominator precision edge is separately carded), so
    //   DRL herschaalConditiePlus markers it and the exec parityOnveilig skips it. (A SIMPLE jr/mnd cross-unit +/- now
    //   FIRES exactly — see phase_cross_unit_arithmetic_is_spec_correct; only the rate edge remains symmetric-declined.)
    static final String CROSS_UNIT_ARITH_CMP_SRC =
        "Eenheidsysteem Geld\n    de euro EUR\nEenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\n"
        + "Objecttype de Passagier\n    het basistarief Numeriek (geheel getal) met eenheid EUR/jr;\n    de bodem Numeriek (geheel getal) met eenheid EUR/mnd;\n    de prijs Numeriek (geheel getal) met eenheid EUR/jr;\n    is duur kenmerk (bijvoeglijk);\n"
        + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn basistarief plus zijn bodem gelijk is aan zijn prijs.\n";

    // ---- §4 EXACT cross-unit +/- ARITHMETIC: a SIMPLE convertible cross-unit +/- (jr vs mnd) restates the operand
    //      by the DECLARED integer factorNaarCanoniek (×÷, one DECIMAL64 rounding) instead of the pre-rounded ratio
    //      mnd→jr = 1/12 = 0.0833…. The lossy ratio gave 3.99999999999999992 for "2 jr + 24 mnd" on BOTH back-ends —
    //      a SHARED wrong value a pure parity check cannot catch, so assert the EXACT BigDecimal (differential-masks-
    //      shared-bugs). The constraint side now FIRES exactly too (was a symmetric marker/skip). ----
    static final String CU_VALUE_SRC =
        "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\n"
        + "Objecttype de Tijdmeter\n    de extra Numeriek (getal met 5 decimalen) met eenheid jr;\n"
        + "    de looptijd Numeriek (getal met 5 decimalen) met eenheid mnd;\n    de totaal Numeriek (getal met 5 decimalen) met eenheid jr;\n"
        + "Regel R\n    geldig altijd\n        De totaal van een Tijdmeter moet gesteld worden op de extra van een Tijdmeter plus de looptijd van een Tijdmeter.\n";
    static final String CU_COND_SRC =
        "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\n"
        + "Objecttype de Tijdmeter\n    de extra Numeriek (getal met 5 decimalen) met eenheid jr;\n"
        + "    de looptijd Numeriek (getal met 5 decimalen) met eenheid mnd;\n    de totaal Numeriek (getal met 5 decimalen) met eenheid jr;\n    is oud kenmerk (bijvoeglijk);\n"
        + "Regel R\n    geldig altijd\n        Een Tijdmeter is oud indien de extra van een Tijdmeter plus de looptijd van een Tijdmeter gelijk is aan de totaal van een Tijdmeter.\n";
    static Tijdmeter tm(Consumer<Tijdmeter> c) { Tijdmeter t = new Tijdmeter(); c.accept(t); return t; }
    static final Fixture[] PHASE_CU_ARITH = {
        // VALUE: 2 jr + 24 mnd = 4 jr EXACTLY (24 mnd → jr = ×1 ÷12 = 2; lossy ratio gave 3.99999999999999992)
        new Fixture("cross-unit value 2 jr + 24 mnd = 4 jr (exact)", CU_VALUE_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(2)); t.setLooptijd(new BigDecimal(24)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).getTotaal()),
        // VALUE: 1 jr + 6 mnd = 1.5 jr EXACTLY (6 mnd → jr = 0.5; lossy gave 1.49999999999999998)
        new Fixture("cross-unit value 1 jr + 6 mnd = 1.5 jr (exact)", CU_VALUE_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(1)); t.setLooptijd(new BigDecimal(6)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).getTotaal()),
        // CONSTRAINT: 2 jr + 24 mnd == 4 jr → WAAR (was a symmetric marker; now fires, 4 == 4 in jr / 48 == 48 canonical)
        new Fixture("cross-unit constraint 2 jr + 24 mnd == 4 jr -> waar", CU_COND_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(2)); t.setLooptijd(new BigDecimal(24)); t.setTotaal(new BigDecimal(4)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).isOud()),
        // CONSTRAINT: 2 jr + 24 mnd == 5 jr → ONWAAR (the exact restatement must NOT spuriously fire either)
        new Fixture("cross-unit constraint 2 jr + 24 mnd == 5 jr -> onwaar", CU_COND_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(2)); t.setLooptijd(new BigDecimal(24)); t.setTotaal(new BigDecimal(5)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).isOud()),
        // CONSTRAINT: a leeg looptijd counts as 0 (§6.2 Tabel 3): 2 jr + leeg == 2 jr → WAAR (no NPE on the null restatement)
        new Fixture("cross-unit constraint 2 jr + leeg == 2 jr -> waar (leeg=0)", CU_COND_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(2)); t.setTotaal(new BigDecimal(2)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).isOud()),
    };
    /** ABSOLUTE spec values: 4 jr, 1.5 jr, waar, onwaar, waar. The first two are the SHARED lossy-ratio bug a pure
     *  parity check would have missed (both back-ends produced 3.9999… / 1.4999…). */
    static final Object[] PHASE_CU_ARITH_EXPECTED = {
        new BigDecimal("4"), new BigDecimal("1.5"), Boolean.TRUE, Boolean.FALSE, Boolean.TRUE
    };

    // ---- §4 EXACT cross-unit restatement on the VALUE side beyond +/-: a pure ASSIGNMENT (gelijkstelling) and a
    //      value-side BEGRENZING bound. Both restated lossily before (×1/12 = 0.0833…) on BOTH back-ends; "24 mnd"
    //      stored into a jr attribute = 1.99999999999999992 instead of 2. Now ×source.factorNaarCanoniek
    //      ÷target.factorNaarCanoniek (rsHerschaalExact / herschaalWaarde). The begrenzing CONSTRAINT side stays a
    //      symmetric skip (parityOnveilig), unchanged. ----
    static final String CU_ASSIGN_SRC =
        "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\n"
        + "Objecttype de Tijdmeter\n    de looptijd Numeriek (getal met 5 decimalen) met eenheid mnd;\n    de totaal Numeriek (getal met 5 decimalen) met eenheid jr;\n"
        + "Regel R\n    geldig altijd\n        De totaal van een Tijdmeter moet gesteld worden op de looptijd van een Tijdmeter.\n";
    static final String CU_BEGR_VAL_SRC =
        "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\n"
        + "Objecttype de Tijdmeter\n    de extra Numeriek (getal met 5 decimalen) met eenheid jr;\n    de looptijd Numeriek (getal met 5 decimalen) met eenheid mnd;\n    de totaal Numeriek (getal met 5 decimalen) met eenheid jr;\n"
        + "Regel R\n    geldig altijd\n        De totaal van een Tijdmeter moet gesteld worden op de extra van een Tijdmeter, met een minimum van de looptijd van een Tijdmeter.\n";
    static final Fixture[] PHASE_CU_VALUE = {
        // ASSIGNMENT 24 mnd -> jr = 2 EXACTLY (was 1.99999999999999992 in BOTH back-ends)
        new Fixture("cross-unit assignment 24 mnd -> 2 jr (exact)", CU_ASSIGN_SRC,
            () -> List.of(tm(t -> t.setLooptijd(new BigDecimal(24)))), Map.of(), l -> ((Tijdmeter) l.get(0)).getTotaal()),
        // ASSIGNMENT 6 mnd -> jr = 0.5 EXACTLY (was 0.49999999999999998)
        new Fixture("cross-unit assignment 6 mnd -> 0.5 jr (exact)", CU_ASSIGN_SRC,
            () -> List.of(tm(t -> t.setLooptijd(new BigDecimal(6)))), Map.of(), l -> ((Tijdmeter) l.get(0)).getTotaal()),
        // VALUE BEGRENZING: max(extra 1 jr, looptijd 24 mnd -> 2 jr) = 2 jr (the bound restate mnd->jr is the lossy direction)
        new Fixture("cross-unit value begrenzing max(1 jr, 24 mnd=2 jr) = 2 jr", CU_BEGR_VAL_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(1)); t.setLooptijd(new BigDecimal(24)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).getTotaal()),
    };
    static final Object[] PHASE_CU_VALUE_EXPECTED = { new BigDecimal("2"), new BigDecimal("0.5"), new BigDecimal("2") };

    // ---- §6.1.4/§4 EXACT cross-unit begrenzing BOUND inside a CONSTRAINT now FIRES at parity (was a symmetric
    //      marker/skip): the min/max bound (mnd) is restated EXACTLY into the basis unit (jr) — DRL herschaalConditie
    //      -> rsHerschaalExact, exec scalarWaarde's .herschaald — so a "min van 24 mnd" clamp compares against an exact
    //      2 jr. The lossy single ratio gave 1.99999999999999992, so the EQUALITY "== 2 jr" flipped to ONWAAR — a
    //      SHARED wrong decision a pure parity check would miss; assert the ABSOLUTE spec value (differential-masks-
    //      shared-bugs). Covers a minimum bound, a maximum bound, a negative control, and a leeg bound (no NPE). ----
    static final String CU_BEGR_MIN_COND_SRC =
        "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\n"
        + "Objecttype de Tijdmeter\n    de extra Numeriek (getal met 5 decimalen) met eenheid jr;\n    de looptijd Numeriek (getal met 5 decimalen) met eenheid mnd;\n    de totaal Numeriek (getal met 5 decimalen) met eenheid jr;\n    is oud kenmerk (bijvoeglijk);\n"
        + "Regel R\n    geldig altijd\n        Een Tijdmeter is oud indien de extra van een Tijdmeter, met een minimum van de looptijd van een Tijdmeter gelijk is aan de totaal van een Tijdmeter.\n";
    static final String CU_BEGR_MAX_COND_SRC = CU_BEGR_MIN_COND_SRC.replace("met een minimum van", "met een maximum van");
    static final Fixture[] PHASE_CU_BEGR_COND = {
        // MIN bound, 24 mnd boundary: clamp max(0 jr, 24 mnd=2 jr) = 2 jr; 2 == 2 -> WAAR (lossy gave 1.9999…992 != 2 -> ONWAAR)
        new Fixture("cross-unit begrenzing-constraint min(24 mnd=2 jr) == 2 jr -> waar", CU_BEGR_MIN_COND_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(0)); t.setLooptijd(new BigDecimal(24)); t.setTotaal(new BigDecimal(2)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).isOud()),
        // MIN bound negative control: clamp = 2 jr; 2 == 3 -> ONWAAR (the exact restatement must not spuriously fire)
        new Fixture("cross-unit begrenzing-constraint min(24 mnd=2 jr) == 3 jr -> onwaar", CU_BEGR_MIN_COND_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(0)); t.setLooptijd(new BigDecimal(24)); t.setTotaal(new BigDecimal(3)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).isOud()),
        // MAX bound, 24 mnd boundary: clamp min(5 jr, 24 mnd=2 jr) = 2 jr; 2 == 2 -> WAAR
        new Fixture("cross-unit begrenzing-constraint max(24 mnd=2 jr) == 2 jr -> waar", CU_BEGR_MAX_COND_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(5)); t.setLooptijd(new BigDecimal(24)); t.setTotaal(new BigDecimal(2)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).isOud()),
        // LEEG bound: looptijd leeg -> no min clamp (rsBegrens(0, null, null) = 0); 0 == 0 -> WAAR, no NPE on the null bound
        new Fixture("cross-unit begrenzing-constraint min(leeg) == 0 jr -> waar (no NPE)", CU_BEGR_MIN_COND_SRC,
            () -> List.of(tm(t -> { t.setExtra(new BigDecimal(0)); t.setTotaal(new BigDecimal(0)); })),
            Map.of(), l -> ((Tijdmeter) l.get(0)).isOud()),
    };
    static final Object[] PHASE_CU_BEGR_COND_EXPECTED = { Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE };

    // ---- §4.4 Tabel 11: a unitless LITERAL times a unit-bearing operand ("2 maal de looptijd(mnd)") carries the right
    //      operand's unit (mnd), so the assignment into a jr doel restates it: 2 × 24 mnd = 48 mnd -> 4 jr. Before, the
    //      unitless×unit-on-the-RIGHT degraded to Onbekend on BOTH back-ends (eenheidOf()==null -> no restate), storing
    //      48 verbatim — a SHARED wrong value, so assert the ABSOLUTE spec value. An ATTRIBUTE-left stays Onbekend (the
    //      TOKA unit-under-model tolerance) — covered by CasusTest; only a LITERAL left triggers the unit propagation. ----
    static final String CU_MAAL_SRC =
        "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\n"
        + "Objecttype de Tijdmeter\n    de looptijd Numeriek (getal met 5 decimalen) met eenheid mnd;\n    de totaal Numeriek (getal met 5 decimalen) met eenheid jr;\n"
        + "Regel R\n    geldig altijd\n        De totaal van een Tijdmeter moet gesteld worden op 2 maal de looptijd van een Tijdmeter.\n";
    static final Fixture[] PHASE_CU_MAAL = {
        // 2 maal 24 mnd = 48 mnd -> 4 jr (was 48 on both back-ends — unitless×unit-on-right Onbekend, no restate)
        new Fixture("unitless-literal 2 maal looptijd 24 mnd -> 4 jr", CU_MAAL_SRC,
            () -> List.of(tm(t -> t.setLooptijd(new BigDecimal(24)))), Map.of(), l -> ((Tijdmeter) l.get(0)).getTotaal()),
        // 2 maal 9 mnd = 18 mnd -> 1.5 jr (the source is "2 maal …"; a fractional jr result exercises the restatement)
        new Fixture("unitless-literal 2 maal looptijd 9 mnd -> 1.5 jr", CU_MAAL_SRC,
            () -> List.of(tm(t -> t.setLooptijd(new BigDecimal(9)))), Map.of(), l -> ((Tijdmeter) l.get(0)).getTotaal()),
    };
    static final Object[] PHASE_CU_MAAL_EXPECTED = { new BigDecimal("4"), new BigDecimal("1.5") };

    // ---- §5.8.3 a MIN/MAX instance-aggregation written into a DIFFERENT-unit doel. The min/max accumulate binds as a
    //      java.lang.Number, so the cross-unit restate's .multiply USED TO FAIL the DRL build (java.lang.Number.multiply)
    //      while the exec fired correctly -> a DRL-build-error-vs-exec-fires DIVERGENCE (wf_7e1d0ff9 review, medium).
    //      The DRL now coerces the min/max binding to BigDecimal (rsGetal), mirroring the exec's toBd. SOM already
    //      yields a BigDecimal accumulate. (Pre-existing; the cross-unit restate on $accN predates the value-side work.) ----
    static final String CU_AGG_SRC =
        "Eenheidsysteem Tijd\n    de maand mnd\n    het jaar jr = 12 mnd\n"
        + "Objecttype de Vlucht\n    de reisduur Numeriek (getal met 5 decimalen) met eenheid jr;\n"
        + "Objecttype de Passagier (mv: passagiers)\n    de leeftijd Numeriek (getal met 5 decimalen) met eenheid mnd;\n"
        + "Feittype Vlucht van passagiers\n    de reis Vlucht\n    de passagier Passagier\nEén reis betreft meerdere passagiers\n"
        + "Regel m\n    geldig altijd\n        De reisduur van een Vlucht moet berekend worden als de %s van de leeftijd van alle passagiers van de reis.\n";
    static Supplier<List<Object>> cuAggFacts() {
        return () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); fs.add(v);
            for (int d : new int[]{24, 12, 6}) { Passagier p = new Passagier(); p.setLeeftijd(new BigDecimal(d)); p.setReis(v); fs.add(p); }
            return fs; };
    }
    static final Fixture[] PHASE_CU_AGG = {
        new Fixture("cross-unit MAX aggregation max(24,12,6) mnd = 24 mnd = 2 jr", String.format(CU_AGG_SRC, "maximale waarde"),
            cuAggFacts(), Map.of(), l -> ((Vlucht) l.get(0)).getReisduur()),
        new Fixture("cross-unit MIN aggregation min(24,12,6) mnd = 6 mnd = 0.5 jr", String.format(CU_AGG_SRC, "minimale waarde"),
            cuAggFacts(), Map.of(), l -> ((Vlucht) l.get(0)).getReisduur()),
    };
    static final Object[] PHASE_CU_AGG_EXPECTED = { new BigDecimal("2"), new BigDecimal("0.5") };

    // ---- §4 a COMPOSITE (rate) cross-unit restatement is now EXACT too (numerator × inverted-denominator integer
    //      factors via Eenheid.exacteHerschaalFactoren, not the pre-rounded single ratio): "120 EUR/jr" assigned to a
    //      EUR/mnd attribute = 10, not 9.99999999999999960 (a SHARED lossy value on BOTH back-ends). The rate +/-
    //      CONSTRAINT now FIRES at parity too (was a symmetric marker/skip). ----
    static final String CU_RATE_HEAD = "Eenheidsysteem Geld\n    de euro EUR\nEenheidsysteem Tijd\n    de maand mnd\n    het jaar jr = 12 mnd\n";
    static final String CU_RATE_ASSIGN_SRC = CU_RATE_HEAD
        + "Objecttype de Tarief\n    de jaartarief Numeriek (getal met 5 decimalen) met eenheid EUR/jr;\n    de maandtarief Numeriek (getal met 5 decimalen) met eenheid EUR/mnd;\n"
        + "Regel R\n    geldig altijd\n        De maandtarief van een Tarief moet gesteld worden op de jaartarief van een Tarief.\n";
    static final String CU_RATE_COND_SRC = CU_RATE_HEAD
        + "Objecttype de Tarief\n    de jaartarief Numeriek (getal met 5 decimalen) met eenheid EUR/jr;\n    de maandtarief Numeriek (getal met 5 decimalen) met eenheid EUR/mnd;\n    de grens Numeriek (getal met 5 decimalen) met eenheid EUR/jr;\n    is hoog kenmerk (bijvoeglijk);\n"
        + "Regel R\n    geldig altijd\n        Een Tarief is hoog indien de jaartarief van een Tarief plus de maandtarief van een Tarief gelijk is aan de grens van een Tarief.\n";
    static Tarief tarief(Consumer<Tarief> c) { Tarief t = new Tarief(); c.accept(t); return t; }
    static final Fixture[] PHASE_CU_RATE = {
        // ASSIGNMENT: 120 EUR/jr -> EUR/mnd = 10 EXACTLY (was 9.99999999999999960 in BOTH back-ends)
        new Fixture("rate assignment 120 EUR/jr -> 10 EUR/mnd (exact)", CU_RATE_ASSIGN_SRC,
            () -> List.of(tarief(t -> t.setJaartarief(new BigDecimal(120)))), Map.of(), l -> ((Tarief) l.get(0)).getMaandtarief()),
        // +/- CONSTRAINT: 120 EUR/jr + 10 EUR/mnd (=120 EUR/jr) = 240 EUR/jr == grens 240 EUR/jr -> WAAR (now fires, was a marker)
        new Fixture("rate +/- constraint 120/jr + 10/mnd == 240/jr -> waar", CU_RATE_COND_SRC,
            () -> List.of(tarief(t -> { t.setJaartarief(new BigDecimal(120)); t.setMaandtarief(new BigDecimal(10)); t.setGrens(new BigDecimal(240)); })),
            Map.of(), l -> ((Tarief) l.get(0)).isHoog()),
    };
    static final Object[] PHASE_CU_RATE_EXPECTED = { new BigDecimal("10"), Boolean.TRUE };

    // ---- §8.1.1 the "belasting op basis van afstand" shape in miniature: a condition whose LHS is an
    //      arithmetic expression reading TWO distinct numeric Parameter-GLOBALS —
    //      "het lage basistarief eerste schijf min (het lage tarief vermindering eerste schijf maal zijn afstand)"
    //      — compared >= 0, gating a gelijkstelling; the 2-global gset routes through nGlobalGate.
    //      Historical note: this fixture originally mirrored toka-45's unitless-Bedrag × km under-model, typed
    //      Onbekend by the old resolver. Since besluit 16Y (2026-08-29) the vendored frontend types identity
    //      products (unitless × km = km) and the min would fault the unit rule — the km was incidental scenery
    //      here (raw BigDecimals throughout), so the fixture is unit-free; the dimensional refusal itself is
    //      witnessed by TokaVuurTest and CasusTest. basistarief 100 - 0.20*200 = 60 >= 0 -> fires -> 60. ----
    static final String VW_MULTIGLOBAL_SRC = """
        Parameter het lage basistarief eerste schijf : Numeriek (getal)
        Parameter het lage tarief vermindering eerste schijf : Numeriek (getal)
        Objecttype de Schijf (mv: Schijven)
            de afstand Numeriek (geheel getal);
            de belasting Numeriek (getal);
        Regel belasting op basis van afstand
            geldig altijd
                De belasting van een Schijf moet gesteld worden op X min Y
                indien X min Y groter of gelijk is aan 0.
                Daarbij geldt:
                    X is het lage basistarief eerste schijf
                    Y is het lage tarief vermindering eerste schijf maal zijn afstand.
        """;
    static Fixture schijfFix(String naam, java.util.function.Consumer<Schijf> seed, Map<String, Object> globals) {
        return new Fixture(naam, VW_MULTIGLOBAL_SRC,
            () -> { Schijf s = new Schijf(); seed.accept(s); return List.<Object>of(s); },
            globals, l -> ((Schijf) l.get(0)).getBelasting());
    }
    static final Map<String, Object> VW_MG_PARAMS = Map.of(
        "lageBasistariefEersteSchijf", new BigDecimal("100"),
        "lageTariefVerminderingEersteSchijf", new BigDecimal("0.20"));
    static final Fixture[] PHASE_VW_MULTIGLOBAL = {
        // 100 - 0.20*200 = 60 >= 0 -> the compound guard fires -> belasting := 60
        schijfFix("100 - 0.20*200 = 60 >= 0 -> fires -> 60", s -> s.setAfstand(new BigDecimal(200)), VW_MG_PARAMS),
        // 100 - 0.20*600 = -20 < 0 -> the guard is FALSE -> belasting stays null (no fire)
        schijfFix("100 - 0.20*600 = -20 < 0 -> no fire -> null", s -> s.setAfstand(new BigDecimal(600)), VW_MG_PARAMS),
    };
    static final Object[] PHASE_VW_MULTIGLOBAL_EXPECTED = { new BigDecimal("60"), null };

    // ---- §5.3 an ENUMERATIE attribute compared to an enum-typed Parameter-GLOBAL ("zijn roepnaam gelijk is aan de
    //      STANDAARDNAAM"). DRL reads the enum global as a free name (String equality); the exec used to SKIP (the
    //      tekst-vs-global branch gated on isTekst(), which excludes Enumeratie) -> a masked DRL-fires/exec-skips.
    //      Enum params now register as String globals + the branch accepts Enumeratie, lowering through tekstVergelijk
    //      (an enumeratiewaarde is its String label). Covers own + to-one NAVIGATED, incl. the ABSENT-relation ONGELIJK
    //      row (leeg != X = WAAR, Tabel 44 — the coverage a vs-global fixture must always include). ----
    static String enumGlobalSrc(boolean nav, String op) {
        String head = "Domein Naam is van het type Enumeratie\n    'Tom'\n    'Maria'\n"
            + "Parameter de STANDAARDNAAM : Naam\n"
            + "Objecttype de Persoon (mv: Personen) (bezield)\n    de roepnaam Naam;\n    is gepensioneerd kenmerk (bijvoeglijk);\n";
        String rel = nav ? "Feittype partnerschap\n    de persoon Persoon\n    de partner Persoon\nEén persoon heeft één partner\n" : "";
        String onderwerp = nav ? "de roepnaam van zijn partner" : "zijn roepnaam";
        return head + rel + "Regel R\n    geldig altijd\n        Een Persoon is gepensioneerd indien " + onderwerp + " " + op + " de STANDAARDNAAM.\n";
    }
    static Persoon epersoon(String roepnaam) { Persoon p = new Persoon(); p.setRoepnaam(roepnaam); return p; }
    static final Map<String, Object> STDNAAM = Map.of("standaardnaam", "Maria");
    static final Fixture[] PHASE_ENUM_GLOBAL = {
        new Fixture("enum-vs-global GELIJK Maria==Maria -> waar", enumGlobalSrc(false, "gelijk is aan"),
            () -> List.of(epersoon("Maria")), STDNAAM, l -> ((Persoon) l.get(0)).isGepensioneerd()),
        new Fixture("enum-vs-global GELIJK Tom==Maria -> onwaar", enumGlobalSrc(false, "gelijk is aan"),
            () -> List.of(epersoon("Tom")), STDNAAM, l -> ((Persoon) l.get(0)).isGepensioneerd()),
        new Fixture("enum-vs-global GELIJK leeg==Maria -> onwaar (Tabel 38)", enumGlobalSrc(false, "gelijk is aan"),
            () -> List.of(epersoon(null)), STDNAAM, l -> ((Persoon) l.get(0)).isGepensioneerd()),
        new Fixture("enum-vs-global ONGELIJK leeg!=Maria -> waar (Tabel 44)", enumGlobalSrc(false, "ongelijk is aan"),
            () -> List.of(epersoon(null)), STDNAAM, l -> ((Persoon) l.get(0)).isGepensioneerd()),
        // NAVIGATED: metPartner reads partner.roepnaam=Maria == Maria -> waar (the bare partner reads its own null partner -> leeg, stays onwaar)
        new Fixture("enum-vs-global NAV partner.roepnaam Maria==Maria -> waar", enumGlobalSrc(true, "gelijk is aan"),
            () -> { Persoon partner = epersoon("Maria"); Persoon metPartner = epersoon(null); metPartner.setPartner(partner);
                    return List.of(metPartner, partner); }, STDNAAM, l -> ((Persoon) l.get(0)).isGepensioneerd()),
        // NAVIGATED ABSENT relation: no partner -> roepnaam van zijn partner = leeg; leeg != Maria = WAAR (Tabel 44)
        new Fixture("enum-vs-global NAV absent-partner ONGELIJK -> waar (Tabel 44)", enumGlobalSrc(true, "ongelijk is aan"),
            () -> List.of(epersoon(null)), STDNAAM, l -> ((Persoon) l.get(0)).isGepensioneerd()),
    };
    static final Object[] PHASE_ENUM_GLOBAL_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE
    };

    // ---- §13.4.6 assign a TEKST/ENUMERATIE Parameter-GLOBAL INTO an attribute ("De roepnaam wordt gesteld op de
    //      STANDAARDNAAM"): DRL build-errored (the global was declared `Object`, so its String setter did not resolve)
    //      and the exec SKIPPED (the numeric value-path binds globals as BigDecimal). The DRL now declares an enum/tekst
    //      global with its String type, and the exec binds the String global + sets it directly. ----
    static final String ENUM_ASSIGN_SRC =
        "Domein Naam is van het type Enumeratie\n    'Tom'\n    'Maria'\n"
        + "Parameter de STANDAARDNAAM : Naam\n"
        + "Objecttype de Persoon (mv: Personen) (bezield)\n    de roepnaam Naam;\n"
        + "Regel R\n    geldig altijd\n        De roepnaam van een Persoon moet gesteld worden op de STANDAARDNAAM.\n";
    static final String TEKST_ASSIGN_SRC =
        "Parameter de STANDAARDNAAM : Tekst\n"
        + "Objecttype de Persoon (mv: Personen) (bezield)\n    de roepnaam Tekst;\n"
        + "Regel R\n    geldig altijd\n        De roepnaam van een Persoon moet gesteld worden op de STANDAARDNAAM.\n";
    static final String DATUM_ASSIGN_SRC =
        "Parameter de PEILDATUM : Datum in dagen\n"
        + "Objecttype de Klok\n    de reisdatum Datum in dagen;\n"
        + "Regel R\n    geldig altijd\n        De reisdatum van een Klok moet gesteld worden op de PEILDATUM.\n";
    static final String DATUMTIJD_ASSIGN_SRC =
        "Parameter de PEILMOMENT : Datum en tijd in millisecondes\n"
        + "Objecttype de Klok\n    de moment Datum en tijd in millisecondes;\n"
        + "Regel R\n    geldig altijd\n        De moment van een Klok moet gesteld worden op de PEILMOMENT.\n";
    static final Fixture[] PHASE_ENUM_ASSIGN = {
        new Fixture("enum-global value-assignment roepnaam := STANDAARDNAAM (Maria)", ENUM_ASSIGN_SRC,
            () -> List.of(new Persoon()), Map.of("standaardnaam", "Maria"), l -> ((Persoon) l.get(0)).getRoepnaam()),
        new Fixture("tekst-global value-assignment roepnaam := STANDAARDNAAM (Tom)", TEKST_ASSIGN_SRC,
            () -> List.of(new Persoon()), Map.of("standaardnaam", "Tom"), l -> ((Persoon) l.get(0)).getRoepnaam()),
        // §13.4.6 a DATUM Parameter-global assigned into a datum attribute (the wf_12d0005c review's divergence:
        // DRL fired, exec skipped because a datum global is not in the numeric `globals` map).
        new Fixture("datum-global value-assignment reisdatum := PEILDATUM (2020-01-15)", DATUM_ASSIGN_SRC,
            () -> List.of(new Klok()), Map.of("peildatum", LocalDate.of(2020, 1, 15)), l -> ((Klok) l.get(0)).getReisdatum()),
        new Fixture("datumtijd-global value-assignment moment := PEILMOMENT", DATUMTIJD_ASSIGN_SRC,
            () -> List.of(new Klok()), Map.of("peilmoment", LocalDateTime.of(2020, 1, 15, 9, 30)), l -> ((Klok) l.get(0)).getMoment()),
    };
    static final Object[] PHASE_ENUM_ASSIGN_EXPECTED = { "Maria", "Tom", LocalDate.of(2020, 1, 15), LocalDateTime.of(2020, 1, 15, 9, 30) };

    // ---- §8.1.1 an EXPRESSION-vs-GLOBAL comparison ("zijn basistarief plus zijn bodem groter is dan de grens",
    //      "zijn basistarief plus de toeslag groter is dan zijn prijs") — the exec voorwaarde binds the SINGLE distinct
    //      numeric/percentage Parameter-global as a pattern variable (a 2-var expr), lowering both sides via scalarWaarde;
    //      DRL reads it as a free name. Now FIRES in both (was an honest exec skip). >1 distinct globals stays a skip. ----
    static final Fixture[] PHASE_EXPR_GLOBAL = {
        // expr vs GLOBAL: basistarief + bodem > grens(50). 30+30=60>50 -> WAAR; 10+10=20 -> ONWAAR; leeg+30=30 -> ONWAAR (leeg=0).
        new Fixture("expr vs global (basistarief + bodem > de grens), incl. leeg",
            "Parameter de grens : Numeriek (geheel getal)\n" + EXPR_CMP_HDR
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn basistarief plus zijn bodem groter is dan de grens.\n",
            () -> List.of(paxBBP(30, 30, 0), paxBBP(10, 10, 0), paxBBP(null, 30, 0)),
            Map.of("grens", new BigDecimal(50)),
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
        // GLOBAL inside the expr: basistarief + toeslag(20) > prijs. 40+20=60>50 -> WAAR; 20+20=40>50 -> ONWAAR.
        new Fixture("global inside expr (basistarief + de toeslag > prijs)",
            "Parameter de toeslag : Numeriek (geheel getal)\n" + EXPR_CMP_HDR
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn basistarief plus de toeslag groter is dan zijn prijs.\n",
            () -> List.of(paxBBP(40, 0, 50), paxBBP(20, 0, 50)),
            Map.of("toeslag", new BigDecimal(20)),
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur())),
    };
    static final Object[] PHASE_EXPR_GLOBAL_EXPECTED = { vals(true, false, false), vals(true, false) };
    // §8.1.1 a comparison reading 2..3 DISTINCT globals ("basistarief + de toeslag > de grens") now FIRES in BOTH
    //   back-ends: the exec binds EACH distinct global into its OWN Optional declaration sourced from the (always-present)
    //   subject × the global (distinct decls don't collapse; the subject anchor keeps a LEEG global's source non-empty),
    //   matching DRL's free-name globals incl. the §6/§5 leegwaarde corners. Locked by PHASE_EXPR_MULTI_GLOBAL.
    static final String EXPR_TWO_GLOBALS_SRC =
        "Parameter de toeslag : Numeriek (geheel getal)\nParameter de grens : Numeriek (geheel getal)\n" + EXPR_CMP_HDR
        + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn basistarief plus de toeslag groter is dan de grens.\n";
    static final String EXPR_THREE_GLOBALS_SRC =
        "Parameter de a : Numeriek (geheel getal)\nParameter de b : Numeriek (geheel getal)\nParameter de c : Numeriek (geheel getal)\n" + EXPR_CMP_HDR
        + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn basistarief plus de a plus de b groter is dan de c.\n";
    static final Fixture[] PHASE_EXPR_MULTI_GLOBAL = {
        // 2 globals both set: basistarief 30 + toeslag 20 = 50 > grens 40 -> WAAR
        new Fixture("2 globals waar (30 + 20 > 40)", EXPR_TWO_GLOBALS_SRC,
            () -> List.of(paxBBP(30, 0, 0)), Map.of("toeslag", new BigDecimal(20), "grens", new BigDecimal(40)),
            l -> ((Passagier) l.get(0)).isDuur()),
        // 2 globals onwaar: 50 > 60 -> ONWAAR
        new Fixture("2 globals onwaar (30 + 20 > 60)", EXPR_TWO_GLOBALS_SRC,
            () -> List.of(paxBBP(30, 0, 0)), Map.of("toeslag", new BigDecimal(20), "grens", new BigDecimal(60)),
            l -> ((Passagier) l.get(0)).isDuur()),
        // toeslag LEEG INSIDE the arithmetic (unset global): 30 + 0 > 25 -> WAAR (§6.2 leeg-as-0, like the single-global path)
        new Fixture("2 globals, toeslag LEEG inside arith (30 + leeg > 25 -> waar)", EXPR_TWO_GLOBALS_SRC,
            () -> List.of(paxBBP(30, 0, 0)), Map.of("grens", new BigDecimal(25)),
            l -> ((Passagier) l.get(0)).isDuur()),
        // grens LEEG as a BARE comparison operand (unset global): 50 > leeg -> ONWAAR (§5 leeg-comparison)
        new Fixture("2 globals, grens LEEG bare operand (50 > leeg -> onwaar)", EXPR_TWO_GLOBALS_SRC,
            () -> List.of(paxBBP(30, 0, 0)), Map.of("toeslag", new BigDecimal(20)),
            l -> ((Passagier) l.get(0)).isDuur()),
        // 3 globals: basistarief 10 + a 5 + b 7 = 22 > c 20 -> WAAR
        new Fixture("3 globals waar (10 + 5 + 7 > 20)", EXPR_THREE_GLOBALS_SRC,
            () -> List.of(paxBBP(10, 0, 0)), Map.of("a", new BigDecimal(5), "b", new BigDecimal(7), "c", new BigDecimal(20)),
            l -> ((Passagier) l.get(0)).isDuur()),
        // 3 globals onwaar: 22 > 30 -> ONWAAR
        new Fixture("3 globals onwaar (10 + 5 + 7 > 30)", EXPR_THREE_GLOBALS_SRC,
            () -> List.of(paxBBP(10, 0, 0)), Map.of("a", new BigDecimal(5), "b", new BigDecimal(7), "c", new BigDecimal(30)),
            l -> ((Passagier) l.get(0)).isDuur()),
    };
    static final Object[] PHASE_EXPR_MULTI_GLOBAL_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE };

    // ---- §8.1.1 a NAVIGATED operand INSIDE an arithmetic-expression comparison ("de vertrektijd van zijn reis plus
    //      zijn basistarief groter is dan zijn prijs"). The exec lowers the navigated leg via scalarWaarde's navReader
    //      (value-path; valueSubject set in the leaf), and an ABSENT relation reads LEEG -> leeg-as-0 in the +/- (§4.1
    //      Tabel 3), NOT a short-circuit. DRL used to render the leg as mvel `reis!.vertrektijd`, whose `!.` SHORT-CIRCUITS
    //      the whole constraint to a non-match on a null relation — DROPPING the leeg-as-0 (a latent bug: it skipped the
    //      WHOLE comparison instead of computing 0+basistarief). DrlTranspiler now reads a navigated arithmetic leg via the
    //      null-safe rsRelGetal helper, so BOTH back-ends compute leeg-as-0 and fire identically (was a masked DRL-fires-
    //      WRONG / exec-skips). Subject Passagier navigates "zijn reis" (a to-ONE role to Vlucht); a null reis -> leeg. ----
    static final String NAV_EXPR_HDR =
            "Objecttype de Vlucht\n    de vertrektijd Numeriek (geheel getal);\n"
            + "Objecttype de Passagier\n    het basistarief Numeriek (geheel getal);\n    de prijs Numeriek (geheel getal);\n    is duur kenmerk (bijvoeglijk);\n" + AGG_FEIT;
    static Passagier paxNav(Vlucht reis, Integer bt, Integer prijs) {
        Passagier p = new Passagier();
        p.setReis(reis);
        if (bt != null) p.setBasistarief(new BigDecimal(bt));
        if (prijs != null) p.setPrijs(new BigDecimal(prijs));
        return p;
    }
    static final Fixture[] PHASE_NAV_EXPR = {
        // GROTER: (vertrektijd van zijn reis) + basistarief > prijs. Rows: present-true (20+100>50), present-false
        // (5+10>50=onwaar), ABSENT relation (leeg+100 -> 0+100>50=WAAR, the leeg-as-0 row the DRL `!.` bug used to drop),
        // and PRESENT relation w/ a NULL vertrektijd attribute (also leeg -> 0+100>50=WAAR — distinguishes null attr from
        // null relation: both read leeg here, both fire). Vluchten at [0..2], passengers at [3..6].
        new Fixture("navigated leg inside + : (vertrektijd van zijn reis) + basistarief > prijs, incl. absent + null-attr",
            NAV_EXPR_HDR + "Regel R\n    geldig altijd\n"
            + "        Een Passagier is duur indien de vertrektijd van zijn reis plus zijn basistarief groter is dan zijn prijs.\n",
            () -> { Vlucht v20 = new Vlucht(); v20.setVertrektijd(new BigDecimal(20));
                    Vlucht v5 = new Vlucht(); v5.setVertrektijd(new BigDecimal(5));
                    Vlucht vNull = new Vlucht();   // vertrektijd null
                    return List.of(v20, v5, vNull, paxNav(v20, 100, 50), paxNav(v5, 10, 50), paxNav(null, 100, 50), paxNav(vNull, 100, 50)); },
            Map.of(),
            l -> vals(((Passagier) l.get(3)).isDuur(), ((Passagier) l.get(4)).isDuur(),
                      ((Passagier) l.get(5)).isDuur(), ((Passagier) l.get(6)).isDuur())),
        // ONGELIJK through the same path: (vertrektijd van zijn reis) + basistarief != prijs. Both passengers have an
        // ABSENT reis (leeg-as-0): (0+10)=10 != 50 -> WAAR; (0+50)=50 != 50 -> ONWAAR. The arithmetic resolves the leeg to
        // a concrete 0, so the != compares concrete numbers (Tabel 44's one-leeg row is not reached — leeg-as-0 precedes).
        new Fixture("navigated leg inside + with ONGELIJK, absent relation (leeg-as-0 then concrete !=)",
            NAV_EXPR_HDR + "Regel R\n    geldig altijd\n"
            + "        Een Passagier is duur indien de vertrektijd van zijn reis plus zijn basistarief ongelijk is aan zijn prijs.\n",
            () -> List.of(paxNav(null, 10, 50), paxNav(null, 50, 50)),
            Map.of(),
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur())),
    };
    static final Object[] PHASE_NAV_EXPR_EXPECTED = { vals(true, false, true, true), vals(true, false) };

    // ---- §8.1.1 a NAVIGATED operand AND a Parameter-GLOBAL in ONE arithmetic comparison ("de vertrektijd van zijn reis
    //      plus de toeslag groter is dan zijn prijs"). The exec voorwaarde global-binding branch binds the single global
    //      as a pattern var AND (now) sets valueSubject so scalarWaarde's navigated read resolves — was a masked DRL-fires/
    //      exec-skips (the branch skipped a navigated operand). DRL renders rsRelGetal(reis,"vertrektijd") + the free-name
    //      global; an absent relation reads leeg -> leeg-as-0. Both fire identically now. ----
    static final Fixture[] PHASE_NAV_GLOBAL = {
        // (vertrektijd van zijn reis) + toeslag(30) > prijs. present-true (20+30=50>40), present-false (5+30=35>40=onwaar),
        // ABSENT onwaar (0+30=30>40=onwaar), ABSENT waar (0+30=30>25=WAAR -> leeg-as-0 still lets the global+rest fire).
        new Fixture("navigated leg + global in one comparison: (vertrektijd van zijn reis) + toeslag > prijs",
            "Parameter de toeslag : Numeriek (geheel getal)\n" + NAV_EXPR_HDR + "Regel R\n    geldig altijd\n"
            + "        Een Passagier is duur indien de vertrektijd van zijn reis plus de toeslag groter is dan zijn prijs.\n",
            () -> { Vlucht v20 = new Vlucht(); v20.setVertrektijd(new BigDecimal(20));
                    Vlucht v5 = new Vlucht(); v5.setVertrektijd(new BigDecimal(5));
                    return List.of(v20, v5, paxNav(v20, null, 40), paxNav(v5, null, 40), paxNav(null, null, 40), paxNav(null, null, 25)); },
            Map.of("toeslag", new BigDecimal(30)),
            l -> vals(((Passagier) l.get(2)).isDuur(), ((Passagier) l.get(3)).isDuur(),
                      ((Passagier) l.get(4)).isDuur(), ((Passagier) l.get(5)).isDuur())),
    };
    static final Object[] PHASE_NAV_GLOBAL_EXPECTED = { vals(true, false, false, true) };

    // ---- §5.5.5 / §5.6 a STANDALONE navigated comparison whose to-one relation is ABSENT reads the operand as LEEGWAARDE,
    //      then applies the typeringen leegwaarde tables (Tabel 33-44) — NOT mvel's `!.` blanket short-circuit-to-false.
    //      ONGELIJK of a leeg-vs-gevuld operand is WAAR (Tabel 44); GELIJK of both-leeg is WAAR (Tabel 37); ordering and
    //      one-sided GELIJK are onwaar. Both back-ends now agree AND match spec (was a masked SHARED bug: parity-only PHASE_H
    //      hid it). Fixtures assert ABSOLUTE spec values across {present-true, present-false, ABSENT relation, both-leeg,
    //      present-null-attribute}. Vluchten + passengers; reis=null = absent relation; vertrektijd null = present-null-attr. ----
    static final Fixture[] PHASE_NAV_LEEG = {
        // ONGELIJK: (vertrektijd van zijn reis) != zijn prijs. 80!=50 WAAR; 50!=50 ONWAAR; ABSENT(leeg)!=50 -> WAAR (Tabel
        // 44, THE fix — was false); ABSENT + prijs null (both-leeg) -> ONWAAR; present vertrektijd null (leeg)!=50 -> WAAR.
        new Fixture("navigated ONGELIJK leegwaarde matrix (Tabel 44: absent + present-null-attr fire, both-leeg not)",
            NAV_EXPR_HDR + "Regel R\n    geldig altijd\n"
            + "        Een Passagier is duur indien de vertrektijd van zijn reis ongelijk is aan zijn prijs.\n",
            () -> { Vlucht v80 = new Vlucht(); v80.setVertrektijd(new BigDecimal(80));
                    Vlucht v50 = new Vlucht(); v50.setVertrektijd(new BigDecimal(50));
                    Vlucht vNull = new Vlucht();   // vertrektijd null
                    return List.of(v80, v50, vNull, paxNav(v80, null, 50), paxNav(v50, null, 50),
                                   paxNav(null, null, 50), paxNav(null, null, null), paxNav(vNull, null, 50)); },
            Map.of(),
            l -> vals(((Passagier) l.get(3)).isDuur(), ((Passagier) l.get(4)).isDuur(), ((Passagier) l.get(5)).isDuur(),
                      ((Passagier) l.get(6)).isDuur(), ((Passagier) l.get(7)).isDuur())),
        // GELIJK: (vertrektijd van zijn reis) == zijn prijs. 50==50 WAAR; 80==50 ONWAAR; ABSENT(leeg)==50 -> ONWAAR;
        // ABSENT + prijs null (both-leeg) -> WAAR (Tabel 37); present vertrektijd null (leeg)==50 -> ONWAAR.
        new Fixture("navigated GELIJK leegwaarde matrix (Tabel 37: both-leeg fires, one-leeg not)",
            NAV_EXPR_HDR + "Regel R\n    geldig altijd\n"
            + "        Een Passagier is duur indien de vertrektijd van zijn reis gelijk is aan zijn prijs.\n",
            () -> { Vlucht v50 = new Vlucht(); v50.setVertrektijd(new BigDecimal(50));
                    Vlucht v80 = new Vlucht(); v80.setVertrektijd(new BigDecimal(80));
                    Vlucht vNull = new Vlucht();
                    return List.of(v50, v80, vNull, paxNav(v50, null, 50), paxNav(v80, null, 50),
                                   paxNav(null, null, 50), paxNav(null, null, null), paxNav(vNull, null, 50)); },
            Map.of(),
            l -> vals(((Passagier) l.get(3)).isDuur(), ((Passagier) l.get(4)).isDuur(), ((Passagier) l.get(5)).isDuur(),
                      ((Passagier) l.get(6)).isDuur(), ((Passagier) l.get(7)).isDuur())),
        // GROTER (control: ordering stays onwaar on any leeg). 80>50 WAAR; 30>50 ONWAAR; ABSENT -> ONWAAR; both-leeg ->
        // ONWAAR; present-null-attr -> ONWAAR. Proves the fix did NOT flip ordering operators.
        new Fixture("navigated GROTER leegwaarde matrix (ordering: every leeg -> onwaar, unchanged)",
            NAV_EXPR_HDR + "Regel R\n    geldig altijd\n"
            + "        Een Passagier is duur indien de vertrektijd van zijn reis groter is dan zijn prijs.\n",
            () -> { Vlucht v80 = new Vlucht(); v80.setVertrektijd(new BigDecimal(80));
                    Vlucht v30 = new Vlucht(); v30.setVertrektijd(new BigDecimal(30));
                    Vlucht vNull = new Vlucht();
                    return List.of(v80, v30, vNull, paxNav(v80, null, 50), paxNav(v30, null, 50),
                                   paxNav(null, null, 50), paxNav(null, null, null), paxNav(vNull, null, 50)); },
            Map.of(),
            l -> vals(((Passagier) l.get(3)).isDuur(), ((Passagier) l.get(4)).isDuur(), ((Passagier) l.get(5)).isDuur(),
                      ((Passagier) l.get(6)).isDuur(), ((Passagier) l.get(7)).isDuur())),
        // §5.6 Tabel 44 over a navigated TEKST ONGELIJK (the SAME leegwaarde flip as numeric, on the String path): "de
        // roepnaam van zijn partner ongelijk is aan 'Maria'". Anna!=Maria WAAR; Maria!=Maria ONWAAR; ABSENT partner
        // (leeg)!=Maria -> WAAR (the fix — was a no-fire short-circuit); present partner with null roepnaam (leeg) ->
        // WAAR. Locks the Tekst surface with ABSOLUTE values too (was fixed by 3eb94d0 but only the numeric path had a
        // leegwaarde-matrix fixture — the Tekst flip could silently re-mask, differential-masks-shared-bugs).
        new Fixture("navigated TEKST ONGELIJK leegwaarde (Tabel 44: absent + present-null-attr fire WAAR)",
            """
            Objecttype de Persoon (mv: Personen) (bezield)
                de roepnaam Tekst;
                is gepensioneerd kenmerk (bijvoeglijk);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel R
                geldig altijd
                    Een Persoon is gepensioneerd indien de roepnaam van zijn partner ongelijk is aan "Maria".
            """,
            () -> { Persoon anna = new Persoon(); anna.setRoepnaam("Anna");
                    Persoon maria = new Persoon(); maria.setRoepnaam("Maria");
                    Persoon nullNaam = new Persoon();                              // partner present, roepnaam null
                    Persoon pAnna = new Persoon(); pAnna.setPartner(anna);
                    Persoon pMaria = new Persoon(); pMaria.setPartner(maria);
                    Persoon pGeen = new Persoon();                                 // no partner -> absent relation
                    Persoon pNull = new Persoon(); pNull.setPartner(nullNaam);
                    return List.of(pAnna, pMaria, pGeen, pNull, anna, maria, nullNaam); },
            Map.of(),
            l -> vals(((Persoon) l.get(0)).isGepensioneerd(), ((Persoon) l.get(1)).isGepensioneerd(),
                      ((Persoon) l.get(2)).isGepensioneerd(), ((Persoon) l.get(3)).isGepensioneerd())),
    };
    static final Object[] PHASE_NAV_LEEG_EXPECTED = {
        vals(true, false, true, false, true),    // ONGELIJK (numeric)
        vals(true, false, false, true, false),   // GELIJK (numeric)
        vals(true, false, false, false, false),  // GROTER (numeric, ordering control)
        vals(true, false, true, true),           // ONGELIJK (TEKST): Anna!=Maria, Maria!=Maria, absent!=Maria, null-attr!=Maria
    };

    // ---- §13.4.1/§7 MULTI-HOP to-one navigation chain ("de X van de Y van de Z"): the resolver re-interprets the
    //      greedy kaleNaam's trailing 'van'-delen as to-one role hops (navigatiePad, declared role names), and BOTH
    //      back-ends lower the consequence VALUE read as one null-guarded getter per hop — an intervening leeg
    //      relation (or leeg leaf attribute) reads as leeg, never an NPE / wrong write. Passagier -reis-> Vlucht
    //      -vliegtuig-> Vliegtuig (2 hops), Persoon -partner->^3 (3 hops, self-relation), and the §5.5.3 own-type
    //      back-reference start ("van de Vlucht", 1 hop after an EIGEN anchor). Chains in a CONDITION stay a
    //      symmetric marker in both back-ends (out of scope here). ----
    static final String KETEN_HDR =
            "Objecttype de Vliegtuig\n    de capaciteit Numeriek (geheel getal);\n"
            + "Objecttype de Vlucht\n    de vertrektijd Numeriek (geheel getal);\n"
            + "Objecttype de Passagier\n    het basistarief Numeriek (geheel getal);\n    de prijs Numeriek (geheel getal);\n"
            + AGG_FEIT
            + "Feittype toewijzing van vliegtuig\n    de vlucht Vlucht\n    het vliegtuig Vliegtuig\nEén vlucht heeft één vliegtuig\n";
    static final String KETEN_PARTNER_SRC = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal);
                de gekopieerd Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel drie hops
                geldig altijd
                    De gekopieerd van een Persoon moet berekend worden als de leeftijd van de partner van de partner van zijn partner.
            """;
    static Vlucht vluchtMet(Vliegtuig vt) { Vlucht v = new Vlucht(); v.setVliegtuig(vt); return v; }
    static final Fixture[] PHASE_KETEN = {
        // 2 hops, possessive anchor: prijs := capaciteit van het vliegtuig van zijn reis. Rows: present (180),
        // ABSENT root relation (leeg), ABSENT middle relation (leeg — THE chain corner), present chain w/ leeg
        // leaf attribute (leeg). A leeg result never writes (the changed-value guard), so prijs stays null.
        new Fixture("2-hop keten waarde (leeg root / leeg midden / leeg blad -> leeg)",
            KETEN_HDR + "Regel keten\n    geldig altijd\n"
            + "        De prijs van een Passagier moet berekend worden als de capaciteit van het vliegtuig van zijn reis.\n",
            () -> { Vliegtuig vt = new Vliegtuig(); vt.setCapaciteit(new BigDecimal(180));
                    Vliegtuig vtLeeg = new Vliegtuig();   // capaciteit null
                    Vlucht vA = vluchtMet(vt); Vlucht vB = vluchtMet(null); Vlucht vC = vluchtMet(vtLeeg);
                    return List.of(vt, vtLeeg, vA, vB, vC,
                                   paxNav(vA, null, null), paxNav(null, null, null), paxNav(vB, null, null), paxNav(vC, null, null)); },
            Map.of(),
            l -> vals(((Passagier) l.get(5)).getPrijs(), ((Passagier) l.get(6)).getPrijs(),
                      ((Passagier) l.get(7)).getPrijs(), ((Passagier) l.get(8)).getPrijs())),
        // The DEFINITE spelling of the same chain ("van de reis") resolves identically (§13.4.1.3 role reference).
        new Fixture("2-hop keten waarde, definiete spelling (van de reis)",
            KETEN_HDR + "Regel keten\n    geldig altijd\n"
            + "        De prijs van een Passagier moet berekend worden als de capaciteit van het vliegtuig van de reis.\n",
            () -> { Vliegtuig vt = new Vliegtuig(); vt.setCapaciteit(new BigDecimal(180));
                    Vlucht v = vluchtMet(vt);
                    return List.of(vt, v, paxNav(v, null, null)); },
            Map.of(),
            l -> ((Passagier) l.get(2)).getPrijs()),
        // A chain leg INSIDE value arithmetic: prijs := (capaciteit van het vliegtuig van zijn reis) plus basistarief.
        // Present: 180+20=200; a broken chain reads leeg -> §6.2 leeg telt als 0 -> 0+20=20.
        new Fixture("keten-leg in waarde-rekenkunde (leeg midden telt als 0 in +)",
            KETEN_HDR + "Regel keten plus\n    geldig altijd\n"
            + "        De prijs van een Passagier moet berekend worden als de capaciteit van het vliegtuig van zijn reis plus zijn basistarief.\n",
            () -> { Vliegtuig vt = new Vliegtuig(); vt.setCapaciteit(new BigDecimal(180));
                    Vlucht vA = vluchtMet(vt); Vlucht vB = vluchtMet(null);
                    return List.of(vt, vA, vB, paxNav(vA, 20, null), paxNav(vB, 20, null)); },
            Map.of(),
            l -> vals(((Passagier) l.get(3)).getPrijs(), ((Passagier) l.get(4)).getPrijs())),
        // 3 hops through a SELF-relation (partner van de partner van zijn partner): p1 -> p2 -> p3 -> p4 (40);
        // p2's chain ends at p4's absent partner -> leeg; p4 has no partner at all -> leeg.
        new Fixture("3-hop keten door zelfrelatie (partner^3)",
            KETEN_PARTNER_SRC,
            () -> { Persoon p4 = new Persoon(); p4.setLeeftijd(new BigDecimal(40));
                    Persoon p3 = new Persoon(); p3.setPartner(p4);
                    Persoon p2 = new Persoon(); p2.setPartner(p3);
                    Persoon p1 = new Persoon(); p1.setPartner(p2);
                    return List.of(p1, p2, p3, p4); },
            Map.of(),
            l -> vals(((Persoon) l.get(0)).getGekopieerd(), ((Persoon) l.get(1)).getGekopieerd(),
                      ((Persoon) l.get(3)).getGekopieerd())),
        // §5.5.3 own-type back-reference anchor: subject Vlucht, "van de Vlucht" = the subject itself, then ONE
        // hop (vliegtuig). Closes the documented "…van het vliegtuig van de Vlucht" residual for the VALUE read.
        new Fixture("keten na eigen-type terugverwijzing (van de Vlucht)",
            KETEN_HDR + "Regel eigen keten\n    geldig altijd\n"
            + "        De vertrektijd van een Vlucht moet berekend worden als de capaciteit van het vliegtuig van de Vlucht.\n",
            () -> { Vliegtuig vt = new Vliegtuig(); vt.setCapaciteit(new BigDecimal(180));
                    return List.of(vt, vluchtMet(vt), vluchtMet(null)); },
            Map.of(),
            l -> vals(((Vlucht) l.get(1)).getVertrektijd(), ((Vlucht) l.get(2)).getVertrektijd())),
        // §4 CROSS-UNIT chain leaf: the leaf carries mnd, the doel jr — the ordinary value-side EXACT restatement
        // (herschaalWaarde/omrekenWaarde) applies to the chain read like any value: 24 mnd -> 2 jr exactly.
        new Fixture("keten-blad met eenheid (24 mnd blad -> 2 jr doel, exacte herschaling)",
            "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\n"
            + "Objecttype de Vliegtuig\n    de capaciteit Numeriek (geheel getal) met eenheid mnd;\n"
            + "Objecttype de Vlucht\n    de vertrektijd Numeriek (geheel getal);\n"
            + "Objecttype de Passagier\n    het basistarief Numeriek (geheel getal);\n    de prijs Numeriek (geheel getal) met eenheid jr;\n"
            + AGG_FEIT
            + "Feittype toewijzing van vliegtuig\n    de vlucht Vlucht\n    het vliegtuig Vliegtuig\nEén vlucht heeft één vliegtuig\n"
            + "Regel keten eenheid\n    geldig altijd\n"
            + "        De prijs van een Passagier moet berekend worden als de capaciteit van het vliegtuig van zijn reis.\n",
            () -> { Vliegtuig vt = new Vliegtuig(); vt.setCapaciteit(new BigDecimal(24));
                    Vlucht v = vluchtMet(vt);
                    return List.of(vt, v, paxNav(v, null, null)); },
            Map.of(),
            l -> ((Passagier) l.get(2)).getPrijs()),
    };
    static final Object[] PHASE_KETEN_EXPECTED = {
        vals(new BigDecimal(180), null, null, null),
        new BigDecimal(180),
        vals(new BigDecimal(200), new BigDecimal(20)),
        vals(new BigDecimal(40), null, null),
        vals(new BigDecimal(180), null),
        new BigDecimal(2),
    };

    // ---- §13.4.6 objectcreatie attribute-INIT values through a navigation: the toekenning value is a consequence
    //      value evaluated against the ANCHOR $subject, so a single-hop navigated read AND a §13.4.1 chain lower in
    //      BOTH back-ends (the exec used to pass valueSubject=null there: every navigated init value skipped while
    //      DRL fired it — the chain work surfaced that asymmetry, review wf keten). ----
    static final ObjFixture[] PHASE_KETEN_OBJ = {
        new ObjFixture("§13.4.6 objectcreatie-init via een §13.4.1 keten (eigen-anker + 1 hop)",
            KETEN_HDR + "Regel maak\n    geldig altijd\n"
            + "        Een Vlucht heeft een passagier met prijs gelijk aan de capaciteit van het vliegtuig van zijn reis.\n",
            () -> { Vliegtuig vt = new Vliegtuig(); vt.setCapaciteit(new BigDecimal(77));
                    return List.<Object>of(vt, vluchtMet(vt)); },
            Map.of(),
            (o, facts) -> { Passagier p = (Passagier) o; return "Passagier|prijs=" + p.getPrijs().toPlainString()
                    + "|reis=" + refIndex(facts, p.getReis()); }),
            // -> ["Passagier|prijs=77|reis=1"]
        new ObjFixture("§13.4.6 objectcreatie-init via een ENKELE to-one navigatie (zijn vliegtuig)",
            KETEN_HDR + "Regel maak\n    geldig altijd\n"
            + "        Een Vlucht heeft een passagier met prijs gelijk aan de capaciteit van zijn vliegtuig.\n",
            () -> { Vliegtuig vt = new Vliegtuig(); vt.setCapaciteit(new BigDecimal(88));
                    return List.<Object>of(vt, vluchtMet(vt)); },
            Map.of(),
            (o, facts) -> { Passagier p = (Passagier) o; return "Passagier|prijs=" + p.getPrijs().toPlainString()
                    + "|reis=" + refIndex(facts, p.getReis()); }),
            // -> ["Passagier|prijs=88|reis=1"]
    };
    static final String[] PHASE_KETEN_OBJ_EXPECTED = { "Passagier|prijs=77|reis=1", "Passagier|prijs=88|reis=1" };

    // ---- §5.3/§5.6 DATUM EQUALITY (gelijk/ongelijk) of an OWN or to-one NAVIGATED datum attribute vs a datum literal OR
    //      another datum attribute. The exec had a datum ORDERING branch but no datum EQUALITY branch -> it SKIPPED these
    //      (DRL fired them correctly). Now both fire: an absent navigation / leeg datum reads LEEGWAARDE, then GELIJK uses
    //      §5.3 Tabel 38 (Anders: one/both-leeg -> onwaar — NOT Tabel 37's numeric both-leeg=WAAR) and ONGELIJK uses §5.6
    //      Tabel 44 (one-leeg -> WAAR, both-leeg -> onwaar). Vluchten + passengers; reis=null = absent; null attr = leeg. ----
    static final String DATUM_EQ_HDR =
            "Objecttype de Vlucht\n    de vluchtdatum Datum in dagen;\n"
            + "Objecttype de Passagier\n    de geboortedatum Datum in dagen;\n    is duur kenmerk (bijvoeglijk);\n" + AGG_FEIT;
    static Passagier paxGeb(Vlucht reis, LocalDate geb) { Passagier p = new Passagier(); p.setReis(reis); if (geb != null) p.setGeboortedatum(geb); return p; }
    static final Fixture[] PHASE_DATUM_EQ = {
        // NAVIGATED datum GELIJK vs literal 1-1-2020: [match, other(1999), ABSENT relation, present-null-attr] -> [T,F,F,F].
        new Fixture("navigated datum GELIJK vs literal (Tabel 38: only the present match fires)",
            DATUM_EQ_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien de vluchtdatum van zijn reis gelijk is aan 1-1-2020.\n",
            () -> { Vlucht vm = new Vlucht(); vm.setVluchtdatum(dat(2020, 1, 1));
                    Vlucht vo = new Vlucht(); vo.setVluchtdatum(dat(1999, 1, 1));
                    Vlucht vn = new Vlucht();   // vluchtdatum null
                    return List.of(vm, vo, vn, paxGeb(vm, null), paxGeb(vo, null), paxGeb(null, null), paxGeb(vn, null)); },
            Map.of(),
            l -> vals(((Passagier) l.get(3)).isDuur(), ((Passagier) l.get(4)).isDuur(),
                      ((Passagier) l.get(5)).isDuur(), ((Passagier) l.get(6)).isDuur())),
        // NAVIGATED datum ONGELIJK vs literal: [match, other, ABSENT, present-null-attr] -> [F,T,T,T] (Tabel 44 one-leeg WAAR).
        new Fixture("navigated datum ONGELIJK vs literal (Tabel 44: absent + present-null-attr fire)",
            DATUM_EQ_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien de vluchtdatum van zijn reis ongelijk is aan 1-1-2020.\n",
            () -> { Vlucht vm = new Vlucht(); vm.setVluchtdatum(dat(2020, 1, 1));
                    Vlucht vo = new Vlucht(); vo.setVluchtdatum(dat(1999, 1, 1));
                    Vlucht vn = new Vlucht();
                    return List.of(vm, vo, vn, paxGeb(vm, null), paxGeb(vo, null), paxGeb(null, null), paxGeb(vn, null)); },
            Map.of(),
            l -> vals(((Passagier) l.get(3)).isDuur(), ((Passagier) l.get(4)).isDuur(),
                      ((Passagier) l.get(5)).isDuur(), ((Passagier) l.get(6)).isDuur())),
        // NAVIGATED datum vs OWN datum, GELIJK ("de vluchtdatum van zijn reis gelijk is aan zijn geboortedatum"):
        // [both-equal, both-diff, absent-reis(lhs leeg), own-null(rhs leeg), both-leeg] -> [T,F,F,F,F] (Tabel 38).
        new Fixture("navigated datum vs own datum GELIJK (Tabel 38: only both-present-equal fires, both-leeg onwaar)",
            DATUM_EQ_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien de vluchtdatum van zijn reis gelijk is aan zijn geboortedatum.\n",
            () -> { Vlucht va = new Vlucht(); va.setVluchtdatum(dat(2020, 1, 1));
                    Vlucht vb = new Vlucht(); vb.setVluchtdatum(dat(2020, 1, 1));
                    return List.of(va, vb, paxGeb(va, dat(2020, 1, 1)), paxGeb(vb, dat(1999, 1, 1)),
                                   paxGeb(null, dat(2020, 1, 1)), paxGeb(va, null), paxGeb(null, null)); },
            Map.of(),
            l -> vals(((Passagier) l.get(2)).isDuur(), ((Passagier) l.get(3)).isDuur(), ((Passagier) l.get(4)).isDuur(),
                      ((Passagier) l.get(5)).isDuur(), ((Passagier) l.get(6)).isDuur())),
        // OWN datum ONGELIJK vs literal: [match, other, null] -> [F,T,T] (mvel null != lit is WAAR; Tabel 44 one-leeg).
        new Fixture("own datum ONGELIJK vs literal (null geboortedatum fires, Tabel 44)",
            "Objecttype de Passagier\n    de geboortedatum Datum in dagen;\n    is duur kenmerk (bijvoeglijk);\n"
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn geboortedatum ongelijk is aan 1-1-2020.\n",
            () -> List.of(passagier(p -> p.setGeboortedatum(dat(2020, 1, 1))),
                          passagier(p -> p.setGeboortedatum(dat(1999, 1, 1))), new Passagier()),
            Map.of(),
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
    };
    static final Object[] PHASE_DATUM_EQ_EXPECTED = {
        vals(true, false, false, false),         // NAV GELIJK vs lit
        vals(false, true, true, true),           // NAV ONGELIJK vs lit
        vals(true, false, false, false, false),  // NAV vs OWN GELIJK
        vals(false, true, true),                 // OWN ONGELIJK vs lit
    };
    // §5.3 Tabel 36 a Datum-in-dagen attribute vs a DATUM-EN-TIJD literal is a TYPE MISMATCH; parseDatum would lossily
    //   extract just the date part and fire. The exec datum-equality branch requires a DatumInDagen literal RHS, so this
    //   skips (honest) instead of mis-firing. (DRL fires it — a pre-existing resolver type-check gap, now masked.)
    static final String MIXED_DATUMLIT_SRC =
        "Objecttype de Vlucht\n    de vluchtdatum Datum in dagen;\n    is laat kenmerk (bijvoeglijk);\n"
        + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vluchtdatum gelijk is aan 15-6-2022 09:30:00.000.\n";

    // ---- §5.3/§5.6/§8.1.1 Datum-en-tijd (LocalDateTime) comparisons now FIRE in the exec (ordering EERDER/LATER +
    //      equality GELIJK/ONGELIJK, own + navigated, vs a datum-tijd literal + a same-kind attr). Previously a present
    //      Datum-en-tijd was read via `instanceof LocalDate` -> leeg: equality was a LocalDate-only honest SKIP, while
    //      ordering & consistentie MISFIRED (read present as leeg -> a wrong no-fire / a wrong consistentie over-flag).
    //      The exec now reads the raw Temporal and compares via datumVergelijkTemporal; DRL fires all these. A mixed
    //      Datum-in-dagen/Datum-en-tijd pair stays an honest skip (§5.3 Tabel 36 type mismatch). Absolute spec values. ----
    static LocalDateTime dtt(int h, int mi) { return LocalDateTime.of(2022, 6, 15, h, mi, 0); }
    static final String DT_HDR =
        "Objecttype de Vlucht\n    de vertrekmoment Datum en tijd in millisecondes;\n    de aankomstmoment Datum en tijd in millisecondes;\n    is laat kenmerk (bijvoeglijk);\n";
    static final Fixture[] PHASE_DATUMTIJD = {
        // own-attr ORDERING vs a datum-tijd literal: vertrekmoment eerder dan 12:00 -> [09:30 WAAR, 13:00 onwaar, leeg onwaar].
        new Fixture("datum-tijd own ordering vs literal (eerder dan 12:00)",
            DT_HDR + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vertrekmoment eerder is dan 15-6-2022 12:00:00.000.\n",
            () -> { Vlucht a = new Vlucht(); a.setVertrekmoment(dtt(9, 30));
                    Vlucht b = new Vlucht(); b.setVertrekmoment(dtt(13, 0));
                    Vlucht c = new Vlucht();   // leeg -> ordering no-match
                    return List.of(a, b, c); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
        // own-attr GELIJK vs literal: vertrekmoment == 09:30 -> [09:30 WAAR, 10:00 onwaar, leeg onwaar (Tabel 38)].
        new Fixture("datum-tijd own GELIJK vs literal (== 09:30)",
            DT_HDR + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vertrekmoment gelijk is aan 15-6-2022 09:30:00.000.\n",
            () -> { Vlucht a = new Vlucht(); a.setVertrekmoment(dtt(9, 30));
                    Vlucht b = new Vlucht(); b.setVertrekmoment(dtt(10, 0));
                    Vlucht c = new Vlucht();
                    return List.of(a, b, c); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
        // own-attr ONGELIJK vs literal: vertrekmoment != 09:30 -> [10:00 WAAR, 09:30 onwaar, leeg WAAR (Tabel 44 one-leeg)].
        new Fixture("datum-tijd own ONGELIJK vs literal (!= 09:30)",
            DT_HDR + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vertrekmoment ongelijk is aan 15-6-2022 09:30:00.000.\n",
            () -> { Vlucht a = new Vlucht(); a.setVertrekmoment(dtt(10, 0));
                    Vlucht b = new Vlucht(); b.setVertrekmoment(dtt(9, 30));
                    Vlucht c = new Vlucht();
                    return List.of(a, b, c); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
        // attr-vs-attr GELIJK: vertrekmoment == aankomstmoment -> [equal WAAR, unequal onwaar, one-leeg onwaar, both-leeg onwaar].
        new Fixture("datum-tijd attr-vs-attr GELIJK (vertrek == aankomst)",
            DT_HDR + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vertrekmoment gelijk is aan zijn aankomstmoment.\n",
            () -> { Vlucht eq = new Vlucht(); eq.setVertrekmoment(dtt(9, 30)); eq.setAankomstmoment(dtt(9, 30));
                    Vlucht ne = new Vlucht(); ne.setVertrekmoment(dtt(9, 30)); ne.setAankomstmoment(dtt(10, 0));
                    Vlucht one = new Vlucht(); one.setVertrekmoment(dtt(9, 30));
                    Vlucht both = new Vlucht();
                    return List.of(eq, ne, one, both); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat(), ((Vlucht) l.get(3)).isLaat())),
        // NAVIGATED ordering vs literal: de vertrekmoment van zijn reis eerder dan 12:00 -> [reis 09:30 WAAR, absent-reis onwaar (leeg)].
        new Fixture("datum-tijd navigated ordering vs literal (reis vertrekmoment eerder dan 12:00)",
            "Objecttype de Vlucht\n    de vertrekmoment Datum en tijd in millisecondes;\n"
            + "Objecttype de Passagier\n    is duur kenmerk (bijvoeglijk);\n" + AGG_FEIT
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien de vertrekmoment van zijn reis eerder is dan 15-6-2022 12:00:00.000.\n",
            () -> { Vlucht v = new Vlucht(); v.setVertrekmoment(dtt(9, 30));
                    Passagier p1 = new Passagier(); p1.setReis(v);
                    Passagier p2 = new Passagier();   // absent reis -> leegwaarde -> ordering no-match
                    return List.of(v, p1, p2); },
            Map.of(),
            l -> vals(((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
    };
    static final Object[] PHASE_DATUMTIJD_EXPECTED = {
        vals(true, false, false),         // own ordering (09:30<12:00 WAAR; 13:00 onwaar; leeg onwaar)
        vals(true, false, false),         // own GELIJK
        vals(true, false, true),          // own ONGELIJK (leeg -> WAAR, Tabel 44)
        vals(true, false, false, false),  // attr-vs-attr GELIJK
        vals(true, false),                // navigated ordering (present WAAR, absent-reis onwaar)
    };
    // §13.4.8 a CONSISTENTIE ordering assertie over Datum-en-tijd ("De vertrekmoment moet eerder zijn dan de aankomstmoment"):
    //   the exec used to over-flag a SATISFIED ordering (a present LocalDateTime read as leeg -> "operand empty -> violation").
    //   Now flags exactly the genuine violation, matching DRL. (satisfied -> no fout; reversed -> fout; one-leeg -> fout.)
    static final FoutFixture[] PHASE_DATUMTIJD_CONS = {
        new FoutFixture("datum-tijd consistentie ordering (vertrek eerder dan aankomst)",
            "Objecttype de Vlucht\n    de vertrekmoment Datum en tijd in millisecondes;\n    de aankomstmoment Datum en tijd in millisecondes;\n"
            + "Regel R\n    geldig altijd\n        De vertrekmoment van een Vlucht moet eerder zijn dan zijn aankomstmoment.\n",
            () -> { Vlucht ok = new Vlucht(); ok.setVertrekmoment(dtt(9, 30)); ok.setAankomstmoment(dtt(10, 0));   // satisfied -> NOT flagged
                    Vlucht bad = new Vlucht(); bad.setVertrekmoment(dtt(11, 0)); bad.setAankomstmoment(dtt(9, 30));  // reversed -> flagged
                    Vlucht leeg = new Vlucht(); leeg.setVertrekmoment(dtt(9, 30));                                   // aankomst leeg -> flagged
                    return List.of(ok, bad, leeg); },
            o -> "vertrek=" + ((Vlucht) o).getVertrekmoment()),
    };

    // §8.1.1/§5.1 attr-vs-attr DATUM ORDERING (eerder/later, own attr-vs-attr, both Datum-in-dagen + Datum-en-tijd) now
    //   fires in the exec — was a DRL-fires/exec-skips (the exec ordering branch matched only a LITERAL RHS). DRL lowers
    //   `a < b`; the exec reads both via datumTemporeelLezer + datumVergelijkTemporal. A leeg operand -> ordering no-match.
    static final String DID_ORDER_HDR =
        "Objecttype de Vlucht\n    de vluchtdatum Datum in dagen;\n    de daadwerkelijk vertrek Datum in dagen;\n    is laat kenmerk (bijvoeglijk);\n";
    static final Fixture[] PHASE_DATUM_ORDER = {
        // Datum-in-dagen: vluchtdatum eerder dan daadwerkelijk vertrek -> [before WAAR, after onwaar, equal onwaar, left-leeg onwaar, right-leeg onwaar].
        new Fixture("datum-in-dagen attr-vs-attr ordering (vluchtdatum eerder dan daadwerkelijk vertrek)",
            DID_ORDER_HDR + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vluchtdatum eerder is dan zijn daadwerkelijk vertrek.\n",
            () -> { Vlucht bf = new Vlucht(); bf.setVluchtdatum(dat(2024, 1, 1)); bf.setDaadwerkelijkVertrek(dat(2024, 1, 2));
                    Vlucht af = new Vlucht(); af.setVluchtdatum(dat(2024, 1, 2)); af.setDaadwerkelijkVertrek(dat(2024, 1, 1));
                    Vlucht eqv = new Vlucht(); eqv.setVluchtdatum(dat(2024, 1, 1)); eqv.setDaadwerkelijkVertrek(dat(2024, 1, 1));
                    Vlucht ll = new Vlucht(); ll.setDaadwerkelijkVertrek(dat(2024, 1, 2));   // left leeg -> no-match
                    Vlucht rl = new Vlucht(); rl.setVluchtdatum(dat(2024, 1, 1));            // right leeg -> no-match
                    return List.of(bf, af, eqv, ll, rl); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat(),
                      ((Vlucht) l.get(3)).isLaat(), ((Vlucht) l.get(4)).isLaat())),
        // Datum-en-tijd: vertrekmoment eerder dan aankomstmoment -> [before WAAR, equal onwaar, right-leeg onwaar].
        new Fixture("datum-en-tijd attr-vs-attr ordering (vertrekmoment eerder dan aankomstmoment)",
            DT_HDR + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vertrekmoment eerder is dan zijn aankomstmoment.\n",
            () -> { Vlucht bf = new Vlucht(); bf.setVertrekmoment(dtt(9, 30)); bf.setAankomstmoment(dtt(10, 0));
                    Vlucht eqv = new Vlucht(); eqv.setVertrekmoment(dtt(9, 30)); eqv.setAankomstmoment(dtt(9, 30));
                    Vlucht rl = new Vlucht(); rl.setVertrekmoment(dtt(9, 30));   // aankomst leeg -> no-match
                    return List.of(bf, eqv, rl); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
    };
    static final Object[] PHASE_DATUM_ORDER_EXPECTED = {
        vals(true, false, false, false, false),   // Datum-in-dagen
        vals(true, false, false),                 // Datum-en-tijd
    };

    // §8.1.1 a comparison with the CONSTANT on the LEFT ("100 groter is dan zijn vertrektijd", "1-1-2020 eerder is dan
    //   zijn vluchtdatum") now fires in the exec — was a DRL-fires/exec-skips (every leaf branch keyed on an attr/expr
    //   LHS). leafPredicate normalises a literal-LHS comparison by swapping operands + reversing the operator (omkeren),
    //   then the existing attr-LHS branches lower it. Numeric + datum, ordering + equality, incl. a leeg operand.
    static final Fixture[] PHASE_LIT_LEFT = {
        // numeric literal-LHS ordering: 100 groter dan vertrektijd -> [50 WAAR, 150 onwaar, leeg onwaar].
        new Fixture("numeric literal-LHS ordering (100 groter dan vertrektijd)",
            "Objecttype de Vlucht\n    de vertrektijd Numeriek (geheel getal);\n    is laat kenmerk (bijvoeglijk);\n"
            + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien 100 groter is dan zijn vertrektijd.\n",
            () -> { Vlucht a = new Vlucht(); a.setVertrektijd(new BigDecimal(50));
                    Vlucht b = new Vlucht(); b.setVertrektijd(new BigDecimal(150));
                    Vlucht c = new Vlucht();
                    return List.of(a, b, c); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
        // datum literal-LHS ordering: 1-1-2020 eerder dan vluchtdatum -> [2021 WAAR, 2019 onwaar, leeg onwaar].
        new Fixture("datum literal-LHS ordering (1-1-2020 eerder dan vluchtdatum)",
            "Objecttype de Vlucht\n    de vluchtdatum Datum in dagen;\n    is laat kenmerk (bijvoeglijk);\n"
            + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien 1-1-2020 eerder is dan zijn vluchtdatum.\n",
            () -> { Vlucht a = new Vlucht(); a.setVluchtdatum(dat(2021, 1, 1));
                    Vlucht b = new Vlucht(); b.setVluchtdatum(dat(2019, 1, 1));
                    Vlucht c = new Vlucht();
                    return List.of(a, b, c); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
        // datum literal-LHS GELIJK: 1-1-2021 gelijk aan vluchtdatum -> [2021 WAAR, 2020 onwaar, leeg onwaar (Tabel 38)].
        new Fixture("datum literal-LHS GELIJK (1-1-2021 gelijk aan vluchtdatum)",
            "Objecttype de Vlucht\n    de vluchtdatum Datum in dagen;\n    is laat kenmerk (bijvoeglijk);\n"
            + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien 1-1-2021 gelijk is aan zijn vluchtdatum.\n",
            () -> { Vlucht a = new Vlucht(); a.setVluchtdatum(dat(2021, 1, 1));
                    Vlucht b = new Vlucht(); b.setVluchtdatum(dat(2020, 1, 1));
                    Vlucht c = new Vlucht();
                    return List.of(a, b, c); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
        // datum literal-LHS ONGELIJK: 1-1-2021 ongelijk aan vluchtdatum -> [2020 WAAR, 2021 onwaar, leeg WAAR (Tabel 44 one-leeg)].
        new Fixture("datum literal-LHS ONGELIJK (1-1-2021 ongelijk aan vluchtdatum)",
            "Objecttype de Vlucht\n    de vluchtdatum Datum in dagen;\n    is laat kenmerk (bijvoeglijk);\n"
            + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien 1-1-2021 ongelijk is aan zijn vluchtdatum.\n",
            () -> { Vlucht a = new Vlucht(); a.setVluchtdatum(dat(2020, 1, 1));
                    Vlucht b = new Vlucht(); b.setVluchtdatum(dat(2021, 1, 1));
                    Vlucht c = new Vlucht();
                    return List.of(a, b, c); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
    };
    static final Object[] PHASE_LIT_LEFT_EXPECTED = {
        vals(true, false, false),   // numeric ordering (100>50 T; 100>150 F; 100>leeg F)
        vals(true, false, false),   // datum ordering (2020<2021 T; 2020<2019 F; leeg F)
        vals(true, false, false),   // datum GELIJK (match T; mismatch F; leeg F)
        vals(true, false, true),    // datum ONGELIJK (mismatch T; match F; leeg WAAR)
    };

    // §8.1.1/§13.3.8 a DATUM comparison vs a Datum-in-dagen Parameter-GLOBAL ("zijn geboortedatum eerder is dan de
    //   PEILDATUM", own + navigated, ordering + equality) now fires in the exec — was a DRL-fires/exec-skips (the
    //   numeric vs-global branch swallowed the datum op with `!isNumeriekOp -> return null`; no datum vs-global branch).
    //   The exec tracks the Datum-in-dagen parameter as a LocalDate global and binds it (datumVergelijkTemporal).
    static final Map<String, Object> PEIL = Map.of("peildatum", LocalDate.of(2000, 1, 1));
    static final String DG_OWN_HDR =
        "Parameter de PEILDATUM : Datum in dagen\nObjecttype de Passagier\n    de geboortedatum Datum in dagen;\n    is duur kenmerk (bijvoeglijk);\n";
    static Passagier passGeb(LocalDate d) { Passagier p = new Passagier(); if (d != null) p.setGeboortedatum(d); return p; }
    static final Fixture[] PHASE_DATUM_GLOBAL = {
        // own-attr ordering vs global PEILDATUM=2000: geboortedatum eerder dan PEILDATUM -> [1999 WAAR, 2001 onwaar, leeg onwaar].
        new Fixture("datum own ordering vs Parameter global (geboortedatum eerder dan PEILDATUM)",
            DG_OWN_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn geboortedatum eerder is dan de PEILDATUM.\n",
            () -> List.of(passGeb(dat(1999, 1, 1)), passGeb(dat(2001, 1, 1)), passGeb(null)), PEIL,
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
        // own-attr GELIJK vs global: geboortedatum gelijk PEILDATUM -> [2000 WAAR, 2001 onwaar, leeg onwaar (Tabel 38)].
        new Fixture("datum own GELIJK vs Parameter global (geboortedatum gelijk PEILDATUM)",
            DG_OWN_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn geboortedatum gelijk is aan de PEILDATUM.\n",
            () -> List.of(passGeb(dat(2000, 1, 1)), passGeb(dat(2001, 1, 1)), passGeb(null)), PEIL,
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
        // own-attr ONGELIJK vs global: geboortedatum ongelijk PEILDATUM -> [1999 WAAR, 2000 onwaar, leeg WAAR (Tabel 44)].
        new Fixture("datum own ONGELIJK vs Parameter global (geboortedatum ongelijk PEILDATUM)",
            DG_OWN_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn geboortedatum ongelijk is aan de PEILDATUM.\n",
            () -> List.of(passGeb(dat(1999, 1, 1)), passGeb(dat(2000, 1, 1)), passGeb(null)), PEIL,
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
        // NAVIGATED ordering vs global: de vluchtdatum van zijn reis eerder dan PEILDATUM -> [reis 1999 WAAR, absent-reis onwaar].
        new Fixture("datum navigated ordering vs Parameter global (vluchtdatum van zijn reis eerder dan PEILDATUM)",
            "Parameter de PEILDATUM : Datum in dagen\nObjecttype de Vlucht\n    de vluchtdatum Datum in dagen;\n"
            + "Objecttype de Passagier\n    is duur kenmerk (bijvoeglijk);\n" + AGG_FEIT
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien de vluchtdatum van zijn reis eerder is dan de PEILDATUM.\n",
            () -> { Vlucht v = new Vlucht(); v.setVluchtdatum(dat(1999, 1, 1));
                    Passagier p1 = new Passagier(); p1.setReis(v);
                    Passagier p2 = new Passagier();   // absent reis -> leeg -> ordering no-match
                    return List.of(v, p1, p2); }, PEIL,
            l -> vals(((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
    };
    static final Object[] PHASE_DATUM_GLOBAL_EXPECTED = {
        vals(true, false, false),   // own ordering
        vals(true, false, false),   // own GELIJK
        vals(true, false, true),    // own ONGELIJK (leeg -> WAAR, Tabel 44)
        vals(true, false),          // navigated ordering
    };

    // §5.3/§13.3.8 a TEKST equality vs a Tekst Parameter-GLOBAL ("zijn vertrek gelijk is aan de STANDAARDVERTREK") now
    //   fires in the exec — was a DRL-fires/exec-skips (a Tekst parameter was registered untracked; the numeric vs-global
    //   branch swallowed the op via `globals.get == null -> return null`). The exec tracks the Tekst parameter as a String
    //   global and binds it (tekstVergelijk: GELIJK Tabel 38, ONGELIJK Tabel 44 — null-asymmetric like DRL mvel).
    static final Map<String, Object> STDV = Map.of("standaardvertrek", "A");
    static final String TG_HDR =
        "Parameter de STANDAARDVERTREK : Tekst\nObjecttype de Route\n    de vertrek Tekst;\n    is gemarkeerd kenmerk (bijvoeglijk);\n";
    static Route routeVertrek(String v) { Route r = new Route(); if (v != null) r.setVertrek(v); return r; }
    static final Fixture[] PHASE_TEKST_GLOBAL = {
        // GELIJK vs global "A": vertrek == "A" -> ["A" WAAR, "B" onwaar, leeg onwaar (Tabel 38)].
        new Fixture("tekst own GELIJK vs Parameter global (vertrek gelijk STANDAARDVERTREK)",
            TG_HDR + "Regel R\n    geldig altijd\n        Een Route is gemarkeerd indien zijn vertrek gelijk is aan de STANDAARDVERTREK.\n",
            () -> List.of(routeVertrek("A"), routeVertrek("B"), routeVertrek(null)), STDV,
            l -> vals(((Route) l.get(0)).isGemarkeerd(), ((Route) l.get(1)).isGemarkeerd(), ((Route) l.get(2)).isGemarkeerd())),
        // ONGELIJK vs global "A": vertrek != "A" -> ["B" WAAR, "A" onwaar, leeg WAAR (Tabel 44 one-leeg)].
        new Fixture("tekst own ONGELIJK vs Parameter global (vertrek ongelijk STANDAARDVERTREK)",
            TG_HDR + "Regel R\n    geldig altijd\n        Een Route is gemarkeerd indien zijn vertrek ongelijk is aan de STANDAARDVERTREK.\n",
            () -> List.of(routeVertrek("B"), routeVertrek("A"), routeVertrek(null)), STDV,
            l -> vals(((Route) l.get(0)).isGemarkeerd(), ((Route) l.get(1)).isGemarkeerd(), ((Route) l.get(2)).isGemarkeerd())),
    };
    static final Object[] PHASE_TEKST_GLOBAL_EXPECTED = {
        vals(true, false, false),   // GELIJK (match T; mismatch F; leeg F)
        vals(true, false, true),    // ONGELIJK (mismatch T; match F; leeg WAAR)
    };

    // §8.1.1 a comparison with the Parameter-GLOBAL on the LEFT ("de DREMPEL groter is dan zijn prijs", "de PEILDATUM
    //   later is dan zijn geboortedatum", "de STANDAARDVERTREK ongelijk is aan zijn vertrek") now fires in the exec — was
    //   a DRL-fires/exec-skips (the vs-global branches keyed on an attr LHS). voorwaarde() normalises a ParameterRef-LHS
    //   comparison by swapping operands + reversing the operator (omkeren). Numeric + datum + tekst, all leegwaarde corners.
    static Passagier passPrijs(String p) { Passagier x = new Passagier(); if (p != null) x.setPrijs(new BigDecimal(p)); return x; }
    static final Fixture[] PHASE_GLOBAL_LEFT = {
        // numeric global-LHS ordering: DREMPEL(100) groter dan prijs -> [50 WAAR, 150 onwaar, leeg onwaar].
        new Fixture("numeric global-LHS ordering (DREMPEL groter dan prijs)",
            "Parameter de DREMPEL : Numeriek (geheel getal)\nObjecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    is duur kenmerk (bijvoeglijk);\n"
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien de DREMPEL groter is dan zijn prijs.\n",
            () -> List.of(passPrijs("50"), passPrijs("150"), passPrijs(null)), Map.of("drempel", new BigDecimal(100)),
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
        // datum global-LHS ordering: PEILDATUM(2000) later dan geboortedatum -> [1999 WAAR, 2001 onwaar, leeg onwaar].
        new Fixture("datum global-LHS ordering (PEILDATUM later dan geboortedatum)",
            "Parameter de PEILDATUM : Datum in dagen\nObjecttype de Passagier\n    de geboortedatum Datum in dagen;\n    is duur kenmerk (bijvoeglijk);\n"
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien de PEILDATUM later is dan zijn geboortedatum.\n",
            () -> List.of(passGeb(dat(1999, 1, 1)), passGeb(dat(2001, 1, 1)), passGeb(null)), PEIL,
            l -> vals(((Passagier) l.get(0)).isDuur(), ((Passagier) l.get(1)).isDuur(), ((Passagier) l.get(2)).isDuur())),
        // tekst global-LHS ONGELIJK: STANDAARDVERTREK("A") ongelijk aan vertrek -> ["B" WAAR, "A" onwaar, leeg WAAR (Tabel 44)].
        new Fixture("tekst global-LHS ONGELIJK (STANDAARDVERTREK ongelijk aan vertrek)",
            TG_HDR + "Regel R\n    geldig altijd\n        Een Route is gemarkeerd indien de STANDAARDVERTREK ongelijk is aan zijn vertrek.\n",
            () -> List.of(routeVertrek("B"), routeVertrek("A"), routeVertrek(null)), STDV,
            l -> vals(((Route) l.get(0)).isGemarkeerd(), ((Route) l.get(1)).isGemarkeerd(), ((Route) l.get(2)).isGemarkeerd())),
    };
    static final Object[] PHASE_GLOBAL_LEFT_EXPECTED = {
        vals(true, false, false),   // numeric (100>50 T; 100>150 F; 100>leeg F)
        vals(true, false, false),   // datum (2000>1999 T; 2000>2001 F; leeg F)
        vals(true, false, true),    // tekst ONGELIJK (A!=B T; A!=A F; A!=leeg WAAR)
    };

    // §8.1.1/§13.3.8 a DATUM-EN-TIJD comparison vs a Datum-en-tijd Parameter-GLOBAL ("zijn vertrekmoment eerder is dan
    //   de PEILMOMENT") now fires in the exec — was a DRL-fires/exec-skips (a Datum-en-tijd parameter was registered
    //   untracked). The exec tracks it as a LocalDateTime global (datumTijdGlobals) and binds it (datumVergelijkTemporal).
    static final Map<String, Object> PEILM = Map.of("peilmoment", LocalDateTime.of(2022, 6, 15, 12, 0, 0));
    static final String DTG_HDR =
        "Parameter de PEILMOMENT : Datum en tijd in millisecondes\nObjecttype de Vlucht\n    de vertrekmoment Datum en tijd in millisecondes;\n    is laat kenmerk (bijvoeglijk);\n";
    static Vlucht vluchtMoment(LocalDateTime vm) { Vlucht v = new Vlucht(); if (vm != null) v.setVertrekmoment(vm); return v; }
    static final Fixture[] PHASE_DATUMTIJD_GLOBAL = {
        // ordering vs PEILMOMENT=12:00: vertrekmoment eerder dan PEILMOMENT -> [09:30 WAAR, 13:00 onwaar, leeg onwaar].
        new Fixture("datum-tijd ordering vs Parameter global (vertrekmoment eerder dan PEILMOMENT)",
            DTG_HDR + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vertrekmoment eerder is dan de PEILMOMENT.\n",
            () -> List.of(vluchtMoment(dtt(9, 30)), vluchtMoment(dtt(13, 0)), vluchtMoment(null)), PEILM,
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
        // GELIJK vs PEILMOMENT=12:00 -> [12:00 WAAR, 13:00 onwaar, leeg onwaar (Tabel 38)].
        new Fixture("datum-tijd GELIJK vs Parameter global (vertrekmoment gelijk PEILMOMENT)",
            DTG_HDR + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vertrekmoment gelijk is aan de PEILMOMENT.\n",
            () -> List.of(vluchtMoment(dtt(12, 0)), vluchtMoment(dtt(13, 0)), vluchtMoment(null)), PEILM,
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
        // ONGELIJK vs PEILMOMENT=12:00 -> [13:00 WAAR, 12:00 onwaar, leeg WAAR (Tabel 44 one-leeg)].
        new Fixture("datum-tijd ONGELIJK vs Parameter global (vertrekmoment ongelijk PEILMOMENT)",
            DTG_HDR + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn vertrekmoment ongelijk is aan de PEILMOMENT.\n",
            () -> List.of(vluchtMoment(dtt(13, 0)), vluchtMoment(dtt(12, 0)), vluchtMoment(null)), PEILM,
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(), ((Vlucht) l.get(2)).isLaat())),
    };
    static final Object[] PHASE_DATUMTIJD_GLOBAL_EXPECTED = {
        vals(true, false, false),   // ordering
        vals(true, false, false),   // GELIJK
        vals(true, false, true),    // ONGELIJK (leeg -> WAAR, Tabel 44)
    };

    // §5.6 Tabel 44 a numeric to-ONE NAVIGATED ONGELIJK vs a Parameter-GLOBAL with an ABSENT relation must be WAAR (the
    //   navigated attr reads leeg, §5.5.5; leeg != 50 = WAAR). The exec nav-vs-global branch used to short-circuit
    //   rel==null -> false for ALL operators (wrong for ONGELIJK) — a DRL-fires/exec-WRONG that the global-on-LEFT
    //   normalisation widened. Now reads the leg leeg -> vergelijk (leegwaarde tables). Both spellings, present + absent.
    static final String NAVG_HDR =
        "Parameter de DREMPEL : Numeriek (geheel getal)\nObjecttype de Vlucht\n    de vertrektijd Numeriek (geheel getal);\n"
        + "Objecttype de Passagier\n    is duur kenmerk (bijvoeglijk);\n" + AGG_FEIT;
    static java.util.function.Supplier<List<Object>> navOngFacts() {
        return () -> { Vlucht v99 = new Vlucht(); v99.setVertrektijd(new BigDecimal(99));
                       Vlucht v50 = new Vlucht(); v50.setVertrektijd(new BigDecimal(50));
                       Passagier pNo = new Passagier();                                   // absent reis -> leeg != 50 -> WAAR
                       Passagier p99 = new Passagier(); p99.setReis(v99);                 // 99 != 50 -> WAAR
                       Passagier p50 = new Passagier(); p50.setReis(v50);                 // 50 != 50 -> onwaar
                       return List.of(v99, v50, pNo, p99, p50); };
    }
    static final Map<String, Object> DREMP50 = Map.of("drempel", new BigDecimal(50));
    static final Fixture[] PHASE_NAV_GLOBAL_ONGELIJK = {
        new Fixture("navigated ONGELIJK vs global, RIGHT spelling (vertrektijd van zijn reis ongelijk aan DREMPEL)",
            NAVG_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien de vertrektijd van zijn reis ongelijk is aan de DREMPEL.\n",
            navOngFacts(), DREMP50,
            l -> vals(((Passagier) l.get(2)).isDuur(), ((Passagier) l.get(3)).isDuur(), ((Passagier) l.get(4)).isDuur())),
        new Fixture("navigated ONGELIJK vs global, LEFT spelling (DREMPEL ongelijk aan vertrektijd van zijn reis)",
            NAVG_HDR + "Regel R\n    geldig altijd\n        Een Passagier is duur indien de DREMPEL ongelijk is aan de vertrektijd van zijn reis.\n",
            navOngFacts(), DREMP50,
            l -> vals(((Passagier) l.get(2)).isDuur(), ((Passagier) l.get(3)).isDuur(), ((Passagier) l.get(4)).isDuur())),
    };
    static final Object[] PHASE_NAV_GLOBAL_ONGELIJK_EXPECTED = {
        vals(true, true, false),    // [absent-reis WAAR (Tabel 44 one-leeg), 99!=50 WAAR, 50!=50 onwaar]
        vals(true, true, false),    // global-on-LEFT — same spec values via omkeren(ONGELIJK)=ONGELIJK
    };

    // regression: the exec model INFINITE-LOOPED when a NAVIGATED-comparison rule (A) was co-resident with a second rule
    //   (B) over the same subject — drools.update(s) re-evaluated A's black-box navigated lambda on every modify by B, and
    //   NO_LOOP blocks only the rule's OWN re-trigger. The idempotent werkBij (update only on an ACTUAL value change)
    //   reaches the fixpoint. A purely numeric navigated comparison triggers it too (not datum-specific).
    static final String HANG_SRC =
        "Objecttype de Vlucht\n    de vertrektijd Numeriek (geheel getal);\n"
        + "Objecttype de Passagier\n    de leeftijd Numeriek (geheel getal);\n    is duur kenmerk (bijvoeglijk);\n    is kind kenmerk (bijvoeglijk);\n"
        + AGG_FEIT
        + "Regel A\n    geldig altijd\n        Een Passagier is duur indien de vertrektijd van zijn reis groter is dan 5.\n"
        + "Regel B\n    geldig altijd\n        Een Passagier is kind indien zijn leeftijd kleiner is dan 18.\n";

    // ---- §5.6 a DATE-ARITHMETIC datum ONGELIJK ("zijn daadwerkelijk vertrek ongelijk is aan zijn vluchtdatum min 1 dag")
    //      routes through datumVergelijkTemporal. Its BOTH-leeg case wrongly fired model=true (an old `|| (a==null &&
    //      b==null)` clause) where DRL (mvel `null != null`) and Tabel 44 give onwaar -> a live divergence. Now both onwaar. ----
    static final String DATEARITH_EQ_SRC =
        "Objecttype de Vlucht\n    de vluchtdatum Datum in dagen;\n    de daadwerkelijk vertrek Datum in dagen;\n    is laat kenmerk (bijvoeglijk);\n";
    static final Fixture[] PHASE_DATEARITH_EQ = {
        // date-arith ONGELIJK: dv != (vluchtdatum - 1 dag). [both-leeg, present-equal(dv==vd-1), present-mismatch, one-leeg]
        // -> [F (Tabel 44 both-leeg onwaar), F (equal), T (mismatch), T (one-leeg WAAR)].
        new Fixture("date-arith datum ONGELIJK leegwaarde (Tabel 44: both-leeg onwaar, one-leeg WAAR)",
            DATEARITH_EQ_SRC + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien zijn daadwerkelijk vertrek ongelijk is aan zijn vluchtdatum min 1 dag.\n",
            () -> { Vlucht both = new Vlucht();   // both leeg
                    Vlucht eq = new Vlucht(); eq.setVluchtdatum(dat(2020, 1, 2)); eq.setDaadwerkelijkVertrek(dat(2020, 1, 1));   // dv == vd-1
                    Vlucht ne = new Vlucht(); ne.setVluchtdatum(dat(2020, 1, 2)); ne.setDaadwerkelijkVertrek(dat(2020, 1, 5));   // mismatch
                    Vlucht one = new Vlucht(); one.setDaadwerkelijkVertrek(dat(2020, 1, 1));   // vluchtdatum leeg
                    return List.of(both, eq, ne, one); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).isLaat(), ((Vlucht) l.get(1)).isLaat(),
                      ((Vlucht) l.get(2)).isLaat(), ((Vlucht) l.get(3)).isLaat())),
    };
    static final Object[] PHASE_DATEARITH_EQ_EXPECTED = { vals(false, false, true, true) };

    // §5.8 a bare SET operand ("de prijs van ALLE passagiers") used as a SCALAR inside arithmetic in a CONDITION is an
    //   instance-AGGREGATION source the exec does not lower to a scalar — it must SKIP (symmetric with DRL's marker), NOT
    //   read `prijs` off the subject Vlucht (which has no such getter) and CRASH the whole session. Rule S is a co-resident
    //   valid rule whose firing proves the SET rule's skip does not take the session down. ----
    static final String SET_IN_ARITH_SRC =
        "Objecttype de Vlucht\n    de vertrektijd Numeriek (geheel getal);\n    is laat kenmerk (bijvoeglijk);\n    is vol kenmerk (bijvoeglijk);\n"
        + "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n"
        + "Feittype Vlucht van passagiers\n    de reis Vlucht\n    de passagier Passagier\nEén reis betreft meerdere passagiers\n"
        + "Regel R\n    geldig altijd\n        Een Vlucht is laat indien de prijs van alle passagiers plus zijn vertrektijd groter is dan 100.\n"
        + "Regel S\n    geldig altijd\n        Een Vlucht is vol indien zijn vertrektijd groter is dan 0.\n";

    // §5.3 a NON-numeriek (Tekst/datum) GELIJK/ONGELIJK CONSISTENTIE over an attr-vs-attr operand ("De vertrek moet gelijk
    //   zijn aan de bestemming") would coerce a String via toBd and CRASH the exec session at fire time (the schending
    //   path's isNumeriekOp gate lets GELIJK/ONGELIJK through to the numeric toBd coercion). The exec must SKIP it (honest
    //   overgeslagen, symmetric-safe with DRL flagging it), not crash. Co-resident rule S (a Tekst vs-LITERAL voorwaarde the
    //   exec DOES lower) fires -> proves the skip does not take the session down.
    static final String TEKST_CONSISTENTIE_SRC =
        "Objecttype de Route\n    de vertrek Tekst;\n    de bestemming Tekst;\n    is gemarkeerd kenmerk (bijvoeglijk);\n"
        + "Regel R\n    geldig altijd\n        De vertrek van een Route moet gelijk zijn aan de bestemming van een Route.\n"
        + "Regel S\n    geldig altijd\n        Een Route is gemarkeerd indien zijn vertrek gelijk is aan \"A\".\n";

    // ---- Phase C2: §5.8 AGGREGATION INSIDE ARITHMETIC — an aggregation operand inside a Binair (the value is an
    //      arithmetic tree whose leaf is an AANTAL/TOTAAL/MIN/MAX). Each embedded aggregation lowers to its own
    //      accumulate CE; the consequence combines the bound results + scalars + a (≤1) global. Facts: [Vlucht, pax...].
    static Passagier pax(Vlucht reis) { Passagier p = new Passagier(); p.setReis(reis); return p; }
    static final String AGG_HDR = "Objecttype de Vlucht\n    de totaalprijs Numeriek (geheel getal);\n"
            + "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel tel\n    geldig altijd\n        ";
    static final Fixture[] PHASE_C2 = {
        // §13.4.17.47 "het aantal passagiers van de reis maal de factor" = (count) * factor — the maal binds OUTSIDE
        // the telling. 2 passagiers maal global 10 = 20 (an empty reis -> 0). Exercises an aggregation MAAL a global.
        new Fixture("aantal passagiers MAAL global factor (2*10=20)",
            "Parameter de factor : Numeriek (geheel getal)\n" + AGG_HDR
            + "De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis maal de factor.\n",
            () -> { Vlucht v = new Vlucht(); Vlucht leeg = new Vlucht(); return List.of(v, leeg, pax(v), pax(v)); },
            Map.of("factor", new BigDecimal(10)),
            l -> vals(((Vlucht) l.get(0)).getTotaalprijs(), ((Vlucht) l.get(1)).getTotaalprijs())),   // 20, 0

        // an aggregation MAAL a literal: 3 passagiers maal 5 = 15.
        new Fixture("aantal passagiers MAAL literal 5 (3*5=15)",
            AGG_HDR + "De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis maal 5.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, pax(v), pax(v), pax(v)); },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 15

        // an aggregation PLUS a literal (leegwaarde rsNul): 3 passagiers plus 1 = 4.
        new Fixture("aantal passagiers PLUS literal 1 (3+1=4)",
            AGG_HDR + "De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis plus 1.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, pax(v), pax(v), pax(v)); },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 4

        // TWO aggregations in one arithmetic (binds two accumulate result vars): count * count = 3*3 = 9.
        new Fixture("aantal passagiers MAAL aantal passagiers (3*3=9)",
            AGG_HDR + "De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis maal het aantal passagiers van de reis.\n",
            () -> { Vlucht v = new Vlucht(); return List.of(v, pax(v), pax(v), pax(v)); },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 9

        // §5.8.2 SOM inside arithmetic: "2 maal de som van de prijs van alle passagiers ..." (the maal on the LEFT so
        // it binds OUTSIDE the SOM). sum 100 * 2 = 200; an empty reis -> SOM leegwaarde (null) -> rsNul -> 0. Binds
        // BOTH the count and sum vars of the SOM accumulate.
        new Fixture("SOM inside arithmetic (2 maal som van prijs = 200; empty -> 0)",
            AGG_HDR + "De totaalprijs van een reis moet berekend worden als 2 maal de som van de prijs van alle passagiers van de reis.\n",
            () -> { Vlucht v = new Vlucht(); Vlucht leeg = new Vlucht(); return List.of(v, leeg, pas(v, 30), pas(v, 70)); },
            Map.of(),
            l -> vals(((Vlucht) l.get(0)).getTotaalprijs(), ((Vlucht) l.get(1)).getTotaalprijs())),   // 200, 0
    };

    // ---- Phase H: §8.1.1 NAVIGATED + attr-vs-attr numeric comparison in a CONDITION (the model conditie back-end
    //      only lowered an OWN-attr-vs-literal/global comparison; DRL lowers `reis!.x op y` and `attrA op attrB`).
    //      The subject Passagier navigates "zijn reis" (a to-ONE role to Vlucht); a null reis reads leeg (mvel `!.`
    //      short-circuits, vergelijk no-matches on an ordering / leeg operand). Facts: [Vlucht..., Passagier...]. ----
    static final String NAV_HDR =
            "Objecttype de Vlucht\n    de vertrektijd Numeriek (geheel getal);\n"
            + "Objecttype de Passagier\n    het basistarief Numeriek (geheel getal);\n    de prijs Numeriek (geheel getal);\n" + AGG_FEIT;
    static Passagier paxReis(Vlucht reis) { Passagier p = new Passagier(); p.setReis(reis); return p; }
    static final Fixture[] PHASE_H = {
        // navigated comparison vs a LITERAL: vertrektijd 150 > 100 fires (basistarief := 50); a null reis -> leeg ->
        // no match (mvel `null!.x > 100` is false), basistarief stays null — both back-ends agree.
        new Fixture("navigated comparison vs literal (vertrektijd van zijn reis > 100)",
            NAV_HDR + "Regel R\n    geldig altijd\n"
            + "        Het basistarief van een passagier moet gesteld worden op 50 indien de vertrektijd van zijn reis groter is dan 100.\n",
            () -> { Vlucht v = new Vlucht(); v.setVertrektijd(new BigDecimal(150)); Passagier p1 = paxReis(v); Passagier p2 = paxReis(null);
                    return List.of(v, p1, p2); },
            Map.of(),
            l -> vals(((Passagier) l.get(1)).getBasistarief(), ((Passagier) l.get(2)).getBasistarief())),   // 50, null

        // navigated comparison below the threshold: vertrektijd 80 > 100 is false -> no fire.
        new Fixture("navigated comparison vs literal below threshold (no fire)",
            NAV_HDR + "Regel R\n    geldig altijd\n"
            + "        Het basistarief van een passagier moet gesteld worden op 50 indien de vertrektijd van zijn reis groter is dan 100.\n",
            () -> { Vlucht v = new Vlucht(); v.setVertrektijd(new BigDecimal(80)); return List.of(v, paxReis(v)); },
            Map.of(),
            l -> ((Passagier) l.get(1)).getBasistarief()),   // null

        // navigated comparison vs a Parameter-GLOBAL — the §12 reisduur "... van zijn reis groter is dan <grens>"
        // shape: the global is a pattern variable, navReader reads the navigated value. 150 > 120 fires, 100 > 120 not.
        new Fixture("navigated comparison vs global (vertrektijd van zijn reis > de grens)",
            "Parameter de grens : Numeriek (geheel getal)\n" + NAV_HDR + "Regel R\n    geldig altijd\n"
            + "        Het basistarief van een passagier moet gesteld worden op 50 indien de vertrektijd van zijn reis groter is dan de grens.\n",
            () -> { Vlucht hoog = new Vlucht(); hoog.setVertrektijd(new BigDecimal(150));
                    Vlucht laag = new Vlucht(); laag.setVertrektijd(new BigDecimal(100));
                    return List.of(hoog, laag, paxReis(hoog), paxReis(laag)); },
            Map.of("grens", new BigDecimal(120)),
            l -> vals(((Passagier) l.get(2)).getBasistarief(), ((Passagier) l.get(3)).getBasistarief())),   // 50, null

        // OWN-attr vs OWN-attr numeric comparison ("zijn prijs groter is dan zijn basistarief"): both fact-local reads.
        // prijs 90 > basistarief 50 fires (plafond := 9); prijs 30 > 50 does not — both back-ends agree.
        new Fixture("own-attr vs own-attr comparison (prijs > basistarief)",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n    het plafond Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        Het plafond van een passagier moet gesteld worden op 9 indien zijn prijs groter is dan zijn basistarief.\n",
            () -> { Passagier vuurt = passagier(p -> { p.setPrijs(new BigDecimal(90)); p.setBasistarief(new BigDecimal(50)); });
                    Passagier niet  = passagier(p -> { p.setPrijs(new BigDecimal(30)); p.setBasistarief(new BigDecimal(50)); });
                    return List.of(vuurt, niet); },
            Map.of(),
            l -> vals(((Passagier) l.get(0)).getPlafond(), ((Passagier) l.get(1)).getPlafond())),   // 9, null

        // (adversary) cross-unit NAVIGATED comparison (§5.1 Tabel 32): vertrektijd[mnd] van zijn reis < 1 jr must
        // restate 1 jr -> 12 mnd first, so 6 mnd < 12 fires and 18 mnd < 12 does not — the SAME restatement DRL's
        // vergelijkingsOperanden applies, now reached through the navigated read.
        new Fixture("cross-unit navigated comparison (mnd nav attr vs jr literal)",
            """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Vlucht
                de vertrektijd Numeriek (geheel getal) met eenheid mnd;
            Objecttype de Passagier
                het basistarief Numeriek (geheel getal);
            """ + AGG_FEIT + """
            Regel R
                geldig altijd
                    Het basistarief van een passagier moet gesteld worden op 50 indien de vertrektijd van zijn reis kleiner is dan 1 jr.
            """,
            () -> { Vlucht kort = new Vlucht(); kort.setVertrektijd(new BigDecimal(6));
                    Vlucht lang = new Vlucht(); lang.setVertrektijd(new BigDecimal(18));
                    return List.of(kort, lang, paxReis(kort), paxReis(lang)); },
            Map.of(),
            l -> vals(((Passagier) l.get(2)).getBasistarief(), ((Passagier) l.get(3)).getBasistarief())),   // 50, null

        // appositive "in <eenheid>" on the NAVIGATED read — the spec's §12 reisduur "de reisduur per trein IN MINUTEN
        // van zijn reis groter is dan ..." (main-spec L4744). The resolver strips the native-unit suffix, so both
        // back-ends lower the SAME bare reis!.vertrektijd; the dimensionless literal 10 adopts the unit (§13.3.5).
        // vertrektijd[mnd] in maanden > 10: 18 fires (basistarief := 50), 6 not.
        new Fixture("appositive 'in <eenheid>' on a navigated read (in maanden)",
            """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Vlucht
                de vertrektijd Numeriek (geheel getal) met eenheid mnd;
            Objecttype de Passagier
                het basistarief Numeriek (geheel getal);
            """ + AGG_FEIT + """
            Regel R
                geldig altijd
                    Het basistarief van een passagier moet gesteld worden op 50 indien de vertrektijd in maanden van zijn reis groter is dan 10.
            """,
            () -> { Vlucht hoog = new Vlucht(); hoog.setVertrektijd(new BigDecimal(18));
                    Vlucht laag = new Vlucht(); laag.setVertrektijd(new BigDecimal(6));
                    return List.of(hoog, laag, paxReis(hoog), paxReis(laag)); },
            Map.of(),
            l -> vals(((Passagier) l.get(2)).getBasistarief(), ((Passagier) l.get(3)).getBasistarief())),   // 50, null

        // (adversary) navigated GELIJK vs literal: vertrektijd 100 == 100 fires; a NULL reis -> leeg, mvel `null ==
        // 100` is false (vergelijk GELIJK true only both-leeg), so the partnerless passenger does NOT fire.
        new Fixture("navigated GELIJK vs literal (null reis no match)",
            NAV_HDR + "Regel R\n    geldig altijd\n"
            + "        Het basistarief van een passagier moet gesteld worden op 50 indien de vertrektijd van zijn reis gelijk is aan 100.\n",
            () -> { Vlucht v = new Vlucht(); v.setVertrektijd(new BigDecimal(100)); return List.of(v, paxReis(v), paxReis(null)); },
            Map.of(),
            l -> vals(((Passagier) l.get(1)).getBasistarief(), ((Passagier) l.get(2)).getBasistarief())),   // 50, null

        // (adversary) navigated ONGELIJK vs literal, null RELATION: §5.5.5 an absent navigation reads LEEG, then §5.6
        // Tabel 44 (one-leeg "ongelijk aan") = WAAR — so the partnerless passenger FIRES. Both back-ends now read the
        // navigated comparison leegwaarde-faithfully (no mvel `!.` short-circuit): equal (100) -> no fire; different (80)
        // -> fire; null reis (leeg != 100) -> FIRE. Absolute values [null, 50, 50]. (This was the carded Tabel-44 gap,
        // since fixed; the file's PHASE_NAV_LEEG asserts the full leegwaarde matrix with absolute spec values.)
        new Fixture("navigated ONGELIJK vs literal (null relation reads leeg -> WAAR, Tabel 44)",
            NAV_HDR + "Regel R\n    geldig altijd\n"
            + "        Het basistarief van een passagier moet gesteld worden op 50 indien de vertrektijd van zijn reis ongelijk is aan 100.\n",
            () -> { Vlucht gelijk = new Vlucht(); gelijk.setVertrektijd(new BigDecimal(100));
                    Vlucht anders = new Vlucht(); anders.setVertrektijd(new BigDecimal(80));
                    return List.of(gelijk, anders, paxReis(gelijk), paxReis(anders), paxReis(null)); },
            Map.of(),
            l -> vals(((Passagier) l.get(2)).getBasistarief(), ((Passagier) l.get(3)).getBasistarief(),
                      ((Passagier) l.get(4)).getBasistarief())),   // null, 50, null  (third SHOULD be 50 per Tabel 44 — carded)

        // (adversary) the SUB-case that proves the relation-null short-circuit does not over-apply: a PRESENT reis
        // whose vertrektijd is itself null. mvel only null-guards the `reis` deref — `reis.vertrektijd` then yields
        // null, and `null != 100` is a normal mvel compare (TRUE), so an ONGELIJK fires for a present-but-empty
        // attribute, UNLIKE the null-reis case. Distinguishes a null RELATION (false) from a null ATTRIBUTE (vergelijk).
        new Fixture("navigated ONGELIJK, present reis but null vertrektijd (attribute leeg, still fires)",
            NAV_HDR + "Regel R\n    geldig altijd\n"
            + "        Het basistarief van een passagier moet gesteld worden op 50 indien de vertrektijd van zijn reis ongelijk is aan 100.\n",
            () -> { Vlucht leegAttr = new Vlucht(); /* vertrektijd null */ return List.of(leegAttr, paxReis(leegAttr)); },
            Map.of(),
            l -> ((Passagier) l.get(1)).getBasistarief()),   // 50 (null != 100 -> true)

        // §8.1.1 DATUM ordering comparison of an OWN datum attribute vs a datum literal (eerder dan): geboortedatum
        // 1990 < 2000 fires, 2005 does not, a null geboortedatum -> ordering no-match (no fire). DRL lowers
        // `geboortedatum < LocalDate.of(2000,1,1)`.
        new Fixture("datum ordering condition (own attr: geboortedatum eerder dan 1-1-2000)",
            "Objecttype de Passagier\n    de geboortedatum Datum in dagen;\n    het basistarief Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        Het basistarief van een passagier moet gesteld worden op 50 indien zijn geboortedatum eerder is dan 1-1-2000.\n",
            () -> List.of(passagier(p -> p.setGeboortedatum(dat(1990, 1, 1))),
                          passagier(p -> p.setGeboortedatum(dat(2005, 1, 1))),
                          new Passagier()),   // null geboortedatum
            Map.of(),
            l -> vals(((Passagier) l.get(0)).getBasistarief(), ((Passagier) l.get(1)).getBasistarief(),
                      ((Passagier) l.get(2)).getBasistarief())),   // 50, null, null

        // (adversary) datum EERDER_OF_GELIJK boundary: geboortedatum exactly == 1-1-2000 satisfies "eerder of gelijk
        // aan 1-1-2000" (inclusive), so it fires; 2005 does not.
        new Fixture("datum eerder-of-gelijk boundary (geboortedatum exactly 1-1-2000 fires)",
            "Objecttype de Passagier\n    de geboortedatum Datum in dagen;\n    het basistarief Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        Het basistarief van een passagier moet gesteld worden op 50 indien zijn geboortedatum eerder of gelijk is aan 1-1-2000.\n",
            () -> List.of(passagier(p -> p.setGeboortedatum(dat(2000, 1, 1))), passagier(p -> p.setGeboortedatum(dat(2005, 1, 1)))),
            Map.of(),
            l -> vals(((Passagier) l.get(0)).getBasistarief(), ((Passagier) l.get(1)).getBasistarief())),   // 50, null

        // §8.1.1 NAVIGATED datum ordering: vluchtdatum van zijn reis later dan 1-1-2020. A null reis short-circuits
        // (mvel `reis!.vluchtdatum`). 2025 > 2020 fires, 2010 does not, null reis no fire.
        new Fixture("datum ordering condition (navigated: vluchtdatum van zijn reis later dan 1-1-2020)",
            "Objecttype de Vlucht\n    de vluchtdatum Datum in dagen;\n"
            + "Objecttype de Passagier\n    het basistarief Numeriek (geheel getal);\n" + AGG_FEIT
            + "Regel R\n    geldig altijd\n        Het basistarief van een passagier moet gesteld worden op 50 indien de vluchtdatum van zijn reis later is dan 1-1-2020.\n",
            () -> { Vlucht laat = new Vlucht(); laat.setVluchtdatum(dat(2025, 1, 1));
                    Vlucht vroeg = new Vlucht(); vroeg.setVluchtdatum(dat(2010, 1, 1));
                    return List.of(laat, vroeg, paxReis(laat), paxReis(vroeg), paxReis(null)); },
            Map.of(),
            l -> vals(((Passagier) l.get(2)).getBasistarief(), ((Passagier) l.get(3)).getBasistarief(),
                      ((Passagier) l.get(4)).getBasistarief())),   // 50, null, null

        // (adversary) a NAVIGATED comparison nested in a GEEN counting quantifier — the navigated leaf must fold into
        // the count exactly as DRL folds its inline. "geen van [vertrektijd van zijn reis > 100, zijn prijs > 50]":
        // a reis at 80 with prijs 30 satisfies neither -> GEEN holds -> fires; a reis at 150 -> a bullet holds -> no fire.
        new Fixture("navigated comparison inside a GEEN quantifier (folds into the count)",
            NAV_HDR + "Regel R\n    geldig altijd\n"
            + "        Het basistarief van een passagier moet gesteld worden op 50 indien hij aan geen van de volgende voorwaarden voldoet:\n"
            + "          • de vertrektijd van zijn reis is groter dan 100\n"
            + "          • zijn prijs is groter dan 50.\n",
            () -> { Vlucht laag = new Vlucht(); laag.setVertrektijd(new BigDecimal(80));
                    Vlucht hoog = new Vlucht(); hoog.setVertrektijd(new BigDecimal(150));
                    Passagier a = paxReis(laag); a.setPrijs(new BigDecimal(30));   // neither bullet -> GEEN -> fire
                    Passagier b = paxReis(hoog); b.setPrijs(new BigDecimal(30));   // nav bullet holds -> no fire
                    return List.of(laag, hoog, a, b); },
            Map.of(),
            l -> vals(((Passagier) l.get(2)).getBasistarief(), ((Passagier) l.get(3)).getBasistarief())),   // 50, null

        // §5.3 navigated TEKST equality: "de roepnaam van zijn partner gelijk is aan 'Maria'". partner.roepnaam=Maria
        // fires; =Anna no fire; NULL partner short-circuits (mvel rol!.attr) -> no fire.
        new Fixture("navigated Tekst equality (roepnaam van zijn partner == Maria)",
            """
            Objecttype de Persoon (mv: Personen) (bezield)
                de roepnaam Tekst;
                de uitkomst Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel R
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1 indien de roepnaam van zijn partner gelijk is aan "Maria".
            """,
            () -> { Persoon maria = new Persoon(); maria.setRoepnaam("Maria");
                    Persoon anna = new Persoon(); anna.setRoepnaam("Anna");
                    Persoon pMaria = new Persoon(); pMaria.setPartner(maria);
                    Persoon pAnna = new Persoon(); pAnna.setPartner(anna);
                    Persoon pGeen = new Persoon();   // no partner -> null relation
                    return List.of(pMaria, pAnna, pGeen, maria, anna); },
            Map.of(),
            l -> vals(((Persoon) l.get(0)).getUitkomst(), ((Persoon) l.get(1)).getUitkomst(), ((Persoon) l.get(2)).getUitkomst())),   // 1, null, null

        // §8.1.2 navigated LEEG: "de roepnaam van zijn partner is leeg". The KEY semantic — an ABSENT partner reads
        // the value as LEEG (typeringen Tabel 53/54), so a partnerless persoon FIRES (unlike a comparison's !.
        // short-circuit). partner.roepnaam=Maria -> not leeg (no fire); partner.roepnaam=null -> leeg (fire); no
        // partner -> leeg (fire).
        new Fixture("navigated leeg (absent partner reads the value as leeg)",
            """
            Objecttype de Persoon (mv: Personen) (bezield)
                de roepnaam Tekst;
                de uitkomst Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel R
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1 indien de roepnaam van zijn partner leeg is.
            """,
            () -> { Persoon metNaam = new Persoon(); metNaam.setRoepnaam("Maria");
                    Persoon zonderNaam = new Persoon();   // roepnaam null
                    Persoon pVol = new Persoon(); pVol.setPartner(metNaam);
                    Persoon pLeegAttr = new Persoon(); pLeegAttr.setPartner(zonderNaam);
                    Persoon pGeenPartner = new Persoon();   // absent reference -> reads leeg -> fires
                    return List.of(pVol, pLeegAttr, pGeenPartner); },
            Map.of(),
            l -> vals(((Persoon) l.get(0)).getUitkomst(), ((Persoon) l.get(1)).getUitkomst(), ((Persoon) l.get(2)).getUitkomst())),   // null, 1, 1

        // §6.x navigated VALUE read "de leeftijd van zijn partner". Both partners are mutually linked (a non-null
        // relation), so DRL's unguarded getter chain reads cleanly and AGREES with navReader. a.uitkomst = b.leeftijd
        // = 30; b.uitkomst = a.leeftijd = 40. (The null-relation case is model-only — DRL NPEs — see the @Test below.)
        new Fixture("navigated value read (de leeftijd van zijn partner)",
            """
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal);
                de uitkomst Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel R
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op de leeftijd van zijn partner.
            """,
            () -> { Persoon a = new Persoon(); a.setLeeftijd(new BigDecimal(40));
                    Persoon b = new Persoon(); b.setLeeftijd(new BigDecimal(30));
                    a.setPartner(b); b.setPartner(a);
                    return List.of(a, b); },
            Map.of(),
            l -> vals(((Persoon) l.get(0)).getUitkomst(), ((Persoon) l.get(1)).getUitkomst())),   // 30, 40

        // §8.1.1 a NAVIGATED LHS vs an OWN-attr RHS ("de vertrektijd van zijn reis groter is dan zijn basistarief").
        // 150>100 fires; 150>200 not; a null reis short-circuits -> no fire.
        new Fixture("navigated LHS vs own-attr RHS comparison",
            NAV_HDR + "Regel R\n    geldig altijd\n"
            + "        De prijs van een passagier moet gesteld worden op 50 indien de vertrektijd van zijn reis groter is dan zijn basistarief.\n",
            () -> { Vlucht v = new Vlucht(); v.setVertrektijd(new BigDecimal(150));
                    Passagier laag = paxReis(v); laag.setBasistarief(new BigDecimal(100));   // 150>100 -> fire
                    Passagier hoog = paxReis(v); hoog.setBasistarief(new BigDecimal(200));   // 150>200 -> no fire
                    Passagier geen = paxReis(null); geen.setBasistarief(new BigDecimal(0));   // null reis -> no fire
                    return List.of(v, laag, hoog, geen); },
            Map.of(),
            l -> vals(((Passagier) l.get(1)).getPrijs(), ((Passagier) l.get(2)).getPrijs(), ((Passagier) l.get(3)).getPrijs())),   // 50, null, null
    };

    /** §6.9 a NAVIGATED value read over a NULL relation: the model is the correct null-safe side (-> leeg), while DRL
     *  renders an unguarded getter chain that NPEs and aborts the session (a latent DRL bug, like the DimensieCel
     *  null-Map). So this is NOT differentially testable — assert the model fires cleanly and leaves the doel leeg. */
    @Test void phase_H_navigated_value_read_over_null_relation_is_null_safe_in_model() {
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal);
                de uitkomst Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel R
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op de leeftijd van zijn partner.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "model moet de navigatie-lezing loweren: " + em.overgeslagen());
        KieSession s = KieBaseBuilder.createKieBaseFromModel(em.model()).newKieSession();
        Persoon zonder = new Persoon(); zonder.setLeeftijd(new BigDecimal(40));   // partner == null
        s.insert(zonder);
        s.fireAllRules();   // must NOT throw (DRL would NPE here)
        s.dispose();
        assertNull(zonder.getUitkomst(), "een null-relatie leest leeg (§6.9), niet een NPE");
    }

    @Test void verdeling_voorwaarde_gate_exec_model_deelt_alleen_bij_ware_conditie() {
        // §13.4.10 exec-model mirror of the DRL voorwaarde-gate: a pot below the threshold distributes NOTHING, one
        // above it distributes normally. Proves the exec-model back-end gates the whole verdeling family on v.voorwaarde().
        String src = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel indien groot genoeg
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen indien de totaal van een pot groter is dan 100.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "de verdeling moet loweren: " + em.overgeslagen());
        KieSession s = KieBaseBuilder.createKieBaseFromModel(em.model()).newKieSession();
        Pot laag = new Pot(); laag.setTotaal(new BigDecimal("40"));
        Deelnemer l1 = new Deelnemer(); l1.setPot(laag);
        Pot hoog = new Pot(); hoog.setTotaal(new BigDecimal("900"));
        Deelnemer h1 = new Deelnemer(); h1.setPot(hoog);
        Deelnemer h2 = new Deelnemer(); h2.setPot(hoog);
        Deelnemer h3 = new Deelnemer(); h3.setPot(hoog);
        for (Object o : new Object[]{laag, l1, hoog, h1, h2, h3}) s.insert(o);
        s.fireAllRules();
        s.dispose();
        assertNull(l1.getAandeel(), "gate false: geen aandeel (exec-model)");
        for (Deelnemer d : new Deelnemer[]{h1, h2, h3})
            assertEquals(0, new BigDecimal("300").compareTo(d.getAandeel()), () -> "gate true: 900/3 = 300 (exec-model), kreeg " + d.getAandeel());
    }

    @Test void verdeling_zonder_ontvangers_rest_leeg_exec_model() {
        // §9.7.5 exec-model mirror: a pot WITH recipients keeps the rest; a pot with NO recipients leaves it leeg (not
        // restant = totaal). Same shape ref/clingo pin (golden jutterswet-gelijkdeling); proves the exec-model rest rule
        // requires ≥1 recipient.
        String src = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel gelijk met rest
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - in gelijke delen,
                    - afgerond op 0 decimalen naar beneden
                    Als onverdeelde rest blijft de restant van een pot over.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "de verdeling+rest moet loweren: " + em.overgeslagen());
        KieSession s = KieBaseBuilder.createKieBaseFromModel(em.model()).newKieSession();
        Pot metOntvangers = new Pot(); metOntvangers.setTotaal(new BigDecimal("1001"));
        Deelnemer d1 = new Deelnemer(); d1.setPot(metOntvangers);
        Deelnemer d2 = new Deelnemer(); d2.setPot(metOntvangers);
        Pot geenOntvangers = new Pot(); geenOntvangers.setTotaal(new BigDecimal("40"));   // GEEN deelnemers
        for (Object o : new Object[]{metOntvangers, d1, d2, geenOntvangers}) s.insert(o);
        s.fireAllRules();
        s.dispose();
        assertEquals(0, new BigDecimal("1").compareTo(metOntvangers.getRestant()),
                () -> "rest met ontvangers = 1001-500-500 = 1 (exec-model), kreeg " + metOntvangers.getRestant());
        assertNull(geenOntvangers.getRestant(), "geen ontvangers -> rest leeg (exec-model)");
    }

    // ---- Phase F fixtures: §7.1 "het totaal van" a temporal attribute (scalar timeline fold) ----
    /** A Segment(van, tot, waarde) — a null waarde marks a kenmerk's true-period; a string waarde is a value tijdvak. */
    private static Segment seg(int y1, int m1, int d1, int y2, int m2, int d2, String w) {
        return new Segment(LocalDate.of(y1, m1, d1), LocalDate.of(y2, m2, d2), w == null ? null : new BigDecimal(w));
    }
    /** Canonicalise a List&lt;Segment&gt; read-back so the two back-ends' distinct Segment instances compare
     *  element-wise by van|tot|waarde (Segment has no equals); waarde is scale-insensitive, null shown as "leeg". */
    private static Object tl(Object segments) {
        List<Object> out = new ArrayList<>();
        if (segments != null) for (Object o : (List<?>) segments) {
            Segment s = (Segment) o;
            out.add(s.getVan() + "|" + s.getTot() + "|"
                    + (s.getWaarde() == null ? "leeg" : s.getWaarde().stripTrailingZeros().toPlainString()));
        }
        return out;
    }
    private static final String TOTAAL_HDR =
            "Domein Bedrag is van het type Numeriek (getal met 2 decimalen)\n";

    // ---- §5.1.4 general temporal-arithmetic WRITE: a temporal doel = <temporal> plus/min <temporal>, evaluated per
    //      period on the MERGED knip timeline of the two operands (leeg -> 0, §5.1.4 note), coalescing equal-adjacent
    //      segments. Was a symmetric marker. Both back-ends call the byte-identical rsTijdReken. ----
    static String trSrc(String op) {
        return "Domein Bedrag is van het type Numeriek (geheel getal)\n" +
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
            "    de belastingvermindering per maand Bedrag voor elke maand;\n" +
            "    de inkomen per maand Bedrag voor elke maand;\n" +
            "    de standaardbedrag Bedrag voor elke maand;\n" +
            "Regel R\n    geldig altijd\n        De standaardbedrag van een Passagier moet berekend worden als zijn belastingvermindering per maand " + op + " zijn inkomen per maand.\n";
    }
    static final Fixture[] PHASE_TR = {
        // §5.1.4.1 the spec's own worked example: afstand + reisduur -> the merged-knip timeline with the 1-5/1-6 coalesce.
        new Fixture("§5.1.4.1 PLUS worked example (60/65/0/100/75, 1-6 coalesced)",
            trSrc("plus"),
            () -> List.of(passagier(p -> {
                p.setBelastingverminderingPerMaand(List.of(   // afstand: €50, €65, (gap), €75, €10
                    seg(2024, 1, 1, 2024, 2, 1, "50"), seg(2024, 2, 1, 2024, 3, 1, "65"),
                    seg(2024, 4, 1, 2024, 6, 1, "75"), seg(2024, 6, 1, 2024, 7, 1, "10")));
                p.setInkomenPerMaand(List.of(                 // reisduur: €10, €25, €65
                    seg(2024, 1, 1, 2024, 2, 1, "10"), seg(2024, 4, 1, 2024, 5, 1, "25"), seg(2024, 6, 1, 2024, 7, 1, "65")));
            })),
            Map.of(), l -> tl(((Passagier) l.get(0)).getStandaardbedrag())),
        // MIN: per-period subtraction, leeg -> 0. afstand 50,40 ; reisduur 20 (Jan only) -> 30, 40.
        new Fixture("§5.1.4 MIN per period (leeg -> 0): 50-20=30, 40-0=40",
            trSrc("min"),
            () -> List.of(passagier(p -> {
                p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "50"), seg(2024, 2, 1, 2024, 3, 1, "40")));
                p.setInkomenPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "20")));
            })),
            Map.of(), l -> tl(((Passagier) l.get(0)).getStandaardbedrag())),
        // one operand entirely leeg (null timeline): PLUS treats leeg as 0 -> the result equals the non-leeg operand.
        new Fixture("§5.1.4 PLUS with one leeg operand -> the other operand verbatim",
            trSrc("plus"),
            () -> List.of(passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 3, 1, 2024, 5, 1, "33"))))),
            Map.of(), l -> tl(((Passagier) l.get(0)).getStandaardbedrag())),
    };
    static final Object[] PHASE_TR_EXPECTED = {
        java.util.List.of("2024-01-01|2024-02-01|60", "2024-02-01|2024-03-01|65", "2024-03-01|2024-04-01|0",
                "2024-04-01|2024-05-01|100", "2024-05-01|2024-07-01|75"),
        java.util.List.of("2024-01-01|2024-02-01|30", "2024-02-01|2024-03-01|40"),
        java.util.List.of("2024-03-01|2024-05-01|33"),
    };

    // ---- §5.1.4.2 the spec's COMPLEX worked example: MIXED granularity (afstand "voor elke dag" + reisduur "voor elke
    //      maand" -> te betalen "voor elke dag") AND CROSS-UNIT (€/mnd operands summed, then restated ×12 to the €/jr
    //      result via the eenheidsysteem Tijd factor). Was a symmetric marker (temporeelRekenOperand gated equal-unit +
    //      equal-gran). Now the merged-knip rsTijdReken handles the boundaries; a per-operand rsTijdSchaal(×12) restates
    //      the units. The final table (spec L1729-1738) has the 1-5/1-6 coalesce (900/jr spans 1-5..22-6). ----
    static final String TR2_SRC =
        "Eenheidsysteem Valuta\n    de euro (mv: euros) EUR €\n" +
        "Eenheidsysteem Tijd\n    de maand mnd\n    het jaar jr = 12 mnd\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "    de belastingvermindering per maand Numeriek (geheel getal) met eenheid €/mnd voor elke dag;\n" +
        "    de inkomen per maand Numeriek (geheel getal) met eenheid €/mnd voor elke maand;\n" +
        "    de standaardbedrag Numeriek (geheel getal) met eenheid €/jr voor elke dag;\n" +
        "Regel R\n    geldig altijd\n        De standaardbedrag van een Passagier moet berekend worden als zijn belastingvermindering per maand plus zijn inkomen per maand.\n";
    // cross-unit ONLY (equal gran "voor elke maand", €/mnd -> €/jr): isolates the ×12 restatement.
    static final String TR2_CU_SRC = TR2_SRC.replace("€/mnd voor elke dag", "€/mnd voor elke maand");
    // mixed-gran ONLY (equal unit, no rate; per-dag + per-maand -> per-dag): isolates the knip-promotion.
    static final String TR2_MG_SRC =
        "Domein Bedrag is van het type Numeriek (geheel getal)\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "    de belastingvermindering per maand Bedrag voor elke dag;\n" +
        "    de inkomen per maand Bedrag voor elke maand;\n" +
        "    de standaardbedrag Bedrag voor elke dag;\n" +
        "Regel R\n    geldig altijd\n        De standaardbedrag van een Passagier moet berekend worden als zijn belastingvermindering per maand plus zijn inkomen per maand.\n";
    // DIVIDE direction, NON-TERMINATING (€/jr operands -> €/mnd doel, ÷12). Guards review wf_115473d5-eee F1: the sum
    // MUST be computed FIRST (in the operand unit) then converted ONCE — scale-each-then-sum double-rounds. 1+1 €/jr ->
    // (2/12) €/mnd = 0.1666666666666667 (DEC64, single round), NOT 1/12+1/12 = 0.16666666666666666 (double round).
    static final String TR2_DIV_SRC =
        "Eenheidsysteem Valuta\n    de euro (mv: euros) EUR €\n" +
        "Eenheidsysteem Tijd\n    de maand mnd\n    het jaar jr = 12 mnd\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "    de belastingvermindering per maand Numeriek (getal met 4 decimalen) met eenheid €/jr voor elke maand;\n" +
        "    de inkomen per maand Numeriek (getal met 4 decimalen) met eenheid €/jr voor elke maand;\n" +
        "    de standaardbedrag Numeriek (getal met 4 decimalen) met eenheid €/mnd voor elke maand;\n" +
        "Regel R\n    geldig altijd\n        De standaardbedrag van een Passagier moet berekend worden als zijn belastingvermindering per maand plus zijn inkomen per maand.\n";
    static Fixture tr2Fix(String naam, String src, java.util.function.Consumer<Passagier> setup) {
        return new Fixture(naam, src, () -> List.of(passagier(setup)), Map.of(), l -> tl(((Passagier) l.get(0)).getStandaardbedrag()));
    }
    static final Fixture[] PHASE_TR2 = {
        tr2Fix("§5.1.4.2 full worked example (mixed-gran dag+maand, €/mnd->€/jr ×12, 1-6 coalesced)", TR2_SRC, p -> {
            p.setBelastingverminderingPerMaand(List.of(   // afstand €/mnd voor elke dag
                seg(2024, 1, 1, 2024, 1, 15, "50"), seg(2024, 2, 1, 2024, 3, 12, "65"),
                seg(2024, 4, 8, 2024, 6, 1, "75"), seg(2024, 6, 1, 2024, 6, 22, "10")));
            p.setInkomenPerMaand(List.of(                 // reisduur €/mnd voor elke maand
                seg(2024, 1, 1, 2024, 2, 1, "10"), seg(2024, 4, 1, 2024, 5, 1, "25"), seg(2024, 6, 1, 2024, 7, 1, "65")));
        }),
        tr2Fix("§5.1.4.2 cross-unit only (€/mnd->€/jr ×12): 60->720, 65->780", TR2_CU_SRC, p -> {
            p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "50"), seg(2024, 2, 1, 2024, 3, 1, "65")));
            p.setInkomenPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "10")));
        }),
        tr2Fix("§5.1.4.2 mixed-gran only (dag+maand -> dag knip-promotion): 60, then 10", TR2_MG_SRC, p -> {
            p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 1, 15, "50")));  // per dag
            p.setInkomenPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "10")));                 // per maand
        }),
        tr2Fix("§5.1.4.2/§4.1 sum-first-then-convert (÷12 non-terminating): 1+1 €/jr -> 2/12 €/mnd = 0.1666666666666667", TR2_DIV_SRC, p -> {
            p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "1")));
            p.setInkomenPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "1")));
        }),
    };
    static final Object[] PHASE_TR2_EXPECTED = {
        java.util.List.of("2024-01-01|2024-01-15|720", "2024-01-15|2024-02-01|120", "2024-02-01|2024-03-12|780",
                "2024-03-12|2024-04-01|0", "2024-04-01|2024-04-08|300", "2024-04-08|2024-05-01|1200",
                "2024-05-01|2024-06-22|900", "2024-06-22|2024-07-01|780"),
        java.util.List.of("2024-01-01|2024-02-01|720", "2024-02-01|2024-03-01|780"),
        java.util.List.of("2024-01-01|2024-01-15|60", "2024-01-15|2024-02-01|10"),
        java.util.List.of("2024-01-01|2024-02-01|0.1666666666666667"),   // sum-first (2/12 DEC64), NOT scale-each 0.16666666666666666
    };

    // ---- §5.1.4 an N-operand temporal arithmetic CHAIN ("a plus b plus c" = Binair(Binair(a,b), c)): was a symmetric marker
    //      (temporeelRekenOperand only accepted an AttribuutRef leaf, so the nested Binair left operand returned null). Now it
    //      recurses over a nested Binair — sum in the LEFT sub-operand's unit, restate the sub-sum once — summing per merged-
    //      knip period (rsTijdReken, leeg -> 0). Mixed granularity (per-dag + per-maand + per-dag -> per-dag) rides the union. ----
    static final String TR3_SRC =
        "Domein Bedrag is van het type Numeriek (geheel getal)\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "    de belastingvermindering per maand Bedrag voor elke dag;\n" +
        "    de inkomen per maand Bedrag voor elke maand;\n" +
        "    de aftrek per maand Bedrag voor elke dag;\n" +
        "    de standaardbedrag Bedrag voor elke dag;\n" +
        "Regel R\n    geldig altijd\n        De standaardbedrag van een Passagier moet berekend worden als zijn belastingvermindering per maand plus zijn inkomen per maand plus zijn aftrek per maand.\n";
    static Fixture tr3Fix(String naam, java.util.function.Consumer<Passagier> setup) {
        return new Fixture(naam, TR3_SRC, () -> List.of(passagier(setup)), Map.of(), l -> tl(((Passagier) l.get(0)).getStandaardbedrag()));
    }
    static final Fixture[] PHASE_TR3 = {
        tr3Fix("§5.1.4 3-operand chain, aligned (50+10+5=65)", p -> {
            p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "50")));
            p.setInkomenPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "10")));
            p.setAftrekPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "5")));
        }),
        tr3Fix("§5.1.4 3-operand chain, knip on the 1st operand (65 | 75)", p -> {
            p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 1, 15, "50"), seg(2024, 1, 15, 2024, 2, 1, "60")));
            p.setInkomenPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "10")));
            p.setAftrekPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "5")));
        }),
        tr3Fix("§5.1.4 3-operand chain, aftrek covers only 2nd half (leeg -> 0: 60 | 65)", p -> {
            p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "50")));
            p.setInkomenPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "10")));
            p.setAftrekPerMaand(List.of(seg(2024, 1, 15, 2024, 2, 1, "5")));
        }),
    };
    static final Object[] PHASE_TR3_EXPECTED = {
        java.util.List.of("2024-01-01|2024-02-01|65"),
        java.util.List.of("2024-01-01|2024-01-15|65", "2024-01-15|2024-02-01|75"),
        java.util.List.of("2024-01-01|2024-01-15|60", "2024-01-15|2024-02-01|65"),
    };

    // ---- §5.8.2/§5.1.4 a SOM over a NAVIGATED to-many TEMPORAL attribute folded PER PERIOD into a TEMPORAL doel
    //      ("De totale bijdrage van een Vlucht ... als de som van de belastingvermindering per maand van alle
    //      passagiers van de Vlucht"): was a symmetric marker ("temporele toekenning"). Now a collectList accumulate
    //      gathers the element timelines and rsTijdSomAlle folds them per merged-knip period with the §5.8.2 leeg rule
    //      (skip empties, all-empty period -> leeg; "of 0 als die er niet zijn" -> 0). Both back-ends call the byte-
    //      identical rsTijdSomAlle. ABSOLUTE folded-Segment expectations + DRL==exec parity. ----
    static final String SOMALLE_SRC =
        "Domein Bedrag is van het type Numeriek (geheel getal)\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "    de belastingvermindering per maand Bedrag voor elke maand;\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n" +
        "    de totale bijdrage Bedrag voor elke maand;\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel R\n    geldig altijd\n        De totale bijdrage van een Vlucht moet berekend worden als de som van de belastingvermindering per maand van alle passagiers van de Vlucht.\n";
    // "of 0 als die er niet zijn": an all-empty period folds to 0 instead of leeg.
    static final String SOMALLE_NUL_SRC = SOMALLE_SRC.replace(
        "van alle passagiers van de Vlucht.",
        "van alle passagiers van de Vlucht of 0 als die er niet zijn.");
    // CROSS-UNIT: element €/mnd, doel €/jr — each element timeline restated ×12 (§4.1) BEFORE the per-period sum.
    static final String SOMALLE_CU_SRC =
        "Eenheidsysteem Valuta\n    de euro (mv: euros) EUR €\n" +
        "Eenheidsysteem Tijd\n    de maand mnd\n    het jaar jr = 12 mnd\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "    de belastingvermindering per maand Numeriek (geheel getal) met eenheid €/mnd voor elke maand;\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n" +
        "    de totale bijdrage Numeriek (geheel getal) met eenheid €/jr voor elke maand;\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel R\n    geldig altijd\n        De totale bijdrage van een Vlucht moet berekend worden als de som van de belastingvermindering per maand van alle passagiers van de Vlucht.\n";
    static Passagier passOp(Vlucht v, List<Segment> tl) { Passagier p = new Passagier(); p.setReis(v); p.setBelastingverminderingPerMaand(tl); return p; }
    static Fixture somAlleFix(String naam, String src, java.util.function.Function<Vlucht, List<Object>> bouw) {
        return new Fixture(naam, src, () -> bouw.apply(new Vlucht()), Map.of(), l -> tl(((Vlucht) l.get(0)).getTotaleBijdrage()));
    }
    static final Fixture[] PHASE_SOMALLE = {
        // two passengers, staggered timelines: Jan p1=50; Feb p1=60,p2=10 -> 70; Mar p2=10 (p1 leeg, SKIPPED not 0).
        somAlleFix("§5.8.2 SOM to-many per period (leeg SKIPPED: 50 | 70 | 10)", SOMALLE_SRC, v -> List.of(v,
            passOp(v, List.of(seg(2024, 1, 1, 2024, 2, 1, "50"), seg(2024, 2, 1, 2024, 3, 1, "60"))),
            passOp(v, List.of(seg(2024, 2, 1, 2024, 4, 1, "10"))))),
        // ALL passengers leeg in a period where only one overlaps -> that period is the single value (no phantom 0).
        somAlleFix("§5.8.2 SOM to-many, one passenger only (verbatim, no phantom 0)", SOMALLE_SRC, v -> List.of(v,
            passOp(v, List.of(seg(2024, 3, 1, 2024, 5, 1, "33"))))),
        // EMPTY set (no passengers) -> leeg timeline (empty List<Segment>), §5.8.2 all-empty is leeg.
        somAlleFix("§5.8.2 SOM to-many over EMPTY set -> leeg", SOMALLE_SRC, v -> List.of(v)),
        // a GAP period between two passengers' spans is all-empty -> leeg (a knip with a leeg segment), NOT 0.
        somAlleFix("§5.8.2 SOM to-many with an all-empty GAP -> leeg segment (not 0)", SOMALLE_SRC, v -> List.of(v,
            passOp(v, List.of(seg(2024, 1, 1, 2024, 2, 1, "40"))),
            passOp(v, List.of(seg(2024, 3, 1, 2024, 4, 1, "20"))))),
        // "of 0 als die er niet zijn": the same GAP now folds to 0 instead of leeg.
        somAlleFix("§5.8.2 SOM to-many 'of 0 als die er niet zijn' -> GAP is 0", SOMALLE_NUL_SRC, v -> List.of(v,
            passOp(v, List.of(seg(2024, 1, 1, 2024, 2, 1, "40"))),
            passOp(v, List.of(seg(2024, 3, 1, 2024, 4, 1, "20"))))),
        // CROSS-UNIT (€/mnd elements -> €/jr doel, each restated ×12 BEFORE the sum): p1=50,p2=10 in Jan -> (50+10)×12=720.
        somAlleFix("§5.8.2/§4.1 SOM to-many cross-unit (€/mnd->€/jr ×12): (50+10)×12=720", SOMALLE_CU_SRC, v -> List.of(v,
            passOp(v, List.of(seg(2024, 1, 1, 2024, 2, 1, "50"))),
            passOp(v, List.of(seg(2024, 1, 1, 2024, 2, 1, "10"))))),
    };
    static final Object[] PHASE_SOMALLE_EXPECTED = {
        java.util.List.of("2024-01-01|2024-02-01|50", "2024-02-01|2024-03-01|70", "2024-03-01|2024-04-01|10"),
        java.util.List.of("2024-03-01|2024-05-01|33"),
        java.util.List.of(),
        java.util.List.of("2024-01-01|2024-02-01|40", "2024-02-01|2024-03-01|leeg", "2024-03-01|2024-04-01|20"),
        java.util.List.of("2024-01-01|2024-02-01|40", "2024-02-01|2024-03-01|0", "2024-03-01|2024-04-01|20"),
        java.util.List.of("2024-01-01|2024-02-01|720"),
    };

    // ---- §8.2 over a NAVIGATED to-many TEMPORAL attribute: a whole-period FORALL across the accumulated element
    //      timelines ("Een Vlucht is groot indien de belastingvermindering per maand van alle passagiers van de Vlucht
    //      gedurende het gehele jaar groter is dan 40"): was a symmetric marker. Now a collectList accumulate + an
    //      rsGeheelPeriodeAlle result-var eval (the §8.2 predicate AND-ed over every element). Both back-ends call the
    //      byte-identical rsGeheelPeriodeAlle. Absolute firing + DRL==exec parity. Empty set = vacuously WAAR. ----
    static final String GP_ALLE_SRC =
        "Domein Bedrag is van het type Numeriek (geheel getal)\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "    de belastingvermindering per maand Bedrag voor elke maand;\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is groot kenmerk (bijvoeglijk);\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel R\n    geldig altijd\n        Een Vlucht is groot indien de belastingvermindering per maand van alle passagiers van de Vlucht gedurende het gehele jaar groter is dan 40.\n";
    // a full-year segment (1-1-2024 .. 1-1-2025) with value w — the whole-year check needs the full calendar year covered.
    static Passagier passJaar(Vlucht v, String w) { Passagier p = new Passagier(); p.setReis(v); p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, w))); return p; }
    static Fixture gpAlleFix(String naam, java.util.function.Function<Vlucht, List<Object>> bouw) {
        return new Fixture(naam, GP_ALLE_SRC, () -> bouw.apply(new Vlucht()), Map.of(), l -> ((Vlucht) l.get(0)).isGroot());
    }
    static final Fixture[] PHASE_GP_ALLE = {
        gpAlleFix("§8.2 forall > 40: all passengers 50,60 whole year -> groot", v -> List.of(v, passJaar(v, "50"), passJaar(v, "60"))),
        gpAlleFix("§8.2 forall > 40: one passenger 30 -> NIET groot (forall fails)", v -> List.of(v, passJaar(v, "50"), passJaar(v, "30"))),
        gpAlleFix("§8.2 forall > 40: a passenger with a GAP (not whole year) -> NIET groot", v -> List.of(v,
            passJaar(v, "50"),
            passOp(v, List.of(seg(2024, 1, 1, 2024, 6, 1, "60"))))),   // only half the year covered -> the bucket fails
        gpAlleFix("§8.2 forall > 40 over EMPTY set -> groot (vacuous)", v -> List.of(v)),
        gpAlleFix("§8.2 forall > 40: exactly 40 (not strictly greater) -> NIET groot", v -> List.of(v, passJaar(v, "40"))),
    };
    static final Object[] PHASE_GP_ALLE_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE,
    };

    // ---- §5.8/§13.4.14 a COUNT aggregation as one side of a CONDITION comparison ("Een Vlucht is vol indien het aantal
    //      passagiers van de Vlucht <op> N"): was a symmetric marker ("voorwaarde buiten Phase-B scope"). Now a result-
    //      constrained accumulate CE hoisted after the subject pattern — DRL `Number(intValue <op> N) from accumulate(
    //      $e : Passagier(reis == $subject), count($e))` <-> the exec Gate.extra [accumulate CE + result-var constraint].
    //      Scoped to AANTAL + a literal threshold. ----
    static final String AGG_COND_SRC =
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien het aantal passagiers van de Vlucht %s.\n";
    static Fixture aggCondFix(String naam, String opFrase, int nPass) {
        String src = String.format(AGG_COND_SRC, opFrase);
        return new Fixture(naam, src,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); fs.add(v);
                        for (int i = 0; i < nPass; i++) { Passagier p = new Passagier(); p.setReis(v); fs.add(p); } return fs; },
                Map.of(), l -> ((Vlucht) l.get(0)).isVol());
    }
    static final Fixture[] PHASE_AGG_COND = {
        aggCondFix("§5.8 count > 3: 4 passagiers -> vol", "groter is dan 3", 4),
        aggCondFix("§5.8 count > 3: 3 passagiers -> NIET vol (strikt)", "groter is dan 3", 3),
        aggCondFix("§5.8 count > 3: 0 passagiers -> NIET vol", "groter is dan 3", 0),
        aggCondFix("§5.8 count >= 4: 4 passagiers -> vol (grens)", "groter of gelijk is aan 4", 4),
        aggCondFix("§5.8 count >= 4: 3 passagiers -> NIET vol", "groter of gelijk is aan 4", 3),
        aggCondFix("§5.8 count == 2: 2 passagiers -> vol", "gelijk is aan 2", 2),
        aggCondFix("§5.8 count == 2: 3 passagiers -> NIET vol", "gelijk is aan 2", 3),
        aggCondFix("§5.8 count < 1: 0 passagiers -> vol", "kleiner is dan 1", 0),
        aggCondFix("§5.8 count < 1: 1 passagier -> NIET vol", "kleiner is dan 1", 1),
    };
    static final Object[] PHASE_AGG_COND_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE,
    };

    // ---- §5.8 VALUE-FOLD aggregations (SUM / TOTAAL / MIN / MAX) as one side of a CONDITION comparison — the sibling of
    //      the count case: reuses the value-side accumulate lowering + an rsAggVergelijk eval / result-var constraint. The
    //      KEY absolute check (differential-masks-shared-bugs) is the empty-set leegwaarde: an empty SOM is LEEG (§5.8.2,
    //      "SUM < 100" over 0 elements -> onwaar), an empty TOTAAL is 0 (§4.20, "TOTAAL < 100" over 0 elements -> waar),
    //      an empty MIN/MAX is leeg. ----
    static final String AGG_FOLD_SRC =
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de leeftijd Numeriek (geheel getal);\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien %s.\n";
    static Fixture aggFoldFix(String naam, String cond, int[] leeftijds) {
        String src = String.format(AGG_FOLD_SRC, cond);
        return new Fixture(naam, src,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); fs.add(v);
                        for (int lt : leeftijds) { Passagier p = new Passagier(); p.setLeeftijd(new BigDecimal(lt)); p.setReis(v); fs.add(p); } return fs; },
                Map.of(), l -> ((Vlucht) l.get(0)).isVol());
    }
    static final String SOM_GT = "de som van de leeftijd van alle passagiers van de Vlucht groter is dan 100";
    static final String MAX_GT = "de maximale waarde van de leeftijd van alle passagiers van de Vlucht groter is dan 65";
    static final String MIN_LT = "de minimale waarde van de leeftijd van alle passagiers van de Vlucht kleiner is dan 12";
    static final Fixture[] PHASE_AGG_FOLD = {
        aggFoldFix("§5.8.2 SUM > 100: 50+60=110 -> vol", SOM_GT, new int[]{50, 60}),
        aggFoldFix("§5.8.2 SUM > 100: 50+40=90 -> niet", SOM_GT, new int[]{50, 40}),
        aggFoldFix("§5.8.2 SUM < 100 over EMPTY -> niet (empty SOM is LEEG, not 0)",
                "de som van de leeftijd van alle passagiers van de Vlucht kleiner is dan 100", new int[]{}),
        aggFoldFix("§5.8.3 MAX > 65: max(30,70)=70 -> vol", MAX_GT, new int[]{30, 70}),
        aggFoldFix("§5.8.3 MAX > 65: max(30,60)=60 -> niet", MAX_GT, new int[]{30, 60}),
        aggFoldFix("§5.8.3 MIN < 12: min(30,8)=8 -> vol", MIN_LT, new int[]{30, 8}),
        aggFoldFix("§5.8.3 MIN < 12 over EMPTY -> niet (empty MIN is LEEG)", MIN_LT, new int[]{}),
        aggFoldFix("§4.20 TOTAAL > 100: 60+60=120 -> vol", "het totaal van de leeftijd van alle passagiers van de Vlucht groter is dan 100", new int[]{60, 60}),
        aggFoldFix("§4.20 TOTAAL < 100 over EMPTY -> VOL (empty TOTAAL is 0, NOT leeg — the SOM/TOTAAL split)",
                "het totaal van de leeftijd van alle passagiers van de Vlucht kleiner is dan 100", new int[]{}),
    };
    static final Object[] PHASE_AGG_FOLD_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE,
        Boolean.TRUE, Boolean.TRUE,
    };

    // ---- §5.8 an aggregation in a condition compared to a NON-LITERAL threshold (an OWN attribute of the subject:
    //      "het aantal passagiers van de Vlucht groter is dan de vertrektijd van de Vlucht"). The DRL eval reads the
    //      $subject-qualified getter; the exec binds the subject var in the result-var constraint and reads it. ----
    static final String AGG_RHS_SRC =
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de leeftijd Numeriek (geheel getal);\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n    de vertrektijd Numeriek (geheel getal);\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien %s.\n";
    static Fixture aggRhsFix(String naam, String cond, int drempel, int[] leeftijds) {
        String src = String.format(AGG_RHS_SRC, cond);
        return new Fixture(naam, src,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); v.setVertrektijd(new BigDecimal(drempel)); fs.add(v);
                        for (int lt : leeftijds) { Passagier p = new Passagier(); p.setLeeftijd(new BigDecimal(lt)); p.setReis(v); fs.add(p); } return fs; },
                Map.of(), l -> ((Vlucht) l.get(0)).isVol());
    }
    static final String CNT_GT_ATTR = "het aantal passagiers van de Vlucht groter is dan de vertrektijd van de Vlucht";
    static final String SOM_GT_ATTR = "de som van de leeftijd van alle passagiers van de Vlucht groter is dan de vertrektijd van de Vlucht";
    static final Fixture[] PHASE_AGG_RHS = {
        aggRhsFix("§5.8 count > own-attr: 4 > 3 -> vol", CNT_GT_ATTR, 3, new int[]{1, 1, 1, 1}),
        aggRhsFix("§5.8 count > own-attr: 3 > 3 -> niet (strikt)", CNT_GT_ATTR, 3, new int[]{1, 1, 1}),
        aggRhsFix("§5.8 SUM > own-attr: 110 > 100 -> vol", SOM_GT_ATTR, 100, new int[]{50, 60}),
        aggRhsFix("§5.8 SUM > own-attr: 90 > 100 -> niet", SOM_GT_ATTR, 100, new int[]{50, 40}),
    };
    static final Object[] PHASE_AGG_RHS_EXPECTED = { Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE };

    // ---- §5 Tabel 37 GELIJK leegwaarde for an aggregation-in-condition (review wf_900d3055-a62 F1): an aggregation
    //      GELIJK a threshold where BOTH are leeg -> WAAR (Numeriek gelijk aan); one leeg -> onwaar. An empty SOM is leeg,
    //      a null own-attr threshold is leeg. rsAggVergelijk must return WAAR for op="==" both-null. ----
    static Fixture aggGelijkFix(String naam, Integer vt, int[] leeftijds) {
        String src = String.format(AGG_RHS_SRC, "de som van de leeftijd van alle passagiers van de Vlucht gelijk is aan de vertrektijd van de Vlucht");
        return new Fixture(naam, src,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); if (vt != null) v.setVertrektijd(new BigDecimal(vt)); fs.add(v);
                        for (int lt : leeftijds) { Passagier p = new Passagier(); p.setLeeftijd(new BigDecimal(lt)); p.setReis(v); fs.add(p); } return fs; },
                Map.of(), l -> ((Vlucht) l.get(0)).isVol());
    }
    static final Fixture[] PHASE_AGG_GELIJK = {
        aggGelijkFix("§5 Tabel37 SOM(leeg) == vt(leeg) -> WAAR (both-leeg)", null, new int[]{}),
        aggGelijkFix("§5 SOM(5) == vt(leeg) -> onwaar (X==leeg)", null, new int[]{5}),
        aggGelijkFix("§5 SOM(leeg) == vt(0) -> onwaar (leeg==Y)", 0, new int[]{}),
        aggGelijkFix("§5 SOM(5) == vt(5) -> WAAR", 5, new int[]{5}),
        aggGelijkFix("§5 SOM(5) == vt(6) -> onwaar", 6, new int[]{5}),
    };
    static final Object[] PHASE_AGG_GELIJK_EXPECTED = { Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE };

    // ---- §5.6 Tabel 44 ONGELIJK ("ongelijk is aan") for an aggregation-in-condition: was a symmetric marker (the ONGELIJK
    //      gate in both aggregatieVoorwaarde), now FIRES. Both-gevuld a!=b, and the leeg semantics: leeg!=gevuld -> WAAR,
    //      leeg!=leeg -> onwaar (Tabel 44, the inverse of the GELIJK Tabel 37 above). rsAggVergelijk now handles op "!=". ----
    static Fixture aggOngelijkFix(String naam, Integer vt, int[] leeftijds) {
        String src = String.format(AGG_RHS_SRC, "de som van de leeftijd van alle passagiers van de Vlucht ongelijk is aan de vertrektijd van de Vlucht");
        return new Fixture(naam, src,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); if (vt != null) v.setVertrektijd(new BigDecimal(vt)); fs.add(v);
                        for (int lt : leeftijds) { Passagier p = new Passagier(); p.setLeeftijd(new BigDecimal(lt)); p.setReis(v); fs.add(p); } return fs; },
                Map.of(), l -> ((Vlucht) l.get(0)).isVol());
    }
    static final Fixture[] PHASE_AGG_ONGELIJK = {
        // count != literal (both-gevuld; an empty count is 0, still gevuld — never a leeg operand)
        aggCondFix("§5.6 count != 3: 4 passagiers -> WAAR (4!=3)", "ongelijk is aan 3", 4),
        aggCondFix("§5.6 count != 3: 3 passagiers -> onwaar (3!=3)", "ongelijk is aan 3", 3),
        aggCondFix("§5.6 count != 3: 0 passagiers -> WAAR (0!=3)", "ongelijk is aan 3", 0),
        // SOM != own-attr, both gevuld
        aggOngelijkFix("§5.6 SOM(5) != vt(6) -> WAAR", 6, new int[]{5}),
        aggOngelijkFix("§5.6 SOM(5) != vt(5) -> onwaar", 5, new int[]{5}),
        // §5.6 Tabel 44 leeg corners (a SOM binds count -> the empty-set aggregate fires with a null value)
        aggOngelijkFix("§5.6 Tabel44 SOM(leeg) != vt(leeg) -> onwaar (both-leeg)", null, new int[]{}),
        aggOngelijkFix("§5.6 Tabel44 SOM(5) != vt(leeg) -> WAAR (X!=leeg)", null, new int[]{5}),
        aggOngelijkFix("§5.6 Tabel44 SOM(leeg) != vt(0) -> WAAR (leeg!=Y)", 0, new int[]{}),
    };
    static final Object[] PHASE_AGG_ONGELIJK_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE,
        Boolean.TRUE, Boolean.FALSE,
        Boolean.FALSE, Boolean.TRUE, Boolean.TRUE,
    };

    // ---- §5.8 an aggregation-in-condition vs a numeric scalar Parameter-GLOBAL threshold ("... groter is dan de grens"):
    //      was a symmetric marker (the exec rejected a non-empty rhs.globals(); the DRL gate rejected a ParameterRef). Now
    //      the DRL reads the header-declared global by name, and the exec binds the Global directly into the result
    //      constraint (the null-tolerant single-global idiom). The CRITICAL corner: an UNSET (leeg) global must still fire
    //      the rule (bind null, not drop) — count>leeg -> onwaar (ordering), count!=leeg -> WAAR (Tabel 44 one-leeg). ----
    static final String AGG_GLOBAL_SRC =
        "Parameter de grens : Numeriek (geheel getal);\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de leeftijd Numeriek (geheel getal);\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien %s.\n";
    static Fixture aggGlobalFix(String naam, String cond, Integer grens, int[] leeftijds) {
        String src = String.format(AGG_GLOBAL_SRC, cond);
        Map<String, Object> g = grens == null ? Map.of() : Map.of("grens", new BigDecimal(grens));   // absent key -> UNSET (leeg) global
        return new Fixture(naam, src,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); fs.add(v);
                        for (int lt : leeftijds) { Passagier p = new Passagier(); p.setLeeftijd(new BigDecimal(lt)); p.setReis(v); fs.add(p); } return fs; },
                g, l -> ((Vlucht) l.get(0)).isVol());
    }
    static final String CNT_GT_G = "het aantal passagiers van de Vlucht groter is dan de grens";
    static final String CNT_NE_G = "het aantal passagiers van de Vlucht ongelijk is aan de grens";
    static final String SOM_GT_G = "de som van de leeftijd van alle passagiers van de Vlucht groter is dan de grens";
    static final Fixture[] PHASE_AGG_GLOBAL = {
        aggGlobalFix("§5.8 count > grens(3): 4 -> WAAR", CNT_GT_G, 3, new int[]{1, 1, 1, 1}),
        aggGlobalFix("§5.8 count > grens(3): 3 -> onwaar", CNT_GT_G, 3, new int[]{1, 1, 1}),
        aggGlobalFix("§5.8 SOM > grens(100): 110 -> WAAR", SOM_GT_G, 100, new int[]{50, 60}),
        aggGlobalFix("§5.8 SOM > grens(100): 90 -> onwaar", SOM_GT_G, 100, new int[]{50, 40}),
        aggGlobalFix("§5.6 count != grens(3): 4 -> WAAR", CNT_NE_G, 3, new int[]{1, 1, 1, 1}),
        aggGlobalFix("§5.6 count != grens(3): 3 -> onwaar", CNT_NE_G, 3, new int[]{1, 1, 1}),
        aggGlobalFix("§5 count > grens(LEEG): threshold leeg -> onwaar (ordering)", CNT_GT_G, null, new int[]{1, 1, 1, 1}),
        aggGlobalFix("§5.6 count != grens(LEEG): count gevuld != leeg -> WAAR (Tabel 44)", CNT_NE_G, null, new int[]{1, 1, 1, 1}),
        aggGlobalFix("§5.8.2 SOM(leeg) > grens(100): empty-set aggregate leeg -> onwaar", SOM_GT_G, 100, new int[]{}),
    };
    static final Object[] PHASE_AGG_GLOBAL_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE,
        Boolean.FALSE, Boolean.TRUE,
        Boolean.FALSE,
    };

    // ---- §5.8.3 an empty MIN/MAX in a CONDITION is LEEG (§5.3 Tabel 37 / §5.6 Tabel 44). A bare min()/max() accumulate
    //      binds NO result row over an empty source, so the compare silently read onwaar (review wf_5c07914a-647 CONFIRMED,
    //      closing 20260701-min-max-empty-set-gelijk-leeg). The condition-path accumulate now also binds count() (leegCount),
    //      so a row exists for the empty set with a null aggregate -> GELIJK leeg==leeg WAAR, ONGELIJK leeg!=gevuld WAAR,
    //      ordering stays onwaar. SOM already fired this (it bound count); TOTAAL empty is 0. VALUE-side MIN/MAX unchanged. ----
    static Fixture aggMinMaxFix(String naam, String cond, Integer vt, int[] leeftijds) {
        String src = String.format(AGG_RHS_SRC, cond);
        return new Fixture(naam, src,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); if (vt != null) v.setVertrektijd(new BigDecimal(vt)); fs.add(v);
                        for (int lt : leeftijds) { Passagier p = new Passagier(); p.setLeeftijd(new BigDecimal(lt)); p.setReis(v); fs.add(p); } return fs; },
                Map.of(), l -> ((Vlucht) l.get(0)).isVol());
    }
    static final String MAX_NE_ATTR = "de maximale waarde van de leeftijd van alle passagiers van de Vlucht ongelijk is aan de vertrektijd van de Vlucht";
    static final String MAX_EQ_ATTR = "de maximale waarde van de leeftijd van alle passagiers van de Vlucht gelijk is aan de vertrektijd van de Vlucht";
    static final String MIN_NE_ATTR = "de minimale waarde van de leeftijd van alle passagiers van de Vlucht ongelijk is aan de vertrektijd van de Vlucht";
    static final Fixture[] PHASE_AGG_MINMAX_LEEG = {
        aggMinMaxFix("§5.6 Tabel44 MAX(leeg) != vt(5) -> WAAR (leeg!=gevuld)", MAX_NE_ATTR, 5, new int[]{}),
        aggMinMaxFix("§5.6 Tabel44 MAX(leeg) != vt(leeg) -> onwaar (leeg!=leeg)", MAX_NE_ATTR, null, new int[]{}),
        aggMinMaxFix("§5.3 Tabel37 MAX(leeg) == vt(leeg) -> WAAR (leeg==leeg)", MAX_EQ_ATTR, null, new int[]{}),
        aggMinMaxFix("§5.6 Tabel44 MIN(leeg) != vt(5) -> WAAR", MIN_NE_ATTR, 5, new int[]{}),
        aggMinMaxFix("§5 MIN(leeg) < 12 -> onwaar (ordering over an empty MIN stays leeg)",
                "de minimale waarde van de leeftijd van alle passagiers van de Vlucht kleiner is dan 12", null, new int[]{}),
        aggMinMaxFix("§5.6 MAX(8) != vt(5) -> WAAR (non-empty control)", MAX_NE_ATTR, 5, new int[]{3, 8}),
        aggMinMaxFix("§5.6 MAX(5) != vt(5) -> onwaar (non-empty control)", MAX_NE_ATTR, 5, new int[]{3, 5}),
        aggMinMaxFix("§5.3 MAX(5) == vt(5) -> WAAR (non-empty control)", MAX_EQ_ATTR, 5, new int[]{3, 5}),
    };
    static final Object[] PHASE_AGG_MINMAX_LEEG_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE,
        Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE,
    };

    // ---- §5.8 an aggregation-in-condition vs a to-ONE NAVIGATED numeric threshold ("... groter is dan de capaciteit van
    //      zijn vliegtuig"): was a symmetric marker. The resolver encodes the POSSESSIVE form (navRol==null, objecttype=the
    //      TARGET type != subject, a real numeric attr); the DRL reads it null-safely via rsRelGetal($subject.get<Rol>(),
    //      "attr"), the exec via navReader (both: absent relation -> leeg). The explicit-double-"van" form
    //      ("...van het vliegtuig van de Vlucht") stays an Onbekend/objecttype==subject marker (a resolver limit) — that is
    //      the F2 case in phase_AGG_navigated_rhs_is_symmetric_marker. The CRITICAL corner: an ABSENT relation reads leeg. ----
    static final String AGG_NAV_SRC =
        "Objecttype het Vliegtuig (mv: Vliegtuigen)\n    de capaciteit Numeriek (geheel getal);\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de leeftijd Numeriek (geheel getal);\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
        "Feittype toewijzing van vliegtuig\n    de vlucht Vlucht\n    het vliegtuig Vliegtuig\nÉén vlucht heeft één vliegtuig\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nÉén reis heeft meerdere passagiers\n" +
        "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien %s.\n";
    static Fixture aggNavFix(String naam, String cond, Integer cap, int[] leeftijds) {
        String src = String.format(AGG_NAV_SRC, cond);
        return new Fixture(naam, src,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); fs.add(v);
                        if (cap != null) { Vliegtuig vt = new Vliegtuig(); vt.setCapaciteit(new BigDecimal(cap)); v.setVliegtuig(vt); fs.add(vt); }
                        for (int lt : leeftijds) { Passagier p = new Passagier(); p.setLeeftijd(new BigDecimal(lt)); p.setReis(v); fs.add(p); } return fs; },
                Map.of(), l -> ((Vlucht) l.get(0)).isVol());
    }
    static final String CNT_GT_NAV = "het aantal passagiers van de Vlucht groter is dan de capaciteit van zijn vliegtuig";
    static final String CNT_NE_NAV = "het aantal passagiers van de Vlucht ongelijk is aan de capaciteit van zijn vliegtuig";
    static final String SOM_GT_NAV = "de som van de leeftijd van alle passagiers van de Vlucht groter is dan de capaciteit van zijn vliegtuig";
    static final Fixture[] PHASE_AGG_NAV = {
        aggNavFix("§5.8 count 4 > cap 3 -> WAAR", CNT_GT_NAV, 3, new int[]{1, 1, 1, 1}),
        aggNavFix("§5.8 count 3 > cap 3 -> onwaar", CNT_GT_NAV, 3, new int[]{1, 1, 1}),
        aggNavFix("§5.8 count 2 > cap(ABSENT) -> onwaar (navigated leeg, ordering)", CNT_GT_NAV, null, new int[]{1, 1}),
        aggNavFix("§5.6 count 2 != cap(ABSENT) -> WAAR (gevuld != leeg, Tabel44)", CNT_NE_NAV, null, new int[]{1, 1}),
        aggNavFix("§5.6 count 3 != cap 3 -> onwaar", CNT_NE_NAV, 3, new int[]{1, 1, 1}),
        aggNavFix("§5.6 count 4 != cap 3 -> WAAR", CNT_NE_NAV, 3, new int[]{1, 1, 1, 1}),
        aggNavFix("§5.8 SOM 110 > cap 100 -> WAAR", SOM_GT_NAV, 100, new int[]{50, 60}),
        aggNavFix("§5.8 SOM(empty) > cap 100 -> onwaar (empty SOM leeg)", SOM_GT_NAV, 100, new int[]{}),
    };
    static final Object[] PHASE_AGG_NAV_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE,
        Boolean.TRUE, Boolean.FALSE,
    };

    // ---- §5.8 agg-vs-agg: BOTH operands are aggregations over the subject's relation ("het aantal X <op> de som van ...").
    //      Two accumulate CEs + a two-result compare via rsAggVergelijk. The exec rides the 2nd accumulate result and
    //      references the 1st (upstream, valid cached-left; riding the 1st and referencing the downstream 2nd crashes
    //      PhreakAccumulateNode). Exercises Predicate3 (count/totaal both) / 4 (one count-bound: SOM or empty MIN·MAX) /
    //      5 (both count-bound), incl. the empty-set leeg corners (empty SOM/MIN·MAX leeg, empty TOTAAL/count 0). ----
    static final String AGG_CNT = "het aantal passagiers van de Vlucht";
    static final String AGG_SOM = "de som van de leeftijd van alle passagiers van de Vlucht";
    static final String AGG_TOT = "het totaal van de leeftijd van alle passagiers van de Vlucht";
    static final String AGG_MAXV = "de maximale waarde van de leeftijd van alle passagiers van de Vlucht";
    static final Fixture[] PHASE_AGG_VS_AGG = {
        aggFoldFix("§5.8 TOTAAL 30 > count 2 -> WAAR (Pred3)", AGG_TOT + " groter is dan " + AGG_CNT, new int[]{10, 20}),
        aggFoldFix("§5.8 count 2 > TOTAAL 30 -> onwaar", AGG_CNT + " groter is dan " + AGG_TOT, new int[]{10, 20}),
        aggFoldFix("§5.8 TOTAAL(0) == count(0) empty -> WAAR (both 0)", AGG_TOT + " gelijk is aan " + AGG_CNT, new int[]{}),
        aggFoldFix("§5.8 SOM 30 > count 2 -> WAAR (Pred4 c1)", AGG_SOM + " groter is dan " + AGG_CNT, new int[]{10, 20}),
        aggFoldFix("§5.8 count 2 > SOM 30 -> onwaar (Pred4 c2)", AGG_CNT + " groter is dan " + AGG_SOM, new int[]{10, 20}),
        aggFoldFix("§5.8 SOM(leeg) == count(0) empty -> onwaar (leeg vs 0)", AGG_SOM + " gelijk is aan " + AGG_CNT, new int[]{}),
        aggFoldFix("§5.8 SOM 30 > MAX 20 -> WAAR (Pred5)", AGG_SOM + " groter is dan " + AGG_MAXV, new int[]{10, 20}),
        aggFoldFix("§5.8 SOM 20 > MAX 20 -> onwaar (=)", AGG_SOM + " groter is dan " + AGG_MAXV, new int[]{20}),
        aggFoldFix("§5.3 SOM(leeg) == MAX(leeg) empty -> WAAR (both-leeg Tabel37)", AGG_SOM + " gelijk is aan " + AGG_MAXV, new int[]{}),
    };
    static final Object[] PHASE_AGG_VS_AGG_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE,
        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE,
    };

    // ---- §8.1.1 an aggregation-in-condition vs a RHS arithmetic EXPRESSION reading 2 DISTINCT scalar Parameter-globals
    //      ("het aantal passagiers van de Vlucht groter is dan de grensa plus de grensb"): was a SYMMETRIC marker (DRL
    //      /* TODO */, exec "voorwaarde buiten Phase-B scope"). Now the DRL reads each global as a free mvel name in the
    //      eval, the exec binds each into its OWN Optional from-declaration (aggMultiGlobalRhs, the 88ef44f nGlobalGate
    //      idiom — two globals in ONE exec-model expr collapse to the last). A LEEG global INSIDE the +/- is leeg-as-0
    //      (§6.2, matching PHASE_EXPR_MULTI_GLOBAL's "toeslag LEEG inside arith" row). Capped at 2 distinct globals
    //      (exec Predicate5 arity ceiling for a count-bound SOM); 3+ stays a symmetric marker. ----
    static final String AGG_2G_SRC =
        "Parameter de grensa : Numeriek (geheel getal)\nParameter de grensb : Numeriek (geheel getal)\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien het aantal passagiers van de Vlucht groter is dan de grensa plus de grensb.\n";
    static Fixture agg2gFix(String naam, Integer ga, Integer gb, int nPass) {
        Map<String, Object> g = new java.util.HashMap<>();
        if (ga != null) g.put("grensa", new BigDecimal(ga));
        if (gb != null) g.put("grensb", new BigDecimal(gb));   // an absent key -> UNSET (leeg) global -> leeg-as-0 in the +/-
        return new Fixture(naam, AGG_2G_SRC,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); fs.add(v);
                        for (int i = 0; i < nPass; i++) { Passagier p = new Passagier(); p.setReis(v); fs.add(p); } return fs; },
                g, l -> ((Vlucht) l.get(0)).isVol());
    }
    static final Fixture[] PHASE_AGG_MULTI_GLOBAL = {
        agg2gFix("§8.1.1 count 4 > grensa(2)+grensb(1)=3 -> WAAR", 2, 1, 4),
        agg2gFix("§8.1.1 count 3 > grensa(2)+grensb(1)=3 -> onwaar (strikt)", 2, 1, 3),
        agg2gFix("§8.1.1 count 6 > grensa(2)+grensb(3)=5 -> WAAR", 2, 3, 6),
        // grensb LEEG inside the +/- -> leeg-as-0 (§6.2): threshold = grensa(2)+0 = 2. count 3 > 2 -> WAAR.
        agg2gFix("§6.2 count 3 > grensa(2)+grensb(LEEG=0)=2 -> WAAR (leeg-as-0)", 2, null, 3),
        // both LEEG -> threshold 0. count 1 > 0 -> WAAR; count 0 > 0 -> onwaar.
        agg2gFix("§6.2 count 1 > grensa(LEEG)+grensb(LEEG)=0 -> WAAR", null, null, 1),
        agg2gFix("§6.2 count 0 > grensa(LEEG)+grensb(LEEG)=0 -> onwaar", null, null, 0),
    };
    static final Object[] PHASE_AGG_MULTI_GLOBAL_EXPECTED = {
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE };

    // ---- §13.4.14.4 a SINGLE aggregation bullet under a NON-ALLE counting quantifier ("indien de Vlucht aan GEEN /
    //      ten minste 1 van de volgende voorwaarden voldoet: - het aantal passagiers … groter is dan 10"): was a broken
    //      lowering (DRL emitted an uncompilable "!(())" AND leaked the accumulate; exec SKIPPED). A single-bullet
    //      counting quantifier reduces to a plain boolean transform of the one leaf — GEEN = NOT the leaf, ten minste 1 =
    //      the leaf — so it routes through the aggregation lowering with a boolean-NEGATION flag for GEEN. Negating the
    //      rsAggVergelijk RESULT (an eval-level "!( … )" / a "neg != …" XOR), NOT the operator, is leegwaarde-correct:
    //      NOT(SOM(leeg) > 10) is WAAR (§5.8.2 empty SOM leeg -> §5 ordering onwaar -> NOT waar), whereas the operator-flip
    //      "SOM(leeg) <= 10" would read onwaar. The empty-set count is 0 (§5.8). >1 bullet / ten hoogste / precies stay
    //      symmetric markers (phase_AGG_quantifier_multibullet_is_symmetric_marker). ----
    static final String AGG_QUANT_SRC =
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de leeftijd Numeriek (geheel getal);\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien de Vlucht aan %s van de volgende voorwaarden voldoet:\n        - %s.\n";
    static Fixture aggQuantFix(String naam, String kwantor, String bullet, int[] leeftijds) {
        String src = String.format(AGG_QUANT_SRC, kwantor, bullet);
        return new Fixture(naam, src,
                () -> { List<Object> fs = new ArrayList<>(); Vlucht v = new Vlucht(); fs.add(v);
                        for (int lt : leeftijds) { Passagier p = new Passagier(); p.setLeeftijd(new BigDecimal(lt)); p.setReis(v); fs.add(p); } return fs; },
                Map.of(), l -> ((Vlucht) l.get(0)).isVol());
    }
    static final String CNT_GT_10 = "het aantal passagiers van de Vlucht groter is dan 10";
    static final String SOM_GT_10 = "de som van de leeftijd van alle passagiers van de Vlucht groter is dan 10";
    static int[] nPax(int n) { int[] a = new int[n]; java.util.Arrays.fill(a, 1); return a; }
    static final Fixture[] PHASE_AGG_QUANTIFIER = {
        // GEEN(count > 10): fire iff NOT(count > 10). 11 -> onwaar; 5 -> WAAR; empty (count 0) -> WAAR (0 > 10 is onwaar).
        aggQuantFix("GEEN(count > 10): 11 passagiers -> NIET vol", "geen", CNT_GT_10, nPax(11)),
        aggQuantFix("GEEN(count > 10): 5 passagiers -> vol", "geen", CNT_GT_10, nPax(5)),
        aggQuantFix("GEEN(count > 10): 0 passagiers (empty set count 0) -> vol", "geen", CNT_GT_10, nPax(0)),
        // ten minste 1(count > 10): fire iff count > 10 (single bullet). 11 -> WAAR; 5 -> onwaar; empty -> onwaar.
        aggQuantFix("ten minste 1(count > 10): 11 passagiers -> vol", "ten minste 1", CNT_GT_10, nPax(11)),
        aggQuantFix("ten minste 1(count > 10): 5 passagiers -> NIET vol", "ten minste 1", CNT_GT_10, nPax(5)),
        aggQuantFix("ten minste 1(count > 10): 0 passagiers -> NIET vol", "ten minste 1", CNT_GT_10, nPax(0)),
        // GEEN(SOM > 10) over the EMPTY set — the leegwaarde soundness check: empty SOM is LEEG (§5.8.2), SOM(leeg) > 10 is
        // onwaar (§5 ordering), so NOT(onwaar) -> WAAR. An operator-flip (SOM(leeg) <= 10) would read onwaar -> the wrong
        // gate. This row is the whole reason CORNER 2 negates the RESULT, not the operator.
        aggQuantFix("GEEN(SOM > 10) over EMPTY -> vol (NOT(leeg>10)=WAAR, the leegwaarde soundness pin)", "geen", SOM_GT_10, new int[]{}),
        aggQuantFix("GEEN(SOM > 10): 5+7=12 > 10 -> NIET vol", "geen", SOM_GT_10, new int[]{5, 7}),
        aggQuantFix("GEEN(SOM > 10): 3+4=7 > 10 onwaar -> vol", "geen", SOM_GT_10, new int[]{3, 4}),
        // ten minste 1(SOM > 10) over EMPTY -> onwaar (SOM(leeg)>10 onwaar) -> NIET vol.
        aggQuantFix("ten minste 1(SOM > 10) over EMPTY -> NIET vol", "ten minste 1", SOM_GT_10, new int[]{}),
    };
    static final Object[] PHASE_AGG_QUANTIFIER_EXPECTED = {
        Boolean.FALSE, Boolean.TRUE, Boolean.TRUE,
        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE,
        Boolean.FALSE,
    };

    // ---- review wf_900d3055-a62 F2: an aggregation-in-condition vs a NAVIGATED RHS ("het aantal passagiers van de Vlucht
    //      groter is dan de afstand van de luchthaven van de Vlucht") must stay a SYMMETRIC marker — DRL markers, exec SKIPS
    //      (was: DRL markers but exec built a LIVE rule that THREW at fireAllRules). ----
    static final String AGG_NAV_RHS_SRC =
        "Objecttype de Luchthaven (mv: Luchthavens)\n    de afstand Numeriek (geheel getal);\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Feittype vertrek van vlucht\n    de vlucht Vlucht\n    de luchthaven Luchthaven\nEén vlucht heeft één luchthaven\n" +
        "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien het aantal passagiers van de Vlucht groter is dan de afstand van de luchthaven van de Vlucht.\n";

    static final Fixture[] PHASE_F = {
        // A1 §7.1: the total weights each tijdvak by its month span — 12×10 + 6×11 = 186.
        single("§7.1 totaal van temporeel attribuut (12×10 + 6×11 = 186)",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel Totale belastingvermindering
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op het totaal van zijn belastingvermindering per maand.
            """,
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(
                    seg(2023, 1, 1, 2024, 1, 1, "10.00"), seg(2024, 1, 1, 2024, 7, 1, "11.00")))),
            Map.of(),
            Passagier::getTotaleBelastingvermindering),   // 186.00

        // A2 §7.1: "gedurende de tijd dat hij rechthebbend is" clips to the bijvoeglijk kenmerk's true periods — 6×10 + 1×11 = 71.
        single("§7.1 gedurende (bijvoeglijk kenmerk) klipt (6×10 + 1×11 = 71)",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                is rechthebbend kenmerk (bijvoeglijk) voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat hij rechthebbend is).
            """,
            () -> passagier(p -> {
                p.setBelastingverminderingPerMaand(List.of(seg(2023, 1, 1, 2024, 1, 1, "10.00"), seg(2024, 1, 1, 2024, 7, 1, "11.00")));
                p.setRechthebbend(List.of(seg(2023, 7, 1, 2024, 2, 1, null)));
            }),
            Map.of(),
            Passagier::getTotaleBelastingvermindering),   // 71.00

        // A3 §7.1: "gedurende ... een recht ... heeft" clips on a bezittelijk kenmerk — 71.
        single("§7.1 gedurende (bezittelijk kenmerk, heeft) klipt (71)",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """,
            () -> passagier(p -> {
                p.setBelastingverminderingPerMaand(List.of(seg(2023, 1, 1, 2024, 1, 1, "10.00"), seg(2024, 1, 1, 2024, 7, 1, "11.00")));
                p.setRechtOpBelastingvermindering(List.of(seg(2023, 7, 1, 2024, 2, 1, null)));
            }),
            Map.of(),
            Passagier::getTotaleBelastingvermindering),   // 71.00

        // A4 §8.1.8: the NEGATED "gedurende ... geen recht ... heeft" clips to the complement (false periods) — 6×10 + 5×11 = 115.
        single("§8.1.8 negated gedurende klipt het complement (6×10 + 5×11 = 115)",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat hij geen recht op belastingvermindering heeft).
            """,
            () -> passagier(p -> {
                p.setBelastingverminderingPerMaand(List.of(seg(2023, 1, 1, 2024, 1, 1, "10.00"), seg(2024, 1, 1, 2024, 7, 1, "11.00")));
                p.setRechtOpBelastingvermindering(List.of(seg(2023, 7, 1, 2024, 2, 1, null)));
            }),
            Map.of(),
            Passagier::getTotaleBelastingvermindering),   // 115.00

        // A22 §7.1: "het totaal van de <attr> van zijn partner" reads a to-one navigated timeline, root-guarded — 120 / null.
        new Fixture("§7.1 navigated totaal van zijn partner (12×10 = 120 / partnerloos = null)",
            TOTAAL_HDR + """
            Objecttype de Persoon (mv: Personen) (bezield)
                de bedrag per maand Bedrag voor elke maand;
                de totaal partner bedrag Bedrag;
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel R
                geldig altijd
                    De totaal partner bedrag van een Persoon moet gesteld worden op het totaal van de bedrag per maand van zijn partner.
            """,
            () -> {
                Persoon p1 = new Persoon(), p2 = new Persoon();
                p1.setPartner(p2);
                p2.setBedragPerMaand(List.of(seg(2023, 1, 1, 2024, 1, 1, "10.00")));
                return List.of(p1, p2);
            },
            Map.of(),
            l -> vals(((Persoon) l.get(0)).getTotaalPartnerBedrag(), ((Persoon) l.get(1)).getTotaalPartnerBedrag())),   // 120.00, null

        // A23 §4.20: an empty timeline totals 0; a null timeline totals leeg (null) — no crash.
        new Fixture("§4.20 totaal van lege tijdreeks = 0 / null tijdreeks = leeg",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel Totale belastingvermindering
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op het totaal van zijn belastingvermindering per maand.
            """,
            () -> {
                Passagier leeg = passagier(p -> p.setBelastingverminderingPerMaand(List.of()));
                Passagier nul = new Passagier();
                return List.of(leeg, nul);
            },
            Map.of(),
            l -> vals(((Passagier) l.get(0)).getTotaleBelastingvermindering(), ((Passagier) l.get(1)).getTotaleBelastingvermindering())),   // 0, null

        // EUR71 §7.1 "gedurende de tijd dat <temporeel attr> <op> <scalar>": clip the value timeline to the periods
        // the comparison holds (its OWN boolean timeline). bv {Jan-Apr: 1200 (3 mnd), Apr-Jun: 800 (2 mnd)}; the
        // comparison ">1000" keeps only the first segment -> rsTijdTotaal(1200×3) = 3600.
        single("§7.1 EUR71 gedurende (temporal comparison vs literal) klipt (1200×3 = 3600)",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat zijn belastingvermindering per maand groter is dan 1000).
            """,
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(
                    seg(2024, 1, 1, 2024, 4, 1, "1200.00"), seg(2024, 4, 1, 2024, 6, 1, "800.00")))),
            Map.of(),
            Passagier::getTotaleBelastingvermindering),   // 3600.00

        // EUR71 vs a Parameter-GLOBAL grens (exercises the exec WaardeFn.globals propagation): same 3600.
        single("§7.1 EUR71 gedurende (temporal comparison vs global) klipt (1200×3 = 3600)",
            "Parameter de grens : Numeriek (geheel getal)\n" + TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat zijn belastingvermindering per maand groter is dan de grens).
            """,
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(
                    seg(2024, 1, 1, 2024, 4, 1, "1200.00"), seg(2024, 4, 1, 2024, 6, 1, "800.00")))),
            Map.of("grens", new BigDecimal(1000)),
            Passagier::getTotaleBelastingvermindering),   // 3600.00

        // §7.1 a NAVIGATED temporal kenmerk inside gedurende ("... gedurende de tijd dat zijn partner een recht op
        // korting heeft"): clip the subject's value timeline to the to-one partner's kenmerk true-periods, root-guarded.
        // p1.bedrag {Jan-Jul: 10 (6 mnd)} clipped to partner p2's rechtOpKorting {Mar-May (2 mnd)} -> 10×2 = 20; p2 has
        // no bedrag (null) -> null.
        new Fixture("§7.1 navigated kenmerk gedurende (zijn partner recht op korting) klipt (10×2 = 20 / null)",
            TOTAAL_HDR + """
            Objecttype de Persoon (mv: Personen) (bezield)
                de bedrag per maand Bedrag voor elke maand;
                het recht op korting kenmerk (bezittelijk) voor elke maand;
                de totaal partner bedrag Bedrag;
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel R
                geldig altijd
                    De totaal partner bedrag van een Persoon moet gesteld worden op (het totaal van zijn bedrag per maand gedurende de tijd dat zijn partner een recht op korting heeft).
            """,
            () -> {
                Persoon p1 = new Persoon(), p2 = new Persoon();
                p1.setPartner(p2);
                p1.setBedragPerMaand(List.of(seg(2023, 1, 1, 2023, 7, 1, "10.00")));   // 6 mnd x 10
                p2.setRechtOpKorting(List.of(seg(2023, 3, 1, 2023, 5, 1, null)));      // Mar-May true (2 mnd)
                return List.of(p1, p2);
            },
            Map.of(),
            l -> vals(((Persoon) l.get(0)).getTotaalPartnerBedrag(), ((Persoon) l.get(1)).getTotaalPartnerBedrag())),   // 20.00, null

        // EUR71 grens forms beyond literal/global (review fixes). An OWN-attribute grens: "... groter is dan zijn
        // basistarief" — the grens lands in the THEN block, lowered value-side ($subject.getBasistarief()), NOT a
        // bare field name (which aborts the KieBase build). bv {Jan-Apr: 1200 (3 mnd), Apr-Jun: 800} > 1000 -> 3600.
        single("§7.1 EUR71 eigen-attribuut grens (1200×3 = 3600)",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                het basistarief Numeriek (geheel getal);
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat zijn belastingvermindering per maand groter is dan zijn basistarief).
            """,
            () -> passagier(p -> { p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 4, 1, "1200.00"), seg(2024, 4, 1, 2024, 6, 1, "800.00"))); p.setBasistarief(new BigDecimal(1000)); }),
            Map.of(),
            Passagier::getTotaleBelastingvermindering),   // 3600.00

        // §5.6 Tabel 44 ONGELIJK: a leeg (null-value) condition segment vs a gevulde grens is WAAR, so it must be
        // INCLUDED. Cross-attribute: bv {Jan-Jun: 100 (5 mnd)} gedurende inkomen {Jan-Apr LEEG, Apr-Jun 800} != 1000
        // -> both periods qualify (leeg!=1000=waar, 800!=1000=waar) -> 100×5 = 500 (not the 200 if leeg were dropped).
        single("§7.1 EUR71 ONGELIJK includeert de lege periode (Tabel 44) (100×5 = 500)",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de inkomen per maand Bedrag voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat zijn inkomen per maand ongelijk is aan 1000).
            """,
            () -> passagier(p -> {
                p.setBelastingverminderingPerMaand(List.of(seg(2023, 1, 1, 2023, 6, 1, "100.00")));   // Jan-Jun: 100 (5 mnd)
                p.setInkomenPerMaand(List.of(seg(2023, 1, 1, 2023, 4, 1, null), seg(2023, 4, 1, 2023, 6, 1, "800.00")));  // Jan-Apr LEEG, Apr-Jun 800
            }),
            Map.of(),
            Passagier::getTotaleBelastingvermindering),   // 500.00

        // EUR71 a to-ONE NAVIGATED grens ("... groter is dan de vertrektijd van zijn reis"): the grens lowers
        // root-guarded value-side in BOTH back-ends (DRL waarde -> reis!.vertrektijd; exec scalarWaarde with
        // valueSubject set). 1200×3 = 3600 (vertrektijd 1000).
        new Fixture("§7.1 EUR71 genavigeerde grens (vertrektijd van zijn reis) (1200×3 = 3600)",
            TOTAAL_HDR + """
            Objecttype de Vlucht
                de vertrektijd Numeriek (geheel getal);
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de totale belastingvermindering Bedrag;
            """ + AGG_FEIT + """
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat zijn belastingvermindering per maand groter is dan de vertrektijd van zijn reis).
            """,
            () -> { Vlucht v = new Vlucht(); v.setVertrektijd(new BigDecimal(1000));
                    Passagier p = passagier(x -> x.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 4, 1, "1200.00"), seg(2024, 4, 1, 2024, 6, 1, "800.00")))); p.setReis(v);
                    return List.of(v, p); },
            Map.of(),
            l -> ((Passagier) l.get(1)).getTotaleBelastingvermindering()),   // 3600.00

        // §4 robustness: rsTijdTotaal must skip a zero-length (van==tot) or reversed (tot<van) segment — it
        // contributes 0, never a negative period count. {Jan-Jul: 10 (6 mnd)} + zero-length 100 + reversed 50 -> 60.
        single("§4 totaal negeert nul/negatieve-lengte segmenten (6×10 = 60)",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op het totaal van zijn belastingvermindering per maand.
            """,
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(
                    seg(2023, 1, 1, 2023, 7, 1, "10.00"),     // 6 mnd x 10 = 60
                    seg(2023, 8, 1, 2023, 8, 1, "100.00"),    // zero-length -> 0
                    seg(2023, 10, 1, 2023, 9, 1, "50.00")))), // reversed -> 0 (not -50)
            Map.of(),
            Passagier::getTotaleBelastingvermindering),   // 60.00
    };

    // ---- Phase F2 fixtures: §13.4.17.57 "het aantal dagen in <maand|jaar> dat <kenmerk>" — a temporal WRITE ----
    static final Fixture[] PHASE_F2 = {
        // A5 §7.2/§13.4.17.57: write a per-month true-day-count timeline — 23 dg in Jan (1 + 22), 13 dg in Feb [1-2,14-2).
        single("§13.4.17.57 aantal dagen in de maand (Jan 23 / Feb 13)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                het aantal dagen recht op belastingvermindering Numeriek (geheel getal) met eenheid dg voor elke maand;
            Regel Aantal dagen recht op belastingvermindering
                geldig altijd
                    Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat hij een recht op belastingvermindering heeft.
            """,
            () -> passagier(p -> p.setRechtOpBelastingvermindering(List.of(
                    seg(2024, 1, 1, 2024, 1, 2, null), seg(2024, 1, 10, 2024, 2, 14, null)))),
            Map.of(),
            p -> tl(p.getAantalDagenRechtOpBelastingvermindering())),   // [Jan→23, Feb→13]

        // §4.21 a null kenmerk timeline -> an empty result (no true days), no crash.
        single("§4.21 aantal dagen over een leeg kenmerk = lege tijdreeks",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                het aantal dagen recht op belastingvermindering Numeriek (geheel getal) met eenheid dg voor elke maand;
            Regel Aantal dagen recht op belastingvermindering
                geldig altijd
                    Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat hij een recht op belastingvermindering heeft.
            """,
            () -> new Passagier(),
            Map.of(),
            p -> tl(p.getAantalDagenRechtOpBelastingvermindering())),   // []
    };

    // ---- PHASE_STARTPUNT: §9.9/§13.4.12 startpuntbepaling + §3.8 tijdlijn met variabel startpunt — the doel's
    //      buckets align to the per-object anchor (the derived "startpunt <tijdlijn>" attribute the startpunt
    //      rule writes), not the calendar. Absolute values pinned by phase_STARTPUNT_..._spec_correct. ----
    private static final String STARTPUNT_HDR = """
            Tijdlijn de contractperiode heeft tijdvakken van een maand met variabel startpunt;

            Objecttype de Passagier (mv: Passagiers) (bezield)
                de geboortedatum Datum in dagen;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                het aantal dagen recht op belastingvermindering Numeriek (geheel getal) met eenheid dg voor elke contractperiode;

            Regel Aantal dagen recht op belastingvermindering
                geldig altijd
                    Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat hij een recht op belastingvermindering heeft.
            """;
    static final Fixture[] PHASE_STARTPUNT = {
        // §3.8: anchor 15-1 -> walls on the 15th. True [20-1, 5-3): [15-1,15-2)=26 dg, [15-2,15-3)=19 dg (leap Feb).
        single("§9.9 aantal dagen in een variabel-startpunt maandperiode (ankers op de 15e)",
            STARTPUNT_HDR + """
            Regel Startpunt contractperiode
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door zijn geboortedatum.
            """,
            () -> passagier(p -> { p.setGeboortedatum(LocalDate.of(2024, 1, 15));
                                   p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 20, 2024, 3, 5, null))); }),
            Map.of(),
            p -> tl(p.getAantalDagenRechtOpBelastingvermindering())),

        // toka-44 shape: the anchor is datum-arithmetic over a Parameter-global — 15-1 min 45 dg = 1-12-2023,
        // walls on the 1st: Jan 12 dg, Feb 29 dg (leap), Mar 4 dg.
        single("§9.9 anker = datum min Parameter-global (walls op de 1e)",
            "Parameter de wachttijd : Numeriek (geheel getal) met eenheid dg\n" + STARTPUNT_HDR + """
            Regel Startpunt contractperiode
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door zijn geboortedatum min de wachttijd.
            """,
            () -> passagier(p -> { p.setGeboortedatum(LocalDate.of(2024, 1, 15));
                                   p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 20, 2024, 3, 5, null))); }),
            Map.of("wachttijd", new BigDecimal(45)),
            p -> tl(p.getAantalDagenRechtOpBelastingvermindering())),

        // §6.8 a leeg anchor -> NO periodization (empty timeline, never silently calendar); the fout itself is
        // the companion's Inconsistentie (PHASE_STARTPUNT_FOUT).
        single("§6.8 leeg anker -> lege tijdreeks",
            STARTPUNT_HDR + """
            Regel Startpunt contractperiode
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door zijn geboortedatum.
            """,
            () -> passagier(p -> p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 20, 2024, 3, 5, null)))),
            Map.of(),
            p -> tl(p.getAantalDagenRechtOpBelastingvermindering())),
    };
    static final List<?>[] PHASE_STARTPUNT_EXPECTED = {
        List.of("2024-01-15|2024-02-15|26", "2024-02-15|2024-03-15|19"),
        List.of("2024-01-01|2024-02-01|12", "2024-02-01|2024-03-01|29", "2024-03-01|2024-04-01|4"),
        List.of(),
    };

    @Test void phase_STARTPUNT_anchored_buckets_are_spec_correct() {
        for (int i = 0; i < PHASE_STARTPUNT.length; i++) {
            Fixture f = PHASE_STARTPUNT[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_STARTPUNT_EXPECTED[i], viaDrl, () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // §6.8 rekentabel: a leeg anchor is a FOUT — the startpunt rule's companion inserts the Inconsistentie
    // (for exactly the leeg-anchored subject; an anchored subject is not flagged).
    static final FoutFixture[] PHASE_STARTPUNT_FOUT = {
        new FoutFixture("§6.8 leeg startpunt-anker -> Inconsistentie",
            STARTPUNT_HDR + """
            Regel Startpunt contractperiode
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door zijn geboortedatum.
            """,
            () -> {
                Passagier ok = passagier(p -> { p.setLeeftijd(new BigDecimal(1)); p.setGeboortedatum(LocalDate.of(2024, 1, 15)); });
                Passagier leeg = passagier(p -> p.setLeeftijd(new BigDecimal(2)));   // leeg anker -> flagged
                return List.of(ok, leeg);
            },
            o -> "leeftijd=" + ((Passagier) o).getLeeftijd()),
    };

    @Test void phase_STARTPUNT_matches_drl_back_end()       { runDifferential("Phase STARTPUNT", PHASE_STARTPUNT, 3); }
    @Test void phase_STARTPUNT_leeg_anker_fout_matches()    { runFoutDifferential("Phase STARTPUNT fout", PHASE_STARTPUNT_FOUT, 1); }

    // ---- Phase F3 fixtures: §13.4.17.58 "het tijdsevenredig deel per <maand|jaar> van <basis> gedurende ..." ----
    static final Fixture[] PHASE_F3 = {
        // A6 §7.3.2: per maand, OWN-attribute base — 12 full months ×12, 1 full ×18, 1 partial (7/28×18 = 4,50).
        single("§13.4.17.58 tijdsevenredig deel per maand, eigen basis (14 segmenten, laatste 4,50)",
            """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de standaardbedrag Bedrag voor elk jaar;
                de belastingvermindering Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel Belastingvermindering
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van zijn standaardbedrag gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """,
            () -> passagier(p -> {
                p.setStandaardbedrag(List.of(seg(2024, 1, 1, 2025, 1, 1, "12.00"), seg(2025, 1, 1, 2026, 1, 1, "18.00")));
                p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 1, 2025, 2, 8, null)));
            }),
            Map.of(),
            p -> tl(p.getBelastingvermindering())),

        // A11 §7.3.2 ex.1: the base is a TEMPORAL PARAMETER (a List<Segment> global) — same 14-segment result.
        single("§7.3.2 tijdsevenredig deel over een temporele PARAMETER-basis (List-global)",
            """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Parameter de STANDAARD BELASTINGVERMINDERING : Bedrag voor elk jaar
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel Belastingvermindering
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van de STANDAARD BELASTINGVERMINDERING gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """,
            () -> passagier(p -> p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 1, 2025, 2, 8, null)))),
            Map.of("standaardBelastingvermindering", List.of(seg(2024, 1, 1, 2025, 1, 1, "12.00"), seg(2025, 1, 1, 2026, 1, 1, "18.00"))),
            p -> tl(p.getBelastingvermindering())),

        // A12 §7.3.2 ex.2: per jaar, a non-terminating fraction kept EXACT (DECIMAL64), NOT rounded to doel decimals (18×38/365).
        single("§7.3.2 tijdsevenredig deel per jaar, niet-eindigende breuk exact (18×38/365)",
            """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de standaardbedrag Bedrag voor elk jaar;
                de jaardeel Bedrag voor elk jaar;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel Jaardeel
                geldig altijd
                    De jaardeel van een Passagier moet gesteld worden op (het tijdsevenredig deel per jaar van zijn standaardbedrag gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """,
            () -> passagier(p -> {
                p.setStandaardbedrag(List.of(seg(2025, 1, 1, 2026, 1, 1, "18.00")));
                p.setRechtOpBelastingvermindering(List.of(seg(2025, 1, 1, 2025, 2, 8, null)));
            }),
            Map.of(),
            p -> tl(p.getJaardeel())),

        // §7.3.2 ex.3 RATE CONVERSION: the base is €/jr, the doel €/mnd — the base is restated ÷12 (rsTijdSchaal, §7.3.1)
        // BEFORE proration. 120 €/jr -> 10 €/mnd full months 2024; 180 €/jr -> 15 full (jan-2025) and 15×7/28 = 3,75
        // (feb-2025). The ABSOLUTE values are pinned by DroolsExecutionTest; here the shared rsTijdSchaal stays at parity.
        single("§7.3.2 ex.3 tijdsevenredig met rate-omrekening (€/jr basis -> €/mnd doel, ÷12)",
            """
            Eenheidsysteem Geld
                de euro EUR
            Eenheidsysteem Tijd
                de maand mnd
                het jaar jr = 12 mnd
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de standaardbedrag Numeriek (geheel getal) met eenheid EUR/jr voor elk jaar;
                de belastingvermindering Numeriek (getal met 2 decimalen) met eenheid EUR/mnd voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel Belastingvermindering
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van zijn standaardbedrag gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """,
            () -> passagier(p -> {
                p.setStandaardbedrag(List.of(seg(2024, 1, 1, 2025, 1, 1, "120"), seg(2025, 1, 1, 2026, 1, 1, "180")));
                p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 1, 2025, 2, 8, null)));
            }),
            Map.of(),
            p -> tl(p.getBelastingvermindering())),

        // §7.3.2 a TO-ONE NAVIGATED base ("de bedrag per maand van zijn partner"): the PARTNER's temporal attribute is
        // prorated over the SUBJECT's gedurende, read root-guarded (a partnerloos persoon -> empty). The subject p1 has
        // NO own bedragPerMaand, so the 14-segment result (12×12 + 1×18 + 1×4,50) PROVES the navigated read reaches the
        // partner's value, not the subject's. Was a marker (gedurendeKenmerkFilter rejected a navigated base). p2 (no
        // partner, no recht) -> empty.
        new Fixture("§7.3.2 tijdsevenredig over a NAVIGATED base (de X van zijn partner) (14 segm / partnerloos leeg)",
            """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Persoon (mv: Personen) (bezield)
                de bedrag per maand Bedrag voor elke maand;
                de belastingvermindering Bedrag voor elke maand;
                het recht op korting kenmerk (bezittelijk) voor elke maand;
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel R
                geldig altijd
                    De belastingvermindering van een Persoon moet gesteld worden op (het tijdsevenredig deel per maand van de bedrag per maand van zijn partner gedurende de tijd dat hij een recht op korting heeft).
            """,
            () -> {
                Persoon p1 = new Persoon(), p2 = new Persoon();
                p1.setPartner(p2);
                p2.setBedragPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "12.00"), seg(2025, 1, 1, 2026, 1, 1, "18.00")));
                p1.setRechtOpKorting(List.of(seg(2024, 1, 1, 2025, 2, 8, null)));
                return List.of(p1, p2);
            },
            Map.of(),
            l -> vals(tl(((Persoon) l.get(0)).getBelastingvermindering()), tl(((Persoon) l.get(1)).getBelastingvermindering()))),
    };
    /** §7.3.2 navigated base — assert the ABSOLUTE spec value on the oracle (14 segments; the partner's 12/18 prorated,
     *  NOT the subject's empty own attribute) + back-end parity, so a shared "read the wrong base" bug cannot hide. */
    @Test void phase_F3_navigated_base_is_spec_correct() {
        Fixture f = PHASE_F3[PHASE_F3.length - 1];
        RegelSpraakModel m = Resolver.resolve(f.src());
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "model overgeslagen: " + em.overgeslagen());
        Object viaDrl = fire(drlBase(m), f);
        Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
        List<?> p1 = (List<?>) ((List<?>) viaDrl).get(0);   // [tl(p1), tl(p2)] -> p1's 14-segment timeline
        assertEquals(14, p1.size(), () -> "DRL (oracle) wrong: navigated base must prorate the partner's 12/18 to 14 segments, got " + p1);
        assertTrue(p1.get(13).toString().contains("2025-02-01|2025-03-01|4.5"),
                () -> "DRL (oracle) wrong: last (partial) segment must be 4,50, got " + p1.get(13));
        assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on the navigated base");
    }

    // ---- Phase F4 fixtures: §8.4.2 pure-kenmerk compound gedurende (ALLE intersect / ten-minste-1 union) ----
    private static final String CMP_OT =
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
            + "    de belastingvermindering per maand Bedrag voor elke maand;\n"
            + "    is rechthebbend kenmerk (bijvoeglijk) voor elke maand;\n"
            + "    is actief kenmerk (bijvoeglijk) voor elke maand;\n"
            + "    de totale belastingvermindering Bedrag;\n";
    /** The shared waarde/kenmerk facts for A19–A21: value 10/11; rechthebbend [2023-07,2024-02); actief [2023-01,2023-10). */
    private static Passagier cmpPax() {
        return passagier(p -> {
            p.setBelastingverminderingPerMaand(List.of(seg(2023, 1, 1, 2024, 1, 1, "10.00"), seg(2024, 1, 1, 2024, 7, 1, "11.00")));
            p.setRechthebbend(List.of(seg(2023, 7, 1, 2024, 2, 1, null)));
            p.setActief(List.of(seg(2023, 1, 1, 2023, 10, 1, null)));
        });
    }
    static final Fixture[] PHASE_F4 = {
        // A19 §8.4.2 ALLE compound clips the value to the INTERSECTION of the kenmerks' true periods — [2023-07,2023-10) = 3×10 = 30.
        single("§8.4.2 ALLE gedurende = doorsnede (3×10 = 30)",
            TOTAAL_HDR + CMP_OT + """
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat er aan alle volgende voorwaarden wordt voldaan:
                    • hij rechthebbend is
                    • hij actief is).
            """,
            ExecModelDifferentialTest::cmpPax, Map.of(), Passagier::getTotaleBelastingvermindering),   // 30.00

        // A21 §8.4.2 "ten minste 1" clips to the UNION of the kenmerks' true periods — [2023-01,2024-02) = 12×10 + 1×11 = 131.
        single("§8.4.2 ten-minste-1 gedurende = unie (12×10 + 1×11 = 131)",
            TOTAAL_HDR + CMP_OT + """
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat er aan ten minste 1 van de volgende voorwaarden wordt voldaan:
                    • hij rechthebbend is
                    • hij actief is).
            """,
            ExecModelDifferentialTest::cmpPax, Map.of(), Passagier::getTotaleBelastingvermindering),   // 131.00

        // A20 §8.4.2 "ten minste 1" with a NEGATED member: rechthebbend ∪ ¬actief (value-bounded complement) — [2023-07,2024-07) = 6×10+6×11 = 126.
        single("§8.4.2 ten-minste-1 met negatie = unie van het span-begrensde complement (126)",
            TOTAAL_HDR + CMP_OT + """
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat er aan ten minste 1 van de volgende voorwaarden wordt voldaan:
                    • hij rechthebbend is
                    • hij niet actief is).
            """,
            ExecModelDifferentialTest::cmpPax, Map.of(), Passagier::getTotaleBelastingvermindering),   // 126.00

        // A17 §8.4.2 ALLE compound on the aantal-dagen-in WRITE: count per month the days BOTH kenmerks hold (rsTijdKnip).
        single("§8.4.2 ALLE compound op aantal-dagen-in (doorsnede: Jan 22 / Feb 13)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                is actief kenmerk (bijvoeglijk) voor elke maand;
                het aantal dagen recht op belastingvermindering Numeriek (geheel getal) met eenheid dg voor elke maand;
            Regel R
                geldig altijd
                    Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat er aan alle volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - hij actief is.
            """,
            () -> passagier(p -> {
                p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 1, 2024, 2, 14, null)));
                p.setActief(List.of(seg(2024, 1, 10, 2024, 3, 1, null)));
            }),
            Map.of(),
            p -> tl(p.getAantalDagenRechtOpBelastingvermindering())),   // [Jan→22, Feb→13]

        // A18 §8.4.2 "ten minste 1" compound on the tijdsevenredig WRITE: prorate over the UNION (rsTijdUnie).
        single("§8.4.2 ten-minste-1 compound op tijdsevenredig (unie: Jan 18,00 / Feb 4,50)",
            """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de standaardbedrag Bedrag voor elk jaar;
                de belastingvermindering Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                is actief kenmerk (bijvoeglijk) voor elke maand;
            Regel R
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van zijn standaardbedrag gedurende de tijd dat er aan ten minste 1 van de volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - hij actief is).
            """,
            () -> passagier(p -> {
                p.setStandaardbedrag(List.of(seg(2025, 1, 1, 2026, 1, 1, "18.00")));
                p.setRechtOpBelastingvermindering(List.of(seg(2025, 1, 1, 2025, 2, 5, null)));
                p.setActief(List.of(seg(2025, 2, 1, 2025, 2, 8, null)));
            }),
            Map.of(),
            p -> tl(p.getBelastingvermindering())),   // [Jan→18.00, Feb→4.50]

        // A22 §8.4.2 OR-mixed (ten minste 1: a temporal kenmerk OR a static comparison) on the tijdsevenredig WRITE:
        // a TRUE static member (leeftijd 18) satisfies the OR for the WHOLE span -> prorate the base over its full
        // extent (base AS its own all-true condition); FALSE (19) leaves only the temporal union (recht Jan-Mar).
        // base €10/2024 -> [12×€10] (true) vs [3×€10, Jan-Mar] (false). (aantal-dagen-in has no base/extent -> markers.)
        new Fixture("§8.4.2 OR-mixed op tijdsevenredig WRITE (18 -> 12×10 / 19 -> 3×10)",
            """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de standaardbedrag Bedrag voor elk jaar;
                de belastingvermindering Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de leeftijd Numeriek (geheel getal);
            Regel R
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van zijn standaardbedrag gedurende de tijd dat er aan ten minste 1 van de volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - zijn leeftijd is gelijk aan 18).
            """,
            () -> List.of(
                passagier(p -> { p.setStandaardbedrag(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")));
                                 p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 1, 2024, 4, 1, null)));
                                 p.setLeeftijd(new BigDecimal(18)); }),
                passagier(p -> { p.setStandaardbedrag(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")));
                                 p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 1, 2024, 4, 1, null)));
                                 p.setLeeftijd(new BigDecimal(19)); })),
            Map.of(),
            l -> vals(tl(((Passagier) l.get(0)).getBelastingvermindering()),
                      tl(((Passagier) l.get(1)).getBelastingvermindering()))),   // [12×10], [3×10]
    };

    // ---- Phase F4b fixtures: §8.4 mixed compound — a static (non-temporal) comparison member as the value-side guard ----
    private static LocalDate dat(int y, int m, int d) { return LocalDate.of(y, m, d); }
    /** waarde 10/11 + recht [2023-07,2024-02), plus the static-guard attribute the caller sets (leeftijd / geboortedatum). */
    private static Passagier mixedPax(Consumer<Passagier> extra) {
        Passagier p = passagier(q -> {
            q.setBelastingverminderingPerMaand(List.of(seg(2023, 1, 1, 2024, 1, 1, "10.00"), seg(2024, 1, 1, 2024, 7, 1, "11.00")));
            q.setRechtOpBelastingvermindering(List.of(seg(2023, 7, 1, 2024, 2, 1, null)));
        });
        extra.accept(p);
        return p;
    }
    private static final String MIXED_LEEFTIJD_OT = TOTAAL_HDR
            + "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
            + "    de belastingvermindering per maand Bedrag voor elke maand;\n"
            + "    het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;\n"
            + "    de leeftijd Numeriek (geheel getal);\n"
            + "    de totale belastingvermindering Bedrag;\n";
    private static final String MIXED_GEB_OT = TOTAAL_HDR
            + "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
            + "    de belastingvermindering per maand Bedrag voor elke maand;\n"
            + "    het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;\n"
            + "    de geboortedatum Datum in dagen;\n"
            + "    de totale belastingvermindering Bedrag;\n";
    private static Object bothTotaal(List<Object> l) {
        return vals(((Passagier) l.get(0)).getTotaleBelastingvermindering(), ((Passagier) l.get(1)).getTotaleBelastingvermindering());
    }
    static final Fixture[] PHASE_F4B = {
        // A13 §8.4 ALLE mixed: a numeric GELIJK static member gates the totaal — leeftijd 18 -> 71, else 0.
        new Fixture("§8.4 ALLE mixed numeric guard (leeftijd==18 -> 71 / anders 0)",
            MIXED_LEEFTIJD_OT + """
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat er aan alle volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - zijn leeftijd is gelijk aan 18).
            """,
            () -> List.of(mixedPax(p -> p.setLeeftijd(new BigDecimal(18))), mixedPax(p -> p.setLeeftijd(new BigDecimal(19)))),
            Map.of(),
            ExecModelDifferentialTest::bothTotaal),   // 71.00, 0

        // A14 §8.4.2 ten-minste-1 mixed: a TRUE static member WIDENS to the full span (186); FALSE leaves the clip (71).
        new Fixture("§8.4.2 ten-minste-1 mixed verbreedt naar de volle span (18 -> 186 / 19 -> 71)",
            MIXED_LEEFTIJD_OT + """
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat er aan ten minste 1 van de volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - zijn leeftijd is gelijk aan 18).
            """,
            () -> List.of(mixedPax(p -> p.setLeeftijd(new BigDecimal(18))), mixedPax(p -> p.setLeeftijd(new BigDecimal(19)))),
            Map.of(),
            ExecModelDifferentialTest::bothTotaal),   // 186.00, 71.00

        // A16 §8.4 ALLE mixed: a DATUM ordering static member gates the totaal — geboortedatum < 2002 -> 71, else 0.
        new Fixture("§8.4 ALLE mixed datum guard (geboortedatum eerder dan 2002 -> 71 / anders 0)",
            MIXED_GEB_OT + """
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat er aan alle volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - zijn geboortedatum is eerder dan 1-1-2002).
            """,
            () -> List.of(mixedPax(p -> p.setGeboortedatum(dat(1990, 1, 1))), mixedPax(p -> p.setGeboortedatum(dat(2005, 1, 1)))),
            Map.of(),
            ExecModelDifferentialTest::bothTotaal),   // 71.00, 0

        // A15 §8.4 ALLE mixed on the aantal-dagen WRITE: the static date guard empties the whole write when false.
        new Fixture("§8.4 mixed datum guard op aantal-dagen-in (jong -> [Jan 31, Feb 13] / oud -> leeg)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de geboortedatum Datum in dagen;
                het aantal dagen recht op belastingvermindering Numeriek (geheel getal) met eenheid dg voor elke maand;
            Regel R
                geldig altijd
                    Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat er aan alle volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - zijn geboortedatum is eerder dan 1-1-2002.
            """,
            () -> {
                Passagier jong = passagier(p -> { p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 1, 2024, 2, 14, null))); p.setGeboortedatum(dat(1990, 1, 1)); });
                Passagier oud = passagier(p -> { p.setRechtOpBelastingvermindering(List.of(seg(2024, 1, 1, 2024, 2, 14, null))); p.setGeboortedatum(dat(2005, 1, 1)); });
                return List.of(jong, oud);
            },
            Map.of(),
            l -> vals(tl(((Passagier) l.get(0)).getAantalDagenRechtOpBelastingvermindering()),
                      tl(((Passagier) l.get(1)).getAantalDagenRechtOpBelastingvermindering()))),   // [Jan 31, Feb 13], []
    };

    // ---- Phase 82 fixtures: §8.2/§13.4.15.70 "gedurende de/het gehele jaar|maand" — a whole-period check over a
    //      tijdsafhankelijk onderwerp, reduced to a scalar boolean (rsGeheelPeriode: forall complete calendar bucket
    //      touched, the per-moment check holds). The voorwaarde gates a scalar kenmerk (gemarkeerd). Both back-ends
    //      call the byte-identical rsGeheelPeriode helper. ----
    private static final String G82_ATTR_HDR = TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                is gemarkeerd kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is gemarkeerd
                    indien """;
    private static String g82attr(String conditie) { return G82_ATTR_HDR + " " + conditie + ".\n"; }   // space: the text block strips "indien "'s trailing space

    static final Fixture[] PHASE_82 = {
        // JAAR comparison TRUE: loon == 10 for the WHOLE of 2024 -> the year is satisfied -> gemarkeerd.
        single("§8.2 JAAR == TRUE (heel 2024 == 10)",
            g82attr("zijn belastingvermindering per maand gedurende het gehele jaar gelijk is aan 10"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),   // true
        // JAAR comparison FALSE: ==10 only Jan-Jun, ==11 Jul-Dec -> 2024 not wholly ==10 -> not gemarkeerd.
        single("§8.2 JAAR == FALSE (half 10, half 11)",
            g82attr("zijn belastingvermindering per maand gedurende het gehele jaar gelijk is aan 10"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(
                    seg(2024, 1, 1, 2024, 7, 1, "10.00"), seg(2024, 7, 1, 2025, 1, 1, "11.00")))),
            Map.of(), Passagier::isGemarkeerd),   // false
        // NIET (vragende vorm) over the partial timeline: NOT(whole year ==10) is TRUE -> gemarkeerd.
        single("§8.2 NIET vragende TRUE (niet heel jaar 10)",
            g82attr("zijn belastingvermindering per maand niet gedurende het gehele jaar gelijk is aan 10"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(
                    seg(2024, 1, 1, 2024, 7, 1, "10.00"), seg(2024, 7, 1, 2025, 1, 1, "11.00")))),
            Map.of(), Passagier::isGemarkeerd),   // true
        // NIET over a wholly-10 timeline: NOT(whole year ==10) is FALSE -> not gemarkeerd.
        single("§8.2 NIET vragende FALSE (heel jaar 10)",
            g82attr("zijn belastingvermindering per maand niet gedurende het gehele jaar gelijk is aan 10"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),   // false
        // (The §8.2 NIET STELLENDE form "is niet gedurende het gehele jaar gelijk aan 10" — verb-leading — is no
        // longer valid under a bare 'indien' (§8.3.1 = vragende, the tightening); the stellende register is bullet-only
        // (§8.3.2). Its semantics are already covered by the vragende NIET above; a bullet-context §8.2 test is a
        // separate §8.2-coverage follow-up. See 20260625-verb-order-tightening.md.)
        // MAAND TRUE where JAAR would be FALSE: ==10 for Jan-Jun only; every TOUCHED month is wholly ==10 (Jul-Dec
        // untouched -> not tested), but the year is not wholly covered. Distinguishes the MAAND bucketing.
        single("§8.2 MAAND TRUE (Jan-Jun wholly 10; jaar zou FALSE zijn)",
            g82attr("zijn belastingvermindering per maand gedurende de gehele maand gelijk is aan 10"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 7, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),   // true
        // GEVULD TRUE: a value present for the whole of 2024.
        single("§8.2 GEVULD TRUE (heel jaar gevuld)",
            g82attr("zijn belastingvermindering per maand gedurende het gehele jaar gevuld is"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),   // true
        // GEVULD FALSE: a gap (Jul-Dec) means the year is not wholly gevuld.
        single("§8.2 GEVULD FALSE (gat Jul-Dec)",
            g82attr("zijn belastingvermindering per maand gedurende het gehele jaar gevuld is"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 7, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),   // false
        // multi-year FALSE: 2024 wholly ==10, 2025 wholly ==11 -> 2025 violates -> not gemarkeerd.
        single("§8.2 multi-jaar FALSE (2024 ok, 2025 != 10)",
            g82attr("zijn belastingvermindering per maand gedurende het gehele jaar gelijk is aan 10"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(
                    seg(2024, 1, 1, 2025, 1, 1, "10.00"), seg(2025, 1, 1, 2026, 1, 1, "11.00")))),
            Map.of(), Passagier::isGemarkeerd),   // false
        // empty / null timeline: no calendar period is touched -> vacuously TRUE (no period violates).
        single("§8.2 null tijdlijn -> vacuously TRUE",
            g82attr("zijn belastingvermindering per maand gedurende het gehele jaar gelijk is aan 10"),
            () -> passagier(p -> {}),   // belastingverminderingPerMaand stays null
            Map.of(), Passagier::isGemarkeerd),   // true
        // grens reads a Parameter-GLOBAL ("gelijk is aan de drempel"): the model binds the global (2-var expr).
        new Fixture("§8.2 grens is parameter-global (== drempel)",
            "Parameter de drempel : Numeriek (geheel getal)\n" + g82attr(
                "zijn belastingvermindering per maand gedurende het gehele jaar gelijk is aan de drempel"),
            () -> List.of(passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00"))))),
            Map.of("drempel", new BigDecimal(10)),
            l -> ((Passagier) l.get(0)).isGemarkeerd()),   // true
    };

    // ---- Phase 82 CROSS-UNIT grens (§8.2 + §5.1): the temporal attribute is in mnd, the grens "1 jr" is in jr; both
    //      back-ends restate the grens into the timeline's unit (mnd) by the EXACT factor (1 jr = 12 mnd) before the
    //      per-bucket compare (DRL rsHerschaalExact, exec WaardeFn.herschaald). 12-mnd whole year == 1 jr -> WAAR;
    //      11-mnd whole year != 1 jr -> ONWAAR. Closed-form -> assert the ABSOLUTE Tabel-32 value (differential-masks). ----
    private static final String G82_CU_SRC =
        "Eenheidsysteem Tijd\n    de maand    mnd\n    het jaar    jr    =    12 mnd\n" +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
        "    de belastingvermindering per maand Numeriek (geheel getal) met eenheid mnd voor elke maand;\n" +
        "    is gemarkeerd kenmerk (bijvoeglijk);\n" +
        "Regel R\n    geldig altijd\n        Een Passagier is gemarkeerd\n" +
        "        indien zijn belastingvermindering per maand gedurende het gehele jaar gelijk is aan 1 jr.\n";
    static final Fixture[] PHASE_82_CU = {
        new Fixture("§8.2 cross-unit: heel 2024 == 12 mnd == 1 jr -> gemarkeerd",
            G82_CU_SRC,
            () -> List.of(passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "12"))))),
            Map.of(), l -> ((Passagier) l.get(0)).isGemarkeerd()),   // 12 mnd == 1 jr -> true
        new Fixture("§8.2 cross-unit: heel 2024 == 11 mnd != 1 jr -> niet gemarkeerd",
            G82_CU_SRC,
            () -> List.of(passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "11"))))),
            Map.of(), l -> ((Passagier) l.get(0)).isGemarkeerd()),   // 11 mnd != 12 mnd -> false
        new Fixture("§8.2 cross-unit: half 12 half 11 -> niet heel jaar 1 jr -> niet gemarkeerd",
            G82_CU_SRC,
            () -> List.of(passagier(p -> p.setBelastingverminderingPerMaand(List.of(
                    seg(2024, 1, 1, 2024, 7, 1, "12"), seg(2024, 7, 1, 2025, 1, 1, "11"))))),
            Map.of(), l -> ((Passagier) l.get(0)).isGemarkeerd()),   // not the WHOLE year == 12 -> false
    };
    static final Boolean[] PHASE_82_CU_EXPECTED = { Boolean.TRUE, Boolean.FALSE, Boolean.FALSE };

    // ---- Phase 82 KENMERK fixtures: "is gedurende het gehele jaar een <kenmerk>" over a temporal kenmerk timeline ----
    private static final String G82_KENMERK_HDR = TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                is rechthebbend kenmerk (bijvoeglijk) voor elke maand;
                is gemarkeerd kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is gemarkeerd
                    indien """;
    static final Fixture[] PHASE_82_KENMERK = {
        // kenmerk TRUE: rechthebbend the WHOLE of 2024 -> gemarkeerd.
        single("§8.2 kenmerk TRUE (heel jaar rechthebbend)",
            G82_KENMERK_HDR + " hij gedurende het gehele jaar rechthebbend is.\n",
            () -> passagier(p -> p.setRechthebbend(List.of(seg(2024, 1, 1, 2025, 1, 1, null)))),
            Map.of(), Passagier::isGemarkeerd),   // true
        // kenmerk FALSE: rechthebbend only Mar-Sep 2024 -> the touched year is not wholly true.
        single("§8.2 kenmerk FALSE (alleen Mar-Sep)",
            G82_KENMERK_HDR + " hij gedurende het gehele jaar rechthebbend is.\n",
            () -> passagier(p -> p.setRechthebbend(List.of(seg(2024, 3, 1, 2024, 9, 1, null)))),
            Map.of(), Passagier::isGemarkeerd),   // false
    };

    // ---- Phase 82 NAVIGATED KENMERK: "zijn reis gedurende het gehele jaar actief is" — a §8.2 whole-period check over a
    //      to-one role's temporal kenmerk timeline, read root-guarded (an absent relation -> leeg timeline -> vacuously
    //      TRUE, matching the own-kenmerk null-timeline convention). Both back-ends: DRL navigatieKenmerkTijdreeks
    //      ((reis==null?null:reis.getActief())), exec navigatieKenmerkTijdreeks. Was a symmetric marker. ----
    private static final String G82_NAVKENMERK_SRC = TOTAAL_HDR +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n    is gemarkeerd kenmerk (bijvoeglijk);\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    is actief kenmerk (bijvoeglijk) voor elke maand;\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel R\n    geldig altijd\n        Een Passagier is gemarkeerd indien zijn reis gedurende het gehele jaar actief is.\n";
    static Fixture navKenmerkFix(String naam, java.time.LocalDate van, java.time.LocalDate tot) {
        return new Fixture(naam, G82_NAVKENMERK_SRC,
                () -> { Passagier p = new Passagier();
                        if (van != null) { Vlucht v = new Vlucht();
                            v.setActief(List.of(seg(van.getYear(), van.getMonthValue(), van.getDayOfMonth(),
                                    tot.getYear(), tot.getMonthValue(), tot.getDayOfMonth(), null)));
                            p.setReis(v); }   // van==null -> NO reis (absent relation)
                        return List.of(p); },
                Map.of(), l -> ((Passagier) l.get(0)).isGemarkeerd());
    }
    static final Fixture[] PHASE_82_NAVKENMERK = {
        navKenmerkFix("§8.2 nav-kenmerk TRUE: reis actief heel 2024 -> gemarkeerd",
                java.time.LocalDate.of(2024, 1, 1), java.time.LocalDate.of(2025, 1, 1)),
        navKenmerkFix("§8.2 nav-kenmerk FALSE: reis actief alleen Mar-Sep 2024 -> niet gemarkeerd",
                java.time.LocalDate.of(2024, 3, 1), java.time.LocalDate.of(2024, 9, 1)),
        navKenmerkFix("§8.2 nav-kenmerk ABSENT reis -> leeg timeline -> vacuously TRUE -> gemarkeerd",
                null, null),
    };
    static final Boolean[] PHASE_82_NAVKENMERK_EXPECTED = { Boolean.TRUE, Boolean.FALSE, Boolean.TRUE };

    // ---- §8.2 line 3468 whole-period LEEG: "zijn belastingvermindering per maand gedurende de gehele maand leeg is" —
    //      the inverse of GEVULD (toets 8: every moment of a touched bucket carries NO value). Was a symmetric marker. ----
    static final Fixture[] PHASE_82_LEEG = {
        // a leeg-segment covering the whole month -> every moment is leeg -> gemarkeerd.
        single("§8.2 LEEG TRUE (heel jan een leeg-segment)",
            g82attr("zijn belastingvermindering per maand gedurende de gehele maand leeg is"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, null)))),
            Map.of(), Passagier::isGemarkeerd),   // true
        // a value in January -> the month is not wholly leeg -> not gemarkeerd.
        single("§8.2 LEEG FALSE (jan heeft een waarde)",
            g82attr("zijn belastingvermindering per maand gedurende de gehele maand leeg is"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 2, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),   // false
        // a null timeline (no data) -> no period to violate -> vacuously leeg (matches the §8.2 empty-timeline convention).
        single("§8.2 LEEG TRUE (null tijdlijn -> vacuously)",
            g82attr("zijn belastingvermindering per maand gedurende de gehele maand leeg is"),
            () -> passagier(p -> {}),
            Map.of(), Passagier::isGemarkeerd),   // true
        // a leeg-segment covering only PART of the month + a GAP for the rest -> all of Jan is leeg (a gap day is leeg
        // too) -> gemarkeerd. (review wf_52c820ef: the gap days must count as leeg, not as a violation.)
        single("§8.2 LEEG TRUE (leeg-segment jan 1-15 + gap -> heel jan leeg)",
            g82attr("zijn belastingvermindering per maand gedurende de gehele maand leeg is"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 1, 16, null)))),
            Map.of(), Passagier::isGemarkeerd),   // true
        // a VALUE-segment covering only PART of the month + a gap -> Jan has a value -> NOT wholly leeg -> niet gemarkeerd.
        single("§8.2 LEEG FALSE (waarde jan 1-15 + gap -> jan niet leeg)",
            g82attr("zijn belastingvermindering per maand gedurende de gehele maand leeg is"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 1, 16, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),   // false
    };
    static final Boolean[] PHASE_82_LEEG_EXPECTED = { Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE };

    // ---- Phase 82 FOUT fixtures: a §8.2-qualified consistentie criterium — the VIOLATION inserts an Inconsistentie ----
    static final FoutFixture[] PHASE_82_FOUT = {
        new FoutFixture("§8.2 consistentie criterium (whole-year == 10)",
            TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de leeftijd Numeriek (geheel getal) met eenheid jr;
            Regel R
                geldig altijd
                    De belastingvermindering per maand van een Passagier moet gedurende het gehele jaar gelijk zijn aan 10.
            """,
            () -> {
                Passagier ok = passagier(p -> { p.setLeeftijd(new BigDecimal(1)); p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00"))); });    // consistent
                Passagier bad = passagier(p -> { p.setLeeftijd(new BigDecimal(2)); p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 7, 1, "10.00"), seg(2024, 7, 1, 2025, 1, 1, "11.00"))); });   // flagged
                Passagier leeg = passagier(p -> p.setLeeftijd(new BigDecimal(3)));   // null timeline -> vacuously consistent (no fout)
                return List.of(ok, bad, leeg);
            },
            o -> "leeftijd=" + ((Passagier) o).getLeeftijd()),   // only leeftijd=2 flagged
    };

    // ---- Phase 82 NAV: the §8.2 grenswaarde is a NAVIGATED to-one attribute ("… gelijk is aan de totaalprijs van
    //      zijn reis"). DRL fires reis!.totaalprijs; the model must lower the same null-safe nav read (not skip). A
    //      null relation -> null grens -> the check fails (no NPE) — exercises both the navigated-grens + null-grens fixes. ----
    private static final String G82_NAV_HDR = TOTAAL_HDR + """
            Objecttype de Vlucht (mv: Vluchten) (bezield)
                de totaalprijs Bedrag;
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                is gemarkeerd kenmerk (bijvoeglijk);
            Feittype reis
                de passagier Passagier
                de reis Vlucht
            één passagier heeft één reis;
            Regel R
                geldig altijd
                    Een Passagier is gemarkeerd
                    indien zijn belastingvermindering per maand gedurende het gehele jaar gelijk is aan de totaalprijs van zijn reis.
            """;
    static final Fixture[] PHASE_82_NAV = {
        new Fixture("§8.2 navigated grens TRUE (timeline 10 == reis.totaalprijs 10)",
            G82_NAV_HDR,
            () -> { Vlucht v = new Vlucht(); v.setTotaalprijs(new BigDecimal("10.00"));
                    Passagier p = passagier(x -> { x.setReis(v); x.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00"))); });
                    return List.of(v, p); },
            Map.of(), l -> ((Passagier) l.get(1)).isGemarkeerd()),   // true
        new Fixture("§8.2 navigated grens FALSE (timeline 10 != reis.totaalprijs 20)",
            G82_NAV_HDR,
            () -> { Vlucht v = new Vlucht(); v.setTotaalprijs(new BigDecimal("20.00"));
                    Passagier p = passagier(x -> { x.setReis(v); x.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00"))); });
                    return List.of(v, p); },
            Map.of(), l -> ((Passagier) l.get(1)).isGemarkeerd()),   // false
        new Fixture("§8.2 navigated grens null relation -> leeg grens -> FALSE (no NPE)",
            G82_NAV_HDR,
            () -> List.of(passagier(x -> x.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00"))))),   // reis = null
            Map.of(), l -> ((Passagier) l.get(0)).isGemarkeerd()),   // false (grens null -> check fails, never crashes)
    };

    // The §8.2 RIGHT-temporal operand absolute-correctness guard. A pure differential cannot catch a bug SHARED by
    // both back-ends (e.g. the operand-reversal of the toets) — so assert the DRL oracle against the SPEC-correct
    // boolean as well. "10 gedurende het gehele jaar gelijk is aan <temporal>": the literal is the LHS, the temporal
    // the RHS, so the per-moment toets must be the REVERSE (GELIJK->GELIJK, GROTER->KLEINER), not the negation.
    static final Fixture[] PHASE_82_RIGHT = {
        single("§8.2 right-temporal GELIJK 10==tl(10) -> TRUE",
            g82attr("10 gedurende het gehele jaar gelijk is aan zijn belastingvermindering per maand"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),
        single("§8.2 right-temporal GROTER 20>tl(10) -> TRUE",
            g82attr("20 gedurende het gehele jaar groter is dan zijn belastingvermindering per maand"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),
        single("§8.2 right-temporal GROTER 10>tl(10) boundary -> FALSE",
            g82attr("10 gedurende het gehele jaar groter is dan zijn belastingvermindering per maand"),
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),
    };
    static final boolean[] PHASE_82_RIGHT_EXPECTED = { true, true, false };

    // ---- Phase 82 COUNTING: a §8.2 leaf (literal grens) INSIDE a structured counting quantifier ("hij aan ten minste
    //      1 van de volgende voorwaarden voldoet"). DRL folds rsGeheelPeriode(...) into the minstens(); the model's
    //      leafPredicate lowers the same §8.2 Predicate1 into the counting fold -> both agree. (A §8.2 grens reading a
    //      Parameter-global inside a counting fold is the pre-existing globals-in-a-counting-fold model limitation —
    //      the model cannot bind a global in a Predicate1, same as any vs-global comparison there — and is out of scope.) ----
    private static final String G82_COUNT_HDR = TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                is student kenmerk (bijvoeglijk);
                is gemarkeerd kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is gemarkeerd indien hij aan ten minste 1 van de volgende voorwaarden voldoet:
                      - zijn belastingvermindering per maand gedurende het gehele jaar gelijk is aan 10
                      - hij student is.
            """;
    static final Fixture[] PHASE_82_COUNTING = {
        single("§8.2 in TEN_MINSTE: §8.2 holds (10) -> TRUE",
            G82_COUNT_HDR,
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")))),
            Map.of(), Passagier::isGemarkeerd),   // true (§8.2 member holds)
        single("§8.2 in TEN_MINSTE: neither holds (11, not student) -> FALSE",
            G82_COUNT_HDR,
            () -> passagier(p -> p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "11.00")))),
            Map.of(), Passagier::isGemarkeerd),   // false
        single("§8.2 in TEN_MINSTE: only student holds -> TRUE",
            G82_COUNT_HDR,
            () -> passagier(p -> { p.setStudent(true); p.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "11.00"))); }),
            Map.of(), Passagier::isGemarkeerd),   // true (student member holds)
    };

    /** §8.2 right-temporal: assert the DRL oracle against the SPEC-correct boolean (catches an operand-reversal bug
     *  SHARED by both back-ends, which a pure differential would miss) AND that the model agrees. */
    @Test void phase_82_right_temporal_operand_is_spec_correct() {
        for (int i = 0; i < PHASE_82_RIGHT.length; i++) {
            Fixture f = PHASE_82_RIGHT[i];
            boolean expected = PHASE_82_RIGHT_EXPECTED[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(expected, eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ---- Phase 11 fixtures: §11/§13.4.2.10 "Daarbij geldt: X is <expressie>" local rule variables. Since the
    //      Let-port both back-ends consume the SAME wire the frontend emits (uchar RUNTIME-SEMANTICS ch. 10.6): a
    //      read in the RESULTAAT is a BinderRef under a call-by-need Let chain, a read in the VOORWAARDE is still
    //      substituted (the wire's boundary a — a rule's condition and its value are separate fields, so no Let can
    //      span them). This checks that BOTH routes lower + fire identically in the two back-ends. ----
    static final Fixture[] PHASE_11 = {
        // X used in the CONDITION: "minderjarig indien X < 18" with X = zijn leeftijd.
        single("§11 var in condition (X = zijn leeftijd, X < 18)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is minderjarig indien X kleiner is dan 18 jr.
                    Daarbij geldt: X is zijn leeftijd.
            """,
            () -> passagier(p -> p.setLeeftijd(new BigDecimal(16))),
            Map.of(), Passagier::isMinderjarig),   // true
        // a variable referencing an EARLIER variable, inlined in the RESULT: Y = X plus X, X = zijn prijs.
        single("§11 var refs earlier var in result (Y = X+X, X = prijs)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal) met eenheid EUR;
                het basistarief Numeriek (geheel getal) met eenheid EUR;
            Regel R
                geldig altijd
                    Het basistarief van een Passagier moet gesteld worden op Y.
                    Daarbij geldt:
                        X is zijn prijs
                        Y is X plus X.
            """,
            () -> passagier(p -> p.setPrijs(new BigDecimal(21))),
            Map.of(), Passagier::getBasistarief),   // 42
        // X used in BOTH the condition and the result (the canonical §11 use): plafond = X + 5 indien X > 10.
        single("§11 var in condition AND result (plafond = X+5 if X>10, X = prijs)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet gesteld worden op X plus 5 indien X groter is dan 10.
                    Daarbij geldt: X is zijn prijs.
            """,
            () -> passagier(p -> p.setPrijs(new BigDecimal(20))),
            Map.of(), Passagier::getPlafond),   // 25
    };

    // The §11.1 rekenvolgorde example (B references the earlier A; the "A != 0" condition guards the b/A division).
    // A pure differential can't catch a lowering bug shared by both back-ends, so assert the absolute
    // spec result too: prijs=5,basistarief=20 -> A=5, A!=0 -> B=20/5=4 -> plafond=4+1=5; prijs=0 -> A=0 -> no fire.
    // Note the two routes meeting in one rule: the "A ongelijk aan 0" VOORWAARDE substitutes A, while the value
    // "B plus 1" reads B — and B's own definition reads A — through the Let chain (ch. 10.6 clause 1: one Let, one
    // evaluation, however many reads). The pinned values are unchanged by the Let-port.
    static final Fixture[] PHASE_11_ABS = {
        single("§11.1 B=basistarief/A, A=prijs, plafond=B+1 if A!=0 (5)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als B plus 1 indien A ongelijk is aan 0.
                    Daarbij geldt:
                        A is zijn prijs
                        B is zijn basistarief gedeeld door A.
            """,
            () -> passagier(p -> { p.setPrijs(new BigDecimal(5)); p.setBasistarief(new BigDecimal(20)); }),
            Map.of(), Passagier::getPlafond),
        single("§11.1 A=0 -> no fire (plafond null)",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als B plus 1 indien A ongelijk is aan 0.
                    Daarbij geldt:
                        A is zijn prijs
                        B is zijn basistarief gedeeld door A.
            """,
            () -> passagier(p -> { p.setPrijs(new BigDecimal(0)); p.setBasistarief(new BigDecimal(20)); }),
            Map.of(), Passagier::getPlafond),
    };
    static final Object[] PHASE_11_ABS_EXPECTED = { new BigDecimal(5), null };

    // The Let-port's own absolute pins (uchar RUNTIME-SEMANTICS ch. 10.6, tracked delta 1 / CONSTRUCTS row 24).
    // These are the class where the v0 inlining and the v1 lowering DISAGREE, so a pure differential is not enough:
    // both back-ends inlining would agree with each other and be wrong together. Hence absolute values.
    //
    //   1. THE BARRIER. "zijn prijs plus de toeslag", toeslag = zijn basistarief, with basistarief ABSENT. The
    //      use site READS the binder, so the leeg-barrier (clause 2) empties the whole rule value and the target
    //      stays leeg. The inlined form would have coalesced the absent operand to 0 (the ch. 1.2 `plus` row) and
    //      written 30 — that is precisely the divergence this port removes.
    //   2. THE CONTROL. Same rule, basistarief FILLED: with the variable present the lowered form computes
    //      exactly what the inlined form did. Only case 1 separates them.
    //   3. THE SHARING. One binder, two reads (clause 1: one Let, one evaluation) — the value is 2x21, not a
    //      recomputation artefact.
    static final Fixture[] PHASE_11_LET_ABS = {
        single("ch. 10.6 barriere: gelezen binder met LEGE waarde laat het doel leeg",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als zijn prijs plus de toeslag.
                    Daarbij geldt: de toeslag is zijn basistarief.
            """,
            () -> passagier(p -> p.setPrijs(new BigDecimal(30))),
            Map.of(), Passagier::getPlafond),
        single("ch. 10.6 controle: gevulde binder rekent zoals de ingelijnde vorm",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als zijn prijs plus de toeslag.
                    Daarbij geldt: de toeslag is zijn basistarief.
            """,
            () -> passagier(p -> { p.setPrijs(new BigDecimal(30)); p.setBasistarief(new BigDecimal(12)); }),
            Map.of(), Passagier::getPlafond),
        single("ch. 10.6 deling: een binder die twee keer gelezen wordt",
            """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als de vangst plus de vangst.
                    Daarbij geldt: de vangst is zijn prijs.
            """,
            () -> passagier(p -> p.setPrijs(new BigDecimal(21))),
            Map.of(), Passagier::getPlafond),
    };
    static final Object[] PHASE_11_LET_ABS_EXPECTED = { null, new BigDecimal(42), new BigDecimal(42) };

    /** ch. 10.6: the leeg-barrier, its control and the sharing witness, pinned ABSOLUTELY in both back-ends.
     *  Fixture 1 is the tracked v1 divergence from the v0 inlining — before the Let-port both back-ends wrote 30. */
    @Test void ch_10_6_leeg_barriere_is_spec_correct() {
        for (int i = 0; i < PHASE_11_LET_ABS.length; i++) {
            Fixture f = PHASE_11_LET_ABS[i];
            Object expected = PHASE_11_LET_ABS_EXPECTED[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(expected), eq(viaDrl), () -> "DRL wijkt af van ch. 10.6 op '" + f.naam() + "'");
            assertEquals(eq(expected), eq(viaModel), () -> "exec-model wijkt af van ch. 10.6 op '" + f.naam() + "'");
        }
    }

    // ---- The Let-port fix round (uchar RUNTIME-SEMANTICS §5.6 + ch. 10.6). Two classes the first cut got wrong,
    //      both pinned here ABSOLUTELY (a count, not merely back-end agreement — two back-ends can be wrong
    //      together, and in the first cut they were). ----

    /** One fired world: the target value plus the number of §9.5 Inconsistentie facts, or the fault it died on. */
    private record LetUitkomst(Object waarde, long fouten, String fout) {
        static LetUitkomst ok(Object w, long f) { return new LetUitkomst(w, f, null); }
        @Override public String toString() { return fout != null ? "FOUT(" + fout + ")" : "waarde=" + waarde + " fouten=" + fouten; }
    }

    private LetUitkomst vuurLet(KieBase kb, Supplier<List<Object>> facts, Function<List<Object>, Object> uit) {
        List<Object> fs = facts.get();
        KieSession s = sessie(kb);
        for (Object o : fs) s.insert(o);
        try {
            s.fireAllRules(10_000);
        } catch (RuntimeException e) {
            s.dispose();
            return new LetUitkomst(null, -1, e.getClass().getSimpleName());
        }
        long fouten = s.getObjects().stream().filter(o -> o instanceof Inconsistentie).count();
        Object w = uit.apply(fs);
        s.dispose();
        return LetUitkomst.ok(w, fouten);
    }

    /** Fire {@code src} through BOTH back-ends and require they agree with each other AND with {@code verwacht}. */
    private void eisLet(String label, String src, Supplier<List<Object>> facts,
                        Function<List<Object>, Object> uit, LetUitkomst verwacht) {
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> label + " resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> label + " exec sloeg over: " + em.overgeslagen());
        LetUitkomst viaDrl = vuurLet(drlBase(m), facts, uit);
        LetUitkomst viaModel = vuurLet(KieBaseBuilder.createKieBaseFromModel(em.model()), facts, uit);
        assertEquals(verwacht.toString(), viaDrl.toString(), () -> "DRL wijkt af op '" + label + "'");
        assertEquals(verwacht.toString(), viaModel.toString(), () -> "exec-model wijkt af op '" + label + "'");
    }

    private static Supplier<List<Object>> eenPassagier(Consumer<Passagier> setup) {
        return () -> List.of(passagier(setup));
    }
    private static final Function<List<Object>, Object> PLAFOND = l -> ((Passagier) l.get(0)).getPlafond();

    private static final String LET_KOP = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                de bodem Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            """;

    /**
     * uchar §5.6 + ch. 10.6 clause 1: a §9.5 CHANNEL REASON behind a §11 variabele must still be deposited. The
     * first cut of the Let-port lost it — {@code companionDeel}/{@code companionOperand} rejected a {@code
     * BinderRef} operand, so a division by a leeg binder and a negative radicand behind a binder raised NO
     * Inconsistentie where the inlined v0 form raised one. Each shape is pinned here TOGETHER WITH its inlined
     * oracle, so the two can never drift apart again: the count must be 1 in both forms and both back-ends.
     */
    @Test void ch_10_6_kanaalreden_achter_een_binder_blijft_staan() {
        // §6.5 — the DIVISOR sits behind a binder whose value is leeg. The READ of that binder is evaluated
        // BEFORE the division operator applies (it is the division's own right operand), so under ch. 10.6 the
        // barrier fires FIRST and the division never runs: NO reason. (Fix round 1 of this card pinned 1 here —
        // a FABRICATION, corrected against uchar's CompanionPadWitnessTest flank (b).) The inlined oracle below
        // legitimately differs: without a binder there is no barrier, so the division really happens.
        eisLet("§6.5 LEGE binder-deler: de barriere gaat vóór de deling", LET_KOP + """
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als zijn prijs gedeeld door de noemer.
                    Daarbij geldt: de noemer is zijn bodem.
            """, eenPassagier(p -> p.setPrijs(new BigDecimal(10))), PLAFOND, LetUitkomst.ok(null, 0));
        eisLet("§6.5 orakel: dezelfde deling ingelijnd", LET_KOP + """
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als zijn prijs gedeeld door zijn bodem.
            """, eenPassagier(p -> p.setPrijs(new BigDecimal(10))), PLAFOND, LetUitkomst.ok(null, 1));

        // §6.6 — the RADICAND sits behind a binder and is negative.
        eisLet("§6.6 wortel van een NEGATIEVE binder-radicand", LET_KOP + """
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als de wortel van de grond naar beneden afgerond op 0 decimalen.
                    Daarbij geldt: de grond is zijn prijs.
            """, eenPassagier(p -> p.setPrijs(new BigDecimal(-4))), PLAFOND, LetUitkomst.ok(null, 1));
        eisLet("§6.6 orakel: dezelfde wortel ingelijnd", LET_KOP + """
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als de wortel van zijn prijs naar beneden afgerond op 0 decimalen.
            """, eenPassagier(p -> p.setPrijs(new BigDecimal(-4))), PLAFOND, LetUitkomst.ok(null, 1));
    }

    /**
     * The ARBITER flanks, transcribed from uchar's own pinned witness
     * ({@code backends/drools/.../CompanionPadWitnessTest} (b) and its mirror) whose values were produced by
     * {@code ref/} — the normative executor — on the identical shape. A §9.5 companion must fire exactly when the
     * site it guards is actually EVALUATED under ch. 10.6, never for a site the barrier cut off (FABRICAGE) and
     * never silently for one that ran (MISSER). Both directions are pinned, each beside its twin, so an
     * implementation that errs either way fails here.
     *
     * <p>The shape is "uit := Let d = deler in (d plus (inkomen ÷ tweede))" and its mirror with the operands of
     * the plus swapped. {@code tweede} is always absent, so the division is always a §6.5 "deling door leeg" IF
     * it runs; what differs is whether the read of {@code d} precedes it and whether that read raises the barrier.
     */
    @Test void ch_10_6_companion_volgt_het_pad_de_vier_arbiterflanken() {
        String model = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                de bodem Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als %s.
                    Daarbij geldt: de d is zijn basistarief.
            """;
        String lezingEerst = model.formatted("de d plus (zijn prijs gedeeld door zijn bodem)");
        String delingEerst = model.formatted("(zijn prijs gedeeld door zijn bodem) plus de d");
        // (b) the read comes FIRST and the binder is leeg: the barrier ends the Let before the division runs.
        eisLet("(b) lezing eerst, binder LEEG -> geen deling, geen reden", lezingEerst,
            eenPassagier(p -> p.setPrijs(new BigDecimal(10))), PLAFOND, LetUitkomst.ok(null, 0));
        // (b-twin) the same shape with the binder FILLED: the read passes, the division runs and faults.
        eisLet("(b-twin) lezing eerst, binder GEVULD -> de deling draait en faalt", lezingEerst,
            eenPassagier(p -> { p.setPrijs(new BigDecimal(10)); p.setBasistarief(new BigDecimal(2)); }),
            PLAFOND, LetUitkomst.ok(new BigDecimal(2), 1));
        // (b-spiegel) the division is evaluated BEFORE the read, so its reason stands whatever the read then does.
        eisLet("(b-spiegel) deling eerst, dan de barriere -> de reden staat", delingEerst,
            eenPassagier(p -> p.setPrijs(new BigDecimal(10))), PLAFOND, LetUitkomst.ok(null, 1));
        eisLet("(b-spiegel-twin) deling eerst, binder gevuld", delingEerst,
            eenPassagier(p -> { p.setPrijs(new BigDecimal(10)); p.setBasistarief(new BigDecimal(2)); }),
            PLAFOND, LetUitkomst.ok(new BigDecimal(2), 1));

        // ch. 10.6 clause 3 — a division in the value of a binder NO read forces never runs; one in a value that
        // IS read does. (uchar's flank (d).)
        String value = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                de bodem Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als %s.
                    Daarbij geldt: de q is zijn prijs gedeeld door zijn bodem.
            """;
        eisLet("(d) deling in een GELEZEN binder-value -> draait en meldt", value.formatted("de q plus 1"),
            eenPassagier(p -> p.setPrijs(new BigDecimal(10))), PLAFOND, LetUitkomst.ok(null, 1));
        eisLet("(d-twin) dezelfde deling in een ONGELEZEN binder-value -> draait niet", value.formatted("42"),
            eenPassagier(p -> p.setPrijs(new BigDecimal(10))), PLAFOND, LetUitkomst.ok(new BigDecimal(42), 0));
    }

    /**
     * ch. 10.6 order — a {@code Begrensd} evaluates basis, then MAXIMUM, then minimum, and the companion table must
     * model THAT and not the wire order. Evidence: {@code ref/lib/eval.ml} binds the basis with an explicit
     * {@code let} and then passes BOTH bounds as labelled arguments of ONE {@code Arith.clamp} application, which
     * OCaml evaluates right to left. Walking minimum first FABRICATED one companion and MISSED its mirror — the two
     * cases below are exactly that pair, so an implementation that reverts the order fails on both at once.
     */
    @Test void ch_10_6_begrensd_evalueert_maximum_voor_minimum() {
        String grens = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als %s.
                    Daarbij geldt: de v is zijn basistarief.
            """;
        String wortel = "de wortel van zijn leeftijd rekenkundig afgerond op 0 decimalen";
        java.util.function.Consumer<Passagier> leegBinder =
                p -> { p.setPrijs(new BigDecimal(10)); p.setLeeftijd(new BigDecimal(-4)); };
        // The read sits in the MINIMUM, so the MAXIMUM's wortel runs FIRST and deposits its reason; only then does
        // the read raise the barrier and empty the Let.
        eisLet("Begrensd: lezing in minimum, wortel in maximum -> de wortel draait nog",
            grens.formatted("zijn prijs, met een minimum van de v en een maximum van " + wortel),
            eenPassagier(leegBinder), PLAFOND, LetUitkomst.ok(null, 1));
        // The mirror: the read sits in the MAXIMUM, so it runs FIRST, the barrier fires, and the minimum's wortel
        // is never evaluated — no reason at all.
        eisLet("Begrensd: wortel in minimum, lezing in maximum -> de wortel draait niet",
            grens.formatted("zijn prijs, met een minimum van " + wortel + " en een maximum van de v"),
            eenPassagier(leegBinder), PLAFOND, LetUitkomst.ok(null, 0));
    }

    /**
     * The barrier precondition must be EXPRESSIBLE for a composite binder definition, not only for a bare attribute
     * read. Refusing there dropped four real companions — a MISSER, and the b-spiegel flank is the standing proof
     * that missing is just as wrong as fabricating. Each definition below is a different shape of "this binder can
     * never be leeg, so the barrier never fires and the division really runs":
     * a coalescing {@code plus} (ch. 1.2 makes it a number whatever the attribute holds), a literal, and a
     * begrenzing whose leegte is its basis's. All three must report exactly as the bare-read control does.
     */
    @Test void ch_10_6_barriere_preconditie_draagt_samengestelde_definities() {
        String weiger = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
                de bodem Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                het plafond Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als de d plus (zijn prijs gedeeld door zijn bodem).
                    Daarbij geldt: de d is %s.
            """;
        java.util.function.Consumer<Passagier> gevuld =
                p -> { p.setPrijs(new BigDecimal(10)); p.setBasistarief(new BigDecimal(2)); };
        eisLet("preconditie-ijkpunt: definitie is een kale attribuutlezing",
            weiger.formatted("zijn basistarief"), eenPassagier(gevuld), PLAFOND, LetUitkomst.ok(new BigDecimal(2), 1));
        eisLet("preconditie: samengestelde definitie (plus coalesceert, dus nooit leeg)",
            weiger.formatted("zijn basistarief plus 1"), eenPassagier(gevuld), PLAFOND, LetUitkomst.ok(new BigDecimal(3), 1));
        eisLet("preconditie: literal-definitie (nooit leeg)",
            weiger.formatted("2"), eenPassagier(gevuld), PLAFOND, LetUitkomst.ok(new BigDecimal(2), 1));
        eisLet("preconditie: begrensde definitie (leeg precies als haar basis)",
            weiger.formatted("zijn basistarief, met een minimum van 0"), eenPassagier(gevuld), PLAFOND,
            LetUitkomst.ok(new BigDecimal(2), 1));
    }

    /**
     * §11 a TEMPORAL doel with a variabelendeel must still LOWER. {@code temporeleWaarde} dispatches on the
     * syntactic shape of its operand, so an opaque {@code BinderRef} degraded the whole construct to a marker (DRL)
     * and a skip (exec) — silent coverage loss against main, which only ever saw pre-inlined trees. The remedy is
     * uchar's own boundary (c) applied locally: substitute for the shape, carry the ch. 10.6 barrier as a pattern
     * guard. This pins the COVERAGE (no marker, no skip) so the regression cannot return unnoticed.
     */
    @Test void ch_10_6_temporeel_doel_met_variabelendeel_lowert() {
        String kop = """
            Domein Bedrag is van het type Numeriek (geheel getal)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de inkomen per maand Bedrag voor elke maand;
                de standaardbedrag Bedrag voor elke maand;
            """;
        String zonder = kop + """
            Regel R
                geldig altijd
                    De standaardbedrag van een Passagier moet berekend worden als zijn belastingvermindering per maand plus zijn inkomen per maand.
            """;
        String met = kop + """
            Regel R
                geldig altijd
                    De standaardbedrag van een Passagier moet berekend worden als de v plus zijn inkomen per maand.
                    Daarbij geldt: de v is zijn belastingvermindering per maand.
            """;
        // the control: without a variabelendeel the temporal path lowers in both back-ends.
        RegelSpraakModel ijk = Resolver.resolve(zonder);
        assertTrue(ijk.errors().isEmpty(), () -> "resolver-fout (ijkpunt): " + ijk.errors());
        assertFalse(DrlTranspiler.transpile(ijk).contains("TODO temporele toekenning"), "ijkpunt markert al");
        assertTrue(ExecModelTranspiler.build(ijk).overgeslagen().isEmpty(), "ijkpunt slaat al over");

        RegelSpraakModel m = Resolver.resolve(met);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("TODO temporele toekenning"),
                () -> "het temporele pad markert de Let weer weg (dekkingsverlies t.o.v. main):\n" + drl);
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(),
                () -> "exec laat de temporele regel vallen: " + em.overgeslagen());
        // and the barrier is really carried: the rule only fires while the read variabele is gevuld.
        assertTrue(drl.contains("belastingverminderingPerMaand != null"),
                () -> "de ch. 10.6 barriere ontbreekt in het temporele patroon:\n" + drl);
    }

    /** uchar §6.8, the THIRD arm of the same companion gate: a leeg §9.9 startpunt anchor behind a binder. The
     *  first cut dropped this companion too (measured on the emitted DRL: the inlined form kept its
     *  "(leeg startpunt)" rule, the binder form emitted none). Pinned as a COUNT, and beside its inlined oracle. */
    @Test void ch_10_6_leeg_startpunt_anker_achter_een_binder_blijft_gemeld() {
        Supplier<List<Object>> twee = () -> List.of(
                passagier(p -> { p.setLeeftijd(new BigDecimal(1)); p.setGeboortedatum(LocalDate.of(2024, 1, 15)); }),
                passagier(p -> p.setLeeftijd(new BigDecimal(2))));   // leeg anker -> flagged
        Function<List<Object>, Object> geenWaarde = l -> "n.v.t.";
        eisLet("§6.8 leeg startpunt-anker achter een binder", STARTPUNT_HDR + """
            Regel Startpunt contractperiode
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door de aanvang.
                    Daarbij geldt: de aanvang is zijn geboortedatum.
            """, twee, geenWaarde, LetUitkomst.ok("n.v.t.", 1));
        eisLet("§6.8 orakel: hetzelfde anker ingelijnd", STARTPUNT_HDR + """
            Regel Startpunt contractperiode
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door zijn geboortedatum.
            """, twee, geenWaarde, LetUitkomst.ok("n.v.t.", 1));
    }

    /**
     * ch. 10.6 clauses 2+3 — a binder value is forced only when a read REACHES it, in READ order. The first cut
     * declared every binder up front, so a value the reader never reaches still ran; where such a value FAULTS
     * (a division by zero — {@code rsGedeeld} is total on null but not on nul) the DRL back-end died while the
     * exec-model back-end, which forces a genuine thunk, answered leeg.
     *
     * <p>The three cases together pin that the fix follows the READ order and not the DECLARATION order — which
     * is exactly what separates 5d from 5f: the same two binders, the same data, only the body's read order
     * reversed.
     */
    @Test void ch_10_6_luie_leesvolgorde_forceert_alleen_wat_gelezen_wordt() {
        String risicoRegel = """
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als %s.
                    Daarbij geldt: de grondslag is zijn basistarief. de risico is zijn prijs gedeeld door zijn bodem.
            """;
        // 5d: the FIRST read (grondslag) is leeg -> barrier; 'risico' is never forced, so its /0 never runs.
        eisLet("ch. 10.6 luie leesvolgorde: barriere vóór een faulting value",
            LET_KOP + risicoRegel.formatted("de grondslag plus de risico"),
            eenPassagier(p -> { p.setPrijs(new BigDecimal(10)); p.setBodem(BigDecimal.ZERO); }),
            PLAFOND, LetUitkomst.ok(null, 0));
        // 5e: control — grondslag is FILLED, so the read reaches 'risico' and the /0 really happens. Both fault.
        eisLet("ch. 10.6 controle: gevulde eerste binder laat de fault wél toe",
            LET_KOP + risicoRegel.formatted("de grondslag plus de risico"),
            eenPassagier(p -> { p.setPrijs(new BigDecimal(10)); p.setBodem(BigDecimal.ZERO); p.setBasistarief(new BigDecimal(5)); }),
            PLAFOND, new LetUitkomst(null, -1, "ConsequenceException"));
        // P5a: the fault sits LEFT of the read — it is evaluated BEFORE the barrier can fire, so it stands.
        //      (Fix round 1 hoisted ONE guard around the whole body and swallowed it: DRL answered leeg where
        //      exec faulted — a differential break as well as a spec violation.)
        String linksRegel = """
            Regel R
                geldig altijd
                    Het plafond van een Passagier moet berekend worden als %s.
                    Daarbij geldt: de v is zijn basistarief.
            """;
        eisLet("P5a fault LINKS van de lezing, binder leeg -> de fault staat",
            LET_KOP + linksRegel.formatted("(zijn prijs gedeeld door zijn bodem) plus de v"),
            eenPassagier(p -> { p.setPrijs(new BigDecimal(10)); p.setBodem(BigDecimal.ZERO); }),
            PLAFOND, new LetUitkomst(null, -1, "ConsequenceException"));
        eisLet("P5b controle: dezelfde vorm met een GEVULDE binder",
            LET_KOP + linksRegel.formatted("(zijn prijs gedeeld door zijn bodem) plus de v"),
            eenPassagier(p -> { p.setPrijs(new BigDecimal(10)); p.setBodem(BigDecimal.ZERO); p.setBasistarief(new BigDecimal(5)); }),
            PLAFOND, new LetUitkomst(null, -1, "ConsequenceException"));
        // P5c: the same fault RIGHT of the read — the barrier fires first, so it never happens.
        eisLet("P5c fault RECHTS van de lezing, binder leeg -> geen fault",
            LET_KOP + linksRegel.formatted("de v plus (zijn prijs gedeeld door zijn bodem)"),
            eenPassagier(p -> { p.setPrijs(new BigDecimal(10)); p.setBodem(BigDecimal.ZERO); }),
            PLAFOND, LetUitkomst.ok(null, 0));
        // 5f: the SAME binders and data as 5d, read order REVERSED — now 'risico' is forced FIRST, so the fault
        //     stands. Declaration order is unchanged, so this is what proves the lowering follows READ order.
        eisLet("ch. 10.6 leesvolgorde omgedraaid: de eerst-gelezen binder faalt wél",
            LET_KOP + risicoRegel.formatted("de risico plus de grondslag"),
            eenPassagier(p -> { p.setPrijs(new BigDecimal(10)); p.setBodem(BigDecimal.ZERO); }),
            PLAFOND, new LetUitkomst(null, -1, "ConsequenceException"));
    }

    @Test void phase_11_daarbij_geldt_matches_drl_back_end()   { runDifferential("Phase 11", PHASE_11, 3); }

    /** §11.1: assert the DRL oracle against the absolute spec value (a lowering bug shared by both back-ends
     *  would pass a pure differential), and that the model agrees. Renamed at the Let-port — the variabelendeel is
     *  no longer an inline substitution in the result part — but every pinned value below is untouched. */
    @Test void phase_11_variabelendeel_is_spec_correct() {
        for (int i = 0; i < PHASE_11_ABS.length; i++) {
            Fixture f = PHASE_11_ABS[i];
            Object expected = PHASE_11_ABS_EXPECTED[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(expected), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    /** §8.2 regression (Phase F adversarial review): a temporal attribute read into a SCALAR doel must be an HONEST
     *  SKIP, not a firing rule that calls setX(List<Segment>) and throws (the one scalarWaarde gap the review caught,
     *  where DRL honestly markers the read). The model must record overgeslagen, never mis-render. */
    @Test void phase_F_temporal_read_into_scalar_doel_is_honestly_skipped() {
        for (String soort : new String[]{"moet gesteld worden op", "moet berekend worden als"}) {
            String src = """
                Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
                Objecttype de Passagier (mv: Passagiers) (bezield)
                    de belastingvermindering per maand Bedrag voor elke maand;
                    de prijs Bedrag;
                Regel R
                    geldig altijd
                        De prijs van een Passagier %s zijn belastingvermindering per maand.
                """.formatted(soort);
            RegelSpraakModel m = Resolver.resolve(src);
            assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertFalse(em.overgeslagen().isEmpty(),
                    () -> "model moet de temporele-lezing-in-scalair-doel SKIPPEN (" + soort + "), niet mis-renderen tot een crashende regel");
        }
    }

    /** §5.7/§13.4.17.2 a bare "de concatenatie van X, Y en Z" as a STANDALONE gelijkstelling VALUE has NO scalar
     *  meaning: §5.8.1 says a concatenatie is ALWAYS meervoudig, so it is only defined once an aggregation (het
     *  aantal / de som / de min-max / de eerste-laatste) consumes it — a bare concatenatie assigned to a scalar
     *  attribute is a multi-valued-into-scalar with no spec semantics. (And "de concatenatie van" is NOT even a
     *  spec value keyword: it belongs to §13.4.8.14 uniciteitconcatenatie only; the real §13.4.17.2 value
     *  concatenatie has no keyword and rides inline in each aggregation's operand list — the aantal/min/max folds.)
     *  So BOTH back-ends decline it SYMMETRICALLY: the DRL markers ({@code TODO aggregatie CONCATENATIE}), the exec
     *  skips (overgeslagen). This pins the symmetry AND guards the resolver fix: typing the kind Tekst used to
     *  fabricate a spurious "Tekst→Numeriek" mismatch over numeric operands that MASKED the honest marker — the
     *  resolver now leaves it onbekend, so a numeric-operand concatenatie resolves clean and markers like a
     *  tekst-operand one. See docs/grammar-divergences.md "concatenatie as a standalone value". */
    @Test void concatenatie_als_standalone_waarde_is_een_symmetrische_marker() {
        record Geval(String naam, String src) {}
        Geval[] gevallen = {
            // numeric operands: post-fix this resolves clean (was a spurious Tekst→Numeriek ERROR that masked the marker)
            new Geval("numerieke operanden", """
                Objecttype de Passagier (mv: Passagiers) (bezield)
                    de prijs Numeriek (geheel getal);
                    het basistarief Numeriek (geheel getal);
                    de bodem Numeriek (geheel getal);
                    het plafond Numeriek (geheel getal);
                Regel R
                    geldig altijd
                        De prijs van een Passagier moet gesteld worden op de concatenatie van het basistarief van een Passagier, de bodem van een Passagier en het plafond van een Passagier.
                """),
        };
        for (Geval g : gevallen) {
            RegelSpraakModel m = Resolver.resolve(g.src());
            assertTrue(m.errors().isEmpty(),
                    () -> "een bare concatenatie-waarde mag geen resolver-fout geven (" + g.naam() + "), enkel een symmetrische marker: " + m.errors());
            // DRL: the honest marker, never a silently-wrong scalar fold.
            String drl = DrlTranspiler.transpile(m);
            assertTrue(drl.contains("/* TODO aggregatie CONCATENATIE"),
                    () -> "DRL moet de concatenatie MARKEREN (" + g.naam() + "):\n" + drl);
            // exec: the SAME decline, recorded as an honest skip (never a mis-render).
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertFalse(em.overgeslagen().isEmpty(),
                    () -> "exec moet de bare concatenatie-waarde SKIPPEN (" + g.naam() + "), symmetrisch met de DRL-marker: " + em.overgeslagen());
        }
    }

    /** §8.4/§13.4.5 a KENMERKTOEKENNING whose kenmerk is TEMPORAL (kenmerkGranulariteit — its runtime field is a
     *  List&lt;Segment&gt; TRUE-period timeline, not a boolean) is LOWERED SYMMETRICALLY when its voorwaarde is a
     *  "gedurende de tijd dat &lt;temporeel kenmerk&gt;": §3.8 a kenmerk-tijdlijn IS a boolean-tijdlijn, so the doel
     *  holds exactly where the gating kenmerk does (§3.4 default-onwaar elsewhere — monotone). The write producer is
     *  the SAME conditieTijdreeks lowering the §7.1 gedurende READ uses, so a WRITTEN kenmerk-tijdlijn can then DRIVE a
     *  gedurende-voorwaarde. This fixture pins the WRITE→READ chain: rule R1 writes {@code rechthebbend} from a seeded
     *  bezittelijk source kenmerk, rule R2's §7.1 totaal "gedurende de tijd dat hij rechthebbend is" reads it back —
     *  and BOTH back-ends must AGREE (no skip/marker) AND match the spec value (6×10 + 1×11 = 71). The residual
     *  UNDER-SPECIFIED case (an EMPTY voorwaarde is an UNBOUNDED true-timeline; a geldigheids-window needs a
     *  timeline×window clip §8.4 leaves open) stays a symmetric decline — the DRL inert /* TODO *&#47; marker leaves the
     *  timeline NULL, the exec records overgeslagen. */
    @Test void temporal_kenmerktoekenning_gedurende_lowert_symmetrisch() {
        // R1: a temporal kenmerktoekenning driven by a "gedurende de tijd dat <bezittelijk temporeel kenmerk>"
        // voorwaarde -> rechthebbend inherits the source kenmerk's TRUE periods. R2: a §7.1 totaal READS the WRITTEN
        // kenmerk-tijdlijn back (the write-then-consume chain), clipping the value to 6×10 + 1×11 = 71.
        String src = TOTAAL_HDR + """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                is rechthebbend kenmerk (bijvoeglijk) voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel Rechthebbend
                geldig altijd
                    Een Passagier is rechthebbend gedurende de tijd dat hij een recht op belastingvermindering heeft.
            Regel Totaal
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat hij rechthebbend is).
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());

        // BOTH back-ends must lower the WHOLE model — no skip on the temporal kenmerktoekenning.
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(),
                () -> "exec moet de gedurende-gedreven temporele kenmerktoekenning LOWEREN, niet skippen: " + em.overgeslagen());

        Fixture f = single("temporele kenmerktoekenning (gedurende) schrijft + wordt gelezen", src,
                () -> passagier(p -> {
                    p.setBelastingverminderingPerMaand(List.of(seg(2023, 1, 1, 2024, 1, 1, "10.00"), seg(2024, 1, 1, 2024, 7, 1, "11.00")));
                    p.setRechtOpBelastingvermindering(List.of(seg(2023, 7, 1, 2024, 2, 1, null)));
                }),
                Map.of(), Passagier::getTotaleBelastingvermindering);
        Object viaDrl = fire(drlBase(m), f);
        Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
        assertEquals(new BigDecimal("71.00"), viaDrl, () -> "DRL (oracle) verkeerd: de geschreven kenmerk-tijdlijn moet 71 opleveren, kreeg " + viaDrl);
        assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends WIJKEN AF op de geschreven+gelezen kenmerk-tijdlijn: drl=" + viaDrl + " model=" + viaModel);
    }

    /** §8.4/§13.4.5 the residual UNDER-SPECIFIED temporal kenmerktoekenning — an EMPTY voorwaarde ("geldig altijd, geen
     *  gedurende") is an UNBOUNDED true-timeline with no finite List&lt;Segment&gt; producer — stays a SYMMETRIC
     *  DECLINE: the exec records overgeslagen, the DRL emits an inert /* TODO *&#47; marker (the rule COMPILES + FIRES,
     *  a `setK(null)` would WIPE a seeded timeline, so the marker writes NOTHING). Never a crash, never a mis-typed
     *  scalar boolean into the List field. */
    @Test void temporal_kenmerktoekenning_zonder_gedurende_is_een_symmetrische_decline() {
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal);
                is rechthebbend kenmerk (bijvoeglijk) voor elke maand;
            Regel Rechthebbend
                geldig altijd
                    Een Passagier is rechthebbend.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());

        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().stream().anyMatch(s -> s.contains("temporele kenmerktoekenning")),
                () -> "exec skip moet de onbepaalde temporele kenmerktoekenning benoemen: " + em.overgeslagen());

        // DRL: MUST compile (drlBase asserts no ERROR), and firing must leave the temporal kenmerk field NULL.
        Fixture f = single("temporele kenmerktoekenning (geen gedurende)", src,
                () -> passagier(p -> p.setLeeftijd(new BigDecimal(20))), Map.of(), Passagier::getRechthebbend);
        Object viaDrl = fire(drlBase(m), f);
        assertNull(viaDrl, () -> "DRL moet de tijdlijn ONGEMOEID laten (inert marker), niet schrijven: " + viaDrl);
    }

    // ---- Phase G fixtures: §13.4.7 feitcreatie (a created relation = a reference field set on the doel) ----
    /** The index of {@code ref} within the facts list (stable across both back-ends' fresh facts), or null when the
     *  reference is unset — so a created relation compares by WHICH fact it points at, not by (per-fire) identity. */
    private static Object refIndex(List<Object> facts, Object ref) {
        if (ref == null) return null;
        for (int i = 0; i < facts.size(); i++) if (facts.get(i) == ref) return i;
        return -1;
    }
    private static final String FEIT_HDR = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                is student kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            """;
    static final Fixture[] PHASE_G_FEIT = {
        // F1 §13.4.7 1-hop: every passenger of a vlucht gets the bonus relation set to its vlucht.
        new Fixture("§13.4.7 feitcreatie 1-hop legt de relatie op alle passagiers van de vlucht",
            FEIT_HDR + """
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht.
            """,
            () -> {
                Vlucht v1 = new Vlucht(), v2 = new Vlucht();
                Passagier p1 = new Passagier(), p2 = new Passagier(), p3 = new Passagier(), zonder = new Passagier();
                p1.setReis(v1); p2.setReis(v1); p3.setReis(v2);
                return List.of(v1, v2, p1, p2, p3, zonder);
            },
            Map.of(),
            l -> vals(refIndex(l, ((Passagier) l.get(2)).getBonusvlucht()), refIndex(l, ((Passagier) l.get(3)).getBonusvlucht()),
                      refIndex(l, ((Passagier) l.get(4)).getBonusvlucht()), refIndex(l, ((Passagier) l.get(5)).getBonusvlucht()))),   // [0,0,1,null]

        // F2 §13.4.7 doel comparison guard: only passengers with prijs > 18 get the relation.
        new Fixture("§13.4.7 feitcreatie doel-vergelijking-voorwaarde koppelt alleen prijs>18",
            FEIT_HDR + """
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht indien de prijs van de passagier groter is dan 18.
            """,
            () -> {
                Vlucht v = new Vlucht();
                Passagier rijk = new Passagier(), arm = new Passagier();
                rijk.setReis(v); rijk.setPrijs(new BigDecimal(99));
                arm.setReis(v); arm.setPrijs(new BigDecimal(5));
                return List.of(v, rijk, arm);
            },
            Map.of(),
            l -> vals(refIndex(l, ((Passagier) l.get(1)).getBonusvlucht()), refIndex(l, ((Passagier) l.get(2)).getBonusvlucht()))),   // [0, null]

        // F3 §13.4.7 doel kenmerk guard: only student passengers get the relation.
        new Fixture("§13.4.7 feitcreatie doel-kenmerk-voorwaarde koppelt alleen studenten",
            FEIT_HDR + """
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht indien de passagier student is.
            """,
            () -> {
                Vlucht v = new Vlucht();
                Passagier a = new Passagier(), b = new Passagier();
                a.setReis(v); a.setStudent(true);
                b.setReis(v);
                return List.of(v, a, b);
            },
            Map.of(),
            l -> vals(refIndex(l, ((Passagier) l.get(1)).getBonusvlucht()), refIndex(l, ((Passagier) l.get(2)).getBonusvlucht()))),   // [0, null]

        // F9 §13.4.7 anker kenmerk guard: only internationale vluchten's passengers get the relation.
        new Fixture("§13.4.7 feitcreatie anker-kenmerk-voorwaarde vuurt alleen voor internationale vluchten",
            """
            Objecttype de Vlucht
                de naam Tekst;
                is internationaal kenmerk (bijvoeglijk);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht indien de vlucht internationaal is.
            """,
            () -> {
                Vlucht intl = new Vlucht(), binnen = new Vlucht();
                intl.setInternationaal(true);
                Passagier p1 = new Passagier(), p2 = new Passagier();
                p1.setReis(intl); p2.setReis(binnen);
                return List.of(intl, binnen, p1, p2);
            },
            Map.of(),
            l -> vals(refIndex(l, ((Passagier) l.get(2)).getBonusvlucht()), refIndex(l, ((Passagier) l.get(3)).getBonusvlucht()))),   // [0, null]

        // F8 §9.4 multi-hop chain Contingent -> Vlucht -> passagier: the relation rides the navigation path.
        new Fixture("§9.4 feitcreatie meerstaps legt de relatie over de navigatieketen",
            """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Objecttype de Contingent
                de naam Tekst;
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype contingentrelatie
                de contingentvlucht Vlucht
                de contingent Contingent
            Eén contingentvlucht heeft meerdere contingenten
            Feittype bonusrelatie
                de bonuscontingent Contingent
                de bonuspassagier Passagier
            Eén bonuscontingent kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een contingent is een passagier van de contingentvlucht van het contingent.
            """,
            () -> {
                Vlucht vlucht = new Vlucht(), andere = new Vlucht();
                Contingent c = new Contingent(); c.setContingentvlucht(vlucht);
                Passagier p1 = new Passagier(), p2 = new Passagier(), q = new Passagier();
                p1.setReis(vlucht); p2.setReis(vlucht); q.setReis(andere);
                return List.of(vlucht, andere, c, p1, p2, q);
            },
            Map.of(),
            l -> vals(refIndex(l, ((Passagier) l.get(3)).getBonuscontingent()), refIndex(l, ((Passagier) l.get(4)).getBonuscontingent()),
                      refIndex(l, ((Passagier) l.get(5)).getBonuscontingent()))),   // [2,2,null]

        // F4 §13.4.7 an un-lowerable voorwaarde makes the rule INERT — no over-creation.
        new Fixture("§13.4.7 feitcreatie met onlowerbare voorwaarde creeert niets (geen over-creatie)",
            FEIT_HDR + """
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht indien de passagier een werkdag is.
            """,
            () -> {
                Vlucht v = new Vlucht();
                Passagier a = new Passagier(), b = new Passagier();
                a.setReis(v); b.setReis(v);
                return List.of(v, a, b);
            },
            Map.of(),
            l -> vals(refIndex(l, ((Passagier) l.get(1)).getBonusvlucht()), refIndex(l, ((Passagier) l.get(2)).getBonusvlucht()))),   // [null, null]
    };

    // ---- Phase H §4.2 VERSIE-GELDIGHEID: a dated (geldig vanaf/tot/t-m) construct fires only when the rekendatum
    //      global is inside its [vanaf, tot] window. The model now registers a LocalDate `rekendatum` global + a
    //      per-construct window guard (geldigGuard), mirroring DrlTranspiler.geldigheidGuard. The Fixture globals map
    //      sets rekendatum on BOTH sessions; an out-of-window rekendatum -> the rule does not fire (attr stays null). ----
    static final String PRIJS_OT = "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n";
    static final String DATED_REGEL = PRIJS_OT + "Regel R\n    geldig vanaf 2018\n        De prijs van een passagier moet gesteld worden op 50.\n";
    static final String WINDOW_REGEL = PRIJS_OT + "Regel R\n    geldig vanaf 01-07-2022 t/m 31-12-2022\n        De prijs van een passagier moet gesteld worden op 50.\n";
    static final String MULTI_REGEL = PRIJS_OT + "Regel R\n    geldig t/m 2019\n        De prijs van een passagier moet gesteld worden op 10.\n"
            + "    geldig vanaf 2020\n        De prijs van een passagier moet gesteld worden op 20.\n";
    static final Fixture[] PHASE_GELDIG = {
        new Fixture("geldig vanaf 2018, rekendatum 2020 inside -> fires",
            DATED_REGEL, () -> List.of(new Passagier()), Map.of("rekendatum", LocalDate.of(2020, 1, 1)),
            l -> ((Passagier) l.get(0)).getPrijs()),   // 50

        new Fixture("geldig vanaf 2018, rekendatum 2010 before vanaf -> no fire",
            DATED_REGEL, () -> List.of(new Passagier()), Map.of("rekendatum", LocalDate.of(2010, 1, 1)),
            l -> ((Passagier) l.get(0)).getPrijs()),   // null

        new Fixture("geldig window 01-07-2022 t/m 31-12-2022, rekendatum 2022-09 inside -> fires",
            WINDOW_REGEL, () -> List.of(new Passagier()), Map.of("rekendatum", LocalDate.of(2022, 9, 1)),
            l -> ((Passagier) l.get(0)).getPrijs()),   // 50

        new Fixture("geldig window 01-07-2022 t/m 31-12-2022, rekendatum 2023-01 after tot -> no fire",
            WINDOW_REGEL, () -> List.of(new Passagier()), Map.of("rekendatum", LocalDate.of(2023, 1, 1)),
            l -> ((Passagier) l.get(0)).getPrijs()),   // null

        // a boundary: rekendatum exactly on the inclusive `tot` (31-12-2022) still fires (isAfter is strict).
        new Fixture("geldig window, rekendatum exactly on the inclusive tot -> fires",
            WINDOW_REGEL, () -> List.of(new Passagier()), Map.of("rekendatum", LocalDate.of(2022, 12, 31)),
            l -> ((Passagier) l.get(0)).getPrijs()),   // 50

        // multi-version rule (resolver disambiguates "R #1"/"R #2"): each version's window guards it; the rekendatum
        // selects which fires. The model builds two distinct guarded rules — no name collision.
        new Fixture("multi-version rule, rekendatum 2018 selects the t/m-2019 version (10)",
            MULTI_REGEL, () -> List.of(new Passagier()), Map.of("rekendatum", LocalDate.of(2018, 6, 1)),
            l -> ((Passagier) l.get(0)).getPrijs()),   // 10

        new Fixture("multi-version rule, rekendatum 2021 selects the vanaf-2020 version (20)",
            MULTI_REGEL, () -> List.of(new Passagier()), Map.of("rekendatum", LocalDate.of(2021, 6, 1)),
            l -> ((Passagier) l.get(0)).getPrijs()),   // 20

        // (adversary) boundary: rekendatum exactly on the inclusive `vanaf` (geldig vanaf 2018 = 2018-01-01) fires.
        new Fixture("geldig vanaf 2018, rekendatum exactly on 2018-01-01 -> fires",
            DATED_REGEL, () -> List.of(new Passagier()), Map.of("rekendatum", LocalDate.of(2018, 1, 1)),
            l -> ((Passagier) l.get(0)).getPrijs()),   // 50

        // (adversary) an ALTIJD rule COEXISTS with a dated rule: the rekendatum global is registered (a dated rule
        // exists), but the altijd rule must NOT be guarded — it fires regardless of rekendatum. rekendatum 2010 is
        // before the dated window, so basistarief := 7 (altijd) fires and prijs (dated) does not.
        new Fixture("altijd rule unguarded while a sibling dated rule is window-guarded",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n"
            + "Regel A\n    geldig altijd\n        Het basistarief van een passagier moet gesteld worden op 7.\n"
            + "Regel D\n    geldig vanaf 2018\n        De prijs van een passagier moet gesteld worden op 50.\n",
            () -> List.of(new Passagier()), Map.of("rekendatum", LocalDate.of(2010, 1, 1)),
            l -> vals(((Passagier) l.get(0)).getBasistarief(), ((Passagier) l.get(0)).getPrijs())),   // 7, null

        // (adversary) an UNSET rekendatum (the global is declared but never set): the dated rule does not fire — DRL's
        // `rekendatum != null` guard and the model's `rd instanceof LocalDate` guard both no-match. No global in the map.
        new Fixture("dated rule with an UNSET rekendatum -> no fire (both back-ends)",
            DATED_REGEL, () -> List.of(new Passagier()), Map.of(),
            l -> ((Passagier) l.get(0)).getPrijs()),   // null

        // cross-family: a dated FEITCREATIE — the relation is created only inside the window (the guard rides the anker).
        new Fixture("dated feitcreatie, rekendatum 2020 inside -> relation created",
            FEIT_HDR + "Regel ken bonus toe\n    geldig vanaf 2018\n        Een bonuspassagier van een vlucht is een passagier van de vlucht.\n",
            () -> { Vlucht v = new Vlucht(); Passagier p = new Passagier(); p.setReis(v); return List.of(v, p); },
            Map.of("rekendatum", LocalDate.of(2020, 1, 1)),
            l -> refIndex(l, ((Passagier) l.get(1)).getBonusvlucht())),   // 0

        new Fixture("dated feitcreatie, rekendatum 2010 before vanaf -> no relation",
            FEIT_HDR + "Regel ken bonus toe\n    geldig vanaf 2018\n        Een bonuspassagier van een vlucht is een passagier van de vlucht.\n",
            () -> { Vlucht v = new Vlucht(); Passagier p = new Passagier(); p.setReis(v); return List.of(v, p); },
            Map.of("rekendatum", LocalDate.of(2010, 1, 1)),
            l -> refIndex(l, ((Passagier) l.get(1)).getBonusvlucht())),   // null
    };

    // ---- §8.4.3 / §13.4.15.68-.69 PERIODEVERGELIJKING as a voorwaarde: standalone after the resultaatdeel
    //      (the §13.4.13.1 branch, "… van dd. 1-1-2024 tot dd. 8-2-2025") and as the "het is de periode …"
    //      bullet inside a compound (§13.4.15.69, the spec-main-99 shape). The engine evaluates scalar rules
    //      as of the §4.2 Rekendatum, so the condition is "rekendatum inside the period" — §5.1.3 bounds:
    //      vanaf INCLUSIVE, tot EXCLUSIVE, tot en met INCLUSIVE. Each fixture pins the ABSOLUTE spec value
    //      (fires 50 / stays null), not just DRL==exec parity. ----
    static final String PERIODE_REGEL = PRIJS_OT + "Regel R\n    geldig altijd\n"
            + "        De prijs van een passagier moet gesteld worden op 50\n"
            + "        van dd. 1-1-2024 tot dd. 8-2-2025.\n";
    static final String PERIODE_TEM_REGEL = PRIJS_OT + "Regel R\n    geldig altijd\n"
            + "        De prijs van een passagier moet gesteld worden op 50\n"
            + "        van dd. 1-1-2024 tot en met dd. 8-2-2025.\n";
    static final String PERIODE_VANAF_REGEL = PRIJS_OT + "Regel R\n    geldig altijd\n"
            + "        De prijs van een passagier moet gesteld worden op 50\n"
            + "        vanaf dd. 1-1-2024.\n";
    static final String PERIODE_TOT_REGEL = PRIJS_OT + "Regel R\n    geldig altijd\n"
            + "        De prijs van een passagier moet gesteld worden op 50\n"
            + "        tot dd. 1-1-2024.\n";
    // the §8.4.3 / spec-main-99 compound shape: "gedurende de tijd dat … • <conditie> • het is de periode …".
    static final String PERIODE_COMPOUND_OT = "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
            + "    de prijs Numeriek (geheel getal);\n    is student kenmerk (bijvoeglijk);\n";
    static final String PERIODE_COMPOUND_REGEL = PERIODE_COMPOUND_OT + "Regel R\n    geldig altijd\n"
            + "        De prijs van een Passagier moet gesteld worden op 50\n"
            + "        gedurende de tijd dat er aan alle volgende voorwaarden wordt voldaan:\n"
            + "        • hij student is\n"
            + "        • het is de periode van dd. 1-1-2024 tot dd. 8-2-2025.\n";
    static final String PERIODE_EIGEN_ATTR_REGEL = "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
            + "    de prijs Numeriek (geheel getal);\n    de geboortedatum Datum in dagen;\n"
            + "Regel R\n    geldig altijd\n"
            + "        De prijs van een passagier moet gesteld worden op 50\n"
            + "        vanaf zijn geboortedatum.\n";
    static final Fixture[] PHASE_PERIODE = {
        single("van..tot, rekendatum inside -> fires",
            PERIODE_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::getPrijs),          // 50
        single("van..tot, rekendatum on the inclusive vanaf bound -> fires",
            PERIODE_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2024, 1, 1)), Passagier::getPrijs),          // 50
        single("van..tot, rekendatum on the EXCLUSIVE tot bound (§5.1.3) -> no fire",
            PERIODE_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2025, 2, 8)), Passagier::getPrijs),          // null
        single("van..tot, rekendatum before vanaf -> no fire",
            PERIODE_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2023, 12, 31)), Passagier::getPrijs),        // null
        single("van..tot en met, rekendatum on the INCLUSIVE tot-en-met bound (§5.1.3) -> fires",
            PERIODE_TEM_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2025, 2, 8)), Passagier::getPrijs),      // 50
        single("van..tot en met, rekendatum a day past the bound -> no fire",
            PERIODE_TEM_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2025, 2, 9)), Passagier::getPrijs),      // null
        single("vanaf (one-sided), rekendatum far after -> fires",
            PERIODE_VANAF_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2030, 1, 1)), Passagier::getPrijs),    // 50
        single("vanaf (one-sided), rekendatum before -> no fire",
            PERIODE_VANAF_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2023, 6, 1)), Passagier::getPrijs),    // null
        single("tot (one-sided, exclusive), rekendatum the day before -> fires",
            PERIODE_TOT_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2023, 12, 31)), Passagier::getPrijs),    // 50
        single("tot (one-sided, exclusive), rekendatum on the bound -> no fire",
            PERIODE_TOT_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2024, 1, 1)), Passagier::getPrijs),      // null
        single("periodevergelijking with an UNSET rekendatum -> no fire",
            PERIODE_REGEL, Passagier::new, Map.of(), Passagier::getPrijs),                                                // null
        // the spec-main-99 compound: both the kenmerk-bullet AND the periode-bullet must hold.
        single("compound ALLE: student + periode inside -> fires",
            PERIODE_COMPOUND_REGEL, () -> passagier(p -> p.setStudent(true)),
            Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::getPrijs),                                         // 50
        single("compound ALLE: student + rekendatum outside the periode -> no fire",
            PERIODE_COMPOUND_REGEL, () -> passagier(p -> p.setStudent(true)),
            Map.of("rekendatum", LocalDate.of(2026, 1, 1)), Passagier::getPrijs),                                         // null
        single("compound ALLE: periode inside but NOT a student -> no fire",
            PERIODE_COMPOUND_REGEL, Passagier::new,
            Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::getPrijs),                                         // null
        // an OWN-attribute bound ("vanaf zijn geboortedatum"): per-instance window; a leeg bound never matches.
        single("own-attr bound: rekendatum after the subject's geboortedatum -> fires",
            PERIODE_EIGEN_ATTR_REGEL, () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2024, 1, 1))),
            Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::getPrijs),                                         // 50
        single("own-attr bound: rekendatum before the subject's geboortedatum -> no fire",
            PERIODE_EIGEN_ATTR_REGEL, () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2024, 1, 1))),
            Map.of("rekendatum", LocalDate.of(2023, 6, 1)), Passagier::getPrijs),                                         // null
        single("own-attr bound: LEEG geboortedatum -> no fire (a leeg bound never matches)",
            PERIODE_EIGEN_ATTR_REGEL, Passagier::new,
            Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::getPrijs),                                         // null
    };
    static final BigDecimal V50 = new BigDecimal(50);
    static final BigDecimal[] PHASE_PERIODE_EXPECTED = {
        V50, V50, null, null, V50, null, V50, null, V50, null, null,   // standalone forms
        V50, null, null,                                               // compound (spec-main-99 shape)
        V50, null, null,                                               // own-attr bound
    };

    // ---- §5.3 / §13.4.17.34 REKENDATUM/REKENJAAR as a VALUE operand: the §4.2 rekendatum global read as an
    //      expression — the spec-antlr-02 / spec-main-51 leeftijd shape ("de tijdsduur van zijn geboortedatum tot
    //      Rekendatum in hele jaren"), the spec-antlr-03 condition shape (vs the volwassenleeftijd parameter), a
    //      direct datum-assign, and Rekenjaar (the rekendatum's year, §5.3). An UNSET rekendatum reads leeg and
    //      the §6/§5 leegwaarde rules apply. Absolute values pinned per fixture. ----
    static final String REKENDATUM_LEEFTIJD_REGEL = """
            Eenheidsysteem Tijd
                de maand            mnd
                het jaar            jr       =    12 mnd
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                de geboortedatum Datum in dagen;
            Regel bepaal leeftijd op rekendatum
                geldig altijd
                    De leeftijd van een Passagier moet berekend worden als de tijdsduur van zijn geboortedatum tot Rekendatum in hele jaren.
            """;
    static final String REKENDATUM_CONDITIE_REGEL = """
            Eenheidsysteem Tijd
                de maand            mnd
                het jaar            jr       =    12 mnd
            Parameter de volwassenleeftijd : Numeriek (geheel getal) met eenheid jr
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de geboortedatum Datum in dagen;
                is minderjarig kenmerk (bijvoeglijk);
            Regel Kenmerktoekenning persoon minderjarig
                geldig altijd
                    Een Passagier is minderjarig
                    indien de tijdsduur van zijn geboortedatum tot Rekendatum in hele jaren kleiner is dan de volwassenleeftijd.
            """;
    static final String REKENJAAR_REGEL = "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op Rekenjaar.\n";
    static final String REKENDATUM_ORDERING_REGEL =
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de geboortedatum Datum in dagen;\n    is minderjarig kenmerk (bijvoeglijk);\n"
            + "Regel R\n    geldig altijd\n        Een Passagier is minderjarig indien zijn geboortedatum eerder is dan Rekendatum.\n";
    static final String REKENJAAR_CONDITIE_REGEL =
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n    is minderjarig kenmerk (bijvoeglijk);\n"
            + "Regel R\n    geldig altijd\n        Een Passagier is minderjarig indien Rekenjaar groter is dan 2020.\n";
    static final Fixture[] PHASE_REKENDATUM = {
        // the spec-antlr-02 / spec-main-51 leeftijd shape: born 2000-03-15, rekendatum 2024-06-01 -> 24 whole years.
        single("tijdsduur tot Rekendatum (leeftijd 24)",
            REKENDATUM_LEEFTIJD_REGEL, () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2000, 3, 15))),
            Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::getLeeftijd),                              // 24
        // a day before the birthday: 23 complete years (ChronoUnit.YEARS.between).
        single("tijdsduur tot Rekendatum, dag voor verjaardag (23)",
            REKENDATUM_LEEFTIJD_REGEL, () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2000, 3, 15))),
            Map.of("rekendatum", LocalDate.of(2024, 3, 14)), Passagier::getLeeftijd),                             // 23
        // an UNSET rekendatum: the tijdsduur operand reads leeg -> leeg result -> no assignment.
        single("tijdsduur tot ONGEZETTE Rekendatum -> leeg",
            REKENDATUM_LEEFTIJD_REGEL, () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2000, 3, 15))),
            Map.of(), Passagier::getLeeftijd),                                                                    // null
        // the spec-antlr-03 condition shape (Daarbij-geldt X inlined): age 14 < 18 -> minderjarig.
        single("conditie: tijdsduur tot Rekendatum kleiner dan volwassenleeftijd -> vuurt",
            REKENDATUM_CONDITIE_REGEL, () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2010, 6, 1))),
            Map.of("volwassenleeftijd", new BigDecimal(18), "rekendatum", LocalDate.of(2024, 6, 2)),
            Passagier::isMinderjarig),                                                                            // true
        single("conditie: leeftijd 30 niet kleiner dan 18 -> vuurt niet",
            REKENDATUM_CONDITIE_REGEL, () -> passagier(p -> p.setGeboortedatum(LocalDate.of(1994, 6, 1))),
            Map.of("volwassenleeftijd", new BigDecimal(18), "rekendatum", LocalDate.of(2024, 6, 2)),
            Passagier::isMinderjarig),                                                                            // false
        // a bare datum-attribute ordering vs Rekendatum ("eerder is dan Rekendatum").
        single("conditie: geboortedatum eerder dan Rekendatum -> vuurt",
            REKENDATUM_ORDERING_REGEL, () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2010, 1, 1))),
            Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::isMinderjarig),                            // true
        single("conditie: geboortedatum NA Rekendatum -> vuurt niet",
            REKENDATUM_ORDERING_REGEL, () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2030, 1, 1))),
            Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::isMinderjarig),                            // false
        // Rekendatum assigned VERBATIM into a datum attribute.
        single("Rekendatum als directe datum-toekenning",
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de geboortedatum Datum in dagen;\n"
            + "Regel R\n    geldig altijd\n        De geboortedatum van een passagier moet gesteld worden op Rekendatum.\n",
            Passagier::new, Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::getGeboortedatum),         // 2024-06-01
        // §5.3 Rekenjaar: the rekendatum's year as a geheel getal; unset -> leeg.
        single("Rekenjaar als waarde (2024)",
            REKENJAAR_REGEL, Passagier::new, Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::getPrijs), // 2024
        single("Rekenjaar met ongezette rekendatum -> leeg",
            REKENJAAR_REGEL, Passagier::new, Map.of(), Passagier::getPrijs),                                       // null
        // Rekenjaar inside arithmetic and as a comparison operand.
        single("Rekenjaar in arithmetiek (Rekenjaar min 2000 = 24)",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op Rekenjaar min 2000.\n",
            Passagier::new, Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::getPrijs),                  // 24
        single("conditie: Rekenjaar groter dan 2020 -> vuurt",
            REKENJAAR_CONDITIE_REGEL, Passagier::new,
            Map.of("rekendatum", LocalDate.of(2024, 6, 1)), Passagier::isMinderjarig),                             // true
        single("conditie: Rekenjaar groter dan 2020 in 2019 -> vuurt niet",
            REKENJAAR_CONDITIE_REGEL, Passagier::new,
            Map.of("rekendatum", LocalDate.of(2019, 6, 1)), Passagier::isMinderjarig),                             // false
    };
    static final Object[] PHASE_REKENDATUM_EXPECTED = {
        new BigDecimal(24), new BigDecimal(23), null,
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE,
        LocalDate.of(2024, 6, 1),
        new BigDecimal(2024), null, new BigDecimal(24), Boolean.TRUE, Boolean.FALSE,
    };

    // ---- UNTESTED-TAIL pins: implemented-but-unasserted behaviours, each pinned to its ABSOLUTE spec value —
    //      §13.4.17.39/.40 eerste/laatste van (the actual earliest/latest DATE, not just operand legality),
    //      §6.1.3 Tabel 3 richting-nul vs weg-van-nul distinct rounding of negative/positive halves,
    //      §8.1.1 Tabel 16 of-gelijk boundary firing (KLEINER_OF_GELIJK / GROTER_OF_GELIJK / LATER_OF_GELIJK
    //      from source, each against its strict counterpart), and the §13.4.14.4 bare-DE quantor — read as
    //      conjunction (=ALLE): the §5.6 semantic list does not spell bare-DE out; AND is the natural reading. ----
    static final String TAIL_DATUM_OT = "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de geboortedatum Datum in dagen;\n    de einddatum Datum in dagen;\n";
    static final String TAIL_PRIJS_OT = "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (getal met 1 decimalen);\n";
    static final String TAIL_KENMERK_OT = "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de prijs Numeriek (geheel getal);\n    is student kenmerk (bijvoeglijk);\n    is duur kenmerk (bijvoeglijk);\n";
    static final String DE_QUANTOR_REGEL = TAIL_KENMERK_OT + "Regel R\n    geldig altijd\n"
            + "        Een Passagier is duur indien er aan de volgende voorwaarden wordt voldaan:\n"
            + "        • hij student is\n"
            + "        • zijn prijs groter is dan 10.\n";
    static final Fixture[] PHASE_TAIL = {
        // §13.4.17.40 laatste van: the LATEST of the listed dates — mixed literal + attribute operands.
        single("laatste van twee datums -> de laatste (15-6-2024)",
            TAIL_DATUM_OT + "Regel R\n    geldig altijd\n        De einddatum van een passagier moet gesteld worden op de laatste van dd. 1-1-2024 en dd. 15-6-2024.\n",
            Passagier::new, Map.of(), Passagier::getEinddatum),                                                   // 2024-06-15
        single("laatste van attribuut en literal -> het attribuut wint (1-1-2030)",
            TAIL_DATUM_OT + "Regel R\n    geldig altijd\n        De einddatum van een passagier moet gesteld worden op de laatste van zijn geboortedatum en dd. 15-6-2024.\n",
            () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2030, 1, 1))), Map.of(), Passagier::getEinddatum), // 2030-01-01
        single("eerste van twee datums -> de eerste (1-1-2024)",
            TAIL_DATUM_OT + "Regel R\n    geldig altijd\n        De einddatum van een passagier moet gesteld worden op de eerste van dd. 1-1-2024 en dd. 15-6-2024.\n",
            Passagier::new, Map.of(), Passagier::getEinddatum),                                                   // 2024-01-01
        // §6.1.3 Tabel 3: RICHTING_NUL truncates toward zero; WEG_VAN_NUL rounds away — distinct on ±x,5.
        single("afronding richting nul: -2,5 -> -2",
            TAIL_PRIJS_OT + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op zijn basistarief richting nul afgerond op 0 decimalen.\n",
            () -> passagier(p -> p.setBasistarief(new BigDecimal("-2.5"))), Map.of(), Passagier::getPrijs),       // -2
        single("afronding weg van nul: -2,5 -> -3",
            TAIL_PRIJS_OT + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op zijn basistarief weg van nul afgerond op 0 decimalen.\n",
            () -> passagier(p -> p.setBasistarief(new BigDecimal("-2.5"))), Map.of(), Passagier::getPrijs),       // -3
        single("afronding richting nul: 2,5 -> 2",
            TAIL_PRIJS_OT + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op zijn basistarief richting nul afgerond op 0 decimalen.\n",
            () -> passagier(p -> p.setBasistarief(new BigDecimal("2.5"))), Map.of(), Passagier::getPrijs),        // 2
        single("afronding weg van nul: 2,5 -> 3",
            TAIL_PRIJS_OT + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op zijn basistarief weg van nul afgerond op 0 decimalen.\n",
            () -> passagier(p -> p.setBasistarief(new BigDecimal("2.5"))), Map.of(), Passagier::getPrijs),        // 3
        // §8.1.1 Tabel 16 of-gelijk boundary firing, each against its strict counterpart on the SAME value.
        single("groter of gelijk aan: op de grens -> vuurt",
            TAIL_KENMERK_OT + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn prijs groter of gelijk is aan 100.\n",
            () -> passagier(p -> p.setPrijs(new BigDecimal(100))), Map.of(), Passagier::isDuur),                  // true
        single("groter dan (strikt): op de grens -> vuurt niet",
            TAIL_KENMERK_OT + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn prijs groter is dan 100.\n",
            () -> passagier(p -> p.setPrijs(new BigDecimal(100))), Map.of(), Passagier::isDuur),                  // false
        single("kleiner of gelijk aan: op de grens -> vuurt",
            TAIL_KENMERK_OT + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn prijs kleiner of gelijk is aan 100.\n",
            () -> passagier(p -> p.setPrijs(new BigDecimal(100))), Map.of(), Passagier::isDuur),                  // true
        // §8.1.1 Tabel 16 LATER_OF_GELIJK from source: on the boundary fires, the day before does not.
        single("later of gelijk aan: op de grensdag -> vuurt",
            TAIL_DATUM_OT.replace("de einddatum Datum in dagen;\n", "de einddatum Datum in dagen;\n    is duur kenmerk (bijvoeglijk);\n")
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn geboortedatum later of gelijk is aan 1-1-2000.\n",
            () -> passagier(p -> p.setGeboortedatum(LocalDate.of(2000, 1, 1))), Map.of(), Passagier::isDuur),     // true
        single("later of gelijk aan: dag ervoor -> vuurt niet",
            TAIL_DATUM_OT.replace("de einddatum Datum in dagen;\n", "de einddatum Datum in dagen;\n    is duur kenmerk (bijvoeglijk);\n")
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn geboortedatum later of gelijk is aan 1-1-2000.\n",
            () -> passagier(p -> p.setGeboortedatum(LocalDate.of(1999, 12, 31))), Map.of(), Passagier::isDuur),   // false
        // §13.4.14.4 the bare-DE quantor ("er aan de volgende voorwaarden wordt voldaan") = conjunction.
        single("DE-quantor: beide bullets waar -> vuurt",
            DE_QUANTOR_REGEL, () -> passagier(p -> { p.setStudent(true); p.setPrijs(new BigDecimal(20)); }),
            Map.of(), Passagier::isDuur),                                                                          // true
        single("DE-quantor: één bullet onwaar -> vuurt niet",
            DE_QUANTOR_REGEL, () -> passagier(p -> { p.setStudent(false); p.setPrijs(new BigDecimal(20)); }),
            Map.of(), Passagier::isDuur),                                                                          // false
    };
    static final Object[] PHASE_TAIL_EXPECTED = {
        LocalDate.of(2024, 6, 15), LocalDate.of(2030, 1, 1), LocalDate.of(2024, 1, 1),
        new BigDecimal(-2), new BigDecimal(-3), new BigDecimal(2), new BigDecimal(3),
        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE,
        Boolean.TRUE, Boolean.FALSE,
        Boolean.TRUE, Boolean.FALSE,
    };

    // ---- Phase H value-path ops: §13.4.17.14 tot de macht, §6.8 percentage (% van), §6.5 gedeeld door (ABS),
    //      §6.1.4 begrenzing — ported runtime helpers rsMacht/rsMachtReeel/rsGedeeldAbs/rsBegrens. ----
    static final Fixture[] PHASE_H_VAL = {
        new Fixture("tot de macht (basistarief 3 ^ 2 = 9)",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op zijn basistarief tot de macht 2 rekenkundig afgerond op 0 decimalen.\n",
            () -> List.of(passagier(p -> p.setBasistarief(new BigDecimal(3)))),
            Map.of(), l -> ((Passagier) l.get(0)).getPrijs()),   // 9

        new Fixture("percentage (basistarief 50 % van bodem 200 = 100)",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n    de bodem Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op zijn basistarief % van zijn bodem.\n",
            () -> List.of(passagier(p -> { p.setBasistarief(new BigDecimal(50)); p.setBodem(new BigDecimal(200)); })),
            Map.of(), l -> ((Passagier) l.get(0)).getPrijs()),   // 100

        new Fixture("gedeeld door (ABS) (10 / 3 = 3.33333, 5 dec toward zero)",
            "Objecttype de Passagier\n    de prijs Numeriek (getal met 5 decimalen);\n    het basistarief Numeriek (geheel getal);\n    de bodem Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op zijn basistarief gedeeld door (ABS) zijn bodem.\n",
            () -> List.of(passagier(p -> { p.setBasistarief(new BigDecimal(10)); p.setBodem(new BigDecimal(3)); })),
            Map.of(), l -> ((Passagier) l.get(0)).getPrijs()),   // 3.33333

        new Fixture("begrenzing met een maximum van 100 (150 -> 100, 50 -> 50)",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op zijn basistarief, met een maximum van 100.\n",
            () -> List.of(passagier(p -> p.setBasistarief(new BigDecimal(150))), passagier(p -> p.setBasistarief(new BigDecimal(50)))),
            Map.of(), l -> vals(((Passagier) l.get(0)).getPrijs(), ((Passagier) l.get(1)).getPrijs())),   // 100, 50

        // a min+max begrenzing: clamp to [10, 100]. 5 -> 10, 50 -> 50, 150 -> 100.
        new Fixture("begrenzing met minimum 10 en maximum 100",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op zijn basistarief, met een minimum van 10 en een maximum van 100.\n",
            () -> List.of(passagier(p -> p.setBasistarief(new BigDecimal(5))), passagier(p -> p.setBasistarief(new BigDecimal(50))),
                          passagier(p -> p.setBasistarief(new BigDecimal(150)))),
            Map.of(), l -> vals(((Passagier) l.get(0)).getPrijs(), ((Passagier) l.get(1)).getPrijs(), ((Passagier) l.get(2)).getPrijs())),   // 10, 50, 100

        // §5.8.3 listed MINIMUM over two operands "de minimale waarde van A en B": min(10,20)=10; a leeg operand is
        // SKIPPED (null,20 -> 20); all-leeg -> leeg.
        new Fixture("minimale waarde van twee operanden (min-skip leeg)",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n    het plafond Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        Het plafond van een passagier moet gesteld worden op de minimale waarde van zijn prijs en zijn basistarief.\n",
            () -> List.of(passagier(p -> { p.setPrijs(new BigDecimal(10)); p.setBasistarief(new BigDecimal(20)); }),
                          passagier(p -> p.setBasistarief(new BigDecimal(20))),   // prijs leeg -> skip -> 20
                          new Passagier()),                                        // both leeg -> leeg
            Map.of(), l -> vals(((Passagier) l.get(0)).getPlafond(), ((Passagier) l.get(1)).getPlafond(),
                                ((Passagier) l.get(2)).getPlafond())),   // 10, 20, null

        // §4.14 listed EERSTE over two dates "de eerste van A en B": the earliest. 2020-03-01 < 2020-06-01.
        new Fixture("eerste van twee data (earliest)",
            "Objecttype de Vlucht\n    de verwacht vertrek Datum in dagen;\n    de daadwerkelijk vertrek Datum in dagen;\n    de vroegste vertrek Datum in dagen;\n"
            + "Regel R\n    geldig altijd\n        De vroegste vertrek van een Vlucht moet gesteld worden op de eerste van zijn verwacht vertrek en zijn daadwerkelijk vertrek.\n",
            () -> { Vlucht v = new Vlucht(); v.setVerwachtVertrek(dat(2020, 6, 1)); v.setDaadwerkelijkVertrek(dat(2020, 3, 1)); return List.of(v); },
            Map.of(), l -> String.valueOf(((Vlucht) l.get(0)).getVroegsteVertrek())),   // 2020-03-01

        // §5.4/§13.4.17.9 tekstreeks value: a literal segment + an inserted «expressie» (rsTekst-coerced, leeg -> "").
        // leeftijd 30 -> "leeftijd: 30"; a leeg leeftijd -> "leeftijd: ".
        new Fixture("tekstreeks value (literal + inserted leeftijd)",
            "Objecttype de Persoon (mv: Personen) (bezield)\n    de roepnaam Tekst;\n    de leeftijd Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De roepnaam van een Persoon moet gesteld worden op \"leeftijd: «zijn leeftijd»\".\n",
            () -> { Persoon p = new Persoon(); p.setLeeftijd(new BigDecimal(30)); return List.of(p, new Persoon()); },
            Map.of(), l -> vals(((Persoon) l.get(0)).getRoepnaam(), ((Persoon) l.get(1)).getRoepnaam())),   // "leeftijd: 30", "leeftijd: "
    };

    // ---- Phase H builtin scalar Functies: §13.4.17.17 abs, §13.4.17.18-.20 date components, §13.4.17.37 paasdag
    //      (ported rsAbs/rsDatumDeel/rsEerstePaasdag). ----
    static final Fixture[] PHASE_H_FUNC = {
        new Fixture("absolute waarde (|-5| = 5, |leeg| = leeg)",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    het basistarief Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op de absolute waarde van (zijn basistarief).\n",
            () -> List.of(passagier(p -> p.setBasistarief(new BigDecimal(-5))), new Passagier()),
            Map.of(), l -> vals(((Passagier) l.get(0)).getPrijs(), ((Passagier) l.get(1)).getPrijs())),   // 5, null

        new Fixture("maand uit (geboortedatum 2020-06-15 -> 6)",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    de geboortedatum Datum in dagen;\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op de maand uit (zijn geboortedatum).\n",
            () -> List.of(passagier(p -> p.setGeboortedatum(dat(2020, 6, 15))), new Passagier()),
            Map.of(), l -> vals(((Passagier) l.get(0)).getPrijs(), ((Passagier) l.get(1)).getPrijs())),   // 6, null (leeg datum -> leeg)

        new Fixture("eerste paasdag van 2024 = 2024-03-31",
            "Objecttype de Passagier\n    de geboortedatum Datum in dagen;\n"
            + "Regel R\n    geldig altijd\n        De geboortedatum van een passagier moet gesteld worden op de eerste paasdag van 2024.\n",
            () -> List.of(new Passagier()),
            Map.of(), l -> String.valueOf(((Passagier) l.get(0)).getGeboortedatum())),   // 2024-03-31

        // §8.1.1 a builtin-Functie LHS COMPARISON in a condition: "de maand uit (zijn geboortedatum) gelijk is aan 6"
        // (the toka-26/spec-main-78 shape). June -> fires (50); July -> no fire; a leeg datum -> rsDatumDeel leeg ->
        // GELIJK-vs-6 is false (vergelijk null) -> no fire.
        new Fixture("maand uit comparison in a condition (month == 6 fires)",
            "Objecttype de Passagier\n    de prijs Numeriek (geheel getal);\n    de geboortedatum Datum in dagen;\n"
            + "Regel R\n    geldig altijd\n        De prijs van een passagier moet gesteld worden op 50 indien de maand uit (zijn geboortedatum) gelijk is aan 6.\n",
            () -> List.of(passagier(p -> p.setGeboortedatum(dat(2020, 6, 15))),
                          passagier(p -> p.setGeboortedatum(dat(2020, 7, 15))),
                          new Passagier()),
            Map.of(), l -> vals(((Passagier) l.get(0)).getPrijs(), ((Passagier) l.get(1)).getPrijs(),
                                ((Passagier) l.get(2)).getPrijs())),   // 50, null, null
    };

    private static String bdStr(BigDecimal x) { return x == null ? "null" : x.stripTrailingZeros().toPlainString(); }
    static final ObjFixture[] PHASE_G_OBJ = {
        // O1 §13.4.6: a Bestelling creates a linked Factuur (bedrag = its waarde) and back-references it.
        new ObjFixture("§13.4.6 objectcreatie voegt een gekoppelde factuur toe (bedrag = waarde)",
            """
            Objecttype de Bestelling
                de waarde Numeriek (geheel getal);
            Objecttype de Factuur
                de bedrag Numeriek (geheel getal);
            Feittype Bestelling met factuur
                de bestelling Bestelling
                de factuur Factuur
            Eén bestelling heeft één factuur
            Regel maak factuur
                geldig altijd
                    Een Bestelling heeft een factuur met bedrag gelijk aan de waarde van een Bestelling.
            """,
            () -> { Bestelling b = new Bestelling(); b.setWaarde(new BigDecimal(50)); return List.<Object>of(b); },
            Map.of(),
            (o, facts) -> { Factuur f = (Factuur) o; return "Factuur|bedrag=" + bdStr(f.getBedrag()) + "|best=" + refIndex(facts, f.getBestelling()); }),
            // -> ["Factuur|bedrag=50|best=0"]

        // O3 §13.4.6.4: the DISTINCT kenmerkwaardetoekenning — "duur gelijk aan waar" sets the created object's
        //     KENMERK (a boolean property), next to an ordinary attribuut init. Absolute pin: waar -> true.
        new ObjFixture("§13.4.6.4 kenmerkwaardetoekenning 'gelijk aan waar' zet het kenmerk op de nieuwe instantie",
            """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                is duur kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel maak passagier
                geldig altijd
                    Een Vlucht heeft een passagier met prijs gelijk aan 10, duur gelijk aan waar.
            """,
            () -> List.of(new Vlucht()),
            Map.of(),
            (o, facts) -> { Passagier p = (Passagier) o; return "Passagier|prijs=" + bdStr(p.getPrijs())
                    + "|duur=" + p.isDuur() + "|reis=" + refIndex(facts, p.getReis()); }),
            // -> ["Passagier|prijs=10|duur=true|reis=0"]

        // O4 §13.4.6.4: "gelijk aan onwaar" — the explicit false form.
        new ObjFixture("§13.4.6.4 kenmerkwaardetoekenning 'gelijk aan onwaar' zet het kenmerk expliciet op onwaar",
            """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                is duur kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel maak passagier
                geldig altijd
                    Een Vlucht heeft een passagier met prijs gelijk aan 10, duur gelijk aan onwaar.
            """,
            () -> List.of(new Vlucht()),
            Map.of(),
            (o, facts) -> { Passagier p = (Passagier) o; return "Passagier|prijs=" + bdStr(p.getPrijs())
                    + "|duur=" + p.isDuur() + "|reis=" + refIndex(facts, p.getReis()); }),
            // -> ["Passagier|prijs=10|duur=false|reis=0"]

        // O2 §13.4.6 + §8.1.7: a Passagier (prijs 10) is created only for a Vlucht that HAS crew (a far-side exists guard).
        new ObjFixture("§13.4.6 objectcreatie met far-side voorwaarde maakt alleen voor bemande vluchten",
            """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Objecttype het Bemanningslid
                de naam Tekst;
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype Vlucht met bemanning
                de bemande vlucht Vlucht
                het bemanningslid Bemanningslid
            Eén bemande vlucht heeft meerdere bemanningsleden
            Regel maak passagier
                geldig altijd
                    Een Vlucht heeft een passagier met prijs gelijk aan 10 indien de vlucht een bemanningslid heeft.
            """,
            () -> {
                Vlucht bemand = new Vlucht(), onbemand = new Vlucht();
                Bemanningslid crew = new Bemanningslid(); crew.setBemandeVlucht(bemand);
                return List.of(bemand, onbemand, crew);
            },
            Map.of(),
            (o, facts) -> { Passagier p = (Passagier) o; return "Passagier|prijs=" + bdStr(p.getPrijs()) + "|reis=" + refIndex(facts, p.getReis()); }),
            // -> ["Passagier|prijs=10|reis=0"]
    };

    // ---- §13.4.6 + §5.8.2 AGGREGATION-VALUED objectcreatie toekenning (toka-28 in miniature). The created object's
    //      attribute value is an aggregation over the subject's related elements ("het aantal passagiers van de Vlucht
    //      maal <param>") — a COUNT, not an attribute. The scalarWaarde path cannot host an accumulate CE, so the exec
    //      used to SKIP the whole objectcreatie (DRL lowered it). Now the aggregation-toekenning lowers via lowerVal to
    //      its own accumulate CE threaded through the consequence, exactly like the regel aggregatie-in-arithmetiek
    //      path. Absolute pin: 3 passengers * 5 = 15 on the created Contingent; an EMPTY Vlucht counts 0 -> 0*5 = 0
    //      (§5.8.2 count of empty is 0, NOT the leegwaarde — verified against DRL). The Contingent target is TO-MANY
    //      (contingentrelatie: één contingentvlucht heeft meerdere contingenten), so a CreationMarker idempotence guard
    //      caps it at one per (rule, Vlucht) — and creating a Contingent does NOT feed back into the passenger count. ----
    static final ObjFixture[] PHASE_OBJ_AGG = {
        new ObjFixture("§13.4.6+§5.8.2 aggregatie-waarde objectcreatie: aantal treinmiles = het aantal passagiers maal param",
            """
            Parameter de treinmiles per passagier : Numeriek (geheel getal)
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Objecttype de Contingent
                de aantal treinmiles Numeriek (positief geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype contingentrelatie
                de contingentvlucht Vlucht
                de contingent Contingent
            Eén contingentvlucht heeft meerdere contingenten
            Regel maak contingent
                geldig altijd
                    Een Vlucht heeft een contingent met aantal treinmiles gelijk aan het aantal passagiers van de Vlucht maal de treinmiles per passagier.
            """,
            () -> {
                Vlucht vol = new Vlucht(), leeg = new Vlucht();
                Passagier p1 = new Passagier(), p2 = new Passagier(), p3 = new Passagier();
                p1.setReis(vol); p2.setReis(vol); p3.setReis(vol);
                return List.<Object>of(vol, leeg, p1, p2, p3);   // vol has 3 passengers, leeg has 0
            },
            Map.of("treinmilesPerPassagier", new BigDecimal(5)),
            (o, facts) -> { Contingent c = (Contingent) o; return "Contingent|treinmiles=" + bdStr(c.getAantalTreinmiles())
                    + "|vlucht=" + refIndex(facts, c.getContingentvlucht()); }),
            // -> ["Contingent|treinmiles=0|vlucht=1", "Contingent|treinmiles=15|vlucht=0"]  (sorted)
    };
    // 3 passengers * 5 = 15 (vlucht 0); 0 passengers * 5 = 0 (vlucht 1, empty-count is 0 not leeg).
    static final List<String> PHASE_OBJ_AGG_EXPECTED =
            List.of("Contingent|treinmiles=0|vlucht=1", "Contingent|treinmiles=15|vlucht=0");

    /** §13.4.6 + §5.8.2 an AGGREGATION-valued objectcreatie toekenning lowers in BOTH back-ends (was an exec skip):
     *  the created object's attribute = "het aantal passagiers van de Vlucht maal <param>", a count over the subject's
     *  related elements. ABSOLUTE created-set pin (3*5=15, empty-count 0*5=0) AND DRL==exec parity. Mirrors
     *  {@link #phase_keten_objectcreatie_init_is_spec_correct} / {@link #phase_G_objectcreatie_idempotentie_is_spec_correct}. */
    @Test void phase_OBJ_AGG_aggregatie_waarde_objectcreatie_is_spec_correct() {
        for (ObjFixture f : PHASE_OBJ_AGG) {
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            List<String> viaDrl = fireCreated(drlBase(m), f);
            List<String> viaModel = fireCreated(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_OBJ_AGG_EXPECTED, viaDrl, () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(viaDrl, viaModel, () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ---- §13.4.6/§9 objectcreatie IDEMPOTENCE: the creation is a function of the fact base, not of the fire count.
    //      no-loop only blocks a rule re-activating on its OWN insert; when ANOTHER rule modifies a matched subject
    //      the unguarded creation re-fires and mints duplicates. PRE-FIX these fixtures RAN AWAY (fireAllRules never
    //      terminated: R2's modify re-woke R1, which created another instance, which re-woke R2, …). The guard makes
    //      the creation fire at most once per subject — asserted here as EXACTLY ONE created object in both back-ends.
    static final ObjFixture[] PHASE_IDEM = {
        // IDEM1 RE-FIRE (to-many target, CreationMarker guard): R1 creates a Passagier (prijs 10) for every Vlucht
        //   whose totaalprijs > 0; R2 sets totaalprijs = #passagiers + 1. R1's pattern reads totaalprijs, so R2's
        //   modify re-activates it — pre-fix an unbounded create-count-modify cascade (verified RED: it hit the
        //   fire cap at 100 Passagiers in both back-ends). The marker (regel+subject) caps R1 at one creation.
        new ObjFixture("§9 objectcreatie re-fire: R2 modifieert het subject -> R1 vuurt niet opnieuw (precies 1)",
            """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel maak passagier
                geldig altijd
                    Een Vlucht heeft een passagier met prijs gelijk aan 10 indien de totaalprijs van een Vlucht groter is dan 0.
            Regel zet totaalprijs
                geldig altijd
                    De totaalprijs van een Vlucht moet berekend worden als het aantal passagiers van een Vlucht plus 1.
            """,
            () -> { Vlucht v = new Vlucht(); v.setTotaalprijs(new BigDecimal(1)); return List.of(v); },
            Map.of(),
            (o, facts) -> { Passagier p = (Passagier) o; return "Passagier|prijs=" + bdStr(p.getPrijs()) + "|reis=" + refIndex(facts, p.getReis()); }),
            // -> ["Passagier|prijs=10|reis=0"]  (exactly one, whoever re-modifies the Vlucht)

        // IDEM2 1:1 SUPPRESSION (instance guard): a Bestelling:Factuur is 1:1 (PHASE_G_OBJ O1). With a Factuur
        //   ALREADY linked to the Bestelling (seeded), the guard not(Factuur(bestelling==$subject)) suppresses the
        //   creation — the creation is a function of the fact base, so no second Factuur is minted. Empty created set.
        new ObjFixture("§9 objectcreatie 1:1 met reeds-gekoppelde Factuur -> maakt niets (functie van de feitenbasis)",
            """
            Objecttype de Bestelling
                de waarde Numeriek (geheel getal);
            Objecttype de Factuur
                de bedrag Numeriek (geheel getal);
            Feittype Bestelling met factuur
                de bestelling Bestelling
                de factuur Factuur
            Eén bestelling heeft één factuur
            Regel maak factuur
                geldig altijd
                    Een Bestelling heeft een factuur met bedrag gelijk aan de waarde van een Bestelling.
            """,
            () -> {
                Bestelling b = new Bestelling(); b.setWaarde(new BigDecimal(50));
                Factuur bestaand = new Factuur(); bestaand.setBedrag(new BigDecimal(999)); bestaand.setBestelling(b);
                return List.<Object>of(b, bestaand);
            },
            Map.of(),
            (o, facts) -> { Factuur f = (Factuur) o; return "Factuur|bedrag=" + bdStr(f.getBedrag()) + "|best=" + refIndex(facts, f.getBestelling()); }),
            // -> []  (the seeded Factuur is not "created"; no new one is minted)

        // IDEM3 TO-MANY with a PRE-EXISTING related instance: a Vlucht already has one Passagier (seeded). The
        //   CreationMarker keys on (rule, subject), NOT on pre-existing instances, so the creation STILL happens
        //   exactly once — the pre-existing passenger does not suppress it, and no duplicate is minted either.
        new ObjFixture("§9 objectcreatie to-many met reeds-bestaande passagier -> maakt nog precies één",
            """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel maak passagier
                geldig altijd
                    Een Vlucht heeft een passagier met prijs gelijk aan 10.
            """,
            () -> {
                Vlucht v = new Vlucht();
                Passagier bestaand = new Passagier(); bestaand.setPrijs(new BigDecimal(7)); bestaand.setReis(v);
                return List.<Object>of(v, bestaand);   // the seeded passenger is not "created"; only the new one is observed
            },
            Map.of(),
            (o, facts) -> { Passagier p = (Passagier) o; return "Passagier|prijs=" + bdStr(p.getPrijs()) + "|reis=" + refIndex(facts, p.getReis()); }),
            // -> ["Passagier|prijs=10|reis=0"]  (the new one; the seeded prijs=7 passenger is a seed, not observed)
    };

    /** §13.4.6/§9 the objectcreatie idempotence guard: the creation is a function of the fact base, not of the fire
     *  count. ABSOLUTE created-sets in BOTH back-ends (like {@link #phase_keten_objectcreatie_init_is_spec_correct}):
     *  a re-firing subject creates exactly one instance (not a runaway), a 1:1 pre-linked target creates nothing, and
     *  a to-many pre-existing instance does not suppress the (rule,subject)-keyed creation. Runs uncapped
     *  fireAllRules — pre-fix IDEM1 did not terminate. */
    @Test void phase_G_objectcreatie_idempotentie_is_spec_correct() {
        List<List<String>> verwacht = List.of(
                List.of("Passagier|prijs=10|reis=0"),   // IDEM1: exactly one, despite R2 re-modifying the Vlucht
                List.of(),                              // IDEM2: 1:1 pre-linked -> nothing created
                List.of("Passagier|prijs=10|reis=0"));  // IDEM3: to-many pre-existing -> still exactly one new
        for (int i = 0; i < PHASE_IDEM.length; i++) {
            ObjFixture f = PHASE_IDEM[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            List<String> viaDrl = fireCreated(drlBase(m), f);
            List<String> viaModel = fireCreated(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            final int idx = i;
            assertEquals(verwacht.get(idx), viaDrl, () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(viaDrl, viaModel, () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ==== §9.10 Regelgroep (DOCUMENTED EXTENSION — see docs/grammar-divergences.md) ====================
    // FASE 1 (namespacing): the SAME two rules, grouped vs ungrouped, over identical facts -> IDENTICAL
    //   results in BOTH back-ends, and the DRL text differs ONLY by the rule-name prefix "<groep>: ".
    // FASE 2 (`is recursief`): an objectcreatie that advances a chain (leeftijd N -> a linked successor
    //   leeftijd N+1, bounded by a voorwaarde) terminates at the exact instance count at fixpoint. This
    //   works PRECISELY because of the 10:22 idempotence guard: each subject creates exactly one successor
    //   (1:1 back-reference guard `not Persoon(partner==$subject)`), so forward-chaining converges within
    //   one fireAllRules instead of minting duplicates — no fire-cap hit.

    // The two rules: a kenmerktoekenning (minderjarig) + a gelijkstelling (prijs), each over a Passagier.
    private static final String RG_FASE1_REGELS = """
            Regel zet minderjarig
                geldig altijd
                    Een Passagier is minderjarig indien zijn leeftijd kleiner is dan 18.
            Regel zet prijs
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op zijn basistarief plus 5.
            """;
    private static final String RG_FASE1_OT = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            """;
    private static final String RG_FASE1_UNGROUPED = RG_FASE1_OT + RG_FASE1_REGELS;
    // A non-recursief group scoping the two rules (fase-1 pure namespacing — no `is recursief`).
    private static final String RG_FASE1_GROUPED = RG_FASE1_OT
            + "Regelgroep passagierregels\n" + RG_FASE1_REGELS;

    /** §9.10 fase-1: grouped vs ungrouped rules produce IDENTICAL firing results in BOTH back-ends over the
     *  same facts (leeftijd 16, basistarief 42 -> minderjarig=true, prijs=47), and the DRL differs ONLY by the
     *  "<groep>: " rule-name prefix (asserted by normalizing the prefix away and diffing the two DRL texts). */
    @Test void regelgroep_fase1_namespacing_is_inert() {
        RegelSpraakModel ung = Resolver.resolve(RG_FASE1_UNGROUPED);
        RegelSpraakModel grp = Resolver.resolve(RG_FASE1_GROUPED);
        assertTrue(ung.errors().isEmpty() && grp.errors().isEmpty(),
                () -> "resolver-fout ung=" + ung.errors() + " grp=" + grp.errors());
        // the group IR is recorded, member names bare, not recursief
        assertEquals(1, grp.regelGroepen.size());
        assertEquals("passagierregels", grp.regelGroepen.get(0).naam());
        assertFalse(grp.regelGroepen.get(0).recursief());
        assertEquals(List.of("zet minderjarig", "zet prijs"), grp.regelGroepen.get(0).leden());
        assertTrue(ung.regelGroepen.isEmpty());

        // DRL differs ONLY by the "<groep>: " header prefix: strip it and the two texts are identical.
        String drlUng = DrlTranspiler.transpile(ung);
        String drlGrp = DrlTranspiler.transpile(grp);
        assertNotEquals(drlUng, drlGrp, "grouped DRL must carry the prefix (so it is not identical raw)");
        assertEquals(drlUng, drlGrp.replace("passagierregels: ", ""),
                "grouped DRL must differ from ungrouped ONLY by the '<groep>: ' rule-name prefix");
        // ...and each data key stays BARE (no prefix leaked into a CreationMarker/RuleFired/Inconsistentie key).
        assertTrue(drlGrp.contains("rule \"passagierregels: zet prijs\""), "header must be prefixed");

        // Firing results are IDENTICAL in BOTH back-ends. Same facts, read both fields back.
        Supplier<List<Object>> facts = () -> { Passagier p = passagier(x -> {
            x.setLeeftijd(new BigDecimal(16)); x.setBasistarief(new BigDecimal(42)); }); return List.of(p); };
        for (RegelSpraakModel m : List.of(ung, grp)) {
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> "overgeslagen: " + em.overgeslagen());
            for (KieBase kb : List.of(drlBase(m), KieBaseBuilder.createKieBaseFromModel(em.model()))) {
                KieSession s = sessie(kb);
                List<Object> fs = facts.get();
                fs.forEach(s::insert);
                s.fireAllRules();
                Passagier p = (Passagier) fs.get(0);
                assertTrue(p.isMinderjarig(), "minderjarig moet true zijn");
                assertEquals(new BigDecimal(47), p.getPrijs(), "prijs moet 47 zijn");
                s.dispose();
            }
        }
    }

    // FASE 2 chain: a Persoon (leeftijd N) creates a linked successor (leeftijd N+1) via the wederkerig
    // Partnerrelatie (a 1:1 self-relation, so the back-reference role "partner" resolves uniquely and the
    // idempotence guard is `not Persoon(partner==$subject)`), bounded by `zijn leeftijd kleiner is dan 5`.
    // Seed one Persoon (leeftijd 1) -> the chain mints leeftijd 2,3,4,5 and STOPS (leeftijd 5 fails the
    // voorwaarde). EXACT expected: 5 Persoon instances total (1 seed + 4 created), no fire-cap diagnostic.
    private static final String RG_FASE2_CHAIN = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal);
            Wederkerig feittype Partnerrelatie
                de partner Persoon
            Eén partner heeft één partner
            Regelgroep partnerketen is recursief
            Regel maak partner
                geldig altijd
                    Een Persoon heeft een partner met leeftijd gelijk aan de leeftijd van een Persoon plus 1 indien de leeftijd van een Persoon kleiner is dan 5.
            """;

    /** §9.10 fase-2: a recursief group whose objectcreatie advances a chain terminates at the EXACT instance
     *  count at fixpoint, both back-ends at parity, no fire-cap hit. Sound BECAUSE of the 10:22 idempotence
     *  guard (each Persoon creates exactly one successor via `not Persoon(partner==$subject)`), so the
     *  forward-chaining fixpoint is reached in one fireAllRules — a pre-guard unconditional insert would have
     *  minted duplicates and never converged. Seed leeftijd 1 -> {1,2,3,4,5} = 5 Persoon instances. */
    @Test void regelgroep_fase2_recursief_chain_reaches_fixpoint() {
        RegelSpraakModel m = Resolver.resolve(RG_FASE2_CHAIN);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        assertEquals(1, m.regelGroepen.size());
        assertTrue(m.regelGroepen.get(0).recursief(), "group must be recursief");
        // the recursief group HAS an objectcreatie member with a voorwaarde -> no ERROR, no unbounded-WARNING.
        assertFalse(m.diagnostics.stream().anyMatch(d ->
                d.message().contains("zonder objectcreatieregel") || d.message().contains("heeft geen voorwaarde")),
                () -> "unexpected recursief diagnostic: " + m.diagnostics);
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "overgeslagen: " + em.overgeslagen());

        for (KieBase kb : List.of(drlBase(m), KieBaseBuilder.createKieBaseFromModel(em.model()))) {
            KieSession s = sessie(kb);
            Persoon seed = new Persoon(); seed.setLeeftijd(new BigDecimal(1));
            s.insert(seed);
            int fired = s.fireAllRules();   // returns the activation count — never near the fire cap
            List<BigDecimal> leeftijden = s.getObjects().stream()
                    .filter(o -> o instanceof Persoon)
                    .map(o -> ((Persoon) o).getLeeftijd())
                    .sorted().toList();
            assertEquals(List.of(new BigDecimal(1), new BigDecimal(2), new BigDecimal(3),
                    new BigDecimal(4), new BigDecimal(5)), leeftijden,
                    "chain must terminate at leeftijd 1..5 (5 instances)");
            assertTrue(fired < 100, () -> "must converge well below the fire cap, fired=" + fired);
            s.dispose();
        }
    }

    // ---- Phase G dimensie fixtures: §5.8.5 dimensie-fold (rsDimSom) + §3.6 DimensieCel read (Map.get) ----
    private static NatuurlijkPersoon np(Map<String, BigDecimal> m) {
        NatuurlijkPersoon n = new NatuurlijkPersoon(); n.setBetaaldeBelastingInJaar(m); return n;
    }
    private static final String DIM7_HDR = """
            Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)
                de betaalde belasting over de afgelopen vier jaar Numeriek (geheel getal);
                de betaalde belasting in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. zes jaar geleden
                2. vijf jaar geleden
                3. vier jaar geleden
                4. drie jaar geleden
                5. twee jaar geleden
                6. een jaar geleden
                7. heden
            """;
    private static final String DIM_SOM_REGEL = """
            Regel Totaal betaalde belasting over 4 jaar
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet berekend worden als de som van zijn betaalde belasting in jaar vanaf vier jaar geleden t/m een jaar geleden.
            """;
    private static final Map<String, BigDecimal> DIM7_WAARDEN = Map.of(
            "zes jaar geleden", new BigDecimal(11), "vijf jaar geleden", new BigDecimal(22),
            "vier jaar geleden", new BigDecimal(100), "drie jaar geleden", new BigDecimal(200),
            "twee jaar geleden", new BigDecimal(300), "een jaar geleden", new BigDecimal(400),
            "heden", new BigDecimal(9999));
    static final Fixture[] PHASE_G_DIM = {
        // D1 §5.8.5 interval fold: sum only the labels in [vier jaar geleden … een jaar geleden] = 100+200+300+400 = 1000.
        new Fixture("§5.8.5 dimensie-som vanaf/t-m telt alleen het interval (1000)",
            DIM7_HDR + DIM_SOM_REGEL,
            () -> List.<Object>of(np(Map.of("zes jaar geleden", new BigDecimal(11), "vijf jaar geleden", new BigDecimal(22),
                    "vier jaar geleden", new BigDecimal(100), "drie jaar geleden", new BigDecimal(200),
                    "twee jaar geleden", new BigDecimal(300), "een jaar geleden", new BigDecimal(400), "heden", new BigDecimal(9999)))),
            Map.of(),
            l -> ((NatuurlijkPersoon) l.get(0)).getBetaaldeBelastingOverDeAfgelopenVierJaar()),   // 1000

        // D2 §5.8.2 null-skip: labels with no value are SKIPPED (not 0) — only drie/een present = 200+400 = 600.
        new Fixture("§5.8.2 dimensie-som slaat lege labels over (600)",
            DIM7_HDR + DIM_SOM_REGEL,
            () -> List.<Object>of(np(Map.of("drie jaar geleden", new BigDecimal(200), "een jaar geleden", new BigDecimal(400)))),
            Map.of(),
            l -> ((NatuurlijkPersoon) l.get(0)).getBetaaldeBelastingOverDeAfgelopenVierJaar()),   // 600

        // D4 §13.4.17.52 "over alle <meervoud>": every declared label folds — 11+22+100+200+300+400+9999 = 11032.
        new Fixture("§13.4.17.52 som over alle jaardimensies telt elk label (11032)",
            DIM7_HDR + """
            Regel Totaal betaalde belasting over 4 jaar
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet berekend worden als de som van zijn betaalde belasting in jaar over alle jaardimensies.
            """,
            () -> List.<Object>of(np(DIM7_WAARDEN)), Map.of(),
            l -> ((NatuurlijkPersoon) l.get(0)).getBetaaldeBelastingOverDeAfgelopenVierJaar()),   // 11032

        // D5 §13.4.17.53 the EXPLICIT "over de <meervoud> vanaf … t/m …" form (the resolver re-splits the
        //    ' over de jaardimensies' tail the greedy attribute name absorbed): same interval as D1 = 1000.
        new Fixture("§13.4.17.53 som over de jaardimensies vanaf/t-m (expliciete vorm) telt het interval (1000)",
            DIM7_HDR + """
            Regel Totaal betaalde belasting over 4 jaar
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet berekend worden als de som van zijn betaalde belasting in jaar over de jaardimensies vanaf vier jaar geleden t/m een jaar geleden.
            """,
            () -> List.<Object>of(np(DIM7_WAARDEN)), Map.of(),
            l -> ((NatuurlijkPersoon) l.get(0)).getBetaaldeBelastingOverDeAfgelopenVierJaar()),   // 1000

        // D6 §13.4.17.54 "in {A, B en C}" bereik: exactly the listed labels — 100+300+9999 = 10399.
        new Fixture("§13.4.17.54 som over de jaardimensies in {…} telt precies de genoemde labels (10399)",
            DIM7_HDR + """
            Regel Totaal betaalde belasting over 4 jaar
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet berekend worden als de som van zijn betaalde belasting in jaar over de jaardimensies in {vier jaar geleden, twee jaar geleden en heden}.
            """,
            () -> List.<Object>of(np(DIM7_WAARDEN)), Map.of(),
            l -> ((NatuurlijkPersoon) l.get(0)).getBetaaldeBelastingOverDeAfgelopenVierJaar()),   // 10399

        // D7 §13.4.17.52 MAX over alle labels: max of the seven values = 9999.
        new Fixture("§13.4.17.52 maximale waarde over alle jaardimensies (9999)",
            DIM7_HDR + """
            Regel Totaal betaalde belasting over 4 jaar
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet berekend worden als de maximale waarde van zijn betaalde belasting in jaar over alle jaardimensies.
            """,
            () -> List.<Object>of(np(DIM7_WAARDEN)), Map.of(),
            l -> ((NatuurlijkPersoon) l.get(0)).getBetaaldeBelastingOverDeAfgelopenVierJaar()),   // 9999

        // D3 §3.6 DimensieCel read: "… van huidig jaar" lowers to map.get("huidig jaar") = 222.
        new Fixture("§3.6 dimensiecel lezen pakt het juiste label (222)",
            """
            Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)
                de betaalde belasting over de afgelopen vier jaar Numeriek (geheel getal);
                de betaalde belasting in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
            Regel lees
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet gesteld worden op zijn betaalde belasting in jaar van huidig jaar.
            """,
            () -> List.<Object>of(np(Map.of("vorig jaar", new BigDecimal(111), "huidig jaar", new BigDecimal(222)))),
            Map.of(),
            l -> ((NatuurlijkPersoon) l.get(0)).getBetaaldeBelastingOverDeAfgelopenVierJaar()),   // 222
    };

    /** §3.6/§6.9 (Phase G adversarial review): a DimensieCel read over a NULL gedimensioneerd Map must yield leeg
     *  (null), not crash. Both back-ends are now null-guarded — the model always was (spec-correct), and the DRL oracle's
     *  cell read is now guarded too (was a latent ConsequenceException on a null Map). So this is now a FIRE-BOTH
     *  differential: both fire over a null-Map fact and leave the doel leeg, neither throws. */
    @Test void phase_G_dimensiecel_over_null_map_is_leeg_in_both() {
        String src = """
            Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)
                de betaalde belasting over de afgelopen vier jaar Numeriek (geheel getal);
                de betaalde belasting in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
            Regel lees
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet gesteld worden op zijn betaalde belasting in jaar van huidig jaar.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "model moet de cel-lezing loweren: " + em.overgeslagen());
        for (KieBase kb : List.of(drlBase(m), KieBaseBuilder.createKieBaseFromModel(em.model()))) {
            NatuurlijkPersoon np = new NatuurlijkPersoon();   // betaaldeBelastingInJaar == null
            KieSession s = sessie(kb);
            s.insert(np);
            s.fireAllRules();   // must NOT throw on the null Map in EITHER back-end (was a DRL ConsequenceException)
            s.dispose();
            assertNull(np.getBetaaldeBelastingOverDeAfgelopenVierJaar(), "null-Map DimensieCel -> leeg, niet crash");
        }
    }

    @Test void phase_G_dimensie_matches_drl_back_end()             { runDifferential("Phase G dim", PHASE_G_DIM, 7); }
    /** §13.4.17.52-.54 the three OVER-forms (alle / vanaf-t/m expliciet / in {…}) + MAX-over-alle —
     *  ABSOLUTE values in BOTH back-ends (a shared wrong-label-set bug would pass the parity-only run).
     *  The .53 explicit form also locks the resolver's " over de <meervoud>"-tail re-split. */
    @Test void phase_G_dim_over_vormen_zijn_spec_correct() {
        Object[] verwacht = { new BigDecimal(11032), new BigDecimal(1000), new BigDecimal(10399), new BigDecimal(9999) };
        for (int i = 0; i < verwacht.length; i++) {
            Fixture f = PHASE_G_DIM[2 + i];   // D4..D7
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            assertFalse(DrlTranspiler.transpile(m).contains("/* TODO"),
                    () -> f.naam() + " DRL moet vuren (geen marker):\n" + DrlTranspiler.transpile(m));
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(verwacht[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_G_objectcreatie_matches_drl_back_end()        { runObjDifferential("Phase G obj", PHASE_G_OBJ, 4); }
    /** §13.4.6.4 kenmerkwaardetoekenning — ABSOLUTE pin of the created object's kenmerk in BOTH back-ends
     *  (waar -> true, onwaar -> false; a shared drop-the-kenmerk bug would pass the parity-only Phase G run). */
    @Test void phase_G_kenmerkwaardetoekenning_is_spec_correct() {
        String[] verwacht = { "Passagier|prijs=10|duur=true|reis=0", "Passagier|prijs=10|duur=false|reis=0" };
        ObjFixture[] fixtures = { PHASE_G_OBJ[1], PHASE_G_OBJ[2] };   // O3 waar, O4 onwaar
        for (int i = 0; i < fixtures.length; i++) {
            ObjFixture f = fixtures[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            assertTrue(m.objectCreaties.get(0).toekenningen().stream().anyMatch(
                    t -> t.kenmerk() && "duur".equals(t.attribuut())),
                    () -> f.naam() + " moet een kenmerk-toekenning in de IR dragen: " + m.objectCreaties.get(0).toekenningen());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            List<String> viaDrl = fireCreated(drlBase(m), f);
            List<String> viaModel = fireCreated(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(List.of(verwacht[i]), viaDrl, () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(viaDrl, viaModel, () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    // The inert-voorwaarde fixture is a VERDICT divergence but a behavioral AGREEMENT: the DRL back-end
    // surfaces "create nothing" as an eval(false) marker rule (verdict MARKER), the exec back-end as no
    // rule at all — a deliberate correct lowering, not a skip (verdict LOWERED). The fixture FIRES in the
    // differential and both back-ends create nothing, so the divergence is in the label, not the behavior.
    @Test void phase_G_feitcreatie_matches_drl_back_end()          { runDifferential("Phase G feit", PHASE_G_FEIT, 6, Map.of(
        "§13.4.7 feitcreatie met onlowerbare voorwaarde creeert niets (geen over-creatie) :: feitcreatie: ken bonus toe",
            "beide back-ends creëren niets; DRL markert dat als eval(false)-no-op-regel, exec lowert het als geen-regel — gedragsgelijk, alleen het verdict-label verschilt")); }
    @Test void phase_F4b_mixed_static_guard_matches_drl_back_end() { runDifferential("Phase F4b", PHASE_F4B, 4); }
    @Test void phase_F4_compound_gedurende_matches_drl_back_end()  { runDifferential("Phase F4", PHASE_F4, 6); }
    @Test void phase_F3_tijdsevenredig_matches_drl_back_end()      { runDifferential("Phase F3", PHASE_F3, 5); }
    /** §13.3.6.7 a WRITE to a MULTI-PERIOD temporal doel ("voor elke N maanden") is under-specified — both back-ends
     *  must MARKER it, NEVER silently emit a scalar setter on a List<Segment> field (the temporal AUDIT #7 silent-write
     *  bug: a scalar 5 was being written to a multi-period List timeline in both back-ends with no marker). */
    @Test void multi_period_doel_write_is_honest_marker_in_both() {
        String src = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de bedrag Bedrag voor elke 2 maanden startend op 1-1-2024;
            Regel R
                geldig altijd
                    De bedrag van een Passagier moet gesteld worden op 5.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO meer-periodieke"),
                () -> "DRL moet een meer-periodieke doel-write markeren:\n" + drl);
        assertFalse(drl.contains("setBedrag( new java.math.BigDecimal"),
                () -> "DRL mag GEEN scalar setBedrag(5) op een meer-periodiek List<Segment>-veld emitteren:\n" + drl);
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().stream().anyMatch(s -> s.contains("meer-periodieke")),
                () -> "de exec moet een meer-periodieke doel-write overslaan, kreeg: " + em.overgeslagen());
    }
    @Test void phase_F2_aantal_dagen_in_matches_drl_back_end()     { runDifferential("Phase F2", PHASE_F2, 2); }
    @Test void phase_F_temporal_totaal_matches_drl_back_end()      { runDifferential("Phase F", PHASE_F, 13); }
    /** §5.1.4 general temporal-arithmetic WRITE: a temporal doel = <temporal> plus/min <temporal> evaluated per merged-knip
     *  period (leeg -> 0) now FIRES in both back-ends via rsTijdReken (was a symmetric marker). The result timeline is
     *  read back canonicalised (tl) and asserted against the spec's §5.1.4.1 worked-example table + parity. */
    @Test void phase_TR_temporal_arithmetic_is_spec_correct() {
        for (int i = 0; i < PHASE_TR.length; i++) {
            Fixture f = PHASE_TR[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_TR_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.1.4.2 the spec's COMPLEX worked example: MIXED granularity (dag+maand -> dag) AND CROSS-UNIT (€/mnd operands
     *  restated ×12 to a €/jr result) now FIRE in both back-ends — the merged-knip rsTijdReken over per-operand
     *  rsTijdSchaal restatements (was a symmetric marker). The full worked-example table (spec L1729-1738, with the
     *  1-6 coalesce) is asserted ABSOLUTELY, plus a cross-unit-only and a mixed-gran-only isolation, + DRL==exec parity. */
    @Test void phase_TR2_mixed_gran_cross_unit_is_spec_correct() {
        for (int i = 0; i < PHASE_TR2.length; i++) {
            Fixture f = PHASE_TR2[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_TR2_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.1.4 an N-operand temporal arithmetic chain ("a plus b plus c") now FIRES in both back-ends — temporeelRekenOperand
     *  recurses over a nested Binair, summing per merged-knip period (leeg -> 0). Absolute + DRL==exec parity + no skip. */
    @Test void phase_TR3_temporal_chain_is_spec_correct() {
        for (int i = 0; i < PHASE_TR3.length; i++) {
            Fixture f = PHASE_TR3[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_TR3_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.8.2/§5.1.4 a SOM over a NAVIGATED to-many TEMPORAL attribute folded PER PERIOD into a TEMPORAL doel now FIRES
     *  in both back-ends — a collectList accumulate + rsTijdSomAlle (was a symmetric "temporele toekenning" marker). The
     *  ABSOLUTE folded Segment lists pin the §5.8.2 leeg semantics: an empty contributor is SKIPPED (not 0), an all-empty
     *  period is leeg, and "of 0 als die er niet zijn" folds that period to 0 instead. DRL==exec parity + no exec skip. */
    @Test void phase_SOMALLE_temporal_som_over_to_many_is_spec_correct() {
        for (int i = 0; i < PHASE_SOMALLE.length; i++) {
            Fixture f = PHASE_SOMALLE[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_SOMALLE_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.2 over a NAVIGATED to-many TEMPORAL attribute — a whole-period FORALL across the accumulated element timelines —
     *  now FIRES in both back-ends (a collectList accumulate + rsGeheelPeriodeAlle result-var eval; was a symmetric
     *  marker). Absolute firing (forall passes iff EVERY element's whole-year check holds; empty set = vacuously WAAR) +
     *  DRL==exec parity + no exec skip. The genuinely-undefined timeline-vs-timeline comparison stays a symmetric marker. */
    @Test void phase_GP_ALLE_wholeperiod_forall_over_to_many_is_spec_correct() {
        for (int i = 0; i < PHASE_GP_ALLE.length; i++) {
            Fixture f = PHASE_GP_ALLE[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_GP_ALLE_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.2 the genuinely-undefined TIMELINE-vs-TIMELINE whole-period comparison over a navigated to-many set stays a
     *  SYMMETRIC marker in both back-ends (the DRL markers, the exec skips) — the reachable §8.2 win is the forall vs a
     *  SCALAR grens (phase_GP_ALLE); a per-moment comparison of one element-set's timeline against ANOTHER's has no §8.2
     *  fold and is deliberately NOT lowered. Also pins the non-scalar-grens set §8.2 (grens = an own attribute) as a
     *  symmetric marker: the set eval rides after the collectList accumulate with no $subject-qualified field. */
    @Test void gehele_periode_over_verzameling_niet_lowerbaar_is_symmetrische_marker() {
        String hdr =
            "Domein Bedrag is van het type Numeriek (geheel getal)\n" +
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
            "    de belastingvermindering per maand Bedrag voor elke maand;\n" +
            "    de inkomen per maand Bedrag voor elke maand;\n" +
            "Objecttype de Vlucht (mv: Vluchten)\n    is groot kenmerk (bijvoeglijk);\n    de drempel Numeriek (geheel getal);\n" +
            "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n";
        record Geval(String naam, String voorwaarde) {}
        Geval[] gevallen = {
            // timeline-vs-timeline: both sides are to-many temporal sets — no §8.2 fold defined -> marker/skip.
            new Geval("tijdlijn-vs-tijdlijn", "de belastingvermindering per maand van alle passagiers van de Vlucht gedurende het gehele jaar groter is dan de inkomen per maand van alle passagiers van de Vlucht"),
            // non-scalar grens (an OWN attribute of the subject): the set eval has no $subject-qualified field -> marker/skip.
            new Geval("niet-scalaire grens (eigen attribuut)", "de belastingvermindering per maand van alle passagiers van de Vlucht gedurende het gehele jaar groter is dan de drempel van de Vlucht"),
        };
        for (Geval g : gevallen) {
            String src = hdr + "Regel R\n    geldig altijd\n        Een Vlucht is groot indien " + g.voorwaarde() + ".\n";
            RegelSpraakModel m = Resolver.resolve(src);
            assertTrue(m.errors().isEmpty(), () -> g.naam() + " resolver-fout: " + m.errors());
            String drl = DrlTranspiler.transpile(m);
            assertTrue(drl.contains("/* TODO"), () -> g.naam() + ": DRL moet markeren (tijdlijn-vs-tijdlijn / niet-scalaire grens over §8.2-verzameling):\n" + drl);
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertFalse(em.overgeslagen().isEmpty(), () -> g.naam() + ": exec moet SKIPPEN, symmetrisch met de DRL-marker");
        }
    }

    /** §5.8/§13.4.14 a COUNT aggregation as one side of a CONDITION comparison now FIRES in both back-ends — a result-
     *  constrained accumulate CE (was a symmetric marker). Absolute (count vs the literal N over the subject's related
     *  count) across >, >=, ==, < and their boundaries + DRL==exec parity + no exec skip. */
    @Test void phase_AGG_COND_aggregation_in_condition_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_COND.length; i++) {
            Fixture f = PHASE_AGG_COND[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_COND_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.8 VALUE-FOLD aggregations (SUM/TOTAAL/MIN/MAX) in a CONDITION now FIRE in both back-ends via the value-side
     *  accumulate + rsAggVergelijk (was a symmetric marker). Absolute over each fold + the empty-set leegwaarde split
     *  (empty SOM/MIN leeg -> onwaar; empty TOTAAL 0 -> the "< 100" case is waar) + DRL==exec parity + no exec skip. */
    @Test void phase_AGG_FOLD_value_fold_in_condition_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_FOLD.length; i++) {
            Fixture f = PHASE_AGG_FOLD[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_FOLD_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.8 an aggregation in a condition vs a NON-LITERAL (own-attribute) threshold now FIRES in both back-ends — the
     *  DRL eval reads the $subject-qualified getter, the exec binds the subject var in the result-var constraint. Absolute
     *  (count/SUM vs the subject's own attribute) + DRL==exec parity + no exec skip. */
    @Test void phase_AGG_RHS_own_attribute_threshold_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_RHS.length; i++) {
            Fixture f = PHASE_AGG_RHS[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_RHS_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5 Tabel 37 GELIJK leegwaarde for an aggregation-in-condition (review wf_900d3055-a62 F1): both-leeg -> WAAR,
     *  one-leeg -> onwaar, in BOTH back-ends (rsAggVergelijk). */
    @Test void phase_AGG_GELIJK_leegwaarde_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_GELIJK.length; i++) {
            Fixture f = PHASE_AGG_GELIJK[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_GELIJK_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.6 Tabel 44 ONGELIJK ("ongelijk is aan") for an aggregation-in-condition now FIRES in both back-ends (was a
     *  symmetric marker): both-gevuld a!=b, and the leeg semantics leeg!=gevuld -> WAAR / leeg!=leeg -> onwaar (the inverse
     *  of the GELIJK Tabel 37). Absolute + DRL==exec parity + no exec skip. */
    @Test void phase_AGG_ONGELIJK_leegwaarde_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_ONGELIJK.length; i++) {
            Fixture f = PHASE_AGG_ONGELIJK[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_ONGELIJK_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.8 an aggregation-in-condition vs a numeric scalar Parameter-GLOBAL threshold now FIRES in both back-ends (was a
     *  symmetric marker): the DRL reads the header-declared global, the exec binds the Global into the result constraint.
     *  Absolute + DRL==exec parity + no skip, incl. the UNSET (leeg) global corner (count>leeg onwaar, count!=leeg WAAR). */
    @Test void phase_AGG_GLOBAL_threshold_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_GLOBAL.length; i++) {
            Fixture f = PHASE_AGG_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_GLOBAL_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.8.3 an empty MIN/MAX in a condition is LEEG — the condition-path accumulate binds count() so the empty-set
     *  aggregate still fires (was silently onwaar): GELIJK leeg==leeg -> WAAR, ONGELIJK leeg!=gevuld -> WAAR / leeg!=leeg ->
     *  onwaar, ordering -> onwaar. Absolute + DRL==exec parity + no skip. Closes 20260701-min-max-empty-set-gelijk-leeg. */
    @Test void phase_AGG_MINMAX_LEEG_empty_set_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_MINMAX_LEEG.length; i++) {
            Fixture f = PHASE_AGG_MINMAX_LEEG[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_MINMAX_LEEG_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.8 an aggregation-in-condition vs a to-ONE NAVIGATED numeric threshold (possessive "de capaciteit van zijn
     *  vliegtuig") now FIRES in both back-ends — DRL rsRelGetal / exec navReader, both null-safe for an ABSENT relation
     *  (-> leeg). Absolute + DRL==exec parity + no skip, incl. the absent-relation corner (count>leeg onwaar, count!=leeg WAAR). */
    @Test void phase_AGG_NAV_navigated_threshold_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_NAV.length; i++) {
            Fixture f = PHASE_AGG_NAV[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_NAV_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.8 agg-vs-agg (both operands aggregations, "het aantal X <op> de som van ...") now FIRES in both back-ends: two
     *  accumulate CEs + a two-result rsAggVergelijk compare. Absolute + DRL==exec parity + no skip, over Predicate3/4/5
     *  (count/totaal, one count-bound, both count-bound) incl. the empty-set leeg corners. */
    @Test void phase_AGG_VS_AGG_two_aggregations_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_VS_AGG.length; i++) {
            Fixture f = PHASE_AGG_VS_AGG[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_VS_AGG_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.1 CORNER 1: an aggregation-in-condition vs a RHS arithmetic expression reading 2 DISTINCT scalar
     *  Parameter-globals ("het aantal … groter is dan de grensa plus de grensb") now FIRES in both back-ends (was a
     *  symmetric marker). Absolute + DRL==exec parity + no skip, incl. a LEEG global inside the +/- (leeg-as-0, §6.2). */
    @Test void phase_AGG_MULTI_GLOBAL_rhs_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_MULTI_GLOBAL.length; i++) {
            Fixture f = PHASE_AGG_MULTI_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_MULTI_GLOBAL_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §13.4.14.4 CORNER 2: a SINGLE aggregation bullet under GEEN / "ten minste 1" now FIRES in both back-ends (was a
     *  broken DRL "!(())" + leaked accumulate / exec skip). Absolute + DRL==exec parity + no skip. The GEEN(SOM>10)-over-
     *  empty row is the leegwaarde soundness pin: result-negation gives WAAR, an operator-flip would give the wrong onwaar. */
    @Test void phase_AGG_QUANTIFIER_single_bullet_is_spec_correct() {
        for (int i = 0; i < PHASE_AGG_QUANTIFIER.length; i++) {
            Fixture f = PHASE_AGG_QUANTIFIER[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_AGG_QUANTIFIER_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** CORNER 1/2 boundaries stay SYMMETRIC markers: 3+ DISTINCT globals in an aggregation RHS (exec Predicate arity
     *  ceiling), and a MULTI-bullet or ten hoogste / precies aggregation compound (the counting fold cannot host >1
     *  accumulate leaf). DRL markers AND exec SKIPS — never one firing while the other declines. */
    @Test void phase_AGG_corner_boundaries_are_symmetric_markers() {
        String threeGlobals =
            "Parameter de a : Numeriek (geheel getal)\nParameter de b : Numeriek (geheel getal)\nParameter de c : Numeriek (geheel getal)\n" +
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
            "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
            "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
            "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien het aantal passagiers van de Vlucht groter is dan de a plus de b plus de c.\n";
        String multiBullet =
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de leeftijd Numeriek (geheel getal);\n" +
            "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n    de vertrektijd Numeriek (geheel getal);\n" +
            "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
            "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien de Vlucht aan geen van de volgende voorwaarden voldoet:\n" +
            "        - het aantal passagiers van de Vlucht groter is dan 10\n        - zijn vertrektijd is groter dan 5.\n";
        String tenHoogste =
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
            "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
            "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
            "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien de Vlucht aan ten hoogste 1 van de volgende voorwaarden voldoet:\n        - het aantal passagiers van de Vlucht groter is dan 10.\n";
        for (String src : new String[]{threeGlobals, multiBullet, tenHoogste}) {
            RegelSpraakModel m = Resolver.resolve(src);
            assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
            assertTrue(DrlTranspiler.transpile(m).contains("/* TODO"), () -> "DRL must MARKER the out-of-scope aggregation corner");
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertFalse(em.overgeslagen().isEmpty(), () -> "exec must SKIP the out-of-scope aggregation corner");
        }
    }
    /** review wf_ee9d1919-c3c: a post-accumulate bail in aggregatieVoorwaarde (an unresolvable 2nd aggregation in
     *  agg-vs-agg, or a to-many navigated RHS in a single-agg) must NOT leave an ORPHAN accumulate in the marker rule —
     *  aggTerug rolls back the shared `accumulates` list. Both sources resolve cleanly, then MARKER with no dangling
     *  accumulate (DRL) and SKIP (exec). */
    @Test void phase_AGG_no_orphan_accumulate_on_marker() {
        String aggVsAgg =
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n" +
            "Objecttype het Bemanningslid (mv: Bemanningsleden) (bezield)\n" +
            "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
            "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nÉén reis heeft meerdere passagiers\n" +
            "Feittype bemanning\n    de bemande vlucht Vlucht\n    het bemanningslid Bemanningslid\nÉén bemande vlucht heeft meerdere bemanningsleden\n" +
            "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien het aantal passagiers van de Vlucht gelijk is aan het aantal bemanningsleden van de Vlucht.\n";
        String toManyNav =
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de leeftijd Numeriek (geheel getal);\n" +
            "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
            "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nÉén reis heeft meerdere passagiers\n" +
            "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien het aantal passagiers van de Vlucht groter is dan de leeftijd van zijn passagiers.\n";
        for (String src : new String[]{aggVsAgg, toManyNav}) {
            RegelSpraakModel m = Resolver.resolve(src);
            assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
            String drl = DrlTranspiler.transpile(m);
            assertTrue(drl.contains("/* TODO"), "must MARKER the unlowerable aggregation comparison");
            assertFalse(drl.contains("accumulate("), "must NOT leak an orphan accumulate into the marker rule");
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertFalse(em.overgeslagen().isEmpty(), "exec must SKIP the unlowerable aggregation comparison");
        }
    }
    /** review wf_900d3055-a62 F2: an aggregation-in-condition vs a NAVIGATED RHS must be a SYMMETRIC marker — DRL markers
     *  AND exec SKIPS (no live rule, no ConstraintEvaluationException at fireAllRules). */
    @Test void phase_AGG_navigated_rhs_is_symmetric_marker() {
        RegelSpraakModel m = Resolver.resolve(AGG_NAV_RHS_SRC);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        assertTrue(DrlTranspiler.transpile(m).contains("/* TODO"), () -> "DRL must MARKER the navigated-RHS aggregation condition");
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertFalse(em.overgeslagen().isEmpty(), () -> "exec must SKIP the navigated-RHS aggregation condition (not build a live crashing rule)");
    }
    /** review verification (agent af7975835bd6dbf65): a COUNT-with-a-comparison-die-filter in a condition (accumulate()
     *  returns NULL, not a marker Frag) must NOT NPE in DrlTranspiler.transpile() (which sank the whole DRL package); it
     *  markers symmetrically. */
    @Test void phase_AGG_count_die_filter_no_npe() {
        String src =
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de leeftijd Numeriek (geheel getal);\n" +
            "Objecttype de Vlucht (mv: Vluchten)\n    is vol kenmerk (bijvoeglijk);\n" +
            "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
            "Regel Vol\n    geldig altijd\n        Een Vlucht is vol indien het aantal passagiers van de Vlucht die groter zijn dan 18 groter is dan 1.\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        assertTrue(DrlTranspiler.transpile(m).contains("/* TODO"), () -> "DRL must MARKER (not NPE) the count-with-die-filter condition");
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertFalse(em.overgeslagen().isEmpty(), () -> "exec must SKIP the count-with-die-filter condition");
    }
    @Test void phase_82_gehele_periode_matches_drl_back_end()      { runDifferential("Phase 82", PHASE_82, 10); }
    @Test void phase_82_kenmerk_matches_drl_back_end()             { runDifferential("Phase 82 kenmerk", PHASE_82_KENMERK, 2); }
    /** §8.2 line 3468 whole-period LEEG ("... gedurende de gehele maand leeg is") now FIRES in both back-ends (toets 8,
     *  the inverse of GEVULD), was a symmetric marker. Absolute (leeg-segment -> WAAR, value -> ONWAAR, null -> vacuous WAAR) + parity. */
    @Test void phase_82_leeg_is_spec_correct() {
        for (int i = 0; i < PHASE_82_LEEG.length; i++) {
            Fixture f = PHASE_82_LEEG[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_82_LEEG_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_82_consistentie_matches_drl_back_end()        { runFoutDifferential("Phase 82 fout", PHASE_82_FOUT, 1); }
    @Test void phase_82_navigated_grens_matches_drl_back_end()     { runDifferential("Phase 82 nav", PHASE_82_NAV, 3); }
    @Test void phase_82_in_counting_quantifier_matches_drl_back_end() { runDifferential("Phase 82 counting", PHASE_82_COUNTING, 3); }
    /** §8.2 + §5.1 cross-unit grens: a temporal attribute in mnd compared "gedurende het gehele jaar gelijk is aan 1 jr"
     *  -> both back-ends restate the grens 1 jr = 12 mnd by the EXACT factor before the per-bucket compare (was a
     *  symmetric marker). Closed-form -> assert the ABSOLUTE value (12==1jr TRUE, 11!=1jr FALSE) + parity + no exec skip. */
    @Test void phase_82_cross_unit_grens_is_spec_correct() {
        for (int i = 0; i < PHASE_82_CU.length; i++) {
            Fixture f = PHASE_82_CU[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_82_CU_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.2 + §7.1 a whole-period check over a to-one NAVIGATED temporal kenmerk ("zijn reis gedurende het gehele jaar
     *  actief is") now FIRES in both back-ends (DRL navigatieKenmerkTijdreeks, exec navigatieKenmerkTijdreeks), was a
     *  symmetric marker. The ABSENT-relation row (leeg timeline -> vacuously TRUE) is the load-bearing parity check.
     *  Absolute + DRL==exec + exec NOT skipping. */
    @Test void phase_82_navigated_kenmerk_is_spec_correct() {
        for (int i = 0; i < PHASE_82_NAVKENMERK.length; i++) {
            Fixture f = PHASE_82_NAVKENMERK[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_82_NAVKENMERK_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_H_navigated_comparison_matches_drl_back_end()  { runDifferential("Phase H", PHASE_H, 17); }
    @Test void phase_H_versie_geldigheid_matches_drl_back_end()     { runDifferential("Phase H geldig", PHASE_GELDIG, 12); }
    @Test void phase_PERIODE_matches_drl_back_end()                 { runDifferential("Phase PERIODE", PHASE_PERIODE, 17); }
    @Test void phase_REKENDATUM_matches_drl_back_end()              { runDifferential("Phase REKENDATUM", PHASE_REKENDATUM, 13); }
    @Test void phase_TAIL_matches_drl_back_end()                    { runDifferential("Phase TAIL", PHASE_TAIL, 14); }
    // the untested-tail behaviours pinned to their ABSOLUTE spec values (DRL as oracle first, then parity).
    @Test void phase_TAIL_is_spec_correct() {
        for (int i = 0; i < PHASE_TAIL.length; i++) {
            Fixture f = PHASE_TAIL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_TAIL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    // §5.3: every fixture pinned to its ABSOLUTE value (DRL as oracle first, then parity).
    @Test void phase_REKENDATUM_is_spec_correct() {
        for (int i = 0; i < PHASE_REKENDATUM.length; i++) {
            Fixture f = PHASE_REKENDATUM[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_REKENDATUM_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    // §8.4.3: every fixture pinned to its ABSOLUTE §5.1.3 window value (DRL as oracle first, then parity) —
    // never differential-only, so a shared both-back-ends bug cannot hide behind agreement.
    @Test void phase_PERIODE_is_spec_correct() {
        for (int i = 0; i < PHASE_PERIODE.length; i++) {
            Fixture f = PHASE_PERIODE[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_PERIODE_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    // §8.4.3 the shapes NEITHER back-end lowers yet must decline SYMMETRICALLY (an honest marker/skip, never a
    // DRL-fires/exec-skips divergence): a periode-bullet under a COUNTING quantifier (the exec fact-only fold
    // cannot bind the rekendatum global), and a periodevergelijking on a TIJDSAFHANKELIJK doel (it should CLIP
    // the timeline, not gate it on the rekendatum).
    @Test void phase_PERIODE_counting_quantor_declines_symmetrically() {
        String src = PERIODE_COMPOUND_OT + "Regel R\n    geldig altijd\n"
                + "        De prijs van een Passagier moet gesteld worden op 50\n"
                + "        gedurende de tijd dat er aan ten minste 1 van de volgende voorwaarden wordt voldaan:\n"
                + "        • hij student is\n"
                + "        • het is de periode van dd. 1-1-2024 tot dd. 8-2-2025.\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        String drl = DrlTranspiler.build(m).drl();
        assertTrue(drl.contains("tellende kwantor"), () -> "verwacht DRL-marker voor de tellende kwantor:\n" + drl);
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertFalse(em.overgeslagen().isEmpty(), "verwacht een exec-skip voor de tellende kwantor");
        // the fully-markered rule references no global, so (lazy splice) the DRL declares none — fire bare.
        Fixture f = single("counting+periode declines", src, () -> passagier(p -> p.setStudent(true)),
                Map.of(), Passagier::getPrijs);
        assertEquals(eq(null), eq(fire(drlBase(m), f)), "de DRL-marker mag nooit vuren");
    }
    @Test void phase_PERIODE_temporal_doel_declines_symmetrically() {
        String src = "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
                + "    de belastingvermindering Numeriek (geheel getal) voor elke maand;\n"
                + "Regel R\n    geldig altijd\n"
                + "        De belastingvermindering van een Passagier moet gesteld worden op 10\n"
                + "        van dd. 1-1-2024 tot dd. 8-2-2025.\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        String drl = DrlTranspiler.build(m).drl();
        assertTrue(drl.contains("tijdsafhankelijk doel"), () -> "verwacht DRL-marker op het tijdsafhankelijke doel:\n" + drl);
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().stream().anyMatch(s -> s.contains("tijdsafhankelijk doel")),
                () -> "verwacht een exec-skip op het tijdsafhankelijke doel: " + em.overgeslagen());
    }
    @Test void phase_H_value_ops_matches_drl_back_end()             { runDifferential("Phase H val", PHASE_H_VAL, 8); }
    @Test void phase_H_builtin_functies_match_drl_back_end()        { runDifferential("Phase H func", PHASE_H_FUNC, 4); }
    @Test void phase_C2_aggregation_in_arithmetic_matches_drl_back_end() { runDifferential("Phase C2", PHASE_C2, 5); }
    @Test void beslistabel_rows_match_drl_back_end()          { runDifferential("Beslistabel", BESLISTABEL, 6); }
    @Test void phase_A_model_back_end_matches_drl_back_end()  { runDifferential("Phase A", PHASE_A, 3); }
    @Test void phase_VW_EQ_model_back_end_matches_drl_back_end() { runDifferential("Phase VW-EQ", PHASE_VW_EQ, 6); }
    /** The two exec condition-lowering lifts (Boolean-attr == literal; datum == eerste-paasdag builtin) — the ABSOLUTE
     *  spec outcomes, not just DRL==exec parity (a shared upstream bug would pass parity alone). The DRL back-end is the
     *  oracle for the outcome; a hand-checked TRUE case (attr=true fires; 2024-03-31 IS Easter 2024) anchors each lift. */
    @Test void phase_VW_EQ_condition_lowering_is_spec_correct() {
        for (int i = 0; i < PHASE_VW_EQ.length; i++) {
            Fixture f = PHASE_VW_EQ[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_VW_EQ_EXPECTED[i], viaDrl, () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_TEKST_ATTR_model_back_end_matches_drl_back_end() { runDifferential("Phase TEKST-ATTR", PHASE_TEKST_ATTR, 8); }
    /** LIFT B: a NON-numeriek (Tekst) GELIJK/ONGELIJK own-attr-vs-own-attr equality in a CONDITION — the ABSOLUTE spec
     *  outcomes (Tabel 38 GELIJK / Tabel 44 ONGELIJK leegwaarde cells), not just DRL==exec parity. A parity-only test
     *  would pass VACUOUSLY when the rule is silently skipped, so every cell (incl. the VIOLATING/firing case) is pinned
     *  against the hand-computed truth table AND the DRL oracle. */
    @Test void phase_TEKST_ATTR_condition_lowering_is_spec_correct() {
        for (int i = 0; i < PHASE_TEKST_ATTR.length; i++) {
            Fixture f = PHASE_TEKST_ATTR[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_TEKST_ATTR_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_B_model_back_end_matches_drl_back_end()  { runDifferential("Phase B", PHASE_B, 3); }
    @Test void phase_B2_quantifiers_match_drl_back_end()       { runDifferential("Phase B2", PHASE_B2, 5); }
    @Test void phase_B3_relational_match_drl_back_end()        { runDifferential("Phase B3", PHASE_B3, 6); }
    @Test void phase_B4_comparison_semantics_match_drl_back_end() { runDifferential("Phase B4", PHASE_B4, 7); }
    @Test void phase_B5_then_side_global_matches_drl_back_end()   { runDifferential("Phase B5", PHASE_B5, 4); }
    @Test void phase_C_aggregations_match_drl_back_end()          { runDifferential("Phase C", PHASE_C, 14); }
    @Test void phase_C_param_rhs_subselectie_matches_drl_back_end() { runDifferential("Phase C param-RHS", PHASE_C_PARAM_RHS, 2); }
    @Test void phase_C_compound_subselectie_matches_drl_back_end() { runDifferential("Phase C compound", PHASE_C_COMPOUND, 2); }
    @Test void phase_INLINE_predicaat_matches_drl_back_end()      { runDifferential("Phase INLINE", PHASE_INLINE, 3); }
    @Test void phase_REGELVERSIE_matches_drl_back_end()           { runDifferential("Phase REGELVERSIE", PHASE_REGELVERSIE, 2); }
    @Test void phase_C_count_global_subselectie_matches_drl_back_end() { runDifferential("Phase C count+global", PHASE_C_COUNT_GLOBAL, 4); }
    @Test void phase_crossunit_matches_drl_back_end() { runDifferential("Phase cross-unit", PHASE_CROSSUNIT, 2); }
    @Test void phase_cu_arith_matches_drl_back_end() { runDifferential("Phase cross-unit +/-", PHASE_CU_ARITH, 5); }
    @Test void phase_cu_value_matches_drl_back_end() { runDifferential("Phase cross-unit value", PHASE_CU_VALUE, 3); }
    @Test void phase_cu_agg_matches_drl_back_end() { runDifferential("Phase cross-unit min/max agg", PHASE_CU_AGG, 2); }
    @Test void phase_cu_rate_matches_drl_back_end() { runDifferential("Phase cross-unit rate", PHASE_CU_RATE, 2); }
    /** §4 a COMPOSITE (rate) cross-unit restatement is now EXACT (numerator × inverted-denominator integer factors):
     *  120 EUR/jr assigned to a EUR/mnd attribute = 10, not 9.99999999999999960 (a SHARED lossy value — assert ABSOLUTE).
     *  The rate +/- CONSTRAINT now FIRES at parity too (was a symmetric marker/skip). */
    @Test void phase_cross_unit_rate_restatement_is_spec_correct() {
        for (int i = 0; i < PHASE_CU_RATE.length; i++) {
            Fixture f = PHASE_CU_RATE[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_CU_RATE_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_VW_multiglobal_matches_drl_back_end() { runDifferential("Phase VW-multiglobal", PHASE_VW_MULTIGLOBAL, 2); }
    /** §8.1.1 the toka-45 unblock: a condition whose LHS is an Onbekend-typed composite-unit (unitless-Bedrag × km)
     *  arithmetic expr reading TWO Parameter-globals, compared >= 0, gating a gelijkstelling. ABSOLUTE outcome (not
     *  just DRL==exec parity — a shared upstream bug would pass parity alone): basistarief 100 - 0.20*200 = 60 >= 0
     *  fires -> belasting 60; 100 - 0.20*600 = -20 < 0 does NOT fire -> belasting null. DRL is the oracle first. */
    @Test void phase_VW_multiglobal_is_spec_correct() {
        for (int i = 0; i < PHASE_VW_MULTIGLOBAL.length; i++) {
            Fixture f = PHASE_VW_MULTIGLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_VW_MULTIGLOBAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.8.3 a MIN/MAX instance-aggregation into a different-unit doel: the DRL must COERCE the min/max binding (a
     *  java.lang.Number) to BigDecimal (rsGetal) before the cross-unit restate, else its build fails (Number.multiply)
     *  while the exec fires — a divergence the wf_7e1d0ff9 review caught. ABSOLUTE: max(24,12,6) mnd = 2 jr, min = 0.5 jr. */
    @Test void phase_cross_unit_minmax_aggregation_is_spec_correct() {
        for (int i = 0; i < PHASE_CU_AGG.length; i++) {
            Fixture f = PHASE_CU_AGG[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_CU_AGG_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_enum_global_matches_drl_back_end() { runDifferential("Phase enum-vs-global", PHASE_ENUM_GLOBAL, 6); }
    @Test void phase_enum_assign_matches_drl_back_end() { runDifferential("Phase non-numeric-global assign", PHASE_ENUM_ASSIGN, 4); }
    /** §13.4.6 assigning a NON-NUMERIC Parameter-global INTO an attribute now fires in BOTH back-ends (was a DRL
     *  build-error / exec-skip): a Tekst/Enumeratie String, a Datum LocalDate, or a Datum-en-tijd LocalDateTime. DRL
     *  declares the global with its concrete type; the exec binds the typed global + sets it directly. */
    @Test void phase_enum_global_assignment_is_spec_correct() {
        for (int i = 0; i < PHASE_ENUM_ASSIGN.length; i++) {
            Fixture f = PHASE_ENUM_ASSIGN[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_ENUM_ASSIGN_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.3 an ENUMERATIE attribute compared to an enum-typed Parameter-global now FIRES in the exec (was a masked
     *  DRL-fires/exec-skips: the tekst-vs-global branch gated on isTekst(), excluding Enumeratie). Both compare by
     *  String-label equality (tekstVergelijk) with the §5 leegwaarde tables. Covers own + to-one NAVIGATED, incl. the
     *  ABSENT-relation ONGELIJK row (leeg != X = WAAR, Tabel 44). */
    @Test void phase_enum_vs_global_is_spec_correct() {
        for (int i = 0; i < PHASE_ENUM_GLOBAL.length; i++) {
            Fixture f = PHASE_ENUM_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_ENUM_GLOBAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §4 the EXACT canonical-unit restatement now also covers the VALUE side beyond +/-: a pure cross-unit ASSIGNMENT
     *  (24 mnd stored into a jr attribute = 2, not 1.99999999999999992) and a value-side BEGRENZING bound (the bound
     *  restated mnd→jr exactly). A SHARED lossy-ratio bug -> assert the ABSOLUTE spec value (differential-masks-shared-bugs). */
    @Test void phase_cross_unit_value_restatement_is_spec_correct() {
        for (int i = 0; i < PHASE_CU_VALUE.length; i++) {
            Fixture f = PHASE_CU_VALUE[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_CU_VALUE_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §4 a SIMPLE convertible cross-unit +/- restates EXACTLY (×÷ the declared integer factorNaarCanoniek, NOT the
     *  pre-rounded 1/12 ratio): "2 jr + 24 mnd" = 4 jr, "1 jr + 6 mnd" = 1.5 jr — both EXACT where the old lossy ratio
     *  gave 3.99999999999999992 / 1.49999999999999998 in BOTH back-ends (a SHARED bug, hence the ABSOLUTE assertion).
     *  The constraint side now FIRES at parity (was a symmetric marker), incl. a leeg operand (leeg=0, §6.2 Tabel 3). */
    @Test void phase_cross_unit_arithmetic_is_spec_correct() {
        for (int i = 0; i < PHASE_CU_ARITH.length; i++) {
            Fixture f = PHASE_CU_ARITH[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_CU_ARITH_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_expr_cmp_matches_drl_back_end() { runDifferential("Phase expr-cmp", PHASE_EXPR_CMP, 2); }
    @Test void phase_expr_global_matches_drl_back_end() { runDifferential("Phase expr-global", PHASE_EXPR_GLOBAL, 2); }
    /** §8.1.1 an EXPRESSION-vs-GLOBAL comparison now fires in the exec (binds the single distinct global as a pattern
     *  variable), at parity with DRL's free-name global. Closed-form -> assert the ABSOLUTE spec value + parity, incl. a
     *  leeg operand (leeg -> 0) and a global nested INSIDE the expression. */
    @Test void phase_expr_global_is_spec_correct() {
        for (int i = 0; i < PHASE_EXPR_GLOBAL.length; i++) {
            Fixture f = PHASE_EXPR_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_EXPR_GLOBAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.1 a comparison reading 2..3 DISTINCT numeric globals now FIRES at parity (each global bound into its own
     *  Optional declaration sourced from subject × global — distinct decls don't collapse, the subject anchor keeps a
     *  LEEG global's source non-empty). Closed-form -> ABSOLUTE spec value + parity + the exec NOT skipping. Covers 2 and
     *  3 globals, a leeg global INSIDE arithmetic (leeg-as-0), and a leeg BARE comparison operand (onwaar). */
    @Test void phase_expr_multi_global_is_spec_correct() {
        for (int i = 0; i < PHASE_EXPR_MULTI_GLOBAL.length; i++) {
            Fixture f = PHASE_EXPR_MULTI_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_EXPR_MULTI_GLOBAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.1 a comparison reading FOUR distinct globals stays an honest exec skip (the from-decl fold supports 2..3; a
     *  4th is rare and out of scope). DRL fires it — recorded overgeslagen so the differential excludes it (never a silent
     *  divergence). Pins the >3 boundary of the multi-global fire. */
    @Test void expr_with_four_globals_is_honest_exec_skip() {
        String src = "Parameter de a : Numeriek (geheel getal)\nParameter de b : Numeriek (geheel getal)\n"
            + "Parameter de c : Numeriek (geheel getal)\nParameter de d : Numeriek (geheel getal)\n" + EXPR_CMP_HDR
            + "Regel R\n    geldig altijd\n        Een Passagier is duur indien zijn basistarief plus de a plus de b plus de c groter is dan de d.\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet resolven: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertFalse(em.overgeslagen().isEmpty(), () -> "een vier-globals expr-vergelijking moet een eerlijke exec-skip zijn, kreeg: " + em.overgeslagen());
    }
    /** §5.1/§4 a COMPOSITE (rate) cross-unit +/- in an arithmetic comparison now FIRES exactly (was a symmetric marker):
     *  the EUR/jr→EUR/mnd restatement uses the exact numerator/inverted-denominator integer factors (Eenheid.exacte-
     *  HerschaalFactoren) instead of the pre-rounded single ratio, so DRL herschaalConditiePlus emits rsHerschaalExact and
     *  the exec parityOnveilig no longer skips. (An OPAQUE product unit — €·m, no canonical — would still marker, but two
     *  such are never inter-convertible so a cross-unit +/- never reaches that branch.) */
    @Test void cross_unit_RATE_arithmetic_comparison_fires_exactly_in_both() {
        RegelSpraakModel m = Resolver.resolve(CROSS_UNIT_ARITH_CMP_SRC);
        assertTrue(m.errors().isEmpty(), () -> "moet resolven: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "de exec moet een rate cross-unit +/- voorwaarde nu vuren, kreeg overgeslagen: " + em.overgeslagen());
        assertFalse(DrlTranspiler.transpile(m).contains("/* TODO voorwaarde"),
                () -> "DRL moet een rate cross-unit +/- voorwaarde nu vuren (geen marker):\n" + DrlTranspiler.transpile(m));
    }
    /** §4.4 Tabel 11 a unitless LITERAL × a unit-bearing operand carries the right operand's unit, so "2 maal de
     *  looptijd(mnd)" assigned into a jr doel restates 2×24 mnd = 48 mnd -> 4 jr. Before, the unitless×unit-on-the-RIGHT
     *  degraded to Onbekend on BOTH back-ends (no restate, 48 stored) — a SHARED wrong value, so assert the ABSOLUTE
     *  spec value. An ATTRIBUTE left stays Onbekend (the TOKA tolerance, CasusTest). */
    @Test void phase_cu_maal_unitless_literal_is_spec_correct() {
        for (int i = 0; i < PHASE_CU_MAAL.length; i++) {
            Fixture f = PHASE_CU_MAAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_CU_MAAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §6.1.4/§4 a CROSS-UNIT begrenzing BOUND inside a CONSTRAINT now FIRES at parity (was a symmetric marker/skip): the
     *  min/max bound (mnd) restates EXACTLY into the basis unit (jr) — DRL herschaalConditie -> rsHerschaalExact, exec
     *  scalarWaarde's .herschaald — so a "min van 24 mnd" clamp compares against an exact 2 jr. The lossy single ratio gave
     *  1.99999999999999992, so the equality "== 2 jr" flipped to ONWAAR — a SHARED wrong decision, hence the ABSOLUTE
     *  assertion (differential-masks-shared-bugs). Closes the last cross-unit capability gap; the wf_5355bc06 review's
     *  exec-fires/DRL-markers divergence is now fire-vs-fire at parity. */
    @Test void phase_cross_unit_begrenzing_constraint_is_spec_correct() {
        for (int i = 0; i < PHASE_CU_BEGR_COND.length; i++) {
            Fixture f = PHASE_CU_BEGR_COND[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            assertFalse(DrlTranspiler.transpile(m).contains("/* TODO voorwaarde"),
                    () -> f.naam() + " DRL moet vuren (geen marker):\n" + DrlTranspiler.transpile(m));
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_CU_BEGR_COND_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.8 a bare SET operand ("de prijs van alle passagiers") inside arithmetic in a CONDITION must SKIP symmetrically
     *  with DRL's marker, NOT lower to a scalar read that reads `prijs` off the subject Vlucht (no such getter) and CRASH
     *  the whole session at fire time. The co-resident valid rule S MUST still fire in BOTH back-ends (no blast radius).
     *  Regression lock for the scalarWaarde a.set() guard (review wf_0acdef2b high-sev asymmetric-crash finding). */
    @Test void bare_set_operand_in_arithmetic_is_symmetric_marker_no_session_crash() {
        RegelSpraakModel m = Resolver.resolve(SET_IN_ARITH_SRC);
        assertTrue(m.errors().isEmpty(), () -> "moet resolven: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertFalse(em.overgeslagen().isEmpty(), () -> "de exec moet de SET-in-rekenkunde voorwaarde overslaan, kreeg: " + em.overgeslagen());
        assertTrue(DrlTranspiler.transpile(m).contains("/* TODO voorwaarde"),
                () -> "DRL moet de SET-in-rekenkunde voorwaarde ook markeren (symmetrie):\n" + DrlTranspiler.transpile(m));
        // firing must NOT crash, and the co-resident valid rule S must fire in BOTH back-ends (vertrektijd 50 > 0 -> vol).
        for (KieBase kb : List.of(drlBase(m), KieBaseBuilder.createKieBaseFromModel(em.model()))) {
            Vlucht v = new Vlucht(); v.setVertrektijd(new BigDecimal(50));
            KieSession s = sessie(kb); s.insert(v); s.fireAllRules(); s.dispose();
            assertEquals(Boolean.TRUE, v.isVol(), () -> "de co-residente geldige regel S moet vuren (geen sessie-crash door de SET-regel)");
            assertFalse(Boolean.TRUE.equals(v.isLaat()), () -> "de SET-regel R mag NIET vuren (symmetrische marker)");
        }
    }
    /** §5.3 / typeringen Tabel 38 a NON-numeriek (Tekst) GELIJK attr-vs-attr CONSISTENTIE now LOWERS in the exec (LIFT A):
     *  "de vertrek moet gelijk zijn aan de bestemming" reads the two Tekst attributes VALUE-equal (no toBd coercion, no
     *  session crash). vertrek="A" != bestemming="B" -> the GELIJK criterion is NOT satisfied -> a violation -> 1
     *  Inconsistentie, exactly as DRL. The co-resident vs-LITERAL rule S (vertrek == "A") still fires -> gemarkeerd. */
    @Test void tekst_attr_vs_attr_consistentie_lowers_and_flags() {
        RegelSpraakModel m = Resolver.resolve(TEKST_CONSISTENTIE_SRC);
        assertTrue(m.errors().isEmpty(), () -> "moet resolven: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "de exec moet de Tekst attr-vs-attr consistentie NIET meer overslaan, kreeg: " + em.overgeslagen());
        assertTrue(DrlTranspiler.transpile(m).contains("Inconsistentie"),
                () -> "DRL moet de consistentie wel flaggen:\n" + DrlTranspiler.transpile(m));
        // vertrek="A" != bestemming="B" -> GELIJK-criterium onwaar -> een violation in BEIDE back-ends (1 Inconsistentie).
        Route rDrl = new Route(); rDrl.setVertrek("A"); rDrl.setBestemming("B");
        Route rModel = new Route(); rModel.setVertrek("A"); rModel.setBestemming("B");
        KieSession sDrl = sessie(drlBase(m)); sDrl.insert(rDrl); sDrl.fireAllRules();
        long incDrl = sDrl.getObjects().stream().filter(o -> o instanceof Inconsistentie).count(); sDrl.dispose();
        KieSession sModel = KieBaseBuilder.createKieBaseFromModel(em.model()).newKieSession();
        sModel.insert(rModel); sModel.fireAllRules();   // must not throw
        long incModel = sModel.getObjects().stream().filter(o -> o instanceof Inconsistentie).count(); sModel.dispose();
        assertEquals(Boolean.TRUE, rModel.isGemarkeerd(), () -> "de co-residente regel S moet vuren");
        assertEquals(Boolean.TRUE, rDrl.isGemarkeerd(), () -> "regel S vuurt ook in DRL");
        assertEquals(1L, incDrl, () -> "DRL flagt de A!=B GELIJK-consistentie");
        assertEquals(1L, incModel, () -> "de exec LOWERT de Tekst-consistentie nu ook en flagt A!=B (LIFT A)");
        assertEquals(incDrl, incModel, () -> "back-ends DIVERGE op de Tekst attr-vs-attr consistentie");
    }
    /** §8.1.1 a comparison whose operand is an arithmetic/builtin EXPRESSION vs an attribute (not a literal) now fires in
     *  the exec (was a masked DRL-fires/exec-skips). Closed-form -> assert the ABSOLUTE spec value + parity, incl. a
     *  leeg operand (leeg -> 0) and a cross-unit expr (restated null-safely on both back-ends). */
    @Test void phase_expr_cmp_is_spec_correct() {
        for (int i = 0; i < PHASE_EXPR_CMP.length; i++) {
            Fixture f = PHASE_EXPR_CMP[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_EXPR_CMP_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.1 a NAVIGATED operand INSIDE an arithmetic comparison now fires at parity: an ABSENT to-one relation reads
     *  leeg -> leeg-as-0 in the +/- (§4.1 Tabel 3), NOT the mvel `!.` short-circuit-to-false the DRL used to emit (a
     *  latent bug that dropped the rest of the arithmetic). DrlTranspiler reads the leg via the null-safe rsRelGetal;
     *  the exec via navReader. Closed-form -> assert the ABSOLUTE spec value + parity (a shared short-circuit would FAIL
     *  the absent-relation row, which spec-fires). */
    @Test void phase_nav_expr_is_spec_correct() {
        for (int i = 0; i < PHASE_NAV_EXPR.length; i++) {
            Fixture f = PHASE_NAV_EXPR[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_NAV_EXPR_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.1 a NAVIGATED operand AND a single Parameter-GLOBAL in one arithmetic comparison now fires at parity: the
     *  exec voorwaarde global-binding branch binds the global as a pattern var AND sets valueSubject so the navigated read
     *  resolves (was a masked DRL-fires/exec-skips). Absent relation -> leeg-as-0, the global+rest still fire. Closed-form
     *  -> assert the ABSOLUTE spec value + parity. */
    @Test void phase_nav_global_is_spec_correct() {
        for (int i = 0; i < PHASE_NAV_GLOBAL.length; i++) {
            Fixture f = PHASE_NAV_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_NAV_GLOBAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.5.5/§5.6 a STANDALONE navigated comparison with an ABSENT relation reads the operand as leegwaarde then applies
     *  Tabel 33-44 — ONGELIJK one-leeg -> WAAR (Tabel 44), GELIJK both-leeg -> WAAR (Tabel 37), ordering/one-sided GELIJK
     *  -> onwaar. Closes a masked SHARED bug (mvel `!.` short-circuited to false for every operator; only ONGELIJK + GELIJK
     *  both-leeg were wrong). Assert the ABSOLUTE spec values + parity across the null-state matrix. */
    @Test void phase_nav_leeg_is_spec_correct() {
        for (int i = 0; i < PHASE_NAV_LEEG.length; i++) {
            Fixture f = PHASE_NAV_LEEG[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_NAV_LEEG_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §13.4.1 a MULTI-HOP to-one navigation chain ("de X van de Y van de Z") now fires as a consequence VALUE in both
     *  back-ends: the resolver threads the declared role hops as navigatiePad, DRL emits one null-guarded getter per
     *  hop, the exec composes typed getters with the same per-hop short-circuit. An intervening leeg relation or a
     *  leeg leaf reads as leeg (lege waarde, hoofdspec §5.5.5/§13.3.9); inside + the leeg then counts as 0 (§6.2
     *  Tabel 5). Covers 2 hops (possessive + definite spelling), 3 hops through a self-relation, the §5.5.3 own-type
     *  back-reference anchor, and a cross-unit leaf (§4 exact restatement). Closed-form -> assert the ABSOLUTE spec
     *  values + parity. */
    @Test void phase_keten_is_spec_correct() {
        for (int i = 0; i < PHASE_KETEN.length; i++) {
            Fixture f = PHASE_KETEN[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_KETEN_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §13.4.6 an objectcreatie attribute-INIT through a navigation (single hop AND a §13.4.1 chain) creates the
     *  object with the navigated value in BOTH back-ends — the exec used to lower toekenning values with
     *  valueSubject=null, silently skipping every navigated init while DRL fired it (found by the keten fire-both
     *  review). ABSOLUTE pin of the created object + parity. */
    @Test void phase_keten_objectcreatie_init_is_spec_correct() {
        for (int i = 0; i < PHASE_KETEN_OBJ.length; i++) {
            ObjFixture f = PHASE_KETEN_OBJ[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            List<String> viaDrl = fireCreated(drlBase(m), f);
            List<String> viaModel = fireCreated(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(List.of(PHASE_KETEN_OBJ_EXPECTED[i]), viaDrl, () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(viaDrl, viaModel, () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.3/§5.6 a DATUM EQUALITY (gelijk/ongelijk) of an own/navigated datum attribute vs a literal/datum-attr now fires
     *  in the exec (was an honest skip; DRL already fired it). An absent navigation / leeg datum reads leegwaarde, then
     *  GELIJK uses Tabel 38 (one/both-leeg -> onwaar — NOT Tabel 37's numeric WAAR) and ONGELIJK uses Tabel 44 (one-leeg ->
     *  WAAR). Assert the ABSOLUTE spec values + parity across the null-state matrix. */
    @Test void phase_datum_eq_is_spec_correct() {
        for (int i = 0; i < PHASE_DATUM_EQ.length; i++) {
            Fixture f = PHASE_DATUM_EQ[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_DATUM_EQ_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.3/§5.6/§8.1.1/§13.2.20 Datum-en-tijd (LocalDateTime) comparisons now FIRE in the exec at parity with DRL —
     *  ordering EERDER/LATER + equality GELIJK/ONGELIJK, own + navigated, vs a datum-tijd literal and a same-kind attr.
     *  The exec reads the raw Temporal (not `instanceof LocalDate`, which read a present datum-tijd as leeg -> equality
     *  was a honest skip, ordering MISFIRED to a wrong no-fire) and compares via datumVergelijkTemporal. A mixed
     *  Datum-in-dagen/Datum-en-tijd pair stays an honest skip (Tabel 36). Absolute spec values + parity. */
    @Test void phase_datumtijd_comparisons_are_spec_correct() {
        for (int i = 0; i < PHASE_DATUMTIJD.length; i++) {
            Fixture f = PHASE_DATUMTIJD[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_DATUMTIJD_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_datumtijd_consistentie_matches_drl_back_end() { runFoutDifferential("datum-tijd consistentie", PHASE_DATUMTIJD_CONS, 1); }
    /** §8.1.1 a comparison with the CONSTANT on the LEFT now fires in the exec (numeric + datum, ordering + equality) —
     *  was a DRL-fires/exec-skips (every leaf branch keyed on an attr/expr LHS). leafPredicate swaps operands + reverses
     *  the operator (omkeren). Absolute spec values + parity, incl. a leeg operand (Tabel 44 ONGELIJK one-leeg WAAR). */
    /** §8.1.1/§13.3.8 a datum comparison vs a Datum-in-dagen Parameter-GLOBAL (own + navigated, ordering + equality) now
     *  fires in the exec — was a DRL-fires/exec-skips. Absolute spec values + parity, incl. leegwaarde (Tabel 44). */
    /** §5.3/§13.3.8 a tekst equality (GELIJK/ONGELIJK) vs a Tekst Parameter-GLOBAL now fires in the exec — was a
     *  DRL-fires/exec-skips. Absolute spec values + parity, incl. a leeg operand (Tabel 38 / Tabel 44). */
    /** §8.1.1 a comparison with the Parameter-GLOBAL on the LEFT (numeric + datum + tekst) now fires in the exec — was a
     *  DRL-fires/exec-skips. voorwaarde() swaps operands + reverses the operator (omkeren). Absolute spec values + parity. */
    /** §8.1.1/§13.3.8 a Datum-en-tijd comparison vs a Datum-en-tijd Parameter-GLOBAL (ordering + equality) now fires in
     *  the exec — was a DRL-fires/exec-skips (the Datum-en-tijd parameter was registered untracked). Absolute + parity. */
    /** §5.6 Tabel 44 a numeric NAVIGATED ONGELIJK vs a Parameter-GLOBAL with an absent relation is WAAR (leeg != global).
     *  Regression lock for the nav-vs-global short-circuit fix (was rel==null -> false for ALL ops; widened by the
     *  global-on-LEFT normalisation). Both spellings; absolute spec values + parity. */
    @Test void phase_nav_global_ongelijk_is_spec_correct() {
        for (int i = 0; i < PHASE_NAV_GLOBAL_ONGELIJK.length; i++) {
            Fixture f = PHASE_NAV_GLOBAL_ONGELIJK[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_NAV_GLOBAL_ONGELIJK_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_datumtijd_vs_global_is_spec_correct() {
        for (int i = 0; i < PHASE_DATUMTIJD_GLOBAL.length; i++) {
            Fixture f = PHASE_DATUMTIJD_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_DATUMTIJD_GLOBAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_global_on_left_is_spec_correct() {
        for (int i = 0; i < PHASE_GLOBAL_LEFT.length; i++) {
            Fixture f = PHASE_GLOBAL_LEFT[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_GLOBAL_LEFT_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_tekst_vs_global_is_spec_correct() {
        for (int i = 0; i < PHASE_TEKST_GLOBAL.length; i++) {
            Fixture f = PHASE_TEKST_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_TEKST_GLOBAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_datum_vs_global_is_spec_correct() {
        for (int i = 0; i < PHASE_DATUM_GLOBAL.length; i++) {
            Fixture f = PHASE_DATUM_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_DATUM_GLOBAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_literal_on_left_comparison_is_spec_correct() {
        for (int i = 0; i < PHASE_LIT_LEFT.length; i++) {
            Fixture f = PHASE_LIT_LEFT[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_LIT_LEFT_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.1/§5.1 attr-vs-attr DATUM ORDERING (both Datum-in-dagen and Datum-en-tijd) now fires in the exec — was a
     *  DRL-fires/exec-skips (the ordering branch matched only a literal RHS). Absolute spec values + parity; a leeg
     *  operand is the ordering no-match in both. */
    @Test void phase_datum_ordering_attr_vs_attr_is_spec_correct() {
        for (int i = 0; i < PHASE_DATUM_ORDER.length; i++) {
            Fixture f = PHASE_DATUM_ORDER[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_DATUM_ORDER_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.3 a Datum-in-dagen attribute GELIJK a DATUM-EN-TIJD literal is a type mismatch. Formerly it resolved
     *  clean and only the exec back-end skipped it honestly (the RHS-literal DatumInDagen gate); the resolver
     *  now catches the mixed tijd-precisie at resolve time — the author gets a diagnostic instead of a
     *  silently skipped rule. The no-lossy-fire property is thereby enforced upstream of both back-ends. */
    @Test void mixed_datumindagen_vs_datumtijd_literal_is_een_resolver_fout() {
        RegelSpraakModel m = Resolver.resolve(MIXED_DATUMLIT_SRC);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("tijd-precisie")),
                () -> "een gemengde datum/datum-tijd gelijk moet een tijd-precisie-fout geven, kreeg: " + m.errors());
        // Defense-in-depth: the exec back-end's own sameDatumKind gate must ALSO keep skipping the mixed
        // comparison (no lossy date-part fire) — the resolver ERROR and the transpile-time skip are two
        // independent guards, and this keeps the downstream one exercised.
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertFalse(em.overgeslagen().isEmpty(),
                () -> "de exec moet de gemengde datum/datum-tijd vergelijking blijven overslaan: " + em.overgeslagen());
    }
    /** Regression: the exec model used to INFINITE-LOOP (hang) when a NAVIGATED-comparison rule was co-resident with a
     *  second modify-rule over the same subject (drools.update ping-pong; NO_LOOP blocks only self). The idempotent
     *  werkBij (update only on an actual value change) reaches a fixpoint. @Timeout turns a regression (hang) into a test
     *  FAILURE instead of a hung suite. Asserts BOTH back-ends terminate and fire the spec-correct kenmerken. */
    @org.junit.jupiter.api.Timeout(30)
    @Test void navigated_comparison_with_sibling_rule_terminates_no_hang() {
        RegelSpraakModel m = Resolver.resolve(HANG_SRC);
        assertTrue(m.errors().isEmpty(), () -> "moet resolven: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "beide regels moeten loweren: " + em.overgeslagen());
        for (KieBase kb : List.of(drlBase(m), KieBaseBuilder.createKieBaseFromModel(em.model()))) {
            Vlucht v = new Vlucht(); v.setVertrektijd(new BigDecimal(10));
            Passagier p = new Passagier(); p.setReis(v); p.setLeeftijd(new BigDecimal(10));
            KieSession s = sessie(kb); s.insert(v); s.insert(p); s.fireAllRules(); s.dispose();   // must TERMINATE
            assertEquals(Boolean.TRUE, p.isDuur(), () -> "regel A (vertrektijd 10 > 5) moet vuren");   // navigated comparison
            assertEquals(Boolean.TRUE, p.isKind(), () -> "regel B (leeftijd 10 < 18) moet vuren");      // own comparison
        }
    }
    /** Regression (the §9.7 verdeling tail of the cd19b87 navigated-loop family): TWO verdelingen writing DIFFERENT
     *  recipient attributes of the SAME objecttype (here aandeel + leeftijd of de Deelnemer) used to INFINITE-LOOP the
     *  exec model. Each share consequence joins via a BLACK-BOX reflective lambda (class-reactive → re-fires on every
     *  update of the recipient) and used to {@code drools.update} UNCONDITIONALLY, so the two rules ping-ponged forever
     *  (NO_LOOP blocks only self). Measured 1e6+ fires before the idempotent {@code werkBijReflectief} (update only on a
     *  real change). DRL escapes via mvel property reactivity (fires 6 = 3 recipients × 2 rules). @Timeout turns the
     *  hang into a FAILURE; asserts BOTH back-ends TERMINATE and converge to the SAME spec value (90 ÷ 3 = 30 each). */
    @org.junit.jupiter.api.Timeout(30)
    @Test void two_verdelingen_same_recipient_terminate_no_hang() {
        String src =
            "Objecttype de Pot\n    de totaal Numeriek (geheel getal);\n"
            + "Objecttype de Deelnemer\n    de aandeel Numeriek (geheel getal);\n    de leeftijd Numeriek (geheel getal);\n"
            + "Feittype Pot over deelnemers\n    de pot Pot\n    de deelnemer Deelnemer\nEén pot wordt verdeeld over meerdere deelnemers\n"
            + "Regel verdeel a\n    geldig altijd\n        De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.\n"
            + "Regel verdeel b\n    geldig altijd\n        De totaal van een pot wordt verdeeld over de leeftijd van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet resolven: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "beide verdelingen moeten loweren: " + em.overgeslagen());
        for (KieBase kb : List.of(drlBase(m), KieBaseBuilder.createKieBaseFromModel(em.model()))) {
            Pot p = new Pot(); p.setTotaal(new BigDecimal(90));
            Deelnemer d1 = deeln(p, null, null, null), d2 = deeln(p, null, null, null), d3 = deeln(p, null, null, null);
            KieSession s = sessie(kb);
            s.insert(p); s.insert(d1); s.insert(d2); s.insert(d3);
            s.fireAllRules(2_000_000);   // bounded: a regressed loop would hit the cap, then @Timeout fails the test
            s.dispose();
            for (Deelnemer d : List.of(d1, d2, d3)) {
                assertEquals(0, new BigDecimal(30).compareTo(d.getAandeel()), () -> "aandeel = 90/3 = 30");
                assertEquals(0, new BigDecimal(30).compareTo(d.getLeeftijd()), () -> "leeftijd = 90/3 = 30");
            }
        }
    }
    /** §5.6 a DATE-ARITHMETIC datum ONGELIJK (datumVergelijkTemporal) with BOTH operands leeg must be onwaar (Tabel 44),
     *  matching DRL's mvel `null != null` -> false. An old `|| (a==null && b==null)` clause wrongly fired the model true
     *  (a live divergence). Assert the ABSOLUTE spec values + parity. */
    @Test void phase_datearith_eq_is_spec_correct() {
        for (int i = 0; i < PHASE_DATEARITH_EQ.length; i++) {
            Fixture f = PHASE_DATEARITH_EQ[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_DATEARITH_EQ_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §5.1 a cross-unit comparison in a CONSTRAINT now fires null-safely (rsHerschaal, the mirror of the exec scale) —
     *  standalone AND in a counting subselectie. A LEEG operand restates to leeg -> no-match (no NPE) in both. A closed-
     *  form filtered fold -> assert the ABSOLUTE spec value (a shared crash/wrong-restatement would not pass this). */
    @Test void phase_crossunit_is_spec_correct() {
        for (int i = 0; i < PHASE_CROSSUNIT.length; i++) {
            Fixture f = PHASE_CROSSUNIT[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_CROSSUNIT_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §13.4.13.4 a COUNTING-quantifier compound subselectie with a SINGLE Parameter-global bullet, now FIRING in BOTH
     *  back-ends (DRL free-name in the bullet OR/count; exec a 2-var count-fold binding the one source-global). A
     *  closed-form filtered fold -> assert the ABSOLUTE spec value (a shared "global never bound / filter never applied"
     *  bug would pass a pure differential), the exec NOT skipping, and parity. Sweeps ten-minste-1/geen/precies/ten-hoogste. */
    @Test void phase_C_count_global_subselectie_is_spec_correct() {
        for (int i = 0; i < PHASE_C_COUNT_GLOBAL.length; i++) {
            Fixture f = PHASE_C_COUNT_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_C_COUNT_GLOBAL_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** back-end-agnostic resolver: the resolver no longer pre-cripples the IR to the exec count-fold's capability. A COUNTING
     *  compound the exec cannot bind (>1 distinct global; a global-reading NESTED bullet) now FIRES in DRL —
     *  the ABSOLUTE spec value asserted here — while the exec back-end records an honest skip. The divergence
     *  is EXPLICIT and enumerated: DRL manifest LOWERED, exec manifest SKIP; a runDifferential sweep hitting
     *  this shape needs a per-fixture allowlist entry. (Replaces the old symmetric-marker pin, which encoded
     *  back-end capability INSIDE the resolver — the IR-pre-crippling resolver-as-naming-authority removes.) */
    private void assertDrlVuurtExecSlaatOver(String src, Map<String, Object> globals, BigDecimal verwacht) {
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet resolven: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertFalse(em.overgeslagen().isEmpty(),
                () -> "de exec moet deze counting compound (nog) eerlijk overslaan, kreeg: " + em.overgeslagen());
        DrlTranspiler dt = DrlTranspiler.build(m);
        assertTrue(dt.dekking().stream().allMatch(Dekking::lowered),
                () -> "DRL moet deze counting compound loweren (resolver-as-naming-authority):\n" + dt.drl());
        KieSession s = sessie(drlBase(m));
        globals.forEach(s::setGlobal);
        List<Object> facts = countFacts().get();
        for (Object o : facts) s.insert(o);
        s.fireAllRules(); s.dispose();
        assertEquals(0, verwacht.compareTo(((Vlucht) facts.get(0)).getTotaalprijs()),
                () -> "DRL moet de absolute spec-waarde vuren, kreeg " + ((Vlucht) facts.get(0)).getTotaalprijs());
    }
    // ondergrens 18, bovengrens 50 over countFacts (prijs,leeftijd): (30,10) (70,20) (40,15) (25,30) —
    // leeftijd<18: pax1+pax3; prijs>50: pax2 -> som prijs = 30+70+40 = 140.
    @Test void counting_subselectie_met_twee_globals_vuurt_in_drl_exec_slaat_over() {
        assertDrlVuurtExecSlaatOver(COUNT_TWO_GLOBALS_SRC,
                Map.of("ondergrens", new BigDecimal(18), "bovengrens", new BigDecimal(50)), new BigDecimal(140));
    }
    /** A nested compound bullet that READS A GLOBAL: (minderjarig && leeftijd<18) -> pax1; prijs>50 -> pax2;
     *  union som = 30+70 = 100. */
    @Test void counting_subselectie_nested_met_global_vuurt_in_drl_exec_slaat_over() {
        assertDrlVuurtExecSlaatOver(COUNT_NESTED_GLOBAL_SRC, Map.of("grens", new BigDecimal(18)), new BigDecimal(100));
    }
    /** §13.4.14.6 TWO consecutive nested-compound bullets under a counting quantifier — the indentation-blind grammar
     *  absorbs the second into the first's ALLE, but the bullet COLUMNS re-nest cleanly, so the resolver rebuilds the
     *  intended SIBLINGS (depth-aware re-nesting) and BOTH back-ends FIRE the spec-correct OR/count (was a symmetric
     *  marker). Closed-form SOM -> ABSOLUTE spec value + parity (a symmetric mis-fold would pass a parity-only check). */
    @Test void phase_C_count_nested_twice_is_spec_correct() {
        for (int i = 0; i < PHASE_C_COUNT_NESTED_TWICE.length; i++) {
            Fixture f = PHASE_C_COUNT_NESTED_TWICE[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_C_COUNT_NESTED_TWICE_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §13.4.14.6 a nested compound bullet FOLLOWED BY a dedented sibling LEAF — the grammar absorbs the leaf into the
     *  nested ALLE, but the columns disambiguate, so re-nesting lifts it back to a SIBLING and BOTH back-ends FIRE the
     *  spec OR (was a symmetric marker; review wf_5235420c-fa7 had locked the old marker). ABSOLUTE SOM + parity. */
    @Test void phase_C_count_nested_then_leaf_is_spec_correct() {
        for (int i = 0; i < PHASE_C_COUNT_NESTED_THEN_LEAF.length; i++) {
            Fixture f = PHASE_C_COUNT_NESTED_THEN_LEAF[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_C_COUNT_NESTED_THEN_LEAF_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.5 + §5.15 + Tabel 16 NAVIGATED dagsoortcontrole "de vluchtdatum van zijn reis (geen) kerstdag is" (NDS-1):
     *  the resolver recovers the trailing "geen" absorbed into the navigation role and builds a NEGATED DAGSOORT
     *  predicaat; both back-ends FIRE. The ABSENT-relation row is the load-bearing assertion (a leegwaarde is nooit een
     *  dagsoort -> positive ONWAAR, negated WAAR); the differential masks it under parity, so assert the ABSOLUTE
     *  Tabel-16 value AND that the exec does NOT skip (the negated nav was previously a symmetric marker). */
    @Test void phase_NDS_navigated_dagsoort_is_spec_correct() {
        for (int i = 0; i < PHASE_NDS.length; i++) {
            Fixture f = PHASE_NDS[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_NDS_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    // ---- §8.1.5/§5.15/Tabel 57 a DATUM-EN-TIJD dagsoortcontrole IS spec-legal: the operand is day-granular, so both
    //      back-ends take its DATE PART (.toLocalDate()) for the §9.8 LocalDate predicate. OWN + NAVIGATED(negated) +
    //      present-kerstday / present-non-kerstday / ABSENT-relation. Was a symmetric marker (the LocalDateTime operand
    //      crashed the DRL rsDagsoort_<N>(LocalDate)); now FIRES at parity. Absolute Tabel-58 value + no exec skip. ----
    private static final String DTD_KERST =
        "Dagsoort de kerstdag (mv: kerstdagen)\n" +
        "Regel Kerstdag\n    geldig altijd\n        Een dag is een kerstdag\n" +
        "        indien de dag aan alle volgende voorwaarden voldoet:\n" +
        "        - de maand uit (de dag) is gelijk aan 12\n" +
        "        - de dag voldoet aan ten minste één van de volgende voorwaarden:\n" +
        "            .. de dag uit (de dag) is gelijk aan 25\n            .. de dag uit (de dag) is gelijk aan 26.\n";
    static final String DTD_NAV_NEG_SRC = DTD_KERST +
        "Objecttype de Passagier (mv: Passagiers) (bezield)\n    is duur kenmerk (bijvoeglijk);\n" +
        "Objecttype de Vlucht (mv: Vluchten)\n    het vertrekmoment Datum en tijd in millisecondes;\n" +
        "Feittype reis van passagier\n    de reis Vlucht\n    de passagier Passagier\nEén reis heeft meerdere passagiers\n" +
        "Regel Duur\n    geldig altijd\n        Een Passagier is duur indien het vertrekmoment van zijn reis geen kerstdag is.\n";
    static final String DTD_OWN_POS_SRC = DTD_KERST +
        "Objecttype de Vlucht (mv: Vluchten)\n    het vertrekmoment Datum en tijd in millisecondes;\n    is laat kenmerk (bijvoeglijk);\n" +
        "Regel Laat\n    geldig altijd\n        Een Vlucht is laat indien het vertrekmoment van de Vlucht een kerstdag is.\n";
    static Fixture dtdNavNeg(String naam, java.time.LocalDateTime vdt) {
        return new Fixture(naam, DTD_NAV_NEG_SRC,
                () -> { Passagier p = new Passagier();
                        if (vdt != null) { Vlucht v = new Vlucht(); v.setVertrekmoment(vdt); p.setReis(v); }
                        return List.of(p); },
                Map.of(), l -> ((Passagier) l.get(0)).isDuur());
    }
    static Fixture dtdOwnPos(String naam, java.time.LocalDateTime vdt) {
        return new Fixture(naam, DTD_OWN_POS_SRC,
                () -> { Vlucht v = new Vlucht(); v.setVertrekmoment(vdt); return List.of(v); },
                Map.of(), l -> ((Vlucht) l.get(0)).isLaat());
    }
    static final Fixture[] PHASE_DTD = {
        dtdNavNeg("DTD nav-neg: reis.vertrekmoment 25-12 10:30 IS kerstdag -> geen -> onwaar", java.time.LocalDateTime.of(2024, 12, 25, 10, 30, 0)),
        dtdNavNeg("DTD nav-neg: reis.vertrekmoment 24-12 geen kerstdag -> waar",                java.time.LocalDateTime.of(2024, 12, 24, 10, 30, 0)),
        dtdNavNeg("DTD nav-neg: ABSENT reis -> §5.15+Tabel16 geen kerstdag -> WAAR",            null),
        dtdOwnPos("DTD own-pos: vertrekmoment 26-12 23:59 IS kerstdag -> waar",                 java.time.LocalDateTime.of(2024, 12, 26, 23, 59, 0)),
        dtdOwnPos("DTD own-pos: vertrekmoment 24-12 00:00 geen kerstdag -> onwaar",             java.time.LocalDateTime.of(2024, 12, 24, 0, 0, 0)),
    };
    static final Boolean[] PHASE_DTD_EXPECTED = { Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE };
    /** §8.1.5/§5.15/Tabel 57: a Datum-en-tijd dagsoortcontrole fires (date part taken) in both back-ends; the DRL
     *  rsDagsoort_<N>(...toLocalDate()) COMPILES (was a whole-program rejection) and the exec asDag() mirrors it. */
    @Test void phase_datum_en_tijd_dagsoort_is_spec_correct() {
        for (int i = 0; i < PHASE_DTD.length; i++) {
            Fixture f = PHASE_DTD[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_DTD_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §13.4.13.4 a COUNTING-quantifier compound subselectie with a NESTED PURE-LEAF compound bullet now FIRES at parity
     *  (DRL conditieInline's nested &&; exec leafCompound folds the nested compound into ONE Predicate1). Closed-form SOM
     *  -> assert the ABSOLUTE spec value, the exec NOT skipping, and parity. A nested compound reading a global stays a
     *  marker (counting_subselectie_nested_met_global_is_symmetric_marker_in_both). */
    @Test void phase_C_count_nested_compound_is_spec_correct() {
        for (int i = 0; i < PHASE_C_COUNT_NESTED.length; i++) {
            Fixture f = PHASE_C_COUNT_NESTED[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_C_COUNT_NESTED_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §13.4.13.4 the COMPOUND subselectie (ALLE of comparison + kenmerk bullets over the element), now FIRING in BOTH
     *  back-ends (DRL `&&` source constraint; exec successive .expr). A closed-form filtered fold -> assert the ABSOLUTE
     *  spec value (a shared "filter never applied -> sums all" bug would pass a pure differential). */
    @Test void phase_C_compound_subselectie_is_spec_correct() {
        for (int i = 0; i < PHASE_C_COMPOUND.length; i++) {
            Fixture f = PHASE_C_COMPOUND[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_C_COMPOUND_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §13.4.13.4 the INLINE single-comparison predicate tail, in both registers plus a kenmerk tail — a
     *  closed-form filtered fold, so assert the ABSOLUTE spec value (a shared filter-dropped bug would pass a
     *  pure differential) as well as parity. */
    @Test void phase_INLINE_predicaat_is_spec_correct() {
        for (int i = 0; i < PHASE_INLINE.length; i++) {
            Fixture f = PHASE_INLINE[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_INLINE_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.9 / §13.4.15.66 "regelversie <X> is inconsistent" — an exists on the §9.5 Inconsistentie flag
     *  fact, absolute values in both back-ends (a shared exists-always-true bug would pass a pure differential). */
    @Test void phase_REGELVERSIE_is_spec_correct() {
        for (int i = 0; i < PHASE_REGELVERSIE.length; i++) {
            Fixture f = PHASE_REGELVERSIE[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_REGELVERSIE_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.9 "is gevuurd" has no observable runtime record and an UNKNOWN regelversie would be a
     *  never-matching exists — both decline SYMMETRICALLY (DRL marker, exec skip), pinned so neither
     *  back-end ever silently lowers a never-firing or wrongly-firing shape (docs/grammar-divergences.md). */
    @Test void regelversie_gevuurd_en_onbekende_naam_blijven_symmetrische_markers() {
        for (String vorm : new String[]{
                "regelversie controleer de prijs (altijd) is gevuurd",     // no runtime firing record
                "regelversie bestaat niet (altijd) is inconsistent"}) {    // unknown producer name
            RegelSpraakModel m = Resolver.resolve(REGELVERSIE_HDR
                    + "Regel duur\n    geldig altijd\n        Een Passagier is duur indien " + vorm + ".\n");
            assertTrue(m.errors().isEmpty(), () -> vorm + " moet resolven: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertFalse(em.overgeslagen().isEmpty(), () -> "de exec moet '" + vorm + "' overslaan, kreeg: " + em.overgeslagen());
            assertTrue(DrlTranspiler.transpile(m).contains("/* TODO regelpredicaat"),
                    () -> "DRL moet '" + vorm + "' markeren (symmetrie):\n" + DrlTranspiler.transpile(m));
        }
    }

    // ---- §8.1.9 / §13.4.15.66 regelpredicaat "regelversie <X> (altijd) is gevuurd": an exists-join on the
    //      RuleFired control fact the referenced main-regel() rule inserts in its consequence. §8.1.9 (main spec
    //      ~line 3433): "gevuurd" = "of er aan de voorwaarden in het voorwaardendeel van de regel is voldaan" —
    //      the named rule's voorwaardendeel held and its consequence ran. KEYING IS PER-RULE (no onderwerp): the
    //      flag is existential over the RULE, so when A has fired for ANY subject, B fires for ALL its subjects
    //      (exactly like the sibling "is inconsistent"). A = a kenmerktoekenning whose consequence marks itself;
    //      B reads the marker. Absolute values on the oracle (not just parity — a shared always-true exists would
    //      pass a differential) plus DRL==exec parity plus NO exec skip. ----
    static final String GEVUURD_HDR =
            "Objecttype de Passagier\n    de leeftijd Numeriek (geheel getal);\n    is minderjarig kenmerk (bijvoeglijk);\n    is duur kenmerk (bijvoeglijk);\n"
          + "Regel bepaal minderjarig\n    geldig altijd\n        Een Passagier is minderjarig indien zijn leeftijd kleiner is dan 18.\n"
          + "Regel duur\n    geldig altijd\n        Een Passagier is duur indien regelversie bepaal minderjarig (altijd) is gevuurd.\n";
    static final Fixture[] PHASE_GEVUURD = {
        // (a) A fires (leeftijd<18 holds) -> RuleFired inserted -> B fires for its subject.
        new Fixture("is gevuurd: A vuurt -> de conditie geldt (B vuurt)", GEVUURD_HDR,
            () -> List.of(passagier(p -> p.setLeeftijd(new BigDecimal(10)))),
            Map.of(), l -> ((Passagier) l.get(0)).isDuur()),
        // (b) A does NOT fire (leeftijd>=18) -> no RuleFired -> B does not fire.
        new Fixture("is gevuurd: A vuurt niet -> de conditie geldt niet (B vuurt niet)", GEVUURD_HDR,
            () -> List.of(passagier(p -> p.setLeeftijd(new BigDecimal(30)))),
            Map.of(), l -> ((Passagier) l.get(0)).isDuur()),
        // (c) PER-RULE existential + ordering: two passengers, only the FIRST triggers A. RuleFired is keyed on the
        //     RULE (not the subject), so B fires for the SECOND (non-triggering) passenger too. Drools re-activates
        //     B when RuleFired lands mid-session (one fireAllRules) even if B was evaluated before A fired.
        new Fixture("is gevuurd: per-regel bereik + volgorde (B vuurt voor de niet-triggerende passagier)", GEVUURD_HDR,
            () -> List.of(passagier(p -> p.setLeeftijd(new BigDecimal(10))),   // triggert A
                          passagier(p -> p.setLeeftijd(new BigDecimal(30)))),  // triggert A niet
            Map.of(), l -> ((Passagier) l.get(1)).isDuur()),
    };
    static final Object[] PHASE_GEVUURD_EXPECTED = { Boolean.TRUE, Boolean.FALSE, Boolean.TRUE };

    /** §8.1.9 "is gevuurd" -> exists RuleFired, absolute values in both back-ends (a shared always-true exists
     *  would pass a pure differential); (a) A fires -> B fires, (b) A does not fire -> B does not, (c) per-rule
     *  key + mid-session re-activation (B fires for a subject that never triggered A, and even if evaluated first). */
    @Test void phase_GEVUURD_is_spec_correct() {
        for (int i = 0; i < PHASE_GEVUURD.length; i++) {
            Fixture f = PHASE_GEVUURD[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_GEVUURD_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.9 (d) a "is gevuurd" reference to an UN-INSTRUMENTED / UNKNOWN regelversie stays a SYMMETRIC decline:
     *  DRL markers (/* TODO regelpredicaat *&#47;), exec SKIPS the rule — never a never-matching exists. "controleer
     *  de prijs" is a CONSISTENTIEregel (its "result" is the Inconsistentie channel that "is inconsistent" reads;
     *  "gevuurd" over it is semantically murky), "bestaat niet" is unknown. Pinned so neither back-end silently
     *  lowers a never-firing shape. */
    @Test void phase_GEVUURD_un_instrumented_blijft_symmetrische_marker() {
        for (String vorm : new String[]{
                "regelversie controleer de prijs (altijd) is gevuurd",   // consistentie -> not a RuleFired producer
                "regelversie bestaat niet (altijd) is gevuurd"}) {       // unknown rule name
            RegelSpraakModel m = Resolver.resolve(REGELVERSIE_HDR
                    + "Regel duur\n    geldig altijd\n        Een Passagier is duur indien " + vorm + ".\n");
            assertTrue(m.errors().isEmpty(), () -> vorm + " moet resolven: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertFalse(em.overgeslagen().isEmpty(), () -> "de exec moet '" + vorm + "' overslaan, kreeg: " + em.overgeslagen());
            String drl = DrlTranspiler.transpile(m);
            assertTrue(drl.contains("/* TODO regelpredicaat"),
                    () -> "DRL moet '" + vorm + "' markeren (symmetrie):\n" + drl);
            assertFalse(drl.contains("RuleFired"), () -> "geen RuleFired voor een niet-geïnstrumenteerde '" + vorm + "':\n" + drl);
        }
    }
    /** §5.6 the spec's worked-example RHS "die kleiner zijn dan de volwassenleeftijd" — a comparison subselectie whose RHS
     *  is a Parameter-global, now FIRING in BOTH back-ends (DRL reads the global free name in the accumulate source; the
     *  exec binds it on the source pattern). A closed-form filtered fold -> assert the ABSOLUTE spec value AND parity. */
    @Test void phase_C_param_rhs_subselectie_is_spec_correct() {
        for (int i = 0; i < PHASE_C_PARAM_RHS.length; i++) {
            Fixture f = PHASE_C_PARAM_RHS[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_C_PARAM_RHS_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_D_consistentie_matches_drl_back_end()        { runFoutDifferential("Phase D", PHASE_D, 12); }
    @Test void phase_TEKST_CONS_matches_drl_back_end()            { runFoutDifferential("Phase TEKST-CONS", PHASE_TEKST_CONS, 2); }
    /** LIFT A: a §9.5 NON-numeriek (Tekst) attr-vs-attr GELIJK/ONGELIJK consistentie — the ABSOLUTE flagged SET (the
     *  toka-30 "geen rondvlucht" shape), not just DRL==exec parity. A consistentie fixture that tests only the satisfied
     *  case passes VACUOUSLY even when the rule is silently skipped, so both the VIOLATING (Inconsistentie-inserting) and
     *  the satisfied cells are pinned against the hand-computed Tabel 38/44 truth AND the DRL oracle. */
    @Test void phase_TEKST_CONS_consistentie_is_spec_correct() {
        // ONGELIJK: only the A==A rondvlucht and the both-leeg are violations.
        FoutFixture ong = PHASE_TEKST_CONS[0];
        RegelSpraakModel mo = Resolver.resolve(ong.src());
        assertTrue(mo.errors().isEmpty(), () -> "resolver-fout: " + mo.errors());
        ExecModelTranspiler eo = ExecModelTranspiler.build(mo);
        assertTrue(eo.overgeslagen().isEmpty(), () -> "de exec moet de ONGELIJK Tekst-consistentie lowern, kreeg: " + eo.overgeslagen());
        List<String> verwachtOng = List.of("geen rondvlucht/v=A,b=A", "geen rondvlucht/v=null,b=null");
        List<String> ongDrl   = fireFout(drlBase(mo), ong);
        List<String> ongModel = fireFout(KieBaseBuilder.createKieBaseFromModel(eo.model()), ong);
        assertEquals(verwachtOng, ongDrl,   "DRL (oracle) wrong vs spec: ONGELIJK geschonden door A==A en both-leeg");
        assertEquals(ongDrl, ongModel,      "back-ends DIVERGE op de ONGELIJK Tekst-consistentie");

        // GELIJK: everything EXCEPT A==A is a violation (mismatch, one-leeg, both-leeg all onwaar/fout).
        FoutFixture gel = PHASE_TEKST_CONS[1];
        RegelSpraakModel mg = Resolver.resolve(gel.src());
        assertTrue(mg.errors().isEmpty(), () -> "resolver-fout: " + mg.errors());
        ExecModelTranspiler eg = ExecModelTranspiler.build(mg);
        assertTrue(eg.overgeslagen().isEmpty(), () -> "de exec moet de GELIJK Tekst-consistentie lowern, kreeg: " + eg.overgeslagen());
        List<String> verwachtGel = List.of("zelfde plek/v=A,b=B", "zelfde plek/v=A,b=null", "zelfde plek/v=null,b=null");
        List<String> gelDrl   = fireFout(drlBase(mg), gel);
        List<String> gelModel = fireFout(KieBaseBuilder.createKieBaseFromModel(eg.model()), gel);
        assertEquals(verwachtGel, gelDrl,   "DRL (oracle) wrong vs spec: GELIJK geschonden tenzij beide gevuld en gelijk");
        assertEquals(gelDrl, gelModel,      "back-ends DIVERGE op de GELIJK Tekst-consistentie");
    }

    /** §5.7/§8.1.6 tuple (concatenatie) uniciteit — ABSOLUTE spec outcome, not just DRL==exec parity (a shared bug in the
     *  tuple key would pass a parity-only check). "de concatenatie van luchthaven en vertrektijd" keys on the PAIR: only the
     *  two Vluchten that share BOTH luchthaven AND vertrektijd are inconsistent; a single-component match is a distinct
     *  tuple and a null component leaves the identity unestablished (§8.1.6), so neither is flagged. The DRL back-end is the
     *  spec oracle here (asserted against the hand-computed set); the exec model must agree. */
    @Test void phase_D_tuple_uniciteit_flags_only_full_pair_duplicates() {
        FoutFixture f = null;
        for (FoutFixture cand : PHASE_D) if (cand.naam().contains("concatenatie tuple")) f = cand;
        assertNotNull(f, "de tuple-uniciteit fixture ontbreekt in PHASE_D");
        final FoutFixture fx = f;
        RegelSpraakModel m = Resolver.resolve(fx.src());
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "model overgeslagen: " + em.overgeslagen());
        // exactly the (1,800) pair is a duplicate; (1,900)/(2,800)/the two (3,null) are not.
        List<String> verwacht = List.of("vlucht uniek/lh=1,tijd=800", "vlucht uniek/lh=1,tijd=800");
        List<String> viaDrl   = fireFout(drlBase(m), fx);
        List<String> viaModel = fireFout(KieBaseBuilder.createKieBaseFromModel(em.model()), fx);
        assertEquals(verwacht, viaDrl,   "DRL (oracle) wrong vs spec: alleen het volledige (luchthaven,vertrektijd)-paar is niet-uniek");
        assertEquals(viaDrl, viaModel,   "back-ends DIVERGE op de tuple-uniciteit");
    }

    /** §9.5/§13.4.8 compound (ALLE) consistentie — ABSOLUTE spec outcome. "moet voldoen aan alle volgende criteria" is
     *  inconsistent when NOT all criteria hold (§9.5: "Als niet aan één van deze criteria voldaan wordt, dan zal de regel
     *  inconsistent opleveren") — the OR of the per-criterion violations. Both bullets hold -> consistent; either bullet
     *  fails (or a leeg operand makes an ordering onwaar) -> flagged. DRL is the oracle; the exec model must agree. */
    @Test void phase_D_compound_consistentie_fires_on_any_failing_criterion() {
        FoutFixture f = null;
        for (FoutFixture cand : PHASE_D) if (cand.naam().contains("compound ALLE")) f = cand;
        assertNotNull(f, "de compound-consistentie fixture ontbreekt in PHASE_D");
        final FoutFixture fx = f;
        RegelSpraakModel m = Resolver.resolve(fx.src());
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> "model overgeslagen: " + em.overgeslagen());
        // binnen (50, both hold) NOT flagged; teHoog (150>limiet), teLaag (-10<minimum), leeg (saldo null) ALL flagged.
        List<String> verwacht = List.of("grenzen/saldo=-10", "grenzen/saldo=150", "grenzen/saldo=null");
        List<String> viaDrl   = fireFout(drlBase(m), fx);
        List<String> viaModel = fireFout(KieBaseBuilder.createKieBaseFromModel(em.model()), fx);
        assertEquals(verwacht, viaDrl,   "DRL (oracle) wrong vs spec: inconsistent zodra één criterium faalt");
        assertEquals(viaDrl, viaModel,   "back-ends DIVERGE op de samengestelde consistentie");
    }
    @Test void phase_H_consistentie_matches_drl_back_end()        { runFoutDifferential("Phase H fout", PHASE_H_FOUT, 11); }
    /** §13.4.8.12/.14 "verenigd met" over NINE sources — past the exec-model consequence's 9-variable bind ceiling
     *  ([subject]+[9 per-source counts] = 10 vars). The DRL back-end sums the counts as a string and has no ceiling;
     *  the exec back-end now FOLDS the per-source counts into one running-total variable so the consequence binds only
     *  [subject, total], general over any source count. Before this lowering the exec back-end SKIPPED ("te veel
     *  bronnen"); now it FIRES and must agree with the oracle. The value 7 appears in the 1st source (leeftijd) AND the
     *  9th (bedrag) -> both flagged; the collision spanning the LAST source proves the fold counts every source. */
    @Test void phase_H_uniciteit_unie_negen_bronnen_gevouwen() {
        String src = """
            Objecttype de Persoon (mv: Personen)
                de leeftijd Numeriek (geheel getal);
            Objecttype de Meting (mv: Metingen)
                de uitkomst Numeriek (geheel getal);
            Objecttype de Konto (mv: Kontos)
                de saldo Numeriek (geheel getal);
            Objecttype de Vlucht (mv: Vluchten)
                de luchthaven Numeriek (geheel getal);
            Objecttype de Pot (mv: Potten)
                de totaal Numeriek (geheel getal);
            Objecttype de Schijf (mv: Schijven)
                de afstand Numeriek (geheel getal);
            Objecttype de Tarief (mv: Tarieven)
                de grens Numeriek (geheel getal);
            Objecttype de Heffing (mv: Heffingen)
                de prijs Numeriek (geheel getal);
            Objecttype de Factuur (mv: Facturen)
                de bedrag Numeriek (geheel getal);
            Regel uniek
                geldig altijd
                    De leeftijd van alle Personen verenigd met de uitkomst van alle Metingen verenigd met de saldo van alle Kontos verenigd met de luchthaven van alle Vluchten verenigd met de totaal van alle Potten verenigd met de afstand van alle Schijven verenigd met de grens van alle Tarieven verenigd met de prijs van alle Heffingen verenigd met de bedrag van alle Facturen moeten uniek zijn.
            """;
        FoutFixture fx = new FoutFixture("uniciteit-unie: 9 bronnen — duplicaat over de negende bron", src,
            () -> {
                Persoon p = new Persoon(); p.setLeeftijd(new BigDecimal(7));            // 1e bron
                Meting me = new Meting(); me.setUitkomst(new BigDecimal(1));
                Konto k = new Konto(); k.setSaldo(new BigDecimal(2));
                Vlucht vl = new Vlucht(); vl.setLuchthaven(new BigDecimal(3));
                Pot po = new Pot(); po.setTotaal(new BigDecimal(4));
                io.github.yhavinga.regelspraak.facts.Schijf sc = new io.github.yhavinga.regelspraak.facts.Schijf(); sc.setAfstand(new BigDecimal(5));
                io.github.yhavinga.regelspraak.facts.Tarief ta = new io.github.yhavinga.regelspraak.facts.Tarief(); ta.setGrens(new BigDecimal(6));
                io.github.yhavinga.regelspraak.facts.Heffing he = new io.github.yhavinga.regelspraak.facts.Heffing(); he.setPrijs(new BigDecimal(8));
                io.github.yhavinga.regelspraak.facts.Factuur fa = new io.github.yhavinga.regelspraak.facts.Factuur(); fa.setBedrag(new BigDecimal(7));  // 9e bron: botst met 7
                return List.of(p, me, k, vl, po, sc, ta, he, fa);
            },
            o -> {
                if (o instanceof Persoon x) return "Persoon:" + x.getLeeftijd();
                if (o instanceof io.github.yhavinga.regelspraak.facts.Factuur x) return "Factuur:" + x.getBedrag();
                if (o instanceof Meting x) return "Meting:" + x.getUitkomst();
                if (o instanceof Konto x) return "Konto:" + x.getSaldo();
                if (o instanceof Vlucht x) return "Vlucht:" + x.getLuchthaven();
                if (o instanceof Pot x) return "Pot:" + x.getTotaal();
                if (o instanceof io.github.yhavinga.regelspraak.facts.Schijf x) return "Schijf:" + x.getAfstand();
                if (o instanceof io.github.yhavinga.regelspraak.facts.Tarief x) return "Tarief:" + x.getGrens();
                return "Heffing:" + ((io.github.yhavinga.regelspraak.facts.Heffing) o).getPrijs();
            });
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(),
                () -> "9-bronnen 'verenigd met' moet nu LOWEREN, geen 'te veel bronnen' skip: " + em.overgeslagen());
        List<String> verwacht = List.of("uniek/Factuur:7", "uniek/Persoon:7");
        List<String> viaDrl   = fireFout(drlBase(m), fx);
        List<String> viaModel = fireFout(KieBaseBuilder.createKieBaseFromModel(em.model()), fx);
        assertEquals(verwacht, viaDrl,   "DRL (oracle) wrong vs spec: alleen de over de 9e bron botsende waarde 7 is niet-uniek");
        assertEquals(viaDrl, viaModel,   "back-ends DIVERGE op de 9-bronnen (gevouwen) uniciteit-unie");
    }
    @Test void phase_div_value_matches_drl_back_end()             { runDifferential("Phase DIV", PHASE_DIV, 2); }
    @Test void phase_arith_value_matches_drl_back_end()           { runDifferential("Phase ARITH", PHASE_ARITH, 3); }
    @Test void phase_datum_arithmetic_matches_drl_back_end()      { runDifferential("Phase DATUM", PHASE_DATUM, 16); }
    @Test void phase_dagsoort_controle_matches_drl_back_end()     { runDifferential("Phase DAGSOORT", PHASE_DAGSOORT, 5); }
    @Test void phase_dagsoort_negatie_matches_drl_back_end()      { runDifferential("Phase DAGSOORT NEG", PHASE_DAGSOORT_NEG, 6); }
    /** §8.1.5 + Tabel 16 "is geen <dagsoort>" is a closed-form (¬dagsoort); assert the ABSOLUTE spec values (incl. §5.15:
     *  a leeg datum IS "geen <dagsoort>" = WAAR) on the oracle AND back-end parity. */
    @Test void phase_DAGSOORT_NEG_is_spec_correct() {
        for (int i = 0; i < PHASE_DAGSOORT_NEG.length; i++) {
            Fixture f = PHASE_DAGSOORT_NEG[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_DAGSOORT_NEG_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_dagsoort_duur_matches_drl_back_end()         { runDifferential("Phase DAGSOORT DUUR", PHASE_DAGSOORT_DUUR, 5); }
    /** §6.11 + §4.1/§4.2 Tabel 2/5 rule 2: "datum-EN-tijd ± N <dagsoort>" now FIRES in both back-ends — step over the
     *  DATE part and re-attach the time-of-day (was a symmetric marker). Closed-form -> assert the ABSOLUTE datum-tijd
     *  (time-of-day preserved, X-never-counts, +0, leeg, MIN, year-boundary) + DRL==exec parity + no exec skip. */
    @Test void phase_dagsoort_duur_datum_en_tijd_is_spec_correct() {
        for (int i = 0; i < PHASE_DAGSOORT_DUUR_DTD.length; i++) {
            Fixture f = PHASE_DAGSOORT_DUUR_DTD[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_DAGSOORT_DUUR_DTD_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_dagsoort_tijdsduur_matches_drl_back_end()    { runDifferential("Phase DAGSOORT TDS", PHASE_DAGSOORT_TDS, 6); }
    /** §6.10 + typeringen §4.16 L676: "de [absolute] tijdsduur van A tot B in hele <dagsoort>" over datum-EN-tijd operands
     *  now FIRES in both back-ends — count over the DATE parts (was a symmetric marker). Closed-form -> assert the
     *  ABSOLUTE count (time-of-day irrelevant, signed, absolute, leeg) + DRL==exec parity + no exec skip. */
    @Test void phase_dagsoort_tijdsduur_datum_en_tijd_is_spec_correct() {
        for (int i = 0; i < PHASE_DAGSOORT_TDS_DTD.length; i++) {
            Fixture f = PHASE_DAGSOORT_TDS_DTD[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_DAGSOORT_TDS_DTD_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    @Test void phase_dagsoort_cross_en_datearith_matches_drl_back_end() { runDifferential("Phase DAGSOORT X", PHASE_DAGSOORT_X, 5); }
    @Test void phase_dagsoort_over_global_matches_drl_back_end()        { runDifferential("Phase DAGSOORT GLOBAL", PHASE_DAGSOORT_GLOBAL, 5); }
    /** §8.1.5 over a global-reading datum operand is a closed-form transform (rsDatumReken + the §9.8 dagsoort
     *  predicate); assert the ABSOLUTE spec value on the oracle AND that the exec back-end (which binds the global as a
     *  pattern variable) agrees — a shared bug (e.g. the global never wired -> null duration) would pass a pure differential. */
    @Test void phase_DAGSOORT_GLOBAL_is_spec_correct() {
        for (int i = 0; i < PHASE_DAGSOORT_GLOBAL.length; i++) {
            Fixture f = PHASE_DAGSOORT_GLOBAL[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_DAGSOORT_GLOBAL_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    /** §8.1.5 over a BARE Datum-PARAMETER operand ("indien de peildatum een kerstdag is", peildatum a Datum parameter):
     *  DRL declares a LocalDate global and FIRES; the exec model cannot bind a date-typed global through the BigDecimal
     *  WaardeFn path, so it must HONESTLY SKIP (overgeslagen) — NOT crash with "Illegal class for global" (the review's
     *  blocker). Locks the global-registration fix (a Datum parameter is now a Global<LocalDate>, so setGlobal accepts a
     *  date) AND the numeric-only gate (the date global is not in `globals`, so the dagsoort-over-global branch skips it).
     *  A documented asymmetry (DRL fires, model skips), recorded in overgeslagen so the differential excludes it. */
    @Test void phase_dagsoort_bare_datum_param_global_is_honest_skip_not_crash() {
        String src = """
            Parameter de peildatum : Datum in dagen
            Dagsoort de kerstdag (mv: kerstdagen)
            Regel Kerstdag
                geldig altijd
                    Een dag is een kerstdag
                    indien de dag aan alle volgende voorwaarden voldoet:
                    - de maand uit (de dag) is gelijk aan 12
                    - de dag voldoet aan ten minste één van de volgende voorwaarden:
                        .. de dag uit (de dag) is gelijk aan 25
                        .. de dag uit (de dag) is gelijk aan 26.
            Objecttype de Vlucht
                de luchthaven Numeriek (geheel getal);
                is laat kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Vlucht is laat indien de peildatum een kerstdag is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().stream().anyMatch(s -> s.contains("R")),
                () -> "expected rule R recorded as an honest skip (a date-typed global is not bound), got overgeslagen=" + em.overgeslagen());
        // the model KieBase builds and setGlobal of a LocalDate does NOT crash (the global is now declared LocalDate);
        // the skipped rule leaves laat=false, while the DRL oracle FIRES laat=true (a documented, recorded asymmetry).
        KieBase mB = KieBaseBuilder.createKieBaseFromModel(em.model());
        KieSession ms = mB.newKieSession();
        ms.setGlobal("peildatum", java.time.LocalDate.of(2024, 12, 25));   // must NOT throw "Illegal class for global"
        Vlucht vm = new Vlucht(); ms.insert(vm); ms.fireAllRules(); ms.dispose();
        assertFalse(vm.isLaat(), "the model honestly skips the bare-date-param dagsoort rule (laat stays false)");
        KieSession ds = sessie(drlBase(m));
        ds.setGlobal("peildatum", java.time.LocalDate.of(2024, 12, 25));
        Vlucht vd = new Vlucht(); ds.insert(vd); ds.fireAllRules(); ds.dispose();
        assertTrue(vd.isLaat(), "the DRL oracle fires the bare-date-param dagsoort rule (25-12 is a kerstdag)");
    }
    @Test void phase_tds_general_value_matches_drl_back_end()      { runDifferential("Phase TDS", PHASE_TDS, 4); }
    @Test void phase_tds_general_condition_matches_drl_back_end()  { runDifferential("Phase TDS cond", PHASE_TDS_COND, 2); }
    /** §6.10 general tijdsduur is a closed-form transform (ChronoUnit.between via rsTijdsduur, ported byte-identical to
     *  the DRL function body); assert the ABSOLUTE spec values (signed / absolute / 0 / leeg) on the oracle AND that the
     *  exec back-end — newly wired off the dagsoort-only path — agrees. A shared bug passes a pure differential. */
    @Test void phase_TDS_general_is_spec_correct() {
        for (int i = 0; i < PHASE_TDS.length; i++) {
            Fixture f = PHASE_TDS[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_TDS_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
        for (int i = 0; i < PHASE_TDS_COND.length; i++) {
            Fixture f = PHASE_TDS_COND[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(PHASE_TDS_COND_EXPECTED[i], eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }
    // Phase E carries the ONE principled non-LOWERED pair: ">1 verdeling op hetzelfde doel" is agenda-order-
    // dependent (RegelSpraak defines no value for two conflicting distributions), so the exec back-end
    // deliberately SKIPs both writers while the DRL back-end lowers them — allowlisted per construct, with reason.
    @Test void phase_E_verdeling_shares_match_drl_back_end()       { runDifferential("Phase E", PHASE_E, 9, Map.of(
        "verdeling: twee verdelingen op hetzelfde doel -> honest skip (geen hang) :: verdeling: verdeel een",
            "multi-writer doel-attribuut: DRL lowert beide verdelingen (agenda-order-afhankelijke uitkomst), exec skipt principieel",
        "verdeling: twee verdelingen op hetzelfde doel -> honest skip (geen hang) :: verdeling: verdeel twee",
            "idem — de tweede schrijver van hetzelfde doel-attribuut")); }
    @Test void phase_E_verdeling_fouten_match_drl_back_end()       { runFoutDifferential("Phase E fout", PHASE_E_FOUT, 2); }
    // NB: a corpus-level (whole-TOKA) manifest diff is not possible yet — ExecModelTranspiler.build
    // resolves fact-bean getters EAGERLY and fail-fasts on the missing TOKA beans (IllegalState "geen
    // getter voor 'leeftijd'"). The DRL-side TOKA scoreboard lives in TokaDekkingTest; the two-back-end
    // corpus diff becomes possible once the fact-model contract's FactModelGenerator can supply generated beans.

    // ---- Phase D: §9.5/§13.4.8 consistentieregel — the VIOLATION inserts an Inconsistentie. The observable is the
    //      SET of flagged subjects (not a subject field), so a FoutFixture maps each inserted Inconsistentie to a
    //      stable "regel/<subject-key>" string the two back-ends can compare across their fresh fact instances. ----
    record FoutFixture(String naam, String src, Supplier<List<Object>> facts, Function<Object, String> subjectKey,
                       Map<String, Object> globals) {
        FoutFixture(String naam, String src, Supplier<List<Object>> facts, Function<Object, String> subjectKey) {
            this(naam, src, facts, subjectKey, Map.of());
        }
    }
    static final FoutFixture[] PHASE_D = {
        // own-attribute equality assertion across CONVERTIBLE units: grensjr(jr) gelijk aan duurmnd(mnd). 2 jr == 24
        // mnd is consistent (NOT flagged); 2 jr != 25 mnd is a real violation (flagged). Exercises flip + restatement.
        new FoutFixture("consistentie: cross-unit own-attr equality",
            """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Meting
                de duurmnd Numeriek (geheel getal) met eenheid mnd;
                de grensjr Numeriek (geheel getal) met eenheid jr;
            Regel gelijkcheck
                geldig altijd
                    De grensjr van een Meting moet gelijk zijn aan de duurmnd van een Meting.
            """,
            () -> {
                Meting gelijk = new Meting(); gelijk.setGrensjr(new BigDecimal(2)); gelijk.setDuurmnd(new BigDecimal(24));
                Meting anders = new Meting(); anders.setGrensjr(new BigDecimal(2)); anders.setDuurmnd(new BigDecimal(25));
                return List.of(gelijk, anders);
            },
            o -> "duurmnd=" + ((Meting) o).getDuurmnd()),   // distinguishes the two Metingen

        // ordering assertion vs a literal: "de uitkomst moet groter zijn dan 0". A leeg (null) operand is ALSO a
        // violation (typeringen: an ordering comparison with an empty operand is onwaar -> inconsistent).
        new FoutFixture("consistentie: ordering vs literal (null is a violation)",
            """
            Objecttype de Meting
                de uitkomst Numeriek (geheel getal);
            Regel positief
                geldig altijd
                    De uitkomst van een Meting moet groter zijn dan 0.
            """,
            () -> {
                Meting pos = new Meting(); pos.setUitkomst(new BigDecimal(5));    // 5 > 0 -> consistent
                Meting neg = new Meting(); neg.setUitkomst(new BigDecimal(-3));   // -3 > 0 false -> flagged
                Meting leeg = new Meting();                                       // uitkomst null -> flagged
                return List.of(pos, neg, leeg);
            },
            o -> "uitkomst=" + ((Meting) o).getUitkomst()),

        // §13.4.8 COMPOUND (ALLE) assertion: a Konto is inconsistent when ANY criterion fails (violation = OR of the
        // per-criterion schendingen). In band -> consistent; over limiet / under minimum / leeg saldo -> flagged.
        new FoutFixture("consistentie: compound ALLE (OR of violations)",
            """
            Objecttype de Konto
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
                de minimum Numeriek (geheel getal);
            Regel grenzen
                geldig altijd
                    Een Konto moet voldoen aan alle volgende criteria:
                        - het saldo van een Konto is kleiner dan de limiet van een Konto.
                        - het saldo van een Konto is groter dan het minimum van een Konto.
            """,
            () -> {
                Konto binnen = new Konto(); binnen.setSaldo(new BigDecimal(50)); binnen.setLimiet(new BigDecimal(100)); binnen.setMinimum(BigDecimal.ZERO);
                Konto teHoog = new Konto(); teHoog.setSaldo(new BigDecimal(150)); teHoog.setLimiet(new BigDecimal(100)); teHoog.setMinimum(BigDecimal.ZERO);
                Konto teLaag = new Konto(); teLaag.setSaldo(new BigDecimal(-10)); teLaag.setLimiet(new BigDecimal(100)); teLaag.setMinimum(BigDecimal.ZERO);
                Konto leeg = new Konto(); leeg.setLimiet(new BigDecimal(100)); leeg.setMinimum(BigDecimal.ZERO);   // saldo null -> violation
                return List.of(binnen, teHoog, teLaag, leeg);
            },
            o -> "saldo=" + ((Konto) o).getSaldo()),

        // §8.1.6 UNICITEIT: two persons sharing a BSN both flagged; a unique BSN not; two null BSNs do NOT collide.
        new FoutFixture("uniciteit: shared key flagged, null key not",
            """
            Objecttype de Natuurlijk persoon
                het burgerservicenummer Numeriek (geheel getal);
            Regel bsn uniek
                geldig altijd
                    De burgerservicenummers van alle Natuurlijk persoon moeten uniek zijn.
            """,
            () -> {
                NatuurlijkPersoon a = new NatuurlijkPersoon(); a.setBurgerservicenummer(new BigDecimal(100));
                NatuurlijkPersoon b = new NatuurlijkPersoon(); b.setBurgerservicenummer(new BigDecimal(100));  // deelt BSN
                NatuurlijkPersoon c = new NatuurlijkPersoon(); c.setBurgerservicenummer(new BigDecimal(200));  // uniek
                NatuurlijkPersoon d = new NatuurlijkPersoon();   // BSN leeg
                NatuurlijkPersoon e = new NatuurlijkPersoon();   // BSN leeg
                return List.of(a, b, c, d, e);
            },
            o -> "bsn=" + ((NatuurlijkPersoon) o).getBurgerservicenummer()),

        // §5.7/§8.1.6 TUPLE (concatenatie) UNICITEIT: "de concatenatie van luchthaven en vertrektijd van alle Vluchten
        // moeten uniek zijn" keys uniqueness on the (luchthaven, vertrektijd) PAIR — a duplicate is TWO instances sharing
        // BOTH components. Both back-ends bind + non-null EACH component on the subject and join on EACH in the accumulate
        // (DrlTranspiler.uniciteit / ExecModelTranspiler.uniciteit walk the full u.sleutels()). Rows: a full-pair duplicate
        // (both flagged); a pair that matches on ONLY ONE component (distinct tuple -> NOT flagged); and a tuple with ONE
        // null component (§8.1.6 identity is not established when any key part is null -> guarded out, NOT flagged, and does
        // NOT collide with another same-shaped null-component tuple). Proves the key is the TUPLE, not just sleutels.get(0).
        new FoutFixture("uniciteit: concatenatie tuple (luchthaven, vertrektijd)",
            """
            Objecttype de Vlucht (mv: Vluchten)
                de luchthaven Numeriek (geheel getal);
                de vertrektijd Numeriek (geheel getal);
            Regel vlucht uniek
                geldig altijd
                    De concatenatie van luchthaven en vertrektijd van alle Vluchten moeten uniek zijn.
            """,
            () -> {
                Vlucht dupA = new Vlucht(); dupA.setLuchthaven(new BigDecimal(1)); dupA.setVertrektijd(new BigDecimal(800));  // (1,800)
                Vlucht dupB = new Vlucht(); dupB.setLuchthaven(new BigDecimal(1)); dupB.setVertrektijd(new BigDecimal(800));  // (1,800) -> collides with dupA (both flagged)
                Vlucht sameLh = new Vlucht(); sameLh.setLuchthaven(new BigDecimal(1)); sameLh.setVertrektijd(new BigDecimal(900)); // (1,900) shares only luchthaven -> distinct tuple, NOT flagged
                Vlucht sameTijd = new Vlucht(); sameTijd.setLuchthaven(new BigDecimal(2)); sameTijd.setVertrektijd(new BigDecimal(800)); // (2,800) shares only vertrektijd -> distinct tuple, NOT flagged
                Vlucht nullTijdA = new Vlucht(); nullTijdA.setLuchthaven(new BigDecimal(3));   // (3,null) -> null component, identity not established, NOT flagged
                Vlucht nullTijdB = new Vlucht(); nullTijdB.setLuchthaven(new BigDecimal(3));   // (3,null) -> two null-component tuples do NOT collide
                return List.of(dupA, dupB, sameLh, sameTijd, nullTijdA, nullTijdB);
            },
            o -> "lh=" + ((Vlucht) o).getLuchthaven() + ",tijd=" + ((Vlucht) o).getVertrektijd()),

        // (adversary repro) uniciteit keys of different SCALE (100 vs 100.0): mvel '==' is scale-INSENSITIVE
        // (compareTo), so DRL flags BOTH as a duplicate. Agrees only after the model's key join uses gelijkWaarde
        // (compareTo) instead of the scale-sensitive Objects.equals.
        new FoutFixture("uniciteit: keys of different scale (100 vs 100.0)",
            """
            Objecttype de Natuurlijk persoon
                het burgerservicenummer Numeriek (geheel getal);
            Regel bsn uniek
                geldig altijd
                    De burgerservicenummers van alle Natuurlijk persoon moeten uniek zijn.
            """,
            () -> {
                NatuurlijkPersoon a = new NatuurlijkPersoon(); a.setBurgerservicenummer(new BigDecimal("100"));
                NatuurlijkPersoon b = new NatuurlijkPersoon(); b.setBurgerservicenummer(new BigDecimal("100.0")); // same value, scale 1
                return List.of(a, b);
            },
            o -> "bsn=" + ((NatuurlijkPersoon) o).getBurgerservicenummer().stripTrailingZeros().toPlainString()),

        // §6.5 deling-door-leeg COMPANION: "deelResultaat := grondslag gedeeld door aftrek". 12/leeg is a fout
        // (flagged); leeg/4 short-circuits to 0 (no fout); 12/4 is fine (no fout). The Inconsistentie carries the
        // HOST rule name "deel". Exercises the value-side rsGedeeld + the separate insert-only companion rule.
        new FoutFixture("deling-door-leeg companion (dividend vol, divisor leeg)",
            """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de deelResultaat Numeriek (getal met 2 decimalen);
            Regel deel
                geldig altijd
                    De deelResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom gedeeld door de aftrek van een Rekensom.
            """,
            () -> {
                Rekensom waardeLeeg = new Rekensom(); waardeLeeg.setGrondslag(new BigDecimal(12));   // 12 / leeg -> fout
                Rekensom leegRecht = new Rekensom(); leegRecht.setAftrek(new BigDecimal(4));         // leeg / 4 -> 0, geen fout
                Rekensom beide = new Rekensom(); beide.setGrondslag(new BigDecimal(12)); beide.setAftrek(new BigDecimal(4)); // geen fout
                return List.of(waardeLeeg, leegRecht, beide);
            },
            o -> "g=" + ((Rekensom) o).getGrondslag() + ",a=" + ((Rekensom) o).getAftrek()),

        // §6.6 wortel-van-negatief COMPANION: a negative radicand is a fout (flagged); a non-negative is fine.
        new FoutFixture("wortel-van-negatief companion",
            """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (getal met 2 decimalen);
            Regel w
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de wortel van de basis van een Heffing naar beneden afgerond op 2 decimalen.
            """,
            () -> {
                Heffing neg = new Heffing(); neg.setBasis(new BigDecimal(-9));   // wortel van -9 -> fout
                Heffing pos = new Heffing(); pos.setBasis(new BigDecimal(20));   // ok
                return List.of(neg, pos);
            },
            o -> "basis=" + ((Heffing) o).getBasis()),

        // §13.4.8 a consistentie assertie whose operand is a §6/§13.4.16 date-arithmetic expression: "X moet later
        // zijn dan Y plus Z". Violated when daadwerkelijk vertrek is NOT after vluchtdatum+uitstelduur. Both
        // back-ends lower the negated date comparison (DRL the constraint, the model !datumVergelijkTemporal).
        new FoutFixture("consistentie: datum ordening vs date-arith (later dan vluchtdatum + uitstelduur)",
            """
            Objecttype de Vlucht (mv: Vluchten)
                de vluchtdatum Datum in dagen;
                de daadwerkelijk vertrek Datum in dagen;
                de uitstelduur Numeriek (geheel getal) met eenheid mnd;
            Regel R
                geldig altijd
                    De daadwerkelijk vertrek van een Vlucht moet later zijn dan de vluchtdatum van de Vlucht plus de uitstelduur van de Vlucht.
            """,
            () -> {
                Vlucht ok = new Vlucht(); ok.setVluchtdatum(java.time.LocalDate.of(2022, 6, 15)); ok.setUitstelduur(new BigDecimal(3)); ok.setDaadwerkelijkVertrek(java.time.LocalDate.of(2022, 10, 1));   // after 09-15 -> consistent
                Vlucht bad = new Vlucht(); bad.setVluchtdatum(java.time.LocalDate.of(2022, 6, 15)); bad.setUitstelduur(new BigDecimal(3)); bad.setDaadwerkelijkVertrek(java.time.LocalDate.of(2022, 8, 1));   // before 09-15 -> violation
                return List.of(ok, bad);
            },
            o -> "dav=" + ((Vlucht) o).getDaadwerkelijkVertrek()),

        // §13.4.8 consistentie with a date-arith assertie whose DURATION is a Parameter-GLOBAL: the model binds the
        // global (schendingMetGlobal's date-arith branch). Violated when daadwerkelijk vertrek is NOT after vluchtdatum+marge.
        new FoutFixture("consistentie: datum ordening vs date-arith with GLOBAL duration",
            """
            Parameter de marge : Numeriek (geheel getal) met eenheid mnd
            Objecttype de Vlucht (mv: Vluchten)
                de vluchtdatum Datum in dagen;
                de daadwerkelijk vertrek Datum in dagen;
            Regel R
                geldig altijd
                    De daadwerkelijk vertrek van een Vlucht moet later zijn dan de vluchtdatum van de Vlucht plus de marge.
            """,
            () -> {
                Vlucht ok = new Vlucht(); ok.setVluchtdatum(java.time.LocalDate.of(2022, 6, 15)); ok.setDaadwerkelijkVertrek(java.time.LocalDate.of(2022, 10, 1));   // after 09-15 -> consistent
                Vlucht bad = new Vlucht(); bad.setVluchtdatum(java.time.LocalDate.of(2022, 6, 15)); bad.setDaadwerkelijkVertrek(java.time.LocalDate.of(2022, 8, 1));   // before 09-15 -> violation
                return List.of(ok, bad);
            },
            o -> "dav=" + ((Vlucht) o).getDaadwerkelijkVertrek(),
            Map.of("marge", new BigDecimal(3))),

        // §6.5 "gedeeld door (ABS)" — an ABS division is a leeg-divisor fout exactly like a plain division. The model's
        // verzamelDelingen previously collected only GEDEELD_DOOR, dropping the ABS companion DRL emits (masked under-
        // fire, main rule lowers via rsGedeeldAbs). dividend present + divisor leeg -> fout; leeg dividend -> 0, geen fout.
        new FoutFixture("deling door (ABS) door leeg companion",
            """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de deelResultaat Numeriek (getal met 2 decimalen);
            Regel deelabs
                geldig altijd
                    De deelResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom gedeeld door (ABS) de aftrek van een Rekensom.
            """,
            () -> {
                Rekensom waardeLeeg = new Rekensom(); waardeLeeg.setGrondslag(new BigDecimal(12));   // 12 / |leeg| -> fout
                Rekensom leegLinks = new Rekensom(); leegLinks.setAftrek(new BigDecimal(4));         // leeg / |4| -> 0, geen fout
                Rekensom beide = new Rekensom(); beide.setGrondslag(new BigDecimal(12)); beide.setAftrek(new BigDecimal(-4)); // 12 / |−4| = 3, geen fout
                return List.of(waardeLeeg, leegLinks, beide);
            },
            o -> "g=" + ((Rekensom) o).getGrondslag() + ",a=" + ((Rekensom) o).getAftrek()),

        // §6.5 a division INSIDE a §13.4.17.28 begrenzing BOUND (parenthesised, "met een minimum van (a / b)"). DRL's
        // verzamelDelingen recurses into Begrensd.minimum()/maximum(); the model previously recursed only into basis(),
        // dropping the bound's deling-door-leeg companion (masked under-fire — the rsBegrens value still lowers).
        new FoutFixture("deling-door-leeg INSIDE a begrenzing bound (minimum van (a / b))",
            """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de deelResultaat Numeriek (getal met 2 decimalen);
            Regel deelbound
                geldig altijd
                    De deelResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom, met een minimum van (de grondslag van een Rekensom gedeeld door de aftrek van een Rekensom).
            """,
            () -> {
                Rekensom leegDeler = new Rekensom(); leegDeler.setGrondslag(new BigDecimal(12));   // bound 12 / leeg -> fout
                Rekensom okDeler = new Rekensom(); okDeler.setGrondslag(new BigDecimal(12)); okDeler.setAftrek(new BigDecimal(4)); // bound 12/4 -> geen fout
                return List.of(leegDeler, okDeler);
            },
            o -> "g=" + ((Rekensom) o).getGrondslag() + ",a=" + ((Rekensom) o).getAftrek()),
    };

    // ---- Phase TEKST-CONS (LIFT A): a §9.5/§13.4.8 NON-numeriek (Tekst) attr-vs-attr GELIJK/ONGELIJK CONSISTENTIE now
    //      lowers in the exec schending path (was an honest skip). This is the toka-30 "geen rondvlucht" shape:
    //      "de vertrek moet ongelijk zijn aan de bestemming". Tabel 38 (GELIJK): satisfied only both-present-and-equal
    //      -> a violation for a mismatch / any leeg. Tabel 44 (ONGELIJK): satisfied for one-leeg OR both-present-unequal
    //      -> a violation for both-present-EQUAL (a rondvlucht: vertrek == bestemming) OR both-leeg. Every fixture
    //      INCLUDES the VIOLATING (Inconsistentie-inserting) case so a silent skip cannot pass it vacuously. ----
    static final String TEKST_CONS_ONGELIJK =
        "Objecttype de Route\n    de vertrek Tekst;\n    de bestemming Tekst;\n"
        + "Regel geen rondvlucht\n    geldig altijd\n        De vertrek van een Route moet ongelijk zijn aan de bestemming van een Route.\n";
    static final String TEKST_CONS_GELIJK =
        "Objecttype de Route\n    de vertrek Tekst;\n    de bestemming Tekst;\n"
        + "Regel zelfde plek\n    geldig altijd\n        De vertrek van een Route moet gelijk zijn aan de bestemming van een Route.\n";
    static Route routeVB(String vertrek, String bestemming) {
        Route r = new Route(); if (vertrek != null) r.setVertrek(vertrek); if (bestemming != null) r.setBestemming(bestemming); return r;
    }
    static final FoutFixture[] PHASE_TEKST_CONS = {
        // ONGELIJK (toka-30 "geen rondvlucht"): A!=B satisfied (NOT flagged); A==A a rondvlucht -> VIOLATION (flagged);
        // A vs leeg satisfied (Tabel 44 one-leeg -> WAAR, NOT flagged); both-leeg -> VIOLATION (Tabel 44 both-leeg onwaar).
        new FoutFixture("Tekst attr-vs-attr ONGELIJK consistentie (geen rondvlucht)",
            TEKST_CONS_ONGELIJK,
            () -> List.of(routeVB("A", "B"), routeVB("A", "A"), routeVB("A", null), routeVB(null, null)),
            o -> "v=" + ((Route) o).getVertrek() + ",b=" + ((Route) o).getBestemming()),

        // GELIJK: A==A satisfied (NOT flagged); A!=B -> VIOLATION (flagged); A vs leeg -> VIOLATION (Tabel 38 one-leeg
        // onwaar); both-leeg -> VIOLATION (Tabel 38 both-leeg onwaar/fout).
        new FoutFixture("Tekst attr-vs-attr GELIJK consistentie",
            TEKST_CONS_GELIJK,
            () -> List.of(routeVB("A", "A"), routeVB("A", "B"), routeVB("A", null), routeVB(null, null)),
            o -> "v=" + ((Route) o).getVertrek() + ",b=" + ((Route) o).getBestemming()),
    };

    // ---- Phase D FAR-SIDE: a §6.5/§6.6 fout companion whose HOST rule is gated by a §8.1.7 FAR-side membership
    //      ("indien hij (geen) een passagier heeft" -> a VerreRollidmaatschap exists/not join, a non-empty Gate.extra).
    //      The enkele "indien" voorwaarde uses the §8.3.1 VRAGENDE vorm (verb at the back: "… passagier heeft"), not
    //      the §8.3.2 stellende/bullet form ("hij heeft … passagier"). Both parse (permissive grammar); this is canon.
    //      The companion must CARRY that membership gate, so it raises the Inconsistentie only for a subject the host
    //      rule actually acts on. Before the fix the model `continue`-DROPPED the companion on a non-empty Gate.extra
    //      (without recording overgeslagen, so the harness compared anyway) -> DRL fired the fout, the model did not.
    //      These pin the ABSOLUTE spec firing set on the DRL oracle AND that the model now agrees. ----
    private static final String FARSIDE_FOUT_HDR = """
            Objecttype de Passagier
            Objecttype de Vlucht
                de somMet Numeriek (geheel getal);
                de somZonder Numeriek (geheel getal);
                de totaalprijs Numeriek (getal met 2 decimalen);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            """;
    static final FoutFixture[] PHASE_D_FARSIDE = {
        // §6.5 deling-door-leeg under a far-side POSITIVE membership. Fires only for a Vlucht with a leeg divisor AND a
        // passagier; a divisor-leeg Vlucht WITHOUT a passagier (the negative control) is never acted on -> no fout.
        new FoutFixture("§6.5 deling-door-leeg gated by far-side 'een passagier heeft'",
            FARSIDE_FOUT_HDR + """
            Regel deel
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op de somMet van een Vlucht gedeeld door de somZonder van een Vlucht indien hij een passagier heeft.
            """,
            () -> {
                Vlucht metPax = new Vlucht();    metPax.setSomMet(new BigDecimal(12));                                    // somZonder leeg + heeft passagier -> fout
                Vlucht zonderPax = new Vlucht(); zonderPax.setSomMet(new BigDecimal(99));                                 // somZonder leeg, GEEN passagier -> gate suppresses
                Vlucht beide = new Vlucht();     beide.setSomMet(new BigDecimal(7)); beide.setSomZonder(new BigDecimal(4)); // divisor gevuld -> geen fout
                Passagier p = new Passagier();   p.setReis(metPax);
                return List.of(metPax, zonderPax, beide, p);
            },
            o -> "somMet=" + ((Vlucht) o).getSomMet()),

        // §6.6 wortel-van-negatief under a far-side POSITIVE membership (radicand = somMet, result = totaalprijs).
        new FoutFixture("§6.6 wortel-van-negatief gated by far-side 'een passagier heeft'",
            FARSIDE_FOUT_HDR + """
            Regel wortelregel
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op de wortel van de somMet van een Vlucht naar beneden afgerond op 2 decimalen indien hij een passagier heeft.
            """,
            () -> {
                Vlucht negPax = new Vlucht();    negPax.setSomMet(new BigDecimal(-9));    // negatief radicand + heeft passagier -> fout
                Vlucht negZonder = new Vlucht(); negZonder.setSomMet(new BigDecimal(-4)); // negatief, GEEN passagier -> gate suppresses
                Vlucht posPax = new Vlucht();    posPax.setSomMet(new BigDecimal(20));    // niet-negatief -> geen fout
                Passagier p = new Passagier();   p.setReis(negPax);
                return List.of(negPax, negZonder, posPax, p);
            },
            o -> "somMet=" + ((Vlucht) o).getSomMet()),

        // §6.5 deling-door-leeg under a far-side NEGATION ("geen passagier heeft" -> a `not(...)` join): the companion
        // must keep the negation, so it fires for an EMPTY Vlucht with a leeg divisor and NOT for one with a passagier.
        new FoutFixture("§6.5 deling-door-leeg gated by far-side negation 'geen passagier heeft'",
            FARSIDE_FOUT_HDR + """
            Regel deelgeen
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op de somMet van een Vlucht gedeeld door de somZonder van een Vlucht indien hij geen passagier heeft.
            """,
            () -> {
                Vlucht leegPax = new Vlucht(); leegPax.setSomMet(new BigDecimal(12));   // somZonder leeg, GEEN passagier -> negation holds -> fout
                Vlucht metPax = new Vlucht();  metPax.setSomMet(new BigDecimal(99));    // somZonder leeg, HEEFT passagier -> negation false -> geen fout
                Passagier p = new Passagier(); p.setReis(metPax);
                return List.of(leegPax, metPax, p);
            },
            o -> "somMet=" + ((Vlucht) o).getSomMet()),
    };
    // the ABSOLUTE spec-correct flagged set per fixture (the DRL oracle must match this, not merely the model) — a
    // model-only omission IS caught by the pure differential, but pinning the oracle absolute guards both at once.
    static final List<List<String>> PHASE_D_FARSIDE_EXPECTED = List.of(
        List.of("deel/somMet=12"),
        List.of("wortelregel/somMet=-9"),
        List.of("deelgeen/somMet=12"));

    /** §6.5/§6.6 fout companion under a §8.1.7 FAR-side membership voorwaarde: assert the DRL oracle raises EXACTLY the
     *  spec-expected Inconsistentie set (absolute) AND that the model agrees (parity). Fails before the companion
     *  threads the far-side Gate.extra (the model raised nothing for the gated case). */
    @Test void phase_D_farside_gated_fout_companion_is_spec_correct() {
        for (int i = 0; i < PHASE_D_FARSIDE.length; i++) {
            FoutFixture f = PHASE_D_FARSIDE[i];
            List<String> expected = PHASE_D_FARSIDE_EXPECTED.get(i);
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            List<String> viaDrl   = fireFout(drlBase(m), f);
            List<String> viaModel = fireFout(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(expected, viaDrl, () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(viaDrl, viaModel, () -> "back-ends DIVERGE on '" + f.naam() + "': DRL=" + viaDrl + " model=" + viaModel);
        }
    }

    // ---- Phase D NAV: a §6.5/§6.6 fout companion whose operand is a to-ONE NAVIGATED attribute ("de X van zijn
    //      partner"). DRL's companionDeel accepts EIGEN and GENAVIGEERD, so it root-guards "(partner != null &&
    //      partner.x != null)" / "partner.x"; the model previously REJECTED any navigated operand (continue, no
    //      overgeslagen) -> a masked under-fire (DRL flagged the Inconsistentie, the model did not). The model now
    //      admits a to-one navigated operand via navReader. Mutual partnership so every Persoon HAS a partner (a
    //      navigated VALUE is not yet root-null-guarded; a partnerless Persoon would NPE the main rule — orthogonal). ----
    static final FoutFixture[] PHASE_D_NAV = {
        // §6.5 navigated deling-door-leeg: ratio = inkomen-of-partner / aftrek-of-partner. p navigates q (12/leeg ->
        // fout); q navigates p (10/5 -> geen fout). Flagged subject = p (its partner q has a leeg aftrek).
        new FoutFixture("§6.5 navigated deling-door-leeg (de X van zijn partner / de Y van zijn partner)",
            """
            Objecttype de Persoon
                de inkomen Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de ratio Numeriek (getal met 2 decimalen);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel ratioregel
                geldig altijd
                    De ratio van een Persoon moet gesteld worden op de inkomen van zijn partner gedeeld door de aftrek van zijn partner.
            """,
            () -> {
                Persoon p = new Persoon(); Persoon q = new Persoon();
                p.setPartner(q); q.setPartner(p);
                q.setInkomen(new BigDecimal(12));                            // p navigates q: 12 / leeg -> fout
                p.setInkomen(new BigDecimal(10)); p.setAftrek(new BigDecimal(5)); // q navigates p: 10 / 5 -> geen fout
                return List.of(p, q);
            },
            o -> "inkomen=" + ((Persoon) o).getInkomen()),   // flagged = p (inkomen=10)

        // §6.6 navigated wortel-van-negatief: ratio = wortel van inkomen-of-partner. p navigates q (wortel(-9) ->
        // fout); q navigates p (wortel(16)=4 -> geen fout). Flagged subject = p (its partner q has a negative inkomen).
        new FoutFixture("§6.6 navigated wortel-van-negatief (de wortel van de X van zijn partner)",
            """
            Objecttype de Persoon
                de inkomen Numeriek (geheel getal);
                de ratio Numeriek (getal met 2 decimalen);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel wortelnav
                geldig altijd
                    De ratio van een Persoon moet gesteld worden op de wortel van de inkomen van zijn partner naar beneden afgerond op 2 decimalen.
            """,
            () -> {
                Persoon p = new Persoon(); Persoon q = new Persoon();
                p.setPartner(q); q.setPartner(p);
                q.setInkomen(new BigDecimal(-9));   // p navigates q: wortel(-9) -> fout
                p.setInkomen(new BigDecimal(16));   // q navigates p: wortel(16)=4 -> geen fout
                return List.of(p, q);
            },
            o -> "inkomen=" + ((Persoon) o).getInkomen()),   // flagged = p (inkomen=16)
    };
    static final List<List<String>> PHASE_D_NAV_EXPECTED = List.of(
        List.of("ratioregel/inkomen=10"),
        List.of("wortelnav/inkomen=16"));

    /** §6.5/§6.6 fout companion over a to-ONE NAVIGATED operand: assert the DRL oracle raises EXACTLY the spec-expected
     *  Inconsistentie set (absolute) AND the model agrees (parity). Fails before the model admits navigated operands
     *  (the model raised nothing, a masked under-fire the differential harness compared without skipping). */
    @Test void phase_D_navigated_fout_companion_is_spec_correct() {
        for (int i = 0; i < PHASE_D_NAV.length; i++) {
            FoutFixture f = PHASE_D_NAV[i];
            List<String> expected = PHASE_D_NAV_EXPECTED.get(i);
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            List<String> viaDrl   = fireFout(drlBase(m), f);
            List<String> viaModel = fireFout(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(expected, viaDrl, () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(viaDrl, viaModel, () -> "back-ends DIVERGE on '" + f.naam() + "': DRL=" + viaDrl + " model=" + viaModel);
        }
    }

    // ---- division value (§6.5 rsGedeeld) — the value-side of the deling, checked by reading the result field ----
    static final Fixture[] PHASE_DIV = {
        new Fixture("division value (rsGedeeld: left-empty->0, right-empty->leeg)",
            """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de deelResultaat Numeriek (getal met 2 decimalen);
            Regel deel
                geldig altijd
                    De deelResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom gedeeld door de aftrek van een Rekensom.
            """,
            () -> { Rekensom r = new Rekensom(); r.setGrondslag(new BigDecimal(12)); r.setAftrek(new BigDecimal(4)); return List.of(r); },
            Map.of(),
            l -> ((Rekensom) l.get(0)).getDeelResultaat()),   // 12 / 4 = 3

        new Fixture("division value: non-empty over empty -> leeg",
            """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de deelResultaat Numeriek (getal met 2 decimalen);
            Regel deel
                geldig altijd
                    De deelResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom gedeeld door de aftrek van een Rekensom.
            """,
            () -> { Rekensom r = new Rekensom(); r.setGrondslag(new BigDecimal(12)); return List.of(r); },  // aftrek null -> leeg
            Map.of(),
            l -> ((Rekensom) l.get(0)).getDeelResultaat()),
    };

    // ---- Phase PCT: §13.4.17.12/§6.8 "het percentage P van V" WITHOUT the '%'. Our grammar requires '%' on
    //      percentageVanExpr (to disambiguate from a 'van' navigation), so the no-'%' form parses as a navigation; the
    //      resolver reinterprets it as PERCENTAGE_VAN when the leftmost name is a Percentage-typed PARAMETER. Value =
    //      V*P/100 (§4.8 Tabel 19); a leeg V telts als 0 (§4.8 — the bridge's leegwaarde divergence: percentage->0,
    //      UNLIKE tijdsevenredig->leeg). korting = 10 (%) is supplied as a BigDecimal global. ----
    private static final String PCT_HDR = """
            Parameter de korting : Percentage (geheel getal)
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de maalResultaat Numeriek (geheel getal);
            """;
    static final Fixture[] PHASE_PCT = {
        new Fixture("§13.4.17.12 percentage 'P van V' (navigated V) = V*P/100",
            PCT_HDR + """
            Regel r
                geldig altijd
                    De maalResultaat van een Rekensom moet gesteld worden op de korting van de grondslag van een Rekensom.
            """,
            () -> { Rekensom r = new Rekensom(); r.setGrondslag(new BigDecimal(200)); return List.of(r); },
            Map.of("korting", new BigDecimal(10)),
            l -> ((Rekensom) l.get(0)).getMaalResultaat()),   // 200 * 10 / 100 = 20
        new Fixture("§13.4.17.12 percentage 'P van zijn V' (possessive)",
            PCT_HDR + """
            Regel r
                geldig altijd
                    De maalResultaat van een Rekensom moet gesteld worden op de korting van zijn grondslag.
            """,
            () -> { Rekensom r = new Rekensom(); r.setGrondslag(new BigDecimal(150)); return List.of(r); },
            Map.of("korting", new BigDecimal(10)),
            l -> ((Rekensom) l.get(0)).getMaalResultaat()),   // 150 * 10 / 100 = 15
        new Fixture("§13.4.17.12 percentage leeg V -> 0 (leeg telt als 0, §4.8)",
            PCT_HDR + """
            Regel r
                geldig altijd
                    De maalResultaat van een Rekensom moet gesteld worden op de korting van de grondslag van een Rekensom.
            """,
            () -> List.of(new Rekensom()),   // grondslag null -> 0
            Map.of("korting", new BigDecimal(10)),
            l -> ((Rekensom) l.get(0)).getMaalResultaat()),   // 0
        new Fixture("§13.4.17.12 percentage 'P van de V' (bare subject-own V, §5.5.3)",
            PCT_HDR + """
            Regel r
                geldig altijd
                    De maalResultaat van een Rekensom moet gesteld worden op de korting van de grondslag.
            """,
            () -> { Rekensom r = new Rekensom(); r.setGrondslag(new BigDecimal(300)); return List.of(r); },
            Map.of("korting", new BigDecimal(10)),
            l -> ((Rekensom) l.get(0)).getMaalResultaat()),   // 300 * 10 / 100 = 30
    };
    static final BigDecimal[] PHASE_PCT_EXPECTED = { new BigDecimal(20), new BigDecimal(15), BigDecimal.ZERO, new BigDecimal(30) };

    /** §13.4.17.12 percentage 'P van V' (no '%'): assert the DRL oracle computes V*P/100 (and leeg->0) per §4.8 — an
     *  ABSOLUTE spec value, not only DRL==model (PERCENTAGE_VAN is a SHARED transform, so a pure differential would miss
     *  a shared bug; see the differential-masks-shared-bugs discipline) — AND that the model agrees. */
    @Test void phase_PCT_is_spec_correct() {
        for (int i = 0; i < PHASE_PCT.length; i++) {
            Fixture f = PHASE_PCT[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            BigDecimal viaDrl = (BigDecimal) fire(drlBase(m), f);
            BigDecimal viaModel = (BigDecimal) fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertNotNull(viaDrl, () -> f.naam() + " DRL produced null");
            assertEquals(0, PHASE_PCT_EXPECTED[i].compareTo(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "': " + viaDrl);
            assertEquals(0, viaDrl.compareTo(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "': DRL=" + viaDrl + " model=" + viaModel);
        }
    }

    // ---- Phase PE: §7.1+§5.8 PER-ELEMENT timeline fold — "het totaal van <temporeel attr> van alle <set>". Each
    //      element's tijdreeks is summed over time (rsTijdTotaal) to a scalar, then those scalars are summed over the
    //      set (a Drools accumulate sum over rsTijdTotaal($e.getX(), gran)). Subject = Vlucht, set = its passagiers. ----
    static final Fixture[] PHASE_PE = {
        new Fixture("§7.1 per-element timeline fold: het totaal van <temporeel attr> van alle <set>",
            """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
            Objecttype de Vlucht (mv: Vluchten) (bezield)
                de totaalprijs Bedrag;
            Feittype reis
                de passagier Passagier
                de reis Vlucht
            Eén reis heeft meerdere passagiers
            Regel R
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op het totaal van de belastingvermindering per maand van alle passagiers.
            """,
            () -> {
                Vlucht v = new Vlucht();
                Passagier p1 = new Passagier(); p1.setReis(v);
                p1.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2025, 1, 1, "10.00")));   // €10/mnd × 12 = 120
                Passagier p2 = new Passagier(); p2.setReis(v);
                p2.setBelastingverminderingPerMaand(List.of(seg(2024, 1, 1, 2024, 7, 1, "5.00")));    // €5/mnd × 6 = 30
                Passagier leeg = new Passagier(); leeg.setReis(v);                                     // null timeline -> 0
                return List.of(v, p1, p2, leeg);
            },
            Map.of(),
            l -> ((Vlucht) l.get(0)).getTotaalprijs()),   // 120 + 30 + 0 = 150
    };

    /** §7.1 per-element timeline fold: assert the DRL oracle sums the per-element rsTijdTotaal to the spec value (150)
     *  AND the model agrees (rsTijdTotaal is a shared helper — assert the absolute value, not only DRL==model). */
    @Test void phase_PE_per_element_timeline_fold_is_spec_correct() {
        Fixture f = PHASE_PE[0];
        RegelSpraakModel m = Resolver.resolve(f.src());
        assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
        BigDecimal viaDrl = (BigDecimal) fire(drlBase(m), f);
        BigDecimal viaModel = (BigDecimal) fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
        assertEquals(0, new BigDecimal("150").compareTo(viaDrl), () -> "DRL (oracle) wrong vs spec (120+30+0=150): " + viaDrl);
        assertEquals(0, viaDrl.compareTo(viaModel), () -> "back-ends DIVERGE: DRL=" + viaDrl + " model=" + viaModel);
    }

    // ---- scalar arithmetic (§6.2-6.4): plus / min / verminderd met / maal, with the leegwaarde tables ----
    static final String ARITH_SRC = """
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
    static final Fixture[] PHASE_ARITH = {
        // §6.2-6.4 leegwaarde tables across all four ops, for a leeg-left and a fully-filled Rekensom:
        // leeg-left -> verminderd=leeg, min=-4, plus=4, maal=0; filled (10,4) -> 6/6/14/40.
        new Fixture("plus/min/verminderd/maal leegwaarde tables",
            ARITH_SRC,
            () -> {
                Rekensom leegLinks = new Rekensom(); leegLinks.setAftrek(new BigDecimal(4));   // grondslag leeg
                Rekensom gevuld = new Rekensom(); gevuld.setGrondslag(new BigDecimal(10)); gevuld.setAftrek(new BigDecimal(4));
                return List.of(leegLinks, gevuld);
            },
            Map.of(),
            l -> {
                Rekensom a = (Rekensom) l.get(0), b = (Rekensom) l.get(1);
                return vals(a.getVerminderdResultaat(), a.getMinResultaat(), a.getPlusResultaat(), a.getMaalResultaat(),
                            b.getVerminderdResultaat(), b.getMinResultaat(), b.getPlusResultaat(), b.getMaalResultaat());
            }),

        // §13.4.17.13 wortel + its mandatory afronding: wortel(20) naar beneden op 2 dec = 4.47; a negative
        // radicand -> leeg (the §6.6 Inconsistentie is the companion, checked separately in Phase D).
        new Fixture("wortel + afronding value (neg -> leeg)",
            """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (getal met 2 decimalen);
            Regel w
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de wortel van de basis van een Heffing naar beneden afgerond op 2 decimalen.
            """,
            () -> {
                Heffing pos = new Heffing(); pos.setBasis(new BigDecimal(20));
                Heffing neg = new Heffing(); neg.setBasis(new BigDecimal(-9));
                return List.of(pos, neg);
            },
            Map.of(),
            l -> vals(((Heffing) l.get(0)).getNetto(), ((Heffing) l.get(1)).getNetto())),   // 4.47, null

        // (adversary repro) MAAL of two high-precision decimals: mvel's infix '*' rounds to DECIMAL128; an EXACT
        // .multiply diverges once the product exceeds 34 sig digits. Agrees only after the model uses DECIMAL128.
        new Fixture("MAAL high-precision (DECIMAL128 parity)",
            """
            Objecttype de Rekensom
                de grondslag Numeriek (getal met 9 decimalen);
                de aftrek Numeriek (getal met 9 decimalen);
                de maalResultaat Numeriek (getal met 18 decimalen);
            Regel rx
                geldig altijd
                    De maalResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom maal de aftrek van een Rekensom.
            """,
            () -> {
                Rekensom r = new Rekensom();
                r.setGrondslag(new BigDecimal("123456789.123456789"));
                r.setAftrek(new BigDecimal("987654321.987654321"));
                return List.of(r);
            },
            Map.of(),
            l -> ((Rekensom) l.get(0)).getMaalResultaat()),
    };

    // ---- Phase E: §9.7/§13.4.10 verdeling — share-rule value parity (gelijke delen / naar rato / op volgorde + cap
    //      + floor). Restant-free sources: the §9.7.5 onverdeelde-rest rule is a source-modify that forms a share↔rest
    //      cycle (a focused follow-up), so a verdeling carrying it SKIPS. Facts are [pot, d1, d2, ...]; uit reads the
    //      recipients' aandeel in insertion order. ----
    private static Deelnemer deeln(Pot pot, String gewicht, String leeftijd, String maximum) {
        Deelnemer d = new Deelnemer(); d.setPot(pot);
        if (gewicht != null) d.setGewicht(new BigDecimal(gewicht));
        if (leeftijd != null) d.setLeeftijd(new BigDecimal(leeftijd));
        if (maximum != null) d.setMaximum(new BigDecimal(maximum));
        return d;
    }
    static final Fixture[] PHASE_E = {
        // §13.4.10 GELIJKE DELEN: 90 over 3 deelnemers -> 30 each (total/count).
        new Fixture("verdeling gelijke delen (90/3 = 30 ieder)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel gelijk
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(90));
                    return List.of(p, deeln(p, null, null, null), deeln(p, null, null, null), deeln(p, null, null, null)); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel(), ((Deelnemer) l.get(3)).getAandeel())),

        // §13.4.10 NAAR RATO: 120 over weights 1/2/3 (Σ6) -> 20/40/60 (total·weight/Σweight).
        new Fixture("verdeling naar rato (120, gewicht 1:2:3 -> 20/40/60)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel rato
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld naar rato van de gewicht.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(120));
                    return List.of(p, deeln(p, "1", null, null), deeln(p, "2", null, null), deeln(p, "3", null, null)); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel(), ((Deelnemer) l.get(3)).getAandeel())),

        // §9.7.3 cap + §9.7.4 floor (naar rato): 1200 over 3:1:2 (Σ6) -> 600/200/400, each capped at 300, floor 0 dec
        // -> 300/200/300 (the capped surplus is NOT redistributed). Maxima all valid -> no Inconsistentie.
        new Fixture("verdeling naar rato + maximum + afronding (cap 300, floor 0)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de maximum Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel rato max
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - naar rato van de gewicht,
                    - met een maximum van de maximum,
                    - afgerond op 0 decimalen naar beneden.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(1200));
                    return List.of(p, deeln(p, "3", null, "300"), deeln(p, "1", null, "300"), deeln(p, "2", null, "300")); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel(), ((Deelnemer) l.get(3)).getAandeel())),

        // §9.7.2 OP VOLGORDE toenemend: 1200 over ages 20/20/22, tie naar rato van gewicht 3:1. The youngest group
        // {20,20} splits the WHOLE 1200 (Σ4 -> 900/300), exhausting it, so age 22 gets 0 (group-entry-pot reading).
        new Fixture("verdeling op volgorde toenemend (1200, jongste groep neemt alles)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel volgorde
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - op volgorde van toenemende de leeftijd,
                    - bij een even groot criterium naar rato van de gewicht.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(1200));
                    return List.of(p, deeln(p, "3", "20", null), deeln(p, "1", "20", null), deeln(p, "2", "22", null)); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel(), ((Deelnemer) l.get(3)).getAandeel())),

        // §9.7.5 Tabel 18 (op volgorde + cap + floor): 1800 over ages 22/39/39/66/66, tie naar rato 3/2/1/3/1, max
        // 500/500/500/250/250, floor 0. Group-by-group: 500 (pot 1300); 500/433 (pot 367); 250/91. The cap+floor are
        // applied INSIDE the helper so the carry-down pot is right — pins against a post-hoc clamp on the full plafond.
        new Fixture("verdeling op volgorde + max + afronding (Tabel 18: 500/500/433/250/91)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
                de maximum Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel volgorde max
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - op volgorde van toenemende de leeftijd,
                    - bij een even groot criterium naar rato van de gewicht,
                    - met een maximum van de maximum,
                    - afgerond op 0 decimalen naar beneden.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(1800));
                    return List.of(p, deeln(p, "3", "22", "500"), deeln(p, "2", "39", "500"), deeln(p, "1", "39", "500"),
                                      deeln(p, "3", "66", "250"), deeln(p, "1", "66", "250")); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel(), ((Deelnemer) l.get(3)).getAandeel(),
                      ((Deelnemer) l.get(4)).getAandeel(), ((Deelnemer) l.get(5)).getAandeel())),

        // §9.7.2 OP VOLGORDE afnemend: the OLDEST group is served first. Age 22 alone takes the whole 1200, so the
        // age-20 group gets 0 (the sort direction flips the depletion order).
        new Fixture("verdeling op volgorde afnemend (oudste groep neemt alles)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel volgorde afnemend
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - op volgorde van afnemende de leeftijd,
                    - bij een even groot criterium naar rato van de gewicht.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(1200));
                    return List.of(p, deeln(p, "3", "20", null), deeln(p, "1", "20", null), deeln(p, "2", "22", null)); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel(), ((Deelnemer) l.get(3)).getAandeel())),

        // §9.7.5 ONVERDEELDE REST (naar rato + afronding): 1001 over 3:2 (Σ5) -> 600,6/400,4 floored 600/400; the
        // source keeps the undistributed 1001-1000 = 1. The rest rule is a SOURCE-modify; the share↔rest cycle is
        // broken by the source pattern watching only 'totaal' (not the 'restant' it writes).
        new Fixture("verdeling naar rato + afronding + onverdeelde rest (600/400 rest 1)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel rest
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - naar rato van de gewicht,
                    - afgerond op 0 decimalen naar beneden.
                    Als onverdeelde rest blijft de restant van een pot over.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(1001));
                    return List.of(p, deeln(p, "3", null, null), deeln(p, "2", null, null)); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel(), ((Pot) l.get(0)).getRestant())),

        // §9.7.3 cap + §9.7.5 rest: 1200 over 3:1:2 capped 300 -> 300/200/300; the capped surplus is NOT
        // redistributed, the 1200-800 = 400 is the onverdeelde rest.
        new Fixture("verdeling naar rato + maximum + onverdeelde rest (300/200/300 rest 400)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de maximum Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel max rest
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - naar rato van de gewicht,
                    - met een maximum van de maximum.
                    Als onverdeelde rest blijft de restant van een pot over.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(1200));
                    return List.of(p, deeln(p, "3", null, "300"), deeln(p, "1", null, "300"), deeln(p, "2", null, "300")); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel(), ((Deelnemer) l.get(3)).getAandeel(),
                      ((Pot) l.get(0)).getRestant())),

        // §9.7.5 Tabel 18 op-volgorde + max + afronding + REST: shares 500/500/433/250/91, rest 1800-1774 = 26 (the
        // cap+floor are applied group-by-group INSIDE the helper, so the carry-down pot — and the rest — are right).
        new Fixture("verdeling op volgorde + max + afronding + rest (Tabel 18 rest 26)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
                de maximum Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel volgorde max rest
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - op volgorde van toenemende de leeftijd,
                    - bij een even groot criterium naar rato van de gewicht,
                    - met een maximum van de maximum,
                    - afgerond op 0 decimalen naar beneden.
                    Als onverdeelde rest blijft de restant van een pot over.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(1800));
                    return List.of(p, deeln(p, "3", "22", "500"), deeln(p, "2", "39", "500"), deeln(p, "1", "39", "500"),
                                      deeln(p, "3", "66", "250"), deeln(p, "1", "66", "250")); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel(), ((Deelnemer) l.get(3)).getAandeel(),
                      ((Deelnemer) l.get(4)).getAandeel(), ((Deelnemer) l.get(5)).getAandeel(), ((Pot) l.get(0)).getRestant())),

        // (adversary-confirmed) TWO verdelingen writing the SAME recipient attribuut 'aandeel'. The model SKIPS such a
        // multi-writer target (overgeslagen non-empty), so this fixture is an explicit logged skip — never fired. The
        // skip is PRINCIPLED, not just a hang-guard: .watch(join) on the Deelnemer patterns breaks the sibling loop,
        // but the back-ends then DIVERGE on the value (which verdeling wins the shared attribute is agenda-order
        // dependent — DRL 25/75 vs model 50/50 for 100 over 1:3). The construct is degenerate (RegelSpraak defines no
        // value for two conflicting distribution rules), so an honest skip is the right call. Regression for the guard.
        new Fixture("verdeling: twee verdelingen op hetzelfde doel -> honest skip (geen hang)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel een
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld naar rato van de gewicht.
            Regel verdeel twee
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.
            """,
            () -> { Pot p = new Pot(); p.setTotaal(new BigDecimal(100));
                    return List.of(p, deeln(p, "1", null, null), deeln(p, "3", null, null)); },
            Map.of(),
            l -> vals(((Deelnemer) l.get(1)).getAandeel(), ((Deelnemer) l.get(2)).getAandeel())),
    };

    // ---- Phase H fout: §13.4.8 consistentie schending generality — datum ordering + non-numeriek (Tekst) equality
    //      assertions (the model only flagged own-numeric comparisons before). Observe the SET of flagged subjects. ----
    static final FoutFixture[] PHASE_H_FOUT = {
        // §13.4.8 datum ordering vs a literal: geboortedatum moet eerder zijn dan 1-1-2000. 1990 ok (not flagged),
        // 2005 violated, a leeg geboortedatum violated (an empty ordering operand is a violation).
        new FoutFixture("consistentie: datum ordering vs literal (leeg is a violation)",
            "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de geboortedatum Datum in dagen;\n"
            + "Regel datcheck\n    geldig altijd\n        De geboortedatum van een Passagier moet eerder zijn dan 1-1-2000.\n",
            () -> List.of(passagier(p -> p.setGeboortedatum(dat(1990, 1, 1))),
                          passagier(p -> p.setGeboortedatum(dat(2005, 1, 1))),
                          new Passagier()),
            o -> "gb=" + ((Passagier) o).getGeboortedatum()),

        // §13.4.8 / Tabel 38 Tekst equality vs a literal: naam moet gelijk zijn aan "Amsterdam". A leeg naam is a
        // violation (a leeg attr never equals the non-null literal).
        new FoutFixture("consistentie: Tekst equality vs literal (leeg is a violation)",
            "Objecttype de Bestemming (mv: Bestemmingen)\n    de naam Tekst;\n"
            + "Regel naamcheck\n    geldig altijd\n        De naam van een Bestemming moet gelijk zijn aan \"Amsterdam\".\n",
            () -> { io.github.yhavinga.regelspraak.facts.Bestemming ok = new io.github.yhavinga.regelspraak.facts.Bestemming(); ok.setNaam("Amsterdam");
                    io.github.yhavinga.regelspraak.facts.Bestemming anders = new io.github.yhavinga.regelspraak.facts.Bestemming(); anders.setNaam("Rotterdam");
                    return List.of(ok, anders, new io.github.yhavinga.regelspraak.facts.Bestemming()); },
            o -> "naam=" + ((io.github.yhavinga.regelspraak.facts.Bestemming) o).getNaam()),

        // §13.4.8 a NAVIGATED numeric ordering assertie "de leeftijd ... kleiner dan de leeftijd van zijn partner"
        // (toka-30 shape). 30<40 ok; 50<40 violated; a partnerless persoon -> partner.leeftijd leeg -> ordering
        // violation (navReader null == DRL's l.leeg). flagged = {ouder(no partner), oud(50), zonder(no partner)}.
        new FoutFixture("consistentie: navigated numeric ordering (leeftijd < die van zijn partner)",
            """
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel check
                geldig altijd
                    De leeftijd van een Persoon moet kleiner zijn dan de leeftijd van zijn partner.
            """,
            () -> { Persoon ouder = new Persoon(); ouder.setLeeftijd(new BigDecimal(40));
                    Persoon jong = new Persoon(); jong.setLeeftijd(new BigDecimal(30)); jong.setPartner(ouder);   // 30<40 ok
                    Persoon oud = new Persoon(); oud.setLeeftijd(new BigDecimal(50)); oud.setPartner(ouder);       // 50<40 violation
                    Persoon zonder = new Persoon(); zonder.setLeeftijd(new BigDecimal(35));                        // partner leeg -> violation
                    return List.of(ouder, jong, oud, zonder); },
            o -> "lft=" + ((Persoon) o).getLeeftijd()),

        // §8.1.6 "verenigd met" cross-type union uniqueness: names unique across the UNION of Cirkels and Vierkanten.
        // "A" appears in BOTH a Cirkel and a Vierkant -> both flagged; "B" (Cirkel only) and "C" (Vierkant only) unique.
        new FoutFixture("uniciteit-unie: namen van Cirkels verenigd met namen van Vierkanten",
            """
            Objecttype de Cirkel
                de naam Tekst;
            Objecttype het Vierkant
                de naam Tekst;
            Regel uniek
                geldig altijd
                    De namen van alle Cirkels verenigd met de namen van alle Vierkanten moeten uniek zijn.
            """,
            () -> { io.github.yhavinga.regelspraak.facts.Cirkel ca = new io.github.yhavinga.regelspraak.facts.Cirkel(); ca.setNaam("A");
                    io.github.yhavinga.regelspraak.facts.Cirkel cb = new io.github.yhavinga.regelspraak.facts.Cirkel(); cb.setNaam("B");
                    io.github.yhavinga.regelspraak.facts.Vierkant va = new io.github.yhavinga.regelspraak.facts.Vierkant(); va.setNaam("A");
                    io.github.yhavinga.regelspraak.facts.Vierkant vc = new io.github.yhavinga.regelspraak.facts.Vierkant(); vc.setNaam("C");
                    return List.of(ca, cb, va, vc); },
            o -> (o instanceof io.github.yhavinga.regelspraak.facts.Cirkel c ? "Cirkel:" + c.getNaam()
                                                                 : "Vierkant:" + ((io.github.yhavinga.regelspraak.facts.Vierkant) o).getNaam())),

        // §8.1.6/§13.4.8.12 THREE-source "verenigd met": the union counts EVERY source, so a value shared between the
        // FIRST and THIRD source is a collision the 3rd-source count must catch. "A" appears in a Cirkel AND a Bestemming
        // (the 3rd source) -> both flagged; "B" (Cirkel only) and "C" (Vierkant only) stay unique.
        new FoutFixture("uniciteit-unie: 3 bronnen — duplicaat over de derde bron",
            """
            Objecttype de Cirkel
                de naam Tekst;
            Objecttype het Vierkant
                de naam Tekst;
            Objecttype de Bestemming (mv: Bestemmingen)
                de naam Tekst;
            Regel uniek
                geldig altijd
                    De namen van alle Cirkels verenigd met de namen van alle Vierkanten verenigd met de namen van alle Bestemmingen moeten uniek zijn.
            """,
            () -> { io.github.yhavinga.regelspraak.facts.Cirkel ca = new io.github.yhavinga.regelspraak.facts.Cirkel(); ca.setNaam("A");
                    io.github.yhavinga.regelspraak.facts.Cirkel cb = new io.github.yhavinga.regelspraak.facts.Cirkel(); cb.setNaam("B");
                    io.github.yhavinga.regelspraak.facts.Vierkant vc = new io.github.yhavinga.regelspraak.facts.Vierkant(); vc.setNaam("C");
                    io.github.yhavinga.regelspraak.facts.Bestemming ba = new io.github.yhavinga.regelspraak.facts.Bestemming(); ba.setNaam("A");
                    return List.of(ca, cb, vc, ba); },
            o -> unieKey(o)),

        // §8.1.6/§13.4.8.12 FOUR-source "verenigd met": a value shared between the SECOND and FOURTH source collides.
        // "D" appears in a Vierkant AND a Contingent (the 4th source) -> both flagged; "A"(Cirkel), "B"(Bestemming) unique.
        new FoutFixture("uniciteit-unie: 4 bronnen — duplicaat over de vierde bron",
            """
            Objecttype de Cirkel
                de naam Tekst;
            Objecttype het Vierkant
                de naam Tekst;
            Objecttype de Bestemming (mv: Bestemmingen)
                de naam Tekst;
            Objecttype de Contingent (mv: Contingenten)
                de naam Tekst;
            Regel uniek
                geldig altijd
                    De namen van alle Cirkels verenigd met de namen van alle Vierkanten verenigd met de namen van alle Bestemmingen verenigd met de namen van alle Contingenten moeten uniek zijn.
            """,
            () -> { io.github.yhavinga.regelspraak.facts.Cirkel ca = new io.github.yhavinga.regelspraak.facts.Cirkel(); ca.setNaam("A");
                    io.github.yhavinga.regelspraak.facts.Vierkant vd = new io.github.yhavinga.regelspraak.facts.Vierkant(); vd.setNaam("D");
                    io.github.yhavinga.regelspraak.facts.Bestemming bb = new io.github.yhavinga.regelspraak.facts.Bestemming(); bb.setNaam("B");
                    io.github.yhavinga.regelspraak.facts.Contingent kd = new io.github.yhavinga.regelspraak.facts.Contingent(); kd.setNaam("D");
                    return List.of(ca, vd, bb, kd); },
            o -> unieKey(o)),

        // §9.5 a vs-PARAMETER assertie (spec-main-90 "leeftijd moet kleiner zijn dan de grens"): the global is bound
        // as a pattern variable. grens 18 -> 10 ok, 30 violated, a leeg leeftijd -> ordering violation.
        new FoutFixture("consistentie: own attr vs Parameter-global (leeftijd < de grens)",
            "Parameter de grens : Numeriek (geheel getal)\n"
            + "Objecttype de Persoon (mv: Personen) (bezield)\n    de leeftijd Numeriek (geheel getal);\n"
            + "Regel check\n    geldig altijd\n        De leeftijd van een Persoon moet kleiner zijn dan de grens.\n",
            () -> { Persoon jong = new Persoon(); jong.setLeeftijd(new BigDecimal(10));
                    Persoon oud = new Persoon(); oud.setLeeftijd(new BigDecimal(30));
                    return List.of(jong, oud, new Persoon()); },   // leeg leeftijd -> violation
            o -> "lft=" + ((Persoon) o).getLeeftijd(),
            Map.of("grens", new BigDecimal(18))),

        // §9.5 a LEEG/GEVULD assertie ("de roepnaam moet gevuld zijn"): the violation is its negation — a leeg
        // roepnaam violates GEVULD. metNaam ok; the nameless persoon flagged.
        new FoutFixture("consistentie: GEVULD assertie (roepnaam moet gevuld zijn)",
            "Objecttype de Persoon (mv: Personen) (bezield)\n    de roepnaam Tekst;\n"
            + "Regel check\n    geldig altijd\n        De roepnaam van een Persoon moet gevuld zijn.\n",
            () -> { Persoon metNaam = new Persoon(); metNaam.setRoepnaam("Maria"); return List.of(metNaam, new Persoon()); },
            o -> "naam=" + ((Persoon) o).getRoepnaam()),

        // §8.1.6 ever-uniqueness on the SINGLE form (uchar RS ch. 5.9, extended 2026-08-20): a temporal
        // key joins on the ever-value intersection, so ALL THREE persons ever-carry 100 and are flagged —
        // including p3, whose content-equal-but-distinct timeline the old List-identity join silently
        // missed. Both routes call the shared rsOoitBotst; the differential guards the reading.
        new FoutFixture("uniciteit: temporal key (ever-value collision, single form)",
            "Objecttype de Persoon (mv: Personen) (bezield)\n    de leeftijd Numeriek (geheel getal);\n"
            + "    de bedrag per maand Numeriek (geheel getal) voor elke maand;\n"
            + "Regel uniek\n    geldig altijd\n        De bedrag per maand van alle Personen moeten uniek zijn.\n",
            () -> { List<Segment> shared = List.of(seg(2024, 1, 1, 2024, 6, 1, "100"));
                    Persoon p1 = new Persoon(); p1.setLeeftijd(new BigDecimal(1)); p1.setBedragPerMaand(shared);
                    Persoon p2 = new Persoon(); p2.setLeeftijd(new BigDecimal(2)); p2.setBedragPerMaand(shared);
                    Persoon p3 = new Persoon(); p3.setLeeftijd(new BigDecimal(3)); p3.setBedragPerMaand(List.of(seg(2024, 1, 1, 2024, 6, 1, "100")));
                    return List.of(p1, p2, p3); },
            o -> "lft=" + ((Persoon) o).getLeeftijd()),

        // §8.1.6 ever-uniqueness (uchar RS ch. 5.9): a temporal key in a UNION reads the ever-value
        // intersection — 7-in-2024 collides with 7-in-2026 across the union's two types even though the
        // periods are disjoint; the same-instance redeclaration (9 in both years) claims once and stays
        // unique, as does the sole 8. Both routes call the shared rsOoitBotst, so the differential
        // guards the reading.
        new FoutFixture("uniciteit-unie: temporele sleutel botst ooit over disjuncte periodes",
            """
            Objecttype de Strandvondst
                het vondstnummer Numeriek (geheel getal) voor elk jaar;
            Objecttype de Wrakvondst
                het vondstnummer Numeriek (geheel getal) voor elk jaar;
            Regel nummer uniek
                geldig altijd
                    De vondstnummers van alle Strandvondsten verenigd met de vondstnummers van alle Wrakvondsten moeten uniek zijn.
            """,
            () -> { io.github.yhavinga.regelspraak.facts.Strandvondst s1 = new io.github.yhavinga.regelspraak.facts.Strandvondst();
                    s1.setVondstnummer(List.of(seg(2024, 1, 1, 2025, 1, 1, "7")));
                    io.github.yhavinga.regelspraak.facts.Strandvondst s2 = new io.github.yhavinga.regelspraak.facts.Strandvondst();
                    s2.setVondstnummer(List.of(seg(2024, 1, 1, 2025, 1, 1, "9"), seg(2026, 1, 1, 2027, 1, 1, "9")));
                    io.github.yhavinga.regelspraak.facts.Wrakvondst w1 = new io.github.yhavinga.regelspraak.facts.Wrakvondst();
                    w1.setVondstnummer(List.of(seg(2026, 1, 1, 2027, 1, 1, "7")));
                    io.github.yhavinga.regelspraak.facts.Wrakvondst w2 = new io.github.yhavinga.regelspraak.facts.Wrakvondst();
                    w2.setVondstnummer(List.of(seg(2024, 1, 1, 2025, 1, 1, "8")));
                    return List.of(s1, s2, w1, w2); },
            o -> (o instanceof io.github.yhavinga.regelspraak.facts.Strandvondst s
                    ? "S:" + s.getVondstnummer().get(0).getWaarde()
                    : "W:" + ((io.github.yhavinga.regelspraak.facts.Wrakvondst) o).getVondstnummer().get(0).getWaarde())),

        // §8.1.6 ever-uniqueness, COMPOUND key (reekscode scalar via gelijkWaarde, volgnummer temporal
        // via rsOoitBotst, combined in one arity-2 source): the (4,3)-tuple collides across different
        // moments; (9,3) shares only the volgnummer and stays unique. Guards the exec-model compound
        // path differentially (the firing test alone proves only the DRL route).
        new FoutFixture("uniciteit-unie: samengestelde sleutel, temporele component over momenten",
            """
            Objecttype de Strandvondst
                de reekscode Numeriek (geheel getal);
                het volgnummer Numeriek (geheel getal) voor elk jaar;
            Objecttype de Wrakvondst
                de reekscode Numeriek (geheel getal);
                het volgnummer Numeriek (geheel getal) voor elk jaar;
            Regel reeks uniek
                geldig altijd
                    De concatenatie van reekscode en volgnummer van alle Strandvondsten verenigd met de concatenatie van reekscode en volgnummer van alle Wrakvondsten moeten uniek zijn.
            """,
            () -> { io.github.yhavinga.regelspraak.facts.Strandvondst s1 = new io.github.yhavinga.regelspraak.facts.Strandvondst();
                    s1.setReekscode(new BigDecimal("4")); s1.setVolgnummer(List.of(seg(2024, 1, 1, 2025, 1, 1, "3")));
                    io.github.yhavinga.regelspraak.facts.Wrakvondst w1 = new io.github.yhavinga.regelspraak.facts.Wrakvondst();
                    w1.setReekscode(new BigDecimal("4")); w1.setVolgnummer(List.of(seg(2026, 1, 1, 2027, 1, 1, "3")));
                    io.github.yhavinga.regelspraak.facts.Wrakvondst w2 = new io.github.yhavinga.regelspraak.facts.Wrakvondst();
                    w2.setReekscode(new BigDecimal("9")); w2.setVolgnummer(List.of(seg(2024, 1, 1, 2025, 1, 1, "3")));
                    return List.of(s1, w1, w2); },
            o -> (o instanceof io.github.yhavinga.regelspraak.facts.Strandvondst s
                    ? "S:" + s.getReekscode()
                    : "W:" + ((io.github.yhavinga.regelspraak.facts.Wrakvondst) o).getReekscode())),
    };

    // ---- Phase E fout: §9.7.6 Tabel 19 — an empty weight (naar rato) or a leeg/negatief maximum at ANY recipient is
    //      a Fout, surfaced as a SEPARATE insert-only Inconsistentie (the distribution still runs / degrades). Observe
    //      the SET of flagged pots; subjectKey = the pot's totaal (distinguishing the fout-pot from the goede). ----
    static final FoutFixture[] PHASE_E_FOUT = {
        // empty weight: the fout-pot (120) has a weightless recipient -> flagged; the goede pot (100) is fully weighted.
        new FoutFixture("verdeling naar rato: leeg gewicht (alleen de fout-pot gemeld)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel rato fout
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld naar rato van de gewicht.
            """,
            () -> {
                Pot fout = new Pot(); fout.setTotaal(new BigDecimal(120));
                Pot goed = new Pot(); goed.setTotaal(new BigDecimal(100));
                return List.of(fout, deeln(fout, "1", null, null), deeln(fout, "2", null, null), deeln(fout, null, null, null),
                               goed, deeln(goed, "1", null, null), deeln(goed, "1", null, null));
            },
            o -> "totaal=" + ((Pot) o).getTotaal()),

        // leeg/negatief maximum: the fout-pot (1200) has a no-maximum and a negative-maximum recipient -> flagged; the
        // goede pot (120) has only valid maxima. (All recipients are weighted, so the empty-weight fout does not fire.)
        new FoutFixture("verdeling naar rato: leeg of negatief maximum (alleen de fout-pot)",
            """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de maximum Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel rato max fout
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - naar rato van de gewicht,
                    - met een maximum van de maximum.
            """,
            () -> {
                Pot fout = new Pot(); fout.setTotaal(new BigDecimal(1200));
                Pot goed = new Pot(); goed.setTotaal(new BigDecimal(120));
                return List.of(fout, deeln(fout, "3", null, null), deeln(fout, "2", null, "-5"), deeln(fout, "1", null, "300"),
                               goed, deeln(goed, "1", null, "300"));
            },
            o -> "totaal=" + ((Pot) o).getTotaal()),
    };

    private void runDifferential(String label, Fixture[] fixtures, int minDiffed) { runDifferential(label, fixtures, minDiffed, Map.of()); }
    private void runDifferential(String label, Fixture[] fixtures, int minDiffed, Map<String, String> toegestaneDivergenties) {
        int diffed = 0, skipped = 0;
        StringBuilder skips = new StringBuilder();
        var gebruikt = new java.util.HashSet<String>();
        for (Fixture f : fixtures) {
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());

            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            manifestDiff(label, f.naam(), m, em, toegestaneDivergenties, gebruikt);
            if (!em.overgeslagen().isEmpty()) {     // not yet migrated -> explicit skip, not a divergence
                skipped++; skips.append("\n  SKIP ").append(f.naam()).append(": ").append(em.overgeslagen());
                continue;
            }
            KieBase drlBase = drlBase(m), modelBase = KieBaseBuilder.createKieBaseFromModel(em.model());
            Object viaDrl   = fire(drlBase, f);
            Object viaModel = fire(modelBase, f);
            assertEquals(eq(viaDrl), eq(viaModel),
                    () -> "back-ends DIVERGE on '" + f.naam() + "': DRL=" + viaDrl + " model=" + viaModel);
            diffed++;
        }
        // the phase must actually exercise the model path (not silently skip everything)
        final int agreed = diffed;
        assertTrue(agreed >= minDiffed, () -> "verwacht >=" + minDiffed + " gediffte " + label + " fixtures, kreeg " + agreed + skips);
        eisAllowlistGebruikt(label, toegestaneDivergenties, gebruikt);
        System.out.println("[differential] " + label + ": " + agreed + " agreed, " + skipped + " skipped" + skips);
    }

    /** manifest parity: the two back-ends' {@link Dekking} manifests must agree that every construct is
     *  LOWERED on both sides; ANY non-LOWERED verdict — a one-sided capability divergence, but also a
     *  both-sides MARKER/SKIP (which the retired skip-count cap could mask as "agreement": the
     *  §differential-masks-shared-bugs lesson, a shared upstream regression skips both back-ends at
     *  once) — is legal only with an entry in the allowlist, keyed {@code "<fixture> :: <soort>: <naam>"}
     *  and valued with the REASON. This names the construct a regression touches, where the old bare
     *  skip-count cap could only say "one more". */
    private static void manifestDiff(String label, String fixtureNaam, RegelSpraakModel m, ExecModelTranspiler em,
                                     Map<String, String> toegestaan, java.util.Set<String> gebruikt) {
        var drlPerSoort  = perSoort(DrlTranspiler.build(m).dekking());
        var execPerSoort = perSoort(em.dekking());
        assertEquals(drlPerSoort.keySet(), execPerSoort.keySet(),
                () -> label + " '" + fixtureNaam + "': manifest-soorten verschillen tussen de back-ends");
        for (String soort : drlPerSoort.keySet()) {
            List<Dekking> dl = drlPerSoort.get(soort), el = execPerSoort.get(soort);
            assertEquals(dl.size(), el.size(), () -> label + " '" + fixtureNaam + "': manifest-lengte (" + soort + ") verschilt");
            for (int i = 0; i < dl.size(); i++) {
                Dekking d = dl.get(i), e = el.get(i);
                assertEquals(d.sleutel(), e.sleutel(), () -> label + " '" + fixtureNaam + "': manifest-sleutels verschillen");
                if (d.lowered() && e.lowered()) continue;
                String key = fixtureNaam + " :: " + d.sleutel();
                if (toegestaan.containsKey(key)) { gebruikt.add(key); continue; }
                fail(label + " niet-gelowerde constructie zonder allowlist-reden: " + key
                        + " — DRL=" + d.verdict() + " exec=" + e.verdict()
                        + " (" + (d.lowered() ? e.reden() : d.reden()) + ")");
            }
        }
    }

    /** Group a manifest by soort (order preserved): both back-ends walk the same model lists per soort, so the
     *  per-soort sequences align positionally even when construct names repeat (e.g. §4.2 rule versions). */
    private static Map<String, List<Dekking>> perSoort(List<Dekking> manifest) {
        return manifest.stream().collect(java.util.stream.Collectors.groupingBy(
                Dekking::soort, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
    }

    /** A divergence that disappears (the exec back-end learned the construct) must be REMOVED from the
     *  allowlist consciously — a stale entry would silently re-admit the divergence later. */
    private static void eisAllowlistGebruikt(String label, Map<String, String> toegestaan, java.util.Set<String> gebruikt) {
        assertEquals(toegestaan.keySet(), gebruikt, () -> label + ": allowlist-entries zonder waargenomen divergentie — "
                + "de divergentie is verdwenen (mooi!): verwijder de entry zodat ze niet stil kan terugkeren");
    }

    /** As {@link #runDifferential} but the observable is the SET of inserted Inconsistentie facts (Phase D). */
    private void runFoutDifferential(String label, FoutFixture[] fixtures, int minDiffed) { runFoutDifferential(label, fixtures, minDiffed, Map.of()); }
    private void runFoutDifferential(String label, FoutFixture[] fixtures, int minDiffed, Map<String, String> toegestaneDivergenties) {
        int diffed = 0, skipped = 0;
        StringBuilder skips = new StringBuilder();
        var gebruikt = new java.util.HashSet<String>();
        for (FoutFixture f : fixtures) {
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            manifestDiff(label, f.naam(), m, em, toegestaneDivergenties, gebruikt);
            if (!em.overgeslagen().isEmpty()) {
                skipped++; skips.append("\n  SKIP ").append(f.naam()).append(": ").append(em.overgeslagen());
                continue;
            }
            List<String> viaDrl   = fireFout(drlBase(m), f);
            List<String> viaModel = fireFout(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(viaDrl, viaModel,
                    () -> "back-ends DIVERGE on '" + f.naam() + "': DRL flagged=" + viaDrl + " model flagged=" + viaModel);
            diffed++;
        }
        final int agreed = diffed;
        assertTrue(agreed >= minDiffed, () -> "verwacht >=" + minDiffed + " gediffte " + label + " fixtures, kreeg " + agreed + skips);
        eisAllowlistGebruikt(label, toegestaneDivergenties, gebruikt);
        System.out.println("[differential] " + label + ": " + agreed + " agreed, " + skipped + " skipped" + skips);
    }

    /** As {@link #runDifferential} but the observable is the SET of NEW objects an objectcreatie inserted (Phase G). */
    private void runObjDifferential(String label, ObjFixture[] fixtures, int minDiffed) { runObjDifferential(label, fixtures, minDiffed, Map.of()); }
    private void runObjDifferential(String label, ObjFixture[] fixtures, int minDiffed, Map<String, String> toegestaneDivergenties) {
        int diffed = 0, skipped = 0;
        StringBuilder skips = new StringBuilder();
        var gebruikt = new java.util.HashSet<String>();
        for (ObjFixture f : fixtures) {
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            manifestDiff(label, f.naam(), m, em, toegestaneDivergenties, gebruikt);
            if (!em.overgeslagen().isEmpty()) {
                skipped++; skips.append("\n  SKIP ").append(f.naam()).append(": ").append(em.overgeslagen());
                continue;
            }
            List<String> viaDrl   = fireCreated(drlBase(m), f);
            List<String> viaModel = fireCreated(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(viaDrl, viaModel,
                    () -> "back-ends DIVERGE on '" + f.naam() + "': DRL created=" + viaDrl + " model created=" + viaModel);
            diffed++;
        }
        final int agreed = diffed;
        assertTrue(agreed >= minDiffed, () -> "verwacht >=" + minDiffed + " gediffte " + label + " fixtures, kreeg " + agreed + skips);
        eisAllowlistGebruikt(label, toegestaneDivergenties, gebruikt);
        System.out.println("[differential] " + label + ": " + agreed + " agreed, " + skipped + " skipped" + skips);
    }

    /** Fire and return the SORTED canonical descriptors of every NEW object inserted (not among the seeded facts) —
     *  comparable across the two back-ends' fresh fact instances (the canon maps a back-reference to its seed index). */
    private List<String> fireCreated(KieBase kb, ObjFixture f) {
        KieSession s = sessie(kb);
        f.globals().forEach(s::setGlobal);
        List<Object> facts = f.facts().get();
        java.util.Set<Object> seeded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        seeded.addAll(facts);
        for (Object o : facts) s.insert(o);
        s.fireAllRules();
        List<String> created = s.getObjects().stream()
                .filter(o -> !seeded.contains(o))
                // §9 objectcreatie idempotence: a CreationMarker is a per-(rule,subject) control fact the
                // creation rule inserts alongside $new — it is not a created domain object, so it never
                // reaches the fixture canon (which casts to the created type). Filter it out of the observed set.
                .filter(o -> !(o instanceof io.github.yhavinga.regelspraak.runtime.CreationMarker))
                .map(o -> f.canon().apply(o, facts))
                .sorted().toList();
        s.dispose();
        return created;
    }

    /** Stable per-instance key for a §8.1.6 "verenigd met" Inconsistentie subject across the four naam-carrying fact
     *  types — the subject identity differs between the two back-ends, its "Type:naam" descriptor does not. */
    private static String unieKey(Object o) {
        if (o instanceof io.github.yhavinga.regelspraak.facts.Cirkel c) return "Cirkel:" + c.getNaam();
        if (o instanceof io.github.yhavinga.regelspraak.facts.Vierkant v) return "Vierkant:" + v.getNaam();
        if (o instanceof io.github.yhavinga.regelspraak.facts.Bestemming b) return "Bestemming:" + b.getNaam();
        return "Contingent:" + ((io.github.yhavinga.regelspraak.facts.Contingent) o).getNaam();
    }

    /** Fire and return the SORTED "regel/<subject-key>" descriptors of every inserted Inconsistentie — comparable
     *  across the two back-ends' fresh fact instances (the subject identity differs, the key does not). */
    private List<String> fireFout(KieBase kb, FoutFixture f) {
        KieSession s = sessie(kb);
        f.globals().forEach(s::setGlobal);
        List<Object> facts = f.facts().get();
        for (Object o : facts) s.insert(o);
        s.fireAllRules();
        List<String> flagged = s.getObjects().stream()
                .filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getRegel() + "/" + f.subjectKey().apply(((Inconsistentie) o).getSubject()))
                .sorted().toList();
        s.dispose();
        return flagged;
    }

    // ---- harness plumbing ----
    /** The §9.8 dagsoort-predicate globals each DRL KieBase declares (single-runtime principle: the predicates are compiled once
     *  by kern.Dagsoorten and supplied as session globals, like the parameter globals) — {@link #sessie} sets
     *  them on every session over that base. Exec-model bases are absent (their predicates are baked in). */
    private final java.util.IdentityHashMap<KieBase, Map<String, java.util.function.Predicate<java.time.LocalDate>>>
            dagsoortenPerBase = new java.util.IdentityHashMap<>();

    private KieBase drlBase(RegelSpraakModel m) {
        // the fact-model contract (drift test): every model this harness FIRES must honour the bean contract the
        // hand-written facts implement — the same derivation FactModelGenerator renders. A fixture whose
        // model implies a member the bean pool lacks fails here with the full list, not with one
        // reflective fire-time abort.
        java.util.List<String> schendingen = io.github.yhavinga.regelspraak.schema.ContractValidator.valideer(m, getClass().getClassLoader());
        assertTrue(schendingen.isEmpty(), () -> "bean-model drift (" + schendingen.size() + "):\n  " + String.join("\n  ", schendingen));
        DrlTranspiler t = DrlTranspiler.build(m);
        String drl = t.drl();
        KieHelper h = new KieHelper();
        h.addContent(drl, ResourceType.DRL);
        assertFalse(h.verify().hasMessages(org.kie.api.builder.Message.Level.ERROR),
                () -> "DRL did not compile:\n" + h.verify().getMessages() + "\n---\n" + drl);
        KieBase kb = h.build();
        if (!t.dagsoortGlobalen().isEmpty()) dagsoortenPerBase.put(kb, t.dagsoortGlobalen());
        return kb;
    }

    /** A session over {@code kb} with its declared dagsoort-predicate globals (if any) already set. */
    private KieSession sessie(KieBase kb) {
        KieSession s = kb.newKieSession();
        dagsoortenPerBase.getOrDefault(kb, Map.of()).forEach(s::setGlobal);
        return s;
    }

    private Object fire(KieBase kb, Fixture f) {
        KieSession s = sessie(kb);
        f.globals().forEach(s::setGlobal);
        List<Object> facts = f.facts().get();
        for (Object o : facts) s.insert(o);
        s.fireAllRules();
        s.dispose();
        return f.uit().apply(facts);
    }

    // ---- §9.6 INITIALISATIE: assigns only when the doel is leeg. Each fixture asserts the ABSOLUTE spec value:
    //      the leeg-guard means a pre-filled doel stays, and a berekening on the same doel wins under BOTH
    //      declaration orders (without the guard the outcome is agenda-order last-writer-wins). ----
    static final String INIT_HDR = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het basistarief Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
            """;
    static final String INIT_REGEL = """
            Regel initialiseer prijs
                geldig altijd
                    De prijs van een Passagier moet geïnitialiseerd worden op 10.
            """;
    static final String INIT_BEREKEN = """
            Regel bereken prijs
                geldig altijd
                    De prijs van een Passagier moet berekend worden als zijn basistarief plus 5
                    indien zijn basistarief groter is dan 50.
            """;
    static final Fixture[] PHASE_INIT = {
        single("initialisatie vult een leeg doel", INIT_HDR + INIT_REGEL,
            () -> passagier(p -> {}), Map.of(), Passagier::getPrijs),
        single("initialisatie laat een gevuld doel staan", INIT_HDR + INIT_REGEL,
            () -> passagier(p -> p.setPrijs(new BigDecimal(42))), Map.of(), Passagier::getPrijs),
        single("berekening wint (initialisatie eerst gedeclareerd)", INIT_HDR + INIT_REGEL + INIT_BEREKEN,
            () -> passagier(p -> p.setBasistarief(new BigDecimal(100))), Map.of(), Passagier::getPrijs),
        single("berekening wint (initialisatie laatst gedeclareerd)", INIT_HDR + INIT_BEREKEN + INIT_REGEL,
            () -> passagier(p -> p.setBasistarief(new BigDecimal(100))), Map.of(), Passagier::getPrijs),
        single("berekening n.v.t. -> initialisatie vult", INIT_HDR + INIT_BEREKEN + INIT_REGEL,
            () -> passagier(p -> p.setBasistarief(new BigDecimal(40))), Map.of(), Passagier::getPrijs),
    };
    static final BigDecimal[] PHASE_INIT_EXPECTED = {
        new BigDecimal(10), new BigDecimal(42), new BigDecimal(105), new BigDecimal(105), new BigDecimal(10),
    };
    @Test void phase_INIT_initialisatie_leeg_guard_is_spec_correct() {
        for (int i = 0; i < PHASE_INIT.length; i++) {
            Fixture f = PHASE_INIT[i];
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(eq(PHASE_INIT_EXPECTED[i]), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ---- FRESHNESS: an UNCONDITIONED chained berekening must recompute when its input arrives. The DRL
    //      pattern used to listen to nothing (empty property-reactivity mask), so "prijs = basistarief + 5"
    //      computed once against leeg and kept rsNul(leeg)+5 = 5 after a sibling rule wrote basistarief —
    //      the TOKA toka-45 -> toka-24 staleness the end-to-end test caught. Now the DRL @watch-es the read
    //      attributes and writes idempotently (fixpoint); the exec was already class-reactive + idempotent.
    //      Absolute value asserted (42 + 5 = 47), not just parity — both stale would otherwise mask it. ----
    @Test void unconditioned_chain_recomputes_when_input_arrives() {
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de leeftijd Numeriek (geheel getal);
                het basistarief Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
            Regel stel basistarief
                geldig altijd
                    Het basistarief van een Passagier moet gesteld worden op zijn leeftijd.
            Regel bereken prijs
                geldig altijd
                    De prijs van een Passagier moet berekend worden als zijn basistarief plus 5.
            """;
        Fixture f = single("unconditioned chain (basistarief -> prijs)", src,
                () -> passagier(p -> p.setLeeftijd(new BigDecimal(42))), Map.of(), Passagier::getPrijs);
        RegelSpraakModel m = Resolver.resolve(f.src());
        assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
        Object viaDrl = fire(drlBase(m), f);
        Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
        assertEquals(eq(new BigDecimal(47)), eq(viaDrl), () -> "DRL (oracle) wrong vs spec on '" + f.naam() + "'");
        assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
    }

    // ---- NAVIGATED FRESHNESS (the order-fragility of navigated reads — the second-most-bitten defect after the
    //      accumulate reverse). A spiegel-regel whose RHS NAVIGATES to another object's attribute ("de prijs van een
    //      Passagier ... als de totaalprijs van zijn reis") reads a fact (the Vlucht B) that a SIBLING rule computes
    //      later in the SAME fire-cycle. The freshness watch only ever collected the subject's OWN reads (eigenReads
    //      skips a navigated ref), so the spiegel pattern was EXPR-LESS -> not reactive to B's update -> it computed
    //      once against a null totaalprijs and kept the stale value when B.totaalprijs landed. The producer chain on B
    //      is 2 hops deep (luchthaven -> vertrektijd -> totaalprijs) so the spiegel reliably fires FIRST, against the
    //      still-null navigated value — the downstream reproducer (a producing chain that gains a hop). Fix: the
    //      navigated read makes the subject pattern reactive to the FAR type's update (a watch on the read property /
    //      class-reactivity), mirroring the DRL back-end. Absolute value asserted (100), not just parity — both stale
    //      would read null and a pure parity would mask it. ----
    @Test void navigated_spiegel_recomputes_when_far_side_lands() {
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
            Objecttype de Vlucht (mv: Vluchten)
                de luchthaven Numeriek (geheel getal);
                de vertrektijd Numeriek (geheel getal);
                de totaalprijs Numeriek (geheel getal);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel spiegel prijs
                geldig altijd
                    De prijs van een Passagier moet berekend worden als de totaalprijs van zijn reis.
            Regel bereken totaalprijs
                geldig altijd
                    De totaalprijs van een Vlucht moet berekend worden als de vertrektijd van een Vlucht plus 10.
            Regel bereken vertrektijd
                geldig altijd
                    De vertrektijd van een Vlucht moet berekend worden als de luchthaven van een Vlucht plus 40.
            """;
        Fixture f = new Fixture("navigated spiegel (totaalprijs van zijn reis)", src,
                () -> { Vlucht v = new Vlucht(); v.setLuchthaven(new BigDecimal(50));
                        Passagier p = new Passagier(); p.setReis(v);
                        return List.<Object>of(p, v); },
                Map.of(),
                l -> ((Passagier) l.get(0)).getPrijs());   // 50 -> +40 -> 90 -> +10 -> 100
        RegelSpraakModel m = Resolver.resolve(f.src());
        assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
        Object viaDrl = fire(drlBase(m), f);
        Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
        assertEquals(eq(new BigDecimal(100)), eq(viaDrl), () -> "DRL (oracle) stale navigated read on '" + f.naam() + "'");
        assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
    }

    // ---- INVERSE-ROL navigated freshness (the dgl-05 shape): the spiegel navigates the INVERSE role of a 1-1
    //      feittype — a Vlucht reads an attribute of its Passagier ("de totaalprijs van een Vlucht ... als de prijs
    //      van zijn passagier"), where the Passagier attribute is itself derived by a 2-hop producer chain on the
    //      Passagier side. The mirror fires on stale data once the producing chain gains a hop, for the same reason:
    //      the far-side (Passagier) update did not re-trigger the Vlucht pattern. Absolute value asserted (100). ----
    @Test void inverse_rol_spiegel_recomputes_when_far_side_lands() {
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het basistarief Numeriek (geheel getal);
                de bodem Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
            Objecttype de Vlucht (mv: Vluchten)
                de totaalprijs Numeriek (geheel getal);
            Feittype Vlucht met passagier
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft één passagier
            Regel spiegel totaalprijs
                geldig altijd
                    De totaalprijs van een Vlucht moet berekend worden als de prijs van zijn passagier.
            Regel bereken prijs
                geldig altijd
                    De prijs van een Passagier moet berekend worden als de bodem van een Passagier plus 10.
            Regel bereken bodem
                geldig altijd
                    De bodem van een Passagier moet berekend worden als het basistarief van een Passagier plus 40.
            """;
        Fixture f = new Fixture("inverse-rol spiegel (prijs van zijn passagier)", src,
                () -> { Vlucht v = new Vlucht(); Passagier p = new Passagier(); p.setBasistarief(new BigDecimal(50));
                        p.setReis(v); v.setPassagier(p);
                        return List.<Object>of(v, p); },
                Map.of(),
                l -> ((Vlucht) l.get(0)).getTotaalprijs());   // 50 -> +40 -> 90 -> +10 -> 100
        RegelSpraakModel m = Resolver.resolve(f.src());
        assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
        Object viaDrl = fire(drlBase(m), f);
        Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
        assertEquals(eq(new BigDecimal(100)), eq(viaDrl), () -> "DRL (oracle) stale inverse-rol read on '" + f.naam() + "'");
        assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
    }

    // ---- KOPPELTEKEN (card V1): a hyphenated attribute name must resolve the SAME bean getter on BOTH back-ends.
    //      kern.Namen.ident splits on EVERY non-letter/digit run, so "de datum-tijd voor het berekenen" ->
    //      DatumTijdVoorHetBerekenen and the FactModelGenerator wrote getDatumTijdVoorHetBerekenen. The exec/runtime
    //      read used to derive its accessor name via a whitespace-ONLY split (RegelSpraakRuntime.pascal), leaving the
    //      "-" in -> getDatum-tijd… -> a getter mismatch that made toka-22/25 non-compilable under exec. pascal now
    //      DELEGATES to kern.Namen.ident, so both back-ends target the one bean getter. This fixture is the toka-22
    //      shape shrunk to a precompiled facts.Heffing bean carrying the three koppelteken DatumTijd members: "de
    //      eerste van" the two vertrek moments -> the hyphenated result. The daadwerkelijke (09:30) precedes the
    //      verwachte (10:00), so the earliest = 09:30 — an ABSOLUTE value asserted (a shared bug a pure parity would
    //      mask), plus DRL==exec parity. Without the fix the exec build would throw on the missing getter. ----
    @Test void phase_KOPPELTEKEN_hyphenated_attribute_resolves_same_getter_both_back_ends() {
        String src = """
            Objecttype de Heffing (mv: Heffingen)
                de verwachte datum-tijd van vertrek Datum en tijd in millisecondes;
                de daadwerkelijke datum-tijd van vertrek Datum en tijd in millisecondes;
                de datum-tijd voor het berekenen Datum en tijd in millisecondes;
            Regel R
                geldig altijd
                    De datum-tijd voor het berekenen van een Heffing moet berekend worden als de eerste van de verwachte datum-tijd van vertrek van een Heffing en de daadwerkelijke datum-tijd van vertrek van een Heffing.
            """;
        LocalDateTime verwacht = LocalDateTime.of(2024, 6, 15, 10, 0);
        LocalDateTime echt = LocalDateTime.of(2024, 6, 15, 9, 30);
        Fixture f = new Fixture("koppelteken: eerste van twee datum-tijden", src,
                () -> { Heffing h = new Heffing();
                        h.setVerwachteDatumTijdVanVertrek(verwacht);
                        h.setDaadwerkelijkeDatumTijdVanVertrek(echt);
                        return List.<Object>of(h); },
                Map.of(),
                l -> ((Heffing) l.get(0)).getDatumTijdVoorHetBerekenen());
        RegelSpraakModel m = Resolver.resolve(f.src());
        assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen (koppelteken-getter?): " + em.overgeslagen());
        Object viaDrl = fire(drlBase(m), f);
        Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
        assertEquals(echt, viaDrl, () -> "DRL (oracle) wrong vs spec (eerste-van) on '" + f.naam() + "'");
        assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
    }

    // ---- §9.2 NEGATION-STRATIFICATION (the fuzzer's minimised reproducer, PERMANENT differential fixture). A §13.4.5
    //      kenmerktoekenning is MONOTONIC: a kenmerk is default-onwaar and a toekenning only ever makes it WAAR. So a
    //      rule N whose voorwaarde reads "niet <kenmerk>" must fire at the FIXPOINT, after every PRODUCER of that
    //      kenmerk. The DRL agenda gives no such guarantee: with N declared BEFORE its producer P, N was eligible in the
    //      transient window (bevoorrecht still onwaar), fired, and its once-true belast=WAAR persisted — a source-order
    //      dependent fixpoint (belast=true when N first, belast=false when P first). ExecModelTranspiler sinks such a
    //      consumer to SALIENCE -1; DrlTranspiler now emits the SAME salience, so both back-ends give belast=FALSE
    //      (bevoorrecht=true) under BOTH declaration orders. The fixture RUNS BOTH orders and asserts the ABSOLUTE
    //      spec value on each back-end (a pure DRL≡exec parity would pass while both are agenda-order wrong). ----
    static final String STRAT_HDR = """
            Objecttype de Bravo (mv: Bravos)
                het bedrag Numeriek (geheel getal);
                is bevoorrecht kenmerk (bijvoeglijk);
                is belast kenmerk (bijvoeglijk);
            """;
    static final String STRAT_P = """
            Regel P bevoorrecht
                geldig altijd
                    Een Bravo is bevoorrecht indien zijn bedrag groter is dan 2.
            """;
    static final String STRAT_N = """
            Regel N belast
                geldig altijd
                    Een Bravo is belast indien hij niet bevoorrecht is.
            """;
    @Test void phase_STRAT_negation_consumer_is_confluent_both_declaration_orders() {
        // bedrag 14 > 2 -> bevoorrecht WAAR -> "niet bevoorrecht" onwaar -> belast stays FALSE (the §9.2 fixpoint).
        record Order(String naam, String src) {}
        Order[] orders = {
            new Order("P (producent) eerst gedeclareerd", STRAT_HDR + STRAT_P + STRAT_N),
            new Order("N (negatie-consument) eerst gedeclareerd", STRAT_HDR + STRAT_N + STRAT_P),
        };
        for (Order o : orders) {
            Fixture f = new Fixture("stratificatie: " + o.naam(), o.src(),
                    () -> { Bravo b = new Bravo(); b.setBedrag(new BigDecimal(14)); return List.<Object>of(b); },
                    Map.of(),
                    l -> vals(((Bravo) l.get(0)).isBevoorrecht(), ((Bravo) l.get(0)).isBelast()));
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
            assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
            Object viaDrl = fire(drlBase(m), f);
            Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
            assertEquals(vals(true, false), eq(viaDrl),
                    () -> "DRL (oracle) agenda-order wrong (§9.2 stratificatie) on '" + f.naam() + "'");
            assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
        }
    }

    // ---- §13.4.17.26 REKENKUNDIGE AFRONDING op de halve cent (tie-breaking). 1/8 = 0,125 is exact-terminating, so no
    //      Breuk churn; rounded REKENKUNDIG (HALF_UP) to 2 decimalen the tie 0,125 goes UP to 0,13. A HALF_DOWN mutant
    //      would give 0,12. Both back-ends call the same runtime rounding helper (RoundingMode.HALF_UP), so the fixture
    //      asserts the ABSOLUTE spec value 0,13 (a pure parity would pass on a shared HALF_DOWN bug). ----
    @Test void phase_AFRONDING_rekenkundig_halve_cent_tie_rounds_up() {
        String src = STRAT_HDR.replace("het bedrag Numeriek (geheel getal);", "het bedrag Numeriek (getal met 2 decimalen);")
                .replace("    is bevoorrecht kenmerk (bijvoeglijk);\n    is belast kenmerk (bijvoeglijk);\n", "")
                + """
                Regel R afronding
                    geldig altijd
                        Het bedrag van een Bravo moet gesteld worden op 1 gedeeld door 8 rekenkundig afgerond op 2 decimalen.
                """;
        Fixture f = new Fixture("afronding: 1/8 = 0,125 rekenkundig -> 0,13", src,
                () -> List.<Object>of(new Bravo()), Map.of(),
                l -> ((Bravo) l.get(0)).getBedrag());
        RegelSpraakModel m = Resolver.resolve(f.src());
        assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
        Object viaDrl = fire(drlBase(m), f);
        Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
        assertEquals(eq(new BigDecimal("0.13")), eq(viaDrl), () -> "DRL (oracle) wrong rekenkundige afronding on '" + f.naam() + "'");
        assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
    }

    // ---- §5.5.5 / §8.1 KENMERK-DEFAULT: a kenmerk that is NEVER set is default-onwaar, so "niet <kenmerk>" is WAAR
    //      and a rule guarded on it MUST fire. The exec-model reads the kenmerk via !Boolean.FALSE.equals(...) (a null
    //      wrapper reads as onwaar); a Boolean.TRUE.equals mutant would read the unset kenmerk as onwaar-for-the-test
    //      and SKIP the fire. No producer of bevoorrecht exists here, so bevoorrecht stays default-onwaar and belast
    //      must become WAAR on both back-ends (absolute value, not parity). ----
    @Test void phase_KENMERK_DEFAULT_niet_unset_kenmerk_fires() {
        String src = STRAT_HDR + STRAT_N;   // only N: "belast indien hij niet bevoorrecht is", no producer of bevoorrecht
        Fixture f = new Fixture("kenmerk-default: niet <ongezet kenmerk> vuurt", src,
                () -> List.<Object>of(new Bravo()), Map.of(),
                l -> ((Bravo) l.get(0)).isBelast());
        RegelSpraakModel m = Resolver.resolve(f.src());
        assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
        ExecModelTranspiler em = ExecModelTranspiler.build(m);
        assertTrue(em.overgeslagen().isEmpty(), () -> f.naam() + " model overgeslagen: " + em.overgeslagen());
        Object viaDrl = fire(drlBase(m), f);
        Object viaModel = fire(KieBaseBuilder.createKieBaseFromModel(em.model()), f);
        assertEquals(Boolean.TRUE, viaDrl, () -> "DRL (oracle) did not fire on unset kenmerk on '" + f.naam() + "'");
        assertEquals(eq(viaDrl), eq(viaModel), () -> "back-ends DIVERGE on '" + f.naam() + "'");
    }

    /** Compare BigDecimals by value (scale-insensitive), everything else by equals — recursing into a {@link #vals}
     *  list so a multi-bean read compares element-wise (42 == 42.00, null stays null). */
    private static Object eq(Object o) {
        if (o instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object e : l) out.add(eq(e)); return out; }
        return o instanceof BigDecimal b ? b.stripTrailingZeros() : o;
    }

    /** A multi-value read-back (allows nulls, unlike List.of). */
    private static List<Object> vals(Object... vs) { return Arrays.asList(vs); }

    private static Passagier passagier(Consumer<Passagier> setup) {
        Passagier p = new Passagier(); setup.accept(p); return p;
    }
    private static Passagier pass(int leeftijd) { return passagier(p -> p.setLeeftijd(new BigDecimal(leeftijd))); }
    private static Passagier pass(int leeftijd, boolean kind) {
        return passagier(p -> { p.setLeeftijd(new BigDecimal(leeftijd)); p.setKind(kind); });
    }
}
