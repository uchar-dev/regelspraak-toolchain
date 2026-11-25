# settled-empty-input-in-service: `{"state": "empty"}` op een attribuut in een strikt verzoek

*Vervolgkaart voor `regelspraak-toolchain` na landing en pin-bump van `uchar`-kaart `20261122-settled-empty-input` (`8d7a98c9b`: `attributesEmpty` + scenariovorm `- de/het <attr> is leeg`).*

Goal: de beslisservice (JDK en Quarkus), de playground (beide routes), OpenAPI, `fixpoint`, `openInputs` en `service_replay.py` dragen uchars `attributesEmpty` door in strikte regelsets (`Afleiden uit leeg: nee`):
1. **Strikt verzoek (`OpenValues`, `RequestMapper`):** een scalair, niet-tijdsafhankelijk, niet-gedimensioneerd attribuut mag naast een concrete waarde (gevuld) en weglaten/`null` (open) ook `{"state": "empty"}` krijgen (vaststaand leeg → geschreven in `attributesEmpty` van de case). In een gewone regelset is `{"state": "empty"}` op een attribuut een 400 `WRONG_TYPE`, zoals elk onbekend object op de plaats van een waarde.
2. **Fixpunt (`FixpointGivens`, `playground.html`):** geeft een strikt verzoek of strikte situatie een attribuut als vaststaand leeg en overschrijft een regel het met een waarde, of laat een schrijver het open, dan toont het fixpunt die invoer als `"input": {"state": "empty"}` (spiegel van `"input": {"state": "holdsNot"}` uit `fixpoint-replaced-holdsnot`).
3. **Playground & replay (`ScenarioData`, `SituationToRequest`, `service_replay.py`, `OpenInputsPropertyTest`):** een situatie met `- de/het <attr> is leeg` (of een case met `attributesEmpty`) wordt op `?via=service` en in `service_replay.py` vertaald naar `{"state": "empty"}` (strikt) of weggelaten (gewoon), en door `ScenarioData` aan `FixpointGivens` doorgegeven.
4. **OpenAPI, README & `docs/ENGINE.md`:** beschrijven `{"state": "empty"}` op strikte verzoek-attributen en in `fixpoint`, terwijl gewone regelsets byte-voor-byte ongewijzigd blijven.

Acceptance:
- **Pin & basis:** `uchar`-pin bijgetrokken naar `8d7a98c9b` (`20261122-settled-empty-input`); `make check-pin` groen.
- **Rood eerst + HTTP-tests (`OpenValuesHttpTest`, `OpenValuesParityTest` voor JDK én Quarkus):**
  1. Strikt verzoek met `{"state": "empty"}` op een scalair invoerattribuut:
     - attribuut staat als `{"state": "empty"}` in `instances` (indien in `output`) en niet in `openInputs`;
     - hogere afwijking met `X is gevuld` valt vaststaand af zodat een lagere regel/variant beslist; voorwaarde `X is leeg` houdt stand; `explanation` en `explanationParts` verbaliseren de geldende beslissing zonder open lezing op `X`.
  2. Zelfde verzoek met attribuut weggelaten of `null`: blijft `open`, `openInputs` noemt het attribuut (geen regressie).
  3. Onjuiste standvormen op een strikt attribuut:
     - `{"state": "open"}`, `{"state": "holds"}`, `{"state": "holdsNot"}`, `{"state": "filled", "value": ...}` of een onbekende state → 400 `WRONG_TYPE` met duidelijke melding;
     - `{"state": "empty", "extra": 1}` of `{"state": 123}` → 400 `MALFORMED`;
     - `{"state": "empty"}` op een gedimensioneerd attribuut of tijdsafhankelijk attribuut → 400 `WRONG_TYPE` (niet stil doorgesluisd).
     *(Besluit eigenaar 2026-11-25: gevulde scalaire attribuutwaarden blijven hun platte JSON-waarde houden; `{"state": "filled", "value": ...}` wordt geweigerd met 400 `WRONG_TYPE`.)*
  4. Gewone regelset (zonder `Afleiden uit leeg: nee`): `{"state": "empty"}` op een attribuut is 400 `WRONG_TYPE`; alle bestaande antwoorden, `/info` en `/openapi` van gewone regelsets zijn byte-voor-byte identiek.
  5. **Fixpunt:**
     - strikt attribuut als `{"state": "empty"}` naast een vurende regel → `values`-rij heeft `"origin": "derived"` en `"input": {"state": "empty"}`;
     - strikt attribuut als `{"state": "empty"}` naast een open schrijver → `open`-rij heeft `"input": {"state": "empty"}`;
     - strikt attribuut als `{"state": "empty"}` dat vaststaand leeg blijft → geen rij in `values` of `open`;
     - gewone regelset met `- de X is leeg` in een scenario op de gewone playground-route → geen `"input"` op de afgeleide rij.
  6. **Playground (`PlaygroundOpenValuesTest`, beide routes: gewoon en `?via=service`):**
     - scenario met `- de X is leeg` op een strikte regelset geeft PASS op beide routes, `openInputs` meldt `X` niet, en `SituationToRequest` schrijft `{"state": "empty"}` (strikt) resp. laat weg (gewoon); in een gewone regelset toont de playground uchars waarschuwing (`RGS-473`) bij vervanging van een geërfde waarde;
     - `playground.html` toont `(invoer leeg)` bij `input.state === "empty"`, geslaagd met `node --check`.
  7. **OpenInputs (`OpenInputsHttpTest`, `OpenInputsPropertyTest`):**
     - een attribuut met `{"state": "empty"}` verschijnt niet in `openInputs`;
     - `OpenInputsPropertyTest` vertaalt `attributesEmpty` van een case naar `{"state": "empty"}` in het verzoek en houdt de invariant groen (inclusief `jutterswet-invoer-leeg-strikt`).
  8. **OpenAPI (`OpenValuesHttpTest`, `OpenApiEmitterTest`, `OpenApiVocabularyTest`, `OpenApiCommentTest`):**
     - strikte OpenAPI beschrijft `{"state": "empty"}` op scalaire attributen (en op `Fixpoint.values[].input` / `Fixpoint.open[].input`);
     - gewone OpenAPI-goldens blijven byte-voor-byte ongewijzigd.
