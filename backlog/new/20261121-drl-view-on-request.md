# drl-view-on-request: de playground maakt de DRL-weergave alleen op verzoek, en parseert de bron één keer

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

1. **De DRL-weergave op verzoek.** Een aanroeper van `/playground/analyse` kan de DRL-teksttak overslaan; het
   antwoord heeft dan geen `drl` en `drlRules` (en geen `drlError`). Zonder die keuze blijft het antwoord zoals nu,
   zodat de regelsetpagina niets hoeft te veranderen. De naam en vorm (query-parameter of veld) kiest de bouwer,
   Engels en in de OpenAPI-beschrijving, zoals de andere namen van het koppelvlak. "Compile-per-request, no shared
   state" (`ServiceHttp`) blijft zoals het is: er komt geen cache.
2. **Eén parse per analyse.** Stap 2 leest de parse van stap 1 in plaats van de bron opnieuw te parseren. uchar biedt
   daar nu geen publieke API voor (`ScenarioResolver.read` is package-private, `Resolver.resolve(String)` parseert
   zelf). Volgens de regels van deze repository is dat een kaart in uchar die eerst landt en hier als pin aankomt; de
   bouwer stelt die API voor (welke methode, welke parse-vlag: de scenarioroute parseert met `testDocument = true`
   en soms een tweede keer) en schrijft de uchar-kaart. Zonder geland uchar-werk is dit deel niet af, en zegt de
   Result wat er ontbreekt.

## Acceptance

- Op de bron s01 van v2/herleving-ww (6d39e7d of later, met de scenario's uit de eval-dataset zoals de werkbank ze
  stuurt): de analyse zonder DRL-weergave is gemeten vóór en na, in de Result; de velden behalve `drl`, `drlRules`
  en `drlError` zijn byte-gelijk aan die van de volle analyse.
- Een test die de keuze vastlegt: zonder keuze `drl` en `drlRules` aanwezig, met keuze afwezig; eerst rood getoond.
- Deel 2: de tijd van stap 1+2 vóór en na, en een test of bestaande dekking die aantoont dat de diagnostiek
  (`parse.errors` met kolom en verwachte tokens, `resolver.errors/warnings`) gelijk blijft, ook voor een bron met een
  syntaxfout en voor een testdocument met een dimensielabel met 'en'.
- README/`docs/ENGINE.md` als huidige stand; `make verify` en de gates groen.
- Een WERKBANK.md voor de werkbank: de naam van de keuze, zodat de eval-pagina (`static/api.js`, `static/eval.js`)
  hem kan zetten. Die wijziging in de werkbank is niet deze kaart.

May touch: `service/` (`Playground`, `ServiceHttp`, OpenAPI), tests, README, `docs/ENGINE.md`, kaartverplaatsing;
een kaart in uchar voor deel 2.

Out of scope: de DRL-weergave of andere takken bewaren tussen aanvragen; minder `function`-declaraties in de
DRL-weergave en de parsesnelheid van de telconstructie "het aantal … die … voldoen" (beide uchar); de werkbank.

## Omvang

Klein voor deel 1; deel 2 hangt af van de uchar-API.
