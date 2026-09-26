# dienst-gedrag-en-poorten: de dienst antwoordt zoals uchar antwoordt, en de poorten bewijzen het

Na `submodule-en-reactor` bouwt de distributie uit uchar en draaien de
bestaande suites. Deze kaart maakt het gedrag van de dienst, de
scenariorunner en de playground gelijk aan wat uchars gates bewijzen, en
zet de poorten die de verhuizing "klaar" noemen (onderzoek 2026-09-26, §2
G1/G2/G10 en §10.1 stap 5d–5e/klaar-criteria).

## U-besluiten (hoofdsessie, 2026-09-26)

- U1 Markeroordeel: de dienst en de scenariorunner beoordelen "gedragen /
  niet gedragen" op de dekking van de tak die vuurt (de exec-tak,
  `DroolsBackend.Build.dekking()`), nooit op de DRL-tak; een document dat
  de facade weigert (`Excluded`, lader-`Refusal`, `Documentfout`) geeft een
  MARKER-antwoord met de zin van uchar, nooit HTTP 200 met een stil
  antwoord; `/info` toont de dekking per vorm inclusief defaultsfamilies.
- U2 Playground: bij resolverfouten alleen de diagnoses (geen vuren, geen
  DRL), zonder de fout in te slikken; bij een schoon document de IR, de
  DRL-weergave (weergave, geen oordeel), het vuurresultaat.
- U3 nluwv-commit `f3613bd3` geport (de werkbank-UI leest `graafJson`,
  `trace` en `uitleg` uit `/playground/analyse`): `graafJson` uit uchars
  graaf-API (`RegelGraaf.bouw(model, defaults, navigatie)` op het geladen
  document), `trace`/`uitleg` uit `ScenarioResultaat` en de `Verbalisator`.
  Bron: /Users/yeb/Developer/uwv/regelspraak-werkbank/toolchain (nluwv
  8fbb1704; alleen die commit lezen; de UWV-rulesets daar niet citeren).
- U4 Poorten (scripts in deze repo, elk met een vloer en een lege-set-fout):
  (a) de drie dialect-tweelingen `ww-herleving-inkomen-rgs`,
  `jutterswet-uitsluiting-rgs`, `jutterswet-nestkenmerk-rgs` vuren via HTTP
  met de verwachtingen van al hun casebestanden (4 + 4 + 10) — een
  smoke-script tegen de JDK-dienst; (b) een replay van elke uchar-golden
  die gate 12 speelt door de dienst (casebestand → verzoek → antwoord →
  vergelijking met de gepinde verwachtingen), met een gepind aantal en een
  benoemde, redendragende uitsluitingslijst (vormen die de dienst niet als
  verzoek kan uitdrukken); (c) vloeren per suite (engine, dienst, quarkus,
  toolchain-kern) op de gemeten stand; "553" vervalt.
- U5 `ScenarioRunnerCli --manifest` generiek: exit 0 als geen regel
  mismatcht (de oude vaste telling eruit); exit 3 blijft de markerexit.
- U6 Klaar: `mvn -B verify` groen op de root; de poortscripts groen;
  README beschrijft de poorten en hoe je ze draait.

May touch: `toolchain/`, `service/`, `service-quarkus/`, `scripts/`,
README/CLAUDE.md. Niet: `uchar/` (een semantische vraag is een kaart in
uchar), `examples/toka*`, `specification/`.

## Erbij na de landing van `submodule-en-reactor` (hoofdsessie, 2026-09-26)

Uit het Result van die kaart (restpunten 3, 5, 6, 9 en een deel van 2):

- U7 De scenarioroute kreeg door de overstap op uchars scenariolaag geen
  `--uitleg` en geen `--parametersets` meer (uchars replay geeft geen gevuurde
  feiten terug; uchars scenariolaag eist elke gelezen parameter in de
  situatie). Herstel beide via uchars publieke API waar dat kan — de
  uitleg met een eigen sessie en luisteraar op `Build.kieBase()` en de
  `Verbalisator`, de parametersets door ze vóór de replay in de gelowerde
  casebestanden te zetten als het caseformaat dat draagt — en anders als
  benoemd verlies in de README, met de reden. Geen wijziging in `uchar/`.
