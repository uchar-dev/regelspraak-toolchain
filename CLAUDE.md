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

### Permitted: the Drools knowledge bridge

One **narrow, mediated** exception to the constraint above. A bridge at
`/work/.bridge` (governed by `/work/.bridge/POLICY.md`) is fed by a host-side
oracle that *filters* the prior work and may deliver only:

- **target-platform facts** (Drools / MVEL / Quarkus / ANTLR / Java) grounded in
  official docs, and
- **spec-derived test goals and coverage checklists** that cite v2.3.0 sections.

The prior parser / resolver / engine remain off-limits as a source of RegelSpraak
**meaning** or **expected values**. You MAY post questions to the bridge and
consume its answers, but you MUST **reject** any answer that conveys prior source
code, a prior construct→DRL mapping, or prior-engine output (provenance
`prior-repo` ⇒ reject), and you MUST **re-derive all RegelSpraak semantics from
the v2.3.0 spec** regardless. Ask "what does Drools require for X?" or "what
should §Y compute?" — never "how did the old repo do X?". Post questions with
`.bridge/ask.sh`. The bridge is the ONLY permitted contact with the prior work;
never read the prior repos directly.

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
5. **Anchor the entry rule in `EOF`.** Without it ANTLR parses a valid prefix and
   ignores the rest, so a malformed file silently "passes" — the top rule must
   consume the whole token stream.
6. **Express precedence with ANTLR4 left recursion.** Order the alternatives of a
   single left-recursive `expressie` rule low-to-high precedence and let ANTLR
   rewrite the recursion, rather than hand-rolling a cascade of rules.
7. **Treat generation warnings as errors.** An ambiguity or nondeterminism warning
   is a grammar defect; a parse that works on top of it is luck.

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

## Working autonomously

This project is built to run hands-off. The done-condition is verifiable, so let
the verifier drive.

**Definition of done**
1. `grammar/RegelSpraakLexer.g4` and `grammar/RegelSpraak.g4` exist.
2. `make compile` is green.
3. Every RegelSpraak snippet in the TOKA casus and the spec's worked examples
   parses cleanly via `make grun` (no lexer errors, no `error` nodes).
4. The resolver builds a typed AST and enforces the typeringen matrix, with
   passing tests.

**The loop** (per Chapter 13 section): map → implement → `make compile` →
`make grun` the examples → adversarial review → next. Work section by section in
§13 order.

**Subagents** (`.claude/agents/`):
- `spec-cartographer` — read-only; maps a §13 section to tokens/rules/examples.
  Fan out over sections in parallel.
- `grammar-adversary` — builds and runs the grammar against spec inputs to find
  mis-parses. Run before declaring a section done.
- `resolver-typechecker` — checks resolver code against the typeringen matrix.

**Commands** (`.claude/commands/`): `/grammar-section <§>` does one section
end-to-end; `/verify-corpus` builds and parses the whole example corpus and
reports.

**Launching**
```
claude --permission-mode plan        # approve the build plan first
/goal Drive this build to the Definition of done above, section by section through
      Chapter 13, spec as the only oracle; commit each green section. Met only once
      a turn has shown `make compile` succeeding, `/verify-corpus` reporting every
      snippet passing, and the resolver tests green — surface that output each turn.
      If blocked, explain and stop after ~25 turns.
```
The `/goal` evaluator is a separate model that reads only this transcript and
cannot run commands, so *show* the `make` / `/verify-corpus` output rather than
asserting success; there is no turn cap unless the condition names one.

Or fan out hard: ask Claude to "create a workflow that maps all of Chapter 13 in
parallel, drafts the lexer then the parser, and adversarially verifies each
section against the TOKA casus."

**Permissions:** `.claude/settings.json` sets `defaultMode: bypassPermissions` —
it runs without prompts. **Only run this repo inside a sandbox / VM.** `rm -rf /`,
`rm -rf ~`, and `sudo` are still blocked as circuit-breakers. To dial back,
change `defaultMode` to `acceptEdits`.

**Your environment is the sandbox.** A human launches you with `./run-sandbox.sh`
(or `make run-sandbox`) on the host; you then run inside a container with this repo
mounted at `/work` — JDK 21, make, git, and the ANTLR jar already present — and
nothing else of the host reachable. If you are the coding agent reading this, you
are *already inside* that container: never run `./run-sandbox.sh` or
`make run-sandbox` yourself — there is no Docker in here and the nesting is
meaningless.
Just build in `/work`. The `bypassPermissions` posture is safe because the
container is the boundary, and your login persists in a named volume across runs.
