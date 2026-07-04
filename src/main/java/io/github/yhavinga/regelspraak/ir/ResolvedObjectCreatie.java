package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

import java.util.List;

/**
 * A resolved §13.4.6 objectcreatie: the subject creates a new object of
 * {@code nieuwType}, sets the given attributes, and is linked to it through the
 * feittype that relates them. This is the unit the transpiler turns into a DRL
 * consequence that inserts a new fact.
 *
 * @param naam         the regel name
 * @param nieuwType    the object type being created (null if unresolved)
 * @param subject      the object type that owns the rule (the creator)
 * @param toekenningen the attribute assignments on the new object
 * @param voorwaarde   the condition guarding the creation, or null
 * @param geldigheid   the §13.4.2.5 version validity window ({@link Geldigheid#ALTIJD} if always)
 */
public record ResolvedObjectCreatie(String naam, Objecttype nieuwType, Objecttype subject,
                                    List<Toekenning> toekenningen, Conditie voorwaarde, Geldigheid geldigheid) {
    /** One §13.4.6.2 waardetoekenning on the new object. {@code kenmerk} distinguishes the DISTINCT
     *  §13.4.6.4 kenmerkwaardetoekenning ("&lt;kenmerknaam&gt; gelijk aan waar|onwaar" — {@code attribuut}
     *  then holds the DECLARED kenmerk name and {@code waarde} a Booleaan literal, resolver-enforced)
     *  from the §13.4.6.3 attribuutwaardetoekenning (generic expressie RHS). Both back-ends set the
     *  same-named property either way; the flag keeps the IR honest about which spec form it carries. */
    public record Toekenning(String attribuut, Expr waarde, boolean kenmerk) {
        /** An attribuutwaardetoekenning (§13.4.6.3). */
        public Toekenning(String attribuut, Expr waarde) { this(attribuut, waarde, false); }
    }
}
