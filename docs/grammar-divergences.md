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
    the §4.4 worked example writes exactly this (`Numeriek met eenheid jaren`).
    The **resolver** reads such a bare `Numeriek` as the least-restrictive numeriek
    — identical to a plain `getal`: unbounded decimals (the `decimalen 0` sentinel),
    not a whole number, no sign restriction — keeping any unit, and raises a WARNING
    that the normatively-mandatory getalspecificatie is absent (rather than NPE'ing
    on the null specification, the spec-main-43 crash). A domein-typed attribuut may
    still pin a unit (`… Bedrag met eenheid euro`); a kenmerk may carry a `tijdlijn`
    (`… kenmerk (bezittelijk) voor elke dag`).
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
with a factor. The transpiler converts a unit on the right operand of `+`/`-` and at an
assignment, so cross-unit arithmetic and assignment are scaled rather than computed on
raw magnitudes.

A cross-unit **comparison** scales BOTH operands to their shared canonical unit by the
DECLARED (exact) `factorNaarCanoniek`, rather than dividing one operand by the ratio of the
two factors. The ratio truncates for a non-terminating quotient (`mnd → jr = 1/12 =
0.0833…`), which made an exact cross-unit `gelijk aan` a false-positive violation (`24 mnd
== 2 jr` lowered to `2 != 1.9999…`); scaling each side by its integer factor (`24 == 2·12`)
is exact (p43). Both the consistentie and the voorwaarde comparison paths use this; a
composite (rate) unit keeps the single-operand ratio (its precision edge stays carded).

Two units are **convertible** only when they are the same unit or reduce to the SAME system
canonical — sharing only an `eenheidstelsel` is not enough (p44). A declared conversion
(`het jaar jr = 12 mnd`) is what reduces `jr` to canonical `mnd`; two independent base units in
one system (`appel app`, `peer pr`, no declared conversion) reduce to disjoint canonicals, so
§13.3.5's "equal or convertible" requirement fails and a comparison/`+`/assignment between them is a
unit error (`eenheden 'app' en 'pr' zijn niet in elkaar om te rekenen`) rather than a silent raw-
magnitude comparison. A well-formed system reduces every unit to one canonical, so the ordinary
`jr`/`mnd`/`dag` case is unaffected. (The binary-arithmetic path used to *swallow* this typeringen
error — `appels plus peren` resolved silently; it now surfaces it as a diagnostic like the comparison
and begrenzing paths, p45.)

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

### To-one navigation in a comparison constraint is null-safe (`!.`)

A comparison/predicate operand that navigates a to-one reference ("de leeftijd van zijn partner",
"de roepnaam van de reis") lowers, on the constraint (when) side, to a property path on the pattern.
mvel's `.` is **not** null-safe, so `partner.roepnaam` throws an NPE at evaluation when `partner`
is null — which sinks the *whole* rule firing, not just that instance. The path now uses Drools'
null-safe operator `!.` (`partner!.roepnaam`): a null reference makes the constraint a **non-match**
rather than a throw.

A **consistentie** comparison is now leegwaarde-faithful (p41): instead of `!.`, its violation is built
from explicit guards per the typeringen rekentabellen — a navigated operand's emptiness is
`(rol == null || rol.attr == null)`, and its value is read with a plain `.` that the guard short-circuits
before any null dereference. So a gevuld value compared to a null-navigated operand evaluates per Tabel 38
(non-numeriek `==`: onwaar → a violation), Tabel 37 (numeriek `==`: a one-sided empty is onwaar → a
violation, but **both** empty is waar → consistent), Tabel 40-43 (ordening: any empty → onwaar → a
violation), and Tabel 44 (ongelijk). A partnerless person is now correctly flagged where the spec says the
criterion is not satisfied, and still never NPEs (the `rol == null` guard precedes the `.`).

A **voorwaarde** (rule-guard) comparison still lowers via `!.`. That is faithful for a rule guard in nearly
every row: an empty operand makes the comparison "onwaar", and a `!.` non-match means the rule does not fire
— the same outcome. The only residual under-report is the rows where an empty operand makes the comparison
**waar** (a both-empty numeriek `==`, a gevuld-vs-empty `ongelijk`), where the rule should fire but `!.`
does not match; that narrow case overlaps the §9.5/p40 "what does a fout/empty condition mean" question and
is left carded.

