# onopgeloste-naam-weigeren: `check` en `serve` weigeren een bron met een onopgeloste naam

*Vastgelegd door de hoofdsessie op 2026-10-13, na het besluit van de eigenaar op het open punt van de kaart
`20261010-sessie-via-de-ingang-van-uchar` ("ja, moet inderdaad al bij check of serve geweigerd worden").*

## Aanleiding

Een naam die de resolver van uchar niet aan een declaratie kan binden, laat zij als `Unresolved`-knoop in het IR staan,
met een WARNING. Een voorbeeld: "hij een minderjarig is" bij een bijvoeglijk kenmerk `minderjarig`, waar "hij is
minderjarig" hoort.
- **Vóór de pin op uchar `b536bbc9`** weigerde de oude exec-model-tak zo'n regel bij de bouw. `check` gaf exit 3
  (MARKER), en `serve` startte niet in strikte modus.
- **Sinds die pin** draagt de tak met truth maintenance de regel en faalt die pas bij de evaluatie (WF-044), net als
  ref. `check` geeft exit 0 met een WARNING, de dienst start, en elk verzoek dat de regel bereikt, krijgt een 422
  `COMPUTE_FAULT`. Een tikfout in een regel merkt de afnemer zo pas bij een verzoek.

## Wat er gebeurt

`check` en `serve` weigeren een bron, of een IR-document, waarin een `Unresolved`-knoop staat. Ze noemen de naam en de
regel. De weigering hangt aan het IR, niet aan de tekst van een WARNING. Zo geldt ze ook voor een IR-document dat de
dienst als bron krijgt.
- `check`: een exitcode als bij een resolverfout, met de naam, de regel en de plaats.
- `serve`: start niet, ook niet met `--allow-markers`. Een onopgeloste naam is een fout in de bron, geen vorm die uchar
  nog niet draagt.

De semantiek van uchar verandert niet: ref en de backends laten een onopgeloste naam bij de evaluatie falen (WF-044).
Dit is een poort van de distributie.

## Acceptatie

- (a) Rood eerst: `check` en `serve` op een bron met de een-vorm hierboven, en op een IR-document met een
  `Unresolved`-knoop. Eerst exit 0 en een start, daarna een weigering met de naam.
- (b) De getuige in `ScenarioOracleTest` die de fout bij de evaluatie pint (sinds `ac4c3ac`), en de docs, volgen.
- (c) `make gates` groen.

## Omvang

Klein. May touch: `toolchain/` (CLI `check`, `UcharBuild` of een eigen IR-toets), `service/` (de start van de dienst),
`service-quarkus/` voor de start, de tests, `README.md` en `docs/ENGINE.md`.

## Result

*Gebouwd op 2026-11-06 in de branch `card/scope-check-and-cleanup`. Logs in `uchar-dev/werk/toolchain-cleanup/logs/`.*

De weigering hangt aan het IR-document: `UcharBuild.assemble(JsonNode, …)` loopt het document af en bouwt geen document
met een knoop `"node": "Unresolved"` (expressie- en voorwaardemarker). De weigering is `UcharBuild.UnresolvedName`,
geen `NotCarried`, zodat `--allow-markers` haar niet toelaat. Per knoop: de naam (het snippet), de regel (de naam van
de vorm onder `/rules/<lijst>/<i>`) en de plaats: de bronregel van de diagnose die het snippet citeert (WF-113 (b)),
of, als geen of meer dan één regel het citeert, de JSON-pointer. Elke weg naar een KieBase gaat door `UcharBuild`,
dus de weigering geldt voor `check` en `drl` (exit 1, als een resolverfout, met de regel van de bron in een keten van
bestanden), de engine (`RegelSpraakCompileException`), de dienst uit bronnen en uit een document (`MALFORMED` op
`source`, ook met `--allow-markers`) en de playground (`executable: false`, de zin als `refusal` en `drlError`). De
semantiek van uchar en de scenarioroute (uchars replay) veranderen niet.

- (a) Rood eerst (`logs/c2-red.log`): `CheckCommandTest` (`check` en `drl` via `bin/regelspraak`: exit 0),
  `StartupResolveTest.an_unresolved_name_in_a_source_…` en `…_in_a_document_…` (strikt en met markers: de dienst
  start) en `RegelSpraakEngineTest.an_unresolved_name_refuses_at_compiling` (geen weigering). Groen
  (`logs/c2-green.log`, `logs/c2-reactor-2.log`: toolchain 197, service 501, service-quarkus 54, geen skip). Met de
  hand op de een-vorm uit de kaart: `check-before.log` exit 0 met alleen de WARNING; `check-after.log` "ERROR (line
  10): onopgeloste naam 'hij een minderjarig is' in de regel 'bijvoeglijk'", exit 1; `serve-after.log` (bron) en
  `serve-document-after.log` (het IR-document), beide met `--allow-markers`: "dienst start niet: de bron draagt een
  onopgeloste naam … MALFORMED [source]: … bronregel 10", exit 1.
- (b) De getuige in `ScenarioOracleTest.characteristic_nominal_from_characteristic` volgt: de Java-API-tak bouwt via
  `UcharBuild` en krijgt nu de weigering met naam, regel en bronregel 10; de scenariotak (uchars replay) faalt nog
  steeds bij de evaluatie met de `ComputeFault` van WF-044. Docs: `README.md` (`check`, `drl`, de replay-gate) en
  `docs/ENGINE.md` (de engine, de start van de dienst). Twee tests veranderd omdat het gedrag verandert:
  `PlaygroundDrlRulesTest` (twee corpusvegen slaan nu ook een document over dat de distributie niet bouwt, zoals een
  weigering van uchar; de vloeren houden) en de replay-gate: `unresolved-behind-a-failing-condition` (een met de hand
  gebouwd document met drie Unresolved-knopen) speelt niet meer door de dienst. `service_replay.py` meldt een start
  die op een onopgeloste naam weigert als `EXCLUDE` met de weigering (gemeten: "EXCLUDE de dienst start niet op een
  onopgeloste naam: …"), `REPLAY_EXCLUDED` noemt de golden met reden, `EXPECTED_REPLAY_PLAYED` 338 → 337.
- (c) `make gates` GROEN op 21ade915f, de eindstand van de branch (`uchar-dev/werk/toolchain-cleanup/logs/gates-final.log`,
  7 stappen in 6m26s): pin; reactor 197/502/57 testgevallen; twins 3 tweelingen, 18 cases; replay 338 goldens (pin 338),
  968 cases (pin 968); manifest 32 regels, 29 ok, 3 uitgesloten, 0 mismatch; image; kind-smoke H2, H3, H4 PASS.

Een technische keuze, vastgelegd: de dienst toetst de documenten die hij bouwt, dus de scope van elk entrypoint (uit
bronnen) of het hele document. Een onopgeloste naam in een vorm die geen entrypoint draagt, wordt niet gebouwd en
weigert de start niet; een verzoek kan die vorm niet bereiken, en `check` toetst de hele bron.
