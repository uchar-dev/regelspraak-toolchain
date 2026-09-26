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
descriptor over the same core (`BeslisserKern`). Its source is a set of `.rgs` files, resolved together into one
model, or one `uchar-ir-v1` document (a `.json` file) that uchar's loader reads. Per entry point the core keeps the
forms its scope names — the rules, decision-table rows, defaults groups and other forms of its rule names and rule
groups (`VormIndex`, then `CaseEmitter.metScope`, the lowering of uchar's scenario layer) — and builds that document
through uchar, so each entry point has its own KieBase and bean class loader and a form outside its scope does not
decide whether it runs. A `uchar-ir-v1` document is served whole: a scope drops forms by rebuilding the model it
came from, which a document without its source does not have, so a scope that does not carry every form refuses the
start.

```
java -cp … dev.uchar.regelspraak.toolchain.service.http.DienstMain <descriptor.dienst.json> <bron.rgs…> | <document.json> \
    [--allow-markers] [--host H] [--poort N]
```

- `POST /<pad>` per entry point; `?uitleg=true` adds the verbaliser's sentences to the answer. The inline `rollen`
  of a request are written as uchar's replay writes a case's `roles` (`RolSchrijver`: by the role's written name,
  the far field of a one-to-one relation too, a to-many role through the far end); an enumeration value names a
  declared value as uchar compares declared names (case and spacing aside), and keeps its own spelling.
- The answer lists per instance the selected attributes and characteristics, `inconsistenties` (the rule names of
  the failed consistency checks) and `inconsistentieDetails` (the same, each with its `onderwerp`: the request id
  of the instance the check is about). A 400 names per violation its `veld`, its `soort` and its message; `soort`
  `NIET_ONDERSTEUND` marks a request form the service does not carry (a timeline value, a characteristic with
  periods, a dimensioned attribute).
- `GET /info`: per entry point whether it is `uitvoerbaar`, its `dekking` (the exec-model branch's verdict per form
  of its document, defaults groups included), its markers and, when uchar does not carry it, uchar's sentence as
  `weigering`; the parameter defaults, the loaded parameter sets and the period-coverage warnings.
- What uchar does not carry — a refusal of its emitter, loader or backend (a construct the exec-model branch does
  not lower among them), or a scoped rule name that yields no form in uchar's model — makes the entry point a
  marker. Strict, the service refuses to start (exit 3, `MARKER: …` with uchar's sentence). With `--allow-markers`
  (`ALLOW_MARKERS=true` for Quarkus) it starts, and every request to that entry point gets 503 with
  `{"status": "MARKER", "fout": …, "reden": <uchar's sentence>, "markers": […]}`: an answer without the construct
  would be an answer to another rule set.
- `PLAYGROUND=true` binds the dev-only workbench (`GET /playground`, `POST /playground/analyse`). A source with a
  parse or resolver error gets its diagnostics and nothing else. A clean source gets the `uchar-ir-v1` document,
  the exec-model verdict (`uitvoerbaar` with the `dekking`, or uchar's `weigering`), the DRL of the DRL-text branch
  as a view (not the verdict), uchar's rule graph of the document its loader reads back, as Mermaid (`graaf`) and
  as `graafJson`
  (`{nodes:[{id,label,kind,eindOutput}], edges:[{van,naar}]}`), and per scenario uchar's report with its `trace`
  and `uitleg`.

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
