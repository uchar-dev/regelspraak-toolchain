package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.kern.Dekking;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import io.github.yhavinga.regelspraak.model.Parameterset;
import io.github.yhavinga.regelspraak.transpiler.kern.Namen;

import org.drools.modelcompiler.KieBaseBuilder;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The shippable RegelSpraak runtime: compile a RegelSpraak v2.3.0 source into a ready-to-fire Drools engine and
 * run rules over facts. This is the CONSUMER side of the executable-model split — it adds the Drools runtime on
 * top of the lean transpiler (the {@code regelspraak-parser} module builds the {@link org.drools.model.Model};
 * this module fires it). It uses ONLY the executable-model back-end ({@link ExecModelTranspiler}), the shippable
 * path — never the DRL-string oracle.
 *
 * <pre>{@code
 *   RegelSpraakEngine engine = RegelSpraakEngine.compile(regelSpraakSource);   // resolve + transpile + KieBase
 *   try (RegelSpraakEngine.Session s = engine.newSession()) {
 *       s.insert(persoon).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
 *   }                                                                          // persoon is mutated in place
 * }</pre>
 *
 * A Quarkus application holds a {@code RegelSpraakEngine} as an application-scoped bean (compiled once) and opens
 * a {@link Session} per request. The fact classes the Model references must be on the classpath in package
 * {@code io.github.yhavinga.regelspraak.facts} (the consumer's domain beans) — {@code compile} validates that contract
 * up front via {@link io.github.yhavinga.regelspraak.schema.ContractValidator} and fails fast with EVERY violation
 * (the fact-model contract); {@code io.github.yhavinga.regelspraak.schema.FactModelGenerator} emits matching bean sources.
 * The {@link #compile(String, ClassLoader)} overload threads a bean {@link ClassLoader} for beans compiled into a
 * CHILD loader (the {@code FactModelGenerator} + {@code BeanCompiler} shape); the 1-arg form uses the app loader.
 */
public final class RegelSpraakEngine {
    private final KieBase kieBase;
    private final List<String> overgeslagen;
    private final List<Dekking> dekking;

    private RegelSpraakEngine(KieBase kieBase, List<String> overgeslagen, List<Dekking> dekking) {
        this.kieBase = kieBase;
        this.overgeslagen = overgeslagen;
        this.dekking = dekking;
    }

    /** Resolve + transpile a RegelSpraak source to an executable {@link org.drools.model.Model} and build its
     *  {@link KieBase}. Throws {@link RegelSpraakCompileException} if the source has resolver errors. */
    public static RegelSpraakEngine compile(String regelSpraakSource) {
        return compile(regelSpraakSource, null);
    }

    /** As {@link #compile(String)} but resolves the fact beans against {@code beanLoader} (card fase 1): beans
     *  compiled into a CHILD classloader ({@code FactModelGenerator} + {@code BeanCompiler}) are then visible to the
     *  contract validator, the exec-model back-end, and Drools-internal linking. A null loader uses the app loader. */
    public static RegelSpraakEngine compile(String regelSpraakSource, ClassLoader beanLoader) {
        ClassLoader cl = beanLoader != null ? beanLoader : RegelSpraakEngine.class.getClassLoader();
        RegelSpraakModel model = Resolver.resolve(regelSpraakSource);
        if (!model.errors().isEmpty()) throw new RegelSpraakCompileException(model.errors());
        // the fact-model contract: the fact-bean contract is validated FIRST, and completely — every bean the model
        // implies must exist on this classpath with the right member shapes, or compilation fails with the
        // FULL violation list (one round trip, not one reflective fire-time abort per run).
        java.util.List<String> schendingen = io.github.yhavinga.regelspraak.schema.ContractValidator
                .valideer(model, cl);
        if (!schendingen.isEmpty()) throw RegelSpraakCompileException.contract(schendingen);
        ExecModelTranspiler transpiler = ExecModelTranspiler.build(model, cl);
        // TCCL swap so any Drools-internal linking of the Model sees the bean loader (child-loaded beans).
        ClassLoader vorige = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        KieBase kieBase;
        try { kieBase = KieBaseBuilder.createKieBaseFromModel(transpiler.model()); }
        finally { Thread.currentThread().setContextClassLoader(vorige); }
        return new RegelSpraakEngine(kieBase, transpiler.overgeslagen(), transpiler.dekking());
    }

    /** Open a fresh fact session against the compiled rule base. */
    public Session newSession() { return new Session(kieBase.newKieSession()); }

    /**
     * Open a fresh session op één datumautoriteit: {@code peildatum} wordt de {@code rekendatum}-global. Een model
     * dat geen rekendatum-global declareert (niet-tijdgebonden) is tolerant — {@code setGlobal} wordt dan opgeslikt,
     * geen fout (spiegel {@code setGlobalVeilig} uit ScenarioRunner/Uitvoerder). Dit is het pad zonder parametersets;
     * de sessie draagt dan alleen de datum, geen parameter-globals.
     */
    public Session newSession(LocalDate peildatum) {
        Session s = new Session(kieBase.newKieSession());
        setGlobalVeilig(s, "rekendatum", peildatum);
        return s;
    }

    /**
     * Open een sessie op de ÉÉN-DATUMAUTORITEIT-unificatie: de {@code peildatum} selecteert de parameter-vintage
     * EN wordt de {@code rekendatum}-global — een regel die Rekendatum/Rekenjaar leest en de vintage-selectie volgen
     * dus ALTIJD dezelfde datum; een tweede datumnotie is onmogelijk. Fail-fast:
     * <ul>
     *   <li><b>peildatum {@code null}</b> — de datumautoriteit is verplicht op dit pad ({@link IllegalArgumentException});</li>
     *   <li><b>overlap</b> — {@link Parameterset#overlapFouten} niet-leeg is nooit last-one-wins, ook voor
     *       hand-gebouwde sets ({@link IllegalArgumentException}); de lader vangt dit normaal al af;</li>
     *   <li><b>gap</b> — geen interval bevat de peildatum voor een parameter is een SESSION-fout die parameter +
     *       peildatum noemt ({@link IllegalStateException} met alle gap-regels; geen compile-fout, dus geen
     *       {@link RegelSpraakCompileException}: de bron compileerde prima, de session-DATA dekt deze datum niet).</li>
     * </ul>
     * Elke opgeloste parameter wordt als global gezet onder {@code Namen.ident(parameternaam, false)} (dezelfde formule
     * als ScenarioRunner en de service-VerzoekMapper), tolerant voor niet-gedeclareerde globals: een parameter die geen
     * enkele regel leest is geen fout.
     */
    public Session newSession(LocalDate peildatum, List<Parameterset> parametersets) {
        if (peildatum == null)
            throw new IllegalArgumentException("peildatum is verplicht: de datumautoriteit selecteert de "
                    + "parameter-vintage en is tevens de rekendatum — een null-datum kan geen vintage kiezen");

        List<Parameterset> sets = parametersets == null ? List.of() : parametersets;

        // defensieve overlap-hercheck: ook hand-gebouwde sets krijgen nooit last-one-wins (de lader vangt dit al af).
        List<String> overlap = Parameterset.overlapFouten(sets);
        if (!overlap.isEmpty())
            throw new IllegalArgumentException("parametersets bevatten overlappende geldigheidsperiodes: "
                    + String.join("; ", overlap));

        List<String> gaten = new ArrayList<>();
        Map<String, Object> opgelost = Parameterset.waardenOp(sets, peildatum, gaten);
        if (!gaten.isEmpty())
            throw new IllegalStateException("geen parameterwaarde geldig op peildatum " + peildatum + ": "
                    + String.join("; ", gaten));

        Session s = new Session(kieBase.newKieSession());
        for (Map.Entry<String, Object> e : opgelost.entrySet())
            setGlobalVeilig(s, Namen.ident(e.getKey(), false), e.getValue());
        // de unificatie: dezelfde peildatum is de rekendatum-global.
        setGlobalVeilig(s, "rekendatum", peildatum);
        return s;
    }

    /** setGlobal, tolerant voor een model dat geen zodanige global declareerde (een parameter die geen regel leest,
     *  of een niet-tijdgebonden model zonder rekendatum-global): schrijven zou throwen — hier onschadelijk gemaakt
     *  (spiegel ScenarioRunner/Uitvoerder.setGlobalVeilig). */
    private static void setGlobalVeilig(Session s, String naam, Object waarde) {
        try { s.setGlobal(naam, waarde); }
        catch (RuntimeException onbekend) { /* global niet gedeclareerd: onschadelijk — geen regel leest hem */ }
    }

    /** Rules the transpiler could not lower (honest markers); empty for a fully-supported source. */
    public List<String> overgeslagen() { return overgeslagen; }

    /** The coverage manifest: one verdict per construct (LOWERED or SKIP with a reason), so a
     *  consumer can see exactly WHICH rules the compiled engine carries — not just a skip count. */
    public List<Dekking> dekking() { return dekking; }

    /** A unit of work: insert facts (+ globals), fire, read results. {@link AutoCloseable} disposes the session. */
    public static final class Session implements AutoCloseable {
        private final KieSession session;
        Session(KieSession session) { this.session = session; }

        public Session insert(Object fact) { session.insert(fact); return this; }
        public Session insertAll(Object... facts) { for (Object f : facts) session.insert(f); return this; }
        public Session setGlobal(String name, Object value) { session.setGlobal(name, value); return this; }

        /** Fire all rules to quiescence; returns the number of rule activations fired. BOUNDED: a
         *  read-write dependency CYCLE between berekeningen (x = y+1; y = x+1 — resolver-diagnosed as a
         *  WARNING, but a voorwaarde may or may not break it at runtime) recomputes divergently forever
         *  under the freshness semantics; the cap turns that authoring error into an exception instead
         *  of a hung session. Any real model reaches its fixpoint in a tiny fraction of the bound. */
        public int fire() {
            int n = session.fireAllRules(100_000);
            if (n >= 100_000)
                throw new IllegalStateException("regel-herberekening bereikt geen fixpunt binnen 100000 activaties"
                        + " — vermoedelijk een kringverwijzing tussen berekeningen (zie de resolver-WARNING)");
            return n;
        }

        /** All facts in working memory that are instances of {@code type} — e.g. inserted §9.5 Inconsistentie
         *  facts, or objects a §13.4.6 objectcreatie produced. */
        @SuppressWarnings("unchecked")
        public <T> Collection<T> facts(Class<T> type) {
            return (Collection<T>) (Collection<?>) session.getObjects(type::isInstance);
        }

        @Override public void close() { session.dispose(); }
    }
}
