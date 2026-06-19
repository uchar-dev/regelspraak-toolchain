package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;
import java.time.LocalDate;

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
