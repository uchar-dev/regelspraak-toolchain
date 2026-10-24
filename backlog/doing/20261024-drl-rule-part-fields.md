# drl-rule-part-fields: drlRules noemt de rol van elke regel, generated het type en de samenstelling van een stapel

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
- Elke stapel krijgt haar samenstelling uit uchar: de defaults in volgorde van voorrang over de groepen heen, met de
  nesting, in de sleutels van de beslisser van een grondslag (`group`, `pointer`, `position`, `variant`, of `rule`),
  zoals `drlRules` ze al noemt; en waar groepen zonder volgorde naast elkaar staan, dat ook. Gemeten op v2: in
  `defaults A:RechtOpUitkeringWwBijOntslag.herlevingstermijn` gaat de groep
  `herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag` voor op de basisregel; nu staat dat alleen in
  labels en in de volgorde van `drl`.
- `docs/ENGINE.md` (de velden van de playground: `drlRules`, `generated`) en de javadoc van `Playground.java` bij; de
  OpenAPI als die de velden noemt. De toolchain kiest de namen van de velden en de sleutels en zegt waarom; ze vertaalt
  wat uchar levert en voegt geen eigen indeling toe (`CLAUDE.md`). Daarbij: ENGINE.md en de javadoc van
  `Playground.generated` (r. ±569) noemen de velden van `generated` `{naam, soort, gegenereerdUit}` of
  `{naam, soort, generatedFrom}`, het antwoord geeft `{name, kind, generatedFrom}` (de werkbank vond dit 2026-10-24).
- `drl`, de coverage, de trace en het exec-model veranderen niet.

## Acceptatie

- (a) Op de bundel van v2/herleving-ww (fundament plus herleving-ww, regels `ece012e`): elke vermelding van `drlRules`
  met een vorm heeft een rol. De vermeldingen van de variant `nieuw recht boven de herlevingsgrens art. 21 lid 2
  onder a` (groep `herleving art. 21 lid 1`, pointer `/defaults/2`) zijn twee keer de eigen beslissing (in de stapel
  van `Recht op uitkering WW` en in die van `RechtOpUitkeringWwBijOntslag`) en één bijdrage aan een vouw; als de pointer
  van de vouw er is, wijst die naar de maximale waarde van het maandloon in haar rechtvaardiging. In `generated` heeft
  de stapel `defaults K:RechtOpUitkeringWwBijOntslag.indicatie herleving recht op uitkering` het type
  `RechtOpUitkeringWwBijOntslag` en die van `Recht op uitkering WW` het type `Recht op uitkering WW`. De samenstelling
  van `defaults A:RechtOpUitkeringWwBijOntslag.herlevingstermijn` noemt eerst de default van de groep `… voor
  RechtOpUitkeringWwBijOntslag` met daaronder zijn drie afwijkingen, dan de basisregel.
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
  `make check-pin`. De pin raakt alleen uchars backend en zijn getuigen; geen golden verandert. Sinds `1d68c408` staat
  de pin op `b0d5241c`, met de samenstelling (zie "De samenstelling (uitbreiding van 16:01)").
- `d8bbc0cd` `role` en `fold` in `drlRules`, `type` in `generated`: `Playground`, de tests, `docs/ENGINE.md`, README.
- dit Result, in het commit na `d8bbc0cd`.

### De velden

