package io.github.yhavinga.regelspraak.ir;

/**
 * A resolved assignment rule (§13.4.4 gelijkstelling / §13.4.9 initialisatie): a
 * target reference that receives the value of a resolved {@link Expr}. This is the
 * unit the Drools transpiler turns into a rule consequence ("then" side).
 *
 * Only the assignment-shaped resultaatdelen are captured here so far; the other
 * resultaatdeel kinds (kenmerktoekenning, objectcreatie, verdeling, …) and the
 * voorwaardendeel will join the IR as the resolver learns to bind them.
 *
 * @param naam       the regel name
 * @param soort      which assignment form produced it
 * @param doel       the assignment target — typically an {@link Expr.AttribuutRef}
 * @param waarde     the resolved value expression (the rule's "then")
 * @param voorwaarde the resolved condition guarding the rule, or null if unconditional
 *                   (the rule's "when")
 */
public record ResolvedRegel(String naam, Soort soort, Expr doel, Expr waarde, Conditie voorwaarde) {
    public enum Soort { GELIJKSTELLING, BEREKENING, INITIALISATIE, KENMERKTOEKENNING }
}
