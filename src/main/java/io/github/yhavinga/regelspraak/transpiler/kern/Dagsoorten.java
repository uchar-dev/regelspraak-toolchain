package io.github.yhavinga.regelspraak.transpiler.kern;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedDagsoort;
import io.github.yhavinga.regelspraak.kern.Literalen;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * THE §9.8 dagsoortdefinitie compiler — the one implementation both back-ends share (single shared implementation).
 * A definitie's condition over "de dag" compiles to a {@code Predicate<LocalDate>}: the exec model
 * captures it directly in its lambdas, and the DRL back-end declares it as a session {@code global}
 * ({@code rsDagsoort_<Naam>}) that the session builder supplies from {@link #globalen()} — so the
 * predicate SEMANTICS exist exactly once, and the generated DRL carries no {@code function} bodies.
 *
 * <p>Compilation is idempotent and cycle-safe: a nested dagsoort reference (a feestdag tests
 * kerstdag/paasdag) resolves via a DEFERRED map lookup, so cross-references work regardless of
 * compilation order and a (spec-forbidden, §9.10) cyclic definitie cannot infinite-loop the compile.
 *
 * <p>The admitted condition shapes are deliberately narrow and identical for both back-ends — a
 * Vergelijking of same-comparable-type date operands, a (negated) dagsoort membership, a
 * Samengesteld with any §13.4.14 kwantor — over the §9.8-realistic operands: "de dag" itself, a
 * date-component of it (§13.4.17.18-.24, via {@link RegelSpraakRuntime#rsDatumDeel}), "de eerste
 * paasdag van" (§13.4.17.37), or a fact-free literal. Anything else returns null and the calling
 * back-end markers/skips honestly, so the per-date predicate never diverges between back-ends.
 */
public final class Dagsoorten {
    private final RegelSpraakModel model;
    private final Map<String, Predicate<LocalDate>> predikaten = new LinkedHashMap<>();
    private final java.util.Set<String> bezig = new java.util.HashSet<>();

    public Dagsoorten(RegelSpraakModel model) { this.model = model; }

    /** Compile (idempotently, cycle-safe) the §9.8 definitie of {@code naam}; null when no such definitie
     *  exists or its condition is not a pure date predicate (the caller then markers/skips). */
    public Predicate<LocalDate> predikaat(String naam) {
        String key = naam.toLowerCase();
        Predicate<LocalDate> have = predikaten.get(key);
        if (have != null) return have;
        if (bezig.contains(key))   // cyclic reference: defer to the eventual map entry
            return d -> { var p = predikaten.get(key); return p != null && p.test(d); };
        ResolvedDagsoort def = null;
        for (var d : model.resolvedDagsoorten) if (d.naam().equalsIgnoreCase(naam)) { def = d; break; }
        if (def == null) return null;
        bezig.add(key);
        Predicate<LocalDate> body = bool(def.voorwaarde());
        bezig.remove(key);
        if (body != null) predikaten.put(key, body);
        return body;
    }

    /** Lower a §9.8 condition-over-the-day; null when any leaf is not a pure date predicate. */
    private Predicate<LocalDate> bool(Conditie c) {
        if (c instanceof Conditie.Vergelijking v) {
            // a type-mismatched comparison (datum vs numeric date-component) can never compare via
            // Comparable.compareTo -> null, so both back-ends marker it symmetrically.
            boolean compat = (v.links().type().isNumeriek() && v.rechts().type().isNumeriek())
                    || (v.links().type().isDatum() && v.rechts().type().isDatum());
            if (!compat) return null;
            Function<LocalDate, Object> lo = waarde(v.links()), ro = waarde(v.rechts());
            if (lo == null || ro == null) return null;
            Conditie.Operator op = v.op();
            return d -> vergelijk(lo.apply(d), ro.apply(d), op);
        }
        if (c instanceof Conditie.Predicaat p && p.soort() == Conditie.Predicaatsoort.DAGSOORT) {
            Function<LocalDate, Object> o = waarde(p.operand());
            Predicate<LocalDate> inner = predikaat(p.dagsoort());
            if (o == null || inner == null) return null;
            boolean neg = p.negatie();   // §8.1.5 + Tabel 16 "(de dag) is geen <dagsoort>" inside a §9.8 definitie
            return d -> neg != (o.apply(d) instanceof LocalDate ld && inner.test(ld));
        }
        if (c instanceof Conditie.Samengesteld s) {
            List<Predicate<LocalDate>> delen = new ArrayList<>();
            for (Conditie deel : s.onderdelen()) { var d = bool(deel); if (d == null) return null; delen.add(d); }
            if (delen.isEmpty()) return null;
            int n = s.drempel();
            return switch (s.kwantor()) {
                case ALLE, DE    -> d -> delen.stream().allMatch(q -> q.test(d));
                case GEEN        -> d -> delen.stream().noneMatch(q -> q.test(d));
                case TEN_MINSTE  -> d -> delen.stream().filter(q -> q.test(d)).count() >= n;
                case TEN_HOOGSTE -> d -> delen.stream().filter(q -> q.test(d)).count() <= n;
                case PRECIES     -> d -> delen.stream().filter(q -> q.test(d)).count() == n;
            };
        }
        return null;   // any other condition shape is not a pure date predicate
    }

    /** A §9.8 operand over "de dag" as a {@code Function<LocalDate,Object>} (the day itself, a date-component
     *  of it via rsDatumDeel, "de eerste paasdag van", or a fact-free literal); null when not a pure date operand. */
    private Function<LocalDate, Object> waarde(Expr e) {
        if (e instanceof Expr.Constante c && "de dag".equalsIgnoreCase(c.naam())) return d -> d;
        if (e instanceof Expr.Functie f && f.argumenten().size() == 1) {
            java.time.temporal.ChronoField cf = switch (f.kind()) {
                case JAAR_UIT         -> java.time.temporal.ChronoField.YEAR;
                case MAAND_UIT        -> java.time.temporal.ChronoField.MONTH_OF_YEAR;
                case DAG_UIT          -> java.time.temporal.ChronoField.DAY_OF_MONTH;
                case UUR_UIT          -> java.time.temporal.ChronoField.HOUR_OF_DAY;
                case MINUUT_UIT       -> java.time.temporal.ChronoField.MINUTE_OF_HOUR;
                case SECONDE_UIT      -> java.time.temporal.ChronoField.SECOND_OF_MINUTE;
                case MILLISECONDE_UIT -> java.time.temporal.ChronoField.MILLI_OF_SECOND;
                default               -> null;
            };
            Function<LocalDate, Object> inner = waarde(f.argumenten().get(0));
            if (inner == null) return null;
            if (cf != null) return d -> RegelSpraakRuntime.rsDatumDeel(inner.apply(d), cf);
            // §13.4.17.37 "de eerste paasdag van <jaar>" — the paasdag/feestdag building block.
            if (f.kind() == Expr.FunctieKind.EERSTE_PAASDAG) return d -> RegelSpraakRuntime.rsEerstePaasdag(inner.apply(d));
            return null;
        }
        if (e instanceof Expr.Literal lit) {
            if (lit.type().isDatum()) { LocalDate ld = Literalen.parseDatum(lit.waarde()); return ld == null ? null : d -> ld; }
            BigDecimal n = Literalen.getal(lit); return n == null ? null : d -> n;
        }
        return null;
    }

    /** Compare two §9.8 operands (BigDecimal date-components or LocalDate values) by Comparable.compareTo. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean vergelijk(Object a, Object b, Conditie.Operator op) {
        if (!(a instanceof Comparable) || b == null) return false;
        int c = ((Comparable) a).compareTo(b);
        return switch (op) {
            case GELIJK -> c == 0;  case ONGELIJK -> c != 0;
            case GROTER, LATER -> c > 0;  case KLEINER, EERDER -> c < 0;
            case GROTER_OF_GELIJK, LATER_OF_GELIJK -> c >= 0;  case KLEINER_OF_GELIJK, EERDER_OF_GELIJK -> c <= 0;
        };
    }
}
