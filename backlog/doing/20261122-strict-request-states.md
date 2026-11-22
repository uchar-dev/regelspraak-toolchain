# strict-request-states: een kenmerk in een strikt verzoek is altijd een stand

*Besluit van de eigenaar op 2026-11-22, na een tweede lezing van het contract: ook een kale `true` weigeren. Er is nog geen strikte afnemer, dus het kost nu niets.*

## Aanleiding

In een strikte regelset (`Afleiden uit leeg: nee`) weigert de dienst een kale `false` op een kenmerk (400 `WRONG_TYPE`), maar neemt zij een kale `true` aan. Een client die een kenmerk als boolean verstuurt, test met `true`, slaagt, en loopt pas bij de eerste `false` vast. Het OpenAPI-document beschrijft het kenmerk bovendien als een mengsel van een boolean
(alleen `true`) en een object; veel generatoren maken daar een gewone boolean van.

## Goal

1. Het verzoek van een strikte regelset: een kenmerk is `{"state": "holds"}`, `{"state": "holdsNot"}`, weggelaten of `null`. Elke kale boolean (`true` en `false`) is een 400 `WRONG_TYPE`, met één zin die de twee vormen en weglaten noemt. Een gewone regelset verandert niet.
2. OpenAPI van een strikte regelset: een kenmerk in het verzoek is een stand (de component `CharacteristicState` van het antwoord, beperkt tot `holds` en `holdsNot`, of een eigen component als dat klaarder is), zonder boolean. Dat van een gewone regelset is byte-gelijk.
3. Wie zelf een strikt verzoek schrijft, schrijft standen: de playground (`SituationToRequest`, `?via=service`), `scripts/service_replay.py`, de tests, de documentatie (`docs/ENGINE.md`, README).

## Acceptance

- Rood eerst: een strikt verzoek met `true` is 400 `WRONG_TYPE` op het kenmerk (JDK en Quarkus), met de zin; met `{"state": "holds"}` speelt het; `false` idem als nu.
- Een gewone regelset: elk antwoord, elke uitleg, `/info` en OpenAPI byte-gelijk (`compare.py`, zie `werk/open-values-in-service/`, met een verse jar van `main` als basis).
- `make check-service-replay` speelt de strikte goldens (377 goldens, 1072 cases ongewijzigd), `make check-twins`, `make scenario-test`, de reactor groen.

## May touch

`service/`, `service-quarkus/`, `toolchain/` (alleen waar een strikt verzoek geschreven of gelezen wordt), `scripts/service_replay.py`, `docs/ENGINE.md`, `README.md`.
