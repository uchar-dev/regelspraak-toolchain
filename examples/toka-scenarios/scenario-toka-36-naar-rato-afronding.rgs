// Golden TOKA scenario — toka-36 "verdeling treinmiles op basis van woonregio factor
// met afronding". Bron: spec §9.7.4 (naar rato, afgerond naar beneden) +
// TokaVuurTest.verdeling_naar_rato_met_afronding_en_rest_spec_974: contingent 1001 naar
// rato woonregio factor 3:2 -> 600 (P1) / 400 (P2), onverdeelde rest 1 op het contingent.
Situatie basis-36:
een Contingent treinmiles C1
- het totaal aantal treinmiles is 1001
een Natuurlijk persoon P1
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de woonregio factor is 3
een Natuurlijk persoon P2
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
- de woonregio factor is 2
Scenario verdeelt naar rato met afronding en laat een rest over
toetst de regel verdeling treinmiles op basis van woonregio factor met afronding
gebaseerd op de situatie basis-36
Met als verwacht resultaat:
- de treinmiles van P1 is 600
- de treinmiles van P2 is 400
- het restant na verdeling van C1 is 1
