# dienst-per-vocabulaire: de dienst, de OpenAPI en de uitleg spreken het vocabulaire dat de afnemer kiest

Uchar krijgt benoemde vocabulaires met een benaming per declaratie (kaart
`vocabulaires-benamingen` in uchar, 2026-09-27): een zijtafel zonder
semantiek, uniek per vocabulaire, met een publieke leesingang in het
model. Deze kaart gebruikt die tafel aan de rand: op de JSON-grens van de
dienst en in de verwoording. Hangt af van de landing van die kaart en de
pin-bump hierheen.

## U-besluiten (hoofdsessie, 2026-09-27)

- U1 Keuze van het vocabulaire: de descriptor noemt per entrypoint een
  standaardvocabulaire (of geen: canoniek); een verzoek kan er één kiezen
  met een header en een queryparameter (de bouwer kiest de namen en de
  vaste voorrang: query boven header boven descriptor boven canoniek); een
  onbekend vocabulaire is een 400 met een eigen soort en een zin die de
  gedeclareerde vocabulaires noemt.
- U2 Verzoek en antwoord: de verzoeklezer vertaalt veldnamen, rolnamen,
  parameternamen en enumeratiecodes van het gekozen vocabulaire naar
  canoniek vóór ze de beans raken, via de omkering die uchar levert; de
  antwoordbouwer vertaalt terug, inclusief inconsistenties en
  regelnamen in schendingen en uitleg; wat in het gekozen vocabulaire
  geen benaming heeft, draagt de canonieke naam (het vocabulaire is dan
  niet volledig; `/info` zegt per vocabulaire of het volledig is).
- U3 OpenAPI: één document per vocabulaire (pad of parameter, de bouwer
  kiest), gegenereerd uit dezelfde emitter; de bestaande golden blijft
  het canonieke document.
- U4 Verwoording: de verbalisator, de uitleg, de trace en `graafJson` van
  de playground en de scenarioroute (`--vocabulaire`) gebruiken het
  gekozen vocabulaire voor namen van objecttypen, attributen, kenmerken,
  rollen, regels en enumeratiewaarden; de regeltekst zelf blijft
  canoniek.
- U5 Poorten: een tweeling uit uchars corpus met vocabulaires via HTTP in
  elk vocabulaire, met dezelfde gepinde verwachtingen; een vloer; de
  bestaande poorten onveranderd groen; `make gates`.
- U6 README: hoe een afnemer een vocabulaire kiest, wat volledig betekent,
  en dat een gewijzigde benaming een contractwijziging is.

Mag raken: `service/`, `service-quarkus/`, `toolchain/`, `scripts/`,
README/CLAUDE.md. Niet: `uchar/` (een vraag aan uchar is een kaart daar),
`examples/toka*`, `specification/`.
