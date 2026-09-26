package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.DekkingRegel;
import dev.uchar.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Vuring;
import dev.uchar.regelspraak.toolchain.engine.ScenarioRunner;
import dev.uchar.regelspraak.toolchain.engine.UcharBouw;
import dev.uchar.regelspraak.graaf.ProducentConsument;
import dev.uchar.regelspraak.graaf.RegelGraaf;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;
import dev.uchar.regelspraak.model.RegelSpraakModel.Severity;
import dev.uchar.regelspraak.parser.RegelSpraak;
import dev.uchar.regelspraak.parser.RegelSpraakLexer;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import dev.uchar.regelspraak.transpiler.kern.Dekking;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.ArrayList;
import java.util.List;

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
 *       {@code dekking} per form, or {@code false} with uchar's {@code weigering};</li>
 *   <li><b>drl</b> — the DRL text of uchar's DRL-text branch, a view and no verdict (that branch is narrower than
 *       the one that fires); {@code drlFout} carries its refusal;</li>
 *   <li><b>graaf</b> and <b>graafJson</b> — uchar's rule graph of the document its loader reads back (the route
 *       uchar's gate 15 holds equal to the source route), as Mermaid source and as
 *       {@code {nodes:[{id,label,kind,eindOutput}], edges:[{van,naar}]}}, so a workbench can take a rule's dataflow
 *       neighbourhood without parsing Mermaid;</li>
 *   <li><b>scenario</b> — only when the source contains a {@code Scenario} block: per scenario uchar's report
 *       (status, per expectation verwacht/werkelijk, the coverage), its {@code trace} (per fired rule the labels it
 *       bound and how often) and its {@code uitleg} ({@link ScenarioRunner#runMetUitleg}), or {@code uitlegFout}.</li>
 * </ol>
 *
 * <p><b>Scenario-aware front-end.</b> {@code Situatie}/{@code Scenario} blocks are a documented extension of uchar's
 * frontend (its {@code grammar/DIVERGENCES.md}) reachable only via the scenario entry rule, NOT part of the
 * production {@code regelSpraakDocument}. So when a source carries such a block the analyzer parses + resolves it
 * through the scenario front-end ({@link ScenarioResolver}) and runs the scenarios off that same resolved bundle.
 *
 * <p>This class owns NO web plumbing and NO persistence. A refusal of uchar is part of the answer; any other failure
 * is not caught here, so the HTTP adapter ({@link DienstHttp}) answers it with 500 and logs it.
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
        ObjectNode root = NF.objectNode();
        boolean scenario = bevatScenario(bron);

        // 1. PARSE — a dedicated pass that KEEPS the column + expected-token set the shared Resolver discards. Uses
        //    the scenario entry rule when a Scenario block is present, so those keywords are not mis-flagged.
        List<ParseFout> parseFouten = parse(bron, scenario);
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
        } catch (UcharBouw.NietGedragen e) {
            root.put("uitvoerbaar", false);
            root.put("weigering", e.getMessage());
        }

        // 4. DRL — a view of the DRL-text branch, never the verdict
        try {
            root.put("drl", UcharBouw.bouw(model, DroolsBackend.Branch.DRL_TEXT).drlTranspiler().drl());
        } catch (UcharBouw.NietGedragen e) {
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
                root.set("graafJson", graafJson(graaf));
            } catch (IrLoader.Refusal | IrLoader.Documentfout e) {
                root.put("graaf", "");
                root.put("graafFout", "loader: " + boodschap(e));
            }
        }

        // 6. SCENARIO — only when the source carries a Scenario block
        if (scenario) {
            ArrayNode arr = root.putObject("scenario").putArray("resultaten");
            for (ScenarioRunner.Uitgelegd u : ScenarioRunner.runMetUitleg(bundel)) rendScenario(arr.addObject(), u);
        }
        return root;
    }

    private static void rendScenario(ObjectNode n, ScenarioRunner.Uitgelegd u) {
        ScenarioResultaat r = u.resultaat();
        n.put("naam", r.naam());
        n.put("status", r.status().name());
        if (r.diagnose() != null && !r.diagnose().isEmpty()) n.put("diagnose", r.diagnose());
        ArrayNode verwachtingen = n.putArray("verwachtingen");
        for (VerwachtingResultaat v : r.verwachtingen()) {
            ObjectNode vn = verwachtingen.addObject();
            vn.put("bron", v.bron().trim());
            vn.put("verwacht", v.verwacht());
            vn.put("werkelijk", v.werkelijk());
            vn.put("ok", v.ok());
        }
        ArrayNode dekking = n.putArray("dekking");
        for (DekkingRegel d : r.dekking()) {
            ObjectNode dn = dekking.addObject();
            dn.put("regel", d.regel());
            dn.put("soort", d.soort().name());
        }
        // the fired rules in the order they first fired, with the labels of the input instances they bound
        ArrayNode trace = n.putArray("trace");
        for (Vuring vuring : r.trace().vuringen()) {
            ObjectNode tn = trace.addObject();
            tn.put("regel", vuring.regel());
            ArrayNode labels = tn.putArray("labels");
            for (String l : vuring.labels()) labels.add(l);
            tn.put("aantal", vuring.aantal());
        }
        ArrayNode uitleg = n.putArray("uitleg");
        for (String z : u.uitleg()) uitleg.add(z);
        if (u.uitlegFout() != null) n.put("uitlegFout", u.uitlegFout());
    }

    /** The rule graph as {@code {nodes:[{id,label,kind,eindOutput}], edges:[{van,naar}]}}. */
    private static ObjectNode graafJson(RegelGraaf graaf) {
        ObjectNode g = NF.objectNode();
        ArrayNode nodes = g.putArray("nodes");
        for (RegelGraaf.Node kn : graaf.nodes()) {
            ObjectNode nn = nodes.addObject();
            nn.put("id", kn.id());
            nn.put("label", kn.label());
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

    /**
     * Parse {@code bron}, collecting a {@link ParseFout} per syntax error. Unlike {@link Resolver#resolve} (which
     * keeps only line + message), this listener recovers the offending token text and the parser's expected-token
     * set at the failure point, rendered with the grammar's literal/symbolic names — the "verwacht ..." hint a
     * learner needs. The entry rule is {@code testDocument} when {@code scenario} is set (so a Scenario/Situatie
     * block is admitted), else the production {@code regelSpraakDocument}.
     */
    static List<ParseFout> parse(String bron, boolean scenario) {
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
        if (scenario) parser.testDocument(); else parser.regelSpraakDocument();
        return fouten;
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
