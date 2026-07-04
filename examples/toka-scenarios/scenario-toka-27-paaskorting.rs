// Golden TOKA scenario — toka-27 "Paaskorting".
// Bron: §12 TOKA-casus (reis met paaskorting iff vluchtdatum == eerste paasdag van
// het jaar uit de vluchtdatum). Eerste paasdag 2024 = 31-03-2024 (computus) ->
// vluchtdatum 31-03-2024 -> reis met paaskorting.
Situatie basis-27:
een Vlucht V1
- de vluchtdatum is 31-03-2024
Scenario vlucht op paaszondag krijgt paaskorting
toetst de regel Paaskorting
gebaseerd op de situatie basis-27
Met als verwacht resultaat:
- V1 is een reis met paaskorting
