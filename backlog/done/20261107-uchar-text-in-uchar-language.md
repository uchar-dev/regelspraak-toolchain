# uchar-text-in-uchar-language: een regel met een tekst van uchar staat in uchars taal

*Gevraagd door de eigenaar, 2026-11-07, aan de hoofdsessie. Het besluit is voorlopig en volgt het advies van Fable
(2026-11-07).*

## Aanleiding

`bin/regelspraak --language en scenario …` drukt bij een mislukte verwachting

```
    verwachting <n> FOUT: <bron>  (verwacht=present, werkelijk=absent)
```

Dat is een Nederlandse omlijsting van de toolchain met Engelse waarden uit uchars catalogus in één regel. De waarden
volgen bewust `--language` (uchar-kaart `message-language`, de sleutels `PART.value.*`); de regel in `CLAUDE.md`
(Working) zegt dat de eigen meldingen van de toolchain Nederlands blijven. Hetzelfde geldt in het scenariorapport
(`ScenarioRunnerCli`) voor `FOUT: <diagnose>` en `MARKER: <diagnose>`, en bij `check` en `drl` (`RegelSpraakCli`) voor
`niet uitvoerbaar in uchar (exit 3): <zin van uchar>` en `fout van uchar (exit 1): <zin van uchar>`.

## Goal

Een regel die een waarde of zin van uchar draagt, is in één taal te lezen: de woorden van de toolchain eromheen staan
in de taal waarin uchar zijn tekst gaf. Zonder `--language` (Nederlands) blijven die regels zoals ze zijn.

## Besluit (voorlopig, op advies van Fable, 2026-11-07)

- Een regel die een waarde of zin van uchar draagt, krijgt de omlijsting in uchars taal. Alle overige eigen tekst
  van de toolchain blijft Nederlands: usage- en optiefouten, de samenvatting van `check`, de uitleg (`Verbaliser`).
- Geen wijziging in uchar.
- `--language` is één procesbrede JVM-eigenschap (`bin/regelspraak`, `-Duchar.messages.locale`);
  `Messages.defaultLocale` leest haar per aanroep. `ScenarioResult.ExpectationResult` draagt alleen strings. De
  toolchain zet de eigenschap niet tijdelijk om: in dezelfde `report`-aanroep wordt de KieBase gebouwd die de zinnen
  van een evaluatiefout vastlegt (javadoc van `MessageLanguage`).
- `ScenarioRunnerCli`: een kleine labeltabel nl/en, gekozen op de taal waarin uchar rendert
  (`Messages.defaultLocale(Component.DROOLS)`): `verwachting`/`expectation`, `FOUT`/`FAIL`, `verwacht=`/`expected=`,
  `werkelijk=`/`actual=`. `PASS`, `FAIL` en `MARKER` als status blijven zoals ze zijn; `MARKER:` is in beide talen
  hetzelfde woord.
- `RegelSpraakCli`: de drie regels met de zin van uchar (`niet uitvoerbaar in uchar (exit 3):` bij `drl` en `check`,
  `fout van uchar (exit 1):` bij `drl`).
- `CLAUDE.md` (Working) en `README.md` (de taal van de meldingen, bij "Everyday use") zeggen de regel in de geest van
  "the toolchain's own messages keep their Dutch sentences; a line that carries a text of uchar is labelled in uchar's
  language". `docs/ENGINE.md` en de usage van `bin/regelspraak` (`--language`) worden erop nagelezen.

## Acceptance

- Eén testklasse draait `ScenarioRunnerCli` op een scenario met een mislukte verwachting zonder evaluatiefout onder
  `uchar.messages.locale=en`, en pint `(expected=present, actual=absent)` met de Engelse omlijsting; de tegenhanger
  onder `nl` pint de Nederlandse. De eigenschap wordt alleen rond de eigen aanroep gezet en in `finally` teruggezet,
  want de suite deelt haar procesbreed. De klasse levert testgevallen op (`tools/check-skips.sh`).
- De regels van `RegelSpraakCli` zijn getoetst onder `--language en` en zonder.
- Een echte run van `bin/regelspraak --language en scenario …` en zonder `--language`, op een bron met een mislukte
  verwachting; de gedrukte regels staan letterlijk in de Result.
- `make gates` groen vóór de landingscommit; de looptijd staat in de Result.

May touch: `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/ScenarioRunnerCli.java`,
`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/cli/RegelSpraakCli.java`,
`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/MessageLanguage.java` (als de taal daar gelezen
wordt), de toetsen van de toolchain, `CLAUDE.md`, `README.md`, `docs/ENGINE.md`, de usage in `bin/regelspraak`, deze
kaart.

