// Golden TOKA scenario — toka-17 "bepaal leeftijd".
// Bron: §12 TOKA-casus (leeftijd = tijdsduur van geboortedatum tot vluchtdatum van
// zijn reis, in hele jaren). Gekozen data: geboren 15-06-1990, vlucht 15-06-2024 →
// exact 34 hele jaren (spec-eenheid jr).
Situatie basis-17:
een Vlucht V1
- de vluchtdatum is 15-06-2024
een Natuurlijk persoon P1
- de geboortedatum is 15-06-1990
- de reis is V1
Scenario leeftijd wordt berekend uit geboortedatum en vluchtdatum
toetst de regel bepaal leeftijd
gebaseerd op de situatie basis-17
Met als verwacht resultaat:
- de leeftijd van P1 is 34 jr
