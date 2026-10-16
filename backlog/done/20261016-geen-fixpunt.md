# geen-fixpunt: een run zonder fixpunt is een 422 op de beslisroute en een FAIL per scenario, geen 500

*Vastgelegd op 2026-10-16, uit het open punt "Geen fixpunt" van de kaart `20261015-playground-via-dienst`. Toolchain
`2335f01`, uchar-pin `a3ac41fe`.*

## Aanleiding

Bereikt een run geen fixpunt, dan geeft uchars `DroolsBackend.vuur` een `Vuring` met de zin in `geenFixpunt()`: het
vuurplafond (`max(VLOER, FACTOR × R × N0)`) of het creatiebudget (`TmsTranspiler.CREATIEBUDGET`, RUNTIME-SEMANTICS
ch. 5.5) is bereikt. `Uitvoerder.java:115` werpt dan een `IllegalStateException` met die zin. Gevolg:

- de beslisroute (`POST /<pad>`) geeft een 500 `interne fout`, in JDK en Quarkus; de zin staat alleen in de log, en
  aan het type is het verschil met een interne fout niet te zien;
- `POST /playground/analyse?via=dienst` faalt als geheel (500): alle scenario's van het document verliezen hun
  uitkomst.

uchars rapport (`CaseRunner.rapport`, via `runCase`) maakt van zo'n case een FAIL met diagnose `<case>: <zin>`, zonder
verwachtingen, met de trace en de dekking van wat vuurde, en speelt de andere cases door.

## Wat er gebeurt

1. Een eigen fouttype in de service, `GeenFixpunt`, met uchars zin als boodschap; `Uitvoerder` werpt het waar nu de
   `IllegalStateException` staat. De toolchain beoordeelt de zin niet: plafond en budget gaan dezelfde weg.
2. De beslisroute geeft voor `GeenFixpunt` een 422, naar het voorbeeld van een evaluatiefout:
   `{"status": "GEEN_FIXPUNT", "fout": <uchars zin>}`. Met detailvlaggen krijgt de 422 `trace` en `dekking` van wat
   vuurde, en `fixpuntFout` met de zin voor `fixpunt` (er is geen fixpunt om te tonen). JDK (`DienstHttp`) en Quarkus
   (`BeslisResource`) geven hetzelfde. Het OpenAPI-schema van de 422 beschrijft beide lichamen.
3. `?via=dienst` vangt `GeenFixpunt` per scenario: FAIL met `diagnose` `<case>: <zin>`, zoals uchars rapport, zonder
   verwachtingen, met `trace` en `dekking` uit de 422, `uitlegFout` en `fixpuntFout`. De andere scenario's lopen door.

## Acceptatie

- (a) Het document `uchar/corpus/rood/jutterswet-budgetgrens-over/bron.rgs` (een keten van creaties die het
  creatiebudget overschrijdt) met twee scenario's: Wachter W1 met rang 1 (budget overschreden) en met rang 50
  (fixpunt). `?via=dienst` geeft FAIL voor het eerste en PASS voor het tweede; het FAIL-resultaat heeft dezelfde
  `status`, `diagnose`, `verwachtingen`, `trace` en `dekking` als de gewone route, en de diagnose begint met de naam
  van het casebestand en het fragment dat uchar in `case1.json` (`expectedFault.drools`) pint.
- (b) De beslisroute geeft voor W1 met rang 1 een 422 met `status` `GEEN_FIXPUNT` en uchars zin als `fout`; JDK en
  Quarkus geven dezelfde status en hetzelfde lichaam, zonder en met `trace`, `dekking` en `fixpunt`.
- (c) De kern werpt `GeenFixpunt` (geen `IllegalStateException`); een 500 blijft voor interne fouten.
- (d) De OpenAPI-422 noemt het lichaam van `GeenFixpunt` naast dat van `Evaluatiefout`.
- (e) Eerst rood: de nieuwe tests falen op `2335f01` (500, of een `IllegalStateException` uit de analyse).
- (f) Bestaande tests ongewijzigd. Raakt het nieuwe 422-schema een golden of test, dan gaat die wijziging in een eigen
  commit met "testpatch" in de titel, alleen mechanisch, beschreven in het Result.
