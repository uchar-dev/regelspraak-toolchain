package io.github.yhavinga.regelspraak.ir;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

/**
 * A structured source anchor for an IR node: the {@code file}/{@code line}/{@code column} the
 * node was built from, plus the whitespace-normalised source {@code snippet}. It replaces the
 * bare {@code String bron} that {@link Expr}, {@link Conditie} and the {@code Resolved*} forms
 * used to carry, giving diagnostics and (via the {@code // Bron:} statuutannotaties) uitleg a
 * coordinate back into the wetsartikel — without changing what the v0 wire serialises.
 *
 * <p><b>Wire discipline.</b> {@link #toString()} returns exactly the {@link #snippet()}, so the
 * emitter (which writes the snippet) stays byte-identical and every prose consumer that spliced
 * a {@code bron()} into a comment keeps its text. Only the added coordinates are new, and they
 * are never emitted to the v0 wire.
 *
 * @param file    the source file name (from the token's char stream; {@code "<unknown>"} when the
 *                input was resolved from a bare string, {@code null} for a synthetic node)
 * @param line    the 1-based source line of the node's start token (0 for a synthetic node)
 * @param column  the 0-based source column of the node's start token (0 for a synthetic node)
 * @param snippet the whitespace-normalised source slice — the exact string the bare bron carried
 */
public record Bron(String file, int line, int column, String snippet) {

    /** A synthetic node with no source position — a desugared / artificially-built node whose only
     *  faithful anchor is its snippet text (file null, line/column 0). */
    public static Bron synthetisch(String snippet) {
        return new Bron(null, 0, 0, snippet);
    }

    /** The anchor of {@code ctx}: file/line/column from its start token, with {@code snippet} the
     *  caller's whitespace-normalised source slice. The file name is the char stream's source name
     *  (set by {@code CharStreams.fromString(text, name)} at the front end; {@code "<unknown>"} for a
     *  nameless string input). A null ctx (or one with no start token) yields a synthetic anchor. */
    public static Bron van(ParserRuleContext ctx, String snippet) {
        if (ctx == null || ctx.getStart() == null) return synthetisch(snippet);
        Token t = ctx.getStart();
        String file = t.getInputStream() != null ? t.getInputStream().getSourceName() : null;
        return new Bron(file, t.getLine(), t.getCharPositionInLine(), snippet);
    }

    /** The snippet — so a {@code bron()} spliced into a string keeps its old text (wire-preserving). */
    @Override public String toString() { return snippet; }
}
