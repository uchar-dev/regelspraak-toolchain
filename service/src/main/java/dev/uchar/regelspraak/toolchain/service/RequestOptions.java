package dev.uchar.regelspraak.toolchain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * What a request asks beside its body, from the query of its URL: {@code explanation}, the explanation in words;
 * {@code vocabulaire}, the vocabulary the answer names the declarations in (null: the entry point's default from the
 * descriptor, else the canonical names); {@code inputVocabulary}, the vocabulary the request's names are read in
 * (null: every name is read liberally, canonical or a designation in any vocabulary). A vocabulary is one the model
 * declares, or {@code canoniek} for the canonical names.
 *
 * <p>The detail flags ask to see the request's session beside the answer, which a core gives only when it was started
 * with them ({@link CoreConfig#detail()}): {@code trace}, the firings per form with the ids of the request they bound;
 * {@code dekking}, per form of the entry point whether it fired; {@code fixpunt}, what stands at the fixpoint.
 */
public record RequestOptions(boolean explanation, String vocabulary, String inputVocabulary, boolean trace,
                            boolean coverage, boolean fixpoint) {

    /** A request that asks nothing beside its body. */
    public static final RequestOptions DEFAULT = new RequestOptions(false, null, null);

    /** A request that asks no detail of its session. */
    public RequestOptions(boolean explanation, String vocabulary, String inputVocabulary) {
        this(explanation, vocabulary, inputVocabulary, false, false, false);
    }

    /** The detail flags this request sets, by the name of their query parameter, in the order of the answer. */
    public List<String> detailFlags() {
        List<String> sink = new ArrayList<>();
        if (trace) sink.add("trace");
        if (coverage) sink.add("coverage");
        if (fixpoint) sink.add("fixpoint");
        return sink;
    }
}
