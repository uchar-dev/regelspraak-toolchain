package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

import java.util.List;

/**
 * A resolved §8.1.6 cross-type uniqueness "verenigd met" ("de namen van alle Cirkels verenigd met de
 * namen van alle Vierkanten moeten uniek zijn"): the value — or combination of values — must be unique
 * across the UNION of the key spaces of two or more {@link Bron sources}. The spec (§8.1.6) reads this
 * as: no two instances within one source share the key, AND no instance of one source shares it with an
 * instance of another.
 *
 * <p>Every source carries the same key arity (the values are unioned positionally, "attributen met
 * hetzelfde datatype"). The transpiler emits one rule per source: a subject of that source is flagged
 * when the total count, summed over every source, of instances whose key equals the subject's exceeds
 * one.
 */
public record ResolvedUniciteitUnie(String naam, List<Bron> bronnen,
                                    io.github.yhavinga.regelspraak.ir.Bron bron, Geldigheid geldigheid) {
    /** One side of the union: an object type and the key attribute(s) whose values join the shared space. */
    public record Bron(Objecttype type, List<String> sleutels) {}
}
