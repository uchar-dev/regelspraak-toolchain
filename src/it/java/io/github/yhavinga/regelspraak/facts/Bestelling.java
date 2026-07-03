package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO: an order, whose value seeds the invoice an objectcreatie rule makes. */
public class Bestelling {
    private Factuur factuur;                 // contract drift test: the 1-1 feittype implies this to-one reference
    public Factuur getFactuur() { return factuur; }
    public void setFactuur(Factuur v) { this.factuur = v; }
    private BigDecimal waarde;
    public BigDecimal getWaarde() { return waarde; }
    public void setWaarde(BigDecimal v) { this.waarde = v; }
}
