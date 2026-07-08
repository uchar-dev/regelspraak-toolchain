// Golden TOKA scenario — toka-42 "Passagier van 18 tm 24 jaar" (kenmerk).
// Bron: §12 TOKA-casus. Een Natuurlijk persoon is een passagier van 18 tot en met 24 jaar
// indien: zijn leeftijd >= volwassenleeftijd, zijn leeftijd <= 24 jr, en hij is een passagier
// (d.w.z. hij heeft een reis — de resolver leest 'passagier' als de rol uit het feittype
// Vlucht van natuurlijke personen). leeftijd 20 jr, volwassenleeftijd 18 jr, reis gezet.
Situatie basis-42:
een Vlucht V1
een Natuurlijk persoon P1
- de leeftijd is 20 jr
- de reis is V1
de parameter de volwassenleeftijd is 18 jr
Scenario twintigjarige passagier valt in de band 18 tot en met 24
toetst de regel Passagier van 18 tm 24 jaar
gebaseerd op de situatie basis-42
Met als verwacht resultaat:
- P1 is een passagier van 18 tot en met 24 jaar
