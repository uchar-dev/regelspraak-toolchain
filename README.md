# RegelSpraak toolchain

A distribution of RegelSpraak tooling on top of **uchar**: a CLI, an embeddable engine, a scenario runner and a
decision service (JDK `HttpServer` and Quarkus), with Docker and Helm packaging. It has no semantics of its own.
The grammar, the resolver, the IR emitter, the loader, the Drools transpilers, the runtime, the fact-model
generator, the rule graph and the scenario layer are uchar's, and this repository builds them from uchar's source
tree, which it carries as the git submodule `uchar/`.

This repository succeeds `yhavinga/regelspraak-java`, which is deprecated; its history is in this repository.

## Getting it

```
git clone --recurse-submodules https://github.com/uchar-dev/regelspraak-toolchain.git
# or, in an existing clone:
git submodule update --init --recursive
```

The submodule URL is relative (`../uchar.git`), so it resolves to `uchar-dev/uchar` next to this repository. Both
repositories are public.

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
| `service/` | the decision service on the JDK `HttpServer` (`DecisionCore`, `ServiceMain`, OpenAPI emitter, dev-only playground); its build also bundles the distribution into the one jar `bin/regelspraak` runs from |
| `service-quarkus/` | the Quarkus adapter over the same service core |

Own code lives in `dev.uchar.regelspraak.toolchain.*` and uses only uchar's public API; every KieBase is built by
`DroolsBackend.build(document, branch)` over the document uchar's emitter writes. The engine and the service are
described in `docs/ENGINE.md`.

## Contract change: English names

Since the card `engelse-namen` (2026-10-18) everything a caller sees is English, except what comes from RegelSpraak
(the kinds of declarations `ATTRIBUUT`, `FEITTYPE`, `ROL` ..., the predicates, the global `rekendatum`, the names a
model declares) and the sentences of the messages, which keep their text. The old names no longer work:

- **JSON** of the requests, the answers, `/info`, the playground and the OpenAPI document: `instanties`, `attributen`,
  `kenmerken`, `rollen`, `relaties`, `feittype`, `peildatum` are `instances`, `attributes`, `characteristics`, `roles`,
  `relations`, `factType`, `asOfDate`; `fout`, `fouten`, `veld`, `soort`, `schendingen`, `reden` are `error`, `faults`,
  `field`, `kind`, `violations`, `reason`; `dekking`, `fixpunt`, `uitleg`, `inconsistenties`, `onderwerp`, `waarde`,
  `eenheid`, `tijdlijn` are `coverage`, `fixpoint`, `explanation`, `inconsistencies`, `subject`, `value`, `unit`,
  `timeline`; the descriptor's `dienst`, `naam`, `pad`, `invoer`, `uitvoer`, `regelnamen`, `regelgroepen`,
  `vocabulaire`, `parametersets` are `service`, `name`, `path`, `input`, `output`, `ruleNames`, `ruleGroups`,
  `vocabulary`, `parameterSets` (the file is `<name>.service.json`); the schemas `Verzoek`, `Antwoord`, `Fout`,
  `Evaluatiefout`, `GeenFixpunt`, `Dekking`, `Fixpunt` are `Request`, `Answer`, `Error`, `EvaluationFault`,
  `NoFixpoint`, `Coverage`, `Fixpoint`; the OpenAPI extensions `x-spellingen` and `x-waardespellingen` are
  `x-spellings` and `x-valueSpellings`.
- **Parameter-set sidecar** (`<name>.parameters.json`, named by the descriptor's `parameterSets`, read by
  `ParameterSetLoader`): `parametersets`, `naam`, `waarden`, `waarde`, `vanaf`, `tot`, `eenheid` are `parameterSets`,
  `name`, `values`, `value`, `from`, `to`, `unit`. A sidecar in the old shape is refused with the message that
  `parameterSets` is missing, and `scripts/as-of-date-vintage-check.sh` fails on it instead of reporting a single-value
  deploy.
