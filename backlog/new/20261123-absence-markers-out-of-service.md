# absence-markers-out-of-service: de markers uit dienst, uitleg en playground

*Besluit van de eigenaar, 2026-11-23, op uchar's kaart `remove-absence-markers`: de markers `onbekend` en `niet van
toepassing` vervallen. Nog niet begonnen; volgt op de landing van die kaart in uchar, als pin-bump.*

## Wat vervalt

- **Verzoek:** `{"marker": "onbekend"}` en `{"marker": "niet van toepassing"}` als waarde van een attribuut, van een segment
  van zijn tijdlijn, van een cel en van `opgegeven X`; de foutsoort `UNKNOWN_MARKER` en de zin "heeft geen markers" van
  `WRONG_TYPE`.
- **Antwoord:** een attribuut met markers in de vorm van de marker, en in een strikte regelset `{"state": "marker", …}`.
  Wat overblijft: in een gewone regelset een waarde of `null`; in een strikte `filled`, `empty` en `open` voor een
  attribuut en `holds`, `holdsNot` en `open` voor een kenmerk.
- **Uitleg:** de marker als waarde, `marker` op het `VALUE`-deel (`explanationParts`), "een waarde heeft", en `onbekend` als
  woord van de uitleg.
- **OpenAPI:** de `oneOf` van datatype en markerobject met haar `enum`.
- **Scenario en playground:** de marker als waarde van een situatie en een verwachting, `?via=service` met een marker.
- **Tests en docs:** `AbsenceMarkerCellHttpTest`, `MarkerAsOpgaveHttpTest` en de markerdelen van de andere tests en van
  de openapi-goldens; de secties over markers in `README.md` en `docs/ENGINE.md`.

Het woord `MARKER` van `scenario` en `serve` (een ingang die uchar niet draagt) en de vlag `--allow-markers` blijven: dat is
een ander begrip.

## Acceptatie

- Geen absence marker meer in `service`, `service-quarkus`, `toolchain`, `tools`, `README.md` en `docs/`, behalve waar een
  tekst het besluit noemt.
- `make gates` groen op de nieuwe pin.
- De regelset die `niet van toepassing` gebruikt, is vóór deze pin-bump herschreven (uchar's kaart, "Volgorde").
- Geen `Co-Authored-By` of andere vermelding van een assistent in commits.
