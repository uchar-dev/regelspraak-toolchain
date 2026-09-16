# dienst-keten: de HTTP-dienst bedient een subtype-instantie volledig

Aanleiding: de overerving-port (`backlog/done/20260912-overerving-port.md`,
afwijking 2) liet negen rauwe facetlezingen buiten de vertaallaag staan,
alle onder `service/`: `OpenApiEmitter` (destijds r. 157/164 en 225/229),
`DescriptorLoader` (r. 166), `VerzoekMapper` (r. 430/431/438/439) — de
regelnummers zijn van die kaart en kunnen verschoven zijn. De
eigen-kaartlezingen laten geërfde leden uit het OpenAPI-schema weg en een
subtype-instantie is via de dienst dus nog niet volledig te bedienen. De
maintainer wil dat gedicht.

## Acceptatie

- (a) Elke rauwe facetlezing onder `service/` is óf ketenbewust gemaakt
  (de `*Leden()`-accessors, het patroon van de port), óf blijft per
  declarerend type met de gemeten reden in code én Result (het
  port-precedent: sommige iteraties horen raw).
- (b) Een end-to-end-getuige met een EIGEN, rechtenvrije fixture-bronset
  (een `is een`-subtype naast de TOKA-set, niet erin): het
  OpenAPI-schema van het subtype toont de geërfde attributen en
  kenmerken; een verzoek dat geërfde attributen als invoer draagt mapt en
  beantwoordt; de descriptor laadt een regel op een geërfd lid. De
  verwachte antwoordwaarden HANDBEREKEND vóór het draaien, in het Result.
- (c) De volledige poortenset groen (de tien poorten van
  `backlog/done/20260916-poorten-herstel.md`), inclusief de
  `CLAUDE.md`-vloeren; nieuwe tests verhogen `service ≥ 145` niet
  verplicht (vloeren zijn ondergrenzen) maar het Result noemt de nieuwe
  aantallen.
- (d) De gebakken TOKA-dienst (`docker/Dockerfile`, `TokaBron`) blijft
  onaangeraakt door de fixture; geen wijziging aan de TOKA-descriptor.
- (e) Bestaande tests alleen wijzigen met gemelde reden per geval;
  `vendor/`, `SPEC_REV`, `scripts/` alleen bij aantoonbare noodzaak met
  reden.

## Result (2026-09-16)

### 1. De negen geboekte lezingen — plek, oordeel, wijziging

De regelnummers van de port-kaart klopten alle negen nog. Oordeel:
**alle negen worden ketenbewust; geen enkele blijft bewust raw.** Het
port-precedent (`FactModelGenerator.alleLeden`,
`ContractValidator`) blijft per declarerend type omdat de gegenereerde
bean native `extends` en een herdeclaratie het veld van de ouder zou
verbergen. Geen van de negen plekken onder `service/` heeft zo'n
mechanisme: een OpenAPI-eigenschappenverzameling kent geen overerving,
en de verzoekmapper werkt per CONCRETE instantie. Daar is de eigen kaart
lezen geen bewuste beperking maar een weglating. Dat staat nu als reden
in de code (`OpenApiEmitter.objecttypeSchema`, met de expliciete
tegenstelling tot de fact-model-generator).

| # | plek nu | leest | oordeel | wijziging |
|---|---------|-------|---------|-----------|
| 1 | `OpenApiEmitter:157` | attributen-kaart (eigen) | keten | `ot.attribuutLeden()` |
| 2 | `OpenApiEmitter:164` | kenmerken-lijst (eigen) | keten | `DescriptorLoader.kenmerkLeden(ot)` |
| 3 | `OpenApiEmitter:225` | `attribuutGranulariteit` + `attribuutTijdlijnAantal` | keten | `granulariteitVan` / `tijdlijnAantalVan` |
| 4 | `OpenApiEmitter:229` | `kenmerkGranulariteit` + `kenmerkTijdlijnAantal` | keten | `kenmerkGranulariteitVan` / `kenmerkTijdlijnAantalVan` |
| 5 | `DescriptorLoader:166` | kenmerken-lijst (eigen) | keten | `ot.kenmerkNaam(naam) != null` |
| 6 | `VerzoekMapper:430` | `attribuutGranulariteit` | keten | `granulariteitVan` |
| 7 | `VerzoekMapper:431` | `attribuutTijdlijnAantal` | keten | `tijdlijnAantalVan` |
| 8 | `VerzoekMapper:438` | `kenmerkGranulariteit` | keten | `kenmerkGranulariteitVan` |
| 9 | `VerzoekMapper:439` | `kenmerkTijdlijnAantal` | keten | `kenmerkTijdlijnAantalVan` |

