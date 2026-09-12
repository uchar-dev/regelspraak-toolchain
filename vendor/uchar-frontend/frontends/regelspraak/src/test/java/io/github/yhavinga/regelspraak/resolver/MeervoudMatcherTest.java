package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the regular Dutch noun plural that lets a §8.1.6 uniciteitscontrole bind its plural-form
 *  attribute and object-type names back to the declared singulars — including the safe limits. */
class MeervoudMatcherTest {

    private static Objecttype metAttributen(String... namen) {
        Objecttype ot = new Objecttype("Ding");
        for (String n : namen) ot.attributen.put(n, new Datatype.Tekst());
        return ot;
    }

    /** A subtype of {@code ouder} with its own attributes — the chain SUBTYPING §3.2 resolves along. */
    private static Objecttype onder(Objecttype ouder, String... namen) {
        Objecttype ot = metAttributen(namen);
        ot.supertype = ouder;
        return ot;
    }

    @Test void regelmatige_meervouden() {
        assertTrue(MeervoudMatcher.meervoudsVormen("burgerservicenummer").contains("burgerservicenummers")); // -s
        assertTrue(MeervoudMatcher.meervoudsVormen("ploeg").contains("ploegen"));                            // -en
        assertTrue(MeervoudMatcher.meervoudsVormen("naam").contains("namen"));                               // aa->a +en
        assertTrue(MeervoudMatcher.meervoudsVormen("persoon").contains("personen"));                         // oo->o +en
    }

    @Test void attribuut_bindt_enkelvoud_en_meervoud() {
        Objecttype ot = metAttributen("burgerservicenummer");
        assertEquals("burgerservicenummer", MeervoudMatcher.vindAttribuut(ot, "burgerservicenummer")); // exact
        assertEquals("burgerservicenummer", MeervoudMatcher.vindAttribuut(ot, "burgerservicenummers")); // plural
        assertNull(MeervoudMatcher.vindAttribuut(ot, "adres"));                                          // unknown
    }

    @Test void objecttype_bindt_naam_meervoud_en_gedeclareerd_meervoud() {
        Objecttype ploeg = new Objecttype("Ploeg");
        Objecttype np = new Objecttype("Natuurlijk persoon");
        np.meervoud = "Natuurlijke personen";
        List<Objecttype> typen = List.of(ploeg, np);
        assertEquals(ploeg, MeervoudMatcher.vindObjecttype(typen, "Ploeg"));                 // exact name
        assertEquals(ploeg, MeervoudMatcher.vindObjecttype(typen, "Ploegen"));               // regular plural
        assertEquals(np, MeervoudMatcher.vindObjecttype(typen, "Natuurlijke personen"));     // declared meervoud (irregular)
        assertNull(MeervoudMatcher.vindObjecttype(typen, "Routes"));                         // unknown
    }

    @Test void gedeclareerd_attribuut_meervoud_wint_en_dekt_onregelmatige() {
        // The spec-sanctioned source (footnote 2) is the declared "(mv: …)"; it is matched exactly,
        // ahead of synthesis, so it covers irregular plurals synthesis deliberately refuses.
        Objecttype kind = metAttributen("kind");
        kind.attribuutMeervoud.put("kind", "kinderen");
        assertEquals("kind", MeervoudMatcher.vindAttribuut(kind, "kinderen")); // irregular -eren plural
        assertEquals("kind", MeervoudMatcher.vindAttribuut(kind, "kind"));     // singular still binds
        Objecttype stad = metAttributen("stad");                               // synth refuses (stad->[])
        stad.attribuutMeervoud.put("stad", "steden");
        assertEquals("stad", MeervoudMatcher.vindAttribuut(stad, "steden"));   // vowel-change plural
    }

