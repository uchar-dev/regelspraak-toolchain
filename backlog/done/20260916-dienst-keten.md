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

Twee rondes. De lensronde bevestigde de eerste ronde op elk punt en vond
er één defect onder: de scope-volledigheidspoort droeg hetzelfde gebrek
één laag dieper (§3). Zij mat ook de twee lezingsparen los die hier
eerst als redenering stonden (§5d) en corrigeerde een telling (§5b). Die
correcties staan hieronder verwerkt, niet los eronder geplakt.

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

### 3. De scope-volledigheidspoort was ⊑-blind — dezelfde klasse, één laag dieper

Gevonden door de lensronde (2026-09-16), nagemeten en gedicht in deze
ronde. `ProducentConsument.ElementSleutel` sleutelde op de objecttypenaam
die de RAKENDE REGEL toevallig doorloopt: de consumentsleutel droeg het
subjecttype van de lezende regel, de producentsleutel het doeltype van de
schrijvende. Producent op het supertype plus consument op het subtype
gaven dus twee verschillende sleutels, waarna de poort concludeerde "geen
producent, dus echte verzoekinvoer" en de scope doorliet. Het geërfde
kenmerk evalueerde daarna stil als onwaar.

**Rood-eerst gemeten, drie regels naast elkaar** (vlakke controle, dan
beide ketenrichtingen; producent in een EIGEN bronbestand, dus werkelijk
buiten de gebouwde KieBase):

```
VLAK  producent+consument op Persoon
  -> geweigerd: SCOPE_ONVOLLEDIG … leest kenmerk 'volwassen' van 'persoon',
     maar de enige producent(en) [bepaal volwassen] vallen buiten de scope
(a)   producent Persoon / consument Student
  -> START LUKT, antwoord = {"id":"S1","attributen":{"korting":null}}
(b)   producent Student / consument Persoon
  -> START LUKT, antwoord = {"id":"P1","attributen":{"korting":null}}
```

Met de producent WEL in scope is het antwoord 5,00 EUR. De sleutels:
producent `[persoon, volwassen, true]`, consument `[student, volwassen,
true]` — nooit gelijk.

**De fix zit in de sleutel, niet in de vergelijking.** Een lid wordt ééns
gedeclareerd en daarna geërfd, dus de identiteit van een element is zijn
DECLARERENDE type (ch. 2.2). `ProducentConsument.elementVan` sleutelt
daarop; `producentIndex`, `verbruikt` en de `invoerElementen`-opt-out gaan
er alle drie doorheen. Daarmee is de ⊑-bewustheid één keer geschreven in
plaats van bij elke lezer, en hoeft geen aanroeper te veranderen.

**Beide richtingen, met de gekozen poortgedraging.**

| richting | dekking | keuze | gemeten na de fix |
|---|---|---|---|
| (a) producent supertype, consument subtype | volledig | melden | `SCOPE_ONVOLLEDIG`, identiek aan de vlakke controle |
| (b) producent subtype, consument supertype | partieel | melden | `SCOPE_ONVOLLEDIG` |

Richting (b) is een keuze en geen automatisme: die producent bedient
alleen subtype-instanties. De poort vraagt of er een producent BESTAAT
die buiten de scope viel, en een gedeeltelijke producent buiten de scope
is een producent buiten de scope. Zwijgen zou betekenen dat een
Student-instantie in het verzoek het kenmerk stil als onwaar leest — het
gebrek waarvoor deze poort bestaat. Zo kan de ketenlezing alleen maar
MEER melden dan de vlakke, nooit minder.

**Het ontsnappingsluik sprak geen van beide spellingen.** De
`invoerElementen`-opt-out bouwde zijn sleutel uit de descriptorstring en
was even ⊑-blind. Na de fix silencen `Persoon:volwassen` én
`Student:volwassen` dezelfde consumptie: een luik dat wel bestaat maar
niet te spellen is, is geen luik.

**Neveneffect, gemeten: de graaf deelde de fout en is mee genezen.** De
README-invariant is dat de scope-poort en de graafextractie dezelfde
IR-feiten lezen. Op een ketenmodel tekende de graaf het lid TWEE keer, en
allebei die beweringen waren onwaar:

```
vóór: e0 'volwassen · persoon' KENMERK eind=true      (eind-output die niemand leest)
      e3 'volwassen · student' INVOER  eind=false     (invoer die niemand schrijft)
      edges: r0->e0, r1->e1, e2->r0, e3->r1           (6 nodes)
na:   e0 'volwassen · persoon' KENMERK eind=false
      edges: r0->e0, r1->e1, e2->r0, e0->r1           (5 nodes)
```

Op een VLAK model verandert er niets: 5 nodes, 4 edges, mermaid-hash
`-1527058475` vóór én na. `make engine` blijft 115 groen, `make test` 552
groen — beide nagemeten.

