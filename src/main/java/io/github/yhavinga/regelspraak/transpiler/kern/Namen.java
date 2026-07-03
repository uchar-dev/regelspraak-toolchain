package io.github.yhavinga.regelspraak.transpiler.kern;

/**
 * THE Dutch-phrase → Java/DRL identifier contract (the shared naming contract) — the one place a RegelSpraak name
 * becomes a bean property, getter/setter, fact class, parameter global, rule-name string, or
 * comment splice. Both back-ends import these; neither derives a name locally, so the DRL text and
 * the executable model can never disagree on what a written name maps to.
 */
public final class Namen {
    private Namen() {}

    /** A RegelSpraak name → a Java identifier: split on non-letter/digit runs, then camel-
     *  (upperFirst=false) or Pascal-case (true) the words. NO lidwoord stripping: the resolver already
     *  delivers declared names bare, and a kenmerk whose name deliberately CARRIES an article ("de duurste
     *  reis") must bind its full name, not collide with a shorter sibling. A word written ALL-CAPS (a
     *  shouted parameter, "STANDAARD TERMIJN") is title-cased FIRST — without that the camel form of the
     *  first word reads "sTANDAARD…". An empty result is "x". A camel result CAN equal a Java
     *  keyword ("final") — reconciling that is the fact-model generator's contract (the explicit fact-model contract), which must move
     *  BOTH back-ends at once; a one-sided suffix here would diverge from the runtime's pascal-based reads. */
    public static String ident(String naam, boolean upperFirst) {
        StringBuilder b = new StringBuilder();
        for (String d : naam.trim().split("[^\\p{L}\\p{N}]+")) {
            if (d.isEmpty()) continue;
            if (isSchreeuw(d)) d = d.charAt(0) + d.substring(1).toLowerCase();
            boolean hoofd = upperFirst || b.length() > 0;
            b.append(hoofd ? Character.toUpperCase(d.charAt(0)) : Character.toLowerCase(d.charAt(0)))
             .append(d.substring(1));
        }
        return b.isEmpty() ? "x" : b.toString();
    }

    /** An ALL-CAPS word of length > 1 (at least one letter, no lowercase letters). */
    private static boolean isSchreeuw(String w) {
        if (w.length() < 2) return false;
        boolean letter = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLowerCase(c)) return false;
            letter |= Character.isLetter(c);
        }
        return letter;
    }

    /** The JavaBean getter name for an attribute/kenmerk/rol ({@code get<Pascal>}; the boolean {@code is<X>}
     *  form is the FACT side's — mvel resolves the property either way). */
    public static String getter(String naam) { return "get" + ident(naam, true); }

    public static String setter(String naam) { return "set" + ident(naam, true); }

    /** A §9.3 regelnaam spliced inside DRL double quotes: {@code \} and {@code "} would end the string (or
     *  escape into it) and a newline ends the rule header — all four are substituted (disjoint characters,
     *  none introduces another, so order is immaterial). The SAME escaping guards the idempotency-constraint
     *  {@code regel == "…"} splice, so the two occurrences always match each other. */
    public static String escapeRegelNaam(String naam) {
        return naam.replace("\\", "/").replace("\"", "'").replace("\r", " ").replace("\n", " ");
    }

    /** User text (a {@code bron()} phrase) spliced into a {@code /* TODO … *&#47;} block comment or an
     *  end-of-line {@code // onopgelost …} comment: a {@code *&#47;} would END the block comment mid-splice
     *  (escaping by construction — the rest of the phrase then parses as DRL and sinks the package) and a newline would
     *  terminate a line comment, so both are defused. */
    public static String commentVeilig(String tekst) {
        return tekst.replace("*/", "* /").replace("\r", " ").replace("\n", " ");
    }

    /** A value spliced as a DRL/mvel STRING literal: backslashes and embedded double quotes are ESCAPED —
     *  never stripped (escaping by construction: an enum label with an embedded quote must compare against the real label,
     *  not a silently altered one). Returns the quoted literal including its delimiters. */
    public static String drlString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
