# marker-as-opgave-in-service: pin naar uchar 2e54dd254, en een marker als opgave in verzoek, antwoord, uitleg en OpenAPI

*Opgesteld door de hoofdsessie op 2026-11-20, op verzoek van de eigenaar, na uchar's kaart `absence-markers-with-opgave`
(landing `2e54dd254`).*

## Aanleiding

Sinds uchar `2e54dd254` draagt een attribuut in RegelSpraak een afwezigheidsmarkering en de opgavemarkering samen, de
afwezigheidsmarkering eerst:

    de teruggavedatum Datum in dagen (of onbekend of niet van toepassing) (mag opgegeven worden);
    de opslagkosten Numeriek (geheel getal) voor elke maand (of onbekend) (mag opgegeven worden);

`opgegeven X` en `berekende X` dragen dan de markers en het sum type van X, over het ene domein van X
(`uchar/frontends/regelspraak/grammar/DIVERGENCES.md` §7 en "Afwezigheidsmarkeringen"; `uchar/spec/RUNTIME-SEMANTICS.md`
ch. 9.5, "An opgave above a computation"). Besluiten van de eigenaar van 2026-11-20: een opgave mag zelf een marker
zijn, voor elke marker die X declareert; een opgave die verschilt van een gevulde berekening is een afwijkende opgave,
ook waar een van de twee een marker is (een datum tegenover een marker, een marker tegenover een datum, twee
verschillende markers); twee gelijke markers wijken niet af, en een opgave naast een lege berekening geeft geen melding.
De groep `opgave boven berekening X` geeft het beslissende lid ongewijzigd door, een marker inbegrepen. De golden
`jutterswet-opgave-afwezigheidsmarkers` legt het vast in vijftien cases, scalair en per maand, met een berekende datum,
een berekend getal en een initialisatie onder de opgave. In dezelfde landing: een getal dat in een andere eenheid in
een attribuut met markers wordt geschreven, staat er in de gedeclareerde eenheid (ch. 9.1).

De werkbank vraagt dit voor de herlevingsdatum van v2/herleving-ww: een regel schrijft `niet van toepassing` als de
herleving is uitgesloten, en een medewerker kan een datum of een marker opgeven. Daarvoor moet de dienst een marker als
opgave ontvangen, haar teruggeven en uitleggen.

Gelezen vóór de bouw, niet gemeten: `Markers.of` herkent een attribuut met markers aan `ObjectType.markersOf`, of aan
een sum type met `waarde` voorop; na de pin dragen de twee gegenereerde leden die markers, dus verzoek, antwoord en
OpenAPI volgen misschien zonder codewijziging. De uitleg (`Verbaliser`) van de default die beslist en van de regel
`afwijking opgave X` met een marker aan een van beide kanten is het onzekere deel, net als de replay.

## Goal

- De pin naar uchar `2e54dd254`.
- **Verzoek.** `opgegeven X` neemt op een attribuut met markers een gewone waarde of `{"marker": <naam>}`, ook als
  `value` van een segment van een tijdlijn. Een marker die X niet declareert en een marker op een `opgegeven X` zonder
  markers zijn de bestaande fouten met naam (400).
- **Antwoord.** X, `berekende X` en, waar het antwoord haar toont, `opgegeven X` in de bestaande vorm voor een attribuut
  met markers.
- **Uitleg.** De zin van de default die beslist ("opgegeven waarde", "berekende waarde", de initialisatie) en de zin
  van `afwijkende opgave X` noemen een marker bij zijn naam, ook waar de opgave een marker is en de berekening een datum
  of omgekeerd.
- **OpenAPI.** `opgegeven X` krijgt de vorm van een attribuut met markers (`oneOf` van het payloadtype en het
  markerobject met de gedeclareerde markers).
- **Replay en scenario.** `scripts/check-service-replay.sh` speelt de nieuwe golden door de dienst, en
  `bin/regelspraak scenario` speelt haar `scenario.rgs`.

