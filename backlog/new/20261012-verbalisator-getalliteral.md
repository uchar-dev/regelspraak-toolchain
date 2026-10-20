# verbalisator-getalliteral: de uitleg leest een getalliteral in de notatie van het model

*Vastgelegd door de hoofdsessie bij de kaart `20261010-sessie-via-de-ingang-van-uchar` (2026-10-12), uit de bijvangst
van stap 1. Regelnummers op `card/sessie-via-de-ingang-van-uchar`.*

## Aanleiding

De uitleg van de scenario-runner (`bin/regelspraak scenario --uitleg`, het veld `uitleg` van de dienst en de playground)
geeft een getalliteral met eenheid verkeerd weer. Gemeten met een eigen model met de regel "Een Vlucht is belast indien de
heffing van de Vlucht groter is dan 30,00 EUR", en een afgeleide heffing van 60,00 EUR. De bron, met `bin/regelspraak scenario --uitleg <bron>`:

    Objecttype de Vlucht (mv: Vluchten)
        de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
        de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
        is belast kenmerk (bijvoeglijk);
    Parameter het tarief per km : Numeriek (getal met 2 decimalen) met eenheid EUR/km;
    Regel heffingsregel
    geldig altijd
    De heffing van een Vlucht moet berekend worden als de afstand tot bestemming van de Vlucht maal het tarief per km.
    Regel belastregel
    geldig altijd
    Een Vlucht is belast indien de heffing van de Vlucht groter is dan 30,00 EUR.
    Situatie basis:
    een Vlucht V1
    - de afstand tot bestemming is 200 km
    de parameter het tarief per km is 0,30 EUR/km
    Scenario belast door de heffing
    toetst de regels heffingsregel en belastregel
    gebaseerd op de situatie basis
    Met als verwacht resultaat:
    - de heffing van V1 is 60,00 EUR
    - V1 is een belast

De uitleg:

    V1 is belast, omdat de heffing van de Vlucht (60,00) is groter dan 3.000 eur

Verwacht: "… (60,00 EUR) is groter dan 30,00 EUR".
- Op de oude pin (uchar `0a378588`) en de nieuwe (`b536bbc9`) is de uitvoer gelijk. Het is dus geen gevolg van de
  pinbump.
- `Verbaliser.parseNumber` (`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java`
  r. 314–318) leest de tekst van de literal als Nederlandse notatie: een punt is een duizendtal, een komma een decimaal.
  Het model draagt "30.00", en dat wordt 3000. De eenheid verschijnt in kleine letters ("eur").
- Een afgeleide waarde met eenheid verschijnt zonder eenheid ("(60,00)", "(400)"), terwijl een invoerattribuut met
  eenheid km wel "(200 km)" geeft (`VerbaliserTest`).

## Wat er gebeurt

De Verbalisator leest de waarde van een getalliteral in de notatie die het model draagt, en geeft literal en afgeleide
waarde weer met de eenheid zoals de declaratie die schrijft.

## Acceptatie

- (a) Rood eerst: een getuige met het model hierboven, die de verwachte zin pint.
- (b) Een getuige voor een afgeleide waarde met eenheid.
- (c) `make poorten` groen.

## Omvang

Klein. May touch: `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java` en zijn tests.
