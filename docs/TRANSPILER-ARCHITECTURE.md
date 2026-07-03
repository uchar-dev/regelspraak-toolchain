# Transpiler architecture — one semantic core, two thin back-ends, one runtime

*(goal-M4 D10 — the layering ELEGANT_JAVA_TRANSPILER_FABLE_20260702.md § 3 targets; kept short on purpose.
Correctness authority: the RegelSpraak v2.3.0 spec, always.)*

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
                     parameter, never a field.
runtime/             RegelSpraakRuntime — the ONE rs* implementation (leegwaarde discipline, §6 arithmetic,
                     tijdlijn algebra, verdeling, dagsoort walks) + the reflective fact bridge/Accessors.
                     Generated DRL therefore needs the regelspraak-parser artifact on its compiling
                     classpath (docs/ENGINE.md).
schema/              the EXPLICIT fact-model contract:
  FactModelGenerator emits the bean sources a resolved model implies (attributes incl. Map-dimension and
                     List<Segment>-timeline fields, isX kenmerken, the to-one reference on the many side,
                     Segment, Inconsistentie)
  ContractValidator  checks beans on a classpath against the SAME member derivation and returns ALL
                     violations; RegelSpraakEngine.compile() runs it first and fails fast with the full list.
engine/ (module)     RegelSpraakEngine: resolve -> validate contract -> exec-model -> KieBase; exposes the
                     Dekking manifest so a consumer sees exactly which rules the engine carries.
```

Invariants the tests enforce:

- **Fail-closed, never silent**: everything a back-end cannot lower is an honest compilable no-op
  (MARKER) or an explicit SKIP, each with a deduplicated WARNING; `Resolver --drl` exits non-zero on any
  marker unless `--allow-markers`.
- **Manifest parity**: the differential suite diffs the two back-ends' manifests per fixture; any
  legitimate divergence is enumerated in an explicit allowlist with a reason — no bare skip counts.
- **The differential is parity-only**: closed-form transforms are additionally pinned to ABSOLUTE spec
  values, so a shared bug in both back-ends cannot hide behind agreement.
- **Bean drift**: every model the differential fires is validated against the fact beans it fires into
  (ContractValidator inside the harness), and the generator's own output round-trips
  (generate -> in-memory javac -> validate -> fire).