## Acceptance

- (a) Meet eerst, op de nieuwe pin: wat doen verzoek, antwoord, uitleg, OpenAPI, de replay en het scenariocommando met
  de golden `uchar/corpus/golden/jutterswet-opgave-afwezigheidsmarkers`? Wat fout is of ontbreekt, staat eerst rood in
  een test.
- (b) Een HTTP-test op de bron van de golden: een opgegeven marker boven een berekende datum en een opgegeven datum
  boven een berekende marker, elk met de afwijking in het antwoord en in de uitleg; dezelfde marker opgegeven en
  berekend zonder afwijking; een opgegeven marker per maand.
- (c) `check-service-replay.sh`: de golden speelt en de gepinde aantallen volgen; een vorm die de dienst bij naam
  weigert, staat met reden in de lijst.
- (d) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `uchar` (alleen de pin), `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh`
(lijsten en pins), `tools/check-skips.sh` (floors), README, `docs/`, kaartverplaatsing.

Out of scope: uchar zelf. Een fout in uchar of een API die uchar mist, komt met een proef in het Result; de hoofdsessie
lost dat in uchar op. De werkbank (regelspraak-rules, de eval-dataset) valt er ook buiten.

## Omvang

Klein tot middel; de meting van (a) bepaalt het.

## Result

*Bouwer, 2026-11-20. Niet gemerged, niet gepusht. Logs onder `werk/marker-as-opgave-in-service/logs/`, proeven en
scripts onder `werk/marker-as-opgave-in-service/probes/` (`measure.py`, `unit.py`, `mutations.py`; uitvoer in
`probes/m1/`), buiten de repo.*

### Kort

Op de nieuwe pin dragen verzoek, antwoord, fixpunt, uitleg, OpenAPI, de replay en `bin/regelspraak scenario` een marker
als opgave al, zonder codewijziging: uchar geeft `opgegeven X` en `berekende X` de markers van X (`ObjectType.markersOf`
op het model van een bron, het sum type van X op het model van een document), en `Markers.of` leest beide. Niets was
fout of ontbrak, dus er is geen rood-eerst en geen fix; wat werkt, staat nu in tests, en de replay-pins volgen.

### Meting (acceptatie a), op de pin `2e54dd254`

Dienst gestart met `bin/regelspraak serve --detail` over de `source.rgs` van de golden, één entrypoint met alle vormen
(`probes/measure.py`, uitvoer `probes/m1/*.json`); verzoeken met `?explanation=true&explanationParts=true&fixpoint=true`.

- **Verzoek.** `"opgegeven teruggavedatum": {"marker": "niet van toepassing"}` en `{"marker": "onbekend"}`: 200, ook
  als `value` van een segment van `opgegeven opslagkosten`. Fouten met naam: `{"marker": "misschien"}` op `opgegeven
  teruggavedatum` → 400 `UNKNOWN_MARKER` "'misschien' is geen marker van attribuut 'opgegeven teruggavedatum'
  (toegestaan: onbekend, niet van toepassing)"; `onbekend` op `opgegeven afhaaldatum` → 400 `UNKNOWN_MARKER`
  "(toegestaan: niet van toepassing)"; `niet van toepassing` in een segment van `opgegeven opslagkosten` → 400
  `UNKNOWN_MARKER` op `instances[0].attributes.opgegeven opslagkosten.timeline[0].value`; een marker op `opgegeven
  meldingsdatum` van de golden `jutterswet-opgave-datum` (opgave zonder markers) → 400 `WRONG_TYPE` "attribuut
  'opgegeven meldingsdatum' heeft geen markers; {"marker": <naam>} is de invoer van een attribuut met markers".
