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
    @Test void plus_datum_en_getal_geeft_datum() {           // §4.1 scenario 2
        var r = Typering.reken(BinOp.PLUS, datum(), num());
        assertTrue(r.ok()); assertTrue(r.type().isDatum());
    }
    @Test void plus_convertibele_eenheden_ok() {
        assertTrue(Typering.reken(BinOp.PLUS, num("jr", "Tijd"), num("mnd", "Tijd")).ok());
    }
    @Test void plus_niet_convertibele_eenheden_fout() {
        assertFalse(Typering.reken(BinOp.PLUS, num("jr", "Tijd"), num("km", "afstand")).ok());
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

    // §5.7-5.10 eerder/later: datum
    @Test void later_datums_ok()    { assertTrue(Typering.vergelijk(CmpOp.LATER, datum(), datum()).ok()); }
    @Test void later_getallen_fout(){ assertFalse(Typering.vergelijk(CmpOp.LATER, num(), num()).ok()); }

    // §5.11-5.16 predicaten
    @Test void leeg_op_elk_type_ok()      { assertTrue(Typering.predicaat(UnairePred.LEEG, num()).ok()); }
    @Test void elfproef_tekst_ok()        { assertTrue(Typering.predicaat(UnairePred.ELFPROEF, tekst()).ok()); }
    @Test void elfproef_numeriek_fout()   { assertFalse(Typering.predicaat(UnairePred.ELFPROEF, num()).ok()); }
    @Test void dagsoort_datum_ok()        { assertTrue(Typering.predicaat(UnairePred.DAGSOORT, datum()).ok()); }
    @Test void dagsoort_tekst_fout()      { assertFalse(Typering.predicaat(UnairePred.DAGSOORT, tekst()).ok()); }

    // §6.1 toekenning
    @Test void toekenning_match_ok()      { assertNull(Typering.toekenning(num(), num())); }
    @Test void toekenning_mismatch_fout() { assertNotNull(Typering.toekenning(num(), tekst())); }
    @Test void toekenning_eenheid_mismatch_fout() {
        assertNotNull(Typering.toekenning(num("jr", "Tijd"), num("km", "afstand")));
    }
}
