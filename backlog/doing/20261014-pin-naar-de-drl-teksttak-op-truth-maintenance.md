# pin-naar-de-drl-teksttak-op-truth-maintenance: de pin naar uchar `a63ef307`, waar beide Drools-takken één analyse weergeven

*Vastgelegd op 2026-10-14, uit het `Result:` van de uchar-kaart `20261008-drl-teksttak-op-truth-maintenance` ("Voor
de distributie (bij de pin-bump)") en PLAN §9 van uchar. Regelnummers op toolchain `7e9e502` (submodule op uchar
`b536bbc9`).*

## Aanleiding

Op uchar main `a63ef307` (2026-10-14) is de kaart `drl-teksttak-op-truth-maintenance` geland. De Drools-backend heeft
daarna twee takken van één ontwerp. Eén analyse (`transpiler/tms/TmsTranspiler`) maakt per regel een plan, en twee
weergaven bouwen er een regelbank van: `PatternDslWeergave` voor `Branch.EXEC_MODEL` en `DrlWeergave` voor
`Branch.DRL_TEXT`. Beide leiden af met truth maintenance. Wat de analyse weigert, weigert zij voor beide takken in één
zin; de DRL-weergave weigert daarnaast alleen wat DRL-tekst niet kan uitdrukken (label `drl:`). `DrlTranspiler` en
`Stratificatie` zijn weg. Beide takken spelen 255 goldens.

Voor de distributie verandert de API van `DroolsBackend.Build`: het record draagt nu altijd de analyse
(`execModelTranspiler`, nooit null) en op de DRL-teksttak de tekst als veld `drl`. `drlTranspiler()` bestaat niet meer.
Na een pinbump zonder deze kaart compileren de CLI en de playground niet.

## Wat er gebeurt

1. **De pin** naar uchar main `a63ef307`. Daartussen zitten ook de TOKA-vrijgave, de toka-zone zonder "tijdelijk" en
   SUBTYPING §11. Ga na of iets daarvan de toolchain raakt, bijvoorbeeld de pariteit van `examples/toka/*.rgs` met
   uchars `toka/casus/*.rgs`.
2. **`RegelSpraakCli.java:156` en `Playground.java:162`**: `.drlTranspiler().drl()` wordt `.drl()`.
3. **`CaseSessie.java:62–65`**: de bewaking `build.execModelTranspiler() == null` onderscheidt de takken niet meer,
   want de analyse staat nu in elke build. Zoek uit wat de bewaking moet bewaken en doe dat op de nieuwe API.
4. **Een DRL-tekst die niet compileert** gooit sinds uchar `eac6fa5d` een `IllegalStateException` ("een fout van de
   weergave"), geen `Excluded`. Nu geeft de CLI daarbij een stacktrace met exit 1 en de playground HTTP 500. De
   playground meldt haar als `drlFout`, zoals de andere weigeringen in `Playground.analyseer`. De CLI meldt de zin van
   uchar met een exitcode ongelijk aan nul, zonder stacktrace.
5. **`Namen.getter`/`setter`** volgen de beangenerator (`Namen.lidnamen`). Controleer `RolSchrijver`, `Verbalisator`,
   `CaseSessie`, `ScenarioRunner` en `VerzoekMapper`.
6. **De replay.** uchars gate 12 heeft één lijst niet-spelers en één pin `EXPECTED_PLAYED` (255); `EXPECTED_DRL_PLAYED`
   en `DRL_CHANNEL` vervallen. `scripts/check-dienst-replay.sh` volgt: de set van gate 12, de pin
   `EXPECTED_REPLAY_PLAYED` en de lijst `REPLAY_UITGESLOTEN`. Nieuw in uchars corpus zijn vijf goldens
   (`jutterswet-verdeling-dubbelbezet`, `jutterswet-uniciteit-drie-bronnen`, `jutterswet-uitsluiting-gedateerd`,
   `jutterswet-aantalfilter-gehele-jaar` en `jutterswet-aantalfilter-gehele-jaar-creatieguard`, de laatste twee zonder
   `bron.rgs`).
7. **Verder alleen wat de pin breekt**: compileerfouten, verouderde weigerzinnen en tekst die de pin onwaar maakt, de
   vloeren van `tools/check-skips.sh`.

**Gedrag dat met de pin verandert** (uchar-kaart, "Gedragswijzigingen buiten de meting" en "Dekkingsverlies"):
- `Namen.getter`/`setter` geven de namen van de generator: een lid als "İş" wordt gelezen en gezet met `getIş` en
  `setIş`, waar vroeger `getİş` werd gezocht.
- `CaseRunner` zoekt bij een case die een lid in een andere hoofdletter spelt de gedeclareerde naam op, ook in het
  scenariorapport (`werkelijk`).
- `renderIncs` sorteert de inconsistenties: de tekst van een mismatchzin en van `VerwachtingResultaat.werkelijk`
  verandert, geen feit en geen status.
- Twee schrijvers die het eens zijn met een andere schaal (5 en 5.00): de lezer neemt de waarde van de regel die in
  het document eerst komt.
- Een DRL-tekst die de compiler weigert, is een `IllegalStateException`, geen `Excluded`.
- Het DRL-paneel van de playground toont nu ook documenten met defaults.
- Wat alleen de oude DRL-teksttak speelde en de analyse weigert, weigert nu ook de DRL-teksttak (bijvoorbeeld een vouw
  of deling in een ledeninitialisatie van een creatie).

## Acceptatie

- (a) De pin staat op uchar `a63ef307`; `make check-pin` groen.
- (b) Geen `drlTranspiler()` meer in de eigen code; de bewaking in `CaseSessie` bewaakt op de nieuwe API wat ze moet
  bewaken, met de reden in het Result.
- (c) Een DRL-tekst die niet compileert, geeft in de playground `drlFout` met uchars zin en in de CLI uchars zin met
  een exitcode ongelijk aan nul, zonder stacktrace.
- (d) `make gates` groen. De pin en de lijst van `check-dienst-replay` volgen de nieuwe gate 12; elke golden die van
  kanaal wisselt, staat met reden in het script en in het Result. Een vloer die zakt, zakt in dezelfde commit als de
  verwijdering, met de reden.
- (e) Elke testwijziging staat in een eigen commit, met haar reden uit het Result van de uchar-kaart.

## Omvang

Klein. May touch: de submodule `uchar` (alleen de pin), `toolchain/` (CLI, `CaseSessie`, `UcharBouw`), `service/`
(`Playground`, de playground-pagina), `scripts/check-dienst-replay.sh`, tests alleen waar de nieuwe API of een
gedragswijziging van de uchar-kaart dat afdwingt, `README.md`, `Makefile` en `docs/ENGINE.md` voor tekst die de pin
onwaar maakt, kaartverplaatsing.

## Raakvlakken

uchar-kaarten in `backlog/new/` na de landing: `beanlidnamen-zonder-java-identifier`, `caselezer-zoals-ref`,
`frontend-heelperiode-in-aantalfilter`, `weergaven-als-getuige`, `clingo-default-literaalschaal` en
`uitvoerdernotities-na-truth-maintenance`. De eigen kaarten in `backlog/new/` blijven buiten deze kaart.
