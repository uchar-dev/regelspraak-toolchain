# RegelSpraak-rebooted

A complete **RegelSpraak v2.3.0** (TOKA) toolchain: from an ANTLR grammar and a
semantic resolver, through a typed IR and two Drools back-ends, to a deployable
decision service. RegelSpraak is a Dutch business-rules DSL; this repo takes a
`.rs` rule set from authoring all the way to a firing, HTTP-exposed engine.

The specification in `specification/v2.3.0/` is the **single source of truth**.
Correctness means adherence to that spec — not parity with any prior engine. See
**[CLAUDE.md](CLAUDE.md)** for the agent constitution and the standing gate-set,
and **[docs/](docs/)** for the architecture and runbooks.

## What it does

- **Parse** — an ANTLR 4.13.2 lexer + parser authored from the v2.3.0 spec.
- **Resolve & typecheck** — a visitor builds a typed IR and enforces the
  typeringen matrix (datatypes, units, arity, name resolution).
- **Transpile** — one semantic core, two thin Drools back-ends: an
  **executable-model** back-end (the shippable transport since commit `9ce4ed0`)
  and a **DRL-text** back-end (retained as the differential oracle + debug
  emitter).
- **Generate the fact model** — `Resolver --facts` (or `make facts`) emits
  exactly the beans a resolved model implies; `ContractValidator` fails fast on
  drift.
- **Author-side scenariotoetsen** — a rule analyst writes rules *and* expected
  outcomes in one `.rs` surface and gets machine-checkable PASS/FAIL per scenario
  (contract: `docs/SCENARIOTOETSEN.md`).
- **Serve** — a JSON descriptor becomes an HTTP decision service with build-time
  OpenAPI, an `/info` endpoint reporting coverage (dekking), and validity-
  versioned parametersets selected against the request `peildatum`.

## Layout

| Path | Role |
|------|------|
| `grammar/` | `RegelSpraakLexer.g4` + `RegelSpraak.g4` (lexer first), authored from the spec |
| `src/` | the `regelspraak-parser` module — `resolver/` (typed IR builder + typeringen), `ir/` (the resolved AST both back-ends read), `model/` (the domain model: `Datatype`, `Eenheid`, `Parameterset`, `RegelSpraakModel`), `transpiler/` (shared `kern/` + both back-ends: `DrlTranspiler` and `model/ExecModelTranspiler`), `runtime/` (`RegelSpraakRuntime`, the one rs* implementation), `schema/` (fact-model generator + `ContractValidator`) |
| `engine/` | `regelspraak-engine` — fires the exec-model `Model` over facts; hosts the scenariotoetsen runner |
| `service/` | `regelspraak-service` — zero-dependency JDK-`HttpServer` decision service (`BeslisserKern`, OpenAPI emitter) |
| `service-quarkus/` | `regelspraak-service-quarkus` — opt-in Quarkus HTTP adapter over `BeslisserKern` (SmallRye health, config injection) |
| `examples/` | `toka/` (the TOKA casus, `toka-01..47` + `toka.dienst.json`), `beslistabel/` (§12 decision tables), `toka-reconciliatie/` (casus-omissie companions), `toka-scenarios/` (the golden scenariotoetsen + their `manifest`) |
| `specification/` | the v2.3.0 spec (Markdown + authoritative PDFs) — the oracle |
| `docs/` | `ENGINE.md` (engine + deploy runbook), `TRANSPILER-ARCHITECTURE.md` (core + two back-ends), `SCENARIOTOETSEN.md` (the analyst-toets contract), `grammar-divergences.md` (spec reconciliations), `ANTLR-HOWTO.md` (grammar workflow) |
| `docker/` | the **beslisservice** production image (`Dockerfile`, built via `make image`) — see `docker/README.md` |
| `helm/` | `beslisservice/` Helm chart (dev/prod value overlays) |
| `scripts/` | `kind-smoke.sh` (in-cluster smoke gates), `peildatum-vintage-check.sh` (advisory) |
| `tools/` | `Parse.java` (grammar smoke harness), corpus extraction, CSV→Beslistabel converter |
| `Makefile` | the build entry point (`make help`) |

**The root `pom.xml` is NOT an aggregator.** It builds only `regelspraak-parser`;
`engine/`, `service/`, and `service-quarkus/` are separate builds wired together
by explicit `mvn install` chains. The **Makefile is the entry point** — start
with `make help`.

## Quickstarts

**Rule analyst** — write a `.rs` model plus scenariotoetsen, then check them:

    make scenario-test FILES="model.rs regels.rs scenario.rs"   # PASS/FAIL per scenario
    make scenario-test                                          # the golden TOKA set

Author rules and their expected outcomes in one surface, no Java. See
`docs/SCENARIOTOETSEN.md`.

**Developer** — build, test, embed:

    make test                    # jar-only parser/resolver JUnit gate (offline, no Maven)
    mvn -o clean verify          # full differential suite (needs Drools deps)
    make engine                  # build + test the shippable engine consumer

Embed the engine via `RegelSpraakEngine.compile(source)` → `newSession()` →
`fire()`. See `docs/ENGINE.md`.

**Ops** — build the image and smoke-test in-cluster (needs Docker):

    make image                   # build the beslisservice image (docker/Dockerfile)
    helm install beslisservice helm/beslisservice
    make kind-smoke              # kind H2/H3/H4 gates against the live service

Deploy runbook (container contract, Helm probes, peildatum contract):
`docs/ENGINE.md`.

## Naming convention

Infrastructure and directory names are English; domain code and the DSL surface
are Dutch (`BeslisserKern`, `scenariotoetsen`, `kern/Tijdrekening`). This is
deliberate — the domain language is Dutch law, so the vocabulary that models it
stays Dutch.
