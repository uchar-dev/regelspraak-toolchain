package io.github.yhavinga.regelspraak.ir;

import java.util.List;

/**
 * §6.1 (Phase 4) the prioritized defaults — the engine-neutral wire form of a prioritised default calculus,
 * per {@code spec/RUNTIME-SEMANTICS.md} ch. 7. One writer aimed at one target (a characteristic XOR an
 * attribute of {@link #onderwerp}); {@link #defaults} are in DESCENDING priority and the highest default that
 * decides wins (attribute defaults fall through on a leeg consequence; the initialisation default, lowest, is
 * justified iff the target is empty in the input). Multiple defaults groups on ONE target are admissible only
 * as a declared-{@link #ongeordend} group (ch. 7.2), whose differing simultaneous decisions route to the §9.5
 * channel. An extension node the v0 emitter never produces; the RegelSpraak resolver never builds it — it
 * is carried by the Catala frontend and hand-authored anchors, and consumed here by the Drools loader.
 *
 * @param naam       the defaults-group name, or null
 * @param onderwerp  the subject objecttype NAME (resolved by the consumer against its model)
 * @param kenmerk    the characteristic target, or null on attribute defaults (exactly one of
 *                   kenmerk/attribuut is non-null, WF-050)
 * @param attribuut  the attribute target, or null on characteristic defaults
 * @param ongeordend declared-unordered: two or more defaults groups on one target require this marking on
 *                   every one
 * @param defaults   the priority chain, highest first
 * @param bron       the statute/source annotation, or null
 * @param geldigheid the §13.4.2.5 validity window ({@link Geldigheid#ALTIJD} = always), or null
 */
public record PrioritizedDefaults(String naam, String onderwerp, String kenmerk, String attribuut,
                                  boolean ongeordend, List<Default> defaults, String bron,
                                  Geldigheid geldigheid) {

    /** Whether this group writes a characteristic (true) or an attribute (false). */
    public boolean isKenmerk() { return kenmerk != null; }

    /** The bare target name — the characteristic or the attribute, whichever this group carries. */
    public String doel() { return kenmerk != null ? kenmerk : attribuut; }
}
