package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

import java.util.ArrayList;
import java.util.List;

/** Binds a written kenmerk name from a predicaat ("die een <X> zijn", "indien hij <X> is") to a
 *  declared kenmerk of an object type.
 *
 *  <p>A bijvoeglijk kenmerk is declared in its base/predicative form ("is <b>minderjarig</b>
 *  kenmerk", §3.5), but a subselectie writes it nominalized — a §5.6 subselectie
 *  reads "het aantal deelnemers van de groep die een <b>minderjarige</b> zijn" against that declared
 *  "minderjarig", and §5.7 does the same. Plain case-insensitive equality misses the "-e" inflection,
 *  so that subselectie would silently stay a marker.
 *
 *  <p>Matching tries exact (case-insensitive) against the base form first — always preferred over an
 *  inflected hit, so a kenmerk that happens to be the inflection of another still binds itself — then
 *  the regular Dutch adjectival "-e" inflection of the base ({@link #verbogenVormen}). The inflection
 *  pass is <b>ambiguity-safe</b>: if the written form is the regular inflection of more than one
 *  declared kenmerk it binds NONE (an honest marker), so the result never depends on declaration
 *  order. This matters because a long-vowel base and a short-vowel base can share an inflected form
 *  ("groot"→"grote" and the (unmodelled-doubling) "grot"→"grote").
 *
 *  <p>Only the REGULAR inflection is generated: the plain "-e" suffix (minderjarig→minderjarige,
 *  groen→groene) OR, for a base ending in a long-vowel digraph plus one consonant, the
 *  syllable-opening shortening alone (groot→grote, duurzaam→duurzame) — never the bare "groote", which
 *  is not a word. Stress-dependent consonant doubling (wit→witte) and voicing (vies→vieze) are
 *  deliberately NOT modelled: they would mis-transform unstressed endings (-ig, -el), so a kenmerk
 *  whose written inflection needs them stays a marker — an honest no-op, never a wrong match.
 *
 *  <p>The plural meervoudsvorm a bijvoeglijk kenmerk may declare ("(mv: …)", §13.2.29) is NOT matched
 *  here: the grammar's predicaat has no bare-plural-noun alternative ("die minderjarigen zijn" does
 *  not parse), and the spec writes the singular nominalized form even for a plural subject, so a
 *  meervoudsvorm has no reachable predicaat input to bind — see the p29 backlog note. */
final class KenmerkMatcher {
    private KenmerkMatcher() {}

    /** The declared kenmerk of {@code ot} that the written predicaat name {@code geschreven} denotes,
     *  or null when none does (an unknown name, an attribute, a role, or an ambiguous inflection) —
     *  left to the marker path.
     *
     *  <p>Both passes range over the whole SUPERTYPE CHAIN (IR-SPEC ch. 2.2 / SUBTYPING §3.2): a kenmerk
     *  declared on an ancestor is a member of {@code ot}, so it must bind in every predicaat form, and
     *  the §3.5 soort check that follows each call reads the same declarant. The ambiguity guard ranges
     *  over the chain for the same reason it ranges over one type's declarations — an inflection that
     *  fits a kenmerk here and another one hop up binds NEITHER, never the nearer one. */
    static String vind(Objecttype ot, String geschreven) {
        if (ot == null || geschreven == null) return null;
        String basis = ot.kenmerkNaam(geschreven);            // base form — always preferred
        if (basis != null) return basis;
        String enige = null;                                  // the single kenmerk this form inflects from
        for (Objecttype t : ot.keten())
            for (String k : t.kenmerken)
                for (String vorm : verbogenVormen(k))
                    if (vorm.equalsIgnoreCase(geschreven)) {
                        if (enige != null && !enige.equalsIgnoreCase(k)) return null;  // ambiguous → marker
                        enige = k;
                    }
        return enige;
    }

    /** The regular attributive/nominalized "-e" form of a bijvoeglijk-naamwoord base. Exactly one
     *  candidate: the syllable-opening shortened form for a long-vowel-digraph-plus-one-consonant
     *  stem, otherwise the plain "-e" suffix.
     *
     *  <p>A MULTI-WORD bijvoeglijk kenmerk ("vaarbaar bij eb", §3.5) inflects on its HEAD — the
     *  adjective is the first word, the rest a prepositional/complement tail — so "vaarbare bij eb",
     *  not the tail-inflected non-word "vaarbaar bij ebe". So the head word is inflected and the
     *  tail re-appended verbatim; a single-word phrase is just the head with no tail. */
    static List<String> verbogenVormen(String basis) {
        List<String> uit = new ArrayList<>();
        String b = basis.toLowerCase();
        int spatie = b.indexOf(' ');
        if (spatie > 0) {                                     // multi-word: inflect the head, keep the tail
            String staart = b.substring(spatie);              // includes the leading space
            for (String v : verbogenVormen(b.substring(0, spatie))) uit.add(v + staart);
            return uit;
        }
        if (b.isEmpty() || b.endsWith("e")) return uit;       // an -e base is already its inflected form
        // A long-vowel digraph (aa|ee|oo|uu) in the final closed syllable shortens when "-e" opens the
        // syllable: …VV + one consonant → single V + that consonant + e (groot→grote, duurzaam→duurzame).
        // The bare base+"e" (a non-word, "groote") is NOT also emitted — it would over-generate and
        // collide with a short-vowel sibling's regular inflection.
        int n = b.length();
        if (n >= 3) {
            char last = b.charAt(n - 1);
            char v1 = b.charAt(n - 3), v2 = b.charAt(n - 2);
            boolean digraf = v1 == v2 && (v1 == 'a' || v1 == 'e' || v1 == 'o' || v1 == 'u');
            if (digraf && isMedeklinker(last)) {
                uit.add(b.substring(0, n - 3) + v1 + last + "e");
                return uit;
            }
        }
        uit.add(b + "e");                                     // klein→kleine, groen→groene, minderjarig→minderjarige
        return uit;
    }

    private static boolean isMedeklinker(char c) {
        return c >= 'a' && c <= 'z' && "aeiou".indexOf(c) < 0;
    }
}
