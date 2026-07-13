# ANTLR HOWTO (RegelSpraak-java)

Everything needed to run ANTLR 4 for this project. Distilled from experience
building RegelSpraak grammars and adapted to the Java target.

## 1. Local jar, no global install

We pin **ANTLR 4.13.2** (the latest 4.x; not ANTLR 5) as a single complete jar
in `lib/`. The complete jar contains the three things we use:

- the **tool** (`org.antlr.v4.Tool`) that turns `.g4` into source,
- the Java **runtime** (`org.antlr.v4.runtime.*`) the generated parser links against,
- the **TestRig** (`org.antlr.v4.gui.TestRig`, a.k.a. `grun`) for smoke tests.

Because the runtime is bundled, the grammar bootstrap needs only a JDK and this
jar — no Maven, no network. Re-fetch on a fresh clone with:

    make antlr-jar
    # = curl -fsSL https://www.antlr.org/download/antlr-4.13.2-complete.jar -o lib/antlr-4.13.2-complete.jar

## 2. Two grammars, generated lexer-first

RegelSpraak is split into a **lexer** grammar and a **parser** grammar:

    grammar/RegelSpraakLexer.g4    // lexer grammar RegelSpraakLexer;   (the tokens)
    grammar/RegelSpraak.g4         // parser grammar RegelSpraak;
                                   //   options { tokenVocab=RegelSpraakLexer; }

**Generate the lexer first.** It writes `RegelSpraakLexer.tokens`; the parser
imports that vocabulary. Generating the parser against a stale or absent
`.tokens` is the classic `token X is not defined` error. The Makefile enforces
the order and passes `-lib` so the parser finds the fresh `.tokens`:

    java -jar lib/antlr-4.13.2-complete.jar -Dlanguage=Java -visitor -no-listener \
        -package io.github.yhavinga.regelspraak.parser -o gen/io/github/yhavinga/regelspraak/parser \
        grammar/RegelSpraakLexer.g4
    java -jar lib/antlr-4.13.2-complete.jar -Dlanguage=Java -visitor -no-listener \
        -package io.github.yhavinga.regelspraak.parser -lib gen/io/github/yhavinga/regelspraak/parser \
        -o gen/io/github/yhavinga/regelspraak/parser grammar/RegelSpraak.g4

`-visitor -no-listener`: we walk the tree with a visitor (the resolver returns
values bottom-up); we don't use the listener, so we don't generate it.

## 3. Compile and smoke-test (jar only)

TestRig runs against *compiled* classes, so compile the generated sources first:

    javac -cp lib/antlr-4.13.2-complete.jar -d out gen/io/github/yhavinga/regelspraak/parser/*.java
    java  -cp lib/antlr-4.13.2-complete.jar:out \
        org.antlr.v4.gui.TestRig io.github.yhavinga.regelspraak.parser.RegelSpraak <startRule> -tree <file>

`make grun RULE=<startRule> FILE=<file>` wraps this; `make gui ...` opens the
visual tree inspector (`-gui`) instead of printing text. Other useful TestRig
flags: `-tokens` (dump the token stream), `-trace` (rule enter/exit).

## 4. Lexing a natural-language DSL — the ordering trap

RegelSpraak reads like Dutch prose, so the lexer carries many **multi-word
terminals**, e.g. `'gedurende de tijd dat'`, `'de datum met jaar:'`,
`'Extensie van objecttype'`. ANTLR uses maximal-munch with first-match-wins on
ties, so:

- define **longer phrases before shorter** ones that share a prefix;
- define **all keyword tokens before** the generic `IDENTIFIER` rule, or the
  identifier rule will swallow words that should be keywords;
- the ANTLR-targeted spec already lists keywords longest-first — see
  `specification/v2.3.0/RegelSpraak-spec-for-ANTLR-v2.3.0.md` §2.

Conventions: lexer rules UPPERCASE, parser rules lowercase (and corresponding to
EBNF non-terminals in Chapter 13). Whitespace is matched by a `WS` rule and
skipped (`-> skip`).

## 5. Where the grammar comes from

- **`specification/v2.3.0/RegelSpraak-spec-for-ANTLR-v2.3.0.md`** — the EBNF
  already mapped onto ANTLR lexer/parser rules. The best starting point.
- **`… - syntaxdiagrammen v2.3.0.md`** — Chapter 13 syntax diagrams; the
  authoritative non-terminal structure to cross-check against.
- **`… - typeringen v2.3.0.md`** — the datatype matrix; not grammar, but the
  contract the **resolver** enforces after parsing.
- **`RegelSpraak-TOKA-casus-v2.3.0.md`** — a full example; the first thing the
  grammar should parse end-to-end and a natural regression fixture.

## 6. Targets other than Java (reference)

We use Java. To retarget, change `-Dlanguage=` (ANTLR 4.13.2 ships Java, Cpp,
CSharp, Dart, JavaScript, TypeScript, Python3, PHP, Swift, Go). Note the
TypeScript target emits trailing-whitespace artifacts that some projects
normalize after generation; the Java target needs no such step.

## 7. Toward Maven/Quarkus (later)

The jar-only flow is enough to build and test the grammar. When the resolver and
the Drools transpiler grow into a real JVM codebase with JUnit, add a `pom.xml`:
Java 21, `org.antlr:antlr4-runtime:4.13.2`, JUnit 5, and either keep the Makefile
generating sources (compiled by Maven as an extra source root) or adopt the
`antlr4-maven-plugin`. The Drools engine this feeds is Quarkus/Maven, so a Maven
build aligns the eventual integration.

## 8. Common errors

| Symptom | Cause / fix |
|---------|-------------|
| a keyword parsed as `IDENTIFIER`, or `token recognition error` | keyword defined after `IDENTIFIER`, or a longer phrase defined after a shorter prefix — reorder lexer rules |
| `token <X> is not defined` when generating the parser | lexer not regenerated; run the lexer step first (`make parser` does) |
| TestRig `ClassNotFoundException` | generated classes not compiled / not on the classpath — run `make compile` (the Makefile chains it) |
| `Could not find or load main class org.antlr.v4.gui.TestRig` | a runtime-only jar on the classpath — use the *complete* jar |
