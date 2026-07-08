package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.runtime.CreationMarker;
import io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime;
import io.github.yhavinga.regelspraak.runtime.RuleFired;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import org.drools.modelcompiler.KieBaseBuilder;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * THE DIFFERENTIAL FIRING WALK over the corpus (the spec-completeness sweep's residue: "corpus semantic-asserting
 * walk — replace the smoke-walk's silent fragment-skip"). Today the corpus is PARSED ({@code make verify-corpus})
 * and TRANSPILE-swept ({@link DrlTest#corpus_drl_bevat_geen_enkele_function_definitie}); NO corpus program was ever
 * FIRED. This walk fires the ones that CAN be fired on BOTH back-ends and asserts they agree.
 *
 * <p><b>What this net asserts (and, per the repo's differential-masks-shared-bugs lesson, what it does NOT).</b>
 * It asserts, over REAL corpus programs: (1) both back-ends BUILD (DRL compiles, exec Model builds), (2) both
 * FIRE without throwing, (3) both TERMINATE under a fixed fire-cap, and (4) they reach the SAME canonical
 * end-state. It does NOT assert SPEC VALUES — that job belongs to the per-construct absolute-value fixtures
 * ({@link TokaVuurTest}, {@link ExecModelDifferentialTest}). Parity alone can mask a bug shared by both
 * back-ends; this walk is still valuable because it catches ASYMMETRIC regressions on real corpus programs
 * (a change that makes one back-end diverge, crash, or loop where the other does not) — the very class of bug
 * the per-construct fixtures, hand-curated and narrow, do not sweep over the whole corpus for.
 *
 * <p><b>Fact-class mechanism (chosen, with the reason).</b> The exec back-end resolves a fact class via
 * {@code Class.forName("io.github.yhavinga.regelspraak.facts." + Pascal(naam))} against the TRANSPILER's own classloader
 * (the application classloader) — it has no hook to consume an alternate, per-file classloader. So the
 * generate-and-in-memory-compile mechanism ({@link FactModelGenerator} + {@link FactModelGeneratorTest#compileer},
 * which {@link TokaVuurTest} rides) feeds ONLY the DRL back-end (via {@code KieHelper.setClassLoader}); the exec
 * Model never sees the generated beans, so it cannot fire differentially against them without a production-code
 * change to thread a classloader into {@code factClass} (out of scope). We therefore take the task's stated
 * fallback: RESTRICT the WALKED set to corpus files whose objecttypen ALL map onto an existing hand-written
 * {@code io.github.yhavinga.regelspraak.facts.*} class (both back-ends resolve them identically), and seed facts of those
 * hand-written beans deterministically.
 *
 * <p><b>Structural finding: the corpus is fragment-per-construct.</b> The census below shows the corpus is
 * extracted as one construct PER FILE (an objecttype declaration in one file, the rules over it in others). A
 * single fragment rarely combines an object model WITH the rules over it, so (a) rules whose target attribute
 * is declared in a sibling file are HONEST-SKIPPED by the exec back-end ("doel geen eigen attribuut/kenmerk"),
 * and (b) the files that DO fully-lower on both back-ends declare NO objecttypen at all (bare consistentie /
 * feitcreatie rules). Assembling the whole spec-main into one program does not parse (36 syntax errors — the
 * fragments overlap and offer alternative declarations); the assembled TOKA collides on names the hand-written
 * pool renders differently (ContingentTreinmiles) and a §174 date-arith error, which is exactly why
 * {@link TokaVuurTest} uses GENERATED beans and scenario subsets. The net result: the WALKED bucket fires real
 * corpus rule text against a (currently empty) working memory and confirms build+fire+terminate+parity; the
 * per-construct absolute-value assertions live in {@link TokaVuurTest}.
 *
 * <p><b>BUCKET CENSUS (the b56bab6 no-silent-narrowing discipline — EXACT counts, a file moving buckets FAILS).</b>
 * Over the 201 extracted {@code .rs} files:
 * <ul>
 *   <li>resolver-error = 59 (fragment resolves with ERRORs alone — e.g. a rule over an undeclared attribute)</li>
 *   <li>no-rules = 54 (declarations-only fragment: objecttype/feittype/parameter/eenheidsysteem/beslistabel-shell)</li>
 *   <li>honest-skip = 79 (fully parses + has rules, but exec {@code overgeslagen} non-empty OR DRL has a TODO —
 *       overwhelmingly "doel geen eigen attribuut/kenmerk": the rule's target lives in a sibling fragment)</li>
 *   <li>WALKED = 9 (fully lowers on BOTH back-ends AND every objecttype maps to a hand-written fact class)</li>
 * </ul>
 * Example WALKED files: spec-main-90 (controleer minderjarig), spec-main-111/toka-30 (rondvlucht ongelijk),
 * spec-main-119/toka-38 (Kerstdag), toka-29 (passagier met recht op treinmiles). The four counts sum to 202.
 */
class CorpusVuurTest {

    // ---- EXACT bucket floors (the b56bab6 discipline: a file moving buckets must FAIL so drift is visible). ----
    private static final int VERWACHT_RESOLVER_ERROR = 59;
    private static final int VERWACHT_NO_RULES        = 55;
    private static final int VERWACHT_HONEST_SKIP     = 79;
    private static final int VERWACHT_WALKED          = 9;

    /** The fire cap (mirrors the engine's 10_000): a regressed rule-loop hits it, and hitting it is a FAILURE
     *  naming the file, never a silent truncation. */
    private static final int FIRE_CAP = 10_000;

    /** The fixed rekendatum for any tijdgebonden corpus program (§4.2). Both back-ends register it as a global. */
    private static final LocalDate REKENDATUM = LocalDate.of(2024, 1, 1);

    private static Class<?> factClass(Objecttype ot) {
        try { return Class.forName(FactModelGenerator.PAKKET + "." + RegelSpraakRuntime.pascal(ot.naam)); }
        catch (ClassNotFoundException e) { return null; }
    }

    @Test void differential_firing_walk_over_de_corpus() throws Exception {
        Path root = Path.of("examples");
        // Key on the EXTRACTED corpus (spec-main is gitignored, produced by tools/extract_corpus.py); a fresh
        // clone lacking it would fail the exact floors below instead of skipping.
        assumeTrue(Files.isDirectory(root.resolve("spec-main")),
                "extracted corpus absent (run tools/extract_corpus.py / make verify-corpus)");

        List<String> resolverError = new ArrayList<>();
        List<String> noRules = new ArrayList<>();
        List<String> honestSkip = new ArrayList<>();   // "naam :: reason"
        List<String> walked = new ArrayList<>();

        List<Path> files;
        try (var s = Files.walk(root)) {
            // examples/toka-scenarios/ holds scenariotoetsen fixtures (docs/SCENARIOTOETSEN.md) — a DOCUMENTED
            // EXTENSION reachable only via the `testDocument` front-end, NOT the production `regelSpraakDocument`
            // resolver this walk exercises. A bare scenario .rs is not standalone-resolvable (it needs its
            // companion model files + the scenario runner), so it belongs to no production bucket; excluding the
            // directory keeps this walk a pure production-corpus census and the 59/54/79/9 partition byte-identical
            // (the G6 isolation the scenario extension guarantees).
            files = s.filter(x -> x.toString().endsWith(".rs"))
                     .filter(x -> !x.toString().contains("toka-scenarios"))
                     .sorted().toList();
        }

        for (Path f : files) {
            String naam = root.relativize(f).toString();
            RegelSpraakModel m = Resolver.resolve(Files.readString(f));

            if (!m.errors().isEmpty()) { resolverError.add(naam); continue; }
            if (m.regels.isEmpty())    { noRules.add(naam); continue; }

            // BOTH back-ends. A crash building EITHER is a REAL FINDING — fail naming the file.
            String drl;
            ExecModelTranspiler em;
            try {
                drl = DrlTranspiler.transpile(m);
            } catch (RuntimeException e) {
                throw new AssertionError("DRL-transpile CRASH op corpusbestand " + naam + ": " + e, e);
            }
            try {
                em = ExecModelTranspiler.build(m);
            } catch (RuntimeException e) {
                throw new AssertionError("exec-model-build CRASH op corpusbestand " + naam + ": " + e, e);
            }

            // The two back-ends knowingly diverge on markers/skips — firing those differentially would need an
            // asymmetry allowlist (out of scope). A file that either back-end has not fully lowered is an HONEST,
            // RECORDED skip (never a silent continue).
            if (!em.overgeslagen().isEmpty() || drl.contains("/* TODO")) {
                honestSkip.add(naam + " :: " + (em.overgeslagen().isEmpty() ? "DRL /* TODO" : em.overgeslagen()));
                continue;
            }

            // Fully lowered. Every objecttype must map onto a hand-written fact class (the exec back-end resolves
            // fact classes against the app classloader — see the class javadoc). If any does not, HONEST-skip it
            // with the missing names rather than firing half a model.
            List<String> missing = new ArrayList<>();
            for (Objecttype ot : m.objecttypen.values())
                if (factClass(ot) == null) missing.add(ot.naam);
            if (!missing.isEmpty()) {
                honestSkip.add(naam + " :: geen hand-geschreven fact-class voor " + missing);
                continue;
            }

            // WALK IT: fire both back-ends over identically-seeded facts and compare canonical end-states.
            List<Object> drlFacts = seed(m);
            List<Object> execFacts = seed(m);   // fresh beans per back-end (pristine working memory each)
            List<String> drlEnd = fireEnState(drlBase(m, drl), m, drlFacts, naam, "DRL");
            List<String> execEnd = fireEnState(KieBaseBuilder.createKieBaseFromModel(em.model()), m, execFacts, naam, "exec");
            assertEquals(drlEnd, execEnd,
                    () -> "back-ends DIVERGE op corpusbestand " + naam + ":\n  DRL  = " + drlEnd + "\n  exec = " + execEnd);
            walked.add(naam);
        }

        // ---- the census (recorded so a run always PRINTS the bucketing, per the b56bab6 discipline). ----
        System.out.println("[corpus-vuur] resolver-error=" + resolverError.size()
                + " no-rules=" + noRules.size() + " honest-skip=" + honestSkip.size()
                + " WALKED=" + walked.size() + " (van " + files.size() + " .rs)");
        System.out.println("[corpus-vuur] WALKED: " + walked);

        // EXACT floors: a file moving buckets fails HERE so the drift is visible and must be re-blessed.
        assertEquals(VERWACHT_RESOLVER_ERROR, resolverError.size(),
                () -> "resolver-error-bucket verschoven: " + resolverError);
        assertEquals(VERWACHT_NO_RULES, noRules.size(),
                () -> "no-rules-bucket verschoven: " + noRules);
        assertEquals(VERWACHT_HONEST_SKIP, honestSkip.size(),
                () -> "honest-skip-bucket verschoven:\n  " + String.join("\n  ", honestSkip));
        assertEquals(VERWACHT_WALKED, walked.size(),
                () -> "WALKED-bucket verschoven: " + walked);
        assertEquals(files.size(),
                resolverError.size() + noRules.size() + honestSkip.size() + walked.size(),
                "de vier buckets moeten ALLE .rs-bestanden partitioneren");
    }

    // ---- deterministic fact synthesis (NO Math.random / no Date.now — fixed values only) --------------------

    /** One hand-written-bean instance per objecttype, seeded deterministically, wired by the unambiguous to-one
     *  feittype relations. Numeric attrs 1,2,3,… in declaration order; datum attrs 2024-01-01 + i days; tekst
     *  "w&lt;i&gt;"; booleans false. Currently every WALKED corpus file declares NO objecttypen, so this returns
     *  an empty list — but the seeding is spec-faithful for the day a self-contained fragment appears. */
    private static List<Object> seed(RegelSpraakModel m) throws Exception {
        Map<Objecttype, Object> beans = new LinkedHashMap<>();
        for (Objecttype ot : m.objecttypen.values()) {
            Class<?> fc = factClass(ot);
            if (fc == null) continue;   // guarded by the caller; defensive
            Object bean = fc.getConstructor().newInstance();
            int i = 1;
            for (Map.Entry<String, Datatype> e : ot.attributen.entrySet()) {
                if (!ot.dimensiesVan(e.getKey()).isEmpty()) continue;             // §3.6 gedimensioneerd — skip
                if (ot.attribuutGranulariteit.containsKey(e.getKey())) continue;  // §13.3.6 tijdreeks — skip
                Object v = seedValue(e.getValue(), i);
                if (v != null) invokeSetter(bean, e.getKey(), v);
                i++;
            }
            beans.put(ot, bean);
        }
        // wire the unambiguous to-one relations: for every ordered pair reachable through exactly one feittype
        // over a NON-to-many role, set the houder's reference to the doelwit bean (the same authority
        // FactModelGenerator derived the scalar reference field from).
        for (Objecttype houder : m.objecttypen.values()) {
            for (Objecttype doelwit : m.objecttypen.values()) {
                if (houder == doelwit) continue;
                String rol = m.verbindendeRol(houder, doelwit);
                if (rol == null || m.naarVeelRol(houder, doelwit, rol)) continue;
                Object hb = beans.get(houder), db = beans.get(doelwit);
                if (hb != null && db != null) invokeSetter(hb, rol, db);
            }
        }
        return new ArrayList<>(beans.values());
    }

    private static Object seedValue(Datatype t, int i) {
        if (t.isNumeriek() || t.isPercentage()) return new BigDecimal(i);
        if (t.isTekst() || t instanceof Datatype.Enumeratie) return "w" + i;
        if (t instanceof Datatype.Booleaan) return Boolean.FALSE;
        if (t instanceof Datatype.DatumInDagen) return LocalDate.of(2024, 1, 1).plusDays(i);
        if (t instanceof Datatype.DatumTijd) return LocalDate.of(2024, 1, 1).plusDays(i).atStartOfDay();
        return null;
    }

    private static void invokeSetter(Object bean, String logischeNaam, Object waarde) throws Exception {
        String prop = io.github.yhavinga.regelspraak.transpiler.kern.Namen.ident(logischeNaam, false);
        String setter = "set" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        for (Method mm : bean.getClass().getMethods()) {
            if (mm.getName().equals(setter) && mm.getParameterCount() == 1
                    && mm.getParameterTypes()[0].isInstance(waarde)) {
                mm.invoke(bean, waarde);
                return;
            }
        }
        // no matching setter (e.g. a to-many-only reference): leave unset — the reverse to-one wiring covers it.
    }

    // ---- firing + canonical end-state -----------------------------------------------------------------------

    private final java.util.IdentityHashMap<KieBase, Map<String, java.util.function.Predicate<LocalDate>>>
            dagsoortenPerBase = new java.util.IdentityHashMap<>();

    private KieBase drlBase(RegelSpraakModel m, String drl) {
        KieHelper h = new KieHelper();
        h.addContent(drl, ResourceType.DRL);
        assertFalse(h.verify().hasMessages(org.kie.api.builder.Message.Level.ERROR),
                () -> "DRL compileert niet:\n" + h.verify().getMessages() + "\n---\n" + drl);
        DrlTranspiler t = DrlTranspiler.build(m);
        KieBase kb = h.build();
        if (!t.dagsoortGlobalen().isEmpty()) dagsoortenPerBase.put(kb, t.dagsoortGlobalen());
        return kb;
    }

    /** Fire over the seeded facts (a fixed rekendatum + fixed parameter-global values), CAP the fire, then map
     *  every session object to a sorted canonical descriptor. A crash or hitting the cap = FAILURE naming the file. */
    private List<String> fireEnState(KieBase kb, RegelSpraakModel m, List<Object> facts, String naam, String backend) {
        KieSession s = kb.newKieSession();
        dagsoortenPerBase.getOrDefault(kb, Map.of()).forEach(s::setGlobal);
        // parameter globals + rekendatum: both back-ends declare the same names; set fixed deterministic values.
        for (Parameter p : m.parameters.values()) {
            try { s.setGlobal(io.github.yhavinga.regelspraak.transpiler.kern.Namen.ident(p.naam(), false), globalValue(p)); }
            catch (RuntimeException ignore) { /* a global the rules never reference is not declared on the base */ }
        }
        try { s.setGlobal("rekendatum", REKENDATUM); } catch (RuntimeException ignore) { /* not tijdgebonden */ }

        for (Object o : facts) s.insert(o);
        int fired;
        try {
            fired = s.fireAllRules(FIRE_CAP);
        } catch (RuntimeException e) {
            s.dispose();
            throw new AssertionError(backend + "-fire CRASH op corpusbestand " + naam + ": " + e, e);
        }
        assertTrue(fired < FIRE_CAP,
                () -> backend + "-fire bereikte de fire-cap " + FIRE_CAP + " op corpusbestand " + naam + " (regel-loop?)");
        List<String> end = s.getObjects().stream()
                .filter(o -> !(o instanceof CreationMarker) && !(o instanceof RuleFired))   // control facts: count, don't compare
                .map(CorpusVuurTest::canon)
                .sorted().toList();
        s.dispose();
        return end;
    }

    private static Object globalValue(Parameter p) {
        if (p.granulariteit() != null) return List.of();                 // a tijdreeks parameter — empty timeline
        Object v = seedValue(p.datatype(), 7);                            // a fixed non-1 value, distinct from attrs
        return v != null ? v : BigDecimal.ZERO;
    }

    /** class-simple-name + sorted getter-name=value pairs, so two fresh instances across the back-ends compare
     *  by VALUE. BigDecimal via stripTrailingZeros().toPlainString(); Temporal via toString; List&lt;Segment&gt;
     *  canonicalized elementwise; everything else via String.valueOf. */
    static String canon(Object o) {
        StringBuilder b = new StringBuilder(o.getClass().getSimpleName()).append('{');
        TreeSet<String> paren = new TreeSet<>();
        for (Method mm : o.getClass().getMethods()) {
            String n = mm.getName();
            if (mm.getParameterCount() != 0) continue;
            if (mm.getDeclaringClass() == Object.class) continue;
            if (!(n.startsWith("get") || n.startsWith("is"))) continue;
            if (n.equals("getClass")) continue;
            String prop = n.startsWith("get") ? n.substring(3) : n.substring(2);
            Object v;
            try { v = mm.invoke(o); } catch (Exception e) { continue; }
            paren.add(prop + "=" + canonValue(v));
        }
        return b.append(String.join(",", paren)).append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String canonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof Temporal) return v.toString();
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object e : l) out.add(canon(e));   // List<Segment> (or any bean list) elementwise
            return out.toString();
        }
        // a reference to another fact bean: use its identity-free canon to avoid recursion loops on cyclic wiring
        if (v.getClass().getName().startsWith(FactModelGenerator.PAKKET + "."))
            return v.getClass().getSimpleName() + "@ref";
        return String.valueOf(v);
    }
}
