# pin-and-tokens: pin naar uchar met de semantic tokens, en `bin/regelspraak tokens`

*Opdracht van de eigenaar 2026-10-22 (syntax highlighting van de regels in de werkbank), vastgelegd door de
hoofdsessie. Vervolg op de uchar-kaart `semantic-tokens` (uchar `backlog/done/20261022-semantic-tokens.md`).*

## Aanleiding

uchar main draagt sinds 2026-10-22 de semantic tokens: `Resolver.tokens(List<SemanticTokens.SourceFile>)` geeft per
bronbestand spans die de tekst dekken, met een soort (sleutelwoord, commentaar, objecttype, attribuut, rol, parameter,
domeinwaarde, `unresolved`, …), een positie (regel vanaf 1, kolom in UTF-16) en bij een verwijzing de plek van haar
declaratie; de diagnostics met code staan in dezelfde uitvoer (uchar `grammar/DIVERGENCES.md`, "Semantic tokens"). De
werkbank roept de toolchain aan via `bin/regelspraak` en wil de regels daarmee kleuren. Verder brengt de pin de
landingen van die dag mee: de datumconstructie in ref en clingo (EF-032), een vouw in een vouwfilter (brug K13 van de
v2-regelset) en een keten met ten hoogste één deelselectie.

## Doel

- De pin naar uchar main op het moment van de start (minstens `5d8a06de`), met wat de distributie daarvoor moet
  bijwerken (replay-pin, lijsten van de servicegates, de TOKA-manifestuitzonderingen).
- Een subcommando `bin/regelspraak tokens <out.json> <source.rgs...>` dat de uitvoer van `Resolver.tokens` schrijft,
  zoals `ir` het IR schrijft: de bestanden in de volgorde van de aanroep, de uitvoer ook bij ERRORs en syntaxfouten,
  exit 0 als het bestand geschreven is. Usage en `README.md` noemen het. Geen service-endpoint in deze kaart.

## Acceptatie

- (a) Een test in de toolchain die `tokens` op een eigen klein model met twee bestanden draait en de vorm leest (een
  verwijzing over de bestandsgrens, een onopgeloste naam).
- (b) Meting: `bin/regelspraak tokens` op de v2-regelset herleving-WW van de werkbank (fundament en rules, alleen
  lezen) dekt alles, zonder `unresolved`; tijd en telling in de Result.
- (c) `make gates` groen.
