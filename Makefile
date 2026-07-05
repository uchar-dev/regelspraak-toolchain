# RegelSpraak-rebooted — ANTLR build (Java target)
#
# Builds a RegelSpraak v2.3.0 parser from grammar/*.g4 using a *local* ANTLR jar,
# so no global `antlr4` install is required. The complete jar bundles the ANTLR
# tool, the Java runtime, and the TestRig (grun) harness — so the whole grammar
# bootstrap (generate -> compile -> parse a sample) runs with only a JDK + this
# jar: no Maven, no network. Maven/Quarkus enters later, when the resolver and
# the Drools transpiler grow into real JVM code (see CLAUDE.md / docs/ANTLR-HOWTO.md).

.DEFAULT_GOAL := help
.PHONY: help antlr-jar junit-jar parser compile resolver test verify-corpus drl facts drools-it engine grun gui tokens clean clean-jar image kind-smoke run-sandbox

ANTLR4_VERSION := 4.13.2
ANTLR4_URL     := https://www.antlr.org/download/antlr-$(ANTLR4_VERSION)-complete.jar
LIB_DIR        := $(CURDIR)/lib
ANTLR4_JAR     := $(LIB_DIR)/antlr-$(ANTLR4_VERSION)-complete.jar
ANTLR4         := java -jar $(ANTLR4_JAR)

# JUnit 5 standalone console launcher — bundles the platform + jupiter engine, so
# the resolver's tests run with only a JDK + this jar (same jar-only spirit as the
# ANTLR tool). The ANTLR *runtime* the resolver links against ships inside the
# ANTLR complete jar, so no further downloads are needed.
JUNIT_VERSION := 1.10.2
JUNIT_JAR     := $(LIB_DIR)/junit-platform-console-standalone-$(JUNIT_VERSION).jar
JUNIT_URL     := https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$(JUNIT_VERSION)/junit-platform-console-standalone-$(JUNIT_VERSION).jar

# Resolver (typed AST + typeringen enforcement) sources and tests.
# The executable-model back-end (transpiler/model/*) links against the Drools canonical
# model (org.drools.model.*), a JVM dependency graph this jar-only build deliberately does
# NOT carry — per CLAUDE.md it is a Maven artifact. So exclude it here; `mvn -B test` (with
# the Drools deps) compiles and differential-tests it. The parser, resolver, IR and the DRL
# *text* emitter all stay pure jar-only, so `make test` remains the offline parser/resolver gate.
RESOLVER_SRC := $(shell find src/main/java -name '*.java' -not -path '*/transpiler/model/*' 2>/dev/null)
TEST_SRC     := $(shell find src/test/java -name '*.java' 2>/dev/null)

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
	@echo "  make test                            Run the resolver/type-checker JUnit suite"
	@echo "  make verify-corpus                   Parse every spec example under corpusEntry; report pass/fail"
	@echo "  make drl  FILE=<input>               Transpile a .rs file to Drools (DRL) on stdout"
	@echo "  make facts                           Generate the TOKA fact-model beans and javac them (out/facts/)"
	@echo "  make drools-it                       (needs Maven+network) Compile & fire the DRL in Drools"
	@echo "  make engine                          (needs Maven) Build + test the shippable engine consumer"
	@echo "  make scenario-test FILES=\"a.rs …\"    (needs Maven) Run the scenariotoetsen CLI over .rs files"
	@echo "  make grun RULE=<rule> FILE=<input>   Print the parse tree for FILE (ANTLR TestRig, text)"
	@echo "  make gui  RULE=<rule> FILE=<input>   Open the parse tree in the TestRig GUI inspector"
	@echo "  make clean                           Remove generated sources (gen/) and classes (out/)"
	@echo "  make clean-jar                       Remove the downloaded ANTLR jar"
	@echo ""
	@echo "  make image  [TAG=… DESCRIPTOR_SRC=…] (host only) Build the beslisservice service image"
	@echo "  make kind-smoke                      (host only) kind H2/H3/H4 gates against the live service"
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

junit-jar: $(JUNIT_JAR)

$(JUNIT_JAR):
	mkdir -p $(LIB_DIR)
	curl -fsSL $(JUNIT_URL) -o $@.tmp
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

# Compile the resolver (typed AST builder + typeringen checker) against the
# generated parser. The ANTLR complete jar provides the runtime it links against.
resolver: compile
	javac -cp $(ANTLR4_JAR):$(OUT_DIR) -d $(OUT_DIR) $(RESOLVER_SRC)

