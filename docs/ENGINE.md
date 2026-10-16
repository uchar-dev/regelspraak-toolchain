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
     .fire();                                  // fires to the fixpoint through uchar's entry
    s.zetTerug();                              // the derived values onto persoon
}
```

A session fills, fires and reads through uchar's entry (`DroolsBackend.voegIn`, `vuur`, `lees` and
`zetAfgeleideWaarden`; uchar's `backends/drools/README.md`, "The entry for consumers"). `insert` and `insertAll`
gather the beans until the first `fire()`, which puts them in and fires to the fixpoint; a later `fire()` only fires.
Two statements go with the beans, before the first `fire()` as well. A consumer that sets an attribute on a timeline as
the one segment of the period of the peildatum states it with `emmerscalair(bean, attribuut)`: without it the branch
reads a full timeline of one segment, and the subject's own total faults with an `Invoervormfout`, while a form that
needs the full timeline computes over that segment without a fault. `lijst(houder, rol, leden)` gives the order of a
to-many list, which decides only which member a fault of a fold names; without it a list keeps the order of the
inserts.
The exec-model branch derives with truth maintenance, so a derived value is a fact of the session and the beans hold
the input alone until `zetTerug()`: `lees()` gives what stands at the fixpoint of the last fire (uchar's `Fixpunt`: the
derived values and characteristics, the objects and links the creations made, the evaluation faults as data, the
inconsistencies) without writing anything, and `zetTerug()` puts it on the beans, after which the session does not
fire again. Between two fires a consumer changes an attribute or a characteristic of a bean of the session and calls
`update(bean)`; an object that comes or goes, a changed relation or another global asks a fresh session, and
`setGlobal` belongs before the first `fire()`. uchar's entry refuses each of these by name. A model with an object or
a fact creation fires once per session: uchar refuses a second `fire()` there, so a change asks a fresh session. In a
session that may fire again, a forgotten `update` uchar's entry does not refuse: the entry does not see an attribute
or a characteristic set without one, so the next `fire()` fires nothing, and `lees()` and `zetTerug()` give the old
fixpoint (uchar's `backends/drools/README.md`, "What a consumer can forget"). `fire()` returns the number of firings
and throws with uchar's sentence when a run reaches no fixpoint;
`zetTerug()` throws every evaluation fault that stands at the fixpoint as uchar's own exception, two or more as
`Evaluatiefouten`: one of the four types uchar's README names (`Breuk.DelingDoorNul`, `Rekenfout`,
`Schrijversconflict`, `Invoervormfout`) or another, such as the `IllegalStateException` of a fact creation that gives
a to-one role a second filling. A fault is an evaluation fault by its place at the fixpoint, not by its type:
`lees().fouten()` gives each as data, which tells it from an exception of the entry itself. `facts(…)` reads the
working memory, the `Inconsistentie` facts among them.

The facts are instances of the generated beans (`nieuw`, `beanLoader()`), in uchar's package
`dev.uchar.regelspraak.facts`: the KieBase is built against those classes, so an object of a same-named class from
another class loader never matches a rule. `newSession(peildatum)` sets the `rekendatum` global;
`newSession(peildatum, parametersets)` also selects each parameter's vintage on that one date. `compile` throws
`RegelSpraakCompileException` for resolver errors and for a model uchar refuses. `bin/regelspraak facts <uitmap>
<bron.rgs...>` (or `make facts` for the TOKA casus) writes the beans a model implies as source, for a consumer that
wants to see them.

## Building

```
make verify          # check-pin, then mvn -B -o verify over the whole reactor
mvn -B -o verify -pl toolchain -am      # only the toolchain core and what it needs from uchar
```

`RegelSpraakEngineTest` is the end-to-end proof: a rule compiles, fires over generated beans through uchar's entry
and writes the derived values back onto them; a session that fires twice with an update in between reads what a fresh
session reads; a bucket scalar and the order of a list reach uchar's entry with the first fire, and the session then
gives what uchar's replay gives on the same case; a type error and a construct uchar does not lower both surface as
`RegelSpraakCompileException`.

## The decision service

`service/` (`DienstMain`, the JDK `HttpServer`) and `service-quarkus/` (the Quarkus adapter) serve a JSON
descriptor over the same core (`BeslisserKern`). Its source is a set of `.rgs` files, resolved together into one
model, or one `uchar-ir-v1` document (a `.json` file) that uchar's loader reads. Per entry point the core keeps the
forms its scope names — the rules, decision-table rows, defaults groups and other forms of its rule names and rule
groups (`VormIndex`, then `CaseEmitter.metScope`, the lowering of uchar's scenario layer) — and builds that document
through uchar, so each entry point has its own KieBase and bean class loader and a form outside its scope does not
decide whether it runs. A defaults group without a base of its own, which uchar makes of the own exceptions of a
subtype (`<basis> voor <subtype>`), goes with the groups of the base it inherits, since RUNTIME-SEMANTICS ch. 7.10
composes one stack per concrete type from them: a scope that names either carries all of them, as uchar's
scenario layer does as well since uchar `2a0c6d60` (`ScenarioResolver`). A `uchar-ir-v1` document is served whole: a scope drops forms by rebuilding the model it
came from, which a document without its source does not have, so a scope that does not carry every form refuses the
start.

At the start the core also checks that each scope uchar carries is complete. It reads the forms of the entry point's
KieBase as uchar's rule graph reads them (`ProducentConsument.vormen`): of a rule its condition and value, of a defaults
group the justification and consequence of every default, nested exceptions included, over roles as well. An attribute
or characteristic a rule or a defaults group of the scope reads, which forms outside the scope write and none inside,
refuses the start (`SCOPE_ONVOLLEDIG`, exit 1). The violation names the reader — a rule, the group of the rule a tree
of exceptions hangs on (or of a member of an `Uitzonderingen ongeordend` block), or a group uchar generates by its
name (`opgave boven berekening X` for the opgave marker, `X voor S` for the own exceptions of a subtype S) — the
element and its writers. For a group it also names each default that reads the element, from uchar's walk per default
(`ProducentConsument.lezingen`): the part that reads with its source text and the default's path in the group, as in
"in de rechtvaardiging 'zijn bergdag later is dan 01-06-2026' van default [0]" ([0] the highest default, [i, g, j] the
j-th default of exception group g of default i). An element no form writes is request input. Two opt-outs per entry point: `invoerElementen`
declares an element the caller supplies, and `scopeControleUit: true` waives the check (the replay gate sets it). The
other forms a scope can carry (object creations, allocations, consistencies, uniquenesses, fact creations) are not
checked.

```
bin/regelspraak serve <descriptor.dienst.json> <bron.rgs…> | <document.json> [--allow-markers] [--detail] [--host H]
                      [--poort N]
