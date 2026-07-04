// MUTATIE 06 — consistent verwacht maar inconsistent.
// De consistentieregel eist dat de afstand kleiner is dan 1000 km. De situatie geeft 1500 km, dus de
// assertie FAALT en er komt een Inconsistentie-feit. De verwachting eist 'de gegevens zijn consistent'.
// FOUT: consistentie wordt verwacht maar de gegevens zijn inconsistent (1500 km >= 1000 km).
// De runner moet FALEN op '- de gegevens zijn consistent' met verwacht=consistent / werkelijk=inconsistent.
// MOET schoon resolven: de regel + verwachting zijn welgevormd — alleen de verwachting is fout.
Objecttype de Vlucht (mv: Vluchten)
    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;

Regel afstand niet te groot
geldig altijd
De afstand tot bestemming van een Vlucht moet kleiner zijn dan 1000 km.

Situatie basis:
een Vlucht V1
- de afstand tot bestemming is 1500 km

Scenario onterecht consistent
toetst de regel afstand niet te groot
gebaseerd op de situatie basis
Met als verwacht resultaat:
- de gegevens zijn consistent
