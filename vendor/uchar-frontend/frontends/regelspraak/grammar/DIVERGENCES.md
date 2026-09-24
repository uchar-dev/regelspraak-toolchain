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

**Pointers this register does not answer yet.** The frontend came over from the
source toolchain with its comments, and those comments pointed at that
toolchain's own register, a file that does not exist in this repository. Since
the fix round of 2026-09-23 the 24 in the grammar and the main sources point
here (two in existing tests still name the old file), but the text they cited
stayed behind. This register does not carry the sections
`'§9.10 recursie / regelgroep'`, `'§Scenariotoetsen'`, `'negated membership'`
and `"concatenatie as a standalone value"`, nor the untitled topics at the other
sites: the percentage forms and the percentage sign, the unit checks of the
aggregations, the begrenzing bound, the period lowering, the "is gevuurd"
predicate, the mixed date precision, the best-effort role plural, and the
`Regelgroep` extension itself beyond the introduction above. At each site the
comment states its own rationale, and a card that touches such a construct
writes its section here. Two pointers land on text: the count's operand list
(§13.4.17.47 below) and the interaction of `Regelgroep` with the defaults
extension (§4).

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
target, so the lifted rule's semantics live in the defaults group alone. The
model keeps a `Regel` stub for each lifted rule, but since M1 of the v1 wire
(delta B3) the emitter writes no `regels` list, so on the wire a lifted rule
exists only as its default.

- **Ordered:** the base rule and its `Uitzondering op` blocks become ONE
  `PrioritizedDefaults` group. Defaults descend in priority — the exception
  default(s) above, the base default below. With multiple exceptions on one base,
  the **later-written** block stacks **higher** (PRIORITIZED-DEFAULTS-EVOLUTIE
  §4.2). A characteristic rule yields a
  `besluit`-carrying default — `true`, or `false` where its sentence negates the
  characteristic (form 1, below); an attribute base yields a
  `consequentie`-carrying default. The base rule's geldigheid is carried onto the
  defaults group.
- **Unordered:** each `Uitzonderingen ongeordend` block becomes SIBLING
  declared-unordered defaults groups (one per member) over one shared target — the
  `toelagegronden`-anchor shape. Every sibling carries `ongeordend: true`
  (WF-050: two-or-more defaults groups on one target require the marking on every
  one).
  Members deciding differently on one target route to the §9.5 Inconsistentie
  channel; deciding the same reconciles idempotently. On an attribute target the
  members differ in value; on a characteristic target a negated member (form 1,
  below) beside a positive one gives the conflict when both apply — no write and
  one §9.5 fact. Until form 1 a kenmerk assignment could only decide `waar`, and
  the conflict was expressible on an attribute target alone.

### Form 1 — a negated decision in a rule that becomes a default

Origin: maintainer decisions 1–3 of 2026-09-23
(`spec/proposals/DIALECT-DEFAULTS-NIET-EN-NESTING.md` §4 and §10). RegelSpraak
v2.3.0 §9.2 has no negative attribution: a characteristic that no rule attributes
is false (§3.5), so in a flat document a negative attribution adds nothing, and
beside a positive one it is a conflict with no rule to decide it. Inside a
defaults group neither holds: the group is the target's only writer, its
priority is total, and a `false` decision writes nothing (RUNTIME-SEMANTICS
ch. 7.1; ch. 5.3's monotone writes hold). The dialect admits the negation there
and nowhere else.

| Kind (declaration) | Positive result | Negated result |
|---|---|---|
| bijvoeglijk — `is uitgesloten kenmerk (bijvoeglijk);` | `Een Jutter is uitgesloten` | `Een Jutter is niet uitgesloten` |
| bezittelijk — `het recht op de penning kenmerk (bezittelijk);` | `Een Jutter heeft recht op de penning` | `Een Jutter heeft geen recht op de penning` |
| zelfstandig — `de vaste ingezetene kenmerk;` | `Een Jutter is een vaste ingezetene` | `Een Jutter is geen vaste ingezetene`, `Een Jutter is niet een vaste ingezetene` |

These are the negations a condition already accepts (§8.1.8, Tabel 16), and the
resolver reads them the way it reads a condition: the WHOLE written name first,
so a declared characteristic whose name begins with `niet` or `geen` binds
itself; only when the whole name binds nothing is a leading `niet` or `geen`
read as the negation and the rest bound. A verb that does not fit the kind
(`is geen` on a bezittelijk characteristic) gets the condition side's Tabel 16
WARNING, at the line of the sentence; the lowering is the same. That lint checks
the verb against the kind and not the negation word, on both sides: `heeft niet
X` on a bezittelijk characteristic and `is geen X` on a bijvoeglijk one pass
without a message, in a result as in a condition. `is niet een` binds a
zelfstandig characteristic only, as it does in a condition; on another kind the
sentence is refused and the message gives the kind's own spelling (`is niet X`,
`heeft geen X`), since a result cannot stay an unresolved marker the way a
condition can. After `een` no negation is read at all: a condition binds the
name behind `een` whole (the EEN-form), so `is een niet X` and `heeft een geen
X` negate nothing there, and a result refuses them, with the spelling of X's
kind. A positive name keeps its written spelling, as before: the lookup only
decides whether the name is negated.

