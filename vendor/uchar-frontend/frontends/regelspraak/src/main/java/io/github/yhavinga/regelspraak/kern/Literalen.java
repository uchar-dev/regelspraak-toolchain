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

    /* The two datum-tijd lexemes, each matched WHOLE (never {@code find()}): a substring match is how a
     * reader ends up answering a document it did not understand — a trailing "Z" or "+01:00" dropped, a
     * fourth fraction digit cut off, surrounding text ignored. Both arms number their groups alike from
     * the month on (2=mm, 4=hh, 5=mm, 6=ss, 7=fff); only the year/day positions swap (ISO 1/3, snippet 3/1). */
    private static final java.util.regex.Pattern ISO_DATUM_TIJD = java.util.regex.Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{3}))?");
    private static final java.util.regex.Pattern SNIPPET_DATUM_TIJD = java.util.regex.Pattern.compile(
            "(?:dd\\. +)?(\\d{1,2})-(\\d{1,2})-(\\d{4}) +(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{3}))?");

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

    /** Parse a datum-tijd literal to a LocalDateTime. Two lexemes carry, each read WHOLE: the canonical uchar IR
     *  lexeme — ISO {@code yyyy-mm-ddThh:mm:ss[.SSS]}, fixed field widths, {@code T} (never a space), the seconds
     *  field always written, NO zone suffix or offset, a fraction that is optional but exactly three digits
     *  (IR-SPEC ch. 4.1) — and the §13.2.21 RegelSpraak source snippet ({@code "[dd. ]d-m-yyyy hh:mm:ss[.SSS]"},
     *  day-first, the token's variable-width day/month kept so this reader is never stricter than the producer
     *  that feeds it). Anything else is null.
     *  <p>The fence is the point. This reader used to scan for a date and a time ANYWHERE in the value, so
     *  "…T12:00:00Z", "…T12:00:00+01:00", "2024-01-31 12:00:00", one-digit fields and "…:00.1234" (truncated to
     *  123 ms) were all silently accepted as some instant the writer had not written — a wrong answer with no
     *  diagnostic. A null is never inert here: the exec-model branch drops the whole rule and the DRL branch
     *  markers it, both naming the unreadable lexeme, which is a refusal a producer can act on.
     *  The captured milliseconds scale ×1e6 to nanoOfSecond; an impossible field combination (month 13, 30
     *  February, hour 24) is rejected by the calendar below. */
    public static java.time.LocalDateTime parseDatumTijd(String waarde) {
        java.util.regex.Matcher m = ISO_DATUM_TIJD.matcher(waarde);
        boolean iso = m.matches();
        if (!iso) {
            m = SNIPPET_DATUM_TIJD.matcher(waarde);
            if (!m.matches()) return null;
        }
        try {
            int nanos = (m.group(7) == null ? 0 : Integer.parseInt(m.group(7))) * 1_000_000;
            return java.time.LocalDateTime.of(
                    Integer.parseInt(m.group(iso ? 1 : 3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(iso ? 3 : 1)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), nanos);
        } catch (RuntimeException e) { return null; }
    }

    /** Whether {@code e} is a datum-tijd literal whose lexeme {@link #parseDatumTijd} cannot read. Both back-ends
     *  ask this to say WHY they dropped a construct: an unreadable LEXEME is a document defect the producer can
     *  fix, not a construct the back-end does not carry, and the generic "outside scope" reason hid the difference
     *  (it once passed for a whole turn as "the back-end cannot do milliseconds"). One predicate, so the two
     *  branches can never disagree about which lexemes are readable. */
    public static boolean onleesbaarDatumTijdLexeem(Expr e) {
        return e instanceof Expr.Literal l && l.type() instanceof io.github.yhavinga.regelspraak.model.Datatype.DatumTijd
                && parseDatumTijd(l.waarde()) == null;
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

    /** The string a Tekst literal or an enumeratiewaarde compares by (§13.3.3.4 / §13.2.14 — both are VALUE
     *  equality). A Tekst literal's {@code waarde} IS that string, returned VERBATIM: since the 2026-09-18 canonicalisation IR-SPEC ch. 4.1 puts the
     *  PARSED string in that field (the emitter strips only the outer delimiters; a hand-authored value may
     *  carry any text, embedded quotes included), which is precisely what the reference interpreter reads into its text
     *  value and what a case input's {@code {"text": …}} carries. Any character removed here would compare one
     *  side of an equality against a string no other executor ever sees, so the two lexeme families that reach
     *  this reader both survive whole: a text with an EMBEDDED double quote ({@code merk "AA"}, which a
     *  hand-authored wire may carry and the grammar's quote-free STRING_TEXT never produces), and a wire whose
     *  value carries its own OUTER double quotes. A blanket {@code replace("\"","")} broke the first by dropping
     *  the embedded quote and the second by dropping the delimiters the other side kept — opposite directions,
     *  one cause. null for any other literal.
     *  <p><b>The enumeratiewaarde arm is STALE and refuses more than it should</b> (measured 2026-09-20, card
     *  `20260919-v8-enum-emitter`). It routes through {@link #enumLabel}, which DEMANDS the {@code '…'}
     *  delimiters — the shape IR-SPEC ch. 4.1 used to describe as the wire form. That sentence was wrong and
     *  is corrected: on this wire an enumeration literal's value is the BARE label, which WF-039 crosses
     *  byte-exactly against the declared domain. So on every conforming document this arm returns null, and
     *  the Drools transpiler arms that guard on {@code tekstOfEnumLabel(lit) != null} — the two condition
     *  arms, the navigated one and the VALUE arm — refuse every enumeration equality and every enumeration
     *  write. That is why `jutterswet-keurstaat` and `jutterswet-vondstklasse` sit pinned as EXCLUDE in both
     *  channel lists of gate 12. The remedy is here, not at the call sites: return an
     *  {@code Enumeratie}-typed literal's value VERBATIM, exactly as the Tekst arm does. Held for card
     *  `drools-enumlabel-lezer`, because it shifts gate-12's played counts and those move serially.
     *  <p>What a back-end may do with this string is RENDER it: the DRL branch escapes it into DRL text
     *  ({@code Namen.drlString}). Escaping is not interpretation — it leaves the value itself untouched. */
    public static String tekstOfEnumLabel(Expr.Literal lit) {
        if (lit.type().isTekst()) return lit.waarde();
        return enumLabel(lit.waarde());
    }

    /** A §13.2.x boolean literal: "waar" is true, "onwaar" false. */
    public static boolean waar(String waarde) {
        String l = waarde.toLowerCase();
        return l.contains("waar") && !l.contains("onwaar");
    }
}
