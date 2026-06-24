package io.github.yhavinga.regelspraak.transpiler.model;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;
import io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime;

import org.drools.model.DSL;
import org.drools.model.Global;
import org.drools.model.Model;
import org.drools.model.PatternDSL;
import org.drools.model.Rule;
import org.drools.model.RuleItemBuilder;
import org.drools.model.Variable;
import org.drools.model.functions.Block2;
import org.drools.model.functions.Block3;
import org.drools.model.functions.Block4;
import org.drools.model.functions.Function1;
import org.drools.model.functions.Predicate1;
import org.drools.model.functions.Predicate2;
import org.drools.model.impl.ModelImpl;

import org.drools.core.base.accumulators.BigDecimalSumAccumulateFunction;
import org.drools.core.base.accumulators.CountAccumulateFunction;
import org.drools.core.base.accumulators.MaxAccumulateFunction;
import org.drools.core.base.accumulators.MinAccumulateFunction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The EXECUTABLE-MODEL back-end (migration roadmap 20260624) — produces an {@link org.drools.model.Model}
 * (programmatic rules, Java lambdas) from the SAME resolved IR {@code DrlTranspiler} consumes
 * ({@code model.resolvedRegels} etc.). Replaces emitting DRL text. {@code DrlTranspiler} stays the GOLDEN
 * oracle; the differential harness fires both and asserts agreement.
 *
 * <p><b>Phase A scope</b> (the core rule spine): an assignment/kenmerk rule whose voorwaarde is null or a single
 * scalar {@link Conditie.Vergelijking} (own numeric attribute vs a numeric Parameter-global or Literal), and
 * whose value is a kenmerk's {@code true}, a Literal, an own-attribute read, or a {@code +} of those. Anything
 * outside scope is SKIPPED and recorded in {@link #overgeslagen()} — never silently mis-rendered (the marker
 * analog). Later phases (conditions/aggregations/fout/verdeling/temporal/creatie) extend this dispatch.
 */
public final class ExecModelTranspiler {

    public static final String PKG = "io.github.yhavinga.regelspraak.generated";
    static final String FACTS = "io.github.yhavinga.regelspraak.facts";

    private final RegelSpraakModel model;
    private final Map<String, Global<BigDecimal>> globals = new LinkedHashMap<>();
    private final List<String> overgeslagen = new ArrayList<>();
    /** Monotonic within ONE rule (reset per {@link #regel}) — keeps every {@code .expr}/declaration id unique so
     *  two constraints on the SAME attribute (e.g. an ALLE of {@code leeftijd > 5} and {@code leeftijd < 18}) do
     *  not collide on a shared id and silently drop one (Drools shares constraints by id). */
    private int seq;

    /** A lowered voorwaarde: the subject pattern with its in-pattern constraints, plus zero or more SEPARATE
     *  rule items (the §8.1.7 far-side {@code exists}/{@code not} joins) appended after it — the model analog of
     *  {@code DrlTranspiler.LoweredConditie}'s inline + extra split. */
    private record Gate(PatternDSL.PatternDef pattern, List<RuleItemBuilder<?>> extra) {}

    private ExecModelTranspiler(RegelSpraakModel m) { this.model = m; }

    public static Model transpile(RegelSpraakModel m) { return new ExecModelTranspiler(m).run(); }
    /** As {@link #transpile} but also exposes the skipped-construct reasons (for the differential harness to
     *  decide a fixture is "not yet migrated" rather than a divergence). */
    public static ExecModelTranspiler build(RegelSpraakModel m) { ExecModelTranspiler t = new ExecModelTranspiler(m); t.model0 = t.run(); return t; }
    private Model model0;
    public Model model() { return model0; }
    public List<String> overgeslagen() { return overgeslagen; }

