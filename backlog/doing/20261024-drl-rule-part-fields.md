# drl-rule-part-fields: drlRules noemt de rol van elke regel, generated het type van een stapel

*Voorstel van de werkbank (2026-10-24), met akkoord van de eigenaar door de werkbank-sessie in deze backlog gezet (niet
gecommit). Vervolg op `drl-rule-provenance` (done). Wacht op de uchar-kaart `drl-rule-part`
(uchar `backlog/new/20261024-drl-rule-part.md`), die de rol en het type levert; daarna een pin-bump en deze kaart.*

## Aanleiding

De werkbank toont een defaults-stapel aan een jurist als **beslisvolgorde**: de varianten van een regel, de hoogste
eerst (uchar RUNTIME-SEMANTICS ch. 7.1). Ze leest daarvoor `drlRules` en `generated` van `/playground/analyse`
(`Playground.java`, `drlRules` r. ±616, `generated` r. ±574) en ontleedt geen namen. Twee dingen staan nu alleen in een
naam:

- de rol van een DRL-regel in de verlaging van zijn default of vorm: `…: default 2`, `…: bijdrage aan vouw 17`,
  `…: terugval 0.0.1`, `…: eens 0`, `…: fout …`, `…: filterfout in …`; op v2 (regels `ece012e`, toolchain `b0b3f849`,
  de bundel fundament plus herleving-ww) 64 `default`, 32 `bijdrage aan vouw`, 9 `fout`, 8 `eens`, 4 `terugval`,
  2 `filterfout` van de 339 regels;
- het type waarvoor een stapel loopt: `defaults K:Recht op uitkering WW.…` en
  `defaults K:RechtOpUitkeringWwBijOntslag.…` hebben in `generated` dezelfde `generatedFrom` (het lid op het
  declarerende type `RechtOpUitkering`).

## Doel

- Elke vermelding van `drlRules` krijgt de rol uit uchars `RuleOrigin` (kaart `drl-rule-part`) als een veld met vaste
  sleutels, en waar uchar haar levert de vouw van een bijdrageregel als pointer in de IR, zoals `pointer` van een
  default de IR aanwijst.
- Elke stapel in `generated` krijgt het type waarvoor zij loopt, in het vocabulaire van het antwoord zoals `name` dat
  al volgt (`Naming`), met de canonieke naam ernaast waar die verschilt.
- `docs/ENGINE.md` (de velden van de playground: `drlRules`, `generated`) en de javadoc van `Playground.java` bij; de
  OpenAPI als die de velden noemt. De toolchain kiest de namen van de velden en de sleutels en zegt waarom; ze vertaalt
  wat uchar levert en voegt geen eigen indeling toe (`CLAUDE.md`).
- `drl`, de coverage, de trace en het exec-model veranderen niet.

## Acceptatie

- (a) Op de bundel van v2/herleving-ww (fundament plus herleving-ww, regels `ece012e`): elke vermelding van `drlRules`
  met een vorm heeft een rol. De vermeldingen van de variant `nieuw recht boven de herlevingsgrens art. 21 lid 2
  onder a` (groep `herleving art. 21 lid 1`, pointer `/defaults/2`) zijn twee keer de eigen beslissing (in de stapel
  van `Recht op uitkering WW` en in die van `RechtOpUitkeringWwBijOntslag`) en één bijdrage aan een vouw; als de pointer
  van de vouw er is, wijst die naar de maximale waarde van het maandloon in haar rechtvaardiging. In `generated` heeft
  de stapel `defaults K:RechtOpUitkeringWwBijOntslag.indicatie herleving recht op uitkering` het type
  `RechtOpUitkeringWwBijOntslag` en die van `Recht op uitkering WW` het type `Recht op uitkering WW`.
- (b) Een eigen klein model met een uitzonderingsgroep, een vouw in een voorwaarde en een stapel over een subtype: elke
  DRL-regel met zijn rol, elke stapel met haar type.
- (c) Zonder DRL (`drlError`): `drlRules` blijft een lege lijst; `generated` heeft het type ook dan, als het uit de
  analyse komt en niet uit de DRL.
