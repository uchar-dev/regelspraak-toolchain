# CLAUDE.md — regelspraak-toolchain

## What this repository is

A distribution of RegelSpraak tooling — CLI, engine facade, scenario runner, decision service (JDK and Quarkus),
Docker and Helm — built on **uchar**, which it carries as the git submodule `uchar/`. It succeeds the deprecated
`yhavinga/regelspraak-java`. It has **no semantics of its own**: parser, resolver, emitter, loader, transpilers,
runtime, fact-model generator, rule graph and scenario layer are uchar's. A question about what RegelSpraak means
(a construct, a verdict, a refusal, a missing form) is a card in uchar, never a change here. `README.md` is the
map; `docs/ENGINE.md` describes the engine and the service.

## Rules

- **Never edit `uchar/`.** A change to uchar is made in uchar, lands there, and arrives here as a pin bump: the
  submodule moved to a newer commit of uchar's main, `git add uchar`.
- **Own code in `dev.uchar.regelspraak.toolchain.*` only**, reading uchar's public API. Never a class in a uchar
  package, and never one in uchar's generated namespaces (`dev.uchar.regelspraak.facts`, `…generated`).
- **One way to a KieBase:** `DroolsBackend.build(document, branch)` over a `uchar-ir-v1` document — the one
  uchar's emitter writes, or one the service is given as its source — always through `UcharBuild`. The transpilers
  take the loader's side tables beside the model, and only that build passes all of them; a transpiler call of our
  own could drop one and still produce a KieBase.
- **Fail fast.** A refusal of uchar is reported with uchar's sentence; nothing is skipped, caught and dropped, or
  answered as if a construct did not exist.
- **The TOKA fragments stay as they are.** `examples/toka/*.rgs` are the 47 definition fragments copied verbatim
  from the TOKA casus, byte-identical to uchar's `toka/casus/*.rgs`; they are never edited, so that the two stay in
  parity. The other files under `examples/toka*` are ordinary project files. Source, rights and status of the casus
  are one text, kept the same in `NOTICE`, `README.md` and `examples/toka/README.md`. `specification/v2.3.0/` holds
  the project's own notes derived from the RegelSpraak specification; the specification itself is not in this
  repository (`NOTICE`).

## Build and gates

```
git submodule update --init --recursive
make check-pin        # scripts/check-pin.sh: submodule initialised, clean, on the recorded commit; versions = uchar's
mvn -B -o verify      # the reactor: uchar's backend suite, then toolchain, service, service-quarkus
```

The reactor needs no `mvn install`. The first build on a machine needs the network once
(`mvn -B -DskipTests package`), for the plugins and dependencies the local Maven repository lacks, the
antlr4-maven-plugin among them; after that, build offline.

Green means `make gates` passes (`make gates-no-docker` is its first five steps): check-pin, `mvn -B -o verify`, the two service gates and the golden TOKA
manifest (README, "The gates"). Each own module ends its tests with the skip-pin step (`tools/check-skips.sh`):
every skipped or aborted test is printed by name and fails the build unless it is pinned with a reason, a test
class that produced no test case fails, and each module is held to a floor (`FLOORS` in the script). Floors are
lower bounds: growth needs no bump; a deliberate removal lowers the floor in the same commit.

The service gates (`scripts/check-twins.sh`, `scripts/check-service-replay.sh`) start the JDK service
themselves and replay uchar's case files over HTTP; the replay takes its set from uchar's gate 12 and pins the
number that plays (an equality) beside a reason-carrying list of the goldens that do not, which fails when stale.
`make scenario-test` runs the golden TOKA manifest; the scenarios uchar's scenario resolver refuses are listed with
a fragment of the refusal in `scripts/toka-manifest-exclusions.txt`.

Further checks, run when their area changes: `bash tools/testdata/run_tests.sh` after `make classpath` (the CSV →
Beslistabel converter). `make image` and `make kind-smoke` need Docker and run on the host only.

## Working

- Work enters as a card in `backlog/`: `new/` → `doing/` → `done/`, with the result recorded in the card.
- Commit messages in Dutch: short, the why, wrapped at 72 columns.
- Every identifier is English, as in the Conventions of uchar (`uchar/CLAUDE.md`): classes, methods, fields, variables, shell
  variables and the names of files and directories. The RegelSpraak language and the domain content (declared names in test
  documents, the `jutterswet-*` names) stay Dutch, and so do the parser rules of the grammar. What is visible outside the code is
  English too: JSON keys, OpenAPI, subcommands and flags, Make targets, environment variables, Helm and Docker configuration. What
  comes from RegelSpraak stays (the kinds of declarations `ATTRIBUUT`, `FEITTYPE`, `ROL`, the predicates, the global `rekendatum`, names
  a model declares). The messages of uchar come from its catalogues (codes `RGS-###`, `DROOLS-###`; Dutch by default, `--language`
  or `Accept-Language` chooses), and the toolchain's own messages keep their Dutch sentences. A message or OpenAPI description
  that names a JSON key, query parameter, field or Helm value names the English one the code reads;
  `ContractNamesInMessagesTest` refuses the old names in the literals.
