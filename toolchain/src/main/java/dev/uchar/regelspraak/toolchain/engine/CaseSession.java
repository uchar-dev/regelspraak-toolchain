package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;
import dev.uchar.regelspraak.transpiler.tms.PeriodDecisions;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The session of one case of the {@code uchar-case-v0} format as uchar's replay played it
 * ({@code CaseRunner.report} with a {@code CaseRunner.Handoff}), on a build with readings: what the replay does not
 * return in its report. The beans of the input with their derived values, from which the {@link Verbaliser} explains an
 * outcome, uchar's {@link Fixpoint}, what stands at the fixpoint with or without an expectation on it, the values the
 * creations put on the objects they made, which default of
 * each defaults stack decided, who decided each timeline target per period, and how each condition came out
 * ({@code DroolsBackend.readingsOf}). Since card
 * explanation-from-readings the explanation reads the very session the replay judged; until then this class played the
 * case a second time, in a session of its own, and compared its firings with the replay's.
 */
public final class CaseSession {

    private CaseSession() {}

    /** The input beans by label, their object types, the firings of the replay's report, and uchar's reading of the
     *  fixpoint, read before the write-back with the labels of the input beans, with the values of the making
     *  ({@link #making}) read at the same moment. The beans carry the derived values
     *  written back, unless an evaluation fault stands at the fixpoint ({@code fixpoint.faults()} not empty): the
     *  write-back throws it, so the beans then hold the input alone and the fault is in the fixpoint as data.
     *  {@code decisions} ({@code DroolsBackend.decisionsOf}), {@code periodDecisions}
     *  ({@code DroolsBackend.periodDecisionsOf}) and {@code readings} ({@code DroolsBackend.readingsOf}) are read off
     *  the same session, and are empty where an evaluation fault stands: the session has no answer then. */
    public record Outcome(Map<String, Object> beans, Map<String, ObjectType> typePerLabel,
                          List<ScenarioResult.Firing> firings, Fixpoint fixpoint, Map<Object, Map<String, Object>> making,
                          List<DefaultsDecision> decisions, List<PeriodDecisions> periodDecisions,
                          List<ConditionReading> readings) {}

    /**
     * The values of the making: per object a creation made ({@link Fixpoint#objects}), by identity, per attribute of its
     * type and its supertypes the value the creation put on its bean, in the form the bean field holds it (a timeline as
     * its list of segments, a dimensioned attribute as uchar's map of cells), an attribute without one (null, an empty
     * list or map) left out. A made object has no input, and this value stands in its place (rank 2 of uchar's stack,
     * RUNTIME-SEMANTICS ch. 7.8). Read off the beans of {@code fixpoint} as uchar's entry read it, before the
     * write-back: that puts the derived values on the same beans, and so replaces the value of every member a rule
     * decided. A map of cells is copied, as the write-back puts a derived cell into the same map; a timeline it
     * replaces with a list of its own.
     */
    public static Map<Object, Map<String, Object>> making(DroolsBackend.Build build, Fixpoint fixpoint) {
        Map<String, ObjectType> typePerClass = new HashMap<>();
        for (ObjectType ot : build.model().objectTypes.values())
            typePerClass.put(FactModelGenerator.PACKAGE + "." + Namen.ident(ot.name, true), ot);
        Map<Object, Map<String, Object>> sink = new IdentityHashMap<>();
        for (Fixpoint.MadeObject g : fixpoint.objects()) {
            Object bean = g.object();
            ObjectType ot = typePerClass.get(bean.getClass().getName());
            if (ot == null)
                throw new IllegalStateException("het gemaakte object " + bean.getClass().getName()
                        + " is van geen objecttype van het model");
            Map<String, Object> values = new LinkedHashMap<>();
            for (ObjectType t : ot.chain())
                for (String a : t.attributes.keySet()) {
                    Object v = read(bean, Namen.getter(a));
                    if (v instanceof List<?> l && l.isEmpty()) v = null;
                    else if (v instanceof Map<?, ?> m) v = m.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(m));
                    if (v != null) values.put(a, v);
                }
            sink.put(bean, Collections.unmodifiableMap(values));
        }
        return sink;
    }

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
