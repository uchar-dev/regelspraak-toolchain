package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO: a pot holding a total to be distributed in equal shares. {@code restant} receives the
 *  §9.7.5 undistributed remainder when the distribution floors its shares. */
public class Pot {
    private BigDecimal totaal;
    private BigDecimal restant;
    public BigDecimal getTotaal() { return totaal; }
    public void setTotaal(BigDecimal v) { this.totaal = v; }
    public BigDecimal getRestant() { return restant; }
    public void setRestant(BigDecimal v) { this.restant = v; }
}
