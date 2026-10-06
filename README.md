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
| `service/` | the decision service on the JDK `HttpServer` (`BeslisserKern`, `DienstMain`, OpenAPI emitter, dev-only playground); its build also bundles the distribution into the one jar `bin/regelspraak` runs from |
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

`make gates` is the check before every landing. It runs the whole sequence on this machine, in this order, and stops
at the first red step, with one summary line per step and a closing line; the full output of each step lands in
`out/gates/`:

```
scripts/check-pin.sh      # the submodule is initialised, clean, on the recorded commit; the versions are uchar's
mvn -B -o verify          # the reactor; each own module holds its test count to a floor (tools/check-skips.sh)
make check-tweelingen     # the three dialect twins and the vocabulary twin, over HTTP, with their case expectations
make check-dienst-replay  # every golden uchar's gate 12 plays, replayed through the service
make scenario-test        # the golden TOKA scenario manifest
make image                # the service image ("The container")
make kind-smoke           # that image in kind: the golden scenarios over HTTP, the marker, a rollout
```

It needs, beside the checked-out submodule and the Maven repository of a first build: Java 21, Maven, python3,
Docker, kind, kubectl and helm. The first five steps are `make poorten`, which needs no Docker: green there means
the distribution builds and answers as uchar does. No hosted CI runs the gates; `TAG=`, `DESCRIPTOR_SRC=` and
`CLUSTER=` pass on to the image and the kind smoke.

The two service gates start the JDK service themselves (`bin/regelspraak serve` on the jar the reactor builds, on a
free port of 127.0.0.1, removed when done) and turn every uchar case file into a request; the answer must give each
pinned expectation, compared as uchar's replay compares it (`scripts/dienst_replay.py`). They need `java`, `mvn` and
`python3`.

- `check-tweelingen` serves `ww-herleving-inkomen-rgs`, `jutterswet-uitsluiting-rgs` and
  `jutterswet-nestkenmerk-rgs` from their `bron.rgs` with a descriptor as a user writes one
  (`scripts/tweelingen/`): named entry points whose scope names the rules and the defaults group. The case count of
  each twin is pinned (10 + 4 + 4). It then serves the vocabulary twin `jutterswet-vocabulaires`
  (`scripts/vocabulaire_replay.py`): each case in the canonical spelling, in that of each vocabulary and mixed, each
  answer asked canonically and in each vocabulary, beside the pinned input vocabulary, an entry point's default
  vocabulary and `/info`, with the golden's `ir.json` as the table of names (2 cases pinned, at least 34 requests);
  and an own witness (`scripts/tweelingen/vocabulaire-dubbelzinnig.*`): a name that two vocabularies give to two
  declarations is a 400 naming both readings, and pinning the input vocabulary answers the same request (at least 4
  testimonies).
- `check-dienst-replay` takes its set from uchar's gate 12 (`uchar/scripts/check-drools.sh`: every golden with a
  case file outside `EXPECTED_CHANNEL`) and checks its size against uchar's `EXPECTED_PLAYED` (235). Each golden is
  served from its `bron.rgs`, or without one from its `ir.json`, with one entry point that carries every form. The
  number that plays is pinned (`EXPECTED_REPLAY_PLAYED`, 208); every golden that does not play is listed by name with
  its reason (27, each a timeline form the request cannot express); a deviation always fails, and the list fails when
  it goes stale in either direction. About 50 seconds with four services at a time (`REPLAY_WERKERS`).
- `scenario-test` runs the 32 lines of `examples/toka-scenarios/manifest`; the three golden TOKA scenarios uchar's
  scenario resolver refuses are listed in `scripts/toka-manifest-uitsluitingen.txt` with a fragment of that refusal,
  and a listed scenario uchar accepts fails the run.

## Everyday use: `bin/regelspraak`

`bin/regelspraak <opdracht>` is the one entry for a user, a script or another repository: it takes no class name
and no build path. Several sources are read as one model, concatenated in the order given, and paths are relative
to the directory the command is given in.