```

- `POST /<pad>` per entry point; `?uitleg=true` adds the verbaliser's sentences to the answer. The inline `rollen`
  of a request are written as uchar's replay writes a case's `roles` (`RolSchrijver`: by the role's written name,
  the far field of a one-to-one relation too, a to-many role through the far end); an enumeration value names a
  declared value as uchar compares declared names (case and spacing aside), and keeps its own spelling. Each to-many
  list goes to uchar in the order the replay gives it (`RolSchrijver.Lijsten`, `Invoer.lijst`): the members written
  under the holder first, in the order of the request, then the members linked from their own side, by an inline
  role or by `relaties`, by id in byte order. That order decides which member a fault of a fold names, so the 422
  names the member uchar's replay names on the same input. A to-one role that gets a second, different target, among
  the inline roles or through `relaties`, is a 400 `VERKEERD_TYPE` on the role that names the second target.
- Names and vocabularies (README, "Vocabularies"). `NaamLezer` reads every name of a request canonically or as a
  designation in any vocabulary of the model (uchar's `RegelSpraakModel.benoemd`), within the scope of its kind,
  case-insensitively and mixed; a name that reads as two declarations is a 400 `DUBBELZINNIGE_NAAM` naming both
  readings, and `?invoervocabulaire=<naam>` (or `canoniek`) pins the vocabulary names are read in. A designation is
  read as the declaration's canonical name, an enumeration value as the declared value it names. Two names of one
  object that read as one declaration are a 400 `MALFORMED`. `?vocabulaire=<naam>` chooses the names of the answer,
  its `inconsistenties` and its `uitleg` (`Naamgeving`), else the entry point's `vocabulaire` from the descriptor,
  else the canonical names; an unknown vocabulary is a 400 `ONBEKEND_VOCABULAIRE`, and a descriptor naming one does
  not start. A declaration without a designation in the chosen vocabulary keeps its canonical name. The Quarkus
  adapter reads the same query parameters.
- Each request gets a fresh session that the core fills, fires and reads through uchar's entry (`DroolsBackend.voegIn`,
  `vuur`, `lees`, `zetAfgeleideWaarden`), so the answer carries the values the rules derive. The answer lists per
  instance the selected attributes and characteristics, `inconsistenties` (the rule names of the failed consistency
  checks) and `inconsistentieDetails` (the same, each with its `onderwerp`: the request id of the instance the check
  is about). A 400 names per violation its `veld`, its `soort` and its message; `soort` `NIET_ONDERSTEUND` marks a
  request form the service does not carry (a timeline value, a characteristic with periods, a dimensioned
  attribute), and a detail flag of a service started without `DETAIL` (below).
- A request on which the rules give an evaluation fault that stands at the fixpoint gets 422 with
  `{"status": "EVALUATIEFOUT", "fout": …, "fouten": [{"soort": …, "fout": <uchar's sentence>}, …]}`, one element per
  fault in uchar's order. Every fault at the fixpoint is such an element, whatever its type: the core reads them from
  uchar's entry (`DroolsBackend.lees`) before the write-back, so an exception of the entry itself stays a 500.
  `soort` is `DELING_DOOR_NUL` (a division by a numeric zero), `REKENFOUT` (a fault of a computation or of the
  evaluation date), `SCHRIJVERSCONFLICT` (two writers of one attribute that disagree), `INVOERVORMFOUT` (a timeline
  input in the other convention, which no request reaches, since a request carries no timeline) or `ANDERE` (a fault
  of another type, such as a fact creation that gives a to-one role a second filling). Where uchar's sentence names
  an object, it names an object of the request by its id; a sentence about a kind of object (the second filling: "a
  Jutter") names none. No stacktrace enters the answer. The Quarkus adapter answers the same, and the
  OpenAPI contract describes the body of the 422 (schema `Evaluatiefout`, with `soort` as an enumeration of these
  kinds).
- A request whose session reaches no fixpoint gets 422 with `{"status": "GEEN_FIXPUNT", "fout": <uchar's
  sentence>}`: uchar's `DroolsBackend.vuur` ended the run at its fire cap or at the creation budget
  (`Vuring.geenFixpunt`), and the core throws `GeenFixpunt` with that sentence. Nothing stands to be read then, so
  nothing is read or written back; the service does not judge which of the two it was. With the detail flags (below)
  the 422 carries `trace` and `dekking` of what fired up to the end of the run, and for `?fixpunt=true` `fixpuntFout`
  with the same sentence. The Quarkus adapter answers the same; the OpenAPI 422 is one of `Evaluatiefout` and
  `GeenFixpunt`. Any other exception stays a 500 `interne fout`.
- The detail of a session. A service started with `--detail` or `DETAIL=true` (Quarkus: `DETAIL=true`; default off,
  as `PLAYGROUND`, and no part of the container contract) answers three more query flags, read as `uitleg` is (`true`
  or bare), each adding a field after `uitleg`: `?trace=true` gives `trace: [{regel, labels, aantal}]`, per form that
  fired, in the order a rule of it first fired, the request ids its matches bound and how often its rules fired, as
  uchar's `DroolsBackend.Waarnemer` records them on the request's session; `?dekking=true` gives `dekking: [{regel,
  soort}]`, per form of the entry point's build `GEVUURD` or `NIET_GERAAKT` (uchar's `DroolsBackend.dekking` over the
  forms the waarnemer saw); `?fixpunt=true` gives `fixpunt: {objecten, gemaakteObjecten, koppelingen, fouten,
  inconsistenties}` in the form of the playground's (below), with the instances of the request as its input: an
  instance by its id, its input values read off its bean before the fire, a made object `#<n>`, a type by its name;
  or `fixpuntFout` with the reason a value of it cannot be shown.
  A form and a declaration are named in the vocabulary of the answer; in `fixpunt` the canonical name stands beside a
  name that differs (`objecttypeCanoniek`), as in the playground, whose trace and coverage name a form without it. A
  422 gets the asked fields beside its faults, so the faults stand as data in `fixpunt.fouten` next to the values. The
  fields show every intermediate value, where the answer gives the selected `uitvoer` alone, so a service started
  without `DETAIL` answers each flag with a 400 `NIET_ONDERSTEUND` whose `veld` is the flag's name, before the body is
  read. Without a flag the answer is byte for byte that of a service without `DETAIL`. The core builds the fields
  (`BeslisserKern.verwerk` with the flags of `VerzoekOpties`), so the Quarkus adapter answers the same, and the
  playground and the service render a fixpoint with one class (`FixpuntGegevens`).
- `GET /info`: per entry point whether it is `uitvoerbaar`, its `dekking` (the exec-model branch's verdict per form
  of its document, defaults groups included), its markers and, when uchar does not carry it, uchar's sentence as
  `weigering`; the parameter defaults, the loaded parameter sets and the period-coverage warnings. An entry point
  with a default vocabulary names it (`vocabulaire`), and a model with vocabularies adds `vocabulaires`: per
  vocabulary `naam`, `standaard`, `taal`, whether it is `volledig`, and `zonderBenaming`, the declarations
  (`{soort, eigenaar?, naam}`) an answer in it names canonically. uchar's WF-119 makes only the default vocabulary
  complete.
- The OpenAPI contract (`OpenApiEmitter`) is written per vocabulary: `emit(descriptor, model, vocabulaire)` names the
  object types, properties, enumeration values and selectors in that vocabulary (null or `canoniek`: canonically). For
  a model with vocabularies every object type, property and entry point lists its other spellings in `x-spellingen`,
  every enumeration its values' in `x-waardespellingen`, the descriptions say the service accepts them, the
  operations document `vocabulaire` and `invoervocabulaire`, and `info.x-vocabulaire` names the document's
  vocabulary. A model without vocabularies gets none of it. Every operation documents the flags `uitleg`, `trace`,
  `dekking` and `fixpunt`; `Antwoord` has the fields they add, `Evaluatiefout` those of the detail, with the
  components `Trace`, `Dekking` and `Fixpunt`. The 422 is one of `Evaluatiefout` and `GeenFixpunt`. The documents are written by the emitter's API
  (`schrijf`, file `<dienst>.<vocabulaire>.json`); the service serves no route for them.
- What uchar does not carry — a refusal of its emitter, loader or backend (a construct the exec-model branch does
  not lower among them), or a scoped rule name that yields no form in uchar's model — makes the entry point a
  marker. Strict, the service refuses to start (exit 3, `MARKER: …` with uchar's sentence). With `--allow-markers`
  (`ALLOW_MARKERS=true` for Quarkus) it starts, and every request to that entry point gets 503 with
  `{"status": "MARKER", "fout": …, "reden": <uchar's sentence>, "markers": […]}`: an answer without the construct
  would be an answer to another rule set.
- `PLAYGROUND=true` binds the dev-only workbench (`GET /playground`, `POST /playground/analyse`). A source with a
  parse or resolver error gets its diagnostics and nothing else. A clean source gets the `uchar-ir-v1` document, the
  exec-model verdict (`uitvoerbaar` with the `dekking` and `gegenereerd`, or uchar's `weigering`), the DRL of the
  DRL-text branch as a view (not the verdict), uchar's rule graph of the document its loader reads back, as Mermaid
  (`graaf`) and as `graafJson` (`{nodes:[{id,label,kind,eindOutput}], edges:[{van,naar}]}`), the `vocabulaires` of the
  source (the declared ones, the chosen one, and per declaration its designation in each, with its source line `regel`
  and the `commentaar` directly above it, read from uchar's parse; the list `elementen` is always filled, also without
  a declared vocabulary, and a declaration uchar's frontend wrote itself has no line; `declaratieFout` names an
  element that does not couple to one declaration). An element uchar's frontend made itself, under a name no
  declaration carries (`berekende maxdatum`), has instead `gegenereerdUit: {soort, eigenaar, naam, vorm}`: the element
  it comes from, canonical like an element's own `soort`, `eigenaar` and `canoniek`, and uchar's fixed key of the form
  that made it (`opgave-boven-berekening`, `uitzondering-voor-subtype`, `beslistabelrij`, `stappen-na-de-verzameling`,
  `startpunt`). Beside the `dekking`, `gegenereerd` lists each name of it that is no element (an effective defaults
  stack `defaults A:<type>.<member>` or `defaults K:<type>.<member>`, a version `<form> #<k>`) as `{naam, soort,
  gegenereerdUit}`, with the key `defaultsstapel` or `versie`; `naam` is spelled as the coverage and the trace of a
  scenario spell it, with `naamCanoniek` beside it where that differs, so every name of the coverage and the trace is
  found by its exact text among the elements or in `gegenereerd`. Nothing is read off a name: both come from uchar's
  tables (`RegelSpraakModel.gegenereerd`, `TmsTranspiler.gegenereerd`). Per scenario it gets uchar's report with its
  `trace` and `uitleg`. Beside the report stand the source line of the scenario and of each expectation (`regel`,
  `regeltekst`: uchar gives the line an expectation begins on), the `situatie` uchar's scenario resolver makes (each
  role in `objecten[].rollen[]` with its `feittype`, the fact type the resolver found the role in), and the `fixpunt`
  of the case's own session as uchar reads it (`DroolsBackend.lees`): per object every value and characteristic with
  its origin, the made objects, the links, the evaluation faults as data and the inconsistencies, each list sorted, or
  `fixpuntFout` with the reason there is none. `?vocabulaire=<naam>` names `graafJson`, the trace, the per-scenario
  coverage, the uitleg, the situation and the fixpoint in a vocabulary of the source, the canonical name beside a name
  that differs (the Mermaid graph, the IR and the verdict stay uchar's); an unknown one is a 400.
- `POST /playground/analyse?via=dienst` gives the same answer, except that the service the process runs computes the
  scenarios, so that a PASS says: this service gives this answer. It shows the session of each scenario, so a service
  started without `DETAIL` answers it with a 400 that names `DETAIL` (`veld` `via`), and another value of `via` is a
  400. The service computes with its own sources: the `uchar-ir-v1` document uchar's emitter writes of the posted
  source, without its scenarios, must equal the service's (`BeslisserKern.document`: the document it was given, or the
  one its `.rgs` sources make, in the order the service reads them), as JSON trees, the source lines uchar's document
  carries included; otherwise the whole request is a 400 `NIET_ONDERSTEUND` on `veld` `bron` that names the first
  difference as a JSON pointer. A source with a parse or resolver error gets its diagnostics, as without the flag. Per
  scenario:
  - its scope is mapped to an entry point of the descriptor (`BeslisserKern.padVoorScope`): one whose scope carries the
    forms of the scenario's scope, or every form of the model for a scenario without a scope; an exact match first, else
    the entry point with the fewest forms beside them, the first in the descriptor's order on a tie. The result names
    it as `pad`, after `regel`. Without one the result is a MARKER whose `diagnose` says what each entry point lacks,
    with `pad` null; there is no fallback to the ordinary route;
  - the situation becomes a request (`SituatieNaarVerzoek`, each instance with its label as its id, the names
    canonical, read with `invoervocabulaire=canoniek`), and the core answers it with `uitleg`, `trace`, `dekking` and
    `fixpunt`, in the vocabulary of `?vocabulaire=` (canonical without it). uchar compares the `expected` of the case
    its frontend writes for the scenario with the written-back session of that request, the ids of the request as
    labels (`BeslisserKern.beoordeel`, `DroolsBackend.vergelijk`): PASS or FAIL, the `verwachtingen` and the `diagnose`
    as uchar's report gives them, prefixed with the name of the case file as the report prefixes it;
  - an evaluation fault (the service's 422) is a FAIL with no `verwachtingen` and the line of uchar's report,
    `<case file>: ERROR <exception>: <sentence>`, the exception uchar's write-back throws for the faults; `trace`,
    `dekking` and `fixpunt` are those of the 422, and `uitlegFout` says there is no `uitleg`;
  - a session without a fixpoint (the service's 422 `GEEN_FIXPUNT`) is a FAIL with no `verwachtingen` and the line
    uchar's report gives such a case, `<case file>: <sentence>`; `trace`, `dekking` and `fixpuntFout` are those of the
    422, and `uitlegFout` says there is no `uitleg`. The other scenarios of the document are computed as usual;
  - a situation a request cannot carry (a timeline, a characteristic with periods, a fraction without a decimal form),
    a 400 of the service whose violations are all `NIET_ONDERSTEUND`, an entry point uchar does not carry and an
    expectation uchar does not compare (`CaseRunner.Excluded`) are a MARKER with the sentence; any other 400 of the
    service is a FAIL naming its violations.
  The entry point fires only the forms of its scope and uses the parameters of the descriptor beside those the
  situation sets, so a verdict can differ from the ordinary route's, which fires the forms of the scenario's own scope.

**peildatum-contract (multi-vintage deploys).** When the descriptor names a parameterset sidecar whose values
carry more than one vintage, the service selects each parameter's vintage against ONE date authority: the request
`peildatum`, which *is* the rekendatum global. A request without `peildatum` gets `400 "peildatum verplicht"`, a
peildatum/rekendatum clash gets 400, and `/info` lists the loaded validity ranges plus `periodeWaarschuwingen`. The
advisory `scripts/peildatum-vintage-check.sh <parametersets.json> <true|false>` WARNs (never blocks) on a
declared/actual mismatch.

## Deploying (container + Helm + kind) — host only

`make image`, `docker/`, `helm/beslisservice/` and `make kind-smoke` package the service (README, "The
container"). The image is built from the reactor and starts `bin/regelspraak serve` (`docker/entrypoint.sh`); its
contract is the environment: `DESCRIPTOR`, `BRONNEN_MANIFEST` (a file listing the `.rgs` sources in order), `POORT`,
`ALLOW_MARKERS`.
