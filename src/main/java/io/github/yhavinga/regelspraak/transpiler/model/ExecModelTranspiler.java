package io.github.yhavinga.regelspraak.transpiler.model;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedVerdeling;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;
import io.github.yhavinga.regelspraak.runtime.Accessors;
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
import org.drools.model.functions.Block5;
import org.drools.model.functions.Block6;
import org.drools.model.functions.Block7;
import org.drools.model.functions.Block8;
import org.drools.model.functions.Block9;
import org.drools.model.functions.Block10;
import org.drools.model.functions.Function1;
import org.drools.model.functions.Predicate1;
import org.drools.model.functions.Predicate2;
import org.drools.model.impl.ModelImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
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
    /** §9.8 dagsoortdefinities compiled to per-date predicates (the executable-model mirror of DrlTranspiler's
     *  generated {@code function boolean rsDagsoort_<Naam>}). Compiled lazily on first reference (§8.1.5 / §6
     *  duration); cross-dagsoort references resolve via a deferred map lookup so population order is irrelevant.
     *  {@link #dagsoortBezig} guards against infinite recursion on a (spec-forbidden, §9.10) cyclic definitie. */
    private final Map<String, java.util.function.Predicate<java.time.LocalDate>> dagsoortPredikaten = new LinkedHashMap<>();
    private final java.util.Set<String> dagsoortBezig = new java.util.HashSet<>();
    /** §13.3.6 temporal parameters: a tijdlijn parameter is a {@code List<Segment>} global (only a §7.3.2
     *  tijdsevenredig base reads it), kept apart from the scalar BigDecimal {@link #globals}. */
    private final Map<String, Global<java.util.List>> listGlobals = new LinkedHashMap<>();
    /** §4.2 the Rekendatum global (a {@code LocalDate}) — registered iff ANY construct is time-bounded (a dated
     *  {@code geldig vanaf/tot} version), read by the per-construct {@link #geldigGuard} window guard. null when
     *  every construct is {@code geldig altijd} (no guard needed). Mirrors DrlTranspiler's {@code rekendatum} global. */
    private Global<java.time.LocalDate> rekendatumGlobal;
    /** The subject a VALUE-path {@link #scalarWaarde} is lowering for, set tightly around the regel value-path call so
     *  a to-one NAVIGATED value read ("de X van zijn partner") can resolve its role. null everywhere else (the
     *  condition path, aggregation fallbacks, creatie/verdeling values) so a navigated read SKIPS there rather than
     *  resolving against a stale subject — the value side's getter-chain null semantics differ from the condition
     *  side's {@code !.}, so the two must not share this. */
    private Objecttype valueSubject;
    private final List<String> overgeslagen = new ArrayList<>();
    /** Monotonic within ONE rule (reset per rule) — keeps each rule's declaration NAMES ({@code $e_0}, {@code $inc_1})
     *  unique. Declaration names are per-rule scoped, so they may repeat across rules. */
    private int seq;
    /** Model-wide, NEVER reset — the source of {@link #nextId} constraint ids. Drools shares {@code .expr} constraints
     *  by id across the WHOLE package, so two DIFFERENT rules emitting the same id (e.g. the {@code (leeg gewicht)} and
     *  {@code (leeg of negatief maximum)} fout companions both starting at {@code fp_1}) would collapse to ONE
     *  constraint and silently use the first rule's predicate for both — the cross-rule analog of the within-rule
     *  id-collision. A global counter makes every constraint id unique, so no rule ever inherits another's predicate. */
    private int idSeq;

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

    @SuppressWarnings("rawtypes")
    private Model run() {
        ModelImpl m = new ModelImpl(PKG);
        for (Parameter p : model.parameters.values()) {
            if (p.granulariteit() != null) {
                // §13.3.6 a TEMPORAL parameter is a List<Segment> global — only a §7.3.2 tijdsevenredig base reads it.
                Global<java.util.List> g = DSL.globalOf(java.util.List.class, PKG, ident(p.naam()));
                listGlobals.put(ident(p.naam()), g);
                m.addGlobal(g);
                continue;
            }
            // §13.3.8 a scalar parameter is a global of its DECLARED Java type (mirrors DrlTranspiler.javaType), so the
            // session setGlobal supplies a value of the right class. Only a NUMERIC (BigDecimal) parameter is TRACKED in
            // `globals` — the only type the condition/value binding paths consume (they cast the bound value to
            // BigDecimal). A date/tekst/booleaan parameter is registered (so setGlobal works) but NOT tracked, so a
            // construct reading it stays an honest skip rather than a fire-time "Illegal class for global" crash.
            if (p.datatype().isNumeriek() || p.datatype().isPercentage()) {
                Global<BigDecimal> g = DSL.globalOf(BigDecimal.class, PKG, ident(p.naam()));
                globals.put(ident(p.naam()), g);
                m.addGlobal(g);
            } else {
                m.addGlobal(DSL.globalOf(globalKlasse(p.datatype()), PKG, ident(p.naam())));
            }
        }
        // §4.2 register the Rekendatum global iff any construct of any family is time-bounded (mirrors
        // DrlTranspiler's `tijdgebonden` test); each dated construct then guards its pattern via geldigGuard.
        if (heeftTijdgebonden()) {
            rekendatumGlobal = DSL.globalOf(java.time.LocalDate.class, PKG, "rekendatum");
            m.addGlobal(rekendatumGlobal);
        }
        for (ResolvedRegel r : model.resolvedRegels) {
            Rule rule = regel(r);
            if (rule != null) {
                m.addRule(rule);
                for (Rule comp : delingCompanions(r)) m.addRule(comp);   // §6.5 deling-door-leeg fout (Phase D)
                for (Rule comp : wortelCompanions(r)) m.addRule(comp);   // §6.6 wortel-van-negatief fout (Phase D)
            }
        }
        for (io.github.yhavinga.regelspraak.ir.ResolvedConsistentie c : model.consistenties) {   // §9.5 / §13.4.8 (Phase D)
            Rule rule = consistentie(c);
            if (rule != null) m.addRule(rule);
        }
        for (io.github.yhavinga.regelspraak.ir.ResolvedUniciteit u : model.uniciteiten) {        // §8.1.6 (Phase D)
            Rule rule = uniciteit(u);
            if (rule != null) m.addRule(rule);
        }
        for (io.github.yhavinga.regelspraak.ir.ResolvedVerdeling v : model.verdelingen) {        // §9.7 / §13.4.10 (Phase E)
            for (Rule rule : verdeling(v)) m.addRule(rule);
        }
        for (io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie oc : model.objectCreaties) {  // §13.4.6 (Phase G)
            Rule rule = objectCreatie(oc);
            if (rule != null) m.addRule(rule);
        }
        for (io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie fc : model.feitCreaties) {       // §13.4.7 (Phase G)
            Rule rule = feitCreatie(fc);
            if (rule != null) m.addRule(rule);
        }
        for (io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie u : model.uniciteitUnies)  // §8.1.6 "verenigd met" (Phase H)
            for (Rule rule : uniciteitUnie(u)) m.addRule(rule);
        return m;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule regel(ResolvedRegel r) {
        if (!(r.doel() instanceof Expr.AttribuutRef doel) || doel.navigatieRol() != null || doel.objecttype() == null) {
            return skip("regel (doel geen eigen attribuut/kenmerk): " + r.naam());
        }
        Class<?> factClass = factClass(doel.objecttype());
        if (factClass == null) return skip("regel (geen fact-class " + doel.objecttype().naam + "): " + r.naam());
        // §13.3.6.7 a MULTI-PERIOD temporal doel ("voor elke N maanden") is a List<Segment> timeline (gran set AND in
        // attribuutTijdlijnAantal) whose WRITE producer semantics are under-specified. Skip it honestly BEFORE the
        // doelSetter — never fall to the scalar value path below, which would silently emit a scalar setter on a
        // List<Segment> field. Mirrors the DRL multi-period marker.
        if (doel.objecttype().attribuutGranulariteit.get(doel.attribuut()) != null
                && doel.objecttype().attribuutTijdlijnAantal.containsKey(doel.attribuut()))
            return skip("regel (meer-periodieke temporele doel — §13.3.6.7, geen producent): " + r.naam());

        String doelProp = doel.attribuut();
        BiConsumer<Object, Object> doelSetter = Accessors.setter(factClass, doelProp);   // typed write (Phase-H codegen)
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
        pattern = geldigGuard(pattern, r.geldigheid());   // §4.2 version validity window

        RuleItemBuilder<?> consequence;
        // §5.1.4 a single-period TEMPORAL doel (a List<Segment> timeline attribute) is WRITTEN a timeline, not a
        // scalar: the value must be a §13.4.17.57/.58 timeline producer (aantal-dagen-in / tijdsevenredig deel).
        RegelSpraakModel.Granulariteit doelGran = doel.objecttype().attribuutGranulariteit.get(doel.attribuut());
        boolean temporalDoel = doelGran != null && !doel.objecttype().attribuutTijdlijnAantal.containsKey(doel.attribuut());
        // §8.2 a temporal KENMERK doel (a List<Segment> true-period timeline) cannot be written a scalar boolean —
        // setX(true) on a List field throws at fire time. No producer for it yet, so skip honestly (never mis-render).
        if (r.soort() == ResolvedRegel.Soort.KENMERKTOEKENNING
                && doel.objecttype().kenmerkGranulariteit.containsKey(doel.attribuut()))
            return skip("regel (temporele kenmerktoekenning — latere fase): " + r.naam());
        // §5.8/§13.4.17 AGGREGATION value (SOM/TOTAAL/AANTAL/MIN/MAX over related elements): an accumulate CE binds
        // the result in the when, read in the consequence — distinct from a scalar fact-local value.
        WaardeFn tijdTotaal = !temporalDoel && isAggregatieWaarde(r) && r.waarde() instanceof Expr.Functie ftt
                && ftt.kind() == Expr.FunctieKind.TOTAAL ? tijdreeksTotaal(ftt, doel.objecttype()) : null;
        WaardeFn dimAgg = !temporalDoel && isAggregatieWaarde(r) && r.waarde() instanceof Expr.Functie fda
                && fda.dimensieSelectie() != null ? dimensieAggregatie(fda, doel.objecttype()) : null;
        if (temporalDoel) {
            // §13.4.17.57/.58 lower the timeline-producing RHS to a (subject -> List<Segment>) function and set it.
            // A producer we cannot lower is an honest skip (never a scalar setter on a List field, which would not
            // even reflect cleanly). NO_LOOP guards the modify; the List property is read by no pattern, so nothing
            // reacts to the write (Oracle-confirmed).
            TemporeleWaarde tw = temporeleWaarde(r.waarde(), doel.objecttype(), doelGran, doel.type());
            if (tw == null) return skip("regel (temporele toekenning buiten Phase-F scope): " + r.naam());
            BiFunction<Object, Object, Object> twf = tw.fn();
            if (tw.global() == null) {
                consequence = DSL.on(sVar).execute((Block2<org.drools.model.Drools, Object>) (drools, s) -> {
                    doelSetter.accept(s, twf.apply(s, null));
                    drools.update(s);
                });
            } else {
                // §7.3.2 the tijdsevenredig base is a TEMPORAL PARAMETER (a List<Segment> global) — bind it.
                Global<java.util.List> g = listGlobals.get(tw.global());
                if (g == null) return skip("regel (temporele basis-parameter niet geregistreerd " + tw.global() + "): " + r.naam());
                consequence = DSL.on(sVar, (Variable) g).execute((Block3<org.drools.model.Drools, Object, Object>) (drools, s, gv) -> {
                    doelSetter.accept(s, twf.apply(s, gv));
                    drools.update(s);
                });
            }
        } else if (dimAgg != null) {
            // §5.8.5 a dimensie-fold (SOM/MIN/MAX over a gedimensioneerd attribuut's selected labels) collapses a
            // Map<label,value> to a SCALAR — a fixed fold, NOT an instance accumulate, reading no globals.
            BigDecimal vf = valueFactor(r.waarde().type(), doel.type());
            if (vf != null) dimAgg = dimAgg.scaledBy(vf);
            BiFunction<Object, Map<String, BigDecimal>, Object> fn = dimAgg.fn();
            consequence = DSL.on(sVar).execute((Block2<org.drools.model.Drools, Object>) (drools, s) -> {
                doelSetter.accept(s, fn.apply(s, Map.of()));
                drools.update(s);
            });
        } else if (tijdTotaal != null) {
            // §7.1 a TOTAAL over a TEMPORAL attribute is a SCALAR timeline fold (rsTijdTotaal over the subject's own/
            // navigated List<Segment>, optionally gedurende-clipped) — NOT an instance accumulate. It reads no globals
            // EXCEPT a §7.1 EUR71 gedurende-comparison vs a Parameter grens ("... groter is dan de grens"), which the
            // WaardeFn surfaces; bind those like the scalar path so the consequence reads them from the global map.
            BigDecimal vf = valueFactor(r.waarde().type(), doel.type());
            if (vf != null) tijdTotaal = tijdTotaal.scaledBy(vf);
            BiFunction<Object, Map<String, BigDecimal>, Object> fn = tijdTotaal.fn();
            List<String> gnames = new ArrayList<>(tijdTotaal.globals());
            List<Variable> vars = new ArrayList<>();
            vars.add(sVar);
            for (String gn : gnames) {
                Global<BigDecimal> g = globals.get(gn);
                if (g == null) return skip("regel (totaal-grens refereert niet-geregistreerde global " + gn + "): " + r.naam());
                vars.add((Variable) g);
            }
            final List<String> fgn = gnames;
            consequence = consequenceOf(vars.toArray(new Variable[0]), (drools, args) -> {
                Object s = args[0];
                Map<String, BigDecimal> gm = new LinkedHashMap<>();
                for (int i = 0; i < fgn.size(); i++) gm.put(fgn.get(i), (BigDecimal) args[1 + i]);
                doelSetter.accept(s, fn.apply(s, gm));
                drools.update(s);
            });
            if (consequence == null) return skip("regel (totaal: te veel gebonden globals): " + r.naam());
        } else if (isAggregatieWaarde(r) && r.waarde() instanceof Expr.Functie fx && !isGelisteFold(fx)) {
            // (a ≥2-operand MINIMUM/MAXIMUM is a §5.8.3 LISTED concatenation fold, not an instance accumulate — it
            //  flows to the scalar path / concatenatieFold below.)
            Agg agg = aggregatie(fx, doel.objecttype(), sVar);
            if (agg == null) return skip("regel (aggregatie buiten Phase-C scope): " + r.naam());
            extra.add(agg.accItem());
            BigDecimal vf = valueFactor(fx.type(), doel.type());   // §4 restate the aggregate into the doel unit
            if (agg.isSom()) {
                // §5.8.2 sommatie: an empty sum (0 non-empty contributors) is the leegwaarde fallback, not 0.
                Variable cntVar = agg.countVar(), sumVar = agg.resultVar();
                WaardeFn fallback = agg.somFallback();
                Block4<org.drools.model.Drools, Object, Object, Object> body = (drools, s, cnt, sum) -> {
                    BigDecimal v = ((Number) cnt).longValue() == 0L
                            ? (fallback == null ? null : (BigDecimal) fallback.fn().apply(s, Map.of()))
                            : RegelSpraakRuntime.toBd(sum);
                    if (vf != null && v != null) v = v.multiply(vf);
                    doelSetter.accept(s, v);
                    drools.update(s);
                };
                consequence = DSL.on(sVar, (Variable) cntVar, (Variable) sumVar).execute(body);
            } else {
                Variable resVar = agg.resultVar();
                Block3<org.drools.model.Drools, Object, Object> body = (drools, s, res) -> {
                    BigDecimal v = RegelSpraakRuntime.toBd(res);
                    if (vf != null && v != null) v = v.multiply(vf);
                    doelSetter.accept(s, v);
                    drools.update(s);
                };
                consequence = DSL.on(sVar, (Variable) resVar).execute(body);
            }
        } else if (isScalarSoort(r.soort()) && containsAggregatie(r.waarde())) {
            // §5.8 AGGREGATION INSIDE ARITHMETIC ("het aantal passagiers maal de factor", "de totaal van X plus Y"):
            // the value is an arithmetic tree whose operand is an aggregation. Lower each embedded aggregation to its
            // own accumulate CE (binding a result var), then the consequence combines the bound results + own-attr
            // reads + a (≤1) global. Mirrors DrlTranspiler.waarde recursing through binairTerm into accumulate.
            Set<String> aggGlobals = new LinkedHashSet<>();
            List<RuleItemBuilder<?>> accItems = new ArrayList<>();
            List<Variable> accVars = new ArrayList<>();
            Eval eval = lowerVal(r.waarde(), doel.objecttype(), sVar, aggGlobals, accItems, accVars);
            if (eval == null) return skip("regel (aggregatie-in-arithmetiek buiten scope): " + r.naam());
            List<String> gnames = new ArrayList<>(aggGlobals);
            BigDecimal vf = valueFactor(r.waarde().type(), doel.type());   // §4 restate the whole value into the doel unit
            // consequence vars: [subject, g0, g1, ..., acc0, acc1, ...]; the body rebuilds the global map + agg array.
            List<Variable> vars = new ArrayList<>();
            vars.add(sVar);
            for (String gn : gnames) {
                Global<BigDecimal> g = globals.get(gn);
                if (g == null) return skip("regel (aggregatie-waarde refereert niet-geregistreerde global " + gn + "): " + r.naam());
                vars.add((Variable) g);
            }
            vars.addAll(accVars);
            final List<String> fgn = gnames; final int nG = gnames.size(); final int nAgg = accVars.size();
            consequence = consequenceOf(vars.toArray(new Variable[0]), (drools, args) -> {
                Object s = args[0];
                Map<String, BigDecimal> gm = new LinkedHashMap<>();
                for (int i = 0; i < nG; i++) gm.put(fgn.get(i), (BigDecimal) args[1 + i]);
                Object[] aggs = new Object[nAgg];
                for (int i = 0; i < nAgg; i++) aggs[i] = args[1 + nG + i];
                Object v = eval.apply(s, gm, aggs);
                if (vf != null && v != null) v = ((BigDecimal) v).multiply(vf);
                doelSetter.accept(s, v);
                drools.update(s);
            });
            if (consequence == null) return skip("regel (aggregatie-waarde: te veel gebonden variabelen): " + r.naam());
            extra.addAll(accItems);
        } else {
            // scalar value path: a WaardeFn(subject, global-values -> value) plus the Parameter-globals it reads.
            // valueSubject is set tightly around the lowering so a to-one navigated value read can resolve its role.
            valueSubject = doel.objecttype();
            WaardeFn wf = switch (r.soort()) {
                case KENMERKTOEKENNING -> WaardeFn.constant(Boolean.TRUE);      // "is <kenmerk>" -> kenmerk == true
                case GELIJKSTELLING, BEREKENING, INITIALISATIE -> scalarWaarde(r.waarde());
                default -> null;
            };
            valueSubject = null;
            if (wf == null) return skip("regel (waarde buiten scope): " + r.naam());
            // §4 value-side unit restatement (a kenmerk value is Boolean, no unit). Mirrors DrlTranspiler.omrekenWaarde.
            if (r.soort() != ResolvedRegel.Soort.KENMERKTOEKENNING) {
                BigDecimal vf = valueFactor(r.waarde().type(), doel.type());
                if (vf != null) wf = wf.scaledBy(vf);
            }
            // resolve the referenced globals; an unregistered one (e.g. a temporal parameter) -> honest skip. The
            // consequence binds [subject, g0, g1, ...] via consequenceOf (up to Block10 = 9 vars); the body rebuilds
            // the name->value map. >1 distinct global is now supported (e.g. a value of two parameters).
            List<String> gnames = new ArrayList<>(wf.globals());
            List<Variable> vars = new ArrayList<>();
            vars.add(sVar);
            for (String gn : gnames) {
                Global<BigDecimal> g = globals.get(gn);
                if (g == null) return skip("regel (THEN-waarde refereert niet-geregistreerde global " + gn + "): " + r.naam());
                vars.add((Variable) g);
            }
            BiFunction<Object, Map<String, BigDecimal>, Object> fn = wf.fn();
            final List<String> fgn = gnames;
            consequence = consequenceOf(vars.toArray(new Variable[0]), (drools, args) -> {
                Object s = args[0];
                Map<String, BigDecimal> gm = new LinkedHashMap<>();
                for (int i = 0; i < fgn.size(); i++) gm.put(fgn.get(i), (BigDecimal) args[1 + i]);
                doelSetter.accept(s, fn.apply(s, gm));
                drools.update(s);
            });
            if (consequence == null) return skip("regel (THEN-waarde: te veel gebonden globals): " + r.naam());
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
    /** §5.8.3-.4 a LISTED concatenation fold ("de minimale/maximale/eerste/laatste van A en B …") — ≥2 listed scalar
     *  operands, no restriction — handled on the scalar path by {@link #concatenatieFold}, NOT an instance aggregation. */
    private static boolean isGelisteFold(Expr.Functie f) {
        return (f.kind() == Expr.FunctieKind.MINIMUM || f.kind() == Expr.FunctieKind.MAXIMUM
                || f.kind() == Expr.FunctieKind.EERSTE || f.kind() == Expr.FunctieKind.LAATSTE)
                && f.argumenten().size() >= 2 && f.filter() == null;
    }

    /** A lowered aggregation: the accumulate rule item, the result Variable, and (for §5.8.2 SOM) the contributor
     *  count Variable + the empty-sum fallback. */
    private record Agg(RuleItemBuilder<?> accItem, Variable resultVar, Variable countVar, WaardeFn somFallback, boolean isSom) {}

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
        boolean temporeel = arg != null && arg.objecttype() != null && arg.objecttype().attribuutGranulariteit.containsKey(arg.attribuut());
        // §7.1 a SOM/TOTAAL over a temporal attribute of a set is a PER-ELEMENT timeline fold (rsTijdTotaal per element,
        // then sum); MIN/MAX over a temporal attribute is ambiguous -> skip (mirror the DRL marker). ("de som van het
        // totaal van X van alle" attaches the set to the inner totaal -> the outer SOM is element-less -> markers.)
        if (temporeel && f.kind() != Expr.FunctieKind.SOM && f.kind() != Expr.FunctieKind.TOTAAL) return null;
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
            src = filterSource(src, f.filter(), elem);
            if (src == null) return null;               // unstructured / unbindable filter -> skip (mirror the DRL marker)
        }

        Variable resultVar = DSL.declarationOf(Object.class, "$acc_" + (seq++));
        if (count)
            return new Agg(DSL.accumulate(src, DSL.accFunction(accFn("CountAccumulateFunction")).as(resultVar)),
                    resultVar, null, null, false);

        final String attr = arg.attribuut();
        // §7.1 per-element timeline fold: a temporal element value is summed over time (rsTijdTotaal at the attribute's
        // granularity) to a scalar BEFORE the set fold, instead of the bare List<Segment> getter.
        final String tijdGran = temporeel ? arg.objecttype().attribuutGranulariteit.get(attr).name() : null;
        final Function1<Object, Object> elemWaarde = temporeel
                ? (e) -> RegelSpraakRuntime.rsTijdTotaal((List<?>) RegelSpraakRuntime.get(e, attr), tijdGran)
                : (e) -> RegelSpraakRuntime.get(e, attr);
        // The fold always uses the BigDecimal accumulate variant: a Numeriek attribute maps to a BigDecimal getter
        // (the §6 exact-precision invariant), and BigDecimalSum/Min/Max skip nulls — matching what the DRL `sum`/
        // `min`/`max` keyword resolves to for a BigDecimal arg. (Seam, adversary-noted: if a future objecttype ever
        // lowered a Numeriek to a Double/Integer getter, DRL's keyword would fall back to the generic null-NPEing
        // Double sum while this stays BigDecimal — re-introduce an `attr != null` source guard if that invariant breaks.)
        Variable amtVar = DSL.declarationOf(BigDecimal.class, "$amt_" + (seq++));
        if (f.kind() == Expr.FunctieKind.SOM) {
            // §5.8.2: empty SOM -> the leegwaarde (null, or a literal "of N als die er niet zijn"), not 0. Filter to
            // non-empty contributors, count them, and sum; the consequence branches on the count.
            // §5.8.2 "of <expr> als die er niet zijn": the fallback may be a numeric literal OR a fact-local
            // expression (an own attribute / arithmetic) read from the subject — but not one reading a global.
            WaardeFn fallback = null;
            if (f.leegwaarde() != null) {
                fallback = scalarWaarde(f.leegwaarde());
                if (fallback == null || !fallback.globals().isEmpty()) return null;   // unlowerable / global fallback -> skip
            }
            PatternDSL.PatternDef src2 = src.expr(nextId("nn"), (Predicate1) (e) -> RegelSpraakRuntime.get(e, attr) != null)
                    .bind(amtVar, (Function1) elemWaarde);
            Variable cntVar = DSL.declarationOf(Object.class, "$cnt_" + (seq++));
            RuleItemBuilder acc = DSL.accumulate(src2,
                    DSL.accFunction(accFn("CountAccumulateFunction")).as(cntVar),
                    DSL.accFunction(accFn("BigDecimalSumAccumulateFunction"), amtVar).as(resultVar));
            return new Agg(acc, resultVar, cntVar, fallback, true);
        }
        // TOTAAL (empty -> 0) / MIN / MAX: bind the per-element value and fold it.
        Class<?> accClass = switch (f.kind()) {
            case TOTAAL  -> accFn("BigDecimalSumAccumulateFunction");
            case MINIMUM -> accFn("MinAccumulateFunction");
            case MAXIMUM -> accFn("MaxAccumulateFunction");
            default      -> null;
        };
        if (accClass == null) return null;
        PatternDSL.PatternDef src2 = src.bind(amtVar, (Function1) elemWaarde);
        return new Agg(DSL.accumulate(src2, DSL.accFunction(accClass, amtVar).as(resultVar)), resultVar, null, null, false);
    }

    /** §13.4.2/§5.6 apply a die/dat-subselectie filter to the accumulate SOURCE pattern. A §5.6 comparison whose RHS is
     *  a NUMERIC Parameter-global binds that global on the source pattern (a 2-var expr over the element + the global) —
     *  the exec analog of DRL reading the global as a free name in the source constraint ("prijs &lt; grens"); the LHS is
     *  the element's folded attribute. Every other supported filter is a fact-only leaf via {@link #leafPredicate}.
     *  null -&gt; the filter is unstructured, or its global is non-numeric / unregistered -&gt; the caller skips
     *  (symmetric with the DRL marker — DRL also needs the global declared). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private PatternDSL.PatternDef filterSource(PatternDSL.PatternDef src, Conditie filter, Objecttype elem) {
        // §13.4.13.4 a COMPOUND subselectie on the element ("die aan alle volgende voorwaarden voldoen: …"): ALLE/DE
        // ANDs each bullet onto the source pattern as a successive .expr (mirroring DRL's `&&`); a counting quantifier
        // (geen/ten-minste/…) folds the bullets into ONE count-vs-N Predicate1. A bullet that cannot be lowered (null)
        // skips the WHOLE compound — never a partial filter (symmetric with DRL markering an un-lowerable child).
        if (filter instanceof Conditie.Samengesteld s) {
            if (s.kwantor() == Conditie.Kwantor.ALLE || s.kwantor() == Conditie.Kwantor.DE) {
                for (Conditie deel : s.onderdelen()) {
                    src = filterSource(src, deel, elem);   // recurse: each bullet is a leaf OR a param-global comparison
                    if (src == null) return null;
                }
                return src;
            }
            // §13.4.14.4 counting quantifier. Each bullet is either a fact-only Predicate1 ({@link #leafPredicate})
            // or a §5.6 numeric/percentage Parameter-GLOBAL comparison ("zijn leeftijd is kleiner dan de grens"). A
            // SINGLE distinct source-global across the bullets binds as a 2-var expr (element + the global), folding the
            // count INSIDE a Predicate2 — the exec analog of DRL reading the global as a free name regardless of
            // quantifier. 0 globals -> a plain Predicate1 fold. An un-lowerable leaf OR a SECOND distinct global skips
            // the whole compound (symmetric with RuleChecker's parity guard, so DRL also declines exactly those).
            final Conditie.Kwantor kw = s.kwantor(); final int n = s.drempel();
            Global<BigDecimal> bound = null;
            List<Predicate2<Object, BigDecimal>> tellers = new ArrayList<>();
            for (Conditie deel : s.onderdelen()) {
                GlobalVergelijking gb = globalBullet(deel, elem);   // a single numeric-vs-global comparison, or null
                if (gb != null) {
                    if (bound != null && bound != gb.global()) return null;   // >1 distinct global -> skip
                    bound = gb.global();
                    tellers.add(gb.test());
                } else {
                    Predicate1<Object> lp = leafPredicate(deel, elem);
                    if (lp == null) return null;
                    tellers.add((e, gv) -> lp.test(e));   // a fact-only leaf ignores the global
                }
            }
            final List<Predicate2<Object, BigDecimal>> fTellers = tellers;
            Predicate2<Object, BigDecimal> fold = (e, gv) -> {
                int t = 0; for (Predicate2<Object, BigDecimal> p : fTellers) if (p.test(e, gv)) t++;
                return switch (kw) {
                    case GEEN -> t == 0; case TEN_MINSTE -> t >= n; case TEN_HOOGSTE -> t <= n; case PRECIES -> t == n;
                    default -> false;   // ALLE/DE handled above
                };
            };
            if (bound == null) {
                Predicate1<Object> p1 = e -> fold.test(e, null);   // no global -> a plain fact-only count fold
                return src.expr(nextId("filter"), (Predicate1) p1);
            }
            Predicate2<Object, Object> p2 = (e, gv) -> fold.test(e, (BigDecimal) gv);
            return src.expr(nextId("filter"), (Variable) bound, (Predicate2) p2);
        }
        GlobalVergelijking gb = globalBullet(filter, elem);   // §5.6 a getalachtig Parameter-global comparison source filter
        if (gb != null) {
            Predicate2<Object, Object> p2 = (e, gv) -> gb.test().test(e, (BigDecimal) gv);
            return src.expr(nextId("filter"), (Variable) gb.global(), (Predicate2) p2);
        }
        Predicate1<Object> fp = leafPredicate(filter, elem);
        return fp == null ? null : src.expr(nextId("filter"), (Predicate1) fp);
    }

    /** A §5.6/§13.4.13.4 numeric/percentage Parameter-GLOBAL comparison bullet ("zijn leeftijd is kleiner dan de
     *  grens"): the bound global {@link Variable} + a (element, globalValue) -&gt; boolean test, unit-restated like the
     *  §8.1.1 numeric-vs-global path. null when the bullet is not such a comparison, or its global is non-numeric /
     *  unregistered, or the element attribute is navigated / temporal / dimensioned (the caller then treats it as a
     *  fact-only leaf, or skips). Shared by {@link #filterSource}'s standalone branch and its counting-quantifier fold,
     *  so a single source-global binds identically in both — the only quantifier shape the ALLE/DE arm cannot reach
     *  (it recurses, binding each bullet's global on its own {@code .expr}). */
    private record GlobalVergelijking(Global<BigDecimal> global, Predicate2<Object, BigDecimal> test) {}
    private GlobalVergelijking globalBullet(Conditie deel, Objecttype elem) {
        if (!(deel instanceof Conditie.Vergelijking v) || !(v.links() instanceof Expr.AttribuutRef la)
                || la.navigatieRol() != null || !la.type().isGetalachtig() || !isNumeriekOp(v.op())   // §4.8 numeriek OR percentage
                || !(v.rechts() instanceof Expr.ParameterRef pr)) return null;
        if (elem == null || !elem.heeftLid(la.attribuut())) return null;
        if (elem.attribuutGranulariteit.containsKey(la.attribuut())) return null;   // temporal element attr -> skip
        if (!elem.dimensiesVan(la.attribuut()).isEmpty()) return null;              // §3.6 gedimensioneerd -> skip
        Global<BigDecimal> g = globals.get(ident(pr.parameter().naam()));
        if (g == null) return null;   // non-numeric / unregistered global -> skip (DRL needs the global declared too)
        String attr = la.attribuut();
        Conditie.Operator op = v.op();
        BigDecimal[] ff = restatement(la.type(), pr.parameter().datatype());        // §5.1 Tabel 32 unit restatement
        return new GlobalVergelijking(g, (e, gv) -> vergelijk(scale(num(e, attr), ff[0]), scale(gv, ff[1]), op));
    }

    // ---------------- §7.1 temporal totaal (Phase F) ----------------
    /** §7.1 "het totaal van &lt;temporeel attribuut&gt;": a TOTAAL over a single OWN (or to-one navigated) TEMPORAL
     *  attribute is a SCALAR fold of its {@code List<Segment>} timeline ({@link RegelSpraakRuntime#rsTijdTotaal}
     *  weighting each tijdvak by its granularity-period count), optionally clipped to a §7.1 "gedurende de tijd dat
     *  &lt;temporeel kenmerk&gt;" condition ({@code rsTijdKnip}, or {@code rsTijdKnipNiet} for a negated membership).
     *  Returns a (subject -&gt; BigDecimal) {@link WaardeFn} that reads no globals, or null for any other operand —
     *  a non-temporal attribute (-&gt; the instance {@link #aggregatie}), a §13.3.6.7 multi-period / set / unresolvable
     *  navigation, or a compound / unstructured filter (-&gt; honest skip; §8.4 compounds are a later increment).
     *  Mirrors DrlTranspiler.tijdreeksAggregatie — the only temporal expression that collapses to a scalar (§5.1.1). */
    private WaardeFn tijdreeksTotaal(Expr.Functie f, Objecttype subject) {
        if (f.kind() != Expr.FunctieKind.TOTAAL || f.argumenten().size() != 1) return null;
        if (f.filter() instanceof Conditie.Onopgelost) return null;   // unstructured restriction -> skip (never an unconditioned total)
        if (!(f.argumenten().get(0) instanceof Expr.AttribuutRef a) || a.objecttype() == null) return null;
        if (a.set()) return null;     // "het totaal van <attr> van een SET" is an instance aggregation, not an own timeline
        RegelSpraakModel.Granulariteit g = a.objecttype().attribuutGranulariteit.get(a.attribuut());
        if (g == null) return null;   // not temporal -> fall through to the instance accumulate
        if (a.objecttype().attribuutTijdlijnAantal.containsKey(a.attribuut())) return null;   // §13.3.6.7 multi-period -> skip
        java.util.function.Function<Object, Object> reader = navReader(a, subject);   // own or to-one navigated, root-guarded
        if (reader == null) return null;
        final String gran = g.name();
        // §7.1/§8.4 optional gedurende clip: a single temporal kenmerk (positive intersect / negated complement), or a
        // §8.4.2 pure-kenmerk compound (ALLE intersect / ten-minste-1 union). A mixed-static compound -> skip (next inc).
        String kenmerk = null; boolean negatie = false; Class<?> segCls = null; String navRol = null;
        BiFunction<Object, List<?>, List<?>> compoundClip = null;
        VergClip vergClip = null;                                  // §7.1 EUR71 a temporal-comparison clip
        java.util.function.Predicate<Object> staticGuard = null;   // §8.4 mixed-static value-side gate
        boolean orWiden = false;                                   // ten-minste-1: a TRUE static member widens to the full span
        if (f.filter() instanceof Conditie.Kenmerk k && k.navigatie() == null
                && subject != null && subject.kenmerkGranulariteit.containsKey(k.kenmerk())) {
            segCls = segmentClass();
            if (segCls == null) return null;
            kenmerk = k.kenmerk();
            negatie = k.negatie();
        } else if (f.filter() instanceof Conditie.Kenmerk kn && kn.navigatie() != null && subject != null) {
            // §7.1 a NAVIGATED temporal kenmerk ("gedurende de tijd dat zijn reis een recht op korting heeft"): clip
            // to the to-one role's kenmerk timeline, read root-guarded (a null reference -> leeg). Mirrors DRL.
            segCls = segmentClass();
            if (segCls == null) return null;
            Objecttype doel = model.rolDoeltypeNaarSubject(subject, kn.navigatie());
            if (doel == null || kn.negatie() || !doel.kenmerkGranulariteit.containsKey(kn.kenmerk())
                    || doel.kenmerkTijdlijnAantal.containsKey(kn.kenmerk())) return null;   // negated / to-many / non-temporal -> skip
            navRol = kn.navigatie();
            kenmerk = kn.kenmerk();
        } else if (f.filter() instanceof Conditie.Vergelijking vc) {
            // §7.1 EUR71 "gedurende de tijd dat <temporeel attr> <op> <scalar>": clip the value timeline to the
            // comparison's OWN boolean timeline (rsTijdConditie over the comparison attribute's segments), then total.
            segCls = segmentClass();
            if (segCls == null) return null;
            vergClip = vergelijkingClip(vc, subject);
            if (vergClip == null) return null;   // non-temporal / cross-unit / datum / navigated comparison -> skip
        } else if (f.filter() instanceof Conditie.Samengesteld sc) {
            segCls = segmentClass();
            if (segCls == null) return null;
            // §8.4.2 partition: temporal kenmerks (the clip) + static comparisons (the value-side guard). Any other
            // member -> skip; no temporal member -> not a temporal restriction (skip).
            List<Conditie.Kenmerk> kenmerken = new ArrayList<>();
            List<Conditie.Vergelijking> statisch = new ArrayList<>();
            for (Conditie c : sc.onderdelen()) {
                if (c instanceof Conditie.Kenmerk kk) kenmerken.add(kk);
                else if (c instanceof Conditie.Vergelijking vv) statisch.add(vv);
                else return null;
            }
            if (kenmerken.isEmpty()) return null;
            compoundClip = kenmerkClip(kenmerken, sc.kwantor(), sc.drempel(), subject, segCls);
            if (compoundClip == null) return null;
            if (!statisch.isEmpty()) {
                boolean alle = sc.kwantor() == Conditie.Kwantor.ALLE;
                staticGuard = statischeGuard(statisch, subject, alle);
                if (staticGuard == null) return null;   // a static member we cannot lower -> skip, never an ungated totaal
                orWiden = !alle;   // ten-minste-1: a TRUE static member widens the result to the full (unclipped) span
            }
        } else if (f.filter() != null) {
            return null;   // any other restriction -> honest skip
        }
        final String fKenmerk = kenmerk; final boolean fNeg = negatie; final Class<?> fSeg = segCls; final String fNavRol = navRol;
        final BiFunction<Object, List<?>, List<?>> fClip = compoundClip; final VergClip fVerg = vergClip;
        final java.util.function.Predicate<Object> fGuard = staticGuard; final boolean fOrWiden = orWiden;
        BiFunction<Object, Map<String, BigDecimal>, Object> fn = (s, gm) -> {
            List<?> tl = (List<?>) reader.apply(s);
            List<?> clipped;
            if (fKenmerk != null) {
                List<?> cond;
                if (fNavRol != null) {   // §7.1 navigated kenmerk: read the to-one role's kenmerk timeline, root-guarded
                    Object rel = RegelSpraakRuntime.get(s, fNavRol);
                    cond = rel == null ? null : (List<?>) RegelSpraakRuntime.get(rel, fKenmerk);
                } else {
                    cond = (List<?>) RegelSpraakRuntime.get(s, fKenmerk);
                }
                clipped = fNeg ? RegelSpraakRuntime.rsTijdKnipNiet(tl, cond, fSeg) : RegelSpraakRuntime.rsTijdKnip(tl, cond, fSeg);
            } else if (fVerg != null) {
                BigDecimal grens = RegelSpraakRuntime.toBd(fVerg.grens().fn().apply(s, gm));
                List<?> cond = RegelSpraakRuntime.rsTijdConditie((List<?>) fVerg.timeline().apply(s), fVerg.toets(), grens, fSeg);
                clipped = RegelSpraakRuntime.rsTijdKnip(tl, cond, fSeg);
            } else if (fClip != null) {
                clipped = fClip.apply(s, tl);
            } else {
                clipped = tl;
            }
            if (fGuard != null) {
                boolean guardHeld = fGuard.test(s);
                if (fOrWiden) return RegelSpraakRuntime.rsTijdTotaal(guardHeld ? tl : clipped, gran);   // ten-minste-1: TRUE -> full span
                if (!guardHeld) return java.math.BigDecimal.ZERO;                                       // ALLE: FALSE -> 0
            }
            return RegelSpraakRuntime.rsTijdTotaal(clipped, gran);
        };
        return new WaardeFn(fn, vergClip != null ? vergClip.grens().globals() : Set.of());
    }

    /** §7.1 EUR71 the temporal-attribute comparison's condition reader: the timeline of the comparison attribute, the
     *  toets code, and the scalar grens (a {@link WaardeFn} carrying its globals). null for a non-temporal /
     *  cross-unit / datum / navigated comparison (the caller skips). Mirrors DrlTranspiler.vergelijkingTijdreeks /
     *  geheelPeriode82's comparison branch. */
    private VergClip vergelijkingClip(Conditie.Vergelijking v, Objecttype subject) {
        Expr.AttribuutRef tijd; Expr ander; boolean linksTemp;
        if (v.links() instanceof Expr.AttribuutRef la && isTemporeel82(la))       { tijd = la; ander = v.rechts(); linksTemp = true; }
        else if (v.rechts() instanceof Expr.AttribuutRef ra && isTemporeel82(ra)) { tijd = ra; ander = v.links();  linksTemp = false; }
        else return null;
        if (!(tijd.type().isNumeriek() || tijd.type().isPercentage())) return null;
        if (ander instanceof Expr.AttribuutRef ar && isTemporeel82(ar)) return null;   // both-temporal (timeline vs timeline) -> skip
        io.github.yhavinga.regelspraak.model.Eenheid el = tijd.type().eenheidOf(), er = ander.type().eenheidOf();
        if (el != null && er != null && el.vereistOmrekening(er)) return null;   // cross-unit -> skip (raw-magnitude would be wrong)
        Integer code = toetsCode82(linksTemp ? v.op() : omkeren(v.op()));        // temporal operand on the left (REVERSE)
        if (code == null) return null;                                           // datum ordering -> skip
        java.util.function.Function<Object, Object> tl = temporeelTijdlijnReader(tijd, subject);
        if (tl == null) return null;                                             // navigated/unresolvable -> skip
        // the grens is read value-side (own attr / to-one navigated / literal / global); scalarWaarde's navigated
        // branch needs valueSubject set (tijdreeksTotaal runs before regel() sets it), so set it around the call so a
        // navigated grens ("... groter is dan de inkomen van zijn partner") lowers — symmetric with DRL's waarde().
        Objecttype bewaardeValueSubject = valueSubject;
        valueSubject = subject;
        WaardeFn g = scalarWaarde(ander);
        valueSubject = bewaardeValueSubject;
        if (g == null) return null;                                              // unlowerable grens -> skip
        return new VergClip(tl, code, g);
    }
    private record VergClip(java.util.function.Function<Object, Object> timeline, int toets, WaardeFn grens) {}

    /** §5.8.5/§13.4.17.51-.54 a SOM / MINIMUM / MAXIMUM over a gedimensioneerd attribuut, restricted to a
     *  {@link Expr.DimensieSelectie}'s labels — a FIXED fold of the attribute's {@code Map<label,value>} over the
     *  selected keys ({@link RegelSpraakRuntime#rsDimSom}/{@code rsDimMin}/{@code rsDimMax}, null-skipping empties),
     *  NOT an instance accumulate. §5.8.2: an all-empty SOM yields the declared numeric-literal "of … als die er niet
     *  zijn" fallback (else null — an empty sum is a leegwaarde, not 0). Returns a (subject -> BigDecimal) WaardeFn
     *  reading no globals, or null (no selectie / unresolvable map / non-literal SOM fallback). Mirrors
     *  DrlTranspiler.dimensieAggregatie. */
    private WaardeFn dimensieAggregatie(Expr.Functie f, Objecttype subject) {
        if (f.dimensieSelectie() == null) return null;
        Expr.FunctieKind kind = f.kind();
        if (kind != Expr.FunctieKind.SOM && kind != Expr.FunctieKind.MINIMUM && kind != Expr.FunctieKind.MAXIMUM) return null;
        if (f.argumenten().size() != 1 || !(f.argumenten().get(0) instanceof Expr.AttribuutRef a)) return null;
        java.util.function.Function<Object, Object> reader = navReader(a, subject);   // the dimensioned attribute's Map field
        if (reader == null) return null;
        final List<String> labels = new ArrayList<>(f.dimensieSelectie().labels());
        BigDecimal fallback = null;
        if (kind == Expr.FunctieKind.SOM && f.leegwaarde() != null) {
            if (!(f.leegwaarde() instanceof Expr.Literal lit) || !lit.type().isNumeriek()) return null;
            fallback = bd(lit);
            if (fallback == null) return null;
        }
        final BigDecimal fFallback = fallback; final Expr.FunctieKind fKind = kind;
        BiFunction<Object, Map<String, BigDecimal>, Object> vf = (s, gm) -> {
            Map<?, ?> map = (Map<?, ?>) reader.apply(s);
            BigDecimal r = switch (fKind) {
                case SOM     -> RegelSpraakRuntime.rsDimSom(map, labels);
                case MINIMUM -> RegelSpraakRuntime.rsDimMin(map, labels);
                case MAXIMUM -> RegelSpraakRuntime.rsDimMax(map, labels);
                default      -> null;
            };
            return fKind == Expr.FunctieKind.SOM && r == null ? fFallback : r;
        };
        return new WaardeFn(vf, Set.of());
    }

    /** Resolve an {@link Expr.AttribuutRef} to a {@code (subject -> value)} reader: the subject's OWN attribute, or a
     *  to-ONE navigated read (root-guarded so a null relation reads as leeg, never an NPE). null for an absent /
     *  to-many / unresolvable navigation — the model analog of DrlTranspiler.naviResolve's EIGEN / GENAVIGEERD vs the
     *  marker cases. (The self-anaphora "van de &lt;subject-role&gt;" is resolved the same way DRL does, so the two
     *  back-ends agree.) */
    private java.util.function.Function<Object, Object> navReader(Expr.AttribuutRef a, Objecttype subject) {
        if (a.objecttype() == null || !a.objecttype().heeftLid(a.attribuut())) return null;   // AFWEZIG
        String rol = null;
        if (a.navigatieRol() != null) rol = model.verbindendeRolViaNaam(subject, a.objecttype(), a.navigatieRol());
        final String attr = a.attribuut();
        if (rol == null) {
            if (a.objecttype() == subject) return typedGetter(factClass(subject), attr);   // EIGEN
            rol = subject != null ? model.verbindendeRol(subject, a.objecttype()) : null;
            if (rol == null) return null;   // ONOPGELOST
        }
        if (subject != null && model.naarVeelRol(subject, a.objecttype(), rol)) return null;   // NAAR_VEEL
        // GENAVIGEERD: read the relation field on the subject, then the attribute on the (known) target type — both
        // typed (Accessors LMF) when the fact class is known at build time. Null relation -> leeg (null-safe).
        java.util.function.Function<Object, Object> roleGet = typedGetter(factClass(subject), rol);
        java.util.function.Function<Object, Object> attrGet = typedGetter(factClass(a.objecttype()), attr);
        return s -> { Object rel = roleGet.apply(s); return rel == null ? null : attrGet.apply(rel); };
    }

    /** A getter for {@code prop} on {@code cls}: a TYPED {@link Accessors} LMF accessor when the fact class is known
     *  at model-build time, else the cached reflective bridge. Both are null-owner-safe (null -> null). */
    private static java.util.function.Function<Object, Object> typedGetter(Class<?> cls, String prop) {
        return cls != null ? Accessors.getter(cls, prop) : (s -> RegelSpraakRuntime.get(s, prop));
    }

    /** Resolve the to-ONE navigation role of a navigated AttribuutRef (navigatieRol != null), mirroring
     *  {@link #navReader}'s GENAVIGEERD case — or null when absent / unresolvable / to-many. The navigated
     *  COMPARISON branches use this (rather than {@link #navReader}) so they can read the RELATION field and,
     *  per §5.5.5, treat a NULL relation as a LEEGWAARDE — the SAME as a present relation with a null ATTRIBUTE
     *  value — and then let {@link #vergelijk}'s leegwaarde semantics decide (Tabel 33-44: a leeg operand makes
     *  ordering/GELIJK onwaar but ONGELIJK WAAR, Tabel 44). The earlier "a null relation is false for EVERY
     *  operator" reading mirrored mvel's {@code rol!.attr} short-circuit but was spec-WRONG for ONGELIJK; the DRL
     *  voorwaarde now also reads a navigated comparison leegwaarde-faithfully (genavigeerdeVergelijkingVoorwaarde). */
    private String navComparisonRole(Expr.AttribuutRef a, Objecttype subject) {
        if (a.objecttype() == null || a.navigatieRol() == null || !a.objecttype().heeftLid(a.attribuut())) return null;
        String rol = model.verbindendeRolViaNaam(subject, a.objecttype(), a.navigatieRol());
        if (rol == null) return null;
        if (subject != null && model.naarVeelRol(subject, a.objecttype(), rol)) return null;   // NAAR_VEEL
        return rol;
    }

    /** The fact {@code Segment} class (a runtime objecttype) reflectively, or null when absent — needed by the
     *  timeline-constructing helpers (rsTijdKnip/rsTijdUnie/rsAantalDagenIn/rsTijdsevenredig) to build new segments. */
    static Class<?> segmentClass() {
        try { return Class.forName(FACTS + ".Segment"); }
        catch (ClassNotFoundException e) { return null; }
    }

    // ---------------- §13.4.17.57/.58 temporal WRITE producers (Phase F) ----------------
    /** A lowered timeline producer: {@code fn(subject, globalValue)} -> the {@code List<Segment>} to write, plus the
     *  name of the (≤1) List-global it reads (a §7.3.2 tijdsevenredig PARAMETER base), or null. */
    private record TemporeleWaarde(BiFunction<Object, Object, Object> fn, String global) {}

    /** §5.1.4 lower a RHS that PRODUCES a timeline written to a single-period temporal doel attribute, into a
     *  {@link TemporeleWaarde} — or null when it is not a supported producer (caller skips). Two writers:
     *  §13.4.17.57 "het aantal dagen in &lt;de maand|het jaar&gt; dat &lt;temporeel kenmerk&gt;" ->
     *  {@link RegelSpraakRuntime#rsAantalDagenIn} over the kenmerk's true-period timeline; §13.4.17.58 "het
     *  tijdsevenredig deel per &lt;maand|jaar&gt; van &lt;basis&gt; gedurende ..." -> {@link
     *  RegelSpraakRuntime#rsTijdsevenredig} prorating a base value-timeline (the subject's own temporal attribute, or
     *  a temporal PARAMETER's List global). Both bucket by the DOEL's granularity (the operator's "per/in
     *  &lt;maand|jaar&gt;" MUST match the doel tijdlijn, §7.2 — a mismatch skips). For tijdsevenredig the base must be
     *  numeriek, COARSER-OR-EQUAL to the bucket (§7.3.2 reads it "voor de hele maand/jaar"), and in the SAME unit as
     *  the doel (no rate conversion — §7.3.2 ex.3 markers). Mirrors DrlTranspiler.temporeleWaarde. */
    private TemporeleWaarde temporeleWaarde(Expr w, Objecttype subject,
            RegelSpraakModel.Granulariteit gran, Datatype doelType) {
        if (w instanceof Expr.Functie f && f.kind() == Expr.FunctieKind.AANTAL_DAGEN_IN
                && f.granulariteit() == gran && subject != null) {
            java.util.function.Function<Object, List<?>> cond = conditieTijdreeks(f.filter(), subject);   // single kenmerk / §8.4 compound
            java.util.function.Predicate<Object> guard = cond == null ? null : mixedGuardWrite(f.filter(), subject);   // §8.4 static gate
            if (cond != null && guard != null) {
                Class<?> segCls = segmentClass();
                if (segCls != null) {
                    final String granName = gran.name();
                    final java.util.function.Predicate<Object> fGuard = guard;
                    return new TemporeleWaarde((s, gv) -> fGuard.test(s)
                            ? RegelSpraakRuntime.rsAantalDagenIn(cond.apply(s), granName, segCls) : new ArrayList<>(), null);
                }
            }
        }
        if (w instanceof Expr.Functie f && f.kind() == Expr.FunctieKind.TIJDSEVENREDIG_DEEL
                && f.granulariteit() == gran && f.argumenten().size() == 1 && subject != null) {
            Expr base = f.argumenten().get(0);
            RegelSpraakModel.Granulariteit baseGran = null; Datatype baseType = null; String baseGlobal = null;
            BiFunction<Object, Object, List<?>> baseReader = null;   // (subject, globalValue) -> the base List<Segment>
            if (base instanceof Expr.AttribuutRef ba && ba.navigatieRol() == null && !ba.set() && ba.objecttype() == subject
                    && subject.attribuutGranulariteit.containsKey(ba.attribuut())
                    && !subject.attribuutTijdlijnAantal.containsKey(ba.attribuut())) {
                baseGran = subject.attribuutGranulariteit.get(ba.attribuut());
                baseType = ba.type();
                String baseAttr = ba.attribuut();
                baseReader = (s, gv) -> (List<?>) RegelSpraakRuntime.get(s, baseAttr);
            } else if (base instanceof Expr.AttribuutRef ba && ba.navigatieRol() != null && !ba.set()
                    && ba.objecttype() != null && ba.objecttype().attribuutGranulariteit.containsKey(ba.attribuut())
                    && !ba.objecttype().attribuutTijdlijnAantal.containsKey(ba.attribuut())) {
                // §7.3.2 a TO-ONE NAVIGATED base ("de X van zijn reis"): read the partner's temporal attribute root-guarded
                // (navReader: null relation -> leeg). The gedurende condition stays over the SUBJECT. Mirrors DRL.
                java.util.function.Function<Object, Object> nav = navReader(ba, subject);
                if (nav != null) {
                    baseGran = ba.objecttype().attribuutGranulariteit.get(ba.attribuut());
                    baseType = ba.type();
                    baseReader = (s, gv) -> (List<?>) nav.apply(s);
                }
            } else if (base instanceof Expr.ParameterRef bp && bp.parameter().granulariteit() != null) {
                baseGran = bp.parameter().granulariteit();
                baseType = bp.parameter().datatype();
                baseGlobal = ident(bp.parameter().naam());
                baseReader = (s, gv) -> (List<?>) gv;   // the List<Segment> global the runtime supplies
            }
            if (baseReader != null && baseGran.ordinal() >= gran.ordinal() && baseType.isNumeriek()) {
                // §7.3.2 ex.3 rate conversion: the base's per-tijdseenheid unit may DIFFER from the doel's (€/jr base
                // into a €/mnd doel). §7.3.1 restates the base FIRST (÷12 via rsTijdSchaal) and THEN takes the day
                // fraction. Same unit -> factor null (no scale); convertible-different -> a factor; not convertible ->
                // marker. Mirrors DRL.
                boolean zelfdeEenheid = java.util.Objects.equals(baseType.eenheidOf(), doelType == null ? null : doelType.eenheidOf());
                // §4.22 the operand must be a RATE (eenheid per tijdseenheid): a unit mismatch is a §7.3.2 rate
                // conversion only when the base unit has a noemer; a non-rate mismatch (cm -> m) stays a marker. Mirrors DRL.
                boolean baseIsRate = baseType.eenheidOf() != null && baseType.eenheidOf().noemer() != null;
                BigDecimal fwd = (zelfdeEenheid || !baseIsRate) ? null : valueFactor(baseType, doelType);
                // §7.3.1 DIVISION: pick the EXACT integer direction (fewer sig digits) and divide by it — never the
                // pre-rounded reciprocal (1/12), which would diverge from ÷12 for ~13% of bases. Mirrors DrlTranspiler.
                BigDecimal inv = fwd == null ? null : valueFactor(doelType, baseType);
                boolean deel = inv != null && inv.precision() < fwd.precision();
                BigDecimal factor = fwd == null ? null : (deel ? inv : fwd);
                java.util.function.Function<Object, List<?>> cond = (zelfdeEenheid || factor != null) ? conditieTijdreeks(f.filter(), subject) : null;
                java.util.function.Predicate<Object> guard = cond == null ? null : mixedGuardWrite(f.filter(), subject);   // §8.4 static gate
                // §8.4.2 OR-mixed (ten minste 1: a temporal kenmerk OR a static comparison): conditieTijdreeks markers
                // the mixed OR, but tijdsevenredig's base bounds the static-true span. Mirrors DrlTranspiler.orMixedTijdsevenredig.
                OrMixed orMixed = (cond == null && (zelfdeEenheid || factor != null)) ? orMixedTijdsevenredig(f.filter(), subject) : null;
                if ((cond != null && guard != null) || orMixed != null) {
                    Class<?> segCls = segmentClass();
                    if (segCls != null) {
                        final String granName = gran.name();
                        final BiFunction<Object, Object, List<?>> br0 = baseReader;
                        final BigDecimal fFactor = factor;
                        final boolean fDeel = deel;
                        // scale the base by the unit factor when a rate conversion is needed (§7.3.1), else read it raw.
                        final BiFunction<Object, Object, List<?>> br = fFactor == null ? br0
                                : (s, gv) -> RegelSpraakRuntime.rsTijdSchaal(br0.apply(s, gv), fFactor, fDeel);
                        if (orMixed != null) {
                            // static TRUE -> prorate the base over its FULL extent (base AS its own all-true condition,
                            // rsTijdsevenredig reading the base's segments as true periods); static FALSE -> the temporal
                            // union only. Mirrors DrlTranspiler.orMixedTijdsevenredig.
                            final OrMixed om = orMixed;
                            return new TemporeleWaarde((s, gv) -> {
                                List<?> baseList = br.apply(s, gv);
                                return om.orGuard().test(s)
                                        ? RegelSpraakRuntime.rsTijdsevenredig(baseList, baseList, granName, segCls)
                                        : RegelSpraakRuntime.rsTijdsevenredig(om.unionCond().apply(s), baseList, granName, segCls);
                            }, baseGlobal);
                        }
                        final java.util.function.Predicate<Object> fGuard = guard;
                        return new TemporeleWaarde(
                                (s, gv) -> fGuard.test(s)
                                        ? RegelSpraakRuntime.rsTijdsevenredig(cond.apply(s), br.apply(s, gv), granName, segCls)
                                        : new ArrayList<>(),
                                baseGlobal);
                    }
                }
            }
        }
        return null;
    }

    /** The TRUE-period {@code (subject -> List<Segment>)} timeline of a temporal WRITE-producer's condition. A single
     *  positive single-period subject kenmerk is its getter; a §8.4.2 pure-kenmerk compound is their combined
     *  timeline (ALLE = intersection via rsTijdKnip, "ten minste 1" = union via rsTijdUnie, both over the members'
     *  getters). null when unsupported (a negated / navigated / multi-period kenmerk, or a mixed-static / non-kenmerk
     *  / other-kwantor compound): a negated member has no standalone complement here (unlike the totaal CLIP, where
     *  the value bounds it), so the caller markers rather than mis-count. Mirrors DrlTranspiler.conditieTijdreeks. */
    private java.util.function.Function<Object, List<?>> conditieTijdreeks(Conditie filter, Objecttype subject) {
        if (filter instanceof Conditie.Kenmerk k) return temporeelKenmerkTijdreeks(k, subject);
        if (filter instanceof Conditie.Samengesteld s
                && (s.kwantor() == Conditie.Kwantor.ALLE
                    || (s.kwantor() == Conditie.Kwantor.TEN_MINSTE && s.drempel() == 1))) {
            boolean alle = s.kwantor() == Conditie.Kwantor.ALLE;
            List<java.util.function.Function<Object, List<?>>> regios = new ArrayList<>();
            for (Conditie c : s.onderdelen()) {
                // §8.4 a static comparison member is the value-side guard (the mixedGuardWrite gate), not part of the
                // timeline — admitted only in an ALLE compound (an OR-mixed write widens the span, which the producers
                // do not express, so it markers).
                if (c instanceof Conditie.Vergelijking) { if (!alle) return null; continue; }
                if (!(c instanceof Conditie.Kenmerk k)) return null;
                java.util.function.Function<Object, List<?>> g = temporeelKenmerkTijdreeks(k, subject);
                if (g == null) return null;   // a negated / navigated / multi-period member -> marker (no standalone complement)
                regios.add(g);
            }
            if (regios.isEmpty()) return null;
            if (regios.size() == 1) return regios.get(0);
            Class<?> segCls = segmentClass();
            if (segCls == null) return null;
            return s2 -> {
                List<?> acc = regios.get(0).apply(s2);
                for (int i = 1; i < regios.size(); i++)
                    acc = alle ? RegelSpraakRuntime.rsTijdKnip(acc, regios.get(i).apply(s2), segCls)
                               : RegelSpraakRuntime.rsTijdUnie(acc, regios.get(i).apply(s2), segCls);
                return acc;
            };
        }
        return null;
    }

    /** §8.4.2 the clip a (pure-kenmerk) compound gedurende applies to a value timeline: ALLE nests the per-kenmerk
     *  rsTijdKnip (negation per member); "ten minste 1" (drempel 1) clips the value to the UNION of each member's
     *  true-region (a negated member's region is the value's rsTijdKnipNiet complement, value-bounded). Returns a
     *  {@code (subject, value) -> clipped-value} function, or null when a member is not a single-period subject kenmerk
     *  or the kwantor is unsupported. Mirrors DrlTranspiler.samengesteldeTijdKnip. */
    private BiFunction<Object, List<?>, List<?>> kenmerkClip(List<Conditie.Kenmerk> leden, Conditie.Kwantor kw, int drempel,
                                                             Objecttype subject, Class<?> segCls) {
        if (leden.isEmpty()) return null;
        for (Conditie.Kenmerk k : leden)
            if (k.navigatie() != null || subject == null || !subject.kenmerkGranulariteit.containsKey(k.kenmerk())) return null;
        if (kw == Conditie.Kwantor.ALLE) {
            return (sub, value) -> {
                List<?> r = value;
                for (Conditie.Kenmerk k : leden) {
                    List<?> g = (List<?>) RegelSpraakRuntime.get(sub, k.kenmerk());
                    r = k.negatie() ? RegelSpraakRuntime.rsTijdKnipNiet(r, g, segCls)
                                    : RegelSpraakRuntime.rsTijdKnip(r, g, segCls);
                }
                return r;
            };
        }
        if (kw == Conditie.Kwantor.TEN_MINSTE && drempel == 1) {
            return (sub, value) -> {
                List<?> unie = lidSteun(sub, value, leden.get(0), segCls);
                for (int i = 1; i < leden.size(); i++)
                    unie = RegelSpraakRuntime.rsTijdUnie(unie, lidSteun(sub, value, leden.get(i), segCls), segCls);
                return RegelSpraakRuntime.rsTijdKnip(value, unie, segCls);
            };
        }
        return null;
    }

    /** One OR-member's true-region for the union: a positive member is its kenmerk getter; a negated member is the
     *  value clipped to the kenmerk's FALSE periods (value-bounded complement). Mirrors DrlTranspiler.lidSteun. */
    private static List<?> lidSteun(Object sub, List<?> value, Conditie.Kenmerk k, Class<?> segCls) {
        List<?> g = (List<?>) RegelSpraakRuntime.get(sub, k.kenmerk());
        return k.negatie() ? RegelSpraakRuntime.rsTijdKnipNiet(value, g, segCls) : g;
    }

    /** The two pieces of a lowered OR-mixed tijdsevenredig: the temporal union's true-period timeline (the static-FALSE
     *  condition) and the static members' OR gate (true -> widen to the base's full extent). */
    private record OrMixed(java.util.function.Function<Object, List<?>> unionCond, java.util.function.Predicate<Object> orGuard) {}

    /** §8.4.2 lower an OR-mixed ("ten minste 1": a temporal kenmerk OR a static comparison) gating the §13.4.17.58
     *  tijdsevenredig WRITE producer. A TRUE static member satisfies the OR for the WHOLE span; tijdsevenredig's base
     *  bounds that span, so static TRUE prorates the base over its full extent (base AS its own all-true condition) and
     *  static FALSE prorates over the temporal union only. Returns the (union-cond, OR-guard) pair, or null when the
     *  compound is not a temporal+static OR-mix or a member is not lowerable. ONLY tijdsevenredig lowers an OR-mix:
     *  §7.2 aantal-dagen-in has no value base, so its result is bounded only by the condition's true-periods (§7.2 line
     *  3005's "zeer groot aantal resultaten" caveat) -> a static-TRUE OR is unbounded and stays an honest marker.
     *  Mirrors DrlTranspiler.orMixedTijdsevenredig. */
    private OrMixed orMixedTijdsevenredig(Conditie filter, Objecttype subject) {
        if (!(filter instanceof Conditie.Samengesteld s) || s.kwantor() != Conditie.Kwantor.TEN_MINSTE || s.drempel() != 1)
            return null;
        List<Conditie> temporeel = new ArrayList<>();
        List<Conditie.Vergelijking> statisch = new ArrayList<>();
        for (Conditie c : s.onderdelen()) {
            if (c instanceof Conditie.Kenmerk) temporeel.add(c);
            else if (c instanceof Conditie.Vergelijking v) statisch.add(v);
            else return null;   // an unsupported member type -> marker
        }
        if (temporeel.isEmpty() || statisch.isEmpty()) return null;   // not a temporal+static mixed compound
        Conditie.Samengesteld temporeelDeel = new Conditie.Samengesteld(s.kwantor(), s.drempel(), temporeel, s.bron());
        java.util.function.Function<Object, List<?>> unionCond = conditieTijdreeks(temporeelDeel, subject);
        if (unionCond == null) return null;   // a negated / navigated / multi-period temporal member -> marker
        java.util.function.Predicate<Object> orGuard = statischeGuard(statisch, subject, false);   // OR
        if (orGuard == null) return null;     // a static member we cannot lower -> marker
        return new OrMixed(unionCond, orGuard);
    }

    /** §8.4 the value-side static guard for a temporal-WRITE compound: the AND of the non-temporal comparison members
     *  (only an ALLE compound admits statics on the write side). Returns a {@code (subject -> Boolean)} gate (a
     *  constant-true gate when there are no statics), or null when a static member is not lowerable (caller skips). */
    private java.util.function.Predicate<Object> mixedGuardWrite(Conditie filter, Objecttype subject) {
        if (!(filter instanceof Conditie.Samengesteld s)) return o -> true;
        List<Conditie.Vergelijking> statisch = new ArrayList<>();
        for (Conditie c : s.onderdelen()) if (c instanceof Conditie.Vergelijking v) statisch.add(v);
        if (statisch.isEmpty()) return o -> true;
        return statischeGuard(statisch, subject, true);   // AND (an ALLE compound — the OR-mixed write markers in conditieTijdreeks)
    }

    /** §8.4 combine the static comparison members into one {@code (subject -> Boolean)} gate — the AND ({@code and}) for
     *  an ALLE compound, the OR for a "ten minste 1". null when any member is not lowerable. Mirrors DrlTranspiler.statischeGuard. */
    private java.util.function.Predicate<Object> statischeGuard(List<Conditie.Vergelijking> leden, Objecttype subject, boolean and) {
        List<java.util.function.Predicate<Object>> ps = new ArrayList<>();
        for (Conditie.Vergelijking v : leden) {
            java.util.function.Predicate<Object> p = statischeVergelijking(v, subject);
            if (p == null) return null;
            ps.add(p);
        }
        return s -> {
            for (java.util.function.Predicate<Object> p : ps) {
                boolean r = p.test(s);
                if (and && !r) return false;
                if (!and && r) return true;
            }
            return and;
        };
    }

    /** §8.4 one non-temporal comparison member as a value-side {@code (subject -> Boolean)} predicate, with the §5.1
     *  comparison-matrix leegwaarde semantics. A NUMERIC comparison reuses {@link #vergelijk} (which already matches
     *  the value-side rule: GELIJK both-empty true, ONGELIJK gevuld-vs-empty true, ordering onwaar if either empty). A
     *  DATUM ordering (eerder/later) is true only when both operands are present (an empty operand -> onwaar). null
     *  when the operand is temporal / navigated / not an own attribute vs a literal/own-attribute, or the op/operand is
     *  out of scope. Mirrors DrlTranspiler.statischeVergelijkingGuard. */
    private java.util.function.Predicate<Object> statischeVergelijking(Conditie.Vergelijking v, Objecttype subject) {
        if (!(v.links() instanceof Expr.AttribuutRef la) || la.navigatieRol() != null) return null;
        if (subject != null && subject.attribuutGranulariteit.containsKey(la.attribuut())) return null;   // a temporal operand is no static member
        String lattr = la.attribuut();
        if (isNumeriekOp(v.op())) {
            Conditie.Operator op = v.op();
            if (v.rechts() instanceof Expr.Literal lit && lit.type().isNumeriek()) {
                BigDecimal rhs = bd(lit);
                if (rhs == null) return null;
                BigDecimal[] f = restatement(la.type(), lit.type());
                BigDecimal rhsS = scale(rhs, f[1]);
                return s -> vergelijk(scale(num(s, lattr), f[0]), rhsS, op);
            }
            if (v.rechts() instanceof Expr.AttribuutRef ra && ra.navigatieRol() == null) {
                if (subject != null && subject.attribuutGranulariteit.containsKey(ra.attribuut())) return null;
                String rattr = ra.attribuut();
                BigDecimal[] f = restatement(la.type(), ra.type());
                return s -> vergelijk(scale(num(s, lattr), f[0]), scale(num(s, rattr), f[1]), op);
            }
            return null;
        }
        if (isDatumOp(v.op()) && la.type().isDatum() && v.rechts() instanceof Expr.Literal lit && lit.type().isDatum()) {
            java.time.LocalDate rhs = parseDatum(lit.waarde());
            if (rhs == null) return null;
            Conditie.Operator op = v.op();
            return s -> {
                Object a = RegelSpraakRuntime.get(s, lattr);
                return a instanceof java.time.LocalDate ld && datumVergelijk(ld, rhs, op);   // empty datum operand -> onwaar (ordening)
            };
        }
        return null;
    }
    private static boolean isDatumOp(Conditie.Operator op) {
        return switch (op) {
            case EERDER, EERDER_OF_GELIJK, LATER, LATER_OF_GELIJK -> true;
            default -> false;
        };
    }
    /** Parse a §13.2.19 date literal ("dd. d-m-yyyy ...") to a LocalDate — its d-m-yyyy core, or null when absent.
     *  Mirrors the date core of DrlTranspiler.literal. */
    private static java.time.LocalDate parseDatum(String waarde) {
        java.util.regex.Matcher d = java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})-(\\d{4})").matcher(waarde);
        if (!d.find()) return null;
        try { return java.time.LocalDate.of(Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1))); }
        catch (RuntimeException e) { return null; }
    }
    /** Parse a §13.2.21 datum-tijd literal ("dd-m-yyyy hh:mm:ss[.SSS]") to a LocalDateTime — mirrors DrlTranspiler's
     *  datetime literal (the captured millisecondes scale x1e6 to nanoOfSecond). null when the date or time is absent. */
    private static java.time.LocalDateTime parseDatumTijd(String waarde) {
        java.util.regex.Matcher d = java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})-(\\d{4})").matcher(waarde);
        if (!d.find()) return null;
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?").matcher(waarde);
        if (!tm.find()) return null;
        try {
            int nanos = (tm.group(4) == null ? 0 : Integer.parseInt(tm.group(4))) * 1_000_000;
            return java.time.LocalDateTime.of(Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1)),
                    Integer.parseInt(tm.group(1)), Integer.parseInt(tm.group(2)), Integer.parseInt(tm.group(3)), nanos);
        } catch (RuntimeException e) { return null; }
    }

    /** Map a duration unit (its Datatype's eenheid) to a {@link java.time.temporal.ChronoUnit} name — byte-identical
     *  to DrlTranspiler.chronoUnit, so both back-ends key date arithmetic on the SAME unit. A kwartaal maps to the
     *  pseudo-unit "KWARTAAL" (rsDatumReken special-cases it as 3×MONTHS); null only for a dagsoort / an unrecognised
     *  unit (the caller then skips). */
    private static String chronoUnit(Datatype t) {
        Eenheid e = t.eenheidOf();
        if (e == null) return null;
        return switch (RegelSpraakModel.normaliseerTijdseenheid(e.afkorting())) {
            case "jaar", "jr"             -> "YEARS";
            case "maand", "mnd"           -> "MONTHS";
            case "week", "wk"             -> "WEEKS";
            case "dag"                    -> "DAYS";
            case "uur", "u"               -> "HOURS";
            case "minuut", "min"          -> "MINUTES";
            case "seconde", "secondes", "sec", "s"     -> "SECONDS";
            case "milliseconde", "millisecondes", "ms" -> "MILLIS";
            case "kwartaal", "kwartalen" -> "KWARTAAL";   // §3.7 3×MONTHS — rsDatumReken special-cases it (no ChronoUnit.QUARTERS)
            default -> null;
        };
    }

    /** §13.3.8 the Java class a scalar parameter's global is declared with — the {@link org.drools.model.Global} type
     *  must match the value the session setGlobal supplies, else Drools throws "Illegal class for global" at fire time.
     *  Mirrors DrlTranspiler.javaType (Datum -> LocalDate, DatumTijd -> LocalDateTime, Tekst -> String, Booleaan -> Boolean). */
    private static Class<?> globalKlasse(Datatype t) {
        if (t instanceof Datatype.DatumInDagen) return java.time.LocalDate.class;
        if (t instanceof Datatype.DatumTijd) return java.time.LocalDateTime.class;
        if (t.isTekst()) return String.class;
        if (t instanceof Datatype.Booleaan) return Boolean.class;
        return Object.class;
    }

    /** A single-entry global binding map that — unlike {@link Map#of}, which rejects a null value with an NPE — TOLERATES
     *  a null: an unset / §13.3.8 leeg numeric Parameter binds to a null global value, and the downstream value-fns
     *  (rsDatumReken treats a leeg duration as 0, rsGeheelPeriode null-guards its grens, ...) already carry the §6/§8
     *  leegwaarde rules. {@code Map.of(k, null)} would throw a ConstraintEvaluationException out of fireAllRules — the
     *  DRL constraint passes the null global straight through, so the model must too. */
    private static Map<String, BigDecimal> bindGlobal(String naam, Object waarde) {
        return java.util.Collections.singletonMap(naam, (BigDecimal) waarde);
    }

    /** §6/§13.4.16 date arithmetic as a value: a datum/datumtijd ± a numeric-with-time-unit -> a new date(time) via
     *  {@link RegelSpraakRuntime#rsDatumReken} (optellen=PLUS=later; MIN/VERMINDERD_MET=earlier; aantal 0 -> unchanged;
     *  leeg operand -> leeg). Mirrors DrlTranspiler.binairTerm's datum branch and its guards: only +/-, no datum-datum,
     *  a time unit (uur/…) needs a datum-tijd operand, kwartaal lowers (3×MONTHS via rsDatumReken), and a
     *  dagsoort/unresolvable operand -> null (skip). */
    private WaardeFn datumRekenWaarde(Expr.Binair b) {
        Expr.BinOp op = b.op();
        if (op != Expr.BinOp.PLUS && op != Expr.BinOp.MIN && op != Expr.BinOp.VERMINDERD_MET) return null;
        boolean datumLinks = b.links().type().isDatum();
        Expr datumExpr = datumLinks ? b.links() : b.rechts();
        Expr durExpr   = datumLinks ? b.rechts() : b.links();
        boolean optellen = op == Expr.BinOp.PLUS;
        if (durExpr.type().isDatum() || (!optellen && !datumLinks)) return null;   // datum-datum / duur-min-datum
        // §6.11 datum ± N <dagsoort> ("5 werkdagen"): step day-by-day counting the §9.8 dagsoort days. A dagsoort is
        // day-granular -> a Datum in dagen (LocalDate) operand only. Mirrors DrlTranspiler's rsDatumPlus_<Naam>.
        io.github.yhavinga.regelspraak.model.Eenheid durEenheid = durExpr.type().eenheidOf();
        String dagsoortUnit = durEenheid != null ? model.dagsoortVoorEenheid(durEenheid.afkorting()) : null;
        if (dagsoortUnit != null) {
            if (datumExpr.type() instanceof Datatype.DatumTijd) return null;
            java.util.function.Predicate<java.time.LocalDate> pred = dagsoortPredikaat(dagsoortUnit);
            WaardeFn df0 = scalarWaarde(datumExpr), nf0 = scalarWaarde(durExpr);
            if (pred == null || df0 == null || nf0 == null) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> dfn0 = df0.fn(), nfn0 = nf0.fn();
            Set<String> g0 = new LinkedHashSet<>(df0.globals()); g0.addAll(nf0.globals());
            return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsDatumPlusDagsoort(
                    (java.time.LocalDate) dfn0.apply(s, gm), (BigDecimal) nfn0.apply(s, gm), pred, optellen), g0);
        }
        String unit = chronoUnit(durExpr.type());
        if (unit == null) return null;
        boolean tijdEenheid = switch (unit) { case "HOURS", "MINUTES", "SECONDS", "MILLIS" -> true; default -> false; };
        if (tijdEenheid && !(datumExpr.type() instanceof Datatype.DatumTijd)) return null;
        WaardeFn df = scalarWaarde(datumExpr), nf = scalarWaarde(durExpr);
        if (df == null || nf == null) return null;
        BiFunction<Object, Map<String, BigDecimal>, Object> dfn = df.fn(), nfn = nf.fn();
        Set<String> g = new LinkedHashSet<>(df.globals()); g.addAll(nf.globals());
        return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsDatumReken(
                (java.time.temporal.Temporal) dfn.apply(s, gm), (BigDecimal) nfn.apply(s, gm), unit, optellen), g);
    }

    /** A single positive single-period subject kenmerk's TRUE-period reader ({@code s -> the kenmerk's List<Segment>}),
     *  or null when not lowerable as a standalone true-period timeline (a navigated / negated / multi-period /
     *  non-subject kenmerk — a negated member has no standalone complement here, so the caller markers). Mirrors
     *  DrlTranspiler.temporeelKenmerkTijdreeks. */
    private java.util.function.Function<Object, List<?>> temporeelKenmerkTijdreeks(Conditie.Kenmerk k, Objecttype subject) {
        if (k.navigatie() != null || k.negatie() || subject == null
                || !subject.kenmerkGranulariteit.containsKey(k.kenmerk())
                || subject.kenmerkTijdlijnAantal.containsKey(k.kenmerk())) return null;
        String kenmerk = k.kenmerk();
        return s -> (List<?>) RegelSpraakRuntime.get(s, kenmerk);
    }

    // ---------------- §8.2 / §13.4.15.70 gehele-periodevergelijking (mirrors DrlTranspiler.geheelPeriodeCall) ------
    /** The §8.2 whole-period qualifier on a predicate, or null when it carries none. */
    private static Conditie.GeheelPeriode geheelPeriodeVan(Conditie c) {
        if (c instanceof Conditie.Vergelijking v) return v.periode();
        if (c instanceof Conditie.Predicaat p)    return p.periode();
        if (c instanceof Conditie.Kenmerk k)      return k.periode();
        return null;
    }

    /** Whether an AttribuutRef reads a tijdsafhankelijk attribuut (its field is a List&lt;Segment&gt; timeline). */
    private static boolean isTemporeel82(Expr.AttribuutRef a) {
        return a.objecttype() != null && a.objecttype().attribuutGranulariteit.containsKey(a.attribuut());
    }

    /** §8.2 rsGeheelPeriode toets code for a comparison operator, or null for a DATUM ordering (out of scope — the
     *  Segment carries a BigDecimal). Mirrors DrlTranspiler.toetsCode. */
    private static Integer toetsCode82(Conditie.Operator op) {
        return switch (op) {
            case KLEINER -> 0; case KLEINER_OF_GELIJK -> 1;
            case GROTER  -> 2; case GROTER_OF_GELIJK  -> 3;
            case GELIJK  -> 4; case ONGELIJK          -> 5;
            default -> null;
        };
    }

    /** The §8.2 timeline reader for a temporal attribute — the subject's own List&lt;Segment&gt;, or a to-ONE
     *  navigated partner's (null reference -> null timeline -> rsGeheelPeriode returns true). null for a to-many /
     *  unresolvable navigation. Mirrors DrlTranspiler.temporeelTijdlijnGetter. */
    private java.util.function.Function<Object, Object> temporeelTijdlijnReader(Expr.AttribuutRef a, Objecttype subject) {
        if (a.navigatieRol() == null) {
            if (subject == null || !subject.heeftLid(a.attribuut())) return null;
            String attr = a.attribuut();
            return s -> RegelSpraakRuntime.get(s, attr);
        }
        String rol = navComparisonRole(a, subject);
        if (rol == null) return null;
        String attr = a.attribuut();
        return s -> { Object rel = RegelSpraakRuntime.get(s, rol); return rel == null ? null : RegelSpraakRuntime.get(rel, attr); };
    }

    /** A resolved §8.2 whole-period predicate: the subject-&gt;timeline reader, the toets code, the grenswaarde
     *  (null for gevuld/kenmerk), the granularity, and the NIET flag. */
    private record Gp82(java.util.function.Function<Object, Object> timeline, int toets, WaardeFn grens, String gran, boolean niet) {}

    /** Build the §8.2 model representation of a whole-period-qualified predicate, or null when outside the supported
     *  set (mirrors DrlTranspiler.geheelPeriodeCall exactly): a numeric comparison of a temporal attribute vs a scalar
     *  grens, a GEVULD over a temporal attribute, or a temporal kenmerkcheck; a mixed-unit comparison / DATUM ordering
     *  / LEEG / ELFPROEF / NUMERIEK-met-exact whole-period form -> null (an honest skip, symmetric with the DRL marker). */
    private Gp82 geheelPeriode82(Conditie c, Conditie.GeheelPeriode gp, Objecttype subject) {
        String gran = gp.jaar() ? "JAAR" : "MAAND";
        if (c instanceof Conditie.Vergelijking v) {
            Expr.AttribuutRef tijd; Expr ander; boolean linksTemp;
            if (v.links() instanceof Expr.AttribuutRef la && isTemporeel82(la))       { tijd = la; ander = v.rechts(); linksTemp = true; }
            else if (v.rechts() instanceof Expr.AttribuutRef ra && isTemporeel82(ra)) { tijd = ra; ander = v.links();  linksTemp = false; }
            else return null;
            if (!(tijd.type().isNumeriek() || tijd.type().isPercentage())) return null;
            io.github.yhavinga.regelspraak.model.Eenheid el = tijd.type().eenheidOf(), er = ander.type().eenheidOf();
            if (el != null && er != null && el.vereistOmrekening(er)) return null;   // mixed unit -> marker (DRL too)
            Integer code = toetsCode82(linksTemp ? v.op() : omkeren(v.op()));   // REVERSE (operand swap), not negate
            if (code == null) return null;
            java.util.function.Function<Object, Object> tl = temporeelTijdlijnReader(tijd, subject);
            if (tl == null) return null;
            WaardeFn g = scalarWaarde(ander);
            if (g == null) {
                // scalarWaarde's navigated branch is value-path-only (valueSubject is null here), so a to-ONE navigated
                // grenswaarde ("… gelijk is aan de drempel van zijn reis") would skip while the DRL oracle FIRES it via
                // reis!.attr. Lower it with the same condition-aware null-safe role reader the timeline side uses, so
                // both back-ends agree (a null relation -> null grens -> the §8.2 check fails, matching mvel's reis!.attr).
                if (ander instanceof Expr.AttribuutRef ar && ar.navigatieRol() != null && (ar.type().isNumeriek() || ar.type().isPercentage())) {
                    String rol = navComparisonRole(ar, subject);
                    if (rol != null) {
                        String gattr = ar.attribuut();
                        g = new WaardeFn((s, gm) -> { Object rel = RegelSpraakRuntime.get(s, rol); return rel == null ? null : RegelSpraakRuntime.get(rel, gattr); }, java.util.Set.of());
                    }
                }
                if (g == null) return null;
            }
            return new Gp82(tl, code, g, gran, gp.niet());
        }
        if (c instanceof Conditie.Predicaat p && p.soort() == Conditie.Predicaatsoort.GEVULD
                && p.operand() instanceof Expr.AttribuutRef pa && isTemporeel82(pa)) {
            java.util.function.Function<Object, Object> tl = temporeelTijdlijnReader(pa, subject);
            if (tl == null) return null;
            return new Gp82(tl, 6, null, gran, gp.niet());
        }
        if (c instanceof Conditie.Kenmerk k) {
            java.util.function.Function<Object, List<?>> tl = temporeelKenmerkTijdreeks(k, subject);
            if (tl == null) return null;
            return new Gp82(s -> tl.apply(s), 7, null, gran, gp.niet());
        }
        return null;
    }

    /** A fact-only Predicate1 for "the §8.2 check holds" (the NIET prefix complements the rsGeheelPeriode result), or
     *  null when the grens reads a Parameter-global (the caller binds it as a pattern variable). */
    private Predicate1<Object> geheelPeriodeLeaf(Gp82 g) {
        if (g == null) return null;
        if (g.grens() != null && !g.grens().globals().isEmpty()) return null;   // needs a global -> 2-var expr
        java.util.function.Function<Object, Object> tl = g.timeline();
        WaardeFn gf = g.grens(); int toets = g.toets(); String gran = g.gran(); boolean niet = g.niet();
        return s -> {
            BigDecimal grens = gf == null ? null : RegelSpraakRuntime.toBd(gf.fn().apply(s, java.util.Map.of()));
            boolean b = RegelSpraakRuntime.rsGeheelPeriode((List<?>) tl.apply(s), gran, toets, grens);
            return niet ? !b : b;
        };
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
        // §8.2 / §13.4.15.70 "gedurende de/het gehele jaar|maand": a whole-period check over a tijdsafhankelijk
        // onderwerp. A literal/own-attr grens (0 globals) is a fact-only leaf (handled in leafPredicate, reached by
        // the fallthrough); a Parameter-global grens binds the global as a pattern variable (a 2-var expr), like the
        // §6 datum-arith branch. An unsupported form -> skip the whole rule (symmetric with the DRL marker -> no fire).
        Conditie.GeheelPeriode gp82 = geheelPeriodeVan(c);
        if (gp82 != null) {
            Gp82 g = geheelPeriode82(c, gp82, subject);
            if (g == null) return null;
            Predicate1<Object> leaf = geheelPeriodeLeaf(g);
            if (leaf != null) return new Gate(pattern.expr(nextId("c"), (Predicate1) leaf), List.of());
            if (g.grens() != null && g.grens().globals().size() == 1) {
                String gn = g.grens().globals().iterator().next();
                Global<BigDecimal> gg = globals.get(gn);
                if (gg == null) return null;
                java.util.function.Function<Object, Object> tl = g.timeline(); WaardeFn gf = g.grens();
                int toets = g.toets(); String gran = g.gran(); boolean niet = g.niet();
                return new Gate(pattern.expr(nextId("c"), (Variable) gg, (Predicate2) (s, gv) -> {
                    BigDecimal grens = RegelSpraakRuntime.toBd(gf.fn().apply(s, bindGlobal(gn, gv)));
                    boolean b = RegelSpraakRuntime.rsGeheelPeriode((List<?>) tl.apply(s), gran, toets, grens);
                    return niet ? !b : b;
                }), List.of());
            }
            return null;   // >1 global / unresolved -> skip
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
            if (subject == null || !subject.heeftLid(la.attribuut())) return null;   // not the subject's own member (feitcreatie anker-first probe)
            if (subject.attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> Phase F
            if (!subject.dimensiesVan(la.attribuut()).isEmpty()) return null;   // §3.6 gedimensioneerd (a Map) -> marker
            if (!isNumeriekOp(v.op())) return null;   // EERDER/LATER (datum) -> Phase later
            Global<BigDecimal> g = globals.get(ident(pr.parameter().naam()));
            if (g == null) return null;
            String attr = la.attribuut();
            Conditie.Operator op = v.op();
            BigDecimal[] f = restatement(la.type(), pr.parameter().datatype());  // §5.1 Tabel 32 unit restatement
            return new Gate(pattern.expr(nextId("c"), (Variable) g,
                    (Predicate2) (s, gv) -> vergelijk(scale(num(s, attr), f[0]), scale((BigDecimal) gv, f[1]), op)), List.of());
        }
        // §8.1.1 a to-ONE NAVIGATED numeric comparison vs a Parameter-GLOBAL — the §12 reisduur "de reisduur van
        // zijn reis groter is dan <de bovengrens schijf>" shape. The global is a pattern variable (2-var expr),
        // navReader reads the navigated value null-safely. Mirrors DRL `reis!.x op global`.
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() != null
                && la.type().isGetalachtig() && isNumeriekOp(v.op()) && v.rechts() instanceof Expr.ParameterRef pr) {   // §4.8 numeriek OR percentage
            if (la.objecttype() == null || la.objecttype().attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> Phase F
            if (!la.objecttype().dimensiesVan(la.attribuut()).isEmpty()) return null;                                       // §3.6 gedimensioneerd -> marker
            String rol = navComparisonRole(la, subject);
            if (rol == null) return null;
            Global<BigDecimal> g = globals.get(ident(pr.parameter().naam()));
            if (g == null) return null;
            String attr = la.attribuut();
            Conditie.Operator op = v.op();
            BigDecimal[] f = restatement(la.type(), pr.parameter().datatype());  // §5.1 Tabel 32 unit restatement
            return new Gate(pattern.expr(nextId("c"), (Variable) g, (Predicate2) (s, gv) -> {
                Object rel = RegelSpraakRuntime.get(s, rol);
                if (rel == null) return false;   // mvel `rol!.attr` short-circuits the whole term on a null reference
                return vergelijk(scale(RegelSpraakRuntime.toBd(RegelSpraakRuntime.get(rel, attr)), f[0]), scale((BigDecimal) gv, f[1]), op);
            }), List.of());
        }
        // §8.1.1 a NUMERIC comparison where an operand is an EXPRESSION (arithmetic Binair / Functie / begrenzing /
        // afronding) and the comparison reads EXACTLY ONE numeric/percentage Parameter-GLOBAL — "zijn prijs plus de
        // toeslag groter is dan de drempel", "zijn prijs maal 2 groter is dan de grens", "de vertrektijd van zijn reis
        // plus de toeslag groter is dan zijn prijs". Bind that one global as a pattern variable (a 2-var expr), lowering
        // BOTH sides via scalarWaarde. DRL reads the global as a free name in the constraint operand. A NAVIGATED operand
        // inside the expr lowers via scalarWaarde's navigated read — set valueSubject around the calls (value-path-only,
        // like the leaf expr-comparison branch) so navReader resolves; an absent relation reads leeg -> leeg-as-0 in the
        // arithmetic (DRL's rsRelGetal mirror). 0 globals -> falls through to leafPredicate's fact-only expr-comparison
        // branch; >1 distinct globals -> falls through -> skip (a condition expr binds at most one global -> the N-var
        // PatternDSL.expr is a carded follow-up). parityOnveilig (a §3.6 dimensiecel / cross-unit +/-) -> skip (DRL marker).
        // Mirrors the date-arith-vs-global branch below.
        if (c instanceof Conditie.Vergelijking v && isNumeriekOp(v.op())
                && v.links().type() != null && v.links().type().isNumeriek()
                && v.rechts().type() != null && v.rechts().type().isNumeriek()
                && (numeriekeExpressie(v.links()) || numeriekeExpressie(v.rechts()))
                && !(parityOnveilig(v.links()) || parityOnveilig(v.rechts()))) {
            Objecttype bewaard = valueSubject; valueSubject = subject;   // enable scalarWaarde's navigated (value-path) read
            WaardeFn lw = scalarWaarde(v.links()), rw = scalarWaarde(v.rechts());
            valueSubject = bewaard;
            if (lw != null && rw != null) {
                Set<String> gset = new LinkedHashSet<>(lw.globals()); gset.addAll(rw.globals());
                if (gset.size() == 1) {
                    String gn = gset.iterator().next();
                    Global<BigDecimal> g = globals.get(gn);
                    if (g != null) {   // numeric/percentage global (a date/tekst global -> globals.get == null -> fall through -> skip)
                        BigDecimal[] f = restatement(v.links().type(), v.rechts().type());   // §5.1 Tabel 32 unit restatement
                        BiFunction<Object, Map<String, BigDecimal>, Object> lfn = lw.fn(), rfn = rw.fn();
                        Conditie.Operator op = v.op();
                        return new Gate(pattern.expr(nextId("c"), (Variable) g, (Predicate2) (s, gv) ->
                                vergelijk(scale((BigDecimal) lfn.apply(s, bindGlobal(gn, gv)), f[0]),
                                          scale((BigDecimal) rfn.apply(s, bindGlobal(gn, gv)), f[1]), op)), List.of());
                    }
                }
            }
            // 0 globals -> leafPredicate's expr-comparison branch; >1 / unresolved / non-numeric global -> falls through -> skip
        }
        // §8.1.1 a DATUM comparison where an operand is a §6/§13.4.16 date-arithmetic expression that reads EXACTLY
        // ONE Parameter-GLOBAL (the duration is a parameter, e.g. "de geboortedatum plus de MEERDERJARIGHEIDSLEEFTIJD
        // eerder is dan de peildatum"): bind that global as a pattern variable (a 2-var expr) and evaluate
        // datumVergelijkTemporal. DRL binds the global directly in the rsDatumReken constraint. 0 globals -> the
        // fact-only leafPredicate below; >1 global -> skip (a condition expr binds at most one global, like the
        // numeric vs-global branches above).
        if (c instanceof Conditie.Vergelijking v && isDatumVergelijkOp(v.op())
                && v.links().type().isDatum() && v.rechts().type().isDatum()
                && (isDatumRekenExpr(v.links()) || isDatumRekenExpr(v.rechts()))) {
            WaardeFn lw = scalarWaarde(v.links()), rw = scalarWaarde(v.rechts());
            if (lw != null && rw != null) {
                Set<String> gset = new LinkedHashSet<>(lw.globals()); gset.addAll(rw.globals());
                if (gset.size() == 1) {
                    String gn = gset.iterator().next();
                    Global<BigDecimal> g = globals.get(gn);
                    if (g != null) {
                        BiFunction<Object, Map<String, BigDecimal>, Object> lfn = lw.fn(), rfn = rw.fn();
                        Conditie.Operator op = v.op();
                        return new Gate(pattern.expr(nextId("c"), (Variable) g, (Predicate2) (s, gv) ->
                                datumVergelijkTemporal(lfn.apply(s, bindGlobal(gn, gv)), rfn.apply(s, bindGlobal(gn, gv)), op)), List.of());
                    }
                }
            }
            // 0 globals -> falls through to leafPredicate (covered); >1 / unresolved -> falls through -> skip
        }
        // §8.1.5 dagsoortcontrole "<datum> is een <dagsoort>" whose datum operand reads EXACTLY ONE NUMERIC Parameter-
        // GLOBAL (a §6 date-arithmetic expression whose duration is a numeric parameter, "de vluchtdatum plus de MARGE
        // een werkdag is"): bind that global as a pattern variable (a 2-var expr), mirroring the date-arith-vs-global
        // comparison branch above. DRL reads the global as a free name directly in the rsDagsoort_<N> constraint; the
        // exec pattern needs it as a Variable. A fact-only (0-global) operand falls through to leafPredicate. The cases
        // this branch does NOT take — a >1-global operand, a NAVIGATED operand (scalarWaarde's nav read needs the
        // value-path subject, null on the condition path), or a non-numeric date-typed global (globals.get == null) —
        // fall through to an HONEST RECORDED skip (overgeslagen). Those are NOT byte-parity with DRL (DRL fires them);
        // the differential EXCLUDES an overgeslagen fixture, so each is a documented asymmetry, never a SILENT
        // divergence — close-list follow-ups, not silent wrongs. §5.15: an absent/non-date value is ONWAAR.
        if (c instanceof Conditie.Predicaat dp && dp.soort() == Conditie.Predicaatsoort.DAGSOORT && dp.operand().type().isDatum()) {
            WaardeFn wf = scalarWaarde(dp.operand());
            if (wf != null && wf.globals().size() == 1) {
                String gn = wf.globals().iterator().next();
                Global<BigDecimal> g = globals.get(gn);   // null for a non-numeric (date/tekst) global -> honest skip below
                java.util.function.Predicate<java.time.LocalDate> pred = dagsoortPredikaat(dp.dagsoort());
                if (g != null && pred != null) {
                    BiFunction<Object, Map<String, BigDecimal>, Object> fn = wf.fn();
                    boolean neg = dp.negatie();   // §8.1.5 + Tabel 16 "is geen <dagsoort>"
                    return new Gate(pattern.expr(nextId("c"), (Variable) g, (Predicate2) (s, gv) ->
                            neg != (fn.apply(s, bindGlobal(gn, gv)) instanceof java.time.LocalDate ld && pred.test(ld))), List.of());
                }
            }
            // 0 globals -> the fact-only leafPredicate below; >1 / navigated / date-typed -> fall through -> honest skip
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
    /** The string label of a comparison RHS that is a §13.3.3.4 Tekst literal (double quotes stripped) or a §13.2.14
     *  enumeratiewaarde 'X' (single quotes stripped, straight or typographic). null otherwise — both compare by
     *  VALUE equality, mirroring {@link io.github.yhavinga.regelspraak.transpiler.DrlTranspiler#enumLiteralLabel}. */
    private static String tekstOfEnumLabel(Expr e) {
        if (!(e instanceof Expr.Literal lit)) return null;
        if (lit.type().isTekst()) return lit.waarde().replace("\"", "");
        String w = lit.waarde(); int n = w.length();
        if (n >= 2 && w.charAt(0) == '\'' && w.charAt(n - 1) == '\'') return w.substring(1, n - 1);
        if (n >= 2 && w.charAt(0) == '’' && w.charAt(n - 1) == '‘') return w.substring(1, n - 1);
        return null;
    }

    private Predicate1<Object> leafPredicate(Conditie c, Objecttype subject) {
        // §8.2 a whole-period-qualified predicate over a tijdsafhankelijk onderwerp, with a literal/own-attr grens
        // (0 globals) -> a fact-only "the check holds" predicate. A grens reading a Parameter-global returns null here
        // (handled with a bound global in voorwaarde); inside a counting quantifier that null skips the whole compound.
        Conditie.GeheelPeriode gp82 = geheelPeriodeVan(c);
        if (gp82 != null) return geheelPeriodeLeaf(geheelPeriode82(c, gp82, subject));
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
            if (subject == null || !subject.heeftLid(pa.attribuut())) return null;   // not the subject's own member (e.g. a feitcreatie anker-first probe of a doel condition)
            if (subject.attribuutGranulariteit.containsKey(pa.attribuut())) return null;  // temporal -> Phase F
            if (!subject.dimensiesVan(pa.attribuut()).isEmpty()) return null;   // §3.6 gedimensioneerd (a Map) -> marker
            String attr = pa.attribuut();
            boolean leeg = p.soort() == Conditie.Predicaatsoort.LEEG;
            return s -> (RegelSpraakRuntime.get(s, attr) == null) == leeg;
        }
        // §8.1.2 LEEG/GEVULD over a to-ONE NAVIGATED operand ("de woonplaats van zijn partner is leeg"). Unlike a
        // comparison, this is NOT the mvel `!.` short-circuit: an ABSENT reference reads the value as EMPTY (typeringen
        // Tabel 53/54 — an absent value IS leeg -> waar), so DRL guards the reference explicitly (genavigeerdLeegGevuld).
        // leeg = (rol == null || rol.attr == null); gevuld = (rol != null && rol.attr != null).
        if (c instanceof Conditie.Predicaat p
                && (p.soort() == Conditie.Predicaatsoort.LEEG || p.soort() == Conditie.Predicaatsoort.GEVULD)
                && p.operand() instanceof Expr.AttribuutRef pa && pa.navigatieRol() != null) {
            if (pa.objecttype() == null || pa.objecttype().attribuutGranulariteit.containsKey(pa.attribuut())) return null;  // temporal -> Phase F
            if (!pa.objecttype().dimensiesVan(pa.attribuut()).isEmpty()) return null;   // §3.6 gedimensioneerd -> marker
            String rol = navComparisonRole(pa, subject);
            if (rol == null) return null;
            String attr = pa.attribuut();
            boolean leeg = p.soort() == Conditie.Predicaatsoort.LEEG;
            return s -> {
                Object rel = RegelSpraakRuntime.get(s, rol);
                boolean isLeeg = rel == null || RegelSpraakRuntime.get(rel, attr) == null;
                return isLeeg == leeg;
            };
        }
        // §8.1.3 elfproef / §8.1.4 numeriek-met-exact over an OWN or to-one NAVIGATED operand. Read the value (a Tekst
        // String or a Numeriek BigDecimal) and apply the runtime predicate XOR the §8.1 Tabel-16 negatie. A navigated
        // null relation makes the WHOLE constraint a non-match (onwaar for both polarities), mirroring the mvel
        // `rol!.attr` short-circuit in the DRL. The runtime helpers are byte-identical to the DRL rsElfproef /
        // rsNumeriekMetExact (the differential gate).
        if (c instanceof Conditie.Predicaat p
                && (p.soort() == Conditie.Predicaatsoort.ELFPROEF || p.soort() == Conditie.Predicaatsoort.NUMERIEK_MET_EXACT)
                && p.operand() instanceof Expr.AttribuutRef pa) {
            if (pa.objecttype() != null && pa.objecttype().attribuutGranulariteit.containsKey(pa.attribuut())) return null;  // temporal -> Phase F
            if (pa.objecttype() != null && !pa.objecttype().dimensiesVan(pa.attribuut()).isEmpty()) return null;            // §3.6 gedimensioneerd -> marker
            boolean elf = p.soort() == Conditie.Predicaatsoort.ELFPROEF;
            int n = p.cijfers();
            boolean wanted = !p.negatie();
            String attr = pa.attribuut();
            if (pa.navigatieRol() == null) {
                if (subject == null || !subject.heeftLid(attr)) return null;   // not the subject's own member
                return s -> (elf ? RegelSpraakRuntime.rsElfproef(RegelSpraakRuntime.get(s, attr))
                                 : RegelSpraakRuntime.rsNumeriekMetExact(RegelSpraakRuntime.get(s, attr), n)) == wanted;
            }
            String rol = navComparisonRole(pa, subject);
            if (rol == null) return null;
            return s -> {
                Object rel = RegelSpraakRuntime.get(s, rol);
                if (rel == null) return false;   // mvel `rol!.attr` short-circuits the WHOLE constraint to non-match on a
                                                 // null reference -> onwaar for BOTH polarities (the navigated null house rule)
                return (elf ? RegelSpraakRuntime.rsElfproef(RegelSpraakRuntime.get(rel, attr))
                            : RegelSpraakRuntime.rsNumeriekMetExact(RegelSpraakRuntime.get(rel, attr), n)) == wanted;
            };
        }
        // §8.1.1 an own-attribute scalar comparison vs a numeric literal.
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() == null
                && v.rechts() instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            if (subject == null || !subject.heeftLid(la.attribuut())) return null;   // not the subject's own member (feitcreatie anker-first probe)
            if (subject.attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> Phase F
            if (!subject.dimensiesVan(la.attribuut()).isEmpty()) return null;   // §3.6 gedimensioneerd (a Map) -> marker
            if (!isNumeriekOp(v.op())) return null;   // EERDER/LATER (datum) -> Phase later
            BigDecimal rhs = bd(lit);
            if (rhs == null) return null;
            String attr = la.attribuut();
            Conditie.Operator op = v.op();
            BigDecimal[] f = restatement(la.type(), lit.type());   // §5.1 Tabel 32 unit restatement (e.g. mnd vs jr)
            BigDecimal rhsScaled = scale(rhs, f[1]);
            return s -> vergelijk(scale(num(s, attr), f[0]), rhsScaled, op);
        }
        // §8.1.1 a to-ONE NAVIGATED numeric comparison vs a numeric literal ("de reisduur van zijn reis groter is dan
        // 60"): read the navigated value null-safely (mvel's `reis!.x`), restate units, and vergelijk. navReader
        // resolves the to-one role and root-guards a null relation (-> leeg -> ordering no-match / GELIJK-both-leeg,
        // matching mvel `null op x`); a to-many / unresolvable navigation -> null (skip). Mirrors DRL `reis!.x op lit`.
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() != null
                && la.type().isNumeriek() && isNumeriekOp(v.op())
                && v.rechts() instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            if (la.objecttype() == null || la.objecttype().attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> Phase F
            if (!la.objecttype().dimensiesVan(la.attribuut()).isEmpty()) return null;                                       // §3.6 gedimensioneerd (Map) -> marker
            String rol = navComparisonRole(la, subject);
            if (rol == null) return null;
            BigDecimal rhs = bd(lit);
            if (rhs == null) return null;
            String attr = la.attribuut();
            Conditie.Operator op = v.op();
            BigDecimal[] f = restatement(la.type(), lit.type());
            BigDecimal rhsScaled = scale(rhs, f[1]);
            return s -> {
                Object rel = RegelSpraakRuntime.get(s, rol);
                // §5.5.5 a null to-one relation reads LEEG (like a present relation with a null attribute), then vergelijk's
                // leegwaarde semantics decide (Tabel 33-44): ordering/GELIJK of a leeg operand -> onwaar, but ONGELIJK ->
                // WAAR (Tabel 44, one-leeg). NOT a blanket short-circuit (which would make a leeg-vs-X ONGELIJK wrongly false).
                Object attrVal = rel == null ? null : RegelSpraakRuntime.get(rel, attr);
                return vergelijk(scale(RegelSpraakRuntime.toBd(attrVal), f[0]), rhsScaled, op);
            };
        }
        // §8.1.1 a to-ONE NAVIGATED numeric comparison vs an OWN attribute ("de reisduur van zijn reis groter is dan
        // zijn drempel"): the navigated LHS read null-safely (a null relation short-circuits the whole term), the own
        // RHS fact-local. Mirrors DRL `rol!.x op ownAttr`.
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() != null
                && la.type().isNumeriek() && isNumeriekOp(v.op())
                && v.rechts() instanceof Expr.AttribuutRef ra && ra.navigatieRol() == null && ra.type().isNumeriek()) {
            if (la.objecttype() == null || la.objecttype().attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> Phase F
            if (!la.objecttype().dimensiesVan(la.attribuut()).isEmpty()) return null;                                       // §3.6 -> marker
            if (subject == null || !subject.heeftLid(ra.attribuut())) return null;                                          // own RHS member
            if (subject.attribuutGranulariteit.containsKey(ra.attribuut())) return null;                                    // temporal RHS -> Phase F
            if (!subject.dimensiesVan(ra.attribuut()).isEmpty()) return null;                                               // §3.6 RHS -> marker
            String rol = navComparisonRole(la, subject);
            if (rol == null) return null;
            String lattr = la.attribuut(), rattr = ra.attribuut();
            Conditie.Operator op = v.op();
            BigDecimal[] f = restatement(la.type(), ra.type());
            return s -> {
                Object rel = RegelSpraakRuntime.get(s, rol);
                Object lval = rel == null ? null : RegelSpraakRuntime.get(rel, lattr);   // §5.5.5 null relation -> leeg -> vergelijk (Tabel 44)
                return vergelijk(scale(RegelSpraakRuntime.toBd(lval), f[0]), scale(num(s, rattr), f[1]), op);
            };
        }
        // §8.1.1 an OWN-attribute numeric comparison vs ANOTHER own attribute ("de prijs groter is dan de drempel"):
        // both fact-local reads, unit-restated, vergelijk (mvel's null semantics — ordering no-match on a leeg
        // operand, GELIJK true only both-leeg). Mirrors DRL's `attrA op attrB`. (vs-literal handled above; a global
        // or navigated operand lives in voorwaarde / the navigated branch.)
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() == null
                && la.type().isNumeriek() && isNumeriekOp(v.op())
                && v.rechts() instanceof Expr.AttribuutRef ra && ra.navigatieRol() == null && ra.type().isNumeriek()) {
            if (subject == null || !subject.heeftLid(la.attribuut()) || !subject.heeftLid(ra.attribuut())) return null;
            if (subject.attribuutGranulariteit.containsKey(la.attribuut())
                    || subject.attribuutGranulariteit.containsKey(ra.attribuut())) return null;          // temporal -> Phase F
            if (!subject.dimensiesVan(la.attribuut()).isEmpty() || !subject.dimensiesVan(ra.attribuut()).isEmpty()) return null;  // §3.6 -> marker
            String lattr = la.attribuut(), rattr = ra.attribuut();
            Conditie.Operator op = v.op();
            BigDecimal[] f = restatement(la.type(), ra.type());
            return s -> vergelijk(scale(num(s, lattr), f[0]), scale(num(s, rattr), f[1]), op);
        }
        // §8.1.1 a DATUM ordering comparison (eerder/later/eerder-of-gelijk/later-of-gelijk) of an OWN or to-one
        // NAVIGATED datum attribute vs a §13.2.19 datum literal. DRL lowers `attr < LocalDate.of(...)` (LocalDate is
        // Comparable); an absent datum -> onwaar (ordering no-match), matching mvel. A navigated null relation
        // short-circuits the whole term (mvel `rol!.attr`), like the navigated numeric comparison.
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.type().isDatum()
                && isDatumOp(v.op()) && v.rechts() instanceof Expr.Literal lit && lit.type().isDatum()) {
            if (la.objecttype() == null || la.objecttype().attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> Phase F
            java.time.LocalDate rhs = parseDatum(lit.waarde());
            if (rhs == null) return null;
            Conditie.Operator op = v.op();
            String attr = la.attribuut();
            if (la.navigatieRol() == null) {
                if (!subject.heeftLid(la.attribuut())) return null;   // not the subject's own member
                return s -> { Object a = RegelSpraakRuntime.get(s, attr); return a instanceof java.time.LocalDate ld && datumVergelijk(ld, rhs, op); };
            }
            String rol = navComparisonRole(la, subject);
            if (rol == null) return null;
            return s -> {
                Object rel = RegelSpraakRuntime.get(s, rol);
                if (rel == null) return false;   // mvel `rol!.attr` short-circuits the whole term on a null reference
                Object a = RegelSpraakRuntime.get(rel, attr);
                return a instanceof java.time.LocalDate ld && datumVergelijk(ld, rhs, op);
            };
        }
        // §8.1.1 a DATUM ordering comparison where an operand is a §6/§13.4.16 date-arithmetic EXPRESSION ("de
        // daadwerkelijke vertrek later is dan de vluchtdatum plus de uitstelduur"). The simple attr-vs-literal form
        // is handled above; here at least one side is a date-arith Binair, so BOTH sides lower via scalarWaarde
        // (-> a Temporal) and compare via datumVergelijkTemporal. A fact-only reader (0 globals). DRL lowers the
        // same expression as the constraint operand (`attr > ((LocalDate) rsDatumReken(...))`).
        if (c instanceof Conditie.Vergelijking v && isDatumVergelijkOp(v.op())
                && v.links().type().isDatum() && v.rechts().type().isDatum()
                && (isDatumRekenExpr(v.links()) || isDatumRekenExpr(v.rechts()))) {
            WaardeFn lw = scalarWaarde(v.links()), rw = scalarWaarde(v.rechts());
            if (lw == null || rw == null || !lw.globals().isEmpty() || !rw.globals().isEmpty()) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> lfn = lw.fn(), rfn = rw.fn();
            Conditie.Operator op = v.op();
            return s -> datumVergelijkTemporal(lfn.apply(s, Map.of()), rfn.apply(s, Map.of()), op);
        }
        // §5.3/§5.6 a DATUM EQUALITY (gelijk/ongelijk) of an OWN or to-one NAVIGATED datum attribute vs a §13.2.19 datum
        // LITERAL or another own/navigated datum attribute. An absent navigation / leeg datum reads LEEGWAARDE (§5.5.5),
        // then the §5 leegwaarde tables decide (datumGelijk): GELIJK both-leeg -> fout/onwaar (Tabel 38, NON-numeric — NOT
        // Tabel 37's WAAR) and one-leeg -> onwaar; ONGELIJK one-leeg -> WAAR (Tabel 44) and both-leeg -> onwaar. Mirrors
        // DrlTranspiler's genavigeerdeVergelijkingVoorwaarde (navigated) / non-numeriek schending complement (own). The
        // date-ARITHMETIC equality (an operand is a §6 Binair) is handled by the branch above (isDatumRekenExpr).
        if (c instanceof Conditie.Vergelijking v && (v.op() == Conditie.Operator.GELIJK || v.op() == Conditie.Operator.ONGELIJK)
                && v.links() instanceof Expr.AttribuutRef la && la.type() != null && la.type().isDatum()) {
            java.util.function.Function<Object, java.time.LocalDate> lf = datumLezer(la, subject);
            if (lf == null) return null;
            Conditie.Operator op = v.op();
            if (v.rechts() instanceof Expr.Literal lit && lit.type().isDatum()) {
                java.time.LocalDate rhs = parseDatum(lit.waarde());
                if (rhs == null) return null;
                return s -> datumGelijk(lf.apply(s), rhs, op);
            }
            if (v.rechts() instanceof Expr.AttribuutRef ra && ra.type() != null && ra.type().isDatum()) {
                java.util.function.Function<Object, java.time.LocalDate> rf = datumLezer(ra, subject);
                if (rf == null) return null;
                return s -> datumGelijk(lf.apply(s), rf.apply(s), op);
            }
            return null;   // datum-vs-(global/expr/other) -> not a fact-only datum-equality leaf
        }
        // §8.1.1 a comparison whose LHS is a NUMERIC EXPRESSION — a builtin Functie ("de maand uit (...)", abs), an
        // arithmetic Binair ("de prijs plus 10"), a §6.1.4 begrenzing or a §13.4.17.25 afronding — vs a numeric
        // literal. Lower the LHS via scalarWaarde (which now covers these); a fact-only reader (0 globals) makes it a
        // Predicate1, then vergelijk + restatement. DRL lowers the same expression as a constraint operand. A NAVIGATED
        // operand inside the LHS ("de vertrektijd van zijn reis plus 1 > 100") lowers via scalarWaarde's navigated read —
        // valueSubject set around the call (value-path-only); an ABSENT relation reads leeg -> leeg-as-0 in the arithmetic
        // (see the expr-vs-expr branch below for the rsRelGetal parity). A param-reading LHS keeps globals non-empty ->
        // skipped (the global path binds it in voorwaarde).
        if (c instanceof Conditie.Vergelijking v && isNumeriekOp(v.op()) && v.links().type().isNumeriek()
                && (v.links() instanceof Expr.Functie || v.links() instanceof Expr.Binair
                    || v.links() instanceof Expr.Begrensd || v.links() instanceof Expr.Afgerond)
                && v.rechts() instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            if (parityOnveilig(v.links())) return null;   // §3.6 dimensiecel / cross-unit +/- -> DRL markers -> skip (symmetric)
            Objecttype bewaard = valueSubject; valueSubject = subject;   // enable scalarWaarde's navigated (value-path) read
            WaardeFn lw = scalarWaarde(v.links());
            valueSubject = bewaard;
            if (lw == null || !lw.globals().isEmpty()) return null;   // unlowerable / unresolvable navigation / needs a global -> skip
            BigDecimal rhs = bd(lit);
            if (rhs == null) return null;
            BigDecimal[] f = restatement(v.links().type(), lit.type());
            BigDecimal rhsScaled = scale(rhs, f[1]);
            BiFunction<Object, Map<String, BigDecimal>, Object> lfn = lw.fn();
            Conditie.Operator op = v.op();
            return s -> vergelijk(scale((BigDecimal) lfn.apply(s, Map.of()), f[0]), rhsScaled, op);
        }
        // §8.1.1 a comparison where at least one side is a NUMERIC EXPRESSION (arithmetic Binair / builtin Functie /
        // begrenzing / afronding) and the OTHER side is a numeric attribute OR also an expression — NOT a literal (the
        // branch above). Lower BOTH sides via scalarWaarde (the value-path lowering, at parity with DrlTranspiler.binairTerm
        // on the value side), then vergelijk + §5.1 restatement. parityOnveilig skips a §3.6 dimensiecel and a cross-unit
        // +/- (DRL markers those). A NAVIGATED operand inside the expr ("de prijs van zijn reis plus 1 groter is dan zijn
        // drempel") lowers via scalarWaarde's navigated read — set valueSubject around the calls (value-path-only, like the
        // §8.2 grens path) so navReader resolves. An ABSENT relation reads LEEG, which the arithmetic treats per the §4/§6
        // leegwaarde tables (leeg-as-0 in +/-/*, §4.1 Tabel 3 etc.) and then vergelijk's leegwaarde semantics — NOT a
        // short-circuit-to-false (DRL's mvel `rol!.attr` short-circuit was a latent bug; DrlTranspiler now reads a navigated
        // arithmetic leg null-safely via rsRelGetal, so both back-ends compute leeg-as-0). A GLOBAL operand keeps globals
        // non-empty -> skip (the expr-vs-global path binds at most one global, in voorwaarde); an unresolvable / to-many
        // navigation -> scalarWaarde null -> skip. Closes the masked DRL-fires/exec-skips for "de a plus de b is gelijk aan
        // de c" / "de prijs van zijn reis plus 1 groter is dan zijn drempel".
        if (c instanceof Conditie.Vergelijking v && isNumeriekOp(v.op())
                && v.links().type() != null && v.links().type().isNumeriek()
                && v.rechts().type() != null && v.rechts().type().isNumeriek()
                && (numeriekeExpressie(v.links()) || numeriekeExpressie(v.rechts()))) {
            if (parityOnveilig(v.links()) || parityOnveilig(v.rechts())) return null;   // §3.6 dimensiecel / cross-unit +/- -> DRL markers -> skip (symmetric)
            Objecttype bewaard = valueSubject; valueSubject = subject;   // enable scalarWaarde's navigated (value-path) read
            WaardeFn lw = scalarWaarde(v.links()), rw = scalarWaarde(v.rechts());
            valueSubject = bewaard;
            if (lw == null || rw == null || !lw.globals().isEmpty() || !rw.globals().isEmpty()) return null;   // unlowerable / unresolvable navigation / needs a global -> skip
            BigDecimal[] f = restatement(v.links().type(), v.rechts().type());
            BiFunction<Object, Map<String, BigDecimal>, Object> lfn = lw.fn(), rfn = rw.fn();
            Conditie.Operator op = v.op();
            return s -> vergelijk(scale((BigDecimal) lfn.apply(s, Map.of()), f[0]), scale((BigDecimal) rfn.apply(s, Map.of()), f[1]), op);
        }
        // §5.3 an own-attribute equality vs a §13.3.3.4 Tekst literal OR a §13.2.14 enumeratiewaarde 'X' (the §12
        // beslistabel "is gelijk aan X" cell, and the §12 OR-list of them). An enumeratie compares by VALUE equality
        // on its label, so it lowers exactly like a Tekst. mvel string '==' is VALUE equality (.equals); ONGELIJK is
        // null-asymmetric (mvel reads `null != "x"` as TRUE) — mirror DrlTranspiler's `prop == "lit"`.
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() == null
                && v.rechts() instanceof Expr.Literal lit && tekstOfEnumLabel(lit) != null
                && (v.op() == Conditie.Operator.GELIJK || v.op() == Conditie.Operator.ONGELIJK)) {
            if (subject == null || !subject.heeftLid(la.attribuut())) return null;            // not the subject's own member
            if (subject.attribuutGranulariteit.containsKey(la.attribuut())) return null;      // temporal -> Phase F
            if (!subject.dimensiesVan(la.attribuut()).isEmpty()) return null;                 // §3.6 gedimensioneerd -> marker
            String attr = la.attribuut();
            String rhs = tekstOfEnumLabel(lit);   // the DRL strips the tekstwaarde "/enumeratiewaarde ' delimiters the same way
            boolean gelijk = v.op() == Conditie.Operator.GELIJK;
            return s -> rhs.equals(RegelSpraakRuntime.get(s, attr)) == gelijk;   // attr may be null; rhs non-null
        }
        // §5.3 the to-ONE NAVIGATED Tekst/enumeratiewaarde equality ("de woonplaats van zijn partner is gelijk aan
        // 'X'"): §5.5.5 a null relation reads LEEG (the SAME as a present relation with a null attribute), then string
        // equality decides — GELIJK of leeg vs the literal -> onwaar, ONGELIJK of leeg vs the literal -> WAAR (Tabel 44).
        // NOT a blanket short-circuit. Mirrors the navigated numeric comparison's null-relation handling.
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() != null
                && v.rechts() instanceof Expr.Literal lit && tekstOfEnumLabel(lit) != null
                && (v.op() == Conditie.Operator.GELIJK || v.op() == Conditie.Operator.ONGELIJK)) {
            if (la.objecttype() == null || la.objecttype().attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> Phase F
            if (!la.objecttype().dimensiesVan(la.attribuut()).isEmpty()) return null;                                       // §3.6 gedimensioneerd -> marker
            String rol = navComparisonRole(la, subject);
            if (rol == null) return null;
            String attr = la.attribuut();
            String rhs = tekstOfEnumLabel(lit);
            boolean gelijk = v.op() == Conditie.Operator.GELIJK;
            return s -> {
                Object rel = RegelSpraakRuntime.get(s, rol);
                Object val = rel == null ? null : RegelSpraakRuntime.get(rel, attr);   // §5.5.5 null relation -> leeg
                return rhs.equals(val) == gelijk;   // leeg != literal -> ONGELIJK WAAR (Tabel 44); leeg == literal -> GELIJK onwaar
            };
        }
        // §8.1.5 dagsoortcontrole "<datum> is een <dagsoort>": test an OWN or to-one NAVIGATED datum attribute
        // against the dagsoort's §9.8 definitie predicate (the mirror of DrlTranspiler's `attr != null &&
        // rsDagsoort_<Naam>(attr)`). §5.15: een leegwaarde is nooit een dagsoort -> an absent datum is ONWAAR.
        if (c instanceof Conditie.Predicaat p && p.soort() == Conditie.Predicaatsoort.DAGSOORT
                && p.operand() instanceof Expr.AttribuutRef la && la.type().isDatum()) {
            if (la.objecttype() == null || la.objecttype().attribuutGranulariteit.containsKey(la.attribuut())) return null;  // temporal -> skip
            java.util.function.Predicate<java.time.LocalDate> pred = dagsoortPredikaat(p.dagsoort());
            if (pred == null) return null;
            String attr = la.attribuut();
            boolean neg = p.negatie();   // §8.1.5 + Tabel 16 "is geen <dagsoort>": a leeg/non-dagsoort datum is WAAR
            if (la.navigatieRol() == null) {
                if (!subject.heeftLid(attr)) return null;
                return s -> neg != (RegelSpraakRuntime.get(s, attr) instanceof java.time.LocalDate ld && pred.test(ld));
            }
            String rol = navComparisonRole(la, subject);
            if (rol == null) return null;
            return s -> {
                Object rel = RegelSpraakRuntime.get(s, rol);
                if (rel == null) return false;   // mvel `rol!.attr` short-circuits the whole term to non-match (BOTH polarities)
                return neg != (RegelSpraakRuntime.get(rel, attr) instanceof java.time.LocalDate ld && pred.test(ld));
            };
        }
        // §8.1.5 dagsoortcontrole over a NON-attribute datum operand (a §6 date-arithmetic expression "de vluchtdatum
        // plus 1 dag is een kerstdag", a date-valued function) — lower it via scalarWaarde, mirroring DrlTranspiler's
        // generic operand path so the back-ends stay at parity. Fact-only (no globals); a global-reading operand is not
        // a fact-only leaf -> skip. §5.15: an absent/non-date value is ONWAAR.
        if (c instanceof Conditie.Predicaat p && p.soort() == Conditie.Predicaatsoort.DAGSOORT && p.operand().type().isDatum()) {
            java.util.function.Predicate<java.time.LocalDate> pred = dagsoortPredikaat(p.dagsoort());
            WaardeFn wf = scalarWaarde(p.operand());
            if (pred == null || wf == null || !wf.globals().isEmpty()) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> fn = wf.fn();
            boolean neg = p.negatie();   // §8.1.5 + Tabel 16 "is geen <dagsoort>"
            return s -> neg != (fn.apply(s, Map.of()) instanceof java.time.LocalDate ld && pred.test(ld));
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

    // ---------------- §9.8 dagsoort definitie -> a per-date Predicate<LocalDate> ----------------

    /** Compile (idempotently, cycle-safe) the §9.8 definitie of {@code naam} to a Predicate&lt;LocalDate&gt;; null
     *  when no such definitie exists or its condition is not a pure date predicate. A nested dagsoort reference
     *  resolves via a DEFERRED map lookup, so cross-references work regardless of compilation order and a
     *  (spec-forbidden, §9.10) cyclic definitie cannot infinite-loop. Mirrors DrlTranspiler.dagsoortFunctie. */
    private java.util.function.Predicate<java.time.LocalDate> dagsoortPredikaat(String naam) {
        String key = naam.toLowerCase();
        java.util.function.Predicate<java.time.LocalDate> have = dagsoortPredikaten.get(key);
        if (have != null) return have;
        if (dagsoortBezig.contains(key))   // cyclic reference: defer to the eventual map entry
            return d -> { var p = dagsoortPredikaten.get(key); return p != null && p.test(d); };
        io.github.yhavinga.regelspraak.ir.ResolvedDagsoort def = null;
        for (var d : model.resolvedDagsoorten) if (d.naam().equalsIgnoreCase(naam)) { def = d; break; }
        if (def == null) return null;
        dagsoortBezig.add(key);
        java.util.function.Predicate<java.time.LocalDate> body = dagsoortBoolPred(def.voorwaarde());
        dagsoortBezig.remove(key);
        if (body != null) dagsoortPredikaten.put(key, body);
        return body;
    }

    /** Lower a §9.8 condition-over-the-day to a Predicate&lt;LocalDate&gt;; null when any leaf is not a pure date
     *  predicate. Mirrors DrlTranspiler.dagsoortBool (Vergelijking via Comparable.compareTo, Samengesteld quantifiers). */
    private java.util.function.Predicate<java.time.LocalDate> dagsoortBoolPred(Conditie c) {
        if (c instanceof Conditie.Vergelijking v) {
            // mirror DrlTranspiler.dagsoortBool: a type-mismatched comparison (datum vs numeric) can never compare via
            // Comparable.compareTo -> marker, so the two back-ends stay symmetric (DRL would emit invalid Java otherwise).
            boolean compat = (v.links().type().isNumeriek() && v.rechts().type().isNumeriek())
                    || (v.links().type().isDatum() && v.rechts().type().isDatum());
            if (!compat) return null;
            java.util.function.Function<java.time.LocalDate, Object> lo = dagWaarde(v.links()), ro = dagWaarde(v.rechts());
            if (lo == null || ro == null) return null;
            Conditie.Operator op = v.op();
            return d -> vergelijkComparable(lo.apply(d), ro.apply(d), op);
        }
        if (c instanceof Conditie.Predicaat p && p.soort() == Conditie.Predicaatsoort.DAGSOORT) {
            java.util.function.Function<java.time.LocalDate, Object> o = dagWaarde(p.operand());
            java.util.function.Predicate<java.time.LocalDate> inner = dagsoortPredikaat(p.dagsoort());
            if (o == null || inner == null) return null;
            boolean neg = p.negatie();   // §8.1.5 + Tabel 16 "(de dag) is geen <dagsoort>" inside a §9.8 definitie
            return d -> neg != (o.apply(d) instanceof java.time.LocalDate ld && inner.test(ld));
        }
        if (c instanceof Conditie.Samengesteld s) {
            List<java.util.function.Predicate<java.time.LocalDate>> delen = new ArrayList<>();
            for (Conditie deel : s.onderdelen()) { var d = dagsoortBoolPred(deel); if (d == null) return null; delen.add(d); }
            if (delen.isEmpty()) return null;
            int n = s.drempel();
            return switch (s.kwantor()) {
                case ALLE, DE    -> d -> delen.stream().allMatch(q -> q.test(d));
                case GEEN        -> d -> delen.stream().noneMatch(q -> q.test(d));
                case TEN_MINSTE  -> d -> delen.stream().filter(q -> q.test(d)).count() >= n;
                case TEN_HOOGSTE -> d -> delen.stream().filter(q -> q.test(d)).count() <= n;
                case PRECIES     -> d -> delen.stream().filter(q -> q.test(d)).count() == n;
            };
        }
        return null;
    }

    /** A §9.8 operand over "de dag" as a Function&lt;LocalDate,Object&gt; (the day itself, a date-component of it via
     *  rsDatumDeel, or a fact-free literal); null when not a pure date operand. Mirrors DrlTranspiler.dagOperand. */
    private java.util.function.Function<java.time.LocalDate, Object> dagWaarde(Expr e) {
        if (e instanceof Expr.Constante c && "de dag".equalsIgnoreCase(c.naam())) return d -> d;
        if (e instanceof Expr.Functie f && f.argumenten().size() == 1) {
            java.time.temporal.ChronoField cf = switch (f.kind()) {
                case JAAR_UIT         -> java.time.temporal.ChronoField.YEAR;
                case MAAND_UIT        -> java.time.temporal.ChronoField.MONTH_OF_YEAR;
                case DAG_UIT          -> java.time.temporal.ChronoField.DAY_OF_MONTH;
                case UUR_UIT          -> java.time.temporal.ChronoField.HOUR_OF_DAY;
                case MINUUT_UIT       -> java.time.temporal.ChronoField.MINUTE_OF_HOUR;
                case SECONDE_UIT      -> java.time.temporal.ChronoField.SECOND_OF_MINUTE;
                case MILLISECONDE_UIT -> java.time.temporal.ChronoField.MILLI_OF_SECOND;
                default               -> null;
            };
            java.util.function.Function<java.time.LocalDate, Object> inner = dagWaarde(f.argumenten().get(0));
            if (inner == null) return null;
            if (cf != null) return d -> RegelSpraakRuntime.rsDatumDeel(inner.apply(d), cf);
            // §13.4.17.37 "de eerste paasdag van <jaar>" — the paasdag/feestdag building block (mirrors DrlTranspiler's
            // dagOperand, which lets EERSTE_PAASDAG through scalarFunctie). Other functies stay a symmetric marker.
            if (f.kind() == Expr.FunctieKind.EERSTE_PAASDAG) return d -> RegelSpraakRuntime.rsEerstePaasdag(inner.apply(d));
            return null;
        }
        if (e instanceof Expr.Literal lit) {
            if (lit.type().isDatum()) { java.time.LocalDate ld = parseDatum(lit.waarde()); return ld == null ? null : d -> ld; }
            BigDecimal n = bd(lit); return n == null ? null : d -> n;
        }
        return null;
    }

    /** Compare two §9.8 operands (BigDecimal date-components or LocalDate values) by Comparable.compareTo. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean vergelijkComparable(Object a, Object b, Conditie.Operator op) {
        if (!(a instanceof Comparable) || b == null) return false;
        int c = ((Comparable) a).compareTo(b);
        return switch (op) {
            case GELIJK -> c == 0;  case ONGELIJK -> c != 0;
            case GROTER, LATER -> c > 0;  case KLEINER, EERDER -> c < 0;
            case GROTER_OF_GELIJK, LATER_OF_GELIJK -> c >= 0;  case KLEINER_OF_GELIJK, EERDER_OF_GELIJK -> c <= 0;
        };
    }

    /** A globally-unique constraint id (see {@link #idSeq}) — unique across the whole model, not just one rule. */
    private String nextId(String prefix) { return prefix + "_" + (idSeq++); }

    /** §4.2: true when ANY construct of ANY family carries a time-bounded (dated) version — the trigger for
     *  registering the Rekendatum global. Mirrors DrlTranspiler's `tijdgebonden` disjunction over every family. */
    private boolean heeftTijdgebonden() {
        java.util.function.Predicate<io.github.yhavinga.regelspraak.ir.Geldigheid> dated = g -> g != null && !g.altijd();
        return model.resolvedRegels.stream().anyMatch(r -> dated.test(r.geldigheid()))
                || model.objectCreaties.stream().anyMatch(o -> dated.test(o.geldigheid()))
                || model.verdelingen.stream().anyMatch(v -> dated.test(v.geldigheid()))
                || model.consistenties.stream().anyMatch(c -> dated.test(c.geldigheid()))
                || model.uniciteiten.stream().anyMatch(u -> dated.test(u.geldigheid()))
                || model.uniciteitUnies.stream().anyMatch(u -> dated.test(u.geldigheid()))
                || model.feitCreaties.stream().anyMatch(f -> dated.test(f.geldigheid()));
    }

    /** §4.2 add the validity-window guard to a construct's pattern: an {@code .expr} over the Rekendatum global
     *  matching only when the rekendatum is inside the version's inclusive {@code [vanaf, tot]} window (an unset
     *  rekendatum never matches, so the dated rule simply does not fire) — the exec-model mirror of
     *  DrlTranspiler.geldigheidGuard ({@code eval( rekendatum != null && !rekendatum.isBefore(vanaf) &&
     *  !rekendatum.isAfter(tot) )}). A no-op (returns the pattern unchanged) for an {@code altijd} version. The
     *  predicate ignores the subject — it reads only the global — so it composes with any pattern (subject, anker,
     *  Pot source). The global is constant per session, so the guard introduces no extra reactivity. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private PatternDSL.PatternDef geldigGuard(PatternDSL.PatternDef pattern, io.github.yhavinga.regelspraak.ir.Geldigheid g) {
        if (g == null || g.altijd() || rekendatumGlobal == null) return pattern;
        java.time.LocalDate vanaf = g.vanaf(), tot = g.tot();
        return pattern.expr(nextId("geldig"), (Variable) rekendatumGlobal, (Predicate2) (s, rd) -> {
            if (!(rd instanceof java.time.LocalDate d)) return false;   // unset rekendatum -> no match (no NPE)
            if (vanaf != null && d.isBefore(vanaf)) return false;
            return tot == null || !d.isAfter(tot);
        });
    }

    /** A lowered scalar value: a {@code (subject, globalValuesByName) -> value} function plus the set of
     *  Parameter-global names it reads (so {@link #regel} can bind them on the consequence). */
    private record WaardeFn(BiFunction<Object, Map<String, BigDecimal>, Object> fn, Set<String> globals) {
        static WaardeFn constant(Object v) { return new WaardeFn((s, gm) -> v, Set.of()); }
        /** A copy whose NUMERIC result is multiplied by a unit factor (null stays null — a leegwaarde; a non-number,
         *  e.g. a Tekst/enumeratiewaarde String value, passes through unscaled). */
        WaardeFn scaledBy(BigDecimal f) {
            BiFunction<Object, Map<String, BigDecimal>, Object> inner = fn;
            return new WaardeFn((s, gm) -> { Object v = inner.apply(s, gm); return v instanceof BigDecimal bd ? bd.multiply(f) : v; }, globals);
        }
    }

    /** Scalar value: Literal | own-attr read | Parameter-global read | a {@code +} of those (rsNul leegwaarde).
     *  null -> skip. The global read pulls its value from the consequence-bound map (see {@link #regel}). */
    private WaardeFn scalarWaarde(Expr e) {
        if (e instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            BigDecimal val = bd(lit);
            return val == null ? null : WaardeFn.constant(val);
        }
        if (e instanceof Expr.Literal dl && dl.type() instanceof Datatype.DatumInDagen) {
            // §13.2.19 a date literal as a VALUE (e.g. a datum-arithmetic operand) -> its LocalDate constant; DRL
            // lowers the same LocalDate.of(...).
            java.time.LocalDate d = parseDatum(dl.waarde());
            return d == null ? null : WaardeFn.constant(d);
        }
        if (e instanceof Expr.Literal dtl && dtl.type() instanceof Datatype.DatumTijd) {
            // §13.2.21 a datum-tijd literal as a VALUE -> its LocalDateTime constant; DRL lowers the same
            // LocalDateTime.of(...) (date + HH:mm:ss[.SSS], millis -> nanoOfSecond).
            java.time.LocalDateTime dt = parseDatumTijd(dtl.waarde());
            return dt == null ? null : WaardeFn.constant(dt);
        }
        // §13.3.3.4 Tekst / §13.2.14 enumeratiewaarde literal value -> its String label (an enumeratie/Tekst field is
        // a String; the §12 beslistabel may SET such an attribute). DRL renders the same string, so both back-ends
        // store the identical label; the value-side unit restatement is a no-op (scaledBy passes a non-number through).
        String tekstLabel = tekstOfEnumLabel(e);
        if (tekstLabel != null) return WaardeFn.constant(tekstLabel);
        // §5.4/§13.4.17.9 tekstreeks "de reeks van <tekst> en <waarde> …": concatenate each part's text — a literal
        // segment verbatim, an inserted expression via rsTekst (leeg -> ""). Mirrors DrlTranspiler.tekstReeks.
        if (e instanceof Expr.TekstReeks tr) {
            List<BiFunction<Object, Map<String, BigDecimal>, Object>> parts = new ArrayList<>();
            Set<String> g = new LinkedHashSet<>();
            for (Expr.TekstReeks.Deel d : tr.delen()) {
                if (d instanceof Expr.TekstReeks.Letterlijk lit) { String t = lit.tekst(); parts.add((s, gm) -> t); }
                else if (d instanceof Expr.TekstReeks.Ingevoegd ing) {
                    WaardeFn wf = scalarWaarde(ing.expr());
                    if (wf == null) return null;
                    g.addAll(wf.globals());
                    parts.add(wf.fn());
                } else return null;
            }
            return new WaardeFn((s, gm) -> {
                StringBuilder sb = new StringBuilder();
                for (BiFunction<Object, Map<String, BigDecimal>, Object> p : parts) sb.append(RegelSpraakRuntime.rsTekst(p.apply(s, gm)));
                return sb.toString();
            }, g);
        }
        if (e instanceof Expr.AttribuutRef a && a.navigatieRol() == null) {
            // §5.8 a SET operand ("de prijs van ALLE passagiers", set()==true) is an instance-AGGREGATION SOURCE, not the
            // subject's own scalar attribute — reading it as RegelSpraakRuntime.get(subject, attr) would look the
            // attribute up on the WRONG fact (the subject, which need not declare it) and THROW at fire time, aborting the
            // WHOLE session (an asymmetric, masked crash: DRL honestly markers a bare SET-in-scalar read). Skip it here so
            // the expr-comparison branches stay symmetric with the DRL marker. Mirrors tijdreeksTotaal's a.set() guard.
            if (a.set()) return null;
            // §8.2 a tijdsafhankelijk (List<Segment>) attribute read in a SCALAR slot is NOT a scalar — only the §7.1
            // "het totaal van" collapses a timeline to a scalar (handled in regel() before this path). Splicing the
            // List into a scalar setter would throw at fire time (setX(List) on a BigDecimal field); skip it instead
            // (DRL honestly markers the same read). Mirrors DrlTranspiler.isTemporeelAttribuut / lowerVal's guard.
            if (a.objecttype() != null && a.objecttype().attribuutGranulariteit.containsKey(a.attribuut())) return null;
            // §3.6 a bare read of a gedimensioneerd attribuut (its field is a Map<label,value>) is under-specified —
            // a scalar slot needs a pinned label (a DimensieCel) or a dimensie-fold. Skip (DRL markers it likewise).
            if (a.objecttype() != null && !a.objecttype().dimensiesVan(a.attribuut()).isEmpty()) return null;
            String attr = a.attribuut();
            return new WaardeFn((s, gm) -> RegelSpraakRuntime.get(s, attr), Set.of());
        }
        if (e instanceof Expr.AttribuutRef a && a.navigatieRol() != null && valueSubject != null) {
            // §6.x a to-ONE NAVIGATED value read ("de X van zijn partner"). DRL renders a PLAIN getter chain
            // ($subject.getRol().getX()) — NOT null-safe — so a null relation NPEs and aborts the session (a latent
            // DRL bug, like the §6.9 DimensieCel null-Map). navReader is null-safe (-> leeg), the CORRECT side: the
            // non-null read AGREES with DRL, the null-relation read is a model-is-better divergence (not differentially
            // testable — DRL crashes). Only reached on the VALUE path (valueSubject set); the condition path skips it.
            if (a.objecttype() != null && a.objecttype().attribuutGranulariteit.containsKey(a.attribuut())) return null;   // temporal -> Phase F
            if (a.objecttype() != null && !a.objecttype().dimensiesVan(a.attribuut()).isEmpty()) return null;             // §3.6 gedimensioneerd -> marker
            java.util.function.Function<Object, Object> reader = navReader(a, valueSubject);
            if (reader == null) return null;
            return new WaardeFn((s, gm) -> reader.apply(s), Set.of());
        }
        if (e instanceof Expr.DimensieCel c && c.attribuut() instanceof Expr.AttribuutRef ca && ca.navigatieRol() == null
                && c.labels().size() == 1) {
            // §3.6 a single labeled cell read of a gedimensioneerd attribuut: ((Map) map).get(label). A multi-label
            // (multi-dimension) or navigated cell stays a skip (DRL markers it). Mirrors DrlTranspiler.waarde(DimensieCel).
            String attr = ca.attribuut();
            String label = c.labels().get(0);
            return new WaardeFn((s, gm) -> { Object m = RegelSpraakRuntime.get(s, attr); return m == null ? null : ((Map<?, ?>) m).get(label); }, Set.of());
        }
        if (e instanceof Expr.ParameterRef pr) {
            String gn = ident(pr.parameter().naam());
            return new WaardeFn((s, gm) -> gm.get(gn), Set.of(gn));
        }
        if (e instanceof Expr.Binair b && b.op() == Expr.BinOp.TOT_DE_MACHT) {
            // §13.4.17.14 power: an INTEGER literal exponent -> exact BigDecimal.pow (rsMacht); else Math.pow
            // (rsMachtReeel). §6.7 a leeg base/operand -> leeg. No unit restatement. Mirrors DrlTranspiler.binairTerm.
            if (b.links().type().isDatum()) return null;
            WaardeFn base = scalarWaarde(b.links());
            if (base == null) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> bfn = base.fn();
            Integer n = geheelExponent(b.rechts());
            if (n != null) {
                int exp = n;
                return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsMacht(bfn.apply(s, gm), exp), base.globals());
            }
            WaardeFn rf = scalarWaarde(b.rechts());
            if (rf == null) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> rfn = rf.fn();
            Set<String> g = new LinkedHashSet<>(base.globals()); g.addAll(rf.globals());
            return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsMachtReeel(bfn.apply(s, gm), rfn.apply(s, gm)), g);
        }
        if (e instanceof Expr.Begrensd bg) {
            // §6.1.4 begrenzing (clamp): restate the bounds into the basis unit (§4, like +/-) then rsBegrens
            // (min-then-max, a leeg basis stays leeg). Mirrors DrlTranspiler's value-side clamp ternaries.
            WaardeFn baseF = scalarWaarde(bg.basis());
            if (baseF == null) return null;
            Datatype bt = bg.basis().type();
            WaardeFn minF = null, maxF = null;
            if (bg.minimum() != null) {
                minF = scalarWaarde(bg.minimum());
                if (minF == null) return null;
                BigDecimal vf = valueFactor(bg.minimum().type(), bt);
                if (vf != null) minF = minF.scaledBy(vf);
            }
            if (bg.maximum() != null) {
                maxF = scalarWaarde(bg.maximum());
                if (maxF == null) return null;
                BigDecimal vf = valueFactor(bg.maximum().type(), bt);
                if (vf != null) maxF = maxF.scaledBy(vf);
            }
            BiFunction<Object, Map<String, BigDecimal>, Object> bfn = baseF.fn();
            BiFunction<Object, Map<String, BigDecimal>, Object> mnfn = minF == null ? null : minF.fn();
            BiFunction<Object, Map<String, BigDecimal>, Object> mxfn = maxF == null ? null : maxF.fn();
            Set<String> g = new LinkedHashSet<>(baseF.globals());
            if (minF != null) g.addAll(minF.globals());
            if (maxF != null) g.addAll(maxF.globals());
            return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsBegrens(
                    (BigDecimal) bfn.apply(s, gm),
                    mnfn == null ? null : (BigDecimal) mnfn.apply(s, gm),
                    mxfn == null ? null : (BigDecimal) mxfn.apply(s, gm)), g);
        }
        if (e instanceof Expr.Binair b && arithmeticOp(b.op())) {
            // §6.2-6.5 binary arithmetic with the leegwaarde tables. Datum arithmetic (datum ± tijdsduur) lowers
            // via datumRekenWaarde (.plus/.minus, not "+"). +/- restate the right operand into the LEFT's unit (§4);
            // MAAL does not (units multiply). The op's empty-operand rule: PLUS/MIN/MAAL = leeg counts as 0;
            // VERMINDERD_MET = empty LEFT -> leeg; GEDEELD_DOOR = empty LEFT -> 0, value/empty -> leeg.
            if (b.links().type().isDatum() || b.rechts().type().isDatum()) return datumRekenWaarde(b);
            WaardeFn lf = scalarWaarde(b.links()), rf = scalarWaarde(b.rechts());
            if (lf == null || rf == null) return null;
            Expr.BinOp op = b.op();
            if (op == Expr.BinOp.PLUS || op == Expr.BinOp.MIN || op == Expr.BinOp.VERMINDERD_MET) {
                BigDecimal vf = valueFactor(b.rechts().type(), b.links().type());   // restate rhs into lhs unit
                if (vf != null) rf = rf.scaledBy(vf);
            }
            BiFunction<Object, Map<String, BigDecimal>, Object> lfn = lf.fn(), rfn = rf.fn();
            Set<String> g = new LinkedHashSet<>(lf.globals()); g.addAll(rf.globals());
            return new WaardeFn((s, gm) -> {
                Object lv = lfn.apply(s, gm), rv = rfn.apply(s, gm);
                // PLUS/MIN/MAAL mirror DRL's mvel INFIX (+/-/*), which rounds to MathContext.DECIMAL128 — NOT
                // Java's exact BigDecimal arithmetic (which diverges once the exact result exceeds 34 sig digits).
                // VERMINDERD_MET uses the EXACT rsVerminderd helper and GEDEELD_DOOR the DECIMAL64 rsGedeeld — as DRL.
                java.math.MathContext mc = java.math.MathContext.DECIMAL128;
                return switch (op) {
                    case PLUS           -> RegelSpraakRuntime.rsNul(lv).add(RegelSpraakRuntime.rsNul(rv), mc);
                    case MIN            -> RegelSpraakRuntime.rsNul(lv).subtract(RegelSpraakRuntime.rsNul(rv), mc);
                    case VERMINDERD_MET -> RegelSpraakRuntime.rsVerminderd(lv, rv);
                    case MAAL           -> RegelSpraakRuntime.rsNul(lv).multiply(RegelSpraakRuntime.rsNul(rv), mc);
                    case GEDEELD_DOOR   -> RegelSpraakRuntime.rsGedeeld(lv, rv);
                    case GEDEELD_DOOR_ABS -> RegelSpraakRuntime.rsGedeeldAbs(lv, rv);   // §6.5 (5 dec, toward zero)
                    // §6.8 Tabel 13 percentage: leeg counts as 0; mvel infix `l/100*r` (DECIMAL128). The /100 is exact.
                    case PERCENTAGE_VAN -> RegelSpraakRuntime.rsNul(lv).divide(java.math.BigDecimal.valueOf(100), mc).multiply(RegelSpraakRuntime.rsNul(rv), mc);
                    default             -> null;   // unreachable (arithmeticOp gates)
                };
            }, g);
        }
        if (e instanceof Expr.Functie fnc) {
            WaardeFn sf = scalarFunctie(fnc);   // §13.4.17 abs / date-component / datum-met / paasdag
            if (sf != null) return sf;
        }
        return scalarWrapper(e);   // §13.4.17.25 afronding / §13.4.17.13 wortel
    }

    /** §13.4.17 single-value builtin Functies as a value: §13.4.17.17 ABSOLUTE_WAARDE (rsAbs), §13.4.17.18-.24
     *  date/time component (rsDatumDeel via a ChronoField — a time part off a plain Datum markers), §13.4.17.35/.36
     *  DATUM_MET/DATUMTIJD_MET (rsDatumMet/rsDatumtijdMet), §13.4.17.37 EERSTE_PAASDAG. Each argument lowers through
     *  scalarWaarde; null for an unsupported kind/arity or an unlowerable argument. WORTEL is handled by scalarWrapper
     *  (its mandatory afronding); the aggregations/tijdsduur go through other paths. Mirrors DrlTranspiler.scalarFunctie. */
    private WaardeFn scalarFunctie(Expr.Functie f) {
        List<Expr> a = f.argumenten();
        WaardeFn fold = concatenatieFold(f);   // §4.12-4.15/§5.8.3 "de minimale/maximale/eerste/laatste van A en B …"
        if (fold != null) return fold;
        java.time.temporal.ChronoField cf = switch (f.kind()) {
            case JAAR_UIT         -> java.time.temporal.ChronoField.YEAR;
            case MAAND_UIT        -> java.time.temporal.ChronoField.MONTH_OF_YEAR;
            case DAG_UIT          -> java.time.temporal.ChronoField.DAY_OF_MONTH;
            case UUR_UIT          -> java.time.temporal.ChronoField.HOUR_OF_DAY;
            case MINUUT_UIT       -> java.time.temporal.ChronoField.MINUTE_OF_HOUR;
            case SECONDE_UIT      -> java.time.temporal.ChronoField.SECOND_OF_MINUTE;
            case MILLISECONDE_UIT -> java.time.temporal.ChronoField.MILLI_OF_SECOND;
            default               -> null;
        };
        if (cf != null) {
            if (a.size() != 1) return null;
            boolean tijdDeel = switch (f.kind()) { case UUR_UIT, MINUUT_UIT, SECONDE_UIT, MILLISECONDE_UIT -> true; default -> false; };
            if (tijdDeel && !(a.get(0).type() instanceof Datatype.DatumTijd)) return null;   // a time part off a plain Datum markers
            WaardeFn arg = scalarWaarde(a.get(0));
            if (arg == null) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> fn = arg.fn();
            return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsDatumDeel(fn.apply(s, gm), cf), arg.globals());
        }
        switch (f.kind()) {
            case ABSOLUTE_WAARDE -> {
                if (a.size() != 1) return null;
                WaardeFn arg = scalarWaarde(a.get(0));
                if (arg == null) return null;
                BiFunction<Object, Map<String, BigDecimal>, Object> fn = arg.fn();
                return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsAbs(fn.apply(s, gm)), arg.globals());
            }
            case EERSTE_PAASDAG -> {
                if (a.size() != 1) return null;
                WaardeFn arg = scalarWaarde(a.get(0));
                if (arg == null) return null;
                BiFunction<Object, Map<String, BigDecimal>, Object> fn = arg.fn();
                return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsEerstePaasdag(fn.apply(s, gm)), arg.globals());
            }
            case DATUM_MET     -> { return datumConstructor(a, 3); }
            case DATUMTIJD_MET -> { return datumConstructor(a, 7); }
            case TIJDSDUUR, ABSOLUTE_TIJDSDUUR -> {
                // §6.10 / §13.4.17.32 "de [absolute] tijdsduur van A tot B in [hele] <tijdseenheid>" — the number of
                // COMPLETE units between two dates. Mirrors DrlTranspiler.tijdsduur exactly: a DAGSOORT eenheid counts
                // the §9.8 dagsoort days in the half-open interval (van, tot] (rsTijdsduurDagsoort, day-granular ->
                // Datum operands); any OTHER unit is ChronoUnit.<U>.between via rsTijdsduur (a kwartaal = 3×MONTHS), with
                // a TIME unit (uur/.../ms) demanding datum-tijd operands. A kwartaal/unrecognised non-dagsoort unit -> skip.
                if (a.size() != 2) return null;
                boolean absoluut = f.kind() == Expr.FunctieKind.ABSOLUTE_TIJDSDUUR;
                io.github.yhavinga.regelspraak.model.Eenheid e = f.type().eenheidOf();
                String dagsoortUnit = e != null ? model.dagsoortVoorEenheid(e.afkorting()) : null;
                if (dagsoortUnit != null) {
                    if (a.get(0).type() instanceof Datatype.DatumTijd || a.get(1).type() instanceof Datatype.DatumTijd) return null;
                    java.util.function.Predicate<java.time.LocalDate> pred = dagsoortPredikaat(dagsoortUnit);
                    WaardeFn vanF = scalarWaarde(a.get(0)), totF = scalarWaarde(a.get(1));
                    if (pred == null || vanF == null || totF == null) return null;
                    BiFunction<Object, Map<String, BigDecimal>, Object> vfn = vanF.fn(), tfn = totF.fn();
                    Set<String> g = new LinkedHashSet<>(vanF.globals()); g.addAll(totF.globals());
                    return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsTijdsduurDagsoort(
                            (java.time.LocalDate) vfn.apply(s, gm), (java.time.LocalDate) tfn.apply(s, gm), pred, absoluut), g);
                }
                // general (non-dagsoort) tijdseenheid: ChronoUnit.<U>.between (kwartaal -> 3×MONTHS) via rsTijdsduur.
                String unit = chronoUnit(f.type());
                if (unit == null) return null;   // unrecognised tijdseenheid -> honest skip (symmetric with the DRL marker)
                boolean tijdEenheid = switch (unit) { case "HOURS", "MINUTES", "SECONDS", "MILLIS" -> true; default -> false; };
                if (tijdEenheid && (!(a.get(0).type() instanceof Datatype.DatumTijd) || !(a.get(1).type() instanceof Datatype.DatumTijd)))
                    return null;   // a time-unit tijdsduur needs datum-tijd operands (ChronoUnit.HOURS.between throws on a LocalDate)
                WaardeFn vanF = scalarWaarde(a.get(0)), totF = scalarWaarde(a.get(1));
                if (vanF == null || totF == null) return null;
                BiFunction<Object, Map<String, BigDecimal>, Object> vfn = vanF.fn(), tfn = totF.fn();
                Set<String> g = new LinkedHashSet<>(vanF.globals()); g.addAll(totF.globals());
                return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsTijdsduur(
                        (java.time.temporal.Temporal) vfn.apply(s, gm), (java.time.temporal.Temporal) tfn.apply(s, gm), unit, absoluut), g);
            }
            default            -> { return null; }
        }
    }

    /** §13.4.17.35/.36 lower a DATUM_MET (3 parts) / DATUMTIJD_MET (7 parts) constructor to a WaardeFn over its
     *  numeric component value-fns (rsDatumMet/rsDatumtijdMet, §6.9 any empty part -> leeg). null on arity/argument. */
    private WaardeFn datumConstructor(List<Expr> a, int n) {
        if (a.size() != n) return null;
        List<BiFunction<Object, Map<String, BigDecimal>, Object>> fns = new ArrayList<>();
        Set<String> g = new LinkedHashSet<>();
        for (Expr e : a) {
            WaardeFn p = scalarWaarde(e);
            if (p == null) return null;
            fns.add(p.fn());
            g.addAll(p.globals());
        }
        if (n == 3) return new WaardeFn((s, gm) ->
                RegelSpraakRuntime.rsDatumMet(fns.get(0).apply(s, gm), fns.get(1).apply(s, gm), fns.get(2).apply(s, gm)), g);
        return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsDatumtijdMet(
                fns.get(0).apply(s, gm), fns.get(1).apply(s, gm), fns.get(2).apply(s, gm), fns.get(3).apply(s, gm),
                fns.get(4).apply(s, gm), fns.get(5).apply(s, gm), fns.get(6).apply(s, gm)), g);
    }

    /** §4.12-4.15/§5.8.3-.4 the concatenation-of-expressies fold "de minimale/maximale/eerste/laatste van A en B
     *  [en C …]" over ≥2 LISTED scalar operands (distinct from a single-operand instance aggregation). A left fold:
     *  MINIMUM/EERSTE keep the smaller/earlier (Comparable compareTo — BigDecimal value-wise, LocalDate(Time)
     *  natural), MAXIMUM/LAATSTE the larger/later; §4.12-4.15 leegwaarde — a null operand is SKIPPED, all-null yields
     *  null. Numeric operands 1..n are restated into the first operand's unit (§Tabel 22/23, the result unit). null
     *  for <2 operands / a restricted (filter) fold / an unlowerable operand. Mirrors DrlTranspiler.concatenatieAggregatie. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private WaardeFn concatenatieFold(Expr.Functie f) {
        boolean kleinste = f.kind() == Expr.FunctieKind.MINIMUM || f.kind() == Expr.FunctieKind.EERSTE;
        boolean grootste = f.kind() == Expr.FunctieKind.MAXIMUM || f.kind() == Expr.FunctieKind.LAATSTE;
        if ((!kleinste && !grootste) || f.argumenten().size() < 2 || f.filter() != null) return null;
        Datatype doel = f.argumenten().get(0).type();   // Eenheid1: result carries the first operand's unit
        List<BiFunction<Object, Map<String, BigDecimal>, Object>> fns = new ArrayList<>();
        Set<String> g = new LinkedHashSet<>();
        for (int i = 0; i < f.argumenten().size(); i++) {
            Expr arg = f.argumenten().get(i);
            WaardeFn wf = scalarWaarde(arg);
            if (wf == null) return null;   // a set/instance-aggregation or unlowerable operand -> skip (DRL markers too)
            if (i > 0) { BigDecimal vf = valueFactor(arg.type(), doel); if (vf != null) wf = wf.scaledBy(vf); }
            fns.add(wf.fn());
            g.addAll(wf.globals());
        }
        final boolean fKlein = kleinste;
        return new WaardeFn((s, gm) -> {
            Comparable acc = null;
            for (BiFunction<Object, Map<String, BigDecimal>, Object> fn : fns) {
                Object x = fn.apply(s, gm);
                if (x == null) continue;                       // §4.12-4.15: skip a leeg operand
                if (acc == null) { acc = (Comparable) x; continue; }
                int c = acc.compareTo(x);                      // ties take x, mirroring DRL's `acc < x ? acc : x`
                acc = fKlein ? (c < 0 ? acc : (Comparable) x) : (c > 0 ? acc : (Comparable) x);
            }
            return acc;
        }, g);
    }
    private static boolean arithmeticOp(Expr.BinOp op) {
        return op == Expr.BinOp.PLUS || op == Expr.BinOp.MIN || op == Expr.BinOp.VERMINDERD_MET
                || op == Expr.BinOp.MAAL || op == Expr.BinOp.GEDEELD_DOOR
                || op == Expr.BinOp.GEDEELD_DOOR_ABS || op == Expr.BinOp.PERCENTAGE_VAN;
    }
    /** A non-negative INTEGER literal exponent for §13.4.17.14 "tot de macht" (exact BigDecimal.pow(int)), or null for
     *  a fractional/negative/runtime exponent (-> the Math.pow rsMachtReeel path). Mirrors DrlTranspiler.geheelExponent
     *  (via bd() — a fraction-literal exponent therefore falls through to scalarWaarde(rechts)). */
    private static Integer geheelExponent(Expr e) {
        if (!(e instanceof Expr.Literal lit) || !lit.type().isNumeriek()) return null;
        BigDecimal v = bd(lit);
        if (v == null || v.signum() < 0 || v.stripTrailingZeros().scale() > 0) return null;
        try { int n = v.intValueExact(); return n <= 999_999_999 ? n : null; }
        catch (ArithmeticException ignore) { return null; }
    }

    // ---------------- §5.8 aggregation inside arithmetic (Phase C follow-up) ----------------
    /** A lowered value-tree node: given the subject, the resolved global values, and the bound aggregation results
     *  (index-aligned with the accVars the lowering collected), produce the value. */
    @FunctionalInterface
    interface Eval { Object apply(Object subject, Map<String, BigDecimal> globals, Object[] aggResults); }

    private static boolean isScalarSoort(ResolvedRegel.Soort s) {
        return s == ResolvedRegel.Soort.GELIJKSTELLING || s == ResolvedRegel.Soort.BEREKENING || s == ResolvedRegel.Soort.INITIALISATIE;
    }

    /** True when the value CONTAINS an aggregation Functie somewhere in its arithmetic tree (a BARE aggregation at the
     *  root is the separate {@link #isAggregatieWaarde} path) — the trigger for the embedded-aggregation value path. */
    private static boolean containsAggregatie(Expr e) {
        if (e instanceof Expr.Functie f) {
            // a §5.8.3 LISTED MIN/MAX/EERSTE/LAATSTE fold (≥2 operands) is NOT an instance aggregation — it lowers via
            // concatenatieFold on the scalar path; only recurse into its listed operands.
            if (isGelisteFold(f)) { for (Expr a : f.argumenten()) if (containsAggregatie(a)) return true; return false; }
            if (isAggregatieKind(f.kind())) return true;
            for (Expr a : f.argumenten()) if (containsAggregatie(a)) return true;
            return false;
        }
        if (e instanceof Expr.Binair b) return containsAggregatie(b.links()) || containsAggregatie(b.rechts());
        if (e instanceof Expr.Afgerond a) return containsAggregatie(a.basis());
        if (e instanceof Expr.Begrensd g) return containsAggregatie(g.basis());
        return false;
    }

    /** Recursively lower a value tree to an {@link Eval}, collecting each embedded aggregation as its own accumulate CE
     *  (accItem + result Variable, index-aligned with accVarsOut) and the Parameter-globals it reads. null -> out of
     *  scope (caller skips). Mirrors {@link #scalarWaarde}/{@link #scalarWrapper} for the scalar leaves, but threads
     *  aggregation result vars through the {@code aggResults[]} the consequence binds. A SOM (count+fallback two-var
     *  fold) inside arithmetic is not lowered here -> null (honest skip). */
    private Eval lowerVal(Expr e, Objecttype subject, Variable sVar, Set<String> globalsOut,
                          List<RuleItemBuilder<?>> accItemsOut, List<Variable> accVarsOut) {
        if (e instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            BigDecimal val = bd(lit);
            return val == null ? null : (s, g, a) -> val;
        }
        if (e instanceof Expr.AttribuutRef ar && ar.navigatieRol() == null) {
            if (subject != null && subject.attribuutGranulariteit.containsKey(ar.attribuut())) return null;   // temporal -> Phase F
            String attr = ar.attribuut();
            return (s, g, a) -> RegelSpraakRuntime.get(s, attr);
        }
        if (e instanceof Expr.ParameterRef pr) {
            String gn = ident(pr.parameter().naam());
            globalsOut.add(gn);
            return (s, g, a) -> g.get(gn);
        }
        if (e instanceof Expr.Functie f && isAggregatieKind(f.kind())) {
            Agg agg = aggregatie(f, subject, sVar);
            if (agg == null) return null;
            if (agg.isSom()) {
                // §5.8.2 SOM inside arithmetic: ONE accumulate CE binds BOTH the contributor count and the sum;
                // the eval branches count==0 -> the leegwaarde fallback, else toBd(sum).
                accItemsOut.add(agg.accItem());
                int idxCnt = accVarsOut.size(); accVarsOut.add(agg.countVar());
                int idxSum = accVarsOut.size(); accVarsOut.add(agg.resultVar());
                WaardeFn fallback = agg.somFallback();
                return (s, g, a) -> ((Number) a[idxCnt]).longValue() == 0L
                        ? (fallback == null ? null : (BigDecimal) fallback.fn().apply(s, Map.of()))
                        : RegelSpraakRuntime.toBd(a[idxSum]);
            }
            int idx = accVarsOut.size();
            accItemsOut.add(agg.accItem());
            accVarsOut.add(agg.resultVar());
            return (s, g, a) -> RegelSpraakRuntime.toBd(a[idx]);
        }
        if (e instanceof Expr.Binair b && arithmeticOp(b.op())) {
            if (b.links().type().isDatum() || b.rechts().type().isDatum()) return null;   // datum arithmetic -> Phase F
            Eval l = lowerVal(b.links(), subject, sVar, globalsOut, accItemsOut, accVarsOut);
            Eval r = lowerVal(b.rechts(), subject, sVar, globalsOut, accItemsOut, accVarsOut);
            if (l == null || r == null) return null;
            Expr.BinOp op = b.op();
            if (op == Expr.BinOp.PLUS || op == Expr.BinOp.MIN || op == Expr.BinOp.VERMINDERD_MET) {
                BigDecimal vf = valueFactor(b.rechts().type(), b.links().type());   // restate rhs into lhs unit
                if (vf != null) { Eval inner = r; r = (s, g, a) -> { Object x = inner.apply(s, g, a); return x == null ? null : ((BigDecimal) x).multiply(vf); }; }
            }
            Eval lf = l, rf = r;
            return (s, g, a) -> {
                Object lv = lf.apply(s, g, a), rv = rf.apply(s, g, a);
                java.math.MathContext mc = java.math.MathContext.DECIMAL128;   // mirror mvel infix +/-/* (DECIMAL128)
                return switch (op) {
                    case PLUS           -> RegelSpraakRuntime.rsNul(lv).add(RegelSpraakRuntime.rsNul(rv), mc);
                    case MIN            -> RegelSpraakRuntime.rsNul(lv).subtract(RegelSpraakRuntime.rsNul(rv), mc);
                    case VERMINDERD_MET -> RegelSpraakRuntime.rsVerminderd(lv, rv);
                    case MAAL           -> RegelSpraakRuntime.rsNul(lv).multiply(RegelSpraakRuntime.rsNul(rv), mc);
                    case GEDEELD_DOOR   -> RegelSpraakRuntime.rsGedeeld(lv, rv);
                    default             -> null;
                };
            };
        }
        if (e instanceof Expr.Afgerond af) {
            Eval base = lowerVal(af.basis(), subject, sVar, globalsOut, accItemsOut, accVarsOut);
            if (base == null) return null;
            int dec = af.decimalen();
            java.math.RoundingMode mode = afrondMode(af.modus());
            return (s, g, a) -> RegelSpraakRuntime.rsAfronding(base.apply(s, g, a), dec, mode);
        }
        if (e instanceof Expr.Functie f && f.kind() == Expr.FunctieKind.WORTEL && f.argumenten().size() == 1) {
            Eval rad = lowerVal(f.argumenten().get(0), subject, sVar, globalsOut, accItemsOut, accVarsOut);
            if (rad == null) return null;
            return (s, g, a) -> RegelSpraakRuntime.rsWortel(rad.apply(s, g, a));
        }
        return null;
    }

    /** Build the consequence binding {@code v} (subject, then optional global, then the accumulate result vars) and
     *  invoking {@code body} with the matched fact array. Switches on arity (Block2..Block10); null if too many vars. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private RuleItemBuilder<?> consequenceOf(Variable[] v, java.util.function.BiConsumer<org.drools.model.Drools, Object[]> body) {
        return switch (v.length) {
            case 1 -> DSL.on(v[0]).execute((Block2<org.drools.model.Drools, Object>) (d, a0) -> body.accept(d, new Object[]{a0}));
            case 2 -> DSL.on(v[0], v[1]).execute((Block3<org.drools.model.Drools, Object, Object>) (d, a0, a1) -> body.accept(d, new Object[]{a0, a1}));
            case 3 -> DSL.on(v[0], v[1], v[2]).execute((Block4<org.drools.model.Drools, Object, Object, Object>) (d, a0, a1, a2) -> body.accept(d, new Object[]{a0, a1, a2}));
            case 4 -> DSL.on(v[0], v[1], v[2], v[3]).execute((Block5<org.drools.model.Drools, Object, Object, Object, Object>) (d, a0, a1, a2, a3) -> body.accept(d, new Object[]{a0, a1, a2, a3}));
            case 5 -> DSL.on(v[0], v[1], v[2], v[3], v[4]).execute((Block6<org.drools.model.Drools, Object, Object, Object, Object, Object>) (d, a0, a1, a2, a3, a4) -> body.accept(d, new Object[]{a0, a1, a2, a3, a4}));
            case 6 -> DSL.on(v[0], v[1], v[2], v[3], v[4], v[5]).execute((Block7<org.drools.model.Drools, Object, Object, Object, Object, Object, Object>) (d, a0, a1, a2, a3, a4, a5) -> body.accept(d, new Object[]{a0, a1, a2, a3, a4, a5}));
            case 7 -> DSL.on(v[0], v[1], v[2], v[3], v[4], v[5], v[6]).execute((Block8<org.drools.model.Drools, Object, Object, Object, Object, Object, Object, Object>) (d, a0, a1, a2, a3, a4, a5, a6) -> body.accept(d, new Object[]{a0, a1, a2, a3, a4, a5, a6}));
            case 8 -> DSL.on(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]).execute((Block9<org.drools.model.Drools, Object, Object, Object, Object, Object, Object, Object, Object>) (d, a0, a1, a2, a3, a4, a5, a6, a7) -> body.accept(d, new Object[]{a0, a1, a2, a3, a4, a5, a6, a7}));
            case 9 -> DSL.on(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]).execute((Block10<org.drools.model.Drools, Object, Object, Object, Object, Object, Object, Object, Object, Object>) (d, a0, a1, a2, a3, a4, a5, a6, a7, a8) -> body.accept(d, new Object[]{a0, a1, a2, a3, a4, a5, a6, a7, a8}));
            default -> null;
        };
    }

    /** Extends {@link #scalarWaarde} with §13.4.17.25 afronding (an Afgerond wrapper) and §13.4.17.13 wortel (a scalar
     *  WORTEL Functie, leeg/negatief -> leeg) — the wortel ALWAYS sits under its mandatory afronding. */
    private WaardeFn scalarWrapper(Expr e) {
        if (e instanceof Expr.Afgerond af) {
            WaardeFn base = scalarWaarde(af.basis());
            if (base == null) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> bfn = base.fn();
            int dec = af.decimalen();
            java.math.RoundingMode mode = afrondMode(af.modus());
            return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsAfronding(bfn.apply(s, gm), dec, mode), base.globals());
        }
        if (e instanceof Expr.Functie f && f.kind() == Expr.FunctieKind.WORTEL && f.argumenten().size() == 1) {
            WaardeFn rad = scalarWaarde(f.argumenten().get(0));
            if (rad == null) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> rfn = rad.fn();
            return new WaardeFn((s, gm) -> RegelSpraakRuntime.rsWortel(rfn.apply(s, gm)), rad.globals());
        }
        return null;
    }
    private static java.math.RoundingMode afrondMode(Expr.AfrondModus m) {
        return switch (m) {
            case NAAR_BENEDEN -> java.math.RoundingMode.FLOOR;
            case NAAR_BOVEN   -> java.math.RoundingMode.CEILING;
            case REKENKUNDIG  -> java.math.RoundingMode.HALF_UP;
            case RICHTING_NUL -> java.math.RoundingMode.DOWN;
            case WEG_VAN_NUL  -> java.math.RoundingMode.UP;
        };
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
    /** A numeric EXPRESSION operand (an arithmetic {@link Expr.Binair}, a builtin {@link Expr.Functie}, a §6.1.4
     *  begrenzing or a §13.4.17.25 afronding) — i.e. NOT a plain attribute/literal/parameter leaf. Used to route an
     *  expr-vs-attr / expr-vs-expr comparison through the {@link #scalarWaarde} value-path lowering on both sides. */
    private static boolean numeriekeExpressie(Expr e) {
        return e instanceof Expr.Binair || e instanceof Expr.Functie || e instanceof Expr.Begrensd || e instanceof Expr.Afgerond;
    }
    /** Whether an expression operand contains a construct the DRL CONDITION path MARKERS (so the exec must also skip, to
     *  stay symmetric) or that restates LOSSILY: a §3.6 DimensieCel (DrlTranspiler.operand markers a dimensioned read in a
     *  constraint — review F1), or a CROSS-UNIT +/- (DrlTranspiler.omrekenConditie markers it; the right operand restated
     *  into the left's unit truncates a non-terminating ratio mnd→jr = 1/12, a wrong firing — review F2). MAAL/GEDEELD do
     *  NOT restate units (§6.4), so they are unit-safe. Recurses through arithmetic / begrenzing / afronding / functie args. */
    private static boolean parityOnveilig(Expr e) {
        if (e instanceof Expr.DimensieCel) return true;
        if (e instanceof Expr.Binair b) {
            if (b.op() == Expr.BinOp.PLUS || b.op() == Expr.BinOp.MIN || b.op() == Expr.BinOp.VERMINDERD_MET) {
                Eenheid el = b.links().type() == null ? null : b.links().type().eenheidOf();
                Eenheid er = b.rechts().type() == null ? null : b.rechts().type().eenheidOf();
                if (el != null && er != null && el.vereistOmrekening(er)) return true;   // cross-unit +/- -> lossy / DRL markers
            }
            return parityOnveilig(b.links()) || parityOnveilig(b.rechts());
        }
        if (e instanceof Expr.Begrensd bg) return parityOnveilig(bg.basis())
                || (bg.minimum() != null && parityOnveilig(bg.minimum())) || (bg.maximum() != null && parityOnveilig(bg.maximum()));
        if (e instanceof Expr.Afgerond af) return parityOnveilig(af.basis());
        if (e instanceof Expr.Functie f) return f.argumenten().stream().anyMatch(ExecModelTranspiler::parityOnveilig);
        return false;
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
    /** §5.3/§5.6 DATUM equality with the leegwaarde tables — GELIJK is §5.3 Tabel 38 (Anders): a leeg operand makes it
     *  onwaar (both-leeg is "fout", which as a rule GUARD is not-satisfied -> onwaar; NOT Tabel 37's numeric both-leeg=WAAR);
     *  ONGELIJK is §5.6 Tabel 44: exactly-one-leeg -> WAAR, both-leeg -> onwaar. Mirrors the DRL's non-numeriek schending
     *  complement. (LocalDate equality is .equals; a leeg date is null.) */
    private static boolean datumGelijk(java.time.LocalDate a, java.time.LocalDate b, Conditie.Operator op) {
        boolean aNull = a == null, bNull = b == null;
        return switch (op) {
            case GELIJK   -> !aNull && !bNull && a.equals(b);                       // one/both-leeg -> onwaar (Tabel 38)
            case ONGELIJK -> aNull || bNull ? !(aNull && bNull) : !a.equals(b);     // one-leeg -> WAAR, both-leeg -> onwaar (Tabel 44)
            default       -> false;
        };
    }
    /** A reader for an OWN or to-ONE NAVIGATED datum attribute as a {@link java.time.LocalDate} (null = leegwaarde): an
     *  absent navigation reads leeg (§5.5.5), a present relation reads the attribute (which may itself be leeg). null when
     *  the attribute is temporal (Phase F), §3.6 gedimensioneerd, not the subject's own member, or an unresolvable /
     *  to-many navigation. Mirrors {@link #navReader}'s null-safety for the datum-equality leaf. */
    private java.util.function.Function<Object, java.time.LocalDate> datumLezer(Expr.AttribuutRef a, Objecttype subject) {
        if (a.objecttype() == null || a.objecttype().attribuutGranulariteit.containsKey(a.attribuut())) return null;   // temporal -> Phase F
        if (!a.objecttype().dimensiesVan(a.attribuut()).isEmpty()) return null;                                        // §3.6 -> marker
        String attr = a.attribuut();
        if (a.navigatieRol() == null) {
            if (subject == null || !subject.heeftLid(attr)) return null;
            return s -> RegelSpraakRuntime.get(s, attr) instanceof java.time.LocalDate ld ? ld : null;
        }
        String rol = navComparisonRole(a, subject);
        if (rol == null) return null;
        return s -> { Object rel = RegelSpraakRuntime.get(s, rol);
            return rel != null && RegelSpraakRuntime.get(rel, attr) instanceof java.time.LocalDate ld ? ld : null; };
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
    /** §5.1 datum-ordening: compare two present dates with the EERDER/LATER operator (LocalDate is Comparable, so DRL
     *  mvel {@code <}/{@code <=}/{@code >}/{@code >=} use compareTo). The caller guards both operands present — an
     *  empty datum operand is the ordering's no-match (false), handled at the call site. */
    private static boolean datumVergelijk(java.time.LocalDate a, java.time.LocalDate b, Conditie.Operator op) {
        return switch (op) {
            case EERDER           -> a.isBefore(b);
            case EERDER_OF_GELIJK -> !a.isAfter(b);
            case LATER            -> a.isAfter(b);
            case LATER_OF_GELIJK  -> !a.isBefore(b);
            default               -> false;   // never reached (isDatumOp gates the caller)
        };
    }
    /** §8.1.1 datum comparison over ANY Temporal (LocalDate or LocalDateTime), for a date-arithmetic EXPRESSION
     *  operand whose runtime type the caller does not pin. Mirrors DRL's mvel rendering exactly: ORDERING
     *  (EERDER/LATER) compares two present operands via {@link Comparable#compareTo}, a leeg operand is the no-match
     *  (false); GELIJK is {@code a==b && !(both null)} (two leeg dates are NOT equal); ONGELIJK is {@code a!=b ||
     *  both null} (mvel's null-asymmetric {@code !=}). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean datumVergelijkTemporal(Object a, Object b, Conditie.Operator op) {
        switch (op) {
            case GELIJK:   return java.util.Objects.equals(a, b) && !(a == null && b == null);
            case ONGELIJK: return !java.util.Objects.equals(a, b) || (a == null && b == null);
            default: break;
        }
        if (!(a instanceof Comparable) || b == null) return false;   // ordering: a leeg operand -> no-match (mvel)
        int c = ((Comparable) a).compareTo(b);
        return switch (op) {
            case EERDER           -> c < 0;
            case EERDER_OF_GELIJK -> c <= 0;
            case LATER            -> c > 0;
            case LATER_OF_GELIJK  -> c >= 0;
            default               -> false;
        };
    }
    /** The datum comparison ops the {@link #datumVergelijkTemporal} date-expression branch covers: the EERDER/LATER
     *  ordering family plus GELIJK/ONGELIJK (date equality). */
    private static boolean isDatumVergelijkOp(Conditie.Operator op) {
        return isDatumOp(op) || op == Conditie.Operator.GELIJK || op == Conditie.Operator.ONGELIJK;
    }
    /** True for a §6/§13.4.16 date-arithmetic Binair (a +/- whose result is a datum because one operand is a datum):
     *  the operand form the leafPredicate datum branch handles via scalarWaarde rather than a bare attribute/literal. */
    private static boolean isDatumRekenExpr(Expr e) {
        return e instanceof Expr.Binair b && (b.links().type().isDatum() || b.rechts().type().isDatum());
    }

    // ---------------- §9.5 / §13.4.8 consistentie (Phase D) ----------------
    /** §13.4.8 consistentieregel: the assertie MUST hold, so the rule fires on its VIOLATION (the negated
     *  assertie) and INSERTS an Inconsistentie(naam, subject). Insert-only -> NO no-loop; an idempotency guard
     *  (not Inconsistentie(regel==naam, subject==$s)) keeps it from re-flagging an already-flagged subject.
     *  Mirrors DrlTranspiler.consistentie + schendingConstraint. null -> skip (no faithful violation predicate). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule consistentie(io.github.yhavinga.regelspraak.ir.ResolvedConsistentie c) {
        Objecttype subject = c.subject();
        if (subject == null) return skip("consistentie (geen subject): " + c.naam());
        Class<?> factClass = factClass(subject);
        if (factClass == null) return skip("consistentie (geen fact-class " + subject.naam + "): " + c.naam());
        Class<?> incClass = inconsistentieClass();
        if (incClass == null) return skip("consistentie (geen Inconsistentie-fact): " + c.naam());
        seq = 0;
        String naam = c.naam();
        String fqcn = FACTS + ".Inconsistentie";
        Variable sVar = DSL.declarationOf(factClass, "$subject");
        PatternDSL.PatternDef pattern;
        Predicate1<Object> schending = schending(c.assertie(), subject);
        if (schending != null) {
            pattern = PatternDSL.pattern(sVar).expr(nextId("schending"), (Predicate1) schending);
        } else {
            // §9.5 a vs-PARAMETER assertie ("X moet kleiner zijn dan de <parameter>") needs the global bound as a
            // pattern variable (a 2-var expr) — schending returns a fact-only Predicate1, so it cannot carry it.
            SchendingGlobal sg = schendingMetGlobal(c.assertie(), subject);
            if (sg == null) return skip("consistentie (schending buiten Phase-D scope): " + c.naam());
            Global<BigDecimal> g = globals.get(sg.global());
            java.util.function.BiPredicate<Object, BigDecimal> pred = sg.pred();
            pattern = PatternDSL.pattern(sVar).expr(nextId("schending"), (Variable) g,
                    (Predicate2) (s, gv) -> pred.test(s, (BigDecimal) gv));
        }
        pattern = geldigGuard(pattern, c.geldigheid());   // §4.2 window
        // idempotency guard: not Inconsistentie( regel == naam, subject == $subject )
        Variable incVar = DSL.declarationOf(incClass, "$inc_" + (seq++));
        PatternDSL.PatternDef guard = PatternDSL.pattern(incVar)
                .expr(nextId("ig"), (Predicate1) i -> naam.equals(RegelSpraakRuntime.get(i, "regel")))
                .expr(nextId("ig"), (Variable) sVar, (Predicate2) (i, s) -> RegelSpraakRuntime.get(i, "subject") == s);
        Block2<org.drools.model.Drools, Object> body = (drools, s) ->
                drools.insert(RegelSpraakRuntime.newInconsistentie(fqcn, naam, s));
        return PatternDSL.rule(naam).build(pattern, DSL.not(guard), DSL.on(sVar).execute(body));
    }

    /** The §9.5 VIOLATION predicate of an assertie: the negation that fires the Inconsistentie. Phase D scope = a
     *  single own-attribute numeric comparison (vs another own attribute or a numeric literal), unit-restated:
     *  equality (gelijk/ongelijk) flips the operator (vergelijk's mvel null semantics make an empty operand a
     *  violation of "gelijk aan", per typeringen Tabel 37); an ordering comparison is ALSO violated when either
     *  operand is leeg (the typeringen leegwaarde tables make it onwaar -> inconsistent). null -> skip. */
    private Predicate1<Object> schending(Conditie assertie, Objecttype subject) {
        // §13.4.8: "alle criteria moeten gelden" -> inconsistent when ANY criterion is violated (OR the schendingen).
        if (assertie instanceof Conditie.Samengesteld s && s.kwantor() == Conditie.Kwantor.ALLE) {
            List<Predicate1<Object>> schendingen = new ArrayList<>();
            for (Conditie deel : s.onderdelen()) {
                Predicate1<Object> sp = schending(deel, subject);
                if (sp == null) return null;                                // an un-negatable criterion no-ops the rule
                schendingen.add(sp);
            }
            if (schendingen.isEmpty()) return null;
            return obj -> { for (Predicate1<Object> sp : schendingen) if (sp.test(obj)) return true; return false; };
        }
        // §8.2 a whole-period-qualified criterium ("X moet gedurende het gehele jaar gelijk zijn aan Y"): the criterium
        // is satisfied iff the §8.2 check holds, so the VIOLATION is its negation. A literal/own-attr grens (0 globals)
        // is fact-only; a Parameter-global grens is handled by schendingMetGlobal. Symmetric with DrlTranspiler.
        Conditie.GeheelPeriode gp82 = geheelPeriodeVan(assertie);
        if (gp82 != null) {
            Predicate1<Object> hold = geheelPeriodeLeaf(geheelPeriode82(assertie, gp82, subject));
            return hold == null ? null : s -> !hold.test(s);
        }
        // §9.5 a LEEG/GEVULD assertie criterion ("X moet gevuld/leeg zijn", also as a nested ALLE criterion): the
        // violation is its negation — a GEVULD assertie is violated when the operand is leeg, a LEEG one when it is
        // gevuld. Own or to-one navigated operand (consistentieReader; navReader's null == leeg). Mirrors negeer(LEEG/
        // GEVULD) + DRL's genavigeerdLeegGevuld (absent reference reads leeg).
        if (assertie instanceof Conditie.Predicaat p
                && (p.soort() == Conditie.Predicaatsoort.LEEG || p.soort() == Conditie.Predicaatsoort.GEVULD)
                && p.operand() instanceof Expr.AttribuutRef pa) {
            java.util.function.Function<Object, Object> read = consistentieReader(pa, subject);
            if (read == null) return null;
            boolean assertGevuld = p.soort() == Conditie.Predicaatsoort.GEVULD;
            return s -> (read.apply(s) == null) == assertGevuld;   // leeg violates GEVULD; gevuld violates LEEG
        }
        if (!(assertie instanceof Conditie.Vergelijking v)) return null;     // other compound: later
        // §13.4.8 a DATUM comparison assertie where an operand is a §6/§13.4.16 date-arithmetic EXPRESSION ("X moet
        // later zijn dan Y plus Z"): the violation is the negation of the assertion. datumVergelijkTemporal already
        // encodes mvel's leeg/ordering/equality semantics, so !it IS the schending (either operand leeg, or the
        // flipped comparison) — matching DRL's negated constraint. (Checked before the bare-AttribuutRef-LHS forms.)
        if (isDatumVergelijkOp(v.op()) && v.links().type().isDatum() && v.rechts().type().isDatum()
                && (isDatumRekenExpr(v.links()) || isDatumRekenExpr(v.rechts()))) {
            WaardeFn lw = scalarWaarde(v.links()), rw = scalarWaarde(v.rechts());
            if (lw == null || rw == null || !lw.globals().isEmpty() || !rw.globals().isEmpty()) return null;
            BiFunction<Object, Map<String, BigDecimal>, Object> lfn = lw.fn(), rfn = rw.fn();
            Conditie.Operator op = v.op();
            return s -> !datumVergelijkTemporal(lfn.apply(s, Map.of()), rfn.apply(s, Map.of()), op);
        }
        if (!(v.links() instanceof Expr.AttribuutRef la)) return null;
        // OWN or to-ONE NAVIGATED operand readers (navReader's null == DRL's `l.leeg` = rol==null || attr==null, so
        // the explicit leeg/vol semantics of DrlTranspiler.genavigeerdeVergelijkingSchending fall out of a null check).
        java.util.function.Function<Object, Object> lRead = consistentieReader(la, subject);
        if (lRead == null) return null;

        // §13.4.8 DATUM ordering assertie ("X moet eerder/later zijn dan Y"): violated when either operand is empty
        // OR the flipped (negated) ordering holds (own or navigated operands).
        if (la.type().isDatum() && isDatumOp(v.op())) {
            Conditie.Operator flipOp = flip(v.op());
            if (v.rechts() instanceof Expr.Literal dl && dl.type().isDatum()) {
                java.time.LocalDate rhs = parseDatum(dl.waarde());
                if (rhs == null) return null;
                return s -> { Object a = lRead.apply(s);
                    return !(a instanceof java.time.LocalDate ld) || datumVergelijk(ld, rhs, flipOp); };
            }
            if (v.rechts() instanceof Expr.AttribuutRef ra && ra.type().isDatum()) {
                java.util.function.Function<Object, Object> rRead = consistentieReader(ra, subject);
                if (rRead == null) return null;
                return s -> { Object a = lRead.apply(s), b = rRead.apply(s);
                    return !(a instanceof java.time.LocalDate la2) || !(b instanceof java.time.LocalDate lb2)
                            || datumVergelijk(la2, lb2, flipOp); };
            }
            return null;
        }
        // §13.4.8 / typeringen Tabel 38 a NON-numeriek (Tekst / §13.2.14 enumeratiewaarde) equality assertie vs a
        // literal ("X moet gelijk/ongelijk zijn aan 'lit'"): the criterion is satisfied when (attr equals the label)
        // == (op is GELIJK); a violation otherwise. A leeg operand never equals the non-null literal, so a GELIJK
        // assertie over a leeg operand IS a violation (Tabel 38). Own or navigated LHS.
        if ((v.op() == Conditie.Operator.GELIJK || v.op() == Conditie.Operator.ONGELIJK)
                && v.rechts() instanceof Expr.Literal tl && tekstOfEnumLabel(tl) != null) {
            String rhs = tekstOfEnumLabel(tl);
            boolean assertGelijk = v.op() == Conditie.Operator.GELIJK;
            return s -> rhs.equals(lRead.apply(s)) != assertGelijk;   // violation = criterion not satisfied
        }

        if (!isNumeriekOp(v.op())) return null;                              // remaining non-numeric op (EERDER/LATER datum): later
        // §5.3 a GELIJK/ONGELIJK assertie over NON-numeric operands (Tekst/datum/enum/boolean — attr-vs-attr or navigated-
        // vs-attr) reaching the toBd() coercion below would call RegelSpraakRuntime.toBd on a non-number and CRASH the whole
        // session at fire time (a leeg/String value -> IllegalStateException -> ConstraintEvaluationException). Skip it ->
        // an HONEST exec TODO (overgeslagen), never a crash (DRL flags it via the non-numeric schending form, so it is a
        // masked DRL-flags/exec-skips, not a silent divergence). The Tekst/enum vs-LITERAL consistentie is handled above;
        // this catches the attr-vs-attr / navigated-vs-attr Tekst/datum-equality form.
        if (la.type() != null && !la.type().isNumeriek() && !la.type().isPercentage()) return null;
        Conditie.Operator flip = flip(v.op());
        boolean gelijkheid = v.op() == Conditie.Operator.GELIJK || v.op() == Conditie.Operator.ONGELIJK;
        if (v.rechts() instanceof Expr.AttribuutRef ra) {
            java.util.function.Function<Object, Object> rRead = consistentieReader(ra, subject);
            if (rRead == null) return null;
            BigDecimal[] f = restatement(la.type(), ra.type());
            return s -> {
                BigDecimal a = scale(RegelSpraakRuntime.toBd(lRead.apply(s)), f[0]), b = scale(RegelSpraakRuntime.toBd(rRead.apply(s)), f[1]);
                return gelijkheid ? vergelijk(a, b, flip) : (a == null || b == null || vergelijk(a, b, flip));
            };
        }
        if (v.rechts() instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            BigDecimal rhs = bd(lit);
            if (rhs == null) return null;
            BigDecimal[] f = restatement(la.type(), lit.type());
            BigDecimal rhsS = scale(rhs, f[1]);
            return s -> {
                BigDecimal a = scale(RegelSpraakRuntime.toBd(lRead.apply(s)), f[0]);
                return gelijkheid ? vergelijk(a, rhsS, flip) : (a == null || rhsS == null || vergelijk(a, rhsS, flip));
            };
        }
        return null;
    }

    /** A §9.5 consistentie violation predicate that reads a Parameter-GLOBAL: the global name + a {@code (subject,
     *  globalValue) -> violated} test. */
    private record SchendingGlobal(String global, java.util.function.BiPredicate<Object, BigDecimal> pred) {}

    /** §9.5 the violation predicate of a single numeric comparison assertie whose RHS is a scalar Parameter ("X moet
     *  kleiner zijn dan de volwassenleeftijd") — the global is bound as a pattern variable by the caller. Own or
     *  navigated LHS (consistentieReader); null when not this shape. The leeg/violation semantics match the own-attr
     *  numeric schending (an ordering with an empty operand is a violation). */
    private SchendingGlobal schendingMetGlobal(Conditie assertie, Objecttype subject) {
        // §8.2 a whole-period-qualified criterium whose grens reads EXACTLY ONE Parameter-global ("X moet gedurende
        // het gehele jaar gelijk zijn aan <de parameter>"): the global binds as a pattern variable, the violation is
        // !(§8.2 check holds). Mirrors DrlTranspiler.schendingConstraint lowering the global grens via operand().
        Conditie.GeheelPeriode gp82 = geheelPeriodeVan(assertie);
        if (gp82 != null) {
            Gp82 g = geheelPeriode82(assertie, gp82, subject);
            if (g == null || g.grens() == null || g.grens().globals().size() != 1) return null;
            String gn = g.grens().globals().iterator().next();
            java.util.function.Function<Object, Object> tl = g.timeline(); WaardeFn gf = g.grens();
            int toets = g.toets(); String gran = g.gran(); boolean niet = g.niet();
            return new SchendingGlobal(gn, (s, gv) -> {
                BigDecimal grens = RegelSpraakRuntime.toBd(gf.fn().apply(s, bindGlobal(gn, gv)));
                boolean b = RegelSpraakRuntime.rsGeheelPeriode((List<?>) tl.apply(s), gran, toets, grens);
                return !(niet ? !b : b);
            });
        }
        if (!(assertie instanceof Conditie.Vergelijking v)) return null;
        // §13.4.8 a DATUM comparison assertie where an operand is a §6/§13.4.16 date-arithmetic expression reading
        // EXACTLY ONE Parameter-GLOBAL (the duration is a parameter): the violation is !datumVergelijkTemporal; the
        // global is bound as a pattern variable by the caller. Mirrors DRL's negated rsDatumReken constraint.
        if (isDatumVergelijkOp(v.op()) && v.links().type().isDatum() && v.rechts().type().isDatum()
                && (isDatumRekenExpr(v.links()) || isDatumRekenExpr(v.rechts()))) {
            WaardeFn lw = scalarWaarde(v.links()), rw = scalarWaarde(v.rechts());
            if (lw != null && rw != null) {
                Set<String> gset = new LinkedHashSet<>(lw.globals()); gset.addAll(rw.globals());
                if (gset.size() == 1) {
                    String gn = gset.iterator().next();
                    if (globals.containsKey(gn)) {
                        BiFunction<Object, Map<String, BigDecimal>, Object> lfn = lw.fn(), rfn = rw.fn();
                        Conditie.Operator op = v.op();
                        return new SchendingGlobal(gn, (s, gv) ->
                                !datumVergelijkTemporal(lfn.apply(s, bindGlobal(gn, gv)), rfn.apply(s, bindGlobal(gn, gv)), op));
                    }
                }
            }
            return null;   // 0 globals -> handled by schending; >1 global -> skip
        }
        if (!(v.links() instanceof Expr.AttribuutRef la) || !isNumeriekOp(v.op())) return null;
        if (!(v.rechts() instanceof Expr.ParameterRef pr) || pr.parameter().granulariteit() != null) return null;
        java.util.function.Function<Object, Object> lRead = consistentieReader(la, subject);
        if (lRead == null) return null;
        String gn = ident(pr.parameter().naam());
        if (!globals.containsKey(gn)) return null;
        Conditie.Operator flip = flip(v.op());
        boolean gelijkheid = v.op() == Conditie.Operator.GELIJK || v.op() == Conditie.Operator.ONGELIJK;
        BigDecimal[] f = restatement(la.type(), pr.parameter().datatype());
        return new SchendingGlobal(gn, (s, gv) -> {
            BigDecimal a = scale(RegelSpraakRuntime.toBd(lRead.apply(s)), f[0]), b = scale(gv, f[1]);
            return gelijkheid ? vergelijk(a, b, flip) : (a == null || b == null || vergelijk(a, b, flip));
        });
    }

    /** A §13.4.8 consistentie comparison-operand reader: the subject's OWN attribute (get) or a to-ONE NAVIGATED read
     *  (navReader — its null IS the operand's leeg, since DRL's {@code l.leeg} = {@code rol == null || rol.attr ==
     *  null}, exactly what navReader collapses). null for a temporal / dimensioned / unresolvable / to-many operand. */
    private java.util.function.Function<Object, Object> consistentieReader(Expr.AttribuutRef a, Objecttype subject) {
        if (a.objecttype() == null) return null;
        if (a.objecttype().attribuutGranulariteit.containsKey(a.attribuut())) return null;   // temporal -> Phase F
        if (!a.objecttype().dimensiesVan(a.attribuut()).isEmpty()) return null;               // §3.6 gedimensioneerd -> later
        if (a.navigatieRol() == null) {
            if (subject == null || !subject.heeftLid(a.attribuut())) return null;
            String attr = a.attribuut();
            return s -> RegelSpraakRuntime.get(s, attr);
        }
        return navReader(a, subject);   // to-one navigated; null == leeg
    }

    /** §8.1.6 uniciteitscontrole: "is uniek" is violated when ≥2 DISTINCT instances share a (non-null) key. The
     *  subject binds each non-null key part (+ a parent scope field), an accumulate COUNTS the instances whose key
     *  matches, and the rule fires (inserts an Inconsistentie) when that count > 1 — flagging every instance in the
     *  duplicate group. A null key cannot establish a duplicate (§8.1.6) so it is filtered out. Mirrors
     *  DrlTranspiler.uniciteit. null -> skip (no fact class / a temporal key). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule uniciteit(io.github.yhavinga.regelspraak.ir.ResolvedUniciteit u) {
        Objecttype subject = u.subject();
        if (subject == null || u.sleutels().isEmpty()) return skip("uniciteit (geen subject/sleutel): " + u.naam());
        Class<?> factClass = factClass(subject);
        if (factClass == null) return skip("uniciteit (geen fact-class " + subject.naam + "): " + u.naam());
        Class<?> incClass = inconsistentieClass();
        if (incClass == null) return skip("uniciteit (geen Inconsistentie-fact): " + u.naam());
        // A temporal (List<Segment>) key is NOT special-cased: gelijkWaarde -> List.equals matches DRL's mvel
        // `timeline == $k` (only instance-equal timelines collide, so it is degenerate but parity-faithful).

        seq = 0;
        String naam = u.naam();
        String fqcn = FACTS + ".Inconsistentie";
        Variable sVar = DSL.declarationOf(factClass, "$subject");
        PatternDSL.PatternDef subjPat = geldigGuard(PatternDSL.pattern(sVar), u.geldigheid());   // §4.2 window
        // bind + non-null each key part (and the parent scope field, if any).
        List<Variable> keyVars = new ArrayList<>();
        for (String key : u.sleutels()) {
            Variable kVar = DSL.declarationOf(Object.class, "$k_" + (seq++));
            subjPat = subjPat.bind(kVar, (Function1) s -> RegelSpraakRuntime.get(s, key))
                    .expr(nextId("knn"), (Predicate1) s -> RegelSpraakRuntime.get(s, key) != null);
            keyVars.add(kVar);
        }
        Variable scopeVar = null;
        if (u.scopeVeld() != null) {
            String sv = u.scopeVeld();
            scopeVar = DSL.declarationOf(Object.class, "$scope_" + (seq++));
            subjPat = subjPat.bind(scopeVar, (Function1) s -> RegelSpraakRuntime.get(s, sv))
                    .expr(nextId("snn"), (Predicate1) s -> RegelSpraakRuntime.get(s, sv) != null);
        }
        // accumulate source: every instance whose key (and scope) matches the subject's; count them.
        Variable oVar = DSL.declarationOf(factClass, "$o_" + (seq++));
        PatternDSL.PatternDef src = PatternDSL.pattern(oVar);
        for (int i = 0; i < u.sleutels().size(); i++) {
            String key = u.sleutels().get(i);
            src = src.expr(nextId("kj"), (Variable) keyVars.get(i),
                    (Predicate2) (o, k) -> RegelSpraakRuntime.gelijkWaarde(RegelSpraakRuntime.get(o, key), k));
        }
        if (scopeVar != null) {
            String sv = u.scopeVeld();
            src = src.expr(nextId("sj"), (Variable) scopeVar,
                    (Predicate2) (o, sc) -> RegelSpraakRuntime.gelijkWaarde(RegelSpraakRuntime.get(o, sv), sc));
        }
        Variable cntVar = DSL.declarationOf(Object.class, "$cnt_" + (seq++));
        RuleItemBuilder<?> accItem = DSL.accumulate(src, DSL.accFunction(accFn("CountAccumulateFunction")).as(cntVar));
        PatternDSL.PatternDef cntPat = PatternDSL.pattern(cntVar).expr(nextId("gt1"), (Predicate1) c -> ((Number) c).intValue() > 1);
        // idempotency guard + insert (insert-only -> NO no-loop).
        Variable incVar = DSL.declarationOf(incClass, "$inc_" + (seq++));
        PatternDSL.PatternDef guard = PatternDSL.pattern(incVar)
                .expr(nextId("ig"), (Predicate1) i -> naam.equals(RegelSpraakRuntime.get(i, "regel")))
                .expr(nextId("ig"), (Variable) sVar, (Predicate2) (i, s) -> RegelSpraakRuntime.get(i, "subject") == s);
        Block2<org.drools.model.Drools, Object> body = (drools, s) ->
                drools.insert(RegelSpraakRuntime.newInconsistentie(fqcn, naam, s));
        return PatternDSL.rule(naam).build(subjPat, accItem, cntPat, DSL.not(guard), DSL.on(sVar).execute(body));
    }

    /** §8.1.6 "verenigd met" cross-type union uniqueness ("de namen van alle Cirkels verenigd met de namen van alle
     *  Vierkanten moeten uniek zijn"): the key (or key tuple) must be unique across the UNION of every source's key
     *  space. One rule per source — a subject of that source binds its non-null key parts, an accumulate per source
     *  counts the instances (of that source) whose key matches, and the rule flags the subject when the SUM of the
     *  counts over all sources exceeds 1. The Inconsistentie carries the SHARED union name (so the flagged-set spans
     *  the sources); the rule NAME is disambiguated by the 1-based source index (rule names must be unique). The sum
     *  is gated in the consequence (insert iff >1) — same observable as DRL's {@code eval(sum>1)} when-gate, but
     *  general over any source count. Mirrors DrlTranspiler.uniciteitUnie. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Rule> uniciteitUnie(io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie u) {
        var bronnen = u.bronnen();
        if (bronnen.isEmpty()) return skipList("uniciteit-unie (geen bronnen): " + u.naam());
        int arity = bronnen.get(0).sleutels().size();
        Class<?> incClass = inconsistentieClass();
        if (incClass == null) return skipList("uniciteit-unie (geen Inconsistentie-fact): " + u.naam());
        for (var b : bronnen) {
            if (b.sleutels().size() != arity) return skipList("uniciteit-unie (sleutel-ariteit verschilt): " + u.naam());
            if (factClass(b.type()) == null) return skipList("uniciteit-unie (geen fact-class " + b.type().naam + "): " + u.naam());
            for (String k : b.sleutels())
                if (b.type().attribuutGranulariteit.containsKey(k)) return skipList("uniciteit-unie (temporele sleutel): " + u.naam());
        }
        String naam = u.naam();
        String fqcn = FACTS + ".Inconsistentie";
        List<Rule> rules = new ArrayList<>();
        for (int s = 0; s < bronnen.size(); s++) {
            var subj = bronnen.get(s);
            Class<?> subjClass = factClass(subj.type());
            seq = 0;
            Variable sVar = DSL.declarationOf(subjClass, "$subject");
            PatternDSL.PatternDef subjPat = geldigGuard(PatternDSL.pattern(sVar), u.geldigheid());   // §4.2 window
            List<Variable> keyVars = new ArrayList<>();
            for (int i = 0; i < arity; i++) {
                String key = subj.sleutels().get(i);
                Variable kVar = DSL.declarationOf(Object.class, "$k_" + (seq++));
                subjPat = subjPat.bind(kVar, (Function1) o -> RegelSpraakRuntime.get(o, key))
                        .expr(nextId("uknn"), (Predicate1) o -> RegelSpraakRuntime.get(o, key) != null);   // null key cannot collide
                keyVars.add(kVar);
            }
            List<RuleItemBuilder<?>> items = new ArrayList<>();
            items.add(subjPat);
            List<Variable> countVars = new ArrayList<>();
            for (int j = 0; j < bronnen.size(); j++) {                 // one count per source, matched by key value
                var b = bronnen.get(j);
                Variable oVar = DSL.declarationOf(factClass(b.type()), "$o" + j + "_" + (seq++));
                PatternDSL.PatternDef src = PatternDSL.pattern(oVar);
                for (int i = 0; i < arity; i++) {
                    String key = b.sleutels().get(i);
                    src = src.expr(nextId("ukj"), (Variable) keyVars.get(i),
                            (Predicate2) (e, k) -> RegelSpraakRuntime.gelijkWaarde(RegelSpraakRuntime.get(e, key), k));
                }
                Variable cntVar = DSL.declarationOf(Object.class, "$c" + j + "_" + (seq++));
                items.add(DSL.accumulate(src, DSL.accFunction(accFn("CountAccumulateFunction")).as(cntVar)));
                countVars.add(cntVar);
            }
            // idempotency guard: not Inconsistentie( regel == naam, subject == $subject )
            Variable incVar = DSL.declarationOf(incClass, "$inc_" + (seq++));
            PatternDSL.PatternDef guard = PatternDSL.pattern(incVar)
                    .expr(nextId("uig"), (Predicate1) i -> naam.equals(RegelSpraakRuntime.get(i, "regel")))
                    .expr(nextId("uig"), (Variable) sVar, (Predicate2) (i, ss) -> RegelSpraakRuntime.get(i, "subject") == ss);
            items.add(DSL.not(guard));
            // consequence: sum the per-source counts; insert iff > 1 (insert-only -> NO no-loop).
            List<Variable> vars = new ArrayList<>();
            vars.add(sVar);
            vars.addAll(countVars);
            final int nC = countVars.size();
            RuleItemBuilder<?> consequence = consequenceOf(vars.toArray(new Variable[0]), (drools, args) -> {
                int sum = 0;
                for (int c = 0; c < nC; c++) sum += ((Number) args[1 + c]).intValue();
                if (sum > 1) drools.insert(RegelSpraakRuntime.newInconsistentie(fqcn, naam, args[0]));
            });
            if (consequence == null) { overgeslagen.add("uniciteit-unie (te veel bronnen): " + u.naam()); return rules; }
            items.add(consequence);
            rules.add(PatternDSL.rule(naam + " (" + subj.type().naam + " " + (s + 1) + ")").build(items.toArray(new RuleItemBuilder[0])));
        }
        return rules;
    }

    // ---------------- §9.7 / §13.4.10 verdeling (Phase E) ----------------
    /** §13.4.10 verdeling: a source amount ({@code bronAttribuut} of {@code bronType}) is distributed over a
     *  recipient attribute ({@code doelAttribuut} of the related {@code doelType}) by a method. GELIJKE_DELEN
     *  (total/count) and NAAR_RATO (total·weight/Σweight) each fold the recipients in one accumulate share rule;
     *  OP_VOLGORDE (§9.7.2) collects the recipients and lowers the whole ordered group-by-group allocation through
     *  {@link RegelSpraakRuntime#rsVerdeelOpVolgorde}. The §9.7.3 cap / §9.7.4 floor ride on every fireable method;
     *  the §9.7.6 empty-weight and leeg/negatief-maximum Fouten are SEPARATE insert-only Inconsistentie companions.
     *  Mirrors DrlTranspiler.verdeling. Returns the rules, or an empty list (recording an honest skip) for an
     *  unresolvable join / non-numeric target/weight / non-orderable ordering key / a §9.7.5 onverdeelde-rest
     *  modifier (the rest rule is a source-modify that forms a share↔rest cycle DRL breaks via property-reactive
     *  {@code modify}; the reflective model's opaque lambdas are class-reactive, so it lands as a focused follow-up). */
    private List<Rule> verdeling(ResolvedVerdeling v) {
        Objecttype bron = v.bronType(), doel = v.doelType();
        String join = bron != null && doel != null ? model.verbindendeRol(doel, bron) : null;
        ResolvedVerdeling.Methode methode = v.methode();
        Datatype doelType = doel != null && v.doelAttribuut() != null ? doel.attribuut(v.doelAttribuut()) : null;
        boolean doelOk = doelType != null && doelType.isGetalachtig();
        Datatype gewichtType = doel != null && v.ratoAttribuut() != null ? doel.attribuut(v.ratoAttribuut()) : null;
        boolean gelijk = methode == ResolvedVerdeling.Methode.GELIJKE_DELEN && join != null && doelOk;
        boolean rato = methode == ResolvedVerdeling.Methode.NAAR_RATO && join != null && doelOk
                && gewichtType != null && gewichtType.isGetalachtig();
        Datatype groepType = methode == ResolvedVerdeling.Methode.OP_VOLGORDE && doel != null && v.groepeerAttribuut() != null
                ? doel.attribuut(v.groepeerAttribuut()) : null;
        boolean opVolgorde = methode == ResolvedVerdeling.Methode.OP_VOLGORDE && join != null && doelOk
                && groepType != null && groepType.isOrdenbaar()
                && (v.ratoAttribuut() == null || (gewichtType != null && gewichtType.isGetalachtig()));
        if (!gelijk && !rato && !opVolgorde)
            return skipList("verdeling (onopgelost " + (bron == null || doel == null ? "referentie" : methode.name().toLowerCase()) + "): " + v.naam());
        // TWO verdelingen writing the SAME recipient (doelType, doelAttribuut) infinite-loop in the model — the
        // reflective share rules are class-reactive, so each update(r) re-activates the SIBLING (NO_LOOP blocks only
        // self-retrigger). Adding .watch(join) to the Deelnemer patterns DOES break the loop (empirically verified),
        // but then the back-ends DIVERGE on the VALUE: which verdeling "wins" the shared attribute is agenda-order
        // dependent, and DRL (KieHelper) and the model (createKieBaseFromModel) order the rules differently — e.g. 100
        // over 1:3 gave DRL a1=25/a2=75 (naar-rato won) vs model a1=50/a2=50 (gelijke-delen won). The construct is
        // DEGENERATE (two distribution rules conflicting on one attribute is a modeling error; RegelSpraak defines no
        // value for it), so the principled choice is an HONEST skip — not a fragile agenda-order parity. (Single-writer
        // — every real fixture — is unaffected.)
        long writers = model.verdelingen.stream().filter(o -> o.doelType() != null && doel != null
                && o.doelType().naam.equals(doel.naam) && java.util.Objects.equals(o.doelAttribuut(), v.doelAttribuut())).count();
        if (writers > 1) return skipList("verdeling (>1 verdeling schrijft hetzelfde doel-attribuut — agenda-order-afhankelijke waarde, principiële skip): " + v.naam());
        Class<?> bronClass = factClass(bron), doelClass = factClass(doel);
        if (bronClass == null || doelClass == null) return skipList("verdeling (geen fact-class): " + v.naam());

        List<Rule> rules = new ArrayList<>();
        rules.add(opVolgorde ? opVolgordeRegel(v, join, bronClass, doelClass)
                             : gelijkOfRatoRegel(v, join, gelijk, bronClass, doelClass));
        // §9.7.5 onverdeelde rest: the source keeps total − Σ(assigned shares). naar-rato's recipient filter excludes
        // the weightless (they get no share); gelijke/op-volgorde collect every recipient.
        if (v.restantAttribuut() != null)
            rules.add(restantRegel(v, join, rato, bronClass, doelClass));
        // §9.7.6 Tabel 19 Fouten: surface an empty weight (whenever a naar-rato split is present) or a leeg/negatief
        // maximum at ANY recipient as a SEPARATE insert-only Inconsistentie companion (the value side already
        // degrades a bad cap to uncapped via rsPlafond — never a negative share).
        Class<?> incClass = inconsistentieClass();
        boolean tieNaarRato = rato || (opVolgorde && v.ratoAttribuut() != null);
        if (tieNaarRato && incClass != null) {
            String w = v.ratoAttribuut();
            rules.add(existsFoutRegel(v.naam() + " (leeg gewicht)", v.naam(), bronClass, doelClass, join, incClass,
                    d -> RegelSpraakRuntime.get(d, w) == null, v.geldigheid()));
        }
        if (v.maximumAttribuut() != null && incClass != null) {
            String mx = v.maximumAttribuut();
            rules.add(existsFoutRegel(v.naam() + " (leeg of negatief maximum)", v.naam(), bronClass, doelClass, join, incClass,
                    d -> { Object m = RegelSpraakRuntime.get(d, mx); return m == null || ((BigDecimal) m).signum() < 0; }, v.geldigheid()));
        }
        return rules;
    }

    /** §13.4.10 GELIJKE_DELEN / NAAR_RATO: one accumulate folds the recipients joined to {@code $src} (count, or the
     *  Σ of the present weights); each recipient {@code $r} gets total/count or total·weight/Σweight, then the §9.7.3
     *  cap (naar rato) and §9.7.4 floor. A non-zero weight-sum guards the naar-rato divide (a weightless recipient is
     *  excluded — left unset — and the §9.7.6 empty-weight Fout is the separate companion). Mirrors gelijkOfRatoRegel. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule gelijkOfRatoRegel(ResolvedVerdeling v, String join, boolean gelijk, Class<?> bronClass, Class<?> doelClass) {
        seq = 0;
        String bronAttr = v.bronAttribuut(), doelAttr = v.doelAttribuut(), gewAttr = v.ratoAttribuut(), maxAttr = v.maximumAttribuut();
        Integer dec = v.afrondingDecimalen();
        // §9.7.5 cycle break: the SOURCE pattern watches ONLY the source amount, NOT the restant a rest rule writes —
        // so a rest rule's update(src) (mask=restant) does not re-wake this share rule (the share↔rest Pot cycle).
        // The Deelnemer accumulate/recipient patterns stay class-reactive (they must react to recipient inserts); they
        // never drive a loop here (NO_LOOP blocks the share's own aandeel-write re-trigger, and a rest writes the Pot,
        // not a Deelnemer). The multi-writer-same-attribute loop is separately GUARDED above. watch(...) is honoured by
        // the default model base (empirically verified, Phase E review).
        Variable srcVar = DSL.declarationOf(bronClass, "$src");
        PatternDSL.PatternDef srcPat = PatternDSL.pattern(srcVar).watch(prop(bronAttr));
        srcPat = geldigGuard(srcPat, v.geldigheid());   // §4.2 window (Pot source; the rekendatum global is constant, no extra reactivity)

        // accumulate the recipients joined to $src: count (gelijke delen) or sum of the present weights (naar rato).
        Variable cVar = DSL.declarationOf(doelClass, "$c_" + (seq++));
        PatternDSL.PatternDef accSrc = PatternDSL.pattern(cVar).expr(nextId("join"), (Variable) srcVar,
                (Predicate2) (c, s) -> RegelSpraakRuntime.get(c, join) == s);
        Variable foldVar = DSL.declarationOf(Object.class, "$fold_" + (seq++));
        RuleItemBuilder<?> acc;
        if (gelijk) {
            acc = DSL.accumulate(accSrc, DSL.accFunction(accFn("CountAccumulateFunction")).as(foldVar));
        } else {
            accSrc = accSrc.expr(nextId("wnn"), (Predicate1) c -> RegelSpraakRuntime.get(c, gewAttr) != null);
            Variable wVar = DSL.declarationOf(BigDecimal.class, "$w_" + (seq++));
            accSrc = accSrc.bind(wVar, (Function1) c -> RegelSpraakRuntime.get(c, gewAttr));
            acc = DSL.accumulate(accSrc, DSL.accFunction(accFn("BigDecimalSumAccumulateFunction"), wVar).as(foldVar));
        }

        // the recipient pattern: $r joined to $src (naar rato additionally requires a present weight).
        Variable rVar = DSL.declarationOf(doelClass, "$r");
        PatternDSL.PatternDef rPat = PatternDSL.pattern(rVar).expr(nextId("rjoin"), (Variable) srcVar,
                (Predicate2) (r, s) -> RegelSpraakRuntime.get(r, join) == s);
        if (!gelijk) rPat = rPat.expr(nextId("rwnn"), (Predicate1) r -> RegelSpraakRuntime.get(r, gewAttr) != null);

        List<RuleItemBuilder<?>> items = new ArrayList<>();
        items.add(srcPat); items.add(acc); items.add(rPat);
        if (!gelijk)   // a zero weight-sum would divide-by-zero (the spec leaves it undefined): guard it out.
            items.add(PatternDSL.pattern(foldVar).expr(nextId("sumnz"),
                    (Predicate1) s -> s != null && ((BigDecimal) s).signum() != 0));

        final boolean gelijkF = gelijk;
        Block4<org.drools.model.Drools, Object, Object, Object> body = (drools, s, r, fold) -> {
            BigDecimal totaal = (BigDecimal) RegelSpraakRuntime.get(s, bronAttr);
            BigDecimal aandeel;
            if (gelijkF) {
                aandeel = totaal.divide(RegelSpraakRuntime.toBd(fold), java.math.MathContext.DECIMAL64);
            } else {
                BigDecimal gewicht = (BigDecimal) RegelSpraakRuntime.get(r, gewAttr);
                aandeel = totaal.multiply(gewicht).divide((BigDecimal) fold, java.math.MathContext.DECIMAL64);
            }
            if (maxAttr != null) aandeel = RegelSpraakRuntime.rsPlafond(aandeel, (BigDecimal) RegelSpraakRuntime.get(r, maxAttr));
            if (dec != null) aandeel = RegelSpraakRuntime.rsAfronding(aandeel, dec, java.math.RoundingMode.FLOOR);
            RegelSpraakRuntime.set(r, doelAttr, aandeel);
            drools.update(r);
        };
        items.add(DSL.on(srcVar, (Variable) rVar, (Variable) foldVar).execute(body));
        return PatternDSL.rule(v.naam()).attribute(Rule.Attribute.NO_LOOP, true).build(items.toArray(new RuleItemBuilder[0]));
    }

    /** §9.7.2 "op volgorde van": one share rule whose accumulate collects every recipient joined to {@code $src} into
     *  a list, then the consequence recomputes the whole ordered allocation via {@link RegelSpraakRuntime#rsVerdeelOpVolgorde}
     *  (reading the ordering criterium / tie weight / cap reflectively off the collected facts) and assigns each
     *  recipient {@code $r} its identity-keyed share. The cap+floor are applied inside the helper, group by group, so
     *  a future onverdeelde-rest reflects the carried-down pot (§9.7.5 Tabel 18). Mirrors opVolgordeRegel. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule opVolgordeRegel(ResolvedVerdeling v, String join, Class<?> bronClass, Class<?> doelClass) {
        seq = 0;
        String bronAttr = v.bronAttribuut(), doelAttr = v.doelAttribuut(), groepAttr = v.groepeerAttribuut(),
                gewAttr = v.ratoAttribuut(), maxAttr = v.maximumAttribuut();
        Integer dec = v.afrondingDecimalen();
        boolean toenemend = !v.afnemend();
        // cycle break: the SOURCE pattern watches only the source amount (NOT restant); see gelijkOfRatoRegel.
        Variable srcVar = DSL.declarationOf(bronClass, "$src");
        PatternDSL.PatternDef srcPat = PatternDSL.pattern(srcVar).watch(prop(bronAttr));
        srcPat = geldigGuard(srcPat, v.geldigheid());   // §4.2 window (Pot source; the rekendatum global is constant, no extra reactivity)

        // collect every recipient joined to $src into one index-stable list.
        Variable pVar = DSL.declarationOf(doelClass, "$p_" + (seq++));
        Variable idVar = DSL.declarationOf(Object.class, "$id_" + (seq++));
        PatternDSL.PatternDef accSrc = PatternDSL.pattern(pVar)
                .expr(nextId("join"), (Variable) srcVar, (Predicate2) (p, s) -> RegelSpraakRuntime.get(p, join) == s)
                .bind(idVar, (Function1) p -> p);
        Variable factsVar = DSL.declarationOf(java.util.List.class, "$facts_" + (seq++));
        RuleItemBuilder<?> acc = DSL.accumulate(accSrc, DSL.accFunction(accFn("CollectListAccumulateFunction"), idVar).as(factsVar));

        Variable rVar = DSL.declarationOf(doelClass, "$r");
        PatternDSL.PatternDef rPat = PatternDSL.pattern(rVar).expr(nextId("rjoin"), (Variable) srcVar,
                (Predicate2) (r, s) -> RegelSpraakRuntime.get(r, join) == s);

        Block4<org.drools.model.Drools, Object, Object, Object> body = (drools, s, r, facts) -> {
            List<Object> fl = (List<Object>) facts;
            BigDecimal totaal = (BigDecimal) RegelSpraakRuntime.get(s, bronAttr);
            List<Object> groepL = new ArrayList<>();
            for (Object f : fl) groepL.add(RegelSpraakRuntime.get(f, groepAttr));
            List<Object> gewL = null, maxL = null;
            if (gewAttr != null) { gewL = new ArrayList<>(); for (Object f : fl) gewL.add(RegelSpraakRuntime.get(f, gewAttr)); }
            if (maxAttr != null) { maxL = new ArrayList<>(); for (Object f : fl) maxL.add(RegelSpraakRuntime.get(f, maxAttr)); }
            Map<Object, BigDecimal> shares = RegelSpraakRuntime.rsVerdeelOpVolgorde(totaal, fl, groepL, toenemend, gewL, maxL, dec);
            RegelSpraakRuntime.set(r, doelAttr, shares.get(r));
            drools.update(r);
        };
        return PatternDSL.rule(v.naam()).attribute(Rule.Attribute.NO_LOOP, true)
                .build(srcPat, acc, rPat, DSL.on(srcVar, (Variable) rVar, (Variable) factsVar).execute(body));
    }

    /** §9.7.6 build a verdeling Fout companion: an insert-only rule (NO no-loop, idempotency-guarded) firing once per
     *  source that has a recipient matching {@code recipientFout} (an empty weight, or a leeg/negatief maximum),
     *  inserting an Inconsistentie carrying the verdeling's name. The distribution still runs (the §9.7.6 "degrade +
     *  flag" representation); this only surfaces the Fout. Mirrors the DRL "(leeg gewicht)"/"(leeg of negatief maximum)" rules. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule existsFoutRegel(String compNaam, String hostNaam, Class<?> bronClass, Class<?> doelClass, String join,
                                 Class<?> incClass, Predicate1<Object> recipientFout, io.github.yhavinga.regelspraak.ir.Geldigheid geldigheid) {
        seq = 0;
        String fqcn = FACTS + ".Inconsistentie";
        Variable srcVar = DSL.declarationOf(bronClass, "$src");
        PatternDSL.PatternDef srcPat = geldigGuard(PatternDSL.pattern(srcVar), geldigheid);   // §4.2 window
        Variable dVar = DSL.declarationOf(doelClass, "$d_" + (seq++));
        PatternDSL.PatternDef dPat = PatternDSL.pattern(dVar)
                .expr(nextId("fj"), (Variable) srcVar, (Predicate2) (d, s) -> RegelSpraakRuntime.get(d, join) == s)
                .expr(nextId("fp"), (Predicate1) recipientFout);
        Variable incVar = DSL.declarationOf(incClass, "$inc_" + (seq++));
        PatternDSL.PatternDef guard = PatternDSL.pattern(incVar)
                .expr(nextId("ig"), (Predicate1) i -> hostNaam.equals(RegelSpraakRuntime.get(i, "regel")))
                .expr(nextId("ig"), (Variable) srcVar, (Predicate2) (i, s) -> RegelSpraakRuntime.get(i, "subject") == s);
        Block2<org.drools.model.Drools, Object> body = (drools, s) ->
                drools.insert(RegelSpraakRuntime.newInconsistentie(fqcn, hostNaam, s));
        return PatternDSL.rule(compNaam).build(srcPat, DSL.exists(dPat), DSL.not(guard), DSL.on(srcVar).execute(body));
    }

    /** The §9.7.5 recipient filter shared by the rest rule's {@code not} guard and its sum accumulate: the join,
     *  plus (naar rato) a present weight — the SAME set the share rule distributed over. Left class-reactive (the
     *  caller adds the {@code doelAttr ==/!= null} constraint); it writes no Deelnemer, so it cannot loop. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private PatternDSL.PatternDef recipientFilterPattern(Variable var, Variable srcVar, String join, boolean vereisGewicht,
                                                         String gewAttr, String doelAttr) {
        PatternDSL.PatternDef p = PatternDSL.pattern(var).expr(nextId("rfj"), (Variable) srcVar,
                (Predicate2) (d, s) -> RegelSpraakRuntime.get(d, join) == s);
        if (vereisGewicht) p = p.expr(nextId("rfw"), (Predicate1) d -> RegelSpraakRuntime.get(d, gewAttr) != null);
        return p;
    }

    /** §9.7.5 onverdeelde rest: the source keeps {@code total − Σ(assigned shares)}. A SEPARATE rule that modifies the
     *  SOURCE (so it cannot cancel the per-recipient share activations); method-agnostic and RECOMPUTABLE — it re-fires
     *  whenever the share set changes (a §9.4 rule-derived recipient may arrive late, so a fire-once freeze would lock
     *  in a partial sum). The {@code not Doel(filter, doelAttr == null)} guard keeps it from summing a recipient
     *  mid-assignment. Termination: the source pattern watches the source amount only (NOT the restant it writes), so
     *  its own update does not re-wake it (and the share rule's source pattern likewise does not watch restant); the
     *  recipient patterns watch the join + the assigned share, so the rest recomputes exactly when a share lands.
     *  Mirrors the DRL "(onverdeelde rest)" rule. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule restantRegel(ResolvedVerdeling v, String join, boolean vereisGewicht, Class<?> bronClass, Class<?> doelClass) {
        seq = 0;
        String bronAttr = v.bronAttribuut(), doelAttr = v.doelAttribuut(), restAttr = v.restantAttribuut(), gewAttr = v.ratoAttribuut();
        Variable srcVar = DSL.declarationOf(bronClass, "$src");
        PatternDSL.PatternDef srcPat = PatternDSL.pattern(srcVar)
                .expr(nextId("bnn"), (Predicate1) s -> RegelSpraakRuntime.get(s, bronAttr) != null)
                .watch(prop(bronAttr));   // NOT prop(restAttr): the rest's own write must not re-wake it / the share rule
        srcPat = geldigGuard(srcPat, v.geldigheid());   // §4.2 window
        // not Doel( filter, doelAttr == null ) — no recipient is mid-assignment. The Deelnemer patterns stay
        // class-reactive (they MUST react to aandeel becoming set); they write no Deelnemer, so they cannot loop.
        Variable notVar = DSL.declarationOf(doelClass, "$u0_" + (seq++));
        PatternDSL.PatternDef notPat = recipientFilterPattern(notVar, srcVar, join, vereisGewicht, gewAttr, doelAttr)
                .expr(nextId("unset"), (Predicate1) d -> RegelSpraakRuntime.get(d, doelAttr) == null);
        // accumulate Σ of the assigned shares.
        Variable uVar = DSL.declarationOf(doelClass, "$u_" + (seq++));
        PatternDSL.PatternDef accSrc = recipientFilterPattern(uVar, srcVar, join, vereisGewicht, gewAttr, doelAttr)
                .expr(nextId("set"), (Predicate1) d -> RegelSpraakRuntime.get(d, doelAttr) != null);
        Variable amtVar = DSL.declarationOf(BigDecimal.class, "$amt_" + (seq++));
        accSrc = accSrc.bind(amtVar, (Function1) d -> RegelSpraakRuntime.get(d, doelAttr));
        Variable sumVar = DSL.declarationOf(Object.class, "$uit_" + (seq++));
        RuleItemBuilder<?> acc = DSL.accumulate(accSrc, DSL.accFunction(accFn("BigDecimalSumAccumulateFunction"), amtVar).as(sumVar));
        Block3<org.drools.model.Drools, Object, Object> body = (drools, s, uit) -> {
            BigDecimal totaal = (BigDecimal) RegelSpraakRuntime.get(s, bronAttr);
            BigDecimal sum = RegelSpraakRuntime.toBd(uit);
            RegelSpraakRuntime.set(s, restAttr, totaal.subtract(sum == null ? BigDecimal.ZERO : sum));
            drools.update(s);
        };
        return PatternDSL.rule(v.naam() + " (onverdeelde rest)").attribute(Rule.Attribute.NO_LOOP, true)
                .build(srcPat, DSL.not(notPat), acc, DSL.on(srcVar, (Variable) sumVar).execute(body));
    }

    private List<Rule> skipList(String reason) { overgeslagen.add(reason); return List.of(); }

    // ---------------- §13.4.6 objectcreatie / §13.4.7 feitcreatie (Phase G) ----------------
    /** §13.4.6 objectcreatie: the subject creates a NEW object of {@code nieuwType}, sets each toekenning value, sets
     *  the back-reference to {@code $subject} (the §9.4 feittype role on the new object), and inserts it. An
     *  insert-only rule (NO no-loop — it never modifies the subject, so it cannot self-retrigger), gated by the
     *  voorwaarde (incl. a §8.1.7 far-side exists). null -> skip (unresolved type / no fact class / a toekenning value
     *  out of the scalar scope / >1 global). Mirrors DrlTranspiler.objectCreatie. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule objectCreatie(io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie oc) {
        Objecttype nieuw = oc.nieuwType(), subject = oc.subject();
        if (subject == null || nieuw == null) return skip("objectcreatie (onopgelost type): " + oc.naam());
        Class<?> subjClass = factClass(subject), nieuwClass = factClass(nieuw);
        if (subjClass == null || nieuwClass == null) return skip("objectcreatie (geen fact-class): " + oc.naam());
        String backRef = model.verbindendeRol(nieuw, subject);   // field on $new -> subject (null -> orphan insert, like DRL)

        // lower each toekenning value to a (subject, globals) -> value function; collect the Parameter-globals read.
        List<String> attrs = new ArrayList<>();
        List<BiFunction<Object, Map<String, BigDecimal>, Object>> fns = new ArrayList<>();
        Set<String> gNames = new LinkedHashSet<>();
        for (var t : oc.toekenningen()) {
            WaardeFn wf = scalarWaarde(t.waarde());
            if (wf == null) return skip("objectcreatie (toekenning-waarde buiten scope): " + oc.naam());
            attrs.add(t.attribuut());
            fns.add(wf.fn());
            gNames.addAll(wf.globals());
        }
        List<String> gList = new ArrayList<>(gNames);

        seq = 0;
        Variable sVar = DSL.declarationOf(subjClass, "$subject");
        PatternDSL.PatternDef pattern = PatternDSL.pattern(sVar);
        List<RuleItemBuilder<?>> extra = new ArrayList<>();
        if (oc.voorwaarde() != null) {
            Gate gate = voorwaarde(oc.voorwaarde(), pattern, subject, sVar);
            if (gate == null) return skip("objectcreatie (voorwaarde buiten scope): " + oc.naam());
            pattern = gate.pattern();
            extra.addAll(gate.extra());
        }
        pattern = geldigGuard(pattern, oc.geldigheid());   // §4.2 version validity window

        final List<String> fAttrs = attrs; final List<BiFunction<Object, Map<String, BigDecimal>, Object>> fFns = fns;
        final String fBackRef = backRef; final Class<?> fNieuwClass = nieuwClass;
        final List<String> fG = gList; final int nG = gList.size();
        java.util.function.BiConsumer<org.drools.model.Drools, Object[]> body = (drools, args) -> {
            Object s = args[0];
            Map<String, BigDecimal> gm = new LinkedHashMap<>();
            for (int i = 0; i < nG; i++) gm.put(fG.get(i), (BigDecimal) args[1 + i]);
            Object newObj = RegelSpraakRuntime.newInstance(fNieuwClass);
            for (int i = 0; i < fAttrs.size(); i++) RegelSpraakRuntime.set(newObj, fAttrs.get(i), fFns.get(i).apply(s, gm));
            if (fBackRef != null) RegelSpraakRuntime.set(newObj, fBackRef, s);
            drools.insert(newObj);
        };
        List<Variable> vars = new ArrayList<>();
        vars.add(sVar);
        for (String gn : gList) {
            Global<BigDecimal> g = globals.get(gn);
            if (g == null) return skip("objectcreatie (niet-geregistreerde global): " + oc.naam());
            vars.add((Variable) g);
        }
        RuleItemBuilder<?> consequence = consequenceOf(vars.toArray(new Variable[0]), body);
        if (consequence == null) return skip("objectcreatie (te veel gebonden variabelen): " + oc.naam());

        List<RuleItemBuilder<?>> items = new ArrayList<>();
        items.add(pattern); items.addAll(extra); items.add(consequence);
        return PatternDSL.rule(oc.naam()).build(items.toArray(new RuleItemBuilder[0]));
    }

    /** §13.4.7 feitcreatie: for each {@code anker} and each {@code doel} ALREADY related to it (the §9.4 path: the
     *  optional multi-hop {@code tussenstappen} then the final {@code joinVeld}), CREATE the new relation by setting
     *  {@code zetVeld} on the doel -> {@code modify($doel){ set zetVeld($subject) }}, NO_LOOP. The voorwaarde gates the
     *  anker (its pattern) or the doel (its pattern); a guard lowerable to NEITHER makes the rule INERT (a never-true
     *  guard) rather than over-creating. Mirrors DrlTranspiler.feitCreatie. null -> skip (unresolved). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule feitCreatie(io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie fc) {
        Objecttype anker = fc.anker(), doel = fc.doel();
        if (anker == null || doel == null || fc.zetVeld() == null || fc.joinVeld() == null)
            return skip("feitcreatie (onopgelost): " + fc.naam());
        Class<?> ankerClass = factClass(anker), doelClass = factClass(doel);
        if (ankerClass == null || doelClass == null) return skip("feitcreatie (geen fact-class): " + fc.naam());

        seq = 0;
        Variable sVar = DSL.declarationOf(ankerClass, "$subject");
        PatternDSL.PatternDef ankerPat = geldigGuard(PatternDSL.pattern(sVar), fc.geldigheid());   // §4.2 window (on the anker)
        List<RuleItemBuilder<?>> ankerExtra = new ArrayList<>();
        // §8.1.7 the voorwaarde: try the ANKER side first (its constraints + any far-side exists ride the anker
        // pattern); if it cannot lower there, try the DOEL side (an in-pattern predicate on $doel); else INERT.
        Predicate1<Object> doelGuard = null;
        boolean inert = false;
        if (fc.voorwaarde() != null) {
            Gate g = voorwaarde(fc.voorwaarde(), ankerPat, anker, sVar);
            if (g != null) {
                ankerPat = g.pattern();
                ankerExtra.addAll(g.extra());
            } else {
                doelGuard = leafPredicate(fc.voorwaarde(), doel);   // a fact-only predicate on the doel
                if (doelGuard == null) inert = true;                // lowerable to NEITHER -> never-fire, never over-create
            }
        }
        // An inert voorwaarde is a CORRECT lowering (create NOTHING — the model analog of DRL's eval(false) no-op),
        // NOT an unmigrated skip: emit no rule and do NOT record overgeslagen, so the differential FIRES it (both
        // back-ends create nothing -> agree). A model that wrongly goes inert where DRL lowers then surfaces as a real
        // divergence rather than a hidden skip.
        if (inert) return null;

        // §9.4 multi-hop: bind each intermediate object, joining to the previous; $prev starts at the anker.
        List<RuleItemBuilder<?>> items = new ArrayList<>();
        items.add(ankerPat);
        items.addAll(ankerExtra);
        Variable vorige = sVar;
        for (io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie.Tussenstap stap : fc.tussenstappen()) {
            Class<?> stapClass = factClass(stap.type());
            if (stapClass == null) return skip("feitcreatie (geen fact-class tussenstap): " + fc.naam());
            String veld = stap.veld();
            boolean viaOuder = stap.viaOuderVeld();
            Variable stapVar = DSL.declarationOf(stapClass, "$t_" + (seq++));
            Variable prev = vorige;
            // a to-one hop reads the PARENT's forward ref (this == $prev.veld); a to-many hop reads the CHILD's backref (veld == $prev).
            PatternDSL.PatternDef stapPat = PatternDSL.pattern(stapVar).expr(nextId("hop"), (Variable) prev,
                    (Predicate2) (o, p) -> viaOuder ? RegelSpraakRuntime.get(p, veld) == o : RegelSpraakRuntime.get(o, veld) == p);
            items.add(stapPat);
            vorige = stapVar;
        }

        String joinVeld = fc.joinVeld(), zetVeld = fc.zetVeld();
        Variable doelVar = DSL.declarationOf(doelClass, "$doel");
        Variable laatste = vorige;
        PatternDSL.PatternDef doelPat = PatternDSL.pattern(doelVar).expr(nextId("djoin"), (Variable) laatste,
                (Predicate2) (d, p) -> RegelSpraakRuntime.get(d, joinVeld) == p);
        if (doelGuard != null) doelPat = doelPat.expr(nextId("dg"), (Predicate1) doelGuard);
        items.add(doelPat);

        // create the relation: set the anker-role reference on $doel; NO_LOOP (the modify must not re-fire the rule).
        Block3<org.drools.model.Drools, Object, Object> body = (drools, subj, d) -> {
            RegelSpraakRuntime.set(d, zetVeld, subj);
            drools.update(d);
        };
        items.add(DSL.on(sVar, (Variable) doelVar).execute(body));
        return PatternDSL.rule(fc.naam()).attribute(Rule.Attribute.NO_LOOP, true).build(items.toArray(new RuleItemBuilder[0]));
    }

    /** A null-safe {@code (subject -> value)} reader for a §6.5/§6.6 fout-companion OPERAND, or null when the operand
     *  has no re-readable leeg/vol guard. Accepts the subject's OWN attribute and a to-ONE NAVIGATED attribute
     *  (root-guarded -> a null relation reads leeg), mirroring DrlTranspiler.companionDeel's EIGEN / GENAVIGEERD
     *  acceptance. Null for a literal / parameter / to-many / unresolvable operand and for a TEMPORAL attribute (whose
     *  List leeg/vol would test the timeline's nullness, not per-moment leegte — DRL markers it too), so such a
     *  division/wortel raises no Inconsistentie in EITHER back-end. */
    private java.util.function.Function<Object, Object> companionOperand(Expr e, Objecttype subject) {
        if (!(e instanceof Expr.AttribuutRef a) || a.objecttype() == null) return null;
        if (a.objecttype().attribuutGranulariteit.containsKey(a.attribuut())) return null;   // temporal -> no re-readable guard
        if (!a.objecttype().dimensiesVan(a.attribuut()).isEmpty()) return null;              // §3.6 gedimensioneerd (a Map) -> DRL markers it too
        return navReader(a, subject);   // own attribute or to-one navigated (null for to-many / absent / unresolvable)
    }

    /** §6.5 Tabel 12 "waarde gedeeld door leeg" companion: the value side already degrades the division to leeg
     *  (rsGedeeld), so this surfaces the Fout via the §9.5 Inconsistentie channel — a SEPARATE insert-only rule per
     *  division that fires when the dividend is present and the divisor is empty (and the host voorwaarde holds).
     *  Scope: each operand is the subject's OWN attribute or one reached by a to-ONE navigation (companionOperand).
     *  Mirrors DrlTranspiler.deelDoorLeegFout. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Rule> delingCompanions(ResolvedRegel r) {
        if (!(r.doel() instanceof Expr.AttribuutRef doel) || doel.objecttype() == null) return List.of();
        Objecttype subject = doel.objecttype();
        Class<?> factClass = factClass(subject);
        Class<?> incClass = inconsistentieClass();
        if (factClass == null || incClass == null) return List.of();
        List<Expr.Binair> delingen = new ArrayList<>();
        verzamelDelingen(r.waarde(), delingen);
        // First collect the QUALIFYING divisions (both operands a re-readable own OR to-one navigated attribute),
        // mirroring DRL's `patronen` (companionDeel accepts EIGEN and GENAVIGEERD), so the companion-name suffix
        // indexes over the SAME filtered list in BOTH back-ends. Indexing over the raw `delingen` count diverged from
        // DRL when a rule mixes qualifying and non-qualifying divisions (e.g. a §11 division variable inlined next to a
        // parameter division): DRL named the lone qualifier "(deling door leeg)", the model "(deling door leeg 1)".
        List<Expr.Binair> kwalificeren = new ArrayList<>();
        for (Expr.Binair d : delingen)
            if (companionOperand(d.links(), subject) != null && companionOperand(d.rechts(), subject) != null)
                kwalificeren.add(d);
        List<Rule> companions = new ArrayList<>();
        String naam = r.naam();
        for (int i = 0; i < kwalificeren.size(); i++) {
            Expr.Binair d = kwalificeren.get(i);
            // null-safe readers for the dividend/divisor — own attribute or a to-one navigated read (a null relation
            // collapses to leeg, exactly as DRL's root-guarded "(reis != null && reis.x != null)" / "reis.x" forms).
            java.util.function.Function<Object, Object> dividend = companionOperand(d.links(), subject);
            java.util.function.Function<Object, Object> divisor = companionOperand(d.rechts(), subject);
            seq = 0;
            Variable sVar = DSL.declarationOf(factClass, "$subject");
            PatternDSL.PatternDef pattern = PatternDSL.pattern(sVar);
            List<RuleItemBuilder<?>> gateExtra = List.of();
            if (r.voorwaarde() != null) {                       // the division only fires when the host gate holds
                Gate gate = voorwaarde(r.voorwaarde(), pattern, subject, sVar);
                if (gate == null) continue;                     // unlowerable host voorwaarde -> no companion (DRL: voorwaardeMarker -> return)
                pattern = gate.pattern();
                gateExtra = gate.extra();                       // a §8.1.7 far-side membership exists/not -> thread it into the companion (mirror DRL lc.extra())
            }
            pattern = geldigGuard(pattern, r.geldigheid());   // §4.2 the companion only flags inside the host's window
            // dividend present AND divisor empty (an empty LEFT short-circuits to 0 -> no fout, so guard the dividend too).
            pattern = pattern.expr(nextId("dvd"), (Predicate1) s -> dividend.apply(s) != null)
                    .expr(nextId("dvs"), (Predicate1) s -> divisor.apply(s) == null);
            String compNaam = naam + " (deling door leeg" + (kwalificeren.size() > 1 ? " " + (i + 1) : "") + ")";
            companions.add(companionRule(compNaam, naam, incClass, sVar, pattern, gateExtra));
        }
        return companions;
    }

    /** §6.6 "wortel van een negatief getal" companion: the value side degrades a negative-radicand root to leeg
     *  (rsWortel), so this surfaces the Fout via the §9.5 Inconsistentie channel — a SEPARATE insert-only rule per
     *  wortel whose radicand (own attribute or a to-ONE navigation, companionOperand) is &lt; 0 (and the host
     *  voorwaarde holds). Mirrors DrlTranspiler.wortelNegatiefFout. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Rule> wortelCompanions(ResolvedRegel r) {
        if (!(r.doel() instanceof Expr.AttribuutRef doel) || doel.objecttype() == null) return List.of();
        Objecttype subject = doel.objecttype();
        Class<?> factClass = factClass(subject);
        Class<?> incClass = inconsistentieClass();
        if (factClass == null || incClass == null) return List.of();
        List<Expr.Functie> wortels = new ArrayList<>();
        verzamelWortels(r.waarde(), wortels);
        // Collect the QUALIFYING wortels (radicand a re-readable own OR to-one navigated attribute), so the
        // companion-name suffix indexes over the SAME filtered list as DRL's wortelNegatiefFout `guards`.
        List<Expr.Functie> kwalificeren = new ArrayList<>();
        for (Expr.Functie w : wortels)
            if (w.argumenten().size() == 1 && companionOperand(w.argumenten().get(0), subject) != null)
                kwalificeren.add(w);
        List<Rule> companions = new ArrayList<>();
        String naam = r.naam();
        for (int i = 0; i < kwalificeren.size(); i++) {
            // null-safe reader for the radicand — own attribute or a to-one navigated read (null relation -> leeg).
            java.util.function.Function<Object, Object> radicand = companionOperand(kwalificeren.get(i).argumenten().get(0), subject);
            seq = 0;
            Variable sVar = DSL.declarationOf(factClass, "$subject");
            PatternDSL.PatternDef pattern = PatternDSL.pattern(sVar);
            List<RuleItemBuilder<?>> gateExtra = List.of();
            if (r.voorwaarde() != null) {
                Gate gate = voorwaarde(r.voorwaarde(), pattern, subject, sVar);
                if (gate == null) continue;                     // unlowerable host voorwaarde -> no companion (DRL: voorwaardeMarker -> return)
                pattern = gate.pattern();
                gateExtra = gate.extra();                       // a §8.1.7 far-side membership exists/not -> thread it into the companion (mirror DRL lc.extra())
            }
            pattern = geldigGuard(pattern, r.geldigheid());   // §4.2 the companion only flags inside the host's window
            // radicand < 0 (a null radicand is leeg, not negative -> no fout; signum guards null out).
            pattern = pattern.expr(nextId("rad"), (Predicate1) s -> {
                Object v = radicand.apply(s);
                return v != null && ((BigDecimal) v).signum() < 0;
            });
            String compNaam = naam + " (wortel van negatief" + (kwalificeren.size() > 1 ? " " + (i + 1) : "") + ")";
            companions.add(companionRule(compNaam, naam, incClass, sVar, pattern, gateExtra));
        }
        return companions;
    }
    private static void verzamelWortels(Expr e, List<Expr.Functie> uit) {
        if (e instanceof Expr.Functie f) {
            if (f.kind() == Expr.FunctieKind.WORTEL) uit.add(f);
            for (Expr a : f.argumenten()) verzamelWortels(a, uit);
        } else if (e instanceof Expr.Binair b) {
            verzamelWortels(b.links(), uit); verzamelWortels(b.rechts(), uit);
        } else if (e instanceof Expr.Afgerond a) {
            verzamelWortels(a.basis(), uit);
        } else if (e instanceof Expr.Begrensd g) {
            verzamelWortels(g.basis(), uit);
            verzamelWortels(g.minimum(), uit);   // mirror DRL verzamelWortels — a wortel inside a min/max bound
            verzamelWortels(g.maximum(), uit);
        }
    }

    /** Build a §9.5 fout-companion rule: an insert-only rule (NO no-loop) on the given subject pattern that inserts
     *  an Inconsistentie carrying the HOST rule name, idempotency-guarded against re-flagging the same subject. The
     *  {@code gateExtra} items are the host rule's §8.1.7 FAR-side membership exists/not joins (a non-empty
     *  {@link Gate#extra()}): a fout companion under a far-side voorwaarde must carry that gate too, else it loses the
     *  membership and over-fires for subjects the host rule never acts on (mirror of DrlTranspiler's extraClausules
     *  threading). The companion is insert-only, so an extra exists/not clause is safe (it cancels no sibling). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rule companionRule(String compNaam, String hostNaam, Class<?> incClass, Variable sVar,
                              PatternDSL.PatternDef pattern, List<RuleItemBuilder<?>> gateExtra) {
        String fqcn = FACTS + ".Inconsistentie";
        Variable incVar = DSL.declarationOf(incClass, "$inc_" + (seq++));
        PatternDSL.PatternDef guard = PatternDSL.pattern(incVar)
                .expr(nextId("ig"), (Predicate1) i -> hostNaam.equals(RegelSpraakRuntime.get(i, "regel")))
                .expr(nextId("ig"), (Variable) sVar, (Predicate2) (i, s) -> RegelSpraakRuntime.get(i, "subject") == s);
        Block2<org.drools.model.Drools, Object> body = (drools, s) ->
                drools.insert(RegelSpraakRuntime.newInconsistentie(fqcn, hostNaam, s));
        List<RuleItemBuilder<?>> items = new ArrayList<>();
        items.add(pattern);
        items.addAll(gateExtra);                          // the host rule's far-side membership gate, if any
        items.add(DSL.not(guard));
        items.add(DSL.on(sVar).execute(body));
        return PatternDSL.rule(compNaam).build(items.toArray(new RuleItemBuilder[0]));
    }
    /** Collect the GEDEELD_DOOR / GEDEELD_DOOR_ABS nodes through the arithmetic-wrapper layers of a value (for the
     *  §6.5 fout) — byte-identical to DrlTranspiler.verzamelDelingen so both back-ends surface the SAME divisions. */
    private static void verzamelDelingen(Expr e, List<Expr.Binair> uit) {
        if (e instanceof Expr.Binair b) {
            // §6.5 "gedeeld door" AND "gedeeld door (ABS)" both lower to a leeg-divisor fout (mirror DRL).
            if (b.op() == Expr.BinOp.GEDEELD_DOOR || b.op() == Expr.BinOp.GEDEELD_DOOR_ABS) uit.add(b);
            verzamelDelingen(b.links(), uit); verzamelDelingen(b.rechts(), uit);
        } else if (e instanceof Expr.Afgerond a) {
            verzamelDelingen(a.basis(), uit);
        } else if (e instanceof Expr.Begrensd g) {
            verzamelDelingen(g.basis(), uit);
            verzamelDelingen(g.minimum(), uit);   // mirror DRL — a division inside a min/max bound
            verzamelDelingen(g.maximum(), uit);
        }
    }

    /** §13.4.8 operator negation (the complement that detects a violation). Mirrors DrlTranspiler.flip. */
    private static Conditie.Operator flip(Conditie.Operator op) {
        return switch (op) {
            case GELIJK            -> Conditie.Operator.ONGELIJK;
            case ONGELIJK          -> Conditie.Operator.GELIJK;
            case KLEINER           -> Conditie.Operator.GROTER_OF_GELIJK;
            case KLEINER_OF_GELIJK -> Conditie.Operator.GROTER;
            case GROTER            -> Conditie.Operator.KLEINER_OF_GELIJK;
            case GROTER_OF_GELIJK  -> Conditie.Operator.KLEINER;
            case EERDER            -> Conditie.Operator.LATER_OF_GELIJK;   // §13.4.8 datum ordering negation
            case EERDER_OF_GELIJK  -> Conditie.Operator.LATER;
            case LATER             -> Conditie.Operator.EERDER_OF_GELIJK;
            case LATER_OF_GELIJK   -> Conditie.Operator.EERDER;
            default                -> op;
        };
    }

    /** Operand REVERSAL (swap LHS/RHS) of a comparison operator — distinct from {@link #flip} (logical negation):
     *  "a = b" reverses to "b = a" (GELIJK stays GELIJK), "a &gt; b" to "b &lt; a" (GROTER -&gt; KLEINER). Mirrors
     *  DrlTranspiler.omkeren; used by §8.2 to put the temporal operand on the left when it was written on the right. */
    private static Conditie.Operator omkeren(Conditie.Operator op) {
        return switch (op) {
            case GELIJK            -> Conditie.Operator.GELIJK;
            case ONGELIJK          -> Conditie.Operator.ONGELIJK;
            case KLEINER           -> Conditie.Operator.GROTER;
            case KLEINER_OF_GELIJK -> Conditie.Operator.GROTER_OF_GELIJK;
            case GROTER            -> Conditie.Operator.KLEINER;
            case GROTER_OF_GELIJK  -> Conditie.Operator.KLEINER_OF_GELIJK;
            case EERDER            -> Conditie.Operator.LATER;
            case EERDER_OF_GELIJK  -> Conditie.Operator.LATER_OF_GELIJK;
            case LATER             -> Conditie.Operator.EERDER;
            case LATER_OF_GELIJK   -> Conditie.Operator.EERDER_OF_GELIJK;
            default                -> op;
        };
    }
    private static Class<?> inconsistentieClass() {
        try { return Class.forName(FACTS + ".Inconsistentie"); }
        catch (ClassNotFoundException e) { return null; }
    }

    /** A built-in Drools accumulate function ({@code org.drools.core.base.accumulators.*}) by simple name, resolved
     *  at MODEL-BUILD time via {@link Class#forName} rather than a compile-time {@code .class} literal. This keeps
     *  {@code src/main} compiling against {@code drools-canonical-model} ALONE (the Model-building DSL) — the
     *  accumulator impls live in {@code drools-core}, which only needs to be on the RUNTIME classpath of whoever
     *  fires the Model (the consumer engine), not the compile classpath of the transpiler. {@code DSL.accFunction}
     *  accepts an unbounded {@code Class<?>}, so the runtime-resolved class flows through unchanged.
     *
     *  <p>VERSION-PINNED to Drools 8.44: this package is a drools-core-INTERNAL FQN (not {@code kie-api}). On later
     *  Drools the same classes relocate to {@code org.drools.base.accumulators} (a separate {@code drools-base}
     *  module). Centralized here behind this one constant + a fail-fast {@link ClassNotFoundException} guard, so an
     *  engine upgrade surfaces as a clear model-build error naming the missing class — never a silent miscompile. */
    private static final String ACC_PKG = "org.drools.core.base.accumulators.";
    static Class<?> accFn(String simpleName) {
        try { return Class.forName(ACC_PKG + simpleName); }
        catch (ClassNotFoundException e) {
            throw new IllegalStateException("Drools accumulate-functie " + simpleName + " ontbreekt op het classpath"
                    + " (drools-core is een RUNTIME-afhankelijkheid van de uitvoerende engine)", e);
        }
    }

    private Rule skip(String reason) { overgeslagen.add(reason); return null; }
    static Class<?> factClass(Objecttype ot) {
        try { return Class.forName(FACTS + "." + RegelSpraakRuntime.pascal(ot.naam)); }
        catch (ClassNotFoundException e) { return null; }
    }
    static String ident(String naam) {
        String s = naam.replaceFirst("^(?i)(de|het|een)\\s+", "").trim();
        StringBuilder b = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            // lowercase the VERY first character, PascalCase the rest — matching DrlTranspiler.ident(.., false), so the
            // model's parameter-GLOBAL name equals the DRL's (e.g. "STANDAARD BELASTINGVERMINDERING" -> the shared
            // "sTANDAARDBELASTINGVERMINDERING"); the differential session sets one global by that single name.
            boolean hoofd = b.length() > 0;
            b.append(hoofd ? Character.toUpperCase(w.charAt(0)) : Character.toLowerCase(w.charAt(0))).append(w.substring(1));
        }
        return b.isEmpty() ? "x" : b.toString();
    }
    /** The JAVABEAN property name Drools watches for property reactivity (the lowercase-first form of {@link #ident}):
     *  "de aandeel" -> "aandeel", "het saldo" -> "saldo". Matches the get/set the reflective bridge uses. */
    static String prop(String naam) {
        String p = RegelSpraakRuntime.pascal(naam);
        return p.isEmpty() ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }
}
