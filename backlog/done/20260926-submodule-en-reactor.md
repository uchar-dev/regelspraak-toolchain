# submodule-en-reactor: de distributie bouwt uit uchar als submodule, zonder eigen kopie van parser, lader, transpiler of runtime

Deze repo is de opvolger van yhavinga/regelspraak-java (mirror met volledige
geschiedenis, 2026-09-26) en wordt een DISTRIBUTIE zonder eigen semantiek —
CLI, dienst (JDK en Quarkus), docker/helm — bovenop uchar
(uchar-dev/uchar, privé) als git-submodule. Besluiten van de maintainer
(2026-09-26): de submodule-commit is de pin (geen `SPEC_REV`, geen
conformantiegate); uchar bezit het pakket `dev.uchar.regelspraak.*`, deze
repo krijgt `dev.uchar.regelspraak.toolchain.*` en leest alleen uchars
publieke API; de graaf, de scenariolaag (resolver, lowering naar
casebestanden, het rapportrecord `ScenarioResultaat`) en het rekenjaar zijn
inmiddels in uchar geland (uchar main `d67f5da1`, 2026-09-26). Reden voor
de vorm: uchars Drools-backend draagt gedrag (vier nluwv-commits, de
defaults-families, de zijtafels) dat deze repo's eigen kopieën niet dragen;
de oude dienst bouwde met `ExecModelTranspiler.build(model, cl)`, wat in
uchar de zijtafels stil wegliet — de facade `DroolsBackend.build(document,
branch)` (uchar, `backends/drools/.../cases/DroolsBackend.java`) is nu de
enige bouwingang, en de korte overloads bestaan niet meer.

Onderzoek (uchar, `../research/toolchain-verhuizing/onderzoek-2026-09-26.md`)
mat: de eigen code compileert tegen uchar met vier fouten (alle graaf-API,
inmiddels veranderd: `bouw(model, defaults, navigatie)`, `elementVan`,
`verbruikt(r, navigatie)`), een reactor-prototype bouwt offline
(/tmp/verhuizing/exp/proto — op de OUDE pakketnaam; alleen ter referentie),
Quarkus' BOM zet Jackson en de ANTLR-runtime terug tenzij de ouder-pom ze
pint. De pariteitszone van deze repo (`examples/toka*`, `specification/`) is
©-Belastingdienst-materiaal en blijft zoals ze is: nergens geciteerd, de
repo blijft privé.

## U-besluiten (hoofdsessie, 2026-09-26)

- U1 Submodule `uchar` met relatieve URL `../uchar.git`, op uchar main
  `d67f5da1`; `.gitmodules` en README zeggen `git clone --recurse-submodules`
  / `git submodule update --init --recursive`. Pin-bewaking
  `scripts/check-pin.sh`: submodule geïnitialiseerd en schoon (`git
  submodule status` zonder `-`/`+`/`U`, `git -C uchar status --porcelain`
  leeg; genegeerde buildbestanden tellen niet), en de versies in de
  ouder-pom gelijk aan `uchar/frontends/regelspraak/Makefile`
  (`ANTLR4_VERSION`) en `uchar/backends/drools/pom.xml` (drools, jackson).
- U2 Reactor: root-pom `dev.uchar.regelspraak.toolchain:regelspraak-toolchain`
  (packaging pom) met modules `uchar/backends/drools` (uchars eigen pom,
  eigen tests draaien mee), `uchar-frontend` (dunne module: antlr4-maven-
  plugin op `uchar/frontends/regelspraak/grammar` met `-package
  dev.uchar.regelspraak.parser`, build-helper op `uchar/frontends/
  regelspraak/src/main/java`, compiler-excludes voor model/ir/kern die uit
  de backend-jar komen; visitor aan, listener uit — controleer wat uchars
  Makefile aan ANTLR meegeeft en volg dat), `toolchain` (eigen kern: cli,
  engine-facade, `Verbalisator`, `ParametersetLader`, scenariorunner en
  -CLI), `service`, `service-quarkus`. `dependencyManagement` in de
  ouder-pom pint Jackson, ANTLR-runtime, Drools en JUnit op uchars
  versies; de Quarkus-BOM alleen in de Quarkus-module. De
  antlr4-maven-plugin staat niet in `~/.m2`: één keer online ophalen mag;
  README zegt dat een offline build die cache nodig heeft.
