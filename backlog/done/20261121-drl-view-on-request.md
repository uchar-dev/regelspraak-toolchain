# drl-view-on-request: de playground maakt de DRL-weergave alleen op verzoek

*Opgesteld door een werkbanksessie op 2026-11-21, op verzoek van de eigenaar. Meting:
`regelspraak-werkbank/werknotities/20261121-traagheid/verslag.md` (met harnas en logs in `meet/`; werknotities zijn
niet in git, de bevindingen die de kaart nodig heeft staan hieronder).*

## Aanleiding

Een scenariowissel op de eval-pagina van de werkbank wacht op één aanvraag, `POST /playground/analyse?via=service`.
Op de regelset v2/herleving-ww (regelspraak-rules 6d39e7d, bron s01 275.605 tekens) kost die 6,0 s; op f2217a9 was
dat 1,45 s. Fasen van `Playground.analyze`, mediaan in ms, toolchain 8341ae0e (Drools 8.44.0.Final):

| Fase | f2217a9 | 6d39e7d |
|---|---:|---:|
| `Resolver.parse` (stap 1) | 126 | 659 |
| `ScenarioResolver.resolve`, met een tweede parse (stap 2) | 189 | 720 |
| exec-model-tak | 44 | 71 |
| DRL-teksttak (`UcharBuild.assemble(model, DRL_TEXT)`) | 899 | 4.422 |
| waarvan `KieHelper.build` van de DRL-tekst | 884 | 4.280 |
| totaal | 1.445 | 6.045 |

De DRL-teksttak levert alleen de velden `drl` en `drlRules`; de code noemt hem "a view of the DRL-text branch,
never the verdict". De eval-pagina leest die velden niet (alleen de regelsetpagina van de werkbank doet dat,
`static/regelset.js`). Voor elk scenario van één regelset zijn ze byte-gelijk.

De compilatie groeit sneller dan de bron: 380 → 744 DRL-`function`s, en Drools 8.44 maakt van elke functie een
statische import in elke gegenereerde klasse (`addStaticImport`; in het JFR-profiel is ~38 % van de samples
import-resolutie in ECJ). Dat ligt aan de vorm van de DRL-weergave en is een vraag voor uchar, niet voor deze kaart.

Daarnaast parseert `Playground.analyze` de bron twee keer: stap 1 met `Resolver.parse(source)` voor de kolommen en
verwachte tokens, stap 2 opnieuw in `ScenarioResolver.resolve(source)` (via `ScenarioResolver.read`, package-private)
of `Resolver.resolve(source)`.

## Goal

Een aanroeper van `/playground/analyse` kan de DRL-teksttak overslaan; het
antwoord heeft dan geen `drl` en `drlRules` (en geen `drlError`). Zonder die keuze blijft het antwoord zoals nu,
zodat de regelsetpagina niets hoeft te veranderen. De naam en vorm (query-parameter of veld) kiest de bouwer,
Engels en in de OpenAPI-beschrijving, zoals de andere namen van het koppelvlak. "Compile-per-request, no shared
state" (`ServiceHttp`) blijft zoals het is: er komt geen cache.

## Vervolg, niet deze kaart: één parse per analyse

Stap 2 zou de parse van stap 1 kunnen lezen in plaats van de bron opnieuw te parseren. uchar biedt daar geen
publieke API voor (`ScenarioResolver.read` is package-private, `Resolver.resolve(String)` parseert zelf), dus dat is
eerst een kaart in uchar. Besluit eigenaar 2026-11-21: wachten. De parse-groei komt bijna geheel uit één regel van
v2 ("herleving afhankelijk van ontbrekende invoer art. 21 lid 1", hww-05), een handmatige afvang van lege invoer die
verdwijnt zodra uchar de strikte lezing van leeg heeft (uchar-kaart `empty-closures-inventory` en vervolg). Daarna
opnieuw meten en dan pas beslissen.

## Acceptance

