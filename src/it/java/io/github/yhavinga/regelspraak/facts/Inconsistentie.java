package io.github.yhavinga.regelspraak.facts;

/** The runtime result of a §13.4.8 consistency rule: when a rule's assertion is
 *  violated the generated DRL inserts one of these, naming the rule and the offending
 *  subject. It is the observable form of the spec's "inconsistent" result — a fact
 *  other rules (the §9.6 "is inconsistent" regelpredicaat) and callers can react to. */
public class Inconsistentie {
    private final String regel;
    private final Object subject;
    public Inconsistentie(String regel, Object subject) { this.regel = regel; this.subject = subject; }
    public String getRegel() { return regel; }
    public Object getSubject() { return subject; }
    @Override public String toString() { return "Inconsistentie[" + regel + " @ " + subject + "]"; }
}
