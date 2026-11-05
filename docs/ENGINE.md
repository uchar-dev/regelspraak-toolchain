# The engine facade and the decision service

Everything here fires through uchar: a RegelSpraak source is resolved by uchar's frontend, emitted as a
`uchar-ir-v1` document, and built by uchar's Drools backend on its exec-model branch
(`DroolsBackend.build(document, branch)`, called from `UcharBuild`). That build generates the fact beans from the
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

A session fills, fires and reads through uchar's entry (`DroolsBackend.insert`, `fire`, `fixpointOf` and
`setDerivedValues`; uchar's `backends/drools/README.md`, "The entry for consumers"). `insert` and `insertAll`
gather the beans until the first `fire()`, which puts them in and fires to the fixpoint; a later `fire()` only fires.
Two statements go with the beans, before the first `fire()` as well. A consumer that sets an attribute on a timeline as
the one segment of the period of the peildatum states it with `bucketScalar(bean, attribute)`: without it the branch
reads a full timeline of one segment, and the subject's own total faults with an `InputFormFault`, while a form that
needs the full timeline computes over that segment without a fault. `list(holder, role, members)` gives the order of a
to-many list, which decides only which member a fault of a fold names; without it a list keeps the order of the
inserts.
The exec-model branch derives with truth maintenance, so a derived value is a fact of the session and the beans hold
the input alone until `restore()`: `read()` gives what stands at the fixpoint of the last fire (uchar's `Fixpoint`: the
derived values and characteristics, the objects and links the creations made, the evaluation faults as data, the
inconsistencies) without writing anything, and `restore()` puts it on the beans, after which the session does not
fire again. Between two fires a consumer changes an attribute or a characteristic of a bean of the session and calls
`update(bean)`; an object that comes or goes, a changed relation or another global asks a fresh session, and
`setGlobal` belongs before the first `fire()`. uchar's entry refuses each of these by name. A model with an object or
a fact creation fires once per session: uchar refuses a second `fire()` there, so a change asks a fresh session. In a
session that may fire again, a forgotten `update` uchar's entry does not refuse: the entry does not see an attribute
or a characteristic set without one, so the next `fire()` fires nothing, and `read()` and `restore()` give the old
fixpoint (uchar's `backends/drools/README.md`, "What a consumer can forget"). `fire()` returns the number of firings
and throws with uchar's sentence when a run reaches no fixpoint;
`restore()` throws every evaluation fault that stands at the fixpoint as uchar's own exception, two or more as
`EvaluationFaults`: one of the five types uchar's README names (`Breuk.DelingDoorNul`, `ComputeFault`,
`WritersConflict`, `InputFormFault`, `WriteBoundaryFault`) or another, such as the `IllegalStateException` of a fact
creation that gives a to-one role a second filling. A fault is an evaluation fault by its place at the fixpoint, not
by its type: `read().faults()` gives each as data, which tells it from an exception of the entry itself. `facts(…)`
reads the working memory, the `Inconsistentie` facts among them.

The facts are instances of the generated beans (`nieuw`, `beanLoader()`), in uchar's package
`dev.uchar.regelspraak.facts`: the KieBase is built against those classes, so an object of a same-named class from
another class loader never matches a rule. `newSession(asOfDate)` sets the `rekendatum` global;
`newSession(asOfDate, parameterSets)` also selects each parameter's vintage on that one date. A time-dependent
parameter (`voor elke maand`) is no vintage: uchar's branch reads it whole, from a global of type `List<Period>`
(`Period.OPEN_FROM`, `Period.OPEN_TO` for an open bound), which `Session.setGlobal` takes, and the parameter-set
loader refuses one by name. A global the rule base declares takes its value or the session fails with Drools'
sentence; one it does not declare is not set. `compile` throws
`RegelSpraakCompileException` for resolver errors and for a model uchar refuses. `bin/regelspraak facts <outdir>
<source.rgs...>` (or `make facts` for the TOKA casus) writes the beans a model implies as source, for a consumer that
wants to see them.

## Building

```
make verify          # check-pin, then mvn -B -o verify -Dmaven.test.skip.exec=true over the whole reactor
mvn -B -o verify -Dmaven.test.skip.exec=true -pl toolchain -am   # only the toolchain core and what it needs from uchar
```

`RegelSpraakEngineTest` is the end-to-end proof: a rule compiles, fires over generated beans through uchar's entry
and writes the derived values back onto them; a session that fires twice with an update in between reads what a fresh
session reads; a bucket scalar and the order of a list reach uchar's entry with the first fire, and the session then
gives what uchar's replay gives on the same case; a type error and a construct uchar does not lower both surface as
`RegelSpraakCompileException`.

## The decision service

