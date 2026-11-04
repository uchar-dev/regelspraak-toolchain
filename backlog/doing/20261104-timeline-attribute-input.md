# timeline-attribute-input: een tijdsafhankelijk attribuut als invoer van de dienst, en de uitleg van een default per dag

*Opgesteld door de hoofdsessie op 2026-11-04, na uchar's kaarten `writers-per-day-beside-input`, `empty-write-decides-nothing` en
`defaults-per-period-on-timeline` (uchar `c3c750103`).*

## Aanleiding

De dienst weigert een tijdsafhankelijk attribuut als invoer (`RequestMapper`, `UNSUPPORTED`; `OpenApiEmitter` geeft er geen property
voor), en een scalaire waarde erop ook. In uchar is invoer op een tijdlijn sinds 2026-11-04 volwaardig: elke schrijver beslist alleen zijn
eigen dagen naast de invoer, en met `(mag opgegeven worden)` op een tijdsafhankelijk attribuut gaat een opgave per periode voor de
berekening (een defaults-groep die per dag beslist, RUNTIME-SEMANTICS ch. 7.11). Een vastgesteld dagloon of een correctie van een
behandelaar voor een periode kan de werkbank zo pas via de dienst gebruiken als de dienst die invoer neemt. In de replay van
`check-service-replay.sh` staan 41 goldens met de reden "tijdlijn als invoerwaarde" of "kenmerk met perioden".

Daarnaast weigert uchar's `decisionsOf` (de beslissers per object, die `Verbaliser`, `ScenarioRunner` en `CaseSession` lezen) een build met
een defaults-groep op een tijdlijndoel, omdat het één beslisser per object geeft; de uitleg van zo'n groep faalt dan.

## Goal

- Het verzoek draagt een tijdsafhankelijk attribuut als tijdlijn, in de vorm van uchar's case (`{"timeline": [{"from", "to", "value"}]}`,
  `from` of `to` weggelaten voor een open grens), zoals een tijdsafhankelijke parameter al; de OpenAPI beschrijft het. Een scalaire waarde
  op zo'n attribuut blijft een fout met naam (400).
- De uitleg van een default per dag: uchar levert de beslissers per periode (een kaart in uchar als de API dat nog niet heeft, eerst
  daar), en `Verbaliser` noemt per periode welke default besliste; ook de dagen waarop de invoer bleef staan (open punt van pin
  `f6487587`).
- Een tijdsafhankelijk kenmerk met perioden als invoer: meten wat uchar draagt en, als het past, meenemen; anders een fout met naam.

## Acceptance

- (a) Rood eerst: een verzoek met een tijdlijn op een attribuut (een golden met `(mag opgegeven worden)` op een tijdlijn, uchar's
  `jutterswet-opgave-per-periode`, en een gewone schrijver naast invoer) geeft nu `UNSUPPORTED` en daarna de pinnen van uchar.
- (b) De replay: elke golden van `REPLAY_EXCLUDED` met de reden "tijdlijn als invoerwaarde" die nu speelt, gaat uit de lijst;
  `EXPECTED_REPLAY_PLAYED` stijgt met dat aantal; de rest houdt zijn reden.
