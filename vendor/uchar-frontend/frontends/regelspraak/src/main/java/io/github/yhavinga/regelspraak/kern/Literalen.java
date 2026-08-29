package io.github.yhavinga.regelspraak.kern;

import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Rationaal;

import java.math.BigDecimal;

/**
 * The single literal-to-typed-value reader both back-ends share (grown from the §9.8 dagsoort
 * compiler outward). A RegelSpraak literal reaches the transpilers as its source text plus a
 * resolved {@link io.github.yhavinga.regelspraak.model.Datatype}; the conversions here are the one place that
 * text is interpreted, so the two back-ends can never drift on what a literal MEANS.
 */
public final class Literalen {
    private Literalen() {}

    private static final java.util.regex.Pattern ISO_DATUM = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final java.util.regex.Pattern DATUM = java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})-(\\d{4})");
    private static final java.util.regex.Pattern TIJD  = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?");

    /** Parse a date literal to a LocalDate. Accepts both the canonical uchar IR lexeme — the strict
     *  ISO {@code yyyy-mm-dd} form (WF-084) the emitter now writes into {@code waarde} and the back-ends
     *  read — and the §13.2.19 RegelSpraak source snippet ({@code "dd. d-m-yyyy"}, Dutch day-first) the
     *  emitter canonicalises. The year's field position (four digits, leading in ISO, trailing in the
     *  snippet) disambiguates, so this stays the one place a date's text is interpreted. null when absent. */
    public static java.time.LocalDate parseDatum(String waarde) {
        java.util.regex.Matcher iso = ISO_DATUM.matcher(waarde);
        if (iso.find()) {
            try { return java.time.LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3))); }
            catch (RuntimeException e) { return null; }
        }
        java.util.regex.Matcher d = DATUM.matcher(waarde);
        if (!d.find()) return null;
        try { return java.time.LocalDate.of(Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1))); }
        catch (RuntimeException e) { return null; }
    }

    /** Parse a §13.2.21 datum-tijd literal ("dd-m-yyyy hh:mm:ss[.SSS]") to a LocalDateTime — the captured
     *  millisecondes scale ×1e6 to nanoOfSecond. null when the date or time part is absent. */
    public static java.time.LocalDateTime parseDatumTijd(String waarde) {
        java.util.regex.Matcher d = DATUM.matcher(waarde);
        if (!d.find()) return null;
        java.util.regex.Matcher tm = TIJD.matcher(waarde);
        if (!tm.find()) return null;
        try {
            int nanos = (tm.group(4) == null ? 0 : Integer.parseInt(tm.group(4))) * 1_000_000;
            return java.time.LocalDateTime.of(Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1)),
                    Integer.parseInt(tm.group(1)), Integer.parseInt(tm.group(2)), Integer.parseInt(tm.group(3)), nanos);
        } catch (RuntimeException e) { return null; }
    }

    /** A NUMBER literal (incl. a §13.2.16 rationaal "1/3") as BigDecimal via the shared {@link Rationaal}
     *  reader: exact when the quotient terminates, DECIMAL64 otherwise. null for a non-numeric literal. */
    public static BigDecimal getal(Expr.Literal lit) {
        BigDecimal[] r = Rationaal.van(lit.waarde());
        if (r == null) return null;
        try { return r[0].divide(r[1]); }                                   // exact (terminating quotient)
        catch (ArithmeticException nietEindig) { return r[0].divide(r[1], java.math.MathContext.DECIMAL64); }
    }

    /** The §13.2.14 enumeratiewaarde label of {@code 'X'} — its single-quote delimiters (straight or the
     *  typographic ’X‘ pair) stripped, EMBEDDED characters (incl. double quotes) kept verbatim (escaping by construction).
     *  null when the text is not a single-quoted enumeratiewaarde. */
    public static String enumLabel(String waarde) {
        int n = waarde.length();
        if (n >= 2 && waarde.charAt(0) == '\'' && waarde.charAt(n - 1) == '\'') return waarde.substring(1, n - 1);
        if (n >= 2 && waarde.charAt(0) == '’' && waarde.charAt(n - 1) == '‘') return waarde.substring(1, n - 1);
        return null;
    }

    /** The string label a Tekst literal or an enumeratiewaarde compares by (§13.3.3.4 / §13.2.14 — both are
     *  VALUE equality on the label): a Tekst's double-quote DELIMITERS are stripped (the grammar's STRING_TEXT
     *  cannot contain a quote, so only the delimiters can occur); an enumeratiewaarde via {@link #enumLabel}.
     *  null for any other literal. */
    public static String tekstOfEnumLabel(Expr.Literal lit) {
        if (lit.type().isTekst()) return lit.waarde().replace("\"", "");
        return enumLabel(lit.waarde());
    }

    /** A §13.2.x boolean literal: "waar" is true, "onwaar" false. */
    public static boolean waar(String waarde) {
        String l = waarde.toLowerCase();
        return l.contains("waar") && !l.contains("onwaar");
    }
}
