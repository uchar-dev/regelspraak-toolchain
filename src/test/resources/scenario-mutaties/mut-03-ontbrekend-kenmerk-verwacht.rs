// MUTATIE 03 — kenmerk verwacht AANWEZIG maar de regel kent het niet toe.
// De regel kent 'belaste reis' toe indien de afstand > 100 km. De situatie geeft V1 een afstand van 50 km,
// dus de regel vuurt NIET en V1 is GEEN belaste reis. De verwachting eist toch 'V1 is een belaste reis'.
// FOUT: het kenmerk 'belaste reis' wordt verwacht maar is afwezig (afstand 50 km <= 100 km).
// De runner moet FALEN op '- V1 is een belaste reis' met verwacht=aanwezig / werkelijk=afwezig.
// MOET schoon resolven: 'belaste reis' is een gedeclareerd kenmerk — alleen de verwachting is fout.
Objecttype de Vlucht (mv: Vluchten)
    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
    is belaste reis kenmerk;

Regel Kenmerktoekenning belaste reis
geldig altijd
Een Vlucht is een belaste reis indien de afstand tot bestemming van de Vlucht groter is dan 100 km.

Situatie basis:
een Vlucht V1
- de afstand tot bestemming is 50 km

Scenario ontbrekend kenmerk
toetst de regel Kenmerktoekenning belaste reis
gebaseerd op de situatie basis
Met als verwacht resultaat:
- V1 is een belaste reis
