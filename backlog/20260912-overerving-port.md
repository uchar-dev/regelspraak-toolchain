# Port: objecttype-overerving door de eigen vertaallaag

Mandaat: de maintainer wil met RegelSpraak + Drools + overerving in de
toolchain-engine experimenteren. De gevendorde frontend
(`vendor/uchar-frontend/`, `SPEC_REV` 60d021c) parseert en resolvet
`Objecttype het Wrakrecht is een Strandrecht` al volledig — supertype,
`keten()` en de ketenbewuste accessors zitten in het model. De EIGEN
vertaallaag onder `src/main/java/io/github/yhavinga/regelspraak/` stamt
van vóór de overerving en leest overal het gedeclareerde type.

Bron van de port: uchar's `backends/drools/` — ooit als kopie van deze
laag begonnen, daarna doorontwikkeld met native `extends`, ketenbewuste
facetlezingen en de ch.-7 defaults-lowering. Alleen die drie
doorontwikkelingen komen hier terug; de overige uchar-divergentie
(let-lowering, effectieve-eenheid, wire-loader, naamnormalisatie) blijft
waar zij is.

## Acceptatie

- (a) `--facts` op de wrakrecht-bron geeft `class Wrakrecht extends
  Strandrecht` met de eigen leden alleen; de keten telt zes leden.
- (b) De zes cellen van de subtype-semantiek reproduceren door de
  exec-model-tak, met de waarden die in uchar drievoudig gepind zijn
  (ref ≡ clingo ≡ Drools):

  | geval | instantie | betwist | bergingsdiepte | uitkering |
  |-------|-----------|---------|----------------|-----------|
  | 1 | S1 Strandrecht | nee | —  | 10,00 |
  | 1 | W1 Wrakrecht   | nee | 5  | 20,00 |
  | 2 | S1 | ja  | —  | 99,00 |
  | 2 | W1 | ja  | 5  | 99,00 |
  | 3 | W1 | nee | 25 | 55,00 |
  | 4 | W1 | ja  | 25 | 55,00 |

  `registratiekosten` is 5,00 op elke instantie (de kale regel van het
  supertype vuurt voor de subtype-instantie); `uitkeringstotaal` /
  `registertal` zijn 30,00/2, 198,00/2, 55,00/1, 55,00/1.
- (c) `make test` blijft groen (553-basis).
- (d) `mvn -B test` blijft groen.
- (e) `make uchar-conformance` blijft groen — `vendor/` wordt NIET
  aangeraakt.
- (f) geen bestaande test wijzigt zonder gemelde reden.

## Fasering

1. Divergentiemeting (in het Result).
2. Beans: `FactModelGenerator` → `extends` + eigen-leden-alleen;
   `ContractValidator` beweegt mee.
3. Ketenbewuste lezingen: elke facetlezing in beide transpilers door de
   `*Leden()`-accessors.
4. Defaults: de `prioritizedDefaults`-lowering in de exec-model-tak,
   inclusief de effectieve-stapel-samenstelling van ch. 7.10. De
   DRL-tekst-tak weigert luid aan de deur (zoals in uchar).
5. Acceptatie.

---

## Result (2026-09-16)

### 1. Divergentiemeting

De twee vertaallagen delen 17 bestandsnamen. Elf daarvan waren al
byte-identiek (`Stratificatie`, `Accessors`, `Breuk`, `CreationMarker`,
`RuleFired`, `graaf/*`, `kern/Dagsoorten|Dekking|Operatoren|PeriodeDekking`)
en zijn dat gebleven. De divergentie zat in zes bestanden; regels die
alleen aan één kant bestaan, vóór → na de port:

| bestand | uchar-only | toolchain-only |
|---------|-----------|----------------|
| `ContractValidator` | 3 → **0** | 0 → 0 |
| `FactModelGenerator` | 177 → **166** | 9 → 24 |
| `DrlTranspiler` | 357 → **332** | 755 → 768 |
| `model/ExecModelTranspiler` | 2474 → **1914** | 861 → 990 |
| `kern/Namen` | 52 → **37** | 2 → 10 |
| `runtime/RegelSpraakRuntime` | 30 → 30 | 48 → 48 |