- U3 Verwijdercommit "parser en lowering verhuisd naar uchar", één commit:
  `vendor/`, `SPEC_REV`, `scripts/uchar-conformance.sh`,
  `src/main/java/…/{runtime,transpiler,schema,graaf}`,
  `src/main/java/…/resolver/ScenarioResolver.java`,
  `src/main/java/…/ir/ResolvedScenario.java` (beide in uchar), `engine/…/
  {BeanCompiler,RegelSpraakCompileException,ScenarioResultaat}.java` (in
  uchar), `.claude/` (agentscaffolding over een grammatica die in uchar
  staat; de `bypassPermissions`-instelling hoort niet in een gedeelde
  repo), `docs/SCENARIOTOETSEN.md` (opgegaan in uchars `grammar/
  DIVERGENCES.md`), de Makefile-doelen die de parser bouwden (parser,
  compile, resolver, grun, tokens, gui, verify-*, uchar-conformance) en
  `gen/`/`lib/`-regels. `docs/grammar-divergences.md` naar
  `docs/historisch/` met één regel dat uchars DIVERGENCES.md gezaghebbend
  is. Tests die verwijderde code toetsten gaan mee weg (src/test-
  frontendsuites, DrlTest, de IT-differentiaal in `src/it`; de
  geschiedenis bewaart ze voor de latere triagekaart in uchar); tests van
  eigen code blijven. Elke verwijderde suite met reden in het Result.
- U4 Pakketten: eigen klassen naar `dev.uchar.regelspraak.toolchain.<sub>`
  (`cli`, `engine`, `service`, `service.http`, `quarkus`, …), verwijzingen
  naar uchar-typen naar `dev.uchar.regelspraak.*` (nooit hetzelfde pakket
  als uchar; geen klasse in uchars gegenereerde naamruimten `…facts` en
  `…generated`). `Uitvoerder` laadt de inconsistentieklasse via uchars
  constante (`FactModelGenerator.PAKKET` of wat uchar publiek maakt), niet
  via een letterlijke naam, en slikt geen `ClassNotFoundException` meer.
- U5 De dienst (`BeslisserKern`), de engine-facade (`RegelSpraakEngine`) en
  de scenariorunner bouwen hun KieBase via `DroolsBackend.build(document,
  branch)` — bron → `Resolver.resolve` → `IrEmitter.emit` → document —
  met alle zijtafels (de facade neemt ze uit het document); de
  scenariorunner gebruikt uchars `ScenarioResolver`/`CaseEmitter`
  (frontend, `--cases`-lowering) en het rapport uit `CaseRunner.rapport`/
  `ScenarioResultaat` waar dat past; het markeroordeel komt uit de dekking
  van de exec-tak (`Build.dekking()`), niet uit de DRL-tak. Gedragsvragen
  die verder gaan (playground bij resolverfouten, graafJson/trace/uitleg
  uit nluwv `f3613bd3`, de replay van uchars goldens door de dienst, de
  drie dialect-tweelingen via HTTP, nieuwe vloeren) zijn de VOLGENDE kaart;
  hier alleen wat nodig is om te bouwen en de bestaande suites eerlijk
  groen te krijgen. Een bestaande test die het oude, foute gedrag pinde
  (zijtafels weg, defaults niet gevuurd) wordt omgezet naar de waarheid van
  uchar, met de reden in de commit.
- U6 Klaar: een verse `git clone --recurse-submodules` bouwt met
  `mvn -B verify` op de root: alle modules compileren, uchars backendsuite
  en de eigen suites (engine, dienst, quarkus, toolchain-kern) draaien
  groen met een niet-nul aantal; `scripts/check-pin.sh` groen;
  `git grep -n "io\.github\.yhavinga"` leeg buiten `backlog/` en
  `docs/historisch/`; geen bronbestand buiten `uchar/` declareert een
  pakket `dev.uchar.regelspraak` dat niet met `dev.uchar.regelspraak.
  toolchain` begint; `vendor/` bestaat niet.
