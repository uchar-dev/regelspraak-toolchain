package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the role-vs-objecttype name-collision test. The subject of the rule:
 *  it reaches a {@link Luchthaven} through the reference field "bestemming" — the role,
 *  NOT the same-named free objecttype {@link Bestemming} that does not join it. */
public class Reiziger {
    private BigDecimal afstandTotDoel;
    private Luchthaven bestemming;
    public BigDecimal getAfstandTotDoel() { return afstandTotDoel; }
    public void setAfstandTotDoel(BigDecimal v) { this.afstandTotDoel = v; }
    public Luchthaven getBestemming() { return bestemming; }
    public void setBestemming(Luchthaven v) { this.bestemming = v; }
}
