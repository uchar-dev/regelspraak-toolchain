package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.runtime.Breuk;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;
import dev.uchar.regelspraak.transpiler.tms.InputFormFault;
import dev.uchar.regelspraak.transpiler.tms.ComputeFault;
import dev.uchar.regelspraak.transpiler.tms.WritersConflict;

import java.util.ArrayList;
import java.util.List;

/**
 * The evaluation faults that stand at the fixpoint of a request's session, as uchar's entry reads them
 * ({@code DroolsBackend.fixpointOf(…).fouten()}): the rules give no answer on this input. A fault there is an evaluation
 * fault by its place at the fixpoint, whatever the type of its exception, so each one is an element: it keeps uchar's
 * sentence and gets the kind of its exception, one of the four types uchar's README names ("Faults") or
 * {@link Kind#OTHER} for any other, such as the {@code IllegalStateException} of a fact creation that gives a to-one
 * role a second filling. Both adapters answer it with a 422 ({@link AnswerBuilder#evaluationFault}).
 *
 * <p>It carries the {@link Fixpoint} the faults stand at, and, when the request asked to see its session
 * ({@link RequestOptions#detailFlags()}), the fields the 422 gives beside the faults ({@link #detail()}). Its cause
 * is the exception uchar's write-back throws for these faults: the fault's own for one, uchar's
 * {@code EvaluationFaults} for two or more, the one uchar's scenario report names in its diagnose.
 */
public final class EvaluationFault extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The kind of a fault, by the type of uchar's exception. */
    public enum Kind { DIVISION_BY_ZERO, COMPUTE_FAULT, WRITERS_CONFLICT, INPUT_FORM_FAULT, OTHER }

    /** One fault: its kind and uchar's sentence. */
    public record Fault(Kind kind, String sentence) {}

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
    static EvaluationFault van(Fixpoint fixpoint, RuntimeException cause) {
        List<Fault> sink = new ArrayList<>();
        for (dev.uchar.regelspraak.transpiler.tms.Fault f : fixpoint.faults()) sink.add(new Fault(kind(f.fault()), f.message()));
        return new EvaluationFault(sink, cause, fixpoint, null);
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
            default -> Kind.OTHER;
        };
    }

    /** The faults, in the order uchar reports them. */
    public List<Fault> errors() {
        return errors;
    }

    /** What stands at the fixpoint the faults stand at, as uchar's entry reads it. */
    public Fixpoint fixpoint() {
        return fixpoint;
    }

    /** The fields the 422 gives beside the faults ({@code trace}, {@code dekking}, {@code fixpunt}), or null when the
     *  request asked none. */
    public ObjectNode detail() {
        return detail;
    }
}
