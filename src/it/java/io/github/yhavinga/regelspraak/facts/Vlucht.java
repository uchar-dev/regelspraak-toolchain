package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** A fact POJO matching the transpiler's naming convention (Pascal-case class,
 *  getX/setX per attribute). Hand-written here to give the generated DRL a runtime
 *  fact model to compile and fire against. */
public class Vlucht {
    private BigDecimal totaalprijs;
    private BigDecimal somMet;     // sommatie with "of 0 als die er niet zijn" fallback
    private BigDecimal somZonder;  // sommatie without a fallback (empty -> leegwaarde)
    public BigDecimal getTotaalprijs() { return totaalprijs; }
    public void setTotaalprijs(BigDecimal v) { this.totaalprijs = v; }
    public BigDecimal getSomMet() { return somMet; }
    public void setSomMet(BigDecimal v) { this.somMet = v; }
    public BigDecimal getSomZonder() { return somZonder; }
    public void setSomZonder(BigDecimal v) { this.somZonder = v; }
}
