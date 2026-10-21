package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.ir.Source;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedRule;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.toolchain.engine.ParameterSetLoader;
import dev.uchar.regelspraak.toolchain.engine.Sentence;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.engine.Verbaliser;
import dev.uchar.regelspraak.toolchain.engine.ParameterSetLoader.ParameterSetError;
import dev.uchar.regelspraak.graph.ProducerConsumer;
import dev.uchar.regelspraak.graph.ProducerConsumer.ElementKey;
import dev.uchar.regelspraak.model.ParameterSet;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.transpiler.core.Coverage;
import dev.uchar.regelspraak.transpiler.core.PeriodCoverage;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;

import org.kie.api.KieBase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The framework-agnostic decision-service CORE. Constructed from a descriptor and the ordered list of
 * {@code .rgs} source files that make up the service (manifest-style), it performs, at startup:
 *
 * <ol>
 *   <li><b>Resolve the FULL concatenated source once</b> — one shared, validated {@link RegelSpraakModel}; the
 *       descriptor is validated against it (unknown regel / regelgroep / objecttype / attribuut / kenmerk /
 *       parameter = a startup error naming the value, ALL collected first). A file set whose union does not
 *       resolve refuses startup with the resolver diagnostics.</li>
 *   <li><b>Build each entry point from the forms of its scope</b> ({@link FormIndex}): the forms its rule names
 *       and rule groups carry are kept, the others dropped ({@link CaseEmitter#withScope}, the lowering uchar's
 *       scenario layer uses), and that model is emitted as a {@code uchar-ir-v1} document and built by uchar's
 *       backend on the exec-model branch ({@link UcharBuild}) — one {@link KieBase} per entry point, so upstream
 *       rules cannot overwrite request-given intermediate inputs, and a form outside the scope does not decide
 *       whether the entry point runs. The build generates the fact beans into a class loader of the entry point's
 *       own, checks the bean contract, and lowers every form with every side table the loader returns.</li>
 *   <li><b>Fail-fast on what uchar does not carry</b> — every refusal on the way (emitter, loader, document,
 *       backend: a construct the exec-model branch does not lower among them), and a scoped rule name that carries
 *       no form in uchar's model, makes the entry point a marker. With {@link CoreConfig#allowMarkers()} false the
 *       startup refuses naming it; with true it starts, lists it under the entry point's markers with uchar's
 *       sentence, and refuses every request to that entry point ({@link NonExecutable}), since an answer
 *       without the construct would be an answer to another rule set.</li>
 * </ol>
 *
 * <p>The generated beans live on the entry point's class loader, so every fact {@code Class.forName}/read/write
 * of a request goes through it. KieBases are immutable after startup; a session is opened per request
 * ({@link Executor}), so the core is thread-safe by construction.
 */
public final class DecisionCore {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private final ServiceDescriptor descriptor;
    private final RegelSpraakModel model;
    private final Map<String, Entrypoint> perPath;   // URL path -> compiled entry point
    /** The loaded, validity-versioned parameter sets (fase 5), or empty when the descriptor names no sidecar. */
    private final List<ParameterSet> parameterSets;
    /** The rendered {@link PeriodCoverage} WARNING lines (regel-geldigheid × parameter-geldigheid gaps), computed
     *  once at startup; served verbatim on /info and logged by ServiceMain. Empty when no gap or no sets. */
    private final List<String> periodWarnings;
    /** Whether a request may ask to see its session ({@link CoreConfig#detail()}). */
    private final boolean detail;
    /** The forms of the model by the names a scope uses, as the entry points were built from them. */
    private final FormIndex forms;
    /** The {@code uchar-ir-v1} document of the source: the one the service was given, or, for {@code .rgs} sources,
     *  the one uchar's emitter writes of the model, emitted on the first ask ({@link #document()}). */
    private volatile String document;
    private DecisionCore(ServiceDescriptor descriptor, RegelSpraakModel model,
                          Map<String, Entrypoint> perPath, List<ParameterSet> parameterSets,
                          List<String> periodWarnings, boolean detail, FormIndex forms, String document) {
        this.descriptor = descriptor;
        this.model = model;
        this.perPath = perPath;
        this.parameterSets = parameterSets;
        this.periodWarnings = periodWarnings;
        this.detail = detail;
        this.forms = forms;
        this.document = document;
    }

    /** One compiled entry point: its descriptor entry, uchar's {@link DroolsBackend.Build} of its scope (the scoped
     *  {@link KieBase}, the class loader of its generated fact beans and the manifest, which a session needs whole
     *  to go through uchar's entry), its marker list, the descriptor parameter defaults resolved to globals, and when
     *  uchar refused its build, the refusal.
     *
     *  <p>{@link #coverage()} is the exec-model branch's manifest of the entry point's document, one verdict per form,
     *  the defaults groups included; the document carries only the forms of the scope, so the manifest is the
     *  scope's. {@code /info} reports it verbatim.
     *
     *  <p>An entry point uchar does not carry, started with {@link CoreConfig#allowMarkers()}, has no build:
     *  {@code markers} names the construct when uchar's refusal names one, {@code weigering} carries uchar's sentence,
     *  and a request to it is refused with {@link NonExecutable}.
     *
     *  <p>{@code explanationBuild} is the build with readings of the same document (card explanation-from-readings): a
     *  request that asks for the explanation is mapped, fired and answered there, once, and the explanation reads the
     *  readings of that session; uchar's witnesses hold that its facts at the fixpoint are those of {@code build}.
     *  {@code document} is what uchar's loader read of that document, with the positions of its nodes, on which the
     *  explanation lays the engine's decisions and readings ({@link Verbaliser.Document}), from sources with the
     *  attribution rules the scope leaves out. Both are null without a build, and for an entry point whose descriptor
     *  says it does not explain ({@link ServiceDescriptor.Entrypoint#explains}). */
    public record Entrypoint(ServiceDescriptor.Entrypoint descriptor, DroolsBackend.Build build,
                             List<String> markers, Map<String, Object> defaultGlobals, String refusal,
                             DroolsBackend.Build explanationBuild, Verbaliser.Document document) {
        /** Whether a request to this entry point can fire: uchar built it. */
        public boolean executable() { return build != null; }

        /** The manifest of the build, empty for an entry point uchar does not carry. */
        public List<Coverage> coverage() { return build == null ? List.of() : build.coverage(); }

        /** The class loader of the generated fact beans, null for an entry point uchar does not carry. */
        public ClassLoader beanLoader() { return build == null ? null : build.beanLoader(); }
    }

    // ---------------- startup ----------------

    /** Build the core from a descriptor file and the service's {@code .rgs} source files (in order), or from one
     *  {@code uchar-ir-v1} document (a {@code .json} file, the only source then; see {@link #buildFromDocument}). The
     *  {@code loader} is the parent for the bean classloader; the generated beans are served from a child
     *  loader created here, not looked up on {@code loader}.
     *
     *  <p><b>Parametersets (fase 5, decision A).</b> When the descriptor names a {@code parameterSets} sidecar
     *  file, it is read RELATIVE to {@code descriptorFile} ({@code descriptorFile.getParent().resolve(naam)})
     *  and handed to the String-form as {@code parameterSetsJson}. A named-but-unreadable sidecar refuses startup
     *  with a {@link ServiceError} naming the bouw-pad (never a silent skip). */
    public static DecisionCore assemble(Path descriptorFile, List<Path> sourceFiles, CoreConfig config,
                                     ClassLoader loader) throws IOException {
        boolean document = sourceFiles.stream().anyMatch(DecisionCore::isDocument);
        if (document && sourceFiles.size() != 1)
            throw new ServiceError("dienst start niet: een uchar-ir-v1-document is de enige bron van een dienst",
                    List.of(Violation.of(Violation.Kind.MALFORMED, "sources",
                            "de bronnen mengen een .json-document met andere bestanden: " + sourceFiles)));
        Map<String, String> text = new LinkedHashMap<>();
        for (Path p : sourceFiles) text.put(p.toString(), Files.readString(p));
        String json = Files.readString(descriptorFile);
        // resolve the parametersets sidecar (if the descriptor names one) relative to the descriptor file.
        String parameterSetsJson = null;
        String name = peekParameterSetsName(json);
        if (name != null) {
            Path parent = descriptorFile.toAbsolutePath().getParent();
            Path sidecar = parent == null ? Path.of(name) : parent.resolve(name);
            try {
                parameterSetsJson = Files.readString(sidecar);
            } catch (IOException e) {
                throw new ServiceError("dienst start niet: parameterSets-bestand kan niet gelezen worden op dit"
                        + " bouwpad", List.of(Violation.of(Violation.Kind.MALFORMED, "parameterSets",
                        "kon parameterSets-bestand '" + sidecar + "' niet lezen: " + e.getMessage())));
            }
        }
        if (document)
            return buildFromDocument(json, text.values().iterator().next(), config, loader, parameterSetsJson);
        return assemble(json, text, config, loader, parameterSetsJson);
    }

    private static boolean isDocument(Path source) {
        return source.getFileName().toString().endsWith(".json");
    }

    /** Peek the {@code parameterSets} file-name field out of a descriptor JSON WITHOUT a full validated bind
     *  (which needs the model). Returns null when absent/blank. A malformed descriptor here is left for the full
     *  {@link #assemble} to report as its own MALFORMED startup fout. */
    private static String peekParameterSetsName(String descriptorJson) {
        try {
            var tree = DescriptorLoader.MAPPER.readTree(descriptorJson);
            var node = tree == null ? null : tree.get("parameterSets");
            if (node != null && node.isTextual() && !node.asText().isBlank()) return node.asText();
        } catch (IOException ignore) { /* full bouw reports the malformed descriptor */ }
        return null;
    }

    /** Build the core from descriptor JSON text and the ordered file-id→source-text map, WITHOUT a parametersets
     *  sidecar (delegates with {@code null}). A descriptor that DOES name a {@code parameterSets} file on this
     *  String-form path — where no sidecar JSON can be resolved — is a startup {@link ServiceError} (decision A). */
    public static DecisionCore assemble(String descriptorJson, Map<String, String> sourcePerFile, CoreConfig config,
                                     ClassLoader loader) {
        return assemble(descriptorJson, sourcePerFile, config, loader, null);
    }

    /** Build the core from descriptor JSON text, the ordered file-id→source-text map, and OPTIONALLY the
     *  parameterset sidecar JSON (fase 5). {@code parameterSetsJson == null} = no sidecar (the fase 1-4 path,
     *  byte-identical). When the descriptor names a {@code parameterSets} file but {@code parameterSetsJson} is
     *  null, that is a startup {@link ServiceError} (decision A: the String-form cannot resolve a bestandsnaam). */
    public static DecisionCore assemble(String descriptorJson, Map<String, String> sourcePerFile, CoreConfig config,
                                     ClassLoader loader, String parameterSetsJson) {
        // 1. resolve the FULL concatenated source once -> the shared model. A union that does not resolve
        //    refuses startup naming each resolver diagnostic (a scope cannot fire against an ill-typed model).
        StringBuilder full = new StringBuilder();
        for (String text : sourcePerFile.values()) full.append(text).append('\n');
        RegelSpraakModel model = Resolver.resolve(full.toString());
        List<Violation> startErrors = new ArrayList<>();
        for (var d : model.errors())
            startErrors.add(Violation.of(Violation.Kind.MALFORMED, "bron:regel" + d.line(),
                    "resolve-fout: " + d.message()));

        // 2. descriptor validation against the shared model (collect all)
        ServiceDescriptor descriptor = readDescriptor(descriptorJson);
        // only validate the descriptor against a well-formed model — a resolve failure already dominates
        FormIndex forms = FormIndex.of(model, model.prioritizedDefaults);
        if (startErrors.isEmpty()) startErrors.addAll(DescriptorLoader.validate(descriptor, model, forms));
        if (!startErrors.isEmpty())
            throw new ServiceError("dienst start niet: descriptor/model-validatie faalt", startErrors);

        // 3. per entry point: the document of the forms its scope carries, built through uchar. What uchar does not
        //    carry — a refusal on the way, or a scoped rule that carries no form — is a marker of that entry point;
        //    allowMarkers only decides whether the service then starts without it.
        Map<String, Entrypoint> perPath = new LinkedHashMap<>();
        List<Violation> markerErrors = new ArrayList<>();
        List<Violation> scopeErrors = new ArrayList<>();
        // the producer index is model-wide (every form that WRITES a kenmerk/attribuut), computed once.
        Map<ElementKey, java.util.Set<String>> producers =
                ProducerConsumer.producerIndex(model, model.prioritizedDefaults);
        for (ServiceDescriptor.Entrypoint ep : descriptor.entrypoints()) {
            FormIndex.Choice choice = forms.scope(ep.scope());
            Map<String, Object> defaults = defaultGlobals(descriptor, model);
            if (!choice.withoutForm().isEmpty()) {
                String name = choice.withoutForm().get(0);
                List<String> uchar = forms.warningsAbout(name);
                marker(ep, name, "de regel '" + name + "' uit de scope levert in uchars model geen vorm op"
                        + (uchar.isEmpty() ? "" : "; uchar: " + String.join("; ", uchar)),
                        defaults, config, perPath, markerErrors);
                continue;
            }
            RegelSpraakModel scoped = CaseEmitter.withScope(model, new ResolvedScenario.Scope(choice.forms()));
            DroolsBackend.Build build;
            DroolsBackend.Build explanationBuild = null;
            Verbaliser.Document explained = null;
            try {
                com.fasterxml.jackson.databind.JsonNode tree = documentTree(UcharBuild.document(scoped));
                build = UcharBuild.assemble(tree, DroolsBackend.Branch.EXEC_MODEL);
                if (ep.explains()) {
                    explanationBuild = UcharBuild.assemble(tree, DroolsBackend.Branch.EXEC_MODEL, true);
                    explained = Verbaliser.Document.of(IrLoader.loadWithPositions(tree))
                            .withOutside(outsideScope(model, scoped));
                }
            } catch (UcharBuild.NotCarried ng) {
                marker(ep, ng.construct(), ng.getMessage(), defaults, config, perPath, markerErrors);
                continue;
            }
            // Only once uchar carries the scope: a marker answers no request, so its reads decide nothing, and uchar's
            // walk throws on a navigation its emitter refuses, where the marker names the refusal.
            if (!ep.scopeCheckOptOut())
                scopeErrors.addAll(scopeCompleteness(ep, model, scoped, producers));
            perPath.put(ep.path(), new Entrypoint(ep, build, List.of(), defaults, null, explanationBuild, explained));
        }
        if (!scopeErrors.isEmpty())
            throw new ServiceError("dienst start niet: onvolledige regel-scope (buiten-scope kenmerk-producent)",
                    scopeErrors);
        if (!markerErrors.isEmpty())
            throw new ServiceError("dienst start niet: uchar draagt een entrypoint niet", markerErrors);

        // 6. parametersets (fase 5, decision B). Load + validate the sidecar against the model, apply the
        //    default×set conflict rule, then compute the regel×parameter period-coverage WARNINGs.
        List<ParameterSet> sets = loadParameterSets(descriptor, parameterSetsJson, model);
        List<String> warnings = periodWarnings(model, sets);

        return new DecisionCore(descriptor, model, perPath, sets, warnings, config.detail(), forms, null);
    }

    /** The {@code uchar-ir-v1} document uchar's emitter wrote, as a tree. */
    private static com.fasterxml.jackson.databind.JsonNode documentTree(String document) {
        try {
            return DescriptorLoader.MAPPER.readTree(document);
        } catch (IOException e) {
            throw new IllegalStateException("de emitter schreef geen leesbare JSON: " + e.getMessage(), e);
        }
    }

    /** The attribution rules of {@code model} that {@code scoped} does not carry, by form name, each with the object
     *  type of its target, in the order of the model: the explanation names them as outside the computation. */
    private static Map<String, String> outsideScope(RegelSpraakModel model, RegelSpraakModel scoped) {
        java.util.Set<String> carried = new java.util.HashSet<>();
        for (ResolvedRule r : scoped.resolvedRules) carried.add(RegelSpraakModel.formName(r.name()));
        Map<String, String> outside = new LinkedHashMap<>();
        for (ResolvedRule r : model.resolvedRules) {
            String form = RegelSpraakModel.formName(r.name());
            if (r.kind() != ResolvedRule.Kind.KENMERKTOEKENNING || carried.contains(form)) continue;
            if (r.target() instanceof dev.uchar.regelspraak.ir.Expr.AttributeReference ref && ref.objectType() != null)
                outside.putIfAbsent(form, ref.objectType().name);
        }
        return outside;
    }

    /**
     * Build the core from a {@code uchar-ir-v1} document instead of RegelSpraak sources — a document another frontend
     * wrote, or uchar's emitter. uchar's loader reads the model from it (the descriptor is validated against that, and
     * requests are mapped with it), and uchar's facade builds it on the exec-model branch, as uchar's gate 12 builds
     * every golden. The document is served whole: a scope drops forms by rebuilding the model it came from, which a
     * document without its source does not have, so an entry point whose scope does not carry every form of the
     * document is a startup error. A document the loader refuses, or one the facade does not carry, is uchar not
     * carrying the service (all entry points are markers); the loader's refusal leaves no model to validate
     * against, so that one refuses the start even with markers allowed.
     */
    public static DecisionCore buildFromDocument(String descriptorJson, String documentJson, CoreConfig config,
                                                ClassLoader loader, String parameterSetsJson) {
        com.fasterxml.jackson.databind.JsonNode document;
        try {
            document = DescriptorLoader.MAPPER.readTree(documentJson);
        } catch (IOException e) {
            throw new ServiceError("dienst start niet: het document is geen JSON", List.of(
                    Violation.of(Violation.Kind.MALFORMED, "source", e.getMessage())));
        }
        IrLoader.Loaded loaded;
        try {
            loaded = IrLoader.loadAll(document);
        } catch (IrLoader.Refusal | IrLoader.DocumentError e) {
            throw new ServiceError("dienst start niet: uchar draagt het document niet", List.of(
                    Violation.of(Violation.Kind.MARKER, "source", (e instanceof IrLoader.Refusal ? "loader: "
                            : "document: ") + e.getMessage())));
        }
        RegelSpraakModel model = loaded.model();
        ServiceDescriptor descriptor = readDescriptor(descriptorJson);
        FormIndex forms = FormIndex.of(model, loaded.prioritizedDefaults());
        List<Violation> startErrors = DescriptorLoader.validate(descriptor, model, forms);
        if (!startErrors.isEmpty())
            throw new ServiceError("dienst start niet: descriptor/model-validatie faalt", startErrors);

        // one build: every entry point serves the whole document; beside it the build with readings when an entry
        // point explains
        DroolsBackend.Build build = null;
        DroolsBackend.Build explanationBuild = null;
        Verbaliser.Document explained = null;
        UcharBuild.NotCarried refusal = null;
        try {
            build = UcharBuild.assemble(document, DroolsBackend.Branch.EXEC_MODEL);
            if (descriptor.entrypoints().stream().anyMatch(ServiceDescriptor.Entrypoint::explains)) {
                explanationBuild = UcharBuild.assemble(document, DroolsBackend.Branch.EXEC_MODEL, true);
                explained = Verbaliser.Document.of(IrLoader.loadWithPositions(document));
            }
        } catch (UcharBuild.NotCarried ng) {
            refusal = ng;
        }
        Map<String, Entrypoint> perPath = new LinkedHashMap<>();
        List<Violation> markerErrors = new ArrayList<>();
        List<Violation> scopeErrors = new ArrayList<>();
        for (ServiceDescriptor.Entrypoint ep : descriptor.entrypoints()) {
            List<String> absent = new ArrayList<>(forms.all());
            absent.removeAll(forms.scope(ep.scope()).forms());
            if (!absent.isEmpty()) {
                scopeErrors.add(Violation.of(Violation.Kind.MALFORMED, "entrypoint:" + ep.path(),
                        "een uchar-ir-v1-document wordt heel gediend, maar de scope van entrypoint '" + ep.name()
                                + "' draagt niet: " + absent));
                continue;
            }
            Map<String, Object> defaults = defaultGlobals(descriptor, model);
            if (refusal != null) {
                marker(ep, refusal.construct(), refusal.getMessage(), defaults, config, perPath, markerErrors);
                continue;
            }
            perPath.put(ep.path(), new Entrypoint(ep, build, List.of(), defaults, null,
                    ep.explains() ? explanationBuild : null, ep.explains() ? explained : null));
        }
        if (!scopeErrors.isEmpty())
            throw new ServiceError("dienst start niet: een scope draagt niet het hele document", scopeErrors);
        if (!markerErrors.isEmpty())
            throw new ServiceError("dienst start niet: uchar draagt een entrypoint niet", markerErrors);
        List<ParameterSet> sets = loadParameterSets(descriptor, parameterSetsJson, model);
        return new DecisionCore(descriptor, model, perPath, sets, periodWarnings(model, sets), config.detail(),
                forms, documentJson);
    }

    private static ServiceDescriptor readDescriptor(String descriptorJson) {
        try {
            return DescriptorLoader.MAPPER.readValue(descriptorJson, ServiceDescriptor.class);
        } catch (IOException e) {
            throw new ServiceError("descriptor is geen geldige JSON", List.of(
                    Violation.of(Violation.Kind.MALFORMED, "", e.getMessage())));
        }
    }

    /** Record that uchar does not carry entry point {@code ep}: {@code construct} is the construct uchar's refusal
     *  names (null when it names none) and {@code reden} uchar's sentence. Strict, it is a startup violation; with
     *  markers allowed, the entry point is registered without a KieBase and every request to it is refused. */
    private static void marker(ServiceDescriptor.Entrypoint ep, String construct, String reason,
                               Map<String, Object> defaults, CoreConfig config, Map<String, Entrypoint> perPath,
                               List<Violation> markerErrors) {
        if (config.allowMarkers()) {
            perPath.put(ep.path(), new Entrypoint(ep, null, construct == null ? List.of() : List.of(construct),
                    defaults, reason, null, null));
            return;
        }
        markerErrors.add(Violation.of(Violation.Kind.MARKER, "entrypoint:" + ep.path(), construct == null
                ? "entrypoint '" + ep.name() + "' wordt niet gedragen door uchar: " + reason
                : "regel '" + construct + "' in de scope van entrypoint '" + ep.name()
                        + "' is een marker (niet gedragen): " + reason));
    }

    /**
     * Load the parameterset sidecar (fase 5, decision B). Returns the empty list when the descriptor names no
     * sidecar. When it DOES:
     * <ul>
     *   <li>{@code parameterSetsJson == null} — a named-but-unresolvable sidecar on the String-form path: a
     *       startup {@link ServiceError} (decision A);</li>
     *   <li>{@link ParameterSetLoader#load} — every {@link ParameterSetError} becomes a MALFORMED startup schending
     *       (a set that does not validate against the model refuses start);</li>
     *   <li><b>conflict rule (pinned):</b> a parameter present in BOTH {@code descriptor.parameters} (defaults)
     *       AND the sets is a startup ERROR naming the parameter — ambiguous authority, never last-one-wins.</li>
     * </ul>
     */
    private static List<ParameterSet> loadParameterSets(ServiceDescriptor descriptor, String parameterSetsJson,
                                                        RegelSpraakModel model) {
        if (descriptor.parameterSets() == null || descriptor.parameterSets().isBlank()) return List.of();
        if (parameterSetsJson == null)
            throw new ServiceError("dienst start niet: parameterSets-bestand kan niet gelezen worden op dit bouwpad",
                    List.of(Violation.of(Violation.Kind.MALFORMED, "parameterSets",
                            "de descriptor noemt parameterSets '" + descriptor.parameterSets() + "' maar er is geen"
                                    + " parameterSets-JSON meegegeven op dit bouwpad")));
        List<ParameterSet> sets;
        try {
            sets = ParameterSetLoader.load(parameterSetsJson, model);
        } catch (ParameterSetError f) {
            List<Violation> sink = new ArrayList<>();
            for (String error : f.errors())
                sink.add(Violation.of(Violation.Kind.MALFORMED, "parameterSets", error));
            if (sink.isEmpty())   // malformed-JSON single-throw carries the message, not the fouten list
                sink.add(Violation.of(Violation.Kind.MALFORMED, "parameterSets", f.getMessage()));
            throw new ServiceError("dienst start niet: parameterSets valideren niet tegen het model", sink);
        }

        // conflict rule: a parameter that is a descriptor default AND a set value has ambiguous authority.
        List<Violation> conflict = new ArrayList<>();
        if (descriptor.parameters() != null) {
            java.util.Set<String> setNames = ParameterSet.pool(sets).keySet();
            for (String d : descriptor.parameters().keySet()) {
                RegelSpraakModel.Parameter p = DescriptorLoader.parameter(model, d);
                String canonical = p == null ? d : p.name();
                if (setNames.contains(canonical))
                    conflict.add(Violation.of(Violation.Kind.MALFORMED, "parameters." + d,
                            "parameter '" + canonical + "' staat ZOWEL in descriptor.parameters (default) ALS in de"
                                    + " parameterSets — ambigue autoriteit; kies er één"));
            }
        }
        if (!conflict.isEmpty())
            throw new ServiceError("dienst start niet: parameter staat zowel in de defaults als in de sets", conflict);
        return sets;
    }

    /** The rendered {@link PeriodCoverage} WARNING lines for the regel-geldigheid × parameter-geldigheid gaps
     *  (fase 5, gate G3). {@link PeriodCoverage#check} is pure; {@link PeriodCoverage#reportAll} renders each
     *  finding onto a throw-away model so we can lift the WARNING message text out verbatim (the SAME text the
     *  engine surfaces), without polluting the real service model's diagnostics. Empty when no gap / no sets. */
    private static List<String> periodWarnings(RegelSpraakModel model, List<ParameterSet> sets) {
        List<PeriodCoverage> findings = PeriodCoverage.check(model, sets);
        List<String> sink = new ArrayList<>();
        RegelSpraakModel shadow = new RegelSpraakModel();
        PeriodCoverage.reportAll(findings, shadow);
        for (var d : shadow.diagnostics)
            if (d.severity() == RegelSpraakModel.Severity.WARNING) sink.add(d.message());
        return sink;
    }

    // ---------------- scope-completeness validation ----------------

    // The producer/consumer analysis — the producer index, the walk over what a form reads, and the ElementKey
    // identity — is the SHARED {@link ProducerConsumer} core (jar-only, no Drools). This class is one of its two
    // consumers (uchar's rule graph is the other); both read the same walk ({@link ProducerConsumer#forms}), so
    // this scope-completeness gate and the graph never diverge.

    /**
     * The scope-completeness violations for one entry point (decision: fail-fast, opt-out per entrypoint). It reads
     * the forms the entry point's KieBase carries — {@code scoped}, the model of its scope — as uchar's rule graph
     * reads them: of a rule the kenmerken/attributen its voorwaarde and value read, of a defaults group those the
     * justification and consequence of every default read, nested exception groups included. A read element is a
     * violation iff (a) some form in {@code producers} WRITES it, (b) NONE of those producers is in this entry
     * point's scope, and (c) the element is not a declared {@code inputElements} (the caller then supplies the
     * value, so the missing producer is intentional). Elements with no producer at all are genuine request inputs,
     * not violations. The violation of a group also names the defaults that read the element, from uchar's walk per
     * default ({@link ProducerConsumer#readings}), of which the group's reads are the union.
     */
    private static List<Violation> scopeCompleteness(ServiceDescriptor.Entrypoint ep, RegelSpraakModel model,
            RegelSpraakModel scoped, Map<ElementKey, java.util.Set<String>> producers) {
        // a resolver's model: a navigated kenmerk check reads the kenmerk of the type its role reaches
        ProducerConsumer.Navigation navigation = ProducerConsumer.sourceNavigation(scoped);
        List<ProducerConsumer.Form> carried = ProducerConsumer.forms(scoped, scoped.prioritizedDefaults,
                navigation);
        Map<String, PrioritizedDefaults> groups = new java.util.HashMap<>();
        for (PrioritizedDefaults p : scoped.prioritizedDefaults)
            if (p.name() != null) groups.put(ProducerConsumer.baseName(p.name()), p);
        // the writers in the scope, under the names the producer index keys them on
        java.util.Set<String> scope = new java.util.LinkedHashSet<>();
        for (ProducerConsumer.Form v : carried) scope.add(v.name());
        // The opt-out keys through the SAME element identity the consumption does ({@link
        // ProducerConsumer#elementOf}), so the declaration binds on the member's declarant: a caller that
        // declares the element on the type that DECLARES it silences a consumption on any subtype, and one that
        // names a subtype silences it too — the alternative is an escape hatch that exists but cannot be spelled.
        java.util.Set<ElementKey> inputElements = new java.util.LinkedHashSet<>();
        for (String sel : ep.inputElementsOrEmpty()) {
            OutputSelector u = OutputSelector.parse(sel);
            if (u == null) continue;
            RegelSpraakModel.ObjectType holder = DescriptorLoader.objectType(model, u.objectType());
            inputElements.add(holder == null ? ElementKey.of(u.objectType(), u.member(), u.characteristic())
                    : ProducerConsumer.elementOf(holder, u.member(), u.characteristic()));
        }
        // A tree of exceptions is lowered to one group named after its base rule, an unordered block without a
        // carrier to one group per member; the opgave marker and a subtype's own exceptions get a group whose name
        // uchar makes up. The author knows the first two by a rule they wrote, the last two only by that name.
        java.util.Set<String> written = new java.util.HashSet<>();
        for (var r : model.rules) if (r.name() != null) written.add(CaseEmitter.norm(r.name()));

        List<Violation> sink = new ArrayList<>();
        java.util.Set<ElementKey> reported = new java.util.LinkedHashSet<>();
        for (ProducerConsumer.Form v : carried) {
            String reader = switch (v.kind()) {
                case REGEL -> "regel '" + v.name() + "'";
                case DEFAULTS_GROUP -> written.contains(CaseEmitter.norm(v.name()))
                        ? "defaults-groep van regel '" + v.name() + "'"
                        : "gegenereerde defaults-groep '" + v.name() + "'";
                // The other forms read as well, but are not held to it: five entry points of
                // examples/toka/toka.service.json scope an allocation without the writers of what it reads.
                case OBJECT_CREATION, ALLOCATION, CONSISTENCY, UNIQUENESS, FACT_CREATION -> null;
            };
            if (reader == null) continue;
            List<ProducerConsumer.DefaultRead> reads = null;
            for (ElementKey consumed : v.reads()) {
                java.util.Set<String> writers = producers.get(consumed);
                if (writers == null || writers.isEmpty()) continue;          // a genuine request input
                if (writers.stream().anyMatch(scope::contains)) continue;    // produced in scope: fine
                if (inputElements.contains(consumed)) continue;            // element-level caller input: opt-out
                if (!reported.add(consumed)) continue;
                String where = "";
                if (v.kind() == ProducerConsumer.FormKind.DEFAULTS_GROUP) {
                    PrioritizedDefaults group = java.util.Objects.requireNonNull(groups.get(v.name()),
                            () -> "geen defaultsgroep '" + v.name() + "' in de scope");
                    if (reads == null) reads = ProducerConsumer.readings(scoped, group, navigation);
                    where = " " + whereRead(reads, consumed, v.name());
                }
                sink.add(Violation.of(Violation.Kind.INCOMPLETE_SCOPE, "entrypoint:" + ep.path(),
                        reader + " in entrypoint '" + ep.name() + "' leest "
                                + (consumed.characteristic() ? "kenmerk" : "attribuut") + " '" + consumed.name() + "'"
                                + (consumed.objectType().isEmpty() ? "" : " van '" + consumed.objectType() + "'")
                                + where + ", maar de enige producent(en) " + writers + " vallen buiten de scope"
                                + " en het element is niet als invoerElement gedeclareerd"));
            }
        }
        return sink;
    }

    /** Where a defaults group reads {@code element}: every default that reads it, by the part that reads (its
     *  justification or its consequence) with that part's source text, and by its path in the group. */
    private static String whereRead(List<ProducerConsumer.DefaultRead> reads, ElementKey element,
                                      String group) {
        List<String> sites = new ArrayList<>();
        for (ProducerConsumer.DefaultRead d : reads) {
            var dflt = d.dflt();
            if (d.justification().contains(element))
                sites.add(site("rechtvaardiging", dflt.condition() == null ? null : dflt.condition().source(), d));
            if (d.consequence().contains(element))
                sites.add(site("consequentie", dflt.consequence() == null ? null : dflt.consequence().source(), d));
        }
        // uchar takes a group's reads from these, so an element no default reads is a broken promise of uchar's
        if (sites.isEmpty())
            throw new IllegalStateException("uchars wandeling per default van defaults-groep '" + group
                    + "' leest '" + element.name() + "' niet, maar die van de groep wel");
        return String.join(" en ", sites);
    }

    /** One reading default: the part that reads, its source text as the author wrote it (or else the default's own
     *  source, which the opgave marker fills with the attribute it reads), and the default's path in the group. */
    private static String site(String part, Source source, ProducerConsumer.DefaultRead d) {
        String text = source != null && source.snippet() != null && !source.snippet().isBlank()
                ? source.snippet() : d.dflt().source();
        return "in de " + part + (text == null ? "" : " '" + text + "'") + " van default " + d.path();
    }

    /** The descriptor parameter defaults, decoded per-parameter datatype to (globalName -> value). */
    private static Map<String, Object> defaultGlobals(ServiceDescriptor descriptor, RegelSpraakModel model) {
        Map<String, Object> globals = new LinkedHashMap<>();
        if (descriptor.parameters() == null) return globals;
        List<Violation> errors = new ArrayList<>();
        for (var e : descriptor.parameters().entrySet()) {
            RegelSpraakModel.Parameter p = DescriptorLoader.parameter(model, e.getKey());
            if (p == null) continue;   // already reported by descriptor validation
            ValueCoder.Result r = ValueCoder.decode(e.getValue(), p.datatype(), "parameters." + e.getKey());
            errors.addAll(r.violations());
            if (r.violations().isEmpty()) globals.put(RequestMapper.globalName(p.name()), r.value());
        }
        if (!errors.isEmpty()) throw new ServiceError("descriptor parameter-default ongeldig", errors);
        return globals;
    }

    // ---------------- request handling ----------------

    /** Handle a request against the entry point at {@code path}: map → fire → build response. Existing 2-arg
     *  contract (no uitleg); delegates with {@link RequestOptions#DEFAULT}, byte-identical for every current caller. */
    public ObjectNode process(String path, JsonNode requestTree) {
        return process(path, requestTree, RequestOptions.DEFAULT);
    }

    /** As {@link #process(String, JsonNode, RequestOptions)} with only the explanation asked for or not. */
    public ObjectNode process(String path, JsonNode requestTree, boolean explanation) {
        return process(path, requestTree, new RequestOptions(explanation, null, null));
    }

    /**
     * Handle a request with what it asks beside its body. When {@code explanation} is asked, an {@code "explanation"} array is
     * added to the response — one NL sentence per assigned/withheld kenmerk of a request instance, justified with the
     * evaluated operandwaarden (§norm read via the IR, not the programmer); without it the response tree has no such
     * key. The request's names are read by a {@link NameReader}, liberally or in the input vocabulary the request
     * pins; the answer and its explanation name the declarations in the vocabulary the request chooses, else in the
     * entry point's default from the descriptor, else canonically ({@link Naming}). A vocabulary the model does
     * not declare is refused before the body is read: an answer in it could not be given.
     *
     * <p>The detail flags of {@code options} add the session to the answer, after the explanation ({@link #detail}), to
     * the {@link EvaluationFault} of a request on which the rules give a fault, and to the {@link NoFixpoint} of a request
     * whose session reached no fixpoint. A core started without
     * {@link CoreConfig#detail()} refuses each of them by its name before the body is read: it shows every intermediate
     * value, where the answer gives the selected {@code output} alone. Without a detail flag the answer is the one a
     * core without detail gives, byte for byte.
     */
    public ObjectNode process(String path, JsonNode requestTree, RequestOptions options) {
        return process(path, requestTree, options, null).answer();
    }

    /** The answer to a request and uchar's verdict on expectations about it ({@link #assess}). */
    public record Assessment(ObjectNode answer, DroolsBackend.Comparison comparison) {}

    /**
     * As {@link #process(String, JsonNode, RequestOptions)}, with uchar's verdict on {@code expectations}, the
     * {@code expected} of a {@code uchar-case-v0} case whose labels are ids of the request: uchar compares them with the
     * written-back session of the request ({@link DroolsBackend#vergelijk}), the ids of the request as labels. No route
     * of the service asks it; the playground does, for {@code via=service}.
     *
     * @throws Evaluatiefout what {@link #process(String, JsonNode, RequestOptions)} throws, and nothing is compared
     * @throws NoFixpoint   what {@link #process(String, JsonNode, RequestOptions)} throws, and nothing is compared
     * @throws dev.uchar.regelspraak.cases.CaseRunner.Excluded an expectation of a form uchar does not compare
     */
    public Assessment assess(String path, JsonNode requestTree, RequestOptions options, JsonNode expectations) {
        java.util.Objects.requireNonNull(expectations, "verwachtingen");
        return process(path, requestTree, options, expectations);
    }

    private Assessment process(String path, JsonNode requestTree, RequestOptions options, JsonNode expectations) {
        Entrypoint ep = perPath.get(path);
        if (ep == null) throw new UnknownPath(path);
        if (!ep.executable()) throw new NonExecutable(ep);
        List<Violation> choices = new ArrayList<>();
        Naming output = naming(options.vocabulary() != null ? options.vocabulary()
                : ep.descriptor().vocabulary(), "vocabulary", choices);
        if (options.inputVocabulary() != null) naming(options.inputVocabulary(), "inputVocabulary", choices);
        if (!detail)
            for (String flag : options.detailFlags())
                choices.add(Violation.of(Violation.Kind.UNSUPPORTED, flag, "de dienst is zonder DETAIL gestart"
                        + " en geeft geen " + flag + "; start hem met DETAIL=true of --detail"));
        if (options.explanation() && ep.explanationBuild() == null)
            choices.add(Violation.of(Violation.Kind.UNSUPPORTED, "explanation", "het entrypoint '" + ep.descriptor().name()
                    + "' geeft geen uitleg: de descriptor zet explanation op false"));
        if (!choices.isEmpty()) throw new ServiceError("verzoek valideert niet", choices);
        // an explanation is given from the build with readings, which answers the request as well: one fire
        DroolsBackend.Build fired = options.explanation() ? ep.explanationBuild() : ep.build();
        RequestMapper mapper = new RequestMapper(model, fired, new NameReader(model, options.inputVocabulary()));
        RequestMapper.Result mr = mapper.map(new Request(requestTree));
        if (!mr.violations().isEmpty())
            throw new ServiceError("verzoek valideert niet", mr.violations());
        // core contract check (fase 6, G4): the entrypoint's invoer list is the DECLARED contract — every
        // objecttype it names MUST have at least one instantie in the request. A mapping/adapter rejection here
        // happens strictly BEFORE Executor.fire, so the resulting 4xx is itself the no-fire witness: an absent
        // required instance can never fire meaningfully (the scoped rules quantify over instances of that
        // objecttype, so a missing invoer objecttype means the rule has nothing to fire against). Collected first,
        // then thrown together as one 400.
        List<Violation> missing = missingInput(ep, mr.typePerLabel(), model);
        if (!missing.isEmpty())
            throw new ServiceError("verzoek valideert niet", missing);
        // fase 5, decision D: resolve the parameterset vintage against the one date authority (the request
        // peildatum, which IS World.evaluationDate). The resolved values sit BETWEEN the descriptor defaults and the
        // request parameter overrides (descriptor default first, set-value next — the conflict rule guarantees
        // they never name the same parameter — request override last: explicit caller-intent wins).
        Map<String, Object> setGlobals = vintageGlobals(mr.world().evaluationDate());
        boolean withDetail = !options.detailFlags().isEmpty();
        // uchar's waarnemer labels a firing with the ids of the request; the input is read off the beans before the
        // fire, because the write-back puts the derived values on the same beans
        Map<Object, String> label = new IdentityHashMap<>();
        mr.world().beans().forEach((id, bean) -> label.put(bean, id));
        DroolsBackend.Observer observer = options.trace() || options.coverage()
                ? new DroolsBackend.Observer(label::get) : null;
        List<FixpointGivens.InputObject> input = options.fixpoint()
                ? FixpointGivens.input(mr.world().beans(), mr.typePerLabel(), output) : null;
        Executor executor = new Executor(fired, ep.defaultGlobals());
        Executor.Outcome outcome;
        try {
            outcome = executor.fire(mr.world(), setGlobals, observer, expectations, options.explanation());
        } catch (EvaluationFault error) {
            if (!withDetail) throw error;
            throw error.withDetail(detail(options, ep, observer, error.fixpoint(), null, input, output));
        } catch (NoFixpoint none) {
            if (!withDetail) throw none;
            throw none.withDetail(detail(options, ep, observer, null, none.getMessage(), input, output));
        }
        ObjectNode answer = new AnswerBuilder(model, output).assemble(outcome, ep.descriptor().output(),
                mr.typePerLabel());
        if (options.explanation()) addExplanation(answer, outcome, mr, ep, output, options.explanationParts());
        if (withDetail) answer.setAll(detail(options, ep, observer, outcome.fixpoint(), null, input, output));
        return new Assessment(answer, outcome.comparison());
    }

    /**
     * The detail of a request's session that {@code options} asks, in this order and under these keys, the names in
     * {@code naming}: {@code trace}, per form that fired, in the order a rule of it first fired, the ids of the
     * request its matches bound ({@code labels}) and how often its rules fired ({@code aantal}), as uchar's
     * {@code observer} recorded them; {@code dekking}, per form of the entry point's build whether a rule of it fired
     * ({@link DroolsBackend#coverage}: {@code FIRED} or {@code NOT_FIRED}); and {@code fixpunt}, what stands at the
     * fixpoint with the instances of the request as its input ({@link FixpointGivens}), or {@code fixpointError} with the
     * reason a value of it cannot be shown, or {@code withoutFixpoint}, uchar's sentence of a run that reached no fixpoint
     * (null at a fixpoint): then there is nothing to show. The playground gives a scenario the same three.
     */
    private ObjectNode detail(RequestOptions options, Entrypoint ep, DroolsBackend.Observer observer, Fixpoint fixpoint,
                              String withoutFixpoint, List<FixpointGivens.InputObject> input, Naming naming) {
        ObjectNode n = NF.objectNode();
        if (options.trace()) {
            ArrayNode trace = n.putArray("trace");
            for (ScenarioResult.Firing v : observer.firings()) {
                ObjectNode tn = trace.addObject();
                tn.put("rule", naming.form(v.rule()));
                ArrayNode labels = tn.putArray("labels");
                for (String l : v.labels()) labels.add(l);
                tn.put("count", v.count());
            }
        }
        if (options.coverage()) {
            ArrayNode coverage = n.putArray("coverage");
            for (ScenarioResult.CoverageRule d : DroolsBackend.coverage(ep.build(), observer.forms())) {
                ObjectNode dn = coverage.addObject();
                dn.put("rule", naming.form(d.rule()));
                dn.put("kind", d.kind().name());
            }
        }
        if (options.fixpoint() && withoutFixpoint != null) {
            n.put("fixpointError", withoutFixpoint);
        } else if (options.fixpoint()) {
            try {
                n.set("fixpoint", FixpointGivens.fixpoint(fixpoint, input, model, naming));
            } catch (FixpointGivens.UnrenderableValue o) {
                n.put("fixpointError", o.getMessage());
            }
        }
        return n;
    }

    /** The naming {@code choice} names, or the canonical one after adding the violation of a vocabulary the model does
     *  not declare to {@code errors}, on the query parameter {@code field}. */
    private Naming naming(String choice, String field, List<Violation> errors) {
        try {
            return Naming.of(model, choice);
        } catch (Naming.VocabularyError f) {
            errors.add(Violation.of(Violation.Kind.UNKNOWN_VOCABULARY, field, f.getMessage()));
            return Naming.canonical(model);
        }
    }

    /**
     * Attach the {@link Verbaliser} verhaal to {@code answer} as an {@code "explanation"} array: the beans of the
     * request with their derived values, on the document of the entry point's build with readings, with the decisions
     * and readings of the session that gave the answer. A pure read of the already-fired session — nothing re-fires,
     * nothing is recomputed. The types of the request's labels and the names lie on the model of that document. With
     * {@code parts}, {@code "explanationParts"} beside it: the same sentences as their parts ({@link ExplanationParts}).
     */
    private void addExplanation(ObjectNode answer, Executor.Outcome outcome, RequestMapper.Result mr, Entrypoint ep,
                                Naming naming, boolean parts) {
        RegelSpraakModel explained = ep.document().model();
        Map<String, RegelSpraakModel.ObjectType> types = new LinkedHashMap<>();
        mr.typePerLabel().forEach((label, type) -> {
            RegelSpraakModel.ObjectType named = explained.objectTypeByName(type.name);
            if (named == null)
                throw new IllegalStateException("het objecttype " + type.name + " van " + label + " staat niet in het"
                        + " document van de uitleg");
            types.put(label, named);
        });
        List<Sentence> sentences = new Verbaliser(ep.document(), Naming.of(explained, naming.choice()), outcome.readings())
                .sentences(outcome.beans(), types, outcome.decisions());
        var arr = answer.putArray("explanation");
        for (Sentence sentence : sentences) arr.add(sentence.text());
        if (parts) answer.set("explanationParts", ExplanationParts.json(sentences));
    }

    /**
     * Resolve the parameterset vintage for one request (fase 5, decision D). Returns the (global-name → value) map
     * of set-resolved parameters, empty when no sets are loaded. Fail-fast against the one date authority
     * ({@code datum} = the request peildatum = {@code World.evaluationDate}):
     * <ul>
     *   <li><b>datum present</b> — {@link ParameterSet#valuesOn}; any gap is a 4xx {@link ServiceError} whose
     *       schendingen name the parameter + the datum, on veld {@code "asOfDate"};</li>
     *   <li><b>datum absent + multi-vintage</b> — a 4xx naming veld {@code "asOfDate"}: the peildatum is
     *       mandatory because the sets carry more than one vintage (ambiguous truth);</li>
     *   <li><b>datum absent + NOT multi-vintage</b> — the degenerate single-value case: resolve with
     *       {@link LocalDate#EPOCH} (date-independent by construction, mirroring the ScenarioRunner).</li>
     * </ul>
     * The resolved values are keyed by the engine's camelCase global name ({@link RequestMapper#globalName}),
     * exactly as the descriptor defaults are.
     */
    private Map<String, Object> vintageGlobals(LocalDate date) {
        if (parameterSets.isEmpty()) return Map.of();
        if (date == null && ParameterSet.multiVintage(parameterSets))
            throw new ServiceError("verzoek valideert niet", List.of(Violation.of(Violation.Kind.WRONG_TYPE,
                    "asOfDate", "asOfDate is verplicht: de geladen parameterSets dragen meer dan één vintage")));
        LocalDate asOf = date != null ? date : LocalDate.EPOCH;   // degeneraat: datum-onafhankelijk
        List<String> holes = new ArrayList<>();
        Map<String, Object> resolved = ParameterSet.valuesOn(parameterSets, asOf, holes);
        if (!holes.isEmpty()) {
            List<Violation> errors = new ArrayList<>();
            for (String gap : holes)
                errors.add(Violation.of(Violation.Kind.WRONG_TYPE, "asOfDate", gap));
            throw new ServiceError("verzoek valideert niet", errors);
        }
        Map<String, Object> globals = new LinkedHashMap<>();
        for (var e : resolved.entrySet())
            globals.put(RequestMapper.globalName(e.getKey()), e.getValue());
        return globals;
    }

    /**
     * The invoer contract check: for every objecttype the entrypoint declares in {@code input}, require at
     * least ONE mapped instance that IS one of that objecttype in the request. A missing one yields a
     * {@link Violation.Kind#MISSING_INSTANCE} with veld {@code "instances"} whose message names BOTH the
     * objecttype AND the entrypoint.
     *
     * <p>Membership is the ch. 2.2 ⊑ ({@link DescriptorLoader#objectMatch}), not name equality: the scoped
     * rules quantify over a pattern on the declared objecttype, which the generated beans {@code extends}, so a
     * subtype instance DOES make those rules fire — refusing it as "no instance of the invoer objecttype" would
     * reject a request the engine can serve. Case-insensitive and, on a model without subtypes, identical to
     * the previous name equality.
     */
    private static List<Violation> missingInput(Entrypoint ep,
            Map<String, RegelSpraakModel.ObjectType> typePerLabel, RegelSpraakModel model) {
        List<Violation> sink = new ArrayList<>();
        List<String> input = ep.descriptor().input();
        if (input == null) return sink;
        for (String required : input) {
            boolean present = false;
            for (RegelSpraakModel.ObjectType ot : typePerLabel.values())
                if (DescriptorLoader.objectMatch(model, ot, required)) { present = true; break; }
            if (!present)
                sink.add(Violation.of(Violation.Kind.MISSING_INSTANCE, "instances",
                        "entrypoint '" + ep.descriptor().name() + "' vereist minstens één instantie van objecttype '"
                                + required + "', maar het verzoek levert er geen"));
        }
        return sink;
    }

    // ---------------- accessors (fase-3 /info) ----------------

    public ServiceDescriptor descriptor() { return descriptor; }
    public RegelSpraakModel model() { return model; }

    /** Whether a request may ask to see its session: the core was started with {@link CoreConfig#detail()}. */
    public boolean detail() { return detail; }

    /** The {@code uchar-ir-v1} document the service serves: the one it was given as its source, or the one uchar's
     *  emitter writes of the model of its {@code .rgs} sources ({@link UcharBuild#document}), the forms of every scope
     *  together. Whoever holds a document equal to it holds the service's model. */
    public String document() {
        String d = document;
        if (d == null) document = d = UcharBuild.document(model);
        return d;
    }

    /**
     * The entry point a scenario with {@code scope} runs on, by its path: one whose scope carries the forms of the
     * scenario ({@link FormIndex}), the forms named as the model spells them without a version. A scenario without a
     * scope runs on every form of the model. An entry point that carries exactly those forms comes first, the first
     * in the order of the descriptor; else the one that carries them with the fewest others, the first on a tie. That
     * one fires the extra forms as well, so its answer can differ from the scenario's, and the path says which entry
     * point it was.
     *
     * @throws NoEntryPoint when no entry point carries every form of the scenario, naming what each one lacks
     */
    public String pathForScope(ResolvedScenario.Scope scope) {
        java.util.Set<String> requested = new java.util.LinkedHashSet<>();
        for (String v : scope == null ? forms.all() : scope.forms())
            requested.add(CaseEmitter.norm(CaseEmitter.baseName(v)));
        String best = null;
        int extra = Integer.MAX_VALUE;
        List<String> shortfall = new ArrayList<>();
        for (ServiceDescriptor.Entrypoint ep : descriptor.entrypoints()) {
            java.util.Set<String> carried = new java.util.LinkedHashSet<>();
            for (String v : forms.scope(ep.scope()).forms()) carried.add(CaseEmitter.norm(v));
            List<String> lacking = new ArrayList<>();
            for (String v : requested) if (!carried.contains(v)) lacking.add(v);
            if (!lacking.isEmpty()) {
                shortfall.add("'" + ep.path() + "' mist " + (lacking.size() <= 5 ? lacking : lacking.subList(0, 5) + " en "
                        + (lacking.size() - 5) + " andere"));
                continue;
            }
            int surplus = carried.size() - requested.size();
            if (surplus < extra) { best = ep.path(); extra = surplus; }
        }
        if (best != null) return best;
        throw new NoEntryPoint((scope == null ? "het scenario noemt geen scope en rekent dus met alle "
                + requested.size() + " vormen van het model" : "de scope van het scenario draagt " + requested.size()
                + " vorm(en)") + "; geen ingangspunt van de dienst draagt ze alle: "
                + String.join("; ", shortfall));
    }

    public Map<String, Entrypoint> entrypoints() { return perPath; }
    public Entrypoint entrypoint(String path) { return perPath.get(path); }

    /** The loaded validity-versioned parameter sets (fase 5), or the empty list when the descriptor names none. */
    public List<ParameterSet> parameterSets() { return parameterSets; }

    /** The rendered regel-geldigheid × parameter-geldigheid WARNING lines (gate G3), served on /info and logged
     *  by {@link dev.uchar.regelspraak.toolchain.service.http.ServiceMain} at startup. Empty when no gap / no sets. */
    public List<String> periodWarnings() { return periodWarnings; }

    /** Thrown when no entry point of the service carries the forms of a scenario ({@link #pathForScope}). */
    public static final class NoEntryPoint extends RuntimeException {
        public NoEntryPoint(String sentence) { super(sentence); }
    }

    /** Thrown when a request targets a path no entry point serves. */
    public static final class UnknownPath extends RuntimeException {
        public UnknownPath(String path) { super("onbekend pad: '" + path + "'"); }
    }

    /** Thrown when a request targets an entry point uchar does not carry, started with
     *  {@link CoreConfig#allowMarkers()}: uchar built no KieBase for it, so no answer it could give is complete.
     *  {@link #reason()} is uchar's sentence, {@link #markers()} the construct it names, if any. */
    public static final class NonExecutable extends RuntimeException {
        private final transient Entrypoint ep;

        public NonExecutable(Entrypoint ep) {
            super("entrypoint '" + ep.descriptor().name() + "' is niet uitvoerbaar: " + ep.refusal());
            this.ep = ep;
        }

        public String reason() { return ep.refusal(); }
        public List<String> markers() { return ep.markers(); }
    }
}
