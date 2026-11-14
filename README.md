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
make check-pin        # the submodule is checked out, clean, on uchar's main, and the versions match uchar's
mvn -B -o verify -Dmaven.test.skip.exec=true   # the whole reactor, offline (make verify runs both)
```

`-o` builds from the local Maven repository alone. The first build on a machine needs the network once
(`mvn -B -DskipTests package`) to fill that repository with the plugins and dependencies, the antlr4-maven-plugin
and the ANTLR tool among them; after that every build runs offline.

The reactor runs the test suites of `toolchain`, `service` and `service-quarkus` and builds uchar's two modules
without suites. `-Dmaven.test.skip.exec=true` skips the suite of uchar's backend, whose pom the parent pom does not
reach; the parent pom keeps the suites of the own modules running. uchar runs its suites before a commit lands on its
main, and the pin is always a commit of that main ("The pin").

| Module | What it is |
|--------|------------|
| `uchar/backends/drools` | uchar's Drools backend, built with uchar's own pom, without its test suite |
| `uchar-frontend/` | uchar's RegelSpraak frontend compiled from `uchar/frontends/regelspraak`: the parser generated from uchar's grammar, the resolver and the emitters; the main sources only, so no tests |
| `toolchain/` | the CLI (`RegelSpraakCli`), the build through uchar (`UcharBuild`), the scenario runner and its CLI, the verbaliser |
| `service/` | the decision service on the JDK `HttpServer` (`DecisionCore`, `ServiceMain`, OpenAPI emitter, parameterset loader, dev-only playground); its build also bundles the distribution into the one jar `bin/regelspraak` runs from |
| `service-quarkus/` | the Quarkus adapter over the same service core |

Own code lives in `dev.uchar.regelspraak.toolchain.*` and uses only uchar's public API; every KieBase is built by
`DroolsBackend.build(document, branch)` over the document uchar's emitter writes. The engine and the service are
described in `docs/ENGINE.md`.

## Contract change: English names

Since the card `engelse-namen` (2026-10-18) everything a caller sees is English, except what comes from RegelSpraak
(the kinds of declarations `ATTRIBUUT`, `FEITTYPE`, `ROL` ..., the predicates, the global `rekendatum`, the names a
model declares) and the sentences of the messages, which keep their text. The old names no longer work; a request
that writes one at the top, in an instance or in a relation gets a 400 `MALFORMED` on that key, and a descriptor that
writes one does not start:

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
- **The deciding variant computes** (card `explanation-defaults-computation`): in the sentence of a defaults group on
  an attribute, the variant that decided gets the same `COMPUTATION` after its name. This changes existing sentences:
  `"… volgens 'berekende maxdatum' (onvoorwaardelijk); …"` is now `"… volgens 'berekende maxdatum' (onvoorwaardelijk):
  zijn berekende maxdatum (01-01-2026); …"`. Nothing else in those sentences changes.
- **The object of a value** (card `explanation-read-objects-and-displaced`): a `NAME` or `QUOTE` with the keys of an
  attribute or a characteristic carries `object`, the label of the object whose value the sentence names (the subject
  for the head of a sentence, the object a read over a role reached), or `null` where a link on the role is absent
  (`docs/ENGINE.md`, "The object of a value"). `explanation` and the text of the parts are unchanged.
- **Omissible parts carry their glue** (card `explanation-part-groups`): the colon before the members of a fold is the
  first `TEXT` of `MEMBERS`; every variant of a defaults group that did not decide is a group `VARIANT` (`decided:
  false`, and `displaced: true` after the decider) with its separator; and a value the engine read stands with its
  brackets in a new group `READING`. Without those groups a sentence stays a sentence. `explanation` and the text of the
  leaves are unchanged.
- **A displaced variant says what it found** (card `explanation-read-objects-and-displaced`, part 2): in the sentence of
  a defaults group, every variant after the decider is followed by `(gold, <value>)`, `(gold, gaf geen waarde)`,
  `(gold niet)`, `(gold, toekennen)`/`(gold, niet toekennen)` or `(onvoorwaardelijk, …)` (`docs/ENGINE.md`, "What a
  displaced variant found"). This changes existing sentences: `"…; verdrongen: de regel 'premie'"` is now `"…;
  verdrongen: de regel 'premie' (gold, 100,00)"`.
- **Allocations and creations are explained**: the share an allocation writes on a recipient and the rest it writes on
  its source get the sentence of a computed value, with the allocation as a writer beside the ordinary rules of that
  target and how it divided as its `COMPUTATION` (`"M1 heeft als meldaandeel 3 volgens de regel 'verdeel het meldgeld'
  (onvoorwaardelijk): W verdeelde zijn meldgeld (10) in gelijke delen, afgerond op 0 decimalen naar beneden, over M1
  (3), M2 (3) en M3 (3) (3 leden)"`); an object creation and a fact creation get a sentence on their subject (`"H1
  heeft een Premiepot «"premiepot"»(H1)#1 volgens de regel 'premiepot' (onvoorwaardelijk): met als pot …"`, `"H1 heeft
  S1 en S2 als winterschuit volgens de regel '…', omdat …"`), and a `NAME` can carry `declaration: OBJECTTYPE`
  (`docs/ENGINE.md`, "An allocation", "A creation"). The sentences that were there keep their order; the new ones
  follow the computed values of their subject, and an attribute an allocation wrote, which had a sentence of its
  ordinary rules, now names the allocation as its writer.
- **Corrections in the explanation**: a value that something other than the writers of a sentence derived is "de
  afgeleide waarde", no longer "de invoerwaarde" (the standing is read from uchar's `EndState`, not from the bean
  after the write-back); "numeriek met exact N cijfers" keeps its negation, digits and whole-period form, where it was
  "voldoet aan numeriek_met_exact"; a number is padded to its declared decimals and never rounded to them ("0,505", not
  "0,51"; "2,8 km", not "3 km"); a value fold counts "leden met een waarde" and says "geen lid droeg een waarde bij"
  where it said "er is geen enkel lid" of members that were all leeg.
