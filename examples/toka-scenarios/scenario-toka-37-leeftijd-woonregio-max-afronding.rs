// Golden TOKA scenario — toka-37 "Verdeling treinmiles op basis van leeftijd, woonregio
// factor, met maximum waarde en afronding". Bron: spec §9.7.5 Tabel 18 +
// TokaVuurTest.verdeling_op_volgorde_met_maximum_en_afronding_tabel_18: contingent 1800,
// op volgorde van (jongste) leeftijd, naar rato woonregio factor, met maximum en afronding
// -> P1 500, P2 500, P3 433, P4 250, P5 91; onverdeelde rest 26.
Situatie basis-37:
een Contingent treinmiles C1
- het totaal aantal treinmiles is 1800
een Natuurlijk persoon P1
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de leeftijd is 22 jr
- de woonregio factor is 3
- de maximaal aantal te ontvangen treinmiles is 500
een Natuurlijk persoon P2
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de leeftijd is 39 jr
- de woonregio factor is 2
- de maximaal aantal te ontvangen treinmiles is 500
een Natuurlijk persoon P3
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de leeftijd is 39 jr
- de woonregio factor is 1
- de maximaal aantal te ontvangen treinmiles is 500
een Natuurlijk persoon P4
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de leeftijd is 66 jr
- de woonregio factor is 3
- de maximaal aantal te ontvangen treinmiles is 250
een Natuurlijk persoon P5
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de leeftijd is 66 jr
- de woonregio factor is 1
- de maximaal aantal te ontvangen treinmiles is 250
Scenario verdeelt op leeftijd en woonregio met maximum en afronding
toetst de regel Verdeling treinmiles op basis van leeftijd, woonregio factor, met maximum waarde en afronding
gebaseerd op de situatie basis-37
Met als verwacht resultaat:
- de treinmiles van P1 is 500
- de treinmiles van P2 is 500
- de treinmiles van P3 is 433
- de treinmiles van P4 is 250
- de treinmiles van P5 is 91
- het restant na verdeling van C1 is 26
