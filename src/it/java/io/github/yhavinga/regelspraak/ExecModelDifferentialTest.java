package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.facts.Inconsistentie;
import io.github.yhavinga.regelspraak.facts.Konto;
import io.github.yhavinga.regelspraak.facts.Meting;
import io.github.yhavinga.regelspraak.facts.NatuurlijkPersoon;
import io.github.yhavinga.regelspraak.facts.Passagier;
import io.github.yhavinga.regelspraak.facts.Persoon;
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

    @Test void phase_A_model_back_end_matches_drl_back_end()  { runDifferential("Phase A", PHASE_A, 3); }
    @Test void phase_B_model_back_end_matches_drl_back_end()  { runDifferential("Phase B", PHASE_B, 3); }
    @Test void phase_B2_quantifiers_match_drl_back_end()       { runDifferential("Phase B2", PHASE_B2, 5); }
    @Test void phase_B3_relational_match_drl_back_end()        { runDifferential("Phase B3", PHASE_B3, 5); }
    @Test void phase_B4_comparison_semantics_match_drl_back_end() { runDifferential("Phase B4", PHASE_B4, 7); }
    @Test void phase_B5_then_side_global_matches_drl_back_end()   { runDifferential("Phase B5", PHASE_B5, 3); }
    @Test void phase_C_aggregations_match_drl_back_end()          { runDifferential("Phase C", PHASE_C, 8); }
    @Test void phase_D_consistentie_matches_drl_back_end()        { runFoutDifferential("Phase D", PHASE_D, 4); }

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