- **A deviation names its layers** (card `ground-deviation`): a `DEVIATION` part carries `between` (the layers of
  the pair in the order of the register, or the one layer of `deviatie binnen`) and `follows` (the layer the rule
  set follows; absent within one layer) in place of `followed`. The old forms `afwijking: ` and `afwijking, niet
  gevolgd: ` are a `NOTE`. `explanation` and the text of the parts are unchanged.
- **The DRL names what each rule lowers** (card `drl-rule-provenance`): beside `drl` the playground gives `drlRules`,
  per rule of the text its `name` and its decider in the keys of `GROUND`, with `stack` and `generatedFrom`
  (`docs/ENGINE.md`); `drl` is unchanged, and with `drlError` the list is empty.
- **The DRL names the role of each rule, a stack its type and composition** (card `drl-rule-part-fields`): an entry
  of `drlRules` with a form has `role` (uchar's key) and a rule of a fold `fold` (its node, within the group or form
  like `pointer`); a stack in `generated` has `type`, with `typeCanonical` where that differs, and `composition`, its
  defaults in the order of priority across its groups, in the keys of `drlRules`, with their `exceptions` below them
  (`docs/ENGINE.md`).
- **The DRL gives each rule its piece** (card `drl-rule-functions-fields`): an entry of `drlRules` has `piece`, the last
  key, uchar's piece of `drl` for that rule: `{start, ruleStart, end, functions}`, with per function `{name, start,
  declaration, end}` (`TmsTranspiler.drlPieces`). The places are indices in the `drl` of the same answer, in UTF-16 code
  units as JavaScript counts a string (`docs/ENGINE.md`). `drl` is unchanged, and with `drlError` the list is empty.
- **Dimensioned attributes are cells** (uchar's card `dimensions-executed`): a request gives a dimensioned attribute
  as `{"cells": [{"labels": [...], "value": ...}]}`, where it was a 400 `UNSUPPORTED` ("dimensie-gewaardeerd attribuut
  … wordt niet ondersteund door de dienst-kern"); the answer gives it as every cell with its labels and value, where it
  gave the text of uchar's map as one value; labels that name no cell (EF-034) and a cell given twice (EF-035) are a
  422 of the new kind `CELL_FAULT`; the `fixpoint` has a row per cell with `labels`, where a dimensioned value stood
  under `dimensions`; in the OpenAPI document a dimensioned attribute is a property of its cells, where it was left out
  as unsupported. The explanation gives every cell a rule writes a sentence of its own, a `NAME` or `QUOTE` of a cell
  carries `labels`, and a fold over cells is the rule text with its value (`docs/ENGINE.md`).
- **Cells in a test document** (uchar's card `dimensions-remaining`): the playground gives a cell of the situation in
  `situation.objects[].attributes[]` as `cells`, `[{labels, value}]`, beside `value` and `timeline`, and
  `?via=service` sends it as the `cells` of the request; such a document was a 500. The `QUOTE` of a fold over a cell
  of each member (`de som van het vondstgewicht in voorjaar van alle leden van de Ploeg`) carries the cell under
  `labels` beside the keys of the attribute, where it carried the attribute's keys alone.
- **Exact values in the explanation** (uchar's card `drools-exact-arithmetic`): uchar's reading of a node gives a
  value that does not terminate as the exact fraction (RUNTIME-SEMANTICS ch. 5.12), where it gave a decimal of sixteen
  digits. The explanation writes it as that fraction with its unit: `"…; vóór de afronding 100/3 EUR"`, where it wrote
  `"…; vóór de afronding 33,33333333333333 EUR"`. A value of the unbounded form (`Numeriek (getal)`) keeps its decimals
  in the explanation: `"B2 heeft als ruwe deel 2,5 EUR"`, where the explanation rounded it to a whole number (`3 EUR`),
  and one that does not terminate is its fraction: `"B1 heeft als ruwe deel 100/3 EUR"`, where it stood as `33 EUR`.
  The answer is unchanged: a value of the unbounded form is the decimal uchar puts on the bean.
- **Timelines over all time** (uchar's cards `timeline-arithmetic-by-rule`, `timeline-arithmetic-per-period`,
  `conditions-per-period` and `total-over-partial-unit`): uchar's Drools branch reads a time-dependent parameter
  (`voor elke maand`) whole, as a `List<Period>` global, so a request and a descriptor default give such a parameter
  its timeline, `{"timeline": [{"from": …, "to": …, "value": …}]}`, where a segment without `from` begins before every
  day and one without `to` runs past every day (an open bound). A scalar on such a parameter is a 400 `WRONG_TYPE`,
  where the service set no global and the rules faulted on an unbound parameter (422 `COMPUTE_FAULT`), and a
  parameter set may not name one. A timeline attribute in `output` is answered as its segments, an open bound left
  out, where the answer gave the text of the bean's list; a time-dependent characteristic in `output` refuses the
  start, where the answer gave `false`; the `fixpoint` leaves out an open bound, where it wrote `null`. A total over
  an open segment that is not 0 (EF-036) has the kind `OPEN_SEGMENT_FAULT` and periods of a characteristic with an
  open bound (EF-023) `CHARACTERISTIC_PERIODS_FAULT`, both where they were `OTHER`; a value uchar's branch cannot
  hold, a segment of the unbounded form that does not terminate (DROOLS-312), is a 422 `{"status": "LIMIT", "error",
  "code", "reason"}` (`docs/ENGINE.md`). The day count decides every day: a month without a true day counts 0, open
  at both ends, so a jutter that is never borgplichtig has the timeline 0 on all time (`"J2 heeft als borgdagen altijd
  0 volgens de regel 'borgdagen' (onvoorwaardelijk)"`, where it was `"J2 heeft geen borgdagen, want de regel
  'borgdagen' gaf geen waarde (onvoorwaardelijk)"`). In the explanation a timeline the engine read stands as its
  periods (`zijn maandrente (0,000 EUR/mnd tot 01-11-2025, 16,667 EUR/mnd van 01-11-2025 tot 01-12-2025, … en 0,000
  EUR/mnd vanaf 01-04-2026)`), where it was the text of the engine's list, the total of the subject's own timeline
  has no members, where it said `er is geen enkel lid`, a time-dependent characteristic a rule writes in a window
  has a sentence with its periods (`"A1 is rentedragend van 16-11-2025 tot 13-03-2026 volgens de regel
  'rentedragend'"`), and a condition per period of a timeline target names the periods it holds in.
