package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the consistency-rule empty-operand test: a saldo that must stay below
 *  a limiet. A null saldo (missing value) must itself count as a violation. */
public class Konto {
    private BigDecimal saldo;
    private BigDecimal limiet;
    public BigDecimal getSaldo() { return saldo; }
    public void setSaldo(BigDecimal v) { this.saldo = v; }
    public BigDecimal getLimiet() { return limiet; }
    public void setLimiet(BigDecimal v) { this.limiet = v; }
}