- U7 README en CLAUDE.md van deze repo herschreven naar wat ze nu is: een
  distributie op uchar als submodule (wat waar staat, hoe te bouwen, de
  pin), de pariteitszone (`examples/toka*`, `specification/`: blijft, nooit
  geciteerd, repo privé zolang ze bestaat), de deprecatie van
  yhavinga/regelspraak-java, en het regime van deze repo (kaarten in
  `backlog/`, geen eigen semantiek: een semantische vraag is een kaart in
  uchar). Kort; geen logboek.
- U8 Rechten en regime: `examples/toka*` en `specification/` onaangeroerd
  en nergens geciteerd (alleen paden en tellingen); uchars `toka/` alleen
  tellen; geen D-/U-nummers in code, scripts of README; commitboodschappen
  in het Nederlands, kort het waarom.

## Acceptatie

- (a) U6 volledig, gemeten met de commando's in het Result; (b) de
  verwijdercommit is één commit met alleen verwijderingen en de
  bijbehorende Makefile/README-regels; (c) de reactor bouwt offline na één
  online ophaling van de antlr4-maven-plugin (meld de ophaling).

May touch: alles in deze repo behalve `examples/toka*`, `specification/`
en `backlog/done/`. Niet in deze kaart: gedrag van de dienst voorbij U5,
`bin/`-ingangen, docker/helm (kaart 3), de werkbank.

## Result (2026-09-26, bouwer)

**Uitkomst.** De distributie bouwt uit uchar als submodule, zonder eigen kopie van parser, lader, transpiler of
runtime. Een verse kloon met de submodule bouwt met `mvn -B -o verify` op de root: uchars backendsuite 840, de
toolchain-kern 109, de dienst 169 en de Quarkus-adapter 33 tests, alle groen, geen skip. `scripts/check-pin.sh` is
groen; `io.github.yhavinga` komt buiten `backlog/` en `docs/historisch/` niet meer voor; elk eigen pakket begint met
`dev.uchar.regelspraak.toolchain`; `vendor/` bestaat niet. Wat niet haalbaar was of buiten de kaart viel, staat
genummerd onder Restpunten.

### Commits (basis b002492, branch card/submodule-en-reactor)

- 343b1a8 claim (git mv new → doing).
- d1d2aad submodule `uchar` (`../uchar.git`, op d67f5da1), `scripts/check-pin.sh`, make-doel `check-pin`.
- 26c86a8 root-pom als aggregator/ouder; module `uchar-frontend`.
- 6ecfc0d verwijdercommit "parser en lowering verhuisd naar uchar": 262 bestanden, +7 −76907; de +7 zijn ingekorte
  regels in Makefile (3) en README (4), er is geen bestand toegevoegd of hernoemd (acceptatie (b)).
- e14111a `docs/grammar-divergences.md`, `docs/ANTLR-HOWTO.md`, `docs/TRANSPILER-ARCHITECTURE.md` naar
  `docs/historisch/`, elk met één regel die naar uchar wijst. Apart van de verwijdercommit, omdat een verplaatsing
  met een toegevoegde regel geen verwijdering is.
- f6d7738 hernoeming naar `dev.uchar.regelspraak.toolchain.*` (81 renames, mechanisch; bouwt pas weer na 5a2c209).
- 5a2c209 dienst, engine-facade en scenariorunner op uchars facade en scenariolaag; tests omgezet; Makefile op de
  reactor; vloer toolchain 109.
- 2c8d087 README, CLAUDE.md en docs/ENGINE.md herschreven (U7).
- (laatste) dit Result in de kaart.

### Metingen (commando → uitkomst)

1. `git submodule add ../uchar.git uchar` → SSH-kloon uit uchar-dev/uchar in 4,3 s; `git -C uchar checkout d67f5da1`;
   `git submodule status` → ` d67f5da10dbe11b4ce6b1162b0ec81f3157d2fe8 uchar (heads/main)`.
2. `mvn -B -o -q -DskipTests package` vóór de ophaling → exit 1: "Plugin org.antlr:antlr4-maven-plugin:4.13.2 ... has
   not been downloaded from it before".
