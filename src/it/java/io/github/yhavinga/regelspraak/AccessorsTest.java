package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.runtime.Accessors;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the typed per-objecttype accessors (migration option B / the Phase-H codegen optimization): the
 * {@link Accessors} LMF path is actually ENGAGED (no silent fallback to the reflective bridge — which would
 * still pass the differential yet deliver none of the speed) and stays behaviour-identical to
 * {@code RegelSpraakRuntime.get/set} across every POJO accessor shape the model uses: a BigDecimal attribute,
 * a boolean kenmerk ({@code is}/{@code set}), a reference field, and null-owner safety.
 */
public class AccessorsTest {

    /** A dedicated bean the differential never touches, so the accessor builds happen INSIDE this test and the
     *  {@link Accessors#lmfFallbacks} delta is exact. Mirrors the fact-POJO shapes. */
    public static class Bean {
        private BigDecimal saldo;
        private boolean vip;
        private Bean partner;
        public BigDecimal getSaldo() { return saldo; }
        public void setSaldo(BigDecimal v) { this.saldo = v; }
        public boolean isVip() { return vip; }
        public void setVip(boolean v) { this.vip = v; }
        public Bean getPartner() { return partner; }
        public void setPartner(Bean v) { this.partner = v; }
    }

    @Test
    void typed_accessors_are_lmf_backed_and_behaviour_identical() {
        int before = Accessors.lmfFallbacks();

        Function<Object, Object> getSaldo = Accessors.getter(Bean.class, "saldo");
        BiConsumer<Object, Object> setSaldo = Accessors.setter(Bean.class, "saldo");
        Function<Object, Object> getVip = Accessors.getter(Bean.class, "vip");      // is-getter, boxes boolean
        BiConsumer<Object, Object> setVip = Accessors.setter(Bean.class, "vip");    // unboxes Boolean -> boolean
        Function<Object, Object> getPartner = Accessors.getter(Bean.class, "partner");
        BiConsumer<Object, Object> setPartner = Accessors.setter(Bean.class, "partner");

        // every one of these ordinary POJO accessors must be LMF-bound, not a reflective fallback
        assertEquals(before, Accessors.lmfFallbacks(), "an ordinary POJO accessor fell back off the typed LMF path");

        Bean b = new Bean();
        setSaldo.accept(b, new BigDecimal("12.50"));
        assertEquals(new BigDecimal("12.50"), getSaldo.apply(b));     // numeric attribute round-trips (scale kept)
        setVip.accept(b, Boolean.TRUE);
        assertEquals(Boolean.TRUE, getVip.apply(b));                  // boolean kenmerk round-trips through box/unbox
        Bean p = new Bean();
        setPartner.accept(b, p);
        assertSame(p, getPartner.apply(b));                          // reference field
        assertNull(getSaldo.apply(null));                            // null owner reads leeg, never NPE
        assertNull(getVip.apply(null));
        setPartner.accept(b, null);                                  // null into a reference setter clears it
        assertNull(getPartner.apply(b));
    }

    @Test
    void missing_getter_fails_fast_at_build() {
        assertThrows(IllegalStateException.class, () -> Accessors.getter(Bean.class, "nietBestaand"));
    }

    /** Corpus-wide: every getter/setter on every real fact bean (the actual objecttype shapes the model drives —
     *  BigDecimal & boolean attributes, Map&lt;String,BigDecimal&gt; dimensioned cells, List&lt;Segment&gt;
     *  timelines, reference roles) must land on the TYPED path (LMF, or the direct-handle path for primitive
     *  setters) — never a genuine LMF binding fallback. {@code lmfFallbacks()} is global, so this catches a fallback
     *  whether it happened here or already during the differential run. */
    @Test
    void every_fact_bean_accessor_is_on_the_typed_path() throws Exception {
        int built = 0;
        for (Class<?> bean : factBeans()) {
            for (Method m : bean.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                String n = m.getName();
                if (m.getParameterCount() == 0 && m.getReturnType() != void.class
                        && (n.startsWith("get") || n.startsWith("is")) && !n.equals("getClass")) {
                    String suffix = n.substring(n.startsWith("is") ? 2 : 3);
                    if (!suffix.isEmpty()) { Accessors.getter(bean, decap(suffix)); built++; }
                } else if (m.getParameterCount() == 1 && m.getReturnType() == void.class && n.startsWith("set")) {
                    String suffix = n.substring(3);
                    if (!suffix.isEmpty()) { Accessors.setter(bean, decap(suffix)); built++; }
                }
            }
        }
        assertTrue(built > 30, "expected to exercise many fact-bean accessors, got " + built);
        assertEquals(0, Accessors.lmfFallbacks(),
                "a real fact-bean accessor fell off the typed LMF path (Map/List/BigDecimal/ref shape)");
    }

    private static String decap(String s) { return Character.toLowerCase(s.charAt(0)) + s.substring(1); }

    /** All concrete fact POJOs in {@code io.github.yhavinga.regelspraak.facts}, read off the test classpath directory. */
    private static List<Class<?>> factBeans() throws Exception {
        String pkg = "io.github.yhavinga.regelspraak.facts";
        URL url = AccessorsTest.class.getClassLoader().getResource(pkg.replace('.', '/'));
        File dir = new File(url.toURI());
        List<Class<?>> out = new ArrayList<>();
        String[] entries = dir.list();
        if (entries != null) for (String f : entries)
            if (f.endsWith(".class") && !f.contains("$"))
                out.add(Class.forName(pkg + "." + f.substring(0, f.length() - 6)));
        return out;
    }
}
