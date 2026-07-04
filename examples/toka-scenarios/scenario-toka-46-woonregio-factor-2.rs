// Golden TOKA scenario — toka-46 beslistabel "Woonregio factor", rij 2 ("Woonregio factor 2").
// Bron: §12 TOKA-casus / toka-46 beslistabel: woonprovincie in {Noord-Brabant, Gelderland,
// Overijssel, Flevoland} -> woonregio factor 2. Woonprovincie "Gelderland" -> factor 2.
Situatie basis-46-2:
een Natuurlijk persoon P1
- de woonprovincie is "Gelderland"
Scenario Gelderse woonprovincie geeft woonregio factor 2
toetst de regel Woonregio factor 2
gebaseerd op de situatie basis-46-2
Met als verwacht resultaat:
- de woonregio factor van P1 is 2