The **`leeg`/`gevuld` predicate is the exception** — for it, `!.`'s non-match would be plain *wrong*, not
merely conservative: Drools `!.` short-circuits the whole term to a non-match on a null reference, so
`partner!.leeftijd == null` reads **false** for a partnerless person, but typeringen Tabel 54 says an absent
value `is leeg` is **`waar`**. So `leeg`/`gevuld` over a to-one navigation instead guards the reference
explicitly: `is leeg` → `(rol == null || rol.attr == null)`, `is gevuld` → `(rol != null && rol.attr !=
null)`. The short-circuit reaches the `.` only when the reference is non-null, so it neither NPEs nor
misreads — an absent reference is correctly empty (Tabel 53/54). The value (then) side keeps its getter
chain (`$subject.getReis().getX()`); a null there is a separate, carded concern. The §8.1.8 navigated
kenmerk already emits its own explicit `rol != null && rol.x` guard and is unchanged.

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
afgerond …". No corpus example uses wortel/macht at all, so none is affected. Both `de wortel
van` and `tot de macht` LOWER AND FIRE: the runtime's `rsWortel` computes the radicand's 34-digit
`BigDecimal.sqrt` inside the mandatory afronding (a leeg or negative radicand yields leeg, the §6.6
fout surfaced by the separate `(wortel van negatief)` Inconsistentie-companion rule — pinned in
`DrlTest`/`DroolsExecutionTest`). An earlier revision of this parenthetical still called the wortel
a `/* TODO */` marker; that claim predated the rsWortel lowering (docs-match-reality).

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

An **unbounded target** (§13.3.3 form (c) "getal" — `geheel=false, decimalen=0`) carries no precision
ceiling, so the check does not apply to it: assigning a decimal value to a `Numeriek (getal)` attribute
is always valid. The `decimalen 0` field is an overloaded sentinel — it means both "geheel getal"
(form (a), bounded at 0) and "getal" (form (c), unbounded), and only the `geheel` flag tells them apart
(`Datatype.onbeperkt()` encodes this). Before this guard an unbounded target read `decimalenVan == 0`
and was wrongly capped like a whole number, rejecting any decimal value (p33). The same sentinel overload
once mislabelled a min/max value-fold as `geheel` (its `decimalen` floor is 0 whenever any operand is
unbounded or integral), which would have admitted a min-of-unbounded to the §5.13 elfproef; the min/max
`geheel` flag is now derived from whether **every** operand is geheel, not from `decimalen==0` (p35),
and the elfproef type-check fires in a voorwaarde (p34).

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

### Kenmerk-membership test in a condition (§8.1.8) — subject-only

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
from that absorbed prefix plus the operator's `kaleNaam`. The leading **`een`** article switches the
reading to a **membership** test: see the §8.1.7 rolcheck section below.

**The EEN-form is disambiguated kenmerk-first (Tabel 16): a §8.1.8.3 zelfstandig kenmerkcheck, else a §8.1.7
rolcheck.** The grammar conflates a kenmerknaam and a rolnaam after the optional `een`, so the resolver
disambiguates by lookup. WITHOUT `een` the name is the subject's kenmerk (above). WITH `een`: a ZELFSTANDIG
kenmerk binds first (`is een <k>` → `k == true`), otherwise the name is a §8.1.7 rolcheck — the subject FILLS
role `<naam>` iff it joins the other end of that feittype. `model.rolSiblingType` (the mirror of
`rolDoeltypeNaarSubject`) returns that other end's object type; in the §9.4 reference-field fact model the
subject participates iff its reference to that end is set, so `Conditie.Rollidmaatschap` lowers to a bare
null-test on the subject's own pattern — toka-42 `hij is een passagier` → `reis != null`, lighting up a rule
whose whole compound used to be a `false /* TODO */` dead gate.

The EEN-form is disambiguated **by the verb** into the matching §3.5 soort: `is een <k>` binds a §8.1.8.3
ZELFSTANDIG kenmerk, `heeft een <k>` binds a §8.1.8.2 BEZITTELIJK kenmerk (the §7.1 verbatim "heeft een recht op
belastingvermindering"). The soort is recorded on the model from the optional `(bijvoeglijk)`/`(bezittelijk)` tag
(an untagged declaration is zelfstandig — incl. the TOKA `is passagier van 18 … kenmerk`, admitted via the IS-form
without the tag). Neither soort binds under the *other* verb: a bijvoeglijk kenmerk is written `is <k>` (no `een`),
the canonical bezittelijk form `heeft het <k>` rides the non-EEN path (the article is absorbed into the `kaleNaam`
and `stripArtikel` recovers it), and the EEN-form pairing is strict in both directions — `is een <bezittelijk k>`
and `heeft een <zelfstandig k>` are wrong-verb forms that stay honest markers (the latter formerly over-bound
verb-agnostically). A name that is no such kenmerk falls through to the §8.1.7 rolcheck (kenmerk-first, Tabel 16),
so the far-side `heeft een <rol>` exists-join is preserved. The TOKA EEN-form kenmerk bullets resolve but their
compounds still marker through an undeclared sibling kenmerk, so no corpus rule lights up yet; the **navigated**
EEN-form bezittelijk (`zijn reis heeft een <bezittelijk k>`) stays a marker too (`navigatieKenmerk` is still
zelfstandig-only — a carded follow-on), never a wrong gate.

The NAVIGATED EEN-form (`de <rol> van <X> is/heeft een <kenmerk>`, e.g. "de reis van een Passagier is een reis met
paaskorting") now binds too, through the same to-one navigation as the non-EEN navigated kenmerkcheck and gated by
the SAME verb→soort rule the subject's own EEN-form uses: `is een <k>` binds a ZELFSTANDIG kenmerk of the navigated
type, `heeft een <k>` a BEZITTELIJK one ("de reis van een Passagier heeft een recht op vergoeding"), each
→ `<rol> != null && <rol>.<k> == true`; neither soort binds under the other verb (a bijvoeglijk one, or a wrong-verb
form, stays a marker) and a navigated *role* membership via the EEN-form remains out of scope. Corpus-invisible (the
TOKA navigated EEN-form compounds marker through an undeclared sibling), so proven on a synthetic fixture firing the
to-one path (a holder partner → 7, a non-holder and a partnerless subject → no fire, null-safe).

The **POSSESSIVE** navigated form (`zijn <rol> …`, e.g. "indien zijn partner een recht op duurzaamheidskorting
heeft") now binds too. `possessief()` resolves a possessive that is neither an own attribuut nor a dimensiecel but
NAMES a feittype role the subject reaches into the same navigated `AttribuutRef` shape `bouwAttribuut` builds for the
written "de `<rol>` van `<X>`" — so a possessive kenmerkcheck reaches `navigatieKenmerk` (`partner != null &&
partner.<k> == true`, by the same verb→soort rule) and a possessive value read reaches the null-safe to-one getter
(the symmetric mirror of the definite form; without it the possessive role stayed `Onopgelost` and markered). Fired
(a holder partner → 7, a partnerless subject → null-safe no fire); corpus byte-identical (no corpus rule uses a
possessive role this way). The article-absorbed `zijn <rol> het <k>` form stays a marker (the same class as the
gedurende "heeft het" edge; carded).

The **no-`een` rolcheck** (`<subject> is <rolnaam>`, §13.4.15.44 makes the `een` optional) now binds as well. The
non-`een` name is resolved **kenmerk-first** (Tabel 16): `kenmerkConditie` claims a declared kenmerk (a bijvoeglijk
kenmerk is written exactly this way, `is <k>`), and only a name that is a role but **not** a kenmerk falls through
to the §8.1.7 rolcheck (`<refField> != null`). Gated to the IS-verb — `heeft <rol>` is the far-side possessive
(the subject *owns* the filler), which needs an exists-join and stays a marker, never the wrong near-side test.
Corpus byte-identical (no corpus condition uses the bare `is <rolnaam>` form).

The **negated membership** `<subject> is geen <rol>` / `is geen <kenmerk>` (§8.1.7 / §8.1.8.3) now lowers as well.
`geen` carries no token (only `GEEN_VAN_DE`), so it lexes as part of the `kaleNaam` and reaches the non-`een` path
with `naam = "geen <X>"`. The kenmerk negation rides the existing `bindKenmerk` `geen`-de-negation (its verb gate
was dropped, so `is geen <k>` lowers like `heeft geen <k>` → `<k> == false`); a leftover `geen <rol>` is the
inverse rolcheck — `Conditie.Rollidmaatschap` gained a `negatie` flag and the transpiler emits `<refField> == null`.
Per Tabel 16 the negation is resolved kenmerk-first (verbatim-first, so a kenmerk literally named `geen …` still
binds positively); the boolean lowering is the same regardless of soort, so the noch-noch form is not soort-gated.
Corpus byte-identical (no corpus `is geen <declared X>` flips).

The **FAR-side rolcheck** `<subject> heeft (een) <rol>` (§8.1.7 — the subject is on the "één" side, so the relation
field lives on the to-MANY filler, not on the subject) now lowers too. When `rolLidmaatschap` finds the subject
does not FILL the role but the role reaches a to-many filler from it, it resolves a new `Conditie.VerreRollidmaatschap`
(the filler type + its back-reference field), which the transpiler lowers to an **exists-join** — a SEPARATE
when-clause `exists <Filler>( <backref> == $subject )`. The **backref is found by the written role name**
(`verbindendeRolViaElementnaam(subject, naam)` — the subject-side role of the SAME feittype that declares the named
role), falling back to the by-pair `verbindendeRol(filler, subject)` for the single-feittype case (both yield the
same role there, so it stays byte-identical). This disambiguates a filler that references the subject through MORE
THAN ONE feittype (a `Passagier` that fills both `passagier` (`reis`) and `bonuspassagier` (`bonusvlucht`) of a
`Vlucht`): the by-pair lookup is then ambiguous and the whole far-side wrongly degraded to a marker; now `heeft een
passagier` gates on the `reis` backref specifically. A genuinely ambiguous model (e.g. two roles of one feittype
share the subject type) still degrades safely to a marker, never a wrong backref. (The `heeft geen` negation is the
`not` of the exists.) Because an
exists-join cannot live inside the subject pattern's inline constraint, condition lowering returns a
`LoweredConditie{inline, extra}`: the inline mvel constraint plus zero or more hoisted when-clauses. A far-side
membership yields an empty inline and one `exists`/`not` clause; everything else yields `extra=[]`. `regel()`
appends the extra clauses to its when-list **after** the subject pattern, so the former top-level peel falls out of
the general mechanism (the hand-rolled `verreClausule` special case is gone).

**Nested in a PURE CONJUNCTION (§13.4.14 `alle` / `de`) the far-side now lowers too:** a `Samengesteld` joins its
children's non-empty inlines with `&&` and concatenates their hoisted clauses, so "vol indien hij heeft een
passagier én hij is internationaal" emits `internationaal == true` inline AND a separate `exists Passagier(...)`,
firing only on the real AND. When **every** conjunct is a far-side membership the inline is empty (`Vlucht(  )`) and
each rides out as its own `exists`. Under a **non-conjunction quantifier** (`geen` / `ten hoogste` / `precies`, and
`ten minste` via its OR-fold) a hoisted clause — implicitly AND-ed as a separate pattern — would instead *assert*
the membership unconditionally, a silently-wrong gate, so a far-side child there still no-ops the **whole compound**
to a marker (keyed explicitly on `alle`/`de`, never the default arm). (`de` is the §10.2 singular single-criterion
form, faithfully a conjunction of its one bullet.) A **feitcreatie anker guard** (§13.4.7 "Een bonuspassagier van
een vlucht is een passagier van de vlucht **indien de vlucht heeft een bemanningslid**") and an **objectcreatie
subject guard** (§13.4.6) now lower with the full carrier and host their `extra()` after the anker/subject pattern,
exactly like `regel()` — the membership (a relation distinct from the doel join) genuinely filters which ankers get
the relation, so a vlucht with passengers but no crew creates no bonusrelatie. A near guard yields `extra=[]` and is
byte-identical. The remaining inline-only callers (the feitcreatie **$doel** guard — its exists would reference
`$doel`, bound later; the §13.4.8 schending OR/negation; the die-filter accumulate source) still go through the
`conditieInline` adapter that degrades a would-be-hoisted condition to the same `false /* TODO */` marker those
sites already detect — never a silent drop, never an over-creation. Still **only the conjunction case** lowers; a
far-side nested under a negation/OR, or hosted by an inline-only caller, remains an honest marker.

The "een" is optional and the IS/heeft verb no longer gates it (a kenmerk still binds first; only a role newly
reaches the far side). Corpus byte-identical across all 193 files (no corpus condition uses the far-side form),
proven on synthetic fixtures firing `exists`/`not` in real Drools (the mixed inline+far-side AND matrix, the
all-far-side empty-inline AND, the negated-quantifier marker). The §6.5/§6.6 deling-door-leeg / wortel-negatief
Inconsistentie companions now **carry the host rule's hoisted clauses** (`lc.extra()`), appended after their own
subject pattern, so a far-side-gated own-attribute division/wortel raises its fout exactly when it would execute —
the leeg-divisor / negative-radicand guard holds AND the membership holds. (An earlier slice SUPPRESSED them for a
far-side-gated rule, because a companion built from the empty inline constraint lost the membership gate and
over-fired; threading the exists/not clause restores the gate.) The companion is insert-only, so adding an
exists/not clause cancels no sibling activation (unlike a modify+no-loop rule); the own-attribute operand scope
limit is unchanged (navigated / aggregation / parameter operands still raise none), and a non-far-side rule passes
an empty extra list so its companion is byte-identical. **Carded:** the far-side nested under a non-conjunction
quantifier or hosted by an inline-only caller (would need De Morgan restructuring / per-caller join hosting).

**Navigated and negated forms are now lowered too.** A TO-ONE navigated kenmerk (`de partner van een
Persoon gepensioneerd is`) resolves the kenmerk against the navigated type and lowers to the null-guarded
property path `partner != null && partner.gepensioneerd == true`; the role is normalised to its declared
name (so a plural `de passagiers` is the same role), a TO-MANY navigation stays a marker (it needs an
exists/forall), and the multi-word kenmerk is reconstructed from the absorbed operand tail. **Negation**
(`… niet oud is`) lowers to `oud == false` (and `partner != null && partner.gepensioneerd == false`),
detected by a leading `niet` only AFTER the whole reconstructed name fails to match — so a kenmerk
*declared* with a leading `niet` (`is niet ingezetene kenmerk`) still binds positively.

**The bezittelijk `geen` negation lowers; the noch-noch `geen` stays a marker.** §8.1.8 writes the
bezittelijk negation as `heeft geen <kenmerk>` (§8.1.8.2) and the noch-noch one as `is geen <kenmerk>`
(§8.1.8.3); both reach the resolver with `geen` *inside* the `kaleNaam` (there is no `GEEN` token — only
`GEEN_VAN_DE` — so `geen` lexes as a plain `LOWER_ID` and survives `stripArtikel`). The bezittelijk form
lowers to `== false`, **gated on the hebben-verb** (`op.HEEFT()/HEBBEN()`): its positive `heeft <k>` is
lowered, so the negative is its faithful complement. The noch-noch `is geen <k>` is left `Onopgelost`,
because its positive `is een <k>` is *itself* deferred for the §13.4.15.44/.45 membership ambiguity (p28) —
lowering only the negation would gate one of a complementary pair while the other is an inert no-op. A
`geen <k>` whose `<k>` is not a declared kenmerk stays a marker regardless (`KenmerkMatcher` gates on a
declared kenmerk), so a genuine role/objecttype membership is never mis-gated.

**Verbatim-then-stripped matching of the reconstructed name (bundled fix).** A bezittelijk/noch-noch
kenmerk is written with its own leading article (`het recht op duurzaamheidskorting`), and the greedy
`onderwerp` parse can leave that article on the kop it absorbs (`de Persoon` + `het recht op` + operator
`duurzaamheidskorting`). The reconstructed name `het recht op duurzaamheidskorting` then failed to match the
article-stripped declared kenmerk, so the **positive** `heeft het recht …` was silently a marker while
`heeft geen recht …` lowered — an asymmetry on the canonical TOKA bezittelijk kenmerk. The match now tries
the name **verbatim first** and only retries with a leading `de/het/een` stripped if the verbatim name binds
nothing. A blind strip-first is *wrong*: a **bijvoeglijk** kenmerk has no separate `bepaaldLidwoord?` in its
grammar rule (`bijvoeglijkKenmerk : IS lowerNaam … KENMERK`) and `naamWoordLower` admits `de`/`het`, so `is
de minimis kenmerk` is stored *verbatim* as `de minimis` (article inside the name); stripping it would drop
the match, or bind a shorter sibling (`de minimis voordeel` vs `minimis voordeel`). Verbatim-first binds a
kenmerk whose name really begins with an article to *itself*, and only the article-stripped retry recovers
the bezittelijk case where the article is the kenmerk's own (stored stripped) but the parse left it on.

Still `Onopgelost` and carded: the objecttype/role-membership lowering itself (positive `is een <X>`,
negative `is geen <X>`), and a navigated-DOEL kenmerk on a to-many role (p32).

### Inflected kenmerk forms in a die-subselectie (§5.6) — bound morphologically

A bijvoeglijk kenmerk is declared in its base/predicative form (`is minderjarig kenmerk`, §3.5), but a
subselectie writes it nominalized: the spec's *own* canonical §5.6 example (spec line 1958) reads `het
aantal passagiers van de reis die een **minderjarige** zijn` against that declared `minderjarig`, and
§5.7 does the same. Exact (case-insensitive) equality misses the `-e` inflection, so the spec's primary
subselectie example silently stayed a marker (an honest no-op — never a wrong count, but the count was
lost). `KenmerkMatcher` now binds the written form to a declared kenmerk through the **regular Dutch
adjectival `-e` inflection**: the plain suffix (`minderjarig`→`minderjarige`, `groen`→`groene`) OR, for
a base ending in a long-vowel digraph plus one consonant, the syllable-opening shortening alone
(`groot`→`grote`, `duurzaam`→`duurzame`) — never the bare non-word `groote`. An exact base match is
always preferred over an inflected one; the inflection pass is **ambiguity-safe** — if a written form
is the regular inflection of more than one declared kenmerk it binds NONE (an honest marker), so the
result is independent of declaration order (a long-vowel base and a short-vowel base can share an
inflected form: `groot`→`grote` and the unmodelled-doubling `grot`→`grote`). A predicaat naming an
*undeclared* kenmerk stays a marker. Applied uniformly to the die-filter and the subject kenmerk
check.

A **multi-word** bijvoeglijk kenmerk inflects on its **head** word, not its tail: the adjective is the
first token and the rest is a prepositional/complement tail carried verbatim, so `bereikbaar per trein`
→ `bereikbare per trein` (the head's digraph-shortening still applies, `groot bereik`→`grote bereik`) —
never the tail-inflected non-word `bereikbaar per treine`. The exact-base-first and ambiguity-safe
guards are unchanged, so a tail-inflected non-word still does not bind.

Deliberately **not** modelled (each stays an errs-safe marker, never a wrong bind): stress-dependent
consonant doubling (`wit`→`witte`) and consonant voicing (`vies`→`vieze`) — they would mis-transform
unstressed endings (`-ig`, `-el`); and the declared **meervoudsvorm** (`(mv: …)`, §13.2.29) — the
grammar's predicaat has no bare-plural-noun alternative (`die minderjarigen zijn` does not parse) and
the spec writes the singular nominalized form even for a plural subject, so a meervoudsvorm has no
reachable predicaat input to bind. Both are carded (p29) should a real input ever demand them.

### Aggregation over a dimension (§13.4.17.51-.54) — a fold over static labels, not an accumulate

A `som`/`min`/`max` over a **gedimensioneerd attribuut** carrying a **dimensieselectie** —
`over alle <dim>`, `over de <dim> vanaf <label> t/m <label>`, `over de <dim> in {…}`, or the bare
`… vanaf <label> t/m <label>` (§5.8.5) — aggregates over a **dimension's label set**, not over object
instances. The reading is taken straight from §5.8.5: a gedimensioneerd attribuut holds one value per
label (per the cartesian product of its dimensions, §3.6), the labels are an **ordered symbolic set**
(§13.3.7.5), and `vanaf a t/m b` selects the **contiguous interval** `[a..b]` inclusive *in the declared
order*. The labels are therefore **static** — known at transpile time — so the resolver expands the
interval/set to a concrete label list (`Expr.DimensieSelectie{dimensie, labels}`) and the transpiler
lowers the aggregation to a **fixed fold of `map.get(label)`** over exactly those labels via a registered
DRL helper (`rsDimSom`/`rsDimMin`/`rsDimMax`), with the dimensioned attribute modelled as a
`Map<label, value>` field. This is *not* a Drools `accumulate` — there is no instance set to range over.
Key points of the reading:

- The "relative" jaardimensie labels (`vier jaar geleden`, `heden`, …) are **opaque ordered strings**,
  never date arithmetic — §5.8.5 defines the interval purely by declared order, so no temporal model is
  needed. This is what makes the §5.8.5 form lowerable while the genuinely temporal forms below are not.
- §5.8.2 leegwaarde: an empty cell (`map.get(label) == null`) is **skipped**; an all-empty fold yields the
  `of … als die er niet zijn` fallback, else `null` (an empty sum, never a silent 0).
- Only the rule subject's **own** dimensioned attribute folds (`zijn betaalde belasting in jaar`, the
  §5.8.5 case). A **navigated** operand is deferred: a to-MANY read (`de prijs in jaar van alle
  passagiers`) is a 2-level aggregation (over instances *and* labels) the single-Map fold cannot express
  — resolving it would drop the marker and let the instance accumulate `sum(getMapField())`, a miscompile
  — and a to-one navigated fold has no spec/corpus example yet.
- The attribute must carry **exactly one** dimension; the bare `vanaf … t/m …` form leaves the dimension
  implicit (unambiguous only for a single-dimension attribute), and an over-form's named dimension must be
  the one the attribute carries. A **multi-dimension** attribute (§3.6 `inkomen` over jaardimensie *and*
  brutonettodimensie) is keyed by label combinations, which the single-label fold would mis-read, so it
  stays a marker.
- A reversed interval, an unknown label, a multi-operand fold, or a scalar operand stays the marker.
- Grammar shadow: the named-interval over-form `over de <dim> vanaf a t/m b` (§13.4.17.53) is currently
  **unreachable** — the greedy `kaleNaam` absorbs `over de <meervoud>` into the attribute run — so only
  `over alle <dim>` (§.52), `over de <dim> in {…}` (§.54), and the bare `… vanaf a t/m b` (§5.8.5) reach
  the resolver. It fails safe (marker), and no corpus/spec example exercises it; carded.

What is **still** marked, not lowered (the temporal remainder of p5): a `totaal`/`tijdsevenredig deel`
**conditiebijexpressie** (`gedurende de tijd dat …`, spec-main-81/84-87), and a dimensieselectie whose
operand is not a single gedimensioneerd attribuut. These keep the resolver's `Onopgelost` filter +
WARNING, so the transpiler markers them (`/* TODO aggregatie … */` no-op) rather than aggregating the
whole set unrestricted. The `gedurende de tijd dat` form is deliberately **not** collapsed to a boolean
"currently true" guard: without a real time axis that loses the §5.1 piecewise period model, so it stays an
honest marker rather than a silently-degraded fire. Temporal IR stays carded (p5/p2).

The **declaration side of the temporal model is now captured** (§13.3.6), the IR-shape foundation for that
carded Phase-7 value model. An attribute or kenmerk with a tijdlijn — "de premie Bedrag **voor elk jaar**",
"is duurzaam kenmerk **voor elke dag**" — records a `Granulariteit {DAG,WEEK,MAAND,KWARTAAL,JAAR}` in a
parallel `attribuutGranulariteit` / `kenmerkGranulariteit` map on the `Objecttype` (mirroring
`attribuutDimensies`, so the `Datatype` hierarchy and the typeringen matrix are untouched). `member()`
previously read the datatype, plural and dimensions but **never** `a.tijdlijn()`, so a temporal attribute
was indistinguishable from a single-value one. Both the single-period token form ("voor elke dag" → DAG)
and the multi-period plural unit ("voor elke 5 maanden" → MAAND) reduce to the unit; the period **count**
("voor elke 5 maanden" → 5) rides in a parallel `attribuutTijdlijnAantal` / `kenmerkTijdlijnAantal` map (absent
= 1), the **startpunt** (vast/variabel) is not yet captured. The named form "voor elke `<tijdlijn>`" adopts
the granularity of its standalone `Tijdlijn` definition ("Tijdlijn de periode … heeft tijdvakken van een
kwartaal" → KWARTAAL): the definitions are registered in pass 1 so declaration order is free, and the
reference is matched to the definition on a whitespace-collapsed, leading-lidwoord-stripped name (the
reference drops the "de" the definition carries and may wrap across lines). An unresolved name (the
definition living in another file — the corpus splits them per construct) leaves the attribute untagged,
no granularity faked. Resolver-only: most periods (knip-merge, tijdsevenredig, gedurende) are still unlowered,
and the 193-file corpus DRL is byte-identical. A **duplicate timeline name** (two `Tijdlijn` definitions sharing
a normalised name) now **WARNs** at registration, since a "voor elke `<naam>`" reference adopts the first via
`findFirst` — the ambiguity is made visible rather than silently resolved.

**The first temporal VALUE operator is lowered: §7.1 "het totaal van `<temporeel attribuut>`".** A temporal
attribute's runtime value is a piecewise `List<Segment{van,tot,waarde}>` on the bean (a new
`io.github.yhavinga.regelspraak.facts.Segment` POJO — the externally-provided timeline contract). `tijdreeksAggregatie`
recognises a `TOTAAL` over a single OWN temporal attribute (by the captured granularity) and lowers it to a
registered `rsTijdTotaal` helper that folds the list, weighting each segment's value by the count of whole
DAG/WEEK/MAAND/KWARTAAL/JAAR periods between its bounds (a kwartaal is three maanden; `ChronoUnit` has no
quarter). A null list or a segment with a null bound/value contributes nothing (§4.20 leegwaarde); an empty
timeline totals 0. It is a transpiler-only recognition — **no** new sealed-`Expr` member — so a NON-temporal
`TOTAAL` (the ordinary set sum) still flows to the instance-accumulate path; the corpus DRL is byte-identical,
proven by firing the spec's €186 worked example (12 mnd × 10 + 6 mnd × 11) and the empty/null-timeline cases in
real Drools. Two further temporal totaal forms now lower. A **`gedurende de tijd dat <temporeel kenmerk>`**
restriction (§7.1's €71 case) is structured — when the totaal is over the subject's own single-period temporal
attribute and the voorwaarde is a simple membership naming a single-period temporal kenmerk of the
subject — into a temporal `Conditie.Kenmerk` (via `KenmerkMatcher`, tightly gated so the accumulate path never
misreads this subject-kenmerk as an element die-filter), which the transpiler clips the value timeline by:
`rsTijdTotaal(rsTijdKnip(getValue(), getKenmerk()), "MAAND")`, where `rsTijdKnip` intersects each value Segment with
the kenmerk's TRUE-period Segments. **Both membership verbs bind** — a bijvoeglijk/zelfstandig kenmerk reads `is
<k>`, a bezittelijk one `heeft (een/het) <k>` (§3.5); the verb is not the discriminator, the single-period temporal
granularity is — so the spec's **verbatim** §7.1 example ("gedurende de tijd dat hij een recht op
belastingvermindering **heeft**") now lowers, as does the canonical `heeft het <k>` (article-stripped) form. A
**NEGATED** condition ("gedurende de tijd dat hij **geen** recht … heeft" / "niet rechthebbend is") clips to the
kenmerk's FALSE periods — the complement — via a registered `rsTijdKnipNiet` helper that subtracts the condition's
true-intervals from each value segment and keeps the gaps (clamp-overlap + selection-sort + gap-walk, plain Java so
a Drools function body compiles it; a null/empty condition leaves the whole value). A **navigated** to-ONE temporal
totaal ("het totaal van de X per maand van zijn partner") folds the partner's timeline via `rsTijdTotaal` over the
same root-guarded getter chain the navigated value read uses (a null reference → leeg). All fire (the €71 clip; the
€115 complement = €186 − €71; a partner's 12-month series → 120). A **§8.4.2 COMPOUND** condition ("gedurende de
tijd dat er aan **alle** / **ten minste 1** van de volgende voorwaarden wordt voldaan: • … • …") now lowers when
every bullet is a single-period temporal-kenmerk membership of the subject: `alle` (AND) nests the per-kenmerk clips
(`rsTijdKnip` / `rsTijdKnipNiet` — the intersection of true-periods), and `ten minste 1` (OR) clips the value to the
UNION of the per-member true-regions. A positive member's region is its getter timeline; a **NEGATED** member's
region is `rsTijdKnipNiet(value, getter)` (= `value ∩ ¬kenmerk`, the complement bounded by the value's own span
rather than an unbounded `¬kenmerk`); the regions are unioned (`rsTijdUnie` coalesces overlapping/adjacent half-open
intervals) and clipped once, so `value ∩ (A ∪ ¬B ∪ …)` is exact with no double-count. The single-bullet resolution
is factored into `temporeelKenmerkUitOperator`, reused per bullet. Fired the intersection (€30), the positive union
(€131), and `rechthebbend ∪ ¬actief` (€126). The **set guard** that tells a collapsed
set-totaal from a single-object timeline is now a structural `Expr.AttribuutRef.set` (from the onderwerp's §13.4.2
quantifier — any kwantor that is not the bare `de`), not an `onderwerpBron "alle "` text scan, so it also catches the
threshold quantifiers (`ten minste 2 van de passagiers`) the text scan missed and is immune to casing/spacing. Still
**markered, not faked**: a **multi-period** attribute ("voor elke 5 maanden", inline OR named) — semantics §7.1 does
not work out (the count rides on the `Tijdlijn` record + `attribuutTijdlijnAantal`); a **set** totaal ("het totaal
van `<temporeel attr>` van een set") — a per-element timeline fold (fold each element's series, then total) is not
lowered, so it markers rather than mis-folding the subject's own series or summing the `List<Segment>` (when the
element type equals the subject's the resolver collapses the set to an EIGEN ref, caught by the structural `set`
flag; a distinct element type is caught in `accumulate`); a **to-many** navigated totaal; a nested
compound, or any other kwantor (`ten minste >1` / `geen van de` / `ten hoogste` / `precies`) — each leaves the WHOLE
compound an `Onopgelost` marker, never a silently-dropped predicate; and any single gedurende the resolver could not
structure (a comparison / navigated / non-temporal condition). Carded follow-ons: the pre-existing resolver set→own
collapse (the deeper fix — the structural `set` flag now hardens the guard but the per-element fold is still
unlowered) and the navigated EEN-form bezittelijk gedurende;
`rsTijdTotaal` truncates a sub-period-aligned segment (exact for boundary-aligned timelines) and `rsTijdKnip` does not
union overlapping condition intervals (a contractually-disjoint kenmerk timeline — `rsTijdKnipNiet`, `rsTijdUnie` and
`rsAantalDagenIn` handle overlap by gap-walking/coalescing/bucketing).

**The first temporal-WRITE path is lowered: §13.4.17.57 "het aantal dagen in `<de maand|het jaar>` dat".** Writing a
TIMELINE-valued result into a temporal (`voor elke maand`) attribute was unsupported — the `regel()` doel setter
always emitted a SCALAR `setX(value)`, and the §7.2 day-count expression (whose `dat`-condition is a direct grammar
child, not a `conditiebijexpressie`) was dropped to a marker. Now, when the doel is a single-period temporal
attribute (granularity present, not multi-period, not navigated), the setter writes a `List<Segment>` via
`temporeleWaarde()`. The first (and so far only) timeline producer, `het aantal dagen in de maand dat <temporeel
kenmerk>`, lowers to a registered `rsAantalDagenIn(getKenmerk(), "MAAND"|"JAAR")` helper that buckets the kenmerk's
true-period timeline into calendar months/years and counts, per bucket, the days the kenmerk holds — one output
Segment per non-empty bucket carrying that day-count (the §7.2 example: true `[1-1..2-1)` + `[10-1..14-2)` → **23 dg
in Jan, 13 dg in Feb**, fired in real Drools). The resolver structures the `dat`-condition into a `Conditie.Kenmerk`
(reusing `temporeelKenmerkUitOperator`); the bucket granularity is taken from the DOEL's tijdlijn (§7.2 requires the
expression's `in <maand|jaar>` to match it). Tightly gated — a **non-temporal** doel keeps the scalar path, and a
**multi-period** / **navigated** doel, or a non-kenmerk (comparison/compound/navigated/negated) `dat`-condition,
stays an honest `/* TODO */` marker, never a scalar setter on a `List<Segment>` field. The corpus is byte-identical
(the only `aantal dagen in` file, spec-main-82, is a standalone fragment whose doel has no declared tijdlijn, so the
gate does not fire — proven on the firing fixture instead). `tijdsevenredig deel` (§13.4.17.58) is the second
producer on this path (next section).

### Tijdsevenredig deel (§13.4.17.58 / §7.3.2) — the second temporal-WRITE producer, over a TEMPORAL-attribute base

"het tijdsevenredig deel per `<maand|jaar>` van `<expressie>` gedurende de tijd dat `<temporeel kenmerk>`" prorates a
base value-timeline per calendar bucket by the fraction of the bucket the condition holds — `baseValueInBucket ×
trueDays / totalDays` per month/year, written as a `List<Segment>` to a single-period temporal doel attribute. It
lowers to a registered `rsTijdsevenredig(getKenmerk(), getBasis(), "MAAND"|"JAAR")` helper that buckets the
condition's true-period timeline (like `rsAantalDagenIn`) and, per bucket, reads the base value from the base timeline
(the segment covering the bucket start) and prorates it. Fired in real Drools: the spec's **example-1** numbers via a
timeline base (12 full months of €12 in 2024, €18 jan-2025, **7/28 × €18 = €4,50** feb-2025) and the per-jaar
**684/365** of example 2.

Three deliberate readings, each the honest alternative to a silent miscompile (a scoping review flagged all three):

- **The base must be a single-period TIMELINE — a temporal attribute OR a temporal parameter; a plain SCALAR base
  markers.** §5.1.2 (spec line 1536) makes this the *only* operator that combines the Tijd unit-system with an
  operand's *value*-timeline — the base's own timeline is intrinsic, not optional, and all three §7.3.2 examples use a
  *time-varying* parameter (`de STANDAARD BELASTINGVERMINDERING`, "kan jaarlijks wijzigen"). A genuinely scalar base
  prorated as a constant would be **wrong on those examples** (it loses the 12-vs-18 split), so it is **not** lowered:
  the resolver's `gedurendeKenmerkFilter` operand gate accepts only the subject's own single-period temporal attribute
  or a temporal parameter; a scalar operand leaves the filter unset → the §13.4.17.51-.58 WARNING **and** a `/* TODO */`
  marker both stand. The **temporal parameter** is now modeled (a `tijdlijn` on `parameterDefinition`, a `Granulariteit`
  on `Parameter`, emitted as a `global java.util.List` the runtime supplies) — so the **literal §7.3.2 examples** (a
  parameter base) now lower and fire (`rsTijdsevenredig(cond, <param-list-global>, gran)`; €12/€18/€4,50). Any **scalar**
  read of a temporal parameter markers (a `List` in a scalar slot would not compile).
- **No auto-rounding (§4.22 / §6.1.2).** §4.22 keeps the result an exact fraction (`684/365`, not a decimal); §6.1.2
  forbids the engine rounding a result down to the doel's declared decimals (the author must add an explicit afronding,
  for which `tijdsevenredig` has no syntactic slot). The per-bucket division therefore uses **`MathContext.DECIMAL64`**,
  not `setScale` to the doel precision: terminating fractions (4,50 / 3,75) come out exact, the non-terminating
  `684/365` is stored at DECIMAL64 precision. (The truly-irrational tail is the carded exact-rational gap, shared with
  §6.5 division.)
- **A base/doel unit mismatch markers.** §7.3.2 example 3 (€/jr base → €/mnd doel) requires a ÷12 rate conversion
  *before* proration. The temporal-WRITE path applies no `omrekening`, and `TIJDSEVENREDIG_DEEL` types as `Onbekend`,
  so an unconverted €/jr value would land in a €/mnd field off by 12. The lowering instead requires the base operand's
  unit to **equal** the doel's (both unitless counts as equal) and markers otherwise — the rate conversion is deferred.

Also markered (honest, never a wrong period): an operator/doel **granularity mismatch** (`per jaar` into a `per maand`
doel — the resolver resolves the filter but the transpiler markers), a **negated** gedurende (`geen` — the complement
proration is unsupported), a **navigated** base or condition, and a **non-numeric**
base. The corpus is byte-identical (the four `tijdsevenredig` files, spec-main-84..87, are standalone fragments whose
base/doel are undeclared, so the gate does not fire — proven on firing fixtures instead).

**A COMPOUND condition on the temporal-WRITE producers (§8.4.2).** Both writers — `aantal dagen in <maand|jaar> dat …`
and `tijdsevenredig deel … gedurende de tijd dat …` — accept a `§8.4.2` compound ("er aan **alle** / **ten minste 1**
van de volgende voorwaarden wordt voldaan: • … • …") of single-period subject kenmerks, not only a single kenmerk. A
shared `conditieTijdreeks()` builds the condition's TRUE-period timeline: a single positive kenmerk is its getter; a
compound folds the members' getters into ONE boolean timeline — **ALLE** = intersection (nested `rsTijdKnip`, reusing
the clip helper on two boolean timelines), **ten minste 1** = union (`rsTijdUnie`) — which then feeds `rsAantalDagenIn`
/ `rsTijdsevenredig` unchanged. The single-kenmerk output stays byte-identical (the same getter string), so it is pure
generalization. Fired: an ALLE aantal-dagen intersection (Jan 22 / Feb 13 dg) and a ten-minste-1 tijdsevenredig union
(€18 / €4,50, overlap coalesced). A **negated** compound member markers — unlike the `totaal` CLIP (where the value
timeline bounds the complement via `rsTijdKnipNiet`), a WRITE producer has no value to bound a standalone `¬kenmerk`,
so combining it would be ill-defined; an honest marker over a mis-count. A mixed ALLE compound (a temporal kenmerk + a
non-temporal comparison) DOES lower on both producers via a value-side static guard (see "the MIXED compound" below);
a non-kenmerk / other-kwantor / mixed-OR compound still markers.

### The MIXED compound gedurende on a totaal (§8.4) — a static comparison as a value-side guard

§8.4's *primary* illustration of a compound `gedurende de tijd dat` mixes a temporal kenmerk with a **non-temporal**
comparison — "er aan **alle** volgende voorwaarden wordt voldaan: • hij heeft een recht op belastingvermindering •
zijn geboortedatum is eerder dan 1-1-2002" — and states the compound "is als geheel wel tijdsafhankelijk". The whole
compound used to marker. On the **`totaal`** path it now lowers: the resolver (`gedurendeSamengesteld`, **ALLE only**)
admits a non-temporal **comparison** member, built as a `Conditie.Vergelijking` via RuleChecker's now package-static
`cmpOf`/`naarOperator` (shared, not duplicated); the transpiler **partitions** the compound — temporal kenmerks clip
the timeline (`rsTijdKnip`, intersection), the static comparison becomes a value-side guard wrapping the totaal:
`(geboortedatum < 2002 ? rsTijdTotaal(clip, gran) : java.math.BigDecimal.ZERO)`. The guard rides in the **consequence**,
so it uses the value-side `$subject.getX()` form (not the when-side bare property) and null-guards each operand
(`lv != null && rv != null && lv <op> rv`); MVEL compares `LocalDate` via `Comparable`. Fired: born 1990 (< 2002) → the
clipped €71; born 2005 (≥ 2002) → 0. The static comparison can be **ordering/datum** (empty → onwaar, Tabel 40-43),
**GELIJK** (waar for both-empty numeriek, Tabel 37; onwaar for both-empty non-numeriek, Tabel 38), or **ONGELIJK**
(waar for gevuld-vs-empty, onwaar for both-empty, Tabel 44) — `statischeVergelijkingGuard` builds each leegwaarde form.
The mixed ALLE compound also lowers on the **WRITE producers** (aantal-dagen / tijdsevenredig): `conditieTijdreeks`
takes the static member as the guard (not part of the timeline) and `mixedGuardWrap` wraps the per-bucket producer call
— `(guard ? rsAantalDagenIn(clip, gran) : new java.util.ArrayList())` — so a false guard yields an empty doel timeline
(fired: 31/13 dg when born <2002, an empty timeline otherwise).

A mixed **OR** (`ten minste 1`) on the **`totaal`** now lowers too — with semantics *distinct* from ALLE. Because a
static member has no time-variation, a TRUE static member satisfies the OR across the **entire** span, so the totaal
WIDENS to the un-clipped value timeline; a FALSE static member leaves only the temporal-union clip. So the guard
chooses **full-vs-clip**, not (as for ALLE) clip-vs-ZERO: `(orGuard ? rsTijdTotaal(volleReeks, gran) :
rsTijdTotaal(clip, gran))`, where `orGuard` ORs (`||`) its static members (`statischeGuard` gained a separator
overload), `volleReeks` is the un-clipped getter and `clip` the temporal-union (`rsTijdKnip`/`rsTijdUnie`). The
resolver (`gedurendeSamengesteld`) admits the comparison member for `ten minste 1` too; `tijdreeksAggregatie` captures
the pre-clip timeline and branches on the kwantor. Fired: "recht heeft OR leeftijd == 18", leeftijd 18 → the full €186,
leeftijd 19 → the clipped €71 (the inverse of the ALLE case 18→€71/19→0). The OR-mixed is **not** lowered on the WRITE
producers — `conditieTijdreeks` returns null on a comparison member when `!alle`, so an OR-mixed aantal-dagen /
tijdsevenredig stays an honest marker (the static-widen is unexpressed there). Still markered everywhere: a
non-comparison non-temporal member, a compound with **no** temporal kenmerk, `ten minste >1`, and (always) a comparison
on a temporal attribute. Pure-temporal compounds and the ALLE mixed compound are byte-identical (the partition passes
the original Samengesteld unchanged for them; the corpus is byte-identical 73/27).

### §8.2 / §13.4.15.70 — a tijdsafhankelijk read as a scalar markers (the gehele-periode check)

A **tijdsafhankelijk** (temporal, `voor elk jaar` / `voor elke maand`) attribute or kenmerk is a `List<Segment>`
timeline at runtime, **not** a scalar. A **bare** read of one in a scalar slot is unsupported and now MARKERS, on both
the value/then side (`waarde`) and the condition/when side (`operand`), instead of leaking the `List` into a scalar DRL
term. Before this guard, an **accepted** input (zero diagnostics) compiled to silently-wrong / build-sinking DRL — the
recurring "List-in-scalar leak" class — in five forms, all confirmed: a when-side comparison (`loon == new
BigDecimal("1000")`), an own-subject temporal-kenmerk check (`actief == true`), a to-one **navigated** temporal-kenmerk
check (`reis.actief == true`), a then-side value into a scalar doel (`setPrijs($subject.getLoon())`), and a **navigated**
temporal attribute in `is leeg`/`gevuld` (`reis.loon == null` — this one *compiles*, `List == null` being type-valid in
mvel, but tests the timeline's nullness, so it is silent-wrong rather than a build break).

This also resolves the **§8.2 gehele-periode check** (`<geheleperiodevergelijking> ::= [niet] gedurende (het gehele
jaar | de gehele maand)`, §13.4.15.70). The grammar parses the optional `gehelePeriodevergelijking` qualifier on every
predicate, but no lowering honoured it, so it was **silently dropped** — `… gedurende het gehele jaar gelijk is aan 10`
resolved byte-identically to the bare `… gelijk is aan 10`. Per §8.2 the onderwerp of such a predicate is by definition
tijdsafhankelijk, so the predicate now markers via the same guard rather than dropping the whole-period semantics. All
§8.2 forms are covered — comparison, `leeg`/`gevuld`, `een <kenmerk>` check, and the negated `niet gedurende` — as is a
temporal operand nested in condition-side arithmetic (the `operand`/`waarde` descent recurses through
`Binair`/`Afgerond`/`Begrensd`).

The guard (`isTemporeelAttribuut`, the `attribuutGranulariteit` membership test) lives at the leaves — `waarde`,
`operand`, `genavigeerdLeegGevuld`, `operandDeel` — plus a `kenmerkGranulariteit` check in the scalar `Conditie.Kenmerk`
lowering, whose owner is the subject for an own kenmerk or the reference role's far type
(`model.rolDoeltypeNaarSubject`) for a navigated one. It cannot over-mark: the **legitimate** temporal reads — `het
totaal van`, `het tijdsevenredig deel per …`, `het aantal dagen in … dat`, and the `gedurende de tijd dat <kenmerk>`
clip — arrive as an `Expr.Functie` / structured filter and are intercepted **upstream** (`tijdreeksAggregatie` /
`temporeleWaarde` / `temporeelKenmerkTijdreeks`), which build the timeline getter directly and never descend the guarded
leaf; a non-temporal attribute/kenmerk (own or navigated) still lowers normally. Corpus byte-identical (73/27 — the
corpus temporal reads are all aggregations). **Deferred** (not yet lowered, an honest marker): the *true* §8.2 semantics
— a whole-period predicate over a timeline (the comparison/check holding at every moment of each calendar year/month),
which needs a temporal-boolean reduction helper over the `List<Segment>`, like the gedurende clip but yielding a scalar
per period.

### Elfproef predicate (§8.1.3) — lowered to a registered rsElfproef helper

"voldoet aan de elfproef" type-checked but the transpiler's predicate switch markered it (a `false /* TODO */`
no-op that never fired). It now lowers to a registered `rsElfproef(Object)` helper: per §8.1.3 it weights each
digit by its position from the right (the controlecijfer −1, then 2, 3, …; an n-digit reeks weights its leftmost
digit by n down to 2, the last by −1) and passes iff the weighted sum **divided by 11 is a positief geheel getal** —
i.e. `sum > 0 && sum % 11 == 0` (a review caught that `% 11 == 0` alone wrongly accepts a weighted sum of 0, e.g.
"12" or any not-all-zeros reeks summing to 0; the `sum > 0` guard also subsumes the not-all-zeros rule, and a
negative multiple of 11 is unreachable). It admits a Numeriek (geheel getal) — leading zeros already gone — or a
Tekst of digits (preserving voor-/achterloopnullen); a null, fractional, or non-digit value fails. A
boolean-returning function is valid in a pattern constraint and fires (the spec's 192837465 fails, sum 205 rest 7;
111222333 passes, sum 66; "12" fails, sum 0). The sibling default-arm
predicates `numeriek met exact <n> cijfers` and the dagsoortcontrole stay markered — carded.

### Timed dedato literal is a Datum-tijd, not a Datum in dagen (§13.2.19/.21)

A dedato literal carrying a time — "dd. 12-3-2023 **11:34:42.679**" — is a §13.2.21 Datum-tijd (millisecond
precision). The resolver typed every dedato as Datum in dagen, and the literal lowering matched only the
day-month-year groups and emitted `LocalDate.of`, silently dropping the time AND assigning a `LocalDate` into a
`LocalDateTime` setter (a Drools build/type error, a wrong runtime value). Now a dedato whose source carries an
`hh:mm:ss[.mmm]` component is typed `Datum-tijd` and lowers to `LocalDateTime.of(y,m,d,H,M,S,nanos)` with the
millisecondes scaled to `nanoOfSecond` (× 1 000 000, the `rsDatumtijdMet` convention); a bare date is unchanged
(`LocalDate.of`). Only the timed form is new — the bare date is the only form in the TOKA casus and every worked
example — so the corpus DRL is byte-identical; proven on a synthetic fixture firing in real Drools.

Date-literal components are emitted as **decimal ints**, not the raw captured strings: `08-09-2024` lowers to
`LocalDate.of(2024, 9, 8)`, never `of(2024, 09, 08)` — a leading-zero `08`/`09` is an INVALID OCTAL literal in
mvel/Java that fails the whole KieBuilder build (an accepted input must never compile to crashing DRL). The time
components and the `datumLit()` geldigheid guard already used int parsing; only the date-component splice in
`literal()` needed it. Corpus byte-identical (no corpus date uses a leading zero, so the difference only manifests on
08/09 inputs); fired through KieBuilder.

### Tekstreeks `«…»` string-building (§5.4/§13.4.17.9) — lowered via a lexer sub-mode

A tekstreeks `"nummer «het identificatienummer …» einde"` builds a string by **substituting** each `«…»`
part at evaluation time. This now lowers (Tier-2; the earlier honest-ERROR safety net is retired). The lexer
gained a **`TEKST_MODE` sub-mode**: a `"` (`STRING_START`) enters it, where `STRING_TEXT` matches literal
karakterreeks chunks, `«` (`L_ANGLE_QUOTE`) `pushMode(DEFAULT_MODE)` so the embedded **expressie** is lexed
with the full token set, `»` (`R_ANGLE_QUOTE`) pops back, and `"` (`STRING_END`) closes. A plain `"…"` (no
guillemets) re-tokenises as `STRING_START STRING_TEXT? STRING_END`, which the parser (`tekstWaarde : STRING_START
tekstReeksDeel* STRING_END`) reassembles into the **same** `Tekst` literal — so the corpus DRL stays
byte-identical (the only stderr delta is one syntax-error message naming the renamed `STRING_START`). An
interpolated string builds an `Expr.TekstReeks` (literal-chunk + `«expressie»` parts, each a full recursively
resolved expressie, §5.4 any datatype coerced to Tekst); the transpiler emits an mvel concat `("nummer " +
rsTekst($subject.getX()) + " einde")`, each part coerced by the null-safe `rsTekst` helper (a leeg value → `""`,
never `"null"`). A markered `«…»` part propagates (the `Frag` marker), so an unlowerable part makes the whole
tekstreeks an honest marker. Because the embedded expression reuses `DEFAULT_MODE`, `»` pops the mode **only when
the mode stack is non-empty** (`if (!_modeStack.isEmpty()) popMode()`); a stray top-level `»`/`«` is then an
ordinary token the parser rejects with a syntaxfout, never a mode-stack-underflow crash. `STRING_LITERAL` was
used in exactly one parser rule, so the blast radius was tiny. Carded refinements: `rsTekst`'s boolean/date/
number text form is `o.toString()` (English `true`/`false`, ISO dates) — §5.4 mandates no specific format beyond
the numeric example, refine if a fixture needs Dutch/locale text.

