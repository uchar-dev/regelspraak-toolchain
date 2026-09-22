# Grammar divergences — RegelSpraak frontend

Tracked divergences of this frontend's grammar from the RegelSpraak **v2.3.0**
specification (the arbiter). Each divergence is either a reconstruction of a
defective source production (noted at the rule in the `.g4` files) or an
**additive dialect extension** that is *inert on official v2.3.0 documents*.
The lexer/parser headers carry the per-rule rationale; this file records the
extensions and their lowering at one place.

The frontend takes the same inert-extension posture the grammar already uses
for `Regelgroep` (§9.10 is prose-only in v2.3.0) and the scenariotoetsen test
layer: the extension's opening tokens occur **nowhere** in the flat v2.3.0
corpus, so an official document parses byte-for-byte as before and emits an IR
with no extension keys.

---

## §4 The prioritized-defaults extension — `Uitzondering op` / `Uitzonderingen ongeordend`

Origin: `spec/proposals/PRIORITIZED-DEFAULTS-EVOLUTIE.md` §4 (ratified
2026-08-29, Q1a). The uchar IR carries a `PrioritizedDefaults` group — a
prioritised default calculus (one writer, one target, defaults in descending
priority; `spec/RUNTIME-SEMANTICS.md` ch. 7).
Official RegelSpraak v2.3.0 has **no** exception, priority, default-override or
rule-ordering construct, so a flat RegelSpraak document can never denote
prioritized defaults. This dialect extension adds a source channel for authoring
them.

**Recognition only ever advises; it never folds.** A flat rule family is NEVER
transformed into a defaults group — the priority prioritized defaults encode is
not recoverable from a source that never wrote it
(PRIORITIZED-DEFAULTS-EVOLUTIE §4.2). Only the extension's own blocks lower to
defaults groups.

### Surface syntax (additive; new top-level alternatives)

Two forms sit beside `regel` / `regelGroep` / `beslistabel` in
`regelSpraakDocument`:

**Ordered exception** — stacks an exception above a named base rule:

```
Regel <basisregelnaam>
    geldig <versie>
        <resultaat>            (kenmerktoekenning of gelijkstelling)
        [indien <voorwaarde>]

Uitzondering op <basisregelnaam>: <uitzonderingsnaam>
    geldig <versie>
        <resultaat>            (zelfde doel als de basisregel)
        [indien <voorwaarde>]
```

New tokens: `UITZONDERING_OP` (`'Uitzondering op'`, a whole-phrase token). The
base name runs up to the `:`; the exception's own name is an ordinary
`regelNaam` (up to `geldig`), so the body's `regelVersie+` is reached exactly as
a §13.4.2 rule's is.

**Unordered group** — the only §9.5-conflict surface:

```
Uitzonderingen ongeordend:
Regel <lidnaam-1>
    geldig <versie>
        <resultaat>            (één gedeeld doel)
        [indien <voorwaarde>]
Regel <lidnaam-2>
    ...
```

New tokens: `UITZONDERINGEN` (`'Uitzonderingen'`), `ONGEORDEND`
(`'ongeordend'`). Like `Regelgroep`, the group scopes the **consecutive**
`regel` constructs that follow it and ends at the first non-rule construct, so
it is inert on any non-extension document. `UITZONDERINGEN` is never a prefix of
`UITZONDERING_OP` (`'Uitzonderingen'` has no space after `Uitzondering`), so the
two whole-phrase/keyword tokens never collide under maximal munch.

### Lowering (resolver)

Both forms LIFT already-resolved rules out of `resolvedRegels` into
`prioritizedDefaults`: WF-050 forbids both a rule and a defaults group on one
target, so the lifted rule's semantics live in the defaults group alone. Each
lifted rule keeps a `Regel` stub in `regels` (mirroring the hand-written
prioritized-defaults anchors).

- **Ordered:** the base rule and its `Uitzondering op` blocks become ONE
  `PrioritizedDefaults` group. Defaults descend in priority — the exception
  default(s) above, the base default below. With multiple exceptions on one base,
  the **later-written** block stacks **higher** (PRIORITIZED-DEFAULTS-EVOLUTIE
  §4.2). A characteristic base yields a
  `besluit`-carrying default (a RegelSpraak kenmerk assignment is monotone `waar`,
  §9.2 — the frontend never emits `besluit: false`); an attribute base yields a
  `consequentie`-carrying default. The base rule's geldigheid is carried onto the
  defaults group.
