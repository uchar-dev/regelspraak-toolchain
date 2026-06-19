# Grammar divergences from the v2.3.0 source

The grammar is derived from the spec as the oracle. Where the **authoritative
syntax diagrams** (§13) and a **worked example** disagree, or where a source
production is defective, this file records the divergence and the reading chosen
— per CLAUDE.md ("state the ambiguity and the reading you chose; don't guess
silently"). Reconciliations already annotated inline in the `.g4` files are
summarised; this file additionally catalogues the spec worked examples that do
**not** parse and why.

## Reconciliations adopted (also noted inline in the grammars)

- **Whitespace skipped, incl. newlines.** Rules wrap mid-sentence across physical
  lines, so newlines cannot be structural; `;`, `.`, bullets, and keywords
  delimit. Multi-word keyword tokens join their words with a whitespace fragment
  so a phrase that wraps across a line break (`wordt verdeeld` / `over`) still
  tokenises as one terminal.
- **Names split by case** (`UPPER_ID`/`LOWER_ID`). Objecttype/domain names are
  capitalised; attribute/role/parameter names are lowercase. This is the boundary
  that lets a context-free parser split `de belasting … afstand Bedrag` and
  `de reis Vlucht` with no symbol table.
- **Expression type-strata collapsed.** §13 stratifies expressies and comparison
  operators by operand datatype; a context-free parser cannot know operand types,
  so they are fused into one `expressie` and one operator family. The toplevel
  (`gelijk is aan`) vs nested (`is gelijk aan`) word-order distinction is kept.
  Operator/operand-type legality is a resolver obligation (the typeringen matrix).
- **Source EBNF defects reconstructed from the diagrams** (each noted inline):
  `<onderwerpketen>` §13.4.1.1 unbalanced `(`; `<tijdlijndefinitie>` §13.3.6.10
  missing `)`; aggregation `(` typo §13.4.17.46/.48 (aggregations are paren-free);
  `<startpuntbepaling>` stray spaces §13.4.12.1.
- **Model-dependent boundaries deferred to the resolver** (captured as token
  runs): regelnaam (free text up to `geldig`); the feittype cardinality line
  (role abuts free-text relatiebeschrijving); the objectcreatie `met`-clause; a
  name whose own text contains `… van de …` where capitalisation cannot decide
  the navigation split (e.g. `leeftijd van de oudste passagier`).
- **Robustness beyond the diagrams, justified by worked examples:** member-closing
  `;` optional (a last member sometimes omits it); `, gedimensioneerd met`;
  trailing `;` on a feittype; telwoorden inside names (`… vier jaar …`); the `▪`
  bullet glyph; article-less `Parameter`; the subject-led compound consistency
  `<onderwerp> moet voldoen aan <kwantificatie> volgende criteria:`; `alle van de`
  as a tolerated variant of the diagram's bare `alle`.
- **Spec-author surface variants accepted (each appears verbatim in a §-worked
  example, so the spec is the authority over its own EBNF):**
  - `gedurende de tijd dat` as a `<voorwaardendeel>` opener replacing `indien`
    (§13.4.13.1 lists it; §8.4.18.4 explains the substitution).
  - the toplevel/non-toplevel operator split is word-order, not structure, so both
    families are accepted in both positions — a nested bullet may read
    `… groter of gelijk is aan …` (§13.4.15).
  - a bulleted sub-condition may carry its own trailing `.`; it is absorbed into
    the bullet so the list continues to the next bullet (otherwise the rule's own
    terminating `.` would close the rule prematurely).
  - the `:` before a bulleted sub-condition list is optional — two `gedurende de
    tijd dat … wordt voldaan` examples omit it.
  - `Numeriek` without the `( getalspecificatie )` (§13.3.3.2 makes it mandatory);
    a domein-typed attribuut may still pin a unit (`… Bedrag met eenheid euro`);
    a kenmerk may carry a `tijdlijn` (`… kenmerk (bezittelijk) voor elke dag`).
  - `geheelgetal`/`tenminste` written closed-up; a unit spelled out plural
    (`met eenheid jaren`); `de Rekendatum`/`het Rekenjaar` with an article.
  - a sommatie's leegwaarde fallback `… of 0 als die er niet zijn` (§6.7); a
    dimension interval without leading `over` (`… in jaar vanaf <label> t/m
    <label>`, §5.8.5); a begrenzing maximum dropping `met` after `en`
    (`… en een maximum van 1000 €`); an indefinite attribute reference on a
    berekening's left side (`Een a van een persoon moet berekend worden als …`).
  - a later rule version opened with capitalised `Geldig` (a distinct token from
    lowercase `geldig`, so a regelnaam may still begin "Geldig …").

## Corpus scope and verification

`make verify-corpus` extracts every ```regelspraak fenced block from the TOKA
casus and the two specification markdown files and parses each one. Two scoping
decisions make the count meaningful:

- **Chapter-13 EBNF productions are excluded.** The §13 syntaxdiagrammen are
  themselves fenced as ```regelspraak, but their bodies are grammar *notation*
  (`<digit> ::= "0" | …`), not RegelSpraak programs. Parsing a production as a
  program is a category error, so `tools/extract_corpus.py` drops any block
  containing the `::=` operator (32 blocks) and reports the count it skipped.
- **Fragments are parsed under `corpusEntry`, not the document rule.** Most worked
  examples are a single non-terminal in isolation — a bare expressie, a condition,
  an attribuutspecificatie. `corpusEntry` unions the documented top-level fragment
  kinds and anchors on `EOF`; `Parse.java` additionally fails any parse that does
  not consume the whole token stream, so a prefix match cannot masquerade as a
  pass. Three header-only illustrations (a rule's opening line, an extensie's
  opening line) are recognised by harness-only `regelAanhef`/`extensieAanhef`
  alternatives — these are NOT in the real `regel`/`objectTypeExtensie` rules,
  which stay strict, so a real model with a body-less rule is still rejected.

**What this number is and is not.** It is **syntactic parse coverage of worked-example
fragments** — "does this snippet parse into *a* tree under a documented start rule" —
not a claim of semantic or structural correctness. Type/unit/arity/name-resolution
legality is the resolver's job (Definition of Done item 4), and is incomplete; two
snippets additionally parse into a known-wrong tree (see the precedence limitations
below) yet still count as parses. Read "192/193" as recognisability, not validation.

The result: **192 of 193** snippets parse (TOKA 47/47). The one exception:

- **`spec-main-35` — unquoted enumeration literals (PDF→Markdown conversion
  defect).** The Luchthavens enum values (`Amsterdam Schiphol`, …) are quoted
  everywhere else in the spec (lines 774, 1751) but lost their quotes here, and
  the block also glues an orphaned objecttype-member list onto two `Domein`
  definitions. Because whitespace incl. newlines is non-structural, unquoted
  multi-word enum values are intrinsically unparseable — re-quoting them would be
  editing the source, not parsing it. Catalogued as a conversion defect.

### Begrenzing + afronding precedence — fixed, with a bounded bound-operand divergence

A trailing afronding on a begrensde expressie — "… min de korting, met een minimum
van 0 € naar beneden afgerond op 0 decimalen" — must round the WHOLE bounded result
(§13.4.17.25 afronding follows a getalexpressie, and "X met een minimum van B" is the
getalexpressie it follows). It used to parse INTO the bound (minimum = "0 afgerond"),
because the bound was a full `expressie` that greedily took the afronding — so the TOKA
"Te betalen belasting" rule returned max(belasting − korting, 0) UNROUNDED. Fixed: the
bound is now a `primaireExpressie`, so the trailing afronding can no longer be swallowed
and binds to the outer begrensde expression — the rule lowers to
`(max(belasting − korting, 0)).setScale(0, FLOOR)`.

The EBNF does not formalize where the afronding binds (the bound "0 € … afgerond" is
itself a valid afrondingexpressie), so binding it to the whole bounded result is a
deliberate disambiguation, justified by the §6.1.3/6.1.4 prose (the rounding is "in het
voordeel van de passagier" — of the amount, not the constant 0).

Bounded divergence this introduces: §13.4.17.29/.30 give the bound as a full
`<getalexpressie>`, but `primaireExpressie` excludes the binary-operator alternatives, so
a **bare arithmetic/percentage bound** — "met een maximum van de basis maal 2",
"met een minimum van 10% van de basis" — silently mis-binds (the trailing operator
re-attaches outside the begrenzing) rather than being part of the bound. Every TOKA/spec
bound is a literal or an attribute reference (a primaireExpressie), so no example is
affected; an arithmetic bound must be written **parenthesised** — "met een maximum van
(de basis maal 2)". Accepted in trade for the afronding fix: a flat left-recursive
`expressie` cannot give the bound full arithmetic AND stop it before a trailing afronding
without splitting the precedence into a separate bound rule (deferred — no corpus need).

### Unit conversion — scope and known gaps

Unit systems declare conversions (§13.3.5.2: "het jaar jr = 12 mnd", incl. the
"1/N" rational form), and the resolver reduces each unit to its system canonical
with a factor. The transpiler converts a unit on the right operand of `+`/`-`, in a
comparison, and at an assignment, so cross-unit arithmetic and comparison are scaled
rather than computed on raw magnitudes.

A **composite (rate) unit** (`€/jr`, `€/mnd` — §13.3.3.2's `<eenheidmacht> "/"
<eenheidmacht>`) now carries its denominator (`Eenheid.noemer`) instead of dropping it.
Two rates are convertible iff their numerators AND denominators are each convertible, and
the conversion restates the numerator and **inverts** the denominator: assigning `10 €/mnd`
to a `€/jr` attribute scales by 12 (a `jr` is 12 of the `mnd` it replaces), so `10 €/mnd =
120 €/jr`; mismatched numerators (`€/jr` vs `km/jr`) are a type error naming the full units.

Deliberately **not yet** handled (documented rather than half-done):
- **Composite-unit ALGEBRA** (the §6.4/§6.5 result unit of `maal`/`gedeeld door`:
  `€ · 1/jr`, cancellation, multi-denominator `€/u/s`, unit exponents `m²`): only a single
  numerator-over-single-denominator rate is modelled and only conversion (not synthesis) is
  implemented. `maal`/`gedeeld door` still yield Onbekend for a composite result (no claim,
  not a wrong number), and a unit with more than one `/` keeps the numerator only.
- **Reverse-factor precision**: the exact direction (to the smaller unit, ×N) is exact;
  the reverse (×1/N — e.g. `€/jr → €/mnd`, ÷12) is carried at DECIMAL64. Per §6.1.2 there is
  **no implicit rounding** — the author adds an explicit afronding where precision matters.
  (The rate conversion above is fired and asserted in the exact ×N direction.)

### Plural (meervoud) resolution

RegelSpraak declares plurals explicitly — `(mv: …)` on a `<naamwoord>` (§13.2.29),
`(mv: …)` on a feittype role (§13.3.9.1), `<dimensienaammeervoud>` after "bestaande
uit de" (§13.3.7). It defines **no plural morphology**; §3.1 footnote 2 states that
deriving a missing plural is "ALEF-specific … outside RegelSpraak." The resolver
therefore matches a plural reference (e.g. "alle passagiers") against the **declared**
plural first (role `(mv:)`). Only when a role declares none — as every TOKA role does
— does it fall back to a regular +s/+en (and head-noun) guess. That fallback is a
documented, out-of-language best-effort (per footnote 2), never overriding a declared
form; it does not handle Dutch irregular plurals. A fuller fix would also read the
plural that appears literally in the feittype cardinality line ("meerdere passagiers").

### Reference filler: objecttypenaam vs rolnaam (resolver-side disambiguation)

§13.4.1.2/.3 let a reference filler in an onderwerp be **either** an `<objecttypenaam>`
**or** a `<rolnaam>`; the EBNF lists the objecttype alternative first but carries no
normative precedence, and §4804 of the spec states explicitly that the grammar cannot
express that a reference must point to a *reachable* object — it is a resolver-level
semantic check. When a noun is **both** a declared objecttype name and a feittype role,
the resolver therefore disambiguates by **reachability from the rule subject**, which is
what §13.4.1 means by "the written reference *is* the navigation": for a single-object
definite/possessive reference (`de <X>` / `zijn <X>`), it takes the role reading only
when (a) the objecttype reading is **not** the subject's own type — a definite
back-reference to the subject's type reads the subject's own attribute, §5.5.3 — **and**
(b) no role **named as written** reaches that objecttype from the subject, **and** (c) a
same-named role uniquely reaches a type that actually **carries** the referenced
attribute. Otherwise the objecttype reading stands. The disambiguation never
*introduces* a wrong join or an attribute-less role binding — the override is taken only
when its target carries the attribute, else the prior reading is left untouched — and a
collision the override declines (because no reachable reading carries the attribute)
stays a `/* TODO navigatie */` marker. This is a deliberate, bounded divergence: the spec
offers no explicit tie-break for an objecttypenaam/rolnaam collision (the rangtelwoord of
§13.4.1.3 disambiguates two *different* onderwerpen sharing a name, a different case), so
reachability is the resolver's reading. It fires only where the objecttype reading would
otherwise have been a non-functional dangling reference; a fuller treatment could emit an
INFO diagnostic when both readings are independently valid.

This narrow guarantee is **not** yet a general one. On the ordinary objecttype/by-pair
navigation path (no name collision, or a collision where the objecttype reading itself
joins the subject), the transpiler still emits `$subject.getRol().getAttr()` for an
attribute the bound type does not declare — uncompilable DRL with no resolver diagnostic
— because attribute-existence is checked only inside this override branch, not before
every getter is emitted. That pre-existing gap (and the related rangtelwoord
disambiguation for two same-named roles, §5.5.3 ¶1888, which the resolver does not yet
consume) is a separate hardening: the resolver should mark a reference whose bound type
lacks the attribute, so an unresolvable navigation is always a marker, never a broken
getter.

### To-many navigation: a scalar read through a meerdere role is a marker

§13.3.9.1's cardinaliteit line ("Eén reis betreft meerdere passagiers") fixes each role's
multiplicity, but the IR/transpiler used to ignore it: a bare scalar read "de <attr> van de
<meervoudige rol>" lowered to `$subject.getRol().getAttr()`, silently reading ONE element of a
collection (and, when the subject is the "één" side, a getter the fact model does not even
have). The resolver now records each role's cardinality (`Rol.meervoudig`). The cardinaliteit
line is `(één|meerdere) <rolnaam> <relatiebeschrijving> (één|meerdere) <rolnaam>`, so the
rolnaam is the **leading phrase** of each segment and the free-text relatiebeschrijving prose
follows it; the quantifier is bound to the longest leading sub-phrase that names a role. Matching
the leading phrase (not word-by-word) is what lets a **multi-word** role name bind whole
("passagier met recht op treinmiles") and keeps a role that the relatiebeschrijving merely
mentions ("… boeken de reis voor …") from being mis-marked. The transpiler then refuses a scalar
getter chain through a to-many role — it emits a `/* TODO naar-veel-navigatie */` marker instead,
since the read must be an aggregation (`de som/maximale/… waarde van … van de <rol>`, which
already lowers to an accumulate). The check is fail-open: a role whose cardinality cannot be
determined stays single, so a genuine to-one navigation is never wrongly refused (the corpus and
the to-one direction are unchanged). A wederkerig role is treated as enkelvoudig (§3.11), so its
single role is not marked to-many; flagging a wederkerig "meerdere…meerdere" line is left as
future work. This is the cardinality counterpart to the absent-attribute marker above: an
honestly unrepresentable scalar read is a visible marker, never silently-wrong DRL.

### Wortel / tot de macht + afronding — fixed, with a bounded operand divergence

§13.4.17.13/.14 make `<afronding>` **mandatory** on "de wortel van" and "tot de macht"
(it is not bracketed in the diagram). The grammar used to make it optional and let the
operand run as a full `expressie`, so the trailing afronding bound INTO the operand: "de
wortel van X maal Y afgerond" rooted `(X·Y) rounded`, and "X tot de macht 2 afgerond"
rounded the EXPONENT (`X^(2 rounded)`) instead of the power. Fixed by (a) requiring the
afronding and (b) making the radicand / the exponent a `primaireExpressie` — so the
afronding is a sibling that rounds the root/power, and a trailing operator binds OUTSIDE:
"de wortel van X afgerond … maal Y" is `(√X rounded)·Y`, and "X tot de macht 2 afgerond …
maal 3" is `((X²) rounded)·3`. (Math.pow's double result is wrapped as a BigDecimal so the
setScale and surrounding arithmetic compile and fire.)

Bounded divergence (same trade as begrenzing): §13.4.17.13/.14 give the operand as a full
`<getalexpressie>`, but `primaireExpressie` excludes the binary-operator alternatives, so a
**bare arithmetic radicand/exponent** must be parenthesised — "de wortel van (X maal Y)
afgerond …". No corpus example uses wortel/macht at all, so none is affected. (`de wortel
van` still lowers to a `/* TODO aggregatie WORTEL */` marker — no Drools form yet — but the
afronding and precedence are now captured correctly; `tot de macht` lowers and fires.)

`tot de macht` with an integer **decimal** literal exponent — the common case — lowers exactly
through `BigDecimal.pow(int)`, so e.g. 7¹⁹ = 11398895185373143, not the …144 that an IEEE-754
double yields. A genuinely fractional or runtime (non-literal) exponent still lowers through
`Math.pow` (a double), so such a power above 2^53 (~9·10¹⁵) can be off by a few units before the
afronding rounds it — far outside any realistic RegelSpraak magnitude.

### Rationeelgetal literals (§13.2.5 `a[_b]/c`) — fixed

The numeric **literal** reader (`literal`, shared by `geheelExponent`) once matched only a number's
leading decimal head, silently dropping the `"/c"` of a `<rationeelgetal>`. So `4/2` lowered to `4`,
`3/2` to `3`, and `tot de macht 4/2` to `pow(4)` — every arithmetic position with a rational was
wrong, not only exponents. The reader now parses all four §13.2.5 forms via `rationaal`: a bare
integer, an `a,b` decimal, the simple `a/c`, and the mixed `a_b/c` (= a + b/c, the fraction carrying
the whole part's sign). A rational whose quotient **terminates** lowers to its exact `BigDecimal`
value (`4/2` → `2`, `3/2` → `1.5`, `1_1/2` → `1.5`); a non-terminating one (`1/3`) lowers to a
`DECIMAL64` division so it computes at runtime rather than throwing. Because the exact value is now
read, an integer-valued rational exponent (`4/2`) reduces to an exact `pow(2)`, while a true fraction
(`3/2`) or non-terminating quotient falls through to the `Math.pow` path as before. No corpus example
uses a rational literal, so corpus coverage is unchanged; proven by firing in Drools.

Three points the adversarial review surfaced, all addressed or deliberately bounded:

- **Denominator is `POSITIVE_DIGIT` (lexer tightened).** §13.2.5 gives the denominator as
  `POSITIVE_DIGIT+` (`[1-9] DIGIT*`), but the `NUMBER` token had written it `'/' DIGIT+`, admitting
  `1/0`. That lowered to a `BigDecimal` divide-by-zero that throws at *fire* time. The token now uses
  `'/' [1-9] DIGIT*`, so `1/0` is not a number: the lexer splits it into `NUMBER SLASH NUMBER` and the
  parser rejects the stray `/` (an honest parse error, not a runtime crash). Valid fractions such as
  the `1/1000` omrekenfactor are unaffected.
- **Mixed-form sign from the text, not the value.** The mixed `a_b/c` sign is read from a leading `-`
  in the literal, because `BigDecimal("-0")` has `signum() == 0` — so `-0_1/2` (= −0.5) would otherwise
  lose its minus and lower to +0.5.
- **Non-terminating rationals are approximated at `DECIMAL64`, not kept exact.** The typeringen
  precision clauses (gedeeld door §389, percentage van §486) describe a rationeelgetal as an *exact*
  fraction, decimal notation used only when it terminates. The engine, however, is `BigDecimal`
  throughout — `gedeeld door` itself already divides under `DECIMAL64` — so a non-terminating literal
  like `1/3` is approximated to 16 digits (`9 maal 1/3` fires 2.9999999999999997, not an exact 3).
  This is the engine-wide decimal-vs-exact-fraction limitation, not specific to literals; carried as a
  backlog item rather than fixed here, since exact-rational arithmetic would have to be threaded
  through every operator.

A note on the mixed form's spelling: the EBNF (syntaxdiagrammen §13.2.5, and the spec-for-ANTLR
`NUMBER` token) writes the mixed number with an underscore (`a_b/c`), and the parser follows it.
The spec's *rendered* values in prose and tables show a space instead (`2 1/11`, `1 319/365 €/mnd`) —
a display convention (a space-separated `2 1/11` would lex as two separate numbers), so those table
values are not single-literal input. The underscore form is the one the grammar admits.

### Literal precision and §6.1.2 assignment-precision — enforced

A numeric literal now carries its real decimal precision instead of a hardcoded 0: a decimaalgetal
`1,5` types as 1 decimal, a rationeelgetal as the decimals of its exact value (`4/2` → 0, `3/2` → 1,
a non-terminating `1/3` → its DECIMAL64 scale). Precision is the value's *minimal* scale, per the
§6.1.2 note that `0,5` and `0,50` are the same precision. An integer literal is now `geheel` too,
which also lets an integer literal pass the §5.13 elfproef (it could not before).

On the back of that, §6.1.2 is enforced: `Typering.toekenning` rejects assigning a value whose
decimal count exceeds the target attribute's, unless it was first explicitly rounded — "`1,5` →
geheel getal" is an error, "`1,5` afgerond op 0 decimalen → geheel getal" is fine. The afronding
wrapper now reports the rounded precision (`Expr.Afgerond.type()` returns the base type with
`decimalen` set to the rounding's), which is exactly what makes the rounded form pass. A read of a
2-decimal attribute into a geheel-getal attribute is likewise flagged.

To keep the check free of *false positives*, two result types that would otherwise over-report were
made precision-conservative: `percentage van` (§4.8) is an exact fraction that shares only its
operand's unit, so it no longer inherits the operand's decimal count (`50% van prijs(2 dec) → geheel
getal` is valid — `50% van 4,00 = 2`); and a multi-operand `min/max` value-fold (§4.12/§4.13), whose
result is the winning value and thus value-dependent, reports the *least* operand precision rather
than the (order-dependent) first operand's. The remaining inaccuracies are *false negatives* (the
check passes some assignments it ideally would flag) and are strictly safe: `maal` types its result
with `maxDec` where §4.4 mandates the sum-of-decimals, and `gedeeld door` / a non-terminating
fraction under-reports — both carded with the per-operation precision work. False negatives are
acceptable (the check is an added safeguard, not load-bearing); false positives would reject valid
models, so those were eliminated.

### Kenmerk-membership test in a condition (§5.14) — subject-only

A condition `indien de Meting oud is` / `indien hij oud is` tests whether the subject carries a
boolean kenmerk. The resolver used to drop it (`Conditie.Onopgelost` → a `/* TODO voorwaarde */`
marker), so the rule never gated on the kenmerk — even though kenmerk *assignment* ("Een Meting is
oud indien …" → `setOud(true)`) already worked. It is now structured as a `Conditie.Kenmerk` and
lowered to the boolean **property** constraint `oud == true` inside the subject pattern (the property
form, not `getOud()`, resolves to the fact's `isOud()` — a boolean bean getter is `isX`, not `getX`).
Scope is deliberately the **subject's own** kenmerk: the operand must denote the subject (the
bezielde `hij`, or a definite self-reference whose noun is the subject type). A multi-word kenmerk in
the definite form is handled too — the greedy `onderwerp` parse absorbs its leading words into the
operand (`de Meting bereikbaar per` + operator `trein`), so the resolver reconstructs the full name
from that absorbed prefix plus the operator's `kaleNaam`. The leading **`een`** article is honoured as
the §13.4.15.44/.45 signal of an objecttype/role-**membership** test (`X is een <objecttype>`), which
stays `Onopgelost` — so a name that is *both* a subject kenmerk and an objecttype/role is not silently
gated as the kenmerk. Still `Onopgelost` and carded: a navigation-based kenmerk check (`de partner …
oud is` — the kenmerk is on another object, which is what the §13.4.2 die-filter needs), the
objecttype/role-membership lowering itself, and negation.

### An unresolved condition is a compilable no-op, not an uncompilable `null`

Any condition the resolver leaves `Onopgelost` (a navigation/objecttype/role-membership test, a
period comparison, an unstructured predicaat) lowers in the "when" side to an **always-false**
constraint `false /* TODO voorwaarde: … */`, so the rule is a visible no-op (it never fires) and the
DRL still compiles. Previously it lowered to `/* TODO … */ null`, but a bare `null` is not a boolean
pattern constraint — `Subject( null )` fails to compile, which sank the *whole* generated DRL even
when every other rule was fine. (The value-side `… null` marker is unchanged — `null` is a valid
value for an object/decimal field; only a constraint position rejects it.) A **compound** condition
with any unresolved sub-condition is marked a no-op *as a whole*, not by splicing `false` into one
operand: under the negated quantifiers (`geen` / `ten hoogste` / `precies`, which wrap operands in
`!(…)`) a spliced `false` would be dropped by the negation and let the rule fire when it must not.

### Telling (aantal) operand precedence — fixed, with a deliberate EBNF asymmetry

The §13.4.17.46 EBNF and syntax diagram give all four `getalaggregatie` functions the
same operand non-terminal — `<getalaggregatiefunctie> <expressie>`, so the count, the
sum, and min/max alike take a full `<expressie>`. Taken literally that makes
"het aantal X maal Y" a count of "(X maal Y)": the operand greedily absorbs the
trailing `maal`. On the TOKA contingent rule that swallowed the whole product into the
count and lowered it to a null marker — the core "aantal passagiers maal aantal
treinmiles per passagier" calculation produced nothing.

The fix gives **only** the telling a higher-precedence operand — `HET AANTAL_WORD
primaireExpressie` — so a trailing `maal`/`plus` binds outside the count and
"het aantal X maal Y" is "(count of X) maal Y". `de som van` / `de maximale waarde
van` / `de minimale waarde van` keep their full `<expressie>` operand. This per-function
asymmetry is a deliberate divergence from §13.4.17.46's symmetric EBNF, and it is what
the **typeringen** matrix mandates: §4.11 — "de *aantal* operator kan alleen gebruik
maken van onderwerpexpressies" (a count ranges over an object/multiplicity expression,
never an arithmetic one), whereas §5.8.2 explicitly allows a sum's operand to be "een
rekenkundige expressie." So the count must stop at its objectexpressie while the sum
keeps reading the arithmetic. (`primaireExpressie` is slightly broader than an
objectexpressie — it also admits literals and a nested function call — but it excludes
the binary-operator alternatives, which is the precedence point.)

Tightening the operand surfaced a telling-vs-name ambiguity the absorbed argument had
hidden: a parameter may be named beginning with "aantal" (TOKA's "aantal treinmiles per
passagier voor contingent", referenced "het aantal treinmiles per passagier voor
contingent"). The spec offers no syntactic tie-break, so the resolver decides by
countability: when "het aantal <phrase>" binds no countable element and the whole
phrase is a declared parameter, it is read as that parameter (a genuine count always
binds an element in a well-formed model, so it is never reinterpreted).

The telling also takes a **concatenation of operands** (§4.11 "een concatenatie van
expressies"; the §5.8.1 "uitzonderingspassagiers" worked example counts "het aantal X, Y
en Z"): `HET AANTAL_WORD primaireExpressie ( COMMA primaireExpressie )* ( EN
primaireExpressie )?`. The per-operand operand stays a `primaireExpressie`, so the
single-operand "het aantal X maal Y" precedence above is unchanged (a trailing `maal` binds
outside; the comma/`en` list only matches a following primaire). The IR captures every
operand, but the count's SEMANTICS — the UNION of the per-operand sets — is not yet lowered:
a multi-operand telling is emitted as a `/* TODO aggregatie AANTAL */` marker rather than
silently counting only the first operand. The union-count lowering (a disjunctively-filtered
single accumulate) is carded.

A related §13.4.2 gap: a `die/dat <predicaat>` **subselectie** on a single-operand SET
aggregation — `het aantal` / `de som` / `de minimale|maximale waarde van … passagiers van de
reis die een kind zijn` — filters the aggregated element. A subselectie whose predicaat is an
`is/zijn` **kenmerk** of the aggregated element is resolved (against the ELEMENT type, not the
rule subject) into a `Conditie.Kenmerk` and lowered as an extra accumulate-source constraint —
`accumulate( $e : Passagier( reis == $subject, kind == true ) ; count($e) )` (and likewise
`sum`/`min`/`max`) — so only the matching instances are aggregated. The detection is confined to
a SINGLE operand: a multi-operand telling (the §5.8.1 union) or a min/max value-fold has no single
element filter — there a die belongs to one operand's own navigation, not the aggregation (see
p20b) — so those stay markers/drops. A subselectie the resolver cannot structure (a comparison, a
`heeft een …` possession, an objecttype/role membership, a name that is not a kenmerk of the
element) stays an `Onopgelost` `filter` on the `Expr.Functie`; the resolver warns and the
transpiler emits the aggregation as a marker rather than aggregating over EVERY instance — a
visible no-op, never a wrong total.

A **multi-operand count** — the §5.8.1 union "het aantal X, Y en Z" — is lowered when every operand
resolves to a kenmerk filter over the *same* element: the resolver ORs the per-operand filters (a
`Conditie.Samengesteld(TEN_MINSTE, 1, …)`), and the transpiler emits ONE accumulate whose source
carries the disjunction — `Passagier( reis == $subject, ((kind == true) || (student == true)) )` —
so an instance in several sets matches once and is counted once (a true union, not a sum of set
sizes). A union with any unresolvable operand (a bare set, a comparison, a different element) stays a
marker. A multi-operand min/max VALUE-fold is excluded (its die is on a sub-operand navigation). Still
carded (p29): inflected/meervoud kenmerk spellings, and the value-fold operand die.

### Aggregation operand separator — `en` splits, it is not a name word

The §13.4.17 concatenation-of-expressies forms — "de eerste/laatste/minimale/maximale
waarde van A en B [, C en D]" (§4.12-4.15) and the §13.4.6 gegevensverstrekking list —
separate their operands with `en`. But the spec defines a `<naam>` as a free
`<karakterreeks>` (§13.2 `<naam> ::= <karakterreeks>`), so a bare "A en B" is genuinely
ambiguous: one name containing the word "en", or two operands separated by it. A
context-free parser must choose deterministically, and ANTLR's greedy `kaleNaamWoord+` /
`naamWoordLower+` loops chose to **absorb** the `en` — so "de eerste van X en Y" parsed as
a single-operand aggregation over a compound name "X en Y", silently dropping the second
operand (two TOKA examples, the eerste/laatste van of two dates, mis-parsed this way while
still producing no error node).

Resolved by **removing `EN` from the name-word sets** (`naamWoordLower`, `kaleNaamWoord`),
so `en` is the operand separator. Justification: the corpus never uses a bare `en` *inside*
a declared name (its only `en` occurrences are the `tot en met` token and the datatype
keyword `Datum en tijd in millisecondes`), and RegelSpraak conditions compose with
**bullet-delimited** structures, not an inline `en`/`of` boolean operator — so `en` carries
no in-name or in-expression role that this loses. Bounded divergence: a name that genuinely
needs a literal interior "en" can no longer be written (it would split); no spec or TOKA
example needs one. This makes the multi-operand min/max/eerste/laatste forms parse all their
operands, which the resolver then type-checks (§4.12-4.15: min/max operands all numeriek with
inter-convertible units; eerste/laatste all datum of one tijd-precisie) and the transpiler
lowers as a pairwise fold of `<`/`>` ternaries rather than a collection accumulate.


### Forms still modelled at main-clause word order (resolver-side concern)

- **V-final subordinate clauses** after `dat` (`… dat hij een recht … heeft`,
  §13.4.17.57): the toplevel-operator trailing-verb form (`… EEN kaleNaam HEEFT`)
  already accepts this surface, so it parses; a fully faithful tree would mark it
  as a subordinate clause, which the resolver can do from the operator label.
