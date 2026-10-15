# uitleg-herkomst-uit-ir: de uitleg leest de tabel van herkomst uit het model dat hij uitlegt

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `87fd9b9` en uchar `7fbab5ec`.*

## Aanleiding

De kaart `uitleg-gegenereerde-regel-in-vocabulaire` (done, toolchain `a53d0de`) laat de uitleg van een regel die
uchar schreef (herkomstvorm `OPGAVE_BOVEN_BEREKENING` volgens `RegelSpraakModel.gegenereerdUit`) de operand in het
vocabulaire noemen. uchars IR-lader liet de tabel van herkomst toen leeg, omdat de emitter haar niet schreef. Daarom
kreeg de `Verbalisator` een vierde constructorparameter `herkomst` (`Verbalisator.java`, r. 77–98), en geeft de
`ScenarioRunner` daar het model van de frontend mee (`irModel`, r. 93, doorgegeven op r. 142, 166 en 191). Het
Result van die kaart liet open: een dienst die uit een IR-document start, heeft de tabel niet.

uchar `7fbab5ec` (kaart `herkomst-op-de-draad`, IR-SPEC ch. 2.13, WF-121) schrijft de herkomst als veld `origin` in
het IR-document, en de IR-lader leest haar terug in `RegelSpraakModel.gegenereerd` (uchar
`RegelSpraakModel.java`, r. 73–81: "so a model read from the wire has the table the frontend's had"; `IrLoader`,
r. 719–). De toolchain pint die uchar sinds `87fd9b9`. Daarmee heeft `build.model()` in de `ScenarioRunner` de tabel
zelf, en ook het model van `BeslisserKern.bouwVanDocument` (`IrLoader.loadAll`, r. 266), dat de dienst met de
drie-parameterconstructor uitlegt (r. 614).

## Wat er gebeurt

De `ScenarioRunner` geeft geen apart model van herkomst meer mee: de Verbalisator leest de tabel in het model dat hij
uitlegt. De vierde constructorparameter en het veld `herkomst` van de Verbalisator verdwijnen; de bestaande tests
gebruiken alleen de constructors met twee en drie parameters. Waar de klassedoc, het commentaar van de
`ScenarioRunner` en `README.md` (Vocabularies, r. 189–193) zeggen of aannemen dat de wire de tabel niet draagt,
worden ze bijgewerkt.

## Acceptatie

- (a) `Verbalisator` heeft geen veld `herkomst` en geen constructor met vier parameters; `doorUcharGeschreven` leest
  `model.gegenereerdUit`. `ScenarioRunner.uitleg` neemt geen model van herkomst meer.
- (b) Een nieuwe testklasse (bestaande tests ongewijzigd): op het model dat uchars IR-lader uit het geëmitteerde
  IR-document leest (de route van `BeslisserKern.bouwVanDocument`) noemt de uitleg in `cgm` de operand van
  `afwijking opgave X` in het vocabulaire, gelijk aan de uitleg op het model van de frontend. De test is eerst rood
  getoond, met de reden.
- (c) De klassedoc van de Verbalisator, het commentaar in de `ScenarioRunner` en `README.md` zeggen niet meer dat de
  wire de tabel niet draagt.
- (d) `make gates` groen.

## Omvang

Klein. May touch: `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbalisator.java`,
`…/engine/ScenarioRunner.java`, een nieuwe testklasse, `README.md`, kaartverplaatsing. Niet `uchar/`. Bestaande
testbestanden niet.

## Raakvlakken

- `20261015-uitleg-gegenereerde-regel-in-vocabulaire` (done): deze kaart sluit haar open punt over de dienst die uit
  een IR-document start.
