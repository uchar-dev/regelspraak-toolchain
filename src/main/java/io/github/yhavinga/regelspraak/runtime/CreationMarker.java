package io.github.yhavinga.regelspraak.runtime;

/**
 * §13.4.6 / §9 idempotence control fact for objectcreatie. A creation consequence must be a FUNCTION of the
 * fact base, not of the fire count (§9 regelvuurmodel): {@code no-loop} only blocks a rule re-activating on
 * its OWN insertion, so when ANOTHER rule later modifies a matched subject the unguarded creation rule
 * re-fires and mints a duplicate instance. This marker keys a per-rule, per-subject "already created" flag
 * so the creation fires at most once per (rule, subject).
 *
 * <p>The creation rule's LHS gains {@code not( CreationMarker( regel == "<rule>", subject == $subject ) )}
 * and its consequence inserts a {@code new CreationMarker("<rule>", $subject)} alongside the created object.
 * Used for the TO-MANY target-role case and the orphan-insert (no back-reference) case; a 1:1 target role is
 * instead guarded on the absence of the linked instance itself (a stronger, fact-base-derived check).
 *
 * <p>Unlike the generated {@code io.github.yhavinga.regelspraak.facts.Inconsistentie} carrier (emitted per model by
 * {@link io.github.yhavinga.regelspraak.schema.FactModelGenerator}), this is a fixed runtime class the generated rules
 * import — it is model-independent (its shape never varies), so it lives with {@link RegelSpraakRuntime}.
 * The getters give Drools bean property access ({@code regel}, {@code subject}); {@code subject} is
 * identity-compared in the guard (the same fact reference).
 */
public final class CreationMarker {
    private final String regel;
    private final Object subject;

    public CreationMarker(String regel, Object subject) { this.regel = regel; this.subject = subject; }

    public String getRegel() { return regel; }
    public Object getSubject() { return subject; }

    @Override public String toString() { return "CreationMarker[" + regel + " @ " + subject + "]"; }
}
