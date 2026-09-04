package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.Eenheid;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fail-fast guard on {@link Eenheid}'s conversion factor: asking for a factor between two units
 * that do not inter-convert is a fault, never the silent 1 it used to answer. That fallback is what
 * turned a name mismatch into a WRONG MAGNITUDE — measured on a reference-type copy that lied only
 * in case ("KG" against the declared "kg"), where one and the same case read 3,75 in the reference
 * interpreter against 3.750,00 down the Drools path.
 *
 * <p>The witnesses split in three, and the split is the point:
 * <ul>
 *   <li>SIX refusals, one per shape a mismatch can take, each carrying BOTH unit names in its
 *       message — a fault a reader can act on rather than a bare stack trace;</li>
 *   <li>the wire-shape guard ({@code canoniek} filled, {@code factorNaarCanoniek} absent — a shape
 *       the schema admits and the outer convertibility gate lets through) plus the absent target;</li>
 *   <li>what the card deliberately leaves alone: the three surviving factors, and the predicate
 *       {@code vereistOmrekening}, which stays FALSE on every mismatch. Eight transpiler sites ask
 *       that question bare — "is this a cross-unit site?" — on operand pairs whose convertibility is
 *       not established, and a later "throw there too" would break them. This test pins that
 *       contract so the breakage cannot happen quietly.</li>
 * </ul>
 *
 * <p>Mutation-measured: with {@code return BigDecimal.ONE} put back at the three throw sites, EIGHT
 * of the twelve go red — the six refusals, the wire shape and the absent target — while the three
 * preserved factors and the predicate stay green. No OTHER test in the suite moves, which is the
 * same zero the call-site probe measured: nothing else covered this branch.
 */
class EenheidFactorTest {

    // The declarations the six mismatch shapes are built from. Each is well-formed on its own;
    // what fails is the PAIRING — two declared units need not inter-convert.
    private static final Eenheid EUR = new Eenheid("EUR", "Valuta");
    private static final Eenheid KG = new Eenheid("kg", "Gewicht");
    private static final Eenheid TON = new Eenheid("t", "Gewicht", "kg", new BigDecimal("1000"));
    /** The case-lie of the measured divergence: a copy repeating kg/Gewicht/kg in a different case. */
    private static final Eenheid KAST = new Eenheid("KG", "GEWICHT", "KG", BigDecimal.ONE, null);
    private static final Eenheid JR = new Eenheid("jr", "Tijd", "mnd", new BigDecimal("12"));
    private static final Eenheid MND = new Eenheid("mnd", "Tijd", "mnd", BigDecimal.ONE);
    /** Same system as jr/mnd, a DIFFERENT canonical — the appel-vs-peer pair tellerConvertibel names. */
    private static final Eenheid DG = new Eenheid("dg", "Tijd", "dg", BigDecimal.ONE);
    private static final Eenheid M = new Eenheid("m", "Lengte", "m", BigDecimal.ONE);
    /** §4.4's opaque product: no canonical, so convertible only to an identical product. */
    private static final Eenheid M_MAAL_M = Eenheid.product(M, M);
    /** The bare corpus shape (WF-038 tijdsduurparameter): no system, no canonical, no factor. */
    private static final Eenheid KALE_CORPUSVORM = new Eenheid("jaar", null, null, null, null);
    private static final Eenheid EUR_PER_JR = Eenheid.samengesteld(EUR, JR);
    private static final Eenheid EUR_PER_MND = Eenheid.samengesteld(EUR, MND);
    private static final Eenheid EUR_PER_KG = Eenheid.samengesteld(EUR, KG);
    /** The wire shape: a canonical WITHOUT the factor that reaches it. Both fields are nullable in
     *  the schema and no WF rule forbids the combination, so this survives verification. */
    private static final Eenheid CANONIEK_ZONDER_FACTOR = new Eenheid("x", "Lengte", "m", null, null);