### Reading a single dimension cell (§3.6) — peel `<voorzetsel> <label>`, marker an under-specified read

A gedimensioneerd attribuut read in a rule must pin every dimension (§3.6: "dienen dan altijd alle
dimensies te worden aangegeven"). The label is woven into the noun phrase: the suffix form
`<attribuut> <voorzetsel> <label>` (`betaalde belasting in jaar van huidig jaar`, the dimension's
declared read preposition) or the prefix form `<label> <attribuut>` (`bruto inkomen`). The grammar gives
this no non-terminal — the `kaleNaam` swallows the whole run — so the **resolver** splits it: a bare name
that is not a declared attribute is retried as a dimensioned read by peeling the longest declared
attribute off the head and matching `<voorzetsel> <label>` against the attribute's single dimension. It
lowers to an `Expr.DimensieCel` and then to `<map-read>.get("label")` (the dimensioned attribute's field
being the `Map<label,value>` from the aggregation reading above).

- Scope: a **single-dimension** attribute via the **suffix-voorzetsel** form, read as the subject's OWN
  attribute (`zijn …`), on the **value (then)** side. The prefix (`bruto inkomen`) form, a **multi-dimension**
  cell (it needs every label and a composite key), and a **navigated/definite** read (`de … van <label> van
  een <type>`, which the two-`van` `kaleNaam` does not cleanly split) all stay honest markers — carded.
- On the **condition (when)** side a dimensioned cell is deferred to a marker (`-> false /* TODO
  voorwaarde */`). A Drools PATTERN constraint cannot host the value form's null-guarded ternary (the inline
  `? :` mis-parses, and a null cell vs a number is a build-breaking type error), and a correct null-safe cell
  comparison needs a guarded conjunction the single-operand path cannot express. So the rule does not fire,
  never a wrong or uncompilable comparison. Carded. The under-specified guard below applies on BOTH sides
  (`operand()` as well as `waarde()`), so a bare dimensioned read in a voorwaarde is a marker, not a
  build-breaking `prijsInJaar > 5` Map-vs-number term.
