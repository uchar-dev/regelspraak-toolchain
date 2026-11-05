# fixpoint-creation-value: het fixpunt toont op een gemaakt object de waarde bij het maken

*Opgesteld door de hoofdsessie op 2026-11-05, uit de open punten van `timeline-stack-in-service` (toolchain `4288f4d4a`). Een fout die
in die landing had moeten meegaan.*

## Aanleiding

Sinds uchar `timeline-initialisation-allocation-creation` is de waarde die een objectcreatie op een attribuut zet, per dag de invoer van
het gemaakte object (rang 2 van de stapel): een regel die op een dag beslist gaat ervoor, op de andere dagen blijft de waarde bij het maken
staan. Het antwoord van de dienst geeft dat goed, en de uitleg noemt het "de waarde bij het maken". Het fixpunt (`?fixpoint=true`, in detail
en playground; `FixpointGivens`) geeft op een gemaakt object alleen de afgeleide tijdlijn, zonder de waarde bij het maken die op een deel
van de dagen bleef staan; op een object uit het verzoek geeft het daar `input` (r. ~295) en `origin`.

## Goal

- Het fixpunt geeft op een attribuut van een gemaakt object de waarde bij het maken waar zij stond, in de vorm die het voor de invoer van
  een object uit het verzoek geeft (`input`, per segment op een tijdlijn), met een `origin` die zegt dat het de waarde van het maken is
  (de bestaande herkomst `creation` waar die past). Scalair en op een tijdlijn, en op een cel als die vorm daar bestaat.
- Wat uchar daarvoor geeft (`Fixpoint.objects`, `PeriodDecisions.Span.input`) lees je; een API die ontbreekt, wordt een vraag.

## Acceptance

- (a) Rood eerst: `?fixpoint=true` op uchar's golden `jutterswet-creatie-per-dag` (een gemaakt object met een waarde bij het maken naast een
  regel op een deel van het jaar) mist op main de waarde bij het maken; een test die haar verwacht, faalt.
- (b) Geen andere verwachting verandert; een bestaande pin die de oude vorm vastlegt, gaat mee met oud en nieuw in het Result.
- (c) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, README, `docs/ENGINE.md`, kaartverplaatsing.

## Omvang

Klein.

## Result

*Bouwer, 2026-11-05. Branch `card/fixpoint-creation-value`, niet gemerged, niet gepusht. Logs onder
`werk/fixpoint-creation-value/logs/` (buiten de repo), het mutatiescript onder `werk/fixpoint-creation-value/scripts/`
(`mutations.py`).*

### Commits

| hash | inhoud |
|---|---|
| `b65a4e321` | kaart naar `doing/` |
| `b2c2061b6` | Fixpunt: de waarde bij het maken naast een afgeleide waarde (`CaseSession.making`, `Executor`, `EvaluationFault`, `Limit`, `DecisionCore`, `FixpointGivens`, `ScenarioRunner`, `ScenarioData`, `OpenApiEmitter`, `playground.html`); tests in `TimelineStackHttpTest`, `DetailHttpTest`, `TimelineHttpTest` |
| `de6d2f100` | README en `docs/ENGINE.md` |
| (deze) | Result |
| (laatste) | kaart naar `done/` |

### Gemeten op main (`logs/02-probe.log`, een proeftest, niet gecommit)

- Tijdlijn (`jutterswet-creatie-per-dag` case1): op de gemaakte melding is de afgeleide tijdlijn al de vereniging van de
  schrijvers met de waarde bij het maken: `{"attribute":"seizoenswaarde","timeline":[{"to":"2025-03-01","value":"7"},
  {"from":"2025-03-01","to":"2025-09-01","value":"20"},{"from":"2025-09-01","value":"7"}],"origin":"derived"}`. Wat
  ontbrak, is wat een object van het verzoek daar wel heeft: `input`, de waarde die de regels vonden, waaraan te zien is
  welk deel van de tijdlijn van het maken komt.
- Scalair (`jutterswet-invoer-naast-objectcreatie`): de melding van J2 heeft `{"attribute":"waarde","value":"99",
  "origin":"derived"}` zonder de 7 van het maken, waar M1 van het verzoek `{"value":"30","origin":"derived","input":"40"}`
  heeft.
- Cel (eigen materiaal: een creatie vult twee cellen, een regel schrijft de cel najaar): `vangstgewicht [najaar] 9
  derived` zonder de 4 van het maken.
- Waar geen regel besliste, stond de waarde bij het maken er al, met de herkomst `creation` (`meldwaarde`, `meldloon`,
  de melding van J1 met `waarde 7`).

### Wat er veranderde

