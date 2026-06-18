package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the rule-validity test: its prijs is set differently by two rule
 *  versions, each valid in a different period (selected by the Rekendatum). */
public class Heffing {
    private BigDecimal prijs;
    public BigDecimal getPrijs() { return prijs; }
    public void setPrijs(BigDecimal v) { this.prijs = v; }
}
