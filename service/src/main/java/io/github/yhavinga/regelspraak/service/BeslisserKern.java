package io.github.yhavinga.regelspraak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.yhavinga.regelspraak.engine.BeanCompiler;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.ContractValidator;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.kern.Dekking;

import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.io.ResourceType;
import org.kie.internal.utils.KieHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The framework-agnostic decision-service CORE (fase 2). Constructed from a descriptor and the ordered list of
 * {@code .rs} source files that make up the service (manifest-style), it performs, at startup, exactly the
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
 * <p><b>Back-end</b>: the DRL back-end, exactly the ScenarioRunner recipe — the pinned path for gate G2. The
 * exec-model back-end cannot compile toka-22/25 (hyphenated datum-tijd getter mismatch) and SKIPs several
 * golden scopes (Paaskorting, vastgestelde contingent, rondvlucht, belaste reis, belasting op afstand), so it
 * cannot reach 30/30; the DRL back-end lowers all of them (verified via the golden manifest, whose exit-0 lines
 * are the 30 golden scenarios). The generated beans live on the CHILD classloader, so all fact
 * {@code Class.forName}/read/write go through the bean classloader.
 *
 * <p>KieBases are immutable after startup; a session is opened per request ({@link Uitvoerder}), so the core is
 * thread-safe by construction.
 */
public final class BeslisserKern {

    private final DienstDescriptor descriptor;
    private final RegelSpraakModel model;
    private final ClassLoader beanLoader;   // the shared child bean classloader
    private final Map<String, Entrypoint> perPad;   // URL path -> compiled entry point

    private BeslisserKern(DienstDescriptor descriptor, RegelSpraakModel model, ClassLoader beanLoader,
                          Map<String, Entrypoint> perPad) {
        this.descriptor = descriptor;
        this.model = model;
        this.beanLoader = beanLoader;
        this.perPad = perPad;
    }

    /** One compiled entry point: its descriptor entry, its scoped {@link KieBase}, its marker rule list, the
     *  descriptor parameter defaults resolved to globals, and the §9.8 dagsoort predicates its DRL declares as
     *  globals (the session builder must supply those per {@link DrlTranspiler#dagsoortGlobalen()} — the analog
     *  of the parameter globals). */
    public record Entrypoint(DienstDescriptor.Entrypoint descriptor, KieBase kieBase,
                             List<String> markers, Map<String, Object> defaultGlobals,
                             Map<String, java.util.function.Predicate<java.time.LocalDate>> dagsoortGlobalen) {}

    // ---------------- startup ----------------

    /** Build the core from a descriptor file and the service's {@code .rs} source files (in order). The
     *  {@code loader} is the parent for the bean classloader; the generated beans are served from a child
     *  loader created here, not looked up on {@code loader}. */
    public static BeslisserKern bouw(Path descriptorBestand, List<Path> bronBestanden, KernConfig config,
                                     ClassLoader loader) throws IOException {
        Map<String, String> tekst = new LinkedHashMap<>();
        for (Path p : bronBestanden) tekst.put(p.toString(), Files.readString(p));
        String json = Files.readString(descriptorBestand);
        return bouw(json, tekst, config, loader);
    }

    /** Build the core from descriptor JSON text and the ordered file-id→source-text map. */
    public static BeslisserKern bouw(String descriptorJson, Map<String, String> bronPerBestand, KernConfig config,
                                     ClassLoader loader) {
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
        for (DienstDescriptor.Entrypoint ep : descriptor.entrypoints()) {
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
            KieBase kieBase = bouwKieBase(transpiler, beanLoader, ep, hardeFouten);
            if (kieBase == null) continue;
            Map<String, Object> defaults = defaultGlobals(descriptor, model);
            perPad.put(ep.pad(), new Entrypoint(ep, kieBase, markers, defaults, transpiler.dagsoortGlobalen()));
        }
        if (!hardeFouten.isEmpty())
            throw new DienstFout("dienst start niet: entrypoint(s) niet bouwbaar", hardeFouten);
        if (!markerFouten.isEmpty() && !config.allowMarkers())
            throw new DienstFout("dienst start niet: marker-regels in scope", markerFouten);

        return new BeslisserKern(descriptor, model, beanLoader, perPad);
    }

