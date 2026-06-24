package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.facts.Deelnemer;
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
                    Een Vlucht is vol indien hij heeft een passagier.
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

        // §8.1.7 near-side rolcheck "hij is een passagier" -> "reis != null": fires only for a passagier with a reis.
        new Fixture("near-side rolcheck (hij is een passagier)",
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
                    Een passagier is minderjarig indien hij is een passagier.
            """,
            () -> {
                Vlucht v = new Vlucht();
                Passagier p1 = new Passagier(); p1.setReis(v);   // fills the role
                Passagier p2 = new Passagier();                  // no reis
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
        // (overgeslagen), so the harness logs a skip rather than a divergence. (DRL would fire it.)
        new Fixture("THEN value over two globals -> honest skip",
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
            l -> ((Passagier) l.get(0)).getPrijs()),
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
    };

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
    };

    @Test void phase_F3_tijdsevenredig_matches_drl_back_end()      { runDifferential("Phase F3", PHASE_F3, 3); }
    @Test void phase_F2_aantal_dagen_in_matches_drl_back_end()     { runDifferential("Phase F2", PHASE_F2, 2); }
    @Test void phase_F_temporal_totaal_matches_drl_back_end()      { runDifferential("Phase F", PHASE_F, 6); }
    @Test void phase_C2_aggregation_in_arithmetic_matches_drl_back_end() { runDifferential("Phase C2", PHASE_C2, 4); }
    @Test void phase_A_model_back_end_matches_drl_back_end()  { runDifferential("Phase A", PHASE_A, 3); }
    @Test void phase_B_model_back_end_matches_drl_back_end()  { runDifferential("Phase B", PHASE_B, 3); }
    @Test void phase_B2_quantifiers_match_drl_back_end()       { runDifferential("Phase B2", PHASE_B2, 5); }
    @Test void phase_B3_relational_match_drl_back_end()        { runDifferential("Phase B3", PHASE_B3, 5); }
    @Test void phase_B4_comparison_semantics_match_drl_back_end() { runDifferential("Phase B4", PHASE_B4, 7); }
    @Test void phase_B5_then_side_global_matches_drl_back_end()   { runDifferential("Phase B5", PHASE_B5, 3); }
    @Test void phase_C_aggregations_match_drl_back_end()          { runDifferential("Phase C", PHASE_C, 8); }
    @Test void phase_D_consistentie_matches_drl_back_end()        { runFoutDifferential("Phase D", PHASE_D, 7); }
    @Test void phase_div_value_matches_drl_back_end()             { runDifferential("Phase DIV", PHASE_DIV, 2); }
    @Test void phase_arith_value_matches_drl_back_end()           { runDifferential("Phase ARITH", PHASE_ARITH, 3); }
    @Test void phase_E_verdeling_shares_match_drl_back_end()       { runDifferential("Phase E", PHASE_E, 9); }
    @Test void phase_E_verdeling_fouten_match_drl_back_end()       { runFoutDifferential("Phase E fout", PHASE_E_FOUT, 2); }

    // ---- Phase D: §9.5/§13.4.8 consistentieregel — the VIOLATION inserts an Inconsistentie. The observable is the
    //      SET of flagged subjects (not a subject field), so a FoutFixture maps each inserted Inconsistentie to a
    //      stable "regel/<subject-key>" string the two back-ends can compare across their fresh fact instances. ----
    record FoutFixture(String naam, String src, Supplier<List<Object>> facts, Function<Object, String> subjectKey) {}
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
    };

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

    private void runDifferential(String label, Fixture[] fixtures, int minDiffed) {
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
        System.out.println("[differential] " + label + ": " + agreed + " agreed, " + skipped + " skipped" + skips);
    }

    /** As {@link #runDifferential} but the observable is the SET of inserted Inconsistentie facts (Phase D). */
    private void runFoutDifferential(String label, FoutFixture[] fixtures, int minDiffed) {
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
        System.out.println("[differential] " + label + ": " + agreed + " agreed, " + skipped + " skipped" + skips);
    }

    /** Fire and return the SORTED "regel/<subject-key>" descriptors of every inserted Inconsistentie — comparable
     *  across the two back-ends' fresh fact instances (the subject identity differs, the key does not). */
    private List<String> fireFout(KieBase kb, FoutFixture f) {
        KieSession s = kb.newKieSession();
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
