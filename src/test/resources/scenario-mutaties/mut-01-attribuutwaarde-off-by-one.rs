// MUTATIE 01 — attribuutwaarde off-by-one.
// De regel 'afstandsregel' berekent de afstand tot bestemming als 200 km. De verwachting eist 201 km.
// FOUT: de verwachte waarde (201) wijkt één af van de berekende waarde (200).
// De runner moet FALEN op de verwachtingregel '- de afstand tot bestemming van V1 is 201 km' en
// verwacht=201 km / werkelijk=200 km melden.
// Dit fixture MOET schoon resolven (types kloppen; alleen de waarde is fout) — het falen hoort bij de runner.
Objecttype de Vlucht (mv: Vluchten)
    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;

Regel afstandsregel
geldig altijd
De afstand tot bestemming van een Vlucht moet berekend worden als 200 km.

Situatie basis:
een Vlucht V1
- de afstand tot bestemming is 200 km

Scenario off-by-one
toetst de regel afstandsregel
gebaseerd op de situatie basis
Met als verwacht resultaat:
- de afstand tot bestemming van V1 is 201 km
