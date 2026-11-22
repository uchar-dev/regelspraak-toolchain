# open-values-in-service: een regelset die uit leeg niets afleidt, door de dienst

*Gevraagd door de eigenaar op 2026-11-22, samen met de pin: uchar draagt `Afleiden uit leeg: nee` (`inferFromEmpty: false`,
RUNTIME-SEMANTICS ch. 1.5) in ref, clingo en Drools.*

## Aanleiding

In een document met `Afleiden uit leeg: nee` is een lid gevuld, vaststaand leeg of open. De dienst kent er twee: een waarde en
`null`, en van een kenmerk `true` en `false`. Na de pin zou een strikte regelset door de dienst rekenen, maar haar antwoord zou een open
uitkomst als `null` of `false` geven: de afnemer leest "er is geen recht" waar staat "dat weten we nog niet". En een aanvrager kan nu niet
zeggen dat een kenmerk vaststaand niet geldt: `false` en weglaten zijn hetzelfde.

Een dienst in een omgeving met onvolledige invoer (een gesprek dat nog doorvraagt) heeft juist dat verschil nodig: vraag door waar iets open is,
en alleen daar.

## Goal

1. **De pin.** De submodule naar uchars main van na de kaart open-values-in-drools; wat de API van `backends/drools` wijzigde, volgt
   (lijst: het Result van die kaart, "voor de pin"). Een regelset zonder de regel `Afleiden uit leeg: nee` houdt haar antwoord, uitleg,
   `/info` en OpenAPI byte voor byte, op wat uchar zelf aan een gewoon document veranderde (met bewijs).
2. **Het verzoek** van een strikte regelset. Een kenmerk is `true` of `{"state": "holds"}` waar het geldt, `{"state": "holdsNot"}` waar
   vaststaat dat het niet geldt (`characteristicsAbsent` van uchars case), en weggelaten of `null` waar het niet gegeven is: open, tenzij de
   declaratie `(volledig gegeven)` zegt. Een kale `false` is een 400 `WRONG_TYPE` met een zin die de twee vormen noemt, want een formulier of een
   DTO vult een oningevulde boolean met `false` en de regelset zou daar stil uit afleiden. Een attribuut dat ontbreekt of `null` is, is niet
   gegeven; een vaststaand leeg invoerattribuut bestaat alleen als `(volledig gegeven)`. In een gewone regelset verandert niets.
3. **Het antwoord** van een strikte regelset geeft elk lid van `output` met zijn stand, zodat een afnemer de vier begrippen niet door elkaar kan
   halen en code die de stand niet leest altijd en niet soms misgaat:

   | lid | in het antwoord | betekent |
   |---|---|---|
   | attribuut | `{"state": "filled", "value": <waarde>}` | gevuld |
   | attribuut met markers | `{"state": "marker", "marker": "onbekend"}` | een afwezigheidsmarker, een gevulde waarde |
   | attribuut | `{"state": "empty"}` | vaststaand leeg: het staat vast dat er geen waarde is |
   | attribuut, kenmerk | `{"state": "open"}` | open: staat niet vast |
   | kenmerk | `{"state": "holds"}`, `{"state": "holdsNot"}` | het geldt; het staat vast dat het niet geldt |

   Een gewone regelset antwoordt zoals nu. `/info` zegt bij een strikte regelset `"inferFromEmpty": false` en welke invoerleden volledig gegeven zijn.
   Het OpenAPI-document van een strikte regelset beschrijft de stand als opsomming; dat van een gewone regelset verandert niet. `fixpoint` geeft van
   elk object ook de open leden, met de invoerwaarde die er niet staat waar de invoer er een gaf.
4. **Wat een uitkomst open houdt.** `?openInputs=true` (op een ingang die uitleg kan geven, los van `?explanation`) voegt `openInputs` toe: een
   platte lijst van invoerleden die een open uitkomst van `output` nu open houden, elk eenmaal, `{"kind": "attribute" | "characteristic",
   "instance", "name", "forOutputs": [{"instance", "name", "kind"}, …]}`, in de volgorde van de instanties van het verzoek en dan van de declaratie.
   Zij volgt uchars open lezingen (`NodeReading.open`) van de uitkomst naar de leden die geen regel schrijft. Het is wat nu gevraagd kan worden en niet
   de volledige vragenlijst: is dat gegeven, dan kan een volgende lezing open blijken. Op een gewone regelset een 400 `UNSUPPORTED`.
