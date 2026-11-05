# markers-in-test-documents: pin naar uchar 9c275fd8b, en een marker uit een toetsdocument in de playground en de dienst

*Opgesteld door de hoofdsessie op 2026-11-05, na uchar's kaart `absence-markers-residue` (uchar `fc347f1f3`, `9c275fd8b`).*

## Aanleiding

Sinds uchar `absence-markers-residue` schrijft een toetsdocument een marker als waarde ("de taxatie van V1 is onbekend"), in een situatie
en een verwachting; uchar's `ResolvedScenario.ScenarioValue` heeft daarvoor de componenten `constructor` en `payload`. Verder: de frontend
bouwt de knopen van het sum type nu in zijn model, een marker staat ook op een cel en in een objectcreatie, en WF-062 leest het argument
van een `Construct` als een toekenning.

Gemeten in uchar (Result van stap A van die kaart): `SituationToRequest.valueToJson` valt voor een constructorwaarde door naar
`NF.textNode(w.text())` met `w.text()` null, zonder fout, en `ScenarioData.value` gooit "uchars resolver gaf een waarde zonder variant".
Een toetsdocument met een marker gaat zo stil fout door de playground (`?via=service`) of faalt.

## Goal

- De pin naar uchar `9c275fd8b`.
- `SituationToRequest` en `ScenarioData` lezen een constructorwaarde: een marker wordt `{"marker": …}` in het verzoek, een waarde van een
  attribuut met markers de payload, ook in een cel en een segment; een vorm die de dienst niet kent, is een fout met naam, nooit stil.
- De playground speelt een toetsdocument met markers via de dienst, met dezelfde uitkomst als `--cases`.
- Een marker op een cel in verzoek, antwoord en uitleg, als uchar haar nu draagt.

## Acceptance

- (a) Rood eerst: het toetsdocument van uchar's golden `jutterswet-afwezigheidsmarkers` (`scenario.rgs`) via de playground of de dienst
  geeft vóór de bouw een fout of een verkeerde uitkomst, daarna de uitkomst van `--cases`.
- (b) De replay van `check-service-replay.sh` speelt de nieuwe goldens van uchar die de dienst kan dragen
  (`jutterswet-afwezigheidsmarkers-cel`, `-creatie`, `-kopie`), of noemt ze in `REPLAY_EXCLUDED` met hun reden.
