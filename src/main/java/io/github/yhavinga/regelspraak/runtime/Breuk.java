package io.github.yhavinga.regelspraak.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * An EXACT rational number — {@code teller/noemer} over {@link BigInteger}, kept normalized (positive
 * denominator, gcd-reduced). It is the computation-time carrier for the typeringen <b>Precisie</b> clauses
 * (§389 gedeeld door, §486 percentage van, §845 tijdsevenredig deel), which state that a division / percentage
 * result is "altijd een geheel getal of een breuk … dit zal dan altijd het exacte getal zijn": the engine
 * computes in {@link BigDecimal} (DECIMAL64) which loses a non-terminating quotient (1/3 → 0.3333…16 digits),
 * so a chain like {@code 9 maal 1/3} diverges to 2.9999999999999997 instead of the exact 3.
 *
 * <p>The design keeps <b>fact fields at rest as {@link BigDecimal}</b> (values are decimal-typed per the
 * declared datatypes; the resolver's §6.1.2 write-precision guard already rejects a write of a value with more
 * decimals than the doel unless it was explicitly rounded — so a non-terminating exact value only ever reaches
 * an afronding or terminates). Exactness therefore lives in the <b>computation</b>: the {@code rs*} arithmetic
 * helpers accept a {@link Breuk} wherever they accept a number, return a {@link Breuk} when the exact result
 * does not terminate (division/percentage) or when any input was a {@link Breuk} and the op is {@code +,-,×,÷},
 * and collapse to {@link BigDecimal} at an {@code afronding} ({@link #naarDecimal(int, java.math.RoundingMode)})
 * or at a naturally-terminating boundary ({@link #naarDecimal()}).
 *
 * <p>Extends {@link Number} so it flows through the existing {@code Object}-typed value plumbing (the exec
 * back-end's {@code BiFunction<Object,…,Object>} values, the DRL mvel {@code Object}); the {@code Number}
 * methods evaluate the quotient at double/long precision (used only where a numeric primitive is genuinely
 * wanted, never on the exact path).
 */
public final class Breuk extends Number implements Comparable<Breuk> {
    private static final long serialVersionUID = 1L;

    private final BigInteger teller;   // signed
    private final BigInteger noemer;   // strictly positive

    private Breuk(BigInteger teller, BigInteger noemer) {
        this.teller = teller;
        this.noemer = noemer;
    }

    /** Normalize (sign to the numerator, gcd-reduce) and construct. A zero denominator is never produced here —
     *  the callers guard division-by-zero — but is rejected defensively. */
    public static Breuk van(BigInteger teller, BigInteger noemer) {
        if (noemer.signum() == 0) throw new ArithmeticException("Breuk met noemer 0");
        if (noemer.signum() < 0) { teller = teller.negate(); noemer = noemer.negate(); }
        BigInteger g = teller.gcd(noemer);
        if (g.signum() != 0 && !g.equals(BigInteger.ONE)) { teller = teller.divide(g); noemer = noemer.divide(g); }
        return new Breuk(teller, noemer);
    }

    /**
     * Coerce any number-like value to a {@link Breuk}: a {@link Breuk} is returned as-is (identity), a
     * {@link BigDecimal} becomes its EXACT rational ({@code unscaledValue / 10^scale}, no precision loss), and
     * any other {@link Number} goes through its {@link BigDecimal} form. {@code null} is passed through as
     * {@code null} (a leegwaarde stays leeg — the helpers apply their own leeg tables). A non-number is a bug.
     */
    public static Breuk van(Object o) {
        if (o == null) return null;
        if (o instanceof Breuk b) return b;
        if (o instanceof BigDecimal bd) return vanDecimal(bd);
        if (o instanceof BigInteger bi) return van(bi, BigInteger.ONE);
        if (o instanceof Number n) return vanDecimal(new BigDecimal(n.toString()));
        throw new IllegalArgumentException("kan geen Breuk maken van " + o.getClass().getName());
    }

    private static Breuk vanDecimal(BigDecimal bd) {
        int s = bd.scale();
        BigInteger unscaled = bd.unscaledValue();
        if (s == 0) return van(unscaled, BigInteger.ONE);
        if (s > 0) return van(unscaled, BigInteger.TEN.pow(s));
        // negative scale (e.g. 1E+2): value = unscaled * 10^(-scale)
        return van(unscaled.multiply(BigInteger.TEN.pow(-s)), BigInteger.ONE);
    }

    public BigInteger teller() { return teller; }
    public BigInteger noemer() { return noemer; }

    // ---- exact arithmetic (a/b op c/d) ----
    public Breuk optellen(Breuk a) {
        return van(teller.multiply(a.noemer).add(a.teller.multiply(noemer)), noemer.multiply(a.noemer));
    }
    public Breuk aftrekken(Breuk a) {
        return van(teller.multiply(a.noemer).subtract(a.teller.multiply(noemer)), noemer.multiply(a.noemer));
    }
    public Breuk vermenigvuldigen(Breuk a) {
        return van(teller.multiply(a.teller), noemer.multiply(a.noemer));
    }
    public Breuk delen(Breuk a) {
        if (a.teller.signum() == 0) throw new ArithmeticException("Breuk deling door 0");
        return van(teller.multiply(a.noemer), noemer.multiply(a.teller));
    }

    /** Exact sign-aware comparison by cross-multiplication (both denominators are positive, so no sign flip). */
    @Override public int compareTo(Breuk a) {
        return teller.multiply(a.noemer).compareTo(a.teller.multiply(noemer));
    }
    public int vergelijk(Breuk a) { return compareTo(a); }

    /** The exact-decimal test: this fraction terminates iff its reduced denominator's only prime factors are
     *  2 and 5 (a base-10 terminating decimal). */
    public boolean isTerminerend() {
        BigInteger n = noemer;
        BigInteger twee = BigInteger.TWO, vijf = BigInteger.valueOf(5);
        while (n.mod(twee).signum() == 0) n = n.divide(twee);
        while (n.mod(vijf).signum() == 0) n = n.divide(vijf);
        return n.equals(BigInteger.ONE);
    }

    /** The EXACT decimal when the fraction terminates, else the DECIMAL64 quotient — the collapse used at a
     *  naturally-terminating boundary or a defensive write. Never throws on a non-terminating value. */
    public BigDecimal naarDecimal() {
        BigDecimal t = new BigDecimal(teller), n = new BigDecimal(noemer);
        if (isTerminerend()) return t.divide(n);           // exact
        return t.divide(n, MathContext.DECIMAL64);          // defensive: DECIMAL64 (impossible-by-resolver-guard)
    }

    /** The §6.1.2 collapse point: round the EXACT rational at {@code scale} decimals with {@code mode} — an
     *  {@code afronding} rounds the true value, never a pre-truncated DECIMAL64 approximation. */
    public BigDecimal naarDecimal(int scale, java.math.RoundingMode mode) {
        return new BigDecimal(teller).divide(new BigDecimal(noemer), scale, mode);
    }

    public boolean isNul() { return teller.signum() == 0; }
    public int signum() { return teller.signum(); }
    public Breuk abs() { return teller.signum() < 0 ? new Breuk(teller.negate(), noemer) : this; }
    public Breuk negate() { return new Breuk(teller.negate(), noemer); }

    // ---- Number contract (quotient at primitive precision; never used on the exact path) ----
    @Override public int intValue()       { return naarDecimal().intValue(); }
    @Override public long longValue()     { return naarDecimal().longValue(); }
    @Override public float floatValue()   { return (float) doubleValue(); }
    @Override public double doubleValue() { return new BigDecimal(teller).divide(new BigDecimal(noemer), MathContext.DECIMAL64).doubleValue(); }

    @Override public boolean equals(Object o) {
        return o instanceof Breuk b && teller.equals(b.teller) && noemer.equals(b.noemer);
    }
    @Override public int hashCode() { return teller.hashCode() * 31 + noemer.hashCode(); }
    @Override public String toString() { return teller + "/" + noemer; }
}
