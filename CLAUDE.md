# CLAUDE.md — RegelSpraak-java

## Mission

Build, **from scratch**, a new **RegelSpraak v2.3.0** toolchain in **Java** — an
ANTLR 4 parser + semantic resolver, a typed IR, a RegelSpraak → Drools
transpiler, a firing engine, and a decision service on top.

**The mission is achieved.** Parser, resolver, typed IR, both Drools back-ends
(exec-model + DRL oracle), the fact-model generator, the analyst scenariotoetsen,
and the HTTP decision service (JDK + Quarkus adapters, container/Helm/kind) have
all landed. The repo is now in **maintenance/extension mode**: new work extends
or hardens a shipped chain, it does not bootstrap it. Read `README.md` for the
map and `docs/` for the architecture.

This is a deliberate reboot. A prior parser/resolver exists (TypeScript,
ANTLR-based) but was built quickly with weaker tooling and carries accumulated
bugs. We did **not** refactor it — every construct was re-derived directly from
the specification, with the specification as the single source of truth. That
discipline still governs every extension.

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
- **Makefile** is the entry point — `make help` lists targets. The grammar
  bootstrap loop (still the way to iterate on a grammar change):
  ```
  make parser                                   # regenerate Java sources (lexer first)
  make compile                                  # javac the generated parser
  make grun RULE=<startRule> FILE=<snippet>     # print the parse tree
  ```
  The Maven-backed targets (`make test` for the jar-only resolver suite,
  `mvn -o clean verify`, `make engine`, `make scenario-test`, `make image`,
  `make kind-smoke`) build the rest of the chain — see the gate-set below.
- The root `pom.xml` is **not** an aggregator: `engine/`, `service/`, and
  `service-quarkus/` are separate builds, chained by explicit `mvn install`.
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

## From-scratch discipline (how it was built — and how to extend it)

This is context now, not a task list. The grammar (`grammar/RegelSpraakLexer.g4`,
`grammar/RegelSpraak.g4`) was grown incrementally against real input — the TOKA
casus and the spec's worked examples — smoke-tested with `make grun`. The
resolver walks the parse tree with the generated visitor, builds the resolved AST
(the transpiler's IR), and enforces the typeringen matrix. The transpiler, engine,
and service were layered on top; the Maven/Quarkus build the old plan deferred now
exists (root `pom.xml` + `engine/`, `service/`, `service-quarkus/`).

The same discipline governs any extension: derive every RegelSpraak construct
from the v2.3.0 spec, extend a rule against a failing example, and keep the whole
gate-set green (below). Documented, spec-honest divergences go in
`docs/grammar-divergences.md`; the analyst-toets extension is contracted in
`docs/SCENARIOTOETSEN.md`.

## Working autonomously

This project is built to run hands-off. The green-condition is verifiable, so let
the verifier drive. The chain is shipped; new work extends or hardens it and must
leave the **whole** gate-set green — a green subset is not green.

**The standing gate-set (the definition of green)** — floors are "≥ the value at
HEAD", never a number to chase down:
1. `make test` — the jar-only parser/resolver JUnit suite (offline, no Maven).
2. `mvn -o clean verify` — the full differential suite incl. `src/it/java`
   (DRL fired in Drools, DRL≡exec-model, plus the `ConfluenceFuzzTest`); ≥ 937 green.
3. `make engine` — the exec-model engine consumer; ≥ 115 green. Note: an
   exec-model semantics mutation (e.g. a kenmerk-default or negation-salience
   flip) is caught ONLY by the gate-2 differential (DRL≡exec over `src/it/java`),
   not by `make engine` — the consumer fires one back-end and has no oracle.
4. `make scenario-test` — the golden TOKA analyst set via
   `examples/toka-scenarios/manifest` (single-JVM `--manifest` route);
   30/30 PASS, marker-exit-3 present.
5. `make facts` — the TOKA fact-model beans generate *and* javac clean (doubles
   as a model-level resolve gate).
6. The service modules stay green: `service` ≥ 146, `service-quarkus` ≥ 34.
7. `make verify-corpus` — every spec snippet under `corpusEntry` parses (no
   lexer errors, no `error` nodes), known defects excepted.
7bis. `make verify-negatief-corpus` — every malformed snippet under
   `tools/negatief-corpus/` matches its manifest verdict (correctly rejected, or
   parses-as-expected with a documented `[GAT]` where the grammar is knowingly
   too loose) — the negative counterpart to gate 7.

`make image` / `make kind-smoke` are host-only packaging gates (they need
Docker); run them on the host before a release, not per change.

**The loop:** new work enters as a backlog card (`backlog/` is git-ignored local
planning). Per card: derive the construct from the v2.3.0 spec → extend against a
failing example → run the affected gate red-then-green → re-run the full gate-set
→ commit. Documented, spec-honest divergences go in `docs/grammar-divergences.md`.

**Subagents** (`.claude/agents/`):
- `spec-cartographer` — read-only; maps a §13 section to tokens/rules/examples.
  Fan out over sections in parallel.
- `grammar-adversary` — builds and runs the grammar against spec inputs to find
  mis-parses. Run before declaring a grammar change done.
- `resolver-typechecker` — checks resolver code against the typeringen matrix.

**Commands** (`.claude/commands/`): `/grammar-section <§>` does one §13 section
end-to-end; `/verify-corpus` builds and parses the whole example corpus and
reports.

When a turn claims a gate is green, *show* the `make` / `mvn` output rather than
asserting it — a reader (or a `/goal` evaluator) cannot run commands and reads
only the transcript.

**Permissions:** `.claude/settings.json` sets `defaultMode: bypassPermissions` —
it runs without prompts. **Only run this repo inside a sandbox / VM.** `rm -rf /`,
`rm -rf ~`, and `sudo` are still blocked as circuit-breakers. To dial back,
change `defaultMode` to `acceptEdits`.

**The sandbox launcher lives in the sibling repo** `the sandbox repo`: its
`run-sandbox.sh` builds a container (JDK 21, make, git, ANTLR jar) and mounts this
repo at `/work`. If you are a coding agent running inside that container, just
build in `/work` — there is no Docker in there, so the host-only packaging gates
(`make image`, `make kind-smoke`) cannot run from inside it.
