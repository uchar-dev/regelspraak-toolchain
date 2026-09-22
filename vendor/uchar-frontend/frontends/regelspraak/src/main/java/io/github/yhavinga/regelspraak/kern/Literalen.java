package io.github.yhavinga.regelspraak.kern;

import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Rationaal;

import java.math.BigDecimal;

/**
 * The single literal-to-typed-value reader both back-ends share (grown from the §9.8 dagsoort
 * compiler outward). A literal reaches the transpilers as its wire lexeme ({@code waarde}) plus a
 * resolved {@link io.github.yhavinga.regelspraak.model.Datatype}; the conversions here are the one place that
 * lexeme is interpreted, so the two back-ends can never drift on what a literal MEANS. The dates read the
 * WIRE lexeme only — the frontend reads the RegelSpraak source snippet with a reader of its own.
 */
public final class Literalen {
    private Literalen() {}

    /* Both lexemes are matched WHOLE (never {@code find()}): a substring match is how a reader ends up
     * answering a document it did not understand — a trailing "Z" or "+01:00" dropped, a fourth fraction
     * digit cut off, surrounding text ignored, 2024-01-2026 read as 2024-01-20. */
    private static final java.util.regex.Pattern ISO_DATUM = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final java.util.regex.Pattern ISO_DATUM_TIJD = java.util.regex.Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{3}))?");

    /** Parse a date literal's lexeme to a LocalDate: the strict ISO {@code yyyy-mm-dd} form of WF-084 — fixed
     *  field widths, matched whole, calendar-valid — and nothing else. null for any other value, which both
     *  branches report through {@link #onleesbaarDatumLexeem}.
     *  <p>This reader used to SEARCH for {@code \d{4}-\d{1,2}-\d{1,2}} and fall back to a day-first
     *  {@code d-m-yyyy} arm, so {@code 2024-01-2026} was silently 2024-01-20 on both branches, and the source
     *  snippet {@code 31-01-2024} that WF-084 refuses was read as well (cards {@code datum-lexeem-hele-match},
     *  {@code datumtijd-lezers-versmalling}, 2026-09-23). */
    public static java.time.LocalDate parseDatum(String waarde) {
        java.util.regex.Matcher iso = ISO_DATUM.matcher(waarde);
        if (!iso.matches()) return null;
        try { return java.time.LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3))); }
        catch (java.time.DateTimeException e) { return null; }
    }

    /** Parse a datum-tijd literal to a LocalDateTime. One lexeme carries, read WHOLE: the canonical uchar IR
     *  lexeme — ISO {@code yyyy-mm-ddThh:mm:ss[.SSS]}, fixed field widths, {@code T} (never a space), the seconds
     *  field always written, NO zone suffix or offset, a fraction that is optional but exactly three digits
     *  (IR-SPEC ch. 4.1, WF-095). Anything else is null — since 2026-09-23 the §13.2.21 RegelSpraak source
     *  snippet ({@code "[dd. ]d-m-yyyy hh:mm:ss[.SSS]"}) too, which this reader read as a second lexeme while
     *  the emitter still wrote it into {@code waarde} (card {@code datumtijd-lezers-versmalling}).
     *  <p>The fence is the point. This reader used to scan for a date and a time ANYWHERE in the value, so
     *  "…T12:00:00Z", "…T12:00:00+01:00", "2024-01-31 12:00:00", one-digit fields and "…:00.1234" (truncated to
     *  123 ms) were all silently accepted as some instant the writer had not written — a wrong answer with no
     *  diagnostic. A null is never inert here: the exec-model branch drops the whole rule and the DRL branch
     *  markers it, both naming the unreadable lexeme, which is a refusal a producer can act on.
     *  The captured milliseconds scale ×1e6 to nanoOfSecond; an impossible field combination (month 13, 30
     *  February, hour 24) is rejected by the calendar below. */
    public static java.time.LocalDateTime parseDatumTijd(String waarde) {
        java.util.regex.Matcher m = ISO_DATUM_TIJD.matcher(waarde);
        if (!m.matches()) return null;
        try {
            int nanos = (m.group(7) == null ? 0 : Integer.parseInt(m.group(7))) * 1_000_000;
            return java.time.LocalDateTime.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), nanos);
        } catch (java.time.DateTimeException e) { return null; }
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

    /** The day-precision sibling of {@link #onleesbaarDatumTijdLexeem}: a date literal whose lexeme
     *  {@link #parseDatum} cannot read. Without it a refused date lexeme was filed under the generic
     *  "outside scope" reason on one branch and "voorwaarde" on the other, while the cause is a document defect. */
    public static boolean onleesbaarDatumLexeem(Expr e) {
        return e instanceof Expr.Literal l && l.type() instanceof io.github.yhavinga.regelspraak.model.Datatype.DatumInDagen
                && parseDatum(l.waarde()) == null;
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
     *  <p>An {@code Enumeratie}-typed literal's value is likewise returned VERBATIM (card
     *  `drools-enumlabel-lezer`, 2026-09-20), for the same reason the Tekst arm is: on this wire an
     *  enumeration literal's value IS the bare label, which WF-039 crosses byte-exactly against the declared
     *  domain, so there are no delimiters left to strip. Until this arm existed the enumeration case routed
     *  through {@link #enumLabel}, which DEMANDS the {@code '…'} pair that IR-SPEC ch. 4.1 used to describe
     *  as the wire form — a sentence about a FRONTEND reader, corrected 2026-09-19. On a conforming document
     *  that reader answered null, so every Drools transpiler arm guarding on
     *  {@code tekstOfEnumLabel(lit) != null} — the own-attribute and navigated condition arms, the assertie
     *  arm and the VALUE arm — refused every enumeration equality AND every enumeration write.
     *  <p>The {@link #enumLabel} fallback below stays for the other datatypes: a NON-enumeration literal that
     *  carries a single-quoted lexeme is a shape no emitter writes and no corpus document holds, but reading
     *  it is what this reader did before an enumeration literal could be typed at all, and narrowing it here
     *  would be a second change in the same line with no witness of its own.
     *  <p>What a back-end may do with this string is RENDER it: the DRL branch escapes it into DRL text
     *  ({@code Namen.drlString}). Escaping is not interpretation — it leaves the value itself untouched. */
    public static String tekstOfEnumLabel(Expr.Literal lit) {
        if (lit.type().isTekst()) return lit.waarde();
        if (lit.type() instanceof io.github.yhavinga.regelspraak.model.Datatype.Enumeratie) return lit.waarde();
        return enumLabel(lit.waarde());
    }

    /** A §13.2.x boolean literal: "waar" is true, "onwaar" false. */
    public static boolean waar(String waarde) {
        String l = waarde.toLowerCase();
        return l.contains("waar") && !l.contains("onwaar");
    }
}
