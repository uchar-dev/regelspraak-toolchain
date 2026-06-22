package io.github.yhavinga.regelspraak.model;

import java.util.List;

/**
 * The RegelSpraak datatypes (§13.3.3), the typed core of the resolved AST. Every
 * attribute, parameter, domain, and (inferred) expression carries one of these.
 * The typeringen matrix (see {@link io.github.yhavinga.regelspraak.resolver.Typering}) is
 * defined over them.
 *
 * Units live on {@link Numeriek}: a unit is optional, but once one operand in an
 * arithmetic pattern carries a unit the others must too (typeringen §4).
 */
public sealed interface Datatype
        permits Datatype.Numeriek, Datatype.Percentage, Datatype.Tekst, Datatype.Booleaan,
                Datatype.DatumInDagen, Datatype.DatumTijd, Datatype.Enumeratie, Datatype.Onbekend {

    /** §13.3.3.2 Numeriek (geheel/decimaal) with an optional unit. */
    record Numeriek(boolean geheel, int decimalen, Teken teken, Eenheid eenheid) implements Datatype {
        public boolean heeftEenheid() { return eenheid != null; }
    }
    /** §13.3.3.3 Percentage. */
    record Percentage(boolean geheel, int decimalen) implements Datatype {}
    /** §13.3.3.4 Tekst. */
    record Tekst() implements Datatype {}
    /** §13.3.3.5 Boolean. */
    record Booleaan() implements Datatype {}
    /** §13.3.3.6 Datum in dagen. */
    record DatumInDagen() implements Datatype {}
    /** §13.3.3.6 Datum en tijd in millisecondes. */
    record DatumTijd() implements Datatype {}
    /** §13.3.4.2 Enumeratie domain. */
    record Enumeratie(List<String> waarden) implements Datatype {}
    /** A reference that could not be resolved to a concrete datatype. */
    record Onbekend(String reden) implements Datatype {}

    enum Teken { ONBEPAALD, NEGATIEF, NIET_NEGATIEF, POSITIEF }

    // --- predicates used by the typeringen matrix ---
    default boolean isNumeriek()   { return this instanceof Numeriek; }
    default boolean isPercentage() { return this instanceof Percentage; }
    /** numeric in the typeringen sense: a number or a percentage. */
    default boolean isGetalachtig() { return isNumeriek() || isPercentage(); }
    default boolean isTekst()      { return this instanceof Tekst; }
    default boolean isBoolean()    { return this instanceof Booleaan; }
    default boolean isDatum()      { return this instanceof DatumInDagen || this instanceof DatumTijd; }
    default boolean isOnbekend()   { return this instanceof Onbekend; }
    /** Orderable by a scalar &lt;/&gt; (so it can drive a §9.7.2 "op volgorde van toenemende/afnemende"
     *  ordering): a number/percentage or a date. Tekst, Booleaan and Enumeratie carry only gelijk/ongelijk
     *  in the typeringen matrix (§5.x), no ordinal {@code <}, so they are NOT orderable — a non-orderable
     *  ordering key keeps the verdeling a COMPLEX marker rather than guessing a lexicographic order. */
    default boolean isOrdenbaar()  { return isGetalachtig() || isDatum(); }

    /** §13.3.3 form (c) "getal": an unbounded numeriek/percentage — any number of decimals, no
     *  precision ceiling. The three getalspecificatie forms are encoded as: (a) "geheel getal"
     *  = geheel∧0; (b) "getal met x decimalen" = ¬geheel∧x≥1; (c) "getal" = ¬geheel∧0. So a
     *  decimalen of 0 is unbounded precisely when the type is NOT a whole number — the geheel
     *  flag is what tells form (c) apart from form (a), both of which carry decimalen 0. */
    default boolean onbeperkt() {
        if (this instanceof Numeriek n)   return !n.geheel() && n.decimalen() == 0;
        if (this instanceof Percentage p) return !p.geheel() && p.decimalen() == 0;
        return false;
    }

    /** The unit of a Numeriek, or null. */
    default Eenheid eenheidOf() {
        return (this instanceof Numeriek n) ? n.eenheid() : null;
    }

    /** A short, human-readable name for diagnostics. */
    default String naam() {
        return switch (this) {
            case Numeriek n   -> "Numeriek" + (n.heeftEenheid() ? " (" + n.eenheid().afkorting() + ")" : "");
            case Percentage p -> "Percentage";
            case Tekst t      -> "Tekst";
            case Booleaan b   -> "Boolean";
            case DatumInDagen d -> "Datum in dagen";
            case DatumTijd d  -> "Datum-tijd";
            case Enumeratie e -> "Enumeratie";
            case Onbekend o   -> "onbekend(" + o.reden() + ")";
        };
    }
}
