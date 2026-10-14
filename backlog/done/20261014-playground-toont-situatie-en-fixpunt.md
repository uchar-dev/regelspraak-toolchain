# playground-toont-situatie-en-fixpunt: de situatie, de bronregel van een verwachting en het fixpunt per scenario, en de vocabulaires

*Vastgelegd op 2026-10-14, uit de inventaris in het Result van `pin-naar-de-drl-teksttak-op-truth-maintenance` ("De
playground na de pin"). Regelnummers op toolchain `dba5864` (submodule op uchar `f752a5c9`).*

## Aanleiding

De werkbank wil de eval-dataset herleving-ww per scenario tonen: het situatieblok, de vragen en de verwachte antwoorden.
`Playground.analyseer` (`service/…/http/Playground.java`) geeft daarvoor te weinig:

1. Het situatieblok staat niet als gegevens in het antwoord. `ScenarioRunner` maakt er de case van
   (`CaseEmitter.emit`, `ScenarioRunner.java:109`), maar de playground geeft alleen uchars rapport door.
2. Een verwachting draagt in `bron` uchars korte vorm ("attributeValue P1 herlevingsdatum", uchar `CaseRunner.bron`),
   niet de plaats in het scenario.
3. Het `Fixpunt` van uchar (`DroolsBackend.lees`) ontbreekt: de afgeleide waarden en kenmerken zonder verwachting, de
   gemaakte objecten, de koppelingen, de fouten en de inconsistenties. `CaseSessie.speel` zet de waarden terug met
   `zetAfgeleideWaarden` (r. 117), dat een staande evaluatiefout gooit; een case met zo'n fout eindigt via uchars rapport
   als "speelde de case niet uit", en de eigen sessie speelt haar dan niet (`ScenarioRunner.java:158`).
4. `playground.html` toont de dekking per scenario niet (zij staat al in de JSON) en heeft geen overzicht van de
   vocabulaires.

Twee pagina's lezen de JSON: `playground.html` hier en `werkbank/workbench.html` in de werkbank.

## Wat er gebeurt

1. **De situatie per scenario** als gegevens, uit uchars `ResolvedScenario`: de objecten met type en label, hun
   attribuut- en kenmerkwaarden, de rollen, de parameterwaarden, de peildatum.
2. **De plaats van een verwachting**: naast `bron` het regelnummer dat uchar geeft
   (`VerwachtingResultaat.positie` → `ResolvedScenario.verwachtingen()` → `Verwachting.line`) en de tekst van die
   bronregel. Waar uchars API de zin zelf niet aanwijst, komt er geen omweg; het Result zegt wat in uchar ontbreekt.
3. **Het fixpunt per case**: `CaseSessie` leest `DroolsBackend.lees(build, sessie, label)` vóór
   `zetAfgeleideWaarden` en zet alleen terug als er geen evaluatiefout staat (zoals `Uitvoerder.vuur` van de dienst).
   De JSON geeft per object de waarden en kenmerken met hun herkomst (invoer, afgeleid, creatie), de gemaakte objecten
   met creatie en onderwerp, de koppelingen, de fouten en de inconsistenties, elk deterministisch gesorteerd (uchar:
   de lijsten van `Fixpunt` hebben geen volgorde). Het fixpunt staat er alleen als de eigen sessie volgt wat uchars
   replay deed, met dezelfde toetsen als de uitleg; een case die uchars replay niet uitspeelde, speelt de sessie toch,
   zodat de fouten die de terugzetting gooide als gegevens verschijnen.
4. **Het vocabulaireoverzicht** uit `Naamgeving`: de gedeclareerde vocabulaires, het gekozen, en per gedeclareerd
   element de benaming in elk vocabulaire.
5. **`playground.html`** toont per scenario de situatie, de verwachtingen met hun bronregel, het fixpunt en de dekking
   als tabellen, en de vocabulaires in een eigen tab; leesbaar bij een document van 1 600 regels met tientallen
   objecten.

De JSON wordt alleen uitgebreid: bestaande sleutels houden hun vorm en betekenis. Namen staan in het gekozen
vocabulaire, met de canonieke naam ernaast waar die verschilt.

## Acceptatie

- (a) Op het eval-document s01 (PASS) en s04 (FAIL op `s04-v02`) van herleving-ww geeft `POST /playground/analyse`
  per scenario `situatie`, de regel van elke verwachting en een `fixpunt`; de bestaande sleutels zijn ongewijzigd.
- (b) Twee analyses van één document geven dezelfde JSON (de sortering is deterministisch).
- (c) Een case met een staande evaluatiefout geeft de fouten als gegevens in `fixpunt.fouten`.
- (d) Nieuw gedrag is getest in nieuwe testklassen; een bestaand testbestand verandert alleen als de uitbreiding het
  afdwingt, in een eigen commit met de reden in het Result.
- (e) `make gates` groen.

## Omvang

Middel. May touch: `toolchain/` (`CaseSessie`, `ScenarioRunner`, `ScenarioRunnerCli`, `Naamgeving`), `service/`
(`Playground`, een nieuwe klasse voor de scenariogegevens, `Evaluatiefout` alleen voor de zichtbaarheid van de soort,
`playground.html`), nieuwe testklassen, `docs/ENGINE.md` en `README.md` voor de playground, kaartverplaatsing. Niet
`uchar/`.

## Raakvlakken

De werkbank-pagina `werkbank/workbench.html` wordt elders aangepast en leest dit contract; zij is hier alleen gelezen.

## Result (2026-10-14)

### Wat de kaart opleverde

- **Het fixpunt in de sessie van de uitleg.** `CaseSessie.speel` (r. 123) leest `DroolsBackend.lees(build, sessie,
  label)` vóór de terugzetting en zet alleen terug als er geen evaluatiefout staat; het `Fixpunt` reist mee in
  `CaseSessie.Uitkomst.fixpunt`. Dat volgt `Uitvoerder.vuur` van de dienst, dat de fouten ook eerst als gegevens leest.
  `CaseSessie.lees` (een getter lezen) is publiek geworden, voor de gemaakte objecten en de inconsistenties.
- **De sessie naast elk rapport.** `ScenarioRunner.Uitgelegd` heeft twee velden erbij: `sessie` en `sessieFout`. De
  sessie staat er onder dezelfde toetsen als de uitleg (dezelfde vuringen, dezelfde gelezen waarden), plus één nieuwe:
  geen evaluatiefout die uchars replay niet gaf. Een case die uchars replay niet uitspeelde (FAIL zonder
  verwachtingen), speelt de sessie nu toch (`nietUitgespeeld`, r. 196). Staat er dan een evaluatiefout aan haar
  fixpunt, dan is dat de fout die de terugzetting van de replay gooide, en de sessie wordt gegeven; zonder fout komt
  er een `sessieFout`. In dat pad vangt de runner ook een andere `RuntimeException` van de sessie en meldt die met
  klasse en zin als `sessieFout`, omdat uchars rapport dezelfde mislukking al als FAIL van die case geeft. Zonder die
  vangst zou de hele analyse een 500 worden. De zinnen van `uitlegFout` zijn ongewijzigd. De CLI (`scenario
  --uitleg`) speelt zo'n case daardoor ook, maar haar uitvoer verandert niet.
- **De JSON van de playground** (`Playground.analyseer`, nieuwe klasse `ScenarioGegevens`). Hij wordt alleen
  uitgebreid; het contract staat hieronder. Daarnaast twee toetsen die snel falen: het rapport en het scenario dragen
  dezelfde naam, en de case die uchars lowering schrijft telt evenveel verwachtingen als het opgeloste scenario. Op
  die gelijkheid rust de koppeling van `positie` aan `ResolvedScenario.verwachtingen()`.
- **`Naamgeving`** kreeg `benaming(b)` (de benaming zelf, of null), `feittype(naam)` en `rol(feittype, rol)`.
  `Evaluatiefout.soort` is publiek geworden voor de soort van een fout in het fixpunt.
- **`playground.html`** toont één scenario tegelijk, met een keuzebalk van knoppen met de status. Per scenario komen
  de verwachtingen met hun bronregel, de situatie en het fixpunt als tabellen. De dekking, de vuringen en de uitleg
  staan in uitklapbare delen. Een filter op label of naam en de keuze "alleen afgeleid en gemaakt" tekenen alleen de
  tabellen opnieuw. Een nieuwe tab Vocabulaires toont het gekozen vocabulaire, de gedeclareerde met hun aantal
  benamingen, en per declaratie de benaming in elk vocabulaire, met een filter op naam en soort. De rasterkolommen
  hebben nu `min-width: 0`, zodat een brede tabel in haar paneel scrolt. Bekeken met een headless Chrome
  (puppeteer, buiten de repository) op s01 in `cgm` en op s04. Er waren geen scriptfouten; de enige consolemelding
  is een 404, vermoedelijk op `/favicon.ico` (niet nagegaan). Het filterveld houdt de focus bij het typen.

### Het JSON-contract (toevoegingen)

Op de wortel, zodra de bron resolvet:
- `vocabulaires`: `{gekozen, gedeclareerd: [{naam, standaard, taal}], elementen: [{soort, eigenaar, canoniek,
  benamingen: {<vocabulaire>: <benaming>|null}}]}`. `elementen` volgt `RegelSpraakModel.benoembaar()` en is leeg
  zonder gedeclareerd vocabulaire.

Per element van `scenario.resultaten`:
- `regel`: de regel van de `Scenario`-kop (`ResolvedScenario.line`).
- per verwachting naast `bron`, `verwacht`, `werkelijk` en `ok`: `regel` (uchars `Verwachting.line`) en
  `regeltekst` (de tekst van die bronregel, gestript).
- `situatie`: `{peildatum|null, objecten: [{label, objecttype, attributen: [{attribuut, waarde|tijdlijn, eenheid?,
  regel}], kenmerken: [{kenmerk, perioden?, regel}], rollen: [{rol, doeltype, labels, meervoudig, regel}]}],
  parameters: [{parameter, waarde|tijdlijn, eenheid?, regel}]}`.
- `fixpunt`: `{objecten: [{object, objecttype, waarden: [{attribuut, waarde|tijdlijn|dimensies, herkomst, invoer?,
  eenheid?}], kenmerken: [{kenmerk, herkomst, invoer?}]}], gemaakteObjecten: [{object, objecttype, creatie,
  onderwerp}], koppelingen: [{feittype, eersteRol, eerste, tweedeRol, tweede}], fouten: [{regel, onderwerp, soort,
  zin}], inconsistenties: [{regel|null, onderwerp|null}]}`, of `fixpuntFout` met de reden. Een MARKER-scenario heeft
  geen van beide.

Vormen: een waarde is een JSON-string (getal met zijn schaal, datum, datum-tijd, tekst, enumeratiewaarde), een
boolean of null (leeg). Een tijdlijn is `[{van, tot, waarde}]`. `herkomst` is `invoer`, `afgeleid` of `creatie`. Een
object heet naar zijn label, een gemaakt object `#<n>`. Een naam staat in het gekozen vocabulaire; waar de canonieke
naam verschilt, staat die ernaast onder `<sleutel>Canoniek`. Volgorde: objecten in de volgorde van de situatie, de
gemaakte objecten erna (op type, creatie, onderwerp en waarden); leden in declaratievolgorde van het type, het
dichtstbijzijnde type eerst; koppelingen, fouten en inconsistenties op naam en object.

### Punt 2: de zin bij een verwachting

uchars API wijst de regel aan waarop een verwachting begint: `VerwachtingResultaat.positie` geeft de plaats in de
case, en die plaats is ook de index in `ResolvedScenario.verwachtingen()`. Daar staat `Verwachting.line`, de regel
van de eerste bullet. Een einde geeft uchar niet. `regeltekst` is daarom die ene regel, en geen omweg naar de hele
zin. Dat is de hele verwachting waar één bullet op één regel staat, zoals in elk document dat de eval-replay schrijft
(`"- " + v` per regel). Wat in uchar ontbreekt om de zin zelf aan te wijzen:
- een eindpositie of de brontekst van de verwachting in `ResolvedScenario.Verwachting`; de grammatica slaat `\n` over
  (`WS`), dus een verwachting mag over meer regels lopen;
- voor een `ATTRIBUUT_TIJDLIJN` de regels van alle segmentbullets: de resolver voegt ze samen tot één verwachting op
  de regel van de eerste.

Ook niet in `ResolvedScenario`: de naam van de gekozen parameterset. Haar waarden staan er wel, elk met de regel in
het `Parameterset`-blok.

### Getoetst op de eval-documenten

De documenten s01 en s04 zijn samengesteld zoals `cmd_replay` en `bin/regelspraak` dat doen: fundament en
herleving-ww van v2 plus het scenariodocument, met `"\n"` ertussen. Ze zijn 1 717 en 1 652 regels lang en zijn
getoetst tegen `PLAYGROUND=true bin/regelspraak serve` op poort 18092.
- s01: `s01-v01` en `s01-v02` PASS. Elke verwachting draagt haar regel (1711, 1717) met de tekst. De situatie telt 13
  objecten en 7 parameters (regels 1602–1608). Het fixpunt telt 13 objecten met 42 waarden en 7 kenmerken.
- s04: `s04-v02` FAIL ("verwacht 2025-09-01, werkelijk 2025-10-01"). Het fixpunt geeft voor P1 `herlevingsdatum
  2025-10-01 afgeleid`, en voor G1 `einddatum-van-een-eindigingsgrond 2025-09-15 invoer` en `herlevingsdatum
  2025-10-01 afgeleid`. De dekking telt 32 van 74 vormen gevuurd.
- `?vocabulaire=cgm`: `objecttype "Recht op uitkering WW bij ontslag"` met `objecttypeCanoniek
  "RechtOpUitkeringWwBijOntslag"`, en `vocabulaires.elementen` telt 268 declaraties, waarvan cgm er 87 benoemt en
  handboek 10.
- Twee analyses op één server, en ook na een herstart van de JVM, geven voor `vocabulaires` en `scenario` dezelfde
  JSON.
- Het antwoord voor s01 groeit van 1 231 380 naar 1 289 508 bytes. Daarvan is 41 674 voor `vocabulaires`; situatie
  en fixpunt zijn 4,7 en 5,7 kB per scenario. Eén analyse van s01 duurt ongeveer 3,4 s.

### Testwijzigingen

Er is geen bestaand testbestand gewijzigd: de uitbreiding dwong dat nergens af, omdat de bestaande tests de sleutels
die zij lezen houden en geen volledige sleutelset pinnen. Er zijn twee nieuwe testklassen:
- `toolchain/…/engine/ScenarioRunnerSessieTest` (3): een gespeeld scenario geeft zijn fixpunt, met de afgeleide
  waarde zonder verwachting. Een deling door nul staat als gegeven in het fixpunt, met de ongewijzigde `uitlegFout`
  en een bean zonder terugzetting. Een MARKER heeft geen sessie.
- `service/…/http/PlaygroundScenarioGegevensTest` (6):
  - situatie, verwachting en fixpunt, met eenheden, regels en herkomst;
  - de fout als gegeven (`DELING_DOOR_NUL`, onderwerp `B`);
  - gemaakte objecten en koppelingen op uchars golden `jutterswet-strandmelding`, met de gemaakte objecten op
    onderwerp gesorteerd en de analyse tweemaal gelijk;
  - inconsistenties en afgeleide kenmerken op `jutterswet-opgave-bedrag`;
  - de vocabulaires en `…Canoniek` op `jutterswet-vocabulaires`;
  - een bron zonder vocabulaire.

### Acceptatie

- (a) Gehaald op s01 en s04, zie hierboven. De sleutels die `workbench.html` leest (`verwachtingen[].bron/verwacht/
  werkelijk/ok`, `dekking`, `trace`, `uitleg`, `graafJson`) zijn ongewijzigd.
- (b) Gelijk binnen één JVM (test) en over een herstart (s01, s04).
- (c) `PlaygroundScenarioGegevensTest.een_evaluatiefout_staat_als_gegeven_in_het_fixpunt`.
- (d) Twee nieuwe testklassen; er is geen bestaand testbestand gewijzigd.
- (e) `make gates` groen op `1814191` (17:02–18:00, 57 min 30 s, waarvan 49 min 6 s kind-smoke; de laptop sliep in
  die stap):
  - pin;
  - reactor: 1574 tests in uchars backend, 129 in toolchain (vloer 120), 255 in service (vloer 235) en 47 in
    service-quarkus (vloer 43), geen skip;
  - tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, en 4
    getuigenissen;
  - replay: 225 goldens met 599 cases (pin 225);
  - manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
  - image `beslisservice:dev`;
  - kind-smoke: H2, H3 en H4 PASS.

### Open

- Een scenario dat uchars replay niet uitspeelde, of een MARKER, heeft in `verwachtingen` niets: uchars rapport geeft
  dan geen verwachtingen. De opgeloste verwachtingen staan wel in `ResolvedScenario`, maar de playground zet ze niet
  apart in het antwoord; daar is niet om gevraagd.
- Twee gemaakte objecten die in type, creatie, onderwerp en elke waarde gelijk zijn, kunnen van plaats wisselen. Wat
  van hen getoond wordt verandert daardoor niet, behalve de koppelingen waarin zij staan. In herleving-ww komt geen
  creatie voor.
- Een waarde in een vorm die geen bean van uchars generator heeft, geeft `fixpuntFout` (`OnweergeefbareWaarde`) in
  plaats van een geraden weergave.

### Na de bekijking in de browser (alleen `playground.html`, de JSON is gelijk)

- **Het eigen type bij een label.** Een rol in de situatie toonde het doeltype dat zij declareert. Daardoor stond R1
  in s04 onder drie typen: `RechtOpUitkeringWwBijOntslag` (recht van U1), `Recht op uitkering WW` (recht van D1) en
  `RechtOpUitkering` (recht van G1, P1). Bij elk label staat nu het objecttype van het object zelf, uit
  `situatie.objecten`. Het gedeclareerde doeltype staat in de tooltip, alleen waar het verschilt. In het fixpunt staat
  bij de objecten van koppelingen, bij het onderwerp van gemaakte objecten, fouten en inconsistenties ook het eigen
  type, uit `fixpunt.objecten`.
- **Van verwachting naar fixpunt.** Een klik op een verwachtingsrij zet het filter op het label van die verwachting,
  klapt het fixpunt open en schuift het in beeld. Het label komt uit uchars korte vorm in `bron` (`CaseRunner.bron`).
  Bij `attributeValue`, `attributeEmpty`, `attributeTimeline`, `characteristicPresent` en `characteristicAbsent` is
  het het tweede woord, want een label is één woord (`UPPER_ID`). Bij een inconsistentie op een object is het het
  woord na ` @ ` (`inconsistent (<regel> @ <LABEL>)`). `consistent` en een inconsistentie zonder label (`inconsistent`,
  `inconsistent (<regel>)`) krijgen geen label, dus geen klik. Het filter vergelijkt een label nu heel, zodat P1 niet
  ook P10 toont; een naam past nog op een deel.
- **Getoetst met headless Chrome (puppeteer, buiten de repository).**
  - De functie gaf voor alle negen vormen het verwachte label of null.
  - Op s04-v02 gaf een klik filter P1 en een geopend fixpunt, met in situatie en fixpunt alleen P1.
  - R1 staat bij elke rol als `RechtOpUitkeringWwBijOntslag`, met in de tooltip "gedeclareerd doeltype: Recht op
    uitkering WW" of "…: RechtOpUitkering".
  - Op uchars golden `jutterswet-opgave-bedrag` met zijn eigen scenario's kregen de vijf attribuut- en
    kenmerkregels V, `- de gegevens zijn consistent` geen label, en de inconsistentie "voor V" kreeg V.
  - Er waren geen scriptfouten.
- `make gates` groen op `66403d0` (18:06–18:14, 8 min 30 s):
  - pin;
  - reactor 1574/129/255/47;
  - tweelingen 3 met 18 cases, de vocabulairetweeling 2 cases in 34 verzoeken, 4 getuigenissen;
  - replay 225 met 599 cases;
  - manifest 29 ok, 3 uitgesloten, 0 mismatch;
  - image;
  - kind-smoke H2, H3 en H4 PASS (1 min 19 s).

### Commits

`e2ef852` kaart · `0cf1de3` claim · `721d4f7` de sessie leest het fixpunt · `71dbff6` de JSON van de playground ·
`9fe674f` de pagina · `1814191` tekst · `18d732a` Result en naar `done/` · `66403d0` eigen type bij een label en klik
naar het fixpunt · deze aanvulling.