- (c) De uitleg per periode met een test die eerst rood is.
- (d) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh` (lijst en pin), README, `docs/ENGINE.md`,
kaartverplaatsing. Een API die uchar mist, wordt een kaart in uchar, niet hier.

## Omvang

Middel.

## Result

*Bouwer, 2026-11-04. Branch `card/timeline-attribute-input`, niet gemerged, niet gepusht. Logs van het rood onder
`werk/timeline-attribute-input/logs/` (buiten de repo), de gate-runs in `werk/timeline-attribute-input/gates-0-pin.log`
(de pin, `make gates-no-docker`) en `gates-1.log` (het eind, `make gates`).*

### Commits

| hash | inhoud |
|---|---|
| `537ad4e8d` | Pin uchar 474732d3: een defaults-groep op een tijdlijn beslist per dag (de pins van de replay, README) |
| `7938c1594` | kaart naar `doing/` |
| `edfe1a2a3` | dienst: een tijdsafhankelijk attribuut als tijdlijn, een kenmerk als perioden; de uitleg per dag een fout met naam |
| (deze) | Result |

### De pin (uchar `f64875872` → `474732d3c`)

Nieuw voor de dienst: `defaults-per-period-on-timeline` (een defaults-groep op een tijdsafhankelijk attribuut beslist
per dag; de opgave-markering mag op zo'n attribuut; `decisionsOf` weigert een build met zo'n stapel) en
`clingo-lazy-bucket-scalar-fault` (alleen clingo en het corpus). `writers-per-day-beside-input` en
`empty-write-decides-nothing` zaten al in `f64875872`. Op de pin zelf veranderde voor de dienst niets: uchars gate 12
speelt 330 goldens (was 328), met `jutterswet-opgave-per-periode` en `jutterswet-gevolg-buiten-de-beurt`; geen van
beide speelde door de dienst. `make gates-no-docker` op de pin: groen (287 goldens, 794 cases).

### Wat er in de dienst veranderde

- **Verzoek.** Een tijdsafhankelijk attribuut neemt zijn tijdlijn, `{"timeline": [{"from", "to", "value"}]}`, `from` of
  `to` weggelaten voor een open grens; een tijdsafhankelijk kenmerk de perioden waarin het geldt, `{"periods": [{"from",
  "to"}]}`. `RequestMapper` toetst ze op het veld van het verzoek zoals uchars case-lezer (`CaseRunner.timelineSegments`,
  `CaseInput.characteristicTimeline`) en schrijft ze in de case (`attributes` en `characteristicTimelines`); uchars
  `CaseInput.of` leest ze. `ParameterTimeline` is `TimelineInput` geworden: één toets voor de tijdlijn van een parameter
  en van een attribuut, en die van de perioden. Een raster van meer dan één periode en een variabel startpunt: 400
  `UNSUPPORTED`, zoals uchars case-lezer ze niet draagt.
- **Oud → nieuw.** Een tijdlijn op een attribuut: 400 `UNSUPPORTED` ("tijdlijn-gewaardeerd attribuut … wordt niet
  ondersteund door de dienst-kern") → gelezen. Perioden op een kenmerk: 400 `UNSUPPORTED` → gelezen. Een boolean op een
  tijdsafhankelijk kenmerk: 400 `UNSUPPORTED` → 400 `WRONG_TYPE` (uchars invoerstap weigert hem als defect; de
  vorm bestaat nu). Een scalaire waarde op een tijdsafhankelijk attribuut: 400 `UNSUPPORTED` blijft, met een zin die
  de tijdlijnvorm noemt (uchars emmer van de peildatum, RUNTIME-SEMANTICS ch. 8.4, neemt het verzoek niet over). Een
  tijdlijn op een attribuut zonder tijdlijn en perioden op een kenmerk zonder: 400 `WRONG_TYPE` met een eigen zin. Een
  volledige tijdlijn waar een regel de emmerscalair leest (het eigen totaal): bereikte de dienst niet → 422
  `INPUT_FORM_FAULT` met uchars zin.
- **OpenAPI.** Een tijdsafhankelijk attribuut is een eigenschap van zijn tijdlijn, een tijdsafhankelijk kenmerk een
  van zijn perioden (waren weggelaten, "Tijdlijn-gewaardeerde leden worden … niet ondersteund"); het verzoekschema en
  de beschrijving van `INPUT_FORM_FAULT` zeggen het. De vier OpenAPI-goldens kregen elk drie beschrijvingsregels.
- **Uitleg per dag (fout met naam).** `Verbaliser.unexplained(build)` leest uit `TmsTranspiler.defaultsGroups()` welke
  stapels per dag beslissen; `DecisionCore.withoutExplanation(path)` geeft dan op `?explanation=true` een 400
  `UNSUPPORTED` op `explanation` die de stapels noemt (was een 500: `IllegalStateException` van `decisionsOf`). Het
  antwoord zonder uitleg speelt. `ScenarioRunner` leest bij zo'n build geen beslissingen en geeft het scenario zijn
  oordeel, zijn sessie en de reden als `explanationError` (was een FAIL `ERROR IllegalStateException`); de playground
  vraagt via de dienst zonder uitleg en geeft dezelfde reden.
- **Playground `?via=service`.** `SituationToRequest` stuurt de tijdlijn van een attribuut en de perioden van een
  kenmerk mee (was een MARKER "… het verzoek van de dienst draagt geen perioden").

### Meting: wat uchars publieke API biedt voor de uitleg per dag

- `DroolsBackend.decisionsOf` → `TmsTranspiler.decisionsOf` gooit voor elke build met een stapel per dag
  (`StackLayout.perDay`): "decisionsOf leest per object en stapel één beslisser, en de stapel … op een tijdlijndoel
  beslist per dag".
- `TmsTranspiler.defaultsGroups()` geeft per stapel `DefaultsGroup.perDay` (publiek): daarmee weet de dienst vooraf welke
  stapels het zijn.
- `DroolsBackend.readingsOf` geeft per default `HOLDS` waar hij op minstens één dag aan de beurt is en `DISPLACED` waar
  op geen, de rechtvaardiging per periode en het gevolg (uchar VB-B1: de statusregel noemt geen dagen). Welke default
  op welke dag besliste, staat er niet in; dat volgt alleen uit de verzoening per dag, die semantiek is en in uchar
  hoort.
- `Fixpoint` geeft de geschreven tijdlijn, niet wie elke dag besliste. Hetzelfde geldt voor gewone schrijvers naast
  invoer (het open punt van pin `f6487587`): geen API noemt de dagen waarop de invoer bleef staan.

Dus geen beslisser per periode; uchar is niet veranderd, de dienst geeft een fout met naam, en hieronder staat de
voorgestelde uchar-kaart.

### Voorgestelde uchar-kaart: `decisions-per-period`

**Goal.** Een consument kan per object en tijdlijndoel lezen wie op welke dagen besliste, zonder de verzoening per dag
zelf te doen. Voor een stapel per dag (RUNTIME-SEMANTICS ch. 7.11) geeft `DroolsBackend.decisionsOf` (of een
zustermethode) per object en stapel de maximale perioden met één uitkomst: de beslissende default (pointer zoals
`DefaultsDecision.Decider`, met de waarde die hij die dagen schreef), een conflict (sleutel en beslissers) of geen
beslisser (de invoer staat, of het doel blijft leeg), over alle tijd met open grenzen; voor een tijdlijndoel van gewone
schrijvers (ch. 3.11) per object en doel de perioden per schrijver (de vorm) en die van de invoer. Het leest de
controlefeiten en de `Outcome`-dagen (`PeriodDefaults.Days`) die de lowering al neerlegt en rekent niets opnieuw.

**Acceptance.**
- (a) Een publiek record per periode met javadoc in de termen van ch. 7.11 en 3.11; `decisionsOf` weigert zo'n build
  niet meer, of de zustermethode is de weg en `decisionsOf` noemt haar in zijn weigering.
- (b) Uitgewerkt voorbeeld, letterlijk als test: `jutterswet-opgave-per-periode` case4 → [2025-01-01, 2025-03-01)
  default `/defaults/0` (opgave, 10), [2025-03-01, 2025-06-01) `/defaults/1` (berekend, 20), [2025-06-01, 2026-01-01)
  geen beslisser (invoer 99); `anchor/jutterswet-borgdeel` case6 → [2024-01-01, 2024-03-01) de regel 'borgdeel van een
  jutter', [2024-03-01, 2024-04-01) de invoer.
- (c) Een witness over elke golden, rode case en fixture met een stapel per dag (`ref/test/fixtures/defaults-per-day`,
  `-cases`) of een tijdlijndoel met invoer, op beide takken: de beslissers per periode zijn die van ref
  (`run_defaults_timeline`, `decide_days`), en de vereniging van hun waarden met de invoer is de tijdlijn van het
  fixpunt.
- (d) Rood eerst; per bewaking een mutatie die een test rood maakt; `make gates` groen.

Daarna, in de toolchain: `Verbaliser` noemt per periode welke default of regel besliste en op welke dagen de invoer
bleef staan, `Verbaliser.unexplained` en `EXPLANATION_REFUSED` vervallen.

### Rood eerst (letterlijk)

- `TimelineHttpTest`, nieuwe tests tegen de oude mapper (`logs/01-red-timeline-http.log`): 23 tests, 9 failures, o.a.
  `an_attribute_takes_its_timeline_and_the_opgave_decides_per_day`: `{"field":"instances[0].attributes.strandloon",
  "kind":"UNSUPPORTED","error":"tijdlijn-gewaardeerd attribuut 'strandloon' wordt niet ondersteund door de
  dienst-kern"}, … ==> expected: <200> but was: <400>`; `the_explanation_of_a_defaults_group_that_decides_per_day_is_400_naming_the_stacks`:
  `expected: <explanation> but was: <instances[0].attributes.strandloon>`. De tiende,
  `a_timeline_on_an_attribute_without_one_is_400_wrong_type`, was al groen (`ValueCoder` gaf WRONG_TYPE op het object).
- `ScenarioRunnerTest.a_defaults_group_that_decides_per_day_has_a_named_explanation_error` tegen de oude runner
  (`logs/02-red-scenario-runner.log`): `case1-opgave-in-de-winter.json: ERROR IllegalStateException: decisionsOf leest
  per object en stapel één beslisser, en de stapel defaults A:Jutter.strandloon op een tijdlijndoel beslist per dag
  (RUNTIME-SEMANTICS ch. 7.11) [] ==> expected: <PASS> but was: <FAIL>`.
- `PlaygroundViaServiceTest`, `ChainServiceTest`, `EntryHttpTest` tegen de oude hoofdbronnen
  (`logs/03-red-playground-chain-entry.log`): 4 failures, 2 errors, o.a. `SituationToRequest$NonPortable: kenmerk
  'borgplichtig' van J1 is tijdsafhankelijk; het verzoek van de dienst draagt geen perioden` en, via de dienst, `"status":
  "FAIL","diagnosis":"case1-opgave-in-de-winter.json: ERROR IllegalStateException: decisionsOf …"`.

### Tests die de kaart verandert (oud → nieuw)

- `ChainServiceTest.schema_of_the_subtype_rejects_the_inherited_timeline_members_instead_of_offering_them_as_scalar`
  (geen eigenschap, "meldtermijn (tijdlijn)" in de beschrijving) →
  `schema_of_the_subtype_offers_the_inherited_timeline_members_as_timeline_and_periods_never_as_scalar` (een object met
  `timeline`, een object met `periods`).
- `ChainServiceTest.an_inherited_timeline_characteristic_is_refused_loudly_on_the_subtype` (`UNSUPPORTED`) →
  `a_boolean_on_an_inherited_timeline_characteristic_is_a_wrong_type_on_the_subtype` (`WRONG_TYPE`); de attribuutzus
  heet `a_scalar_on_an_inherited_timeline_attribute_…` met dezelfde toets.
- `PlaygroundViaServiceTest.a_timeline_in_the_situation_is_a_marker` (MARKER) →
  `a_timeline_and_periods_in_the_situation_are_carried` (alle vier scenario's van `jutterswet-dagentelling-rgs` PASS met
  de verwachtingen van de gewone route).
- `EntryHttpTest.a_timeline_gives_over_http_400_and_the_core_translates_an_input_form_error_to_the_422` →
  `a_scalar_gives_over_http_400_and_a_full_timeline_where_the_rule_takes_the_bucket_scalar_the_422`: de 400 op de
  scalair blijft, de 422 `INPUT_FORM_FAULT` komt er nu over HTTP bij; de toets op kernniveau blijft.
- De vier OpenAPI-goldens (`service/src/test/resources/openapi/*.golden.json`): drie beschrijvingsregels.

Nieuw: tien tests in `TimelineHttpTest` (uchars pinnen van `jutterswet-opgave-per-periode` case4 en case5 en van
`anchor/jutterswet-borgdeel` case6 door de dienst, de fouten met naam), één in `ChainServiceTest`, één in
`PlaygroundViaServiceTest`, één in `ScenarioRunnerTest`. Testgevallen per module 179/425/54 → 180/437/54; de vloeren
van `tools/check-skips.sh` onaangeroerd.

### De pins van de toolchain-gates (oud → nieuw)

- uchar-submodule: `f64875872` → `474732d3c`.
- `EXPECTED_REPLAY_PLAYED`: 287 → 287 (pin; de set van gate 12 328 → 330) → **323** (kaart): 36 goldens spelen, de 27
  met de reden "tijdlijn als invoerwaarde" en de 9 met "kenmerk met perioden" zonder scalaire waarde op een
  tijdsafhankelijk attribuut.
- `REPLAY_EXCLUDED`: 41 → 43 (pin: `jutterswet-opgave-per-periode` "tijdlijn als invoerwaarde",
  `jutterswet-gevolg-buiten-de-beurt` "kenmerk met perioden") → **7**. Alle zeven geven in een case een scalaire waarde op
  een tijdsafhankelijk attribuut; vijf kregen die reden in plaats van hun oude (`jutterswet-gevolg-buiten-de-beurt`
  "kenmerk met perioden"; `jutterswet-kadetarief`, `-keurlast`, `-muntbewaring`, `-vast-startpunt` "tijdlijn als
  invoerwaarde"): de oude reden was de eerste vorm die het verzoek niet droeg, de scalair is wat overblijft.
  `jutterswet-penningjaar` en `-tempototaal` houden hun reden.
- `EXPLANATION_REFUSED` (nieuw, stale in beide richtingen): 0 → 1, `jutterswet-opgave-per-periode`.
- Vloeren van de replay onaangeroerd; gemeten 3159 voorwaarden (vloer 1241), 2751 berekende waarden (1852), 5616
  gelezen waarden met hun zin (4248).
- `scripts/service_replay.py` (de driver van `check-service-replay.sh`, buiten de May-touch van de kaart maar nodig):
  stuurt de tijdlijn van een attribuut en de perioden van een kenmerk, en telt een uitleg die de dienst met een 400
  `UNSUPPORTED` op `explanation` weigert als zesde veld van de PLAYED-regel.

### Open punten

- Een tijdsafhankelijk kenmerk in `output` weigert nog steeds de start (het antwoord geeft een kenmerk als boolean); de
  perioden in het antwoord zijn een eigen kaart van de toolchain.
- De scalaire waarde op een tijdsafhankelijk attribuut (de emmer van de peildatum) draagt het verzoek bewust niet; de
  zeven goldens blijven daarom uitgesloten.

### make gates

Eén run aan het eind in de worktree op `edfe1a2a3` (`werk/timeline-attribute-input/gates-1.log`); de commit daarna raakt
alleen deze kaart.

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m05s  testgevallen per module 180/437/54
gates: 3 twins      GROEN      3s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m22s  323 goldens, 924 cases (pin 323)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     43s  decision-service:dev
gates: 7 kind-smoke GROEN   1m21s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 5m31s; de uitvoer staat in out/gates/
```
