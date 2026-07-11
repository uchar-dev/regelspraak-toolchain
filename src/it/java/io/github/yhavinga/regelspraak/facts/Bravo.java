package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the §9.2 negation-stratification differential fixture (the fuzzer's minimised reproducer):
 *  a Numeriek attribute {@code bedrag} plus two kenmerken — {@code bevoorrecht} (producer P: waar indien bedrag>2)
 *  and {@code belast} (negation-consumer N: waar indien hij niet bevoorrecht is). With bedrag=14 the DRL back-end
 *  used to leave belast=true whenever N was declared before P (transient-window firing); exec always false. */
public class Bravo {
    private BigDecimal bedrag;
    private boolean bevoorrecht;
    private boolean belast;

    public BigDecimal getBedrag() { return bedrag; }
    public void setBedrag(BigDecimal v) { this.bedrag = v; }

    public boolean isBevoorrecht() { return bevoorrecht; }
    public void setBevoorrecht(boolean v) { this.bevoorrecht = v; }

    public boolean isBelast() { return belast; }
    public void setBelast(boolean v) { this.belast = v; }
}