3. **De ene online ophaling** (acceptatie (c)): `mvn -B -DskipTests package` → exit 0; 25 downloads, 16 artefacten:
   antlr4-maven-plugin 4.13.2, antlr4 4.13.2, ST4 4.3.4, antlr-runtime en antlr-master 3.5.3, icu4j 72.1,
   org.abego.treelayout.core 1.0.3, plexus-compiler-api/plexus-compiler 2.12.1, plexus-components 10.0, plexus 2.0.2,
   plexus-utils 1.5.8 en 3.4.2, slf4j-api/-parent/-simple 2.0.0. Daarna is elke build offline gelopen (0 downloads).
4. `mvn -B -o clean verify` op 26c86a8 (alleen uchar-backend + uchar-frontend) → BUILD SUCCESS; backend "Tests run:
   840, Failures: 0, Errors: 0, Skipped: 0"; uchar-frontend 307 klassen (emit, parser, resolver).
5. `bash scripts/check-pin.sh` → exit 0, vier versies gelijk (antlr 4.13.2, drools 8.44.0.Final, jackson 2.21.2,
   junit 5.11.4). Faalwijzen getoetst, elk exit 1 met naam: onbekend bestand in uchar/ ("?? PROEFBESTAND");
   submodule op HEAD~1 ("+9d46e19d…"); jackson.version 2.17.2 in de ouder-pom; submodule gedeïnitialiseerd
   ("-d67f5da1…", in de verse kloon, daarna weer groen).
6. `mvn -B -o clean verify` in de worktree op 5a2c209 → BUILD SUCCESS, 51,4 s: backend 840, toolchain 109, service
   169, service-quarkus 33, 0 fout, 0 skip; skip-pin "PASS: geen enkele skip of abort" in alle drie de eigen poorten
   (vloeren 109/167/33).
7. **Verse kloon (U6).** Zonder push: `git clone -b card/submodule-en-reactor <lokale repo> /tmp/tc/vers`,
   `git remote set-url origin git@github.com:uchar-dev/regelspraak-toolchain.git`, `git submodule update --init
   --recursive` → de relatieve URL loste op naar `git@github.com:uchar-dev/uchar.git`, "checked out 'd67f5da1…'"
   (5,2 s). Dat is wat `git clone --recurse-submodules` van GitHub doet. Daarin op 2c8d087: `check-pin` → PASS;
   `mvn -B -o verify` → BUILD SUCCESS, 51,0 s, 840/109/169/33 groen, 0 downloads; na de build `git status --short`
   leeg en `git -C uchar status --porcelain` leeg (target/ en dependency-reduced-pom.xml negeert uchar zelf). De
   offline run is strenger dan `mvn -B verify`: wat offline slaagt, slaagt online met dezelfde lokale repository.
8. U6-greps op HEAD: `git grep -n "io\.github\.yhavinga" -- ':!backlog' ':!docs/historisch'` → geen treffer;
   `git ls-files '*.java' | grep -v '^uchar/' | xargs grep -h '^package '` → alleen
   `dev.uchar.regelspraak.toolchain.{cli,engine,service,service.http,quarkus}`; `ls vendor` → bestaat niet;
   `git diff --stat b002492..HEAD -- examples specification backlog/done` → leeg.
9. Versies in de Quarkus-module (`mvn -o dependency:list`): jackson-core/-databind 2.21.2, jackson-annotations 2.21,
   jackson-datatype-jdk8/-jsr310 en jackson-module-parameter-names 2.21.2, antlr4-runtime 4.13.2, drools-* 8.44.0.Final;
   junit-jupiter 5.10.3 (die van de Quarkus-BOM, zie besluit 2).
10. `make scenario-test` (gouden TOKA-manifest via de scenario-CLI) → "golden TOKA: 26/29 PASS, marker-exit-3: yes
    (+3 andere manifestregels ok, 3 mismatch)", exit 1. De drie mismatches (toka-29, -43, -44, verwacht 0, kreeg 1)
    zijn weigeringen van uchars ScenarioResolver; uchars eigen `Resolver --ir --cases` weigert dezelfde drie
    manifestregels met dezelfde zinnen (gemeten). Zie restpunt 2.
11. `make classpath && bash tools/testdata/run_tests.sh` → "TOTAL: 7 pass, 0 fail" (CSV → beslistabel, DRL via uchars
    DRL-tak). `make facts` → PASS; `make drl`/`make ir`/`make graaf` werken; `make drl` op een niet-gelowerde regel →
    exit 3 met uchars weigering.