- Op de bron s01 van v2/herleving-ww (6d39e7d of later, met de scenario's uit de eval-dataset zoals de werkbank ze
  stuurt): de analyse zonder DRL-weergave is gemeten vóór en na, in de Result; de velden behalve `drl`, `drlRules`
  en `drlError` zijn byte-gelijk aan die van de volle analyse.
- Een test die de keuze vastlegt: zonder keuze `drl` en `drlRules` aanwezig, met keuze afwezig; eerst rood getoond.
- README/`docs/ENGINE.md` als huidige stand; `make verify` en de gates groen.
- Een WERKBANK.md voor de werkbank: de naam van de keuze, zodat de eval-pagina (`static/api.js`, `static/eval.js`)
  hem kan zetten. Die wijziging in de werkbank is niet deze kaart.

May touch: `service/` (`Playground`, `ServiceHttp`, OpenAPI), tests, README, `docs/ENGINE.md`, kaartverplaatsing.

Out of scope: één parse per analyse (zie Vervolg); de DRL-weergave of andere takken bewaren tussen aanvragen;
minder `function`-declaraties in de DRL-weergave en de parsesnelheid van de telconstructie "het aantal … die …
voldoen" (beide uchar); de werkbank.

## Omvang

Klein.

## Result

*Kaartsessie, 2026-11-21.*

