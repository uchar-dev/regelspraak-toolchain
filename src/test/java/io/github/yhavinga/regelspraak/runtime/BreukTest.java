package io.github.yhavinga.regelspraak.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit pins on {@link Breuk} — the exact-rational computation carrier for the typeringen Precisie clauses
 * (§389 gedeeld door, §486 percentage van, §845 tijdsevenredig deel). These cover normalization, the exact
 * arithmetic, the terminating/non-terminating decision, and the decimal collapse (exact vs DECIMAL64 / rounded).
 */
class BreukTest {

    private static Breuk b(long t, long n) { return Breuk.van(BigInteger.valueOf(t), BigInteger.valueOf(n)); }

    @Test void normaliseertTekenEnGcd() {
        assertEquals(BigInteger.ONE, b(2, 4).teller());
        assertEquals(BigInteger.TWO, b(2, 4).noemer());
        // sign moves to the numerator, denominator stays positive
        Breuk neg = b(1, -3);
        assertEquals(BigInteger.valueOf(-1), neg.teller());
        assertEquals(BigInteger.valueOf(3), neg.noemer());
        // -0/5 normalizes to 0/1
        assertEquals(BigInteger.ZERO, b(0, 5).teller());
        assertEquals(BigInteger.ONE, b(0, 5).noemer());
    }

    @Test void vanBigDecimalIsExact() {
        // 0.3 -> 3/10 exactly (unscaled/10^scale), NOT the double 0.29999…
        Breuk drie = Breuk.van(new BigDecimal("0.3"));
        assertEquals(BigInteger.valueOf(3), drie.teller());
        assertEquals(BigInteger.TEN, drie.noemer());
        // an integer BigDecimal
        assertEquals(b(7, 1), Breuk.van(new BigDecimal("7")));
        // negative-scale BigDecimal (1E+2 = 100)
        assertEquals(b(100, 1), Breuk.van(new BigDecimal("1E+2")));
    }

    @Test void vanObjectCoercion() {
        assertNull(Breuk.van((Object) null));
        Breuk id = b(1, 3);
        assertSame(id, Breuk.van((Object) id));                 // identity
        assertEquals(b(5, 2), Breuk.van(new BigDecimal("2.5")));
        assertEquals(b(4, 1), Breuk.van(BigInteger.valueOf(4)));
        assertEquals(b(6, 1), Breuk.van(Integer.valueOf(6)));   // other Number via toString
    }

    @Test void exacteRekenkunde() {
        assertEquals(b(5, 6), b(1, 2).optellen(b(1, 3)));       // 1/2 + 1/3 = 5/6
        assertEquals(b(1, 6), b(1, 2).aftrekken(b(1, 3)));      // 1/2 - 1/3 = 1/6
        assertEquals(b(1, 6), b(1, 2).vermenigvuldigen(b(1, 3)));// 1/2 · 1/3 = 1/6
        assertEquals(b(3, 2), b(1, 2).delen(b(1, 3)));          // (1/2)/(1/3) = 3/2
        // 9 · 1/3 = 3 exactly
        assertEquals(b(3, 1), b(9, 1).vermenigvuldigen(b(1, 3)));
    }

    @Test void delingDoorNulGooit() {
        assertThrows(ArithmeticException.class, () -> b(1, 2).delen(b(0, 1)));
        assertThrows(ArithmeticException.class, () -> Breuk.van(BigInteger.ONE, BigInteger.ZERO));
    }

    @Test void exacteVergelijking() {
        // 1/3 vs 2/6 are equal; 1/3 < 1/2; cross-multiplication is sign-safe
        assertEquals(0, b(1, 3).compareTo(b(2, 6)));
        assertTrue(b(1, 3).compareTo(b(1, 2)) < 0);
        assertTrue(b(-1, 3).compareTo(b(1, 3)) < 0);
        // 1/3 · 3 == 1 exactly (the comparison the DECIMAL64 engine got wrong)
        assertEquals(0, b(1, 3).vermenigvuldigen(b(3, 1)).compareTo(b(1, 1)));
    }

    @Test void terminerend() {
        assertTrue(b(1, 2).isTerminerend());     // 0.5
        assertTrue(b(3, 4).isTerminerend());     // 0.75
        assertTrue(b(1, 5).isTerminerend());     // 0.2
        assertTrue(b(7, 1).isTerminerend());     // integer
        assertTrue(b(1, 20).isTerminerend());    // 0.05 (2^2·5)
        assertFalse(b(1, 3).isTerminerend());    // 0.333…
        assertFalse(b(1, 6).isTerminerend());    // 0.1666…
        assertFalse(b(2, 7).isTerminerend());
    }

    @Test void naarDecimalExactWanneerTerminerend() {
        assertEquals(new BigDecimal("0.5"), b(1, 2).naarDecimal());
        // 9 · 1/3 = 3 exactly (the chain the DECIMAL64 engine got wrong as 2.9999999999999997)
        assertEquals(0, new BigDecimal("3").compareTo(b(9, 1).vermenigvuldigen(b(1, 3)).naarDecimal()));
    }

    @Test void naarDecimalNietTerminerendIsDecimal64() {
        // 1/3 does not terminate -> DECIMAL64 (16 sig digits), never a throw
        BigDecimal d = b(1, 3).naarDecimal();
        assertEquals(16, d.precision());        // DECIMAL64
        assertTrue(d.toPlainString().startsWith("0.3333333333333333"));
    }

    @Test void naarDecimalMetSchaalRondtExact() {
        // (1/3)% van 600 = 2 exactly; rounding the exact 1/3·6 = 2 to 0 dec is 2
        Breuk pct = b(1, 3).delen(b(100, 1)).vermenigvuldigen(b(600, 1));   // (1/3)/100 · 600 = 2
        assertEquals(new BigDecimal("2"), pct.naarDecimal(0, RoundingMode.HALF_UP));
        // 1/3 rounded to 4 dec HALF_UP = 0.3333
        assertEquals(new BigDecimal("0.3333"), b(1, 3).naarDecimal(4, RoundingMode.HALF_UP));
        // 2/3 rounded to 2 dec HALF_UP = 0.67
        assertEquals(new BigDecimal("0.67"), b(2, 3).naarDecimal(2, RoundingMode.HALF_UP));
    }

    @Test void absEnNegate() {
        assertEquals(b(1, 3), b(-1, 3).abs());
        assertEquals(b(1, 3), b(1, 3).abs());
        assertEquals(b(-1, 3), b(1, 3).negate());
        assertEquals(0, b(0, 1).signum());
        assertTrue(b(0, 5).isNul());
    }

    @Test void numberContract() {
        assertEquals(3, b(9, 3).intValue());   // 9/3 = 3
        assertEquals(3L, b(7, 2).longValue()); // 3.5 -> 3 (truncation via naarDecimal.longValue)
        assertEquals(0.5, b(1, 2).doubleValue(), 1e-12);
    }
}
