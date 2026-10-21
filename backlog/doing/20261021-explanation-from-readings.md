# explanation-from-readings: de uitleg rendert uit de lezingen van de motor

*Vastgelegd door de hoofdsessie op 2026-10-21, kaart T2 uit het Fable-advies over de waarden in de uitleg
(`research/uitleg-lezingen-2026-10-21/fable-advies-2026-10-21.md` in `uchar-dev/research`, §3, §4, §6, §7). Besluit van de
eigenaar 2026-10-21: weg C, de motor levert de waarden. Volgt op uchar-kaart `fixpoint-readings` met een pin-bump. Sluit
`backlog/new/20261012-verbalisator-getalliteral.md`.*

## Doel

De Verbalisator rendert de voorwaarden uit `DroolsBackend.readingsOf`; zijn eigen evaluatie (`evaluate`, `evalExpr`,
`compare`, `compute`, `holds`, `firstFailingConjunct`, `literalValue`/`parseNumber`) verdwijnt. De zinsvormen blijven:
"omdat <voorwaarde met waarden>", "gold niet, want niet geldt: <het lid waar de motor stopte>", "(onbekend)" voor een
lege waarde (besluit: "(onbekend)" blijft; "niet vast te stellen" verdwijnt voor wat de motor draagt). Een vorm buiten
de scope van een entrypoint krijgt één zin "<regel> staat buiten deze berekening". De dienst bouwt de uitlegbuild bij de
start, achter een descriptorvlag die standaard aan staat. Een kenmerkcheck over een rol op het model van de lader krijgt
haar tekst via een tweede methode op `Walks`.

Twee builds (besluit van de eigenaar, 2026-10-21): de productiebuild zonder reading rules, de uitlegbuild met. Alleen
als het de code niet ingewikkelder maakt (eigenaar, 2026-10-21): een verzoek met `explanation=true` vuurt één keer, in
de uitlegbuild, en geeft daaruit zowel antwoord als lezingen (getuige (a) van `fixpoint-readings` bewijst dat de
productiefeiten daar gelijk zijn); en de scenario-runner leest de sessie die uchars replay doorgeeft in plaats van een
case een tweede keer te spelen. Maakt een van beide de code ingewikkelder, dan niet doen en de reden in de Result
zetten.

## Acceptatie

- (a) Rood eerst: per vormfamilie van `fixpoint-readings` een zin, op de drie routes.
- (b) Elke bestaande uitlegtest ongewijzigd groen, behalve de benoemde pins (een operand die de motor uitrekent en de
  uitleg nu "(onbekend)" of "niet vast te stellen" noemt; een getalliteral in de notatie en eenheid van het model), elk
  in de Result.
- (c) Bewijs op v2 vóór/na: "niet is vast te stellen" van 80 naar 0 op de vormen die de motor draagt, "(onbekend)" alleen
  waar de waarde leeg is (per overgebleven geval de reden), elke oude zin letterlijk terug behalve de benoemde groepen,
  het antwoord buiten de uitleg gelijk.
- (d) In `scripts/check-service-replay.sh` een leg die over de gespeelde goldens met `explanation=true` telt dat "niet is
  vast te stellen" niet meer voorkomt (redendragende lijst voor wat de motor niet draagt, vloer op het aantal
  uitgelegde voorwaarden).
- (e) `make gates` groen.

May touch: `toolchain/` (Verbalisator, runner, sessie), `service/` (doorgeven en de build-optie), tests, README, de pin,
de kaart.

## Result (2026-10-21)

Tak `card/explanation-from-readings` in de worktree `wt-tc/explanation-from-readings`, vanaf toolchain main `bbce6546`,
Gebouwd door een Opus-bouwer; de hoofdsessie draaide `make gates` en schreef dit Result af uit zijn concept.

- `61b72c47` de kaart naar `doing/`.
- `2487ca0c` de pin op uchar `15f39170` (fixpoint-readings).
- `c90617fc` de uitleg rendert uit de lezingen, met de tests; de kaart `verbalisator-getalliteral` naar `done/`.
- `c2341353` de leg van de uitleg in `scripts/check-service-replay.sh`.
- `c9d52f8a` ongebruikte imports weg uit `ExplanationOriginFromIrTest` (de toolchain-suite opnieuw groen, 148).

