// MUTATIE 11 — enumeratie-waarde mismatch.
// De regel berekent de status als 'geboekt'. De verwachting eist "de status van V1 is 'geannuleerd'".
// FOUT: de verwachte enum-waarde ('geannuleerd') is niet gelijk aan de berekende ('geboekt').
// De runner moet FALEN op "- de status van V1 is 'geannuleerd'" met verwacht='geannuleerd' /
// werkelijk='geboekt'.
// MOET schoon resolven: beide zijn waarden van hetzelfde enum-domein — alleen de waarde is fout.
Domein Boekingsstatus is van het type Enumeratie
    'geboekt'
    'geannuleerd'
    'ingecheckt'

Objecttype de Vlucht (mv: Vluchten)
    de status Boekingsstatus;

Regel statusregel
geldig altijd
De status van een Vlucht moet berekend worden als 'geboekt'.

Situatie basis:
een Vlucht V1
- de status is 'geboekt'

Scenario verkeerde enum
toetst de regel statusregel
gebaseerd op de situatie basis
Met als verwacht resultaat:
- de status van V1 is 'geannuleerd'
