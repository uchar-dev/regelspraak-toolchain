# pin-naar-de-drl-teksttak-op-truth-maintenance: de pin naar uchar `a63ef307`, waar beide Drools-takken één analyse weergeven

*Vastgelegd op 2026-10-14, uit het `Result:` van de uchar-kaart `20261008-drl-teksttak-op-truth-maintenance` ("Voor
de distributie (bij de pin-bump)") en PLAN §9 van uchar. Regelnummers op toolchain `7e9e502` (submodule op uchar
`b536bbc9`).*

## Aanleiding

Op uchar main `a63ef307` (2026-10-14) is de kaart `drl-teksttak-op-truth-maintenance` geland. De Drools-backend heeft
daarna twee takken van één ontwerp. Eén analyse (`transpiler/tms/TmsTranspiler`) maakt per regel een plan, en twee
weergaven bouwen er een regelbank van: `PatternDslWeergave` voor `Branch.EXEC_MODEL` en `DrlWeergave` voor
`Branch.DRL_TEXT`. Beide leiden af met truth maintenance. Wat de analyse weigert, weigert zij voor beide takken in één
zin; de DRL-weergave weigert daarnaast alleen wat DRL-tekst niet kan uitdrukken (label `drl:`). `DrlTranspiler` en
`Stratificatie` zijn weg. Beide takken spelen 255 goldens.

Voor de distributie verandert de API van `DroolsBackend.Build`: het record draagt nu altijd de analyse
(`execModelTranspiler`, nooit null) en op de DRL-teksttak de tekst als veld `drl`. `drlTranspiler()` bestaat niet meer.
Na een pinbump zonder deze kaart compileren de CLI en de playground niet.

## Wat er gebeurt

1. **De pin** naar uchar main `a63ef307`. Daartussen zitten ook de TOKA-vrijgave, de toka-zone zonder "tijdelijk" en
   SUBTYPING §11. Ga na of iets daarvan de toolchain raakt, bijvoorbeeld de pariteit van `examples/toka/*.rgs` met
   uchars `toka/casus/*.rgs`.
2. **`RegelSpraakCli.java:156` en `Playground.java:162`**: `.drlTranspiler().drl()` wordt `.drl()`.
3. **`CaseSessie.java:62–65`**: de bewaking `build.execModelTranspiler() == null` onderscheidt de takken niet meer,
   want de analyse staat nu in elke build. Zoek uit wat de bewaking moet bewaken en doe dat op de nieuwe API.
