package io.github.yhavinga.regelspraak.transpiler.model;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;
import io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime;

import org.drools.model.DSL;
import org.drools.model.Global;
import org.drools.model.Model;
import org.drools.model.PatternDSL;
import org.drools.model.Rule;
import org.drools.model.Variable;
import org.drools.model.functions.Block2;
import org.drools.model.functions.Predicate1;
import org.drools.model.functions.Predicate2;
import org.drools.model.impl.ModelImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

        // the value to assign: a Function<bean, value> (Phase A reads only the fact — globals in the THEN are a later phase)
        Function<Object, Object> waarde = switch (r.soort()) {
            case KENMERKTOEKENNING -> bean -> Boolean.TRUE;                 // "is <kenmerk>" -> kenmerk == true
            case GELIJKSTELLING, BEREKENING, INITIALISATIE -> scalarWaarde(r.waarde());
            default -> null;
        };
        if (waarde == null) return skip("regel (waarde buiten Phase-A scope): " + r.naam());
        String doelProp = doel.attribuut();

        Variable sVar = DSL.declarationOf(factClass, "$subject");
        PatternDSL.PatternDef pattern = PatternDSL.pattern(sVar);

        if (r.voorwaarde() != null) {
            PatternDSL.PatternDef p2 = voorwaarde(r.voorwaarde(), pattern, doel.objecttype());
            if (p2 == null) return skip("regel (voorwaarde buiten Phase-A scope): " + r.naam());
            pattern = p2;
        }

        Block2<org.drools.model.Drools, Object> body = (drools, s) -> {
            RegelSpraakRuntime.set(s, doelProp, waarde.apply(s));
            drools.update(s);
        };
        // NO_LOOP is mandatory: the modify must not re-activate this rule (else fireAllRules infinite-loops).
        return PatternDSL.rule(r.naam())
                .attribute(Rule.Attribute.NO_LOOP, true)
                .build(pattern, DSL.on(sVar).execute(body));
    }

    /** Phase A: a single own-attribute comparison vs a numeric Parameter-global or Literal -> one expr constraint.
     *  Returns the augmented pattern, or null when the condition is outside Phase-A scope (caller skips the rule). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private PatternDSL.PatternDef voorwaarde(Conditie c, PatternDSL.PatternDef pattern, Objecttype subject) {
        if (!(c instanceof Conditie.Vergelijking v) || !(v.links() instanceof Expr.AttribuutRef la)
                || la.navigatieRol() != null) return null;
        java.util.function.BiPredicate<BigDecimal, BigDecimal> cmp = comparator(v.op());
        if (cmp == null) return null;
        String attr = la.attribuut();
        if (v.rechts() instanceof Expr.ParameterRef pr) {
            Global<BigDecimal> g = globals.get(ident(pr.parameter().naam()));
            if (g == null) return null;
            return pattern.expr("c_" + attr, (Variable) g,
                    (Predicate2) (s, gv) -> bothNonNull(num(s, attr), (BigDecimal) gv, cmp));
        }
        if (v.rechts() instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            BigDecimal rhs = bd(lit);
            if (rhs == null) return null;
            return pattern.expr("c_" + attr, (Predicate1) s -> bothNonNull(num(s, attr), rhs, cmp));
        }
        return null;
    }

    /** Phase A scalar value: Literal | own-attr read | a {@code +} of those (rsNul leegwaarde). null -> skip. */
    private Function<Object, Object> scalarWaarde(Expr e) {
        if (e instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            BigDecimal val = bd(lit);
            return val == null ? null : bean -> val;
        }
        if (e instanceof Expr.AttribuutRef a && a.navigatieRol() == null) {
            String attr = a.attribuut();
            return bean -> RegelSpraakRuntime.get(bean, attr);
        }
        if (e instanceof Expr.Binair b && b.op() == Expr.BinOp.PLUS) {
            Function<Object, Object> lf = scalarWaarde(b.links()), rf = scalarWaarde(b.rechts());
            if (lf == null || rf == null) return null;
            return bean -> RegelSpraakRuntime.rsNul(lf.apply(bean)).add(RegelSpraakRuntime.rsNul(rf.apply(bean)));
        }
        return null;
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
    /** A comparison constraint is unsatisfied when either operand is leeg (null) — matching the DRL/mvel
     *  constraint behaviour where a null operand simply does not match (no NPE, no spurious true). */
    private static boolean bothNonNull(BigDecimal a, BigDecimal b, java.util.function.BiPredicate<BigDecimal, BigDecimal> cmp) {
        return a != null && b != null && cmp.test(a, b);
    }
    private static java.util.function.BiPredicate<BigDecimal, BigDecimal> comparator(Conditie.Operator op) {
        return switch (op) {
            case KLEINER           -> (a, b) -> a.compareTo(b) < 0;
            case KLEINER_OF_GELIJK -> (a, b) -> a.compareTo(b) <= 0;
            case GROTER            -> (a, b) -> a.compareTo(b) > 0;
            case GROTER_OF_GELIJK  -> (a, b) -> a.compareTo(b) >= 0;
            case GELIJK            -> (a, b) -> a.compareTo(b) == 0;
            case ONGELIJK          -> (a, b) -> a.compareTo(b) != 0;
            default -> null;  // EERDER/LATER (datum) — later phase
        };
    }
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