**Wat uchar had en de toolchain miste** — vier categorieën:

1. **Overerving in de beans** (1 plek): geen `extends`, geen
   eigen-leden-aftrek. Geport.
2. **Ketenbewuste facetlezingen** (106 plekken): 63× `attribuutGranulariteit`,
   17× `kenmerkGranulariteit`, 17× `attribuutTijdlijnAantal`, 7×
   `kenmerkTijdlijnAantal` als EIGEN-kaartlezing, plus 2 ledenlookups
   (`attributen.containsKey`). Geport.
3. **De ch.-7 defaults-lowering** (0 plekken aanwezig, ~660 regels
   geport): de toolchain had er niets van — geen stapel, geen
   reduce-regel, geen absorptie.
4. **Buiten deze kaart, bewust niet geport**: de effectieve-eenheid-
   afleiding (`kern/EffectieveEenheid`, 478 r., + `Eenheidsconflict`,
   112 r. — de annotatie-onafhankelijkheid van uchar gate 13), de
   wire-loader (`loader/IrLoader`, 701 r.) met `cases/` en
   `engine/BeanCompiler`, de multipliciteitslezing `kern/Tijdlijnen`
   (47 r.), en de naamnormalisatie `Namen.bevatNaam`/`gedeclareerdeNaam`.
   Die horen bij andere uchar-kaarten en raken overerving niet.

**Wat de toolchain heeft en uchar mist** (blijft waar het is): de
scenariotoetsen-machinerie (`resolver/ScenarioResolver`,
`ir/ResolvedScenario`), de CLI (`cli/RegelSpraakCli`), de
uitleg-/graaf-paden, en in `RegelSpraakRuntime` de ch.-5.11-lezing van de
leegwaarde als gewone operand (`rsSomLeeg`, `rsLeegwaarde`,
`rsTijdSomAlle` met een waarde in plaats van een vlag) — daar loopt de
toolchain juist vóór. Omgekeerd mist de toolchain `rsOoitBevat` (§8.1.6
gemengde uniesleutel). Geen van beide raakt overerving.

### 2. Beans — geport

`FactModelGenerator.leden` splitst in `alleLeden` (de oude afleiding) en
`leden` (die aftrekt wat de ouderketen al declareert, op
eigenschapsnaam); `genereer` emitteert `extends`. `ContractValidator`
beweegt mee zonder codewijziging — `getMethods()` ziet de geërfde paren
al — en houdt zijn eigen-kaartlezing met de reden erbij.

Gemeten op de wrakrecht-bron: `class Wrakrecht extends Strandrecht`, één
eigen veld (`bergingsdiepte`), zes ketengetters. Baseline: een vlakke
klasse met twee velden.

### 3. Ketenlezingen — geport

Alle 104 facetlezingen en 2 ledenlookups in beide transpilers gaan door
`granulariteitLeden()`, `kenmerkGranulariteitLeden()`,
`tijdlijnAantalLeden()`, `kenmerkTijdlijnAantalLeden()` en
`attribuutLeden()`. Bewust RAUW gebleven, met reden in de code: de twee
iteraties in `FactModelGenerator.alleLeden` en de temporeel-disjunctie in
`ContractValidator` — die lopen juist per declarerend type.

### 4. Defaults — geport, met één benoemde weigering

De exec-model-tak draagt: `defaultsGroepen`/`effectieveStapels` (de
kind-wint-blokorde per CONCREET type), `regelLid` (de kale regel als
basis van zijn laag), `geabsorbeerdOp` (de absorptiepoort die zo'n regel
op precies die klassen uit het regelcircuit haalt), `voegSamen`,
`defaultsGroep` (de reduce-regel), `DefaultsRun` (de doorval-wandeling,
de geneste uitzonderingsgroepen, de §9.5-verzoening) en
`defaultsSalience`. `Namen.norm` kwam mee als gedeelde naamcontract.

