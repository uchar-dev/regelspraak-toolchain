package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

/**
 * A resolved §13.4.7 feitcreatie: "Een &lt;rolB&gt; van een &lt;anker&gt; is een &lt;rolC&gt; van
 * &lt;onderwerpD&gt;". It CREATES an instance of the feittype that {@code rolB} belongs to, relating
 * each {@code anker} to the {@code doel} objects that fill {@code rolC} of it (§9.4: a new feit links
 * the two existing objects).
 *
 * <p>The fact model represents a relation instance as a reference field on the many-side fact
 * pointing at the one-side fact (the same convention {@code objectCreatie} links by, e.g.
 * {@code Passagier.reis -> Vlucht}). So creation is a {@code modify}-set of {@code zetVeld} — the
 * anker-side role of {@code rolB}'s feittype — on each matching {@code doel}.
 *
 * <p>Only the 1-hop shape lifts into this record: {@code onderwerpD} is the {@code anker} itself,
 * reached from it by the single role {@code rolC}, whose existing relation's reference field is
 * {@code joinVeld}. A multi-hop navigation ("… van de reis van het contingent") cannot be expressed
 * as one join field and stays an honest WARNING in the resolver (it never reaches this record).
 *
 * @param naam       the regel name
 * @param anker      the onderwerp1 object type — the "one" side, the rule's iterated subject
 * @param doel       the filler object type — the "many" side, filling both {@code rolB} and {@code rolC}
 * @param zetVeld    the reference field on {@code doel} -&gt; {@code anker} for the NEW feittype (set in the consequence)
 * @param joinVeld   the reference field on {@code doel} -&gt; {@code anker} for the EXISTING {@code rolC} relation (joined in the when)
 * @param voorwaarde the condition guarding the creation (on the anker), or null
 * @param geldigheid the §13.4.2.5 validity window ({@link Geldigheid#ALTIJD} if always)
 */
public record ResolvedFeitCreatie(String naam, Objecttype anker, Objecttype doel,
                                  String zetVeld, String joinVeld,
                                  Conditie voorwaarde, Geldigheid geldigheid) {}
