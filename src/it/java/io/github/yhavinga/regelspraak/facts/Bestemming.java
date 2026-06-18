package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the role-vs-objecttype name-collision tests: a free objecttype that
 *  shares the name "bestemming" with a role. Its {@code rang} exists ONLY here (the role
 *  target {@link Luchthaven} has no rang), so a navigation of "de rang van de bestemming"
 *  must stay an honest marker rather than emit a getter the role target cannot answer. Its
 *  {@code afstand} is the wrong value a different-role join would read. */
public class Bestemming {
    private String naam;
    private BigDecimal rang;
    private BigDecimal afstand;
    public String getNaam() { return naam; }
    public void setNaam(String v) { this.naam = v; }
    public BigDecimal getRang() { return rang; }
    public void setRang(BigDecimal v) { this.rang = v; }
    public BigDecimal getAfstand() { return afstand; }
    public void setAfstand(BigDecimal v) { this.afstand = v; }
}
