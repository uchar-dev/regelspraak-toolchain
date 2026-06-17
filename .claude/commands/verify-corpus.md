---
description: Build the grammar and parse the whole example corpus, reporting any failures
allowed-tools: Bash, Read, Grep, Glob
---

Verify the current grammar against the full RegelSpraak v2.3.0 example corpus.
This is a report — do not fix anything.

1. `make compile` — if it fails, report the error and stop.
2. Extract every RegelSpraak snippet from
   `specification/v2.3.0/RegelSpraak-TOKA-casus-v2.3.0.md` and the worked
   examples in `RegelSpraak-specificatie v2.3.0.md` and
   `RegelSpraak-spec-for-ANTLR-v2.3.0.md` into temp files.
3. For each snippet, run `make grun RULE=<appropriate start rule> FILE=<tmp>` and
   record whether it parses cleanly (no lexer errors, no `error` nodes).
4. Produce a table: snippet → start rule → pass/fail → first error if any.
5. End with a count (passed / total) and the failing snippets ranked by how
   central they are to the language.