12. Nulmeting van vóór de verhuizing niet opnieuw gedraaid; het onderzoek (§2 G11, op 5b227cc) mat: engine 115 (1
    error), dienst 167, Quarkus 33, root 981, jar 553 + 1 gepinde abort.
13. Ongeluk, hersteld: één `mvn install -DskipTests -pl toolchain -am` schreef `~/.m2/repository/dev/uchar/**`. De map
    bestond niet vóór deze sessie (alle bestanden jonger dan het online-log) en is verwijderd; daarna geen install
    meer. CLAUDE.md zegt nu dat de reactor geen install nodig heeft.
14. JDK: op deze machine alleen GraalVM 25.0.3; de build compileert met `maven.compiler.release` 21.

### Besluiten die de kaart aan de bouwer liet

1. Coördinaten: ouder `dev.uchar.regelspraak.toolchain:regelspraak-toolchain:0-SNAPSHOT`; artifactId = moduledirectory
   (`uchar-frontend`, `toolchain`, `service`, `service-quarkus`).
2. Versiepinnen: de ouder importeert de jackson-bom en de junit-bom op uchars versies en pint antlr4-runtime,
   drools-engine en drools-mvel expliciet. De Quarkus-module importeert de jackson-bom vóór de Quarkus-BOM (van twee
   geïmporteerde BOMs wint de eerste); JUnit laat zij aan de Quarkus-BOM, omdat quarkus-junit5 daartegen gebouwd is.
   `check-pin.sh` vergelijkt ook `junit.version` (U2 pint JUnit op uchars versie), niet de merge-base met origin/main
   (niet in U1).
3. De skip-pin-bewaking (`tools/check-skips.sh`) blijft, nu via de pluginManagement van de ouder per module
   (`skippin.poort`); poorten root en jar vervallen met hun suites, engine heet toolchain. Vloeren: toolchain 109
   (gemeten, was engine 115), service 167 (gemeten 169), service-quarkus 33.
4. uchars frontendtests draaien niet in deze reactor: `uchar-frontend` compileert alleen de hoofdcode; die suite is
   van uchars gate 10.
5. Naast de genoemde Makefile-doelen gingen ook `test`, `antlr-jar`, `junit-jar`, `clean-jar` en `drools-it` weg, en
   de gereedschappen die alleen die doelen dienden: `tools/Parse.java`, `tools/verify_corpus.sh`,
   `tools/extract_corpus.py`, `tools/negatief-corpus/`. Uit `.gitignore` de regels voor `lib/`, `gen/`, `*.tokens`,
   `*.interp` en de spec-extracties. De resterende doelen (`drl`, `facts`, `graaf`, `ir`, `scenario-test`) draaien op
   de runtime-classpath van de toolchain-module (`make classpath`); nieuw `make verify`.
6. Eén bouwingang in de eigen code: `UcharBouw.bouw(model, tak)` = `IrEmitter.emit` → `DroolsBackend.build`. Een
   weigering van emitter, lader of backend wordt `UcharBouw.NietGedragen` met uchars zin. Daarnaast weigert
   `UcharBouw` een model met een verdeling die de emitter weglaat (`IrEmitter.ongeschrevenVerdelingen`), als marker:
   uchar noemt die alleen in een WARNING, en een KieBase zonder die verdeling zou stil antwoorden.
