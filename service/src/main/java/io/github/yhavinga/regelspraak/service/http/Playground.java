package io.github.yhavinga.regelspraak.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.yhavinga.regelspraak.engine.ScenarioResultaat;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.DekkingRegel;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import io.github.yhavinga.regelspraak.engine.ScenarioRunner;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.parser.RegelSpraak;
import io.github.yhavinga.regelspraak.parser.RegelSpraakLexer;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;

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
 * The DEV-ONLY RegelSpraak playground core (a "type-and-see" workbench). It is a pure analyzer: given a
 * RegelSpraak source it returns ONE JSON tree carrying, in order, the four things a learner wants to see:
 * <ol>
 *   <li><b>parse</b> — {@code ok} plus, per syntax error, its {@code regel}/{@code kolom}, the offending token
 *       and the {@code verwacht} token set (the ANTLR follow set), so a beginner sees WHERE and WHAT was
 *       expected;</li>
 *   <li><b>resolver</b> — the {@link Resolver} verdict: the ERROR and WARNING diagnostics (line + message);</li>
 *   <li><b>drl</b> — the transpiled Drools text, present only when parse + resolve are clean (a broken model has
 *       no honest DRL);</li>
 *   <li><b>scenario</b> — only when the source contains a {@code Scenario} block: the in-proc
 *       {@link ScenarioRunner} outcome per scenario (status + per-expectation verwacht/werkelijk).</li>
 * </ol>
 *
 * <p><b>Scenario-aware front-end.</b> {@code Situatie}/{@code Scenario} blocks are a DOCUMENTED extension
 * ({@code docs/SCENARIOTOETSEN.md}) reachable only via the scenario entry rule, NOT part of the production
 * {@code regelSpraakDocument}. So when a source carries such a block the analyzer parses + resolves it through the
 * scenario front-end ({@link ScenarioResolver}) — whose {@code parse}/{@code resolver}/{@code drl} panes then
 * reflect the MODEL portion (definities/regels/beslistabellen), un-confused by the scenario keywords — and runs
 * the scenarios off that SAME resolved bundle. A production-only source keeps the plain {@link Resolver} path.
 *
 * <p>This class owns NO web plumbing and NO persistence: it never touches disk, holds no state between calls,
 * and every analysis runs compile-per-request. The HTTP adapter ({@link DienstHttp}) wires it behind an
 * env-gated route and imposes the request timeout. It reuses the SAME resolver / transpiler / scenario-runner
 * the shipped chain uses — it invents no RegelSpraak meaning of its own.
 */
public final class Playground {

    private Playground() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    /** The literal that opens a scenariotoets block (RegelSpraakLexer SCENARIO token). Its presence is what makes
     *  the analyzer run the scenario front-end/runner rather than only the production pipeline. */
    private static final String SCENARIO_KOP = "Scenario";

    /**
     * Analyze {@code bron} and return the single answer tree (see the class javadoc for the shape). Never throws
     * on RegelSpraak-level errors: a parse error, a resolve error, an un-transpilable model, and a scenario
     * FAIL/MARKER are all NORMAL, reported outcomes, not exceptions. An unexpected internal failure of the
     * scenario runner is caught and reported in the {@code scenario.fout} field rather than propagated, so the
     * workbench answers even on a runner surprise.
     */
    public static ObjectNode analyseer(String bron) {
        ObjectNode root = NF.objectNode();

        // A Scenario/Situatie block is a scenario-front-end construct, unknown to the production grammar. When one
        // is present the parse + resolve + DRL panes must read the source through the SCENARIO front-end (whose
        // model portion is the production model), or the scenario keywords would spuriously error every pane.
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

        // 2. RESOLVER — the shipped front-end verdict. For a scenario source the ScenarioResolver bundle carries the
        //    SAME model (built by the identical DefinitionResolver + RuleChecker) plus the scenario diagnostics; its
        //    model is what the DRL and scenario panes below read, so the whole answer stays consistent.
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

        // 3. DRL — only for a clean model (a broken model has no honest transpilation). The transpiler reads the
        //    resolved model; scenario blocks live outside the production model, so they never taint the DRL.
        if (model.isOk()) {
            try {
                root.put("drl", DrlTranspiler.transpile(model));
            } catch (RuntimeException e) {
                root.put("drl", "");
                root.put("drlFout", boodschap(e));
            }
        }

        // 4. SCENARIO — only when the source carries a Scenario block. Reuse the already-resolved bundle (the same
        //    core ScenarioRunnerCli drives). A resolve error inside the bundle is reported as scenario.fout.
        if (scenario) {
            ObjectNode scenarioNode = root.putObject("scenario");
            try {
                List<ScenarioResultaat> resultaten = ScenarioRunner.run(bundel);
                ArrayNode arr = scenarioNode.putArray("resultaten");
                for (ScenarioResultaat r : resultaten) rendScenario(arr.addObject(), r);
            } catch (ScenarioRunner.ResolveFout f) {
                ArrayNode diag = scenarioNode.putArray("diagnostiek");
                for (String d : f.diagnostiek()) diag.add(d);
                scenarioNode.put("fout", "de bron resolvet niet als scenariotoets");
            } catch (RuntimeException e) {
                scenarioNode.put("fout", boodschap(e));
            }
        }

        return root;
    }

    private static void rendScenario(ObjectNode n, ScenarioResultaat r) {
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
