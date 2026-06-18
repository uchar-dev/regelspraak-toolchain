package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the by-name navigation test: the flight a Burger reaches as either
 *  its "heenvlucht" or its "terugvlucht" (two feittypen over the same Burger↔Trip pair). */
public class Trip {
    private BigDecimal tarief;
    public BigDecimal getTarief() { return tarief; }
    public void setTarief(BigDecimal v) { this.tarief = v; }
}
