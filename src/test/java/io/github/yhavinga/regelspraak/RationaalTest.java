package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.Rationaal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** §13.2.5: the shared numeric-literal -> {numerator, denominator} parser used by both the resolver
 *  (precision) and the transpiler (lowering). Pins each form and the -0_b/c sign subtlety. */
class RationaalTest {

    private static void assertRationaal(String bron, String teller, String noemer) {
        BigDecimal[] r = Rationaal.van(bron);
        assertNotNull(r, () -> "geen getal in: " + bron);
        assertEquals(0, new BigDecimal(teller).compareTo(r[0]), () -> "teller van " + bron + " = " + r[0]);
        assertEquals(0, new BigDecimal(noemer).compareTo(r[1]), () -> "noemer van " + bron + " = " + r[1]);
    }

    @Test void integer_decimaal_breuk_en_gemengd() {
        assertRationaal("5", "5", "1");                 // bare integer
        assertRationaal("-12", "-12", "1");             // negative integer
        assertRationaal("0,50", "0.50", "1");           // a,b decimal (comma)
        assertRationaal("3.25", "3.25", "1");           // a.b decimal (dot)
        assertRationaal("3/2", "3", "2");               // a/c fraction
        assertRationaal("1_1/2", "3", "2");             // mixed a_b/c = 1 + 1/2 = 3/2
    }

    @Test void de_min_nul_breuk_houdt_zijn_teken() {
        // -0_1/2 = -0.5: the whole part BigDecimal("-0") has signum 0, so the sign MUST come from the leading
        // '-' in the text, not heel.signum() (which would yield +0.5).
        assertRationaal("-0_1/2", "-1", "2");
    }

    @Test void geen_getal_is_null() {
        assertNull(Rationaal.van("geen cijfer hier"));
    }

    @Test void leidende_woorden_worden_overgeslagen() {
        // find() (not matches()) so a number embedded in a phrase is still read.
        assertRationaal("maal 4/2", "4", "2");
    }
}
