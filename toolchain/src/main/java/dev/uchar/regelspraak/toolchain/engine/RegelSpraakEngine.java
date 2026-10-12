package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.cases.Invoer;
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
import dev.uchar.regelspraak.transpiler.tms.Fixpunt;

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
 * ({@link UcharBouw}), which also generates the fact beans and compiles them into a class loader of their own.
 *
 * <pre>{@code
 *   RegelSpraakEngine engine = RegelSpraakEngine.compile(regelSpraakSource);   // resolve, emit, build
 *   Object persoon = engine.nieuw("Persoon");                                  // a generated fact bean
 *   try (RegelSpraakEngine.Session s = engine.newSession()) {
 *       s.insert(persoon).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
 *       s.zetTerug();                                                          // the derived values onto persoon
 *   }
 * }</pre>
 *
 * The facts are instances of the generated beans ({@link #nieuw}, {@link #beanLoader()}): the KieBase is built
 * against those classes, so an object of a same-named class from another class loader never matches a rule. A
 * session fills, fires and reads through uchar's entry ({@link Session}): the branch derives with truth maintenance,
 * so a derived value is a fact of the session, and the beans hold the input alone until {@link Session#zetTerug}.
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
    public Session newSession() { return new Session(build, build.kieBase().newKieSession()); }

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

    /** The coverage manifest of the built rule base: one verdict per construct, every one LOWERED, because the build
     *  refuses any other. */
    public List<Dekking> dekking() { return build.dekking(); }

    /**
     * A unit of work on uchar's entry ({@code DroolsBackend.voegIn}, {@code vuur}, {@code lees},
     * {@code zetAfgeleideWaarden}): the beans of {@link #insert} and {@link #insertAll}, with the bucket scalars of
     * {@link #emmerscalair} and the list orders of {@link #lijst}, are gathered until the first {@link #fire}, which
     * puts them in through {@code voegIn} and fires through {@code vuur}; a later fire only fires.
     * Between two fires a consumer changes an attribute or a characteristic of a bean of the session and calls
     * {@link #update}; anything else, an object that comes or goes, a changed relation, another global, asks a fresh
     * session, and uchar's entry refuses it by name. {@link #lees} gives what stands at the fixpoint of the last fire,
     * {@link #zetTerug} puts it on the beans and closes the session for firing. {@link AutoCloseable} disposes the
     * session. The rules resolve the generated fact types through the thread's context class loader, which every call
     * of the entry gets as uchar's replay gives it.
     */
    public static final class Session implements AutoCloseable {
        private final DroolsBackend.Build build;
        private final KieSession session;
        private final List<Object> invoer = new ArrayList<>();
        /** The bucket scalars and list orders of the first fire's input, stated on its {@code Invoer} beside the
         *  beans of {@link #invoer}. */
        private final List<Consumer<Invoer>> opgaven = new ArrayList<>();
        private boolean gevuld;

        Session(DroolsBackend.Build build, KieSession session) {
            this.build = build;
            this.session = session;
        }

        /** Adds {@code fact} to the input of the first {@link #fire}. */
        public Session insert(Object fact) { verzamel(fact); return this; }

        /** Adds {@code facts}, in this order, to the input of the first {@link #fire}. */
        public Session insertAll(Object... facts) { for (Object f : facts) verzamel(f); return this; }

        private void verzamel(Object fact) {
            Objects.requireNonNull(fact, "fact");
            voorDeEersteFire("insert", "een object erbij");
            invoer.add(fact);
        }

        /**
         * States that {@code bean} holds the input of {@code attribuut}, an attribute on a timeline named as the model
         * declares it, as the asOfDate-bucket scalar: the one segment of the period of the attribute's grid that holds
         * the peildatum (uchar's {@code Invoer.emmerscalair}, RUNTIME-SEMANTICS ch. 8.4). A consumer that sets such an
         * attribute that way states it here. The bean cannot tell that scalar from a full timeline of one segment, and
         * without the statement the branch reads the full timeline: a form that needs the bucket scalar, the subject's
         * own total without a clip, faults with an {@code Invoervormfout} at {@link #zetTerug}, and a form that needs
         * the full timeline, such as the base of a pro-rata part, computes over that one segment without a fault.
         * Before the first {@link #fire}, as {@link #insert}; there uchar's entry refuses a statement that does not fit
         * the beans or the model.
         */
        public Session emmerscalair(Object bean, String attribuut) {
            Objects.requireNonNull(bean, "bean");
            Objects.requireNonNull(attribuut, "attribuut");
            voorDeEersteFire("emmerscalair", "een emmerscalair erbij");
            opgaven.add(i -> i.emmerscalair(bean, attribuut));
            return this;
        }

        /**
         * States the order of a to-many list (uchar's {@code Invoer.lijst}): {@code leden} are the members whose
         * reference field {@code rol} holds {@code houder}, in this order, {@code rol} being the role the holder plays,
         * the name of that field; the list names every bean of the input whose field holds the holder, each once. The
         * order decides only which member a fault of a fold names, where the fold reads its members one by one and stops
         * at the first fault; without the statement a list holds its members in the order of the inserts. Before the
         * first {@link #fire}, as {@link #insert}; there uchar's entry refuses a list that does not fit the beans.
         */
        public Session lijst(Object houder, String rol, List<?> leden) {
            Objects.requireNonNull(houder, "houder");
            Objects.requireNonNull(rol, "rol");
            List<Object> volgorde = List.copyOf(leden);   // the order as given now, not as the list reads at the fire
            voorDeEersteFire("lijst", "een lijst erbij");
            opgaven.add(i -> i.lijst(houder, rol, volgorde));
            return this;
        }

        // voegIn fills a fresh session only, and states the places of a list and the bucket scalars over one input
        private void voorDeEersteFire(String wat, String erbij) {
            if (gevuld)
                throw new IllegalStateException(wat + " na de eerste fire: de sessie is gevuld via uchars ingang"
                        + " (DroolsBackend.voegIn), die alleen een verse sessie vult; " + erbij + " vraagt een verse"
                        + " sessie");
        }

        /** Sets a global, before the first {@link #fire}: the branch states a global once, at the first fire, and
         *  uchar's entry refuses a session whose global changed after it. */
        public Session setGlobal(String name, Object value) { session.setGlobal(name, value); return this; }

        /** Fires to the fixpoint through uchar's entry, the first time after putting the gathered input in (the beans,
         *  the bucket scalars, the list orders), and returns the number of firings. A run without a fixpoint, at the cap or at the creation budget, throws an
         *  {@link IllegalStateException} with uchar's sentence. A model with a creation fires once per session, and
         *  a fire after {@link #zetTerug} is refused, each by uchar's entry with the way on. */
        public int fire() {
            return metBeanLoader(() -> {
                if (!gevuld) {
                    Invoer in = new Invoer().beans(invoer);
                    for (Consumer<Invoer> opgave : opgaven) opgave.accept(in);
                    DroolsBackend.voegIn(build, session, in);
                    gevuld = true;
                }
                DroolsBackend.Vuring vuring = DroolsBackend.vuur(build, session);
                if (vuring.geenFixpunt() != null) throw new IllegalStateException(vuring.geenFixpunt());
                return vuring.vuringen();
            });
        }

        /** Tells the session that an attribute or a characteristic of {@code bean} changed, between two fires: the
         *  entry does not see a field set without it, and the next {@link #fire} would keep the old fixpoint. Before
         *  the first fire there is nothing to tell, as that fire reads the bean as it is then. */
        public Session update(Object bean) {
            Objects.requireNonNull(bean, "bean");
            if (!gevuld) {
                if (invoer.stream().noneMatch(b -> b == bean))
                    throw new IllegalArgumentException("update van een bean die niet in deze sessie staat");
                return this;
            }
            FactHandle handle = session.getFactHandle(bean);
            if (handle == null)
                throw new IllegalArgumentException("update van een bean die niet in deze sessie staat; een object"
                        + " erbij vraagt een verse sessie");
            metBeanLoader(() -> { session.update(handle, bean); return null; });
            return this;
        }

        /** What stands at the fixpoint of the last {@link #fire}, without writing anything on a bean: the derived values
         *  and characteristics, the objects and links the creations made, the evaluation faults as data and the
         *  inconsistencies. */
        public Fixpunt lees() {
            return metBeanLoader(() -> DroolsBackend.lees(build, session));
        }

        /** Puts what stands at the fixpoint of the last {@link #fire} on the beans, after which the session does not
         *  fire again. An evaluation fault that stands there is thrown as uchar's own exception (two or more as
         *  {@code Evaluatiefouten}), and then nothing is written. */
        public void zetTerug() {
            metBeanLoader(() -> { DroolsBackend.zetAfgeleideWaarden(build, session); return null; });
        }

        /** All facts in working memory that are instances of {@code type} — e.g. objects a §13.4.6 objectcreatie
         *  produced; {@link #facts(String)} reaches a generated class by name. A derived value is a fact of the
         *  session, not a field of its bean, until {@link #zetTerug}. */
        @SuppressWarnings("unchecked")
        public <T> Collection<T> facts(Class<T> type) {
            return (Collection<T>) (Collection<?>) session.getObjects(type::isInstance);
        }

        /** All facts in working memory that are instances of the generated class {@code klasse} (its simple name in
         *  {@link FactModelGenerator#PAKKET}), e.g. {@code "Inconsistentie"} for the §9.5 inconsistency facts. */
        public Collection<?> facts(String klasse) {
            try {
                Class<?> type = Class.forName(FactModelGenerator.PAKKET + "." + klasse, true, build.beanLoader());
                return session.getObjects(type::isInstance);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("geen gegenereerde klasse " + klasse + " in "
                        + FactModelGenerator.PAKKET, e);
            }
        }

        @Override public void close() { session.dispose(); }

        private <T> T metBeanLoader(java.util.function.Supplier<T> stap) {
            ClassLoader vorige = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(build.beanLoader());
            try { return stap.get(); }
            finally { Thread.currentThread().setContextClassLoader(vorige); }
        }
    }
}
