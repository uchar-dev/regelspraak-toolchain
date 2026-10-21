package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;

import org.kie.api.runtime.KieSession;

import java.time.LocalDate;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-request executor: given an entry point's {@link DroolsBackend.Build} (built once at startup by uchar,
 * immutable thereafter) and a mapped {@link World}, it opens a FRESH session of the build's KieBase, sets the
 * parameter globals (the descriptor defaults, overridden by the request), sets the rekendatum global when the request
 * carried one, and then fills, fires and reads it through uchar's entry: {@link DroolsBackend#insert} puts the input
 * in that uchar's input step read ({@link World#input()}), each to-many list in the replay's order, {@link DroolsBackend#fire}
 * fires to the fixpoint, {@link DroolsBackend#fixpointOf} gives the evaluation faults that stand there, and where none
 * stands {@link DroolsBackend#setDerivedValues} puts the derived values on the beans, which the branch keeps as
 * facts of the session until then. It returns the beans and the {@code Inconsistentie} facts for the response builder.
 * Session-per-request; the KieBase is shared and immutable, so this is thread-safe by construction.
 *
 * <p>Every fault that stands at the fixpoint leaves here as an {@link EvaluationFault}, and nothing is written back: the
 * rules give no answer on this input. Such a fault is an evaluation fault by its place at the fixpoint, whatever its
 * type, so it is read as data before the write-back, which would throw it as an exception like those the entry throws
 * itself for a session used wrongly; those stay internal faults. A run without a fixpoint, at uchar's fire cap or at
 * its creation budget, leaves as a {@link NoFixpoint} with uchar's sentence, and nothing is read or written back.
 */
public final class Executor {

    private final DroolsBackend.Build build;
    private final Map<String, Object> defaultGlobals;   // descriptor parameter defaults (global name -> value)

    public Executor(DroolsBackend.Build build, Map<String, Object> defaultGlobals) {
        this.build = build;
        this.defaultGlobals = defaultGlobals == null ? Map.of() : defaultGlobals;
    }

    /** The result of a fire: the beans by id with the derived values written back, the firings, the
     *  inconsistenties, what stood at the fixpoint before the write-back, as uchar's entry reads it, uchar's
     *  verdict on the expectations the caller gave ({@link DroolsBackend#vergelijk}), null when it gave none, and which
     *  default of each defaults stack decided ({@link DroolsBackend#decisionsOf}), empty when the caller did not ask. */
    public record Outcome(Map<String, Object> beans, int fired, List<Object> inconsistencies, Fixpoint fixpoint,
                           DroolsBackend.Comparison comparison, List<DefaultsDecision> decisions) {}

    /** Fire the entry point's rules over {@code world} and return the beans with their derived values and the
     *  inconsistenties. */
    public Outcome fire(World world) {
        return fire(world, Map.of());
    }

    /**
     * Fire the entry point's rules over {@code world}, with {@code setGlobals} the parameterset-vintage resolved
     * values (fase 5). PRECEDENCE (decision D): descriptor defaults FIRST, then the set-resolved values (the
     * DecisionCore conflict rule guarantees these never name the same parameter as a default), then the request
     * parameter overrides LAST (explicit caller-intent wins — mirrors the scenario situatie-override). The
     * rekendatum global stays {@code world.evaluationDate()} — that IS the peildatum (the one date authority).
     *
     * @throws Evaluatiefout the evaluation faults that stand at the fixpoint, one or more, of any type
     */
    public Outcome fire(World world, Map<String, Object> setGlobals) {
        return fire(world, setGlobals, null);
    }

    /**
     * As {@link #fire(Wereld, Map)}, with {@code observer} (null: none) recording what the session fires, through
     * uchar's {@link DroolsBackend#fire(DroolsBackend.Build, KieSession, DroolsBackend.Observer)}. A waarnemer belongs
     * to one session, so the caller gives each request a fresh one and reads it after this returns or throws.
     *
     * @throws Evaluatiefout the evaluation faults that stand at the fixpoint, one or more, of any type, with the
     *                       fixpoint they stand at
     */
    public Outcome fire(World world, Map<String, Object> setGlobals, DroolsBackend.Observer observer) {
        return fire(world, setGlobals, observer, null);
    }

    /**
     * As {@link #fire(Wereld, Map, DroolsBackend.Observer)}, and with {@code expectations} (null: none), the
     * {@code expected} of a {@code uchar-case-v0} case whose labels are ids of the request, uchar's verdict on them over
     * the written-back session ({@link DroolsBackend#vergelijk}, with the ids of the request as labels), before the
     * session is disposed. uchar compares; this class only hands the session over.
     *
     * @throws Evaluatiefout            the evaluation faults that stand at the fixpoint, with the fixpoint they stand
     *                                  at; nothing is compared then, as uchar's report compares nothing of such a case
     * @throws NoFixpoint              a run that reached no fixpoint, with uchar's sentence; nothing is compared
     * @throws dev.uchar.regelspraak.cases.CaseRunner.Excluded an expectation of a form uchar does not compare, in the
     *                                  sentence of its replay
     */
    public Outcome fire(World world, Map<String, Object> setGlobals, DroolsBackend.Observer observer,
                         JsonNode expectations) {
        return fire(world, setGlobals, observer, expectations, false);
    }

    /**
     * As {@link #fire(World, Map, DroolsBackend.Observer, JsonNode)}, and with {@code withDecisions} which default of
     * each defaults stack decided, read off the session after the write-back ({@link DroolsBackend#decisionsOf}), for the
     * explanation. Only asked for an explanation: a pointer uchar cannot give fails the read, and a request without an
     * explanation has no use for it.
     */
    public Outcome fire(World world, Map<String, Object> setGlobals, DroolsBackend.Observer observer,
                         JsonNode expectations, boolean withDecisions) {
        // descriptor defaults first, set-vintage values next, request parameters override last
        Map<String, Object> globals = new LinkedHashMap<>(defaultGlobals);
        if (setGlobals != null) globals.putAll(setGlobals);
        globals.putAll(world.globals());

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(build.beanLoader());   // Drools resolves fact types via the TCCL
        KieSession session = build.kieBase().newKieSession();
        try {
            // the branch states the globals as facts at the first fire, so they all go on before it
            for (var e : build.dayKindGlobals().entrySet()) setGlobalSafe(session, e.getKey(), e.getValue());
            for (var e : globals.entrySet()) setGlobalSafe(session, e.getKey(), e.getValue());
            LocalDate rd = world.evaluationDate();
            if (rd != null) setGlobalSafe(session, "rekendatum", rd);
            // the input as uchar's replay puts it in: the order of a list decides which member a fault of a fold
            // names, so the 422 names the member uchar's replay names on the same input
            DroolsBackend.insert(build, session, world.input().inputData());
            DroolsBackend.Firing firing = observer == null ? DroolsBackend.fire(build, session)
                    : DroolsBackend.fire(build, session, observer);
            if (firing.noFixpoint() != null) throw new NoFixpoint(firing.noFixpoint());
            // a fault names its object by the id of the request; a made object has none, and uchar names it as ref does
            Map<Object, String> label = new IdentityHashMap<>();
            world.beans().forEach((id, bean) -> label.put(bean, id));
            // a fault is an evaluation fault by its place at the fixpoint, not by its type: the write-back would throw
            // it as one exception among those the entry throws itself, so the faults are read as data first
            Fixpoint fixpoint = DroolsBackend.fixpointOf(build, session, label::get);
            if (!fixpoint.faults().isEmpty()) throw EvaluationFault.of(fixpoint, rethrown(session, label, fixpoint));
            DroolsBackend.setDerivedValues(build, session, label::get);
            List<Object> inc = readInconsistencies(session);
            DroolsBackend.Comparison comparison = expectations == null ? null
                    : DroolsBackend.compare(build, session, label::get, expectations);
            List<DefaultsDecision> decisions = withDecisions ? DroolsBackend.decisionsOf(build, session) : List.of();
            return new Outcome(world.beans(), firing.firings(), inc, fixpoint, comparison, decisions);
        } finally {
            session.dispose();
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** The exception uchar's write-back throws for the faults that stand at {@code fixpunt}: the fault's own for one,
     *  uchar's {@code EvaluationFaults} for two or more. It writes nothing back then; a write-back that does not throw
     *  is a broken promise of uchar's entry. */
    private RuntimeException rethrown(KieSession session, Map<Object, String> label, Fixpoint fixpoint) {
        try {
            DroolsBackend.setDerivedValues(build, session, label::get);
        } catch (RuntimeException e) {
            return e;
        }
        throw new IllegalStateException("uchars terugzetting wierp niets, terwijl aan het fixpunt "
                + fixpoint.faults().size() + " evaluatiefout(en) staan, de eerste: " + fixpoint.faults().get(0).message());
    }

    /** setGlobal tolerating a global the compiled DRL did not declare (a parameter no scoped rule reads is not
     *  registered as a global — writing it would throw; harmless to skip). Mirrors ScenarioRunner. */
    private static void setGlobalSafe(KieSession session, String name, Object value) {
        try { session.setGlobal(name, value); }
        catch (RuntimeException unknown) { /* global not declared: no scoped rule reads it */ }
    }

    /** uchar's generator writes the {@code Inconsistentie} bean for every model, so a class loader without it is not
     *  the one the KieBase was built against: that fails here rather than dropping every inconsistency. */
    private List<Object> readInconsistencies(KieSession session) {
        String name = FactModelGenerator.PACKAGE + ".Inconsistentie";
        Class<?> inc;
        try {
            inc = Class.forName(name, true, build.beanLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("de bean-classloader van dit entrypoint draagt " + name + " niet", e);
        }
        return List.copyOf(session.getObjects(inc::isInstance));
    }
}
