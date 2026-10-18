package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.cases.Input;
import dev.uchar.regelspraak.engine.RegelSpraakCompileException;
import dev.uchar.regelspraak.model.ParameterSet;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Severity;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.core.Coverage;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;

import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The embeddable RegelSpraak engine: compile a RegelSpraak source into a ready-to-fire Drools engine and run rules
 * over facts. The source goes through uchar's frontend and is built by uchar's backend on the exec-model branch
 * ({@link UcharBuild}), which also generates the fact beans and compiles them into a class loader of their own.
 *
 * <pre>{@code
 *   RegelSpraakEngine engine = RegelSpraakEngine.compile(regelSpraakSource);   // resolve, emit, build
 *   Object persoon = engine.newInstance("Persoon");                                  // a generated fact bean
 *   try (RegelSpraakEngine.Session s = engine.newSession()) {
 *       s.insert(persoon).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
 *       s.restore();                                                          // the derived values onto persoon
 *   }
 * }</pre>
 *
 * The facts are instances of the generated beans ({@link #newInstance}, {@link #beanLoader()}): the KieBase is built
 * against those classes, so an object of a same-named class from another class loader never matches a rule. A
 * session fills, fires and reads through uchar's entry ({@link Session}): the branch derives with truth maintenance,
 * so a derived value is a fact of the session, and the beans hold the input alone until {@link Session#restore}.
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
            return new RegelSpraakEngine(model, UcharBuild.assemble(model, DroolsBackend.Branch.EXEC_MODEL));
        } catch (UcharBuild.NotCarried e) {
            throw new RegelSpraakCompileException(List.of(new Diagnostic(Severity.ERROR,
                    "niet uitvoerbaar in uchar: " + e.getMessage(), 0)));
        }
    }

    /** The resolved model the engine was compiled from (what a {@link Verbaliser} explains a decision with). */
    public RegelSpraakModel model() { return model; }

    /** The class loader the generated fact beans live in ({@link FactModelGenerator#PAKKET}). */
    public ClassLoader beanLoader() { return build.beanLoader(); }

    /** A fresh instance of the generated bean for objecttype {@code objectType} (its declared name). */
    public Object newInstance(String objectType) {
        Objecttype ot = model.objectTypeByName(objectType);
        if (ot == null) throw new IllegalArgumentException("onbekend objecttype '" + objectType + "'");
        String clazz = FactModelGenerator.PACKAGE + "." + Namen.ident(ot.naam, true);
        try {
            return Class.forName(clazz, true, build.beanLoader()).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kon de bean " + clazz + " niet maken", e);
        }
    }

    /** Open a fresh fact session against the compiled rule base. */
    public Session newSession() { return new Session(build, build.kieBase().newKieSession()); }

    /**
     * Open a fresh session op één datumautoriteit: {@code asOfDate} wordt de {@code rekendatum}-global. Een model
     * dat geen rekendatum-global declareert (niet-tijdgebonden) is tolerant — {@code setGlobal} wordt dan opgeslikt,
     * geen fout. Dit is het pad zonder parametersets; de sessie draagt dan alleen de datum, geen parameter-globals.
     */
    public Session newSession(LocalDate asOfDate) {
        Session s = newSession();
        setGlobalSafe(s, "rekendatum", asOfDate);
        return s;
    }

    /**
     * Open een sessie op de ÉÉN-DATUMAUTORITEIT-unificatie: de {@code asOfDate} selecteert de parameter-vintage
     * EN wordt de {@code rekendatum}-global — een regel die Rekendatum/Rekenjaar leest en de vintage-selectie volgen
     * dus ALTIJD dezelfde datum; een tweede datumnotie is onmogelijk. Fail-fast:
     * <ul>
     *   <li><b>peildatum {@code null}</b> — de datumautoriteit is verplicht op dit pad ({@link IllegalArgumentException});</li>
     *   <li><b>overlap</b> — {@link ParameterSet#overlapErrors} niet-leeg is nooit last-one-wins, ook voor
     *       hand-gebouwde sets ({@link IllegalArgumentException}); de lader vangt dit normaal al af;</li>
     *   <li><b>gap</b> — geen interval bevat de peildatum voor een parameter is een SESSION-fout die parameter +
     *       peildatum noemt ({@link IllegalStateException} met alle gap-regels; geen compile-fout, dus geen
     *       {@link RegelSpraakCompileException}: de bron compileerde prima, de session-DATA dekt deze datum niet).</li>
     * </ul>
     * Elke opgeloste parameter wordt als global gezet onder {@code Namen.ident(parameternaam, false)}, de naam die de
     * transpiler de global geeft, tolerant voor niet-gedeclareerde globals: een parameter die geen enkele regel leest
     * is geen fout.
     */
    public Session newSession(LocalDate asOfDate, List<ParameterSet> parameterSets) {
        if (asOfDate == null)
            throw new IllegalArgumentException("peildatum is verplicht: de datumautoriteit selecteert de "
                    + "parameter-vintage en is tevens de rekendatum — een null-datum kan geen vintage kiezen");

        List<ParameterSet> sets = parameterSets == null ? List.of() : parameterSets;

        // defensieve overlap-hercheck: ook hand-gebouwde sets krijgen nooit last-one-wins (de lader vangt dit al af).
        List<String> overlap = ParameterSet.overlapErrors(sets);
        if (!overlap.isEmpty())
            throw new IllegalArgumentException("parametersets bevatten overlappende geldigheidsperiodes: "
                    + String.join("; ", overlap));

        List<String> holes = new ArrayList<>();
        Map<String, Object> resolved = ParameterSet.valuesOn(sets, asOfDate, holes);
        if (!holes.isEmpty())
            throw new IllegalStateException("geen parameterwaarde geldig op peildatum " + asOfDate + ": "
                    + String.join("; ", holes));

        Session s = newSession();
        for (Map.Entry<String, Object> e : resolved.entrySet())
            setGlobalSafe(s, Namen.ident(e.getKey(), false), e.getValue());
        // de unificatie: dezelfde peildatum is de rekendatum-global.
        setGlobalSafe(s, "rekendatum", asOfDate);
        return s;
    }

    /** setGlobal, tolerant voor een model dat geen zodanige global declareerde (een parameter die geen regel leest,
     *  of een niet-tijdgebonden model zonder rekendatum-global): schrijven zou throwen — hier onschadelijk gemaakt. */
    private static void setGlobalSafe(Session s, String label, Object argument) {
        try { s.setGlobal(label, argument); }
        catch (RuntimeException unknown) { /* global niet gedeclareerd: onschadelijk — geen regel leest hem */ }
    }

    /** The coverage manifest of the built rule base: one verdict per construct, every one LOWERED, because the build
     *  refuses any other. */
    public List<Coverage> coverage() { return build.coverage(); }

    /**
     * A unit of work on uchar's entry ({@code DroolsBackend.insert}, {@code fire}, {@code fixpointOf},
     * {@code setDerivedValues}): the beans of {@link #insert} and {@link #insertAll}, with the bucket scalars of
     * {@link #bucketScalar} and the list orders of {@link #list}, are gathered until the first {@link #fire}, which
     * puts them in through {@code insert} and fires through {@code fire}; a later fire only fires.
     * Between two fires a consumer changes an attribute or a characteristic of a bean of the session and calls
     * {@link #update}; anything else, an object that comes or goes, a changed relation, another global, asks a fresh
     * session, and uchar's entry refuses it by name. {@link #read} gives what stands at the fixpoint of the last fire,
     * {@link #restore} puts it on the beans and closes the session for firing. {@link AutoCloseable} disposes the
     * session. The rules resolve the generated fact types through the thread's context class loader, which every call
     * of the entry gets as uchar's replay gives it.
     */
    public static final class Session implements AutoCloseable {
        private final DroolsBackend.Build build;
        private final KieSession session;
        private final List<Object> input = new ArrayList<>();
        /** The bucket scalars and list orders of the first fire's input, stated on its {@code Invoer} beside the
         *  beans of {@link #input}. */
        private final List<Consumer<Input>> feeds = new ArrayList<>();
        private boolean filled;

        Session(DroolsBackend.Build build, KieSession session) {
            this.build = build;
            this.session = session;
        }

        /** Adds {@code fact} to the input of the first {@link #fire}. */
        public Session insert(Object fact) { collect(fact); return this; }

        /** Adds {@code facts}, in this order, to the input of the first {@link #fire}. */
        public Session insertAll(Object... facts) { for (Object f : facts) collect(f); return this; }

        private void collect(Object fact) {
            Objects.requireNonNull(fact, "fact");
            beforeFirstFire("insert", "een object erbij");
            input.add(fact);
        }

        /**
         * States that {@code bean} holds the input of {@code attribute}, an attribute on a timeline named as the model
         * declares it, as the asOfDate-bucket scalar: the one segment of the period of the attribute's grid that holds
         * the peildatum (uchar's {@code Invoer.emmerscalair}, RUNTIME-SEMANTICS ch. 8.4). A consumer that sets such an
         * attribute that way states it here. The bean cannot tell that scalar from a full timeline of one segment, and
         * without the statement the branch reads the full timeline: a form that needs the bucket scalar, the subject's
         * own total without a clip, faults with an {@code InputFormFault} at {@link #restore}, and a form that needs
         * the full timeline, such as the base of a pro-rata part, computes over that one segment without a fault.
         * Before the first {@link #fire}, as {@link #insert}; there uchar's entry refuses a statement that does not fit
         * the beans or the model.
         */
        public Session bucketScalar(Object bean, String attribute) {
            Objects.requireNonNull(bean, "bean");
            Objects.requireNonNull(attribute, "attribuut");
            beforeFirstFire("emmerscalair", "een emmerscalair erbij");
            feeds.add(i -> i.bucketScalar(bean, attribute));
            return this;
        }

        /**
         * States the order of a to-many list (uchar's {@code Invoer.lijst}): {@code members} are the members whose
         * reference field {@code role} holds {@code houder}, in this order, {@code role} being the role the holder plays,
         * the name of that field; the list names every bean of the input whose field holds the holder, each once. The
         * order decides only which member a fault of a fold names, where the fold reads its members one by one and stops
         * at the first fault; without the statement a list holds its members in the order of the inserts. Before the
         * first {@link #fire}, as {@link #insert}; there uchar's entry refuses a list that does not fit the beans.
         */
        public Session list(Object houder, String role, List<?> members) {
            Objects.requireNonNull(houder, "houder");
            Objects.requireNonNull(role, "rol");
            List<Object> order = List.copyOf(members);   // the order as given now, not as the list reads at the fire
            beforeFirstFire("lijst", "een lijst erbij");
            feeds.add(i -> i.list(houder, role, order));
            return this;
        }

        // insert fills a fresh session only, and states the places of a list and the bucket scalars over one input
        private void beforeFirstFire(String what, String surplus) {
            if (filled)
                throw new IllegalStateException(what + " na de eerste fire: de sessie is gevuld via uchars ingang"
                        + " (DroolsBackend.voegIn), die alleen een verse sessie vult; " + surplus + " vraagt een verse"
                        + " sessie");
        }

        /** Sets a global, before the first {@link #fire}: the branch states a global once, at the first fire, and
         *  uchar's entry refuses a session whose global changed after it. */
        public Session setGlobal(String name, Object value) { session.setGlobal(name, value); return this; }

        /** Fires to the fixpoint through uchar's entry, the first time after putting the gathered input in (the beans,
         *  the bucket scalars, the list orders), and returns the number of firings. A run without a fixpoint, at the cap or at the creation budget, throws an
         *  {@link IllegalStateException} with uchar's sentence. A model with a creation fires once per session, and
         *  a fire after {@link #restore} is refused, each by uchar's entry with the way on. */
        public int fire() {
            return withBeanLoader(() -> {
                if (!filled) {
                    Input in = new Input().beans(input);
                    for (Consumer<Input> feed : feeds) feed.accept(in);
                    DroolsBackend.insert(build, session, in);
                    filled = true;
                }
                DroolsBackend.Firing firing = DroolsBackend.fire(build, session);
                if (firing.noFixpoint() != null) throw new IllegalStateException(firing.noFixpoint());
                return firing.firings();
            });
        }

        /** Tells the session that an attribute or a characteristic of {@code bean} changed, between two fires: the
         *  entry does not see a field set without it, and the next {@link #fire} would keep the old fixpoint. Before
         *  the first fire there is nothing to tell, as that fire reads the bean as it is then. A model with an object
         *  or a fact creation fires once per session: uchar refuses a second fire there, so a change asks a fresh
         *  session. */
        public Session update(Object bean) {
            Objects.requireNonNull(bean, "bean");
            if (!filled) {
                if (input.stream().noneMatch(b -> b == bean))
                    throw new IllegalArgumentException("update van een bean die niet in deze sessie staat");
                return this;
            }
            FactHandle handle = session.getFactHandle(bean);
            if (handle == null)
                throw new IllegalArgumentException("update van een bean die niet in deze sessie staat; een object"
                        + " erbij vraagt een verse sessie");
            withBeanLoader(() -> { session.update(handle, bean); return null; });
            return this;
        }

        /** What stands at the fixpoint of the last {@link #fire}, without writing anything on a bean: the derived values
         *  and characteristics, the objects and links the creations made, the evaluation faults as data and the
         *  inconsistencies. */
        public Fixpoint read() {
            return withBeanLoader(() -> DroolsBackend.fixpointOf(build, session));
        }

        /** Puts what stands at the fixpoint of the last {@link #fire} on the beans, after which the session does not
         *  fire again. Every evaluation fault that stands there is thrown as uchar's own exception (two or more as
         *  {@code EvaluationFaults}), and then nothing is written: one of the four types uchar's README names
         *  ({@code Breuk.DelingDoorNul}, {@code ComputeFault}, {@code WritersConflict}, {@code InputFormFault}) or
         *  another, such as the {@code IllegalStateException} of a fact creation that gives a to-one role a second
         *  filling. A fault is an evaluation fault by its place at the fixpoint, not by its type: a consumer that must
         *  tell it from an exception of the entry itself reads {@link #read}{@code ().fouten()} first. */
        public void restore() {
            withBeanLoader(() -> { DroolsBackend.setDerivedValues(build, session); return null; });
        }

        /** All facts in working memory that are instances of {@code type} — e.g. objects a §13.4.6 objectcreatie
         *  produced; {@link #facts(String)} reaches a generated class by name. A derived value is a fact of the
         *  session, not a field of its bean, until {@link #restore}. */
        @SuppressWarnings("unchecked")
        public <T> Collection<T> facts(Class<T> type) {
            return (Collection<T>) (Collection<?>) session.getObjects(type::isInstance);
        }

        /** All facts in working memory that are instances of the generated class {@code clazz} (its simple name in
         *  {@link FactModelGenerator#PAKKET}), e.g. {@code "Inconsistentie"} for the §9.5 inconsistency facts. */
        public Collection<?> facts(String clazz) {
            try {
                Class<?> type = Class.forName(FactModelGenerator.PACKAGE + "." + clazz, true, build.beanLoader());
                return session.getObjects(type::isInstance);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("geen gegenereerde klasse " + clazz + " in "
                        + FactModelGenerator.PACKAGE, e);
            }
        }

        @Override public void close() { session.dispose(); }

        private <T> T withBeanLoader(java.util.function.Supplier<T> step) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(build.beanLoader());
            try { return step.get(); }
            finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }
}
