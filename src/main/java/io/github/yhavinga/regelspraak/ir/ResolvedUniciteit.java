package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

import java.util.List;

/**
 * A resolved §8.1.6 uniciteitscontrole ("de &lt;attribuut&gt; van alle &lt;Type&gt; moeten uniek
 * zijn", or a concatenatie of several attributes): the value — or combination of values — of
 * {@code sleutels} must be unique across all instances of {@code subject}.
 *
 * <p>The predicate "is uniek" is WAAR when the values are unique (§8.1.6), so the rule is violated
 * precisely when two DISTINCT instances share the key. The transpiler fires on that violation —
 * an instance for which another instance with an equal (non-null) key exists — and records an
 * Inconsistentie, the observable §9.5 "inconsistent" result. A null key cannot establish a duplicate
 * (two unknown identities are not the same real-world object, §8.1.6), so it does not collide.
 *
 * <p>{@code sleutels} holds one declared attribute name (single-attribute uniqueness) or several (a
 * §5.7 concatenatie). The cross-type {@code verenigd met} form is not lifted here.
 *
 * <p>{@code scopeVeld} (usually null) makes the uniqueness PARENT-SCOPED — "de X van alle Y van &lt;Z&gt;",
 * unique within each Z rather than globally: it is the reference field on a {@code subject} fact that
 * points at the scope owner, so the count is restricted to instances sharing the subject's scope
 * (e.g. seat numbers unique per flight: scopeVeld "reis", counting only passagiers of the same reis).
 *
 * <p>{@code scopeRol} (the uchar {@code scopeRol} extension, null unless the uniqueness is scoped and
 * the scope relation is uniquely derivable) is the symbolic form of {@code scopeVeld}: the fact type
 * and the (declared) scope-parent role — the reference field's relation, made explicit on the wire.
 */
public record ResolvedUniciteit(String naam, Objecttype subject, List<String> sleutels, String scopeVeld,
                                Bron bron, Geldigheid geldigheid,
                                RegelSpraakModel.RolBindingRef scopeRol) {

    /** The v0 form without the symbolic scope binding (a global uniqueness, or a scope whose
     *  relation is not uniquely derivable — the extension field then stays absent). */
    public ResolvedUniciteit(String naam, Objecttype subject, List<String> sleutels, String scopeVeld,
                             Bron bron, Geldigheid geldigheid) {
        this(naam, subject, sleutels, scopeVeld, bron, geldigheid, null);
    }
}