- (g) `docs/ENGINE.md` beschrijft de 422 en het FAIL per scenario. `make gates` groen op de eindcommit.

## Omvang

Klein. May touch: `service/` (`Uitvoerder`, nieuw `GeenFixpunt`, `BeslisserKern`, `AntwoordBouwer`, `OpenApiEmitter`,
`http/DienstHttp`, `http/Playground`), `service-quarkus/` (`BeslisResource`), nieuwe testklassen, de OpenAPI-goldens
(testpatch), `docs/ENGINE.md`, kaartverplaatsing. Niet `uchar/`.

## Result (2026-10-16)

### Wat de kaart opleverde

- **`GeenFixpunt`** (`service/…/GeenFixpunt.java`), een eigen `RuntimeException` met uchars zin als boodschap.
  `Uitvoerder.vuur` werpt het waar `Vuring.geenFixpunt()` niet null is (de plaats van de oude
  `IllegalStateException`); er wordt dan niets gelezen of teruggezet. De toolchain onderscheidt plafond en budget niet:
  uchars zin zegt welk het was.
- **De kern** (`BeslisserKern.verwerk`) vangt het naast `Evaluatiefout` en hangt met detailvlaggen de gevraagde velden
  eraan: `trace` en `dekking` van wat vuurde tot het einde van de run, en voor `fixpunt` het veld `fixpuntFout` met
  uchars zin, want er staat geen fixpunt om te tonen (`detail(…, zonderFixpunt, …)`). Zonder vlag gaat het ongewijzigd
  door.
- **De beslisroute**: `DienstHttp` en `BeslisResource` geven een 422 met `AntwoordBouwer.geenFixpunt`:
  `{"status": "GEEN_FIXPUNT", "fout": <uchars zin>}`, met de detailvelden erna. Elke andere exceptie blijft 500.
- **`?via=dienst`** (`Playground.rendViaDienst`): het scenario wordt FAIL met `diagnose` `<case>: <zin>`, de regel die
  uchars rapport voor zo'n case geeft (`CaseRunner.runCase`: `naam + ": " + geenFixpunt`, zonder `ERROR`), zonder
  verwachtingen, met `trace`, `dekking` en `fixpuntFout` uit de 422 en `uitlegFout` "de dienst bereikte geen fixpunt
  (422) en geeft dan geen uitleg". De andere scenario's van het document lopen door.
- **OpenAPI**: de 422 van elke operatie is `oneOf` [`Evaluatiefout`, `GeenFixpunt`], met een beschrijving die beide
  noemt; het component `GeenFixpunt` (verplicht `status` met enum `GEEN_FIXPUNT` en `fout`; verder `trace`,
  `dekking`, `fixpuntFout`) staat na `Fixpunt`.
- **Tekst**: `docs/ENGINE.md` (een punt over de 422 `GEEN_FIXPUNT` onder de dienst, een punt onder via=dienst, de
  zin over de 422 bij OpenAPI); de javadoc van de geraakte klassen.

### Voorbeeld

Eigen dienst van deze branch op `127.0.0.1:18517` (`PLAYGROUND=true bin/regelspraak serve estafette.dienst.json
uchar/corpus/rood/jutterswet-budgetgrens-over/bron.rgs --detail`, descriptor zoals in `GeenFixpuntTest`), daarna op
PID gestopt.

`POST /estafette` met W1 rang 1, ketennummer 1: 422

```json
{"status":"GEEN_FIXPUNT","fout":"creation budget exhausted: objectcreatie \"werf de lange keten\" van stratum 0 zou een object maken op aanmaakdiepte 101, voor een Wachter op diepte 100 — een keten van aanmaken die niet stopt is een fout, nooit een stille afkapping (RUNTIME-SEMANTICS ch. 5.5)"}
```

