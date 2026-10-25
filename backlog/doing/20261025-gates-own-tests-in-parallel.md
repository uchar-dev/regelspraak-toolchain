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