5. **De uitleg.** Een open uitkomst krijgt een zin die zegt dat zij niet vaststaat en door welke open lezing; een regel die niet beslist, een
   default die open is, een verdrongen default daaronder, een onbepaalde voorwaarde en een sluiting ("geldt, want zijn taxatie is niet
   gegeven" als toets op leeg) staan er met hun eigen woorden. `explanationParts` draagt het oordeel: `undetermined: true` en `closure: true` op
   `CONDITION`, en `open` met object en lid op de `READING`. Geen zin noemt iets open "leeg" of "onbekend". Een consistentieregel die niet beslist, krijgt een zin; `inconsistencies` noemt
   alleen wat vaststaat, en een lege lijst zegt niet dat elke regel houdt.
6. **De playground** toont hetzelfde; een scenario kan een open uitkomst niet verwachten (uchars scenariolaag heeft er geen zin voor), en zegt dat als
   zij er een krijgt waar een waarde verwacht werd.

## Besluiten (voorlopig, hoofdsessie; het contract is door een tweede lezer beoordeeld)

1. De stand als eigen vorm per lid in een strikte regelset, in plaats van `{"open": true}` op de plaats van de waarde: een object op de plaats van een
   boolean is in veel talen waar, dus een open kenmerk zou als geldend gelezen worden, soms.
2. Een kale `false` in een strikt verzoek wordt geweigerd (zie 2).
3. Een aparte lijst van open leden naast de waarden is niet gekozen: een afnemer die haar niet leest, ziet `null` als "er is niets".
4. Dezelfde vorm in beide adapters; geen versieheader: strikt worden is een keuze van de auteur, die zijn regelset en haar versie zelf bumpt.

## Bouwdelen

1. Pin, verzoek, antwoord, `/info`, OpenAPI, `fixpoint`, beide adapters, de poorten (`service_replay.py` leest `attributeOpen`,
   `characteristicOpen` en `characteristicsAbsent`), `docs/ENGINE.md` en README.
2. De uitleg en haar delen, `openInputs`, de playground.

Elk deel een verse bouwer; elk deel eindigt met een groene `make gates` door de hoofdsessie en landt.

## Acceptance

- Over HTTP en Quarkus: een strikte regelset (uchars golden `jutterswet-afleiden-uit-leeg` en een eigen regelset met een stapel, een vouw, een
  kenmerk en een volledig lid) met open, vaststaand leeg en gevulde uitkomsten, en met `holdsNot` tegenover weggelaten, en een kale `false` als 400.
- De poort `check-service-replay` speelt de strikte goldens die uchars gate 12 speelt, met hun open en afwezige verwachtingen.
- Een gewone regelset: elk antwoord, elke uitleg en elk OpenAPI-document van de replay-set vóór en na byte-gelijk (eenmalig gemeten), op wat
  uchar veranderde.
- De uitleg van elke open uitkomst van die goldens, letterlijk in tests; en van elke vorm (regel, stapel, vouw, kwantorvouw,
  consistentie).
- `make gates` groen.

## Buiten de kaart

De semantiek (uchars). Een zin in de scenariolaag voor een open verwachting (uchars kaart). Tijdlijnen in een strikt document (uchar weigert ze).

## Result van deel 1: pin, verzoek, antwoord

### Per punt

- **De pin** (`513a1be37`): uchar `af54fac21`. Van de lijst "Voor de pin van de distributie" vroegen hier werk: de vier `switch`-en van de verbaliser
  over `ConditionReading.Status` zonder `default`, die op `OPEN` zwijgend doorliepen (elk faalt nu luid), de rol `reconcile-open` in `drlRules`, en `EndObject.open`. Er is geen
  compileerfout: een `switch` als opdracht hoeft niet volledig te zijn. In deze commit weigert een strikte regelset de start.
- **Het verzoek** (`264a94534`): `RequestMapper` schrijft `holds` onder `characteristics` en `holdsNot` onder `characteristicsAbsent` van de case; `null` en
  weggelaten zijn niet gegeven; een kale `false` is `WRONG_TYPE` met de twee vormen, een andere stand ook, een object met een andere sleutel `MALFORMED`.
- **Het antwoord** (`8b223f183`): elk lid van `output` met zijn stand; `/info` met `inferFromEmpty: false` en `completeInput`; `fixpoint` met `open` per
  object, ook in de 422; beide adapters (één `ServiceHttp`). De uitleg is een 400 `UNSUPPORTED` op `explanation`.
- **OpenAPI** (`8cdcb4b82`): `AttributeState` en `CharacteristicState` als opsommingen, een kenmerk van een verzoek als `true` of een stand.
- **Eén plaats**: `OpenValues.in(model)`. Een gewone regelset loopt langs de code die er stond.
- **De poort**: `service_replay.py` leest `characteristicsAbsent`, `attributeOpen` en `characteristicOpen`, en de standen van een strikt antwoord. Met een
  verwachting veranderd (open naar leeg, open naar afwezig) en met `characteristicsAbsent` weggelaten faalt zij, alle drie gemeten.
- **Scenario's en playground**: een verwachting tegenover een open uitkomst is FAIL met `openstaand`, op beide routes; het toetsdocument heeft geen zin voor een
  kenmerk dat niet geldt, dus `SituationToRequest` verandert niet; de pagina toont een open lid ("staat niet vast").
- **Documentatie** (`7d79970a6`): `docs/ENGINE.md` en README, "A strict rule set"; README, één punt onder "Contract change".

### Vóór en na de pin

Een programma in de werkmap (`compare.py`) start per map de dienst van `870251382` en die van deze tak (met `--detail`) over dezelfde bron en descriptor, elk
attribuut en kenmerk als `output`, en vergelijkt elke body byte voor byte: `/info`, `/openapi` (ook per vocabulaire), en per case het antwoord, het antwoord
met `explanation` en `explanationParts`, en dat met `trace`, `coverage` en `fixpoint`.

| corpus (van de oude pin) | gelijk | start niet, met dezelfde zin | anders |
|---|---|---|---|
| goldens | 384 mappen, 3983 bodies (3947 met 200), 6270 uitlegzinnen | 46 | 0 |
| rode cases | 58 mappen | 18 | 0 |

Gemeten na stap 1 en nog eens na stap 2: beide keren 0 verschillen. Op het corpus van de nieuwe pin: 385 gelijk (`jutterswet-gevuldtoets-soorten` erbij) en de
twee strikte goldens anders, die de oude bron niet parst.

### Getuigen

Verwachtingen met de hand gerekend, op een eigen regelset van de Jutterswet (`OpenValuesSource`: regel, kenmerk, defaults-groep, vouw, markers, consistentie,
twee volledige leden). `OpenValuesHttpTest` (21), `PlaygroundOpenValuesTest` (4), `ScenarioOpenValuesTest` (2), `OpenValuesParityTest` (Quarkus, 4),
`RequestMapperTest` (een `null`-attribuut). Beide strikte goldens van gate 12 spelen door de dienst, elk met zes cases.

### Gepinde getallen

- `EXPECTED_REPLAY_PLAYED` 374 naar 377 en `EXPECTED_REPLAY_CASES` 1057 naar 1072: `jutterswet-gevuldtoets-soorten` (3 cases) en de twee strikte goldens (6 en 6).
- `EXPECTED_RED_FAULTS` blijft 69; `RED_REFUSED` kreeg `jutterswet-kenmerk-wel-en-niet/case1.json` (EF-047: het verzoek geeft een kenmerk één keer).
- `PlaygroundDrlRulesTest`: de rol `reconcile-open`, die uchar erbij kreeg. Geen vloer en geen andere verwachting veranderde.

### Voorlopige besluiten

1. **Een attribuut met `null` is niet gegeven, in elke regelset.** Een gewone regelset gaf op zo'n verzoek een 500: uchars invoerstap weigert een `null` in de case
   sinds een eerdere pin. De mapper laat het lid nu uit de case. Dit is het ene antwoord van een gewone regelset dat verandert, van 500 naar 200.
2. **De uitleg weigert** in plaats van een neutrale zin (`Verbaliser.withoutExplanation`, de ene plaats); er wordt dan geen build met lezingen gemaakt, zodat een document dat
   alleen met lezingen geweigerd wordt (DROOLS-318) start.
3. `completeInput` noemt invoerleden: een lid dat een regel op zijn eigen declarerende type schrijft (de `berekende X` van de opgavemarkering, die uchar ook volledig
   noemt) staat er niet in. Vorm: `kind`, `objectType`, `name`, zoals `openInputs` haar leden zal noemen.
4. Een gedimensioneerd attribuut in `output` van een strikte regelset weigert de start: ch. 1.5 zegt van een cel niet of zij open is. Evenzo een attribuut zonder
   eigen beanveld, dat uchars eindstand niet geeft.
5. Een stand met een andere waarde dan `holds` of `holdsNot` is `WRONG_TYPE`, een object met een andere vorm `MALFORMED`, zoals bij een marker; geen nieuwe
   `Violation.Kind`, want de opsomming staat in het OpenAPI-document van elke regelset.
6. `fixpoint` noemt bij een open kenmerk dat het verzoek op `holdsNot` zette `"input": {"state": "holdsNot"}`; uchars `Open.given` heeft het niet, het komt uit het verzoek.

### Open punten

1. Een regelset die met `?explanation` moet uitleggen: deel 2.
2. Gezien en gelaten: `null` als waarde van een cel of segment liep al door de lezer van cellen en tijdlijnen en is niet gemeten.

### Poorten

Op `7d79970a6`, elk los: `mvn -B -o verify -Dmaven.test.skip.exec=true` groen (210, 615 en 66 tests), `make check-service-replay` PASS (377 goldens, 1072
cases, rode leg 69 en 14), `make check-twins` PASS (18 cases, 34 verzoeken), `make scenario-test` 0 mismatch. Daarna alleen javadoc.

## Result van deel 2: de uitleg, openInputs, de playground

### Per punt van de kaart

- **4, wat een uitkomst openhoudt** (`9869ae8ad`): `?openInputs=true` in beide adapters (`RequestOptions.openInputs`, `OpenInputs` als de ene schrijver, `OpenReads.openInputs`) en in het OpenAPI-document
  van een strikte regelset. Een open uitkomst zonder open invoerlid is een fout van de motor en valt luid. Op een gewone regelset en op een ingang zonder uitleg een 400 `UNSUPPORTED`.
- **5, de uitleg** (`9869ae8ad`, `2f5857032`): een zin per open uitkomst met één oorzaak en per vorm wat zij vond; sluitingen, samenstellingen, vouwen, kwantorvouwen, stapels, uitzonderingsgroepen,
  initialisaties en consistentieregels. `explanationParts`: `undetermined` en `closure` op `CONDITION`, `open` op `READING`, `unsettled` op `MEMBERS`, `undetermined` op een verdrongen
  `VARIANT`. `engine/OpenReads` zegt waardoor iets openstaat, uit uchars lezingen en de eindstand, zonder iets na te rekenen.
- **6, de playground** (`761c6a8ef`): per scenario de zinnen en "Open invoer"; een verwachting tegenover een open uitkomst blijft FAIL met `openstaand`.
- **Een document dat uchar alleen met lezingen weigert** (DROOLS-318): de ingang en het scenario antwoorden zonder lezingen; `explanation` en `openInputs` zijn een 400 met uchars zin.
- **Documentatie** (`28e622fb0`): `docs/ENGINE.md`, "A strict rule set" en "The parts of an explanation"; README; het klassecommentaar van `Verbaliser`.

### De acht correcties van de tweede lezing

1 (de woordvolgorde van "niet gegeven of hij betwist is"), 4, 5, 6, 7 en 8 zoals gevraagd; 8: een onbepaalde kwantorvouw heeft geen `READING`, haar lid staat in de kop. 2: het model draagt het lidwoord van een
declaratie niet, dus staat er overal "van J1 is zijn bonus niet gegeven". 3: "dat geen regel een waarde geeft" in plaats van "dat geen regel het vult", om dezelfde reden. Bij 6 heet een
regel waarvan de voorwaarde geldt en de waarde open is "wat de regel 'X' geeft, staat niet vast".

### Nagelezen en hersteld (`2f5857032`)

Drie keer een 500: een kwantorvouw met een toets op gevuld of leeg in haar filter, die door die sluiting geldt; een kenmerk uit het verzoek naast een open stapel met een lid dat iets besloot; een
regel op het supertype naast de stapel van een subtype. Drie keer een onware zin: "gaf geen waarde" van een lid van een uitzonderingsgroep dat iets gaf, waar de initialisatie over de groep besliste; een lid na de stop met een fout als "niet voor";
en een sluiting over een lid dat het filter uitsluit. De derde 500 had ook een gewone regelset.

### Getuigen

Letterlijke zinnen: `OpenExplanationHttpTest` (28), `OpenExplanationFormsHttpTest` (14: vormen die elkaar raken, elke kwantor beslist en onbepaald, geneste samenstelling, binder, subtype, marker, grondslag, vocabulaire),
`OpenValuesHttpTest`, `ScenarioOpenValuesTest`, `PlaygroundOpenValuesTest`. `OpenInputsHttpTest` (12). `OpenInputsPropertyTest`: elke open uitkomst van de strikte goldens en van twee eigen regelsets, met
elk invoerlid weggelaten, heeft een zin en staat onder een invoerlid, en dat lid geven verandert de uitkomst of de vraag. `OpenValuesParityTest` (6): Quarkus gelijk aan de JDK-dienst, met de weigeringen.

Eenmalig gemeten, niet gepind: elke golden als strikt document door de dienst (433 mappen, 239 starten, 3867 verzoeken met elk gegeven lid een keer weggelaten, 20355 open uitkomsten): geen 500, elke open uitkomst een zin en een plaats in
`openInputs`, geen zin "staat niet vast" over wat vaststaat, de stukken gelijk aan de zin.

Aangepast omdat het gedrag verandert: `OpenValuesHttpTest.what_stands_is_explained` (was `the_explanation_is_refused`), `ScenarioOpenValuesTest` en `PlaygroundOpenValuesTest` (de uitleg is er), en
`service_replay.py`: strikte goldens gaan door `explained`.

### Voorlopige besluiten

1. `openInputs` noemt van een samenstelling elk onbepaald lid en van een waarde of een vouw het eerste; op de gewone route van de playground staan alle afgeleide open leden van de situatie erin.
2. Een sluiting in een vouw is een bovengrens (uchars lezing noemt het lid niet): de open leden die het filter, de toets of het getelde attribuut op de leden van het bereik leest. In een waarde die vaststaat, zegt een zin
   alleen iets over een sluiting waar het filter zelf de toets is of de telling geen filter heeft.
3. De zin over een kenmerk dat een regel op het supertype toekent waar de groep van het subtype niets besloot, "… volgens een regel buiten de groep", ook in een gewone regelset, in plaats van een 500.

### Open punten

1. Voor uchar: een lezing noemt de open lezing niet waarover een sluiting in een vouw of in een waarde sloot, en van een bijdrager van een kwantorvouw niet of zijn filter vaststaat of een sluiting zijn oordeel gaf.
2. Gewone regelset, bestaand en gelaten: besliste de stapel van een subtype "niet toekennen" en kent een regel op het supertype het kenmerk toe, dan staan er twee zinnen die elkaar tegenspreken. Herstel verandert een
   zin van een gewone regelset.

### Poorten en vergelijking

Op `de265a72a`: `mvn -B -o verify -Dmaven.test.skip.exec=true` groen (212, 673 en 68 tests) en `make check-service-replay` PASS (377 goldens, 1072 cases, rode leg 69 en 14). Op `28e622fb0`, dat alleen documentatie en
javadoc toevoegt: `make check-twins` PASS (18 cases, 34 verzoeken) en `make scenario-test` 0 mismatch. Geen vloer en geen pin veranderde.

Gewone regelsets, `compare.py` als in deel 1, de jar van stap 2 van deel 1 (`1-step2.jar`; de rest van deel 1 raakte alleen strikte regelsets) tegen die van `28e622fb0`, elke body byte voor byte: goldens 385 mappen
gelijk, 46 starten niet met dezelfde zin, 2 anders; rood 58 gelijk, 18 starten niet, 1 anders. De drie die anders zijn, zijn strikt: `jutterswet-afleiden-uit-leeg` en
`jutterswet-kenmerk-wel-en-niet` op `/openapi` (de vlag en velden van de uitleg), `jutterswet-volledig-gegeven` op `/info` (`completeInput`, van het eind van deel 1).
Geen body van een gewone regelset verschilt.
