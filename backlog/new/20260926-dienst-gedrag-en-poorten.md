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
