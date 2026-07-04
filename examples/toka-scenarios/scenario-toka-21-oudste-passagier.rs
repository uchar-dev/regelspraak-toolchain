// Golden TOKA scenario — toka-21 "Leeftijd oudste passagier".
// Bron: §12 TOKA-casus (leeftijd oudste passagier = maximale waarde van de
// leeftijden van alle passagiers). p1 = 40 jr, p2 = 30 jr -> 40 jr.
Situatie basis-21:
een Vlucht V1
een Natuurlijk persoon P1
- de reis is V1
- de leeftijd is 40 jr
een Natuurlijk persoon P2
- de reis is V1
- de leeftijd is 30 jr
Scenario kiest de hoogste leeftijd
toetst de regel Leeftijd oudste passagier
gebaseerd op de situatie basis-21
Met als verwacht resultaat:
- de leeftijd van de oudste passagier van V1 is 40 jr
