package io.github.yhavinga.regelspraak.ir;

import java.util.List;

/**
 * A resolved assignment rule (§13.4.4 gelijkstelling / §13.4.9 initialisatie): a
 * target reference that receives the value of a resolved {@link Expr}. This is the
 * unit the Drools transpiler turns into a rule consequence ("then" side).
 *
 * Only the assignment-shaped resultaatdelen live in THIS record; the other resultaatdeel
 * kinds have since gained nodes of their own — kenmerktoekenning as the {@link Soort#KENMERKTOEKENNING}
 * arm here, objectcreatie as {@link ResolvedObjectCreatie}, verdeling as {@link ResolvedVerdeling},
 * and likewise feitcreatie, startpunt, dagsoort, consistentie and uniciteit. The voorwaardendeel
 * joined the IR as the {@code voorwaarde} component below.
 *
 * @param naam           the regel name (with a per-version suffix when the rule has more
 *                       than one §13.4.2.3 version, so each version is a distinct DRL rule)
 * @param soort          which assignment form produced it
 * @param doel           the assignment target — typically an {@link Expr.AttribuutRef}
 * @param waarde         the resolved value expression (the rule's "then")
 * @param voorwaarde     the resolved condition guarding the rule, or null if unconditional
 *                       (the rule's "when")
 * @param geldigheid     the §13.4.2.5 version validity window; {@link Geldigheid#ALTIJD} when
 *                       always-valid (the transpiler then emits no Rekendatum guard)
 * @param bronAnnotaties the {@code // Bron: …} statuutannotaties written directly above the rule
 *                       (the text after {@code Bron:}, in source order), so uitleg can cite the
 *                       wetsartikel. In-memory only — deliberately NOT emitted to the v0 wire.
 */
public record ResolvedRegel(String naam, Soort soort, Expr doel, Expr waarde,
                            Conditie voorwaarde, Geldigheid geldigheid, List<String> bronAnnotaties) {
    public enum Soort { GELIJKSTELLING, BEREKENING, INITIALISATIE, KENMERKTOEKENNING }

    /** Without statuutannotaties (an empty list) — for the construction sites that carry no
     *  {@code // Bron:} context (or none was written above the rule). */
    public ResolvedRegel(String naam, Soort soort, Expr doel, Expr waarde,
                         Conditie voorwaarde, Geldigheid geldigheid) {
        this(naam, soort, doel, waarde, voorwaarde, geldigheid, List.of());
    }
}
