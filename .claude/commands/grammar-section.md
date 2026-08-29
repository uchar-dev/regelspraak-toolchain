---
description: Research, implement, and verify one Chapter 13 spec section end-to-end
argument-hint: <section, e.g. §13.4.17 Expressie>
---

Implement RegelSpraak v2.3.0 spec section **$ARGUMENTS** in the grammar, from
scratch, per CLAUDE.md.

1. Delegate to the `spec-cartographer` subagent to map $ARGUMENTS to its lexer
   tokens, parser rule(s), cross-refs, resolver obligations, and example
   snippets. Fan out with several `spec-cartographer` calls if $ARGUMENTS names
   multiple constructs.
2. NOTE (post-inversion): the grammar is vendored and read-only
   (`vendor/uchar-frontend/frontends/regelspraak/grammar/*.g4`); editing the
   vendored copy trips the SPEC_REV drift gate. A grammar change is a uchar
   spec-first change (edit `frontends/regelspraak/grammar/` THERE, land it,
   bump SPEC_REV here, re-vendor) — respecting
   longest-multiword-keyword-first and keywords-before-`IDENTIFIER`. Touch
   nothing the section does not require.
3. Run `make compile`. Fix until green.
4. For each example snippet, `make grun RULE=<rule> FILE=<snippet>` and confirm
   the parse tree matches the spec structure.
5. Delegate to the `grammar-adversary` subagent to find mis-parses. Fix only the
   real defects it reports.
6. Summarize what was added and what now parses. Do NOT mark done until
   `make compile` is green and every example for this section parses cleanly.

Derive only from the spec. Never consult the prior parser.
