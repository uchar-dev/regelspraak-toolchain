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

    /** The declared attribute of {@code ot} the written (possibly plural) name denotes, or null. The
     *  singular exact match is preferred; otherwise the written form must be the declared "(mv: …)" OR
     *  the synthesised regular plural of exactly ONE attribute — if it is the plural (declared or
     *  synthesised) of more than one, it binds none (an honest marker, not an order-dependent guess). */
    static String vindAttribuut(Objecttype ot, String geschreven) {
        if (ot == null || geschreven == null) return null;
        for (String attr : ot.attributen.keySet())
            if (attr.equalsIgnoreCase(geschreven)) return attr;          // singular, exact — preferred
        String enige = null;
        for (String attr : ot.attributen.keySet()) {
            String mv = ot.attribuutMeervoud.get(attr);                  // declared (mv: …), §3.3 — spec-sanctioned
            boolean matcht = mv != null && mv.equalsIgnoreCase(geschreven);
            if (!matcht)                                                 // else the synthesised regular plural
                for (String vorm : meervoudsVormen(attr))
                    if (vorm.equalsIgnoreCase(geschreven)) { matcht = true; break; }
            if (matcht) {
                if (enige != null && !enige.equalsIgnoreCase(attr)) return null;  // ambiguous → marker
                enige = attr;
            }
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

    /** The regular Dutch noun plural form(s) of a singular — generated CONSERVATIVELY so a written form
     *  binds only when it is a confident plural of the declared name (else it stays an honest marker,
     *  never a wrong bind):
     *  <ul>
     *    <li>"-s" only for the unstressed -e/-el/-em/-en/-er endings and vowel stems (nummer→nummers,
     *        tafel→tafels) — NOT for an arbitrary stem, so "naam" does not yield the non-word "naams";</li>
     *    <li>"-en" otherwise, with the long-vowel-digraph shortening (naam→namen, persoon→personen) —
     *        but NOT for a short-vowel-plus-single-consonant stem, whose real plural doubles the
     *        consonant (man→mannen) and is left unmodelled: emitting the lossy "manen"/"bomen" would
     *        mis-bind, since those are the real plurals of "maan"/"boom".</li>
     *  </ul> */
    static List<String> meervoudsVormen(String enkelvoud) {
        List<String> uit = new ArrayList<>();
        String b = enkelvoud.toLowerCase();
        int n = b.length();
        if (n == 0) return uit;
        if (neemtS(b)) uit.add(b + "s");
        if (n >= 3) {
            char last = b.charAt(n - 1), v2 = b.charAt(n - 2), v1 = b.charAt(n - 3);
            boolean digraf = v1 == v2 && (v1 == 'a' || v1 == 'e' || v1 == 'o' || v1 == 'u');
            if (digraf && isMedeklinker(last)) {
                uit.add(b.substring(0, n - 3) + v1 + last + "en");   // naam→namen, persoon→personen
                return uit;
            }
            if (isKlinker(v2) && isMedeklinker(v1) && isMedeklinker(last))
                return uit;                                          // short V + single C: doubling class, unmodelled
        }
        uit.add(b + "en");                                           // vlucht→vluchten, vierkant→vierkanten
        return uit;
    }

    /** Does this stem take the "-s" plural? The unstressed -el/-em/-en/-er suffixes (a consonant before
     *  the suffix, so taf-el not the stressed -eel of "deel"), a final schwa -e, or a vowel stem. */
    private static boolean neemtS(String b) {
        int n = b.length();
        if (b.endsWith("e") || isKlinker(b.charAt(n - 1))) return true;
        if (n >= 3 && isMedeklinker(b.charAt(n - 3))) {
            String suf = b.substring(n - 2);
            return suf.equals("el") || suf.equals("em") || suf.equals("en") || suf.equals("er");
        }
        return false;
    }

    private static boolean isKlinker(char c) { return "aeiou".indexOf(c) >= 0; }

    private static boolean isMedeklinker(char c) {
        return c >= 'a' && c <= 'z' && "aeiou".indexOf(c) < 0;
    }
}
