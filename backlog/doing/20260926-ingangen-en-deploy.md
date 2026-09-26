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
