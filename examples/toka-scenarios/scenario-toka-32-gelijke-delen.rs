// Golden TOKA scenario — toka-32 "verdeling treinmiles in gelijke delen".
// Bron: spec §9.7.1 (gelijke delen) + TokaVuurTest.verdeling_gelijke_delen_spec_971:
// contingent 1000 over 2 rechthebbenden in gelijke delen -> 500 elk.
Situatie basis-32:
een Contingent treinmiles C1
- het totaal aantal treinmiles is 1000
een Natuurlijk persoon P1
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
een Natuurlijk persoon P2
- de te verdelen contingent treinmiles is C1
- de treinmiles is 0
Scenario verdeelt het contingent in gelijke delen
toetst de regel verdeling treinmiles in gelijke delen
gebaseerd op de situatie basis-32
Met als verwacht resultaat:
- de treinmiles van P1 is 500
- de treinmiles van P2 is 500
