# fact-type-on-open-roles: feittype op open en volledig gegeven rollen in fixpoint, openInputs en /info

Goal: geef bij elke rolvermelding in `fixpoint.objects[].open[]`, `?openInputs=true` en `/info` (`completeInput`) het feittype (`factType`, en waar een vocabulaire gekozen is ook `factTypeCanonical` in het fixpunt) mee, zoals `situation.objects[].roles[]`, `fixpoint.links[]` en `explanationParts` (`owner`) dat al doen.

## Achtergrond

In RegelSpraak identificeert een rolnaam een roldeclaratie pas samen met zijn feittype: het paar `(feittype, rol)`. Verschillende feittypen kunnen dezelfde rolnaam dragen (in `v2/fundament` bijvoorbeeld `recht` in twaalf feittypen, waaronder het zelfverwijzende `Feittype eerdere recht`).

Sinds `open-to-one-roles-in-service` (`9c9dada52`):
- draagt een `NAME`-stuk voor een open rol in `?explanationParts=true` (`Verbaliser`) al `"declaration": "ROL"`, `"owner": "<feittype>"` en `"canonical": "<rol>"`;
- draagt `situation.objects[].roles[]` (`ScenarioData`) al `factType` (met eventueel `factTypeCanonical`);
- draagt `fixpoint.links[]` (`FixpointGivens`) al `factType` (met eventueel `factTypeCanonical`);
- maar `FixpointGivens.openOf()` (`fixpoint.objects[].open[]`) schrijft alleen `{"role": ..., "roleCanonical"?: ..., "input"?: ...}`, terwijl `FactType ft` daar al in scope staat;
- en `OpenInputs.json()` (`openInputs[]` met `kind: "role"`) schrijft alleen `{"kind": "role", "instance": ..., "name": ..., "forOutputs": [...]}` zonder `factType`, terwijl `i.input().factType()` daar al in scope staat;
- en `OpenValues.completeInput()` (`/info` `completeInput` met `kind: "role"`) schrijft alleen `{"kind": "role", "objectType": ..., "name": ...}` zonder `factType`, terwijl `FactType ft` daar al in scope staat.

Zonder `factType` kan een afnemer zoals `regelspraak-werkbank` een open rol in de objectboom, objectgraaf of open-invoerlijst niet eenduidig aan haar declaratie in `vocabularies.elements` koppelen (en geen naam-popover openen zonder zelf `ir.model.factTypes` na te rekenen).

## Acceptance

- **`?fixpoint=true` (`FixpointGivens.openOf`):**
  - Elke open rol in `fixpoint.objects[].open[]` draagt `factType` (in het gekozen vocabulaire, via `Naming.factType(ft.name)`), met `factTypeCanonical` zodra die van de canonieke feittypenaam verschilt (via dezelfde `name(rn, "factType", ...)`-hulpmethode als `fixpoint.links[]` en `ScenarioData`).
- **`?openInputs=true` (`OpenInputs.json`):**
  - Elke vermelding met `"kind": "role"` draagt `factType` (in het gekozen vocabulaire, via `Naming.factType(...)`).
- **`GET /<pad>/info` (`OpenValues.completeInput`):**
  - Elke vermelding met `"kind": "role"` draagt `factType` (canoniek, zoals `objectType` en `name` in `completeInput`).
- **Compatibiliteit en pariteit:**
  - Gewone regelsets (zonder `Afleiden uit leeg: nee`) blijven in verzoek, antwoord, uitleg, `/info`, `?fixpoint=true` en OpenAPI byte-voor-byte ongewijzigd.
  - OpenAPI-schema's (voor strikte regelsets) en `docs/ENGINE.md` documenteren het nieuwe veld; `playground.html` blijft werken.
  - Pariteit tussen JDK- en Quarkus-adapter en alle poorten (`make gates`) groen.

May touch:
- `service/` (`FixpointGivens.java`, `OpenInputs.java`, `OpenValues.java`, `OpenApiEmitter.java`, `playground.html` en tests)
- `service-quarkus/` (pariteitstests)
- `docs/ENGINE.md`, `README.md`
- deze kaart (`backlog/new/20261127-fact-type-on-open-roles.md` → `doing/` → `done/`)

Out of scope:
- `uchar`-semantiek (er verandert niets in `uchar/`: `FactType` en `OpenValue.factType()` bestaan al in de publieke API)
- wijzigingen aan `kind: "attribute"` of `kind: "characteristic"`

## Beslispunt bij oppakken

- **`factTypeCanonical` in `openInputs`?** `openInputs` gebruikt bij `attribute` en `characteristic` nu alleen `name` in het gekozen vocabulaire (geen `*Canonical`-sleutels), terwijl `fixpoint` wel consequent `<veld>` + `<veld>Canonical` gebruikt. Voorkeur: in `fixpoint.objects[].open[]` zowel `factType` als (bij afwijking) `factTypeCanonical`; in `openInputs` alleen `factType` in het antwoordvocabulaire (consistent met `name`), tenzij er een bredere behoefte aan `*Canonical` op `openInputs` is.
