package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;

import java.util.List;
import java.util.Map;

/**
 * The session of one case of the {@code uchar-case-v0} format as uchar's replay played it
 * ({@code CaseRunner.report} with a {@code CaseRunner.Handoff}), on a build with readings: what the replay does not
 * return in its report. The beans of the input with their derived values, from which the {@link Verbaliser} explains an
 * outcome, uchar's {@link Fixpoint}, what stands at the fixpoint with or without an expectation on it, which default of
 * each defaults stack decided, and how each condition came out ({@code DroolsBackend.readingsOf}). Since card
 * explanation-from-readings the explanation reads the very session the replay judged; until then this class played the
 * case a second time, in a session of its own, and compared its firings with the replay's.
 */
public final class CaseSession {

    private CaseSession() {}

    /** The input beans by label, their object types, the firings of the replay's report, and uchar's reading of the
     *  fixpoint, read before the write-back with the labels of the input beans. The beans carry the derived values
     *  written back, unless an evaluation fault stands at the fixpoint ({@code fixpoint.faults()} not empty): the
     *  write-back throws it, so the beans then hold the input alone and the fault is in the fixpoint as data.
     *  {@code decisions} ({@code DroolsBackend.decisionsOf}) and {@code readings} ({@code DroolsBackend.readingsOf}) are
     *  read off the same session, and are empty where an evaluation fault stands: the session has no answer then. */
    public record Outcome(Map<String, Object> beans, Map<String, ObjectType> typePerLabel,
                          List<ScenarioResult.Firing> firings, Fixpoint fixpoint, List<DefaultsDecision> decisions,
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
