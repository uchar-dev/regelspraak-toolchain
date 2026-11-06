# marker-on-value-part: een marker in de uitleg is als marker te herkennen

*Opgesteld door de werkbank-sessie op 2026-11-06, na `writer-holds-on-no-day` (toolchain `eb912c57d`, uchar `74119a23e`).*

## Aanleiding

Sinds `absence-markers-in-service` schrijft de uitleg de waarde van een attribuut met afwezigheidsmarkers als haar
payload en een marker bij zijn naam (`docs/ENGINE.md`, "The parts of an explanation", de rij `VALUE`). Het blad `VALUE`
van een marker draagt geen meta: `Verbaliser.render` en `renderRead` maken van de `ConstructorValue` een string
(`c.constructor()`) voordat `value(…)` het blad maakt, en dat blad kent alleen `empty: true` en `timeline: true`. Een
afnemer van `explanationParts` kan een marker daardoor alleen aan zijn tekst herkennen, en die tekst is ook die van een
enumeratiewaarde of een literal met dezelfde spelling (een domein `'onbekend' | 'getaxeerd'`).

De werkbank leest geen tekst: zij kleurt en omkadert per soort stuk en per meta. Zij toont een lege waarde
(`empty: true`) in een gestreept kader, en wil een marker tonen als een gevulde waarde zonder getal: in de kleur van
een waarde, met een doorgetrokken kader, omdat een marker beslist zoals een waarde en leeg niets beslist. In haar
objectboom doet zij dat al met `{"marker": …}` uit de situatie en het fixpunt; in de uitleg kan zij het pas als het blad
het zegt.

## Goal

- Elk blad `VALUE` dat een marker schrijft, draagt `marker: "<naam>"`, de naam in de gedeclareerde spelling: in de kop
  van een zin ("V1 heeft als taxatie onbekend volgens …"), als waarde die een voorwaarde of berekening las ("zijn
  taxatie (niet van toepassing) een waarde heeft"), op een cel ("V1 heeft als gewicht in voorjaar onbekend …"), en als
  literal in een vergelijking ("is gelijk aan onbekend") waar die een eigen blad `VALUE` is (de rij `VALUE` noemt "a
  literal"); staat zij daar in een `QUOTE` van de regeltekst, zeg dat in het Result en laat het zo.
- Een blad met de payload van zo'n attribuut ("300,00") en elk ander blad `VALUE` draagt geen `marker`.
- De tekst verandert niet: `explanation` en de tekst van elk stuk blijven byte-gelijk.
- Een tijdlijn (`timeline: true`) is één blad met al haar perioden in de tekst ("onbekend van 01-01-2025 tot
  01-03-2025, …"); daar een periode met een marker te markeren vraagt een andere vorm van `VALUE` (per periode een
  stuk). Dat hoort niet bij deze kaart. Tel in het Result hoe vaak een tijdlijn met een marker voorkomt in uchar's
  goldens die de replay speelt, zodat de werkbank kan beslissen of zij die vorm wil vragen.
- README en `docs/ENGINE.md` als huidige stand: de rij `VALUE` noemt `marker`.

## Acceptance

- (a) Rood eerst: een test die op de uitleg van uchar's golden `jutterswet-afwezigheidsmarkers` per blad `VALUE` met
  een marker `marker` verwacht, en op het blad van een payload en van een enumeratiewaarde geen `marker`, faalt op main.
- (b) Over de goldens die de replay speelt is `explanation` byte-gelijk met main, en elk stuk heeft dezelfde `kind` en
  `text`; alleen `marker` komt erbij.
- (c) `make gates` groen.

May touch: `toolchain/` (`Verbaliser`, `Part`), `service/` waar de stukken naar JSON gaan, README, `docs/ENGINE.md`,
kaartverplaatsing.

## Omvang

Klein.

## Result
