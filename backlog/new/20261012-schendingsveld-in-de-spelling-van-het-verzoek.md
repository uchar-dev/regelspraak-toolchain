# schendingsveld-in-de-spelling-van-het-verzoek: een 400 wijst het veld aan zoals het verzoek het schreef

*Vastgelegd door de hoofdsessie bij de kaart `20261010-sessie-via-de-ingang-van-uchar` (2026-10-12), uit de herkeuring
van fixronde 1. Het gedrag bestond al vóór die kaart.*

## Aanleiding

`NaamLezer` leest een naam in een verzoek ongeacht hoofdletters, en ook als benaming in een vocabulaire. Een schending in
het pad `relaties` noemt het veld echter met de canonieke rolnaam (`VerzoekMapper`, de `ONBEKEND_VELD`-schendingen bij
`rolA`/`rolB`, en sinds die kaart ook de `VERKEERD_TYPE` van een tweede doel). Schrijft het verzoek
`"AANMELDER": "J2"`, of de benaming van de rol in een ander vocabulaire, dan zegt de 400
`relaties[0].rollen.aanmelder`. Dat veld staat niet in het verzoek. Een afnemer die de schending via het pad aanwijst,
vindt het veld dan niet.

## Wat er gebeurt

Een schending noemt het veld zoals het verzoek het schreef. Het canonieke veld mag ernaast staan.

## Acceptatie

- (a) Rood eerst: een verzoek met een rolnaam in hoofdletters en een met een benaming uit een ander vocabulaire. De 400
  noemt in beide gevallen het veld zoals geschreven.
- (b) Beide adapters geven hetzelfde (`FoutParityTest`).
- (c) `make poorten` groen.

## Omvang

Klein. May touch: `service/` (de mapper en de schendingen), `service-quarkus/` alleen voor de pariteit, en de tests.
