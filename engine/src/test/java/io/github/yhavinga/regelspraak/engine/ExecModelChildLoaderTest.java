package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import org.drools.modelcompiler.KieBaseBuilder;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins card fase 1 (CLASSLOADER THREADING): the exec-model back-end must serve a fact bean loaded in a CHILD
 * classloader (the {@link ScenarioRunner}/BeslisserKern shape — {@link FactModelGenerator} generates bean sources,
 * {@link BeanCompiler} compiles them into a child-first loader).
 *
 * <p><b>RED form.</b> Before the fix, {@link ExecModelTranspiler#factClass} resolved beans via a ZERO-arg
 * {@code Class.forName(FACTS + "." + Pascal)} — bound to the transpiler's OWN classloader. A child-loaded bean is
 * invisible there, so {@code factClass(...)} returns null → the gelijkstelling rule records a "geen fact-class" SKIP
 * → NO rule is emitted → the computed attribute (prijs) stays null after firing. Reverting the {@code factClass} body
 * to the zero-arg form fails {@link #exec_model_serves_a_child_classloader_bean()} on the "geen fact-class" skip
 * assertion (and, were the model built, on a null computed value).
 */
public class ExecModelChildLoaderTest {

    /** A minimal fact-local gelijkstelling: prijs := basis (same unit, so the computed value equals the input). One
     *  objecttype, no globals, no navigation — the smallest rule that still needs {@code factClass(Heffing)}. */
    static final String SRC = """
            Objecttype de Heffing
                de prijs Numeriek (geheel getal) met eenheid EUR;
                de basis Numeriek (geheel getal) met eenheid EUR;
            Regel r
                geldig altijd
                    De prijs van een Heffing moet gesteld worden op de basis van een Heffing.
            """;

    @Test
    void exec_model_serves_a_child_classloader_bean() throws Exception {
        RegelSpraakModel model = Resolver.resolve(SRC);
        assertTrue(model.errors().isEmpty(), () -> "bron moet schoon resolven: " + model.errors());

        // The CHILD loader — same shape as ScenarioRunner L137-138 (FactModelGenerator + BeanCompiler).
        Map<String, String> bronnen = FactModelGenerator.genereer(model);
        ClassLoader child = BeanCompiler.compileer(bronnen);

        Class<?> heffing = Class.forName(FactModelGenerator.PAKKET + ".Heffing", true, child);
        assertNotNull(heffing);
        assertSame(child, heffing.getClassLoader(), "de bean moet in de CHILD-loader geladen zijn");

        // The fix: build the exec-model against the child loader. Pre-fix factClass(Heffing) is null → a
        // "geen fact-class" skip; the assertion below is the RED guard.
        ExecModelTranspiler em = ExecModelTranspiler.build(model, child);
        assertTrue(em.overgeslagen().stream().noneMatch(s -> s.contains("geen fact-class")),
                () -> "exec-model sloeg de child-geladen bean over (card fase 1 regressie): " + em.overgeslagen());

        // Build the KieBase with TCCL=child so Drools-internal linking sees the bean loader.
        ClassLoader vorige = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(child);
        KieBase kb;
        try { kb = KieBaseBuilder.createKieBaseFromModel(em.model()); }
        finally { Thread.currentThread().setContextClassLoader(vorige); }

        // A child-loaded Heffing with basis=10; fire; read prijs back — must be 10 (prijs := basis, same unit).
        Object bean = heffing.getConstructor().newInstance();
        heffing.getMethod("setBasis", BigDecimal.class).invoke(bean, new BigDecimal(10));

        ClassLoader vorige2 = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(child);
        KieSession s = kb.newKieSession();
        try {
            s.insert(bean);
            int fired = s.fireAllRules(10_000);
            assertTrue(fired >= 1, "de gelijkstelling-regel moet vuren op de child-geladen bean");
        } finally {
            s.dispose();
            Thread.currentThread().setContextClassLoader(vorige2);
        }

        Object prijs = heffing.getMethod("getPrijs").invoke(bean);
        assertNotNull(prijs, "prijs bleef null — de regel vuurde niet (child-bean was onzichtbaar)");
        assertEquals(0, new BigDecimal(10).compareTo((BigDecimal) prijs),
                () -> "prijs := basis: verwacht 10, kreeg " + prijs);
    }
}
