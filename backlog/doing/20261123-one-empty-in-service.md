# one-empty-in-service: geen markers en geen sluitingen in dienst, uitleg en playground

Goal: de pin naar uchar `e25f201ef` (de kaarten `strict-scenarios` en `one-empty`, beide delen) in één pin-bump. Dienst,
uitleg, OpenAPI, scenario en playground dragen geen absence markers meer (een markerobject in een verzoek is een gewone
400 `WRONG_TYPE`); in een strikte regelset geen uitkomst door een sluiting meer in de uitleg, geen melding van een
consistentie "moet gevuld zijn" over een open lid, geen bovengrens van `openInputs` voor een sluiting in een vouw, en een
lid met alleen een initialisatie als invoerlid in `openInputs`. De scenariolaag leest `holds()` van een kenmerk
(`characteristicsAbsent` als `{"state": "holdsNot"}`), `attributeOpen` en `characteristicOpen` als verwachting, en toont de
waarschuwingen van `ScenarioBundle.warnings` (RGS-469) naast de diagnostiek van het model.

Acceptance: `make check-pin` op `e25f201ef`; `make gates-no-docker` groen (check-pin, de reactor met de suites van
`toolchain`, `service` en `service-quarkus` en hun skip-pins, `check-twins`, `check-service-replay` met de vloeren die met
de goldens van uchar meebewegen, oud en nieuw in de commitboodschap, en `scenario-test`); `make gates` groen (de
hoofdsessie, vóór de landing); `git grep -i -E 'absence.?marker|UNKNOWN_MARKER|onbekend|niet van toepassing|Markers\b'`
in `service`, `service-quarkus`, `toolchain`, `tools`, `scripts`, `README.md` en `docs/` vindt niets behalve waar een
tekst het besluit noemt, en het `MARKER` van `scenario`/`serve` en `--allow-markers` (een ander begrip).

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh`, `scripts/service_replay.py`,
`tools/check-skips.sh` (de vloeren van de suites), `README.md`, `docs/ENGINE.md`, de submodule `uchar` (pin), deze kaart.

Out of scope: uchar zelf (nooit bewerkt; een vraag over betekenis is een kaart daar); de werkbank en haar regelset (zij
pint de toolchain zelf en zet om bij haar eigen bump); de website; het `MARKER` van `scenario` en `serve` en de vlag
`--allow-markers`; `docs/historical/`.

*Volgt op uchar's kaart `one-empty` (twee delen). Deel 1 is een besluit van de eigenaar (2026-11-23): de markers `onbekend`
en `niet van toepassing` vervallen. Deel 2 volgt op uchar's deel 2 (een strikt document op zuivere Kleene), ook besloten op
2026-11-23. Nog niet begonnen; beide delen en uchar's `strict-scenarios` landen in één pin-bump. Bijgewerkt na een tweede review
(extern model, 2026-11-23).*

## Deel 1 — de markers

### Wat vervalt

- **Verzoek:** `{"marker": "onbekend"}` en `{"marker": "niet van toepassing"}` als waarde van een attribuut, van een segment
  van zijn tijdlijn, van een cel en van `opgegeven X` (`RequestMapper`, `TimelineInput`); de foutsoort `UNKNOWN_MARKER` en de
  zin "heeft geen markers" van `WRONG_TYPE` (`Violation`). Een verzoek met een markerobject wordt een gewone 400
  `WRONG_TYPE`: een wijziging van het contract die de README noemt.
- **Antwoord en detail:** een attribuut met markers in het antwoord en in de `fixpoint` (`AnswerBuilder`, `FixpointGivens`),
  in een strikte regelset `{"state": "marker", …}`, en de markers in `openInputs` (`OpenValues`, `OpenValuesSource`).
  Wat overblijft: in een gewone regelset een waarde of `null`; in een strikte `filled`, `empty` en `open` voor een attribuut
  en `holds`, `holdsNot` en `open` voor een kenmerk.
- **Start:** de uitzondering in `DescriptorLoader` voor een attribuut met markers; een sum type in `output` weigert de start
  dan altijd (`UNSUPPORTED`). `Markers` vervalt.
- **Uitleg:** de marker als waarde (`Verbaliser`), `marker` op het `VALUE`-deel (`Part`, `explanationParts`), "een waarde
  heeft", en `onbekend` als woord van de uitleg.
- **OpenAPI:** de `oneOf` van datatype en markerobject met haar `enum`, `UNKNOWN_MARKER` in het foutschema, `marker` op een
  deel van de uitleg en `{"state": "marker"}` (`OpenApiEmitter`); de zes openapi-goldens.
- **Scenario en playground:** de marker als waarde van een situatie en een verwachting (`ScenarioData`,
  `SituationToRequest`), `?via=service` met een marker, `playground.html`.
- **Tests, scripts en docs:** `AbsenceMarkerHttpTest`, `AbsenceMarkerCellHttpTest`, `MarkerAsOpgaveHttpTest`,
  `PlaygroundAbsenceMarkersTest`, `MarkersTest`, `OpenApiAbsenceMarkersTest`, `ExplanationMarkerPartsTest`, en de
  markerdelen van `OpenValuesHttpTest`, `OpenExplanationFormsHttpTest`, `PlaygroundViaServiceTest`, `ServiceHttpTest` en
  `TimelineStackHttpTest` (een tijdlijnstapel zonder marker blijft gedekt); `scripts/check-service-replay.sh` en
  `scripts/service_replay.py`, met de vloeren die met de goldens dalen; de secties over markers in `README.md` en
  `docs/ENGINE.md`, ook in "Empty is leeg" en "A strict rule set".

Het woord `MARKER` van `scenario` en `serve` (een ingang die uchar niet draagt) en de vlag `--allow-markers` blijven: dat is
een ander begrip.

### Acceptatie

- Geen absence marker meer in `service`, `service-quarkus`, `toolchain`, `tools`, `scripts`, `README.md` en `docs/`, behalve
  waar een tekst het besluit noemt.
- De werkbank pint de toolchain zelf en zet de regelset die `niet van toepassing` gebruikt om bij haar eigen bump (uchar's
  kaart, "Volgorde").

## Deel 2 — geen sluitingen in een strikte regelset

- **Uitleg:** de zinnen voor een uitkomst die door een sluiting vaststaat (`docs/ENGINE.md`, "A strict rule set": "wordt
  zijn taxatie gegeven, dan kan dit veranderen", "komt zijn vindloon vast te staan, …", "omdat zijn taxatie niet gegeven is",
  "A closure in the value of an outcome is named where it is certain …") en de initialisatie onder een open default
  vervallen; een toets op leeg of gevuld over een open lid heet onbepaald, zoals elke andere atomaire voorwaarde. Een
  consistentie "moet gevuld zijn" over een open lid meldt niets in `inconsistencies`; de uitleg zegt dat zij niet vaststaat.
- **`openInputs`:** de bovengrens voor een sluiting in een vouw (`OpenValues`, `OpenReads`; `docs/ENGINE.md`, "Of a closure
  inside a fold …") vervalt: een open lid in een vouw is nu gewoon een open lezing.
- **Scenario en playground:** met uchar's kaart `strict-scenarios` (zelfde pin): `characteristicsAbsent` als
  `{"state": "holdsNot"}`, en `attributeOpen` en `characteristicOpen` als verwachting (`ScenarioData`, `SituationToRequest`,
  `playground.html`); "The scenarios" in `docs/ENGINE.md` ("A test document has no sentence that expects an open outcome")
  en de README volgen.
- **Tests:** `OpenExplanationFormsHttpTest`, `OpenValuesHttpTest` en de verwante tests die deze zinnen en `openInputs`
  vastpinnen.

## Voor beide delen

- `make gates` groen op de nieuwe pin.
- Geen `Co-Authored-By` of andere vermelding van een assistent in commits.
