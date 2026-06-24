package io.github.yhavinga.regelspraak.runtime;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shippable runtime support for the EXECUTABLE-MODEL back-end ({@code ExecModelTranspiler}). Two roles:
 *
 * <ol>
 *   <li><b>The reflective fact bridge</b> — the model's constraint/consequence lambdas reach a RegelSpraak
 *       fact bean by REFLECTION ({@link #get}/{@link #set}), because the fact classes are RegelSpraak
 *       objecttypes, unknown at transpiler-compile time. (A future codegen pass could replace these with typed
 *       getters per objecttype; for now reflection keeps the renderer generic — migration roadmap Phase H.)</li>
 *   <li><b>The leegwaarde + arithmetic helpers</b> — the DRL {@code function} bodies reborn as plain Java static
 *       methods the lambdas call directly. Each is ported from its DRL-function string in {@code DrlTranspiler}
 *       as its migration phase lands, so the two back-ends compute the SAME value (the differential gate).</li>
 * </ol>
 */
public final class RegelSpraakRuntime {
    private RegelSpraakRuntime() {}

    // ---- reflective fact bridge ----
    /** A property read: tries {@code get<Prop>} then {@code is<Prop>} (boolean). Null bean -> null (a null
     *  to-one navigation reads as leeg, never an NPE). */
    public static Object get(Object bean, String prop) {
        if (bean == null) return null;
        String p = pascal(prop);
        for (String pre : new String[]{"get", "is"}) {
            try { Method m = bean.getClass().getMethod(pre + p); return m.invoke(bean); }
            catch (ReflectiveOperationException ignore) { }
        }
        throw new IllegalStateException("geen getter voor '" + prop + "' op " + bean.getClass().getName());
    }

    /** A property write via {@code set<Prop>}; a Boolean value targets a {@code boolean} setter. */
    public static void set(Object bean, String prop, Object val) {
        Class<?> argT = val instanceof Boolean ? boolean.class : val == null ? Object.class : val.getClass();
        try { bean.getClass().getMethod("set" + pascal(prop), argT).invoke(bean, val); return; }
        catch (ReflectiveOperationException first) {
            // fall back to a single-arg setter of any type (e.g. a BigDecimal subtype, or a null value)
            for (Method m : bean.getClass().getMethods())
                if (m.getName().equals("set" + pascal(prop)) && m.getParameterCount() == 1) {
                    try { m.invoke(bean, val); return; } catch (ReflectiveOperationException ignore) { }
                }
            throw new IllegalStateException("geen setter voor '" + prop + "' op " + bean.getClass().getName(), first);
        }
    }

    public static BigDecimal num(Object o) { return (BigDecimal) o; }

    /** §8.1.6 value equality matching mvel's {@code ==}: two BigDecimals compare by VALUE (scale-insensitive,
     *  100 == 100.0), everything else by {@code equals}. Used for the uniciteit key join (mvel reads
     *  {@code key == $k0} as compareTo==0, not the scale-sensitive BigDecimal.equals). */
    public static boolean gelijkWaarde(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return java.util.Objects.equals(a, b);
    }

    /** §13.4.6 construct a new instance of a runtime objecttype reflectively (its class is unknown at
     *  transpiler-compile time) via the no-arg constructor — the model analog of the DRL {@code new <Type>()} an
     *  objectcreatie inserts. */
    public static Object newInstance(Class<?> cls) {
        try { return cls.getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kan geen instantie van " + cls.getName() + " maken", e);
        }
    }

    /** §9.5 build an Inconsistentie fact reflectively (the fact class is a runtime objecttype, unknown at
     *  transpiler-compile time) — {@code new <fqcn>(regel, subject)} — for a consistentie/uniciteit companion
     *  rule to insert. The model back-end's analog of the DRL {@code insert(new ….Inconsistentie(...))}. */
    public static Object newInconsistentie(String fqcn, String regel, Object subject) {
        try {
            return Class.forName(fqcn).getConstructor(String.class, Object.class).newInstance(regel, subject);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kan geen " + fqcn + "(String, Object) construeren", e);
        }
    }

    /** Coerce a Drools accumulate result (a Long count, a BigDecimal sum, a min/max BigDecimal) to BigDecimal for
     *  assignment into a Numeriek attribute — mirrors the mvel widening the DRL consequence does. null stays null. */
    public static BigDecimal toBd(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        throw new IllegalStateException("kan accumulate-resultaat niet naar BigDecimal omzetten: " + o.getClass());
    }

    // ---- §6.x leegwaarde / arithmetic (ported from the DRL helper bodies as phases land) ----
    /** §6.2-6.4 Tabel: an empty (null) numeric operand counts as 0 in +/-. Mirrors the DRL {@code rsNul}. */
    public static BigDecimal rsNul(Object o) { return o == null ? BigDecimal.ZERO : (BigDecimal) o; }

    /** §6.3 Tabel 7/8 "verminderd met": the ONE subtraction whose empty LEFT yields empty (an empty RIGHT counts as
     *  0); plain "min" treats both empties as 0 (rsNul). Mirrors the DRL {@code rsVerminderd}. */
    public static BigDecimal rsVerminderd(Object links, Object rechts) {
        if (links == null) return null;
        return ((BigDecimal) links).subtract(rsNul(rechts));
    }

    /** §6.5 Tabel 12 division: an empty LEFT short-circuits to 0; an empty RIGHT (deling door leeg) is a Fout that
     *  degrades to leeg (null) on the value side — the §9.5 Inconsistentie is raised separately by the companion
     *  rule. Mirrors the DRL {@code rsGedeeld}. */
    public static BigDecimal rsGedeeld(Object links, Object rechts) {
        if (links == null) return BigDecimal.ZERO;
        return rechts == null ? null : ((BigDecimal) links).divide((BigDecimal) rechts, java.math.MathContext.DECIMAL64);
    }

    /** §13.4.17.13/§6.6 square root: a leeg OR negative radicand -> leeg (the negative is a Fout surfaced by the
     *  companion rule, never a session-aborting sqrt throw); else the 34-digit sqrt (NOT Math.sqrt double-noise,
     *  which can flip a directional afronding). Mirrors the DRL {@code rsWortel}. */
    public static BigDecimal rsWortel(Object x) {
        BigDecimal b = (BigDecimal) x;
        return (b == null || b.signum() < 0) ? null : b.sqrt(java.math.MathContext.DECIMAL128);
    }

    /** §13.4.17.25 afronding: round to {@code scale} decimals; a leeg (null) value rounds to leeg (so a wortel/
     *  macht/division leeg result propagates instead of NPE-ing .setScale). Mirrors the DRL {@code rsAfronding}. */
    public static BigDecimal rsAfronding(Object x, int scale, java.math.RoundingMode mode) {
        return x == null ? null : ((BigDecimal) x).setScale(scale, mode);
    }

    /** §6.5 "gedeeld door (ABS)": the same Tabel 12 leegwaarde as {@link #rsGedeeld} (empty LEFT -> 0, value/empty
     *  -> leeg), but the divide truncates TOWARD ZERO at 5 decimals. Mirrors the DRL {@code rsGedeeldAbs}. */
    public static BigDecimal rsGedeeldAbs(Object links, Object rechts) {
        if (links == null) return BigDecimal.ZERO;
        return rechts == null ? null : ((BigDecimal) links).divide((BigDecimal) rechts, 5, java.math.RoundingMode.DOWN);
    }

    /** §13.4.17.14 "tot de macht" with an INTEGER exponent: BigDecimal.pow(int) (exact, unlike Math.pow's double
     *  noise above 2^53); §6.7 a leeg base -> leeg. Mirrors the DRL {@code rsMacht}. */
    public static BigDecimal rsMacht(Object basis, int exponent) {
        return basis == null ? null : ((BigDecimal) basis).pow(exponent);
    }

    /** §13.4.17.14 "tot de macht" with a FRACTIONAL/runtime exponent: Math.pow at double precision; any leeg
     *  operand -> leeg. Mirrors the DRL {@code rsMachtReeel}. */
    public static BigDecimal rsMachtReeel(Object basis, Object exponent) {
        if (basis == null || exponent == null) return null;
        return BigDecimal.valueOf(Math.pow(((BigDecimal) basis).doubleValue(), ((BigDecimal) exponent).doubleValue()));
    }

    /** §6.1.4 begrenzing (clamp): a leeg value stays leeg, a null bound is no bound; clamps min-then-max (the value
     *  side's order). Mirrors the DRL {@code rsBegrens}. */
    public static BigDecimal rsBegrens(BigDecimal x, BigDecimal min, BigDecimal max) {
        if (x == null) return null;
        if (min != null && x.compareTo(min) < 0) x = min;
        if (max != null && x.compareTo(max) > 0) x = max;
        return x;
    }

    /** §13.4.17.17/§6.9 Tabel 20 "de absolute waarde van": |leeg| = leeg. Mirrors the DRL {@code rsAbs}. */
    public static BigDecimal rsAbs(Object x) { return x == null ? null : ((BigDecimal) x).abs(); }

    /** §5.4 text coercion for a §13.4.17.9 tekstreeks: a value's text form, null-safe (leeg -> "", not "null"); a
     *  BigDecimal via toString (528 -> "528"), a date/boolean via its toString. Mirrors the DRL {@code rsTekst}. */
    public static String rsTekst(Object o) { return o == null ? "" : o.toString(); }

    /** §13.4.17.18-.24/§6.12 a DATE/TIME component (jaar/maand/dag/uur/… uit) read via one ChronoField; a leeg
     *  date -> leeg. Mirrors the DRL {@code rsDatumDeel}. */
    public static BigDecimal rsDatumDeel(Object d, java.time.temporal.TemporalField f) {
        return d == null ? null : BigDecimal.valueOf(((java.time.temporal.TemporalAccessor) d).getLong(f));
    }

    /** §13.4.17.35 "de datum met jaar/maand/dag": a LocalDate from three numeric parts; any empty part -> leeg.
     *  Mirrors the DRL {@code rsDatumMet}. */
    public static java.time.LocalDate rsDatumMet(Object jaar, Object maand, Object dag) {
        if (jaar == null || maand == null || dag == null) return null;
        return java.time.LocalDate.of(((BigDecimal) jaar).intValue(), ((BigDecimal) maand).intValue(), ((BigDecimal) dag).intValue());
    }

    /** §13.4.17.36 "de datumtijd met jaar/…/milliseconde": a LocalDateTime from seven parts (the 7th, a
     *  millisecond, scaled to nanoOfSecond); any empty part -> leeg. Mirrors the DRL {@code rsDatumtijdMet}. */
    public static java.time.LocalDateTime rsDatumtijdMet(Object jaar, Object maand, Object dag, Object uur,
            Object minuut, Object seconde, Object milli) {
        if (jaar == null || maand == null || dag == null || uur == null || minuut == null || seconde == null || milli == null) return null;
        return java.time.LocalDateTime.of(((BigDecimal) jaar).intValue(), ((BigDecimal) maand).intValue(), ((BigDecimal) dag).intValue(),
                ((BigDecimal) uur).intValue(), ((BigDecimal) minuut).intValue(), ((BigDecimal) seconde).intValue(), ((BigDecimal) milli).intValue() * 1_000_000);
    }

    /** §13.4.17.37 "de eerste paasdag van" a year — Gauss/Anonymous Easter algorithm; a leeg year -> leeg.
     *  Mirrors the DRL {@code rsEerstePaasdag}. */
    public static java.time.LocalDate rsEerstePaasdag(Object jaar) {
        if (jaar == null) return null;
        int y = ((BigDecimal) jaar).intValue();
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4;
        int f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int maand = (h + l - 7 * m + 114) / 31;
        int dag = ((h + l - 7 * m + 114) % 31) + 1;
        return java.time.LocalDate.of(y, maand, dag);
    }

    // ---- §9.7 verdeling (allocation) helpers (ported from the DRL helper bodies for Phase E) ----
    /** §9.7.3 cap a naar-rato share at the recipient's maximum (the surplus is NOT redistributed). A null share
     *  stays leeg; a leeg or NEGATIVE maximum degrades to UNCAPPED — never a negative share — with the §9.7.6
     *  Tabel 19 Fout surfaced by the separate companion rule. A zero maximum is valid (the recipient gets nothing).
     *  Mirrors the DRL {@code rsPlafond}. */
    public static BigDecimal rsPlafond(BigDecimal waarde, BigDecimal max) {
        return (waarde == null || max == null || max.signum() < 0) ? waarde : waarde.min(max);
    }

    /** §9.7.2 "op volgorde van" ordered allocation: given the verdeelplafond {@code totaal}, the index-aligned
     *  recipient lists (the facts for identity keying, the ordering criterium {@code groep}, the optional tie weight
     *  {@code gewicht} and optional cap {@code max}), the sort direction and the optional floor decimals, compute the
     *  whole distribution and return an identity-keyed share map. Recipients are partitioned into groups of equal
     *  criterium and processed in sort order; every member of a tie-group splits the SAME group-entry pot (§9.7.2,
     *  Wilma 3/4·1200 and Hans 1/4·1200 both off 1200), naar rato of {@code gewicht} or in gelijke delen when no
     *  weight is present. The §9.7.3 cap and §9.7.4 floor are applied per share, the pot is then depleted by the group
     *  total, so the undistributed remainder carries to the next group (§9.7.5 Tabel 18: 1800 → 500/500/433/250/91,
     *  rest 26). A leeg/negative cap degrades to uncapped; a null ordering criterium sorts last; a leeg plafond yields
     *  an empty map (§9.7.6 row 1); a zero weight-sum gives that group ZERO; recipients past an empty pot get an
     *  assigned ZERO (never null). Mirrors the DRL {@code rsVerdeelOpVolgorde}. */
    public static java.util.Map<Object, BigDecimal> rsVerdeelOpVolgorde(BigDecimal totaal, java.util.List<Object> facts,
            java.util.List<Object> groep, boolean toenemend, java.util.List<Object> gewicht, java.util.List<Object> max, Integer decimals) {
        java.util.IdentityHashMap<Object, BigDecimal> result = new java.util.IdentityHashMap<>();
        if (totaal == null) return result;                       // §9.7.6 row 1: leeg verdeelplafond -> niet toegepast
        int aantal = facts.size();
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        for (int i = 0; i < aantal; i++) idx.add(i);
        for (int a = 1; a < aantal; a++) {                       // stable insertion sort of idx by groep value (nulls last)
            Integer cur = idx.get(a);
            Object curKey = groep.get(cur);
            int b = a - 1;
            while (b >= 0) {
                Object bKey = groep.get(idx.get(b));
                int cmp;
                if (bKey == null && curKey == null) cmp = 0;
                else if (bKey == null) cmp = 1;                  // a null criterium sorts after a present one
                else if (curKey == null) cmp = -1;
                else { @SuppressWarnings("unchecked") int c = ((Comparable<Object>) bKey).compareTo(curKey); cmp = toenemend ? c : -c; }
                if (cmp > 0) { idx.set(b + 1, idx.get(b)); b--; } else break;
            }
            idx.set(b + 1, cur);
        }
        BigDecimal pot = totaal;
        int p = 0;
        while (p < aantal) {
            int q = p;
            Object key = groep.get(idx.get(p));
            while (q < aantal && java.util.Objects.equals(key, groep.get(idx.get(q)))) q++;
            BigDecimal groepPot = pot;                            // §9.7.2: every member of the group splits this same pot
            BigDecimal som = BigDecimal.ZERO;
            boolean naarRato = false;
            for (int k = p; k < q; k++) {
                Object w = gewicht == null ? null : gewicht.get(idx.get(k));
                if (w != null) { naarRato = true; som = som.add(((BigDecimal) w).max(BigDecimal.ZERO)); }
            }
            int n = q - p;
            BigDecimal groepSom = BigDecimal.ZERO;
            for (int k = p; k < q; k++) {
                int i = idx.get(k);
                BigDecimal aandeel;
                if (naarRato) {
                    Object wo = gewicht == null ? null : gewicht.get(i);
                    // a negative weight is no proportional basis: clamp it to 0 so it cannot shrink the weight-sum and
                    // over-allocate the others past the pot (-> a negative rest, §9.7.6).
                    BigDecimal w = wo == null ? BigDecimal.ZERO : ((BigDecimal) wo).max(BigDecimal.ZERO);
                    aandeel = som.signum() == 0 ? BigDecimal.ZERO
                            : groepPot.multiply(w).divide(som, java.math.MathContext.DECIMAL64);
                } else {
                    aandeel = groepPot.divide(BigDecimal.valueOf(n), java.math.MathContext.DECIMAL64);
                }
                Object cap = max == null ? null : max.get(i);
                if (cap != null && ((BigDecimal) cap).signum() >= 0
                        && aandeel.compareTo((BigDecimal) cap) > 0) aandeel = (BigDecimal) cap;   // §9.7.3 cap
                if (decimals != null) aandeel = aandeel.setScale(decimals, java.math.RoundingMode.FLOOR);   // §9.7.4 floor
                if (aandeel.signum() < 0) aandeel = BigDecimal.ZERO;
                result.put(facts.get(i), aandeel);
                groepSom = groepSom.add(aandeel);
            }
            pot = pot.subtract(groepSom);                         // §9.7.5: carry the undistributed remainder to the next group
            if (pot.signum() < 0) pot = BigDecimal.ZERO;
            p = q;
        }
        return result;
    }

    // ---- §7 temporal (Tijdreeks / List<Segment>) helpers (ported from the DRL helper bodies for Phase F) ----
    // A temporal attribute is a piecewise-constant timeline held as an ordered List of Segment beans
    // ({van, tot, waarde}, half-open [van, tot)). The fact Segment class is a runtime objecttype unknown at
    // transpiler-compile time, so these read its van/tot/waarde REFLECTIVELY (the bridge below) and build new
    // segments via {@link #newSegment}; the per-segment values + arithmetic are identical to the DRL functions,
    // so both back-ends fold a timeline to the SAME result (the differential gate). The DRL `function` bodies are
    // plain Java (dialect java), so a verbatim Java port computes byte-identically (no mvel DECIMAL128 infix).
    private static LocalDate segVan(Object seg) { return (LocalDate) get(seg, "van"); }
    private static LocalDate segTot(Object seg) { return (LocalDate) get(seg, "tot"); }
    private static BigDecimal segWaarde(Object seg) { return (BigDecimal) get(seg, "waarde"); }
    /** Construct a Segment(van, tot, waarde) of the given fact class reflectively — the model analog of the DRL
     *  {@code new ….Segment(van, tot, waarde)} (the class is a runtime objecttype, unknown at compile time). */
    public static Object newSegment(Class<?> segCls, LocalDate van, LocalDate tot, BigDecimal waarde) {
        try {
            return segCls.getConstructor(LocalDate.class, LocalDate.class, BigDecimal.class).newInstance(van, tot, waarde);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kan geen Segment construeren op " + segCls.getName(), e);
        }
    }

    /** §7.1 "het totaal van" a temporal attribute: fold a {@code List<Segment>} by weighting each segment's value
     *  by the count of whole granularity periods (DAG/WEEK/MAAND/KWARTAAL/JAAR) between its van and tot. A null
     *  list -> null (leeg); a segment with a null bound/value contributes nothing (§4.20 leeg telt als 0); an empty
     *  timeline totals 0. Kwartaal = three maanden (ChronoUnit has no quarter). Mirrors the DRL {@code rsTijdTotaal}. */
    public static BigDecimal rsTijdTotaal(List<?> segmenten, String granulariteit) {
        if (segmenten == null) return null;
        BigDecimal som = BigDecimal.ZERO;
        for (Object o : segmenten) {
            BigDecimal w = segWaarde(o);
            LocalDate van = segVan(o), tot = segTot(o);
            if (w == null || van == null || tot == null) continue;
            long n;
            switch (granulariteit) {
                case "DAG":      n = ChronoUnit.DAYS.between(van, tot); break;
                case "WEEK":     n = ChronoUnit.WEEKS.between(van, tot); break;
                case "MAAND":    n = ChronoUnit.MONTHS.between(van, tot); break;
                case "KWARTAAL": n = ChronoUnit.MONTHS.between(van, tot) / 3; break;
                case "JAAR":     n = ChronoUnit.YEARS.between(van, tot); break;
                default:         n = 0;
            }
            som = som.add(w.multiply(BigDecimal.valueOf(n)));
        }
        return som;
    }

    /** §7.1 clip a value timeline by a condition's TRUE-period timeline: intersect each value Segment with each
     *  condition interval, emitting the overlap [max(van), min(tot)) carrying the value's waarde. A null value or
     *  condition clips to empty. Mirrors the DRL {@code rsTijdKnip}. */
    public static List<Object> rsTijdKnip(List<?> waarde, List<?> conditie, Class<?> segCls) {
        List<Object> uit = new ArrayList<>();
        if (waarde == null || conditie == null) return uit;
        for (Object wo : waarde) {
            LocalDate wv = segVan(wo), wt = segTot(wo);
            if (wv == null || wt == null) continue;
            for (Object co : conditie) {
                LocalDate cv = segVan(co), ct = segTot(co);
                if (cv == null || ct == null) continue;
                LocalDate van = wv.isAfter(cv) ? wv : cv;
                LocalDate tot = wt.isBefore(ct) ? wt : ct;
                if (van.isBefore(tot)) uit.add(newSegment(segCls, van, tot, segWaarde(wo)));
            }
        }
        return uit;
    }

    /** §8.1.8.1/.2 negated-gedurende complement-clip: keep each value Segment's parts where the condition is FALSE
     *  (subtract the condition TRUE-intervals from each value segment, emitting the gaps). The condition intervals
     *  overlapping a value segment are clamped to it, selection-sorted by start, then walked. A null value clips to
     *  empty; a null/empty condition leaves the whole value. Mirrors the DRL {@code rsTijdKnipNiet}. */
    public static List<Object> rsTijdKnipNiet(List<?> waarde, List<?> conditie, Class<?> segCls) {
        List<Object> uit = new ArrayList<>();
        if (waarde == null) return uit;
        for (Object wo : waarde) {
            LocalDate wv = segVan(wo), wt = segTot(wo);
            if (wv == null || wt == null) continue;
            List<LocalDate[]> knips = new ArrayList<>();
            if (conditie != null) for (Object co : conditie) {
                LocalDate cv = segVan(co), ct = segTot(co);
                if (cv == null || ct == null) continue;
                LocalDate van = wv.isAfter(cv) ? wv : cv;
                LocalDate tot = wt.isBefore(ct) ? wt : ct;
                if (van.isBefore(tot)) knips.add(new LocalDate[]{van, tot});
            }
            for (int i = 0; i < knips.size(); i++) {   // selection sort by start (small lists; plain, like the DRL body)
                int min = i;
                for (int j = i + 1; j < knips.size(); j++)
                    if (knips.get(j)[0].isBefore(knips.get(min)[0])) min = j;
                LocalDate[] t = knips.get(i); knips.set(i, knips.get(min)); knips.set(min, t);
            }
            LocalDate cursor = wv;
            for (LocalDate[] c : knips) {
                if (c[0].isAfter(cursor)) uit.add(newSegment(segCls, cursor, c[0], segWaarde(wo)));
                if (c[1].isAfter(cursor)) cursor = c[1];
            }
            if (cursor.isBefore(wt)) uit.add(newSegment(segCls, cursor, wt, segWaarde(wo)));
        }
        return uit;
    }

    /** §8.4.2 OR-compound union: merge two TRUE-period timelines into their union — drop empty/null-bounded
     *  segments, selection-sort by start, then sweep coalescing overlapping OR ADJACENT intervals ([a,b) ∪ [b,c)
     *  = [a,c)). The result carries a null waarde (boolean true-periods, re-stamped by a later rsTijdKnip). A null
     *  input contributes nothing. Mirrors the DRL {@code rsTijdUnie}. */
    public static List<Object> rsTijdUnie(List<?> a, List<?> b, Class<?> segCls) {
        List<LocalDate[]> segs = new ArrayList<>();
        if (a != null) for (Object o : a) {
            LocalDate v = segVan(o), t = segTot(o);
            if (v != null && t != null && v.isBefore(t)) segs.add(new LocalDate[]{v, t});
        }
        if (b != null) for (Object o : b) {
            LocalDate v = segVan(o), t = segTot(o);
            if (v != null && t != null && v.isBefore(t)) segs.add(new LocalDate[]{v, t});
        }
        for (int i = 0; i < segs.size(); i++) {
            int min = i;
            for (int j = i + 1; j < segs.size(); j++)
                if (segs.get(j)[0].isBefore(segs.get(min)[0])) min = j;
            LocalDate[] t = segs.get(i); segs.set(i, segs.get(min)); segs.set(min, t);
        }
        List<Object> uit = new ArrayList<>();
        LocalDate van = null, tot = null;
        for (LocalDate[] s : segs) {
            if (van == null) { van = s[0]; tot = s[1]; }
            else if (!s[0].isAfter(tot)) { if (s[1].isAfter(tot)) tot = s[1]; }
            else { uit.add(newSegment(segCls, van, tot, null)); van = s[0]; tot = s[1]; }
        }
        if (van != null) uit.add(newSegment(segCls, van, tot, null));
        return uit;
    }

    /** §7.2/§13.4.17.57 "het aantal dagen in <de maand|het jaar> dat <conditie>": bucket a condition's TRUE
     *  timeline into calendar months (or years) and count, per bucket, the days the condition holds — one output
     *  Segment per non-empty bucket [start, eind) whose waarde is that day-count. A null condition -> empty.
     *  Mirrors the DRL {@code rsAantalDagenIn}. */
    public static List<Object> rsAantalDagenIn(List<?> conditie, String granulariteit, Class<?> segCls) {
        if (conditie == null) return new ArrayList<>();
        boolean jaar = "JAAR".equals(granulariteit);
        TreeMap<LocalDate, Long> tellingen = new TreeMap<>();
        for (Object o : conditie) {
            LocalDate sv = segVan(o), st = segTot(o);
            if (sv == null || st == null || !sv.isBefore(st)) continue;
            LocalDate cursor = jaar ? sv.withDayOfYear(1) : sv.withDayOfMonth(1);
            while (cursor.isBefore(st)) {
                LocalDate next = jaar ? cursor.plusYears(1) : cursor.plusMonths(1);
                LocalDate van = sv.isAfter(cursor) ? sv : cursor;
                LocalDate tot = st.isBefore(next) ? st : next;
                long dagen = ChronoUnit.DAYS.between(van, tot);
                if (dagen > 0) tellingen.merge(cursor, dagen, Long::sum);
                cursor = next;
            }
        }
        List<Object> uit = new ArrayList<>();
        for (Map.Entry<LocalDate, Long> en : tellingen.entrySet()) {
            LocalDate start = en.getKey();
            LocalDate eind = jaar ? start.plusYears(1) : start.plusMonths(1);
            uit.add(newSegment(segCls, start, eind, new BigDecimal(en.getValue().longValue())));
        }
        return uit;
    }

    /** §7.3.2/§13.4.17.58 "het tijdsevenredig deel per <maand|jaar> van <basis> gedurende de tijd dat <conditie>":
     *  per calendar bucket, value = baseValueInBucket × (true-days / total-days) at DECIMAL64 — §4.22 keeps the
     *  exact fraction, NEVER auto-rounding to the doel's decimals (§6.1.2). The base value for a bucket is the basis
     *  segment covering the bucket start; a leeg base there yields no segment. A null conditie/basis -> empty.
     *  Mirrors the DRL {@code rsTijdsevenredig}. */
    public static List<Object> rsTijdsevenredig(List<?> conditie, List<?> basis, String granulariteit, Class<?> segCls) {
        List<Object> uit = new ArrayList<>();
        if (conditie == null || basis == null) return uit;
        boolean jaar = "JAAR".equals(granulariteit);
        TreeMap<LocalDate, Long> trueDagen = new TreeMap<>();
        for (Object o : conditie) {
            LocalDate sv = segVan(o), st = segTot(o);
            if (sv == null || st == null || !sv.isBefore(st)) continue;
            LocalDate cursor = jaar ? sv.withDayOfYear(1) : sv.withDayOfMonth(1);
            while (cursor.isBefore(st)) {
                LocalDate next = jaar ? cursor.plusYears(1) : cursor.plusMonths(1);
                LocalDate van = sv.isAfter(cursor) ? sv : cursor;
                LocalDate tot = st.isBefore(next) ? st : next;
                long dagen = ChronoUnit.DAYS.between(van, tot);
                if (dagen > 0) trueDagen.merge(cursor, dagen, Long::sum);
                cursor = next;
            }
        }
        for (Map.Entry<LocalDate, Long> en : trueDagen.entrySet()) {
            LocalDate start = en.getKey();
            LocalDate eind = jaar ? start.plusYears(1) : start.plusMonths(1);
            long totaal = ChronoUnit.DAYS.between(start, eind);
            long dagen = en.getValue().longValue();
            BigDecimal w = null;
            for (Object o : basis) {
                LocalDate bv = segVan(o), bt = segTot(o);
                if (bv != null && bt != null && !bv.isAfter(start) && bt.isAfter(start)) { w = segWaarde(o); break; }
            }
            if (w == null) continue;
            BigDecimal deel = w.multiply(BigDecimal.valueOf(dagen)).divide(BigDecimal.valueOf(totaal), MathContext.DECIMAL64);
            uit.add(newSegment(segCls, start, eind, deel));
        }
        return uit;
    }

    // ---- §13.4.17.51-.54 dimensie folds (a gedimensioneerd attribuut is a Map<label,value>) ----
    /** §5.8.5 fold a dimensioned attribute's per-label values over the SELECTED label keys, null-skipping empties
     *  (an absent label / null value is skipped, NOT 0) and returning null for an all-empty selection (§5.8.2 an
     *  empty sum is a leegwaarde). Exact add (no MathContext), matching the DRL {@code rsDimSom} function (Java). */
    public static BigDecimal rsDimSom(Map<?, ?> m, List<?> keys) {
        BigDecimal acc = null;
        for (Object k : keys) {
            BigDecimal v = m == null ? null : (BigDecimal) m.get(k);
            if (v != null) acc = acc == null ? v : acc.add(v);
        }
        return acc;
    }
    /** §5.8.5 minimum of a dimensioned attribute's per-label values over the selected keys (null-skipping; null when
     *  all-empty). Mirrors the DRL {@code rsDimMin}. */
    public static BigDecimal rsDimMin(Map<?, ?> m, List<?> keys) {
        BigDecimal acc = null;
        for (Object k : keys) {
            BigDecimal v = m == null ? null : (BigDecimal) m.get(k);
            if (v != null && (acc == null || v.compareTo(acc) < 0)) acc = v;
        }
        return acc;
    }
    /** §5.8.5 maximum of a dimensioned attribute's per-label values over the selected keys. Mirrors the DRL {@code rsDimMax}. */
    public static BigDecimal rsDimMax(Map<?, ?> m, List<?> keys) {
        BigDecimal acc = null;
        for (Object k : keys) {
            BigDecimal v = m == null ? null : (BigDecimal) m.get(k);
            if (v != null && (acc == null || v.compareTo(acc) > 0)) acc = v;
        }
        return acc;
    }

    // ---- identifier convention (matches the transpiler's ident()/getter() Pascal-casing) ----
    /** Strip a leading lidwoord, PascalCase the whitespace-separated words (so a getter/setter name matches the
     *  hand-written fact bean: "leeftijd" -> "Leeftijd", "recht op korting" -> "RechtOpKorting"). */
    public static String pascal(String naam) {
        String s = naam.replaceFirst("^(?i)(de|het|een)\\s+", "").trim();
        StringBuilder b = new StringBuilder();
        for (String w : s.split("\\s+")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }
}
