# one-empty-in-service: geen markers en geen sluitingen in dienst, uitleg en playground

*Volgt op uchar's kaart `one-empty` (twee delen). Deel 1 is een besluit van de eigenaar (2026-11-23): de markers `onbekend`
en `niet van toepassing` vervallen. Deel 2 volgt op uchar's deel 2 (een strikt document op zuivere Kleene), als dat besloten
wordt. Nog niet begonnen; elk deel landt als pin-bump op het deel van uchar dat het volgt. Bijgewerkt na een tweede review
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
- De regelset die `niet van toepassing` gebruikt, is vóór deze pin-bump herschreven (uchar's kaart, "Volgorde").

## Deel 2 — geen sluitingen in een strikte regelset

- **Uitleg:** de zinnen voor een uitkomst die door een sluiting vaststaat (`docs/ENGINE.md`, "A strict rule set": "wordt
  zijn taxatie gegeven, dan kan dit veranderen", "komt zijn vindloon vast te staan, …", "omdat zijn taxatie niet gegeven is")
  en de initialisatie onder een open default vervallen; een toets op leeg of gevuld over een open lid heet onbepaald, zoals
  elke andere atomaire voorwaarde. De tests die deze zinnen vastpinnen (`OpenExplanationFormsHttpTest` en verwante) volgen.
- **Scenario en playground:** met uchar's kaart `strict-scenarios` (zelfde pin): `characteristicsAbsent` als
  `{"state": "holdsNot"}`, en `attributeOpen` en `characteristicOpen` als verwachting.

## Voor beide delen

- `make gates` groen op de nieuwe pin.
- Geen `Co-Authored-By` of andere vermelding van een assistent in commits.
