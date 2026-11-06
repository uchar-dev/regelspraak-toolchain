package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.runtime.Breuk;
import dev.uchar.regelspraak.transpiler.tms.CharacteristicPeriodsFault;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;
import dev.uchar.regelspraak.transpiler.tms.InputFormFault;
import dev.uchar.regelspraak.transpiler.tms.ComputeFault;
import dev.uchar.regelspraak.transpiler.tms.OpenSegmentFault;
import dev.uchar.regelspraak.transpiler.tms.SecondFillingFault;
import dev.uchar.regelspraak.transpiler.tms.WriteBoundaryFault;
import dev.uchar.regelspraak.transpiler.tms.WritersConflict;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The evaluation faults that stand at the fixpoint of a request's session, as uchar's entry reads them
 * ({@code DroolsBackend.fixpointOf(…).fouten()}): the rules give no answer on this input. A fault there is an evaluation
 * fault by its place at the fixpoint, so each one is an element: it keeps uchar's sentence and gets the kind of its
 * exception, one per type that uchar's README names ("Faults") and that uchar's branch allows at the fixpoint
 * ({@code Fault.KINDS}): among them the two faults of a timeline over all time ({@code OpenSegmentFault}, EF-036, and
 * {@code CharacteristicPeriodsFault}, EF-023, uchar's RUNTIME-SEMANTICS ch. 3.6 and 3.11) and the second filling of a
 * to-one role by a fact creation ({@code SecondFillingFault}, EF-017, ch. 5.5). Both adapters answer it with a 422
 * ({@link AnswerBuilder#evaluationFault}).
 *
 * <p>It carries the {@link Fixpoint} the faults stand at, with the values the creation assigned read at the same moment
 * ({@code CaseSession.making}), and, when the request asked to see its session
 * ({@link RequestOptions#detailFlags()}), the fields the 422 gives beside the faults ({@link #detail()}). Its cause
 * is the exception uchar's write-back throws for these faults: the fault's own for one, uchar's
 * {@code EvaluationFaults} for two or more, the one uchar's scenario report names in its diagnose.
 *
 * <p>A value of the request that its declaration does not hold is an evaluation fault too, EF-004, which uchar's input
 * fence raises before any session ({@code CaseRunner.inputFits}, {@link Kind#INPUT_PRECISION_FAULT}, {@link #ofInput}):
 * it stands at no fixpoint and has no session to show, and its cause is the first such value, the one uchar's replay
 * of the same input refuses. So is a cell of a dimensioned attribute whose labels name no cell (EF-034) or that the
 * request gives twice (EF-035), which uchar's input step refuses before any session as well
 * ({@code CaseInput.CellFault}, {@link Kind#CELL_FAULT}); a value of a cell its declaration does not hold is EF-004.
 */
public final class EvaluationFault extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The kind of a fault, by the type of uchar's exception ({@link #CELL_FAULT}: {@code CaseInput.CellFault}). */
    public enum Kind {
        DIVISION_BY_ZERO, COMPUTE_FAULT, WRITERS_CONFLICT, INPUT_FORM_FAULT, WRITE_BOUNDARY_FAULT, INPUT_PRECISION_FAULT,
        CELL_FAULT, OPEN_SEGMENT_FAULT, CHARACTERISTIC_PERIODS_FAULT, SECOND_FILLING_FAULT
    }

    /** One fault: its kind and uchar's sentence. */
    public record Fault(Kind kind, String sentence) {}

    /** A value of a request that uchar's input refuses before any session: its kind and uchar's exception. */
    public record InputFault(Kind kind, RuntimeException fault) {}

    private final transient List<Fault> errors;
    private final transient Fixpoint fixpoint;
    private final transient Map<Object, Map<String, Object>> making;
    private final transient ObjectNode detail;

    private EvaluationFault(List<Fault> errors, RuntimeException cause, Fixpoint fixpoint,
                            Map<Object, Map<String, Object>> making, ObjectNode detail) {
        super(errors.size() == 1 ? "de regels geven op deze invoer een evaluatiefout"
                : "de regels geven op deze invoer " + errors.size() + " evaluatiefouten", cause);
        this.errors = List.copyOf(errors);
        this.fixpoint = fixpoint;
        this.making = making;
        this.detail = detail;
    }

    /** The faults that stand at {@code fixpunt}, one element per fault in the order of its {@code errors}, which is
     *  uchar's: by the name of the form, then by the sentence; {@code making} the values the creation assigned at that
     *  fixpoint. {@code cause} is what uchar's write-back threw for them. */
    static EvaluationFault of(Fixpoint fixpoint, Map<Object, Map<String, Object>> making, RuntimeException cause) {
        List<Fault> sink = new ArrayList<>();
        for (dev.uchar.regelspraak.transpiler.tms.Fault f : fixpoint.faults()) sink.add(new Fault(kind(f.fault()), f.message()));
        return new EvaluationFault(sink, cause, fixpoint, making, null);
    }

    /** The values of a request that uchar's input refuses: its input fence ({@link CaseRunner.InputPrecisionFault},
     *  EF-004) and its reading of the cells (EF-034, EF-035), one element per value in the order the service reads
     *  them: the parameters, then the first attribute or cell uchar's input step refuses, where it stops. No session
     *  ran, so there is no {@link #fixpoint} and no detail. */
    static EvaluationFault ofInput(List<InputFault> faults) {
        List<Fault> sink = new ArrayList<>();
        for (InputFault f : faults) sink.add(new Fault(f.kind(), f.fault().getMessage()));
        return new EvaluationFault(sink, faults.get(0).fault(), null, null, null);
    }

    /** The same faults with {@code detail}, the fields the request asked to see of its session. */
    EvaluationFault withDetail(ObjectNode detail) {
        return new EvaluationFault(errors, (RuntimeException) getCause(), fixpoint, making, detail);
    }

    /** The kind of uchar's fault {@code f}. uchar's branch puts no other type at the fixpoint ({@code Fault.KINDS}), so
     *  another type is a broken contract and fails here, naming its type. */
    public static Kind kind(RuntimeException f) {
        return switch (f) {
            case Breuk.DelingDoorNul d -> Kind.DIVISION_BY_ZERO;
            case ComputeFault r -> Kind.COMPUTE_FAULT;
            case WritersConflict s -> Kind.WRITERS_CONFLICT;
            case InputFormFault i -> Kind.INPUT_FORM_FAULT;
            case WriteBoundaryFault w -> Kind.WRITE_BOUNDARY_FAULT;
            case CaseRunner.InputPrecisionFault p -> Kind.INPUT_PRECISION_FAULT;
            case OpenSegmentFault o -> Kind.OPEN_SEGMENT_FAULT;
            case CharacteristicPeriodsFault c -> Kind.CHARACTERISTIC_PERIODS_FAULT;
            case SecondFillingFault s -> Kind.SECOND_FILLING_FAULT;
            default -> throw new IllegalStateException("geen evaluatiefout die uchars tak aan het fixpunt zet"
                    + " (Fault.KINDS): " + f.getClass().getName() + ": " + f.getMessage(), f);
        };
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

    /** The values the creations put on the objects they made, at {@link #fixpoint}; null where that is null. */
    public Map<Object, Map<String, Object>> making() {
        return making;
    }

    /** The fields the 422 gives beside the faults ({@code trace}, {@code dekking}, {@code fixpunt}), or null when the
     *  request asked none. */
    public ObjectNode detail() {
        return detail;
    }
}
