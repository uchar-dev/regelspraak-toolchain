// Golden TOKA scenario — toka-34 "Verdeling treinmiles op basis van leeftijd en woonregio
// factor". Bron: spec §9.7.2 Tabel 17 rekenvoorbeeld: contingent 1200 over Wilma (20 jr,
// factor 3), Hans (20 jr, factor 1), Diederik (22 jr, factor 2). Op volgorde van toenemende
// leeftijd; Wilma en Hans (jongst, gelijk) delen naar rato: Wilma 3/4 * 1200 = 900, Hans
// 1/4 * 1200 = 300. Het contingent is dan op -> Diederik krijgt 0 en er is geen restant.
Situatie basis-34:
een Contingent treinmiles C1
- het totaal aantal treinmiles is 1200
een Natuurlijk persoon P1
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de leeftijd is 20 jr
- de woonregio factor is 3
een Natuurlijk persoon P2
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de leeftijd is 20 jr
- de woonregio factor is 1
een Natuurlijk persoon P3
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de leeftijd is 22 jr
- de woonregio factor is 2
Scenario verdeelt jongste groep eerst naar rato
toetst de regel Verdeling treinmiles op basis van leeftijd en woonregio factor
gebaseerd op de situatie basis-34
Met als verwacht resultaat:
- de treinmiles van P1 is 900
- de treinmiles van P2 is 300
- de treinmiles van P3 is 0
- het restant na verdeling van C1 is 0
