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

Daarbovenop sluit het script de drie manieren waarop een testrun kan
KRIMPEN — krimp is de stilte die een skiplijst per definitie niet ziet.
Die drie kwamen uit de lens en staan in de fixronde onderaan: een
afgebroken container, een gekrompen suite (de `VLOEREN`-ondergrens per
poort), en een rapport dat de parser niet volledig heeft gelezen.

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
`make test`: `FAIL: 1 verouderde pin in poort 'jar'`, make exit 2.
Teruggedraaid. (De formulering van die faalregel is in de fixronde
aangescherpt; zie punt 4 hieronder.)

### Fixronde na de lens

De lens bevestigde alle metingen hierboven — drie faalpaden
reproduceerbaar, ijkpunt en `-DskipTests`-keten correct, bash 3.2 veilig,
tien poorten groen — en vond zes punten. Alle zes verwerkt; elk nieuw
faalpad is in een echte poortrun gemeten en daarna teruggedraaid.

**1 (blokkerend) — een @BeforeAll-abort maakte een hele klasse
onzichtbaar.** Surefire schrijft voor een afgebroken container een
rapport met `tests="0"` en GEEN ENKEL `<testcase>`; een telling die
alleen testgevallen leest ziet dan niets. Zelf nagemeten door
`@BeforeAll static void demo() { assumeTrue(false, …) }` op
`RangtelwoordTest` te zetten: `<testsuite … tests="0" errors="0"
skipped="0">` met nul testcase-elementen, en `Tests run: 0` in de `.txt`
ernaast. Nu dubbel gedicht. (a) Elk KLASSErapport zonder testgeval faalt
bij naam — een suitenaam zonder spatie en mét punt is een Java-klasse;
de engine-rapporten van de console ("JUnit Vintage") mogen legitiem leeg
zijn en worden overgeslagen. (b) De vloeren van punt 2 vangen dezelfde
krimp van de andere kant. Gemeten met de abort in de volledige run:
`mvn -o -B test` → 971 tests, `FAIL: 1 testklasse(n) leverden GEEN ENKEL
testgeval: io.github.yhavinga.regelspraak.RangtelwoordTest` PLUS
`FAIL: 971 testgevallen gemeten, vloer is 980`, BUILD FAILURE, exit 1.
`make test` vangt hetzelfde geval langs de andere kant: de console zet
elk kind neer als `<skipped>` met "parent was skipped", dus negen
ongepinde skips bij naam, make exit 2.

**2 (ernstig) — een gekrompen suite was stil groen.** Het script eiste
alleen `totaal > 0`. Nu draagt elke poort een ondergrens in `VLOEREN`
(in `tools/check-skips.sh`), met reden en meetdatum, naar het patroon
van `EXPECTED_MIN_SNIPPETS` in `tools/verify_corpus.sh`: root 980, jar
553, engine 115, service 167, service-quarkus 33. Ondergrenzen, geen
gelijkheden — groei hoeft geen bump, krimp faalt. Een poortsleutel
zonder vloer is zelf een failure, zodat een nieuwe poort er niet
zonder doorglipt. Gemeten:
`mvn -o -B -f engine/pom.xml test -Dtest=ParametersetLaderTest` →
`7 (vloer 115)`, `FAIL … de suite is gekrompen`, exit 1.

Gevolg, in het script gedocumenteerd: een GERICHTE `-Dtest`-run zakt per
definitie door de vloer. Voor een poortscript is dat juist — de poort
IS de volledige run — en punt 4 haalt de scherpe kant eraf.

**3 (ernstig) — `-Dmaven.test.skip=true` liet de skip-pin doorlopen.**
De zusterproperty slaat surefire over zonder `skipTests` te zetten, dus
de natelling vond geen vers rapport en liet de build vallen. Gemeten
vóór de fix: `mvn -o -B install -Dmaven.test.skip=true` → BUILD FAILURE,
exit 1 op `exec (skip-pin)`. `docker/Dockerfile` bouwt met precies die
vlag, drie keer. Fix: elk van de vier poms krijgt een profiel
`skip-pin-uit-bij-maven-test-skip`, geactiveerd op de USER-property
`maven.test.skip=true`, dat `skipTests` op true zet. Dat is de nette
route, want `<skip>` neemt één expressie en kent geen OR. Na de fix
beide vlaggen exit 0, in alle vier de modules gemeten.

**De tweede, onafhankelijke breuk op datzelfde pad.** `docker/Dockerfile`
deed `COPY grammar/ grammar/` terwijl `grammar/` bij de
dependency-inversie (`9f7b001`) verdween: sindsdien zitten de
grammatica én de frontendbronnen in de gevendorde slice, en `vendor/`
werd niet gekopieerd. Stage 1 struikelt dus op de eerste COPY — het
image bouwt al langer niet, los van deze kaart. Minimaal hersteld:
`COPY vendor/ vendor/` met de reden erboven, en de `.dockerignore`-kop
die nog "grammar/tools/src" zei. Het image is NIET gebouwd; statisch
nagemeten zoals de poortenkaart deed: alle dertien COPY-bronpaden van
het recept bestaan, geen enkele botst met `.dockerignore`, en de paden
die `make parser` en de twee extra bronwortels van de root-pom nodig
hebben (`vendor/…/grammar/RegelSpraak{,Lexer}.g4`,
`vendor/…/src/main/java`, `src/main/java`) zitten allemaal in het
gekopieerde deel. Dat de drie `mvn … -Dmaven.test.skip=true`-aanroepen
van het recept de skip-pin overleven is hierboven gemeten. Groter dan
COPY-regels bleek het niet, dus geen eigen kaart.

