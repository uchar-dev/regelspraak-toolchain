package io.github.yhavinga.regelspraak.facts;

/** Fact POJO for the §8.1.6 "verenigd met" example: a name must be unique across Cirkels AND
 *  Vierkanten together. */
public class Cirkel {
    private String naam;
    public String getNaam() { return naam; }
    public void setNaam(String v) { this.naam = v; }
}
