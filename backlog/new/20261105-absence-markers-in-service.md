# absence-markers-in-service: pin naar uchar d208bbfa5, en de markers onbekend en niet van toepassing in verzoek, antwoord en uitleg

*Opgesteld door de hoofdsessie op 2026-11-05, na uchar's kaart `absence-markers` (stap A `d9c218ce1`, stap B `994462df1`, register
`d208bbfa5`).*

## Aanleiding

Sinds uchar `absence-markers` kan een attribuut in RegelSpraak de markers `onbekend` en `niet van toepassing` dragen:
`(of onbekend)`, `(of niet van toepassing)` of `(of onbekend of niet van toepassing)` achter de declaratie (besluiten van de eigenaar,
2026-11-04, `uchar/spec/proposals/FLAVOURS-OF-EMPTY.md`). Een marker is een gevulde waarde: hij beslist zoals een getal (de invoer, de
stapel van schrijvers, EF-016, per dag op een tijdlijn); leeg blijft de enige afwezigheid. In de IR verlaagt zo'n attribuut naar een sum
type met de constructors `waarde` (het gedeclareerde datatype als payload), `onbekend` en `niet van toepassing`; een uchar-case schrijft
`{"constructor": "waarde", "payload": {"number": "40.00"}}` en `{"constructor": "onbekend"}`. Beide Drools-branches dragen het sinds stap B:
een constructorwaarde is een `ConstructorValue` op de bean, in een feit en in een segment, en `Period.value`, `Segment.getWaarde()` en
`PeriodDecisions.Span.input` zijn `Object`.

De dienst kent deze waarden nog niet: het verzoek heeft geen vorm voor een marker, het antwoord en de uitleg weten niet hoe ze een
constructorwaarde schrijven, en de OpenAPI beschrijft ze niet.

## Goal

- De pin naar uchar `d208bbfa5`.
- Het verzoek: op een attribuut met markers is een gewone waarde (getal, tekst, datum, of een tijdlijn daarvan) de waarde `waarde(…)`, en
  een marker `{"marker": "onbekend"}` of `{"marker": "niet van toepassing"}`, ook als `value` van een segment van een tijdlijn. Een marker
  die het attribuut niet declareert, of een marker op een attribuut zonder markers, is een fout met naam (400).
- Het antwoord schrijft een attribuut met markers in dezelfde vorm: de payload van `waarde` als gewone waarde, een marker als
  `{"marker": …}`.
- De uitleg noemt een marker bij zijn naam ("V1 heeft als taxatie onbekend volgens 'taxatie onbekend'"), ook per periode.
- De OpenAPI beschrijft de vorm per attribuut met markers (`oneOf` van het payloadtype en het markerobject met een `enum` van de
  gedeclareerde markers).

## Acceptance

- (a) Rood eerst: een verzoek met een marker op uchar's golden `jutterswet-afwezigheidsmarkers` geeft vóór de bouw een fout of een
  verkeerd antwoord, en daarna de pinnen van uchar; de replay van `check-service-replay.sh` speelt die golden (uit `REPLAY_EXCLUDED`,
  `EXPECTED_REPLAY_PLAYED` omhoog met het aantal goldens dat nu speelt).
- (b) De uitleg van de golden zonder "niet vast te stellen", met een test die eerst rood is.
- (c) Een test per foutsoort van het verzoek (onbekende marker, marker zonder markers).
- (d) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh` (lijst en pin), README, `docs/ENGINE.md`,
kaartverplaatsing. Een API die uchar mist, wordt een kaart in uchar, niet hier.

## Omvang

Middel.

## Result
