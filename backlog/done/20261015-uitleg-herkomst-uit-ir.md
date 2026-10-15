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

## Result (2026-10-15)

### Wat de kaart opleverde

Regelnummers van de toolchain op `21d99a5`.

- **Verbalisator.** Het veld `herkomst` en de constructor met vier parameters zijn weg; de constructor met drie
  parameters zet de velden zelf (r. 83). `doorUcharGeschreven` leest `model.gegenereerdUit` (r. 148), dus de tabel
  van het model dat hij uitlegt. Buiten de ScenarioRunner gebruikte niemand de vierde parameter: `BeslisserKern`
  (r. 614) en de tests gebruiken de constructors met twee en drie parameters.
- **ScenarioRunner.** `uitleg` neemt geen model van herkomst meer (r. 165) en legt uit op `build.model()` met zijn
  eigen tabel (r. 189–190). `irModel` blijft voor de scope en de emitter (r. 93).
- **Tekst.** De Javadoc van de constructor die zei dat de wire de tabel niet draagt, is met de constructor weg. De
  klassedoc ("Vocabulaire", r. 53–55) zegt nu dat de tabel die van het uitgelegde model is, en dat ook een model van
  uchars IR-lader haar draagt (`origin`, IR-SPEC ch. 2.13). Het commentaar van `ScenarioRunner.uitleg` (r. 160–163)
  zegt hetzelfde. `README.md` (Vocabularies, r. 192–194): het IR-document draagt de tabel, dus een dienst die uit een
  IR-document start legt deze regels op dezelfde manier uit.

### Testwijzigingen

- Nieuw: `toolchain/…/engine/UitlegHerkomstUitIrTest` (1 test). Het document is dat van
  `UitlegGegenereerdeRegelInVocabulaireTest` (overgenomen: daar is het een private veld, en dat bestand blijft
  ongewijzigd). De test compileert het met `RegelSpraakEngine`, laat uchars emitter het IR-document schrijven
  (`UcharBouw.document`) en leest dat met `IrLoader.loadAll`, zoals `BeslisserKern.bouwVanDocument` doet. Op beans
  V1 (met een afwijkende opgave) en V2 (zonder) eist hij in `cgm` vier zinnen, op het model van de frontend en op het
  geladen model dezelfde, met de operand van `afwijking opgave meldingsdatum` als "zijn opgegeven Datum melding
  vondst".
- Rood getoond tegen de oude stand: toolchain-code van `87fd9b9` met uchar op `fe8901fb`, de pin vóór `87fd9b9`
  (dat is precies `567b6e7`). De bewering op het model van de frontend slaagt; die op het geladen model faalt, met
  in `cgm` de operand canoniek: "V2 heeft geen afwijkende opgave Datum melding vondst, want niet geldt: zijn
  opgegeven meldingsdatum (onbekend) gevuld is". De oude IR-lader las geen `origin`.
- Tegen de toolchain-code van `87fd9b9` met uchar `7fbab5ec` is de test al groen: de constructor met drie parameters
  nam daar het uitgelegde model zelf als herkomst, en het geladen model draagt de tabel sinds de pin. De verwijdering
  van de vierde parameter verandert dus geen uitkomst; de test pint de route van de dienst die uit een IR-document
  start, die het open punt van `uitleg-gegenereerde-regel-in-vocabulaire` was. Dat de ScenarioRunner zonder het
  model van de frontend de operand in het vocabulaire noemt, houdt de bestaande
  `UitlegGegenereerdeRegelInVocabulaireTest` vast (`cgm` en `handboek` via `ScenarioRunner.runMetUitleg`), die
  ongewijzigd groen is.
- Geen bestaand testbestand veranderd.

### Poorten

`make gates` op `21d99a5`: groen, 7 stappen in 8 min 38 s.
- pin: uchar op `7fbab5ec`;
- reactor (5 min 21 s): 1593 tests in uchars backend, 140 in toolchain (was 139, plus de nieuwe), 269 in service en
  47 in service-quarkus;
- tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, 4 getuigenissen;
- replay: 225 goldens met 599 cases (pin 225);
- manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
- image `beslisservice:dev`;
- kind-smoke: H2, H3 en H4 PASS.

### Open

Geen.

### Commits

`1659e41` kaart · `933fc08` claim · `21d99a5` de Verbalisator, de ScenarioRunner, de nieuwe testklasse en de README ·
deze Result en de verplaatsing naar `done/`.
