# explanation-ground-source: de grondslag zegt van welke regel of variant hij is

*Voorstel vanuit de werkbank (2026-10-22), door de eigenaar doorgegeven aan de toolchain-sessie; tekst van de werkbank,
ongewijzigd op deze kop na. Vervolg op `explanation-parts` (toolchain `6b5d3750`). De hoofdsessie kiest de eerste weg
(de identiteit van de beslisser in `GROUND`), niet het alternatief via uchar.*

## Aanleiding

De Uitleg-tab van de werkbank rendert elke uitlegzin uit `explanationParts` en zet de groep `GROUND` klein onder de
zin. De eigenaar wil per zin kunnen zien waar die grondslag vandaan komt: uit de `// Bron:`-regels boven welke regel,
in welk bestand en op welke regel. Dat helpt bij het debuggen van een regelset.

De werkbank kan dat nu niet bepalen zonder te raden:

- `GROUND` draagt geen verwijzing naar de beslisser waarvan hij de annotaties is (`Verbaliser.groundOf(Variant)`: een
  regel haar `statuteAnnotations`, een default die van zijn blok, de opgavemarkering geen).
- Een zin over een kenmerktoekenning heeft niet altijd een `RULE`-stuk. Een defaults-zin noemt meer regels: de
  beslissende variant en de varianten die niet golden of verdrongen werden. Uit de stukken is dus niet af te lezen bij
  welke regel de grondslag hoort.
- De grondslag uit de tekst terugzoeken in de bronnen zou de tekst van de toolchain knippen of interpreteren; dat doet
  de werkbank niet (besluit van de eigenaar, 2026-10-21).
- In de IR staan per regel de naam en de `statuteAnnotations`, maar geen bestand of regelnummer. De werkbank heeft wel
  een koppeling van een reeks annotaties naar de plek in de bronnen: `annotatieblokken` in
  `werkbank/tools/lint-benamingen.py`, met dezelfde lezing van een commentaarblok als uchars
  `RuleChecker.statuteAnnotations`. Met de identiteit van de beslisser vindt zij via de IR diens annotaties en daarmee
  het blok, bestand en regelnummer.

## Doel

- `GROUND` krijgt meta die de beslisser aanwijst waarvan hij de annotaties is, in de termen van de IR:
  - een regel: `rule` = de naam van de regel zoals in `rules[].name` van de IR;
  - een default: `group` = de naam van de defaults-groep, `position` = zijn plaats in de groep (1-gebaseerd, zoals de
    uitleg "de variant op plaats n van 'X'" noemt), en `variant` = zijn naam als hij er een heeft;
  - de toolchain kiest de precieze sleutels en zegt waarom.
- De platte zin in `explanation` en de tekst van de stukken veranderen niet (bytegelijk). Zonder `explanationParts=true`
  verandert het antwoord niet.
- OpenAPI (`OpenApiEmitter`, schema `ExplanationPart`) en `ContractNamesInMessagesTest` bij; `docs/ENGINE.md`,
  "The parts of an explanation".

Alternatief, alleen als de toolchain het beter vindt: uchar levert per regel en per default de bronplek van zijn
`// Bron:`-blok (bestand, eerste en laatste regel) in de IR, en `GROUND` geeft die door. Dat raakt IR-SPEC en is groter;
met de identiteit van de beslisser kan de werkbank de plek zelf vinden.

## Acceptatie

- (a) Op de vijf service-verzoeken van herleving-WW v2 en s01: elke `GROUND` draagt de identiteit van zijn beslisser,
  en de `statuteAnnotations` die de IR die beslisser geeft, ontleden met `engine/Ground` tot precies de kinderen van die
  `GROUND`.
- (b) Een eigen klein model met een regel met twee `// Bron:`-regels, een defaults-groep met een naamloze en een benoemde
  variant, en de opgavemarkering (geen `GROUND`).
- (c) `explanation` en de tekst van de stukken bytegelijk aan nu; `make gates` groen.

## Daarna, in de werkbank (geen deel van deze kaart)

Een klik op het grondslagblok onder een zin toont "uit de `// Bron:` boven 'herleving art. 21 lid 1',
`hww-12-….rgs` r. 27" en linkt naar die plek op de definitiepagina; de server zoekt de plek op met de koppeling van de
lint.

## Result (2026-10-22)

Tak `card/explanation-ground-source` in de worktree `wt-tc/explanation-ground-source`, vanaf toolchain main `6b86e79f`,
pin uchar `680e5263` (ongewijzigd). Gebouwd door een Opus-bouwer; `make gates` en de landing doet de hoofdsessie.

