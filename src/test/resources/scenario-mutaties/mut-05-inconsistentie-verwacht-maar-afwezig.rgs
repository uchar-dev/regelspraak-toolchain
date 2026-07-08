// MUTATIE 05 — Inconsistentie verwacht maar afwezig.
// De consistentieregel eist dat de afstand kleiner is dan 1000 km. De situatie geeft 200 km, dus de
// gegevens zijn CONSISTENT (geen Inconsistentie-feit). De verwachting eist 'de gegevens zijn inconsistent'.
// FOUT: een Inconsistentie wordt verwacht maar er is er geen (200 km < 1000 km, de assertie houdt).
// De runner moet FALEN op '- de gegevens zijn inconsistent' met verwacht=inconsistent / werkelijk=consistent.
// MOET schoon resolven: de consistentieregel + verwachting zijn welgevormd — alleen de verwachting is fout.
Objecttype de Vlucht (mv: Vluchten)
    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;

Regel afstand niet te groot
geldig altijd
De afstand tot bestemming van een Vlucht moet kleiner zijn dan 1000 km.

Situatie basis:
een Vlucht V1
- de afstand tot bestemming is 200 km

Scenario onterecht inconsistent
toetst de regel afstand niet te groot
gebaseerd op de situatie basis
Met als verwacht resultaat:
- de gegevens zijn inconsistent
