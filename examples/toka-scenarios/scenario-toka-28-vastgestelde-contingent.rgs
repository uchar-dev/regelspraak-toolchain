// Golden TOKA scenario — toka-28 "vastgestelde contingent treinmiles" (§9.3 objectcreatie).
// Bron: §12 TOKA-casus. De regel CREEERT een Contingent treinmiles voor de Vlucht met
// aantal treinmiles = hoeveelheid passagiers maal de parameter. Het gecreeerde object
// heeft geen LABEL in de situatie, dus de scenario-oppervlakte kan zijn attribuut niet per
// label uitlezen; de verwachting toetst daarom consistentie (de regel vuurt, zie de trace,
// en levert geen Inconsistentie). Parametergate: 'de aantal treinmiles per passagier voor
// contingent' gezet op 100 (spec pint geen waarde).
Situatie basis-28:
een Vlucht V1
- de hoeveelheid passagiers is 3
de parameter de aantal treinmiles per passagier voor contingent is 100
Scenario stelt een contingent treinmiles vast voor de vlucht
toetst de regel vastgestelde contingent treinmiles
gebaseerd op de situatie basis-28
Met als verwacht resultaat:
- de gegevens zijn consistent
