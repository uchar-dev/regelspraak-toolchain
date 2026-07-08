// Golden TOKA scenario — toka-24 "Te betalen belasting van een passagier".
// Bron: TokaVuurTest.belastingketen_afstand_naar_te_betalen_belasting (de deelstap
// toka-24: te betalen = belasting op basis van afstand min de korting, minimaal 0,
// afgerond op 0 decimalen naar beneden). Gegeven belasting op basis van afstand 60,00
// en korting 10,00 -> 50,00. Parametergate: 'de korting bij gebruik niet-fossiele
// brandstof' gezet op 10,00 (Bedrag is eenheidloos: Numeriek getal met 2 decimalen).
Situatie basis-24:
een Natuurlijk persoon P1
- de belasting op basis van afstand is 60,00
de parameter de korting bij gebruik niet-fossiele brandstof is 10,00
Scenario trekt de korting af met minimum nul
toetst de regel Te betalen belasting van een passagier
gebaseerd op de situatie basis-24
Met als verwacht resultaat:
- de te betalen belasting van P1 is 50,00
