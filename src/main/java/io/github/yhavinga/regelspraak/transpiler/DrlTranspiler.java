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
import java.util.function.Function;

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
    /** DRL `function` definitions a rule needed (name -> body), injected once after the header.
     *  Only the helpers actually used are emitted, so a model with no dimensieselectie produces
     *  byte-identical DRL to before. */
    private final java.util.LinkedHashMap<String, String> hulpFuncties = new java.util.LinkedHashMap<>();

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
                || model.consistenties.stream().anyMatch(c -> tijdgebondenGeldigheid(c.geldigheid()))
                || model.uniciteiten.stream().anyMatch(u -> tijdgebondenGeldigheid(u.geldigheid()))
                || model.uniciteitUnies.stream().anyMatch(u -> tijdgebondenGeldigheid(u.geldigheid()))
                || model.feitCreaties.stream().anyMatch(f -> tijdgebondenGeldigheid(f.geldigheid()));
        if (tijdgebonden) out.append("global java.time.LocalDate rekendatum;\n\n");
        int naHeader = out.length();   // helper functions (if any) are spliced in here, after the header

        for (ResolvedRegel r : model.resolvedRegels) regel(r);
        for (io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie oc : model.objectCreaties) objectCreatie(oc);
        for (io.github.yhavinga.regelspraak.ir.ResolvedVerdeling v : model.verdelingen) verdeling(v);
        for (io.github.yhavinga.regelspraak.ir.ResolvedConsistentie c : model.consistenties) consistentie(c);
        for (io.github.yhavinga.regelspraak.ir.ResolvedUniciteit u : model.uniciteiten) uniciteit(u);
        for (io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie u : model.uniciteitUnies) uniciteitUnie(u);
        for (io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie f : model.feitCreaties) feitCreatie(f);
        if (!hulpFuncties.isEmpty())
            out.insert(naHeader, String.join("", hulpFuncties.values()));
        return out.toString();
    }

    /**
     * One DRL rule under construction: an ordered list of "when" clauses (patterns, accumulates and
     * guards, in the exact textual order they must appear) and a "then" consequence body. {@link #render}
     * owns the rule header, the 4-space when-line indent, and the rule-name escaping — the structure that
     * was hand-appended, identically, by every emitter. This is rule-ASSEMBLY only: the clause and value
     * strings are still produced by the leaf emitters; here they are composed, not re-derived. An inert
     * no-op rule (the resolver could not lower it) is just a Regel whose clauses are a {@code // onopgelost}
     * comment and an {@code eval(true)} with an empty consequence — so the no-op is built structurally,
     * not special-cased in the renderer.
     */
    private static final class Regel {
        private final String naam;
        private final List<String> when = new ArrayList<>();
        private String then = "";

        Regel(String naam) { this.naam = naam; }

        /** Add one when-clause (a pattern, an accumulate, or a guard) — rendered as a single indented line. */
        Regel clausule(String c) { when.add(c); return this; }
        Regel clausules(List<String> cs) { when.addAll(cs); return this; }
        /** The §4.2 validity guard — added only for a time-bounded version, so an "altijd" rule is unchanged. */
        Regel geldigheid(io.github.yhavinga.regelspraak.ir.Geldigheid g) {
            if (tijdgebondenGeldigheid(g)) when.add(geldigheidGuard(g));
            return this;
        }
        /** The consequence body: the text between {@code then} and {@code end}, each line already 4-space
         *  indented and newline-terminated (empty for an inconsistency-free / inert rule). */
        Regel dan(String body) { this.then = body; return this; }

        String render() {
            StringBuilder b = new StringBuilder();
            b.append("rule \"").append(escapeRegelNaam(naam)).append("\"\n");
            b.append("    dialect \"mvel\"\n    no-loop true\nwhen\n");
            for (String c : when) b.append("    ").append(c).append('\n');
            b.append("then\n").append(then).append("end\n\n");
            return b.toString();
        }
    }

    /** Reset the per-rule accumulate side-channel that value/condition lowering writes into (an
     *  aggregation in the value registers an accumulate pattern for the "when"). Called at the start of
     *  every emitter that lowers a value or condition, so accumulates from one rule never leak into the
     *  next. */
    private void resetAccumulates() { accumulates = new ArrayList<>(); accCount = 0; }

    /** §9.5 the consequence shared by the three inconsistency-flagging rules (consistentie, uniciteit,
     *  uniciteit-unie): insert an Inconsistentie carrying the rule name and the offending subject. The
     *  name is escaped to match the {@link #inconsistentieGuard} that keys off the same literal. */
    private String inconsistentieDan(String naam) {
        return "    insert(new " + FACT_PACKAGE + ".Inconsistentie(\""
                + escapeRegelNaam(naam) + "\", " + SUBJECT + "));\n";
    }

    /** §13.4.8 consistentieregel. The assertion MUST hold, so the rule fires on its
     *  VIOLATION — the negated condition — and inserts an Inconsistentie carrying the
     *  rule name and the offending subject (§9.5: "inconsistent" as the result, usable by
     *  other rules). A condition whose negation is not expressible (compound/predicate
     *  beyond leeg/gevuld) or an unresolved subject becomes an explicit marker. */
    private void consistentie(io.github.yhavinga.regelspraak.ir.ResolvedConsistentie c) {
        resetAccumulates();
        Objecttype subject = c.subject();
        String constraint = subject != null ? schendingConstraint(c.assertie(), subject) : null;
        Regel rule = new Regel(c.naam());
        if (constraint == null) {
            // The resolver judged this consistentie liftable, but its violation is not expressible — an
            // operand lowered to a marker (e.g. an unresolvable navigation / foreign-type right operand)
            // or the assertion has no faithful negation. The rule would be an inert eval(true) that flags
            // nothing, so surface the dropped check as a WARNING (matching the unlifted forms) instead of
            // letting it vanish silently. Deduped so a re-transpile of the same model does not pile up.
            var w = new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                    "consistentieregel '" + c.naam() + "' kon niet naar een schending-constraint worden omgezet"
                    + " (operand onresolveerbaar of geen getrouwe negatie); de regel controleert niets",
                    c.line());
            if (!model.diagnostics.contains(w)) model.diagnostics.add(w);
            rule.clausule("// onopgelost consistentieregel: " + c.bron()).clausule("eval(true)");
            out.append(rule.render());
            return;
        }
        rule.clausule(SUBJECT + " : " + ident(subject.naam, true) + "( " + constraint + " )")
            .clausules(accumulates)
            .geldigheid(c.geldigheid())
            .clausule(inconsistentieGuard(c.naam()))
            .dan(inconsistentieDan(c.naam()));
        out.append(rule.render());
    }

    /** §8.1.6 uniciteitscontrole. "is uniek" is waar when the key is unique, so the rule is violated
     *  when two DISTINCT instances share it. The subject binds each non-null key part, and an
     *  accumulate counts the instances whose key equals it; more than one means a duplicate exists, so
     *  the subject is flagged with an Inconsistentie — once, regardless of how many instances share the
     *  key (an {@code exists}-with-{@code this != $subject} self-join is unreliable here: Drools does
     *  not exclude self inside {@code exists} for this shape, flagging even a unique instance). A null
     *  key cannot establish a duplicate (§8.1.6: two unknown identities are not the same object), so it
     *  is guarded out — the subject is matched only when its key is non-null, and a non-null "== $kN"
     *  never matches a null-keyed instance, so a null key is neither flagged nor counted as a match. */
    private void uniciteit(io.github.yhavinga.regelspraak.ir.ResolvedUniciteit u) {
        String type = ident(u.subject().naam, true);
        StringBuilder binders = new StringBuilder();   // op de subject: "$k0 : p != null, …"
        StringBuilder matchers = new StringBuilder();  // in de accumulate: "p == $k0, …"
        if (u.scopeVeld() != null) {                   // parent-scoped: count only within the subject's scope
            String sv = ident(u.scopeVeld(), false);
            binders.append("$scope : ").append(sv).append(" != null, ");
            matchers.append(sv).append(" == $scope, ");
        }
        for (int i = 0; i < u.sleutels().size(); i++) {
            String prop = ident(u.sleutels().get(i), false);
            binders.append("$k").append(i).append(" : ").append(prop).append(" != null");
            matchers.append(prop).append(" == $k").append(i);
            if (i < u.sleutels().size() - 1) { binders.append(", "); matchers.append(", "); }
        }
        out.append(new Regel(u.naam())
            .clausule(SUBJECT + " : " + type + "( " + binders + " )")
            .clausule("Number( intValue > 1 ) from accumulate( $o : " + type + "( " + matchers + " ), count($o) )")
            .geldigheid(u.geldigheid())
            .clausule(inconsistentieGuard(u.naam()))
            .dan(inconsistentieDan(u.naam()))
            .render());
    }

    /** §8.1.6 "verenigd met": uniqueness across the UNION of several sources' key spaces. One rule per
     *  source — a subject of that source is flagged when the total count, summed over EVERY source, of
     *  instances whose key equals the subject's exceeds one (so a value shared within one source or
     *  across two is caught). Keys join positionally (every source has the same arity). */
    private void uniciteitUnie(io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie u) {
        var bronnen = u.bronnen();
        int arity = bronnen.get(0).sleutels().size();
        for (int s = 0; s < bronnen.size(); s++) {                   // the source whose instances this rule judges
            var subj = bronnen.get(s);
            StringBuilder binders = new StringBuilder();             // bind the subject's non-null key parts
            for (int i = 0; i < arity; i++) {
                if (i > 0) binders.append(", ");
                binders.append("$k").append(i).append(" : ").append(ident(subj.sleutels().get(i), false)).append(" != null");
            }
            // DRL rule names must be unique; the 1-based source index disambiguates even a same-type union.
            String ruleNaam = u.naam() + " (" + subj.type().naam + " " + (s + 1) + ")";
            Regel rule = new Regel(ruleNaam)
                    .clausule(SUBJECT + " : " + ident(subj.type().naam, true) + "( " + binders + " )");
            StringBuilder som = new StringBuilder();
            for (int j = 0; j < bronnen.size(); j++) {               // one count per source, matched by value
                var b = bronnen.get(j);
                StringBuilder match = new StringBuilder();
                for (int i = 0; i < arity; i++) {
                    if (i > 0) match.append(", ");
                    match.append(ident(b.sleutels().get(i), false)).append(" == $k").append(i);
                }
                rule.clausule("$o" + j + " : Number() from accumulate( $i" + j + " : "
                        + ident(b.type().naam, true) + "( " + match + " ), count($i" + j + ") )");
                som.append(j > 0 ? " + " : "").append("$o").append(j).append(".intValue()");
            }
            rule.clausule("eval( " + som + " > 1 )")
                .geldigheid(u.geldigheid())
                .clausule(inconsistentieGuard(u.naam()))             // guard on the shared rule name
                .dan(inconsistentieDan(u.naam()));
            out.append(rule.render());
        }
    }

    /** A "when" guard that makes an Inconsistentie-inserting rule IDEMPOTENT across fire cycles: it
     *  fires only while no Inconsistentie for this rule + subject already exists. Without it, a later
     *  insertion that changes a count/comparison re-activates the rule on an already-flagged subject
     *  (no-loop only blocks re-activation from the rule's OWN consequence), inserting a duplicate. */
    private String inconsistentieGuard(String naam) {
        return "not " + FACT_PACKAGE + ".Inconsistentie( regel == \"" + escapeRegelNaam(naam)
                + "\", subject == " + SUBJECT + " )";
    }

    /** The "when" constraint that detects a VIOLATION of the assertion. An ordering or
     *  date comparison is onwaar — hence inconsistent (§9.5) — not only when the
     *  complement holds but also when EITHER operand is empty (the typeringen leegwaarde
     *  tables: a leeg operand makes the comparison onwaar). The algebraic complement is
     *  likewise onwaar on null, so those operands are guarded explicitly. gelijk/ongelijk
     *  and leeg/gevuld use the plain negation (their empty cells are complementary).
     *  Returns null when no faithful negation exists — the caller marks the rule. */
    private String schendingConstraint(Conditie assertie, Objecttype subject) {
        if (assertie instanceof Conditie.Samengesteld s && s.kwantor() == Conditie.Kwantor.ALLE) {
            // §13.4.8: "alle criteria moeten gelden" — inconsistent when ANY criterion is violated.
            // OR the per-criterion violations; an un-lowerable criterion no-ops the whole rule.
            java.util.List<String> schendingen = new java.util.ArrayList<>();
            for (Conditie deel : s.onderdelen()) {
                String d = schendingConstraint(deel, subject);
                if (d == null) return null;
                schendingen.add(d);
            }
            return schendingen.isEmpty() ? null : "(" + String.join(" || ", schendingen) + ")";
        }
        // A comparison with a TO-ONE navigated operand cannot use the simple null-guard forms below: those
        // emit the operand via operand()'s Drools "!." which short-circuits the WHOLE term to a non-match on
        // a null reference, hiding a real gevuld-vs-leeg violation (p41). Handle it with explicit leegwaarde
        // guards per the typeringen rekentabellen instead.
        if (assertie instanceof Conditie.Vergelijking cmp) {
            String genav = genavigeerdeVergelijkingSchending(cmp, subject);
            if (genav != null) return genav;
        }
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
        if (assertie instanceof Conditie.Vergelijking v && v.op() == Conditie.Operator.GELIJK
                && nietNumeriekVergelijking(v)) {
            // typeringen Tabel 38: a NON-numeriek "gelijk aan" with BOTH operands empty is "fout" — unlike the
            // numeriek Tabel 37 case, which is "waar". So the criterion is NOT satisfied -> a violation. The
            // plain complement `l != r` reads `null != null` -> false, wrongly reporting the instance consistent
            // (§9.5 reading: a criterion is satisfied only when its comparison is "waar"; "fout" is a violation).
            String l = operand(v.links(), subject), r = operand(v.rechts(), subject);
            if (bevatMarker(l) || bevatMarker(r)) return null;
            return "(" + l + " != " + r + " || (" + l + " == null && " + r + " == null))";
        }
        Conditie schending = negeer(assertie);
        if (schending == null) return null;
        // Same hazard via the negation: a gevuld→leeg predicate over a marker emits
        // "<marker> == null" (always true). An un-evaluable criterion flags nothing.
        String c = conditie(schending, subject);
        return bevatMarker(c) ? null : c;
    }

    /** A comparison whose operands are a RESOLVED non-numeriek type (Tekst/Datum/Boolean/Enumeratie). For
     *  these, typeringen Tabel 38 makes a both-empty "gelijk aan" evaluate to "fout"; the numeriek Tabel 37
     *  makes it "waar". An unresolved (Onbekend) operand is left to the numeriek-or-unknown default, so the
     *  both-empty special case never fires on a construct the resolver could not type. */
    private static boolean nietNumeriekVergelijking(Conditie.Vergelijking v) {
        Datatype l = v.links().type(), r = v.rechts().type();
        return !l.isOnbekend() && !r.isOnbekend()
                && !l.isNumeriek() && !l.isPercentage() && !r.isNumeriek() && !r.isPercentage();
    }

    /** Does a lowered constraint string carry an unresolved-reference marker? Such a fragment
     *  is not a usable boolean — wrapping it in a null-guard or negation flips it to a constant. */
    private static boolean bevatMarker(String s) { return s.contains("/* TODO"); }

    /** A comparison operand decomposed for a leegwaarde-faithful violation. {@code leeg}/{@code vol}
     *  are the "is empty"/"is present" guards; {@code waarde} is the value via a plain "." (safe ONLY
     *  behind {@code !leeg}, which short-circuits a null root). A navigated to-one operand uses an
     *  explicit root guard; a direct attribute / literal / parameter uses its plain operand() form. */
    private record OperandDeel(String leeg, String vol, String waarde, boolean genavigeerd) {}

    private OperandDeel operandDeel(Expr e, Objecttype subject) {
        if (e instanceof Expr.AttribuutRef a) {
            NaviRef n = naviResolve(a, subject);
            if (n.soort() == NaviSoort.GENAVIGEERD) {
                String wortel = ident(n.rol(), false), pad = wortel + "." + ident(a.attribuut(), false);
                return new OperandDeel("(" + wortel + " == null || " + pad + " == null)",
                        "(" + wortel + " != null && " + pad + " != null)", pad, true);
            }
        }
        String o = operand(e, subject);   // direct attribute / literal / parameter — never "!."
        return new OperandDeel(o + " == null", o + " != null", o, false);
    }

    /** §13.3.9 to-one navigation + the typeringen leegwaarde rekentabellen (Tabel 37/38 gelijk,
     *  40-43 ordening, 44 ongelijk): build a consistentie violation for a comparison with at least one
     *  navigated to-one operand, treating a null root as the operand being LEEG. Drools "!." would make
     *  such an operand a silent non-match (under-reporting the violation, p41); these explicit guards
     *  short-circuit the null root before any plain "." dereference, so an empty operand yields the
     *  spec result. Returns null when NEITHER operand is navigated (the simpler forms handle it) or when
     *  an operand is an unresolved marker (the caller then WARNs). */
    private String genavigeerdeVergelijkingSchending(Conditie.Vergelijking v, Objecttype subject) {
        OperandDeel l = operandDeel(v.links(), subject), r = operandDeel(v.rechts(), subject);
        if (!l.genavigeerd() && !r.genavigeerd()) return null;
        if (bevatMarker(l.waarde()) || bevatMarker(r.waarde())) return null;
        String[] ov = vergelijkingsOperanden(l.waarde(), v.links().type(), r.waarde(), v.rechts().type());
        String lv = ov[0], rv = ov[1];
        if (ordeningOfDatum(v.op()))   // Tabel 40-43: any empty operand -> onwaar -> violation
            return "(" + l.leeg() + " || " + r.leeg() + " || " + lv + " " + drlOp(flip(v.op())) + " " + rv + ")";
        if (v.op() == Conditie.Operator.GELIJK) {
            if (nietNumeriekVergelijking(v))   // Tabel 38: empty -> onwaar/fout -> always a violation
                return "(" + l.leeg() + " || " + r.leeg() + " || " + lv + " != " + rv + ")";
            // Tabel 37 (numeriek): BOTH empty is "waar" (consistent); only a one-sided empty (onwaar) or a
            // gevuld mismatch violates.
            return "((" + l.leeg() + " && " + r.vol() + ") || (" + r.leeg() + " && " + l.vol() + ") || ("
                    + l.vol() + " && " + r.vol() + " && " + lv + " != " + rv + "))";
        }
        if (v.op() == Conditie.Operator.ONGELIJK)   // Tabel 44: ongelijk is "waar" for gevuld-vs-empty, "onwaar"
            // for both-empty -> "moet ongelijk zijn" is violated by both-empty OR a gevuld match.
            return "((" + l.leeg() + " && " + r.leeg() + ") || (" + l.vol() + " && " + r.vol() + " && " + lv + " == " + rv + "))";
        return null;
    }

    private static boolean ordeningOfDatum(Conditie.Operator op) {
        return switch (op) {
            case KLEINER, KLEINER_OF_GELIJK, GROTER, GROTER_OF_GELIJK,
                 EERDER, EERDER_OF_GELIJK, LATER, LATER_OF_GELIJK -> true;
            case GELIJK, ONGELIJK -> false;
        };
    }

    /** A rule name is free text (§13.2.8) and may contain characters that break a DRL string literal — a
     *  double quote (closes the literal early), a backslash (escapes the next character, so a trailing one
     *  would escape the closing quote), or a newline / carriage return (splits the {@code rule "…"} header
     *  across lines). Neutralise all four to a harmless visible substitute rather than backslash-escaping,
     *  so the SAME form is well-formed in the {@code rule} header, the Inconsistentie literal, and the
     *  idempotency guard's {@code regel == "…"} — keeping the whole Drools package well-formed. The four
     *  substitutions are over disjoint characters and none introduces another, so their order is immaterial. */
    static String escapeRegelNaam(String naam) {
        return naam.replace("\\", "/").replace("\"", "'").replace("\r", " ").replace("\n", " ");
    }

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

    /** §13.4.10 verdeling. Two methods have a single forward-rule form: EQUAL SHARES binds the source,
     *  counts the recipients joined to it, and gives each total/count; NAAR RATO (proportional) instead
     *  SUMS each recipient's weight attribute and gives each total·weight/sum. Both fold the recipients in
     *  one accumulate. The group/ordering/remainder schemes (COMPLEX) are multi-pass allocations with no
     *  single-rule form — and an unresolvable join or a weight that is not a real recipient field — stay an
     *  explicit no-op marker. */
    private void verdeling(io.github.yhavinga.regelspraak.ir.ResolvedVerdeling v) {
        Objecttype bron = v.bronType(), doel = v.doelType();
        String join = bron != null && doel != null ? model.verbindendeRol(doel, bron) : null;
        var methode = v.methode();
        boolean gelijk = methode == io.github.yhavinga.regelspraak.ir.ResolvedVerdeling.Methode.GELIJKE_DELEN && join != null;
        // NAAR RATO needs the join AND a weight that is a real GETALACHTIG (numeriek/percentage) field of the
        // recipient: heeftLid alone would also accept a Tekst attribute or a boolean kenmerk, whose getter
        // cannot be summed/multiplied — uncompilable DRL that sinks the whole package. A non-numeric, foreign,
        // or absent weight therefore stays the honest no-op marker.
        Datatype gewichtType = doel != null && v.ratoAttribuut() != null ? doel.attribuut(v.ratoAttribuut()) : null;
        boolean rato = methode == io.github.yhavinga.regelspraak.ir.ResolvedVerdeling.Methode.NAAR_RATO && join != null
                && gewichtType != null && gewichtType.isGetalachtig();
        if (!gelijk && !rato) {
            out.append(new Regel(v.naam())
                .clausule("// onopgelost verdeling ("
                        + (bron == null || doel == null ? "referentie" : methode.name().toLowerCase())
                        + "): " + v.bronAttribuut() + " over " + v.doelAttribuut())
                .clausule("eval(true)")
                .render());
            return;
        }
        String j = ident(join, false) + " == $src";
        Regel rule = new Regel(v.naam()).clausule("$src : " + ident(bron.naam, true) + "(  )");
        String aandeel;   // the per-recipient value set into the modify
        if (gelijk) {
            rule.clausule("accumulate( $c : " + ident(doel.naam, true) + "( " + j + " ) ; $n : count($c) )")
                .clausule("$r : " + ident(doel.naam, true) + "( " + j + " )");
            aandeel = "$src." + getter(v.bronAttribuut()) + "().divide($n, java.math.MathContext.DECIMAL64)"; // safe division
        } else {
            // §13.4.10 naar rato: each recipient gets total·weight/Σweight. The accumulate sums the weights of
            // the recipients that HAVE one, and $r binds the same set, so a weightless recipient is left unset
            // (its weight is null — there is no proportional basis) while the weighted ones share the total.
            // DIVERGENCE: §9.7.6 Tabel 19 makes an empty weight on any recipient a "Fout" for the WHOLE
            // verdeling, not a quietly smaller split. The faithful form (suppress the distribution and surface
            // the Fout) needs either a second accumulate / `not` — which, with the modify in the consequence,
            // cancels the sibling recipient activations under no-loop so only one recipient fires — or an
            // inserted Inconsistentie fact. Both are out of this slice; the exclude-and-redistribute reading is
            // documented (docs/grammar-divergences.md) and carded, and the corpus weight (woonregio factor) is
            // always populated. A zero total weight (all weights 0) is guarded so the divide cannot throw — the
            // spec leaves a zero divisor undefined, so no-op-not-crash is the safe reading.
            String wNaam = ident(v.ratoAttribuut(), false), wGet = getter(v.ratoAttribuut());
            rule.clausule("accumulate( $c : " + ident(doel.naam, true) + "( " + j + ", " + wNaam
                            + " != null ) ; $sum : sum($c." + wGet + "()) )")
                .clausule("$r : " + ident(doel.naam, true) + "( " + j + ", " + wNaam + " != null )")
                .clausule("eval( $sum.signum() != 0 )");
            aandeel = "$src." + getter(v.bronAttribuut()) + "().multiply($r." + wGet
                    + "()).divide($sum, java.math.MathContext.DECIMAL64)";
        }
        out.append(rule.geldigheid(v.geldigheid())
            .dan("    modify($r) {\n        " + setter(v.doelAttribuut()) + "( " + aandeel + " )\n    }\n")
            .render());
    }

    /** §13.4.6 objectcreatie -> a rule that inserts a new fact, sets its attributes,
     *  and links it back to the subject through the feittype role. */
    private void objectCreatie(io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie oc) {
        resetAccumulates();
        Objecttype nieuw = oc.nieuwType(), subject = oc.subject();
        // value expressions may carry aggregations -> collected into the "when" (so lower them BEFORE
        // reading `accumulates` into the clauses, even on the no-op path, to keep the side-channel order).
        java.util.List<String> setters = new ArrayList<>();
        for (var t : oc.toekenningen())
            setters.add("    $new." + setter(t.attribuut()) + "( " + waarde(t.waarde(), subject) + " );");

        if (subject == null || nieuw == null) {
            out.append(new Regel(oc.naam())
                .clausule("// onopgelost objectcreatie: " + oc.naam())
                .clausule("eval(true)")
                .render());
            return;
        }
        StringBuilder dan = new StringBuilder();
        dan.append("    ").append(ident(nieuw.naam, true)).append(" $new = new ")
           .append(ident(nieuw.naam, true)).append("();\n");
        for (String s : setters) dan.append(s).append('\n');
        String rolNaarSubject = model.verbindendeRol(nieuw, subject); // field on the new object -> subject
        if (rolNaarSubject != null)
            dan.append("    $new.").append(setter(rolNaarSubject)).append("( ").append(SUBJECT).append(" );\n");
        else
            dan.append("    // onopgelost terugkoppeling naar ").append(subject.naam).append('\n');
        dan.append("    insert($new);\n");

        out.append(new Regel(oc.naam())
            .clausule(SUBJECT + " : " + ident(subject.naam, true)
                    + "( " + (oc.voorwaarde() != null ? conditie(oc.voorwaarde(), subject, true) : "") + " )")
            .clausules(accumulates)
            .geldigheid(oc.geldigheid())
            .dan(dan.toString())
            .render());
    }

    /** §13.4.7 feitcreatie -> a rule that, for each anker and each doel ALREADY related to it by the
     *  existing rolC relation ({@code joinVeld}), creates the NEW relation by setting {@code zetVeld}
     *  on the doel. A relation instance is a reference field in this fact model (§9.4), so creating one
     *  is a {@code modify}-set; {@code no-loop} keeps the modify from re-triggering the rule, and setting
     *  the field to the same value is idempotent across other rules' fire cycles. */
    private void feitCreatie(io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie fc) {
        resetAccumulates();
        Objecttype anker = fc.anker(), doel = fc.doel();
        // The voorwaarde selects which objects get the relation: a guard on the anker (Vlucht) stays on
        // the anker pattern; a guard on the doel (the passengers) hosts on the $doel pattern. A guard that
        // lowers to NEITHER must NOT be silently dropped — dropping it makes the rule fire unconditionally
        // and OVER-CREATE the relation. Instead the rule becomes an inert eval(false) no-op (carrying the
        // marker), the §-wide "an unresolved condition is a compilable no-op" discipline: better to create
        // nothing (visibly) than to create wrongly.
        String ankerC = "", doelExtra = "", noFire = "";
        if (fc.voorwaarde() != null) {
            String opAnker = conditie(fc.voorwaarde(), anker, true);
            if (!bevatMarker(opAnker)) {
                ankerC = opAnker;
            } else {
                resetAccumulates();   // discard the failed anker attempt
                String opDoel = conditie(fc.voorwaarde(), doel, true);
                if (!bevatMarker(opDoel)) {
                    doelExtra = opDoel;
                } else {
                    resetAccumulates();
                    noFire = "eval( false ) /* TODO voorwaarde niet naar anker of doel te lowereren — regel inert i.p.v. over-creatie */";
                }
            }
        }
        Regel rule = new Regel(fc.naam());
        if (!noFire.isEmpty()) rule.clausule(noFire);
        rule.clausule(SUBJECT + " : " + ident(anker.naam, true) + "( " + ankerC + " )")
            .clausules(accumulates)
            .geldigheid(fc.geldigheid());
        // §9.4 multi-hop: bind each intermediate object on the path anker -> … -> doel, then join the doel to
        // the last one. A to-many hop reads the child's back-reference (veld == $prev); a to-one hop reads the
        // parent's forward reference (this == $prev.veld). $prev is the anker for the first step.
        String vorige = SUBJECT;
        int t = 0;
        for (io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie.Tussenstap stap : fc.tussenstappen()) {
            String var = "$t" + t++;
            rule.clausule(var + " : " + ident(stap.type().naam, true) + "( "
                    + (stap.viaOuderVeld() ? "this == " + vorige + "." + ident(stap.veld(), false)
                                           : ident(stap.veld(), false) + " == " + vorige)
                    + " )");
            vorige = var;
        }
        rule.clausule("$doel : " + ident(doel.naam, true) + "( "
                + ident(fc.joinVeld(), false) + " == " + vorige
                + (!doelExtra.isEmpty() ? ", " + doelExtra : "") + " )")
            .dan("    modify($doel) {\n        " + setter(fc.zetVeld()) + "( " + SUBJECT + " )\n    }\n");
        out.append(rule.render());
    }

    private void regel(ResolvedRegel r) {
        resetAccumulates();
        Objecttype subject = r.doel() instanceof Expr.AttribuutRef a ? a.objecttype() : null;
        // Translate the value first: any aggregation registers an accumulate pattern
        // for the "when" and yields the bound result variable used here in the "then".
        String valueStr = subject != null && r.doel() instanceof Expr.AttribuutRef
                ? waarde(r.waarde(), subject) : null;

        // A forward calculation should not retrigger itself on its own modify (no-loop, in the header);
        // it still recomputes when another rule changes the facts it reads.
        Regel rule = new Regel(r.naam());
        if (subject == null) {
            rule.clausule("// onopgelost doel-objecttype: " + r.doel().bron()).clausule("eval(true)");
        } else {
            String constraint = r.voorwaarde() != null ? conditie(r.voorwaarde(), subject, true) : "";
            rule.clausule(SUBJECT + " : " + ident(subject.naam, true) + "( " + constraint + " )")
                .clausules(accumulates)
                .geldigheid(r.geldigheid());   // §4.2: a time-bounded version fires only inside its window
        }
        if (valueStr != null && r.doel() instanceof Expr.AttribuutRef a) {
            // restate the value in the target's unit (§4); §6.1.2 forbids AUTO-rounding —
            // if the result has too many decimals the author must add an explicit afronding.
            if (r.waarde().type().isNumeriek())
                valueStr = omrekenWaarde(valueStr, r.waarde().type(), a.type());
            rule.dan("    modify(" + SUBJECT + ") {\n        " + setter(a.attribuut()) + "( " + valueStr + " )\n    }\n");
        } else {
            rule.dan("    // onopgelost doel: " + r.doel().bron() + "\n");
        }
        out.append(rule.render());
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

    /** Whether a bare scalar read {@code $subject.getRol().getAttr()} crosses a TO-MANY role
     *  (§13.3.9.1): the subject relates to MANY of {@code a}'s object type through {@code rol},
     *  so a single getter would silently read one of many. Such a read must instead be an
     *  aggregation (de som/maximale/… van … van de <rol>), so it is emitted as a marker. Only a
     *  resolved (non-EIGEN, non-null) role can be to-many. */
    private boolean naarVeel(Objecttype subject, Expr.AttribuutRef a, String rol) {
        return rol != null && !EIGEN.equals(rol) && model.naarVeelRol(subject, a.objecttype(), rol);
    }

    /** True when the reference is bound to a known object type that does NOT declare the
     *  attribute (nor a kenmerk by that name). Emitting `getX()` for it would call a method
     *  the fact has no declaration for — uncompilable DRL — so the caller emits a marker
     *  instead. (A null object type is already handled by {@link #naviRol} returning null.) */
    private static boolean attribuutOntbreekt(Expr.AttribuutRef a) {
        return a.objecttype() != null && !a.objecttype().heeftLid(a.attribuut());
    }

    /** How an attribute reference reads relative to the rule subject, resolved ONCE so the four
     *  context-specific emitters (the value getter, the condition property, the leegwaarde decomposition,
     *  the leeg/gevuld guard) format from it instead of each re-deriving {@link #naviRol} + the
     *  EIGEN/naar-veel/absent branching. {@code EIGEN} = the subject's own attribute; {@code GENAVIGEERD} =
     *  a single to-one navigation through {@code rol}; the rest are the marker cases. */
    private enum NaviSoort { EIGEN, GENAVIGEERD, AFWEZIG, NAAR_VEEL, ONOPGELOST }
    private record NaviRef(NaviSoort soort, String rol) {}

    private NaviRef naviResolve(Expr.AttribuutRef a, Objecttype subject) {
        if (attribuutOntbreekt(a)) return new NaviRef(NaviSoort.AFWEZIG, null);
        String rol = naviRol(subject, a);
        if (EIGEN.equals(rol))          return new NaviRef(NaviSoort.EIGEN, null);
        if (naarVeel(subject, a, rol))  return new NaviRef(NaviSoort.NAAR_VEEL, rol);
        if (rol != null)                return new NaviRef(NaviSoort.GENAVIGEERD, rol);
        return new NaviRef(NaviSoort.ONOPGELOST, null);
    }

    /** Whether {@code a} reads a §13.3.7 gedimensioneerd attribuut (its field is a Map&lt;label,value&gt;).
     *  Such a read is only valid through an {@link Expr.DimensieCel} (which pins the label) or as the source
     *  of a dimensieaggregatie; a bare value read of it is under-specified (§3.6). */
    private boolean isGedimensioneerd(Expr.AttribuutRef a) {
        return a.objecttype() != null && !a.objecttype().dimensiesVan(a.attribuut()).isEmpty();
    }

    /** The getter chain for an attribute read relative to the subject — the EIGEN own-attribute, a single
     *  navigated reference, or a marker for an absent/naar-veel/unresolvable navigation. Shared by the plain
     *  value read and the dimensioned-read forms (which need the bare getter — for a dimensioned attribute
     *  the Map field — before indexing a label), so the latter bypass the §3.6 under-specified guard. */
    private String attribuutGetter(Expr.AttribuutRef a, Objecttype subject) {
        NaviRef n = naviResolve(a, subject);
        return switch (n.soort()) {
            case EIGEN       -> SUBJECT + "." + getter(a.attribuut()) + "()";
            case GENAVIGEERD -> SUBJECT + "." + getter(n.rol()) + "()." + getter(a.attribuut()) + "()";
            case NAAR_VEEL   -> todo("naar-veel-navigatie", a.bron());
            default          -> todo("navigatie", a.bron());   // AFWEZIG / ONOPGELOST
        };
    }

    // ---------------- value expression ("then" side: getters) ----------------
    private String waarde(Expr e, Objecttype subject) {
        if (e instanceof Expr.Literal lit)      return literal(lit);
        if (e instanceof Expr.ParameterRef p)   return ident(p.parameter().naam(), false);
        if (e instanceof Expr.AttribuutRef a) {
            // §3.6: a gedimensioneerd attribuut read WITHOUT pinning its dimensions is under-specified (it
            // would emit a bare Map getter where a scalar is expected). A legitimate dimensioned read is an
            // Expr.DimensieCel; a bare AttribuutRef to a dimensioned attribute is therefore an error — marker.
            if (isGedimensioneerd(a)) return todo("ongedimensioneerde lezing van gedimensioneerd attribuut (§3.6)", a.bron());
            return attribuutGetter(a, subject);
        }
        if (e instanceof Expr.DimensieCel c) {
            // §3.6 a single dimensioned cell reads the attribute's Map<label,value> field and indexes the
            // selected label. Scope: a single-dimension attribute (one label); a multi-dimension composite
            // key is deferred to a marker. An unresolvable underlying read (e.g. a navigation) markers too.
            String map = attribuutGetter(c.attribuut(), subject);
            if (bevatMarker(map) || c.labels().size() != 1) return todo("dimensiecel", c.bron());
            return "(" + map + ").get(\"" + c.labels().get(0).replace("\"", "") + "\")";
        }
        if (e instanceof Expr.Binair b) {
            if (b.op() == Expr.BinOp.TOT_DE_MACHT) {
                // §13.4.17.14: an INTEGER (literal) exponent — the common case — is computed
                // exactly with BigDecimal.pow(int); going through Math.pow (a double) would lose
                // integer precision above 2^53 (7^19 off by 1) before the mandatory afronding.
                Integer n = geheelExponent(b.rechts());
                if (n != null) return "(" + waarde(b.links(), subject) + ").pow(" + n + ")";
                // a genuinely fractional/runtime exponent: Math.pow yields a double, wrapped as a
                // BigDecimal so the afronding's setScale and surrounding arithmetic compile.
                return "java.math.BigDecimal.valueOf(Math.pow((" + waarde(b.links(), subject)
                        + ").doubleValue(), (" + waarde(b.rechts(), subject) + ").doubleValue()))";
            }
            if (b.op() == Expr.BinOp.PERCENTAGE_VAN)
                return "(" + waarde(b.links(), subject) + " / 100 * " + waarde(b.rechts(), subject) + ")";
            if (b.op() == Expr.BinOp.GEDEELD_DOOR)
                return "(" + waarde(b.links(), subject) + ").divide(" + waarde(b.rechts(), subject)
                        + ", java.math.MathContext.DECIMAL64)"; // safe: no non-terminating-quotient throw
            if (b.op() == Expr.BinOp.GEDEELD_DOOR_ABS)
                // §6.5: "gedeeld door (ABS)" (the AanslagBelastingenSysteem division) does NOT share the exact /
                // fraction result of "gedeeld door" — its result has at most 5 decimals, rounded TOWARD ZERO
                // ("richting 0"), so a non-terminating quotient always has a known decimal form. divide(d, 5,
                // DOWN) is exactly that (scale 5, truncate toward zero). The spec's 2/3→0,66667 row contradicts
                // its own "richting 0" rule and its -0,66666 neighbour — a table typo; the rule (DOWN) governs.
                return "(" + waarde(b.links(), subject) + ").divide(" + waarde(b.rechts(), subject)
                        + ", 5, java.math.RoundingMode.DOWN)";
            if (b.op() == Expr.BinOp.MAAL)  // §6.4: units multiply, operands are NOT converted to match
                return "(" + waarde(b.links(), subject) + " * " + waarde(b.rechts(), subject) + ")";
            // §13.4.16 date arithmetic (a datum ± a tijdsduur) is NOT mvel "+": "LocalDate + BigDecimal" is a
            // type error that breaks the Drools build. The correct form is .plusDays/.plusMonths/… keyed on the
            // duration's unit (deferred to the arithmetic-correctness work), so emit an honest marker rather
            // than uncompilable DRL — the only +/- path that otherwise emits broken (not inert) code.
            if (b.links().type().isDatum() || b.rechts().type().isDatum())
                return todo("datum-rekenkunde (datum ± tijdsduur vereist .plusX, geen mvel '+')", b.bron());
            // §4: restate the right operand in the left operand's unit before +/- so a
            // convertible-but-different unit (mnd vs jr) is scaled, not added raw.
            String rechts = omrekenWaarde(waarde(b.rechts(), subject), b.rechts().type(), b.links().type());
            return "(" + waarde(b.links(), subject) + " " + infix(b.op()) + " " + rechts + ")";
        }
        if (e instanceof Expr.Afgerond af) {
            return afgerondeTerm(waarde(af.basis(), subject), af);
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
            String scalar = scalarFunctie(f, x -> waarde(x, subject)); // §13.4.17 single-value builtins (abs, datum-delen, datum-met)
            if (scalar != null) return scalar;
            String dim = dimensieAggregatie(f, subject);      // SOM/min/max over a dimension's labels
            if (dim != null) return dim;
            String concat = concatenatieAggregatie(f, subject); // pairwise min/max over listed operands
            if (concat != null) return concat;
            String var = accumulate(f, subject);              // hoists an accumulate into the "when"
            return var != null ? var : todo("aggregatie " + f.kind(), f.bron());
        }
        return todo("expressie", e.bron());
    }

    /**
     * §13.4.17 the SCALAR built-in functions — those that map to a single mvel/Java expression rather than an
     * accumulate over instances: the absolute value, the date/time component extractions, and the date /
     * datetime constructors, plus the tijdsduur between two dates. Returns null for every other
     * {@link Expr.FunctieKind} (the aggregations and the not-yet-lowered builtins like concatenatie/paasdag),
     * so the caller falls through to the accumulate path or its marker. A marker propagates straight through
     * when an argument is itself unresolved.
     *
     * <p>{@code arg} lowers an argument expression: on the VALUE side it is {@link #waarde} (a {@code
     * $subject.getX()} getter), on the CONDITION side {@link #operand} (the bare property a pattern constraint
     * needs, since {@code $subject} is not bound inside its own pattern). Every scalar builtin lowers to a
     * single mvel term valid in either position, so the one routine serves both — the aggregations, which need
     * a {@code when}-clause accumulate, stay null here and let each caller fall through to its marker.
     */
    private String scalarFunctie(Expr.Functie f, Function<Expr, String> arg) {
        List<Expr> a = f.argumenten();
        if (f.kind() == Expr.FunctieKind.TIJDSDUUR || f.kind() == Expr.FunctieKind.ABSOLUTE_TIJDSDUUR)
            return tijdsduur(f, arg);
        // §13.4.17.18-.24 / §6.12 a DATE or TIME component (jaar/maand/dag/uur/minuut/seconde/milliseconde uit).
        // All seven read as a Numeriek via one ChronoField, lowered through the rsDatumDeel helper that carries
        // the §6.12 / typeringen Tabel 28 leegwaarde rule — an empty input yields an empty result. That null
        // guard is essential, not cosmetic: a bare "(d).getLong(…)" NPEs on a null date, and inside a `when`
        // constraint the throw is a ConstraintEvaluationException that aborts the WHOLE fireAllRules() pass; a
        // pattern constraint also cannot express the guard with a ternary, so the helper is the only form valid
        // in both positions. DATE parts read off any datum; the TIME parts (HOUR_OF_DAY..MILLI_OF_SECOND) are
        // defined only on a LocalDateTime, so a plain Datum-in-dagen operand markers honestly (LocalDate would
        // throw UnsupportedTemporalType). (The resolver does not yet reject "het uur uit een Datum" — carded.)
        String chronoField = switch (f.kind()) {
            case JAAR_UIT -> "YEAR"; case MAAND_UIT -> "MONTH_OF_YEAR"; case DAG_UIT -> "DAY_OF_MONTH";
            case UUR_UIT -> "HOUR_OF_DAY"; case MINUUT_UIT -> "MINUTE_OF_HOUR";
            case SECONDE_UIT -> "SECOND_OF_MINUTE"; case MILLISECONDE_UIT -> "MILLI_OF_SECOND";
            default -> null;
        };
        if (chronoField != null) {
            if (a.size() != 1) return null;
            boolean tijdDeel = switch (f.kind()) {
                case UUR_UIT, MINUUT_UIT, SECONDE_UIT, MILLISECONDE_UIT -> true; default -> false;
            };
            if (tijdDeel && !(a.get(0).type() instanceof Datatype.DatumTijd))
                return todo("tijd-component uit een niet-datum-tijd (§13.4.17.21-.24)", f.bron());
            String d = arg.apply(a.get(0));
            if (bevatMarker(d)) return d;
            registreerDatumDeelFunctie();
            return "rsDatumDeel(" + d + ", java.time.temporal.ChronoField." + chronoField + ")";
        }
        return switch (f.kind()) {
            case ABSOLUTE_WAARDE -> {                 // §13.4.17.17 / §6.9 Tabel 20: |leeg| = leeg, so route
                if (a.size() != 1) yield null;        // through rsAbs (a null guard a constraint cannot ternary).
                String x = arg.apply(a.get(0));
                if (bevatMarker(x)) yield x;
                registreerAbsFunctie();
                yield "rsAbs(" + x + ")";
            }
            case WORTEL -> {                          // §13.4.17.13 — the radicand's root, rounded by the
                if (a.size() != 1) yield null;        // MANDATORY afronding (an Afgerond wrapper the resolver
                String x = arg.apply(a.get(0));       // adds). BigDecimal.sqrt at 34-digit precision, NOT a
                // double: Math.sqrt's ~16-digit noise can flip a directional (FLOOR/CEILING) afronding on a
                // terminating root (sqrt(805147.29)=897.3 but the double is 897.3000…1 -> CEILING gives 897.4).
                // 34 digits make the directional cut exact for any realistic afronding; a negative radicand
                // throws a self-describing ArithmeticException (§6.6 foutmelding) instead of a NaN crash.
                yield bevatMarker(x) ? x : "(" + x + ").sqrt(java.math.MathContext.DECIMAL128)";
            }
            case DATUM_MET     -> datumConstructor(a, arg, "java.time.LocalDate", 3, false);     // §13.4.17.35 (3 parts)
            case DATUMTIJD_MET -> datumConstructor(a, arg, "java.time.LocalDateTime", 7, true);  // §13.4.17.36 (7 parts)
            case EERSTE_PAASDAG -> {                  // §13.4.17.37 — Easter Sunday of the given year
                if (a.size() != 1) yield null;
                String j = arg.apply(a.get(0));
                if (bevatMarker(j)) yield j;
                registreerPaasdagFunctie();
                yield "rsEerstePaasdag(" + j + ")";
            }
            default -> null;
        };
    }

    /** §13.4.17.35/.36 build a LocalDate/LocalDateTime from its {@code n} numeric component expressions
     *  (jaar, maand, dag[, uur, minuut, seconde, milliseconde]); each is restated as an int. When
     *  {@code laatsteIsMilli}, the trailing component is a MILLIsecond but {@code LocalDateTime.of}'s 7th
     *  argument is nanoOfSecond, so it is scaled ×1e6. A marker argument propagates. */
    private String datumConstructor(List<Expr> a, Function<Expr, String> arg, String type, int n, boolean laatsteIsMilli) {
        if (a.size() != n) return null;
        StringJoiner sj = new StringJoiner(", ", type + ".of(", ")");
        for (int i = 0; i < a.size(); i++) {
            String v = arg.apply(a.get(i));
            if (bevatMarker(v)) return v;
            sj.add(laatsteIsMilli && i == a.size() - 1 ? "(" + v + ").intValue() * 1000000" : "(" + v + ").intValue()");
        }
        return sj.toString();
    }

    /**
     * §6.10 / §13.4.17.32 "de [absolute] tijdsduur van A tot B in [hele] &lt;tijdseenheid&gt;": the number of
     * COMPLETE units between two dates, which is exactly {@code ChronoUnit.<UNIT>.between(A, B)} — signed (A
     * later than B gives a negative result, §6.10) and symmetric, matching Tabel 14's +52 / -52. The absolute
     * form wraps it in {@code Math.abs}. §6.10 Tabel 14 also makes an EMPTY operand yield an empty result (not
     * 0), and {@code between(null, …)} would NPE, so both operands are null-guarded. A time unit (uur/…/
     * milliseconde) needs Datum-en-tijd operands (ChronoUnit.HOURS.between throws on a LocalDate); a kwartaal
     * (no ChronoUnit) or an unrecognised unit markers honestly.
     */
    private String tijdsduur(Expr.Functie f, Function<Expr, String> arg) {
        if (f.argumenten().size() != 2) return null;
        String unit = chronoUnit(f.type());
        if (unit == null) return todo("tijdsduur-eenheid (kwartaal / onbekende tijdseenheid)", f.bron());
        boolean tijdEenheid = switch (unit) { case "HOURS", "MINUTES", "SECONDS", "MILLIS" -> true; default -> false; };
        Expr eVan = f.argumenten().get(0), eTot = f.argumenten().get(1);
        if (tijdEenheid && (!(eVan.type() instanceof Datatype.DatumTijd) || !(eTot.type() instanceof Datatype.DatumTijd)))
            return todo("tijdsduur in een tijdseenheid vereist datum-tijd operanden (§6.10)", f.bron());
        String van = arg.apply(eVan), tot = arg.apply(eTot);
        if (bevatMarker(van) || bevatMarker(tot)) return todo("tijdsduur", f.bron());
        // Lower via a registered Java-bodied helper, NOT inline mvel: a fully-qualified ChronoUnit.<U>.between
        // does not analyse in a Drools mvel modify-block, and the helper also carries the §6.10 null-guard and
        // the absolute/sign in plain Java where the enum and the overload resolve cleanly.
        registreerTijdsduurFunctie();
        return "rsTijdsduur(" + van + ", " + tot + ", \"" + unit + "\", "
                + (f.kind() == Expr.FunctieKind.ABSOLUTE_TIJDSDUUR) + ")";
    }

    /** Register the Java-bodied DRL helper that computes a §6.10 tijdsduur — the whole-unit count between two
     *  Temporals (signed; absolute when asked), empty when either operand is empty (Tabel 14). Idempotent. */
    private void registreerTijdsduurFunctie() {
        hulpFuncties.putIfAbsent("rsTijdsduur",
                "function java.math.BigDecimal rsTijdsduur(java.time.temporal.Temporal van, "
                + "java.time.temporal.Temporal tot, String eenheid, boolean absoluut) {\n"
                + "    if (van == null || tot == null) return null;\n"
                + "    long n = java.time.temporal.ChronoUnit.valueOf(eenheid).between(van, tot);\n"
                + "    return java.math.BigDecimal.valueOf(absoluut ? Math.abs(n) : n);\n"
                + "}\n\n");
    }

    /** Register the Java-bodied DRL helper that returns §13.4.17.37 "de eerste paasdag van" a year — Easter
     *  Sunday in the Gregorian calendar via the Anonymous Gregorian computus (exact, no lookup table).
     *  Empty year -> empty result. Idempotent. */
    private void registreerPaasdagFunctie() {
        hulpFuncties.putIfAbsent("rsEerstePaasdag",
                "function java.time.LocalDate rsEerstePaasdag(java.math.BigDecimal jaar) {\n"
                + "    if (jaar == null) return null;\n"
                + "    int y = jaar.intValue();\n"
                + "    int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4;\n"
                + "    int f = (b + 8) / 25, g = (b - f + 1) / 3;\n"
                + "    int h = (19 * a + b - d - g + 15) % 30;\n"
                + "    int i = c / 4, k = c % 4;\n"
                + "    int l = (32 + 2 * e + 2 * i - h - k) % 7;\n"
                + "    int m = (a + 11 * h + 22 * l) / 451;\n"
                + "    int maand = (h + l - 7 * m + 114) / 31;\n"
                + "    int dag = ((h + l - 7 * m + 114) % 31) + 1;\n"
                + "    return java.time.LocalDate.of(y, maand, dag);\n"
                + "}\n\n");
    }

    /** Register the Java-bodied DRL helper that reads one §13.4.17.18-.24 date/time component (a ChronoField)
     *  off any Temporal as a Numeriek, with the §6.12 / Tabel 28 leegwaarde rule: an empty input yields an
     *  empty (null) result instead of NPE-ing the field access. One helper for all seven components.
     *  Idempotent. */
    private void registreerDatumDeelFunctie() {
        hulpFuncties.putIfAbsent("rsDatumDeel",
                "function java.math.BigDecimal rsDatumDeel(java.time.temporal.TemporalAccessor d, "
                + "java.time.temporal.TemporalField f) {\n"
                + "    return d == null ? null : java.math.BigDecimal.valueOf(d.getLong(f));\n"
                + "}\n\n");
    }

    /** Register the Java-bodied DRL helper for §13.4.17.17 "de absolute waarde van", carrying the §6.9 / Tabel
     *  20 leegwaarde rule (|leeg| = leeg). A null guard a Drools pattern constraint cannot express with a
     *  ternary. Idempotent. */
    private void registreerAbsFunctie() {
        hulpFuncties.putIfAbsent("rsAbs",
                "function java.math.BigDecimal rsAbs(java.math.BigDecimal x) {\n"
                + "    return x == null ? null : x.abs();\n"
                + "}\n\n");
    }

    /** Map a tijdsduur's declared unit (its result {@link Datatype}'s eenheid, e.g. TOKA's "jr" or a bare
     *  "jaar") to a {@link java.time.temporal.ChronoUnit} name. Normalised through the shared plural->singular
     *  reducer so "jaren"/"jaar"/"jr" all land on YEARS; returns null for kwartaal (no ChronoUnit) or any
     *  unrecognised unit, so the caller markers. */
    private static String chronoUnit(Datatype t) {
        io.github.yhavinga.regelspraak.model.Eenheid e = t.eenheidOf();
        if (e == null) return null;
        return switch (RegelSpraakModel.normaliseerTijdseenheid(e.afkorting())) {
            case "jaar", "jr"             -> "YEARS";
            case "maand", "mnd"           -> "MONTHS";
            case "week", "wk"             -> "WEEKS";
            case "dag"                    -> "DAYS";
            case "uur", "u"               -> "HOURS";
            case "minuut", "min"          -> "MINUTES";
            case "seconde", "secondes", "sec", "s"     -> "SECONDS";
            case "milliseconde", "millisecondes", "ms" -> "MILLIS"; // §6.10 spells the plural with -s, which
            default -> null;   // the -en reducer leaves unchanged; kwartaal (no ChronoUnit) / custom -> marker
        };
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
    /**
     * §13.4.17.51-.54 a SOM / MINIMUM / MAXIMUM over a gedimensioneerd attribuut, restricted to a
     * {@link Expr.DimensieSelectie}'s labels. The labels are resolved at build time (the "vanaf … t/m …"
     * interval / "in {…}" set already expanded against the dimension's declared order), so this is NOT an
     * accumulate over instances but a FIXED fold of the attribute's per-label values: the dimensioned
     * attribute reads as a {@code Map<label, value>} field and the aggregation folds {@code map.get(label)}
     * over exactly the selected labels.
     *
     * <p>§5.8.2 leegwaarde semantics: an empty contributor (an absent label, {@code map.get} → null) is
     * SKIPPED, not treated as 0; an all-empty fold yields the declared "of … als die er niet zijn" fallback,
     * else null (an empty sum is a leegwaarde, not 0). Lowered as a call to a registered DRL {@code function}
     * (folded in Java, so the DRL stays O(1) in the label count rather than an exponential mvel ternary),
     * passing the attribute's Map and the selected label keys. Returns null for any function without a
     * dimensieselectie, so the caller falls through to the instance path.
     */
    private String dimensieAggregatie(Expr.Functie f, Objecttype subject) {
        Expr.DimensieSelectie sel = f.dimensieSelectie();
        if (sel == null) return null;
        String fn = switch (f.kind()) {
            case SOM     -> "rsDimSom";
            case MINIMUM -> "rsDimMin";
            case MAXIMUM -> "rsDimMax";
            default -> null;
        };
        if (fn == null || f.argumenten().size() != 1 || !(f.argumenten().get(0) instanceof Expr.AttribuutRef a))
            return null;
        String map = attribuutGetter(a, subject);   // the dimensioned attribute reads as its Map<label,value> field
        if (bevatMarker(map)) return null;
        registreerDimensieFunctie(fn);
        StringJoiner keys = new StringJoiner(", ");
        for (String label : sel.labels()) keys.add("\"" + label.replace("\"", "") + "\"");
        String call = fn + "(" + map + ", java.util.Arrays.asList(" + keys + "))";
        // §5.8.2: an all-empty SOM is a leegwaarde — substitute the declared "of … als die er niet zijn"
        // fallback when present, else leave the helper's null (an empty sum, never a silent 0). MIN/MAX of
        // an empty set is likewise the helper's null (no §5.8.2 fallback form).
        if (f.kind() == Expr.FunctieKind.SOM && f.leegwaarde() != null)
            return "(" + call + " == null ? " + waarde(f.leegwaarde(), subject) + " : " + call + ")";
        return call;
    }

    /** Register the Java-bodied DRL helper that folds a dimensioned attribute's per-label values,
     *  null-skipping empties and returning null for an all-empty selection (§5.8.2). Idempotent. */
    private void registreerDimensieFunctie(String fn) {
        if (hulpFuncties.containsKey(fn)) return;
        String body = switch (fn) {
            case "rsDimSom" -> "        if (v != null) acc = (acc == null) ? v : acc.add(v);\n";
            case "rsDimMin" -> "        if (v != null && (acc == null || v.compareTo(acc) < 0)) acc = v;\n";
            case "rsDimMax" -> "        if (v != null && (acc == null || v.compareTo(acc) > 0)) acc = v;\n";
            default -> "";
        };
        hulpFuncties.put(fn,
                "function java.math.BigDecimal " + fn + "(java.util.Map m, java.util.List keys) {\n"
                + "    java.math.BigDecimal acc = null;\n"
                + "    for (Object k : keys) {\n"
                + "        java.math.BigDecimal v = (m == null) ? null : (java.math.BigDecimal) m.get(k);\n"
                + body
                + "    }\n"
                + "    return acc;\n"
                + "}\n\n");
    }

    private String concatenatieAggregatie(Expr.Functie f, Objecttype subject) {
        // An unlowered restriction (a dimensieselectie captured as an Onopgelost filter, p5) must marker
        // the whole fold too — otherwise the value-fold would emit its unrestricted ternary while the
        // resolver already WARNed that the aggregation is "buiten beschouwing". Bail so the caller falls
        // through to the marker path. (A value-fold otherwise carries no filter, so this only fires here.)
        if (f.filter() instanceof Conditie.Onopgelost) return null;
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
        // §4.11/§5.8.1 telling over a concatenation of expressies ("het aantal X, Y en Z"): the
        // count is the UNION of the per-operand sets. When the resolver could OR the per-operand
        // kenmerk filters (f.filter() is the disjunction), this lowers to ONE accumulate whose
        // source pattern carries that OR — an instance matching several operands counts once. A
        // union it could not resolve has no filter -> marker, never a count over the first operand.
        if (f.kind() == Expr.FunctieKind.AANTAL && f.argumenten().size() > 1 && f.filter() == null) return null;
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
        // §13.4.2 "die/dat <predicaat>" subselectie on the element ("… van de reis die een kind
        // zijn") -> an extra constraint on the accumulate source pattern, so only the matching
        // instances are aggregated. A filter the resolver could not structure (Onopgelost) lowers to
        // a marker here -> bail, so the aggregation is a visible no-op rather than counting EVERY
        // instance (a silent miscompile).
        String filterScope = scope;
        if (f.filter() != null) {
            String fc = conditie(f.filter(), elem);  // Kenmerk -> bare element property; Onopgelost -> marker
            if (bevatMarker(fc)) return null;
            filterScope = scope + ", " + fc;
        }
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
                    + "( " + filterScope + ", " + ident(a.attribuut(), false) + " != null ) ; "
                    + cnt + " : count(" + ev + "), " + var + " : sum( " + ev + "." + getter(a.attribuut()) + "() ) )");
            String fallback = f.leegwaarde() != null ? waarde(f.leegwaarde(), subject) : "null";
            return "(" + cnt + " == 0 ? " + fallback + " : " + var + ")";
        }

        String elementExpr = ev;
        if (!fn.equals("count") && arg instanceof Expr.AttribuutRef a)
            elementExpr = ev + "." + getter(a.attribuut()) + "()";
        accumulates.add("accumulate( " + ev + " : " + ident(elem.naam, true)
                + "( " + filterScope + " ) ; " + var + " : " + fn + "( " + elementExpr + " ) )");
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
    private String conditie(Conditie c, Objecttype subject) { return conditie(c, subject, false); }

    /** Lower a condition to an mvel constraint. {@code alsVoorwaarde} marks a rule-GUARD / filter context,
     *  where the §5.3 both-empty rule applies; it is false when this is the NEGATION of a consistentie
     *  assertion (built by {@link #schendingConstraint}'s complement / negeer tail), because there a
     *  both-empty "fout" must still FIRE the violation (Tabel 44), so the guard must NOT be added. */
    private String conditie(Conditie c, Objecttype subject, boolean alsVoorwaarde) {
        if (c instanceof Conditie.Vergelijking v) {
            // §5.1 Tabel 32: a comparison across convertible-but-different units restates
            // the right operand in the left's unit first ("24 mnd > 2 jr", not "24 > 2").
            String[] o = vergelijkingsOperanden(operand(v.links(), subject), v.links().type(),
                    operand(v.rechts(), subject), v.rechts().type());
            // An operand the resolver/transpiler could not lower (an unresolvable navigation, an
            // under-specified dimensioned read) makes the whole comparison unfaithful — and emitting it raw
            // would be uncompilable DRL (a Map-vs-number term sinks the package). Make the guard an honest
            // always-false marker instead.
            if (bevatMarker(o[0]) || bevatMarker(o[1])) return onopgelosteConstraint("voorwaarde", v.bron());
            String cmp = o[0] + " " + drlOp(v.op()) + " " + o[1];
            // §5.3 / typeringen Tabel 38: a NON-numeriek "gelijk aan" with BOTH operands empty is "fout"
            // (a foutmelding), NOT "waar" — so as a rule GUARD the condition is not satisfied and the rule
            // must NOT fire. Plain mvel reads "null == null" as true (it would fire), so exclude the
            // both-empty case (p40). A numeriek both-empty stays "waar"/fires per Tabel 37 (the §5.3 prose
            // says "onwaar" there — a spec contradiction left to the existing Tabel-37 reading).
            if (alsVoorwaarde && v.op() == Conditie.Operator.GELIJK && nietNumeriekVergelijking(v)
                    && !bevatMarker(o[0]) && !bevatMarker(o[1])
                    && !(v.links() instanceof Expr.Literal) && !(v.rechts() instanceof Expr.Literal))
                return "(" + cmp + " && !(" + o[0] + " == null && " + o[1] + " == null))";
            return cmp;
        }
        if (c instanceof Conditie.Predicaat p) {
            // A leeg/gevuld predicate over a TO-ONE navigated operand cannot use the null-safe "!." form: "!."
            // short-circuits the whole term to a NON-match on a null reference, which is the WRONG answer for
            // `is leeg` (typeringen Tabel 54: an absent value IS leeg -> waar). Guard the reference explicitly
            // so an absent reference reads as EMPTY — leeg = (rol == null || rol.attr == null); gevuld = (rol
            // != null && rol.attr != null) — the "." after the short-circuit guard never dereferences null.
            String navLeeg = genavigeerdLeegGevuld(p, subject);
            if (navLeeg != null) return navLeeg;
            String o = operand(p.operand(), subject);
            if (bevatMarker(o)) return onopgelosteConstraint("voorwaarde", p.bron());  // e.g. an under-specified dimensioned read
            return switch (p.soort()) {
                case LEEG   -> o + " == null";
                case GEVULD -> o + " != null";
                default     -> onopgelosteConstraint("predicaat " + p.soort(), p.bron());
            };
        }
        // §8.1.8 kenmerk test: a boolean property constraint inside the subject pattern. The property form
        // (not getX()) resolves to the boolean isX() getter the fact declares. A navigated kenmerk reads
        // the property through the to-one role's reference field, guarded against a null reference (mvel is
        // not null-safe, so a missing partner must read as "not gepensioneerd", not throw): the constraint
        // becomes "partner != null && partner.gepensioneerd == true".
        if (c instanceof Conditie.Kenmerk k) {
            // §13.4.7 feitcreatie hosts a doel-kenmerk on the $doel pattern: a kenmerk tagged with an
            // eigenaar emits ONLY on that type's pattern (a marker elsewhere), so it never lands on the
            // anker — even when the anker happens to declare a same-named kenmerk. An untagged kenmerk
            // (the single-subject rule) is unconstrained and emits on whatever pattern hosts the voorwaarde.
            if (k.eigenaar() != null && k.eigenaar() != subject)
                return onopgelosteConstraint("kenmerk '" + k.kenmerk() + "' hoort bij " + k.eigenaar().naam, c.bron());
            String prop = ident(k.kenmerk(), false) + " == " + (k.negatie() ? "false" : "true");
            if (k.navigatie() == null) return prop;
            String nav = ident(k.navigatie(), false);
            return nav + " != null && " + nav + "." + prop;
        }
        if (c instanceof Conditie.Samengesteld s) {
            List<String> delen = new ArrayList<>();
            for (Conditie deel : s.onderdelen()) delen.add("(" + conditie(deel, subject, alsVoorwaarde) + ")");
            if (delen.isEmpty()) return "true";
            // An unresolved sub-condition cannot be faithfully combined. Splicing its always-false
            // marker into one operand is wrong under the NEGATED quantifiers (geen/ten hoogste/
            // precies, which wrap the operands in !(…)): there the dropped criterion flips into a
            // positive firing decision. So mark the WHOLE compound a no-op (rule never fires) rather
            // than risk a silently-wrong gate — for every quantifier.
            if (delen.stream().anyMatch(DrlTranspiler::bevatMarker)) return onopgelosteConstraint("voorwaarde", s.bron());
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
        return onopgelosteConstraint("voorwaarde", c.bron());
    }

    /** An unresolved CONDITION lowers to an always-false constraint, so the rule is a visible no-op
     *  that COMPILES — unlike the value-side {@code … null} marker, a bare {@code null} is not a
     *  boolean pattern constraint ({@code Subject( null )} fails to compile, which would sink the
     *  whole DRL). Still carries the "/* TODO *&#47;" text, so {@link #bevatMarker} flags it. */
    private static String onopgelosteConstraint(String wat, String bron) {
        return "false /* TODO " + wat + ": " + bron + " */";
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
    /** A leeg/gevuld predicate over a TO-ONE navigated operand, with the navigation root guarded explicitly
     *  (so a null reference reads as an empty value per typeringen Tabel 53/54, not a Drools "!." non-match),
     *  or null when the operand is not such a navigation (own attribute, to-many, unresolved → the caller's
     *  plain form applies). The "." is reached only after the short-circuit root guard, so it never NPEs. */
    private String genavigeerdLeegGevuld(Conditie.Predicaat p, Objecttype subject) {
        if (p.soort() != Conditie.Predicaatsoort.LEEG && p.soort() != Conditie.Predicaatsoort.GEVULD) return null;
        if (!(p.operand() instanceof Expr.AttribuutRef a)) return null;
        NaviRef n = naviResolve(a, subject);
        if (n.soort() != NaviSoort.GENAVIGEERD) return null;   // own/to-many/unresolved → the caller's plain form
        String wortel = ident(n.rol(), false), pad = wortel + "." + ident(a.attribuut(), false);
        return p.soort() == Conditie.Predicaatsoort.LEEG
                ? "(" + wortel + " == null || " + pad + " == null)"
                : "(" + wortel + " != null && " + pad + " != null)";
    }

    private String operand(Expr e, Objecttype subject) {
        if (e instanceof Expr.DimensieCel c)
            // §3.6 a dimensioned cell on the CONDITION side is deferred to a marker (the value/then side
            // lowers it). A Drools PATTERN constraint cannot host the null-guarded ternary the value form
            // uses (the inline "? :" mis-parses, and a null cell vs a number is a build-breaking type error),
            // and a correct null-safe cell comparison needs a guarded conjunction this single-operand path
            // cannot express. So it markers honestly (-> false voorwaarde): the rule does not fire, never a
            // wrong or uncompilable comparison. Carded.
            return todo("dimensiecel in voorwaarde", c.bron());
        if (e instanceof Expr.AttribuutRef a) {
            // §3.6: a bare value read of a gedimensioneerd attribuut is under-specified (its property is a
            // Map, so "prijsInJaar > 5" is a Map-vs-number type error that fails the whole DRL build). A
            // legitimate dimensioned read is an Expr.DimensieCel (above); a bare one is a marker.
            if (isGedimensioneerd(a)) return todo("ongedimensioneerde lezing van gedimensioneerd attribuut (§3.6)", a.bron());
            return attribuutOperand(a, subject);
        }
        if (e instanceof Expr.ParameterRef p) return ident(p.parameter().naam(), false);
        if (e instanceof Expr.Literal lit)    return literal(lit);
        if (e instanceof Expr.Afgerond af) {
            // §13.4.17.13/.25: an afronding wraps a getalexpressie — and "de wortel van X" carries a MANDATORY
            // afronding, so a condition-side wortel is always Afgerond(Functie(WORTEL)), never the bare Functie.
            // setScale is a pure method call (no ternary), valid in a pattern constraint, so descend with the
            // operand-side (bare-property) lowering. A marker basis (e.g. an afronding over an unlowerable
            // arithmetic operand — those Binair/Begrensd forms use ternaries and stay markers here) propagates.
            String b = operand(af.basis(), subject);
            return bevatMarker(b) ? b : afgerondeTerm(b, af);
        }
        if (e instanceof Expr.Functie f) {
            // §13.4.17 a SCALAR builtin (abs / date-component / datum-met / wortel / paasdag / tijdsduur) is a
            // valid pattern-constraint operand once its arguments lower to bare properties — so reuse the one
            // value-side routine, swapping its arg lowering to operand() (the field name, not a $subject getter,
            // since $subject is not bound inside its own pattern). The AGGREGATIONS (som/min/max over instances)
            // return null there: they need a `when` accumulate and cannot live inside a single constraint, so
            // they fall through to an honest marker (-> the caller's `false /* TODO */`).
            String scalar = scalarFunctie(f, x -> operand(x, subject));
            if (scalar != null) return scalar;
            return todo("aggregatie-functie in voorwaarde " + f.kind(), f.bron());
        }
        return todo("operand", e.bron());
    }

    /** The condition-side property access for an attribute — the bare own-property, a null-safe "!." to-one
     *  navigation, or a marker for an absent/naar-veel/unresolvable read. Shared by the plain operand and the
     *  dimensioned-cell read (which needs the Map property before indexing a label), so the latter bypasses
     *  the §3.6 under-specified guard. Drools "!." yields null (a leegwaarde) for a null to-one reference
     *  instead of NPE-ing the mvel "." dereference. */
    private String attribuutOperand(Expr.AttribuutRef a, Objecttype subject) {
        NaviRef n = naviResolve(a, subject);
        return switch (n.soort()) {
            case EIGEN       -> ident(a.attribuut(), false);
            case GENAVIGEERD -> ident(n.rol(), false) + "!." + ident(a.attribuut(), false);
            case NAAR_VEEL   -> todo("naar-veel-navigatie", a.bron());
            default          -> todo("navigatie", a.bron());   // AFWEZIG / ONOPGELOST
        };
    }

    // ---------------- leaves & helpers ----------------
    /** The integer exponent when {@code e} is an integer-valued numeric literal ("2" in "tot de
     *  macht 2"), else null — lets an integer power lower exactly via {@code BigDecimal.pow(int)}
     *  rather than through Math.pow's double. Reads the number as {@link #literal} does, so a
     *  rational that reduces to a whole number ("4/2" -> 2) is an exact exponent too, while a true
     *  fraction ("3/2") or non-terminating quotient ("1/3") falls through to the Math.pow path. */
    private static Integer geheelExponent(Expr e) {
        if (!(e instanceof Expr.Literal lit) || !lit.type().isNumeriek()) return null;
        java.math.BigDecimal[] r = rationaal(lit.waarde());
        if (r == null) return null;
        try {
            java.math.BigDecimal bd = r[0].divide(r[1]); // exact value; throws when non-terminating
            // BigDecimal.pow(int) is exact only for 0..999999999; a negative or fractional exponent
            // falls through to the Math.pow path (which handles them, at double precision).
            if (bd.signum() >= 0 && bd.stripTrailingZeros().scale() <= 0) {
                int n = bd.intValueExact();
                if (n <= 999_999_999) return n;
            }
        } catch (ArithmeticException ignore) {}
        return null;
    }

    /** Numerator and denominator of a numeric literal's leading number (denominator ONE for a
     *  plain integer or decimal), or null when there is no number. Recognises the §13.2.5 rational
     *  forms — "a/c" and the mixed "a_b/c" (= a + b/c, the fraction taking the whole part's sign) —
     *  alongside a bare integer and an "a,b" (or "a.b") decimal. Earlier readers matched only the
     *  leading "a", silently dropping "/c", so "maal 4/2" lowered to *4 and "macht 4/2" to pow(4). */
    static java.math.BigDecimal[] rationaal(String bron) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(-?\\d+)(?:[.,](\\d+)|(?:_(\\d+))?/(\\d+))?").matcher(bron);
        if (!m.find()) return null;
        java.math.BigDecimal heel = new java.math.BigDecimal(m.group(1));
        if (m.group(2) != null) // a,b decimal
            return new java.math.BigDecimal[]{ new java.math.BigDecimal(m.group(1) + "." + m.group(2)),
                    java.math.BigDecimal.ONE };
        if (m.group(4) == null) // bare integer
            return new java.math.BigDecimal[]{ heel, java.math.BigDecimal.ONE };
        java.math.BigDecimal noemer = new java.math.BigDecimal(m.group(4));
        if (m.group(3) == null) // a/c
            return new java.math.BigDecimal[]{ heel, noemer };
        java.math.BigDecimal teller = heel.abs().multiply(noemer).add(new java.math.BigDecimal(m.group(3)));
        // The sign comes from the leading '-' in the text, not heel.signum(): for "-0_1/2" the whole
        // part is BigDecimal("-0"), whose signum() is 0, so signum() would drop the minus (-0.5 -> +0.5).
        return new java.math.BigDecimal[]{ m.group(1).startsWith("-") ? teller.negate() : teller, noemer }; // a_b/c
    }

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
        // unit scaling (.multiply) and rounding (.setScale) compile in mvel. A §13.2.5 rational
        // ("4/2", "3/2", "1_1/2") is lowered to its exact value when the quotient terminates, else
        // to a DECIMAL64 division so it still computes (e.g. 1/3).
        java.math.BigDecimal[] r = rationaal(lit.waarde());
        if (r == null) return todo("literal", lit.waarde());
        try {
            return "new java.math.BigDecimal(\"" + r[0].divide(r[1]).toPlainString() + "\")"; // exact
        } catch (ArithmeticException nietEindig) {
            return "new java.math.BigDecimal(\"" + r[0].toPlainString() + "\").divide(new java.math.BigDecimal(\""
                    + r[1].toPlainString() + "\"), java.math.MathContext.DECIMAL64)";
        }
    }

    /** Wrap an emitted value in a unit conversion when its unit differs from (but is
     *  convertible to) the target unit; otherwise return it unchanged (§13.3.5.2).
     *  Never wraps an unlowered placeholder — that would emit (null).multiply(...). */
    /** Operands for a unit-aware COMPARISON, scaled to AVOID {@link #omreken}'s lossy ratio. omreken
     *  restates the right operand in the left's unit by {@code factorVan / factorNaar}, which truncates
     *  for a non-terminating quotient (mnd→jr = 1/12 = 0.0833…), so "24 mnd gelijk aan 2 jr" lowered to
     *  "2 != 1.9999…" — a false-positive violation (p43). For two SIMPLE convertible units this instead
     *  restates BOTH operands in their shared canonical unit by the DECLARED (exact) factorNaarCanoniek,
     *  so the comparison scales exactly (24 == 24). No conversion when units match or are absent; a
     *  composite (rate) unit keeps the single-operand ratio (its precision edge is carded). Returns
     *  {leftOut, rightOut}. */
    private static String[] vergelijkingsOperanden(String l, Datatype lt, String r, Datatype rt) {
        io.github.yhavinga.regelspraak.model.Eenheid el = lt.eenheidOf(), er = rt.eenheidOf();
        if (el == null || er == null || !el.convertibleNaar(er) || !el.vereistOmrekening(er))
            return new String[]{l, r};
        if (el.noemer() == null && er.noemer() == null)
            return new String[]{naarCanoniek(l, el), naarCanoniek(r, er)};
        return new String[]{l, omreken(r, rt, lt)};   // composite (rate) unit: keep the existing conversion
    }

    /** Restate a comparison operand in its system's canonical unit by the DECLARED (exact) factor —
     *  a multiplication, never the lossy division omreken uses. Identity for a canonical/unitless
     *  operand or a placeholder marker. */
    private static String naarCanoniek(String expr, io.github.yhavinga.regelspraak.model.Eenheid e) {
        if (expr.contains("/* TODO") || e.factorNaarCanoniek() == null
                || e.factorNaarCanoniek().compareTo(java.math.BigDecimal.ONE) == 0) return expr;
        return "(" + expr + ").multiply(new java.math.BigDecimal(\"" + e.factorNaarCanoniek().toPlainString() + "\"))";
    }

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

    /** §13.4.17.25 afronding: round a basis term to {@code af.decimalen()} via the matching
     *  {@link java.math.RoundingMode}. A pure {@code .setScale(...)} method call — no ternary — so it is
     *  valid in BOTH a modify-block (value side) and a pattern constraint (condition side); shared by
     *  {@code waarde} and {@code operand} so a wortel/getal afronding lowers identically in either position. */
    private static String afgerondeTerm(String basis, Expr.Afgerond af) {
        String mode = switch (af.modus()) {
            case NAAR_BENEDEN -> "FLOOR"; case NAAR_BOVEN -> "CEILING";
            case REKENKUNDIG -> "HALF_UP"; case RICHTING_NUL -> "DOWN"; case WEG_VAN_NUL -> "UP";
        };
        return "(" + basis + ").setScale(" + af.decimalen() + ", java.math.RoundingMode." + mode + ")";
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
