package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A fact POJO matching the transpiler's naming convention (Pascal-case class,
 *  getX/setX per attribute). Hand-written here to give the generated DRL a runtime
 *  fact model to compile and fire against. */
public class Vlucht {
    private BigDecimal totaalprijs;
    private BigDecimal somMet;     // sommatie with "of 0 als die er niet zijn" fallback
    private BigDecimal somZonder;  // sommatie without a fallback (empty -> leegwaarde)
    private LocalDate verwachtVertrek;       // §4.14 "eerste van" operands (date concatenation)
    private LocalDate daadwerkelijkVertrek;
    private LocalDate vroegsteVertrek;       // the "eerste van" result
    private BigDecimal luchthaven;           // §8.1.6 concatenatie uniqueness key (luchthaven, vertrektijd)
    private BigDecimal vertrektijd;
    private boolean internationaal;          // §13.4.7 feitcreatie anker-kenmerk voorwaarde
    private LocalDate vluchtdatum;           // §13.4.17 condition-side builtin (toka-27 Paaskorting)
    private boolean reisMetPaaskorting;      // the kenmerk that rule sets
    private boolean inHetHoogseizoen;        // toka-26: numeric == over a builtin (maand uit) in a quantifier
    private boolean vol;                     // §8.1.7 far-side rolcheck head ("een Vlucht is vol indien hij een passagier heeft")
    private BigDecimal uitstelduur;          // §6/§13.4.16 date-arithmetic duration (eenheid mnd)
    public BigDecimal getUitstelduur() { return uitstelduur; }
    public void setUitstelduur(BigDecimal v) { this.uitstelduur = v; }
    private LocalDateTime vertrekmoment;     // §6/§13.4.16 datum-tijd + minuten -> datum-tijd
    private LocalDateTime aankomstmoment;
    private BigDecimal reisduur;             // duration in minuut (a time unit -> needs a datum-tijd operand)
    public LocalDateTime getVertrekmoment() { return vertrekmoment; }
    public void setVertrekmoment(LocalDateTime v) { this.vertrekmoment = v; }
    public LocalDateTime getAankomstmoment() { return aankomstmoment; }
    public void setAankomstmoment(LocalDateTime v) { this.aankomstmoment = v; }
    public BigDecimal getReisduur() { return reisduur; }
    public void setReisduur(BigDecimal v) { this.reisduur = v; }
    private boolean laat;                    // §6/§13.4.16 condition-side date arithmetic (kenmerk doel)
    public boolean isLaat() { return laat; }
    public void setLaat(boolean v) { this.laat = v; }
    public boolean isInternationaal() { return internationaal; }
    public void setInternationaal(boolean v) { this.internationaal = v; }
    public boolean isVol() { return vol; }
    public void setVol(boolean v) { this.vol = v; }
    public LocalDate getVluchtdatum() { return vluchtdatum; }
    public void setVluchtdatum(LocalDate v) { this.vluchtdatum = v; }
    public boolean isReisMetPaaskorting() { return reisMetPaaskorting; }
    public void setReisMetPaaskorting(boolean v) { this.reisMetPaaskorting = v; }
    public boolean isInHetHoogseizoen() { return inHetHoogseizoen; }
    public void setInHetHoogseizoen(boolean v) { this.inHetHoogseizoen = v; }
    public BigDecimal getLuchthaven() { return luchthaven; }
    public void setLuchthaven(BigDecimal v) { this.luchthaven = v; }
    public BigDecimal getVertrektijd() { return vertrektijd; }
    public void setVertrektijd(BigDecimal v) { this.vertrektijd = v; }
    public BigDecimal getTotaalprijs() { return totaalprijs; }
    public void setTotaalprijs(BigDecimal v) { this.totaalprijs = v; }
    public BigDecimal getSomMet() { return somMet; }
    public void setSomMet(BigDecimal v) { this.somMet = v; }
    public BigDecimal getSomZonder() { return somZonder; }
    public void setSomZonder(BigDecimal v) { this.somZonder = v; }
    public LocalDate getVerwachtVertrek() { return verwachtVertrek; }
    public void setVerwachtVertrek(LocalDate v) { this.verwachtVertrek = v; }
    public LocalDate getDaadwerkelijkVertrek() { return daadwerkelijkVertrek; }
    public void setDaadwerkelijkVertrek(LocalDate v) { this.daadwerkelijkVertrek = v; }
    public LocalDate getVroegsteVertrek() { return vroegsteVertrek; }
    public void setVroegsteVertrek(LocalDate v) { this.vroegsteVertrek = v; }
}
