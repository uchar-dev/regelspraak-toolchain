# drl-readable-functions-pin: de pin op uchar met minder DRL-functies en namen naar de regel

*Voorstel van de werkbank (2026-10-25), op besluit van de eigenaar. Niet gecommit tot de bouw. Hoort bij de
uchar-kaarten `20261025-drl-no-passthrough-functions` en `20261025-drl-function-names-from-rule`. Vervolg in de werkbank:
`backlog/new/20261025-drl-leesbare-functies-pin.md`.*

## Aanleiding

De twee uchar-kaarten veranderen de DRL-tekst zonder het gedrag te veranderen. De eerste schrijft minder functies:
geen `toets` die alleen een `samengesteld` aanroept, en geen functie voor een voorwaarde die niets toetst. De tweede
geeft elke functie een naam naar haar regel en haar soort, in plaats van `r<n>_<soort><plaats>`. De velden van
`drlRules` blijven gelijk (`name`, `start`, `declaration`, `end` per functie), maar hun waarden veranderen. Twee
plaatsen in de toolchain pinnen die waarden:

- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/http/PlaygroundDrlRulesTest.java` r. 732–750: de
  namen en de lijsten "roept", zoals `r11_toets0 | … | roept [r11_samengesteld1]` en `r11_samengesteld1`, die na de
  eerste kaart samenvallen;
- `docs/ENGINE.md` r. 318: het voorbeeld `r118_afloop2`.

## Doel

De pin-bump die een van beide uchar-kaarten binnenhaalt, brengt in hetzelfde commit de verwachtingen van
`PlaygroundDrlRulesTest` en het voorbeeld in `docs/ENGINE.md` in lijn met de nieuwe tekst. Het liefst één bump na de
landing van beide kaarten; komt er eerder een bump die alleen de eerste bevat, dan doet die bump deze kaart voor dat
deel. `Playground.drlRules` en zijn velden veranderen niet.

## Acceptatie

- (a) `make check-pin` PASS op de uchar-commit met de kaart(en).
- (b) `PlaygroundDrlRulesTest` groen. De nieuwe verwachtingen komen uit de nieuwe tekst en volgen de uchar-kaarten
  (één functie waar eerst `toets` en `samengesteld` stonden; namen naar de regel), en het `Result:` noemt elke
  aangepaste verwachting.
- (c) `make gates` groen.

## May touch

`uchar` (de pin), `service/src/test/java/dev/uchar/regelspraak/toolchain/service/http/PlaygroundDrlRulesTest.java`,
`docs/ENGINE.md`, deze kaart.

## Out of scope

De velden van `drlRules`, de Playground zelf, de werkbank.

## Result (2026-10-25)

Tak `card/drl-readable-functions-pin`, vanaf main `2e970aea`, de kaart naar `doing/` in `cfb8b3c5`. Eén bump na beide
uchar-kaarten, zoals de kaart het liefst had.

- `423521fb` de pin op uchar `321fb239` (main: de landingen van `drl-no-passthrough-functions`, `88ab04b0`, en
  `drl-function-names-from-rule`, `4e62a98a`, plus een commit met drie backlogkaarten), met in hetzelfde commit
  `PlaygroundDrlRulesTest` en `docs/ENGINE.md`.

### Aangepaste verwachtingen van `PlaygroundDrlRulesTest`

In `every_rule_of_the_drl_text_has_its_piece_with_its_functions` (het eigen kleine model van de test):

| plaats | was | is |
|---|---|---|
| aantal functies | 36 | 24 |
| `melding` | `r2_waarde0`; regel roept `r2_waarde0` | `waarde_melding`; regel roept `waarde_melding` |
| `defaults A:Vondst.waarde: eens 0` | `r12_afloop0` | `afloop_defaultsAVondstWaardeEens0` |
| `defaults A:Vondst.waarde: default 0.0.0` | `r11_toets0` (roept `r11_samengesteld1`), `r11_samengesteld1`, `r11_afloop2` | `toets_defaultsAVondstWaardeDefault000` (roept niets: de toets draagt de samengestelde voorwaarde), `afloop_defaultsAVondstWaardeDefault000` |
| `defaults A:Vondst.waarde: terugval 0` | `r13_toets0`, `r13_afloop1` | `toets_defaultsAVondstWaardeTerugval0`, `afloop_defaultsAVondstWaardeTerugval0` |

De zinnen en de volgorde zijn gelijk gebleven. In `over_the_corpus_every_piece_follows_the_text` daalt de vloer
`MIN_FUNCTIONS` van 2499 naar 2201, gemeten op deze pin (uchars eigen getuige telt hetzelfde): uchars kaart
`drl-no-passthrough-functions` schrijft een functie alleen waar zij iets toetst of berekent. `MIN_BUILT` (282) en
`MIN_PIECES` (2124) blijven.

### De aanroepen werden niet meer gezien

De test vond een aanroep met het patroon `r[0-9]+_[a-z]+[0-9]+\(`. Met de nieuwe namen vindt dat niets meer, dus de
controle in `piecesFollowTheText` "een stuk roept alleen functies van zijn eigen stuk" en de kolom "roept" van de
tabellen slaagden zonder iets te zien (de eerste run na de pin gaf `regel | roept []` voor `melding`). De test leest
nu de namen uit de declaraties van de tekst (`declaredFunctions`) en zoekt een aanroep van een van die namen
(`callOf`, de langste eerst, niet na een letter, cijfer, `_`, `$` of punt); het patroon van een declaratie neemt elke
letter en elk cijfer, want een naam draagt de naam van zijn regel (`ë`, `日`). Rood aangetoond met een mutatie die elke
gevonden aanroep als een aanroep van een ander stuk telt (niet gecommit, daarna teruggezet): 3 van 13 tests rood, met
onder meer "roept waarde_strandpremie van een ander stuk"; log
`werk/toolchain-2026-10-25/drl-readable-functions-pin/logs/mutation-calls.log`.

### `docs/ENGINE.md`

De sectie over `piece`: "every test and every value of a rule's plan that tests or computes something is a function",
en de naam als `afloop_defaultsAEindigingsgrondHerlevingsdatumTerugval2` (de soort, een nummer erachter waar de regel er
meer van die soort heeft, `_` en de naam van de regel in één woord); welke functies van een regel zijn, zegt het stuk,
nooit de naam. De velden van `drlRules` zijn niet veranderd.

### Acceptatie

- (a) `make check-pin` PASS op `321fb239`.
- (b) `PlaygroundDrlRulesTest` groen: 13 tests, 0 fouten (`logs/green-2.log`).
- (c) `make gates` (TAG/CLUSTER `drl-readable-fns`) groen op `423521fb`: 7 stappen in 11m04s (reactor 7m54s,
  testgevallen per module 1702/173/388/53; replay 249 goldens, 673 cases). Daarna herschreef `7ca0070c` twee
  commentaren van de test naar de huidige stand; `make gates` opnieuw groen op `7ca0070c`: 7 stappen in 11m17s.
  Logs `werk/toolchain-2026-10-25/drl-readable-functions-pin/logs/gates-1.log` en `gates-2.log`.
