# playground-via-dienst: `POST /playground/analyse?via=dienst` laat de scenario's rekenen door de dienst

*Vastgelegd op 2026-10-15, kaart D van de reeks "beslisservice via dienst" (besluit van de maintainer, werknotitie
`werkbank/werknotities/20261015-beslisservice-en-intake/beslisservice-verkenning.md` en het advies daarnaast,
`fable-advies-beslisservice-architectuur.md` §3 "Eenvoudiger alternatief"). Toolchain `91caaf8`, uchar-pin naar
`a3ac41fe` (de vergelijking publiek, uchar-kaart `vergelijking-publiek`).*

## Aanleiding

Op de eval-pagina van de werkbank moet PASS betekenen: de beslisservice geeft dit antwoord. Nu rekent de playground
een scenario zelf, met uchars scenariolaag over het hele document. De dienst rekent anders: alleen de vormen van de
scope van een ingangspunt, met de verzoekmapping en de parameters van de descriptor. Kaart C (`91caaf8`) gaf de kern
`trace`, `dekking` en `fixpunt` achter `DETAIL`; uchar `a3ac41fe` gaf `DroolsBackend.vergelijk(build, sessie, label,
expected)`, het oordeel over een teruggezette sessie met de labels van de afnemer.

## Wat er gebeurt

`POST /playground/analyse?via=dienst` geeft hetzelfde antwoord als zonder de vlag (parse, resolver, IR, oordeel,
DRL, graaf, vocabulaires), alleen `scenario.resultaten[]` komt van de dienst waarmee het proces draait. Per scenario:

1. Het document wordt opgelost zoals nu. Het scenario wordt een dienstverzoek met `SituatieNaarVerzoek`, dat van
   `service/src/test/…/http/` naar main gaat.
2. De scope van het scenario wordt het `pad` van een ingangspunt van de descriptor van de dienst (descriptorlogica,
   zoals `GoldenOverHttpTest.padVoorScope`). Geen ingangspunt: het scenario krijgt een zichtbare weigering, geen
   terugval op de playground-route.
3. De dienst rekent: `kern.verwerk` met uitleg, trace, dekking en fixpunt. Het oordeel komt van uchar:
   `DroolsBackend.vergelijk` met de `expected` van de case die uchars frontend van het scenario maakt, op de
   teruggezette sessie van het verzoek, met de ids van het verzoek als labels. De kern krijgt daarvoor een interne
   ingang die de verwachtingen meeneemt; geen nieuwe HTTP-route, en geen kopie van de vergelijking.
4. Een evaluatiefout (422) is FAIL met een diagnose zoals uchars rapport. Een situatie die het verzoek niet draagt
   (tijdlijn, dimensie: `NIET_ONDERSTEUND`) is MARKER met die zin.
