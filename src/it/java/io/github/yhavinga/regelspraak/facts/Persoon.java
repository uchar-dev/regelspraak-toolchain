package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the self-reference guard test. The subject's own objecttype also lends
 *  its name to a role of another type ({@link Organisatie}); a definite back-reference
 *  "de persoon" must read the subject's OWN attribute (§5.5.3), not navigate the role. */
public class Persoon {
    private BigDecimal leeftijd;
    private BigDecimal gekopieerd;
    private BigDecimal uitkomst;       // §8.1.8 navigated-kenmerk test target
    private boolean gepensioneerd;     // §8.1.8 navigated kenmerk
    private boolean rechtOpDuurzaamheidskorting; // §8.1.8.2 bezittelijk "het recht op duurzaamheidskorting"
    private boolean reiziger;          // §8.1.8.3 zelfstandig kenmerk, for the "is geen reiziger" negation
    private String roepnaam;           // §9.5 navigated Tekst equality (null-safe navigation test)
    private Persoon partner;           // to-one self-relation the navigated kenmerk reads through
    private BigDecimal inkomen;        // §6.5 navigated deling-door-leeg companion: dividend van zijn partner
    private BigDecimal aftrek;         // §6.5 navigated deling-door-leeg companion: divisor van zijn partner
    private BigDecimal ratio;          // §6.5 navigated division target
    private BigDecimal basis;          // §6.6 navigated wortel-van-negatief companion: radicand van zijn partner
    public BigDecimal getInkomen() { return inkomen; }
    public void setInkomen(BigDecimal v) { this.inkomen = v; }
    public BigDecimal getAftrek() { return aftrek; }
    public void setAftrek(BigDecimal v) { this.aftrek = v; }
    public BigDecimal getRatio() { return ratio; }
    public void setRatio(BigDecimal v) { this.ratio = v; }
    public BigDecimal getBasis() { return basis; }
    public void setBasis(BigDecimal v) { this.basis = v; }
    public BigDecimal getLeeftijd() { return leeftijd; }
    public void setLeeftijd(BigDecimal v) { this.leeftijd = v; }
    public BigDecimal getGekopieerd() { return gekopieerd; }
    public void setGekopieerd(BigDecimal v) { this.gekopieerd = v; }
    public BigDecimal getUitkomst() { return uitkomst; }
    public void setUitkomst(BigDecimal v) { this.uitkomst = v; }
    public boolean isGepensioneerd() { return gepensioneerd; }
    public void setGepensioneerd(boolean v) { this.gepensioneerd = v; }
    public boolean isRechtOpDuurzaamheidskorting() { return rechtOpDuurzaamheidskorting; }
    public void setRechtOpDuurzaamheidskorting(boolean v) { this.rechtOpDuurzaamheidskorting = v; }
    public boolean isReiziger() { return reiziger; }
    public void setReiziger(boolean v) { this.reiziger = v; }
    public String getRoepnaam() { return roepnaam; }
    public void setRoepnaam(String v) { this.roepnaam = v; }
    public Persoon getPartner() { return partner; }
    public void setPartner(Persoon v) { this.partner = v; }
}