### De pin

uchar `26a2fb85` → `15f39170`, `make check-pin` groen. Zonder eigen code: de reactor groen, 1646/147/344/53 (de
uchar-backend 1636 → 1646, de getuigen van de lezingen); de replay-poort 231 goldens en 610 cases, pin 231
(`werk/defaults-decider/t2/logs/verify-pin-1.log`, `replay-pin-1.log`). Niets verschoof.

### Wat er veranderde

- **De Verbalisator** (`toolchain/.../engine/Verbaliser.java`) rekent niets meer: `evaluate`, `evalExpr`, `compare`,
  `compute`, `holds`, `firstFailingConjunct`, `literalValue`, `parseNumber`, `roundingMode` en `globalContext` zijn weg,
  en met hen de globals. Hij neemt `Verbaliser(Document, Naming, readings)`: `Document.of(IrLoader.Positioned)` is wat
  uchars lader met de posities van de knopen las; de lezingen (`DroolsBackend.readingsOf`) legt hij per vorm (de pointer
  van de regelversie, `NodePositions.versionOf`, of van de variant) en object, en per knoop op het nummer van
  `NodePositions.subtree(voorwaarde)`. De zinsvormen blijven: "omdat <voorwaarde met waarden>", "want niet geldt: <het
  lid waar de motor stopte>", "(onbekend)" voor een lege waarde. Nieuw, elk uit een besluit of uit de lezing: een lid of
  operand dat de motor niet bereikte, noemt de zin niet of zonder waarde (normvraag 3); een vorm die de uitleg niet in
  woorden zet (een kwantorvouw) staat als `aan de voorwaarde "…" is voldaan` of `… is niet voldaan`, met het oordeel van
  de motor; een kenmerktoekenning die staat terwijl haar eigen voorwaarde niet gold: ", maar de regel '…' gold niet,
  want …"; een vorm buiten haar geldigheid: "de regel valt op de rekendatum buiten haar geldigheid" (bij een variant:
  "gold niet, want zij valt …"); een kenmerktoekenning buiten de scope van een entrypoint (dienst uit bronnen): één zin
  "de regel '…' staat buiten deze berekening" aan het eind (normvraag 4). Een getal staat met de decimalen van zijn type
  of van de declaratie van het gelezen lid en de eenheid die de motor las; een getalliteral met de decimalen van zijn
  lexeem ("30.00": "30,00 EUR"). Luid: een ontbrekende lezing, een fout of een vorm die niet aan de beurt was waar de zin
  een oordeel nodig heeft, een voorwaarde die volgens de motor geldt waar de zin zegt waarom ze niet gold.
- **De kenmerkcheck over een rol** op het model van de lader heet naar de wandeling die de lader naast de check gaf
  (`Loaded.navigationBindings`): "de jutter ingezetene is". Afwijking van de kaart: niet "een tweede methode op
  `Walks`": `Walks` verviel, want alleen de eigen evaluatie las de wandeling van een lezing, en alle drie de routes leggen
  nu uit op het model van de lader; de wandeling van de check komt uit `Document`.
- **Alle routes leggen uit op het document van de build zoals de lader het las.** De dienst uit bronnen legde uit op het
  model van de frontend; nu laadt hij het document dat de emitter van de scope schreef met posities, en noemt hij de
  kenmerktoekenningen van de bron die de scope niet draagt (`Document.withOutside`).
- **De dienst** (`DecisionCore`, `Executor`, `ServiceDescriptor`): per entrypoint dat uitlegt (descriptorvlag
  `explanation`, standaard aan) bij de start de uitlegbuild (`UcharBuild.assemble(document, branch, readings)`) naast de
  productiebuild, en de lading met posities. Zonder vlag één build, en een verzoek met uitleg krijgt een 400
  (`UNSUPPORTED` op `explanation`). README en `docs/ENGINE.md` zeggen het.
