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
        return out.toString();
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

        out.append("rule \"").append(oc.naam()).append("\"\n    dialect \"mvel\"\n    no-loop true\nwhen\n");
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

        out.append("rule \"").append(r.naam()).append("\"\n");
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
            out.append("    modify(").append(SUBJECT).append(") {\n");
            out.append("        ").append(setter(a.attribuut())).append("( ").append(valueStr).append(" )\n");
            out.append("    }\n");
        } else {
            out.append("    // onopgelost doel: ").append(r.doel().bron()).append('\n');
        }
        out.append("end\n\n");
    }

    // ---------------- value expression ("then" side: getters) ----------------
    private String waarde(Expr e, Objecttype subject) {
        if (e instanceof Expr.Literal lit)      return literal(lit);
        if (e instanceof Expr.ParameterRef p)   return ident(p.parameter().naam(), false);
        if (e instanceof Expr.AttribuutRef a) {
            if (a.objecttype() == subject) return SUBJECT + "." + getter(a.attribuut()) + "()";
            String rol = model.verbindendeRol(subject, a.objecttype()); // navigate subject -> related
            if (rol != null) return SUBJECT + "." + getter(rol) + "()." + getter(a.attribuut()) + "()";
            return todo("navigatie", a.bron());
        }
        if (e instanceof Expr.Binair b) {
            if (b.op() == Expr.BinOp.TOT_DE_MACHT)
                return "Math.pow(" + waarde(b.links(), subject) + ", " + waarde(b.rechts(), subject) + ")";
            if (b.op() == Expr.BinOp.PERCENTAGE_VAN)
                return "(" + waarde(b.links(), subject) + " / 100 * " + waarde(b.rechts(), subject) + ")";
            return "(" + waarde(b.links(), subject) + " " + infix(b.op()) + " " + waarde(b.rechts(), subject) + ")";
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
        String elementExpr = ev;
        if (!fn.equals("count") && !f.argumenten().isEmpty()
                && f.argumenten().get(0) instanceof Expr.AttribuutRef a)
            elementExpr = ev + "." + getter(a.attribuut()) + "()";
        accumulates.add("accumulate( " + ev + " : " + ident(elem.naam, true)
                + "( " + scope(elem, subject, f) + " ) ; " + var + " : " + fn + "( " + elementExpr + " ) )");
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
        if (c instanceof Conditie.Vergelijking v)
            return operand(v.links(), subject) + " " + drlOp(v.op()) + " " + operand(v.rechts(), subject);
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
            if (a.objecttype() == subject) return ident(a.attribuut(), false);
            String rol = model.verbindendeRol(subject, a.objecttype());
            if (rol != null) return ident(rol, false) + "." + ident(a.attribuut(), false);
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
        // numeric (possibly "0 €"): keep the leading number
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)?").matcher(lit.waarde());
        return m.find() ? m.group().replace(',', '.') : todo("literal", lit.waarde());
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