- **Antwoord.** case3 (claimdatum 10-03-2026, opgave niet van toepassing): `"teruggavedatum": {"marker": "niet van
  toepassing"}`, `"berekende teruggavedatum": "2026-03-24"`, `"opgegeven teruggavedatum": {"marker": "niet van
  toepassing"}`, `"afwijkende opgave teruggavedatum": true`, `"inconsistencies": ["controle opgave teruggavedatum"]`.
  case2 (drijfhout, opgave 12-03-2026): `"2026-03-12"`, berekend `{"marker": "niet van toepassing"}`, afwijking `true`.
  case4 (dezelfde marker): afwijking `false`, `inconsistencies` `[]`. case5 (onleesbaar, opgave niet van toepassing):
  berekend `{"marker": "onbekend"}`, afwijking `true`. case6: `{"marker": "onbekend"}`, berekend `null`, geen afwijking.
  case10: `opslagkosten` `onbekend` [01-01, 01-02), `4` [01-02, 01-05), `10` [01-05, 01-06), `onbekend` [01-06, 01-08)
  van 2025, `opgegeven opslagkosten` met zijn twee segmenten. case13: `"afhaaldatum": {"marker": "niet van
  toepassing"}`, `"berekende afhaaldatum": null`. Fixpunt: `{"attribute": "opgegeven teruggavedatum", "value":
  {"marker": "niet van toepassing"}, "origin": "input"}`.
