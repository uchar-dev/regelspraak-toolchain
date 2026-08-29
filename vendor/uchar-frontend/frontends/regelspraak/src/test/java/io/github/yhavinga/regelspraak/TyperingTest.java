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
    @Test void plus_datum_en_niet_tijdseenheid_fout() {      // scenario 2: the unit must be a TIJDseenheid
        var r = Typering.reken(BinOp.PLUS, datum(), num("km", "afstand"));
        assertFalse(r.ok());
        assertTrue(r.fout().contains("tijdseenheid"), r::fout);
        assertFalse(Typering.reken(BinOp.MIN, datum(), num("km", "afstand")).ok());
    }
    @Test void plus_datum_en_dagsoort_eenheid_ok() {         // §6.11: a declared dagsoort is a valid duration unit
        var r = Typering.reken(BinOp.PLUS, datum(), num("kerstdagen", null), true);
        assertTrue(r.ok()); assertTrue(r.type().isDatum());
    }

    static Datatype datumTijd() { return new Datatype.DatumTijd(); }

    // Datum-vs-DatumTijd precisie in gelijk/ongelijk/toekenning — a chosen strictness (see
    // grammar-divergences): a mixed toekenning is a type-broken setter, a mixed gelijk never true.
    @Test void gelijk_gemengde_tijd_precisie_fout() {
        var r = Typering.vergelijk(CmpOp.GELIJK, datum(), datumTijd());
        assertFalse(r.ok()); assertTrue(r.fout().contains("tijd-precisie"), r::fout);
        assertFalse(Typering.vergelijk(CmpOp.ONGELIJK, datumTijd(), datum()).ok());
        assertTrue(Typering.vergelijk(CmpOp.GELIJK, datum(), datum()).ok());
        assertTrue(Typering.vergelijk(CmpOp.GELIJK, datumTijd(), datumTijd()).ok());
        // the ordering comparisons stay precision-free (Tabellen 45-52 state no restriction)
        assertTrue(Typering.vergelijk(CmpOp.EERDER, datum(), datumTijd()).ok());
    }
    @Test void toekenning_gemengde_tijd_precisie_fout() {
        assertNotNull(Typering.toekenning(datum(), datumTijd()));
        assertNotNull(Typering.toekenning(datumTijd(), datum()));
        assertNull(Typering.toekenning(datum(), datum()));
        assertNull(Typering.toekenning(datumTijd(), datumTijd()));
    }

    // §4.1/§4.2/§4.3: geheel ± geheel keeps geheel-ness (the sum of integers is an integer), so a
    // geheel-demanding gate (§5.13 elfproef) admits whole-number arithmetic; a decimal or unbounded
    // "getal" operand still drops it.
    @Test void plus_geheel_en_geheel_blijft_geheel() {
        var r = Typering.reken(BinOp.PLUS, num(), num());
        assertTrue(r.ok());
        assertTrue(r.type() instanceof Datatype.Numeriek n && n.geheel(),
                () -> "geheel + geheel moet geheel blijven, kreeg " + r.type());
        assertTrue(Typering.predicaat(UnairePred.ELFPROEF, r.type()).ok(),
                "de elfproef moet een gehele som toelaten (Tabel 55)");
        Datatype tweeDec = new Datatype.Numeriek(false, 2, Teken.ONBEPAALD, null);
        Datatype onbeperkt = new Datatype.Numeriek(false, 0, Teken.ONBEPAALD, null);  // form (c) "getal"
        assertFalse(Typering.reken(BinOp.MIN, num(), tweeDec).type() instanceof Datatype.Numeriek n2 && n2.geheel());
        assertFalse(Typering.reken(BinOp.PLUS, num(), onbeperkt).type() instanceof Datatype.Numeriek n3 && n3.geheel());
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
    @Test void maal_twee_losse_eenheden_geeft_een_opaak_productunit() {
        // §4.4: € maal m = €·m — an opaque PRODUCT unit (no canonical), commutative, not Onbekend.
        Eenheid m = new Eenheid("m", "Lengte");
        var res = Typering.reken(BinOp.MAAL, numEenh(EUR), numEenh(m));
        assertTrue(res.ok());
        assertEquals("EUR·m", res.type().eenheidOf().afkorting(), "het productunit (factoren gesorteerd)");
        var omgekeerd = Typering.reken(BinOp.MAAL, numEenh(m), numEenh(EUR));   // commutatief: m maal € == € maal m
        assertEquals("EUR·m", omgekeerd.type().eenheidOf().afkorting());
    }
    @Test void productunit_is_commutatief_en_associatief_voor_drie_factoren() {
        // §4.4 Tabel 11: unit multiplication is commutative AND associative, so (€·m)·km and €·(m·km) must denote
        // the ONE same product unit (else a correct 3-way product is spuriously rejected on assignment/comparison).
        Eenheid m = new Eenheid("m", "Lengte"), km = new Eenheid("km", "Lengte");
        var ab  = Typering.reken(BinOp.MAAL, numEenh(EUR), numEenh(m)).type();   // € maal m  = €·m
        var abc = Typering.reken(BinOp.MAAL, ab, numEenh(km)).type();            // (€·m) maal km
        var bc  = Typering.reken(BinOp.MAAL, numEenh(m), numEenh(km)).type();    // m maal km = m·km
        var abc2 = Typering.reken(BinOp.MAAL, numEenh(EUR), bc).type();          // € maal (m·km)
        assertEquals(abc.eenheidOf().afkorting(), abc2.eenheidOf().afkorting(), "associatief: één canoniek productunit");
        assertEquals("EUR·km·m", abc.eenheidOf().afkorting(), "gesorteerde factor-multiset");
        assertNull(Typering.toekenning(abc, abc2), "dezelfde 3-weg producteenheid toekennen is geen fout");
    }

    @Test void productunit_toegekend_aan_een_enkele_eenheid_is_een_eenheidfout() {
        // §4.4 / §6.1: €·m is convertible only to an identical product, so assigning it to a € target flags a
        // unit mismatch — where the prior Onbekend silently passed.
        Datatype productEm = Typering.reken(BinOp.MAAL, numEenh(EUR), numEenh(new Eenheid("m", "Lengte"))).type();
        assertNotNull(Typering.toekenning(numEenh(EUR), productEm), "€·m toegekend aan € moet een eenheidfout zijn");
        assertNull(Typering.toekenning(productEm, productEm), "€·m toegekend aan €·m is correct");
    }

    @Test void maal_unitloze_operand_neemt_de_andere_eenheid_over() {   // §4.4 Tabel 11 rij 3/4 + Tabel 13
        // De dimensieloze "1" absorbeert onder vermenigvuldiging (1 × E = E), UNCONDITIONEEL — literal én attribuut
        // (de oude literal/attribuut-split is met besluit 16Y vervallen: was een unitloos ATTRIBUUT × eenheid -> Onbekend).
        var rechts = Typering.reken(BinOp.MAAL, num(), num("mnd", "Tijd"));
        assertTrue(rechts.ok() && rechts.type().eenheidOf() != null && "mnd".equals(rechts.type().eenheidOf().afkorting()),
                () -> "een unitloze operand × eenheid neemt de eenheid (mnd) over, kreeg: " + rechts.type());
        var links = Typering.reken(BinOp.MAAL, num("mnd", "Tijd"), num());   // andersom: E × (none) -> E (rij 3)
        assertTrue(links.ok() && links.type().eenheidOf() != null && "mnd".equals(links.type().eenheidOf().afkorting()),
                () -> "een eenheid × unitloze operand neemt de eenheid (mnd) over, kreeg: " + links.type());
        // §4.5 GEDEELD: een unitloze teller / eenheid is een omgekeerde eenheid (1/mnd) die het Eenheid-model niet kan
        // spellen -> besluit 17b: een LUIDE fout (bewuste divergentie van de dimensioneel onsound Tabel-14-rij-4-cel).
        var deel = Typering.reken(BinOp.GEDEELD_DOOR, num(), num("mnd", "Tijd"));
        assertFalse(deel.ok(), () -> "een unitloze teller / eenheid moet luid falen, kreeg: " + deel.type());
        assertTrue(deel.fout().contains("omgekeerde eenheid"), deel::fout);
    }

    @Test void maal_onspelbare_ratefactor_is_een_luide_fout() {   // besluit 17c
        // €/jr maal m: een tarief maal een niet-cancelende eenheid geeft een producteenheid MET een noemer (€·m/jr)
        // die het Eenheid-model niet kan spellen -> luide fout (was Onbekend "samengestelde eenheid").
        Eenheid m = new Eenheid("m", "Lengte");
        var res = Typering.reken(BinOp.MAAL, numEenh(Eenheid.samengesteld(EUR, JR)), numEenh(m));
        assertFalse(res.ok(), () -> "een onspelbare ratefactor-product moet luid falen, kreeg: " + res.type());
        assertTrue(res.fout().contains("niet uitdrukken"), res::fout);
    }

    @Test void gedeeld_door_een_tarief_is_een_luide_fout() {   // besluit 17c
        // € gedeeld door €/jr heeft een omgekeerde noemer (1/jr) -> onspelbaar -> luide fout (was Onbekend).
        var res = Typering.reken(BinOp.GEDEELD_DOOR, numEenh(EUR), numEenh(Eenheid.samengesteld(EUR, JR)));
        assertFalse(res.ok(), () -> "een gedeeld-door-tarief moet luid falen, kreeg: " + res.type());
        assertTrue(res.fout().contains("niet uitdrukken"), res::fout);
    }
}
