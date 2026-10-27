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
