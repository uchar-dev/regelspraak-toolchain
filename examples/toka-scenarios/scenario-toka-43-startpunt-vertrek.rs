// Golden TOKA scenario — toka-43 "Startpunt variabele periode op datum van vertrek" (§9.9).
// Bron: §12 TOKA-casus. De regel bepaalt het startpunt van de variabele periode via de datum
// van vertrek van de reis; het effect is een tijdlijn-startpunt (geen label-adresseerbare
// scalar/kenmerk), dus de verwachting toetst consistentie — de regel vuurt (zie de trace) en
// levert geen Inconsistentie.
Situatie basis-43:
een Vlucht V1
- de datum van vertrek is 15-06-2024
Scenario bepaalt het periodestartpunt uit de vertrekdatum
toetst de regel Startpunt variabele periode op datum van vertrek
gebaseerd op de situatie basis-43
Met als verwacht resultaat:
- de gegevens zijn consistent
