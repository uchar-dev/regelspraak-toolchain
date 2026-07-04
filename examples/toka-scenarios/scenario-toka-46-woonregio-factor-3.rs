// Golden TOKA scenario — toka-46 beslistabel "Woonregio factor", rij 3 ("Woonregio factor 3").
// Bron: §12 TOKA-casus / toka-46 beslistabel: woonprovincie in {Noord-Holland, Zuid-Holland,
// Utrecht} -> woonregio factor 3. Woonprovincie "Utrecht" -> factor 3.
Situatie basis-46-3:
een Natuurlijk persoon P1
- de woonprovincie is "Utrecht"
Scenario Utrechtse woonprovincie geeft woonregio factor 3
toetst de regel Woonregio factor 3
gebaseerd op de situatie basis-46-3
Met als verwacht resultaat:
- de woonregio factor van P1 is 3
