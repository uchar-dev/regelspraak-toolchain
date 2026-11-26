# open-to-one-roles-in-service: open en vaststaand lege `to-one`-rollen in de beslisservice

*Vervolg op `uchar`-kaart `20261126-open-to-one-roles.md` (op te pakken zodra die kaart op `uchar` `main` is geland).*

Goal: pin `uchar` met `open-to-one-roles` en draag open versus vaststaand lege `to-one`-rollen in een strikte regelset (`Afleiden uit leeg: nee`) over de volledige `regelspraak-toolchain` — zonder eigen semantiek: in HTTP-verzoeken (`JDK` en `Quarkus`), `?openInputs=true`, uitlegzinnen (`Verbaliser`), `/info`, OpenAPI (`/openapi`), `?fixpoint=true`, scenarioloper/playground en `check-service-replay`.

Acceptance:
- **Submodule-pin:** `uchar` gepind op de `main`-commit van `20261126-open-to-one-roles`.
- **HTTP-verzoek (`RequestMapper`, `OpenValues`, `DecisionCore`) & Quarkus-pariteit:**
  - In een strikte regelset ondersteunt `instances[].roles` op een niet-wederkerige `to-one`-rol:
    - `"<rol>": "<id>"` (of 1-element-array indien bestaand toegestaan: ongewijzigd gekoppeld);
    - `"<rol>": {"state": "empty"}` → vaststaand geen gekoppeld object (`rolesEmpty` / `Input.roleEmpty`);
    - weglaten of `"<rol>": null` → niet gegeven (*open*, tenzij de rol `(volledig gegeven)` draagt of vanuit de tegenrol / top-level `relations` gekoppeld is).
  - Weigeringen (`400 WRONG_TYPE` / `MALFORMED`, en `422 EVALUATION_FAULT` `EF-049`):
    - `{"state": "empty"}` in een gewone regelset, op een `to-many`-rol, op een wederkerig feittype, of in top-level `relations` → `400 WRONG_TYPE`;
    - andere standen zoals `{"state": "filled", ...}`, `{"state": "open"}`, lege/extra velden → `400 WRONG_TYPE` / `MALFORMED`;
    - tegelijk een koppeling vanuit de tegeninstantie of `relations` én `"<rol>": {"state": "empty"}` op dezelfde gerichte `to-one`-rol → `422 EVALUATION_FAULT` met code `EF-049` en uchars parameters (`label` vertaald naar `id`, `role`), analoog aan `EF-047` / `EF-048`.
  - Vocabulaires (`?inputVocabulary=...` en gemengde benamingen) werken ook voor `{"state": "empty"}` op een rol.
  - Gewone regelsets blijven in verzoek, antwoord, `/info`, uitleg en OpenAPI byte-voor-byte identiek.
- **`?openInputs=true` (`OpenReads`, `OpenInputs`) & `/info` (`InfoBuilder`):**
  - `OpenReads` volgt een open lezing op een `to-one`-rol (`open <object> <rol>`) naar die open invoerrol op dat object (en volgt netjes over al wél gekoppelde schakels `A -> B` naar open leden of rollen op `B`).
  - `OpenInputs` schrijft een open `to-one`-rol als `{"kind": "role", "instance": "<id>", "name": "<rolnaam in antwoordvocabulaire>", "forOutputs": [...]}` (in de volgorde van `instances` en declaraties). Een rol met `"state": "empty"` of `(volledig gegeven)` verschijnt nooit in `openInputs`.
  - `/info` vermeldt `to-one`-rollen met `(volledig gegeven)` onder `completeInput` van het entrypoint (met vocabulaireondersteuning waar van toepassing).
- **Uitleg (`Verbaliser`):**
  - Wanneer een voorwaarde of berekening openblijft op een open `to-one`-rol op `X`, verwoordt de uitleg dat zonder eigen herberekening uit `ConditionReading` (bijv. `"zijn <rol> is niet gegeven"` / met lidwoord van de rol, in dezelfde stijl als `"zijn <attribuut> is niet gegeven"`), inclusief gestructureerde `explanationParts`.
- **OpenAPI (`OpenApiEmitter`), `?fixpoint=true` (`FixpointGivens`), scenarioloper & playground:**
  - Strikte OpenAPI beschrijft `{"state": "empty"}` op `to-one`-rollen in het verzoek, `"role"` in `OpenInput.kind`, en de rolstanden in `Fixpoint`; gewone OpenAPI-goldens blijven byte-voor-byte ongewijzigd.
  - `?fixpoint=true` toont open en vaststaand lege `to-one`-rollen op objecten consistent met attributen en kenmerken.
  - `ScenarioData`, `SituationToRequest` en `playground.html` (geslaagd met `node --check`) ondersteunen `- de|het <rol> is leeg` op beide routes (gewoon en `?via=service`, plus waarschuwing in een gewoon document).
