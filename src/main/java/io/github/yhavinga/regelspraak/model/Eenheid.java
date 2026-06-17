package io.github.yhavinga.regelspraak.model;

/**
 * A unit (§13.3.5): its abbreviation as written ("jr", "km", "mnd", "€") and the
 * unit system that declares it. Two units are inter-convertible — the relation
 * the typeringen matrix demands for arithmetic and ordered comparison — when they
 * belong to the same system (§13.3.5.1: a system defines the conversion factors
 * among its own units).
 */
public record Eenheid(String afkorting, String stelsel) {
    /** Convertible iff declared in the same unit system (or identical). */
    public boolean convertibleNaar(Eenheid other) {
        if (other == null) return false;
        if (afkorting.equals(other.afkorting)) return true;
        return stelsel != null && stelsel.equals(other.stelsel);
    }
}