- **Voorwaardelijk punt 1, gedaan: één vuring.** Een verzoek met `explanation=true` wordt in de uitlegbuild gemapt,
  gevuurd en beantwoord, en geeft daaruit antwoord en lezingen. Gemeten: het antwoord buiten de uitleg is op v2
  bytegelijk; over de 231 goldens van de replay gelijk op de volgorde van de inconsistenties na, in 13 goldens. Die
  volgorde ligt ook zonder uitleg niet vast: met twee sessies (antwoord uit de productiebuild, uitleg uit een tweede
  sessie) gaf `jutterswet-keurspelling` tussen twee verzoeken op dezelfde productiebuild een andere volgorde. Twee
  sessies maakten de code ingewikkelder zonder dat verschil weg te nemen; dus één vuring, en de leg vergelijkt de
  inconsistenties zonder volgorde.
- **Voorwaardelijk punt 2, gedaan: de scenario-runner leest de doorgegeven sessie.** `ScenarioRunner` roept
  `CaseRunner.report(ir, cases, handoff)` en leest in de afnemer `fixpointOf`, `decisionsOf` en `readingsOf` van de sessie
  die het rapport beoordeelde; de uitleg volgt na de terugschrijving. Weg: `CaseSession.play` met zijn eigen invoer, de
  vergelijking van de vuringen en `valueDifference`; `CaseSession` is alleen nog `Outcome` (zonder globals, met de
  lezingen) en `read`. De typen van de labels komen uit het casebestand.

### Rood eerst (a)

`ExplanationReadingsTest` (scenario-runner) en `ExplanationReadingsServiceTest` (dienst uit bronnen en uit een
IR-document), met een eigen model: per vormfamilie van `fixpoint-readings` één regel. Op de code van de pin met alleen
de tests: de scenario-runner rood (`t2/logs/red-1.log`), de dienst 4 van 5 rood (`red-2.log`; de antwoordgelijkheid was
al groen). Vóór en na, scenario-route (vóór uit `bin/regelspraak scenario --explanation` op de pin):

- samenstelling "alle" die bij lid 2 stopt: "J1 is niet gerechtigd, want niet geldt: het onderwerp in dienst is"
  (gelijk; lid 3 niet bereikt)
