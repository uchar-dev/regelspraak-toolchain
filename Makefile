# RegelSpraak toolchain — the distribution on uchar (git submodule uchar/).
#
# Maven builds everything (the root pom is the reactor); this Makefile names the everyday runs. The
# CLI targets build the toolchain module offline, record its runtime classpath, and run the CLI on it.

.DEFAULT_GOAL := help
.PHONY: help check-pin verify classpath drl facts graaf ir scenario-test csv-beslistabel clean image kind-smoke

MVN      ?= mvn
OUT_DIR  := out
CP_FILE  := toolchain/target/runtime-classpath.txt
TOOLCHAIN_CP = toolchain/target/classes:$$(cat $(CP_FILE))

help:
	@echo "  make check-pin                       Verify the uchar submodule is initialised, clean, and version-aligned"
	@echo "  make verify                          check-pin, then mvn -B -o verify over the whole reactor"
	@echo "  make drl  FILE=<input>               The DRL text of uchar's DRL-text branch on stdout (exit 3 if uchar refuses)"
	@echo "  make facts                           Generate the TOKA fact-model beans and javac them (out/facts/)"
	@echo "  make graaf FILE=\"a.rgs …\" [OUT=… MMD=1]  Emit the dataflow regelgraaf (regels ↔ gegevens) as HTML (or bare Mermaid with MMD=1)"
	@echo "  make ir FILE=\"a.rgs …\" [OUT=…]       Emit the uchar-ir-v1 document of the resolved model (uchar's emitter)"
	@echo "  make scenario-test FILES=\"a.rgs …\"    Run the scenariotoetsen CLI over .rgs files"
	@echo "                                       (no FILES: the golden TOKA set via --manifest in one JVM)"
	@echo "  make csv-beslistabel FILE=<csv> [OUT=<pad>]  Convert a CSV decision table to a §12 Beslistabel .rgs fragment"
	@echo "  the service                          POST /<pad>?uitleg=true adds the uitleg-verhaal to the answer;"
	@echo "                                       PLAYGROUND=true binds the dev-only /playground workbench routes"
	@echo "  make clean                           Remove the CLI outputs (out/)"
	@echo ""
	@echo "  make image  [TAG=… DESCRIPTOR_SRC=…] (host only) Build the beslisservice service image"
	@echo "  make kind-smoke                      (host only) kind H2/H3/H4 gates against the live service"

# The pin is the commit the uchar submodule is checked out at: this target fails when the
# submodule is missing, moved or dirty, or when the parent pom's versions drift from uchar's.
check-pin:
	bash scripts/check-pin.sh

# The whole reactor offline: uchar's backend suite, the toolchain core, the service and the Quarkus adapter.
verify: check-pin
	$(MVN) -B -o verify

# Build the toolchain module and what it needs from uchar, without tests, and record its runtime classpath.
classpath:
	$(MVN) -B -o -q -DskipTests package dependency:build-classpath -Dmdep.includeScope=runtime \
		-Dmdep.outputFile=$(CURDIR)/$(CP_FILE) -pl toolchain -am

# The DRL text of a .rgs file: parse -> resolve -> emit -> uchar's DRL-text branch, on stdout. That branch
# builds no document with a construct it does not lower; the CLI then prints uchar's refusal and exits 3.
drl: classpath
	@java -cp "$(TOOLCHAIN_CP)" dev.uchar.regelspraak.toolchain.cli.RegelSpraakCli --drl $(FILE)

# Convert a CSV decision table into the canonical §12 Beslistabel markdown-pipe-table
# .rgs fragment. Pure-python, no deps. CSV in -> Beslistabel block on stdout:
#   make csv-beslistabel FILE=tools/testdata/woonregio-factor.csv
# Add OUT=<pad> to write a file instead of stdout. Exit 1 (with row+column on stderr)
# on a bad cell / wrong column count / missing header; the resolver remains the sole
# authority on RegelSpraak content — this target only SHAPES the table.
csv-beslistabel:
	@python3 tools/csv_naar_beslistabel.py $(if $(OUT),--out $(OUT)) $(FILE)

# Generate the fact-model beans the TOKA casus implies, then PROVE they are pure-JDK by javac'ing them.
# The input is the TOKA *model foundation* — the exact companion model files the golden scenario manifest
# (examples/toka-scenarios/manifest) lists: toka-01..16 plus the reconciliatie/model-extensie companions. That
# set resolves WITHOUT errors; the toka-2x regel files add no fact-model structure, so the beans are fully
# determined by the foundation. `RegelSpraakCli --facts` is model-level: it writes NOTHING when the model has
# errors, so this target doubles as a resolve gate. The generated beans need no external classpath (java.* only),
# so plain javac proves them.
TOKA_MODEL_FOUNDATION := \
	$(wildcard examples/toka/toka-0[1-9]-*.rgs) \
	$(wildcard examples/toka/toka-1[0-6]-*.rgs) \
	examples/toka-reconciliatie/casus-omissies.rgs \
	examples/toka-scenarios/scenario-model-extensie.rgs