Out of scope: uchar; de zinnen zelf van uchar en van de toolchain; de uitleg, de usage- en optiefouten en de
samenvatting van `check`, die Nederlands blijven.

## Result

*Bouwer, 2026-11-07, op main van de toolchain (van `9bd56fada`). Pin ongewijzigd (uchar `fb143a045`).*

### Commits

| hash | inhoud |
|---|---|
| `810566760` | de kaart |
| `536013507` | de kaart geclaimd |
| `31f6319b1` | de woorden rond een tekst van uchar in uchars taal, `UcharTextLanguageTest`, `CLAUDE.md`, `README.md`, de usage |
| (deze) | Result, kaart naar `done/` |

### Wat er gebouwd is

- `MessageLanguage.label(nl, en)` kiest tussen twee woorden op de taal van het proces: de eigenschap
  `uchar.messages.locale` die `apply` vastlegt, anders `nl`. Dat is de taal waarin uchar rendert als een aanroep er
  geen noemt, want elke ingang roept `apply` eerst aan. Een andere taal dan `nl` of `en` faalt met de melding van
  `check`. Het leest de eigenschap zelf, zoals `apply` dat al deed, en niet via `Messages.defaultLocale(Component…)`:
  zonder `apply` verschillen uchars componenten (frontend `nl`, Drools `en`), en de toolchain kent dan maar één taal.
- `ScenarioRunnerCli.reportAndCode`: een labeltabel `verwachting`/`expectation`, `FOUT`/`FAIL`,
  `verwacht`/`expected`, `werkelijk`/`actual`, voor de regel van een mislukte verwachting en voor `FAIL:`/`FOUT:` bij
  een FAIL zonder mislukte verwachting. De Engelse woorden zijn die van uchars eigen catalogus
  (`PART.mismatch.*`: `expected …, actual …`). `MARKER:` en de statusregel (`PASS`, `FAIL`, `MARKER`) zijn in beide
  talen gelijk en ongewijzigd.
- `ScenarioRunner.diagnostics`: `regel N:`/`line N:` vóór een resolverdiagnose van een bron die niet resolvet (de
  regels onder `de bron resolvet niet — n diagnose(s):`). Die regel stond niet in de kaart; ze draagt uchars zin en
  valt onder hetzelfde besluit.
- `RegelSpraakCli`: `niet uitvoerbaar in uchar (exit 3):`/`not executable in uchar (exit 3):` bij `check` (stdout) en
  `drl` (stderr), en `fout van uchar (exit 1):`/`fault of uchar (exit 1):` bij `drl`.
- Docs: `CLAUDE.md` (Working) en `README.md` ("Everyday use") zeggen de regel (hieronder letterlijk); de usage van
  `bin/regelspraak` zegt bij `--language` nu "the language of uchar's messages, and of the words a line puts around
  one (nl by default)"; de javadoc van `MessageLanguage`, `ScenarioRunnerCli`, `ScenarioRunner` en `RegelSpraakCli`
  noemt het label. `docs/ENGINE.md` nagelezen: het zegt niets over de taal van de meldingen of de omlijsting van het
  CLI-rapport, dus het is ongewijzigd.

### De regel, zoals hij in de docs staat

`README.md`, "Everyday use":

> The toolchain's own messages keep their Dutch sentences; a line of `check`, `drl` or `scenario` that carries a value
> or sentence of uchar is labelled in uchar's language, so that under `--language en` a failed expectation reads
> `expectation 1 FAIL: characteristicPresent Z1 zware zending  (expected=present, actual=absent)` and a refusal
> `not executable in uchar (exit 3): transpiler SKIP: …`. The explanation (`--explanation`), the verdict lines of
> `scenario --manifest` and the messages the service itself writes keep their Dutch words.

`CLAUDE.md`, Working:

> The toolchain's own messages keep their Dutch sentences; a line of `check`, `drl` or `scenario` that carries a value
> or sentence of uchar is labelled in uchar's language (`MessageLanguage.label`; card uchar-text-in-uchar-language).
> The explanation, the verdict lines of `scenario --manifest` and the messages the service itself writes keep their
> Dutch words.

### Toetsen

Nieuw `UcharTextLanguageTest` (toolchain, pakket `cli`), vier testgevallen, elk onder `--language en` en zonder:

- `a_failed_expectation_is_labelled_in_the_language_of_its_values`: pint de hele regel
  `    expectation 1 FAIL: characteristicPresent Z1 zware zending  (expected=present, actual=absent)` en de tegenhanger
  `    verwachting 1 FOUT: characteristicPresent Z1 zware zending  (verwacht=aanwezig, werkelijk=afwezig)`, en dat
  `    dekking:` onder `en` Nederlands blijft;
