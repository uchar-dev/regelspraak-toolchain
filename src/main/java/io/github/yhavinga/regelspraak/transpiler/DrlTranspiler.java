package io.github.yhavinga.regelspraak.transpiler;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Rationaal;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;
import io.github.yhavinga.regelspraak.transpiler.kern.Dagsoorten;
import io.github.yhavinga.regelspraak.transpiler.kern.Dekking;
import io.github.yhavinga.regelspraak.transpiler.kern.Literalen;
import io.github.yhavinga.regelspraak.transpiler.kern.Operatoren;
import io.github.yhavinga.regelspraak.transpiler.kern.Tijdrekening;

import static io.github.yhavinga.regelspraak.transpiler.kern.Namen.commentVeilig;
import static io.github.yhavinga.regelspraak.transpiler.kern.Namen.drlString;
import static io.github.yhavinga.regelspraak.transpiler.kern.Namen.escapeRegelNaam;
import static io.github.yhavinga.regelspraak.transpiler.kern.Namen.getter;
import static io.github.yhavinga.regelspraak.transpiler.kern.Namen.ident;
import static io.github.yhavinga.regelspraak.transpiler.kern.Namen.setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    /** The {@link io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime} static helpers the rules called (method
     *  names), emitted once after the fact imports as {@code import function …RegelSpraakRuntime.<naam>;}
     *  lines — single-runtime principle: the generated DRL carries NO {@code function} bodies; the ONE rs* implementation
     *  is the runtime (which must therefore be on the DRL-compiling classpath, see docs/ENGINE.md). Only the
     *  helpers actually used are imported, sorted for stable output. */
    private final java.util.TreeSet<String> runtimeImports = new java.util.TreeSet<>();
    /** §13.4.6/§9 set iff an objectcreatie emitted a {@link io.github.yhavinga.regelspraak.runtime.CreationMarker} idempotence
     *  guard, so the header imports that runtime fact class exactly when it is referenced (like the Segment import). */
    private boolean creationMarkerNodig;
    /** §8.1.9 set iff a referenced rule emitted a {@link io.github.yhavinga.regelspraak.runtime.RuleFired} "is gevuurd"
     *  marker (or a condition read one), so the header imports that runtime fact class exactly when referenced. */
    private boolean ruleFiredNodig;
    /** §8.1.9 the rule names referenced by a live "regelversie X is gevuurd" predicate — walked from the model
     *  ONCE up front, so only a referenced rule's consequence inserts the {@link io.github.yhavinga.regelspraak.runtime.RuleFired}
     *  marker (opt-in emission, no fact-base bloat). Populated at {@link #run} start. */
    private java.util.Set<String> gevuurdReferenties = java.util.Set.of();
    /** Set exactly where emitted rule text references the §4.2 rekendatum global (a §8.4.3 periode-window, a
     *  §5.3 Rekendatum/Rekenjaar value read), so the global is spliced into the header iff actually used. */
    private boolean rekendatumNodig;
    /** §9.8 dagsoort predicates the rules referenced: DRL global name ({@code rsDagsoort_<Naam>}) -> the
     *  predicate compiled by the SHARED {@link Dagsoorten} compiler. Declared as
     *  {@code global java.util.function.Predicate rsDagsoort_<Naam>;} after the parameter globals; a session
     *  builder must supply them from {@link #dagsoortGlobalen()} — the analog of the parameter globals. */
    private final java.util.LinkedHashMap<String, java.util.function.Predicate<java.time.LocalDate>> dagsoortGlobalen
            = new java.util.LinkedHashMap<>();
    private final Dagsoorten dagsoorten;

    public DrlTranspiler(RegelSpraakModel model) { this.model = model; this.dagsoorten = new Dagsoorten(model); }

    public static String transpile(RegelSpraakModel model) { return new DrlTranspiler(model).run(); }

    /** As {@link #transpile} but also exposes the coverage manifest — one {@link Dekking} verdict per
     *  construct, per soort in model-list order (comparable per soort with the exec back-end's). */
    public static DrlTranspiler build(RegelSpraakModel model) {
        DrlTranspiler t = new DrlTranspiler(model);
        t.drl0 = t.run();
        return t;
    }
    private String drl0;
    private final List<Dekking> dekking = new ArrayList<>();
    public String drl() { return drl0; }
    public List<Dekking> dekking() { return dekking; }
    /** The §9.8 dagsoort predicates the emitted DRL declares as globals (name -> predicate): a session builder
     *  must {@code setGlobal} each before firing — exactly like the parameter globals it already supplies. */
    public java.util.Map<String, java.util.function.Predicate<java.time.LocalDate>> dagsoortGlobalen() {
        return java.util.Collections.unmodifiableMap(dagsoortGlobalen);
    }

    /** Record a {@link io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime} helper as used — it surfaces as one
     *  header {@code import function} line — and return its name for call-site splicing. Idempotent. */
    private String fnImport(String naam) { runtimeImports.add(naam); return naam; }

    private String run() {
        gevuurdReferenties = model.gevuurdReferenties();   // §8.1.9 opt-in: only these rules mark their firing
        out.append("package io.github.yhavinga.regelspraak.generated;\n\n");
        // Import the fact class for each object type so Drools resolves the patterns.
        for (Objecttype ot : model.objecttypen.values())
            out.append("import ").append(FACT_PACKAGE).append('.').append(ident(ot.naam, true)).append(";\n");
        if (!model.objecttypen.isEmpty()) out.append('\n');
        int naImports = out.length();   // the runtime `import function` lines (if any) are spliced in here
        // Parameters become globals the runtime supplies. A §13.3.6 TEMPORAL parameter ("voor elk jaar") supplies a
        // timeline (a List<Segment>) rather than a scalar — only a §7.3.2 tijdsevenredig base reads it (any scalar use
        // markers in waarde()).
        for (Parameter p : model.parameters.values())
            out.append("global ").append(p.granulariteit() != null ? "java.util.List" : javaType(p.datatype())).append(' ')
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
        int naHeader = out.length();   // dagsoort-predicate globals (if any) are spliced in here, after the header

        for (ResolvedRegel r : model.resolvedRegels) meet("regel", r.naam(), () -> regel(r));
        for (io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie oc : model.objectCreaties) meet("objectcreatie", oc.naam(), () -> objectCreatie(oc));
        for (io.github.yhavinga.regelspraak.ir.ResolvedVerdeling v : model.verdelingen) meet("verdeling", v.naam(), () -> verdeling(v));
        for (io.github.yhavinga.regelspraak.ir.ResolvedConsistentie c : model.consistenties) meet("consistentie", c.naam(), () -> consistentie(c));
        for (io.github.yhavinga.regelspraak.ir.ResolvedUniciteit u : model.uniciteiten) meet("uniciteit", u.naam(), () -> uniciteit(u));
        for (io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie u : model.uniciteitUnies) meet("uniciteit-unie", u.naam(), () -> uniciteitUnie(u));
        for (io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie f : model.feitCreaties) meet("feitcreatie", f.naam(), () -> feitCreatie(f));
        // Splice the deferred header blocks BACK TO FRONT so the earlier offset stays valid.
        // §8.4.3 periodevergelijking / §5.3 Rekendatum-als-waarde reference the rekendatum global from a rule
        // body; the flag is set EXACTLY where that text is emitted (never an undeclared-global build error, never
        // an over-eager scan) — declare it here iff the §4.2 geldigheid trigger did not already.
        if (rekendatumNodig && !tijdgebonden) out.insert(naHeader, "global java.time.LocalDate rekendatum;\n\n");
        if (!dagsoortGlobalen.isEmpty()) {
            StringBuilder g = new StringBuilder();
            for (String naam : dagsoortGlobalen.keySet())
                g.append("global java.util.function.Predicate ").append(naam).append(";\n");
            out.insert(naHeader, g.append('\n'));
        }
        if (!runtimeImports.isEmpty() || creationMarkerNodig || ruleFiredNodig) {
            StringBuilder imp = new StringBuilder();
            // The tijdlijn helpers construct Segments via a `Segment.class` argument at their call sites; the
            // fact class is imported iff the emitted text references it — DERIVED from the output, so a new
            // helper call site can never forget to register the import separately.
            if (out.indexOf("Segment.class") >= 0)
                imp.append("import ").append(FACT_PACKAGE).append(".Segment;\n");
            if (creationMarkerNodig)   // §13.4.6/§9 the objectcreatie idempotence control fact (a runtime class, not a per-model bean)
                imp.append("import io.github.yhavinga.regelspraak.runtime.CreationMarker;\n");
            if (ruleFiredNodig)        // §8.1.9 the "is gevuurd" execution-status control fact (a runtime class, not a per-model bean)
                imp.append("import io.github.yhavinga.regelspraak.runtime.RuleFired;\n");
            for (String fn : runtimeImports)
                imp.append("import function io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime.").append(fn).append(";\n");
            out.insert(naImports, imp.append('\n'));
        }
        return out.toString();
    }

    /** Emit one construct and record its manifest verdict. Classification wraps the documented marker
     *  invariant rather than re-deriving it: every non-lowered path in this back-end leaves a
     *  {@code /* TODO *&#47;} placeholder ({@link Frag#todo} / {@link #onopgelosteConstraint} /
     *  {@link #todo}) or an {@code // onopgelost} comment clause in the emitted text, and a clean
     *  rule contains neither, so the construct's own DRL slice decides LOWERED vs MARKER. The scan
     *  runs over the slice with its STRING LITERALS blanked ({@link #zonderStrings}): user text (a
     *  Tekst/enum literal, an escaped rule name) is the one channel that could counterfeit the
     *  sentinel on a lowered rule — a {@code STRING_TEXT} cannot span lines or contain a quote, so
     *  outside quotes only the marker emitters can produce it. A MARKER also raises a deduplicated
     *  WARNING (a semantically inert rule must never be silent). */
    private void meet(String soort, String naam, Runnable emitter) {
        int start = out.length();
        emitter.run();
        String tekst = zonderStrings(out.substring(start));
        boolean marker = tekst.contains("/* TODO") || tekst.contains("// onopgelost");
        Dekking d = marker ? Dekking.marker(soort, naam, markerReden(tekst)) : Dekking.lowered(soort, naam);
        dekking.add(d);
        d.meld(model);
    }

    /** Blank the contents of double-quoted segments (emitted string literals and quoted rule names).
     *  A raw {@code "} or newline cannot occur inside either (STRING_TEXT excludes both; escapeRegelNaam
     *  escapes them), but an ESCAPED quote {@code \"} can — the scan honours backslash escapes so a
     *  quoted-name boundary is never mispaired. */
    private static String zonderStrings(String tekst) {
        return tekst.replaceAll("\"(?:\\\\.|[^\"\\\\\n])*\"", "\"\"");
    }

    /** The first placeholder's own text as the manifest reason: the body of the first
     *  {@code /* TODO … *&#47;} or the remainder of the first {@code // onopgelost …} line. */
    private static String markerReden(String tekst) {
        int todo = tekst.indexOf("/* TODO"), onop = tekst.indexOf("// onopgelost");
        if (todo >= 0 && (onop < 0 || todo < onop)) {
            int einde = tekst.indexOf("*/", todo);
            return tekst.substring(todo + 3, einde < 0 ? tekst.length() : einde).trim();
        }
        int einde = tekst.indexOf('\n', onop);
        return tekst.substring(onop + 3, einde < 0 ? tekst.length() : einde).trim();
    }

    /**
     * One DRL rule under construction: an ordered list of "when" clauses (patterns, accumulate CEs and
     * guards, in the exact textual order they must appear) and a "then" consequence body. {@link #render}
     * owns the rule header, the 4-space when-line indent, and the rule-name escaping — the structure that
     * was hand-appended, identically, by every emitter. This is rule-ASSEMBLY only: the clause and value
     * strings are still produced by the leaf emitters; here they are composed, not re-derived. An inert
     * no-op rule (the resolver could not lower it) is just a Regel whose clauses are a {@code // onopgelost}
     * comment and an {@code eval(true)} with an empty consequence — so the no-op is built structurally,
     * not special-cased in the renderer.
     */
    private final class Regel {
        private final String naam;
        private final List<String> when = new ArrayList<>();
        private String then = "";
        private boolean noLoop = true;

        Regel(String naam) { this.naam = naam; }

        /** §9.10 fase-2: drop {@code no-loop} so the rule can forward-chain on its OWN inserts (a recursief-group
         *  objectcreatie must reach the fixpoint). Safe ONLY because the §13.4.6/§9 idempotence guard already caps
         *  each subject at one creation — it, not no-loop, is the anti-runaway control here. */
        Regel zonderNoLoop() { this.noLoop = false; return this; }

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

        /** Prepend a line to the consequence body — used to inject the §8.1.9 {@code RuleFired} insert at the
         *  TOP of the {@code then} (before any changed-value guard), so a no-op modify still records "gevuurd". */
        Regel danVoor(String prefix) { this.then = prefix + this.then; return this; }

        String render() {
            StringBuilder b = new StringBuilder();
            // §9.10 fase-1 namespacing: the ENGINE-VISIBLE header alone gets the "<groep>: " prefix; every DATA
            // KEY below (RuleFired/CreationMarker/Inconsistentie `regel ==`) keeps the BARE name. Non-grouped
            // rules are unchanged (geëmitteerdeRegelNaam is identity for them).
            b.append("rule \"").append(escapeRegelNaam(model.geëmitteerdeRegelNaam(naam))).append("\"\n");
            b.append("    dialect \"mvel\"\n").append(noLoop ? "    no-loop true\n" : "").append("when\n");
            for (String c : when) b.append("    ").append(c).append('\n');
            b.append("then\n").append(then).append("end\n\n");
            return b.toString();
        }
    }

    /** The per-rule accumulate collector (no-mutable-build-state): value/condition lowering RETURNS its hoisted
     *  accumulate/eval clauses by appending to this explicitly-threaded context — a local of each rule
     *  emitter, passed as a parameter, never a field — so clauses from one rule can structurally never
     *  leak into the next, and a bailing lowering path simply abandons its context slice ({@link #aggTerug})
     *  instead of depending on a hidden reset. {@code n} numbers the {@code $accN}/{@code $nN} bindings. */
    private static final class Acc {
        final List<String> clausules = new ArrayList<>();
        int n;
    }

    /** §9.5 the consequence shared by the three inconsistency-flagging rules (consistentie, uniciteit,
     *  uniciteit-unie): insert an Inconsistentie carrying the rule name and the offending subject. The
     *  name is escaped to match the {@link #inconsistentieGuard} that keys off the same literal. */
    private String inconsistentieDan(String naam) {
        return "    insert(new " + FACT_PACKAGE + ".Inconsistentie(\""
                + escapeRegelNaam(naam) + "\", " + SUBJECT + "));\n";
    }

    /** §8.1.9 prepend a {@code RuleFired} insert to {@code rule}'s consequence iff {@code naam}'s firing is
     *  referenced by a live "regelversie X is gevuurd" predicate (§8.1.9 opt-in — an unreferenced rule marks
     *  nothing). The insert is UNCONDITIONAL and at the TOP of the {@code then}, OUTSIDE the changed-value
     *  guard: "gevuurd" means the voorwaardendeel held and the consequence ran (§8.1.9: "of er aan de
     *  voorwaarden in het voorwaardendeel van de regel is voldaan"), so even a no-op modify is "gevuurd". A
     *  rule firing for N subjects inserts N RuleFired facts with the same {@code regel}; the reader's exists
     *  collapses them, and a not-exists guard here would change THIS rule's firing semantics, so it is
     *  deliberately omitted (bounded bloat: #referenced-rules × #subjects). The name is escaped to match the
     *  {@link #ruleFiredGuard} the reader keys off the same literal. */
    private void markeerGevuurd(Regel rule, String naam) {
        if (!gevuurdReferenties.contains(naam)) return;
        ruleFiredNodig = true;
        rule.danVoor("    insert(new RuleFired(\"" + escapeRegelNaam(naam) + "\"));\n");
    }

    /** §8.1.9 the "regelversie X is gevuurd" reader's hoisted LHS existential — mirrors the "is inconsistent"
     *  {@code exists Inconsistentie( regel == "X" )}, keyed on the same escaped rule-name literal that
     *  {@link #markeerGevuurd} inserts. */
    private String ruleFiredGuard(String naam) {
        return "exists RuleFired( regel == \"" + escapeRegelNaam(naam) + "\" )";
    }

    /** §13.4.8 consistentieregel. The assertion MUST hold, so the rule fires on its
     *  VIOLATION — the negated condition — and inserts an Inconsistentie carrying the
     *  rule name and the offending subject (§9.5: "inconsistent" as the result, usable by
     *  other rules). A condition whose negation is not expressible (compound/predicate
     *  beyond leeg/gevuld) or an unresolved subject becomes an explicit marker. */
    private void consistentie(io.github.yhavinga.regelspraak.ir.ResolvedConsistentie c) {
        Acc acc = new Acc();
        Objecttype subject = c.subject();
        String constraint = subject != null ? schendingConstraint(acc, c.assertie(), subject) : null;
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
            rule.clausule("// onopgelost consistentieregel: " + commentVeilig(c.bron())).clausule("eval(true)");
            out.append(rule.render());
            return;
        }
        rule.clausule(SUBJECT + " : " + ident(subject.naam, true) + "( " + constraint + " )")
            .clausules(acc.clausules)
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
    private String schendingConstraint(Acc acc, Conditie assertie, Objecttype subject) {
        if (assertie instanceof Conditie.Samengesteld s && s.kwantor() == Conditie.Kwantor.ALLE) {
            // §13.4.8: "alle criteria moeten gelden" — inconsistent when ANY criterion is violated.
            // OR the per-criterion violations; an un-lowerable criterion no-ops the whole rule.
            java.util.List<String> schendingen = new java.util.ArrayList<>();
            for (Conditie deel : s.onderdelen()) {
                String d = schendingConstraint(acc, deel, subject);
                if (d == null) return null;
                schendingen.add(d);
            }
            return schendingen.isEmpty() ? null : "(" + String.join(" || ", schendingen) + ")";
        }
        // §8.2 a whole-period-qualified criterium ("X moet gedurende het gehele jaar gelijk zijn aan Y"): the
        // criterium is satisfied iff rsGeheelPeriode(...) holds, so the VIOLATION is its negation — the same boolean
        // term as the voorwaarde form with the "== true|false" comparison FLIPPED (the NIET prefix flips it back).
        // A §8.2 form this transpiler cannot lower no-ops the rule (null), never flagging a never-evaluated criterium.
        Conditie.GeheelPeriode gp82 = geheelPeriodeVan(assertie);
        if (gp82 != null) {
            Frag f = geheelPeriodeCall(acc, assertie, gp82, subject);
            return f.marker() ? null : f.text() + " == " + (gp82.niet() ? "true" : "false");
        }
        // A comparison with a TO-ONE navigated operand cannot use the simple null-guard forms below: those
        // emit the operand via operand()'s Drools "!." which short-circuits the WHOLE term to a non-match on
        // a null reference, hiding a real gevuld-vs-leeg violation (p41). Handle it with explicit leegwaarde
        // guards per the typeringen rekentabellen instead.
        if (assertie instanceof Conditie.Vergelijking cmp) {
            String genav = genavigeerdeVergelijkingSchending(acc, cmp, subject);
            if (genav != null) return genav;
        }
        if (assertie instanceof Conditie.Vergelijking v && ordeningOfDatum(v.op())) {
            Frag l = operand(acc, v.links(), subject), r = operand(acc, v.rechts(), subject);
            // An un-evaluable operand (a /* TODO */ marker) must NOT be wrapped in this
            // null-guard: the guard's "<marker> == null" reads as "null == null" — always
            // true — so the rule would flag EVERY instance inconsistent though its criterion
            // was never evaluated (§9.5: inconsistent only on a real violation). Drop to the
            // no-op path instead, like an assertion with no faithful negation.
            if (l.marker() || r.marker()) return null;
            Frag complement = conditie(acc, new Conditie.Vergelijking(Operatoren.flip(v.op()), v.links(), v.rechts(), v.bron()), subject);
            return "(" + l.text() + " == null || " + r.text() + " == null || " + complement.text() + ")";
        }
        if (assertie instanceof Conditie.Vergelijking v && v.op() == Conditie.Operator.GELIJK
                && nietNumeriekVergelijking(v)) {
            // typeringen Tabel 38: a NON-numeriek "gelijk aan" with BOTH operands empty is "fout" — unlike the
            // numeriek Tabel 37 case, which is "waar". So the criterion is NOT satisfied -> a violation. The
            // plain complement `l != r` reads `null != null` -> false, wrongly reporting the instance consistent
            // (§9.5 reading: a criterion is satisfied only when its comparison is "waar"; "fout" is a violation).
            Frag l = operand(acc, v.links(), subject), r = operand(acc, v.rechts(), subject);
            if (l.marker() || r.marker()) return null;
            return "(" + l.text() + " != " + r.text() + " || (" + l.text() + " == null && " + r.text() + " == null))";
        }
        Conditie schending = negeer(assertie);
        if (schending == null) return null;
        // Same hazard via the negation: a gevuld→leeg predicate over a marker emits
        // "<marker> == null" (always true). An un-evaluable criterion flags nothing.
        Frag c = conditie(acc, schending, subject);
        return c.marker() ? null : c.text();
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

    /**
     * A lowered VALUE / OPERAND fragment: the mvel term to render, plus whether it is an inert
     * {@code /* TODO … *​/} placeholder. A placeholder's {@link #text} is still compilable inert DRL
     * (a {@code null} value term, or — for a condition — a {@code false} constraint), so a marker renders
     * at the boundary exactly like a real fragment: it is a placeholder, not an absence. Callers branch on
     * the typed {@link #marker} flag instead of scanning the rendered text for the sentinel; every
     * combinator ORs the flag, so {@code marker} tracks "the text carries a placeholder" precisely (the
     * invariant that makes this a pure refactor: {@code marker} ⟺ the old {@code text.contains("/* TODO")}).
     */
    private record Frag(String text, boolean marker) {
        /** A fully-lowered fragment (no placeholder). */
        static Frag ok(String text) { return new Frag(text, false); }
        /** A value-side placeholder: {@code /* TODO wat: bron *​/ null}, an inert null term. */
        static Frag todo(String wat, String bron) { return new Frag(DrlTranspiler.todo(wat, bron), true); }
        /** A fragment assembled from {@code delen}: its marker is the OR of theirs (any placeholder
         *  operand taints the whole, exactly as the old substring scan would have detected it). */
        static Frag from(String text, Frag... delen) {
            boolean m = false;
            for (Frag d : delen) m |= d.marker;
            return new Frag(text, m);
        }
    }

    /** A comparison operand decomposed for a leegwaarde-faithful violation. {@code leeg}/{@code vol}
     *  are the "is empty"/"is present" guards; {@code waarde} is the value via a plain "." (safe ONLY
     *  behind {@code !leeg}, which short-circuits a null root). A navigated to-one operand uses an
     *  explicit root guard; a direct attribute / literal / parameter uses its plain operand() form. */
    private record OperandDeel(String leeg, String vol, String waarde, boolean genavigeerd, boolean marker) {}

    private OperandDeel operandDeel(Acc acc, Expr e, Objecttype subject) {
        if (e instanceof Expr.AttribuutRef a) {
            NaviRef n = naviResolve(a, subject);
            if (n.soort() == NaviSoort.GENAVIGEERD) {
                // §13.3.6/§8.2: a navigated TEMPORAL attribute is a List<Segment> — its leeg/vol form ("reis.loon == null")
                // would test the timeline's nullness, not its per-moment leegte, silently dropping the §8.2 period check.
                // Marker it (the leeg/vol/waarde text is unused on a marker; companionDeel/schending bail on .marker()).
                if (isTemporeelAttribuut(a)) return new OperandDeel(null, null, null, true, true);
                String wortel = ident(n.rol(), false), pad = wortel + "." + ident(a.attribuut(), false);
                return new OperandDeel("(" + wortel + " == null || " + pad + " == null)",
                        "(" + wortel + " != null && " + pad + " != null)", pad, true, false);
            }
        }
        Frag o = operand(acc, e, subject);   // direct attribute / literal / parameter — never "!."
        return new OperandDeel(o.text() + " == null", o.text() + " != null", o.text(), false, o.marker());
    }

    /** The §6.5/§6.6 fout-companion decomposition for an operand that is the subject's OWN attribute or one
     *  reached by a to-ONE navigation — the only shapes that lower to a re-readable leeg/vol guard a separate
     *  insert-only rule can re-test. null for a literal / parameter / to-many / unresolvable operand (which
     *  still degrades to leeg on the value side but raises no Inconsistentie). An own attribute reuses the bare
     *  property form, so the companion is byte-identical to the original EIGEN-only emission. */
    private OperandDeel companionDeel(Acc acc, Expr e, Objecttype subject) {
        if (!(e instanceof Expr.AttribuutRef a)) return null;
        NaviSoort s = naviResolve(a, subject).soort();
        if (s != NaviSoort.EIGEN && s != NaviSoort.GENAVIGEERD) return null;
        OperandDeel d = operandDeel(acc, e, subject);
        return d.marker() ? null : d;
    }

    /** §13.3.9 to-one navigation + the typeringen leegwaarde rekentabellen (Tabel 37/38 gelijk,
     *  40-43 ordening, 44 ongelijk): build a consistentie violation for a comparison with at least one
     *  navigated to-one operand, treating a null root as the operand being LEEG. Drools "!." would make
     *  such an operand a silent non-match (under-reporting the violation, p41); these explicit guards
     *  short-circuit the null root before any plain "." dereference, so an empty operand yields the
     *  spec result. Returns null when NEITHER operand is navigated (the simpler forms handle it) or when
     *  an operand is an unresolved marker (the caller then WARNs). */
    private String genavigeerdeVergelijkingSchending(Acc acc, Conditie.Vergelijking v, Objecttype subject) {
        OperandDeel l = operandDeel(acc, v.links(), subject), r = operandDeel(acc, v.rechts(), subject);
        if (!l.genavigeerd() && !r.genavigeerd()) return null;
        if (l.marker() || r.marker()) return null;
        String[] ov = vergelijkingsOperanden(l.waarde(), v.links().type(), r.waarde(), v.rechts().type());
        String lv = ov[0], rv = ov[1];
        if (ordeningOfDatum(v.op()))   // Tabel 40-43: any empty operand -> onwaar -> violation
            return "(" + l.leeg() + " || " + r.leeg() + " || " + lv + " " + Operatoren.symbool(Operatoren.flip(v.op())) + " " + rv + ")";
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

    /** The leegwaarde-faithful POSITIVE (voorwaarde) truth value of a comparison with a TO-ONE navigated operand —
     *  exactly the complement of {@link #genavigeerdeVergelijkingSchending} (the rule fires when the comparison is WAAR;
     *  the consistentie rule fires when it is VIOLATED, i.e. onwaar/fout). A navigated operand otherwise renders via
     *  operand()'s mvel {@code !.}, which short-circuits the whole constraint to a non-match on a null relation —
     *  spec-correct for ordering/GELIJK (a leeg operand is onwaar) but WRONG for ONGELIJK (§5.6 Tabel 44: a leeg-vs-gevuld
     *  "ongelijk aan" is WAAR). Reuses the schending form's explicit leeg/vol/waarde guards (operandDeel) + unit
     *  restatement, so the two stay in lock-step. null when neither operand is navigated (the plain mvel form applies) or
     *  an operand markers. Mirrors the exec navigated-comparison branches (a null relation -> leeg -> vergelijk). */
    private String genavigeerdeVergelijkingVoorwaarde(Acc acc, Conditie.Vergelijking v, Objecttype subject) {
        // Only GELIJK and ONGELIJK need the explicit form: ordering (<,>,<=,>=) of a leeg operand is onwaar (Tabel 33-42),
        // which mvel's `!.` short-circuit ALREADY gives correctly — keep its compact `reis!.x op y` rendering. GELIJK
        // both-leeg -> WAAR (Tabel 37) and ONGELIJK one-leeg -> WAAR (Tabel 44) are the cells `!.` gets wrong.
        if (v.op() != Conditie.Operator.GELIJK && v.op() != Conditie.Operator.ONGELIJK) return null;
        String schending = genavigeerdeVergelijkingSchending(acc, v, subject);
        return schending == null ? null : "!" + schending;
    }

    private static boolean ordeningOfDatum(Conditie.Operator op) {
        return switch (op) {
            case KLEINER, KLEINER_OF_GELIJK, GROTER, GROTER_OF_GELIJK,
                 EERDER, EERDER_OF_GELIJK, LATER, LATER_OF_GELIJK -> true;
            case GELIJK, ONGELIJK -> false;
        };
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
            return new Conditie.Vergelijking(Operatoren.flip(v.op()), v.links(), v.rechts(), v.bron());
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
        // The share is numeric (a division/fold of the verdeelplafond), so the recipient attribute must be
        // getalachtig — else the modify would assign a BigDecimal to a Tekst/Datum setter, uncompilable DRL that
        // sinks the whole package. A non-numeric doel stays an honest marker (the resolver also flags the family
        // mismatch, the same fout regel raises for the equivalent assignment).
        Datatype doelType = doel != null && v.doelAttribuut() != null ? doel.attribuut(v.doelAttribuut()) : null;
        boolean doelOk = doelType != null && doelType.isGetalachtig();
        boolean gelijk = methode == io.github.yhavinga.regelspraak.ir.ResolvedVerdeling.Methode.GELIJKE_DELEN && join != null && doelOk;
        // NAAR RATO needs the join AND a weight that is a real GETALACHTIG (numeriek/percentage) field of the
        // recipient: heeftLid alone would also accept a Tekst attribute or a boolean kenmerk, whose getter
        // cannot be summed/multiplied — uncompilable DRL that sinks the whole package. A non-numeric, foreign,
        // or absent weight therefore stays the honest no-op marker.
        Datatype gewichtType = doel != null && v.ratoAttribuut() != null ? doel.attribuut(v.ratoAttribuut()) : null;
        boolean rato = methode == io.github.yhavinga.regelspraak.ir.ResolvedVerdeling.Methode.NAAR_RATO && join != null && doelOk
                && gewichtType != null && gewichtType.isGetalachtig();
        // §9.7.2 op volgorde van: the recipients are partitioned by an ORDERABLE ordering key (a number or a
        // date — rsVerdeelOpVolgorde compareTo-sorts it) and the pot is depleted group-by-group; a "naar rato"
        // tie additionally needs a getalachtig tie weight (it is summed). Anything else stays the marker.
        Datatype groepType = methode == io.github.yhavinga.regelspraak.ir.ResolvedVerdeling.Methode.OP_VOLGORDE
                && doel != null && v.groepeerAttribuut() != null ? doel.attribuut(v.groepeerAttribuut()) : null;
        boolean opVolgorde = methode == io.github.yhavinga.regelspraak.ir.ResolvedVerdeling.Methode.OP_VOLGORDE && join != null && doelOk
                && groepType != null && groepType.isOrdenbaar()
                && (v.ratoAttribuut() == null || (gewichtType != null && gewichtType.isGetalachtig()));
        if (!gelijk && !rato && !opVolgorde) {
            out.append(new Regel(v.naam())
                .clausule("// onopgelost verdeling ("
                        + (bron == null || doel == null ? "referentie" : methode.name().toLowerCase())
                        + "): " + commentVeilig(v.bronAttribuut() + " over " + v.doelAttribuut()))
                .clausule("eval(true)")
                .render());
            return;
        }
        String j = ident(join, false) + " == $src";
        // a §9.7.6 empty-weight Fout applies whenever a naar-rato split is present (top-level OR the op-volgorde tie)
        boolean tieNaarRato = rato || (opVolgorde && v.ratoAttribuut() != null);
        // emit the share rule; both builders return the recipient filter the onverdeelde-rest rule reuses
        String doelFilter = opVolgorde ? opVolgordeRegel(v, bron, doel, j)
                                       : gelijkOfRatoRegel(v, bron, doel, j, gelijk);
        if (v.restantAttribuut() != null) {
            // §9.7.5 onverdeelde rest: the verdeler keeps total − Σ(distributed shares). A SEPARATE rule that
            // modifies the SOURCE (not the recipients), so it cannot cancel the per-recipient activations. It
            // reads the shares the distribution already assigned, so it is method-agnostic (equal-shares and
            // naar-rato alike) and needs no recompute. It is RECOMPUTABLE, not fire-once: a `restant == null`
            // freeze would lock in a partial sum when recipients arrive incrementally (the §9.4 rule-derived
            // case — the `not` guard only sees recipients already in working memory, so a single early recipient
            // looks "complete"). Instead it re-fires whenever the share set changes and always reflects the
            // CURRENT full distribution. The `not <Doel>(<filter>, <doel> == null)` guard still keeps it from
            // summing a recipient mid-assignment; termination holds because no other rule reads restant, so its
            // modify does not re-trigger the share rule (property reactivity) and the recomputation is a fixpoint.
            String doelP = ident(doel.naam, true), doelN = ident(v.doelAttribuut(), false);
            out.append(new Regel(v.naam() + " (onverdeelde rest)")
                .clausule("$src : " + ident(bron.naam, true) + "( " + ident(v.bronAttribuut(), false) + " != null )")
                .clausule("not " + doelP + "( " + doelFilter + ", " + doelN + " == null )")
                .clausule("accumulate( $u : " + doelP + "( " + doelFilter + ", " + doelN
                        + " != null ) ; $uit : sum($u." + getter(v.doelAttribuut()) + "()) )")
                .geldigheid(v.geldigheid())
                .dan("    modify($src) {\n        " + setter(v.restantAttribuut()) + "( $src."
                        + getter(v.bronAttribuut()) + "().subtract($uit) )\n    }\n")
                .render());
        }
        if (tieNaarRato) {
            // §9.7.6 Tabel 19: surface the empty-weight Fout via the §9.5 Inconsistentie channel — a SEPARATE,
            // modify-free rule (so it cannot cancel the distribution's recipient activations), bound to the
            // bron as $subject, firing once per bron that has a weightless recipient. The header is suffixed for
            // a unique DRL rule name; the Inconsistentie's `regel` field stays the logical verdeling name (as
            // uniciteitUnie does), so the idempotency guard keys off it.
            String wNaam = ident(v.ratoAttribuut(), false);
            out.append(new Regel(v.naam() + " (leeg gewicht)")
                .clausule(SUBJECT + " : " + ident(bron.naam, true) + "(  )")
                .clausule("exists " + ident(doel.naam, true) + "( " + ident(join, false) + " == " + SUBJECT
                        + ", " + wNaam + " == null )")
                .geldigheid(v.geldigheid())
                .clausule(inconsistentieGuard(v.naam()))
                .dan(inconsistentieDan(v.naam()))
                .render());
        }
        if (v.maximumAttribuut() != null) {
            // §9.7.6 Tabel 19: a LEEG or NEGATIVE maximum at ANY recipient makes the whole verdeling a Fout (the
            // N.B. — one recipient suffices). Surfaced like the empty weight: a SEPARATE modify-free Inconsistentie
            // rule. The value side already degrades a leeg/negative cap to uncapped (rsPlafond for naar rato,
            // rsVerdeelOpVolgorde for op volgorde), so no negative share is produced — the same {degrade +
            // Inconsistentie} fout representation as elsewhere.
            String mNaam = ident(v.maximumAttribuut(), false);
            out.append(new Regel(v.naam() + " (leeg of negatief maximum)")
                .clausule(SUBJECT + " : " + ident(bron.naam, true) + "(  )")
                .clausule("exists " + ident(doel.naam, true) + "( " + ident(join, false) + " == " + SUBJECT
                        + ", " + mNaam + " == null || " + mNaam + ".signum() < 0 )")
                .geldigheid(v.geldigheid())
                .clausule(inconsistentieGuard(v.naam()))
                .dan(inconsistentieDan(v.naam()))
                .render());
        }
    }

    /** §13.4.10 GELIJKE_DELEN / NAAR_RATO: one accumulate folds the recipients (count, or sum-of-weights); each
     *  gets total/count or total·weight/Σweight, then the §9.7.3 cap (rsPlafond, naar-rato only) and the §9.7.4
     *  floor (rsAfronding). Returns the recipient filter the onverdeelde-rest rule reuses. */
    private String gelijkOfRatoRegel(io.github.yhavinga.regelspraak.ir.ResolvedVerdeling v, Objecttype bron, Objecttype doel,
                                     String j, boolean gelijk) {
        Regel rule = new Regel(v.naam()).clausule("$src : " + ident(bron.naam, true) + "(  )");
        String aandeel;       // the per-recipient value set into the modify
        String doelFilter;    // the recipient pattern's constraints (reused by the onverdeelde-rest rule)
        if (gelijk) {
            doelFilter = j;
            rule.clausule("accumulate( $c : " + ident(doel.naam, true) + "( " + doelFilter + " ) ; $n : count($c) )")
                .clausule("$r : " + ident(doel.naam, true) + "( " + doelFilter + " )");
            aandeel = "$src." + getter(v.bronAttribuut()) + "().divide($n, java.math.MathContext.DECIMAL64)"; // safe division
        } else {
            // §13.4.10 naar rato: each recipient gets total·weight/Σweight. The accumulate sums the weights of
            // the recipients that HAVE one, and $r binds the same set, so a weightless recipient is left unset
            // (its weight is null — there is no proportional basis) while the weighted ones share the total.
            // §9.7.6 Tabel 19 makes an empty weight on any recipient a "Fout" for the WHOLE verdeling: the Fout
            // is now surfaced as a SEPARATE Inconsistentie rule (below), since it cannot ride on this rule — a
            // second accumulate / `not` in a modify+no-loop rule cancels the sibling recipient activations so
            // only one recipient fires (verified vs Drools 8.44). DIVERGENCE that remains: the distribution is
            // NOT suppressed (the weighted recipients still receive shares) — expressing "no shares when any
            // weight is empty" needs exactly the CE that triggers the cancellation, so it is unsolved; the
            // exclude-and-redistribute split stays, now flagged by the Inconsistentie. Documented + carded. A
            // zero total weight (all weights 0) is guarded so the divide cannot throw — the spec leaves a zero
            // divisor undefined, so no-op-not-crash is the safe reading.
            String wNaam = ident(v.ratoAttribuut(), false), wGet = getter(v.ratoAttribuut());
            doelFilter = j + ", " + wNaam + " != null";
            rule.clausule("accumulate( $c : " + ident(doel.naam, true) + "( " + doelFilter
                            + " ) ; $sum : sum($c." + wGet + "()) )")
                .clausule("$r : " + ident(doel.naam, true) + "( " + doelFilter + " )")
                .clausule("eval( $sum.signum() != 0 )");
            aandeel = "$src." + getter(v.bronAttribuut()) + "().multiply($r." + wGet
                    + "()).divide($sum, java.math.MathContext.DECIMAL64)";
        }
        if (v.maximumAttribuut() != null) {
            // §9.7.3 maximale aanspraak (naar rato only): cap each share at the recipient's maximum — the surplus
            // is NOT redistributed, it falls to the onverdeelde rest (the rest rule reads the capped shares). The
            // cap precedes the §9.7.4 afronding ("als laatste … afgerond"). rsPlafond null-guards both sides (a
            // recipient without a maximum is uncapped).
            fnImport("rsPlafond");
            aandeel = "rsPlafond(" + aandeel + ", $r." + getter(v.maximumAttribuut()) + "())";
        }
        if (v.afrondingDecimalen() != null) {
            // §9.7.4 / §6.1.2: floor each share to N decimals ("naar beneden") — the explicit afronding the spec
            // requires before a division result may land in a bounded target. rsAfronding null-guards the basis.
            fnImport("rsAfronding");
            aandeel = "rsAfronding(" + aandeel + ", " + v.afrondingDecimalen() + ", java.math.RoundingMode.FLOOR)";
        }
        out.append(rule.geldigheid(v.geldigheid())
            .dan("    modify($r) {\n        " + setter(v.doelAttribuut()) + "( " + aandeel + " )\n    }\n")
            .render());
        return doelFilter;
    }

    /** §9.7.2 op volgorde van: one forward rule whose accumulate folds the recipients into index-aligned value
     *  lists (the fact itself, for an identity key; the ordering criterium; and — when present — the tie weight
     *  and the cap), hands them to rsVerdeelOpVolgorde (which computes the whole ordered allocation as one
     *  identity-keyed share map), and assigns each recipient its share via {@code $shares.get($r)}. The §9.7.3 cap
     *  and §9.7.4 floor are applied INSIDE the helper, group by group, so the §9.7.5 carry-over of the remainder
     *  reflects the capped+floored shares (Tabel 18) — not a post-hoc clamp against the full plafond. {@code from}
     *  a Map binds it once (a Map is not a Collection, so it is not iterated). Returns the recipient filter (the
     *  bare join — every recipient is collected) for the onverdeelde-rest rule. */
    private String opVolgordeRegel(io.github.yhavinga.regelspraak.ir.ResolvedVerdeling v, Objecttype bron, Objecttype doel, String j) {
        fnImport("rsVerdeelOpVolgorde");
        String doelP = ident(doel.naam, true);
        StringBuilder acc = new StringBuilder("$facts : collectList($p), $groep : collectList($p."
                + getter(v.groepeerAttribuut()) + "())");
        String gewArg = "null", maxArg = "null";
        if (v.ratoAttribuut() != null) {   // a naar-rato tie ("bij een even groot criterium naar rato van <gewicht>")
            acc.append(", $gewicht : collectList($p.").append(getter(v.ratoAttribuut())).append("())");
            gewArg = "$gewicht";
        }
        if (v.maximumAttribuut() != null) {
            acc.append(", $max : collectList($p.").append(getter(v.maximumAttribuut())).append("())");
            maxArg = "$max";
        }
        String dArg = v.afrondingDecimalen() != null ? "Integer.valueOf(" + v.afrondingDecimalen() + ")" : "null";
        out.append(new Regel(v.naam())
            .clausule("$src : " + ident(bron.naam, true) + "(  )")
            .clausule("accumulate( $p : " + doelP + "( " + j + " ) ; " + acc + " )")
            .clausule("$shares : java.util.Map() from rsVerdeelOpVolgorde( $src." + getter(v.bronAttribuut())
                    + "(), $facts, $groep, " + (v.afnemend() ? "false" : "true") + ", " + gewArg + ", " + maxArg
                    + ", " + dArg + " )")
            .clausule("$r : " + doelP + "( " + j + " )")
            .geldigheid(v.geldigheid())
            .dan("    modify($r) {\n        " + setter(v.doelAttribuut())
                    + "( (java.math.BigDecimal) $shares.get($r) )\n    }\n")
            .render());
        return j;
    }

    /** §13.4.6 objectcreatie -> a rule that inserts a new fact, sets its attributes,
     *  and links it back to the subject through the feittype role.
     *
     *  <p>§9 idempotence: the creation must be a FUNCTION of the fact base, not of the fire count. {@code no-loop}
     *  only blocks the rule re-activating on its OWN insertion — when ANOTHER rule later modifies a matched
     *  subject the unguarded rule re-fires and mints a DUPLICATE instance. So the LHS carries an absence guard:
     *  <ul>
     *    <li><b>1:1 target</b> (the subject relates to at most ONE created-type instance, and the created object
     *        back-references the subject): {@code not( <NieuwType>( <backRef> == $subject ) )} — if a linked
     *        instance already exists (whoever made it), create no other. A fact-base check on the created type.</li>
     *    <li><b>to-many target, or no back-reference</b> (an orphan insert): a per-rule per-subject
     *        {@link io.github.yhavinga.regelspraak.runtime.CreationMarker} control fact — {@code not( CreationMarker(
     *        regel == "<rule>", subject == $subject ) )} on the LHS, {@code insert(new CreationMarker(...))} in
     *        the consequence. Each subject then creates at most one instance per creation rule, while a
     *        pre-existing linked instance stays legitimate (the key is rule+subject, not the instance).</li>
     *  </ul> */
    private void objectCreatie(io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie oc) {
        Acc acc = new Acc();
        Objecttype nieuw = oc.nieuwType(), subject = oc.subject();
        // value expressions may carry aggregations -> collected into the "when" via the threaded Acc
        // context (so lower them BEFORE reading acc.clausules into the rule, even on the no-op path).
        java.util.List<String> setters = new ArrayList<>();
        for (var t : oc.toekenningen()) {
            Frag v = waarde(acc, t.waarde(), subject);
            // §6.1.2 write boundary (as in regel()): a COMPUTED numeric value (arithmetic / non-terminating rational)
            // collapses through rsWaarde so a Breuk never reaches a BigDecimal setter (mvel strict-mode) — inert for a
            // plain BigDecimal, skipped for a marker / a non-Breuk-capable value.
            if (kanBreukOpleveren(t.waarde()) && !v.marker()) { fnImport("rsWaarde"); v = Frag.from("rsWaarde(" + v.text() + ")", v); }
            setters.add("    $new." + setter(t.attribuut()) + "( " + v.text() + " );");
        }

        if (subject == null || nieuw == null) {
            out.append(new Regel(oc.naam())
                .clausule("// onopgelost objectcreatie: " + commentVeilig(oc.naam()))
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
            dan.append("    // onopgelost terugkoppeling naar ").append(commentVeilig(subject.naam)).append('\n');
        dan.append("    insert($new);\n");

        // §9 idempotence guard (see method doc): a 1:1 target keys on the linked instance's absence; a to-many
        // target (or a missing back-reference — an orphan insert we cannot key on the instance) keys on a
        // per-rule per-subject CreationMarker.
        boolean eenOpEen = rolNaarSubject != null && !model.subjectNaarVeelCreatie(subject, nieuw);
        String idemGuard;
        if (eenOpEen) {
            idemGuard = "not " + ident(nieuw.naam, true) + "( " + ident(rolNaarSubject, false) + " == " + SUBJECT + " )";
        } else {
            creationMarkerNodig = true;
            idemGuard = "not CreationMarker( regel == \"" + escapeRegelNaam(oc.naam())
                    + "\", subject == " + SUBJECT + " )";
            dan.append("    insert(new CreationMarker(\"").append(escapeRegelNaam(oc.naam())).append("\", ")
               .append(SUBJECT).append("));\n");
        }

        // Like the feitcreatie anker, a §8.1.7 far-side guard on the objectcreatie subject hosts its exists/not
        // join after the subject pattern; a near guard yields empty extra and keeps the output byte-identical.
        LoweredConditie opVw = oc.voorwaarde() != null
                ? conditie(acc, oc.voorwaarde(), subject, true) : LoweredConditie.van("");
        // §9.10 fase-2: a recursief-group objectcreatie drops no-loop so it forward-chains on its own inserts to the
        // fixpoint (the idempotence guard above is the anti-runaway control, not no-loop). Non-recursief: unchanged.
        Regel r = new Regel(oc.naam())
            .clausule(SUBJECT + " : " + ident(subject.naam, true) + "( " + opVw.inline() + " )")
            .clausules(opVw.extra())
            .clausules(acc.clausules)
            .clausule(idemGuard)
            .geldigheid(oc.geldigheid())
            .dan(dan.toString());
        if (model.isRecursiefGroepslid(oc.naam())) r.zonderNoLoop();
        out.append(r.render());
    }

    /** §13.4.7 feitcreatie -> a rule that, for each anker and each doel ALREADY related to it by the
     *  existing rolC relation ({@code joinVeld}), creates the NEW relation by setting {@code zetVeld}
     *  on the doel. A relation instance is a reference field in this fact model (§9.4), so creating one
     *  is a {@code modify}-set; {@code no-loop} keeps the modify from re-triggering the rule, and setting
     *  the field to the same value is idempotent across other rules' fire cycles. */
    private void feitCreatie(io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie fc) {
        Acc acc = new Acc();
        Objecttype anker = fc.anker(), doel = fc.doel();
        // The voorwaarde selects which objects get the relation: a guard on the anker (Vlucht) stays on
        // the anker pattern; a guard on the doel (the passengers) hosts on the $doel pattern. A guard that
        // lowers to NEITHER must NOT be silently dropped — dropping it makes the rule fire unconditionally
        // and OVER-CREATE the relation. Instead the rule becomes an inert eval(false) no-op (carrying the
        // marker), the §-wide "an unresolved condition is a compilable no-op" discipline: better to create
        // nothing (visibly) than to create wrongly.
        String ankerC = "", doelExtra = "", noFire = "";
        List<String> ankerExtra = List.of();
        if (fc.voorwaarde() != null) {
            // A §8.1.7 far-side anker guard ("indien de vlucht heeft een passagier") lowers to an EMPTY inline
            // plus a hoisted exists/not join, so lower the anker side with the FULL carrier and host its extra
            // after the anker pattern. conditieInline would degrade that join to a marker, dropping the rule to
            // an inert eval(false); a near anker guard yields empty extra and stays byte-identical.
            Acc ankerAcc = new Acc();                          // each attempt collects into its OWN context;
            LoweredConditie opAnker = conditie(ankerAcc, fc.voorwaarde(), anker, true);
            if (!opAnker.marker()) {
                ankerC = opAnker.inline();
                ankerExtra = opAnker.extra();
                acc = ankerAcc;                                // only the successful attempt's clauses host
            } else {
                Acc doelAcc = new Acc();                       // the failed anker attempt is simply abandoned
                Frag opDoel = conditieInline(doelAcc, fc.voorwaarde(), doel, true);
                if (!opDoel.marker()) {
                    doelExtra = opDoel.text();
                    acc = doelAcc;
                } else {
                    noFire = "eval( false ) /* TODO voorwaarde niet naar anker of doel te lowereren — regel inert i.p.v. over-creatie */";
                }
            }
        }
        Regel rule = new Regel(fc.naam());
        if (!noFire.isEmpty()) rule.clausule(noFire);
        rule.clausule(SUBJECT + " : " + ident(anker.naam, true) + "( " + ankerC + " )")
            .clausules(ankerExtra)        // far-side anker membership: the exists/not join, after $subject is bound
            .clausules(acc.clausules)
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
        Acc acc = new Acc();
        Objecttype subject = r.doel() instanceof Expr.AttribuutRef a ? a.objecttype() : null;
        // A TEMPORAL doel (a List<Segment> tijdlijn attribute) is written a timeline, not a scalar — its value lowers
        // through temporeleWaarde() below, NOT waarde() (which would register a wrong scalar accumulate for a SOM-over-
        // temporal-set and mis-count it). Detect it here so the scalar value pre-computation is skipped.
        boolean temporeelDoel = subject != null && r.doel() instanceof Expr.AttribuutRef td && td.navigatieRol() == null
                && td.objecttype() != null && td.objecttype().attribuutGranulariteit.containsKey(td.attribuut())
                && !td.objecttype().attribuutTijdlijnAantal.containsKey(td.attribuut());
        // Translate the value first: any aggregation registers an accumulate pattern
        // for the "when" and yields the bound result variable used here in the "then".
        Frag valueStr = subject != null && !temporeelDoel && r.doel() instanceof Expr.AttribuutRef
                ? waarde(acc, r.waarde(), subject) : null;
        // §5.1.4 lower a temporal-doel timeline value BEFORE the acc.clausules flush (temporeleWaarde may register a
        // collectList accumulate for a §5.8.2 to-many sommatie, which must be emitted after $subject binds).
        String temporeelTl = null; boolean temporeelOngeankerd = false;
        if (temporeelDoel && r.doel() instanceof Expr.AttribuutRef td) {
            String ankerLid0 = model.startpuntAnkerLid(td.objecttype(), td.attribuut());
            temporeelOngeankerd = ankerLid0 == null && model.heeftVariabelStartpunt(td.objecttype(), td.attribuut());
            String anker0 = ankerLid0 == null ? null : SUBJECT + "." + getter(ankerLid0) + "()";
            temporeelTl = temporeelOngeankerd ? null
                    : temporeleWaarde(acc, r.waarde(), subject, td.objecttype().attribuutGranulariteit.get(td.attribuut()), td.type(), anker0);
        }

        // A forward calculation should not retrigger itself on its own modify (no-loop, in the header);
        // it still recomputes when another rule changes the facts it reads.
        Regel rule = new Regel(r.naam());
        // §8.1.7 far-side "heeft een <rol>" lowers to an EXISTS-join — a separate when-clause that cannot live
        // inside the subject pattern's inline constraint, so condition lowering hands it back as an extra clause
        // (directly for a top-level membership, carried up through a pure-conjunction §13.4.14 alle/de compound).
        // Each is appended after the subject pattern; a far-side nested under a non-conjunction quantifier stays
        // an honest marker.
        // §8.4.3 a periodevergelijking on a TIJDSAFHANKELIJK doel should CLIP the written timeline to the
        // period, not all-or-nothing gate it on the rekendatum — that clip is not built yet, so marker the
        // voorwaarde (rule inert, never a silently mis-gated timeline). Mirrored by the exec back-end.
        boolean periodeOpTijdlijnDoel = r.voorwaarde() != null && Conditie.bevatPeriode(r.voorwaarde())
                && r.doel() instanceof Expr.AttribuutRef da && da.objecttype() != null
                && (da.objecttype().attribuutGranulariteit.containsKey(da.attribuut())
                    || da.objecttype().kenmerkGranulariteit.containsKey(da.attribuut()));
        LoweredConditie lc = subject != null && r.voorwaarde() != null
                ? (periodeOpTijdlijnDoel
                    ? LoweredConditie.marker("periodevergelijking op een tijdsafhankelijk doel (§8.4.3)", r.voorwaarde().bron())
                    : conditie(acc, r.voorwaarde(), subject, true))
                : LoweredConditie.van("");
        String constraint = lc.inline();
        // §9.6 initialisatie assigns only when the attribuut holds NO value yet ("controleert of een attribuut
        // een waarde bevat … als dit niet het geval is"): the leeg-guard makes the rule idempotent and removes
        // the agenda-order dependence vs a berekening on the same doel — the berekening (unguarded) wins whenever
        // it applies, the initialisatie fills the rest. Property reactivity then cancels a stale activation.
        if (r.soort() == ResolvedRegel.Soort.INITIALISATIE && subject != null
                && r.doel() instanceof Expr.AttribuutRef ia && ia.navigatieRol() == null) {
            String leegGuard = ident(ia.attribuut(), false) + " == null";
            constraint = constraint.isEmpty() ? leegGuard : leegGuard + ", " + constraint;
        }
        if (subject == null) {
            rule.clausule("// onopgelost doel-objecttype: " + commentVeilig(r.doel().bron())).clausule("eval(true)");
        } else {
            // Freshness: property reactivity listens only to CONSTRAINED properties, so an assignment whose
            // pattern does not constrain what its VALUE reads goes stale — it computes once (possibly against
            // leeg) and never recomputes when another rule writes an input (the TOKA toka-45 -> toka-24 chain:
            // an unconditioned berekening kept 0 after its operand arrived). @watch the subject's OWN
            // attributes the value expression reads; no-loop still blocks the self-retrigger. A read-write
            // CYCLE between rules (x = y+1; y = x+1) would recompute divergently forever — the resolver
            // WARNs on it (keurKringverwijzingen) and the engine caps fireAllRules, since one-writer-per-
            // attribute does NOT make the dependency graph acyclic. The exec back-end needs no analog:
            // its opaque lambda constraints leave the pattern class-reactive.
            java.util.LinkedHashSet<String> watch = new java.util.LinkedHashSet<>();
            verzamelEigenReads(r.waarde(), subject, watch);
            // §9.9: an anchored temporal write ALSO reads the startpunt attribute (injected at lowering,
            // invisible to the IR walk) — watch it, so the write re-fires when the startpunt rule lands.
            if (r.doel() instanceof Expr.AttribuutRef da && da.navigatieRol() == null && da.objecttype() == subject) {
                String ankerLid = model.startpuntAnkerLid(subject, da.attribuut());
                if (ankerLid != null) watch.add(ident(ankerLid, false));
            }
            rule.clausule(SUBJECT + " : " + ident(subject.naam, true) + "( " + constraint + " )"
                    + (watch.isEmpty() ? "" : " @watch( " + String.join(", ", watch) + " )"));
            for (String e : lc.extra()) rule.clausule(e);   // the exists-join(s), after $subject is bound
            rule.clausules(acc.clausules)
                .geldigheid(r.geldigheid());   // §4.2: a time-bounded version fires only inside its window
        }
        if (r.doel() instanceof Expr.AttribuutRef a && a.navigatieRol() == null && a.objecttype() != null
                && a.objecttype().attribuutGranulariteit.containsKey(a.attribuut())
                && !a.objecttype().attribuutTijdlijnAantal.containsKey(a.attribuut())) {
            // §5.1.4 the doel is a SINGLE-period TEMPORAL attribute -> write a List<Segment> timeline, not a scalar.
            // A timeline-producing RHS we cannot lower stays an honest /* TODO */ marker (never a scalar setter on a
            // List<Segment> field, which would not compile).
            // §3.8/§9.9: a doel riding a tijdlijn met VARIABEL startpunt buckets at the per-object anchor
            // (the §9.9 synthetic attribute) instead of the calendar; with NO startpuntbepaling resolved the
            // timeline has no periodization at all -> honest marker, never silently calendar-bucketed.
            String ankerLid = model.startpuntAnkerLid(a.objecttype(), a.attribuut());
            boolean ongeankerd = temporeelOngeankerd;
            String anker = ankerLid == null ? null : SUBJECT + "." + getter(ankerLid) + "()";
            String tl = temporeelTl;   // pre-lowered above (before the acc.clausules flush) so a collectList accumulate emits
            if (tl != null && anker != null) {
                // IDEMPOTENT anchored write (the temporal analogue of the scalar Objects.equals guard): the
                // @watch on the startpunt attribute re-fires this rule when the anchor lands, and a sibling
                // anchored writer's modify would otherwise ping-pong with it forever. rsTijdreeksGelijk
                // compares per-segment (Segment has no equals); the producer is pure, so evaluating twice is
                // safe. Scoped to ANCHORED writes only — the calendar emission (and its byte-pins) unchanged.
                fnImport("rsTijdreeksGelijk");
                rule.dan("    if (!rsTijdreeksGelijk(" + SUBJECT + "." + getter(a.attribuut()) + "(), " + tl + ")) {\n"
                        + "    modify(" + SUBJECT + ") {\n        " + setter(a.attribuut()) + "( " + tl + " )\n    }\n"
                        + "    }\n");
            } else {
                rule.dan("    modify(" + SUBJECT + ") {\n        " + setter(a.attribuut()) + "( "
                        + (tl != null ? tl : todo(ongeankerd ? "tijdlijn met variabel startpunt zonder startpuntbepaling (§9.9)"
                                                             : "temporele toekenning", r.waarde().bron())) + " )\n    }\n");
            }
        } else if (r.doel() instanceof Expr.AttribuutRef a && a.navigatieRol() == null && a.objecttype() != null
                && a.objecttype().attribuutGranulariteit.containsKey(a.attribuut())
                && a.objecttype().attribuutTijdlijnAantal.containsKey(a.attribuut())) {
            // §13.3.6.7 a MULTI-PERIOD temporal doel ("voor elke N maanden") is a List<Segment> timeline whose WRITE
            // producer semantics are under-specified. MARKER it honestly — never fall through to a SCALAR setter on a
            // List<Segment> field (a silent type-mismatched write the bare value branch below would otherwise emit).
            // Mirrors the exec multi-period doel skip.
            rule.dan("    modify(" + SUBJECT + ") {\n        " + setter(a.attribuut()) + "( "
                    + todo("meer-periodieke temporele toekenning (§13.3.6.7)", r.waarde().bron()) + " )\n    }\n");
        } else if (r.doel() instanceof Expr.AttribuutRef a && a.navigatieRol() == null && a.objecttype() != null
                && a.objecttype().kenmerkGranulariteit.containsKey(a.attribuut())) {
            // §8.4/§13.4.5 a TEMPORAL KENMERK doel (kenmerkGranulariteit — its field is a List<Segment> TRUE-period
            // timeline, not a boolean) cannot take the scalar `setK(true)` the bare value branch below would emit:
            // that write does not even compile ("Unable to Analyse Expression" — a boolean into a List field). The
            // clip that lowers the rule's voorwaarde/geldigheid into a List<Segment> of TRUE-periods is an
            // under-specified WRITE producer (§8.4 fixes the READ side — "gedurende de tijd dat <temporeel kenmerk>"
            // — but not the toekenning's periodization), so MARKER it honestly. Mirrors the exec back-end's skip
            // (ExecModelTranspiler: "temporele kenmerktoekenning — latere fase"), keeping the two symmetric. The
            // marker is COMMENT-ONLY (no modify): a `setK(null)` write would WIPE a seeded timeline the exec skip
            // leaves intact — a destructive "marker" and an asymmetry, not an inert one.
            rule.dan("    /* TODO " + commentVeilig("temporele kenmerktoekenning (§8.4/§13.4.5): " + r.doel().bron()) + " */\n");
        } else if (valueStr != null && r.doel() instanceof Expr.AttribuutRef a) {
            // restate the value in the target's unit (§4); §6.1.2 forbids AUTO-rounding —
            // if the result has too many decimals the author must add an explicit afronding.
            if (r.waarde().type().isNumeriek())
                valueStr = omrekenWaarde(valueStr, r.waarde().type(), a.type());
            // §6.1.2 write boundary: collapse any remaining exact Breuk to its decimal value BEFORE the setter, so a
            // Breuk never reaches a BigDecimal field (mvel strict-mode resolves setX(BigDecimal), never setX(Object))
            // and the idempotent Objects.equals guard compares like-typed values. Only a COMPUTED value can be a Breuk
            // (arithmetic / percentage / division / a non-terminating rational literal), so a bare attribute/parameter
            // copy is left unwrapped (no churn, never a Breuk). Never wrap a MARKER placeholder. The wrap is an
            // identity for a terminating BigDecimal (rsWaarde = toBd), so it is inert where it does fire.
            if (kanBreukOpleveren(r.waarde()) && !valueStr.marker()) {
                fnImport("rsWaarde");
                valueStr = Frag.from("rsWaarde(" + valueStr.text() + ")", valueStr);
            }
            // IDEMPOTENT write (the exec back-end's werkBij, mirrored): modify only when the value actually
            // CHANGES, so recomputation reaches a FIXPOINT. Complex mvel constraints (function calls,
            // navigated reads) make a pattern CLASS-reactive — it re-fires on EVERY subject update — and the
            // @watch freshness above makes readers re-fire on real input changes; without this guard two such
            // rules over one subject ping-pong forever (NO_LOOP blocks only the self-retrigger). A same-valued
            // rebuild skips the modify, so nothing re-propagates and the chain terminates. The value
            // expression is pure (rs* helpers are side-effect-free), so evaluating it twice is safe.
            // mvel PROPERTY access in the guard (not an explicit getter call): a boolean kenmerk exposes
            // is<X>(), and mvel's property navigation resolves either accessor form.
            rule.dan("    if (!java.util.Objects.equals(" + SUBJECT + "." + ident(a.attribuut(), false) + ", " + valueStr.text() + ")) {\n"
                    + "    modify(" + SUBJECT + ") {\n        " + setter(a.attribuut()) + "( " + valueStr.text() + " )\n    }\n"
                    + "    }\n");
        } else {
            rule.dan("    // onopgelost doel: " + commentVeilig(r.doel().bron()) + "\n");
        }
        markeerGevuurd(rule, r.naam());   // §8.1.9 prepend the RuleFired insert iff this rule's firing is referenced
        out.append(rule.render());
        // §6.5/§6.6 fout companions are gated by the rule's INLINE constraint PLUS its hoisted join clauses. A
        // far-side condition is a separate exists/not clause, not an inline constraint, so the companion must
        // carry it too — otherwise it loses the membership gate and over-fires (raising the Inconsistentie for
        // subjects the rule never acts on). Threading lc.extra() into the companion fires it exactly when the
        // division/wortel would actually execute: the inline constraint holds AND the membership holds. (The
        // companion is insert-only, so adding an exists/not clause is safe — it cancels no sibling activation,
        // unlike a modify+no-loop rule. The own-attribute operand scope limit is unchanged.)
        if (subject != null) {
            deelDoorLeegFout(acc, r, subject, constraint, lc.marker(), lc.extra());
            wortelNegatiefFout(acc, r, subject, constraint, lc.marker(), lc.extra());
            startpuntLeegFout(acc, r, subject, constraint, lc.marker(), lc.extra());
        }
    }

    /** §6.8: a leeg startpunt-anker is een Fout — surfaced through the §9.5 Inconsistentie channel by a
     *  separate insert-only companion on every §9.9 startpunt write whose anchor decomposes to a
     *  re-readable leeg guard (the subject's OWN attribute or a to-ONE navigation). A datum-arithmetic
     *  anchor ("datum van vertrek min DE TERMIJN") is leeg exactly when its leftmost datum operand is —
     *  Tabel 15: a leeg duration counts as 0/unchanged, only a leeg datum propagates. A parameter or
     *  function anchor still degrades to a leeg write but raises no Inconsistentie (the same scope limit
     *  as the §6.5/§6.6 companions). The write itself needs no change: a leeg anchor writes leeg. */
    private void startpuntLeegFout(Acc acc, ResolvedRegel r, Objecttype subject, String voorwaarde, boolean voorwaardeMarker, List<String> extraClausules) {
        if (voorwaardeMarker) return;
        if (!(r.doel() instanceof Expr.AttribuutRef d) || !model.isStartpuntLid(subject, d.attribuut())) return;
        Expr wortel = r.waarde();
        while (wortel instanceof Expr.Binair b) wortel = b.links();
        OperandDeel anker = companionDeel(acc, wortel, subject);
        if (anker == null) return;
        String pattern = (voorwaarde.isEmpty() ? "" : voorwaarde + ", ") + anker.leeg();
        Regel rule = new Regel(r.naam() + " (leeg startpunt)")
            .clausule(SUBJECT + " : " + ident(subject.naam, true) + "( " + pattern + " )");
        for (String e : extraClausules) rule.clausule(e);   // the host rule's far-side membership gate, if any
        out.append(rule
            .geldigheid(r.geldigheid())
            .clausule(inconsistentieGuard(r.naam()))
            .dan(inconsistentieDan(r.naam()))
            .render());
    }

    /** §6.6: "de wortel van" a NEGATIVE radicand is a Fout (RegelSpraak has no complex numbers). The value side
     *  already degrades it to leeg (rsWortel returns null for a negative radicand, instead of letting
     *  BigDecimal.sqrt throw and abort the session); this surfaces the Fout through the §9.5 Inconsistentie
     *  channel — a SEPARATE rule per wortel that fires when the radicand is negative (and the host voorwaarde
     *  holds). Scope, like the division fout: only a radicand that is the subject's OWN attribute lowers to a
     *  plain re-readable property constraint; a navigated/parameter/nested/aggregation radicand still degrades
     *  to leeg but raises no Inconsistentie (carded). A leeg radicand is leeg, not a fout (§6.6), and the guard
     *  radicand &lt; 0 is false for a null, so it does not fire for leeg. */
    private void wortelNegatiefFout(Acc acc, ResolvedRegel r, Objecttype subject, String voorwaarde, boolean voorwaardeMarker, List<String> extraClausules) {
        if (voorwaardeMarker) return;
        List<Expr.Functie> wortels = new ArrayList<>();
        verzamelWortels(r.waarde(), wortels);
        List<String> guards = new ArrayList<>();   // the "radicand < 0" guard per qualifying wortel
        for (Expr.Functie w : wortels)
            if (w.argumenten().size() == 1) {
                OperandDeel rd = companionDeel(acc, w.argumenten().get(0), subject);
                if (rd != null)
                    // a navigated radicand must guard its root before the "." dereference (mvel is not null-safe);
                    // an own attribute reads `basis < 0` directly (a null is false for "< 0", no NPE) — byte-identical.
                    guards.add(rd.genavigeerd()
                            ? rd.vol() + " && " + rd.waarde() + " < new java.math.BigDecimal(\"0\")"
                            : rd.waarde() + " < new java.math.BigDecimal(\"0\")");
            }
        for (int i = 0; i < guards.size(); i++) {
            String pattern = (voorwaarde.isEmpty() ? "" : voorwaarde + ", ") + guards.get(i);
            Regel rule = new Regel(r.naam() + " (wortel van negatief" + (guards.size() > 1 ? " " + (i + 1) : "") + ")")
                .clausule(SUBJECT + " : " + ident(subject.naam, true) + "( " + pattern + " )");
            for (String e : extraClausules) rule.clausule(e);   // the host rule's far-side membership gate, if any
            out.append(rule
                .geldigheid(r.geldigheid())
                .clausule(inconsistentieGuard(r.naam()))
                .dan(inconsistentieDan(r.naam()))
                .render());
        }
    }

    /** Collect every "de wortel van" function node reachable through the wrapper layers (Functie args / Binair /
     *  Afgerond / Begrensd) of a value expression — for the §6.6 negative-radicand fout surfacing. */
    /** The subject's OWN attribute properties a value expression READS (the @watch set): every EIGEN
     *  AttribuutRef in the tree, as bean property names. Navigated/aggregation reads live in their own
     *  patterns (with their own reactivity) and are not the subject pattern's to watch. */
    private void verzamelEigenReads(Expr e, Objecttype subject, java.util.Set<String> uit) {
        if (e instanceof Expr.AttribuutRef a) {
            if (naviResolve(a, subject).soort() == NaviSoort.EIGEN) uit.add(ident(a.attribuut(), false));
        } else if (e instanceof Expr.Binair b) {
            verzamelEigenReads(b.links(), subject, uit);
            verzamelEigenReads(b.rechts(), subject, uit);
        } else if (e instanceof Expr.Functie f) {
            for (Expr a : f.argumenten()) verzamelEigenReads(a, subject, uit);
        } else if (e instanceof Expr.Afgerond af) {
            verzamelEigenReads(af.basis(), subject, uit);
        } else if (e instanceof Expr.Begrensd bg) {
            verzamelEigenReads(bg.basis(), subject, uit);
            verzamelEigenReads(bg.minimum(), subject, uit);
            verzamelEigenReads(bg.maximum(), subject, uit);
        } else if (e instanceof Expr.TekstReeks tr) {
            for (Expr.TekstReeks.Deel d : tr.delen())
                if (d instanceof Expr.TekstReeks.Ingevoegd i) verzamelEigenReads(i.expr(), subject, uit);
        } else if (e instanceof Expr.DimensieCel dc) {
            verzamelEigenReads(dc.attribuut(), subject, uit);
        }
    }

    private static void verzamelWortels(Expr e, List<Expr.Functie> uit) {
        if (e instanceof Expr.Functie f) {
            if (f.kind() == Expr.FunctieKind.WORTEL) uit.add(f);
            for (Expr a : f.argumenten()) verzamelWortels(a, uit);
        } else if (e instanceof Expr.Binair b) {
            verzamelWortels(b.links(), uit);
            verzamelWortels(b.rechts(), uit);
        } else if (e instanceof Expr.Afgerond af) {
            verzamelWortels(af.basis(), uit);
        } else if (e instanceof Expr.Begrensd bg) {
            verzamelWortels(bg.basis(), uit);
            verzamelWortels(bg.minimum(), uit);
            verzamelWortels(bg.maximum(), uit);
        }
    }

    /** §6.5 / Tabel 12: a "waarde gedeeld door leeg" is a Fout. The value side already degrades it to leeg
     *  (rsGedeeld returns null for a non-empty dividend over an empty divisor); this surfaces the Fout through
     *  the §9.5 Inconsistentie channel — a SEPARATE rule per division that fires when the dividend is present
     *  and the divisor is empty (and the host rule's voorwaarde holds, so the division would actually execute).
     *  Scope: both operands must lower to a re-readable leeg/vol guard — the subject's OWN attribute, or one
     *  reached by a to-ONE navigation (`partner.inkomen / partner.aftrek`), guarded against a null root. A
     *  literal / parameter / to-many / unresolvable operand still degrades to leeg but raises no Inconsistentie
     *  (carded). An empty LEFT short-circuits to 0 (no fout), so the guard is dividend != null AND divisor ==
     *  null, never merely divisor == null. */
    private void deelDoorLeegFout(Acc acc, ResolvedRegel r, Objecttype subject, String voorwaarde, boolean voorwaardeMarker, List<String> extraClausules) {
        if (voorwaardeMarker) return;          // the host rule is itself a no-op marker -> no division fires
        List<Expr.Binair> delingen = new ArrayList<>();
        verzamelDelingen(r.waarde(), delingen);
        List<String> patronen = new ArrayList<>();   // "dividend-vol, divisor-leeg" per qualifying division
        for (Expr.Binair d : delingen) {
            OperandDeel dividend = companionDeel(acc, d.links(), subject), divisor = companionDeel(acc, d.rechts(), subject);
            if (dividend != null && divisor != null) patronen.add(dividend.vol() + ", " + divisor.leeg());
        }
        for (int i = 0; i < patronen.size(); i++) {
            String pattern = (voorwaarde.isEmpty() ? "" : voorwaarde + ", ") + patronen.get(i);
            Regel rule = new Regel(r.naam() + " (deling door leeg" + (patronen.size() > 1 ? " " + (i + 1) : "") + ")")
                .clausule(SUBJECT + " : " + ident(subject.naam, true) + "( " + pattern + " )");
            for (String e : extraClausules) rule.clausule(e);   // the host rule's far-side membership gate, if any
            out.append(rule
                .geldigheid(r.geldigheid())
                .clausule(inconsistentieGuard(r.naam()))
                .dan(inconsistentieDan(r.naam()))
                .render());
        }
    }

    /** Collect every "gedeeld door"/"gedeeld door (ABS)" node reachable through the arithmetic-wrapper layers
     *  (Binair / Afgerond / Begrensd) of a value expression — for the §6.5 fout surfacing. Aggregation operands
     *  are not descended (an aggregation divisor cannot be a plain re-readable guard). */
    private static void verzamelDelingen(Expr e, List<Expr.Binair> uit) {
        if (e instanceof Expr.Binair b) {
            if (b.op() == Expr.BinOp.GEDEELD_DOOR || b.op() == Expr.BinOp.GEDEELD_DOOR_ABS) uit.add(b);
            verzamelDelingen(b.links(), uit);
            verzamelDelingen(b.rechts(), uit);
        } else if (e instanceof Expr.Afgerond af) {
            verzamelDelingen(af.basis(), uit);
        } else if (e instanceof Expr.Begrensd bg) {
            verzamelDelingen(bg.basis(), uit);
            verzamelDelingen(bg.minimum(), uit);
            verzamelDelingen(bg.maximum(), uit);
        }
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
     *  a single to-one navigation through {@code rol}; {@code KETEN} = a resolved §13.4.1 MULTI-hop to-one
     *  chain (the ref's navigatiePad, re-validated against this subject) — lowered only by the value read,
     *  every other emitter treats it as a marker case; the rest are the marker cases. */
    private enum NaviSoort { EIGEN, GENAVIGEERD, KETEN, AFWEZIG, NAAR_VEEL, ONOPGELOST }
    private record NaviRef(NaviSoort soort, String rol) {}

    private NaviRef naviResolve(Expr.AttribuutRef a, Objecttype subject) {
        if (attribuutOntbreekt(a)) return new NaviRef(NaviSoort.AFWEZIG, null);
        if (!a.navigatiePad().isEmpty()) {
            // A chain is lowered ONLY when the pad re-walks to-one from THIS subject and lands on the type
            // that declares the attribute (volgKeten) — a chain resolved against another subject markers.
            List<Objecttype> bereikt = model.volgKeten(subject, a.navigatiePad());
            boolean ok = bereikt != null && bereikt.get(bereikt.size() - 1) == a.objecttype();
            return new NaviRef(ok ? NaviSoort.KETEN : NaviSoort.ONOPGELOST, null);
        }
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

    /** §13.3.6/§8.2: whether {@code a} reads a tijdsafhankelijk (temporal) attribute — its runtime field is a
     *  piecewise {@code List<Segment>} timeline, not a scalar. A BARE read of one in a scalar slot (a when-side
     *  comparison/predicate operand, or a then-side value term) would splice the List into a scalar position — the
     *  List-in-scalar mismatch that sinks the WHOLE KieBuilder package, an accepted input compiling to crashing/
     *  silently-wrong DRL. The LEGITIMATE temporal reads (§7.1 het totaal van / §7.3.2 tijdsevenredig deel / the
     *  §7.1 gedurende clip) intercept the {@link Expr.AttribuutRef} upstream ({@link #tijdreeksAggregatie} /
     *  {@link #temporeleWaarde}) and build the timeline getter directly, so a temporal AttribuutRef reaching the
     *  bare {@code waarde}/{@code operand} leaf is always the not-yet-lowered scalar read — marker it. This also
     *  subsumes the §8.2 "gedurende het gehele jaar / de gehele maand" whole-period qualifier, which the grammar
     *  parses (gehelePeriodevergelijking) but no lowering yet honours: its onderwerp is by definition tijdsafhankelijk
     *  (§8.2 line 3437), so the predicate markers here rather than silently dropping the period check. Detects an own
     *  OR a to-one navigated temporal read alike ({@code a.objecttype()} is the type that DECLARES the attribute). */
    private static boolean isTemporeelAttribuut(Expr.AttribuutRef a) {
        return a.objecttype() != null && a.objecttype().attribuutGranulariteit.containsKey(a.attribuut());
    }

    /** The getter chain for an attribute read relative to the subject — the EIGEN own-attribute, a single
     *  navigated reference, or a marker for an absent/naar-veel/unresolvable navigation. Shared by the plain
     *  value read and the dimensioned-read forms (which need the bare getter — for a dimensioned attribute
     *  the Map field — before indexing a label), so the latter bypass the §3.6 under-specified guard. */
    private Frag attribuutGetter(Expr.AttribuutRef a, Objecttype subject) {
        NaviRef n = naviResolve(a, subject);
        return switch (n.soort()) {
            case EIGEN       -> Frag.ok(SUBJECT + "." + getter(a.attribuut()) + "()");
            case GENAVIGEERD -> Frag.ok(SUBJECT + "." + getter(n.rol()) + "()." + getter(a.attribuut()) + "()");
            case NAAR_VEEL   -> Frag.todo("naar-veel-navigatie", a.bron());
            default          -> Frag.todo("navigatie", a.bron());   // AFWEZIG / ONOPGELOST
        };
    }

    /** How a +/- right operand is restated in the left's unit — the null-guarded ternary form on the value side
     *  ({@link #omrekenWaarde}), the null-safe rsHerschaalExact helper form in a constraint ({@link #herschaalConditie}). */
    @FunctionalInterface private interface Omrekenen { Frag apply(Frag expr, Datatype van, Datatype naar); }

    /** §6.2-6.8 binary arithmetic, shared by the value side ({@link #waarde}) and the condition side
     *  ({@link #operand}). The sub-operands are lowered by {@code arg} (a $subject getter on the value side, a
     *  bare property on the condition side); a +/- right operand is restated in the left's unit by {@code omrekenen}.
     *  Every leegwaarde form is a registered helper or inline arithmetic (rsNul/rsGedeeld/rsGedeeldAbs/rsVerminderd/
     *  rsMacht), i.e. a method call valid in either a modify-block or a pattern constraint — the only ternary is in
     *  {@code omrekenen}, which the condition side supplies ternary-free. A marker operand propagates unwrapped:
     *  {@link Frag#from} ORs its {@link Frag#marker} flag into the result. */
    private Frag binairTerm(Acc acc, Expr.Binair b, Function<Expr, Frag> arg, Omrekenen omrekenen) {
        if (b.op() == Expr.BinOp.TOT_DE_MACHT) {
            // §13.4.17.14: an INTEGER (literal) exponent — the common case — is computed exactly with
            // BigDecimal.pow(int); going through Math.pow (a double) would lose integer precision above 2^53
            // (7^19 off by 1) before the mandatory afronding. §6.7: any empty operand -> leeg, so the base goes
            // through a null-guarding helper (the integer exponent is a literal, never empty). A marker base
            // propagates unwrapped, like the other operators.
            Integer n = geheelExponent(b.rechts());
            if (n != null) {
                Frag l = arg.apply(b.links());
                if (l.marker()) return Frag.from("(" + l.text() + ").pow(" + n + ")", l);
                fnImport("rsMacht");
                return Frag.from("rsMacht(" + l.text() + ", " + n + ")", l);
            }
            // a genuinely fractional/runtime exponent: Math.pow yields a double, wrapped as a BigDecimal.
            Frag l = arg.apply(b.links()), r = arg.apply(b.rechts());
            if (l.marker() || r.marker())
                return Frag.from("java.math.BigDecimal.valueOf(Math.pow((" + l.text() + ").doubleValue(), (" + r.text() + ").doubleValue()))", l, r);
            fnImport("rsMachtReeel");
            return Frag.from("rsMachtReeel(" + l.text() + ", " + r.text() + ")", l, r);
        }
        if (b.op() == Expr.BinOp.PERCENTAGE_VAN) {
            // §486: route through the Breuk-aware rsPercentageVan (single-runtime, shared with the exec back-end) so
            // a non-terminating percentage ((1/3)% van 600) keeps its exact rational until an afronding — inline mvel
            // `l/100*r` computes at DECIMAL128 and would diverge. leeg counts as 0 inside the helper.
            Frag l = arg.apply(b.links()), r = arg.apply(b.rechts());
            fnImport("rsPercentageVan");
            return Frag.from("rsPercentageVan(" + l.text() + ", " + r.text() + ")", l, r);
        }
        if (b.op() == Expr.BinOp.GEDEELD_DOOR) {
            // §6.5 Tabel 12 leegwaarde: an empty LEFT (dividend) short-circuits to 0 — even leeg/leeg = 0; a
            // non-empty value over an empty divisor is a "fout", lowered as leeg (null) and surfaced as an
            // Inconsistentie by the companion rule. rsGedeeld carries that order and the null-guard a bare .divide
            // would NPE on.
            Frag l = arg.apply(b.links()), r = arg.apply(b.rechts());
            if (l.marker() || r.marker())
                return Frag.from("(" + l.text() + ").divide(" + r.text() + ", java.math.MathContext.DECIMAL64)", l, r);
            fnImport("rsGedeeld");
            return Frag.from("rsGedeeld(" + l.text() + ", " + r.text() + ")", l, r);
        }
        if (b.op() == Expr.BinOp.GEDEELD_DOOR_ABS) {
            // §6.5: "gedeeld door (ABS)" has at most 5 decimals, rounded TOWARD ZERO (divide(d, 5, DOWN)); same
            // §6.5 leegwaarde as plain gedeeld door (left leeg -> 0, value/leeg -> leeg).
            Frag l = arg.apply(b.links()), r = arg.apply(b.rechts());
            if (l.marker() || r.marker())
                return Frag.from("(" + l.text() + ").divide(" + r.text() + ", 5, java.math.RoundingMode.DOWN)", l, r);
            fnImport("rsGedeeldAbs");
            return Frag.from("rsGedeeldAbs(" + l.text() + ", " + r.text() + ")", l, r);
        }
        if (b.op() == Expr.BinOp.MAAL) {  // §6.4: units multiply, operands are NOT converted to match
            // §389: route through the Breuk-aware rsMaal (single-runtime) so exactness propagates when an operand is
            // a Breuk (9 maal 1/3 = 3), else the fast BigDecimal path. §6.4 Tabel 10: any empty operand -> 0 (in helper).
            Frag l = arg.apply(b.links()), r = arg.apply(b.rechts());
            if (l.marker() || r.marker()) return Frag.from("(" + l.text() + " * " + r.text() + ")", l, r);
            fnImport("rsMaal");
            return Frag.from("rsMaal(" + l.text() + ", " + r.text() + ")", l, r);
        }
        // §6/§13.4.16 date arithmetic: a datum/datumtijd ± a numeric-with-time-unit -> a new date (optellen=later,
        // aftrekken=earlier; an aantal of 0 leaves the date unchanged). NOT mvel "+": "LocalDate + BigDecimal" is a
        // type error that breaks the Drools build. Lower via the Java-bodied rsDatumReken helper (.plus/.minus keyed
        // on the ChronoUnit), exactly like rsTijdsduur — the enum/overload resolve cleanly in plain Java, not mvel.
        if (b.links().type().isDatum() || b.rechts().type().isDatum()) {
            boolean datumLinks = b.links().type().isDatum();
            Expr datumExpr = datumLinks ? b.links() : b.rechts();
            Expr durExpr   = datumLinks ? b.rechts() : b.links();
            boolean optellen = b.op() == Expr.BinOp.PLUS;
            // "datum - duur" subtracts; "duur - datum" is not a date, and two datums added/subtracted is not date
            // arithmetic (a datum-difference is the §6.10 tijdsduur expression) -> honest marker.
            if (durExpr.type().isDatum() || (!optellen && !datumLinks))
                return Frag.todo("datum-rekenkunde (datum +/- tijdsduur; geen datum-datum)", b.bron());
            // §6.11 datum ± N <dagsoort> ("5 werkdagen"): not a fixed ChronoUnit — step day-by-day counting only the
            // dagsoort days (the §9.8 predicate). X never counts; the result is always a dagsoort day; 0/leeg -> X
            // unchanged. A dagsoort is day-granular, so the date operand must be a Datum in dagen (a LocalDate).
            io.github.yhavinga.regelspraak.model.Eenheid durEenheid = durExpr.type().eenheidOf();
            String dagsoortUnit = durEenheid != null ? model.dagsoortVoorEenheid(durEenheid.afkorting()) : null;
            if (dagsoortUnit != null) {
                // §4.1/§4.2 Tabel 2/5 rule 2: a datum-EN-tijd left operand yields a datum-en-tijd result — step over the
                // date part and re-attach the time-of-day (rsDatumTijdPlusDagsoort). A Datum-in-dagen keeps the LocalDate
                // path. The walking loop lives in the runtime; the §9.8 predicate rides a rsDagsoort_<Naam> global.
                boolean datumTijd = datumExpr.type() instanceof Datatype.DatumTijd;
                String glob = dagsoortGlobaal(dagsoortUnit);
                Frag d = arg.apply(datumExpr), n = arg.apply(durExpr);
                if (glob == null || d.marker() || n.marker()) return Frag.todo("datum-rekenkunde (dagsoort)", b.bron());
                String stapFn = fnImport(datumTijd ? "rsDatumTijdPlusDagsoort" : "rsDatumPlusDagsoort");
                String cast = datumTijd ? "java.time.LocalDateTime" : "java.time.LocalDate";
                return Frag.from("((" + cast + ") " + stapFn + "(" + d.text() + ", " + n.text() + ", " + glob + ", " + optellen + "))", d, n);
            }
            String unit = Tijdrekening.chronoUnit(durExpr.type());
            if (unit == null) return Frag.todo("datum-rekenkunde (dagsoort/onbekende tijdseenheid)", b.bron());
            boolean tijdEenheid = switch (unit) { case "HOURS", "MINUTES", "SECONDS", "MILLIS" -> true; default -> false; };
            if (tijdEenheid && !(datumExpr.type() instanceof Datatype.DatumTijd))
                return Frag.todo("datum-rekenkunde: een tijdseenheid vereist een datum-tijd operand (§6.10)", b.bron());
            Frag d = arg.apply(datumExpr), n = arg.apply(durExpr);
            if (d.marker() || n.marker()) return Frag.todo("datum-rekenkunde", b.bron());
            fnImport("rsDatumReken");
            String cast = datumExpr.type() instanceof Datatype.DatumTijd ? "java.time.LocalDateTime" : "java.time.LocalDate";
            return Frag.from("((" + cast + ") rsDatumReken(" + d.text() + ", " + n.text() + ", \"" + unit + "\", " + optellen + "))", d, n);
        }
        // §4: restate the right operand in the left operand's unit before +/- so a convertible-but-different unit
        // (mnd vs jr) is scaled, not added raw.
        Frag links = arg.apply(b.links());
        Frag rechts = omrekenen.apply(arg.apply(b.rechts()), b.rechts().type(), b.links().type());
        if (links.marker() || rechts.marker())     // a marker propagates unwrapped (no leegwaarde noise)
            return Frag.from("(" + links.text() + " " + infix(b.op()) + " " + rechts.text() + ")", links, rechts);
        // §6.3 Tabel 7/8 — the ONE place "min" and "verminderd met" differ: both subtract, but a "verminderd met"
        // whose LEFT operand is empty yields empty (rsVerminderd), while "min" treats an empty operand as 0 like
        // plus (rsNul). Without this they lowered identically — a silent-wrong for a leeg-left case.
        if (b.op() == Expr.BinOp.VERMINDERD_MET) {
            fnImport("rsVerminderd");
            return Frag.from("rsVerminderd(" + links.text() + ", " + rechts.text() + ")", links, rechts);
        }
        // §6.2/§6.3: route PLUS/MIN through the Breuk-aware rsPlus/rsMin (single-runtime, shared with the exec
        // back-end) so exactness propagates when an operand is a Breuk; leeg counts as 0 inside the helper. Inline
        // mvel `l + r` would compute at DECIMAL128 and cannot carry a Breuk. (A marker propagated above.)
        String plusFn = fnImport(b.op() == Expr.BinOp.PLUS ? "rsPlus" : "rsMin");
        return Frag.from(plusFn + "(" + links.text() + ", " + rechts.text() + ")", links, rechts);
    }

    // ---------------- value expression ("then" side: getters) ----------------
    private Frag waarde(Acc acc, Expr e, Objecttype subject) {
        if (e instanceof Expr.Literal lit)      return literal(lit);
        if (e instanceof Expr.TekstReeks tr)    return tekstReeks(acc, tr, x -> waarde(acc, x, subject));
        if (e instanceof Expr.ParameterRef p)   // §13.3.6 a TEMPORAL parameter is a List<Segment> global — only a
            // §7.3.2 tijdsevenredig base may read it (handled there); any scalar use is a List-in-scalar mismatch -> marker.
            return p.parameter().granulariteit() != null ? Frag.todo("temporele parameter in scalaire context", p.bron())
                    : Frag.ok(ident(p.parameter().naam(), false));
        if (e instanceof Expr.AttribuutRef a) {
            // §3.6: a gedimensioneerd attribuut read WITHOUT pinning its dimensions is under-specified (it
            // would emit a bare Map getter where a scalar is expected). A legitimate dimensioned read is an
            // Expr.DimensieCel; a bare AttribuutRef to a dimensioned attribute is therefore an error — marker.
            if (isGedimensioneerd(a)) return Frag.todo("ongedimensioneerde lezing van gedimensioneerd attribuut (§3.6)", a.bron());
            // §13.3.6/§8.2: a bare scalar read of a tijdsafhankelijk attribuut (a List<Segment> timeline) markers —
            // the legitimate temporal value reads are intercepted upstream (see isTemporeelAttribuut).
            if (isTemporeelAttribuut(a)) return Frag.todo("tijdsafhankelijk attribuut in scalaire context (§8.2)", a.bron());
            NaviRef n = naviResolve(a, subject);
            if (n.soort() == NaviSoort.GENAVIGEERD) {
                // §6.9 / §13.3.9: a value read THROUGH a to-one reference that is empty is leeg, not a crash. The
                // modify-block consequence runs for EVERY instance of the subject type (the rule's pattern is over
                // that type), including ones whose reference is null — and a bare $subject.getPartner().getX()
                // NPEs there, which inside a Drools consequence aborts the WHOLE fireAllRules pass. Guard the root
                // so a null reference yields null (the leegwaarde), which the surrounding helpers then propagate.
                // (The condition side already guards via genavigeerdLeegGevuld / "!."; only this value read did not.)
                String root = SUBJECT + "." + getter(n.rol()) + "()";
                return Frag.ok("(" + root + " == null ? null : " + root + "." + getter(a.attribuut()) + "())");
            }
            if (n.soort() == NaviSoort.KETEN) {
                // §13.4.1/§13.3.9 multi-hop to-one chain: one getter per declared role hop, EVERY relation prefix
                // null-guarded so an intervening leeg relation reads as leeg (never an NPE-abort) — the chain
                // generalisation of the single-hop root-guard above.
                List<String> guards = new ArrayList<>();
                String pad = SUBJECT;
                for (String rol : a.navigatiePad()) {
                    pad = pad + "." + getter(rol) + "()";
                    guards.add(pad + " == null");
                }
                return Frag.ok("(" + String.join(" || ", guards) + " ? null : " + pad + "." + getter(a.attribuut()) + "())");
            }
            return attribuutGetter(a, subject);
        }
        if (e instanceof Expr.DimensieCel c) {
            // §3.6 a single dimensioned cell reads the attribute's Map<label,value> field and indexes the
            // selected label. Scope: a single-dimension attribute (one label); a multi-dimension composite
            // key is deferred to a marker. An unresolvable underlying read (e.g. a navigation) markers too.
            Frag map = attribuutGetter(c.attribuut(), subject);
            if (map.marker() || c.labels().size() != 1) return Frag.todo("dimensiecel", c.bron());
            // §6.9/§13.3.9: a null gedimensioneerd Map (a default-constructed fact) reads as LEEG, not a crash — guard
            // the .get so it does not NPE-abort the consequence (the exec-model's scalarWaarde DimensieCel is already
            // null-safe; this mirrors the value-side GENAVIGEERD root-guard so both back-ends read a null Map as leeg).
            String cel = map.text(), label = c.labels().get(0);
            return Frag.ok("(" + cel + " == null ? null : (" + cel + ").get(" + drlString(label) + "))");
        }
        if (e instanceof Expr.Binair b)
            // §6.2-6.8 arithmetic, shared with the condition side: value getters for the sub-operands, and a
            // +/- right operand restated in the left's unit by the NULL-GUARDED omrekenWaarde (a "then"-side
            // ternary is fine here).
            return binairTerm(acc, b, x -> waarde(acc, x, subject), this::omrekenWaarde);
        if (e instanceof Expr.Afgerond af) {
            return afgerondeTerm(waarde(acc, af.basis(), subject), af);
        }
        if (e instanceof Expr.Begrensd bg) {
            // §4/§6.1.4: a convertible-but-different bound unit (a jr bound on a mnd basis) is
            // restated in the basis unit before the clamp compares magnitudes, exactly as the
            // +/- path does. No-op when the units match or are absent. Route through the Breuk-aware
            // rsBegrens (single-runtime, shared with the exec back-end): an inline mvel ternary
            // `r < m ? m : r` cannot compare a Breuk basis/bound, and clamps EXACTLY inside the helper.
            Frag r = waarde(acc, bg.basis(), subject);
            Datatype bt = bg.basis().type();
            Frag min = bg.minimum() == null ? null : omrekenWaarde(waarde(acc, bg.minimum(), subject), bg.minimum().type(), bt);
            Frag max = bg.maximum() == null ? null : omrekenWaarde(waarde(acc, bg.maximum(), subject), bg.maximum().type(), bt);
            java.util.List<Frag> delen = new java.util.ArrayList<>(); delen.add(r);
            if (min != null) delen.add(min);
            if (max != null) delen.add(max);
            fnImport("rsBegrens");
            return Frag.from("rsBegrens(" + r.text() + ", " + (min == null ? "null" : min.text())
                    + ", " + (max == null ? "null" : max.text()) + ")", delen.toArray(new Frag[0]));
        }
        if (e instanceof Expr.Constante c) {
            Frag rk = rekenConstante(c);   // §5.3 Rekendatum/Rekenjaar as a VALUE operand (§13.4.17.34)
            return rk != null ? rk : Frag.todo("constante", c.naam());
        }
        if (e instanceof Expr.Functie f) {
            Frag scalar = scalarFunctie(acc, f, x -> waarde(acc, x, subject)); // §13.4.17 single-value builtins (abs, datum-delen, datum-met)
            if (scalar != null) return scalar;
            Frag dim = dimensieAggregatie(acc, f, subject);      // SOM/min/max over a dimension's labels
            if (dim != null) return dim;
            Frag concat = concatenatieAggregatie(acc, f, subject); // pairwise min/max over listed operands
            if (concat != null) return concat;
            Frag tijd = tijdreeksAggregatie(acc, f, subject);    // §7.1 "het totaal van" a temporal attribute's timeline
            if (tijd != null) return tijd;
            Frag var = accumulate(acc, f, subject, false);       // hoists an accumulate into the "when" (value side: bare fold)
            return var != null ? var : Frag.todo("aggregatie " + f.kind(), f.bron());
        }
        return Frag.todo("expressie", e.bron());
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
    private Frag scalarFunctie(Acc acc, Expr.Functie f, Function<Expr, Frag> arg) {
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
                return Frag.todo("tijd-component uit een niet-datum-tijd (§13.4.17.21-.24)", f.bron());
            Frag d = arg.apply(a.get(0));
            if (d.marker()) return d;
            fnImport("rsDatumDeel");
            return Frag.from("rsDatumDeel(" + d.text() + ", java.time.temporal.ChronoField." + chronoField + ")", d);
        }
        return switch (f.kind()) {
            case ABSOLUTE_WAARDE -> {                 // §13.4.17.17 / §6.9 Tabel 20: |leeg| = leeg, so route
                if (a.size() != 1) yield null;        // through rsAbs (a null guard a constraint cannot ternary).
                Frag x = arg.apply(a.get(0));
                if (x.marker()) yield x;
                fnImport("rsAbs");
                yield Frag.from("rsAbs(" + x.text() + ")", x);
            }
            case WORTEL -> {                          // §13.4.17.13 — the radicand's root, rounded by the
                if (a.size() != 1) yield null;        // MANDATORY afronding (an Afgerond wrapper the resolver
                Frag x = arg.apply(a.get(0));         // adds, now null-safe). rsWortel does BigDecimal.sqrt at
                // 34-digit precision, NOT Math.sqrt whose ~16-digit double noise can flip a directional
                // (FLOOR/CEILING) afronding on a terminating root, and carries the §6.6 leegwaarde (leeg -> leeg)
                // so a leeg radicand propagates through the afronding. A NEGATIVE radicand also yields leeg (no
                // session-aborting throw); the §6.6 foutmelding is surfaced by wortelNegatiefFout's companion rule.
                if (x.marker()) yield x;
                fnImport("rsWortel");
                yield Frag.from("rsWortel(" + x.text() + ")", x);
            }
            case DATUM_MET     -> datumConstructor(a, arg, "java.time.LocalDate", 3, false);     // §13.4.17.35 (3 parts)
            case DATUMTIJD_MET -> datumConstructor(a, arg, "java.time.LocalDateTime", 7, true);  // §13.4.17.36 (7 parts)
            case EERSTE_PAASDAG -> {                  // §13.4.17.37 — Easter Sunday of the given year
                if (a.size() != 1) yield null;
                Frag j = arg.apply(a.get(0));
                if (j.marker()) yield j;
                fnImport("rsEerstePaasdag");
                yield Frag.from("rsEerstePaasdag(" + j.text() + ")", j);
            }
            default -> null;
        };
    }

    /** §13.4.17.35/.36 build a LocalDate/LocalDateTime from its {@code n} numeric component expressions
     *  (jaar, maand, dag[, uur, minuut, seconde, milliseconde]). Lowered via a registered Java-bodied helper
     *  (rsDatumMet / rsDatumtijdMet) rather than an inline {@code LocalDate.of((c).intValue(), …)}, because a
     *  §6.9 EMPTY component yields an empty (null) date — and a bare {@code (null).intValue()} would NPE inside
     *  the constructor at fire time, aborting the whole session (the same leegwaarde class the date-component
     *  helpers already close). The helper null-guards every component and carries the §13.4.17.36 milli→nano
     *  scale (LocalDateTime.of's 7th arg is nanoOfSecond) in plain Java. A marker argument propagates. */
    private Frag datumConstructor(List<Expr> a, Function<Expr, Frag> arg, String type, int n, boolean laatsteIsMilli) {
        if (a.size() != n) return null;
        List<String> args = new ArrayList<>();
        for (Expr e : a) {
            Frag v = arg.apply(e);
            if (v.marker()) return v;
            args.add(v.text());
        }
        if (laatsteIsMilli) { fnImport("rsDatumtijdMet"); return Frag.ok("rsDatumtijdMet(" + String.join(", ", args) + ")"); }
        fnImport("rsDatumMet");
        return Frag.ok("rsDatumMet(" + String.join(", ", args) + ")");
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
    private Frag tijdsduur(Expr.Functie f, Function<Expr, Frag> arg) {
        if (f.argumenten().size() != 2) return null;
        // §6.10 tijdsduur in N <dagsoort> ("in hele werkdagen"): COUNT the §9.8 dagsoort days in the interval
        // (signed; absolute when asked). The spec leaves the dagsoort boundary undefined; per §6.11's "X telt niet
        // mee" the interval is half-open (van, tot] (the §6.11-consistent reading). Day-granular -> Datum operands.
        io.github.yhavinga.regelspraak.model.Eenheid dagE = f.type().eenheidOf();
        String dagsoortUnit = dagE != null ? model.dagsoortVoorEenheid(dagE.afkorting()) : null;
        if (dagsoortUnit != null) {
            Expr a0 = f.argumenten().get(0), a1 = f.argumenten().get(1);
            // §4.16 (L676): both operands must be the SAME tijd-precisie. Both datum-EN-tijd is legal — the count is over
            // the DATE parts (rsTijdsduurTijd_<Naam>, no time carried). Mixed precision is spec-illegal -> honest marker.
            boolean dt0 = a0.type() instanceof Datatype.DatumTijd, dt1 = a1.type() instanceof Datatype.DatumTijd;
            if (dt0 != dt1)
                return Frag.todo("tijdsduur in een dagsoort vereist datum-operanden van gelijke tijd-precisie (§6.10)", f.bron());
            String glob = dagsoortGlobaal(dagsoortUnit);
            Frag van0 = arg.apply(a0), tot0 = arg.apply(a1);
            if (glob == null || van0.marker() || tot0.marker()) return Frag.todo("tijdsduur (dagsoort)", f.bron());
            String durFn = fnImport(dt0 ? "rsTijdsduurTijdDagsoort" : "rsTijdsduurDagsoort");
            return Frag.from(durFn + "(" + van0.text() + ", " + tot0.text() + ", " + glob + ", "
                    + (f.kind() == Expr.FunctieKind.ABSOLUTE_TIJDSDUUR) + ")", van0, tot0);
        }
        String unit = Tijdrekening.chronoUnit(f.type());
        if (unit == null) return Frag.todo("tijdsduur-eenheid (dagsoort / onbekende tijdseenheid)", f.bron());
        boolean tijdEenheid = switch (unit) { case "HOURS", "MINUTES", "SECONDS", "MILLIS" -> true; default -> false; };
        Expr eVan = f.argumenten().get(0), eTot = f.argumenten().get(1);
        if (tijdEenheid && (!(eVan.type() instanceof Datatype.DatumTijd) || !(eTot.type() instanceof Datatype.DatumTijd)))
            return Frag.todo("tijdsduur in een tijdseenheid vereist datum-tijd operanden (§6.10)", f.bron());
        Frag van = arg.apply(eVan), tot = arg.apply(eTot);
        if (van.marker() || tot.marker()) return Frag.todo("tijdsduur", f.bron());
        // Lower via a registered Java-bodied helper, NOT inline mvel: a fully-qualified ChronoUnit.<U>.between
        // does not analyse in a Drools mvel modify-block, and the helper also carries the §6.10 null-guard and
        // the absolute/sign in plain Java where the enum and the overload resolve cleanly.
        fnImport("rsTijdsduur");
        return Frag.from("rsTijdsduur(" + van.text() + ", " + tot.text() + ", \"" + unit + "\", "
                + (f.kind() == Expr.FunctieKind.ABSOLUTE_TIJDSDUUR) + ")", van, tot);
    }

    /** Wrap a value-side arithmetic operand for the §6.2/§6.4/§6.8 "leeg telt als 0" rekentabel via the rsNul
     *  helper. The caller ({@link #binairTerm}) has already excluded a marker operand (which must propagate
     *  unwrapped), so this only ever wraps a real value term. */
    private String nulVeilig(Acc acc, String operand) {
        fnImport("rsNul");
        return "rsNul(" + operand + ")";
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
    private Frag dimensieAggregatie(Acc acc, Expr.Functie f, Objecttype subject) {
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
        Frag map = attribuutGetter(a, subject);   // the dimensioned attribute reads as its Map<label,value> field
        if (map.marker()) return null;
        fnImport(fn);
        StringJoiner keys = new StringJoiner(", ");
        for (String label : sel.labels()) keys.add(drlString(label));   // embedded quotes ESCAPED (escaping by construction)
        String call = fn + "(" + map.text() + ", java.util.Arrays.asList(" + keys + "))";
        // §5.8.2: an all-empty SOM is a leegwaarde — substitute the declared "of … als die er niet zijn"
        // fallback when present, else leave the helper's null (an empty sum, never a silent 0). MIN/MAX of
        // an empty set is likewise the helper's null (no §5.8.2 fallback form).
        if (f.kind() == Expr.FunctieKind.SOM && f.leegwaarde() != null) {
            Frag lw = waarde(acc, f.leegwaarde(), subject);   // a markered fallback must still propagate
            return Frag.from("(" + call + " == null ? " + lw.text() + " : " + call + ")", lw);
        }
        return Frag.ok(call);
    }

    private Frag concatenatieAggregatie(Acc acc, Expr.Functie f, Objecttype subject) {
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
        Frag accF = waarde(acc, f.argumenten().get(0), subject);
        if (accF.marker()) return null;
        String vouw = accF.text();
        for (int i = 1; i < f.argumenten().size(); i++) {
            Expr arg = f.argumenten().get(i);
            Frag xF = omrekenWaarde(waarde(acc, arg, subject), arg.type(), doel);
            if (xF.marker()) return null;
            String x = xF.text();
            // null-skipping: fold empty → take x; x empty → keep fold; else compare. All-empty stays null.
            vouw = "(" + vouw + " == null ? " + x + " : (" + x + " == null ? " + vouw
                    + " : (" + vouw + " " + cmp + " " + x + " ? " + vouw + " : " + x + ")))";
        }
        return Frag.ok(vouw);
    }

    /** §7.1 "het totaal van &lt;temporeel attribuut&gt;": a TOTAAL over a single OWN temporal attribute read sums
     *  each tijdvak's value weighted by the number of granularity periods it spans. The value is a piecewise
     *  Tijdreeks held as a {@code List<Segment{van,tot,waarde}>} on the bean; rsTijdTotaal folds it (the period
     *  count per segment from the attribute's declared {@link RegelSpraakModel.Granulariteit}). Returns null for
     *  any other operand — a navigated or non-temporal attribute, a multi-arg fold — so the instance-accumulate
     *  and marker paths are unchanged (a non-temporal TOTAAL still flows to {@link #accumulate}). */
    private Frag tijdreeksAggregatie(Acc acc, Expr.Functie f, Objecttype subject) {
        if (f.kind() != Expr.FunctieKind.TOTAAL || f.argumenten().size() != 1) return null;
        // §7.1 / §8.4 a "gedurende de tijd dat <voorwaarde>" restriction the resolver could not structure rides
        // as an Onopgelost filter with its WARNING ("blijft buiten beschouwing"); honour it like the sibling
        // accumulate / concatenatieAggregatie paths do — fall through to the marker rather than fold the FULL
        // unrestricted timeline (which would compute the wrong, condition-free totaal).
        if (f.filter() instanceof Conditie.Onopgelost) return null;
        if (!(f.argumenten().get(0) instanceof Expr.AttribuutRef a)) return null;
        if (a.objecttype() == null) return null;
        // A "het totaal van <attr> van een SET" (alle / ten minste … / etc.) is a SET aggregation. When the element
        // type equals the rule subject's, the resolver collapses it to an EIGEN-looking AttribuutRef (no
        // navigatieRol) — only the structural set flag (from the onderwerp's quantifier) still records it. Folding
        // it as the subject's own single timeline would silently drop the set, so bail to the marker (the
        // accumulate path then markers it too; a distinct element type is caught there). No per-element fold yet.
        if (a.set()) return null;
        RegelSpraakModel.Granulariteit g = a.objecttype().attribuutGranulariteit.get(a.attribuut());
        if (g == null) return null;   // not a temporal attribute -> fall through to the instance-accumulate / marker
        // §13.3.6.7 a MULTI-period attribute ("voor elke 5 maanden") weights each tijdvak by the period count,
        // whose totaal semantics §7.1 does not work out — marker it rather than mis-count it as single-period.
        if (a.objecttype().attribuutTijdlijnAantal.containsKey(a.attribuut()))
            return Frag.todo("totaal van een meer-periodieke tijdreeks (§13.3.6.7, voor elke <n> " + g.name().toLowerCase()
                    + "en)", a.bron());
        // The timeline getter: the subject's own attribute, or a to-ONE navigated partner's, root-guarded so a
        // null reference is leeg (rsTijdTotaal returns null) rather than an NPE-abort. A to-many / unresolvable
        // navigation cannot be one getter — marker it (never a wrong sum() over the List<Segment>).
        NaviRef n = naviResolve(a, subject);
        String waardeTijdreeks;
        if (n.soort() == NaviSoort.EIGEN) {
            waardeTijdreeks = SUBJECT + "." + getter(a.attribuut()) + "()";
        } else if (n.soort() == NaviSoort.GENAVIGEERD) {
            String root = SUBJECT + "." + getter(n.rol()) + "()";
            waardeTijdreeks = "(" + root + " == null ? null : " + root + "." + getter(a.attribuut()) + "())";
        } else {
            return null;
        }
        // §7.1 "gedurende de tijd dat <temporeel kenmerk>": the resolver structured the restriction into a
        // temporal Conditie.Kenmerk of the subject; clip the value timeline to that kenmerk's TRUE periods
        // before totalling (rsTijdKnip). A filter the resolver could not structure stays Onopgelost -> the
        // marker above; any OTHER non-null filter we do not lower is markered rather than silently dropped.
        String guard = null;   // §8.4 a static (non-temporal) compound member gates the WHOLE totaal
        if (f.filter() instanceof Conditie.Kenmerk k && k.navigatie() == null
                && subject != null && subject.kenmerkGranulariteit.containsKey(k.kenmerk())) {
            waardeTijdreeks = kenmerkTijdKnip(waardeTijdreeks, k);
        } else if (f.filter() instanceof Conditie.Kenmerk kn && kn.navigatie() != null && subject != null) {
            // §7.1 a NAVIGATED temporal kenmerk ("gedurende de tijd dat zijn reis een recht op korting heeft"): clip
            // the value timeline to the to-one role's kenmerk timeline, read root-guarded (a null reference -> leeg).
            String cond = navigatieKenmerkTijdreeks(kn, subject);
            if (cond == null) return null;   // negated / to-many / non-temporal navigated kenmerk -> marker
            fnImport("rsTijdKnip");
            waardeTijdreeks = "rsTijdKnip(" + waardeTijdreeks + ", " + cond + ", Segment.class)";
        } else if (f.filter() instanceof Conditie.Samengesteld s && subject != null) {
            // §8.4.2 a COMPOUND "gedurende de tijd dat er aan alle/ten minste 1 van de volgende voorwaarden wordt
            // voldaan": partition the members. TEMPORAL kenmerks clip the timeline (ALLE = intersect, ten minste 1 =
            // union); a §8.4 NON-temporal comparison member becomes a value-side STATIC guard ("de samengestelde
            // conditie is als geheel wel tijdsafhankelijk"). For ALLE the static guard AND-gates the clipped totaal
            // (false -> 0); for "ten minste 1" (OR) a TRUE static member WIDENS the result to the FULL value span
            // (the OR holds everywhere a static member holds), FALSE leaves only the temporal-union clip. A member we
            // cannot lower -> marker, never a wrong period.
            java.util.List<Conditie> temporeel = new java.util.ArrayList<>();
            java.util.List<Conditie.Vergelijking> statisch = new java.util.ArrayList<>();
            for (Conditie c : s.onderdelen()) {
                if (c instanceof Conditie.Kenmerk) temporeel.add(c);
                else if (c instanceof Conditie.Vergelijking v) statisch.add(v);
                else return null;
            }
            if (temporeel.isEmpty()) return null;   // no clip -> not a temporal restriction
            String volleReeks = waardeTijdreeks;    // the un-clipped timeline (the OR static-true span)
            Conditie.Samengesteld temporeelDeel = statisch.isEmpty() ? s
                    : new Conditie.Samengesteld(s.kwantor(), s.drempel(), temporeel, s.bron());
            String compound = samengesteldeTijdKnip(temporeelDeel, waardeTijdreeks, subject);
            if (compound == null) return null;
            waardeTijdreeks = compound;
            if (!statisch.isEmpty()) {
                if (s.kwantor() == Conditie.Kwantor.TEN_MINSTE) {
                    // mixed OR: the static guard ORs its members; TRUE -> the full span, FALSE -> the temporal clip.
                    String orGuard = statischeGuard(acc, statisch, subject, " || ");
                    if (orGuard == null) return null;
                    fnImport("rsTijdTotaal");
                    return Frag.ok("(" + orGuard
                            + " ? rsTijdTotaal(" + volleReeks + ", \"" + g.name() + "\")"
                            + " : rsTijdTotaal(" + waardeTijdreeks + ", \"" + g.name() + "\"))");
                }
                guard = statischeGuard(acc, statisch, subject);   // ALLE: AND-gate the clipped totaal
                if (guard == null) return null;   // a static member we cannot lower -> marker, never an ungated totaal
            }
        } else if (f.filter() instanceof Conditie.Vergelijking vc && subject != null) {
            // §7.1 EUR71 "gedurende de tijd dat <temporeel attr> <op> <scalar>": clip the value timeline to the
            // periods where the comparison holds (its OWN boolean timeline, rsTijdConditie) before totalling. The
            // comparison's temporal attribute may differ from the totalled one ("totaal van de bonus gedurende de
            // tijd dat het inkomen > 1000"); rsTijdKnip intersects the two timelines' intervals.
            String cond = vergelijkingTijdreeks(acc, vc, subject);
            if (cond == null) return null;   // a non-temporal / cross-unit / datum / navigated comparison -> marker
            fnImport("rsTijdKnip");
            waardeTijdreeks = "rsTijdKnip(" + waardeTijdreeks + ", " + cond + ", Segment.class)";
        } else if (f.filter() != null) {
            return null;   // a restriction we cannot lower -> marker, never a silently-unrestricted total
        }
        fnImport("rsTijdTotaal");
        String totaal = "rsTijdTotaal(" + waardeTijdreeks + ", \"" + g.name() + "\")";
        return Frag.ok(guard == null ? totaal : "(" + guard + " ? " + totaal + " : java.math.BigDecimal.ZERO)");
    }

    /** §7.1 EUR71 the condition boolean timeline for a "gedurende de tijd dat &lt;temporeel attr&gt; &lt;op&gt;
     *  &lt;scalar&gt;" restriction: {@code rsTijdConditie(timeline, toets, grens)} lists the periods where the
     *  comparison holds. Mirrors {@link #geheelPeriodeCall}'s operand extraction (temporal side put on the left via
     *  {@link #omkeren}; a cross-unit / datum / navigated / non-temporal comparison returns null -&gt; the caller
     *  markers, never a wrong clip). */
    private String vergelijkingTijdreeks(Acc acc, Conditie.Vergelijking v, Objecttype subject) {
        Expr.AttribuutRef tijd; Expr ander; boolean linksTemp;
        if (v.links() instanceof Expr.AttribuutRef la && isTemporeelAttribuut(la))      { tijd = la; ander = v.rechts(); linksTemp = true; }
        else if (v.rechts() instanceof Expr.AttribuutRef ra && isTemporeelAttribuut(ra)) { tijd = ra; ander = v.links();  linksTemp = false; }
        else return null;
        if (!(tijd.type().isNumeriek() || tijd.type().isPercentage())) return null;   // datum/tekst temporal cmp -> marker
        io.github.yhavinga.regelspraak.model.Eenheid el = tijd.type().eenheidOf(), er = ander.type().eenheidOf();
        if (ander instanceof Expr.AttribuutRef ar && isTemporeelAttribuut(ar)) return null;   // both-temporal (timeline vs timeline) -> marker
        if (el != null && er != null && el.vereistOmrekening(er)) return null;        // cross-unit -> marker (raw-magnitude would be wrong)
        Integer code = Tijdrekening.toets(linksTemp ? v.op() : Operatoren.omkeren(v.op()));               // temporal operand on the left (REVERSE, not negate)
        if (code == null) return null;                                                // a datum ordering op -> marker
        String getter = temporeelTijdlijnGetter(tijd, subject);
        if (getter == null) return null;                                              // navigated -> marker
        // the scalar grens lands in the VALUE/then block (rsTijdTotaal(...)), so lower it value-side (waarde ->
        // $subject.getX() / a root-guarded navigated getter / a literal / a global), NOT operand()'s when-side bare
        // field name (which does not resolve in a then block — KieBase build abort).
        Frag g = waarde(acc, ander, subject);
        if (g.marker()) return null;
        fnImport("rsTijdConditie");
        return "rsTijdConditie(" + getter + ", " + code + ", " + g.text() + ", Segment.class)";
    }

    /** §8.4 the value-side static guard for a mixed gedurende: the AND ({@code " && "}) of each non-temporal
     *  comparison member for an ALLE compound, or the OR ({@code " || "}) for a "ten minste 1" compound. Each member
     *  lowers to a {@code $subject.getX()}-form mvel boolean. Null when any member is not lowerable (the caller markers
     *  the whole totaal). */
    private String statischeGuard(Acc acc, java.util.List<Conditie.Vergelijking> leden, Objecttype subject) {
        return statischeGuard(acc, leden, subject, " && ");
    }
    private String statischeGuard(Acc acc, java.util.List<Conditie.Vergelijking> leden, Objecttype subject, String scheider) {
        java.util.List<String> termen = new java.util.ArrayList<>();
        for (Conditie.Vergelijking v : leden) {
            String t = statischeVergelijkingGuard(acc, v, subject);
            if (t == null) return null;
            termen.add(t);
        }
        return String.join(scheider, termen);
    }

    /** §8.4 one non-temporal comparison member as a value-side ($subject.getX()-form) boolean. The guard rides in the
     *  totaal's CONSEQUENCE, so it uses the value-side {@code waarde()} form, not the when-side bare property, with the
     *  leegwaarde semantics of the §5.1 comparison matrix: an ORDERING/datum comparison is onwaar if either operand is
     *  empty (Tabel 40-43); a GELIJK is waar for both-empty NUMERIEK (Tabel 37) but onwaar for both-empty non-numeriek
     *  (Tabel 38); an ONGELIJK is waar for gevuld-vs-empty and onwaar for both-empty (Tabel 44). A markered/unresolvable
     *  operand -> null. */
    private String statischeVergelijkingGuard(Acc acc, Conditie.Vergelijking v, Objecttype subject) {
        Frag lvF = waarde(acc, v.links(), subject), rvF = waarde(acc, v.rechts(), subject);
        if (lvF.marker() || rvF.marker()) return null;
        String lv = lvF.text(), rv = rvF.text();
        String[] ov = vergelijkingsOperanden(lv, v.links().type(), rv, v.rechts().type());
        String volBeide = "(" + lv + " != null && " + rv + " != null && " + ov[0] + " " + Operatoren.symbool(v.op()) + " " + ov[1] + ")";
        if (ordeningOfDatum(v.op())) {
            // §5.7 Tabel 46: a DATUM ordering (eerder/later) with BOTH operands empty-capable (an attr-vs-attr compare,
            // no datum literal) is "fout" when both are empty — NOT the "onwaar" that volBeide renders. The exec
            // statischeVergelijking fires a datum ordering static member ONLY as <own-attr> <op> <datum literal> and
            // SKIPS every other datum form, so DRL markers them too (return null) to stay symmetric — never a
            // DRL-fires/exec-skips divergence the differential would mask. A numeric ordering both-empty is Tabel 42/33
            // "onwaar" = the volBeide false (correct), so it keeps firing; a literal-RHS datum ordering (never both-empty)
            // keeps firing in both back-ends.
            if (v.links().type().isDatum()) {
                boolean execFires = v.links() instanceof Expr.AttribuutRef la && la.navigatieRol() == null
                        && v.rechts() instanceof Expr.Literal dl && dl.type().isDatum();
                if (!execFires) return null;
            }
            return volBeide;
        }
        if (v.op() == Conditie.Operator.GELIJK)   // Tabel 37 numeriek both-empty -> waar; Tabel 38 non-numeriek -> onwaar
            return nietNumeriekVergelijking(v) ? volBeide
                    : "((" + lv + " == null && " + rv + " == null) || " + volBeide + ")";
        if (v.op() == Conditie.Operator.ONGELIJK)   // Tabel 44 gevuld-vs-empty -> waar; both-empty -> onwaar
            return "(((" + lv + " == null) != (" + rv + " == null)) || " + volBeide + ")";
        return null;
    }

    /** §5.1.4 lower a RHS that PRODUCES a timeline written to a single-period temporal doel attribute, into a
     *  {@code List<Segment>} expression — or null when it is not a supported timeline producer (the caller then
     *  markers). The first writer: §13.4.17.57 "het aantal dagen in <de maand|het jaar> dat <temporeel kenmerk>"
     *  -> rsAantalDagenIn over the kenmerk's true-period timeline, bucketed by the DOEL's granularity (the spec
     *  requires the expression's "in <maand|jaar>" to match the doel's tijdlijn, §7.2). Only a positive
     *  single-period subject temporal-kenmerk condition lowers; a negated/comparison/compound/navigated condition
     *  left the filter unset in the resolver -> null here. */
    private String temporeleWaarde(Acc acc, Expr w, Objecttype subject, RegelSpraakModel.Granulariteit gran, Datatype doelType, String anker) {
        // §5.8.2/§5.1.4 "de som van <temporeel attr> van alle <naar-veel-rol>" into a TEMPORAL doel: a PER-PERIOD
        // sommatie over the navigated SET, keeping the timeline (§5.1.4: only the §7.1 "totaal van" aggregation
        // collapses to a scalar; "som van" over temporal elements stays temporal). An accumulate COLLECTS each
        // element's List<Segment> timeline; rsTijdSomAlle folds them per merged-knip period with the §5.8.2 leeg rule
        // (skip empties, all-empty period -> leeg unless "of 0 als die er niet zijn"). Anchored (variable-startpunt)
        // doelen stay a marker (the per-object bucket interaction with a set fold is out of scope).
        if (anker == null) {
            String somSet = tijdreeksSomAlleSet(acc, w, subject, gran, doelType);
            if (somSet != null) return somSet;
        }
        if (w instanceof Expr.Functie f && f.kind() == Expr.FunctieKind.AANTAL_DAGEN_IN
                // §4.21/§7.2 the operator's "in <maand|jaar>" bucket unit MUST match the doel's tijdlijn — a
                // mismatch ("in het jaar" into a per-maand doel) markers rather than silently bucketing by the doel.
                && f.granulariteit() == gran && subject != null) {
            String cond = conditieTijdreeks(f.filter(), subject);   // single kenmerk OR a §8.4.2 (mixed) compound; null -> marker
            if (cond != null) {
                // §3.8: an anchored doel buckets at the per-object startpunt (rsAantalDagenInVanaf); a leeg
                // anchor yields no periods (the §6.8 fout rides the startpunt rule's own companion).
                String fn = anker == null ? "rsAantalDagenIn" : "rsAantalDagenInVanaf";
                String args = anker == null ? "" : ", " + anker;
                String call = mixedGuardWrap(acc, fn + "(" + cond + ", \"" + gran.name() + "\"" + args + ", Segment.class)", f.filter(), subject);
                if (call != null) { fnImport(fn); return call; }
            }
        }
        // §13.4.17.58/§7.3.2 "het tijdsevenredig deel per <maand|jaar> van <basis> gedurende de tijd dat <conditie>":
        // per calendar bucket, value = baseValueInBucket × (true-days / total-days). The base must be a single-period
        // TIMELINE — its value-timeline is intrinsic (§5.1.2): either the subject's own temporal attribute, OR a
        // TEMPORAL PARAMETER (the spec's literal STANDAARD BELASTINGVERMINDERING base, a List<Segment> global). A
        // plain scalar base markers (the resolver left its filter unset). §4.22: NO auto-round (DECIMAL64, not the
        // doel's decimals — §6.1.2 forbids it); a leeg base bucket yields no segment. The base must be CONSTANT within
        // each output bucket (§7.3.2 lines 3093/3123 read it "voor de hele maand/jaar"), so its granularity must be
        // COARSER-OR-EQUAL to the bucket (DAG<WEEK<MAAND<KWARTAAL<JAAR via ordinal); a unit mismatch (the §7.3.2 ex.3
        // €/jr→€/mnd rate conversion) or a negated/navigated condition markers.
        if (w instanceof Expr.Functie f && f.kind() == Expr.FunctieKind.TIJDSEVENREDIG_DEEL
                && f.granulariteit() == gran && f.argumenten().size() == 1 && subject != null) {
            Expr base = f.argumenten().get(0);
            RegelSpraakModel.Granulariteit baseGran = null; Datatype baseType = null; String baseExpr = null;
            if (base instanceof Expr.AttribuutRef ba && ba.navigatieRol() == null && !ba.set() && ba.objecttype() == subject
                    && subject.attribuutGranulariteit.containsKey(ba.attribuut())
                    && !subject.attribuutTijdlijnAantal.containsKey(ba.attribuut())) {
                baseGran = subject.attribuutGranulariteit.get(ba.attribuut());
                baseType = ba.type();
                baseExpr = SUBJECT + "." + getter(ba.attribuut()) + "()";
            } else if (base instanceof Expr.AttribuutRef ba && ba.navigatieRol() != null && !ba.set()
                    && ba.objecttype() != null && ba.objecttype().attribuutGranulariteit.containsKey(ba.attribuut())
                    && !ba.objecttype().attribuutTijdlijnAantal.containsKey(ba.attribuut())
                    && naviResolve(ba, subject).soort() == NaviSoort.GENAVIGEERD) {
                // §7.3.2 a TO-ONE NAVIGATED base ("de X van zijn reis"): read the partner's temporal attribute,
                // root-guarded (a null reference -> leeg). The gedurende condition stays over the SUBJECT. Mirrors exec navReader.
                baseGran = ba.objecttype().attribuutGranulariteit.get(ba.attribuut());
                baseType = ba.type();
                String root = SUBJECT + "." + getter(naviResolve(ba, subject).rol()) + "()";
                baseExpr = "(" + root + " == null ? null : " + root + "." + getter(ba.attribuut()) + "())";
            } else if (base instanceof Expr.ParameterRef bp && bp.parameter().granulariteit() != null) {
                baseGran = bp.parameter().granulariteit();
                baseType = bp.parameter().datatype();
                baseExpr = ident(bp.parameter().naam(), false);   // the List<Segment> global the runtime supplies
            }
            if (baseExpr != null && baseGran.ordinal() >= gran.ordinal() && baseType.isNumeriek()) {
                // §7.3.2 ex.3 rate conversion: the base's per-tijdseenheid unit may DIFFER from the doel's (€/jr base
                // into a €/mnd doel). §7.3.1 restates the base FIRST (÷12) — rsTijdSchaal scales the base timeline by
                // the unit factor BEFORE proration. Same unit -> factor null (no scale); convertible-different -> a
                // factor; not convertible -> marker (omrekenFactor null AND the units differ).
                boolean zelfdeEenheid = java.util.Objects.equals(baseType.eenheidOf(), doelType == null ? null : doelType.eenheidOf());
                // §4.22 the tijdsevenredig operand must be "numeriek met een eenheid per tijdseenheid" (a RATE). A unit
                // MISMATCH is a §7.3.2 rate conversion ONLY when the base unit is a rate (has a noemer); a non-rate
                // mismatch (cm -> m) is NOT a rate restatement and stays a marker (never a silent unit-rebase).
                boolean baseIsRate = baseType.eenheidOf() != null && baseType.eenheidOf().noemer() != null;
                java.math.BigDecimal fwd = (zelfdeEenheid || !baseIsRate) ? null : omrekenFactor(baseType, doelType);
                // §7.3.1 specifies the restatement as a DIVISION ("gedeeld door de omrekenfactor 12"): one of the two
                // reciprocal factors is the EXACT integer ratio (12) and the other is the lossy 1/12 — pick the exact
                // direction (fewer significant digits) and DIVIDE by it when it is the inverse, so a non-clean base
                // stays exact (a pre-rounded ×0.0833 diverges from ÷12 for ~13% of bases).
                java.math.BigDecimal inv = fwd == null ? null : omrekenFactor(doelType, baseType);
                boolean deel = inv != null && inv.precision() < fwd.precision();
                java.math.BigDecimal factor = fwd == null ? null : (deel ? inv : fwd);
                String basis = factor == null ? baseExpr
                        : "rsTijdSchaal(" + baseExpr + ", new java.math.BigDecimal(\"" + factor.toPlainString() + "\"), " + deel + ")";
                String cond = (zelfdeEenheid || factor != null) ? conditieTijdreeks(f.filter(), subject) : null;
                String call = null;
                if (cond != null) {
                    // §3.8 anchored buckets for a variable-startpunt doel (see the aantal-dagen-in branch).
                    String fn = anker == null ? "rsTijdsevenredig" : "rsTijdsevenredigVanaf";
                    String extra = anker == null ? "" : ", " + anker;
                    call = mixedGuardWrap(acc, fn + "(" + cond + ", " + basis + ", \"" + gran.name() + "\"" + extra + ", Segment.class)", f.filter(), subject);
                    if (call != null && anker != null) { fnImport(fn); if (factor != null) fnImport("rsTijdSchaal"); return call; }
                } else if (anker == null && (zelfdeEenheid || factor != null)) {
                    // §8.4.2 OR-mixed (ten minste 1: a temporal kenmerk OR a static comparison): conditieTijdreeks
                    // markers a mixed OR, but a tijdsevenredig HAS a base whose value-timeline bounds the static-true
                    // span (unlike aantal-dagen-in, which has no base/extent), so it lowers like the totaal-READ
                    // OR-mixed (~line 1757): a TRUE static member -> prorate the base over its FULL extent (the base AS
                    // its own all-true condition — rsTijdsevenredig reads a condition's SEGMENTS as the true periods,
                    // so basis-as-condition is factor 1 per fully-covered bucket); FALSE -> the temporal-union clip.
                    call = orMixedTijdsevenredig(acc, f.filter(), subject, basis, gran);
                }
                if (call != null) { fnImport("rsTijdsevenredig"); if (factor != null) fnImport("rsTijdSchaal"); return call; }
            }
        }
        // §5.1.4 a tijdsafhankelijke arithmetic expression PLUS/MIN over two temporal operands: apply the operator per
        // period on the merged-knip timeline (rsTijdReken; leeg -> 0). §5.1.4.2/§4.1: SUM in the LEFT operand's unit
        // (restate the right operand into it), THEN restate the SUM ONCE to the doel unit ("daarna wordt dit omgerekend
        // naar €/jr"). Scaling each operand to the doel FIRST would DOUBLE-ROUND a non-terminating factor (÷12) — the
        // spec sums first, converts once (review wf_115473d5-eee F1). Mixed granularity rides the merged-knip union.
        if (w instanceof Expr.Binair bin && (bin.op() == Expr.BinOp.PLUS || bin.op() == Expr.BinOp.MIN) && subject != null) {
            Datatype sumType = bin.links().type();
            String l = temporeelRekenOperand(bin.links(), subject, gran, sumType);
            String rr = temporeelRekenOperand(bin.rechts(), subject, gran, sumType);
            if (l != null && rr != null) {
                fnImport("rsTijdReken");
                String schaal = schaalTijd("rsTijdReken(" + l + ", " + rr + ", \"" + bin.op().name() + "\", Segment.class)", sumType, doelType);
                if (schaal != null) return schaal;   // the sum unit is convertible to the doel (§4.1) -> restate once
            }
        }
        return null;
    }

    /** §5.8.2/§5.1.4 "de som van &lt;temporeel attr&gt; van alle &lt;naar-veel-rol&gt;" into a TEMPORAL doel — a per-period
     *  sommatie over the navigated SET. Emits a {@code collectList} accumulate (each element's List&lt;Segment&gt; timeline)
     *  after the subject pattern, and returns an {@code rsTijdSomAlle(...)} fold over the bound list. Only a SOM (not the
     *  §7.1 "totaal van", which collapses to a scalar) over a SET of a single-period temporal attribute lowers; a
     *  to-one/own operand, a multi-period element, an unresolvable join, or an unstructured die-filter -> null (marker). */
    private String tijdreeksSomAlleSet(Acc acc, Expr w, Objecttype subject, RegelSpraakModel.Granulariteit gran, Datatype doelType) {
        if (!(w instanceof Expr.Functie f) || f.kind() != Expr.FunctieKind.SOM || f.argumenten().size() != 1) return null;
        if (!(f.argumenten().get(0) instanceof Expr.AttribuutRef a) || !a.set() || a.objecttype() == null) return null;
        RegelSpraakModel.Granulariteit eg = a.objecttype().attribuutGranulariteit.get(a.attribuut());
        if (eg == null) return null;                                   // non-temporal element -> the scalar accumulate path
        if (a.objecttype().attribuutTijdlijnAantal.containsKey(a.attribuut())) return null;   // multi-period element -> out of scope
        if (eg != gran) return null;                                   // §5.1.4 the element tijdlijn must match the doel's (no set-fold gran promotion yet)
        if (!a.type().isNumeriek()) return null;                       // §5.8.2 a sommatie is over numeriek operands
        if (!schaalbaarNaarDoel(a.type(), doelType)) return null;      // §4.1 element unit must restate to the doel unit (else marker)
        Objecttype elem = f.elementObjecttype();
        if (elem == null || subject == null) return null;
        String rol = model.verbindendeRol(elem, subject);
        if (rol == null) return null;                                  // unresolvable join -> marker
        int n = ++acc.n;
        String ev = "$e" + n, var = "$tl" + n;
        String filterScope = ident(rol, false) + " == " + SUBJECT;
        if (f.filter() != null) {
            Frag fc = conditie(acc, f.filter(), elem);                 // §13.4.2 die/dat subselectie on the element
            if (fc.marker()) return null;                              // unstructured filter -> marker
            filterScope = filterScope + ", " + fc.text();
        }
        // §4.1 restate each element timeline into the doel unit BEFORE folding (the §5.8.2 sum is unit-homogeneous;
        // an equal unit scales by factor 1 -> the getter verbatim). collectList binds the List<List<Segment>>.
        String elemTijdlijn = schaalTijd(ev + "." + getter(a.attribuut()) + "()", a.type(), doelType);
        acc.clausules.add("accumulate( " + ev + " : " + ident(elem.naam, true) + "( " + filterScope + " ) ; "
                + var + " : collectList( " + elemTijdlijn + " ) )");
        boolean fallbackNul = f.leegwaarde() != null;                  // §5.8.2 "of 0 als die er niet zijn"
        fnImport("rsTijdSomAlle");
        return "rsTijdSomAlle(" + var + ", " + fallbackNul + ", Segment.class)";
    }

    /** Whether a numeric temporal timeline in {@code van}'s unit can restate into {@code naar}'s (equal, or unit-convertible). */
    private boolean schaalbaarNaarDoel(Datatype van, Datatype naar) {
        io.github.yhavinga.regelspraak.model.Eenheid eu = van == null ? null : van.eenheidOf(), du = naar == null ? null : naar.eenheidOf();
        if (java.util.Objects.equals(eu, du)) return true;
        return eu != null && du != null && eu.convertibleNaar(du);
    }

    /** §5.1.4 a temporal operand of a tijdsafhankelijke arithmetic expression — an own or to-one navigated SINGLE-period
     *  numeric temporal attribute whose granularity is COARSER-OR-EQUAL to the doel's (gran) and whose unit is CONVERTIBLE
     *  to {@code unitTarget} (the sum unit). Returns the {@code List<Segment>} getter (restated to unitTarget when the
     *  unit differs), or null (not such an operand / incompatible unit / finer granularity -> marker). */
    private String temporeelRekenOperand(Expr e, Objecttype subject, RegelSpraakModel.Granulariteit gran, Datatype unitTarget) {
        // §5.1.4 an N-operand chain ("a plus b plus c" = Binair(Binair(a,b), c)): the operand is itself a temporal sub-sum.
        // Recurse — sum in the LEFT sub-operand's unit (restate the right into it), then restate the sub-sum ONCE to
        // unitTarget (the enclosing sum unit). Arbitrary depth + mixed +/- fall out of the recursion, each Binair its own op.
        if (e instanceof Expr.Binair bin && (bin.op() == Expr.BinOp.PLUS || bin.op() == Expr.BinOp.MIN)) {
            Datatype subSum = bin.links().type();
            String l = temporeelRekenOperand(bin.links(), subject, gran, subSum);
            String r = temporeelRekenOperand(bin.rechts(), subject, gran, subSum);
            if (l == null || r == null) return null;
            fnImport("rsTijdReken");
            return schaalTijd("rsTijdReken(" + l + ", " + r + ", \"" + bin.op().name() + "\", Segment.class)", subSum, unitTarget);
        }
        if (!(e instanceof Expr.AttribuutRef a) || a.set() || a.type() == null || !a.type().isNumeriek()) return null;
        Objecttype ot; String getter;
        if (a.navigatieRol() == null && a.objecttype() == subject) {
            ot = subject; getter = SUBJECT + "." + getter(a.attribuut()) + "()";
        } else if (a.navigatieRol() != null && a.objecttype() != null && naviResolve(a, subject).soort() == NaviSoort.GENAVIGEERD) {
            ot = a.objecttype();
            String root = SUBJECT + "." + getter(naviResolve(a, subject).rol()) + "()";
            getter = "(" + root + " == null ? null : " + root + "." + getter(a.attribuut()) + "())";
        } else return null;
        // §5.1.4.2 mixed granularity: the operand timeline must be COARSER-OR-EQUAL to the doel's (DAG<WEEK<MAAND<
        // KWARTAAL<JAAR via ordinal), so ALL its knips fit the (finer-or-equal) doel timeline; the merged-knip union
        // then evaluates per actual period. An operand FINER than the doel cannot be represented -> marker.
        RegelSpraakModel.Granulariteit og = ot.attribuutGranulariteit.get(a.attribuut());
        if (og == null || og.ordinal() < gran.ordinal()) return null;
        if (ot.attribuutTijdlijnAantal.containsKey(a.attribuut())) return null;   // multi-period -> out of scope
        return schaalTijd(getter, a.type(), unitTarget);   // §4.1 restate into the sum unit (null -> incompatible -> marker)
    }

    /** §4.1/§5.1.4.2 restate a temporal timeline expr from {@code van}'s unit into {@code naar}'s (rsTijdSchaal by the
     *  EXACT-integer direction, ÷ when the inverse has fewer sig digits — like §7.3.1). Returns the expr UNCHANGED when
     *  the units are equal or no conversion is required; null when the units are INCOMPATIBLE (caller markers). */
    private String schaalTijd(String expr, Datatype van, Datatype naar) {
        io.github.yhavinga.regelspraak.model.Eenheid eu = van == null ? null : van.eenheidOf(), du = naar == null ? null : naar.eenheidOf();
        if (java.util.Objects.equals(eu, du)) return expr;                       // equal (incl. both null) -> no scale
        if (eu == null || du == null || !eu.convertibleNaar(du)) return null;    // incompatible unit -> marker
        if (!eu.vereistOmrekening(du)) return expr;                              // convertible with factor 1 -> no scale
        java.math.BigDecimal fwd = omrekenFactor(van, naar), inv = omrekenFactor(naar, van);
        boolean deel = inv != null && inv.precision() < fwd.precision();
        java.math.BigDecimal factor = deel ? inv : fwd;
        fnImport("rsTijdSchaal");
        return "rsTijdSchaal(" + expr + ", new java.math.BigDecimal(\"" + factor.toPlainString() + "\"), " + deel + ")";
    }

    /** The TRUE-period {@code List<Segment>} timeline of a temporal WRITE-producer's condition — the periods whose
     *  days/value get bucketed by rsAantalDagenIn / rsTijdsevenredig. A single positive single-period subject kenmerk
     *  is its getter; a §8.4.2 COMPOUND of such kenmerks is their combined boolean timeline (ALLE = intersection via
     *  rsTijdKnip, "ten minste 1" = union via rsTijdUnie — both fold over the members' getters). Null when unsupported
     *  (a negated / navigated / multi-period kenmerk, or a mixed / non-kenmerk / other-kwantor compound): a negated
     *  member has no standalone complement here (unlike the totaal CLIP, where the value timeline bounds it), so the
     *  caller markers rather than mis-count. */
    private String conditieTijdreeks(Conditie filter, Objecttype subject) {
        if (filter instanceof Conditie.Kenmerk k) return temporeelKenmerkTijdreeks(k, subject);
        // §8.4.2 ALLE = intersection; "ten minste 1" = union. Only drempel 1 is a union — a "ten minste N>1" is a
        // threshold, not an OR (the resolver already markers it, gedurendeSamengesteld gates telwoord==1; this guard
        // keeps the union semantics honest if a drempel>1 Samengesteld ever reaches here).
        if (filter instanceof Conditie.Samengesteld s
                && (s.kwantor() == Conditie.Kwantor.ALLE
                    || (s.kwantor() == Conditie.Kwantor.TEN_MINSTE && s.drempel() == 1))) {
            boolean alle = s.kwantor() == Conditie.Kwantor.ALLE;
            java.util.List<String> regios = new java.util.ArrayList<>();
            for (Conditie c : s.onderdelen()) {
                // §8.4 a static (non-temporal) comparison member is the value-side guard (mixedGuardWrap), not part of
                // the timeline — admitted only in an ALLE compound here. A mixed OR widens the span (the static-true
                // case), which the WRITE producers do not yet express, so an OR-mixed markers here (only the totaal,
                // tijdreeksAggregatie, handles it).
                if (c instanceof Conditie.Vergelijking) { if (!alle) return null; continue; }
                if (!(c instanceof Conditie.Kenmerk k)) return null;
                String g = temporeelKenmerkTijdreeks(k, subject);
                if (g == null) return null;   // a negated/navigated/multi-period member -> marker (no standalone complement)
                regios.add(g);
            }
            if (regios.isEmpty()) return null;             // no temporal clip (a pure-static compound)
            if (regios.size() == 1) return regios.get(0);  // one kenmerk (+ optional static members -> the guard)
            if (alle) fnImport("rsTijdKnip"); else fnImport("rsTijdUnie");
            String combiner = alle ? "rsTijdKnip" : "rsTijdUnie";
            String acc = regios.get(0);
            for (int i = 1; i < regios.size(); i++) acc = combiner + "(" + acc + ", " + regios.get(i) + ", Segment.class)";
            return acc;
        }
        return null;
    }

    /** §8.4.2 lower an OR-mixed ("ten minste 1": a temporal kenmerk OR a static comparison) gating the §13.4.17.58
     *  tijdsevenredig WRITE producer. The disjunction is span-dependent: a TRUE static member satisfies the OR for the
     *  WHOLE span, a FALSE one leaves only the temporal-union periods. tijdsevenredig HAS a base value-timeline that
     *  bounds the "full span", so (mirroring the totaal-READ OR-mixed at ~line 1757): static TRUE -> prorate the base
     *  over its full extent ({@code rsTijdsevenredig(basis, basis)} — basis-as-condition feeds rsTijdsevenredig the
     *  base's segments as the true periods, i.e. factor 1 per fully-covered bucket); static FALSE -> prorate over the
     *  temporal union only. Null when the compound is not a temporal+static OR-mix or a member is not lowerable (a
     *  negated/navigated temporal member, an unliftable static comparison) -> the caller markers. ONLY tijdsevenredig
     *  lowers an OR-mix: §7.2 aantal-dagen-in has NO value base — its result is intrinsically bounded by the condition's
     *  true-periods (the spec's own caveat at §7.2 line 3005: an ever-true / "zeer lange periode" condition yields a
     *  "zeer groot aantal resultaten"), so a static-TRUE OR (true for ALL time) is UNBOUNDED with no finite
     *  List<Segment>; bounding it by the kenmerk segments would drop the full-day months and under-count, so it stays
     *  an honest marker (verified: aantal-dagen-in OR-mixed markers in both back-ends). */
    private String orMixedTijdsevenredig(Acc acc, Conditie filter, Objecttype subject, String basis, RegelSpraakModel.Granulariteit gran) {
        if (!(filter instanceof Conditie.Samengesteld s) || s.kwantor() != Conditie.Kwantor.TEN_MINSTE || s.drempel() != 1)
            return null;
        java.util.List<Conditie> temporeel = new java.util.ArrayList<>();
        java.util.List<Conditie.Vergelijking> statisch = new java.util.ArrayList<>();
        for (Conditie c : s.onderdelen()) {
            if (c instanceof Conditie.Kenmerk) temporeel.add(c);
            else if (c instanceof Conditie.Vergelijking v) statisch.add(v);
            else return null;   // an unsupported member type -> marker
        }
        if (temporeel.isEmpty() || statisch.isEmpty()) return null;   // not a temporal+static mixed compound
        Conditie.Samengesteld temporeelDeel = new Conditie.Samengesteld(s.kwantor(), s.drempel(), temporeel, s.bron());
        String unionCond = conditieTijdreeks(temporeelDeel, subject);
        if (unionCond == null) return null;   // a negated / navigated / multi-period temporal member -> marker
        String orGuard = statischeGuard(acc, statisch, subject, " || ");
        if (orGuard == null) return null;     // a static member we cannot lower -> marker
        String g = "\"" + gran.name() + "\"";
        return "(" + orGuard
                + " ? rsTijdsevenredig(" + basis + ", " + basis + ", " + g + ", Segment.class)"
                + " : rsTijdsevenredig(" + unionCond + ", " + basis + ", " + g + ", Segment.class))";
    }

    /** §8.4 wrap a temporal-WRITE producer call in a mixed compound's STATIC guard: a non-temporal comparison member
     *  gates the whole per-bucket write — when it fails the doel gets an empty timeline. The call is unchanged for a
     *  pure-temporal filter; null when a static member is not lowerable (the caller then markers). */
    private String mixedGuardWrap(Acc acc, String call, Conditie filter, Objecttype subject) {
        if (!(filter instanceof Conditie.Samengesteld s)) return call;
        java.util.List<Conditie.Vergelijking> statisch = new java.util.ArrayList<>();
        for (Conditie c : s.onderdelen()) if (c instanceof Conditie.Vergelijking v) statisch.add(v);
        if (statisch.isEmpty()) return call;
        String guard = statischeGuard(acc, statisch, subject);
        return guard == null ? null : "(" + guard + " ? " + call + " : new java.util.ArrayList())";
    }

    /** A single positive single-period subject kenmerk's TRUE-period getter ({@code $subject.getK()}), or null when it
     *  is not lowerable as a standalone true-period timeline: a navigated, negated, multi-period, or non-subject
     *  kenmerk. */
    private String temporeelKenmerkTijdreeks(Conditie.Kenmerk k, Objecttype subject) {
        if (k.navigatie() != null || k.negatie() || subject == null
                || !subject.kenmerkGranulariteit.containsKey(k.kenmerk())
                || subject.kenmerkTijdlijnAantal.containsKey(k.kenmerk())) return null;
        return SUBJECT + "." + getter(k.kenmerk()) + "()";
    }

    /** §7.1 the boolean-timeline getter for a NAVIGATED temporal kenmerk ("zijn reis een recht op korting heeft") —
     *  the to-ONE role's kenmerk {@code List<Segment>}, root-guarded so a null reference reads as a leeg (null)
     *  timeline (rsTijdKnip then clips to empty). null for a negated / to-many / non-temporal navigated kenmerk. */
    private String navigatieKenmerkTijdreeks(Conditie.Kenmerk k, Objecttype subject) {
        if (k.navigatie() == null || k.negatie() || subject == null) return null;
        Objecttype doel = model.rolDoeltypeNaarSubject(subject, k.navigatie());
        if (doel == null || !doel.kenmerkGranulariteit.containsKey(k.kenmerk())
                || doel.kenmerkTijdlijnAantal.containsKey(k.kenmerk())) return null;
        String root = SUBJECT + "." + getter(k.navigatie()) + "()";
        return "(" + root + " == null ? null : " + root + "." + getter(k.kenmerk()) + "())";
    }

    /** The §8.2 whole-period qualifier on a predicate, or null when the predicate carries none. */
    private static Conditie.GeheelPeriode geheelPeriodeVan(Conditie c) {
        if (c instanceof Conditie.Vergelijking v) return v.periode();
        if (c instanceof Conditie.Predicaat p)    return p.periode();
        if (c instanceof Conditie.Kenmerk k)      return k.periode();
        return null;
    }

    /** The §8.2 timeline getter for a temporal attribute read — the subject's own {@code List<Segment>} field, or a
     *  to-ONE navigated partner's, root-guarded so a null reference reads as a leeg (null) timeline rather than NPE.
     *  null for a to-many / unresolvable navigation. Mirrors {@link #tijdreeksAggregatie}'s timeline getter. */
    private String temporeelTijdlijnGetter(Expr.AttribuutRef a, Objecttype subject) {
        NaviRef n = naviResolve(a, subject);
        if (n.soort() == NaviSoort.EIGEN) return SUBJECT + "." + getter(a.attribuut()) + "()";
        if (n.soort() == NaviSoort.GENAVIGEERD) {
            String root = SUBJECT + "." + getter(n.rol()) + "()";
            return "(" + root + " == null ? null : " + root + "." + getter(a.attribuut()) + "())";
        }
        return null;
    }

    /** §8.2 / §13.4.15.70: the RAW boolean {@code rsGeheelPeriode(timeline, "JAAR"|"MAAND", toets, grens)} call for a
     *  whole-period-qualified predicate (the caller appends {@code == true|false}, NIET flips which), or a marker Frag
     *  when the form is outside the supported set. Supported: a numeric comparison of a temporal attribute vs a scalar
     *  grens (§8.2 line 3467), a GEVULD over a temporal attribute, and a temporal kenmerkcheck (§8.2 line 3469). A
     *  LEEG / ELFPROEF / NUMERIEK-met-exact whole-period form is degenerate without a bounding period -> marker. */
    private Frag geheelPeriodeCall(Acc acc, Conditie c, Conditie.GeheelPeriode gp, Objecttype subject) {
        String gran = gp.jaar() ? "JAAR" : "MAAND";
        String getter; int toets; String grens = "null";
        if (c instanceof Conditie.Vergelijking v) {
            Expr.AttribuutRef tijd; Expr ander; boolean linksTemp;
            if (v.links() instanceof Expr.AttribuutRef la && isTemporeelAttribuut(la))      { tijd = la; ander = v.rechts(); linksTemp = true; }
            else if (v.rechts() instanceof Expr.AttribuutRef ra && isTemporeelAttribuut(ra)) { tijd = ra; ander = v.links();  linksTemp = false; }
            else return Frag.todo("§8.2 zonder tijdsafhankelijk attribuut", c.bron());
            if (!(tijd.type().isNumeriek() || tijd.type().isPercentage()))
                return Frag.todo("§8.2 niet-numerieke gehele-periodevergelijking", c.bron());
            // §8.2 cross-unit grens: the grenswaarde is compared against each Segment's value (in the temporal
            // attribute's unit el), so restate it from its own unit er into el via the EXACT factor (the same
            // restatement the scalar comparison path uses — exacteHerschaalFactoren ×÷, one DECIMAL64 rounding). An
            // OPAQUE product unit (no exact factors) stays an honest marker, kept symmetric in both back-ends.
            io.github.yhavinga.regelspraak.model.Eenheid el = tijd.type().eenheidOf(), er = ander.type().eenheidOf();
            boolean crossUnit = el != null && er != null && el.vereistOmrekening(er);
            if (crossUnit && er.exacteHerschaalFactoren(el) == null)
                return Frag.todo("§8.2 gehele-periodevergelijking met eenheidsomrekening", c.bron());
            Integer code = Tijdrekening.toets(linksTemp ? v.op() : Operatoren.omkeren(v.op()));   // put the temporal operand on the left (REVERSE, not negate)
            if (code == null) return Frag.todo("§8.2 datum-gehele-periodevergelijking", c.bron());
            getter = temporeelTijdlijnGetter(tijd, subject);
            if (getter == null) return Frag.todo("§8.2 navigatie", c.bron());
            Frag g = operand(acc, ander, subject);    // the scalar grenswaarde (literal / parameter-global / own attribute)
            if (g.marker()) return Frag.todo("§8.2 grenswaarde", c.bron());
            toets = code; grens = crossUnit ? herschaalExact(g.text(), ander.type(), tijd.type()) : g.text();
        } else if (c instanceof Conditie.Predicaat p && p.soort() == Conditie.Predicaatsoort.GEVULD
                && p.operand() instanceof Expr.AttribuutRef pa && isTemporeelAttribuut(pa)) {
            getter = temporeelTijdlijnGetter(pa, subject);
            if (getter == null) return Frag.todo("§8.2 navigatie", c.bron());
            toets = 6;
        } else if (c instanceof Conditie.Predicaat p && p.soort() == Conditie.Predicaatsoort.LEEG
                && p.operand() instanceof Expr.AttribuutRef pa && isTemporeelAttribuut(pa)) {
            // §8.2 line 3468 "... gedurende de gehele maand leeg is": the inverse of GEVULD (toets 6) — every moment
            // of each touched bucket carries NO value (toets 8 in rsGeheelPeriode).
            getter = temporeelTijdlijnGetter(pa, subject);
            if (getter == null) return Frag.todo("§8.2 navigatie", c.bron());
            toets = 8;
        } else if (c instanceof Conditie.Kenmerk k) {
            getter = temporeelKenmerkTijdreeks(k, subject);   // null for a navigated/negated/non-temporal kenmerk
            if (getter == null) getter = navigatieKenmerkTijdreeks(k, subject);   // §8.2 over a to-one NAVIGATED temporal kenmerk (root-guarded)
            if (getter == null) return Frag.todo("§8.2 kenmerk", c.bron());
            toets = 7;
        } else {
            return Frag.todo("§8.2 predicaat", c.bron());     // LEEG / ELFPROEF / NUMERIEK-met-exact whole-period
        }
        fnImport("rsGeheelPeriode");
        return Frag.ok("rsGeheelPeriode(" + getter + ", \"" + gran + "\", " + toets + ", " + grens + ")");
    }

    /** §8.2 over a NAVIGATED to-many temporal SET: "de &lt;temporeel attr&gt; van alle &lt;naar-veel-rol&gt; ... gedurende
     *  het gehele jaar|maand &lt;op&gt; &lt;scalar grens&gt;" (or a GEVULD/LEEG whole-period predicate over the set). The
     *  check holds iff it holds for EVERY element's timeline — a forall. Lowered as a {@code collectList} accumulate over
     *  the element timelines + an {@code eval( rsGeheelPeriodeAlle($tlN, gran, toets, grens) == true|false )} clause, both
     *  hoisted after the subject pattern (the accumulate binds $tlN, the eval reads it — the same after-$subject ordering
     *  the aggregation comparisons use). Returns null when the onderwerp is NOT such a set (fall through to the scalar
     *  {@link #geheelPeriodeCall}), or a marker LoweredConditie when the set form is unlowerable (cross-unit-opaque /
     *  datum-op / unresolvable join). Mirrors the exec geheelPeriodeAlle. */
    private LoweredConditie geheelPeriodeAlleVoorwaarde(Acc acc, Conditie c, Conditie.GeheelPeriode gp, Objecttype subject) {
        String gran = gp.jaar() ? "JAAR" : "MAAND";
        Expr.AttribuutRef tijd; int toets; String grens = "null"; Expr ander = null; boolean linksTemp = true;
        if (c instanceof Conditie.Vergelijking v) {
            if (v.links() instanceof Expr.AttribuutRef la && la.set() && isTemporeelSetAttribuut(la))      { tijd = la; ander = v.rechts(); linksTemp = true; }
            else if (v.rechts() instanceof Expr.AttribuutRef ra && ra.set() && isTemporeelSetAttribuut(ra)) { tijd = ra; ander = v.links();  linksTemp = false; }
            else return null;   // not a temporal-set comparison -> the scalar path
            if (!(tijd.type().isNumeriek() || tijd.type().isPercentage()))
                return LoweredConditie.marker("§8.2 niet-numerieke gehele-periode over verzameling", c.bron());
            if (ander instanceof Expr.AttribuutRef ar && isTemporeelAttribuut(ar))
                return LoweredConditie.marker("§8.2 tijdlijn-vs-tijdlijn over verzameling", c.bron());   // timeline-vs-timeline -> marker
            io.github.yhavinga.regelspraak.model.Eenheid el = tijd.type().eenheidOf(), er = ander.type().eenheidOf();
            boolean crossUnit = el != null && er != null && el.vereistOmrekening(er);
            if (crossUnit && er.exacteHerschaalFactoren(el) == null)
                return LoweredConditie.marker("§8.2 gehele-periode over verzameling met eenheidsomrekening", c.bron());
            Integer code = Tijdrekening.toets(linksTemp ? v.op() : Operatoren.omkeren(v.op()));
            if (code == null) return LoweredConditie.marker("§8.2 datum-gehele-periode over verzameling", c.bron());
            // The grens rides an eval AFTER the collectList accumulate (no $subject-relative field). Restrict to a
            // LITERAL or a scalar Parameter-GLOBAL (a $subject-qualified own/navigated grens stays a symmetric marker,
            // matching the exec eval which has no subject bound on the collected-list pattern).
            if (!(ander instanceof Expr.Literal) && !(ander instanceof Expr.ParameterRef pr && pr.parameter().granulariteit() == null))
                return LoweredConditie.marker("§8.2 gehele-periode over verzameling met niet-scalaire grens", c.bron());
            Frag g = waarde(acc, ander, subject);   // $subject-free value (literal / global) valid in a standalone eval
            if (g.marker()) return LoweredConditie.marker("§8.2 grenswaarde over verzameling", c.bron());
            toets = code;
            grens = crossUnit ? herschaalExact(g.text(), ander.type(), tijd.type()) : g.text();
        } else if (c instanceof Conditie.Predicaat p && p.soort() == Conditie.Predicaatsoort.GEVULD
                && p.operand() instanceof Expr.AttribuutRef pa && pa.set() && isTemporeelSetAttribuut(pa)) {
            tijd = pa; toets = 6;
        } else if (c instanceof Conditie.Predicaat p && p.soort() == Conditie.Predicaatsoort.LEEG
                && p.operand() instanceof Expr.AttribuutRef pa && pa.set() && isTemporeelSetAttribuut(pa)) {
            tijd = pa; toets = 8;
        } else {
            return null;   // not a set §8.2 form the DRL lowers -> scalar path / its marker
        }
        int n = ++acc.n;
        String var = "$tl" + n;
        String accCE = temporeelSetAccumulate(tijd, subject, var);
        if (accCE == null) return LoweredConditie.marker("§8.2 gehele-periode over onbereikbare verzameling", c.bron());
        acc.clausules.add(accCE);   // the collectList accumulate binding $tlN
        fnImport("rsGeheelPeriodeAlle");
        String call = "rsGeheelPeriodeAlle(" + var + ", \"" + gran + "\", " + toets + ", " + grens + ")";
        acc.clausules.add("eval( " + call + " == " + (gp.niet() ? "false" : "true") + " )");
        return new LoweredConditie("", java.util.List.of(), false);
    }

    /** Whether {@code a} is a temporal (tijdlijn) attribute of its (set-element) objecttype — the set analogue of
     *  {@link #isTemporeelAttribuut}, which requires objecttype==subject and so rejects a to-many set element. */
    private boolean isTemporeelSetAttribuut(Expr.AttribuutRef a) {
        return a.objecttype() != null && a.objecttype().attribuutGranulariteit.containsKey(a.attribuut())
                && !a.objecttype().attribuutTijdlijnAantal.containsKey(a.attribuut());   // multi-period -> out of scope
    }

    /** §8.2 the {@code collectList} accumulate binding a to-many temporal set element's List&lt;Segment&gt; timelines into
     *  {@code bindVar}. Returns null for an unresolvable join. The element timelines are collected RAW (no unit
     *  restatement): the grens is instead restated into the element unit by the caller (like the scalar §8.2 path), so all
     *  elements share the same comparison unit. */
    private String temporeelSetAccumulate(Expr.AttribuutRef a, Objecttype subject, String bindVar) {
        Objecttype elem = a.objecttype();
        if (elem == null || subject == null) return null;
        String rol = model.verbindendeRol(elem, subject);
        if (rol == null) return null;
        String ev = "$el" + bindVar.substring(3);   // $tlN -> $elN (distinct element bind, no lexical-prefix overlap)
        return "accumulate( " + ev + " : " + ident(elem.naam, true) + "( " + ident(rol, false) + " == " + SUBJECT + " ) ; "
                + bindVar + " : collectList( " + ev + "." + getter(a.attribuut()) + "() ) )";
    }

    /** §7.1 clip a value timeline by ONE temporal-kenmerk membership: rsTijdKnip to its TRUE periods, or
     *  rsTijdKnipNiet to the complement for a §8.1.8.1/.2 negated membership ("gedurende de tijd dat hij GEEN …"). */
    private String kenmerkTijdKnip(String waarde, Conditie.Kenmerk k) {
        String knip = k.negatie() ? "rsTijdKnipNiet" : "rsTijdKnip";
        if (k.negatie()) fnImport("rsTijdKnipNiet"); else fnImport("rsTijdKnip");
        return knip + "(" + waarde + ", " + SUBJECT + "." + getter(k.kenmerk()) + "(), Segment.class)";
    }

    /** §8.4.2 fold a COMPOUND gedurende over the value timeline. Every onderdeel must be a single-period temporal
     *  kenmerk of the subject (the resolver gated this; re-checked defensively) -> else null (marker). ALLE nests
     *  the per-kenmerk clips (the intersection of TRUE periods, negation per member). "ten minste 1" (TEN_MINSTE,
     *  drempel 1) clips the value to the UNION of the per-member true-periods. */
    private String samengesteldeTijdKnip(Conditie.Samengesteld s, String waarde, Objecttype subject) {
        for (Conditie c : s.onderdelen())
            if (!(c instanceof Conditie.Kenmerk k) || k.navigatie() != null
                    || !subject.kenmerkGranulariteit.containsKey(k.kenmerk())) return null;
        if (s.kwantor() == Conditie.Kwantor.ALLE) {
            String expr = waarde;
            for (Conditie c : s.onderdelen()) expr = kenmerkTijdKnip(expr, (Conditie.Kenmerk) c);
            return expr;
        }
        if (s.kwantor() == Conditie.Kwantor.TEN_MINSTE) {
            // OR = clip the value to the UNION of each member's true-region. A positive member's region is its
            // getter timeline; a NEGATED member's region within the value is rsTijdKnipNiet(value, getter) (the
            // value ∩ ¬kenmerk — the span-dependent complement, materialised against the value's own segments). The
            // union (rsTijdUnie coalesces the intervals) is then clipped once: value ∩ (A ∪ ¬B ∪ …) == the OR.
            fnImport("rsTijdUnie");
            fnImport("rsTijdKnip");
            String unie = lidSteun(waarde, (Conditie.Kenmerk) s.onderdelen().get(0));
            for (int i = 1; i < s.onderdelen().size(); i++)
                unie = "rsTijdUnie(" + unie + ", " + lidSteun(waarde, (Conditie.Kenmerk) s.onderdelen().get(i)) + ", Segment.class)";
            return "rsTijdKnip(" + waarde + ", " + unie + ", Segment.class)";
        }
        return null;
    }

    /** One OR-member's true-region timeline for the union: a positive member is its kenmerk getter; a negated
     *  member is the value clipped to the kenmerk's FALSE periods (rsTijdKnipNiet over the value), so the complement
     *  is bounded by the value's own span rather than an unbounded ¬kenmerk. */
    private String lidSteun(String waarde, Conditie.Kenmerk k) {
        String g = SUBJECT + "." + getter(k.kenmerk()) + "()";
        if (!k.negatie()) return g;
        fnImport("rsTijdKnipNiet");
        return "rsTijdKnipNiet(" + waarde + ", " + g + ", Segment.class)";
    }

    /**
     * Turn an aggregation into a Drools accumulate: bind a source pattern over the
     * element object type and an accumulate function (sum/count/min/max), register it
     * for the "when", and return the result variable to drop into the value. The
     * element scope — which instances relate to the subject — is left as a marker
     * here; navigation joins fill it. Returns null for aggregations not yet mappable.
     */
    private Frag accumulate(Acc acc, Expr.Functie f, Objecttype subject, boolean leegCount) {
        // §4.11/§5.8.1 telling over a concatenation of expressies ("het aantal X, Y en Z"): the
        // count is the UNION of the per-operand sets. When the resolver could OR the per-operand
        // kenmerk filters (f.filter() is the disjunction), this lowers to ONE accumulate whose
        // source pattern carries that OR — an instance matching several operands counts once. A
        // union it could not resolve has no filter -> marker, never a count over the first operand.
        if (f.kind() == Expr.FunctieKind.AANTAL && f.argumenten().size() > 1 && f.filter() == null) return null;
        // §5.8.3 the maximum/minimum of a MULTI-operand value-fold over to-many sets ("de hoogste van de prijs
        // van alle X én de andere prijs van alle X") would need one accumulate per operand folded together;
        // accumulate reads only argumenten().get(0), so operands 2..n (and any die on them) are silently
        // dropped — a wrong max/min. A scalar multi-operand fold is already handled upstream by
        // concatenatieAggregatie (it runs first), so only the over-a-set form reaches here. Mark it rather
        // than fake a partial fold over the first set; the single-operand set MIN/MAX (size 1) is untouched.
        if ((f.kind() == Expr.FunctieKind.MINIMUM || f.kind() == Expr.FunctieKind.MAXIMUM)
                && f.argumenten().size() > 1) return null;
        // §7.1+§5.8 a SOM/TOTAAL over a TEMPORAL attribute of a SET ("het totaal van <attr per maand> van alle <set>")
        // is a PER-ELEMENT timeline fold: each element's tijdreeks is summed over time (rsTijdTotaal) to a scalar, and
        // those scalars are summed over the set. A bare Drools sum() over the getter would aggregate the List<Segment>
        // itself (a ClassCastException), so the element value is wrapped in rsTijdTotaal below. MIN/MAX over a temporal
        // attribute is ambiguous (min/max of which moment?) -> still a marker. (The explicit "de som van het totaal van
        // X van alle" phrasing attaches the set to the INNER totaal, leaving the outer SOM element-less -> elem == null
        // markers it; the inner totaal is then a single-object operand, not this set fold.)
        boolean temporeelArg = !f.argumenten().isEmpty() && f.argumenten().get(0) instanceof Expr.AttribuutRef ta0
                && ta0.objecttype() != null && ta0.objecttype().attribuutGranulariteit.containsKey(ta0.attribuut());
        if (temporeelArg && f.kind() != Expr.FunctieKind.SOM && f.kind() != Expr.FunctieKind.TOTAAL)
            return null;
        Objecttype elem = f.elementObjecttype();
        String fn = switch (f.kind()) {
            case SOM, TOTAAL -> "sum";
            case AANTAL -> "count";
            case MINIMUM -> "min";
            case MAXIMUM -> "max";
            default -> null;
        };
        if (elem == null || fn == null) return null;
        Frag scope = scope(elem, subject, f);
        if (scope.marker()) return null;   // unresolvable join -> marker, never a global aggregate
        // §13.4.2 "die/dat <predicaat>" subselectie on the element ("… van de reis die een kind
        // zijn") -> an extra constraint on the accumulate source pattern, so only the matching
        // instances are aggregated. A filter the resolver could not structure (Onopgelost) lowers to
        // a marker here -> bail, so the aggregation is a visible no-op rather than counting EVERY
        // instance (a silent miscompile).
        String filterScope = scope.text();
        if (f.filter() != null) {
            Frag fc = conditie(acc, f.filter(), elem);  // Kenmerk -> bare element property; Onopgelost -> marker
            if (fc.marker()) return null;
            filterScope = scope.text() + ", " + fc.text();
        }
        int n = ++acc.n;
        String ev = "$e" + n, var = "$acc" + n;
        Expr arg = f.argumenten().isEmpty() ? null : f.argumenten().get(0);
        // §7.1 a per-element timeline fold: a temporal-attribute element value is summed over time (rsTijdTotaal at the
        // attribute's granularity) to a scalar BEFORE the set aggregate, instead of the bare List<Segment> getter.
        java.util.function.Function<Expr.AttribuutRef, String> elemWaarde = a -> {
            String getr = ev + "." + getter(a.attribuut()) + "()";
            if (!temporeelArg) return getr;
            fnImport("rsTijdTotaal");
            return "rsTijdTotaal(" + getr + ", \"" + a.objecttype().attribuutGranulariteit.get(a.attribuut()).name() + "\")";
        };

        // §4.10/§5.8.2 sommatie ("som van"): an empty sum (no non-empty contributors) is a
        // leegwaarde, not 0 — unless "of … als die er niet zijn" supplies a fallback. Drools
        // sum() can't tell an empty sum from a real 0, so count the non-empty contributors and
        // branch on it. TOTAAL ("totaal van") is excluded: §4.20 makes its empty result an
        // unconditional 0, which the plain sum() over an empty accumulate already yields.
        // Needs a resolved join and a plain attribute argument; otherwise fall through.
        if (f.kind() == Expr.FunctieKind.SOM
                && arg instanceof Expr.AttribuutRef a && !scope.marker()) {
            String cnt = "$n" + n;
            acc.clausules.add("accumulate( " + ev + " : " + ident(elem.naam, true)
                    + "( " + filterScope + ", " + ident(a.attribuut(), false) + " != null ) ; "
                    + cnt + " : count(" + ev + "), " + var + " : sum( " + elemWaarde.apply(a) + " ) )");
            Frag fallback = f.leegwaarde() != null ? waarde(acc, f.leegwaarde(), subject) : Frag.ok("null");
            return Frag.from("(" + cnt + " == 0 ? " + fallback.text() + " : " + var + ")", fallback);
        }

        String elementExpr = ev;
        if (!fn.equals("count") && arg instanceof Expr.AttribuutRef a)
            elementExpr = elemWaarde.apply(a);
        // §5.8.3 an empty MIN/MAX is LEEG (§5.3 Tabel 37 / §5.6 Tabel 44), but a bare min()/max() accumulate binds NO
        // result row over an EMPTY source — so a downstream CONDITION compare would never run (the rule silently reads
        // onwaar, wrong for leeg==leeg / leeg!=gevuld). In a condition context (leegCount) bind count() alongside the
        // min/max so the accumulate ALWAYS produces a row; the result is null for an empty set (count==0) and the min/max
        // value otherwise — letting rsAggVergelijk apply the leegwaarde semantics. The VALUE side (leegCount=false) keeps
        // the bare fold: an empty value-aggregation simply does not fire (unchanged).
        if (leegCount && (f.kind() == Expr.FunctieKind.MINIMUM || f.kind() == Expr.FunctieKind.MAXIMUM)) {
            String cnt = "$n" + n;
            acc.clausules.add("accumulate( " + ev + " : " + ident(elem.naam, true) + "( " + filterScope + " ) ; "
                    + cnt + " : count(" + ev + "), " + var + " : " + fn + "( " + elementExpr + " ) )");
            return Frag.ok("(" + cnt + " == 0 ? null : " + fnImport("toBd") + "(" + var + "))");
        }
        acc.clausules.add("accumulate( " + ev + " : " + ident(elem.naam, true)
                + "( " + filterScope + " ) ; " + var + " : " + fn + "( " + elementExpr + " ) )");
        // §5.8.3 a min/max reduction binds $accN as a java.lang.Number (the element's static type in the accumulate),
        // so a downstream cross-unit restate (.multiply/.divide) or arithmetic on it fails Drools' strict-mode method
        // resolution (Number has no .multiply). Coerce to BigDecimal, null-safe (an empty min/max stays leeg), mirroring
        // the exec's RegelSpraakRuntime.toBd. SUM already yields a BigDecimal accumulate; COUNT stays an integer count.
        if (f.kind() == Expr.FunctieKind.MINIMUM || f.kind() == Expr.FunctieKind.MAXIMUM)
            return Frag.ok(fnImport("toBd") + "(" + var + ")");
        return Frag.ok(var);
    }

    /** The element scope: the join binding the element to the subject ("the passagiers
     *  OF this reis" -> reis == $subject), if the connecting feittype is unambiguous;
     *  otherwise the source phrase as a marker. */
    private Frag scope(Objecttype elem, Objecttype subject, Expr.Functie f) {
        Expr.AttribuutRef arg = !f.argumenten().isEmpty() && f.argumenten().get(0) instanceof Expr.AttribuutRef a ? a : null;
        // A count names its element role explicitly ("het aantal bestemming …"), so when more than
        // one feittype joins the (element, subject) pair the written name picks the unique join the
        // by-pair search cannot. Try that first, then fall back to the by-pair role.
        String rol = null;
        if (subject != null && arg != null && f.kind() == Expr.FunctieKind.AANTAL)
            rol = model.verbindendeRolViaElementnaam(subject, arg.attribuut());
        if (rol == null && subject != null) rol = model.verbindendeRol(elem, subject);
        if (rol != null) return Frag.ok(ident(rol, false) + " == " + SUBJECT);
        // No join resolvable: emit a TODO marker, so accumulate() returns null and the aggregation
        // becomes a visible no-op — never a silently-wrong count over EVERY instance (§5.8.1: a count
        // ranges over the subject's related instances). Consistent with the resolver's "unresolvable
        // -> marker" rule.
        return Frag.todo("aggregatie-bereik", arg != null ? arg.onderwerpBron() : f.bron());
    }

    /** §5.8/§13.4.14 an AGGREGATION (count / SUM / TOTAAL / MIN / MAX) as one side of a condition comparison ("het aantal /
     *  de som van / de maximale waarde van &lt;elem&gt; van &lt;subject&gt; &lt;op&gt; RHS") — lowered to a result-constrained
     *  accumulate CE hoisted after the subject pattern (an EMPTY-inline {@link LoweredConditie} whose extra is the accumulate
     *  + an rsAggVergelijk eval). The RHS may be a literal, an own attribute, a numeric scalar Parameter-global, or a to-ONE
     *  navigated numeric attribute (null-safe via rsRelGetal); any comparison operator (ordering / GELIJK / ONGELIJK, the
     *  §5.3/§5.6 leegwaarde cells). An empty SOM/MIN/MAX reads leeg (leegCount). BOTH sides aggregations = agg-vs-agg (two
     *  accumulate CEs). null when unlowerable (an unresolved join / a navigated-or-multi-global RHS / a to-many RHS) -> the
     *  caller falls through to the marker path. Mirrors the exec Gate. */
    private LoweredConditie aggregatieVoorwaarde(Acc acc, Conditie.Vergelijking v, Objecttype subject) {
        return aggregatieVoorwaarde(acc, v, subject, false);
    }
    /** {@code negeer} negates the boolean RESULT of the comparison (an eval-level {@code !( … )}), NOT the operator — for
     *  a single aggregation bullet under a GEEN quantifier ("aan geen van de volgende voorwaarden: - het aantal … > 10").
     *  Result-negation is leegwaarde-correct (NOT(SOM(leeg) > 10)=WAAR, whereas SOM(leeg) <= 10 stays onwaar). */
    private LoweredConditie aggregatieVoorwaarde(Acc acc, Conditie.Vergelijking v, Objecttype subject, boolean negeer) {
        if (subject == null) return null;
        // accumulate() appends its CE to the THREADED Acc context (no-mutable-build-state) before returning the result Frag.
        // A post-accumulate bail (an unresolvable 2nd aggregation in agg-vs-agg, a to-many/unresolvable navigated RHS) must
        // roll that back via aggTerug(acc, mark) — else the marker rule keeps a dangling ORPHAN accumulate (inert but malformed).
        final int mark = acc.clausules.size();
        boolean linksAgg = v.links() instanceof Expr.Functie fl && isAggregatieFunctie(fl.kind());
        boolean rechtsAgg = v.rechts() instanceof Expr.Functie fr && isAggregatieFunctie(fr.kind());
        if (!linksAgg && !rechtsAgg) return null;               // no aggregation operand
        if (linksAgg && rechtsAgg) {                            // §5.8 agg-vs-agg ("het aantal X <op> het aantal Y")
            // Two accumulate CEs over the same subject; the eval compares both results via rsAggVergelijk (the right agg
            // coerced to BigDecimal via toBd). leegCount=true so an empty SOM/MIN/MAX on either side reads leeg.
            Frag a1 = accumulate(acc, (Expr.Functie) v.links(), subject, true);
            if (a1 == null || a1.marker()) return aggTerug(acc, mark);
            Frag a2 = accumulate(acc, (Expr.Functie) v.rechts(), subject, true);
            if (a2 == null || a2.marker()) return aggTerug(acc, mark);
            String cmp = fnImport("rsAggVergelijk") + "(" + a1.text() + ", "
                    + fnImport("toBd") + "(" + a2.text() + "), \"" + Operatoren.symbool(v.op()) + "\")";
            acc.clausules.add("eval( " + (negeer ? "!(" + cmp + ")" : cmp) + " )");
            return new LoweredConditie("", java.util.List.of(), false);
        }
        Expr.Functie f = (Expr.Functie) (linksAgg ? v.links() : v.rechts());
        Expr ander = linksAgg ? v.rechts() : v.links();
        // scope: a LITERAL, an OWN (non-navigated) attribute, a numeric scalar Parameter-GLOBAL, or a numeric to-ONE
        // NAVIGATED attribute threshold (the eval has $subject and the header-declared globals in scope). The own-attr
        // containsKey guard is load-bearing: a navigation the resolver could NOT encode surfaces as navRol==null with a
        // name NOT on the subject — the exec's scalarWaarde would build a LIVE crashing rule for it (review wf_900d3055-a62
        // F2), so ONLY a PROPER navigation (navRol != null, resolved to a to-one below via naviResolve / navReader) is
        // admitted, read null-safely (rsRelGetal / navReader). The global/navigated attr must be numeric (isNumeriek/
        // isPercentage -> a BigDecimal, matching rsAggVergelijk's `n`) and non-temporal; a global is also scalar
        // (granulariteit==null) — EXACTLY the set the exec tracks/reads. A tekst/datum/temporal/to-many RHS stays a marker.
        // ALSO admitted: a numeric arithmetic EXPRESSION reading 2 DISTINCT scalar numeric globals ("de grensa plus de
        // grensb") — DRL renders each global as a free mvel name in the eval (waarde() below), the exec binds each into
        // its own Optional from-decl (aggMultiGlobalRhs). Capped at 2 distinct globals to match the exec Predicate-arity
        // ceiling; 3+ distinct stays a symmetric marker. A single global goes through the ParameterRef arm above.
        boolean multiGlobalRhs = !(ander instanceof Expr.AttribuutRef) && !(ander instanceof Expr.Literal)
                && !(ander instanceof Expr.ParameterRef) && aggRhsGlobalNamen(ander).size() == 2;
        if (!(ander instanceof Expr.Literal)
                && !(ander instanceof Expr.AttribuutRef ar && ar.navigatieRol() == null && ar.objecttype() == subject
                     && subject.attributen.containsKey(ar.attribuut()))
                && !(ander instanceof Expr.ParameterRef pr && pr.parameter().granulariteit() == null
                     && (pr.parameter().datatype().isNumeriek() || pr.parameter().datatype().isPercentage()))
                && !(ander instanceof Expr.AttribuutRef nav && nav.objecttype() != null
                     && (nav.navigatieRol() != null || nav.objecttype() != subject) && nav.objecttype().heeftLid(nav.attribuut())
                     && nav.type() != null && (nav.type().isNumeriek() || nav.type().isPercentage())
                     && !isTemporeelAttribuut(nav))
                && !multiGlobalRhs) return null;
        Conditie.Operator op = linksAgg ? v.op() : Operatoren.omkeren(v.op());   // orient the aggregation on the LEFT (Operatoren.omkeren(ONGELIJK)=ONGELIJK, != symmetric)
        // Reuse the VALUE-side accumulate lowering: it registers the accumulate CE (count / BigDecimal sum with the §5.8.2
        // empty-sum leegwaarde branch / min·max coerced to BigDecimal) in the Acc context and returns the RESULT value expr
        // ($accN, or the SOM/min·max count-branch ternary). The comparison then rides an eval AFTER the accumulate (both
        // flushed after the subject pattern) via rsAggVergelijk. leegCount=true so an empty SOM/MIN/MAX binds a count row
        // -> the compare runs with a null aggregate and applies the §5.3/§5.6 leegwaarde cells (leeg==leeg / leeg!=gevuld).
        Frag agg = accumulate(acc, f, subject, true);
        if (agg == null || agg.marker()) return aggTerug(acc, mark);   // accumulate() returns NULL (not a marker Frag) for an
                                                        // unresolvable join / unstructured die-filter / multi-operand fold
        // the RHS rides the EVAL (a standalone when-clause), so it needs a $subject-qualified value ($subject.getX()), NOT
        // operand()'s pattern-relative field name. A literal/own-attr/global goes through waarde() ($subject-qualified). A
        // to-ONE navigated numeric read goes through the null-safe rsRelGetal($subject.get<Rol>(), "attr") — an absent
        // relation reads leeg, NOT a short-circuit or an NPE (waarde() would emit the un-guarded $subject.getRol().getAttr()).
        Frag n;
        if (ander instanceof Expr.AttribuutRef nar && (nar.navigatieRol() != null || nar.objecttype() != subject)) {
            NaviRef nr = naviResolve(nar, subject);
            if (nr.soort() != NaviSoort.GENAVIGEERD) return aggTerug(acc, mark);   // AFWEZIG / NAAR_VEEL / ONOPGELOST -> marker
            fnImport("rsRelGetal");
            n = Frag.ok("rsRelGetal(" + SUBJECT + "." + getter(nr.rol()) + "(), \"" + nar.attribuut() + "\")");
        } else {
            n = waarde(acc, ander, subject);
            if (n.marker()) return aggTerug(acc, mark);
        }
        String cmp = fnImport("rsAggVergelijk") + "(" + agg.text() + ", " + n.text() + ", \"" + Operatoren.symbool(op) + "\")";
        acc.clausules.add("eval( " + (negeer ? "!(" + cmp + ")" : cmp) + " )");
        return new LoweredConditie("", java.util.List.of(), false);
    }

    /** The DISTINCT scalar-numeric Parameter-global names an aggregation-condition RHS expression reads, walking the
     *  arithmetic tree (Binair/Afgerond/Begrensd/Functie args). Only §13.3.8 scalar (granulariteit==null) numeric/
     *  percentage parameters count — a temporal or non-numeric parameter yields an EMPTY set, so the multi-global arm
     *  declines it (staying a marker). A §13.4.1 multi-hop CHAIN ref anywhere in the tree POISONS the walk (empty set):
     *  this arm lowers the RHS through the value-side {@code waarde()}, which fires chains, while the exec's condition
     *  path (ketenWaardePad off) declines them — admitting it here would be a DRL-fires/exec-skips split. Used by
     *  {@link #aggregatieVoorwaarde} to admit the 2-distinct-global RHS. */
    private static Set<String> aggRhsGlobalNamen(Expr e) {
        Set<String> uit = new java.util.LinkedHashSet<>();
        return aggRhsGlobalNamen(e, uit) ? uit : java.util.Set.of();
    }
    private static boolean aggRhsGlobalNamen(Expr e, Set<String> uit) {
        if (e instanceof Expr.AttribuutRef a) return a.navigatiePad().isEmpty();   // a chain leg -> poison (see doc)
        if (e instanceof Expr.ParameterRef p) {
            if (p.parameter().granulariteit() == null
                    && (p.parameter().datatype().isNumeriek() || p.parameter().datatype().isPercentage()))
                uit.add(p.parameter().naam());
            return true;
        }
        if (e instanceof Expr.Binair b) return aggRhsGlobalNamen(b.links(), uit) && aggRhsGlobalNamen(b.rechts(), uit);
        if (e instanceof Expr.Afgerond a) return aggRhsGlobalNamen(a.basis(), uit);
        if (e instanceof Expr.Begrensd bg)
            return aggRhsGlobalNamen(bg.basis(), uit)
                    && (bg.minimum() == null || aggRhsGlobalNamen(bg.minimum(), uit))
                    && (bg.maximum() == null || aggRhsGlobalNamen(bg.maximum(), uit));
        if (e instanceof Expr.Functie fn) {
            for (Expr a : fn.argumenten()) if (!aggRhsGlobalNamen(a, uit)) return false;
            return true;
        }
        return true;
    }

    /** Roll back any accumulate CEs {@link #accumulate} appended into the Acc context since {@code mark}, then signal
     *  "not lowered" (null) — so a post-accumulate bail (unresolvable 2nd agg / to-many navigated RHS) leaves NO orphan
     *  accumulate in the marker rule (review wf_ee9d1919-c3c). */
    private LoweredConditie aggTerug(Acc acc, int mark) {
        if (acc.clausules.size() > mark) acc.clausules.subList(mark, acc.clausules.size()).clear();
        return null;
    }

    private static boolean isAggregatieFunctie(Expr.FunctieKind k) {
        return k == Expr.FunctieKind.AANTAL || k == Expr.FunctieKind.SOM || k == Expr.FunctieKind.TOTAAL
                || k == Expr.FunctieKind.MINIMUM || k == Expr.FunctieKind.MAXIMUM;
    }
    /** Does the comparison have an aggregation (count/sum/totaal/min/max) as either operand? — a bullet routed through
     *  {@link #aggregatieVoorwaarde} rather than the counting fold's inline-boolean expansion (which cannot host it). */
    private static boolean isAggregatieVergelijking(Conditie.Vergelijking v) {
        return (v.links() instanceof Expr.Functie fl && isAggregatieFunctie(fl.kind()))
                || (v.rechts() instanceof Expr.Functie fr && isAggregatieFunctie(fr.kind()));
    }

    // ---------------- condition ("when" side: bare field constraints) ----------------
    /** A lowered condition: the inline mvel constraint spliced into the subject pattern, plus zero or
     *  more SEPARATE when-clauses (exists/not joins) appended AFTER it. {@code extra} is non-empty only
     *  for a hoistable far-side membership (§8.1.7 "heeft een &lt;rol&gt;") — directly, or carried up
     *  through a pure conjunction (§13.4.14 alle/de). An exists-join cannot live inside the in-pattern
     *  constraint, so it rides here instead; a caller that has nowhere to append it degrades to a marker
     *  via {@link #conditieInline}. */
    private record LoweredConditie(String inline, List<String> extra, boolean marker) {
        /** A fully-lowered constraint (no placeholder), with no hoisted clauses. */
        static LoweredConditie van(String inline) { return new LoweredConditie(inline, List.of(), false); }
        /** An unresolved constraint: the always-false {@code false /* TODO … *​/} placeholder, flagged. */
        static LoweredConditie marker(String wat, String bron) {
            return new LoweredConditie(onopgelosteConstraint(wat, bron), List.of(), true);
        }
        boolean heeftExtra() { return !extra.isEmpty(); }
    }

    /** The inline constraint of a condition for a caller that has NO place to append an extra when-clause
     *  (the §13.4.8 schending OR/negation, an accumulate source pattern, the feitcreatie anker/$doel
     *  patterns, the objectcreatie guard). A condition that WANTS to hoist a join ({@code heeftExtra})
     *  has no faithful inline form here, so it collapses to the always-false marker {@link Frag} — exactly
     *  the un-lowerable signal these sites already detect via {@link Frag#marker}, never a silent drop. */
    private Frag conditieInline(Acc acc, Conditie c, Objecttype subject, boolean alsVoorwaarde) {
        LoweredConditie lc = conditie(acc, c, subject, alsVoorwaarde);
        if (lc.heeftExtra()) return new Frag(onopgelosteConstraint("voorwaarde", c.bron()), true);
        return new Frag(lc.inline(), lc.marker());
    }

    private Frag conditie(Acc acc, Conditie c, Objecttype subject) { return conditieInline(acc, c, subject, false); }

    /** Lower a condition to an mvel constraint plus any hoisted join clauses. {@code alsVoorwaarde} marks a
     *  rule-GUARD / filter context, where the §5.3 both-empty rule applies; it is false when this is the
     *  NEGATION of a consistentie assertion (built by {@link #schendingConstraint}'s complement / negeer
     *  tail), because there a both-empty "fout" must still FIRE the violation (Tabel 44), so the guard
     *  must NOT be added. */
    private LoweredConditie conditie(Acc acc, Conditie c, Objecttype subject, boolean alsVoorwaarde) {
        // §8.2 / §13.4.15.70 "gedurende de/het gehele jaar|maand": a whole-period check over a tijdsafhankelijk
        // onderwerp (a List<Segment> timeline). Lower to a boolean rsGeheelPeriode(...) term BEFORE the scalar paths
        // below (which would marker the temporal operand). A form §8.2 cannot lower stays an honest marker.
        Conditie.GeheelPeriode gp = geheelPeriodeVan(c);
        if (gp != null) {
            // §8.2 over a NAVIGATED to-many temporal SET ("de X van alle Y ... gedurende het gehele jaar <op> grens"):
            // a forall across the accumulated element timelines — a collectList accumulate + an rsGeheelPeriodeAlle eval.
            LoweredConditie alle = geheelPeriodeAlleVoorwaarde(acc, c, gp, subject);
            if (alle != null) return alle;
            Frag f = geheelPeriodeCall(acc, c, gp, subject);
            if (f.marker()) return LoweredConditie.marker("§8.2 gehele-periode", c.bron());
            return LoweredConditie.van(f.text() + " == " + (gp.niet() ? "false" : "true"));
        }
        // §8.4.3 / §13.4.15.68-.69 periodevergelijking: the rule applies during the period, and the engine
        // evaluates scalar rules as of the §4.2 Rekendatum — so lower to "rekendatum inside [vanaf, tot)"
        // (§5.1.3: vanaf inclusive; tot exclusive; tot en met inclusive), the expression-bound analogue of the
        // geldigheidGuard window. Bounds are restricted to the shapes the exec back-end mirrors — a Datum-in-dagen
        // literal or the subject's OWN scalar Datum-in-dagen attribute (null-guarded: a leeg bound never matches);
        // anything else (parameter/navigated/computed/Datum-en-tijd) stays a SYMMETRIC honest marker.
        if (c instanceof Conditie.Periode per) {
            StringJoiner sj = new StringJoiner(" && ");
            sj.add("rekendatum != null");
            for (boolean isVan : new boolean[]{true, false}) {
                Expr grens = isVan ? per.vanaf() : per.tot();
                if (grens == null) continue;
                if (!Tijdrekening.periodeGrensLowerbaar(grens, subject))
                    return LoweredConditie.marker("periodevergelijking-grens '" + grens.bron() + "' (§8.4.3)", c.bron());
                Frag g = operand(acc, grens, subject);
                if (g.marker()) return LoweredConditie.marker("periodevergelijking-grens (§8.4.3)", c.bron());
                if (!(grens instanceof Expr.Literal)) sj.add(g.text() + " != null");
                sj.add(isVan ? "!rekendatum.isBefore(" + g.text() + ")"
                             : (per.totInclusief() ? "!rekendatum.isAfter(" : "rekendatum.isBefore(") + g.text() + ")");
            }
            rekendatumNodig = true;
            return LoweredConditie.van(sj.toString());
        }
        if (c instanceof Conditie.Vergelijking va) {
            // §5.8/§13.4.14 a COUNT aggregation as one side of the comparison ("het aantal X van Y <op> N") — lower it to
            // a result-constrained accumulate CE hoisted after the subject pattern, not an inline scalar constraint (which
            // markers, since a count is not one getter). Checked BEFORE the scalar operand paths below.
            LoweredConditie agg = aggregatieVoorwaarde(acc, va, subject);
            if (agg != null) return agg;
        }
        if (c instanceof Conditie.Vergelijking v) {
            // An operand the resolver/transpiler could not lower (an unresolvable navigation, an
            // under-specified dimensioned read) makes the whole comparison unfaithful — and emitting it raw
            // would be uncompilable DRL (a Map-vs-number term sinks the package). Make the guard an honest
            // always-false marker instead. (Checked BEFORE the unit restatement, which a marker operand must
            // never reach — its .multiply would wrap the placeholder.)
            Frag lo = operand(acc, v.links(), subject), ro = operand(acc, v.rechts(), subject);
            if (lo.marker() || ro.marker()) return LoweredConditie.marker("voorwaarde", v.bron());
            // §5.5.5 a comparison with a TO-ONE navigated operand: mvel's `!.` (from operand()) short-circuits the whole
            // constraint to a non-match on a null relation — spec-correct for ordering/GELIJK (a leeg operand is onwaar)
            // but WRONG for ONGELIJK (Tabel 44: a leeg-vs-gevuld "ongelijk aan" is WAAR). Read the comparison leegwaarde-
            // faithfully via explicit leeg/vol/waarde guards (the complement of the consistentie schending form) instead.
            String navVw = genavigeerdeVergelijkingVoorwaarde(acc, v, subject);
            if (navVw != null) return LoweredConditie.van(navVw);
            // §5.1 Tabel 32: a comparison across convertible-but-different units restates
            // the right operand in the left's unit first ("24 mnd > 2 jr", not "24 > 2").
            String[] o = vergelijkingsOperanden(lo.text(), v.links().type(), ro.text(), v.rechts().type());
            // §389/§486: when a numeric operand is arithmetic that can carry an exact Breuk (a non-terminating
            // division/percentage or a rational literal), an inline mvel "l OP r" would throw on a Breuk (mvel's
            // == casts to BigDecimal) — so compare EXACTLY via the Breuk-aware rsAggVergelijk (single-runtime; it
            // also handles the numeric leegwaarde tables 37/44/33). Only for a numeric comparison with a Breuk-capable
            // operand; the ordinary field/literal comparison keeps its inline form (no churn).
            boolean numeriek = (v.links().type() != null && v.links().type().isGetalachtig())
                    || (v.rechts().type() != null && v.rechts().type().isGetalachtig());
            if (numeriek && (bevatBreukBron(v.links()) || bevatBreukBron(v.rechts()))) {
                fnImport("rsAggVergelijk");
                return LoweredConditie.van("rsAggVergelijk(" + o[0] + ", " + o[1] + ", \"" + Operatoren.symbool(v.op()) + "\")");
            }
            String cmp = o[0] + " " + Operatoren.symbool(v.op()) + " " + o[1];
            // §5.3 / typeringen Tabel 38: a NON-numeriek "gelijk aan" with BOTH operands empty is "fout"
            // (a foutmelding), NOT "waar" — so as a rule GUARD the condition is not satisfied and the rule
            // must NOT fire. Plain mvel reads "null == null" as true (it would fire), so exclude the
            // both-empty case (p40). A numeriek both-empty stays "waar"/fires per Tabel 37 (the §5.3 prose
            // says "onwaar" there — a spec contradiction left to the existing Tabel-37 reading).
            if (alsVoorwaarde && v.op() == Conditie.Operator.GELIJK && nietNumeriekVergelijking(v)
                    && !(v.links() instanceof Expr.Literal) && !(v.rechts() instanceof Expr.Literal))
                return LoweredConditie.van("(" + cmp + " && !(" + o[0] + " == null && " + o[1] + " == null))");
            return LoweredConditie.van(cmp);
        }
        if (c instanceof Conditie.Predicaat p) {
            // A leeg/gevuld predicate over a TO-ONE navigated operand cannot use the null-safe "!." form: "!."
            // short-circuits the whole term to a NON-match on a null reference, which is the WRONG answer for
            // `is leeg` (typeringen Tabel 54: an absent value IS leeg -> waar). Guard the reference explicitly
            // so an absent reference reads as EMPTY — leeg = (rol == null || rol.attr == null); gevuld = (rol
            // != null && rol.attr != null) — the "." after the short-circuit guard never dereferences null.
            String navLeeg = genavigeerdLeegGevuld(p, subject);
            if (navLeeg != null) return LoweredConditie.van(navLeeg);
            Frag o = operand(acc, p.operand(), subject);
            if (o.marker()) return LoweredConditie.marker("voorwaarde", p.bron());  // e.g. an under-specified dimensioned read
            return switch (p.soort()) {
                case LEEG   -> LoweredConditie.van(o.text() + " == null");
                case GEVULD -> LoweredConditie.van(o.text() + " != null");
                case ELFPROEF -> { fnImport("rsElfproef"); yield LoweredConditie.van((p.negatie() ? "!" : "") + "rsElfproef(" + o.text() + ")"); }  // §8.1.3 (Tabel 16 NIET-vorm)
                case NUMERIEK_MET_EXACT -> { fnImport("rsNumeriekMetExact");   // §8.1.4 (Tabel 16 NIET-vorm)
                    yield LoweredConditie.van((p.negatie() ? "!" : "") + "rsNumeriekMetExact(" + o.text() + ", " + p.cijfers() + ")"); }
                case DAGSOORT -> {  // §8.1.5 "<datum> is (geen) <dagsoort>" -> the §9.8 definitie predicate (a global)
                    String glob = dagsoortGlobaal(p.dagsoort());
                    // §5.15: een leegwaarde is nooit een dagsoort -> a null date is ONWAAR (no match), not an NPE. The
                    // NEGATED form (Tabel 16 "is geen <dagsoort>") wraps it in "!" -> a leeg/non-dagsoort datum is WAAR.
                    // §8.1.5/§5.15/Tabel 57: a Datum-en-tijd (LocalDateTime) operand is day-granular here — take its
                    // .toLocalDate() for the §9.8 LocalDate predicate (after the != null guard, so no NPE; the && short-circuits).
                    String dag = p.operand().type() instanceof Datatype.DatumTijd ? o.text() + ".toLocalDate()" : o.text();
                    yield glob == null ? LoweredConditie.marker("dagsoortcontrole '" + p.dagsoort() + "'", p.bron())
                            : LoweredConditie.van((p.negatie() ? "!" : "") + "(" + o.text() + " != null && " + glob + ".test(" + dag + "))");
                }
                default     -> LoweredConditie.marker("predicaat " + p.soort(), p.bron());
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
                return LoweredConditie.marker("kenmerk '" + k.kenmerk() + "' hoort bij " + k.eigenaar().naam, c.bron());
            // §13.3.6/§8.2: a tijdsafhankelijk kenmerk is a List<Segment> boolean timeline — a bare "<k> == true"
            // membership check (or the §8.2 "gedurende het gehele jaar <k> is/heeft") would compare the List to a
            // boolean and sink the build; marker it. The gedurende CLIP path reads the same kenmerk as a timeline
            // via temporeelKenmerkTijdreeks (not this scalar leaf), so the clip is unaffected. Covers an own-subject
            // kenmerk AND a to-one navigated one (its owner is resolved from the reference role's far type).
            Objecttype kHouder = k.navigatie() == null ? subject
                    : (subject != null ? model.rolDoeltypeNaarSubject(subject, k.navigatie()) : null);
            if (kHouder != null && kHouder.kenmerkGranulariteit.containsKey(k.kenmerk()))
                return LoweredConditie.marker("tijdsafhankelijk kenmerk in scalaire voorwaarde (§8.2)", c.bron());
            String prop = ident(k.kenmerk(), false) + " == " + (k.negatie() ? "false" : "true");
            if (k.navigatie() == null) return LoweredConditie.van(prop);
            String nav = ident(k.navigatie(), false);
            return LoweredConditie.van(nav + " != null && " + nav + "." + prop);
        }
        // §8.1.7 rolcheck: the subject fills the role iff its reference to the other end of the feittype is
        // set. This lowers to a bare null-test inside the subject's own pattern ONLY when that reference is a
        // to-ONE FORWARD field on the subject — "is een passagier" -> "reis != null". When the subject is on
        // the "één" side (the reference field lives on the other, MANY side: "de Vlucht is een reis", where
        // `passagier` is a to-many role with no scalar field on Vlucht) a bare `passagier != null` would be
        // uncompilable DRL that sinks the package, so that far-side case stays an honest marker (carded) — an
        // exists-join would be needed, which does not fit a single in-pattern constraint.
        if (c instanceof Conditie.Rollidmaatschap r) {
            String veld = subject != null ? model.verbindendeRol(subject, r.anderTeFiller()) : null;
            // the subject fills the role iff its reference to the other end is set; the §8.1.7 "is geen" negation
            // is the inverse null-test. The far-side / to-many case has no in-pattern reference -> marker.
            if (veld != null && !model.naarVeelRol(subject, r.anderTeFiller(), veld))
                return LoweredConditie.van(ident(veld, false) + (r.negatie() ? " == null" : " != null"));
            return LoweredConditie.marker("rollidmaatschap '" + r.rol() + "'", c.bron());
        }
        // §8.1.9 / §13.4.15.66 regelpredicaat "regelversie <X> is gevuurd|inconsistent". "is inconsistent"
        // reads the §9.5 Inconsistentie channel: an exists-join on the flag fact the named consistentie/
        // uniciteit/verdeling rule inserts (hoisted like the far-side rolcheck below — it cannot live inside
        // the subject pattern). "is gevuurd" has NO observable runtime record (rules write values, they do
        // not log firing) and a name that is not a known Inconsistentie producer would be a never-matching
        // exists (a silent never-fire) — both stay an honest marker, symmetric with the exec skip.
        if (c instanceof Conditie.Regelversie rv) {
            if (rv.inconsistent() && model.inconsistentieProducent(rv.regel()))
                return new LoweredConditie("", List.of("exists " + FACT_PACKAGE + ".Inconsistentie( regel == \""
                        + escapeRegelNaam(rv.regel()) + "\" )"), false);
            // §8.1.9 "is gevuurd" over an instrumented main-regel() producer: an exists on the RuleFired marker
            // that producer's consequence inserts (hoisted like the Inconsistentie exists — it has no subject of
            // its own, so it cannot live inside the subject pattern). Drools re-activates this reader when the
            // producer fires later in the same session (§8.1.9 fixpoint: the insert re-triggers the exists).
            if (!rv.inconsistent() && model.gevuurdProducent(rv.regel())) {
                ruleFiredNodig = true;
                return new LoweredConditie("", List.of(ruleFiredGuard(rv.regel())), false);
            }
            // Un-instrumented target (a consistentie/uniciteit rule whose "result" is the Inconsistentie channel,
            // an objectcreatie/verdeling, or an unknown name) stays an honest marker, symmetric with the exec skip
            // — never a never-matching exists (a silent never-fire).
            return LoweredConditie.marker("regelpredicaat (§8.1.9): " + (rv.inconsistent()
                    ? "onbekende regelversie '" + rv.regel() + "'"
                    : "'is gevuurd' over niet-geïnstrumenteerde regelversie '" + rv.regel() + "'"), c.bron());
        }
        // §8.1.7 far-side "heeft een <rol>": the reference lives on the to-many filler, so this is an EXISTS-join
        // — a separate when-clause that CANNOT live inside the subject pattern's inline constraint. It rides out as
        // an extra clause (empty inline); regel() appends it after the subject pattern, and a pure-conjunction
        // compound carries it up. Any caller without a place to host it (conditieInline) degrades it to a marker.
        if (c instanceof Conditie.VerreRollidmaatschap vr)
            return new LoweredConditie("", List.of(verreRolClausule(vr)), false);
        if (c instanceof Conditie.Samengesteld s) {
            // §8.4.3 a periodevergelijking-bullet under a COUNTING quantifier (geen/ten minste/ten hoogste/
            // precies): the exec back-end's counting fold is fact-only (it cannot bind the rekendatum global),
            // so marker here too — symmetric, never a DRL-fires/exec-skips divergence. ALLE/DE conjunctions
            // (the spec's own §8.4.3 example) lower normally below.
            if (s.kwantor() != Conditie.Kwantor.ALLE && s.kwantor() != Conditie.Kwantor.DE
                    && s.onderdelen().stream().anyMatch(Conditie::bevatPeriode))
                return LoweredConditie.marker("periodevergelijking onder een tellende kwantor (§8.4.3)", s.bron());
            // §13.4.14.4 a SINGLE aggregation bullet under GEEN / "ten minste 1": the quantifier reduces to a plain
            // boolean transform of the one leaf (GEEN = NOT the leaf; ten minste 1 = the leaf), so route it through the
            // aggregation lowering with a boolean-NEGATION flag for GEEN. Negating the rsAggVergelijk RESULT (not the
            // comparison operator) is leegwaarde-correct: NOT(SOM(leeg) > 10)=WAAR, whereas SOM(leeg) <= 10 would be
            // onwaar (§5 empty-set ordering). The aggregation contributes an accumulate CE (a separate when-clause) +
            // an eval, which the counting fold's inline-boolean expansion below CANNOT host — so this is the only way
            // an aggregation bullet fires under a counting quantifier. >1 bullet / ten hoogste / precies stay markers.
            if ((s.kwantor() == Conditie.Kwantor.GEEN
                 || (s.kwantor() == Conditie.Kwantor.TEN_MINSTE && s.drempel() == 1))
                    && s.onderdelen().size() == 1
                    && s.onderdelen().get(0) instanceof Conditie.Vergelijking av && isAggregatieVergelijking(av)) {
                LoweredConditie agg = aggregatieVoorwaarde(acc, av, subject, s.kwantor() == Conditie.Kwantor.GEEN);
                if (agg != null) return agg;   // null -> unlowerable RHS -> fall through to the general marker below
                return LoweredConditie.marker("aggregatie-bullet onder tellende kwantor", s.bron());
            }
            // Any OTHER aggregation bullet under a counting (non-conjunction) quantifier — a MULTI-bullet compound, or a
            // ten hoogste / precies — cannot be folded into the inline-boolean expansion (an aggregation is an accumulate
            // CE + eval, not a pattern constraint), so it MARKERS symmetrically (the exec's fact-only fold skips it too).
            // Checked BEFORE recursing so no orphan accumulate leaks into a broken "!(())" fold.
            if (s.kwantor() != Conditie.Kwantor.ALLE && s.kwantor() != Conditie.Kwantor.DE
                    && s.onderdelen().stream().anyMatch(d -> d instanceof Conditie.Vergelijking cv && isAggregatieVergelijking(cv)))
                return LoweredConditie.marker("aggregatie-bullet onder tellende kwantor (>1 bullet / ten hoogste / precies)", s.bron());
            List<LoweredConditie> kinderen = new ArrayList<>();
            for (Conditie deel : s.onderdelen()) kinderen.add(conditie(acc, deel, subject, alsVoorwaarde));
            if (kinderen.isEmpty()) return LoweredConditie.van("true");
            // Only a PURE CONJUNCTION (§13.4.14 alle/de) is a plain && of its parts, so a hoisted exists/not
            // clause — implicitly AND-ed as a separate when-clause — is faithful there. The other quantifiers
            // fold the parts with || / ! (geen, ten hoogste, precies, and ten minste via `minstens`), where a
            // separate clause would instead ASSERT the membership unconditionally — a silently-wrong gate. So a
            // child that wants to hoist (`heeftExtra`) under any NON-conjunction quantifier no-ops the whole
            // compound, exactly as a marker child does. Keyed on alle/de EXPLICITLY (never the default arm) so a
            // future quantifier cannot silently inherit the hoist.
            boolean conjunctie = s.kwantor() == Conditie.Kwantor.ALLE || s.kwantor() == Conditie.Kwantor.DE;
            // An unresolved sub-condition cannot be faithfully combined. Splicing its always-false marker into
            // one operand is wrong under the NEGATED quantifiers (geen/ten hoogste/precies, which wrap the
            // operands in !(…)): there the dropped criterion flips into a positive firing decision. So mark the
            // WHOLE compound a no-op (rule never fires) rather than risk a silently-wrong gate — for every quantifier.
            boolean wilHoisten = kinderen.stream().anyMatch(LoweredConditie::heeftExtra);
            if (kinderen.stream().anyMatch(LoweredConditie::marker) || (wilHoisten && !conjunctie))
                return LoweredConditie.marker("voorwaarde", s.bron());
            if (conjunctie) {
                // Join ONLY the non-empty inlines (a pure far-side child has an empty inline — dropping it
                // avoids a dangling "()" / " && "); concat every child's hoisted clauses. When every child is a
                // pure far-side membership the combined inline is "" — the subject pattern stays "(  )" and the
                // exists clauses carry the whole gate.
                List<String> inlineDelen = kinderen.stream().map(LoweredConditie::inline)
                        .filter(x -> !x.isEmpty()).map(x -> "(" + x + ")").toList();
                List<String> extra = kinderen.stream().flatMap(k -> k.extra().stream()).toList();
                return new LoweredConditie(String.join(" && ", inlineDelen), extra, false);
            }
            // §13.4.14.4: geen = none holds (!OR); the counting quantifiers express "k of the criteria hold"
            // purely in booleans (a Drools pattern constraint accepts &&/||/! but not arithmetic over ternaries),
            // via the OR of every k-subset (minstens): at-least-N directly, at-most-N as not-at-least-(N+1),
            // exactly-N as the conjunction of the two. No child has extra here (guarded above).
            List<String> delen = kinderen.stream().map(k -> "(" + k.inline() + ")").toList();
            int n = s.drempel();
            return LoweredConditie.van(switch (s.kwantor()) {
                case GEEN        -> "!(" + String.join(" || ", delen) + ")";
                case TEN_MINSTE  -> minstens(delen, n);
                case TEN_HOOGSTE -> "!(" + minstens(delen, n + 1) + ")";
                case PRECIES     -> "(" + minstens(delen, n) + " && !(" + minstens(delen, n + 1) + "))";
                default          -> String.join(" && ", delen); // unreachable (alle/de handled above), kept total
            });
        }
        return LoweredConditie.marker("voorwaarde", c.bron());
    }

    /** An unresolved CONDITION lowers to an always-false constraint, so the rule is a visible no-op
     *  that COMPILES — unlike the value-side {@code … null} marker, a bare {@code null} is not a
     *  boolean pattern constraint ({@code Subject( null )} fails to compile, which would sink the
     *  whole DRL). Wrapped in a flagged {@link LoweredConditie#marker} so callers branch on the type. */
    private static String onopgelosteConstraint(String wat, String bron) {
        return "false /* TODO " + commentVeilig(wat + ": " + bron) + " */";
    }

    /** §8.1.7 far-side "heeft een &lt;rol&gt;" as a separate when-clause: the subject is satisfied when at least
     *  one filler references it ({@code exists <Filler>( <backref> == $subject )}); the "heeft geen" negation is
     *  the {@code not} of that. Bound AFTER the subject pattern so {@code $subject} is in scope. */
    private String verreRolClausule(Conditie.VerreRollidmaatschap vr) {
        String patroon = ident(vr.filler().naam, true) + "( " + ident(vr.backref(), false) + " == " + SUBJECT + " )";
        return (vr.negatie() ? "not " : "exists ") + patroon;
    }

    /** A boolean expression true when AT LEAST {@code k} of {@code delen} hold: the OR over
     *  every k-subset, each subset AND-ed. k&lt;=0 is always true; k&gt;size is never. The
     *  bullet lists are small, so the C(size,k) expansion stays modest. */
    // ---------------- §9.8 dagsoort definitie -> a shared Predicate global ----------------

    /** Resolve (idempotently) the §9.8 definitie of {@code dagsoortNaam} via the SHARED {@link Dagsoorten}
     *  compiler (single-runtime principle: ONE predicate implementation for both back-ends) and declare it as a
     *  {@code global java.util.function.Predicate rsDagsoort_<Naam>;} that a session builder supplies from
     *  {@link #dagsoortGlobalen()}. Returns the global name, or null when no such definitie exists or its
     *  condition is not a pure date predicate (the caller then markers) — the same verdict the exec back-end
     *  reaches from the same compiler, so the two manifests stay aligned. */
    private String dagsoortGlobaal(String dagsoortNaam) {
        java.util.function.Predicate<java.time.LocalDate> pred = dagsoorten.predikaat(dagsoortNaam);
        if (pred == null) return null;
        String naam = "rsDagsoort_" + ident(dagsoortNaam, true);
        dagsoortGlobalen.putIfAbsent(naam, pred);
        return naam;
    }


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
        // §13.3.6/§8.2: a navigated TEMPORAL attribute is a List<Segment> — fall through to operand(), which markers it,
        // rather than emit "reis.loon == null" (a list-nullness test that silently drops the §8.2 whole-period leeg check).
        if (isTemporeelAttribuut(a)) return null;
        String wortel = ident(n.rol(), false), pad = wortel + "." + ident(a.attribuut(), false);
        return p.soort() == Conditie.Predicaatsoort.LEEG
                ? "(" + wortel + " == null || " + pad + " == null)"
                : "(" + wortel + " != null && " + pad + " != null)";
    }

    private Frag operand(Acc acc, Expr e, Objecttype subject) {
        if (e instanceof Expr.DimensieCel c)
            // §3.6 a dimensioned cell on the CONDITION side is deferred to a marker (the value/then side
            // lowers it). A Drools PATTERN constraint cannot host the null-guarded ternary the value form
            // uses (the inline "? :" mis-parses, and a null cell vs a number is a build-breaking type error),
            // and a correct null-safe cell comparison needs a guarded conjunction this single-operand path
            // cannot express. So it markers honestly (-> false voorwaarde): the rule does not fire, never a
            // wrong or uncompilable comparison. Carded.
            return Frag.todo("dimensiecel in voorwaarde", c.bron());
        if (e instanceof Expr.AttribuutRef a) {
            // §3.6: a bare value read of a gedimensioneerd attribuut is under-specified (its property is a
            // Map, so "prijsInJaar > 5" is a Map-vs-number type error that fails the whole DRL build). A
            // legitimate dimensioned read is an Expr.DimensieCel (above); a bare one is a marker.
            if (isGedimensioneerd(a)) return Frag.todo("ongedimensioneerde lezing van gedimensioneerd attribuut (§3.6)", a.bron());
            // §13.3.6/§8.2: a tijdsafhankelijk attribuut is a List<Segment> timeline — a bare when-side comparison
            // operand ("loon == 1000", or the §8.2 "gedurende het gehele jaar gelijk is aan") would leak the List
            // into a scalar constraint and sink the build; marker it (see isTemporeelAttribuut).
            if (isTemporeelAttribuut(a)) return Frag.todo("tijdsafhankelijk attribuut in scalaire voorwaarde (§8.2)", a.bron());
            return attribuutOperand(a, subject);
        }
        if (e instanceof Expr.ParameterRef p)   // §13.3.6 a TEMPORAL parameter is a List<Segment> global — a scalar
            // (condition-side) use is a List-in-scalar mismatch that would sink the whole DRL build; marker instead.
            return p.parameter().granulariteit() != null ? Frag.todo("temporele parameter in scalaire context", p.bron())
                    : Frag.ok(ident(p.parameter().naam(), false));
        if (e instanceof Expr.Literal lit)    return literal(lit);
        if (e instanceof Expr.TekstReeks tr)  return tekstReeks(acc, tr, x -> operand(acc, x, subject));
        if (e instanceof Expr.Afgerond af) {
            // §13.4.17.13/.25: an afronding wraps a getalexpressie — and "de wortel van X" carries a MANDATORY
            // afronding, so a condition-side wortel is always Afgerond(Functie(WORTEL)), never the bare Functie.
            // setScale is a pure method call (no ternary), valid in a pattern constraint, so descend with the
            // operand-side (bare-property) lowering. A marker basis (e.g. an afronding over a Begrensd clamp,
            // which still uses a ternary and markers here) propagates.
            Frag b = operandReken(acc, af.basis(), subject);
            return b.marker() ? b : afgerondeTerm(b, af);
        }
        if (e instanceof Expr.Binair b)
            // §6.2-6.8 arithmetic in a CONDITION: the SAME lowering as the value side (binairTerm), with the
            // sub-operands as bare properties and the +/- unit restatement via herschaalConditie — the EXACT null-safe
            // rsHerschaalExact (×÷ the integer factorNaarCanoniek), so a convertible cross-unit +/- FIRES (only an opaque
            // composite stays a marker). The leegwaarde helpers (rsNul/rsGedeeld/rsVerminderd/rsMacht) are method
            // calls, valid in a constraint.
            return binairTerm(acc, b, x -> operandReken(acc, x, subject), this::herschaalConditie);
        if (e instanceof Expr.Begrensd bg) {
            // §6.1.4 begrenzing in a CONDITION: the value side clamps with an inline ternary "(r < m ? m : r)"
            // that mis-parses in a pattern constraint, so lower through the registered rsBegrens helper (a method
            // call, null-safe, clamping min-then-max sequentially to match the value side's order). The bounds are
            // restated EXACTLY into the basis unit via herschaalConditie (×÷ the integer factorNaarCanoniek, like the
            // +/- and value-side begrenzing paths), so a "min van 24 mnd" clamp on a jr basis compares against an
            // exact 2 jr — it FIRES, not markers (only an OPAQUE composite bound, or a marker basis/bound, propagates).
            fnImport("rsBegrens");
            Datatype bt = bg.basis().type();
            Frag basis = operandReken(acc, bg.basis(), subject);
            Frag min = bg.minimum() != null ? herschaalConditie(operandReken(acc, bg.minimum(), subject), bg.minimum().type(), bt) : Frag.ok("null");
            Frag max = bg.maximum() != null ? herschaalConditie(operandReken(acc, bg.maximum(), subject), bg.maximum().type(), bt) : Frag.ok("null");
            if (basis.marker() || min.marker() || max.marker()) return Frag.todo("begrenzing in voorwaarde", bg.bron());
            return Frag.ok("rsBegrens(" + basis.text() + ", " + min.text() + ", " + max.text() + ")");
        }
        if (e instanceof Expr.Functie f) {
            // §13.4.17 a SCALAR builtin (abs / date-component / datum-met / wortel / paasdag / tijdsduur) is a
            // valid pattern-constraint operand once its arguments lower to bare properties — so reuse the one
            // value-side routine, swapping its arg lowering to operand() (the field name, not a $subject getter,
            // since $subject is not bound inside its own pattern). The AGGREGATIONS (som/min/max over instances)
            // return null there: they need a `when` accumulate and cannot live inside a single constraint, so
            // they fall through to an honest marker (-> the caller's `false /* TODO */`).
            Frag scalar = scalarFunctie(acc, f, x -> operandReken(acc, x, subject));
            if (scalar != null) return scalar;
            return Frag.todo("aggregatie-functie in voorwaarde " + f.kind(), f.bron());
        }
        if (e instanceof Expr.Constante c) {
            Frag rk = rekenConstante(c);   // §5.3 Rekendatum/Rekenjaar as a condition operand
            if (rk != null) return rk;
        }
        return Frag.todo("operand", e.bron());
    }

    /** §5.3 Rekendatum / Rekenjaar as an expression: the §4.2 rekendatum global (a LocalDate the runtime
     *  supplies), respectively its year via the null-guarded rsRekenjaar (an unset rekendatum reads leeg,
     *  the leegwaarde rules then apply). Valid in both a pattern constraint and a consequence — a global is
     *  a free name in either. null for any other constant ("hij", "de dag": handled by their own paths). */
    private Frag rekenConstante(Expr.Constante c) {
        if ("Rekendatum".equals(c.naam())) { rekendatumNodig = true; return Frag.ok("rekendatum"); }
        if ("Rekenjaar".equals(c.naam()))  { rekendatumNodig = true; fnImport("rsRekenjaar"); return Frag.ok("rsRekenjaar(rekendatum)"); }
        return null;
    }

    /** The condition-side property access for an attribute — the bare own-property, a null-safe "!." to-one
     *  navigation, or a marker for an absent/naar-veel/unresolvable read. Shared by the plain operand and the
     *  dimensioned-cell read (which needs the Map property before indexing a label), so the latter bypasses
     *  the §3.6 under-specified guard. Drools "!." yields null (a leegwaarde) for a null to-one reference
     *  instead of NPE-ing the mvel "." dereference. */
    private Frag attribuutOperand(Expr.AttribuutRef a, Objecttype subject) {
        NaviRef n = naviResolve(a, subject);
        return switch (n.soort()) {
            case EIGEN       -> Frag.ok(ident(a.attribuut(), false));
            case GENAVIGEERD -> Frag.ok(ident(n.rol(), false) + "!." + ident(a.attribuut(), false));
            case NAAR_VEEL   -> Frag.todo("naar-veel-navigatie", a.bron());
            default          -> Frag.todo("navigatie", a.bron());   // AFWEZIG / ONOPGELOST
        };
    }

    /** The CONDITION-side lowering of an operand sitting INSIDE arithmetic (a binairTerm / begrenzing / afronding /
     *  scalar-functie leg): identical to {@link #operand} EXCEPT a to-ONE NAVIGATED NUMERIC attribute reads through the
     *  null-safe {@link #registreerRelGetalFunctie rsRelGetal} helper instead of mvel's {@code rol!.attr}. Inside a +/-
     *  the §4.1 Tabel 3 leegwaarde reads an absent operand as 0, but mvel's {@code !.} short-circuits the WHOLE
     *  constraint to a non-match on a null relation — dropping the leeg-as-0 (a latent bug: "de prijs van zijn reis plus
     *  1" with no reis must compute 0+1, not silently fail). rsRelGetal yields a leeg (null) value the surrounding
     *  rsNul/rsVerminderd leegwaarde handling then applies, mirroring the exec's navReader. A non-numeric (datum/tekst)
     *  navigated leg, a non-navigated leaf, or a nested expression falls through to {@link #operand} unchanged — leeg-as-0
     *  is a numeric rule, and nested arithmetic re-enters here because operand's arithmetic cases lower through operandReken. */
    private Frag operandReken(Acc acc, Expr e, Objecttype subject) {
        if (e instanceof Expr.AttribuutRef a && a.navigatieRol() != null && a.type() != null && a.type().isNumeriek()
                && !isGedimensioneerd(a) && !isTemporeelAttribuut(a)) {
            NaviRef n = naviResolve(a, subject);
            if (n.soort() == NaviSoort.GENAVIGEERD) {
                fnImport("rsRelGetal");
                return Frag.ok("rsRelGetal(" + ident(n.rol(), false) + ", \"" + a.attribuut() + "\")");
            }
        }
        return operand(acc, e, subject);
    }

    // ---------------- leaves & helpers ----------------
    /** Whether an expression tree can ACTUALLY introduce an exact {@link io.github.yhavinga.regelspraak.runtime.Breuk} — i.e. it
     *  contains a §389 division (GEDEELD_DOOR, whose non-terminating quotient becomes a Breuk), a §486 PERCENTAGE_VAN,
     *  or a NON-terminating rational literal. A plain maal/plus/min of decimals never yields a Breuk, so it is NOT a
     *  source. Used to route a COMPARISON through the Breuk-aware rsAggVergelijk only when a Breuk can genuinely reach
     *  it (else the ordinary inline mvel comparison stays — no churn, no over-wrap). Recurses through the operands. */
    static boolean bevatBreukBron(Expr e) {
        if (e == null) return false;
        if (e instanceof Expr.Binair b) {
            if (b.op() == Expr.BinOp.GEDEELD_DOOR || b.op() == Expr.BinOp.PERCENTAGE_VAN) return true;
            return bevatBreukBron(b.links()) || bevatBreukBron(b.rechts());
        }
        if (e instanceof Expr.Begrensd bg) return bevatBreukBron(bg.basis()) || bevatBreukBron(bg.minimum()) || bevatBreukBron(bg.maximum());
        if (e instanceof Expr.Afgerond) return false;   // an afronding collapses any Breuk to a BigDecimal
        if (e instanceof Expr.Functie f) { for (Expr a : f.argumenten()) if (bevatBreukBron(a)) return true; return false; }
        if (e instanceof Expr.Literal lit && lit.type() != null && lit.type().isNumeriek()) {
            java.math.BigDecimal[] r = Rationaal.van(lit.waarde());
            if (r == null) return false;
            try { r[0].divide(r[1]); return false; } catch (ArithmeticException nietEindig) { return true; }
        }
        return false;
    }

    /** Whether a value expression could compute to an exact {@link io.github.yhavinga.regelspraak.runtime.Breuk} — the trigger
     *  for the §6.1.2 rsWaarde write-boundary collapse. True for any arithmetic (a §389/§486 division/percentage/
     *  maal/plus/min whose exactness can propagate), a begrenzing/afronding wrapping such arithmetic, and a
     *  NON-TERMINATING rational LITERAL (1/3 → a Breuk). A bare attribute/parameter read, a terminating literal, a
     *  date/text/boolean, or an aggregation is never a Breuk, so it stays unwrapped (no pin churn). Conservative:
     *  when in doubt (a compound), it wraps — the wrap is an identity for a BigDecimal. */
    private static boolean kanBreukOpleveren(Expr e) {
        // Only a NUMERIC-typed result can be a Breuk. A DATUM-arithmetic Binair (a date ± a duration) or a
        // date/text builtin yields a KNOWN non-numeric value that rsWaarde (→ BigDecimal) would wrongly cast, so it
        // must NOT be wrapped — reject a known non-numeric result type. An Onbekend type (a beslistabel-desugared
        // arithmetic value carries one) falls through to the structural check: a Binair there is arithmetic (numeric),
        // and rsWaarde is a safe identity for its BigDecimal / Breuk result.
        if (e.type() != null && !e.type().isGetalachtig() && !e.type().isOnbekend()) return false;
        if (e instanceof Expr.Binair || e instanceof Expr.Begrensd || e instanceof Expr.Afgerond) return true;
        // §6.9 "de absolute waarde van" preserves a Breuk (rsAbs returns Object); other builtins (date-component,
        // datum-met, aggregation) return a BigDecimal/date, so they need no collapse and stay unwrapped.
        if (e instanceof Expr.Functie f && f.kind() == Expr.FunctieKind.ABSOLUTE_WAARDE) return true;
        if (e instanceof Expr.Literal lit && lit.type().isNumeriek()) {
            java.math.BigDecimal[] r = Rationaal.van(lit.waarde());
            if (r == null) return false;
            try { r[0].divide(r[1]); return false; }              // terminating -> a plain BigDecimal
            catch (ArithmeticException nietEindig) { return true; } // non-terminating -> a Breuk
        }
        return false;
    }

    /** The integer exponent when {@code e} is an integer-valued numeric literal ("2" in "tot de
     *  macht 2"), else null — lets an integer power lower exactly via {@code BigDecimal.pow(int)}
     *  rather than through Math.pow's double. Reads the number as {@link #literal} does, so a
     *  rational that reduces to a whole number ("4/2" -> 2) is an exact exponent too, while a true
     *  fraction ("3/2") or non-terminating quotient ("1/3") falls through to the Math.pow path. */
    private static Integer geheelExponent(Expr e) {
        if (!(e instanceof Expr.Literal lit) || !lit.type().isNumeriek()) return null;
        java.math.BigDecimal[] r = Rationaal.van(lit.waarde());
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

    /** Render a literal as DRL text — the VALUE comes from the shared {@link Literalen} reader (single shared implementation:
     *  one interpretation for both back-ends), this method only formats it for mvel. */
    private Frag literal(Expr.Literal lit) {
        Datatype t = lit.type();
        // §13.3.3.4 Tekst / §13.2.14 enumeratiewaarde 'X' (typed Onbekend at parse): both compare by VALUE
        // equality on the label (§8.1.1), so render the label as a double-quoted string — embedded quotes
        // ESCAPED, never stripped (escaping by construction), so the constraint compares against the REAL label.
        String label = Literalen.tekstOfEnumLabel(lit);
        if (label != null) return Frag.ok(drlString(label));
        if (t instanceof Datatype.Booleaan) return Frag.ok(Literalen.waar(lit.waarde()) ? "true" : "false");
        if (t.isDatum()) { // "dd. d-m-yyyy [hh:mm:ss.mmm]" — the shared readers parse; the components splice as
            // DECIMAL ints (a raw "08"/"09" would be an invalid octal literal in mvel/Java, §13.2.19).
            if (t instanceof Datatype.DatumTijd) {
                java.time.LocalDateTime dt = Literalen.parseDatumTijd(lit.waarde());
                if (dt != null)
                    return Frag.ok("java.time.LocalDateTime.of(" + dt.getYear() + ", " + dt.getMonthValue() + ", "
                            + dt.getDayOfMonth() + ", " + dt.getHour() + ", " + dt.getMinute() + ", "
                            + dt.getSecond() + ", " + dt.getNano() + ")");
            }
            java.time.LocalDate d = Literalen.parseDatum(lit.waarde());
            if (d == null) return Frag.todo("datumliteral", lit.waarde());
            return Frag.ok("java.time.LocalDate.of(" + d.getYear() + ", " + d.getMonthValue() + ", " + d.getDayOfMonth() + ")");
        }
        // numeric (possibly "0 €"), incl. a §13.2.5 rational ("4/2", "1_1/2", "1/3"). A terminating quotient
        // ("4/2"->2, "3/2"->1.5) splices as an exact BigDecimal constant (the fast path). A NON-terminating
        // rational ("1/3") lowers to an EXACT Breuk (rsBreuk("1","3")) — §389 — so its arithmetic stays exact
        // instead of the former 16-digit DECIMAL64 approximation (9 maal 1/3 = 3, not 2.9999999999999997).
        java.math.BigDecimal[] r = Rationaal.van(lit.waarde());
        if (r != null) {
            try {
                java.math.BigDecimal exact = r[0].divide(r[1]);   // throws when non-terminating
                return Frag.ok("new java.math.BigDecimal(\"" + exact.toPlainString() + "\")");
            } catch (ArithmeticException nietEindig) {
                // reduce to lowest terms so the emitted teller/noemer are the canonical fraction
                io.github.yhavinga.regelspraak.runtime.Breuk b = io.github.yhavinga.regelspraak.runtime.Breuk.van(r[0]).delen(io.github.yhavinga.regelspraak.runtime.Breuk.van(r[1]));
                fnImport("rsBreuk");
                return Frag.ok("rsBreuk(\"" + b.teller() + "\", \"" + b.noemer() + "\")");
            }
        }
        java.math.BigDecimal n = Literalen.getal(lit);
        if (n == null) return Frag.todo("literal", lit.waarde());
        return Frag.ok("new java.math.BigDecimal(\"" + n.toPlainString() + "\")");
    }

    /** §5.4/§13.4.17.9 reeks van teksten en waarden: an mvel concatenation of the literal chunks (quoted) and the
     *  «expressie» parts, each coerced to Tekst by the null-safe rsTekst helper (a leeg value -> "", never the string
     *  "null"). {@code arg} lowers a sub-expression — {@link #waarde} on the value side, {@link #operand} on the
     *  condition side. A markered sub-expression propagates (the Frag marker flag), so an unlowerable «…» part makes
     *  the whole tekstreeks an honest marker. */
    private Frag tekstReeks(Acc acc, Expr.TekstReeks tr, Function<Expr, Frag> arg) {
        fnImport("rsTekst");
        java.util.List<String> delen = new java.util.ArrayList<>();
        boolean marker = false;
        for (Expr.TekstReeks.Deel d : tr.delen()) {
            if (d instanceof Expr.TekstReeks.Letterlijk lit)
                delen.add("\"" + lit.tekst().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            else if (d instanceof Expr.TekstReeks.Ingevoegd ing) {
                Frag f = arg.apply(ing.expr());
                delen.add("rsTekst(" + f.text() + ")");
                marker |= f.marker();
            }
        }
        return new Frag("(" + String.join(" + ", delen) + ")", marker);
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
    private String[] vergelijkingsOperanden(String l, Datatype lt, String r, Datatype rt) {
        io.github.yhavinga.regelspraak.model.Eenheid el = lt.eenheidOf(), er = rt.eenheidOf();
        if (el == null || er == null || !el.convertibleNaar(er) || !el.vereistOmrekening(er))
            return new String[]{l, r};
        if (el.noemer() == null && er.noemer() == null)
            return new String[]{naarCanoniek(l, el), naarCanoniek(r, er)};
        return new String[]{l, omreken(r, rt, lt)};   // composite (rate) unit: keep the existing conversion
    }

    /** Multiply {@code expr} by a unit-conversion {@code factor} — the one place the ×factor restatement is
     *  emitted, as a call to the null-safe runtime helper (a leeg operand restates to leeg — never an NPE-abort;
     *  a Drools pattern constraint could not host an inline {@code == null} ternary anyway, and the SAME call
     *  form is valid on the value side, so both sides share the ONE {@code rsHerschaal} implementation).
     *  Identity when there is no real conversion (a {@code null} or unit (1) factor). The callers
     *  (omrekenWaarde / herschaalConditie / naarCanoniek) guard a placeholder {@link Frag} BEFORE calling. */
    private String vermenigvuldigMetFactor(String expr, java.math.BigDecimal factor) {
        if (factor == null || factor.compareTo(java.math.BigDecimal.ONE) == 0) return expr;
        String f = "new java.math.BigDecimal(\"" + factor.toPlainString() + "\")";
        return fnImport("rsHerschaal") + "(" + expr + ", " + f + ")";
    }

    /** EXACT §4 restatement of a value in {@code van} into {@code naar} for a +/- operand whose units are SIMPLE and
     *  convertible — multiply by the source unit's integer factorNaarCanoniek and DIVIDE by the target's (one DECIMAL64
     *  rounding, AT THE END), NOT a multiply by the pre-rounded ratio (which truncates mnd→jr = 1/12 = 0.0833…, so
     *  "0 jr + 24 mnd" read 1.9999… ≠ 2). Mirrors the §7.3.1 rsTijdSchaal exact-÷ and the comparison path's naarCanoniek.
     *  A COMPOSITE (rate) unit keeps the single-ratio path (its precision edge is separately carded); no conversion is
     *  the identity. One null-safe form for BOTH sides: the imported {@code rsHerschaalExact} runtime helper. */
    private String herschaalExact(String expr, Datatype van, Datatype naar) {
        io.github.yhavinga.regelspraak.model.Eenheid ev = van.eenheidOf(), en = naar.eenheidOf();
        if (ev == null || en == null || !ev.convertibleNaar(en) || !ev.vereistOmrekening(en)) return expr;
        java.math.BigDecimal[] f = ev.exacteHerschaalFactoren(en);
        if (f == null)   // an opaque product unit (no canonical) / unknown factor: keep the single (lossy) ratio
            return vermenigvuldigMetFactor(expr, ev.omrekenfactorNaar(en));
        String fv = "new java.math.BigDecimal(\"" + f[0].toPlainString() + "\")";
        String fn = "new java.math.BigDecimal(\"" + f[1].toPlainString() + "\")";
        return fnImport("rsHerschaalExact") + "(" + expr + ", " + fv + ", " + fn + ")";
    }

    /** The §4 conversion factor to restate a {@code van}-typed quantity in the {@code naar} unit, or {@code
     *  null} when no conversion applies (either side unitless, not convertible, or already the same unit). */
    private static java.math.BigDecimal omrekenFactor(Datatype van, Datatype naar) {
        io.github.yhavinga.regelspraak.model.Eenheid ev = van.eenheidOf(), en = naar.eenheidOf();
        if (ev == null || en == null || !ev.convertibleNaar(en) || !ev.vereistOmrekening(en)) return null;
        return ev.omrekenfactorNaar(en);
    }

    /** Restate a comparison operand in its system's canonical unit by the DECLARED (exact) factor —
     *  a multiplication, never the lossy division omreken uses. Identity for a canonical/unitless
     *  operand or a placeholder marker. */
    private String naarCanoniek(String expr, io.github.yhavinga.regelspraak.model.Eenheid e) {
        return vermenigvuldigMetFactor(expr, e.factorNaarCanoniek());
    }

    /** Unit conversion for a CONSTRAINT operand — null-safe via the rsHerschaal helper ({@link #vermenigvuldigMetFactor}). */
    private String omreken(String expr, Datatype van, Datatype naar) {
        return vermenigvuldigMetFactor(expr, omrekenFactor(van, naar));
    }

    /** Unit conversion on the VALUE side, null-safe: a leegwaarde (an empty sommatie has no fallback) converts to a
     *  leegwaarde rather than an NPE on .multiply. EXACT for SIMPLE convertible units (×source.factorNaarCanoniek
     *  ÷target.factorNaarCanoniek, one DECIMAL64 rounding — {@link #herschaalExact}), so an assignment / begrenzing-bound /
     *  functie-arg / +/- restatement no longer truncates the non-terminating ratio mnd→jr = 1/12 (24 mnd stored in a jr
     *  attribute = 2, not 1.9999…). A COMPOSITE (rate) keeps the single ratio (carded). Only the "then"/value side — the
     *  constraint form is {@link #omreken} (lossy) / {@link #herschaalConditiePlus} (exact, +/-). */
    private Frag omrekenWaarde(Frag expr, Datatype van, Datatype naar) {
        return expr.marker() ? expr : Frag.ok(herschaalExact(expr.text(), van, naar));
    }

    /** §4 EXACT restatement of a value in {@code van} into {@code naar} on the CONSTRAINT side, shared by a +/- RIGHT
     *  operand AND a begrenzing min/max bound: a SIMPLE convertible cross-unit restate now FIRES via the EXACT
     *  rsHerschaalExact (no more lossy-ratio truncation), so "0 jr + 24 mnd == 2 jr" decides 24 == 24 and a "min van 24
     *  mnd" clamp compares against an exact 2 jr. A COMPOSITE (rate) cross-unit is exact too (exacteHerschaalFactoren
     *  folds the inverted denominator); only an OPAQUE product unit (no canonical factors) stays an honest marker; no
     *  conversion is the identity. The null-safe mirror of {@link #omrekenWaarde} (value side). */
    private Frag herschaalConditie(Frag expr, Datatype van, Datatype naar) {
        if (expr.marker()) return expr;
        io.github.yhavinga.regelspraak.model.Eenheid ev = van.eenheidOf(), en = naar.eenheidOf();
        if (ev == null || en == null || !ev.convertibleNaar(en) || !ev.vereistOmrekening(en)) return expr;   // no conversion -> identity
        if (ev.exacteHerschaalFactoren(en) == null)   // an opaque product unit (no exact factors) -> honest marker
            return Frag.todo("eenheidconversie in een rekenkundige voorwaarde (niet-herschaalbare samengestelde eenheid)", expr.text());
        return Frag.ok(herschaalExact(expr.text(), van, naar));
    }

    /** §13.4.17.25 afronding: round a basis term to {@code af.decimalen()} via the matching
     *  {@link java.math.RoundingMode}, through the rsAfronding helper so a leeg basis rounds to leeg rather
     *  than NPE-ing on (null).setScale. That null-guard is load-bearing: wortel and macht carry a MANDATORY
     *  afronding (§13.4.17.13/.14), and §6.6/§6.7 make their leeg result leeg — which can only propagate if the
     *  afronding wrapper passes a null through. The helper (not an inline ternary) is also the form valid in a
     *  pattern constraint; shared by {@code waarde} and {@code operand} so an afronding lowers identically in
     *  either position. */
    private Frag afgerondeTerm(Frag basis, Expr.Afgerond af) {
        String mode = switch (af.modus()) {
            case NAAR_BENEDEN -> "FLOOR"; case NAAR_BOVEN -> "CEILING";
            case REKENKUNDIG -> "HALF_UP"; case RICHTING_NUL -> "DOWN"; case WEG_VAN_NUL -> "UP";
        };
        fnImport("rsAfronding");
        return Frag.from("rsAfronding(" + basis.text() + ", " + af.decimalen() + ", java.math.RoundingMode." + mode + ")", basis);
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

    private static String javaType(Datatype t) {
        if (t.isPercentage() || t.isNumeriek()) return "java.math.BigDecimal";
        if (t.isTekst() || t instanceof Datatype.Enumeratie) return "String";   // an enumeratiewaarde is stored as its String label
        if (t instanceof Datatype.Booleaan) return "Boolean";
        if (t instanceof Datatype.DatumInDagen) return "java.time.LocalDate";
        if (t instanceof Datatype.DatumTijd) return "java.time.LocalDateTime";
        return "Object";
    }

    private static String todo(String wat, String bron) { return "/* TODO " + commentVeilig(wat + ": " + bron) + " */ null"; }

}
