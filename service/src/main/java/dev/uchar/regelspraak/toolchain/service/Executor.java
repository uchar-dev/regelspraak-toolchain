package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.toolchain.engine.CaseSession;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;
import dev.uchar.regelspraak.transpiler.tms.PeriodDecisions;

import org.kie.api.runtime.KieSession;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-request executor: given an entry point's {@link DroolsBackend.Build} (built once at startup by uchar,
 * immutable thereafter) and a mapped {@link World}, it opens a FRESH session of the build's KieBase, lets uchar's input
 * step bind the globals of the case it read ({@link dev.uchar.regelspraak.cases.CaseInput#setGlobals}: the parameters,
 * the rekendatum when the request carried one, the day-kind predicates), and then fills, fires and reads it through
 * uchar's entry: {@link DroolsBackend#insert} puts the input
 * in that uchar's input step read ({@link World#input()}), each to-many list in the replay's order, {@link DroolsBackend#fire}
 * fires to the fixpoint, {@link DroolsBackend#fixpointOf} gives the evaluation faults that stand there, and where none
 * stands {@link DroolsBackend#setDerivedValues} puts the derived values on the beans, which the branch keeps as
 * facts of the session until then. It returns the beans and the fixpoint, whose {@code Inconsistentie} facts the
 * response builder reads.
 * Session-per-request; the KieBase is shared and immutable, so this is thread-safe by construction.
 *
 * <p>Every fault that stands at the fixpoint leaves here as an {@link EvaluationFault}, and nothing is written back: the
 * rules give no answer on this input. Such a fault is an evaluation fault by its place at the fixpoint, whatever its
 * type, so it is read as data before the write-back, which would throw it as an exception like those the entry throws
 * itself for a session used wrongly; those stay internal faults. A run without a fixpoint, at uchar's fire cap or at
 * its creation budget, leaves as a {@link NoFixpoint} with uchar's sentence, and nothing is read or written back. A
 * limit of uchar's branch at the fixpoint (a value it cannot hold, DROOLS-312) is no fault of the rules: uchar's
 * write-back refuses the session as an exclusion with its register code, before the faults beside it, and it leaves as a
 * {@link Limit}.
 */
public final class Executor {

    private final DroolsBackend.Build build;

    public Executor(DroolsBackend.Build build) {
        this.build = build;
    }

    /** The result of a fire: the beans by id with the derived values written back, the firings, what stood at the
     *  fixpoint before the write-back, as uchar's entry reads it with the ids of the request, its §9.5 deposits
     *  included ({@link Fixpoint#inconsistencies}), the values the creations put on the objects they made, read at the
     *  same moment ({@link CaseSession#making}), uchar's verdict on the expectations the caller gave
     *  ({@link DroolsBackend#vergelijk}), null when it gave none, which default of each defaults stack decided
     *  ({@link DroolsBackend#decisionsOf}), who decided each timeline target per period
     *  ({@link DroolsBackend#periodDecisionsOf}) and how each condition came out ({@link DroolsBackend#readingsOf}),
     *  all three empty when the caller did not ask for the explanation. */
    public record Outcome(Map<String, Object> beans, int fired, Fixpoint fixpoint,
                           Map<Object, Map<String, Object>> making,
                           DroolsBackend.Comparison comparison, List<DefaultsDecision> decisions,
                           List<PeriodDecisions> periodDecisions, List<ConditionReading> readings) {}

    /**
     * Fires the entry point's rules over {@code world} and returns the beans with their derived values and what stood
     * at the fixpoint. The globals are those uchar's input step binds for the case it read ({@link World#input()}): the
     * parameters the mapper wrote into it, the {@code rekendatum} of its evaluation date and the day-kind predicates.
     * {@code observer} (null: none) records what the session fires, through uchar's
     * {@link DroolsBackend#fire(DroolsBackend.Build, KieSession, DroolsBackend.Observer)}; it belongs to one session,
     * so the caller gives each request a fresh one. {@code expectations} (null: none) is the {@code expected} of a
     * {@code uchar-case-v0} case whose labels are ids of the request, on which uchar gives its verdict over the
     * written-back session ({@link DroolsBackend#compare}) before the session is disposed. With
     * {@code withExplanation} it reads what the explanation needs off the session after the write-back: which default
     * of each defaults stack decided ({@link DroolsBackend#decisionsOf}), who decided each timeline target per period
     * ({@link DroolsBackend#periodDecisionsOf}) and how each condition came out ({@link DroolsBackend#readingsOf}), the
     * last of a build with readings only; a request without an explanation has no use for it.
     *
     * @throws EvaluationFault the evaluation faults that stand at the fixpoint, one or more, of any type, with the
     *                         fixpoint they stand at; nothing is compared
     * @throws NoFixpoint      a run that reached no fixpoint, with uchar's sentence; nothing is compared
     * @throws Limit           a limit of uchar's branch at the fixpoint, with its code and uchar's sentence
     * @throws dev.uchar.regelspraak.cases.CaseRunner.Excluded an expectation of a form uchar does not compare
     * @throws IllegalArgumentException a defect of {@code expectations} that uchar's replay refuses as well: a kind
     *                                  {@code uchar-case-v0} does not have, a value of another datatype, a scalar
     *                                  expectation on a time-dependent member
     */
    public Outcome fire(World world, DroolsBackend.Observer observer, JsonNode expectations, boolean withExplanation) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(build.beanLoader());   // Drools resolves fact types via the TCCL
        KieSession session = build.kieBase().newKieSession();
        try {
            // the branch states the globals as facts at the first fire, so they all go on before it
            world.input().setGlobals(session);
            // the input as uchar's replay puts it in: the order of a list decides which member a fault of a fold
            // names, so the 422 names the member uchar's replay names on the same input
            DroolsBackend.insert(build, session, world.input().inputData());
            DroolsBackend.Firing firing = observer == null ? DroolsBackend.fire(build, session)
                    : DroolsBackend.fire(build, session, observer);
            if (firing.noFixpoint() != null) throw new NoFixpoint(firing);
            // a fault names its object by the id of the request; a made object has none, and uchar names it as ref does
            Map<Object, String> label = new IdentityHashMap<>();
            world.beans().forEach((id, bean) -> label.put(bean, id));
            // a fault is an evaluation fault by its place at the fixpoint, not by its type: the write-back would throw
            // it as one exception among those the entry throws itself, so the faults are read as data first
            Fixpoint fixpoint = DroolsBackend.fixpointOf(build, session, label::get);
            Map<Object, Map<String, Object>> making = CaseSession.making(build, fixpoint);
            if (!fixpoint.faults().isEmpty()) {
                RuntimeException thrown = rethrown(session, label, fixpoint);
                if (thrown instanceof CaseRunner.Excluded limit) throw new Limit(limit, fixpoint, making);
                throw EvaluationFault.of(fixpoint, making, thrown);
            }
            DroolsBackend.setDerivedValues(build, session, label::get);
            DroolsBackend.Comparison comparison = expectations == null ? null
                    : DroolsBackend.compare(build, session, label::get, expectations);
            List<DefaultsDecision> decisions = withExplanation ? DroolsBackend.decisionsOf(build, session) : List.of();
            List<PeriodDecisions> periodDecisions = withExplanation ? DroolsBackend.periodDecisionsOf(build, session)
                    : List.of();
            List<ConditionReading> readings = withExplanation ? DroolsBackend.readingsOf(build, session) : List.of();
            return new Outcome(world.beans(), firing.firings(), fixpoint, making, comparison, decisions,
                    periodDecisions, readings);
        } finally {
            session.dispose();
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** The exception uchar's write-back throws for the faults that stand at {@code fixpunt}: the fault's own for one,
     *  uchar's {@code EvaluationFaults} for two or more, and the exclusion of a limit of its branch where one stands
     *  there. It writes nothing back then; a write-back that does not throw is a broken promise of uchar's entry. */
    private RuntimeException rethrown(KieSession session, Map<Object, String> label, Fixpoint fixpoint) {
        try {
            DroolsBackend.setDerivedValues(build, session, label::get);
        } catch (RuntimeException e) {
            return e;
        }
        throw new IllegalStateException("uchars terugzetting wierp niets, terwijl aan het fixpunt "
                + fixpoint.faults().size() + " evaluatiefout(en) staan, de eerste: " + fixpoint.faults().get(0).message());
    }
}
