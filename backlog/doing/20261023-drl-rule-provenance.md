# drl-rule-provenance: de playground zegt bij elke DRL-regel van welke regel of variant hij is

*Voorstel van de werkbank (2026-10-23), met akkoord van de eigenaar door de werkbank-sessie in deze backlog gezet
(niet gecommit). Vervolg op `explanation-ground-source` (de beslisser in `GROUND`).*

## Aanleiding

De werkbank toont op de pagina van een definitie de verlaagde DRL ("Verlaagde DRL"). Zij zoekt de regels in het veld
`drl` van `/playground/analyse` (`Playground.java` r. ±224: `UcharBuild.assemble(model, DroolsBackend.Branch.DRL_TEXT).drl()`)
op naam. Voor een gewone regel werkt dat: de DRL-regel draagt de naam van de regel.

Sinds werkbank `0ffcef2` is een variant (`Uitzondering op …`, `Afwijking van …`) een eigen definitie op de
regelsetpagina. Haar DRL heet naar de stapel en een volgnummer, niet naar de variant: uchar `TmsTranspiler` r. ±3044,
`name + ": default " + k.label()`. Gemeten op v2 (regels `3a6e920`, toolchain `b014d409`, de bundel fundament plus
herleving-ww):

- de groep `herleving art. 21 lid 1` (IR `rules.prioritizedDefaults`, vijf defaults) wordt
  `defaults K:Recht op uitkering WW.indicatie herleving recht op uitkering: default 0` tot en met `default 4`, in de
  volgorde van de groep;
- de uitzondering `nieuw recht boven de herlevingsgrens art. 21 lid 2 onder a` is `default 2` (de enige van de vijf die
  de herlevingsgrens leest);
- de stapel heeft daarnaast `…: bijdrage aan vouw 16` tot en met `22`; uit de naam is niet af te lezen bij welke
  variant zo'n regel hoort.

Voor een vlakke groep kan de werkbank het volgnummer afleiden uit de plaats van de variant in de IR. Voor een stapel
over subtypen (uchar RUNTIME-SEMANTICS ch. 7.10) en voor geneste uitzonderingen (in v2 onder meer
`herleving na het vervallen art. 21 lid 1`, `herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag` en
`duurverlenging onder a art. 42b lid 1`) zou zij de nummering moeten raden. Dat doet zij niet.

## Doel

- Het antwoord van `/playground/analyse` (beide routes) krijgt naast `drl` een veld (bijvoorbeeld `drlRules`) met,
  per regel in `drl` en in dezelfde volgorde:
  - `name`: precies de naam na `rule "…"`;
  - de beslisser in de termen van `GROUND` (`docs/ENGINE.md`, "The decider of a ground"): `rule` voor een vorm, of
    `group`, `pointer` en `variant` (waar zij een naam heeft) voor een default, en `position` als dat helpt;
  - ook voor de hulpregels van een regel of variant (de bijdragen aan een vouw), met de beslisser waartoe zij horen;
  - voor een regel die de toolchain zelf maakt (de opgavemarkering: `afwijking opgave …`, `controle opgave …`) wat haar
    maakte, zoals `generated` en `generatedFrom` dat al noemen.

  De toolchain kiest de precieze sleutels en zegt waarom. Het transpileerplan van uchar kent de default al
  (`Node k`, `k.position()`, `k.d()`); of de herkomst daarvoor een uchar-kaart vraagt, beslist de toolchain-sessie.
- Als optie, voor een lezer van de DRL: dezelfde herkomst als commentaarregel onder de kop `rule "…"` in `drl`, zonder
  het gedrag van de DRL te veranderen. Zonder die optie blijft `drl` bytegelijk; de exec-model-tak verandert niet.
- `docs/ENGINE.md` (de velden van de playground) en de javadoc van `Playground.java` bij; de OpenAPI als de playground
  er een heeft.

## Acceptatie

- (a) Op de bundel van v2/herleving-ww (fundament plus herleving-ww, regels `3a6e920`): elke regel in `drl` heeft precies
  één vermelding in het nieuwe veld, in dezelfde volgorde en met dezelfde naam; elke vermelding van een default wijst
  (groep en pointer) een default aan die in de IR bestaat. De vermelding van `…: default 2` van de groep
  `herleving art. 21 lid 1` noemt de variant `nieuw recht boven de herlevingsgrens art. 21 lid 2 onder a`, en de
  bijdragen aan de vouw van die variant wijzen naar dezelfde.
