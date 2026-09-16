# stille-skips: assumptie-bewaakte stiltes worden luide, gepinde skips

Aanleiding: twee kaartrondes noteerden vier assumptie-bewaakte stiltes in
de poortenset — tests die overslaan zonder dat de poortuitvoer het
laat zien:

1. Twee mvn-tests skippen wanneer de externe, niet-meegeleverde
   specificatie (`examples/spec-main`) ontbreekt.
2. Eén mvn-test skipt wanneer `-Duchar.dir`/`UCHAR_DIR` niet is gezet.
3. Eén jar-gate-test aborteert
   (`DrlTest.corpus_drl_bevat_geen_enkele_function_definitie`,
   "Assumption failed: extracted corpus absent").

Geen echte fouten — de afwezigheid is legitiem — maar "stil groen" in
plaats van "luide skip": wie de poort draait ziet `Skipped: 3` en
`1 aborted` zonder naam of reden, en een VIJFDE stille skip zou
onopgemerkt bijkomen. De maintainer wil ze luid en gepind.

## Acceptatie

- (a) Elke poortrun die skips of aborts bevat, noemt ze in zijn uitvoer
  BIJ NAAM met de reden (het uchar-patroon: nooit stil groen). Vorm vrij
  — surefire-rapport nalezen in het make-doel, de assumptieboodschappen
  doorleiden, of een natelscript — maar de namen en redenen moeten in de
  POORTuitvoer staan, niet alleen diep in een rapport-XML.
- (b) Een reason-carrying pin (het check-noop-survivorpatroon): de
  verwachte skips staan bij naam met reden in één lijst; een skip die
  verdwijnt (omdat de voorziening lokaal aanwezig is, is GEEN faal —
  de pin onderscheidt "mag skippen" van "moet skippen") en een NIEUWE,
  ongepinde skip of abort faalt de poort bij naam. Let op: op een
  machine mét de externe specificatie draaien die tests wél — de pin
  formuleert dus een toegestane-skiplijst, geen verplichte.
- (c) De volledige tien-poortenset blijft groen; `CLAUDE.md`-vermelding
  van de poorten alleen aanraken als de uitvoervorm verandert, minimaal
  en gemeld.
- (d) Bestaande tests alleen wijzigen met gemelde reden per geval (de
  assumpties zelf mogen een boodschap krijgen die de poort kan
  doorgeven; hun skip-gedrag verandert niet).
- (e) `vendor/`, `SPEC_REV` niet aanraken; `scripts/` en `tools/`
  alleen waar de poortuitvoer het vraagt, met reden.

## Result

### De vier gevallen, gemeten op basis f0ac2b2

De kaart telt vier gevallen; het zijn drie tests. Geval 2 en geval 4
zijn dezelfde test, gezien vanuit twee poorten: `DrlTest` staat in
`src/test`, dus hij draait zowel in de Maven-poort (surefire, telt hem
als `Skipped`) als in de jar-poort (junit-console, telt hem als
`aborted`).

1. `src/it/java/io/github/yhavinga/regelspraak/CorpusVuurTest.java:111`,
   in `differential_firing_walk_over_de_corpus`:
   `assumeTrue(Files.isDirectory(root.resolve("spec-main")), "extracted
   corpus absent (run tools/extract_corpus.py / make verify-corpus)")`.
   `src/it/java` is via build-helper een testbron van de root-module, dus
   deze test draait in de poort `mvn -o -B test`.
2. `src/test/java/io/github/yhavinga/regelspraak/DrlTest.java:4195`, in
   `corpus_drl_bevat_geen_enkele_function_definitie`: dezelfde assumptie
   met dezelfde boodschap.
3. `src/it/java/io/github/yhavinga/regelspraak/UcharLetReplayTest.java:212`,
   in de helper `ucharDir()` die
   `let_dragende_uchar_goldens_replayen_met_de_uchar_pins` aanroept:
   `Assumptions.assumeTrue(p != null && !p.isBlank(), "zet
   -Duchar.dir=<uchar-checkout> (of UCHAR_DIR) om de Let-goldens te
   replayen")`.
4. Geval 2 in de jar-poort.

Zo zag de poortuitvoer eruit vóór deze kaart:

- `mvn -o -B test` → `Tests run: 980, Failures: 0, Errors: 0, Skipped: 3`,
  exit 0. Geen naam, geen reden; de drie meldingen stonden alleen in
  `target/surefire-reports/*.xml`.
