# Shipping the RegelSpraak → Drools executable-model engine

The executable-model back-end (`ExecModelTranspiler`) is the **shippable** path: it turns a resolved
RegelSpraak v2.3.0 model into an `org.drools.model.Model` (programmatic rules, pure-Java lambdas),
which a Drools runtime fires directly — no DRL text, no MVEL interpreter. (`DrlTranspiler` is retained
only as the differential-test oracle + a debug emitter.)

Shipping is split into **two modules** along the build-vs-fire boundary, so the transpiler stays lean
and engine-free while the consumer carries the runtime.

## The two-module split

| Module | Role | Drools dependency (scope) |
|--------|------|---------------------------|
| `regelspraak-parser` (repo root `pom.xml`) | parser + resolver + **builds** the `Model` | `drools-canonical-model` — **compile**; `drools-core`/`drools-engine`/`drools-mvel` — **test** (differential only) |
| `regelspraak-engine` (`engine/pom.xml`) | **fires** the `Model` over facts | `regelspraak-parser` + `drools-engine` — **compile/runtime** |

`drools-canonical-model` is a pure-Java authoring API with **zero** compile-time engine dependencies, so
the transpiler module compiles against it alone (`mvn dependency:tree` shows it as the only compile-scope
Drools artifact). The built-in accumulate functions (`org.drools.core.base.accumulators.*`, drools-core)
are referenced via `ExecModelTranspiler.accFn(name) = Class.forName(...)`, resolved at **model-build time**
from the runtime classpath — so they impose no compile-time `drools-core` coupling. (That FQN is
drools-core-internal and version-pinned to Drools 8.44; it relocates to `org.drools.base.accumulators` on
later Drools. It is centralized behind one constant with a fail-fast guard.)

`drools-engine` is the single aggregator that, at 8.44, transitively brings everything the consumer needs
to `createKieBaseFromModel(model).newKieSession()`: `drools-core`, `drools-model-compiler`,
`drools-kiesession`, and the wiring modules. **Do not add `drools-mvel`** — it is the deprecated MVEL-
interpreter path; the executable model evaluates constraints as compiled Java lambdas.

## Using the engine

```java
RegelSpraakEngine engine = RegelSpraakEngine.compile(regelSpraakSource);   // resolve → exec-model → KieBase
try (RegelSpraakEngine.Session s = engine.newSession()) {
    s.insert(persoon)
     .setGlobal("volwassenleeftijd", new BigDecimal(18))
     .fire();                                  // rules mutate persoon in place
}
Collection<Inconsistentie> fouten = /* a fresh session */ s.facts(Inconsistentie.class);
```

The fact classes the Model references must be on the classpath in package `io.github.yhavinga.regelspraak.facts`
(Pascal-case class, `getX`/`setX` per attribute, `isX`/`setX` per kenmerk, plus `Segment`/`Inconsistentie`
carriers). You do **not** hand-write them: `RegelSpraakCli --facts <outdir>` (or `make facts` for the TOKA casus)
**generates** exactly the beans a resolved model implies into that package, and `RegelSpraakEngine.compile`
runs `ContractValidator` at build time to enforce the same contract — a missing or mistyped member fails fast
with the full list of violations rather than a reflective abort mid-fire. Generate the beans once from the rule
source, keep them on the engine's classpath, and let the validator guarantee they still match after any rule change.

## The DRL oracle's classpath contract

Since the single-runtime refactor the generated DRL text contains **no `function` bodies**: every helper arrives as
`import function io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime.<naam>;`, so **the `regelspraak-parser`
artifact must be on the classpath that compiles the DRL** (the test suites already satisfy this — the
runtime lives in the same artifact as the transpiler). The one rs* implementation is
`RegelSpraakRuntime`; the DRL and exec back-ends now call the same bodies, so the differential suite
compares wiring, not re-implementations.