    private Model run() {
        ModelImpl m = new ModelImpl(PKG);
        for (Parameter p : model.parameters.values()) {
            if (p.granulariteit() != null) { overgeslagen.add("parameter (tijdlijn): " + p.naam()); continue; }
            Global<BigDecimal> g = DSL.globalOf(BigDecimal.class, PKG, ident(p.naam()));
            globals.put(ident(p.naam()), g);
            m.addGlobal(g);
        }
        for (ResolvedRegel r : model.resolvedRegels) {
            Rule rule = regel(r);
            if (rule != null) m.addRule(rule);
        }
        // the other resolved kinds (objectcreatie/verdeling/consistentie/uniciteit/feitcreatie) are later phases
        int later = model.objectCreaties.size() + model.verdelingen.size() + model.consistenties.size()
                + model.uniciteiten.size() + model.uniciteitUnies.size() + model.feitCreaties.size();
        if (later > 0) overgeslagen.add(later + " niet-regel resultaatdelen (objectcreatie/verdeling/consistentie/uniciteit/feitcreatie) — latere fase");
        return m;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule regel(ResolvedRegel r) {
        if (!(r.doel() instanceof Expr.AttribuutRef doel) || doel.navigatieRol() != null || doel.objecttype() == null) {
            return skip("regel (doel geen eigen attribuut/kenmerk): " + r.naam());
        }
        if (!io.github.yhavinga.regelspraak.ir.Geldigheid.ALTIJD.equals(r.geldigheid()))
            return skip("regel (versie-geldigheid): " + r.naam());  // §4.2 window — later phase
        Class<?> factClass = factClass(doel.objecttype());
        if (factClass == null) return skip("regel (geen fact-class " + doel.objecttype().naam + "): " + r.naam());

        String doelProp = doel.attribuut();
        seq = 0;
        Variable sVar = DSL.declarationOf(factClass, "$subject");
        PatternDSL.PatternDef pattern = PatternDSL.pattern(sVar);
        List<RuleItemBuilder<?>> extra = new ArrayList<>();

        if (r.voorwaarde() != null) {
            Gate gate = voorwaarde(r.voorwaarde(), pattern, doel.objecttype(), sVar);
            if (gate == null) return skip("regel (voorwaarde buiten Phase-B scope): " + r.naam());
            pattern = gate.pattern();
            extra.addAll(gate.extra());
        }

        RuleItemBuilder<?> consequence;
        // §5.8/§13.4.17 AGGREGATION value (SOM/TOTAAL/AANTAL/MIN/MAX over related elements): an accumulate CE binds
        // the result in the when, read in the consequence — distinct from a scalar fact-local value.
        if (isAggregatieWaarde(r) && r.waarde() instanceof Expr.Functie fx) {
            Agg agg = aggregatie(fx, doel.objecttype(), sVar);
            if (agg == null) return skip("regel (aggregatie buiten Phase-C scope): " + r.naam());
            extra.add(agg.accItem());
            BigDecimal vf = valueFactor(fx.type(), doel.type());   // §4 restate the aggregate into the doel unit
            if (agg.isSom()) {
                // §5.8.2 sommatie: an empty sum (0 non-empty contributors) is the leegwaarde fallback, not 0.
                Variable cntVar = agg.countVar(), sumVar = agg.resultVar();
                BigDecimal fallback = agg.somFallback();
                Block4<org.drools.model.Drools, Object, Object, Object> body = (drools, s, cnt, sum) -> {
                    BigDecimal v = ((Number) cnt).longValue() == 0L ? fallback : RegelSpraakRuntime.toBd(sum);
                    if (vf != null && v != null) v = v.multiply(vf);
                    RegelSpraakRuntime.set(s, doelProp, v);
                    drools.update(s);
                };
                consequence = DSL.on(sVar, (Variable) cntVar, (Variable) sumVar).execute(body);
            } else {
                Variable resVar = agg.resultVar();
                Block3<org.drools.model.Drools, Object, Object> body = (drools, s, res) -> {
                    BigDecimal v = RegelSpraakRuntime.toBd(res);
                    if (vf != null && v != null) v = v.multiply(vf);
                    RegelSpraakRuntime.set(s, doelProp, v);
                    drools.update(s);
                };
                consequence = DSL.on(sVar, (Variable) resVar).execute(body);
            }
        } else {
            // scalar value path: a WaardeFn(subject, global-values -> value) plus the Parameter-globals it reads.
            WaardeFn wf = switch (r.soort()) {
                case KENMERKTOEKENNING -> WaardeFn.constant(Boolean.TRUE);      // "is <kenmerk>" -> kenmerk == true
                case GELIJKSTELLING, BEREKENING, INITIALISATIE -> scalarWaarde(r.waarde());
                default -> null;
            };
            if (wf == null) return skip("regel (waarde buiten scope): " + r.naam());
            // §4 value-side unit restatement (a kenmerk value is Boolean, no unit). Mirrors DrlTranspiler.omrekenWaarde.
            if (r.soort() != ResolvedRegel.Soort.KENMERKTOEKENNING) {
                BigDecimal vf = valueFactor(r.waarde().type(), doel.type());
                if (vf != null) wf = wf.scaledBy(vf);
            }
            // resolve the referenced globals; an unregistered one (e.g. a temporal parameter) -> honest skip.
            List<Global<BigDecimal>> globalVars = new ArrayList<>();
            for (String gn : wf.globals()) {
                Global<BigDecimal> g = globals.get(gn);
                if (g == null) return skip("regel (THEN-waarde refereert niet-geregistreerde global " + gn + "): " + r.naam());
                globalVars.add(g);
            }
            if (globalVars.size() > 1) return skip("regel (THEN-waarde refereert >1 global — latere fase): " + r.naam());
            BiFunction<Object, Map<String, BigDecimal>, Object> fn = wf.fn();
            if (globalVars.isEmpty()) {
                Block2<org.drools.model.Drools, Object> body = (drools, s) -> {
                    RegelSpraakRuntime.set(s, doelProp, fn.apply(s, Map.of()));
                    drools.update(s);
                };
                consequence = DSL.on(sVar).execute(body);
            } else {
                String gn = wf.globals().iterator().next();
                Block3<org.drools.model.Drools, Object, Object> body = (drools, s, gv) -> {
                    RegelSpraakRuntime.set(s, doelProp, fn.apply(s, Map.of(gn, (BigDecimal) gv)));
                    drools.update(s);
                };
                consequence = DSL.on(sVar, (Variable) globalVars.get(0)).execute(body);
            }
        }

        // The subject pattern first (binds $subject), then far-side exists/not joins and any accumulate (which
        // reference $subject), then the consequence. NO_LOOP is mandatory (the modify must not re-fire this rule).
        List<RuleItemBuilder<?>> items = new ArrayList<>();
        items.add(pattern);
        items.addAll(extra);
        items.add(consequence);
        return PatternDSL.rule(r.naam())
                .attribute(Rule.Attribute.NO_LOOP, true)
                .build(items.toArray(new RuleItemBuilder[0]));
    }

    private static boolean isAggregatieWaarde(ResolvedRegel r) {
        return (r.soort() == ResolvedRegel.Soort.GELIJKSTELLING || r.soort() == ResolvedRegel.Soort.BEREKENING
                || r.soort() == ResolvedRegel.Soort.INITIALISATIE)
                && r.waarde() instanceof Expr.Functie fx && isAggregatieKind(fx.kind());
    }
    private static boolean isAggregatieKind(Expr.FunctieKind k) {
        return k == Expr.FunctieKind.SOM || k == Expr.FunctieKind.TOTAAL || k == Expr.FunctieKind.AANTAL
                || k == Expr.FunctieKind.MINIMUM || k == Expr.FunctieKind.MAXIMUM;
    }

    /** A lowered aggregation: the accumulate rule item, the result Variable, and (for §5.8.2 SOM) the contributor
     *  count Variable + the empty-sum fallback. */
    private record Agg(RuleItemBuilder<?> accItem, Variable resultVar, Variable countVar, BigDecimal somFallback, boolean isSom) {}

    /** §5.8/§13.4.17 lower an aggregation over the subject's related elements to a {@code DSL.accumulate} CE: the
     *  source pattern joins the element to {@code $subject} (the §9.4 backref role), an optional §13.4.2 die-filter
     *  becomes a source constraint, and the accumulate function (count / BigDecimal sum / min / max) binds the
     *  result. Mirrors DrlTranspiler.accumulate. null -> skip (unresolvable join, unstructured filter, a temporal /
     *  multi-operand fold, or a non-literal SOM fallback) — never a silently-wrong aggregate over EVERY instance. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Agg aggregatie(Expr.Functie f, Objecttype subject, Variable subjectVar) {
        Objecttype elem = f.elementObjecttype();
        if (elem == null) return null;
        Class<?> elemClass = factClass(elem);
        if (elemClass == null) return null;
        boolean count = f.kind() == Expr.FunctieKind.AANTAL;
        // §5.8.3 a multi-operand min/max, or a multi-operand count without a resolved union filter, drops operands -> skip.
        if ((f.kind() == Expr.FunctieKind.MINIMUM || f.kind() == Expr.FunctieKind.MAXIMUM) && f.argumenten().size() > 1) return null;
        if (count && f.argumenten().size() > 1 && f.filter() == null) return null;
        Expr.AttribuutRef arg = !f.argumenten().isEmpty() && f.argumenten().get(0) instanceof Expr.AttribuutRef a ? a : null;
        // a SOM/MIN/MAX over a TEMPORAL attribute would aggregate the List<Segment> itself -> Phase F skip.
        if (arg != null && arg.objecttype() != null && arg.objecttype().attribuutGranulariteit.containsKey(arg.attribuut())) return null;
        if (!count && arg == null) return null;   // sum/min/max need an attribute to fold

        // §9.4 the join role binding the element to the subject (a count may name its element role explicitly).
        String rol = null;
        if (subject != null && arg != null && count) rol = model.verbindendeRolViaElementnaam(subject, arg.attribuut());
        if (rol == null && subject != null) rol = model.verbindendeRol(elem, subject);
        if (rol == null) return null;
        final String backref = rol;

        Variable elemVar = DSL.declarationOf(elemClass, "$e_" + (seq++));
        PatternDSL.PatternDef src = PatternDSL.pattern(elemVar).expr(nextId("join"), (Variable) subjectVar,
                (Predicate2) (e, subj) -> RegelSpraakRuntime.get(e, backref) == subj);
        if (f.filter() != null) {                       // §13.4.2 die/dat <predicaat> on the element
            Predicate1<Object> fp = leafPredicate(f.filter(), elem);
            if (fp == null) return null;                // unstructured filter -> skip (mirror the DRL marker)
            src = src.expr(nextId("filter"), (Predicate1) fp);
        }

        Variable resultVar = DSL.declarationOf(Object.class, "$acc_" + (seq++));
        if (count)
            return new Agg(DSL.accumulate(src, DSL.accFunction(CountAccumulateFunction.class).as(resultVar)),
                    resultVar, null, null, false);

        final String attr = arg.attribuut();
        // The fold always uses the BigDecimal accumulate variant: a Numeriek attribute maps to a BigDecimal getter
        // (the §6 exact-precision invariant), and BigDecimalSum/Min/Max skip nulls — matching what the DRL `sum`/
        // `min`/`max` keyword resolves to for a BigDecimal arg. (Seam, adversary-noted: if a future objecttype ever
        // lowered a Numeriek to a Double/Integer getter, DRL's keyword would fall back to the generic null-NPEing
        // Double sum while this stays BigDecimal — re-introduce an `attr != null` source guard if that invariant breaks.)
        Variable amtVar = DSL.declarationOf(BigDecimal.class, "$amt_" + (seq++));
        if (f.kind() == Expr.FunctieKind.SOM) {
            // §5.8.2: empty SOM -> the leegwaarde (null, or a literal "of N als die er niet zijn"), not 0. Filter to
            // non-empty contributors, count them, and sum; the consequence branches on the count.
            BigDecimal fallback = null;
            if (f.leegwaarde() != null) {
                if (!(f.leegwaarde() instanceof Expr.Literal lit) || !lit.type().isNumeriek()) return null;
                fallback = bd(lit);
                if (fallback == null) return null;
            }
            PatternDSL.PatternDef src2 = src.expr(nextId("nn"), (Predicate1) (e) -> RegelSpraakRuntime.get(e, attr) != null)
                    .bind(amtVar, (Function1) (e) -> RegelSpraakRuntime.get(e, attr));
            Variable cntVar = DSL.declarationOf(Object.class, "$cnt_" + (seq++));
            RuleItemBuilder acc = DSL.accumulate(src2,
                    DSL.accFunction(CountAccumulateFunction.class).as(cntVar),
                    DSL.accFunction(BigDecimalSumAccumulateFunction.class, amtVar).as(resultVar));
            return new Agg(acc, resultVar, cntVar, fallback, true);
        }
        // TOTAAL (empty -> 0) / MIN / MAX: bind the per-element value and fold it.
        Class<?> accClass = switch (f.kind()) {
            case TOTAAL  -> BigDecimalSumAccumulateFunction.class;
            case MINIMUM -> MinAccumulateFunction.class;
            case MAXIMUM -> MaxAccumulateFunction.class;
            default      -> null;
        };
        if (accClass == null) return null;
        PatternDSL.PatternDef src2 = src.bind(amtVar, (Function1) (e) -> RegelSpraakRuntime.get(e, attr));
        return new Agg(DSL.accumulate(src2, DSL.accFunction(accClass, amtVar).as(resultVar)), resultVar, null, null, false);
    }

    /** Lower a voorwaarde into the subject pattern's in-pattern constraints (plus far-side exists/not joins as
     *  extra rule items). Returns a {@link Gate}, or null when ANY part is outside scope (caller skips the whole
     *  rule — never a partial gate). A constraint that needs a pattern variable (a comparison vs a Parameter-global)
     *  or hosts an exists-join lives HERE; every fact-only leaf flows through {@link #leafPredicate}, shared with
     *  the counting-quantifier path. A temporal (List<Segment>) operand SKIPS (Phase F) — never a List in a scalar lambda.
     *  Coverage: §13.4.14 ALLE/DE (AND), GEEN/TEN_MINSTE/TEN_HOOGSTE/PRECIES (counting), §8.1.7 near- and far-side
     *  rolcheck, §8.1.8 own + to-one navigated kenmerkcheck, §8.1.2 LEEG/GEVULD, §8.1.1 comparison vs global/literal. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Gate voorwaarde(Conditie c, PatternDSL.PatternDef pattern, Objecttype subject, Variable subjectVar) {
        if (c instanceof Conditie.Samengesteld s) {
            // §13.4.14 ALLE / DE: AND its parts as successive in-pattern constraints; carry up far-side joins.
            if (s.kwantor() == Conditie.Kwantor.ALLE || s.kwantor() == Conditie.Kwantor.DE) {
                List<RuleItemBuilder<?>> extra = new ArrayList<>();
                for (Conditie sub : s.onderdelen()) {
                    Gate g = voorwaarde(sub, pattern, subject, subjectVar);
                    if (g == null) return null;
                    pattern = g.pattern();
                    extra.addAll(g.extra());
                }
                return new Gate(pattern, extra);
            }
            // §13.4.14.4 counting quantifiers: GEEN = none hold, TEN_MINSTE/TEN_HOOGSTE/PRECIES = count vs N. The
            // children fold with ||/!/count, so a hoisted exists/not (or any non-leaf) cannot ride along — every
            // onderdeel must reduce to a fact-only Predicate1, else the WHOLE compound skips (never a partial gate).
            List<Predicate1<Object>> kinderen = new ArrayList<>();
            for (Conditie sub : s.onderdelen()) {
                Predicate1<Object> lp = leafPredicate(sub, subject);
                if (lp == null) return null;
                kinderen.add(lp);
            }
            final Conditie.Kwantor kw = s.kwantor();
            final int n = s.drempel();
            Predicate1<Object> combined = sObj -> {
                int telling = 0;
                for (Predicate1<Object> p : kinderen) if (p.test(sObj)) telling++;
                return switch (kw) {
                    case GEEN        -> telling == 0;
                    case TEN_MINSTE  -> telling >= n;
                    case TEN_HOOGSTE -> telling <= n;
                    case PRECIES     -> telling == n;
                    default          -> false;   // unreachable (alle/de handled above)
                };
            };
            return new Gate(pattern.expr(nextId("q"), (Predicate1) combined), List.of());
        }
        // §8.1.7 far-side "heeft een <rol>": the reference lives on the to-many filler, so this is an exists-join —
        // a SEPARATE rule item that cannot live inside the subject pattern. It binds the filler against $subject.
        if (c instanceof Conditie.VerreRollidmaatschap vr) {
            Class<?> fillerClass = factClass(vr.filler());
            if (fillerClass == null) return null;
            String backref = vr.backref();
            Variable fVar = DSL.declarationOf(fillerClass, "$filler_" + (seq++));
            PatternDSL.PatternDef fp = PatternDSL.pattern(fVar).expr(nextId("br"), (Variable) subjectVar,
                    (Predicate2) (f, subj) -> RegelSpraakRuntime.get(f, backref) == subj);
            RuleItemBuilder<?> item = vr.negatie() ? DSL.not(fp) : DSL.exists(fp);
            return new Gate(pattern, List.of(item));
        }
        // §8.1.1 a comparison vs a numeric Parameter-GLOBAL needs a 2-var expr (the global as a pattern variable),
        // so it stays here rather than in leafPredicate (a vs-literal comparison IS a fact-only leaf).
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() == null
                && v.rechts() instanceof Expr.ParameterRef pr) {
            if (subject != null && subject.attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> Phase F
            if (!isNumeriekOp(v.op())) return null;   // EERDER/LATER (datum) -> Phase later
            Global<BigDecimal> g = globals.get(ident(pr.parameter().naam()));
            if (g == null) return null;
            String attr = la.attribuut();
            Conditie.Operator op = v.op();
            BigDecimal[] f = restatement(la.type(), pr.parameter().datatype());  // §5.1 Tabel 32 unit restatement
            return new Gate(pattern.expr(nextId("c"), (Variable) g,
                    (Predicate2) (s, gv) -> vergelijk(scale(num(s, attr), f[0]), scale((BigDecimal) gv, f[1]), op)), List.of());
        }
        // every other supported condition is a fact-only leaf.
        Predicate1<Object> lp = leafPredicate(c, subject);
        if (lp != null) return new Gate(pattern.expr(nextId("c"), (Predicate1) lp), List.of());
        return null;
    }

    /** A condition that reduces to a fact-only {@code Predicate1<subject>} (no pattern variable, no exists-join), or
     *  null when it is outside scope / needs a global / hoists a join. Shared by the single-constraint path and the
     *  counting-quantifier fold, so the two stay in lock-step. Covers §8.1.8 own + to-one navigated kenmerkcheck,
     *  §8.1.2 LEEG/GEVULD on an own attribute, §8.1.1 comparison of an own attribute vs a numeric literal, and the
     *  §8.1.7 near-side rolcheck. A temporal (List<Segment>) operand returns null (Phase F). */
    private Predicate1<Object> leafPredicate(Conditie c, Objecttype subject) {
        // §8.1.8 kenmerkcheck — an own kenmerk (boolean property == !negatie) or a to-one navigated one
        // ("zijn partner heeft een <k>"), read null-safely through the reference (a null relation never matches).
        if (c instanceof Conditie.Kenmerk k) {
            if (k.eigenaar() != null && k.eigenaar() != subject) return null;   // a feitcreatie doel-kenmerk hosts elsewhere
            Objecttype kHouder = k.navigatie() == null ? subject
                    : (subject != null ? model.rolDoeltypeNaarSubject(subject, k.navigatie()) : null);
            if (kHouder != null && kHouder.kenmerkGranulariteit.containsKey(k.kenmerk())) return null;  // temporal -> Phase F
            String kenmerk = k.kenmerk();
            boolean wanted = !k.negatie();
            if (k.navigatie() == null)
                return s -> Boolean.TRUE.equals(RegelSpraakRuntime.get(s, kenmerk)) == wanted;
            String nav = k.navigatie();
            return s -> {
                Object rel = RegelSpraakRuntime.get(s, nav);
                return rel != null && Boolean.TRUE.equals(RegelSpraakRuntime.get(rel, kenmerk)) == wanted;
            };
        }
        // §8.1.2 LEEG/GEVULD on an own attribute -> a null / non-null constraint.
        if (c instanceof Conditie.Predicaat p
                && (p.soort() == Conditie.Predicaatsoort.LEEG || p.soort() == Conditie.Predicaatsoort.GEVULD)
                && p.operand() instanceof Expr.AttribuutRef pa && pa.navigatieRol() == null) {
            if (subject != null && subject.attribuutGranulariteit.containsKey(pa.attribuut())) return null;  // temporal -> Phase F
            String attr = pa.attribuut();
            boolean leeg = p.soort() == Conditie.Predicaatsoort.LEEG;
            return s -> (RegelSpraakRuntime.get(s, attr) == null) == leeg;
        }
        // §8.1.1 an own-attribute scalar comparison vs a numeric literal.
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() == null
                && v.rechts() instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            if (subject != null && subject.attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> Phase F
            if (!isNumeriekOp(v.op())) return null;   // EERDER/LATER (datum) -> Phase later
            BigDecimal rhs = bd(lit);
            if (rhs == null) return null;
            String attr = la.attribuut();
            Conditie.Operator op = v.op();
            BigDecimal[] f = restatement(la.type(), lit.type());   // §5.1 Tabel 32 unit restatement (e.g. mnd vs jr)
            BigDecimal rhsScaled = scale(rhs, f[1]);
            return s -> vergelijk(scale(num(s, attr), f[0]), rhsScaled, op);
        }
        // §8.1.7 near-side rolcheck: the subject fills the role iff its to-ONE forward reference is set. The far-side
        // (the reference lives on the MANY side) is not a fact-only leaf -> null (handled as an exists-join above).
        if (c instanceof Conditie.Rollidmaatschap r) {
            String veld = subject != null ? model.verbindendeRol(subject, r.anderTeFiller()) : null;
            if (veld == null || model.naarVeelRol(subject, r.anderTeFiller(), veld)) return null;
            boolean gevuldWanted = !r.negatie();
            return s -> (RegelSpraakRuntime.get(s, veld) != null) == gevuldWanted;
        }
        return null;
    }

    /** A unique id within the current rule (see {@link #seq}). */
    private String nextId(String prefix) { return prefix + "_" + (seq++); }

    /** A lowered scalar value: a {@code (subject, globalValuesByName) -> value} function plus the set of
     *  Parameter-global names it reads (so {@link #regel} can bind them on the consequence). */
    private record WaardeFn(BiFunction<Object, Map<String, BigDecimal>, Object> fn, Set<String> globals) {
        static WaardeFn constant(Object v) { return new WaardeFn((s, gm) -> v, Set.of()); }
        /** A copy whose numeric result is multiplied by a unit factor (null result stays null — a leegwaarde). */
        WaardeFn scaledBy(BigDecimal f) {
            BiFunction<Object, Map<String, BigDecimal>, Object> inner = fn;
            return new WaardeFn((s, gm) -> { Object v = inner.apply(s, gm); return v == null ? null : ((BigDecimal) v).multiply(f); }, globals);
        }
    }

    /** Scalar value: Literal | own-attr read | Parameter-global read | a {@code +} of those (rsNul leegwaarde).
     *  null -> skip. The global read pulls its value from the consequence-bound map (see {@link #regel}). */
    private WaardeFn scalarWaarde(Expr e) {
        if (e instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            BigDecimal val = bd(lit);
            return val == null ? null : WaardeFn.constant(val);
        }
        if (e instanceof Expr.AttribuutRef a && a.navigatieRol() == null) {
            String attr = a.attribuut();
            return new WaardeFn((s, gm) -> RegelSpraakRuntime.get(s, attr), Set.of());
        }
        if (e instanceof Expr.ParameterRef pr) {
            String gn = ident(pr.parameter().naam());
            return new WaardeFn((s, gm) -> gm.get(gn), Set.of(gn));
        }
        if (e instanceof Expr.Binair b && b.op() == Expr.BinOp.PLUS) {
            WaardeFn lf = scalarWaarde(b.links()), rf = scalarWaarde(b.rechts());
            if (lf == null || rf == null) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> lfn = lf.fn(), rfn = rf.fn();
            Set<String> g = new LinkedHashSet<>(lf.globals()); g.addAll(rf.globals());
            return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsNul(lfn.apply(s, gm)).add(RegelSpraakRuntime.rsNul(rfn.apply(s, gm))), g);
        }
        return null;
    }

