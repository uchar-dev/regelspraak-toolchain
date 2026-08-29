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
        if (e == null) return null;
        return switch (RegelSpraakModel.normaliseerTijdseenheid(e.afkorting())) {
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

    /** §8.4.3: whether a periodevergelijking bound can be lowered — a §13.2.19 Datum-in-dagen LITERAL or the
     *  subject's OWN scalar (non-temporal, non-dimensioned) Datum-in-dagen attribute. Everything else — a
     *  parameter/navigated/computed bound, a Datum-en-tijd (the §4.2 Rekendatum is day-granular) — stays an
     *  honest marker/skip, SHARED by both back-ends so they fire/skip the same shapes. */
    public static boolean periodeGrensLowerbaar(Expr grens, Objecttype subject) {
        if (!(grens.type() instanceof Datatype.DatumInDagen)) return false;
        if (grens instanceof Expr.Literal) return true;
        return grens instanceof Expr.AttribuutRef a && a.navigatieRol() == null && !a.set()
                && a.objecttype() != null && a.objecttype() == subject
                && !subject.attribuutGranulariteit.containsKey(a.attribuut())
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
