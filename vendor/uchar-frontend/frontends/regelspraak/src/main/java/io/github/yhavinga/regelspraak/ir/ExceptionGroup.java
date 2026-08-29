package io.github.yhavinga.regelspraak.ir;

import java.util.List;

/**
 * §6.1 (Phase 5c) one member of a {@link Default}'s exception group ({@code RUNTIME-SEMANTICS} ch. 7.9):
 * a default chain (descending, highest first) inheriting the root group's target and itself possibly nesting
 * via {@link Default#excepties}. An exception group carries NO target of its own. An extension node the v0
 * emitter never produces.
 *
 * @param defaults the member's priority chain, highest first (at least one)
 * @param bron     the statute/source annotation, or null
 */
public record ExceptionGroup(List<Default> defaults, String bron) {}