- U8 De facade weigert de hele build bij de eerste niet-gelowerde
  constructie, ook als die in een gescopet bestand buiten de scope staat.
  Scope op vormniveau via `CaseEmitter.metScope` (uchar, frontend): het
  entrypoint bouwt het document van zijn scope, zodat alleen wat het leest
  telt; het markeroordeel blijft per entrypoint.
- U9 uchars scenariolaag weigert drie van de 29 gouden scenario's in de
  pariteitszone (een scope die een feitcreatie noemt; een scope in een
  document met een startpuntbepaling). Dat is een vraag voor uchar, geen
  kaart hier: de manifestpoort (`--manifest` generiek, U5) draagt de drie
  als redendragende uitsluiting die faalt zodra uchar ze aanneemt, zoals de
  golden-tests over HTTP en Quarkus dat al doen.

## Result (2026-09-26, bouwer)

**Uitkomst.** De dienst, de scenariorunner en de playground antwoorden nu zoals uchar antwoordt, en drie poorten
bewijzen dat. `make poorten` is groen op de kop: `check-pin`; `mvn -B -o verify` (uchar-backend 840, toolchain 110,
service 180, service-quarkus 33, geen skip, vloeren gehaald); `check-tweelingen` (3 tweelingen, 18 cases via HTTP);
`check-dienst-replay` (set van gate 12: 215 goldens, 188 gespeeld met 494 cases, 27 bij naam uitgesloten, 0
afwijkingen); het gouden TOKA-manifest (32 regels: 29 ok, 3 uitgesloten, 0 mismatch). `uchar/` is niet gewijzigd,
`~/.m2/repository/dev/uchar` bestaat niet. Wat niet haalbaar was of buiten de kaart viel, staat genummerd onder
Restpunten.

### Commits (basis 316d448, branch card/dienst-gedrag-en-poorten)

- 0862762 claim (git mv new → doing; `backlog/doing/` bestond niet en is aangemaakt).
- b42caf4 scope op vormniveau (`VormIndex` + `CaseEmitter.metScope`), elke weigering van uchar en een gescopete
  regel zonder vorm is een marker, MARKER-antwoord, `/info` met `uitvoerbaar`, `weigering` en de dekking per vorm;
  scenariorunner: een misvormd document is MARKER; `RegelBestandIndex` vervalt.
- 0d3f1ba scenarioroute: `--uitleg` terug (`CaseSessie`, `ScenarioRunner.runMetUitleg`), `--parametersets` benoemd
  geweigerd, `--manifest` generiek met `--uitsluitingen` (`scripts/toka-manifest-uitsluitingen.txt`).
- 6a8f374 playground: alleen diagnoses bij een parse- of resolverfout; IR, oordeel van de exec-tak, DRL als
  weergave, graaf + graafJson, trace + uitleg per scenario; geen catch-all.
- 92807f8 dienst: een `uchar-ir-v1`-document als bron (heel gediend), `DienstMain --host`.
- 84b7deb dienst: rolinvoer via `RolSchrijver` (zoals uchars replay), enumeratie op genormaliseerde naam,
  `inconsistentieDetails` (regel + onderwerp), `soort` per schending in een 400, OpenAPI + golden opnieuw.
- c37afa8 poorten: `scripts/check-tweelingen.sh`, `scripts/check-dienst-replay.sh`, `scripts/dienst_replay.py`,
  `scripts/tweelingen/*.dienst.json`, make-doelen `check-tweelingen`, `check-dienst-replay`, `poorten`.
- d0ac6b5 vloeren (`tools/check-skips.sh`): toolchain 110, service 180, service-quarkus 33.
- f295094 README, CLAUDE.md, docs/ENGINE.md.
- 5e8b8ee playground: de graaf van het geladen document (U3 letterlijk: `RegelGraaf.bouw` op wat de lader teruggeeft).
- 5e09026 de uitleg-build via `UcharBouw` (de enige bouwingang); CLAUDE.md noemt het document als bron.
- (laatste) dit Result in de kaart.

### Metingen (commando → uitkomst)

