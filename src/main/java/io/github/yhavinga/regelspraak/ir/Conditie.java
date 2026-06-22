package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

import java.util.List;

/**
 * A resolved condition — the §13.4.13/.14/.15 voorwaardendeel as IR, i.e. the
 * "when" side of the rule the Drools transpiler will emit (the {@link Expr} value
 * being the "then" side).
 *
 * Operands are resolved {@link Expr} trees, so a comparison or predicate already
 * carries the bound references and datatypes of its operands. The operator and
 * predicate enums are defined here, in the IR, so the IR depends only on the
 * model — the resolver maps the parse tokens onto these and runs the typeringen
 * checks while it builds them.
 *
 * Forms the resolver does not yet structure (kenmerk/objecttype membership tests,
 * period comparisons) become an {@link Onopgelost} node carrying their source
 * text, keeping the IR explicit about its coverage.
 */
public sealed interface Conditie {
    /** The original source slice this condition was built from (whitespace-normalised). */
    String bron();

    /** §5.1-5.10 comparison operators (the typeringen ordering/equality/date families). */
    enum Operator {
        KLEINER, KLEINER_OF_GELIJK, GROTER, GROTER_OF_GELIJK,
        GELIJK, ONGELIJK,
        EERDER, EERDER_OF_GELIJK, LATER, LATER_OF_GELIJK
    }
    record Vergelijking(Operator op, Expr links, Expr rechts, String bron) implements Conditie {}

    /** §5.11-5.16 one-sided predicates. */
    enum Predicaatsoort { LEEG, GEVULD, ELFPROEF, NUMERIEK_MET_EXACT, DAGSOORT }
    record Predicaat(Predicaatsoort soort, Expr operand, String bron) implements Conditie {}

    /** §8.1.8 kenmerk-membership test ("de Meting is oud" / "hij heeft het recht …"): true when the
     *  named object carries the boolean kenmerk. {@code navigatie} is null for the subject's OWN kenmerk
     *  (lowered to the subject's boolean property, {@code oud == true}); for a TO-ONE navigated kenmerk
     *  ("de partner van X is gepensioneerd") it is the navigation role, lowered to the property path
     *  {@code partner.gepensioneerd == true}. A to-many navigation in a single-pattern rule stays
     *  {@link Onopgelost}. {@code eigenaar}, when non-null, is the object type whose pattern this BARE
     *  constraint must attach to — a §13.4.7 feitcreatie hosts a doel-kenmerk on the {@code $doel} pattern,
     *  so the transpiler emits it only on that type and markers it elsewhere (never on the anker, even when
     *  the anker declares a same-named kenmerk). null leaves it unconstrained (the single-subject rule). */
    record Kenmerk(String kenmerk, String navigatie, boolean negatie, Objecttype eigenaar, String bron) implements Conditie {
        public Kenmerk(String kenmerk, String navigatie, boolean negatie, String bron) { this(kenmerk, navigatie, negatie, null, bron); }
        public Kenmerk(String kenmerk, String bron) { this(kenmerk, null, false, null, bron); }
    }

    /** §8.1.7 rolcheck ("hij is een passagier"): true when the subject FILLS the role {@code rol}.
     *  In the §9.4 reference-field fact model the subject fills a role iff its reference to the OTHER
     *  end of that feittype is set, so this lowers to a bare null-test on that reference field
     *  ({@code reis != null}); {@code anderTeFiller} is that other end's object type, from which the
     *  transpiler looks up the reference field. {@code negatie} marks the §8.1.7/§8.1.8.3 "is geen &lt;rol&gt;"
     *  negation — the subject does NOT fill the role — which lowers to the inverse null-test ({@code reis == null}).
     *  When the reference lives on the other side (the §8.1.7 "heeft" form) it is a {@link VerreRollidmaatschap}. */
    record Rollidmaatschap(String rol, Objecttype anderTeFiller, String bron, boolean negatie) implements Conditie {
        /** Positive rolcheck — the subject fills the role. */
        Rollidmaatschap(String rol, Objecttype anderTeFiller, String bron) { this(rol, anderTeFiller, bron, false); }
    }

    /** §8.1.7 FAR-side rolcheck ("de vlucht heeft een passagier"): the subject sits on the "één" side, so the
     *  reference lives on the OTHER object — a to-many {@code filler} whose {@code backref} field points back to
     *  the subject. True when at least one such filler exists, so it lowers to an EXISTS-join (a separate
     *  when-clause {@code exists <filler>( <backref> == $subject )}), NOT an in-pattern constraint — which is why
     *  it can only be peeled when it is a rule's TOP-LEVEL condition; nested in an AND/OR or a comparison it
     *  stays a marker. {@code negatie} is the "heeft geen" form ({@code not <filler>(...)}). */
    record VerreRollidmaatschap(Objecttype filler, String backref, boolean negatie, String bron) implements Conditie {}

    /** §13.4.14 compound condition: a quantifier over a bullet list of sub-conditions.
     *  `drempel` is the threshold count N for the counting quantifiers (ten minste / ten
     *  hoogste / precies N, §13.4.14.4); 0 and unused for alle / geen / de. */
    enum Kwantor { DE, ALLE, GEEN, TEN_MINSTE, TEN_HOOGSTE, PRECIES }
    record Samengesteld(Kwantor kwantor, int drempel, List<Conditie> onderdelen, String bron) implements Conditie {}

    /** A condition form the resolver did not yet structure (membership/period tests). */
    record Onopgelost(String bron) implements Conditie {}
}
