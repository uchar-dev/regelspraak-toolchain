package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO with two attributes in DIFFERENT but convertible units: duur (mnd) and
 *  looptijd (jr). The generated rule must scale jr to mnd, not add raw magnitudes. */
public class Periode {
    private BigDecimal duur;      // months
    private BigDecimal looptijd;  // years
    public BigDecimal getDuur() { return duur; }
    public void setDuur(BigDecimal v) { this.duur = v; }
    public BigDecimal getLooptijd() { return looptijd; }
    public void setLooptijd(BigDecimal v) { this.looptijd = v; }
}