1. Nulmeting op 0862762: `mvn -B -o verify` → BUILD SUCCESS, 53,7 s; 840/109/169/33, 0 skip.
2. Tussendoor, na elke stap: `mvn -B -o test -pl service-quarkus -am -Dtest='dev.uchar.regelspraak.toolchain.**'
   -Dsurefire.failIfNoSpecifiedTests=false` (alle eigen suites, uchars suite overgeslagen; de skip-pin-stap blijft
   zo tellen). Stand per stap: 109/173/33 (stap 2), 110/175 (uitleg, playground), 110/180/33 (dienst, poorten).
3. Een aanname weerlegd door meting: de feitcreatie van toka-29 is in het volle TOKA-model een vorm (probe over de
   geserveerde bronset: feitcreaties=1), in fundering + toka-29 alleen niet (0 vormen, 1 WARNING van uchars
   frontend). De oude bestandsscope bouwde dat entrypoint dus uit een deelverzameling zonder de vorm: een lege
   KieBase die 200 antwoordde. Met de vormscope draagt het entrypoint de feitcreatie (1 verdict LOWERED).
4. Het volle TOKA-model draagt 1 startpuntbepaling (probe: `resolvedStartpunten`=1); zie restpunt 5.
5. Uitleg op de drie tweelingen (probe `ScenarioRunner.runMetUitleg`): 18/18 scenario's, de eigen sessie vuurt
   exact uchars trace; zinnen alleen voor kenmerktoekenningsregels (nestkenmerk: 0 zinnen; restpunt 3).
6. `make scenario-test` → "manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch", exit 0. Faalwijzen, elk exit 1:
   een uitgesloten scenario dat resolvet ("MISMATCH (uitgesloten, maar uchar weigert het niet met …: het
   resolvet)"), een uitsluiting die het manifest niet draagt, geen lijst (3 mismatch), een leeg manifest ("MANIFEST
   FAALT: het manifest telt geen regels").
7. `DienstMain` op een IR-golden (`jutterswet-aandelensom`): start in 0,58 s; het eerste handverzoek gaf 400 "rol
   'bergers' is naar-één maar kreeg 2 doelen" — het eerste verschil met uchars replay (commit 84b7deb).
8. Replay, eerste verkenning (6 werkers): 158 PLAYED, 55 EXCLUDE, 2 FAIL in 42 s. De 2 FAIL: een enumeratiewaarde in
   een andere spelling ("Gaaf"/"Beschadigd", `jutterswet-keurspelling`, `jutterswet-keurstaat`); 28 EXCLUDE: een
   inconsistentie op een onderwerp (31 goldens, 80 verwachtingen pinnen een `label`); 27 EXCLUDE: tijdlijnvormen.
   Na 84b7deb: 188 / 27 / 0 in 43 s.
9. De OpenAPI-golden (`service/src/test/resources/openapi/toka-belastingdienst.golden.json`) opnieuw gegenereerd
   zoals de test voorschrijft: +306 regels, alleen toevoegingen (29× het 503-antwoord, 1× `inconsistentieDetails`),
   geen nieuwe TOKA-inhoud.
10. `bash scripts/check-tweelingen.sh` → PASS, 18 cases, 5,6 s. Mutaties: een gedraaide verwachting (2100 → 2101 in
    `ww-herleving-inkomen-rgs/case1`) → FAIL "verwacht {"number": "2101"}, kreeg 2100.00"; een scope zonder de
    defaultsgroep → de dienst start niet (SCOPE_ONVOLLEDIG, de defaultsgroep is de enige producent).
11. `bash scripts/check-dienst-replay.sh` → PASS: set 215 (uchars EXPECTED_PLAYED 215), 188 gespeeld, 494 cases, 27
    uitgesloten, 47-48 s met 4 diensten; twee keer achter elkaar groen. Proeven op kopieën van het script, elk
    exit 1 bij naam: een weggelaten uitsluiting ("jutterswet-bakenmeester :: tijdlijn als invoerwaarde"), een
    uitgesloten speler ("jutterswet-aandelensom speelt door de dienst maar staat in REPLAY_UITGESLOTEN"), pin 187
    ("188 goldens speelden, EXPECTED_REPLAY_PLAYED pint 187").
