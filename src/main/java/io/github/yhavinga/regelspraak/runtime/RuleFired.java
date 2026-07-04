package io.github.yhavinga.regelspraak.runtime;

/**
 * §8.1.9 / §13.4.15.66 execution-status control fact for the "regelversie &lt;X&gt; is gevuurd" regelpredicaat.
 * §8.1.9 (main spec ~line 3433) defines "gevuurd" as "of er aan de voorwaarden in het voorwaardendeel van de
 * regel is voldaan" — i.e. did the named rule's voorwaardendeel hold and its consequence run at all. There is no
 * value the rule writes that records this (a rule may write a value equal to the one already present — a no-op
 * modify that is still "gevuurd"), so the fact base carries an explicit marker: the referenced rule inserts one
 * {@code RuleFired("<naam>")} at the top of its consequence, UNCONDITIONALLY (before any changed-value guard),
 * and the "is gevuurd" condition lowers to an existential {@code exists RuleFired( regel == "<naam>" )}.
 *
 * <p>KEYING IS PER-RULE, NOT PER-SUBJECT (§8.1.9: "bij deze predicaten is dan ook GEEN onderwerpexpressie nodig
 * maar zal een regelversie opgegeven moeten worden") — the predicate asks "did rule X produce a result at all",
 * an existential over the rule, exactly like the sibling "is inconsistent" which reads
 * {@code exists Inconsistentie( regel == "X" )}. So this fact carries only the rule name, no subject field.
 *
 * <p>EMISSION IS OPT-IN: only a rule whose name is referenced by a live "is gevuurd" condition inserts the
 * marker (the transpilers walk the model for the reference first), so the fact base does not bloat with markers
 * no rule reads. A rule firing for N subjects inserts N {@code RuleFired} facts with the same {@code regel} —
 * that duplication is accepted (the existential collapses them; a not-exists guard on the producer would change
 * ITS firing semantics, which we must not do). The bloat is bounded by (#instrumented rules × #subjects).
 *
 * <p>Like {@link CreationMarker} this is a fixed runtime class the generated rules import — model-independent
 * (its shape never varies), so it lives here with {@link RegelSpraakRuntime} rather than being emitted per model
 * by {@link io.github.yhavinga.regelspraak.schema.FactModelGenerator}. The getter gives Drools bean property access
 * ({@code regel}).
 */
public final class RuleFired {
    private final String regel;

    public RuleFired(String regel) { this.regel = regel; }

    public String getRegel() { return regel; }

    @Override public String toString() { return "RuleFired[" + regel + "]"; }
}