`service/` (`ServiceMain`, the JDK `HttpServer`) and `service-quarkus/` (the Quarkus adapter) serve a JSON
descriptor over the same core (`DecisionCore`). Its source is a set of `.rgs` files, resolved together into one
model, or one `uchar-ir-v1` document (a `.json` file) that uchar's loader reads. Per entry point the core keeps the
forms its scope names — the rules, decision-table rows, defaults groups and other forms of its rule names and rule
groups (`FormIndex`, then `CaseEmitter.withScope`, the lowering of uchar's scenario layer) — and builds that document
through uchar, so each entry point has its own KieBase and bean class loader and a form outside its scope does not
decide whether it runs. A defaults group without a base of its own, which uchar makes of the own exceptions of a
subtype (`<basis> voor <subtype>`), goes with the groups of the base it inherits, since RUNTIME-SEMANTICS ch. 7.10
composes one stack per concrete type from them: a scope that names either carries all of them, as uchar's
scenario layer does as well since uchar `2a0c6d60` (`ScenarioResolver`). A `uchar-ir-v1` document is served whole: a scope drops forms by rebuilding the model it
came from, which a document without its source does not have, so a scope that does not carry every form refuses the
start.

At the start the core also checks that each scope uchar carries is complete. It reads the forms of the entry point's
KieBase as uchar's rule graph reads them (`ProducerConsumer.forms`): of a rule its condition and value, of a defaults
group the justification and consequence of every default, nested exceptions included, over roles as well. An attribute
or characteristic a rule or a defaults group of the scope reads, which forms outside the scope write and none inside,
refuses the start (`INCOMPLETE_SCOPE`, exit 1). The violation names the reader — a rule, the group of the rule a tree
of exceptions hangs on (or of a member of an `Uitzonderingen ongeordend` block), or a group uchar generates by its
name (`opgave boven berekening X` for the opgave marker, `X voor S` for the own exceptions of a subtype S) — the
element and its writers. For a group it also names each default that reads the element, from uchar's walk per default
(`ProducerConsumer.readings`): the part that reads with its source text and the default's path in the group, as in
"in de rechtvaardiging 'zijn bergdag later is dan 01-06-2026' van default [0]" ([0] the highest default, [i, g, j] the
j-th default of exception group g of default i). An element no form writes is request input. A cell of a dimensioned
attribute is an element of its own, as uchar's rule graph keys it (`vondstgewicht[najaar]`, `ProducerConsumer.cellOf`).
Two opt-outs per entry point: `inputElements`
declares an element the caller supplies, a dimensioned attribute each of its cells, and `scopeCheckOptOut: true`
waives the check (the replay gate sets it). The
other forms a scope can carry (object creations, allocations, consistencies, uniquenesses, fact creations) are not
checked.

```
bin/regelspraak serve <descriptor.service.json> <source.rgs…> | <document.json> [--allow-markers] [--detail] [--host H]
                      [--port N]
```

- `POST /<path>` per entry point; `?explanation=true` adds the verbaliser's sentences to the answer. An entry point that
  explains (the descriptor's `explanation`, absent or true) has, beside the build that answers, uchar's build with
  readings of the same document, made at startup; a request with `?explanation=true` is mapped, fired and answered there,
  once, and the verbaliser renders the engine's readings of that session (`DroolsBackend.readingsOf`); uchar's witnesses
  hold that the facts at its fixpoint are those of the build without. With `"explanation": false` the entry point builds
  the one build and refuses a request for an explanation (400, `UNSUPPORTED` on `explanation`,
  `DecisionCore.withoutExplanation`). The sentence of a defaults group names the default that decided from uchar's
  `DroolsBackend.decisionsOf`, one per object and stack; a stack on a time-dependent attribute decides per day (uchar's
  RUNTIME-SEMANTICS ch. 7.11), and its sentences name per period who decided from uchar's
  `DroolsBackend.periodDecisionsOf` ("A defaults group per day", below), read off the same session.
  `?explanationParts=true` asks the explanation as well and adds `explanationParts` right after `explanation`: per
  sentence, in the same order, the array of its parts ("The parts of an explanation", below); the texts of the leaves
  after each other are the sentence, and without the flag the answer is byte for byte what it was. The service makes no
  bean itself: `RequestMapper` validates the request (names, types, units; a 400 with the field path) and writes it as
  the input of a `uchar-case-v0` case, `{"asOfDate", "input": {"instances": [...]}}` with the ids of the request as
  labels, the inline `roles` and each relation of `relations` as `roles` (a relation on the end whose role toward the
  other is to-one). uchar's input step reads that case (`CaseInput.of`), the one its replay reads every case with:
  the beans, the role by its written name, the far field of a one-to-one relation, a to-many role through the far
  end, the reverse leg of a reciprocal role (RUNTIME-SEMANTICS ch. 5.7a), and each to-many list in the replay's order
  (`CaseInput.inputData()`), which decides which member a fault of a fold names, so the 422 names the member uchar's
  replay names on the same input. A link uchar refuses names the link the case wrote, and the mapper gives the field
  of the request that wrote it (for a relation, the role that names the target of its link, also where the collision
  is on the far field): a to-one role that gets a second, different target (WF-013, `CaseInput.RoleCollision`),
  among the inline roles, through `relations` or through the far field, is a 400 `WRONG_TYPE`, a link the bean
  model cannot hold (`CaseInput.RoleRefusal`) a 400 `UNSUPPORTED`, each with uchar's sentence. A date or
  date-time whose year the case lexeme does not carry (beyond 0000–9999, `+10000-01-01`), uchar's input step refuses
  as well: a 400 `WRONG_TYPE` on the field that holds it (`asOfDate`, `rekendatum` or the attribute), with uchar's
  sentence. An enumeration value names a declared value as uchar compares declared names (case and spacing aside),
  and keeps its own spelling.
- Names and vocabularies (README, "Vocabularies"). `NameReader` reads every name of a request canonically or as a
  designation in any vocabulary of the model (uchar's `RegelSpraakModel.namedElement`), within the scope of its kind,
  case-insensitively and mixed; a name that reads as two declarations is a 400 `AMBIGUOUS_NAME` naming both
  readings, and `?inputVocabulary=<name>` (or `canonical`) pins the vocabulary names are read in. A designation is
  read as the declaration's canonical name, an enumeration value as the declared value it names. Two names of one
  object that read as one declaration are a 400 `MALFORMED`. `?vocabulary=<name>` chooses the names of the answer,
  its `inconsistencies` and its `explanation` (`Naming`), else the entry point's `vocabulary` from the descriptor,
  else the canonical names; an unknown vocabulary is a 400 `UNKNOWN_VOCABULARY`, and a descriptor naming one does
  not start. A declaration without a designation in the chosen vocabulary keeps its canonical name. The Quarkus
  adapter reads the same query parameters.
- Each request gets a fresh session that the core fills, fires and reads through uchar's entry (`DroolsBackend.insert`,
  `fire`, `fixpointOf`, `setDerivedValues`), so the answer carries the values the rules derive. The answer lists per
  instance the selected attributes and characteristics, `inconsistencies` (the rule names of the failed consistency
  checks) and `inconsistencyDetails` (the same, each with its `subject`: the request id of the instance the check
  is about). A 400 names per violation its `field`, its `kind` and its message; `kind` `UNSUPPORTED` marks a
  request form the service does not carry (a scalar on a time-dependent attribute, below), an explanation the entry
  point does not give, and a detail flag of a service started without `DETAIL` (below).
- A dimensioned attribute (uchar's RUNTIME-SEMANTICS ch. 5.8c) has a value per cell and none of its own, so a request
  gives it as its cells, in the form of uchar's case: `"vondstgewicht": {"cells": [{"labels": ["voorjaar"], "value":
  1.50}, …]}`, one label per dimension in their declared order, each value as the attribute's datatype takes it, null
  for an empty cell. A label compares as a name (case and spacing aside). The mapper writes the cells into the case as
  they are, and uchar's input step reads them. The answer gives a dimensioned attribute of `output` as its cells too,
  every cell of the attribute, the first dimension outermost and each in the declared order of its labels, each label
  in its declared spelling, null for an empty cell: `"vondstgewicht": {"cells": [{"labels": ["voorjaar"], "value":
  1.50}, {"labels": ["zomer"], "value": null}, {"labels": ["najaar"], "value": 3.00}]}`. A scalar on a dimensioned
  attribute and `cells` on an attribute without dimensions are a 400 `WRONG_TYPE`, a cell without `labels` or `value`
  a 400 `MALFORMED`. Labels that name no cell of the attribute (EF-034) and a cell given twice (EF-035) uchar's input
  step refuses before any session: the 422 below, kind `CELL_FAULT`; a value its declaration does not hold is EF-004,
  `INPUT_PRECISION_FAULT`, with the cell as its place (`J1.vondstgewicht[voorjaar]`).
- A time-dependent parameter (`voor elke maand`, uchar's RUNTIME-SEMANTICS ch. 3.4, 8.3) has a value per day, which
  uchar's Drools branch reads whole, from a global of type `List<Period>`. The service sets the globals itself, so a
  request and a descriptor default give such a parameter its timeline, in the form of uchar's case: `"havenheffing":
  {"timeline": [{"to": "2024-01-01", "value": 2.50}, {"from": "2024-01-01", "value": 3.00}]}`, each value as the
  parameter's datatype takes it. A segment without `from` begins before every day and one without `to` runs past every
  day (an open bound, ch. 3.1), so only the first may leave out `from` and only the last `to`; a day no segment covers
  is a gap. The checks are those of uchar's case reader (`TimelineInput`): `from` before `to`, the segments in
  order and apart, each bound a wall of the parameter's grid (uchar's `FixedStartPoint.opWand`); a violation is a 400
  `MALFORMED` or `WRONG_TYPE` on the field of the segment, the calendar's week and quarter grid a 400 `UNSUPPORTED`,
  and a value with more decimals than declared EF-004 (below). A scalar on such a parameter and a timeline on another
  are a 400 `WRONG_TYPE`. A parameter set (`parameterSets`) gives the vintage an `asOfDate` chooses, so it may not name
  a time-dependent parameter: the service does not start.
- A time-dependent attribute (`voor elke maand`) takes its timeline in the same form, `"strandloon": {"timeline":
  [{"from": "2025-01-01", "to": "2026-01-01", "value": 99}]}`, with the same checks on the attribute's grid, and
  `RequestMapper` writes it into the case as uchar's case gives it, `{"timeline": [{"from", "to", "value": {"number":
  …}}]}`, which uchar's input step reads (`CaseInput.of`); a segment value its declaration does not hold is EF-004 per
  segment (below). A time-dependent characteristic takes the periods in which it holds, `"stormwacht": {"periods":
  [{"from": "2025-10-01", "to": "2026-01-01"}]}`, both bounds required and day-precise, the periods apart (a violation
  is a 400 `MALFORMED` or `WRONG_TYPE` on its field), which the mapper writes as the `characteristicTimelines` of the
  case. On a timeline target every writer decides the days on which its timeline reads a value, and the input stands
  on the other days, also beside a defaults group, which decides per day (uchar's RUNTIME-SEMANTICS ch. 3.11, 7.11):
  with the opgave marker on `strandloon`, an input of 99 on 2025 and an opgave of 10 in January and February, a rule
  writing 20 from March to May gives `10, 20, 99`. An initialisation decides the days the writers and the input leave
  empty, over all time, and on an object a creation made the member value the creation gave it stands in the place of
  the input, a scalar on every day or a timeline per period (the stack of ch. 7.8 per day). A boolean on a time-dependent characteristic, periods on another and
  a timeline on an attribute without one are a 400 `WRONG_TYPE`. A scalar on a time-dependent attribute is a 400
  `UNSUPPORTED`: uchar's case reads it as the value of the period of the evaluation date (the asOfDate-bucket
  convention, ch. 8.4), a convention of its replay that the request does not take over. A grid of more than one period
  or a timeline with a variable start uchar's case reader does not carry, and neither does the request (400
  `UNSUPPORTED`).
- An attribute on a timeline in `output` is answered as its segments, in the same form, as uchar's write-back put them
  on the bean (`"bootheffing": {"timeline": [{"to": "2024-01-01", "value": 5.00}, {"from": "2024-01-01", "value":
  6.00}]}`), an open bound left out, and null where no rule wrote one and the input gave none. A time-dependent
  characteristic in `output` refuses the start (`UNSUPPORTED`): the answer gives a characteristic as a boolean, and
  the bean holds its true periods.
- An attribute with absence markers (`(of onbekend)`, `(of niet van toepassing)` or `(of onbekend of niet van
  toepassing)`, uchar's RUNTIME-SEMANTICS ch. 9.5) holds uchar's sum type: the constructor `waarde` with the declared
  datatype as its payload, and a constructor without payload per marker; uchar's branch puts a `ConstructorValue` on the
  bean (`Markers` reads the markers off the model of a source, where they stand beside the attribute, and off the model
  of an IR document, where the attribute is that sum type). A request gives such an attribute a value of its datatype,
  which is the value `waarde(…)`, or a marker, `"taxatie": {"marker": "onbekend"}`, also as the `value` of a segment
  of its timeline and of a cell of a dimensioned attribute with markers (`"gewicht": {"cells": [{"labels": ["najaar"],
  "value": {"marker": "onbekend"}}]}`), and `RequestMapper` writes them into the case as uchar's case gives them,
  `{"constructor": "waarde", "payload": {"number": "40.00"}}` and `{"constructor": "onbekend"}`; a marker name compares
  as a name (case and spacing aside) and goes in its declared spelling. A marker the attribute does not declare is a
  400 `UNKNOWN_MARKER` that names the declared ones, a marker on an attribute without markers a 400 `WRONG_TYPE`, and a
  marker object with another key a 400 `MALFORMED`. The answer gives the attribute in the same form, the value as its
  payload and a marker as `{"marker": "onbekend"}`, also per segment (`"opslagkosten": {"timeline": [{"from":
  "2025-01-01", "to": "2025-03-01", "value": {"marker": "onbekend"}}, {"from": "2025-03-01", "to": "2025-06-01",
  "value": 10}, …]}`) and per cell, and so does the `fixpoint` of the detail. A marker decides as a value does (uchar's
  ch. 9.5): it displaces an input, and a writer that writes a marker and one that writes a value on the same day are a
  `WRITERS_CONFLICT`. An attribute of another sum type, which only an IR document of another producer declares, has no
  form in the service: a value on it is a 400 `UNSUPPORTED`, and in `output` it refuses the start (`UNSUPPORTED`).
- A request on which the rules give an evaluation fault that stands at the fixpoint gets 422 with
  `{"status": "EVALUATION_FAULT", "error": …, "faults": [{"kind": …, "error": <uchar's sentence>}, …]}`, one element per
  fault in uchar's order. Every fault at the fixpoint is such an element, whatever its type: the core reads them from
  uchar's entry (`DroolsBackend.fixpointOf`) before the write-back, so an exception of the entry itself stays a 500.
  `kind` is `DIVISION_BY_ZERO` (a division by zero that uchar's runtime itself raises; a division by a numeric zero in
  the rules gives an empty value and a §9.5 fact EF-005, so a 200 with the rule among the `inconsistencies`),
  `COMPUTE_FAULT` (a fault of a computation or of the
  evaluation date), `WRITERS_CONFLICT` (two writers of one attribute that disagree), `INPUT_FORM_FAULT` (a timeline of
  the request where a rule reads the value in the period of the evaluation date, such as the subject's own total),
  `WRITE_BOUNDARY_FAULT` (a value that does not fit the declared decimals of its target, EF-006),
  `INPUT_PRECISION_FAULT` (below), `CELL_FAULT` (below), `OPEN_SEGMENT_FAULT` (a temporal total over a segment with an
  open bound that is not 0, EF-036, uchar's `OpenSegmentFault`), `CHARACTERISTIC_PERIODS_FAULT` (the periods in which
  a time-dependent characteristic holds have an open bound, EF-023, uchar's `CharacteristicPeriodsFault`) or `OTHER`
  (a fault of another type, such as a fact creation that gives a to-one role a second filling). Where uchar's sentence names
  an object, it names an object of the request by its id; a sentence about a kind of object (the second filling: "a
  Jutter") names none. No stacktrace enters the answer. The Quarkus adapter answers the same, and the
  OpenAPI contract describes the body of the 422 (schema `EvaluationFault`, with `kind` as an enumeration of these
  kinds).
- A request that validates but holds a number its declaration does not hold, an attribute or a parameter with more
  decimals than declared, gets the same 422 with the kind `INPUT_PRECISION_FAULT` and uchar's sentence of EF-004:
  uchar's input fence (`CaseRunner.inputFits`) refuses the value before any session, as uchar's replay refuses it in a
  case. An attribute reaches the fence in uchar's input step, which stops at the first; the core holds every parameter
  to it, since it binds the parameters itself. No session runs, so the 422 carries no detail, also when the request
  asks for it. A parameter default of the descriptor that does not fit is refused when the descriptor loads, with a
  violation on `parameters.<name>` that carries uchar's sentence, so the service does not start.
- The cells of a dimensioned attribute whose labels name no cell of it (EF-034) or that give one cell twice, also in
  two spellings of a label (EF-035), get the same 422 with the kind `CELL_FAULT` and uchar's sentence; uchar's input
  step refuses them before the values of the cells and stops at the first. The mapper tells that refusal by uchar's
  type of it (`CaseInput.CellFault`) and takes its sentence; an `IllegalArgumentException` of uchar's input step that
  the mapper did not foresee stays a 500.
- A request whose session reaches no fixpoint gets 422 with `{"status": "NO_FIXPOINT", "error": <uchar's
  sentence>}`: uchar's `DroolsBackend.fire` ended the run at its fire cap or at the creation budget
  (`Firing.noFixpoint`), and the core throws `NoFixpoint` with that sentence. Nothing stands to be read then, so
  nothing is read or written back; the service does not judge which of the two it was. With the detail flags (below)
  the 422 carries `trace` and `coverage` of what fired up to the end of the run, and for `?fixpoint=true` `fixpointError`
  with the same sentence. The Quarkus adapter answers the same; the OpenAPI 422 is one of `EvaluationFault` and
  `NoFixpoint`. Any other exception stays a 500 `interne fout`.
- A request on which uchar's Drools branch reaches a limit at the fixpoint, a value of the rules it cannot hold (a
  segment of a timeline of the unbounded form whose value does not terminate, DROOLS-312, a `LIMIT` of uchar's
  register), gets 422 with `{"status": "LIMIT", "error": …, "code": "DROOLS-312", "reason": <uchar's sentence>}`. It is
  no fault of the rules or of the input: uchar's write-back refuses such a session as a `CaseRunner.Excluded` with its
  register code, before the faults beside it, and its replay excludes the case; the core throws `Limit`, and nothing is
  written back. With the detail flags the 422 carries `trace`, `coverage` and `fixpoint`, the limit among its faults.
  The Quarkus adapter answers the same; the OpenAPI 422 is one of `EvaluationFault`, `NoFixpoint` and `Limit`.
- The detail of a session. A service started with `--detail` or `DETAIL=true` (Quarkus: `DETAIL=true`; default off,
  as `PLAYGROUND`, and no part of the container contract) answers three more query flags, read as `explanation` is (`true`
  or bare), each adding a field after `explanation`: `?trace=true` gives `trace: [{rule, labels, count}]`, per form that
  fired, in the order a rule of it first fired, the request ids its matches bound and how often its rules fired, as
  uchar's `DroolsBackend.Observer` records them on the request's session; `?coverage=true` gives `coverage: [{rule,
  kind}]`, per form of the entry point's build `FIRED` or `NOT_FIRED` (uchar's `DroolsBackend.coverage` over the
  forms the observer saw); `?fixpoint=true` gives `fixpoint: {objects, madeObjects, links, faults,
  inconsistencies}` in the form of the playground's (below), with the instances of the request as its input: an
  instance by its id, its input values read off its bean before the fire, a made object `#<n>`, a type by its name;
  or `fixpointError` with the reason a value of it cannot be shown. A dimensioned attribute has a row per cell, with
  the cell's `labels` after `attribute`, in their declared spelling, as uchar's `Fixpoint.DerivedValue.labels` names a
  derived cell, the cells in the declared order of their labels.
  A form and a declaration are named in the vocabulary of the answer; in `fixpoint` the canonical name stands beside a
  name that differs (`objectTypeCanonical`), as in the playground, whose trace and coverage name a form without it. A
  422 gets the asked fields beside its faults, so the faults stand as data in `fixpoint.faults` next to the values. The
  fields show every intermediate value, where the answer gives the selected `output` alone, so a service started
  without `DETAIL` answers each flag with a 400 `UNSUPPORTED` whose `field` is the flag's name, before the body is
  read. Without a flag the answer is byte for byte that of a service without `DETAIL`. The core builds the fields
  (`DecisionCore.process` with the flags of `RequestOptions`), so the Quarkus adapter answers the same, and the
  playground and the service render a fixpoint with one class (`FixpointGivens`).
- `GET /info`: per entry point whether it is `executable`, its `coverage` (the exec-model branch's verdict per form
  of its document, defaults groups included), its markers and, when uchar does not carry it, uchar's sentence as
  `refusal`; the parameter defaults, the loaded parameter sets and the period-coverage warnings. An entry point
  with a default vocabulary names it (`vocabulary`), and a model with vocabularies adds `vocabularies`: per
  vocabulary `name`, `default`, `language`, whether it is `complete`, and `withoutDesignation`, the declarations
  (`{kind, owner?, name}`) an answer in it names canonically. uchar's WF-119 makes only the default vocabulary
  complete.
- The OpenAPI contract (`OpenApiEmitter`) is written per vocabulary: `emit(descriptor, model, vocabulary)` names the
  object types, properties, enumeration values and selectors in that vocabulary (null or `canonical`: canonically). For
  a model with vocabularies every object type, property and entry point lists its other spellings in `x-spellings`,
  every enumeration its values' in `x-valueSpellings`, the descriptions say the service accepts them, the
  operations document `vocabulary` and `inputVocabulary`, and `info.x-vocabulary` names the document's
  vocabulary. A model without vocabularies gets none of it. The value of an attribute with absence markers, also of a
  segment or a cell, is `oneOf` the schema of its datatype and the marker object, `{"type": "object", "required":
  ["marker"], "additionalProperties": false, "properties": {"marker": {"type": "string", "enum": ["onbekend", "niet van
  toepassing"]}}}`, the `enum` the markers the attribute declares. Every operation documents the flags `explanation`,
  `explanationParts`, `trace`, `coverage` and `fixpoint`; `Answer` has the fields they add, `EvaluationFault` those of
  the detail, with the components `ExplanationPart` (recursive through `parts`), `Trace`, `Coverage` and `Fixpoint`. The 422 is one of `EvaluationFault` and `NoFixpoint`. The documents are written by the emitter's API
  (`write`, file `<service>.<vocabulary>.json`); the service serves no route for them.
- What uchar does not carry — a refusal of its emitter, loader or backend (a construct the exec-model branch does
  not lower among them), or a scoped rule name that yields no form in uchar's model — makes the entry point a
  marker. Strict, the service refuses to start (exit 3, `MARKER: …` with uchar's sentence). With `--allow-markers`
  (`ALLOW_MARKERS=true` for Quarkus) it starts, and every request to that entry point gets 503 with
  `{"status": "MARKER", "error": …, "reason": <uchar's sentence>, "markers": […]}`: an answer without the construct
  would be an answer to another rule set. On a timeline target such a construct is the value of an initialisation or
  of a creation's member that is neither period arithmetic nor the constructor of a literal (DROOLS-316: `regel
  'standaardbijdrage' in de scope van entrypoint 'bijdrage' is een marker (niet gedragen): transpiler SKIP: regel:
  standaardbijdrage — de waarde van een initialisatie op het tijdsafhankelijke attribuut 'onderhoudsbijdrage' van
  Bakenmeester is geen rekenen per periode …`). An allocation that names a time-dependent attribute is not
  well-formed (uchar's WF-128): from RegelSpraak the frontend refuses it (RGS-378) and the service does not start with
  that resolver error; a `uchar-ir-v1` document, which the service does not verify, gets as far as uchar's branch,
  which refuses it (DROOLS-315, a `contract` of its register), and the entry point is a marker with that sentence.
- `PLAYGROUND=true` binds the dev-only workbench (`GET /playground`, `POST /playground/analyse`). A source with a
  parse or resolver error gets its diagnostics and nothing else. A clean source gets the `uchar-ir-v1` document, the
  exec-model verdict (`executable` with the `coverage` and `generated`, or uchar's `refusal`), the DRL of the
  DRL-text branch as a view (not the verdict: `drl`, or `drlError` with uchar's sentence and `drl` empty) with
  `drlRules` beside it (below), uchar's rule graph of the document its loader reads back, as Mermaid
  (`graph`) and as `graphJson` (`{nodes:[{id,label,kind,endOutput}], edges:[{from,to}]}`), the `vocabularies` of the
  source (the declared ones, the chosen one, and per declaration its designation in each, with its source line `rule`
  and the `comment` directly above it, read from uchar's parse; the list `elements` is always filled, also without
  a declared vocabulary, and a declaration uchar's frontend wrote itself has no line; `declarationError` names an
  element that does not couple to one declaration). An element uchar's frontend made itself, under a name no
  declaration carries (`berekende maxdatum`), has instead `generatedFrom: {kind, owner, name, form}`: the element
  it comes from, canonical like an element's own `kind`, `owner` and `canonical`, and uchar's fixed key of the form
  that made it (`opgave-boven-berekening`, `uitzondering-voor-subtype`, `beslistabelrij`, `stappen-na-de-verzameling`,
  `startpunt`). Beside the `coverage`, `generated` lists each name of it that is no element (an effective defaults
  stack `defaults A:<type>.<member>` or `defaults K:<type>.<member>`, a version `<form> #<k>`) as `{name, kind,
  generatedFrom}` (a stack as `{name, kind, type, generatedFrom, composition}`), with the key `defaultsstapel` or
  `versie`; `name` is spelled as the coverage and the trace of a
  scenario spell it, with `nameCanonical` beside it where that differs, so every name of the coverage and the trace is
  found by its exact text among the elements or in `generated`. A stack has `type` beside `kind` (card
  `drl-rule-part-fields`): the object type it runs for (uchar's RUNTIME-SEMANTICS ch. 7.10, "the effective stack per
  type"), the type that declares the member of `generatedFrom` or a subtype of it, so two stacks of one inherited
  member (`defaults K:Recht op uitkering WW.…` and `defaults K:RechtOpUitkeringWwBijOntslag.…`, one `generatedFrom`)
  each have their own `type`. It is named in the chosen vocabulary, with `typeCanonical` beside it where that differs;
  a version has no `type`. After `generatedFrom` a stack has `composition`: how uchar composed it
  (`TmsTranspiler.Stack.composition`, RUNTIME-SEMANTICS ch. 7.2, 7.9 and 7.10), as `{unordered, chains}`. `chains`
  are the chains at its root, each its defaults highest first, across the groups the stack is made of; where a type
  contributes its own group or rule over an inherited one, the root is one chain in the block order of ch. 7.10 (its
  own exceptions, the inherited exceptions, the base, the initialisations). A default is named in the keys of the decider
  of a ground, as `drlRules` names one (below, "The decider of a ground"): `group`, `pointer`, `position` and
  `variant`, or `rule` for an ordinary rule a subtype's stack takes in, with `generatedFrom` where uchar's frontend
  made the group or form (a group `B voor S`, the group of the opgave marker). A default with exception groups
  (ch. 7.9) has `exceptions` after those keys, in the same form `{unordered, chains}`, one chain per exception group.
  `unordered` is `true` where more than one chain stands side by side without an order between them (ch. 7.2 at the
  root, ch. 7.9 below a default); a single chain is ordered, also where the document marks it `unordered`. Each
  default of the stack stands in it once. In herleving-ww v2 the stack
  `defaults A:RechtOpUitkeringWwBijOntslag.herlevingstermijn` is one chain: first `{group: "herlevingstermijn art. 21
  lid 3 en 5 voor RechtOpUitkeringWwBijOntslag", pointer: "/defaults/0", position: 1, generatedFrom: …, exceptions:
  {unordered: false, chains: [[…three deviations…]]}}`, then the base of the group `herlevingstermijn art. 21 lid 3
  en 5`. The keys are canonical, like the IR, in every vocabulary. Type and composition come from uchar's analysis of
  the exec-model branch, not from the DRL. Nothing is read off a name (the label `default 0.0.1.0.0` of a rule) or
  off the order of the DRL text: all of it comes from uchar's tables (`RegelSpraakModel.generated`,
  `TmsTranspiler.generated`, `TmsTranspiler.stacks`). Per scenario it gets uchar's report with its
  `trace`, `explanation` and `explanationParts`. Beside the report stand the source line of the scenario and of each expectation (`rule`,
  `lineText`: uchar gives the line an expectation begins on), the `situation` uchar's scenario resolver makes (each
  role in `objects[].roles[]` with its `factType`, the fact type the resolver found the role in; a dimensioned
  attribute in `objects[].attributes[]` as `cells`, `[{labels, value}]`, as a request gives it; a value of an attribute
  with absence markers, which uchar's resolver gives as a constructor value, as a request gives it too, a marker as
  `{"marker": "onbekend"}` under `value` and the value `waarde(…)` as its payload, also in a segment and a cell; the
  page shows a marker by its name), and the `fixpoint`
  of the case's own session as uchar reads it (`DroolsBackend.fixpointOf`): per object every value and characteristic with
  its origin, the made objects, the links, the evaluation faults as data and the inconsistencies, each list sorted, or
  `fixpointError` with the reason there is none. `?vocabulary=<name>` names `graphJson`, the trace, the per-scenario
  coverage, the explanation, the situation and the fixpoint in a vocabulary of the source, the canonical name beside a name
  that differs (the Mermaid graph, the IR and the verdict stay uchar's); an unknown one is a 400.
- `drlRules` (card `drl-rule-provenance`) has one entry per rule of `drl`, in the order of its headers: `name`, the
  rule's name, unescaped (the header `rule "…"` spells it as a DRL string literal, with `\\`, `\"`, `\n` and `\r`), and
  what uchar says the rule lowers (`TmsTranspiler.originOf`), in the keys of the decider of a ground (below, "The
  decider of a ground"), canonical like the IR and the DRL:
  - a rule of a form that is no defaults stack (the form's own rule and every rule its lowering adds, the
    contributions to a fold the form registered first among them): `rule`, the name of the form without a version
    suffix, in whichever list of the rule layer the form stands (`rules.rules`, `consistencies`, `uniquenesses`,
    `allocations`, `objectCreations`, `factCreations`);
  - a rule of an effective defaults stack: `stack`, its name as the coverage and `generated` give it
    (`defaults A:<type>.<member>` on an attribute, `defaults K:<type>.<member>` on a characteristic), and where it lowers one default (the default's own rule, its fall-back, the fault
    rule of its justification, the reconciliation of its exception groups, the contributions to the folds its
    justification or consequence reads) that default, as `group`, `pointer`, `position` and `variant`, or as `rule`
    for an ordinary rule the stack of a subtype takes in (uchar's RUNTIME-SEMANTICS ch. 7.10); a rule of the stack
    itself (the reconciliation of the groups at its root, the fault of a session without an evaluation date) has
    `stack` only;
  - a rule of no form (a standing, a link of the input, the comparison of two writers) has `name` only;
  - `generatedFrom` (`{kind, owner, name, form}`, as in `vocabularies`) where uchar's frontend made the form of `rule`
    or the group of `group`: the rules and the group of the opgave marker, a group `B voor S`, a row of a decision
    table;
  - `role` (card `drl-rule-part-fields`), after `name` on every entry with a form (`rule` or `stack`; a rule of no form
    has none): what the rule does in the lowering of its form, its default or its stack, uchar's fixed key
    (`TmsTranspiler.RuleOrigin.Role.key()`), as uchar's lowering noted it when it built the rule:
    - `own`: the rule of the form itself (the rule that writes or attributes, deposits the inconsistency of a
      consistency, marks a colliding key, distributes, creates), or of a default the rule of its own decision, an
      ordinary rule a subtype's stack takes in among them;
    - `fallback`: the own decision of a default with exception groups, where none of them settles (ch. 7.9);
    - `condition-fault`: the fault of a condition, assertion or justification that can fault;
    - `condition-deposit`: the §9.5 inconsistency of a form's condition (ch. 1.2);
    - `no-evaluation-date`: the fault of a session without an evaluation date, beside a form with a bounded validity or
      for a stack with a dated group (ch. 8.1);
    - `reading`: the reading rule of a build with readings (ch. 5.12); the DRL shown is built without, so it does not
      occur here;
    - `reconcile-pass-on`, `reconcile-difference`, `reconcile-agreement`: the reconciliation of a default's exception
      groups (ch. 7.9) or, on an entry with `stack` only, of the groups at the stack's root (ch. 7.2): pass on the
      first conflict, make a conflict where two members decide differently, take the decision of the first deciding
      member;
    - `fold-contribution`, `fold-filter-fault`, `fold-member-fault`, `fold-filter-deposit`: of a fold (ch. 3.4): insert
      the contribution of a member that passes the filter, note a member whose filter faults (ch. 5.11), a member whose
      timeline the case gives as the asOfDate-bucket scalar (ch. 3.6, 8.4), a member whose filter deposits (ch. 5.10);
    - `uniqueness-key`, `uniqueness-key-form-fault`, `uniqueness-channel`: of a uniqueness (ch. 5.9): insert the key of
      an instance, fault an instance whose key on a timeline is given as a bucket scalar, put the violation on the §9.5
      channel (ch. 5.6);
    - `allocation-recipient`: of an allocation, insert a recipient of a source (ch. 4);
  - `fold` on an entry with a `fold-` role: the node of its fold, an `Aggregation` or a `QuantifierFold`, as a JSON
    Pointer in the group `group` names or else in the form `rule` names (in whichever list of the rule layer it
    stands), the way `pointer` lies in its group: uchar's pointer (`RuleOrigin.fold()`) without `/rules/<list>/<index>`,
    because a scope moves that index and not the name. Under a default it begins with the default's `pointer`
    (`/defaults/2/justification/left`), under a form with the form's own keys (`/versions/0/value`); the text of the
    fold is `source.snippet` of that node;
  - `piece` (card `drl-rule-functions-fields`), the last key of every entry: the piece of `drl` that belongs to the
    rule, as uchar gives it (`TmsTranspiler.drlPieces`), as `{start, ruleStart, end, functions}`. Every test and every
    value of a rule's plan that tests or computes something is a function in the Java dialect that stands in the text
    before the rule, with the RegelSpraak sentence of its node as a comment line above it, and the rule calls it; a
    value that computes nothing, a constant or the value a binding binds, stands in the rule's `then` part itself, with
    its sentence as a comment line above it. The piece is the rule with its functions. `start` is where the piece
    begins: its first function, with the comment of that function (`ruleStart` for a rule without functions);
    `ruleStart` is where the line `rule "…"` begins; `end` is where the piece ends, after the line `end` and the empty
    line after it, which is where the next piece begins, or the end of `drl`. `functions` lists the functions of the
    rule in the order of the text, an empty list for a rule without, each as `{name, start, declaration, end}`: `name`
    as the rule and the other functions of the piece spell it
    (`afloop_defaultsAEindigingsgrondHerlevingsdatumTerugval2`: the kind of the function, a number after it
    where the rule has more than one of that kind, `_` and the name of the rule in one word, so a reader of the text
    or of a stack trace sees whose it is; which functions are a rule's, the piece says, never the name), `start` where
    the function begins, with its comment, `declaration` where the line `function …` begins, `end` after its closing
    brace and the empty line after it, which is where the next function begins, or `ruleStart`. Between `start` and
    `declaration` stands one line, `// `, the RegelSpraak sentence of the function's node (a line break in it as a
    space) and a line break; where the node has no sentence nothing stands there and `start` equals `declaration`. A
    piece holds the calls of its own functions only. The pieces follow one another in the order of the text, from the
    end of its header (package, imports, globals) to the end of `drl`, without a gap or an overlap.

    **The unit of a place** is the UTF-16 code unit, an index into the `drl` of the same answer as JavaScript counts a
    string (`drl.slice(start, end)` is the piece, and `drl.slice(f.start, f.declaration)` the comment line of a
    function, empty for none) and as a Java `String` does: not a byte of UTF-8 and not a code point. A letter such as `ë`
    counts one; a character outside the Basic Multilingual Plane counts two. The JSON text of the answer carries the same
    string, so no place needs converting.

    The keys are uchar's: `piece` for `DrlPiece`, `ruleStart`, `start`, `end` and `functions` for its components,
    `name`, `start`, `declaration` and `end` for those of `DrlFunction`; the `rule` of uchar's record is `name` of the
    entry (the key `rule` of an entry names its form). The places are together in `piece`, apart from the keys of the
    decider, because `position` and `pointer` of an entry are places in the IR and `start` and `end` places in `drl`;
    and `functions` is always there, empty for a rule without, so that no reader tests for its absence. The toolchain adds no division
    of its own: it does not read a function from the text and does not say what a function is for; a reader cuts
    `drl` at the places. The places are checked against the text before they are given: as many pieces as headers, the
    piece at a header the one of the rule of that header, each piece beginning where the one before it ends, the last
    ending with the text, and each `ruleStart` on the header `rule "…"` of its rule; otherwise the request fails with
    a 500 and the sentence of the first piece that does not fit.

  The pointers are uchar's, translated as the ground translates them (`Verbaliser.decider`); nothing is read off the
  name of a rule, whose label (`default 0.1.2`, `bijdrage aan vouw 7`) is the lowering's own numbering. The DRL of a
  variant is the entries with its `group` and `pointer`: its own decision has the role `own` (or `fallback`), a
  collection step for its justification `fold-contribution` with the `fold` it collects for. With `drlError` there is
  no text and `drlRules` is an empty list.
- `POST /playground/analyse?via=service` gives the same answer, except that the service the process runs computes the
  scenarios, so that a PASS says: this service gives this answer. It shows the session of each scenario, so a service
  started without `DETAIL` answers it with a 400 that names `DETAIL` (`field` `via`), and another value of `via` is a
  400. The service computes with its own sources: the `uchar-ir-v1` document uchar's emitter writes of the posted
  source, without its scenarios, must equal the service's (`DecisionCore.document`: the document it was given, or the
  one its `.rgs` sources make, in the order the service reads them), as JSON trees, the source lines uchar's document
  carries included; otherwise the whole request is a 400 `UNSUPPORTED` on `field` `source` that names the first
  difference as a JSON pointer. A source with a parse or resolver error gets its diagnostics, as without the flag. Per
  scenario:
  - its scope is mapped to an entry point of the descriptor (`DecisionCore.pathForScope`): one whose scope carries the
    forms of the scenario's scope, or every form of the model for a scenario without a scope; an exact match first, else
    the entry point with the fewest forms beside them, the first in the descriptor's order on a tie. The result names
    it as `path`, after `rule`. Without one the result is a MARKER whose `diagnosis` says what each entry point lacks,
    with `path` null; there is no fallback to the ordinary route;
  - the situation becomes a request (`SituationToRequest`, each instance with its label as its id, the names
    canonical, read with `inputVocabulary=canonical`, a dimensioned attribute as its `cells`, a marker as `{"marker":
    <naam>}` and the value `waarde(…)` as its payload; a value of a form uchar's RegelSpraak frontend does not write, a
    constructor with a payload other than `waarde` or a value without a variant, is a 500 `interne fout` with the
    sentence that names it on stderr, as on the ordinary route), and the core answers it
    with `explanation`, `explanationParts`,
    `trace`, `coverage` and `fixpoint`, in the vocabulary of `?vocabulary=` (canonical without it). uchar compares the `expected` of the case
    its frontend writes for the scenario with the written-back session of that request, the ids of the request as
    labels (`DecisionCore.assess`, `DroolsBackend.compare`): PASS or FAIL, the `expectations` and the `diagnosis`
    as uchar's report gives them, prefixed with the name of the case file as the report prefixes it;
  - an evaluation fault (the service's 422) is a FAIL with no `expectations` and the line of uchar's report,
    `<case file>: ERROR <exception>: <sentence>`, the exception uchar's write-back throws for the faults; `trace`,
    `coverage` and `fixpoint` are those of the 422, and `explanationError` says there is no `explanation`;
  - a session without a fixpoint (the service's 422 `NO_FIXPOINT`) is a FAIL with no `expectations` and the line
    uchar's report gives such a case, `<case file>: <sentence>`; `trace`, `coverage` and `fixpointError` are those of the
    422, and `explanationError` says there is no `explanation`. The other scenarios of the document are computed as usual;
  - a situation a request cannot carry (a fraction without a decimal form), a 400 of the service whose violations are
    all `UNSUPPORTED`, an entry point uchar does not carry, an expectation uchar does not compare
    (`CaseRunner.Excluded`) and a limit of uchar's branch (the service's 422 `LIMIT`, which uchar's replay excludes) are
    a MARKER with the sentence; any other 400 of the service is a FAIL naming its violations. The timeline of a
    time-dependent parameter or attribute goes as the request's `timeline`, a time-dependent characteristic as its
    `periods`;
  - an entry point that gives no explanation (its descriptor sets `explanation` to false, above) is asked without one:
    the scenario has its verdict, and `explanationError` gives the sentence of the 400.
  The entry point fires only the forms of its scope and uses the parameters of the descriptor beside those the
  situation sets, so a verdict can differ from the ordinary route's, which fires the forms of the scenario's own scope.

## The parts of an explanation

The verbaliser (`engine/Verbaliser`) writes every sentence through one builder (`Sentence.Builder`) as a shallow tree
of parts (`Part`, record `(kind, text, meta, parts)`); `Sentence.text()`, the texts of the leaves after each other, is
the only way to the plain sentence, so `explanation` and `explanationParts` cannot differ. The service
(`ExplanationParts`) and both routes of the playground write the tree with one writer: a leaf as `{"kind", "text",
<meta>}`, a group as `{"kind", <meta>, "parts"}`. The groups stand where the verbaliser is recursive, and around a
part a consumer may leave out with its glue (`COMPUTATION`, `MEMBERS`, `VARIANT`, `READING`; card
`explanation-part-groups`): a sentence without every `MEMBERS`, every `VARIANT` with `decided: false` and every
`READING` is still a sentence, with no bracket, colon or separator left over.

| kind | leaf or group | what | meta |
|---|---|---|---|
| `SUBJECT` | leaf | the label of an instance, or the name of an object a creation made, `«"<creation>"»(<subject>)#1` as uchar's canonical text names it with the subject as the explanation names it: the subject, or a member of a fold | — |
| `NAME` | leaf | a declared name as `Naming` names it (the name part of a predicate, an attribute, a role, a parameter) | `declaration` (`KENMERK`, `ATTRIBUUT`, `ROL`, `PARAMETER`), `owner`, `canonical`: the keys `kind`, `owner`, `canonical` of `vocabularies.elements[]`; `object` where the sentence names the value of an attribute or a characteristic of one object (below); `labels` where it names a cell of a dimensioned attribute, one label per dimension in their declared spelling |
| `QUOTE` | leaf | rule text as written: an operand, the text of a fold | the three keys of `NAME` where the operand reads one attribute, a cell of one, or a parameter, and `object` and `labels` as on `NAME` |
| `VALUE` | leaf | a value as the engine read it, with its unit, a number that does not terminate as its fraction `t/n` (uchar's RUNTIME-SEMANTICS ch. 5.12), a timeline as its periods (`5,00 van 01-12-2023 tot 01-01-2024`, `0,00 vanaf 01-03-2024`), a value of an attribute with absence markers as its payload or the name of its marker; a literal | `empty: true` for an empty one ("leeg", on every attribute); `timeline: true` for the value of a timeline target, its periods with their values (`12,00 van 01-01-2024 tot 01-03-2024`), and for the periods of a time-dependent characteristic |
| `RULE` | leaf | the name of a rule or of a variant, without its quotes | — |
| `CONNECTIVE` | leaf | omdat, want, maar, en, of, en niet, niet geldt, gold niet | — |
| `TEXT` | leaf | glue: every character that belongs to no other kind | — |
| `CONDITION` | group | a condition: an atom, a compound (its members with `CONNECTIVE`s between them), a fold | `holds`, the engine's verdict on its node (absent where the engine reached it without one); on a compound or a fold `quantifier` (`all`, `none`, `atLeast`, `atMost`, `exactly`, `the`: the wire spelling) and `threshold` on a count |
| `MEMBERS` | group | the members of a quantifier fold that carry its outcome, with the colon before them as its first `TEXT`; in a computation the members of a fold over a set, each with what it contributed | `members` (m), `holding` (k), `unjudged`; in a computation `members` alone |
| `COMPUTATION` | group | how the rule of a computed value, or the variant that decided a defaults group on an attribute, computed it, after its name, with its colon (below) | — |
| `VARIANT` | group | a writer that did not write the value of its sentence, with the separator before it (`"; "`, or `", "` between displaced variants): in the sentence of a defaults group every variant but the decider (before it, beside it in an unordered group, after it), in that of a computed value every writer but the one that wrote | `decided: false`, and `displaced: true` for a variant after the decider of a defaults group and for an initialisation that was not at its turn; on a displaced variant of a defaults group `holds` (absent without a condition), `decision` for a characteristic, `unreadable: true` where its reading carried a fault (below) |
| `READING` | group | the value the engine read for the operand or the member right before it, with its brackets: `" ("`, the `VALUE`, `")"` | — |
| `GROUND` | group | the ground, always the last part: `". Grondslag: "` as `TEXT`, then the annotations parsed | the decider whose annotations they are (below): `rule`, or `group`, `pointer`, `position` and `variant` where it has a name |
| `STATUTE` | leaf | a Juriconnect anchor, literally | `bwb`, `article`, and `paragraph` and `part` where it names them |
| `LEMMA` | leaf | the slug of a lemma | — |
| `DEVIATION` | leaf | the slug of a deviation | `between`, the layers it lies between, or the one layer it lies within; `follows`, the layer the rule set follows, absent within one layer (below) |
| `NOTE` | leaf | any other segment of an annotation: a quotation, a free note | — |

Without its last part (`GROUND`) a sentence is the sentence a rule without annotations gets. There is no kind for a CGM
concept: no rule annotation carries one.

**The object of a value** (card `explanation-read-objects-and-displaced`). A `NAME` or `QUOTE` with the keys of an
attribute or a characteristic carries `object` where the sentence names the value of that member on one object: the
label as `SUBJECT` writes it, a made object by its name. The head of a sentence names its subject; an own member in a
condition or a computation names the object of the reading, a read cell too, with the cell under `labels`; a read over
a role names the object its walk reached, and
`object: null` where a link on the walk is absent, so that the read is empty (uchar's RUNTIME-SEMANTICS ch. 5.8). The
object is the engine's: the reading records it at the node of every read of a member (ch. 5.12, "The object of a
read"). With `canonical`, `object` is
the pair a consumer looks up: the sentence of the writer of that value begins with `SUBJECT` equal to `object` and its
first `NAME` has the same `canonical`, and for a cell the same `labels`. An input value has no such sentence; `object`
then says whose input it is. No
`object` stands on a parameter, a role, the text and the filter of a fold over a set (read per member; the members stand
in `MEMBERS`), a read the engine did not reach, and the name of a default the opgave marker made, which names a variant.

**The sentence of a computed value** (card `explanation-computed-attributes`). Every attribute an ordinary rule of the
document writes on an object of the session (an equation, a calculation, an initialisation; not a rule that uchar's
RUNTIME-SEMANTICS ch. 7.10 took into a stack, which stands in the sentence of its stack) gets one sentence per object,
over all its writers, after the sentences of the defaults groups. A dimensioned attribute is written per cell (uchar's
WF-125), so every cell a rule writes gets a sentence of its own, over the writers of that cell, with the cell as
RegelSpraak writes it in place of the attribute: the labels of a dimension without a preposition before the name, the
others after it, each after the preposition of its dimension (`J1 heeft als vondstgewicht in najaar 3,00 kg volgens de
regel 'najaarsgewicht van een jutter' (onvoorwaardelijk): …`, `aanvoer op zuid uit zee`); the labels and their
prepositions are `TEXT`, the `NAME` carries the cell under `labels`. The four forms:

- a rule held and wrote a value: `<SUBJECT> heeft als <NAME> <VALUE> volgens de regel '<RULE>'<COMPUTATION>, omdat
  <CONDITION>`, or ` (onvoorwaardelijk)<COMPUTATION>` without a condition, or `<COMPUTATION>, omdat de <NAME> in de
  invoer leeg was` for an initialisation without one;
- a rule held and gave no value: `<SUBJECT> heeft geen <NAME>, want de regel '<RULE>' gaf geen waarde<COMPUTATION>,
  omdat <CONDITION>`; an empty value decides nothing (uchar's RUNTIME-SEMANTICS ch. 3.11), so where the target keeps
  its input value the sentence says so: `<SUBJECT> houdt als <NAME> de invoerwaarde <VALUE>, want de regel '<RULE>'
  gaf geen waarde en besliste niets<COMPUTATION>, omdat <CONDITION>`;
- no rule held and the target is empty: `<SUBJECT> heeft geen <NAME>, want geen regel gold`;
- no rule held and the target keeps an input value: `<SUBJECT> houdt als <NAME> de invoerwaarde <VALUE>, want geen
  regel gold`.

Then each other writer as a `VARIANT`: `; de regel '…' gold niet, want niet geldt: <the member where the engine
stopped>`, `; de regel '…' gold niet, want zij valt op de rekendatum buiten haar geldigheid`, `; de regel '…' gaf geen
waarde en besliste niets` (beside the rule that wrote, whose value stands), or `; verdrongen: de regel '…'`; then the
ground of the rule that held. The `COMPUTATION` is `": "`, the rule
text of the value (`QUOTE`), and `", met "` with every node that reads or folds something (an attribute, a parameter, a
dimension cell, a binder of `Daarbij geldt` by the text of its definition, a built-in function, a fold), each rule text
once, with the value the engine read there; where the value reads one declaration or folds over a set or over the
cells of a dimensioned attribute, that node is the rule text itself, with its value (`de som van zijn vondstgewicht over
alle seizoenen (4,50 kg)`; the engine's reading of a fold over cells carries no member, also where the cells are those
of the object a role reaches). A fold over a set has its `MEMBERS` ("over I4 (01-04-2026) en I5 (01-05-2026) (2
leden)", a count without a value per member); its text carries the keys of the attribute it folds, and for a cell of
each member (`de som van het vondstgewicht in voorjaar van alle leden van de Ploeg`) the cell under `labels`; and its
filter, where it is one characteristic check or one read, is a `QUOTE` of its own with the keys of that declaration. Last come the value before each rounding (`"; vóór de afronding
"`) and before each bound (`"; vóór de begrenzing "`). Every value is the engine's: the reading of the rule
(`DroolsBackend.readingsOf`) carries the nodes of its value after those of its condition, and since uchar's card
`readings-of-unconditional-forms` a rule without a condition has a reading of its value too; only the input value of
the fourth form is read on the bean. A writer of a timeline target, also its only one, decides the days on which its
timeline reads a value, and on the other days the target keeps its input (uchar's RUNTIME-SEMANTICS ch. 3.11); who
decided which days comes from uchar's `DroolsBackend.periodDecisionsOf`. The sentence of a timeline target gives each
rule that decided the periods it decided with their values, and has no computation; the condition of a rule, where it
holds per period (a clip, ch. 3.10), is its text with the periods the engine read it to hold in; the periods in which
the input stood follow: `"A1 heeft als rentedagen 30 dg/mnd van 01-12-2025 tot 01-03-2026 volgens de regel
'rentedagen in een gehele maand', omdat hij gedurende de gehele maand rentedragend is (van 01-12-2025 tot
01-03-2026), en 0 dg/mnd tot 01-11-2025, … volgens de regel 'rentedagen in een gedeeltelijke maand', omdat …"`,
`"J1 heeft als borgdeel 12,00 van 01-01-2024 tot 01-03-2024 volgens de regel 'borgdeel van een jutter'
(onvoorwaardelijk); de invoerwaarde 99,00 van 01-03-2024 tot 01-04-2024 bleef staan"`. Where no rule decided a day,
the target keeps its input as its periods, or is empty. An initialisation of a timeline target is the lowest layer of
that stack (uchar's RUNTIME-SEMANTICS ch. 7.8): uchar names it among the rules on the days it decided, the days the
other rules and the input leave empty, over all time, so the sentence gives it those periods with `omdat de <NAME> in
de invoer leeg was`, or its condition with the periods it holds in, as the first form gives an initialisation:
`"J2 heeft als voorjaarsloon 20 van 01-03-2025 tot 01-06-2025 volgens de regel 'voorjaarsloon', omdat …, en 5 tot
01-01-2025 en 5 vanaf 01-06-2025 volgens de regel 'standaardvoorjaarsloon', omdat de voorjaarsloon in de invoer leeg
was; de invoerwaarde 10 van 01-01-2025 tot 01-03-2025 bleef staan"`. Where the rules and the input leave it no day,
its reading is `DISPLACED` and it is `; verdrongen: de regel '…'`. A timeline the engine read inside a computation stands as its periods, and the total of the
subject's own timeline (ch. 3.6) has no `MEMBERS`: it is no fold over a set. A time-dependent characteristic a rule
writes in a window has its own sentence, with the periods the bean holds: `"A1 is rentedragend van 16-11-2025 tot
13-03-2026 volgens de regel 'rentedragend'"`, without its condition, or `"… is niet rentedragend volgens de regel
'…'"` where it holds on no day. A rule
outside the scope of an entry point gets the one sentence "de regel '…' staat buiten deze berekening", like an
attribution rule.

**The value of the making** (card `timeline-stack-in-service`). An object a creation made has no input: in the place
the input has on an object of the request, it holds the member value its creation gave it (rank 2 of uchar's stack,
RUNTIME-SEMANTICS ch. 7.8), a scalar on every day or a timeline per period. Every sentence names that value `de
waarde bij het maken` where it names an input value `de invoerwaarde`, on a target with one value, on a cell, per
period and in the sentence of a defaults group, and an initialisation decided there `omdat de <NAME> bij het maken
leeg was`: `"«"meld de jutter"»(J1)#1 heeft als seizoensloon 20 van 01-03-2025 tot 01-09-2025 volgens de regel
'seizoensloon', omdat …; de waarde bij het maken 10 van 01-01-2025 tot 01-03-2025 bleef staan"`, `"«"meld de
jutter"»(J1)#1 heeft als aanvulloon 5 tot 01-01-2025 en 5 vanaf 01-07-2025 volgens de regel 'standaardaanvulloon',
omdat de aanvulloon bij het maken leeg was; de waarde bij het maken 10 van 01-01-2025 tot 01-07-2025 bleef staan"`,
`"«"meld de vangst"»(J1)#1 houdt als meldwaarde de waarde bij het maken 7, want de regel 'meldwaarde uit toeslag' gaf
geen waarde (onvoorwaardelijk) en besliste niets: zijn toeslag (leeg)"`.

**The deciding variant of a defaults group** (card `explanation-defaults-computation`). In the sentence of a defaults
group on an attribute, the variant that decided gets the same `COMPUTATION` after its name, where the sentence of a
computed value has it: after `" (onvoorwaardelijk)"` and before `", omdat …"`. Its value is the default's consequence,
or the value of the ordinary rule uchar's RUNTIME-SEMANTICS ch. 7.10 took into the stack; for the two defaults the
opgave marker makes it reads `opgegeven X` or `berekende X` (`"volgens 'berekende maxdatum' (onvoorwaardelijk): zijn
berekende maxdatum (01-01-2026)"`), so a reader goes on from there to the sentence of the computed value. The values
come from the variant's reading, where the nodes of its value follow those of its condition. The variants that did not
decide stay as they were, and a characteristic has no computation.

**A defaults group per day** (card `explanation-per-period`). A stack on a time-dependent attribute decides per day
(uchar's RUNTIME-SEMANTICS ch. 7.11): on every day the highest default that decides that day, a conflict of the
members it reconciles, or no default, and then the input of that day stands. uchar names that per object in maximal
periods with one outcome (`DroolsBackend.periodDecisionsOf`, `PeriodDecisions`), and the explanation gives the group a
sentence per period, in the order of the days:

- a variant decided: `<SUBJECT> heeft als <NAME> <VALUE> volgens <variant>`, the `VALUE` its value in that period
  with the period (`timeline: true`), then what the sentence of a defaults group gives the variant that decided: its
  `COMPUTATION` and why it held, a condition per period with the periods it holds in, and its `GROUND`: `"J4 heeft als
  strandloon 10 van 01-01-2025 tot 01-03-2025 volgens 'opgegeven strandloon' (onvoorwaardelijk): zijn opgegeven
  strandloon (10 van 01-01-2025 tot 01-03-2025)"`; a variant that decides two periods has a sentence for each;
- a conflict: what the target holds in that period, `de invoerwaarde <VALUE>` or `heeft geen <NAME> <period>`, then the
  conflict as the sentence of a defaults group names it (`"…, want er is een conflict: de regel 'toelage voor
  kustwacht' besliste 'heeft als toelage 10' en de regel 'toelage voor stormwacht' besliste 'heeft als toelage 20', dus
  de groep schrijft niets"`);
- no variant decided and the input stood: `"J4 houdt als strandloon de invoerwaarde 99 van 01-06-2025 tot 01-01-2026,
  want geen variant besliste"`, on a made object the value of the making (above): `"«"meld de jutter"»(J1)#1 houdt
  als toelage de waarde bij het maken 10 van 01-03-2025 tot 01-07-2025, want geen variant besliste"`.

A period without a decider and without input has no sentence; where no variant decided on any day and no input
stands, one sentence says so: `"J2 heeft geen toelage, want geen variant besliste"`. The variants that did not decide a
period are not named in its sentence.

**Absence markers** (card `absence-markers-in-service`). The engine reads the value of an attribute with absence
markers as uchar's `ConstructorValue` (RUNTIME-SEMANTICS ch. 9.5). The explanation writes the value `waarde(…)` as
its payload, with the decimals and the unit of the declared datatype, and a marker by its declared name, as the head of
a sentence (`"V1 heeft als taxatie onbekend volgens de regel 'taxatie onbekend': onbekend, omdat …"`), as a value a
condition or a computation read, on a cell (`"V1 heeft als gewicht in voorjaar onbekend volgens de regel
'voorjaarsgewicht onbekend': onbekend, …"`, `"zijn gewicht in voorjaar (onbekend) is gelijk aan onbekend"`), and per
period (`"V1 heeft als opslagkosten onbekend van 01-01-2025 tot 01-03-2025
volgens de regel 'opslag onbekend in de winter', …, en niet van toepassing van 01-06-2025 tot 01-01-2026 volgens …;
de invoerwaarde 10 van 01-03-2025 tot 01-06-2025 bleef staan"`). A read of the value under a test of it (uchar's
`PayloadAccess`) is empty where the attribute holds a marker, so the sentence names the attribute with what it holds:
`"zijn taxatie (niet van toepassing) een waarde heeft"`, the test of a value in the words of the dialect; a marker in a
comparison stands once, as a literal does (`"zijn taxatie (onbekend) is gelijk aan onbekend"`). An empty value stands
as `leeg`, here as on an attribute without markers (`"zijn taxatie (leeg) is gelijk aan onbekend"`), so `onbekend` in
an explanation is always the marker.

**What a displaced variant found** (card `explanation-read-objects-and-displaced`). Every variant of a defaults group
after the decider says, after its name, what it found, from uchar's reading of a displaced default (RUNTIME-SEMANTICS
ch. 5.12, "A displaced default is read": status `DISPLACED`, with the nodes of its condition and, where that holds, of
its value, evaluated on the fixpoint world as at its turn):

- an attribute: `'<name>' (gold, <VALUE>)`, `(gold, gaf geen waarde)` where its value was empty, or `(gold niet)`;
- a characteristic: `(gold, toekennen)` or `(gold, niet toekennen)`, after the decision of the variant
  (`Default.decision`), or `(gold niet)`;
- a variant without a condition: `(onvoorwaardelijk, <VALUE>)`, or `(onvoorwaardelijk, toekennen)`;
- an initialisation whose target the input filled, which the engine does not read: `(gold niet)`;
- a variant outside its validity: `(buiten haar geldigheid)`;
- a reading with a fault on a node (a fault the session did not have, since the variant was not at its turn): the name
  alone, with `unreadable: true`.

The value is rendered as the head renders the decided value. The reason a variant did not hold stays out of the
sentence; a consumer reads the nodes in the engine's reading if it needs them.

**The decider of a ground.** `GROUND` names the rule or the default whose `statuteAnnotations` it carries, in the
terms of the IR, so a reader finds that decider and its annotations in the document (and from there the `// Bron:`
block in the sources) without reading the sentence, which may name several rules:

- a rule — the rule of a characteristic assignment, or a rule that decides in a defaults group as the base of a
  subtype's stack (uchar's RUNTIME-SEMANTICS ch. 7.10): `rule`, the name of its form, `rules.rules[].name`, which
  carries the annotations for all its versions (IR-SPEC ch. 3.1); the model names a version `"<name> #i"`, the key
  never does;
- a default: `group`, the name of its group, `rules.prioritizedDefaults[].name` (null only where the document gives
  the group no name, which WF-107 refuses and uchar's loader reads); `pointer`, the default in that group as a JSON
  Pointer, `/defaults/1` or, nested in an exception group, `/defaults/0/exceptions/members/0/defaults/0` (the engine's
  pointer without the group's index in the document, because a scope moves that index and not the name); `position`,
  its place in its chain, 1-based, the n of "de variant op plaats n"; and `variant`, its name (`Default.name`), where
  it has one. The name alone is no identity: a default is in no namespace, and an exception may lose its name
  (IR-SPEC ch. 6.1).

A sentence without `GROUND` has no decider with annotations: a rule or block without `// Bron:` lines, and the
defaults and the rule the opgave marker makes.

**The grammar of the ground** (`engine/Ground`). The annotations are free text on the wire (uchar's IR-SPEC ch. 3.1,
WF-082: one string per `// Bron:` line of a rule, provenance only); their structure is the convention of the werkbank's
rule set (MODELKAART B20: "permalink met verhouding · anker(s) · lemma's: slug · slug"). The parser is total: a
segment that matches no pattern is a `NOTE`, and it never fails.

```
ground      = annotation { "; " annotation }          (the annotations of a rule; a default has one, below)
annotation  = segment { " · " segment }
segment     = statute | lemma-head | deviation | lemma | note
statute     = "BWBR" 7DIGIT "&artikel=" value [ "&lid=" value ] [ "&o=" value ]    (value: 1*, no "&")
lemma-head  = "lemma's: " slug                        (opens the list of lemmas)
deviation   = "deviatie " layer "/" layer ", volgt " layer ": " slug
            | "deviatie binnen " layer ": " slug       (both only after the lemma head)
lemma       = slug                                    (only after the lemma head)
note        = any other text, also a slug before the lemma head and a near-anchor
slug        = 1*( letter | DIGIT | "-" )
layer       = 1*( lowercase letter )
```

The separators `" · "`, `"; "`, `"lemma's: "` and the words of a deviation are `TEXT`; an empty segment adds only its
separators. A rule and a default carry their annotations as a list (`statuteAnnotations`; a default those of its
block), so the parser takes each entry as one annotation and never splits text: "; " also occurs within a note.

**The layers of a deviation** (card `ground-deviation`). A deviation is an entry of the deviation register of the
wetsanalyse, which names the layers of each entry (`wet→hb`, `intern brm`). `deviatie wet/hb, volgt hb: <slug>` is a
deviation of `hb` from `wet` in which the rule set follows `hb`: `between: ["wet", "hb"]`, in the order of the
register, and `follows: "hb"`. `deviatie binnen brm: <slug>` lies within one layer: `between: ["brm"]`, without
`follows`; the note in the same annotation says which rule of that layer the rule set follows. `between` is one key
for both forms, a list, so a reader finds the layers of every deviation in one place and tells the forms apart by its
length. The werkbank chooses the layer codes; the parser does not know them, and it does not judge whether `follows`
is one of the pair or whether the deviation is in the register (the werkbank's lint does). The old forms
`afwijking: <slug>` and `afwijking, niet gevolgd: <slug>` are notes since this card.

**peildatum-contract (multi-vintage deploys).** When the descriptor names a parameterset sidecar whose values
carry more than one vintage, the service selects each parameter's vintage against ONE date authority: the request
`asOfDate`, which *is* the rekendatum global. A request without `asOfDate` gets `400 "peildatum verplicht"`, a
peildatum/rekendatum clash gets 400, and `/info` lists the loaded validity ranges plus `periodWarnings`. The
advisory `scripts/as-of-date-vintage-check.sh <parameter-sets.json> <true|false>` WARNs (never blocks) on a
declared/actual mismatch.

## Deploying (container + Helm + kind) — host only

`make image`, `docker/`, `helm/decision-service/` and `make kind-smoke` package the service (README, "The
container"). The image is built from the reactor and starts `bin/regelspraak serve` (`docker/entrypoint.sh`); its
contract is the environment: `DESCRIPTOR`, `SOURCES_MANIFEST` (a file listing the `.rgs` sources in order), `PORT`,
`ALLOW_MARKERS`.
