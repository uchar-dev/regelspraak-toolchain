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
