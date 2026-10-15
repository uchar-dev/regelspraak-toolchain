# dienst-geeft-trace-fixpunt-en-dekking: `?trace`, `?fixpunt` en `?dekking` op de beslisroute, achter `DETAIL`

*Vastgelegd op 2026-10-15, kaart C van de reeks "beslisservice via dienst" (besluit van de maintainer, werknotitie
`werkbank/werknotities/20261015-beslisservice-en-intake/beslisservice-verkenning.md` §1, §2 en §5, en het advies
daarnaast). Regelnummers op toolchain `87fd9b9` (submodule op uchar `7fbab5ec`).*

## Aanleiding

De werkbank laat scenario's straks rekenen door de dienstcode in plaats van de playground. Wat zij van een scenario
leest, levert de beslisroute vandaag niet: de vuringen (`trace`), de dekking per vorm en het fixpunt. Het antwoord van
`POST /<pad>` draagt alleen de `uitvoer`-selectors; het fixpunt wordt per verzoek al gelezen en op de fouten na
weggegooid (`Uitvoerder.java:88-89`). uchar heeft sinds `fe8901fb` een publieke waarnemer op het vuren
(`DroolsBackend.Waarnemer`, `vuur(build, sessie, waarnemer)`) en de dekking uit de gevuurde vormen
(`DroolsBackend.dekking(build, vormen)`).

## Wat er gebeurt

1. **Drie queryvlaggen** op `POST /<pad>`, naast `uitleg`, `vocabulaire` en `invoervocabulaire`, gelezen zoals
   `uitleg` (`true`, hoofdletterongevoelig, of de kale naam):
   - `trace`: `trace: [{regel, labels, aantal}]`, uit uchars `Waarnemer` op de sessie van het verzoek; `labels` zijn
     ids van het verzoek.
   - `dekking`: `dekking: [{regel, soort}]`, uit `DroolsBackend.dekking(build, waarnemer.vormen())` over de build van
     het ingangspunt.
   - `fixpunt`: `fixpunt: {objecten, gemaakteObjecten, koppelingen, fouten, inconsistenties}` in de vorm van
     `ScenarioGegevens.fixpunt` van de playground, met de instanties van het verzoek als invoer. Een object heet naar
     zijn id, een gemaakt object `#<n>`; typen heten naar hun naam in het gekozen vocabulaire. Er komt geen
     klassenaam van een bean in het contract. De playground en de dienst renderen het fixpunt met dezelfde code.
   - Met een vlag krijgt ook een 422 (`EVALUATIEFOUT`) de gevraagde velden, zodat de fouten als gegevens naast de
     waarden staan.
   - Namen volgen het gekozen vocabulaire zoals in de playground.
2. **Startinstelling `DETAIL`** (`DETAIL=true` in de omgeving of `--detail`; Quarkus: `DETAIL`), standaard uit, naar
   het voorbeeld van `PLAYGROUND`. Zonder `DETAIL` geeft een detailvlag 400 met de naam van de vlag als `veld`; ze
   wordt niet stil genegeerd. `DETAIL` hoort niet bij het containercontract (`docker/entrypoint.sh`, Helm).
3. **Beide adapters**: `DienstHttp` en `BeslisResource` geven hetzelfde, omdat de kern het antwoord bouwt en de
   instelling bewaakt. Kaart D (playground `via=dienst`) roept de kernmethode aan, niet HTTP.
4. **OpenAPI** (`OpenApiEmitter`): de vlaggen als queryparameters, de nieuwe velden in `Antwoord` en
   `Evaluatiefout`, en `uitleg`, dat nu in het `Antwoord`-schema ontbreekt.

Zonder vlag blijft elk antwoord bytegelijk, ook op een dienst die met `DETAIL` gestart is.

## Acceptatie

- (a) Met `?trace=true&dekking=true&fixpunt=true` op een dienst met `DETAIL` staan de drie velden in het antwoord,
  in de vorm hierboven; de labels van de trace zijn ids van het verzoek.
