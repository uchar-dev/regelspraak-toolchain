.DEFAULT_GOAL := help
.PHONY: help drl facts graaf ir engine clean image kind-smoke csv-beslistabel check-pin

OUT_DIR  := out

help:
	@echo "  make drl  FILE=<input>               Transpile a .rgs file to Drools (DRL) on stdout"
	@echo "  make facts                           Generate the TOKA fact-model beans and javac them (out/facts/)"
	@echo "  make graaf FILE=\"a.rgs …\" [OUT=… MMD=1]  Emit the dataflow regelgraaf (regels ↔ gegevens) as HTML (or bare Mermaid with MMD=1)"
	@echo "  make ir FILE=\"a.rgs …\" [OUT=…]       Emit a faithful uchar-ir-v1 JSON dump of the resolved model (the uchar mirror)"
	@echo "  make check-pin                       Verify the uchar submodule is initialised, clean, and version-aligned"
	@echo "  make csv-beslistabel FILE=<csv> [OUT=<pad>]  Convert a CSV decision table to a §12 Beslistabel .rgs fragment"
	@echo "  make engine                          (needs Maven) Build + test the shippable engine consumer"
	@echo "  make scenario-test FILES=\"a.rgs …\"    (needs Maven) Run the scenariotoetsen CLI over .rgs files"
	@echo "                                       (no FILES: the golden TOKA set via --manifest in one JVM)"
	@echo "                                       CLI: --uitleg prints the NL uitleg-verhaal per scenario"
	@echo "  the service                          POST /<pad>?uitleg=true adds the uitleg-verhaal to the answer;"
	@echo "                                       PLAYGROUND=true binds the dev-only /playground workbench routes"
	@echo "  make clean                           Remove classes (out/)"
	@echo ""
	@echo "  make image  [TAG=… DESCRIPTOR_SRC=…] (host only) Build the beslisservice service image"
	@echo "  make kind-smoke                      (host only) kind H2/H3/H4 gates against the live service"

# Transpile a .rgs file to Drools (DRL): parse -> resolve -> emit DRL on stdout.
# --allow-markers: this is an inspection tool, so show the DRL even when a construct
# stays an honest marker (the dekking-WARNINGs still land on stderr). A bare
# `RegelSpraakCli --drl` without the flag exits 3 on any marker — the CI-facing contract.
drl: resolver
	@java -cp $(ANTLR4_JAR):$(OUT_DIR) io.github.yhavinga.regelspraak.cli.RegelSpraakCli --drl --allow-markers $(FILE)

# Convert a CSV decision table into the canonical §12 Beslistabel markdown-pipe-table
# .rgs fragment (docs/grammar-divergences.md §12 CSV-import note). Pure-python, no deps
# (sibling of tools/extract_corpus.py). CSV in -> Beslistabel block on stdout:
#   make csv-beslistabel FILE=tools/testdata/woonregio-factor.csv
# Add OUT=<pad> to write a file instead of stdout. Exit 1 (with row+column on stderr)
# on a bad cell / wrong column count / missing header; the resolver remains the sole
# authority on RegelSpraak content — this target only SHAPES the table.
csv-beslistabel:
	@python3 tools/csv_naar_beslistabel.py $(if $(OUT),--out $(OUT)) $(FILE)

# Generate the fact-model beans the TOKA casus implies, then PROVE they are pure-JDK by javac'ing them.
# The input is the TOKA *model foundation* — the exact companion model files the golden scenario manifest
# (examples/toka-scenarios/manifest) lists: toka-01..16 plus the reconciliatie/model-extensie companions. That
# set resolves WITHOUT errors; the toka-2x regel files carry the known §4.1 datum+datum casus-quirk (see
# CasusTest / the --facts error contract) and add no fact-model structure, so the beans are fully determined by
# the foundation. `RegelSpraakCli --facts` is model-level: it writes NOTHING when the model has errors, so this target
# doubles as a resolve gate. The generated beans need no external classpath (java.* only), so plain javac proves them.
TOKA_MODEL_FOUNDATION := \
	$(wildcard examples/toka/toka-0[1-9]-*.rgs) \
	$(wildcard examples/toka/toka-1[0-6]-*.rgs) \
	examples/toka-reconciliatie/casus-omissies.rgs \
	examples/toka-scenarios/scenario-model-extensie.rgs
FACTS_DIR := $(OUT_DIR)/facts
facts: resolver
	@rm -rf $(FACTS_DIR)
	@mkdir -p $(FACTS_DIR)/src $(FACTS_DIR)/classes
	@cat $(TOKA_MODEL_FOUNDATION) > $(FACTS_DIR)/toka-model.rgs
	@java -cp $(ANTLR4_JAR):$(OUT_DIR) io.github.yhavinga.regelspraak.cli.RegelSpraakCli --facts $(FACTS_DIR)/src $(FACTS_DIR)/toka-model.rgs
	@javac -d $(FACTS_DIR)/classes $$(find $(FACTS_DIR)/src -name '*.java')
	@echo "PASS: facts gegenereerd én gecompileerd naar $(FACTS_DIR)/classes"