De sleutelzoektocht is SOORT-bewust (een kenmerk en een gelijknamig
attribuut mogen op verschillende typen van één keten staan; het model's
eigen `declarantVan` antwoordt voor beide soorten tegelijk en zou daar de
verkeerde declarant aanwijzen). Die keten-wandeling leest bewust de EIGEN
declaratiekaart per type — dat is juist de vraag: wélk type declareert het
lid. Negen getuigen in `ScopeKetenTest`, naast het ongewijzigde
`ScopeVolledigheidTest`.

### 4. De fixture en de handberekende verwachtingen

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

### 5. De rood-eerst-meting

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

**(b) Het OpenAPI-schema laat vijf van de zes leden weg.** Met een
descriptor zonder kenmerkselectoren (zodat de kern wél start):

```
schema Wrakstuk properties = {"bergingsdiepte": …}          ← één lid
schema Wrakstuk description = "Objecttype 'Wrakstuk'."      ← verder niets
```

De keten van `Wrakstuk` draagt ZES bedienbare leden — zijn eigen
`bergingsdiepte` plus de vijf geërfde (`vondstwaarde`, `bergloon`,
`aangemeld`, `meldplichtig`, `bergingsplichtig`) — en twee af te wijzen
tijdlijnleden. De rauwe lezing gaf er één en noemde geen enkele
afwijzing: **5 van de 6 weg, 0 van de 2 genoemd.** Nagemeten op de kop:
`Wrakstuk properties(6)`, `Strandvondst properties(5)`. (De eerste versie
van dit Result telde 4 van de 5: die telling stamde uit de meting vóór
`bergingsplichtig` aan de fixture werd toegevoegd, en `bergingsplichtig`
ontbrak toen ook in de opsomming van het supertype. De lensronde wees dat
aan; hierboven staan de gemeten getallen van de DEFINITIEVE fixture.)

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

**(d) Twee lezingsparen waren GEMASKEERD — inmiddels LOS gemeten.** Op de
ongewijzigde code waren 3/4 en 8/9 niet apart zichtbaar: het
tijdlijn-KENMERK liep eerst tegen lezing 5 aan (`ONBEKEND_VELD: onbekend
kenmerk 'verjaard'`), en de tijdlijnleden bereikten de
OpenAPI-classificatie niet zolang de iteratie zelf de eigen kaart las.
Zij worden pas rood zódra hun buurman gedicht is.

De lensronde (2026-09-16) heeft ze alsnog geïsoleerd, door telkens ALLEEN
de buurman te dichten, en beide metingen bevestigen de redenering die
hier eerst als afgeleide stond:

- **3/4** — met de ketenafsluiting van 1/2 aanwezig maar de
  tijdlijnclassificatie nog rauw, komt `meldtermijn` als kaal
  `{"type":"integer"}` in het Wrakstuk-schema te staan en `verjaard` als
  kale `boolean`, allebei zonder een woord in de afwijzingsregel van de
  beschrijving. Het contract biedt dan precies de twee eigenschappen aan
  die de dienst bij het verzoek weigert.
- **8/9** — met alleen lezing 5 gedicht en de mapper nog rauw, levert een
  verzoek met het geërfde tijdlijn-kenmerk dezelfde crashklasse als (c):
  een `ClassCastException` bij het schrijven van een `Boolean` in het
  geërfde `List<Segment>`-veld, in plaats van een schending die het veld
  noemt.

Beide paren zijn daarom als PAAR gedicht en hebben hun eigen getuige in
`KetenDienstTest`.

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

### 6. Poortenstand

Exitcodes expliciet gemeten (`; echo $?`, nooit door een pipe), gedraaid
op de EINDtoestand van de boom.

| poort | uitkomst | exit |
|---|---|---|
| `make verify-corpus` | 54 passed, 0 failed, 0 known-defect (of 54) | 0 |
| `service/` `mvn -o -B test` | **167** tests, 0F/0E/0S (basis 145 + 22 nieuw) | 0 |
| `service-quarkus/` `mvn -o -B test` | 33 tests, 0F/0E/0S | 0 |
| `make scenario-test` | golden TOKA 29/29 PASS, marker-exit-3 ok | 0 |
| `mvn -o -B test` (root) | 980 tests, 0F/0E, 3 skipped | 0 |
| `make test` | 553 gevonden / 552 groen / 0 gefaald / 1 aborted, 34 containers | 0 |
| `make engine` | 115 tests, 0F/0E | 0 |
| `make facts` | PASS, 5 klassen gegenereerd + gecompileerd | 0 |
| `make verify-negatief-corpus` | 52 snippets, 41 rejected, 11 as expected (1 `[GAT]`), 4 strict cross-checks | 0 |
| `make uchar-conformance` | **FAIL op de pin-drift**, inhoud groen (zie hieronder) | 2 |

De nieuwe toetsen: 13 in `KetenDienstTest` + 9 in `ScopeKetenTest`.

**`make uchar-conformance` is rood door PIN-DRIFT van buiten deze
branch.** Eerder in deze ronde gaf dezelfde poort op dezelfde inhoud twee
keer exit 0; tussendoor schoof uchar main van de gepinde
`0db8907` naar `ac0439e` — 18 bestanden, +2476 regels, nieuwe goldens en
een nieuwe frontend-toets. De poort meldt dan spec-first: "normative
content moved between pin and HEAD — bump SPEC_REV". Deze branch raakt
`vendor/`, `SPEC_REV` noch `scripts/` (lege diff over `003883e..HEAD`), en
een pin-bump is een maintainersdaad.

