package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the role-vs-objecttype name-collision test: the type a role named
 *  "bestemming" reaches. Its {@code afstand} is what the navigation must read. */
public class Luchthaven {
    private BigDecimal afstand;
    public BigDecimal getAfstand() { return afstand; }
    public void setAfstand(BigDecimal v) { this.afstand = v; }
}
