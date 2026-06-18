package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;

import java.util.List;

/**
 * A resolved, typed expression — the intermediate representation a §13.4.16/.17
 * RegelSpraak expressie denotes, and the input the Drools transpiler will consume.
 *
 * Every node carries (a) its result {@link Datatype}, computed via the typeringen
 * matrix ({@link io.github.yhavinga.regelspraak.resolver.Typering}), and (b) the original
 * source slice, for diagnostics and traceability back to the regel text.
 *
 * References are bound to the model where the resolver can do so today —
 * attribute-of-subject against a known object type, and parameters. What it cannot
 * yet bind (deep van-chains, role navigation) becomes an {@link Onopgelost} node
 * carrying {@link Datatype.Onbekend}, so the IR is explicit about its resolution
 * coverage rather than guessing: a transpiler can see exactly what is and isn't
 * resolved. This mirrors the resolver's "unknown ⇒ no claim" discipline.
 */
public sealed interface Expr {
    /** The expression's result datatype (per the typeringen matrix). */
    Datatype type();
    /** The original source slice this node was built from (whitespace-normalised). */
    String bron();

    /** §13.2 literal: a number, text, boolean, date, or enumeration value. */
    record Literal(String waarde, Datatype type, String bron) implements Expr {}

    /** §13.4.16 arithmetic operators and §13.4.17.12 percentage-of. */
    enum BinOp { PLUS, MIN, VERMINDERD_MET, MAAL, GEDEELD_DOOR, GEDEELD_DOOR_ABS, TOT_DE_MACHT, PERCENTAGE_VAN }
    record Binair(BinOp op, Expr links, Expr rechts, Datatype type, String bron) implements Expr {}

    /** §13.4.17.10-.58 named functions and aggregations over their argument expressions. */
    enum FunctieKind {
        SOM, TOTAAL, AANTAL, MINIMUM, MAXIMUM, EERSTE, LAATSTE, WORTEL, ABSOLUTE_WAARDE,
        CONCATENATIE, TIJDSDUUR, ABSOLUTE_TIJDSDUUR, EERSTE_PAASDAG, TIJDSEVENREDIG_DEEL,
        AANTAL_DAGEN_IN, JAAR_UIT, MAAND_UIT, DAG_UIT, UUR_UIT, MINUUT_UIT, SECONDE_UIT, MILLISECONDE_UIT,
        DATUM_MET, DATUMTIJD_MET
    }
    /** `elementObjecttype` is the object type the aggregation ranges over — the type
     *  whose instances are summed/counted/etc. (null if unresolved). It is what the
     *  transpiler binds the accumulate source pattern to. */
    record Functie(FunctieKind kind, List<Expr> argumenten, Objecttype elementObjecttype,
                   Datatype type, String bron) implements Expr {}

    /** §13.4.17.25/.26 rounding wrapper; the rounding does not change the datatype. */
    enum AfrondModus { NAAR_BENEDEN, NAAR_BOVEN, REKENKUNDIG, RICHTING_NUL, WEG_VAN_NUL }
    record Afgerond(Expr basis, AfrondModus modus, int decimalen, String bron) implements Expr {
        @Override public Datatype type() { return basis.type(); }
    }

    /** §13.4.17.27-.30 bounding wrapper (a minimum and/or maximum); type is the base's. */
    record Begrensd(Expr basis, Expr minimum, Expr maximum, String bron) implements Expr {
        @Override public Datatype type() { return basis.type(); }
    }

    /**
     * A resolved `<attribuut> van <onderwerp>` reference (§13.4.2.9). `objecttype`
     * is the type the subject denotes (null when it could not be resolved), and
     * `type` is the attribute's declared datatype on that object type.
     *
     * `navigatieRol` is the role name the author WROTE to reach that object (the
     * onderwerp's primary noun, e.g. "heenvlucht" in "de tarief van de heenvlucht"),
     * or null when the reference is the subject's own attribute / names an object type
     * directly. Threading the written role lets the transpiler pick the right
     * reference field when one object-type pair is connected by more than one feittype
     * or role — and distinguishes a wederkerig self-relation ("zijn partner", where the
     * navigated type equals the subject's) from a plain own-attribute read (§13.3.9.2).
     */
    record AttribuutRef(String attribuut, Objecttype objecttype, String onderwerpBron,
                        String navigatieRol, Datatype type, String bron) implements Expr {}

    /** A resolved parameter reference (§13.3.8); the type is the parameter's. */
    record ParameterRef(Parameter parameter, String bron) implements Expr {
        @Override public Datatype type() { return parameter.datatype(); }
    }

    /** A local rule variable introduced by a §13.4.2.10 variabeledeel ("Daarbij geldt: …"). */
    record VariabeleRef(String naam, Datatype type, String bron) implements Expr {}

    /** A built-in reckoning constant or referent: Rekendatum, Rekenjaar, hij, de dag. */
    record Constante(String naam, Datatype type, String bron) implements Expr {}

    /** A reference or sub-expression the resolver could not bind to the model. */
    record Onopgelost(Datatype type, String bron) implements Expr {}
}
