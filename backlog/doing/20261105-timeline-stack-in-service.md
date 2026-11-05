# timeline-stack-in-service: pin naar uchar 74119a23e, de stapel per dag in de dienst en de uitleg

*Opgesteld door de hoofdsessie op 2026-11-05, na uchar's kaarten `reading-and-uniqueness-residue` en
`timeline-initialisation-allocation-creation` (uchar `9df9df9ab`, `85d90f25d`, `74119a23e`).*

## Aanleiding

Sinds uchar `timeline-initialisation-allocation-creation` lezen alle uitvoerders, ook beide Drools-branches, de schrijvers van een
tijdsafhankelijk attribuut per dag in één stapel: regels, dan de invoer of de waarde bij het maken, dan de initialisatie, die de dagen vult
die de rest leeg laat, over alle tijd. Een verdeling met een tijdsafhankelijk attribuut is niet welgevormd (WF-128; in de frontend RGS-378,
in Drools DROOLS-315 als contract). Nieuwe Drools-code DROOLS-316 (een waarde op een tijdlijndoel die geen periode-rekenkunde of constructor
van een literal is); DROOLS-118 is `RETIRED`. Uit `reading-and-uniqueness-residue`: EF-016 noemt een verdeling bij haar naam, en een lezing
per periode draagt de fout die zij doorgeeft.

## Goal

- De pin naar uchar `74119a23e`.
- De dienst en de uitleg dragen de stapel per dag: een initialisatie en een waarde bij het maken op een tijdsafhankelijk attribuut in het
  antwoord, en in de uitleg per periode wie besliste (een regel, de invoer, de waarde bij het maken, de initialisatie). Wat uchar's
  `periodDecisionsOf` daarvoor geeft, lees je; een API die ontbreekt, wordt een vraag.
- De nieuwe codes (DROOLS-315, DROOLS-316) en de gepensioneerde (DROOLS-118) in wat de dienst met een weigering doet.

## Acceptance

- (a) Rood eerst: de nieuwe goldens van uchar (`jutterswet-initialisatie-per-dag`, `jutterswet-creatie-per-dag`,
  `jutterswet-afwezigheidsmarkers-initialisatie`) door de replay en met uitleg: vóór de bouw een uitsluiting, een fout of "niet vast te
  stellen", daarna de pinnen van uchar en een uitleg per periode.
