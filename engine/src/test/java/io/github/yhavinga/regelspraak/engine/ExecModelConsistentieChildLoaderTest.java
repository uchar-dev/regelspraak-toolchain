package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import org.drools.modelcompiler.KieBaseBuilder;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins FIX C (Inconsistentie CHILD-loader threading): a §9.5 consistentieregel that INSERTS an Inconsistentie must fire
 * over CHILD-loaded generated beans (the {@link ScenarioRunner}/BeslisserKern shape — {@link FactModelGenerator} +
 * {@link BeanCompiler} compile bean sources, incl. the {@code Inconsistentie} bean, into a child-first loader).
 *
 * <p>The consistentie is the toka-30 "geen rondvlucht" shape — a NON-numeriek (Tekst) attr-vs-attr ONGELIJK criterion
 * ("de vertrek moet ongelijk zijn aan de bestemming"), which the exec back-end lowers via LIFT A. A rondvlucht
 * (vertrek == bestemming) violates it, so the companion rule inserts an Inconsistentie.
 *
 * <p><b>RED form.</b> Before FIX C, {@link io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime#newInconsistentie} resolved
 * the {@code io.github.yhavinga.regelspraak.facts.Inconsistentie} bean via a bare {@code Class.forName(fqcn)} — bound to
 * RegelSpraakRuntime's OWN (parent) loader. The CHILD-generated bean is invisible there, so the insert throws
 * {@code IllegalStateException} (caused by {@code ClassNotFoundException}) at fire time, aborting {@code fireAllRules}.
 * The fix resolves the bean via the thread context classloader (set to the child around the fire). Reverting
 * {@code newInconsistentie} to the bare {@code Class.forName(fqcn)} makes this test throw ClassNotFound.
 */
public class ExecModelConsistentieChildLoaderTest {

    /** toka-30 shape: a Tekst attr-vs-attr ONGELIJK consistentie. A rondvlucht (vertrek == bestemming) is a violation
     *  -> the companion inserts an Inconsistentie. No globals, no navigation — the smallest §9.5 rule that inserts. */
    static final String SRC = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
            Regel geen rondvlucht
                geldig altijd
                    De vertrek van een Route moet ongelijk zijn aan de bestemming van een Route.
            """;

    @Test
    void exec_model_consistentie_inserts_inconsistentie_over_child_beans() throws Exception {
        RegelSpraakModel model = Resolver.resolve(SRC);
        assertTrue(model.errors().isEmpty(), () -> "bron moet schoon resolven: " + model.errors());

        // The CHILD loader — same shape as ScenarioRunner (FactModelGenerator + BeanCompiler). This compiles BOTH the
        // Route bean AND the Inconsistentie bean into the child-first loader.
        Map<String, String> bronnen = FactModelGenerator.genereer(model);
        ClassLoader child = BeanCompiler.compileer(bronnen);

        Class<?> route = Class.forName(FactModelGenerator.PAKKET + ".Route", true, child);
        Class<?> inconsistentie = Class.forName(FactModelGenerator.PAKKET + ".Inconsistentie", true, child);
        assertNotNull(route);
        assertSame(child, route.getClassLoader(), "de Route-bean moet in de CHILD-loader geladen zijn");
        assertSame(child, inconsistentie.getClassLoader(), "de Inconsistentie-bean moet in de CHILD-loader geladen zijn");

        // Build the exec-model + KieBase against the child loader (TCCL=child so Drools-internal linking sees the beans).
        ExecModelTranspiler em = ExecModelTranspiler.build(model, child);
        assertTrue(em.overgeslagen().isEmpty(),
                () -> "de exec moet de Tekst attr-vs-attr consistentie lowern (LIFT A), kreeg skip: " + em.overgeslagen());

        ClassLoader vorige = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(child);
        KieBase kb;
        try { kb = KieBaseBuilder.createKieBaseFromModel(em.model()); }
        finally { Thread.currentThread().setContextClassLoader(vorige); }

        // A rondvlucht: vertrek == bestemming == "A" -> ONGELIJK criterion onwaar -> a violation -> Inconsistentie.
        Object rondvlucht = route.getConstructor().newInstance();
        route.getMethod("setVertrek", String.class).invoke(rondvlucht, "A");
        route.getMethod("setBestemming", String.class).invoke(rondvlucht, "A");

        ClassLoader vorige2 = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(child);   // FIX C reads TCCL to resolve the Inconsistentie bean
        long inserted;
        KieSession s = kb.newKieSession();
        try {
            s.insert(rondvlucht);
            s.fireAllRules(10_000);   // pre-fix: IllegalStateException (ClassNotFound Inconsistentie) aborts here
            inserted = s.getObjects().stream().filter(inconsistentie::isInstance).count();
        } finally {
            s.dispose();
            Thread.currentThread().setContextClassLoader(vorige2);
        }

        assertEquals(1L, inserted,
                "de §9.5 consistentie moet een CHILD-geladen Inconsistentie inserten op de rondvlucht (FIX C)");
    }
}