- "of"-blok dat gold: vóór "J2 is bijzonder, omdat het onderwerp in dienst is of zijn aantal meldingen (12) is groter
  dan 10", na "J2 is bijzonder, omdat het onderwerp in dienst is"; dat niet gold: vóór `de voorwaarde "…" niet is vast
  te stellen`, na "want niet geldt: het onderwerp in dienst is of zijn aantal meldingen (onbekend) is groter dan 10"
- telling: vóór "… voldoen: hij is waardevol (onbekend) is groter dan 0", na "(1)"
- kwantorvouw over een rol: vóór `de voorwaarde "alle vondsten van de Jutter waardevol zijn" niet is vast te stellen`,
  na `want aan de voorwaarde "alle vondsten van de Jutter waardevol zijn" is niet voldaan`
- vergelijking met een lege operand: na "want niet geldt: zijn aantal meldingen (onbekend) is groter dan 3"
- product met een lege operand: na "want niet geldt: zijn bonus maal 2 (0,00 EUR) is groter dan 10,00 EUR" (vóór "niet
  vast te stellen"; bij J2 vóór "… (15,00 EUR) is groter dan 1.000 EUR")
- herschaalde vergelijking: vóór "zijn afstand tot de kust (700) is kleiner dan 5,0 km", na "(700 m) is kleiner dan 0,5 km"
- tijdsduur: vóór "(onbekend)" of "niet vast te stellen", na "… in hele jaren (30 jr) is kleiner dan 18 jr"
- kenmerkcheck over een rol: vóór `aan de voorwaarde "de jutter van de Vondst ingezetene is" is voldaan`, na "W1 is
  aangemeld, omdat de jutter ingezetene is"
- getalliteral: vóór "zijn premie (60,00) is groter dan 3.000 EUR", na "zijn premie (60,00 EUR) is groter dan 30,00 EUR"
- buiten de scope (dienst uit bronnen): "de regel 'gelukkig' staat buiten deze berekening"

### Aangepaste tests, met reden (b)

- `ExplanationServiceTest.golden_explanation_sentence_minderjarig`: "(onbekend)" → "(9 jr)" voor de tijdsduur (groep 1).
- `ExplanationFollowsCharacteristicKindTest.without_kind_the_condition_stands_like_the_rule_writes_it`: `aan de
  voorwaarde "de samenloop van het Recht een wia-recht heeft" is voldaan` → "de samenloop een wia-recht heeft": de
  kenmerkcheck over een rol op het model van de lader, de opdracht van de kaart.
- Alleen de opzet, geen verwachting: `VerbaliserTest`, `ExplanationGeneratedRuleInVocabularyTest.on_the_model_of_the_frontend…`
  en `ExplanationOriginFromIrTest` bouwden een sessie met `RegelSpraakEngine` en een Verbalisator op het model van de
  frontend; nu een build met lezingen (`ExplanationSession`, testcode). `ExplanationOriginFromIrTest` vergeleek de uitleg
  op het model van de frontend met die op het model uit het IR-document; de eerste route bestaat niet meer, de
  verwachting staat ongewijzigd op de tweede.
- Elke andere uitlegtest ongewijzigd groen.

### Bewijs op v2 (c)

`werk/defaults-decider/bewijs.md`, sectie "Na explanation-from-readings", meting `na3/`: "niet is vast te stellen" 92 →
0 (na2; 80 in na/), "(onbekend)" 121 → 158, alleen waar de lezing leeg is (lijst per operand `t2/onbekend-na3.txt`), het
antwoord buiten de uitleg bytegelijk, van de 81 oude zinnen 49 letterlijk terug en 32 in de twee benoemde groepen (28
groep 1, 4 een "of"-blok dat stopte; `t2/voor-vs-na3.txt`).

### De leg (d)

`check-service-replay.sh` vraagt elk verzoek ook met uitleg (`service_replay.py --explanation`): antwoord buiten de
uitleg gelijk (inconsistenties zonder volgorde), geen "niet is vast te stellen" (`EXPLANATION_UNDETERMINED`, leeg,
veroudering in beide richtingen), vloer `EXPECTED_MIN_EXPLAINED_CONDITIONS=1241`. Gemeten: PASS, 231 goldens, 610 cases,
1092 zinnen, 1241 voorwaarden, 63 s (`t2/logs/replay-leg-2.log`).

### Suites

`mvn -B -o verify` over de eigen modules (uchar-backend gebouwd, zijn suite niet gedraaid; die draaide groen op de pin):
groen, 148/349/53 (was 147/344/53: de zes nieuwe tests), skip-pin PASS (`t2/logs/verify-build-2.log`).

### Gates

`make gates` op `c9d52f8a` met `TAG/CLUSTER=explanation-from-readings`: GROEN, 7 stappen in 11m18s (reactor
1646/148/349/53 in 7m38s; replay 231 goldens, 610 cases, pin 231, uitleg 1092 zinnen en 1241 uitgelegde voorwaarden,
"niet is vast te stellen" bij 0 goldens; manifest 29 ok, 3 uitgesloten; image `decision-service:explanation-from-readings`;
kind-smoke H2–H4 PASS). Log: `werk/defaults-decider/t2/tc-gates.log`.

### Wat open bleef

- De leden van een kwantorvouw (`Contributor`) noemt de zin niet; de vouw staat met haar brontekst en oordeel.
- De volgorde van de inconsistenties in een antwoord ligt niet vast tussen sessies (vondst buiten de kaart). De bron is
  uchar: `TmsTranspiler` geeft `Fixpoint.inconsistencies` in de volgorde van het werkgeheugen, ongesorteerd, en
  `AnswerBuilder` schrijft die lijst over. Kaart in uchar: `backlog/new/20261021-fixpoint-inconsistency-order.md`; bij
  de pin-bump daarna vergelijkt de leg weer in volgorde.
- Uit de kaart `explanation-defaults`, ongewijzigd: het lidwoord van een bezittelijk kenmerk; de graaf en de dekking
  tonen de groepen niet.
