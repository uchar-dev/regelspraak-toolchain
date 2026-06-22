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
                    Een passagier is volwassen indien hij is minderjarig.
            Regel rol
                geldig altijd
                    Een passagier is volwassen indien hij is passagier.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("minderjarig == true"), () -> "'is minderjarig' moet kenmerk-eerst binden:\n" + drl);
        assertTrue(drl.contains("reis != null"), () -> "'is passagier' (rol, geen kenmerk) moet de rolcheck binden:\n" + drl);
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
        assertTrue(drl.contains("java.time.LocalDate.of(($subject.getBouwjaar()).intValue(), "
                + "(new java.math.BigDecimal(\"3\")).intValue(), (new java.math.BigDecimal(\"15\")).intValue())"),
                () -> "datum met:\n" + drl);
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
        // datumtijd met has SEVEN parts; LocalDateTime.of's 7th arg is nanos, so milliseconde scales x1e6
        assertTrue(drl.contains("(new java.math.BigDecimal(\"500\")).intValue() * 1000000)"), () -> "datumtijd met milli->nano:\n" + drl);
        assertTrue(drl.contains("java.time.LocalDateTime.of("), () -> "datumtijd met:\n" + drl);
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
}
