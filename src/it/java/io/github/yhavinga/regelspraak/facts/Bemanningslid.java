package io.github.yhavinga.regelspraak.facts;

/** Fact POJO for the §13.4.7 feitcreatie far-side ANKER guard test: a Bemanningslid fills the
 *  "bemanningslid" role of a Vlucht through its {@code bemandeVlucht} back-reference, so a
 *  feitcreatie gated "indien de vlucht heeft een bemanningslid" hosts
 *  {@code exists Bemanningslid( bemandeVlucht == $subject )} — a relation distinct from the
 *  doel join (the passengers), so the membership genuinely filters which vluchten get the relation. */
public class Bemanningslid {
    private Vlucht bemandeVlucht;
    public Vlucht getBemandeVlucht() { return bemandeVlucht; }
    public void setBemandeVlucht(Vlucht v) { this.bemandeVlucht = v; }
}
