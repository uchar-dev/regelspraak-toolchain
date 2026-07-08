// Golden TOKA scenario — toka-18 "Kenmerktoekenning persoon minderjarig".
// Bron: §12 TOKA-casus. minderjarig iff X < volwassenleeftijd, met
// X = tijdsduur(geboortedatum -> vluchtdatum van zijn reis) in hele jaren.
// Parametergate: 'de volwassenleeftijd' gezet op 18 jr (spec pint geen waarde;
// keuze conform het volwassenleeftijd-gebruik in toka-42/45). Geboren 15-06-2010,
// vlucht 15-06-2024 -> X = 14 < 18 -> minderjarig.
Situatie basis-18:
een Vlucht V1
- de vluchtdatum is 15-06-2024
een Natuurlijk persoon P1
- de geboortedatum is 15-06-2010
- de reis is V1
de parameter de volwassenleeftijd is 18 jr
Scenario jonge passagier is minderjarig
toetst de regel Kenmerktoekenning persoon minderjarig
gebaseerd op de situatie basis-18
Met als verwacht resultaat:
- P1 is minderjarig