- `make test` → `553 tests found / 1 tests aborted / 552 successful`,
  exit 0. De naam stond wél in de boomweergave (regel 434 van 615), maar
  de samenvatting waar een lezer stopt noemt alleen het getal.

Vier andere assumptieplekken bestaan wel maar slaan vandaag NIET over,
omdat ze aan getrackte fixtures hangen: `CasusTest:29`,
`TokaDekkingTest:45`, `BeslistabelTabelTest:198` en
`schema/FactModelCliTest:63`. Ze staan bewust NIET in de pinlijst. Slaan
ze ooit over, dan faalt de poort bij naam — dat is precies het gat dat
deze kaart dicht.

`tools/verify_corpus.sh` heeft een vijfde stilte die er geen is: hij
meldt zelf `normative spec absent (external, © Belastingdienst) —
skipping examples/spec-main corpus`. Dat is een shell-skip, geen
JUnit-skip, en hij noemt zijn reden al. Ongewijzigd gelaten.

### Wat er gebouwd is

`tools/check-skips.sh` (nieuw) leest de legacy JUnit-XML die maven-surefire
én de junit-platform-console-standalone allebei schrijven — beide zetten
een afgebroken test neer als `<skipped>` met de assumptiemelding erin —
en doet per poort twee dingen: elke skip of abort bij naam in de
poortuitvoer zetten met zijn melding, en hem toetsen aan de
toegestane-skiplijst. Die lijst leeft IN het script, als
`TOEGESTANE_SKIPS`, één regel per toegestane skip in de vorm
`<poort>|<klasse>#<methode>|<reden>|<datum>`. Vandaag vier regels: drie
voor poort `root`, één voor poort `jar`.

Drie uitkomsten, precies zoals (b) vraagt:

- ongepinde skip of abort → FAIL, met de naam en de melding;
- gepinde skip die NIET optreedt → geen faal, wel zichtbaar onder
  "gepind maar NIET overgeslagen (toegestaan, niet verplicht — de test
  draaide)";
- gepinde test die niet meer bestaat → FAIL als verouderde pin.

Vorm per poort:

- **jar-poort (`make test`)** — het make-doel geeft de console-launcher
  `--reports-dir=out/junit-reports` mee, zodat hij dezelfde XML schrijft
  als surefire, en draait het script erna. De rapportmap gaat eerst leeg.
- **Maven-poorten (root, `engine/`, `service/`, `service-quarkus/`)** —
  een `exec-maven-plugin`-executie `skip-pin` in de `test`-fase, direct
  na surefire, plus een executie `skip-pin-ijkpunt` in
  `process-test-classes`. Die vier poms hadden geen ander aangrijppunt:
  de poort is een kale `mvn`-aanroep, dus de controle moet binnen de
  build zitten om de build te kunnen laten falen.

`engine/`, `service/` en `service-quarkus/` hebben vandaag geen enkele
toegestane skip. Ze krijgen de binding toch, want daar zou de volgende
stilte even onzichtbaar zijn als de vier van deze kaart.

Drie maatregelen met hun reden:

1. **Het ijkpunt.** Maven ruimt `target/surefire-reports` zonder `clean`
   niet op, dus een rapport van een vorige run kan blijven staan. De
   executie in `process-test-classes` raakt een leeg bestand aan; het
   script telt alleen rapporten die daar jonger dan zijn, meldt hoeveel
   het er als verouderd overslaat, en faalt als er GEEN vers rapport is.
   Zonder ijkpunt zou een controle die per ongeluk vóór surefire draait
   een oude run natellen en groen melden.
2. **`<skipTests>false</skipTests>` in de vier poms.** De executies
   lezen `${skipTests}`; een niet-gedeclareerde property bereikt
   exec-maven-plugin onopgelost. Met de declaratie zet `-DskipTests` hem
   op true en staan beide stappen stand — nodig, want `make engine` en
   `make scenario-test` installeren de root zo, en dan is er niets om na
   te tellen.
3. **bash 3.2-veilig.** De system-bash op macOS breekt onder `set -u` op
   `"${leeg[@]}"`, en een poort zonder enkele pin is de normale toestand.
   Alle array-expansies die leeg kunnen zijn gebruiken
   `${arr[@]+"${arr[@]}"}`; nagemeten door het script met `/bin/bash`
   (3.2.57) te draaien voor een poort mét pins, een poort zonder pins, en
   een volledig lege pinlijst.