- **Unordered:** each `Uitzonderingen ongeordend` block becomes SIBLING
  declared-unordered defaults groups (one per member) over one shared target — the
  `toelagegronden`-anchor shape. Every sibling carries `ongeordend: true`
  (WF-050: two-or-more defaults groups on one target require the marking on every
  one).
  Members deciding differently on one target route to the §9.5 Inconsistentie
  channel; deciding the same reconciles idempotently. Because a kenmerk
  assignment is monotone `waar`, a genuine §9.5 conflict is only expressible on
  an **attribute** target.

### Fail-fast refusals (resolver, loud diagnostics)

- an `Uitzondering op` naming a base rule that does not exist;
- an exception (or group member) writing a DIFFERENT target than the base
  (or than a prior member) — a defaults group is over ONE `(onderwerp, kenmerk XOR
  attribuut)`;
- a base rule that is not a plain kenmerktoekenning/gelijkstelling (an
  initialisatie, or a verdeling/consistentie/objectcreatie/uniciteit);
- a base rule or exception with more than one `geldig`-versie (not carried by
  this first card).

### Emission (v0-mirror invariant)

`prioritizedDefaults` is emitted ONLY when the extension produced defaults
groups. A document without the extension emits no such key, so the v0-mirror
byte-identity of every flat golden holds. Within the list, `attribuut` is emitted
only on attribute defaults and `ongeordend` only when true, matching the schema
and the hand-written prioritized-defaults anchors.

### Scope / limitations of the first card

- Only the kenmerk form is exercised for the ordered shape (an attribute-target
  ordered exception is expressible by the same machinery but not needed for
  the first goldens).
- Exception-on-exception (nested `Default.excepties`) is not authored from source
  in this card; the wire form supports it and the hand anchors witness it.
- Multi-version base/exception rules are refused (single-version only).
- The new keyword tokens narrow the ordinary-name space: `Uitzonderingen`
  (capitalised) and `ongeordend` are reserved and can no longer appear as
  name words (e.g. a plural `(mv: Uitzonderingen)` or a kenmerk named
  `ongeordend` now refuses loudly at the lexer) — the same loud posture the
  existing `Regelgroep`/`recursief` extension tokens take. The corpus and
  every committed source are free of these words.
- An exception whose validity window differs from its base refuses loudly:
  the defaults group carries one window and a per-default window is
  unrepresentable — split per window or align the windows.
- An UNCONDITIONAL base rule (no `indien`) lowers to a default carrying the
  vacuously true justification — an `ALLE`-group over no parts, the same
  "altijd" the hand-written prioritized-defaults anchors spell. WF-050
  requires every non-initialisation default to carry a justification, and a
  flat rule without a condition is the ch. 7.8 standing norm: as the lowest
  default of a group it stands whenever nothing above it decides.

---

## §5 The subtype extension — `is een`

Origin: `spec/proposals/SUBTYPING.md` §9.2, under the maintainer's mandate for
object-type hierarchy (2026-09-10). The uchar IR carries `Objecttype.supertype`
— one direct parent by declared name (`spec/IR-SPEC.md` ch. 2.2,
`spec/RUNTIME-SEMANTICS.md` ch. 7.10). Official RegelSpraak v2.3.0 has **no**
inheritance construct at all: a flat document can only repeat a supertype's
members on every subtype, which is what the canonical data model this corpus
mirrors does *not* do. This dialect extension adds the source channel for
declaring the relation the IR already carries.

### Surface syntax (additive; one optional element in an existing production)

```
Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
    de bergingsdiepte Numeriek (niet-negatief geheel getal);
```

The clause sits **after the object-type name and before the `mv:` group**. It
does not touch the order of the existing head annotations, which the grammar
already fixes (`mv:` before `(bezield)`), and both remain reachable behind it.

