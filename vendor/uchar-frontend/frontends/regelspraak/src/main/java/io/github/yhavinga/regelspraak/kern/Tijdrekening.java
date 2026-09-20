package io.github.yhavinga.regelspraak.kern;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

/**
 * THE time-unit mapping both back-ends share (single shared implementation): a declared tijdseenheid → the
 * {@link java.time.temporal.ChronoUnit} name date arithmetic keys on, and the §8.2 toets-codes
 * {@code rsGeheelPeriode} evaluates.
 */
public final class Tijdrekening {
    private Tijdrekening() {}

    /** A duration's declared unit (its result {@link Datatype}'s eenheid, e.g. an abbreviated "jr" or a bare "jaar")
     *  → a ChronoUnit name for {@code rsDatumReken}/{@code rsTijdsduur}. Normalised through the shared
     *  plural→singular reducer so "jaren"/"jaar"/"jr" all land on YEARS. §3.7 a kwartaal is 3 maanden and
     *  java.time has no ChronoUnit.QUARTERS, so it maps to the pseudo-unit "KWARTAAL" the runtime
     *  special-cases as 3×MONTHS. null for a dagsoort or an unrecognised unit — the caller markers/skips. */
    public static String chronoUnit(Datatype t) {
        return chronoUnit(t.eenheidOf());
    }

    /** As {@link #chronoUnit(Datatype)}, keyed directly on an {@link Eenheid} ({@code null}-safe). */
    public static String chronoUnit(Eenheid e) {
        return e == null ? null : chronoUnitVanSpelling(e.afkorting());
    }

    /** Whether {@code spelling} stands in the NORMATIVE calendar table (uchar's RUNTIME-SEMANTICS ch. 3.8,
     *  thirty spellings over nine units). That table is what decides which of the two readings a duration
     *  unit has — "a spelling outside the table is a declared day kind" — so the day-kind registration guard
     *  must compare against exactly it and against no list of its own, or the producer admits a spelling the
     *  IR then refuses (WF-017). Trims and lower-cases nothing: the caller passes the identity-normalised
     *  spelling, as the registry lookup does. */
    public static boolean isKalenderSpelling(String spelling) {
        return chronoUnitVanSpelling(spelling) != null;
    }

    /** The table keyed directly on a SPELLING — the shared body of the two entries above. A null spelling is
     *  no unit at all and answers null, as the interpreter's own table does: an {@code Eenheid} on this wire
     *  may carry a null afkorting, and the plural reducer hands a null straight back through its default arm,
     *  so without this guard the switch below would throw on a document the IR admits. */
    private static String chronoUnitVanSpelling(String spelling) {
        if (spelling == null) return null;
        return switch (RegelSpraakModel.normaliseerTijdseenheid(spelling)) {
            case "jaar", "jr"             -> "YEARS";
            case "maand", "mnd"           -> "MONTHS";
            case "week", "wk"             -> "WEEKS";
            case "dag", "dg"              -> "DAYS";     // §3.7 declares the dag afkorting "dg"
            case "uur", "u"               -> "HOURS";
            case "minuut", "min"          -> "MINUTES";
            case "seconde", "secondes", "sec", "s"     -> "SECONDS";
            case "milliseconde", "millisecondes", "ms" -> "MILLIS";   // §6.10 spells the plural with -s
            case "kwartaal", "kwartalen", "kw" -> "KWARTAAL";         // §3.7 declares the kwartaal afkorting "kw"
            default -> null;   // the -en reducer leaves unchanged; dagsoort / custom -> marker
        };
    }

    /** The §6.10/§6.11 duration unit read as a DAY KIND: the dagsoort {@code e}'s spelling names, or null when
     *  it names none — and null, too, whenever the spelling stands in the calendar table, WHATEVER the day-kind
     *  registry says about it.
     *
     *  <p>That order is the point of this method, and it is normative: RUNTIME-SEMANTICS ch. 3.8 recognises a
     *  duration's unit by spelling through the table and settles the remainder with "a spelling outside the
     *  table is a declared day kind" — outside, so the table goes first. The verifier (WF-035, WF-038), the
     *  reference interpreter and the ASP backend have always read it that way; both Drools branches asked the
     *  registry first, at all four of their day-kind positions, until 2026-09-20. On a document that registered
     *  a calendar spelling as a day-kind unit the executors therefore SPLIT — three read calendar arithmetic
     *  where these two read a day-kind walk — and that document was verifier-clean at the time. Measured on
     *  {@code jutterswet-bergingsklok} with its unit spelled {@code wk} and {@code wk} registered beside it:
     *  {@code ref} answered 2026-03-31 / 2026-02-17 / 5 / 5 where both branches here answered the day-kind
     *  2026-04-15 / 2026-02-01 / 3 / 3. WF-017 refuses such a document since the same day, so the wire no
     *  longer carries the contradiction; this method is why an executor could not act on it either way.
     *
     *  <p>The four callers keep their own fall-through to {@link #chronoUnit}: a spelling that is neither a day
     *  kind nor a table entry is still an honest marker/skip there. */
    public static String dagsoortVoorEenheid(RegelSpraakModel model, Eenheid e) {
        if (e == null || chronoUnit(e) != null) return null;
        return model.dagsoortVoorEenheid(e.afkorting());
    }

    /** §8.4.3: whether a periodevergelijking bound can be lowered — a §13.2.19 Datum-in-dagen LITERAL or the
     *  subject's OWN scalar (non-temporal, non-dimensioned) Datum-in-dagen attribute. Everything else — a
     *  parameter/navigated/computed bound, a Datum-en-tijd (the §4.2 Rekendatum is day-granular) — stays an
     *  honest marker/skip, SHARED by both back-ends so they fire/skip the same shapes. */
    public static boolean periodeGrensLowerbaar(Expr grens, Objecttype subject) {
        if (!(grens.type() instanceof Datatype.DatumInDagen)) return false;
        if (grens instanceof Expr.Literal) return true;
        return grens instanceof Expr.AttribuutRef a && a.navigatieRol() == null && !a.set()
                && a.objecttype() != null && a.objecttype() == subject
                && subject.granulariteitVan(a.attribuut()) == null
                && subject.dimensiesVan(a.attribuut()).isEmpty();
    }

    /** The §8.2 {@code rsGeheelPeriode}/{@code rsTijdConditie} toets code for a comparison operator
     *  (0..5 = KLEINER..ONGELIJK), or null for a DATUM ordering (EERDER/LATER) — the runtime Segment
     *  carries a BigDecimal waarde, so a date-valued whole-period comparison is out of scope. */
    public static Integer toets(Conditie.Operator op) {
        return switch (op) {
            case KLEINER -> 0; case KLEINER_OF_GELIJK -> 1;
            case GROTER  -> 2; case GROTER_OF_GELIJK  -> 3;
            case GELIJK  -> 4; case ONGELIJK          -> 5;
            default -> null;
        };
    }
}