De modelaccessors zijn hoofdletter-ongevoelig (`opKeten` matcht exact en
dan `equalsIgnoreCase`), dus de tolerantie die 6–9 expliciet droegen
blijft precies staan. `ot.dimensiesVan` (r. 159) en `ot.attribuut`
(`VerzoekMapper.zetAttributen`) waren al ketenbewust — dat is de reden
dat een geërfd ATTRIBUUT als invoer vandaag al werkte en alleen de
kenmerk- en tijdlijnbenen rood stonden.

**Eén nieuwe plek, want de kenmerken zijn een `List` en geen ledenkaart:**
`opKetenKaart` dekt ze niet, dus het model levert er geen
`kenmerkLeden()` bij. `DescriptorLoader.kenmerkLeden(Objecttype)` is de
lijstvormige tweeling van `heeftKenmerk`, zodat `service/` de
kenmerkketen op precies één plek loopt. `vendor/` is niet aangeraakt.

### 2. Drie plekken erbuiten — dezelfde relatie, één stap verder

Buiten de negen stond de TYPEvraag zelf nog op naamgelijkheid. Dat is de
verkeerde relatie: de transpilers lageren een regel als een patroon op
het gedeclareerde type, dat de bean native erft, dus een regel op het
supertype vuurt op de subtype-instantie. Een descriptor die tegen dat
supertype is geschreven — de natuurlijke schrijfwijze, want daar staat
de regel — bediende die instantie dan niet.

- `DescriptorLoader.objectMatch` → `ot.isEen(objecttype(model, naam))`;
- `BeslisserKern.ontbrekendeInvoer` gebruikt hem voor het invoercontract;
- `AntwoordBouwer` gebruikt hem voor de uitvoerselectoren;
- `engine/Verbalisator.verbaliseer` (r. 76) selecteerde de
  kenmerktoekenningsregels per instantie ook op naamgelijkheid — het
  uitlegverhaal achter `?uitleg=true` is deel van de HTTP-dienst, dus
  deze hoort erbij.

Op een model zonder subtypes is de keten één element lang en is dit
letterlijk de oude naamgelijkheid; de bestaande dienst verandert er niet
van (145 bestaande service-toetsen groen, 33 quarkus, 115 engine, de
OpenAPI-golden byte-identiek). Dit gaat verder dan acceptatie (a) en
staat daarom in een EIGEN commit (`8ce9ec9`), apart terug te draaien.

### 3. De fixture en de handberekende verwachtingen

`service/src/test/resources/keten/jutterswet-strandvondsten.rgs` +
`jutterswet.dienst.json` — een eigen, rechtenvrij Jutterswet-domein,
naast de TOKA-set, met niets ervan gedeeld. Onder de testresources en
niet onder `examples/`, omdat `CorpusVuurTest` die map met exacte
emmertellingen partitioneert (de afwijking-1-reden van de port-kaart).

```
Objecttype de Strandvondst (mv: Strandvondsten)
    de vondstwaarde Numeriek (niet-negatief geheel getal) met eenheid eur;
    het bergloon Numeriek (niet-negatief getal met 2 decimalen) met eenheid eur;
    de meldtermijn Numeriek (niet-negatief geheel getal) voor elke dag;
    is aangemeld kenmerk (bijvoeglijk);
    is meldplichtig kenmerk (bijvoeglijk);
    is bergingsplichtig kenmerk (bijvoeglijk);
    is verjaard kenmerk (bijvoeglijk) voor elke dag;

Objecttype het Wrakstuk is een Strandvondst (mv: Wrakstukken)
    de bergingsdiepte Numeriek (niet-negatief geheel getal) met eenheid m;
```

Het subtype declareert ALLEEN `bergingsdiepte`; al het overige is
geërfd, inclusief twee tijdlijnleden. Drie regels, drie entrypoints:
`bergloon` (invoer `Strandvondst`, uitvoer `Strandvondst.bergloon`),
`meldplicht` (invoer `Wrakstuk`, uitvoer `Wrakstuk:meldplichtig` en
`Wrakstuk:aangemeld`) en `bergingsplicht` (invoer `Strandvondst`,
uitvoer `Strandvondst:bergingsplichtig`).

**Handberekend vóór de eerste draai.** De bron is zo gekozen dat de
berekening exact uitkomt: `vondstwaarde` heeft nul decimalen, maal de
factor 0,25 geeft er twee, precies wat `bergloon` declareert. (De eerste
opzet met een 2-decimalen-vondstwaarde werd door de resolver terecht
geweigerd — "meer decimalen (4) dan het attribuut toelaat (2)", §6.1.2.)

