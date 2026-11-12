package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The evaluation faults that stand at the fixpoint of a request's session, as uchar's entry reads them
 * ({@code DroolsBackend.fixpointOf(…).faults()}): the rules give no answer on this input. A fault there is an evaluation
 * fault by its place at the fixpoint, so each one is an element: it keeps uchar's sentence and carries the fault kind
 * uchar's public fault API names for it ({@link CaseRunner#faultKinds}): its code {@code EF-###} of uchar's
 * RUNTIME-SEMANTICS ch. 5.7b and its parameters, as a red case of uchar's corpus pins them in {@code expectedFault}. A
 * fault of which uchar names no kind has no code and no parameters. Both adapters answer it with a 422
 * ({@link AnswerBuilder#evaluationFault}).
 *
 * <p>It carries the {@link Fixpoint} the faults stand at, with what the session holds there as uchar reads it at the
 * same moment ({@code DroolsBackend.standingOf}): no end state and no outcome, which a session with a standing fault
 * does not have (uchar's RUNTIME-SEMANTICS ch. 5.7, 5.12a), but the view of the session the fixpoint detail shows; and,
 * when the request asked to see its session ({@link RequestOptions#detailFlags()}), the fields the 422 gives beside the
 * faults ({@link #detail()}). Its cause is the exception uchar's write-back throws for these faults: the fault's own
 * for one, uchar's {@code EvaluationFaults} for two or more, the one uchar's scenario report names in its diagnose.
 *
 * <p>A value of the request that its declaration does not hold is an evaluation fault too, EF-004, which uchar's input
 * fence raises before any session ({@code CaseRunner.inputFits}, {@link #ofInput}): it stands at no fixpoint and has
 * no session to show, and its cause is the first such value, the one uchar's replay of the same input refuses. So is a
 * cell of a dimensioned attribute whose labels name no cell (EF-034) or that the request gives twice (EF-035), which
 * uchar's input step refuses before any session as well ({@code CaseInput.CellFault}); a value of a cell its
 * declaration does not hold is EF-004.
 */
public final class EvaluationFault extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One fault: the code of its kind ({@code EF-###}, null where uchar names none), the parameters of the kind (empty
     *  then) and uchar's sentence. */
    public record Fault(String code, Map<String, String> params, String sentence) {

        /** The element of uchar's fault {@code f}, with the kind its fault API names. */
        static Fault of(RuntimeException f, String sentence) {
            List<CaseRunner.FaultKind> kinds = CaseRunner.faultKinds(f);
            // one fault names one kind; several stand only in uchar's EvaluationFaults, which is no element
            if (kinds.size() > 1)
                throw new IllegalStateException("uchar noemt voor één fout " + kinds.size() + " soorten: " + kinds);
            return kinds.isEmpty() ? new Fault(null, Map.of(), sentence)
                    : new Fault(kinds.get(0).code(), java.util.Collections.unmodifiableMap(
                            new java.util.LinkedHashMap<>(kinds.get(0).params())), sentence);
        }
    }

    private final transient List<Fault> errors;
    private final transient Fixpoint fixpoint;
    private final transient EndState standing;
    private final transient ObjectNode detail;

    private EvaluationFault(List<Fault> errors, RuntimeException cause, Fixpoint fixpoint, EndState standing,
                            ObjectNode detail) {
        super(errors.size() == 1 ? "de regels geven op deze invoer een evaluatiefout"
                : "de regels geven op deze invoer " + errors.size() + " evaluatiefouten", cause);
        this.errors = List.copyOf(errors);
        this.fixpoint = fixpoint;
        this.standing = standing;
        this.detail = detail;
    }

    /** The faults that stand at {@code fixpunt}, one element per fault in the order of its {@code errors}, which is
     *  uchar's: by the name of the form, then by the sentence; {@code standing} what the session holds at that
     *  fixpoint. {@code cause} is what uchar's write-back threw for them. */
    static EvaluationFault of(Fixpoint fixpoint, EndState standing, RuntimeException cause) {
        List<Fault> sink = new ArrayList<>();
        for (dev.uchar.regelspraak.transpiler.tms.Fault f : fixpoint.faults()) sink.add(Fault.of(f.fault(), f.message()));
        return new EvaluationFault(sink, cause, fixpoint, standing, null);
    }

    /** The values of a request that uchar's input refuses: its input fence ({@link CaseRunner.InputPrecisionFault},
     *  EF-004) and its reading of the cells (EF-034, EF-035), one element per value in the order the service reads
     *  them: the parameters, then the first attribute or cell uchar's input step refuses, where it stops. No session
     *  ran, so there is no {@link #fixpoint} and no detail. */
    static EvaluationFault ofInput(List<RuntimeException> faults) {
        List<Fault> sink = new ArrayList<>();
        for (RuntimeException f : faults) sink.add(Fault.of(f, f.getMessage()));
        return new EvaluationFault(sink, faults.get(0), null, null, null);
    }

    /** The same faults with {@code detail}, the fields the request asked to see of its session. */
    EvaluationFault withDetail(ObjectNode detail) {
        return new EvaluationFault(errors, (RuntimeException) getCause(), fixpoint, standing, detail);
    }

    /** The faults, in the order uchar reports them. */
    public List<Fault> errors() {
        return errors;
    }

    /** What stands at the fixpoint the faults stand at, as uchar's entry reads it; null for the faults of the input
     *  ({@link #ofInput}). */
    public Fixpoint fixpoint() {
        return fixpoint;
    }

    /** What the session holds at {@link #fixpoint}, as uchar reads it ({@code DroolsBackend.standingOf}); null where
     *  that is null. */
    public EndState standing() {
        return standing;
    }

    /** The fields the 422 gives beside the faults ({@code trace}, {@code dekking}, {@code fixpunt}), or null when the
     *  request asked none. */
    public ObjectNode detail() {
        return detail;
    }
}
