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
