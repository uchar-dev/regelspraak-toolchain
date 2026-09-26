# ingangen-en-deploy: stabiele ingangen zonder klassenamen, en de containerbuild op de reactor

De werkbank en de UWV-regelsets riepen de oude toolchain aan met
klassenamen (`…resolver.Resolver`, `…engine.ScenarioRunnerCli`,
`…service.http.DienstMain`) en interne buildpaden (`engine/target/…`,
`service/target/.svc-cp`, `lib/antlr-…jar:out`): dertien klassenamen en
zestig padvoorkomens in zes Makefiles en smoke-scripts (onderzoek
2026-09-26, §2 G2). Na de hernoeming en de reactor kloppen die niet meer.
De maintainer schrijft de UWV-regelsets opnieuw; deze kaart geeft ze
ingangen die geen klassenaam en geen buildpad lekken.

## U-besluiten (hoofdsessie, 2026-09-26)

- U1 `bin/regelspraak <opdracht>` met de opdrachten `check` (parse +
  resolve, diagnoses, exit ≠ 0 bij ERROR), `ir` (IR-document), `cases`
  (scenariolowering), `scenario` (lowering + replay + rapport; `--manifest`),
  `serve` (de JDK-dienst), `drl` (DRL-weergave), `graaf`, `facts`; één
  shaded jar of één classpath-bestand dat de reactor produceert; gelijknamige
  make-doelen (`make check FILE=…`, `make serve`, …) die alleen `bin/
  regelspraak` aanroepen.
- U2 Dockerfile op de reactor: `COPY uchar/frontends/regelspraak` en
  `uchar/backends/drools` (de buildcontext bevat de uitgecheckte
  submodule; `.dockerignore` sluit `.git` en het `.git`-bestand van de
  submodule uit), één `mvn -B package -DskipTests` op de root, geen `make`/
  `curl` meer (de parser komt uit de antlr4-maven-plugin); `entrypoint.sh`
  via `bin/regelspraak serve`. Helm en `scripts/kind-smoke.sh` bijgewerkt;
  kind-smoke groen.
- U3 CI (`.github/workflows`): checkout met `submodules: recursive` en een
  leestoken of deploy key voor uchar-dev/uchar (de maintainer zet het
  secret; de workflow verwacht `UCHAR_READ_TOKEN`), `mvn -B verify`,
  `scripts/check-pin.sh`, de poorten van de vorige kaart.
  - Bijgesteld door de maintainer (2026-09-26): geen GitHub Actions; de reeks draait lokaal als `make gates`.
- U4 Klaar: de werkbank-aanroepen uit het onderzoek (§7.3) zijn te
  herschrijven naar `bin/regelspraak …` zonder dat een pad of klassenaam
  van deze repo nodig is (toon dat met een proefschrift van de drie
  regelset-Makefiles in het Result, zonder de regelsets zelf te wijzigen);
  `make image` bouwt; `make kind-smoke` groen; README beschrijft de
  ingangen.

May touch: `bin/`, `Makefile`, `docker/`, `helm/`, `scripts/`,
`.github/`, README. Niet: `uchar/`, de werkbank, de UWV-regelsets.

## Erbij na de landing van `submodule-en-reactor` (hoofdsessie, 2026-09-26)

Restpunt 1 van die kaart is het uitgangspunt van U2: `docker/Dockerfile` en
`.dockerignore` kopiëren nog `vendor/` en ketenen drie installs,
`scripts/kind-smoke.sh` draait `mvn -f service/pom.xml`; `make image` en
`make kind-smoke` falen tot deze kaart. `NOTICE` is bij de landing al
hernoemd.

## Erbij na de landing van `dienst-gedrag-en-poorten` (hoofdsessie, 2026-09-26)

