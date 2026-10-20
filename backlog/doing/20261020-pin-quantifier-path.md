# pin-quantifier-path: de pin op uchar met de kwantor over een pad

*Vastgelegd op 2026-10-20, stap 6 van de volgorde van de werkbank. Toolchain `09efa300`, uchar-pin `78bae4e3` naar
`d9c0b9a5`.*

## Aanleiding

uchar landde de kaart `frontend-kwantor-over-een-pad` (`backlog/done/20260930-frontend-kwantor-over-een-pad.md` in
uchar): de frontend schrijft een kwantor over een pad van relaties als `QuantifierFold.sourcePath`, ook in de voorwaarde
van een `Uitzondering op`, en de Drools-backend voert hem uit. De frontend weigert sindsdien luid een lezing van het
onderwerp in een toets of filter als onderwerp en element hetzelfde type hebben (RGS-349, RGS-350). Nieuwe golden
`jutterswet-dringende-melding`; gate 12 van uchar speelt 261 goldens (was 260). De werkbank wacht hierop om brug K6 en
het regelset-interne feittype `startperiode` uit de v2-regelset te halen (haar stap 7).

## Wat er gebeurt

1. De pin naar `d9c0b9a5`; `make check-pin`.
2. Wat de reactor of de replay-poort daardoor verschuift, met reden (onder meer `EXPECTED_REPLAY_PLAYED` in
   `scripts/check-service-replay.sh`, nu 230).

## Acceptatie

- (a) `make gates` groen.
- (b) Een pin of golden die verandert, krijgt een oorzaak in het Result.

## Omvang

May touch: de pin, `scripts/check-service-replay.sh` (pins), de kaart.