| Command | What it does |
|---------|--------------|
| `check <bron.rgs...>` | parse and resolve: the model summary and the diagnostics (in a chain of files as `file:line`), exit 1 on an error; then the build on uchar's exec-model branch, exit 3 when uchar does not carry the model |
| `ir <uit.json> <bron.rgs...>` | the `uchar-ir-v1` document of the model (uchar's emitter) |
| `cases <map> <bron.rgs...>` | the scenarios of a test document lowered to uchar case files in `<map>`, beside the IR document they run against (`<map>/ir.json`): the layout of uchar's corpus |
| `scenario [--trace] [--uitleg] [--vocabulaire <naam>] <bron.rgs...>` | lowering, uchar's replay and the report: PASS, FAIL or MARKER per scenario; exit 1 on a FAIL, 3 on a MARKER; `--vocabulaire` names the rules of the trace and the declarations of the uitleg in a vocabulary of the model |
| `scenario --manifest <manifest> [--uitsluitingen <lijst>]` | every line `<verwachte-exit> <scenario.rgs> <companion.rgs...>` of a manifest, in one JVM; exit 0 when no line mismatches |
| `serve <descriptor.dienst.json> <bron.rgs...> \| <document.json> [--allow-markers] [--host H] [--poort N]` | the decision service on the JDK `HttpServer` (`docs/ENGINE.md`); `PLAYGROUND=true` binds the workbench routes |
| `drl <bron.rgs...>` | the DRL text of uchar's DRL-text branch, or exit 3 with uchar's refusal |
| `graaf [--mmd] <uit> <bron.rgs...>` | uchar's rule graph as an HTML page, or as Mermaid text |
| `facts <uitmap> <bron.rgs...>` | the fact beans uchar's generator writes for the model |

Exit 2 is a wrong invocation. Every command runs from one jar the reactor builds. In a checkout `bin/regelspraak`
first rebuilds that jar when it is missing or older than one of its sources (the poms, the own main sources, uchar's
frontend and backend), with Maven offline and without tests, and says so on stderr; the first build on a machine
needs the network once (see Building). `JAVA_OPTS` reach the JVM; `REGELSPRAAK_JAR` names a prebuilt jar, which is
never rebuilt (the container sets it).

The make targets of the same name call `bin/regelspraak` and nothing else, with `FILE` the sources:

```
make check FILE="model.rgs regels.rgs"
make ir FILE="model.rgs regels.rgs"             # out/ir.json (OUT= overrides)
make cases FILE="model.rgs scenario.rgs"        # out/cases/ (OUT= overrides)
make scenario FILE="--uitleg model.rgs scenario.rgs"
make serve FILE="d.dienst.json model.rgs regels.rgs" POORT=8080
make drl FILE=model.rgs
make graaf FILE="model.rgs regels.rgs"          # out/regelgraaf.html (MMD=1 for Mermaid, OUT= overrides)
make facts                                      # the TOKA model foundation (FILE= another model), javac'd
make scenario-test                              # the golden TOKA scenario set
```

A scenario document runs through uchar's scenario layer: uchar resolves it, lowers the scenarios to case files,
and uchar's replay reports each one. With `--uitleg` (`FILE="--uitleg a.rgs"`) each played scenario also gets the
explanation of its outcome: it is played once more, in a session of its own on the same build, and the verbaliser
explains that session's facts; the explanation is withheld, with the difference, when that session did not fire
what uchar's replay reported. uchar's scenario resolver refuses three of the 29 golden TOKA scenarios (listed with
their reasons in `scripts/toka-manifest-uitsluitingen.txt`).

The decision service serves a descriptor over `.rgs` sources or over one `uchar-ir-v1` document; `docs/ENGINE.md`
describes its routes, its MARKER answer and the dev-only playground. A scope that names a base rule also carries the
groups uchar makes of the own exceptions of its subtypes (`<basis> voor <subtype>`), and a scope that names such a group
carries its base. It checks every scope at the start: a rule or a defaults group in the scope that reads an attribute or
characteristic only forms outside the scope write refuses the start, naming the reader (for a group the default that
reads, by its text and path), unless the entry point declares that element in `invoerElementen` or sets
`scopeControleUit`.

## Vocabularies

uchar lets a model declare named vocabularies and give each declaration a designation in them (`Vocabulaire
handboek`, `(handboek: totale premie)`; uchar's `spec/IR-SPEC.md` ch. 2.12). The designations carry no semantics:
rules are written and computed in the canonical names. The distribution uses them at the edge, as one service:

- **Input is liberal.** A request may write every name — object type, attribute, characteristic, fact type, role,
  parameter, enumeration value — canonically or as its designation in any vocabulary, mixed within one request,
  case-insensitively, within the scope of the name (the members of an object type with the inherited ones, the roles
  of a fact type, the values of a domain, the rest model-wide). A name that reads as two different declarations is a
  400 `DUBBELZINNIGE_NAAM` whose sentence names both readings; the query parameter `invoervocabulaire=<naam>` pins
  the vocabulary the names are read in (`canoniek` for the canonical names), which resolves it.
- **Output is chosen.** `vocabulaire=<naam>` names the declarations of the answer, its inconsistencies and its
  uitleg in that vocabulary; without it the entry point's `vocabulaire` from the descriptor applies, else the
  canonical names. An unknown vocabulary is a 400 `ONBEKEND_VOCABULAIRE` naming the declared ones.
- **Complete or not.** A declaration without a designation in the chosen vocabulary keeps its canonical name. uchar
  requires a designation of every declaration only in the default vocabulary; `/info` lists per vocabulary whether it
  is complete and which declarations have none.
- **One OpenAPI document per vocabulary.** `OpenApiEmitter.emit(descriptor, model, vocabulaire)` writes the contract
  in the names of one vocabulary (or canonically); each property, value and entry point lists its other spellings in
  `x-spellingen` / `x-waardespellingen`, which the service accepts as well. A model without vocabularies gets the
  document it always had.
- **A designation is part of the contract.** Changing a designation changes the names a consumer of that vocabulary
  sends and receives; treat it as a contract change of the service.

The word `canoniek` chooses the canonical names, so a model that declares a vocabulary of that name does not start.
The rule text is never renamed: the uitleg quotes it as written.

## The container

`make image` builds the decision-service image (`beslisservice:dev`; `TAG=` and `DESCRIPTOR_SRC=` override, the
latter a descriptor under `docker/` or `examples/`) from `docker/Dockerfile`, with the repository root as build
context and the submodule checked out. `.dockerignore`
admits only the reactor (the root pom, the own modules and, of `uchar/`, the RegelSpraak frontend and the Drools
backend), `bin/regelspraak`, `docker/` and `examples/`, and never a `.git`: neither the repository's directory nor
the file the submodule carries. The build stage runs one `mvn -B -ntp -DskipTests package -pl service -am` on the
root pom and fetches the plugins and dependencies inside the container; the runtime stage is a JDK (uchar compiles the
fact beans and the executable model in memory) with the jar and `bin/regelspraak`. The container starts
`bin/regelspraak serve`, configured by the environment:

| Variable | Meaning |
|----------|---------|
| `DESCRIPTOR` | the `.dienst.json` (default: the baked TOKA descriptor) |
| `BRONNEN_MANIFEST` | a file with the sources, one absolute path per line, in model order (default: the baked TOKA set) |
| `POORT` | the port, 8080 |
| `ALLOW_MARKERS` | `true` starts with entry points uchar does not carry, each request to one gets 503; otherwise such an entry point stops the start with exit 3 |

Mounting a rule set at `/app/rules` and setting `DESCRIPTOR` and `BRONNEN_MANIFEST` serves it from the same image.
`helm/beslisservice` deploys the image. `make kind-smoke` builds it, loads it into the kind cluster `regelspraak`
(created when absent; `CLUSTER=` names another, and an existing cluster that does not answer stops the run) and
checks the running service: the golden TOKA scenarios over HTTP, the marker's exit 3 and its 503 with
`allowMarkers`, and a rollout that changes a parameter and with it the decision. It then deletes the cluster;
`bash scripts/kind-smoke.sh` without `--teardown` keeps it, and so does a failed run, which names the failed step
and the command that deletes the cluster. Both targets need Docker and run on the host.

## Known losses

- **A parameter-set file on the scenario route** (`--parametersets`, refused by name). A scenario gets its parameter
  set from the scenario document itself: a `Parameterset <naam>:` block, chosen with `met parameterset <naam>`
  (uchar's scenario layer, since the pin on `c70dcad6`). The former runner's JSON file beside the scenario has no
  counterpart.
- **Timelines in a service request.** The request has no timeline value, no characteristic with periods, and no
  scalar on a time-dependent attribute (uchar's replay places one in the bucket of the evaluation date); the service
  refuses them as `NIET_ONDERSTEUND`. 27 of the goldens gate 12 plays need one; the replay gate lists them.
- **A `uchar-ir-v1` document is served whole**: a scope that drops forms needs the model the document came from.
- **The uitleg** explains characteristic-attribution rules, not defaults groups: a characteristic a defaults group
  decides gets no sentence.
- **The playground trace** names per fired rule the labels it bound and how often, but no value: uchar's report
  carries none.
- **Vocabulary names in the Mermaid graph.** The playground's `graaf` and `bin/regelspraak graaf` are uchar's
  rendering and stay canonical; the playground's `graafJson` carries the chosen vocabulary.

## The parity zone

`examples/toka*` and `specification/` hold material that is © Belastingdienst: the TOKA casus the tests and the
golden scenarios run on, and derived notes on the RegelSpraak specification. It stays as it is and is never
quoted elsewhere, and the repository stays private while the zone exists.

## Working here

Work enters as a card in `backlog/` (`new/` → `doing/` → `done/`, the result recorded in the card). A question
about what RegelSpraak means — a construct, a verdict, a refusal — is a card in uchar, not a change here. The
naming convention: infrastructure in English, the domain vocabulary in Dutch (`BeslisserKern`, `Verbalisator`).
