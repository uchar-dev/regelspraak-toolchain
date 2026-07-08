// MUTATIE 02 — verkeerde maar VERENIGBARE eenheid, geconverteerd toch fout.
// De regel berekent de afstand als 2000 m (= 2 km). De verwachting eist '3 km'. Beide eenheden zijn
// verenigbaar (km/m), dus de resolver rekent de verwachting 3 km = 3000 m — maar dat wijkt af van 2000 m.
// FOUT: de verwachte 3 km (3000 m) is niet gelijk aan de berekende 2 km (2000 m).
// De runner moet FALEN op '- de afstand tot bestemming van V1 is 3 km' met verwacht=3000 m / werkelijk=2000 m.
// MOET schoon resolven: km en m zijn verenigbaar, dus geen eenheidsfout — enkel de waarde is fout.
Objecttype de Vlucht (mv: Vluchten)
    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid m;
Eenheidsysteem afstand
    de kilometer (mv: kilometers)     km      =    1000 m
    de meter (mv: meters)             m

Regel afstandsregel
geldig altijd
De afstand tot bestemming van een Vlucht moet berekend worden als 2000 m.

Situatie basis:
een Vlucht V1
- de afstand tot bestemming is 2000 m

Scenario verkeerde eenheid
toetst de regel afstandsregel
gebaseerd op de situatie basis
Met als verwacht resultaat:
- de afstand tot bestemming van V1 is 3 km