De INHOUD van de poort is wel geverifieerd, op de pin, zonder de
uchar-checkout aan te raken (alleen `git show <pin>:<pad>`-lezingen):
**58 gevendorde bestanden byte-identiek, 0 drift, 0 missend, 0 extra**, en
**47 van de 47 emitter-goldens op de pin byte-identiek gereproduceerd**
(0 afwijkend, 0 mislukt). Dezelfde afwijking als (e) van de
overerving-portkaart.

De installatieketen was nodig: `mvn -o install -DskipTests` in de root en
in `engine/` vóór `service/`, en opnieuw na elke wijziging aan
`engine/Verbalisator` en `graaf/ProducentConsument`.

### 7. Acceptatie

- (a) Alle negen ketenbewust, met het oordeel en de reden in code én
  hierboven. Geen enkele blijft raw; het port-precedent geldt hier niet,
  en dat staat als reden in `OpenApiEmitter`. ✔
- (b) Eigen, rechtenvrije fixture; schema, verzoek-antwoord en descriptor
  alle drie gepind; verwachtingen handberekend vóór de draai. ✔
- (c) Negen van de tien poorten groen; de tiende
  (`make uchar-conformance`) is rood door pin-drift van buiten deze
  branch, met de inhoud op de pin nagemeten groen. ✖/✔ — zie §6.
- (d) `TokaBron`, `examples/toka/toka.dienst.json` en `docker/Dockerfile`
  onaangeraakt — de fixture staat ernaast, met een eigen descriptor. ✔
- (e) Geen enkele bestaande test gewijzigd. `vendor/`, `SPEC_REV` en
  `scripts/` hebben een lege diff. ✔

### 8. Afwijkingen en twijfels

1. **Ik ben buiten de negen gegaan**, in `service/` (`objectMatch`,
   `ontbrekendeInvoer`, `AntwoordBouwer`), één keer in `engine/`
   (`Verbalisator`) en na de lensronde één keer in de gedeelde
   IR-analyse (`graaf/ProducentConsument`). De kaart noemt alleen
   facetlezingen; de kop van de kaart vraagt een volledig bediende
   subtype-instantie, en zonder die stappen is zij dat niet. De twee
   uitstappen zitten in EIGEN commits (`8ce9ec9` en de scope-commit),
   zodat de beheerder ze los kan afwijzen.
2. **Het subtype-schema is VLAK, geen OpenAPI `allOf`.** Overwogen en
   verworpen: het document belooft "precies de verzoekvorm die de mapper
   accepteert", en de mapper kent geen compositie. Een `allOf` zou elke
   consument extra werk geven voor dezelfde informatie. De keuze staat
   met haar reden in de code.
3. **Richting (b) van de scope-poort is een KEUZE, geen automatisme.**
   Een producent op het subtype dekt een consument op het supertype maar
   gedeeltelijk. Ik laat de poort melden (§3), want zij vraagt of er een
   producent BESTAAT die buiten de scope viel. Wie dat te streng vindt,
   verandert één regel in `elementVan` — maar dan mag de motivatie niet
   zijn "het is stiller".
4. **De graaf is meegenezen zonder dat de kaart erom vroeg.** Hij deelt
   de sleutel met de poort (README-invariant), dus de fix raakte hem
   automatisch. Op vlakke modellen nagemeten onveranderd (5 nodes, 4
   edges, gelijke mermaid-hash; `make engine` 115, `make test` 552). Op
   ketenmodellen tekent hij nu één element-node in plaats van twee — een
   zichtbare uitvoerwijziging waarvoor geen golden bestaat.
5. **`make uchar-conformance` is rood door externe pin-drift** (§6). De
   inhoud is op de pin nagemeten groen; een pin-bump is een
   maintainersdaad en deze branch doet hem niet.
6. **`make scenario-test` geeft 29/29**, conform `CLAUDE.md` sinds de
   poorten-herstelkaart. Het ene abort in `make test` en de drie skips in
   de root-suite zijn de pre-existente assumptie-bewaakte stiltes die de
   poorten-herstelkaart al noteerde; ongewijzigd.
7. **De poorten zijn op de eindtoestand gedraaid**, niet per commit. Per
   commit is alleen de service-suite groen nagemeten; de tussentoestanden
   zijn no-ops op het vlakke TOKA-model.

### 9. Commits

- `fd3115f` — de negen facetlezingen ketenbewust (+ `kenmerkLeden`)
- `8ce9ec9` — het ⊑ aan de dienstgrens (invoer, uitvoer, uitleg)
- `4a906f9` — de eigen fixture en de dertien getuigen
- `0e55f47` — het Result van de eerste ronde
- de scope-commit van deze ronde (het element sleutelt op zijn declarant)
- deze bijwerking van het Result
