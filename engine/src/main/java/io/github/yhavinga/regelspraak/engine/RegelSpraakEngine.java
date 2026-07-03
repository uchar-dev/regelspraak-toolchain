package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.kern.Dekking;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import org.drools.modelcompiler.KieBaseBuilder;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

import java.util.Collection;
import java.util.List;

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
 * (goal-M4 D7); {@code io.github.yhavinga.regelspraak.schema.FactModelGenerator} emits matching bean sources.
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
        RegelSpraakModel model = Resolver.resolve(regelSpraakSource);
        if (!model.errors().isEmpty()) throw new RegelSpraakCompileException(model.errors());
        // goal-M4 D7: the fact-bean contract is validated FIRST, and completely — every bean the model
        // implies must exist on this classpath with the right member shapes, or compilation fails with the
        // FULL violation list (one round trip, not one reflective fire-time abort per run).
        java.util.List<String> schendingen = io.github.yhavinga.regelspraak.schema.ContractValidator
                .valideer(model, RegelSpraakEngine.class.getClassLoader());
        if (!schendingen.isEmpty()) throw RegelSpraakCompileException.contract(schendingen);
        ExecModelTranspiler transpiler = ExecModelTranspiler.build(model);
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel(transpiler.model());
        return new RegelSpraakEngine(kieBase, transpiler.overgeslagen(), transpiler.dekking());
    }

    /** Open a fresh fact session against the compiled rule base. */
    public Session newSession() { return new Session(kieBase.newKieSession()); }

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

        /** Fire all rules to quiescence; returns the number of rule activations fired. */
        public int fire() { return session.fireAllRules(); }

        /** All facts in working memory that are instances of {@code type} — e.g. inserted §9.5 Inconsistentie
         *  facts, or objects a §13.4.6 objectcreatie produced. */
        @SuppressWarnings("unchecked")
        public <T> Collection<T> facts(Class<T> type) {
            return (Collection<T>) (Collection<?>) session.getObjects(type::isInstance);
        }

        @Override public void close() { session.dispose(); }
    }
}
