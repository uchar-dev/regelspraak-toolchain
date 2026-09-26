# The engine facade and the decision service

Everything here fires through uchar: a RegelSpraak source is resolved by uchar's frontend, emitted as a
`uchar-ir-v1` document, and built by uchar's Drools backend on its exec-model branch
(`DroolsBackend.build(document, branch)`, called from `UcharBouw`). That build generates the fact beans from the
model, compiles them into a class loader of their own, checks the bean contract and lowers every construct with
every side table the loader returns. It refuses a model with a construct the branch does not lower, so no KieBase
here lacks a rule without saying so.

## Using the engine

```java
RegelSpraakEngine engine = RegelSpraakEngine.compile(regelSpraakSource);   // resolve → emit → uchar's build
Object persoon = engine.nieuw("Persoon");                                  // a generated fact bean
RegelSpraakRuntime.set(persoon, "leeftijd", new BigDecimal(16));
try (RegelSpraakEngine.Session s = engine.newSession()) {
    s.insert(persoon)
     .setGlobal("volwassenleeftijd", new BigDecimal(18))
     .fire();                                  // rules mutate persoon in place
}
```

The facts are instances of the generated beans (`nieuw`, `beanLoader()`), in uchar's package
`dev.uchar.regelspraak.facts`: the KieBase is built against those classes, so an object of a same-named class from
another class loader never matches a rule. `newSession(peildatum)` sets the `rekendatum` global;
`newSession(peildatum, parametersets)` also selects each parameter's vintage on that one date. `compile` throws
`RegelSpraakCompileException` for resolver errors and for a model uchar refuses. `RegelSpraakCli --facts <outdir>`
(or `make facts` for the TOKA casus) writes the beans a model implies as source, for a consumer that wants to see
them.

## Building

```
make verify          # check-pin, then mvn -B -o verify over the whole reactor
mvn -B -o verify -pl toolchain -am      # only the toolchain core and what it needs from uchar
```

`RegelSpraakEngineTest` is the end-to-end proof: a rule compiles, fires over generated beans, and mutates them;
a type error and a construct uchar does not lower both surface as `RegelSpraakCompileException`.

## The decision service

`service/` (`DienstMain`, the JDK `HttpServer`) and `service-quarkus/` (the Quarkus adapter) serve a JSON
descriptor over the same core (`BeslisserKern`). Per entry point the core resolves the rule-less prelude files plus
the entry point's scoped rule files and builds them through uchar, so each entry point has its own KieBase and its
own bean class loader.

```
java -cp … dev.uchar.regelspraak.toolchain.service.http.DienstMain <descriptor.dienst.json> <bron.rgs…> \
    [--allow-markers] [--poort N]
```

- `POST /<pad>` per entry point; `?uitleg=true` adds the verbaliser's sentences to the answer.
- `GET /info`: the entry points with their coverage (`dekking`) and markers, the parameter defaults, the loaded
  parameter sets and the period-coverage warnings.
- A construct uchar's exec-model branch does not lower refuses startup (exit 3). With `--allow-markers`
  (`ALLOW_MARKERS=true` for Quarkus) the service starts, lists the construct under the entry point's markers,
  and answers every request to that entry point with 503.

**peildatum-contract (multi-vintage deploys).** When the descriptor names a parameterset sidecar whose values
carry more than one vintage, the service selects each parameter's vintage against ONE date authority: the request
`peildatum`, which *is* the rekendatum global. A request without `peildatum` gets `400 "peildatum verplicht"`, a
peildatum/rekendatum clash gets 400, and `/info` lists the loaded validity ranges plus `periodeWaarschuwingen`. The
advisory `scripts/peildatum-vintage-check.sh <parametersets.json> <true|false>` WARNs (never blocks) on a
declared/actual mismatch.

## Deploying (container + Helm + kind) — host only

`make image`, `docker/`, `helm/beslisservice/` and `make kind-smoke` package the service. The Dockerfile still
builds the layout from before the submodule (it copies `vendor/` and chains `mvn install` over the old modules);
until it follows the reactor, `make image` fails. The container contract (`docker/entrypoint.sh`): env
`DESCRIPTOR`, `BRONNEN_MANIFEST` (file listing the `.rgs` sources in order), `POORT`, `ALLOW_MARKERS`.
