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

## Result

Branch `card/strict-request-states`, op main (`68efdc3bf`, pin uchar `b9232544e`, ongewijzigd).

| commit | wat |
|---|---|
| `e083711cb` | de lezer, het OpenAPI-document, de schrijvers en de tests |
| `410d125bb` | README en `docs/ENGINE.md` |
| `b6290e72c` | het klassecommentaar van `OpenValues` |

### Wie een strikt verzoek las of schreef, en wat daarvan `true` was

| plaats | was | is |
|---|---|---|
| `OpenValues.characteristic` (de ene lezer, via `RequestMapper.setCharacteristics`, voor JDK en Quarkus) | nam `true` aan | elke boolean is `WRONG_TYPE` |
| `OpenApiEmitter`: kenmerk van een objecttype en `Request` | `oneOf` van een boolean en een stand; een vrij object | `CharacteristicInput` |
| `SituationToRequest` (playground `?via=service`) | `true` | `{"state": "holds"}` |
| `service_replay.py`, `request_body` | `True` | `{"state": "holds"}` |
| `OpenInputsPropertyTest.request` (case naar verzoek) en de verzoeken van de strikte tests | `true` | `{"state": "holds"}` |
| README, `docs/ENGINE.md`, javadoc van `Request`, `RequestMapper`, `OpenApiEmitter`, `OpenValues` | "`true` of een stand" | een stand |

`vocabulary_replay.py`, de tweelingen, de kind-smoke en `playground.html` schrijven geen strikt verzoek. `FixpointGivens` schreef al `{"state": "holdsNot"}`.

### Per punt

1. **Het verzoek.** `true` en `false` zijn een 400 `WRONG_TYPE` op het kenmerk: `kenmerk 'betwist' is true, en een regelset die uit leeg niets afleidt, vraagt een stand: {"state": "holds"} waar het geldt,
   {"state": "holdsNot"} waar vaststaat dat het niet geldt; waar het niet gegeven is, ontbreekt het of is het null`. Elke kale boolean van een verzoek is een eigen schending. `null`, weglaten, een andere stand (`WRONG_TYPE`) en
   een object met een andere vorm (`MALFORMED`) zijn als voorheen. Een attribuut van het datatype Booleaan blijft een boolean.
2. **OpenAPI.** De component `CharacteristicInput` (`holds`, `holdsNot`, `nullable`); de kenmerken van `Request` zijn een map naar die component, en een kenmerk van een objecttype verwijst ernaar met een `allOf`
   naast zijn eigen beschrijving.
3. **De schrijvers**: de tabel hierboven.

### Rood eerst

Op de code van main: `OpenValuesHttpTest` 3 rood (de kale boolean, elke kale boolean bij naam, het OpenAPI-document), `OpenValuesParityTest` 1 rood. Met alleen de lezer om: `PlaygroundOpenValuesTest` 1 rood
(FAIL "de dienst weigert het verzoek") en beide strikte goldens FAIL in `service_replay.py`.

### Tests

- `OpenValuesHttpTest`: `a_bare_boolean_is_refused_and_the_refusal_names_the_two_forms` (was `a_bare_false_…`, nu `true` en `false` met de zin letterlijk), nieuw `every_bare_boolean_of_a_request_is_named`,
  `the_openapi_document_describes_the_states` (de component, de twee verwijzingen, geen boolean).
- `OpenValuesParityTest`: `a_bare_boolean_is_the_same_400` (was `a_bare_false_…`), met veld en zin.
- `PlaygroundOpenValuesTest`: nieuw `a_characteristic_of_the_situation_holds_via_the_service` en `the_request_of_a_situation_gives_a_characteristic_as_its_state` (strikt een stand, gewoon `true`).
- Aangepast omdat het gedrag verandert: elk `true` van een kenmerk in de strikte verzoeken van `OpenValuesHttpTest`, `OpenExplanationHttpTest`, `OpenExplanationFormsHttpTest`, `OpenInputsPropertyTest` en
  `OpenValuesParityTest` is nu `{"state": "holds"}`; geen verwacht antwoord en geen zin van de uitleg veranderde.
- Alleen de signatuur (`SituationToRequest.assemble` neemt het model): `ConcurrencyTest`, `DetailConcurrencyTest`, `DetailHttpTest`, `GoldenOverHttpTest`, `GoldenOverQuarkusTest`, `PlaygroundAbsenceMarkersTest`,
  `PlaygroundViaServiceTest`.

### Voorlopige besluiten

1. Een eigen component `CharacteristicInput` en geen inperking van `CharacteristicState`: een verwijzing naar `CharacteristicState` kan zijn opsomming in OpenAPI 3.0 niet beperken, en die heeft `open`.
2. De kenmerken van `Request` zijn in het document van een strikte regelset een map naar `CharacteristicInput`, waar zij een vrij object waren: een generator leest het verzoek aan `Request` af, niet aan het objecttype.
   Een tijdsafhankelijk kenmerk verstoort dat niet: uchar draagt geen tijdlijn in een strikte regelset (DROOLS-370, `timeline-member`), dus elke ingang is dan een marker (nagemeten).
3. `SituationToRequest.assemble` krijgt het model als tweede parameter, zonder variant zonder model: die zou een strikte regelset stil als gewone behandelen.

### Vergelijking en poorten

Gewone regelsets, `compare.py` (elke body byte voor byte: antwoord, uitleg met stukken, trace, coverage, fixpoint, `/info`, OpenAPI, ook per vocabulaire), de jar van main tegen die van de code: goldens 385 mappen gelijk, 46
starten niet met dezelfde zin, 2 anders; rood 58 gelijk, 18 starten niet met dezelfde zin, 1 anders. De drie die anders zijn, zijn de strikte `jutterswet-afleiden-uit-leeg`, `jutterswet-volledig-gegeven` en
`jutterswet-kenmerk-wel-en-niet`, en zij verschillen alleen op `/openapi`. Geen body van een gewone regelset verschilt.

Op `b6290e72c`, elk los: `mvn -B -o verify -Dmaven.test.skip.exec=true` groen (212, 676 en 68 tests), `make check-service-replay` PASS (377 goldens, 1072 cases; rode leg 69 en 14), `make check-twins` PASS (18 cases, 34
verzoeken, 4 getuigenissen), `make scenario-test` 0 mismatch. Geen vloer, pin of lijst veranderde.

### Open punten

Geen in de code. De werkbanktekst van `open-values-in-service` (buiten deze repository) noemt `true` nog als toegestane vorm.

### Nagerekend door de hoofdsessie (2026-11-22)

De diff nagelezen. `make gates` op deze inhoud: 7 van 7 groen in 7m12s (gedraaid vóór de herschrijving van de geschiedenis; de boom is daarna gelijk op de pin en de geciteerde hashes na); `make check-pin` op de huidige pin groen.