- (d) `make gates` groen.

## Daarna, in de werkbank (geen deel van deze kaart)

De kaart "Verlaagde DRL" zegt bij elke regel van een variant in de woorden van een jurist wat hij doet (de toets van de
variant, een verzamelstap voor haar voorwaarde, een foutregel, …), met de tekst van de vouw waar de pointer er is. De
kaart, de trace en de dekking noemen bij een stapel het type waarvoor zij loopt, in de kleur van een objecttype; de
codenaam van de stapel gaat naar de tooltip.


## Result (2026-10-24)

Tak `card/drl-rule-part-fields` in de worktree `wt-tc/drl-rule-part-fields`, vanaf toolchain main `b0b3f849` plus de
kaart naar `doing/` (`fb32e315`). Gebouwd door een Opus-bouwer; de landing doet de hoofdsessie.

- `e39328b4` pin uchar `e3985673` (de tip van de uchar-kaarttak `drl-rule-part`, nog niet op uchar main), na
  `make check-pin`. De pin raakt alleen uchars backend en zijn getuigen; geen golden verandert.
- `d8bbc0cd` `role` en `fold` in `drlRules`, `type` in `generated`: `Playground`, de tests, `docs/ENGINE.md`, README.
- dit Result, in het commit na `d8bbc0cd`.

### De velden

Alles komt uit `build.execModelTranspiler()`: `originOf(name)` (`RuleOrigin(form, kind, role, pointer, fold)`) voor
`drlRules`, `stackTypes()` voor `generated`. Geen naam wordt gelezen; het label van een regelnaam (`default 2`,
`bijdrage aan vouw 17`) komt nergens in de toolchain voor.

`drlRules`, per vermelding:

| sleutel | waar | waarde |
|---|---|---|
| `role` | na `name`, bij elke vermelding met een vorm (`rule` of `stack`: `FORM`, `DEFAULT`, `STACK`); een regel van geen vorm heeft er geen | `o.role().key()`, uchars vaste sleutel: `own`, `fallback`, `condition-fault`, `condition-deposit`, `no-evaluation-date`, `reading`, `reconcile-pass-on`, `reconcile-difference`, `reconcile-agreement`, `fold-contribution`, `fold-filter-fault`, `fold-member-fault`, `fold-filter-deposit`, `uniqueness-key`, `uniqueness-key-form-fault`, `uniqueness-channel`, `allocation-recipient` |
| `fold` | aan het eind, bij een rol `fold-…` | de vouwknoop (`Aggregation` of `QuantifierFold`) als JSON Pointer in de groep die `group` noemt, anders in de vorm die `rule` noemt |

Voorbeelden uit de meting (a):

```json
{"name": "defaults K:Recht op uitkering WW.indicatie herleving recht op uitkering: bijdrage aan vouw 17",
 "role": "fold-contribution",
 "stack": "defaults K:Recht op uitkering WW.indicatie herleving recht op uitkering",
 "group": "herleving art. 21 lid 1", "pointer": "/defaults/2", "position": 3,
 "variant": "nieuw recht boven de herlevingsgrens art. 21 lid 2 onder a",
 "fold": "/defaults/2/justification/parts/3/left"}
{"name": "totaalgewicht: bijdrage aan vouw 0", "role": "fold-contribution", "rule": "totaalgewicht",
 "fold": "/versions/0/value"}
```

`generated`, per stapel (`generatedFrom.form` = `defaultsstapel`), tussen `kind` en `generatedFrom`:

| sleutel | waarde |
|---|---|
| `type` | het objecttype waarvoor de stapel loopt, `stackTypes().get(name)`, genoemd als `naming.name(type)`: de benaming in het gekozen vocabulaire, anders de canonieke naam |
| `typeCanonical` | `type.name()`, alleen waar die van `type` verschilt |

```json
{"name": "defaults K:RechtOpUitkeringWwBijOntslag.indicatie herleving recht op uitkering", "kind": "defaults",
 "type": "RechtOpUitkeringWwBijOntslag",
 "generatedFrom": {"kind": "KENMERK", "owner": "RechtOpUitkering",
                   "name": "indicatie herleving recht op uitkering", "form": "defaultsstapel"}}
{"name": "defaults A:RechtOpUitkering.beëindigingsdatum", "kind": "defaults", "type": "Recht op uitkering",
 "typeCanonical": "RechtOpUitkering", "generatedFrom": {…}}            (?vocabulary=cgm)
```

