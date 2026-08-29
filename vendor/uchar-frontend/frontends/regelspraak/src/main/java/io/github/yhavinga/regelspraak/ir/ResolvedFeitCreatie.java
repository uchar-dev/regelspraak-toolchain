package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

/**
 * A resolved §13.4.7 feitcreatie: "Een &lt;rolB&gt; van een &lt;anker&gt; is een &lt;rolC&gt; van
 * &lt;onderwerpD&gt;". It CREATES an instance of the feittype that {@code rolB} belongs to, relating
 * each {@code anker} to the {@code doel} objects that fill {@code rolC} of it (§9.4: a new feit links
 * the two existing objects).
 *
 * <p>The fact model represents a relation instance as a reference field on the many-side fact
 * pointing at the one-side fact (the same convention {@code objectCreatie} links by, e.g.
 * {@code Deelnemer.groep -> Ploeg}). So creation is a {@code modify}-set of {@code zetVeld} — the
 * anker-side role of {@code rolB}'s feittype — on each matching {@code doel}.
 *
 * <p>The §9.4 canonical example is MULTI-HOP: {@code onderwerpD} reaches the {@code anker} through a chain
 * of intermediate objects ("… een deelnemer van de groep met punten van de toegekende reeks punten":
 * Reeks &rarr; Ploeg &rarr; Persoon). {@code tussenstappen} captures those
 * intermediate hops from the anker; the {@code joinVeld} then joins the {@code doel} to the LAST step,
 * or — for the 1-hop shape, with {@code tussenstappen} empty — directly to the anker.
 *
 * @param naam          the regel name
 * @param anker         the onderwerp1 object type — the "one" side, the rule's iterated subject
 * @param doel          the filler object type — the "many" side of both {@code rolB} and the final {@code rolC} hop
 * @param zetVeld       the reference field on {@code doel} -&gt; {@code anker} for the NEW feittype (set in the consequence)
 * @param joinVeld      the reference field on {@code doel} for the EXISTING final-hop relation (joined in the when)
 * @param tussenstappen the intermediate objects on the navigation path anker &rarr; … &rarr; doel-parent (empty = 1-hop)
 * @param voorwaarde    the condition guarding the creation (on the anker or doel), or null
 * @param geldigheid    the §13.4.2.5 validity window ({@link Geldigheid#ALTIJD} if always)
 * @param feittype      the uchar symbolic form: the created fact type's name (null unless the whole
 *                      symbolic form is derivable — WF-028 all-or-nothing)
 * @param ankerRol      the created fact type's role filled by {@code anker} (== {@code zetVeld}), symbolic
 * @param doelRol       the created fact type's role filled by {@code doel}, symbolic
 * @param joinRol       the RolBinding of the FINAL hop onto {@code doel} (the existing relation), symbolic
 */
public record ResolvedFeitCreatie(String naam, Objecttype anker, Objecttype doel,
                                  String zetVeld, String joinVeld,
                                  java.util.List<Tussenstap> tussenstappen,
                                  Conditie voorwaarde, Geldigheid geldigheid,
                                  String feittype, String ankerRol, String doelRol,
                                  RegelSpraakModel.RolBindingRef joinRol) {

    /** The v0 bean-field-only form, without the symbolic creation fields (they stay absent unless the
     *  whole symbolic form — created relation, both ends, join, and every tussenstap — is derivable). */
    public ResolvedFeitCreatie(String naam, Objecttype anker, Objecttype doel, String zetVeld, String joinVeld,
                               java.util.List<Tussenstap> tussenstappen, Conditie voorwaarde, Geldigheid geldigheid) {
        this(naam, anker, doel, zetVeld, joinVeld, tussenstappen, voorwaarde, geldigheid, null, null, null, null);
    }

    /** One intermediate object on the §9.4 multi-hop navigation path from {@code anker} to {@code doel}.
     *  The reference field lives on the many side (§13.3.9.1): for a TO-MANY hop the CHILD ({@code type})
     *  holds it, joined {@code type( veld == $prev )}; for a TO-ONE hop the PARENT holds it (the side
     *  objectcreatie set it on), joined {@code type( this == $prev.veld )} — {@code viaOuderVeld} selects
     *  which. {@code $prev} is the anker for the first step, the prior step otherwise. {@code rolBinding}
     *  is this hop's symbolic RolBinding (null unless the whole feitcreatie symbolic form is derivable). */
    public record Tussenstap(Objecttype type, String veld, boolean viaOuderVeld,
                             RegelSpraakModel.RolBindingRef rolBinding) {
        public Tussenstap(Objecttype type, String veld, boolean viaOuderVeld) {
            this(type, veld, viaOuderVeld, null);
        }
    }
}
