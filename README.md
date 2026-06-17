# RegelSpraak-rebooted

A from-scratch ANTLR 4 **parser + semantic resolver** for **RegelSpraak v2.3.0**
(a Dutch business-rules DSL), targeting **Java**, as the front-end for a
RegelSpraak → Drools (DRL) transpiler.

The specification in `specification/v2.3.0/` is the single source of truth. See
**[CLAUDE.md](CLAUDE.md)** for the mission and constraints, and
**[docs/ANTLR-HOWTO.md](docs/ANTLR-HOWTO.md)** for the ANTLR workflow.

## Layout

    grammar/        RegelSpraakLexer.g4 + RegelSpraak.g4  (author from the spec)
    lib/            ANTLR 4.13.2 complete jar  (git-ignored; `make antlr-jar` re-fetches)
    docs/           ANTLR-HOWTO.md
    specification/  v2.3.0 spec (Markdown + PDFs)
    Makefile        build entry point (`make help`)

## Quickstart

    make antlr-jar                          # ensure the ANTLR jar is present
    # ...author grammar/RegelSpraakLexer.g4 and grammar/RegelSpraak.g4...
    make parser                             # generate the Java lexer+parser
    make compile                            # javac the generated sources
    make grun RULE=regel FILE=snippet.rs    # print a parse tree

Requires a JDK (21+). The ANTLR complete jar bundles the runtime and TestRig, so
no Maven is needed for the grammar bootstrap.
