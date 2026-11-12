package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A request whose session reached no fixpoint: uchar's {@code DroolsBackend.fire} ended the run at its fire cap or at
 * the creation budget, and its message is uchar's sentence ({@code Firing.noFixpoint}). The rules give no answer on
 * this input and nothing stands to be read, so nothing is read or written back. The service does not judge which of
 * the two it was; uchar's sentence says so, and uchar names the fault kind of the creation budget, EF-015 with its
 * parameters ({@code Firing.code}, {@code Firing.params}), and none at the cap, which is a safeguard of its branch. Both adapters answer it with a 422 ({@link AnswerBuilder#noFixpoint}),
 * as an {@link EvaluationFault}, so that it is told apart from an internal fault, which stays a 500.
 *
 * <p>When the request asked to see its session ({@link RequestOptions#detailFlags()}), it carries the fields the 422
 * gives beside the sentence ({@link #detail()}): the trace and the coverage of what fired up to the end of the run.
 */
public final class NoFixpoint extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ObjectNode detail;
    private final String code;
    private final transient java.util.Map<String, String> params;

    /** The end of the run {@code firing}, which reached no fixpoint. */
    NoFixpoint(dev.uchar.regelspraak.cases.DroolsBackend.Firing firing) {
        this(firing.noFixpoint(), firing.code(), firing.params(), null);
    }

    private NoFixpoint(String sentence, String code, java.util.Map<String, String> params, ObjectNode detail) {
        super(sentence);
        this.code = code;
        this.params = params;
        this.detail = detail;
    }

    /** The same run with {@code detail}, the fields the request asked to see of its session. */
    NoFixpoint withDetail(ObjectNode detail) {
        return new NoFixpoint(getMessage(), code, params, detail);
    }

    /** The code of the fault kind uchar names for the end of the run, EF-015 at the creation budget; null at the cap. */
    public String code() {
        return code;
    }

    /** The parameters of that kind, empty where it has none. */
    public java.util.Map<String, String> params() {
        return params;
    }

    /** The fields the 422 gives beside the sentence ({@code trace}, {@code dekking}, {@code fixpointError}), or null when
     *  the request asked none. */
    public ObjectNode detail() {
        return detail;
    }
}
