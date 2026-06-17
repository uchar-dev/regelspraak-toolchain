# grammar/

Author the two ANTLR grammars here, **from scratch**, against
`../specification/v2.3.0/`. The Makefile and tooling expect exactly these names
and the package `io.github.yhavinga.regelspraak.parser`:

- **`RegelSpraakLexer.g4`** — `lexer grammar RegelSpraakLexer;`
  All terminals: keywords, multi-word phrases, identifiers, literals, operators,
  and a skipped `WS`. Define longest multi-word keywords first and **all**
  keywords before `IDENTIFIER` (see [../docs/ANTLR-HOWTO.md](../docs/ANTLR-HOWTO.md) §4).

- **`RegelSpraak.g4`** — `parser grammar RegelSpraak;`
  with `options { tokenVocab=RegelSpraakLexer; }`. Parser rules (lowercase)
  mirror the Chapter 13 non-terminals.

Start from `../specification/v2.3.0/RegelSpraak-spec-for-ANTLR-v2.3.0.md` (EBNF
already mapped to ANTLR rules), cross-check the syntaxdiagrammen, and grow the
grammar against the TOKA casus with `make grun`.

Do **not** copy any prior RegelSpraak grammar — derive from the spec (see
[../CLAUDE.md](../CLAUDE.md)).