Waarom zo:

1. **`role` en `fold` heten zoals de componenten van `RuleOrigin`.** Een lezer van uchars Result en javadoc vindt ze
   terug, en de toolchain voegt geen eigen woord toe. `role` staat direct na `name` omdat het zegt wat de regel is; de
   beslisser volgt.
2. **De rol is uchars sleutel, onvertaald, zonder `switch`.** De toolchain deelt de rollen niet zelf in (`CLAUDE.md`);
   `key()` is de vaste spelling die uchar voor een consument maakt, los van de naam van de constante. Een `switch` over
   `Role` zou een tweede lijst van de rollen in de toolchain zijn. Anders dan bij `Kind` (waar een onbekende soort als
   regel van geen vorm zou doorgaan, een verkeerde vertaling) gaat een rol die een latere pin toevoegt met uchars eigen
   sleutel door, dus juist. Om de lijst in `docs/ENGINE.md` bij te houden pint
   `the_roles_are_the_keys_of_uchar` de zeventien sleutels van `Role.values()`: een pin met een nieuwe rol faalt daar,
   en wie hem bumpt, brengt de documentatie mee.
3. **`fold` in dezelfde vorm als `pointer`.** `pointer` is uchars pointer van een default zonder
   `/rules/prioritizedDefaults/<i>`, omdat een scope die plaats verschuift en de naam niet (`docs/ENGINE.md`, "The
   decider of a ground"). Voor de plaats van een vorm in haar lijst geldt hetzelfde. Daarom: uchars pointer
   (`RuleOrigin.fold()`, in het document dat de emitter van het model schrijft, `ir` van het antwoord) zonder
   `/rules/<lijst>/<i>`; wat overblijft ligt in de groep van `group` of, zonder `group`, in de vorm van `rule`, in welke
   lijst van de regellaag zij ook staat (zoals `rule` al gedocumenteerd is). Onder een default begint hij met de
   `pointer` van die default (`/defaults/2/justification/…`), onder een vorm met haar eigen sleutels
   (`/versions/0/value`); bij een regelversie die de stapel van een subtype opneemt (ch. 7.10) met
   `/versions/<v>/…` onder de vorm van `rule`. **Hoe de werkbank de knoop vindt:** de groep met `name == group` in
   `ir.rules.prioritizedDefaults`, anders het object met `name == rule` in een van de lijsten van `ir.rules`; daarop
   `fold` als JSON Pointer (RFC 6901); de tekst van de vouw is `source.snippet` van die knoop. De vertaling faalt luid
   (een 500, geen veld zonder waarde) als uchars pointer niet met `/rules/<lijst>/<i>/` begint, als de lijst
   `prioritizedDefaults` is zonder dat de vermelding `group` heeft (of omgekeerd), als het object op die plaats niet
   de naam van `group` of `rule` draagt, of als de pointer geen knoop van `ir` aanwijst. Overwogen: een absolute pointer
   in `ir`; afgewezen omdat de opdracht de vorm van `pointer` vraagt en die om de reden hierboven relatief is.
4. **`type` en `typeCanonical` zoals `name` en `nameCanonical`.** `type` is altijd een objecttype, dus een string
   volstaat; de vorm `{kind, owner, name}` van `generatedFrom` zou alleen `OBJECTTYPE` en `null` herhalen. In het
   vocabulaire van het antwoord via `Naming.name` (de benaming van het objecttype, anders zijn canonieke naam); de
   canonieke naam ernaast precies waar die verschilt, zoals bij `name`. `name` van een stapel blijft canoniek, want een
   stapelnaam is geen vorm en `Naming.form` laat haar staan; het type is wel een declaratie en wordt dus genoemd.
5. **Alleen bij een stapel.** Een versie (`versie`) heeft geen type; uchar geeft er ook geen. De toolchain eist dat
   `stackTypes` precies de stapels van `generated` een type geeft en faalt anders luid met de naam.
6. **`drl`, de dekking, de trace en het exec-model veranderen niet.** De meting toetst `drl` tegen
   `bin/regelspraak drl` (gelijk); `Playground` leest alleen.

Een stale stuk tekst mee rechtgezet: de javadoc van `Playground.generated` en `docs/ENGINE.md` noemden de sleutels van
`generated` nog `{naam, soort, gegenereerdUit}` en `naamCanoniek`; nu `{name, kind, type, generatedFrom}` en
`nameCanonical`, zoals de code ze sinds `engelse-namen` schrijft.

### Zonder DRL (c)

`generated` leest de bouw van de exec-model-tak (stap 3 van `analyze`), die geen DRL-tekst heeft (`build.drl()` is
null); de DRL-tekst wordt daarna apart gebouwd (stap 4). Het type komt dus uit de analyse en is er ook als de DRL-bouw
faalt. Met `drlError` blijft `drlRules` `[]`, zoals voorheen.

Wanneer dat samen voorkomt: `drlError` naast `executable: true` ontstaat alleen bij een DRL-tekst die de
Drools-compiler weigert (`DroolsBackend`, "de DRL-tekst van de weergave compileert niet", een fout van uchars
rendering). Een weigering van een vorm is er een van de analyse, en dan weigeren beide takken (gate 12 van uchar houdt
hun statusregels gelijk): `executable: false` en geen `generated`. Een bron met alleen de eerste soort is niet bekend
en wordt in een test niet nagebootst. Gepind:

- `the_type_of_a_stack_comes_from_the_analysis_without_a_drl_text`: de bouw van de exec-model-tak heeft geen DRL
  (`assertNull(build.drl())`), en `Playground.generated` daarop geeft de vier stapels met hun type, gelijk aan
  `generated` van `analyze`;
- `without_drl_the_list_is_empty` (uitgebreid): bij de weigering `heffing markert` is `drlRules` `[]`, en ook
  `executable` false en geen `generated`.

### Rood eerst (b) en (c)

`PlaygroundDrlRulesTest` op het eigen model van `drl-rule-provenance`, dat al een uitzonderingsgroep heeft (`waarde`,
met `zeer zware vondst` genest in de uitzonderingsgroep van `zware vondst`), een vouw in een voorwaarde
(`premie bij veel vondsten`: "het aantal vondsten van de Jutter") en een stapel over een subtype
(`defaults A:Strandjutter.premie`, met `strandpremie` als basis):

- `every_rule_of_the_drl_text_names_its_decider` (bestaand, verwachting uitgebreid): alle 25 DRL-regels met hun rol
  (15 `own`: de vijf gewone vormen en de tien eigen beslissingen in de stapels; `reconcile-agreement` bij
  `eens 0` en `eens wortel`; `fallback` bij `terugval 0`; `fold-contribution` bij de twee bijdragen; 5 zonder vorm
  zonder rol) en de twee vouwen: `totaalgewicht: bijdrage aan vouw 0` → `rule` `totaalgewicht`, `fold`
  `/versions/0/value`, en `defaults A:Jutter.premie: bijdrage aan vouw 1` → `/defaults/1/justification/left`. De
  verwachte pointers staan uit het IR van het model (de twee `Aggregation`-knopen op
  `/rules/rules/1/versions/0/value` en `/rules/prioritizedDefaults/0/defaults/1/justification/left`), niet uit het
  antwoord; de test zoekt elke vouw zoals de werkbank hem zoekt en pint haar `source.snippet`.
- `every_stack_of_generated_names_the_type_it_runs_for` (nieuw): de vier stapels met hun type, de stapel van een
  Strandjutter met `type` `Strandjutter` bij `generatedFrom` `ATTRIBUUT Jutter.premie`; en met een vocabulaire `cgm`
  waarin alleen Strandjutter een benaming heeft: `type` `STRANDJUTTER`, `typeCanonical` `Strandjutter`, de andere
  zonder `typeCanonical`.
- `the_type_of_a_stack_comes_from_the_analysis_without_a_drl_text` (nieuw, c).
- `both_routes_give_the_same_drl_rules` (uitgebreid): `via=service` geeft ook hetzelfde `generated`.
- `without_drl_the_list_is_empty` (uitgebreid, c).
- `the_roles_are_the_keys_of_uchar` (nieuw), zie boven.
- `over_the_corpus_every_fold_lies_in_the_group_or_form_of_its_entry` (nieuw): over elke golden van uchar
  (`ir.json`) die de DRL-teksttak bouwt: een rol precies bij een vorm, een vouw precies bij een vouwrol, en elke vouw,
  gevonden zoals de werkbank haar vindt, een `Aggregation` of `QuantifierFold`. Gemeten 282 goldens, 1437 vermeldingen
  met een rol, 291 met een vouw (de vloeren; uchars corpusgetuige telt dezelfde 291 vouwregels en 2124 − 687 = 1437
  regels met een vorm), ongeveer 14 s.

`PlaygroundGeneratedFromTest`, twee bestaande tests aangepast omdat de kaart het gedrag verandert dat zij toetsen:
`a_stack_in_the_coverage_is_in_generated_with_the_member_like_declared` pint de sleutels van een stapel nu als
`name, kind, type, generatedFrom` (was `name, kind, generatedFrom`); de corpusveeg
`every_golden_has_per_element_a_declaration_or_an_origin` eist daarnaast een `type` bij elke stapel en geen bij een
versie.

Op `e39328b4` (de pin, `Playground` ongewijzigd) met de tests van `d8bbc0cd`: rood, 6 failures van 13
(`werk/toolchain-2026-10-24/drl-rule-part-fields/logs/red-b-c.log`): de vier nieuwe of uitgebreide verwachtingen van
rol, vouw en type in `PlaygroundDrlRulesTest` en de twee van `PlaygroundGeneratedFromTest`. Na de wijziging groen, 13
van 13 (`logs/green-2.log`; `green-1.log` faalde op de test zelf: een vocabulaire `(standaard)` moet elke declaratie
benoemen, WF-119, dus het testvocabulaire is geen standaard).

### Meting (a)

`werk/toolchain-2026-10-24/drl-rule-part-fields/meting/`: `run-measure.sh` (kopie van die van `drl-rule-provenance`,
paden en commit aangepast, een derde en vierde verzoek erbij) neemt de bestanden van regels `ece012e` (`git archive`
naar `rules-ece012e/`, de werkbank niet aangeraakt), zet de bundel samen zoals de dienst haar leest (`bundle.rgs`),
start `bin/regelspraak serve` van de worktree met `PLAYGROUND=true --detail` en
`v2/herleving-ww/service/herleving.service.json`, post de bundel naar `/playground/analyse`, `?via=service`,
`?vocabulary=cgm` en `?vocabulary=cgm&via=service`, en toetst met `check-drl-rules.py` (uitgebreid). Log:
`measure.log` (toolchain `d8bbc0cd`, uchar `e3985673`). PASS:

1. `drl` heeft 339 regels, `drlRules` 339 vermeldingen, elk op de plaats en met de naam van haar kop.
2. Elke vermelding met een vorm heeft een rol uit de zeventien sleutels, de 162 zonder vorm geen. Per soort en rol:
   DEFAULT `own` 64, `fold-contribution` 32, `condition-fault` 9, `reconcile-agreement` 5, `fallback` 4,
   `fold-filter-fault` 2; FORM `own` 45, `fold-contribution` 12, `condition-fault` 1; STACK `reconcile-agreement` 3.
   Dat zijn de tellingen van de kaart (64 `default`, 32 `bijdrage aan vouw`, 9 `fout`, 8 `eens`, 4 `terugval`, 2
   `filterfout`), nu uit de rol en niet uit de naam. 46 vermeldingen hebben een vouw (42 `Aggregation`, 4
   `QuantifierFold`), elk op een knoop met een `source.snippet` in de groep of vorm van de vermelding; de 34 onder een
   groep beginnen met de `pointer` van hun default.
3. De variant `nieuw recht boven de herlevingsgrens art. 21 lid 2 onder a` (`herleving art. 21 lid 1`, `/defaults/2`)
   heeft drie vermeldingen: `…: default 2` met `own` in `defaults K:Recht op uitkering WW.indicatie herleving recht op
   uitkering` en in `defaults K:RechtOpUitkeringWwBijOntslag.indicatie herleving recht op uitkering`, en
   `…Recht op uitkering WW…: bijdrage aan vouw 17` met `fold-contribution` en `fold`
   `/defaults/2/justification/parts/3/left`. Op die pointer in de groep staat een `Aggregation` met de snippet "de
   maximale waarde van het maandloon van alle rechten van de samenloop van het Recht op uitkering WW die aan alle
   volgende voorwaarden voldoen: …", in de rechtvaardiging van de variant.
4. `generated` heeft 27 namen, alle 27 stapels, elk met een `type`, en het type is het declarerende type van het lid of
   een subtype ervan (uit `model.objectTypes[].supertype`); 12 stapels lopen voor een subtype. `defaults
   K:RechtOpUitkeringWwBijOntslag.indicatie herleving recht op uitkering` heeft `type` `RechtOpUitkeringWwBijOntslag`,
   `defaults K:Recht op uitkering WW.indicatie herleving recht op uitkering` `type` `Recht op uitkering WW`, met
   hetzelfde `generatedFrom` (`KENMERK RechtOpUitkering.indicatie herleving recht op uitkering`).
5. `?via=service` geeft dezelfde `drlRules` en `generated`.
6. `?vocabulary=cgm`: `drlRules` gelijk (canoniek); bij 20 van de 27 stapels heet het type in cgm anders, en daar staat
   `typeCanonical` (bijvoorbeeld `type` `Recht op uitkering`, `typeCanonical` `RechtOpUitkering`), bij de andere niet;
   elk type is de benaming in cgm uit `vocabularies.elements` of de canonieke naam. Via de dienst gelijk.
7. `drl` is gelijk aan `bin/regelspraak drl` over dezelfde bronnen (748 007 tekens).

### Suites en `make gates`

`make gates` op `d8bbc0cd` met `TAG/CLUSTER=drl-rule-part-fields`
(`werk/toolchain-2026-10-24/drl-rule-part-fields/logs/gates-1.log`, de uitvoer in `out/gates/`): GROEN, 7 stappen in
12m29s. Reactor 1672 (Drools) / 173 (toolchain) / 383 (dienst, waarvan 8 van `PlaygroundDrlRulesTest`, 4 meer dan
voorheen) / 53 (Quarkus), skip-pin PASS in elke module; tweelingen 3 (18 cases); replay 249 goldens, 673 cases (pin
249); TOKA-manifest 29 ok, 3 uitgesloten; image `decision-service:drl-rule-part-fields`; kind-smoke H2/H3/H4 PASS. Het
Result-commit raakt alleen deze kaart.

### Wat open bleef

- **`drlError` naast een gebouwde exec-model-tak** komt alleen voor bij een DRL-tekst die de Drools-compiler weigert;
  zo'n bron is niet bekend, dus (c) is getoetst op de bouw van de exec-model-tak zonder DRL, niet op een antwoord met
  `drlError` en `generated` samen (zie "Zonder DRL").
- **De pin staat op de kaarttak van uchar** (`e3985673`); de hoofdsessie zet hem vóór de landing op de landingscommit
  van uchar.
- **`reading`, `condition-deposit`, `no-evaluation-date`, de `uniqueness-`- en `allocation-`rollen en
  `fold-member-fault`/`fold-filter-deposit`** komen in v2 en in het eigen model niet voor; de corpusveeg ziet ze in
  uchars goldens (behalve `reading`, dat alleen in een bouw met lezingen bestaat, en de playground toont de DRL van een
  bouw zonder).
- **Een vouw die twee stapels lezen** heeft, zoals in `drl-rule-provenance`, haar bijdrageregels alleen bij de stapel
  die haar als eerste inschreef (v2: vouw 17 bij `Recht op uitkering WW`); de werkbank selecteert op `group` en
  `pointer` en vindt ze zo voor beide stapels.