A §9.8 dagsoortdefinitie no longer becomes a generated DRL function either: the shared
`kern.Dagsoorten` compiler produces a `Predicate<LocalDate>` that the DRL declares as a
`global java.util.function.Predicate rsDagsoort_<Naam>;`. A session over such DRL must supply those
globals from `DrlTranspiler.build(model).dagsoortGlobalen()` — exactly like the parameter globals.
(The exec model bakes the same predicates into its lambdas; its sessions need nothing extra.)

## Building

```
make engine          # mvn install (transpiler, canonical-model only) + mvn -f engine/pom.xml test
```

`RegelSpraakEngineTest` is the end-to-end proof: a rule compiles, fires over real facts, and mutates them;
a type error surfaces as `RegelSpraakCompileException`.

## Deploying the beslisservice (container + Helm + kind) — HOST only

The decision service (`service/`, `DienstMain`) ships as a container image with a Helm chart;
everything below needs Docker, so it runs on the host.

```
make image  [TAG=dev] [DESCRIPTOR_SRC=examples/toka/toka.dienst.json]
                     # multi-stage build (docker/Dockerfile): stage 1 chains
                     # mvn install parser → engine → service (no aggregator pom, so no -pl/-am),
                     # stage 2 = JRE 21 + service.jar + the baked TOKA rule set & descriptor.
docker run --rm -p 8080:8080 beslisservice:dev          # POST /<pad> per entrypoint, GET /info
make kind-smoke      # scripts/kind-smoke.sh: kind cluster (create/reuse) → kind load →
                     # helm install → H2 golden oracle → H3 fail-fast pins → H4 rollout pin
```

Container contract (see `docker/entrypoint.sh`): env `DESCRIPTOR`, `BRONNEN_MANIFEST` (file
listing the `.rgs` sources in order), `POORT`, `ALLOW_MARKERS`. Defaults serve the baked TOKA demo; mount
a volume at `/app/rules` and override the two paths to serve any other rule set from the same image.
The image also bakes `/app/rules/marker/` — a self-contained marker-scoping fixture for the fail-fast gate.

Helm chart `helm/beslisservice/` (values-dev/values-prod overlays): all probes hit `/info`.
**Listening == rules compiled** — `DienstMain` compiles every scoped rule *before* binding the port, so
a Ready pod has a fully built KieBase; the startupProbe grants that compilation up to ~10 minutes. A
descriptor scoping a MARKER rule without `allowMarkers=true` exits 3 → CrashLoopBackOff *by design*.

**peildatum-contract (multi-vintage deploys).** When the descriptor names a parameterset sidecar whose values
carry more than one vintage (validity-versioned parameters — e.g. a rate that changes every 1 January), the
service selects each parameter's vintage against ONE date authority: the request `peildatum`, which *is* the
rekendatum global. The runtime already enforces this — a request without `peildatum` gets `400 "peildatum
verplicht"`, a peildatum/rekendatum clash gets 400, and `/info` lists the loaded validity ranges plus
`periodeWaarschuwingen`. To make the contract visible at deploy time, declare it: `--set
parametersets.multiVintage=true` prints the reminder in the post-install NOTES. The advisory
`scripts/peildatum-vintage-check.sh <parametersets.json> <true|false>` WARNs (never blocks) on a
declared/actual mismatch — run it pre-install against the sidecar you are about to bake or mount.

The third transport: `GoldenOverHttpTest` accepts `-Ddienst.url=<base-url>` (or env `DIENST_URL`) and
then runs the IDENTICAL 29 golden TOKA assertions against that already-running service instead of an
in-process one — scenario-runner == local HTTP == in-cluster HTTP, zero re-typed expected values:

```
kubectl port-forward svc/beslisservice 18080:8080 &
mvn -o -f service/pom.xml test -Dtest=GoldenOverHttpTest -Ddienst.url=http://127.0.0.1:18080
```

## Wiring into Quarkus

A Quarkus application consumes the model in exactly this shape:

- depend on `regelspraak-parser` + `drools-engine` (or `drools-quarkus-*` for a native build — the
  `RegelSpraakEngine` facade is unchanged);
- hold a `RegelSpraakEngine` as an `@ApplicationScoped` bean, compiled once at startup from the rule source;
- open a `Session` per request (insert request facts, fire, read results), then `close()` it.
