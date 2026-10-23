# pin-subject-anchor: de pin naar uchar met het vormanker (`zelf`)

*Hoofdsessie, 2026-10-24, op verzoek van de eigenaar: de werkbank heeft het vormanker nodig voor hww-06.*

## Aanleiding

uchar main `57d0bb6a` brengt sinds de pin `f22c9ff5` de kaart `subselection-reads-subject`: een knoop in een filter of
een kwantortoets kan het onderwerp van zijn vorm lezen (`anchor: "form"`, `IdentityCheck`; IR-SPEC ch. 4.1a, WF-122), in
RegelSpraak "de X zelf"; de lowering over de weg terug is weg (RGS-348 en RGS-352 vervallen), drie goldens kregen het
anker, en er zijn vijf spelende goldens bij (`EXPECTED_PLAYED` van uchars gate 12: 274 → 279).

## Doel

De pin `uchar` → `57d0bb6a`; de replay-pin en de README-getallen volgen. Verder niets.

## Acceptatie

- (a) `make check-pin` PASS; `check-service-replay` met de herrekende pin, elke nieuwe speler en elke nieuwe uitsluiting
  met reden.
- (b) `make gates` groen.

May touch: de pin, `scripts/check-service-replay.sh` (de replay-pin, de lijst van uitsluitingen), `README.md`
(getallen), deze kaart.
