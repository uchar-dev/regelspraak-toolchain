package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.runtime.Breuk;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;
import dev.uchar.regelspraak.transpiler.tms.InputFormFault;
import dev.uchar.regelspraak.transpiler.tms.ComputeFault;
import dev.uchar.regelspraak.transpiler.tms.WriteBoundaryFault;
import dev.uchar.regelspraak.transpiler.tms.WritersConflict;

import java.util.ArrayList;
import java.util.List;

/**
 * The evaluation faults that stand at the fixpoint of a request's session, as uchar's entry reads them
 * ({@code DroolsBackend.fixpointOf(…).fouten()}): the rules give no answer on this input. A fault there is an evaluation
 * fault by its place at the fixpoint, whatever the type of its exception, so each one is an element: it keeps uchar's
 * sentence and gets the kind of its exception, one of the five types uchar's README names ("Faults") or
 * {@link Kind#OTHER} for any other, such as the {@code IllegalStateException} of a fact creation that gives a to-one
 * role a second filling. Both adapters answer it with a 422 ({@link AnswerBuilder#evaluationFault}).
 *
 * <p>It carries the {@link Fixpoint} the faults stand at, and, when the request asked to see its session
 * ({@link RequestOptions#detailFlags()}), the fields the 422 gives beside the faults ({@link #detail()}). Its cause
 * is the exception uchar's write-back throws for these faults: the fault's own for one, uchar's
 * {@code EvaluationFaults} for two or more, the one uchar's scenario report names in its diagnose.
 *
 * <p>A value of the request that its declaration does not hold is an evaluation fault too, EF-004, which uchar's input
 * fence raises before any session ({@code CaseRunner.inputFits}, {@link Kind#INPUT_PRECISION_FAULT}, {@link #ofInput}):
 * it stands at no fixpoint and has no session to show, and its cause is the first such value, the one uchar's replay
 * of the same input refuses. So is a cell of a dimensioned attribute whose labels name no cell (EF-034) or that the
 * request gives twice (EF-035), which uchar's input step refuses before any session as well
 * ({@link Kind#CELL_FAULT}); a value of a cell its declaration does not hold is EF-004.
 */
public final class EvaluationFault extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The kind of a fault, by the type of uchar's exception; {@link #CELL_FAULT} by the cell the request wrote, since
     *  uchar's type of it is not public. */
    public enum Kind {
        DIVISION_BY_ZERO, COMPUTE_FAULT, WRITERS_CONFLICT, INPUT_FORM_FAULT, WRITE_BOUNDARY_FAULT, INPUT_PRECISION_FAULT,
        CELL_FAULT, OTHER
    }

    /** One fault: its kind and uchar's sentence. */
    public record Fault(Kind kind, String sentence) {}

    /** A value of a request that uchar's input refuses before any session: its kind and uchar's exception. */
    public record InputFault(Kind kind, RuntimeException fault) {}

    private final transient List<Fault> errors;
    private final transient Fixpoint fixpoint;
    private final transient ObjectNode detail;

    private EvaluationFault(List<Fault> errors, RuntimeException cause, Fixpoint fixpoint, ObjectNode detail) {
        super(errors.size() == 1 ? "de regels geven op deze invoer een evaluatiefout"
                : "de regels geven op deze invoer " + errors.size() + " evaluatiefouten", cause);
        this.errors = List.copyOf(errors);
        this.fixpoint = fixpoint;
        this.detail = detail;
    }

    /** The faults that stand at {@code fixpunt}, one element per fault in the order of its {@code errors}, which is
     *  uchar's: by the name of the form, then by the sentence. {@code cause} is what uchar's write-back threw for
     *  them. */
    static EvaluationFault of(Fixpoint fixpoint, RuntimeException cause) {
        List<Fault> sink = new ArrayList<>();
        for (dev.uchar.regelspraak.transpiler.tms.Fault f : fixpoint.faults()) sink.add(new Fault(kind(f.fault()), f.message()));
        return new EvaluationFault(sink, cause, fixpoint, null);
    }

    /** The values of a request that uchar's input refuses: its input fence ({@link CaseRunner.InputPrecisionFault},
     *  EF-004) and its reading of the cells (EF-034, EF-035), one element per value in the order the service reads
     *  them: the parameters, then the first attribute or cell uchar's input step refuses, where it stops. No session
     *  ran, so there is no {@link #fixpoint} and no detail. */
    static EvaluationFault ofInput(List<InputFault> faults) {
        List<Fault> sink = new ArrayList<>();
        for (InputFault f : faults) sink.add(new Fault(f.kind(), f.fault().getMessage()));
        return new EvaluationFault(sink, faults.get(0).fault(), null, null);
    }

    /** The same faults with {@code detail}, the fields the request asked to see of its session. */
    EvaluationFault withDetail(ObjectNode detail) {
        return new EvaluationFault(errors, (RuntimeException) getCause(), fixpoint, detail);
    }

    /** The kind of uchar's fault {@code f}. */
    public static Kind kind(RuntimeException f) {
        return switch (f) {
            case Breuk.DelingDoorNul d -> Kind.DIVISION_BY_ZERO;
            case ComputeFault r -> Kind.COMPUTE_FAULT;
            case WritersConflict s -> Kind.WRITERS_CONFLICT;
            case InputFormFault i -> Kind.INPUT_FORM_FAULT;
            case WriteBoundaryFault w -> Kind.WRITE_BOUNDARY_FAULT;
            case CaseRunner.InputPrecisionFault p -> Kind.INPUT_PRECISION_FAULT;
            default -> Kind.OTHER;
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

    /** The fields the 422 gives beside the faults ({@code trace}, {@code dekking}, {@code fixpunt}), or null when the
     *  request asked none. */
    public ObjectNode detail() {
        return detail;
    }
}
