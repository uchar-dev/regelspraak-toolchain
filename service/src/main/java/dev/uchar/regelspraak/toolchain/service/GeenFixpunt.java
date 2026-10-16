package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A request whose session reached no fixpoint: uchar's {@code DroolsBackend.vuur} ended the run at its fire cap or at
 * the creation budget, and its message is uchar's sentence ({@code Vuring.geenFixpunt}). The rules give no answer on
 * this input and nothing stands to be read, so nothing is read or written back. The service does not judge which of
 * the two it was; uchar's sentence says so. Both adapters answer it with a 422 ({@link AntwoordBouwer#geenFixpunt}),
 * as an {@link Evaluatiefout}, so that it is told apart from an internal fault, which stays a 500.
 *
 * <p>When the request asked to see its session ({@link VerzoekOpties#detailvlaggen()}), it carries the fields the 422
 * gives beside the sentence ({@link #detail()}): the trace and the coverage of what fired up to the end of the run.
 */
public final class GeenFixpunt extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ObjectNode detail;

    GeenFixpunt(String zin) {
        this(zin, null);
    }

    private GeenFixpunt(String zin, ObjectNode detail) {
        super(zin);
        this.detail = detail;
    }

    /** The same run with {@code detail}, the fields the request asked to see of its session. */
    GeenFixpunt metDetail(ObjectNode detail) {
        return new GeenFixpunt(getMessage(), detail);
    }

    /** The fields the 422 gives beside the sentence ({@code trace}, {@code dekking}, {@code fixpuntFout}), or null when
     *  the request asked none. */
    public ObjectNode detail() {
        return detail;
    }
}