- **Values**: the statuses `EVALUATIEFOUT` and `GEEN_FIXPUNT` are `EVALUATION_FAULT` and `NO_FIXPOINT`; the violation
  kinds `ONBEKEND_VELD`, `VERKEERD_TYPE`, `NIET_ONDERSTEUND` ... are `UNKNOWN_FIELD`, `WRONG_TYPE`, `UNSUPPORTED` ...
  (`Violation.Kind`); the fault kinds `DELING_DOOR_NUL`, `REKENFOUT`, `SCHRIJVERSCONFLICT`, `INVOERVORMFOUT`, `ANDERE`
  are `DIVISION_BY_ZERO`, `COMPUTE_FAULT`, `WRITERS_CONFLICT`, `INPUT_FORM_FAULT`, `OTHER`; the coverage kinds `GEVUURD`
  and `NIET_GERAAKT` are `FIRED` and `NOT_FIRED`; the origins `afgeleid`, `invoer`, `creatie` are `derived`, `input`,
  `creation`; the pseudo vocabulary `canoniek` is `canonical`.
- **Query parameters**: `uitleg`, `vocabulaire`, `invoervocabulaire`, `dekking`, `fixpunt` are `explanation`,
  `vocabulary`, `inputVocabulary`, `coverage`, `fixpoint`; `?via=dienst` is `?via=service`.
- **Environment and Helm**: `POORT`, `BRONNEN_MANIFEST`, `REPLAY_WERKERS` are `PORT`, `SOURCES_MANIFEST`,
  `REPLAY_WORKERS`; the Helm values `poort`, `bronnenManifest`, `parametersets` are `port`, `sourcesManifest`,
  `parameterSets`; the chart and the image `beslisservice` are `decision-service`.
- **CLI and make**: the flags `--poort`, `--uitleg`, `--vocabulaire`, `--uitsluitingen`, `--parametersets`, `--getuige`
  are `--port`, `--explanation`, `--vocabulary`, `--exclusions`, `--parameter-sets`, `--witness`; the command and target
  `graaf` is `graph`; the targets `poorten`, `check-tweelingen`, `check-dienst-replay` are `gates-no-docker`,
  `check-twins`, `check-service-replay`.