**Luide weigeringen** (alle via `skipList`/`DefaultsOnvertaalbaar`, dus
zichtbaar in het dekkingsmanifest): een eigen groep boven een geërfde
ongeordende verzameling; een eigen ongeordende verzameling boven een
geërfde stapel; een basis met geldigheidsvenster; een basis zonder
waarde; twee schrijvers op één doel; een temporeel, gedimensioneerd of
naamdelend doel; een kanaal-deponerende consequentie; een niet-reduceer-
bare justificatie of consequentie.

**De DRL-tekst-tak weigert, met documentatie.** Zelfde keuze als in
uchar. De reden staat in de javadoc van `DrlTranspiler.defaultsMarker`:
de prioriteitsketen is een fold per subject met leeg-doorval, en deze tak
bestaat als differentieel ORAKEL van de exec-model-tak — een tweede
implementatie uit dezelfde lezing bewijst niets. De weigering staat waar
elke andere niet-gelaagde vorm daar staat (een `/* TODO */`-marker, door
`meet()` geclassificeerd als MARKER met reden, plus een waarschuwing).
Dit is strikt beter dan de oude toestand: daar verdween de hele familie
— basisregel incluis — zonder marker of waarschuwing uit BEIDE takken.

### 5. Acceptatie

- (a) `--facts` op de wrakrecht-bron: `extends` + zes ketenleden. ✔
- (b) De zes cellen reproduceren door de exec-model-tak, met de
  drievoudig gepinde waarden letterlijk overgenomen. ✔
- (c) `make test` 553 gevonden / 552 groen / 0 gefaald (1 aborted;
  identiek aan de basis). ✔
- (d) `mvn -o -B test` 976 tests, 3 gefaald — alle drie PRE-EXISTING,
  geverifieerd op basiscommit `6b10226` met dezelfde drie namen en
  dezelfde resolverfout (WF-096 setlezing, uit de gevendorde slice). ✔
- (e) `make uchar-conformance` FAALT, en niet door deze branch: uchar
  main is doorgeschoven van pin `60d021c` naar `55ed96f` en de gate eist
  dat de normatieve inhoud niet bewoog. Identieke fout op basiscommit
  `6b10226`. Deze branch raakt `vendor/`, `SPEC_REV` noch `scripts/`
  (lege diff). De INHOUD van de gate is wél geverifieerd tegen de pin:
  57 gevendorde bestanden byte-identiek (0 drift, 0 missend, 0 extra) en
  45/45 emitter-goldens byte-identiek gereproduceerd. Een pin-bump is een
  maintainersdaad. ✔ (inhoudelijk)
- (f) Geen bestaande test gewijzigd. ✔

Overige poorten, alle identiek aan de basis: `make facts` PASS,
`make scenario-test` 29/29 + marker-exit-3, `make engine` 115 groen,
`service` 84/1F/17E (pre-existing, gemeten op `6b10226`),
`service-quarkus` BUILD FAILURE (volgt uit `service`).

### Eerlijke afwijkingen

1. **De wrakrecht-bron staat INLINE in de test, niet onder
   `examples/`.** `CorpusVuurTest` partitioneert `examples/**.rgs` met
   EXACTE bucketgetallen (59/55/79/9). Een subtype-voorbeeld daar zou
   `VERWACHT_HONEST_SKIP` van 79 naar 80 duwen — de DRL-tak zet nu een
   marker op de defaults-groepen — en dat is een wijziging aan een
   bestaande test. Wie het bestand toch wil: neerzetten en die ene
   constante ophogen.
2. **Negen rauwe facetlezingen blijven buiten de vertaallaag staan**, in
   `service/`: `OpenApiEmitter` 157/164 (de eigen-kaart-iteraties die de
   geërfde leden uit het OpenAPI-schema WEGLATEN), `OpenApiEmitter`
   225/229, `DescriptorLoader` 166, `VerzoekMapper` 430/431/438/439. De
   kaart staat `service/` alleen toe als de compilatie het afdwingt, en
   dat doet zij niet. Dit is de logische volgende kaart: een
   subtype-instantie is via de HTTP-dienst nu nog niet volledig te
   bedienen.
3. **`make scenario-test` geeft 29/29 waar `CLAUDE.md` 30/30 noemt** —
   pre-existing, identiek op de basiscommit.