    /** Every refusal must name BOTH units: a fault that says only "not convertible" leaves the
     *  reader to guess which of the two declarations is the offending one. */
    private static void weigertMetBeideNamen(Eenheid van, Eenheid naar) {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> van.omrekenfactorNaar(naar),
                () -> "de factorvraag " + van.weergave() + " -> " + naar.weergave() + " moet weigeren, niet 1 geven");
        String melding = ex.getMessage();
        assertTrue(melding.contains("'" + van.weergave() + "'"),
                () -> "de melding noemt de broneenheid '" + van.weergave() + "' niet: " + melding);
        assertTrue(melding.contains("'" + naar.weergave() + "'"),
                () -> "de melding noemt de doeleenheid '" + naar.weergave() + "' niet: " + melding);
    }

    // ---- de zes weigervormen -------------------------------------------------------------------

    /** (1) The measured motor behind the divergence: a copy that differs from the declaration only
     *  in case matches neither on the abbreviation nor on the canonical. */
    @Test void kastleugen_weigert_de_factorvraag() {
        weigertMetBeideNamen(KAST, TON);
    }

    /** (2) Two units from two systems: nothing ties them, so there is no ratio to give. */
    @Test void twee_stelsels_weigeren_de_factorvraag() {
        weigertMetBeideNamen(EUR, KG);
    }

    /** (3) One system, two canonicals: sharing an eenheidstelsel is NOT convertibility. */
    @Test void twee_canonieken_binnen_een_stelsel_weigeren_de_factorvraag() {
        weigertMetBeideNamen(JR, DG);
    }

    /** (4) A plain unit against a rate: the numerators agree, the SHAPES do not. */
    @Test void kale_eenheid_tegen_tarief_weigert_de_factorvraag() {
        weigertMetBeideNamen(EUR, EUR_PER_JR);
    }

    /** (5) The opaque product against one of its own factors — no canonical on either side of the
     *  question. The bare corpus shape (canoniek null, 28 occurrences on the wire) refuses through
     *  the same branch, so it is pinned here too rather than in a witness of its own. */
    @Test void opaak_product_weigert_de_factorvraag() {
        weigertMetBeideNamen(M_MAAL_M, M);
        weigertMetBeideNamen(KALE_CORPUSVORM, MND);
    }

    /** (6) Two rates whose numerators agree and whose DENOMINATORS do not. */
    @Test void tarief_tegen_ander_tarief_weigert_de_factorvraag() {
        weigertMetBeideNamen(EUR_PER_JR, EUR_PER_KG);
    }

    // ---- de klem op de wirevorm en op het ontbrekende doel --------------------------------------

    /** The one shape the outer convertibility gate does NOT catch: same canonical, no declared
     *  factor. It used to answer 1 — a factor nobody declared. It now names itself, through the
     *  factor question AND through the predicate, which is the single public answer this card
     *  changes (it was false). */
    @Test void canoniek_zonder_factor_klemt_ook_via_het_predikaat() {
        assertTrue(CANONIEK_ZONDER_FACTOR.convertibleNaar(M), "de buitenste poortwacht laat deze vorm door");
        IllegalStateException direct = assertThrows(IllegalStateException.class,
                () -> CANONIEK_ZONDER_FACTOR.omrekenfactorNaar(M));
        assertTrue(direct.getMessage().contains("zonder gedeclareerde factorNaarCanoniek"),
                () -> "de melding moet de ontbrekende factor noemen, niet de inwisselbaarheid: " + direct.getMessage());
        IllegalStateException viaPredicaat = assertThrows(IllegalStateException.class,
                () -> CANONIEK_ZONDER_FACTOR.vereistOmrekening(M));
        assertEquals(direct.getMessage(), viaPredicaat.getMessage(),
                "het predikaat moet dezelfde fout doorlaten, niet stil false antwoorden");
    }

    /** No target is no factor either. Every caller in the repo passes a non-null target (measured);
     *  the precondition keeps that path dead instead of assuming it is. */
    @Test void ontbrekend_doel_weigert_de_factorvraag() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> EUR.omrekenfactorNaar(null));
        assertTrue(ex.getMessage().contains("'EUR'") && ex.getMessage().contains("(geen eenheid)"),
                () -> "de melding moet de bron en het ontbrekende doel noemen: " + ex.getMessage());
    }

    // ---- wat ongemoeid blijft: de factoren zelf -------------------------------------------------

    /** jr -> mnd is 12: the declared factors divide, they do not collapse to the identity. */
    @Test void jaar_naar_maand_blijft_twaalf() {
        assertEquals(0, JR.omrekenfactorNaar(MND).compareTo(new BigDecimal("12")),
                () -> "jr -> mnd moet 12 blijven, kreeg " + JR.omrekenfactorNaar(MND));
        assertTrue(JR.vereistOmrekening(MND), "jr -> mnd is een echte omrekening");
    }

    /** mnd -> jr is the non-terminating inverse, pre-rounded on DECIMAL64 — the single-ratio path
     *  the exact {num, den} pair exists to avoid, and it must keep its value. */
    @Test void maand_naar_jaar_blijft_een_twaalfde() {
        assertEquals("0.08333333333333333", MND.omrekenfactorNaar(JR).toPlainString(),
                "mnd -> jr moet 1/12 op DECIMAL64 blijven");
        BigDecimal[] exact = MND.exacteHerschaalFactoren(JR);
        assertEquals(0, exact[0].compareTo(BigDecimal.ONE), "exacte teller");
        assertEquals(0, exact[1].compareTo(new BigDecimal("12")), "exacte noemer");
    }

    /** The denominator INVERTS: EUR/mnd -> EUR/jr is 12, because the jr that replaces the mnd is
     *  twelve of it. The numerators share an abbreviation, so a numerator-only reading would give 1
     *  here — the shape a careless guard loses first. */
    @Test void noemerinversie_blijft_staan() {
        assertEquals(0, EUR_PER_MND.omrekenfactorNaar(EUR_PER_JR).compareTo(new BigDecimal("12")),
                () -> "EUR/mnd -> EUR/jr moet 12 blijven, kreeg " + EUR_PER_MND.omrekenfactorNaar(EUR_PER_JR));
        assertTrue(EUR_PER_MND.vereistOmrekening(EUR_PER_JR), "EUR/mnd -> EUR/jr is een echte omrekening");
    }

    // ---- wat ongemoeid blijft: het predikaat ----------------------------------------------------

    /** The contract the eight bare predicate sites in the transpilers lean on: on a pair with no
     *  common unit there is no conversion to require, so the answer is FALSE — explicitly now,
     *  rather than read off an invented factor of 1. Turning any of these into a throw would fault
     *  a §8.2 timeline bound, an additive pair or a begrenzing bound that today merely asks. */
    @Test void vereistOmrekening_blijft_false_op_elke_mismatch_en_op_niets() {
        Eenheid[][] mismatches = {
                {KAST, TON}, {EUR, KG}, {JR, DG}, {EUR, EUR_PER_JR}, {M_MAAL_M, M}, {EUR_PER_JR, EUR_PER_KG},
        };
        for (Eenheid[] paar : mismatches) {
            assertFalse(paar[0].vereistOmrekening(paar[1]),
                    () -> "vereistOmrekening moet false blijven op " + paar[0].weergave() + " -> " + paar[1].weergave());
            assertFalse(paar[0].convertibleNaar(paar[1]),
                    () -> "en convertibleNaar blijft de manier om 'geen gedeelde eenheid' van 'zelfde eenheid' te "
                            + "onderscheiden: " + paar[0].weergave() + " -> " + paar[1].weergave());
        }
        assertFalse(EUR.vereistOmrekening(null), "een afwezig doel vraagt geen omrekening, het weigert er een");
    }
}
