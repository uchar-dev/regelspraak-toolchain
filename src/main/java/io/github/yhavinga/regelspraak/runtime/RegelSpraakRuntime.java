package io.github.yhavinga.regelspraak.runtime;

import java.lang.reflect.Method;
import java.math.BigDecimal;

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
