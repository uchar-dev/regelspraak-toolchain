# gates-own-tests-in-parallel: de toolchain test wat zij zelf bouwt, en draait parallel wat parallel kan

*Vastgelegd door de hoofdsessie op 2026-10-25, op besluit van de eigenaar: "waarom zou de toolchain iets testen wat
hij niet zelf gewijzigd heeft?", en parallel draaien wat parallel kan, zoals uchar het met zijn gates doet (uchar-kaarten
`20261025-gate-builds-when-stale`, `20261025-drools-suite-forks`, `20261025-parallel-gate-replays`).*

## Aanleiding

`make gates` duurt 11 minuten (gemeten 2026-10-25 op `423521fb`, pin uchar `321fb239`): pin 0 s, reactor 7m54s,
twins 9 s, replay 1m04s, manifest 2 s, image 35 s, kind-smoke 1m20s. De reactor (`mvn -B -o verify`) draait vier
suites: 1702 tests in `uchar/backends/drools` en 173 in `uchar-frontend`, beide de suites van uchar zelf, plus 388 en
53 in de modules van de toolchain. De suite van de Drools-backend alleen kost ±6 minuten. Uchar draait die twee suites
al in zijn gates 12 en 10, op de commit die de toolchain pint: een wijziging van uchar's code landt pas op `main` als
de volledige gate-set van uchar erop groen was (`WAY-OF-WORKING.md` §6 daar; een commit die alleen een kaart
toevoegt, draait gate 1), en de toolchain pint alleen commits van uchar's `main`. `check-pin` houdt de
versies van de toolchain gelijk aan die van uchar, dus de reactor bouwt uchar's modules zoals uchar ze bouwt.

Goal:
- **De eigen tests.** De reactor bouwt uchar's modules (`uchar/backends/drools`, `uchar-frontend`) zonder hun tests
  en draait de suites van `toolchain`, `service` en `service-quarkus` zoals nu, met hun skip-pin en hun vloeren. Wat
  de toolchain met uchar doet, blijft getoetst: de service-tests die de backend aanroepen, de tweelingen, de replay
  van uchar's goldens over HTTP, het manifest, het image en de kind-smoke.
- **Alleen een geteste pin.** `check-pin` faalt als de gepinde uchar-commit niet op uchar's `main` staat
  (`merge-base --is-ancestor` tegen `origin/main` van de submodule, met een melding die zegt dat de pin een gelande
  uchar-commit moet zijn, of dat `origin/main` van de submodule opgehaald moet worden). Dat vervangt het opnieuw
  draaien van uchar's suites.
- **Parallel wat parallel kan, na elkaar wat na elkaar moet.** Na de pin en de reactor draaien twins, replay en
  manifest tegelijk (elk start zijn eigen dienst op een vrije poort; ga na dat ze geen poort, map of bestand delen);
  het image en daarna de kind-smoke blijven na elkaar, na die drie. De reactor bouwt zijn modules parallel waar hun
  afhankelijkheden dat toelaten (`-T`), als de skip-pin-stap en de suites dat verdragen. Het rapport houdt één regel per
  stap in de vaste volgorde, met duur en kern; een rode stap noemt zichzelf met de laatste regels van zijn log, en de
  stappen na een rode stap draaien niet. Eén manier om de gates te draaien (`make gates`, en `make gates-no-docker`
  voor de stappen zonder docker): geen vlag, geen variabele, geen seriële modus ernaast.

Acceptance:
- (a) `make gates` groen; de wandkloktijd per stap en in totaal vóór en na, op dezelfde machine zonder andere runs
  ernaast. Verwacht: reactor van ±8 naar ±2 minuten, totaal van 11 naar ±5 minuten.
- (b) Rood eerst: een pin op een uchar-commit die niet op `main` staat (bijvoorbeeld een commit van een kaarttak) laat
  `check-pin` falen met de melding; een pin op `main` slaagt.
- (c) Een rode stap in de parallelle groep (bijvoorbeeld een twins-case met een verkeerde verwachting, als mutatie
  niet gecommit) maakt `make gates` rood met de naam van die stap en zijn log, en het image draait niet.
- (d) De vloeren van de eigen modules ongewijzigd; die van uchar's twee modules gaan uit `tools/check-skips.sh`
  (`FLOORS`), met de reden in het commit.
