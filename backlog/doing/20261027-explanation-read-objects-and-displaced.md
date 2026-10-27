# explanation-read-objects-and-displaced: het object bij elke gelezen waarde, en wat een verdrongen variant gaf

*Voorstel van de werkbank (2026-10-27), op besluit van de eigenaar. Niet gecommit tot de bouw. Deze kaart raakt
`20261022-explanation-part-groups`, die open staat; punt 2 daarvan zet de varianten van een defaults-groep als groep.
Deze kaart wijzigt die kaart niet. Vervolg in de werkbank: een pin-kaart, die de werkbank zelf schrijft.*

## Aanleiding

De werkbank bouwt de definitieve eval. Daarin begint een agent bij de zin van de uitkomst waar de vraag om gaat, en
vraagt hij door naar de zinnen van de waarden die die zin leest. Het opzoeken gebeurt bij de afnemer, niet in de dienst
(besluit van de eigenaar, 2026-10-27).

Gemeten op scenario s01-v01 van de eval-dataset (werkbank 089e79f, toolchain b4f3ab94, rules ece012e):
- de uitleg heeft 55 zinnen;
- het antwoordmodel van de vraag steunt op ongeveer 9 daarvan;
- de statische graaf (`graphJson`) van de gevraagde attributen raakt er 42; daarmee is niet te selecteren.

Twee dingen ontbreken.

