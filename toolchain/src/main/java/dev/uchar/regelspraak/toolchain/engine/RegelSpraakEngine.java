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
     * {@code zetAfgeleideWaarden}): the beans of {@link #insert} and {@link #insertAll} are gathered until the first
     * {@link #fire}, which puts them in through {@code voegIn} and fires through {@code vuur}; a later fire only fires.
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
        private boolean gevuld;

        Session(DroolsBackend.Build build, KieSession session) {
            this.build = build;
            this.session = session;
        }

        /** Adds {@code fact} to the input of the first {@link #fire}. */
        public Session insert(Object fact) { verzamel(fact); return this; }

        /** Adds {@code facts}, in this order, to the input of the first {@link #fire}. */
        public Session insertAll(Object... facts) { for (Object f : facts) verzamel(f); return this; }

        // voegIn fills a fresh session only, and the places of a list are numbered over the beans of one input
        private void verzamel(Object fact) {
            Objects.requireNonNull(fact, "fact");
            if (gevuld)
                throw new IllegalStateException("insert na de eerste fire: de sessie is gevuld via uchars ingang"
                        + " (DroolsBackend.voegIn), die alleen een verse sessie vult; een object erbij vraagt een verse"
                        + " sessie");
            invoer.add(fact);
        }

        /** Sets a global, before the first {@link #fire}: the branch states a global once, at the first fire, and
         *  uchar's entry refuses a session whose global changed after it. */
        public Session setGlobal(String name, Object value) { session.setGlobal(name, value); return this; }

        /** Fires to the fixpoint through uchar's entry, the first time after putting the gathered beans in, and returns
         *  the number of firings. A run without a fixpoint, at the cap or at the creation budget, throws an
         *  {@link IllegalStateException} with uchar's sentence. A model with a creation fires once per session, and
         *  a fire after {@link #zetTerug} is refused, each by uchar's entry with the way on. */
        public int fire() {
            return metBeanLoader(() -> {
                if (!gevuld) {
                    DroolsBackend.voegIn(build, session, new Invoer().beans(invoer));
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
