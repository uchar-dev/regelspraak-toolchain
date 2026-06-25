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
(the consumer's domain beans: Pascal-case class, `getX`/`setX` per attribute, `isX`/`setX` per kenmerk).

## Building

```
make engine          # mvn install (transpiler, canonical-model only) + mvn -f engine/pom.xml test
```

`RegelSpraakEngineTest` is the end-to-end proof: a rule compiles, fires over real facts, and mutates them;
a type error surfaces as `RegelSpraakCompileException`.

## Wiring into Quarkus

A Quarkus application consumes the model in exactly this shape:

- depend on `regelspraak-parser` + `drools-engine` (or `drools-quarkus-*` for a native build — the
  `RegelSpraakEngine` facade is unchanged);
- hold a `RegelSpraakEngine` as an `@ApplicationScoped` bean, compiled once at startup from the rule source;
- open a `Session` per request (insert request facts, fire, read results), then `close()` it.