1. **Bij een gelezen waarde staat niet van welk object zij is.**
   - Zin 9: "… 'herlevingstermijn verstreken art. 21 lid 3 en 4 en art. 8 lid 3' gold niet, want niet geldt: zijn
     herlevingstermijn (onbekend) gevuld is".
   - De stukken zijn `NAME {declaration: ATTRIBUUT, owner: "Recht op uitkering WW", canonical: "herlevingstermijn"}` en
     `VALUE {empty: true}`.
   - Zin 10 ("R1 heeft geen herlevingstermijn, want geen variant besliste; …") legt die waarde uit, maar niets in de
     stukken verbindt de twee zinnen.
   - Bij "zijn" is het object het onderwerp. Bij een pad ("de herlevingsdatum van alle eindigingsgronden van het
     RechtOpUitkering") staan de objecten niet in de stukken; soms staan ze in de tekst ("over G1 (01-08-2025)").
2. **Een verdrongen variant staat alleen bij naam.**
   - Zin 47: "G1 heeft als herlevingsdatum 01-08-2025 volgens 'gewenste maand in het herlevingsverzoek art. 21 lid 1 na
     onderdeel c' …; verdrongen: 'herleving per de maand van het einde bij de inkomensgrond art. 21 lid 1 na onderdeel c'
     (uitzondering op …), …, 'herleving per kalendermaand art. 21 lid 1 na onderdeel c', de regel 'herleving na het
     vervallen art. 21 lid 1'".
   - Het antwoordmodel heeft als may: "herleving per 01-07-2025 was mogelijk geweest". Dat is de waarde van 'herleving per
     kalendermaand …'. De zin noemt die waarde niet; de lezer moet haar zelf uit zin 24 halen.
   - De kaart `explanation-defaults` (2026-10-20) koos: "de lagere varianten alleen bij naam, als verdrongen".

## Doel

1. **Het object bij een gelezen waarde.**
   - Elk stuk dat een waarde noemt die de motor voor de zin las (`VALUE`, en `NAME`/`QUOTE` met hun sleutels), zegt van
     welk object of welke objecten die waarde is. Dat is het label zoals `SUBJECT` het noemt.
   - De vorm kiest de bouwer: een veld op `VALUE`, of op `NAME`/`QUOTE`.
   - Een programma moet voor elke gelezen waarde met een schrijver de zin kunnen vinden die die schrijver uitlegt. Die
     zin begint met `SUBJECT` (het object) en `NAME` (dezelfde `canonical`).
   - Een invoerwaarde heeft geen eigen zin; het object zegt dan van wie de invoer is.
   - De platte zin in `explanation` blijft voor dit deel bytegelijk.
2. **Wat een verdrongen variant gaf.**
   - Per verdrongen variant: of haar voorwaarde gold, en zo ja, welke waarde zij gaf (bij een attribuut) of wat zij
     besliste (bij een kenmerk).
   - Dat staat in de stukken als meta, en in de platte zin. Standaardvorm in de zin: "'herleving per kalendermaand art.
     21 lid 1 na onderdeel c' (gold, 01-07-2025)", "(gold, gaf geen waarde)" en "(gold niet)", zonder reden. Wijk je
     af, zet dan voor en na in het `Result:`.
   - Meet eerst of de motor lagere varianten uitrekent, zowel in de DRL-verlaging als in de motor van de Playground.
     Rekent hij ze niet uit en is daarvoor een wijziging in uchar nodig, schrijf dan een uchar-kaart. Dit deel volgt
     daarna; deel 1 kan los landen.

OpenAPI, `ContractNamesInMessagesTest` en `docs/ENGINE.md` ("The parts of an explanation") gaan mee.

## Acceptatie

- (a) Op de vijf service-verzoeken van v2/herleving-ww en op de goldens staat bij elke gelezen waarde het object.
  Uitzonderingen alleen met reden in het `Result:`. Een test bewijst: voor elke gelezen waarde met een schrijver staat
  de zin van (object, `canonical`) in dezelfde uitleg.
- (b) Een golden met een defaults-groep waarin een lagere variant gold en een waarde gaf: de zin noemt "(gold, <waarde>)".
  Een golden waarin zij niet gold: "(gold niet)".
- (c) De platte zinnen veranderen alleen door deel 2. Het `Result:` geeft het aantal veranderde zinnen op de vijf
  verzoeken, en voor en na van één ervan.
- (d) `make gates` groen.

## May touch

De code van de uitleg in de toolchain, OpenAPI, `docs/ENGINE.md`, tests en goldens, de pin (als er een uchar-kaart
landt), en deze kaart.

## Out of scope

- Een endpoint per vraag in de dienst; het opzoeken gebeurt bij de afnemer (besluit van de eigenaar).
- In de platte zin markeren welke delen van een samengestelde voorwaarde golden. De zin blijft zo; de afnemer stelt een
  weergave samen uit `holds` (besluit van de eigenaar, 2026-10-27).
- `explanation-part-groups`.
- De werkbank.

## Result deel 1 (2026-10-27)

Tak `card/explanation-read-objects` in `wt-tc/explanation-read-objects`, vanaf toolchain main `b4f3ab94`, uchar-pin
`c6a45d89` ongewijzigd. Werkmap `werk/toolchain-2026-10-27/read-objects/` (`meting/`, `logs/`, `tools/`).

**Ontwerp.** Naar het Fable-advies `research/uitleg-gelezen-objecten-2026-10-27/fable-advies.md` (§4.1, beslispunten
D1 en D8), als voorlopig besluit van de hoofdsessie:

- Eén sleutel `object` op elke `NAME` en `QUOTE` met de sleutels van een attribuut of kenmerk waarvan de zin de
  waarde op één object noemt: het label zoals `SUBJECT` het schrijft (een gemaakt object bij zijn naam). Ook op de
  kopnaam van elke zin (het onderwerp), zodat elk paar (`object`, `canonical`) dezelfde opzoekregel heeft: de zin van
  dat paar is de zin waarvan de kop hetzelfde paar draagt.
- Eén label, geen lijst. `object: null` bij een lezing over een rol waarvan een link ontbreekt (de lezing is leeg).
- Geen `object` (uitzonderingen met reden): een parameter (van geen object); een `ROL`-naam; de tekst en het filter van
  een vouw over een verzameling (per lid gelezen; de leden staan als `SUBJECT` in `MEMBERS`); een lezing die de motor
  niet bereikte; de naam van een default die de opgavemarkering maakte ("'opgegeven maxdatum'"), die een variant noemt
  en geen waarde.
- **Bron van het object: `DroolsBackend.reached`** (uchars bestaande ingang, over de wandeling die de lader naast de
  lezing geeft), als overbrugging (D1). Een kenmerkcheck over een rol gaat er ook door, met een `AttributeReference`
  op het kenmerk en het type dat de wandeling bereikt, omdat `reached` geen check neemt. Fable beveelt aan het object in
  de lezing van uchar te zetten (RUNTIME-SEMANTICS ch. 5.12): uchar-kaart `reading-read-objects` (in de bouw). Met de
  pin van deel 2 leest de uitleg het object uit `NodeReading.object` en vervalt de omweg; de uitvoer blijft dezelfde,
  want uchars getuige houdt het object van de lezing gelijk aan `reached`.

**Wat er veranderde.** `Verbaliser`: `Read` kent het object van de lezing en het objecttype van het onderwerp van de
vorm; `On` zegt van welk object een naam de waarde noemt; `declaration(d, on)` schrijft `object`; de kop van elke zin,
de eigen leden van het onderwerp ("de X in de invoer leeg was", "de X had een invoerwaarde"), elk operand in een
voorwaarde en een berekening (`readOn`) en elke kenmerkcheck (`checkOn`) geven het mee. `OpenApiEmitter`: de
beschrijving van `ExplanationPart` en de eigenschap `object` (string, nullable); de vier goldens overgeschreven (alleen
die twee verschillen). `docs/ENGINE.md` ("The object of a value", de rijen `NAME` en `QUOTE`), README ("Contract
change"), de javadoc van `Part`.

**Getuigen.**

- `ExplanationReadObjectsServiceTest` (dienst, uit bronnen en uit het IR-document): per zin elke naam met haar object
  op een eigen model: de kop, een eigen lid, een rol van één stap (M1 → R1; M3 → R2, waar de waarde leeg is), van twee
  stappen (M1 → D1; M3 → `null`, R2 heeft geen dagloon), een afwezige link (M4 → `null`), een kenmerkcheck over een rol
  (M1 → R1, M4 → `null`), een vouw zonder `object`. Rood op de code van `beba0959`: geen enkele naam draagt `object`.
- Replay-leg (`scripts/service_replay.py`, `read_links`): op elke golden van gate 12 vindt elke gelezen waarde met een
  schrijver de zin van (object, `canonical`); "met een schrijver" = een vorm van de scope schrijft dat lid op het type
  van het object of een supertype (een regel van `rules.rules`, een defaults-groep); het type staat in het verzoek, of
  is het `createdType` van de creatie (bij een versie die van de versie). Een golden zonder die zin faalt. Gemeten: 4248
  koppelingen over 249 goldens en 673 cases, de vloer `EXPECTED_MIN_READ_LINKS=4248` in `check-service-replay.sh`. Rood met de jar van main: 0 koppelingen
  (`anchor/jutterswet-kustwacht` 0 tegen 36, `jutterswet-aanmelderkrediet` 0 tegen 6).
- Bestaande tests die stukken met meta pinnen (de kaart verandert wat ze pinden; geen toets verzwakt): elke wijziging
  is het invoegen van `object=…`, nagerekend met een tijdelijke dump in `ExplanationPartsCheck.render` (alle tests groen
  met `object` weggestreept, `tools/repin.py`): `ExplanationPartsServiceTest` (zinnen 0, 5, 8, 10, 15, 20; de constanten
  `ERVAREN` en `LEEFTIJD` werden functies van het object), `ExplanationComputationServiceTest`,
  `ExplanationDefaultsComputationServiceTest`, `ExplanationMadeObjectsServiceTest` (een gemaakt object als object van
  zijn eigen lid).

**Meting (acceptatie (a) en (c)) op de vijf service-verzoeken van v2/herleving-ww en s01** (regels `ece012e`;
`meting/measure.sh`, `meting/objects.py`, `meting/links.py`; vóór: jar van `b4f3ab94`, na: de jar van de tak):

- zonder vlag, met `?explanation=true` en de platte zinnen bytegelijk; de stukken gelijk op `object` na. Deel 1
  verandert dus 0 zinnen.
- 384 kopnamen en eigen leden op zinsniveau, elk met hun onderwerp; 595 gelezen waarden met een object, 92 met
  `object: null` (een afwezige link: "de code sz product van de uitkering van het RechtOpUitkering (onbekend)"), 57
  zonder (vouwteksten en filters).
- van de 595 vinden er 338 de zin van (object, `canonical`); de overige 257 zijn invoer zonder schrijver ('code reden
  beëindiging recht op uitkering ww' 115 keer, 'eerste rechtdag', 'jareneis', …). Met de kopnamen erbij vinden 648
  gelezen waarden met een schrijver hun zin (`read_links`, 0 zonder).
- Voorbeeld, s01 zin 9: `QUOTE «zijn herlevingstermijn» {declaration: ATTRIBUUT, owner: "Recht op uitkering WW",
  canonical: "herlevingstermijn"}` krijgt `object: "R1"`, en zin 10 begint met `SUBJECT «R1»` en `NAME
  {canonical: "herlevingstermijn", object: "R1"}`. Zin 10: `NAME «werkzaamheden-als-politiek ambtsdrager»` over de rol
  'aanvraag uitkering' krijgt `object: "A1"`; zin 35: «de herlevingsgrens van het recht van het Inkomen
  in-een-kalendermaand» krijgt `object: "R1"`.

**Gates (acceptatie (d)).** `make gates` op `7b52bb08` groen, 7 stappen in 5m05s (`logs/gates-1.log`): reactor
173/390/53 testgevallen, replay 249 goldens en 673 cases met 4248 koppelingen, twins, manifest, image, kind-smoke.
Deel 1 landt los; de kaart blijft in `doing/` voor deel 2.
