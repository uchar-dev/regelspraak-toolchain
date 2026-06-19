package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

import java.util.ArrayList;
import java.util.List;

/** Binds a written kenmerk name from a predicaat ("die een <X> zijn", "indien hij <X> is") to a
 *  declared kenmerk of an object type.
 *
 *  <p>A bijvoeglijk kenmerk is declared in its base/predicative form ("is <b>minderjarig</b>
 *  kenmerk"), but a subselectie naturally writes it nominalized — the spec's own canonical §13.4.2
 *  example reads "het aantal passagiers van de reis die een <b>minderjarige</b> zijn" against that
 *  declared "minderjarig", and §5.7 does the same. Plain case-insensitive equality misses the "-e"
 *  inflection, so the spec's primary subselectie example would silently stay a marker.
 *
 *  <p>Matching tries exact (case-insensitive) against the base form first — always preferred over an
 *  inflected hit, so a kenmerk that happens to be the inflection of another still binds itself —
 *  then the regular Dutch adjectival "-e" inflection of the base ({@link #verbogenVormen}).
 *
 *  <p>Only the REGULAR inflection is generated: the plain "-e" suffix and the long-vowel-digraph
 *  shortening that opens the final syllable (duurzaam→duurzame, groot→grote). Stress-dependent
 *  consonant doubling (wit→witte) and consonant voicing (vies→vieze) are deliberately NOT modelled —
 *  they would mis-transform unstressed endings (-ig, -el), and a kenmerk whose written inflection
 *  needs them simply stays a marker: an honest no-op, never a wrong match. Candidates are generated
 *  from the controlled declared base and tested against the written form (never base against base),
 *  so an over-generated non-word candidate (e.g. "duurzaame" from the plain-suffix rule) cannot
 *  collide with a real name.
 *
 *  <p>The plural meervoudsvorm a bijvoeglijk kenmerk may declare ("(mv: …)", §13.2.29) is NOT matched
 *  here: the grammar's predicaat has no bare-plural-noun alternative ("die minderjarigen zijn" does
 *  not parse), and the spec writes the singular nominalized form even for a plural subject, so a
 *  meervoudsvorm has no reachable predicaat input to bind — see the p29 backlog note. */
final class KenmerkMatcher {
    private KenmerkMatcher() {}

    /** The declared kenmerk of {@code ot} that the written predicaat name {@code geschreven} denotes,
     *  or null when none does (an unknown name, an attribute, a role) — left to the marker path. */
    static String vind(Objecttype ot, String geschreven) {
        if (ot == null || geschreven == null) return null;
        for (String k : ot.kenmerken)                         // base form — preferred
            if (k.equalsIgnoreCase(geschreven)) return k;
        for (String k : ot.kenmerken)                         // regular "-e" inflection
            for (String vorm : verbogenVormen(k))
                if (vorm.equalsIgnoreCase(geschreven)) return k;
        return null;
    }

    /** The regular attributive/nominalized "-e" form(s) of a bijvoeglijk-naamwoord base. */
    static List<String> verbogenVormen(String basis) {
        List<String> uit = new ArrayList<>();
        String b = basis.toLowerCase();
        if (b.isEmpty() || b.endsWith("e")) return uit;       // an -e base is already its inflected form
        uit.add(b + "e");                                     // klein→kleine, groen→groene, minderjarig→minderjarige
        // A long-vowel digraph in the final closed syllable shortens when "-e" opens the syllable:
        // …aa|ee|oo|uu + one consonant → single vowel + that consonant + e (groot→grote, duurzaam→duurzame).
        int n = b.length();
        if (n >= 3) {
            char last = b.charAt(n - 1);
            char v1 = b.charAt(n - 3), v2 = b.charAt(n - 2);
            boolean digraf = v1 == v2 && (v1 == 'a' || v1 == 'e' || v1 == 'o' || v1 == 'u');
            if (digraf && isMedeklinker(last))
                uit.add(b.substring(0, n - 3) + v1 + last + "e");
        }
        return uit;
    }

    private static boolean isMedeklinker(char c) {
        return c >= 'a' && c <= 'z' && "aeiou".indexOf(c) < 0;
    }
}
