package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

/**
 * A resolved §13.4.8 consistency rule in its single-criterion form
 * ("&lt;expressie&gt; moet &lt;operator&gt;", §13.4.8.6 / §9.5): the {@code subject}'s
 * attributes MUST satisfy {@code assertie}, and the rule yields "inconsistent" when
 * they do not (§9.5: "als er niet aan de criteria wordt voldaan … inconsistent").
 *
 * {@code assertie} is the condition that must HOLD (e.g. luchthaven-van-vertrek ≠
 * luchthaven-van-bestemming). The transpiler fires on its negation — the violation —
 * and records the inconsistency, so the result is observable and usable by other
 * rules (the §9.6 "is inconsistent" regelpredicaat). Compound criteria
 * (samengesteld / uniciteit) are a separate, not-yet-lifted form.
 */
public record ResolvedConsistentie(String naam, Objecttype subject, Conditie assertie, Bron bron,
                                   int line, Geldigheid geldigheid) {}