    /** Strip the marker rule blocks off the transpiled DRL and build a {@link KieBase} over the shared bean
     *  classloader via {@link KieHelper#setClassLoader} (the proven ScenarioRunner / TokaVuurTest pattern). A
     *  compile ERROR is a startup violation naming the entrypoint. Returns null on failure (the caller records
     *  it and skips the entrypoint). */
    private static KieBase bouwKieBase(DrlTranspiler transpiler, ClassLoader beanLoader,
                                       DienstDescriptor.Entrypoint ep, List<Schending> fouten) {
        String drl = stripMarkerRegels(transpiler.drl());
        KieHelper helper = new KieHelper();
        helper.setClassLoader(beanLoader);
        helper.addContent(drl, ResourceType.DRL);
        var meldingen = helper.verify();
        if (meldingen.hasMessages(Message.Level.ERROR)) {
            fouten.add(Schending.van(Schending.Soort.MALFORMED, "entrypoint:" + ep.pad(),
                    "entrypoint '" + ep.naam() + "' DRL compileert niet tegen de gegenereerde beans: "
                            + meldingen.getMessages()));
            return null;
        }
        return helper.build();
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
        java.util.Set<String> scope = new java.util.LinkedHashSet<>();
        for (String n : ep.scope().regelnamenOfLeeg()) scope.add(basisNaam(n));
        for (String groep : ep.scope().regelgroepenOfLeeg())
            for (var g : model.regelGroepen) if (g.naam().equals(groep))
                for (String lid : g.leden()) scope.add(basisNaam(lid));

        List<String> markers = new ArrayList<>();
        for (Dekking d : transpiler.dekking())
            if (!d.lowered() && scope.contains(basisNaam(d.naam())) && !markers.contains(basisNaam(d.naam())))
                markers.add(basisNaam(d.naam()));
        return markers;
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

    /** Handle a request against the entry point at {@code pad}: map → fire → build response. */
    public ObjectNode verwerk(String pad, JsonNode verzoekBoom) {
        Entrypoint ep = perPad.get(pad);
        if (ep == null) throw new OnbekendPad(pad);
        VerzoekMapper mapper = new VerzoekMapper(model, beanLoader);
        VerzoekMapper.Resultaat mr = mapper.map(new Verzoek(verzoekBoom));
        if (!mr.schendingen().isEmpty())
            throw new DienstFout("verzoek valideert niet", mr.schendingen());
        Uitvoerder uitvoerder = new Uitvoerder(ep.kieBase(), ep.defaultGlobals(), ep.dagsoortGlobalen(), beanLoader);
        Uitvoerder.Uitkomst uitkomst = uitvoerder.vuur(mr.wereld());
        return new AntwoordBouwer(model).bouw(uitkomst, ep.descriptor().uitvoer(), mr.typePerLabel());
    }

    // ---------------- accessors (fase-3 /info) ----------------

    public DienstDescriptor descriptor() { return descriptor; }
    public RegelSpraakModel model() { return model; }
    public Map<String, Entrypoint> entrypoints() { return perPad; }
    public Entrypoint entrypoint(String pad) { return perPad.get(pad); }

    /** Thrown when a request targets a path no entry point serves. */
    public static final class OnbekendPad extends RuntimeException {
        public OnbekendPad(String pad) { super("onbekend pad: '" + pad + "'"); }
    }

    // ---------------- marker-strip (reimplemented from ScenarioRunner) ----------------

    /**
     * Remove every DRL {@code rule "…" … end} block whose body carries an honest marker ({@code /* TODO} or
     * {@code // onopgelost}) — the MVEL dialect cannot parse the placeholder, so keeping the block would sink
     * the whole compilation. A marker rule produces no value (accepted-but-inert), so dropping it is
     * behaviour-neutral. This is a minimal reimplementation of
     * {@code io.github.yhavinga.regelspraak.engine.ScenarioRunner#stripMarkerRegels} (package-private there); equivalence
     * is pinned by test — the golden marker scope (scenario-marker-exit3 "het absolute tijdsevenredig deel per
     * maand") lowers to a marker and this strip must leave a compilable DRL exactly as the runner does.
     */
    static String stripMarkerRegels(String drl) {
        String[] regels = drl.split("\n", -1);
        StringBuilder uit = new StringBuilder();
        List<String> blok = new ArrayList<>();
        boolean inRegel = false;
        for (String r : regels) {
            String t = r.stripLeading();
            if (!inRegel && t.startsWith("rule ")) { inRegel = true; blok.clear(); blok.add(r); continue; }
            if (inRegel) {
                blok.add(r);
                if (t.equals("end") || t.startsWith("end ")) {
                    inRegel = false;
                    String tekst = String.join("\n", blok);
                    // detect the marker sentinels the SAME way DrlTranspiler.meet does — over the text with
                    // STRING LITERALS blanked, so a genuinely-lowered rule carrying a Tekst/enum literal that
                    // happens to read "/* TODO …" is not mistaken for a marker (mirrors DrlTranspiler.zonderStrings).
                    String zonderStrings = tekst.replaceAll("\"(?:\\\\.|[^\"\\\\\n])*\"", "\"\"");
                    if (!zonderStrings.contains("/* TODO") && !zonderStrings.contains("// onopgelost"))
                        uit.append(tekst).append('\n');
                }
                continue;
            }
            uit.append(r).append('\n');
        }
        if (uit.length() > 0) uit.setLength(uit.length() - 1);   // drop the trailing newline we added last
        return uit.toString();
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
