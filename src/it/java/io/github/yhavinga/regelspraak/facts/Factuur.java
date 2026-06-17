package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO created by an objectcreatie rule: an invoice, with a back-reference to
 *  its order (the feittype role "bestelling" the generated rule sets). */
public class Factuur {
    private BigDecimal bedrag;
    private Bestelling bestelling;
    public BigDecimal getBedrag() { return bedrag; }
    public void setBedrag(BigDecimal v) { this.bedrag = v; }
    public Bestelling getBestelling() { return bestelling; }
    public void setBestelling(Bestelling b) { this.bestelling = b; }
}
