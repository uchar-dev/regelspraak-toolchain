# scopetoets-overige-vormen: de scopetoets bij het starten geldt ook voor objectcreaties, verdelingen, consistenties, uniciteiten en feitcreaties

*Vastgelegd op 2026-10-13, bij de uitvoering van het besluit van de eigenaar over TOKA (C6-b): de rest van
`examples/toka*` is een gewoon projectbestand, ook `examples/toka/toka.service.json`. Open punt 2 van de kaart
`20260930-dienst-scope-defaultsgroepen`. Welke weg de kaart neemt, beslist de eigenaar vóór de bouw (zie "Besluit
vooraf").*

## Aanleiding

Bij het starten toetst `DecisionCore.scopeCompleteness` per entrypoint de vormen in de scope: leest een vorm een
attribuut of kenmerk dat alleen vormen buiten de scope schrijven, dan weigert de dienst de start (`INCOMPLETE_SCOPE`),
tenzij het entrypoint dat element in `inputElements` noemt of `scopeCheckOptOut` zet. De toets geldt voor regels en
defaultsgroepen. Objectcreaties, verdelingen, consistenties, uniciteiten en feitcreaties slaat hij over
(`case OBJECT_CREATION, ALLOCATION, CONSISTENCY, UNIQUENESS, FACT_CREATION -> null`). Het commentaar daarbij geeft als reden
dat vijf entrypoints van `examples/toka/toka.service.json` een verdeling scopen zonder de schrijvers van wat zij leest.

Gemeten in die kaart (2026-10-01; niet opnieuw gemeten):
- Een consistentie in de scope leest `bergdag`, en alleen een regel buiten de scope schrijft `bergdag` (reproductie
  x01d). De dienst start zonder melding en meldt J1 en J2 inconsistent, waar de volle scope geen inconsistentie geeft.
- Een proef met de toets over alle soorten (niet gecommit): `examples/toka/toka.service.json` start niet meer, met 7
  schendingen in 5 entrypoints, alle zeven bij een verdeling, en zes testklassen van de dienst worden rood.

Open punt 2 (`backlog/done/20260930-dienst-scope-defaultsgroepen.md:366-371`, citaat): "die descriptor ligt in de
pariteitszone: een keuze van de eigenaar (`inputElements` daar, of een uitzondering voor verdelingen, of de toets
beperkt laten)." Sinds C6-b mag de descriptor veranderen. De eerste optie vraagt daardoor geen uitzondering op een
zoneregel meer.

## Besluit vooraf (eigenaar)

Een van drie:
1. De toets geldt voor alle soorten; de TOKA-descriptor noemt de gelezen elementen in `inputElements` van de
   entrypoints met een verdeling.
2. De toets geldt voor alle soorten behalve verdelingen.
3. De toets blijft bij regels en defaultsgroepen; `README.md` en `docs/ENGINE.md` zeggen welke soorten hij niet toetst
   en waarom.

## Voorlopig besluit (Fable-advies 2026-11-06)

*Voorlopig: het aanbevolen antwoord van een Fable-advies, gebouwd en geland; de eigenaar kan het omdraaien. Het volledige
advies: `uchar-dev/werk/norm-advice-2026-11-06/ADVIES.md`, vraag B.*

Optie 1. De scopetoets geldt voor elke vorm die een scope draagt, onder dezelfde drie voorwaarden, en de schending noemt
de lezer met zijn soort (`objectcreatie`, `verdeling`, `consistentie`, `uniciteit`, `feitcreatie`, naast `regel` en
`defaults-groep`). Een verdeling leest haar volgorde-, verhoudings- en maximumattributen zoals een regel haar operanden
(`ProducerConsumer.forms` geeft voor elke soort dezelfde `reads`); met de schrijver buiten de scope verdeelt de
gescopete build op een leeg attribuut, dezelfde klasse als x01d. De vijf verdelings-entrypoints van
`examples/toka/toka.service.json` declareren wat de TOKA-scenario's daar al als invoer geven:
`"inputElements": ["Natuurlijk persoon.woonregio factor"]` op `verdeling treinmiles op basis van woonregio factor`, `…
en met maximum waarde` en `… met afronding`; `["Natuurlijk persoon.leeftijd", "Natuurlijk persoon.woonregio factor"]`
op `Verdeling treinmiles op basis van leeftijd en woonregio factor` en `… leeftijd, woonregio factor, met maximum waarde
en afronding`. Controle: zonder die declaraties start de dienst niet en noemen de zeven schendingen precies deze
elementen. Docs: `README.md` (r. ~430–433) en `docs/ENGINE.md` (r. ~92–108) noemen alle soorten, de slotzin "The other
forms a scope can carry … are not checked" en het TOKA-commentaar op r. ~537–538 van `DecisionCore.java` vervallen.
Niet veranderen: `examples/toka/*.rgs`, de uitkomsten van de TOKA-scenario's en van de golden-tests van de dienst, de
bestaande schendingszinnen voor regels en groepen.

## Wat er gebeurt

Bij 1 of 2 toetst `scopeCompleteness` de gekozen soorten onder dezelfde drie voorwaarden als een regel: er is een
schrijver, geen schrijver staat in de scope, en het element staat niet in `inputElements`. De schending noemt de lezer
met zijn soort en naam. Bij 1 krijgt `examples/toka/toka.service.json` de `inputElements` die de toets vraagt. Bij 3
veranderen alleen de docs.

## Acceptatie

- (a) Bij 1 of 2, rood eerst: een getuige met een consistentie in de scope die een element leest dat alleen buiten de
  scope geschreven wordt (de vorm van x01d). Eerst start de dienst; daarna weigert hij de start en noemt hij de lezer.
- (b) Bij 1: de TOKA-descriptor start, en de gouden TOKA-scenario's en de golden-tests van de dienst geven dezelfde
  uitkomsten als vóór de kaart.
- (c) De opt-outs `inputElements` en `scopeCheckOptOut` werken ook voor de nieuw getoetste soorten.
- (d) `make gates` groen.

## Omvang

Klein tot middel. May touch: `service/` (`DecisionCore`, `Violation`, de tests), `service-quarkus/` voor de pariteit,
`examples/toka/toka.service.json`, `README.md` en `docs/ENGINE.md`. Niet: `examples/toka/*.rgs` en `uchar/`.

## Result

*Gebouwd op 2026-11-06 in de branch `card/scope-check-and-cleanup` (van toolchain `main` d2064ab5f, pin uchar
50f179535). Het besluit is **voorlopig**: optie 1 uit het Fable-advies van 2026-11-06 (`werk/norm-advice-2026-11-06/ADVIES.md`,
vraag B); de eigenaar kan het omdraaien. Logs in `uchar-dev/werk/toolchain-cleanup/logs/`.*

`DecisionCore.scopeCompleteness` toetst nu elke soort die `ProducerConsumer.forms` geeft, onder dezelfde drie
voorwaarden; de schending noemt de lezer met zijn soort (`objectcreatie`, `verdeling`, `consistentie`, `uniciteit`,
`feitcreatie`, naast `regel` en `defaults-groep`). De zinnen voor regels en groepen zijn ongewijzigd.

- (a) Rood eerst: `ScopeCompletenessTest.a_consistency_reading_what_only_a_rule_outside_the_scope_writes_refuses_start`
  (de vorm van x01d: de consistentie `bergdagcontrole` leest `bergdag`, alleen de regel `bepaal bergdag` buiten de scope
  schrijft het). Op de oude code: "Expected ServiceError to be thrown, but nothing was thrown", de dienst start
  (`logs/c1-red.log`). Erna: één `INCOMPLETE_SCOPE`, "consistentie 'bergdagcontrole' in entrypoint 'bergdagcontrole' leest
  attribuut 'bergdag' … [bepaal bergdag] …" (`logs/c1-green-without-descriptor.log`).
- (b) Zonder declaraties weigert de TOKA-descriptor de start met precies zeven schendingen in vijf entrypoints, alle bij
  een verdeling: `woonregio factor` op alle vijf, `leeftijd` op de twee die op leeftijd ordenen
  (`logs/c1-toka-without-declarations-DecisionCoreTest.txt`). Met de vijf `inputElements` uit het advies start hij; de
  reactor is groen (`logs/c1-reactor.log`: toolchain 194, service 498, service-quarkus 54 testgevallen, geen skip), met
  de golden-tests van de dienst (`GoldenOverHttpTest`, `GoldenOverQuarkusTest`) en de klassen die de descriptor laden.
  De gouden TOKA-scenario's geven dezelfde uitkomsten: de manifestregel van (d) is die van vóór de kaart.
- (c) `ScopeCompletenessTest.the_opt_outs_hold_for_a_consistency`: met `"inputElements": ["Jutter.bergdag"]` en met
  `"scopeCheckOptOut": true` start dezelfde descriptor. Geen code nodig: beide opt-outs zitten vóór de toets per vorm.
- (d) `make gates` GROEN op 21ade915f, de eindstand van de branch (`uchar-dev/werk/toolchain-cleanup/logs/gates-final.log`,
  7 stappen in 6m26s): pin; reactor 197/502/57 testgevallen; twins 3 tweelingen, 18 cases; replay 338 goldens (pin 338),
  968 cases (pin 968); manifest 32 regels, 29 ok, 3 uitgesloten, 0 mismatch; image; kind-smoke H2, H3, H4 PASS.

Docs: `README.md` (de alinea over de scopetoets) en `docs/ENGINE.md` (wat elke soort leest, de lezers per soort, de
vijf declaraties van de TOKA-descriptor) noemen alle soorten; de zin "The other forms a scope can carry … are not
checked" en het TOKA-commentaar in `DecisionCore` zijn weg. `examples/toka/*.rgs` zijn niet aangeraakt.

### Landing (hoofdsessie, 2026-11-06)

Geland na `make gates` op `21ade915f` (7 stappen groen), met het voorlopige besluit optie 1 (Fable-advies
2026-11-06); de eigenaar kan het omdraaien.
