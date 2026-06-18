package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the self-reference guard test: the type a role named "persoon" reaches.
 *  Present so the generated package's import compiles; the self-reference must NOT bind
 *  a navigation to it. */
public class Organisatie {
    private BigDecimal omvang;
    public BigDecimal getOmvang() { return omvang; }
    public void setOmvang(BigDecimal v) { this.omvang = v; }
}
