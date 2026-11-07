# scenario-runner-fixes-language: ScenarioRunner zet de taal van uchars meldingen vast

*Gevonden bij de kaart `uchar-text-in-uchar-language` (Result, "Bestaande volgorde-afhankelijkheid"); door de eigenaar
gevraagd, 2026-11-07.*

## Aanleiding

`MessageLanguage.apply` zet de taal van uchars meldingen voor het proces vast. Elke ingang van de toolchain roept haar
als eerste aan (`RegelSpraakCli`, `ScenarioRunnerCli`, `ServiceMain`), en `UcharBuild` doet dat in een static
initializer voor code die er zonder ingang komt. `ScenarioRunner` komt niet langs `UcharBuild`: hij laat uchars
`CaseRunner.report` de cases spelen. Is `ScenarioRunner` het eerste wat een JVM van de toolchain gebruikt, dan rendert
uchars Drools-component zonder gekozen taal, en dat is Engels.

Gevolg, gemeten op `7454b1426`: `mvn -o -q -pl toolchain -am test -Dtest=ScenarioMutationRunTest` faalt 11 van 11
(`case1-off-by-one.json: attribute afstand tot bestemming: expected 201, actual 200`, waar de toets `verwacht` en
`werkelijk` eist). In de volle suite is de toets groen, omdat een eerdere toets de taal al vastzette. De suite hangt
dus af van de volgorde van de testklassen, en een afnemer die `ScenarioRunner` als bibliotheek aanroept, krijgt uchars
oordelen in het Engels tussen Nederlandse tekst.

## Goal

`ScenarioRunner` zet de taal vast zoals `UcharBuild`: `static { MessageLanguage.apply(null); }`. De javadoc van
`MessageLanguage` noemt beide klassen.

## Acceptance

- `ScenarioMutationRunTest` alleen gedraaid: 11 van 11 groen.
- `make gates` groen.
