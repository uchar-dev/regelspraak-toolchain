package dev.uchar.regelspraak.toolchain.service;

import java.util.List;

/**
 * The exception carrying a collected list of {@link Schending}s. Every fail-fast boundary in the core
 * (descriptor validation, marker fail-fast, request mapping) collects ALL its violations first and then
 * throws exactly one of these — never one-error-at-a-time — so a caller (a test, or the fase-3 HTTP layer)
 * sees the complete diagnostic set in a single round trip. The message concatenates the violations, each
 * naming its offending value/field, so a plain {@code getMessage()} is already actionable.
 */
public final class DienstFout extends RuntimeException {

    private final transient List<Schending> schendingen;

    public DienstFout(String context, List<Schending> schendingen) {
        super(context + " (" + schendingen.size() + " schending(en)):\n  "
                + String.join("\n  ", schendingen.stream().map(Schending::toString).toList()));
        this.schendingen = List.copyOf(schendingen);
    }

    /** The full, ordered list of violations that caused this failure. */
    public List<Schending> schendingen() { return schendingen; }
}
