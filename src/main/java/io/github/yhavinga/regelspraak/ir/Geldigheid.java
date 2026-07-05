package io.github.yhavinga.regelspraak.ir;

import java.time.LocalDate;

/**
 * §13.4.2.5 rule-version validity: the period in which a rule version applies. Versions
 * of one rule are selected at runtime against the Rekendatum (§4.2) — the version whose
 * window contains the Rekendatum is the one that fires.
 *
 * {@code vanaf} is the inclusive lower bound (null = open, from the beginning) and
 * {@code tot} the inclusive upper bound (null = open, "t/m" being inclusive). "Geldig
 * altijd" is both-null. A year shorthand widens to the whole year — "vanaf 2024" is
 * 2024-01-01, "t/m 2024" is 2024-12-31 — so the bounds are always concrete dates.
 */
public record Geldigheid(LocalDate vanaf, LocalDate tot) {
    public static final Geldigheid ALTIJD = new Geldigheid(null, null);
    public boolean altijd() { return vanaf == null && tot == null; }

    /**
     * Whether {@code d} falls within this window — both bounds INCLUSIVE, a null bound OPEN (matching every
     * date on that side). {@link #ALTIJD} contains every date. Used by parameter-vintage selection to pick
     * the interval that applies on a peildatum (see {@link io.github.yhavinga.regelspraak.model.Parameterset}).
     */
    public boolean bevat(LocalDate d) {
        if (d == null) return false;
        if (vanaf != null && d.isBefore(vanaf)) return false;
        if (tot != null && d.isAfter(tot)) return false;
        return true;
    }
}
