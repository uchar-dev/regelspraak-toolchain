# RegelSpraak toolchain — the distribution on uchar (git submodule uchar/).
#
# Maven builds everything (the root pom is the reactor); this Makefile names the everyday runs. The
# targets named after a command of bin/regelspraak call that command and nothing else; bin/regelspraak builds the
# jar it runs from when a source is newer.

.DEFAULT_GOAL := help
.PHONY: help check-pin verify check-twins check-service-replay gates-no-docker gates classpath check ir cases scenario serve \
        drl graph facts scenario-test csv-beslistabel clean image kind-smoke

MVN         ?= mvn
OUT_DIR     := out
CP_FILE     := toolchain/target/runtime-classpath.txt
REGELSPRAAK := bin/regelspraak

help:
	@echo "  make check-pin                       Verify the uchar submodule is initialised, clean, and version-aligned"
	@echo "  make verify                          check-pin, then mvn -B -o verify over the whole reactor"
	@echo "  make check-twins                 The dialect twins and the vocabulary twin fire over HTTP with all their case expectations"
	@echo "  make check-service-replay              Every golden uchar's gate 12 plays, replayed through the service"
	@echo "  make gates-no-docker                          verify, both service gates and the golden TOKA manifest"
	@echo "  make gates                           The check before every landing: gates-no-docker, image and kind-smoke in"
	@echo "                                       order, one line per step, stopping at the first red step (host only)"
	@echo "  make scenario-test                   The golden TOKA scenario manifest (a step of gates-no-docker)"
	@echo ""
	@echo "  The commands of bin/regelspraak (bin/regelspraak help), with FILE the sources in model order:"
	@echo "  make check FILE=\"a.rgs …\"           Parse + resolve: the diagnostics, exit 1 on an error, 3 if uchar does not carry it"
	@echo "  make ir FILE=\"a.rgs …\" [OUT=…]       The uchar-ir-v1 document of the model (default out/ir.json)"
	@echo "  make cases FILE=\"a.rgs …\" [OUT=…]    The scenarios lowered to case files beside ir.json (default out/cases/)"
	@echo "  make scenario FILE=\"[--trace] [--explanation] a.rgs …\"   PASS / FAIL / MARKER per scenario"
	@echo "  make serve FILE=\"d.service.json a.rgs …\" [PORT=…]   The decision service on the JDK HttpServer;"
	@echo "                                       POST /<path>?explanation=true adds the explanation to the answer,"
	@echo "                                       DETAIL=true answers ?trace, ?coverage and ?fixpoint,"
	@echo "                                       PLAYGROUND=true binds the dev-only /playground workbench routes,"
	@echo "                                       with DETAIL=true also /playground/analyse?via=service"
	@echo "  make drl FILE=\"a.rgs …\"             The DRL text of uchar's DRL-text branch on stdout (exit 3 if uchar refuses)"
	@echo "  make graph FILE=\"a.rgs …\" [OUT=… MMD=1]  The rule graph as HTML (or bare Mermaid with MMD=1)"
	@echo "  make facts [FILE=\"a.rgs …\"]         The fact beans, javac'd (out/facts/); no FILE: the TOKA model foundation"
	@echo ""
	@echo "  make csv-beslistabel FILE=<csv> [OUT=<path>]  Convert a CSV decision table to a §12 Beslistabel .rgs fragment"
	@echo "  make clean                           Remove the CLI outputs (out/)"
	@echo ""
	@echo "  make image  [TAG=… DESCRIPTOR_SRC=…] (host only) Build the decision-service service image"
	@echo "  make kind-smoke                      (host only) kind H2/H3/H4 gates against the live service, then delete the cluster"

# The pin is the commit the uchar submodule is checked out at: this target fails when the
# submodule is missing, moved or dirty, when that commit is not on uchar's main (the submodule's origin/main), or
# when the parent pom's versions drift from uchar's.
check-pin:
	bash scripts/check-pin.sh