- `the_verdict_of_a_case_without_a_failed_expectation_is_labelled_in_its_language`: twee regels schrijven verschillende
  waarden in één attribuut, dus geen fixpunt; `    FAIL: … WritersConflict: no fixpoint: two writers …` tegen
  `    FOUT: … WritersConflict: geen fixpunt: twee schrijvers …`;
- `a_diagnostic_of_a_source_that_does_not_resolve_is_labelled_in_its_language`: `  line 9: scenario: the object type
  'Zending' …` tegen `  regel 9: scenario: het objecttype 'Zending' …`, en de kopregel `de bron resolvet niet — 1
  diagnose(s):` blijft Nederlands;
- `the_refusal_of_uchar_in_check_and_drl_is_labelled_in_its_language`: `check` en `drl`, exit 3, met
  `  not executable in uchar (exit 3): transpiler SKIP: regel: heffing markert — a BuiltinCall` tegen
  `  niet uitvoerbaar in uchar (exit 3): transpiler SKIP: regel: heffing markert — een BuiltinCall`.

Rood eerst: tegen de hoofdbronnen van `9bd56fada` faalden alle vier op hun `en`-asserties. De suite van de module
`toolchain`: 197 → 201 testgevallen, skip-pin PASS (vloer 120).

### Echte runs

In een tijdelijke map buiten de repo, met de bron van de eerste toets (`zending.rgs`), een verwachting op een
onbekend attribuut (`onbekend.rgs`) en de bron die uchar niet draagt (`marker.rgs`):

```
$ bin/regelspraak --language en scenario zending.rgs
FAIL  lichte zending
    expectation 1 FAIL: characteristicPresent Z1 zware zending  (expected=present, actual=absent)
    trace (0 vuring(en)):
    dekking:
      Kenmerktoekenning zware zending : NOT_FIRED
(exit 1)
$ bin/regelspraak scenario zending.rgs
FAIL  lichte zending
    verwachting 1 FOUT: characteristicPresent Z1 zware zending  (verwacht=aanwezig, werkelijk=afwezig)
    trace (0 vuring(en)):
    dekking:
      Kenmerktoekenning zware zending : NOT_FIRED
(exit 1)
$ bin/regelspraak --language en scenario onbekend.rgs
de bron resolvet niet — 1 diagnose(s):
  line 9: scenario: the object type 'Zending' has no member 'lengte'
(exit 1)
$ bin/regelspraak scenario onbekend.rgs
de bron resolvet niet — 1 diagnose(s):
  regel 9: scenario: het objecttype 'Zending' kent geen attribuut 'lengte'
(exit 1)
$ bin/regelspraak --language en check marker.rgs
objecttypen=1  domeinen=0  parameters=0  feittypen=0  regels=1  resolved=1
  [BEREKENING] De heffing van een Vlucht := het aantal dagen in de maand dat hij belast is :: onbekend(niet opgelost)
  not executable in uchar (exit 3): transpiler SKIP: regel: heffing markert — a BuiltinCall dayCount in a value ('het aantal dagen in de maand dat hij belast is'): this branch calculates a time-proportional part and a day count only over the timeline of a time-dependent target (RUNTIME-SEMANTICS ch. 3.7, 3.8, 3.11)
(exit 3)
$ bin/regelspraak check marker.rgs
objecttypen=1  domeinen=0  parameters=0  feittypen=0  regels=1  resolved=1
  [BEREKENING] De heffing van een Vlucht := het aantal dagen in de maand dat hij belast is :: onbekend(niet opgelost)
  niet uitvoerbaar in uchar (exit 3): transpiler SKIP: regel: heffing markert — een BuiltinCall dayCount in een waarde ('het aantal dagen in de maand dat hij belast is'): deze tak rekent een tijdsevenredig deel en een dagentelling alleen als de tijdlijn van een tijdsafhankelijk doel (RUNTIME-SEMANTICS ch. 3.7, 3.8, 3.11)
(exit 3)
```

### `make gates`

GROEN, 7 stappen in 6m35s (wandklok 395 s), op `31f6319b1`: pin 1s; reactor 2m33s (testgevallen per module
201/508/57); twins 4s; replay 1m57s, 353 goldens (pin 353), 1000 cases (pin 1000); manifest 2s, 32 regels, 29 ok,
3 uitgesloten; image 45s, `decision-service:dev`; kind-smoke 1m19s, H2/H3/H4 PASS. De uitvoer staat in
`/Users/yeb/Developer/uchar-dev/werk/uchar-text-in-uchar-language/logs/gates.log` en per stap in `…/logs/out-gates/`
(kopie van `out/gates/`). Daar staan ook de echte runs (`final-runs.txt`), hun bronnen en de proef (`sources/`), de
suite van de module (`toolchain-suite.log`), de rode run (`red-first.log`) en de losse `ScenarioMutationRunTest` op
`9bd56fada` (`targeted-head.log`).

