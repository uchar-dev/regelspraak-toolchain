package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Datatype.Teken;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.resolver.Typering;
import io.github.yhavinga.regelspraak.resolver.Typering.BinOp;
import io.github.yhavinga.regelspraak.resolver.Typering.CmpOp;
import io.github.yhavinga.regelspraak.resolver.Typering.UnairePred;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The typeringen matrix (typeringen §4 operators, §5 comparisons, §6 toekenning). */
class TyperingTest {
    static Datatype num()                    { return new Datatype.Numeriek(true, 0, Teken.ONBEPAALD, null); }
    static Datatype num(String afk, String stelsel) { return new Datatype.Numeriek(false, 0, Teken.ONBEPAALD, new Eenheid(afk, stelsel)); }
    static Datatype tekst()                  { return new Datatype.Tekst(); }
    static Datatype bool()                   { return new Datatype.Booleaan(); }
    static Datatype datum()                  { return new Datatype.DatumInDagen(); }

    // §4.1 plus
    @Test void plus_twee_getallen_ok() {
        var r = Typering.reken(BinOp.PLUS, num(), num());
        assertTrue(r.ok()); assertTrue(r.type().isNumeriek());
    }
    @Test void plus_getal_en_tekst_fout() {
        assertFalse(Typering.reken(BinOp.PLUS, num(), tekst()).ok());
    }
    @Test void plus_datum_en_tijdseenheid_geeft_datum() {    // §4.1 Tabel 2 scenario 2
        var r = Typering.reken(BinOp.PLUS, datum(), num("dg", "Tijd"));
        assertTrue(r.ok()); assertTrue(r.type().isDatum());
    }
    @Test void plus_datum_en_unitloos_getal_fout() {         // scenario 2 requires a (time) unit
        assertFalse(Typering.reken(BinOp.PLUS, datum(), num()).ok());
    }
    @Test void macht_met_eenheid_fout() {                    // §4.7: no units allowed
        assertFalse(Typering.reken(BinOp.TOT_DE_MACHT, num("km", "afstand"), num()).ok());
    }
    // jr and mnd are convertible because the resolver reduces both to the SAME canonical (mnd) via the
    // declared "jr = 12 mnd"; sharing only a stelsel is not enough (p44), so the fixtures carry that link.
    static Datatype num(String afk, String stelsel, String canoniek, long factor) {
        return new Datatype.Numeriek(false, 0, Teken.ONBEPAALD,
                new Eenheid(afk, stelsel, canoniek, java.math.BigDecimal.valueOf(factor)));
    }
    @Test void plus_convertibele_eenheden_ok() {
        assertTrue(Typering.reken(BinOp.PLUS, num("jr", "Tijd", "mnd", 12), num("mnd", "Tijd", "mnd", 1)).ok());
    }
    @Test void plus_niet_convertibele_eenheden_fout() {
        assertFalse(Typering.reken(BinOp.PLUS, num("jr", "Tijd", "mnd", 12), num("km", "afstand", "km", 1)).ok());
    }
    @Test void plus_zelfde_stelsel_andere_canoniek_fout() {
        // p44: two independent base units in ONE system (appel/peer, no declared conversion) reduce to
        // disjoint canonicals, so they are NOT inter-convertible — sharing a stelsel must not make them so.
        assertFalse(Typering.reken(BinOp.PLUS, num("app", "Mix", "app", 1), num("pr", "Mix", "pr", 1)).ok());
        assertFalse(Typering.vergelijk(CmpOp.GELIJK, num("app", "Mix", "app", 1), num("pr", "Mix", "pr", 1)).ok());
    }
    @Test void plus_eenheid_op_een_kant_fout() {
        assertFalse(Typering.reken(BinOp.PLUS, num("jr", "Tijd"), num()).ok());
    }

    // §4.4/4.5 maal/gedeeld vereisen numeriek
    @Test void maal_getallen_ok()       { assertTrue(Typering.reken(BinOp.MAAL, num(), num()).ok()); }
    @Test void gedeeld_door_tekst_fout(){ assertFalse(Typering.reken(BinOp.GEDEELD_DOOR, num(), tekst()).ok()); }

    // §5.1/5.5 ordening: alleen getalachtig
    @Test void kleiner_getallen_ok()  { assertTrue(Typering.vergelijk(CmpOp.KLEINER, num(), num()).ok()); }
    @Test void groter_tekst_fout()    { assertFalse(Typering.vergelijk(CmpOp.GROTER, tekst(), tekst()).ok()); }
    @Test void kleiner_resultaat_is_boolean() {
        assertTrue(Typering.vergelijk(CmpOp.KLEINER, num(), num()).type().isBoolean());
    }

    // §5.3/5.6 gelijk/ongelijk: zelfde datatype
    @Test void gelijk_zelfde_type_ok()  { assertTrue(Typering.vergelijk(CmpOp.GELIJK, tekst(), tekst()).ok()); }
    @Test void gelijk_verschillend_type_fout() { assertFalse(Typering.vergelijk(CmpOp.GELIJK, tekst(), num()).ok()); }
    @Test void gelijk_numeriek_vs_percentage_fout() {        // §5.3 Tabel 36: distinct rows
        assertFalse(Typering.vergelijk(CmpOp.GELIJK, num(), new Datatype.Percentage(true, 0)).ok());
    }

    // §5.7-5.10 eerder/later: datum
    @Test void later_datums_ok()    { assertTrue(Typering.vergelijk(CmpOp.LATER, datum(), datum()).ok()); }
    @Test void later_getallen_fout(){ assertFalse(Typering.vergelijk(CmpOp.LATER, num(), num()).ok()); }

