package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** A fact POJO matching the transpiler's naming convention (Pascal-case class,
 *  getX/setX per attribute). Hand-written here to give the generated DRL a runtime
 *  fact model to compile and fire against. */
public class Vlucht {
    private BigDecimal totaalprijs;
    public BigDecimal getTotaalprijs() { return totaalprijs; }
    public void setTotaalprijs(BigDecimal v) { this.totaalprijs = v; }
}