- **Uitleg.** case3: "V heeft als teruggavedatum niet van toepassing volgens 'opgegeven teruggavedatum'
  (onvoorwaardelijk): zijn opgegeven teruggavedatum (niet van toepassing); verdrongen: 'berekende teruggavedatum'
  (onvoorwaardelijk, 24-03-2026)" en "V heeft afwijkende opgave teruggavedatum, omdat zijn opgegeven teruggavedatum
  (niet van toepassing) gevuld is en zijn berekende teruggavedatum (24-03-2026) gevuld is en zijn opgegeven
  teruggavedatum (niet van toepassing) is ongelijk aan zijn berekende teruggavedatum (24-03-2026)". case2 omgekeerd
  ("… (12-03-2026) is ongelijk aan zijn berekende teruggavedatum (niet van toepassing)"), case4 "V heeft geen
  afwijkende opgave teruggavedatum, want niet geldt: zijn opgegeven teruggavedatum (niet van toepassing) is ongelijk
  aan zijn berekende teruggavedatum (niet van toepassing)", case1 "… volgens 'berekende teruggavedatum'
  (onvoorwaardelijk): zijn berekende teruggavedatum (niet van toepassing); 'opgegeven teruggavedatum' gaf geen waarde",
  case13 "… volgens 'opgegeven afhaaldatum' (onvoorwaardelijk): zijn opgegeven afhaaldatum (niet van toepassing);
  verdrongen: 'berekende afhaaldatum' (onvoorwaardelijk, gaf geen waarde), 'afhaal zonder claim' (onvoorwaardelijk,
  01-01-2026)", case10 per periode "V heeft als opslagkosten onbekend van 01-06-2025 tot 01-08-2025 volgens 'opgegeven
  opslagkosten' (onvoorwaardelijk): zijn opgegeven opslagkosten (4 van 01-02-2025 tot 01-05-2025 en onbekend van
  01-06-2025 tot 01-08-2025)". In `explanationParts` draagt elke `VALUE` van een marker `marker`. Geen "waarde(" en
  geen "niet is vast te stellen".
- **OpenAPI** (`GET /openapi`, `probes/m1/openapi.json`). `opgegeven teruggavedatum` en `berekende teruggavedatum` zijn
  gelijk aan `teruggavedatum`: `oneOf` `{"type": "string", "format": "date"}` en het markerobject met `enum`
  `["onbekend", "niet van toepassing"]`; `opgegeven afhaaldatum` `enum` `["niet van toepassing"]`, `opgegeven bergloon`
  `integer` en `["onbekend"]`; de `value` van een segment van `opgegeven opslagkosten` `oneOf` `integer` en
  `["onbekend"]`.
- **Dienst uit het document** (`serve` over `ir.json`): case3 en case10 geven hetzelfde antwoord en dezelfde uitleg als
  over de bron, de 400 van de onbekende marker ook, en de OpenAPI van de drie leden is gelijk.
- **Replay** (`scripts/service_replay.py --explanation` op de golden, `logs/replay-golden-1.log`):
  `jutterswet-opgave-afwezigheidsmarkers PLAYED 15 182 167 0 99 255` (15 cases, 182 zinnen, 167 voorwaarden, 0 keer
  "niet is vast te stellen", 99 berekende waarden, 255 gekoppelde lezingen). De poort zelf op de pin, vóór de pins
  (`logs/replay-1.log`): `374 goldens speelden, EXPECTED_REPLAY_PLAYED pint 373`.
- **Scenariocommando** (`bin/regelspraak scenario source.rgs scenario.rgs`, `logs/scenario-1.log`): vijftien keer
  `PASS`, exit 0; met `--explanation` (`logs/scenario-explanation-1.log`) dezelfde zinnen als de dienst.
- **Een payload in een andere eenheid** (`probes/unit.py` op uchars fixture `payload-in-another-unit`, gediend uit het
  `ir.json`): lading 2 (t) geeft `"gewicht": 2000` (kg). De oude waarde door de dienst is niet gemeten; uchars Result
  mat vóór zijn fix op de Drools-tak `waarde(2)` (eigen gevolgtrekking: de dienst gaf toen `2`).

### Commits

| hash | inhoud |
|---|---|
| `12dcb5151` | pin uchar `2e54dd254`; replay-pins `EXPECTED_REPLAY_PLAYED` 373 → 374, `EXPECTED_REPLAY_CASES` 1042 → 1057 |
| `16effc88b` | tests: `MarkerAsOpgaveHttpTest` (10, nieuw), `OpenApiAbsenceMarkersTest` (+1), `PlaygroundAbsenceMarkersTest` (+1) |
| `43b328595` | README ("A marker as opgave") en `docs/ENGINE.md` (de alinea over een attribuut met markers) |
| (deze) | Result |

### Wat er veranderde

- **Verzoek, antwoord, fixpunt, uitleg, OpenAPI, Quarkus:** geen codewijziging; het gedrag hierboven komt met de pin.
  Wat uchar veranderde, met oud en nieuw: een declaratie `(of onbekend) (mag opgegeven worden)` was een syntaxfout van
  uchars frontend (RGS-091), zodat de dienst er niet op startte (eigen gevolgtrekking uit uchars rood-eerst-log, niet
  door de dienst gemeten); nu start hij en dragen de drie leden de markers. `(mag opgegeven worden) (of onbekend)` blijft
  een syntaxfout. Een getal in een andere eenheid in een attribuut met markers: oud `2` (uchars meting), nieuw `2000`.
- **Tests** (acceptatie b), alle groen op de pin zonder codewijziging:
  `MarkerAsOpgaveHttpTest` (bron van de golden, drie diensten: bron, `ir.json`, en `jutterswet-opgave-datum`):
  `a_supplied_marker_above_a_computed_date_decides_and_deviates` (case3, met de `VALUE`-stukken),
  `a_supplied_date_above_a_computed_marker_decides_and_deviates` (case2), `a_computed_marker_decides_without_an_opgave`
  (case1), `the_same_marker_supplied_and_computed_does_not_deviate` (case4), `two_different_markers_deviate` (case5),
  `a_supplied_marker_decides_above_the_initialisation` (case13), `a_supplied_marker_per_month_decides_its_days`
  (case10), `a_marker_as_opgave_reads_the_same_on_a_service_from_the_document`,
  `a_marker_the_attribute_does_not_declare_is_400_unknown_marker_on_its_opgave` (scalair en in een segment),
  `a_marker_on_an_opgave_without_markers_is_400_wrong_type`. `OpenApiAbsenceMarkersTest.the_opgave_and_the_computation_have_the_contract_of_their_attribute`
  (bron en document). `PlaygroundAbsenceMarkersTest.the_test_document_of_the_opgave_golden_passes_on_both_routes`
  (het toetsdocument op de gewone route en via de dienst: vijftien keer PASS, gelijke verwachtingen, situatie,
  fixpunt, uitleg en stukken).
- **Replay** (acceptatie c): de golden speelt door de dienst; geen vorm geweigerd, `REPLAY_EXCLUDED` ongewijzigd.

### Rood eerst

Geen: de meting vond niets fout of ontbrekend, dus geen test die vóór een fix rood stond.

### Mutaties (niet gecommit; `probes/mutations.py`, logs `logs/mut/`, samenvatting `logs/mutations-1.log`)

| mutatie | rood |
|---|---|
| M1 `Markers.of` geeft een lid in `attributeMarkerDomain` (`opgegeven X`, `berekende X`) geen markers | 11 tests: alle van `MarkerAsOpgaveHttpTest` behalve de 400 zonder markers, de OpenAPI-test en de playgroundtest; o.a. `V.opgegeven teruggavedatum: value variant "date" does not match the declared datatype` en `… "kind":"WRONG_TYPE","error":"attribuut 'opgegeven opslagkosten' heeft geen markers; …" ==> expected: <200>` |
| M2 de toets "gevuld" op een operand van een sum type als "een waarde heeft" | case3, case2, case13: "… zijn opgegeven teruggavedatum (niet van toepassing) een waarde heeft en zijn berekende teruggavedatum (24-03-2026) een waarde heeft …" |
| M3 de verdrongen waarde als uchars tekst (`renderValue` in plaats van `render`) | case3 en case13: "verdrongen: 'berekende teruggavedatum' (onvoorwaardelijk, waarde(2026-03-24))" |
| M4 een gelezen marker staat één keer, als een literal | case3, case2, case4, case5: "V heeft afwijkende opgave teruggavedatum, omdat niet van toepassing gevuld is en onbekend gevuld is en niet van toepassing is ongelijk aan onbekend" |
| M5 een marker op een attribuut zonder markers stil weggelaten | `a_marker_on_an_opgave_without_markers_is_400_wrong_type`: `{"instances":[… "meldingsdatum":null …]} ==> expected: <400> but was: <200>` |
| M6 een marker die het attribuut niet declareert aanvaard | `a_marker_the_attribute_does_not_declare_is_400_unknown_marker_on_its_opgave`: `{"error":"interne fout"} ==> expected: <400> but was: <500>` |

Na elke mutatie stond het bestand terug; `git status --short` leeg.

### Pins en vloeren (oud → nieuw)

- uchar `202d6f10e` → `2e54dd254`.
- `scripts/check-service-replay.sh`: set van gate 12 382 → 383 (uchars `EXPECTED_PLAYED`); `EXPECTED_REPLAY_PLAYED`
  373 → 374; `EXPECTED_REPLAY_CASES` 1042 → 1057 (de vijftien cases van de golden). Ongewijzigd: `REPLAY_EXCLUDED`
  (1 golden, 10 cases), `EXPLANATION_UNDETERMINED` (leeg), `RED_REFUSED` (13), `EXPECTED_RED_FAULTS` 69, en de vloeren
  `EXPECTED_MIN_EXPLAINED_CONDITIONS` 1241, `…_COMPUTATIONS` 1852, `EXPECTED_MIN_READ_LINKS` 4248 (gemeten 4295, 3540,
  6619).
- Suites: toolchain 206 (ongewijzigd), service 573 → 585, service-quarkus 62 (ongewijzigd); de vloeren van
  `tools/check-skips.sh` blijven.

### Voorlopige besluiten

- VB-1 (tests): een eigen klasse `MarkerAsOpgaveHttpTest` voor de golden van de opgave, omdat ze drie diensten start
  (bron, document, en een opgave zonder markers); de OpenAPI- en playgroundtest staan in de bestaande klassen.
- VB-2 (playground): het toetsdocument van de golden ook via `?via=service` gemeten en vastgelegd, buiten het lijstje
  van de kaart; de werkbank leest hetzelfde document. Geen codewijziging.
- VB-3 (scenariocommando): geen eigen toolchaintest; de playgroundtest draait de gewone scenarioroute
  (`Playground.analyze` zonder dienst, via `ScenarioRunner`), en `bin/regelspraak scenario` is gemeten in
  `logs/scenario-1.log`.
- VB-4 (README): de bullet staat in de bestaande lijst van contractwijzigingen, met oud en nieuw zoals de andere.

### Open punten en vragen

1. **Lidwoord in de uitleg (toolchain, buiten de kaart).** De zin van een initialisatie schrijft altijd "de" voor het
   attribuut: "omdat de bergloon in de invoer leeg was" (`Verbaliser.reason`, `b.text("de ")`), waar RegelSpraak "het
   bergloon" declareert; en "V heeft geen berekende bergloon" volgt uchars gegenereerde naam `berekende bergloon`. Ook
   zonder markers. Niet veranderd: de kaart vraagt het niet.
2. **Geen punt over uchar.** Geen fout in uchar gevonden en geen API gemist; geen proef onder `probes/` voor uchar.

### make gates

Eén run op `43b328595` (`logs/gates-1.log`); de commit daarna raakt alleen deze kaart.

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m46s  testgevallen per module 206/585/62
gates: 3 twins      GROEN      3s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m49s  374 goldens (pin 374), 1057 cases (pin 1057)
gates: 5 manifest   GROEN      1s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     39s  decision-service:dev
gates: 7 kind-smoke GROEN   1m22s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 6m36s; de uitvoer staat in out/gates/
```

## Landing (hoofdsessie, 2026-11-20)

De hoofdsessie las het Result en de diff: de pin, twaalf tests en de tekst in README en `docs/ENGINE.md`; geen
productiecode veranderde, omdat `Markers.of` de markers van `opgegeven X` en `berekende X` al uit uchars model leest.

- **De pin ging daarna nog één commit verder, naar uchar `2c883ab74`** (`21ac0c836`). Na de pin van deze kaart kreeg
  uchar twee wijzigingen. Wie `(mag opgegeven worden) (of onbekend)` schrijft, krijgt de goede volgorde te lezen in
  plaats van de kale syntaxfout (besluit van de eigenaar); gemeten met `bin/regelspraak check`: "ERROR (line 2):
  attribuut 'taxatie' van Vondst: '(of onbekend)' staat vóór '(mag opgegeven worden)', niet erna — schrijf '(of
  onbekend) (mag opgegeven worden)' (dialect §7)" (RGS-461). En uchars clingo-backend weigert een payload in een andere
  eenheid ook in een fold, wat de toolchain niet raakt. De README noemt de melding (`fc8f8fcb1`); geen replay-pin
  bewoog.
- **Open punt 1 (het lidwoord in de zin van een initialisatie)** is aan de eigenaar voorgelegd en zit niet in deze
  landing.

`make gates` op `fc8f8fcb1`, groen (`werk/marker-as-opgave-in-service/logs/gates-2.log`):

```
gates: 1 pin        GROEN      1s
gates: 2 reactor    GROEN   3m21s  testgevallen per module 206/585/62
gates: 3 twins      GROEN      5s
gates: 4 replay     GROEN   3m05s  374 goldens (pin 374), 1057 cases (pin 1057)
gates: 5 manifest   GROEN      3s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     42s
gates: 7 kind-smoke GROEN   1m25s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 8m34s
```

De commit hierna raakt alleen deze kaart.
