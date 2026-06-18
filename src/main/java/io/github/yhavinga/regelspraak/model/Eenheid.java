package io.github.yhavinga.regelspraak.model;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * A unit (§13.3.5): its abbreviation ("jr", "km", "mnd", "€"), the unit system that
 * declares it, and — when the system declares conversions (§13.3.5.2,
 * "het jaar jr = 12 mnd") — the system's canonical unit it reduces to plus the
 * factor that expresses one of this unit in that canonical unit.
 *
 * Two units are inter-convertible when they reduce to the same canonical unit (or
 * share an abbreviation, or — lacking any declared conversion — belong to the same
 * system). {@link #omrekenfactorNaar} gives the multiplier to restate a quantity in
 * this unit as a quantity in a convertible target unit, so arithmetic and comparison
 * across units (jr vs mnd) are scaled rather than computed on raw magnitudes.
 */
public record Eenheid(String afkorting, String stelsel, String canoniek, BigDecimal factorNaarCanoniek) {

    /** A unit with no declared conversion: it is its own canonical (factor 1). */
    public Eenheid(String afkorting, String stelsel) { this(afkorting, stelsel, afkorting, BigDecimal.ONE); }

    /** Convertible iff same abbreviation, same canonical unit, or same system. */
    public boolean convertibleNaar(Eenheid other) {
        if (other == null) return false;
        if (afkorting.equals(other.afkorting)) return true;
        if (canoniek != null && other.canoniek != null && canoniek.equals(other.canoniek)) return true;
        return stelsel != null && stelsel.equals(other.stelsel);
    }

    /** Multiplier to restate a quantity in THIS unit as a quantity in {@code target}
     *  (1 when not convertible or factors are unknown — caller should gate on
     *  {@link #convertibleNaar}). E.g. jr→mnd is 12, mnd→jr is 1/12. */
    public BigDecimal omrekenfactorNaar(Eenheid target) {
        if (target == null || afkorting.equals(target.afkorting)) return BigDecimal.ONE;
        if (factorNaarCanoniek == null || target.factorNaarCanoniek == null
                || canoniek == null || !canoniek.equals(target.canoniek)) return BigDecimal.ONE;
        return factorNaarCanoniek.divide(target.factorNaarCanoniek, MathContext.DECIMAL64);
    }

    /** True when restating this unit in {@code target} is not the identity. */
    public boolean vereistOmrekening(Eenheid target) {
        return target != null && !afkorting.equals(target.afkorting)
                && omrekenfactorNaar(target).compareTo(BigDecimal.ONE) != 0;
    }
}