# Build + run the resolver's JUnit 5 tests via the standalone console launcher.
test: resolver $(JUNIT_JAR)
	javac -cp $(ANTLR4_JAR):$(JUNIT_JAR):$(OUT_DIR) -d $(OUT_DIR) $(TEST_SRC)
	java -jar $(JUNIT_JAR) execute --class-path $(ANTLR4_JAR):$(OUT_DIR) \
		--scan-class-path --details=tree --disable-banner

# Smoke-test the grammar with no application code, e.g.:
#   make grun RULE=regelSpraakDocument FILE=examples/toka/toka-01-....rs
# Uses tools/Parse (not ANTLR's TestRig): a `parser grammar RegelSpraak;` emits a
# class named `RegelSpraak`, but TestRig insists on a `RegelSpraakParser` suffix.
# Parse also exits non-zero on any syntax error or `error` node — the clean
# pass/fail signal /verify-corpus needs.
grun: compile
	java -cp $(ANTLR4_JAR):$(OUT_DIR) Parse $(RULE) $(FILE)

# Re-extract the spec corpus and parse every snippet under `corpusEntry`,
# reporting passed / failed / known-defect. Non-zero exit on any unexpected fail.
verify-corpus: compile
	@bash tools/verify_corpus.sh

# Transpile a .rs file to Drools (DRL): parse -> resolve -> emit DRL on stdout.
# --allow-markers: this is an inspection tool, so show the DRL even when a construct
# stays an honest marker (the dekking-WARNINGs still land on stderr). A bare
# `Resolver --drl` without the flag exits 3 on any marker — the CI-facing contract.
drl: resolver
	@java -cp $(ANTLR4_JAR):$(OUT_DIR) io.github.yhavinga.regelspraak.resolver.Resolver --drl --allow-markers $(FILE)

# Generate the fact-model beans the TOKA casus implies, then PROVE they are pure-JDK by javac'ing them.
# The input is the TOKA *model foundation* — the exact companion model files the golden scenario manifest
# (examples/toka-scenarios/manifest) lists: toka-01..16 plus the reconciliatie/model-extensie companions. That
# set resolves WITHOUT errors; the toka-2x regel files carry the known §4.1 datum+datum casus-quirk (see
# CasusTest / the --facts error contract) and add no fact-model structure, so the beans are fully determined by
# the foundation. `Resolver --facts` is model-level: it writes NOTHING when the model has errors, so this target
# doubles as a resolve gate. The generated beans need no external classpath (java.* only), so plain javac proves them.
TOKA_MODEL_FOUNDATION := \
	$(wildcard examples/toka/toka-0[1-9]-*.rs) \
	$(wildcard examples/toka/toka-1[0-6]-*.rs) \
	examples/toka-reconciliatie/casus-omissies.rs \
	examples/toka-scenarios/scenario-model-extensie.rs
FACTS_DIR := $(OUT_DIR)/facts
facts: resolver
	@rm -rf $(FACTS_DIR)
	@mkdir -p $(FACTS_DIR)/src $(FACTS_DIR)/classes
	@cat $(TOKA_MODEL_FOUNDATION) > $(FACTS_DIR)/toka-model.rs
	@java -cp $(ANTLR4_JAR):$(OUT_DIR) io.github.yhavinga.regelspraak.resolver.Resolver --facts $(FACTS_DIR)/src $(FACTS_DIR)/toka-model.rs
	@javac -d $(FACTS_DIR)/classes $$(find $(FACTS_DIR)/src -name '*.java')
	@echo "PASS: facts gegenereerd én gecompileerd naar $(FACTS_DIR)/classes"

# Compile AND FIRE the generated DRL in a real Drools engine — the executable
# proof that transpilation is correct, not merely well-shaped. Unlike the jar-only
# targets above, this needs Maven on PATH and network access (it resolves Drools
# from Maven Central, test scope) and runs the full suite incl. src/it/java.
MVN ?= mvn
drools-it:
	$(MVN) -B test

# Build + test the shippable engine consumer (engine/): install the lean transpiler artifact
# (regelspraak-parser, drools-canonical-model only), then build+test the engine module on top of it
# (drools-engine runtime). Proves the RegelSpraak -> exec-model -> KieBase -> fire integration end-to-end.
engine:
	$(MVN) -B -f pom.xml install -DskipTests
	$(MVN) -B -f engine/pom.xml test