- **Replay & alle 7 poorten (`make gates`):**
  - `scripts/service_replay.py` en `scripts/check-service-replay.sh` vertalen `rolesEmpty` naar `{"state": "empty"}` bij een strikte regelset (en laten weg bij een gewone) en spelen de nieuwe goldens en de rode casus `EF-049` af.
  - Uitgebreide JUnit-tests (Jutterswet) in `service` en `service-quarkus` (inclusief `OpenInputsPropertyTest`); `README.md` en `docs/ENGINE.md` bijgewerkt; volledige `make gates` 7/7 GROEN.

May touch:
- `uchar` (submodule-pin)
- `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/` (`OpenReads.java`, `Verbaliser.java`, `Naming.java`)
- `service/src/main/java/dev/uchar/regelspraak/toolchain/service/` (`OpenValues.java`, `RequestMapper.java`, `DecisionCore.java`, `OpenInputs.java`, `InfoBuilder.java`, `FixpointGivens.java`, `OpenApiEmitter.java`, `http/ScenarioData.java`, `http/SituationToRequest.java`)
- `service/src/main/resources/dev/uchar/regelspraak/toolchain/service/http/playground.html`
- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/` en `toolchain/src/test/...`
- `service-quarkus/` (pariteitstests en OpenAPI-wiring voor zover geraakt)
- `scripts/service_replay.py`, `scripts/check-service-replay.sh`
- `README.md`, `docs/ENGINE.md`
- deze kaart (`backlog/new/20261126-open-to-one-roles-in-service.md` → `doing/` → `done/`)

Out of scope:
- `uchar`-semantiek (geland in `uchar`-kaart `20261126-open-to-one-roles.md`)
- `to-many`-rollen (`20261126-open-to-many-roles-in-service.md`)

## Result (2026-11-26)

Geland:
- `uchar` gepind op `f6ad5e87e` (`20261126-open-to-one-roles` + `20261126-fold-read-settled-range`).
- Strikte HTTP-verzoeken (`JDK` én `Quarkus`): niet-wederkerige `to-one`-rollen ondersteunen `"<rol>": {"state": "empty"}` (vaststaand leeg via `Input.roleEmpty`), en weglaten of `"<rol>": null` (open, tenzij `(volledig gegeven)` of gekoppeld vanuit de tegenrol / `relations`), inclusief vocabulairevertaling; botsing tussen tegenrol/`relations` en `{"state": "empty"}` geeft `422 EVALUATION_FAULT` `EF-049`; gewone regelsets blijven ongewijzigd (`null` en `{"state": "empty"}` geven `400`).
- `?openInputs=true` en `/info`: `OpenReads` en `OpenInputs` rapporteren open `to-one`-rollen als `{"kind": "role", "instance": "<id>", "name": "<rolnaam>", "forOutputs": [...]}`; `/info` vermeldt `to-one`-rollen met `(volledig gegeven)` onder `completeInput` (`kind: "role"`).
- Uitleg (`Verbaliser` + `explanationParts`): open `to-one`-rollen worden verwoord als `"zijn <rol> is niet gegeven"` (en bij navigatie `"van <X> is zijn <rol> niet gegeven"`), met volledige `explanationParts`-dekking inclusief onbepaalde `QuantifierFold` bij open vouwbereik.
- OpenAPI (`/openapi`), `?fixpoint=true` (`FixpointGivens`), `ScenarioData`, `SituationToRequest`, `playground.html` (`node --check` groen), `scripts/service_replay.py`, `scripts/check-service-replay.sh`, `README.md` en `docs/ENGINE.md` bijgewerkt.

Buiten scope gebleven:
- `to-many`-rollen (`backlog/new/20261126-open-to-many-roles-in-service.md`).

Verificatie (`make gates` 7/7 GROEN in 7m58s):
- `1 pin`: GROEN (1s, `uchar` op `f6ad5e87e`).
- `2 reactor`: GROEN (3m31s, testgevallen per module `216/671/71`).
- `3 twins`: GROEN (3s, 3 tweelingen, 18 cases + vocabulairetweeling 34 verzoeken + 4 getuigenissen).
- `4 replay`: GROEN (2m29s, `391` goldens, `1119` cases, `69` rode foutsoorten).
- `5 manifest`: GROEN (2s, 32 regels, 29 ok, 3 uitgesloten, 0 mismatch).
- `6 image`: GROEN (39s, `decision-service:dev`).
- `7 kind-smoke`: GROEN (1m18s, `H2 PASS, H3 PASS, H4 PASS`).