    // §5.11-5.16 predicaten
    @Test void leeg_op_elk_type_ok()      { assertTrue(Typering.predicaat(UnairePred.LEEG, num()).ok()); }
    @Test void elfproef_tekst_ok()        { assertTrue(Typering.predicaat(UnairePred.ELFPROEF, tekst()).ok()); }
    @Test void elfproef_numeriek_geheel_ok() { assertTrue(Typering.predicaat(UnairePred.ELFPROEF, num()).ok()); } // §5.13 Tabel 55 sc.1
    @Test void elfproef_boolean_fout()    { assertFalse(Typering.predicaat(UnairePred.ELFPROEF, bool()).ok()); }
    @Test void numeriek_met_exact_tekst_ok()  { assertTrue(Typering.predicaat(UnairePred.NUMERIEK_MET_EXACT, tekst()).ok()); }
    @Test void numeriek_met_exact_getal_fout(){ assertFalse(Typering.predicaat(UnairePred.NUMERIEK_MET_EXACT, num()).ok()); }
    @Test void dagsoort_datum_ok()        { assertTrue(Typering.predicaat(UnairePred.DAGSOORT, datum()).ok()); }
    @Test void dagsoort_tekst_fout()      { assertFalse(Typering.predicaat(UnairePred.DAGSOORT, tekst()).ok()); }

    // §6.1 toekenning
    @Test void toekenning_match_ok()      { assertNull(Typering.toekenning(num(), num())); }
    @Test void toekenning_mismatch_fout() { assertNotNull(Typering.toekenning(num(), tekst())); }
    @Test void toekenning_eenheid_mismatch_fout() {
        assertNotNull(Typering.toekenning(num("jr", "Tijd"), num("km", "afstand")));
    }

    // §3.3.5 a Percentage always carries the "%" unit, optionally a rate "%/jr"
    static Datatype pct()        { return new Datatype.Percentage(true, 0); }
    static Datatype pctPerJaar() { return new Datatype.Percentage(true, 0,
            Eenheid.samengesteld(Datatype.Percentage.PROCENT, new Eenheid("jr", "Tijd"))); }

    @Test void percentage_draagt_altijd_de_procent_eenheid() {
        assertEquals("%", pct().eenheidOf().afkorting());
        assertEquals("%/jr", pctPerJaar().eenheidOf().weergave());
    }
    @Test void percentage_kleiner_dan_percentage_blijft_ok() {
        assertTrue(Typering.vergelijk(CmpOp.KLEINER, pct(), pct()).ok());
    }
    @Test void percentage_gelijk_aan_numeriek_blijft_familiefout() {
        assertFalse(Typering.vergelijk(CmpOp.GELIJK, pct(), num()).ok());   // distinct families
    }
    @Test void percentage_toekenning_met_afwijkende_rate_is_eenheidfout() {
        assertNotNull(Typering.toekenning(pct(), pctPerJaar()));   // "%" vs "%/jr": a simple unit vs a rate
    }
    @Test void percentage_plus_eenheidloos_getal_is_een_eenheidfout() {
        // matrix-aligned (Tabel 32/34 list Percentage with Percentage, never with a unitless plain Numeriek):
        // now that a Percentage carries "%", mixing it with a unitless number flags the §4 unit rule.
        assertFalse(Typering.reken(BinOp.PLUS, pct(), num()).ok());
    }

    // §4.4/4.5 composite-unit synthesis (both operands carry a unit)
    static final Eenheid EUR = new Eenheid("EUR", "Geld");
    static final Eenheid JR  = new Eenheid("jr", "Tijd");
    static Datatype numEenh(Eenheid e) { return new Datatype.Numeriek(false, 0, Teken.ONBEPAALD, e); }

    @Test void maal_rate_keer_zijn_noemereenheid_cancelt_tot_de_teller() {
        // €/jr maal jr = € (the denominator cancels) — was Onbekend
        var res = Typering.reken(BinOp.MAAL, numEenh(Eenheid.samengesteld(EUR, JR)), numEenh(JR));
        assertTrue(res.ok());
        assertEquals("EUR", res.type().eenheidOf().afkorting());
        assertNull(res.type().eenheidOf().noemer(), "de noemer moet weggevallen zijn");
    }
    @Test void gedeeld_eenheid_door_eenheid_geeft_een_rate() {
        // € gedeeld door jr = €/jr — was Onbekend
        var res = Typering.reken(BinOp.GEDEELD_DOOR, numEenh(EUR), numEenh(JR));
        assertTrue(res.ok());
        assertEquals("EUR/jr", res.type().eenheidOf().weergave());
    }
    @Test void gedeeld_door_gelijke_eenheid_geeft_een_dimensieloos_getal() {
        // € gedeeld door € = een getal (dimensionless) — was Onbekend
        var res = Typering.reken(BinOp.GEDEELD_DOOR, numEenh(EUR), numEenh(EUR));
        assertTrue(res.ok());
        assertNull(res.type().eenheidOf(), "een quotiënt van gelijke eenheden is eenheidloos");
    }
    @Test void maal_twee_losse_eenheden_blijft_onbekend() {
        // € maal m = €·m (two numerators) — not representable in the model, stays Onbekend
        var res = Typering.reken(BinOp.MAAL, numEenh(EUR), numEenh(new Eenheid("m", "Lengte")));
        assertTrue(res.type().isOnbekend());
    }
}
