package io.github.yhavinga.regelspraak.facts;

/** Fact POJO for the §9.4 multi-hop feitcreatie test. A Contingent references its Vlucht through the
 *  to-one {@code contingentvlucht} field (the side objectcreatie set the link on), which the generated
 *  chain join reads as {@code Vlucht( this == $subject.contingentvlucht )}. */
public class Contingent {
    private String naam;                     // goal-M4 drift test: the fixture models declare it
    public String getNaam() { return naam; }
    public void setNaam(String v) { this.naam = v; }
    private Vlucht contingentvlucht;   // to-one parent->child reference the intermediate hop reads
    public Vlucht getContingentvlucht() { return contingentvlucht; }
    public void setContingentvlucht(Vlucht v) { this.contingentvlucht = v; }
}
