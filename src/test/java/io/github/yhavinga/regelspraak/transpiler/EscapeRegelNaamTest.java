package io.github.yhavinga.regelspraak.transpiler;

import io.github.yhavinga.regelspraak.transpiler.kern.Namen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rule name is free text (§13.2.8) emitted verbatim into a DRL string literal in three places — the
 * {@code rule "…"} header, the Inconsistentie literal, and the idempotency guard's {@code regel == "…"}.
 * {@link io.github.yhavinga.regelspraak.transpiler.kern.Namen#escapeRegelNaam} must neutralise every character that would break that literal so
 * the whole Drools package stays well-formed. A raw backslash or newline cannot reach a rule name through
 * the parser (the lexer emits no such token), so this guards the transpiler directly against a synthetic
 * or future IR that carries one — and pins the §13.2.8 escaping contract regardless of source reachability.
 */
class EscapeRegelNaamTest {

    @Test void double_quote_wordt_geneutraliseerd() {
        // A quote would close the DRL string literal early.
        assertEquals("zeg 'hoi'", Namen.escapeRegelNaam("zeg \"hoi\""));
    }

    @Test void backslash_wordt_geneutraliseerd() {
        // A backslash escapes the next character; a trailing one would escape the closing quote.
        assertEquals("pad/naar/regel", Namen.escapeRegelNaam("pad\\naar\\regel"));
        assertFalse(Namen.escapeRegelNaam("eindigt op \\").contains("\\"),
                "een resterende backslash zou het sluitende aanhalingsteken escapen");
    }

    @Test void newline_en_carriage_return_worden_spaties() {
        // A newline in the name would split the `rule "…"` header across lines.
        assertEquals("regel een twee", Namen.escapeRegelNaam("regel een\ntwee"));
        assertEquals("regel een twee", Namen.escapeRegelNaam("regel een\r\ntwee").replace("  ", " "));
        String escaped = Namen.escapeRegelNaam("a\r\nb");
        assertFalse(escaped.contains("\n") || escaped.contains("\r"), "geen rauwe regeleindes meer: " + escaped);
    }

    @Test void onschuldige_naam_blijft_intact() {
        // The common case must be untouched — the corpus rule names carry none of these characters.
        assertEquals("verhoog prijs", Namen.escapeRegelNaam("verhoog prijs"));
        assertEquals("verdeling treinmiles in gelijke delen",
                Namen.escapeRegelNaam("verdeling treinmiles in gelijke delen"));
    }

    @Test void backslash_eerst_zodat_substitutie_er_geen_terugbrengt() {
        // All four break-characters at once: the result must contain none of them.
        String escaped = Namen.escapeRegelNaam("\\ \" \r \n");
        assertFalse(escaped.contains("\\"), escaped);
        assertFalse(escaped.contains("\""), escaped);
        assertFalse(escaped.contains("\r"), escaped);
        assertFalse(escaped.contains("\n"), escaped);
    }
}
