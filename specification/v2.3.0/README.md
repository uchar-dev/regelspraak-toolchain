# specification/v2.3.0/

This directory holds **project-authored, derived** notes about the RegelSpraak
v2.3.0 specification — not the specification itself:

- `RegelSpraak-spec-for-ANTLR-v2.3.0.md` — the EBNF grammar mapped onto ANTLR
  4 lexer/parser rules. The starting point for `grammar/`.
- `conversie-bevindingen.md` — notes on PDF→Markdown conversion fidelity
  (where the Markdown transcription may diverge from the PDF).

## The authoritative specification (not included here)

The normative RegelSpraak v2.3.0 specification — the main spec, the Chapter 13
syntax diagrams, the typeringen (datatype matrix), and the TOKA casus — is
© Belastingdienst (Dutch Tax & Customs Administration), published with
"Alle rechten voorbehouden" (all rights reserved). It is **not** redistributed
in this repository. Obtain it from the official source:

- Wendbare Wetuitvoering Pleio community —
  https://wendbarewetsuitvoering.pleio.nl/  (search: "specificaties RegelSpraak v2.3.0")

Derive all language semantics from that specification; this implementation
tracks v2.3.0. If a behavior here seems surprising, the spec is the oracle.
