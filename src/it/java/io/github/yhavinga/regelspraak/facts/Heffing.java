package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the rule-validity test: its prijs is set differently by two rule
 *  versions, each valid in a different period (selected by the Rekendatum). The
 *  basis/netto fields serve the begrenzing+afronding precedence test. */
public class Heffing {
    private BigDecimal prijs;
    private BigDecimal basis;
    private BigDecimal netto;
    public BigDecimal getPrijs() { return prijs; }
    public void setPrijs(BigDecimal v) { this.prijs = v; }
    public BigDecimal getBasis() { return basis; }
    public void setBasis(BigDecimal v) { this.basis = v; }
    public BigDecimal getNetto() { return netto; }
    public void setNetto(BigDecimal v) { this.netto = v; }
}