- (b) Een eigen klein model met een vlakke groep, een geneste uitzondering, een stapel over een subtype en een regel met
  een vouw: elke DRL-regel met zijn beslisser.
- (c) Zonder DRL (`drlError`): wat het veld dan is (leeg of afwezig), en waarom.
- (d) `make gates` groen.

## Daarna, in de werkbank (geen deel van deze kaart)

De kaart "Verlaagde DRL" toont op de pagina van een variant precies haar DRL-regels: de regel van haar default en de
bijdragen aan haar vouwen. Op de pagina van een gewone regel selecteert de werkbank op `rule` in plaats van op het
begin van de naam.

## Besluit van de toolchain-sessie (2026-10-23)

De herkomst komt uit uchar, in een eigen uchar-kaart (`drl-rule-origin`) die eerst landt; daarna een pin-bump en deze
kaart. Reden: `TmsTranspiler.formOf` geeft per regelnaam de vorm, maar niet welke default van een groep een regel
verlaagt; de namen dragen het eigen volgnummer van de lowering (`default <label>`), en een bijdrage aan een vouw noemt
haar default niet. De toolchain zou die nummering moeten nabouwen, en zij heeft geen semantiek van zichzelf
(`CLAUDE.md`). uchar levert per regel van het model de vorm en, waar de regel bij een default hoort, de pointer van die
default, ook voor de hulpregels; de toolchain vertaalt dat naar de sleutels van `GROUND`.


## Result (2026-10-23)

Tak `card/drl-rule-provenance` in de worktree `wt-tc/drl-rule-provenance`, vanaf toolchain main `64485601`. Gebouwd door
een Opus-bouwer; de landing doet de hoofdsessie.

- `7bdcb8a9` pin uchar `f22c9ff5` (uchar main met `drl-rule-origin`), na `make check-pin`. Verder brengt de pin drie
  anchor-goldens zonder slotpunt in hun snippets; de set van gate 12 blijft 274.
- `6e0c8f62` `drlRules` naast `drl`: `Playground`, `Verbaliser.decider`, de test, `docs/ENGINE.md`, README.
- dit Result, in het commit na `6e0c8f62`.

### De sleutels

Per regel van `drl`, in de volgorde van de koppen `rule "…"`, één object. Wat erin staat volgt uit
`build.execModelTranspiler().originOf(name)` (`RuleOrigin(form, kind, pointer)`):

| `originOf` | sleutels |
|---|---|
| `FORM` | `name`, `rule` = `RegelSpraakModel.formName(form)` |
| `DEFAULT`, pointer `/rules/prioritizedDefaults/…` | `name`, `stack` = `form`, `group`, `pointer`, `position`, `variant` (waar de default een naam heeft) |
| `DEFAULT`, pointer `/rules/rules/f/versions/v` (ch. 7.10) | `name`, `stack` = `form`, `rule` |
| `STACK` | `name`, `stack` = `form` |
| `NO_FORM` | `name` |

met `generatedFrom` (`{kind, owner, name, form}`) erbij waar uchars frontend de vorm van `rule` of de groep van `group`
maakte. Voorbeelden uit de meting (a):

```json
{"name": "defaults K:Recht op uitkering WW.indicatie herleving recht op uitkering: bijdrage aan vouw 17",
 "stack": "defaults K:Recht op uitkering WW.indicatie herleving recht op uitkering",
 "group": "herleving art. 21 lid 1", "pointer": "/defaults/2", "position": 3,
 "variant": "nieuw recht boven de herlevingsgrens art. 21 lid 2 onder a"}
{"name": "defaults A:RechtOpUitkeringWwBijOntslag.herlevingstermijn: eens wortel",
 "stack": "defaults A:RechtOpUitkeringWwBijOntslag.herlevingstermijn"}
```

Waarom zo:

