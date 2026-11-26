# open-to-many-roles-in-service: open en gesloten `0..n`-collecties in de beslisservice (geparkeerd)

*Geparkeerd vervolg op `uchar`-kaart `20261126-open-to-many-roles.md`. Pas oppakken nadat die kaart in `uchar` op `main` is geland.*

Goal: pin `uchar` met `open-to-many-roles` en ondersteun open versus gesloten `0..n`-collecties (`to-many`-rollen) in een strikte regelset (`Afleiden uit leeg: nee`) over de hele `regelspraak-toolchain` — zonder eigen semantiek: in HTTP-verzoeken (`JDK` en `Quarkus`), `?openInputs=true`, uitlegzinnen (`Verbaliser`), `/info`, OpenAPI (`/openapi`), `?fixpoint=true`, scenarioloper/playground en `check-service-replay`.

Acceptance (bij oppakken aanscherpen):
- **HTTP-verzoek (`RequestMapper`, `OpenValues`):**
  - In een strikte regelset ondersteunt `instances[].roles` op een `to-many`-rol zowel een open collectie (bekende elementen, er kunnen nog elementen ontbreken) als een expliciet gesloten collectie (inclusief vaststaand leeg `[]`, vertaald naar `rolesClosed` in de case).
  - In een gewone regelset blijft het verzoekformaat byte-voor-byte ongewijzigd en wordt de strikte sluitingsvorm met `400 WRONG_TYPE` geweigerd.
- **`?openInputs=true` (`OpenReads`, `OpenInputs`) & `/info`:**
  - Een open uitkomst die rust op een open `to-many`-rol rapporteert die rol in `openInputs` (met vocabulaire-ondersteuning via `Naming` en `forOutputs`).
  - `/info` vermeldt `to-many`-rollen met `(volledig gegeven)` onder `completeInput`.
- **Uitleg (`Verbaliser`):**
  - Verwoordt open kwantoren, waardevouwen en roltoetsen die openstaan doordat een `to-many`-rol op het onderwerp nog niet volledig gegeven is, op basis van uchars `ConditionReading` zonder eigen herberekening.
- **OpenAPI, `fixpoint`, playground & replay:**
  - OpenAPI documenteert het strikte collectieschema op `to-many`-rollen; gewone OpenAPI-goldens blijven byte-identiek.
  - `?fixpoint=true`, `SituationToRequest`, `ScenarioData`, `playground.html` en `scripts/service_replay.py` dragen gesloten tegenover open `to-many`-rollen; pariteit tussen JDK- en Quarkus-adapter en alle 7 poorten (`make gates`) groen.

May touch:
- `uchar` (submodule-pin)
- `toolchain/` (`OpenReads.java`, `Verbaliser.java`, `Naming.java`)
- `service/` (`OpenValues.java`, `RequestMapper.java`, `OpenInputs.java`, `InfoBuilder.java`, `FixpointGivens.java`, `OpenApiEmitter.java`, `ScenarioData.java`, `SituationToRequest.java`, `playground.html` en tests)
- `service-quarkus/` (pariteitstests)
- `scripts/service_replay.py`, `scripts/check-service-replay.sh`
- `README.md`, `docs/ENGINE.md`
- deze kaart (`backlog/new/20261126-open-to-many-roles-in-service.md` → `doing/` → `done/`)

Out of scope:
- `uchar`-semantiek (hoort in `uchar`-kaart `20261126-open-to-many-roles.md`)
- gedeeltelijke/gefilterde collectiesluiting

## Beslispunt bij ontparkeren

- **Wire-vorm van `instances[].roles["<rol-mv>"]` in een strikte regelset:**
  - *Optie A:* kale JSON-array `["V1", "V2"]` betekent open ondergrens, weglaten/`null` betekent open met nul bekende elementen, en `{"state": "closed", "items": ["V1", "V2"]}` (of `{"state": "empty"}` als verkorting voor `items: []`) betekent gesloten. Let op: dit verandert bestaande strikte aanroepen die nu `["V1"]` sturen en stil op geslotenheid rekenden.
  - *Optie B:* expliciet object voor beide standen (`{"state": "open", "items": [...]}` tegenover `{"state": "closed", "items": [...]}`), analoog aan strikte kenmerken waar een kale boolean geweigerd wordt zodat een afnemer nooit ongemerkt open en gesloten verwisselt.
