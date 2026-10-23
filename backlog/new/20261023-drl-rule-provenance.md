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

