package io.github.yhavinga.regelspraak.ir;

/**
 * A resolved §9.8 dagsoortdefinitie: the rule "Een dag is een &lt;dagsoort&gt; indien
 * &lt;voorwaarde over de dag&gt;" lifted into the IR. The {@code voorwaarde} is a condition
 * whose only subject is "de dag" (a single date, §13.4.17.41-44 dagsoortreferentie); its
 * leaves are date-component tests (de maand/dag/jaar uit (de dag)), and it may reference
 * OTHER dagsoorten (a feestdag definitie tests kerstdag/paasdag/…). Both transpilers compile
 * it to a per-date boolean predicate (a DRL {@code function boolean is&lt;Dagsoort&gt;(LocalDate)}
 * / an executable-model {@code Predicate<LocalDate>}), which §8.1.5 dagsoortcontrole and the
 * §6.10/§6.11 dagsoort durations then call.
 *
 * @param naam       the dagsoort name (the §3.12 declared singular, e.g. "kerstdag")
 * @param voorwaarde the resolved condition over "de dag" that makes a date this dagsoort
 * @param geldigheid the §13.4.2.5 version validity window (typically {@link Geldigheid#ALTIJD})
 */
public record ResolvedDagsoort(String naam, Conditie voorwaarde, Geldigheid geldigheid) {}
