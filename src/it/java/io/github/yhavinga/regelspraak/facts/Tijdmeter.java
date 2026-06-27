package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the §4 cross-unit +/- arithmetic differential fixtures: a value in jaar (extra), a value in
 *  maanden (looptijd), and a result/compare attribute in jaar (totaal), plus an "oud" kenmerk for the constraint
 *  side. Exercises the EXACT canonical-unit restatement (×÷ factorNaarCanoniek, no lossy 1/12 ratio). */
public class Tijdmeter {
    private BigDecimal extra;      // jr
    private BigDecimal looptijd;   // mnd
    private BigDecimal totaal;     // jr
    private boolean oud;
    public BigDecimal getExtra() { return extra; }
    public void setExtra(BigDecimal v) { this.extra = v; }
    public BigDecimal getLooptijd() { return looptijd; }
    public void setLooptijd(BigDecimal v) { this.looptijd = v; }
    public BigDecimal getTotaal() { return totaal; }
    public void setTotaal(BigDecimal v) { this.totaal = v; }
    public boolean isOud() { return oud; }
    public void setOud(boolean v) { this.oud = v; }
}
