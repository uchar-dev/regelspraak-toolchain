package io.github.yhavinga.regelspraak.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shippable runtime support for the EXECUTABLE-MODEL back-end ({@code ExecModelTranspiler}). Two roles:
 *
 * <ol>
 *   <li><b>The reflective fact bridge</b> — the model's constraint/consequence lambdas reach a RegelSpraak
 *       fact bean by name ({@link #get}/{@link #set}), because the fact classes are RegelSpraak objecttypes,
 *       unknown at transpiler-compile time. Each (class, property) is resolved ONCE to an unreflected
 *       {@link java.lang.invoke.MethodHandle} and cached, so the bridge is reflection-scan-free after the first
 *       touch. Where the transpiler knows an objecttype's fact class at model-build time it goes one better and
 *       uses {@link Accessors} — TYPED LambdaMetafactory accessors that inline to a direct call (the Phase-H
 *       codegen path). This bridge serves the genuinely dynamic sites (navigation targets, Segment/Inconsistentie
 *       fields, multi-hop joins).</li>
 *   <li><b>The leegwaarde + arithmetic helpers</b> — the DRL {@code function} bodies reborn as plain Java static
 *       methods the lambdas call directly. Each is ported from its DRL-function string in {@code DrlTranspiler}
 *       as its migration phase lands, so the two back-ends compute the SAME value (the differential gate).</li>
 * </ol>
 */
public final class RegelSpraakRuntime {
    private RegelSpraakRuntime() {}

    // ---- reflective fact bridge (cached: Phase-H optimization) ----
    // The constraint/consequence lambdas read/write fact beans by property NAME (a RegelSpraak objecttype is
    // unknown at transpiler-compile time). A naive bridge re-runs getClass().getMethod(...) on EVERY fire; instead
    // we resolve each (class, property) ONCE to an unreflected MethodHandle and cache it. invoke() on the cached
    // handle does the same widening/boxing Method.invoke did, so the bridge is behaviour-identical — only faster.
    // {@link Accessors} layers TYPED LMF accessors on top of this for the statically-known objecttype sites.
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Map<Class<?>, Map<String, MethodHandle>> GETTERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, MethodHandle>> SETTERS = new ConcurrentHashMap<>();
    /** Marks a (class, prop) that has NO unique cacheable accessor (0 getters, or 0/>1 1-arg setters) so the slow
     *  path runs without re-resolving every call — cannot store null in a ConcurrentHashMap. */
    private static final MethodHandle NONE;
    static { try { NONE = LOOKUP.findStatic(RegelSpraakRuntime.class, "noneSentinel", java.lang.invoke.MethodType.methodType(void.class)); }
             catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); } }
    private static void noneSentinel() {}

    /** The unreflected getter handle for (cls, prop) — {@code get<Prop>} then {@code is<Prop>} — or {@link #NONE}. */
    static MethodHandle getterHandle(Class<?> cls, String prop) {
        MethodHandle h = GETTERS.computeIfAbsent(cls, c -> new ConcurrentHashMap<>())
                                .computeIfAbsent(prop, p -> resolveGetter(cls, p));
        return h == NONE ? null : h;
    }
    private static MethodHandle resolveGetter(Class<?> cls, String prop) {
        String p = pascal(prop);
        for (String pre : new String[]{"get", "is"}) {
            try { return LOOKUP.unreflect(cls.getMethod(pre + p)); }
            catch (NoSuchMethodException ignore) { }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        return NONE;
    }
    /** The unreflected setter handle for (cls, prop) when EXACTLY ONE 1-arg {@code set<Prop>} exists (the universal
     *  case for these POJOs); {@link #NONE} when 0 or >1, so {@link #set} keeps the original per-call resolution. */
    static MethodHandle setterHandle(Class<?> cls, String prop) {
        MethodHandle h = SETTERS.computeIfAbsent(cls, c -> new ConcurrentHashMap<>())
                                .computeIfAbsent(prop, p -> resolveSetter(cls, p));
        return h == NONE ? null : h;
    }
    private static MethodHandle resolveSetter(Class<?> cls, String prop) {
        String name = "set" + pascal(prop);
        Method only = null; int n = 0;
        for (Method m : cls.getMethods())
            if (m.getName().equals(name) && m.getParameterCount() == 1) { only = m; n++; }
        if (n != 1) return NONE;   // 0 or overloaded -> let set() fall back to the original reflective resolution
        try { return LOOKUP.unreflect(only); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    /** A property read: tries {@code get<Prop>} then {@code is<Prop>} (boolean). Null bean -> null (a null
     *  to-one navigation reads as leeg, never an NPE). */
    public static Object get(Object bean, String prop) {
        if (bean == null) return null;
        MethodHandle mh = getterHandle(bean.getClass(), prop);
        if (mh == null) throw new IllegalStateException("geen getter voor '" + prop + "' op " + bean.getClass().getName());
        try { return mh.invoke(bean); }
        catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw new IllegalStateException("getter '" + prop + "' faalde op " + bean.getClass().getName(), t); }
    }

    /** A property write via {@code set<Prop>}; a Boolean value targets a {@code boolean} setter. */
    public static void set(Object bean, String prop, Object val) {
        MethodHandle mh = setterHandle(bean.getClass(), prop);
        if (mh != null) {
            try { mh.invoke(bean, val); return; }
            catch (RuntimeException | Error e) { throw e; }
            catch (Throwable t) { throw new IllegalStateException("setter '" + prop + "' faalde op " + bean.getClass().getName(), t); }
        }
        // 0 or >1 single-arg setters: preserve the original reflective resolution exactly (value-type directed).
        Class<?> argT = val instanceof Boolean ? boolean.class : val == null ? Object.class : val.getClass();
        try { bean.getClass().getMethod("set" + pascal(prop), argT).invoke(bean, val); return; }
        catch (ReflectiveOperationException first) {
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

    /** §5.8/§5.6 the leegwaarde-faithful numeric comparison of an aggregation result to a threshold in a CONDITION: a leeg
     *  aggregate (empty SOM/MIN/MAX -> null) or a leeg threshold reads ONWAAR for the ordering / GELIJK operators (§5,
     *  a leeg operand). Coerces the agg (a Long count or a BigDecimal fold) to BigDecimal by value. Byte-identical mirror
     *  of the DRL {@code rsAggVergelijk} function. */
    public static boolean rsAggVergelijk(Object agg, BigDecimal n, String op) {
        BigDecimal a = agg == null ? null : new BigDecimal(agg.toString());
        if (a == null || n == null)
            return op.equals("==") && a == null && n == null;   // §5 Tabel 37: GELIJK both-leeg -> WAAR; any other leeg -> onwaar (ordering Tabel 33 / one-leeg GELIJK)
        int c = a.compareTo(n);
        return switch (op) {
            case ">" -> c > 0;   case ">=" -> c >= 0;
            case "<" -> c < 0;   case "<=" -> c <= 0;
            default  -> c == 0;
        };
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

    /** §8.1.3 elfproef: weight each digit from the last (the controlecijfer, weight -1), the next 2, 3, … and the
     *  weighted sum must be a POSITIEF geheel getal divisible by 11 and not all zeros. Admits a Numeriek (geheel
     *  getal) — leading zeros already gone — or a Tekst of digits (which preserves voor-/achterloopnullen); a null,
     *  fractional, or non-digit value is not a valid reeks -> false. Byte-identical to the DRL {@code rsElfproef}. */
    public static boolean rsElfproef(Object x) {
        if (x == null) return false;
        String s;
        if (x instanceof BigDecimal bd) {
            if (bd.stripTrailingZeros().scale() > 0) return false;   // §8.1.3 Numeriek (geheel getal) only — never truncate a fractional value
            s = bd.toBigInteger().toString();
        } else s = x.toString();
        int n = s.length();
        if (n == 0) return false;
        long som = 0;
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
            som += (long) (ch - '0') * ((i == n - 1) ? -1 : (n - i));
        }
        return som > 0 && (som % 11 == 0);
    }

    /** §8.1.4 "numeriek met exact N cijfers" (Tekst-only): true iff the value reads as a cijferreeks of EXACTLY N
     *  digits — every character a digit and the length exactly N (voor-/achterloopnullen count). A null / non-digit /
     *  wrong-length value is false. Byte-identical to the DRL {@code rsNumeriekMetExact}. */
    public static boolean rsNumeriekMetExact(Object x, int n) {
        if (x == null) return false;
        String s = x.toString();
        if (s.length() != n) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
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

    /** §6/§13.4.16 date arithmetic (§6 spec / Tabel 15): a datum/datumtijd plus or minus a whole number of a time
     *  unit (dag/week/maand/jaar; uur/… only on a datumtijd) -> a new date(time). Leegwaarde per Tabel 15: an empty
     *  DATUM -> empty ("niet te gebruiken"), but an empty DURATION is treated as the number 0 -> the date unchanged
     *  (as is a 0 duration, §6). The aantal is taken as whole units (date arithmetic operates on integer time
     *  units; a fractional value truncates toward zero). Verbatim mirror of the DRL {@code rsDatumReken}. */
    public static java.time.temporal.Temporal rsDatumReken(java.time.temporal.Temporal datum, BigDecimal aantal, String eenheid, boolean optellen) {
        if (datum == null) return null;        // leeg datum -> leeg
        if (aantal == null) return datum;      // leeg tijdseenheid -> behandeld als 0 -> datum onveranderd (Tabel 15)
        long n = aantal.longValue();
        boolean kwartaal = "KWARTAAL".equals(eenheid);   // §3.7 kwartaal = 3 maanden (geen ChronoUnit.QUARTERS)
        java.time.temporal.ChronoUnit u = kwartaal ? java.time.temporal.ChronoUnit.MONTHS : java.time.temporal.ChronoUnit.valueOf(eenheid);
        if (kwartaal) n = n * 3;
        return optellen ? datum.plus(n, u) : datum.minus(n, u);
    }

    /** §6.11 "datum ± N &lt;dagsoort&gt;" ("5 werkdagen"): step one calendar day at a time, counting only days that
     *  ARE the dagsoort ({@code isDagsoort}), until N have passed — the Nth such day is the result. X itself NEVER
     *  counts (step before testing) and the result is always a dagsoort day, EXCEPT a 0 / leeg count returns X
     *  unchanged (the §6.11 0-case wins over "always a werkdag"); a leeg datum stays leeg. Byte-identical mirror of
     *  the DRL {@code rsDatumPlus_<Naam>} loop (there the dagsoort predicate is a generated function, here a lambda). */
    public static java.time.LocalDate rsDatumPlusDagsoort(java.time.LocalDate datum, BigDecimal aantal,
            java.util.function.Predicate<java.time.LocalDate> isDagsoort, boolean optellen) {
        if (datum == null) return null;        // leeg datum -> leeg
        if (aantal == null) return datum;      // leeg eenheid -> behandeld als 0 -> datum onveranderd (§6.11 Tabel 15)
        long n = aantal.longValue();
        boolean op = optellen;
        if (n < 0) { op = !op; n = -n; }
        if (n == 0) return datum;              // §6.11: 0 -> X onveranderd (zelfs als X geen dagsoort-dag is)
        java.time.LocalDate d = datum;
        long geteld = 0, stappen = 0;
        // Bound the walk: a dagsoortdefinitie that matches NO calendar date (an author error, e.g. maand==13) would
        // otherwise loop forever. 4_000_000 days (~10000 years) is far beyond any real dagsoort spacing.
        while (geteld < n) {
            d = op ? d.plusDays(1) : d.minusDays(1);
            if (++stappen > 4_000_000L) throw new IllegalStateException(
                    "dagsoort-rekenkunde: geen " + n + "-de dagsoort-dag binnen 4000000 dagen (onvervulbare dagsoortdefinitie?)");
            if (isDagsoort.test(d)) geteld++;
        }
        return d;
    }

    /** §6.11 "datum-en-tijd ± N &lt;dagsoort&gt;": §4.1/§4.2 Tabel 2/5 rule 2 — adding a time value to a datum-EN-tijd
     *  operand yields a datum-en-tijd result (the time-of-day is preserved). A dagsoort is day-granular, so step over
     *  the DATE part via {@link #rsDatumPlusDagsoort} and re-attach the original time-of-day. A leeg datum stays leeg.
     *  Byte-identical mirror of the DRL {@code rsDatumTijdPlus_<Naam>} wrapper. */
    public static java.time.LocalDateTime rsDatumTijdPlusDagsoort(java.time.LocalDateTime datum, BigDecimal aantal,
            java.util.function.Predicate<java.time.LocalDate> isDagsoort, boolean optellen) {
        if (datum == null) return null;        // leeg datum -> leeg
        java.time.LocalDate d = rsDatumPlusDagsoort(datum.toLocalDate(), aantal, isDagsoort, optellen);
        return d == null ? null : d.atTime(datum.toLocalTime());   // re-attach the time-of-day (§4 rule 2)
    }

    /** §6.10 "de (absolute) tijdsduur van A tot B in &lt;dagsoort&gt;": COUNT the dagsoort days in the half-open
     *  interval (van, tot] — consistent with §6.11's "X telt niet mee". Signed (negative when tot is earlier than
     *  van), absolute when asked; any leeg operand -> leeg (§6.10 Tabel 14). Byte-identical mirror of the DRL
     *  {@code rsTijdsduur_<Naam>} loop. */
    public static BigDecimal rsTijdsduurDagsoort(java.time.LocalDate van, java.time.LocalDate tot,
            java.util.function.Predicate<java.time.LocalDate> isDagsoort, boolean absoluut) {
        if (van == null || tot == null) return null;
        boolean negatief = tot.isBefore(van);
        java.time.LocalDate a = negatief ? tot : van;
        java.time.LocalDate b = negatief ? van : tot;
        long count = 0;
        for (java.time.LocalDate d = a.plusDays(1); !d.isAfter(b); d = d.plusDays(1)) if (isDagsoort.test(d)) count++;
        long result = negatief ? -count : count;
        return BigDecimal.valueOf(absoluut ? Math.abs(result) : result);
    }

    /** §6.10 "de (absolute) tijdsduur van A tot B in &lt;dagsoort&gt;" over datum-EN-tijd operands: typeringen §4.16 (L676)
     *  admits datum-en-tijd operands (both the SAME precision) and the result is a COUNT (no time is carried). A dagsoort
     *  is day-granular, so count over the DATE parts via {@link #rsTijdsduurDagsoort}. Any leeg operand -> leeg.
     *  Byte-identical mirror of the DRL {@code rsTijdsduurTijd_<Naam>} wrapper. */
    public static BigDecimal rsTijdsduurTijdDagsoort(java.time.LocalDateTime van, java.time.LocalDateTime tot,
            java.util.function.Predicate<java.time.LocalDate> isDagsoort, boolean absoluut) {
        return rsTijdsduurDagsoort(van == null ? null : van.toLocalDate(), tot == null ? null : tot.toLocalDate(), isDagsoort, absoluut);
    }

    /** §6.10 / §13.4.17.32 "de (absolute) tijdsduur van A tot B in &lt;tijdseenheid&gt;": the number of COMPLETE
     *  units between two dates ({@code ChronoUnit.<U>.between}, signed — A later than B gives a negative result, §6.10
     *  Tabel 14 +52/-52); absolute when asked. A kwartaal (no ChronoUnit.QUARTERS) is 3×MONTHS, §3.7. Any leeg operand
     *  -> leeg (Tabel 14), not 0. Verbatim mirror of the DRL {@code rsTijdsduur} function body. */
    public static BigDecimal rsTijdsduur(java.time.temporal.Temporal van, java.time.temporal.Temporal tot, String eenheid, boolean absoluut) {
        if (van == null || tot == null) return null;
        long n = "KWARTAAL".equals(eenheid)   // §3.7 kwartaal = 3 maanden (geen ChronoUnit.QUARTERS)
                ? java.time.temporal.ChronoUnit.MONTHS.between(van, tot) / 3
                : java.time.temporal.ChronoUnit.valueOf(eenheid).between(van, tot);
        return BigDecimal.valueOf(absoluut ? Math.abs(n) : n);
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
            if (w == null || van == null || tot == null || !van.isBefore(tot)) continue;   // §4 a zero/negative-length segment contributes 0, never a negative period count
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

    /** §7.1 EUR71 the TRUE-period timeline of a temporal-attribute comparison: keep each value Segment whose value
     *  satisfies the toets (0..5 = KLEINER..ONGELIJK) against the scalar grens — the periods where the comparison
     *  holds. A leeg (null) value or a null grens is onwaar (§5.1 ordering leegwaarde). Mirrors the DRL
     *  {@code rsTijdConditie}; the caller then rsTijdKnips the value timeline by this result. */
    public static List<Object> rsTijdConditie(List<?> reeks, int toets, BigDecimal grens, Class<?> segCls) {
        List<Object> uit = new ArrayList<>();
        if (reeks == null || grens == null) return uit;
        for (Object o : reeks) {
            LocalDate van = segVan(o), tot = segTot(o);
            if (van == null || tot == null || !van.isBefore(tot)) continue;
            BigDecimal w = segWaarde(o);
            if (w == null) {
                if (toets == 5) uit.add(newSegment(segCls, van, tot, w));   // §5.6 Tabel 44: leeg ONGELIJK een gevulde grens = WAAR
                continue;
            }
            int c = w.compareTo(grens);
            boolean ok;
            if (toets == 0) ok = c < 0;
            else if (toets == 1) ok = c <= 0;
            else if (toets == 2) ok = c > 0;
            else if (toets == 3) ok = c >= 0;
            else if (toets == 4) ok = c == 0;
            else if (toets == 5) ok = c != 0;
            else ok = false;
            if (ok) uit.add(newSegment(segCls, van, tot, w));
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

    /** §7.3.1 the standaard jaar/maand omrekening: restate a per-tijdseenheid RATE timeline into another rate unit by
     *  scaling every segment value by the EXACT integer unit ratio at DECIMAL64 (§4.22, no auto-round); a leeg segment
     *  stays leeg. §7.3.1 specifies a DIVISION ("120 gedeeld door de omrekenfactor 12"), so when the restatement is
     *  ÷N the caller passes {@code deel=true} (divide by the exact 12), and ×N passes {@code deel=false} — never a
     *  pre-rounded reciprocal (1/12), which would lose precision for non-clean bases. Applied to the §7.3.2
     *  tijdsevenredig BASE BEFORE proration. Mirrors the DRL {@code rsTijdSchaal}. */
    public static List<Object> rsTijdSchaal(List<?> tijdlijn, BigDecimal factor, boolean deel) {
        List<Object> uit = new ArrayList<>();
        if (tijdlijn == null) return uit;
        for (Object o : tijdlijn) {
            BigDecimal w = segWaarde(o);
            BigDecimal nw = w == null ? null : (deel ? w.divide(factor, MathContext.DECIMAL64) : w.multiply(factor, MathContext.DECIMAL64));
            uit.add(newSegment(o.getClass(), segVan(o), segTot(o), nw));
        }
        return uit;
    }

    /** §5.1.4 the result of a tijdsafhankelijke arithmetic expression: apply {@code op} (PLUS/MIN) per period on the
     *  MERGED knip timeline of the two operands. The result's cut-points are the UNION of both operands' van/tot; in a
     *  period where an operand has no value it is leeg, treated as 0 (§5.1.4 note: "een lege waarde als een 0-waarde").
     *  Adjacent equal-value segments are coalesced (so an unchanged value carries no knip). Mirrors the DRL rsTijdReken. */
    public static List<Object> rsTijdReken(List<?> a, List<?> b, String op, Class<?> segCls) {
        TreeMap<LocalDate, Boolean> knipSet = new TreeMap<>();
        for (Object o : leeg(a)) { LocalDate v = segVan(o), t = segTot(o); if (v != null && t != null && v.isBefore(t)) { knipSet.put(v, true); knipSet.put(t, true); } }
        for (Object o : leeg(b)) { LocalDate v = segVan(o), t = segTot(o); if (v != null && t != null && v.isBefore(t)) { knipSet.put(v, true); knipSet.put(t, true); } }
        List<Object> uit = new ArrayList<>();
        LocalDate prev = null, lopendeVan = null; BigDecimal lopendeWaarde = null;
        for (LocalDate t : knipSet.keySet()) {
            if (prev != null) {
                BigDecimal x = nul(waardeOpDag(a, prev)), y = nul(waardeOpDag(b, prev));
                BigDecimal r = "MIN".equals(op) ? x.subtract(y) : x.add(y);   // PLUS default (§6.2)
                if (lopendeVan == null) { lopendeVan = prev; lopendeWaarde = r; }
                else if (lopendeWaarde.compareTo(r) != 0) { uit.add(newSegment(segCls, lopendeVan, prev, lopendeWaarde)); lopendeVan = prev; lopendeWaarde = r; }
            }
            prev = t;
        }
        if (lopendeVan != null) uit.add(newSegment(segCls, lopendeVan, prev, lopendeWaarde));
        return uit;
    }
    private static List<?> leeg(List<?> l) { return l == null ? java.util.Collections.emptyList() : l; }
    private static BigDecimal nul(BigDecimal w) { return w == null ? BigDecimal.ZERO : w; }
    /** The value of the segment covering {@code dag} (van &le; dag &lt; tot), or null (leeg) when no segment covers it. */
    private static BigDecimal waardeOpDag(List<?> lijst, LocalDate dag) {
        for (Object o : leeg(lijst)) {
            LocalDate v = segVan(o), t = segTot(o);
            if (v != null && t != null && !v.isAfter(dag) && dag.isBefore(t)) return segWaarde(o);
        }
        return null;
    }

    /** §8.2/§13.4.15.70 "gedurende de/het gehele &lt;periode&gt;": is the predicate {@code true at EVERY moment} of
     *  each calendar bucket (MAAND|JAAR) the timeline touches? Bucket the {@code List<Segment>} timeline by calendar
     *  period (like {@link #rsAantalDagenIn}); a bucket is "touched" if any segment overlaps it, and PASSES only when
     *  the per-segment check holds for the bucket's WHOLE calendar length (a gap, or a contrary/leeg segment, leaves
     *  fewer satisfying days than the period has → fail). Returns the AND over all touched buckets — and {@code true}
     *  for an empty/leeg timeline (no period to violate; the caller's NIET-prefix complements). {@code toets}: 0..5 =
     *  KLEINER/KLEINER_OF_GELIJK/GROTER/GROTER_OF_GELIJK/GELIJK/ONGELIJK of {@code waarde} vs {@code grens} (a leeg
     *  waarde never satisfies), 6 = GEVULD (any non-leeg waarde), 7 = covered (a §8.2 kenmerk true-period — any
     *  covering segment). Mirrors the DRL {@code rsGeheelPeriode}; no Segment construction, so no segCls. */
    public static boolean rsGeheelPeriode(List<?> tijdlijn, String granulariteit, int toets, BigDecimal grens) {
        if (tijdlijn == null) return true;
        boolean jaar = "JAAR".equals(granulariteit);
        TreeMap<LocalDate, Long> geraaktDagen = new TreeMap<>();   // calendar buckets the timeline touches
        TreeMap<LocalDate, Long> goedDagen = new TreeMap<>();      // satisfying days per bucket
        for (Object o : tijdlijn) {
            LocalDate van = segVan(o), tot = segTot(o);
            if (van == null || tot == null || !van.isBefore(tot)) continue;
            boolean ok = segmentVoldoet(toets, segWaarde(o), grens);
            LocalDate cursor = jaar ? van.withDayOfYear(1) : van.withDayOfMonth(1);
            while (cursor.isBefore(tot)) {
                LocalDate next = jaar ? cursor.plusYears(1) : cursor.plusMonths(1);
                LocalDate a = van.isAfter(cursor) ? van : cursor;
                LocalDate b = tot.isBefore(next) ? tot : next;
                long dagen = ChronoUnit.DAYS.between(a, b);
                if (dagen > 0) {
                    geraaktDagen.merge(cursor, dagen, Long::sum);
                    if (ok) goedDagen.merge(cursor, dagen, Long::sum);
                }
                cursor = next;
            }
        }
        for (Map.Entry<LocalDate, Long> en : geraaktDagen.entrySet()) {
            LocalDate bucket = en.getKey();
            LocalDate eind = jaar ? bucket.plusYears(1) : bucket.plusMonths(1);
            long totaal = ChronoUnit.DAYS.between(bucket, eind);
            long goed = goedDagen.getOrDefault(bucket, 0L);
            // §8.2 LEEG (toets 8): a GAP day carries no value, so it is ALSO leeg — only the TOUCHED days must be
            // leeg-satisfying (compare against the bucket's touched days). Every other check requires the WHOLE calendar
            // period to satisfy (a gap leaves fewer satisfying days than the period has -> fail).
            long drempel = toets == 8 ? en.getValue() : totaal;
            if (goed < drempel) return false;   // a moment in this calendar period fails the check
        }
        return true;
    }

    /** Whether one timeline segment's value satisfies the §8.2 per-moment check ({@code toets} encoding per
     *  {@link #rsGeheelPeriode}). A leeg (null) value satisfies only the "covered" check (toets 7); every value
     *  comparison treats leeg as not-holding. */
    private static boolean segmentVoldoet(int toets, BigDecimal w, BigDecimal grens) {
        if (toets == 7) return true;       // §8.2 kenmerk: a covering segment = the kenmerk holds here
        if (toets == 8) return w == null;  // §8.2 leeg: this moment carries NO value (the inverse of gevuld)
        if (w == null) return false;       // leeg: no value at this moment -> the check does not hold
        if (toets == 6) return true;       // §8.2 gevuld: a non-leeg value
        if (grens == null) return false;   // §5.1 a comparison with a leeg grens is onwaar (never crash on compareTo(null))
        int c = w.compareTo(grens);
        switch (toets) {
            case 0:  return c < 0;
            case 1:  return c <= 0;
            case 2:  return c > 0;
            case 3:  return c >= 0;
            case 4:  return c == 0;
            case 5:  return c != 0;
            default: return false;
        }
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
