package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** A minimal domain bean for the engine end-to-end test — the kind of fact a consumer supplies (Pascal-case
 *  class, getX/setX per attribute, isX/setX per kenmerk) in package {@code io.github.yhavinga.regelspraak.facts}, which the
 *  executable Model references by name. */
public class Persoon {
    private BigDecimal leeftijd;
    private boolean minderjarig;

    public BigDecimal getLeeftijd() { return leeftijd; }
    public void setLeeftijd(BigDecimal v) { this.leeftijd = v; }
    public boolean isMinderjarig() { return minderjarig; }
    public void setMinderjarig(boolean v) { this.minderjarig = v; }

    // second numeric attribute for the kringverwijzing (fire-cap) test
    private BigDecimal uitkomst;
    public BigDecimal getUitkomst() { return uitkomst; }
    public void setUitkomst(BigDecimal v) { this.uitkomst = v; }
}
