package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;

import java.util.List;

/** Thrown by {@link RegelSpraakEngine#compile} when a RegelSpraak source has resolver errors, carrying the
 *  diagnostics (severity / message / line) so the caller can surface them. */
public final class RegelSpraakCompileException extends RuntimeException {
    private final transient List<Diagnostic> diagnostics;

    public RegelSpraakCompileException(List<Diagnostic> diagnostics) {
        super("RegelSpraak compile-fouten: " + diagnostics);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() { return diagnostics; }
}
