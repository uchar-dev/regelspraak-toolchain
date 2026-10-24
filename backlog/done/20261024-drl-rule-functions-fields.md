# drl-rule-functions-fields: drlRules wijst per regel zijn stuk van drl aan, met zijn functies

*Voorstel van de werkbank (2026-10-24). De eigenaar vroeg de werkbank-sessie deze kaart te schrijven en zelf te bouwen,
na de uchar-kaart `drl-rule-functions` (uchar `backlog/new/20261024-drl-rule-functions.md`) en na de landing van
`drl-rule-part-fields`, omdat die `Playground.drlRules` ook verandert. Niet gecommit tot de bouw.*

## Aanleiding

`drlRules` (`Playground.java` r. ±616, `docs/ENGINE.md`) zegt per regel van `drl` wat hij verlaagt. De werkbank deelt
`drl` op in de stukken `rule "…" … end` en legt die naast de vermeldingen. De functies die een regel aanroept (elke
toets en elke waarde, in het Java-dialect, met de RegelSpraak-zin als commentaar) staan in `drl` vóór de regel, buiten
die stukken; de werkbank toont ze dus niet, en een lezer ziet niet wat een regel berekent. Voorbeeld en getallen: zie de
uchar-kaart (v2/herleving-ww, `terugval 2` van `defaults A:Eindigingsgrond.herlevingsdatum`, functie `r118_afloop2`
met de dag 1; 339 regels en 562 functies).

## Doel

- Elke vermelding van `drlRules` krijgt wat uchar per regel levert (kaart `drl-rule-functions`): de plaatsen van zijn
  stuk in `drl` (functies en regel), of de namen en plaatsen van zijn functies. De werkbank snijdt dan alleen op die
  plaatsen en leest geen namen. De toolchain kiest de sleutels en zegt waarom; zij vertaalt wat uchar levert.
- Plaatsen in tekens van `drl` zoals het antwoord het als JSON-tekst geeft; zeg in ENGINE.md in welke eenheid (UTF-16
  zoals JavaScript telt, of code points), want de bronnen hebben letters als `ë`.
- `docs/ENGINE.md` en de javadoc van `Playground.drlRules` bij.

## Acceptatie

- (a) Op de bundel van v2/herleving-ww: voor elke vermelding geeft het nieuwe veld een stuk van `drl` dat begint met
  zijn functies (of met `rule` zonder functies) en eindigt na zijn `end`; de stukken dekken samen de tekst na de kop,
  zonder overlap. De vermelding van `defaults A:Eindigingsgrond.herlevingsdatum: terugval 2` heeft de functies
  `r118_…`, waaronder `r118_afloop2` met `java.math.BigDecimal("1")` als dag.
- (b) Zonder DRL (`drlError`): `drlRules` blijft een lege lijst.
- (c) `make gates` groen.

## Daarna, in de werkbank (geen deel van deze kaart)

De kaart "Verlaagde DRL" toont boven elke DRL-regel zijn functies, ingeklapt, met de RegelSpraak-zin uit hun
commentaar zichtbaar.


## Result (2026-10-24)

Tak `card/drl-rule-functions-fields` in de worktree `wt-tc/drl-rule-functions-fields`, vanaf toolchain main `e469871e`.
Niet geland en niet gepusht; de landing doet de hoofdsessie.

- `b494d3e8` de kaart uit de werkbank, `63bdf7b9` naar `doing/`.
- `bc4491d9` pin uchar `3c137fcd` (de tip van de uchar-kaarttak `drl-rule-functions`, nog niet op uchar main), na
  `git add uchar` en `make check-pin`. De pin voegt `TmsTranspiler.drlPieces()` en twee getuigen toe; de DRL-tekst
  en het exec-model veranderen niet (uchar: `diff -r` van alle 658 teksten leeg), geen golden verandert.