- `8c66526a` de kaart naar `doing/`.
- `052013f1` de grondslag noemt zijn beslisser: meta op `GROUND`, OpenAPI, `docs/ENGINE.md`, de tests.

### De sleutels

Een `GROUND` draagt de beslisser waarvan hij de `statuteAnnotations` is, in de termen van het IR, als meta van de groep
(naast `kind` en `parts`). Eén van twee vormen:

| beslisser | sleutels |
|---|---|
| een regel: de regel van een kenmerktoekenning, of een gewone regel die in een defaults-zin beslist als basis van de stapel van een subtype (RUNTIME-SEMANTICS ch. 7.10) | `rule`: de naam van haar vorm, `rules.rules[].name` |
| een default | `group`: de naam van zijn groep, `rules.prioritizedDefaults[].name`; `pointer`: de default in die groep als JSON Pointer (`/defaults/1`, genest `/defaults/0/exceptions/members/0/defaults/2`); `position`: zijn plaats in zijn keten, 1-gebaseerd; `variant`: zijn naam (`Default.name`), alleen waar hij er een heeft |

Waarom zo:

1. **`rule` is de naam van de vorm**, niet die van de versie. De annotaties staan op de vorm, één lijst voor al haar
   versies (IR-SPEC ch. 3.1); het model noemt een versie "naam #i" (`RegelSpraakModel.formName` haalt dat eraf). In de
   eigen test heeft 'gevestigd' twee versies; beide zinnen dragen `{rule=gevestigd}`.
2. **`group` is de naam**, geen index: een defaults-groep is een benoemde vorm (WF-107, uniek onder WF-020). De dienst
   bouwt het document van de uitleg uit het model mét scope (`DecisionCore`: `UcharBuild.document(scoped)`); een scope
   kan de plaats van een groep in het document verschuiven, de naam niet, en de werkbank zoekt in haar eigen, volledige
   IR. `group` is null alleen waar een document de groep geen naam geeft (WF-107 weigert dat, uchars lader leest het);
   de zin noemt zo'n groep "de groep op S.t".
3. **`pointer` maakt de default eenduidig.** De naam is geen identiteit: een default staat in geen naamruimte, en een
   uitzondering kan haar naam missen (`Default.name` heeft geen semantiek, IR-SPEC ch. 6.1; gate 13 haalt hem weg). De
   plaats alleen is het ook niet: een geneste default heeft een plaats in zijn eigen keten in een uitzonderingsgroep
   van een andere default. Op v2 komt dat voor: de beslisser van "R1 heeft als herlevingstermijn 28-02-2027 volgens
   'herkrijgingstermijn na zelfstandig werk art. 8 lid 3'" staat in de groep
   `herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag` op `/defaults/0/exceptions/members/0/defaults/2`
   (plaats 3); die groep heeft één default aan de wortel, dus `group` en `position` alleen wezen niets aan. De pointer
   is de vorm waarin het IR een plek adresseert (RFC 6901, zoals de pointers van de motor en de meldingen van de
   verifier), zonder eigen notatie, en het is een tekst, want meta van een stuk draagt alleen teksten, booleans,
   gehele getallen en null (`Part`). Het is de pointer van de motor zonder `/rules/prioritizedDefaults/<i>`, om de reden
   van punt 2. Alle 104 grondslagen op v2 en s01 vinden hun beslisser zo in het volledige IR van `bin/regelspraak ir`.
4. **`position`** is de n van "de variant op plaats n" (bij een geneste: "plaats n van uitzonderingsgroep m"), de plaats
   in de keten waarin de default staat, zoals de kaart vroeg. Hij is de laatste index van `pointer` plus 1; de testhelper
   en de meting toetsen dat. Hij staat erbij zodat een lezer de zin en het stuk naast elkaar kan leggen zonder de pointer
   te ontleden.
5. **`variant`** ontbreekt waar de default geen naam heeft (geen null), zoals `paragraph` en `part` van `STATUTE`
   ontbreken waar het anker ze niet noemt: de kaart zegt "als hij er een heeft".
6. **Volgorde**: `group`, `pointer`, `position`, `variant`: eerst wat de beslisser vindt, dan wat de zin toont.

De opgavemarkering: haar defaults en haar regel (`afwijking opgave X`) dragen geen annotaties, dus haar zinnen hebben
geen `GROUND` (ongewijzigd). Een `CONDITION`, `NAME` enzovoort draagt geen van de vijf sleutels.

### Voorbeelden (v2, uit de meting)