| verzoek | instantie | invoer | handberekend |
|---------|-----------|--------|--------------|
| `/bergloon` | W1 (Wrakstuk) | vondstwaarde 240 eur, aangemeld ja | bergloon 240 × 0,25 = **60,00 eur** |
| `/bergloon` | W2 (Wrakstuk) | vondstwaarde 240 eur, aangemeld nee | voorwaarde onwaar → **leeg** (JSON null) |
| `/bergloon` | S1 (Strandvondst) | vondstwaarde 80 eur, aangemeld ja | bergloon 80 × 0,25 = **20,00 eur** |
| `/meldplicht` | W1 | vondstwaarde 240 eur | 240 > 100 → **meldplichtig true** |
| `/meldplicht` | W2 | vondstwaarde 80 eur | 80 > 100 onwaar → **false** |
| `/bergingsplicht` | W1 | vondstwaarde 600 eur | 600 > 500 → **bergingsplichtig true** + uitlegzin |
| `/bergingsplicht` | W2 | vondstwaarde 400 eur | 400 > 500 onwaar → **false** + uitlegzin |

Alle zeven reproduceren. `KetenDienstTest` pint ze in dertien toetsen,
inclusief één echte HTTP-ronde over `DienstHttp` (200 + 60,00) en de
drie negatieve randen (geërfd tijdlijn-attribuut, geërfd
tijdlijn-kenmerk, een lid dat de keten NIET declareert).

### 4. De rood-eerst-meting

Gemeten met een tijdelijke probe op de ONGEWIJZIGDE code, vóór er iets
bewoog. De probe is daarna verwijderd; wat hij afdrukte staat hier.

**(a) De dienst START niet eens.** `BeslisserKern.bouw` over de fixture:

```
dienst start niet: descriptor/model-validatie faalt (2 schending(en)):
  ONBEKENDE_VERWIJZING [entrypoints[1].uitvoer[0]]:
      onbekend kenmerk 'meldplichtig' op objecttype 'Wrakstuk'
  ONBEKENDE_VERWIJZING [entrypoints[1].uitvoer[1]]:
      onbekend kenmerk 'aangemeld' op objecttype 'Wrakstuk'
```

Dat is lezing 5 (`DescriptorLoader:166`) alleen. `heeftKenmerk(Wrakstuk,
'aangemeld')` gaf `false`.

**(b) Het OpenAPI-schema laat vier van de vijf leden weg.** Met een
descriptor zonder kenmerkselectoren (zodat de kern wél start):

```
schema Wrakstuk properties = {"bergingsdiepte": …}          ← één lid
schema Wrakstuk description = "Objecttype 'Wrakstuk'."      ← verder niets
```

tegenover het supertype, dat in dezelfde draai `vondstwaarde`,
`bergloon`, `aangemeld` en `meldplichtig` draagt én beide tijdlijnleden
bij naam afwijst. Gemeten weglating: 4 van de 5 bedienbare leden
verdwenen uit het contract, en 0 van de 2 afwijzingen werd genoemd.

**(c) Het duurste geval is geen weglating maar een verkeerd oordeel.**
Een verzoek met het geërfde tijdlijn-attribuut op de subtype-instantie:

```
tijdlijn-attribuut meldtermijn op Wrakstuk
  -> java.lang.ClassCastException: Cannot cast java.math.BigDecimal to java.util.List
```

De rauwe `isTemporeel` antwoordt "geen tijdlijn", de mapper accepteert
de waarde als scalar, en `RegelSpraakRuntime.set` duwt een `BigDecimal`
in het geërfde `List<Segment>`-veld. Geen 400 die het veld noemt maar
een crash binnen de dienst. Na de wijziging:
`NIET_ONDERSTEUND [instanties[0].attributen.meldtermijn]`.

**(d) Twee lezingen waren GEMASKEERD, eerlijk genoteerd.** Lezingen 3/4
en 8/9 waren op de ongewijzigde code niet los te meten: het
tijdlijn-KENMERK liep eerst tegen lezing 5 aan
(`ONBEKEND_VELD: onbekend kenmerk 'verjaard'`), en de tijdlijnleden
bereikten de OpenAPI-classificatie niet zolang de iteratie zelf de eigen
kaart las. Zij worden pas rood zódra hun buurman gedicht is — en dan
meteen fout, niet leeg: `meldtermijn` zou als `{"type":"integer"}` in het
contract komen te staan, een eigenschap die de dienst bij het verzoek
weigert. Ze zijn daarom als PAAR gedicht en hebben hun eigen getuige.

**(e) Buiten de negen, ook gemeten.** Een Wrakstuk-instantie tegen
`invoer: ["Strandvondst"]`:

```
DienstFout: ONTBREKENDE_INSTANTIE [instanties]: entrypoint 'bergloon van
een strandvondst' vereist minstens één instantie van objecttype
'Strandvondst', maar het verzoek levert er geen
```

En het uitlegverhaal van `/bergingsplicht?uitleg=true` op W1/W2:

