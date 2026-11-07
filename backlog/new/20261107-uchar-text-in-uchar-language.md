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
