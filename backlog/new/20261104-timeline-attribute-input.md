# timeline-attribute-input: een tijdsafhankelijk attribuut als invoer van de dienst, en de uitleg van een default per dag

*Opgesteld door de hoofdsessie op 2026-11-04, na uchar's kaarten `writers-per-day-beside-input`, `empty-write-decides-nothing` en
`defaults-per-period-on-timeline` (uchar `c3c750103`).*

## Aanleiding

De dienst weigert een tijdsafhankelijk attribuut als invoer (`RequestMapper`, `UNSUPPORTED`; `OpenApiEmitter` geeft er geen property
voor), en een scalaire waarde erop ook. In uchar is invoer op een tijdlijn sinds 2026-11-04 volwaardig: elke schrijver beslist alleen zijn
eigen dagen naast de invoer, en met `(mag opgegeven worden)` op een tijdsafhankelijk attribuut gaat een opgave per periode voor de
berekening (een defaults-groep die per dag beslist, RUNTIME-SEMANTICS ch. 7.11). Een vastgesteld dagloon of een correctie van een
behandelaar voor een periode kan de werkbank zo pas via de dienst gebruiken als de dienst die invoer neemt. In de replay van
`check-service-replay.sh` staan 41 goldens met de reden "tijdlijn als invoerwaarde" of "kenmerk met perioden".

Daarnaast weigert uchar's `decisionsOf` (de beslissers per object, die `Verbaliser`, `ScenarioRunner` en `CaseSession` lezen) een build met
een defaults-groep op een tijdlijndoel, omdat het één beslisser per object geeft; de uitleg van zo'n groep faalt dan.

## Goal

- Het verzoek draagt een tijdsafhankelijk attribuut als tijdlijn, in de vorm van uchar's case (`{"timeline": [{"from", "to", "value"}]}`,
  `from` of `to` weggelaten voor een open grens), zoals een tijdsafhankelijke parameter al; de OpenAPI beschrijft het. Een scalaire waarde
  op zo'n attribuut blijft een fout met naam (400).
- De uitleg van een default per dag: uchar levert de beslissers per periode (een kaart in uchar als de API dat nog niet heeft, eerst
  daar), en `Verbaliser` noemt per periode welke default besliste; ook de dagen waarop de invoer bleef staan (open punt van pin
  `f6487587`).
- Een tijdsafhankelijk kenmerk met perioden als invoer: meten wat uchar draagt en, als het past, meenemen; anders een fout met naam.

## Acceptance

- (a) Rood eerst: een verzoek met een tijdlijn op een attribuut (een golden met `(mag opgegeven worden)` op een tijdlijn, uchar's
  `jutterswet-opgave-per-periode`, en een gewone schrijver naast invoer) geeft nu `UNSUPPORTED` en daarna de pinnen van uchar.
- (b) De replay: elke golden van `REPLAY_EXCLUDED` met de reden "tijdlijn als invoerwaarde" die nu speelt, gaat uit de lijst;
  `EXPECTED_REPLAY_PLAYED` stijgt met dat aantal; de rest houdt zijn reden.
- (c) De uitleg per periode met een test die eerst rood is.
- (d) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh` (lijst en pin), README, `docs/ENGINE.md`,
kaartverplaatsing. Een API die uchar mist, wordt een kaart in uchar, niet hier.

## Omvang

Middel.

## Result
