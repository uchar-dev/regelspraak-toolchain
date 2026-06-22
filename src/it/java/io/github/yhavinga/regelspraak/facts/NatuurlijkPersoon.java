package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;
import java.util.Map;

/** Fact POJO for the §8.1.6 uniciteit example AND the §5.8.5 dimensieaggregatie example. The
 *  burgerservicenummer drives the uniqueness rule (unique across instances). The
 *  betaaldeBelastingInJaar is a §13.3.7 gedimensioneerd attribuut — one value per jaardimensie
 *  label — so it is modelled as a Map keyed by the label string; a "som … vanaf … t/m …" folds the
 *  attribute over the selected labels into betaaldeBelastingOverDeAfgelopenVierJaar. */
public class NatuurlijkPersoon {
    private BigDecimal burgerservicenummer;
    public BigDecimal getBurgerservicenummer() { return burgerservicenummer; }
    public void setBurgerservicenummer(BigDecimal v) { this.burgerservicenummer = v; }

    private boolean geldig;   // §8.1.3 the kenmerk an "voldoet aan de elfproef" rule sets
    public boolean isGeldig() { return geldig; }
    public void setGeldig(boolean v) { this.geldig = v; }

    /** §13.3.7 gedimensioneerd met jaardimensie: label ("vier jaar geleden", …) -> value. */
    private Map<String, BigDecimal> betaaldeBelastingInJaar;
    public Map<String, BigDecimal> getBetaaldeBelastingInJaar() { return betaaldeBelastingInJaar; }
    public void setBetaaldeBelastingInJaar(Map<String, BigDecimal> v) { this.betaaldeBelastingInJaar = v; }

    private BigDecimal betaaldeBelastingOverDeAfgelopenVierJaar;
    public BigDecimal getBetaaldeBelastingOverDeAfgelopenVierJaar() { return betaaldeBelastingOverDeAfgelopenVierJaar; }
    public void setBetaaldeBelastingOverDeAfgelopenVierJaar(BigDecimal v) { this.betaaldeBelastingOverDeAfgelopenVierJaar = v; }
}
