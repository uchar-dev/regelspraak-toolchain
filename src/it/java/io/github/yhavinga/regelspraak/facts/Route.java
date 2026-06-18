package io.github.yhavinga.regelspraak.facts;

/** Fact POJO for the consistency-rule test: a route whose departure and destination
 *  must differ (a round trip is inconsistent). */
public class Route {
    private String vertrek;
    private String bestemming;
    public String getVertrek() { return vertrek; }
    public void setVertrek(String v) { this.vertrek = v; }
    public String getBestemming() { return bestemming; }
    public void setBestemming(String v) { this.bestemming = v; }
}
