# drl-value-functions-pin: de pin op uchar zonder functies voor een waarde die niets berekent

*Voorstel van de werkbank (2026-10-25), op besluit van de eigenaar. Niet gecommit tot de bouw. Hoort bij de
uchar-kaart `20261025-drl-no-trivial-value-functions`. Vervolg in de werkbank:
`backlog/new/20261025-drl-waardefuncties-pin.md`.*

## Aanleiding

De uchar-kaart verandert de DRL-tekst zonder het gedrag te veranderen. Een `afloop` of `waarde` die alleen een
constante, een gebonden variabele of `collapse` van een van beide teruggeeft, krijgt geen functie meer: het `then`-deel
draagt de expressie zelf, met de zin als commentaarregel erboven. Op v2/herleving-ww gaan er 58 van de 416 functies
weg. De velden van `drlRules` blijven gelijk, alleen hun waarden veranderen. In de toolchain pinnen deze plaatsen die
waarden:

- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/http/PlaygroundDrlRulesTest.java`, in
  `every_rule_of_the_drl_text_has_its_piece_with_its_functions`: het aantal functies (24) en de functies per regel,
  zoals `afloop_defaultsAVondstWaardeEens0` en `waarde_melding`. Die vallen weg als hun lichaam niets berekent.
- dezelfde test, `over_the_corpus_every_piece_follows_the_text`: de vloer `MIN_FUNCTIONS` (2201).
- `docs/ENGINE.md` bij `piece` (r. 319 e.v.): "every test and every value of a rule's plan that tests or computes
  something is a function". Dat blijft waar. Het voorbeeld `afloop_defaultsAEindigingsgrondHerlevingsdatumTerugval2`
  berekent een datum en blijft dus ook.

## Doel

De pin-bump die de uchar-kaart binnenhaalt, brengt in hetzelfde commit de verwachtingen van `PlaygroundDrlRulesTest`
in lijn met de nieuwe tekst, en `docs/ENGINE.md` waar die tekst niet meer klopt. `Playground.drlRules` en zijn velden
veranderen niet.

## Acceptatie

- (a) `make check-pin` PASS op de uchar-commit met de kaart.
- (b) `PlaygroundDrlRulesTest` groen. De nieuwe verwachtingen komen uit de nieuwe tekst en volgen de uchar-kaart: een
  regel waarvan de waarde niets berekent, heeft daarvoor geen functie en draagt de expressie in haar `then`-deel. Het
  `Result:` noemt elke aangepaste verwachting, ook de nieuwe vloer `MIN_FUNCTIONS`.
- (c) `make gates` groen.

## May touch

`uchar` (de pin), `service/src/test/java/dev/uchar/regelspraak/toolchain/service/http/PlaygroundDrlRulesTest.java`,
`docs/ENGINE.md`, deze kaart.

## Out of scope

De velden van `drlRules`, de Playground zelf, de werkbank.