1. **`name` onge-escapet.** Het is de naam van de regel zelf: die van de KieBase (`Rule.getName()`), die `originOf`
   verwacht, en die de dekking en het spoor van een scenario noemen. JSON draagt elk teken; de escape van de kop is de
   syntaxis van een DRL-stringliteral (`Namen.drlString`: `\\`, `\"`, `\r`, `\n`), geen deel van de naam. Wie de kop in
   `drl` zoekt, vindt hem op dezelfde plaats (vermelding k hoort bij kop k) of schrijft de vier escapes; de test pint
   dat `"rule " + Namen.drlString(name)` per plaats de kop is. De toolchain leest de koppen zelf
   (`Playground.drlRuleNames`: kop alleen op zijn regel, de vier escapes ongedaan, elke andere escape of een regel die
   met `rule "` begint en geen kop is faalt luid) en houdt de namen als multiset gelijk aan die van de KieBase van die
   tekst, zodat een kop die het lezen mist een fout is en geen ontbrekende vermelding.
2. **De beslisser via dezelfde vertaling als `GROUND`.** `Verbaliser.decider(Document, pointer)` is nieuw en publiek;
   `variantAt`, `ruleVariant`, `unresolved` en `deciderOf` zijn uit `DefaultsExplanation` naar statische methoden van
   `Verbaliser` verhuisd (zij lazen alleen het document; `variant(String)` van de uitleg roept `variantAt`). Zo noemen
   uitleg en playground een default op één manier: `group` bij naam, `pointer` zonder `/rules/prioritizedDefaults/<i>`,
   `position` 1-gebaseerd, `variant` waar hij een naam heeft, en een regelversie bij `rule`. Geen eigen nummering: het
   label van een regelnaam (`default 2`, `bijdrage aan vouw 17`) wordt nergens gelezen. Het document is `ir` van het
   antwoord, met posities geladen (`IrLoader.loadWithPositions`), het document waaruit de DRL-build gebouwd is.
3. **`stack` bij elke regel van een effectieve stapel**, ook bij een default. Het is de vorm die `originOf` noemt, en
   zonder die sleutel gaat informatie verloren: een groep op een supertype staat in de stapel van elk concreet subtype
   (v2: `herleving art. 21 lid 1` in `defaults K:Recht op uitkering WW…` en in `defaults K:RechtOpUitkeringWwBijOntslag…`,
   met dezelfde `group` en `pointer`), en een gewone regel die de stapel van een subtype opneemt heeft `rule` zoals
   haar eigen regel (eigen test: `strandpremie` en `defaults A:Strandjutter.premie: default 2`). Gespeld zoals de dekking
   en `generated` de stapel noemen, zodat de werkbank haar daar vindt met haar `generatedFrom`.
4. **`STACK`: alleen `stack`.** Zo'n regel verlaagt geen default en, bij een stapel uit groepen van meer typen, geen
   enkele groep (uchar-Result); een groep erbij zou gegokt zijn.
5. **`NO_FORM`: alleen `name`**, zoals een zin zonder `GROUND` geen beslisser heeft.
6. **Geen sleutel voor de soort.** De sleutels onderscheiden de vier soorten al: `rule` zonder `stack` is `FORM`,
   `stack` met `group` of `rule` is `DEFAULT`, `stack` alleen is `STACK`, niets is `NO_FORM`.
7. **`generatedFrom`** zoals `vocabularies.elements[].generatedFrom`, uit `RegelSpraakModel.generatedFrom` op de vorm van
   `rule` of `group`: de opgavemarkering (`afwijking opgave X`, `controle opgave X`, de groep `opgave boven berekening X`
   met haar twee defaults zonder naam), en zo ook een groep `B voor S` en een beslistabelrij. De herkomst van de stapel
   zelf staat al in `generated` en wordt niet herhaald.
8. **Canoniek**, zoals `drl` en `ir`, ook met `?vocabulary=`: de DRL is van uchar.
9. **`drlRules` wordt na de `catch` van de DRL-build gelezen**, zodat een fout van het lezen zelf (een pointer die niet
   oplost, een kop die niet past) een 500 is en geen `drlError`.

