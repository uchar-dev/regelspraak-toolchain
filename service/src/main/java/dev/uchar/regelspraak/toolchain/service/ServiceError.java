package dev.uchar.regelspraak.toolchain.service;

import java.util.List;

/**
 * The exception carrying a collected list of {@link Violation}s. Every fail-fast boundary in the core
 * (descriptor validation, marker fail-fast, request mapping) collects ALL its violations first and then
 * throws exactly one of these — never one-error-at-a-time — so a caller (a test, or the fase-3 HTTP layer)
 * sees the complete diagnostic set in a single round trip. The message concatenates the violations, each
 * naming its offending value/field, so a plain {@code getMessage()} is already actionable.
 */
public final class ServiceError extends RuntimeException {

    private final transient List<Violation> violations;

    public ServiceError(String context, List<Violation> violations) {
        super(context + " (" + violations.size() + " schending(en)):\n  "
                + String.join("\n  ", violations.stream().map(Violation::toString).toList()));
        this.violations = List.copyOf(violations);
    }

    /** The full, ordered list of violations that caused this failure. */
    public List<Violation> violations() { return violations; }
}
