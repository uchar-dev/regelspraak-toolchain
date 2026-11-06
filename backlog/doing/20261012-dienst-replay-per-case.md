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
- (c) `make gates` groen.

## Omvang

Klein. May touch: `scripts/check-service-replay.sh`, `scripts/service_replay.py`, en de README-sectie over de gates.

## Result

*Gebouwd op 2026-11-06 in de branch `card/scope-check-and-cleanup`. Logs in `uchar-dev/werk/toolchain-cleanup/logs/`.*

`service_replay.py` sluit per case uit: een case-vorm die het verzoek niet uitdrukt (`request_body`), een 400 met
alleen `UNSUPPORTED` of een verwachte waardevorm die het antwoord niet uitdrukt, geeft een regel
`<golden>/<case>\tEXCLUDE\t<reden>`, en de andere cases spelen door. Een golden is `PLAYED` als elke case die de dienst
draagt speelt en minstens één; een golden waarop de dienst niet start (een onopgeloste naam) of zonder casebestand
blijft één `EXCLUDE`-regel. De descriptor van de replay leest alleen de verwachtingen van de cases met een verzoek.
`check-service-replay.sh` noemt in `REPLAY_EXCLUDED` cases (`<golden>/<case>|<reden>`) en goldens (`<golden>|<reden>`),
weigert een golden met een sleutel van zichzelf en een van een case, en pint naast `EXPECTED_REPLAY_PLAYED` het aantal
gespeelde cases (`EXPECTED_REPLAY_CASES`, een gelijkheid).

- (a) Gemeten (`logs/c5-measure-excluded.log`, de driver over de tien uitgesloten goldens): van de negen goldens met
  een scalaire waarde op een tijdsafhankelijk attribuut heeft alleen `jutterswet-vast-startpunt` meer dan één case;
  vijf van haar zeven cases spelen nu, met uchars verwachtingen en met uitleg (`PLAYED 5`), case5 en case7 blijven
  uitgesloten. De acht andere hebben de vorm in hun enige case. Erbij komen dus 1 golden en 5 cases. De negen cases
  uit de eindlens van 2026-10-12 (`jutterswet-gehele-periode-leeg` case1, `jutterswet-ploegpremie` case4 en case7, …)
  speelden al sinds de kaart timeline-attribute-input mee, met hun hele golden. De poort op de nieuwe stand
  (`logs/c5-replay-capture.log`): PASS, 347 goldens in de set, 338 gespeeld (pin 338, was 337), 968 cases (pin 968;
  zonder de kaart 963), uitgesloten 1 golden en 10 cases.
- (b) Op de bewaarde uitvoer van die run (`logs/c5-stale-probes.log`, een kopie van de poort die de uitvoer leest in
  plaats van de driver te draaien, daarna verwijderd): een ontbrekende case-uitsluiting faalt ("1 uitsluiting(en) …
  staan niet in REPLAY_EXCLUDED", na de proef herschreven tot deze zin), een case die speelt maar in de lijst staat
  faalt ("… wordt niet uitgesloten"), een golden buiten de set faalt, en een golden naast haar eigen case faalt. De
  tweelingen (`check-twins.sh`, dezelfde driver) blijven groen (`logs/c5-twins.log`).
- (c) `make gates`: zie het slot van deze Result.

Docs: `README.md` (de replay-gate, de pinnen bij een pin-bump, de scalaire waarde: 10 cases van 9 goldens) en
`CLAUDE.md` (de poortbeschrijving).
