# review2-fixes: meldingen, sidecar, gemiste namen, documentatie en mapnamen na de tweede eindreview

*Vastgelegd op 2026-10-19. Vervolg op `pin-naming-fixes`. Toolchain `efe75646b`, uchar-pin `ec88b2919` (blijft).*

## Aanleiding

Een tweede eindreview van het programma "Engelse namen" vond in de distributie meldingen, OpenAPI-tekst en een
deployscript die de oude JSON-sleutels noemen (H1, H2), gemiste Nederlandse identifiers (M3), documentatie die niet
bestaande namen of uchar-hashes noemt (M5, M7) en Nederlandse mapnamen (L11).

## Wat er gebeurt

1. H1: elke melding, exceptietekst en OpenAPI-beschrijving die een sleutel, queryparameter, veld of Helm-waarde noemt,
   noemt de naam die de code leest; een test die de oude namen uit "Contract change" in de string-literals zoekt, of de
   reden waarom dat niet kan.
2. H2: `scripts/as-of-date-vintage-check.sh` leest de sleutels van de loader en faalt als `parameterSets` ontbreekt;
   "Contract change" noemt de sidecar.
3. Gemiste namen: `Playground.analyseer`, `ValueCoder.decodeer`, `ParameterSetLoader.decodeerInterval` en `wpad`,
   `Verbaliser.evalueer`, `Declarations.van`, `Spellings.van` en wat een script verder vindt; één hernoemcommit, in
   `.git-blame-ignore-revs`.
4. Documentatie: `CLAUDE.md` (`FLOORS`, de kaart `pin-message-codes`), testcommentaar met oude uchar-testnamen,
   `bron.rgs` en "the uitleg" in de README, uchar-hashes vervangen door kaartnamen.
5. Mapnamen: `docs/historical/`, `examples/toka-reconciliation/`, de twin `vocabulaire-dubbelzinnig` Engels.

## Acceptatie

- (a) `make gates` groen.
- (b) Een golden die verandert, krijgt een oorzaak in het Result.

## Omvang

May touch: alles in de worktree behalve `uchar/`, `examples/toka/*.rgs` en `specification/`.