```
["W1 is meldplichtig, omdat zijn vondstwaarde (600 eur) is groter dan 100 eur",
 "W2 is meldplichtig, omdat zijn vondstwaarde (400 eur) is groter dan 100 eur"]
```

— geen woord over `bergingsplichtig`, terwijl dat kenmerk op beide
instanties wel degelijk was beoordeeld. De regel staat op het supertype.

### 5. Poortenstand

Alle tien groen, exitcodes expliciet gemeten (`; echo $?`, nooit door een
pipe), gedraaid op de EINDtoestand van de boom.

| poort | uitkomst | exit |
|---|---|---|
| `make verify-corpus` | 54 passed, 0 failed, 0 known-defect (of 54) | 0 |
| `service/` `mvn -o -B test` | **158** tests, 0F/0E/0S (basis 145 + 13 nieuw) | 0 |
| `service-quarkus/` `mvn -o -B test` | 33 tests, 0F/0E/0S | 0 |
| `make scenario-test` | golden TOKA 29/29 PASS, marker-exit-3 ok | 0 |
| `mvn -o -B test` (root) | 980 tests, 0F/0E, 3 skipped | 0 |
| `make test` | 553 gevonden / 552 groen / 0 gefaald / 1 aborted, 34 containers | 0 |
| `make engine` | 115 tests, 0F/0E | 0 |
| `make facts` | PASS, 5 klassen gegenereerd + gecompileerd | 0 |
| `make verify-negatief-corpus` | 52 snippets, 41 rejected, 11 as expected (1 `[GAT]`), 4 strict cross-checks | 0 |
| `make uchar-conformance` | PASS, 58 bestanden byte-identiek, 47 goldens gereproduceerd | 0 |

De installatieketen was nodig: `mvn -o install -DskipTests` in de root en
in `engine/` vóór `service/`, en opnieuw na de `Verbalisator`-wijziging.

### 6. Acceptatie

- (a) Alle negen ketenbewust, met het oordeel en de reden in code én
  hierboven. Geen enkele blijft raw; het port-precedent geldt hier niet,
  en dat staat als reden in `OpenApiEmitter`. ✔
- (b) Eigen, rechtenvrije fixture; schema, verzoek-antwoord en descriptor
  alle drie gepind; verwachtingen handberekend vóór de draai. ✔
- (c) Tien poorten groen. ✔
- (d) `TokaBron`, `examples/toka/toka.dienst.json` en `docker/Dockerfile`
  onaangeraakt — de fixture staat ernaast, met een eigen descriptor. ✔
- (e) Geen enkele bestaande test gewijzigd. `vendor/`, `SPEC_REV` en
  `scripts/` hebben een lege diff. ✔

### 7. Afwijkingen en twijfels

1. **Ik ben buiten de negen gegaan**, in `service/` (`objectMatch`,
   `ontbrekendeInvoer`, `AntwoordBouwer`) én één keer buiten `service/`
   (`engine/Verbalisator`). De kaart noemt alleen facetlezingen; de
   kop van de kaart vraagt een volledig bediende subtype-instantie, en
   zonder deze stap is zij dat niet. De wijziging zit apart in commit
   `8ce9ec9` zodat de beheerder haar los kan afwijzen.
2. **Het subtype-schema is VLAK, geen OpenAPI `allOf`.** Overwogen en
   verworpen: het document belooft "precies de verzoekvorm die de mapper
   accepteert", en de mapper kent geen compositie. Een `allOf` zou elke
   consument extra werk geven voor dezelfde informatie. De keuze staat
   met haar reden in de code.
3. **Twee lezingsparen hadden geen los meetbare rode toestand** (punt 4d).
   Ze zijn gedicht met hun buurman en hebben een eigen getuige, maar de
   rood-eerst-meting is voor hen een AFGELEIDE: ik heb hun foutbeeld
   beredeneerd uit de mapper-crash op het attribuutbeen, niet los
   gemeten. Dat is de eerlijke grens van deze meting.
4. **`make scenario-test` geeft 29/29**, conform `CLAUDE.md` sinds de
   poorten-herstelkaart. Het ene abort in `make test` en de drie skips in
   de root-suite zijn de pre-existente assumptie-bewaakte stiltes die de
   poorten-herstelkaart al noteerde; ongewijzigd.
5. **De poorten zijn op de eindtoestand gedraaid**, niet per commit. Per
   commit is alleen de service-suite (145 bestaande toetsen) groen
   nagemeten; de tussentoestanden zijn no-ops op het vlakke TOKA-model.

### 8. Commits

- `fd3115f` — de negen facetlezingen ketenbewust (+ `kenmerkLeden`)
- `8ce9ec9` — het ⊑ aan de dienstgrens (invoer, uitvoer, uitleg)
- `4a906f9` — de eigen fixture en de dertien getuigen
- deze kaart
