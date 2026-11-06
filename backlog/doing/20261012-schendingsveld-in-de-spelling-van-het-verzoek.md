# schendingsveld-in-de-spelling-van-het-verzoek: een 400 wijst het veld aan zoals het verzoek het schreef

*Vastgelegd door de hoofdsessie bij de kaart `20261010-sessie-via-de-ingang-van-uchar` (2026-10-12), uit de herkeuring
van fixronde 1. Het gedrag bestond al vóór die kaart.*

## Aanleiding

`NameReader` leest een naam in een verzoek ongeacht hoofdletters, en ook als benaming in een vocabulaire. Een schending in
het pad `relaties` noemt het veld echter met de canonieke rolnaam (`RequestMapper`, de `UNKNOWN_FIELD`-schendingen bij
`roleA`/`roleB`, en sinds die kaart ook de `WRONG_TYPE` van een tweede doel). Schrijft het verzoek
`"AANMELDER": "J2"`, of de benaming van de rol in een ander vocabulaire, dan zegt de 400
`relations[0].roles.aanmelder`. Dat veld staat niet in het verzoek. Een afnemer die de schending via het pad aanwijst,
vindt het veld dan niet.

## Wat er gebeurt

Een schending noemt het veld zoals het verzoek het schreef. Het canonieke veld mag ernaast staan.

## Acceptatie

- (a) Rood eerst: een verzoek met een rolnaam in hoofdletters en een met een benaming uit een ander vocabulaire. De 400
  noemt in beide gevallen het veld zoals geschreven.
- (b) Beide adapters geven hetzelfde (`ErrorParityTest`).
- (c) `make gates` groen.

## Omvang

Klein. May touch: `service/` (de mapper en de schendingen), `service-quarkus/` alleen voor de pariteit, en de tests.

## Result

*Gebouwd op 2026-11-06 in de branch `card/scope-check-and-cleanup`. Logs in `uchar-dev/werk/toolchain-cleanup/logs/`.*

`RequestMapper.rolesRead` houdt per gedeclareerde rolnaam de sleutel bij die het verzoek schreef, en elke schending
in `relations[i].roles` noemt het veld met die sleutel: de `UNKNOWN_FIELD` van een onbekend id, de `WRONG_TYPE` van
een id dat de rol niet vult en de `WRONG_TYPE` van een tweede doel van een to-one rol (via het veld dat de link
schreef). De zin van de schending mag de gedeclareerde rol noemen. De inline `roles` noemden hun veld al zoals
geschreven.

- (a) Rood eerst (`logs/c4-red.log`): `RelationsTest.a_violation_names_the_role_field_as_the_request_wrote_it` met
  `"PASSAGIER"` (TOKA, onbekend id: het veld was `relations[0].roles.passagier`) en met `"melder"`, de benaming van
  de rol aanmelder in het vocabulaire `handboek` van uchars golden `jutterswet-vocabulaires` (een Vondst op de rol:
  het veld was `relations[0].roles.aanmelder`). Groen (`logs/c4-green.log`).
- (b) `ErrorParityTest.a_role_written_in_capitals_is_the_field_as_written` (`"AANMELDER"`, tweede doel) en
  `…_as_a_designation_of_a_vocabulary_is_the_field_as_written` (`"melder"`): beide adapters geven dezelfde 400 met
  het veld zoals geschreven. De reactor is groen (`logs/c4-reactor.log`: toolchain 197, service 502, service-quarkus 57, geen skip).
- (c) `make gates`: zie het slot van deze Result.

Docs: `docs/ENGINE.md` (namen en vocabulaires) en `README.md` ("Input is liberal") zeggen dat een schending haar veld
noemt zoals het verzoek het schreef.
