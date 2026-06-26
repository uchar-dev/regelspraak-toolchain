package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.facts.Bemanningslid;
import io.github.yhavinga.regelspraak.facts.Bestelling;
import io.github.yhavinga.regelspraak.facts.Contingent;
import io.github.yhavinga.regelspraak.facts.Deelnemer;
import io.github.yhavinga.regelspraak.facts.Factuur;
import io.github.yhavinga.regelspraak.facts.Heffing;
import io.github.yhavinga.regelspraak.facts.Inconsistentie;
import io.github.yhavinga.regelspraak.facts.Konto;
import io.github.yhavinga.regelspraak.facts.Pot;
import io.github.yhavinga.regelspraak.facts.Meting;
import io.github.yhavinga.regelspraak.facts.NatuurlijkPersoon;
import io.github.yhavinga.regelspraak.facts.Passagier;
import io.github.yhavinga.regelspraak.facts.Persoon;
import io.github.yhavinga.regelspraak.facts.Rekensom;
import io.github.yhavinga.regelspraak.facts.Segment;
import io.github.yhavinga.regelspraak.facts.Vlucht;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import org.drools.modelcompiler.KieBaseBuilder;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.math.BigDecimal;
import java.time.LocalDate;
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

        // (adversary, the null-asymmetry flagship) navigated ONGELIJK vs literal: mvel reads `reis!.x != 100` as TRUE
        // for a NULL reis (null != 100), so the partnerless passenger DOES fire — vergelijk's ONGELIJK matches
        // (aNull -> !(both-leeg)). Equal (100) -> no fire; different (80) and null -> fire. Both back-ends agree.
        new Fixture("navigated ONGELIJK vs literal (null reis still fires, mvel null != x)",
            NAV_HDR + "Regel R\n    geldig altijd\n"
            + "        Het basistarief van een passagier moet gesteld worden op 50 indien de vertrektijd van zijn reis ongelijk is aan 100.\n",
            () -> { Vlucht gelijk = new Vlucht(); gelijk.setVertrektijd(new BigDecimal(100));
                    Vlucht anders = new Vlucht(); anders.setVertrektijd(new BigDecimal(80));
                    return List.of(gelijk, anders, paxReis(gelijk), paxReis(anders), paxReis(null)); },
            Map.of(),
            l -> vals(((Passagier) l.get(2)).getBasistarief(), ((Passagier) l.get(3)).getBasistarief(),
                      ((Passagier) l.get(4)).getBasistarief())),   // null, 50, 50

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
            Map.of("sTANDAARDBELASTINGVERMINDERING", List.of(seg(2024, 1, 1, 2025, 1, 1, "12.00"), seg(2025, 1, 1, 2026, 1, 1, "18.00"))),
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
    };

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

    // ---- Phase 11 fixtures: §11/§13.4.2.10 "Daarbij geldt: X is <expressie>" local rule variables, INLINE-
    //      substituted at each VariabeleRef site (voorwaarde + resultaat). Both back-ends consume the same inlined
    //      IR, so this checks the substitution lowers + fires identically. ----
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
    // A pure differential can't catch an inline-substitution bug shared by both back-ends, so assert the absolute
    // spec result too: prijs=5,basistarief=20 -> A=5, A!=0 -> B=20/5=4 -> plafond=4+1=5; prijs=0 -> A=0 -> no fire.
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

    @Test void phase_11_daarbij_geldt_matches_drl_back_end()   { runDifferential("Phase 11", PHASE_11, 3); }

    /** §11.1: assert the DRL oracle against the absolute spec value (an inline-substitution bug shared by both
     *  back-ends would pass a pure differential), and that the model agrees. */
    @Test void phase_11_inline_substitution_is_spec_correct() {
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

    /** §3.6/§6.9 regression (Phase G adversarial review): a DimensieCel read over a NULL gedimensioneerd Map must
     *  yield leeg (null), not crash. The model is null-guarded (spec-correct); the DRL oracle is NOT (a latent
     *  ConsequenceException on a null Map — carded in backlog), so this is a MODEL-only robustness guard, not a
     *  differential (the back-ends cannot be compared where the oracle crashes). */
    @Test void phase_G_dimensiecel_over_null_map_is_null_safe_in_model() {
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
        KieSession s = KieBaseBuilder.createKieBaseFromModel(em.model()).newKieSession();
        NatuurlijkPersoon np = new NatuurlijkPersoon();   // betaaldeBelastingInJaar == null
        s.insert(np);
        s.fireAllRules();   // must NOT throw on the null Map
        s.dispose();
        assertNull(np.getBetaaldeBelastingOverDeAfgelopenVierJaar(), "null-Map DimensieCel -> leeg, niet crash");
    }

    @Test void phase_G_dimensie_matches_drl_back_end()             { runDifferential("Phase G dim", PHASE_G_DIM, 3); }
    @Test void phase_G_objectcreatie_matches_drl_back_end()        { runObjDifferential("Phase G obj", PHASE_G_OBJ, 2); }
    @Test void phase_G_feitcreatie_matches_drl_back_end()          { runDifferential("Phase G feit", PHASE_G_FEIT, 6); }
    @Test void phase_F4b_mixed_static_guard_matches_drl_back_end() { runDifferential("Phase F4b", PHASE_F4B, 4); }
    @Test void phase_F4_compound_gedurende_matches_drl_back_end()  { runDifferential("Phase F4", PHASE_F4, 6); }
    @Test void phase_F3_tijdsevenredig_matches_drl_back_end()      { runDifferential("Phase F3", PHASE_F3, 4); }
    @Test void phase_F2_aantal_dagen_in_matches_drl_back_end()     { runDifferential("Phase F2", PHASE_F2, 2); }
    @Test void phase_F_temporal_totaal_matches_drl_back_end()      { runDifferential("Phase F", PHASE_F, 10); }
    @Test void phase_82_gehele_periode_matches_drl_back_end()      { runDifferential("Phase 82", PHASE_82, 10); }
    @Test void phase_82_kenmerk_matches_drl_back_end()             { runDifferential("Phase 82 kenmerk", PHASE_82_KENMERK, 2); }
    @Test void phase_82_consistentie_matches_drl_back_end()        { runFoutDifferential("Phase 82 fout", PHASE_82_FOUT, 1); }
    @Test void phase_82_navigated_grens_matches_drl_back_end()     { runDifferential("Phase 82 nav", PHASE_82_NAV, 3); }
    @Test void phase_82_in_counting_quantifier_matches_drl_back_end() { runDifferential("Phase 82 counting", PHASE_82_COUNTING, 3); }
    @Test void phase_H_navigated_comparison_matches_drl_back_end()  { runDifferential("Phase H", PHASE_H, 17); }
    @Test void phase_H_versie_geldigheid_matches_drl_back_end()     { runDifferential("Phase H geldig", PHASE_GELDIG, 12); }
    @Test void phase_H_value_ops_matches_drl_back_end()             { runDifferential("Phase H val", PHASE_H_VAL, 8); }
    @Test void phase_H_builtin_functies_match_drl_back_end()        { runDifferential("Phase H func", PHASE_H_FUNC, 4); }
    @Test void phase_C2_aggregation_in_arithmetic_matches_drl_back_end() { runDifferential("Phase C2", PHASE_C2, 5); }
    @Test void beslistabel_rows_match_drl_back_end()          { runDifferential("Beslistabel", BESLISTABEL, 6); }
    @Test void phase_A_model_back_end_matches_drl_back_end()  { runDifferential("Phase A", PHASE_A, 3); }
    @Test void phase_B_model_back_end_matches_drl_back_end()  { runDifferential("Phase B", PHASE_B, 3); }
    @Test void phase_B2_quantifiers_match_drl_back_end()       { runDifferential("Phase B2", PHASE_B2, 5); }
    @Test void phase_B3_relational_match_drl_back_end()        { runDifferential("Phase B3", PHASE_B3, 6); }
    @Test void phase_B4_comparison_semantics_match_drl_back_end() { runDifferential("Phase B4", PHASE_B4, 7); }
    @Test void phase_B5_then_side_global_matches_drl_back_end()   { runDifferential("Phase B5", PHASE_B5, 4); }
    @Test void phase_C_aggregations_match_drl_back_end()          { runDifferential("Phase C", PHASE_C, 9); }
    @Test void phase_D_consistentie_matches_drl_back_end()        { runFoutDifferential("Phase D", PHASE_D, 11); }
    @Test void phase_H_consistentie_matches_drl_back_end()        { runFoutDifferential("Phase H fout", PHASE_H_FOUT, 7); }
    @Test void phase_div_value_matches_drl_back_end()             { runDifferential("Phase DIV", PHASE_DIV, 2); }
    @Test void phase_arith_value_matches_drl_back_end()           { runDifferential("Phase ARITH", PHASE_ARITH, 3); }
    @Test void phase_datum_arithmetic_matches_drl_back_end()      { runDifferential("Phase DATUM", PHASE_DATUM, 15); }
    @Test void phase_dagsoort_controle_matches_drl_back_end()     { runDifferential("Phase DAGSOORT", PHASE_DAGSOORT, 5); }
    @Test void phase_dagsoort_duur_matches_drl_back_end()         { runDifferential("Phase DAGSOORT DUUR", PHASE_DAGSOORT_DUUR, 5); }
    @Test void phase_dagsoort_tijdsduur_matches_drl_back_end()    { runDifferential("Phase DAGSOORT TDS", PHASE_DAGSOORT_TDS, 6); }
    @Test void phase_dagsoort_cross_en_datearith_matches_drl_back_end() { runDifferential("Phase DAGSOORT X", PHASE_DAGSOORT_X, 5); }
    // Phase E carries ONE principled skip: ">1 verdeling op hetzelfde doel" is agenda-order-dependent, so the model
    // deliberately does not lower it (a §differential-masked-skip the cap now pins at exactly 1).
    @Test void phase_E_verdeling_shares_match_drl_back_end()       { runDifferential("Phase E", PHASE_E, 9, 1); }
    @Test void phase_E_verdeling_fouten_match_drl_back_end()       { runFoutDifferential("Phase E fout", PHASE_E_FOUT, 2); }

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

        // §8.1.6 a TEMPORAL (List<Segment>) uniciteit key — DRL is NOT special-cased (`timeline == $k`, List.equals,
        // so only instance-equal timelines collide). p1/p2 SHARE the same List instance -> both flagged; p3 has a
        // distinct (content-equal) timeline -> matches only itself -> not flagged. The model's gelijkWaarde matches.
        new FoutFixture("uniciteit: temporal key (shared timeline instance collides)",
            "Objecttype de Persoon (mv: Personen) (bezield)\n    de leeftijd Numeriek (geheel getal);\n"
            + "    de bedrag per maand Numeriek (geheel getal) voor elke maand;\n"
            + "Regel uniek\n    geldig altijd\n        De bedrag per maand van alle Personen moeten uniek zijn.\n",
            () -> { List<Segment> shared = List.of(seg(2024, 1, 1, 2024, 6, 1, "100"));
                    Persoon p1 = new Persoon(); p1.setLeeftijd(new BigDecimal(1)); p1.setBedragPerMaand(shared);
                    Persoon p2 = new Persoon(); p2.setLeeftijd(new BigDecimal(2)); p2.setBedragPerMaand(shared);
                    Persoon p3 = new Persoon(); p3.setLeeftijd(new BigDecimal(3)); p3.setBedragPerMaand(List.of(seg(2024, 1, 1, 2024, 6, 1, "100")));
                    return List.of(p1, p2, p3); },
            o -> "lft=" + ((Persoon) o).getLeeftijd()),
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

    private void runDifferential(String label, Fixture[] fixtures, int minDiffed) { runDifferential(label, fixtures, minDiffed, 0); }
    private void runDifferential(String label, Fixture[] fixtures, int minDiffed, int expectedSkipped) {
        int diffed = 0, skipped = 0;
        StringBuilder skips = new StringBuilder();
        for (Fixture f : fixtures) {
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());

            ExecModelTranspiler em = ExecModelTranspiler.build(m);
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
        capSkipped(label, expectedSkipped, skipped, skips);
        System.out.println("[differential] " + label + ": " + agreed + " agreed, " + skipped + " skipped" + skips);
    }

    /** The §differential-masks-shared-bugs SKIPPED-CAP: a model-overgeslagen fixture is SKIPPED (not compared), so a
     *  regression that makes an ALREADY-MIGRATED path start skipping would otherwise be invisible (the count is only
     *  printed). Pin the EXACT number of intentional skips per phase: any NEW skip (a DRL-fires/model-skips gap on a
     *  migrated path) trips this, and a skip that newly disappears forces a conscious decrement — like a golden count. */
    private static void capSkipped(String label, int expected, int actual, StringBuilder skips) {
        assertEquals(expected, actual, () -> label + ": verwacht PRECIES " + expected + " overgeslagen (model) fixture(s), kreeg "
                + actual + " — een NIEUW overgeslagen pad (DRL vuurt, model skipt) is een regressie; pas het verwachte aantal "
                + "bewust aan als de wijziging gewenst is." + skips);
    }

    /** As {@link #runDifferential} but the observable is the SET of inserted Inconsistentie facts (Phase D). */
    private void runFoutDifferential(String label, FoutFixture[] fixtures, int minDiffed) { runFoutDifferential(label, fixtures, minDiffed, 0); }
    private void runFoutDifferential(String label, FoutFixture[] fixtures, int minDiffed, int expectedSkipped) {
        int diffed = 0, skipped = 0;
        StringBuilder skips = new StringBuilder();
        for (FoutFixture f : fixtures) {
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
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
        capSkipped(label, expectedSkipped, skipped, skips);
        System.out.println("[differential] " + label + ": " + agreed + " agreed, " + skipped + " skipped" + skips);
    }

    /** As {@link #runDifferential} but the observable is the SET of NEW objects an objectcreatie inserted (Phase G). */
    private void runObjDifferential(String label, ObjFixture[] fixtures, int minDiffed) { runObjDifferential(label, fixtures, minDiffed, 0); }
    private void runObjDifferential(String label, ObjFixture[] fixtures, int minDiffed, int expectedSkipped) {
        int diffed = 0, skipped = 0;
        StringBuilder skips = new StringBuilder();
        for (ObjFixture f : fixtures) {
            RegelSpraakModel m = Resolver.resolve(f.src());
            assertTrue(m.errors().isEmpty(), () -> f.naam() + " resolver-fout: " + m.errors());
            ExecModelTranspiler em = ExecModelTranspiler.build(m);
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
        capSkipped(label, expectedSkipped, skipped, skips);
        System.out.println("[differential] " + label + ": " + agreed + " agreed, " + skipped + " skipped" + skips);
    }

    /** Fire and return the SORTED canonical descriptors of every NEW object inserted (not among the seeded facts) —
     *  comparable across the two back-ends' fresh fact instances (the canon maps a back-reference to its seed index). */
    private List<String> fireCreated(KieBase kb, ObjFixture f) {
        KieSession s = kb.newKieSession();
        f.globals().forEach(s::setGlobal);
        List<Object> facts = f.facts().get();
        java.util.Set<Object> seeded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        seeded.addAll(facts);
        for (Object o : facts) s.insert(o);
        s.fireAllRules();
        List<String> created = s.getObjects().stream()
                .filter(o -> !seeded.contains(o))
                .map(o -> f.canon().apply(o, facts))
                .sorted().toList();
        s.dispose();
        return created;
    }

    /** Fire and return the SORTED "regel/<subject-key>" descriptors of every inserted Inconsistentie — comparable
     *  across the two back-ends' fresh fact instances (the subject identity differs, the key does not). */
    private List<String> fireFout(KieBase kb, FoutFixture f) {
        KieSession s = kb.newKieSession();
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
    private KieBase drlBase(RegelSpraakModel m) {
        String drl = DrlTranspiler.transpile(m);
        KieHelper h = new KieHelper();
        h.addContent(drl, ResourceType.DRL);
        assertFalse(h.verify().hasMessages(org.kie.api.builder.Message.Level.ERROR),
                () -> "DRL did not compile:\n" + h.verify().getMessages() + "\n---\n" + drl);
        return h.build();
    }

    private Object fire(KieBase kb, Fixture f) {
        KieSession s = kb.newKieSession();
        f.globals().forEach(s::setGlobal);
        List<Object> facts = f.facts().get();
        for (Object o : facts) s.insert(o);
        s.fireAllRules();
        s.dispose();
        return f.uit().apply(facts);
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
