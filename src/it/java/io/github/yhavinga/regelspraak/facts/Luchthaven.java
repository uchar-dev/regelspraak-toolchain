package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the role-vs-objecttype name-collision test: the type a role named
 *  "bestemming" reaches. Its {@code afstand} is what the navigation must read. */
public class Luchthaven {
    private BigDecimal afstand;
    private Reiziger reiziger;              // back-reference for the to-many count join (reiziger == $subject)
    private Reiziger vertrekkendeReiziger;  // distinct subject-side roles for the >1-feittype count join
    private Reiziger aankomendeReiziger;
    public BigDecimal getAfstand() { return afstand; }
    public void setAfstand(BigDecimal v) { this.afstand = v; }
    public Reiziger getReiziger() { return reiziger; }
    public void setReiziger(Reiziger v) { this.reiziger = v; }
    public Reiziger getVertrekkendeReiziger() { return vertrekkendeReiziger; }
    public void setVertrekkendeReiziger(Reiziger v) { this.vertrekkendeReiziger = v; }
    public Reiziger getAankomendeReiziger() { return aankomendeReiziger; }
    public void setAankomendeReiziger(Reiziger v) { this.aankomendeReiziger = v; }
}