12. Twee fouten in de driver gevonden bij die proeven en hersteld vóór de commit: een dienst die niet opkwam lekte
    zijn proces (`__exit__` loopt niet als `__enter__` faalt; twee processen opgeruimd), en de driver las het
    uitvoerbestand met `seek` op de bestandspositie die het kindproces deelde, zodat de poortregel overschreven kon
    worden ("noemt na 120 s geen poort"). Een derde: poort 63342 was door een IDE op 127.0.0.1 bezet terwijl de
    dienst op 0.0.0.0 dezelfde poort kreeg; de driver kreeg 404 op /info. Nu bindt de dienst in de poorten op
    127.0.0.1 (`--host`). Na de fix: geen gelekte processen (`ps` telt 0).
13. Eind, op 5e09026: `make poorten` → exit 0, 1:52; `check-pin` PASS; `mvn -B -o verify` BUILD SUCCESS 47,4 s,
    840/110/180/33, "PASS: geen enkele skip of abort" met vloeren 110/180/33; `check-tweelingen` PASS (18 cases);
    `check-dienst-replay` PASS (215 / 188 / 494 cases / 27); manifest 32/29/3/0.
14. `make classpath && bash tools/testdata/run_tests.sh` → "TOTAL: 7 pass, 0 fail".
15. `git -C uchar status --porcelain` → leeg; `git status --short` → leeg; `ls ~/.m2/repository/dev` → alleen
    `resteasy` (Quarkus), geen `uchar`.
16. Controle op de regimewoorden en D-/U-nummers over `git diff 316d448..HEAD`, de commitboodschappen en dit
    Result: geen treffer. Commitboodschappen binnen 72 tekens (awk-controle per commit).

### Besluiten die de kaart aan de bouwer liet

1. **Wat een marker is.** Elke weigering van uchar op de weg naar een build — emitter, lader (`Refusal`),
   misvormd document (`Documentfout`), backend (`Excluded`, met of zonder constructnaam) — maakt het entrypoint een
   marker, en ook een gescopete regelnaam die de bron declareert maar die in uchars model geen vorm oplevert (reden:
   uchars WARNING over die regel, als die er is). Strikt: startweigering met alleen MARKER-schendingen (exit 3);
   met markers: 503 met `{"status":"MARKER","fout",…,"reden":<uchars zin>,"markers":[…]}`. Een weigering zonder
   constructnaam was tot nu een harde fout (exit 1), ook met `--allow-markers`.
2. **`/info`**: per entrypoint `uitvoerbaar` en bij een marker `weigering`; `dekking` = `Build.dekking()` van het
   document van de scope, dus inclusief defaultsgroepen, zonder filter op namen.
3. **Vormscope.** Een scopenaam draagt de vormen van die naam (versieachtervoegsel eraf, genormaliseerd zoals
   uchars scenariolaag vergelijkt), anders de rijen van de beslistabel van die naam; een regelgroep de vormen van
   haar leden. Benoembaar is een vorm of een in de bron gedeclareerde regel. De bestandsindex en haar eis "één
   construct per bestand" vervallen; de scopevolledigheidscontrole rekent nu met de vormen van de scope.
4. **Uitleg op de scenarioroute.** Een eigen sessie op dezelfde build (`CaseSessie`, invoer gezet zoals uchars
   replay het doet) met een luisteraar; de uitleg komt er alleen als die sessie exact uchars trace vuurde en bij
   elke attribuut- en kenmerkverwachting de waarde las die uchar las, anders `uitlegFout` met het verschil. Een
   MARKER-scenario krijgt geen uitleg; `--uitleg` samen met `--manifest` wordt geweigerd.
5. **`--manifest`**: exit 0 als geen regel mismatcht, anders 1; `--uitsluitingen` met `<scenario>|<fragment>|<reden>`;
   een leeg manifest faalt.
6. **Playground**: velden `ir`/`irFout`, `uitvoerbaar` + `dekking`/`weigering`, `drl`/`drlFout` (weergave),
   `graaf`/`graafJson`/`graafFout`, `scenario.resultaten[].{trace,uitleg,uitlegFout}`; alleen uchars weigeringen
   worden onderdeel van het antwoord, al het andere gaat als 500 naar de adapter. De pagina kreeg de tabs IR en
   Dekking, en de scenariotab toont trace en uitleg.