- (e) `CLAUDE.md` (Build and gates), `README.md` ("The gates"), de kop van `scripts/gates.sh` en de help van de
  `Makefile` beschrijven de huidige stand: wat de reactor test, dat een pin op uchar's `main` moet staan, welke stappen
  tegelijk draaien. Geen verhaal over hoe het was.

May touch: `scripts/gates.sh`, `scripts/check-pin.sh`, `pom.xml`, `uchar-frontend/pom.xml`, `tools/check-skips.sh`,
`Makefile`, `CLAUDE.md`, `README.md`, `docs/` waar het de gates noemt, deze kaart.

Out of scope:
- uchar zelf (de submodule wijzig je niet, behalve de pin); wat twins, replay, manifest, image en kind-smoke controleren.
- De forks van uchar's suite (uchar-kaart `drools-suite-forks`): die erft de toolchain alleen als zij die suite draait,
  en na deze kaart draait zij hem niet meer.

## Result (2026-10-25)

Tak `card/gates-own-tests-in-parallel`, vanaf main `cc547050`, de kaart naar `doing/` in `51326c01`; de pin blijft uchar
`321fb239`. Niet geland; dat doet de hoofdsessie.

- `5f1f7847` check-pin: de pin moet een commit van uchars main zijn (`merge-base --is-ancestor HEAD origin/main`).
- `2aeda2ed` reactor: uchars suites draaien niet meer in de toolchain (`pom.xml`, `Makefile` verify, `gates.sh`).
- `dc688a50` de twee servicepoorten herschrijven de jar niet meer (`bin/regelspraak jar`, `check-twins.sh`,
  `check-service-replay.sh`, de rij `jar` in README).
- `3c0d2374` gates: twins, replay en manifest draaien tegelijk (`scripts/gates.sh`, `Makefile` gates-no-docker).
- `846e776a` docs: CLAUDE.md (Build and gates), README (Building, The pin, The gates), docs/ENGINE.md (Building).

### Het mechanisme van de reactor

`make verify` en `gates.sh` draaien `mvn -B -o verify -Dmaven.test.skip.exec=true`. uchars backend bouwt met zijn eigen
pom, die deze ouder-pom niet bereikt, en zijn surefire leest die eigenschap (de parameter `skipExec`): hij compileert
zijn tests en draait ze niet ("Tests are skipped."). De ouder-pom zet in de pluginManagement van surefire `skipExec` op
`false`, zodat toolchain, service en service-quarkus hun suites draaien met skip-pin en vloeren zoals voorheen;
`-DskipTests` slaat ze nog steeds over (bin/regelspraak, de Dockerfile, `make classpath`). `uchar/` is ongewijzigd.

Waarom dit en niet een van de andere wegen:
- Eerst uchars modules bouwen, dan `verify -pl toolchain,service,service-quarkus`: Maven 3.9.16 haalt een module die
  `-pl` buiten de sessie laat niet uit haar `target/`, maar uit `~/.m2`. Gemeten met `dependency:build-classpath -pl
  toolchain`: `~/.m2/.../uchar-drools-backend-0-SNAPSHOT.jar` van 2026-10-15. Dat is een oude backend, stil.
- Eerst installeren: `~/.m2` is gedeeld met de andere worktrees onder `wt-tc/`; een sessie die tegelijk gates draait
  kan de snapshot van een andere pin eroverheen zetten. Een eigen lokale repository per checkout
  (`maven.repo.local` met `maven.repo.local.tail`) kan wel, maar vraagt vier vlaggen, een install-stap met de ouder-pom
  erbij, en valt bij een losse tweede run terug op de oude jar in de tail.
- `skipTests` per module: `-DskipTests` slaat nu ook de eigen tests over, en bin/regelspraak, de Dockerfile en
  `make classpath` rekenen daarop.
`maven.test.skip.exec` gebruikt verder niemand in deze repo. Kosten: surefire noemt `skipExec` deprecated, één
waarschuwingsregel per module (vijf), en de backend compileert zijn 259 testbronnen (±2 s).

`-T`: verdraagt het (skip-pin groen, geen waarschuwing over thread-safety), maar levert niets op. De modules vormen één
keten (backend → frontend → toolchain → service → service-quarkus); gemeten `-T 1C` 3m02s tegen 3m09s zonder, en de som
van de moduletijden is de wandkloktijd. Daarom weggelaten.

