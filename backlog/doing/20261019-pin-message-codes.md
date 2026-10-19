# pin-message-codes: de pin volgt uchar met meldingscodes, catalogi en nieuwe paden

*Vastgelegd op 2026-10-19. Hoort bij de uchar-stappen 7 (meldingscodes en catalogi) en 8 (paden). Toolchain `cc9b4f9`,
uchar-pin `e2343945` naar `476e0ff83`.*

## Aanleiding

Sinds de vorige pin kregen uchars meldingen codes en komt hun tekst uit een catalogus: de Drools-backend geeft standaard
Engels, de frontend Nederlands, gekozen met `uchar.messages.locale` of een `Locale` per aanroep. Verder heten
`corpus/negatief` nu `corpus/negative`, `corpus/rood` `corpus/red` en `bron.*` `source.*`, en zijn de case-slugs hernoemd.

## Wat er gebeurt

1. De pin naar `476e0ff83`; `make check-pin`.
2. De eigen modules compileren tegen de nieuwe API en herstellen wat breekt.
3. De paden in de eigen scripts en tests (`bron.rgs` naar `source.rgs`, `corpus/rood` naar `corpus/red`).
4. De standaardlocale `nl` op één plek in de distributie, met de CLI-vlag en `Accept-Language` als overschrijving.

## Acceptatie

- (a) `make gates` groen.
- (b) Onder `nl` blijft de meldingstekst voor gebruikers gelijk, behalve de zinnen die uchar bewust vertaalde.
- (c) De JSON- en OpenAPI-goldens blijven gelijk; een afwijking krijgt een oorzaak in het Result.

## Omvang

May touch: alles in de worktree behalve `uchar/` (alleen de pin), `examples/toka/*.rgs` en `specification/`.
