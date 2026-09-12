package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pins the Dutch adjectival "-e" inflection that lets a subselectie's written kenmerk form bind the
 *  declared base — including the cases deliberately left unmatched (errs-safe markers). */
class KenmerkMatcherTest {

    private static Objecttype met(String... kenmerken) {
        Objecttype ot = new Objecttype("Ploeg");
        for (String k : kenmerken) ot.kenmerken.add(k);
        return ot;
    }

    /** A subtype of {@code ouder} with its own kenmerken — the chain SUBTYPING §3.2 resolves along. */
    private static Objecttype onder(Objecttype ouder, String... kenmerken) {
        Objecttype ot = met(kenmerken);
        ot.supertype = ouder;
        return ot;
    }

    @Test void exacte_basisvorm_matcht() {
        assertEquals("minderjarig", KenmerkMatcher.vind(met("minderjarig"), "minderjarig"));
        assertEquals("minderjarig", KenmerkMatcher.vind(met("minderjarig"), "Minderjarig")); // hoofdletter-ongevoelig
    }

    @Test void verbogen_e_vorm_matcht_de_basis() {                  // de spec's §13.4.2 voorbeeld
        assertEquals("minderjarig", KenmerkMatcher.vind(met("minderjarig"), "minderjarige"));
        assertEquals("groen", KenmerkMatcher.vind(met("groen"), "groene"));
    }

    @Test void lange_klinker_verkort_bij_verbuiging() {             // groot->grote, duurzaam->duurzame
        assertEquals("groot", KenmerkMatcher.vind(met("groot"), "grote"));
        assertEquals("duurzaam", KenmerkMatcher.vind(met("duurzaam"), "duurzame"));
    }

    @Test void exact_gaat_voor_verbuiging() {
        // both "groot" and a hypothetical "grote" declared: the written "grote" binds the exact one.
        assertEquals("grote", KenmerkMatcher.vind(met("groot", "grote"), "grote"));
    }

    @Test void onbekende_vorm_matcht_niet() {
        assertNull(KenmerkMatcher.vind(met("minderjarig"), "volwassene"));
        assertNull(KenmerkMatcher.vind(met("minderjarig"), "meerderjarig"));
    }

    @Test void gedocumenteerde_grens_geen_medeklinkerverdubbeling_of_verscherping() {
        // wit->witte (stress-doubling) and vies->vieze (s->z) are NOT modelled — they would mis-handle
        // unstressed endings; such a written form stays unmatched (a marker), never a wrong bind.
        assertNull(KenmerkMatcher.vind(met("wit"), "witte"));
        assertNull(KenmerkMatcher.vind(met("vies"), "vieze"));
    }

    @Test void digraaf_stam_overgenereert_de_basisvorm_niet() {
        // A long-vowel-digraph stem inflects ONLY to its shortened form; the bare base+"e" is a
        // non-word and must not be generated (else a misspelled "hooge" would bind, and it would
        // collide with a short-vowel sibling — see ambigue_verbuiging_bindt_niets).
        assertEquals(List.of("hoge"), KenmerkMatcher.verbogenVormen("hoog"));
        assertEquals(List.of("grote"), KenmerkMatcher.verbogenVormen("groot"));
        assertEquals(List.of("duurzame"), KenmerkMatcher.verbogenVormen("duurzaam"));
        assertEquals("hoog", KenmerkMatcher.vind(met("hoog"), "hoge"));   // canonical -e binds
        assertNull(KenmerkMatcher.vind(met("hoog"), "hooge"));            // non-word does not
    }

    @Test void meerwoordig_kenmerk_verbuigt_op_de_kop_niet_de_staart() {
        // §3.5 a multi-word bijvoeglijk kenmerk inflects on its HEAD adjective, with the prepositional tail
        // kept verbatim: "vaarbaar bij eb" -> "vaarbare bij eb", NOT the tail-inflected non-word
        // "vaarbaar bij ebe". The head's digraph-shortening still applies ("groot bereik" -> "grote bereik").
        assertEquals(List.of("vaarbare bij eb"), KenmerkMatcher.verbogenVormen("vaarbaar bij eb"));
        assertEquals(List.of("grote bereik"), KenmerkMatcher.verbogenVormen("groot bereik"));
        assertEquals("vaarbaar bij eb", KenmerkMatcher.vind(met("vaarbaar bij eb"), "vaarbare bij eb"));
        assertEquals("vaarbaar bij eb", KenmerkMatcher.vind(met("vaarbaar bij eb"), "Vaarbare bij eb"));
        // the tail-inflected non-word must NOT bind (proves the fix, not a looser match)
        assertNull(KenmerkMatcher.vind(met("vaarbaar bij eb"), "vaarbaar bij ebe"));
        // the exact base still wins, and an unrelated phrase stays unmatched
        assertEquals("vaarbaar bij eb", KenmerkMatcher.vind(met("vaarbaar bij eb"), "vaarbaar bij eb"));
        assertNull(KenmerkMatcher.vind(met("vaarbaar bij eb"), "vaarbare bij vloed"));
    }

    @Test void ambigue_verbuiging_bindt_niets() {
        // "grote" is the regular inflection of BOTH "groot" (shortened) and "grot" (plain +e, since
        // its doubled "grotte" is unmodelled). Binding either would make the result depend on
        // declaration order — a silent wrong count — so an ambiguous inflection binds NONE.
        assertNull(KenmerkMatcher.vind(met("groot", "grot"), "grote"));
        assertNull(KenmerkMatcher.vind(met("grot", "groot"), "grote"));  // order-independent
        assertNull(KenmerkMatcher.vind(met("laat", "lat"), "late"));
        // each alone is unambiguous and still binds
        assertEquals("groot", KenmerkMatcher.vind(met("groot"), "grote"));
        assertEquals("laat", KenmerkMatcher.vind(met("laat"), "late"));
    }

    @Test void een_geerfd_kenmerk_bindt_op_het_subtype() {
        // SUBTYPING §3.2: resolution walks the chain UPWARD, so a kenmerk the ANCESTOR declares binds in
        // every predicaat form on a subtype — base form and §5.6 inflection alike. Reading only the own
        // list left an inherited kenmerk binding in NO predicaat form at all, across all eight call sites.
        Objecttype sub = onder(met("betwist"), "geborgen");
        assertEquals("betwist", KenmerkMatcher.vind(sub, "betwist"));
        assertEquals("betwist", KenmerkMatcher.vind(sub, "betwiste"));
        assertEquals("geborgen", KenmerkMatcher.vind(sub, "geborgene"));   // its own still binds too
        assertNull(KenmerkMatcher.vind(sub, "verlaten"));
    }

    @Test void ambigue_verbuiging_over_de_keten_heen_bindt_niets() {
        // The ambiguity guard is CHAIN-WIDE: "grote" inflects from the ancestor's "groot" and the
        // subtype's "grot" alike, so it binds NEITHER. Guarding per type would take the nearest and make
        // the answer depend on where the author declared — the order-dependence the guard exists against.
        Objecttype sub = onder(met("groot"), "grot");
        assertNull(KenmerkMatcher.vind(sub, "grote"));
        assertEquals("groot", KenmerkMatcher.vind(sub, "groot"));   // the base form binds wherever it sits
    }
}
