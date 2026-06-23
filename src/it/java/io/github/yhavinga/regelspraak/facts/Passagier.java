package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Fact POJO for a passenger. The feittype role "reis" (the flight it belongs to)
 *  is the reference field the generated accumulate join uses (reis == $subject). */
public class Passagier {
    private BigDecimal prijs;
    private Vlucht reis;
    private boolean kind;
    private boolean student;
    private boolean minderjarig;
    private BigDecimal stoelnummer;   // §8.1.6 parent-scoped uniqueness key (unique per reis)
    private Vlucht bonusvlucht;       // §13.4.7 feitcreatie target: the relation a feitcreatie creates (set to reis)
    private Contingent bonuscontingent; // §9.4 multi-hop feitcreatie target: set to the anker Contingent
    private BigDecimal basistarief;   // §6.1.4 begrenzing-in-condition: the clamped value
    private BigDecimal bodem;         // §6.1.4 begrenzing minimum
    private BigDecimal plafond;       // §6.1.4 begrenzing maximum
    private boolean duur;             // the kenmerk the begrenzing-condition rule sets
    private List<Segment> belastingverminderingPerMaand; // §7.1 a temporal attribute ("voor elke maand") -> a Tijdreeks
    private BigDecimal totaleBelastingvermindering;      // §7.1 "het totaal van" the timeline above
    private List<Segment> rechthebbend;                  // §7.1 a temporal kenmerk's TRUE-periods (the gedurende knip)
    private List<Segment> rechtOpBelastingvermindering;  // §7.1 the BEZITTELIJK temporal kenmerk of the verbatim "heeft" form
    public List<Segment> getBelastingverminderingPerMaand() { return belastingverminderingPerMaand; }
    public void setBelastingverminderingPerMaand(List<Segment> v) { this.belastingverminderingPerMaand = v; }
    public BigDecimal getTotaleBelastingvermindering() { return totaleBelastingvermindering; }
    public void setTotaleBelastingvermindering(BigDecimal v) { this.totaleBelastingvermindering = v; }
    public List<Segment> getRechthebbend() { return rechthebbend; }
    public void setRechthebbend(List<Segment> v) { this.rechthebbend = v; }
    public List<Segment> getRechtOpBelastingvermindering() { return rechtOpBelastingvermindering; }
    public void setRechtOpBelastingvermindering(List<Segment> v) { this.rechtOpBelastingvermindering = v; }
    private List<Segment> actief;                        // §8.4.2 a second temporal kenmerk for the compound gedurende
    public List<Segment> getActief() { return actief; }
    public void setActief(List<Segment> v) { this.actief = v; }
    private List<Segment> aantalDagenRechtOpBelastingvermindering; // §7.2 a temporal attribute WRITTEN by aantal-dagen-in
    public List<Segment> getAantalDagenRechtOpBelastingvermindering() { return aantalDagenRechtOpBelastingvermindering; }
    public void setAantalDagenRechtOpBelastingvermindering(List<Segment> v) { this.aantalDagenRechtOpBelastingvermindering = v; }
    private List<Segment> standaardbedrag;               // §7.3.2 the BASE value-timeline a tijdsevenredig deel prorates
    public List<Segment> getStandaardbedrag() { return standaardbedrag; }
    public void setStandaardbedrag(List<Segment> v) { this.standaardbedrag = v; }
    private List<Segment> belastingvermindering;         // §7.3.2 a per-maand temporal attribute WRITTEN by tijdsevenredig deel
    public List<Segment> getBelastingvermindering() { return belastingvermindering; }
    public void setBelastingvermindering(List<Segment> v) { this.belastingvermindering = v; }
    private List<Segment> jaardeel;                       // §7.3.2 a per-jaar temporal attribute WRITTEN by tijdsevenredig deel
    public List<Segment> getJaardeel() { return jaardeel; }
    public void setJaardeel(List<Segment> v) { this.jaardeel = v; }
    private LocalDate geboortedatum;                      // §8.4 a non-temporal attribute for the MIXED compound static guard
    public LocalDate getGeboortedatum() { return geboortedatum; }
    public void setGeboortedatum(LocalDate v) { this.geboortedatum = v; }
    public BigDecimal getBasistarief() { return basistarief; }
    public void setBasistarief(BigDecimal v) { this.basistarief = v; }
    public BigDecimal getBodem() { return bodem; }
    public void setBodem(BigDecimal v) { this.bodem = v; }
    public BigDecimal getPlafond() { return plafond; }
    public void setPlafond(BigDecimal v) { this.plafond = v; }
    public boolean isDuur() { return duur; }
    public void setDuur(boolean v) { this.duur = v; }
    public BigDecimal getPrijs() { return prijs; }
    public void setPrijs(BigDecimal v) { this.prijs = v; }
    public Vlucht getReis() { return reis; }
    public void setReis(Vlucht r) { this.reis = r; }
    public boolean isKind() { return kind; }
    public void setKind(boolean v) { this.kind = v; }
    public boolean isStudent() { return student; }
    public void setStudent(boolean v) { this.student = v; }
    public boolean isMinderjarig() { return minderjarig; }
    public void setMinderjarig(boolean v) { this.minderjarig = v; }
    public BigDecimal getStoelnummer() { return stoelnummer; }
    public void setStoelnummer(BigDecimal v) { this.stoelnummer = v; }
    public Vlucht getBonusvlucht() { return bonusvlucht; }
    public void setBonusvlucht(Vlucht v) { this.bonusvlucht = v; }
    public Contingent getBonuscontingent() { return bonuscontingent; }
    public void setBonuscontingent(Contingent v) { this.bonuscontingent = v; }
}
