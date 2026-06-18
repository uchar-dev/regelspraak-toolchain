package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the role-vs-objecttype name-collision tests. The subject of the rules:
 *  it reaches a {@link Luchthaven} through the reference field "bestemming" — the role,
 *  NOT the same-named free objecttype {@link Bestemming}. The extra "plek" reference
 *  exercises the case where the name-twin objecttype is itself reachable through a
 *  different, unwritten role: the navigation must still bind the WRITTEN "bestemming". */
public class Reiziger {
    private BigDecimal afstandTotDoel;
    private BigDecimal doelrang;
    private Luchthaven bestemming;
    private Bestemming plek;
    public BigDecimal getAfstandTotDoel() { return afstandTotDoel; }
    public void setAfstandTotDoel(BigDecimal v) { this.afstandTotDoel = v; }
    public BigDecimal getDoelrang() { return doelrang; }
    public void setDoelrang(BigDecimal v) { this.doelrang = v; }
    public Luchthaven getBestemming() { return bestemming; }
    public void setBestemming(Luchthaven v) { this.bestemming = v; }
    public Bestemming getPlek() { return plek; }
    public void setPlek(Bestemming v) { this.plek = v; }
}
