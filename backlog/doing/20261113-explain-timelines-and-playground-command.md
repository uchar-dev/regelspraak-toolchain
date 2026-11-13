# The explanation of timelines and folds over them, and a playground of its own

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
3. The playground can only be started as a route of the decision service (`PLAYGROUND=true bin/regelspraak serve
   <descriptor> <source.rgs>`), although it analyses the source the user enters and uses the served model only for
   `?via=service`.

## Goal

1. A fold over member timelines names its members with what each contributed (the member's timeline, or its total
   where the fold totals), in the style of the scalar fold.
2. A rule with a timeline target names its computation and its operands per period of the outcome, in the style of the
   scalar rule; where that is long, per period in which the outcome changes.
3. `bin/regelspraak playground [--port N]` starts the playground without a descriptor and without a source
   (`?via=service` is then not offered); `serve` with `PLAYGROUND=true` stays for the comparison with a served model.
   README and `docs/ENGINE.md` describe it.

## Acceptance

- Witnesses on `jutterswet-tempovouw`, `jutterswet-tempototaal` and `jutterswet-rekenen-per-dag` (and, after the pin
  of uchar's card `scenario-for-member-timeline-folds`, the same through `bin/regelspraak scenario --explanation`).
- The explanation over every golden case file compared old against new: every difference is one of the intended ones.
- A test that starts `playground` without arguments and analyses a source.
- `make gates` green.
