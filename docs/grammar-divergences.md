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

### Forms still modelled at main-clause word order (resolver-side concern)

- **V-final subordinate clauses** after `dat` (`… dat hij een recht … heeft`,
  §13.4.17.57): the toplevel-operator trailing-verb form (`… EEN kaleNaam HEEFT`)
  already accepts this surface, so it parses; a fully faithful tree would mark it
  as a subordinate clause, which the resolver can do from the operator label.
