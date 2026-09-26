package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.engine.RegelSpraakCompileException;
import dev.uchar.regelspraak.model.Parameterset;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Severity;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.kern.Dekking;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The embeddable RegelSpraak engine: compile a RegelSpraak source into a ready-to-fire Drools engine and run rules
 * over facts. The source goes through uchar's frontend and is built by uchar's backend on the exec-model branch
 * ({@link UcharBouw}), which also generates the fact beans and compiles them into a class loader of their own.
 *
 * <pre>{@code
 *   RegelSpraakEngine engine = RegelSpraakEngine.compile(regelSpraakSource);   // resolve, emit, build
 *   Object persoon = engine.nieuw("Persoon");                                  // a generated fact bean
 *   try (RegelSpraakEngine.Session s = engine.newSession()) {
 *       s.insert(persoon).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
 *   }                                                                          // persoon is mutated in place
 * }</pre>
 *
 * The facts are instances of the generated beans ({@link #nieuw}, {@link #beanLoader()}): the KieBase is built
 * against those classes, so an object of a same-named class from another class loader never matches a rule.
 */
public final class RegelSpraakEngine {
    private final RegelSpraakModel model;
    private final DroolsBackend.Build build;

    private RegelSpraakEngine(RegelSpraakModel model, DroolsBackend.Build build) {
        this.model = model;
        this.build = build;
    }

    /** Resolve {@code regelSpraakSource}, emit it and build its {@link KieBase}. Throws
     *  {@link RegelSpraakCompileException} if the source has resolver errors, and also when uchar refuses the model
     *  (a construct its exec-model branch does not lower among them): an engine without that construct would answer
     *  as if it did not exist. */
    public static RegelSpraakEngine compile(String regelSpraakSource) {
        RegelSpraakModel model = Resolver.resolve(regelSpraakSource);
        if (!model.errors().isEmpty()) throw new RegelSpraakCompileException(model.errors());
        try {
            return new RegelSpraakEngine(model, UcharBouw.bouw(model, DroolsBackend.Branch.EXEC_MODEL));
        } catch (UcharBouw.NietGedragen e) {
            throw new RegelSpraakCompileException(List.of(new Diagnostic(Severity.ERROR,
                    "niet uitvoerbaar in uchar: " + e.getMessage(), 0)));
        }
    }

    /** The resolved model the engine was compiled from (what a {@link Verbalisator} explains a decision with). */
    public RegelSpraakModel model() { return model; }

    /** The class loader the generated fact beans live in ({@link FactModelGenerator#PAKKET}). */
    public ClassLoader beanLoader() { return build.beanLoader(); }

    /** A fresh instance of the generated bean for objecttype {@code objecttype} (its declared name). */
    public Object nieuw(String objecttype) {
        Objecttype ot = model.objecttypeOpNaam(objecttype);
        if (ot == null) throw new IllegalArgumentException("onbekend objecttype '" + objecttype + "'");
        String klasse = FactModelGenerator.PAKKET + "." + Namen.ident(ot.naam, true);
        try {
            return Class.forName(klasse, true, build.beanLoader()).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kon de bean " + klasse + " niet maken", e);
        }
    }

    /** Open a fresh fact session against the compiled rule base. */
    public Session newSession() { return new Session(build.kieBase().newKieSession(), build.beanLoader()); }

    /**
     * Open a fresh session op één datumautoriteit: {@code peildatum} wordt de {@code rekendatum}-global. Een model
     * dat geen rekendatum-global declareert (niet-tijdgebonden) is tolerant — {@code setGlobal} wordt dan opgeslikt,
     * geen fout. Dit is het pad zonder parametersets; de sessie draagt dan alleen de datum, geen parameter-globals.
     */
    public Session newSession(LocalDate peildatum) {
        Session s = newSession();
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
     * Elke opgeloste parameter wordt als global gezet onder {@code Namen.ident(parameternaam, false)}, de naam die de
     * transpiler de global geeft, tolerant voor niet-gedeclareerde globals: een parameter die geen enkele regel leest
     * is geen fout.
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

        Session s = newSession();
        for (Map.Entry<String, Object> e : opgelost.entrySet())
            setGlobalVeilig(s, Namen.ident(e.getKey(), false), e.getValue());
        // de unificatie: dezelfde peildatum is de rekendatum-global.
        setGlobalVeilig(s, "rekendatum", peildatum);
        return s;
    }

    /** setGlobal, tolerant voor een model dat geen zodanige global declareerde (een parameter die geen regel leest,
     *  of een niet-tijdgebonden model zonder rekendatum-global): schrijven zou throwen — hier onschadelijk gemaakt. */
    private static void setGlobalVeilig(Session s, String naam, Object waarde) {
        try { s.setGlobal(naam, waarde); }
        catch (RuntimeException onbekend) { /* global niet gedeclareerd: onschadelijk — geen regel leest hem */ }
    }

    /** Rules the transpiler could not lower. Empty: uchar refuses to build a model with such a rule, and
     *  {@link #compile} reports that refusal. */
    public List<String> overgeslagen() { return build.execModelTranspiler().overgeslagen(); }

    /** The coverage manifest of the built rule base: one verdict per construct, every one LOWERED, because the build
     *  refuses any other. */
    public List<Dekking> dekking() { return build.dekking(); }

    /** A unit of work: insert facts (+ globals), fire, read results. {@link AutoCloseable} disposes the session. */
    public static final class Session implements AutoCloseable {
        private final KieSession session;
        private final ClassLoader beanLoader;

        Session(KieSession session, ClassLoader beanLoader) {
            this.session = session;
            this.beanLoader = beanLoader;
        }

        public Session insert(Object fact) { session.insert(fact); return this; }
        public Session insertAll(Object... facts) { for (Object f : facts) session.insert(f); return this; }
        public Session setGlobal(String name, Object value) { session.setGlobal(name, value); return this; }

        /** Fire all rules to quiescence; returns the number of rule activations fired. BOUNDED: a
         *  read-write dependency CYCLE between berekeningen (x = y+1; y = x+1 — resolver-diagnosed as a
         *  WARNING, but a voorwaarde may or may not break it at runtime) recomputes divergently forever
         *  under the freshness semantics; the cap turns that authoring error into an exception instead
         *  of a hung session. Any real model reaches its fixpoint in a tiny fraction of the bound. The rules
         *  resolve the generated fact types through the thread's context class loader, as uchar's replay fires. */
        public int fire() {
            ClassLoader vorige = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(beanLoader);
            int n;
            try { n = session.fireAllRules(100_000); }
            finally { Thread.currentThread().setContextClassLoader(vorige); }
            if (n >= 100_000)
                throw new IllegalStateException("regel-herberekening bereikt geen fixpunt binnen 100000 activaties"
                        + " — vermoedelijk een kringverwijzing tussen berekeningen (zie de resolver-WARNING)");
            return n;
        }

        /** All facts in working memory that are instances of {@code type} — e.g. objects a §13.4.6 objectcreatie
         *  produced; {@link #facts(String)} reaches a generated class by name. */
        @SuppressWarnings("unchecked")
        public <T> Collection<T> facts(Class<T> type) {
            return (Collection<T>) (Collection<?>) session.getObjects(type::isInstance);
        }

        /** All facts in working memory that are instances of the generated class {@code klasse} (its simple name in
         *  {@link FactModelGenerator#PAKKET}), e.g. {@code "Inconsistentie"} for the §9.5 inconsistency facts. */
        public Collection<?> facts(String klasse) {
            try {
                Class<?> type = Class.forName(FactModelGenerator.PAKKET + "." + klasse, true, beanLoader);
                return session.getObjects(type::isInstance);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("geen gegenereerde klasse " + klasse + " in "
                        + FactModelGenerator.PAKKET, e);
            }
        }

        @Override public void close() { session.dispose(); }
    }
}
