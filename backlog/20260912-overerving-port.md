# Port: objecttype-overerving door de eigen vertaallaag

Mandaat: de maintainer wil met RegelSpraak + Drools + overerving in de
toolchain-engine experimenteren. De gevendorde frontend
(`vendor/uchar-frontend/`, `SPEC_REV` 60d021c) parseert en resolvet
`Objecttype het Wrakrecht is een Strandrecht` al volledig — supertype,
`keten()` en de ketenbewuste accessors zitten in het model. De EIGEN
vertaallaag onder `src/main/java/io/github/yhavinga/regelspraak/` stamt
van vóór de overerving en leest overal het gedeclareerde type.

Bron van de port: uchar's `backends/drools/` — ooit als kopie van deze
laag begonnen, daarna doorontwikkeld met native `extends`, ketenbewuste
facetlezingen en de ch.-7 defaults-lowering. Alleen die drie
doorontwikkelingen komen hier terug; de overige uchar-divergentie
(let-lowering, effectieve-eenheid, wire-loader, naamnormalisatie) blijft
waar zij is.

## Acceptatie

- (a) `--facts` op de wrakrecht-bron geeft `class Wrakrecht extends
  Strandrecht` met de eigen leden alleen; de keten telt zes leden.
- (b) De zes cellen van de subtype-semantiek reproduceren door de
  exec-model-tak, met de waarden die in uchar drievoudig gepind zijn
  (ref ≡ clingo ≡ Drools):

  | geval | instantie | betwist | bergingsdiepte | uitkering |
  |-------|-----------|---------|----------------|-----------|
  | 1 | S1 Strandrecht | nee | —  | 10,00 |
  | 1 | W1 Wrakrecht   | nee | 5  | 20,00 |
  | 2 | S1 | ja  | —  | 99,00 |
  | 2 | W1 | ja  | 5  | 99,00 |
  | 3 | W1 | nee | 25 | 55,00 |
  | 4 | W1 | ja  | 25 | 55,00 |

  `registratiekosten` is 5,00 op elke instantie (de kale regel van het
  supertype vuurt voor de subtype-instantie); `uitkeringstotaal` /
  `registertal` zijn 30,00/2, 198,00/2, 55,00/1, 55,00/1.
- (c) `make test` blijft groen (553-basis).
- (d) `mvn -B test` blijft groen.
- (e) `make uchar-conformance` blijft groen — `vendor/` wordt NIET
  aangeraakt.
- (f) geen bestaande test wijzigt zonder gemelde reden.

## Fasering

1. Divergentiemeting (in het Result).
2. Beans: `FactModelGenerator` → `extends` + eigen-leden-alleen;
   `ContractValidator` beweegt mee.
3. Ketenbewuste lezingen: elke facetlezing in beide transpilers door de
   `*Leden()`-accessors.
4. Defaults: de `prioritizedDefaults`-lowering in de exec-model-tak,
   inclusief de effectieve-stapel-samenstelling van ch. 7.10. De
   DRL-tekst-tak weigert luid aan de deur (zoals in uchar).
5. Acceptatie.
