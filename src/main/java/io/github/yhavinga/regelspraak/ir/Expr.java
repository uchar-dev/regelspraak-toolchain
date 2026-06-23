package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Granulariteit;
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

    /** §5.4/§13.4.17.9 "reeks van teksten en waarden": an interleaving of literal karakterreeks chunks and
     *  «expressie» parts, each coerced to Tekst and concatenated at runtime. A PLAIN "…" (no «…») stays an
     *  {@link Literal}; this node is built ONLY when at least one «expressie» part is present. */
    record TekstReeks(List<Deel> delen, String bron) implements Expr {
        @Override public Datatype type() { return new Datatype.Tekst(); }
        /** One piece of a tekstenwaardereeks: a literal chunk or an embedded expression. */
        public sealed interface Deel {}
        /** A literal karakterreeks chunk (already unquoted, no guillemets). */
        public record Letterlijk(String tekst) implements Deel {}
        /** A «expressie» part — its value is coerced to Tekst at evaluation. */
        public record Ingevoegd(Expr expr) implements Deel {}
    }

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
     *  transpiler binds the accumulate source pattern to.
     *
     *  `leegwaarde` is the §5.8.2 "of … als die er niet zijn" fallback for a sommatie
     *  (SOM/TOTAAL): the value the sum takes when it has no non-empty contributors,
     *  instead of a leegwaarde. Null when the source declared no fallback (an empty
     *  sommatie is then a leegwaarde); always null for non-sommatie functions.
     *
     *  `filter` is a §13.4.2 "die/dat &lt;predicaat&gt;" subselectie on the counted/aggregated
     *  element ("het aantal passagiers van de reis die een kind zijn" — only the kind-passagiers).
     *  It is resolved against the ELEMENT type, not the rule subject. Null when the operand
     *  carries no such filter. A filter the resolver cannot yet structure (the kenmerk-membership
     *  predicates these subselecties use are still {@link Conditie.Onopgelost}) is kept here as an
     *  Onopgelost marker so the transpiler can see it must NOT lower the aggregation to a count
     *  over every instance — it emits a visible marker instead of a silently-wrong total. */
    /** §13.4.17.51-.54 a resolved dimension selection on an aggregation: the dimension the
     *  aggregation ranges over and the CONCRETE ordered list of labels selected — the
     *  "vanaf &lt;a&gt; t/m &lt;b&gt;" interval and the "in {…}" set are already expanded against the
     *  dimension's declared label order (§13.3.7.5), and "over alle …" is every label. The
     *  transpiler folds the dimensioned attribute over exactly these labels (a fixed sum/min/max
     *  of {@code map.get(label)}), so no label arithmetic survives into the back-end. */
    record DimensieSelectie(String dimensie, List<String> labels, String bron) {}

    /** {@code dimensieSelectie} (§13.4.17.51-.54), when non-null, restricts a SOM/MIN/MAX over a
     *  gedimensioneerd attribuut to the selected dimension labels — the only aggregation that
     *  ranges over a dimension's value space rather than over object instances. Null for an
     *  ordinary instance aggregation. */
    record Functie(FunctieKind kind, List<Expr> argumenten, Objecttype elementObjecttype,
                   Expr leegwaarde, Conditie filter, DimensieSelectie dimensieSelectie,
                   Datatype type, String bron, Granulariteit granulariteit) implements Expr {
        /** {@code granulariteit} is the §13.4.17.57 aantal-dagen-in operator's stated bucket unit ("in <de maand|
         *  het jaar>"); null for every other functie. The transpiler requires it to MATCH the doel's tijdlijn
         *  (§4.21/§7.2), markering a mismatch rather than silently bucketing by the wrong unit. */
        public Functie(FunctieKind kind, List<Expr> argumenten, Objecttype elementObjecttype,
                       Expr leegwaarde, Conditie filter, DimensieSelectie dimensieSelectie,
                       Datatype type, String bron) {
            this(kind, argumenten, elementObjecttype, leegwaarde, filter, dimensieSelectie, type, bron, null);
        }
        /** Most call sites build an instance aggregation with no dimensieselectie and no granulariteit. */
        public Functie(FunctieKind kind, List<Expr> argumenten, Objecttype elementObjecttype,
                       Expr leegwaarde, Conditie filter, Datatype type, String bron) {
            this(kind, argumenten, elementObjecttype, leegwaarde, filter, null, type, bron, null);
        }
    }

    /** §13.4.17.25/.26 rounding wrapper. Rounding to N decimals sets the result's precision to N
     *  (§6.1.2: it is exactly the explicit afronding that lets a higher-precision result be assigned
     *  to a lower-precision attribute), keeping the base's unit and sign. */
    enum AfrondModus { NAAR_BENEDEN, NAAR_BOVEN, REKENKUNDIG, RICHTING_NUL, WEG_VAN_NUL }
    record Afgerond(Expr basis, AfrondModus modus, int decimalen, String bron) implements Expr {
        @Override public Datatype type() {
            Datatype b = basis.type();
            if (b instanceof Datatype.Numeriek n) return new Datatype.Numeriek(decimalen == 0, decimalen, n.teken(), n.eenheid());
            if (b instanceof Datatype.Percentage p) return new Datatype.Percentage(decimalen == 0, decimalen);
            return b;
        }
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
                        String navigatieRol, Datatype type, String bron, boolean set) implements Expr {
        /** {@code set} marks an operand that ranges over a SET (a §13.4.2 set quantifier — alle/al/geen van de/
         *  ten minste…/ten hoogste…/precies… — NOT the bare definite "de"), computed structurally from the
         *  onderwerp's quantifier rather than re-scanned from the onderwerp text. Lets the transpiler/resolver
         *  distinguish a set aggregation from a single-object read even when the element type equals the subject. */
        public AttribuutRef(String attribuut, Objecttype objecttype, String onderwerpBron,
                            String navigatieRol, Datatype type, String bron) {
            this(attribuut, objecttype, onderwerpBron, navigatieRol, type, bron, false);
        }
    }

    /** §3.6 a single cell of a gedimensioneerd attribuut: the bare attribute read plus the selected label
     *  for each of its dimensions (in the attribute's declared dimension order — one label for a
     *  single-dimension attribute). The transpiler lowers it to {@code <map-read>.get(label)}; the result
     *  type is the attribute's element datatype. A read must pin EVERY dimension (§3.6), so the labels list
     *  has one entry per dimension. */
    record DimensieCel(AttribuutRef attribuut, List<String> labels, String bron) implements Expr {
        @Override public Datatype type() { return attribuut.type(); }
    }

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