- **De keuze**: de query-parameter `drl` op `POST /playground/analyse`. `?drl=false` laat de DRL-teksttak ongebouwd:
  het antwoord heeft geen `drl`, geen `drlRules` en geen `drlError`. Zonder de parameter, met `drl=true` of met een
  kale `drl` is het antwoord zoals het was. Een andere waarde is een 400 `MALFORMED` op `field` `drl` ("drl kent
  alleen de waarden 'true' en 'false', niet '…'"), zoals `via` een onbekende waarde weigert: een verschreven `false`
  gaat zo niet door als de volle analyse. De parameter staat naast `via` en `vocabulary`.
- **Code** (`2fe581abc`): `Playground.analyze(source, vocabulary, service, locale, drlView)`; de vier bestaande vormen
  geven `true` door. `ServiceHttp.processPlaygroundAnalysis` leest de parameter. Er is geen cache bijgekomen: elke
  aanvraag bouwt nog wat zij antwoordt.
- **Tests**: vier nieuwe in `PlaygroundViaServiceTest` (`service` 585 → 589 testgevallen). Zonder keuze, met
  `drl=true` en met een kale `drl` staan `drl` en `drlRules` er, met en zonder `via=service`; met `drl=false`
  ontbreken ze en is elke andere sleutel op de bytes gelijk aan die van de volle analyse, op beide routes; bij een
  model dat de DRL-teksttak weigert blijft `drlError` weg en staat `refusal` er; `drl=nee` is de 400. Eerst rood
  getoond op de onveranderde code: 589 tests, 3 failures, de drie die het nieuwe gedrag vragen (bij `drl=false`
  stonden `drl` en `drlRules` er nog; `drl=nee` gaf een 200). De vierde legt de ongewijzigde standaard vast en
  slaagde al. Daarna groen: 208/589/62.
- **Meting.** Bron s01 zoals de werkbank haar stuurt (de 33 bronnen van `v2/herleving-ww` op regelspraak-rules
  `6d39e7d` met het scenariodocument, 275.605 tekens), tegen een dienst gestart als `bin/regelspraak playground
  <descriptor> <bronnen> --detail`; per aanvraag 3 opwarmrondes en 7 gemeten, mediaan (kleinste–grootste), GraalVM
  25.0.3, de machine verder stil:

  | Aanvraag | vóór (main `6bf440b27`) | na (`2fe581abc`) | antwoord |
  |---|---:|---:|---:|
  | `?via=service`, de aanvraag van de eval-pagina nu | 6,27 s (5,87–6,36) | 5,84 s (5,45–5,95) | 4.475.259 bytes |
  | `?via=service&drl=false` | (de parameter bestaat niet) | 1,66 s (1,63–1,72) | 2.412.561 bytes |
  | zonder query | 5,95 s (5,76–6,04) | 5,90 s (5,50–6,28) | 4.475.114 bytes |
  | `?drl=false` | (de parameter bestaat niet) | 1,55 s (1,54–1,59) | 2.412.416 bytes |

  Een scenariowissel gaat daarmee van ongeveer 6,1 s naar 1,7 s. Een eerdere reeks van de volle aanvraag op main gaf
  6,06 s (5,98–6,24); het verschil in de eerste rij is spreiding tussen reeksen, want de volle analyse is vóór en
  na als geheel byte-gelijk (`cmp` op beide routes). Per veld, op de bytes die de dienst schreef: `parse`,
  `resolver`, `vocabularies`, `ir`, `executable`, `coverage`, `generated`, `graph`, `graphJson` en `scenario` zijn
  met `drl=false` byte-gelijk aan die van de volle analyse en staan in dezelfde volgorde, met en zonder
  `via=service`; `drl` (1.757.920 bytes) en `drlRules` (304.759) ontbreken. Het model van s01 heeft geen `drlError`;
  dat die sleutel wegblijft, legt de test vast.
- **De OpenAPI-beschrijving: niet gedaan zoals de Goal het zegt.** De Goal vraagt de naam "in de
  OpenAPI-beschrijving, zoals de andere namen van het koppelvlak". Het OpenAPI-document beschrijft de entrypoints van
  het gediende model en `/info`; geen route van de playground staat erin, ook `via` en `vocabulary` van deze route
  niet. `serve` geeft hetzelfde document en bindt de route niet, en `bin/regelspraak openapi` schrijft dezelfde bytes
  als de route. De parameter staat daarom waar de andere namen van deze route staan: `docs/ENGINE.md`, en die tekst
  zegt dat het OpenAPI-document hem niet draagt. De routes van de playground in het document opnemen is een eigen
  kaart, als de eigenaar dat wil.
- **Tekst**: README, één regel in de lijst van contractwijzigingen; `docs/ENGINE.md`, een punt `?drl=false` na
  `drlRules` en een bijzin in de beschrijving van het antwoord.
- **Gates**: `make gates` groen op de onveranderde main vóór het werk (`6bf440b27`, 7 stappen, 208/585/62) en op
  `2fe581abc` (7 stappen in 11m11s, 208/589/62; 374 goldens en 1057 cases in de replay; H2, H3 en H4 PASS). De commit
  die deze kaart naar `done/` verplaatst, raakt alleen dit bestand.
- **Voor de werkbank**: `werk/drl-view-on-request/WERKBANK.md` (de naam, de meting, en wat in `static/api.js`,
  `static/eval.js` en `static/evaldata.js` ligt). Het harnas en de reeksen staan in
  `werk/drl-view-on-request/meting/`, de logs van rood, groen en de gates in `werk/drl-view-on-request/logs/`.
- **Gezien en gelaten**:
  - Eén parse per analyse (het Vervolg van de kaart): van de 1,66 s is naar de fasetabel hierboven ongeveer 1,4 s
    parse en resolve.
  - De limiet van 15 s (`ServiceHttp.PLAYGROUND_TIMEOUT_MS`). Twee meetpogingen vielen samen met een gate-set van een
    andere sessie op dezelfde machine (load 30 tot 60); de volle analyse van v2 haalde de limiet toen niet en de
    playground antwoordde 503, terwijl de analyse zonder DRL-weergave doorkwam (4 tot 15 s). Met 6 s op een stille
    machine is de ruimte tot de limiet klein voor wie de volle analyse vraagt. Die reeksen staan als verstoord in
    `meting/` en tellen niet mee in de tabel.
  - De pagina van de playground zelf (`playground.html`) vraagt de volle analyse en is niet veranderd.
