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
