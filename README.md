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
make check-pin && mvn -B -o verify
git add uchar
```

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
and uchar's replay reports each one. Current limits of that route: every parameter a tested rule reads needs a
value in the scenario's situation (no parameter sets), the uitleg is available on the service
(`POST /<pad>?uitleg=true`) but not per scenario, and uchar's scenario resolver refuses three of the 29 golden TOKA
scenarios (listed with their reasons in `GoldenOverHttpTest`).

## The parity zone

`examples/toka*` and `specification/` hold material that is © Belastingdienst: the TOKA casus the tests and the
golden scenarios run on, and derived notes on the RegelSpraak specification. It stays as it is and is never
quoted elsewhere, and the repository stays private while the zone exists.

## Working here

Work enters as a card in `backlog/` (`new/` → `doing/` → `done/`, the result recorded in the card). A question
about what RegelSpraak means — a construct, a verdict, a refusal — is a card in uchar, not a change here. The
naming convention: infrastructure in English, the domain vocabulary in Dutch (`BeslisserKern`, `Verbalisator`).
