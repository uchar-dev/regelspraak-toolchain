package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the cross-unit comparison test: duurmnd (mnd) vs grensjr (jr). */
public class Meting {
    private BigDecimal duurmnd;
    private BigDecimal grensjr;
    private boolean oud;
    private boolean bonus;
    private boolean bereikbaarPerTrein;
    public BigDecimal getDuurmnd() { return duurmnd; }
    public void setDuurmnd(BigDecimal v) { this.duurmnd = v; }
    public BigDecimal getGrensjr() { return grensjr; }
    public void setGrensjr(BigDecimal v) { this.grensjr = v; }
    public boolean isOud() { return oud; }
    public void setOud(boolean v) { this.oud = v; }
    public boolean isBonus() { return bonus; }
    public void setBonus(boolean v) { this.bonus = v; }
    public boolean isBereikbaarPerTrein() { return bereikbaarPerTrein; }
    public void setBereikbaarPerTrein(boolean v) { this.bereikbaarPerTrein = v; }
}
