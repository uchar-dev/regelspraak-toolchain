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

### Known precedence limitation (begrenzing + afronding)

When a begrenzing's bound value is followed by an afronding — "… met een minimum
van 0 € naar beneden afgerond op 0 decimalen" — the afronding parses INTO the
bound value (minimum = "0 afgerond"), rather than wrapping the bounded result, as
§13.4.17 intends. The bound value uses the full `expressie`, which greedily takes
the trailing afronding. A faithful fix needs the bound value to stop before an
outer afronding (a precedence split in the begrenzing rule); until then the IR —
and therefore the generated DRL — rounds the bound rather than the result. Noted
so the transpiler's output is read with this in mind.

### Unit conversion — scope and known gaps

Unit systems declare conversions (§13.3.5.2: "het jaar jr = 12 mnd", incl. the
"1/N" rational form), and the resolver reduces each unit to its system canonical
with a factor. The transpiler converts a **simple (numerator-only)** unit on the
right operand of `+`/`-`, in a comparison, and at an assignment, so cross-unit
arithmetic and comparison are scaled rather than computed on raw magnitudes.

Deliberately **not yet** handled (documented rather than half-done):
- **Composite / rate units** (`€/jr`, `km/u`): the denominator is currently dropped,
  so two rate units look identical and are not converted (no scaling is emitted —
  a missing feature, not a wrong number). The §6.4/§6.5 composite-unit algebra
  (result unit of `maal`/`gedeeld door`, cancellation, denominator-direction
  inversion) is future work. `maal` therefore emits a plain product and does NOT
  convert its operands (units multiply, they need not match).
- **Reverse-factor precision**: the exact direction (to the smaller unit, ×N) is
  exact; the reverse (×1/N) is carried at DECIMAL64. Per §6.1.2 there is **no
  implicit rounding** — the author adds an explicit afronding where precision matters.

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
attribute. Otherwise the objecttype reading stands, and a genuinely unresolvable
reference stays a `/* TODO navigatie */` marker rather than becoming a wrong join or a
getter for an absent property. This is a deliberate, bounded divergence: the spec offers
no explicit tie-break for an objecttypenaam/rolnaam collision (the rangtelwoord of
§13.4.1.3 disambiguates two *different* onderwerpen sharing a name, a different case), so
reachability is the resolver's reading. It fires only where the objecttype reading would
otherwise have been a non-functional dangling reference; a fuller treatment could emit an
INFO diagnostic when both readings are independently valid.

### Known precedence limitation (wortel / tot de macht + afronding)

§13.4.17.13/.14 make `<afronding>` **mandatory** on "de wortel van" and "tot de macht"
(it is not bracketed in the diagram). The grammar currently makes it optional and lets
the function argument run as a full `expressie`, so "de wortel van X afgerond …" can
absorb a trailing operator into X. To be fixed by requiring the afronding and giving
these functions a higher-precedence operand sub-rule.

### Known precedence limitation (telling + arithmetic)

"het aantal <X> maal <Y>" parses as a count of "(X maal Y)" rather than "(count of
X) maal Y", because the telling's argument is a full `expressie` and greedily takes
the product. Like the begrenzing/afronding case, this is a precedence split the
grammar does not yet make, so such a count surfaces in the IR (and DRL) as an
unresolved aggregation rather than an accumulate over X. Noted for a focused fix.

### Forms still modelled at main-clause word order (resolver-side concern)

- **V-final subordinate clauses** after `dat` (`… dat hij een recht … heeft`,
  §13.4.17.57): the toplevel-operator trailing-verb form (`… EEN kaleNaam HEEFT`)
  already accepts this surface, so it parses; a fully faithful tree would mark it
  as a subordinate clause, which the resolver can do from the operator label.
