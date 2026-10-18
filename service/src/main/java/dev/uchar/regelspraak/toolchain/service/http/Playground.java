package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.engine.ScenarioResult.CoverageRule;
import dev.uchar.regelspraak.engine.ScenarioResult.ExpectationResult;
import dev.uchar.regelspraak.engine.ScenarioResult.Firing;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.toolchain.engine.ScenarioRunner;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.EvaluationFault;
import dev.uchar.regelspraak.toolchain.service.FixpointGivens;
import dev.uchar.regelspraak.toolchain.service.NoFixpoint;
import dev.uchar.regelspraak.toolchain.service.Violation;
import dev.uchar.regelspraak.toolchain.service.RequestOptions;
import dev.uchar.regelspraak.graph.ProducerConsumer;
import dev.uchar.regelspraak.graph.RuleGraph;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;
import dev.uchar.regelspraak.model.RegelSpraakModel.GeneratedFrom;
import dev.uchar.regelspraak.model.RegelSpraakModel.Severity;
import dev.uchar.regelspraak.model.RegelSpraakModel.Vocabulary;
import dev.uchar.regelspraak.parser.RegelSpraak;
import dev.uchar.regelspraak.parser.RegelSpraakLexer;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundle;
import dev.uchar.regelspraak.transpiler.core.Coverage;
import dev.uchar.regelspraak.transpiler.core.Namen;

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
 *   <li><b>parse</b> — {@code ok} plus, per syntax error, its {@code position}/{@code column}, the offending token
 *       and the {@code verwacht} token set (the ANTLR follow set);</li>
 *   <li><b>resolver</b> — the resolver verdict: the ERROR and WARNING diagnostics (line + message).</li>
 * </ol>
 * A source with a parse or resolver error gets those two and nothing else: uchar's emitter writes no document for
 * it, so there is nothing to show, build or fire. A clean source also gets:
 * <ol start="3">
 *   <li><b>ir</b> — the {@code uchar-ir-v1} document uchar's emitter writes, or {@code irError} with the emitter's
 *       refusal;</li>
 *   <li><b>uitvoerbaar</b> — uchar's verdict on the exec-model branch, the branch that fires: {@code true} with the
 *       {@code dekking} per form and {@code generated}, the names of that coverage that name no element of
 *       {@code vocabularies} with the element uchar made them from ({@link #generated}), or {@code false} with
 *       uchar's {@code weigering};</li>
 *   <li><b>drl</b> — the DRL text of uchar's DRL-text branch, a view and no verdict (that branch renders the
 *       analysis the exec-model branch fires as DRL text); {@code drlFout} carries its refusal, or uchar's sentence
 *       for a DRL text the Drools compiler rejects;</li>
 *   <li><b>graaf</b> and <b>graphJson</b> — uchar's rule graph of the document its loader reads back (the route
 *       uchar's gate 15 holds equal to the source route), as Mermaid source and as
 *       {@code {nodes:[{id,label,kind,eindOutput}], edges:[{van,naar}]}}, so a workbench can take a rule's dataflow
 *       neighbourhood without parsing Mermaid;</li>
 *   <li><b>vocabulaires</b> — the vocabularies the source declares, the chosen one, and per declaration its
 *       designation in each, its source line and the comment above it, and for an element uchar made itself the
 *       element it comes from ({@link #vocabularies});</li>
 *   <li><b>scenario</b> — only when the source contains a {@code Scenario} block: per scenario uchar's report
 *       (status, per expectation verwacht/werkelijk, the coverage), its {@code trace} (per fired rule the labels it
 *       bound and how often) and its {@code uitleg} ({@link ScenarioRunner#runWithUitleg}), or {@code uitlegError};
 *       beside it the source line of the scenario and of each expectation, the {@code situatie} uchar's scenario
 *       resolver gives, and the {@code fixpunt} of the case's own session, or {@code fixpointError}
 *       ({@link ScenarioData}).</li>
 * </ol>
 *
 * <p><b>Vocabulary.</b> {@link #analyseer(String, String)} names the declarations in {@code graphJson}, the trace, the
 * per-scenario coverage, the uitleg, the situation and the fixpoint in a vocabulary the source declares
 * ({@link Naming}); the parse and resolver output, the IR, the verdict, the DRL and the Mermaid graph are uchar's
 * and stay as uchar writes them. A vocabulary the source does not declare is a {@link Naming.VocabularyError},
 * which the HTTP adapter answers with 400.
 *
 * <p><b>Scenario-aware front-end.</b> {@code Situatie}/{@code Scenario} blocks are a documented extension of uchar's
 * frontend (its {@code grammar/DIVERGENCES.md}) reachable only via the scenario entry rule, NOT part of the
 * production {@code regelSpraakDocument}. So when a source carries such a block the analyzer parses + resolves it
 * through the scenario front-end ({@link ScenarioResolver}) and runs the scenarios off that same resolved bundle.
 *
 * <p><b>Via the service.</b> {@link #analyseer(String, String, DecisionCore)} with a core gives the same tree, and only
 * the scenarios are computed by that core instead of uchar's scenario layer ({@link #renderViaService}): each becomes a
 * request of the service ({@link SituationToRequest}) to the entry point its scope maps to
 * ({@link DecisionCore#pathForScope}), and uchar compares the expectations of its case with the session of that
 * request ({@link DecisionCore#assess}). The core computes with its own sources, so a source whose
 * {@code uchar-ir-v1} document is not the core's ({@link DecisionCore#document}) is refused as a whole with a
 * {@link ServiceError}.
 *
 * <p>This class owns NO web plumbing and NO persistence. A refusal of uchar, and a DRL text of uchar that does not
 * compile, is part of the answer; any other failure is not caught here, so the HTTP adapter ({@link ServiceHttp})
 * answers it with 500 and logs it.
 */
public final class Playground {

    private Playground() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** The literal that opens a scenariotoets block (RegelSpraakLexer SCENARIO token). Its presence is what makes
     *  the analyzer run the scenario front-end/runner rather than only the production pipeline. */
    private static final String SCENARIO_HEAD = "Scenario";

    /** Analyze {@code source} and return the answer tree (see the class javadoc for its shape). */
    public static ObjectNode analyseer(String source) {
        return analyseer(source, null);
    }

    /** {@link #analyseer(String)} with the declarations named in {@code vocabulaire} (null or
     *  {@link Naming#CANONICAL}: canonically). */
    public static ObjectNode analyseer(String source, String vocabulary) {
        return analyseer(source, vocabulary, null);
    }

    /**
     * {@link #analyseer(String, String)} with the scenarios computed by {@code service} (null: by uchar's scenario layer,
     * as above). Each result then also names the {@code path} of the entry point it ran on.
     *
     * @throws ServiceError when the {@code uchar-ir-v1} document of {@code source}, without its scenarios, is not that of
     *                    {@code service}: the service computes with its own sources, so its verdict would be about other
     *                    rules than these
     */
    public static ObjectNode analyseer(String source, String vocabulary, DecisionCore service) {
        ObjectNode root = NF.objectNode();
        boolean scenario = containsScenario(source);

        // 1. PARSE — a dedicated pass that KEEPS the column + expected-token set the shared Resolver discards. Uses
        //    the scenario entry rule when a Scenario block is present, so those keywords are not mis-flagged.
        ParseResult parseResult = parse(source, scenario);
        List<ParseError> parseErrors = parseResult.errors();
        ObjectNode parse = root.putObject("parse");
        parse.put("ok", parseErrors.isEmpty());
        ArrayNode pf = parse.putArray("errors");
        for (ParseError f : parseErrors) {
            ObjectNode n = pf.addObject();
            n.put("line", f.position());
            n.put("column", f.column());
            n.put("message", f.notice());
            if (f.token() != null) n.put("token", f.token());
            ArrayNode expected = n.putArray("expected");
            for (String t : f.expected()) expected.add(t);
        }

        // 2. RESOLVER — for a scenario source the bundle's model carries the scenario diagnostics as well.
        ScenarioBundle bundle = scenario ? ScenarioResolver.resolve(source) : null;
        RegelSpraakModel model = scenario ? bundle.model() : Resolver.resolve(source);
        ObjectNode resolver = root.putObject("resolver");
        resolver.put("ok", model.isOk());
        ArrayNode errors = resolver.putArray("errors");
        ArrayNode warnings = resolver.putArray("warnings");
        for (Diagnostic d : model.diagnostics) {
            ObjectNode n = (d.severity() == Severity.ERROR ? errors : warnings).addObject();
            n.put("line", d.line());
            n.put("message", d.message());
        }
        if (!model.isOk()) return root;
        Naming naming = Naming.van(model, vocabulary);
        root.set("vocabularies", vocabularies(model, naming, parseResult));

        // 3. IR and the verdict of the branch that fires
        com.fasterxml.jackson.databind.JsonNode ir = null;
        try {
            ir = JSON.readTree(UcharBuild.document(model));
            root.set("ir", ir);
        } catch (UcharBuild.NotCarried e) {
            root.put("irError", e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("de emitter schreef geen leesbare JSON: " + e.getOriginalMessage(), e);
        }
        if (service != null) sameSources(ir, root.path("irError").asText(null), service);
        try {
            DroolsBackend.Build build = UcharBuild.assemble(model, DroolsBackend.Branch.EXEC_MODEL);
            root.put("executable", true);
            ArrayNode coverage = root.putArray("coverage");
            for (Coverage d : build.coverage()) {
                ObjectNode dn = coverage.addObject();
                dn.put("kind", d.category());
                dn.put("name", d.naam());
                dn.put("verdict", d.verdict().name());
            }
            root.set("generated", generated(build, model, naming));
        } catch (UcharBuild.NotCarried e) {
            root.put("executable", false);
            root.put("refusal", e.getMessage());
        }

        // 4. DRL — a view of the DRL-text branch, never the verdict. uchar's build of that branch throws an
        //    IllegalStateException for a DRL text the Drools compiler rejects, a fault of its rendering; the view
        //    shows uchar's sentence for it as it shows a refusal
        try {
            root.put("drl", UcharBuild.assemble(model, DroolsBackend.Branch.DRL_TEXT).drl());
        } catch (UcharBuild.NotCarried | IllegalStateException e) {
            root.put("drl", "");
            root.put("drlError", e.getMessage());
        }

        // 5. GRAAF — uchar's rule graph of the document its loader reads back, with the defaults groups and the
        //    navigation bindings it returns beside the model; without a document there is no graph
        if (ir == null) {
            root.put("graph", "");
            root.put("graphError", root.get("irError").asText());
        } else {
            try {
                IrLoader.Loaded loaded = IrLoader.loadAll(ir);
                RuleGraph graph = RuleGraph.build(loaded.model(), loaded.prioritizedDefaults(),
                        ProducerConsumer.wireNavigation(loaded));
                root.put("graph", graph.mermaid());
                try {
                    root.set("graphJson", graphJson(graph, model, naming));
                } catch (UndesignatableNode o) {
                    root.put("graphError", o.getMessage());
                }
            } catch (IrLoader.Refusal | IrLoader.DocumentError e) {
                root.put("graph", "");
                root.put("graphError", "loader: " + notice(e));
            }
        }

        // 6. SCENARIO — only when the source carries a Scenario block
        if (scenario && service != null) {
            ArrayNode arr = root.putObject("scenario").putArray("results");
            List<String> sourceLines = ScenarioData.lines(source);
            List<ResolvedScenario> scenarios = bundle.scenarios();
            for (int i = 0; i < scenarios.size(); i++)
                renderViaService(arr.addObject(), i, scenarios.get(i), service, sourceLines, model, naming, vocabulary);
        } else if (scenario) {
            ArrayNode arr = root.putObject("scenario").putArray("results");
            List<ResolvedScenario> scenarios = bundle.scenarios();
            List<ScenarioRunner.Explained> explained = ScenarioRunner.runWithUitleg(bundle, vocabulary);
            if (explained.size() != scenarios.size())
                throw new IllegalStateException("de scenariorunner gaf " + explained.size() + " rapport(en) voor "
                        + scenarios.size() + " scenario('s)");
            List<String> sourceLines = ScenarioData.lines(source);
            for (int i = 0; i < scenarios.size(); i++)
                rendScenario(arr.addObject(), explained.get(i), scenarios.get(i), sourceLines, model, naming);
        }
        return root;
    }

    private static void rendScenario(ObjectNode n, ScenarioRunner.Explained u, ResolvedScenario s,
                                     List<String> sourceLines, RegelSpraakModel model, Naming naming) {
        ScenarioResult r = u.result();
        if (!r.naam().equals(s.naam()))
            throw new IllegalStateException("het rapport '" + r.naam() + "' staat op de plaats van het scenario '"
                    + s.naam() + "'");
        // an expectation of the report is found by its position in the case, which the lowering writes one per
        // expectation of the resolved scenario, in its order
        int inCase = expectationsInCase(s);
        if (inCase != s.expectations().size())
            throw new IllegalStateException("de case van het scenario '" + s.naam() + "' telt " + inCase
                    + " verwachting(en), het scenario " + s.expectations().size());
        n.put("name", r.naam());
        n.put("status", r.status().name());
        if (r.diagnostic() != null && !r.diagnostic().isEmpty()) n.put("diagnosis", r.diagnostic());
        n.put("line", s.line());
        ArrayNode expectations = n.putArray("expectations");
        for (ExpectationResult v : r.expectations()) {
            ObjectNode vn = expectations.addObject();
            vn.put("source", v.source().trim());
            vn.put("expected", v.expected());
            vn.put("actual", v.actual());
            vn.put("ok", v.ok());
            ScenarioData.place(vn, v, s, sourceLines);
        }
        ArrayNode coverage = n.putArray("coverage");
        for (CoverageRule d : r.coverage()) {
            ObjectNode dn = coverage.addObject();
            dn.put("rule", naming.form(d.regel()));
            dn.put("kind", d.category().name());
        }
        // the fired rules in the order they first fired, with the labels of the input instances they bound
        ArrayNode trace = n.putArray("trace");
        for (Firing firing : r.trace().firings()) {
            ObjectNode tn = trace.addObject();
            tn.put("rule", naming.form(firing.regel()));
            ArrayNode labels = tn.putArray("labels");
            for (String l : firing.labels()) labels.add(l);
            tn.put("count", firing.count());
        }
        ArrayNode uitleg = n.putArray("explanation");
        for (String z : u.uitleg()) uitleg.add(z);
        if (u.uitlegError() != null) n.put("explanationError", u.uitlegError());
        n.set("situation", ScenarioData.situation(s, model, naming));
        if (u.session() != null) {
            try {
                n.set("fixpoint", ScenarioData.fixpoint(u.session(), s, model, naming));
            } catch (FixpointGivens.UnrenderableValue o) {
                n.put("fixpointError", o.getMessage());
            }
        } else if (u.sessionError() != null) {
            n.put("fixpointError", u.sessionError());
        }
    }

    /** The number of expectations in the case uchar's lowering writes for {@code s}. */
    private static int expectationsInCase(ResolvedScenario s) {
        return expectations(s).size();
    }

    /** The {@code expected} of the case uchar's lowering writes for {@code s}. */
    private static com.fasterxml.jackson.databind.JsonNode expectations(ResolvedScenario s) {
        try {
            return JSON.readTree(CaseEmitter.emit(s)).get("expected");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("de lowering schreef voor het scenario '" + s.naam()
                    + "' geen leesbare case: " + e.getOriginalMessage(), e);
        }
    }

    // ---------------- via the service ----------------

    /** Refuses with a {@link ServiceError} unless {@code ir}, the document of the posted source, equals that of
     *  {@code service} as a JSON tree: the same model, forms and source positions. {@code irError} is the emitter's
     *  refusal when there is no document. */
    private static void sameSources(com.fasterxml.jackson.databind.JsonNode ir, String irError, DecisionCore service) {
        String reason;
        if (ir == null) {
            reason = "uchars emitter schrijft van de geposte bronnen geen document: " + irError;
        } else {
            com.fasterxml.jackson.databind.JsonNode own;
            try {
                own = JSON.readTree(service.document());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("het document van de dienst is geen leesbare JSON: "
                        + e.getOriginalMessage(), e);
            }
            if (ir.equals(own)) return;
            reason = "het uchar-ir-v1-document van de geposte bronnen is niet dat van de dienst; eerste verschil "
                    + difference(ir, own, "");
        }
        throw new ServiceError("verzoek valideert niet", List.of(Violation.van(Violation.Kind.UNSUPPORTED, "source",
                "via=service rekent met de bronnen waarmee de dienst gestart is, en " + reason)));
    }

    /** The first place where {@code a} (the posted source's) and {@code b} (the service's) differ, as a JSON pointer
     *  with what each has there; null when they are equal. */
    static String difference(com.fasterxml.jackson.databind.JsonNode a, com.fasterxml.jackson.databind.JsonNode b,
                           String path) {
        if (a.equals(b)) return null;
        if (a.isObject() && b.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                String p = path + "/" + k;
                if (!a.has(k)) return p + ": ontbreekt in de geposte bronnen, de dienst heeft " + brief(b.get(k));
                if (!b.has(k)) return p + ": de geposte bronnen hebben " + brief(a.get(k)) + ", de dienst niets";
                String v = difference(a.get(k), b.get(k), p);
                if (v != null) return v;
            }
        }
        if (a.isArray() && b.isArray()) {
            Iterator<com.fasterxml.jackson.databind.JsonNode> ia = a.elements(), ib = b.elements();
            for (int i = 0; ia.hasNext() && ib.hasNext(); i++) {
                String v = difference(ia.next(), ib.next(), path + "/" + i);
                if (v != null) return v;
            }
            return path + ": " + a.size() + " elementen in de geposte bronnen, " + b.size() + " in de dienst";
        }
        return (path.isEmpty() ? "/" : path) + ": de geposte bronnen hebben " + brief(a) + ", de dienst " + brief(b);
    }

    private static String brief(com.fasterxml.jackson.databind.JsonNode n) {
        String t = n.toString();
        return t.length() <= 120 ? t : t.substring(0, 117) + "...";
    }

    /**
     * Scenario {@code s}, the {@code i}-th of the source, computed by {@code service}, in the shape {@link #rendScenario}
     * gives, with {@code path} after {@code position}: the entry point its scope maps to, null when none does. The
     * situation becomes a request ({@link SituationToRequest}) with canonical names, the answer is named in
     * {@code vocabulaire}, and uchar compares the expectations of the scenario's case with the request's session
     * ({@link DecisionCore#assess}): PASS or FAIL with uchar's diagnose, prefixed as uchar's report prefixes it with
     * the name of the case file. What the service does not compute is not a PASS:
     * <ul>
     *   <li>no entry point carries the scope, a value the request cannot carry ({@link SituationToRequest.NonPortable}),
     *       a request form the service does not carry (each violation of its 400 {@code UNSUPPORTED}), an entry
     *       point uchar does not carry, an expectation uchar does not compare: MARKER with the sentence;</li>
     *   <li>an evaluation fault (the service's 422): FAIL with the line of uchar's report, the exception its write-back
     *       throws, and the trace, coverage and fixpoint of the 422;</li>
     *   <li>a session without a fixpoint (the service's 422 {@code NO_FIXPOINT}): FAIL with the line uchar's report
     *       gives such a case, the name of the case file and uchar's sentence, and the trace and coverage of the 422;</li>
     *   <li>any other 400 of the service: FAIL naming its violations.</li>
     * </ul>
     */
    private static void renderViaService(ObjectNode n, int i, ResolvedScenario s, DecisionCore service,
                                      List<String> sourceLines, RegelSpraakModel model, Naming naming,
                                      String vocabulary) {
        String caseName = CaseEmitter.fileName(i + 1, s);
        com.fasterxml.jackson.databind.JsonNode expected = expectations(s);
        if (expected.size() != s.expectations().size())
            throw new IllegalStateException("de case van het scenario '" + s.naam() + "' telt " + expected.size()
                    + " verwachting(en), het scenario " + s.expectations().size());
        String path = null;
        ScenarioResult.Status status;
        String diagnostic = "";
        List<ExpectationResult> expectations = List.of();
        com.fasterxml.jackson.databind.JsonNode session = NF.objectNode();   // trace, dekking, uitleg, fixpunt
        String uitlegError = null;
        try {
            path = service.pathForScope(s.scope());
            RequestOptions options = new RequestOptions(true, vocabulary == null ? Naming.CANONICAL : vocabulary,
                    Naming.CANONICAL, true, true, true);
            DecisionCore.Assessment b = service.assess(path, SituationToRequest.assemble(s), options, expected);
            status = b.comparison().pass() ? ScenarioResult.Status.PASS : ScenarioResult.Status.FAIL;
            if (!b.comparison().pass()) diagnostic = caseName + ": " + b.comparison().diagnostic();
            expectations = b.comparison().expectations();
            session = b.answer();
        } catch (DecisionCore.NoEntryPoint | SituationToRequest.NonPortable | DecisionCore.NonExecutable
                 | CaseRunner.Excluded none) {
            status = ScenarioResult.Status.MARKER;
            diagnostic = none.getMessage();
        } catch (ServiceError error) {
            List<String> sentences = new ArrayList<>();
            boolean notSupported = true;
            for (Violation sch : error.violations()) {
                sentences.add(sch.toString());
                notSupported &= sch.kind() == Violation.Kind.UNSUPPORTED;
            }
            status = notSupported ? ScenarioResult.Status.MARKER : ScenarioResult.Status.FAIL;
            diagnostic = notSupported ? String.join("; ", sentences)
                    : caseName + ": de dienst weigert het verzoek: " + String.join("; ", sentences);
        } catch (EvaluationFault error) {
            Throwable uchar = error.getCause();
            status = ScenarioResult.Status.FAIL;
            diagnostic = caseName + ": ERROR " + uchar.getClass().getSimpleName() + ": "
                    + String.valueOf(uchar.getMessage()).replace('\n', ' ');
            session = error.detail();
            uitlegError = "de dienst gaf een evaluatiefout (422) en geeft dan geen uitleg";
        } catch (NoFixpoint none) {
            // uchar's report makes such a case a FAIL with its sentence after the name of the case file, no ERROR
            status = ScenarioResult.Status.FAIL;
            diagnostic = caseName + ": " + none.getMessage();
            session = none.detail();
            uitlegError = "de dienst bereikte geen fixpunt (422) en geeft dan geen uitleg";
        }
        n.put("name", s.naam());
        n.put("status", status.name());
        if (!diagnostic.isEmpty()) n.put("diagnosis", diagnostic);
        n.put("line", s.line());
        if (path == null) n.putNull("path"); else n.put("path", path);
        ArrayNode va = n.putArray("expectations");
        for (ExpectationResult v : expectations) {
            ObjectNode vn = va.addObject();
            vn.put("source", v.source().trim());
            vn.put("expected", v.expected());
            vn.put("actual", v.actual());
            vn.put("ok", v.ok());
            ScenarioData.place(vn, v, s, sourceLines);
        }
        for (String field : List.of("coverage", "trace", "explanation"))
            n.set(field, session.has(field) ? session.get(field) : NF.arrayNode());
        if (uitlegError != null) n.put("explanationError", uitlegError);
        n.set("situation", ScenarioData.situation(s, model, naming));
        if (session.has("fixpoint")) n.set("fixpoint", session.get("fixpoint"));
        if (session.has("fixpointError")) n.put("fixpointError", session.get("fixpointError").asText());
    }

    /** The vocabularies of {@code model}: {@code gekozen}, the choice of {@code naming}
     *  ({@link Naming#CANONICAL} or a vocabulary), {@code declared}, each declared vocabulary with whether it
     *  is the default one and its language, and {@code elements}, every declaration a vocabulary can name in the
     *  model's order ({@link RegelSpraakModel#benoembaar}) with its kind, its owner and canonical name as declared, its
     *  {@code designations}: per declared vocabulary its designation there, or null where it has none and goes by its
     *  canonical name (an empty object without a declared vocabulary), and, where it has a declaration in the source,
     *  its {@code position} and the {@code comment} directly above it, read from the parse in {@code parseResult}
     *  ({@link Declarations}). An element or a declaration that does not couple puts {@code declarationError} with the
     *  reason, and then no element carries {@code position} or {@code comment}. An element uchar's frontend made itself
     *  carries {@code generatedFrom}, the element it comes from ({@link RegelSpraakModel#generatedFrom}). */
    static ObjectNode vocabularies(RegelSpraakModel model, Naming naming, ParseResult parseResult) {
        ObjectNode n = NF.objectNode();
        n.put("chosen", naming.choice());
        ArrayNode declared = n.putArray("declared");
        List<Naming> per = new ArrayList<>();
        for (Vocabulary v : model.vocabularies) {
            ObjectNode vn = declared.addObject();
            vn.put("name", v.naam());
            vn.put("default", v.isDefault());
            if (v.language() == null) vn.putNull("language"); else vn.put("language", v.language());
            per.add(Naming.van(model, v.naam()));
        }
        Map<Designatable, Declarations.Declaration> declarations = Map.of();
        String declarationError = null;
        try {
            declarations = Declarations.van(parseResult.document(), parseResult.tokens(), model);
        } catch (Declarations.Unlinkable o) {
            declarationError = o.getMessage();
        }
        ArrayNode elements = n.putArray("elements");
        for (Designatable b : model.designatable()) {
            ObjectNode enumArray = elements.addObject();
            enumArray.put("kind", b.category().name());
            if (b.owner() == null) enumArray.putNull("owner"); else enumArray.put("owner", b.owner());
            enumArray.put("canonical", b.naam());
            ObjectNode designations = enumArray.putObject("designations");
            for (Naming g : per) {
                String t = g.designation(b);
                if (t == null) designations.putNull(g.choice()); else designations.put(g.choice(), t);
            }
            Declarations.Declaration d = declarations.get(b);
            if (d != null) {
                enumArray.put("line", d.line());
                ArrayNode comment = enumArray.putArray("comment");
                for (String c : d.comment()) comment.add(c);
            }
            model.generatedFrom(b).ifPresent(g -> enumArray.set("generatedFrom", generatedFrom(g)));
        }
        if (declarationError != null) n.put("declarationError", declarationError);
        return n;
    }

    /** Where an element uchar made comes from, as {@code {soort, eigenaar, naam, vorm}}: the kind, owner and name of the
     *  source element as declared (canonical, as an element of {@code vocabularies} carries them) and uchar's fixed key
     *  of the form that made it. */
    private static ObjectNode generatedFrom(GeneratedFrom g) {
        ObjectNode n = NF.objectNode();
        n.put("kind", g.source().category().name());
        if (g.source().owner() == null) n.putNull("owner"); else n.put("owner", g.source().owner());
        n.put("name", g.source().naam());
        n.put("form", g.form().key());
        return n;
    }

    /** The names of the coverage of {@code build} that name no element of {@link RegelSpraakModel#benoembaar}, each as
     *  {@code {naam, soort, generatedFrom}} in the order of the coverage, with uchar's origin of it
     *  ({@code TmsTranspiler.gegenereerd}): an effective defaults stack, a version of a form. {@code naam} is spelled
     *  as the coverage and trace of a scenario spell it ({@link Naming#form}), with {@code naamCanoniek} beside it
     *  where that differs from uchar's name; {@code soort} is the kind of the coverage. A name of the coverage that is
     *  no form of the model and has no origin in uchar's table is a fault of the analysis, not an answer. */
    static ArrayNode generated(DroolsBackend.Build build, RegelSpraakModel model, Naming naming) {
        Set<String> forms = new HashSet<>();
        for (Designatable b : model.designatable()) if (b.category() == Designatable.Kind.VORM) forms.add(b.naam());
        Map<String, GeneratedFrom> table = build.execModelTranspiler().generated();
        ArrayNode sink = NF.arrayNode();
        for (Coverage d : build.coverage()) {
            GeneratedFrom g = table.get(d.naam());
            if (g == null) {
                if (!forms.contains(d.naam()))
                    throw new IllegalStateException("de dekking noemt " + d.category() + " '" + d.naam() + "', geen "
                            + "vorm van het model, en uchar noemt de naam niet gegenereerd");
                continue;
            }
            ObjectNode n = sink.addObject();
            String chosen = naming.form(d.naam());
            n.put("name", chosen);
            if (!chosen.equals(d.naam())) n.put("nameCanonical", d.naam());
            n.put("kind", d.category());
            n.set("generatedFrom", generatedFrom(g));
        }
        return sink;
    }

    /** The rule graph as {@code {nodes:[{id,label,kind,eindOutput}], edges:[{van,naar}]}}, each label in the names of
     *  {@code naming}. */
    private static ObjectNode graphJson(RuleGraph graph, RegelSpraakModel model, Naming naming) {
        Map<String, List<Designatable>> members = naming.isCanonical() ? Map.of() : membersPerLabel(model);
        ObjectNode g = NF.objectNode();
        ArrayNode nodes = g.putArray("nodes");
        for (RuleGraph.Node kn : graph.nodes()) {
            ObjectNode nn = nodes.addObject();
            nn.put("id", kn.id());
            nn.put("label", naming.isCanonical() ? kn.label() : label(kn, members, naming));
            nn.put("kind", kn.kind().name());
            nn.put("endOutput", kn.endOutput());
        }
        ArrayNode edges = g.putArray("edges");
        for (RuleGraph.Edge e : graph.edges()) {
            ObjectNode enumArray = edges.addObject();
            enumArray.put("from", e.van());
            enumArray.put("to", e.destination());
        }
        return g;
    }

    /** A graph node whose declaration the label does not decide, while its candidates carry different names in the
     *  chosen vocabulary. */
    static final class UndesignatableNode extends RuntimeException {
        UndesignatableNode(String sentence) { super(sentence); }
    }

    /** The label of node {@code kn} in the chosen vocabulary. A form node is labelled with its form name, an element
     *  node with the member and its declaring type as uchar's graph spells them ({@code "<lid> · <type>"}, both
     *  normalised): the member is looked up among the attributes and characteristics of that type, by the node's kind,
     *  and an input node, which is either, by both. uchar's graph does not hand out the element behind a node, so an
     *  input node whose attribute and characteristic of one name are named differently is refused, never guessed. */
    private static String label(RuleGraph.Node kn, Map<String, List<Designatable>> members, Naming naming) {
        if (kn.kind() == RuleGraph.NodeKind.RULE) return naming.form(kn.label());
        List<String> names = new ArrayList<>();
        for (Designatable b : members.getOrDefault(kn.label(), List.of())) {
            boolean characteristic = b.category() == Designatable.Kind.KENMERK;
            if (kn.kind() == RuleGraph.NodeKind.ATTRIBUTE && characteristic) continue;
            if (kn.kind() == RuleGraph.NodeKind.CHARACTERISTIC && !characteristic) continue;
            String name = naming.name(b) + " · " + naming.objectType(b.owner());
            if (!names.contains(name)) names.add(name);
        }
        if (names.isEmpty()) return kn.label();       // no declared member: nothing to name
        if (names.size() > 1)
            throw new UndesignatableNode("de graafknoop '" + kn.label() + "' is een attribuut of een kenmerk met die naam,"
                    + " en die heten in het vocabulaire '" + naming.choice() + "' verschillend: " + names);
        return names.get(0);
    }

    /** The attributes and characteristics of {@code model} by the label uchar's graph gives their element node. */
    private static Map<String, List<Designatable>> membersPerLabel(RegelSpraakModel model) {
        Map<String, List<Designatable>> sink = new LinkedHashMap<>();
        for (Designatable b : model.designatable())
            if (b.category() == Designatable.Kind.ATTRIBUUT || b.category() == Designatable.Kind.KENMERK)
                sink.computeIfAbsent(Namen.norm(b.naam()) + " · " + Namen.norm(b.owner()), k -> new ArrayList<>()).add(b);
        return sink;
    }

    /** True when the source carries a {@code Scenario} block. A raw substring test is intentionally cheap and
     *  conservative: a false positive (e.g. the word inside a name) only means the scenario runner is invoked and
     *  reports 0 scenarios, never a crash. */
    static boolean containsScenario(String source) {
        return source != null && source.contains(SCENARIO_HEAD);
    }

    // ---------------- parse pass (keeps column + expected tokens) ----------------

    /** One syntax error with the position AND the ANTLR follow set (the expected tokens), which the shared
     *  Resolver drops. */
    record ParseError(int position, int column, String notice, String token, List<String> expected) {}

    /** The parse of a source: its syntax errors, the tree of its entry rule and the token stream under it, whose
     *  HIDDEN channel holds the line comments ({@link Declarations}). */
    record ParseResult(List<ParseError> errors, ParserRuleContext document, CommonTokenStream tokens) {}

    /**
     * Parse {@code source}, collecting a {@link ParseError} per syntax error. Unlike {@link Resolver#resolve} (which
     * keeps only line + message), this listener recovers the offending token text and the parser's expected-token
     * set at the failure point, rendered with the grammar's literal/symbolic names — the "verwacht ..." hint a
     * learner needs. The entry rule is {@code testDocument} when {@code scenario} is set (so a Scenario/Situatie
     * block is admitted), else the production {@code regelSpraakDocument}.
     */
    static ParseResult parse(String source, boolean scenario) {
        RegelSpraakLexer lexer = new RegelSpraakLexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RegelSpraak parser = new RegelSpraak(tokens);

        List<ParseError> errors = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col,
                                              String msg, RecognitionException e) {
                String token = sym == null ? null : sym.toString();
                if (sym instanceof org.antlr.v4.runtime.Token t) token = t.getText();
                errors.add(new ParseError(line, col, msg, token, expectedTokens(r, e)));
            }
        };
        lexer.removeErrorListeners(); lexer.addErrorListener(listener);
        parser.removeErrorListeners(); parser.addErrorListener(listener);
        ParserRuleContext document = scenario ? parser.testDocument() : parser.regelSpraakDocument();
        return new ParseResult(errors, document, tokens);
    }

    /** The expected-token set at the failure point, rendered as grammar names. Uses the recognition exception's
     *  own expected set (the exact follow set) when present, else the parser's current expected set. A lexer error
     *  (no parser exception, {@code r} is a lexer) yields an empty list — there is no follow set. */
    private static List<String> expectedTokens(Recognizer<?, ?> r, RecognitionException e) {
        IntervalSet expected = null;
        if (e != null) expected = e.getExpectedTokens();
        if (expected == null && r instanceof Parser p) expected = p.getExpectedTokens();
        List<String> sink = new ArrayList<>();
        if (expected == null || r == null) return sink;
        var vocab = r.getVocabulary();
        for (int type : expected.toArray()) {
            String lit = vocab.getLiteralName(type);
            String sym = vocab.getSymbolicName(type);
            sink.add(lit != null ? lit : (sym != null ? sym : "<" + type + ">"));
        }
        return sink;
    }

    private static String notice(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
