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

## Spec worked examples that do NOT parse (with reason)

The TOKA casus parses in full (47/47). Of the spec's other worked examples, the
ones that do not parse are source defects or genuinely under-specified forms.
Most are already catalogued as source errors in
`specification/v2.3.0/conversie-bevindingen.md`.

### Source defects (verbatim-preserved spec errors)

- **Bullet-style dimensie labels** (`• bruto` / `• netto`, §3.6): the normative
  §13.3.7.5 diagram is numbered (`<digit>+ ". " <dimensiewaarde>`); the grammar
  follows the diagram. The bullet form is a prose variant the diagram does not
  sanction. (conversie-bevindingen B/§13.3.7.5.)
- **`Numeriek met eenheid …` without `( … )`**: §13.3.3.2 makes the
  getalspecificatie parentheses mandatory; an example omits them.
- **`tenminste` written as one word** vs the keyword `ten minste`.
- **Capitalised version keyword `Geldig`**: the keyword is lowercase `geldig`;
  one example capitalises it. (Kept lowercase so a regelnaam may itself begin
  "Geldig …".)
- **Lowercase objecttype name** (`Extensie van objecttype de natuurlijk persoon`):
  contradicts the capitalisation convention the rest of the spec relies on.
- **`… minimum van X en een maximum van Y`**: the second begrenzing drops the
  `met` that §13.4.17.30 requires (`met een maximum van`).
- **Truncated example fragments** (a `Regel` header with no body).

### Under-specified / ambiguous forms (diagram is silent or contradictory)

- **V-final subordinate clauses** after `dat` (`… dat hij een recht … heeft`,
  §13.4.17.57): Dutch verb-final word order in a `dat`-clause; the grammar models
  main-clause order. A faithful model needs a dedicated subordinate-clause rule.
- **Time-conditioned plain expression** (`… plus … gedurende de tijd dat …`,
  outside `totaal van`/`tijdsevenredig deel`): §13.4.17.55 attaches
  `conditiebijexpressie` only to those aggregations, not to an arbitrary
  berekening.
- **Leegwaarde fallback** (`de som van … of 0 als die er niet zijn`): an
  `of`-fallback with an `als …`-guard not present in the §13.4.17 expressie
  diagram.
- **Dimensie-aggregation without `over`** (`… in jaar vanaf … t/m …`, §5.8.5):
  §13.4.17.51 mandates a leading `over`; a worked example elides it
  (conversie-bevindingen flags this explicitly).
- **`de Rekendatum`** with an article: `Rekendatum` is normally a bare built-in.
