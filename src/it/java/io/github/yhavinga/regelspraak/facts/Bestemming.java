package io.github.yhavinga.regelspraak.facts;

/** Fact POJO for the role-vs-objecttype name-collision test: a free objecttype that
 *  shares the name "bestemming" with a role but does NOT join the subject. It exists so
 *  the generated package's import compiles; no rule should bind a navigation to it. */
public class Bestemming {
    private String naam;
    public String getNaam() { return naam; }
    public void setNaam(String v) { this.naam = v; }
}