**No new lexer token, deliberately.** A whole-phrase `IS_EEN : 'is' WSP 'een'`
is not safe here and the `IS_VAN_HET_TYPE` precedent does not carry over.
Lexing is not head-local: under maximal munch such a token fires wherever the
phrase occurs, and the phrase already occurs in ordinary **rule text** ("Een dag
is een schouwdag"), so it would change the IR of documents that have nothing to
do with subtyping. `IS_VAN_HET_TYPE` is safe only because its phrase occurs
nowhere else. The extension therefore sits at the **parser** level: the existing
`IS` and `EEN` tokens in sequence at the head position, where the surrounding
context disambiguates. A §13.3.2 kenmerk declaration is `IS lowerNaam …`, and
`naamWoordLower` excludes `EEN`, so no member declaration can be read as this
clause and no clause can be read as a member. The extension consequently
reserves **no** name words: unlike `Uitzonderingen`/`ongeordend`, both its
tokens already existed and their other uses are untouched.

The article before the parent name is the language's own `onbepaaldLidwoord`,
read exactly where the language reads one elsewhere; it is required, so a bare
`is <Naam>` is not the head form and does not bind.

### Lowering (resolver)

`is een <Naam>` records a parent NAME; the binding happens once every
declaration has been read, so resolution is **document-wide and order-free** —
a forward reference is legal and only an undeclared name refuses. The bound
parent lands in `Objecttype.supertype`, whose chain accessors (`keten`,
`isEen`, `attribuut`, `heeftLid`, `kenmerkSoort`, `declarantVan`) the model
already carried; the member maps stay OWN, so a subtype declares only what it
adds.

### Fail-fast refusals (resolver, loud diagnostics)

The frontend refuses BEFORE the verifier does, because it has the chain at
hand. Each refusal cites its rule:

- **WF-106** — more than one direct supertype, in either spelling (two
  `is een` clauses on one head, or `is een A en B`). The grammar deliberately
  ADMITS both shapes so the refusal can name the parents instead of degenerating
  into a syntax error, and the message names the two known
  multiple-inheritance cases (`RechtOpUitkeringWazo`, `RechtUitkeringZw`) as
  refused rather than flattened. This refusal is one of that rule's only two
  witnesses anywhere: on the wire `supertype` is a bare string, so a second
  parent cannot be WRITTEN, and WF-106 carries no verifier check and no
  `corpus/negatief` case (`spec/IR-SPEC.md` ch. 2.2). A head repeating ONE
  parent is redundant, not a second parent, and is not refused.
- **WF-104** — self-reference (`X is een X`), and a cycle of two steps or
  more, reported with its named path. The back edge is cut after it is
  reported, so every later pass terminates on a document already rejected.
- **WF-103** — a parent name nothing declares as an object type. When the
  document declares that name as something else, the message says which kind
  (a domein, a parameter, a feittype, a dimensie, an eenheidsysteem, a
  dagsoort) rather than sending the author hunting for a typo.
- **WF-105** — a member name declared twice along one chain (ci, per kind) is
  refused, at the redeclaration's own line. What is **not** refused, and
  deliberately, is the **sibling** collision — two incomparable subtypes of one
  supertype each declaring the same name — since no instance is of two
  incomparable types, so no read is ambiguous.

A misspelled head form is a bare ANTLR "no viable alternative", which points
the author at the wrong thing, so two common slips get a targeted message
beside it (never instead of it): `is <Naam>` with the article dropped — an `IS`
after the name is otherwise a §13.3.2 kenmerk declaration, but that continues
with a `lowerNaam`, so a capitalised name can only be this — and the clause
written behind the `mv:` group. Both are read off the token stream over the
HEAD only, so the `is een` of ordinary rule text is never mistaken for either.

Explicit **non-**refusal: a target carrying rules on two levels. That is the
kernel's group inheritance (`RUNTIME-SEMANTICS.md` ch. 7.10), and the kernel
witnesses are the semantic referee.

### Emission

`supertype` is emitted ONLY for a type that has one, directly after `naam`
(the schema's property order). A root type emits no key, so the v0-mirror
byte-identity of every flat golden holds.

One emission consequence comes with the relation rather than with the syntax.
A role's declared filler type is an **upper bound** (`IR-SPEC.md` ch. 2.6), so
WF-048's uniqueness is computed over the **chain-closed** type-pair set: adding
a subtype can make a pair that was uniquely connected doubly connected, and a
fold that went out bindingless yesterday would be refused today. The emitter is
ahead of it — where the by-pair search goes silent, the fold's `bronRol` falls
back to the role the sentence WROTE, which still names exactly one.

That fallback carries one tightening the other role searches do not need, and
it is what makes it safe. The name candidates are formed by dropping leading
words, so a truncated phrase can land on a same-named role in **another** fact
type; the upper-bound filler test that is right everywhere else would wave that
through, because the element type is a subtype of the other role's filler. The
reached role must therefore declare **exactly** the element type — identity, not
`⊑`. A phrase naming no role, naming more than one, or naming one whose filler
is not the element type itself stays bindingless, and gets the loud WF-048
refusal. Binding the wrong relation would instead be verifier-clean and
silently fold the wrong set, which is by far the worse of the two.

### Scope / limitations of this card

- Only `Objecttype` carries the clause. `Extensie van objecttype` (§13.3.1.2)
  re-opens a type to add members and is left alone: a second declaration site
  for one relation would immediately reopen the second-parent question across
  constructs.
- Multiple inheritance is refused, not modelled (mandate decision 2).
- The clause declares the relation only. Nothing about the defaults-stack
  composition is authored from source; that is the kernel's, keyed on the
  relation this extension writes.

---

## §6 The filled-value-count extension — `het aantal gevulde waarden van`

Origin: `spec/IR-SPEC.md` ch. 4.3 and `spec/RUNTIME-SEMANTICS.md` ch. 3.4c
(maintainer decision G9, 2026-09-19). The uchar IR carries an aggregation kind
`AANTAL_GEVULDE`: over one set source it counts the contributors whose value is
not leeg, so `{5, 7, leeg}` answers **2** where the instance count answers 3.
The kind was written on the wire first and answered by all four executors
before any frontend could author it; this extension is its producer route.

**Official RegelSpraak v2.3.0 has no spelling for the operation at all.**
§5.8's `<getalaggregatiefunctie>` is a closed alternation of four phrases (the
count, the maximum, the minimum, the sum), with two more for dates; there is no
fifth number form and no place to write one. `gevuld` exists in the language
only as a PREDICATE — §8.1.2, typing §5.11, a boolean test on one subject
expression — and never as an aggregation. So a flat document can neither write
this fold nor mean it.

### Surface syntax (additive; one new alternative of `functieAanroep`)

```
Het aantal gewogen vondsten van een Jutter moet berekend worden als
    het aantal gevulde waarden van het gewicht van alle vondsten van de Jutter.
```

New token: `AANTAL_GEVULDE_WAARDEN_VAN`, a whole-phrase token
(`'het aantal gevulde waarden van'`). The operand is a `primaireExpressie`, for
the same precedence reason the §13.4.17.47 telling gives: a trailing
`plus`/`maal` binds OUTSIDE the count, which is what an author means. The
alternative carries **no facet slot** — no `leegwaardeAlternatief`, no
`dimensieSelectie`, no `conditieBijExpressie` — because all three are ill-formed
on this kind (WF-037's "sum only" and "sum/min/max only", and decision G7). A
facet that cannot be written needs no refusal.

**The extension reserves no name word.** Unlike `Uitzonderingen`/`ongeordend`
(§4), a whole-phrase token takes only the whole phrase: `gevulde` and `waarden`
keep lexing as `LOWER_ID` everywhere else, and an attribute or role whose name
merely begins with "gevulde" is untouched. Only the exact five-word sequence
changes meaning — and under maximal munch it takes precedence over
`HET AANTAL_WORD`, which is the risk this section measures.

### Inertness — measured, not assumed

The `is een` extension (§5) could argue inertness from the shape of the
construct; this one cannot, because the phrase *extends* an existing one. A
document naming a role or attribute "gevulde waarden …" would have its count
sentence re-read. So the claim is a measurement, taken 2026-09-20 over **876
flat RegelSpraak documents** in seven collections: the 64 emitter goldens of
this corpus, the 47 official-casus documents of the repository's temporary
parity zone, 176 documents of the reference toolchain's own corpus, 513
documents of an operational ruleset collection, and 76 more across three
further rule collections found on a second pass — plus the six markdown
documents of the v2.3.0 specification set (the main specification, the typing
document, the syntax-diagram document, the ANTLR-targeted draft, the casus
document and its conversion notes).

- **The phrase occurs nowhere.** Zero occurrences of `aantal gevulde` in any of
  the 876 documents, and zero in the six specification documents.
- **The word `gevulde` occurs twice in 876 documents**, both inside `//` line
  comments in the operational collection, where the lexer cannot reach it. The
  single specification hit is the substring inside "ingevulde" in a prose figure
  caption, not a RegelSpraak name.
- **Every document emits byte-identically before and after.** Each of the 876
  was run through the frontend built at the commit before this extension and at
  the commit after it, and the emitted IR plus the diagnostics compared. Zero
  reinterpretations. Two TEXT changes appear and are accounted for, neither of
  them a reading: WF-096's message lists the admissible kinds and that list grew
  by one name, and the ANTLR "expecting {…}" set in a syntax-error message names
  the new token. Normalising exactly those two leaves no residual difference in
  any document.
- **The seventh collection was found by a second reader, not by the first.**
  Three of the seven were missed on the first pass, and the sentence that
  claimed the sweep was exhaustive was therefore too strong. It is recorded here
  because the lesson outlives the number: a coverage claim over "everything
  reachable" is one a second reader has to re-count, and that re-count found
  three. The re-count agreed — 76 of 76 identical, no diagnostic differences —
  so the conclusion widened rather than moved.

**What the measurement does not claim.** It cannot exclude that some unmeasured
document names a role or an attribute with the exact phrase. What bounds that
case is the grammar itself, and both of its failure modes are LOUD:

- a colliding **role or count sentence** — the phrase consumes its `van`, so
  what follows must be the fold's operand, and there the remainder is a to-ONE
  reading rather than a set reading, which the WF-037 refusal below rejects at
  ERROR level;
- a colliding **attribute name** never reaches a rule at all: the phrase is a
  token, so a member declaration containing it breaks on its own declaration
  line with a syntax error ("extraneous input 'het aantal gevulde waarden
  van'"), and the document does not resolve.

The shift is loud in both, never a different answer. That pair — measured
absence plus a bounded failure — is the whole argument, and both halves carry a
fixture (`AantalGevuldeDialectTest`).

### Lowering (resolver)

The phrase builds an ordinary `Expr.Functie` with `kind = AANTAL_GEVULDE` and
one argument, reusing the value folds' machinery rather than adding any:

- the element type is the argument's own object type (`aggregatieElement`), the
  VALUE-fold derivation — not the count's, whose element word is a role name;
- the range is the shared one: a single written step goes out as `bronRol`, and
  a resolved multi-step range as `bronPad` (WF-102), which is ch. 3.4b's flat
  union and the reason the kind exists at that width;
- the `die/dat` subselectie rides the shared `aggregatieFilter` path;
- the result type is a whole, unitless, non-negative number whatever the folded
  attribute measures (ch. 3.4c). The source's unit is deliberately NOT inherited;
  a cardinality has none.
- `meldAggregatie` carries an explicit no-check arm for the kind. Decision G8
  makes the test typeless — presence is asked of a text, a date, an enumeration
  value and a boolean alike — so a numeric-operand check here would undo the
  decision in silence.

`RuleChecker.VOUWSOORTEN` grows with the kind in the same movement, because
WF-096 admits a set reading on a value fold's source alone and a filled count
is a count in its RESULT but a value fold in its READING. That set and the
verifier's `set_source_exempt` are one boundary in two languages;
`VouwsoortenGrensTest` now READS the verifier's own kind list and the line that
keys on it, so the two cannot drift again in either direction.

### Fail-fast refusals (resolver, loud diagnostics)

- **WF-037** — a source that is not a set reading. The kind reads a value per
  contributor, so a to-one source ("… van het gewicht van de Vondst") would ask
  for the filled count of one own value, which is 0 or 1 and a form no author
  writes. ERROR, so the document never reaches disk; every other kind admits a
  non-set source for a reason of its own (the §7.1 timeline fold, the row-1
  count wart), which is why the fence is drawn per kind.
- The refusals the value folds already carry reach the new kind unchanged,
  because it joined the shared aggregation predicate: a source naming no
  declared member of the element type markers with a WARNING, and the three
  fold-source refusals (a nested set step, an intermediate to-one anchor, a
  by-pair binding the written name contradicts) keep refusing here too.

### Emission

Nothing about emission is new: the kind reaches the wire through the same
`Functie` writer as the other seven, with the same `bronRol`/`bronPad`
exclusivity. A document that does not write the phrase emits exactly what it
emitted before, which is the measurement above stated as a property.

### Scope / limitations of this card

- Only the ONE-operand form exists. There is no listed variant ("het aantal
  gevulde waarden van A, B en C"), because the wire has none: WF-037 pins the
  arity at exactly one.
- **The token matches lowercase `het` only**, as `AANTAL_DAGEN_IN` and
  `TOTAAL_VAN` do, so a sentence-initial "Het aantal gevulde waarden van …"
  falls back to the pre-extension reading — the instance count swallows the
  words as its element name and markers with a WARNING. Measured 2026-09-20:
  that document still reaches disk, exactly as it did before the extension. It
  is no regression (the base behaviour is unchanged) and no aggregation phrase
  in this grammar is written sentence-initially, but it is a real asymmetry with
  the lowercase form and is recorded rather than discovered.
- The temporal set source stays refused downstream, not here. A filled count
  over a timeline attribute has no moment at which to ask, and the two OCaml
  executors refuse it by name (`corpus/rood/aantalgevulde-temporele-setbron`);
  the frontend admits the sentence, as it does for the other folds.
- The catala and flogic frontends do not get the kind. Each carries its own
  refusal list, and a wire kind they never write costs them nothing.

---

## §13.4.17.47 The count's operand list — an ACCEPTING superset, fenced in the resolver

Unlike the three sections above this is **not** a dialect extension and it is
**not** inert: it is a production that accepts more than v2.3.0 spells, and it
was found by a wire decision rather than designed. It is recorded here because
the register is where a divergence has to be findable, whether it was chosen or
inherited.

`functieAanroep`'s `aantalFunc` alternative reads

```
HET AANTAL_WORD primaireExpressie ( COMMA primaireExpressie )* ( EN primaireExpressie )?
```

while §13.4.17.46's EBNF gives `<getalaggregatiefunctie>` one `<expressie>`
operand and §5.8.1 ranges the count over **instances**; the typing document
(§4.11) adds that a count takes onderwerpexpressies only, so leegwaarden do not
apply to it. There is a listed spelling in the source language — "de eerste van
A en B" (§5.8.3) — but it belongs to the ordering folds, and the count has
none. The `( COMMA … )* ( EN … )?` tail is therefore this frontend's own
widening, carried over from the neighbouring `minMaxFunc` and
`eersteLaatsteFunc` alternatives that legitimately have it.

**Two divergences live at this rule, and only one of them was registered.** The
`primaireExpressie` operand (instead of the symmetric `expressie`) is a
deliberate, narrowing reconstruction — a trailing `maal`/`plus` binds OUTSIDE
the count, which §4.11 requires — and it is noted at the rule. The operand LIST
is the widening, and until 2026-09-20 it was noted nowhere.

**What it cost, measured 2026-09-20.** `ExpressieBouwer.args` builds every child
as its own argument, so the list reached the wire as an `AANTAL` with two or
more `argumenten`. Three sentences emitted unblocked — the `en` spelling and the
`comma` spelling with `diagnostics: []`, the element-binding spelling ("het
aantal vondsten van de Jutter en kavels van de Jutter") with a WARNING that does
not stop emission — and the uchar verifier rejects all three: under WF-037 since
that day's count-arity decision (`spec/IR-SPEC.md` ch. 4.3), the third under
WF-034 as well. One executor answered such a document and three refused it,
which is the class `spec/PLAN.md` §6 calls a spec fault.

**The fence stands in the resolver, not in the grammar** — `ExpressieBouwer`'s
`tellingAriteitFout`, an ERROR, so `Resolver.irModus` writes nothing and the
ill-formed document never reaches disk. Witness:
`AantalOpgesomdTest` (both spellings, the element-binding route, the
off-disk property, and the two forms that must stay clean).

Why not simply delete the tail:

- It is a **parse** change, not a diagnostic change. `( EN primaireExpressie )?`
  decides how a trailing conjunction binds inside a larger sentence; removing it
  re-associates those sentences instead of refusing them, and 64 byte-identical
  emitter goldens sit downstream of every parse.
- Mirror-then-diverge: the widening is a fact about this grammar, and a fact
  that is deleted stops being registered. Fenced and written down, it stays
  readable; deleted, the next reader rediscovers it from scratch.
- The refusal is the same either way. What differs is only whether the author
  gets a named refusal citing the rule, or a syntax error.

This is the same posture `AantalSetbronTest` records one decision earlier: when
the wire narrows, the frontend's fence moves and its grammar does not.

### Half of it stopped being a divergence (2026-09-20, the unie-telling card)

**The tail is a widening of the EBNF and it always was. What was wrong was the
reading of what it widened INTO.** §13.4.17.46 gives the count one `<expressie>`
operand, and the register above concluded from that — and from §5.8.1 ranging
the count over instances — that a count has no articulated operand list at all.
That is true of an operand list of VALUES and false of the sentence: §5.7 spells
a **concatenation of subject expressions** — the instance sets the selections
yield, joined into one new set — and the typing document's own §4.11 says of the
count operator that a concatenation of expressions may be used with it,
unbounded in number, subject expressions only.
The previous card cited §4.11 for the half that excludes the value reading and
did not read the half that admits the concatenation.

So the grammar's `( COMMA primaireExpressie )* ( EN primaireExpressie )?` is now
**two things at once**, and the resolver is where they part:

- operands that are **rolselecties** ("aangemelde vondsten van de Jutter die
  gemerkt zijn") are the §5.7 concatenation. `ExpressieBouwer.bouwInstantieUnie`
  builds ONE `Expr.InstanceSetUnion` from them — a selection per operand, each
  with its own population, its own one-step relation and its own die/dat
  subselection — and the count carries it as its SINGLE argument. The wire form
  is `InstanceSetUnion` (uchar `IR-SPEC.md` ch. 4.3, WF-110). At this half the
  production is no longer a divergence: it spells what the source language
  spells.
- operands that are **waardelezingen** ("de gewogen vangst van de Jutter") stay
  the widening, and `tellingAriteitFout` keeps refusing them word for word.

The refusal's message gained a TAIL rather than a rewrite, because its head
would now mislead on its own: it says the source language spells no listed form,
which is true of the list and false of the sentence. The tail names the §5.7
route and says why it did not open here — the operands are not rolselecties, or
`bouwInstantieUnie` refused them for a reason of its own. Three such
reasons, each an ERROR that keeps the document off disk:

- **two populations** ("het aantal aangemelde vondsten van de Jutter en
  duinkavels van de Jutter"): one count answers a cardinality over ONE declared
  type (WF-110 (d)). The v0 toolchain refuses the same sentence on a mechanism
  ground of its own — one Drools accumulate has one source pattern — so the
  boundary agrees and the reason does not.
- **an underivable relation** for a selection (WF-110 (e)): a union names each
  selection's relation, never derives one, because every selection of one union
  shares one type pair.
- **a die/dat that is not a characteristic of the element**: a dropped filter
  would count too many AND the union would absorb the error, since an unfiltered
  selection is a superset of every filtered one over the same relation.

Where uchar deliberately carries what the toolchain markers: a **selection
without a subselection beside a filtered one**. Its marker there is the same
mechanism fact — it lowers a concatenation by OR-ing the operands' filter terms
and has no term to OR for an unfiltered operand — and our wire carries the
selections themselves, so `filter: null` is the ordinary case.

Witness: `AantalUnieTest` (the two-relation union, the two-characteristic union,
the unfiltered selection, the comma spelling, the wire shape, the three named
refusals, and the two counter-proofs). Measured red-first on the pre-card
frontend (5b42e5b9): both of this card's golden sources — `jutterswet-vondstunie`
and `jutterswet-keurunie` — were refused with the WF-037 arity ERROR and wrote
nothing.