- (b) De replay speelt die goldens (uit `REPLAY_EXCLUDED`, `EXPECTED_REPLAY_PLAYED` precies met het aantal dat speelt), of noemt ze met reden.
- (c) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh` (lijst en pin), `scripts/service_replay.py`,
README, `docs/ENGINE.md`, kaartverplaatsing. Een API die uchar mist, wordt een vraag, geen wijziging in uchar.

## Omvang

Klein tot middel.

## Result

*Bouwer, 2026-11-05. Branch `card/timeline-stack-in-service`, niet gemerged, niet gepusht. Logs onder
`werk/pin-74119a23/logs/` (buiten de repo), scripts onder `werk/pin-74119a23/scripts/` (`probe_explanation.py`,
`run-tests.sh`, `mutations.py`), de proef van de weigeringen onder `werk/pin-74119a23/probes/refusals/`.*

### Commits

| hash | inhoud |
|---|---|
| `574535fa2` | kaart naar `doing/` |
| `f355a7918` | Pin uchar 74119a23: de stapel van een tijdlijndoel per dag (de pin van de replay, README) |
| `a745a3dab` | Uitleg: op een gemaakt object de waarde bij het maken (`Verbaliser`); `ExplanationStackPerDayTest`, `TimelineStackHttpTest` |
| `5c065a79b` | README en `docs/ENGINE.md` |
| (deze) | Result |
| (laatste) | kaart naar `done/` |

### De pin (uchar `9c275fd8b` → `74119a23e`)

Nieuw voor de dienst: `timeline-initialisation-allocation-creation` (stap A en B). Op een tijdlijndoel lezen alle
uitvoerders, ook beide Drools-takken, de stapel van ch. 7.8 per dag: de schrijvers beslissen hun dagen, de invoer of
de waarde bij het maken staat op de andere, de initialisatie vult wat leeg blijft, over alle tijd. WF-128 (een
verdeling met een tijdsafhankelijk attribuut; RGS-378, DROOLS-315 als `contract`), DROOLS-316 (`refusal`: een waarde op
een tijdlijndoel die geen periode-rekenkunde en geen constructor van een literal is), DROOLS-118 `RETIRED`.
`reading-and-uniqueness-residue` zat al in `9c275fd8b` (gemeten: `9df9df9ab` is voorouder) en kwam met de vorige pin
mee. De dienst compileert op de pin zonder wijziging; gate 12 van uchar speelt 347 goldens (was 344), en de drie nieuwe
spelen door de dienst, met uitleg, zonder "niet vast te stellen". `make gates-no-docker` op de pin
(`logs/gates-0-pin.log`): groen, reactor 182/475/54, replay 338 goldens en 964 cases, 5043 zinnen.

### Wat er veranderde

- **Antwoord.** Een model met een initialisatie van een tijdsafhankelijk attribuut, of met een creatie die er een
  waarde aan geeft, wordt gediend en per dag beantwoord. Oud → nieuw (uchar's pin, gemeten met de jar van de oude pin,
  `logs/15-probe-old-pin.log`): de dienst startte niet, exit 3 `MARKER: regel 'standaardstrandloon' … transpiler SKIP:
  regel: standaardstrandloon — een initialisatie van het tijdsafhankelijke attribuut 'strandloon' van Jutter: deze tak
  draagt deze vorm nog niet; …` (DROOLS-118; idem `standaardaanvulloon` in `jutterswet-creatie-per-dag`, en DROOLS-103,
  "een tijdsafhankelijk doel met een tweede schrijver", in `jutterswet-afwezigheidsmarkers-initialisatie`) → 200 met de
  pinnen van uchar.
- **Uitleg per periode.** uchar's `periodDecisionsOf` noemt de initialisatie op haar dagen onder de regels; de
  `Verbaliser` gaf haar die perioden al met de zin van een initialisatie (`"… en 5 tot 01-01-2025 en 5 vanaf 01-06-2025
  volgens de regel 'standaardvoorjaarsloon', omdat de voorjaarsloon in de invoer leeg was; de invoerwaarde 10 van
  01-01-2025 tot 01-03-2025 bleef staan"`), en `; verdrongen: de regel '…'` waar regels en invoer haar geen dag laten.
  Geen codewijziging daarvoor; nu met getuigen.
- **De waarde bij het maken** (de codewijziging, `a745a3dab`). Een gemaakt object heeft geen invoer: waar geen schrijver
  besliste, staat de lidwaarde die de creatie gaf (rang 2 van ch. 7.8). Oud → nieuw: `"…; de invoerwaarde 10 van
  01-01-2025 tot 01-03-2025 bleef staan"` → `"…; de waarde bij het maken 10 van …"`; `"houdt als meldwaarde de
  invoerwaarde 7"` → `"houdt als meldwaarde de waarde bij het maken 7"`; `"omdat de aanvulloon in de invoer leeg was"` →
  `"omdat de aanvulloon bij het maken leeg was"`; per periode, op een scalair doel, op een cel en in de zin van een
  defaults-groep (`standingValue`, `emptyBefore`, `madeObject` in `Verbaliser`). Op een object van het verzoek
  verandert niets.
- **Weigeringen.** De dienst behandelt elke weigering van uchar's build gelijk (een marker met uchar's zin, de
  constructie uit `transpiler SKIP: <soort>: <naam> — …`); geen codewijziging. Gemeten en vastgelegd: DROOLS-316 is een
  marker die de regel noemt (strikt exit 3, met `--allow-markers` 503 met de zin); een verdeling met een
  tijdsafhankelijk attribuut weigert uit RegelSpraak de start met de resolverfout RGS-378 (`resolve-fout: verdeling
  '…' noemt het tijdsafhankelijke attribuut 'bergingsaandeel' van Berger: … (WF-128)`), en uit een `uchar-ir-v1`-document,
  dat de dienst niet verifieert, is zij een marker met de zin van DROOLS-315 (`… en het document is niet welgevormd
  (IR-SPEC WF-128)`). Oud (jar van de oude pin, `logs/16-refusals-old-pin.log`): dezelfde initialisatie gaf de zin van
  DROOLS-118, het document de zin van DROOLS-271 ("op een plek die één waarde leest of schrijft"). DROOLS-118 noemt
  niemand in de toolchain (gezocht).
- **Replay.** `EXPECTED_REPLAY_PLAYED` 335 → 338; `REPLAY_EXCLUDED` ongewijzigd (9); `scripts/service_replay.py`
  ongewijzigd.

### Rood eerst (letterlijk)

