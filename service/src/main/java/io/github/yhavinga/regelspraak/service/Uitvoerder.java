package io.github.yhavinga.regelspraak.service;

import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-request executor: given an entry point's compiled {@link KieBase} (built once at startup over the
 * shared bean classloader, immutable thereafter) and a mapped {@link Wereld}, it opens a FRESH
 * {@link KieSession}, sets the parameter globals (the descriptor defaults, overridden by the request), sets the
 * rekendatum global when the request carried one, inserts every bean, fires to quiescence, and returns the
 * fired beans + the {@code Inconsistentie} facts for the response builder. Session-per-request; the KieBase is
 * shared and immutable, so this is thread-safe by construction.
 *
 * <p><b>Back-end</b>: the DRL back-end, exactly the {@link io.github.yhavinga.regelspraak.engine.ScenarioRunner} recipe.
 * That is the pinned path for gate G2 — the 30 golden scenariotoetsen reproduce over the DRL back-end
 * (ScenarioRunner.run L79-107 and its L88-93 comment: the exec-model back-end cannot bind its
 * LambdaMetafactory fact-accessors against a foreign child bean classloader, and it SKIPs / fails to compile
 * several golden scopes). The fire is bounded with the same 100_000 cap the engine's own session uses
 * (RegelSpraakEngine.Session#fire), turning a non-fixpoint kringverwijzing into an exception rather than a hang.
 */
public final class Uitvoerder {

    private final KieBase kieBase;
    private final Map<String, Object> defaultGlobals;   // descriptor parameter defaults (global name -> value)
    /** §9.8 dagsoort predicates the entrypoint's DRL declares as globals — the session builder must supply
     *  them per {@link io.github.yhavinga.regelspraak.transpiler.DrlTranspiler#dagsoortGlobalen()} (like DroolsExecutionTest
     *  does), the analog of the parameter globals. Empty for a scope that references no dagsoort. */
    private final Map<String, java.util.function.Predicate<LocalDate>> dagsoortGlobalen;
    private final ClassLoader beanLoader;               // the shared child bean classloader

    public Uitvoerder(KieBase kieBase, Map<String, Object> defaultGlobals,
                      Map<String, java.util.function.Predicate<LocalDate>> dagsoortGlobalen, ClassLoader beanLoader) {
        this.kieBase = kieBase;
        this.defaultGlobals = defaultGlobals == null ? Map.of() : defaultGlobals;
        this.dagsoortGlobalen = dagsoortGlobalen == null ? Map.of() : dagsoortGlobalen;
        this.beanLoader = beanLoader;
    }

    /** The result of a fire: the beans by id (mutated in place), the activations fired, the inconsistenties. */
    public record Uitkomst(Map<String, Object> beans, int gevuurd, List<Object> inconsistenties) {}

    /** Fire the entry point's rules over {@code wereld} and return the mutated beans + inconsistenties. */
    public Uitkomst vuur(Wereld wereld) {
        // descriptor defaults first, request parameters override
        Map<String, Object> globals = new LinkedHashMap<>(defaultGlobals);
        globals.putAll(wereld.globals());

        ClassLoader vorige = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(beanLoader);   // Drools resolves fact types via the TCCL
        KieSession sessie = kieBase.newKieSession();
        try {
            for (var e : dagsoortGlobalen.entrySet()) setGlobalVeilig(sessie, e.getKey(), e.getValue());
            for (var e : globals.entrySet()) setGlobalVeilig(sessie, e.getKey(), e.getValue());
            LocalDate rd = wereld.rekendatum();
            if (rd != null) setGlobalVeilig(sessie, "rekendatum", rd);
            for (Object bean : wereld.beans().values()) sessie.insert(bean);
            int n = sessie.fireAllRules(100_000);
            if (n >= 100_000)
                throw new IllegalStateException("regel-herberekening bereikt geen fixpunt binnen 100000 activaties"
                        + " — vermoedelijk een kringverwijzing tussen berekeningen (zie de resolver-WARNING)");
            List<Object> inc = leesInconsistenties(sessie);
            return new Uitkomst(wereld.beans(), n, inc);
        } finally {
            sessie.dispose();
            Thread.currentThread().setContextClassLoader(vorige);
        }
    }

    /** setGlobal tolerating a global the compiled DRL did not declare (a parameter no scoped rule reads is not
     *  registered as a global — writing it would throw; harmless to skip). Mirrors ScenarioRunner. */
    private static void setGlobalVeilig(KieSession sessie, String naam, Object waarde) {
        try { sessie.setGlobal(naam, waarde); }
        catch (RuntimeException onbekend) { /* global not declared: no scoped rule reads it */ }
    }

    private List<Object> leesInconsistenties(KieSession sessie) {
        try {
            Class<?> inc = Class.forName("io.github.yhavinga.regelspraak.facts.Inconsistentie", true, beanLoader);
            return List.copyOf(sessie.getObjects(inc::isInstance));
        } catch (ClassNotFoundException e) {
            return List.of();
        }
    }
}
