package io.github.yhavinga.regelspraak.transpiler.kern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE shared naming contract: one place turns a Dutch phrase into a Java/DRL identifier, a
 * getter/setter, a rule-name splice, or a comment splice — exhaustively pinned here so neither
 * back-end can drift on what a written name maps to.
 */
class NamenTest {

    @Test void ident_camelt_en_pascalt_over_de_woorden() {
        assertEquals("rechtOpKorting", Namen.ident("recht op korting", false));
        assertEquals("RechtOpKorting", Namen.ident("recht op korting", true));
        assertEquals("NatuurlijkPersoon", Namen.ident("Natuurlijk persoon", true));
        assertEquals("grens", Namen.ident("grens", false));
    }

    @Test void ident_stript_GEEN_lidwoord() {
        // a kenmerk whose name deliberately carries an article ("de duurste reis") binds its FULL name —
        // stripping would collide it with a shorter sibling; the resolver already delivers declared names bare.
        assertEquals("deDuursteReis", Namen.ident("de duurste reis", false));
        assertEquals("deler", Namen.ident("deler", false));
    }

    @Test void ident_splitst_op_niet_letter_cijfer_runs() {
        assertEquals("eMailadres", Namen.ident("e-mailadres", false));
        assertEquals("x", Namen.ident("  ", false));
    }

    @Test void ident_normaliseert_een_ALL_CAPS_woord() {
        // the pinned wart: the camel form of a shouted parameter must NOT read "sTANDAARD…"
        assertEquals("standaard", Namen.ident("STANDAARD", false));
        assertEquals("standaardBelastingvermindering", Namen.ident("STANDAARD BELASTINGVERMINDERING", false));
        assertEquals("StandaardTermijn", Namen.ident("STANDAARD TERMIJN", true));
        // a single capital letter is NOT a shout ("de X coördinaat")
        assertEquals("xCoordinaat", Namen.ident("X coordinaat", false));
    }

    @Test void ident_en_pascal_delen_de_ALL_CAPS_normalisatie() {
        // the fire-time bean contract (runtime pascal) and the transpile-time ident must target the SAME
        // getter for an acronym-carrying name — "BTW tarief" reads getBtwTarief on BOTH back-ends.
        assertEquals("BtwTarief", io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime.pascal("BTW tarief"));
        assertEquals("getBtwTarief", Namen.getter("BTW tarief"));
        assertEquals("btwTarief", Namen.ident("BTW tarief", false));
    }

    @Test void getter_setter_volgen_ident() {
        assertEquals("getRechtOpKorting", Namen.getter("recht op korting"));
        assertEquals("setLeeftijd", Namen.setter("leeftijd"));
    }

    @Test void commentVeilig_kan_niet_uit_een_blokcommentaar_breken() {
        // escaping by construction: a bron phrase containing */ must not END the /* TODO … */ splice —
        // the remainder would parse as DRL and sink the whole package.
        assertFalse(Namen.commentVeilig("evil */ rule \"x\"").contains("*/"));
        assertFalse(Namen.commentVeilig("regel\nmet\nregels").contains("\n"),
                "een newline zou een //-commentaar voortijdig beeindigen");
    }

    @Test void drlString_escapet_ingebedde_quotes_in_plaats_van_strippen() {
        // escaping by construction: an enum label with embedded quotes compares against the REAL label.
        assertEquals("\"zg. \\\"digitale\\\" kaart\"", Namen.drlString("zg. \"digitale\" kaart"));
        assertEquals("\"pad\\\\naar\"", Namen.drlString("pad\\naar"));
        assertEquals("\"gewoon\"", Namen.drlString("gewoon"));
    }
}