- (b) Een 422 met `?fixpunt=true` draagt het fixpunt met de fouten als gegevens.
- (c) Zonder `DETAIL` geeft elke detailvlag een 400 met haar naam als `veld`.
- (d) Zonder vlag is het antwoord bytegelijk aan dat van een dienst zonder `DETAIL`, voor een 200 en een 422.
- (e) JDK en Quarkus geven met de vlaggen hetzelfde lichaam, voor een 200, een 422 en de 400 zonder `DETAIL`.
- (f) Een concurrencytoets met `trace` laat zien dat vuringen niet tussen sessies lekken: elk antwoord gelijk aan zijn
  seriële basislijn, en de labels van elke trace zijn ids van het eigen verzoek.
- (g) De playground geeft hetzelfde fixpunt als vóór de kaart (`PlaygroundScenarioGegevensTest` ongewijzigd groen).
- (h) Bestaande tests ongewijzigd; een golden of test die onvermijdelijk verandert, gaat in een eigen commit met
  "testpatch" in de titel, met reden in het Result.
- (i) `make gates` groen.

## Omvang

Middel. May touch: `service/` (`BeslisserKern`, `Uitvoerder`, `VerzoekOpties`, `KernConfig`, `AntwoordBouwer`,
`Evaluatiefout`, `OpenApiEmitter`, een nieuwe klasse voor het fixpunt, `http/DienstHttp`, `http/DienstMain`,
`http/ScenarioGegevens`, `http/Playground`), `service-quarkus/` (`BeslisResource`, `KernProducer`), nieuwe
testklassen, `docs/ENGINE.md`, `README.md`, `Makefile`- en `bin/regelspraak`-hulptekst, kaartverplaatsing. Niet
`uchar/`. Niet: `GET /model` en een scenarioroute (vervallen in het besluit).

## Raakvlakken

Kaart D (playground `via=dienst`) bouwt hierop. De kaart `uitleg-herkomst-uit-ir` loopt tegelijk in een eigen worktree
(Verbalisator, ScenarioRunner); die bestanden raakt deze kaart niet.

## Result (2026-10-15)

### Wat de kaart opleverde

- **De vlaggen op de beslisroute.** `POST /<pad>` leest `trace`, `dekking` en `fixpunt` zoals `uitleg` (`true`,
  hoofdletterongevoelig, of de kale naam; elke andere waarde vraagt niets). De kern bouwt de velden
  (`BeslisserKern.verwerk`, privé `detail(...)`) en zet ze na `uitleg` in het antwoord, in de volgorde `trace`,
  `dekking`, `fixpunt`:
  - `trace`: uit een `DroolsBackend.Waarnemer` per verzoek, met de ids van het verzoek als labels, gegeven aan
    `DroolsBackend.vuur(build, sessie, waarnemer)` via een nieuwe overload `Uitvoerder.vuur(wereld, setGlobals,
    waarnemer)`. Zonder `trace` of `dekking` vuurt de sessie zonder waarnemer, zoals voorheen.
  - `dekking`: `DroolsBackend.dekking(ep.build(), waarnemer.vormen())`.
  - `fixpunt`: het `Fixpunt` dat `Uitvoerder` al las, nu meegegeven in `Uitkomst.fixpunt` en, bij een fout, in
    `Evaluatiefout.fixpunt()`. De invoer is per instantie van het verzoek gelezen van de bean vóór het vuren
    (`FixpuntGegevens.invoer`), omdat de terugzetting na het vuren de afgeleide waarden op dezelfde beans zet.
  - Namen volgen `?vocabulaire=`; in `fixpunt` staat de canonieke naam ernaast (`…Canoniek`), in `trace` en
    `dekking` niet, zoals in de playground.