7. Markeroordeel: `Build.dekking()` bevat alleen LOWERED-regels, want de facade weigert elk ander verdict. Het oordeel
   komt daarom uit de weigering van de exec-tak zelf (`CaseRunner.Excluded` "transpiler <verdict>: <soort>: <naam> —
   …"); de constructnaam leest `UcharBouw` uit die zin (zie restpunt 4).
8. Dienst: per entrypoint eigen bean-classloader (uit de build; `VerzoekMapper` en `Uitvoerder` gebruiken die); de
   samenstelling prelude + gescopete regelbestanden bleef. Met `allowMarkers` wordt een entrypoint met een marker
   geregistreerd zonder KieBase: `/info` noemt de marker (dekking leeg), elk verzoek krijgt 503 met uchars reden
   (`BeslisserKern.NietUitvoerbaar`, DienstHttp en BeslisResource); strikt weigert de start (exit 3), zoals voorheen.
   De scopecontrole gebruikt uchars graaf (`producentIndex(model, model.prioritizedDefaults)`, `verbruikt(r,
   bronNavigatie(model))`). `Uitvoerder` laadt `FactModelGenerator.PAKKET + ".Inconsistentie"` en faalt luid als die
   klasse ontbreekt (U4).
9. Engine-facade: `compile(bron)` gaat via `UcharBouw` en gooit `RegelSpraakCompileException` ook bij een weigering
   van uchar; `compile(bron, beanLoader)` verviel (uchars build genereert de beans); nieuw `nieuw(objecttype)`,
   `beanLoader()`, `model()` en `Session.facts(String)`; `Session.fire` zet de context-classloader op de beans.
10. Scenariorunner: uchars `ScenarioResolver` → `CaseEmitter.metScope` + `IrEmitter.emit` → casebestanden in een
    tijdelijke map → `CaseRunner.rapport` → uchars `ScenarioResultaat`, hernoemd naar de scenarionaam. Een weigering
    van de emitter maakt elk scenario MARKER. `--uitleg` en `--parametersets` weigert de CLI bij naam (exit 2); een
    FAIL toont "verwachting <positie>" (uchars rapport kent geen bronregel).
11. CLI: `--drl` via de DRL-tak van de facade, exit 3 met uchars weigering; `--allow-markers` wordt geweigerd
    (exit 2), want die tak bouwt geen document met markers. De samenvattingsmodus bouwt op de exec-tak en eindigt met
    exit 3 als uchar het model niet draagt; `--graaf` gebruikt `bronNavigatie` en `model.prioritizedDefaults`.
12. `tools/testdata/run_tests.sh` eist nu exit 0 van beide `--drl`-runs: twee weigeringen gaven twee lege DRL's die
    byte-gelijk zijn.

### Verwijderde suites, met reden (U3)

Alle in 6ecfc0d; de geschiedenis (b002492) bewaart ze voor de triagekaart in uchar.

- `src/test` (29 klassen; `schema/FactModelCliTest` bleef en ging naar de toolchain-module):
  - toetsten uchars frontend, waarvan uchar een eigen versie heeft: BeslistabelDesugarTest, IrEmitterTest, IrTest,
    ParametersetTest, RationaalTest, RecursieTest, TyperingTest, ir/BronTest, resolver/KenmerkMatcherTest,
    resolver/MeervoudMatcherTest;
  - toetsten de verwijderde oude ScenarioResolver, die uchar heeft overgenomen met dezelfde drie testklassen:
    ScenarioParseTest, ScenarioTypeGateTest, ScenarioMutatieTest (de mutatiefixtures bleven, voor
    ScenarioMutatieRunTest);
  - toetsten de frontend zonder tegenhanger in uchar, op TOKA-fixtures: RuleCheckTest, ModelTest,
    BeslistabelTabelTest, StartpuntTest, RangtelwoordTest (triage: herschrijven op Jutterswet-fixtures in uchar);
  - toetsten de verwijderde transpiler- en runtimekopie: DrlTest (tekstpins op de oude DRL), PeriodeDekkingTest,
    transpiler/EscapeRegelNaamTest, transpiler/kern/{Dagsoorten,Namen,Operatoren}Test, runtime/{Breuk,
    RegelSpraakRuntime}Test (de TOKA-vrije onder deze zijn voor uchar, volgens het onderzoek);
  - toetsten de frontend/transpiler over de TOKA-casus: CasusTest, TokaDekkingTest (uchars gate 11 draagt de
    TOKA-pariteit);
  - GraafTest: toetste de graaf, die nu uchars is (uchars gate 15).
- `src/it` (10 testklassen + 39 fact-POJO's): de DRL≡exec-differentiaal op handgeschreven feiten
  (ExecModelDifferentialTest, DroolsExecutionTest, TokaVuurTest, StratificatieKetenTest, OvererfdeSubtypeSemantiekTest,
  ConfluenceFuzzTest, AccessorsTest, FactModelGeneratorTest, CorpusVuurTest, UcharLetReplayTest); toetsten verwijderde
  code, de POJO's stonden in uchars gegenereerde naamruimte `…facts`; uchars gates 12 en 13 draaien de differentiaal
  over het corpus.
- `engine/src/test`: AccessorsChildLoaderTest, ExecModelChildLoaderTest, ExecModelConsistentieChildLoaderTest —
  toetsten uchars runtime en transpiler via de verwijderde twee-argument-build.
- `tools/negatief-corpus/` (manifest, runner, snippets) met `verify-negatief-corpus`: toetste de grammatica, die uchars
  is; 11 van de 52 snippets dragen TOKA-domeinwoorden (onderzoek §3), dus eerst herschrijven vóór uchar.

In 5a2c209: ParametersetScenarioTest s1b, s2, s3, s5 (vintagekeuze per scenario-peildatum; die route bestaat niet
meer, zie restpunt 3); `toolchain/src/test/.../facts/Persoon.java` (een handgeschreven klasse in uchars
`…facts`-naamruimte, U4).

### Omgezette tests, met reden (U5)

- RegelSpraakEngineTest, PeildatumSessieTest: feiten zijn de gegenereerde beans (`engine.nieuw`, lezen/schrijven via
  `RegelSpraakRuntime`). "ontbrekende feitklasse faalt" werd "de feitklasse wordt gegenereerd"; nieuw: een
  niet-gelowerde regel faalt bij `compile` met de regelnaam.
- RelatiesTest: de wederkerige partnerrelatie gaf NIET_ONDERSTEUND omdat het oude feitmodel geen partnerveld had;
  uchars generator draagt de wederkerige zelfrelatie, dus beide kanten worden nu geschreven en getoetst.
- DienstHttpTest: de marker-entrypoint toont geen MARKER-dekking meer (uchar bouwt niets); nieuw: een verzoek aan dat
  entrypoint krijgt 503 met uchars reden.
- GoldenOverHttpTest, GoldenOverQuarkusTest: op uchars ResolvedScenario (scope als vormen, getallen als lexeem,
  `regel` bij INCONSISTENT); drie scenario's die uchars resolver weigert, staan in `UCHAR_WEIGERT` met een fragment van
  de weigering en falen zodra uchar ze weer aanneemt; de slottelling eist 26 gereproduceerd + 3 geweigerd.
- ScenarioRunnerTest: marker_scope eist MARKER met de regelnaam in de reden en lege dekking (er is geen MARKER-soort
  meer in uchars rapport); het scenario "met peildatum" heet anders, want in uchars grammatica is die frase het
  kop-token.
- ScenarioOrakelTest: het Java-API-been bouwt via `UcharBouw` op de exec-tak (was de oude DRL-transpiler); bij
  kenmerk_zelfstandig_uit_kenmerk weigert uchar de regel met de een-vorm over een bijvoeglijk kenmerk, wat de test nu
  eist, en het been bouwt het model zonder die regel.
- ScenarioMutatieRunTest: eist de positie van de falende verwachting in plaats van "regel N" in de diagnose.
- ParametersetScenarioTest: s1 eist nu de ResolveFout van uchars parameterpoort; s4/s6 zonder sets.
- VerbalisatorTest: via de engine-facade in plaats van de scenarioroute; de gouden zin over de TOKA-minderjarigheid
  staat nu in UitlegDienstTest (`gouden_uitlegzin_minderjarig`, via de dienst, met de parameter in het verzoek).
- NegatieStratificatieTest, ScopeKetenTest, FactModelCliTest: op uchars API (build via de facade, graaf met
  navigatie, pad vanuit de modulemap).

### Restpunten

1. Docker/kind volgen de reactor nog niet: `docker/Dockerfile` en `.dockerignore` kopiëren `vendor/` en ketenen drie
   installs, `scripts/kind-smoke.sh` draait `mvn -f service/pom.xml`; `make image` en `make kind-smoke` falen tot de
   Docker-kaart. Alleen de FQN in `docker/entrypoint.sh` is hernoemd.
2. uchars scenarioresolver weigert 3 van de 29 gouden TOKA-scenario's: in één noemt de scope een feitcreatie die de
   resolver als "bestaat niet in het document" meldt, twee hebben een scope in een document met een
   startpuntbepaling. Daardoor `make scenario-test` "GOLDEN FAALT" (26/29). Een vraag voor uchar (kaart daar).
3. De scenarioroute kent geen externe parametersets meer (uchars parameterpoort eist elke gelezen parameter in de
   situatie) en geen uitleg per scenario (`CaseRunner.rapport` geeft geen gevuurde feiten terug). Uchar-kaart (externe
   parameters in de scenariolaag, of een publieke na-vuur-weergave) of de volgende kaart.
4. Het markeroordeel leest de constructnaam uit uchars weigeringszin (`UcharBouw.NIET_GELOWERD`). Een getypeerde
   weigering in uchar (bijv. `Excluded` met de `Dekking`) haalt die koppeling weg; de markertests vangen een
   gewijzigde zin.
5. De facade weigert de hele build bij de eerste niet-gelowerde constructie: per entrypoint is alleen de eerste marker
   bekend, en een niet-gelowerde regel in een gescopet bestand maar buiten de scope blokkeert nu het entrypoint (vroeger
   genegeerd). In de TOKA-descriptor komt het niet voor. Een scope op vormniveau (`CaseEmitter.metScope`) zou dat
   oplossen; volgende kaart.
6. `ScenarioRunnerCli --manifest` houdt het TOKA-eigen eindoordeel (29 exit-0-regels); generiek maken hoort bij de
   kaart met de stabiele ingangen.
7. In de pariteitszone verwijzen `examples/toka-scenarios/manifest`, `examples/toka-scenarios/scenario-marker-exit3.rgs`
   en `examples/toka-reconciliatie/README.md` naar het verwijderde `docs/SCENARIOTOETSEN.md` of het verplaatste
   `docs/grammar-divergences.md`; deze kaart mag de zone niet aanraken.
8. `NOTICE` noemt het project nog "RegelSpraak-java" (licentietekst, buiten U7).
9. Buiten U5 en onaangeroerd: playground-payload uit nluwv f3613bd3, replay van uchars goldens door de dienst, de drie
   dialect-tweelingen via HTTP, `bin/`-ingangen, nieuwe vloeren.
10. Triage van de verwijderde tests in uchar (43 TOKA-vrije runtime/kern-tests, 170 frontendtests zonder tegenhanger,
    de IT-differentiaal): kaart in uchar, geschiedenis op b002492.

## Keuring (2026-09-26, hoofdsessie)

Gekeurd op `419d4c9` in de worktree en geland met een fast-forward. Gemeten:
`scripts/check-pin.sh` groen; `git grep io\.github\.yhavinga` leeg buiten
`backlog/` en `docs/historisch/`; geen pakket `dev.uchar.regelspraak` buiten
`uchar/` dat niet met `.toolchain` begint; `vendor/`, `SPEC_REV`,
`scripts/uchar-conformance.sh`, `.claude/` en `src/main/java` bestaan niet;
`mvn -B -o verify` op de root groen (uchar-backend 840, toolchain 109, service
169, service-quarkus 33 tests, geen skips); geen tripwirewoorden in de diff;
alle commitboodschappen binnen 72 tekens. `NOTICE` hernoemd in de landing.

Triage van de restpunten: 1 (Docker/kind) en 8 (`NOTICE`, in de landing
gedaan) naar de kaart `ingangen-en-deploy`; 3 (uitleg en parametersets van de
scenarioroute), 5 (scope op vormniveau via `CaseEmitter.metScope`, zodat een
niet-gelowerde regel buiten de scope het entrypoint niet blokkeert), 6
(`--manifest` generiek), 9 en de drie geweigerde pariteitsscenario's uit 2
(als redendragende uitsluiting in de manifestpoort) naar de kaart
`dienst-gedrag-en-poorten`; 2 (de scenariolaag weigert een scope die een
feitcreatie noemt, en een scope in een document met een startpuntbepaling), 4
(een getypeerde weigering uit de facade) en 10 (triage van de verwijderde
tests) zijn open punten voor uchar, vastgelegd in uchars PLAN §9, geen kaart
nu (maintainer, 2026-09-26: eerst de verhuizing). 7 blijft: de pariteitszone
wordt niet aangeraakt.