    @Test void dubbelzinnig_meervoud_over_de_declaratie_en_synthese_heen_bindt_niets() {
        // Two attributes declaring the SAME plural, or a declared plural that is also another
        // attribute's regular plural, is ambiguous across the declared+synthesised pool -> binds none
        // (not an order-dependent guess), like the synthesis pool already did.
        Objecttype tweeDecl = metAttributen("zoon", "dochter");
        tweeDecl.attribuutMeervoud.put("zoon", "kinderen");
        tweeDecl.attribuutMeervoud.put("dochter", "kinderen");
        assertNull(MeervoudMatcher.vindAttribuut(tweeDecl, "kinderen"));
        Objecttype kruis = metAttributen("groep", "ploeg");   // groep declares "ploegen"; it is also ploeg's regular plural
        kruis.attribuutMeervoud.put("groep", "ploegen");
        assertNull(MeervoudMatcher.vindAttribuut(kruis, "ploegen"));
        Objecttype exact = metAttributen("wagens", "auto");   // a singular literally named like a plural still wins exactly
        exact.attribuutMeervoud.put("auto", "wagens");
        assertEquals("wagens", MeervoudMatcher.vindAttribuut(exact, "wagens"));
    }

    @Test void een_geerfd_attribuut_bindt_op_het_subtype() {
        // SUBTYPING §3.2: resolution walks the chain UPWARD, so both the written singular and the written
        // plural of an attribute the ANCESTOR declares bind on a subtype. This is the p18 shape — a
        // verdeling writing "de bergingsaandelen van alle Hoofdbergers" against a "bergingsaandeel"
        // declared on Berger — which used to leave the plural standing on the wire, where no declaration
        // carries it and WF-034 rightly refuses it.
        Objecttype ouder = metAttributen("bergingsaandeel");
        ouder.attribuutMeervoud.put("bergingsaandeel", "bergingsaandelen");
        Objecttype sub = onder(ouder, "rang");
        assertEquals("bergingsaandeel", MeervoudMatcher.vindAttribuut(sub, "bergingsaandeel"));
        // the "(mv: …)" sits at the DECLARANT, so it is read there and not on the type that inherits it
        assertEquals("bergingsaandeel", MeervoudMatcher.vindAttribuut(sub, "bergingsaandelen"));
        assertEquals("rang", MeervoudMatcher.vindAttribuut(sub, "rangen"));   // its own still binds too
        assertNull(MeervoudMatcher.vindAttribuut(sub, "adres"));
    }

    @Test void dubbelzinnig_meervoud_over_de_keten_heen_bindt_niets() {
        // The ambiguity guard is CHAIN-WIDE, exactly as resolution is: a written plural that is the plural
        // of one attribute on the subtype AND of another on the ancestor binds NEITHER. Applying the guard
        // per type instead would return the nearest one, making the answer depend on where the author
        // happened to declare — the order-dependence this matcher exists to refuse.
        Objecttype ouder = metAttributen("zoon");
        ouder.attribuutMeervoud.put("zoon", "kinderen");
        Objecttype sub = onder(ouder, "dochter");
        sub.attribuutMeervoud.put("dochter", "kinderen");
        assertNull(MeervoudMatcher.vindAttribuut(sub, "kinderen"));
        // …while an exact singular anywhere on the chain still wins over any plural on it
        Objecttype metPlural = onder(metAttributen("wagens"), "auto");
        metPlural.attribuutMeervoud.put("auto", "wagens");
        assertEquals("wagens", MeervoudMatcher.vindAttribuut(metPlural, "wagens"));
    }

    @Test void foute_meervouden_worden_niet_overgegenereerd() {
        // A consonant-doubling stem (man->mannen, bom->bommen) is left unmodelled rather than
        // generating the lossy "manen"/"bomen" — those are the real plurals of "maan"/"boom", so
        // generating them would mis-bind. And "-s" is not generated for an arbitrary stem ("naam"
        // -> "naams" is a non-word). So the wrong forms do not bind:
        assertNull(MeervoudMatcher.vindAttribuut(metAttributen("man"), "manen"));
        assertNull(MeervoudMatcher.vindAttribuut(metAttributen("bom"), "bomen"));
        assertNull(MeervoudMatcher.vindAttribuut(metAttributen("naam"), "naams"));
        // …while the genuine plurals still bind their correct singular:
        assertEquals("maan", MeervoudMatcher.vindAttribuut(metAttributen("maan"), "manen"));
        assertEquals("boom", MeervoudMatcher.vindAttribuut(metAttributen("boom"), "bomen"));
    }
}