Restpunt 8 van die kaart: de poorten (`make poorten`: `check-tweelingen`,
`check-dienst-replay`) hebben `python3` nodig naast `java` en `mvn`, dus de
CI-runner ook; `DienstMain` kent nu `--host` en een `.json`-bron (een
`uchar-ir-v1`-document), en `bin/regelspraak serve` geeft die door; de
Quarkus-adapter spiegelt het 503-MARKER-antwoord en de `soort` in een 400
met de hand, zonder pariteitstest voor foutlichamen — voeg die toe als hij
binnen de kaart past (`InfoParityTest` als voorbeeld), anders als benoemd
restpunt. CI op GitHub start op dit moment geen jobs (een accountprobleem
bij GitHub, door de maintainer op te lossen): toets de workflow lokaal, stap
voor stap in de volgorde van de workflow; de eerste echte run volgt later.

## Result (2026-09-26, bouwer)

**Uitkomst.** De distributie heeft één ingang, `bin/regelspraak <opdracht>` (`check`, `ir`, `cases`, `scenario`,
`serve`, `drl`, `graaf`, `facts`), die draait uit één jar die de reactor bouwt; de make-doelen met dezelfde naam roepen
alleen `bin/regelspraak` aan. Het image bouwt op de reactor en start via `bin/regelspraak serve`; helm en
`scripts/kind-smoke.sh` volgen. De Quarkus-adapter heeft een pariteitstest voor zijn foutlichamen; die vond één
afwijking (de 404), die is rechtgezet. U3 is bijgesteld door de maintainer: geen GitHub Actions, maar `make gates`,
dat de hele reeks lokaal draait (pin, reactor, tweelingen, replay, gouden manifest, image, kind-smoke) en bij de eerste
rode stap stopt; op de eindboom groen in 3:02. `uchar/` is niet gewijzigd, `~/.m2/repository/dev` bevat geen `uchar`.
De werkbank-aanroepen zijn in de proef hieronder herschreven zonder klassenaam of buildpad van deze repo.

### Commits (basis 9821576, branch card/ingangen-en-deploy)

- 8f3090a claim (git mv new → doing).
- 4242f86 `RegelSpraakCli`: `check`, `--drl` en `--facts` lezen een keten van bronnen (graaf deed dat al); een
  diagnose in een keten noemt `bestand:regel`; `KetenTest` (3 tests).
- ba6d1f3 de jar (`maven-shade-plugin` in `service/pom.xml`, versie in de ouder-pom), `bin/regelspraak`, de make-doelen.
- 0486f0b de poorten tweelingen en replay starten de dienst via `bin/regelspraak serve`.
- b6387e1 `docker/Dockerfile`, `.dockerignore`, `docker/entrypoint.sh` op de reactor.
- 836e14c `scripts/kind-smoke.sh` en `helm/beslisservice` (Chart, values, NOTES).
- 96258e3 `FoutParityTest` (6 tests) en de 404 van `BeslisResource` gelijk aan die van `DienstHttp`.
- d83996c README (de ingang, de make-doelen, de container) en `docs/ENGINE.md`.
- 04b8410 `make gates` (`scripts/gates.sh`) en de README-sectie over de poorten.
- (laatste) dit Result en de regel onder U3.

Bij de bijstelling van U3: de workflow (`.github/workflows/poorten.yml`) was gebouwd, gecommit en lokaal getoetst
(meting 12); de commit is uit de ongepushte branch gehaald (`git rebase --onto`) en de README-commit is zo gesplitst
dat geen commit de workflow noemt. `git log -p 9821576..HEAD` bevat geen `.github`, `workflow` of `UCHAR_READ_TOKEN`.

### Metingen (commando → uitkomst)

1. Nulmeting op 8f3090a: `mvn -B -o verify` → BUILD SUCCESS, 49,6 s; 840/110/180/33, skip-pin PASS in alle drie.
2. De jar: `mvn -B -o -DskipTests package -pl service -am` → 5,0 s; `service/target/regelspraak.jar` 16,8 MB, 9061
   bestanden; geen JUnit erin (0 treffers op `org/junit`, `opentest4j`); `META-INF/services/…ComponentsSupplier`
   samengevoegd in de volgorde static, dynamic: dezelfde als op de classpath (plaats 12 en 15) en in de gebundelde jar
   van uchars backend. De eerste build schreef ongevraagd `service/dependency-reduced-pom.xml` (shade 3.6.0 doet dat
   ook met `outputFile`, en maakt hem dan de POM van de module); verwijderd en uitgezet, daarna niet meer geschreven.
