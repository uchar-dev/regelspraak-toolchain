package io.github.yhavinga.regelspraak.model;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * A unit (§13.3.5): its abbreviation ("jr", "km", "mnd", "€"), the unit system that
 * declares it, and — when the system declares conversions (§13.3.5.2,
 * "het jaar jr = 12 mnd") — the system's canonical unit it reduces to plus the
 * factor that expresses one of this unit in that canonical unit.
 *
 * <p>A COMPOSITE (rate) unit (§13.3.3.2, "€/jr", "km/u") additionally carries a
 * denominator unit ({@code noemer}). The {@code afkorting}/{@code canoniek}/
 * {@code factorNaarCanoniek} describe the NUMERATOR; {@code noemer} is the (simple)
 * denominator unit, or {@code null} for a plain unit. Converting a rate restates the
 * numerator AND inverts the denominator: "120 €/jr" is "10 €/mnd" (÷12), so the rate
 * scales by the numerator factor times the inverse of the denominator factor.
 *
 * <p>Two units are inter-convertible when their numerators reduce to the same canonical
 * unit (or share an abbreviation/system) AND their denominators are likewise convertible
 * (or both absent). {@link #omrekenfactorNaar} gives the multiplier to restate a quantity
 * in this unit as a quantity in a convertible target unit, so arithmetic and comparison
 * across units (jr vs mnd, €/jr vs €/mnd) are scaled rather than computed on raw magnitudes.
 */
public record Eenheid(String afkorting, String stelsel, String canoniek, BigDecimal factorNaarCanoniek, Eenheid noemer) {

    /** A simple unit with no declared conversion: it is its own canonical (factor 1). */
    public Eenheid(String afkorting, String stelsel) { this(afkorting, stelsel, afkorting, BigDecimal.ONE, null); }

    /** A simple unit with a declared canonical + factor. */
    public Eenheid(String afkorting, String stelsel, String canoniek, BigDecimal factorNaarCanoniek) {
        this(afkorting, stelsel, canoniek, factorNaarCanoniek, null);
    }

    /** A composite (rate) unit: a numerator unit over a denominator unit ("€/jr"). */
    public static Eenheid samengesteld(Eenheid teller, Eenheid noemer) {
        return new Eenheid(teller.afkorting, teller.stelsel, teller.canoniek, teller.factorNaarCanoniek, noemer);
    }

    /** §4.4 the PRODUCT of two simple units ("€·m") — an opaque composite carrying NO canonical, so it is
     *  convertible only to an identical product (any assignment/comparison against a concrete unit then flags a
     *  §4 unit mismatch instead of silently passing as it did when the product degraded to Onbekend). Each
     *  operand's afkorting is FLATTENED into its atomic "·"-factors and the combined multiset is SORTED before
     *  re-joining, so every grouping and order of the same factors yields ONE canonical afkorting — making the
     *  product commutative AND associative (§4.4 Tabel 11): (€·m)·km == €·(m·km) == €·km·m. A self-product
     *  (€ maal €) keeps both factors (€·€, a squared unit). The model does not (yet) re-decompose a product, so
     *  cancelling a factor away (€·m gedeeld door m) is not recognised — it stays an opaque product or Onbekend. */
    public static Eenheid product(Eenheid a, Eenheid b) {
        java.util.List<String> factoren = new java.util.ArrayList<>();
        java.util.Collections.addAll(factoren, a.afkorting.split("·"));
        java.util.Collections.addAll(factoren, b.afkorting.split("·"));
        java.util.Collections.sort(factoren);
        return new Eenheid(String.join("·", factoren), a.stelsel, null, null, null);
    }

    /** Human-readable spelling, including the denominator for a composite ("€/jr"). */
    public String weergave() { return noemer == null ? afkorting : afkorting + "/" + noemer.afkorting; }

    /** The rate's NUMERATOR as a stand-alone unit (denominator stripped); this unit itself when plain. */
    public Eenheid teller() {
        return noemer == null ? this : new Eenheid(afkorting, stelsel, canoniek, factorNaarCanoniek);
    }

    /** Convertible iff numerator and denominator are each convertible (or both denominators
     *  absent). A composite and a plain unit are never convertible. */
    public boolean convertibleNaar(Eenheid other) {
        if (other == null) return false;
        if ((noemer == null) != (other.noemer == null)) return false;
        if (noemer != null && !noemer.convertibleNaar(other.noemer)) return false;
        return tellerConvertibel(other);
    }

    /** Numerator convertibility: the same unit (abbreviation), or two units that reduce to the SAME
     *  system canonical. Sharing only an eenheidstelsel is NOT enough — two independent base units in one
     *  system (appel vs peer) reduce to disjoint canonicals and are not inter-convertible, so §13.3.5's
     *  "equal or convertible" requirement is violated and comparing them on raw magnitudes is a
     *  silent-wrong (p44). A well-formed system reduces every unit to one canonical, so this stays true
     *  for the ordinary jr/mnd/dag case; an unresolved (null) canonical is treated as not convertible. */
    private boolean tellerConvertibel(Eenheid other) {
        if (afkorting.equals(other.afkorting)) return true;
        return canoniek != null && other.canoniek != null && canoniek.equals(other.canoniek);
    }

    /** Multiplier to restate a quantity in THIS unit as a quantity in {@code target}
     *  (1 when not convertible or factors are unknown — caller should gate on
     *  {@link #convertibleNaar}). For a plain unit jr→mnd is 12, mnd→jr is 1/12; for a
     *  rate the numerator factor is multiplied by the INVERSE denominator factor, so
     *  €/mnd→€/jr is 12 (the denominator jr is 12 of the mnd it replaces). */
    public BigDecimal omrekenfactorNaar(Eenheid target) {
        if (target == null) return BigDecimal.ONE;
        BigDecimal f = tellerFactorNaar(target);
        if (noemer != null && target.noemer != null)
            // denominator inverts: per-this.noemer -> per-target.noemer is target.noemer -> this.noemer
            f = f.multiply(target.noemer.tellerFactorNaar(noemer));
        return f;
    }

    /** The EXACT restatement factors {num, den} to restate a quantity in THIS unit as one in {@code target} —
     *  multiply by num then DIVIDE by den (one rounding, at the end), the lossless alternative to
     *  {@link #omrekenfactorNaar}'s pre-rounded single ratio (which truncates a non-terminating quotient like mnd→jr
     *  = 1/12 = 0.0833…). For a simple unit num/den are the two declared factorNaarCanoniek; for a RATE the inverted
     *  denominator contributes its factor too (€/jr→€/mnd: num=€·mnd factors, den=€·jr factors -> ÷12 exactly). Returns
     *  {@code null} when not exactly restatable here: not convertible, an unknown factor, or an opaque product unit
     *  (no canonical) — the caller then keeps the single-ratio fallback. */
    public BigDecimal[] exacteHerschaalFactoren(Eenheid target) {
        if (target == null || !convertibleNaar(target)
                || factorNaarCanoniek == null || target.factorNaarCanoniek == null) return null;
        BigDecimal num = factorNaarCanoniek, den = target.factorNaarCanoniek;
        if (noemer != null && target.noemer != null) {   // a rate: per-this.noemer -> per-target.noemer inverts
            if (noemer.factorNaarCanoniek == null || target.noemer.factorNaarCanoniek == null) return null;
            num = num.multiply(target.noemer.factorNaarCanoniek);
            den = den.multiply(noemer.factorNaarCanoniek);
        }
        return new BigDecimal[]{num, den};
    }

    /** Numerator-only conversion factor (the simple-unit rule). */
    private BigDecimal tellerFactorNaar(Eenheid target) {
        if (afkorting.equals(target.afkorting)) return BigDecimal.ONE;
        if (factorNaarCanoniek == null || target.factorNaarCanoniek == null
                || canoniek == null || !canoniek.equals(target.canoniek)) return BigDecimal.ONE;
        return factorNaarCanoniek.divide(target.factorNaarCanoniek, MathContext.DECIMAL64);
    }

    /** True when restating this unit in {@code target} is not the identity (factor ≠ 1) —
     *  composite-aware, so €/jr→€/mnd (factor 1/12) requires conversion even though the
     *  numerators share an abbreviation. */
    public boolean vereistOmrekening(Eenheid target) {
        return target != null && omrekenfactorNaar(target).compareTo(BigDecimal.ONE) != 0;
    }
}
