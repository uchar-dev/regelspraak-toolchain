# CLAUDE.md — RegelSpraak-rebooted

## Mission

Build, **from scratch**, a new ANTLR 4 **parser + semantic resolver** for
**RegelSpraak v2.3.0**, written in **Java**, to serve as the front-end and
intermediate representation (IR) for a **RegelSpraak → Drools (DRL) transpiler**.

This is a deliberate reboot. A prior parser/resolver exists (TypeScript,
ANTLR-based) but was built quickly with weaker tooling and carries accumulated
bugs. We are **not** refactoring it — we are re-deriving the grammar and the
resolver directly from the specification, with the specification as the single
source of truth.

## The specification is the oracle

Correctness means **adherence to the RegelSpraak v2.3.0 specification**, not
value-for-value parity with any prior engine or parser. When in doubt, the spec
decides. It lives in `specification/v2.3.0/`:

| File | Role |
|------|------|
| `RegelSpraak-specificatie v2.3.0.md` | The full normative specification. Chapter 13 holds the EBNF grammar. |
| `RegelSpraak-specificatie - syntaxdiagrammen v2.3.0.md` | Chapter 13 syntax diagrams (the EBNF non-terminals) — primary source for parser rules. |
| `RegelSpraak-specificatie - typeringen v2.3.0.md` | The typing/datatype matrix — drives the resolver's type checking (operand datatypes, comparisons, units). |
| `RegelSpraak-spec-for-ANTLR-v2.3.0.md` | The EBNF already mapped onto ANTLR lexer/parser rules. **Start here** for grammar authoring; cross-check against the syntaxdiagrammen. |
| `RegelSpraak-TOKA-casus-v2.3.0.md` | A complete worked example (TOKA case). The first end-to-end parse target and a regression fixture. |
| `conversie-bevindingen.md` | Notes on PDF→Markdown conversion fidelity (where the Markdown may diverge). Consult before trusting a surprising detail. |

The authoritative PDFs sit next to the Markdown in the same directory.

## Hard constraint: do not consult the prior parser

**Ignore the existing RegelSpraak parser entirely.** Do not read, fetch, copy,
mirror, or be guided by:

- any `yhavinga` GitHub repositories (`a prior TypeScript implementation`, `regelspraak-parser`, …);
- any sibling checkout of that work on this machine.

That code is the thing being rebooted; mirroring it would replicate its bugs and
defeat the purpose. Derive everything from the specification in this repo. (Web
search for general ANTLR/Java knowledge is fine — just not that prior work.)

## Tooling & build

- **ANTLR 4.13.2** — the latest 4.x; **not** ANTLR 5. The complete jar lives in
  `lib/` and bundles the tool, the Java runtime, and the TestRig harness, so the
  grammar bootstrap needs only a JDK + this jar (no Maven, no network).
- **Java**: JDK 21+ (the dev machine runs JDK 26). The Java target is chosen so
  the parser/resolver can eventually compile straight into the Quarkus/Drools
  engine it feeds.
- **Makefile** is the entry point — `make help` lists targets. Core loop:
  ```
  # author grammar/RegelSpraakLexer.g4 and grammar/RegelSpraak.g4 first, then:
  make parser                                   # generate Java sources (lexer first)
  make compile                                  # javac the generated parser
  make grun RULE=<startRule> FILE=<snippet>     # print the parse tree
  ```
- `lib/` is git-ignored; `make antlr-jar` re-fetches the jar on a fresh clone.

See `docs/ANTLR-HOWTO.md` for the full workflow and the lexer/parser gotchas.

## ANTLR authoring gotchas (summary)

1. **Generate the lexer first.** It emits `RegelSpraakLexer.tokens`, which the
   parser imports via `options { tokenVocab=RegelSpraakLexer; }`. Skipping this
   yields "token not defined" errors. (`make parser` enforces the order.)
2. **Longest multi-word keywords first.** RegelSpraak is natural-language Dutch
   with many multi-word terminals (e.g. `'gedurende de tijd dat'`,
   `'de datum met jaar:'`). Define longer phrases before shorter ones that share
   a prefix, and **all** keyword tokens **before** the generic `IDENTIFIER` rule,
   or maximal-munch will mis-tokenize.
3. **Lexer rules UPPERCASE, parser rules lowercase.** Parser rules map to the
   EBNF non-terminals of Chapter 13.
4. **Semantics are post-parse.** Type checking, name resolution, unit
   compatibility, and arity live in the **resolver** (a tree visitor), not the
   grammar. The parser only fixes syntactic structure.

## From-scratch discipline

- `grammar/` is intentionally empty except for a README. Author
  `RegelSpraakLexer.g4`, then `RegelSpraak.g4`.
- Grow the grammar incrementally against real input: start from the TOKA casus
  and the spec's worked examples; extend rules until they parse, smoke-testing
  with `make grun`.
- The resolver comes after a stable parse tree: walk it with the generated
  visitor, build the resolved AST (the transpiler's IR), and enforce the
  typeringen matrix.

## What is deliberately not here yet

- **No grammar** (`*.g4`) — that is the first task.
- **No Maven/Quarkus project.** The jar-only Makefile is sufficient until the
  resolver and transpiler need a JVM dependency graph and JUnit. When that time
  comes, add a `pom.xml` (Java 21, `org.antlr:antlr4-runtime:4.13.2`, JUnit 5)
  and have Maven compile the ANTLR-generated sources; the Drools engine this
  feeds is Quarkus/Maven, so a Maven build aligns the eventual integration.
