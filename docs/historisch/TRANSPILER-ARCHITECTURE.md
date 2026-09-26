> Historical, kept for reference: the transpilers and the runtime live in uchar (`uchar/backends/drools/`, see its README).

# Transpiler architecture — one semantic core, two thin back-ends, one runtime

*(Kept short on purpose. Correctness authority: the RegelSpraak v2.3.0 spec, always.)*

```
resolver/            names & navigation authority: the IR carries ONLY declared, model-bound names
                     (written plurals bind their declared singular via MeervoudMatcher; unbindable set
                     reads resolve to Onopgelost + WARNING). Knows NOTHING about back-end capabilities.
ir/                  the resolved AST both back-ends consume — the only thing they may read.
transpiler/kern/     THE shared transpile-time knowledge, imported by both back-ends (pure, Drools-free):
  Namen              Dutch phrase -> identifier/getter/setter, rule-name escaping, comment-safe splicing,
                     DRL-string escaping (embedded quotes escape, never strip)
  Operatoren         comparison symbol, flip (logical negation) vs omkeren (operand swap)
  Tijdrekening       tijdseenheid -> ChronoUnit (KWARTAAL pseudo-unit), §8.2 toets-codes
  Literalen          literal -> typed value (BigDecimal incl. Rationaal, LocalDate(Time), labels, boolean)
  Dagsoorten         the ONE §9.8 dagsoortdefinitie compiler -> Predicate<LocalDate>
  Dekking            the coverage manifest: per construct LOWERED | MARKER(reason) | SKIP(reason)
transpiler/
  DrlTranspiler      thin DRL-text back-end (the differential ORACLE + debug emitter): patterns/joins +
                     calls into the runtime via `import function` — ZERO function bodies in the emitted
                     text; §9.8 predicates ride as session globals (dagsoortGlobalen()). Per-rule state
                     (hoisted accumulate CEs) is an explicitly threaded Acc context, never a field.
  model/ExecModelTranspiler
                     thin executable-model back-end (the SHIPPABLE path): the same kern, the same runtime
                     helpers captured in lambdas, the same manifest. The value-path subject is a threaded
                     parameter, never a field. The ONE branch that carries §6.1 prioritized defaults (an
                     `Uitzondering op`-family, which the resolver lifts OUT of resolvedRegels): one
                     reduce-rule per (effective stack, CONCRETE subject type), composed over the
                     inheritance chain — own exceptions, inherited exceptions, the base (the own one
                     replaces the inherited), the initialisation. An ordinary rule is the base of its own
                     level and is then taken out of the rule circuit on exactly the classes where the
                     stack writes its target. The DRL-text branch REFUSES this family by name (see
                     DrlTranspiler.defaultsMarker): the chain is a per-subject fold with leeg
                     fall-through, and that branch exists as this one's differential oracle, so a second
                     implementation from one reading would prove nothing.
runtime/             RegelSpraakRuntime — the ONE rs* implementation (leegwaarde discipline, §6 arithmetic,
                     tijdlijn algebra, verdeling, dagsoort walks) + the reflective fact bridge/Accessors.
                     Generated DRL therefore needs the regelspraak-parser artifact on its compiling
                     classpath (docs/ENGINE.md).
schema/              the EXPLICIT fact-model contract:
  FactModelGenerator emits the bean sources a resolved model implies (attributes incl. Map-dimension and
                     List<Segment>-timeline fields, isX kenmerken, the to-one reference on the many side,
                     Segment, Inconsistentie). A subtype's bean `extends` its supertype's and declares
                     only its OWN members, so subsumption is the JVM's: a pattern on the ancestor class
                     matches descendant facts and an aggregation ranges extensionally, with no per-type
                     duplication. Every facet read in both back-ends therefore asks the model's
                     CHAIN accessors (granulariteitLeden() and family), never the own declaration map —
                     reading the own map answers "not temporal" for an INHERITED timeline, which is not a
                     missing binding but a wrong judgement.
  ContractValidator  checks beans on a classpath against the SAME member derivation and returns ALL
                     violations; RegelSpraakEngine.compile() runs it first and fails fast with the full list.
engine/ (module)     RegelSpraakEngine: resolve -> validate contract -> exec-model -> KieBase; exposes the
                     Dekking manifest so a consumer sees exactly which rules the engine carries.
```

Invariants the tests enforce:

- **Fail-closed, never silent**: everything a back-end cannot lower is an honest compilable no-op
  (MARKER) or an explicit SKIP, each with a deduplicated WARNING; `RegelSpraakCli --drl` exits non-zero on any
  marker unless `--allow-markers`.
- **Manifest parity**: the differential suite diffs the two back-ends' manifests per fixture; any
  legitimate divergence is enumerated in an explicit allowlist with a reason — no bare skip counts.
- **The differential is parity-only**: closed-form transforms are additionally pinned to ABSOLUTE spec
  values, so a shared bug in both back-ends cannot hide behind agreement.
- **Bean drift**: every model the differential fires is validated against the fact beans it fires into
  (ContractValidator inside the harness), and the generator's own output round-trips
  (generate -> in-memory javac -> validate -> fire).
