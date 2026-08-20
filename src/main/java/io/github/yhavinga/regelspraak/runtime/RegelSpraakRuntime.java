package io.github.yhavinga.regelspraak.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
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
 *   <li><b>The leegwaarde + arithmetic helpers</b> — since single-runtime principle the ONE rs* implementation: the exec model's
 *       lambdas call these directly, and the generated DRL imports the same methods via
 *       {@code import function}, so the two back-ends compute the SAME value by construction (the
 *       differential gate now compares wiring, not re-implementations).</li>
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
            // Resolve the Inconsistentie bean via the thread context classloader (both exec runners set TCCL=beanLoader
            // at fire time) so a CHILD-loaded bean is found; fall back to RegelSpraakRuntime's own loader when no TCCL.
            // A bare Class.forName(fqcn) resolves against the parent loader -> ClassNotFound under child-loaded beans.
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            Class<?> c = tccl != null ? Class.forName(fqcn, true, tccl) : Class.forName(fqcn);
            return c.getConstructor(String.class, Object.class).newInstance(regel, subject);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kan geen " + fqcn + "(String, Object) construeren", e);
        }
    }

    /** Coerce a Drools accumulate result (a Long count, a BigDecimal sum, a min/max BigDecimal) to BigDecimal for
     *  assignment into a Numeriek attribute — mirrors the mvel widening the DRL consequence does. null stays null.
     *  A {@link Breuk} (an exact rational produced by the Precisie-clause ops) COLLAPSES via {@link Breuk#naarDecimal()}
     *  (exact when terminating, DECIMAL64 defensively otherwise); it must be handled BEFORE the generic Number arm,
     *  because {@code new BigDecimal("1/3")} (Breuk.toString) would throw. */
    public static BigDecimal toBd(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Breuk br) return br.naarDecimal();
        if (o instanceof Number n) return new BigDecimal(n.toString());
        throw new IllegalStateException("kan accumulate-resultaat niet naar BigDecimal omzetten: " + o.getClass());
    }

    /** Coerce any number-like value (BigDecimal / Breuk / other Number) to an EXACT {@link Breuk}, preserving a
     *  non-terminating rational instead of truncating it. {@code null} stays {@code null} (the leegwaarde tables run
     *  in the callers). The exact-path twin of {@link #toBd}; use it wherever an rs* helper computes on the exact
     *  rational. */
    public static Breuk numeriek(Object o) { return Breuk.van(o); }

    /** Whether any of the operands is already an exact {@link Breuk} — the trigger for exactness to PROPAGATE
     *  through +,-,×,÷ (a Breuk-tainted operand keeps the result exact even for an otherwise-terminating op). */
    private static boolean enigeBreuk(Object a, Object b) { return a instanceof Breuk || b instanceof Breuk; }

    /** Reduce a {@link Breuk} result to a plain {@link BigDecimal} when it terminates (so the common, exact-decimal
     *  case stays on the fast BigDecimal path and pins do not churn), else keep it a {@link Breuk} so a later op /
     *  afronding stays exact. A non-Breuk value is returned unchanged. */
    private static Object verklein(Object v) {
        if (v instanceof Breuk b) return b.isTerminerend() ? b.naarDecimal() : b;
        return v;
    }

    /** §5.8/§5.6 the leegwaarde-faithful numeric comparison of an aggregation result to a threshold in a CONDITION: a leeg
     *  aggregate (empty SOM/MIN/MAX -> null) or a leeg threshold reads ONWAAR for the ordering operators (§5, a leeg
     *  operand); GELIJK is WAAR only when BOTH are leeg (§5.3 Tabel 37); ONGELIJK is WAAR for exactly-one-leeg and onwaar
     *  for both-leeg (§5.6 Tabel 44). Coerces the agg (a Long count or a BigDecimal fold) to BigDecimal by value. */
    public static boolean rsAggVergelijk(Object agg, Object n, String op) {
        if (agg == null || n == null) {
            boolean beideLeeg = agg == null && n == null;
            if (op.equals("==")) return beideLeeg;     // §5.3 Tabel 37: leeg==leeg WAAR, exactly-one-leeg onwaar
            if (op.equals("!=")) return !beideLeeg;    // §5.6 Tabel 44: leeg!=gevuld WAAR, leeg!=leeg onwaar
            return false;                              // ordering with a leeg operand -> onwaar (Tabel 33)
        }
        // Compare EXACTLY when either side is an exact Breuk (1/3 maal 3 gelijk aan 1 -> WAAR); else by BigDecimal value.
        int c = (agg instanceof Breuk || n instanceof Breuk) ? Breuk.van(agg).compareTo(Breuk.van(n)) : toBd(agg).compareTo(toBd(n));
        return switch (op) {
            case ">" -> c > 0;   case ">=" -> c >= 0;
            case "<" -> c < 0;   case "<=" -> c <= 0;
            case "!=" -> c != 0;
            default  -> c == 0;
        };
    }

    // ---- §6.x leegwaarde / arithmetic (ported from the DRL helper bodies as phases land) ----
    /** §6.2-6.4 Tabel: an empty (null) numeric operand counts as 0 in +/-. The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsNul(Object o) { return o == null ? BigDecimal.ZERO : toBd(o); }

    /** §13.2.5/§389 an EXACT rational literal factory — the DRL emits {@code rsBreuk("t", "n")} for a non-terminating
     *  rational literal (1/3) so its arithmetic stays exact (the exec back-end lowers the same literal to a Breuk
     *  constant). A terminating literal never reaches this — it stays a plain BigDecimal on both sides. */
    public static Breuk rsBreuk(String teller, String noemer) {
        return Breuk.van(new java.math.BigInteger(teller), new java.math.BigInteger(noemer));
    }

    /** §6.1.2 write-boundary collapse: a computed value assigned to a decimal-typed field must be a BigDecimal, so a
     *  remaining exact {@link Breuk} (an unrounded terminating result; a non-terminating one is resolver-guarded)
     *  collapses to {@link Breuk#naarDecimal()} — exact when terminating, defensive DECIMAL64 otherwise. The DRL
     *  wraps its final scalar assignment value in this so the idempotent {@code Objects.equals(field, value)} guard
     *  compares like-typed values (a Breuk vs a BigDecimal field would never be equal — an infinite modify loop). A
     *  null / non-Breuk passes through. Mirrors the exec back-end's {@code collapseBreuk} at werkBij. */
    public static BigDecimal rsWaarde(Object o) {
        return toBd(o);   // BigDecimal as-is, Breuk collapses (exact/DECIMAL64), a Long/Number (accumulate count) widens
    }

    // ---- Breuk-aware +,-,×,÷ and percentage (Precisie §389/§486): exactness PROPAGATES through +,-,×,÷ when any
    // operand is a Breuk, and division/percentage RETURN a Breuk when their exact result does not terminate. Both
    // back-ends route their formerly-inline arithmetic (DRL mvel infix, exec rsNul(..).add) through these, so the
    // single-runtime principle holds and no host-language infix ever sees a Breuk. Each returns Object (a BigDecimal
    // on the fast/terminating path, else a Breuk); leegwaarde per §6.2-6.4 Tabel: an empty operand counts as 0. ----

    /** §6.2 "plus": leeg counts as 0; exact when either operand is a Breuk (else the DECIMAL128 BigDecimal add,
     *  byte-identical to the former mvel infix). */
    public static Object rsPlus(Object links, Object rechts) {
        if (enigeBreuk(links, rechts)) return verklein(nulBreuk(links).optellen(nulBreuk(rechts)));
        return rsNul(links).add(rsNul(rechts), java.math.MathContext.DECIMAL128);
    }
    /** §6.2 "min" (both empties -> 0, unlike "verminderd met"): exact when either operand is a Breuk. */
    public static Object rsMin(Object links, Object rechts) {
        if (enigeBreuk(links, rechts)) return verklein(nulBreuk(links).aftrekken(nulBreuk(rechts)));
        return rsNul(links).subtract(rsNul(rechts), java.math.MathContext.DECIMAL128);
    }
    /** §6.4 Tabel 10 "maal": any empty operand -> 0; exact when either operand is a Breuk (so 9 maal 1/3 = 3 exactly,
     *  not 2.9999999999999997). The single implementation; the DRL imports it (single-runtime principle). */
    public static Object rsMaal(Object links, Object rechts) {
        if (enigeBreuk(links, rechts)) return verklein(nulBreuk(links).vermenigvuldigen(nulBreuk(rechts)));
        return rsNul(links).multiply(rsNul(rechts), java.math.MathContext.DECIMAL128);
    }
    /** §6.8/§486 "percentage van": {@code l/100 · r}, leeg counts as 0. The exact fraction is always kept as a Breuk
     *  when it does not terminate ((1/3)% van 600 = 2 exactly, not 1.9999…998), and whenever either operand is a
     *  Breuk. The /100 is exact. The single implementation; the DRL imports it (single-runtime principle). */
    public static Object rsPercentageVan(Object links, Object rechts) {
        Breuk r = nulBreuk(links).delen(HONDERD).vermenigvuldigen(nulBreuk(rechts));
        // §486 the result is exact — keep it a Breuk unless it terminates (then the fast BigDecimal path).
        return verklein(r);
    }
    private static final Breuk HONDERD = Breuk.van(java.math.BigInteger.valueOf(100), java.math.BigInteger.ONE);
    /** A leeg operand as the exact rational 0; else its exact {@link Breuk} form. */
    private static Breuk nulBreuk(Object o) { return o == null ? Breuk.van(java.math.BigInteger.ZERO, java.math.BigInteger.ONE) : Breuk.van(o); }

    /** §6.3 Tabel 7/8 "verminderd met": the ONE subtraction whose empty LEFT yields empty (an empty RIGHT counts as
     *  0); plain "min" treats both empties as 0 (rsNul). Exact when either operand is a Breuk. The single implementation; the DRL imports it (single-runtime principle). */
    public static Object rsVerminderd(Object links, Object rechts) {
        if (links == null) return null;
        if (enigeBreuk(links, rechts)) return verklein(Breuk.van(links).aftrekken(nulBreuk(rechts)));
        return toBd(links).subtract(rsNul(rechts));
    }

    /** §6.5 Tabel 12 / §389 division: an empty LEFT short-circuits to 0; an empty RIGHT (deling door leeg) is a Fout
     *  that degrades to leeg (null) on the value side — the §9.5 Inconsistentie is raised separately by the companion
     *  rule. §389: the exact quotient is kept as a Breuk when it does not terminate (1/3 stays exact instead of the
     *  16-digit DECIMAL64 approximation), else a plain BigDecimal. The single implementation; the DRL imports it (single-runtime principle). */
    public static Object rsGedeeld(Object links, Object rechts) {
        if (links == null) return BigDecimal.ZERO;
        if (rechts == null) return null;
        return verklein(Breuk.van(links).delen(Breuk.van(rechts)));   // §389 exact rational; verklein keeps the fast path when it terminates
    }

    /** §13.4.17.13/§6.6 square root: a leeg OR negative radicand -> leeg (the negative is a Fout surfaced by the
     *  companion rule, never a session-aborting sqrt throw); else the 34-digit sqrt (NOT Math.sqrt double-noise,
     *  which can flip a directional afronding). The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsWortel(Object x) {
        BigDecimal b = toBd(x);   // sqrt is a natural DECIMAL128 collapse; a Breuk radicand reduces exactly first
        return (b == null || b.signum() < 0) ? null : b.sqrt(java.math.MathContext.DECIMAL128);
    }

    /** §13.4.17.25/§6.1.2 afronding — THE collapse point: round to {@code scale} decimals; a leeg (null) value
     *  rounds to leeg (so a wortel/macht/division leeg result propagates instead of NPE-ing .setScale). A
     *  {@link Breuk} is rounded on its EXACT value ({@link Breuk#naarDecimal(int,java.math.RoundingMode)} — the true
     *  division at the requested scale), so a non-terminating intermediate collapses to the exact rounded decimal
     *  ((1/3)% van 600 rounds to exactly 2, 9 maal 1/3 to exactly 3). The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsAfronding(Object x, int scale, java.math.RoundingMode mode) {
        if (x == null) return null;
        if (x instanceof Breuk b) return b.naarDecimal(scale, mode);
        return toBd(x).setScale(scale, mode);
    }

    /** §6.5 "gedeeld door (ABS)": the same Tabel 12 leegwaarde as {@link #rsGedeeld} (empty LEFT -> 0, value/empty
     *  -> leeg), but the divide truncates TOWARD ZERO at 5 decimals — a deliberate collapse, so it returns a
     *  BigDecimal even for a Breuk input (the exact rational is rounded DOWN at 5 dec). The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsGedeeldAbs(Object links, Object rechts) {
        if (links == null) return BigDecimal.ZERO;
        if (rechts == null) return null;
        return Breuk.van(links).delen(Breuk.van(rechts)).naarDecimal(5, java.math.RoundingMode.DOWN);
    }

    /** §13.4.17.14 "tot de macht" with an INTEGER exponent: BigDecimal.pow(int) (exact, unlike Math.pow's double
     *  noise above 2^53); §6.7 a leeg base -> leeg. A {@link Breuk} base stays exact (teller^n / noemer^n), so an
     *  exact rational raised to an integer power keeps its precision. The single implementation; the DRL imports it (single-runtime principle). */
    public static Object rsMacht(Object basis, int exponent) {
        if (basis == null) return null;
        if (basis instanceof Breuk b) {
            if (exponent >= 0) return verklein(Breuk.van(b.teller().pow(exponent), b.noemer().pow(exponent)));
            return verklein(Breuk.van(b.noemer().pow(-exponent), b.teller().pow(-exponent)));   // x^-n = (1/x)^n
        }
        return toBd(basis).pow(exponent);
    }

    /** §13.4.17.14 "tot de macht" with a FRACTIONAL/runtime exponent: Math.pow at double precision; any leeg
     *  operand -> leeg. The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsMachtReeel(Object basis, Object exponent) {
        if (basis == null || exponent == null) return null;
        return BigDecimal.valueOf(Math.pow(toBd(basis).doubleValue(), toBd(exponent).doubleValue()));
    }

    /** §8.1.3 elfproef: weight each digit from the last (the controlecijfer, weight -1), the next 2, 3, … and the
     *  weighted sum must be a POSITIEF geheel getal divisible by 11 and not all zeros. Admits a Numeriek (geheel
     *  getal) — leading zeros already gone — or a Tekst of digits (which preserves voor-/achterloopnullen); a null,
     *  fractional, or non-digit value is not a valid reeks -> false. The single implementation; the DRL imports it (single-runtime principle). */
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
     *  wrong-length value is false. The single implementation; the DRL imports it (single-runtime principle). */
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
     *  side's order). Accepts a {@link Breuk} value/bound (a begrensde exact division) and compares EXACTLY,
     *  returning the clamped operand as-is (Breuk stays Breuk, BigDecimal stays BigDecimal) so a non-terminating
     *  bounded value can still collapse exactly at a later afronding. The single implementation; the DRL imports it (single-runtime principle). */
    public static Object rsBegrens(Object x, Object min, Object max) {
        if (x == null) return null;
        Breuk xb = Breuk.van(x);
        if (min != null && xb.compareTo(Breuk.van(min)) < 0) return min;
        if (max != null && xb.compareTo(Breuk.van(max)) > 0) return max;
        return x;
    }

    /** §13.4.17.17/§6.9 Tabel 20 "de absolute waarde van": |leeg| = leeg. A {@link Breuk} keeps its exact value. The single implementation; the DRL imports it (single-runtime principle). */
    public static Object rsAbs(Object x) {
        if (x == null) return null;
        if (x instanceof Breuk b) return b.abs();
        return toBd(x).abs();
    }

    /** §6.x the null-safe navigated-leg read of a numeric attribute reached through a to-ONE relation INSIDE
     *  arithmetic or an eval: an absent relation reads a leegwaarde (null) — never a pattern short-circuit or an
     *  NPE — so the surrounding §6.2/§6.3 leegwaarde handling (rsNul/rsVerminderd/…) applies. The DRL constraint
     *  mirror of the exec back-end's null-safe navReader. */
    public static BigDecimal rsRelGetal(Object relatie, String attribuut) {
        return relatie == null ? null : toBd(get(relatie, attribuut));
    }

    /** §4 null-safe unit restatement (single ratio): a leeg operand restates to leeg, never an NPE on
     *  {@code .multiply} (which would abort the session). Returns BigDecimal so mvel strict-mode can resolve the
     *  helper param it feeds (a comparison grens, rsGeheelPeriode); a {@link Breuk} operand — only reachable on the
     *  value side of a cross-unit restatement of a non-terminating intermediate — COLLAPSES via toBd first (a
     *  bounded early-collapse, the cross-unit counterpart of the documented DECIMAL64 rate-conversion residual). */
    public static BigDecimal rsHerschaal(Object x, BigDecimal f) {
        return x == null ? null : toBd(x).multiply(f);
    }

    /** §4 null-safe EXACT unit restatement (×fv ÷fn at DECIMAL64, one rounding AT THE END) — the exact mirror of
     *  the value side's inline ×fv ÷fn ternary, so mnd→jr never truncates to 0.0833… A leeg operand restates to
     *  leeg. Returns BigDecimal (mvel-resolvable); a {@link Breuk} operand collapses via toBd first (see
     *  {@link #rsHerschaal}). */
    public static BigDecimal rsHerschaalExact(Object x, BigDecimal fv, BigDecimal fn) {
        return x == null ? null : toBd(x).multiply(fv).divide(fn, java.math.MathContext.DECIMAL64);
    }

    /** §5.4 text coercion for a §13.4.17.9 tekstreeks: a value's text form, null-safe (leeg -> "", not "null"); a
     *  BigDecimal via toString (528 -> "528"), a date/boolean via its toString. The single implementation; the DRL imports it (single-runtime principle). */
    public static String rsTekst(Object o) { return o == null ? "" : o.toString(); }

    /** §13.4.17.18-.24/§6.12 a DATE/TIME component (jaar/maand/dag/uur/… uit) read via one ChronoField; a leeg
     *  date -> leeg. The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsDatumDeel(Object d, java.time.temporal.TemporalField f) {
        return d == null ? null : BigDecimal.valueOf(((java.time.temporal.TemporalAccessor) d).getLong(f));
    }

    /** §13.4.17.35 "de datum met jaar/maand/dag": a LocalDate from three numeric parts; any empty part -> leeg.
     *  The single implementation; the DRL imports it (single-runtime principle). */
    public static java.time.LocalDate rsDatumMet(Object jaar, Object maand, Object dag) {
        if (jaar == null || maand == null || dag == null) return null;
        return java.time.LocalDate.of(toBd(jaar).intValue(), toBd(maand).intValue(), toBd(dag).intValue());
    }

    /** §13.4.17.36 "de datumtijd met jaar/…/milliseconde": a LocalDateTime from seven parts (the 7th, a
     *  millisecond, scaled to nanoOfSecond); any empty part -> leeg. The single implementation; the DRL imports it (single-runtime principle). */
    public static java.time.LocalDateTime rsDatumtijdMet(Object jaar, Object maand, Object dag, Object uur,
            Object minuut, Object seconde, Object milli) {
        if (jaar == null || maand == null || dag == null || uur == null || minuut == null || seconde == null || milli == null) return null;
        return java.time.LocalDateTime.of(toBd(jaar).intValue(), toBd(maand).intValue(), toBd(dag).intValue(),
                toBd(uur).intValue(), toBd(minuut).intValue(), toBd(seconde).intValue(), toBd(milli).intValue() * 1_000_000);
    }

    /** §6/§13.4.16 date arithmetic (§6 spec / Tabel 15): a datum/datumtijd plus or minus a whole number of a time
     *  unit (dag/week/maand/jaar; uur/… only on a datumtijd) -> a new date(time). Leegwaarde per Tabel 15: an empty
     *  DATUM -> empty ("niet te gebruiken"), but an empty DURATION is treated as the number 0 -> the date unchanged
     *  (as is a 0 duration, §6). The aantal is taken as whole units (date arithmetic operates on integer time
     *  units; a fractional value truncates toward zero). The single implementation; the DRL imports it (single-runtime principle). */
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
     *  the single implementation for both back-ends (single-runtime principle) — the dagsoort predicate arrives as a Predicate<LocalDate>
     *  compiled once by kern.Dagsoorten (the DRL passes it as a session global). */
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
     *  The single implementation; the DRL imports it (single-runtime principle). */
    public static java.time.LocalDateTime rsDatumTijdPlusDagsoort(java.time.LocalDateTime datum, BigDecimal aantal,
            java.util.function.Predicate<java.time.LocalDate> isDagsoort, boolean optellen) {
        if (datum == null) return null;        // leeg datum -> leeg
        java.time.LocalDate d = rsDatumPlusDagsoort(datum.toLocalDate(), aantal, isDagsoort, optellen);
        return d == null ? null : d.atTime(datum.toLocalTime());   // re-attach the time-of-day (§4 rule 2)
    }

    /** §6.10 "de (absolute) tijdsduur van A tot B in &lt;dagsoort&gt;": COUNT the dagsoort days in the half-open
     *  interval (van, tot] — consistent with §6.11's "X telt niet mee". Signed (negative when tot is earlier than
     *  van), absolute when asked; any leeg operand -> leeg (§6.10 Tabel 14). The single implementation; the DRL
     *  imports it (single-runtime principle). */
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
     *  The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsTijdsduurTijdDagsoort(java.time.LocalDateTime van, java.time.LocalDateTime tot,
            java.util.function.Predicate<java.time.LocalDate> isDagsoort, boolean absoluut) {
        return rsTijdsduurDagsoort(van == null ? null : van.toLocalDate(), tot == null ? null : tot.toLocalDate(), isDagsoort, absoluut);
    }

    /** §6.10 / §13.4.17.32 "de (absolute) tijdsduur van A tot B in &lt;tijdseenheid&gt;": the number of COMPLETE
     *  units between two dates ({@code ChronoUnit.<U>.between}, signed — A later than B gives a negative result, §6.10
     *  Tabel 14 +52/-52); absolute when asked. A kwartaal (no ChronoUnit.QUARTERS) is 3×MONTHS, §3.7. Any leeg operand
     *  -> leeg (Tabel 14), not 0. The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsTijdsduur(java.time.temporal.Temporal van, java.time.temporal.Temporal tot, String eenheid, boolean absoluut) {
        if (van == null || tot == null) return null;
        long n = "KWARTAAL".equals(eenheid)   // §3.7 kwartaal = 3 maanden (geen ChronoUnit.QUARTERS)
                ? java.time.temporal.ChronoUnit.MONTHS.between(van, tot) / 3
                : java.time.temporal.ChronoUnit.valueOf(eenheid).between(van, tot);
        return BigDecimal.valueOf(absoluut ? Math.abs(n) : n);
    }

    /** §5.3 Rekenjaar: the year of the supplied Rekendatum as a §13.2.3 geheel getal; an unset (null)
     *  rekendatum -> leeg. The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsRekenjaar(java.time.LocalDate rekendatum) {
        return rekendatum == null ? null : BigDecimal.valueOf(rekendatum.getYear());
    }

    /** §13.4.17.37 "de eerste paasdag van" a year — Gauss/Anonymous Easter algorithm; a leeg year -> leeg.
     *  The single implementation; the DRL imports it (single-runtime principle). */
    public static java.time.LocalDate rsEerstePaasdag(Object jaar) {
        if (jaar == null) return null;
        int y = toBd(jaar).intValue();
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
     *  The single implementation; the DRL imports it (single-runtime principle). */
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
     *  assigned ZERO (never null). The single implementation; the DRL imports it (single-runtime principle). */
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

    /** §8.1.6 ever-uniqueness (uchar RS ch. 5.9, decided 2026-08-20): a temporal key is an identity,
     *  not a measurement — the values a key timeline EVER carries, deduplicated (so a same-instance
     *  redeclaration of the same number claims it once). Values normalise via stripTrailingZeros so
     *  5 and 5.00 collide, matching the scalar union's coercive equality. A null or empty timeline
     *  claims nothing (an empty key never collides). */
    public static java.util.Set<BigDecimal> rsOoitWaarden(Object tijdlijn) {
        if (tijdlijn == null) return java.util.Set.of();   // an absent timeline claims nothing (an empty key never collides)
        if (!(tijdlijn instanceof List<?> l))
            // fail fast: the fact model types every temporal attribute List<Segment>, so a non-List here
            // means the generator or a caller broke that invariant — an empty claim-set would silently
            // under-report collisions (the uchar executors fault on the same shape)
            throw new IllegalStateException(
                    "rsOoitWaarden: een temporele sleutel moet een List<Segment> zijn, kreeg "
                    + tijdlijn.getClass().getName() + " — dit had onbereikbaar moeten zijn");
        if (l.isEmpty()) return java.util.Set.of();
        var out = new java.util.HashSet<BigDecimal>();
        for (Object seg : l) {
            BigDecimal w = segWaarde(seg);
            if (w != null) out.add(w.stripTrailingZeros());
        }
        return out;
    }

    /** Two key timelines EVER collide: their ever-value sets intersect — a VALUE intersection, never a
     *  time intersection, so two instances carrying the same number over non-overlapping periods
     *  collide (a number once issued stays issued; date-bound numbering is modelled explicitly). */
    public static boolean rsOoitBotst(Object a, Object b) {
        var sa = rsOoitWaarden(a);
        if (sa.isEmpty()) return false;
        for (BigDecimal w : rsOoitWaarden(b)) if (sa.contains(w)) return true;
        return false;
    }
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
     *  timeline totals 0. Kwartaal = three maanden (ChronoUnit has no quarter). The single implementation; the DRL imports it (single-runtime principle). */
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
                // fail FAST on an unknown granularity: a silent 0-weight would total 0 without a trace
                // The transpilers only ever emit the five names above.
                default: throw new IllegalArgumentException("onbekende granulariteit voor rsTijdTotaal: " + granulariteit);
            }
            som = som.add(w.multiply(BigDecimal.valueOf(n)));
        }
        return som;
    }

    /** §7.1 clip a value timeline by a condition's TRUE-period timeline: intersect each value Segment with each
     *  condition interval, emitting the overlap [max(van), min(tot)) carrying the value's waarde. A null value or
     *  condition clips to empty. The single implementation; the DRL imports it (single-runtime principle). */
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
     *  holds. A leeg (null) value or a null grens is onwaar (§5.1 ordering leegwaarde). The single implementation
     *  (single-runtime principle); the caller then rsTijdKnips the value timeline by this result. */
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
     *  empty; a null/empty condition leaves the whole value. The single implementation; the DRL imports it (single-runtime principle). */
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
     *  input contributes nothing. The single implementation; the DRL imports it (single-runtime principle). */
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
     *  The single implementation; the DRL imports it (single-runtime principle). */
    public static List<Object> rsAantalDagenIn(List<?> conditie, String granulariteit, Class<?> segCls) {
        return rsAantalDagenIn(conditie, granulariteit, null, segCls);
    }

    /** The §3.8 anchored form: when the doel rides a tijdlijn met variabel startpunt, the buckets are
     *  [anker+k·eenheid, anker+(k+1)·eenheid) for all integer k (§3.8: the startpunt is the alignment point
     *  of the whole timeline — periods exist before and after it) instead of calendar-aligned. Every bucket
     *  bound derives directly from the anker (never cursor+1, so a clamped month-end anchor — the 31st —
     *  stays consistent). A null anker (the §9.9 startpunt rule wrote leeg, or has not fired) -> empty:
     *  no anchor, no periodization; the §6.8 fout for the leeg anchor is raised by the startpunt rule's
     *  own companion. The single implementation; the DRL imports it (single-runtime principle). */
    public static List<Object> rsAantalDagenInVanaf(List<?> conditie, String granulariteit, LocalDate anker, Class<?> segCls) {
        return anker == null ? new ArrayList<>() : rsAantalDagenIn(conditie, granulariteit, anker, segCls);
    }

    private static List<Object> rsAantalDagenIn(List<?> conditie, String granulariteit, LocalDate anker, Class<?> segCls) {
        if (conditie == null) return new ArrayList<>();
        boolean jaar = "JAAR".equals(granulariteit);
        TreeMap<LocalDate, Long> tellingen = new TreeMap<>();
        for (Object o : conditie) {
            LocalDate sv = segVan(o), st = segTot(o);
            if (sv == null || st == null || !sv.isBefore(st)) continue;
            LocalDate cursor = bucketStart(anker, sv, jaar);
            while (cursor.isBefore(st)) {
                LocalDate next = bucketNext(anker, cursor, jaar);
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
            LocalDate eind = bucketNext(anker, start, jaar);
            uit.add(newSegment(segCls, start, eind, new BigDecimal(en.getValue().longValue())));
        }
        return uit;
    }

    /** Whether two timelines are segment-for-segment equal (van, tot, waarde) — the temporal analogue of the
     *  scalar idempotent-write guard (Objects.equals): Segment declares no equals, so List.equals degenerates
     *  to reference identity and every rebuild would look "changed". An anchored temporal write (whose exec
     *  pattern is class-reactive for §9.9 freshness) uses this to reach a fixpoint instead of ping-ponging
     *  with a sibling writer. The single implementation; the DRL imports it (single-runtime principle). */
    public static boolean rsTijdreeksGelijk(List<?> a, List<?> b) {
        int na = a == null ? 0 : a.size(), nb = b == null ? 0 : b.size();
        if (na != nb) return false;
        for (int i = 0; i < na; i++) {
            Object x = a.get(i), y = b.get(i);
            if (!java.util.Objects.equals(segVan(x), segVan(y)) || !java.util.Objects.equals(segTot(x), segTot(y)))
                return false;
            BigDecimal wx = segWaarde(x), wy = segWaarde(y);
            if (wx == null ? wy != null : wy == null || wx.compareTo(wy) != 0) return false;
        }
        return true;
    }

    /** The start of the bucket containing {@code d}: calendar-aligned for a null {@code anker}, else the
     *  greatest anker+k·eenheid ≤ d (k may be negative — §3.8 allows periods before the startpunt). All
     *  bounds are computed as anker.plusMonths/Years(k), so a clamped month-end anchor yields the same
     *  bucket walls for every segment. */
    private static LocalDate bucketStart(LocalDate anker, LocalDate d, boolean jaar) {
        if (anker == null) return jaar ? d.withDayOfYear(1) : d.withDayOfMonth(1);
        long k = jaar ? ChronoUnit.YEARS.between(anker, d) : ChronoUnit.MONTHS.between(anker, d);
        LocalDate c = jaar ? anker.plusYears(k) : anker.plusMonths(k);
        while (c.isAfter(d)) { k--; c = jaar ? anker.plusYears(k) : anker.plusMonths(k); }
        while (true) {
            LocalDate n = jaar ? anker.plusYears(k + 1) : anker.plusMonths(k + 1);
            if (n.isAfter(d)) break;
            k++; c = n;
        }
        return c;
    }

    /** The exclusive end of the bucket starting at {@code cursor} — calendar +1 for a null anker, else the
     *  next anker-derived wall strictly after the cursor. */
    private static LocalDate bucketNext(LocalDate anker, LocalDate cursor, boolean jaar) {
        if (anker == null) return jaar ? cursor.plusYears(1) : cursor.plusMonths(1);
        long k = jaar ? ChronoUnit.YEARS.between(anker, cursor) : ChronoUnit.MONTHS.between(anker, cursor);
        LocalDate n = jaar ? anker.plusYears(k + 1) : anker.plusMonths(k + 1);
        while (!n.isAfter(cursor)) { k++; n = jaar ? anker.plusYears(k + 1) : anker.plusMonths(k + 1); }
        return n;
    }

    /** §7.3.2/§13.4.17.58 "het tijdsevenredig deel per <maand|jaar> van <basis> gedurende de tijd dat <conditie>":
     *  per calendar bucket, value = baseValueInBucket × (true-days / total-days) at DECIMAL64 — §4.22 keeps the
     *  exact fraction, NEVER auto-rounding to the doel's decimals (§6.1.2). The base value for a bucket is the basis
     *  segment covering the bucket start; a leeg base there yields no segment. A null conditie/basis -> empty.
     *  The single implementation; the DRL imports it (single-runtime principle). */
    public static List<Object> rsTijdsevenredig(List<?> conditie, List<?> basis, String granulariteit, Class<?> segCls) {
        return rsTijdsevenredig(conditie, basis, granulariteit, null, segCls);
    }

    /** The §3.8 anchored form of {@link #rsTijdsevenredig} — variable-startpunt buckets, see
     *  {@link #rsAantalDagenInVanaf}. A null anker -> empty. */
    public static List<Object> rsTijdsevenredigVanaf(List<?> conditie, List<?> basis, String granulariteit, LocalDate anker, Class<?> segCls) {
        return anker == null ? new ArrayList<>() : rsTijdsevenredig(conditie, basis, granulariteit, anker, segCls);
    }

    private static List<Object> rsTijdsevenredig(List<?> conditie, List<?> basis, String granulariteit, LocalDate anker, Class<?> segCls) {
        List<Object> uit = new ArrayList<>();
        if (conditie == null || basis == null) return uit;
        boolean jaar = "JAAR".equals(granulariteit);
        TreeMap<LocalDate, Long> trueDagen = new TreeMap<>();
        for (Object o : conditie) {
            LocalDate sv = segVan(o), st = segTot(o);
            if (sv == null || st == null || !sv.isBefore(st)) continue;
            LocalDate cursor = bucketStart(anker, sv, jaar);
            while (cursor.isBefore(st)) {
                LocalDate next = bucketNext(anker, cursor, jaar);
                LocalDate van = sv.isAfter(cursor) ? sv : cursor;
                LocalDate tot = st.isBefore(next) ? st : next;
                long dagen = ChronoUnit.DAYS.between(van, tot);
                if (dagen > 0) trueDagen.merge(cursor, dagen, Long::sum);
                cursor = next;
            }
        }
        for (Map.Entry<LocalDate, Long> en : trueDagen.entrySet()) {
            LocalDate start = en.getKey();
            LocalDate eind = bucketNext(anker, start, jaar);
            long totaal = ChronoUnit.DAYS.between(start, eind);
            long dagen = en.getValue().longValue();
            BigDecimal w = null;
            for (Object o : basis) {
                LocalDate bv = segVan(o), bt = segTot(o);
                if (bv != null && bt != null && !bv.isAfter(start) && bt.isAfter(start)) { w = segWaarde(o); break; }
            }
            if (w == null) continue;
            // §845 tijdsevenredig deel: keep the EXACT fraction w·(dagen/totaal). When it terminates the exact
            // BigDecimal is stored (no DECIMAL64 truncation); a non-terminating pro-rata is stored at DECIMAL64
            // (a Segment holds a BigDecimal, not a Breuk) — the §6.1.2 doel afronding then rounds the value the
            // spec keeps exact. Never auto-rounds to the doel's decimals.
            Breuk deelB = Breuk.van(w).vermenigvuldigen(Breuk.van(BigInteger.valueOf(dagen), BigInteger.valueOf(totaal)));
            uit.add(newSegment(segCls, start, eind, deelB.naarDecimal()));
        }
        return uit;
    }

    /** §7.3.1 the standaard jaar/maand omrekening: restate a per-tijdseenheid RATE timeline into another rate unit by
     *  scaling every segment value by the EXACT integer unit ratio at DECIMAL64 (§4.22, no auto-round); a leeg segment
     *  stays leeg. §7.3.1 specifies a DIVISION ("120 gedeeld door de omrekenfactor 12"), so when the restatement is
     *  ÷N the caller passes {@code deel=true} (divide by the exact 12), and ×N passes {@code deel=false} — never a
     *  pre-rounded reciprocal (1/12), which would lose precision for non-clean bases. Applied to the §7.3.2
     *  tijdsevenredig BASE BEFORE proration. The single implementation; the DRL imports it (single-runtime principle). */
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
     *  Adjacent equal-value segments are coalesced (so an unchanged value carries no knip). The single implementation; the DRL imports it (single-runtime principle). */
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

    /** §5.8.2 the SOMMATIE ("de som van") of MANY temporal operands' timelines, evaluated PER PERIOD on the MERGED
     *  knip timeline of every operand — the temporal analogue of {@link #rsTijdTotaal} for a SET. This is the
     *  §5.1.4 "voor elke periode tussen twee knips" fold, but with the §5.8.2 (NOT §6.1.4 plus) leegwaarde rule:
     *  in a period, an operand that carries no value is SKIPPED (not treated as 0), and only when EVERY operand is
     *  leeg in that period is the result leeg — "een lege waarde plus een lege waarde is nog steeds een lege
     *  waarde" (§5.8.2 line 2120). {@code fallbackNul}=true is the "of 0 als die er niet zijn" clause (§5.8.2 line
     *  2128): an all-empty period then yields 0 instead of leeg. Adjacent equal-value (incl. equal-leeg) segments
     *  are coalesced, so an unchanged value carries no knip. An empty operand LIST (no elements at all) yields an
     *  empty timeline (the SOM over 0 contributors is leeg everywhere — or a single 0-span under fallbackNul is not
     *  emitted, since there is no knip to bound it). The single implementation; the DRL imports it (single-runtime principle). */
    public static List<Object> rsTijdSomAlle(List<? extends List<?>> reeksen, boolean fallbackNul, Class<?> segCls) {
        TreeMap<LocalDate, Boolean> knipSet = new TreeMap<>();
        if (reeksen != null) for (List<?> r : reeksen) for (Object o : leeg(r)) {
            LocalDate v = segVan(o), t = segTot(o);
            if (v != null && t != null && v.isBefore(t)) { knipSet.put(v, true); knipSet.put(t, true); }
        }
        List<Object> uit = new ArrayList<>();
        LocalDate prev = null, lopendeVan = null; BigDecimal lopendeWaarde = null; boolean lopendeGezet = false;
        for (LocalDate t : knipSet.keySet()) {
            if (prev != null) {
                // §5.8.2 sum the NON-leeg operands in this period; leeg iff every operand is leeg (unless fallbackNul).
                BigDecimal som = null;
                if (reeksen != null) for (List<?> r : reeksen) {
                    BigDecimal w = waardeOpDag(r, prev);
                    if (w != null) som = som == null ? w : som.add(w);
                }
                if (som == null && fallbackNul) som = BigDecimal.ZERO;
                if (!lopendeGezet) { lopendeVan = prev; lopendeWaarde = som; lopendeGezet = true; }
                else if (!gelijkeWaarde(lopendeWaarde, som)) {
                    uit.add(newSegment(segCls, lopendeVan, prev, lopendeWaarde)); lopendeVan = prev; lopendeWaarde = som;
                }
            }
            prev = t;
        }
        if (lopendeGezet) uit.add(newSegment(segCls, lopendeVan, prev, lopendeWaarde));
        return uit;
    }
    private static boolean gelijkeWaarde(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : (b != null && a.compareTo(b) == 0);
    }

    /** §8.2 over a NAVIGATED to-many temporal set ("de X van alle Y ... gedurende het gehele jaar &lt;op&gt; grens"):
     *  the whole-period check holds iff it holds for EVERY element's timeline (a forall across the accumulated set) —
     *  the §8.2 predicate applied per element via {@link #rsGeheelPeriode}, AND-ed over the set. An EMPTY set is
     *  vacuously WAAR (no element violates; consistent with rsGeheelPeriode's empty-timeline true). The single
     *  implementation; the DRL imports it (single-runtime principle). */
    public static boolean rsGeheelPeriodeAlle(List<? extends List<?>> reeksen, String granulariteit, int toets, BigDecimal grens) {
        if (reeksen == null) return true;
        for (List<?> r : reeksen) if (!rsGeheelPeriode(r, granulariteit, toets, grens)) return false;
        return true;
    }

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
     *  covering segment). The single implementation; the DRL imports it (single-runtime principle). */
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
     *  empty sum is a leegwaarde). Exact add (no MathContext); the single implementation, imported by the DRL (single-runtime principle). */
    public static BigDecimal rsDimSom(Map<?, ?> m, List<?> keys) {
        BigDecimal acc = null;
        for (Object k : keys) {
            BigDecimal v = m == null ? null : (BigDecimal) m.get(k);
            if (v != null) acc = acc == null ? v : acc.add(v);
        }
        return acc;
    }
    /** §5.8.5 minimum of a dimensioned attribute's per-label values over the selected keys (null-skipping; null when
     *  all-empty). The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsDimMin(Map<?, ?> m, List<?> keys) {
        BigDecimal acc = null;
        for (Object k : keys) {
            BigDecimal v = m == null ? null : (BigDecimal) m.get(k);
            if (v != null && (acc == null || v.compareTo(acc) < 0)) acc = v;
        }
        return acc;
    }
    /** §5.8.5 maximum of a dimensioned attribute's per-label values over the selected keys. The single implementation; the DRL imports it (single-runtime principle). */
    public static BigDecimal rsDimMax(Map<?, ?> m, List<?> keys) {
        BigDecimal acc = null;
        for (Object k : keys) {
            BigDecimal v = m == null ? null : (BigDecimal) m.get(k);
            if (v != null && (acc == null || v.compareTo(acc) > 0)) acc = v;
        }
        return acc;
    }

    // ---- identifier convention: DELEGATES to the single naming authority kern.Namen.ident ----
    /** The PascalCase bean-member/class name for a RegelSpraak name — now a thin delegate to
     *  {@link io.github.yhavinga.regelspraak.transpiler.kern.Namen#ident(String, boolean)}, the ONE authority the
     *  fact-model generator and the DRL back-end also derive from. Delegating (instead of a whitespace-only
     *  twin split) is what routes BOTH back-ends through the single naming authority: the exec/runtime reads
     *  now split a name on EVERY non-letter/digit run exactly as the beans were generated, so a koppelteken
     *  attribute ("de datum-tijd voor het berekenen…" -> {@code DatumTijdVoorHetBerekenen…}) resolves the
     *  same getter the FactModelGenerator emitted — the old {@code \s+}-only split left the "-" in and
     *  targeted a non-existent {@code getDatum-tijd…}. No lidwoord stripping (Namen.ident carries the full
     *  name), matching the bean the generator wrote; the resolver already delivers declared names bare. */
    public static String pascal(String naam) {
        return io.github.yhavinga.regelspraak.transpiler.kern.Namen.ident(naam, true);
    }
}
