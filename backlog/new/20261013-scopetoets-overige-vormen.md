# scopetoets-overige-vormen: de scopetoets bij het starten geldt ook voor objectcreaties, verdelingen, consistenties, uniciteiten en feitcreaties

*Vastgelegd op 2026-10-13, bij de uitvoering van het besluit van de eigenaar over TOKA (C6-b): de rest van
`examples/toka*` is een gewoon projectbestand, ook `examples/toka/toka.service.json`. Open punt 2 van de kaart
`20260930-dienst-scope-defaultsgroepen`. Welke weg de kaart neemt, beslist de eigenaar vóór de bouw (zie "Besluit
vooraf").*

## Aanleiding

Bij het starten toetst `DecisionCore.scopeCompleteness` per entrypoint de vormen in de scope: leest een vorm een
attribuut of kenmerk dat alleen vormen buiten de scope schrijven, dan weigert de dienst de start (`INCOMPLETE_SCOPE`),
tenzij het entrypoint dat element in `inputElements` noemt of `scopeCheckOptOut` zet. De toets geldt voor regels en
defaultsgroepen. Objectcreaties, verdelingen, consistenties, uniciteiten en feitcreaties slaat hij over
(`case OBJECT_CREATION, ALLOCATION, CONSISTENCY, UNIQUENESS, FACT_CREATION -> null`). Het commentaar daarbij geeft als reden
dat vijf entrypoints van `examples/toka/toka.service.json` een verdeling scopen zonder de schrijvers van wat zij leest.

Gemeten in die kaart (2026-10-01; niet opnieuw gemeten):
- Een consistentie in de scope leest `bergdag`, en alleen een regel buiten de scope schrijft `bergdag` (reproductie
  x01d). De dienst start zonder melding en meldt J1 en J2 inconsistent, waar de volle scope geen inconsistentie geeft.
- Een proef met de toets over alle soorten (niet gecommit): `examples/toka/toka.service.json` start niet meer, met 7
  schendingen in 5 entrypoints, alle zeven bij een verdeling, en zes testklassen van de dienst worden rood.

Open punt 2 (`backlog/done/20260930-dienst-scope-defaultsgroepen.md:366-371`, citaat): "die descriptor ligt in de
pariteitszone: een keuze van de eigenaar (`inputElements` daar, of een uitzondering voor verdelingen, of de toets
beperkt laten)." Sinds C6-b mag de descriptor veranderen. De eerste optie vraagt daardoor geen uitzondering op een
zoneregel meer.

## Besluit vooraf (eigenaar)

Een van drie:
1. De toets geldt voor alle soorten; de TOKA-descriptor noemt de gelezen elementen in `inputElements` van de
   entrypoints met een verdeling.
2. De toets geldt voor alle soorten behalve verdelingen.
3. De toets blijft bij regels en defaultsgroepen; `README.md` en `docs/ENGINE.md` zeggen welke soorten hij niet toetst
   en waarom.

## Wat er gebeurt

Bij 1 of 2 toetst `scopeCompleteness` de gekozen soorten onder dezelfde drie voorwaarden als een regel: er is een
schrijver, geen schrijver staat in de scope, en het element staat niet in `inputElements`. De schending noemt de lezer
met zijn soort en naam. Bij 1 krijgt `examples/toka/toka.service.json` de `inputElements` die de toets vraagt. Bij 3
veranderen alleen de docs.

## Acceptatie

- (a) Bij 1 of 2, rood eerst: een getuige met een consistentie in de scope die een element leest dat alleen buiten de
  scope geschreven wordt (de vorm van x01d). Eerst start de dienst; daarna weigert hij de start en noemt hij de lezer.
- (b) Bij 1: de TOKA-descriptor start, en de gouden TOKA-scenario's en de golden-tests van de dienst geven dezelfde
  uitkomsten als vóór de kaart.
- (c) De opt-outs `inputElements` en `scopeCheckOptOut` werken ook voor de nieuw getoetste soorten.
- (d) `make gates` groen.

## Omvang

Klein tot middel. May touch: `service/` (`DecisionCore`, `Violation`, de tests), `service-quarkus/` voor de pariteit,
`examples/toka/toka.service.json`, `README.md` en `docs/ENGINE.md`. Niet: `examples/toka/*.rgs` en `uchar/`.