- **De waarde bij het maken in het fixpunt.** Een gemaakt object heeft geen invoer; op die plaats staat wat de creatie
  op zijn bean zette (rang 2 van uchars stapel, RS ch. 7.8). `FixpointGivens` geeft die waarde nu in de vorm van de
  invoer van een object van het verzoek (dezelfde code, `attributes`): zonder afgeleide waarde een rij met de herkomst
  `creation`, zoals voorheen; naast een afgeleide waarde `input` met `inputOrigin: "creation"`, scalair, op een tijdlijn
  als haar segmenten en op een cel. Oud → nieuw, `seizoenswaarde` van de gemaakte melding van case1:
  `{…,"origin":"derived"}` → `{…,"origin":"derived","input":[{"value":"7"}],"inputOrigin":"creation"}`; de `waarde` van
  de melding van J2: `{"value":"99","origin":"derived"}` → `{"value":"99","origin":"derived","input":"7",
  "inputOrigin":"creation"}`. Een object van het verzoek verandert niet.
- **Waar de waarde gelezen wordt.** De terugzetting vervangt de waarde van elk lid dat een regel besliste op dezelfde
  bean (scalair: 99 op de bean; tijdlijn: een nieuwe lijst met de vereniging; cel: de afgeleide cel in dezelfde kaart,
  gemeten met mutatie M6). uchars `Fixpoint.MadeObject` zegt dat de waarden van het maken aan het fixpunt op de bean
  staan, dus vóór de terugzetting. `CaseSession.making(build, fixpoint)` leest ze daar, per gemaakt object en attribuut,
  de kaart van cellen gekopieerd: in de dienst in de `Executor` direct na `fixpointOf` (en meegegeven aan
  `EvaluationFault` en `Limit`, zodat een 422 met `?fixpoint=true` hetzelfde toont), in de speelplaats in de overdracht
  van uchars replay (`CaseRunner.Handoff`, vóór haar terugzetting), naar `CaseSession.Outcome.making`. Voorheen las
  `FixpointGivens` een gemaakt object ná de terugzetting van de bean, en alleen waar geen afgeleide waarde stond.
- **Speelplaats.** De pagina schrijft de `input` van een gemaakt object als `(bij het maken …)`, die van een object van
  de situatie als `(invoer …)`.
- **OpenAPI.** De beschrijving van `values` in de component `Fixpoint` noemt `inputOrigin` (pins hieronder).

### Rood eerst (letterlijk; main `a65ce4704` met de nieuwe tests, `logs/03-red.log`)

27 tests in `TimelineStackHttpTest` en `DetailHttpTest`, 5 failures:

- `TimelineStackHttpTest.the_fixpoint_of_a_made_object_gives_the_value_of_the_making_beside_the_derived_timeline`:
  `but was: <[{"attribute":"meldwaarde","timeline":[{"value":"7"}],"origin":"creation"}, …,
  {"attribute":"seizoenswaarde","timeline":[{"to":"2025-03-01","value":"7"},{"from":"2025-03-01","to":"2025-09-01",
  "value":"20"},{"from":"2025-09-01","value":"7"}],"origin":"derived"},{"attribute":"seizoensloon","timeline":[{"from":
  "2025-01-01","to":"2025-03-01","value":"10"},{"from":"2025-03-01","to":"2025-09-01","value":"20"}],"origin":"derived"},
  …]>`.
- `TimelineStackHttpTest.without_a_value_of_the_making_the_fixpoint_gives_no_input` (case2): dezelfde `seizoenswaarde`
  zonder `input`.
- `TimelineStackHttpTest.the_playground_gives_the_value_of_the_making_in_the_same_fixpoint`: de speelplaats gaf
  hetzelfde fixpunt als de dienst, zonder `input`.
- `DetailHttpTest.beside_a_derived_value_on_a_made_object_the_value_of_the_making`: `expected:
  <[{"attribute":"waarde","value":"99","origin":"derived","input":"7","inputOrigin":"creation"},…]> but was:
  <[{"attribute":"waarde","value":"99","origin":"derived"},…]>`.
- `DetailHttpTest.beside_a_derived_cell_on_a_made_object_the_value_of_the_making`: `… but was: <[…,
  {"attribute":"vangstgewicht","labels":["najaar"],"value":"9","origin":"derived","unit":"kg"}]>`.

Groen op main, en bedoeld als bewaking van de weg na de bouw: `DetailHttpTest.a_422_gives_the_value_of_the_making_of_a_made_object`
en `TimelineHttpTest.a_limit_on_a_made_object_gives_the_value_of_the_making_in_the_fixpoint` (zonder terugzetting
stond de waarde bij het maken op de bean); ze worden rood als de 422 of de grens de waarden niet meekrijgt (M7, M8).

### Mutaties (niet gecommit; `logs/05-mutations/`, `logs/06-mutations/`, `logs/08-mutations/`)