Een regel (`detentie`, zin 0, "R1 heeft geen uitkeringsduur verstreken, want … . Grondslag: BWBR0004045&artikel=20&lid=1&o=b
· …"):

```json
{"kind": "GROUND", "rule": "uitkeringsduur verstreken bij de eindiging art. 20 lid 1 onder b", "parts": [
  {"kind": "TEXT", "text": ". Grondslag: "},
  {"kind": "STATUTE", "text": "BWBR0004045&artikel=20&lid=1&o=b", "bwb": "BWBR0004045", "article": "20", "paragraph": "1", "part": "b"},
  "…"]}
```

Een variant (`detentie`, zin 7, "R1 heeft als berekende beëindigingsdatum 10-03-2026 volgens de regel 'beëindigingsdatum
art. 20 lid 1' (onvoorwaardelijk); …"):

```json
{"kind": "GROUND", "group": "beëindigingsdatum art. 20 lid 1", "pointer": "/defaults/1", "position": 2,
 "variant": "beëindigingsdatum art. 20 lid 1", "parts": [{"kind": "TEXT", "text": ". Grondslag: "}, "…"]}
```

Een geneste variant (`herkrijging`, zin 9):

```json
{"kind": "GROUND", "group": "herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag",
 "pointer": "/defaults/0/exceptions/members/0/defaults/2", "position": 3,
 "variant": "herkrijgingstermijn na zelfstandig werk art. 8 lid 3", "parts": ["…"]}
```

Een variant zonder naam (eigen test, document zonder de naam van 'jeugdpremie'; de zin zegt "volgens de variant op
plaats 1 van 'premie'"): `{group=premie, pointer=/defaults/0, position=1}`.

### Wat er veranderde

- **`Verbaliser`**: `ground(b, annotations, decider)` zet de beslisser als meta op de groep `GROUND`; de tekst van de
  groep en haar kinderen zijn dezelfde. `ruleDecider(ResolvedRule)` (`rule`) voor de zin van een kenmerktoekenning en
  voor een regel die in een defaults-zin beslist; `DefaultsExplanation.deciderOf(Variant)` voor een default, met de
  pointer uit de pointer van de variant (`IN_GROUP`; faalt luid op een pointer die niet in een groep wijst, wat de
  varianten per constructie niet hebben). `groundOf` ongewijzigd.
- **`Part.Kind.GROUND`**, javadoc: de meta.
- **`OpenApiEmitter`**: de beschrijving van `ExplanationPart` noemt de beslisser en zijn sleutels; eigenschappen `rule`,
  `pointer`, `variant` (string), `group` (string, nullable), `position` (integer). De vier OpenAPI-goldens overgeschreven
  uit `target/openapi/`; het verschil is alleen de beschrijving en de vijf eigenschappen.
- **`docs/ENGINE.md`**, "The parts of an explanation": de rij `GROUND` en een alinea "The decider of a ground".
- **`ContractNamesInMessagesTest`** ongewijzigd en groen: hij leest ook de nieuwe beschrijving; de nieuwe sleutels hebben
  geen oude Nederlandse naam die hij zou moeten weigeren.

### Rood eerst (b)

`ExplanationGroundSourceServiceTest` (4), dienst uit bronnen, uit het IR-document, en uit een document waarin
'jeugdpremie' en 'kinderpremie' hun naam missen. Het eigen model: 'gevestigd', een regel van twee versies met twee
`// Bron:`-regels (de eerste versie valt buiten de rekendatum); de groep 'premie' met de basis 'premie', de uitzondering
'jeugdpremie' (onder 18) en de daarin geneste 'kinderpremie' (onder 12), elk met een eigen annotatie; 'strandpremie',
een gewone regel op het subtype Strandjutter die de basis van de groep voor een Strandjutter vervangt (een regel beslist
in een defaults-zin); en de meldingsdatum met `(mag opgegeven worden)`, waarvan de opgavemarkering groep en regel maakt.
De test pint de zinnen (20; ongewijzigd t.o.v. de kaartbasis), per zin de meta van de grondslag (12 met, 8 zonder: de
opgavezinnen hebben er geen, ook waar de rekenregel 'melding' een annotatie draagt), de hele grondslag van de geneste
default en van 'strandpremie', en via de nieuwe helper `ExplanationPartsCheck.assertGroundsNameTheirDeciders` dat elke
`GROUND` zijn beslisser in het document vindt (namen uniek, pointer wijst een default aan, `position` = laatste index + 1,
`variant` = de naam) en dat diens annotaties, met "; " ertussen, de tekst van de grondslag zijn.

Op de kaartbasis met alleen de tests: rood op alle vier, met de zinnen gelijk en de meta leeg
(`werk/toolchain-2026-10-22/ground-source/logs/red-final.log`). Groen na de wijziging (`logs/green-2.log`).

Aangepast, met reden (deze kaart verandert wat ze pinden; geen toets verzwakt):
- `ExplanationPartsServiceTest` en `ExplanationDefaultStatuteAnnotationsServiceTest`: de gepinde weergave van
  `GROUND` draagt nu de meta (`GROUND{rule=ervaren}[…]`, `GROUND{group=premie, pointer=/defaults/0, position=1,
  variant=jeugdpremie}[…]`, `GROUND{group=penning, …}[…]`); beide tests roepen ook de nieuwe helper aan (5 en 2
  grondslagen).
- De vier OpenAPI-goldens (hierboven).

### Meting (a) op v2 en s01

Meetmap `werk/toolchain-2026-10-22/ground-source/meting/`: de vijf service-verzoeken van herleving-WW v2 (momentopname
`werk/defaults-decider/v2/`, regels `a7a5bdf`) en s01 (`requests/s01.request.json`, het verzoek uit de eval van de
werkbank, `werknotities/20261022-eval-uitleg-varianten/antwoorden/s01.verzoek.json`). `measure.sh` start de dienst uit een
gegeven jar en vraagt elk verzoek zonder vlag, met `?explanation=true` en met `?explanationParts=true`.

- **Vóór**: jar gebouwd op de kaartbasis (`before-jar/`, code van `6b86e79f`), uitvoer `before/`.
- **Na**: jar van de wijziging (`after-jar/`, gebouwd vóór de commit uit dezelfde hoofdcode als `052013f1`; daarna
  veranderde alleen javadoc van een test), uitvoer `after/`.
- **Bytegelijk** (`compare.py`, `compare.log`): per verzoek de body zonder vlag en die met `explanation=true` bytegelijk;
  met `explanationParts=true` is `explanation` bytegelijk en het antwoord gelijk aan vóór zodra de vijf sleutels van
  `GROUND` eraf zijn; elke zin gelijk aan haar stukken. 209 zinnen, 104 met `GROUND`. PASS.
- **Identiteit** (`harness/…/GroundSourceMeasure.java`, `ground-source-a.log`): per `GROUND` de beslisser alleen uit
  zijn sleutels gezocht in het volledige IR van v2 (`v2-ir.json`, `bin/regelspraak ir` over dezelfde bronnen), diens
  `statuteAnnotations` geschreven zoals de Verbalisator een grondslag schrijft (". Grondslag: " en `Ground.write`), en
  als JSON vergeleken met de kinderen van die `GROUND`. 104 van 104 gelijk: 64 regels, 39 defaults aan de wortel, 1
  geneste default; 24 verschillende beslissers. PASS.

### Suites

`mvn -B -o verify` over de hele reactor op de code van `052013f1` (vóór de commit, dezelfde bomen;
`logs/verify-1.log`): BUILD SUCCESS, 1649 (Drools) / 163 (toolchain, ongewijzigd) / 364 (dienst, was 360: +4) / 53
(Quarkus), skip-pin PASS op de drie poorten, 8m36s.

### Wat open bleef

- **De grondslag van de berekening in de opgavegroep.** De opgavemarkering maakt de groep `opgave boven berekening X`
  met twee defaults zonder annotaties; beslist de berekende waarde, dan heeft de zin geen grondslag, ook waar de regel
  die X berekent `// Bron:`-regels draagt (eigen test: 'melding'). Dat volgt uit uchar (IR-SPEC ch. 6.1: "the two
  defaults the opgave marker generates carry none"); geen fout in de toolchain. Wil de werkbank daar een grondslag, dan
  is dat een vraag aan uchar.
- **README** ("Contract change") niet bijgewerkt: de bouwer van `pin-and-tokens` werkt parallel in de README. Eén regel
  ("`GROUND` draagt zijn beslisser: `rule`, of `group`/`pointer`/`position`/`variant`") kan bij de landing.
- **`group: null`** (een document dat WF-107 breekt) is niet getoetst.
- **De replay-leg** (`check-service-replay.sh`) toetst de identiteit niet over het corpus; dat kan (de golden heeft haar
  `ir.json`), maar viel buiten de kleinste wijziging.

### Landing (hoofdsessie)

`make gates` op `052013f1` met `TAG/CLUSTER=explanation-ground-source`: GROEN, 7 stappen in 12m49s (reactor
1649/163/364/53, replay 234 goldens, 622 cases). De hoofdsessie voegde in de landingscommit een regel toe aan
`README.md` (de lijst met contractwijzigingen, na "Explanation parts").
