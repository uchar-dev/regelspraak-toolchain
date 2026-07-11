package io.github.yhavinga.regelspraak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.yhavinga.regelspraak.engine.BeanCompiler;
import io.github.yhavinga.regelspraak.engine.ParametersetLader;
import io.github.yhavinga.regelspraak.engine.Verbalisator;
import io.github.yhavinga.regelspraak.engine.ParametersetLader.ParametersetFout;
import io.github.yhavinga.regelspraak.graaf.ProducentConsument;
import io.github.yhavinga.regelspraak.graaf.ProducentConsument.ElementSleutel;
import io.github.yhavinga.regelspraak.model.Parameterset;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.ContractValidator;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.kern.Dekking;
import io.github.yhavinga.regelspraak.transpiler.kern.PeriodeDekking;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

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
 * The framework-agnostic decision-service CORE (fase 2). Constructed from a descriptor and the ordered list of
 * {@code .rgs} source files that make up the service (manifest-style), it performs, at startup, exactly the
 * pinned steps, mirroring {@link io.github.yhavinga.regelspraak.engine.ScenarioRunner#run} (its L79-107):
 *
 * <ol>
 *   <li><b>Resolve the FULL concatenated source once</b> — one shared, validated {@link RegelSpraakModel}; the
 *       descriptor is validated against it (unknown regel / regelgroep / objecttype / attribuut / kenmerk /
 *       parameter = a startup error naming the value, ALL collected first). A file set whose union does not
 *       resolve refuses startup with the resolver diagnostics.</li>
 *   <li><b>Compile the fact beans ONCE</b> — {@link FactModelGenerator#genereer} + {@link BeanCompiler#compileer}
 *       yield ONE child bean classloader for the whole service, and {@link ContractValidator#valideer} over it
 *       is a startup gate (any contract violation refuses startup).</li>
 *   <li><b>Build the regelnaam→bronbestand index</b> ({@link RegelBestandIndex}), so an entry point can be
 *       transpiled over PRELUDE (the rule-less files) + only its scoped rule files — one {@link KieBase} per
 *       entry point over the SHARED bean classloader, so upstream rules cannot overwrite request-given
 *       intermediate inputs (exact G2 reproduction).</li>
 *   <li><b>Fail-fast on markers</b> — per entry point, any in-scope construct the DRL back-end could not lower
 *       ({@code !lowered()} in {@link DrlTranspiler#dekking()}) is collected; with
 *       {@link KernConfig#allowMarkers()} false the startup refuses naming each rule, with true it starts and
 *       exposes the list. The marker DRL rule blocks are STRIPPED before {@link KieHelper} compilation (an
 *       accepted-but-inert no-op never fires — dropping its block is behaviour-neutral).</li>
 * </ol>
 *
 * <p><b>Back-end</b>: the KieBase is built via the EXEC-model back-end over the child bean classloader
 * ({@link ExecModelTranspiler#build(RegelSpraakModel, ClassLoader)} +
 * {@link org.drools.modelcompiler.KieBaseBuilder#createKieBaseFromModel} under TCCL=beanLoader — a per-owner
 * Lookup links the child-loaded beans, so all 30 golden scopes fire under exec). {@link DrlTranspiler} is
 * retained as the coverage/marker manifest AUTHORITY (markers, dekking — so {@code /info} stays byte-identical)
 * and the differential oracle; a startup GUARD refuses any entry point where the exec back-end skips an
 * in-scope rule the DRL back-end lowers (no mixed-truth transport). The generated beans live on the CHILD
 * classloader, so all fact {@code Class.forName}/read/write go through the bean classloader.
 *
 * <p>KieBases are immutable after startup; a session is opened per request ({@link Uitvoerder}), so the core is
 * thread-safe by construction.
 */
public final class BeslisserKern {

    private final DienstDescriptor descriptor;
    private final RegelSpraakModel model;
    private final ClassLoader beanLoader;   // the shared child bean classloader
    private final Map<String, Entrypoint> perPad;   // URL path -> compiled entry point
    /** The loaded, validity-versioned parameter sets (fase 5), or empty when the descriptor names no sidecar. */
    private final List<Parameterset> parametersets;
    /** The rendered {@link PeriodeDekking} WARNING lines (regel-geldigheid × parameter-geldigheid gaps), computed
     *  once at startup; served verbatim on /info and logged by DienstMain. Empty when no gap or no sets. */
    private final List<String> periodeWaarschuwingen;

    private BeslisserKern(DienstDescriptor descriptor, RegelSpraakModel model, ClassLoader beanLoader,
                          Map<String, Entrypoint> perPad, List<Parameterset> parametersets,
                          List<String> periodeWaarschuwingen) {
        this.descriptor = descriptor;
        this.model = model;
        this.beanLoader = beanLoader;
        this.perPad = perPad;
        this.parametersets = parametersets;
        this.periodeWaarschuwingen = periodeWaarschuwingen;
    }

    /** One compiled entry point: its descriptor entry, its scoped {@link KieBase}, its marker rule list, the
     *  descriptor parameter defaults resolved to globals, the §9.8 dagsoort predicates its DRL declares as
     *  globals (the session builder must supply those per {@link DrlTranspiler#dagsoortGlobalen()} — the analog
     *  of the parameter globals), and the in-scope {@link Dekking} manifest.
     *
     *  <p>{@code dekking} carries EVERY per-construct verdict the DRL back-end reached for a construct whose base
     *  name is in this entry point's scope — LOWERED entries included, filtered the SAME way {@link #markers} is
     *  ({@link #dekkingInScope}). Fase 3 reports it verbatim on {@code /info} so a caller sees the per-regel
     *  verdict (LOWERED / MARKER / SKIP). A §13.4.7 feitcreatie scope (toka-29) has NO dekking entry at all, so
     *  this list is legitimately empty there and /info shows the empty list as-is. */
    public record Entrypoint(DienstDescriptor.Entrypoint descriptor, KieBase kieBase,
                             List<String> markers, Map<String, Object> defaultGlobals,
                             Map<String, java.util.function.Predicate<java.time.LocalDate>> dagsoortGlobalen,
                             List<Dekking> dekking) {}

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

        // 3. compile the fact beans ONCE for the whole service (same objectmodel for every entrypoint) and
        //    contract-validate the model against them (the ScenarioRunner.run L83-86 gate).
        Map<String, String> beanBronnen = FactModelGenerator.genereer(model);
        ClassLoader beanLoader = BeanCompiler.compileer(beanBronnen);
        List<String> schendingen = ContractValidator.valideer(model, beanLoader);
        if (!schendingen.isEmpty()) {
            List<Schending> uit = new ArrayList<>();
            for (String s : schendingen) uit.add(Schending.van(Schending.Soort.MALFORMED, "feitmodel", s));
            throw new DienstFout("dienst start niet: het feitmodel-contract faalt", uit);
        }

        // 4. rule -> file index; prelude = files declaring no rules
        RegelBestandIndex index = RegelBestandIndex.bouw(bronPerBestand);

        // 5. per entry point: transpile prelude + scoped rule files to DRL over the shared bean classloader,
        //    collect markers, build the KieBase. HARD failures (a scope file missing, a scope source that does
        //    not resolve, a DRL that does not compile) are startup errors REGARDLESS of allowMarkers — that flag
        //    only tolerates honest markers; it must never mask a broken entrypoint as a silently-missing route.
        Map<String, Entrypoint> perPad = new LinkedHashMap<>();
        List<Schending> markerFouten = new ArrayList<>();
        List<Schending> hardeFouten = new ArrayList<>();
        List<Schending> scopeFouten = new ArrayList<>();
        // the producer index is model-wide (every rule that WRITES a kenmerk/attribuut), computed once.
        Map<ElementSleutel, java.util.Set<String>> producenten = ProducentConsument.producentIndex(model);
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
            DrlTranspiler transpiler = DrlTranspiler.build(scopeModel);
            List<String> markers = markersInScope(ep, transpiler, model);
            if (!markers.isEmpty() && !config.allowMarkers())
                for (String m : markers)
                    markerFouten.add(Schending.van(Schending.Soort.MARKER, "entrypoint:" + ep.pad(),
                            "in-scope regel '" + m + "' is een marker (niet-gelowerd) in entrypoint '" + ep.naam() + "'"));
            // Build the KieBase via the exec-model back-end (what FIRES); DrlTranspiler stays the manifest authority.
            ExecModelTranspiler et = ExecModelTranspiler.build(scopeModel, beanLoader);
            // no-mixed-truth guard: exec must not skip an in-scope rule DRL lowers (otherwise the exec KieBase would
            // silently omit a rule /info reports as covered). All exec lowering gaps are lifted, so this never trips.
            java.util.Set<String> drlLowered = new java.util.LinkedHashSet<>(inScopeLoweredNames(ep, transpiler.dekking(), model));
            List<String> execSkips = markersInScope(ep, et.dekking(), model);
            List<String> mixed = execSkips.stream().filter(drlLowered::contains).collect(java.util.stream.Collectors.toList());
            if (!mixed.isEmpty()) {
                hardeFouten.add(Schending.van(Schending.Soort.MALFORMED, "entrypoint:" + ep.pad(),
                        "exec-model kan in-scope regel(s) niet loweren die DRL wel lowert (geen mixed-truth transport): " + mixed));
                continue;
            }
            KieBase kieBase = bouwKieBaseExec(et, beanLoader, ep, hardeFouten);
            if (kieBase == null) continue;
            Map<String, Object> defaults = defaultGlobals(descriptor, model);
            List<Dekking> dekking = dekkingInScope(ep, transpiler, model);
            perPad.put(ep.pad(), new Entrypoint(ep, kieBase, markers, defaults,
                    transpiler.dagsoortGlobalen(), dekking));
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

        return new BeslisserKern(descriptor, model, beanLoader, perPad, sets, waarschuwingen);
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

    /** Build a {@link KieBase} over the shared child bean classloader via the EXEC-model back-end
     *  ({@link org.drools.modelcompiler.KieBaseBuilder#createKieBaseFromModel} under TCCL=beanLoader, so the
     *  LambdaMetafactory fact-accessors bind against the child-loaded beans). A build failure is a startup
     *  violation naming the entrypoint. Returns null on failure (the caller records it and skips the entrypoint). */
    private static KieBase bouwKieBaseExec(ExecModelTranspiler et, ClassLoader beanLoader,
                                           DienstDescriptor.Entrypoint ep, List<Schending> fouten) {
        ClassLoader vorige = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(beanLoader);
        try { return org.drools.modelcompiler.KieBaseBuilder.createKieBaseFromModel(et.model()); }
        catch (RuntimeException e) {
            fouten.add(Schending.van(Schending.Soort.MALFORMED, "entrypoint:" + ep.pad(),
                    "entrypoint '" + ep.naam() + "' exec-model KieBase-build faalt: " + e.getMessage()));
            return null;
        } finally { Thread.currentThread().setContextClassLoader(vorige); }
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

    /**
     * The in-scope construct names the DRL back-end did NOT lower (markers), from the scoped transpiler's
     * {@link DrlTranspiler#dekking()}. A non-lowered dekking entry whose base name is in scope is a marker.
     *
     * <p><b>A scoped rule that yields NO dekking entry at all is NOT a marker.</b> A marker is specifically a
     * construct the transpiler ACCEPTED into a dekking entry but could not lower ({@code !lowered()}) — an
     * inert placeholder that would mis-fire if left in. A §13.4.7 feitcreatie ("passagier met recht op
     * treinmiles") is different: {@code RegelSpraakModel#isValueWriter} keeps it out of every resolved
     * collection, so {@link DrlTranspiler#build} emits ZERO rule blocks AND ZERO dekking entries for it. Its
     * base name therefore never appears among {@code transpiler.dekking()}, this loop never sees it, and it does
     * not trip strict mode. The construct is ACCEPTED (the DRL is empty for it, nothing fires), the dekking
     * manifest stays empty for it, and the /info story reports that empty manifest as-is. Its golden scenario
     * (scenario-toka-29) consequently passes as a VACUOUS consistency check — nothing fires, no Inconsistentie,
     * so "de gegevens zijn consistent".
     */
    private static List<String> markersInScope(DienstDescriptor.Entrypoint ep, DrlTranspiler transpiler,
                                               RegelSpraakModel model) {
        return markersInScope(ep, transpiler.dekking(), model);
    }

    /** The {@code !lowered()} sub-view of an arbitrary {@link Dekking} manifest, filtered to this entry point's
     *  scope — the SAME scope-set + {@link #basisNaam} logic as the DrlTranspiler overload above, over ANY
     *  manifest (used for the exec back-end's dekking in the no-mixed-truth guard). */
    private static List<String> markersInScope(DienstDescriptor.Entrypoint ep, List<Dekking> dekking,
                                               RegelSpraakModel model) {
        java.util.Set<String> scope = scopeBasisNamen(ep, model);
        List<String> markers = new ArrayList<>();
        for (Dekking d : dekking)
            if (!d.lowered() && scope.contains(basisNaam(d.naam())) && !markers.contains(basisNaam(d.naam())))
                markers.add(basisNaam(d.naam()));
        return markers;
    }

    /** The in-scope base-names a manifest LOWERS ({@code lowered()} TRUE) — the positive counterpart of
     *  {@link #markersInScope(DienstDescriptor.Entrypoint, List, RegelSpraakModel)}, used to compute the DRL-lowered
     *  set the no-mixed-truth guard compares the exec skips against. */
    private static List<String> inScopeLoweredNames(DienstDescriptor.Entrypoint ep, List<Dekking> dekking,
                                                    RegelSpraakModel model) {
        java.util.Set<String> scope = scopeBasisNamen(ep, model);
        List<String> lowered = new ArrayList<>();
        for (Dekking d : dekking)
            if (d.lowered() && scope.contains(basisNaam(d.naam())) && !lowered.contains(basisNaam(d.naam())))
                lowered.add(basisNaam(d.naam()));
        return lowered;
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

    /**
     * The FULL in-scope {@link Dekking} manifest for an entry point — every per-construct verdict the DRL
     * back-end reached for a construct whose base name is in the entry point's scope, filtered EXACTLY the way
     * {@link #markersInScope} filters (same scope set, same {@link #basisNaam} normalisation) but keeping ALL
     * verdicts, LOWERED included. This is what fase-3 {@code /info} reports per-regel; {@code markersInScope} is
     * just the {@code !lowered()} sub-view of the same list. A feitcreatie scope (toka-29) yields ZERO dekking
     * entries and therefore an empty manifest here — reported as-is.
     */
    private static List<Dekking> dekkingInScope(DienstDescriptor.Entrypoint ep, DrlTranspiler transpiler,
                                                RegelSpraakModel model) {
        java.util.Set<String> scope = new java.util.LinkedHashSet<>();
        for (String n : ep.scope().regelnamenOfLeeg()) scope.add(basisNaam(n));
        for (String groep : ep.scope().regelgroepenOfLeeg())
            for (var g : model.regelGroepen) if (g.naam().equals(groep))
                for (String lid : g.leden()) scope.add(basisNaam(lid));

        List<Dekking> uit = new ArrayList<>();
        for (Dekking d : transpiler.dekking())
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
     * every scoped {@link io.github.yhavinga.regelspraak.ir.ResolvedRegel} it collects the kenmerken/attributen the rule
     * CONSUMES (its voorwaarde predicates + operands, and its RHS {@code waarde}); a consumed element is a
     * violation iff (a) some rule in {@code producenten} WRITES it, (b) NONE of those producers is in this
     * entry point's scope, and (c) the element is not a declared {@code invoerElementen} (the caller then
     * supplies the value, so the missing producer is intentional). Elements with no producer at all are genuine
     * request inputs, not violations.
     */
    private static List<Schending> scopeVolledigheid(DienstDescriptor.Entrypoint ep, RegelSpraakModel model,
            Map<ElementSleutel, java.util.Set<String>> producenten) {
        java.util.Set<String> scope = scopeBasisNamen(ep, model);
        java.util.Set<ElementSleutel> invoerElementen = new java.util.LinkedHashSet<>();
        for (String sel : ep.invoerElementenOfLeeg()) {
            Uitvoerselector u = Uitvoerselector.ontleed(sel);
            if (u != null) invoerElementen.add(ElementSleutel.van(u.objecttype(), u.lid(), u.kenmerk()));
        }

        List<Schending> uit = new ArrayList<>();
        java.util.Set<ElementSleutel> gemeld = new java.util.LinkedHashSet<>();
        for (var r : model.resolvedRegels) {
            if (!scope.contains(basisNaam(r.naam()))) continue;
            for (ElementSleutel consumed : ProducentConsument.verbruikt(r)) {
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
        VerzoekMapper mapper = new VerzoekMapper(model, beanLoader);
        VerzoekMapper.Resultaat mr = mapper.map(new Verzoek(verzoekBoom));
        if (!mr.schendingen().isEmpty())
            throw new DienstFout("verzoek valideert niet", mr.schendingen());
        // core contract check (fase 6, G4): the entrypoint's invoer list is the DECLARED contract — every
        // objecttype it names MUST have at least one instantie in the request. A mapping/adapter rejection here
        // happens strictly BEFORE Uitvoerder.vuur, so the resulting 4xx is itself the no-fire witness: an absent
        // required instance can never fire meaningfully (the scoped rules quantify over instances of that
        // objecttype, so a missing invoer objecttype means the rule has nothing to fire against). Collected first,
        // then thrown together as one 400.
        List<Schending> ontbrekend = ontbrekendeInvoer(ep, mr.typePerLabel());
        if (!ontbrekend.isEmpty())
            throw new DienstFout("verzoek valideert niet", ontbrekend);
        // fase 5, decision D: resolve the parameterset vintage against the one date authority (the request
        // peildatum, which IS Wereld.rekendatum). The resolved values sit BETWEEN the descriptor defaults and the
        // request parameter overrides (descriptor default first, set-value next — the conflict rule guarantees
        // they never name the same parameter — request override last: explicit caller-intent wins).
        Map<String, Object> setGlobals = vintageGlobals(mr.wereld().rekendatum());
        Uitvoerder uitvoerder = new Uitvoerder(ep.kieBase(), ep.defaultGlobals(), ep.dagsoortGlobalen(), beanLoader);
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
     * least ONE mapped instance of that objecttype in the request. A missing one yields a
     * {@link Schending.Soort#ONTBREKENDE_INSTANTIE} with veld {@code "instanties"} whose message names BOTH the
     * objecttype AND the entrypoint. Objecttype identity is matched case-insensitively (the same tolerance the
     * mapper and descriptor validation use), against the resolved {@link RegelSpraakModel.Objecttype} of every
     * mapped instance.
     */
    private static List<Schending> ontbrekendeInvoer(Entrypoint ep,
            Map<String, RegelSpraakModel.Objecttype> typePerLabel) {
        List<Schending> uit = new ArrayList<>();
        List<String> invoer = ep.descriptor().invoer();
        if (invoer == null) return uit;
        for (String vereist : invoer) {
            boolean aanwezig = false;
            for (RegelSpraakModel.Objecttype ot : typePerLabel.values())
                if (ot != null && ot.naam.equalsIgnoreCase(vereist)) { aanwezig = true; break; }
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
     *  by {@link io.github.yhavinga.regelspraak.service.http.DienstMain} at startup. Empty when no gap / no sets. */
    public List<String> periodeWaarschuwingen() { return periodeWaarschuwingen; }

    /** Thrown when a request targets a path no entry point serves. */
    public static final class OnbekendPad extends RuntimeException {
        public OnbekendPad(String pad) { super("onbekend pad: '" + pad + "'"); }
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
