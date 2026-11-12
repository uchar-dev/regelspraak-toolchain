package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.transpiler.tms.PeriodDecisions;

import java.util.List;
import java.util.Map;

/**
 * The session of one case of the {@code uchar-case-v0} format as uchar's replay played it
 * ({@code CaseRunner.report} with a {@code CaseRunner.Handoff}), on a build with readings: what the replay does not
 * return in its report. The beans of the input with their derived values, from which the {@link Verbaliser} explains an
 * outcome, what the session holds at the fixpoint as uchar reads it ({@code DroolsBackend.standingOf}), with or
 * without an expectation on it, which default of
 * each defaults stack decided, who decided each timeline target per period, and how each condition came out
 * ({@code DroolsBackend.readingsOf}). Since card
 * explanation-from-readings the explanation reads the very session the replay judged; until then this class played the
 * case a second time, in a session of its own, and compared its firings with the replay's.
 */
public final class CaseSession {

    private CaseSession() {}

    /** The input beans by label, their object types, the firings of the replay's report, and {@code standing}, what
     *  the session holds at the fixpoint as uchar reads it ({@code DroolsBackend.standingOf}), read before the
     *  write-back with the labels of the input beans: the end state of a session without a standing fault, and
     *  otherwise a view of the session that nothing norms (uchar's RUNTIME-SEMANTICS ch. 5.7). The beans carry the
     *  derived values written back, unless an evaluation fault stands at the fixpoint ({@code standing.faults()} not
     *  empty): the write-back throws it, so the beans then hold the input alone and the fault is in
     *  {@code standing} as data.
     *  {@code decisions} ({@code DroolsBackend.decisionsOf}), {@code periodDecisions}
     *  ({@code DroolsBackend.periodDecisionsOf}) and {@code readings} ({@code DroolsBackend.readingsOf}) are read off
     *  the same session, and are empty where an evaluation fault stands: the session has no answer then. */
    public record Outcome(Map<String, Object> beans, Map<String, ObjectType> typePerLabel,
                          List<ScenarioResult.Firing> firings, EndState standing,
                          List<DefaultsDecision> decisions, List<PeriodDecisions> periodDecisions,
                          List<ConditionReading> readings) {}

    /** A getter read, the boolean {@code is…} form included. */
    public static Object read(Object bean, String getter) {
        for (String m : new String[]{ getter, "is" + getter.substring(3) }) {
            try { return bean.getClass().getMethod(m).invoke(bean); }
            catch (NoSuchMethodException next) { /* the other spelling */ }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("kon " + m + " niet lezen", e); }
        }
        throw new IllegalStateException("geen getter " + getter + " op " + bean.getClass().getSimpleName());
    }
}