- Op de pin, vóór de wijziging van de `Verbaliser` (`logs/09-red-pin.log`, `logs/10-red-pin.log`): toolchain
  `ExplanationStackPerDayTest` 6 tests, 5 failures, o.a. `on_a_made_object_with_one_value_the_value_of_the_making_is_named_so`
  `expected: <[… «"meld de vangst"»(J1)#1 houdt als meldwaarde de waarde bij het maken 7, …]> but was: <[…
  «"meld de vangst"»(J1)#1 houdt als meldwaarde de invoerwaarde 7, …, «"meld de vangst"»(J1)#1 heeft als opslag 3
  volgens de regel 'standaardopslag': 3, omdat de opslag in de invoer leeg was, …]>`; service `TimelineStackHttpTest`
  10 tests, 1 failure, `the_explanation_of_a_made_object_names_the_value_of_the_making_per_period` (`… de invoerwaarde
  7 tot 01-03-2025 en 7 vanaf 01-09-2025 bleef staan …`). Groen op de pin, want de pin leverde het: de initialisatie
  per periode, de antwoorden van de drie goldens, hun uitleg op objecten van het verzoek, de weigeringen.
- Op de oude pin `9c275fd8b` met de nieuwe tests (`logs/13-red-old-pin.log`, de submodule tijdelijk terug, daarna weer
  op `74119a23e`): toolchain 3 failures `expected: <PASS> but was: <MARKER>`: `transpiler SKIP: regel:
  standaardaanvulloon — an initialisation of the time-dependent member 'aanvulloon' of Melding: this branch does not yet
  carry this form; …` (DROOLS-118), `regel: voorjaarsloon — a time-dependent target with a second writer …` (DROOLS-103),
  `objectcreatie: meld de jutter — the time-dependent attribute 'toelage' of Melding at a place that reads or writes one
  value: …` (DROOLS-271); service: de klasse faalt in `@BeforeAll`, `NoSuchFileException: …/jutterswet-initialisatie-per-dag/source.rgs`
  (de goldens bestaan op die pin nog niet); daarom de dienst zelf met de jar van de oude pin over de goldens van uchar's
  hoofdcheckout (`logs/15-probe-old-pin.log`, hierboven) en over de twee weigeringen (`logs/16-refusals-old-pin.log`).
- De replay vóór de bouw (op de pin, `logs/replay-0-pin.log`): de drie goldens speelden al, met uitleg en zonder "niet
  vast te stellen"; de enige rode regel was de pin, `338 goldens speelden, EXPECTED_REPLAY_PLAYED pint 335`. Acceptatie
  (a) "vóór de bouw een uitsluiting": op de oude pin stonden ze niet in de set van gate 12 (EXCLUDE DROOLS-118/103) en
  startte de dienst er niet op.

### Mutaties (niet gecommit; `logs/12-mutations/`, `logs/12-mutations.log`)

| mutatie | rood |
|---|---|
| M1a de zin van een tijdlijndoel: `; de invoerwaarde` | `on_a_made_object_the_value_of_the_making_stands_per_period_…`, `TimelineStackHttpTest.the_explanation_of_a_made_object_…` |
| M1b wat een tijdlijndoel houdt: `de invoerwaarde` | `on_a_made_object_the_value_of_the_making_stands_per_period_…` (meldloon) |
| M1c wat een scalair doel houdt | `on_a_made_object_with_one_value_…` |
| M1d wat een cel houdt | `on_a_made_object_a_cell_keeps_the_value_of_the_making` |
| M1e een periode van een stapel zonder beslisser | `a_defaults_group_on_a_made_object_names_the_value_of_the_making_…` |
| M2a de initialisatie van een regel: `in de invoer leeg was` | 3 tests (aanvulloon, opslag, de dienst) |
| M2b de initialisatie in een stapel | `the_initialisation_of_a_stack_on_a_made_object_names_the_making` |
| M3 `madeObject` altijd onwaar | 6 tests |
| M4 `madeObject` altijd waar | 3 tests (de initialisatie per periode met invoer, beide uitlegtests van de dienst op een object van het verzoek) |
| M5 `UcharBuild.notLowered` geeft geen constructie | de marker van DROOLS-316 en die van DROOLS-315 uit een document |

### Tests