FACTS_DIR := $(OUT_DIR)/facts
facts: classpath
	@rm -rf $(FACTS_DIR)
	@mkdir -p $(FACTS_DIR)/src $(FACTS_DIR)/classes
	@cat $(TOKA_MODEL_FOUNDATION) > $(FACTS_DIR)/toka-model.rgs
	@java -cp "$(TOOLCHAIN_CP)" dev.uchar.regelspraak.toolchain.cli.RegelSpraakCli --facts $(FACTS_DIR)/src $(FACTS_DIR)/toka-model.rgs
	@javac -d $(FACTS_DIR)/classes $$(find $(FACTS_DIR)/src -name '*.java')
	@echo "PASS: facts gegenereerd én gecompileerd naar $(FACTS_DIR)/classes"

# Emit the regelgraaf — uchar's bipartite dataflow graph regels ↔ gegevens of the resolved model — over one or
# more .rgs files (concatenated in listed order, so a model chain is FILE="a.rgs b.rgs …"). Default output is a
# self-contained HTML page with an embedded Mermaid flowchart (Mermaid loads from a CDN, so rendering needs the
# network); add MMD=1 for the bare, offline-usable Mermaid text. OUT overrides the default out/regelgraaf.{html,mmd}.
# Model-level: an unresolvable model writes NOTHING and exits 1 (the same disk discipline as --facts). The
# producer/consumer analysis is the ProducentConsument core behind the service's scope-completeness check, so the
# graph never diverges from that gate.
GRAAF_OUT := $(if $(OUT),$(OUT),$(OUT_DIR)/regelgraaf.$(if $(MMD),mmd,html))
graaf: classpath
	@mkdir -p $(dir $(GRAAF_OUT))
	@java -cp "$(TOOLCHAIN_CP)" dev.uchar.regelspraak.toolchain.cli.RegelSpraakCli $(if $(MMD),--graaf-mmd,--graaf) $(GRAAF_OUT) $(FILE)

# Emit the IR (uchar-ir-v1) of one or more .rgs files (concatenated in listed order) through uchar's own emitter
# mode (Resolver --ir). An unresolvable model writes NOTHING and exits 1. OUT overrides the default.
IR_OUT := $(if $(OUT),$(OUT),$(OUT_DIR)/ir.json)
ir: classpath
	@mkdir -p $(dir $(IR_OUT))
	@java -cp "$(TOOLCHAIN_CP)" dev.uchar.regelspraak.resolver.Resolver --ir $(IR_OUT) $(FILE)

# Run the scenariotoetsen CLI over one or more .rgs files, concatenated in order; PASS/FAIL/MARKER per scenario.
#   make scenario-test FILES="model.rgs regels.rgs scenario.rgs"
# Options ride FILES too, e.g. FILES="--trace a.rgs". Exit: 1 on any FAIL/resolve-error, 3 on a MARKER, else 0.
# Without FILES it runs the golden TOKA set of examples/toka-scenarios/manifest in one JVM.
scenario-test: classpath
ifeq ($(strip $(FILES)),)
	@echo "Golden TOKA-set — examples/toka-scenarios/manifest"
	@java -cp "$(TOOLCHAIN_CP)" dev.uchar.regelspraak.toolchain.engine.ScenarioRunnerCli --manifest examples/toka-scenarios/manifest 2>/dev/null
else
	java -cp "$(TOOLCHAIN_CP)" dev.uchar.regelspraak.toolchain.engine.ScenarioRunnerCli $(FILES)
endif

clean:
	rm -rf $(OUT_DIR)

# HOST-side packaging targets (needs Docker): build the beslisservice image and
# run the kind H2/H3/H4 smoke gates. DESCRIPTOR_SRC swaps the baked descriptor at
# build time (the H4 rollout overlay).
IMAGE_REPO ?= beslisservice
TAG ?= dev
DESCRIPTOR_SRC ?= examples/toka/toka.dienst.json
image:
	docker build -f docker/Dockerfile --build-arg DESCRIPTOR_SRC=$(DESCRIPTOR_SRC) -t $(IMAGE_REPO):$(TAG) .

kind-smoke:
	bash scripts/kind-smoke.sh
