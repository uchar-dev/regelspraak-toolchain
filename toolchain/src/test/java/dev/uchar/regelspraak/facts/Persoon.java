package dev.uchar.regelspraak.facts;

import java.math.BigDecimal;

/** A minimal domain bean for the engine end-to-end test — the kind of fact a consumer supplies (Pascal-case
 *  class, getX/setX per attribute, isX/setX per kenmerk) in package {@code dev.uchar.regelspraak.facts}, which the
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

    // vintage-selected parameter target (PeildatumSessieTest): grens is set to the resolved parameter value,
    // prijs is set to Rekenjaar (the rekendatum global's year) — the one-date-authority pair.
    private BigDecimal grens;
    public BigDecimal getGrens() { return grens; }
    public void setGrens(BigDecimal v) { this.grens = v; }

    private BigDecimal prijs;
    public BigDecimal getPrijs() { return prijs; }
    public void setPrijs(BigDecimal v) { this.prijs = v; }
}
