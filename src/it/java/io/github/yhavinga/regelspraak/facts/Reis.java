package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the exact-vs-synthesized-plural navigation test. Two feittypen reach
 *  a {@link Trip} from a Reis through differently-named reference fields — "heenvlucht"
 *  and "heenvluchten" — where the latter is also the +en plural a best-effort
 *  pluraliser would synthesize for the former. The generated DRL must call the getter
 *  for the role the source names exactly. */
public class Reis {
    private BigDecimal prijs;
    private Trip heenvlucht;
    private Trip heenvluchten;
    public BigDecimal getPrijs() { return prijs; }
    public void setPrijs(BigDecimal v) { this.prijs = v; }
    public Trip getHeenvlucht() { return heenvlucht; }
    public void setHeenvlucht(Trip v) { this.heenvlucht = v; }
    public Trip getHeenvluchten() { return heenvluchten; }
    public void setHeenvluchten(Trip v) { this.heenvluchten = v; }
}
