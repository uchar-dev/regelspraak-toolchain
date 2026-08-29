package io.github.yhavinga.regelspraak.ir;

import java.util.List;

/**
 * §6.1 (Phase 5c) one member of a {@link Rung}'s exception sub-group ({@code RUNTIME-SEMANTICS} ch. 7.9):
 * a rung chain (descending, highest first) inheriting the root ladder's target and itself possibly nesting
 * via {@link Rung#excepties}. A sub-ladder carries NO target of its own. An extension node the v0 emitter
 * never produces.
 *
 * @param rungs the member's priority chain, highest first (at least one)
 * @param bron  the statute/source annotation, or null
 */
public record SubLadder(List<Rung> rungs, String bron) {}
