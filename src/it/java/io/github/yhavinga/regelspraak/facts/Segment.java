package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;
import java.time.LocalDate;

/** §5.1/§7.1 one tijdvak of a temporal attribute's Tijdreeks: the value {@code waarde} holds over the
 *  half-open period [{@code van}, {@code tot}). A temporal attribute is an ordered {@code List<Segment>}
 *  on the fact bean; the generated {@code rsTijdTotaal} helper folds it by weighting each segment's value
 *  by the number of whole granularity periods it spans. This is the externally-provided runtime contract
 *  for a piecewise-constant value — the IR-shape foundation for the Phase-7 temporal value model. */
public class Segment {
    private LocalDate van;
    private LocalDate tot;
    private BigDecimal waarde;

    public Segment() {}
    public Segment(LocalDate van, LocalDate tot, BigDecimal waarde) {
        this.van = van; this.tot = tot; this.waarde = waarde;
    }

    public LocalDate getVan() { return van; }
    public void setVan(LocalDate v) { this.van = v; }
    public LocalDate getTot() { return tot; }
    public void setTot(LocalDate v) { this.tot = v; }
    public BigDecimal getWaarde() { return waarde; }
    public void setWaarde(BigDecimal v) { this.waarde = v; }
}