Alles komt uit `build.execModelTranspiler()`: `originOf(name)` (`RuleOrigin(form, kind, role, pointer, fold)`) voor
`drlRules`, `stacks()` voor `generated` (tot de pin op `b0d5241c` heette dat `stackTypes()`, met alleen het type).
Geen naam wordt gelezen; het label van een regelnaam (`default 2`,
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
| `type` | het objecttype waarvoor de stapel loopt, `stacks().get(name).type()`, genoemd als `naming.name(type)`: de benaming in het gekozen vocabulaire, anders de canonieke naam |
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
   `stacks()` precies bij de stapels van `generated` een vermelding heeft en faalt anders luid met de naam.
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
`measure-1.log` (toolchain `d8bbc0cd`, uchar `e3985673`; `measure.log` is sinds de uitbreiding de meting met de
samenstelling erbij, met dezelfde uitkomsten 1 tot en met 7). PASS:

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
- **De pin staat op de kaarttak van uchar** (sinds `1d68c408` op `b0d5241c`, daarvoor `e3985673`); de hoofdsessie zet
  hem vóór de landing op de landingscommit van uchar.
- **`reading`, `condition-deposit`, `no-evaluation-date`, de `uniqueness-`- en `allocation-`rollen en
  `fold-member-fault`/`fold-filter-deposit`** komen in v2 en in het eigen model niet voor; de corpusveeg ziet ze in
  uchars goldens (behalve `reading`, dat alleen in een bouw met lezingen bestaat, en de playground toont de DRL van een
  bouw zonder).
- **Een vouw die twee stapels lezen** heeft, zoals in `drl-rule-provenance`, haar bijdrageregels alleen bij de stapel
  die haar als eerste inschreef (v2: vouw 17 bij `Recht op uitkering WW`); de werkbank selecteert op `group` en
  `pointer` en vindt ze zo voor beide stapels.

### De samenstelling (uitbreiding van 16:01)

Om 16:01 breidde de werkbank de kaart uit met de samenstelling van een stapel (Doel, de bullet "Elke stapel krijgt haar
samenstelling", en acceptatie (a)); die tekst staat sinds `4e4728c1` in de kaart. Uchar bouwde daarvoor de uitbreiding
en veranderde na een advies van Fable de ingang: `TmsTranspiler.stacks()` (`Map<String, Stack>`, `record
Stack(Designatable type, StackComposition composition)`) in plaats van `stackTypes()` (uchar
`backlog/doing/20261024-drl-rule-part.md`, "De samenstelling (uitbreiding van 16:01)"). Gebouwd door een tweede
Opus-bouwer op `4e4728c1`:

- `1d68c408` pin uchar `b0d5241c` (de tip van de uchar-kaarttak `drl-rule-part`, niet op uchar main, niet gepusht), na
  `make check-pin`; in hetzelfde commit leest `Playground.generated` het type uit `stacks().get(name).type()`, want
  `stackTypes()` bestaat op die pin niet meer en anders compileert het commit niet. De kruiscontrole "type precies bij
  een stapel" is nu "een vermelding in `stacks()` precies bij een stapel".
- `0cb78d7d` `composition` in `generated`: `Playground`, de tests, `docs/ENGINE.md`, README "Contract change". De
  OpenAPI noemt de velden van de playground niet (`OpenApiEmitter` beschrijft alleen de ingangen van de dienst); daar
  veranderde niets.
- deze sectie, in het commit na `0cb78d7d`.

#### De velden

Per stapel van `generated`, na `generatedFrom`, het veld `composition`, uit `stacks().get(name).composition()`:

```json
{"name": "defaults A:RechtOpUitkeringWwBijOntslag.herlevingstermijn", "kind": "defaults",
 "type": "RechtOpUitkeringWwBijOntslag", "generatedFrom": {…},
 "composition": {"unordered": false, "chains": [[
   {"group": "herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag", "pointer": "/defaults/0",
    "position": 1, "generatedFrom": {"kind": "VORM", "owner": null, "name": "herlevingstermijn art. 21 lid 3 en 5",
                                     "form": "uitzondering-voor-subtype"},
    "exceptions": {"unordered": false, "chains": [[
      {"group": "… voor RechtOpUitkeringWwBijOntslag", "pointer": "/defaults/0/exceptions/members/0/defaults/0",
       "position": 1, "variant": "resterende uitkeringsduur art. 21 lid 4", "generatedFrom": {…}},
      {"group": "…", "pointer": "/defaults/0/exceptions/members/0/defaults/1", "position": 2,
       "variant": "herkrijgingstermijn na de startperiode art. 8 lid 3", "generatedFrom": {…},
       "exceptions": {"unordered": false, "chains": [[{… "variant": "halve maand na de startperiode art. 8 lid 3" …}]]}},
      {"group": "…", "pointer": "/defaults/0/exceptions/members/0/defaults/2", "position": 3,
       "variant": "herkrijgingstermijn na zelfstandig werk art. 8 lid 3", "generatedFrom": {…},
       "exceptions": {"unordered": false, "chains": [[{… "variant": "halve maand na zelfstandig werk art. 8 lid 3" …}]]}}]]}},
   {"group": "herlevingstermijn art. 21 lid 3 en 5", "pointer": "/defaults/0", "position": 1,
    "variant": "herlevingstermijn art. 21 lid 3 en 5"}]]}}
```

(uit `meting/analyse.json`, ingekort met `…`.)

| sleutel | waar | waarde |
|---|---|---|
| `composition` | na `generatedFrom`, bij elke stapel; een versie heeft er geen | `{unordered, chains}` |
| `chains` | in elke samenstelling | uchars `StackComposition.chains()`: de ketens aan de wortel, of onder een default zijn uitzonderingsgroepen, elk de defaults van hoog naar laag |
| `unordered` | in elke samenstelling, `true` of `false` | uchars `StackComposition.unordered()`: meer dan één keten, die naast elkaar staan |
| per default | in een keten | de sleutels van de beslisser van een grondslag uit `Verbaliser.decider(document, e.pointer())`, met dezelfde helper als `drlRules`: `group`, `pointer`, `position`, `variant` (waar de default een naam heeft), of `rule`; en `generatedFrom` waar uchars frontend de groep of vorm maakte |
| `exceptions` | na die sleutels, bij een default met uitzonderingsgroepen (uchars `Entry.exceptions()` niet null) | dezelfde vorm `{unordered, chains}`, één keten per uitzonderingsgroep |

Waarom zo:

1. **`composition`, `chains`, `unordered` en `exceptions` heten zoals uchars componenten** (`Stack.composition`,
   `StackComposition.chains`, `unordered()`, `Entry.exceptions`), om dezelfde reden als `role` en `fold`: een lezer
   van uchars Result en javadoc vindt ze terug, en de toolchain voegt geen eigen woord toe.
2. **Een default in precies de sleutels van `drlRules`**, met dezelfde helper (`Playground.decider`, die ook
   `generatedFrom` zet). Zo koppelt de werkbank een default van een samenstelling aan zijn DRL-regels op `group` en
   `pointer` (of `rule`), zonder vertaling; en `pointer` is relatief in de groep om de reden die "The decider of a
   ground" noemt (een scope verschuift de plaats van de groep, niet haar naam). Uchars ruwe pointer staat er niet bij:
   die vorm heeft `drlRules` ook niet.
3. **Alleen wat uchar levert.** Geen blok van ch. 7.10 (eigen uitzondering, geërfde, basis, initialisatie), geen
   niveau en geen plaats in de samengestelde keten: de volgorde van de lijst is de voorrang, en uchar geeft die
   indeling bewust niet ("Waarom zo" 2 van de uchar-kaart). `position` is die van de beslisser, de plaats in de keten
   van de groep in het IR, zoals in `drlRules` en een `GROUND`.
4. **`unordered` altijd, ook `false`.** Uchars vorm ter oriëntatie schrijft het zo, en een lezer hoeft geen standaard
   te kennen. Het is uchars `unordered()` (meer dan één keten) en niet de markering van het document; uchar legt die
   keuze vast als voorlopig besluit (een enkele keten is geordend, ook waar het document `unordered: true` draagt).
   Wie de markering van de auteur wil tonen, leest haar in het IR op de pointer.
5. **`exceptions` alleen waar uchar ze geeft**, zoals `variant` alleen staat waar een default een naam heeft.
6. **Na `generatedFrom`, als laatste sleutel.** Het is het geneste deel; de platte sleutels van de stapel staan eerst.
7. **Canoniek in elk vocabulaire**, zoals `drlRules` en `generatedFrom`: de sleutels zijn zoeksleutels in `ir` (een
   groepsnaam, een pointer), geen declaraties die `Naming` vertaalt.
8. **Luid falen.** Een gedragen stapel zonder samenstelling (uchar: alleen een geweigerde stapel heeft er geen, en
   dan weigert de backend het hele document), een pointer die niet op het document ligt (`Verbaliser.decider`), en een
   bouw zonder document zijn een 500, geen veld zonder waarde. `generated` krijgt daarvoor het document mee, zoals
   `drlRules`: `generated(build, ir, model, naming)`, met `Verbaliser.Document.of(IrLoader.loadWithPositions(ir))`.

Voorlopige besluiten (niet uit de kaart of de code te halen):

- **`generatedFrom` bij een default van een samenstelling.** De opdracht noemt `group`, `pointer`, `position`,
  `variant` of `rule`; `drlRules` zet bij dezelfde beslisser ook `generatedFrom` als uchars frontend de groep maakte
  (een groep `B voor S`, de groep van de opgave-marker). Ik nam de helper van `drlRules` over, zodat een default in
  beide velden dezelfde sleutels heeft. Afweging: het herhaalt `generatedFrom` bij elke default van zo'n groep; daar
  staat tegenover dat de werkbank niet hoeft te weten dat het ene veld een sleutel minder heeft dan het andere.
- **Een tweede bron in de test voor (b), niet het eigen model uitgebreid.** Het eigen model van `drl-rule-provenance`
  heeft een stapel over een subtype met een eigen basis (`strandpremie`), maar geen eigen uitzondering. Een
  `Afwijking van premie` erbij weigert uchars frontend: "afwijking 'seniorpremie' schrijft voor het subtype
  Strandjutter, en Strandjutter heeft een eigen basisregel 'strandpremie' voor 'premie', die 'premie' voor
  Strandjutter vervangt (RUNTIME-SEMANTICS ch. 7.10) — een eigen uitzondering van Strandjutter hangt onder die
  basisregel" (`logs/red-2.log`). `SOURCE_COMPOSED` is daarom het eigen model zonder `strandpremie`, met de
  `Afwijking van premie: seniorpremie` op Strandjutter (de vorm van v2: een groep `premie voor Strandjutter` met één
  groep-default en de afwijking in zijn uitzonderingsgroep) en met `vindersloon` op Vondst, twee groepen onder
  `Uitzonderingen ongeordend:`, zodat ook `unordered: true` gepind is. De pins van `drlRules` op het eigen model
  blijven zo ongewijzigd.

#### Rood eerst (b) en (c)

Eerst alleen de nieuwe signatuur van `generated` (met `ir`, nog ongebruikt), zodat de tests compileren; daarop de
tests van `0cb78d7d`: rood, 5 failures van 14 (`werk/toolchain-2026-10-24/drl-rule-part-fields/logs/red-3.log` voor
`PlaygroundDrlRulesTest`, 3 van 9; `logs/red-2.log` voor `PlaygroundGeneratedFromTest`, 2 van 5). Daarna de
samenstelling: groen, 14 van 14 (`logs/green-3.log`), met de verwachtingen zoals ze uit het model geschreven waren.

`PlaygroundDrlRulesTest`:

- `every_stack_of_generated_names_the_type_it_runs_for_and_its_composition` (was `…_the_type_it_runs_for`,
  verwachting uitgebreid): de vier stapels met hun samenstelling, geschreven uit het model en ch. 7.9/7.10: `Jutter`
  `[[jeugdpremie, premie bij veel vondsten, premie]]`; `Strandjutter` `[[jeugdpremie, premie bij veel vondsten,
  {rule: strandpremie}]]` (de geërfde uitzonderingen boven de eigen basis); `waarde` `[[zware vondst {exceptions
  [[zeer zware vondst]]}, waarde]]`; de opgave-groep met `generatedFrom`. Elke default van elke samenstelling wijst,
  gevonden zoals een lezer hem vindt, een `Default` met de naam van `variant` in het IR van het antwoord aan, met
  `exceptions` precies waar die ze heeft, of een vorm; `unordered` precies bij meer dan één keten. In het vocabulaire
  `cgm` dezelfde samenstellingen.
- `the_stack_of_a_subtype_has_its_own_exception_above_the_inherited_ones` (nieuw, b): op `SOURCE_COMPOSED` de vijf
  stapels; die van een Strandjutter is `[[{group: "premie voor Strandjutter", pointer: "/defaults/0", position: 1,
  generatedFrom: …, exceptions [[seniorpremie]]}, jeugdpremie, premie bij veel vondsten, premie]]`, de eigen
  uitzondering boven de geërfde uitzonderingen en de geërfde basis; die van `vindersloon` `{unordered: true, chains:
  [[vindersloon bij zware vondst], [vindersloon bij lichte vondst]]}`; de sleutels van een stapel in de volgorde
  `name, kind, type, generatedFrom, composition`.
- `the_type_and_composition_of_a_stack_come_from_the_analysis_without_a_drl_text` (was `the_type_of_a_stack_comes_…`,
  c): de bouw van de exec-model-tak heeft geen DRL (`assertNull(build.drl())`), en `generated` daarop geeft de vier
  stapels met type en samenstelling, gelijk aan `generated` van `analyze`.
- `both_routes_give_the_same_drl_rules` ongewijzigd: `via=service` geeft hetzelfde `generated`, de samenstelling dus
  ook.

`PlaygroundGeneratedFromTest`, twee tests aangepast omdat de kaart het gedrag verandert dat zij toetsen:
`a_stack_in_the_coverage_is_in_generated_with_the_member_like_declared` pint de sleutels van een stapel nu als
`name, kind, type, generatedFrom, composition`; de corpusveeg `every_golden_has_per_element_a_declaration_or_an_origin`
eist een `composition` bij elke stapel en geen bij een versie (rood: 41 stapels van het corpus zonder).

#### Meting (a)

`meting/check-drl-rules.py` heeft een stap 8 (`check-drl-rules-1.py.bak` is het script van de eerste bouwer);
`run-measure.sh` is op twee commentaarregels na ongewijzigd. Log: `meting/measure.log` (toolchain `0cb78d7d`, uchar `b0d5241c`, regels
`ece012e`). PASS; stap 1 tot en met 7 met dezelfde uitkomsten als in `measure-1.log` (zelfde tellingen per rol, 27
stapels, `drl` gelijk aan `bin/regelspraak drl`, 748 007 tekens). Stap 8:

- elke stapel van `generated` heeft een `composition`, een versie geen: 27 stapels, 69 defaults, waarvan 8 onder een
  default; 5 defaults met uitzonderingsgroepen, elk met zoveel ketens als het IR leden; `unordered` overal `false`
  (v2 heeft geen ketens naast elkaar); één stapel over meer dan één groep;
- elke default wijst, gevonden zoals een lezer hem vindt (de groep van `group` bij naam, daarin `pointer`), een
  `Default` in het IR van het antwoord aan met de naam van `variant`, en heeft `exceptions` precies waar die Default
  ze heeft; geen regelversie in v2; elke default staat één keer in zijn samenstelling;
- elke vermelding van `drlRules` met een stapel en een default (116) noemt een default die in de samenstelling van
  die stapel staat;
- `defaults A:RechtOpUitkeringWwBijOntslag.herlevingstermijn` is één keten: eerst de default van de groep
  `herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag` (zonder `variant`, de groep-default) met in
  `exceptions` één keten van drie afwijkingen (`resterende uitkeringsduur art. 21 lid 4`; `herkrijgingstermijn na de
  startperiode art. 8 lid 3` met daaronder zijn eigen uitzondering `halve maand na de startperiode art. 8 lid 3`;
  `herkrijgingstermijn na zelfstandig werk art. 8 lid 3` met daaronder `halve maand na zelfstandig werk art. 8 lid 3`),
  dan de basisregel, de default `/defaults/0` van de groep `herlevingstermijn art. 21 lid 3 en 5`. Uchar verwachtte
  een eigen uitzonderingsgroep alleen bij de middelste afwijking; gemeten hebben de tweede en de derde er elk een, zoals
  het IR van het antwoord ze heeft (stap 8 toetst `exceptions` tegen het IR);
- `?vocabulary=cgm`, `?via=service` en beide samen: dezelfde samenstelling per stapel, canoniek.

#### Suites en `make gates`

`make gates` op `0cb78d7d` met `TAG/CLUSTER=drl-rule-part-fields`
(`werk/toolchain-2026-10-24/drl-rule-part-fields/logs/gates-2.log`, de uitvoer in `out/gates/`): GROEN, 7 stappen in
12m10s. Reactor 1684 (Drools, de pin: 12 meer dan op `e3985673`) / 173 (toolchain) / 384 (dienst, één meer: de nieuwe
test van (b)) / 53 (Quarkus), skip-pin PASS in elke module; tweelingen 3 (18 cases); replay 249 goldens, 673 cases (pin
249); TOKA-manifest 29 ok, 3 uitgesloten; image `decision-service:drl-rule-part-fields`; kind-smoke H2/H3/H4 PASS. Het
Result-commit raakt alleen deze kaart.

#### Wat open bleef

- **De pin staat op `b0d5241c`, de tip van de uchar-kaarttak** (uchars gate-run liep toen nog); de hoofdsessie zet hem
  vóór de landing op de landingscommit van uchar.
- **`unordered: true` en een regelversie in een samenstelling** komen in v2 niet voor; ze zijn gepind in de eigen test
  (`vindersloon`, `strandpremie`), en uchars `StackCompositionCorpusWitnessTest` ziet ze in het corpus.
- **Twee keer het document laden per antwoord**: `drlRules` en `generated` lezen elk `IrLoader.loadWithPositions(ir)`.
  Eén keer laden in `analyze` en doorgeven scheelt een lading, maar verandert twee signaturen voor geen gedrag; niet
  gedaan.
