package dev.uchar.regelspraak.toolchain.service;

/**
 * What a request asks beside its body, from the query of its URL: {@code uitleg}, the explanation in words;
 * {@code vocabulaire}, the vocabulary the answer names the declarations in (null: the entry point's default from the
 * descriptor, else the canonical names); {@code invoervocabulaire}, the vocabulary the request's names are read in
 * (null: every name is read liberally, canonical or a designation in any vocabulary). A vocabulary is one the model
 * declares, or {@code canoniek} for the canonical names.
 */
public record VerzoekOpties(boolean uitleg, String vocabulaire, String invoervocabulaire) {

    /** A request that asks nothing beside its body. */
    public static final VerzoekOpties STANDAARD = new VerzoekOpties(false, null, null);
}
