package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.DekkingRegel;
import dev.uchar.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Vuring;
import dev.uchar.regelspraak.toolchain.engine.Naamgeving;
import dev.uchar.regelspraak.toolchain.engine.ScenarioRunner;
import dev.uchar.regelspraak.toolchain.engine.UcharBouw;
import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.DienstFout;
import dev.uchar.regelspraak.toolchain.service.Evaluatiefout;
import dev.uchar.regelspraak.toolchain.service.FixpuntGegevens;
import dev.uchar.regelspraak.toolchain.service.GeenFixpunt;
import dev.uchar.regelspraak.toolchain.service.Schending;
import dev.uchar.regelspraak.toolchain.service.VerzoekOpties;
import dev.uchar.regelspraak.graaf.ProducentConsument;
import dev.uchar.regelspraak.graaf.RegelGraaf;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Benoembaar;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;
import dev.uchar.regelspraak.model.RegelSpraakModel.GegenereerdUit;
import dev.uchar.regelspraak.model.RegelSpraakModel.Severity;
import dev.uchar.regelspraak.model.RegelSpraakModel.Vocabulaire;
import dev.uchar.regelspraak.parser.RegelSpraak;
import dev.uchar.regelspraak.parser.RegelSpraakLexer;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import dev.uchar.regelspraak.transpiler.kern.Dekking;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The DEV-ONLY RegelSpraak playground core (a "type-and-see" workbench). It is a pure analyzer: given a RegelSpraak
 * source it returns ONE JSON tree:
 * <ol>
 *   <li><b>parse</b> — {@code ok} plus, per syntax error, its {@code regel}/{@code kolom}, the offending token
 *       and the {@code verwacht} token set (the ANTLR follow set);</li>
 *   <li><b>resolver</b> — the resolver verdict: the ERROR and WARNING diagnostics (line + message).</li>
 * </ol>
 * A source with a parse or resolver error gets those two and nothing else: uchar's emitter writes no document for
 * it, so there is nothing to show, build or fire. A clean source also gets:
 * <ol start="3">
 *   <li><b>ir</b> — the {@code uchar-ir-v1} document uchar's emitter writes, or {@code irFout} with the emitter's
 *       refusal;</li>
 *   <li><b>uitvoerbaar</b> — uchar's verdict on the exec-model branch, the branch that fires: {@code true} with the
 *       {@code dekking} per form and {@code gegenereerd}, the names of that coverage that name no element of
 *       {@code vocabulaires} with the element uchar made them from ({@link #gegenereerd}), or {@code false} with
 *       uchar's {@code weigering};</li>
 *   <li><b>drl</b> — the DRL text of uchar's DRL-text branch, a view and no verdict (that branch renders the
 *       analysis the exec-model branch fires as DRL text); {@code drlFout} carries its refusal, or uchar's sentence
 *       for a DRL text the Drools compiler rejects;</li>
 *   <li><b>graaf</b> and <b>graafJson</b> — uchar's rule graph of the document its loader reads back (the route
 *       uchar's gate 15 holds equal to the source route), as Mermaid source and as
 *       {@code {nodes:[{id,label,kind,eindOutput}], edges:[{van,naar}]}}, so a workbench can take a rule's dataflow
 *       neighbourhood without parsing Mermaid;</li>
 *   <li><b>vocabulaires</b> — the vocabularies the source declares, the chosen one, and per declaration its
 *       designation in each, its source line and the comment above it, and for an element uchar made itself the
 *       element it comes from ({@link #vocabulaires});</li>
 *   <li><b>scenario</b> — only when the source contains a {@code Scenario} block: per scenario uchar's report
 *       (status, per expectation verwacht/werkelijk, the coverage), its {@code trace} (per fired rule the labels it
 *       bound and how often) and its {@code uitleg} ({@link ScenarioRunner#runMetUitleg}), or {@code uitlegFout};
 *       beside it the source line of the scenario and of each expectation, the {@code situatie} uchar's scenario
 *       resolver gives, and the {@code fixpunt} of the case's own session, or {@code fixpuntFout}
 *       ({@link ScenarioGegevens}).</li>
 * </ol>
 *
 * <p><b>Vocabulary.</b> {@link #analyseer(String, String)} names the declarations in {@code graafJson}, the trace, the
 * per-scenario coverage, the uitleg, the situation and the fixpoint in a vocabulary the source declares
 * ({@link Naamgeving}); the parse and resolver output, the IR, the verdict, the DRL and the Mermaid graph are uchar's
 * and stay as uchar writes them. A vocabulary the source does not declare is a {@link Naamgeving.Vocabulairefout},
 * which the HTTP adapter answers with 400.
 *
 * <p><b>Scenario-aware front-end.</b> {@code Situatie}/{@code Scenario} blocks are a documented extension of uchar's
 * frontend (its {@code grammar/DIVERGENCES.md}) reachable only via the scenario entry rule, NOT part of the
 * production {@code regelSpraakDocument}. So when a source carries such a block the analyzer parses + resolves it
 * through the scenario front-end ({@link ScenarioResolver}) and runs the scenarios off that same resolved bundle.
 *
 * <p><b>Via the service.</b> {@link #analyseer(String, String, BeslisserKern)} with a core gives the same tree, and only
 * the scenarios are computed by that core instead of uchar's scenario layer ({@link #rendViaDienst}): each becomes a
 * request of the service ({@link SituatieNaarVerzoek}) to the entry point its scope maps to
 * ({@link BeslisserKern#padVoorScope}), and uchar compares the expectations of its case with the session of that
 * request ({@link BeslisserKern#beoordeel}). The core computes with its own sources, so a source whose
 * {@code uchar-ir-v1} document is not the core's ({@link BeslisserKern#document}) is refused as a whole with a
 * {@link DienstFout}.
 *
 * <p>This class owns NO web plumbing and NO persistence. A refusal of uchar, and a DRL text of uchar that does not
 * compile, is part of the answer; any other failure is not caught here, so the HTTP adapter ({@link DienstHttp})
 * answers it with 500 and logs it.
 */
public final class Playground {

    private Playground() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** The literal that opens a scenariotoets block (RegelSpraakLexer SCENARIO token). Its presence is what makes
     *  the analyzer run the scenario front-end/runner rather than only the production pipeline. */
    private static final String SCENARIO_KOP = "Scenario";

    /** Analyze {@code bron} and return the answer tree (see the class javadoc for its shape). */
    public static ObjectNode analyseer(String bron) {
        return analyseer(bron, null);
    }

    /** {@link #analyseer(String)} with the declarations named in {@code vocabulaire} (null or
     *  {@link Naamgeving#CANONIEK}: canonically). */
    public static ObjectNode analyseer(String bron, String vocabulaire) {
        return analyseer(bron, vocabulaire, null);
    }

    /**
     * {@link #analyseer(String, String)} with the scenarios computed by {@code dienst} (null: by uchar's scenario layer,
     * as above). Each result then also names the {@code pad} of the entry point it ran on.
     *
     * @throws DienstFout when the {@code uchar-ir-v1} document of {@code bron}, without its scenarios, is not that of
     *                    {@code dienst}: the service computes with its own sources, so its verdict would be about other
     *                    rules than these
     */
    public static ObjectNode analyseer(String bron, String vocabulaire, BeslisserKern dienst) {
        ObjectNode root = NF.objectNode();
        boolean scenario = bevatScenario(bron);

        // 1. PARSE — a dedicated pass that KEEPS the column + expected-token set the shared Resolver discards. Uses
        //    the scenario entry rule when a Scenario block is present, so those keywords are not mis-flagged.
        Ontleding ontleding = parse(bron, scenario);
        List<ParseFout> parseFouten = ontleding.fouten();
        ObjectNode parse = root.putObject("parse");
        parse.put("ok", parseFouten.isEmpty());
        ArrayNode pf = parse.putArray("fouten");
        for (ParseFout f : parseFouten) {
            ObjectNode n = pf.addObject();
            n.put("regel", f.regel());
            n.put("kolom", f.kolom());
            n.put("boodschap", f.boodschap());
            if (f.token() != null) n.put("token", f.token());
            ArrayNode verwacht = n.putArray("verwacht");
            for (String t : f.verwacht()) verwacht.add(t);
        }

        // 2. RESOLVER — for a scenario source the bundle's model carries the scenario diagnostics as well.
        ScenarioBundel bundel = scenario ? ScenarioResolver.resolve(bron) : null;
        RegelSpraakModel model = scenario ? bundel.model() : Resolver.resolve(bron);
        ObjectNode resolver = root.putObject("resolver");
        resolver.put("ok", model.isOk());
        ArrayNode fouten = resolver.putArray("fouten");
        ArrayNode waarschuwingen = resolver.putArray("waarschuwingen");
        for (Diagnostic d : model.diagnostics) {
            ObjectNode n = (d.severity() == Severity.ERROR ? fouten : waarschuwingen).addObject();
            n.put("regel", d.line());
            n.put("boodschap", d.message());
        }
        if (!model.isOk()) return root;
        Naamgeving naamgeving = Naamgeving.van(model, vocabulaire);
        root.set("vocabulaires", vocabulaires(model, naamgeving, ontleding));

        // 3. IR and the verdict of the branch that fires
        com.fasterxml.jackson.databind.JsonNode ir = null;
        try {
            ir = JSON.readTree(UcharBouw.document(model));
            root.set("ir", ir);
        } catch (UcharBouw.NietGedragen e) {
            root.put("irFout", e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("de emitter schreef geen leesbare JSON: " + e.getOriginalMessage(), e);
        }
        if (dienst != null) zelfdeBronnen(ir, root.path("irFout").asText(null), dienst);
        try {
            DroolsBackend.Build build = UcharBouw.bouw(model, DroolsBackend.Branch.EXEC_MODEL);
            root.put("uitvoerbaar", true);
            ArrayNode dekking = root.putArray("dekking");
            for (Dekking d : build.dekking()) {
                ObjectNode dn = dekking.addObject();
                dn.put("soort", d.soort());
                dn.put("naam", d.naam());
                dn.put("verdict", d.verdict().name());
            }
            root.set("gegenereerd", gegenereerd(build, model, naamgeving));
        } catch (UcharBouw.NietGedragen e) {
            root.put("uitvoerbaar", false);
            root.put("weigering", e.getMessage());
        }

        // 4. DRL — a view of the DRL-text branch, never the verdict. uchar's build of that branch throws an
        //    IllegalStateException for a DRL text the Drools compiler rejects, a fault of its rendering; the view
        //    shows uchar's sentence for it as it shows a refusal
        try {
            root.put("drl", UcharBouw.bouw(model, DroolsBackend.Branch.DRL_TEXT).drl());
        } catch (UcharBouw.NietGedragen | IllegalStateException e) {
            root.put("drl", "");
            root.put("drlFout", e.getMessage());
        }

        // 5. GRAAF — uchar's rule graph of the document its loader reads back, with the defaults groups and the
        //    navigation bindings it returns beside the model; without a document there is no graph
        if (ir == null) {
            root.put("graaf", "");
            root.put("graafFout", root.get("irFout").asText());
        } else {
            try {
                IrLoader.Loaded geladen = IrLoader.loadAll(ir);
                RegelGraaf graaf = RegelGraaf.bouw(geladen.model(), geladen.prioritizedDefaults(),
                        ProducentConsument.wireNavigatie(geladen));
                root.put("graaf", graaf.mermaid());
                try {
                    root.set("graafJson", graafJson(graaf, model, naamgeving));
                } catch (OnbenoembareKnoop o) {
                    root.put("graafFout", o.getMessage());
                }
            } catch (IrLoader.Refusal | IrLoader.Documentfout e) {
                root.put("graaf", "");
                root.put("graafFout", "loader: " + boodschap(e));
            }
        }

        // 6. SCENARIO — only when the source carries a Scenario block
        if (scenario && dienst != null) {
            ArrayNode arr = root.putObject("scenario").putArray("resultaten");
            List<String> bronregels = ScenarioGegevens.regels(bron);
            List<ResolvedScenario> scenarios = bundel.scenarios();
            for (int i = 0; i < scenarios.size(); i++)
                rendViaDienst(arr.addObject(), i, scenarios.get(i), dienst, bronregels, model, naamgeving, vocabulaire);
        } else if (scenario) {
            ArrayNode arr = root.putObject("scenario").putArray("resultaten");
            List<ResolvedScenario> scenarios = bundel.scenarios();
            List<ScenarioRunner.Uitgelegd> uitgelegd = ScenarioRunner.runMetUitleg(bundel, vocabulaire);
            if (uitgelegd.size() != scenarios.size())
                throw new IllegalStateException("de scenariorunner gaf " + uitgelegd.size() + " rapport(en) voor "
                        + scenarios.size() + " scenario('s)");
            List<String> bronregels = ScenarioGegevens.regels(bron);
            for (int i = 0; i < scenarios.size(); i++)
                rendScenario(arr.addObject(), uitgelegd.get(i), scenarios.get(i), bronregels, model, naamgeving);
        }
        return root;
    }

    private static void rendScenario(ObjectNode n, ScenarioRunner.Uitgelegd u, ResolvedScenario s,
                                     List<String> bronregels, RegelSpraakModel model, Naamgeving naamgeving) {
        ScenarioResultaat r = u.resultaat();
        if (!r.naam().equals(s.naam()))
            throw new IllegalStateException("het rapport '" + r.naam() + "' staat op de plaats van het scenario '"
                    + s.naam() + "'");
        // an expectation of the report is found by its position in the case, which the lowering writes one per
        // expectation of the resolved scenario, in its order
        int inCase = verwachtingenInCase(s);
        if (inCase != s.verwachtingen().size())
            throw new IllegalStateException("de case van het scenario '" + s.naam() + "' telt " + inCase
                    + " verwachting(en), het scenario " + s.verwachtingen().size());
        n.put("naam", r.naam());
        n.put("status", r.status().name());
        if (r.diagnose() != null && !r.diagnose().isEmpty()) n.put("diagnose", r.diagnose());
        n.put("regel", s.line());
        ArrayNode verwachtingen = n.putArray("verwachtingen");
        for (VerwachtingResultaat v : r.verwachtingen()) {
            ObjectNode vn = verwachtingen.addObject();
            vn.put("bron", v.bron().trim());
            vn.put("verwacht", v.verwacht());
            vn.put("werkelijk", v.werkelijk());
            vn.put("ok", v.ok());
            ScenarioGegevens.plaats(vn, v, s, bronregels);
        }
        ArrayNode dekking = n.putArray("dekking");
        for (DekkingRegel d : r.dekking()) {
            ObjectNode dn = dekking.addObject();
            dn.put("regel", naamgeving.vorm(d.regel()));
            dn.put("soort", d.soort().name());
        }
        // the fired rules in the order they first fired, with the labels of the input instances they bound
        ArrayNode trace = n.putArray("trace");
        for (Vuring vuring : r.trace().vuringen()) {
            ObjectNode tn = trace.addObject();
            tn.put("regel", naamgeving.vorm(vuring.regel()));
            ArrayNode labels = tn.putArray("labels");
            for (String l : vuring.labels()) labels.add(l);
            tn.put("aantal", vuring.aantal());
        }
        ArrayNode uitleg = n.putArray("uitleg");
        for (String z : u.uitleg()) uitleg.add(z);
        if (u.uitlegFout() != null) n.put("uitlegFout", u.uitlegFout());
        n.set("situatie", ScenarioGegevens.situatie(s, model, naamgeving));
        if (u.sessie() != null) {
            try {
                n.set("fixpunt", ScenarioGegevens.fixpunt(u.sessie(), s, model, naamgeving));
            } catch (FixpuntGegevens.OnweergeefbareWaarde o) {
                n.put("fixpuntFout", o.getMessage());
            }
        } else if (u.sessieFout() != null) {
            n.put("fixpuntFout", u.sessieFout());
        }
    }

    /** The number of expectations in the case uchar's lowering writes for {@code s}. */
    private static int verwachtingenInCase(ResolvedScenario s) {
        return verwachtingen(s).size();
    }

    /** The {@code expected} of the case uchar's lowering writes for {@code s}. */
    private static com.fasterxml.jackson.databind.JsonNode verwachtingen(ResolvedScenario s) {
        try {
            return JSON.readTree(CaseEmitter.emit(s)).get("expected");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("de lowering schreef voor het scenario '" + s.naam()
                    + "' geen leesbare case: " + e.getOriginalMessage(), e);
        }
    }

    // ---------------- via the service ----------------

    /** Refuses with a {@link DienstFout} unless {@code ir}, the document of the posted source, equals that of
     *  {@code dienst} as a JSON tree: the same model, forms and source positions. {@code irFout} is the emitter's
     *  refusal when there is no document. */
    private static void zelfdeBronnen(com.fasterxml.jackson.databind.JsonNode ir, String irFout, BeslisserKern dienst) {
        String reden;
        if (ir == null) {
            reden = "uchars emitter schrijft van de geposte bronnen geen document: " + irFout;
        } else {
            com.fasterxml.jackson.databind.JsonNode eigen;
            try {
                eigen = JSON.readTree(dienst.document());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("het document van de dienst is geen leesbare JSON: "
                        + e.getOriginalMessage(), e);
            }
            if (ir.equals(eigen)) return;
            reden = "het uchar-ir-v1-document van de geposte bronnen is niet dat van de dienst; eerste verschil "
                    + verschil(ir, eigen, "");
        }
        throw new DienstFout("verzoek valideert niet", List.of(Schending.van(Schending.Soort.NIET_ONDERSTEUND, "bron",
                "via=dienst rekent met de bronnen waarmee de dienst gestart is, en " + reden)));
    }

    /** The first place where {@code a} (the posted source's) and {@code b} (the service's) differ, as a JSON pointer
     *  with what each has there; null when they are equal. */
    static String verschil(com.fasterxml.jackson.databind.JsonNode a, com.fasterxml.jackson.databind.JsonNode b,
                           String pad) {
        if (a.equals(b)) return null;
        if (a.isObject() && b.isObject()) {
            Set<String> sleutels = new LinkedHashSet<>();
            a.fieldNames().forEachRemaining(sleutels::add);
            b.fieldNames().forEachRemaining(sleutels::add);
            for (String k : sleutels) {
                String p = pad + "/" + k;
                if (!a.has(k)) return p + ": ontbreekt in de geposte bronnen, de dienst heeft " + kort(b.get(k));
                if (!b.has(k)) return p + ": de geposte bronnen hebben " + kort(a.get(k)) + ", de dienst niets";
                String v = verschil(a.get(k), b.get(k), p);
                if (v != null) return v;
            }
        }
        if (a.isArray() && b.isArray()) {
            Iterator<com.fasterxml.jackson.databind.JsonNode> ia = a.elements(), ib = b.elements();
            for (int i = 0; ia.hasNext() && ib.hasNext(); i++) {
                String v = verschil(ia.next(), ib.next(), pad + "/" + i);
                if (v != null) return v;
            }
            return pad + ": " + a.size() + " elementen in de geposte bronnen, " + b.size() + " in de dienst";
        }
        return (pad.isEmpty() ? "/" : pad) + ": de geposte bronnen hebben " + kort(a) + ", de dienst " + kort(b);
    }

    private static String kort(com.fasterxml.jackson.databind.JsonNode n) {
        String t = n.toString();
        return t.length() <= 120 ? t : t.substring(0, 117) + "...";
    }

    /**
     * Scenario {@code s}, the {@code i}-th of the source, computed by {@code dienst}, in the shape {@link #rendScenario}
     * gives, with {@code pad} after {@code regel}: the entry point its scope maps to, null when none does. The
     * situation becomes a request ({@link SituatieNaarVerzoek}) with canonical names, the answer is named in
     * {@code vocabulaire}, and uchar compares the expectations of the scenario's case with the request's session
     * ({@link BeslisserKern#beoordeel}): PASS or FAIL with uchar's diagnose, prefixed as uchar's report prefixes it with
     * the name of the case file. What the service does not compute is not a PASS:
     * <ul>
     *   <li>no entry point carries the scope, a value the request cannot carry ({@link SituatieNaarVerzoek.NietDraagbaar}),
     *       a request form the service does not carry (each violation of its 400 {@code NIET_ONDERSTEUND}), an entry
     *       point uchar does not carry, an expectation uchar does not compare: MARKER with the sentence;</li>
     *   <li>an evaluation fault (the service's 422): FAIL with the line of uchar's report, the exception its write-back
     *       throws, and the trace, coverage and fixpoint of the 422;</li>
     *   <li>a session without a fixpoint (the service's 422 {@code GEEN_FIXPUNT}): FAIL with the line uchar's report
     *       gives such a case, the name of the case file and uchar's sentence, and the trace and coverage of the 422;</li>
     *   <li>any other 400 of the service: FAIL naming its violations.</li>
     * </ul>
     */
    private static void rendViaDienst(ObjectNode n, int i, ResolvedScenario s, BeslisserKern dienst,
                                      List<String> bronregels, RegelSpraakModel model, Naamgeving naamgeving,
                                      String vocabulaire) {
        String casenaam = CaseEmitter.bestandsnaam(i + 1, s);
        com.fasterxml.jackson.databind.JsonNode verwacht = verwachtingen(s);
        if (verwacht.size() != s.verwachtingen().size())
            throw new IllegalStateException("de case van het scenario '" + s.naam() + "' telt " + verwacht.size()
                    + " verwachting(en), het scenario " + s.verwachtingen().size());
        String pad = null;
        ScenarioResultaat.Status status;
        String diagnose = "";
        List<VerwachtingResultaat> verwachtingen = List.of();
        com.fasterxml.jackson.databind.JsonNode sessie = NF.objectNode();   // trace, dekking, uitleg, fixpunt
        String uitlegFout = null;
        try {
            pad = dienst.padVoorScope(s.scope());
            VerzoekOpties opties = new VerzoekOpties(true, vocabulaire == null ? Naamgeving.CANONIEK : vocabulaire,
                    Naamgeving.CANONIEK, true, true, true);
            BeslisserKern.Beoordeling b = dienst.beoordeel(pad, SituatieNaarVerzoek.bouw(s), opties, verwacht);
            status = b.vergelijking().pass() ? ScenarioResultaat.Status.PASS : ScenarioResultaat.Status.FAIL;
            if (!b.vergelijking().pass()) diagnose = casenaam + ": " + b.vergelijking().diagnose();
            verwachtingen = b.vergelijking().verwachtingen();
            sessie = b.antwoord();
        } catch (BeslisserKern.GeenIngangspunt | SituatieNaarVerzoek.NietDraagbaar | BeslisserKern.NietUitvoerbaar
                 | CaseRunner.Excluded geen) {
            status = ScenarioResultaat.Status.MARKER;
            diagnose = geen.getMessage();
        } catch (DienstFout fout) {
            List<String> zinnen = new ArrayList<>();
            boolean nietOndersteund = true;
            for (Schending sch : fout.schendingen()) {
                zinnen.add(sch.toString());
                nietOndersteund &= sch.soort() == Schending.Soort.NIET_ONDERSTEUND;
            }
            status = nietOndersteund ? ScenarioResultaat.Status.MARKER : ScenarioResultaat.Status.FAIL;
            diagnose = nietOndersteund ? String.join("; ", zinnen)
                    : casenaam + ": de dienst weigert het verzoek: " + String.join("; ", zinnen);
        } catch (Evaluatiefout fout) {
            Throwable uchar = fout.getCause();
            status = ScenarioResultaat.Status.FAIL;
            diagnose = casenaam + ": ERROR " + uchar.getClass().getSimpleName() + ": "
                    + String.valueOf(uchar.getMessage()).replace('\n', ' ');
            sessie = fout.detail();
            uitlegFout = "de dienst gaf een evaluatiefout (422) en geeft dan geen uitleg";
        } catch (GeenFixpunt geen) {
            // uchar's report makes such a case a FAIL with its sentence after the name of the case file, no ERROR
            status = ScenarioResultaat.Status.FAIL;
            diagnose = casenaam + ": " + geen.getMessage();
            sessie = geen.detail();
            uitlegFout = "de dienst bereikte geen fixpunt (422) en geeft dan geen uitleg";
        }
        n.put("naam", s.naam());
        n.put("status", status.name());
        if (!diagnose.isEmpty()) n.put("diagnose", diagnose);
        n.put("regel", s.line());
        if (pad == null) n.putNull("pad"); else n.put("pad", pad);
        ArrayNode va = n.putArray("verwachtingen");
        for (VerwachtingResultaat v : verwachtingen) {
            ObjectNode vn = va.addObject();
            vn.put("bron", v.bron().trim());
            vn.put("verwacht", v.verwacht());
            vn.put("werkelijk", v.werkelijk());
            vn.put("ok", v.ok());
            ScenarioGegevens.plaats(vn, v, s, bronregels);
        }
        for (String veld : List.of("dekking", "trace", "uitleg"))
            n.set(veld, sessie.has(veld) ? sessie.get(veld) : NF.arrayNode());
        if (uitlegFout != null) n.put("uitlegFout", uitlegFout);
        n.set("situatie", ScenarioGegevens.situatie(s, model, naamgeving));
        if (sessie.has("fixpunt")) n.set("fixpunt", sessie.get("fixpunt"));
        if (sessie.has("fixpuntFout")) n.put("fixpuntFout", sessie.get("fixpuntFout").asText());
    }

    /** The vocabularies of {@code model}: {@code gekozen}, the choice of {@code naamgeving}
     *  ({@link Naamgeving#CANONIEK} or a vocabulary), {@code gedeclareerd}, each declared vocabulary with whether it
     *  is the default one and its language, and {@code elementen}, every declaration a vocabulary can name in the
     *  model's order ({@link RegelSpraakModel#benoembaar}) with its kind, its owner and canonical name as declared, its
     *  {@code benamingen}: per declared vocabulary its designation there, or null where it has none and goes by its
     *  canonical name (an empty object without a declared vocabulary), and, where it has a declaration in the source,
     *  its {@code regel} and the {@code commentaar} directly above it, read from the parse in {@code ontleding}
     *  ({@link Declaraties}). An element or a declaration that does not couple puts {@code declaratieFout} with the
     *  reason, and then no element carries {@code regel} or {@code commentaar}. An element uchar's frontend made itself
     *  carries {@code gegenereerdUit}, the element it comes from ({@link RegelSpraakModel#gegenereerdUit}). */
    static ObjectNode vocabulaires(RegelSpraakModel model, Naamgeving naamgeving, Ontleding ontleding) {
        ObjectNode n = NF.objectNode();
        n.put("gekozen", naamgeving.keuze());
        ArrayNode gedeclareerd = n.putArray("gedeclareerd");
        List<Naamgeving> per = new ArrayList<>();
        for (Vocabulaire v : model.vocabulaires) {
            ObjectNode vn = gedeclareerd.addObject();
            vn.put("naam", v.naam());
            vn.put("standaard", v.standaard());
            if (v.taal() == null) vn.putNull("taal"); else vn.put("taal", v.taal());
            per.add(Naamgeving.van(model, v.naam()));
        }
        Map<Benoembaar, Declaraties.Declaratie> declaraties = Map.of();
        String declaratieFout = null;
        try {
            declaraties = Declaraties.van(ontleding.document(), ontleding.tokens(), model);
        } catch (Declaraties.Onkoppelbaar o) {
            declaratieFout = o.getMessage();
        }
        ArrayNode elementen = n.putArray("elementen");
        for (Benoembaar b : model.benoembaar()) {
            ObjectNode en = elementen.addObject();
            en.put("soort", b.soort().name());
            if (b.eigenaar() == null) en.putNull("eigenaar"); else en.put("eigenaar", b.eigenaar());
            en.put("canoniek", b.naam());
            ObjectNode benamingen = en.putObject("benamingen");
            for (Naamgeving g : per) {
                String t = g.benaming(b);
                if (t == null) benamingen.putNull(g.keuze()); else benamingen.put(g.keuze(), t);
            }
            Declaraties.Declaratie d = declaraties.get(b);
            if (d != null) {
                en.put("regel", d.regel());
                ArrayNode commentaar = en.putArray("commentaar");
                for (String c : d.commentaar()) commentaar.add(c);
            }
            model.gegenereerdUit(b).ifPresent(g -> en.set("gegenereerdUit", gegenereerdUit(g)));
        }
        if (declaratieFout != null) n.put("declaratieFout", declaratieFout);
        return n;
    }

    /** Where an element uchar made comes from, as {@code {soort, eigenaar, naam, vorm}}: the kind, owner and name of the
     *  source element as declared (canonical, as an element of {@code vocabulaires} carries them) and uchar's fixed key
     *  of the form that made it. */
    private static ObjectNode gegenereerdUit(GegenereerdUit g) {
        ObjectNode n = NF.objectNode();
        n.put("soort", g.bron().soort().name());
        if (g.bron().eigenaar() == null) n.putNull("eigenaar"); else n.put("eigenaar", g.bron().eigenaar());
        n.put("naam", g.bron().naam());
        n.put("vorm", g.vorm().sleutel());
        return n;
    }

    /** The names of the coverage of {@code build} that name no element of {@link RegelSpraakModel#benoembaar}, each as
     *  {@code {naam, soort, gegenereerdUit}} in the order of the coverage, with uchar's origin of it
     *  ({@code TmsTranspiler.gegenereerd}): an effective defaults stack, a version of a form. {@code naam} is spelled
     *  as the coverage and trace of a scenario spell it ({@link Naamgeving#vorm}), with {@code naamCanoniek} beside it
     *  where that differs from uchar's name; {@code soort} is the kind of the coverage. A name of the coverage that is
     *  no form of the model and has no origin in uchar's table is a fault of the analysis, not an answer. */
    static ArrayNode gegenereerd(DroolsBackend.Build build, RegelSpraakModel model, Naamgeving naamgeving) {
        Set<String> vormen = new HashSet<>();
        for (Benoembaar b : model.benoembaar()) if (b.soort() == Benoembaar.Soort.VORM) vormen.add(b.naam());
        Map<String, GegenereerdUit> tabel = build.execModelTranspiler().gegenereerd();
        ArrayNode uit = NF.arrayNode();
        for (Dekking d : build.dekking()) {
            GegenereerdUit g = tabel.get(d.naam());
            if (g == null) {
                if (!vormen.contains(d.naam()))
                    throw new IllegalStateException("de dekking noemt " + d.soort() + " '" + d.naam() + "', geen "
                            + "vorm van het model, en uchar noemt de naam niet gegenereerd");
                continue;
            }
            ObjectNode n = uit.addObject();
            String gekozen = naamgeving.vorm(d.naam());
            n.put("naam", gekozen);
            if (!gekozen.equals(d.naam())) n.put("naamCanoniek", d.naam());
            n.put("soort", d.soort());
            n.set("gegenereerdUit", gegenereerdUit(g));
        }
        return uit;
    }

    /** The rule graph as {@code {nodes:[{id,label,kind,eindOutput}], edges:[{van,naar}]}}, each label in the names of
     *  {@code naamgeving}. */
    private static ObjectNode graafJson(RegelGraaf graaf, RegelSpraakModel model, Naamgeving naamgeving) {
        Map<String, List<Benoembaar>> leden = naamgeving.isCanoniek() ? Map.of() : ledenPerLabel(model);
        ObjectNode g = NF.objectNode();
        ArrayNode nodes = g.putArray("nodes");
        for (RegelGraaf.Node kn : graaf.nodes()) {
            ObjectNode nn = nodes.addObject();
            nn.put("id", kn.id());
            nn.put("label", naamgeving.isCanoniek() ? kn.label() : label(kn, leden, naamgeving));
            nn.put("kind", kn.kind().name());
            nn.put("eindOutput", kn.eindOutput());
        }
        ArrayNode edges = g.putArray("edges");
        for (RegelGraaf.Edge e : graaf.edges()) {
            ObjectNode en = edges.addObject();
            en.put("van", e.van());
            en.put("naar", e.naar());
        }
        return g;
    }

    /** A graph node whose declaration the label does not decide, while its candidates carry different names in the
     *  chosen vocabulary. */
    static final class OnbenoembareKnoop extends RuntimeException {
        OnbenoembareKnoop(String zin) { super(zin); }
    }

    /** The label of node {@code kn} in the chosen vocabulary. A form node is labelled with its form name, an element
     *  node with the member and its declaring type as uchar's graph spells them ({@code "<lid> · <type>"}, both
     *  normalised): the member is looked up among the attributes and characteristics of that type, by the node's kind,
     *  and an input node, which is either, by both. uchar's graph does not hand out the element behind a node, so an
     *  input node whose attribute and characteristic of one name are named differently is refused, never guessed. */
    private static String label(RegelGraaf.Node kn, Map<String, List<Benoembaar>> leden, Naamgeving naamgeving) {
        if (kn.kind() == RegelGraaf.NodeKind.REGEL) return naamgeving.vorm(kn.label());
        List<String> namen = new ArrayList<>();
        for (Benoembaar b : leden.getOrDefault(kn.label(), List.of())) {
            boolean kenmerk = b.soort() == Benoembaar.Soort.KENMERK;
            if (kn.kind() == RegelGraaf.NodeKind.ATTRIBUUT && kenmerk) continue;
            if (kn.kind() == RegelGraaf.NodeKind.KENMERK && !kenmerk) continue;
            String naam = naamgeving.naam(b) + " · " + naamgeving.objecttype(b.eigenaar());
            if (!namen.contains(naam)) namen.add(naam);
        }
        if (namen.isEmpty()) return kn.label();       // no declared member: nothing to name
        if (namen.size() > 1)
            throw new OnbenoembareKnoop("de graafknoop '" + kn.label() + "' is een attribuut of een kenmerk met die naam,"
                    + " en die heten in het vocabulaire '" + naamgeving.keuze() + "' verschillend: " + namen);
        return namen.get(0);
    }

    /** The attributes and characteristics of {@code model} by the label uchar's graph gives their element node. */
    private static Map<String, List<Benoembaar>> ledenPerLabel(RegelSpraakModel model) {
        Map<String, List<Benoembaar>> uit = new LinkedHashMap<>();
        for (Benoembaar b : model.benoembaar())
            if (b.soort() == Benoembaar.Soort.ATTRIBUUT || b.soort() == Benoembaar.Soort.KENMERK)
                uit.computeIfAbsent(Namen.norm(b.naam()) + " · " + Namen.norm(b.eigenaar()), k -> new ArrayList<>()).add(b);
        return uit;
    }

    /** True when the source carries a {@code Scenario} block. A raw substring test is intentionally cheap and
     *  conservative: a false positive (e.g. the word inside a name) only means the scenario runner is invoked and
     *  reports 0 scenarios, never a crash. */
    static boolean bevatScenario(String bron) {
        return bron != null && bron.contains(SCENARIO_KOP);
    }

    // ---------------- parse pass (keeps column + expected tokens) ----------------

    /** One syntax error with the position AND the ANTLR follow set (the expected tokens), which the shared
     *  Resolver drops. */
    record ParseFout(int regel, int kolom, String boodschap, String token, List<String> verwacht) {}

    /** The parse of a source: its syntax errors, the tree of its entry rule and the token stream under it, whose
     *  HIDDEN channel holds the line comments ({@link Declaraties}). */
    record Ontleding(List<ParseFout> fouten, ParserRuleContext document, CommonTokenStream tokens) {}

    /**
     * Parse {@code bron}, collecting a {@link ParseFout} per syntax error. Unlike {@link Resolver#resolve} (which
     * keeps only line + message), this listener recovers the offending token text and the parser's expected-token
     * set at the failure point, rendered with the grammar's literal/symbolic names — the "verwacht ..." hint a
     * learner needs. The entry rule is {@code testDocument} when {@code scenario} is set (so a Scenario/Situatie
     * block is admitted), else the production {@code regelSpraakDocument}.
     */
    static Ontleding parse(String bron, boolean scenario) {
        RegelSpraakLexer lexer = new RegelSpraakLexer(CharStreams.fromString(bron));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RegelSpraak parser = new RegelSpraak(tokens);

        List<ParseFout> fouten = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col,
                                              String msg, RecognitionException e) {
                String token = sym == null ? null : sym.toString();
                if (sym instanceof org.antlr.v4.runtime.Token t) token = t.getText();
                fouten.add(new ParseFout(line, col, msg, token, verwachteTokens(r, e)));
            }
        };
        lexer.removeErrorListeners(); lexer.addErrorListener(listener);
        parser.removeErrorListeners(); parser.addErrorListener(listener);
        ParserRuleContext document = scenario ? parser.testDocument() : parser.regelSpraakDocument();
        return new Ontleding(fouten, document, tokens);
    }

    /** The expected-token set at the failure point, rendered as grammar names. Uses the recognition exception's
     *  own expected set (the exact follow set) when present, else the parser's current expected set. A lexer error
     *  (no parser exception, {@code r} is a lexer) yields an empty list — there is no follow set. */
    private static List<String> verwachteTokens(Recognizer<?, ?> r, RecognitionException e) {
        IntervalSet verwacht = null;
        if (e != null) verwacht = e.getExpectedTokens();
        if (verwacht == null && r instanceof Parser p) verwacht = p.getExpectedTokens();
        List<String> uit = new ArrayList<>();
        if (verwacht == null || r == null) return uit;
        var vocab = r.getVocabulary();
        for (int type : verwacht.toArray()) {
            String lit = vocab.getLiteralName(type);
            String sym = vocab.getSymbolicName(type);
            uit.add(lit != null ? lit : (sym != null ? sym : "<" + type + ">"));
        }
        return uit;
    }

    private static String boodschap(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
