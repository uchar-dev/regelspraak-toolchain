package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Fact POJO for the rule-validity test: its prijs is set differently by two rule
 *  versions, each valid in a different period (selected by the Rekendatum). The
 *  basis/netto fields serve the begrenzing+afronding precedence test. The three
 *  koppelteken DatumTijd fields (verwachte/daadwerkelijke datum-tijd van vertrek ->
 *  the hyphenated datum-tijd voor het berekenen) pin the shared-naming fix: a name
 *  with a "-" must resolve the SAME getter kern.Namen.ident generates for the bean
 *  (getDatumTijdVoorHetBerekenen), on BOTH back-ends — mirrors toka-22/25. */
public class Heffing {
    private BigDecimal prijs;
    private BigDecimal basis;
    private BigDecimal netto;
    private BigDecimal wortelr;   // §6.6 wortel-of-leeg propagation through the mandatory afronding
    private BigDecimal machtr;    // §6.7 macht-of-leeg propagation
    private LocalDateTime verwachteDatumTijdVanVertrek;
    private LocalDateTime daadwerkelijkeDatumTijdVanVertrek;
    private LocalDateTime datumTijdVoorHetBerekenen;
    public BigDecimal getPrijs() { return prijs; }
    public void setPrijs(BigDecimal v) { this.prijs = v; }
    public BigDecimal getBasis() { return basis; }
    public void setBasis(BigDecimal v) { this.basis = v; }
    public BigDecimal getNetto() { return netto; }
    public void setNetto(BigDecimal v) { this.netto = v; }
    public BigDecimal getWortelr() { return wortelr; }
    public void setWortelr(BigDecimal v) { this.wortelr = v; }
    public BigDecimal getMachtr() { return machtr; }
    public void setMachtr(BigDecimal v) { this.machtr = v; }
    public LocalDateTime getVerwachteDatumTijdVanVertrek() { return verwachteDatumTijdVanVertrek; }
    public void setVerwachteDatumTijdVanVertrek(LocalDateTime v) { this.verwachteDatumTijdVanVertrek = v; }
    public LocalDateTime getDaadwerkelijkeDatumTijdVanVertrek() { return daadwerkelijkeDatumTijdVanVertrek; }
    public void setDaadwerkelijkeDatumTijdVanVertrek(LocalDateTime v) { this.daadwerkelijkeDatumTijdVanVertrek = v; }
    public LocalDateTime getDatumTijdVoorHetBerekenen() { return datumTijdVoorHetBerekenen; }
    public void setDatumTijdVoorHetBerekenen(LocalDateTime v) { this.datumTijdVoorHetBerekenen = v; }
}