De commentaarregel onder de kop (optie van de kaart) is niet gedaan: `drl` is uchars tekst, en een regel erbij betekent
die tekst in de toolchain herschrijven of uchars rendering veranderen; de kaart eist `drl` bytegelijk. `drlRules` draagt
dezelfde herkomst ernaast. De playground staat niet in het OpenAPI-document (`OpenApiEmitter` beschrijft de
beslisroutes), dus geen OpenAPI-wijziging.

Een zin die nu anders luidt: de foutmelding van een pointer die niet op het document terug te leggen is ("de pointer …
van de motor is niet op het document terug te leggen: …", was "de uitleg legt de pointer … niet op het document terug:
…"), omdat de playground dezelfde methode gebruikt. Zij treedt alleen op bij een pointer die niet op het document past;
geen test pinde haar.

### Zonder DRL (c)

Met `drlError` is `drlRules` een lege lijst, `[]`. Waarom: `drl` is dan `""` (bestaand contract), en een lege tekst
heeft geen regels; zo geldt "één vermelding per kop van `drl`" zonder uitzondering, en een lezer hoeft niet op
afwezigheid te toetsen; `drlError` zegt waarom er geen tekst is. Bij een parse- of resolverfout ontbreken `drl` en
`drlRules` allebei (geen document). Gepind in `PlaygroundDrlRulesTest.without_drl_the_list_is_empty` (de bron van
`PlaygroundTest` die uchar niet draagt).

### Rood eerst (b) en (c)

`PlaygroundDrlRulesTest` (4 tests), geschreven vóór de wijziging van `Playground`, op een eigen model: de vlakke groep
`premie` op Jutter (basis en twee uitzonderingen aan de wortel; `premie bij veel vondsten` leest de vouw "het aantal
vondsten"), de gewone regel `strandpremie` op het subtype Strandjutter die de stapel van een Strandjutter als basis
opneemt, de groep `waarde` op Vondst met `zeer zware vondst` genest in de uitzonderingsgroep van `zware vondst`, de
gewone regel `totaalgewicht` met een vouw, en de meldingsdatum met `(mag opgegeven worden)`. Het model heeft regels van
alle vier soorten.

- `every_rule_of_the_drl_text_names_its_decider`: alle 25 DRL-regels met hun beslisser gepind: 6 `FORM` (waarvan 2
  van de opgavemarkering met `generatedFrom`, en `totaalgewicht: bijdrage aan vouw 0`); 13 `DEFAULT` (waarvan 1
  regelversie, 4 van de groep met de geneste uitzondering: de geneste default, `eens 0` en `terugval 0` van de
  uitzonderingsgroep van `zware vondst`, en `waarde`; 2 van de opgavegroep; en `defaults A:Jutter.premie: bijdrage aan
  vouw 1` naar `premie bij veel vondsten`); 1 `STACK`, `eens wortel`; 5 `NO_FORM`. Elke kop is
  `"rule " + Namen.drlString(name)` op dezelfde plaats; elke pointer wijst in de groep van het IR van het antwoord een
  default met die naam aan. De verwachte waarden staan uit het IR van het model (groepen en defaults op hun pointer),
  niet uit het antwoord.
- `both_routes_give_the_same_drl_rules`: `via=service` (een kern met `DETAIL` over dezelfde bron) geeft dezelfde lijst.
- `without_drl_the_list_is_empty` (c).
- `a_header_is_read_back_as_the_name_it_escapes`: de vier escapes, een functie vóór de kop, en twee koppen die
  falen.

Op `7bdcb8a9` met de verhuizing in `Verbaliser` en een `drlRuleNames` die gooit: rood op alle vier (1 failure, 3
errors; `werk/toolchain-2026-10-23/drl-rule-provenance/logs/red-b-c.log`). Na de wijziging groen, samen met de
bestaande tests van de playground en de uitleg (118 tests, `logs/green-1.log`).

### Meting (a)

`werk/toolchain-2026-10-23/drl-rule-provenance/meting/`: `run-measure.sh` neemt de bestanden van regels `3a6e920`
(`git archive` naar `rules-3a6e920/`, de werkbank niet aangeraakt), zet de bundel samen zoals de dienst haar leest
(fundament dan herleving-ww, elk bestand gesorteerd en gevolgd door een newline: `bundle.rgs`), start
`bin/regelspraak serve` van de worktree met `PLAYGROUND=true --detail` en `v2/herleving-ww/service/herleving.service.json`,
post de bundel naar `/playground/analyse` en naar `?via=service`, en toetst met `check-drl-rules.py`. Log:
`measure.log` (toolchain `6e0c8f62`, uchar `f22c9ff5`). PASS:

1. `drl` heeft 341 regels, `drlRules` 341 vermeldingen, elk op de plaats en met de naam van haar kop (de kop door het
   script zelf onge-escapet).
2. Per soort: 116 defaults van een groep, 58 `FORM`, 164 `NO_FORM`, 3 `STACK` (de `eens wortel` van drie stapels), 0
   regelversies in een stapel; 45 met `generatedFrom` (32 `opgave-boven-berekening`, 13 `uitzondering-voor-subtype`);
   27 stapels. De groepsnamen van het IR zijn uniek; elke vermelding met `group` en `pointer` wijst een `Default` van
   die groep aan, met `variant` gelijk aan zijn naam en `position` gelijk aan de laatste index plus één; samen 51
   verschillende defaults, alle 51 van de 20 groepen. 18 vermeldingen hebben een geneste pointer, in de groepen
   `duurverlenging onder a art. 42b lid 1`, `herleving na het vervallen art. 21 lid 1` en
   `herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag`. Elke `rule` is een vorm van het IR.
3. `…: default 2` van de groep `herleving art. 21 lid 1` staat in twee stapels (`defaults K:Recht op uitkering
   WW.indicatie herleving recht op uitkering` en `defaults K:RechtOpUitkeringWwBijOntslag.indicatie herleving recht op
   uitkering`); beide noemen `pointer` `/defaults/2`, `position` 3 en de variant `nieuw recht boven de herlevingsgrens
   art. 21 lid 2 onder a`. De bijdragen 16 tot en met 22 (alleen in de eerste stapel: een vouw krijgt haar
   bijdrageregels bij de stapel die haar als eerste inschreef) wijzen: 16 naar `/defaults/0` (`nieuw recht op grond van
   de WIA …`), 17 naar `/defaults/2`, de variant, en 18 tot en met 22 naar `/defaults/3` (`herlevingstermijn verstreken
   …`). Onafhankelijk uit het IR (de knopen `Aggregation`, `QuantifierFold` en `Fold` in de eigen rechtvaardiging en het
   gevolg van elke default): de variant heeft één vouw, `/defaults/0` één, `/defaults/3` zeven, de andere twee geen;
   elke bijdrage wijst een default met een vouw aan en elke default met een vouw heeft een bijdrage.
4. `?via=service` geeft dezelfde `drlRules`.
5. `drl` is gelijk aan `bin/regelspraak drl` over dezelfde bronnen (751 158 tekens).

### Suites en `make gates`

`make gates` op `6e0c8f62` met `TAG/CLUSTER=drl-rule-provenance`
(`werk/toolchain-2026-10-23/drl-rule-provenance/logs/gates.log`): GROEN, 7 stappen in 12m28s. Reactor 1662 (Drools) /
173 (toolchain) / 379 (dienst, waarvan 4 van `PlaygroundDrlRulesTest`) / 53 (Quarkus), skip-pin PASS; tweelingen 3
(18 cases); replay 244 goldens, 656 cases (pin 244); TOKA-manifest 29 ok, 3 uitgesloten; image
`decision-service:drl-rule-provenance`; kind-smoke H2/H3/H4 PASS. Het Result-commit raakt alleen deze kaart.

### Wat open bleef

- **Een regelversie in een stapel** komt in v2 niet voor; alleen de eigen test (`strandpremie`) toetst die vorm.
- **De bijdragen van een vouw die twee stapels lezen** staan alleen bij de stapel die haar als eerste inschreef (v2:
  vouw 17 bij `defaults K:Recht op uitkering WW…`, niet bij die van `RechtOpUitkeringWwBijOntslag`). Dat is uchars
  verlaging; de werkbank die de DRL van een variant toont, selecteert op `group` en `pointer` en vindt ze zo in beide
  gevallen.
- **`group: null`** (een document dat WF-107 breekt) is niet getoetst, zoals bij `GROUND`.