Met `?trace=true&dekking=true&fixpunt=true`: 422 met de sleutels `status`, `fout`, `trace`, `dekking`, `fixpuntFout`;
`trace` = `opvolgerrang van een wachter` [W1] 201, `werf de lange keten` [W1] 100, `rangplafond` [] 1, `eerste keten
aan het plafond` [] 1; `dekking` vier vormen `GEVUURD`, `tweede keten aan het plafond` `NIET_GERAAKT`; `fixpuntFout`
gelijk aan `fout`. Met W1 rang 50: 200, `opvolgerrang` 51.

`POST /playground/analyse?via=dienst` met `bron.rgs` en twee scenario's ("keten vanaf rang 1", verwacht opvolgerrang
2; "keten vanaf rang 50", verwacht 51): 200, eerste resultaat

```json
{"naam": "keten vanaf rang 1", "status": "FAIL",
 "diagnose": "case1-keten-vanaf-rang-1.json: creation budget exhausted: objectcreatie \"werf de lange keten\" van stratum 0 zou een object maken op aanmaakdiepte 101, voor een Wachter op diepte 100 — een keten van aanmaken die niet stopt is een fout, nooit een stille afkapping (RUNTIME-SEMANTICS ch. 5.5)",
 "regel": 38, "pad": "estafette", "verwachtingen": [], "dekking": […], "trace": […], "uitleg": [],
 "uitlegFout": "de dienst bereikte geen fixpunt (422) en geeft dan geen uitleg", "situatie": {…},
 "fixpuntFout": "creation budget exhausted: …"}
```