- (c) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh` (lijst en pin), `scripts/service_replay.py`,
README, `docs/ENGINE.md`, kaartverplaatsing. Een API die uchar mist, wordt een vraag, geen wijziging in uchar.

## Omvang

Klein tot middel.

## Result

*Bouwer, 2026-11-05. Niet gemerged, niet gepusht. Logs onder `werk/pin-9c275fd8/logs/`, scripts (testrunner, mutaties,
de proef van de pagina en van `--cases`) onder `werk/pin-9c275fd8/scripts/` (buiten de repo).*

### Commits

| hash | inhoud |
|---|---|
| `b6f6ce23c` | kaart naar `doing/` |
| `8495f340e` | Pin uchar `9c275fd8b`; `EXPECTED_REPLAY_PLAYED` 331 → 335, `jutterswet-voorwaarde-buiten-de-beurt` in `REPLAY_EXCLUDED` |
| `4601caaa6` | `SituationToRequest` en `ScenarioData` lezen een constructorwaarde; `Markers` en `Markers.KEY` publiek; de pagina toont een marker bij naam; tests `PlaygroundAbsenceMarkersTest`, `AbsenceMarkerCellHttpTest` |
| `0ab3b68a7` | README en `docs/ENGINE.md` |
| (deze) | Result |
| (laatste) | kaart naar `done/` |

### Wat er veranderde

- **Pin** `d208bbfa5` → `9c275fd8b` (uchar's kaarten `reading-and-uniqueness-residue` en `absence-markers-residue`).
  De dienst compileert erop zonder wijziging; `ResolvedScenario.ScenarioValue` heeft `constructor` en `payload` erbij.
- **Verzoek** (`SituationToRequest.valueToJson`): een constructorwaarde zonder payload, een marker, wordt
  `{"marker": <naam>}`; `waarde` met payload wordt de payload in haar eigen vorm (getal, datum, tekst, …), ook als
  `value` van een segment en van een cel. De mapper houdt de marker tegen de declaratie van het attribuut, zoals bij
  een verzoek van buiten. Een constructor met een andere payload en een waarde zonder variant zijn een
  `IllegalStateException` met naam; vóór de kaart werd zo'n waarde stil `NF.textNode(null)`.
- **Situatie van de playground** (`ScenarioData.value`): dezelfde vorm onder `value` (een marker als
  `{"marker": "onbekend"}`, de waarde als haar payload, `"40.00"`), ook per segment en per cel, met dezelfde fout met
  naam voor een onbekende vorm; vóór de kaart gooide hij `uchars resolver gaf een waarde zonder variant` op elke
  constructorwaarde, dus elk toetsdocument met een marker was een 500 op beide routes. Het fixpunt gaf de marker al zo
  (`FixpointGivens`, vorige kaart), dus situatie en fixpunt vergelijken nu tussen de routes.
- **De pagina** (`playground.html`, `scalar`): een markerobject staat als zijn naam, gedempt zoals `leeg`, waar het
  `[object Object]` was, in de situatie en in het fixpunt.
- **Een marker op een cel** in verzoek, antwoord, fixpunt en uitleg: geen codewijziging nodig; `RequestMapper.cells`,
  `AnswerBuilder.cells`, `FixpointGivens.cellRows` en de verbaliser gingen al door de functies van een scalaire waarde
  (VB-7 van `absence-markers-in-service`). Sinds deze pin schrijft een regel een marker in een cel en toetst een
  voorwaarde haar, dus nu met getuigen (`AbsenceMarkerCellHttpTest`).
- **De uitkomst van `--cases`**: `bin/regelspraak cases` op de drie toetsdocumenten schrijft 10, 4 en 4 casebestanden,
  alle byte-gelijk aan die van de goldens (`logs/14-cases.log`); `PlaygroundAbsenceMarkersTest` speelt elk scenario op
  de gewone route (uchar's replay van die cases) en via de dienst PASS, met gelijke verwachtingen, situatie, fixpunt,
  uitleg en uitlegstukken.

### Rood eerst (letterlijk)

- De bestaande suite op de pin (`logs/04-reactor-pin.log`): `PlaygroundRoleFactTypeTest.every_scenario_golden:148 »
  IllegalState uchars resolver gaf een waarde zonder variant`, `Tests run: 461, Failures: 0, Errors: 1`.
- De nieuwe tests op de pin (`logs/07-red-tests.keep.log`): `PlaygroundAbsenceMarkersTest` 7 van 7 rood, onder meer
  `the_test_document_of_the_markers_golden_passes_on_both_routes:49->bothRoutesPass:74 » IllegalState uchars resolver
  gaf een waarde zonder variant` (ook `-cel` en `-creatie`), `a_marker_in_the_situation_is_a_marker_object_of_the_request:99
  expected: <{"marker":"onbekend"}> but was: <null>`, `a_marker_in_a_segment_of_the_situation_is_carried:134 expected:
  <{"timeline":[{"from":"2025-03-01","to":"2025-05-01","value":{"marker":"niet van toepassing"}}]}> but was:
  <{"timeline":[{"from":"2025-03-01","to":"2025-05-01","value":null}]}>`. De test van een onbekende vorm in zijn
  eindvorm tegen de twee klassen van de pin (`logs/13-mutations/M0-pin.log`): `a_value_of_no_known_form_is_refused_by_name:158
  Expected java.lang.IllegalStateException to be thrown, but nothing was thrown`.
- `AbsenceMarkerCellHttpTest` op de pin: 6 van 7 groen (de cel ging al door de functies van een scalaire waarde). De
  zevende was rood op mijn verwachting "omdat hij onleesbaar is"; de verbaliser schrijft een kenmerk van het onderwerp
  als "omdat het onderwerp onleesbaar is" (zoals in `ExplanationStatuteAnnotationServiceTest`), dus de verwachting
  volgt dat contract. Elke test van de klasse heeft hieronder een mutatie die hem rood maakt.
- De pagina op de pin (`logs/09-playground-page-pin.txt`, de functie `valueHtml` in node): `[object Object]` voor
  `{marker: "onbekend"}`, in een cel en in een segment; na de bouw `<span class="muted">onbekend</span>`.

### Mutaties (niet gecommit; `logs/12-mutations.log`, `logs/13-mutations.log`)

| mutatie | rood |
|---|---|
| M0 `SituationToRequest` en `ScenarioData` van de pin | 7 van `PlaygroundAbsenceMarkersTest`, `PlaygroundRoleFactTypeTest.every_scenario_golden` |
| S1 een marker in het verzoek als tekst | `a_marker_in_the_situation_is_a_marker_object_of_the_request`, `a_marker_in_a_segment_…`, de drie `…_passes_on_both_routes` |
| S2 `waarde` in het verzoek als marker | `a_marker_in_the_situation_…`, `…cell_golden…`, `…markers_golden…` |
| S3 een andere constructor met payload toegelaten | `a_value_of_no_known_form_is_refused_by_name` |
| S4 een waarde zonder variant stil als tekst | `a_value_of_no_known_form_is_refused_by_name` |
| D1 een marker in de situatie als tekst | `the_situation_and_the_fixpoint_give_a_marker_as_a_marker`, `a_marker_in_a_segment_…`, de drie `…_passes_on_both_routes` |
| D2 `waarde` in de situatie zonder payload | `the_situation_and_the_fixpoint_…`, `…cell_golden…`, `…markers_golden…` |
| D3 een andere constructor met payload in de situatie getoond | `a_value_of_no_known_form_is_refused_by_name` |
| C1 een cel in het antwoord zonder de constructorwaarde | `a_marker_in_a_cell_of_the_request_stands_…`, `a_marker_a_rule_writes_into_a_cell_…`, `a_value_in_a_cell_is_answered_as_its_payload` |
| C2 een cel in het verzoek zonder de markers van haar attribuut | vijf tests van `AbsenceMarkerCellHttpTest` (ook beide van de uitleg en die van `UNKNOWN_MARKER`), `…cell_golden…` |
| C3 een afgeleide cel in het fixpunt als tekst | `the_fixpoint_gives_a_marker_in_a_cell_as_a_marker` |

Zonder getuige in de suite: de pagina (geen JS-harnas in de repo); de proef `scripts/scalar_probe.py` draait `valueHtml`
in node op de pin en na de bouw.

### Pins (oud → nieuw)

- `check-service-replay.sh`: de set van gate 12 339 → 344 (uchar's `EXPECTED_PLAYED`); `EXPECTED_REPLAY_PLAYED` 331 →
  335 (`jutterswet-uniciteit-reeks` en `jutterswet-afwezigheidsmarkers-cel`, `-creatie` en `-kopie` spelen al op de
  pin, met uitleg); `REPLAY_EXCLUDED` 8 → 9 (`jutterswet-voorwaarde-buiten-de-beurt`: een scalaire waarde op een
  tijdsafhankelijk attribuut); 943 → 956 cases. De vloeren blijven (`EXPECTED_MIN_EXPLAINED_CONDITIONS` 1241,
  `…_COMPUTATIONS` 1852, `EXPECTED_MIN_READ_LINKS` 4248; gemeten 3410, 2821, 5902, was 3348, 2805, 5821).
- Suites: toolchain 182 (ongewijzigd), service 461 → 475 (de 14 nieuwe tests), service-quarkus 54; de vloeren van
  `tools/check-skips.sh` blijven. Tweelingen 10 + 4 + 4 en 2, manifest 32 regels: ongewijzigd.
- Gedrag dat uchar veranderde: een marker als waarde in een toetsdocument was een syntaxfout van uchar's scenariolaag
  (`no viable alternative at input …`) en is nu een constructorwaarde; in de playground was de pin zonder deze kaart een
  500 op zo'n document. Geen bestaande test of verwachting veranderde.

### Voorlopige besluiten

- VB-1 (vorm in de situatie): de situatie van de playground geeft een waarde van een attribuut met markers in de vorm
  van het verzoek en het antwoord (`{"marker": <naam>}`, de payload als gewone waarde), niet in die van uchar's case
  (`{"constructor", "payload"}`): de situatie heet "zoals een verzoek haar geeft" (cellen), en het fixpunt gebruikt die
  vorm al, zodat de routes vergelijkbaar blijven.
- VB-2 (onbekende vorm): een constructor met een andere payload dan `waarde` en een waarde zonder variant zijn een
  `IllegalStateException` met naam (500 `interne fout`, de zin op stderr), niet `NonPortable` (een MARKER). Reden: uchar's
  RegelSpraak-frontend schrijft zo'n waarde niet (zijn enige sum types zijn die van de markers), en de situatie, die
  beide routes tonen, heeft er ook geen vorm voor; een MARKER zou de 500 van de situatie alleen verplaatsen.
- VB-3 (de marker zelf): `SituationToRequest` schrijft elke constructor zonder payload als marker en laat de toets tegen
  de declaratie aan `RequestMapper` (`UNKNOWN_MARKER`, dan een FAIL met de schending); uchar's resolver weigert een
  ongedeclareerde marker al (RGS-377).
- VB-4 (zichtbaarheid): `Markers` en `Markers.KEY` zijn publiek, zodat de playground (pakket `http`) dezelfde sleutel
  schrijft als het verzoek en het antwoord.
- VB-5 (de pagina): een marker staat als zijn naam in de gedempte stijl van `leeg`, zodat hij niet als tekstwaarde leest.

### Vragen

Geen. uchar's API volstond: `ScenarioValue.constructor()`/`payload()` en `RegelSpraakModel.VALUE_CONSTRUCTOR`.

### make gates

Eén run op `0ab3b68a7` (`logs/gates-1.log`); de commits daarna raken alleen deze kaart.

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m14s  testgevallen per module 182/475/54
gates: 3 twins      GROEN      3s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m32s  335 goldens, 956 cases (pin 335)
gates: 5 manifest   GROEN      1s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     46s  decision-service:dev
gates: 7 kind-smoke GROEN   1m19s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 5m51s; de uitvoer staat in out/gates/
```