- An **under-specified** read — a bare dimensioned attribute used as a value without a label — is an error
  (§3.6). Because a dimensioned field is a `Map`, emitting its bare getter where a scalar is expected is a
  silent miscompile, so a bare `AttribuutRef` to a dimensioned attribute lowers to a marker
  (`/* TODO ongedimensioneerde lezing … */`). The bare getter is still emitted, deliberately, for the two
  legitimate internal uses — the dimensieaggregatie source and the `DimensieCel` map read — which go
  through `attribuutGetter` and so bypass that guard. An unknown label likewise markers (not a partial read).

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

A **multi-operand min/max value-fold over to-many sets** — "de hoogste van de prijs van alle vluchten
**én** de andere prijs van alle vluchten" (§5.8.3) — is now a `/* TODO aggregatie MAXIMUM/MINIMUM */`
**marker** rather than a silent fold over only the first set. The accumulate path reads one source
pattern (`argumenten().get(0)`), so a second set operand (and any die on it) would be dropped; only
`AANTAL` carried a multi-operand guard, so `MINIMUM`/`MAXIMUM` silently lost the rest. The guard bails
the over-a-set multi-operand case to a marker. A **scalar** multi-operand fold ("de hoogste van a en b"
of one object) is unaffected — it lowers to a nested ternary via `concatenatieAggregatie`, which runs
*before* `accumulate` — and the **single-operand** set min/max (the canonical §5.8.3 example) still
emits its accumulate. Carded: the real per-operand multi-set fold (one accumulate per set, combined),
and the analogous `SOM`/`TOTAAL` multi-set case.

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