3. `bin/regelspraak` per opdracht, vanuit andere mappen dan de repo:
   - `check` op één bestand → exit 0; op een keten van twee → één model, exit 0; met een fout in het tweede bestand →
     `ERROR (regel.rgs:3): …`, exit 1 (één bestand: uchars eigen `ERROR (line 3): …`).
   - `ir` op `jutterswet-uitsluiting-rgs/bron.rgs` → byte-gelijk aan uchars `ir.json`; `cases` met `scenario.rgs` → 4
     casebestanden, elk byte-gelijk aan die van de golden, plus `<map>/ir.json`.
   - `scenario` → 4× PASS, exit 0; met `--uitleg` een zin per scenario; `scenario --manifest` vanuit een
     regelsetachtige map met relatieve paden → 3 regels, 3 ok (waarvan één die exit 1 verwacht).
   - `serve` op een tweeling met `--host 127.0.0.1 --poort 0` → "luistert op poort …", `/info` 200, met
     `PLAYGROUND=true` `/playground` 200; de PID van het achtergrondproces is die van de JVM (`exec`), `kill` stopt de
     dienst.
   - `drl` → exit 3 met uchars weigering op een defaultsgroep; op drie andere goldens exit 0 met 8, 3 en 2 regels.
   - `graaf` → HTML, met `--mmd` Mermaid; `facts` op een keten van twee → 4 klassen.
   - Aanroepfouten, 18 gevallen (geen opdracht, onbekende opdracht, geen bron, onbekende optie, ontbrekend bestand,
     ontbrekende uitvoer): elk exit 2 met een regel op stderr; `help` exit 0.
   - Onder `/bin/bash` 3.2 (macOS) en via een symbolische koppeling: dezelfde uitkomsten. `REGELSPRAAK_JAR` naar een
     kopie → draait zonder bouw; naar een niet-bestaand bestand → exit 1 met de naam. `JAVA_OPTS=-Xmx64m` bereikt de
     JVM (MaxHeapSize 67108864).
