package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO: a participant who receives a share of a pot's total — an equal share, or a
 *  proportional one weighted by {@code gewicht} (the §13.4.10 "naar rato" weight). The
 *  feittype role "pot" is the reference the generated distribution joins on. */
public class Deelnemer {
    private BigDecimal aandeel;
    private BigDecimal gewicht;
    private BigDecimal maximum;   // §9.7.3 maximale aanspraak: caps this recipient's share
    private Pot pot;
    public BigDecimal getAandeel() { return aandeel; }
    public void setAandeel(BigDecimal v) { this.aandeel = v; }
    public BigDecimal getGewicht() { return gewicht; }
    public void setGewicht(BigDecimal v) { this.gewicht = v; }
    public BigDecimal getMaximum() { return maximum; }
    public void setMaximum(BigDecimal v) { this.maximum = v; }
    public Pot getPot() { return pot; }
    public void setPot(Pot p) { this.pot = p; }
}
