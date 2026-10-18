package dev.uchar.regelspraak.toolchain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * What a request asks beside its body, from the query of its URL: {@code uitleg}, the explanation in words;
 * {@code vocabulaire}, the vocabulary the answer names the declarations in (null: the entry point's default from the
 * descriptor, else the canonical names); {@code invoervocabulaire}, the vocabulary the request's names are read in
 * (null: every name is read liberally, canonical or a designation in any vocabulary). A vocabulary is one the model
 * declares, or {@code canoniek} for the canonical names.
 *
 * <p>The detail flags ask to see the request's session beside the answer, which a core gives only when it was started
 * with them ({@link KernConfig#detail()}): {@code trace}, the firings per form with the ids of the request they bound;
 * {@code dekking}, per form of the entry point whether it fired; {@code fixpunt}, what stands at the fixpoint.
 */
public record VerzoekOpties(boolean uitleg, String vocabulaire, String invoervocabulaire, boolean trace,
                            boolean dekking, boolean fixpunt) {

    /** A request that asks nothing beside its body. */
    public static final VerzoekOpties STANDAARD = new VerzoekOpties(false, null, null);

    /** A request that asks no detail of its session. */
    public VerzoekOpties(boolean uitleg, String vocabulaire, String invoervocabulaire) {
        this(uitleg, vocabulaire, invoervocabulaire, false, false, false);
    }

    /** The detail flags this request sets, by the name of their query parameter, in the order of the answer. */
    public List<String> detailvlaggen() {
        List<String> uit = new ArrayList<>();
        if (trace) uit.add("trace");
        if (dekking) uit.add("dekking");
        if (fixpunt) uit.add("fixpunt");
        return uit;
    }
}
