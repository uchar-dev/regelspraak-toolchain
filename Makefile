# RegelSpraak-rebooted — ANTLR build (Java target)
#
# Builds a RegelSpraak v2.3.0 parser from grammar/*.g4 using a *local* ANTLR jar,
# so no global `antlr4` install is required. The complete jar bundles the ANTLR
# tool, the Java runtime, and the TestRig (grun) harness — so the whole grammar
# bootstrap (generate -> compile -> parse a sample) runs with only a JDK + this
# jar: no Maven, no network. Maven/Quarkus enters later, when the resolver and
# the Drools transpiler grow into real JVM code (see CLAUDE.md / docs/ANTLR-HOWTO.md).

.DEFAULT_GOAL := help
.PHONY: help antlr-jar parser compile grun gui clean clean-jar run-sandbox

ANTLR4_VERSION := 4.13.2
ANTLR4_URL     := https://www.antlr.org/download/antlr-$(ANTLR4_VERSION)-complete.jar
LIB_DIR        := $(CURDIR)/lib
ANTLR4_JAR     := $(LIB_DIR)/antlr-$(ANTLR4_VERSION)-complete.jar
ANTLR4         := java -jar $(ANTLR4_JAR)

# Java package for the generated parser, and the dirs that follow from it.
# Change PACKAGE here if you prefer a different namespace.
PACKAGE  := io.github.yhavinga.regelspraak.parser
PKG_PATH := $(subst .,/,$(PACKAGE))
GEN_DIR  := gen/$(PKG_PATH)
OUT_DIR  := out

# Grammar sources — authored from scratch against the v2.3.0 spec (grammar/README.md).
# The lexer MUST be generated before the parser (see the `parser` target).
GRAMMAR_DIR := grammar
LEXER_G4  := $(GRAMMAR_DIR)/RegelSpraakLexer.g4
PARSER_G4 := $(GRAMMAR_DIR)/RegelSpraak.g4

help:
	@echo "RegelSpraak-rebooted — ANTLR $(ANTLR4_VERSION) (Java target)"
	@echo ""
	@echo "  make antlr-jar                       Ensure lib/antlr-$(ANTLR4_VERSION)-complete.jar is present"
	@echo "  make parser                          Generate the Java lexer+parser (lexer first, then parser)"
	@echo "  make compile                         Compile the generated parser with javac"
	@echo "  make grun RULE=<rule> FILE=<input>   Print the parse tree for FILE (ANTLR TestRig, text)"
	@echo "  make gui  RULE=<rule> FILE=<input>   Open the parse tree in the TestRig GUI inspector"
	@echo "  make clean                           Remove generated sources (gen/) and classes (out/)"
	@echo "  make clean-jar                       Remove the downloaded ANTLR jar"
	@echo ""
	@echo "  make run-sandbox                     (host only) Launch the container that IS the agent's"
	@echo "                                       environment; cannot run from inside the sandbox"
	@echo ""
	@echo "  PACKAGE = $(PACKAGE)"
	@echo "  Grammar files expected: $(LEXER_G4), $(PARSER_G4)"

antlr-jar: $(ANTLR4_JAR)

$(ANTLR4_JAR):
	mkdir -p $(LIB_DIR)
	curl -fsSL $(ANTLR4_URL) -o $@.tmp
	mv $@.tmp $@

# Generate the lexer FIRST: it emits RegelSpraakLexer.tokens, which the parser
# grammar imports via `options { tokenVocab=RegelSpraakLexer; }`. Generating the
# parser without an up-to-date .tokens file is the classic "token X is not
# defined" failure. -lib points the parser step at the freshly-written .tokens.
#
# We invoke ANTLR from inside $(GRAMMAR_DIR) with a bare filename: given a grammar
# path with a directory component, ANTLR mirrors that directory *under* -o, so
# `-o $(GEN_DIR) grammar/X.g4` would bury the output in $(GEN_DIR)/grammar/ where
# neither -lib nor the javac glob below would find it. cd-ing first makes the
# output land exactly at $(GEN_DIR), matching the package path.
parser: $(ANTLR4_JAR)
	@test -f $(LEXER_G4)  || { echo "missing $(LEXER_G4) — author it first (see grammar/README.md)";  exit 1; }
	@test -f $(PARSER_G4) || { echo "missing $(PARSER_G4) — author it first (see grammar/README.md)"; exit 1; }
	mkdir -p $(GEN_DIR)
	cd $(GRAMMAR_DIR) && $(ANTLR4) -Dlanguage=Java -visitor -no-listener -package $(PACKAGE) -o $(CURDIR)/$(GEN_DIR) $(notdir $(LEXER_G4))
	cd $(GRAMMAR_DIR) && $(ANTLR4) -Dlanguage=Java -visitor -no-listener -package $(PACKAGE) -lib $(CURDIR)/$(GEN_DIR) -o $(CURDIR)/$(GEN_DIR) $(notdir $(PARSER_G4))

compile: parser
	mkdir -p $(OUT_DIR)
	javac -cp $(ANTLR4_JAR) -d $(OUT_DIR) $(GEN_DIR)/*.java
	javac -cp $(ANTLR4_JAR):$(OUT_DIR) -d $(OUT_DIR) tools/Parse.java

# Smoke-test the grammar with no application code, e.g.:
#   make grun RULE=regelSpraakDocument FILE=examples/toka/toka-01-....rs
# Uses tools/Parse (not ANTLR's TestRig): a `parser grammar RegelSpraak;` emits a
# class named `RegelSpraak`, but TestRig insists on a `RegelSpraakParser` suffix.
# Parse also exits non-zero on any syntax error or `error` node — the clean
# pass/fail signal /verify-corpus needs.
grun: compile
	java -cp $(ANTLR4_JAR):$(OUT_DIR) Parse $(RULE) $(FILE)

# Token dump (no parse tree), for lexer debugging.
tokens: compile
	java -cp $(ANTLR4_JAR):$(OUT_DIR) Parse $(RULE) $(FILE) -tokens -no-tree

gui: compile
	java -cp $(ANTLR4_JAR):$(OUT_DIR) org.antlr.v4.gui.TestRig $(PACKAGE).RegelSpraak $(RULE) -gui $(FILE)

clean:
	rm -rf gen $(OUT_DIR)

clean-jar:
	rm -f $(ANTLR4_JAR)

# Host-side launcher: build and enter the container sandbox that becomes the
# coding agent's environment. There is no Docker inside the sandbox, so this is
# for the host only — the agent itself already runs in /work and must not call it.
run-sandbox:
	./run-sandbox.sh
