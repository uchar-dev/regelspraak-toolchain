// Golden TOKA scenario — toka-45 "belasting op basis van afstand" (§4.2 geldig vanaf 2018).
// Bron: TokaVuurTest.belastingketen_afstand_naar_te_betalen_belasting: X = lage basistarief
// eerste schijf = 100,00; Y = lage tarief vermindering eerste schijf (0,20) maal afstand in
// km (200) = 40,00 -> belasting op basis van afstand = X min Y = 60,00. De regel geldt vanaf
// 2018; peildatum 01-01-2024 opent het venster. Voorwaarden: belaste reis, band-kenmerk
// (passagier van 25 tot en met 64 jaar), 0 < afstand <= bovengrens (500 km), X-Y >= 0.
Situatie basis-45:
een Vlucht V1
- de bereikbaar per trein is waar
- is belaste reis
- de afstand tot bestemming is 200 km
een Natuurlijk persoon P1
- de reis is V1
- is passagier van 25 tot en met 64 jaar
de parameter het lage basistarief eerste schijf is 100,00
de parameter het lage tarief vermindering eerste schijf is 0,20
de parameter de bovengrens afstand eerste schijf is 500 km
Scenario berekent de belasting op basis van afstand in de eerste schijf
toetst de regel belasting op basis van afstand
met peildatum 01-01-2024
gebaseerd op de situatie basis-45
Met als verwacht resultaat:
- de belasting op basis van afstand van P1 is 60,00
