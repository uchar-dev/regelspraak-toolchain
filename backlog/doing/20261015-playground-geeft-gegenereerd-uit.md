# playground-geeft-gegenereerd-uit: per element dat uchar zelf maakte, het element waaruit het komt

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `b2d3ed8` (branch `card/playground-rol-geeft-feittype`, gekeurd
en nog niet geland; deze kaart bouwt erop voort).*

## Aanleiding

De werkbank toont in de objectboom `berekende maxdatum` en `afwijkende opgave maxdatum`, en in de dekking en de trace
`defaults A:RechtOpUitkeringWwBijOntslag.maxdatum`. Die namen staan niet in de bron: uchar maakt ze uit de markering
`(mag opgegeven worden)` en uit de effectieve defaultsstapels. De werkbank wil zulke elementen een eigen kleur en een
uitleg geven en doorverwijzen naar het element waaruit ze komen. Op de naam mag dat niet: "berekende" of " voor " kan
ook in een geschreven naam staan.

uchar legt de herkomst vast op de kaart `gegenereerde-elementen-dragen-herkomst` (branch
`card/gegenereerde-elementen-dragen-herkomst`): `RegelSpraakModel.gegenereerd` en `gegenereerdUit(Benoembaar)` voor
de elementen van het model, en `TmsTranspiler.gegenereerd()` voor de namen in de dekking die geen declaratie noemen
(een effectieve defaultsstapel, een versie `R #k`).

## Wat er gebeurt

De JSON van `Playground.analyseer` wordt alleen uitgebreid; bestaande sleutels houden hun vorm.

1. **De pin** gaat naar de uchar-branch van die kaart.
2. **`vocabulaires.elementen[]`**: een element dat uchar zelf maakte, krijgt `gegenereerdUit: {soort, eigenaar, naam,
   vorm}`: soort, eigenaar en naam van het bronelement (canoniek, zoals `soort`, `eigenaar` en `canoniek` van een
   element) en `vorm`, uchars vaste sleutel (`opgave-boven-berekening`, …).
3. **`gegenereerd`**, naast `dekking` als het document uitvoerbaar is: per naam in de dekking die geen element van
   `vocabulaires.elementen` is, `{naam, soort, gegenereerdUit}`, met `naam` in het gekozen vocabulaire zoals de
   dekking en de trace van een scenario hem schrijven en `naamCanoniek` ernaast waar die afwijkt, en `soort` de soort
   uit de dekking. Een naam in de dekking die geen element is en die uchar niet als gegenereerd noemt, laat de analyse
   falen met een zin die de naam noemt.

De toolchain leidt niets af uit een naam: alles komt uit uchars tabellen.

## Acceptatie

- (a) Op het s01-document van herleving-ww (v2) hebben `opgegeven maxdatum`, `berekende maxdatum` en `afwijkende
  opgave maxdatum` van RechtOpUitkeringWwBijOntslag `gegenereerdUit` naar het attribuut `maxdatum` van dat type, en
  geeft `gegenereerd` voor `defaults A:RechtOpUitkeringWwBijOntslag.maxdatum` hetzelfde attribuut.
- (b) Een element met `gegenereerdUit` heeft geen `regel`; op elke golden van uchar met een `bron.rgs` heeft elk
  element `regel` of `gegenereerdUit` (een uitzondering staat met haar reden in de test), en elke naam in de dekking
  is een element of staat in `gegenereerd`.
- (c) Nieuw gedrag in een nieuwe testklasse; een bestaand testbestand verandert alleen als de uitbreiding het
  afdwingt, in een eigen commit met de reden in het Result.
- (d) `docs/ENGINE.md` noemt de sleutels. `make gates` groen.

## Omvang

Klein. May touch: de submodule-pin `uchar`, `service/` (`Playground`), een nieuwe testklasse, `docs/ENGINE.md`,
kaartverplaatsing. Niet `uchar/`.

## Raakvlakken

De werkbank (`werkbank/static/`) leest dit contract en wordt elders aangepast.
