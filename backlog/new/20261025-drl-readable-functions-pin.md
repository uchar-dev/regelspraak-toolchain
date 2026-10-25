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
