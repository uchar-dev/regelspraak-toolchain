package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for a to-ONE navigated threshold in an aggregation-in-condition test: a Vlucht relates to
 *  one Vliegtuig (role {@code vliegtuig}), so "de capaciteit van zijn vliegtuig" reads capaciteit off the
 *  navigated Vliegtuig — the null-safe navReader / rsRelGetal read (absent relation -> leeg). */
public class Vliegtuig {
    private Vlucht vlucht;                   // contract drift test: the 1-1 feittype implies this to-one reference
    public Vlucht getVlucht() { return vlucht; }
    public void setVlucht(Vlucht v) { this.vlucht = v; }
    private BigDecimal capaciteit;
    public BigDecimal getCapaciteit() { return capaciteit; }
    public void setCapaciteit(BigDecimal v) { this.capaciteit = v; }
}