### Afwijkingen en kanttekeningen

- **De toets draait in een eigen JVM, niet met de eigenschap rond de eigen aanroep.** Het advies was de eigenschap
  alleen rond de eigen aanroep te zetten en in `finally` terug te zetten. uchar legt echter zinnen vast bij de
  initialisatie van een klasse (`static final String … = Msg.drools(…).text()` in `ComputeFault` en
  `RegelSpraakRuntime`). Een proef in één JVM: `ScenarioRunner.run` op `zending.rgs` onder `en`, daarna de eigenschap
  terug op `nl`; toen gaven `ComputeFault.UNRESOLVED` en `RegelSpraakRuntime.DATUM_BUITEN_BEREIK` nog "Unresolved
  reached in evaluation: this rule fails observably" en "date arithmetic leaves the representable range of a date".
  Een toets die als eerste in het JVM van de suite zo'n klasse laadt, laat alle toetsen erna met Engelse vaste zinnen
  achter, afhankelijk van de volgorde. Daarom draait `UcharTextLanguageTest` `bin/regelspraak` in een kind-JVM, zoals
  `CheckCommandTest`. De eigenschap van het JVM van de suite blijft onaangeroerd, en de toets loopt door de echte
  `--language` van de launcher.
- **Wat rond een tekst van uchar Nederlands blijft** (en daarom in de regel bij naam genoemd staat):
  - de uitleg, volgens het besluit: `geen uitleg: uchars replay speelde de case niet uit: <diagnose>` en `uchars replay
    kwam niet op een fixpunt: <zin>`;
  - `scenario --manifest`: `MISMATCH (uitgesloten, maar uchar weigert het niet met '<fragment>': <weigering>)`. De
    uitsluitingen in `scripts/toka-manifest-exclusions.txt` zijn fragmenten van uchars Nederlandse weigeringen, dus
    onder `en` slaagt geen enkele uitsluiting. Het manifest is een poort die in het Nederlands draait;
  - de dienst: `serve` drukt bij het starten `niet uitvoerbaar: <weigering>` achter een entrypoint dat uchar niet draagt
    (`ServiceMain.printServingLines`). Dat valt onder "the messages the service itself writes". Wil de eigenaar het
    besluit ook daar, dan is dat een vervolgkaart;
  - `RegelSpraakEngine.compile` (Java-API, geen CLI-regel) zet `niet uitvoerbaar in uchar: <zin>` in een diagnose van
    `RegelSpraakCompileException`.
- **uchars Engelse zin is niet helemaal Engels.** Onder `en` begint de weigering met `transpiler SKIP: regel: heffing
  markert — a BuiltinCall …`: het woord `regel` (de soort van de vorm) staat zo in uchars Engelse zin, en de
  samenvatting van `check` noemt onder `en` het type `onbekend(niet opgelost)`. Dat is uchars tekst; een wijziging
  daarvan is een uchar-kaart.
- `fout van uchar (exit 1):` is niet getoetst: daarvoor is een DRL-tekst nodig die de Drools-compiler weigert, een
  fout van uchars rendering waarvoor geen bron bekend is. Het pad gebruikt hetzelfde `MessageLanguage.label` als de
  getoetste regels.
- **Bestaande volgorde-afhankelijkheid, niet door deze kaart.** `ScenarioMutationRunTest` alleen gedraaid
  (`-Dtest=ScenarioMutationRunTest`) faalt ook op `9bd56fada`, 11 van 11: uchars oordeel is dan Engels (`expected empty,
  actual Lijnvlucht`), omdat in dat JVM niets `MessageLanguage.apply` aanroept voordat uchars Drools-component rendert
  (standaard `en`). In de volle suite doet een eerdere toets dat. Een vervolg kan `ScenarioRunner` of de toets `apply`
  laten aanroepen.
- **De submodule was niet geregistreerd.** De eerste `make gates` stopte bij stap 1: `submodule 'uchar' is niet
  geïnitialiseerd`. In `.git/config` ontbrak `submodule.uchar.url`, terwijl `uchar/` op de pin stond (een gitfile naar
  `.git/modules/uchar`, schoon). `git submodule update --init --recursive` (CLAUDE.md, "Build and gates") registreerde
  haar zonder de werkboom te veranderen; daarna was stap 1 groen.
