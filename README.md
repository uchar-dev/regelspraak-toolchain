# RegelSpraak-java

A complete **RegelSpraak v2.3.0** toolchain: from an ANTLR grammar and a
semantic resolver, through a typed IR and two Drools back-ends, to a deployable
decision service. RegelSpraak is a Dutch business-rules DSL; this repo takes a
`.rgs` rule set from authoring all the way to a firing, HTTP-exposed engine.

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
  outcomes in one `.rgs` surface and gets machine-checkable PASS/FAIL per scenario
  (contract: `docs/SCENARIOTOETSEN.md`).
- **Explain** — `--uitleg` (CLI) / `?uitleg=true` (service) attaches a plain-NL
  uitleg-verhaal: one sentence per assigned/withheld kenmerk, justified with the
  evaluated operandwaarden read from the IR.
- **Visualise dataflow** — `make graaf` emits the regelgraaf: which rule writes
  which kenmerk/attribuut and which rules read it, as HTML (or bare Mermaid).
- **Serve** — a JSON descriptor becomes an HTTP decision service with build-time
  OpenAPI, an `/info` endpoint reporting coverage (dekking), and validity-
  versioned parametersets selected against the request `peildatum`. A dev-only
  RegelSpraak workbench binds at `/playground` when `PLAYGROUND=true` (off in prod).

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
| `htmldocs/` | the static toolchain docs page, published to the shared VM via `make docs-deploy` — ownership split with the sibling repos in `htmldocs/README.md` |
| `deployment/` | `transpiler-docs/deploy-transpiler-docs.sh` (rsync of `htmldocs/transpiler/` to the VM; dry-run supported) |
| `scripts/` | `kind-smoke.sh` (in-cluster smoke gates), `peildatum-vintage-check.sh` (advisory) |
| `tools/` | `Parse.java` (grammar smoke harness), corpus extraction, CSV→Beslistabel converter |
| `Makefile` | the build entry point (`make help`) |

**The root `pom.xml` is NOT an aggregator.** It builds only `regelspraak-parser`;
`engine/`, `service/`, and `service-quarkus/` are separate builds wired together
by explicit `mvn install` chains. The **Makefile is the entry point** — start
with `make help`.

## Quickstarts

**Rule analyst** — write a `.rgs` model plus scenariotoetsen, then check them:

    make scenario-test FILES="model.rgs regels.rgs scenario.rgs"   # PASS/FAIL per scenario
    make scenario-test                                          # the golden TOKA set (--manifest, one JVM)

Author rules and their expected outcomes in one surface, no Java. Add `--uitleg`
to the CLI for the plain-NL uitleg-verhaal per scenario. See
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

## Uitleg, regelgraaf & playground

Three capabilities on top of the decision chain, aimed at the people *around*
the engine — the jurist who must trust a decision, the analyst who must see the
model, and the newcomer who must learn the language.

**Uitleg — the decision explains itself.** The engine already knows, per fired
rule, which voorwaarden held and with which evaluated values; the `Verbalisator`
(engine module) turns that into plain-Dutch sentences: one per assigned kenmerk
("… omdat de maandloon nieuw van de Eindiging (1.750,01) groter is dan de
drempelwaarde van de Eindiging (1.750,00)") and one per *withheld* kenmerk,
naming the first failing conjunct. Numbers and dates in NL notation; forms the
verbaliser cannot structure are reported honestly as "(onbekend)" rather than
paraphrased. Invoke it anywhere the engine fires:

    java … ScenarioRunnerCli --uitleg model.rgs scenario.rgs   # per scenario, under PASS/FAIL
    curl -X POST 'http://…/<entrypoint>?uitleg=true' -d @casus.json   # extra response field

Both are additive — existing callers see byte-identical output without the flag.
Known limit: `// Bron:`-annotaties from `.rgs` sources are skipped by the lexer,
so the uitleg cannot yet cite wetsartikelen; carrying comments on a hidden
channel is the natural next step.

**Regelgraaf — the model as a picture.** `Resolver --graaf out.html bron.rgs…`
(or `make graaf FILE=…`) renders the producer/consumer graph of the resolved IR:
rules, attributen and kenmerken as nodes, dataflow as edges, with two colours
that matter — **inputs** (nothing produces them: exactly the request/koppelvlak
boundary of a rule set) and **terminal outputs** (nothing consumes them: the
service's deliverables). The extraction shares its core (`ProducentConsument`)
with the service's scope-completeness validation, so the picture and the
enforcement can never disagree. The HTML embeds Mermaid via CDN; use
`--graaf-mmd` for a bare offline `.mmd`.

**Playground — type RegelSpraak, see everything.** A dev-only workbench in the
service: `GET /playground` serves a single self-contained page (textarea left;
parse tree / resolver verdict / generated DRL / scenario outcome as tabs right),
backed by `POST /playground/analyse`. Parse errors come back with position and
expected tokens; include a `Scenario`-blok in the source and it fires for real.
Compile-per-request with a 15 s timeout, no persistence, and **off by default**
— it binds only when `PLAYGROUND=true`, and is meant for a developer laptop, not
production.

    PLAYGROUND=true java -cp … io.github.yhavinga.regelspraak.service.http.DienstMain \
        <descriptor.dienst.json> <bron.rgs…>        # then open /playground
    # or from a rule repo (a domain rule repo): PLAYGROUND=true make serve

## Naming convention

Infrastructure and directory names are English; domain code and the DSL surface
are Dutch (`BeslisserKern`, `scenariotoetsen`, `kern/Tijdrekening`). This is
deliberate — the domain language is Dutch law, so the vocabulary that models it
stays Dutch.