**Same name-boundary family — lowercase `geldig` inside a regelnaam.** The version header
(§13.4.2 `geldig <versiegeldigheid>`) is recognised by the lowercase keyword `geldig`, so a
regelnaam CONTAINING a lowercase `geldig` (e.g. "Regel bepaal geldig verblijf") cannot be
written: the name is cut at the keyword and the parse fails. The failure is a proper syntax
error (never a silent mis-parse) but with confusing FOLLOW-UP diagnostics — the truncated
header can double-register the rule name, yielding a phantom "geldigheidsperioden overlappen"
on a rule "#2" that does not exist in the source (probe P1 of the 20260701 review). A
capitalised `Geldig` at the START of a name is fine (distinct token, see the tolerance list
above); an interior capital `Geldig` also parses since only lowercase `geldig` is the keyword.
Workaround: rephrase the name ("bepaal geldigheid van verblijf" also works — the keyword is
the exact word `geldig`, not its derivations).


### Forms still modelled at main-clause word order (resolver-side concern)

- **V-final subordinate clauses** after `dat` (`… dat hij een recht … heeft`,
  §13.4.17.57): the toplevel-operator trailing-verb form (`… EEN kaleNaam HEEFT`)
  already accepts this surface, so it parses; a fully faithful tree would mark it
  as a subordinate clause, which the resolver can do from the operator label.

### §8.1.6 uniciteitscontrole — lowered as an accumulate-count violation rule

`de <attribuut> van alle <Type> moeten uniek zijn` lifts into `ResolvedUniciteit` (the subject type +
the key attribute(s)) and lowers to a rule that flags an instance whose key is not unique. "is uniek"
is waar when the key is unique, so the violation is *another* instance with the same key — recorded as
an `Inconsistentie`, the observable §9.5 result, exactly like the single-criterion consistentieregel.

