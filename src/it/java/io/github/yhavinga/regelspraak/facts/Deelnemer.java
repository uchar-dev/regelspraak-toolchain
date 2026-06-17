package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO: a participant who receives an equal share of a pot's total. The
 *  feittype role "pot" is the reference the generated distribution joins on. */
public class Deelnemer {
    private BigDecimal aandeel;
    private Pot pot;
    public BigDecimal getAandeel() { return aandeel; }
    public void setAandeel(BigDecimal v) { this.aandeel = v; }
    public Pot getPot() { return pot; }
    public void setPot(Pot p) { this.pot = p; }
}