- `c98e8fbd` `piece` in elke vermelding van `drlRules`: `Playground`, de tests, `docs/ENGINE.md`, README ("Contract
  change").
- dit Result.

### De velden

Alles komt uit `build.execModelTranspiler().drlPieces()`, op dezelfde build van de tak `DRL_TEXT` als `build.drl()`.
De toolchain leest geen naam van een functie en geen plaats uit de tekst.

`drlRules`, per vermelding, als laatste sleutel:

| sleutel | waarde |
|---|---|
| `piece` | `{start, ruleStart, end, functions}`: uchars `DrlPiece` zonder `rule` (dat is `name` van de vermelding) |
| `piece.start` | het begin van het stuk: van zijn eerste functie, met het commentaar van die functie; `ruleStart` bij een regel zonder functies |
| `piece.ruleStart` | het begin van de regel `rule "…"` |
| `piece.end` | het einde van het stuk, na de regel `end` en de lege regel erna: het begin van het volgende stuk, of het einde van `drl` |
| `piece.functions` | de functies in de volgorde van de tekst, altijd aanwezig, `[]` bij een regel zonder; elk `{name, start, declaration, end}` |
| `functions[].name` | de naam zoals de regel en de andere functies van het stuk hem spellen (`r118_afloop2`) |
| `functions[].start` / `.declaration` / `.end` | het begin met commentaar / het begin van de regel `function …` / het einde na de accolade en de lege regel; tussen `start` en `declaration` staat `// <RegelSpraak-zin>\n`, of niets (`start == declaration`) |

Eenheid: UTF-16-code-units van de `drl` van hetzelfde antwoord, zoals JavaScript een string telt en een Java-`String`;
geen bytes en geen code points. `drl.slice(start, end)` is het stuk. In `docs/ENGINE.md` ("drlRules") met de
sleutels, de eenheid en de controle.

Uit de meting (a) (v2, regels `ece012e`), letterlijk:

```json
{"name": "defaults A:Eindigingsgrond.herlevingsdatum: terugval 2", "role": "fallback",
 "stack": "defaults A:Eindigingsgrond.herlevingsdatum", "group": "herleving na het vervallen art. 21 lid 1",
 "pointer": "/defaults/2", "position": 3, "variant": "herleving per kalendermaand art. 21 lid 1 na onderdeel c",
 "piece": {"start": 548839, "ruleStart": 551976, "end": 553759, "functions": [
   {"name": "r118_toets0", "start": 548839, "declaration": 549078, "end": 549431},
   {"name": "r118_samengesteld1", "start": 549431, "declaration": 549670, "end": 551159},
   {"name": "r118_afloop2", "start": 551159, "declaration": 551329, "end": 551976}]}}
{"name": "herlevingsdatum van het recht art. 21 lid 1: bijdrage aan vouw 2", "role": "fold-contribution",
 "rule": "herlevingsdatum van het recht art. 21 lid 1", "fold": "/versions/0/value/arguments/0",
 "piece": {"start": 583097, "ruleStart": 583097, "end": 583715, "functions": []}}
```

`drl.slice(551159, 551329)` is `// de datum met jaar: het jaar uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd),
maand: de maand uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd) en dag: 1\n`; `drl.slice(551329, 551976)` is
`r118_afloop2`, met `new java.math.BigDecimal("1")` als derde argument van `ComputeHelpers.dateWith`.

### Waarom zo

1. **Eén object `piece`, met de sleutels van uchars records.** `piece`, `ruleStart`, `start`, `end`, `functions`,
   `name`, `declaration` heten zoals de componenten van `DrlPiece` en `DrlFunction`, om dezelfde reden als `role`,
   `fold` en `composition`: een lezer van uchars javadoc vindt ze terug en de toolchain voegt geen eigen woord toe.
   `rule` van uchars record is `name` van de vermelding; de sleutel `rule` van een vermelding noemt haar vorm, een
   tweede betekenis zou verwarren. De plaatsen staan in een object naast de sleutels van de beslisser omdat
   `position` en `pointer` plaatsen in het IR zijn en `start` en `end` plaatsen in `drl`.
2. **`functions` binnen `piece` en altijd aanwezig.** Zo heeft uchars record één vorm in het antwoord, en test een
   lezer niet op de afwezigheid van een sleutel; een regel zonder functies heeft `[]` (130 van 339 in v2).
3. **Als laatste sleutel**, zoals `composition` bij een stapel: het is het geneste deel, de platte sleutels staan eerst.
4. **De plaatsen onvertaald, de zin niet losgeknipt.** uchar geeft de RegelSpraak-zin als plaatsen (de regel tussen
   `start` en `declaration`) en niet als string (uchar-kaart, voorlopig besluit 3). De toolchain volgt: `drl.slice(
   f.start, f.declaration)` is de commentaarregel met `// ` en het regeleinde. Geen sleutel `sentence` erbij: dat zou
   een eigen indeling van de toolchain zijn die uchars vorm (`// ` + zin + `\n`) moet kennen.
5. **Canoniek in elk vocabulaire**, zoals `drl` zelf: de plaatsen liggen in de `drl` van het antwoord, en die is uchars.
   Gemeten: `?vocabulary=cgm` en `?via=service` geven dezelfde `drlRules`.

### Koppeling en luid falen

`drlRules` koppelt op de volgorde van de koppen en op de naam, en toetst beide (`Playground.checkPieces`, vóór de
vermeldingen gemaakt worden): evenveel stukken als koppen in `drl`; het i-de stuk heeft de naam van de i-de kop (de
koppen gelezen zoals voorheen, `drlRuleNames`, en nog tegen de KieBase gehouden); elk stuk begint waar het vorige
eindigt, het laatste eindigt op `drl.length()`; en op elke `ruleStart` staat `rule ` en de naam als DRL-stringliteral
(`Namen.drlString`, uchars eigen spelling) en een regeleinde. Een afwijking is een `IllegalStateException` met de zin
over het eerste stuk dat niet past (naam van de regel, de twee plaatsen): een 500, geen veld zonder waarde. Gemeten op
v2 kloppen naam en volgorde allebei: 339 stukken, 339 koppen, geen afwijking. `drlRuleNames` blijft dus, als de
onafhankelijke lezing van de koppen waartegen uchars stukken worden gehouden; uchar noemde als mogelijkheid hem te
vervangen door `p.rule()`.

Bij `drlError` blijft `drlRules` `[]` (de bouw van de tak faalt vóór er een build is; `drlPieces()` wordt niet gevraagd).

### Tests

`PlaygroundDrlRulesTest`, 9 tests voor, 13 erna (de reactor telt voor de dienst 388 tegen 384). Rood eerst, met alleen
een lege `checkPieces` zodat de tests compileren: 4 van 13 rood (`logs/red-1.log`: de vier nieuwe), de andere negen
groen. Na de wijziging groen, 13 van 13 (`logs/green-2.log`; `green-1.log` faalde op twee verwachtingen van de test
zelf: ik telde 34 functies in plaats van de 36 declaraties van de tekst, en de naam met 𝔘 staat vier keer voor een
latere regel, niet een keer).

- `every_rule_of_the_drl_text_has_its_piece_with_its_functions` (nieuw): op het eigen model, via JSON-tekst en terug,
  `piecesFollowTheText` (zie onder) leeg, `piece` is de laatste sleutel en de sleutels van `piece` en functie zijn die van
  uchar in zijn volgorde, 36 functies; de tabel van het stuk (per functie naam, commentaar, wat zij aanroept; wat de
  regel aanroept, uit de tekst gelezen) voor: een regel zonder functies (`kenmerkstand … onwaar`: `start ==
  ruleStart`, het stuk is de regel tot het einde van de tekst), een functie met zin (`melding`), een functie zonder zin
  (`eens 0`), een samengestelde voorwaarde (`defaults A:Vondst.waarde: default 0.0.0`: `r11_toets0` roept
  `r11_samengesteld1` aan, de zin is die van de geërfde en de eigen voorwaarde) en de terugval van een default met een
  uitzonderingsgroep (`terugval 0`: sluit aan op `eens 0` ervoor en `default 1` erna); en `drlRules` in `cgm` gelijk
  aan het canonieke antwoord.
- `a_place_counts_utf16_code_units` (nieuw): de regel `melding` heet in het IR `melding 𝔘` (U+1D518, twee code
  units, een code point); alle plaatsen erna liggen een of meer code units ná het aantal code points, en het antwoord
  voldoet via JSON-tekst en terug aan dezelfde lezing van de tekst.
- `pieces_that_do_not_fit_the_text_are_a_fault` (nieuw): `checkPieces` weigert een stuk te weinig, een stuk van een
  andere regel dan de kop, een gat, een tekst die na het laatste stuk doorloopt en een `ruleStart` die niet op zijn kop
  ligt.
- `over_the_corpus_every_piece_follows_the_text` (nieuw): over elke golden van uchar die de tak bouwt, dezelfde lezing.
  Gemeten 282 goldens, 2124 stukken en 2499 functies (de vloeren; uchars corpusgetuige telt dezelfde getallen), ongeveer
  20 s.
- `piecesFollowTheText` is het orakel van de tests, dat uchars weergave niet raadpleegt: de tekst per regel gelezen
  (`String.indexOf`, dus UTF-16), de koppen `rule "…"`, de declaraties `function …`, de commentaarregel boven een
  declaratie; het stuk begint waar de kop van de tekst eindigt en sluit aan tot het einde van de tekst; elke functie
  begint met haar commentaarregel waar zij er een heeft en eindigt na haar accolade en een lege regel; elke
  declaratie van de tekst staat in een stuk, een keer; een aanroep in een stuk is een aanroep van een functie van dat
  stuk.
- Mutaties van `Playground` (niet gecommit): `declaration` uit `f.start()` (M1), `end` uit `p.ruleStart()` (M2) en het
  stuk van de volgende regel (M3) maken elk drie van de vier nieuwe tests rood (`logs/mut-m1.log` tot `mut-m3.log`).

Een bestaande test aangepast, omdat de kaart het gedrag verandert dat hij toetst: `every_rule_of_the_drl_text_names_its_decider`
vergelijkt de vermeldingen nu zonder hun `piece` (`withoutPiece`) met `EXPECTED`; elke verwachte waarde is ongewijzigd
(naam, rol, beslisser, vouw), alleen het nieuwe veld doet er niet aan mee, en het wordt in de nieuwe tests getoetst.
Verder alleen de lijst van goldens van de corpusveeg naar een hulpmethode (`goldenDocuments`), voor twee veegtests.

### Meting (a)

`werk/toolchain-2026-10-24/drl-rule-functions-fields/meting/`: `run-measure.sh` neemt de bestanden van regels
`ece012e` (`git archive` naar `rules-ece012e/`, de werkbank niet aangeraakt), zet de bundel samen zoals de dienst haar
leest (fundament eerst, op naam), start `bin/regelspraak serve` van de worktree met `PLAYGROUND=true --detail` en
`v2/herleving-ww/service/herleving.service.json` op poort 18095, post de bundel naar `/playground/analyse`, `?via=service`,
`?vocabulary=cgm` en beide, en toetst met `check-pieces.mjs` (Node, zodat de eenheid die van JavaScript is: wat de
werkbank met `JSON.parse` en `slice` doet). Log `meting/measure.log` (toolchain `c98e8fbd` zonder wijziging erna, uchar
`3c137fcd`). PASS:

1. `drl` heeft 748 007 tekens en 339 koppen; `drlRules` 339 vermeldingen, elk op de plaats en met de naam van zijn kop.
2. `piece` is de laatste sleutel van elke vermelding; de sleutels van `piece` en functie zijn die van uchar.
3. De 339 stukken sluiten aan van 1333 (het einde van de kop: package, imports, globals) tot 748 007 (het einde van
   `drl`), zonder overlap; `ruleStart` staat op elke kop; elk stuk eindigt met `end` en een lege regel.
4. 562 functies (438 met commentaar), 130 stukken zonder functies (per stuk 0 tot 11 functies); ze sluiten aan van
   `start` tot `ruleStart`; elke declaratie van de tekst ligt een keer in een stuk; geen aanroep van een functie van een
   ander stuk. `drl` heeft 1461 tekens buiten ASCII en geen enkel buiten het Basic Multilingual Plane, dus v2 maakt geen
   verschil tussen UTF-16 en code points; dat verschil toetst `a_place_counts_utf16_code_units`.
5. `defaults A:Eindigingsgrond.herlevingsdatum: terugval 2` heeft de functies `r118_toets0`, `r118_samengesteld1`,
   `r118_afloop2`, in die volgorde; `r118_afloop2` heeft `java.math.BigDecimal("1")` als dag (zie boven). Het nummer is
   118, zoals in de uchar-kaart.
6. `?via=service`, `?vocabulary=cgm` en beide geven dezelfde `drl` en `drlRules`.
7. `drl` is gelijk aan `bin/regelspraak drl` over dezelfde bronnen, en aan de `drl` van de analyse van de hoofdsessie
   (`scratchpad/stapel/analyse.json`): de vermeldingen zijn gelijk op `piece`, `role` en `fold` na.

### `make gates`

`make gates` op `c98e8fbd` met `TAG/CLUSTER=drl-rule-functions-fields` (`logs/gates-1.log`, de uitvoer in `out/gates/`):
GROEN, 7 stappen in 11m45s. Reactor 1689 (Drools, de pin: vijf meer dan op `42ed425d`) / 173 (toolchain) / 388 (dienst, vier
meer) / 53 (Quarkus), skip-pin PASS in elke module; tweelingen 3 (18 cases); replay 249 goldens, 673 cases (pin 249);
TOKA-manifest 29 ok, 3 uitgesloten; image `decision-service:drl-rule-functions-fields`; kind-smoke H2/H3/H4 PASS. Het
Result-commit raakt alleen deze kaart.

### Wat open bleef

- **De pin staat op `3c137fcd`, de tip van de uchar-kaarttak**; de hoofdsessie zet hem vóór de landing op de
  landingscommit van uchar en draait `make gates` opnieuw.
- **De werkbank knipt het `// ` en het regeleinde zelf.** uchar geeft de zin als plaatsen van de hele commentaarregel
  (uchar-kaart, voorlopig besluit 3); wil de werkbank de kale zin zonder die twee, dan zijn dat `start + 3` en
  `declaration - 1`, en dan voegt uchar liever twee plaatsen toe dan dat de toolchain de vorm kent.
- **Een functie die haar regel niet bereikt** komt door, zoals uchar meldt: een binder (`…_binder<n>`) waarvan de
  verlaagde body de variabele niet leest, in `jutterswet-bergingstoeslag`. De werkbank toont dan bij die regel een
  functie die hij niet aanroept. In v2 niet gemeten (de meting toetst alleen dat geen aanroep naar een ander stuk gaat).
- **Twee keer het document laden per antwoord** blijft zoals bij `drl-rule-part-fields`: `drlRules` en `generated` lezen
  elk `IrLoader.loadWithPositions(ir)`.
- **De gate-images** `decision-service:drl-rule-functions-fields`, `…-h4a` en `…-h4b` staan nog in Docker; de kind-cluster
  is door de smoke verwijderd.

### Na de fixronde van uchar (2026-10-24, werkbank-sessie)

De review van de uchar-kaart `drl-rule-functions` leidde tot een fixronde op de uchar-tak (tip `229c5141`): de
constructors van `DrlPiece` en `DrlFunction` gooien bij een boekhoudfout een `IllegalStateException`, zoals de rendering
zelf, zodat `Playground` een `drlError` geeft in plaats van een 500; de corpusgetuige pint de ene onbereikte binder bij
naam. De signatuur van `drlPieces()` bleef gelijk; `Playground` verandert niet.

- `b48278f9` pin uchar `229c5141`, na `make check-pin` (PASS).
- `make gates` op `b48278f9`: GROEN, 7 stappen in 11m31s (reactor 1691/173/388/53, tweelingen 3, replay 249 goldens en
  673 cases, manifest 29 ok en 3 uitgesloten, image, kind-smoke H2–H4 PASS). Log:
  `/Users/yeb/Developer/uchar-dev/werk/toolchain-2026-10-24/drl-rule-functions-fields/logs/gates-2.log`.
