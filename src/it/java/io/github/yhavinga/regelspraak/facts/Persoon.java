package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the self-reference guard test. The subject's own objecttype also lends
 *  its name to a role of another type ({@link Organisatie}); a definite back-reference
 *  "de persoon" must read the subject's OWN attribute (§5.5.3), not navigate the role. */
public class Persoon {
    private BigDecimal leeftijd;
    private BigDecimal gekopieerd;
    public BigDecimal getLeeftijd() { return leeftijd; }
    public void setLeeftijd(BigDecimal v) { this.leeftijd = v; }
    public BigDecimal getGekopieerd() { return gekopieerd; }
    public void setGekopieerd(BigDecimal v) { this.gekopieerd = v; }
}
