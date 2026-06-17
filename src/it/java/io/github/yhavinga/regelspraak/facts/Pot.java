package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO: a pot holding a total to be distributed in equal shares. */
public class Pot {
    private BigDecimal totaal;
    public BigDecimal getTotaal() { return totaal; }
    public void setTotaal(BigDecimal v) { this.totaal = v; }
}
