# dienst-replay-per-case: de replay door de dienst sluit een case uit, niet de hele golden

*Vastgelegd door de hoofdsessie bij de kaart `20261010-sessie-via-de-ingang-van-uchar` (2026-10-12), uit de eindlens.
Het gedrag bestond al vóór die kaart.*

## Aanleiding

`scripts/check-service-replay.sh` speelt elke golden die uchars gate 12 speelt door de dienst. Een golden die de dienst
niet kan spelen, staat met een reden in `REPLAY_EXCLUDED`: 28 op uchar `b536bbc9`, met 222 spelers.
`service_replay.py` (rond r. 301) bouwt alle verzoeken van een golden vooraf. Eén case met een tijdlijn of een kenmerk met
perioden, die de dienst weigert (`UNSUPPORTED`), sluit zo de hele golden uit.

Volgens de eindlens spelen daardoor 9 cases zonder zo'n vorm niet door de dienst, onder meer
`jutterswet-gehele-periode-leeg` case1 en `jutterswet-ploegpremie` case4 en case7. Dat is nog niet nagemeten.

## Wat er gebeurt

De replay sluit per case uit. Een golden telt als gespeeld als elke case die de dienst kan dragen speelt; de cases die
hij niet draagt, staan per case met hun reden in de lijst. De pin telt cases naast goldens.

## Acceptatie

- (a) Meet eerst hoeveel cases erbij komen. Elke case die dan speelt, geeft uchars verwachtingen.
- (b) De lijst faalt als hij in een van beide richtingen verouderd is, zoals nu.
- (c) `make poorten` groen.

## Omvang

Klein. May touch: `scripts/check-service-replay.sh`, `scripts/service_replay.py`, en de README-sectie over de gates.