    /** §4 value-side unit conversion factor (van -> naar), or null when no conversion applies — the mirror of
     *  DrlTranspiler.omrekenFactor. Restates the value expression in the doel attribute's unit before assignment. */
    private static BigDecimal valueFactor(Datatype van, Datatype naar) {
        Eenheid ev = van.eenheidOf(), en = naar.eenheidOf();
        if (ev == null || en == null || !ev.convertibleNaar(en) || !ev.vereistOmrekening(en)) return null;
        return ev.omrekenfactorNaar(en);
    }

    // ---- small helpers ----
    private static BigDecimal num(Object bean, String attr) { return (BigDecimal) RegelSpraakRuntime.get(bean, attr); }
    /** Parse a numeric literal's value, ignoring any trailing unit ("18 jr" -> 18, "3,5 EUR" -> 3.5; comma is the
     *  RegelSpraak decimal mark). Returns null for a non-decimal (e.g. a fraction) so the caller skips it (Phase A). */
    private static BigDecimal bd(Expr.Literal lit) {
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^\\s*([-+]?\\d[\\d.]*)").matcher(lit.waarde().replace(",", "."));
        if (!mm.find()) return null;
        try { return new BigDecimal(mm.group(1)); } catch (NumberFormatException e) { return null; }
    }
    /** The six numeric comparison operators the model lowers; EERDER/LATER (datum) are a later phase (caller skips). */
    private static boolean isNumeriekOp(Conditie.Operator op) {
        return switch (op) {
            case KLEINER, KLEINER_OF_GELIJK, GROTER, GROTER_OF_GELIJK, GELIJK, ONGELIJK -> true;
            default -> false;
        };
    }
    /** Compare two (already unit-restated) numbers with the SAME null semantics the DRL/mvel constraint has —
     *  the subtle case being {@code !=}: mvel reads {@code null != 18} as TRUE (a leeg value DIFFERS from a number),
     *  so ONGELIJK must be true when exactly one operand is leeg, while {@code ==} is false there and BOTH-leeg makes
     *  {@code ==} true / {@code !=} false. The ordering ops do NOT match when either operand is leeg (mvel returns
     *  false, no NPE). Getting this wrong flips firing decisions under the negated quantifiers (geen/precies/ten hoogste). */
    private static boolean vergelijk(BigDecimal a, BigDecimal b, Conditie.Operator op) {
        boolean aNull = a == null, bNull = b == null;
        return switch (op) {
            case GELIJK            -> aNull || bNull ? (aNull && bNull) : a.compareTo(b) == 0;
            case ONGELIJK          -> aNull || bNull ? !(aNull && bNull) : a.compareTo(b) != 0;
            case KLEINER           -> !aNull && !bNull && a.compareTo(b) < 0;
            case KLEINER_OF_GELIJK -> !aNull && !bNull && a.compareTo(b) <= 0;
            case GROTER            -> !aNull && !bNull && a.compareTo(b) > 0;
            case GROTER_OF_GELIJK  -> !aNull && !bNull && a.compareTo(b) >= 0;
            default                -> false;  // EERDER/LATER — never reached (isNumeriekOp gates the caller)
        };
    }
    /** §5.1 Tabel 32: the multipliers that restate the left/right comparison operands into a common unit before
     *  comparing (so "24 mnd > 2 jr" compares 24 vs 24, not 24 vs 2) — the BigDecimal mirror of the DRL oracle's
     *  {@code vergelijkingsOperanden}: no conversion when either side is unitless / not convertible / already the
     *  same unit; both sides to their canonical unit for simple units; the rate (noemer) unit restates only the
     *  right in the left's unit. The factors are the DECLARED exact factors, so the multiply is lossless. */
    private static BigDecimal[] restatement(Datatype lt, Datatype rt) {
        Eenheid el = lt.eenheidOf(), er = rt.eenheidOf();
        if (el == null || er == null || !el.convertibleNaar(er) || !el.vereistOmrekening(er))
            return new BigDecimal[]{BigDecimal.ONE, BigDecimal.ONE};
        if (el.noemer() == null && er.noemer() == null)
            return new BigDecimal[]{nz(el.factorNaarCanoniek()), nz(er.factorNaarCanoniek())};
        return new BigDecimal[]{BigDecimal.ONE, nz(er.omrekenfactorNaar(el))};  // composite (rate) unit: restate rhs in lhs unit
    }
    private static BigDecimal nz(BigDecimal f) { return f == null ? BigDecimal.ONE : f; }
    /** Restate one operand by its unit factor; a leeg (null) operand stays leeg (no NPE on .multiply). */
    private static BigDecimal scale(BigDecimal x, BigDecimal f) { return x == null ? null : x.multiply(f); }
    private Rule skip(String reason) { overgeslagen.add(reason); return null; }
    static Class<?> factClass(Objecttype ot) {
        try { return Class.forName(FACTS + "." + RegelSpraakRuntime.pascal(ot.naam)); }
        catch (ClassNotFoundException e) { return null; }
    }
    static String ident(String naam) {
        String s = naam.replaceFirst("^(?i)(de|het|een)\\s+", "").trim();
        String[] w = s.split("\\s+");
        StringBuilder b = new StringBuilder(w[0]);
        for (int i = 1; i < w.length; i++) if (!w[i].isEmpty()) b.append(Character.toUpperCase(w[i].charAt(0))).append(w[i].substring(1));
        return b.toString();
    }
}