- **`README.md` ("Contract change") en `docs/ENGINE.md`** ("A strict rule set": *The request*, *OpenAPI*, *fixpoint*, *The scenarios*) bijgewerkt (verwijder in `ENGINE.md` *"A request cannot state that an attribute is leeg: that stands only of an attribute that is `(volledig gegeven)`"*).
- **`make gates` 7/7 groen** (inclusief `make check-service-replay` met de nieuwe goldens en rode case uit `uchar` en `make check-twins`).

May touch:
- `uchar` (submodule-pin)
- `service/src/main/java/dev/uchar/regelspraak/toolchain/service/OpenValues.java`
- `service/src/main/java/dev/uchar/regelspraak/toolchain/service/RequestMapper.java`
- `service/src/main/java/dev/uchar/regelspraak/toolchain/service/DecisionCore.java`
- `service/src/main/java/dev/uchar/regelspraak/toolchain/service/FixpointGivens.java`
- `service/src/main/java/dev/uchar/regelspraak/toolchain/service/OpenApiEmitter.java`
- `service/src/main/java/dev/uchar/regelspraak/toolchain/service/http/ScenarioData.java`
- `service/src/main/java/dev/uchar/regelspraak/toolchain/service/http/SituationToRequest.java`
- `service/src/main/resources/dev/uchar/regelspraak/toolchain/service/http/playground.html`
- `service-quarkus/` (pariteitstests en voor zover pariteit/OpenAPI-wiring geraakt wordt)
- `scripts/service_replay.py`, `scripts/check-service-replay.sh`
- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/` (`OpenValuesHttpTest`, `OpenInputsHttpTest`, `OpenInputsPropertyTest`, `PlaygroundOpenValuesTest`, `OpenValuesSource`)
- `README.md`, `docs/ENGINE.md`
- deze kaart (`backlog/new/20261125-settled-empty-input-in-service.md` → `doing/` → `done/`)

Out of scope:
- `uchar`-semantiek (geland in `uchar`-kaart `20261122-settled-empty-input`, `8d7a98c9b`)
- `{"state": "filled", "value": ...}` toestaan op invoerattributen (gevulde waarden blijven gewone scalaire JSON-waarden; `{"state": "filled", ...}` wordt geweigerd met 400 `WRONG_TYPE`)
