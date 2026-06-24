package io.github.yhavinga.regelspraak.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Typed per-objecttype fact accessors — the executable-model back-end's "codegen" path (migration option B /
 * the deferred Phase-H optimization). Where {@code ExecModelTranspiler} knows an objecttype's fact CLASS at
 * model-build time, it resolves a getter/setter to a TYPED lambda via {@link LambdaMetafactory}: the JVM spins a
 * hidden accessor class that calls {@code bean.get<Prop>()} / {@code bean.set<Prop>(v)} DIRECTLY, the same
 * direct-dispatch a Kogito-generated executable model uses. Two wins over the reflective bridge
 * ({@link RegelSpraakRuntime#get}/{@link RegelSpraakRuntime#set}):
 *
 * <ul>
 *   <li><b>fire-time speed</b> — {@code apply()}/{@code accept()} inline to a direct virtual call, no reflection;</li>
 *   <li><b>build-time fail-fast</b> — a missing getter throws when the transpiler RESOLVES the accessor (model
 *       build), not on the first fire of the rule.</li>
 * </ul>
 *
 * Sites whose runtime class is genuinely dynamic (navigation targets of unknown type, {@code Segment} /
 * {@code Inconsistentie} fields, multi-hop joins) keep the {@link RegelSpraakRuntime} cached-MethodHandle bridge,
 * which is itself reflection-free after the first resolve. Resolution rules (get-then-is for reads; the unique
 * single-arg {@code set<Prop>} for writes) mirror the bridge exactly, so the two paths stay behaviour-identical.
 */
public final class Accessors {
    private Accessors() {}

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Map<Class<?>, Map<String, Function<Object, Object>>> GETTERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, BiConsumer<Object, Object>>> SETTERS = new ConcurrentHashMap<>();

    /** How many accessor builds fell back to the reflective bridge because LambdaMetafactory could not bind the
     *  signature. Stays 0 for ordinary POJO getters/setters — a regression in the typed path makes it climb. A
     *  test/diagnostics hook; never read by production code. */
    private static volatile int lmfFallbacks = 0;
    /** Diagnostics: count of accessor builds that fell off the typed LMF path onto the reflective bridge. */
    public static int lmfFallbacks() { return lmfFallbacks; }

    /** A null-safe typed getter for {@code owner.get<Prop>()} / {@code is<Prop>()}: {@code apply(null) -> null}
     *  (a null to-one navigation reads leeg, matching {@link RegelSpraakRuntime#get}). Throws at THIS call (model
     *  build time) when the bean has no such getter. */
    public static Function<Object, Object> getter(Class<?> owner, String prop) {
        return GETTERS.computeIfAbsent(owner, c -> new ConcurrentHashMap<>())
                      .computeIfAbsent(prop, p -> buildGetter(owner, p));
    }

    /** A typed setter for {@code owner.set<Prop>(v)}. Falls back to the value-type-directed reflective bridge when
     *  the bean has 0 or &gt;1 single-arg {@code set<Prop>} (so overload resolution stays identical to
     *  {@link RegelSpraakRuntime#set}). */
    public static BiConsumer<Object, Object> setter(Class<?> owner, String prop) {
        return SETTERS.computeIfAbsent(owner, c -> new ConcurrentHashMap<>())
                      .computeIfAbsent(prop, p -> buildSetter(owner, p));
    }

    private static Function<Object, Object> buildGetter(Class<?> owner, String prop) {
        Method m = getterMethod(owner, prop);
        if (m == null) throw new IllegalStateException("geen getter voor '" + prop + "' op " + owner.getName());
        MethodHandle mh;
        try { mh = LOOKUP.unreflect(m); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        try {
            CallSite cs = LambdaMetafactory.metafactory(
                    LOOKUP, "apply",
                    MethodType.methodType(Function.class),                 // factory: () -> Function (no captures)
                    MethodType.methodType(Object.class, Object.class),     // Function.apply erased
                    mh,                                                    // impl: (Owner)Ret
                    mh.type());                                            // specialized: (Owner)Ret
            @SuppressWarnings("unchecked")
            Function<Object, Object> raw = (Function<Object, Object>) cs.getTarget().invoke();
            return o -> o == null ? null : raw.apply(o);                   // preserve get's null-bean -> null
        } catch (Throwable t) {
            // a signature LMF cannot bind -> the cached reflective bridge (still correct, just untyped)
            lmfFallbacks++;
            return o -> RegelSpraakRuntime.get(o, prop);
        }
    }

    private static BiConsumer<Object, Object> buildSetter(Class<?> owner, String prop) {
        Method m = uniqueSetterMethod(owner, prop);
        if (m == null) return (o, v) -> RegelSpraakRuntime.set(o, prop, v);   // 0 or >1 overloads: value-directed bridge
        MethodHandle mh;
        try { mh = LOOKUP.unreflect(m); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        if (m.getParameterTypes()[0].isPrimitive()) {
            // LambdaMetafactory cannot adapt the BiConsumer's Object parameter to a PRIMITIVE setter parameter (a
            // boolean kenmerk's setX(boolean)); invoke the direct cached handle, which unboxes at the call. Still
            // typed + reflection-free (NOT an lmfFallback — a principled path, not a binding regression).
            final MethodHandle fmh = mh;
            return (o, v) -> {
                try { fmh.invoke(o, v); }
                catch (RuntimeException | Error e) { throw e; }
                catch (Throwable t) { throw new IllegalStateException("setter '" + prop + "' faalde op " + owner.getName(), t); }
            };
        }
        try {
            CallSite cs = LambdaMetafactory.metafactory(
                    LOOKUP, "accept",
                    MethodType.methodType(BiConsumer.class),                       // factory: () -> BiConsumer
                    MethodType.methodType(void.class, Object.class, Object.class),  // BiConsumer.accept erased
                    mh,                                                            // impl: (Owner, ParamT)void
                    mh.type());                                                    // specialized: (Owner, ParamT)void
            @SuppressWarnings("unchecked")
            BiConsumer<Object, Object> bc = (BiConsumer<Object, Object>) cs.getTarget().invoke();
            return bc;
        } catch (Throwable t) {
            lmfFallbacks++;
            return (o, v) -> RegelSpraakRuntime.set(o, prop, v);
        }
    }

    private static Method getterMethod(Class<?> owner, String prop) {
        String p = RegelSpraakRuntime.pascal(prop);
        for (String pre : new String[]{"get", "is"}) {
            try { return owner.getMethod(pre + p); } catch (NoSuchMethodException ignore) { }
        }
        return null;
    }
    private static Method uniqueSetterMethod(Class<?> owner, String prop) {
        String name = "set" + RegelSpraakRuntime.pascal(prop);
        Method only = null; int n = 0;
        for (Method m : owner.getMethods())
            if (m.getName().equals(name) && m.getParameterCount() == 1) { only = m; n++; }
        return n == 1 ? only : null;
    }
}
