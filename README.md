# RegelSpraak toolchain

A distribution of RegelSpraak tooling on top of **uchar**: a CLI, an embeddable engine, a scenario runner and a
decision service (JDK `HttpServer` and Quarkus), with Docker and Helm packaging. It has no semantics of its own.
The grammar, the resolver, the IR emitter, the loader, the Drools transpilers, the runtime, the fact-model
generator, the rule graph and the scenario layer are uchar's, and this repository builds them from uchar's source
tree, which it carries as the git submodule `uchar/`.

This repository succeeds `yhavinga/regelspraak-java`, which is deprecated; its history is in this repository.

## Getting it

```
git clone --recurse-submodules git@github.com:uchar-dev/regelspraak-toolchain.git
# or, in an existing clone:
git submodule update --init --recursive
```

The submodule URL is relative (`../uchar.git`), so it resolves to `uchar-dev/uchar` next to this repository. Both
repositories are private: a clone needs read access to both.

## Building

Java 21 or later and Maven. The root `pom.xml` is the reactor:

```
make check-pin        # the submodule is checked out, clean, and the versions match uchar's
mvn -B -o verify      # the whole reactor, offline (make verify runs both)
```

`-o` builds from the local Maven repository alone. The first build on a machine needs the network once
(`mvn -B -DskipTests package`) to fill that repository with the plugins and dependencies, the antlr4-maven-plugin
and the ANTLR tool among them; after that every build runs offline.

| Module | What it is |
|--------|------------|
| `uchar/backends/drools` | uchar's Drools backend, built with uchar's own pom; its test suite runs with every build |
| `uchar-frontend/` | uchar's RegelSpraak frontend compiled from `uchar/frontends/regelspraak`: the parser generated from uchar's grammar, the resolver and the emitters |
| `toolchain/` | the CLI (`RegelSpraakCli`), the engine facade (`RegelSpraakEngine`), the scenario runner and its CLI, the verbaliser and the parameterset loader |
| `service/` | the decision service on the JDK `HttpServer` (`BeslisserKern`, `DienstMain`, OpenAPI emitter, dev-only playground) |
| `service-quarkus/` | the Quarkus adapter over the same service core |

Own code lives in `dev.uchar.regelspraak.toolchain.*` and uses only uchar's public API; every KieBase is built by
`DroolsBackend.build(document, branch)` over the document uchar's emitter writes. The engine and the service are
described in `docs/ENGINE.md`.

## The pin

The commit the submodule is checked out at is the pin: there is no other version to keep in step. The parent
pom pins Jackson, the ANTLR runtime, Drools and JUnit on uchar's versions, and `scripts/check-pin.sh` fails when
they drift, when the submodule is not initialised or not on the recorded commit, or when it has local changes. To
move the pin:

```
git -C uchar fetch && git -C uchar checkout <commit on uchar's main>
make poorten
git add uchar
```

A pin that changes uchar's corpus can move a count the gates pin (the replay's `EXPECTED_REPLAY_PLAYED`, the case
counts of the twins); such a count is set deliberately, with its reason in the script.

## The gates

Green means `make poorten` passes. It runs, in this order:

```
make check-pin            # the submodule is initialised, clean, on the recorded commit; the versions are uchar's
mvn -B -o verify          # the reactor; each own module holds its test count to a floor (tools/check-skips.sh)
make check-tweelingen     # the three dialect twins, over HTTP, with the expectations of all 18 of their cases
make check-dienst-replay  # every golden uchar's gate 12 plays, replayed through the service
make scenario-test        # the golden TOKA scenario manifest
```

The two service gates start the JDK service themselves (`DienstMain` on the classpath the reactor builds, on a free
port of 127.0.0.1, removed when done) and turn every uchar case file into a request; the answer must give each
pinned expectation, compared as uchar's replay compares it (`scripts/dienst_replay.py`). They need `java`, `mvn` and
`python3`.

