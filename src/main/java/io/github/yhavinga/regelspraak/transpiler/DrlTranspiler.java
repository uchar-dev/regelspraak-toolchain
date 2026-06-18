package io.github.yhavinga.regelspraak.transpiler;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The Drools back-end: it walks the resolved IR ({@link ResolvedRegel}) and emits
 * DRL text. Each assignment rule becomes one DRL rule — the §13.4.13 condition is
 * the "when" (a pattern over the target's object type with the condition as a
 * constraint), and the target-:=-value is the "then" (a modify-block setter).
 *
 * This is the first back-end increment: it generates faithful DRL for the
 * resolvable subset (a target object type, comparisons and arithmetic over the
 * subject's own attributes, parameters and literals) and emits an explicit,
 * clearly-marked placeholder for everything the resolver left unbound — navigation
 * to other objects, aggregations (which need accumulate), bounds and rounding. So
 * the DRL states its own coverage, exactly like the IR it is built from. Parameters
 * become globals. The mvel dialect is used so arithmetic and comparison read as
 * infix over BigDecimal.
 */
public final class DrlTranspiler {
    private static final String SUBJECT = "$subject";
    /** The package the generated DRL imports its fact classes from. The fact model
     *  (a POJO per objecttype, with the getters/setters this transpiler assumes) is
     *  the contract between the generated rules and the runtime. */
    public static final String FACT_PACKAGE = "io.github.yhavinga.regelspraak.facts";
    private final RegelSpraakModel model;
    private final StringBuilder out = new StringBuilder();
    // per-rule: accumulate patterns hoisted out of the value into the "when"
    private List<String> accumulates = new ArrayList<>();
    private int accCount = 0;

    public DrlTranspiler(RegelSpraakModel model) { this.model = model; }

    public static String transpile(RegelSpraakModel model) { return new DrlTranspiler(model).run(); }

    private String run() {
        out.append("package io.github.yhavinga.regelspraak.generated;\n\n");
        // Import the fact class for each object type so Drools resolves the patterns.
        for (Objecttype ot : model.objecttypen.values())
            out.append("import ").append(FACT_PACKAGE).append('.').append(ident(ot.naam, true)).append(";\n");
        if (!model.objecttypen.isEmpty()) out.append('\n');
        // Parameters become globals the runtime supplies.
        for (Parameter p : model.parameters.values())
            out.append("global ").append(javaType(p.datatype())).append(' ')
               .append(ident(p.naam(), false)).append(";\n");
        if (!model.parameters.isEmpty()) out.append('\n');
        // §4.2 version selection: a time-bounded rule version guards on the Rekendatum the
        // runtime supplies, so declare it as a global when any rule of any kind needs it.
        boolean tijdgebonden =
                   model.resolvedRegels.stream().anyMatch(r -> tijdgebondenGeldigheid(r.geldigheid()))
                || model.objectCreaties.stream().anyMatch(o -> tijdgebondenGeldigheid(o.geldigheid()))
                || model.verdelingen.stream().anyMatch(v -> tijdgebondenGeldigheid(v.geldigheid()))
                || model.consistenties.stream().anyMatch(c -> tijdgebondenGeldigheid(c.geldigheid()));
        if (tijdgebonden) out.append("global java.time.LocalDate rekendatum;\n\n");

        for (ResolvedRegel r : model.resolvedRegels) regel(r);
        for (io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie oc : model.objectCreaties) objectCreatie(oc);
        for (io.github.yhavinga.regelspraak.ir.ResolvedVerdeling v : model.verdelingen) verdeling(v);
        for (io.github.yhavinga.regelspraak.ir.ResolvedConsistentie c : model.consistenties) consistentie(c);
        return out.toString();
    }

    /** §13.4.8 consistentieregel. The assertion MUST hold, so the rule fires on its
     *  VIOLATION — the negated condition — and inserts an Inconsistentie carrying the
     *  rule name and the offending subject (§9.5: "inconsistent" as the result, usable by
     *  other rules). A condition whose negation is not expressible (compound/predicate
     *  beyond leeg/gevuld) or an unresolved subject becomes an explicit marker. */
    private void consistentie(io.github.yhavinga.regelspraak.ir.ResolvedConsistentie c) {
        accumulates = new ArrayList<>();
        accCount = 0;
        Objecttype subject = c.subject();
        String constraint = subject != null ? schendingConstraint(c.assertie(), subject) : null;
        out.append("rule \"").append(escapeRegelNaam(c.naam())).append("\"\n    dialect \"mvel\"\n    no-loop true\nwhen\n");
        if (constraint == null) {
            out.append("    // onopgelost consistentieregel: ").append(c.bron()).append("\n    eval(true)\nthen\nend\n\n");
            return;
        }
        out.append("    ").append(SUBJECT).append(" : ").append(ident(subject.naam, true))
           .append("( ").append(constraint).append(" )\n");
        for (String acc : accumulates) out.append("    ").append(acc).append('\n');
        if (tijdgebondenGeldigheid(c.geldigheid())) out.append("    ").append(geldigheidGuard(c.geldigheid())).append('\n');
        out.append("then\n    insert(new ").append(FACT_PACKAGE).append(".Inconsistentie(\"")
           .append(escapeRegelNaam(c.naam())).append("\", ").append(SUBJECT).append("));\nend\n\n");
    }

    /** The "when" constraint that detects a VIOLATION of the assertion. An ordering or
     *  date comparison is onwaar — hence inconsistent (§9.5) — not only when the
     *  complement holds but also when EITHER operand is empty (the typeringen leegwaarde
     *  tables: a leeg operand makes the comparison onwaar). The algebraic complement is
     *  likewise onwaar on null, so those operands are guarded explicitly. gelijk/ongelijk
     *  and leeg/gevuld use the plain negation (their empty cells are complementary).
     *  Returns null when no faithful negation exists — the caller marks the rule. */
    private String schendingConstraint(Conditie assertie, Objecttype subject) {
        if (assertie instanceof Conditie.Vergelijking v && ordeningOfDatum(v.op())) {
            String l = operand(v.links(), subject), r = operand(v.rechts(), subject);
            // An un-evaluable operand (a /* TODO */ marker) must NOT be wrapped in this
            // null-guard: the guard's "<marker> == null" reads as "null == null" — always
            // true — so the rule would flag EVERY instance inconsistent though its criterion
            // was never evaluated (§9.5: inconsistent only on a real violation). Drop to the
            // no-op path instead, like an assertion with no faithful negation.
            if (bevatMarker(l) || bevatMarker(r)) return null;
            String complement = conditie(new Conditie.Vergelijking(flip(v.op()), v.links(), v.rechts(), v.bron()), subject);
            return "(" + l + " == null || " + r + " == null || " + complement + ")";
        }
        Conditie schending = negeer(assertie);
        if (schending == null) return null;
        // Same hazard via the negation: a gevuld→leeg predicate over a marker emits
        // "<marker> == null" (always true). An un-evaluable criterion flags nothing.
        String c = conditie(schending, subject);
        return bevatMarker(c) ? null : c;
    }

    /** Does a lowered constraint string carry an unresolved-reference marker? Such a fragment
     *  is not a usable boolean — wrapping it in a null-guard or negation flips it to a constant. */
    private static boolean bevatMarker(String s) { return s.contains("/* TODO"); }

    private static boolean ordeningOfDatum(Conditie.Operator op) {
        return switch (op) {
            case KLEINER, KLEINER_OF_GELIJK, GROTER, GROTER_OF_GELIJK,
                 EERDER, EERDER_OF_GELIJK, LATER, LATER_OF_GELIJK -> true;
            case GELIJK, ONGELIJK -> false;
        };
    }

    /** A rule name is free text (§13.2.8) and may contain a double quote; escape it so
     *  the DRL {@code rule "…"} header and the Inconsistentie string literal stay
     *  well-formed (an unescaped quote fails the whole Drools package). */
    private static String escapeRegelNaam(String naam) { return naam.replace("\"", "'"); }

    /** The §4.2 validity guard: an eval over the Rekendatum global that fires only inside the
     *  version's window. Null-guarded so an unset Rekendatum simply doesn't match (no NPE), and
     *  the inclusive bounds use isBefore/isAfter negations (LocalDate has no &lt;= operator). */
    private static String geldigheidGuard(io.github.yhavinga.regelspraak.ir.Geldigheid g) {
        StringJoiner sj = new StringJoiner(" && ", "eval( ", " )");
        sj.add("rekendatum != null");
        if (g.vanaf() != null) sj.add("!rekendatum.isBefore(" + datumLit(g.vanaf()) + ")");
        if (g.tot() != null)   sj.add("!rekendatum.isAfter(" + datumLit(g.tot()) + ")");
        return sj.toString();
    }
    private static String datumLit(java.time.LocalDate d) {
        return "java.time.LocalDate.of(" + d.getYear() + ", " + d.getMonthValue() + ", " + d.getDayOfMonth() + ")";
    }
    private static boolean tijdgebondenGeldigheid(io.github.yhavinga.regelspraak.ir.Geldigheid g) {
        return g != null && !g.altijd();
    }

    /** The negation of a consistency assertion — what makes the rule fire (the
     *  violation). A comparison flips to its complement; an emptiness predicate flips
     *  leeg↔gevuld; anything else has no clean single-constraint negation here and yields
     *  null so the caller marks the rule rather than emit a wrong trigger. */
    private static Conditie negeer(Conditie c) {
        if (c instanceof Conditie.Vergelijking v)
            return new Conditie.Vergelijking(flip(v.op()), v.links(), v.rechts(), v.bron());
        if (c instanceof Conditie.Predicaat p) {
            Conditie.Predicaatsoort neg = switch (p.soort()) {
                case LEEG -> Conditie.Predicaatsoort.GEVULD;
                case GEVULD -> Conditie.Predicaatsoort.LEEG;
                default -> null;
            };
            return neg != null ? new Conditie.Predicaat(neg, p.operand(), p.bron()) : null;
        }
        return null;
    }

    private static Conditie.Operator flip(Conditie.Operator op) {
        return switch (op) {
            case GELIJK -> Conditie.Operator.ONGELIJK;
            case ONGELIJK -> Conditie.Operator.GELIJK;
            case KLEINER -> Conditie.Operator.GROTER_OF_GELIJK;
            case KLEINER_OF_GELIJK -> Conditie.Operator.GROTER;
            case GROTER -> Conditie.Operator.KLEINER_OF_GELIJK;
            case GROTER_OF_GELIJK -> Conditie.Operator.KLEINER;
            case EERDER -> Conditie.Operator.LATER_OF_GELIJK;
            case EERDER_OF_GELIJK -> Conditie.Operator.LATER;
            case LATER -> Conditie.Operator.EERDER_OF_GELIJK;
            case LATER_OF_GELIJK -> Conditie.Operator.EERDER;
        };
    }

    /** §13.4.10 verdeling. Equal shares is a real rule: bind the source, count the
     *  recipients (joined to the source), and give each recipient total/count. The
     *  proportional and group/remainder methods are multi-step allocations with no
     *  single-rule form, so they are emitted as an explicit marker. */
    private void verdeling(io.github.yhavinga.regelspraak.ir.ResolvedVerdeling v) {
        Objecttype bron = v.bronType(), doel = v.doelType();
        String join = bron != null && doel != null ? model.verbindendeRol(doel, bron) : null;
        boolean kan = v.methode() == io.github.yhavinga.regelspraak.ir.ResolvedVerdeling.Methode.GELIJKE_DELEN
                && join != null;
        out.append("rule \"").append(escapeRegelNaam(v.naam())).append("\"\n    dialect \"mvel\"\n    no-loop true\nwhen\n");
        if (!kan) {
            out.append("    // onopgelost verdeling (")
               .append(bron == null || doel == null ? "referentie" : v.methode().name().toLowerCase())
               .append("): ").append(v.bronAttribuut()).append(" over ").append(v.doelAttribuut()).append('\n');
            out.append("    eval(true)\nthen\nend\n\n");
            return;
        }
        String j = ident(join, false) + " == $src";
        out.append("    $src : ").append(ident(bron.naam, true)).append("(  )\n");
        out.append("    accumulate( $c : ").append(ident(doel.naam, true)).append("( ").append(j)
           .append(" ) ; $n : count($c) )\n");
        out.append("    $r : ").append(ident(doel.naam, true)).append("( ").append(j).append(" )\n");
        if (tijdgebondenGeldigheid(v.geldigheid())) out.append("    ").append(geldigheidGuard(v.geldigheid())).append('\n');
        out.append("then\n    modify($r) {\n        ").append(setter(v.doelAttribuut()))
           .append("( $src.").append(getter(v.bronAttribuut()))
           .append("().divide($n, java.math.MathContext.DECIMAL64) )\n    }\nend\n\n"); // safe division
    }

    /** §13.4.6 objectcreatie -> a rule that inserts a new fact, sets its attributes,
     *  and links it back to the subject through the feittype role. */
    private void objectCreatie(io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie oc) {
        accumulates = new ArrayList<>();
        accCount = 0;
        Objecttype nieuw = oc.nieuwType(), subject = oc.subject();
        // value expressions may carry aggregations -> collected into the "when"
        java.util.List<String> setters = new ArrayList<>();
        for (var t : oc.toekenningen())
            setters.add("    $new." + setter(t.attribuut()) + "( " + waarde(t.waarde(), subject) + " );");

        out.append("rule \"").append(escapeRegelNaam(oc.naam())).append("\"\n    dialect \"mvel\"\n    no-loop true\nwhen\n");
        if (subject == null || nieuw == null) {
            out.append("    // onopgelost objectcreatie: ").append(oc.naam()).append("\n    eval(true)\n");
            out.append("then\nend\n\n");
            return;
        }
        out.append("    ").append(SUBJECT).append(" : ").append(ident(subject.naam, true))
           .append("( ").append(oc.voorwaarde() != null ? conditie(oc.voorwaarde(), subject) : "").append(" )\n");
        for (String acc : accumulates) out.append("    ").append(acc).append('\n');
        if (tijdgebondenGeldigheid(oc.geldigheid())) out.append("    ").append(geldigheidGuard(oc.geldigheid())).append('\n');
        out.append("then\n");
        out.append("    ").append(ident(nieuw.naam, true)).append(" $new = new ")
           .append(ident(nieuw.naam, true)).append("();\n");
        for (String s : setters) out.append(s).append('\n');
        String rolNaarSubject = model.verbindendeRol(nieuw, subject); // field on the new object -> subject
        if (rolNaarSubject != null)
            out.append("    $new.").append(setter(rolNaarSubject)).append("( ").append(SUBJECT).append(" );\n");
        else
            out.append("    // onopgelost terugkoppeling naar ").append(subject.naam).append('\n');
        out.append("    insert($new);\n");
        out.append("end\n\n");
    }

    private void regel(ResolvedRegel r) {
        accumulates = new ArrayList<>();
        accCount = 0;
        Objecttype subject = r.doel() instanceof Expr.AttribuutRef a ? a.objecttype() : null;
        // Translate the value first: any aggregation registers an accumulate pattern
        // for the "when" and yields the bound result variable used here in the "then".
        String valueStr = subject != null && r.doel() instanceof Expr.AttribuutRef
                ? waarde(r.waarde(), subject) : null;

        out.append("rule \"").append(escapeRegelNaam(r.naam())).append("\"\n");
        out.append("    dialect \"mvel\"\n");
        // A forward calculation should not retrigger itself on its own modify; it
        // still recomputes when another rule changes the facts it reads.
        out.append("    no-loop true\n");
        out.append("when\n");
        if (subject == null) {
            out.append("    // onopgelost doel-objecttype: ").append(r.doel().bron()).append('\n');
            out.append("    eval(true)\n");
        } else {
            String constraint = r.voorwaarde() != null ? conditie(r.voorwaarde(), subject) : "";
            out.append("    ").append(SUBJECT).append(" : ").append(ident(subject.naam, true))
               .append("( ").append(constraint).append(" )\n");
            for (String acc : accumulates) out.append("    ").append(acc).append('\n');
            // §4.2: a time-bounded version only fires when the Rekendatum is in its window.
            if (r.geldigheid() != null && !r.geldigheid().altijd())
                out.append("    ").append(geldigheidGuard(r.geldigheid())).append('\n');
        }
        out.append("then\n");
        if (valueStr != null && r.doel() instanceof Expr.AttribuutRef a) {
            // restate the value in the target's unit (§4); §6.1.2 forbids AUTO-rounding —
            // if the result has too many decimals the author must add an explicit afronding.
            if (r.waarde().type().isNumeriek())
                valueStr = omrekenWaarde(valueStr, r.waarde().type(), a.type());
            out.append("    modify(").append(SUBJECT).append(") {\n");
            out.append("        ").append(setter(a.attribuut())).append("( ").append(valueStr).append(" )\n");
            out.append("    }\n");
        } else {
            out.append("    // onopgelost doel: ").append(r.doel().bron()).append('\n');
        }
        out.append("end\n\n");
    }

    /** Sentinel from {@link #naviRol}: the reference is the subject's own attribute. */
    private static final String EIGEN = "";

    /**
     * How to read an attribute reference relative to the rule subject: the reference
     * FIELD to navigate through, {@link #EIGEN} for the subject's own attribute, or
     * null when it cannot be resolved (caller emits a marker).
     *
     * When the source named the role (navigatieRol), a by-name resolution is tried
     * first — it disambiguates an object-type pair joined by more than one feittype/role
     * AND covers a wederkerig self-relation ("zijn partner", where the navigated type
     * equals the subject's). But a definite back-reference to the subject by its OWN
     * role name ("de reis", where the subject already fills the role reis) names the
     * same instance (§5.5.3), so when the by-name lookup finds nothing we fall through:
     * a same-type reference reads as the subject's own attribute, otherwise the by-pair
     * search applies. Returning null only when all three fail keeps an honestly
     * unresolved reference a marker rather than a wrong own-attribute read.
     */
    private String naviRol(Objecttype subject, Expr.AttribuutRef a) {
        if (a.navigatieRol() != null) {
            String rol = model.verbindendeRolViaNaam(subject, a.objecttype(), a.navigatieRol());
            if (rol != null) return rol;
        }
        if (a.objecttype() == subject) return EIGEN;
        return model.verbindendeRol(subject, a.objecttype());
    }

    /** True when the reference is bound to a known object type that does NOT declare the
     *  attribute (nor a kenmerk by that name). Emitting `getX()` for it would call a method
     *  the fact has no declaration for — uncompilable DRL — so the caller emits a marker
     *  instead. (A null object type is already handled by {@link #naviRol} returning null.) */
    private static boolean attribuutOntbreekt(Expr.AttribuutRef a) {
        return a.objecttype() != null && !a.objecttype().heeftLid(a.attribuut());
    }

    // ---------------- value expression ("then" side: getters) ----------------
    private String waarde(Expr e, Objecttype subject) {
        if (e instanceof Expr.Literal lit)      return literal(lit);
        if (e instanceof Expr.ParameterRef p)   return ident(p.parameter().naam(), false);
        if (e instanceof Expr.AttribuutRef a) {
            if (attribuutOntbreekt(a)) return todo("navigatie", a.bron());
            String rol = naviRol(subject, a);
            if (EIGEN.equals(rol)) return SUBJECT + "." + getter(a.attribuut()) + "()";
            if (rol != null)       return SUBJECT + "." + getter(rol) + "()." + getter(a.attribuut()) + "()";
            return todo("navigatie", a.bron());
        }
        if (e instanceof Expr.Binair b) {
            if (b.op() == Expr.BinOp.TOT_DE_MACHT)
                return "Math.pow(" + waarde(b.links(), subject) + ", " + waarde(b.rechts(), subject) + ")";
            if (b.op() == Expr.BinOp.PERCENTAGE_VAN)
                return "(" + waarde(b.links(), subject) + " / 100 * " + waarde(b.rechts(), subject) + ")";
            if (b.op() == Expr.BinOp.GEDEELD_DOOR || b.op() == Expr.BinOp.GEDEELD_DOOR_ABS)
                return "(" + waarde(b.links(), subject) + ").divide(" + waarde(b.rechts(), subject)
                        + ", java.math.MathContext.DECIMAL64)"; // safe: no non-terminating-quotient throw
            if (b.op() == Expr.BinOp.MAAL)  // §6.4: units multiply, operands are NOT converted to match
                return "(" + waarde(b.links(), subject) + " * " + waarde(b.rechts(), subject) + ")";
            // §4: restate the right operand in the left operand's unit before +/- so a
            // convertible-but-different unit (mnd vs jr) is scaled, not added raw.
            String rechts = omrekenWaarde(waarde(b.rechts(), subject), b.rechts().type(), b.links().type());
            return "(" + waarde(b.links(), subject) + " " + infix(b.op()) + " " + rechts + ")";
        }
        if (e instanceof Expr.Afgerond af) {
            String mode = switch (af.modus()) {
                case NAAR_BENEDEN -> "FLOOR"; case NAAR_BOVEN -> "CEILING";
                case REKENKUNDIG -> "HALF_UP"; case RICHTING_NUL -> "DOWN"; case WEG_VAN_NUL -> "UP";
            };
            return "(" + waarde(af.basis(), subject) + ").setScale(" + af.decimalen()
                    + ", java.math.RoundingMode." + mode + ")";
        }
        if (e instanceof Expr.Begrensd bg) {
            // §4/§6.1.4: a convertible-but-different bound unit (a jr bound on a mnd basis) is
            // restated in the basis unit before the clamp compares magnitudes, exactly as the
            // +/- path does. No-op when the units match or are absent.
            String r = waarde(bg.basis(), subject);
            Datatype bt = bg.basis().type();
            if (bg.minimum() != null) {
                String m = omrekenWaarde(waarde(bg.minimum(), subject), bg.minimum().type(), bt);
                r = "(" + r + " < " + m + " ? " + m + " : " + r + ")";
            }
            if (bg.maximum() != null) {
                String m = omrekenWaarde(waarde(bg.maximum(), subject), bg.maximum().type(), bt);
                r = "(" + r + " > " + m + " ? " + m + " : " + r + ")";
            }
            return r;
        }
        if (e instanceof Expr.Constante c)      return todo("constante", c.naam());
        if (e instanceof Expr.Functie f) {
            String concat = concatenatieAggregatie(f, subject); // pairwise min/max over listed operands
            if (concat != null) return concat;
            String var = accumulate(f, subject);              // hoists an accumulate into the "when"
            return var != null ? var : todo("aggregatie " + f.kind(), f.bron());
        }
        return todo("expressie", e.bron());
    }

    /**
     * §4.12-4.15 / §5.8.3-5.8.4 concatenation-of-expressies form: "de minimale/maximale/eerste/
     * laatste van A en B [en C …]" over two-or-more LISTED scalar operands — distinct from the
     * single-operand collection aggregation (handled by {@link #accumulate}, which ranges over
     * instances). Lowered as a left fold of ternaries: min/eerste keeps the smaller/earlier, max/
     * laatste the larger/later. Numeric operands are restated in the first operand's unit before
     * comparing (the result unit "Eenheid1", Tabel 22/23), exactly as +/- and begrenzing do;
     * dates lower to Comparable LocalDate(Time), which mvel's &lt;/&gt; compare directly.
     *
     * <p>§4.12-4.15 ("leegwaarden … worden niet meegeteld; alle leeg → leeg"): each fold step
     * SKIPS an empty (null) operand and yields empty only when every operand is empty — a bare
     * comparison would poison the whole result to null, because mvel evaluates "null &lt; x" as
     * false and so selects the empty branch. The guard re-references each operand by name (a
     * side-effect-free getter), so the step is O(operands) textually but correct; the corpus only
     * ever lists two operands. Returns null for the single-operand collection form and for
     * non-min/max/eerste/laatste kinds, and falls through to the marker path on an unmappable operand.
     */
    private String concatenatieAggregatie(Expr.Functie f, Objecttype subject) {
        String cmp = switch (f.kind()) {
            case MINIMUM, EERSTE  -> "<";   // keep the smaller / earlier
            case MAXIMUM, LAATSTE -> ">";   // keep the larger / later
            default -> null;
        };
        if (cmp == null || f.argumenten().size() < 2) return null;
        Datatype doel = f.argumenten().get(0).type();          // Eenheid1: result carries the first operand's unit
        String acc = waarde(f.argumenten().get(0), subject);
        if (bevatMarker(acc)) return null;
        for (int i = 1; i < f.argumenten().size(); i++) {
            Expr arg = f.argumenten().get(i);
            String x = omrekenWaarde(waarde(arg, subject), arg.type(), doel);
            if (bevatMarker(x)) return null;
            // null-skipping: acc empty → take x; x empty → keep acc; else compare. All-empty stays null.
            acc = "(" + acc + " == null ? " + x + " : (" + x + " == null ? " + acc
                    + " : (" + acc + " " + cmp + " " + x + " ? " + acc + " : " + x + ")))";
        }
        return acc;
    }

    /**
     * Turn an aggregation into a Drools accumulate: bind a source pattern over the
     * element object type and an accumulate function (sum/count/min/max), register it
     * for the "when", and return the result variable to drop into the value. The
     * element scope — which instances relate to the subject — is left as a marker
     * here; navigation joins fill it. Returns null for aggregations not yet mappable.
     */
    private String accumulate(Expr.Functie f, Objecttype subject) {
        Objecttype elem = f.elementObjecttype();
        String fn = switch (f.kind()) {
            case SOM, TOTAAL -> "sum";
            case AANTAL -> "count";
            case MINIMUM -> "min";
            case MAXIMUM -> "max";
            default -> null;
        };
        if (elem == null || fn == null) return null;
        String scope = scope(elem, subject, f);
        if (bevatMarker(scope)) return null;   // unresolvable join -> marker, never a global aggregate
        int n = ++accCount;
        String ev = "$e" + n, var = "$acc" + n;
        Expr arg = f.argumenten().isEmpty() ? null : f.argumenten().get(0);

        // §4.10/§5.8.2 sommatie ("som van"): an empty sum (no non-empty contributors) is a
        // leegwaarde, not 0 — unless "of … als die er niet zijn" supplies a fallback. Drools
        // sum() can't tell an empty sum from a real 0, so count the non-empty contributors and
        // branch on it. TOTAAL ("totaal van") is excluded: §4.20 makes its empty result an
        // unconditional 0, which the plain sum() over an empty accumulate already yields.
        // Needs a resolved join and a plain attribute argument; otherwise fall through.
        if (f.kind() == Expr.FunctieKind.SOM
                && arg instanceof Expr.AttribuutRef a && !scope.startsWith("/*")) {
            String cnt = "$n" + n;
            accumulates.add("accumulate( " + ev + " : " + ident(elem.naam, true)
                    + "( " + scope + ", " + ident(a.attribuut(), false) + " != null ) ; "
                    + cnt + " : count(" + ev + "), " + var + " : sum( " + ev + "." + getter(a.attribuut()) + "() ) )");
            String fallback = f.leegwaarde() != null ? waarde(f.leegwaarde(), subject) : "null";
            return "(" + cnt + " == 0 ? " + fallback + " : " + var + ")";
        }

        String elementExpr = ev;
        if (!fn.equals("count") && arg instanceof Expr.AttribuutRef a)
            elementExpr = ev + "." + getter(a.attribuut()) + "()";
        accumulates.add("accumulate( " + ev + " : " + ident(elem.naam, true)
                + "( " + scope + " ) ; " + var + " : " + fn + "( " + elementExpr + " ) )");
        return var;
    }

    /** The element scope: the join binding the element to the subject ("the passagiers
     *  OF this reis" -> reis == $subject), if the connecting feittype is unambiguous;
     *  otherwise the source phrase as a marker. */
    private String scope(Objecttype elem, Objecttype subject, Expr.Functie f) {
        Expr.AttribuutRef arg = !f.argumenten().isEmpty() && f.argumenten().get(0) instanceof Expr.AttribuutRef a ? a : null;
        // A count names its element role explicitly ("het aantal bestemming …"), so when more than
        // one feittype joins the (element, subject) pair the written name picks the unique join the
        // by-pair search cannot. Try that first, then fall back to the by-pair role.
        String rol = null;
        if (subject != null && arg != null && f.kind() == Expr.FunctieKind.AANTAL)
            rol = model.verbindendeRolViaElementnaam(subject, arg.attribuut());
        if (rol == null && subject != null) rol = model.verbindendeRol(elem, subject);
        if (rol != null) return ident(rol, false) + " == " + SUBJECT;
        // No join resolvable: emit a TODO marker, not a bare "/* van: */" comment. The marker is
        // caught by bevatMarker so accumulate() returns null and the aggregation becomes a visible
        // no-op — never a silently-wrong count over EVERY instance (§5.8.1: a count ranges over the
        // subject's related instances). Consistent with the resolver's "unresolvable -> marker" rule.
        return todo("aggregatie-bereik", arg != null ? arg.onderwerpBron() : f.bron());
    }

    // ---------------- condition ("when" side: bare field constraints) ----------------
    private String conditie(Conditie c, Objecttype subject) {
        if (c instanceof Conditie.Vergelijking v) {
            // §5.1 Tabel 32: a comparison across convertible-but-different units restates
            // the right operand in the left's unit first ("24 mnd > 2 jr", not "24 > 2").
            String rechts = omreken(operand(v.rechts(), subject), v.rechts().type(), v.links().type());
            return operand(v.links(), subject) + " " + drlOp(v.op()) + " " + rechts;
        }
        if (c instanceof Conditie.Predicaat p) {
            String o = operand(p.operand(), subject);
            return switch (p.soort()) {
                case LEEG   -> o + " == null";
                case GEVULD -> o + " != null";
                default     -> todo("predicaat " + p.soort(), p.bron());
            };
        }
        if (c instanceof Conditie.Samengesteld s) {
            List<String> delen = new ArrayList<>();
            for (Conditie deel : s.onderdelen()) delen.add("(" + conditie(deel, subject) + ")");
            if (delen.isEmpty()) return "true";
            // §13.4.14.4: alle = all hold (AND); geen = none holds (!OR); the counting
            // quantifiers express "k of the criteria hold" purely in booleans (a Drools
            // pattern constraint accepts &&/||/! but not arithmetic over ternaries), via the
            // OR of every k-subset (minstens): at-least-N directly, at-most-N as not-at-
            // least-(N+1), exactly-N as the conjunction of the two.
            int n = s.drempel();
            return switch (s.kwantor()) {
                case GEEN        -> "!(" + String.join(" || ", delen) + ")";
                case TEN_MINSTE  -> minstens(delen, n);
                case TEN_HOOGSTE -> "!(" + minstens(delen, n + 1) + ")";
                case PRECIES     -> "(" + minstens(delen, n) + " && !(" + minstens(delen, n + 1) + "))";
                default          -> String.join(" && ", delen); // ALLE / DE
            };
        }
        return todo("voorwaarde", c.bron());
    }

    /** A boolean expression true when AT LEAST {@code k} of {@code delen} hold: the OR over
     *  every k-subset, each subset AND-ed. k&lt;=0 is always true; k&gt;size is never. The
     *  bullet lists are small, so the C(size,k) expansion stays modest. */
    private static String minstens(List<String> delen, int k) {
        if (k <= 0) return "true";
        if (k > delen.size()) return "false";
        List<String> combos = new ArrayList<>();
        combineer(delen, k, 0, new ArrayList<>(), combos);
        return "(" + String.join(" || ", combos) + ")";
    }
    private static void combineer(List<String> delen, int k, int start, List<String> huidig, List<String> uit) {
        if (huidig.size() == k) { uit.add("(" + String.join(" && ", huidig) + ")"); return; }
        for (int i = start; i <= delen.size() - (k - huidig.size()); i++) {
            huidig.add(delen.get(i));
            combineer(delen, k, i + 1, huidig, uit);
            huidig.remove(huidig.size() - 1);
        }
    }

    /** A condition operand: the subject's own attribute reads as a bare field name; a
     *  related object's attribute reads as a property path ("reis.vluchtdatum"). */
    private String operand(Expr e, Objecttype subject) {
        if (e instanceof Expr.AttribuutRef a) {
            if (attribuutOntbreekt(a)) return todo("navigatie", a.bron());
            String rol = naviRol(subject, a);
            if (EIGEN.equals(rol)) return ident(a.attribuut(), false);
            if (rol != null)       return ident(rol, false) + "." + ident(a.attribuut(), false);
            return todo("navigatie", a.bron());
        }
        if (e instanceof Expr.ParameterRef p) return ident(p.parameter().naam(), false);
        if (e instanceof Expr.Literal lit)    return literal(lit);
        return todo("operand", e.bron());
    }

    // ---------------- leaves & helpers ----------------
    private static String literal(Expr.Literal lit) {
        Datatype t = lit.type();
        if (t.isTekst()) return "\"" + lit.waarde().replace("\"", "") + "\"";
        if (t instanceof Datatype.Booleaan) return lit.waarde().toLowerCase().contains("waar")
                && !lit.waarde().toLowerCase().contains("onwaar") ? "true" : "false";
        if (t.isDatum()) { // "dd. d-m-yyyy [time]" -> LocalDate.of(y, m, d)
            java.util.regex.Matcher d = java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})-(\\d{4})").matcher(lit.waarde());
            return d.find() ? "java.time.LocalDate.of(" + d.group(3) + ", " + d.group(2) + ", " + d.group(1) + ")"
                    : todo("datumliteral", lit.waarde());
        }
        // numeric (possibly "0 €"): the leading number AS A BigDecimal, so arithmetic,
        // unit scaling (.multiply) and rounding (.setScale) compile in mvel.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)?").matcher(lit.waarde());
        return m.find() ? "new java.math.BigDecimal(\"" + m.group().replace(',', '.') + "\")" : todo("literal", lit.waarde());
    }

    /** Wrap an emitted value in a unit conversion when its unit differs from (but is
     *  convertible to) the target unit; otherwise return it unchanged (§13.3.5.2).
     *  Never wraps an unlowered placeholder — that would emit (null).multiply(...). */
    private static String omreken(String expr, Datatype van, Datatype naar) {
        if (expr.contains("/* TODO")) return expr; // placeholder value — do not wrap (would NPE)
        io.github.yhavinga.regelspraak.model.Eenheid ev = van.eenheidOf(), en = naar.eenheidOf();
        if (ev == null || en == null || !ev.convertibleNaar(en) || !ev.vereistOmrekening(en)) return expr;
        return "(" + expr + ").multiply(new java.math.BigDecimal(\""
                + ev.omrekenfactorNaar(en).toPlainString() + "\"))";
    }

    /** Unit conversion on the VALUE side, null-safe: a leegwaarde (an empty sommatie has
     *  no fallback) converts to a leegwaarde rather than an NPE on .multiply. Only for the
     *  "then"/value side — a Drools pattern constraint cannot carry the {@code == null}
     *  guard (it reads as a nested constraint), and constraint operands are plain
     *  attributes, so {@link #omreken} stays the constraint form. */
    private static String omrekenWaarde(String expr, Datatype van, Datatype naar) {
        if (expr.contains("/* TODO")) return expr;
        io.github.yhavinga.regelspraak.model.Eenheid ev = van.eenheidOf(), en = naar.eenheidOf();
        if (ev == null || en == null || !ev.convertibleNaar(en) || !ev.vereistOmrekening(en)) return expr;
        String factor = ev.omrekenfactorNaar(en).toPlainString();
        return "(" + expr + " == null ? null : (" + expr + ").multiply(new java.math.BigDecimal(\"" + factor + "\")))";
    }

    private static String infix(Expr.BinOp op) {
        return switch (op) {
            case PLUS -> "+";
            case MIN, VERMINDERD_MET -> "-";
            case MAAL -> "*";
            case GEDEELD_DOOR, GEDEELD_DOOR_ABS -> "/";
            default -> "+"; // TOT_DE_MACHT / PERCENTAGE_VAN handled by caller
        };
    }

    private static String drlOp(Conditie.Operator op) {
        return switch (op) {
            case KLEINER, EERDER -> "<";
            case KLEINER_OF_GELIJK, EERDER_OF_GELIJK -> "<=";
            case GROTER, LATER -> ">";
            case GROTER_OF_GELIJK, LATER_OF_GELIJK -> ">=";
            case GELIJK -> "==";
            case ONGELIJK -> "!=";
        };
    }

    private static String javaType(Datatype t) {
        if (t.isPercentage() || t.isNumeriek()) return "java.math.BigDecimal";
        if (t.isTekst()) return "String";
        if (t instanceof Datatype.Booleaan) return "Boolean";
        if (t instanceof Datatype.DatumInDagen) return "java.time.LocalDate";
        if (t instanceof Datatype.DatumTijd) return "java.time.LocalDateTime";
        return "Object";
    }

    private static String getter(String attribuut) { return "get" + ident(attribuut, true); }
    private static String setter(String attribuut) { return "set" + ident(attribuut, true); }
    private static String todo(String wat, String bron) { return "/* TODO " + wat + ": " + bron + " */ null"; }

    /** A RegelSpraak name → a Java identifier (camel- or Pascal-case over its words). */
    static String ident(String s, boolean upperFirst) {
        String[] delen = s.trim().split("[^\\p{L}\\p{N}]+");
        StringBuilder b = new StringBuilder();
        for (String d : delen) {
            if (d.isEmpty()) continue;
            boolean hoofd = upperFirst || b.length() > 0;
            b.append(hoofd ? Character.toUpperCase(d.charAt(0)) : Character.toLowerCase(d.charAt(0)));
            b.append(d.substring(1));
        }
        return b.isEmpty() ? "x" : b.toString();
    }
}