# The whole reactor offline. uchar's backend and frontend are built without their suites: the backend's pom reads
# -Dmaven.test.skip.exec=true, the frontend has no tests, and uchar runs its suites before a commit lands on its main.
# The toolchain core, the service and the Quarkus adapter run their suites, each held to its floor (tools/check-skips.sh).
verify: check-pin
	$(MVN) -B -o verify -Dmaven.test.skip.exec=true

# The service gates: each starts the JDK service itself (on a free port) and replays uchar case files over HTTP.
check-twins:
	bash scripts/check-twins.sh

check-service-replay:
	bash scripts/check-service-replay.sh

# Everything that calls this distribution green: the pin, the reactor with its per-module floors, the two service
# gates and the golden TOKA manifest.
gates-no-docker: verify check-twins check-service-replay
	$(MAKE) --no-print-directory scenario-test

# The check before every landing: the steps of gates-no-docker, then the image and the kind smoke, in that order, one summary
# line per step, stopping at the first red one (scripts/gates.sh; the full output lands in out/gates/).
gates:
	@bash scripts/gates.sh

# Build the toolchain module and what it needs from uchar, without tests, and record its runtime classpath
# (tools/testdata/run_tests.sh runs on it).
classpath:
	$(MVN) -B -o -q -DskipTests package dependency:build-classpath -DincludeScope=runtime \
		-Dmdep.outputFile=$(CURDIR)/$(CP_FILE) -pl toolchain -am

# Parse and resolve the sources as one model: the model summary and the diagnostics, exit 1 on an error; then the
# build on uchar's exec-model branch, exit 3 when uchar does not carry the model.
check:
	@$(REGELSPRAAK) check $(FILE)

# The DRL text of the model: parse -> resolve -> emit -> uchar's DRL-text branch, on stdout. That branch
# builds no document with a construct it does not lower; the CLI then prints uchar's refusal and exits 3.
drl:
	@$(REGELSPRAAK) drl $(FILE)

# Convert a CSV decision table into the canonical §12 Beslistabel markdown-pipe-table
# .rgs fragment. Pure-python, no deps. CSV in -> Beslistabel block on stdout:
#   make csv-beslistabel FILE=tools/testdata/woonregio-factor.csv
# Add OUT=<path> to write a file instead of stdout. Exit 1 (with row+column on stderr)
# on a bad cell / wrong column count / missing header; the resolver remains the sole
# authority on RegelSpraak content — this target only SHAPES the table.
csv-beslistabel:
	@python3 tools/csv_to_beslistabel.py $(if $(OUT),--out $(OUT)) $(FILE)

# Generate the fact-model beans a model implies, then PROVE they are pure-JDK by javac'ing them. Without FILE the
# input is the TOKA *model foundation* — the exact companion model files the golden scenario manifest
# (examples/toka-scenarios/manifest) lists: toka-01..16 plus the reconciliatie/model-extensie companions. That
# set resolves WITHOUT errors; the toka-2x regel files add no fact-model structure, so the beans are fully
# determined by the foundation. `bin/regelspraak facts` is model-level: it writes NOTHING when the model has
# errors, so this target doubles as a resolve gate. The generated beans need no external classpath (java.* only),
# so plain javac proves them.
TOKA_MODEL_FOUNDATION := \
	$(wildcard examples/toka/toka-0[1-9]-*.rgs) \
	$(wildcard examples/toka/toka-1[0-6]-*.rgs) \
	examples/toka-reconciliation/casus-omissies.rgs \
	examples/toka-scenarios/scenario-model-extensie.rgs
FACTS_DIR := $(OUT_DIR)/facts
facts:
	@rm -rf $(FACTS_DIR)
	@mkdir -p $(FACTS_DIR)/src $(FACTS_DIR)/classes
	@$(REGELSPRAAK) facts $(FACTS_DIR)/src $(if $(FILE),$(FILE),$(TOKA_MODEL_FOUNDATION))
	@javac -d $(FACTS_DIR)/classes $$(find $(FACTS_DIR)/src -name '*.java')
	@echo "PASS: facts gegenereerd én gecompileerd naar $(FACTS_DIR)/classes"

