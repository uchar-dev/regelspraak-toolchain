# pin-naming-fixes: de pin volgt uchars hernoemingen en de reviewpunten van de distributie

*Vastgelegd op 2026-10-19. Vervolg op `pin-message-codes`. Toolchain `957f7ec`, uchar-pin `476e0ff83` naar `ec88b2919`.*

## Aanleiding

Sinds de vorige pin hernoemde uchar de per spelling bevroren namen van het kernmodel, javadoc en `toString`, `category` naar
`kind` en de fabrieksmethoden `van(...)` naar `of(...)`; meldingen halen Nederlandse zinsneden uit de catalogus, en een deling
door een numerieke nul geeft in Drools leeg plus een §9.5-feit EF-005 in plaats van een evaluatiefout. Een eindreview van de
distributie vond daarnaast losse punten (bevinding 6 en 13).

## Wat er gebeurt

1. De pin naar `ec88b2919`; `make check-pin`.
2. De eigen modules volgen de nieuwe namen (compileren, plekken die de compiler niet ziet met de hand).
3. Reviewpunten, elk in een eigen commit: `van` wordt `of` (`Violation`, `Naming`, `EvaluationFault`, `FormIndex`); namen van
   uchar in `README.md`, `docs/` en `CLAUDE.md` die niet bestaan, rechtgezet; Engelse plaatshouders in de CLI-hulp en de
   README; de `.pyc`-bestanden uit de index en `__pycache__/` in `.gitignore`; `.git-blame-ignore-revs` aangevuld.
4. De EF-005-wijziging genoemd in de README ("Contract change").

## Acceptatie

- (a) `make gates` groen.
- (b) Een golden die verandert, krijgt een oorzaak in het Result.

## Omvang

May touch: alles in de worktree behalve `uchar/` (alleen de pin), `examples/toka/*.rgs` en `specification/`.
