package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A request whose session reached no fixpoint: uchar's {@code DroolsBackend.fire} ended the run at its fire cap or at
 * the creation budget, and its message is uchar's sentence ({@code Firing.noFixpoint}). The rules give no answer on
 * this input and nothing stands to be read, so nothing is read or written back. The service does not judge which of
 * the two it was; uchar's sentence says so. Both adapters answer it with a 422 ({@link AnswerBuilder#noFixpoint}),
 * as an {@link EvaluationFault}, so that it is told apart from an internal fault, which stays a 500.
 *
 * <p>When the request asked to see its session ({@link RequestOptions#detailFlags()}), it carries the fields the 422
 * gives beside the sentence ({@link #detail()}): the trace and the coverage of what fired up to the end of the run.
 */
public final class NoFixpoint extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ObjectNode detail;

    NoFixpoint(String sentence) {
        this(sentence, null);
    }

    private NoFixpoint(String sentence, ObjectNode detail) {
        super(sentence);
        this.detail = detail;
    }

    /** The same run with {@code detail}, the fields the request asked to see of its session. */
    NoFixpoint withDetail(ObjectNode detail) {
        return new NoFixpoint(getMessage(), detail);
    }

    /** The fields the 422 gives beside the sentence ({@code trace}, {@code dekking}, {@code fixpointError}), or null when
     *  the request asked none. */
    public ObjectNode detail() {
        return detail;
    }
}