- `check-tweelingen` serves `ww-herleving-inkomen-rgs`, `jutterswet-uitsluiting-rgs` and
  `jutterswet-nestkenmerk-rgs` from their `bron.rgs` with a descriptor as a user writes one
  (`scripts/tweelingen/`): named entry points whose scope names the rules and the defaults group. The case count of
  each twin is pinned (10 + 4 + 4).
- `check-dienst-replay` takes its set from uchar's gate 12 (`uchar/scripts/check-drools.sh`: every golden with a
  case file outside `EXPECTED_CHANNEL`) and checks its size against uchar's `EXPECTED_PLAYED` (215). Each golden is
  served from its `bron.rgs`, or without one from its `ir.json`, with one entry point that carries every form. The
  number that plays is pinned (`EXPECTED_REPLAY_PLAYED`, 188); every golden that does not play is listed by name with
  its reason (27, each a timeline form the request cannot express); a deviation always fails, and the list fails when
  it goes stale in either direction. About 50 seconds with four services at a time (`REPLAY_WERKERS`).
- `scenario-test` runs the 32 lines of `examples/toka-scenarios/manifest`; the three golden TOKA scenarios uchar's
  scenario resolver refuses are listed in `scripts/toka-manifest-uitsluitingen.txt` with a fragment of that refusal,
  and a listed scenario uchar accepts fails the run.

## Everyday use

```
make drl FILE=model.rgs                       # the DRL text of uchar's DRL-text branch
make ir FILE="model.rgs regels.rgs"           # the uchar-ir-v1 document
make graaf FILE="model.rgs regels.rgs"        # the rule graph as HTML (MMD=1 for Mermaid)
make facts                                    # the fact beans of the TOKA model foundation
make scenario-test FILES="model.rgs scenario.rgs"   # PASS / FAIL / MARKER per scenario
make scenario-test                            # the golden TOKA scenario set
```

A scenario document runs through uchar's scenario layer: uchar resolves it, lowers the scenarios to case files,
and uchar's replay reports each one. With `--uitleg` (`FILES="--uitleg a.rgs"`) each played scenario also gets the
explanation of its outcome: it is played once more, in a session of its own on the same build, and the verbaliser
explains that session's facts; the explanation is withheld, with the difference, when that session did not fire
what uchar's replay reported. uchar's scenario resolver refuses three of the 29 golden TOKA scenarios (listed with
their reasons in `scripts/toka-manifest-uitsluitingen.txt`).

The decision service serves a descriptor over `.rgs` sources or over one `uchar-ir-v1` document; `docs/ENGINE.md`
describes its routes, its MARKER answer and the dev-only playground.

## Known losses

- **Parameter sets on the scenario route** (`--parametersets`, refused by name). uchar's scenario resolver refuses a
  scenario whose carried rules read a parameter its situation does not set, so no case file is written for a
  parameter set to go into. Every parameter a tested rule reads needs a value in the scenario's situation.
- **Timelines in a service request.** The request has no timeline value, no characteristic with periods, and no
  scalar on a time-dependent attribute (uchar's replay places one in the bucket of the evaluation date); the service
  refuses them as `NIET_ONDERSTEUND`. 27 of the goldens gate 12 plays need one; the replay gate lists them.
- **A `uchar-ir-v1` document is served whole**: a scope that drops forms needs the model the document came from.
- **The uitleg** explains characteristic-attribution rules, not defaults groups: a characteristic a defaults group
  decides gets no sentence.
- **The playground trace** names per fired rule the labels it bound and how often, but no value: uchar's report
  carries none.

## The parity zone

`examples/toka*` and `specification/` hold material that is © Belastingdienst: the TOKA casus the tests and the
golden scenarios run on, and derived notes on the RegelSpraak specification. It stays as it is and is never
quoted elsewhere, and the repository stays private while the zone exists.

## Working here

Work enters as a card in `backlog/` (`new/` → `doing/` → `done/`, the result recorded in the card). A question
about what RegelSpraak means — a construct, a verdict, a refusal — is a card in uchar, not a change here. The
naming convention: infrastructure in English, the domain vocabulary in Dutch (`BeslisserKern`, `Verbalisator`).
