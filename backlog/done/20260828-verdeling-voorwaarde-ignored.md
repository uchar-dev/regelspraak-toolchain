# BUG: the verdeling voorwaarde is silently ignored by both transpilers

Found 2026-08-28 during the uchar gate-12 divergence triage (uchar
`backlog/done/20260827-leeg-divergentie-triage.md`, finding 7 — verdict
confirmed by decisive replay through BOTH backends of this repo).

## The bug

A `ResolvedVerdeling` carries a `voorwaarde`, but neither transpiler
ever reads it:

- `DrlTranspiler.verdeling` (L673-768): no reference to
  `v.voorwaarde()`;
- `ExecModelTranspiler.verdeling` (L4420 ff.): same.

Consequence: a conditional allocation distributes UNCONDITIONALLY. A
rule written as "verdeel ... indien <conditie>" runs its split even when
the condition is false — silently, with no marker, no diagnostic.

Reproduced (minimal, synthetic model — Pot/Deelnemer, naar rato,
`voorwaarde: totaal >= 1000`, actual totaal = 40): DRL backend
distributes 40, exec-model backend distributes 40; the control run with
totaal = 2000 also distributes. Expected per the written rule: no
distribution at all when the gate is false.

For reference: the uchar executors (ref and clingo) honour the
voorwaarde — the corpus golden `jutterswet-voorwaardedeling` pins the
gated behaviour with absolute values, and the uchar Drools backend
(this repo's ported transpilers, byte-identical here) is the odd one
out, listed there as a reasoned exclusion.

## Suggested fix

Emit the voorwaarde as a guard on every rule the verdeling lowers to
(the share rules, the maximum/rounding steps and the onverdeelde-rest
rule alike — the whole family must gate together, otherwise a false
condition still writes a rest). In the DRL backend that is an extra
LHS condition on the generated rules; in the exec-model backend the
matching `expr` guard. Add a test that pins: gate false → target
attributes stay leeg AND no rest is written; gate true → identical to
today's output. The uchar golden `jutterswet-voorwaardedeling`
(ir.json + case.json, rights-free) can serve as the reference shape
and expected values.

## Related observation (possibly its own card)

The same triage surfaced an intra-toolchain divergence between the two
backends: a leeg-source gelijkstelling oscillating against an
INITIALISATIE terminates in the DRL backend (result 25, fired=2) but
hits the 100k fire limit in the exec-model backend (non-terminating) on
identical input. The exec-model differential presumably never covers
this shape; worth a pin once the null-write behaviour is decided.

---

## Result (2026-08-28, card verdeling-fixes)

BOTH allocation bugs FIXED at the source (this repo) and synced byte-identically
into the uchar copy (`backends/drools/.../transpiler/`).

**1. The dropped verdeling voorwaarde.** `verdeling` now lowers `v.voorwaarde()`
as a guard on EVERY rule of the lowered family — the share rule, the §9.7.5
onverdeelde-rest rule AND the §9.7.6 Fout companions (leeg gewicht / leeg-of-
negatief maximum) — so a false gate writes NOTHING. In `DrlTranspiler.verdeling`
the voorwaarde is lowered via `conditieInline(...)` and spliced into every bron
pattern (`$src`/`$subject`); a voorwaarde that needs a hoisted join/aggregation
(no in-pattern form) makes the whole verdeling an honest no-op marker rather than
distribute unconditionally. `ExecModelTranspiler.verdeling` mirrors this: it
probes the voorwaarde's exec-scope once (`voorwaarde(...)` gate) — an out-of-scope
or CE-hoisting voorwaarde is an honest `skipList` — and each sub-rule applies the
gate to its `$src` pattern via the new `gateVoorwaarde(...)` helper.

**2. The no-recipient rest.** The onverdeelde-rest rule now requires at least one
recipient (`exists <Doel>(<filter>)` in DRL; `DSL.exists(recipientFilterPattern)`
in the exec-model). A verdeling with ZERO recipients distributes nothing, so the
accumulate Σ=0 no longer writes restant = totaal — the rest stays leeg. The
official RegelSpraak spec v2.3.0 §9.7.5 frames the onverdeelde rest as "het
restant na verdeling" (what remains AFTER distributing) and is SILENT on the
zero-recipient case (§9.7.6 Tabel 19 covers only empty attribute values, not the
empty recipient set), so leeg is a bugfix, not a v1 delta — it agrees with the
uchar reference interpreter and Clingo (golden `jutterswet-gelijkdeling`).

Tests: DrlTest gains 2 structural pins (voorwaarde in all four lowered rules;
rest `exists`); DroolsExecutionTest gains 2 DRL firing tests; ExecModelDifferential
Test gains 2 exec-model firing tests (gate false → no writes / no rest; gate true
and ≥1 recipient → identical to today). `make test` 557 green, `make -B test`
(Drools IT) 966 green (0 failures). No existing pin weakened: the added `exists`
and the empty-voorwaarde splice keep byte-identical output when no voorwaarde is
present (only the rest rule gains its recipient-exists guard).

The uchar gate-12 square closes on both: ref ≡ clingo ≡ Drools; the two goldens
move from value-divergence to PLAYED (played 43 → 45, value-divergence 5 → 3).

NOT addressed (still its own candidate): the "Related observation" — the exec-
model non-termination on a leeg-source gelijkstelling oscillating against an
INITIALISATIE (a leeg-write semantics divergence, uchar gate-12 exclusion
`jutterswet-waardering-v0vorm`). Out of scope for the allocation-bug card.

SPEC_REV is NOT re-pinned here and nothing is pushed — the maintainer re-pins and
lands at merge.
