# open-to-many-roles-in-service: open en gesloten `0..n`-collecties in de beslisservice

*Vervolg op `uchar`-kaarten `20261126-open-to-many-roles.md` en `20261126-role-and-existence-expectations.md` (samen gepind op `uchar` `c4c2e70c5`).*

Goal: pin `uchar` op `c4c2e70c5` en ondersteun open versus gesloten `0..n`-collecties (niet-wederkerige `to-many`-rollen) in een strikte regelset (`Afleiden uit leeg: nee`) over de hele `regelspraak-toolchain` — zonder eigen semantiek: in HTTP-verzoeken (`JDK` en `Quarkus`), `?openInputs=true`, uitlegzinnen (`Verbaliser`), `/info`, OpenAPI (`/openapi`), `?fixpoint=true`, scenarioloper/playground en `check-service-replay`.

## Besloten wire-vorm (Optie B — streng expliciet)

Op een niet-wederkerige `to-many`-rol die **niet** `(volledig gegeven)` is in een strikt verzoek (`Afleiden uit leeg: nee`):
- Weglaten of `null`: de collectie blijft **open** (met de eventueel vanaf de tegenkant of `relations` gekoppelde elementen als $S_{\text{known}}$).
- `{"state": "open", "items": [...]}`: open collectie met de opgegeven doel-ids (geschreven onder `roles` van de case, niet onder `rolesClosed`).
- `{"state": "closed", "items": [...]}`: gesloten collectie met de opgegeven doel-ids (geschreven onder `roles` waar niet leeg én onder `rolesClosed`).
- `{"state": "empty"}` (zonder `items`): verkorting voor `{"state": "closed", "items": []}` (geschreven onder `rolesClosed`).
- Een **kale JSON-array** (`[]` of `["V1"]`) of kale doel-id op zo'n niet-`(volledig gegeven)` `to-many`-rol in een strikt verzoek wordt geweigerd met **`400 WRONG_TYPE`** (analoog aan kale booleans op strikte kenmerken, zodat `"rol": []` nooit stil als open gelezen wordt). Ongeldige rolstanden zijn `400 WRONG_TYPE`, vormfouten in het standobject (`state` geen tekst, ontbrekende/niet-tekstuele `items` bij `open`/`closed`, of extra sleutels) zijn `400 MALFORMED`.
- Op een **`(volledig gegeven)`** niet-wederkerige `to-many`-rol blijft een kale array `[...]` (en een enkel doel-id) geaccepteerd als gesloten; daarnaast zijn `{"state": "closed", "items": [...]}` en `{"state": "empty"}` toegestaan, terwijl `{"state": "open", ...}` met `400 WRONG_TYPE` geweigerd wordt (een volledig gegeven rol kan niet openstaan).
- Op een wederkerige rol, in `relations[].roles`, en in een gewone regelset (`Afleiden uit leeg: nee` ontbreekt) blijft elk `state`-object een `400 WRONG_TYPE`; gewone verzoeken, antwoorden en OpenAPI-goldens blijven byte-voor-byte ongewijzigd.

Acceptance:
- **HTTP-verzoek (`RequestMapper`, `OpenValues`):**
  - Implementeert het bovenstaande Optie B-contract met naamresolutie via `NameReader` (enkelvoud, meervoud en vocabulaires, inclusief `?inputVocabulary=`) en ondubbelzinnige `uchar-case-v0`-sleutelbewaking (`400 UNSUPPORTED` waar noch naam noch meervoud uniek naar één tegenrol oplost).
- **`?openInputs=true` (`OpenReads`, `OpenInputs`) & `/info`:**
  - `OpenReads` indexeert ook open niet-wederkerige `to-many`-rollen uit `standing.openLinks()`; `openInputs` rapporteert zo'n open `to-many`-rol met `"kind": "role"`, `"instance"`, `"factType"`, `"name"` en `"forOutputs"` (met vocabulaire-ondersteuning via `Naming`).
  - `/info` vermeldt niet-wederkerige `to-many`-invoerrollen met `(volledig gegeven)` (die niet door een creatie geschreven worden) onder `completeInput` met `"kind": "role"`, `"objectType"`, `"factType"` en `"name"`.
- **Uitleg (`Verbaliser`):**
  - Verwoordt open kwantoren (zowel zonder als met bekende bijdragers naast een open `to-many`-bereik), waardevouwen en roltoetsen die openstaan doordat een `to-many`-rol nog niet volledig gegeven is (`"zijn <rol> is niet volledig gegeven"` / `"van <id> is zijn <rol> niet volledig gegeven"`), uitsluitend op basis van uchars `ConditionReading` zonder eigen herberekening.
- **OpenAPI, `fixpoint`, playground & replay:**
  - OpenAPI documenteert het strikte collectieschema (`CollectionRoleInput` naast `EmptyRoleInput`) op `instances[].roles`; gewone OpenAPI-goldens blijven byte-identiek.
  - `?fixpoint=true` (`FixpointGivens.openOf`) toont ook open niet-wederkerige `to-many`-rollen onder `objects[].open[]` met `role` en `factType` (plus `*Canonical` bij afwijkend vocabulaire).
  - `SituationToRequest`, `ScenarioData`, `playground.html` en `scripts/service_replay.py` dragen open en gesloten `to-many`-rollen (`rolesClosed`); `jutterswet-invoer-collectie` en `jutterswet-invoer-collectie-strikt` spelen in `check-service-replay.sh` (terwijl de negen cases met rolverwachtingen van `jutterswet-rolverwachting*` met reden in `REPLAY_EXCLUDED` staan zolang `role-and-existence-expectations-in-service` geparkeerd is).
  - Pariteit tussen JDK- en Quarkus-adapter en alle 7 poorten (`make gates`) groen.