- **Division by zero** (uchar's card `zero-division-channel`): a filled dividend divided by a numeric
  zero is no evaluation fault any more. uchar's entry gives an empty value and a §9.5 fact of kind EF-005, so the
  service answers 200 with the attribute `null` and the rule among the `inconsistencies` (and the playground shows
  the scenario as an inconsistency), where it answered 422 `EVALUATION_FAULT` with the kind `DIVISION_BY_ZERO`. That
  kind is left for what uchar's runtime itself still raises; the code EF-005 is not in the JSON of the answer.
- **Explanation parts** (card `explanation-parts`): `?explanationParts=true` asks the explanation and adds
  `explanationParts` beside `explanation`, per sentence its parts with their kind (`docs/ENGINE.md`, "The parts of an
  explanation"); the playground gives it per scenario on both routes. `explanation` is unchanged, and without the flag
  the answer is byte for byte what it was.
- **The ground names its decider** (card `explanation-ground-source`): a `GROUND` part carries the decider whose
  `statuteAnnotations` it renders, in the terms of the IR: `rule` (the name of the rule's form), or for a default
  `group`, `pointer` (the default within its group, RFC 6901), `position` (1-based) and `variant` (its name, where it
  has one). `explanation` and the text of the parts are unchanged.
- **Computed values are explained** (card `explanation-computed-attributes`): every attribute an ordinary rule writes
  gets one sentence per object, after the sentences of the defaults groups, with the rule that held, the value, how it
  was computed (the values the computation read, the members of a fold, the value before a rounding or a bound) and why
  the other writers did not write; the parts have two new groups, `COMPUTATION` and `VARIANT` (`docs/ENGINE.md`, "The
  parts of an explanation"). The sentences that were there are unchanged and keep their order.

## The pin

The commit the submodule is checked out at is the pin: there is no other version to keep in step. The parent
pom pins Jackson, the ANTLR runtime, Drools and JUnit on uchar's versions, and `scripts/check-pin.sh` fails when
they drift, when the submodule is not initialised or not on the recorded commit, or when it has local changes. To
move the pin:

```
git -C uchar fetch && git -C uchar checkout <commit on uchar's main>
make gates-no-docker
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
make check-twins     # the three dialect twins and the vocabulary twin, over HTTP, with their case expectations
make check-service-replay  # every golden uchar's gate 12 plays, replayed through the service
make scenario-test        # the golden TOKA scenario manifest
make image                # the service image ("The container")
make kind-smoke           # that image in kind: the golden scenarios over HTTP, the marker, a rollout
```

It needs, beside the checked-out submodule and the Maven repository of a first build: Java 21, Maven, python3,
Docker, kind, kubectl and helm. The first five steps are `make gates-no-docker`, which needs no Docker: green there means
the distribution builds and answers as uchar does. No hosted CI runs the gates; `TAG=`, `DESCRIPTOR_SRC=` and
`CLUSTER=` pass on to the image and the kind smoke.

The two service gates start the JDK service themselves (`bin/regelspraak serve` on the jar the reactor builds, on a
free port of 127.0.0.1, removed when done) and turn every uchar case file into a request; the answer must give each
pinned expectation, compared as uchar's replay compares it (`scripts/service_replay.py`). They need `java`, `mvn` and
`python3`.

- `check-twins` serves `ww-herleving-inkomen-rgs`, `jutterswet-uitsluiting-rgs` and
  `jutterswet-nestkenmerk-rgs` from their `source.rgs` with a descriptor as a user writes one
  (`scripts/twins/`): named entry points whose scope names the rules and the defaults group. The case count of
  each twin is pinned (10 + 4 + 4). It then serves the vocabulary twin `jutterswet-vocabulaires`
  (`scripts/vocabulary_replay.py`): each case in the canonical spelling, in that of each vocabulary and mixed, each
  answer asked canonically and in each vocabulary, beside the pinned input vocabulary, an entry point's default
  vocabulary and `/info`, with the golden's `ir.json` as the table of names (2 cases pinned, at least 34 requests);
  and an own witness (`scripts/twins/ambiguous-vocabulary.*`): a name that two vocabularies give to two
  declarations is a 400 naming both readings, and pinning the input vocabulary answers the same request (at least 4
  testimonies).
- `check-service-replay` takes its set from uchar's gate 12 (`uchar/scripts/check-drools.sh`: every golden with a
  case file outside `EXPECTED_CHANNEL`) and checks its size against uchar's `EXPECTED_PLAYED` (271). Each golden is
  served from its `source.rgs`, or without one from its `ir.json`, with one entry point that carries every form. The
  number that plays is pinned (`EXPECTED_REPLAY_PLAYED`, 241); every golden that does not play is listed by name with
  its reason (30, each a timeline form the request cannot express); a deviation always fails, and the list fails when
  it goes stale in either direction. About 90 seconds with four services at a time (`REPLAY_WORKERS`; 88 s measured 2026-10-22, 238 goldens).
- `scenario-test` runs the 32 lines of `examples/toka-scenarios/manifest`; the three golden TOKA scenarios uchar's
  scenario resolver refuses are listed in `scripts/toka-manifest-exclusions.txt` with a fragment of that refusal,
  and a listed scenario uchar accepts fails the run.

## Everyday use: `bin/regelspraak`

`bin/regelspraak <command>` is the one entry for a user, a script or another repository: it takes no class name
and no build path. Several sources are read as one model, concatenated in the order given, and paths are relative
to the directory the command is given in.

The messages of uchar are Dutch unless asked otherwise: `bin/regelspraak --language en <command>` renders them in
English, and the playground of the service does so per request for an `Accept-Language` that names `en`
(`MessageLanguage`). The messages the service itself writes stay Dutch.

| Command | What it does |
|---------|--------------|
| `check <source.rgs...>` | parse and resolve: the model summary and the diagnostics (in a chain of files as `file:line`), exit 1 on an error; then the build on uchar's exec-model branch, exit 3 when uchar does not carry the model |
| `ir <out.json> <source.rgs...>` | the `uchar-ir-v1` document of the model (uchar's emitter) |
| `cases <dir> <source.rgs...>` | the scenarios of a test document lowered to uchar case files in `<dir>`, beside the IR document they run against (`<dir>/ir.json`): the layout of uchar's corpus |
| `tokens <out.json> <source.rgs...>` | the semantic tokens of the sources (uchar's `Resolver.tokens`), for a consumer that colours the rules: per file, named as given, the spans that cover its text, each with a kind (`keyword`, `objectType`, `attribute`, `parameter`, `unresolved`, …), a position (line from 1 in that file, column from 0 in UTF-16 code units) and for a reference the place of its declaration, also in another file; and the diagnostics with code, file and line. Written also when the model has an error or a syntax error, with exit 0; the diagnostics go to stderr as well. The form, the kinds and their LSP token types: uchar's `frontends/regelspraak/grammar/DIVERGENCES.md`, "Semantic tokens" |
| `scenario [--trace] [--explanation] [--vocabulary <name>] <source.rgs...>` | lowering, uchar's replay and the report: PASS, FAIL or MARKER per scenario; exit 1 on a FAIL, 3 on a MARKER; `--vocabulary` names the rules of the trace and the declarations of the explanation in a vocabulary of the model |
| `scenario --manifest <manifest> [--exclusions <list>]` | every line `<verwachte-exit> <scenario.rgs> <companion.rgs...>` of a manifest, in one JVM; exit 0 when no line mismatches |
| `serve <descriptor.service.json> <source.rgs...> \| <document.json> [--allow-markers] [--detail] [--host H] [--port N]` | the decision service on the JDK `HttpServer` (`docs/ENGINE.md`); `PLAYGROUND=true` binds the workbench routes; `--detail` or `DETAIL=true` answers `?trace`, `?coverage` and `?fixpoint`, and the workbench's `?via=service` |
| `drl <source.rgs...>` | the DRL text of uchar's DRL-text branch, or exit 3 with uchar's refusal; exit 1 with uchar's sentence when that text does not compile (a fault of uchar's rendering) |
| `graph [--mmd] <out> <source.rgs...>` | uchar's rule graph as an HTML page, or as Mermaid text |
| `facts <outdir> <source.rgs...>` | the fact beans uchar's generator writes for the model |

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
make scenario FILE="--explanation model.rgs scenario.rgs"
make serve FILE="d.service.json model.rgs regels.rgs" PORT=8080
make drl FILE=model.rgs
make graph FILE="model.rgs regels.rgs"          # out/rule-graph.html (MMD=1 for Mermaid, OUT= overrides)
make facts                                      # the TOKA model foundation (FILE= another model), javac'd
make scenario-test                              # the golden TOKA scenario set
```

A scenario document runs through uchar's scenario layer: uchar resolves it, lowers the scenarios to case files,
and uchar's replay reports each one. With `--explanation` (`FILE="--explanation a.rgs"`) each played scenario also gets the
explanation of its outcome: uchar's replay plays it on the build with readings and hands over the session it judged
(`CaseRunner.report` with a handoff), and the verbaliser renders each condition from the engine's readings of that
session (`DroolsBackend.readingsOf`: per form and object how the condition came out, per node what the engine reached
and the value and unit it read), here and in the service's `?explanation=true`; it computes nothing itself. An empty
value stands as "(onbekend)", a member the engine did not reach is not named, and a condition that did not hold names
the member where the engine stopped. uchar's scenario resolver refuses three of the 29 golden TOKA
scenarios (listed with their reasons in `scripts/toka-manifest-exclusions.txt`).

The decision service serves a descriptor over `.rgs` sources or over one `uchar-ir-v1` document; `docs/ENGINE.md`
describes its routes, its MARKER answer and the dev-only playground. A scope that names a base rule also carries the
groups uchar makes of the own exceptions of its subtypes (`<basis> voor <subtype>`), and a scope that names such a group
carries its base. It checks every scope at the start: a rule or a defaults group in the scope that reads an attribute or
characteristic only forms outside the scope write refuses the start, naming the reader (for a group the default that
reads, by its text and path), unless the entry point declares that element in `inputElements` or sets
`scopeCheckOptOut`.

## Vocabularies

uchar lets a model declare named vocabularies and give each declaration a designation in them (`Vocabulaire
handboek`, `(handboek: totale premie)`; uchar's `spec/IR-SPEC.md` ch. 2.12). The designations carry no semantics:
rules are written and computed in the canonical names. The distribution uses them at the edge, as one service:

- **Input is liberal.** A request may write every name — object type, attribute, characteristic, fact type, role,
  parameter, enumeration value — canonically or as its designation in any vocabulary, mixed within one request,
  case-insensitively, within the scope of the name (the members of an object type with the inherited ones, the roles
  of a fact type, the values of a domain, the rest model-wide). A name that reads as two different declarations is a
  400 `AMBIGUOUS_NAME` whose sentence names both readings; the query parameter `inputVocabulary=<name>` pins
  the vocabulary the names are read in (`canonical` for the canonical names), which resolves it.
- **Output is chosen.** `vocabulary=<name>` names the declarations of the answer, its inconsistencies and its
  explanation in that vocabulary; without it the entry point's `vocabulary` from the descriptor applies, else the
  canonical names. An unknown vocabulary is a 400 `UNKNOWN_VOCABULARY` naming the declared ones.
- **Complete or not.** A declaration without a designation in the chosen vocabulary keeps its canonical name. uchar
  requires a designation of every declaration only in the default vocabulary; `/info` lists per vocabulary whether it
  is complete and which declarations have none.
- **One OpenAPI document per vocabulary.** `OpenApiEmitter.emit(descriptor, model, vocabulary)` writes the contract
  in the names of one vocabulary (or canonically); each property, value and entry point lists its other spellings in
  `x-spellings` / `x-valueSpellings`, which the service accepts as well. A model without vocabularies gets the
  document it always had.
- **A designation is part of the contract.** Changing a designation changes the names a consumer of that vocabulary
  sends and receives; treat it as a contract change of the service.

The word `canonical` chooses the canonical names, so a model that declares a vocabulary of that name does not start.
The rule text is not renamed: the explanation quotes it as written. The exception is a rule uchar wrote itself, the rule
`afwijking opgave X` of the opgave marker: its text is uchar's canonical spelling, not an author's, so the explanation names
the attribute an operand reads in the chosen vocabulary (`zijn opgegeven Datum einde recht op uitkering`). uchar's table
of origins (`RegelSpraakModel.generatedFrom`) says which rule that is; the IR document carries that table (`origin`,
IR-SPEC ch. 2.13), so a service started from an IR document explains these rules the same way. The row of a decision
table is generated too, but its text is the author's column header and cell, and stays as written.
In the explanation, an enumeration value whose designation
differs from the declared value is written as both, `<designation> · <value>` (`01 · Hoogte inkomsten uit verzekerde
arbeid`), because a code alone does not say what it stands for; the answer carries the designation alone. A
characteristic stands in the explanation with the verb of its declared kind (§3.5), in every vocabulary and also in a
condition: adjectival `is waardevol` / `is niet waardevol`, possessive `heeft de uitkeringsduur verstreken` / `heeft
geen uitkeringsduur verstreken`, nominal `is een recht op grond-van-de-wia` / `is geen recht op grond-van-de-wia`.
The model does not carry the article of a declaration, so a present possessive characteristic takes the article the
rule text writes before its name, and none where it writes none (`heeft recht op duurzaamheidskorting`).

## The container

`make image` builds the decision-service image (`decision-service:dev`; `TAG=` and `DESCRIPTOR_SRC=` override, the
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
| `DESCRIPTOR` | the `.service.json` (default: the baked TOKA descriptor) |
| `SOURCES_MANIFEST` | a file with the sources, one absolute path per line, in model order (default: the baked TOKA set) |
| `PORT` | the port, 8080 |
| `ALLOW_MARKERS` | `true` starts with entry points uchar does not carry, each request to one gets 503; otherwise such an entry point stops the start with exit 3 |

Mounting a rule set at `/app/rules` and setting `DESCRIPTOR` and `SOURCES_MANIFEST` serves it from the same image.
`helm/decision-service` deploys the image. `make kind-smoke` builds it, loads it into the kind cluster `regelspraak`
(created when absent; `CLUSTER=` names another, and an existing cluster that does not answer stops the run) and
checks the running service: the golden TOKA scenarios over HTTP, the marker's exit 3 and its 503 with
`allowMarkers`, and a rollout that changes a parameter and with it the decision. It then deletes the cluster;
`bash scripts/kind-smoke.sh` without `--teardown` keeps it, and so does a failed run, which names the failed step
and the command that deletes the cluster. Both targets need Docker and run on the host.

## Known losses

- **A parameter-set file on the scenario route** (`--parameter-sets`, refused by name). A scenario gets its parameter
  set from the scenario document itself: a `Parameterset <name>:` block, chosen with `met parameterset <name>`
  (uchar's scenario layer, since uchar's card `scenariolaag-in-uchar`). The former runner's JSON file beside the scenario has no
  counterpart.
- **Timelines in a service request.** The request has no timeline value, no characteristic with periods, and no
  scalar on a time-dependent attribute (uchar's replay places one in the bucket of the evaluation date); the service
  refuses them as `UNSUPPORTED`. 30 of the goldens gate 12 plays need one; the replay gate lists them.
- **A `uchar-ir-v1` document is served whole**: a scope that drops forms needs the model the document came from.
- **The explanation** explains characteristic-attribution rules, defaults groups and the values ordinary rules
  compute, not consistencies or creations, whose readings uchar has as well. A rule outside the scope of an entry point
  has no rule in its build and gets the one sentence "de regel '…' staat buiten deze berekening".
- **The playground trace** names per fired rule the labels it bound and how often, but no value: uchar's report
  carries none. The values stand in the scenario's `fixpoint`, read from the case's own session.
- **The sentence of an expectation.** uchar's scenario resolver gives the line an expectation begins on
  (`ResolvedScenario.Expectation.line`) and no end; the playground's `lineText` is that line, which is the whole
  expectation only where one bullet stands on one line. A timeline expectation of several bullets is one entry at
  its first bullet. The name of the parameter set a scenario chose is not in the resolved scenario either; its
  values are, each with its line.
- **Vocabulary names in the Mermaid graph.** The playground's `graph` and `bin/regelspraak graph` are uchar's
  rendering and stay canonical; the playground's `graphJson` carries the chosen vocabulary.

## The TOKA casus and the specification

`examples/toka*` holds the TOKA casus the tests and the golden scenarios run on.

> **TOKA casus.** Source: *RegelSpraak specificatie* v2.3.0 (4-12-2025), Bijlage 1, "Treinen Op Korte Afstand" (TOKA,
> a fictitious act from the Belastingdienst's ALEF course), published by the Belastingdienst at
> https://wendbarewetsuitvoering.pleio.nl/page/view/291f5846-cdfc-42d9-9f31-a2158fe59128/specificaties-regelspraak.
> Rights notice of the source: "© 2025 Belastingdienst / Alle rechten voorbehouden".
> Status: the Belastingdienst indicated to the maintainer on 2026-10-13 that TOKA is meant to be releasable. It is
> probably under EUPL-1.2, like ALEF (https://github.com/belastingdienst/ALEF) and the repository
> MinBZK/regels.overheid.nl, which carries a 2022 version of the course casus. Written confirmation: not yet received.
> The licence of this repository (Apache-2.0) does not cover this material.

Copied verbatim from the casus: the 47 definition fragments `examples/toka/*.rgs`, byte-identical to uchar's
`toka/casus/*.rgs`; they are not edited, so that the two stay in parity. Derived from the casus or this project's own
work: the rest of `examples/toka*` (the README and the service descriptor `toka.service.json` in `examples/toka/`, the
scenarios in `examples/toka-scenarios/`, the reconciliation in `examples/toka-reconciliation/`), the decision-table
examples `examples/beslistabel/spec12-*.rgs`, and the tests and test resources that load the casus.

The RegelSpraak v2.3.0 specification itself is not in this repository; `NOTICE` says where it is published.
`specification/v2.3.0/` holds this project's own notes derived from it.

## Working here

Work enters as a card in `backlog/` (`new/` → `doing/` → `done/`, the result recorded in the card). A question
about what RegelSpraak means — a construct, a verdict, a refusal — is a card in uchar, not a change here. The
naming convention: every identifier is English, as in the Conventions of uchar; the RegelSpraak language and the domain content stay Dutch.