4. **Een DRL-tekst die niet compileert** gooit sinds uchar `eac6fa5d` een `IllegalStateException` ("een fout van de
   weergave"), geen `Excluded`. Nu geeft de CLI daarbij een stacktrace met exit 1 en de playground HTTP 500. De
   playground meldt haar als `drlFout`, zoals de andere weigeringen in `Playground.analyseer`. De CLI meldt de zin van
   uchar met een exitcode ongelijk aan nul, zonder stacktrace.
5. **`Namen.getter`/`setter`** volgen de beangenerator (`Namen.lidnamen`). Controleer `RolSchrijver`, `Verbalisator`,
   `CaseSessie`, `ScenarioRunner` en `VerzoekMapper`.
6. **De replay.** uchars gate 12 heeft één lijst niet-spelers en één pin `EXPECTED_PLAYED` (255); `EXPECTED_DRL_PLAYED`
   en `DRL_CHANNEL` vervallen. `scripts/check-dienst-replay.sh` volgt: de set van gate 12, de pin
   `EXPECTED_REPLAY_PLAYED` en de lijst `REPLAY_UITGESLOTEN`. Nieuw in uchars corpus zijn vijf goldens
   (`jutterswet-verdeling-dubbelbezet`, `jutterswet-uniciteit-drie-bronnen`, `jutterswet-uitsluiting-gedateerd`,
   `jutterswet-aantalfilter-gehele-jaar` en `jutterswet-aantalfilter-gehele-jaar-creatieguard`, de laatste twee zonder
   `bron.rgs`).
7. **Verder alleen wat de pin breekt**: compileerfouten, verouderde weigerzinnen en tekst die de pin onwaar maakt, de
   vloeren van `tools/check-skips.sh`.

**Gedrag dat met de pin verandert** (uchar-kaart, "Gedragswijzigingen buiten de meting" en "Dekkingsverlies"):
- `Namen.getter`/`setter` geven de namen van de generator: een lid als "İş" wordt gelezen en gezet met `getIş` en
  `setIş`, waar vroeger `getİş` werd gezocht.
- `CaseRunner` zoekt bij een case die een lid in een andere hoofdletter spelt de gedeclareerde naam op, ook in het
  scenariorapport (`werkelijk`).
- `renderIncs` sorteert de inconsistenties: de tekst van een mismatchzin en van `VerwachtingResultaat.werkelijk`
  verandert, geen feit en geen status.
- Twee schrijvers die het eens zijn met een andere schaal (5 en 5.00): de lezer neemt de waarde van de regel die in
  het document eerst komt.
- Een DRL-tekst die de compiler weigert, is een `IllegalStateException`, geen `Excluded`.
- Het DRL-paneel van de playground toont nu ook documenten met defaults.
- Wat alleen de oude DRL-teksttak speelde en de analyse weigert, weigert nu ook de DRL-teksttak (bijvoorbeeld een vouw
  of deling in een ledeninitialisatie van een creatie).

## Acceptatie

- (a) De pin staat op uchar `a63ef307`; `make check-pin` groen.
- (b) Geen `drlTranspiler()` meer in de eigen code; de bewaking in `CaseSessie` bewaakt op de nieuwe API wat ze moet
  bewaken, met de reden in het Result.
- (c) Een DRL-tekst die niet compileert, geeft in de playground `drlFout` met uchars zin en in de CLI uchars zin met
  een exitcode ongelijk aan nul, zonder stacktrace.
- (d) `make gates` groen. De pin en de lijst van `check-dienst-replay` volgen de nieuwe gate 12; elke golden die van
  kanaal wisselt, staat met reden in het script en in het Result. Een vloer die zakt, zakt in dezelfde commit als de
  verwijdering, met de reden.
- (e) Elke testwijziging staat in een eigen commit, met haar reden uit het Result van de uchar-kaart.

## Omvang

Klein. May touch: de submodule `uchar` (alleen de pin), `toolchain/` (CLI, `CaseSessie`, `UcharBouw`), `service/`
(`Playground`, de playground-pagina), `scripts/check-dienst-replay.sh`, tests alleen waar de nieuwe API of een
gedragswijziging van de uchar-kaart dat afdwingt, `README.md`, `Makefile` en `docs/ENGINE.md` voor tekst die de pin
onwaar maakt, kaartverplaatsing.

## Raakvlakken

uchar-kaarten in `backlog/new/` na de landing: `beanlidnamen-zonder-java-identifier`, `caselezer-zoals-ref`,
`frontend-heelperiode-in-aantalfilter`, `weergaven-als-getuige`, `clingo-default-literaalschaal` en
`uitvoerdernotities-na-truth-maintenance`. De eigen kaarten in `backlog/new/` blijven buiten deze kaart.

## Result (2026-10-14)

### Wat de kaart opleverde

- **De pin** staat op uchar `f752a5c9`: de landing van `drl-teksttak-op-truth-maintenance` (`a63ef307`) met daarop
  uchars kaart `jackson-databind-kwetsbaarheden` (`ba7b5f60`, `jackson.version` 2.21.2 naar 2.21.7, voor 16
  Dependabot-waarschuwingen op jackson-databind). Het fixwerk is op `a63ef307` gedaan; de tweede pin (`ee33d75`) zet in
  dezelfde commit `jackson.version` van de ouder-pom op 2.21.7, omdat `check-pin` die gelijk eist aan die van uchars
  backend. `mvn dependency:tree` geeft in elke module jackson-databind en jackson-core 2.21.7, jackson-annotations 2.21
  en in service-quarkus de datatype- en modulejars 2.21.7, zonder tweede versie. De drie Quarkus-jars van 2.21.7
  stonden niet in de lokale Maven-repository; die zijn eenmalig met netwerk opgehaald (`mvn -B -DskipTests package`).
- **De DRL-tekst komt van de Build.** De CLI (`RegelSpraakCli.java:157`) en de playground (`Playground.java:166`) lezen
  `build.drl()`; `drlTranspiler()` bestaat niet meer.
- **De bewaking in `CaseSessie.speel`** (r. 64–66) vraagt nu `build.branch() != Branch.EXEC_MODEL`. Ze moet blijven:
  `ScenarioRunner` vergelijkt de vuringen van de uitleg met het spoor van `CaseRunner.rapport`, en dat rapport speelt
  altijd de exec-model-tak (uchar `CaseRunner.java:171`, met `build(irPath)` op r. 185, dat op r. 387–389 de
  exec-model-tak bouwt). De DRL-teksttak komt op hetzelfde fixpunt, maar uchar meet 47 spoorverschillen in volgorde en
  aantal bij een accumulate; een uitleg op die tak zou haar antwoord dan inhouden om een verschil dat er niet is. Na de
  pin trof de oude toets `execModelTranspiler() == null` nooit meer, omdat elke Build de analyse draagt. PLAN §9 van
  uchar schrijft "the branch guard in `CaseSessie` goes"; dat is hier niet gevolgd, om de reden hierboven (eigen
  gevolgtrekking uit de code van uchar, niet gemeten met een DRL-build in de uitleg). De hoofdsessie ging akkoord met
  het blijven van de bewaking, met deze reden.
- **Een DRL-tekst die niet compileert** (uchars `IllegalStateException`, "drl: de DRL-tekst van de weergave compileert
  niet, een fout van de weergave: …") is in de playground `drlFout` met uchars zin, en in de CLI uchars zin op stderr
  met exit 1, zonder stacktrace. Exit 1 en niet 3, omdat uchar het geen weigering van een vorm noemt. De vangst staat
  alleen om de bouw van de DRL-teksttak, die volgens uchars contract (`DroolsBackend.build`, `@throws`) op die tak
  precies deze fout als `IllegalStateException` gooit.
- **`Namen.getter`/`setter`.** `RolSchrijver`, `Verbalisator`, `CaseSessie` en `ScenarioRunner` nemen hun namen van
  `Namen.getter`/`setter`, en hun `is`-vorm (`"is" + getter.substring(3)`) is gelijk aan `Namen.lidnamen(…).isGetter()`.
  Er hoefde niets te veranderen. Gemeten met jshell op de jar: voor "İş" gaf `Namen.setter` vóór de pin `setİş` en nu
  `setIş`, wat de generator schrijft; `VerzoekMapper.heeftSetter` (r. 568) vindt de setter van zo'n rol daardoor nu wel.
- **De replay** volgt de nieuwe gate 12: de set telt 255 goldens (uchars `EXPECTED_PLAYED`), 225 spelen door de dienst
  (599 cases), 30 staan bij naam met reden bij de uitsluitingen. Drie nieuwe spelers:
  `jutterswet-verdeling-dubbelbezet`, `jutterswet-uniciteit-drie-bronnen` en `jutterswet-uitsluiting-gedateerd`.
  `jutterswet-aantalfilter-gehele-jaar` en `-creatieguard` geven een kenmerk met perioden en staan met die reden bij de
  uitsluitingen. De replay las de DRL-pin en -lijst van uchar nooit; alleen de getallen volgen.
- **Weigerzinnen en tekst.** `UcharBouw.NIET_GELOWERD` kent het voorvoegsel `drl-transpiler` niet meer, dat uchar niet
  meer schrijft. De playground-pagina noemt de DRL-teksttak niet meer smaller, en de README noemt de exit 1 van `drl`
  en de nieuwe getallen van de replay.
- **TOKA en de andere uchar-commits.** De 47 fragmenten `examples/toka/*.rgs` zijn byte-gelijk aan uchars
  `toka/casus/*.rgs` op de pin. De TOKA-vrijgave, de toka-zone en SUBTYPING §11 raken in uchar alleen tekst, scripts van
  uchar zelf en `spec/`; de vaste TOKA-tekst van de toolchain stond er al sinds `b6238b8`.
- **Het DRL-paneel** toont nu ook documenten met defaults: `Playground.analyseer` geeft DRL zonder `drlFout` voor
  `ww-herleving-inkomen-rgs` (15 531 tekens), `jutterswet-uitsluiting-rgs` en `jutterswet-nestkenmerk-rgs`, die vóór de
  pin in uchars `DRL_CHANNEL` stonden.

### Testwijzigingen

Geen. Geen test hoefde te veranderen: de nieuwe API raakt alleen hoofdcode, en geen test pint een gedrag dat het
Result van de uchar-kaart verandert. De vloeren van `tools/check-skips.sh` staan nog.

### Acceptatie

- (a) `make check-pin` groen op `a63ef307` en op `f752a5c9`, met `jackson.version` 2.21.7 gelijk aan uchars backend.
- (b) `grep drlTranspiler` over de eigen code vindt niets; de bewaking in `CaseSessie` staat hierboven, met de reden.
- (c) Er is geen getuige van een DRL-tekst die niet compileert: geen bekend document veroorzaakt die fout (uchar
  compileert de DRL-tekst van het hele corpus, 261 teksten), en `drl` gaf op alle eigen voorbeelden exit 0 of een
  resolverfout (de 31 TOKA-bronsets van het manifest, de acht beslistabellen, `vocabulaire-dubbelzinnig.rgs`). De
  twee vangsten zijn alleen door lezing getoetst.
- (d) `make gates` groen op `ee33d75` (16:04–16:13, 9 min 8 s), zonder `QUARKUS_HTTP_TEST_PORT`, in 7 stappen:
  - reactor: 1574 tests in uchars backend, 126 in toolchain (vloer 120), 249 in service (vloer 235), 47 in
    service-quarkus (vloer 43); geen skip;
  - tweelingen: 3 tweelingen met 18 cases over HTTP; de vocabulairetweeling met 2 cases in 34 verzoeken; de getuige
    met 4 getuigenissen;
  - replay: 255 goldens in de set van gate 12, 225 gespeeld met 599 cases (pin 225), 30 uitgesloten met reden;
  - manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
  - image `beslisservice:dev`; kind-smoke H2, H3 en H4 PASS.

  Eerder, op `a63ef307`, was `mvn -B -o verify` groen met dezelfde aantallen. De eerste run daar faalde in
  `HealthTest`, omdat een ander proces poort 8081 bezette; de tweede draaide met `QUARKUS_HTTP_TEST_PORT=0`. Daarna is
  dat proces gestopt en zijn de gates zonder die variabele gedraaid.
- (e) Er zijn geen testwijzigingen.

### Waarneming, niet gewijzigd

- **Namen in de dienst, een bekende grens zonder echte invoer.** `VerzoekMapper` (r. 244, 273, 556, 562) en
  `AntwoordBouwer` (r. 87) lezen en zetten een lid via `RegelSpraakRuntime.get`/`set` over `Namen.ident(naam, false)`.
  Die brug zet `RegelSpraakRuntime.pascal` van de eigenschap achter `get`, `is` en `set`, en uchar noemt die vorm in de
  javadoc van `pascal` niet maatgevend. Voor "日 X" geeft de brug `get日x`/`set日x`, waar de generator en
  `Namen.getter`/`setter` `get日X`/`set日X` schrijven; de dienst faalt bij zo'n naam met een melding, niet stil. Dat was
  vóór de pin ook zo: de pin veranderde `pascal` en `Namen.ident` niet. Gemeten of echte invoer zo'n naam heeft
  (2026-10-14, eenmalig, met een programma buiten de repository op de jar van deze branch): per attribuut-, kenmerk- en
  rolnaam de getter, de `is`-getter en de setter van `Namen` tegen die van de brug. Invoer: elke `ir.json` van uchars
  `corpus/` (303 golden, 45 rood, 505 negatief); de `.rgs`-bestanden van `examples/` en `scripts/tweelingen/` van de
  toolchain, per bestand en per regel van het TOKA-manifest; en de werkbank (alleen gelezen): de vier ketens van
  `rulesets/regelspraak-rules` zoals hun Makefiles ze samenstellen, elk van de 240 `.rgs`-bestanden daar afzonderlijk,
  en `wetsanalyse/herleving-ww/dist/cgm-snede-begrippenkaart.rgs`. Uitkomst: 7 952 voorkomens, 2 231 unieke namen, geen
  verschil. Een controle met "日 X" gaf het verschil wel. Daarom is niets gebouwd en is er geen kaart; het blijft een
  bekende grens.
- **toka-29.** `drl` op de bronnen van `scenario-toka-29-passagier-met-recht.rgs` geeft alleen de pakketregel: de
  frontend zet de feitcreatie niet om (WARNING "referentieveld-zijde voor tussenstap 'reis met treinmiles' niet af te
  leiden"). Dat staat los van deze pin.

### De playground na de pin (inventaris, niets gebouwd)

**Wat `Playground.analyseer` geeft**
(`service/src/main/java/dev/uchar/regelspraak/toolchain/service/http/Playground.java`):
- `parse` (r. 106–120), `resolver` (r. 122–134); bij een fout daar niets anders (r. 134).
- `ir` of `irFout` (r. 137–147); `uitvoerbaar` met `dekking` per vorm, of `weigering` (r. 148–160); `drl` of
  `drlFout` (r. 162–170); `graaf` (Mermaid) en `graafJson` (r. 172–192).
- `scenario.resultaten` (r. 194–199), per scenario in `rendScenario` (r. 203–234): `naam`, `status`, `diagnose`
  (r. 205–207); `verwachtingen` met `bron`, `verwacht`, `werkelijk`, `ok` (r. 208–215), waarbij `bron` uchars korte
  vorm is ("attributeValue P1 korting", uchar `CaseRunner.bron`, r. 1389) en niet de zin uit het scenario; `dekking`
  per vorm (r. 216–221); `trace` per vuring met `regel`, `labels`, `aantal` (r. 222–230), zonder waarden; `uitleg` of
  `uitlegFout` (r. 231–233).
- Het vocabulaire (`?vocabulaire=`) noemt `graafJson`, de trace, de dekking per scenario en de uitleg (r. 70–73); de
  parse, het IR, het oordeel, de DRL en de Mermaid-graaf blijven canoniek.

**Wat `playground.html` daarvan toont**
(`service/src/main/resources/dev/uchar/regelspraak/toolchain/service/http/playground.html`):
- Zeven tabbladen (r. 85–91). Scenario (`renderScenario`, r. 234–258): status en naam, `diagnose`, een tabel
  verwachting/verwacht/werkelijk, één regel "vuringen", de uitlegzinnen of "geen uitleg". De `dekking` per scenario
  komt niet op het scherm.
- Graaf (`renderGraaf`, r. 198–210) toont de Mermaid-graaf, dus canoniek; `graafJson` gebruikt de pagina niet. Het
  vocabulaire werkt op het scherm daardoor alleen in de vuringen en de uitleg (het veld op r. 78 noemt "graaf, trace en
  uitleg").
- Het situatieblok van een scenario staat alleen als brontekst in het invoerveld. De case die `ScenarioRunner` ervan
  maakt (`CaseEmitter.emit`, `ScenarioRunner.java:109`) komt niet in het antwoord: geen tabel van instanties,
  attributen, kenmerken en rollen.
- Er is geen overzicht van de vocabulaires van de bron (welke er zijn, of ze volledig zijn); dat geeft alleen `/info`
  van de dienst, en een onbekende naam geeft een 400.
- De uitleg (`Verbalisator`) verantwoordt kenmerktoekenningen en hun voorwaarden; een attribuutwaarde krijgt alleen
  een zin als operand van zo'n voorwaarde, en een defaults-groep geen zin (README, "Known losses").

**Wat het `Fixpunt` van uchar biedt en de playground niet toont** (uchar
`backends/drools/src/main/java/dev/uchar/regelspraak/transpiler/tms/Fixpunt.java` r. 29–30, gelezen met
`DroolsBackend.lees`, `DroolsBackend.java` r. 599 en 609):
- `waarden`: elke afgeleide attribuutwaarde per object, ook zonder verwachting en op gemaakte objecten. De playground
  toont alleen `werkelijk` bij een verwachting.
- `kenmerken`: elk afgeleid kenmerk. De playground toont het bij een verwachting en in de uitleg.
- `objecten`: elk object dat een creatie maakte, met de creatie en het onderwerp. Niet getoond.
- `koppelingen`: elke koppeling die een creatie legde (feittype, beide rollen en objecten). Niet getoond.
- `fouten`: de evaluatiefouten aan het fixpunt als gegevens (onderwerp, vorm, zin). De playground toont alleen de
  diagnose van een FAIL; de uitleg zet de waarden met `zetAfgeleideWaarden` terug (`CaseSessie.java:117`), dat een
  staande fout gooit, zodat zo'n case via uchars rapport als "speelde de case niet uit" eindigt.
- `inconsistenties`: de §9.5-deposito's met regel en onderwerp. Alleen zichtbaar als een verwachting erom vraagt.
- De lijsten hebben geen volgorde (alleen `fouten` is gesorteerd); een weergave sorteert zelf (uchar-kaart,
  "Ontwerpnotities").

### Commits

`6132ce1` kaart · `46eb0d0` claim · `8cd459b` pin `a63ef307` met `.drl()` · `f34f5b8` bewaking in `CaseSessie` ·
`09e0642` een DRL-tekst die niet compileert is een melding · `66f79ff` replay 225 · `40f25af` weigerzinnen ·
`3700a08` tekst · `ee33d75` pin `f752a5c9` met jackson 2.21.7 · dit Result · naar `done/`.
