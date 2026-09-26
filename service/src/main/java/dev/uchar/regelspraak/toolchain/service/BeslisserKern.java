package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
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
 *   <li><b>Build the regelnaam→bronbestand index</b> ({@link RegelBestandIndex}), so an entry point is built
 *       over PRELUDE (the rule-less files) + only its scoped rule files — one {@link KieBase} per entry point, so
 *       upstream rules cannot overwrite request-given intermediate inputs.</li>
 *   <li><b>Build each entry point through uchar</b> ({@link UcharBouw}): its source is resolved, emitted as a
 *       {@code uchar-ir-v1} document and built by uchar's backend on the exec-model branch, which generates the
 *       fact beans into a class loader of the entry point's own, checks the bean contract, and lowers every
 *       construct with every side table the loader returns. A refusal is a startup error naming the entry
 *       point.</li>
 *   <li><b>Fail-fast on markers</b> — a construct the exec-model branch does not lower refuses the build of the
 *       entry point whose source carries it. With {@link KernConfig#allowMarkers()} false the startup refuses
 *       naming the construct; with true it starts, lists the construct under the entry point's markers, and
 *       refuses every request to that entry point ({@link NietUitvoerbaar}), since uchar hands out no KieBase
 *       that lacks a construct.</li>
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
     *  globals (empty on the exec-model branch, which wires them into its rules), the in-scope {@link Dekking}
     *  manifest, the class loader of its generated fact beans, and when uchar refused its build, the refusal.
     *
     *  <p>{@code dekking} carries the verdict of every lowered construct whose base name is in this entry point's
     *  scope ({@link #dekkingInScope}); {@code /info} reports it verbatim. A §13.4.7 feitcreatie scope has NO
     *  dekking entry at all, so this list is legitimately empty there and /info shows the empty list as-is.
     *
     *  <p>An entry point with a marker, started with {@link KernConfig#allowMarkers()}, has no KieBase and no bean
     *  class loader: {@code markers} names the construct, {@code weigering} carries uchar's sentence, and a request
     *  to it is refused with {@link NietUitvoerbaar}. */
    public record Entrypoint(DienstDescriptor.Entrypoint descriptor, KieBase kieBase,
                             List<String> markers, Map<String, Object> defaultGlobals,
                             Map<String, java.util.function.Predicate<java.time.LocalDate>> dagsoortGlobalen,
                             List<Dekking> dekking, ClassLoader beanLoader, String weigering) {
        /** Whether a request to this entry point can fire: uchar built its KieBase. */
        public boolean uitvoerbaar() { return kieBase != null; }
    }

    // ---------------- startup ----------------

    /** Build the core from a descriptor file and the service's {@code .rgs} source files (in order). The
     *  {@code loader} is the parent for the bean classloader; the generated beans are served from a child
     *  loader created here, not looked up on {@code loader}.
     *
     *  <p><b>Parametersets (fase 5, decision A).</b> When the descriptor names a {@code parametersets} sidecar
     *  file, it is read RELATIVE to {@code descriptorBestand} ({@code descriptorBestand.getParent().resolve(naam)})
     *  and handed to the String-form as {@code parametersetsJson}. A named-but-unreadable sidecar refuses startup
     *  with a {@link DienstFout} naming the bouw-pad (never a silent skip). */
    public static BeslisserKern bouw(Path descriptorBestand, List<Path> bronBestanden, KernConfig config,
                                     ClassLoader loader) throws IOException {
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
        return bouw(json, tekst, config, loader, parametersetsJson);
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
        DienstDescriptor descriptor;
        try {
            descriptor = DescriptorLoader.MAPPER.readValue(descriptorJson, DienstDescriptor.class);
        } catch (IOException e) {
            throw new DienstFout("descriptor is geen geldige JSON", List.of(
                    Schending.van(Schending.Soort.MALFORMED, "", e.getMessage())));
        }
        // only validate the descriptor against a well-formed model — a resolve failure already dominates
        if (startFouten.isEmpty()) startFouten.addAll(DescriptorLoader.valideer(descriptor, model));
        if (!startFouten.isEmpty())
            throw new DienstFout("dienst start niet: descriptor/model-validatie faalt", startFouten);

        // 3. rule -> file index; prelude = files declaring no rules
        RegelBestandIndex index = RegelBestandIndex.bouw(bronPerBestand);

        // 4. per entry point: resolve prelude + scoped rule files and build them through uchar, which generates
        //    the beans, checks their contract and lowers every construct. HARD failures (a scope file missing, a
        //    scope source that does not resolve, a form uchar does not carry) are startup errors REGARDLESS of
        //    allowMarkers — that flag only tolerates honest markers; it must never mask a broken entrypoint.
        Map<String, Entrypoint> perPad = new LinkedHashMap<>();
        List<Schending> markerFouten = new ArrayList<>();
        List<Schending> hardeFouten = new ArrayList<>();
        List<Schending> scopeFouten = new ArrayList<>();
        // the producer index is model-wide (every form that WRITES a kenmerk/attribuut), computed once.
        Map<ElementSleutel, java.util.Set<String>> producenten =
                ProducentConsument.producentIndex(model, model.prioritizedDefaults);
        for (DienstDescriptor.Entrypoint ep : descriptor.entrypoints()) {
            if (!ep.scopeControleUit())
                scopeFouten.addAll(scopeVolledigheid(ep, model, producenten));
            List<String> bestanden = scopeBestanden(ep, index, model, hardeFouten);
            String bron = concat(bronPerBestand, index.preludeBestanden(), bestanden);
            RegelSpraakModel scopeModel = Resolver.resolve(bron);
            if (!scopeModel.errors().isEmpty()) {
                for (var d : scopeModel.errors())
                    hardeFouten.add(Schending.van(Schending.Soort.MALFORMED, "entrypoint:" + ep.pad(),
                            "scope-bron van '" + ep.naam() + "' resolvet niet: regel " + d.line() + " " + d.message()));
                continue;
            }
            Map<String, Object> defaults = defaultGlobals(descriptor, model);
            DroolsBackend.Build build;
            try {
                build = UcharBouw.bouw(scopeModel, DroolsBackend.Branch.EXEC_MODEL);
            } catch (UcharBouw.NietGedragen ng) {
                if (ng.construct() == null) {
                    hardeFouten.add(Schending.van(Schending.Soort.MALFORMED, "entrypoint:" + ep.pad(),
                            "entrypoint '" + ep.naam() + "' is niet uitvoerbaar in uchar: " + ng.getMessage()));
                    continue;
                }
                // A marker: the construct is not lowered, and uchar builds no KieBase without it.
                if (!config.allowMarkers())
                    markerFouten.add(Schending.van(Schending.Soort.MARKER, "entrypoint:" + ep.pad(),
                            "regel '" + ng.construct() + "' in de bron van entrypoint '" + ep.naam()
                                    + "' is een marker (niet-gelowerd): " + ng.getMessage()));
                else
                    perPad.put(ep.pad(), new Entrypoint(ep, null, List.of(ng.construct()), defaults, Map.of(),
                            List.of(), null, ng.getMessage()));
                continue;
            }
            perPad.put(ep.pad(), new Entrypoint(ep, build.kieBase(), List.of(), defaults,
                    build.dagsoortGlobalen(), dekkingInScope(ep, build.dekking(), model), build.beanLoader(), null));
        }
        if (!hardeFouten.isEmpty())
            throw new DienstFout("dienst start niet: entrypoint(s) niet bouwbaar", hardeFouten);
        if (!scopeFouten.isEmpty())
            throw new DienstFout("dienst start niet: onvolledige regel-scope (buiten-scope kenmerk-producent)",
                    scopeFouten);
        if (!markerFouten.isEmpty() && !config.allowMarkers())
            throw new DienstFout("dienst start niet: marker-regels in scope", markerFouten);

        // 6. parametersets (fase 5, decision B). Load + validate the sidecar against the model, apply the
        //    default×set conflict rule, then compute the regel×parameter period-coverage WARNINGs.
        List<Parameterset> sets = laadParametersets(descriptor, parametersetsJson, model);
        List<String> waarschuwingen = periodeWaarschuwingen(model, sets);

        return new BeslisserKern(descriptor, model, perPad, sets, waarschuwingen);
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

    /** The rule FILES an entry point's scope needs, from its scoped rule names (via the index). */
    private static List<String> scopeBestanden(DienstDescriptor.Entrypoint ep, RegelBestandIndex index,
                                               RegelSpraakModel model, List<Schending> fouten) {
        List<String> namen = new ArrayList<>(ep.scope().regelnamenOfLeeg());
        for (String groep : ep.scope().regelgroepenOfLeeg())
            for (var g : model.regelGroepen) if (g.naam().equals(groep)) namen.addAll(g.leden());
        List<String> bestanden = new ArrayList<>();
        for (String n : namen) {
            String f = index.bestandVoorRegel(n);
            if (f == null)
                fouten.add(Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING, "entrypoint:" + ep.pad(),
                        "geen bronbestand declareert regel '" + n + "'"));
            else if (!bestanden.contains(f)) bestanden.add(f);
        }
        return bestanden;
    }

    private static String concat(Map<String, String> bron, List<String> prelude, List<String> ruleBestanden) {
        StringBuilder b = new StringBuilder();
        for (String p : prelude) b.append(bron.get(p)).append('\n');
        for (String r : ruleBestanden) if (!prelude.contains(r)) b.append(bron.get(r)).append('\n');
        return b.toString();
    }

    /** The set of in-scope base-names for an entry point: its scoped rule names plus every member of its scoped
     *  regelgroepen, {@link #basisNaam}-normalised. Shared by the marker/lowered scope filters. */
    private static java.util.Set<String> scopeBasisNamen(DienstDescriptor.Entrypoint ep, RegelSpraakModel model) {
        java.util.Set<String> scope = new java.util.LinkedHashSet<>();
        for (String n : ep.scope().regelnamenOfLeeg()) scope.add(basisNaam(n));
        for (String groep : ep.scope().regelgroepenOfLeeg())
            for (var g : model.regelGroepen) if (g.naam().equals(groep))
                for (String lid : g.leden()) scope.add(basisNaam(lid));
        return scope;
    }

    /** The {@link Dekking} manifest of an entry point's build restricted to its scope: the verdict of every
     *  construct whose base name is in the scope ({@link #scopeBasisNamen}). A feitcreatie scope yields ZERO
     *  dekking entries and therefore an empty manifest here — reported as-is. */
    private static List<Dekking> dekkingInScope(DienstDescriptor.Entrypoint ep, List<Dekking> dekking,
                                                RegelSpraakModel model) {
        java.util.Set<String> scope = scopeBasisNamen(ep, model);
        List<Dekking> uit = new ArrayList<>();
        for (Dekking d : dekking)
            if (scope.contains(basisNaam(d.naam()))) uit.add(d);
        return uit;
    }

    // ---------------- scope-completeness validation ----------------

    // The producer/consumer analysis — the producer index, the consumed-element walk, and the ElementSleutel
    // identity — is the SHARED {@link ProducentConsument} core (jar-only, no Drools). This class is one of its
    // two consumers (the regelgraaf builder is the other); both check producer against consumer over the same
    // IR-derived facts, so this scope-completeness gate and the graph never diverge.

    /**
     * The scope-completeness violations for one entry point (decision: fail-fast, opt-out per entrypoint). For
     * every scoped {@link dev.uchar.regelspraak.ir.ResolvedRegel} it collects the kenmerken/attributen the rule
     * CONSUMES (its voorwaarde predicates + operands, and its RHS {@code waarde}); a consumed element is a
     * violation iff (a) some rule in {@code producenten} WRITES it, (b) NONE of those producers is in this
     * entry point's scope, and (c) the element is not a declared {@code invoerElementen} (the caller then
     * supplies the value, so the missing producer is intentional). Elements with no producer at all are genuine
     * request inputs, not violations.
     */
    private static List<Schending> scopeVolledigheid(DienstDescriptor.Entrypoint ep, RegelSpraakModel model,
            Map<ElementSleutel, java.util.Set<String>> producenten) {
        java.util.Set<String> scope = scopeBasisNamen(ep, model);
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

        List<Schending> uit = new ArrayList<>();
        java.util.Set<ElementSleutel> gemeld = new java.util.LinkedHashSet<>();
        // a resolver's model: a navigated kenmerk check reads the kenmerk of the type its role reaches
        ProducentConsument.Navigatie navigatie = ProducentConsument.bronNavigatie(model);
        for (var r : model.resolvedRegels) {
            if (!scope.contains(basisNaam(r.naam()))) continue;
            for (ElementSleutel consumed : ProducentConsument.verbruikt(r, navigatie)) {
                java.util.Set<String> writers = producenten.get(consumed);
                if (writers == null || writers.isEmpty()) continue;          // a genuine request input
                if (writers.stream().anyMatch(scope::contains)) continue;    // produced in scope: fine
                if (invoerElementen.contains(consumed)) continue;            // element-level caller input: opt-out
                if (!gemeld.add(consumed)) continue;
                uit.add(Schending.van(Schending.Soort.SCOPE_ONVOLLEDIG, "entrypoint:" + ep.pad(),
                        "regel '" + basisNaam(r.naam()) + "' in entrypoint '" + ep.naam() + "' leest "
                                + (consumed.kenmerk() ? "kenmerk" : "attribuut") + " '" + consumed.naam() + "'"
                                + (consumed.objecttype().isEmpty() ? "" : " van '" + consumed.objecttype() + "'")
                                + ", maar de enige producent(en) " + writers + " vallen buiten de scope"
                                + " en het element is niet als invoerElement gedeclareerd"));
            }
        }
        return uit;
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
     *  contract (no uitleg); delegates with {@code uitleg=false}, byte-identical for every current caller. */
    public ObjectNode verwerk(String pad, JsonNode verzoekBoom) {
        return verwerk(pad, verzoekBoom, false);
    }

    /**
     * As {@link #verwerk(String, JsonNode)} but OPTIONALLY attaching the {@link Verbalisator} verhaal. When
     * {@code uitleg} is true, an {@code "uitleg"} array is added to the response — one NL sentence per assigned/
     * withheld kenmerk of a request instance, justified with the evaluated operandwaarden (§norm read via the
     * IR, not the programmer). This is DESCRIPTOR-INDEPENDENT and ADDITIVE: with {@code uitleg=false} the
     * response tree is byte-identical to the pre-verbalisator shape, so no existing caller's contract breaks.
     */
    public ObjectNode verwerk(String pad, JsonNode verzoekBoom, boolean uitleg) {
        Entrypoint ep = perPad.get(pad);
        if (ep == null) throw new OnbekendPad(pad);
        if (!ep.uitvoerbaar()) throw new NietUitvoerbaar(ep);
        VerzoekMapper mapper = new VerzoekMapper(model, ep.beanLoader());
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
        ObjectNode antwoord = new AntwoordBouwer(model).bouw(uitkomst, ep.descriptor().uitvoer(), mr.typePerLabel());
        if (uitleg) voegUitlegToe(antwoord, uitkomst, mr, setGlobals, ep);
        return antwoord;
    }

    /**
     * Attach the {@link Verbalisator} verhaal to {@code antwoord} as an {@code "uitleg"} array. The globals the
     * verbalisator re-evaluates condition operands against are rebuilt with the SAME precedence
     * {@link Uitvoerder#vuur} fired them under (descriptor defaults → set-vintage → request overrides → rekendatum);
     * the keys are {@link VerzoekMapper#globalNaam}, which the verbalisator reads a {@code ParameterRef} on. A pure
     * read of the already-fired beans — nothing re-fires.
     */
    private void voegUitlegToe(ObjectNode antwoord, Uitvoerder.Uitkomst uitkomst, VerzoekMapper.Resultaat mr,
                               Map<String, Object> setGlobals, Entrypoint ep) {
        Map<String, Object> globals = new LinkedHashMap<>(ep.defaultGlobals());
        globals.putAll(setGlobals);
        globals.putAll(mr.wereld().globals());
        if (mr.wereld().rekendatum() != null) globals.put("rekendatum", mr.wereld().rekendatum());
        List<String> zinnen = new Verbalisator(model, globals).verbaliseer(uitkomst.beans(), mr.typePerLabel());
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

    /** Thrown when a request targets an entry point that started with a marker ({@link KernConfig#allowMarkers()}):
     *  uchar built no KieBase for it, so no answer it could give is complete. */
    public static final class NietUitvoerbaar extends RuntimeException {
        public NietUitvoerbaar(Entrypoint ep) {
            super("entrypoint '" + ep.descriptor().naam() + "' is niet uitvoerbaar: " + ep.weigering());
        }
    }

    /** Strip a §9.10 "<groep>: " prefix and a §13.4.2.3 " #k" version suffix (and a beslistabel " (n)"
     *  multi-conclusie suffix) off an emitted rule name — the base name the index, the scope, and the
     *  transpiler dekking all agree on. Mirrors ScenarioRunner.basisNaam + RegelBestandIndex.basisNaam. */
    static String basisNaam(String naam) {
        if (naam == null) return "";
        String n = naam;
        int hash = n.indexOf(" #");
        if (hash > 0) n = n.substring(0, hash);
        int colon = n.indexOf(": ");
        if (colon > 0) n = n.substring(colon + 2);
        n = n.replaceFirst("\\s*\\(\\d+\\)\\s*$", "");
        return n.trim();
    }
}