**Grammar.** `is niet X`, `is geen X` and `heeft geen X` parsed already: `NIET`
is a name word and `geen` lexes as an ordinary word, so both stand inside the
`kaleNaam`. `is niet een X` did not (`EEN` is no name word; the syntax error was
"extraneous input 'een'"). `kenmerkToekenning` has one more alternative,
`onderwerpKeten IS NIET EEN kaleNaam`, the result counterpart of the condition's
`NIET EEN` branch. `Regel` and the extension blocks share the production, so
whether a negation is admitted is decided in the resolver.

**Where it is admitted** (decision 2): in every rule that becomes a default — the
body of an `Uitzondering op`, a member of `Uitzonderingen ongeordend`, and the
base of an `Uitzondering op` chain. A negated base is the lowest default and
decides `onwaar`. In a flat model that changes no outcome, since under the
closed world an unattributed characteristic is false anyway; what the base buys
there is that the author can write a statute that opens with the negation
("geen recht heeft …, tenzij …") and hang the exceptions on a named base, as
Catala's `definition x equals false` does. Under inheritance it does change
outcomes (RUNTIME-SEMANTICS ch. 7.10): on a subtype, its base replaces the base
of an ancestor's defaults group on the same characteristic, so a positive
inherited base stops applying to the subtype's instances; on a supertype, the
group's `onwaar` exceptions stay above a subtype's own base and block it where
they apply. A plain kenmerk rule is no base — ch. 7.10 folds attribute writers
only — so a negated subtype base does not switch off an ancestor's plain
kenmerk rule, which keeps writing on the subtype's instances. All three
measured 2026-09-24 in ref, clingo and the Drools exec branch.

**Lowering.** A negated sentence becomes a default with `besluit: false` on the
target of its group; the rest is the lowering above. Between reading and
lowering the resolver carries the negation as the value `onwaar` on the
resolved kenmerktoekenning. WF-022 refuses that value on a flat rule, so a
plain rule that still carries it after the lift is refused (below), and the
value never reaches the wire.

**What a `false` decision says.** Where it is the highest deciding default of
its group, the rule set does not attribute the characteristic. It does not say
the characteristic is false: a characteristic the case input sets stays set
(RegelSpraak §3.5: input or attribution), and without input the closed world
makes it false anyway.

**Refusals and the warning** (witness: `NietInUitzonderingTest`):

