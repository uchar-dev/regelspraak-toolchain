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
    private Persoon partner;           // to-one self-relation the navigated kenmerk reads through
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
    public Persoon getPartner() { return partner; }
    public void setPartner(Persoon v) { this.partner = v; }
}
