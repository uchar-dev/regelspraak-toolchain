package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO: an order, whose value seeds the invoice an objectcreatie rule makes. */
public class Bestelling {
    private BigDecimal waarde;
    public BigDecimal getWaarde() { return waarde; }
    public void setWaarde(BigDecimal v) { this.waarde = v; }
}
