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
