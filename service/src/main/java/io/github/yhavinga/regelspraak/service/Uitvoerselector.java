package io.github.yhavinga.regelspraak.service;

/**
 * A parsed descriptor uitvoer selector: {@code "Objecttype.attribuut"} (an attribute read) or
 * {@code "Objecttype:kenmerk"} (a boolean kenmerk read). The two forms are distinguished by the SEPARATOR so
 * an attribuut whose own name contains a dot is never confused with a kenmerk: a {@code ':'} means kenmerk, a
 * {@code '.'} means attribuut. The objecttype part is everything before the FIRST separator; the lid part the
 * rest (an attribuut name may itself contain dots — e.g. a phrase — so we split on the first '.' only, and on
 * the first ':' only).
 *
 * @param objecttype the objecttype name
 * @param lid        the attribuut or kenmerk name
 * @param kenmerk    true when this is a {@code :kenmerk} selector, false for a {@code .attribuut} selector
 */
public record Uitvoerselector(String objecttype, String lid, boolean kenmerk) {

    /** Parse a selector, or null when it is neither an {@code Objecttype.attribuut} nor {@code Objecttype:kenmerk}. */
    public static Uitvoerselector ontleed(String sel) {
        if (sel == null) return null;
        int colon = sel.indexOf(':');
        int dot = sel.indexOf('.');
        // whichever separator comes FIRST decides the form; a ':' before any '.' is a kenmerk selector.
        if (colon >= 0 && (dot < 0 || colon < dot)) {
            String ot = sel.substring(0, colon).trim();
            String lid = sel.substring(colon + 1).trim();
            if (ot.isEmpty() || lid.isEmpty()) return null;
            return new Uitvoerselector(ot, lid, true);
        }
        if (dot >= 0) {
            String ot = sel.substring(0, dot).trim();
            String lid = sel.substring(dot + 1).trim();
            if (ot.isEmpty() || lid.isEmpty()) return null;
            return new Uitvoerselector(ot, lid, false);
        }
        return null;
    }
}
