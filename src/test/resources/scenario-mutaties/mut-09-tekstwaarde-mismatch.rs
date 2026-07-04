// MUTATIE 09 — tekstwaarde-mismatch (verkeerde string).
// De regel berekent de naam als "Lijnvlucht". De verwachting eist 'de naam van V1 is "Chartervlucht"'.
// FOUT: de verwachte tekst ("Chartervlucht") is niet gelijk aan de berekende tekst ("Lijnvlucht").
// De runner moet FALEN op '- de naam van V1 is "Chartervlucht"' met verwacht="Chartervlucht" /
// werkelijk="Lijnvlucht".
// MOET schoon resolven: Tekst op Tekst-attribuut, geen typefout — alleen de waarde is fout.
Objecttype de Vlucht (mv: Vluchten)
    de naam Tekst;

Regel naamregel
geldig altijd
De naam van een Vlucht moet berekend worden als "Lijnvlucht".

Situatie basis:
een Vlucht V1
- de naam is "Lijnvlucht"

Scenario verkeerde tekst
toetst de regel naamregel
gebaseerd op de situatie basis
Met als verwacht resultaat:
- de naam van V1 is "Chartervlucht"
