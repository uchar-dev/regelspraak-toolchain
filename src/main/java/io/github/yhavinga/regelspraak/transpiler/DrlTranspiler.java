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
            String complement = conditie(new Conditie.Vergelijking(flip(v.op()), v.links(), v.rechts(), v.bron()), subject);
            return "(" + l + " == null || " + r + " == null || " + complement + ")";
        }
        Conditie schending = negeer(assertie);
        return schending != null ? conditie(schending, subject) : null;
    }

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

    // ---------------- value expression ("then" side: getters) ----------------
    private String waarde(Expr e, Objecttype subject) {
        if (e instanceof Expr.Literal lit)      return literal(lit);
        if (e instanceof Expr.ParameterRef p)   return ident(p.parameter().naam(), false);
        if (e instanceof Expr.AttribuutRef a) {
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
            String r = waarde(bg.basis(), subject);
            if (bg.minimum() != null) { String m = waarde(bg.minimum(), subject); r = "(" + r + " < " + m + " ? " + m + " : " + r + ")"; }
            if (bg.maximum() != null) { String m = waarde(bg.maximum(), subject); r = "(" + r + " > " + m + " ? " + m + " : " + r + ")"; }
            return r;
        }
        if (e instanceof Expr.Constante c)      return todo("constante", c.naam());
        if (e instanceof Expr.Functie f) {
            String var = accumulate(f, subject);              // hoists an accumulate into the "when"
            return var != null ? var : todo("aggregatie " + f.kind(), f.bron());
        }
        return todo("expressie", e.bron());
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
        int n = ++accCount;
        String ev = "$e" + n, var = "$acc" + n;
        String scope = scope(elem, subject, f);
        Expr arg = f.argumenten().isEmpty() ? null : f.argumenten().get(0);

        // §5.8.2 sommatie: an empty sum (no non-empty contributors) is a leegwaarde, not 0 —
        // unless "of … als die er niet zijn" supplies a fallback. Drools sum() can't tell an
        // empty sum from a real 0, so count the non-empty contributors and branch on it.
        // Needs a resolved join and a plain attribute argument; otherwise fall through.
        if ((f.kind() == Expr.FunctieKind.SOM || f.kind() == Expr.FunctieKind.TOTAAL)
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
        String rol = subject != null ? model.verbindendeRol(elem, subject) : null; // field on element -> subject
        if (rol != null) return ident(rol, false) + " == " + SUBJECT;
        String bron = !f.argumenten().isEmpty() && f.argumenten().get(0) instanceof Expr.AttribuutRef a
                ? a.onderwerpBron() : f.bron();
        return "/* van: " + bron + " */";
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
            String join = s.kwantor() == Conditie.Kwantor.GEEN ? " || " : " && ";
            StringJoiner sj = new StringJoiner(join);
            for (Conditie deel : s.onderdelen()) sj.add("(" + conditie(deel, subject) + ")");
            String body = sj.toString().isEmpty() ? "true" : sj.toString();
            return s.kwantor() == Conditie.Kwantor.GEEN ? "!(" + body + ")" : body;
        }
        return todo("voorwaarde", c.bron());
    }

    /** A condition operand: the subject's own attribute reads as a bare field name; a
     *  related object's attribute reads as a property path ("reis.vluchtdatum"). */
    private String operand(Expr e, Objecttype subject) {
        if (e instanceof Expr.AttribuutRef a) {
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
