// Golden TOKA scenario — toka-30 "Controleer of vlucht geen rondvlucht is".
// Bron: §12 TOKA-casus / §9.5 consistentieregel (luchthaven van vertrek moet ongelijk
// zijn aan luchthaven van bestemming). Verschillende luchthavens -> consistent.
Situatie basis-30:
een Vlucht V1
- de luchthaven van vertrek is 'Amsterdam Schiphol'
- de luchthaven van bestemming is 'Parijs Charles de Gaulle'
Scenario verschillende luchthavens zijn consistent
toetst de regel Controleer of vlucht geen rondvlucht is
gebaseerd op de situatie basis-30
Met als verwacht resultaat:
- de gegevens zijn consistent
