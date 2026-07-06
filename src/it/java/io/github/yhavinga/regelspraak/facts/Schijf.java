package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** §4/§8.1.1 fact POJO for the exec-model VW-multiglobal lift (toka-45 miniature). {@code afstand} is a km-typed
 *  attribute; {@code belasting} is the EUR doel a condition-gated gelijkstelling writes. The rule's guard is an
 *  arithmetic expression reading TWO distinct Parameter-globals as a composite (Bedrag×km) product compared >= 0 —
 *  the resolver types that LHS Onbekend (a §4 rate/composite unit), which the exec voorwaarde branch now admits. */
public class Schijf {
    private BigDecimal afstand;     // eenheid km
    private BigDecimal belasting;   // eenheid EUR (the doel)
    public BigDecimal getAfstand() { return afstand; }
    public void setAfstand(BigDecimal v) { this.afstand = v; }
    public BigDecimal getBelasting() { return belasting; }
    public void setBelasting(BigDecimal v) { this.belasting = v; }
}