7. **De dienst dient een `uchar-ir-v1`-document.** Nodig omdat 124 van de 215 goldens van gate 12 geen
   RegelSpraak-bron hebben; zo speelt de replay de hele set. Het document wordt heel gediend; `DienstMain --host`.
8. **Verzoek en antwoord zoals uchars replay**: `RolSchrijver` voor de inline rollen (ook de spiegel van een
   wederkerige relatie), enumeratie op genormaliseerde naam met de spelling van de invoer, `inconsistentieDetails`
   naast `inconsistenties` (bestaande afnemers blijven werken), `soort` per schending in een 400.
9. **Replay-poort**: de set uit `uchar/scripts/check-drools.sh` (getoetst tegen uchars `EXPECTED_PLAYED`), per golden
   een gegenereerde descriptor (één entrypoint met alle vormen, `scopeControleUit`, uitvoer uit de verwachtingen),
   bron `bron.rgs` of anders `ir.json`, getallen als lexeem (exact), vergelijking zoals uchars `CaseRunner`
   (getal op waarde, enumeratie en regelnaam genormaliseerd, datum-tijd op tijdstip). Pin 188 als gelijkheid plus
   27 uitsluitingen bij naam; een FAIL faalt altijd. De driver is Python (JSON, HTTP, Decimal, parallel), de
   poortlogica met pins en lijsten bash, zoals uchars poorten.
10. **Tweelingen-poort**: handgeschreven descriptors zoals een gebruiker ze schrijft; per tweeling het aantal cases
    gepind (10/4/4) en het totaal 18.
11. **Vloeren**: toolchain 110, service 180, service-quarkus 33 (gemeten 2026-09-26). Engine en toolchain-kern zijn
    sinds de vorige kaart één module; "553" bestond al niet meer.
12. Volgorde: de uitleg en `--manifest` kwamen vóór de playground, omdat de playground de uitleg per scenario nodig
    heeft.

### Omgezette en nieuwe tests, met reden

- `RegelBestandIndexTest` → `VormIndexTest`: de bestandsindex bestaat niet meer; dezelfde TOKA-feiten nu over vormen
  (toka-45 niet benoembaar, de drie woonregio-rijen en hun tabelnaam), plus tabel, uitzondering en regelgroep op een
  eigen klein model.
- `BeslisserKernTest.toka29_…_round_trip`: pinde het stille antwoord (lege KieBase, "vacuous consistency check");
  eist nu één LOWERED feitcreatie-verdict en een gewoon antwoord. Nieuw: `dekking_is_de_manifest_van_het_document…`.
- `DienstHttpTest.info_feitcreatie_toont_lege_dekking` → `info_feitcreatie_toont_haar_verdict`, zelfde reden. Nieuw:
  de defaultsgroep in `/info` (en vuren via HTTP), een scope zonder vorm als MARKER, het 503-MARKER-antwoord met
  `status`, `reden` en `markers`.
- `MarkerFailFastTest`: nieuw, een weigering zonder constructnaam (wederkerige rol die haar naam met een attribuut
  deelt) is een MARKER.
- `PlaygroundTest`: resolverfout geeft geen `ir`/`uitvoerbaar`; nieuw: een scenariobron met een resolverfout geeft
  alleen diagnoses (het G10-geval), een schoon model dat uchar niet draagt, IR/dekking/graafJson, trace en uitleg.
- `ScenarioRunnerTest`: nieuw, de uitleg van een gespeeld scenario. `DienstMainTest`: `--host`.
- `DocumentBronTest` (nieuw): IR-document als bron met een naar-veel-rol bij haar geschreven naam, een scope die niet
  alles draagt, een document dat de lader weigert, enumeratie op genormaliseerde naam, `inconsistentieDetails`.
- `openapi/toka-belastingdienst.golden.json` opnieuw gegenereerd (meting 9).

### Restpunten van `submodule-en-reactor`

Meegenomen: 3 (uitleg hersteld, parametersets benoemd verlies in de README), 5 (vormscope), 6 (`--manifest`), 9
(playground-payload, replay, tweelingen, vloeren) en de drie geweigerde pariteitsscenario's uit 2 (uitsluiting in de
manifestpoort). Blijven staan: 1 (Docker/kind, kaart `ingangen-en-deploy`), 4 (getypeerde weigering, uchar), 7
(verwijzingen in de pariteitszone), 8 (bij de landing gedaan), 10 (triage in uchar).

