package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the §6.2-6.4 binary arithmetic leegwaarde rekentabellen: two numeric operands and a
 *  result slot per operator, so a rule firing "grondslag <op> aftrek" can be observed with a null operand. */
public class Rekensom {
    private BigDecimal grondslag;
    private BigDecimal aftrek;
    private BigDecimal verminderdResultaat;
    private BigDecimal minResultaat;
    private BigDecimal plusResultaat;
    private BigDecimal maalResultaat;
    public BigDecimal getGrondslag() { return grondslag; }
    public void setGrondslag(BigDecimal v) { this.grondslag = v; }
    public BigDecimal getAftrek() { return aftrek; }
    public void setAftrek(BigDecimal v) { this.aftrek = v; }
    public BigDecimal getVerminderdResultaat() { return verminderdResultaat; }
    public void setVerminderdResultaat(BigDecimal v) { this.verminderdResultaat = v; }
    public BigDecimal getMinResultaat() { return minResultaat; }
    public void setMinResultaat(BigDecimal v) { this.minResultaat = v; }
    public BigDecimal getPlusResultaat() { return plusResultaat; }
    public void setPlusResultaat(BigDecimal v) { this.plusResultaat = v; }
    public BigDecimal getMaalResultaat() { return maalResultaat; }
    public void setMaalResultaat(BigDecimal v) { this.maalResultaat = v; }
}