| mutatie | rood |
|---|---|
| M1 geen `input` naast een afgeleide waarde op een gemaakt object | 4: de drie van `TimelineStackHttpTest`, `beside_a_derived_value_…` |
| M2 geen `inputOrigin` | 5: de drie van `TimelineStackHttpTest`, `beside_a_derived_value_…`, `beside_a_derived_cell_…` |
| M3 een cel zonder de waarde bij het maken | `beside_a_derived_cell_…` |
| M4 de dienst leest de waarden na de terugzetting | 5 (als M2) |
| M5 de speelplaats krijgt geen waarden | `made_objects_like_the_playground`, `the_core_without_http_gives_the_form_of_the_playground`, `the_playground_gives_…` |
| M6 de kaart van cellen niet gekopieerd | `beside_a_derived_cell_…` (de terugzetting zet de 9 in dezelfde kaart) |
| M7 de 422 zonder waarden (`Map.of()`) | `a_422_gives_the_value_of_the_making_of_a_made_object` |
| M8, M8b de grens zonder waarden (`null`, `Map.of()`) | `a_limit_on_a_made_object_gives_the_value_of_the_making_in_the_fixpoint` (vóór die test overleefde M8) |
| M9 een gemaakt object zonder afgeleide waarde heet `input` | 4: de drie van `TimelineStackHttpTest`, `beside_a_derived_value_…` |

M6b, de lijst van een tijdlijn niet gekopieerd, overleefde: de terugzetting zet een eigen lijst op de bean. De kopie van
de lijst is daarom weg; alleen de kaart van cellen wordt gekopieerd.

### Tests

Geen bestaande test of verwachting veranderd. Nieuw: `TimelineStackHttpTest` 3 (case1 en case2 van
`jutterswet-creatie-per-dag` door de dienst met `?fixpoint=true`, en de speelplaats op een scenario van case1, met
hetzelfde fixpunt), `DetailHttpTest` 3 (scalair op `jutterswet-invoer-naast-objectcreatie`, de cel op eigen materiaal,
de 422 van een schrijversconflict op die cel), `TimelineHttpTest` 1 (een grens, DROOLS-312, op een gemaakt object).
Testgevallen per module 188/485/54 → 188/492/54; de vloeren van `tools/check-skips.sh` onaangeroerd.

### Pins (oud → nieuw)

- De vier OpenAPI-pins (`service/src/test/resources/openapi/toka-belastingdienst.golden.json`,
  `jutterswet-vocabulaires.golden.json`, `.cgm.golden.json`, `.handboek.golden.json`), de beschrijving van `values` in
  `Fixpoint`: `"… value (of timeline), origin (input, derived of creation), input (bij een afgeleide waarde de
  invoerwaarde die zij verving) en unit."` → `"… value (of timeline), origin (input, derived of creation: op een gemaakt
  object de waarde bij het maken), input (bij een afgeleide waarde de invoerwaarde die zij verving, op een gemaakt object
  de waarde bij het maken), inputOrigin (creation naast de waarde bij het maken) en unit."`
- De pins van de gates: ongewijzigd (submodule `74119a23e`, `EXPECTED_REPLAY_PLAYED` 338, `REPLAY_EXCLUDED` 9, de
  vloeren van de replay).

### Voorlopige besluiten

- VB-1: `input` op een gemaakt object is de hele waarde bij het maken, zoals `input` op een object van het verzoek de
  hele invoer is (van de bean vóór het vuur), niet alleen de dagen waarop zij bleef staan; die dagen noemt de uitleg al
  (uit `PeriodDecisions.Span.input`). Zo staat zij ook naast een scalair en een cel die een regel verdrong (de melding
  van J2: `99`, `input` `7`), en leest een consument de stand op een dag op beide soorten objecten op dezelfde manier.
- VB-2: de herkomst van de waarde naast een afgeleide waarde staat onder een eigen sleutel, `inputOrigin: "creation"`,
  alleen op een gemaakt object en alleen naast `input`; `origin` blijft de herkomst van de getoonde waarde (`derived`).
  Een object van het verzoek krijgt geen `inputOrigin`, zodat daar niets verandert (acceptatie b).
- VB-3: de dienst leest de waarden bij het maken bij elk verzoek met gemaakte objecten, ook zonder `?fixpoint=true`:
  de `Executor` kent de vlaggen niet, en het is één getter per attribuut per gemaakt object.

### Vragen

1. uchars `Fixpoint.MadeObject` draagt geen waarden: de toolchain leest de waarde bij het maken van de bean aan het
   fixpunt, vóór de terugzetting, en moet de kaart van cellen kopiëren omdat de terugzetting een afgeleide cel in
   dezelfde kaart zet. Zou `Fixpoint.MadeObject` (of `Fixpoint`) de waarden bij het maken zelf moeten geven, zoals
   `Fixpoint.values` de afgeleide geeft, zodat een consument niet op het moment van lezen hoeft te letten? Geen
   blokkade: de API volstaat.

### make gates

Eén run in de worktree op `de6d2f100` (`werk/fixpoint-creation-value/logs/gates-1.log`); de commits daarna raken
alleen deze kaart.

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m15s  testgevallen per module 188/492/54
gates: 3 twins      GROEN      3s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m27s  338 goldens, 964 cases (pin 338)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     37s  decision-service:dev
gates: 7 kind-smoke GROEN   1m21s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 5m40s; de uitvoer staat in out/gates/
```