### Restpunten

1. **uchar, publieke API voor de case-invoer.** De invoerstap van uchars replay (`CaseRunner`: beans, rollen,
   spiegel, tijdlijnen) is niet publiek; de distributie spiegelt het niet-tijdlijndeel in `RolSchrijver` en
   `CaseSessie`. De replay-poort bewaakt die spiegel over het corpus, niet daarbuiten. Een publieke invoer- en
   na-vuur-API in uchar maakt de spiegel en de tweede sessie van de uitleg overbodig.
2. **Tijdlijnen in een verzoek** (27 goldens uitgesloten). Een tijdlijnvorm in het verzoek vraagt uchars temporele
   invoer (rooster, emmer van de peildatum), die niet publiek is; samen met 1.
3. **De uitleg legt defaultsgroepen niet uit**: de `Verbalisator` verantwoordt kenmerktoekenningsregels. Een kenmerk
   dat een defaultsgroep beslist krijgt geen zin (presentatiewerk in deze repo).
4. **De trace draagt geen waarde per vuring**, anders dan nluwv f3613bd3 renderde: uchars `Vuring` kent die niet.
   Na te gaan bij het omhangen van de werkbank of de UI dat veld leest.
5. **Startpuntbepaling en vormscope.** Het volle TOKA-model draagt een startpuntbepaling; uchars scenariolaag weigert
   elke scope in zo'n document, de dienst houdt de bepaling en bouwt de vormen van de scope (zoals de bestandsscope
   dat deed). De dienst dezelfde weigering laten volgen maakt elk TOKA-entrypoint een marker; een vraag voor uchar
   en de maintainer.
6. **Scope op een IR-document**: een document wordt heel gediend. Een scope erop vraagt een `metScope` op de wire of
   een publieke vormenlijst in uchar.
7. **`VormIndex` spiegelt uchars scope-naar-vormen** (`ScenarioResolver.vormenVan` is privé): een publieke API in
   uchar haalt die spiegel weg.
8. **Kaart `ingangen-en-deploy`**: de poorten vragen `python3` naast `java` en `mvn` (voor CI); `DienstMain` kent nu
   `--host` en een `.json`-bron; de Quarkus-adapter spiegelt het 503-MARKER-antwoord en de `soort` in een 400 met de
   hand, zonder pariteitstest voor foutlichamen (`InfoParityTest` dekt alleen `/info`).

## Keuring (2026-09-26, hoofdsessie)

Gekeurd op `c6ebdb7` in de worktree en geland met een fast-forward. Gemeten:
`scripts/check-pin.sh` groen; de greps op de oude pakketnaam en op
`dev.uchar.regelspraak` buiten `uchar/` leeg; `mvn -B -o verify` groen
(uchar-backend 840, toolchain 110, service 180, service-quarkus 33 tests,
geen skips); `make poorten` groen: de tweelingen 18 cases (10/4/4) via HTTP,
de replay 188 van de 215 goldens van gate 12 gespeeld (494 cases) met 27
uitsluitingen bij naam en 0 afwijkingen, het manifest 32 regels, 29 ok, 3
uitgesloten met uchars weigeringszin, 0 mismatch.

Triage van de restpunten: 1, 2, 6 en 7 (een publieke API in uchar voor de
case-invoer inclusief tijdlijnen, een scope op de wire, de vormenlijst van
een scope) en 5 (normvraag: een scope in een document met een
startpuntbepaling — de dienst houdt de bepaling en bouwt de vormen van de
scope; standaardantwoord: zo hoort het, uchars scenariolaag moet de
bepalingen op documentniveau meedragen en alleen de regellaag selecteren)
zijn open punten voor uchar, vast te leggen in uchars PLAN §9 bij de
volgende registerronde en geen kaart nu (maintainer, 2026-09-26: eerst de
verhuizing); 3 en 4 zijn presentatiewerk in deze repo, na te gaan bij het
omhangen van de werkbank; 8 naar de kaart `ingangen-en-deploy`.
