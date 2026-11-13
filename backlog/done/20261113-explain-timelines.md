# The explanation of timelines and of folds over them

*Found 2026-11-13 in the explanation of the goldens without a scenario (the `Verbaliser` run over every golden case
file, after the card `binding-parse-catala-explanation` of uchar).*

## Aanleiding

1. A fold over the timelines of members names no member. `jutterswet-tempovouw`: "J1 heeft als bewaarlast 48 EUR
   volgens de regel 'bewaarlast van een jutter' (onvoorwaardelijk): het totaal van het bewaartarief van alle vondsten
   van de Jutter" — which vondsten with which timeline gave 48 EUR does not appear. A fold over scalar values does
   name its members ("over V1 (12 EUR/mnd) en V2 (7 EUR/mnd) (2 leden met een waarde)").
2. A rule with a timeline target names the outcome per period, not its computation. `jutterswet-rekenen-per-dag`:
   "J1 heeft als loonproduct 0 tot 01-04-2024, 2.500 van 01-04-2024 tot 01-07-2024 en 0 vanaf 01-07-2024 volgens de
   regel 'loonproduct van een jutter' (onvoorwaardelijk)" — what 2.500 came from does not appear. A rule with a scalar
   target names its computation and its operands.

## Goal

1. A fold over member timelines names its members with what each contributed (the member's timeline, or its total
   where the fold totals), in the style of the scalar fold.
2. A rule with a timeline target names its computation and its operands per period of the outcome, in the style of the
   scalar rule; where that is long, per period in which the outcome changes.

## Acceptance

- Witnesses on `jutterswet-tempovouw`, `jutterswet-tempototaal` and `jutterswet-rekenen-per-dag` (and, after the pin
  of uchar's card `scenario-for-member-timeline-folds`, the same through `bin/regelspraak scenario --explanation`).
- The explanation over every golden case file compared old against new: every difference is one of the intended ones.
- `make gates` green.

Result (2026-11-13):
- A total over the timelines of members names its members with what each contributed ("over V1 (36 EUR uit 12 EUR/mnd
  van 01-01-2026 tot 01-04-2026), V2 (…) en V3 (0 EUR)"); the explanation read the range of the fold from the record,
  where uchar's loader keeps it in `roleBindings`/`sourcePaths`. A rule with a timeline target names its computation:
  operands read as one value once, operands read as a timeline per decided period, cut to it; a fold over a set per
  period the members that contributed there. Witnesses `ExplanationTimelinesTest` (tempovouw, tempototaal,
  rekenen-per-dag); through `bin/regelspraak scenario --explanation` on tempovouw and tempototaal since the pin of uchar
  `202d6f10e`. Old against new over 1113 golden cases: 316 sentences changed, text only added (299 computations on a
  timeline target, 17 members of a fold over timelines). README and `docs/ENGINE.md` describe the forms. Provisional:
  every decided period is named; beside a member's total its timeline only for a fold without a filter.
- `make gates` green.
