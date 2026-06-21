package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Fact POJO for the §13.4.17 scalar built-in functions: the absolute value, a date/datetime's
 *  year/month/hour components, and a date/datetime built from numeric parts. */
public class Klok {
    private BigDecimal saldo;
    private BigDecimal absval;
    private LocalDate reisdatum;
    private BigDecimal jaartal;
    private BigDecimal maandnr;
    private BigDecimal bouwjaar;
    private LocalDate gemaakt;
    private LocalDateTime moment;
    private BigDecimal uurnr;
    private LocalDateTime stip;
    public BigDecimal getSaldo() { return saldo; }
    public void setSaldo(BigDecimal v) { this.saldo = v; }
    public BigDecimal getAbsval() { return absval; }
    public void setAbsval(BigDecimal v) { this.absval = v; }
    public LocalDate getReisdatum() { return reisdatum; }
    public void setReisdatum(LocalDate v) { this.reisdatum = v; }
    public BigDecimal getJaartal() { return jaartal; }
    public void setJaartal(BigDecimal v) { this.jaartal = v; }
    public BigDecimal getMaandnr() { return maandnr; }
    public void setMaandnr(BigDecimal v) { this.maandnr = v; }
    public BigDecimal getBouwjaar() { return bouwjaar; }
    public void setBouwjaar(BigDecimal v) { this.bouwjaar = v; }
    public LocalDate getGemaakt() { return gemaakt; }
    public void setGemaakt(LocalDate v) { this.gemaakt = v; }
    public LocalDateTime getMoment() { return moment; }
    public void setMoment(LocalDateTime v) { this.moment = v; }
    public BigDecimal getUurnr() { return uurnr; }
    public void setUurnr(BigDecimal v) { this.uurnr = v; }
    public LocalDateTime getStip() { return stip; }
    public void setStip(LocalDateTime v) { this.stip = v; }
}