The lowering **counts** rather than self-joins:
```
$subject : Type( $k0 : attr != null )
Number( intValue > 1 ) from accumulate( $o : Type( attr == $k0 ), count($o) )
not io.github.yhavinga.regelspraak.facts.Inconsistentie( regel == "<naam>", subject == $subject )
```
An `exists Type( this != $subject, attr == $k0 )` would read more directly, but Drools does *not*
exclude self inside `exists` for this shape — it flagged even a unique instance (verified by firing).
The count includes the subject, so `> 1` means a twin exists; this also flags each subject exactly once
*per evaluation*. The trailing `not Inconsistentie(…)` makes the consequence **idempotent across fire
cycles**: a duplicate inserted in a later cycle raises the count for an already-flagged subject and
re-activates the rule (no-loop blocks only re-activation from the rule's *own* consequence), which would
otherwise insert a second Inconsistentie. The same self-guard is on the single-criterion
consistentieregel, whose inserted Inconsistentie could likewise be re-inserted if its triggering facts
change. A **null** key cannot establish a duplicate (§8.1.6: two unknown identities are not the same
real-world object): the subject is matched only when its key is non-null, and a non-null `== $k0` never
matches a null-keyed instance, so nulls neither flag nor count.

The plural surface (`alle Vluchten`, `de burgerservicenummers`) binds back to the declared singular via
`MeervoudMatcher`. Per spec footnote 2 (line 450), *deriving* a plural from the name is an ALEF-specific
convenience, explicitly "separate from RegelSpraak" — the sanctioned source is the declared `(mv: …)`
form. So matching prefers the exact name and the declared meervoudsvorm; the synthesised regular plural
is a best-effort tooling fallback, generated **conservatively** (like the kenmerk `-e` inflection): `-s`
only for the unstressed `-e/-el/-em/-en/-er`/vowel endings, `-en` with long-vowel shortening
(naam→namen) but **not** for a consonant-doubling stem (man→mannen is unmodelled, so `manen`/`bomen`
— the real plurals of `maan`/`boom` — are never generated and cannot mis-bind), and ambiguity-safe.
The §5.7 **concatenatie** key (`de concatenatie van X en Y van alle <Type>`) lowers to the same rule
with a **composite** key — the accumulate matches every part (`luchthaven == $k0, vertrektijd == $k1`),
so only instances sharing the WHOLE combination are flagged. `CONCATENATIE_VAN` now accepts a
sentence-initial `De`, and each attribute name's absorbed leading article is stripped. A nested-`van`
attribute navigation in a concatenatie part (the spec's `luchthaven van vertrek`) does not resolve to a
single attribute, so that form stays a WARNING rather than building a partial key.

The cross-type **`verenigd met`** union (`de namen van alle Cirkels verenigd met de namen van alle
Vierkanten`) lifts to one rule **per source type**: a subject of that source is flagged when the total
count — summed over every source — of instances whose key equals the subject's exceeds one. So a value
shared by two Cirkels, or by a Cirkel and a Vierkant, is caught (§8.1.6's three cases). The sources join
by value positionally and must share key arity; the per-source rule names are distinct but the
Inconsistentie carries the one declared rule name, and the idempotency guard is on that shared name.

Uniqueness **scoped within a parent** (`de X van alle Y van <parent>`, e.g. seat numbers unique per
flight) lowers to the same count rule **restricted to the subject's scope**: the subject binds its scope
reference (`$scope : reis != null`) and the accumulate adds `reis == $scope`, so only instances sharing
the parent are counted. The element Y may be an object type or a **role** (resolved through the feittype,
whose role plural matches where the conservative object-type plural would not); the scope ref is the
connecting role field (`verbindendeRol`). The parent navigation is the type `kaleNaam`'s trailing
`van`-part; exactly one parent level is handled.

**Deliberately deferred (carded), each leaving a WARNING, never a silent drop:** a nested-`van`
concatenatie part; a scoped uniqueness with more than one parent level; and an object type whose plural
is irregular/multi-word and undeclared (`Natuurlijke personen` resolves only via a declared meervoudsvorm).

### §13.4.8 samengestelde consistentieregel — the `alle`-criteria form, fired on any violation

A compound consistency rule ("`<onderwerp>` moet voldoen aan alle volgende criteria: …", or the
impersonal "er moet worden voldaan aan alle volgende criteria: …") lifts into a `Conditie.Samengesteld`
over its bullet criteria and lowers like the single-criterion rule: the rule fires on the VIOLATION and
records an `Inconsistentie`. For the `alle` kwantor the violation is the **OR of the per-criterion
violations** — a subject is inconsistent when *any* criterion fails — each criterion reusing the
single-criterion null-guarded violation (so an empty operand is itself a violation, §9.5). The subject is
the type the criteria are about (the first criterion's left operand). `CONCATENATIE_VAN`-style
lowercase-only tokens were widened: `ER_MOET_WORDEN_VOLDAAN_AAN` now accepts a sentence-initial `Er`.

Deferred (carded), left as an honest WARNING (no inert eval-true rule): the `geen` / `ten minste` /
`ten hoogste` / `precies` kwantoren — their violation needs a count of *satisfied* criteria the single
violation-constraint cannot yet express — and a criterion that is itself not a lowerable comparison or
emptiness predicate (membership, elfproef, …) leaves the whole compound a WARNING via `transpileerbaar`.

**Both-empty `gelijk aan` over a non-numeriek type is a violation (typeringen Tabel 37/38).** For a
`gelijk aan` criterion, two empty operands evaluate to `waar` when the type is Numeriek/Percentage
(Tabel 37) but to **`fout`** for Tekst/Datum/Boolean/Enumeratie (Tabel 38). A consistentie criterion is
satisfied only when its comparison is `waar`, so the non-numeriek both-empty case is a violation. The
plain complement `l != r` reads `null != null` → false and would wrongly report the instance *consistent*,
so a non-numeriek equality violation is widened to `l != r || (l == null && r == null)`; the numeriek case
keeps the bare `l != r` (both-empty = `waar` = consistent). `ongelijk aan` needs no change — its both-empty
result is `onwaar` for every type (Tabel 44), which the existing `l == r` violation already reports. The
§9.5 reading of `fout` is the chosen one (a criterion is a violation unless its comparison is `waar`); the
spec does not spell out whether `fout` should instead be an ERROR diagnostic, so this is documented, not
silently assumed. The ordering operators' both-empty `fout` (Tabel 45) is unrelated — they take the
separate null-guarded `schendingConstraint` path.

The **voorwaarde (rule-guard) counterpart** (p40) is the mirror: a non-numeriek both-empty `gelijk aan` is
`fout` (§5.3 line 3264 calls it a *foutmelding*), and a rule fires only when its guard is `waar`, so the
rule must **not** fire. Plain mvel reads `null == null` as `true` (it would fire), so a non-numeriek equality
guard over two potentially-empty operands is narrowed to `l == r && !(l == null && r == null)`. This applies
only in a guard context — `conditie(..., alsVoorwaarde=true)`; the same method serves
`schendingConstraint`'s negation of a consistentie assertion, where a both-empty `fout` must still *fire*
the violation (an `ongelijk` consistentie negates to `gelijk`, and Tabel 44 makes its both-empty case a
violation), so the guard is suppressed there (`alsVoorwaarde=false`). A literal operand (never empty) skips
the guard too. The numeriek both-empty guard stays `waar`/fires per Tabel 37 — §5.3 line 3264 says `onwaar`
there, a spec self-contradiction left to the existing Tabel-37 reading (consistent with the consistentie
side above).

### §9.4 / §13.4.7 feitcreatie — only the 1-hop shape is lowered, as a reference-field set

A feitcreatie ("Een `<rolB>` van een `<anker>` is een `<rolC>` van `<onderwerpD>`") creates an instance
of the feittype that `<rolB>` names, relating each `<anker>` to the objects that fill `<rolC>` of it
(§9.4). The fact model represents a relation instance as a **single-valued reference field on the MANY
side, pointing at the ONE side** (the same convention `objectcreatie` links by, e.g. `Passagier.reis ->
Vlucht`), so creating a feit is a `modify`-set of that field — not a new fact. The rule binds the anker,
joins the doel objects already related to it by the existing `<rolC>` relation, and sets the new
relation's reference field on each:

    $subject : Anker( <anker-voorwaarde> )
    $doel    : Doel( <joinVeld> == $subject, <doel-voorwaarde> )
    then modify($doel) { set<ZetVeld>($subject) }

Both `joinVeld` (the existing `<rolC>` relation) and `zetVeld` (the new `<rolB>` relation) resolve through
the **written role name** (`verbindendeRolViaElementnaam`), which selects the one feittype among several
over the same object-type pair — a by-pair search cannot. No grammar change was needed: the greedy
`kaleNaam` splits the RHS at the last "van" (the rule's trailing `VAN onderwerp`), so the role is the
first segment and the resolver re-splits against the model, as the rest of the §13.4 name handling does.

**Lowered only when ALL of these hold** (otherwise an honest WARNING, never a silently-wrong/uncompilable
rule):
- **1-hop**: the role part (`<rolC>`) is a single segment — no further `van`-navigation inside it.
- **Direct back-reference**: `<onderwerpD>` DIRECTLY names the anker objecttype (`objecttypeOpNaam`), not
  merely a same-typed object reached by a role. "de vlucht" lifts; "de aansluitvlucht" (a different
  flight of the same type) WARNs — a type-identity test could not tell them apart and would reuse the
  anker's own join.
- **Cardinality**: the doel is the MANY side of BOTH relations (`naarVeelRol(anker, doel, rolC)` and
  `naarVeelRol(anker, doel, rolB)`). Only then do `joinVeld` and `zetVeld` exist as reference fields ON
  the doel. When `<rolB>` (or the navigation) is the "één" side the field belongs on the anker, so lifting
  would emit a setter Drools rejects (sinking the whole package) or link the wrong direction — it WARNs
  instead. This also covers the under-determined non-wederkerig same-type self-relation (voogd/pupil).
- distinct fields (`joinVeld != zetVeld`) and a uniquely-resolvable role/feittype.

A multi-version (time-bounded) feitcreatie gets per-version DRL rule names ("… #1", "… #2"), like every
other resultaatdeel kind. A voorwaarde is hosted where it resolves — a guard on the anker stays on the
`$subject` pattern (a kenmerk too: `subjectVan` yields the anker so §8.1.8 resolves it), a guard on the
doel moves to the `$doel` pattern. A **kenmerk** on the navigated doel ("indien de passagier vip is") is
hosted on `$doel` too: the guard is first built with the anker as subject, where the to-many navigation to
the doel leaves the §8.1.8 kenmerkcheck an unresolved marker; the feitcreatie then re-resolves that single
marker with the **doel** as subject and tags the resulting bare `Conditie.Kenmerk` with `eigenaar = doel`,
so the transpiler emits `vip == true` only on the `$doel` pattern — never the anker, even when the anker
declares a same-named kenmerk (the `eigenaar` tag pins it; an untagged kenmerk stays unconstrained). The
doel is named by the **role it fills** ("de passagier"), which need not equal its type name ("Natuurlijk
persoon" in the canonical §9.4 shape), so the re-resolution recognises `de <rolC>` as denoting the doel —
not only a type-name match. The bezielde **`hij`** / bare reference is *rejected* during this re-resolution:
in a feitcreatie `hij` names the anker (the universele onderwerp), so a guard "indien hij vip is" the anker
cannot satisfy stays inert rather than being silently re-targeted to the doel (which would create a wrong
link). The rejection is unconditional even in the corner case where the doel is the *only* bezield onderwerp
(a strict §5.5.4 reading could then bind `hij` to the doel) — §9.4's feitcreatie syntax carries no `indien`
guard at all, so a `hij` guard there is unspecified, and an inert no-op creates nothing, which is never
wrong against the spec. The re-resolution is restricted to the single-kenmerk guard and to a distinct doel type, and its
speculative diagnostics are discarded (the anker build and the separate type-check own them), so the
conservative to-many-navigation refusal still holds for an ordinary single-pattern rule (a regular-rule
self-relation to-many kenmerk stays a marker, never a silent read of the subject's own kenmerk). A guard that lowers to
NEITHER pattern (an objecttype/role-membership test, or a mixed anker+doel compound) makes the rule an inert
`eval(false)` no-op carrying a marker — never a dropped guard, which would fire unconditionally and
**over-create** the relation. Creating nothing (visibly) beats creating wrongly.

**Multi-hop (the canonical §9.4 / Figure-17 example) is lowered.** `<onderwerpD>` may reach the anker
through a chain ("… een passagier van de reis met treinmiles van het vastgestelde contingent treinmiles":
Contingent &rarr; Vlucht &rarr; Natuurlijk persoon). `ResolvedFeitCreatie` carries the intermediate objects
as an ordered `Tussenstap` list; the resolver walks the written target segments in REVERSE (they read
outside-in — the role onto the doel first, the innermost hop last). The transpiler emits one pattern per hop
between `$subject` and `$doel`, the doel joining to the last hop. The back-reference check is generalised so
an anker named by a role ("een vastgestelde contingent treinmiles") is accepted when the back-reference
repeats that same written noun (still rejecting "de aansluitvlucht").

Each hop's reference field lives on the MANY side (§13.3.9.1), and the join form follows: a **to-many** hop
keeps it on the child — `child( veld == $prev )`; a **to-one** hop keeps it on the parent when the reverse
role is meervoudig (cur is the many side) — `child( this == $prev.veld )`. A genuine **one-to-one** hop is
the hard case: cardinality cannot place the field, so it is taken from the **objectcreatie convention**
(objectCreatie sets the reference on the *new* object, so the created side holds it). When neither a
cardinality nor an objectcreatie signal is present, the side is NOT derivable from the model and the
feitcreatie stays an honest WARNING — never a guessed side that could emit an uncompilable wrong-side join.

**Still an honest WARNING:** a hop role that does not chain; a `<rolB>`/`<rolC>` whose own name contains
"van" (which makes `kaleNaam` multi-segment); directional self-relation lowering; and the §9.4 role-name
mismatch ("reis met treinmiles" written against a "reis met contingent treinmiles" feittype role) — the
worked example only lowers when the navigation names the declared role.

### §13.3.9.1/.2 feittype cardinaliteit — the many-to-many opener must be lowercase `meerdere`

A feittype's cardinaliteit line ends the feittype: `(één|meerdere) <rol> … (één|meerdere) <rol>`. The
one-side opener is genuinely capitalised in the spec's examples ("**Eén** reis betreft meerdere
passagiers"), so `EEN_TELWOORD` admits `'Eén'/'Één'`. A many-to-many line would, by Dutch sentence
orthography, open with a capital "**Meerdere** …" — but this form is **not supported**, and the spec does
not require it: the syntaxdiagrammen write the terminal as the lowercase `"meerdere"` in both positions,
and no spec/TOKA example contains a capitalised many-to-many opener.

The capital is irreducibly ambiguous and cannot be accepted cleanly. The opener immediately follows the
second role's `objectTypeNaam` (`UPPER_ID (UPPER_ID|LOWER_ID)*`, greedy). A capitalised "Meerdere" is
either (a) an ordinary `UPPER_ID`, which `objectTypeNaam` then absorbs ("… het vak Vak **Meerdere
studenten volgen** …"), truncating the cardinaliteit and failing the parse; or (b) a dedicated keyword
token, which then steals the word "Meerdere" from every name context (objecttypenaam, meervoudsvorm,
feittype-name word, any §13.4 `kaleNaam`) where `<karakterreeks>` makes it spec-legal. No lexer or parser
formulation escapes this, because the boundary between "last word of the second objecttypenaam" and "first
word of the cardinaliteit" is exactly a capitalised word either way.

**Resolution:** write a many-to-many cardinaliteit line with a lowercase opener — "meerdere studenten
begeleiden meerdere docenten" — matching the spec terminal. That parses and marks both roles meervoudig.
(An earlier attempt to widen `MEERDERE` to `[Mm]'eerdere'` was reverted: an adversarial review showed it
regressed "Meerdere" as a name word for no spec-required gain.)

### §13.4.10 verdeling naar rato — one weighted-accumulate rule, with two carded edges

"… waarbij wordt verdeeld **naar rato van** \<gewicht\>" (§9.7.1) gives each recipient a share proportional
to its own weight attribute: `share_i = totaal · w_i / Σ w_j`. This lowers to a single forward rule — the
equal-shares rule (total / count) with `sum(weight)` in place of `count` and `· w_r` in the numerator:

```
$src : Bron(  )
accumulate( $c : Doel( <join> == $src, w != null ) ; $sum : sum($c.getW()) )
$r : Doel( <join> == $src, w != null )
eval( $sum.signum() != 0 )
then modify($r) { setDoelAttr( $src.getBronAttr().multiply($r.getW()).divide($sum, DECIMAL64) ) }
```

The weight must be a **getalachtig** (numeriek/percentage) field of the recipient — `heeftLid` alone would
also accept a Tekst attribute or a boolean kenmerk, whose getter cannot be summed/multiplied (uncompilable
DRL), so a non-numeric/foreign/absent weight stays the honest no-op marker. **Group ordering (`op volgorde van
… bij een even groot criterium`) and a per-recipient `maximum` are the resolver's `COMPLEX` method and stay
markers — they are genuinely multi-pass.** The §9.7.4 `afronding` and §9.7.5 `onverdeelde rest` modifiers,
however, ride on the equal-shares / naar-rato spine when no grouping and no maximum is present — see the
"verdeling met afronding en onverdeelde rest" section below.

**Two deliberate divergences, both carded (corpus-invisible: TOKA's woonregio factor is always populated):**

1. **Empty weight surfaces a Fout (Inconsistentie), but the distribution is not suppressed.** §9.7.6 Tabel 19
   makes an *empty* weight on any recipient a **Fout** for the whole verdeling. The Fout is now surfaced: a
   SEPARATE, modify-free rule `"<naam> (leeg gewicht)"` fires `exists Doel(join==$subject, weight==null)` and
   inserts an `Inconsistentie` (the §9.5 channel, idempotency-guarded, exactly like consistentie/uniciteit). It
   must be a separate rule — a second `accumulate` / a `not` CE on the `modify($r)` distribution rule cancels
   the sibling recipient activations under `no-loop` so only ONE recipient fires (verified vs Drools 8.44). What
   REMAINS divergent: the distribution is still exclude-and-redistribute (the weighted recipients keep their
   shares) rather than suppressed-entirely, because expressing "set no shares when any weight is empty" needs
   precisely the CE that triggers the cancellation. So the final state on a fout verdeling is {weighted shares
   set + an Inconsistentie present} — the Fout is detectable, but the shares are not withheld. Carded.

2. **§6.1.2 precision is now enforced statically on the share, like the ordinary assignment.** A verdeling
   whose share's declared decimal count exceeds the target attribute's is flagged as the §6.1.2 violation it is
   (`Typering.verdeelToekenning`, raised by `RuleChecker.verdeling`) — the same ERROR `regel` raises for the
   equivalent expression, suppressed the same way by an afronding (toka-36's "afgerond op 0 decimalen"). It
   follows `regel`'s static model: equal-shares is total/count (the share carries the total's decimals), naar
   rato is total·weight/Σweight (total+weight decimals). It is a no-op for every TOKA verdeling (all
   integer-typed → 0-decimal share). What it does NOT catch — by design, exactly as `regel` does not — is a
   RUNTIME-over-precise result of an *integer*-operand division (1001 over 3 → 600,6 at fire time): the static
   `maxDec` model reports 0 decimals there, and the bare `divide(…, DECIMAL64)` keeps the runtime fraction.
   That is the engine-wide DECIMAL64 (vs exact-rational) precision model, carded separately; toka-36's
   afronding is the author's explicit fix for it. A zero total weight (`Σ = 0`) is guarded (`signum() != 0`)
   so the divide cannot throw; the spec leaves a zero divisor undefined, so no-op-not-crash is the chosen reading.
   **`verdeelToekenning` now also runs the FAMILY + UNIT half of `toekenning`** (not just precision): the share
   carries the bron's eenheid (equal-shares `total/count` and naar-rato `total·w/Σw` both keep it — the weight
   ratio is dimensionless), so a bron family/unit inconvertible to the doel's (€ distributed over a metre target,
   or a Percentage bron into a Numeriek doel) raises the same fout the ordinary `regel` does — previously the
   verdeling ran only the precision check and stayed silent. The **family check runs BEFORE the getalachtig
   guard**, so a numeric verdeelplafond distributed over a *non-numeric* recipient (a Numeriek bron into a Tekst/
   Datum doel) is flagged as the family mismatch it is (a slice review caught the early-return swallowing it); and
   the transpiler gates the lowering on a getalachtig doel, so such a malformed verdeling **markers** instead of
   emitting an uncompilable `BigDecimal` setter against a Tekst attribute. No-op for the unitless TOKA corpus. The
   shared `eenhedenCompatibel` helper gained an optional (empty) context prefix so the verdeling message is not
   double-prefixed by `RuleChecker`'s own "verdeling: ".

### §9.7.3/§9.7.4/§9.7.5 verdeling met maximum, afronding en onverdeelde rest — cap, floor, recomputable remainder

The bulleted `meervoudigCriterium` modifiers (toka-36 the §9.7.4 worked example, toka-35 the §9.7.3 one) now
lower — the grammar already parsed them; the resolver had been discarding the whole `meervoudigCriterium`
subtree. They ride on the equal-shares / naar-rato spine, with these additions (applied in spec order: cap, then
floor):

- **Maximum (§9.7.3, naar-rato only).** A per-recipient cap: `share_i = min(total·w_i/Σw, max_i)` via the
  null-safe `rsPlafond(share, $r.getMax())`. The surplus is **not** redistributed (the §9.7.3 worked example,
  1200 over 3:1:2 capped at 300 → 300/200/300, leaves 400 as the rest), so the existing remainder rule — which
  reads the *capped* assigned shares — captures it for free. The maximum is naar-rato only ("niet mogelijk bij
  gelijke verdeling", §9.7.3) and must name a getalachtig recipient attribute, else the verdeling stays a marker.
  **§9.7.6 Tabel 19** makes a *leeg* or *negative* maximum at any recipient a Fout for the whole verdeling (the
  N.B. — one recipient suffices): surfaced like the empty weight, by a SEPARATE `"(leeg of negatief maximum)"`
  Inconsistentie rule, while `rsPlafond` degrades a leeg/negative cap to UNCAPPED (never a negative share — that
  would be silently wrong). A zero maximum is valid (the recipient receives nothing). Caught + fixed by the
  slice's adversarial review.

- **Floor (§9.7.4).** Each share is wrapped in the null-safe `rsAfronding(share, N, RoundingMode.FLOOR)` —
  "naar beneden" is FLOOR, and since `verdeelAfronding` is grammatically always "naar beneden" the mode is
  fixed. This floor IS the explicit §6.1.2 afronding the spec demands before a division result lands in a
  bounded target, so it resolves divergence (2) above for the rounded case (the floored share fits the target).

- **Remainder (§9.7.5).** A SEPARATE rule on the bron sets `restant = totaal − Σ(distributed shares)`. It reads
  the shares the distribution already assigned (method-agnostic — equal-shares and naar-rato alike) rather than
  recomputing, and is **recomputable, not fire-once**: a `restant == null` freeze would lock in a *partial* sum
  when recipients arrive incrementally (the §9.4 rule-derived case, toka-29 — the `not Doel(…, doel == null)`
  guard only sees recipients already in working memory, so a single early recipient looks "complete"). Instead
  it re-fires whenever the share set changes and always reflects the FINAL distribution. It must be a separate
  rule modifying the SOURCE (a `not`/second-accumulate on the per-recipient `modify($r)` rule would cancel the
  sibling activations, like the leeg-gewicht Fout above); termination holds because no other rule reads
  `restant`, so its modify does not re-trigger the share rule (property reactivity) and the recomputation is a
  fixpoint. Caught by an adversarial review (the freeze-guard premature-partial bug) + regression-pinned.

`op volgorde van` (group ordering, §9.7.2) now lowers as well — see the next section. **On the TOKA corpus
itself, however, ALL SIX verdelingen (toka-32…37) marker** — measured and pinned by `TokaDekkingTest`
(an earlier revision of this paragraph claimed toka-34/36 lower on the corpus; that was never corpus-true).
The cause is a casus spec omission, the same family as the undeclared "passagier jonger dan 18 jaar" kenmerk:
every verdeling distributes over "de **treinmiles** van alle passagiers…", but `Natuurlijk persoon` declares
only the longer "treinmiles op basis van evenredige verdeling" / "…volgens rangorde" attributes, so the bare
recipient attribute `treinmiles` does not exist and the getalachtig-doel guard honestly markers. Declaring
`de treinmiles Numeriek (geheel getal);` makes toka-32/33/34/36 lower (verified probe); toka-35/37 then still
marker on their *also*-undeclared cap attribute "maximaal aantal te ontvangen treinmiles". The §9.7.2–§9.7.5
machinery is therefore proven on the synthetic fixtures firing the worked examples, not on the TOKA corpus.

**Warning:** §9.7.3/§9.7.4 — "Als een maximale aanspraak / afronding wordt gespecificeerd, dan moet ook altijd
een onverdeelde rest worden gespecificeerd." A verdeling that caps or floors with no (recognisable) bron-side
remainder attribute raises a WARNING (the leftover would otherwise be silently lost), but only when the verdeler
resolves — an unresolved-verdeler fragment markers anyway, so warning there is noise. The shares still cap/floor;
the remainder simply is not captured.

### §9.7.2 verdeling op volgorde van — ordered group depletion via one registered allocation helper

"… waarbij wordt verdeeld: **op volgorde van toenemende/afnemende** \<criterium\>, **bij een even groot
criterium** \<in gelijke delen | naar rato van \<gewicht\>\>" (§9.7.2) is a genuinely **multi-pass, order-dependent**
allocation: the recipients are partitioned into groups of equal `criterium` and the pot is depleted group by
group in sort order, carrying the remainder forward. It has no single closed-form Drools rule, so it lowers to
**one forward rule + one registered Java helper**:

- the rule's `accumulate` folds the recipients into **index-aligned value lists** via several `collectList`
  functions in one accumulate — `collectList($p)` (the fact, for identity keying), `collectList($p.get<crit>())`
  (the ordering key), and, when present, `collectList($p.get<weight>())` / `collectList($p.get<max>())`;
- `$shares : java.util.Map() from rsVerdeelOpVolgorde(total, $facts, $groep, toenemend, $gewicht, $max, decimals)`
  binds the whole share map **once** (a `Map` is not a `Collection`, so `from` does not iterate it); the helper
  does the imperative ordered allocation and returns an `IdentityHashMap` keyed by the recipient fact;
- `$r : <Doel>(<join>)` then assigns each recipient its share by identity: `set<doel>((BigDecimal) $shares.get($r))`.

The allocation (in `rsVerdeelOpVolgorde`): sort the recipients by `criterium` (ascending for *toenemende*,
descending for *afnemende*); for each tie-group in order, **every member splits the SAME group-entry pot** — naar
rato of the tie weight (`pot·w_i/Σw`) or in gelijke delen (`pot/n`) — then each share is **capped (§9.7.3) and
floored (§9.7.4)**, and the pot is depleted by the group total so the undistributed remainder carries to the next
group (§9.7.5). The §9.7.5 onverdeelde-rest and §9.7.6 empty-weight / leeg-or-negative-maximum Fout rules are the
**same shared companion rules** as the non-ordered methods.

Two design points are load-bearing and pinned by tests:

1. **Group-entry pot, not sequential depletion.** All members of a tie-group divide the pot *as it stood when the
   group was reached*, not a pot already reduced by earlier members of the same group. The §9.7.2 worked example
   forces this: 1200 over Wilma(age 20, w3) / Hans(age 20, w1) / Diederik(age 22, w2) gives Wilma `3/4·1200 = 900`
   and Hans `1/4·1200 = 300` (both off **1200**), exhausting the pot, so Diederik gets **0** and the rest is 0.
   Sequential within-group depletion would (wrongly) give Hans `1/4·300 = 75`. Pinned by
   `verdeling_op_volgorde_geeft_de_eerste_groep_alles_zonder_maximum`.

2. **Cap and floor are applied INSIDE the helper, group by group** — never as a post-hoc `rsPlafond`/`rsAfronding`
   wrap against the full plafond. The §9.7.5 worked example (Tabel 18) proves it: 1800, max 500/250, floor 0 →
   Piet 500 (pot→1300), Jan/Klaas split **1300** (Jan `2/3·1300=866`→cap 500, Klaas `1/3·1300=433.33`→floor 433;
   pot→367), Nel/Ria split **367** (Nel `3/4·367=275.25`→cap 250, Ria `1/4·367=91.75`→floor 91), rest **26**. A
   clamp against the fixed 1800 would compute every later group off the wrong pot. Pinned by
   `verdeling_op_volgorde_met_maximum_en_afronding_reproduceert_tabel_18`.

Chosen readings where the spec is silent or self-inconsistent (flagged, not invented):

- **Empty *ordering* criterium → sorts last (a degrade), no extra Fout.** Tabel 19's "Criterium bij gelijke
  volgorde → Leeg → Fout" is the *tie-split* weight (the `bij een even groot criterium naar rato van …` weight),
  which is the same channel as "Naar rato → Leeg → Fout" and is surfaced by the existing `(leeg gewicht)` rule.
  Tabel 19 says **nothing** about an empty *ordering* attribute (`leeftijd`), so we do not raise a non-mandated
  Inconsistentie for it; the helper merely sorts a null key last so it cannot crash. (The earlier design draft's
  separate `(leeg criterium)` Fout rule was dropped as spec-unsupported.)
- **§9.7.5 prose arithmetic typos.** The §9.7.5 narrative writes "3/4 · **337** = 252" and "1/4 · **227** = 91";
  the carried pot is **367** (`1800−500−500−433`) and `3/4·367 = 275.25`. Tabel 18's final column (250/91) and
  the rest (26) are correct. We reproduce the **table**, and flag "337"/"227" as conversion/spec typos.
- **Maximum on a gelijke-delen tie → marker (strict reading).** §9.7.3 "niet mogelijk bij gelijke verdeling": we
  allow a maximum only when a naar-rato split is present (top-level naar rato, or an op-volgorde *naar-rato* tie),
  and keep a maximum on a gelijke-delen tie `COMPLEX` (a marker), matching the resolver's existing posture.
- **Negative *tie* weight is not a Fout, but is clamped to 0.** Tabel 19 lists `< 0` as a Fout only for the
  *maximum*, not the weight, so we do not raise a Fout the spec does not list. But a negative weight has no
  proportional meaning (a "naar rato van een factor" factor is inherently non-negative), and leaving it signed
  would deflate the weight-sum and *over-allocate* the positive members past the pot — yielding `Σ(shares) > plafond`
  and a **negative onverdeelde rest**, a state the §9.7 model cannot represent. So the helper clamps each weight to
  `max(w, 0)`: a negative-weight member claims nothing (like a weightless one) and cannot inflate the others, so
  `Σ(shares) ≤ pot` and the rest stays `≥ 0` by construction. (Caught by the slice's adversarial review.)

**Ordering key — orderable, not just getalachtig.** The ordering key need only be **orderable** by a scalar `<`
(`Datatype.isOrdenbaar()` = `isGetalachtig() || isDatum()`): a `Datum` criterium ("op volgorde van toenemende de
inschrijfdatum") sorts chronologically through the same `compareTo`-based helper, with nulls still last. Tekst,
Booleaan and Enumeratie carry only `gelijk`/`ongelijk` in the typeringen matrix (§5.x) — no ordinal `<` — so a
non-orderable key keeps the verdeling a COMPLEX marker rather than guessing a lexicographic order. The **tie weight**
stays `getalachtig` (it is summed), and the `maximum` cap stays `getalachtig`.

### §6.5 gedeeld door (ABS) — 5 decimals toward zero, and a table typo

"gedeeld door (ABS)" (the AanslagBelastingenSysteem division) is NOT the exact/fraction quotient of plain
"gedeeld door": §6.5 makes its result "altijd maximaal 5 [decimalen] waarbij het resultaat **richting 0**
is/wordt afgerond". That is `BigDecimal.divide(divisor, 5, RoundingMode.DOWN)` — scale 5, truncated toward
zero — which we emit for `GEDEELD_DOOR_ABS` while plain `GEDEELD_DOOR` keeps its DECIMAL64 (≈16-digit) divide.

The spec's own Tabel 11 row `2 gedeeld door (ABS) 3 → 0,66667` **contradicts** both the stated "richting 0"
rule (which truncates to `0,66666`) and the symmetric neighbouring row `2 / -3 → -0,66666`. `RoundingMode.DOWN`
matches 5 of the 6 (ABS) rows exactly (e.g. `2,3/1,134 → 2,02821`, where HALF_UP would give `2,02822`); no
other mode fits more. So `0,66667` is a table typo and the rule (toward zero) governs — consistent with the
project's "typo's daargelaten" mandate.

Two things deliberately NOT changed (each pre-existing and shared with plain `gedeeld door`, so out of scope
here): the §6.1.2 result precision typing still treats (ABS) like `gedeeld door` (errs safe — a false
negative, never a false positive — see Tabel 37/§6.1.2 note in Typering); and the §6.5 leegwaarde table
(`leeg → 0`, `waarde / leeg → fout`) is the broader division-leegwaarde item, still unhandled (a null divisor
NPEs in mvel exactly as plain division does today).

### §13.4.17 scalar builtins as a condition operand — bare property, not a getter

A scalar builtin (abs, a date/time component, datum-met/datumtijd-met, wortel, eerste paasdag, tijdsduur) is a
single mvel term, equally valid on a rule's value side (a `then` modify-block) and inside a `when` pattern
constraint. Originally only the value side lowered it; as a comparison operand it fell through to a marker, so
two TOKA rules — Paaskorting (`de vluchtdatum gelijk is aan de eerste paasdag van (het jaar uit (de
vluchtdatum)))`) and Hoogseizoen (`de maand uit (de vluchtdatum) is gelijk aan 6/7/8`) — no-oped.

The lowering is reused verbatim across both positions; only the **argument** lowering differs. The value side
lowers a builtin's argument with `waarde` (a `$subject.getVluchtdatum()` getter); a pattern constraint is
evaluated against each candidate fact and references its fields by **bare property name** (`vluchtdatum`),
because `$subject` is not bound inside its own pattern. So `scalarFunctie` takes its argument lowering as a
`Function<Expr,String>` — `waarde` on the value side, `operand` on the condition side — and the one builtin
table serves both. The aggregations (som/min/max over instances) still return null there: they require a `when`
accumulate and cannot live inside a single constraint, so they stay markers, unchanged.

**Binary arithmetic (§6.2-6.8) in a condition lowers too, via the same `binairTerm`.** The value-side binary
lowering was extracted into `binairTerm(b, arg, omrekenen)` — shared, so value and condition arithmetic no longer
duplicate — and `operand` now lowers a `Binair` ("indien de a maal 2 gelijk is aan de b" → `(rsNul(a) * rsNul(2))
== b`). Every leegwaarde form is already a registered helper or inline arithmetic (`rsNul`/`rsGedeeld`/
`rsGedeeldAbs`/`rsVerminderd`/`rsMacht`), i.e. a method call valid in either a modify-block or a pattern
constraint; the **only** ternary was the §4 unit restatement of a `+`/`-` right operand, which the condition side
supplies via `omrekenConditie` — **identity** when no conversion is due (the common case), else a **marker**.
That marker is load-bearing: a pattern constraint cannot host the value-side null-guard ternary, and the
ternary-free `.multiply(factor)` would NPE-**abort the session** on a `null` operand, so a convertible-but-
different-unit `+`/`-` in a condition stays an honest no-op (the value side, which *can* host the ternary, still
converts). `tot de macht <int>` lowers in a condition too, with its mandatory §13.4.17.14 afronding wrapper
(`… tot de macht 2 naar beneden afgerond op 0 decimalen gelijk is aan …` → `rsAfronding(rsMacht(a, 2), 0,
FLOOR) == b`). A `Begrensd` clamp lowers in a condition too (§6.1.4): the value side's inline clamp ternary
`(r < m ? m : r)` mis-parses in a constraint, so a condition-side begrenzing (`… met een minimum van de bodem en
een maximum van de plafond groter is dan 50`) lowers through a registered `rsBegrens(basis, min, max)` helper
(null-safe, clamping min-then-max to match the value side's order); a convertible-but-different-unit bound markers
like the `+`/`-` path. The value side keeps its inline ternary (byte-pinned; no corpus DRL has a clamp).

### §6.9 / §13.3.9 — a navigated to-one VALUE read is null-guarded at its root

A then-side value read through a to-one reference lowered to a bare `$subject.getPartner().getX()`. The rule's
`modify` consequence runs for EVERY instance of the subject type, so a subject whose reference is null NPE'd inside
the consequence — which, inside Drools, **aborts the whole `fireAllRules` pass** (every later rule on every fact).
§6.9 / §13.3.9 make a read through an empty reference *leeg*, not a crash. The plain value read (not
`attribuutGetter`, which the dimensioned Map-reads also call) now guards the root:
`($subject.getPartner() == null ? null : $subject.getPartner().getX())`, so a null reference yields the leegwaarde
and the surrounding helpers propagate it (a partnerless subject's `leeg / leeg` division is `0` per §6.5). The
condition side already guarded (genavigeerd-leeg-gevuld / `!.`); only this value read did not. Corpus byte-identical
(no corpus value read is navigated).

Two facts worth recording, each verified by firing in Drools 8.44:

- **Numeric `==` is value-based, not scale-sensitive.** Hoogseizoen is the corpus's first firing
  `BigDecimal == BigDecimal`. A probe confirms mvel coerces numerically (`new BigDecimal("2.5") == new
  BigDecimal("2.50")` matches), so the existing `==`/`!=` comparison lowering is correct for numeric equality
  without resorting to `.compareTo(...) == 0`.
- **The §6.12 / §6.9 leegwaarde of a date-component or abs operand now propagates as `leeg`, instead of NPE-ing.**
  Originally the date-component (`(d).getYear()`) and abs (`(x).abs()`) lowerings NPE'd on a null operand, and
  inside a `when` constraint the throw was a Drools `ConstraintEvaluationException` that aborted the **entire
  `fireAllRules()` pass** — triggered by *any* fact in working memory with a null date, not just a matching one.
  The seven §13.4.17.18-.24 components and §13.4.17.17 abs now lower through null-guarding registered helpers
  (`rsDatumDeel(d, ChronoField)` / `rsAbs(x)`, returning `null` on a `null` input per typeringen Tabel 28 / Tabel
  20), so `leeg` propagates as `null` in both a modify-block and a pattern constraint. A downstream numeric
  comparison reads `null == 6` as a non-match (Tabel 37); a non-numeric `gelijk aan` with both sides `leeg` is
  decided by the §5.3 both-empty guard — `beide leeg → fout → no fire` (Tabel 38), which the NPE used to
  pre-empt and which now actually runs (Paaskorting on a null `vluchtdatum` correctly does not fire). The
  ChronoField read is value-identical to the old getters, milliseconde included (`MILLI_OF_SECOND` *is*
  `nanoOfSecond / 1_000_000`).

  **datum-met / datumtijd-met now propagate `leeg` too.** "de datum met jaar/maand/dag" (§13.4.17.35) and "de
  datumtijd met …" (§13.4.17.36) lowered to an inline `LocalDate.of((c).intValue(), …)` / `LocalDateTime.of(…)`,
  where a `null` component made `(null).intValue()` throw inside the constructor — again a session-aborting
  `fireAllRules` throw. They now lower through `rsDatumMet(jaar, maand, dag)` / `rsDatumtijdMet(7 args)`, which
  null-guard every component (any `null` → a `null` date, §6.9) and carry the §13.4.17.36 milli→nano scale (the
  7th arg is `nanoOfSecond`, so `×1e6`) in the helper. The typeringen tables give no §4.x leegwaarde row for the
  date *constructor*, so "any leeg → leeg" generalises the tabled date operators (§4.19) — strictly preferable to
  a crash. Still NOT propagated (carded in builtins-vervolg): **wortel** — its mandatory afronding wrapper
  `(…).setScale(…)` would itself NPE on a `null` root, so it entangles with the §6.1.2 afronding-leegwaarde.

### §6.2-6.4 binary arithmetic leegwaarde — leeg = 0, except verminderd met's left

The value-side binary operators used to lower as a bare mvel `l op r`, which NPEs on a `null` operand and — the
headline — lowered `min` and `verminderd met` IDENTICALLY (both to `-`), a silent-wrong. §6.3 Tabel 7/8 make
them the same subtraction differing ONLY in leegwaarde: `min` treats an empty operand as 0 (`leeg min 4 = -4`),
while `verminderd met` yields leeg whenever the LEFT operand is empty (`leeg verminderd met 4 = leeg`), treating
an empty right as 0. They now lower differently: `plus`/`min`/`maal`/`percentage van` wrap each operand in an
`rsNul` helper (empty → 0, per §6.2 Tabel 5 / §6.4 Tabel 10 / §6.8 Tabel 13), and `verminderd met` lowers to an
`rsVerminderd` helper (left-empty → leeg). A marker operand still propagates unwrapped, so an unresolved
arithmetic term is byte-identical and `bevatMarker` still catches it; for a non-empty operand the result is
identical to the old `l op r`, so no fired total changes (verified: corpus DRL byte-identical per file).

**gedeeld door / (ABS)** now follows the §6.5 Tabel 12 leegwaarde via `rsGedeeld`/`rsGedeeldAbs`: a LEFT-empty
dividend short-circuits to 0 (even `leeg / leeg = 0`, footnote 22's load-bearing order — left tested first), and
a non-empty value over an empty divisor is the **fout**. The fout is modeled as **{leeg value + an
Inconsistentie}** (the user's chosen representation): the division returns leeg, and a SEPARATE companion rule
`"<naam> (deling door leeg)"` inserts an idempotency-guarded Inconsistentie via the §9.5 channel when the
dividend is present and the divisor empty (and the host rule's voorwaarde holds). The spec's "fout !!!" defines
no runtime form, so {leeg + Inconsistentie} is a defensible engineering choice — never a crash, never a wrong
number, and the fout is detectable. SCOPE of the companion: both operands must lower to a re-readable leeg/vol
guard — the subject's OWN attribute, **or one reached by a to-ONE navigation** (`de inkomen van zijn partner
gedeeld door de aftrek van zijn partner`), guarded against a null root (`(partner != null && partner.inkomen !=
null), (partner == null || partner.aftrek == null)`); a literal / parameter / to-many / unresolvable operand still
degrades the value to leeg but raises no Inconsistentie (carded). The own-attribute path reuses the bare-property
form, so it stays byte-identical to the prior EIGEN-only emission. A division wrapped in an *explicit* afronding
also propagates its leeg (the afronding is null-safe — see below).

### §6.6/§6.7 + §13.4.17.25 — wortel, tot de macht, and a null-safe afronding

`wortel` and `tot de macht` both carry a MANDATORY afronding (§13.4.17.13/.14), and §6.6/§6.7 make their result
leeg when an operand is leeg. The afronding lowered to a bare `(basis).setScale(...)`, which NPEs on a null
basis and aborts the whole `fireAllRules()` pass — so those operators' leeg results could not propagate (the
leeg hit the afronding and crashed). They were deferred on exactly this entanglement; now resolved:

- The afronding lowers through `rsAfronding(x, scale, mode)` — a leeg value rounds to leeg (the helper form is
  also what a pattern constraint accepts, so the condition-side afronding is covered). Whether a *bare* afronding
  over an empty value is leeg is not spec-stated (the typeringen has no afronding leegwaarde row), but leeg→leeg
  is the only reading that lets the spec-mandated wortel/macht leeg propagate, so it is adopted.
- `wortel` → `rsWortel` (BigDecimal.sqrt at 34 digits — NOT Math.sqrt, whose ~16-digit double noise can flip a
  directional round on a terminating root — with `leeg → leeg`). `tot de macht` → `rsMacht` (exact integer pow)
  / `rsMachtReeel` (runtime Math.pow, both operands guarded), each `leeg → leeg`.

Behaviour-preserving for a non-leeg value (each helper equals the old `.sqrt`/`.pow`/`.setScale`), so the only
corpus change is toka-24's afronding becoming `rsAfronding`; wortel/macht are corpus-invisible.

A **negative radicand** (§6.6: a Fout, since RegelSpraak has no complex numbers) is now handled the way gedeeld
door's `÷ leeg` fout is: `rsWortel` returns leeg for a negative value (no more session-aborting
`BigDecimal.sqrt` throw), and a SEPARATE modify-free companion rule `"<naam> (wortel van negatief)"` fires
`<radicand> < 0` (and the host voorwaarde) and inserts an idempotency-guarded Inconsistentie. So a negative
radicand is {leeg value + Inconsistentie}, detectable and non-crashing. SCOPE, like the division fout: a radicand
that is the subject's OWN attribute lowers to a plain `< 0` constraint, and a to-ONE NAVIGATED radicand to a
root-guarded one (`(partner != null && partner.basis != null) && partner.basis < 0`); a parameter / nested /
to-many / aggregation radicand still degrades to leeg but raises no Inconsistentie (carded). A leeg
radicand is leeg WITHOUT a fout (§6.6) — the `< 0` guard is false for a null, so the companion does not fire for
leeg. `tot de macht` of a negative base is well-defined (a real power), so it has no analogous fout.

### §3.3.5 Percentage carries its eenheid (always %, optionally a rate)

A Percentage attribuut "heeft verder **altijd** als eenheid %" (§3.3.5), optionally specialized to a rate such as
`%/jaar` (the grammar admits `met eenheid % ( / <eenheidAfkorting> )?` inside `percentageDatatype`). The resolver
used to drop this — `Datatype.Percentage` carried no unit and `eenheidOf()` returned null for a percentage. It now
threads the unit: `Datatype.Percentage` gains an `eenheid` field that defaults to a `"%"` constant
(`Percentage.PROCENT`) and becomes `%/<eenheid>` when the rate form is declared; `eenheidOf()` returns it. A
2-argument delegating constructor keeps the literal/inference sites (`Expr`, `ExpressieBouwer`) unchanged.

This is the literal "altijd" reading (variant A), not "preserve only an explicitly-declared rate": measured
zero regression — the full unit suite stays green and the corpus resolver-ERROR set is byte-identical (73 = 73).
The only behavioural change is **matrix-aligned**: now that a Percentage carries `%`, mixing it with a *unitless*
plain Numeriek in `+`/`-`/`<` flags the §4 "als één operand een eenheid heeft, moeten beide er een hebben" rule —
which the typeringen tables (Tabel 32/34/39/41 list Percentage only against Percentage, never against a bare
Numeriek) sanction. It fires on zero corpus files. `Typering` needed no edit: once `eenheidOf()` returns `%`, the
existing `zelfdeFamilie` (Percentage and Numeriek are distinct families) + `eenhedenCompatibel` do the right thing.

### §4.4/4.5 maal/gedeeld composite units — the representable cases are synthesized

A `maal`/`gedeeld door` of two unit-bearing Numerieks used to always degrade to `Onbekend ("samengestelde
eenheid")`. The `Eenheid` model represents a numerator over **one** simple denominator (a rate `€/jr`), so the
combinations that fit are now synthesized; the rest stay `Onbekend` (an honest "no claim", never a wrong unit):
- **`maal`:** a rate times its (convertible) denominator-unit cancels to the numerator — `€/jr maal jr = €`. Two
  plain numerators multiply to an opaque **PRODUCT unit** — `€ maal m = €·m` (`Eenheid.product`): an afkorting
  `€·m` with **no canonical**, so it is convertible only to an identical product, and a `€·m` result assigned to a
  `€` target now flags the §4 unit mismatch the typeringen matrix intends (where the prior `Onbekend` let it pass
  silently). The factor abbreviations are flattened to atomic factors and **sorted**, so the product is both
  commutative AND associative (§4.4 Tabel 11): `€ maal m == m maal €` and `(€·m)·km == €·(m·km) == €·km·m`. A rate
  factor (a product with a denominator, `€·m/jr`) is still not representable → `Onbekend`.
- **`gedeeld door`:** two plain units divide to a rate — `€ gedeeld door jr = €/jr` — or to a dimensionless getal
  when convertible — `€ gedeeld door € = een getal`. A rate divisor (whose result needs a second denominator or a
  bare `1/jr`) stays `Onbekend`.

Corpus-invisible: no corpus expression multiplies/divides two unit-bearing operands (the unitless×unit and
unit×unitless branches, which the corpus does exercise, are untouched), so the resolver-ERROR set (73) and the
per-file DRL are byte-identical. The product unit is opaque — the model does not re-decompose it, so cancelling a
factor (`€·m gedeeld door m`) is not recognised; the rate-product forms (`€·m/jr`, `1/jr`) stay `Onbekend` (they
need a multi-factor `Eenheid` with exponents, the §13.3.7 model the roadmap defers).

## §3.3 / §13.4.17 — a written plural binds its declared singular (declared-names-only IR)

An attribute reference written in the PLURAL ("de som van **de leeftijden** van alle passagiers" with the
attribute declared `leeftijd`; the TOKA-21 shape "de te betalen belastingen") now binds the DECLARED singular
at resolve time, via the ambiguity-safe `MeervoudMatcher` (declared `(mv: …)` form or the conservative regular
plural of exactly ONE attribute). The IR therefore only ever carries declared member names and the back-ends
never synthesize a getter from surface morphology (`getLeeftijden()` — a member no fact bean has — used to
reach the DRL accumulate and sink the package at build time). A written form that binds nothing leaves the
aggregation `Onopgelost` with a WARNING (an honest marker in both back-ends). This also admits a bindable
plural in a SCALAR read ("de leeftijden van de passagier" now reads `leeftijd` instead of markering) — the §3.3
meervoud is declared precisely so the running text can use it; requiring the singular there was the divergence.

## §9.6 — initialisatie assigns only when the doel is leeg (leeg-guard in both back-ends)

An initialisatieregel now lowers with a `doel == null` pattern constraint (DRL: `prijs == null` in the subject
pattern; exec model: an equivalent getter-is-null `expr`), instead of an unconditional assignment identical to a
berekening. This encodes the §9.6 text literally: "Een initialisatieregel **controleert of een attribuut een
waarde bevat** … Als dit niet het geval is, dan kent de initialisatieregel een gespecificeerde waarde toe." The
practical consequence is determinism: when an initialisatie and a berekening/gelijkstelling target the SAME
attribute (TOKA: "Initialiseer te betalen belasting op" vs "Te betalen belasting"), the computed value wins under
EVERY agenda order — if the berekening fires first the initialisatie's activation is cancelled by property
reactivity (its pattern constrains the written property); if the initialisatie fires first the berekening simply
overwrites. Without the guard both rules fired unconditionally and the final value was last-writer-wins by agenda
order. A pre-filled doel (a value supplied with the facts) is likewise left standing. The guard applies to the
initialisatie resultaatdeel only; gelijkstelling/berekening stay unguarded. Pinned by a DRL-text test, a
DRL firing test under both declaration orders, and differential fixtures asserting the absolute values.
