package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;
import java.util.List;

/** Fact POJO for the §8.1.6 ever-unique temporal union key (uchar RS ch. 5.9): the vondstnummer is a
 *  yearly timeline, yet reads as an identity — every value it EVER carries is claimed across the
 *  Strandvondst ∪ Wrakvondst union. */
public class Strandvondst {
    private List<Segment> vondstnummer;   // "voor elk jaar" -> a Tijdreeks
    private BigDecimal reekscode;         // scalar component of the compound key
    private List<Segment> volgnummer;     // temporal component of the compound key
    public List<Segment> getVondstnummer() { return vondstnummer; }
    public void setVondstnummer(List<Segment> v) { this.vondstnummer = v; }
    public BigDecimal getReekscode() { return reekscode; }
    public void setReekscode(BigDecimal v) { this.reekscode = v; }
    public List<Segment> getVolgnummer() { return volgnummer; }
    public void setVolgnummer(List<Segment> v) { this.volgnummer = v; }
}
