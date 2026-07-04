// MUTATIE 08 — gevuld verwacht maar leeg.
// Er is GEEN regel die de bijnaam van een Natuurlijk persoon zet, en de situatie geeft P1 geen bijnaam.
// De bijnaam blijft dus leeg. De verwachting eist 'de bijnaam van P1 is "Tommie"'.
// FOUT: een concrete waarde ("Tommie") wordt verwacht maar het attribuut is leeg (niets zet het).
// De runner moet FALEN op '- de bijnaam van P1 is "Tommie"' met verwacht="Tommie" / werkelijk=leeg.
// MOET schoon resolven: de verwachting is welgevormd (Tekst op een Tekst-attribuut) — alleen fout.
Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)
    de bijnaam Tekst;
    de leeftijd Numeriek (geheel getal) met eenheid jr;

Regel leeftijdregel
geldig altijd
De leeftijd van een Natuurlijk persoon moet berekend worden als 30 jr.

Situatie basis:
een Natuurlijk persoon P1
- de leeftijd is 30 jr

Scenario onterecht gevuld
toetst de regel leeftijdregel
gebaseerd op de situatie basis
Met als verwacht resultaat:
- de bijnaam van P1 is "Tommie"