- **Eén weergave van het fixpunt.** De code van `ScenarioGegevens.fixpunt` staat nu in `service/FixpuntGegevens`, met
  een lijst `Invoerobject`en (label, type, bean, invoerwaarden, ingevoerde kenmerken) als invoer. De playground maakt
  die van de situatie, de dienst van het verzoek. Klassenamen van beans komen niet in het contract; het type van een
  gemaakt object wordt intern via de klasse van zijn bean gevonden, omdat uchars `GemaaktObject` geen type noemt.
- **Een 422 met detail.** `Evaluatiefout` draagt het `Fixpunt` en, als het verzoek een vlag zette, de gevraagde
  velden (`detail()`); `AntwoordBouwer.evaluatiefout` zet die na `fouten`. Een 422 krijgt dus alle gevraagde velden,
  niet alleen `fixpunt`.
- **`DETAIL`.** `KernConfig` kreeg `detail` (`metDetail()`); `DienstMain` zet het met `--detail` of `DETAIL=true`,
  `KernProducer` (Quarkus) met `DETAIL=true`. Zonder `DETAIL` geeft de kern per gezette vlag een schending
  `NIET_ONDERSTEUND` met de naam van de vlag als `veld` ("de dienst is zonder DETAIL gestart en geeft geen trace;
  start hem met DETAIL=true of --detail"), samen met de vocabulairecontrole en vóór het lezen van de body. `docker/` en
  Helm zijn ongewijzigd: `DETAIL` hoort niet bij het containercontract, net als `PLAYGROUND`.
- **Quarkus.** `BeslisResource` leest de drie vlaggen als `@QueryParam`; de oude vijf-argumentenmethode blijft als
  gewone methode (de pariteitstests roepen haar aan).
- **OpenAPI.** Elke operatie noemt de vlaggen `uitleg`, `trace`, `dekking` en `fixpunt` (type boolean). `Antwoord`
  kreeg `uitleg`, `trace`, `dekking`, `fixpunt` en `fixpuntFout`; `Evaluatiefout` de laatste vier. De componenten
  `Trace`, `Dekking` en `Fixpunt` staan na `Info`, zodat de diff van de goldens alleen toevoegt.
- **Tekst.** `docs/ENGINE.md` (een eigen punt onder de dienst, de 400, het OpenAPI-punt, de startregel), `README.md`,
  `Makefile`-hulp en `bin/regelspraak help`.

### Voorbeeld (v2 herleving-ww, ingekort)

Gestart met `bin/regelspraak serve dienst/herleving.dienst.json <fundament en rules> --host 127.0.0.1 --poort 0
--detail`, verzoek `dienst/herkrijging.request.json`, `?trace=true&dekking=true&fixpunt=true`. Het antwoord is
200, 14 419 bytes in 0,09 s (zonder vlag 2 233 bytes). De sleutels zijn `instanties`, `inconsistenties`,
`inconsistentieDetails`, `trace`, `dekking` en `fixpunt`; de eerste drie zijn gelijk aan het antwoord zonder vlag.

```json
{ "instanties": [ { "id": "R1", "type": "RechtOpUitkeringWwBijOntslag",
                    "attributen": { "beëindigingsdatum": "2025-06-01", "herlevingstermijn": "2027-02-28", … } }, … ],
  "inconsistenties": [], "inconsistentieDetails": [],
  "trace": [ { "regel": "kalendermaandsystematiek art. 20 lid 1 onder c en d", "labels": ["G1"], "aantal": 1 },
             { "regel": "herlevingsdatum van het recht art. 21 lid 1", "labels": ["R1", "G1"], "aantal": 10 }, … ],
  "dekking": [ { "regel": "kalendermaandsystematiek art. 20 lid 1 onder c en d", "soort": "GEVUURD" },
               { "regel": "zesmaandentermijn art. 21 lid 3", "soort": "NIET_GERAAKT" }, … ],
  "fixpunt": { "objecten": [ …, { "object": "G1", "objecttype": "Eindigingsgrond",
                 "waarden": [ { "attribuut": "datum aanvang status zelfstandige", "waarde": "2025-06-15",
                                "herkomst": "invoer" },
                              { "attribuut": "beëindigingsdatum", "waarde": "2025-06-01", "herkomst": "afgeleid" }, … ],
                 "kenmerken": [ { "kenmerk": "kalendermaandsystematiek", "herkomst": "afgeleid" }, … ] }, … ],
               "gemaakteObjecten": [], "koppelingen": [], "fouten": [], "inconsistenties": [] } }
```

Tellingen: 30 vormen in de trace, 74 in de dekking (30 GEVUURD, 44 NIET_GERAAKT), vier objecten (R1, U1, G1, P1)
met 24 waarden en 5 kenmerken. Met `vocabulaire=cgm` (15 974 bytes) staat R1 als `"objecttype": "Recht op uitkering
WW bij ontslag", "objecttypeCanoniek": "RechtOpUitkeringWwBijOntslag"` en `maxdatum` als `"attribuut": "Datum einde
basisuitkering WW bij ontslag", "attribuutCanoniek": "maxdatum"`.

**Zonder `DETAIL`** geeft `?fixpunt=true&trace&dekking=TRUE` een 400:
`{"fout": "verzoek valideert niet", "schendingen": [{"veld": "trace", "soort": "NIET_ONDERSTEUND", "fout": "de dienst
is zonder DETAIL gestart en geeft geen trace; start hem met DETAIL=true of --detail"}, {"veld": "dekking", …},
{"veld": "fixpunt", …}]}`. `?trace=false` vraagt niets en geeft het gewone antwoord.

### Wat kaart D kan aanroepen

Kaart D (playground `via=dienst`) heeft HTTP niet nodig:
- `BeslisserKern.bouw(descriptorJson, bronPerBestand, KernConfig.strikt().metDetail(), loader)` (of de vorm met
  `Path`).
- `kern.verwerk(pad, verzoekBoom, new VerzoekOpties(uitleg, vocabulaire, invoervocabulaire, true, true, true))` geeft
  het antwoord met `trace`, `dekking` en `fixpunt` (of `fixpuntFout`) in de vorm die de playground per scenario
  geeft. Een fout aan het fixpunt komt als `Evaluatiefout` met `fouten()`, `fixpunt()` (uchars `Fixpunt`) en
  `detail()` (de gevraagde velden); `AntwoordBouwer.evaluatiefout(fout)` maakt er het 422-lichaam van. Verder
  `DienstFout` (400), `BeslisserKern.NietUitvoerbaar` (MARKER) en `BeslisserKern.OnbekendPad`.
- `FixpuntGegevens.fixpunt(fixpunt, invoer, model, naamgeving)` en `FixpuntGegevens.invoer(beans, typePerLabel,
  naamgeving)` voor wie het fixpunt zelf rendert.
- De afleiding van het verzoek uit een situatie is nog testcode (`SituatieNaarVerzoek`, `service/src/test/…/http`);
  D zet haar naar main. Een verzoekboom die rechtstreeks aan de kern gaat, houdt de schaal van een getal (`20.00`);
  over HTTP niet (zie Open).
- Getoetst: op dezelfde situatie geven playground en dienst hetzelfde `fixpunt` en dezelfde `trace` (BERGING,
  ook bij een deling door nul, en uchars golden `jutterswet-strandmelding` met gemaakte objecten en koppelingen) en
  dezelfde `dekking` (BERGING), zowel over HTTP als via de kern.
- D moet een scenario op een pad afbeelden (descriptorlogica, zoals `GoldenOverHttpTest.padVoorScope`) en voor
  PASS/FAIL uchars vergelijking hebben (U1 in de werknotitie).

### Testwijzigingen

Nieuwe testklassen:
- `service/…/http/DetailHttpTest` (10): de drie velden bij een 200 met het antwoord zelf ongewijzigd; alleen wat
  gevraagd is; een 422 met `trace` en `fixpunt` en de fout als gegeven; dezelfde vorm als de playground (200, 422,
  gemaakte objecten); de kern zonder HTTP met de schaal van de playground; zonder `DETAIL` een 400 per vlag met haar
  naam, en `trace=false` bytegelijk; zonder vlag bytegelijk aan een dienst zonder `DETAIL` (200 en 422); de
  queryparsing, `--detail` en `KernConfig`.
- `service/…/http/DetailConcurrencyTest` (1): de drie families van `ConcurrencyTest` (TOKA 17, 20, 30) met elf
  verzoeken, elk met eigen ids (voorvoegsel `R<n>-`), 60 tegelijk, drie rondes. Elk antwoord met alle vlaggen is
  gelijk aan zijn seriële basislijn; een vuring uit een andere sessie zou een rij of een telling toevoegen. Daarnaast
  is elk label van een trace en elk object van een fixpunt (op `#<n>` na) een id van het eigen verzoek.
- `service-quarkus/…/DetailParityTest` (4): JDK en Quarkus geven byte voor byte hetzelfde lichaam voor een 200 met
  alle vlaggen, een 422 met `fixpunt`, een antwoord in `cgm`, en de 400 zonder `DETAIL`.

De testpatch staat apart in `591165d` ("testpatch: OpenAPI-goldens en de velden van Evaluatiefout"):
- de vier byte-goldens `service/src/test/resources/openapi/{toka-belastingdienst, jutterswet-vocabulaires,
  jutterswet-vocabulaires.cgm, jutterswet-vocabulaires.handboek}.golden.json`, opnieuw uitgeschreven uit
  `target/openapi/`; de diff heeft alleen toegevoegde regels (1 302 in TOKA, 272 in elk van de andere drie);
- `OpenApiEmitterTest.de_422_beschrijft_het_lichaam_van_een_evaluatiefout`: de veldenlijst van `Evaluatiefout` is
  nu `status, fout, fouten, trace, dekking, fixpunt, fixpuntFout` (was `status, fout, fouten`), `required` blijft
  `status, fout, fouten`; de javadoc zegt dat de laatste vier niet verplicht zijn.
  Reden: de 422 draagt met een vlag die velden, en de test pint dat het schema de velden van het lichaam noemt.
`OpenApiVocabulaireTest` controleert verder dat `vocabulaire` en `invoervocabulaire` de eerste twee parameters zijn
en dat een document zonder vocabulaires `invoervocabulaire` niet noemt; dat blijft zo.

De playground is na de verplaatsing van het fixpunt bytegelijk: met een tijdelijk harnas (niet gecommit) gaven 49
documenten voor en na dezelfde JSON: de 32 van het TOKA-manifest, de acht v2-toetsdocumenten van herleving-ww
canoniek en in `cgm`, en uchars golden `jutterswet-vocabulaires` met zijn scenario in `cgm`. Samen 347 fixpunten,
met gemaakte objecten, fouten en namen in `cgm`.

### Keuzes

- **`DETAIL` in de kern**, niet in de adapters: de kern weigert de vlaggen, dus JDK en Quarkus doen hetzelfde en
  kaart D gebruikt dezelfde poort zonder HTTP.
- **De soort van de 400 is `NIET_ONDERSTEUND`**, geen nieuwe soort: die zegt al dat de dienst, zoals hij gestart is,
  deze vorm van het verzoek niet draagt. De zin noemt `DETAIL`.
- **Een 422 krijgt alle gevraagde velden**, niet alleen `fixpunt`: de trace en de dekking bestaan ook bij een fout.
- **`trace` en `dekking` zonder `regelCanoniek`**, zoals de playground ze per scenario geeft; zo kan D ze
  ongewijzigd overnemen.
- **Invoer van het fixpunt van de bean**, niet uit de JSON van het verzoek: de bean draagt de waarde zoals de kern
  haar las (eenheid omgerekend, enumeratie genormaliseerd). Een attribuut dat het verzoek op `null` zet, is daardoor
  geen invoerrij.

### Acceptatie

- (a) `DetailHttpTest.trace_dekking_en_fixpunt_bij_een_antwoord`, en het v2-voorbeeld hierboven.
- (b) `DetailHttpTest.een_422_krijgt_het_fixpunt`, `dezelfde_fout_als_de_playground`.
- (c) `DetailHttpTest.zonder_detail_een_400_met_de_naam_van_de_vlag`, `DetailParityTest.zonder_detail_is_de_400_gelijk`.
- (d) `DetailHttpTest.zonder_vlag_bytegelijk` (200 en 422).
- (e) `DetailParityTest` (4).
- (f) `DetailConcurrencyTest`.
- (g) `PlaygroundScenarioGegevensTest` ongewijzigd groen, en het harnas over 49 documenten.
- (h) Eén testpatch-commit, `591165d`, beschreven onder Testwijzigingen.
- (i) Zie Gates.

### Open

- **Schaal over HTTP.** Beide adapters lezen de body met Jackson's standaard-nodefactory, die nullen achter de komma
  weghaalt: `20.00` wordt `20`. Dat gold al voor het antwoord; het fixpunt van de dienst toont daardoor een
  invoerbedrag zonder de schaal die de situatie schrijft, waar de playground haar houdt. Via de kern met een
  verzoekboom met exacte getallen is er geen verschil (`de_kern_zonder_http_geeft_de_vorm_van_de_playground`).
  `JsonNodeFactory.withExactBigDecimals(true)` op `DienstHttp.MAPPER` en de mapper van `BeslisResource` zou het
  oplossen, maar verandert gewone antwoorden; dat is een eigen beslissing.
- **ENGINE.md over de playground** zegt dat `?vocabulaire=` in de trace en de dekking per scenario de canonieke naam
  naast een afwijkende naam zet; de code (`Playground.rendScenario`) doet dat niet. De dienst volgt de code.
- **Het `Info`-schema** zet `dekking` op hoofdniveau, waar `/info` haar per ingangspunt geeft (werknotitie §5); niet
  in deze kaart.
- **Het type van een gemaakt object** vindt `FixpuntGegevens` via de klasse van de bean, omdat uchars
  `GemaaktObject` geen typenaam draagt. Een uchar-kaart kan die naam geven.
- **Bindadres.** `DienstMain` bindt standaard aan `0.0.0.0`; een start met `DETAIL` hoort op `127.0.0.1` (kaart W2
  van de werknotitie). Deze kaart dwingt dat niet af.
- **Een onweergeefbare waarde** heet nu "… toont het fixpunt niet" (was "… toont de playground niet"), ook in de
  `fixpuntFout` van de playground. Geen test of document bereikt die tak.
- **Quarkus** is op het niveau van de resource getoetst, zoals de andere pariteitstests; er is geen `@QuarkusTest` met
  `DETAIL=true`.

### Gates

`make gates` groen op `591165d` (de commit daarna verplaatst alleen deze kaart), 9 min 42 s:
- pin;
- reactor: 1593 tests in uchars backend, 139 in toolchain, 280 in service (vloer 235) en 51 in service-quarkus
  (vloer 43), geen skip;
- tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, 4 getuigenissen;
- replay: 225 goldens met 599 cases (pin 225);
- manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
- image `beslisservice:dev`;
- kind-smoke: H2, H3 en H4 PASS.

### Commits

`fba1021` kaart · `5f1003d` claim · `8275ad3` het fixpunt van de playground naar FixpuntGegevens · `755b272` trace,
dekking en fixpunt op de beslisroute, achter DETAIL · `591165d` testpatch: OpenAPI-goldens en de velden van
Evaluatiefout · deze commit (Result en naar `done/`).
