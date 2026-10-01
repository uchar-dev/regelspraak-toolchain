package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.ir.Bron;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.toolchain.engine.Naamgeving;
import dev.uchar.regelspraak.toolchain.engine.ParametersetLader;
import dev.uchar.regelspraak.toolchain.engine.UcharBouw;
import dev.uchar.regelspraak.toolchain.engine.Verbalisator;
import dev.uchar.regelspraak.toolchain.engine.ParametersetLader.ParametersetFout;
import dev.uchar.regelspraak.graaf.ProducentConsument;
import dev.uchar.regelspraak.graaf.ProducentConsument.ElementSleutel;
import dev.uchar.regelspraak.model.Parameterset;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.transpiler.kern.Dekking;
import dev.uchar.regelspraak.transpiler.kern.PeriodeDekking;

import org.kie.api.KieBase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
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
 *   <li><b>Build each entry point from the forms of its scope</b> ({@link VormIndex}): the forms its rule names
 *       and rule groups carry are kept, the others dropped ({@link CaseEmitter#metScope}, the lowering uchar's
 *       scenario layer uses), and that model is emitted as a {@code uchar-ir-v1} document and built by uchar's
 *       backend on the exec-model branch ({@link UcharBouw}) — one {@link KieBase} per entry point, so upstream
 *       rules cannot overwrite request-given intermediate inputs, and a form outside the scope does not decide
 *       whether the entry point runs. The build generates the fact beans into a class loader of the entry point's
 *       own, checks the bean contract, and lowers every form with every side table the loader returns.</li>
 *   <li><b>Fail-fast on what uchar does not carry</b> — every refusal on the way (emitter, loader, document,
 *       backend: a construct the exec-model branch does not lower among them), and a scoped rule name that carries
 *       no form in uchar's model, makes the entry point a marker. With {@link KernConfig#allowMarkers()} false the
 *       startup refuses naming it; with true it starts, lists it under the entry point's markers with uchar's
 *       sentence, and refuses every request to that entry point ({@link NietUitvoerbaar}), since an answer
 *       without the construct would be an answer to another rule set.</li>
 * </ol>
 *
 * <p>The generated beans live on the entry point's class loader, so every fact {@code Class.forName}/read/write
 * of a request goes through it. KieBases are immutable after startup; a session is opened per request
 * ({@link Uitvoerder}), so the core is thread-safe by construction.
 */
public final class BeslisserKern {

    private final DienstDescriptor descriptor;
    private final RegelSpraakModel model;
    private final Map<String, Entrypoint> perPad;   // URL path -> compiled entry point
    /** The loaded, validity-versioned parameter sets (fase 5), or empty when the descriptor names no sidecar. */
    private final List<Parameterset> parametersets;
    /** The rendered {@link PeriodeDekking} WARNING lines (regel-geldigheid × parameter-geldigheid gaps), computed
     *  once at startup; served verbatim on /info and logged by DienstMain. Empty when no gap or no sets. */
    private final List<String> periodeWaarschuwingen;

    private BeslisserKern(DienstDescriptor descriptor, RegelSpraakModel model,
                          Map<String, Entrypoint> perPad, List<Parameterset> parametersets,
                          List<String> periodeWaarschuwingen) {
        this.descriptor = descriptor;
        this.model = model;
        this.perPad = perPad;
        this.parametersets = parametersets;
        this.periodeWaarschuwingen = periodeWaarschuwingen;
    }

    /** One compiled entry point: its descriptor entry, its scoped {@link KieBase}, its marker list, the
     *  descriptor parameter defaults resolved to globals, the §9.8 dagsoort predicates the session must carry as
     *  globals (empty on the exec-model branch, which wires them into its rules), the {@link Dekking} manifest of
     *  its build, the class loader of its generated fact beans, and when uchar refused its build, the refusal.
     *
     *  <p>{@code dekking} is the exec-model branch's manifest of the entry point's document, one verdict per form,
     *  the defaults groups included; the document carries only the forms of the scope, so the manifest is the
     *  scope's. {@code /info} reports it verbatim.
     *
     *  <p>An entry point uchar does not carry, started with {@link KernConfig#allowMarkers()}, has no KieBase and no
     *  bean class loader: {@code markers} names the construct when uchar's refusal names one, {@code weigering}
     *  carries uchar's sentence, and a request to it is refused with {@link NietUitvoerbaar}. */
    public record Entrypoint(DienstDescriptor.Entrypoint descriptor, KieBase kieBase,
                             List<String> markers, Map<String, Object> defaultGlobals,
                             Map<String, java.util.function.Predicate<java.time.LocalDate>> dagsoortGlobalen,
                             List<Dekking> dekking, ClassLoader beanLoader, String weigering) {
        /** Whether a request to this entry point can fire: uchar built its KieBase. */
        public boolean uitvoerbaar() { return kieBase != null; }
    }

    // ---------------- startup ----------------

    /** Build the core from a descriptor file and the service's {@code .rgs} source files (in order), or from one
     *  {@code uchar-ir-v1} document (a {@code .json} file, the only source then; see {@link #bouwVanDocument}). The
     *  {@code loader} is the parent for the bean classloader; the generated beans are served from a child
     *  loader created here, not looked up on {@code loader}.
     *
     *  <p><b>Parametersets (fase 5, decision A).</b> When the descriptor names a {@code parametersets} sidecar
     *  file, it is read RELATIVE to {@code descriptorBestand} ({@code descriptorBestand.getParent().resolve(naam)})
     *  and handed to the String-form as {@code parametersetsJson}. A named-but-unreadable sidecar refuses startup
     *  with a {@link DienstFout} naming the bouw-pad (never a silent skip). */
    public static BeslisserKern bouw(Path descriptorBestand, List<Path> bronBestanden, KernConfig config,
                                     ClassLoader loader) throws IOException {
        boolean document = bronBestanden.stream().anyMatch(BeslisserKern::isDocument);
        if (document && bronBestanden.size() != 1)
            throw new DienstFout("dienst start niet: een uchar-ir-v1-document is de enige bron van een dienst",
                    List.of(Schending.van(Schending.Soort.MALFORMED, "bronnen",
                            "de bronnen mengen een .json-document met andere bestanden: " + bronBestanden)));
        Map<String, String> tekst = new LinkedHashMap<>();
        for (Path p : bronBestanden) tekst.put(p.toString(), Files.readString(p));
        String json = Files.readString(descriptorBestand);
        // resolve the parametersets sidecar (if the descriptor names one) relative to the descriptor file.
        String parametersetsJson = null;
        String naam = parametersetsNaamUit(json);
        if (naam != null) {
            Path parent = descriptorBestand.toAbsolutePath().getParent();
            Path sidecar = parent == null ? Path.of(naam) : parent.resolve(naam);
            try {
                parametersetsJson = Files.readString(sidecar);
            } catch (IOException e) {
                throw new DienstFout("dienst start niet: parametersets-bestand kan niet gelezen worden op dit"
                        + " bouwpad", List.of(Schending.van(Schending.Soort.MALFORMED, "parametersets",
                        "kon parametersets-bestand '" + sidecar + "' niet lezen: " + e.getMessage())));
            }
        }
        if (document)
            return bouwVanDocument(json, tekst.values().iterator().next(), config, loader, parametersetsJson);
        return bouw(json, tekst, config, loader, parametersetsJson);
    }

    private static boolean isDocument(Path bron) {
        return bron.getFileName().toString().endsWith(".json");
    }

    /** Peek the {@code parametersets} file-name field out of a descriptor JSON WITHOUT a full validated bind
     *  (which needs the model). Returns null when absent/blank. A malformed descriptor here is left for the full
     *  {@link #bouw} to report as its own MALFORMED startup fout. */
    private static String parametersetsNaamUit(String descriptorJson) {
        try {
            var boom = DescriptorLoader.MAPPER.readTree(descriptorJson);
            var node = boom == null ? null : boom.get("parametersets");
            if (node != null && node.isTextual() && !node.asText().isBlank()) return node.asText();
        } catch (IOException ignore) { /* full bouw reports the malformed descriptor */ }
        return null;
    }

    /** Build the core from descriptor JSON text and the ordered file-id→source-text map, WITHOUT a parametersets
     *  sidecar (delegates with {@code null}). A descriptor that DOES name a {@code parametersets} file on this
     *  String-form path — where no sidecar JSON can be resolved — is a startup {@link DienstFout} (decision A). */
    public static BeslisserKern bouw(String descriptorJson, Map<String, String> bronPerBestand, KernConfig config,
                                     ClassLoader loader) {
        return bouw(descriptorJson, bronPerBestand, config, loader, null);
    }

    /** Build the core from descriptor JSON text, the ordered file-id→source-text map, and OPTIONALLY the
     *  parameterset sidecar JSON (fase 5). {@code parametersetsJson == null} = no sidecar (the fase 1-4 path,
     *  byte-identical). When the descriptor names a {@code parametersets} file but {@code parametersetsJson} is
     *  null, that is a startup {@link DienstFout} (decision A: the String-form cannot resolve a bestandsnaam). */
    public static BeslisserKern bouw(String descriptorJson, Map<String, String> bronPerBestand, KernConfig config,
                                     ClassLoader loader, String parametersetsJson) {
        // 1. resolve the FULL concatenated source once -> the shared model. A union that does not resolve
        //    refuses startup naming each resolver diagnostic (a scope cannot fire against an ill-typed model).
        StringBuilder full = new StringBuilder();
        for (String tekst : bronPerBestand.values()) full.append(tekst).append('\n');
        RegelSpraakModel model = Resolver.resolve(full.toString());
        List<Schending> startFouten = new ArrayList<>();
        for (var d : model.errors())
            startFouten.add(Schending.van(Schending.Soort.MALFORMED, "bron:regel" + d.line(),
                    "resolve-fout: " + d.message()));

        // 2. descriptor validation against the shared model (collect all)
        DienstDescriptor descriptor = leesDescriptor(descriptorJson);
        // only validate the descriptor against a well-formed model — a resolve failure already dominates
        VormIndex vormen = VormIndex.van(model, model.prioritizedDefaults);
        if (startFouten.isEmpty()) startFouten.addAll(DescriptorLoader.valideer(descriptor, model, vormen));
        if (!startFouten.isEmpty())
            throw new DienstFout("dienst start niet: descriptor/model-validatie faalt", startFouten);

        // 3. per entry point: the document of the forms its scope carries, built through uchar. What uchar does not
        //    carry — a refusal on the way, or a scoped rule that carries no form — is a marker of that entry point;
        //    allowMarkers only decides whether the service then starts without it.
        Map<String, Entrypoint> perPad = new LinkedHashMap<>();
        List<Schending> markerFouten = new ArrayList<>();
        List<Schending> scopeFouten = new ArrayList<>();
        // the producer index is model-wide (every form that WRITES a kenmerk/attribuut), computed once.
        Map<ElementSleutel, java.util.Set<String>> producenten =
                ProducentConsument.producentIndex(model, model.prioritizedDefaults);
        for (DienstDescriptor.Entrypoint ep : descriptor.entrypoints()) {
            VormIndex.Keuze keuze = vormen.scope(ep.scope());
            Map<String, Object> defaults = defaultGlobals(descriptor, model);
            if (!keuze.zonderVorm().isEmpty()) {
                String naam = keuze.zonderVorm().get(0);
                List<String> uchar = vormen.waarschuwingenOver(naam);
                marker(ep, naam, "de regel '" + naam + "' uit de scope levert in uchars model geen vorm op"
                        + (uchar.isEmpty() ? "" : "; uchar: " + String.join("; ", uchar)),
                        defaults, config, perPad, markerFouten);
                continue;
            }
            RegelSpraakModel gescoopt = CaseEmitter.metScope(model, new ResolvedScenario.Scope(keuze.vormen()));
            DroolsBackend.Build build;
            try {
                build = UcharBouw.bouw(gescoopt, DroolsBackend.Branch.EXEC_MODEL);
            } catch (UcharBouw.NietGedragen ng) {
                marker(ep, ng.construct(), ng.getMessage(), defaults, config, perPad, markerFouten);
                continue;
            }
            // Only once uchar carries the scope: a marker answers no request, so its reads decide nothing, and uchar's
            // walk throws on a navigation its emitter refuses, where the marker names the refusal.
            if (!ep.scopeControleUit())
                scopeFouten.addAll(scopeVolledigheid(ep, model, gescoopt, producenten));
            perPad.put(ep.pad(), new Entrypoint(ep, build.kieBase(), List.of(), defaults,
                    build.dagsoortGlobalen(), build.dekking(), build.beanLoader(), null));
        }
        if (!scopeFouten.isEmpty())
            throw new DienstFout("dienst start niet: onvolledige regel-scope (buiten-scope kenmerk-producent)",
                    scopeFouten);
        if (!markerFouten.isEmpty())
            throw new DienstFout("dienst start niet: uchar draagt een entrypoint niet", markerFouten);

        // 6. parametersets (fase 5, decision B). Load + validate the sidecar against the model, apply the
        //    default×set conflict rule, then compute the regel×parameter period-coverage WARNINGs.
        List<Parameterset> sets = laadParametersets(descriptor, parametersetsJson, model);
        List<String> waarschuwingen = periodeWaarschuwingen(model, sets);

        return new BeslisserKern(descriptor, model, perPad, sets, waarschuwingen);
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
    public static BeslisserKern bouwVanDocument(String descriptorJson, String documentJson, KernConfig config,
                                                ClassLoader loader, String parametersetsJson) {
        com.fasterxml.jackson.databind.JsonNode document;
        try {
            document = DescriptorLoader.MAPPER.readTree(documentJson);
        } catch (IOException e) {
            throw new DienstFout("dienst start niet: het document is geen JSON", List.of(
                    Schending.van(Schending.Soort.MALFORMED, "bron", e.getMessage())));
        }
        IrLoader.Loaded geladen;
        try {
            geladen = IrLoader.loadAll(document);
        } catch (IrLoader.Refusal | IrLoader.Documentfout e) {
            throw new DienstFout("dienst start niet: uchar draagt het document niet", List.of(
                    Schending.van(Schending.Soort.MARKER, "bron", (e instanceof IrLoader.Refusal ? "loader: "
                            : "document: ") + e.getMessage())));
        }
        RegelSpraakModel model = geladen.model();
        DienstDescriptor descriptor = leesDescriptor(descriptorJson);
        VormIndex vormen = VormIndex.van(model, geladen.prioritizedDefaults());
        List<Schending> startFouten = DescriptorLoader.valideer(descriptor, model, vormen);
        if (!startFouten.isEmpty())
            throw new DienstFout("dienst start niet: descriptor/model-validatie faalt", startFouten);

        // one build: every entry point serves the whole document
        DroolsBackend.Build build = null;
        UcharBouw.NietGedragen weigering = null;
        try {
            build = UcharBouw.bouw(document, DroolsBackend.Branch.EXEC_MODEL);
        } catch (UcharBouw.NietGedragen ng) {
            weigering = ng;
        }
        Map<String, Entrypoint> perPad = new LinkedHashMap<>();
        List<Schending> markerFouten = new ArrayList<>();
        List<Schending> scopeFouten = new ArrayList<>();
        for (DienstDescriptor.Entrypoint ep : descriptor.entrypoints()) {
            List<String> ontbreekt = new ArrayList<>(vormen.alle());
            ontbreekt.removeAll(vormen.scope(ep.scope()).vormen());
            if (!ontbreekt.isEmpty()) {
                scopeFouten.add(Schending.van(Schending.Soort.MALFORMED, "entrypoint:" + ep.pad(),
                        "een uchar-ir-v1-document wordt heel gediend, maar de scope van entrypoint '" + ep.naam()
                                + "' draagt niet: " + ontbreekt));
                continue;
            }
            Map<String, Object> defaults = defaultGlobals(descriptor, model);
            if (weigering != null) {
                marker(ep, weigering.construct(), weigering.getMessage(), defaults, config, perPad, markerFouten);
                continue;
            }
            perPad.put(ep.pad(), new Entrypoint(ep, build.kieBase(), List.of(), defaults, build.dagsoortGlobalen(),
                    build.dekking(), build.beanLoader(), null));
        }
        if (!scopeFouten.isEmpty())
            throw new DienstFout("dienst start niet: een scope draagt niet het hele document", scopeFouten);
        if (!markerFouten.isEmpty())
            throw new DienstFout("dienst start niet: uchar draagt een entrypoint niet", markerFouten);
        List<Parameterset> sets = laadParametersets(descriptor, parametersetsJson, model);
        return new BeslisserKern(descriptor, model, perPad, sets, periodeWaarschuwingen(model, sets));
    }

    private static DienstDescriptor leesDescriptor(String descriptorJson) {
        try {
            return DescriptorLoader.MAPPER.readValue(descriptorJson, DienstDescriptor.class);
        } catch (IOException e) {
            throw new DienstFout("descriptor is geen geldige JSON", List.of(
                    Schending.van(Schending.Soort.MALFORMED, "", e.getMessage())));
        }
    }

    /** Record that uchar does not carry entry point {@code ep}: {@code construct} is the construct uchar's refusal
     *  names (null when it names none) and {@code reden} uchar's sentence. Strict, it is a startup violation; with
     *  markers allowed, the entry point is registered without a KieBase and every request to it is refused. */
    private static void marker(DienstDescriptor.Entrypoint ep, String construct, String reden,
                               Map<String, Object> defaults, KernConfig config, Map<String, Entrypoint> perPad,
                               List<Schending> markerFouten) {
        if (config.allowMarkers()) {
            perPad.put(ep.pad(), new Entrypoint(ep, null, construct == null ? List.of() : List.of(construct),
                    defaults, Map.of(), List.of(), null, reden));
            return;
        }
        markerFouten.add(Schending.van(Schending.Soort.MARKER, "entrypoint:" + ep.pad(), construct == null
                ? "entrypoint '" + ep.naam() + "' wordt niet gedragen door uchar: " + reden
                : "regel '" + construct + "' in de scope van entrypoint '" + ep.naam()
                        + "' is een marker (niet gedragen): " + reden));
    }

    /**
     * Load the parameterset sidecar (fase 5, decision B). Returns the empty list when the descriptor names no
     * sidecar. When it DOES:
     * <ul>
     *   <li>{@code parametersetsJson == null} — a named-but-unresolvable sidecar on the String-form path: a
     *       startup {@link DienstFout} (decision A);</li>
     *   <li>{@link ParametersetLader#laad} — every {@link ParametersetFout} becomes a MALFORMED startup schending
     *       (a set that does not validate against the model refuses start);</li>
     *   <li><b>conflict rule (pinned):</b> a parameter present in BOTH {@code descriptor.parameters} (defaults)
     *       AND the sets is a startup ERROR naming the parameter — ambiguous authority, never last-one-wins.</li>
     * </ul>
     */
    private static List<Parameterset> laadParametersets(DienstDescriptor descriptor, String parametersetsJson,
                                                        RegelSpraakModel model) {
        if (descriptor.parametersets() == null || descriptor.parametersets().isBlank()) return List.of();
        if (parametersetsJson == null)
            throw new DienstFout("dienst start niet: parametersets-bestand kan niet gelezen worden op dit bouwpad",
                    List.of(Schending.van(Schending.Soort.MALFORMED, "parametersets",
                            "de descriptor noemt parametersets '" + descriptor.parametersets() + "' maar er is geen"
                                    + " parametersets-JSON meegegeven op dit bouwpad")));
        List<Parameterset> sets;
        try {
            sets = ParametersetLader.laad(parametersetsJson, model);
        } catch (ParametersetFout f) {
            List<Schending> uit = new ArrayList<>();
            for (String fout : f.fouten())
                uit.add(Schending.van(Schending.Soort.MALFORMED, "parametersets", fout));
            if (uit.isEmpty())   // malformed-JSON single-throw carries the message, not the fouten list
                uit.add(Schending.van(Schending.Soort.MALFORMED, "parametersets", f.getMessage()));
            throw new DienstFout("dienst start niet: parametersets valideren niet tegen het model", uit);
        }

        // conflict rule: a parameter that is a descriptor default AND a set value has ambiguous authority.
        List<Schending> conflict = new ArrayList<>();
        if (descriptor.parameters() != null) {
            java.util.Set<String> setNamen = Parameterset.pool(sets).keySet();
            for (String d : descriptor.parameters().keySet()) {
                RegelSpraakModel.Parameter p = DescriptorLoader.parameter(model, d);
                String canoniek = p == null ? d : p.naam();
                if (setNamen.contains(canoniek))
                    conflict.add(Schending.van(Schending.Soort.MALFORMED, "parameters." + d,
                            "parameter '" + canoniek + "' staat ZOWEL in descriptor.parameters (default) ALS in de"
                                    + " parametersets — ambigue autoriteit; kies er één"));
            }
        }
        if (!conflict.isEmpty())
            throw new DienstFout("dienst start niet: parameter staat zowel in de defaults als in de sets", conflict);
        return sets;
    }

    /** The rendered {@link PeriodeDekking} WARNING lines for the regel-geldigheid × parameter-geldigheid gaps
     *  (fase 5, gate G3). {@link PeriodeDekking#controleer} is pure; {@link PeriodeDekking#meldAlle} renders each
     *  finding onto a throw-away model so we can lift the WARNING message text out verbatim (the SAME text the
     *  engine surfaces), without polluting the real service model's diagnostics. Empty when no gap / no sets. */
    private static List<String> periodeWaarschuwingen(RegelSpraakModel model, List<Parameterset> sets) {
        List<PeriodeDekking> bevindingen = PeriodeDekking.controleer(model, sets);
        List<String> uit = new ArrayList<>();
        RegelSpraakModel schaduw = new RegelSpraakModel();
        PeriodeDekking.meldAlle(bevindingen, schaduw);
        for (var d : schaduw.diagnostics)
            if (d.severity() == RegelSpraakModel.Severity.WARNING) uit.add(d.message());
        return uit;
    }

    // ---------------- scope-completeness validation ----------------

    // The producer/consumer analysis — the producer index, the walk over what a form reads, and the ElementSleutel
    // identity — is the SHARED {@link ProducentConsument} core (jar-only, no Drools). This class is one of its two
    // consumers (uchar's rule graph is the other); both read the same walk ({@link ProducentConsument#vormen}), so
    // this scope-completeness gate and the graph never diverge.

    /**
     * The scope-completeness violations for one entry point (decision: fail-fast, opt-out per entrypoint). It reads
     * the forms the entry point's KieBase carries — {@code gescoopt}, the model of its scope — as uchar's rule graph
     * reads them: of a rule the kenmerken/attributen its voorwaarde and value read, of a defaults group those the
     * justification and consequence of every default read, nested exception groups included. A read element is a
     * violation iff (a) some form in {@code producenten} WRITES it, (b) NONE of those producers is in this entry
     * point's scope, and (c) the element is not a declared {@code invoerElementen} (the caller then supplies the
     * value, so the missing producer is intentional). Elements with no producer at all are genuine request inputs,
     * not violations. The violation of a group also names the defaults that read the element, from uchar's walk per
     * default ({@link ProducentConsument#lezingen}), of which the group's reads are the union.
     */
    private static List<Schending> scopeVolledigheid(DienstDescriptor.Entrypoint ep, RegelSpraakModel model,
            RegelSpraakModel gescoopt, Map<ElementSleutel, java.util.Set<String>> producenten) {
        // a resolver's model: a navigated kenmerk check reads the kenmerk of the type its role reaches
        ProducentConsument.Navigatie navigatie = ProducentConsument.bronNavigatie(gescoopt);
        List<ProducentConsument.Vorm> gedragen = ProducentConsument.vormen(gescoopt, gescoopt.prioritizedDefaults,
                navigatie);
        Map<String, PrioritizedDefaults> groepen = new java.util.HashMap<>();
        for (PrioritizedDefaults p : gescoopt.prioritizedDefaults)
            if (p.naam() != null) groepen.put(ProducentConsument.basisNaam(p.naam()), p);
        // the writers in the scope, under the names the producer index keys them on
        java.util.Set<String> scope = new java.util.LinkedHashSet<>();
        for (ProducentConsument.Vorm v : gedragen) scope.add(v.naam());
        // The opt-out keys through the SAME element identity the consumption does ({@link
        // ProducentConsument#elementVan}), so the declaration binds on the member's declarant: a caller that
        // declares the element on the type that DECLARES it silences a consumption on any subtype, and one that
        // names a subtype silences it too — the alternative is an escape hatch that exists but cannot be spelled.
        java.util.Set<ElementSleutel> invoerElementen = new java.util.LinkedHashSet<>();
        for (String sel : ep.invoerElementenOfLeeg()) {
            Uitvoerselector u = Uitvoerselector.ontleed(sel);
            if (u == null) continue;
            RegelSpraakModel.Objecttype houder = DescriptorLoader.objecttype(model, u.objecttype());
            invoerElementen.add(houder == null ? ElementSleutel.van(u.objecttype(), u.lid(), u.kenmerk())
                    : ProducentConsument.elementVan(houder, u.lid(), u.kenmerk()));
        }
        // A tree of exceptions is lowered to one group named after its base rule, an unordered block without a
        // carrier to one group per member; the opgave marker and a subtype's own exceptions get a group whose name
        // uchar makes up. The author knows the first two by a rule they wrote, the last two only by that name.
        java.util.Set<String> geschreven = new java.util.HashSet<>();
        for (var r : model.regels) if (r.naam() != null) geschreven.add(CaseEmitter.norm(r.naam()));

        List<Schending> uit = new ArrayList<>();
        java.util.Set<ElementSleutel> gemeld = new java.util.LinkedHashSet<>();
        for (ProducentConsument.Vorm v : gedragen) {
            String lezer = switch (v.soort()) {
                case REGEL -> "regel '" + v.naam() + "'";
                case DEFAULTSGROEP -> geschreven.contains(CaseEmitter.norm(v.naam()))
                        ? "defaults-groep van regel '" + v.naam() + "'"
                        : "gegenereerde defaults-groep '" + v.naam() + "'";
                // The other forms read as well, but are not held to it: five entry points of
                // examples/toka/toka.dienst.json scope an allocation without the writers of what it reads.
                case OBJECTCREATIE, VERDELING, CONSISTENTIE, UNICITEIT, FEITCREATIE -> null;
            };
            if (lezer == null) continue;
            List<ProducentConsument.DefaultLezing> lezingen = null;
            for (ElementSleutel consumed : v.leest()) {
                java.util.Set<String> writers = producenten.get(consumed);
                if (writers == null || writers.isEmpty()) continue;          // a genuine request input
                if (writers.stream().anyMatch(scope::contains)) continue;    // produced in scope: fine
                if (invoerElementen.contains(consumed)) continue;            // element-level caller input: opt-out
                if (!gemeld.add(consumed)) continue;
                String waar = "";
                if (v.soort() == ProducentConsument.Vormsoort.DEFAULTSGROEP) {
                    PrioritizedDefaults groep = java.util.Objects.requireNonNull(groepen.get(v.naam()),
                            () -> "geen defaultsgroep '" + v.naam() + "' in de scope");
                    if (lezingen == null) lezingen = ProducentConsument.lezingen(gescoopt, groep, navigatie);
                    waar = " " + waarGelezen(lezingen, consumed, v.naam());
                }
                uit.add(Schending.van(Schending.Soort.SCOPE_ONVOLLEDIG, "entrypoint:" + ep.pad(),
                        lezer + " in entrypoint '" + ep.naam() + "' leest "
                                + (consumed.kenmerk() ? "kenmerk" : "attribuut") + " '" + consumed.naam() + "'"
                                + (consumed.objecttype().isEmpty() ? "" : " van '" + consumed.objecttype() + "'")
                                + waar + ", maar de enige producent(en) " + writers + " vallen buiten de scope"
                                + " en het element is niet als invoerElement gedeclareerd"));
            }
        }
        return uit;
    }

    /** Where a defaults group reads {@code element}: every default that reads it, by the part that reads (its
     *  justification or its consequence) with that part's source text, and by its path in the group. */
    private static String waarGelezen(List<ProducentConsument.DefaultLezing> lezingen, ElementSleutel element,
                                      String groep) {
        List<String> plekken = new ArrayList<>();
        for (ProducentConsument.DefaultLezing d : lezingen) {
            var dflt = d.dflt();
            if (d.rechtvaardiging().contains(element))
                plekken.add(plek("rechtvaardiging", dflt.voorwaarde() == null ? null : dflt.voorwaarde().bron(), d));
            if (d.consequentie().contains(element))
                plekken.add(plek("consequentie", dflt.consequentie() == null ? null : dflt.consequentie().bron(), d));
        }
        // uchar takes a group's reads from these, so an element no default reads is a broken promise of uchar's
        if (plekken.isEmpty())
            throw new IllegalStateException("uchars wandeling per default van defaults-groep '" + groep
                    + "' leest '" + element.naam() + "' niet, maar die van de groep wel");
        return String.join(" en ", plekken);
    }

    /** One reading default: the part that reads, its source text as the author wrote it (or else the default's own
     *  source, which the opgave marker fills with the attribute it reads), and the default's path in the group. */
    private static String plek(String deel, Bron bron, ProducentConsument.DefaultLezing d) {
        String tekst = bron != null && bron.snippet() != null && !bron.snippet().isBlank()
                ? bron.snippet() : d.dflt().bron();
        return "in de " + deel + (tekst == null ? "" : " '" + tekst + "'") + " van default " + d.pad();
    }

    /** The descriptor parameter defaults, decoded per-parameter datatype to (globalNaam -> value). */
    private static Map<String, Object> defaultGlobals(DienstDescriptor descriptor, RegelSpraakModel model) {
        Map<String, Object> globals = new LinkedHashMap<>();
        if (descriptor.parameters() == null) return globals;
        List<Schending> fouten = new ArrayList<>();
        for (var e : descriptor.parameters().entrySet()) {
            RegelSpraakModel.Parameter p = DescriptorLoader.parameter(model, e.getKey());
            if (p == null) continue;   // already reported by descriptor validation
            WaardeCoder.Resultaat r = WaardeCoder.decodeer(e.getValue(), p.datatype(), "parameters." + e.getKey());
            fouten.addAll(r.schendingen());
            if (r.schendingen().isEmpty()) globals.put(VerzoekMapper.globalNaam(p.naam()), r.waarde());
        }
        if (!fouten.isEmpty()) throw new DienstFout("descriptor parameter-default ongeldig", fouten);
        return globals;
    }

    // ---------------- request handling ----------------

    /** Handle a request against the entry point at {@code pad}: map → fire → build response. Existing 2-arg
     *  contract (no uitleg); delegates with {@link VerzoekOpties#STANDAARD}, byte-identical for every current caller. */
    public ObjectNode verwerk(String pad, JsonNode verzoekBoom) {
        return verwerk(pad, verzoekBoom, VerzoekOpties.STANDAARD);
    }

    /** As {@link #verwerk(String, JsonNode, VerzoekOpties)} with only the explanation asked for or not. */
    public ObjectNode verwerk(String pad, JsonNode verzoekBoom, boolean uitleg) {
        return verwerk(pad, verzoekBoom, new VerzoekOpties(uitleg, null, null));
    }

    /**
     * Handle a request with what it asks beside its body. When {@code uitleg} is asked, an {@code "uitleg"} array is
     * added to the response — one NL sentence per assigned/withheld kenmerk of a request instance, justified with the
     * evaluated operandwaarden (§norm read via the IR, not the programmer); without it the response tree has no such
     * key. The request's names are read by a {@link NaamLezer}, liberally or in the input vocabulary the request
     * pins; the answer and its explanation name the declarations in the vocabulary the request chooses, else in the
     * entry point's default from the descriptor, else canonically ({@link Naamgeving}). A vocabulary the model does
     * not declare is refused before the body is read: an answer in it could not be given.
     */
    public ObjectNode verwerk(String pad, JsonNode verzoekBoom, VerzoekOpties opties) {
        Entrypoint ep = perPad.get(pad);
        if (ep == null) throw new OnbekendPad(pad);
        if (!ep.uitvoerbaar()) throw new NietUitvoerbaar(ep);
        List<Schending> keuzes = new ArrayList<>();
        Naamgeving uitvoer = naamgeving(opties.vocabulaire() != null ? opties.vocabulaire()
                : ep.descriptor().vocabulaire(), "vocabulaire", keuzes);
        if (opties.invoervocabulaire() != null) naamgeving(opties.invoervocabulaire(), "invoervocabulaire", keuzes);
        if (!keuzes.isEmpty()) throw new DienstFout("verzoek valideert niet", keuzes);
        VerzoekMapper mapper = new VerzoekMapper(model, ep.beanLoader(), new NaamLezer(model, opties.invoervocabulaire()));
        VerzoekMapper.Resultaat mr = mapper.map(new Verzoek(verzoekBoom));
        if (!mr.schendingen().isEmpty())
            throw new DienstFout("verzoek valideert niet", mr.schendingen());
        // core contract check (fase 6, G4): the entrypoint's invoer list is the DECLARED contract — every
        // objecttype it names MUST have at least one instantie in the request. A mapping/adapter rejection here
        // happens strictly BEFORE Uitvoerder.vuur, so the resulting 4xx is itself the no-fire witness: an absent
        // required instance can never fire meaningfully (the scoped rules quantify over instances of that
        // objecttype, so a missing invoer objecttype means the rule has nothing to fire against). Collected first,
        // then thrown together as one 400.
        List<Schending> ontbrekend = ontbrekendeInvoer(ep, mr.typePerLabel(), model);
        if (!ontbrekend.isEmpty())
            throw new DienstFout("verzoek valideert niet", ontbrekend);
        // fase 5, decision D: resolve the parameterset vintage against the one date authority (the request
        // peildatum, which IS Wereld.rekendatum). The resolved values sit BETWEEN the descriptor defaults and the
        // request parameter overrides (descriptor default first, set-value next — the conflict rule guarantees
        // they never name the same parameter — request override last: explicit caller-intent wins).
        Map<String, Object> setGlobals = vintageGlobals(mr.wereld().rekendatum());
        Uitvoerder uitvoerder = new Uitvoerder(ep.kieBase(), ep.defaultGlobals(), ep.dagsoortGlobalen(),
                ep.beanLoader());
        Uitvoerder.Uitkomst uitkomst = uitvoerder.vuur(mr.wereld(), setGlobals);
        ObjectNode antwoord = new AntwoordBouwer(model, uitvoer).bouw(uitkomst, ep.descriptor().uitvoer(),
                mr.typePerLabel());
        if (opties.uitleg()) voegUitlegToe(antwoord, uitkomst, mr, setGlobals, ep, uitvoer);
        return antwoord;
    }

    /** The naming {@code keuze} names, or the canonical one after adding the violation of a vocabulary the model does
     *  not declare to {@code fouten}, on the query parameter {@code veld}. */
    private Naamgeving naamgeving(String keuze, String veld, List<Schending> fouten) {
        try {
            return Naamgeving.van(model, keuze);
        } catch (Naamgeving.Vocabulairefout f) {
            fouten.add(Schending.van(Schending.Soort.ONBEKEND_VOCABULAIRE, veld, f.getMessage()));
            return Naamgeving.canoniek(model);
        }
    }

    /**
     * Attach the {@link Verbalisator} verhaal to {@code antwoord} as an {@code "uitleg"} array. The globals the
     * verbalisator re-evaluates condition operands against are rebuilt with the SAME precedence
     * {@link Uitvoerder#vuur} fired them under (descriptor defaults → set-vintage → request overrides → rekendatum);
     * the keys are {@link VerzoekMapper#globalNaam}, which the verbalisator reads a {@code ParameterRef} on. A pure
     * read of the already-fired beans — nothing re-fires.
     */
    private void voegUitlegToe(ObjectNode antwoord, Uitvoerder.Uitkomst uitkomst, VerzoekMapper.Resultaat mr,
                               Map<String, Object> setGlobals, Entrypoint ep, Naamgeving naamgeving) {
        Map<String, Object> globals = new LinkedHashMap<>(ep.defaultGlobals());
        globals.putAll(setGlobals);
        globals.putAll(mr.wereld().globals());
        if (mr.wereld().rekendatum() != null) globals.put("rekendatum", mr.wereld().rekendatum());
        List<String> zinnen = new Verbalisator(model, globals, naamgeving)
                .verbaliseer(uitkomst.beans(), mr.typePerLabel());
        var arr = antwoord.putArray("uitleg");
        for (String zin : zinnen) arr.add(zin);
    }

    /**
     * Resolve the parameterset vintage for one request (fase 5, decision D). Returns the (global-name → value) map
     * of set-resolved parameters, empty when no sets are loaded. Fail-fast against the one date authority
     * ({@code datum} = the request peildatum = {@code Wereld.rekendatum}):
     * <ul>
     *   <li><b>datum present</b> — {@link Parameterset#waardenOp}; any gap is a 4xx {@link DienstFout} whose
     *       schendingen name the parameter + the datum, on veld {@code "peildatum"};</li>
     *   <li><b>datum absent + multi-vintage</b> — a 4xx naming veld {@code "peildatum"}: the peildatum is
     *       mandatory because the sets carry more than one vintage (ambiguous truth);</li>
     *   <li><b>datum absent + NOT multi-vintage</b> — the degenerate single-value case: resolve with
     *       {@link LocalDate#EPOCH} (date-independent by construction, mirroring the ScenarioRunner).</li>
     * </ul>
     * The resolved values are keyed by the engine's camelCase global name ({@link VerzoekMapper#globalNaam}),
     * exactly as the descriptor defaults are.
     */
    private Map<String, Object> vintageGlobals(LocalDate datum) {
        if (parametersets.isEmpty()) return Map.of();
        if (datum == null && Parameterset.meerVintage(parametersets))
            throw new DienstFout("verzoek valideert niet", List.of(Schending.van(Schending.Soort.VERKEERD_TYPE,
                    "peildatum", "peildatum is verplicht: de geladen parametersets dragen meer dan één vintage")));
        LocalDate peil = datum != null ? datum : LocalDate.EPOCH;   // degeneraat: datum-onafhankelijk
        List<String> gaten = new ArrayList<>();
        Map<String, Object> opgelost = Parameterset.waardenOp(parametersets, peil, gaten);
        if (!gaten.isEmpty()) {
            List<Schending> fouten = new ArrayList<>();
            for (String gat : gaten)
                fouten.add(Schending.van(Schending.Soort.VERKEERD_TYPE, "peildatum", gat));
            throw new DienstFout("verzoek valideert niet", fouten);
        }
        Map<String, Object> globals = new LinkedHashMap<>();
        for (var e : opgelost.entrySet())
            globals.put(VerzoekMapper.globalNaam(e.getKey()), e.getValue());
        return globals;
    }

    /**
     * The invoer contract check: for every objecttype the entrypoint declares in {@code invoer}, require at
     * least ONE mapped instance that IS one of that objecttype in the request. A missing one yields a
     * {@link Schending.Soort#ONTBREKENDE_INSTANTIE} with veld {@code "instanties"} whose message names BOTH the
     * objecttype AND the entrypoint.
     *
     * <p>Membership is the ch. 2.2 ⊑ ({@link DescriptorLoader#objectMatch}), not name equality: the scoped
     * rules quantify over a pattern on the declared objecttype, which the generated beans {@code extends}, so a
     * subtype instance DOES make those rules fire — refusing it as "no instance of the invoer objecttype" would
     * reject a request the engine can serve. Case-insensitive and, on a model without subtypes, identical to
     * the previous name equality.
     */
    private static List<Schending> ontbrekendeInvoer(Entrypoint ep,
            Map<String, RegelSpraakModel.Objecttype> typePerLabel, RegelSpraakModel model) {
        List<Schending> uit = new ArrayList<>();
        List<String> invoer = ep.descriptor().invoer();
        if (invoer == null) return uit;
        for (String vereist : invoer) {
            boolean aanwezig = false;
            for (RegelSpraakModel.Objecttype ot : typePerLabel.values())
                if (DescriptorLoader.objectMatch(model, ot, vereist)) { aanwezig = true; break; }
            if (!aanwezig)
                uit.add(Schending.van(Schending.Soort.ONTBREKENDE_INSTANTIE, "instanties",
                        "entrypoint '" + ep.descriptor().naam() + "' vereist minstens één instantie van objecttype '"
                                + vereist + "', maar het verzoek levert er geen"));
        }
        return uit;
    }

    // ---------------- accessors (fase-3 /info) ----------------

    public DienstDescriptor descriptor() { return descriptor; }
    public RegelSpraakModel model() { return model; }
    public Map<String, Entrypoint> entrypoints() { return perPad; }
    public Entrypoint entrypoint(String pad) { return perPad.get(pad); }

    /** The loaded validity-versioned parameter sets (fase 5), or the empty list when the descriptor names none. */
    public List<Parameterset> parametersets() { return parametersets; }

    /** The rendered regel-geldigheid × parameter-geldigheid WARNING lines (gate G3), served on /info and logged
     *  by {@link dev.uchar.regelspraak.toolchain.service.http.DienstMain} at startup. Empty when no gap / no sets. */
    public List<String> periodeWaarschuwingen() { return periodeWaarschuwingen; }

    /** Thrown when a request targets a path no entry point serves. */
    public static final class OnbekendPad extends RuntimeException {
        public OnbekendPad(String pad) { super("onbekend pad: '" + pad + "'"); }
    }

    /** Thrown when a request targets an entry point uchar does not carry, started with
     *  {@link KernConfig#allowMarkers()}: uchar built no KieBase for it, so no answer it could give is complete.
     *  {@link #reden()} is uchar's sentence, {@link #markers()} the construct it names, if any. */
    public static final class NietUitvoerbaar extends RuntimeException {
        private final transient Entrypoint ep;

        public NietUitvoerbaar(Entrypoint ep) {
            super("entrypoint '" + ep.descriptor().naam() + "' is niet uitvoerbaar: " + ep.weigering());
            this.ep = ep;
        }

        public String reden() { return ep.weigering(); }
        public List<String> markers() { return ep.markers(); }
    }
}