# Run the scenariotoetsen CLI (docs/SCENARIOTOETSEN.md) over one or more .rs files via the engine classpath.
#   make scenario-test FILES="model.rs regels.rs scenario.rs"   # concatenated in order; PASS/FAIL/MARKER per scenario
# Options ride FILES too, e.g. FILES="--trace a.rs". Exit: 1 on any FAIL/resolve-error, 3 on a MARKER scope, else 0.
# Installs the lean transpiler (-DskipTests), compiles the engine offline, resolves its classpath, and runs the CLI.
# Without FILES it runs the golden TOKA set via examples/toka-scenarios/manifest — which does NOT exist yet (the
# next phase), so it fails with exit 2 and a clear message.
scenario-test:
	$(MVN) -B -o -f pom.xml install -DskipTests -q
	$(MVN) -B -o -f engine/pom.xml compile -q
	$(MVN) -B -o -f engine/pom.xml dependency:build-classpath -Dmdep.outputFile=$(CURDIR)/engine/target/.scenario-cp -q
ifeq ($(strip $(FILES)),)
	@echo "Golden TOKA-set — examples/toka-scenarios/manifest (docs/SCENARIOTOETSEN.md gate G2)"
	@CP="engine/target/classes:$$(cat engine/target/.scenario-cp)"; \
	MAN=examples/toka-scenarios/manifest; \
	value_ok=0; other_ok=0; fail=0; marker_ok=no; \
	while read -r verwacht scenario companions; do \
	  case "$$verwacht" in ''|'#'*) continue;; esac; \
	  java -cp "$$CP" io.github.yhavinga.regelspraak.engine.ScenarioRunnerCli $$scenario $$companions >/tmp/.scenario-out 2>/dev/null; \
	  got=$$?; \
	  name=$$(basename $$scenario); \
	  if [ "$$got" = "$$verwacht" ]; then \
	    echo "  OK (exit $$got)  $$name"; \
	    if [ "$$verwacht" = "0" ]; then value_ok=$$((value_ok+1)); else other_ok=$$((other_ok+1)); fi; \
	    if [ "$$verwacht" = "3" ]; then marker_ok=yes; fi; \
	  else \
	    echo "  MISMATCH (verwacht exit $$verwacht, kreeg $$got)  $$name"; fail=$$((fail+1)); \
	    grep -v SLF4J /tmp/.scenario-out | sed 's/^/      /'; \
	  fi; \
	done < $$MAN; \
	echo "-------------------------------------------------------------------"; \
	echo "golden TOKA: $$value_ok/30 PASS, marker-exit-3: $$marker_ok  (+$$other_ok andere manifestregels ok, $$fail mismatch)"; \
	[ "$$value_ok" = "30" ] && [ "$$fail" = "0" ] && [ "$$marker_ok" = "yes" ] || { echo "GOLDEN FAALT"; exit 1; }; \
	echo "golden TOKA: 30/30 PASS, marker-exit-3: ok"
else
	java -cp "engine/target/classes:$$(cat engine/target/.scenario-cp)" io.github.yhavinga.regelspraak.engine.ScenarioRunnerCli $(FILES)
endif

# Token dump (no parse tree), for lexer debugging.
tokens: compile
	java -cp $(ANTLR4_JAR):$(OUT_DIR) Parse $(RULE) $(FILE) -tokens -no-tree

gui: compile
	java -cp $(ANTLR4_JAR):$(OUT_DIR) org.antlr.v4.gui.TestRig $(PACKAGE).RegelSpraak $(RULE) -gui $(FILE)

clean:
	rm -rf gen $(OUT_DIR)

clean-jar:
	rm -f $(ANTLR4_JAR)

# HOST-side packaging targets (there is no Docker inside the sandbox): build the
# beslisservice image and run the kind H2/H3/H4 smoke gates. DESCRIPTOR_SRC swaps the
# baked descriptor at build time (the H4 rollout overlay).
IMAGE_REPO ?= beslisservice
TAG ?= dev
DESCRIPTOR_SRC ?= examples/toka/toka.dienst.json
image:
	docker build -f docker/service.Dockerfile --build-arg DESCRIPTOR_SRC=$(DESCRIPTOR_SRC) -t $(IMAGE_REPO):$(TAG) .

kind-smoke:
	bash scripts/kind-smoke.sh

# Host-side launcher: build and enter the container sandbox that becomes the
# coding agent's environment. There is no Docker inside the sandbox, so this is
# for the host only — the agent itself already runs in /work and must not call it.
run-sandbox:
	./run-sandbox.sh