- a negated kenmerktoekenning in a plain `Regel` — no exception, no member of
  an unordered block, no base of a chain — is an ERROR that cites §9.2, §3.5,
  WF-022 and this section ("een gewone regel kan een kenmerk niet ontkennen
  …").
  Measured on the frontend before this card: the sentence went to disk as an
  undeclared characteristic `niet uitgesloten` with `diagnostics: []`, for the
  verifier to refuse under WF-022; in an `Uitzondering op` it drew "schrijft een
  ander doel (Jutter.niet uitgesloten (kenmerk))". A plain negated rule on a
  target that also has a defaults group gets this ERROR alone, without WF-050's
  second-writer ERROR beside it. A rule written in an extension block that was
  refused for another reason stays un-lifted and is judged by that refusal;
- a negation whose rest names no characteristic of the subject (`is niet
  vervallen` with `vervallen` undeclared, and no characteristic `niet vervallen`
  either) is an ERROR naming the rest ("… ontkent geen kenmerk van Jutter —
  'vervallen' is geen kenmerk van Jutter …"); when the rest is a declared
  ATTRIBUTE the message says so, and for a Boolean attribute it points to `moet
  gesteld worden op onwaar`.
  The lowering then skips the block's family instead of adding a
  target-mismatch ERROR. A POSITIVE result on an undeclared characteristic is
  still not refused here: it reaches the verifier's WF-022;
- `is niet een X` on an X that is bijvoeglijk or bezittelijk is an ERROR ("de
  vorm 'is niet een' ontkent een zelfstandig kenmerk (Tabel 16) …");
- a negation written after `een` (`is een niet X`, `heeft een geen X`) is an
  ERROR ("… ontkent niets — achter 'een' leest de frontend de naam als geheel
  …") that gives the spelling of X's kind;
- there is no negated gelijkstelling: an attribute has no polarity. `moet
  gesteld worden op onwaar` on a Boolean attribute stays a value — a
  `consequentie`, never a `besluit` — and `niet` written into an attribute name
  is no negation (the undeclared-attribute refusal of WF-022);
- on a characteristic target where every default of every group decides
  `onwaar`, the groups never attribute the characteristic. That is well-formed
  and draws no ERROR; the RuleChecker gives one WARNING per target ("… kent het
  nooit toe …", with several groups "kennen"), which the emitter writes into
  `diagnostics` (WF-113 (a)). Per target, not per group: an unordered block
  spreads its target over sibling groups, and a target with a negated sibling
  beside a positive one derives through the positive one. The warning is left
  out where the characteristic has another writer in the subject's inheritance
  line: a defaults group there composes with these groups and the all-`onwaar`
  group then changes outcomes (above); a plain kenmerk rule there keeps writing
  beside the groups, and the warning's hint, a missing `waar` exception, would
  point away from it. It is left out beside a plain rule on the same target
  too, which draws WF-050's ERROR instead;
- the refusals above stand at the decision table's own line when the rule is
  a table row (a row is re-parsed in a stream of its own) and name the table;
  a rule with several versions draws one ERROR, not one per version.

**Which documents change.** Only a document with a result sentence whose name,
after an article, begins with `niet` or `geen` and does not bind as a whole, or
with `is niet een` — the forms §9.2 keeps out of official RegelSpraak. Before
this card no such document reached a valid IR: it drew the target-mismatch ERROR
or a syntax error, or it was emitted with a characteristic named `niet …` or
`geen …` that its object type does not declare, which the verifier refuses
(WF-022 on a flat rule, the defaults-target check on a group). The 71 `bron.rgs`
goldens that existed before this card re-emit byte-identically
(gate 10). The golden `jutterswet-schorsing` (Jutterswet art. 3 and 4bis) is the
first whose source carries a negated exception.

### `// Bron:` annotations of lifted rules

A lifted rule leaves `resolvedRegels`, and with it the `bronAnnotaties` list
that carries its `// Bron:` annotation on a flat rule. Until 2026-09-23 the
lowering also set `bron: null` on every defaults group and every default, so
each annotation on an extension block vanished. Measured on the frontend before
the change (the M6 document of `spec/proposals/DIALECT-DEFAULTS-NIET-EN-NESTING.md`
§9.2: an annotated base and its annotated exception): neither annotation text
occurred anywhere in the IR, with `diagnostics: []` and a verifier PASS. The
placement since, with no new wire field (card `frontend-bron-annotatie-in-defaults`):

- a lifted rule's annotation lands in the `Default.bron` of the default it
  becomes: the exception's on the exception default, the base's on the base
  default, an unordered member's on its sibling group's single default;
- the group's `PrioritizedDefaults.bron` carries the annotation of the rule the
  group is named after: the base of an ordered family, the member itself for an
  unordered sibling. The base's text therefore stands twice, on the group and
  on the lowest default. The hand-written defaults anchors decided this: in
  every one of them each default, the lowest included, carries its own
  provenance beside the group's (`anchor/ww-herleving-inkomen`: the group
  "art. 15 tot en met 21: …", its base default "art. 15: …"). Writing the base's
  text on the group alone would give the base default a shape no anchor has,
  and it would lose the per-default citation the statute trace hangs on. The
  anchors' group-level citation is an umbrella the frontend cannot write
  without inventing text ("art. 3 en 5"), so the group takes the text of the
  block it is named after;
- several annotations on one block are joined with `"; "` in source order, and a
  blank one is dropped, as the emitter drops it from `bronAnnotaties`. The join
  loses a distinction and cannot be undone: one annotation that itself contains
  `"; "` reads exactly like two, and the wire already carries such a text (the
  group citation of the golden `jutterswet-samenloop`), so a reader cannot
  recover the list from a joined `bron`. Recorded, not repaired: `Default.bron`
  and `PrioritizedDefaults.bron` are single strings, and keeping the list would
  take a new wire field (option (b) of the proposal's §9.2, not taken). Where
  the separate annotations matter, write one per block;
- an unannotated block keeps `bron: null`: the three extension goldens carry no
  annotation and re-emit byte-identically;
- the `Regel` stubs carry no annotation either: since M1 of the v1 wire (delta
  B3) the emitter writes no `regels` list, so a lifted rule's annotation reaches
  the wire through its default alone;
- nesting: when exception-on-exception lands, a nested block's annotation
  lands on its own default inside the `ExceptionGroup`. Every lifted rule
  becomes a default in one place (`RuleChecker.defaultVan`), so the nested
  lowering inherits the placement; what `ExceptionGroup.bron` itself carries is
  that card's decision;
- an annotation written above the `Uitzonderingen ongeordend:` header belongs
  to no rule — an annotation belongs to the rule directly below it, and the
  header is none — so it reaches no default and no group. It used to vanish
  without a word; it now draws a WARNING that names it, instead of being hung
  on a member the author did not choose. The same WARNING, citing §9.10,
  covers an annotation above a `Regelgroep` header (fix round, 2026-09-23).
  The group's own `bron` field is no place for it: on a `ResolvedRegelGroep`
  that field is the v0 source snippet of the group's name. A `Beslistabel`
  header is the one header whose annotation does reach the wire, because the
  table is rules: the annotation lands in the `bronAnnotaties` of every rule the
  table desugars to, the multi-version precedent of IR-SPEC ch. 3.1 (one comment
  block above a shared header, stamped per flattened entry). Until the same fix
  round it vanished, since each row is re-parsed in a stream of its own where no
  comment stands above it. An annotation above
  a definition (an `Objecttype`, `Feittype`, `Domein`, `Parameter`, …) draws no
  warning: it documents the declaration, which has no wire slot for it, and it
  is common practice — 351 of the 736 annotations in the measured operational
  ruleset collection stand above a definition.

Witness: `DefaultsBronAnnotatieTest`, `BronAnnotatiePlaatsTest`.

### Fail-fast refusals (resolver, loud diagnostics)

- an `Uitzondering op` naming a base rule that does not exist;
- an exception (or group member) writing a DIFFERENT target than the base
  (or than a prior member) — a defaults group is over ONE `(onderwerp, kenmerk XOR
  attribuut)`;
- a base rule that is not a plain kenmerktoekenning/gelijkstelling (an
  initialisatie, or a verdeling/consistentie/objectcreatie/uniciteit);
- a negated kenmerktoekenning in a plain `Regel`, a negation whose rest names
  no declared characteristic, `is niet een` on a characteristic that is not
  zelfstandig, and a negation written after `een` (form 1, above);
- a base rule or exception with more than one `geldig`-versie (not carried by
  this first card);
- an extension block within the scope of a `Regelgroep`, or over a base rule
  that is a group member (next subsection);
- a second writer on a group's target (WF-050, fix round 3): a plain rule
  beside a defaults group — another rule on the base's target, or the other
  rows of a decision table when one row is the base — or a second group on the
  target unless every group there is declared unordered (two ordered
  families, a family beside an unordered block). Each reached disk silently
  for the verifier to refuse; the ERROR names both writers. Keyed on the
  declared object type, as WF-050 is, so a supertype's and a subtype's stacks
  stay two targets. Two unordered blocks on one target stay admitted. A rule
  written in an extension block whose family or block the lowering refused is
  not counted as a second writer (2026-09-23, card
  `dialect-niet-in-uitzondering`): its block's own ERROR stands, and "neem de
  regel op in de groep" would name a block the rule already stands in.

### Interaction with `Regelgroep` (§9.10) — refused

Maintainer decision 2026-09-23, option (a) of the card
`frontend-regelgroep-sluit-bij-uitzondering`. A `Regelgroep` scopes the
consecutive rules and decision tables behind its header and exists only to
mark recursion (§9.10, prose-only in v2.3.0). `groepLid` takes no extension
block, so until that day a group **ended silently** at the first extension
block. Measured on the frontend before the refusal (the M5 document of
`spec/proposals/DIALECT-DEFAULTS-NIET-EN-NESTING.md` §9.1): a group over a
base rule, its `Uitzondering op` and a third rule emitted `leden` holding the
base alone with `diagnostics: []`; the exception and the rule behind it fell
outside the group without a word, and because the lowering lifted the base
into a defaults group, the verifier then refused the document under WF-033 —
on the group rather than on the block, and without the cause.

The dialect does not admit the combination. Option (b) — a defaults group as
a group member — would need WF-033 to accept one and a statement of what a
defaults group inside a recursive group means: a spec question with three
executors behind it, recorded and not taken. The refusal is one ERROR at the
block's own head, citing §9.10 and this section, with two arms:

- **positional** — the block stands directly after a group's last member,
  where the group would read on if the block were a rule. The arm keys on
  "neither a definition nor a group" rather than on the two heads by name,
  so a head a later card adds (`Afwijking van`) is refused there by
  construction instead of reopening the silent end;
- **membership** — the block's base rule is a group member, wherever the
  block stands. A definition that ends the group does not help: the lift
  would still take the member out of the rule forms, and the group would
  name nothing (WF-033). A decision table in the group stands for the rules
  of its rows ("Bergingspremie 1", …), which is what a block names as its
  base; the table's own name names no rule. The group's own `leden` list
  those rows too: until the same fix round it carried the table's name, and
  the verifier refused every group holding a table under WF-033. A row is
  named after the table and its number, so rows without a number, or with the
  same one, share a name; that table is refused by name (WF-020, WF-021 at the
  verifier), and the group lists each name once (fix round 3). The arm speaks only where the
  lift would happen: a base that resolves to no plain rule — a table's own
  name, an object creation — gets the lowering's "no base rule" refusal,
  which names the construct (fix round 2, 2026-09-23); for a table it names
  the rows and says why none of them alone can be a base (fix round 3). A later head that
  names a base of its own has to join this arm by name.

A block for which both hold gets one diagnostic naming both reasons, and a
refused block stays un-lifted together with its whole base family, as the
other refusals leave it. The way out the message names is one that holds
wherever the author follows it: before the document's FIRST group header,
which no group's scope reaches, or behind a definition that ends the group.
"Before the header of this group" was named until fix round 2 and did not
hold — with two groups back to back it lands behind the other group's last
member. A header without any member is a syntax error of its own and draws
no placement message. What stays clean, and is pinned as clean: a block
before the group's header, and a block behind a definition that ends the
group, each over a base outside the group. Two placements that were
verifier-clean before are refused since — an `Uitzondering op` directly after
a group whose members do not include its base, and an `Uitzonderingen
ongeordend` block directly after a group — because at that position the rules
behind the block would fall outside the group in silence. No `.rgs` source in
the repository — golden, rood or parity zone — carries a `Regelgroep` at all,
so nothing in the corpus moves. Witness: `RegelgroepUitzonderingTest`.

### Emission (v0-mirror invariant)

`prioritizedDefaults` is emitted ONLY when the extension produced defaults
groups. A document without the extension emits no such key, so the v0-mirror
byte-identity of every flat golden holds. Within the list, `attribuut` is emitted
only on attribute defaults and `ongeordend` only when true, matching the schema
and the hand-written prioritized-defaults anchors.

### Scope / limitations of the first card

- The first card exercised the ordered shape on a characteristic only. Since
  then `jutterswet-wrakrecht` exercises it on an attribute target, on a
  supertype and its subtype, and `jutterswet-schorsing` with a negated
  exception (form 1, above).
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

## §8.4.3 A time window's bounds — narrowed to the wire's two shapes

Not a dialect extension and not inert: a narrowing of what v2.3.0 spells.
§13.4.15.68 gives a periodevergelijking ("vanaf …", "van … tot …", "tot en met
…") any date expression as a bound, and the frontend used to build every one of
them into a window. WF-111 (`spec/IR-SPEC.md` ch. 4.6, maintainer decision
2026-09-23) admits two shapes on the wire — a date literal, or an own scalar
date attribute of the subject — both of day precision; the general date
expression is an OPEN wire decision (`spec/RUNTIME-SEMANTICS.md` ch. 3.12).
Measured before the fence (2026-09-23): a parameter, a navigated read, a
datum-tijd attribute, a computed date ("zijn peildatum plus 1 dg"), a
time-dependent attribute, a datum-tijd literal and an attribute of another
object type each reached disk with `diagnostics: []`, and the verifier refused
every one. `RuleChecker.periodeGrensFout` refuses them now with an ERROR that
names the shape, so the document never reaches disk. No source in any zone
writes a window with such a bound, so nothing moves.

**"Own" is written on the subject, and compared by identity.** WF-111's text
admits an attribute "on an object type the subject satisfies" (`S ⊑ O`). This
frontend writes an own read — an inherited attribute included — with the
subject as its object type: "zijn peildatum" in a rule on a subtype is an
`AttribuutRef` on that subtype. A read that names any other type, a supertype of
the subject included ("de peildatum van de Jutter" in a rule on a subtype of
Jutter), goes out as a navigation — with by-pair bindings when a fact type
relates the two types, which the verifier refuses as navigated (WF-111), or
without any, which it refuses under WF-053 as an off-subject read no fact type
carries. So the fence compares by identity, as the Drools gate
`Tijdrekening.periodeGrensLowerbaar` does; with `⊑` it would pass the
supertype-named read on to a verifier that refuses it. The message for that
case says how to read the subject's own date instead. Witness:
`PeriodegrensTest`.

**The window's position — WF-112** (fix round 2, 2026-09-23). v2.3.0 lets a
"het is de periode …" bullet stand in any compound condition (§13.4.15.69),
under any quantifier. The wire does not: a window gates the RULE, so under
'geen van de' or a counting quantifier ('ten minste', 'ten hoogste',
'precies') its verdict would be complemented or counted, and no reading is
specified for that. Measured before the fence: the bullet under each of those
four quantifiers, and under a 'geen van de' sub-compound of an 'alle' compound,
reached disk with `diagnostics: []`, and the verifier refused every one.
`RuleChecker.keurVensterposities` now walks every condition the emitter writes —
the reach of the verifier's own walk, conditions nested in expressions included
— and refuses each composite or quantifier fold of that kind that holds a
window, with an ERROR naming the quantifier. Under 'alle', or as the rule's own
voorwaardendeel, the window stays admitted. No source in any zone writes the
refused form. Witness: `VensterKwantorTest`.

**A window bullet written last in a compound subselection** (same fix round).
The grammar ends such a subselection at the bullet's words "het is de periode",
reads them as a characteristic of that name (a marker), and takes the bound as
the RULE's voorwaardendeel: a window on the whole rule, which is a different
document from the one written, while the warnings name only the marker. The
same bullet written earlier in the list is read as a window inside the filter.
The frontend refuses the split with an ERROR at the rule; the grammar is not
changed. Witness: `RegeldoelTest`.

---

## §13.4.4 A rule target is written on the subject — narrowed

§13.4.4 gives a gelijkstelling an `<attribuutvanonderwerp>` target, whose
onderwerpketen may run through roles ("De vakpremie van het strandvak van een
Jutter …"). This frontend builds a target on the rule's universal subject only,
and such a sentence used to come out as an attribute named "vakpremie van het
strandvak" on Jutter — the name swallowed the navigation — reaching disk with
`diagnostics: []` for the verifier to refuse under WF-022 and WF-034. An
undeclared attribute target did the same, and so did a characteristic whose
subject is a navigation, which resolves to no object type at all. Since fix
round 2 (2026-09-23) each is an ERROR naming the form
(`RuleChecker.keurRegeldoelen`). An undeclared characteristic on a resolved
subject is not fenced here: that is the ground of the dialect card for negated
decisions ("is niet X"). No source in any zone writes the refused forms. A
refused target inside an extension block speaks alone: the defaults lowering
skips its family, whose checks would otherwise judge a target the author did
not write and add a second refusal ("is geen kenmerktoekenning of
gelijkstelling", "schrijft een ander doel") beside the true one (fix round 3).
Witness: `RegeldoelTest`.

**A cell of a dimensioned attribute as the target** (fix round 3). The wire has
no cell assignment: a rule's target is an attribute reference, which WF-022
demands, and the dimension-cell node is a read (IR-SPEC ch. 4.3). "De premie
van een Jutter in voorjaar moet gesteld worden op 10" put the label into the
subject text and wrote the WHOLE attribute — verifier PASS on a different
document than the one written. It is refused now with the cell named
(attribute, dimension, label), and so is "De premie in voorjaar van een Jutter
…", which was refused before as an undeclared attribute without naming the
dimension. A whole-attribute write of a dimensioned attribute, with no label at
all, stays unfenced: the verifier admits it, `ref` and clingo write the value as
a scalar, and the Drools exec branch fails at fire time — a norm question for
the verifier line, recorded in the card. Witness: `DimensieDoelTest`.

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

### A type as the operand: no count over all instances (2026-09-23)

`aantalFunc` takes any `primaireExpressie`, so "het aantal Vondsten" — an
object type's plural with nothing it is counted from — parses as a count. The
card `frontend-typemeervoud-telling` asked whether the source language knows a
count over ALL instances of a type, and measured that it does not:

- the count takes a plural subject expression (§5.8.1; typeringen §4.11 admits
  subject expressions only), and §5.8 enumerates the plural ones — a
  concatenation (§5.7), a selection whose object or role selector is plural
  (§5.5.2, §5.5.5), a subselection (§5.6) — each hanging on a subject the rule
  binds per instance (§5.5.3);
- an object type stands in an onderwerpketen only in the singular, behind an
  article: as the universal subject the rule is applied to per instance, or as
  a reference back to it (§5.5.1, §5.5.3, §13.4.1). Its plural has no place
  there;
- the only document-wide range over a type, "van alle <objecttype>", is the
  uniqueness check's own subject expression (§8.1.6, its syntax in §9.5),
  admitted in a single criterion of a consistency rule and nowhere as an
  aggregation operand;
- the reference toolchain has no lowering for the sentence either — its
  resolver leaves it a marker and emits nothing without an explicit override —
  and no count in the official casus of the repository's temporary parity zone
  counts a type: its two count sentences count a role.

So the form stays refused, and nothing reaches the wire. The refusal is older
than the card (WF-037's source-less-count arm), but its sentence said that the
operand "names no instances to count", which is untrue for an author who named
a population. The sentence now states the norm and the way out — count through
a role from the rule's subject, naming that role when exactly one reaches the
type from it and it is plural (a to-one role counts 0 or 1, never a
population) — for every spelling that reaches it: bare, with an article or "alle", the
type's own name, the plural in lower case, in parentheses. Only an operand that
resolved to nothing declared qualifies, so a parameter or a "Daarbij geldt"
variable that shares a type's name keeps the generic sentence. A subselection
over a bare type ("het aantal Vondsten die gewogen zijn") parses another way
and is refused by the subselection fence of §5.6 with its own sentence.
Witness: `TypemeervoudTellingTest`.