- **An empty value decides nothing** (uchar's card `empty-write-decides-nothing`): a rule that fires with an empty
  value decides nothing, on every scalar target, so a request with an input value on an attribute that rules write
  gets that input back where no rule decides. Beside a second rule that does not hold and with a validity window
  (`geldig vanaf 2020`) the answer gives the input, where it gave `null`; beside a rule that gives 50 the answer is
  50, and beside a conditional initialisation the initialisation fills an empty target and an input stands, where
  each was a 422 `WRITERS_CONFLICT` ("de regel … schrijft leeg en de regel … schrijft 50"). Two rules with different
  values stay `WRITERS_CONFLICT`. The explanation says that the rule decided nothing where the target keeps a value
  it did not write: `"V1 houdt als taxatie de invoerwaarde 40, want de regel 'taxatie uit opgave' gaf geen waarde en
  besliste niets: …"`, where it was `"V1 heeft geen taxatie, want de regel 'taxatie uit opgave' gaf geen waarde: …"`,
  and a rule beside the rule that wrote is `"; de regel '…' gaf geen waarde en besliste niets"`. On the scenario
  route the same holds per day on a timeline target (uchar's card `writers-per-day-beside-input`): a writer, also the
  only one, decides the days on which its timeline reads a value, and the input stands on the other days, where one
  writer replaced the whole timeline.
- **Timelines of attributes, periods of characteristics** (card `timeline-attribute-input`, on uchar's cards
  `writers-per-day-beside-input` and `defaults-per-period-on-timeline`): a request gives a time-dependent attribute as
  its timeline, `{"timeline": [{"from": …, "to": …, "value": …}]}`, as a time-dependent parameter, and a time-dependent
  characteristic as the periods in which it holds, `{"periods": [{"from": …, "to": …}]}`, where each was a 400
  `UNSUPPORTED` ("tijdlijn-gewaardeerd attribuut … wordt niet ondersteund door de dienst-kern"); the OpenAPI document
  describes both as properties of their object type, where it left them out. A boolean on a time-dependent
  characteristic is a 400 `WRONG_TYPE`, where it was `UNSUPPORTED`; a scalar on a time-dependent attribute stays a 400
  `UNSUPPORTED`, with a sentence that names the timeline form. A full timeline where a rule reads the value in the
  period of the evaluation date (the subject's own total) now reaches the service: a 422 of the kind
  `INPUT_FORM_FAULT`. On a defaults group on a time-dependent attribute, which decides per day (the opgave marker on
  such an attribute among them), the answer gives what uchar decided per day.
- **The explanation per period** (card `explanation-per-period`, on uchar's card `decisions-per-period`): a defaults
  group on a time-dependent attribute, which decides per day, gets a sentence per period in which a variant decided,
  with its value in that period, how it computed it and why it held (`"J4 heeft als strandloon 10 van 01-01-2025 tot
  01-03-2025 volgens 'opgegeven strandloon' (onvoorwaardelijk): zijn opgegeven strandloon (10 van 01-01-2025 tot
  01-03-2025)"`), a sentence per period with a conflict, and one per period in which no variant decided and the input
  stood (`"J4 houdt als strandloon de invoerwaarde 99 van 01-06-2025 tot 01-01-2026, want geen variant besliste"`).
  `?explanation=true` on such an entry point gives that explanation, where it was a 400 `UNSUPPORTED` on
  `explanation` naming the stacks, and the playground and `bin/regelspraak scenario --explanation` give it, where
  they gave the scenario no explanation and the reason as `explanationError`. A time-dependent attribute that
  ordinary rules write names per rule the periods it decided with their values, and the periods in which the input
  stood: `"J1 heeft als borgdeel 12,00 van 01-01-2024 tot 01-03-2024 volgens de regel 'borgdeel van een jutter'
  (onvoorwaardelijk); de invoerwaarde 99,00 van 01-03-2024 tot 01-04-2024 bleef staan"`, where it was `"J1 heeft als
  borgdeel een tijdlijn volgens de regel 'borgdeel van een jutter' (onvoorwaardelijk)"`; an input on which no rule
  decided a day is its periods, where it was `de invoerwaarde een tijdlijn`. Such a `VALUE` carries `timeline: true`,
  as `een tijdlijn` did.
- **Absence markers** (card `absence-markers-in-service`, on uchar's card `absence-markers`): an attribute declared
  `(of onbekend)`, `(of niet van toepassing)` or `(of onbekend of niet van toepassing)` holds uchar's sum type
  (RUNTIME-SEMANTICS ch. 9.5). A request gives it a value of its datatype, which is the value `waarde(…)`, or a marker,
  `{"marker": "onbekend"}` or `{"marker": "niet van toepassing"}`, also as the `value` of a segment of its timeline;
  where a marker was a 400 `WRONG_TYPE` ("eenheid-object mist 'value'") and a plain value a 500. A marker the attribute
  does not declare is a 400 of the new kind `UNKNOWN_MARKER`, and a marker on an attribute without markers a 400
  `WRONG_TYPE` whose sentence names the attribute ("heeft geen markers"). The answer, and the `fixpoint` of the detail,
  give the attribute in the same form, where they gave the text of uchar's value (`"waarde(300)"`, `"onbekend"`). The
  explanation writes the value as its payload (`"V1 heeft als taxatie 300,00 volgens …"`) and a marker by its name,
  also per period; the test whether such an attribute holds a value reads `"zijn taxatie (niet van toepassing) een
  waarde heeft"`, and a marker in a comparison stands once (`"is gelijk aan onbekend"`). In `explanationParts` the
  `VALUE` of a marker carries `marker`, its name (card `marker-on-value-part`): in the head of a sentence, as a value
  read and as the literal of a comparison, so a consumer tells it from an enumeration value spelled the same; a
  timeline is one `VALUE` with all its periods and carries none. In the OpenAPI document such
  an attribute is `oneOf` the schema of its datatype and the marker object, with an `enum` of the markers it declares.
  An attribute of another sum type, which only an IR document of another producer declares, refuses the start in
  `output` (`UNSUPPORTED`).
- **Empty is leeg** (card `empty-as-leeg-in-explanation`): the explanation writes an empty value as `leeg` on every
  attribute, as RegelSpraak writes it (`is leeg`), also as a value the engine read (`"zijn leeftijd (leeg) is groter
  dan de minimumleeftijd (17)"`, `"met zijn schatting (leeg)"`); the `VALUE` of an empty value (`empty: true`) has the
  text `leeg`. `onbekend` in an explanation is the absence marker alone. The answer is unchanged: an empty attribute
  is `null`.
- **Markers in a test document** (card `markers-in-test-documents`, on uchar's card `absence-markers-residue`): uchar's
  scenario layer reads a marker as the value of a situation and an expectation (`- de taxatie is onbekend`), also in a
  segment and in a cell, where it refused one as a syntax error. The playground gives such a value in the `situation`
  in the form of the request, a marker as `{"marker": "onbekend"}` under `value` and a value of an attribute with
  markers as its payload (`"40.00"`), and `?via=service` sends it so; the page shows a marker by its name. A marker on
  a cell of a dimensioned attribute with markers, which a rule now writes and a condition tests, goes in the request,
  the answer, the `fixpoint` and the explanation as a marker on a scalar does (`"V1 heeft als gewicht in voorjaar
  onbekend volgens de regel 'voorjaarsgewicht onbekend': onbekend, …"`).
- **The stack per day** (card `timeline-stack-in-service`, on uchar's card `timeline-initialisation-allocation-creation`):
  uchar reads the stack of a timeline target per day (RUNTIME-SEMANTICS ch. 7.8): the writers decide their days, the
  input stands on the other days, and the initialisation decides the days that leaves empty, over all time. A model
  with an initialisation of a time-dependent attribute, or with a creation that gives one a value, is served and
  answered per day, where uchar's branch refused it and the entry point was a marker (DROOLS-118 for the
  initialisation, DROOLS-103 for it beside a computing rule, DROOLS-271 for the value of a creation). The explanation names the initialisation per period among the rules (`"… en 5 tot 01-01-2025 en 5
  vanaf 01-06-2025 volgens de regel 'standaardvoorjaarsloon', omdat de voorjaarsloon in de invoer leeg was; de
  invoerwaarde 10 van 01-01-2025 tot 01-03-2025 bleef staan"`). An object a creation made has no input, and every
  sentence names what stands there the value the creation assigned, per period and on a target with one value, in the words
  of the RegelSpraak specification (the construct objectcreatie, each of its members a waardetoekenning): `"; de bij
  de objectcreatie toegekende waarde 10 van 01-01-2025 tot 01-03-2025 bleef staan"`, `"houdt als meldwaarde de bij de
  objectcreatie toegekende waarde 7"`, `"omdat de aanvulloon bij de objectcreatie leeg was"`, where a sentence on an
  object of the request says `de invoerwaarde` and `in de invoer leeg was`. A
  value of an initialisation or a creation on a timeline target that is neither period arithmetic nor the constructor
  of a literal makes the entry point a marker with uchar's sentence (DROOLS-316); an allocation that names a
  time-dependent attribute refuses the start with the frontend's error (RGS-378), and in a `uchar-ir-v1` document the
  entry point is a marker with uchar's sentence naming WF-128 (DROOLS-315).
- **The value the creation assigned, in the fixpoint** (card `fixpoint-creation-value`): the `fixpoint` of the detail and of the
  playground gives on an object a creation made, beside a value a rule derived, the value the creation put on it as
  `input`, with `inputOrigin: "creation"`, as it gives the input of an instance of the request beside a derived value:
  a scalar, a timeline as its segments and a cell. Where it gave the derived value alone, a made melding of uchar's
  golden `jutterswet-creatie-per-dag` now has `{"attribute": "seizoenswaarde", "timeline": [… 7, 20, 7 …], "origin":
  "derived", "input": [{"value": "7"}], "inputOrigin": "creation"}`. A row without a derived value keeps the origin
  `creation`; the rows of an instance of the request are unchanged. The OpenAPI description of `values` names
  `inputOrigin`.
- **Periods of a characteristic in the fixpoint** (found in the comparison run of card `marker-on-value-part`): the
  `fixpoint` of the detail and of the playground gives a time-dependent characteristic with the periods in which it
  holds, `{"characteristic": "rentedragend", "origin": "derived", "periods": [{"from": "2025-11-16", "to":
  "2026-03-13"}]}`: those a rule wrote within a window, which uchar gives among the derived values as its true periods,
  or else those of the input.
- **A writer that held on no day** (card `writer-holds-on-no-day`): a rule or an initialisation on a timeline target
  whose condition per period holds on no day (uchar's reading `HOLDS` with a condition that reads no period) is named as
  a rule that did not hold, with its condition and the periods the engine read: `"; de regel 'standaardstormloon' gold
  op geen dag, want niet geldt: hij stormwacht is (altijd leeg)"`, where it was `"; de regel 'standaardstormloon' gaf
  geen waarde en besliste niets"`, and `"J4 heeft geen berekende stormtoelage, want geen regel gold; de regel
  'stormtoelage' gold op geen dag, want niet geldt: hij stormwacht is (altijd leeg)"`, where it was `"J4 heeft geen
  berekende stormtoelage, want de regel 'stormtoelage' gaf geen waarde, omdat hij stormwacht is (altijd leeg)"`. An
  initialisation whose condition holds only on days that a rule or the input already filled held only on days that
  already had a value: `"; de regel 'standaardstormloon' gold alleen op dagen die al een waarde hadden: hij stormwacht
  is (van 01-03-2025 tot 01-06-2025)"`, where it was `"; de regel 'standaardstormloon' gaf geen waarde en besliste
  niets"`. A writer that held on some empty day and gave leeg there still gave no value. Over uchar's goldens 30 of
  5145 sentences change, all of the first kind; the answer is unchanged.
- **A second filling has its own kind** (uchar's card `tweede-vulling-als-eigen-fouttype`): a fact creation that gives
  a to-one role a second, different filling (EF-017, uchar's `SecondFillingFault`) is a fault of the kind
  `SECOND_FILLING_FAULT` in the 422, where it was `OTHER`, and uchar's sentence names the object by its id (`de
  naar-één-rol "haven" van "J1" heeft twee verschillende vullingen; …`), where it named its type (`van een Jutter`).
  The kind `OTHER` is gone: uchar's branch puts no fault at the fixpoint outside the types its README names
  (`Fault.KINDS`), and each has its own kind.
- **Names as uchar's case reader reads them** (uchar's card `caselezer-zoals-ref` and IR-SPEC ch. 2.1): a name in a
  request or a descriptor is compared as uchar's case reader compares it, a run of spaces and tabs as one space, A–Z
  as a–z and every other character as written (`Namen.norm`), where the service compared case-insensitively and took
  whitespace as written. `" vondst"` and `"Aangemelde  Vondsten"` name `Vondst` and `aangemelde vondsten`, where they
  were a 400 `UNKNOWN_FIELD`; an output selector with a tab starts the service, where it refused the start; a capital
  outside A–Z no longer folds (`COÖRDINAAT` names no `coördinaat`). A role written at an instance is read by its
  declared name or declared plural, as uchar reads a role key; a plural the model does not declare is a 400
  `UNKNOWN_FIELD`. The answer names an attribute or characteristic of `output` in its declared spelling, where it
  repeated the spelling of the selector.
- **Parameters read by uchar** (card `end-state-and-service-reads`): the service writes the parameters into the case
  it hands uchar's input step, the descriptor defaults and the values of the parameter sets under the request's own,
  and uchar binds them on the session (`docs/ENGINE.md`). A parameter a rule reads that nothing binds is a 422 with
  uchar's EF-014, where the rules computed with an empty value. Of the values a request gives with more decimals than
  declared, the 422 names the first uchar's input step reads, the attributes before the parameters, where it named
  every parameter and then the first attribute. A value of a parameter set that uchar's input step refuses (more
  decimals than declared, a date outside the case lexeme) refuses the start, where the service bound it unchecked.
- **The fault kind of a 422 is uchar's code** (card `end-state-and-service-reads`): every element of `faults` in a 422
  `EVALUATION_FAULT` and in `fixpoint.faults` carries `code`, the `EF-###` of uchar's RUNTIME-SEMANTICS ch. 5.7b, and
  `params`, an object of strings, as uchar's public fault API names them, where it carried `kind`, a class of the
  service per Java type of uchar's exception (`COMPUTE_FAULT` covered several codes). `kind` is gone. A fault of which
  uchar names no kind has `"code": null` and `"params": {}`, where it had `DIVISION_BY_ZERO`, `INPUT_FORM_FAULT` or
  `CHARACTERISTIC_PERIODS_FAULT`. A 422 `NO_FIXPOINT` carries `code` and `params` too: EF-015 at the creation budget.
  A cycle in the links of the switch relation of an instance-ordered cycle is a 422 EF-033, where it was a 500.
  `docs/ENGINE.md` has the codes; the OpenAPI schemas `EvaluationFault`, `NoFixpoint` and `Fixpoint` describe them.
- **A number in the decimals of its type** (card `number-scale-in-answer`): the answer writes a number of a type with
  a finite number of decimals in exactly those decimals, as uchar writes a number in text (RUNTIME-SEMANTICS ch. 1.2),
  in `instances`, in a segment, in a cell and as the text of a value in `fixpoint`: `"kwart": 25.00` for a
  `Numeriek (getal met 2 decimalen)` that `zijn opbrengst gedeeld door 4` gave, where it was `25`, and
  `"opbrengst": 100` for a whole number the request wrote as `100.0`, where it was `100.0`. As JSON numbers they are
  the same; a caller that reads them with `BigDecimal.equals` or as text sees the change. A number of the unbounded
  form keeps its own scale (`docs/ENGINE.md`).
- **The explanation of timelines** (card `explain-timelines`): a total over the timelines of members names its members
  with what each contributed, its total and the timeline it came from (`"… het totaal van het bewaartarief van alle
  vondsten van de Jutter (48 EUR): over V1 (36 EUR uit 12 EUR/mnd van 01-01-2026 tot 01-04-2026), …"`), where it
  named no value and no member; and a rule on a timeline target names its computation, per period it decided with
  the operands it read as a timeline in that period (`"… volgens de regel 'loonproduct van een jutter'
  (onvoorwaardelijk): zijn strandloon maal zijn stormtoeslag, tot 01-04-2024 met zijn strandloon (100 van 01-01-2024
  tot 01-04-2024) en zijn stormtoeslag (leeg); van 01-04-2024 tot 01-07-2024 met …"`), where it named its periods and
  no computation. The explanation on the scenario route, on the service and in the playground changes alike.
- **The playground only through its command** (cards `playground-command` and
  `playground-only-through-its-command`): `bin/regelspraak playground [--host H] [--port N]` starts the workbench
  without a descriptor and a source, and offers no `?via=service`, saying why; `bin/regelspraak playground
  <descriptor.service.json> <source.rgs...> [--detail]` starts it beside the service of that model, for
  `?via=service`. `serve` binds no playground route; it no longer reads `PLAYGROUND`, so `PLAYGROUND=true` gives a
  404 on `/playground` where it bound the workbench.

## The pin

The commit the submodule is checked out at is the pin: there is no other version to keep in step. The parent
pom pins Jackson, the ANTLR runtime, Drools and JUnit on uchar's versions, and `scripts/check-pin.sh` fails when
they drift, when the submodule is not initialised or not on the recorded commit, when it has local changes, or when
the recorded commit is not on uchar's main (`git -C uchar merge-base --is-ancestor HEAD origin/main`). The pin must
be a commit of uchar's main because the reactor here does not run uchar's suites: uchar runs them, with its whole
gate set, before a commit lands on its main. The script fetches nothing; when the pin is on main but the submodule's
`origin/main` is older, it fails and says to run `git -C uchar fetch origin`. To move the pin:

```
git -C uchar fetch && git -C uchar checkout <commit on uchar's main>
make gates-no-docker
git add uchar
```

A pin that changes uchar's corpus can move a count the gates pin (the replay's `EXPECTED_REPLAY_PLAYED` and
`EXPECTED_REPLAY_CASES`, the case counts of the twins); such a count is set deliberately, with its reason in the
script.

## The gates

`make gates` is the check before every landing. It runs the gates on this machine (`scripts/gates.sh`), with one
summary line per step in the order below and a closing line; the full output of each step lands in `out/gates/`.
Steps 1 and 2 run one after the other; steps 3, 4 and 5 then run at the same time; step 6 and then step 7 run after
those three:

```
1 scripts/check-pin.sh        # the submodule is initialised, clean, on the recorded commit, which is on uchar's main;
                              # the versions are uchar's
2 mvn -B -o verify -Dmaven.test.skip.exec=true
                              # the reactor: the suites of toolchain, service and service-quarkus, each holding its
                              # test count to a floor (tools/check-skips.sh); uchar's modules without their suites
3 make check-twins            # the three dialect twins and the vocabulary twin, over HTTP, with their case expectations
4 make check-service-replay   # every golden uchar's gate 12 plays, replayed through the service
5 make scenario-test          # the golden TOKA scenario manifest
6 make image                  # the service image ("The container")
7 make kind-smoke             # that image in kind: the golden scenarios over HTTP, the marker, a rollout
```

A red step is reported with the last lines of its log, and no step after it runs; when one of steps 3, 4 and 5 is
red, the other two still finish and are reported, and steps 6 and 7 do not run. Steps 3, 4 and 5 share no port and no
file: each service they start takes a free port, each step writes its own log and temporary directories, and none of
them rebuilds the jar the reactor built (`bin/regelspraak jar` builds it only when a source is newer).

It needs, beside the checked-out submodule and the Maven repository of a first build: Java 21, Maven, python3,
Docker, kind, kubectl and helm. `make gates-no-docker` runs steps 1 to 5 in the same way and needs no Docker: green
there means the distribution builds and answers as uchar does. No hosted CI runs the gates; `TAG=`,
`DESCRIPTOR_SRC=` and `CLUSTER=` pass on to the image and the kind smoke.

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
  case file outside `EXPECTED_CHANNEL`) and checks its size against uchar's `EXPECTED_PLAYED` (379). Each golden is
  served from its `source.rgs`, or without one from its `ir.json`, with one entry point that carries every form. The
  replay excludes a case, not a golden: a golden plays when every case the service carries plays. The numbers of
  goldens and cases that play are pinned (`EXPECTED_REPLAY_PLAYED`, 370; `EXPECTED_REPLAY_CASES`, 1038); every case
  that does not play is listed by golden and case file with its reason (10 cases of 9 goldens, each with a scalar on a
  time-dependent attribute, which the request does not carry), and every golden the service does not start on by name
  (1, whose document holds an `Unresolved` node); a deviation always fails, and the list fails when it goes stale in
  either direction. An attribute or characteristic of the answer is compared by its name under uchar's name equality
  (`Namen.norm`), since the answer spells a name as declared and a case as it writes it. Every golden that plays is asked with
  `?explanation=true` as well, and an explanation the service does not give fails it. A red leg sends every red case
  of uchar (`uchar/corpus/red`) on which uchar's gate 8 lets the Drools branch fault as a request, and the 422 must
  carry the pinned `code` with the pinned `params` as a subset, as gate 8 holds them (`EXPECTED_RED_FAULTS`, 64); the
  cases the service refuses first, with a 400 or by not starting on the document, stand in `RED_REFUSED` with the
  reason (13), and that list fails when it goes stale in either direction. About 90 seconds with four services at a time (`REPLAY_WORKERS`; 88 s measured 2026-10-22, 238 goldens).
- `scenario-test` runs the 32 lines of `examples/toka-scenarios/manifest`; the three golden TOKA scenarios uchar's
  scenario resolver refuses are listed in `scripts/toka-manifest-exclusions.txt` with a fragment of that refusal,
  and a listed scenario uchar accepts fails the run.

## Everyday use: `bin/regelspraak`

`bin/regelspraak <command>` is the one entry for a user, a script or another repository: it takes no class name
and no build path. Several sources are read as one model, concatenated in the order given, and paths are relative
to the directory the command is given in.

The messages of uchar are Dutch unless asked otherwise: `bin/regelspraak --language en <command>` renders them in
English, and the playground of the service does so per request for an `Accept-Language` that names `en`
(`MessageLanguage`). The toolchain's own messages keep their Dutch sentences; a line of `check`, `drl` or `scenario`
that carries a value or sentence of uchar is labelled in uchar's language, so that under `--language en` a failed
expectation reads `expectation 1 FAIL: characteristicPresent Z1 zware zending  (expected=present, actual=absent)` and
a refusal `not executable in uchar (exit 3): transpiler SKIP: …`. The explanation (`--explanation`), the verdict lines
of `scenario --manifest` and the messages the service itself writes keep their Dutch words.

| Command | What it does |
|---------|--------------|
| `check <source.rgs...>` | parse and resolve: the model summary and the diagnostics (in a chain of files as `file:line`), exit 1 on an error; then the build on uchar's exec-model branch, exit 1 on a name the resolver bound to no declaration (an `Unresolved` node in the document: `ERROR (line 7): onopgeloste naam 'hij een minderjarig is' in de regel 'bijvoeglijk'`), exit 3 when uchar does not carry the model, also when every such node is a form without a norm (RGS-092: the eleven test, a time-of-day part, `regelversie X is gevuurd`; `vorm zonder norm: <reason> — '<snippet>'`) |
| `ir <out.json> <source.rgs...>` | the `uchar-ir-v1` document of the model (uchar's emitter); exit 0 also when the document holds an `Unresolved` node (a name the resolver bound to no declaration, or a form without a norm): the document is written and well-formed, uchar's WF-113 lists such a node in its `diagnostics`, and an executor faults only where an evaluation reaches it (WF-044), so `check` and `drl` judge them, not `ir` |
| `cases <dir> <source.rgs...>` | the scenarios of a test document lowered to uchar case files in `<dir>`, beside the IR document they run against (`<dir>/ir.json`): the layout of uchar's corpus |
| `tokens <out.json> <source.rgs...>` | the semantic tokens of the sources (uchar's `Resolver.tokens`), for a consumer that colours the rules: per file, named as given, the spans that cover its text, each with a kind (`keyword`, `objectType`, `attribute`, `parameter`, `unresolved`, …), a position (line from 1 in that file, column from 0 in UTF-16 code units) and for a reference the place of its declaration, also in another file; and the diagnostics with code, file and line. Written also when the model has an error or a syntax error, with exit 0; the diagnostics go to stderr as well. Sources that carry a block of the scenario layer (`Situatie`, `Parameterset`, `Scenario`) are a test document and are read as `cases` reads them, with its diagnostics and the kinds `label`, `situation`, `parameterSet` and `scenario`. The form, the kinds and their LSP token types: uchar's `frontends/regelspraak/grammar/DIVERGENCES.md`, "Semantic tokens" |
| `scenario [--trace] [--explanation] [--vocabulary <name>] <source.rgs...>` | lowering, uchar's replay and the report: PASS, FAIL or MARKER per scenario; exit 1 on a FAIL and on a source without a Scenario block, 3 on a MARKER; `--vocabulary` names the rules of the trace and the declarations of the explanation in a vocabulary of the model |
| `scenario --manifest <manifest> [--exclusions <list>]` | every line `<verwachte-exit> <scenario.rgs> <companion.rgs...>` of a manifest, in one JVM; exit 0 when no line mismatches |
| `serve <descriptor.service.json> <source.rgs...> \| <document.json> [--allow-markers] [--detail] [--host H] [--port N]` | the decision service on the JDK `HttpServer` (`docs/ENGINE.md`); `--detail` or `DETAIL=true` answers `?trace`, `?coverage` and `?fixpoint`; it binds no workbench route; it listens on `0.0.0.0`, every interface, where the container and the chart reach it, unless `--host` names another address |
| `playground [--host H] [--port N]` | the workbench alone, without a descriptor and a source (`GET /playground`, `POST /playground/analyse`; `docs/ENGINE.md`): it analyses the source the page posts; it serves no model, so `?via=service`, the comparison of a scenario with the answers of a served model, is a 400 that says why, and the page says the same; it listens on `127.0.0.1`, this machine alone, unless `--host` names another address: `--host 0.0.0.0` opens it to other machines |
| `playground <descriptor.service.json> <source.rgs...> \| <document.json> [--allow-markers] [--detail] [--host H] [--port N]` | the workbench beside the service of that model, in one process: the routes of `serve` and the two of the workbench; with `--detail` or `DETAIL=true` its `?via=service` computes the scenarios of the posted source through the served entry points; workbench and entry points listen on `127.0.0.1`, this machine alone, unless `--host` names another address: `--host 0.0.0.0` opens them to other machines |
| `openapi [--vocabulary <name>] <out.json> <descriptor.service.json> <source.rgs...> \| <document.json>` | the OpenAPI document of the service, byte for byte what its `GET /openapi` sends (`docs/ENGINE.md`): the service is built as `serve` builds it, with markers allowed; `--vocabulary` names the declarations in a vocabulary of the model (`canonical`: the canonical names), an unknown one is exit 2. The description of an object type, an attribute, a characteristic and a parameter starts with the comment above its declaration |
| `drl <source.rgs...>` | the DRL text of uchar's DRL-text branch, or exit 3 with uchar's refusal; exit 1 with uchar's sentence when that text does not compile (a fault of uchar's rendering), and on a name the resolver bound to no declaration, as `check` |
| `graph [--mmd] <out> <source.rgs...>` | uchar's rule graph as an HTML page, or as Mermaid text |
| `facts <outdir> <source.rgs...>` | the fact beans uchar's generator writes for the model |
| `jar` | the path of the jar the commands run from, after the rebuild below when it is due; the service gates call it once before they start their services side by side |

Exit 2 is a wrong invocation, a source that cannot be read included, and an output file in a directory that does not
exist. Every command runs from one jar the reactor builds. In a checkout `bin/regelspraak`
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
value stands as "(leeg)" on every attribute, and `onbekend` is only the absence marker; a member the engine did not
reach is not named, and a condition that did not hold names the member where the engine stopped. The engine reads a
number exactly: a value that does not terminate stands as its
fraction, as uchar's reading writes it (`vóór de afronding 100/3 EUR`), and a value of the unbounded form (`Numeriek
(getal)`) with every decimal it has. A timeline the engine read stands as its periods, as RegelSpraak writes them
(`5,00 van 01-12-2023 tot 01-01-2024`, `0,00 vanaf 01-03-2024`). uchar's scenario resolver refuses three of the 29
golden TOKA scenarios (listed with their reasons in `scripts/toka-manifest-exclusions.txt`).

The decision service serves a descriptor over `.rgs` sources or over one `uchar-ir-v1` document; `docs/ENGINE.md`
describes its routes, its MARKER answer and the dev-only playground. A scope that names a base rule also carries the
groups uchar makes of the own exceptions of its subtypes (`<basis> voor <subtype>`), and a scope that names such a group
carries its base. It checks every scope at the start: a form in the scope (a rule, a defaults group, an object creation,
an allocation, a consistency, a uniqueness or a fact creation) that reads an attribute or characteristic only forms
outside the scope write refuses the start, naming the reader by its kind and name (for a group also the default that
reads, by its text and path), unless the entry point declares that element in `inputElements` or sets
`scopeCheckOptOut`. A descriptor with a key its form does not have, at any level, does not start (a `MALFORMED`
violation on the key's path, `entrypoints[0].outputs`), nor one whose entry point paths are not each one segment of
the URL of its own: a path twice, `info`, `openapi`, `playground` or a path with a `/`.

`GET /openapi` serves the service's OpenAPI document, `?vocabulary=<name>` in a vocabulary of the model; `bin/regelspraak
openapi` writes the same bytes without a running service. A caller reads there per entry point the input object types
and output selectors, and per object type its attributes and characteristics with datatype, unit, markers, timeline
form and dimensions. The `description` of an object type, an attribute, a characteristic and a parameter starts with
the comment the author wrote directly above its declaration, verbatim, and then says the form; a service started on a
`uchar-ir-v1` document has no comment there, since the IR carries none.

## Vocabularies

uchar lets a model declare named vocabularies and give each declaration a designation in them (`Vocabulaire
handboek`, `(handboek: totale premie)`; uchar's `spec/IR-SPEC.md` ch. 2.12). The designations carry no semantics:
rules are written and computed in the canonical names. The distribution uses them at the edge, as one service:

- **Input is liberal.** A request may write every name — object type, attribute, characteristic, fact type, role,
  parameter, enumeration value — canonically or as its designation in any vocabulary, mixed within one request, as
  uchar's case reader compares names (a run of spaces and tabs as one space, A–Z as a–z, every other character as
  written; `Namen.norm`), within the scope of the name (the members of an object type with the inherited ones, the
  roles of a fact type, the values of a domain, the rest model-wide). A role written at an instance is read by its
  declared name or its declared plural. A violation names its field as the request wrote it.
  A name that reads as two different declarations is a 400 `AMBIGUOUS_NAME` whose sentence names both readings; the query parameter `inputVocabulary=<name>` pins
  the vocabulary the names are read in (`canonical` for the canonical names), which resolves it.
- **Output is chosen.** `vocabulary=<name>` names the declarations of the answer, its inconsistencies and its
  explanation in that vocabulary; without it the entry point's `vocabulary` from the descriptor applies, else the
  canonical names. An unknown vocabulary is a 400 `UNKNOWN_VOCABULARY` naming the declared ones.
- **Complete or not.** A declaration without a designation in the chosen vocabulary keeps its canonical name. uchar
  requires a designation of every declaration only in the default vocabulary; `/info` lists per vocabulary whether it
  is complete and which declarations have none.
- **One OpenAPI document per vocabulary.** `GET /openapi?vocabulary=<name>` (`OpenApiEmitter.emit(descriptor, model,
  vocabulary)`) gives the contract in the names of one vocabulary (or canonically); each property, value and entry point lists its other spellings in
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
- **A scalar on a time-dependent attribute in a service request.** The request carries the timeline of a
  time-dependent parameter and of a time-dependent attribute and the periods of a time-dependent characteristic, but
  no scalar on a time-dependent attribute (uchar's replay places one in the period of the evaluation date, the
  asOfDate-bucket convention); the service refuses it as `UNSUPPORTED`. The answer gives no time-dependent
  characteristic. 10 cases of 9 goldens gate 12 plays need a scalar there; the replay gate lists them per case.
- **The other variants of a period.** The sentence of a period of a defaults group on a time-dependent attribute
  names the variant that decided, the conflict or the input that stood (`DroolsBackend.periodDecisionsOf`), and not the
  variants before or after it in that period: uchar reads each default once per object, not per period.
- **A `uchar-ir-v1` document is served whole**: a scope that drops forms needs the model the document came from.
- **A sum type other than that of absence markers.** The request, the answer and the OpenAPI document carry the sum
  type uchar's frontend writes for an attribute with absence markers. Another sum type, which only an IR document of
  another producer declares (whose `Match` uchar's Drools branch refuses), has no form there: a value on it is a 400
  `UNSUPPORTED`, it refuses the start in `output`, and the OpenAPI emitter fails on it.
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