# Emit the regelgraaf — the bipartite dataflow graph regels ↔ gegevens the resolved model implies — over one or
# more .rgs files (concatenated in listed order, so a model chain is FILE="a.rgs b.rgs …"). Default output is a
# self-contained HTML page with an embedded Mermaid flowchart (Mermaid loads from a CDN, so rendering needs the
# network); add MMD=1 for the bare, offline-usable Mermaid text. OUT overrides the default out/regelgraaf.{html,mmd}.
# Model-level and jar-only (no Drools): an unresolvable model writes NOTHING and exits 1 (the same disk discipline
# as --facts). The producer/consumer analysis is the shared ProducentConsument core behind the service's
# scope-completeness check, so the graph never diverges from that gate.
GRAAF_OUT := $(if $(OUT),$(OUT),$(OUT_DIR)/regelgraaf.$(if $(MMD),mmd,html))
graaf: resolver
	@java -cp $(ANTLR4_JAR):$(OUT_DIR) io.github.yhavinga.regelspraak.cli.RegelSpraakCli $(if $(MMD),--graaf-mmd,--graaf) $(GRAAF_OUT) $(FILE)

# Emit the IR (uchar-ir-v1) — a FAITHFUL JSON dump of the complete resolved in-memory model (objecttypen with
# every side-map, domeinen, parameters, eenheidstelsels, feittypen, dimensies, tijdlijnen, the resolved rule/
# creatie/verdeling/consistentie/uniciteit/feitcreatie/startpunt/regelgroep lists, and diagnostics): the M1
# container, the L2 reference layer (a reference carries only its symbolic form: its role bindings, no copy of
# its type, one RoleMembership node per role check), and elsewhere the Dutch v0 field names EXACTLY as the Java
# records name them. This is the uchar-programme "mirror" artifact — mirror first, diverge later; no filtering,
# and outside the M1 container and the reference layer no renaming and no cleanup. Over one-or-more .rgs files
# (concatenated in listed order, so a model chain is FILE="a.rgs b.rgs …"). Model-level and jar-only (no Drools):
# an unresolvable model writes NOTHING and exits 1 (the same disk discipline as --facts/--graaf). OUT overrides
# the default.
IR_OUT := $(if $(OUT),$(OUT),$(OUT_DIR)/ir.json)
ir: resolver
	@java -cp $(ANTLR4_JAR):$(OUT_DIR) io.github.yhavinga.regelspraak.resolver.Resolver --ir $(IR_OUT) $(FILE)

MVN ?= mvn
# Build + test the shippable engine consumer (engine/): install the lean transpiler artifact
# (regelspraak-parser, drools-canonical-model only), then build+test the engine module on top of it
# (drools-engine runtime). Proves the RegelSpraak -> exec-model -> KieBase -> fire integration end-to-end.
engine:
	$(MVN) -B -f pom.xml install -DskipTests
	$(MVN) -B -f engine/pom.xml test

# Run the scenariotoetsen CLI (docs/SCENARIOTOETSEN.md) over one or more .rgs files via the engine classpath.
#   make scenario-test FILES="model.rgs regels.rgs scenario.rgs"   # concatenated in order; PASS/FAIL/MARKER per scenario
# Options ride FILES too, e.g. FILES="--trace a.rgs". Exit: 1 on any FAIL/resolve-error, 3 on a MARKER scope, else 0.
# Installs the lean transpiler (-DskipTests), compiles the engine offline, resolves its classpath, and runs the CLI.
# Without FILES it runs the golden TOKA set via examples/toka-scenarios/manifest — which does NOT exist yet (the
# next phase), so it fails with exit 2 and a clear message.
scenario-test:
	$(MVN) -B -o -f pom.xml install -DskipTests -q
	$(MVN) -B -o -f engine/pom.xml compile -q
	$(MVN) -B -o -f engine/pom.xml dependency:build-classpath -Dmdep.outputFile=$(CURDIR)/engine/target/.scenario-cp -q
ifeq ($(strip $(FILES)),)
	@echo "Golden TOKA-set — examples/toka-scenarios/manifest (docs/SCENARIOTOETSEN.md gate G2)"
	@# Single JVM: --manifest draait alle regels in één proces (deelt de TOKA-fundering + fact-beans via de
	@# BeanCompiler-cache), reproduceert dezelfde OK/MISMATCH/summary-uitvoer en de golden-verdict-exitcode.
	@CP="engine/target/classes:$$(cat engine/target/.scenario-cp)"; \
	java -cp "$$CP" io.github.yhavinga.regelspraak.engine.ScenarioRunnerCli --manifest examples/toka-scenarios/manifest 2>/dev/null
else
	java -cp "engine/target/classes:$$(cat engine/target/.scenario-cp)" io.github.yhavinga.regelspraak.engine.ScenarioRunnerCli $(FILES)
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

# The pin is the commit the uchar submodule is checked out at: this target fails when the
# submodule is missing, moved or dirty, or when the parent pom's versions drift from uchar's.
check-pin:
	bash scripts/check-pin.sh
