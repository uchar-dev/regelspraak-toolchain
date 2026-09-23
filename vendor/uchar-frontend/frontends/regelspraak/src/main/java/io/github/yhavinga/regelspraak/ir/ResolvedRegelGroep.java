package io.github.yhavinga.regelspraak.ir;

import java.util.List;

/**
 * A resolved §9.10 Regelgroep — a DOCUMENTED EXTENSION, not a §13 construct (v2.3.0 gives regelgroep no
 * written form; see grammar/DIVERGENCES.md '§9.10 recursie / regelgroep'). The group is a namespacing
 * unit over its member rules: each member resolves EXACTLY as an ungrouped rule (its own
 * {@link ResolvedRegel} / consistentie / … entry, keyed on the BARE written name), and the group only records
 * <em>which</em> rule names belong to it (in written order) plus the {@code recursief} marker.
 *
 * <p>Fase 1 (namespacing): both back-ends emit each member rule identically to an ungrouped rule, except the
 * engine-visible rule-name header is prefixed {@code "<naam>: "}. Every data key (Inconsistentie regel,
 * RuleFired regel, CreationMarker regel) stays the bare member name, so all name-keyed mechanisms keep working.
 *
 * <p>Fase 2 ({@code recursief}): a structural-validation-plus-documentation marker, NOT an execution mechanism —
 * Drools forward-chaining reaches the fixpoint within one {@code fireAllRules}, and the §13.4.6/§9 objectcreatie
 * idempotence guard (backlog/done/20260704-10:22) makes each creation advance exactly one generation per instance.
 * The resolver checks: a recursief group with NO objectcreatie member is an ERROR (the marker is meaningless);
 * an objectcreatie member WITHOUT a bounding voorwaarde is a WARNING (termination not automatable).
 *
 * @param naam      the group name as written (whitespace-normalised)
 * @param recursief whether the header carried {@code is recursief}
 * @param leden     the member RULE NAMES in written order (the bare names, matching the {@link ResolvedRegel}
 *                  / consistentie / objectcreatie / … entries the members produced)
 * @param bron      the source slice of the header (for diagnostics)
 * @param line      the 1-based source line of the header
 */
public record ResolvedRegelGroep(String naam, boolean recursief, List<String> leden, Bron bron, int line) {
}
