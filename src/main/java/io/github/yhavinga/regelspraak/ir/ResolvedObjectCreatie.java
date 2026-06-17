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
 */
public record ResolvedObjectCreatie(String naam, Objecttype nieuwType, Objecttype subject,
                                    List<Toekenning> toekenningen, Conditie voorwaarde) {
    /** One attribute of the new object set to a resolved value expression. */
    public record Toekenning(String attribuut, Expr waarde) {}
}
