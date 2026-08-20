package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;
import java.util.List;

/** Fact POJO for the §8.1.6 ever-unique temporal union key — the Wrakvondst side of the union. */
public class Wrakvondst {
    private List<Segment> vondstnummer;
    private BigDecimal reekscode;
    private List<Segment> volgnummer;
    public List<Segment> getVondstnummer() { return vondstnummer; }
    public void setVondstnummer(List<Segment> v) { this.vondstnummer = v; }
    public BigDecimal getReekscode() { return reekscode; }
    public void setReekscode(BigDecimal v) { this.reekscode = v; }
    public List<Segment> getVolgnummer() { return volgnummer; }
    public void setVolgnummer(List<Segment> v) { this.volgnummer = v; }
}