# Emit the rule graph — uchar's bipartite dataflow graph regels ↔ gegevens of the resolved model — over one or
# more .rgs files (concatenated in listed order, so a model chain is FILE="a.rgs b.rgs …"). Default output is a
# self-contained HTML page with an embedded Mermaid flowchart (Mermaid loads from a CDN, so rendering needs the
# network); add MMD=1 for the bare, offline-usable Mermaid text. OUT overrides the default out/rule-graph.{html,mmd}.
# Model-level: an unresolvable model writes NOTHING and exits 1 (the same disk discipline as facts). The
# producer/consumer analysis is the ProducentConsument core behind the service's scope-completeness check, so the
# graph never diverges from that gate.
GRAPH_OUT := $(if $(OUT),$(OUT),$(OUT_DIR)/rule-graph.$(if $(MMD),mmd,html))
graph:
	@mkdir -p $(dir $(GRAPH_OUT))
	@$(REGELSPRAAK) graph $(if $(MMD),--mmd) $(GRAPH_OUT) $(FILE)

# Emit the IR (uchar-ir-v1) of one or more .rgs files (concatenated in listed order) through uchar's own emitter
# mode (Resolver --ir). An unresolvable model writes NOTHING and exits 1. OUT overrides the default.
IR_OUT := $(if $(OUT),$(OUT),$(OUT_DIR)/ir.json)
ir:
	@mkdir -p $(dir $(IR_OUT))
	@$(REGELSPRAAK) ir $(IR_OUT) $(FILE)

# Lower the scenarios of a test document (model + scenario sources, concatenated in order) to uchar case files,
# written with the IR document they run against (ir.json) into one directory, the layout of uchar's corpus. A case
# file the directory already holds that this run would not write is refused. OUT overrides the default out/cases.
CASES_OUT := $(if $(OUT),$(OUT),$(OUT_DIR)/cases)
cases:
	@$(REGELSPRAAK) cases $(CASES_OUT) $(FILE)

# Run the scenariotoetsen over one or more .rgs files, concatenated in order; PASS/FAIL/MARKER per scenario.
#   make scenario FILE="model.rgs regels.rgs scenario.rgs"
# Options ride FILE too, e.g. FILE="--trace a.rgs", FILE="--explanation a.rgs" or FILE="--manifest <manifest>". Exit: 1
# on any FAIL/resolve-error, 3 on a MARKER, else 0.
scenario:
	@$(REGELSPRAAK) scenario $(FILE)

# The golden TOKA set of examples/toka-scenarios/manifest in one JVM: exit 0 when no line mismatches; the
# scenarios uchar's scenario resolver refuses are listed with their reason in scripts/toka-manifest-exclusions.txt
# and must stay refused.
scenario-test:
	@echo "Golden TOKA-set — examples/toka-scenarios/manifest"
	@$(REGELSPRAAK) scenario --manifest examples/toka-scenarios/manifest \
		--exclusions scripts/toka-manifest-exclusions.txt

# The decision service on the JDK HttpServer over a descriptor and its sources (or one uchar-ir-v1 document), in
# the foreground. PORT defaults to the service's own 8080; PLAYGROUND=true binds the dev-only workbench routes.
serve:
	@$(REGELSPRAAK) serve $(FILE) $(if $(PORT),--port $(PORT))

clean:
	rm -rf $(OUT_DIR)

# HOST-side packaging targets (needs Docker): build the decision-service image and
# run the kind H2/H3/H4 smoke gates. DESCRIPTOR_SRC swaps the baked descriptor at
# build time (the H4 rollout overlay).
IMAGE_REPO ?= decision-service
TAG ?= dev
DESCRIPTOR_SRC ?= examples/toka/toka.service.json
image:
	docker build -f docker/Dockerfile --build-arg DESCRIPTOR_SRC=$(DESCRIPTOR_SRC) -t $(IMAGE_REPO):$(TAG) .

# --teardown: a kind control plane left running idles at about a fifth of a core.
kind-smoke:
	bash scripts/kind-smoke.sh --teardown