het tweede PASS (`werkelijk` 51). Naast de gewone route: `status`, `diagnose`, `verwachtingen`, `trace`, `dekking` en
`situatie` gelijk voor beide scenario's; alleen `uitlegFout` en `fixpuntFout` van het FAIL-scenario verschillen in
bewoording (de gewone route: "uchars replay speelde de case niet uit: …" en "de eigen sessie speelt deze case niet:
…"), zoals bij de evaluatiefout. De sleutels zijn die van de gewone route plus `pad`.

### Acceptatie

- (a) `GeenFixpuntTest.via_dienst_het_scenario_zonder_fixpunt_is_een_FAIL_en_het_andere_een_PASS` en
  `via_dienst_over_http_een_200`.
- (b) `GeenFixpuntTest.de_beslisroute_geeft_een_422_met_uchars_zin`, `met_de_detailvlaggen_trace_dekking_en_fixpuntFout`;
  `GeenFixpuntParityTest` (Quarkus): zonder en met de drie vlaggen dezelfde 422 en byte voor byte hetzelfde lichaam.
- (c) `GeenFixpuntTest.de_kern_werpt_GeenFixpunt_met_uchars_zin` (rang 1 werpt, rang 50 antwoordt 51).
- (d) `GeenFixpuntTest.de_openapi_422_noemt_het_lichaam_van_GeenFixpunt`.
- (e) Rood gemeten op `5241d86` met alleen een kale klasse `GeenFixpunt` (nergens geworpen), zodat de test compileert:
  6 van 6 tests rood, vijf failures (500 `interne fout` waar 422 of 200 verwacht werd, een `IllegalStateException`
  waar `GeenFixpunt` verwacht werd, geen `oneOf` in de 422) en één error (de `IllegalStateException` met uchars zin uit
  `Playground.analyseer(…, kern)`). `GeenFixpuntParityTest` is niet apart rood gemeten; zijn JDK-helft is dezelfde 500.
- (f) Testpatch apart, zie hieronder.
- (g) `docs/ENGINE.md`; gates hieronder.

### Testwijzigingen

Nieuw:
- `service/…/http/GeenFixpuntTest` (6): op uchars rode golden `jutterswet-budgetgrens-over` (`bron.rgs` uit de
  submodule, het fragment uit `case1.json` `expectedFault.drools`), met een eigen descriptor over de vijf regels.
- `service-quarkus/…/GeenFixpuntParityTest` (2).

Testpatch `0fef7d7` ("testpatch: OpenAPI-goldens en het pad van de 422 naar oneOf"), alleen mechanisch:
- de vier byte-goldens `service/src/test/resources/openapi/{toka-belastingdienst, jutterswet-vocabulaires,
  jutterswet-vocabulaires.cgm, jutterswet-vocabulaires.handboek}.golden.json`, opnieuw uitgeschreven uit
  `target/openapi/`: per operatie de beschrijving en de `$ref` van de 422 (nu `oneOf`), en het component
  `GeenFixpunt` erbij (TOKA +349/−58 regels, elk van de andere drie +52/−4 regels, samen +505/−70);
- `OpenApiEmitterTest.de_422_beschrijft_het_lichaam_van_een_evaluatiefout`: de `$ref` van `Evaluatiefout` wordt
  gelezen op `…/schema/oneOf/0/$ref` in plaats van `…/schema/$ref`; de verwachte waarde en de rest van de toets zijn
  ongewijzigd.

Reden: de 422 heeft nu twee lichamen, en de toets en de goldens pinnen het schema van de 422.

### Keuzes

- **Een eigen status, geen soort van `Evaluatiefout`.** Een evaluatiefout staat aan het fixpunt, en `Evaluatiefout
  .Soort` is ook de soort van `fixpunt.fouten`; zonder fixpunt staat er niets. Daarom `GEEN_FIXPUNT` als status naast
  `EVALUATIEFOUT`, met `fout` = uchars zin, en een `oneOf` in het contract.
- **`fixpuntFout` in plaats van niets** voor de vlag `fixpunt`: een gevraagd veld valt niet stil weg.
- **De diagnose zonder `ERROR`**: uchars rapport geeft een case zonder fixpunt niet als exceptie (`onspeelbaar`) maar
  als de zin van `vuur` na de casenaam; via=dienst volgt dat, zodat de diagnose gelijk is aan die van de gewone route.
- **Het creatiebudget als toets, niet het vuurplafond**: uchar weigert een kring bij de bouw, en geen document haalt
  het plafond; uchars getuigen halen het met een kleine factor via een package-private naad. Het budget gaat dezelfde
  weg (`Vuring.geenFixpunt`) en heeft een rode golden in uchar.

### Open

- **De stacktrace van `Creatiebudget` in de testuitvoer**: bij elke run die het budget raakt, staat de exceptie met
  stacktrace in de uitvoer van surefire; die komt niet uit de toolchain (die vangt `GeenFixpunt` en logt niets). Niet
  onderzocht waar Drools of uchar haar schrijft.
- **`OpenApiEmitterTest.eersteVerschil`** gooide bij de rode golden-run een `StringIndexOutOfBoundsException` (`regelBij`,
  "Range [3568, 3567)") in plaats van de bedoelde verschilmelding. De toets faalt dan nog steeds, maar met een
  minder bruikbare melding. Niet aangepast: bestaande toets.

### Gates

`make gates` groen op `0fef7d7` (de commit daarna raakt alleen deze kaart), 9 min 58 s:
- pin (uchar `a3ac41fe`);
- reactor: 1601 tests in uchars backend, 140 in toolchain, 322 in service (was 316; 6 nieuw) en 53 in
  service-quarkus (was 51; 2 nieuw), geen skip;
- tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, 4 getuigenissen;
- replay: 225 goldens met 599 cases (pin 225);
- manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
- image `beslisservice:dev`;
- kind-smoke: H2, H3 en H4 PASS (cluster daarna verwijderd).

### Commits

`755ae78` kaart · `5241d86` claim · `dad3416` geen fixpunt: een 422 op de beslisroute en een FAIL per scenario ·
`0fef7d7` testpatch · deze commit (Result en naar `done/`).
