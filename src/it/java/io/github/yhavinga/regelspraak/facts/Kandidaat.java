package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the threshold-quantifier test: chosen iff at least N of three numeric
 *  criteria hold. */
public class Kandidaat {
    private BigDecimal a;
    private BigDecimal b;
    private BigDecimal c;
    private boolean gekozen;
    public BigDecimal getA() { return a; }
    public void setA(BigDecimal v) { this.a = v; }
    public BigDecimal getB() { return b; }
    public void setB(BigDecimal v) { this.b = v; }
    public BigDecimal getC() { return c; }
    public void setC(BigDecimal v) { this.c = v; }
    public boolean isGekozen() { return gekozen; }
    public void setGekozen(boolean v) { this.gekozen = v; }
}