May touch:
- `uchar` (submodule-pin naar `c4c2e70c5`)
- `toolchain/` (`OpenReads.java`, `Verbaliser.java`, `Naming.java` en tests)
- `service/` (`OpenValues.java`, `RequestMapper.java`, `OpenInputs.java`, `FixpointGivens.java`, `OpenApiEmitter.java`, `ScenarioData.java`, `SituationToRequest.java`, `playground.html` en tests)
- `service-quarkus/` (pariteitstests)
- `scripts/service_replay.py`, `scripts/check-service-replay.sh`
- `README.md`, `docs/ENGINE.md`
- deze kaart (`backlog/new/20261126-open-to-many-roles-in-service.md` → `doing/` → `done/`)

Out of scope:
- `uchar`-semantiek (geland in `uchar`-kaart `20261126-open-to-many-roles.md`)
- gedeeltelijke/gefilterde collectiesluiting
- rollen of gemaakte instanties toevoegen aan `AnswerBuilder` (geparkeerde kaart `20261126-role-and-existence-expectations-in-service.md`)

## Result (2026-11-27)

Geland:
- `uchar`-submodule gepind op `c4c2e70c5ba5ba45bbbb057e2c84d2aac46785b9` (draagt `open-to-many-roles` én `role-and-existence-expectations` mee in één pin-bump).
- HTTP-verzoek (`OpenValues`, `RequestMapper`): Optie B-contract op niet-wederkerige `to-many`-rollen in strikte regelsets (`Afleiden uit leeg: nee`) — weglaten/`null` = open, `{"state": "open", "items": [...]}` = open met bekende doel-ids (`roles`), `{"state": "closed", "items": [...]}` = gesloten (`roles` + `rolesClosed`), `{"state": "empty"}` = verkorting voor gesloten + `[]` (`rolesClosed`), kale array/doel-id op niet-`(volledig gegeven)` `to-many`-rol = `400 WRONG_TYPE`; op `(volledig gegeven)` `to-many`-rollen blijven kale array/doel-id, `{"state": "closed", ...}` en `{"state": "empty"}` gesloten terwijl `{"state": "open", ...}` `400 WRONG_TYPE` geeft; ondubbelzinnige `uchar-case-v0`-sleutelbewaking (`400 UNSUPPORTED`) en volledige enkelvoud/meervoud/vocabulaire-resolutie via `NameReader`.
- `?openInputs=true` (`OpenReads`, `OpenInputs`) & `/info` (`OpenValues.completeInput`): open niet-wederkerige `to-many`-rollen verschijnen in `openInputs` met `"kind": "role"`, `"instance"`, `"factType"`, `"name"` en `"forOutputs"`; `/info` vermeldt niet-wederkerige `to-many`-invoerrollen met `(volledig gegeven)` onder `completeInput` met `"kind": "role"`, `"objectType"`, `"factType"` en `"name"`.
- Uitleg (`Verbaliser`) & `?fixpoint=true` (`FixpointGivens.openOf`): open kwantoren (zowel zonder als met bekende bijdragers naast een open `to-many`-bereik), waardevouwen en roltoetsen worden verwoord vanuit uchars `ConditionReading` met oorzaak `"zijn <rol> is niet volledig gegeven"` / `"van <id> is zijn <rol> niet volledig gegeven"` bij open `to-many`-rollen; `fixpoint.objects[].open[]` toont ook open niet-wederkerige `to-many`-rollen met `role` en `factType` (plus `*Canonical` bij afwijkend vocabulaire).
- OpenAPI (`OpenApiEmitter`), scenarioloper/playground (`SituationToRequest`, `ScenarioData`, `playground.html`), replay (`scripts/service_replay.py`, `scripts/check-service-replay.sh`), documentatie (`docs/ENGINE.md`, `README.md`) en JDK/Quarkus-tests bijgewerkt; `jutterswet-invoer-collectie` en `jutterswet-invoer-collectie-strikt` spelen in replay (`EXPECTED_REPLAY_PLAYED=393`, `EXPECTED_REPLAY_CASES=1130`), negen cases met rolverwachtingen van `jutterswet-rolverwachting*` staan gedocumenteerd in `REPLAY_EXCLUDED` in afwachting van `role-and-existence-expectations-in-service`, en gewone verzoeken/antwoorden/OpenAPI-goldens zijn byte-voor-byte ongewijzigd.

Verificatie (`make gates` 7/7 GROEN in 7m37s):
- `1 pin`: GROEN (0s, `uchar` op `c4c2e70c5`).
- `2 reactor`: GROEN (3m14s, testgevallen per module `216/677/71`).
- `3 twins`: GROEN (4s, 3 tweelingen, 18 cases + vocabulairetweeling 34 verzoeken + 4 getuigenissen).
- `4 replay`: GROEN (2m24s, `393` goldens, `1130` cases, `6580` uitlegzinnen, `0` met `"niet is vast te stellen"`, `69` rode foutsoorten).
- `5 manifest`: GROEN (2s, 32 regels, 29 ok, 3 uitgesloten, 0 mismatch).
- `6 image`: GROEN (41s, `decision-service:dev`).
- `7 kind-smoke`: GROEN (1m18s, `H2 PASS, H3 PASS, H4 PASS`).
