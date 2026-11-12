package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.EndState;

/**
 * A request whose session reaches a limit of uchar's Drools branch at its fixpoint: a value of the rules that this
 * branch cannot hold, such as a segment of a timeline of the unbounded form whose value does not terminate
 * (DROOLS-312, a {@code LIMIT} of uchar's register). It is no fault of the rules and no fault of the input: the value
 * is the document's, and the branch has no answer on it. uchar's write-back refuses such a session as a
 * {@link CaseRunner.Excluded} with its register code, before the faults that stand beside it
 * ({@code DroolsBackend.setDerivedValues}), and its replay reports the case as excluded; nothing is written back.
 * Both adapters answer it with a 422 ({@link AnswerBuilder#limit}) with the code and uchar's sentence.
 *
 * <p>When the request asked to see its session ({@link RequestOptions#detailFlags()}), it carries the fields the 422
 * gives beside the sentence ({@link #detail()}), as an {@link EvaluationFault} does: what the session holds at the
 * fixpoint is there, the limit among its faults.
 */
public final class Limit extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient EndState standing;
    private final transient ObjectNode detail;

    /** uchar's {@code refusal} at the fixpoint, with {@code standing} what the session holds there. */
    Limit(CaseRunner.Excluded refusal, EndState standing) {
        this(refusal.getMessage(), refusal.code().id(), refusal, standing, null);
    }

    private Limit(String sentence, String code, Throwable cause, EndState standing, ObjectNode detail) {
        super(sentence, cause);
        this.code = code;
        this.standing = standing;
        this.detail = detail;
    }

    /** The same limit with {@code detail}, the fields the request asked to see of its session. */
    Limit withDetail(ObjectNode detail) {
        return new Limit(getMessage(), code, getCause(), standing, detail);
    }

    /** uchar's register code of the limit ({@code DROOLS-312}). */
    public String code() {
        return code;
    }

    /** What the session holds at the fixpoint the limit was reached at, as uchar reads it
     *  ({@code DroolsBackend.standingOf}): no end state, a view of the session for diagnosis. */
    public EndState standing() {
        return standing;
    }

    /** The fields the 422 gives beside the sentence ({@code trace}, {@code coverage}, {@code fixpoint}), or null when
     *  the request asked none. */
    public ObjectNode detail() {
        return detail;
    }
}
