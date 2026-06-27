package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Drools back-end walks the resolved IR into DRL: a target object-type pattern
 * for the "when", a modify-block setter for the "then", parameters as globals, and
 * an explicit marker wherever the IR was left unresolved.
 */
class DrlTest {

    static final String SRC = """
        Parameter de grens : Numeriek (geheel getal)
        Objecttype de Reis
            de prijs Numeriek (geheel getal);
            de toeslag Numeriek (geheel getal);
        Regel verhoog prijs
            geldig altijd
                De prijs van een Reis moet gesteld worden op de toeslag van een Reis plus de grens
                indien de prijs van een Reis kleiner is dan de grens.
        """;

    @Test void rule_skeleton_en_globals() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(SRC));
        assertTrue(drl.contains("package io.github.yhavinga.regelspraak.generated;"), drl);
        assertTrue(drl.contains("global java.math.BigDecimal grens;"), drl);
        assertTrue(drl.contains("rule \"verhoog prijs\""), drl);
        assertTrue(drl.contains("$subject : Reis("), () -> "subject pattern ontbreekt:\n" + drl);
    }

    @Test void conditie_wordt_when_constraint() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(SRC));
        // "de prijs ... kleiner is dan de grens" -> prijs < grens
        assertTrue(drl.contains("prijs < grens"), () -> "constraint ontbreekt:\n" + drl);
    }

    @Test void toekenning_wordt_modify_setter() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(SRC));
        // "de prijs ... := de toeslag plus de grens" -> modify { setPrijs( ($subject.getToeslag() + grens) ) }
        assertTrue(drl.contains("modify($subject)"), () -> "modify-blok ontbreekt:\n" + drl);
        assertTrue(drl.contains("setPrijs("), () -> "setter ontbreekt:\n" + drl);
        assertTrue(drl.contains("rsNul($subject.getToeslag()) + rsNul(grens)"), () -> "waarde-expressie (leeg-veilige plus) ontbreekt:\n" + drl);
    }

    @Test void aggregaties_worden_accumulate_patronen() {
        String src = """
            Objecttype de Vlucht
                het totaalbedrag Numeriek (geheel getal);
                het aantal reizigers Numeriek (geheel getal);
            Objecttype de Persoon
                het bedrag Numeriek (geheel getal);
            Feittype Vlucht van personen
                de reis Vlucht
                de passagier Persoon
            Eén reis betreft meerdere passagiers
            Regel som
                geldig altijd
                    Het totaalbedrag van een reis moet berekend worden als de som van het bedrag van alle passagiers van de reis.
            Regel telling
                geldig altijd
                    Het aantal reizigers van een reis moet berekend worden als het aantal passagiers van de reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // sum over the passenger (element type via role), scoped to the subject by the
        // feittype join, result fed to the setter
        assertTrue(drl.contains("$e1 : Persoon( reis == $subject )"), () -> "accumulate-join ontbreekt:\n" + drl);
        assertTrue(drl.contains("sum( $e1.getBedrag() )"), () -> "sum-functie ontbreekt:\n" + drl);
        assertTrue(drl.contains("setTotaalbedrag(") && drl.contains("$acc1"), () -> "som-resultaat niet gebruikt:\n" + drl);
        // count
        assertTrue(drl.contains("count( $e1 )"), () -> "count-functie ontbreekt:\n" + drl);
    }

    @Test void navigatie_wordt_join_pad_in_waarde_en_voorwaarde() {
        // The subject navigates to a related object via the feittype role "reis":
        // value -> $subject.getReis().getVluchtdatum(); condition -> reis.vluchtdatum.
        String src = """
            Objecttype de Vlucht
                de vluchtdatum Datum in dagen;
            Objecttype de Persoon
                de reisdatum Datum in dagen;
            Feittype Vlucht van personen
                de reis Vlucht
                de passagier Persoon
            Eén reis betreft meerdere passagiers
            Regel kopieer datum
                geldig altijd
                    De reisdatum van een passagier moet gesteld worden op de vluchtdatum van de reis
                    indien de vluchtdatum van de reis later is dan de reisdatum van een passagier.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject : Persoon("), () -> "subject:\n" + drl);
        // value navigation through the role reference (getters)
        assertTrue(drl.contains("$subject.getReis().getVluchtdatum()"), () -> "waarde-navigatie:\n" + drl);
        // condition navigation as a null-safe property path on the pattern (Drools "!." — a null to-one
        // reference yields a leegwaarde instead of throwing; mvel "." is not null-safe)
        assertTrue(drl.contains("reis!.vluchtdatum > reisdatum"), () -> "voorwaarde-navigatie:\n" + drl);
    }

    @Test void navigatie_eenvorm_kenmerkcheck_bindt_alleen_de_zelfstandige_soort() {
        // §8.1.8.3 navigated EEN-form ("de reis van een Passagier is een <k>") binds a ZELFSTANDIG kenmerk of the
        // navigated type but NOT a bijvoeglijk one — the same soort gate the subject's own EEN-form uses. The
        // bijvoeglijk case stays an honest marker (a navigated role membership via "een" is out of scope), never a
        // silently-true gate.
        String src = """
            Objecttype de Vlucht
                is reis met paaskorting kenmerk;
                is internationaal kenmerk (bijvoeglijk);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                de toeslag Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel zelfstandig
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op 1
                    indien de reis van een Passagier een reis met paaskorting is.
            Regel bijvoeglijk
                geldig altijd
                    De toeslag van een Passagier moet gesteld worden op 2
                    indien de reis van een Passagier een internationaal is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("reis.reisMetPaaskorting == true"), () -> "navigated zelfstandig EEN-vorm niet gebonden:\n" + drl);
        assertFalse(drl.contains("reis.internationaal == true"), () -> "een bijvoeglijk navigated kenmerk mag de EEN-vorm NIET binden:\n" + drl);
        assertTrue(drl.contains("/* TODO voorwaarde: de reis van een Passagier een internationaal is */"),
                () -> "de bijvoeglijke navigated EEN-vorm moet een honest marker blijven:\n" + drl);
    }

    @Test void possessieve_navigated_kenmerkcheck_het_vorm_bindt_via_de_langste_rolprefix() {
        // §3.5 the possessive HET-form "zijn reis HET recht op korting heeft" absorbs "reis het recht op" into the
        // role kaleNaam (an article is a kaleNaamWoord, unlike "een" which stays a separate operator token), so the
        // role never resolved and it markered. possessief now finds the LONGEST role-prefix ("reis"), keeps the full
        // bron, and navigatieStaart + the operator's kaleNaam rejoin into the kenmerk "recht op korting". The EEN-form
        // (a contrast) already bound. RED-before-green: without the longest-role-prefix search this stays a marker.
        String src = """
            Objecttype de Vlucht
                het recht op korting kenmerk (bezittelijk);
            Objecttype de Persoon (bezield)
                is blij kenmerk (bijvoeglijk);
                is vrolijk kenmerk (bijvoeglijk);
            Feittype Vlucht van personen
                de reis Vlucht
                de passagier Persoon
            Eén reis betreft meerdere passagiers
            Regel het
                geldig altijd
                    Een Persoon is blij indien zijn reis het recht op korting heeft.
            Regel een
                geldig altijd
                    Een Persoon is vrolijk indien zijn reis een recht op korting heeft.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("reis != null && reis.rechtOpKorting == true"),
                () -> "de possessieve HET-vorm moet via de langste rolprefix binden:\n" + drl);
        assertFalse(drl.contains("/* TODO voorwaarde: zijn reis het recht op korting heeft */"),
                () -> "de HET-vorm mag geen marker meer zijn:\n" + drl);
    }

    @Test void to_many_self_relatie_navigated_gedurende_kenmerk_markert_niet_de_eigen_kenmerk() {
        // Review fix (major): a TO-MANY navigated kenmerk inside gedurende ("... gedurende de tijd dat zijn partner een
        // recht op korting heeft", partner to-many) must MARKER — it must NOT silently fall through to the subject's
        // OWN same-named kenmerk (a self-relation shadow that clips by the wrong timeline). RED-before-green: before the
        // fix it lowered to $subject.getRechtOpKorting() (own) with no marker.
        String src = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Persoon (mv: Personen) (bezield)
                de bedrag per maand Bedrag voor elke maand;
                het recht op korting kenmerk (bezittelijk) voor elke maand;
                de totaal partner bedrag Bedrag;
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft meerdere partners
            Regel R
                geldig altijd
                    De totaal partner bedrag van een Persoon moet gesteld worden op (het totaal van zijn bedrag per maand gedurende de tijd dat zijn partner een recht op korting heeft).
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("$subject.getRechtOpKorting()"),
                () -> "een to-many navigated gedurende-kenmerk mag NIET stil de eigen kenmerk binden:\n" + drl);
        assertTrue(drl.contains("/* TODO aggregatie TOTAAL"),
                () -> "een to-many navigated gedurende-kenmerk moet een honest marker blijven:\n" + drl);
    }

    @Test void navigatie_eenvorm_heeft_bindt_een_bezittelijk_kenmerk_van_het_genavigeerde_type() {
        // §8.1.8.2 the navigated EEN-form HEEFT binds a BEZITTELIJK kenmerk of the to-one navigated type ("de reis
        // van een Passagier heeft een recht op vergoeding"), the mirror of the subject's own EEN-form. The verb
        // selects the soort on the navigated path too: a bezittelijk kenmerk under "is" and a zelfstandig one under
        // "heeft" are wrong-verb forms that stay honest markers. (Teeth: hardcode the navigatieKenmerk metEen guard
        // back to ZELFSTANDIG and the bezittelijk binding falls back to a marker.)
        String src = """
            Objecttype de Vlucht
                het recht op vergoeding kenmerk (bezittelijk);
                het vip kenmerk;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                de toeslag Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel bezittelijk heeft
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op 1
                    indien de reis van een Passagier een recht op vergoeding heeft.
            Regel bezittelijk onder is
                geldig altijd
                    De toeslag van een Passagier moet gesteld worden op 2
                    indien de reis van een Passagier een recht op vergoeding is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("reis != null && reis.rechtOpVergoeding == true"),
                () -> "navigated bezittelijk heeft-EEN-vorm niet gebonden:\n" + drl);
        assertTrue(drl.contains("/* TODO voorwaarde: de reis van een Passagier een recht op vergoeding is */"),
                () -> "een bezittelijk navigated kenmerk onder 'is' moet een marker blijven:\n" + drl);
    }

    @Test void possessieve_navigatie_zijn_rol_bereikt_de_navigatie_kenmerkcheck() {
        // §8.1.7/§8.1.8 a POSSESSIVE "zijn <rol>" that names a feittype role is a to-one navigation, the mirror of
        // the written "de <rol> van <X>"; possessief() resolves it to the navigated AttribuutRef so a kenmerkcheck
        // reaches navigatieKenmerk (-> "rol != null && rol.<k> == true") and a value read reaches the to-one getter.
        // (Teeth: drop the role-navigation fallback in possessief() and "zijn partner heeft een <k>" markers again.)
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                het recht op duurzaamheidskorting kenmerk (bezittelijk);
                is rechthebbend kenmerk (bijvoeglijk);
                de uitkomst Numeriek (geheel getal);
                de toeslag Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel bezittelijk
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 7 indien zijn partner een recht op duurzaamheidskorting heeft.
            Regel bijvoeglijk
                geldig altijd
                    De toeslag van een Persoon moet gesteld worden op 3 indien zijn partner rechthebbend is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("partner.rechtOpDuurzaamheidskorting == true"),
                () -> "possessieve navigatie 'zijn partner heeft een <k>' niet gebonden:\n" + drl);
        assertTrue(drl.contains("partner.rechthebbend == true"),
                () -> "possessieve navigatie 'zijn partner rechthebbend is' niet gebonden:\n" + drl);
    }

    @Test void eenvorm_heeft_bindt_een_bezittelijk_kenmerk_niet_de_zelfstandige_soort() {
        // §8.1.8.2 the EEN-form HEEFT binds a BEZITTELIJK kenmerk ("hij heeft een recht op duurzaamheidskorting" —
        // the §7.1 verbatim possession form), the mirror of the §8.1.8.3 "is een <zelfstandig kenmerk>". The verb
        // selects the soort: heeft -> bezittelijk, is -> zelfstandig; neither soort binds under the other verb, so
        // an "is een <bezittelijk k>" (wrong verb) stays an honest marker. (Teeth: route the heeft EEN-form through
        // zelfstandigKenmerk instead and the bezittelijk binding falls back to the marker.)
        // The pairing is symmetric: a ZELFSTANDIG kenmerk binds the EEN-form only under "is", so "heeft een
        // <zelfstandig k>" (wrong verb) is now an honest marker too (it formerly over-bound verb-agnostically —
        // §3.5 line 823 makes a noch-noch/zelfstandig kenmerk read "is een …", not "heeft …").
        String src = """
            Objecttype de Passagier (bezield)
                het recht op duurzaamheidskorting kenmerk (bezittelijk);
                het vip kenmerk;
                de korting Numeriek (geheel getal);
                de toeslag Numeriek (geheel getal);
                de premie Numeriek (geheel getal);
                de bonus Numeriek (geheel getal);
            Regel bezit
                geldig altijd
                    De korting van een Passagier moet gesteld worden op 1
                    indien hij een recht op duurzaamheidskorting heeft.
            Regel verkeerd werkwoord bezittelijk
                geldig altijd
                    De toeslag van een Passagier moet gesteld worden op 2
                    indien hij een recht op duurzaamheidskorting is.
            Regel zelfstandig juist
                geldig altijd
                    De premie van een Passagier moet gesteld worden op 3
                    indien hij een vip is.
            Regel verkeerd werkwoord zelfstandig
                geldig altijd
                    De bonus van een Passagier moet gesteld worden op 4
                    indien hij een vip heeft.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rechtOpDuurzaamheidskorting == true"),
                () -> "de heeft EEN-vorm moet het bezittelijk kenmerk binden:\n" + drl);
        assertTrue(drl.contains("/* TODO voorwaarde: hij een recht op duurzaamheidskorting is */"),
                () -> "een bezittelijk kenmerk onder 'is' (verkeerd werkwoord) moet een marker blijven:\n" + drl);
        assertTrue(drl.contains("vip == true"),
                () -> "een zelfstandig kenmerk moet de IS EEN-vorm binden:\n" + drl);
        assertTrue(drl.contains("/* TODO voorwaarde: hij een vip heeft */"),
                () -> "een zelfstandig kenmerk onder 'heeft' (verkeerd werkwoord) moet een marker blijven:\n" + drl);
    }

    @Test void rolcheck_zonder_een_bindt_kenmerk_eerst_dan_rol() {
        // §13.4.15.44: "een" optional in the IS-form rolcheck. The non-"een" name is resolved kenmerk-FIRST
        // (Tabel 16): a bare bijvoeglijk kenmerk ("is minderjarig") binds the kenmerk; only a name that is a
        // role but NOT a kenmerk ("is passagier") falls through to the §8.1.7 rolcheck (reis != null).
        String src = """
            Objecttype de Passagier
                is minderjarig kenmerk (bijvoeglijk);
                is volwassen kenmerk (bijvoeglijk);
            Objecttype de Vlucht
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel kenmerk
                geldig altijd
                    Een passagier is volwassen indien hij minderjarig is.
            Regel rol
                geldig altijd
                    Een passagier is volwassen indien hij passagier is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("minderjarig == true"), () -> "'is minderjarig' moet kenmerk-eerst binden:\n" + drl);
        assertTrue(drl.contains("reis != null"), () -> "'is passagier' (rol, geen kenmerk) moet de rolcheck binden:\n" + drl);
    }

    @Test void navigatie_negatie_de_negeert_de_bezittelijke_navigatie_niet_markeert() {
        // §8.1.8.1 a NAVIGATED negated kenmerk "zijn reis niet internationaal is": the greedy POSSESSIVE onderwerp
        // absorbs the "niet" into the role ("reis niet"), which used to leave the navigation unresolvable -> a marker.
        // The resolver now splits the trailing negator back onto the kenmerk -> reis != null && reis.internationaal
        // == false. A NULL reis -> the whole AND is false (onwaar) — the negation does NOT flip an absent navigation
        // to true (the §3.5-faithful reading: a kenmerk tests an instance's presence; no instance -> nothing aanwezig
        // -> onwaar for BOTH polarities, decided BEFORE the polarity flip). The affirmative stays "== true".
        String src = """
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
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("reis != null && reis.internationaal == false"),
                () -> "navigated negatie moet null-veilig de-negeren (reis != null && reis.X == false), niet markeren:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "de navigated negatie mag geen marker zijn:\n" + drl);

        // VERBATIM-FIRST edge (bridge matrix #9): a kenmerk literally named "geen toegang" binds POSITIVELY — only a
        // name that fails to bind is de-negated, so a legitimate geen/niet-named kenmerk is never silently inverted.
        String verbatim = DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de geen toegang kenmerk;
                is gemarkeerd kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Passagier is gemarkeerd indien hij een geen toegang is.
            """));
        assertTrue(verbatim.contains("geenToegang == true"),
                () -> "een kenmerk dat letterlijk 'geen toegang' heet moet POSITIEF binden (verbatim-first):\n" + verbatim);
    }

    private static final String FAR_SIDE_ALLE_SRC = """
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
                    - hij heeft een passagier
                    - hij is internationaal.
            """;

    @Test void far_side_rolcheck_genest_in_een_conjunctie_hoist_als_exists_join() {
        // §8.1.7 far-side "heeft een <rol>" lowers to an EXISTS-join — a separate when-clause that cannot live
        // inside the inline constraint. Under a PURE CONJUNCTION (alle/de) it hoists faithfully: the kenmerk
        // conjunct stays inline on the subject pattern, the membership rides out as a separate "exists" clause.
        String drl = DrlTranspiler.transpile(Resolver.resolve(FAR_SIDE_ALLE_SRC));
        assertTrue(drl.contains("exists Passagier( reis == $subject )"),
                () -> "een geneste far-side rolcheck in een conjunctie moet hoisten naar een exists-join:\n" + drl);
        assertTrue(drl.contains("internationaal == true"),
                () -> "de andere conjunct moet inline op het subject-patroon blijven:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "geen marker meer voor de geneste conjunctie far-side:\n" + drl);
    }

    @Test void far_side_rolcheck_genest_onder_negatieve_kwantor_blijft_marker() {
        // The safety floor: under a NON-conjunction quantifier (geen / ten hoogste / precies, and ten minste via
        // an OR-fold) the parts are combined with || / !, where a hoisted "exists" — implicitly AND-ed as a
        // separate clause — would ASSERT the membership unconditionally (a silently-wrong gate). So a far-side
        // nested under such a quantifier must STAY a marker, never emit an exists.
        String drl = DrlTranspiler.transpile(Resolver.resolve(
                FAR_SIDE_ALLE_SRC.replace("aan alle volgende voorwaarden voldoet", "aan geen van de volgende voorwaarden voldoet")));
        assertTrue(drl.contains("/* TODO"),
                () -> "een far-side onder een negatieve kwantor moet een marker blijven:\n" + drl);
        assertFalse(drl.contains("exists Passagier"),
                () -> "een far-side onder een negatieve kwantor mag geen exists emitteren:\n" + drl);
    }

    @Test void far_side_gate_wordt_in_de_deling_door_leeg_companion_meegedragen() {
        // §6.5: a far-side condition is a hoisted exists-clause, not an inline constraint. The deling-door-leeg
        // companion now CARRIES that exists clause, so it fires exactly when the division would execute — the
        // own-attribute divisor is leeg AND the membership holds — never for a subject the rule does not act on.
        String src = """
            Objecttype de Passagier
            Objecttype de Vlucht
                de somMet Numeriek (geheel getal);
                de somZonder Numeriek (geheel getal);
                de totaalprijs Numeriek (getal met 2 decimalen);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel deel
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op de somMet van een Vlucht gedeeld door de somZonder van een Vlucht indien hij een passagier heeft.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsGedeeld"), () -> "de deling moet herkend zijn (anders bewijst de test niets):\n" + drl);
        assertTrue(drl.contains("(deling door leeg)"), () -> "de deling-door-leeg companion moet nu wel ge-emit zijn:\n" + drl);
        // the companion pattern carries both the own-attribute leeg-guard AND the far-side membership exists-clause
        assertTrue(drl.contains("$subject : Vlucht( somMet != null, somZonder == null )\n    exists Passagier( reis == $subject )"),
                () -> "de companion moet de far-side gate (exists) meedragen:\n" + drl);
    }

    @Test void onmapbare_functie_wordt_gemarkeerd_niet_verzonnen() {
        // concatenatie has no lowering yet; it must surface as an explicit marker, never as silently-wrong
        // DRL. (wortel — once the example here — now lowers, see wortel_lowert_naar_sqrt_….)
        String src = """
            Objecttype de Reis
                de naam Tekst;
                de bijnaam Tekst;
                de volledig Tekst;
            Regel c
                geldig altijd
                    De volledig van een Reis moet gesteld worden op de concatenatie van de naam van een Reis en de bijnaam van een Reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO aggregatie CONCATENATIE"), () -> "concatenatie niet gemarkeerd:\n" + drl);
    }

    @Test void aggregatie_met_dimensieselectie_wordt_gemarkeerd_niet_stil_gesommeerd() {
        // A "som van … over alle <dim>" whose operand is an INSTANCE aggregation over a scalar attribute
        // ("de prijs van alle passagiers", prijs not gedimensioneerd) has no dimension to range over, so it
        // stays an honest MARKER (a no-op) rather than silently summing the whole set as if the
        // dimensieselectie were absent. (The lowerable case — a single gedimensioneerd attribuut operand —
        // is dimensieaggregatie_over_gedimensioneerd_attribuut_wordt_gevouwen.)
        String src = """
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
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
                    De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis over alle jaardimensies.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO aggregatie SOM"), () -> "dimensie-beperkte som niet gemarkeerd:\n" + drl);
        assertFalse(drl.contains("accumulate("), () -> "een dimensie-beperkte som mag geen ongefilterde accumulate worden:\n" + drl);
    }

    @Test void waardefold_met_dimensieselectie_wordt_ook_gemarkeerd() {
        // p5 review follow-up: a multi-operand min/max value-fold carrying a dimensieselectie must marker
        // too — the value-fold path (concatenatieAggregatie) used to emit its unrestricted ternary while the
        // resolver had already WARNed the aggregation is left out. Without the selectie it still folds.
        String beperkt = """
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
            Objecttype de Ding
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
                de m Numeriek (geheel getal);
            Regel r
                geldig altijd
                    De m van een Ding moet gesteld worden op de minimale waarde van de a van een Ding en de b van een Ding over alle jaardimensies.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(beperkt));
        assertTrue(drl.contains("/* TODO aggregatie MINIMUM"), () -> "dimensie-beperkte waardefold niet gemarkeerd:\n" + drl);

        // control: the same fold WITHOUT the dimensieselectie still lowers to the ternary
        String vrij = beperkt.replace(" over alle jaardimensies", "");
        String drl2 = DrlTranspiler.transpile(Resolver.resolve(vrij));
        assertFalse(drl2.contains("/* TODO aggregatie"), () -> "een onbeperkte waardefold moet gewoon lowert:\n" + drl2);
    }

    @Test void dimensieaggregatie_over_gedimensioneerd_attribuut_wordt_gevouwen() {
        // §5.8.5: a "som van <gedimensioneerd attribuut> vanaf <a> t/m <b>" folds the attribute's per-label
        // values over the contiguous label interval. The labels are static, so it lowers NOT to an
        // instance accumulate but to a fixed call to a registered helper (rsDimSom) over the selected label
        // keys — no marker. The helper is emitted once, after the header.
        String src = """
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
            Regel Totaal betaalde belasting over 4 jaar
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet berekend worden als de som van zijn betaalde belasting in jaar vanaf vier jaar geleden t/m een jaar geleden.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("/* TODO aggregatie"), () -> "de dimensie-som mag geen marker meer zijn:\n" + drl);
        assertTrue(drl.contains("function java.math.BigDecimal rsDimSom("), () -> "helper-functie ontbreekt:\n" + drl);
        // exactly the interval labels [vier..een jaar geleden]; the out-of-interval labels are excluded.
        assertTrue(drl.contains("rsDimSom($subject.getBetaaldeBelastingInJaar(), java.util.Arrays.asList(\"vier jaar geleden\", \"drie jaar geleden\", \"twee jaar geleden\", \"een jaar geleden\"))"),
                () -> "verwachte dimensie-som-aanroep over het interval ontbreekt:\n" + drl);
        assertFalse(drl.contains("\"heden\""), () -> "labels buiten het interval mogen niet meegeteld worden:\n" + drl);
    }

    @Test void dimensie_cel_lezen_en_ongedimensioneerde_lezing() {
        // §3.6: a labeled dimensioned read lowers to map.get(label); a multi-dimension read and an
        // under-specified read (no label) both stay honest markers — the latter would otherwise emit a bare
        // Map getter where a scalar is expected.
        String src = """
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
            Dimensie de brutonettodimensie, bestaande uit de brutonettodimensies (voor het attribuut zonder voorzetsel):
                1. bruto
                2. netto
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                de prijs in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
                de inkomen Numeriek (geheel getal) gedimensioneerd met jaardimensie en brutonettodimensie;
            Regel cel
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op zijn prijs in jaar van vorig jaar.
            Regel onder
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op zijn prijs in jaar.
            Regel multi
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op zijn inkomen van vorig jaar.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("(" + "$subject.getPrijsInJaar()).get(\"vorig jaar\")"),
                () -> "de gelabelde cel-lezing moet map.get(label) worden:\n" + drl);
        assertTrue(drl.contains("ongedimensioneerde lezing"),
                () -> "een lezing zonder label moet een marker zijn, geen kale Map-getter:\n" + drl);
        // the prefix-form multi-dimension read ("inkomen van vorig jaar", bruto/netto unspecified) is not a
        // single-dimension suffix cell -> it stays a marker (deferred), never a single-axis map.get.
        assertFalse(drl.contains("getInkomen()).get("),
                () -> "een multidimensionele lezing mag niet als enkel-label cel lowered worden:\n" + drl);
    }

    @Test void gedimensioneerde_lezing_in_voorwaarde_wordt_marker_geen_map_vergelijking() {
        // review (HIGH): on the CONDITION side a bare dimensioned read used to emit "prijsInJaar > 5" — a
        // Map-vs-number term that fails the WHOLE DRL build — and a labeled cell did not lower. Both must now
        // become a compilable always-false voorwaarde marker, never a raw Map comparison.
        String src = """
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                de prijs in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
            Regel bare
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 7 indien zijn prijs in jaar groter is dan 5.
            Regel cel
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 8 indien zijn prijs in jaar van vorig jaar groter is dan 5.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("prijsInJaar >"), () -> "een kale Map-vergelijking is onbouwbare DRL:\n" + drl);
        assertEquals(2, drl.split("false /\\* TODO voorwaarde", -1).length - 1,
                () -> "beide gedimensioneerde voorwaarde-lezingen moeten een marker-guard worden:\n" + drl);
    }

    @Test void dimensieselectie_op_een_naar_veel_navigatie_blijft_marker_geen_map_som() {
        // review (lens B): a dimensieselectie over a TO-MANY navigated dimensioned attribuut ("de som van de
        // prijs in jaar van alle passagiers … over alle jaardimensies") is a 2-level aggregation (over
        // instances AND labels). The single-Map fold cannot express it, so it must stay the honest marker —
        // NOT silently fall through to an instance accumulate that sums Map fields (sum($e.getPrijsInJaar())),
        // a miscompile, with the dimensieselectie WARNING dropped.
        String src = """
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
            Objecttype de Reis
                de totaal Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
            Feittype Reis van passagiers
                de reis Reis
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaal van een reis moet berekend worden als de som van de prijs in jaar van alle passagiers van de reis over alle jaardimensies.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie SOM"), () -> "de naar-veel dimensie-som moet een marker blijven:\n" + drl);
        assertFalse(drl.contains("sum( $e1.getPrijsInJaar"), () -> "een Map-veld mag niet ge-sum-d worden (miscompile):\n" + drl);
        assertFalse(drl.contains("rsDimSom"), () -> "een naar-veel navigatie mag niet als labelfold lowered worden:\n" + drl);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("dimensieselectie")),
                () -> "de marker-WARNING mag niet verdwijnen: " + m.diagnostics);
    }

    @Test void begrenzing_en_afronding_worden_geemit() {
        // Kept in separate rules: a begrenzing whose bound value is itself followed by
        // an afronding parses the afronding INTO the bound value (a grammar-precedence
        // limitation noted in docs/grammar-divergences.md), so they are tested apart.
        String src = """
            Parameter de bodem : Numeriek (geheel getal)
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
                de basis Numeriek (geheel getal);
            Regel afrond
                geldig altijd
                    De prijs van een Reis moet gesteld worden op de basis van een Reis naar beneden afgerond op 2 decimalen.
            Regel begrens
                geldig altijd
                    De prijs van een Reis moet gesteld worden op de basis van een Reis, met een minimum van de bodem.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsAfronding($subject.getBasis(), 2, java.math.RoundingMode.FLOOR)"), () -> "afronding:\n" + drl);
        assertTrue(drl.contains("< bodem ? bodem :"), () -> "begrenzing-minimum:\n" + drl);
    }

    @Test void rekenkundige_voorwaarde_lowert_ternary_vrij_via_binairTerm() {
        // §6.2-6.8 arithmetic in a CONDITION now lowers, sharing binairTerm with the value side but ternary-free:
        // the leegwaarde helpers (rsNul) are method calls valid in a pattern constraint, and the unit restatement
        // is identity when no conversion is due. A maal and a plus over the subject's own attributes lower inline.
        String src = """
            Objecttype de Klok
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
                is oud kenmerk (bijvoeglijk);
            Regel m
                geldig altijd
                    Een Klok is oud indien de a van een Klok maal 2 gelijk is aan de b van een Klok.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // the exact constraint is ternary-free (a "?:" in the pattern would mis-parse); the rsNul helper BODY
        // may carry a ternary, so assert on the constraint line, not the whole DRL.
        assertTrue(drl.contains("$subject : Klok( (rsNul(a) * rsNul(new java.math.BigDecimal(\"2\"))) == b )"),
                () -> "rekenkundige voorwaarde niet ternary-vrij gelowerd:\n" + drl);
        String constraintRegel = drl.lines().filter(l -> l.contains("$subject : Klok(")).findFirst().orElse("");
        assertFalse(constraintRegel.contains(" ? "), () -> "de voorwaarde-constraint mag geen ternary bevatten:\n" + constraintRegel);
    }

    @Test void convertibele_eenheid_in_een_rekenkundige_voorwaarde_blijft_marker() {
        // §5.1 a +/- whose operands need a UNIT CONVERSION in a constraint stays an honest MARKER: the restatement
        // would restate the right operand into the LEFT's unit by the §4 ratio (omrekenfactorNaar), which TRUNCATES a
        // non-terminating quotient (mnd→jr = 1/12 = 0.0833…) — "1 jr + 12 mnd == 2 jr" would compute 1.9999… ≠ 2, a
        // WRONG firing (review F2). The comparison restatement uses the exact factorNaarCanoniek, but a +/- result must
        // stay in the left's unit (lossy for the smaller→larger direction). So both back-ends decline it symmetrically
        // (the exec parityOnveilig skips a cross-unit +/-). Carded: exact canonical-unit arithmetic would fire both.
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Klok
                de looptijd Numeriek (geheel getal) met eenheid mnd;
                de extra Numeriek (geheel getal) met eenheid jr;
                de totaal Numeriek (geheel getal) met eenheid mnd;
                is oud kenmerk (bijvoeglijk);
            Regel cond
                geldig altijd
                    Een Klok is oud indien de looptijd van een Klok plus de extra van een Klok gelijk is aan de totaal van een Klok.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO voorwaarde"), () -> "een cross-unit +/- in een rekenkundige voorwaarde moet markeren (lossy ratio, review F2):\n" + drl);
        assertFalse(drl.contains(".multiply"), () -> "geen ongegarde multiply in een constraint:\n" + drl);
    }

    @Test void macht_afronding_rondt_de_macht_af_en_een_trailing_operator_bindt_buiten() {
        // §13.4.17.14: the mandatory afronding rounds the POWER (it is a sibling of the exponent,
        // not inside it), and a trailing 'maal' binds OUTSIDE the rounded power — the exponent is
        // now a primaireExpressie, so neither the afronding nor the operator is absorbed into it.
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
            Regel r
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de basis van een Heffing tot de macht 2 naar beneden afgerond op 0 decimalen maal 3.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // the afronding (rounding the power) is the maal's left operand -> the maal binds OUTSIDE it
        assertTrue(drl.contains("rsAfronding(rsMacht($subject.getBasis(), 2), 0, java.math.RoundingMode.FLOOR)) * rsNul(new java.math.BigDecimal(\"3\"))"),
                () -> "afronding moet de macht afronden en 'maal 3' moet erbuiten binden:\n" + drl);
    }

    @Test void verminderd_met_en_min_loweren_verschillend_door_de_leegwaarde() {
        // §6.3 Tabel 7/8: "min" and "verminderd met" subtract identically, but differ on a leeg LEFT operand —
        // min treats it as 0 (rsNul, never leeg), verminderd met yields leeg (rsVerminderd). They must NOT lower
        // to the same DRL (they used to, a silent-wrong).
        String src = """
            Objecttype de Reis
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
                de vm Numeriek (geheel getal);
                de mn Numeriek (geheel getal);
            Regel rv
                geldig altijd
                    De vm van een Reis moet gesteld worden op de a van een Reis verminderd met de b van een Reis.
            Regel rm
                geldig altijd
                    De mn van een Reis moet gesteld worden op de a van een Reis min de b van een Reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsVerminderd($subject.getA(), $subject.getB())"),
                () -> "verminderd met moet leeg-links -> leeg afdwingen via rsVerminderd:\n" + drl);
        assertTrue(drl.contains("(rsNul($subject.getA()) - rsNul($subject.getB()))"),
                () -> "min moet leeg als 0 behandelen via rsNul:\n" + drl);
    }

    @Test void gedeeld_door_abs_rondt_op_vijf_decimalen_af_gewoon_delen_blijft_exact() {
        // §6.5: "gedeeld door (ABS)" truncates to 5 decimals toward zero; plain "gedeeld door" stays the
        // exact DECIMAL64 quotient. The two must lower to DIFFERENT divides.
        String src = """
            Objecttype de Reis
                de uitkomst Numeriek (getal met 5 decimalen);
                de teller Numeriek (getal met 5 decimalen);
                de noemer Numeriek (getal met 5 decimalen);
            Regel abs
                geldig altijd
                    De uitkomst van een Reis moet gesteld worden op de teller van een Reis gedeeld door (ABS) de noemer van een Reis.
            Regel gewoon
                geldig altijd
                    De teller van een Reis moet gesteld worden op de uitkomst van een Reis gedeeld door de noemer van een Reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // both lower through their §6.5-leegwaarde helper, with the operands passed in...
        assertTrue(drl.contains("rsGedeeldAbs($subject.getTeller(), $subject.getNoemer())"),
                () -> "gedeeld door (ABS) moet via rsGedeeldAbs lowert:\n" + drl);
        assertTrue(drl.contains("rsGedeeld($subject.getUitkomst(), $subject.getNoemer())"),
                () -> "gewone 'gedeeld door' moet via rsGedeeld lowert:\n" + drl);
        // ...and the two helpers keep DIFFERENT divides: ABS truncates 5 decimals toward 0, plain stays DECIMAL64.
        assertTrue(drl.contains(".divide(rechts, 5, java.math.RoundingMode.DOWN)"),
                () -> "rsGedeeldAbs moet op 5 decimalen richting 0 afronden:\n" + drl);
        assertTrue(drl.contains(".divide(rechts, java.math.MathContext.DECIMAL64)"),
                () -> "rsGedeeld moet de exacte DECIMAL64-deling houden:\n" + drl);
    }

    @Test void gedeeld_door_emitteert_een_deling_door_leeg_inconsistentie_companion() {
        // §6.5 / Tabel 12: "waarde gedeeld door leeg" -> fout, surfaced as a SEPARATE Inconsistentie rule
        // guarded dividend != null && divisor == null (an empty LEFT is 0, not a fout). Modify-free.
        String src = """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de deelResultaat Numeriek (getal met 2 decimalen);
            Regel deel
                geldig altijd
                    De deelResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom gedeeld door de aftrek van een Rekensom.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rule \"deel (deling door leeg)\""), () -> "geen companion-regel:\n" + drl);
        assertTrue(drl.contains("Rekensom( grondslag != null, aftrek == null )"),
                () -> "de fout-guard moet dividend != null EN divisor == null zijn (een leeg dividend is 0, geen fout):\n" + drl);
        assertTrue(drl.contains("insert(new io.github.yhavinga.regelspraak.facts.Inconsistentie(\"deel\""), () -> "geen Inconsistentie-insert:\n" + drl);
        // the companion must be modify-free (it must not interfere with the value rule)
        String comp = drl.substring(drl.indexOf("(deling door leeg)"));
        assertFalse(comp.substring(0, comp.indexOf("\nend")).contains("modify"), () -> "companion mag geen modify bevatten:\n" + comp);
    }

    @Test void scalaire_builtins_loweren_naar_een_enkele_expressie() {
        String src = """
            Objecttype de Klok
                de saldo Numeriek (geheel getal);
                de absval Numeriek (geheel getal);
                de reisdatum Datum in dagen;
                de jaartal Numeriek (geheel getal);
                de bouwjaar Numeriek (geheel getal);
                de gemaakt Datum in dagen;
            Regel a
                geldig altijd
                    De absval van een Klok moet gesteld worden op de absolute waarde van (de saldo van een Klok).
            Regel j
                geldig altijd
                    De jaartal van een Klok moet gesteld worden op het jaar uit de reisdatum van een Klok.
            Regel d
                geldig altijd
                    De gemaakt van een Klok moet gesteld worden op de datum met jaar: de bouwjaar van een Klok, maand: 3 en dag: 15.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsAbs($subject.getSaldo())"), () -> "abs (leeg-veilig via helper):\n" + drl);
        assertTrue(drl.contains("rsDatumDeel($subject.getReisdatum(), java.time.temporal.ChronoField.YEAR)"),
                () -> "jaar uit (leeg-veilig via helper):\n" + drl);
        // §6.9: lowered via rsDatumMet (a null component -> null date, no NPE) — not an inline LocalDate.of
        assertTrue(drl.contains("setGemaakt( rsDatumMet($subject.getBouwjaar(), new java.math.BigDecimal(\"3\"), new java.math.BigDecimal(\"15\")) )"),
                () -> "datum met (leeg-veilig via helper):\n" + drl);
    }

    @Test void datumliteral_met_voorloopnul_compileert_decimaal_niet_octaal() {
        // §13.2.19: a Datum literal whose month or day is 08 or 09 must emit a DECIMAL int — a raw "08"/"09" splice
        // is an INVALID OCTAL literal in mvel/Java (LocalDate.of(2024, 09, 08) fails the whole KieBuilder build),
        // where only 01-07 happen to equal their decimal value. (Teeth: revert the Integer.parseInt splice in
        // literal()'s Datum branch and the octal form returns.) Both the value side and a when-side comparison.
        String drl = DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Vlucht (mv: Vluchten) (bezield)
                de vertrekdatum Datum in dagen;
                de aankomstdatum Datum in dagen;
                is laat kenmerk (bijvoeglijk);
            Regel a
                geldig altijd
                    De aankomstdatum van een Vlucht moet gesteld worden op 08-09-2024.
            Regel b
                geldig altijd
                    Een Vlucht is laat indien zijn vertrekdatum gelijk is aan 09-08-2024.
            """));
        assertTrue(drl.contains("setAankomstdatum( java.time.LocalDate.of(2024, 9, 8) )"),
                () -> "value-side datumliteral moet decimaal zijn:\n" + drl);
        assertTrue(drl.contains("vertrekdatum == java.time.LocalDate.of(2024, 8, 9)"),
                () -> "when-side datumliteral moet decimaal zijn:\n" + drl);
        assertFalse(drl.contains(", 09,") || drl.contains(", 08)") || drl.contains(", 09)") || drl.contains(", 08,"),
                () -> "geen octale voorloopnul-component in een datum:\n" + drl);
    }

    @Test void tekstreeks_interpoleert_expressies_en_blijft_anders_een_plain_literal() {
        // §5.4/§13.4.17.9: a "…«expressie»…" string lowers to an mvel concat of literal chunks (quoted) and the
        // «…» parts (each coerced to Tekst by rsTekst, null-safe). A PLAIN "…" stays a plain string literal.
        // (Teeth: revert the tekstWaarde grammar rule / the resolver TekstReeks branch and «…» falls back to a marker.)
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal);
                de roepnaam Tekst;
                de label Tekst;
            Regel a
                geldig altijd
                    De roepnaam van een Persoon moet gesteld worden op "nr «de leeftijd van een Persoon» jaar".
            Regel b
                geldig altijd
                    De label van een Persoon moet gesteld worden op "vast".
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("setRoepnaam( (\"nr \" + rsTekst($subject.getLeeftijd()) + \" jaar\") )"),
                () -> "tekstreeks moet naar een mvel concat met rsTekst lowereren:\n" + drl);
        assertTrue(drl.contains("function String rsTekst("), () -> "rsTekst helper moet geregistreerd zijn:\n" + drl);
        assertTrue(drl.contains("setLabel( \"vast\" )"), () -> "een plain string blijft een plain literal:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "een resolvebare tekstreeks mag geen marker zijn:\n" + drl);
    }

    @Test void tekstreeks_met_onresolveerbaar_deel_blijft_een_marker() {
        // honesty: an «expressie» part that cannot lower (an unknown attribute) propagates -> the whole tekstreeks
        // markers (the Frag marker flag), never a silently-wrong concat baking the source.
        String drl = DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Persoon (mv: Personen) (bezield)
                de roepnaam Tekst;
            Regel a
                geldig altijd
                    De roepnaam van een Persoon moet gesteld worden op "x«de onbekende van een Persoon»y".
            """));
        assertTrue(drl.contains("/* TODO"), () -> "een onresolveerbaar «…»-deel moet de tekstreeks markeren:\n" + drl);
    }

    @Test void tijd_builtins_eisen_een_datumtijd_en_datumtijd_met_telt_zeven_delen() {
        String src = """
            Objecttype de Klok
                de moment Datum en tijd in millisecondes;
                de reisdatum Datum in dagen;
                de uurnr Numeriek (geheel getal);
                de uurfout Numeriek (geheel getal);
                de bouwjaar Numeriek (geheel getal);
                de stip Datum en tijd in millisecondes;
            Regel u
                geldig altijd
                    De uurnr van een Klok moet gesteld worden op het uur uit de moment van een Klok.
            Regel uf
                geldig altijd
                    De uurfout van een Klok moet gesteld worden op het uur uit de reisdatum van een Klok.
            Regel dt
                geldig altijd
                    De stip van een Klok moet gesteld worden op de datumtijd met jaar: de bouwjaar van een Klok, maand: 3, dag: 15, uur: 10, minuut: 30, seconde: 0 en milliseconde: 500.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // a time component on a Datum-en-tijd lowers; on a Datum-in-dagen it markers (getHour() would not compile)
        assertTrue(drl.contains("rsDatumDeel($subject.getMoment(), java.time.temporal.ChronoField.HOUR_OF_DAY)"), () -> "uur uit datumtijd:\n" + drl);
        assertFalse(drl.contains("rsDatumDeel($subject.getReisdatum()"), () -> "uur uit een Datum-in-dagen moet een marker blijven, niet lageren:\n" + drl);
        assertTrue(drl.contains("setUurfout( /* TODO tijd-component"), () -> "uur uit een Datum-in-dagen moet een marker zijn:\n" + drl);
        // datumtijd met has SEVEN parts, lowered via rsDatumtijdMet (leeg-veilig); the milli->nano scale (x1e6)
        // now lives inside the helper, and the 7 components pass straight through as their value expressions.
        assertTrue(drl.contains("setStip( rsDatumtijdMet($subject.getBouwjaar(), new java.math.BigDecimal(\"3\"), "
                + "new java.math.BigDecimal(\"15\"), new java.math.BigDecimal(\"10\"), new java.math.BigDecimal(\"30\"), "
                + "new java.math.BigDecimal(\"0\"), new java.math.BigDecimal(\"500\")) )"), () -> "datumtijd met:\n" + drl);
        assertTrue(drl.contains("milli.intValue() * 1000000"), () -> "de milli->nano schaal moet in de helper zitten:\n" + drl);
    }

    @Test void wortel_lowert_naar_sqrt_binnen_zijn_verplichte_afronding() {
        String src = """
            Objecttype de Vierkant
                de oppervlakte Numeriek (geheel getal);
                de zijde Numeriek (geheel getal);
            Regel w
                geldig altijd
                    De zijde van een Vierkant moet gesteld worden op de wortel van de oppervlakte van een Vierkant naar beneden afgerond op 0 decimalen.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // the sqrt (rsWortel) is the basis of the mandatory afronding (rsAfronding) — the afronding is NOT dropped.
        // rsWortel does BigDecimal.sqrt (exact to 34 digits), NOT Math.sqrt (a double whose noise flips a round).
        assertTrue(drl.contains("rsWortel($subject.getOppervlakte())"), () -> "sqrt via rsWortel:\n" + drl);
        assertFalse(drl.contains("Math.sqrt"), () -> "geen double-sqrt (rondingsfout op de afrondgrens):\n" + drl);
        assertTrue(drl.contains("rsAfronding(rsWortel($subject.getOppervlakte()), 0, java.math.RoundingMode.FLOOR)"),
                () -> "de verplichte afronding moet de root afronden (leeg-veilig):\n" + drl);
        assertTrue(drl.contains(".sqrt(java.math.MathContext.DECIMAL128)"), () -> "rsWortel moet 34-cijferige sqrt gebruiken:\n" + drl);
    }

    @Test void wortel_van_negatief_geeft_leeg_plus_een_companion_inconsistentie() {
        // §6.6: a negative radicand is a Fout. rsWortel returns leeg for a negative (no session-aborting throw),
        // and a SEPARATE modify-free companion rule surfaces the fout when the radicand (own attribute) < 0.
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de wortelr Numeriek (geheel getal);
            Regel w
                geldig altijd
                    De wortelr van een Heffing moet gesteld worden op de wortel van de basis van een Heffing naar beneden afgerond op 0 decimalen.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("if (x == null || x.signum() < 0) return null;"), () -> "rsWortel moet een negatief radicand op leeg zetten:\n" + drl);
        assertTrue(drl.contains("rule \"w (wortel van negatief)\""), () -> "geen wortel-negatief companion:\n" + drl);
        assertTrue(drl.contains("Heffing( basis < new java.math.BigDecimal(\"0\") )"), () -> "geen radicand < 0 guard:\n" + drl);
        assertTrue(drl.contains("insert(new io.github.yhavinga.regelspraak.facts.Inconsistentie(\"w\""), () -> "geen Inconsistentie-insert:\n" + drl);
        String comp = drl.substring(drl.indexOf("(wortel van negatief)"));
        assertFalse(comp.substring(0, comp.indexOf("\nend")).contains("modify"), () -> "companion mag geen modify bevatten:\n" + comp);
    }

    @Test void tijdsduur_lowert_naar_chronounit_between_met_leeg_guard() {
        String src = """
            Eenheidsysteem Tijd
                de maand            mnd
                het jaar            jr       =    12 mnd
            Objecttype de Klok
                de vertrek Datum in dagen;
                de aankomst Datum in dagen;
                de jaren Numeriek (geheel getal) met eenheid jr;
                de maanden Numeriek (geheel getal) met eenheid mnd;
            Regel j
                geldig altijd
                    De jaren van een Klok moet gesteld worden op de tijdsduur van de vertrek van een Klok tot de aankomst van een Klok in hele jaren.
            Regel m
                geldig altijd
                    De maanden van een Klok moet gesteld worden op de absolute tijdsduur van de vertrek van een Klok tot de aankomst van een Klok in hele maanden.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // lowered via the injected rsTijdsduur helper (a fully-qualified ChronoUnit.between does not analyse
        // inside a Drools mvel modify-block); the signed years, the absolute months, and one helper definition
        assertTrue(drl.contains("rsTijdsduur($subject.getVertrek(), $subject.getAankomst(), \"YEARS\", false)"),
                () -> "tijdsduur in jaren:\n" + drl);
        assertTrue(drl.contains("rsTijdsduur($subject.getVertrek(), $subject.getAankomst(), \"MONTHS\", true)"),
                () -> "absolute tijdsduur in maanden:\n" + drl);
        // §6.10 Tabel 14: an empty operand makes the result empty (the guard lives in the helper), and the
        // helper is injected exactly once
        assertTrue(drl.contains("function java.math.BigDecimal rsTijdsduur("), () -> "helper niet geïnjecteerd:\n" + drl);
        assertTrue(drl.contains("if (van == null || tot == null) return null;"), () -> "leeg-guard in helper:\n" + drl);
    }

    @Test void tijdsduur_in_millisecondes_de_s_meervoudsvorm_uit_de_spec_lowert() {
        // §6.10 spells the unit "in millisecondes" (-s plural); the -en plural reducer leaves it unchanged,
        // so chronoUnit must accept the -s form too rather than markering a spec example.
        String src = """
            Objecttype de Klok
                de start Datum en tijd in millisecondes;
                de eind Datum en tijd in millisecondes;
                de duur Numeriek (geheel getal) met eenheid millisecondes;
            Regel d
                geldig altijd
                    De duur van een Klok moet gesteld worden op de tijdsduur van de start van een Klok tot de eind van een Klok in millisecondes.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsTijdsduur($subject.getStart(), $subject.getEind(), \"MILLIS\", false)"),
                () -> "in millisecondes moet naar MILLIS loweren, niet markeren:\n" + drl);
    }

    @Test void eerste_paasdag_lowert_naar_de_computus_helper() {
        String src = """
            Objecttype de Klok
                de jaartal Numeriek (geheel getal);
                de pasen Datum in dagen;
            Regel p
                geldig altijd
                    De pasen van een Klok moet gesteld worden op de eerste paasdag van de jaartal van een Klok.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsEerstePaasdag($subject.getJaartal())"), () -> "eerste paasdag:\n" + drl);
        assertTrue(drl.contains("function java.time.LocalDate rsEerstePaasdag("), () -> "computus-helper niet geïnjecteerd:\n" + drl);
    }

    @Test void scalaire_builtin_in_voorwaarde_lowert_naar_kale_property() {
        // §13.4.17 a scalar builtin as a CONDITION operand must lower to bare pattern-constraint properties
        // (the field name), NOT the $subject.getX() getter the value side uses — $subject is not bound inside
        // its own pattern. toka-27 Paaskorting: vluchtdatum == eerste paasdag van (jaar uit vluchtdatum).
        String src = """
            Objecttype de Vlucht
                de reis met paaskorting kenmerk;
                de vluchtdatum Datum in dagen;
            Regel Paaskorting
                geldig altijd
                    Een Vlucht is een reis met paaskorting
                    indien de vluchtdatum van een Vlucht gelijk is aan de eerste paasdag van (het jaar uit (de vluchtdatum van een Vlucht)).
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("TODO voorwaarde"), () -> "de conditie-builtin mag geen marker zijn:\n" + drl);
        assertTrue(drl.contains("rsEerstePaasdag(rsDatumDeel(vluchtdatum, java.time.temporal.ChronoField.YEAR))"),
                () -> "het builtin-argument moet de kale property zijn, geen $subject getter:\n" + drl);
        assertFalse(drl.contains("$subject.getVluchtdatum()"), () -> "geen getter in een pattern-constraint:\n" + drl);
    }

    @Test void kenmerktoekenning_wordt_boolean_setter_onder_voorwaarde() {
        String src = """
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Regel minderjarig
                geldig altijd
                    Een Persoon is minderjarig
                    indien de leeftijd van een Persoon kleiner is dan 18.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("leeftijd < new java.math.BigDecimal(\"18\")"), () -> "voorwaarde:\n" + drl);
        assertTrue(drl.contains("setMinderjarig( true )"), () -> "kenmerk-setter:\n" + drl);
    }

    @Test void definiete_terugverwijzing_via_eigen_rolnaam_leest_eigen_attribuut() {
        // §5.5.3: a definite back-reference to the subject by a role it itself fills
        // ("de reis", where the subject IS the Vlucht playing role reis) names the SAME
        // instance — it must read the subject's own attribute, not degrade to a marker.
        String src = """
            Objecttype de Vlucht
                de vertrek Numeriek (geheel getal);
                de aankomst Numeriek (geheel getal);
            Objecttype de Passagier
                de naam Tekst;
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel kopieer
                geldig altijd
                    De aankomst van een Vlucht moet gesteld worden op de vertrek van de reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("setAankomst( $subject.getVertrek() )"), () -> "eigen-attribuut-lezing:\n" + drl);
        assertFalse(drl.contains("TODO navigatie"), () -> "mag geen navigatie-marker zijn:\n" + drl);
    }

    @Test void exacte_rolnaam_wint_van_gesynthetiseerd_meervoud_van_andere_rol() {
        // Two feittypen over Reis<->Vlucht; one role is literally "heenvluchten", which
        // also happens to be the synthesized +en plural of the other feittype's
        // "heenvlucht". A reference naming "heenvluchten" exactly must bind THAT role, not
        // collapse to a false ambiguity (§3.1 footnote 2: a synthesized plural never
        // defeats a declared name).
        String src = """
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
            Objecttype de Vlucht
                de tarief Numeriek (geheel getal);
            Feittype boeking
                de reis Reis
                de heenvlucht Vlucht
            Eén reis heeft één heenvlucht
            Feittype extra
                de extrareis Reis
                de heenvluchten Vlucht
            Eén extrareis heeft één heenvluchten
            Regel prijs
                geldig altijd
                    De prijs van een Reis moet gesteld worden op de tarief van de heenvluchten.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject.getHeenvluchten().getTarief()"), () -> "exacte rol moet binden:\n" + drl);
        assertFalse(drl.contains("TODO navigatie"), () -> "geen valse ambiguïteit:\n" + drl);
    }

    @Test void consistentieregel_vuurt_op_de_schending_en_markeert_inconsistentie() {
        // §13.4.8 / §9.5: "X moet ongelijk zijn aan Y" asserts X != Y; the rule is
        // INCONSISTENT when that is violated (X == Y). So the generated rule fires on the
        // negated assertion and inserts an Inconsistentie naming the rule and the subject.
        String src = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
            Regel rondvlucht
                geldig altijd
                    De vertrek van een Route moet ongelijk zijn aan de bestemming van een Route.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rule \"rondvlucht\""), () -> "regel ontbreekt:\n" + drl);
        // ongelijk asserted -> fires on the gelijk violation
        assertTrue(drl.contains("$subject : Route( vertrek == bestemming )"), () -> "schending-constraint:\n" + drl);
        assertTrue(drl.contains("insert(new io.github.yhavinga.regelspraak.facts.Inconsistentie(\"rondvlucht\", $subject))"),
                () -> "inconsistentie-markering ontbreekt:\n" + drl);
    }

    @Test void consistentieregel_ordening_bewaakt_lege_operanden() {
        // §9.5 + leegwaarde rekentabels: "saldo moet kleiner zijn dan limiet" is onwaar —
        // inconsistent — when violated AND when an operand is empty. The algebraic
        // complement alone is also onwaar on null, so the violation guards the nulls.
        String src = """
            Objecttype de Konto
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
            Regel saldocheck
                geldig altijd
                    De saldo van een Konto moet kleiner zijn dan de limiet van een Konto.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("saldo == null || limiet == null || saldo >= limiet"),
                () -> "leeg-operand-bewaking ontbreekt:\n" + drl);
    }

    @Test void consistentieregel_met_langvorm_navigatie_waarschuwt_en_noopt_nooit_stil() {
        // A long-form navigated operand ("de <attr> van de <rol> van een <Type>") the resolver cannot
        // resolve lowers to a /* TODO */ marker. schendingConstraint must DROP the whole rule to a no-op
        // AND surface a WARNING — never silently control nothing, and never wrap the marker into a
        // constant-true guard (which would flag every instance inconsistent). The load-bearing guards are
        // the bevatMarker checks: ordening_nav exercises the ordening branch (~222); gelijk_nav and leeg_nav
        // both route through the negeer→conditie tail guard (~241) — gelijk_nav over Tekst does NOT hit the
        // non-numeriek GELIJK branch (~233), because the resolver types the unresolved navigated operand as
        // Onbekend, so nietNumeriekVergelijking is false. (The ~233 guard is unreachable-with-a-marker for
        // the same reason — defensive only.) This pins that a navigated consistentie cannot silently no-op.
        String src = """
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
                de naam Tekst;
            Feittype partnerschap van personen
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel ordening_nav
              geldig altijd
                De leeftijd van de partner van een Persoon moet kleiner zijn dan de leeftijd van een Persoon.
            Regel gelijk_nav
              geldig altijd
                De naam van de partner van een Persoon moet gelijk zijn aan de naam van een Persoon.
            Regel leeg_nav
              geldig altijd
                De naam van de partner van een Persoon moet gevuld zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        String drl = DrlTranspiler.transpile(m);

        for (String regel : new String[]{"ordening_nav", "gelijk_nav", "leeg_nav"}) {
            assertTrue(m.diagnostics.stream().anyMatch(d ->
                            d.severity() == RegelSpraakModel.Severity.WARNING
                                    && d.message().contains(regel)
                                    && d.message().contains("controleert niets")),
                    () -> "geen no-op-WARNING voor '" + regel + "'; kreeg " + m.diagnostics);
        }
        // the marker must never leak into a guard: no rule emits a TODO marker or an "== null"
        // null-guard built around an unresolved operand.
        assertFalse(drl.contains("/* TODO"), () -> "marker lekte naar DRL:\n" + drl);
        // and the no-op shape is the honest "eval(true)" body, not a half-baked constraint pattern
        assertFalse(drl.contains("Persoon( "), () -> "onverwacht een constraint-patroon op een onresolveerbare regel:\n" + drl);
    }

    @Test void consistentieregel_naam_met_quote_levert_geldige_drl_header() {
        // a rule name is free text and may contain a double quote (§13.2.8); it must be
        // escaped so the DRL header and the Inconsistentie literal stay well-formed.
        String src = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
            Regel Controleer "rondvlucht" status
                geldig altijd
                    De vertrek van een Route moet ongelijk zijn aan de bestemming van een Route.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rule \"Controleer 'rondvlucht' status\""), () -> "header niet geëscaped:\n" + drl);
        assertFalse(drl.contains("\"rondvlucht\""), () -> "ongeëscapete quote lekt door:\n" + drl);
    }

    @Test void lege_sommatie_is_leegwaarde_tenzij_een_fallback_is_opgegeven() {
        // §5.8.2: an empty sommatie is a leegwaarde, NOT 0 — Drools sum() returns 0, so the
        // non-empty contributors are counted and the result branches on that count. With
        // "of 0 als die er niet zijn" the empty case takes the fallback instead.
        String src = """
            Objecttype de Vlucht
                de somMet Numeriek (geheel getal);
                de somZonder Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel met
                geldig altijd
                    De somMet van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis of 0 als die er niet zijn.
            Regel zonder
                geldig altijd
                    De somZonder van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // empty contributors counted; only non-null values summed
        assertTrue(drl.contains("$e1 : Passagier( reis == $subject, prijs != null )"), () -> "leeg-filter ontbreekt:\n" + drl);
        assertTrue(drl.contains("$n1 : count($e1)"), () -> "count-bewaking ontbreekt:\n" + drl);
        // fallback present -> 0 on empty; absent -> leegwaarde (null)
        assertTrue(drl.contains("setSomMet( ($n1 == 0 ? new java.math.BigDecimal(\"0\") : $acc1) )"), () -> "fallback-tak:\n" + drl);
        assertTrue(drl.contains("setSomZonder( ($n1 == 0 ? null : $acc1) )"), () -> "leegwaarde-tak:\n" + drl);
    }

    @Test void drempelkwantoren_worden_booleaanse_telcombinaties() {
        // §13.4.14.4: the threshold quantifiers count satisfied criteria. Drools constraints
        // take only booleans, so this is the OR of k-subsets (minstens): at-least-N directly,
        // at-most-N as !at-least-(N+1), exactly-N as their conjunction. No ternary arithmetic.
        String src = """
            Objecttype de Persoon
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
                de c Numeriek (geheel getal);
                is gekozen kenmerk (bijvoeglijk);
                is afgewezen kenmerk (bijvoeglijk);
                is uniek kenmerk (bijvoeglijk);
            Regel minstens
                geldig altijd
                    Een Persoon is gekozen
                    indien hij aan ten minste 2 van de volgende voorwaarden voldoet:
                    - de a van een Persoon is groter dan 0
                    - de b van een Persoon is groter dan 0
                    - de c van een Persoon is groter dan 0.
            Regel hoogstens
                geldig altijd
                    Een Persoon is afgewezen
                    indien hij aan ten hoogste 1 van de volgende voorwaarden voldoet:
                    - de a van een Persoon is groter dan 0
                    - de b van een Persoon is groter dan 0.
            Regel preciesEen
                geldig altijd
                    Een Persoon is uniek
                    indien hij aan precies 1 van de volgende voorwaarden voldoet:
                    - de a van een Persoon is groter dan 0
                    - de b van een Persoon is groter dan 0.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("? 1 : 0"), () -> "geen ternaire telkunst in een constraint:\n" + drl);
        // ten minste 2 of 3 -> OR of the three pairs (a&&b) || (a&&c) || (b&&c)
        assertTrue(drl.contains(") && (b > ") && drl.contains(") || ("), () -> "minstens-combinaties:\n" + drl);
        // ten hoogste 1 of 2 -> !(at least 2) = !((a&&b))
        assertTrue(drl.contains("Persoon( !("), () -> "ten hoogste -> negatie:\n" + drl);
        // precies 1 of 2 -> (at least 1) && !(at least 2)
        assertTrue(drl.contains(" && !("), () -> "precies -> minstens N en niet minstens N+1:\n" + drl);
    }

    @Test void rule_versies_krijgen_een_rekendatum_venster_en_unieke_namen() {
        // §13.4.2.5 / §4.2: each version becomes a distinct rule guarded by the Rekendatum
        // window; a single-version "geldig altijd" rule keeps its bare name and no guard.
        String src = """
            Objecttype de Heffing
                de prijs Numeriek (geheel getal);
            Regel verhoog
                geldig vanaf 2024 t/m 2024
                    De prijs van een Heffing moet gesteld worden op 100.
                geldig vanaf 2025
                    De prijs van een Heffing moet gesteld worden op 200.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("global java.time.LocalDate rekendatum;"), () -> "rekendatum-global ontbreekt:\n" + drl);
        assertTrue(drl.contains("rule \"verhoog #1\"") && drl.contains("rule \"verhoog #2\""),
                () -> "versies krijgen geen unieke namen:\n" + drl);
        assertTrue(drl.contains("!rekendatum.isBefore(java.time.LocalDate.of(2024, 1, 1))")
                && drl.contains("!rekendatum.isAfter(java.time.LocalDate.of(2024, 12, 31))"),
                () -> "venster van versie #1 ontbreekt:\n" + drl);
        // open-ended "vanaf 2025": a lower bound but no upper
        assertTrue(drl.contains("!rekendatum.isBefore(java.time.LocalDate.of(2025, 1, 1))"), () -> "venster #2:\n" + drl);
    }

    @Test void namen_worden_geldige_java_identifiers() {
        String src = """
            Objecttype de Natuurlijk persoon
                de te betalen belasting Numeriek (geheel getal);
            Regel r
                geldig altijd
                    De te betalen belasting van een Natuurlijk persoon moet gesteld worden op 0.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject : NatuurlijkPersoon("), () -> "klassenaam:\n" + drl);
        assertTrue(drl.contains("setTeBetalenBelasting("), () -> "setternaam:\n" + drl);
    }

    @Test void onschaalbare_telling_wordt_marker_geen_globale_telling() {
        // p11 (verdict C): a count whose element has NO relationship to the subject cannot be
        // scoped to it. It must surface as a marker, NOT a comment-placeholder accumulate that
        // would count EVERY instance globally (§5.8.1: a count ranges over the related instances).
        String src = """
            Objecttype de Reiziger
                het aantal dingen Numeriek (geheel getal);
            Objecttype de Ding
            Regel tel
                geldig altijd
                    Het aantal dingen van een Reiziger moet gesteld worden op het aantal Ding van de reiziger.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("accumulate("), () -> "een onschaalbare telling mag geen globale accumulate worden:\n" + drl);
        assertTrue(drl.contains("/* TODO"), () -> "verwachtte een marker:\n" + drl);
    }

    @Test void naar_veel_rol_scalar_read_wordt_marker_niet_een_getter() {
        // §13.3.9.1: a scalar read through a TO-MANY role ("de leeftijd van de passagier", where
        // passagier is the meerdere side) cannot read one of many via a scalar getter — it must be
        // an aggregation. Emit a marker, not $subject.getPassagier().getLeeftijd().
        String src = """
            Objecttype de Vlucht
                de uitkomst Numeriek (geheel getal);
            Objecttype de Passagier
                de leeftijd Numeriek (geheel getal);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    De uitkomst van een Vlucht moet gesteld worden op de leeftijd van de passagier.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO naar-veel-navigatie"), () -> "to-many read niet gemarkeerd:\n" + drl);
        assertFalse(drl.contains("getPassagier().getLeeftijd()"), () -> "mag geen scalar getter zijn:\n" + drl);
    }

    @Test void naar_een_rol_blijft_een_scalar_getter() {
        // the reverse direction (de vluchtprijs van de reis, reis = the één side) stays a getter.
        String src = """
            Objecttype de Vlucht
                de vluchtprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de uitkomst Numeriek (geheel getal);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    De uitkomst van een Passagier moet gesteld worden op de vluchtprijs van de reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject.getReis().getVluchtprijs()"), () -> "to-one getter ontbreekt:\n" + drl);
        assertFalse(drl.contains("naar-veel"), () -> "to-one mag geen marker zijn:\n" + drl);
    }

    @Test void telling_concatenatie_met_oplosbare_kenmerkfilters_wordt_een_union_accumulate() {
        // §4.11/§5.8.1: "het aantal X, Y en Z" (a concatenation) must PARSE, and when every operand
        // resolves to a kenmerk filter the union count lowers to ONE accumulate whose source ORs the
        // filters (p29 part 4) — NOT a silent count of only the first operand.
        String src = """
            Objecttype de Vlucht
                het aantal Numeriek (geheel getal);
            Objecttype de Passagier
                is jong kenmerk (bijvoeglijk);
                is oud kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het aantal van een Vlucht moet berekend worden als het aantal passagiers van de reis die een jong zijn en passagiers van de reis die een oud zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de concatenatie-telling moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("accumulate(") && drl.contains("jong == true") && drl.contains("oud == true")
                && drl.contains("||"), () -> "de union-telling moet één accumulate met OR-filters zijn:\n" + drl);
        assertFalse(drl.contains("/* TODO aggregatie AANTAL"), () -> "de oplosbare union mag geen marker zijn:\n" + drl);
    }

    @Test void vergelijking_subselectie_wordt_een_accumulate_filter() {
        // §5.6 getalpredicaat "die kleiner zijn dan X": the subselectie comparison filters the AGGREGATED attribute
        // (prijs) on the accumulate source pattern — only matching elements are summed. Was an honest marker (no
        // attribute named); now lowers. RED-before-green: before this it emitted a TODO aggregatie SOM marker.
        String src = """
            Objecttype de Vlucht
                het totaal Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het totaal van een Vlucht moet berekend worden als de som van de prijs van alle passagiers van de reis die kleiner zijn dan 50.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de vergelijking-subselectie moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("accumulate(") && drl.contains("prijs < new java.math.BigDecimal(\"50\")"),
                () -> "de comparison-subselectie moet de accumulate-bron filteren op prijs < 50:\n" + drl);
        assertFalse(drl.contains("/* TODO aggregatie SOM"), () -> "de oplosbare comparison-subselectie mag geen marker zijn:\n" + drl);
    }

    @Test void vergelijking_subselectie_met_parameter_rhs_wordt_accumulate_filter() {
        // §5.6 the spec's worked-example RHS "die kleiner zijn dan de volwassenleeftijd" — a Parameter-global RHS. The
        // RHS is bouw()'d in the SUBJECT context, but a Parameter is a context-independent GLOBAL, so it does not
        // mis-bind. DRL reads it as a free name in the accumulate source ("prijs < grens"); the exec binds it on the
        // source pattern (a 2-var expr). Was an honest marker (the exec could not bind a global there); now FIRES in both.
        String src = """
            Parameter de grens : Numeriek (geheel getal)
            Objecttype de Vlucht
                het totaal Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het totaal van een Vlucht moet berekend worden als de som van de prijs van alle passagiers van de reis die kleiner zijn dan de grens.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de parameter-RHS subselectie moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("accumulate(") && drl.contains("prijs < grens"),
                () -> "een parameter-RHS subselectie moet de accumulate-bron filteren op 'prijs < grens':\n" + drl);
        assertFalse(drl.contains("/* TODO aggregatie SOM"),
                () -> "de parameter-RHS subselectie mag geen marker meer zijn:\n" + drl);
    }

    @Test void vergelijking_subselectie_met_subject_attribuut_rhs_blijft_marker() {
        // Review guard: the comparison-subselectie RHS is bouw()'d in the rule SUBJECT context, so a subject/element
        // ATTRIBUTE ("zijn drempel") mis-navigates to the WRONG object's attribute (the spec intends the element). Only a
        // LITERAL or a context-independent Parameter lowers; an AttribuutRef RHS stays an honest, symmetric marker.
        String src = """
            Objecttype de Vlucht
                het totaal Numeriek (geheel getal);
                de drempel Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het totaal van een Vlucht moet berekend worden als de som van de prijs van alle passagiers van de reis die kleiner zijn dan zijn drempel.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("prijs < drempel"),
                () -> "een subject-attribuut subselectie-RHS mag NIET in de accumulate-bron belanden (mis-bind):\n" + drl);
        assertTrue(drl.contains("/* TODO aggregatie SOM"),
                () -> "een subject-attribuut subselectie-RHS moet een eerlijke marker blijven:\n" + drl);
    }

    @Test void compound_subselectie_lowert_naar_gefilterde_accumulate() {
        // §13.4.13.4 the COMPOUND subselectie "die aan alle volgende voorwaarden voldoen: - … - …" (subject-FUL bullets,
        // the spec EBNF form) — each bullet resolves against the ELEMENT (the RuleChecker callback), an ALLE compound
        // AND-ed on the accumulate source. Was an honest marker; now lowers (DRL conditieInline already handles ALLE).
        String src = """
            Objecttype de Vlucht
                het totaal Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het totaal van een Vlucht moet berekend worden als de som van de prijs van alle passagiers van de reis die aan alle volgende voorwaarden voldoen:
                    - zijn leeftijd is kleiner dan 18
                    - hij is minderjarig.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de compound subselectie moet parseren+resolven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("/* TODO aggregatie"), () -> "de compound subselectie mag geen marker meer zijn:\n" + drl);
        assertTrue(drl.contains("leeftijd < new java.math.BigDecimal(\"18\")") && drl.contains("minderjarig == true"),
                () -> "de compound filter (leeftijd<18 && minderjarig) moet op de accumulate-bron staan:\n" + drl);
    }

    @Test void compound_subselectie_telling_met_enkele_global_lowert_naar_gefilterde_accumulate() {
        // §13.4.13.4 a COUNTING quantifier ("ten minste 1 van de") compound whose bullet reads a SINGLE
        // Parameter-global ("kleiner dan de grens"): DRL reads the global as a free name in the OR-of-bullets on the
        // accumulate source; the exec binds that one global on the source pattern (a 2-var count-fold). Was an honest
        // marker in both; now FIRES in both (only a >1-global / non-bindable counting compound stays a marker).
        String src = """
            Parameter de grens : Numeriek (geheel getal)
            Objecttype de Vlucht
                het totaal Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het totaal van een Vlucht moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:
                    - zijn leeftijd is kleiner dan de grens
                    - hij is minderjarig.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren+resolven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("/* TODO aggregatie"), () -> "een counting+enkele-global compound subselectie mag geen marker meer zijn:\n" + drl);
        assertTrue(drl.contains("leeftijd < grens") && drl.contains("minderjarig == true") && drl.contains("||"),
                () -> "de ten-minste-1 fold moet de global vrij lezen in een OR op de accumulate-bron:\n" + drl);
    }

    @Test void compound_subselectie_telling_met_twee_globals_blijft_marker() {
        // §13.4.13.4 PARITY guard: a COUNTING quantifier compound reading TWO DISTINCT Parameter-globals cannot be
        // bound in the exec count-fold (one accumulate-source expr binds at most one global) — so it stays an honest
        // marker in BOTH back-ends to stay symmetric (DRL would otherwise read both free names and fire: a masked
        // DRL-fires/exec-skips). The SINGLE-global case fires (test above); only >1-distinct-global is restricted.
        String src = """
            Parameter de ondergrens : Numeriek (geheel getal)
            Parameter de bovengrens : Numeriek (geheel getal)
            Objecttype de Vlucht
                het totaal Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het totaal van een Vlucht moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:
                    - zijn leeftijd is kleiner dan de ondergrens
                    - zijn prijs is groter dan de bovengrens.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren+resolven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie"),
                () -> "een counting+twee-globals compound subselectie moet een eerlijke marker blijven (exec kan twee globals niet binden in de count-fold):\n" + drl);
    }

    @Test void compound_subselectie_telling_met_geneste_compound_bullet_blijft_marker() {
        // §13.4.13.4 PARITY guard: a COUNTING quantifier compound with a NESTED compound bullet ("- hij voldoet aan
        // alle volgende voorwaarden: …") cannot lower in the exec count-fold — ExecModelTranspiler.leafPredicate has no
        // nested-Samengesteld branch and returns null, skipping the whole compound, while DRL conditie lowers the nested
        // compound to ||/&& on the accumulate source and FIRES. The differential's overgeslagen-skip would MASK that
        // DRL-fires/exec-skips, so the resolver markers it (Onopgelost) -> BOTH decline symmetrically.
        String src = """
            Parameter de grens : Numeriek (geheel getal)
            Objecttype de Vlucht
                het totaal Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het totaal van een Vlucht moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:
                    - zijn leeftijd is kleiner dan de grens
                    - hij voldoet aan alle volgende voorwaarden:
                        - hij is minderjarig
                        - zijn prijs is groter dan 50.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren+resolven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie"),
                () -> "een counting compound met een geneste-compound bullet moet een eerlijke marker blijven (exec leafPredicate kan een geneste compound niet vouwen):\n" + drl);
    }

    @Test void cross_unit_vergelijking_in_voorwaarde_is_null_veilig_via_rsHerschaal() {
        // §5.1 a cross-unit COMPARISON in a constraint (leeftijd jr vs grens mnd) restates an operand by its factor.
        // A bare (leeftijd).multiply(12) NPE-aborts the Drools session on a leeg leeftijd (and the value-side null-guard
        // ternary mis-parses in a pattern constraint), so the restatement goes through the null-safe rsHerschaal helper
        // (the DRL mirror of the exec scale): a leeg operand restates to leeg -> mvel `null > grens` no-match, no crash.
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Parameter de grens : Numeriek (geheel getal) met eenheid mnd
            Objecttype de Passagier
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is duur kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is duur indien zijn leeftijd groter is dan de grens.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren+resolven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("/* TODO"), () -> "een cross-unit vergelijking moet vuren (geen marker):\n" + drl);
        assertTrue(drl.contains("function java.math.BigDecimal rsHerschaal"), () -> "de rsHerschaal helper moet geregistreerd zijn:\n" + drl);
        assertTrue(drl.contains("rsHerschaal(leeftijd,"), () -> "de constraint moet de operand null-veilig herschalen:\n" + drl);
        // the ONLY .multiply is inside the rsHerschaal function body — never a bare (operand).multiply in the constraint
        assertTrue(drl.lines().filter(l -> l.contains(".multiply")).allMatch(l -> l.contains("x.multiply(f)")),
                () -> "geen ongegarde .multiply buiten de rsHerschaal helper:\n" + drl);
    }

    @Test void genavigeerde_operand_in_rekenkundige_voorwaarde_leest_null_veilig_via_rsRelGetal() {
        // §6.2/§4.1 a navigated NUMERIC leg inside arithmetic in a CONDITION ("de vertrektijd van zijn reis plus zijn
        // basistarief > zijn prijs"). mvel's `reis!.vertrektijd` short-circuits the WHOLE constraint to a non-match on an
        // absent reis, dropping the §4.1 Tabel-3 leeg-as-0 (which should compute 0+basistarief). The leg now reads through
        // the null-safe rsRelGetal helper, so a null relation yields a leeg (null) value rsNul folds to 0 — leeg-as-0, not
        // a short-circuit. (The STANDALONE navigated comparison keeps mvel's `!.`; only the arithmetic leg changes.)
        String src = """
            Objecttype de Vlucht
                de vertrektijd Numeriek (geheel getal);
            Objecttype de Passagier
                het basistarief Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
                is duur kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel R
                geldig altijd
                    Een Passagier is duur indien de vertrektijd van zijn reis plus zijn basistarief groter is dan zijn prijs.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren+resolven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("/* TODO"), () -> "een navigated rekenkundige operand moet vuren (geen marker):\n" + drl);
        assertTrue(drl.contains("function java.math.BigDecimal rsRelGetal"), () -> "de rsRelGetal helper moet geregistreerd zijn:\n" + drl);
        assertTrue(drl.contains("rsRelGetal(reis, \"vertrektijd\")"), () -> "de navigated arithmetic-leg moet null-veilig via rsRelGetal lezen:\n" + drl);
        // the navigated leg inside arithmetic must NOT use mvel's `!.` short-circuit (which drops the leeg-as-0)
        assertFalse(drl.contains("reis!.vertrektijd"), () -> "een navigated arithmetic-leg mag mvel's `!.` short-circuit NIET gebruiken:\n" + drl);
    }

    @Test void compound_subselectie_telling_met_cross_unit_global_lowert_via_rsHerschaal() {
        // §13.4.13.4 a COUNTING quantifier + single global whose comparison needs a §5.1 UNIT RESTATEMENT (leeftijd jr
        // vs grens mnd) now FIRES in both back-ends: DRL restates the operand on the accumulate source via the null-safe
        // rsHerschaal helper (was an unguarded .multiply that NPE-aborted on a leeg operand -> a marker); the exec scale
        // is null-safe. So a convertible-but-different-unit global bullet under a counting quantifier no longer markers.
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Parameter de grens : Numeriek (geheel getal) met eenheid mnd
            Objecttype de Vlucht
                het totaal Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is minderjarig kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het totaal van een Vlucht moet berekend worden als de som van de prijs van alle passagiers van de reis die aan ten minste 1 van de volgende voorwaarden voldoen:
                    - zijn leeftijd is kleiner dan de grens
                    - hij is minderjarig.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren+resolven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("/* TODO aggregatie"), () -> "een cross-unit global counting compound mag geen marker meer zijn:\n" + drl);
        assertTrue(drl.contains("rsHerschaal(leeftijd,") && drl.contains("accumulate("),
                () -> "de cross-unit global moet null-veilig op de accumulate-bron herschaald worden:\n" + drl);
    }

    @Test void telling_concatenatie_met_onoplosbare_operand_blijft_marker() {
        // A union with an operand that has NO resolvable kenmerk filter (here a bare set) cannot be
        // lowered faithfully — it stays an honest marker, never a count of only the first operand.
        String src = """
            Objecttype de Vlucht
                het aantal Numeriek (geheel getal);
            Objecttype de Passagier
                is jong kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het aantal van een Vlucht moet berekend worden als het aantal passagiers van de reis die een jong zijn en passagiers van de reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de concatenatie-telling moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie AANTAL"), () -> "een onoplosbare union moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("accumulate("), () -> "een onoplosbare union mag niet stil tellen:\n" + drl);
    }

    @Test void union_over_verschillende_elementtypes_blijft_marker() {
        // p29(4) review (HIGH): a single accumulate has one source pattern, so operands ranging over
        // DIFFERENT element types cannot be unioned — even when they share a kenmerk name. Resolving
        // every operand against operand-0's element would silently drop the foreign set and OR its
        // filter onto the wrong fact. Such a union must stay a marker, never a wrong count.
        String src = """
            Objecttype de Vlucht
                het aantal Numeriek (geheel getal);
            Objecttype de Passagier
                is jong kenmerk (bijvoeglijk);
            Objecttype de Bemanning
                is jong kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype Vlucht met bemanning
                de vlucht Vlucht
                het bemanningslid Bemanning
            Eén vlucht betreft meerdere bemanningsleden
            Regel r
                geldig altijd
                    Het aantal van een Vlucht moet berekend worden als het aantal passagiers van de reis die een jong zijn, bemanningsleden van de reis die een jong zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie AANTAL"),
                () -> "een union over verschillende elementtypes moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("accumulate("), () -> "een type-mismatch union mag niet stil tellen:\n" + drl);
    }

    @Test void subselectie_vragende_meervoud_vergelijking_parseert_voortaan() {
        // §13.4.13.5-.8: the elementair predicate after 'die'/'dat' is the VRAGENDE-MEERVOUD
        // (verb-trailing) operator — "die kleiner zijn dan X". The grammar used to route the
        // predicaat through the verb-LEADING nonToplevelOperator only, so this spec form was a
        // syntax error ("input not fully consumed at 'die'"). It must now PARSE. A comparison filter
        // over a counted element is not yet lowered, so it stays an HONEST marker (a visible no-op),
        // never a silent count over EVERY passenger.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de leeftijd Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die kleiner zijn dan 18.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(),
                () -> "de vragende-meervoud vergelijking moet voortaan parseren (geen syntaxfout): " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie AANTAL"),
                () -> "een nog-niet-verlaagde vergelijkingsfilter blijft een eerlijke marker:\n" + drl);
        assertFalse(drl.contains("accumulate("),
                () -> "de vergelijkingsfilter mag niet stil over ALLE passagiers tellen:\n" + drl);
    }

    @Test void subselectie_kenmerk_via_vragende_register_lowert_naar_kenmerkfilter() {
        // Guard against a MASKED regression: routing the predicaat through toplevelOperator means a
        // kenmerk subselectie "die een minderjarige zijn" now parses via the verb-trailing register
        // instead of nonToplevelOperator. elementKenmerkFilter must read EITHER register, so the
        // §13.4.2 kenmerk filter still binds (minderjarig == true) and the count is not silently lost.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                is minderjarig kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die een minderjarige zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("minderjarig == true"),
                () -> "de kenmerkfilter moet ook via het vragende register een accumulate-constraint blijven:\n" + drl);
        assertFalse(drl.contains("/* TODO aggregatie AANTAL"),
                () -> "de oplosbare kenmerk-subselectie mag geen marker worden:\n" + drl);
    }

    @Test void subselectie_verbleidende_bezit_die_heeft_een_kind_blijft_parsebaar() {
        // The grammar fix is ADDITIVE: the verb-leading nonToplevelOperator is kept, so a possession
        // query written verb-leading after 'die' ("die heeft een kind") still PARSES. A possession is
        // not the element's boolean kenmerk, so it stays an honest marker — unchanged behaviour.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Kind
                de leeftijd Numeriek (geheel getal);
            Objecttype de Passagier
                de naam Tekst;
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype Passagier met kinderen
                de ouder Passagier
                het kind Kind
            Eén ouder betreft meerdere kinderen
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die heeft een kind.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(),
                () -> "de verb-leidende bezit-subselectie 'die heeft een kind' moet blijven parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie AANTAL"),
                () -> "een bezit-subselectie blijft een eerlijke marker:\n" + drl);
    }

    @Test void negatie_elfproef_en_numeriek_met_exact_loweren_naar_niet_helper() {
        // §8.1 Tabel 16 NEGATED elfproef / numeriek-met-exact ("voldoet NIET aan de elfproef", "is NIET numeriek met
        // exact N cijfers", stellende/bullet register). The elfproef lowers to !rsElfproef and the numeriek-met-exact
        // to !rsNumeriekMetExact(bsn, N) — the §8.1.4 digit-count N is now captured on the IR Predicaat.
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de bsn Tekst;
                de leeftijd Numeriek (geheel getal);
                is verdacht kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is verdacht indien hij aan alle volgende voorwaarden voldoet:
                    - zijn bsn voldoet niet aan de elfproef
                    - zijn bsn is niet numeriek met exact 9 cijfers.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de NIET-vormen moeten parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("(!rsElfproef(bsn))"),
                () -> "de negatie-elfproef moet !rsElfproef zijn:\n" + drl);
        assertTrue(drl.contains("(!rsNumeriekMetExact(bsn, 9))"),
                () -> "de negatie-numeriek-met-exact moet !rsNumeriekMetExact(bsn, 9) zijn:\n" + drl);
        assertFalse(drl.contains("/* TODO voorwaarde"),
                () -> "geen van beide predicaten mag nog een marker zijn:\n" + drl);
    }

    @Test void positieve_numeriek_met_exact_lowert_met_aantal_cijfers() {
        // §8.1.4: "numeriek met exact N cijfers" lowers to rsNumeriekMetExact(operand, N) in both registers — the
        // vragende "numeriek is met exact" (under indien) and the stellende "is numeriek met exact" (in a bullet).
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de bsn Tekst;
                is geldig kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is geldig indien zijn bsn numeriek is met exact 9 cijfers.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsNumeriekMetExact(bsn, 9)") && !drl.contains("!rsNumeriekMetExact"),
                () -> "de positieve numeriek-met-exact moet een kale rsNumeriekMetExact(bsn, 9) zijn:\n" + drl);
    }

    @Test void positieve_elfproef_blijft_zonder_uitroepteken() {
        // Guard the negation didn't flip the positive form: "aan de elfproef voldoet" (vragende, under indien) stays a
        // bare rsElfproef, no leading '!'.
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de bsn Tekst;
                is verdacht kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is verdacht indien zijn bsn aan de elfproef voldoet.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsElfproef(bsn)") && !drl.contains("!rsElfproef(bsn)"),
                () -> "de positieve elfproef moet een kale rsElfproef blijven:\n" + drl);
    }

    @Test void vragende_negatie_elfproef_recovert_de_geabsorbeerde_niet() {
        // §8.1 Tabel 16 vragende negated form "indien X niet aan de elfproef voldoet": the leading "niet" is greedily
        // absorbed into the operand kaleNaam (NIET is a kaleNaamWoord), so op.NIET() is null. The resolver recovers it
        // — possessief resolves the bare attribuut but keeps the full bron, and elfOperand re-derives negatie from the
        // trailing "niet". Lowers to !rsElfproef / !rsNumeriekMetExact, just like the stellende form.
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de bsn Tekst;
                is verdacht kenmerk (bijvoeglijk);
                is ongeldig kenmerk (bijvoeglijk);
            Regel A
                geldig altijd
                    Een Persoon is verdacht indien zijn bsn niet aan de elfproef voldoet.
            Regel B
                geldig altijd
                    Een Persoon is ongeldig indien zijn bsn niet numeriek is met exact 9 cijfers.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de vragende NIET-vormen moeten parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("!rsElfproef(bsn)"), () -> "de vragende elfproef-negatie moet !rsElfproef zijn:\n" + drl);
        assertTrue(drl.contains("!rsNumeriekMetExact(bsn, 9)"), () -> "de vragende numeriek-negatie moet !rsNumeriekMetExact zijn:\n" + drl);
        assertFalse(drl.contains("/* TODO voorwaarde"), () -> "geen van beide mag een marker zijn:\n" + drl);
    }

    @Test void geabsorbeerde_niet_op_niet_elfproef_operator_markeert_niet_inverteert() {
        // Review regression guard: possessief resolves "zijn <attr> niet" to the bare attribuut (needed for the
        // elfproef/numeriek vragende negation). For ANY OTHER operator the dangling "niet" cannot be honoured, so it
        // must MARKER — never silently emit the inverted positive ("zijn leeftijd niet groter is dan 5" must NOT become
        // leeftijd > 5; "zijn bsn niet leeg is" must NOT become bsn == null).
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de leeftijd Numeriek (geheel getal);
                de bsn Tekst;
                is minderjarig kenmerk (bijvoeglijk);
            Regel A
                geldig altijd
                    Een Persoon is minderjarig indien zijn leeftijd niet groter is dan 5.
            Regel B
                geldig altijd
                    Een Persoon is minderjarig indien zijn bsn niet leeg is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("leeftijd > new java.math.BigDecimal(\"5\")"),
                () -> "een geabsorbeerde 'niet' mag de vergelijking NIET als positief lowereren:\n" + drl);
        assertFalse(drl.contains("bsn == null"),
                () -> "een geabsorbeerde 'niet' mag leeg NIET inverteren:\n" + drl);
        assertTrue(drl.contains("/* TODO voorwaarde: zijn leeftijd niet groter is dan 5 */")
                && drl.contains("/* TODO voorwaarde: zijn bsn niet leeg is */"),
                () -> "beide moeten een eerlijke marker zijn:\n" + drl);
    }

    @Test void vragende_elfproef_negatie_eerbiedigt_verbatim_attribuut_op_niet() {
        // Verbatim-first guard: an attribuut literally named "... niet" must bind itself (positive), not be de-negated.
        // "zijn saldo niet aan de elfproef voldoet" with an attribuut "saldo niet" -> a bare rsElfproef on saldoNiet.
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de saldo niet Tekst;
                is verdacht kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is verdacht indien zijn saldo niet aan de elfproef voldoet.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsElfproef(saldoNiet)") && !drl.contains("!rsElfproef"),
                () -> "een attribuut 'saldo niet' moet verbatim (positief) binden, niet ge-de-negeerd worden:\n" + drl);
    }

    @Test void is_niet_een_zelfstandig_kenmerk_lowert_naar_false() {
        // §8.1.8.3 Tabel 16: the zelfstandig kenmerkcheck NEGATION stellende form "is niet een <k>". The "een"
        // (a separate token) used to break the parse — "niet" lexed into the kaleNaam but "een" could not follow it.
        // The explicit nonToplevelOperator alt (IS|ZIJN) NIET EEN kaleNaam now binds the negated zelfstandig kenmerk.
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de student kenmerk;
                is jong kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is jong indien hij aan alle volgende voorwaarden voldoet:
                    - hij is niet een student
                    - hij is een student.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "is niet een <k> moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("student == false"), () -> "de zelfstandig-kenmerk negatie moet '== false' zijn:\n" + drl);
        assertTrue(drl.contains("student == true"),  () -> "de positieve EEN-vorm moet '== true' blijven:\n" + drl);
    }

    @Test void is_niet_een_rol_lowert_inverse_rolcheck_met_waarschuwing() {
        // Review finding: "is niet een <rolnaam>" binds the NEGATED rolcheck (the subject does NOT fill the role) and
        // lowers to the inverse null-test, the same meaning as "is geen <rol>". But Tabel 16 sanctions only
        // "is/heeft geen <rolnaam>" for a rolcheck negation (§8.1.7), so the unsanctioned writing raises a WARNING
        // (a lint, never a wrong gate — the lowering is unchanged).
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                is duur kenmerk (bijvoeglijk);
            Objecttype de Vlucht (mv: Vluchten)
            Feittype reis van passagier
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel R
                geldig altijd
                    Een Passagier is duur indien hij aan alle volgende voorwaarden voldoet:
                    - hij is niet een passagier
                    - hij is duur.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("reis == null"), () -> "de negatieve rolcheck moet de inverse null-test zijn:\n" + drl);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.severity() == RegelSpraakModel.Severity.WARNING
                        && d.message().contains("rolnaam")),
                () -> "de niet-voorziene 'is niet een <rol>' schrijfwijze moet waarschuwen: " + m.diagnostics);
    }

    @Test void is_niet_bijvoeglijk_kenmerk_absorbeert_niet_in_kalenaam() {
        // Guard: the new NIET EEN alt must NOT change the bijvoeglijk "is niet <k>" parse, where the "niet" lexes
        // INTO the kaleNaam (load-bearing for the §8.1.8 verbatim-first de-negation). No "een" -> the NIET-EEN alt
        // does not fire; bindKenmerk de-negates "niet jong" -> jong == false.
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                de student kenmerk;
                is jong kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is student indien hij aan alle volgende voorwaarden voldoet:
                    - hij is niet jong
                    - hij is een student.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("jong == false"), () -> "bijvoeglijk 'is niet jong' moet jong == false blijven:\n" + drl);
    }

    @Test void uniciteit_lowert_naar_een_accumulate_count() {
        // §8.1.6: a uniqueness check becomes a rule that binds the non-null key on the subject and
        // counts the instances whose key equals it; more than one (the subject plus a twin) is the
        // violation. Counting (not an exists self-join) flags each subject exactly once.
        String src = """
            Objecttype de Natuurlijk persoon
                het burgerservicenummer Numeriek (geheel getal);
            Regel bsn uniek
                geldig altijd
                    De burgerservicenummers van alle Natuurlijk persoon moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject : NatuurlijkPersoon( $k0 : burgerservicenummer != null )"),
                () -> "de sleutel moet niet-leeg gebonden worden:\n" + drl);
        assertTrue(drl.contains("Number( intValue > 1 ) from accumulate( $o : NatuurlijkPersoon( burgerservicenummer == $k0 ), count($o) )"),
                () -> "meer dan een instantie met dezelfde sleutel is de schending:\n" + drl);
    }

    @Test void uniciteit_met_gedeclareerd_onregelmatig_attribuut_meervoud_lowert() {
        // §8.1.6 writes the attribute in its meervoudsvorm; an attribuut is a naamwoord, so it may
        // declare "(mv: …)". The declared plural (incl. an irregular -eren one synthesis refuses)
        // binds the singular attribute exactly, so the uniqueness rule lowers.
        String src = """
            Objecttype de Reiziger
                het kind (mv: kinderen) Numeriek (geheel getal);
            Regel uniek
                geldig altijd
                    De kinderen van alle Reiziger moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject : Reiziger( $k0 : kind != null )"),
                () -> "de gedeclareerde meervoudsvorm moet het enkelvoud-attribuut binden:\n" + drl);
        assertTrue(drl.contains("from accumulate( $o : Reiziger( kind == $k0 ), count($o) )"),
                () -> drl);
    }

    @Test void uniciteit_over_onbekend_type_blijft_een_waarschuwing() {
        // The object type must resolve; "alle Routes" with no Route declared cannot lower — it stays a
        // WARNING and emits no rule, rather than silently dropping the consistency check.
        String src = """
            Objecttype de Vlucht
                het vluchtnummer Numeriek (geheel getal);
            Regel x
                geldig altijd
                    De vluchtnummers van alle Routes moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("consistentieregel")),
                "een onresolvebare uniciteit moet waarschuwen");
        assertFalse(DrlTranspiler.transpile(m).contains("accumulate("),
                "een onresolvebare uniciteit mag geen regel opleveren");
    }

    @Test void uniciteit_verenigd_met_verschillende_datatypes_blijft_een_waarschuwing() {
        // §8.1.6: a vereniging combines attributes of the SAME datatype. A Tekst attribute unioned with
        // a Numeriek one is ill-formed (mvel's coercive == would cross-match "5" and 5) -> WARNING, no rule.
        String src = """
            Objecttype de Cirkel
                de naam Tekst;
            Objecttype het Vierkant
                de code Numeriek (geheel getal);
            Regel mismatch
                geldig altijd
                    De namen van alle Cirkels verenigd met de codes van alle Vierkanten moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("consistentieregel")),
                "een datatype-mismatch vereniging moet waarschuwen");
        assertFalse(DrlTranspiler.transpile(m).contains("accumulate("),
                "een datatype-mismatch vereniging mag geen regel opleveren");
    }

    @Test void uniciteit_verenigd_met_verschillende_eenheden_blijft_een_waarschuwing() {
        // §8.1.6 "hetzelfde datatype": for Numeriek the unit is part of the datatype, and units do not
        // survive into the fact model (bare BigDecimals), so a EUR ∪ jr union would cross-match "5 EUR" and
        // "5 jr" as one value -> a silently-wrong false duplicate. A unit mismatch must WARN, no rule.
        String src = """
            Eenheidsysteem Geld
                de euro EUR
            Eenheidsysteem Tijd
                de jaar jr
            Objecttype de Konto
                het saldo Numeriek (geheel getal) met eenheid EUR;
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal) met eenheid jr;
            Regel mismatch
                geldig altijd
                    De saldos van alle Konto verenigd met de leeftijden van alle Persoon moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("consistentieregel")),
                "een eenheid-mismatch vereniging moet waarschuwen");
        assertFalse(DrlTranspiler.transpile(m).contains("accumulate("),
                "een eenheid-mismatch vereniging mag geen regel opleveren");
    }

    @Test void uniciteit_verenigd_met_zelfde_eenheid_lowert() {
        // The same unit on both sources is a well-formed union and must still lower (no false WARN).
        String src = """
            Eenheidsysteem Geld
                de euro EUR
            Objecttype de Konto
                het saldo Numeriek (geheel getal) met eenheid EUR;
            Objecttype de Rekening
                het saldo Numeriek (geheel getal) met eenheid EUR;
            Regel uniek
                geldig altijd
                    De saldos van alle Konto verenigd met de saldos van alle Rekening moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("accumulate("), () -> "een zelfde-eenheid vereniging moet lowereren:\n" + drl);
        assertFalse(m.diagnostics.stream().anyMatch(d -> d.message().contains("consistentieregel")),
                () -> "een zelfde-eenheid vereniging mag niet waarschuwen: " + m.diagnostics);
    }

    @Test void samengestelde_consistentie_alle_lowert_naar_or_van_schendingen() {
        // §13.4.8 "alle volgende criteria": the violation is OR of the per-criterion violations.
        String src = """
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
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("saldo >= limiet) || (") && drl.contains("saldo <= minimum"),
                () -> "de schending moet de OR van de criterium-schendingen zijn:\n" + drl);
    }

    @Test void samengestelde_consistentie_geen_kwantor_blijft_een_waarschuwing() {
        // GEEN / ten-minste / ten-hoogste / precies need a satisfied-criterion COUNT the single
        // violation rule cannot express yet -> honest WARNING, no rule (not a silent inert eval).
        String src = """
            Objecttype de Konto
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
            Regel geen
                geldig altijd
                    Een Konto moet voldoen aan geen van de volgende criteria:
                        - het saldo van een Konto is kleiner dan de limiet van een Konto.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("consistentieregel")),
                "een GEEN-kwantor samengestelde consistentie moet waarschuwen");
        assertFalse(DrlTranspiler.transpile(m).contains("rule \"geen\""),
                "een niet-omgezette samengestelde consistentie mag geen regel opleveren");
    }

    @Test void samengestelde_consistentie_met_gemengde_subjecten_blijft_een_waarschuwing() {
        // The rule lowers to ONE subject pattern, so criteria over DIFFERENT object types cannot be
        // lifted (a foreign attribute on the wrong fact would not compile, sinking the DRL) -> WARNING.
        String src = """
            Objecttype de Konto
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
            Objecttype de Klant
                de leeftijd Numeriek (geheel getal);
            Regel mix
                geldig altijd
                    Een Konto moet voldoen aan alle volgende criteria:
                        - het saldo van een Konto is kleiner dan de limiet van een Konto.
                        - de leeftijd van een Klant is groter dan de limiet van een Konto.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("consistentieregel")),
                "een compound over gemengde subjecten moet waarschuwen");
        assertFalse(DrlTranspiler.transpile(m).contains("rule \"mix\""),
                "een gemengd-subject compound mag geen regel opleveren");
    }

    @Test void uniciteit_parent_scoped_telt_binnen_de_ouder() {
        // §8.1.6 "de X van alle Y van <parent>": unique within each parent — the accumulate binds the
        // subject's scope ref and counts only same-scope instances.
        String src = """
            Objecttype de Vlucht
            Objecttype de Passagier
                het stoelnummer Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel stoel uniek
                geldig altijd
                    De stoelnummers van alle passagiers van de reis moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject : Passagier( $scope : reis != null, $k0 : stoelnummer != null )"),
                () -> "de scope (reis) moet niet-leeg gebonden worden:\n" + drl);
        assertTrue(drl.contains("from accumulate( $o : Passagier( reis == $scope, stoelnummer == $k0 ), count($o) )"),
                () -> "de telling moet binnen dezelfde reis blijven:\n" + drl);
    }

    @Test void feitcreatie_1hop_lowert_naar_een_join_en_modify_set() {
        // §13.4.7 feitcreatie, 1-hop: the new "bonusrelatie" is created on each passenger of the flight,
        // joined via the existing "passagier" relation (reis), set as the new relation's reference field.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de naam Tekst;
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
                    Een bonuspassagier van een vlucht is een passagier van de vlucht.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject : Vlucht(  )"), () -> "de anker (Vlucht) is het hoofd-patroon:\n" + drl);
        assertTrue(drl.contains("$doel : Passagier( reis == $subject )"),
                () -> "de doel-passagiers joinen via de bestaande rol (reis):\n" + drl);
        assertTrue(drl.contains("modify($doel) {") && drl.contains("setBonusvlucht( $subject )"),
                () -> "de nieuwe relatie wordt als referentieveld gezet:\n" + drl);
    }

    @Test void feitcreatie_meerstaps_lowert_naar_een_ketenjoin() {
        // The §9.4 TOKA shape: onderwerpD navigates anker -> Vlucht -> passagier. It lowers to a CHAIN of
        // patterns: a to-one hop reads the parent's forward reference (this == $subject.contingentvlucht),
        // a to-many hop reads the child's back reference (reis == $t0). The relation is set on the doel.
        // Here the Contingent is the MANY side of the contingentrelatie, so the FK side is derivable from
        // cardinality (cur holds it) without an objectcreatie.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de naam Tekst;
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
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject : Contingent("), () -> "anker-patroon ontbreekt:\n" + drl);
        assertTrue(drl.contains("$t0 : Vlucht( this == $subject.contingentvlucht )"),
                () -> "to-one tussenstap moet de ouder-referentie lezen:\n" + drl);
        assertTrue(drl.contains("$doel : Passagier( reis == $t0 )"),
                () -> "de doel-join moet naar de laatste tussenstap wijzen:\n" + drl);
        assertTrue(drl.contains("setBonuscontingent( $subject )"), () -> "de relatie moet op de doel gezet worden:\n" + drl);
    }

    @Test void feitcreatie_meerstaps_een_op_een_via_objectcreatie_lowert() {
        // The faithful §9.4 shape: the intermediate hop Contingent -> Vlucht is a true 1-to-1, so cardinality
        // alone cannot place the reference field — but the §9.3 objectcreatie ("Een Vlucht heeft het
        // contingent …") created the Contingent and set the link on it, so the field lives on the Contingent
        // (cur). The chain hop reads it as the parent's forward reference.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de naam Tekst;
            Objecttype de Contingent
                de aantal Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype contingentrelatie
                de contingentvlucht Vlucht
                de contingent Contingent
            Eén contingentvlucht heeft één contingent
            Feittype bonusrelatie
                de bonuscontingent Contingent
                de bonuspassagier Passagier
            Eén bonuscontingent kent meerdere bonuspassagiers
            Regel maak contingent
                geldig altijd
                    Een Vlucht heeft het contingent met aantal gelijk aan 5.
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een contingent is een passagier van de contingentvlucht van het contingent.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$t0 : Vlucht( this == $subject.contingentvlucht )"),
                () -> "een 1-1 die door objectcreatie wordt geplaatst moet lager komen (ouder-veld):\n" + drl);
    }

    @Test void feitcreatie_meerstaps_kale_een_op_een_blijft_een_waarschuwing() {
        // A true 1-to-1 intermediate hop with NEITHER a cardinality signal NOR an objectcreatie cannot have
        // its FK side derived from the model. It must WARN (honest), never guess a side and risk an
        // uncompilable wrong-side join.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de naam Tekst;
            Objecttype de Contingent
                de naam Tekst;
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype contingentrelatie
                de contingentvlucht Vlucht
                de contingent Contingent
            Eén contingentvlucht heeft één contingent
            Feittype bonusrelatie
                de bonuscontingent Contingent
                de bonuspassagier Passagier
            Eén bonuscontingent kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een contingent is een passagier van de contingentvlucht van het contingent.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("rule \"ken bonus toe\""),
                () -> "een onbepaalde 1-1 tussenstap mag geen relatie-regel emitteren:\n" + drl);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.severity() == RegelSpraakModel.Severity.WARNING
                        && d.message().contains("referentieveld-zijde")),
                () -> "een onbepaalde 1-1 tussenstap moet een eerlijke waarschuwing geven: " + m.diagnostics);
    }

    /** Vlucht/Passagier with the existing "passagier" relation plus a NEW relation whose roles +
     *  cardinaliteit + rule body are spliced in — the shared scaffold for the feitcreatie edge tests. */
    private static String feitcreatieBron(String nieuwFeittype, String regelBody) {
        return """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            """ + nieuwFeittype + """
            Regel ken bonus toe
                geldig altijd
                    """ + regelBody + "\n";
    }

    @Test void feitcreatie_inverse_cardinaliteit_blijft_een_waarschuwing() {
        // The reference field lives on the MANY side. When rolB ("hoofdpassagier") is the EEN side, the
        // new relation's field belongs on the anker, not the doel; lifting it would emit a setter Drools
        // cannot resolve (or link the wrong way). So it must WARN, never emit a rule.
        String ft = """
            Feittype hoofdrelatie
                de hoofdvlucht Vlucht
                de hoofdpassagier Passagier
            Meerdere hoofdvluchten kennen één hoofdpassagier
            """;
        String src = feitcreatieBron(ft, "Een hoofdpassagier van een vlucht is een passagier van de vlucht.");
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(DrlTranspiler.transpile(m).contains("rule \"ken bonus toe\""),
                () -> "inverse-cardinaliteit feitcreatie mag geen regel emitteren");
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("feitcreatie")
                        && d.message().contains("meervoudige kant")),
                () -> "verwachtte een cardinaliteit-waarschuwing: " + m.diagnostics);
    }

    @Test void feitcreatie_zonder_directe_anker_back_reference_blijft_een_waarschuwing() {
        // onderwerpD "de aansluitvlucht" resolves to the same TYPE (Vlucht) as the anker but is a
        // different flight reached by a role. A type-identity test would wrongly reuse the anker's own
        // join; the back-reference check requires onderwerpD to DIRECTLY name the anker -> WARN here.
        String ft = """
            Feittype Vlucht heeft aansluiting
                de hoofdvlucht Vlucht
                de aansluitvlucht Vlucht
            Eén hoofdvlucht heeft één aansluitvlucht
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            """;
        String src = feitcreatieBron(ft, "Een bonuspassagier van een vlucht is een passagier van de aansluitvlucht.");
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(DrlTranspiler.transpile(m).contains("rule \"ken bonus toe\""),
                () -> "een niet-directe back-reference mag geen regel emitteren");
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("feitcreatie")
                        && d.message().contains("back-reference")),
                () -> "verwachtte een back-reference-waarschuwing: " + m.diagnostics);
    }

    @Test void feitcreatie_self_relatie_blijft_een_waarschuwing() {
        // A non-wederkerig same-type feittype (voogd/pupil, both Persoon): the rule navigates via the
        // EEN-side role (voogd), so the doel is not the field-holding many side -> WARN, no rule.
        String src = """
            Objecttype de Persoon
                de naam Tekst;
            Feittype voogdij
                de voogd Persoon
                de pupil Persoon
            Eén voogd heeft meerdere pupillen
            Regel ken voogd toe
                geldig altijd
                    Een pupil van een persoon is een voogd van de persoon.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(DrlTranspiler.transpile(m).contains("rule \"ken voogd toe\""),
                () -> "een onder-bepaalde self-relatie mag geen regel emitteren");
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("feitcreatie")),
                () -> "verwachtte een feitcreatie-waarschuwing: " + m.diagnostics);
    }

    @Test void feitcreatie_meerdere_versies_krijgen_unieke_regelnamen() {
        // A time-bounded feitcreatie with two versions must get distinct DRL rule names (like every other
        // resultaatdeel kind), else Drools rejects the package with "Duplicate rule name".
        String ft = """
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            """;
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            """ + ft + """
            Regel ken bonus toe
                geldig vanaf 2020 t/m 2020
                    Een bonuspassagier van een vlucht is een passagier van de vlucht.
                geldig vanaf 2021
                    Een bonuspassagier van een vlucht is een passagier van de vlucht.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rule \"ken bonus toe #1\"") && drl.contains("rule \"ken bonus toe #2\""),
                () -> "versies van een feitcreatie krijgen geen unieke namen:\n" + drl);
        assertFalse(drl.contains("rule \"ken bonus toe\"\n"), () -> "de kale dubbele naam mag niet voorkomen:\n" + drl);
    }

    @Test void feitcreatie_doel_voorwaarde_komt_op_het_doel_patroon() {
        // A guard on the doel (the passengers) must host on the $doel pattern, not become a null marker
        // on the anker (which would make the rule silently never fire).
        String ft = """
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            """;
        String src = feitcreatieBron(ft,
                "Een bonuspassagier van een vlucht is een passagier van de vlucht\n"
                + "                    indien de prijs van de passagier groter is dan 18.");
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$doel : Passagier( reis == $subject, prijs > new java.math.BigDecimal(\"18\") )"),
                () -> "de doel-voorwaarde moet op het $doel-patroon staan:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "de doel-voorwaarde mag geen null-marker op de anker worden:\n" + drl);
    }

    @Test void feitcreatie_anker_voorwaarde_blijft_op_het_subject() {
        // A guard on the anker (Vlucht) stays on the $subject pattern.
        String ft = """
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            """;
        String src = feitcreatieBron(ft,
                "Een bonuspassagier van een vlucht is een passagier van de vlucht\n"
                + "                    indien de naam van de vlucht gelijk is aan \"KL123\".");
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject : Vlucht( naam == \"KL123\" )"),
                () -> "de anker-voorwaarde moet op het $subject-patroon staan:\n" + drl);
    }

    @Test void feitcreatie_anker_kenmerk_voorwaarde_komt_op_het_subject() {
        // A kenmerk guard on the anker resolves (subjectVan now yields the anker) and hosts on $subject.
        String src = """
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
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien de vlucht internationaal is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject : Vlucht( internationaal == true )"),
                () -> "een anker-kenmerk-voorwaarde moet op $subject resolven:\n" + drl);
        assertFalse(drl.contains("eval( false )"), () -> "een resolveerbare anker-kenmerk-voorwaarde mag de regel niet inert maken:\n" + drl);
    }

    @Test void feitcreatie_doel_kenmerk_voorwaarde_host_op_doel_pattern() {
        // p32: a feitcreatie guard naming a kenmerk of the navigated DOEL ("indien de passagier vip is")
        // resolves against the doel type and hosts on the $doel pattern (vip == true), like the comparison
        // case — it no longer falls through to an inert eval(false). The guard is built with the anker as
        // subject (a to-many navigation -> marker), then re-resolved with the doel as subject and tagged so
        // the transpiler emits it ONLY on $doel.
        String ft = """
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            """;
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                is vip kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            """ + ft + """
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien de passagier vip is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$doel : Passagier( reis == $subject, vip == true )"),
                () -> "een doel-kenmerk moet op het $doel-patroon hosten:\n" + drl);
        assertFalse(drl.contains("eval( false )"), () -> "het mag niet meer inert zijn:\n" + drl);
        assertFalse(drl.contains("Vlucht( vip"), () -> "het kenmerk mag NIET op de anker hosten:\n" + drl);
    }

    @Test void feitcreatie_doel_kenmerk_host_op_doel_ook_bij_gelijknamige_anker_kenmerk() {
        // p32 collision safety: with the SAME kenmerk name declared on BOTH the anker (Vlucht) and the doel
        // (Passagier), the guard "indien de passagier student is" names the doel. The eigenaar tag must pin it
        // to the $doel pattern — it must NOT host on the anker (which is tried first and also declares it).
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
                is student kenmerk (bijvoeglijk);
            Objecttype de Passagier
                is student kenmerk (bijvoeglijk);
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
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien de passagier student is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$doel : Passagier( reis == $subject, student == true )"),
                () -> "moet op $doel hosten, ondanks gelijknamig anker-kenmerk:\n" + drl);
        assertFalse(drl.contains("Vlucht( student"), () -> "mag NIET op de anker hosten:\n" + drl);
    }

    @Test void feitcreatie_genegeerd_doel_kenmerk_host_op_doel_als_false() {
        // p32 + p36: a NEGATED bezittelijk doel-kenmerk guard ("indien de passagier geen recht op korting
        // heeft") hosts on $doel as "== false" — the doel re-resolution preserves the negation.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                het recht op korting kenmerk (bezittelijk);
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
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien de passagier geen recht op korting heeft.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$doel : Passagier( reis == $subject, rechtOpKorting == false )"),
                () -> "een genegeerd doel-kenmerk moet op $doel hosten als == false:\n" + drl);
    }

    @Test void feitcreatie_echt_onlowerbare_voorwaarde_wordt_inert_geen_overcreatie() {
        // A guard that lowers to NEITHER anker nor doel — here an objecttype/role-membership test (§13.4.15.44,
        // left a marker, p28), not a doel kenmerk — must make the rule inert (eval(false)), NOT be dropped:
        // a dropped guard fires unconditionally and OVER-CREATES the relation.
        String ft = """
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            """;
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            """ + ft + """
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien de passagier een werkdag is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("eval( false )"),
                () -> "een echt onlowerbare voorwaarde moet de regel inert maken (geen over-creatie):\n" + drl);
    }

    @Test void consistentie_met_onresolveerbare_rechter_operand_waarschuwt() {
        // §9.5: a consistentie whose RIGHT operand is a foreign-type attribute (no navigation from the
        // subject) lowers to an inert eval(true) — it checks nothing. The transpiler must surface that as
        // a WARNING (eenSubject only inspects the LEFT operand), not drop the check silently.
        String src = """
            Objecttype de Vlucht
                de prijs Numeriek (geheel getal);
            Objecttype de Klant
                de leeftijd Numeriek (geheel getal);
            Regel controleer
                geldig altijd
                    De prijs van een Vlucht moet groter zijn dan de leeftijd van een Klant.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        String drl = DrlTranspiler.transpile(m);   // the WARNING is a lowering-phase diagnostic
        assertTrue(drl.contains("// onopgelost consistentieregel") && drl.contains("eval(true)"),
                () -> "een onresolveerbare consistentie lowert naar een no-op:\n" + drl);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.severity() == RegelSpraakModel.Severity.WARNING
                        && d.message().contains("consistentieregel") && d.message().contains("controleert niets")),
                () -> "de gedropte controle moet een WARNING geven: " + m.diagnostics);
    }

    @Test void onregelmatig_meervoud_kardinaliteit_maakt_de_rol_naar_veel() {
        // p33 downstream: the cardinaliteit "meerdere vakken" marks vak meervoudig via elimination, so a
        // scalar read through it ("de punten van het vak", a to-many role) is correctly a marker — not a
        // wrong scalar getter (getVak().getPunten()), which is what an unmarked role would have produced.
        String src = """
            Objecttype de Student
                de uitkomst Numeriek (geheel getal);
            Objecttype de Vak
                de punten Numeriek (geheel getal);
            Feittype inschrijving
                de student Student
                het vak Vak
            Eén student volgt meerdere vakken
            Regel r
                geldig altijd
                    De uitkomst van een Student moet gesteld worden op de punten van het vak.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO naar-veel-navigatie"),
                () -> "een scalar-read door de meervoudige (vakken) rol moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("getVak().getPunten()"),
                () -> "de onregelmatige-meervoud rol mag niet als to-one getter lowereren:\n" + drl);
    }

    @Test void consistentie_met_geldige_operanden_waarschuwt_niet() {
        // The same-subject comparison lowers cleanly, so no lowering WARNING must be raised.
        String src = """
            Objecttype de Vlucht
                de luchthavenVertrek Tekst;
                de luchthavenBestemming Tekst;
            Regel rondvlucht
                geldig altijd
                    De luchthavenVertrek van een Vlucht moet ongelijk zijn aan de luchthavenBestemming van een Vlucht.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        DrlTranspiler.transpile(m);
        assertFalse(m.diagnostics.stream().anyMatch(d -> d.message().contains("controleert niets")),
                () -> "een geldige consistentie mag geen lowering-WARNING geven: " + m.diagnostics);
    }

    /** A subject with an attribute + a single-word and multi-word kenmerk, related to a navigated type by a
     *  to-one and a to-many role — the scaffold for the navigated-kenmerk condition tests. */
    private static String navKenmerkBron(String regelBody) {
        return """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
            Objecttype de Woning
                is leegstaand kenmerk (bijvoeglijk);
                is bereikbaar per trein kenmerk (bijvoeglijk);
            Feittype bewoning
                de bewoner Persoon
                de woning Woning
            Eén bewoner heeft één woning
            Feittype eigendom
                de eigenaar Persoon
                de bezitting (mv: bezittingen) Woning
            Eén eigenaar heeft meerdere bezittingen
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1
                    """ + regelBody + "\n";
    }

    @Test void navigatie_kenmerk_to_one_lowert_met_nullguard() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(
                navKenmerkBron("indien de woning van een Persoon leegstaand is.")));
        assertTrue(drl.contains("woning != null && woning.leegstaand == true"),
                () -> "een to-one navigated kenmerk moet null-guarded lowereren:\n" + drl);
    }

    @Test void navigatie_kenmerk_meerwoordig_lowert() {
        // Review D2: the greedy parse absorbs the leading kenmerk words ("bereikbaar per") into the operand;
        // the resolver must reconstruct the full kenmerk so a multi-word navigated kenmerk binds.
        String drl = DrlTranspiler.transpile(Resolver.resolve(
                navKenmerkBron("indien de woning van een Persoon bereikbaar per trein is.")));
        assertTrue(drl.contains("woning.bereikbaarPerTrein == true"),
                () -> "een meerwoordig navigated kenmerk moet lowereren:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "het mag geen marker blijven:\n" + drl);
    }

    @Test void navigatie_kenmerk_naar_veel_blijft_marker() {
        // Review D1: a to-many role written plural ("de bezittingen") must NOT leak to a scalar constraint
        // (uncompilable) — the canonical role normalisation makes the to-many test see it.
        String drl = DrlTranspiler.transpile(Resolver.resolve(
                navKenmerkBron("indien de bezittingen van een Persoon leegstaand is.")));
        assertTrue(drl.contains("/* TODO"), () -> "een to-many navigated kenmerk moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("bezittingen.leegstaand"), () -> "het mag niet als scalar lowereren:\n" + drl);
    }

    @Test void kenmerk_negatie_subject_lowert_naar_false() {
        // §8.1.8 negation: "indien de Meting niet oud is" -> "oud == false".
        String src = """
            Objecttype de Meting
                de uitkomst Numeriek (geheel getal);
                is oud kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    De uitkomst van een Meting moet gesteld worden op 1 indien de Meting niet oud is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("oud == false"), () -> "subject-negatie moet naar '== false' lowereren:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "het mag geen marker blijven:\n" + drl);
    }

    @Test void kenmerk_negatie_navigatie_lowert_met_nullguard() {
        // A negated navigated kenmerk lowers to "<rol> != null && <rol>.<kenmerk> == false".
        String drl = DrlTranspiler.transpile(Resolver.resolve(
                navKenmerkBron("indien de woning van een Persoon niet leegstaand is.")));
        assertTrue(drl.contains("woning != null && woning.leegstaand == false"),
                () -> "navigated negatie moet null-guarded naar '== false' lowereren:\n" + drl);
    }

    @Test void declared_niet_kenmerk_bindt_positief_niet_als_negatie() {
        // A kenmerk DECLARED with a leading "niet" ("is niet ingezetene kenmerk") must bind positively —
        // the whole name is tried before stripping "niet", so it is not mistaken for a negation.
        String src = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                is niet ingezetene kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1 indien de Persoon niet ingezetene is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("nietIngezetene == true"),
                () -> "een gedeclareerd 'niet-…' kenmerk moet positief binden:\n" + drl);
    }

    @Test void bijvoeglijk_kenmerk_met_lidwoord_in_de_naam_bindt_zichzelf() {
        // p36 review (MED): a bijvoeglijk kenmerk has no separate bepaaldLidwoord? in its grammar rule and
        // naamWoordLower admits de/het, so "is de minimis kenmerk" is stored verbatim as "de minimis" (the
        // article is part of the name). A blind article-strip at lookup dropped the leading "de" and lost the
        // match (a marker). The condition must bind the kenmerk verbatim and lower to "deMinimis == true".
        String src = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                is de minimis kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1 indien de Persoon de minimis is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("deMinimis == true"),
                () -> "een bijvoeglijk kenmerk met een lidwoord in de naam moet verbatim binden:\n" + drl);
        assertFalse(drl.contains("TODO voorwaarde"),
                () -> "het mag geen marker worden door het lidwoord weg te strippen:\n" + drl);
    }

    @Test void kenmerk_met_lidwoord_bindt_de_volledige_naam_niet_een_kortere_broer() {
        // p36 review (MED): with both "de minimis voordeel" (stored verbatim via a leading article in the
        // name) and "minimis voordeel" declared, a strip-first lookup of the written "de minimis voordeel"
        // would bind the SHORTER sibling — a silent wrong gate. Verbatim-first must bind the full name.
        String src = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                het de minimis voordeel kenmerk;
                de minimis voordeel kenmerk;
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1 indien de Persoon de minimis voordeel heeft.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("deMinimisVoordeel == true"),
                () -> "de geschreven volledige naam moet binden, niet de kortere broer:\n" + drl);
    }

    @Test void consistentie_gelijkheid_beide_leeg_alleen_voor_niet_numeriek() {
        // p39 / typeringen Tabel 37 vs 38: a both-empty "gelijk aan" is "waar" for Numeriek (consistent) but
        // "fout" for a non-numeriek type (a violation). So the Tekst criterion gets the both-empty clause and
        // the Numeriek one does NOT.
        String src = """
            Objecttype de Konto
                de naam Tekst;
                de bijnaam Tekst;
                de saldo Numeriek (geheel getal);
                de tegoed Numeriek (geheel getal);
            Regel tekst
                geldig altijd
                    De naam van een Konto moet gelijk zijn aan de bijnaam van een Konto.
            Regel num
                geldig altijd
                    De saldo van een Konto moet gelijk zijn aan de tegoed van een Konto.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("naam != bijnaam || (naam == null && bijnaam == null)"),
                () -> "een niet-numerieke beide-leeg gelijkheid moet een schending zijn:\n" + drl);
        assertTrue(drl.contains("Konto( saldo != tegoed )"),
                () -> "een numerieke gelijkheid mag de beide-leeg clausule NIET krijgen (Tabel 37 waar):\n" + drl);
    }

    @Test void verdeling_naar_rato_met_niet_numerieke_weging_blijft_no_op() {
        // The naar-rato weight must be a getalachtig field: a Tekst weight cannot be summed/multiplied, so
        // the verdeling stays the honest no-op marker rather than emitting an uncompilable sum/multiply.
        String src = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de label Tekst;
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel naar rato
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld naar rato van de label.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("// onopgelost verdeling (naar_rato)"),
                () -> "een niet-numerieke weging moet een no-op blijven:\n" + drl);
        assertFalse(drl.contains("getLabel()"),
                () -> "een Tekst-weging mag geen getter/sum/multiply emitteren (onsamenstelbare DRL):\n" + drl);
    }

    @Test void verdeling_naar_rato_emitteert_een_lege_gewicht_inconsistentie_regel() {
        // §9.7.6 Tabel 19: a naar-rato verdeling gets a SEPARATE modify-free Inconsistentie rule that fires
        // when any recipient has an empty weight (exists … weight == null), distinct from the distribution rule.
        String src = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel naar rato
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld naar rato van de gewicht.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rule \"verdeel naar rato (leeg gewicht)\""), () -> "geen aparte lege-gewicht regel:\n" + drl);
        assertTrue(drl.contains("exists Deelnemer( pot == $subject, gewicht == null )"), () -> "geen exists-weegloos patroon:\n" + drl);
        assertTrue(drl.contains("insert(new io.github.yhavinga.regelspraak.facts.Inconsistentie(\"verdeel naar rato\""), () -> "geen Inconsistentie-insert:\n" + drl);
        // the Inconsistentie rule must NOT contain a modify (that would cancel the distribution's activations)
        String legeRegel = drl.substring(drl.indexOf("(leeg gewicht)"));
        assertFalse(legeRegel.substring(0, legeRegel.indexOf("\nend")).contains("modify"),
                () -> "de lege-gewicht regel mag geen modify bevatten:\n" + legeRegel);
    }

    static final String OP_VOLGORDE_NAAR_RATO_SRC = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel op volgorde
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - op volgorde van toenemende de leeftijd,
                    - bij een even groot criterium naar rato van de gewicht.
                    Als onverdeelde rest blijft de restant van een pot over.
            """;

    @Test void verdeling_op_volgorde_lowert_naar_de_geordende_allocatie() {
        // §9.7.2: op volgorde lowers to ONE forward rule — an accumulate folding the recipients into index-aligned
        // value lists (the fact for identity, the ordering criterium, the tie weight), a single Map binding from
        // the registered allocation helper, and a per-recipient modify reading its share by identity.
        String drl = DrlTranspiler.transpile(Resolver.resolve(OP_VOLGORDE_NAAR_RATO_SRC));
        assertTrue(drl.contains("$facts : collectList($p), $groep : collectList($p.getLeeftijd()), $gewicht : collectList($p.getGewicht())"),
                () -> "geen index-uitgelijnde collectList-lijsten:\n" + drl);
        assertTrue(drl.contains("$shares : java.util.Map() from rsVerdeelOpVolgorde( $src.getTotaal(), $facts, $groep, true, $gewicht, null, null )"),
                () -> "geen Map-binding van de geordende allocatie:\n" + drl);
        assertTrue(drl.contains("setAandeel( (java.math.BigDecimal) $shares.get($r) )"),
                () -> "het aandeel wordt niet per ontvanger uit de share-map gelezen:\n" + drl);
        // the §9.7.5 rest rule rides on op-volgorde unchanged, and the helper is registered exactly once
        assertTrue(drl.contains("rule \"verdeel op volgorde (onverdeelde rest)\""), () -> "geen onverdeelde-rest regel:\n" + drl);
        assertEquals(drl.indexOf("function java.util.Map rsVerdeelOpVolgorde("),
                     drl.lastIndexOf("function java.util.Map rsVerdeelOpVolgorde("),
                     "de allocatie-helper moet precies één keer geregistreerd worden");
    }

    @Test void verdeling_op_volgorde_met_niet_numeriek_criterium_blijft_no_op() {
        // The ordering criterium is compareTo-sorted, so a non-getalachtig key (Tekst) cannot be ordered — the
        // verdeling stays the honest no-op marker rather than emitting an uncompilable allocation.
        String drl = DrlTranspiler.transpile(Resolver.resolve(
                OP_VOLGORDE_NAAR_RATO_SRC.replace("de leeftijd Numeriek (geheel getal);", "de leeftijd Tekst;")));
        // the resolver never promotes a non-getalachtig ordering key to OP_VOLGORDE, so it stays COMPLEX
        assertTrue(drl.contains("// onopgelost verdeling (complex)"),
                () -> "een niet-numeriek ordeningscriterium moet een no-op blijven:\n" + drl);
        assertFalse(drl.contains("rsVerdeelOpVolgorde"),
                () -> "een niet-ordenbaar criterium mag geen allocatie emitteren:\n" + drl);
    }

    @Test void verdeling_op_volgorde_in_gelijke_delen_tie_geeft_geen_gewicht_door() {
        // "bij een even groot criterium in gelijke delen" -> no tie weight: the accumulate omits the $gewicht list
        // and the helper is called with a null weight argument (it then splits each group in equal shares). With no
        // weight there is also no §9.7.6 empty-weight Fout, so the (leeg gewicht) rule must NOT be emitted.
        String drl = DrlTranspiler.transpile(Resolver.resolve(OP_VOLGORDE_NAAR_RATO_SRC.replace(
                "bij een even groot criterium naar rato van de gewicht", "bij een even groot criterium in gelijke delen")));
        assertTrue(drl.contains("from rsVerdeelOpVolgorde( $src.getTotaal(), $facts, $groep, true, null, null, null )"),
                () -> "een gelijke-delen tie moet null als gewicht doorgeven:\n" + drl);
        assertFalse(drl.contains("$gewicht"), () -> "een gelijke-delen tie mag geen gewicht-lijst verzamelen:\n" + drl);
        assertFalse(drl.contains("(leeg gewicht)"), () -> "een gelijke-delen tie heeft geen lege-gewicht Fout:\n" + drl);
    }

    private static final String MINMAX_SET_SRC = """
        Domein Bedrag is van het type Numeriek (getal met 2 decimalen) met eenheid €
        Objecttype de Vlucht (mv: Vluchten)
            de prijs Bedrag;
            de andere prijs Bedrag;
        Objecttype de Reis (mv: Reizen)
            de hoogste vluchtprijs Bedrag;
        Feittype reis met vluchten
            de reis Reis
            de vlucht Vlucht
        Eén reis omvat meerdere vluchten
        Regel max
            geldig altijd
                De hoogste vluchtprijs van een reis moet berekend worden als %s.
        """;

    @Test void multi_operand_max_over_sets_wordt_gemarkeerd_niet_stil_over_de_eerste_set() {
        // §5.8.3: "de maximale waarde van de prijs van alle vluchten EN de andere prijs van alle vluchten" is a
        // fold over TWO to-many sets. accumulate reads only the first operand, so emitting it would silently drop
        // the second set — a wrong max. The multi-operand-over-a-set form must mark, not fake a partial fold.
        String drl = DrlTranspiler.transpile(Resolver.resolve(MINMAX_SET_SRC.formatted(
                "de maximale waarde van de prijs van alle vluchten van de reis "
                        + "en de andere prijs van alle vluchten van de reis")));
        assertTrue(drl.contains("/* TODO aggregatie MAXIMUM"),
                () -> "een multi-operand max over sets moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("max( $e1.getPrijs() )"),
                () -> "een multi-operand max mag geen single-set accumulate over de eerste operand emitteren:\n" + drl);
    }

    @Test void enkel_operand_max_over_een_set_blijft_een_accumulate() {
        // the single-set MIN/MAX (the spec's canonical §5.8.3 example) is untouched by the multi-operand guard.
        String drl = DrlTranspiler.transpile(Resolver.resolve(MINMAX_SET_SRC.formatted(
                "de maximale waarde van de prijs van alle vluchten van de reis")));
        assertTrue(drl.contains("max( $e1.getPrijs() )"),
                () -> "een enkel-operand max over een set moet een accumulate blijven:\n" + drl);
        assertFalse(drl.contains("/* TODO aggregatie MAXIMUM"),
                () -> "een enkel-operand max mag geen marker zijn:\n" + drl);
    }

    @Test void totaal_van_een_meer_periodieke_tijdreeks_wordt_gemarkeerd_niet_mis_geteld() {
        // §13.3.6.7: "het totaal van" a "voor elke 5 maanden" attribute cannot reuse the per-maand weighting
        // (§7.1 does not work out the multi-period totaal), so it MARKERS rather than mis-counting 5x as
        // single-period. A single-period "voor elke maand" totaal still lowers via rsTijdTotaal.
        String meer = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de premie per blok Bedrag voor elke 5 maanden startend op 9-3-2025;
                de totale premie Bedrag;
            Regel Totale premie
                geldig altijd
                    De totale premie van een Passagier moet gesteld worden op het totaal van zijn premie per blok.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(meer));
        assertTrue(drl.contains("/* TODO totaal van een meer-periodieke tijdreeks"),
                () -> "een meer-periodieke totaal moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("rsTijdTotaal"),
                () -> "een meer-periodieke totaal mag niet als enkel-periodiek lowereren:\n" + drl);

        String enkel = meer.replace("de premie per blok Bedrag voor elke 5 maanden startend op 9-3-2025;",
                                    "de premie per blok Bedrag voor elke maand;");
        assertTrue(DrlTranspiler.transpile(Resolver.resolve(enkel)).contains("rsTijdTotaal"),
                "een enkel-periodieke totaal moet via rsTijdTotaal lowereren");
    }

    @Test void temporeel_totaal_met_gedurende_voorwaarde_klipt_de_tijdreeks() {
        // §7.1: a "gedurende de tijd dat <temporeel kenmerk>" restriction clips the value timeline to the
        // kenmerk's TRUE periods before totalling — the spec's €71 case, NOT the unrestricted €186. The resolver
        // structures the condition into a temporal Conditie.Kenmerk; the transpiler wraps the value series in
        // rsTijdKnip(value, condition) before rsTijdTotaal — never a condition-free unrestricted total.
        String src = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                is rechthebbend kenmerk (bijvoeglijk) voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat hij rechthebbend is).
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsTijdTotaal(rsTijdKnip($subject.getBelastingverminderingPerMaand(), "
                        + "$subject.getRechthebbend()), \"MAAND\")"),
                () -> "een gedurende-conditie moet de tijdreeks klippen voor het totaal:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "de gedurende-conditie mag geen marker meer zijn:\n" + drl);
    }

    @Test void gedurende_klipt_op_een_bezittelijk_kenmerk_met_heeft() {
        // §7.1 the SPEC'S VERBATIM €71 example uses a BEZITTELIJK kenmerk read with the verb "heeft"
        // ("gedurende de tijd dat hij een recht op belastingvermindering heeft"), not the "is rechthebbend"
        // workaround. The gedurende clip must bind on the heeft-verb membership too — the verb is not the
        // discriminator, the single-period temporal-kenmerk granularity is. (Teeth: revert the line-418
        // relaxation in gedurendeKenmerkFilter and this falls back to a /* TODO */ marker.)
        String src = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsTijdTotaal(rsTijdKnip($subject.getBelastingverminderingPerMaand(), "
                        + "$subject.getRechtOpBelastingvermindering()), \"MAAND\")"),
                () -> "de heeft-vorm bezittelijk kenmerk moet de tijdreeks klippen voor het totaal:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "de heeft-vorm gedurende-conditie mag geen marker zijn:\n" + drl);
    }

    @Test void gedurende_op_een_attribuut_met_heeft_blijft_een_marker() {
        // The heeft-relaxation must NOT let a "heeft <attribuut>" (a possession of a value, not a kenmerk) slip
        // through: only a declared single-period temporal KENMERK clips. A plain temporal attribute named after
        // "heeft" stays an honest /* TODO */ marker, never a wrong getter-as-condition.
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Numeriek (geheel getal) met eenheid € voor elke maand;
                de premie Numeriek (geheel getal) met eenheid € voor elke maand;
                de totale belastingvermindering Numeriek (geheel getal) met eenheid €;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat hij een premie heeft).
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO"), () -> "een heeft-conditie over een attribuut moet een marker blijven:\n" + drl);
        assertFalse(drl.contains("rsTijdKnip"), () -> "een attribuut-conditie mag geen tijdklip krijgen:\n" + drl);
    }

    @Test void gedurende_bindt_de_canonieke_heeft_het_bezittelijke_vorm() {
        // §8.1.8.2/§3.5 the CANONICAL bezittelijk membership is "heeft HET <k>" ("heeft het recht op
        // duurzaamheidskorting"); the article is absorbed into the kaleNaam (only "een" is a separate token), so
        // the gedurende filter must article-strip to bind it. (Teeth: drop the article-strip fallback and this
        // falls back to a /* TODO */ marker.)
        String src = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat hij het recht op belastingvermindering heeft).
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsTijdTotaal(rsTijdKnip($subject.getBelastingverminderingPerMaand(), "
                        + "$subject.getRechtOpBelastingvermindering()), \"MAAND\")"),
                () -> "de canonieke 'heeft het <k>' vorm moet de tijdreeks klippen:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "de 'heeft het' vorm mag geen marker zijn:\n" + drl);
    }

    @Test void negated_gedurende_klipt_via_het_complement() {
        // §8.1.8.1/.2 a "gedurende de tijd dat hij GEEN <kenmerk> heeft" clips the value timeline to the FALSE
        // periods — rsTijdKnipNiet (the complement), never the positive rsTijdKnip. (Teeth: drop the negatie
        // branch and it falls back to the positive clip, computing the wrong period.)
        String src = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat hij geen recht op belastingvermindering heeft).
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rsTijdTotaal(rsTijdKnipNiet($subject.getBelastingverminderingPerMaand(), "
                        + "$subject.getRechtOpBelastingvermindering()), \"MAAND\")"),
                () -> "de negatie moet via rsTijdKnipNiet (complement) klippen:\n" + drl);
    }

    private static String compoundGedurende(String kwantor, String tweedeBullet) {
        return DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de bedrag per maand Numeriek (geheel getal) met eenheid € voor elke maand;
                is rechthebbend kenmerk (bijvoeglijk) voor elke maand;
                is actief kenmerk (bijvoeglijk) voor elke maand;
                de geboortedatum Datum in dagen;
                de totaal Numeriek (geheel getal) met eenheid €;
            Regel R
                geldig altijd
                    De totaal van een Passagier moet gesteld worden op (het totaal van zijn bedrag per maand gedurende de tijd dat er aan %s wordt voldaan:
                    • hij rechthebbend is
                    • %s).
            """.formatted(kwantor, tweedeBullet)));
    }

    @Test void aantal_dagen_in_schrijft_een_tijdreeks_naar_een_temporeel_doel() {
        // §7.2/§13.4.17.57 the FIRST temporal-WRITE path: a temporal doel attribute ("voor elke maand") set to
        // "het aantal dagen in de maand dat <temporeel kenmerk>" writes a List<Segment> via rsAantalDagenIn, not a
        // scalar setter. (Teeth: remove the temporal-doel branch in regel() and it falls back to the scalar
        // marker path.) The gate stays a marker for a non-temporal doel.
        String ok = DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                het aantal dagen recht Numeriek (geheel getal) met eenheid dg voor elke maand;
            Regel R
                geldig altijd
                    Het aantal dagen recht van een passagier moet gesteld worden op het aantal dagen in de maand dat hij een recht op belastingvermindering heeft.
            """));
        assertTrue(ok.contains("setAantalDagenRecht( rsAantalDagenIn($subject.getRechtOpBelastingvermindering(), \"MAAND\") )"),
                () -> "aantal-dagen-in moet een rsAantalDagenIn tijdreeks naar het temporele doel schrijven:\n" + ok);
        assertFalse(ok.contains("/* TODO"), () -> "de aantal-dagen WRITE mag geen marker zijn:\n" + ok);

        String nonTemp = DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                het aantal dagen recht Numeriek (geheel getal);
            Regel R
                geldig altijd
                    Het aantal dagen recht van een passagier moet gesteld worden op het aantal dagen in de maand dat hij een recht op belastingvermindering heeft.
            """));
        assertFalse(nonTemp.contains("rsAantalDagenIn"), () -> "een NIET-temporeel doel mag geen tijdreeks-write krijgen:\n" + nonTemp);
        assertTrue(nonTemp.contains("/* TODO"), () -> "aantal-dagen-in naar een scalair doel moet markeren:\n" + nonTemp);

        // §4.21/§7.2 the operator's "in <maand|jaar>" must MATCH the doel's tijdlijn: "in het jaar" into a per-maand
        // doel must MARKER, not silently bucket by month. (Review MED: the operator unit was parsed but dropped.)
        String mismatch = DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                het aantal dagen recht Numeriek (geheel getal) met eenheid dg voor elke maand;
            Regel R
                geldig altijd
                    Het aantal dagen recht van een passagier moet gesteld worden op het aantal dagen in het jaar dat hij een recht op belastingvermindering heeft.
            """));
        assertFalse(mismatch.contains("rsAantalDagenIn"), () -> "een eenheid-mismatch (jaar-in-maand) mag niet stil bucketen:\n" + mismatch);
        assertTrue(mismatch.contains("/* TODO"), () -> "een operator/doel eenheid-mismatch moet markeren:\n" + mismatch);
    }

    @Test void tijdsevenredig_deel_lowert_alleen_een_temporele_basis_en_markeert_de_rest() {
        // §7.3.2/§13.4.17.58 the SECOND temporal-WRITE: "het tijdsevenredig deel per maand van <temporeel attribuut>
        // gedurende de tijd dat <temporeel kenmerk>" prorates the base value-timeline per calendar bucket. Only a
        // single-period temporal-ATTRIBUTE base of the subject lowers (its §5.1.2 value-timeline is intrinsic); a
        // SCALAR/parameter base, a gran mismatch, and a negated condition all MARKER honestly.
        String ok = DrlTranspiler.transpile(Resolver.resolve("""
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de standaardbedrag Bedrag voor elk jaar;
                de belastingvermindering Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel R
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van zijn standaardbedrag gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """));
        assertTrue(ok.contains("setBelastingvermindering( rsTijdsevenredig($subject.getRechtOpBelastingvermindering(), $subject.getStandaardbedrag(), \"MAAND\") )"),
                () -> "tijdsevenredig deel over een temporele basis moet via rsTijdsevenredig lowereren:\n" + ok);
        assertFalse(ok.contains("/* TODO"), () -> "de tijdsevenredig WRITE mag geen marker zijn:\n" + ok);

        // §5.1.2 a SCALAR (parameter) base has no modeled value-timeline -> marker (a constant proration would be
        // silently wrong on the spec's time-varying examples), with the §13.4.17.51-.58 WARNING preserved.
        String param = DrlTranspiler.transpile(Resolver.resolve("""
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Parameter de STANDAARD : Bedrag
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel R
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van de STANDAARD gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """));
        assertFalse(param.contains("rsTijdsevenredig"), () -> "een scalaire (parameter) basis mag niet als constante prorateren:\n" + param);
        assertTrue(param.contains("/* TODO"), () -> "een scalaire basis moet markeren:\n" + param);

        // §7.3.2 the operator's "per <maand|jaar>" must MATCH the doel's tijdlijn — per jaar into a per-maand doel markers.
        String mismatch = DrlTranspiler.transpile(Resolver.resolve("""
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de standaardbedrag Bedrag voor elk jaar;
                de belastingvermindering Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel R
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per jaar van zijn standaardbedrag gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """));
        assertFalse(mismatch.contains("rsTijdsevenredig"), () -> "een operator/doel gran-mismatch mag niet stil bucketen:\n" + mismatch);
        assertTrue(mismatch.contains("/* TODO"), () -> "een gran-mismatch moet markeren:\n" + mismatch);

        // §8.1.8.1/.2 a NEGATED gedurende ("geen") is not yet supported for tijdsevenredig -> marker (never a
        // positive proration over the complement).
        String negatie = DrlTranspiler.transpile(Resolver.resolve("""
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de standaardbedrag Bedrag voor elk jaar;
                de belastingvermindering Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel R
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van zijn standaardbedrag gedurende de tijd dat hij geen recht op belastingvermindering heeft).
            """));
        assertFalse(negatie.contains("rsTijdsevenredig"), () -> "een genegeerde gedurende mag niet positief prorateren:\n" + negatie);
        assertTrue(negatie.contains("/* TODO"), () -> "een genegeerde gedurende moet markeren:\n" + negatie);

        // §7.3.2 (lines 3093/3123) the base must be CONSTANT within each output bucket: a base FINER than the bucket
        // (a per-maand base into a per-jaar operator/doel) changes mid-bucket, so the bucket-start read would silently
        // drop the rest -> marker, never a mis-proration over only January's base. (Adversarial review finding.)
        String fijnerBasis = DrlTranspiler.transpile(Resolver.resolve("""
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de standaardbedrag Bedrag voor elke maand;
                de jaardeel Bedrag voor elk jaar;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel R
                geldig altijd
                    De jaardeel van een Passagier moet gesteld worden op (het tijdsevenredig deel per jaar van zijn standaardbedrag gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """));
        assertFalse(fijnerBasis.contains("rsTijdsevenredig"), () -> "een basis fijner dan de bucket mag niet stil prorateren:\n" + fijnerBasis);
        assertTrue(fijnerBasis.contains("/* TODO"), () -> "een per-maand basis in een per-jaar bucket moet markeren:\n" + fijnerBasis);
    }

    @Test void temporele_parameter_is_een_lijst_global_en_alleen_een_tijdsevenredig_basis() {
        // §13.3.6/§7.3.2 a TEMPORAL parameter ("voor elk jaar") is a List<Segment> global, readable ONLY as a
        // tijdsevenredig base (the spec's literal STANDAARD BELASTINGVERMINDERING); any SCALAR use markers.
        String tev = DrlTranspiler.transpile(Resolver.resolve("""
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Parameter de STANDAARD : Bedrag voor elk jaar
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            Regel R
                geldig altijd
                    De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van de STANDAARD gedurende de tijd dat hij een recht op belastingvermindering heeft).
            """));
        assertTrue(tev.contains("global java.util.List sTANDAARD;"), () -> "een temporele parameter moet een List<Segment> global zijn:\n" + tev);
        assertTrue(tev.contains("rsTijdsevenredig($subject.getRechtOpBelastingvermindering(), sTANDAARD, \"MAAND\")"),
                () -> "een temporele-parameter basis moet de List global lezen:\n" + tev);
        assertFalse(tev.contains("/* TODO"), () -> "de temporele-parameter tijdsevenredig mag geen marker zijn:\n" + tev);

        // a SCALAR use of a temporal parameter -> marker (a List in a scalar slot would not compile).
        String scalair = DrlTranspiler.transpile(Resolver.resolve("""
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Parameter de STANDAARD : Bedrag voor elk jaar
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Bedrag;
            Regel R
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op de STANDAARD.
            """));
        assertTrue(scalair.contains("/* TODO temporele parameter in scalaire context"),
                () -> "een scalaire lezing van een temporele parameter moet markeren:\n" + scalair);

        // a CONDITION-side scalar use must marker too (the when-side operand() path) — never a bare List<Segment>
        // global in a numeric constraint, which would fail the whole KieBuilder. (Adversarial review HIGH.)
        String conditie = DrlTranspiler.transpile(Resolver.resolve("""
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Parameter de STANDAARD : Bedrag voor elk jaar
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Bedrag;
                het duur kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is duur indien de prijs van de Passagier groter is dan de STANDAARD.
            """));
        assertFalse(conditie.contains("> sTANDAARD") || conditie.contains("sTANDAARD >"),
                () -> "een temporele parameter mag niet als List in een scalaire constraint lekken:\n" + conditie);
        assertTrue(conditie.contains("/* TODO"), () -> "een conditie-zijde temporele parameter moet markeren:\n" + conditie);

        // a plain (non-temporal) parameter is unchanged: a scalar global, read directly.
        String scalarParam = DrlTranspiler.transpile(Resolver.resolve("""
            Parameter de grens : Numeriek (geheel getal)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de prijs Numeriek (geheel getal);
            Regel R
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op de grens.
            """));
        assertTrue(scalarParam.contains("global java.math.BigDecimal grens;"), () -> "een gewone parameter blijft een scalaire global:\n" + scalarParam);
        assertFalse(scalarParam.contains("/* TODO"), () -> "een gewone parameter-lezing mag geen marker zijn:\n" + scalarParam);
    }

    @Test void compound_dat_op_de_write_producers_combineert_de_kenmerk_tijdreeksen() {
        // §8.4.2 a COMPOUND condition on the temporal-WRITE producers (aantal-dagen-in / tijdsevenredig) combines the
        // bullets' true-period timelines into ONE: ALLE = intersection (rsTijdKnip), ten minste 1 = union (rsTijdUnie).
        // A NEGATED member markers (no standalone complement here, unlike the totaal CLIP bounded by its value).
        String alle = DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                is actief kenmerk (bijvoeglijk) voor elke maand;
                het aantal dagen recht op belastingvermindering Numeriek (geheel getal) met eenheid dg voor elke maand;
            Regel R
                geldig altijd
                    Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat er aan alle volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - hij actief is.
            """));
        assertTrue(alle.contains("rsAantalDagenIn(rsTijdKnip($subject.getRechtOpBelastingvermindering(), $subject.getActief()), \"MAAND\")"),
                () -> "een ALLE compound moet de doorsnede tellen via rsTijdKnip:\n" + alle);
        assertFalse(alle.contains("/* TODO"), () -> "een ALLE compound mag geen marker zijn:\n" + alle);

        // a NEGATED member ("hij niet actief is") has no standalone complement -> the whole compound markers.
        String negLid = DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                is actief kenmerk (bijvoeglijk) voor elke maand;
                het aantal dagen recht op belastingvermindering Numeriek (geheel getal) met eenheid dg voor elke maand;
            Regel R
                geldig altijd
                    Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat er aan alle volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - hij niet actief is.
            """));
        assertFalse(negLid.contains("rsAantalDagenIn"), () -> "een genegeerd compound-lid mag niet stil combineren:\n" + negLid);
        assertTrue(negLid.contains("/* TODO"), () -> "een genegeerd compound-lid moet markeren:\n" + negLid);
    }

    @Test void or_mixed_op_tijdsevenredig_write_verbreedt_de_basis_span_aantal_dagen_markeert() {
        // §8.4.2 an OR-mixed ("ten minste 1": a temporal kenmerk OR a static comparison) gating a temporal-WRITE
        // producer. tijdsevenredig HAS a base whose value-timeline bounds the static-true "full span", so it lowers
        // like the totaal-READ OR-mixed: static TRUE -> prorate the base over its FULL extent (base AS its own all-true
        // condition, rsTijdsevenredig reading a condition's segments as the true periods); static FALSE -> the temporal
        // union. aantal-dagen-in has NO base/extent, so its OR-mixed static-true "count all days" is under-defined and
        // stays an HONEST marker.
        String tev = DrlTranspiler.transpile(Resolver.resolve("""
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
            """));
        assertTrue(tev.contains("? rsTijdsevenredig($subject.getStandaardbedrag(), $subject.getStandaardbedrag(), \"MAAND\")"),
                () -> "static-TRUE moet de basis over de volle span prorateren (basis-als-eigen-conditie):\n" + tev);
        assertTrue(tev.contains(": rsTijdsevenredig($subject.getRechtOpBelastingvermindering(), $subject.getStandaardbedrag(), \"MAAND\")"),
                () -> "static-FALSE moet over de temporele unie prorateren:\n" + tev);
        assertFalse(tev.contains("/* TODO"), () -> "de OR-mixed tijdsevenredig WRITE mag geen marker zijn:\n" + tev);

        // aantal-dagen-in OR-mixed: §7.2 has NO value base — the result is bounded ONLY by the condition's true-periods
        // (the spec's own §7.2 line 3005 caveat: an ever-true / "zeer lange periode" condition yields a "zeer groot
        // aantal resultaten"). A static-TRUE OR is true for ALL time -> unbounded, no finite List<Segment>; unioning
        // by the kenmerk segments would drop the static-true full-day months and under-count. So it stays an HONEST
        // marker, never a silent (wrong) union. This is the spec-correct completion of OR-mixed on this producer.
        String adi = DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de leeftijd Numeriek (geheel getal);
                het aantal dagen recht op belastingvermindering Numeriek (geheel getal) met eenheid dg voor elke maand;
            Regel R
                geldig altijd
                    Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat er aan ten minste 1 van de volgende voorwaarden wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - zijn leeftijd is gelijk aan 18.
            """));
        assertFalse(adi.contains("rsAantalDagenIn"), () -> "OR-mixed aantal-dagen-in mag niet stil verenigen (de static-true span ontbreekt):\n" + adi);
        assertTrue(adi.contains("/* TODO temporele toekenning"), () -> "OR-mixed aantal-dagen-in moet eerlijk markeren:\n" + adi);
    }

    private static String mixedCompound(String kwantor, String tweedeLid) {
        return DrlTranspiler.transpile(Resolver.resolve("""
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
                de geboortedatum Datum in dagen;
                de leeftijd Numeriek (geheel getal);
                de totale belastingvermindering Bedrag;
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op (het totaal van zijn belastingvermindering per maand gedurende de tijd dat er aan %s wordt voldaan:
                    - hij een recht op belastingvermindering heeft
                    - %s).
            """.formatted(kwantor, tweedeLid)));
    }

    @Test void mixed_compound_gedurende_zet_een_statische_vergelijking_als_guard_of_markeert() {
        // §8.4 an ALLE compound mixing a temporal kenmerk + a NON-temporal ORDERING comparison ("zijn geboortedatum
        // is eerder dan 1-1-2002") lowers to a value-side static guard wrapping the clipped totaal. Other forms marker.
        String guard = mixedCompound("alle volgende voorwaarden", "zijn geboortedatum is eerder dan 1-1-2002");
        assertTrue(guard.contains("$subject.getGeboortedatum() != null") && guard.contains("? rsTijdTotaal(rsTijdKnip(")
                        && guard.contains(": java.math.BigDecimal.ZERO)"),
                () -> "een mixed ALLE compound moet de statische vergelijking als value-side guard zetten:\n" + guard);
        assertFalse(guard.contains("/* TODO"), () -> "de mixed ALLE compound mag geen marker zijn:\n" + guard);

        // a mixed OR (ten minste 1) now lowers: a TRUE static member WIDENS the result to the full span, FALSE leaves
        // only the clip — NOT the ALLE clip-vs-ZERO. (Depth in compound_ten_minste_1_mixed_static_verbreedt_naar_de_volle_span.)
        String orMixed = mixedCompound("ten minste 1 van de volgende voorwaarden", "zijn geboortedatum is eerder dan 1-1-2002");
        assertTrue(orMixed.contains("? rsTijdTotaal($subject.getBelastingverminderingPerMaand(), \"MAAND\")")
                        && orMixed.contains(": rsTijdTotaal(rsTijdKnip("),
                () -> "een mixed ten-minste-1 compound moet de volle span vs de klip kiezen:\n" + orMixed);
        assertFalse(orMixed.contains("/* TODO"), () -> "de mixed OR compound mag geen marker meer zijn:\n" + orMixed);

        // a GELIJK static member now lowers (numeriek both-empty -> waar, Tabel 37): both-empty OR both-gevuld-equal.
        String gelijk = mixedCompound("alle volgende voorwaarden", "zijn leeftijd is gelijk aan 18");
        assertTrue(gelijk.contains("? rsTijdTotaal(rsTijdKnip(") && gelijk.contains("$subject.getLeeftijd() == new java.math.BigDecimal(\"18\")"),
                () -> "een GELIJK statisch lid moet als value-side guard lowereren:\n" + gelijk);
        assertFalse(gelijk.contains("/* TODO"), () -> "een GELIJK statisch lid mag geen marker meer zijn:\n" + gelijk);

        // §8.4 a comparison on a TEMPORAL attribute is itself time-varying -> NOT a static member: it must MARKER, not
        // lower to a List<Segment>-vs-scalar term that fails the whole DRL build. (Adversarial review HIGH.)
        String temp = mixedCompound("alle volgende voorwaarden", "zijn belastingvermindering per maand is groter dan 1000");
        assertFalse(temp.contains("? rsTijdTotaal"), () -> "een vergelijking op een temporeel attribuut mag geen statische guard worden:\n" + temp);
        assertTrue(temp.contains("/* TODO"), () -> "een temporeel-attribuut lid moet markeren:\n" + temp);

        // §8.4 a temporal read NESTED in arithmetic ("... per maand plus 1 ...") is just as time-varying -> marker, not
        // a rsNul(List<Segment>) term that breaks the build. (Adversarial review MED: the temporal-operand gate must
        // recurse through Binair/Afgerond, else the GELIJK/ORDERING guard leaks a List.)
        String tempArith = mixedCompound("alle volgende voorwaarden", "zijn belastingvermindering per maand plus 1 is gelijk aan 1000");
        assertFalse(tempArith.contains("rsNul") || tempArith.contains("? rsTijdTotaal"),
                () -> "een temporeel attribuut in een rekenkundig statisch lid mag geen List lekken:\n" + tempArith);
        assertTrue(tempArith.contains("/* TODO"), () -> "een genest temporeel-attribuut lid moet markeren:\n" + tempArith);
    }

    @Test void compound_alle_gedurende_nest_de_knippen_de_doorsnede() {
        // §8.4.2 a COMPOUND "er aan ALLE volgende voorwaarden wordt voldaan" intersects the bullets' true-period
        // sets by nesting rsTijdKnip (a negated bullet nests rsTijdKnipNiet). (Teeth: drop the Samengesteld branch
        // in tijdreeksAggregatie/gedurendeSamengesteld and the whole compound falls back to a marker.)
        String drl = compoundGedurende("alle volgende voorwaarden", "hij actief is");
        assertTrue(drl.contains("rsTijdTotaal(rsTijdKnip(rsTijdKnip($subject.getBedragPerMaand(), "
                        + "$subject.getRechthebbend()), $subject.getActief()), \"MAAND\")"),
                () -> "compound ALLE moet geneste rsTijdKnip-doorsnede zijn:\n" + drl);
        String neg = compoundGedurende("alle volgende voorwaarden", "hij niet actief is");
        assertTrue(neg.contains("rsTijdKnipNiet(rsTijdKnip($subject.getBedragPerMaand(), $subject.getRechthebbend()), "
                        + "$subject.getActief())"),
                () -> "een genegeerde bullet moet rsTijdKnipNiet nesten:\n" + neg);
    }

    @Test void compound_ten_minste_1_gedurende_verenigt_via_rsTijdUnie() {
        // §8.4.2 "ten minste 1 van de volgende voorwaarden" (OR) clips the value to the UNION of the bullets'
        // true-period sets (rsTijdUnie), POSITIVE members only.
        String drl = compoundGedurende("ten minste 1 van de volgende voorwaarden", "hij actief is");
        assertTrue(drl.contains("rsTijdKnip($subject.getBedragPerMaand(), "
                        + "rsTijdUnie($subject.getRechthebbend(), $subject.getActief()))"),
                () -> "compound OR moet via rsTijdUnie verenigen:\n" + drl);
    }

    @Test void compound_ten_minste_1_mixed_static_verbreedt_naar_de_volle_span() {
        // §8.4.2 a mixed "ten minste 1" (OR): a temporal kenmerk OR a static comparison. A TRUE static member makes
        // the OR hold across the WHOLE span (a static member has no time-variation), so the totaal widens to the
        // UN-clipped timeline; a FALSE static member leaves only the temporal-union clip. So the static guard chooses
        // full-vs-clip — NOT (as for ALLE) clip-vs-ZERO. (Teeth: restore the kwantor==ALLE gate on the comparison
        // member in gedurendeSamengesteld and the whole compound markers.)
        String drl = compoundGedurende("ten minste 1 van de volgende voorwaarden", "zijn geboortedatum is eerder dan 01-01-2002");
        assertTrue(drl.contains("? rsTijdTotaal($subject.getBedragPerMaand(), \"MAAND\")"
                        + " : rsTijdTotaal(rsTijdKnip($subject.getBedragPerMaand(), $subject.getRechthebbend()), \"MAAND\"))"),
                () -> "OR-mixed: static-true -> de volle span, static-false -> de klip:\n" + drl);
        assertFalse(drl.contains(": java.math.BigDecimal.ZERO)"),   // the helper body inits "= ...ZERO", so match the ternary else
                () -> "OR-mixed mag niet naar ZERO gaten (dat is de ALLE-semantiek):\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "OR-mixed mag geen marker zijn:\n" + drl);
    }

    @Test void compound_gedurende_markeert_de_niet_ondersteunde_kwantoren() {
        // The whole compound stays an honest marker — never a silently-dropped predicate — for any kwantor that is
        // not ALLE or "ten minste 1": ten minste >1, geen van de, ten hoogste, precies. (The mixed temporal+
        // non-temporal ALLE, the OR-with-negation, and the mixed OR-with-static DO lower, tested separately.)
        assertTrue(compoundGedurende("ten minste 2 van de volgende voorwaarden", "hij actief is")
                        .contains("/* TODO"), "ten minste 2 moet markeren");
        assertTrue(compoundGedurende("geen van de volgende voorwaarden", "hij actief is")
                        .contains("/* TODO"), "geen van de moet markeren");
    }

    @Test void compound_ten_minste_1_met_een_genegeerde_member_verenigt_het_complement() {
        // §8.4.2 an OR ("ten minste 1") with a NEGATED member now lowers: a positive member contributes its getter,
        // a negated member contributes the value clipped to its FALSE periods (rsTijdKnipNiet over the value — the
        // span-bounded complement), unioned then clipped once. (Teeth: restore the negatie->return null guard.)
        String drl = compoundGedurende("ten minste 1 van de volgende voorwaarden", "hij niet actief is");
        assertTrue(drl.contains("rsTijdKnip($subject.getBedragPerMaand(), "
                        + "rsTijdUnie($subject.getRechthebbend(), "
                        + "rsTijdKnipNiet($subject.getBedragPerMaand(), $subject.getActief())))"),
                () -> "OR met een genegeerde member moet het span-begrensde complement verenigen:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "OR-negatie mag geen marker meer zijn:\n" + drl);
    }

    @Test void benoemde_meer_periodieke_tijdlijn_wordt_ook_gemarkeerd_niet_single_period_geteld() {
        // Review defect: a NAMED multi-period timeline ("voor elke blokperiode" -> "tijdvakken van 5 maanden")
        // must inherit the period count from its definition and marker, not slip past the guard into a
        // single-period rsTijdTotaal (the 5x mis-count the inline form already guards against).
        String src = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Tijdlijn de blokperiode heeft tijdvakken van 5 maanden startend op 9-3-2025;
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de premie per blok Bedrag voor elke blokperiode;
                de totale premie Bedrag;
            Regel Totale premie
                geldig altijd
                    De totale premie van een Passagier moet gesteld worden op het totaal van zijn premie per blok.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO totaal van een meer-periodieke tijdreeks"),
                () -> "een benoemde meer-periodieke totaal moet markeren:\n" + drl);
        assertFalse(drl.contains("rsTijdTotaal"),
                () -> "geen single-period rsTijdTotaal voor een benoemde 5-maanden tijdlijn:\n" + drl);
    }

    @Test void temporeel_totaal_over_een_set_vouwt_per_element_de_tijdreeks() {
        // §7.1+§5.8 "het totaal van <temporeel attr> van ALLE <set>" is a PER-ELEMENT timeline fold: each element's
        // tijdreeks is summed over time (rsTijdTotaal) to a scalar, then those scalars are summed over the set — NEVER
        // a raw sum() over the List<Segment> (a fire-time ClassCastException). The same-type case still markers (the
        // separate set->own collapse: when the element type EQUALS the subject's the resolver loses the "alle"
        // quantifier — carded), so it must not fold the subject's OWN series either.
        String sameType = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Vlucht (mv: Vluchten)
                de naam Tekst;
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de totale belastingvermindering Bedrag;
            Feittype reis met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op het totaal van de belastingvermindering per maand van alle passagiers van de reis.
            """;
        String drl1 = DrlTranspiler.transpile(Resolver.resolve(sameType));
        assertFalse(drl1.contains("rsTijdTotaal"), () -> "een set-totaal mag niet als eigen tijdreeks vouwen:\n" + drl1);
        assertTrue(drl1.contains("/* TODO aggregatie TOTAAL"), () -> "een set-totaal moet markeren:\n" + drl1);

        String diffType = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Vlucht (mv: Vluchten)
                de totaalprijs Bedrag;
            Objecttype de Passagier (mv: Passagiers)
                de belastingvermindering per maand Bedrag voor elke maand;
            Feittype reis met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel R
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op het totaal van de belastingvermindering per maand van alle passagiers van de reis.
            """;
        String drl2 = DrlTranspiler.transpile(Resolver.resolve(diffType));
        assertTrue(drl2.contains("sum( rsTijdTotaal($e1.getBelastingverminderingPerMaand(), \"MAAND\") )"),
                () -> "een set-totaal over een temporeel attribuut moet per element de tijdreeks vouwen (rsTijdTotaal):\n" + drl2);
        assertFalse(drl2.contains("sum( $e1.getBelastingverminderingPerMaand() )"),
                () -> "het mag de List<Segment> niet RAUW sommen (ClassCastException):\n" + drl2);
    }

    @Test void temporeel_totaal_over_een_niet_alle_set_kwantor_markeert_via_de_structurele_set_vlag() {
        // The set guard is now a STRUCTURAL flag (Expr.AttribuutRef.set, from the onderwerp's §13.4.2 quantifier),
        // not an onderwerpBron "alle " text scan — so it also catches the THRESHOLD quantifiers the text scan
        // missed ("ten minste 2 van de passagiers"), which over an element==subject set would otherwise mis-fold
        // the subject's OWN timeline. Uses the objecttype-plural onderwerp (element type == subject Passagier), the
        // load-bearing shape; the old "alle " startsWith would NOT match "ten minste 2 …" and lower a wrong
        // rsTijdTotaal. (Teeth: revert a.set() to the "alle " startsWith and this folds instead of marking.)
        String src = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Vlucht (mv: Vluchten)
                de naam Tekst;
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                de totale belastingvermindering Bedrag;
            Feittype reis met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel R
                geldig altijd
                    De totale belastingvermindering van een Passagier moet gesteld worden op het totaal van de belastingvermindering per maand van ten minste 2 van de passagiers van de reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("rsTijdTotaal"),
                () -> "een 'ten minste' set-totaal mag niet als eigen tijdreeks vouwen (structurele set-vlag):\n" + drl);
        assertTrue(drl.contains("/* TODO aggregatie TOTAAL"),
                () -> "een niet-'alle' set-totaal moet ook markeren:\n" + drl);
    }

    @Test void de_twee_nietsen_van_8_2_vallen_niet_tegen_elkaar_weg() {
        // §8.2: the two distinct "niet"s must NOT cancel. "niet gedurende het gehele jaar X" is ∃ moment ¬X (the
        // WHOLE-PERIOD check is negated -> GeheelPeriode.niet) and lowers to `rsGeheelPeriode(...) == false`.
        // "gedurende het gehele jaar niet X" is ∀ moment ¬X (the PREDICATE is negated -> Kenmerk.negatie); the negated
        // temporal kenmerk-check is not yet lowered, so it stays an honest §8.2 marker. The two forms produce visibly
        // DIFFERENT lowerings -> the resolver keeps ¬(∀X) and ∀(¬X) apart.
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                is rechthebbend kenmerk (bijvoeglijk) voor elke maand;
                is duur kenmerk (bijvoeglijk);
                is jong kenmerk (bijvoeglijk);
            Regel existsneg
                geldig altijd
                    Een Passagier is duur indien hij niet gedurende het gehele jaar rechthebbend is.
            Regel forallneg
                geldig altijd
                    Een Passagier is jong indien hij gedurende het gehele jaar niet rechthebbend is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "temporeel onderwerp: geen §8.2 fout verwacht: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        // ∃¬X: the period-check is negated (== false), NOT the predicate.
        assertTrue(drl.contains("rsGeheelPeriode($subject.getRechthebbend(), \"JAAR\", 7, null) == false"),
                () -> "∃¬X (niet gedurende ...) moet de periode-check negeren (== false):\n" + drl);
        // ∀¬X: the predicate is negated -> a negated temporal kenmerk-check, still an honest marker.
        assertTrue(drl.contains("TODO §8.2 gehele-periode") && drl.contains("niet rechthebbend"),
                () -> "∀¬X (gedurende ... niet) is de predicaat-negatie en blijft een marker:\n" + drl);
    }

    @Test void dubbele_negatie_door_compositie_valt_niet_weg() {
        // §8.2/§13.4.14: v2.3.0 has no `niet niet`; a double negation only arises by COMPOSITION (a negated bullet
        // under the GEEN-quantifier). The resolver must NOT algebraically cancel across the construct boundary: it
        // emits the GEEN compound as `!(...)` over leaves that KEEP their own `== false`, never the simplified
        // `jong == true`. So `geen van de [¬jong, ¬duur]` -> !((jong == false) || (duur == false)).
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                is jong kenmerk (bijvoeglijk);
                is duur kenmerk (bijvoeglijk);
                is kind kenmerk (bijvoeglijk);
            Regel dneg
                geldig altijd
                    Een Passagier is kind indien hij aan geen van de volgende voorwaarden voldoet:
                    - hij is niet jong
                    - hij is niet duur.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("!((jong == false) || (duur == false))"),
                () -> "de GEEN-compound moet de binnen-negaties bewaren (geen algebraïsche wegval):\n" + drl);
        assertFalse(drl.contains("jong == true"),
                () -> "de dubbele negatie mag NIET tot 'jong == true' versimpeld worden:\n" + drl);
    }

    @Test void tijdsafhankelijk_attribuut_en_kenmerk_in_een_gehele_periode_conditie_lowEren_zonder_lekken() {
        // §8.2/§13.4.15.70: a "gedurende het gehele jaar" whole-period qualifier over a tijdsafhankelijk attribuut/
        // kenmerk LOWERS to a boolean rsGeheelPeriode(...) check — never a `field == scalar` that leaks the
        // List<Segment> into a scalar slot. The own-attribute comparison (Regel A) and the own-kenmerk check (Regel B)
        // both lower; a BARE temporal read into a scalar doel WITHOUT a §8.2 qualifier (Regel C) still markers (the
        // value side has no whole-period meaning), proving the §8.2 lowering is the qualifier's doing, not a blanket pass.
        String src = """
            Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de belastingvermindering per maand Bedrag voor elke maand;
                is rechthebbend kenmerk (bijvoeglijk) voor elke maand;
                de prijs Bedrag;
                het duur kenmerk (bijvoeglijk);
            Regel A
                geldig altijd
                    Een Passagier is duur indien zijn belastingvermindering per maand gedurende het gehele jaar gelijk is aan 10.
            Regel B
                geldig altijd
                    Een Passagier is duur indien hij gedurende het gehele jaar rechthebbend is.
            Regel C
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op zijn belastingvermindering per maand.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "geaccepteerde invoer (geen resolver-fout): " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        // no raw List-in-scalar leak may appear
        assertFalse(drl.contains("belastingverminderingPerMaand =="), () -> "attribuut-vergelijking lekt een List:\n" + drl);
        assertFalse(drl.contains("rechthebbend == true"), () -> "kenmerk-check lekt een List:\n" + drl);
        assertFalse(drl.contains("setPrijs( $subject.getBelastingverminderingPerMaand() )"), () -> "scalaire setter lekt een List:\n" + drl);
        // §8.2 comparison + kenmerk-check now LOWER to the whole-period helper (toets 4 = GELIJK, 7 = covered/kenmerk)
        assertTrue(drl.contains("rsGeheelPeriode($subject.getBelastingverminderingPerMaand(), \"JAAR\", 4,"),
                () -> "§8.2 attribuut-vergelijking niet gelowEred:\n" + drl);
        assertTrue(drl.contains("rsGeheelPeriode($subject.getRechthebbend(), \"JAAR\", 7, null)"),
                () -> "§8.2 kenmerk-check niet gelowEred:\n" + drl);
        // the value-side temporal read (no §8.2 qualifier) is still an honest marker
        assertTrue(drl.contains("tijdsafhankelijk attribuut in scalaire context"),
                () -> "scalaire waarde-lezing niet gemarkeerd:\n" + drl);
    }

    @Test void navigeerd_tijdsafhankelijk_kenmerk_in_een_conditie_markeert_niet_lekt() {
        // §13.3.6/§8.2 the navigated edge: "zijn reis gedurende het gehele jaar actief is" reads a temporal kenmerk
        // (a List<Segment> timeline) THROUGH a to-one reference. The owner is resolved from the reference role, so it
        // markers rather than leaking "reis.actief == true" (List vs boolean). A NON-temporal navigated kenmerk
        // ("zijn reis internationaal is") still lowers normally — the guard fires only on a temporal one.
        String src = """
            Objecttype de Vlucht (bezield)
                actief kenmerk (bijvoeglijk) voor elk jaar;
                internationaal kenmerk (bijvoeglijk);
                het loon Numeriek (geheel getal) met eenheid € voor elk jaar;
                de prijs Numeriek (geheel getal) met eenheid €;
            Objecttype de Passagier (bezield)
                is duur kenmerk (bijvoeglijk);
            Feittype reis
                de passagier    Passagier
                de reis         Vlucht
            één passagier heeft één reis;
            Regel temporeel
                geldig altijd
                    Een Passagier is duur indien zijn reis gedurende het gehele jaar actief is.
            Regel statisch
                geldig altijd
                    Een Passagier is duur indien zijn reis internationaal is.
            Regel temporeel_leeg
                geldig altijd
                    Een Passagier is duur indien de loon van zijn reis leeg is.
            Regel statisch_leeg
                geldig altijd
                    Een Passagier is duur indien de prijs van zijn reis leeg is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "geaccepteerde invoer (geen resolver-fout): " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("reis.actief == true"), () -> "navigeerd temporeel kenmerk lekt een List:\n" + drl);
        // §8.2 over a NAVIGATED temporal kenmerk is not lowerable as a standalone timeline (temporeelKenmerkTijdreeks
        // returns null for a navigated kenmerk) -> an honest §8.2 marker, never a "reis.actief == true" List leak.
        assertTrue(drl.contains("TODO §8.2 gehele-periode"),
                () -> "navigeerd temporeel §8.2 kenmerk niet gemarkeerd:\n" + drl);
        assertTrue(drl.contains("reis != null && reis.internationaal == true"),
                () -> "een NIET-temporeel navigeerd kenmerk moet gewoon lowEren:\n" + drl);
        // §8.2 navigated LEEG: a temporal navigated attr markers (no "reis.loon == null" list-nullness test); a
        // non-temporal navigated attr still lowers to the plain null-pair guard.
        assertFalse(drl.contains("reis.loon == null"), () -> "navigeerd temporeel attribuut LEEG lekt een List-nultest:\n" + drl);
        assertTrue(drl.contains("(reis == null || reis.prijs == null)"),
                () -> "een NIET-temporeel navigeerd LEEG moet gewoon lowEren:\n" + drl);
    }

    @Test void variabele_met_lidwoord_en_shadowing_van_parameter() {
        // §11 (review): a local "Daarbij geldt:" variable inlines at a reference written WITH a leading article
        // ("de drempel" — the spec explicitly allows a lidwoord at a variable reference), AND it SHADOWS a same-named
        // global parameter (a variable "geldt alleen binnen de regel"). Both arrive via the onderwerp path
        // (isVariabele=false), so naamRef must consult the variabelen map FIRST.
        String src = """
            Parameter de drempel : Numeriek (geheel getal)
            Objecttype de Persoon (mv: Personen) (bezield)
                de prijs Numeriek (geheel getal);
                de uitkomst Numeriek (geheel getal);
            Regel R
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op de drempel.
                    Daarbij geldt: de drempel is zijn prijs.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "geaccepteerde invoer (geen resolver-fout): " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        // "de drempel" inlines to the local definition (zijn prijs) — NOT the global parameter, NOT a TODO marker
        assertTrue(drl.contains("setUitkomst( $subject.getPrijs() )"),
                () -> "§11 lidwoord-referentie van een lokale variabele die een parameter shadowt moet inlinen naar getPrijs():\n" + drl);
        assertFalse(drl.contains("setUitkomst( drempel )"), () -> "de globale parameter 'drempel' mag de lokale variabele niet winnen:\n" + drl);
    }

    @Test void percentage_functie_zonder_procent_lowert_als_PV_door_100() {
        // §13.4.17.12/§6.8 "het percentage P van V" WITHOUT the '%': our grammar requires '%' on percentageVanExpr to
        // disambiguate from a 'van' navigation, so the no-'%' form parses as a navigation; the resolver reinterprets it
        // as PERCENTAGE_VAN when the leftmost name is a Percentage-typed PARAMETER -> (rsNul(P)/100 * rsNul(V)).
        String src = """
            Parameter de korting : Percentage (geheel getal)
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
                de uitkomst Numeriek (geheel getal);
            Regel R
                geldig altijd
                    De uitkomst van een Reis moet gesteld worden op de korting van de prijs van een Reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("setUitkomst( (rsNul(korting) / 100 * rsNul($subject.getPrijs())) )"),
                () -> "'de korting van de prijs' (korting een Percentage-parameter) moet als PERCENTAGE_VAN loweren:\n" + drl);
    }

    @Test void percentage_param_naambotsing_met_navigatie_attribuut_blijft_navigatie() {
        // §4.8 Tabel 18 requires a NUMERIEK right operand, so the percentage reinterpretation only fires when V is
        // getalachtig. When a Percentage parameter shares its name with an attribute reachable via a to-one role
        // ("de korting van zijn bestelling", 'korting' also a Bestelling attribute), V is an OBJECT navigation (type
        // onbekend) -> the percentage reading is rejected and the construct stays the correct attribute navigation.
        String src = """
            Parameter de korting : Percentage (geheel getal)
            Objecttype de Bestelling
                de korting Numeriek (geheel getal);
            Objecttype de Klant
                de uitkomst Numeriek (geheel getal);
            Feittype plaatst
                de klant Klant
                de bestelling Bestelling
            Eén klant heeft één bestelling
            Regel R
                geldig altijd
                    De uitkomst van een Klant moet gesteld worden op de korting van zijn bestelling.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("getBestelling()") && drl.contains(".getKorting()"),
                () -> "een naam-botsing moet de NAVIGATIE behouden (bestelling.korting), niet de percentage-functie:\n" + drl);
        assertFalse(drl.contains("/ 100"), () -> "de percentage-functie mag NIET vuren bij een object-V (geen getalachtige V):\n" + drl);
    }

    /** Resolve a §8.4 mixed-compound tijdsevenredig WRITE whose static member is {@code member}, return "MARKER"/"FIRES". */
    private static String mixedStaticDrl(String member) {
        String src = "Domein Bedrag is van het type Numeriek (getal met 2 decimalen)\n"
            + "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de standaardbedrag Bedrag voor elk jaar;\n"
            + "    de belastingvermindering Bedrag voor elke maand;\n    het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;\n"
            + "    de datum a Datum in dagen;\n    de datum b Datum in dagen;\n    de leeftijd Numeriek (geheel getal);\n    de grens Numeriek (geheel getal);\n"
            + "Regel R\n    geldig altijd\n        De belastingvermindering van een Passagier moet gesteld worden op (het tijdsevenredig deel per maand van zijn standaardbedrag gedurende de tijd dat er aan alle volgende voorwaarden wordt voldaan:\n"
            + "        - hij een recht op belastingvermindering heeft\n        - " + member + ").\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet resolven: " + m.errors());
        return DrlTranspiler.transpile(m).contains("/* TODO temporele toekenning") ? "MARKER" : "FIRES";
    }

    @Test void mixed_compound_datum_attr_vs_attr_static_member_markert_voor_pariteit() {
        // §5.7 Tabel 46: a §8.4 mixed-compound DATUM ordering static member with BOTH operands empty-capable
        // (attr-vs-attr — no datum literal) is "fout" when both are empty; the firing form renders it "onwaar" (wrong)
        // AND the exec statischeVergelijking declines an attr-vs-attr datum ordering (literal-RHS only), so DRL markers
        // it to stay SYMMETRIC (no masked DRL-fires/exec-skips divergence). A datum-vs-literal member (never both-empty)
        // and a NUMERIC attr-vs-attr member (Tabel 42 "onwaar" = the firing false, correct) keep firing in both back-ends.
        assertEquals("MARKER", mixedStaticDrl("zijn datum a is eerder dan zijn datum b"),
                "een datum attr-vs-attr static member (Tabel 46 both-empty 'fout') moet markeren voor pariteit met de exec");
        assertEquals("FIRES", mixedStaticDrl("zijn datum a is eerder dan 1-1-2002"),
                "een datum-vs-literal static member (nooit both-empty) blijft vuren");
        assertEquals("FIRES", mixedStaticDrl("zijn leeftijd is groter dan zijn grens"),
                "een numeriek attr-vs-attr static member (Tabel 42 'onwaar') blijft vuren");
    }
}
