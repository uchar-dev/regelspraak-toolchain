package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for §4 COMPOSITE (rate) cross-unit restatement: a per-year rate (EUR/jr), a per-month rate (EUR/mnd),
 *  a per-year bound (EUR/jr), and an "hoog" kenmerk for the constraint side. Exercises the exact rate restatement
 *  (numerator × inverted-denominator integer factors, not the pre-rounded 1/12 ratio). */
public class Tarief {
    private BigDecimal jaartarief;    // EUR/jr
    private BigDecimal maandtarief;   // EUR/mnd
    private BigDecimal grens;         // EUR/jr
    private boolean hoog;
    public BigDecimal getJaartarief() { return jaartarief; }
    public void setJaartarief(BigDecimal v) { this.jaartarief = v; }
    public BigDecimal getMaandtarief() { return maandtarief; }
    public void setMaandtarief(BigDecimal v) { this.maandtarief = v; }
    public BigDecimal getGrens() { return grens; }
    public void setGrens(BigDecimal v) { this.grens = v; }
    public boolean isHoog() { return hoog; }
    public void setHoog(boolean v) { this.hoog = v; }
}
