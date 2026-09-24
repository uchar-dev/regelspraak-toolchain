package io.github.yhavinga.regelspraak.ir;

import java.util.List;

/**
 * §6.1 one default of a {@link PrioritizedDefaults} group (or of an {@link ExceptionGroup}) — a
 * (justification, consequence) pair aimed at the group's target. On characteristic defaults a justified
 * default decides {@link #besluit} (true = attribute, false = do-not-attribute); on attribute defaults a
 * justified default decides {@link #consequentie} when it evaluates gevuld, and FALLS THROUGH on a leeg. The
 * {@link #initialisatie} default (lowest only) has a null {@link #voorwaarde}: it is justified iff the target
 * is empty in the input facts (ch. 7.3). A default carrying {@link #excepties} is a nested exception group
 * (ch. 7.9): a group-default (no own besluit/consequentie — its exception groups are its decision) or a form-a
 * default (own content too — the exception groups decide first, its own decision the fall-back when
 * they decide nothing).
 *
 * @param besluit       the characteristic decision (true/false), or null on an attribute default
 * @param voorwaarde    the justification, or null on the initialisation default
 * @param consequentie  the attribute default's value, or null on a characteristic/group default
 * @param initialisatie the initialisation default marker (lowest only)
 * @param excepties     the nested exception groups (ch. 7.9), or null
 * @param bron          the statute/source annotation, or null
 */
public record Default(Boolean besluit, Conditie voorwaarde, Expr consequentie,
                      boolean initialisatie, Excepties excepties, String bron) {

    /** A default's nested exception groups: one or more {@link ExceptionGroup} members that reconcile as a
     *  declared-{@link #ongeordend} group at this default's level (WF-075). */
    public record Excepties(boolean ongeordend, List<ExceptionGroup> leden) {}

    /** Whether this default carries its OWN decision content (a besluit or a consequentie) — false for a
     *  group-default whose only content is its {@link #excepties} exception groups. */
    public boolean heeftEigenInhoud() { return besluit != null || consequentie != null; }
}