4. Verouderde jar: na `touch` op uchars grammatica bouwt `bin/regelspraak check` eerst ("de jar is ouder dan
   uchar/frontends/regelspraak/grammar/RegelSpraakLexer.g4 en wordt gebouwd"), samen 5,9 s; daarna 0,5 s zonder bouw.
5. Make-doelen: `check`, `ir` (out/ir.json byte-gelijk aan de golden), `cases` (4 + ir.json), `scenario` (met
   `--trace`), `drl`, `graaf MMD=1`, `facts` (TOKA-fundering: 5 klassen, javac PASS), `check` zonder FILE → exit 2 via
   de melding van `bin/regelspraak`; `scenario-test` → "manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch".
6. Poorten door de jar (0486f0b): `check-tweelingen` PASS, 18 cases, 6,1 s; `check-dienst-replay` PASS, 215 / 188
   gespeeld / 494 cases / 27 uitgesloten, 45 s (was 47-48 s op de classpath). Van de 188 kwamen er 76 uit `bron.rgs`
   en 112 uit `ir.json`: de `.json`-bron en `--host` gaan door `bin/regelspraak serve`. Geen achtergebleven processen.
7. Buildcontext (proefimage op alpine met `COPY . /ctx`): 7,03 MB, 454 bestanden, 0 `.git`, 0 `target`; van `uchar/`
   alleen `frontends/regelspraak` en `backends/drools`, zonder `dependency-reduced-pom.xml`. Proefimage verwijderd.
8. `make image TAG=ingangen` (b6387e1) → 42,8 s, waarvan basisimages 6 s en de Maven-stap 33,6 s (online in de
   container); image 695 MB (het image uit juli: 694 MB). Opnieuw zonder cache (`docker build --no-cache`, zelfde
   argumenten) → 30,1 s, Maven-stap 27,7 s. Container met de standaardwaarden: `/info` na 2 s, 29 entrypoints, 29
   uitvoerbaar, HEALTHCHECK `healthy`; met de markerdescriptor exit 3 en de regel "MARKER: regel 'heffing markert' …";
   met `ALLOW_MARKERS=true` start hij, `/info` noemt de marker, een verzoek krijgt 503.
9. `make kind-smoke` met het bestaande cluster `regelspraak` (node-container sinds 5 weken gestopt) → stopt na 0,2 s
   met de melding dat het cluster niet antwoordt en hoe het te starten of te verwijderen is; dat cluster is niet
   aangeraakt. De eerste run op een nieuw cluster `regelspraak-ingangen` faalde in H3: poort 18081 op 127.0.0.1 is op
   deze machine bezet door Docker Desktop, de port-forward bond alleen [::1]; en de PID van een subshell liet twee
   `kubectl port-forward`-processen achter (opgeruimd). Na de fix (vrije poort, kubectl als eigen PID): groen op het
   hergebruikte cluster in 43 s, en groen op een vers aangemaakt cluster in 1:18: `/info` 29/29 uitvoerbaar, H2
   `GoldenOverHttpTest` 30 tests groen (26 over HTTP, 3 geweigerd, 1 telling), H3 exit 3 en 503, H4a minderjarig false,
   H4b true. Geen achtergebleven port-forward; het H4-overlaybestand is weg.
10. `FoutParityTest`: eerste run 5 van 6 groen; de 404 verschilde (`'/bestaat-niet'` bij `DienstHttp`,
    `'bestaat-niet'` bij Quarkus). Na de fix 6/6.
11. `make poorten` (vóór `make gates` bestond, op dezelfde code) → exit 0, 1:48: `check-pin` PASS; `mvn -B -o verify`
    BUILD SUCCESS 48,2 s, 840/113/180/39, skip-pin PASS (vloeren 110/180/33); tweelingen 18 cases; replay 215/188/494/27
    in 45 s; manifest 32/29/3/0.
12. De workflow, lokaal getoetst vóór de bijstelling van U3: een checkout zoals `actions/checkout` hem doet (origin
    `https://github.com/uchar-dev/regelspraak-toolchain`, `git submodule update --init --force --depth=1 --recursive`
    met een extraheader, het token van de lokale `gh` als leestoken: d67f5da1 in 4 s), daarna de stappen in
    `maven:3.9-eclipse-temurin-21` (Ubuntu 24.04, Temurin 21.0.12.1, lege `~/.m2`, `python3` en `make` via apt): pin
    PASS; `mvn -B verify` online 151 s, 1157 downloads, 840/113/180/39; tweelingen 18 cases; replay 215/188/494/27 in
    43 s; manifest 32/29/3/0; samen 3:36. Blijft als uitkomst staan: de reactor en de poorten zijn groen op Java 21
    met een lege Maven-repository; op deze machine is alleen Java 25 geïnstalleerd.
13. `make gates` rood: met een onbekend bestand in `uchar/` → "gates: 1 pin ROOD …", de laatste logregels, "gates:
    ROOD bij stap 1 (pin) …; de stappen daarna zijn niet gedraaid", exit 1 (bestand weer weg, `uchar/` schoon).
14. `make gates CLUSTER=regelspraak-ingangen TAG=ingangen` op de boom van 04b8410 → exit 0, 3:02:
    ```
    gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit; …
    gates: 2 reactor    GROEN     49s  testgevallen per module 840/113/180/39
    gates: 3 tweelingen GROEN      6s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen
    gates: 4 replay     GROEN     51s  188 goldens, 494 cases (pin 188)
    gates: 5 manifest   GROEN      1s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
    gates: 6 image      GROEN      1s  beslisservice:ingangen
    gates: 7 kind-smoke GROEN   1m14s  H2 PASS, H3 PASS, H4 PASS
    gates: GROEN — 7 stappen in 3m02s; de uitvoer staat in out/gates/
    ```
    De image-stap was een cachetreffer (de buildcontext was sinds meting 8 niet veranderd); de kind-smoke maakte het
    cluster nieuw aan.
15. `make classpath && bash tools/testdata/run_tests.sh` → "TOTAL: 7 pass, 0 fail".
16. Na afloop: `git status --short` alleen de kaart (dit Result), `git -C uchar status --porcelain` leeg,
    `ls ~/.m2/repository/dev` → alleen `resteasy`; de diff 9821576..HEAD raakt `examples/`, `specification/`,
    `uchar/` en `backlog/done/` niet; geen D-/U-nummers en geen van de twee regimewoorden in diff of
    commitboodschappen; elke regel van elke commitboodschap binnen 72 tekens.
17. Opgeruimd: het cluster `regelspraak-ingangen` is verwijderd, de images `beslisservice:ingangen`, `-h4a`, `-h4b` en
    `-nocache` zijn weg, net als de kloon van de workflowproef en `out/`. Blijven staan: de getrokken basisimages
    (`maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jdk-jammy`) en de BuildKit-cache; de images
    `beslisservice:dev*` uit juli zijn niet aangeraakt.

### Besluiten die de kaart aan de bouwer liet

1. **Eén gebundelde jar, geen classpath-bestand.** De container en `bin/regelspraak` draaien dan uit één bestand dat
   niet van `~/.m2` of van `target/`-mappen afhangt. Hij komt uit de dienstmodule, die van alles afhangt, met
   `outputFile` (`service/target/regelspraak.jar`), zodat de gewone jar van de module het artefact blijft waar de
   Quarkus-adapter van afhangt; `createDependencyReducedPom` staat uit (meting 2). `verify` werd er niet trager van
   (49,3 s tegen 49,6 s).
2. **De verdeling over de Java-ingangen staat in het script**, niet in een nieuwe Java-klasse: de klassenamen staan
   alleen in `bin/regelspraak`. Het script `exec`t de JVM, zodat exitcode, signalen en PID die van de opdracht zijn.
   Vorm van de argumenten: eerst de uitvoer waar de opdracht een bestand schrijft (`ir`, `graaf`, `facts`); `cases
   <map>` schrijft `<map>/ir.json` naast de casebestanden, zoals uchars corpus ze legt. `check`, `ir`, `cases`, `drl`,
   `graaf` en `facts` controleren hun argumenten zelf (exit 2); `scenario` en `serve` geven hun opties door aan hun
   eigen parser.
3. **Bouwen bij verouderd.** In een checkout bouwt `bin/regelspraak` de jar als hij ontbreekt of ouder is dan een van
   zijn bronnen (de poms, `toolchain/src/main`, `service/src/main`, uchars grammatica en frontendbron, uchars backend),
   offline en zonder tests, met een melding op stderr. Offline, omdat deze repo en de regelset-Makefiles offline
   bouwen; de eerste build op een machine heeft één keer het netwerk nodig (README, Building). `REGELSPRAAK_JAR` wijst
   een kant-en-klare jar aan die nooit gebouwd wordt (de container).
4. **Ketens in de CLI** (4242f86): `check` is de ingang die de werkbank het meest gebruikt, over `$(RULES)`, veel
   bestanden. Een diagnose in een keten noemt `bestand:regel`, zoals uchars `casesModus` doet; één bestand houdt de
   tekst van uchar. Buiten de lijst "May touch" van de kaart, omdat de ingang anders alleen het eerste bestand las.
5. **Make-doelen**: overal `FILE`; `scenario` is nieuw (de CLI), `scenario-test` is alleen nog het gouden
   TOKA-manifest (de `FILES`-vorm verviel, die heet nu `make scenario`); `serve FILE=… [POORT=…]`; `facts [FILE=…]` met
   de TOKA-fundering als standaard en een vaste uitvoermap (`out/facts`, want het doel doet `rm -rf`); `classpath`
   blijft voor `tools/testdata/run_tests.sh`, met `-DincludeScope=runtime` in plaats van het genegeerde
   `-Dmdep.includeScope` (de oude classpath bevatte JUnit: 48 elementen, nu 39).
6. **De poorten door de jar** (0486f0b): de replay bewijst zo dat de gebundelde jar over 188 goldens hetzelfde zegt als
   de classpath, en test de ingang die de container en de werkbank gebruiken.
7. **Dockerfile**: `mvn -B -ntp -DskipTests package -pl service -am` in plaats van de hele reactor: het image heeft
   alleen de dienst nodig, en de Quarkus-afhankelijkheden hoeven zo niet de container in. De pom van de Quarkus-adapter
   gaat mee, omdat Maven elke module van de root-pom leest. `-DskipTests` zoals de kaart zegt (de testbronnen
   compileren mee). Geen BuildKit-cachemount: een koude build kost 30-43 s. `.dockerignore` is een toelatingslijst;
   gevolg: `DESCRIPTOR_SRC` moet onder `docker/` of `examples/` liggen (in README en Dockerfile genoemd). De
   runtime-stage houdt `curl` voor de HEALTHCHECK; de build-stage heeft geen `make` of `curl` meer.
8. **kind-smoke**: elke kubectl- en helm-aanroep noemt de context `kind-$CLUSTER`, een bestaand cluster dat niet
   antwoordt stopt de run, de port-forward kiest een vrije poort op 127.0.0.1 (`LOCAL_PORT` verviel), het
   H4-overlaybestand verdwijnt ook bij een fout, H3 toetst het 503-antwoord. De namen H2/H3/H4 bleven.
9. **Helm**: alleen tekst: omschrijving en `appVersion` (`0-SNAPSHOT`, de versie van de reactor; `2.3.0` was die van
   de oude repo), NOTES en een commentaar in values; de templates veranderden niet.
10. **`make gates`** (U3 zoals bijgesteld): een script (`scripts/gates.sh`) in plaats van make-afhankelijkheden, omdat
    `make poorten` zelf `verify` draait en de reeks dan twee keer zou bouwen. Zeven stappen: de vijf van `make poorten`
    (ook het gouden manifest, dat de bijstelling niet noemde; zonder die stap zou `make gates` minder toetsen dan `make
    poorten`), dan `make image` en `make kind-smoke`. Per stap één regel met uitkomst, duur en de kern van de uitvoer;
    de volledige uitvoer in `out/gates/<nr>-<stap>.log`; bij rood de laatste 20 logregels en de eindregel met de stap.
    `TAG`, `DESCRIPTOR_SRC` en `CLUSTER` gaan door, als omgevingsvariabele of op de make-regel.
11. **De pariteitstest paste binnen de kaart**: een gewone JUnit-test zoals `InfoParityTest`, die `BeslisResource`
    rechtstreeks aanroept en `DienstHttp` over HTTP, over dezelfde kern; zes gevallen (400 met `soort`, leeg, geen
    JSON, `null`, 404, 503 MARKER).
12. **`docs/ENGINE.md`** (buiten "May touch") beschreef de dienst nog met `java -cp … DienstMain` en zei dat
    `make image` faalt; beide regels zijn bijgewerkt.
13. **Vloeren** niet verhoogd (toolchain 110 bij 113 gemeten, service-quarkus 33 bij 39): groei vraagt geen bump.

### Proef: de werkbank-aanroepen op `bin/regelspraak`

Gelezen: alleen de drie regelset-Makefiles (`rulesets/regelspraak-rules/{herleving-ww,dagloon-ww,verrekening-ww}/
Makefile`), hun drie `tools/dienst-smoke.sh`, het verzamel-Makefile van `regelspraak-rules` en het Makefile van de
werkbank; niets gewijzigd. Geteld in de zes bestanden (zoals het onderzoek, §2 G2): 13 klassenamen
(`…resolver.Resolver` 3, `…engine.ScenarioRunnerCli` 3, `…service.http.DienstMain` 7) en de buildpaden
`engine/pom.xml` 13, `engine/target` 9, `service/pom.xml` 7, `service/target` 21, `lib/antlr-4.13.2-complete.jar:out`
3, de doelen `resolver` 3 en `scenario-manifest` 3, `mvn` 30 keer. Herschreven, per doel (`<descriptor>` staat voor
het descriptorpad dat het Makefile al noemt):

```make
TOOLCHAIN ?= $(abspath ../..)/regelspraak-toolchain   # de werkbank geeft TOOLCHAIN=$(ROOT)/toolchain mee

check:                                                # 3×; de twee lint-regels blijven
	$(TOOLCHAIN)/bin/regelspraak check $(RULES)

scenario-test:                                        # 3×; zelfde manifestvorm, paden relatief aan $(HERE)
	@test -s $(HERE)/scenarios/manifest || { echo "scenarios/manifest is missing/empty"; exit 2; }
	@cd $(HERE) && $(TOOLCHAIN)/bin/regelspraak scenario --manifest scenarios/manifest

serve:                                                # 3×; PLAYGROUND=true bereikt de JVM via de omgeving
	$(TOOLCHAIN)/bin/regelspraak serve $(HERE)/dienst/<descriptor> \
		$$(sed "s|^|$(HERE)/|" $(HERE)/dienst/bronnen-manifest.txt) --poort $(POORT)

serve-composed:                                       # dagloon: de lus die $$bronnen opzoekt blijft, zonder cd en mvn
	… $(TOOLCHAIN)/bin/regelspraak serve $(HERE)/dienst/<descriptor> $$bronnen --poort $(POORT_COMPOSED)
```

In elk van de drie `tools/dienst-smoke.sh` vervallen de drie `mvn`-regels en `CP`; de start wordt:

```bash
"$TOOLCHAIN/bin/regelspraak" serve "$DESCRIPTOR" $BRONNEN --host 127.0.0.1 --poort 0 > "$LOG" 2>&1 &
```

De poortregel "luistert op poort N", de wachtlus op `/info`, `PID=$!` en de trap blijven kloppen: `bin/regelspraak`
`exec`t de JVM, dus `$!` is de JVM. `image-smoke` en `image-smoke-composed` noemen geen klassenaam of buildpad: ze
gebruiken `beslisservice:$(TAG)` en het contract `DESCRIPTOR`/`BRONNEN_MANIFEST`, dat gelijk bleef; `make -C
$(TOOLCHAIN) image` bouwt het image. Na het herschrijven: 0 klassenamen, 0 buildpaden, 0 `mvn`, geen aanroep van een
niet-bestaand doel; het enige pad van deze repo is de ingang zelf (13 aanroepen: 3 `check`, 3 `scenario`, 4 `serve` in
de Makefiles, 3 `serve` in de smoke-scripts). Verschil in uitvoer: `--manifest` drukt per regel "OK (exit N)" of
"MISMATCH (verwacht exit X, kreeg Y)" en een samenvatting, maar niet meer de uitvoer van een mislukt scenario; die
geeft `bin/regelspraak scenario <scenario.rgs> <companions…>`.

De vormen zijn gedraaid in een nagebouwde regelset op eigen Jutterswet-materiaal (`/tmp/tc/proef/werkbank-sim`:
`basis/rules` en `jutter-ww/{rules,scenarios,dienst,tools}`, een Makefile en een `dienst-smoke.sh` met precies deze
recepten, `TOOLCHAIN` op deze worktree): `make check` → exit 0 over de keten basis + rules; `make scenario-test` → 1
regel OK; `make dienst-smoke` → de dienst op een vrije poort, POST gaf `penninggerechtigd: true`, PASS. De
UWV-regelsets zijn niet gedraaid.

### Restpunten van eerdere kaarten

- `submodule-en-reactor` restpunt 1 (Docker en kind volgen de reactor niet): gedaan. Restpunt 8 (`NOTICE`) was bij de
  landing gedaan. Blijven staan: 7 (verwijzingen in de pariteitszone naar verplaatste documenten; de zone wordt niet
  aangeraakt) en de open punten voor uchar (2, 4, 10).
- `dienst-gedrag-en-poorten` restpunt 8: `python3` staat bij wat `make gates` lokaal vraagt (README), `--host` en de
  `.json`-bron gaan via `bin/regelspraak serve` (meting 6), de pariteitstest voor foutlichamen is er (meting 10).
  Blijven staan: 1, 2, 5, 6, 7 (uchar) en 3, 4 (presentatie, na te gaan bij het omhangen van de werkbank).

### Restpunten

1. **CLAUDE.md** van deze repo zegt nog "Green means `make poorten` passes" en noemt `make gates` niet; de README noemt
   `make gates` nu de controle vóór elke landing. CLAUDE.md viel buiten deze kaart.
2. **Het cluster `regelspraak`** op deze machine (node-container gestopt) is onveranderd. `make kind-smoke`, en dus
   `make gates`, stopt daar zonder `CLUSTER=` met de melding hoe het te starten of te verwijderen is; dat is aan de
   maintainer.
3. **Twee gelijktijdige aanroepen met een verouderde jar** bouwen tegelijk in dezelfde `target/`-mappen;
   `bin/regelspraak` heeft geen slot. De poortscripts bouwen daarom eerst en starten daarna pas diensten naast elkaar.
4. **Quarkus**: de pariteitstest vergelijkt de lichamen die `BeslisResource` bouwt. Antwoorden die JAX-RS geeft vóór
   de resource (een andere methode, een pad met meer dan één segment, een ander mediatype) zijn niet vergeleken.
5. **Werkbank, verzamel-Makefile** (geen van de drie regelset-Makefiles): het doel `werkbank` stopt de analyzer met
   `pkill -f "DienstMain.*--poort …"`. Dat patroon treft de JVM nog, want de hoofdklasse staat in zijn opdrachtregel,
   maar het noemt een klassenaam; `pkill -f -- "--poort …"` doet hetzelfde zonder.
6. `make classpath` en `tools/testdata/run_tests.sh` draaien nog op de classpath in plaats van op `bin/regelspraak drl`
   (`tools/` viel buiten deze kaart; CLAUDE.md beschrijft die volgorde).
7. De vloeren toolchain (110, gemeten 113) en service-quarkus (33, gemeten 39) zijn niet verhoogd.
8. `bin/regelspraak check` drukt, zoals de CLI al deed, per opgeloste regel een samenvattingsregel af; over een grote
   regelset is dat veel uitvoer vóór de diagnoses.

## Keuring (2026-09-26, hoofdsessie)

Gekeurd op `2d60cbc` in de worktree en geland met een fast-forward. Gemeten:
de greps op de oude pakketnaam, op `UCHAR_READ_TOKEN` en op `workflows/`
leeg; geen `.github/`; geen tripwirewoorden in de diff; commitboodschappen
binnen 72 tekens; `make gates CLUSTER=regelspraak-keur TAG=keur` groen in
3m03s: pin; reactor 840/113/180/39 tests; tweelingen 18 cases; replay 188
goldens, 494 cases (pin 188); manifest 32 regels, 29 ok, 3 uitgesloten, 0
mismatch; image; kind-smoke H2–H4. Buiten de May-touch-lijst en aanvaard:
`RegelSpraakCli` leest een reeks bestanden (de werkbank geeft er veel aan
`check`), `docs/ENGINE.md` bijgewerkt.

Open punten: 1 (`CLAUDE.md` van deze repo noemt `make gates`) in de landing
gedaan; 2 (het stilstaande kind-cluster `regelspraak`) aan de maintainer;
3 (geen slot op `bin/regelspraak` bij twee gelijktijdige aanroepen met een
verouderde jar) blijft staan; 4 (het `pkill`-patroon op een klassenaam in
het verzamel-Makefile van de werkbank) hoort bij het omhangen van de
werkbank.
