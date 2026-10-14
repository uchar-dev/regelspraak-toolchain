# playground-toont-situatie-en-fixpunt: de situatie, de bronregel van een verwachting en het fixpunt per scenario, en de vocabulaires

*Vastgelegd op 2026-10-14, uit de inventaris in het Result van `pin-naar-de-drl-teksttak-op-truth-maintenance` ("De
playground na de pin"). Regelnummers op toolchain `dba5864` (submodule op uchar `f752a5c9`).*

## Aanleiding

De werkbank wil de eval-dataset herleving-ww per scenario tonen: het situatieblok, de vragen en de verwachte antwoorden.
`Playground.analyseer` (`service/…/http/Playground.java`) geeft daarvoor te weinig:

1. Het situatieblok staat niet als gegevens in het antwoord. `ScenarioRunner` maakt er de case van
   (`CaseEmitter.emit`, `ScenarioRunner.java:109`), maar de playground geeft alleen uchars rapport door.
2. Een verwachting draagt in `bron` uchars korte vorm ("attributeValue P1 herlevingsdatum", uchar `CaseRunner.bron`),
   niet de plaats in het scenario.
3. Het `Fixpunt` van uchar (`DroolsBackend.lees`) ontbreekt: de afgeleide waarden en kenmerken zonder verwachting, de
   gemaakte objecten, de koppelingen, de fouten en de inconsistenties. `CaseSessie.speel` zet de waarden terug met
   `zetAfgeleideWaarden` (r. 117), dat een staande evaluatiefout gooit; een case met zo'n fout eindigt via uchars rapport
   als "speelde de case niet uit", en de eigen sessie speelt haar dan niet (`ScenarioRunner.java:158`).
4. `playground.html` toont de dekking per scenario niet (zij staat al in de JSON) en heeft geen overzicht van de
   vocabulaires.

Twee pagina's lezen de JSON: `playground.html` hier en `werkbank/workbench.html` in de werkbank.

## Wat er gebeurt

1. **De situatie per scenario** als gegevens, uit uchars `ResolvedScenario`: de objecten met type en label, hun
   attribuut- en kenmerkwaarden, de rollen, de parameterwaarden, de peildatum.
2. **De plaats van een verwachting**: naast `bron` het regelnummer dat uchar geeft
   (`VerwachtingResultaat.positie` → `ResolvedScenario.verwachtingen()` → `Verwachting.line`) en de tekst van die
   bronregel. Waar uchars API de zin zelf niet aanwijst, komt er geen omweg; het Result zegt wat in uchar ontbreekt.
3. **Het fixpunt per case**: `CaseSessie` leest `DroolsBackend.lees(build, sessie, label)` vóór
   `zetAfgeleideWaarden` en zet alleen terug als er geen evaluatiefout staat (zoals `Uitvoerder.vuur` van de dienst).
   De JSON geeft per object de waarden en kenmerken met hun herkomst (invoer, afgeleid, creatie), de gemaakte objecten
   met creatie en onderwerp, de koppelingen, de fouten en de inconsistenties, elk deterministisch gesorteerd (uchar:
   de lijsten van `Fixpunt` hebben geen volgorde). Het fixpunt staat er alleen als de eigen sessie volgt wat uchars
   replay deed, met dezelfde toetsen als de uitleg; een case die uchars replay niet uitspeelde, speelt de sessie toch,
   zodat de fouten die de terugzetting gooide als gegevens verschijnen.
4. **Het vocabulaireoverzicht** uit `Naamgeving`: de gedeclareerde vocabulaires, het gekozen, en per gedeclareerd
   element de benaming in elk vocabulaire.
5. **`playground.html`** toont per scenario de situatie, de verwachtingen met hun bronregel, het fixpunt en de dekking
   als tabellen, en de vocabulaires in een eigen tab; leesbaar bij een document van 1 600 regels met tientallen
   objecten.

De JSON wordt alleen uitgebreid: bestaande sleutels houden hun vorm en betekenis. Namen staan in het gekozen
vocabulaire, met de canonieke naam ernaast waar die verschilt.

## Acceptatie

- (a) Op het eval-document s01 (PASS) en s04 (FAIL op `s04-v02`) van herleving-ww geeft `POST /playground/analyse`
  per scenario `situatie`, de regel van elke verwachting en een `fixpunt`; de bestaande sleutels zijn ongewijzigd.
- (b) Twee analyses van één document geven dezelfde JSON (de sortering is deterministisch).
- (c) Een case met een staande evaluatiefout geeft de fouten als gegevens in `fixpunt.fouten`.
- (d) Nieuw gedrag is getest in nieuwe testklassen; een bestaand testbestand verandert alleen als de uitbreiding het
  afdwingt, in een eigen commit met de reden in het Result.
- (e) `make gates` groen.

## Omvang

Middel. May touch: `toolchain/` (`CaseSessie`, `ScenarioRunner`, `ScenarioRunnerCli`, `Naamgeving`), `service/`
(`Playground`, een nieuwe klasse voor de scenariogegevens, `Evaluatiefout` alleen voor de zichtbaarheid van de soort,
`playground.html`), nieuwe testklassen, `docs/ENGINE.md` en `README.md` voor de playground, kaartverplaatsing. Niet
`uchar/`.

## Raakvlakken

De werkbank-pagina `werkbank/workbench.html` wordt elders aangepast en leest dit contract; zij is hier alleen gelezen.