**4 (klein) — een gerichte run meldde drie "verouderde pin".** De pins
waren niet verouderd, de run was partieel. De stale-faal geldt nu alleen
wanneer de gepinde KLASSE een vers rapport heeft en de METHODE daarin
ontbreekt. Draaide de klasse in het geheel niet mee, dan meldt het
script "gepinde klasse niet in deze run (partiële run — geen faal, de
vloer bewaakt de telling)". De redenering staat als commentaar bij de
pinlogica: de richting die hierdoor openvalt — een hele klasse die
verdwijnt — is precies wat de vloer meet. Beide kanten gemeten:
`mvn -o -B test -Dtest=OperatorenTest` → drie pins gemeld, geen
stale-faal (wel de vloer); een synthetisch rapport waarin `DrlTest` wél
draait maar zonder de gepinde methode → `FAIL: 1 verouderde pin in poort
'jar' — de klasse draaide, de gepinde methode bestaat niet meer`.

**5 (klein) — `-nt` vergelijkt hele seconden.** Een rapport dat in
dezelfde seconde als het ijkpunt wordt weggeschreven telde als
verouderd; op een bestandssysteem met grove tijdstempels is dat
spookrood. De vergelijking is nu "niet ouder dan", op de mtime in
seconden (`stat -f %m` op BSD, `stat -c %Y` op GNU). Gemeten: gelijke
seconde → `1 vers, 0 verouderd`; één seconde ouder → verouderd, en met
uitsluitend verouderde rapporten faalt de poort.

**6 (klein) — de eenregelige `<testcase …><skipped …/></testcase>` werd
stil geconsumeerd.** De statemachine handelt die vorm nu af, met
`message="…"` én met CDATA, en de attribuutlezer eist een
scheidingsteken vóór de attribuutnaam zodat `name=` niet binnen
`classname=` wordt gevonden — die volgorde komt in dezelfde vorm voor.
Gemeten op een synthetisch rapport met alle vier de vormen: 4 van 4
geteld, 3 skips bij naam, geen leesfout. De overige randen staan als
BEKENDE GRENZEN in de scriptkop; ze lopen alle drie luid stuk, niet
stil.

**Een integriteitscontrole die uit punt 6 volgt.** Elk rapport wordt nu
op zijn eigen boekhouding gelegd: het getelde aantal
`<testcase>`-elementen moet gelijk zijn aan `tests="N"` van het
`<testsuite>`. Wijkt dat af, dan heeft de parser iets gemist en faalt de
poort daarop, in plaats van een te kleine telling voor waar aan te
nemen. Nagemeten dat surefire's `tests` de overgeslagen tests meetelt
(`tests="1" skipped="1"` bij een klasse met één afgebroken test), dus de
gelijkheid klopt en de controle geeft geen spookrood.

### Poortenstand op de kaartcommit (alle tien groen)

| # | poort | kerncijfer | exit |
|---|-------|-----------|------|
| 1 | `make verify-corpus` | `corpus: 54 passed, 0 failed, 0 known-defect (of 54)` | 0 |
| 2 | `service/` `mvn -o -B test` | 167 tests, 0F/0E/0S — skip-pin: geen enkele skip (vloer 167) | 0 |
| 3 | `service-quarkus/` `mvn -o -B test` | 33 tests, 0F/0E/0S — skip-pin: geen enkele skip (vloer 33) | 0 |
| 4 | `make scenario-test` | golden 29/29 PASS, marker-exit-3 ok | 0 |
| 5 | root `mvn -o -B test` | 980 tests, 0F/0E/3S — skip-pin: 3 overgeslagen, alle gepind, bij naam (vloer 980) | 0 |
| 6 | `make test` | 553 tests, 1 aborted, 0 failed — skip-pin: 1 overgeslagen, gepind, bij naam (vloer 553) | 0 |
| 7 | `make engine` | 115 tests, 0F/0E/0S — skip-pin: geen enkele skip (vloer 115) | 0 |
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
  dat is goed: de vloeren zijn ondergrenzen, geen gelijkheden. De
  `VLOEREN`-tabel in het script pint 167 — de gemeten waarde op deze
  commit, niet de losser gestelde poortvloer uit `CLAUDE.md`; twee
  ondergrenzen naast elkaar, de strengste meet als eerste.
- `docker/Dockerfile` is in deze kaart aangeraakt, wat buiten de
  oorspronkelijke scope (e) valt. Reden: de `-Dmaven.test.skip`-fix van
  punt 3 loopt over precies dat recept, en de meting daar legde een
  tweede, pre-existente breuk bloot die het image al langer niet liet
  bouwen. Twee COPY-regels plus een commentaarregel; het image zelf is
  niet gebouwd (dat is een hostpoort).
