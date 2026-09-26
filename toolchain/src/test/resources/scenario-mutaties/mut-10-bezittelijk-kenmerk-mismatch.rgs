// MUTATIE 10 — bezittelijk kenmerk verwacht aanwezig maar afwezig.
// Er is GEEN regel die 'recht op duurzaamheidskorting' toekent, en de situatie kent het niet toe.
// Het bezittelijk kenmerk blijft dus afwezig. De verwachting eist 'P1 heeft het recht op
// duurzaamheidskorting'.
// FOUT: een bezittelijk kenmerk wordt aanwezig verwacht maar is afwezig (niets kent het toe).
// De runner moet FALEN op '- P1 heeft het recht op duurzaamheidskorting' met verwacht=aanwezig /
// werkelijk=afwezig.
// MOET schoon resolven: het bezittelijk kenmerk is gedeclareerd en de 'heeft'-verwachting is welgevormd.
Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)
    de leeftijd Numeriek (geheel getal) met eenheid jr;
    het recht op duurzaamheidskorting kenmerk (bezittelijk);

Regel leeftijdregel
geldig altijd
De leeftijd van een Natuurlijk persoon moet berekend worden als 30 jr.

Situatie basis:
een Natuurlijk persoon P1
- de leeftijd is 30 jr

Scenario onterecht bezittelijk kenmerk
toetst de regel leeftijdregel
gebaseerd op de situatie basis
Met als verwacht resultaat:
- P1 heeft het recht op duurzaamheidskorting
