package io.github.yhavinga.regelspraak.ir;

import java.util.List;

/**
 * §6.1 one rung of a {@link DefaultLadder} (or a {@link SubLadder}) — a (justification, consequence) pair
 * aimed at the ladder's target. On a CHARACTERISTIC ladder a justified rung decides {@link #besluit}
 * (true = attribute, false = do-not-attribute); on an ATTRIBUTE ladder a justified rung decides
 * {@link #consequentie} when it evaluates gevuld, and FALLS THROUGH on a leeg. The
 * {@link #initialisatie} rung (lowest only) has a null {@link #voorwaarde}: it is justified iff the target
 * is empty in the input facts (ch. 7.3). A rung carrying {@link #excepties} is a nested exception group
 * (ch. 7.9): a group-rung (no own besluit/consequentie — the sub-group is its decision) or a form-a rung
 * (own content too — the sub-group decides first, its own decision the fall-back when the sub-group is
 * empty). An extension node the v0 emitter never produces.
 *
 * @param besluit       the characteristic decision (true/false), or null on an attribute rung
 * @param voorwaarde    the justification, or null on the initialisation rung
 * @param consequentie  the attribute rung's value, or null on a characteristic/group rung
 * @param initialisatie the initialisation rung marker (lowest only)
 * @param excepties     the nested exception sub-group (ch. 7.9), or null
 * @param bron          the statute/source annotation, or null
 */
public record Rung(Boolean besluit, Conditie voorwaarde, Expr consequentie,
                   boolean initialisatie, Excepties excepties, String bron) {

    /** A rung's nested exception sub-group: one or more {@link SubLadder} members that reconcile as a
     *  declared-{@link #ongeordend} group at this rung's level (WF-075). */
    public record Excepties(boolean ongeordend, List<SubLadder> leden) {}

    /** Whether this rung carries its OWN decision content (a besluit or a consequentie) — false for a
     *  group-rung whose only content is its {@link #excepties} sub-group. */
    public boolean heeftEigenInhoud() { return besluit != null || consequentie != null; }
}