`CLAUDE.md` kreeg één alinea onder de poortenlijst ("Skips are named,
never silent"), omdat de uitvoervorm van vier poorten verandert: ze
eindigen nu op een `skip-pin`-blok en falen op een ongepinde skip.

Bestaande tests: GEEN gewijzigd. De assumptieboodschappen zijn
ongemoeid gebleven — de reden hoort bij de pin, niet bij de test, en dan
hoeft (d) niet te worden ingeroepen.

### De faalpaden, gedemonstreerd in echte poortruns

**Ongepinde skip.** Tijdelijk
`assumeTrue(false, "TIJDELIJKE DEMO-SKIP — wordt teruggedraaid")` in
`OperatorenTest#symbool_dekt_alle_operatoren` gezet.

- `make test`: 553 tests, 2 aborted; skip-pin noemt de nieuwe naast de
  gepinde, meldt `gepind : NEE — deze skip staat in geen enkele pin`, en
  sluit met `FAIL: 1 ongepinde skip/abort in poort 'jar'` — make exit 2.
- `mvn -o -B test`: `Skipped: 4`, skip-pin `FAIL: 1 ongepinde skip/abort
  in poort 'root'`, `BUILD FAILURE`, exit 1.

Vóór deze kaart was precies deze situatie in beide poorten exit 0.
Teruggedraaid met `git checkout`.

**Toegestaan, niet verplicht.** Tijdelijk een extra pinregel gezet voor
diezelfde test, die dan NIET overslaat. `make test`: skip-pin meldt hem
onder "gepind maar NIET overgeslagen (toegestaan, niet verplicht — de
test draaide)" en sluit met `PASS`, exit 0. Teruggedraaid.

Eerlijk over de vorm van deze demonstratie: het echte pad — de
voorziening IS er, dus de gepinde test draait — is hier niet te meten.
De externe specificatie staat niet op deze machine, en de uchar-checkout
mocht deze ronde alleen voor de ene conformance-aanroep worden
aangeraakt. De tijdelijke pin op een bestaande, niet-overslaande test
loopt door dezelfde code als het echte pad.

**Verouderde pin.** Tijdelijk een pin op een methode die niet bestaat.
`make test`: `FAIL: 1 verouderde pin in poort 'jar' — de gepinde test
bestaat daar niet meer`, make exit 2. Teruggedraaid.

### Poortenstand op de kaartcommit (alle tien groen)

| # | poort | kerncijfer | exit |
|---|-------|-----------|------|
| 1 | `make verify-corpus` | `corpus: 54 passed, 0 failed, 0 known-defect (of 54)` | 0 |
| 2 | `service/` `mvn -o -B test` | 167 tests, 0F/0E/0S — skip-pin: geen enkele skip | 0 |
| 3 | `service-quarkus/` `mvn -o -B test` | 33 tests, 0F/0E/0S — skip-pin: geen enkele skip | 0 |
| 4 | `make scenario-test` | golden 29/29 PASS, marker-exit-3 ok | 0 |
| 5 | root `mvn -o -B test` | 980 tests, 0F/0E/3S — skip-pin: 3 overgeslagen, alle gepind, bij naam | 0 |
| 6 | `make test` | 553 tests, 1 aborted, 0 failed — skip-pin: 1 overgeslagen, gepind, bij naam | 0 |
| 7 | `make engine` | 115 tests, 0F/0E/0S — skip-pin: geen enkele skip | 0 |
| 8 | `make facts` | beans gegenereerd én gecompileerd | 0 |
| 9 | `make verify-negatief-corpus` | 52 snippets — 41 rejected, 11 parse as expected (1 `[GAT]`), 4 strict cross-checks | 0 |
| 10 | `make uchar-conformance` | vendored slice byte-identiek + 49 emitter-goldens gereproduceerd op de pin | 0 |

Installatieketen vooraf: `mvn -o -B install -DskipTests` in de root, in
`engine/` en in `service/` (die laatste voor de test-jar die
`service-quarkus` leent) — alle drie exit 0.

### Afwijkingen

- De kaart spreekt van vier gevallen; het zijn drie tests in twee
  poorten. De pinlijst telt daarom vier regels: de poort is deel van de
  sleutel.
- De demonstratie van "toegestaan, niet verplicht" gebruikt een
  tijdelijke pin in plaats van de echte voorziening; zie hierboven.
- `service/` telt 167 tests waar `CLAUDE.md` `≥ 145` als vloer noemt, en
  dat is goed: de vloeren zijn ondergrenzen, geen gelijkheden.