5. Elk resultaat noemt het `pad` waarop de dienst rekende. De werkbank hoeft niets aan haar leesvorm te veranderen.
6. `via=dienst` rekent met de bronnen van de dienst. Het hele verzoek krijgt een 400 met een zin als de geposte
   bronnen niet die van de dienst zijn; de toets bewijst dat het model gelijk is (het IR-document van de geposte
   bronnen zonder scenario's tegen dat van de dienst), geen naamvergelijking. Zonder `DETAIL` geeft `via=dienst` een
   400 die `DETAIL` noemt.

## Acceptatie

- (a) Op documenten binnen de scope van een ingangspunt geeft `via=dienst` hetzelfde oordeel (status en per
  verwachting `ok`) als de gewone route, met `pad` per resultaat; de vorm van het resultaat is die van de gewone
  route.
- (b) Een document met andere bronnen dan die van de dienst: 400 met een zin; ook bij een gelijk aantal declaraties
  met dezelfde namen.
- (c) Een scenario waarvoor geen ingangspunt bestaat: een zichtbare weigering in dat resultaat.
- (d) Een dienst zonder `DETAIL`: 400 die `DETAIL` noemt.
- (e) Een evaluatiefout is FAIL met diagnose; een situatie met een tijdlijn is MARKER met de zin.
- (f) Meting op de eval-documenten van de werkbank (`GET /api/eval`), met een eigen dienst op de v2-descriptor van
  herleving-ww (`PLAYGROUND=true DETAIL=true`): per scenario het oordeel van de gewone route naast dat van
  `via=dienst`; verschillen verklaard.
- (g) Bestaande tests ongewijzigd; alleen een importpad mag, in een eigen commit "testpatch" met reden.
- (h) `docs/ENGINE.md` beschrijft `via=dienst`. `make gates` groen.

## Omvang

Middel. May touch: `service/` (`BeslisserKern`, `Uitvoerder`, `Evaluatiefout`, `http/Playground`, `http/DienstHttp`,
`http/SituatieNaarVerzoek` van test naar main, nieuwe testklassen), `service/pom.xml` (commentaar over de test-jar),
`docs/ENGINE.md`, `README.md`, kaartverplaatsing. Niet `uchar/`, niet de Quarkus-adapter (die heeft geen playground).

## Raakvlakken

Kaart E (werkbank) zet de queryparameter en start de dienst met `DETAIL`.

## Result (2026-10-15)

### Wat de kaart opleverde

- **`?via=dienst` op `POST /playground/analyse`** (`DienstHttp.verwerkPlaygroundAnalyse`, `Playground.analyseer(bron,
  vocabulaire, kern)`). Het antwoord is dat van de playground (parse, resolver, vocabulaires, IR, oordeel, DRL, graaf);
  alleen `scenario.resultaten[]` komt van de kern van het proces. Elk resultaat heeft de sleutels van de gewone route,
  met `pad` na `regel`.
- **Weigering van het hele verzoek (400)**, vóór enige analyse of vóór de scenario's:
  - zonder `DETAIL`: `{"veld": "via", "soort": "NIET_ONDERSTEUND", "fout": "de dienst is zonder DETAIL gestart en
    rekent geen scenario's via de dienst; start hem met DETAIL=true of --detail"}`;
  - een andere waarde van `via`: `MALFORMED` op `via` ("via kent alleen de waarde 'dienst', niet '…'");
  - **de bronnentoets**: het `uchar-ir-v1`-document dat uchars emitter van de geposte bron schrijft (de playground
    berekende het al voor `ir`; de scenario's staan er niet in) moet als JSON-boom gelijk zijn aan dat van de dienst,
    `BeslisserKern.document()`: het gegeven document bij een `.json`-bron, of het document van de `.rgs`-bronnen in de
    volgorde van de dienst (één keer uitgeschreven, daarna bewaard). Anders `NIET_ONDERSTEUND` op `bron`, met het
    eerste verschil als JSON-pointer, bijvoorbeeld "eerste verschil /rules/rules/30/versions/0/condition/op: de geposte
    bronnen hebben \"greater\", de dienst \"greaterOrEqual\"" (één vergelijking in `hww-17` veranderd: zelfde namen,
    zelfde aantal declaraties).
  Een bron met een parse- of resolverfout krijgt haar diagnostiek, zoals zonder de vlag.
- **Per scenario** (`Playground.rendViaDienst`):
  1. `BeslisserKern.padVoorScope(scope)`: de vormen van de scope van het scenario (zonder scope: alle vormen van het
     model, `VormIndex.alle()`), genormaliseerd zonder versie, tegen de vormen van de scope van elk ingangspunt
     (`VormIndex.scope`). Eerst een exacte treffer, anders het ingangspunt met de minste vormen erbij; bij gelijkspel
     het eerste in de descriptor. Geen treffer: MARKER, `pad` null, en een `diagnose` die per ingangspunt zegt wat
     het mist.
  2. `SituatieNaarVerzoek.bouw(scenario)` (nu in main): elke instantie met haar label als id, canonieke namen.
  3. `BeslisserKern.beoordeel(pad, verzoek, opties, expected)` met `uitleg`, `trace`, `dekking`, `fixpunt`,
     `vocabulaire` uit de query (anders `canoniek`) en `invoervocabulaire=canoniek`; `expected` is die van
     `CaseEmitter.emit(scenario)`. De kern loopt de gewone weg van `verwerk`; `Uitvoerder.vuur(…, verwachtingen)` roept
     na `zetAfgeleideWaarden` en vóór `dispose` `DroolsBackend.vergelijk(build, sessie, label::get, verwachtingen)` aan,
     met de ids van het verzoek als labels. De toolchain vergelijkt niets zelf.
  4. Oordeel: `Vergelijking.pass()` geeft PASS of FAIL, met de `verwachtingen` en de `diagnose` van uchar, voorafgegaan
     door de naam van het casebestand (`CaseEmitter.bestandsnaam`), zoals uchars rapport dat doet. Een 422
     (`Evaluatiefout`) is FAIL zonder verwachtingen met `<case>: ERROR <exceptie>: <zin>`; de exceptie is wat uchars
     terugzetting werpt (één fout: die fout; twee of meer: uchars `Evaluatiefouten`), en `trace`, `dekking` en
     `fixpunt` komen uit de 422, met `uitlegFout`. MARKER met de zin: geen ingangspunt, `SituatieNaarVerzoek.NietDraagbaar`
     (tijdlijn, kenmerk met perioden, breuk zonder decimale vorm), een 400 waarvan elke schending `NIET_ONDERSTEUND` is,
     `NietUitvoerbaar` en `CaseRunner.Excluded`. Elke andere 400 is FAIL met de schendingen.
- **Evaluatiefout** draagt nu als oorzaak de exceptie die uchars terugzetting voor de fouten werpt: `Uitvoerder` roept
  bij fouten aan het fixpunt `zetAfgeleideWaarden` aan, die werpt en niets terugzet. Het lichaam van de 422 is
  ongewijzigd (het leest `fouten()`); bij één fout is de oorzaak hetzelfde object als voorheen.
- **Tekst**: `docs/ENGINE.md` (een punt onder de playground), `README.md` (de rij `serve`), `bin/regelspraak help`, de
  `Makefile`-hulp, het commentaar over de test-jar in `service/pom.xml`.

### Meting op de eval-documenten van de werkbank

`GET http://localhost:8089/api/eval` (2026-10-15 en opnieuw 2026-10-16, bytegelijk): 37 scenario's, waarvan 7 met een
scenariodocument (s01–s07, samen 11 Scenario-blokken; s08–s37 zijn SKIP in de replay). De bron per document is zoals de
werkbank haar samenvoegt (`evaldata.js` `evalDelen`, `api.js` `bronVan`): de 33 bronnen van `bronnen` en
`replay.document`, verbonden met `"\n"`. Eigen dienst van deze branch op `127.0.0.1:18431`, `PLAYGROUND=true
DETAIL=true`, `bin/regelspraak serve …/v2/herleving-ww/dienst/herleving.dienst.json` met het fundament en de rules in
de volgorde van het `Makefile` (`$(sort …)`). Per document twee keer `POST /playground/analyse`, zonder en met
`?via=dienst`, op dezelfde dienst. De laatste meting draaide op `1cbcc77`; de dienst is daarna gestopt.

| Document | Scenario | Gewone route | via=dienst | `pad` | Verschil |
|---|---|---|---|---|---|
| s01 | s01-v01 | PASS (herlevingsdatum 2025-08-01) | PASS (2025-08-01) | beoordeel-herleving | `uitleg`: 3 van 20 zinnen |
| s01 | s01-v02 | PASS (datum einde basisuitkering 2026-10-31) | PASS (2026-10-31) | beoordeel-herleving | `uitleg`: 3 van 20 zinnen |
| s02 | s02-v01 | PASS (2025-02-21) | PASS | beoordeel-herleving | geen |
| s02 | s02-v03 | PASS (2026-10-08) | PASS | beoordeel-herleving | geen |
| s03 | s03-v01 | PASS (2025-06-16) | PASS | beoordeel-herleving | geen |
| s03 | s03-v02 | PASS (2026-09-14) | PASS | beoordeel-herleving | geen |
| s04 | s04-v01 | PASS (2026-06-30) | PASS | beoordeel-herleving | geen |
| s04 | s04-v02 | FAIL (verwacht 2025-09-01, werkelijk 2025-10-01) | FAIL (zelfde) | beoordeel-herleving | geen |
| s05 | s05-v01 | PASS (608; 2026-03-31) | PASS | beoordeel-herleving | geen |
| s05 | s05-v02 | PASS (2025-07-01) | PASS | beoordeel-herleving | geen |
| s06 | s06-v01 | PASS (aow-datum 2025-09-15) | PASS | beoordeel-herleving | geen |
| s07 | s07-v03 | PASS (2024-12-16) | PASS | beoordeel-herleving | geen |

"Geen" betekent: `status`, `diagnose`, `verwachtingen` (met `regel` en `regeltekst`), `trace`, `dekking`, `fixpunt`,
`uitleg` en `situatie` gelijk als JSON; de sleutels van elk resultaat zijn die van de gewone route plus `pad`. Met
`?vocabulaire=cgm` geeft dezelfde vergelijking hetzelfde beeld. Tijd per document: gewone route 1,8–3,6 s, via=dienst
1,8–2,2 s.

Verklaring van de verschillen:
- **Geen verschil in oordeel.** De scenario's van de dataset noemen geen scope, dus ze rekenen met alle 67 vormen van
  het model; het ene ingangspunt `beoordeel-herleving` draagt die 67 vormen (gemeten met `VormIndex`), dus het is een
  exacte treffer en de dienst vuurt dezelfde vormen als de gewone route. Het scenariodocument zet de parameters van de
  descriptor in een Parameterset (`eval/runner/cli.py`, `_parameterset`), en het verzoek draagt ze als parameters, dus
  ook die zijn gelijk. De IR van de geposte bronnen was in alle zeven documenten gelijk aan die van de dienst.
- **s04-v02 FAIL** op beide routes: de regels geven herlevingsdatum 2025-10-01 waar het scenario 2025-09-01 verwacht.
  Dat is een bevinding over regelset of dataset, niet over de route.
- **`uitleg` in s01**: drie zinnen over de herlevingsgrens (I4, I5, I6) tonen het inkomen via de dienst als
  "2.800,00 EUR", op de gewone route als "2.800,00". *(gevolgtrekking uit de code)* De Verbalisator zet de eenheid
  achter een operand uit het type van de expressie (`Verbalisator.renderOperand`, `eenheidWoord(e.type())`). De dienst
  geeft hem het model van de resolver (`BeslisserKern.voegUitlegToe`), waarin een attribuutlezing haar type draagt; de
  gewone route geeft hem het model dat uchars lader uit het IR-document leest (`ScenarioRunner.uitleg`,
  `build.model()`), en "een read uit uchars lader draagt zelf geen type" (commentaar bij `Verbalisator.enumeratieVan`).
  Het oordeel raakt dit niet.

### Testwijzigingen

Nieuwe testklasse `service/…/http/PlaygroundViaDienstTest` (36 tests):
- `via_dienst_geeft_het_oordeel_van_de_gewone_route` (26 dynamische tests en een telling): elk golden TOKA-scenario
  dat uchars scenarioresolver neemt (de exit-0-regels van het manifest, zonder de drie van `GoldenOverHttpTest
  .UCHAR_WEIGERT`), op een eigen dienst van de companions van dat scenario, met de ingangspunten van
  `examples/toka/toka.dienst.json` waarvan de regels in die companions staan. Via de dienst gelijk aan de gewone route:
  `status`, `diagnose`, `verwachtingen`, `situatie`, de sleutels plus `pad`, en `pad` is het ingangspunt dat
  `GoldenOverHttpTest.padVoorScope` kiest. 26 vergeleken, 26 PASS. (Op de hele TOKA-bron kan dat niet: uchars
  scenarioresolver weigert een scope in een document met een startpuntbepaling.)
- `een_fail_heeft_de_diagnose_van_de_gewone_route`: een verwachting die niet houdt; zelfde `diagnose`
  (`case1-berging.json: …`), `verwachtingen`, `fixpunt`, `trace`, `dekking`.
- `een_evaluatiefout_is_een_fail_met_de_diagnose_van_uchars_rapport`: deling door nul; FAIL met dezelfde `diagnose`
  als de gewone route (`case1-berging.json: ERROR DelingDoorNul: …`), de fout in `fixpunt.fouten`, een trace en
  `uitlegFout`.
- `een_tijdlijn_in_de_situatie_is_een_marker`: uchars golden `jutterswet-dagentelling-rgs`, scenario `voorbeeld`
  (kenmerk met perioden): gewone route PASS, via=dienst MARKER met de zin van `SituatieNaarVerzoek`.
- `zonder_ingangspunt_een_zichtbare_weigering`: een ingangspunt met één van de twee regels; MARKER, `pad` null, de zin
  noemt wat het ingangspunt mist.
- `andere_bronnen_dan_die_van_de_dienst_een_400`: één regel anders ("gedeeld door" wordt "maal"), zelfde namen en
  aantallen: 400 op `bron` met het eerste verschil; de eigen bronnen geven 200 en PASS; de kern weigert hetzelfde
  zonder HTTP.
- `het_eerste_verschil`: de JSON-pointer bij een waarde, een arraylengte en een ontbrekende sleutel.
- `zonder_detail_een_400_die_DETAIL_noemt`, `een_andere_waarde_van_via_is_een_400`, `dezelfde_vorm_over_http`.

Eerst rood: vijf tijdelijke mutaties (gemeten, niet bewaard), elk laat minstens één test vallen: status altijd PASS,
geen bronnentoets, elk ingangspunt een treffer, geen DETAIL-toets, geen vergelijking door uchar.

Geen bestaande test gewijzigd en geen testpatch: `SituatieNaarVerzoek` staat in hetzelfde pakket, dus
`GoldenOverHttpTest`, `DetailHttpTest`, `ConcurrencyTest` en `DetailConcurrencyTest` vinden haar zonder import, en
`GoldenOverQuarkusTest` laadt haar bij naam via de klassenlader van uchar, die de main-jar van de dienst ook ziet
(30/30 groen).

### Keuzes

- **Scope naar pad over vormen, niet over regelnamen.** `GoldenOverHttpTest.padVoorScope` vergelijkt de regelnamen
  van de descriptor; de kern vergelijkt de vormen die `VormIndex` van een scope maakt, dezelfde index waarmee de
  ingangspunten gebouwd zijn. Zo telt een beslistabel met haar rijen en een defaultsgroep met haar stapel.
- **Zonder scope: alle vormen van het model.** Zo rekent uchars scenariolaag zo'n scenario (`CaseEmitter.metScope`
  zonder scope). Het ingangspunt moet ze alle dragen; anders MARKER. Een ruimer ingangspunt dan de scope mag (zoals
  `padVoorScope`), het kleinste eerst; het `pad` zegt welk.
- **MARKER voor wat de dienst niet uitrekent**, FAIL voor een antwoord dat anders is: een 422 en een 400 die geen
  `NIET_ONDERSTEUND` is (bijvoorbeeld `ONTBREKENDE_INSTANTIE`) zijn FAIL, want de dienst geeft dan niet het verwachte
  antwoord.
- **De diagnose met de naam van het casebestand**, zoals uchars rapport, zodat een gelijk oordeel ook een gelijke
  diagnose heeft.
- **Namen**: het verzoek met canonieke namen en `invoervocabulaire=canoniek` (de situatie is canoniek opgelost, en een
  liberale lezing kan een naam dubbelzinnig vinden); het antwoord in `?vocabulaire=`, anders canoniek, zoals de gewone
  route, ook als de descriptor een standaardvocabulaire noemt.
- **De bronnentoets vergelijkt het hele document**, ook de bronregels die uchars IR draagt (7 keer `source.line` in
  v2). Bronnen in een andere volgorde zonder ander document (fnd-01 en fnd-02 omgewisseld) worden aanvaard; één
  kopregel extra wordt geweigerd ("eerste verschil /rules/consistencies/0/versions/0/source/line: de geposte bronnen
  hebben 104, de dienst 103"). De werkbank voegt de bronnen samen zoals de dienst ze leest, dus dit gaat goed.
- **De exceptie van uchars terugzetting als oorzaak van elke `Evaluatiefout`**, ook op de beslisroute: anders zou
  `getCause()` per route iets anders betekenen. Het 422-lichaam verandert niet.
- **`via=dienst` alleen in de JDK-dienst**: de Quarkus-adapter heeft geen playground.

### Acceptatie

- (a) `via_dienst_geeft_het_oordeel_van_de_gewone_route` (26 TOKA-scenario's), `een_fail_…`, `dezelfde_vorm_over_http`,
  en de meting hierboven (11 van 11 gelijk).
- (b) `andere_bronnen_dan_die_van_de_dienst_een_400`, en in de meting de veranderde vergelijking in `hww-17`.
- (c) `zonder_ingangspunt_een_zichtbare_weigering`.
- (d) `zonder_detail_een_400_die_DETAIL_noemt`.
- (e) `een_evaluatiefout_is_een_fail_met_de_diagnose_van_uchars_rapport`, `een_tijdlijn_in_de_situatie_is_een_marker`.
- (f) Zie de meting.
- (g) Geen bestaande test gewijzigd, geen testpatch.
- (h) `docs/ENGINE.md`; gates hieronder.

### Wat kaart E (werkbank) moet zetten

- De eval-pagina en "alle scenario's draaien" sturen `POST /playground/analyse?via=dienst` (via `/api/analyse`, met
  `vocabulaire` erbij zoals nu) naar een dienst die gestart is met de v2-descriptor
  `rulesets/regelspraak-rules/v2/herleving-ww/dienst/herleving.dienst.json` en de bronnen zoals `make serve` ze geeft,
  met `PLAYGROUND=true DETAIL=true` (of `--detail`), op `127.0.0.1`.
- De bron blijft de samenvoeging van nu (de bronnen van `/api/eval` en het scenariodocument met `"\n"`); een andere
  volgorde of een extra regel ervoor geeft een 400 op `bron`.
- Een resultaat heeft `pad` extra; verder dezelfde sleutels. Een 400 (veld `bron` of `via`) heeft `fout` en
  `schendingen` en geen analyse; `heeftFout` en `AnalyseFout` in `detail.js` tonen zo'n antwoord nu al, en `api.js`
  bewaart het niet.
- `#/regelset` en andere regelsets blijven op de ruimere playground (8092): via=dienst weigert elke bron die niet die
  van de dienst is.

### Open

- **Geen fixpunt** (`Uitvoerder` werpt een `IllegalStateException` met uchars zin, de beslisroute geeft dan 500): via
  de dienst valt dan de hele analyse (500), waar uchars rapport dat scenario FAIL geeft. Het verschil tussen deze
  exceptie en een interne fout is aan het type niet te zien; een eigen type in `Uitvoerder` zou het scheiden. Geen
  document in de tests of de meting bereikt het.
- **`uitleg` per model**: de eenheid in de uitleg verschilt tussen het resolvermodel (dienst) en het lader-model
  (gewone route), zie de meting. Dat hoort bij de Verbalisator (de open noot over haar tweede evaluatie in de
  werknotitie), niet bij deze kaart.
- **Bronregels in de bronnentoets**: een bron die alleen in regelnummers afwijkt, wordt geweigerd. Een toets zonder
  `source.line` zou ruimer zijn, maar die regels horen bij uchars document; niet gedaan.
- **Bindadres**: `DienstMain` bindt standaard aan `0.0.0.0`; met `DETAIL` hoort de start op `127.0.0.1` (W2), niet
  afgedwongen.
- **`ENGINE.md` over de playground** noemt nog een canonieke naam naast de naam in de trace en de dekking per scenario
  (open punt van kaart C); via=dienst volgt de code.

### Gates

`make gates` groen op `1cbcc77` (de commit daarna raakt alleen deze kaart), 8 min 45 s:
- pin (uchar `a3ac41fe`);
- reactor: 1601 tests in uchars backend, 140 in toolchain, 316 in service (was 280; 36 nieuw) en 51 in
  service-quarkus, geen skip;
- tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, 4 getuigenissen;
- replay: 225 goldens met 599 cases (pin 225);
- manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
- image `beslisservice:dev`;
- kind-smoke: H2, H3 en H4 PASS (cluster daarna verwijderd).

### Commits

`79ca8eb` uchar-pin naar a3ac41fe · `f6ab721` kaart · `2ba4789` claim · `9baa1c6` SituatieNaarVerzoek van de testtak
naar main · `1cbcc77` playground via=dienst · deze commit (Result en naar `done/`).