(d) `FLOORS` heeft geen vloer voor uchars modules, dus gaat er niets uit: de backend had nooit een skip-pin-stap (zijn
pom is geen kind van de ouder-pom) en `uchar-frontend` compileert alleen uchars main-bronnen. De 173 die de kaart
`uchar-frontend` toeschrijft zijn de tests van `toolchain`; per module: backend 1702, toolchain 173, service 388,
service-quarkus 53. De vloeren 120/235/43 blijven. `uchar-frontend/pom.xml` hoefde niet te veranderen.

### De parallelle groep en wat ik naging

Na pin en reactor starten twins, replay en manifest samen; `gates.sh` wacht op alle drie en meldt ze in de vaste
volgorde 3, 4, 5. Een rode stap komt met de laatste 20 regels van zijn log; is er een rood in de groep, dan draaien image
en kind-smoke niet. Image en daarna kind-smoke blijven na elkaar. Elke stap draait als achtergrondproces, en zo'n
proces negeert SIGINT. Een INT of TERM stopt daarom elke lopende stap met zijn processen (op PID, via `pgrep -P`).
Getest met nepstappen onder bash 3.2 en 5.3: groen, twee rode in de groep, no-docker, onbekend argument, onderbreking.
`make gates-no-docker` is `gates.sh no-docker` (stap 1 tot en met 5); er is geen seriële modus naast.

Wat de drie delen:
- Poort: niets. `service_replay.py` start elke dienst met `--port 0` op 127.0.0.1 en leest de poort uit zijn uitvoer.
- Mappen en bestanden: elke stap heeft zijn eigen `out/gates/<nr>-<stap>.log`; de drivers werken in
  `tempfile.TemporaryDirectory` en de scenariorunner in `Files.createTempDirectory`. De vocabulaire-driver schrijft
  geen bytecode (`dont_write_bytecode`).
- De jar: wel gedeeld. `check-twins.sh` en `check-service-replay.sh` draaiden elk een onvoorwaardelijke
  `mvn -B -o -q -DskipTests package -pl service -am`. Tegelijk zou dat twee Maven-runs in dezelfde `target/`-mappen
  laten schrijven, en `maven-shade` `service/target/regelspraak.jar` laten herschrijven terwijl diensten en het
  manifest eruit lezen. Opgelost zonder knop: `bin/regelspraak jar` geeft het pad van de jar en bouwt hem alleen als
  een bron nieuwer is, met dezelfde regel als elke andere opdracht. Beide poorten roepen dat één keer aan voordat hun
  diensten tegelijk starten. Na de reactor bouwt dus geen van de drie. Hun `command -v mvn` ging mee weg: ze roepen
  mvn zelf niet meer aan.

### Tijden en load

Load is `uptime` load1, gemiddelde (min–max) per stap uit een monster om de 15 s, op 16 cores. Vier gate-runs van
andere sessies liepen tegelijk; deze tijden zijn dus geen meting op een rustige machine. Die doet de hoofdsessie bij
de landing.

| run | commit | pin | reactor | twins | replay | manifest | image | kind-smoke | totaal | load |
|-----|--------|-----|---------|-------|--------|----------|-------|------------|--------|------|
| vóór 1 (13:22) | `51326c01` | 0s | 10m10s | 11s | 1m40s | 2s | 2s | 1m26s | 13m31s | reactor 28 (4–100), replay 65, kind 54 |
| na 1 (13:44) | `3c0d2374` | 1s | 3m13s | 8s | 1m41s | 4s | 48s | 1m20s | 7m03s | reactor 69 (35–120), groep 148 (127–166), kind 37 |
| vóór 2 (13:57) | `51326c01` | 0s | 11m13s | 10s | 1m21s | 1s | 1s | 2m12s | 14m58s | reactor 43 (13–133), twins+replay 29, kind 68 |
| na 2 (14:12) | `846e776a` | 0s | 2m14s | 3s | 1m12s | 2s | 2s | 1m19s | 4m47s | reactor 48 (24–79), groep 24 (21–27), kind 18 |

