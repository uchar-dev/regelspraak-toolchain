// MUTATIE 07 — leeg verwacht maar gevuld.
// De regel berekent de naam als "Lijnvlucht". De situatie geeft V1 geen naam, maar de regel vult hem.
// De verwachting eist 'de naam van V1 is leeg'.
// FOUT: leeg wordt verwacht maar de naam is gevuld ("Lijnvlucht") door de regel.
// De runner moet FALEN op '- de naam van V1 is leeg' met verwacht=leeg / werkelijk="Lijnvlucht".
// MOET schoon resolven: de leeg-verwachting op een bestaand Tekst-attribuut is welgevormd — alleen fout.
Objecttype de Vlucht (mv: Vluchten)
    de naam Tekst;
    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;

Regel naamregel
geldig altijd
De naam van een Vlucht moet berekend worden als "Lijnvlucht".

Situatie basis:
een Vlucht V1
- de afstand tot bestemming is 200 km

Scenario onterecht leeg
toetst de regel naamregel
gebaseerd op de situatie basis
Met als verwacht resultaat:
- de naam van V1 is leeg
