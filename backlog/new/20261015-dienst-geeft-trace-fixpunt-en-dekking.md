# dienst-geeft-trace-fixpunt-en-dekking: `?trace`, `?fixpunt` en `?dekking` op de beslisroute, achter `DETAIL`

*Vastgelegd op 2026-10-15, kaart C van de reeks "beslisservice via dienst" (besluit van de maintainer, werknotitie
`werkbank/werknotities/20261015-beslisservice-en-intake/beslisservice-verkenning.md` §1, §2 en §5, en het advies
daarnaast). Regelnummers op toolchain `87fd9b9` (submodule op uchar `7fbab5ec`).*

## Aanleiding

De werkbank laat scenario's straks rekenen door de dienstcode in plaats van de playground. Wat zij van een scenario
leest, levert de beslisroute vandaag niet: de vuringen (`trace`), de dekking per vorm en het fixpunt. Het antwoord van
`POST /<pad>` draagt alleen de `uitvoer`-selectors; het fixpunt wordt per verzoek al gelezen en op de fouten na
weggegooid (`Uitvoerder.java:88-89`). uchar heeft sinds `fe8901fb` een publieke waarnemer op het vuren
(`DroolsBackend.Waarnemer`, `vuur(build, sessie, waarnemer)`) en de dekking uit de gevuurde vormen
(`DroolsBackend.dekking(build, vormen)`).

## Wat er gebeurt

1. **Drie queryvlaggen** op `POST /<pad>`, naast `uitleg`, `vocabulaire` en `invoervocabulaire`, gelezen zoals
   `uitleg` (`true`, hoofdletterongevoelig, of de kale naam):
   - `trace`: `trace: [{regel, labels, aantal}]`, uit uchars `Waarnemer` op de sessie van het verzoek; `labels` zijn
     ids van het verzoek.
   - `dekking`: `dekking: [{regel, soort}]`, uit `DroolsBackend.dekking(build, waarnemer.vormen())` over de build van
     het ingangspunt.
   - `fixpunt`: `fixpunt: {objecten, gemaakteObjecten, koppelingen, fouten, inconsistenties}` in de vorm van
     `ScenarioGegevens.fixpunt` van de playground, met de instanties van het verzoek als invoer. Een object heet naar
     zijn id, een gemaakt object `#<n>`; typen heten naar hun naam in het gekozen vocabulaire. Er komt geen
     klassenaam van een bean in het contract. De playground en de dienst renderen het fixpunt met dezelfde code.
   - Met een vlag krijgt ook een 422 (`EVALUATIEFOUT`) de gevraagde velden, zodat de fouten als gegevens naast de
     waarden staan.
   - Namen volgen het gekozen vocabulaire zoals in de playground.
2. **Startinstelling `DETAIL`** (`DETAIL=true` in de omgeving of `--detail`; Quarkus: `DETAIL`), standaard uit, naar
   het voorbeeld van `PLAYGROUND`. Zonder `DETAIL` geeft een detailvlag 400 met de naam van de vlag als `veld`; ze
   wordt niet stil genegeerd. `DETAIL` hoort niet bij het containercontract (`docker/entrypoint.sh`, Helm).
3. **Beide adapters**: `DienstHttp` en `BeslisResource` geven hetzelfde, omdat de kern het antwoord bouwt en de
   instelling bewaakt. Kaart D (playground `via=dienst`) roept de kernmethode aan, niet HTTP.
4. **OpenAPI** (`OpenApiEmitter`): de vlaggen als queryparameters, de nieuwe velden in `Antwoord` en
   `Evaluatiefout`, en `uitleg`, dat nu in het `Antwoord`-schema ontbreekt.

Zonder vlag blijft elk antwoord bytegelijk, ook op een dienst die met `DETAIL` gestart is.

## Acceptatie

- (a) Met `?trace=true&dekking=true&fixpunt=true` op een dienst met `DETAIL` staan de drie velden in het antwoord,
  in de vorm hierboven; de labels van de trace zijn ids van het verzoek.
- (b) Een 422 met `?fixpunt=true` draagt het fixpunt met de fouten als gegevens.
- (c) Zonder `DETAIL` geeft elke detailvlag een 400 met haar naam als `veld`.
- (d) Zonder vlag is het antwoord bytegelijk aan dat van een dienst zonder `DETAIL`, voor een 200 en een 422.
- (e) JDK en Quarkus geven met de vlaggen hetzelfde lichaam, voor een 200, een 422 en de 400 zonder `DETAIL`.
- (f) Een concurrencytoets met `trace` laat zien dat vuringen niet tussen sessies lekken: elk antwoord gelijk aan zijn
  seriële basislijn, en de labels van elke trace zijn ids van het eigen verzoek.
- (g) De playground geeft hetzelfde fixpunt als vóór de kaart (`PlaygroundScenarioGegevensTest` ongewijzigd groen).
- (h) Bestaande tests ongewijzigd; een golden of test die onvermijdelijk verandert, gaat in een eigen commit met
  "testpatch" in de titel, met reden in het Result.
- (i) `make gates` groen.

## Omvang

Middel. May touch: `service/` (`BeslisserKern`, `Uitvoerder`, `VerzoekOpties`, `KernConfig`, `AntwoordBouwer`,
`Evaluatiefout`, `OpenApiEmitter`, een nieuwe klasse voor het fixpunt, `http/DienstHttp`, `http/DienstMain`,
`http/ScenarioGegevens`, `http/Playground`), `service-quarkus/` (`BeslisResource`, `KernProducer`), nieuwe
testklassen, `docs/ENGINE.md`, `README.md`, `Makefile`- en `bin/regelspraak`-hulptekst, kaartverplaatsing. Niet
`uchar/`. Niet: `GET /model` en een scenarioroute (vervallen in het besluit).

## Raakvlakken

Kaart D (playground `via=dienst`) bouwt hierop. De kaart `uitleg-herkomst-uit-ir` loopt tegelijk in een eigen worktree
(Verbalisator, ScenarioRunner); die bestanden raakt deze kaart niet.
