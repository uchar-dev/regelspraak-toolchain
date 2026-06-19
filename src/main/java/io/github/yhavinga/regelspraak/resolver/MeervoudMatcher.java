package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Binds a written PLURAL noun to a declared singular — needed by the §8.1.6 uniciteitscontrole, whose
 *  onderwerpexpressie names the attribute and the object type in the plural ("de burgerservicenummers
 *  van alle Vluchten moeten uniek zijn") while both are declared in the singular.
 *
 *  <p>Like {@link KenmerkMatcher} it tries exact (case-insensitive) first and is <b>ambiguity-safe</b>
 *  (a written plural that is the regular plural of more than one declared name binds none — an honest
 *  no-op). For attribute and single-word object-type names it then tries the regular Dutch noun plural
 *  ({@link #meervoudsVormen}). An object type may also declare its plural verbatim ("(mv: …)",
 *  §13.2.29), which is matched exactly — the only path that resolves an irregular or multi-word plural
 *  ("Natuurlijke personen"), since this matcher does not guess multi-word adjective+noun morphology.
 *
 *  <p>Only the REGULAR plural is generated: "-s" and "-en", with the long-vowel-digraph shortening that
 *  opens the final syllable (naam→namen, persoon→personen). Stress-dependent consonant doubling
 *  (bus→bussen) and the "-eren" plural (kind→kinderen) are NOT modelled — a name needing them stays a
 *  marker rather than risk a wrong bind. Candidates are generated from the controlled declared singular
 *  and tested against the written form, so an over-generated non-word ("vluchts") cannot mis-bind. */
final class MeervoudMatcher {
    private MeervoudMatcher() {}

    /** The declared attribute of {@code ot} the written (possibly plural) name denotes, or null. */
    static String vindAttribuut(Objecttype ot, String geschreven) {
        if (ot == null || geschreven == null) return null;
        for (String attr : ot.attributen.keySet())
            if (attr.equalsIgnoreCase(geschreven)) return attr;          // singular, exact — preferred
        String enige = null;
        for (String attr : ot.attributen.keySet())
            for (String mv : meervoudsVormen(attr))
                if (mv.equalsIgnoreCase(geschreven)) {
                    if (enige != null && !enige.equalsIgnoreCase(attr)) return null;  // ambiguous → marker
                    enige = attr;
                }
        return enige;
    }

    /** The declared object type the written (possibly plural) name denotes, or null — by exact name,
     *  declared meervoudsvorm, or regular single-word plural (ambiguity-safe). */
    static Objecttype vindObjecttype(Collection<Objecttype> typen, String geschreven) {
        if (geschreven == null) return null;
        for (Objecttype ot : typen)
            if (ot.naam.equalsIgnoreCase(geschreven)) return ot;         // singular name, exact
        for (Objecttype ot : typen)
            if (ot.meervoud != null && ot.meervoud.equalsIgnoreCase(geschreven)) return ot;  // declared plural
        Objecttype enige = null;
        for (Objecttype ot : typen)
            for (String mv : meervoudsVormen(ot.naam))
                if (mv.equalsIgnoreCase(geschreven)) {
                    if (enige != null && enige != ot) return null;       // ambiguous → marker
                    enige = ot;
                }
        return enige;
    }

    /** The regular Dutch noun plural form(s) of a singular: "-s" and "-en", the latter shortened on a
     *  long-vowel-digraph-plus-one-consonant stem (naam→namen, persoon→personen). */
    static List<String> meervoudsVormen(String enkelvoud) {
        List<String> uit = new ArrayList<>();
        String b = enkelvoud.toLowerCase();
        if (b.isEmpty()) return uit;
        uit.add(b + "s");                                     // nummer→nummers, tafel→tafels (else a harmless non-word)
        int n = b.length();
        if (n >= 3) {
            char last = b.charAt(n - 1);
            char v1 = b.charAt(n - 3), v2 = b.charAt(n - 2);
            boolean digraf = v1 == v2 && (v1 == 'a' || v1 == 'e' || v1 == 'o' || v1 == 'u');
            if (digraf && isMedeklinker(last)) {
                uit.add(b.substring(0, n - 3) + v1 + last + "en");   // naam→namen, persoon→personen
                return uit;
            }
        }
        uit.add(b + "en");                                    // vlucht→vluchten, vierkant→vierkanten
        return uit;
    }

    private static boolean isMedeklinker(char c) {
        return c >= 'a' && c <= 'z' && "aeiou".indexOf(c) < 0;
    }
}
