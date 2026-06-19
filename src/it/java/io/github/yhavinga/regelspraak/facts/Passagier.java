package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for a passenger. The feittype role "reis" (the flight it belongs to)
 *  is the reference field the generated accumulate join uses (reis == $subject). */
public class Passagier {
    private BigDecimal prijs;
    private Vlucht reis;
    private boolean kind;
    public BigDecimal getPrijs() { return prijs; }
    public void setPrijs(BigDecimal v) { this.prijs = v; }
    public Vlucht getReis() { return reis; }
    public void setReis(Vlucht r) { this.reis = r; }
    public boolean isKind() { return kind; }
    public void setKind(boolean v) { this.kind = v; }
}
