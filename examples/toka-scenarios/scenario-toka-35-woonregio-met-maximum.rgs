// Golden TOKA scenario — toka-35 "verdeling treinmiles op basis van woonregio factor en
// met maximum waarde". Bron: spec §9.7.3 rekenvoorbeeld (Tabel 17 + maximum 300 per
// passagier): contingent 1200 over Wilma (factor 3), Hans (factor 1), Diederik (factor 2).
// Naar rato: Wilma 3/6*1200 = 600 -> begrensd op 300; Hans 1/6*1200 = 200; Diederik
// 2/6*1200 = 400 -> begrensd op 300. Restant na verdeling = 1200 - 300 - 200 - 300 = 400.
Situatie basis-35:
een Contingent treinmiles C1
- het totaal aantal treinmiles is 1200
een Natuurlijk persoon P1
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de woonregio factor is 3
- de maximaal aantal te ontvangen treinmiles is 300
een Natuurlijk persoon P2
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de woonregio factor is 1
- de maximaal aantal te ontvangen treinmiles is 300
een Natuurlijk persoon P3
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de woonregio factor is 2
- de maximaal aantal te ontvangen treinmiles is 300
Scenario verdeelt naar rato met een maximum per passagier
toetst de regel verdeling treinmiles op basis van woonregio factor en met maximum waarde
gebaseerd op de situatie basis-35
Met als verwacht resultaat:
- de treinmiles van P1 is 300
- de treinmiles van P2 is 200
- de treinmiles van P3 is 300
- het restant na verdeling van C1 is 400