Geen bestaande test of verwachting veranderd. Nieuw: `ExplanationStackPerDayTest` (6, eigen materiaal: de
initialisatie per periode naast regel en invoer, met voorwaarde en verdrongen; de waarde bij het maken op een
tijdlijndoel, op een scalair doel, op een cel, in een defaults-groep per dag en in de stapel van de opgave-markering) en
`TimelineStackHttpTest` (10: de pinnen van de drie goldens door de dienst, hun uitleg, DROOLS-316 strikt en met
markers, RGS-378, DROOLS-315 uit een document). Testgevallen per module 182/475/54 → 188/485/54; de vloeren van
`tools/check-skips.sh` onaangeroerd.

### De pins van de toolchain-gates (oud → nieuw)

- uchar-submodule: `9c275fd8b` → `74119a23e`.
- De set van `check-service-replay` (uchar's `EXPECTED_PLAYED`): 344 → 347.
- `EXPECTED_REPLAY_PLAYED`: 335 → 338 (pin: `jutterswet-initialisatie-per-dag`, `jutterswet-creatie-per-dag`,
  `jutterswet-afwezigheidsmarkers-initialisatie`; op de kaart geen verdere verandering).
- `REPLAY_EXCLUDED`: 9 → 9. `EXPLANATION_UNDETERMINED`: leeg → leeg.
- Vloeren van de replay onaangeroerd (`EXPECTED_MIN_EXPLAINED_CONDITIONS` 1241, `…_COMPUTATIONS` 1852,
  `EXPECTED_MIN_READ_LINKS` 4248); gemeten 5043 zinnen, 3461 voorwaarden,
  2821 berekende waarden, 5968 gelezen waarden met hun zin (op de pin dezelfde getallen).

### Voorlopige besluiten

- VB-1: op een gemaakt object heet wat op de plaats van de invoer staat overal "de waarde bij het maken", ook op een
  scalair doel en een cel, niet alleen per periode: één begrip (ch. 7.8, rang 2), en een verzoek geeft een gemaakt
  object niets. Daarmee veranderde ook de zin van een scalair gemaakt object (`jutterswet-invoer-naast-objectcreatie`:
  `«"meld de vangst"»(J1)#1 houdt als waarde de invoerwaarde 7` → `… de waarde bij het maken 7`); geen test pinde die.
- VB-2: de initialisatie per periode houdt de zin van een initialisatie ("volgens de regel '…', omdat de X in de invoer
  leeg was", of haar voorwaarde met de perioden), zoals op een scalair doel; geen eigen woord voor "de laagste laag".
- VB-3: een `contract`-weigering van de build (DROOLS-315) blijft een marker, zoals elke weigering van uchar's build:
  de dienst verifieert een document niet en onderscheidt de soorten van uchar's register bij de start niet. Met
  `--allow-markers` start de dienst dus op een niet-welgevormd document, met dat entrypoint als marker.
- VB-4: `initialisationNotDecided` ("had een invoerwaarde") blijft ongewijzigd: op een gemaakt object is die zin uit
  RegelSpraak onbereikbaar (de frontend weigert een creatie die een lid met de opgave-markering zet, WF-050, en een
  initialisatie naast een ongeordende groep), dus zonder getuige geen wijziging.

### Vragen

Geen. uchar's API volstond: `periodDecisionsOf` noemt de initialisatie onder de regels en de waarde bij het maken als
`input` van een periode, en `Fixpoint.objects` zegt welk object gemaakt is.

### Open punten

- Een schrijver van een tijdlijndoel waarvan de voorwaarde per periode op geen dag geldt, heeft de lezing `HOLDS` met
  lege perioden; de uitleg zegt dan `"; de regel 'standaardstormloon' gaf geen waarde en besliste niets"`
  (`jutterswet-initialisatie-per-dag` case3) of `"… omdat hij stormwacht is (altijd leeg)"` (al gepind in
  `TimelineHttpTest`). Dat geldt voor elke regel, niet alleen voor een initialisatie; een eigen zin ("gold op geen dag")
  is een eigen kaart.
- Het fixpunt (`?fixpoint=true`) geeft op een gemaakt object een afgeleide tijdlijn zonder de waarde bij het maken die
  zij deels liet staan (een object van het verzoek heeft daar `input`).

### make gates

Eén run aan het eind in de worktree op `5c065a79b` (`werk/pin-74119a23/logs/gates-1.log`); de commits daarna raken
alleen deze kaart.

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m23s  testgevallen per module 188/485/54
gates: 3 twins      GROEN      3s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m29s  338 goldens, 964 cases (pin 338)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     41s  decision-service:dev
gates: 7 kind-smoke GROEN   1m17s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 5m50s; de uitvoer staat in out/gates/
```
