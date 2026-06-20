package io.github.yhavinga.regelspraak.ir;

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
     *  {@code partner.gepensioneerd == true}. A to-many navigation or objecttype-membership stays
     *  {@link Onopgelost}. */
    record Kenmerk(String kenmerk, String navigatie, boolean negatie, String bron) implements Conditie {
        public Kenmerk(String kenmerk, String bron) { this(kenmerk, null, false, bron); }
    }

    /** §13.4.14 compound condition: a quantifier over a bullet list of sub-conditions.
     *  `drempel` is the threshold count N for the counting quantifiers (ten minste / ten
     *  hoogste / precies N, §13.4.14.4); 0 and unused for alle / geen / de. */
    enum Kwantor { DE, ALLE, GEEN, TEN_MINSTE, TEN_HOOGSTE, PRECIES }
    record Samengesteld(Kwantor kwantor, int drempel, List<Conditie> onderdelen, String bron) implements Conditie {}

    /** A condition form the resolver did not yet structure (membership/period tests). */
    record Onopgelost(String bron) implements Conditie {}
}
