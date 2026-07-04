// Golden TOKA scenario — toka-46 beslistabel "Woonregio factor", rij 1 ("Woonregio factor 1").
// Bron: §12 TOKA-casus / toka-46 beslistabel: woonprovincie in {Friesland, Groningen, Drenthe,
// Zeeland, Limburg} -> woonregio factor 1. Woonprovincie "Friesland" -> factor 1.
Situatie basis-46-1:
een Natuurlijk persoon P1
- de woonprovincie is "Friesland"
Scenario Friese woonprovincie geeft woonregio factor 1
toetst de regel Woonregio factor 1
gebaseerd op de situatie basis-46-1
Met als verwacht resultaat:
- de woonregio factor van P1 is 1
