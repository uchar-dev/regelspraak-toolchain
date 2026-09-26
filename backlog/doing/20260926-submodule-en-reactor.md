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