Bij "na" draaien twins, replay en manifest tegelijk: de groep duurt zo lang als de replay. In "na 1" bouwde het image
opnieuw (48s), want `pom.xml` was veranderd; in de andere runs kwam het uit de cache van docker. De reactor zakt van
10–11 minuten naar 2–3 minuten. De suite van de backend (7m21s en 8m58s) valt weg, en wat overblijft is vooral de suite
van `service` (1m52s tot 2m48s). Uptime vóór en na: `logs/load-<n>.log` (eerste en laatste regel), met alle logs onder
`/Users/yeb/Developer/uchar-dev/werk/gates-design/toolchain-gates/logs/`.

### Definitieve meting (hoofdsessie)

`make gates` in twee verse worktrees, na elkaar, gestart toen geen andere gate-set liep, met `TAG`/`CLUSTER`
`timing-before` en `timing-after` (`werk/gates-design/toolchain-gates/timing/timing.txt`):

| run | commit | pin | reactor | twins | replay | manifest | image | kind-smoke | totaal | load1 begin → eind |
|-----|--------|-----|---------|-------|--------|----------|-------|------------|--------|--------------------|
| vóór | `51326c01` | 0s | 7m56s | 8s | 1m05s | 2s | 1s | 1m20s | 10m32s | 8,6 → 9,5 |
| na | `0ae0760a` | 0s | 1m56s | 3s (tegelijk) | 1m00s (tegelijk) | 1s (tegelijk) | 2s | 1m19s | 4m17s | 7,1 → 10,8 |

Van 10m32s naar 4m17s. De reactor telt na de kaart de suites `173/388/53` van de eigen modules; de 1702 tests van
uchar's backend draaien in uchar's gate 12 op de gepinde commit.

### Rode standen

- (b) De submodule op `099e96e7` (`card/drools-suite-forks`, uit de lokale uchar-repo gehaald), `git add uchar`, `make
  check-pin`: `FAIL: de pin 099e96e76 staat niet op uchars main (origin/main van de submodule: 321fb239b). Pin een commit
  die op uchars main geland is; staat hij daar wel, dan is origin/main verouderd: git -C uchar fetch origin`, exit 2.
  Terug op `321fb239`: `ok  : de pin 321fb239b staat op uchars main`, PASS (`logs/pin-red-b.log`).
- (c) In `scripts/check-twins.sh` `jutterswet-uitsluiting-rgs|4` naar `|5` gezet, niet gecommit, `make gates`:
  `gates: 3 twins ROOD 2s exit 1` met de staart van zijn log (`check-twins: FAIL — 'jutterswet-uitsluiting-rgs' speelde
  4 cases, gepind zijn er 5`). Replay en manifest werden groen gemeld, daarna `gates: ROOD bij stap 3 (twins) na 4m53s;
  de stappen daarna zijn niet gedraaid`, exit 2. Er kwam geen `6-image.log` (`logs/gates-3-red-c.log`). De mutatie
  is teruggedraaid.

### Voorlopige besluiten

- `bin/regelspraak jar` is een nieuwe opdracht, en `bin/regelspraak`, `check-twins.sh` en `check-service-replay.sh`
  vallen buiten May touch. Afweging: de jar moest gedeeld blijven zonder knop, en de regel "bouw als een bron nieuwer
  is" staat alleen in `bin/regelspraak`. Een kopie van de bronnenlijst in twee poortscripts kan uit de pas lopen; een
  slot in `bin/regelspraak` is meer code. Wat de poorten controleren is niet veranderd.
- `gates.sh` neemt het argument `no-docker` voor `make gates-no-docker`; de gebruiker kiest tussen de twee make-doelen
  en nooit tussen een seriële en een parallelle modus.
- De kop van `scripts/gates.sh` bleef Nederlands, zoals de commentaren van alle scripts; de docs zijn Engels.

### Wat openblijft

- De suite van `service` (388 tests, `forkCount 1` met `reuseForks false`, dus een nieuwe JVM per testklasse, één
  tegelijk) is nu het grootste deel van de reactor. Forks parallel draaien is een eigen kaart (`service/pom.xml`).
- `kind-smoke.sh` bouwt het image zelf nog eens (`make image`, uit de cache). De H4-images `<TAG>-h4a` en `<TAG>-h4b`
  ruimt de teardown sinds `0ae0760a` (hoofdsessie) zelf op; een mislukte run laat ze staan om te debuggen.
- De vijf waarschuwingen over het deprecated `skipExec`. Surefire 3.5.2 heeft de parameter nog; verdwijnt hij in een
  latere surefire, dan draait de backend-suite weer mee, wat traag maar niet fout is.
