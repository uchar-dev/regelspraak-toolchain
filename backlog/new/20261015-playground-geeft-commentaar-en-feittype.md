# playground-geeft-commentaar-en-feittype: de regel en het commentaar van een declaratie, en het feittype van een rol in de situatie

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `2af1864` (submodule op uchar `f752a5c9`).*

## Aanleiding

De werkbank toont per scenario een objectboom uit de JSON van `Playground.analyseer`
(`service/…/http/Playground.java`, met `ScenarioGegevens.java`). Zij wil twee dingen tonen die die JSON niet geeft:

1. **Bij een klik op een attribuut- of typenaam** een popover met de namen per vocabulaire en het commentaar bij de
   declaratie. De namen staan al in `vocabulaires.elementen`. Het commentaar niet: uchars lexer zet `LINE_COMMENT`
   op het HIDDEN-kanaal (`uchar/frontends/regelspraak/grammar/RegelSpraakLexer.g4:501`), dus het staat niet in het
   model, wel in de tokenstroom van uchars parser. In de v2-regelset van herleving-ww staat boven elk objecttype,
   attribuut en kenmerk een commentaarblok met een `// Bron:`-regel. Een naam kan op meer typen staan (`maxdatum`
   op RechtOpUitkeringWwBijOntslag en op Uitkeringsduur), dus het commentaar hoort bij het paar (eigenaar, naam).
   Een lid van een `Extensie van objecttype X`-blok heeft zijn commentaar in dat blok.
2. **De relaties tussen de objecten van de situatie.** `situatie.objecten[].rollen[]` geeft `rol`, `doeltype`,
   `labels`, `meervoudig` en `regel`, maar niet het feittype waartoe uchar de rol oploste. De rol `recht` komt in
   veel feittypen voor, en de werkbank mag dat niet zelf afleiden.

## Wat er gebeurt

De JSON wordt alleen uitgebreid; bestaande sleutels houden hun vorm.

1. **`vocabulaires.elementen[]`** krijgt per element met een declaratie in de bron `regel` (de bronregel van de
   declaratie) en `commentaar` (de `//`-regels direct erboven, zonder `//` en zonder witruimte aan de randen,
   aaneengesloten tot de eerste niet-commentaarregel of lege regel). Ook zonder gedeclareerd vocabulaire: dan staan
   de elementen er met lege `benamingen`. Regel en commentaar komen uit uchars eigen parse: de parse tree en
   `getHiddenTokensToLeft` op de tokenstroom van uchars lexer, niet uit een zoektocht in de tekst. Een lid uit een
   extensie krijgt de regel en het commentaar van zijn regel in die extensie, met de eigenaar van de extensie. Een
   element dat zich niet eenduidig aan een declaratie laat koppelen, laat de analyse falen met een melding die het
   element noemt.
2. **`situatie.objecten[].rollen[]`** krijgt `feittype`, uit uchars geresolvede scenario of case
   (`ResolvedScenario`, `CaseEmitter`). Geeft uchars publieke API het daar niet, dan komt er geen omweg; het Result
   zegt dan precies wat in uchar ontbreekt.
3. Optioneel: de namen van `fixpunt.koppelingen[].feittype` gelijk aan die van punt 2.

## Acceptatie

- (a) Op het s01-document van herleving-ww (v2) heeft `maxdatum` van RechtOpUitkeringWwBijOntslag en van
  Uitkeringsduur elk een eigen `regel` en `commentaar`; een lid uit een extensie van hww-08 heeft de regel in die
  extensie en Uitkeringsduur als eigenaar.
- (b) Een bron zonder vocabulaire geeft de elementen met lege `benamingen`, met regel en commentaar.
- (c) Een element zonder eenduidige declaratie geeft een melding die het noemt, geen leeg veld.
- (d) Nieuw gedrag is getest in nieuwe testklassen; een bestaand testbestand verandert alleen als de uitbreiding het
  afdwingt, in een eigen commit met de reden in het Result.
- (e) `make gates` groen.

## Omvang

Klein. May touch: `service/` (`Playground`, `ScenarioGegevens`, een nieuwe klasse voor de declaraties), nieuwe
testklassen, `docs/ENGINE.md`, kaartverplaatsing. Niet `uchar/`.

## Raakvlakken

De werkbank (`werkbank/static/`) leest dit contract en wordt elders aangepast; zij is hier alleen gelezen.
