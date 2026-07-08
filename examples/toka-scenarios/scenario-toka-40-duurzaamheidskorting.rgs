// Golden TOKA scenario — toka-40 "Recht op Duurzaamheidskorting" (bezittelijk kenmerk).
// Bron: §12 TOKA-casus. Een passagier heeft recht op duurzaamheidskorting indien: zijn reis
// is duurzaam, de afstand tot bestemming >= duurzaamheidskorting minimale afstand, en zijn
// leeftijd >= pensioenleeftijd. Reis duurzaam, afstand 200 km, leeftijd 70 jr; parameters
// min afstand 100 km, pensioenleeftijd 67 jr (spec pint geen waarden) -> recht.
Situatie basis-40:
een Vlucht V1
- is duurzaam
- de afstand tot bestemming is 200 km
een Natuurlijk persoon P1
- de reis is V1
- de leeftijd is 70 jr
de parameter de duurzaamheidskorting minimale afstand is 100 km
de parameter de pensioenleeftijd is 67 jr
Scenario oudere passagier op duurzame lange reis krijgt korting
toetst de regel Recht op Duurzaamheidskorting
gebaseerd op de situatie basis-40
Met als verwacht resultaat:
- P1 heeft recht op duurzaamheidskorting
