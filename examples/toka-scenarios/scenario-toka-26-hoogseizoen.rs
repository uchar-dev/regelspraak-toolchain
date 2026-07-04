// Golden TOKA scenario — toka-26 "Hoogseizoen".
// Bron: §12 TOKA-casus (hoogseizoen iff maand uit de vluchtdatum in {6,7,8}).
// vluchtdatum 15-07-2024 -> maand 7 -> in het hoogseizoen.
Situatie basis-26:
een Vlucht V1
- de vluchtdatum is 15-07-2024
Scenario julivlucht valt in het hoogseizoen
toetst de regel Hoogseizoen
gebaseerd op de situatie basis-26
Met als verwacht resultaat:
- V1 is in het hoogseizoen
