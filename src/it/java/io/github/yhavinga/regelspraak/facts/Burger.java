package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the by-name / wederkerig navigation test. Two distinct reference
 *  fields point at a {@link Trip} ("heenvlucht"/"terugvlucht"), so a pair-only join
 *  would be ambiguous and the source must name which one. "partner" is the single
 *  role of a wederkerig Partnerrelatie — a Burger related to another Burger — so it
 *  is a real navigation, not an own-attribute read. */
public class Burger {
    private BigDecimal heensom;
    private BigDecimal terugsom;
    private BigDecimal partnerleeftijd;
    private BigDecimal leeftijd;
    private Trip heenvlucht;
    private Trip terugvlucht;
    private Burger partner;

    public BigDecimal getHeensom() { return heensom; }
    public void setHeensom(BigDecimal v) { this.heensom = v; }
    public BigDecimal getTerugsom() { return terugsom; }
    public void setTerugsom(BigDecimal v) { this.terugsom = v; }
    public BigDecimal getPartnerleeftijd() { return partnerleeftijd; }
    public void setPartnerleeftijd(BigDecimal v) { this.partnerleeftijd = v; }
    public BigDecimal getLeeftijd() { return leeftijd; }
    public void setLeeftijd(BigDecimal v) { this.leeftijd = v; }
    public Trip getHeenvlucht() { return heenvlucht; }
    public void setHeenvlucht(Trip v) { this.heenvlucht = v; }
    public Trip getTerugvlucht() { return terugvlucht; }
    public void setTerugvlucht(Trip v) { this.terugvlucht = v; }
    public Burger getPartner() { return partner; }
    public void setPartner(Burger v) { this.partner = v; }
}
