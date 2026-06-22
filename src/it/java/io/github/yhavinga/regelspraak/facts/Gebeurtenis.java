package io.github.yhavinga.regelspraak.facts;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Fact POJO for the §13.2.19/.21 dedato-literal test: a {@code tijdstip} typed Datum-tijd (LocalDateTime,
 *  millisecond precision) and a {@code geldigheid} typed Datum in dagen (LocalDate). A timed dedato literal
 *  must lower to a LocalDateTime that type-matches {@code setTijdstip}; a bare date stays a LocalDate. */
public class Gebeurtenis {
    private LocalDateTime tijdstip;
    private LocalDate geldigheid;
    public LocalDateTime getTijdstip() { return tijdstip; }
    public void setTijdstip(LocalDateTime v) { this.tijdstip = v; }
    public LocalDate getGeldigheid() { return geldigheid; }
    public void setGeldigheid(LocalDate v) { this.geldigheid = v; }
}
