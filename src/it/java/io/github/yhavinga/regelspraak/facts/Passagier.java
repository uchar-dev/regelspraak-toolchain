package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for a passenger. The feittype role "reis" (the flight it belongs to)
 *  is the reference field the generated accumulate join uses (reis == $subject). */
public class Passagier {
    private BigDecimal prijs;
    private Vlucht reis;
    private boolean kind;
    private boolean student;
    private boolean minderjarig;
    private BigDecimal stoelnummer;   // §8.1.6 parent-scoped uniqueness key (unique per reis)
    private Vlucht bonusvlucht;       // §13.4.7 feitcreatie target: the relation a feitcreatie creates (set to reis)
    public BigDecimal getPrijs() { return prijs; }
    public void setPrijs(BigDecimal v) { this.prijs = v; }
    public Vlucht getReis() { return reis; }
    public void setReis(Vlucht r) { this.reis = r; }
    public boolean isKind() { return kind; }
    public void setKind(boolean v) { this.kind = v; }
    public boolean isStudent() { return student; }
    public void setStudent(boolean v) { this.student = v; }
    public boolean isMinderjarig() { return minderjarig; }
    public void setMinderjarig(boolean v) { this.minderjarig = v; }
    public BigDecimal getStoelnummer() { return stoelnummer; }
    public void setStoelnummer(BigDecimal v) { this.stoelnummer = v; }
    public Vlucht getBonusvlucht() { return bonusvlucht; }
    public void setBonusvlucht(Vlucht v) { this.bonusvlucht = v; }
}
