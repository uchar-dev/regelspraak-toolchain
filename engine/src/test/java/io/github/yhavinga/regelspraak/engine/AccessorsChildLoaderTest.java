package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.runtime.Accessors;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the per-owner {@link java.lang.invoke.MethodHandles.Lookup} in {@link Accessors} (card fase 3): a TYPED
 * accessor must LMF-link against a fact bean loaded in a CHILD classloader (the shape the {@link ScenarioRunner}
 * runs — {@link BeanCompiler} serves the generated beans from a child-first loader), not only against a
 * parent/app-classpath bean.
 *
 * <p><b>RED form.</b> Before the fix, {@link Accessors} spun its LambdaMetafactory hidden accessor class off a static
 * parent-bound {@code LOOKUP}, so the hidden class landed in the PARENT nest and could not resolve the child-loaded
 * owner: {@code bc.accept(instance, value)} / {@code raw.apply(instance)} threw
 * {@code java.lang.NoClassDefFoundError: io/github/yhavinga/regelspraak/facts/Widget} at the first invoke. Revert the fix (put the
 * static {@code LOOKUP} + {@code LambdaMetafactory.metafactory} back into {@code buildGetter}/{@code buildSetter}) and
 * {@link #typed_accessor_links_against_a_child_classloader_bean()} fails with that NoClassDefFoundError.
 *
 * <p><b>Why NOT a per-owner LambdaMetafactory.</b> A {@code privateLookupIn(owner, LOOKUP)} teleported into a
 * child-loaded owner is full-access but NOT {@code ORIGINAL}/full-privilege (it crossed a module), and
 * {@code LambdaMetafactory} rejects such a lookup with {@code LambdaConversionException: Invalid caller}. So the fix
 * binds the per-owner MethodHandle to the {@code Function}/{@code BiConsumer} via {@link java.lang.invoke.MethodHandleProxies}
 * instead — cross-loader-safe, still reflection-free at call time — no fallback, so {@code lmfFallbacks()} delta stays 0.
 */
public class AccessorsChildLoaderTest {

    /** A minimal fact bean in {@code io.github.yhavinga.regelspraak.facts} with a BigDecimal attribute and a boolean kenmerk —
     *  the two accessor shapes the fix must reach across the loader boundary (LMF object-setter + primitive setter).
     *  The map KEY is the SIMPLE name; {@link BeanCompiler} prepends {@link FactModelGenerator#PAKKET}. */
    private static ClassLoader childLoaderWithWidget() {
        String src = "package " + FactModelGenerator.PAKKET + ";\n"
                + "public class Widget {\n"
                + "  private java.math.BigDecimal saldo;\n"
                + "  private boolean actief;\n"
                + "  public java.math.BigDecimal getSaldo() { return saldo; }\n"
                + "  public void setSaldo(java.math.BigDecimal v) { this.saldo = v; }\n"
                + "  public boolean isActief() { return actief; }\n"
                + "  public void setActief(boolean v) { this.actief = v; }\n"
                + "}\n";
        return BeanCompiler.compileer(Map.of("Widget", src));
    }

    @Test
    void typed_accessor_links_against_a_child_classloader_bean() throws Exception {
        ClassLoader child = childLoaderWithWidget();
        Class<?> widget = Class.forName(FactModelGenerator.PAKKET + ".Widget", true, child);
        // sanity: the bean really was compiled + child-loaded (a DIFFERENT loader than this test class's)
        assertNotNull(widget);
        assertSame(child, widget.getClassLoader());
        Object instance = widget.getConstructor().newInstance();

        int before = Accessors.lmfFallbacks();

        // BigDecimal attribute — the LMF object-setter/getter branch. THIS is the RED line pre-fix
        // (bc.accept / raw.apply -> NoClassDefFoundError: io/github/yhavinga/regelspraak/facts/Widget).
        BiConsumer<Object, Object> setSaldo = Accessors.setter(widget, "saldo");
        Function<Object, Object> getSaldo = Accessors.getter(widget, "saldo");
        setSaldo.accept(instance, new BigDecimal("42.75"));
        assertEquals(new BigDecimal("42.75"), getSaldo.apply(instance),
                "typed accessor did not round-trip a BigDecimal on a child-loaded bean");

        // boolean kenmerk — the primitive-setter direct-handle branch, also unreflected via the owner Lookup.
        BiConsumer<Object, Object> setActief = Accessors.setter(widget, "actief");
        Function<Object, Object> getActief = Accessors.getter(widget, "actief");
        setActief.accept(instance, Boolean.TRUE);
        assertEquals(Boolean.TRUE, getActief.apply(instance),
                "primitive setter did not round-trip a boolean on a child-loaded bean");

        // the whole point: the TYPED path linked — no reflective fallback for any of these child-bean accessors.
        assertEquals(before, Accessors.lmfFallbacks(),
                "a child-loaded bean accessor fell off the typed LMF path onto the reflective bridge");
    }

    /** A PARENT-classpath bean must keep working with zero fallback — the owner-Lookup change must not regress the
     *  path that already worked (this test class is loaded by the app/parent loader). */
    public static class ParentBean {
        private BigDecimal bedrag;
        private boolean geldig;
        public BigDecimal getBedrag() { return bedrag; }
        public void setBedrag(BigDecimal v) { this.bedrag = v; }
        public boolean isGeldig() { return geldig; }
        public void setGeldig(boolean v) { this.geldig = v; }
    }

    @Test
    void parent_classpath_bean_still_links_with_no_fallback() {
        int before = Accessors.lmfFallbacks();
        BiConsumer<Object, Object> setBedrag = Accessors.setter(ParentBean.class, "bedrag");
        Function<Object, Object> getBedrag = Accessors.getter(ParentBean.class, "bedrag");
        BiConsumer<Object, Object> setGeldig = Accessors.setter(ParentBean.class, "geldig");
        Function<Object, Object> getGeldig = Accessors.getter(ParentBean.class, "geldig");

        ParentBean b = new ParentBean();
        setBedrag.accept(b, new BigDecimal("3.14"));
        assertEquals(new BigDecimal("3.14"), getBedrag.apply(b));
        setGeldig.accept(b, Boolean.TRUE);
        assertEquals(Boolean.TRUE, getGeldig.apply(b));
        assertEquals(before, Accessors.lmfFallbacks(), "a parent-classpath accessor regressed off the typed path");
    }
}
