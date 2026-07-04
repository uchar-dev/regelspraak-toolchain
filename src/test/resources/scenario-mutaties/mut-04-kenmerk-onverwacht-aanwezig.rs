// MUTATIE 04 — kenmerk verwacht AFWEZIG maar de regel kent het WEL toe.
// De regel kent 'belaste reis' toe indien de afstand > 100 km. De situatie geeft V1 een afstand van 200 km,
// dus de regel vuurt en V1 IS een belaste reis. De verwachting eist 'V1 is geen belaste reis'.
// FOUT: het kenmerk 'belaste reis' wordt afwezig verwacht maar is aanwezig (afstand 200 km > 100 km).
// De runner moet FALEN op '- V1 is geen belaste reis' met verwacht=afwezig / werkelijk=aanwezig.
// MOET schoon resolven: 'geen belaste reis' strip't naar het gedeclareerde kenmerk — alleen de verwachting is fout.
Objecttype de Vlucht (mv: Vluchten)
    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
    is belaste reis kenmerk;

Regel Kenmerktoekenning belaste reis
geldig altijd
Een Vlucht is een belaste reis indien de afstand tot bestemming van de Vlucht groter is dan 100 km.

Situatie basis:
een Vlucht V1
- de afstand tot bestemming is 200 km

Scenario onverwacht kenmerk
toetst de regel Kenmerktoekenning belaste reis
gebaseerd op de situatie basis
Met als verwacht resultaat:
- V1 is geen belaste reis
