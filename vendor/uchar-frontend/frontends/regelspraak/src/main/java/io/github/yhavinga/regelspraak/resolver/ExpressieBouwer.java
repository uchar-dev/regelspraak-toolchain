package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Bron;
import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;
import io.github.yhavinga.regelspraak.kern.Literalen;
import io.github.yhavinga.regelspraak.kern.Tijdrekening;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the resolved, typed {@link Expr} IR from a parsed §13.4.17 expressie,
 * binding references against the model and computing result datatypes via the
 * {@link Typering} matrix.
 *
 * The node datatypes it produces are, by construction, exactly the datatypes the
 * checker's earlier inline inference produced — so type-checking diagnostics, and
 * the resolver's "unknown ⇒ no claim" guarantee, are unchanged by the move to an
 * AST. What the AST adds is structure: arithmetic, aggregations and bounds are now
 * first-class nodes, and attribute/parameter references are bound, giving the
 * transpiler something to walk instead of a bare datatype. Aggregation result typing,
 * once deliberately left at Onbekend, has since landed in {@code functieType} — SOM,
 * TOTAAL (with the §4.20 rate strip), MIN/MAX, AANTAL and the date functions all carry a
 * computed type; the kinds with no scalar meaning keep Onbekend on purpose.
 */
final class ExpressieBouwer {
    private final RegelSpraakModel model;
    // The object type the current rule is about, so a possessive "zijn <attribuut>"
    // (and "hij") resolve against the rule subject. Set per rule by the checker.
    private Objecttype huidigSubject;
    // §11 the current rule's "Daarbij geldt:" variabelen, keyed by normalized name -> the resolved definition Expr,
    // INLINED at each read site (naamRef). Per-rule; cleared and re-set by zetVariabelen each rule. This is the
    // form a VOORWAARDE read takes — see zetVariabelen for why the two forms coexist.
    private final Map<String, Expr> variabelen = new LinkedHashMap<>();
    // §11 the SAME variabelen in BINDER form, in declaration order (§11.1 rule 1): the value a ch. 4.10 Let node
    // carries, in which a read of an EARLIER variable is a BinderRef rather than that variable's inlined subtree.
    private final Map<String, Variabele> binders = new LinkedHashMap<>();
    // Which of the two forms naamRef hands back. false (the default) inlines — the VOORWAARDE route; true yields a
    // BinderRef — the RESULTAAT route, under the Let chain omhulMetVariabelen builds. Scoped by bouwResultaat,
    // and turned back OFF inside a structure-sensitive position by ingelijnd().
    private boolean binderModus;
    // The inlined definitions naamRef may substitute RIGHT NOW. It is `variabelen` itself everywhere a rule's own
    // text is built (the voorwaarde and the resultaat see every declaration, as they did in v0), and a growing
    // PREFIX while pass 2 rebuilds the definitions themselves — so a structure-sensitive position INSIDE a
    // definition sees exactly what pass 1 saw there: earlier declarations only, a forward reference still unbound.
    private Map<String, Expr> zichtbaar = variabelen;

    /** One §13.4.2.10 declared variable in BINDER form: its lookup {@code sleutel}, the {@code naam} the Let binder
     *  and every BinderRef carry (the written name minus a leading bepaald lidwoord), the resolved value, and the
     *  declaration's source anchor. */
    record Variabele(String sleutel, String naam, Expr waarde, Bron bron) {}

    ExpressieBouwer(RegelSpraakModel model) { this.model = model; }

    void setSubject(Objecttype subject) { this.huidigSubject = subject; }
    Objecttype subject() { return huidigSubject; }

    /** §5.6: the die/dat subselecties this builder CONSUMED — by identity, over the whole document.
     *  Consuming means the predicaat reached a resolution that accounts for it: a bound filter, or a
     *  refusal that names it. Everything the parse produced and this set does not hold was DROPPED, and
     *  {@link RuleChecker} makes that loud rather than letting the document answer about a set nobody
     *  wrote. */
    private final java.util.Set<PredicaatContext> verbruikt =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    java.util.Set<PredicaatContext> verbruikteDeelselecties() { return verbruikt; }

    /** §13.4.13.4 resolves a COMPOUND subselectie predicaat ("die aan alle volgende voorwaarden voldoen: …")
     *  against a given AGGREGATED ELEMENT type, producing a {@link Conditie.Samengesteld} whose bullets are bound
     *  to that element (not the rule subject). Implemented by {@link RuleChecker} (which owns the bullet machinery +
     *  the bouwer subject); null until set, so the compound stays an honest marker if unwired. */
    @FunctionalInterface
    interface SamengesteldeFilterResolver { Conditie resolve(SamengesteldPredicaatContext sp, Objecttype elem); }
    private SamengesteldeFilterResolver samengesteldeFilter;
    void setSamengesteldeFilter(SamengesteldeFilterResolver r) { this.samengesteldeFilter = r; }

    /**
     * §11 capture the "Daarbij geldt: [lidwoord] X is &lt;expressie&gt;" variabelendeel of the current rule, in BOTH
     * forms the wire needs. CLEARS first (so a previous rule's variables never leak — call it for EVERY rule, even
     * one with no variabeleDeel: a null ctx just clears). Subject-relative "zijn X" inside a definition resolves
     * against the subject, so {@link #setSubject} must be called BEFORE this.
     *
     * <p><b>Why two forms.</b> A rule's voorwaarde and its waarde are two SEPARATE wire fields
     * ({@code ResolvedRegel.voorwaarde} is a {@link Conditie}, {@code .waarde} an {@link Expr}), and a ch. 4.10
     * {@code Let} is an Expr node — so no Let can span them. A variable read in the RESULT part therefore becomes a
     * {@link Expr.BinderRef} under the Let chain {@link #omhulMetVariabelen} builds (sharing survives: two reads,
     * one Let); a variable read in the VOORWAARDE keeps the v0 route and is SUBSTITUTED at its use site. What that
     * costs is SHARING: a variable read twice inside one condition is substituted twice and so evaluated twice,
     * where one Let would evaluate it once (ch. 10.6 clause 1). No case observes the difference — the values agree
     * — but they are not identical in theory: a definition whose evaluation deposits a §9.5 channel reason would
     * deposit one PER READ. The leeg-barrier a Let would raise at the read place is no loss either way: it
     * coincides with the §5 leeg-operand discipline the comparison already applies.
     *
     * <p><b>Pass 1</b> resolves each definition in declaration order (§11.1 rule 1) with earlier variables INLINED
     * — byte-for-byte the v0 behaviour, and the map {@link #naamRef} reads on the voorwaarde route. <b>Pass 2</b>
     * re-resolves the SAME sources with earlier variables as BinderRefs. Two passes rather than one plus a
     * substitution rewrite: the second pass reuses the whole resolution machinery instead of duplicating it, and a
     * forward/cyclic reference falls through identically in both because {@link #binders} fills incrementally.
     */
    void zetVariabelen(VariabeleDeelContext ctx) {
        variabelen.clear();
        binders.clear();
        if (ctx == null) return;
        int voorInline = model.diagnostics.size();
        for (VariabeleOnderdeelContext vo : ctx.variabeleOnderdeel()) {
            if (vo.variabeleNaam() == null || vo.expressie() == null) continue;
            variabelen.put(variabeleSleutel(src(vo.variabeleNaam())), bouw(vo.expressie()));
        }
        int naInline = model.diagnostics.size();
        boolean vorige = binderModus;
        Map<String, Expr> vorigZichtbaar = zichtbaar;
        // Pass 2 sees the same growing PREFIX of inlined definitions pass 1 saw, so a structure-sensitive position
        // inside definition i substitutes exactly the earlier definitions (never a forward one, which stays unbound
        // in both passes) — the two passes then differ ONLY in what naamRef hands back, never in what it can see.
        Map<String, Expr> prefix = new LinkedHashMap<>();
        binderModus = true;
        zichtbaar = prefix;
        try {
            for (VariabeleOnderdeelContext vo : ctx.variabeleOnderdeel()) {
                if (vo.variabeleNaam() == null || vo.expressie() == null) continue;
                String geschreven = src(vo.variabeleNaam());
                String sleutel = variabeleSleutel(geschreven);
                binders.put(sleutel, new Variabele(sleutel, binderNaam(geschreven), bouw(vo.expressie()), bron(vo)));
                prefix.put(sleutel, variabelen.get(sleutel));
            }
        } finally {
            binderModus = vorige;
            zichtbaar = vorigZichtbaar;
        }
        // Pass 2 re-resolves the same source text, so a diagnostic it repeats VERBATIM is pass 1's own — report it
        // once. A diagnostic pass 2 raises that pass 1 did not is a REAL difference (a BinderRef is opaque where
        // the inlined subtree was transparent) and is KEPT, never swallowed.
        List<RegelSpraakModel.Diagnostic> inlineMeldingen = new ArrayList<>(model.diagnostics.subList(voorInline, naInline));
        for (int i = model.diagnostics.size() - 1; i >= naInline; i--)
            if (inlineMeldingen.contains(model.diagnostics.get(i))) model.diagnostics.remove(i);
    }

    /**
     * §11 wrap a rule's RESULT-part value in the Let chain its variabelendeel denotes: one ch. 4.10 {@link Expr.Let}
     * per declared variable, nested in DECLARATION order (§11.1 rule 1 — the first-declared variable is the
     * OUTERMOST Let, so a later definition's BinderRef to an earlier one is in scope), each carrying that variable's
     * binder-form value and typed with its body (WF-076).
     *
     * <p><b>Full lowering, no dead Lets</b> (maintainer decision 5, as reconciled with the wire): a variable the
     * result reads is ALWAYS a Let — at one read as much as at five, so nothing is inlined away and sharing is
     * visible. A variable NO read reaches — the voorwaarde-only variable (substituted there instead) and the
     * declared-but-unused one — gets none: under ch. 10.6 an unread binder evaluates nothing, so such a Let is a
     * guaranteed no-op that would only pollute the wire. The test is TRANSITIVE by construction: the chain is built
     * inside-out, so a variable read only by a LATER variable's value is still wrapped, because that later Let is
     * already part of the body when this one is considered.
     */
    Expr omhulMetVariabelen(Expr body) {
        if (body == null || binders.isEmpty()) return body;
        List<Variabele> vs = new ArrayList<>(binders.values());
        Expr uit = body;
        for (int i = vs.size() - 1; i >= 0; i--) {
            Variabele v = vs.get(i);
            if (!leestBinder(uit, v.sleutel())) continue;
            uit = new Expr.Let(v.naam(), v.waarde(), uit, uit.type(), v.bron());
        }
        return uit;
    }

    /** §11/§13.4.2 build a rule's RESULT-part value: the expressie resolved with every variable read as a
     *  {@link Expr.BinderRef}, wrapped in {@link #omhulMetVariabelen}'s Let chain. A rule with no variabelendeel
     *  gets back exactly what {@link #bouw} produces — the binder modus is inert without declared variables. */
    Expr bouwResultaat(ExpressieContext e) {
        boolean vorige = binderModus;
        binderModus = true;
        try {
            return omhulMetVariabelen(bouw(e));
        } finally {
            binderModus = vorige;
        }
    }

    /**
     * §11/ch. 4.10 build {@code f} as a SHAPE-READING CONSTRUCT: one where the resolver decides on the SHAPE of the
     * resolved subtree, not on its value. A variable read there is SUBSTITUTED (the v0 route), never handed back
     * as a {@link Expr.BinderRef} — a BinderRef is an opaque leaf, so every {@code instanceof Expr.AttribuutRef} /
     * {@code Literal} / {@code ParameterRef} test would fail and the construct would silently degrade to an
     * Onopgelost marker (a wrong answer, not a lost optimisation). This is the THIRD lowering boundary, beside the
     * voorwaarde-only variable and the never-read one; it is normative in {@code IR-SPEC.md} ch. 4.10.
     *
     * <p>The boundary is the WHOLE SUBTREE, not the shape-reading positions inside it: the scope stays off until
     * the construct is built, so a pure VALUE position underneath — a §5.8.2 leegwaarde fallback, say — is
     * substituted too, though it decides nothing about shape. One scope, no half-inlined subtree. The consequence
     * is normative: inside an aggregation subtree the v0 inlining-coalesce still governs, so the ch. 10.6 tracked
     * delta 1 (the leeg-barrier on the variabelendeel) speaks only about reads OUTSIDE such a subtree.
     */
    private <T> T ingelijnd(java.util.function.Supplier<T> f) {
        boolean vorige = binderModus;
        binderModus = false;
        try { return f.get(); } finally { binderModus = vorige; }
    }

    /** The functie kinds that range over a SET or a TIMELINE and therefore read their operand's shape: the eight
     *  instance/dimension aggregations, plus the two §7 temporal operators whose operand must be a single-period
     *  temporal attribuut ({@code TIJDSEVENREDIG_DEEL}) or whose "dat …" condition clips one
     *  ({@code AANTAL_DAGEN_IN}). {@link #ingelijnd} covers exactly these. */
    private static boolean aggregeert(Expr.FunctieKind kind) {
        return switch (kind) {
            case AANTAL, SOM, TOTAAL, MINIMUM, MAXIMUM, EERSTE, LAATSTE, AANTAL_GEVULDE,
                 TIJDSEVENREDIG_DEEL, AANTAL_DAGEN_IN -> true;
            default -> false;
        };
    }

    /** Whether {@code e} reads the binder keyed {@code sleutel} anywhere — including inside a nested Let's value,
     *  which {@link Expr#bevat} descends into. Variable names are unique per rule (both maps are keyed on the
     *  normalized name), so no inner binder can shadow an outer one here. */
    private static boolean leestBinder(Expr e, String sleutel) {
        return Expr.bevat(e, x -> x instanceof Expr.BinderRef b && sleutel.equals(variabeleSleutel(b.naam())));
    }

    /** Normalize a variabele name to its lookup key: strip a leading bepaald lidwoord (§11 "Mogelijkheid van
     *  lidwoord bij naam variabele"), lowercase, collapse whitespace — so "het Lage tarief" and "lage  tarief"
     *  map to the same variable. Mirrors {@link #naamRef}'s parameter-name strip. */
    private static String variabeleSleutel(String naam) {
        return naam.replaceFirst("^(?i)(de|het|een)\\s+", "").trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /** The name a Let binder and its BinderRefs carry on the wire: the DECLARED spelling minus a leading bepaald
     *  lidwoord (§11), case preserved — "het Lage tarief" binds "Lage tarief". Distinct from {@link
     *  #variabeleSleutel}, which lowercases for lookup only; the wire keeps what the author wrote. */
    private static String binderNaam(String naam) {
        return naam.replaceFirst("^(?i)(de|het|een)\\s+", "").trim().replaceAll("\\s+", " ");
    }

    // ---------------- §13.4.16/.17 expressie ----------------
    Expr bouw(ExpressieContext e) {
        if (e == null) return onopgelost(e,
                "er is geen expressie om te bouwen — de ontleding leverde op deze plaats geen boom op");
        Bron bron = bron(e);
        if (e instanceof PrimaireExprContext p) return bouwPrimair(p.primaireExpressie());
        if (e instanceof SomExprContext s) {
            Expr.BinOp op = s.PLUS() != null ? Expr.BinOp.PLUS
                    : s.MIN() != null ? Expr.BinOp.MIN : Expr.BinOp.VERMINDERD_MET;
            return binair(op, s.expressie(0), s.expressie(1), bron, s.getStart().getLine());
        }
        if (e instanceof ProductExprContext s) {
            Expr.BinOp op = s.MAAL() != null ? Expr.BinOp.MAAL
                    : s.GEDEELD_DOOR_ABS() != null ? Expr.BinOp.GEDEELD_DOOR_ABS : Expr.BinOp.GEDEELD_DOOR;
            return binair(op, s.expressie(0), s.expressie(1), bron, s.getStart().getLine());
        }
        if (e instanceof MachtExprContext s) {
            // §13.4.17.14: "X tot de macht Y <afronding>" — the exponent Y is a primaireExpressie
            // and the mandatory afronding rounds the POWER (it is a sibling of Y, not inside it).
            Expr basis = binairExpr(Expr.BinOp.TOT_DE_MACHT, bouw(s.expressie()), bouwPrimair(s.primaireExpressie()), bron, s.getStart().getLine());
            return afgerond(basis, s.afronding(), bron);
        }
        if (e instanceof PercentageVanExprContext s) {
            // §4.8 Tabel 18: "X% van Y" carries Y's UNIT. But §4.8 Precisie makes the result an
            // exact fraction with "geen decimale precisie" of its own (the §4.5 gedeeld door rule),
            // so do NOT propagate Y's declared decimal count: "50% van prijs (2 dec)" is an exact
            // whole or fraction, and asserting 2 decimals would trip the §6.1.2 assignment check
            // against a lower-precision target. Keep the unit, drop the decimal claim.
            Expr links = bouw(s.expressie(0)), rechts = bouw(s.expressie(1));
            Datatype t = rechts.type() instanceof Datatype.Numeriek n
                    ? new Datatype.Numeriek(false, 0, Datatype.Teken.ONBEPAALD, n.eenheid()) : onbekend();
            return new Expr.Binair(Expr.BinOp.PERCENTAGE_VAN, links, rechts, t, bron);
        }
        if (e instanceof AfgerondeExprContext s) return afgerond(bouw(s.expressie()), s.afronding(), bron);
        if (e instanceof BegrensdeExprContext s) {
            Expr basis = bouw(s.expressie());
            Expr[] mm = grenzen(s.begrenzing());
            // §6.1.4: each bound must be order-comparable with the bounded value (same numeric
            // family, unit-convertible) — else the clamp is meaningless and lowers to invalid DRL.
            if (mm[0] != null) meldBegrenzing(basis.type(), mm[0].type(), s);
            if (mm[1] != null) meldBegrenzing(basis.type(), mm[1].type(), s);
            return new Expr.Begrensd(basis, mm[0], mm[1], bron);
        }
        // GEEN GETUIGE: expressie heeft zeven gelabelde takken en alle zeven staan hierboven, dus geen
        // enkele ontleding — ook geen herstelde — bereikt deze regel. Gemeten 2026-09-19.
        return onopgelost(e,
                "deze expressievorm wordt niet naar IR omgezet — zij is geen som, product, macht, percentage, "
                + "afronding, begrenzing of primaire expressie (§13.4.16/.17)");
    }

    /** Arithmetic node whose result datatype is taken from the typeringen matrix
     *  (a type error there yields Onbekend, matching the checker's no-claim path). */
    private Expr binair(Expr.BinOp op, ExpressieContext l, ExpressieContext r, Bron bron, int line) {
        return binairExpr(op, bouw(l), bouw(r), bron, line);
    }

    /** Arithmetic node over already-built operands (used where the right operand is a
     *  primaireExpressie, e.g. the exponent of "tot de macht"). A typeringen error (a
     *  family mismatch or incompatible/inconvertible units) is SURFACED as a diagnostic
     *  before degrading the result to Onbekend — the comparison and begrenzing paths
     *  already do; the arithmetic path used to swallow it (p45). An Onbekend operand makes
     *  reken return ok (no claim), so no false positive on an unresolved sub-expression. */
    private Expr binairExpr(Expr.BinOp op, Expr links, Expr rechts, Bron bron, int line) {
        // §6.11: "datum plus 2 kerstdagen" — a declared dagsoort as duration unit is a valid date shift
        // (day-by-day through the §9.8 predicate); the matrix itself cannot know the declared dagsoorten.
        // Through the shared reader, so this typing question and the four lowering positions in the Drools
        // branches read the spelling in ONE order (ch. 3.8: the calendar table first).
        boolean dagsoortRechts = (op == Expr.BinOp.PLUS || op == Expr.BinOp.MIN)
                && rechts.type() != null
                && Tijdrekening.dagsoortVoorEenheid(model, rechts.type().eenheidOf()) != null;
        Typering.Resultaat res = Typering.reken(naarTyperingOp(op), links.type(), rechts.type(), dagsoortRechts);
        if (!res.ok())
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(
                    RegelSpraakModel.Severity.ERROR, res.fout(), line));
        Datatype t = res.ok() ? res.type() : onbekend();
        return new Expr.Binair(op, links, rechts, t, bron);
    }

    private Expr afgerond(Expr basis, AfrondingContext a, Bron bron) {
        Expr.AfrondModus modus =
                a.NAAR_BENEDEN() != null ? Expr.AfrondModus.NAAR_BENEDEN
              : a.NAAR_BOVEN()  != null ? Expr.AfrondModus.NAAR_BOVEN
              : a.RICHTING_NUL() != null ? Expr.AfrondModus.RICHTING_NUL
              : a.WEG_VAN_NUL()  != null ? Expr.AfrondModus.WEG_VAN_NUL
              : Expr.AfrondModus.REKENKUNDIG;
        int dec = 0;
        if (a.NUMBER() != null) try { dec = Integer.parseInt(a.NUMBER().getText()); } catch (NumberFormatException ignore) {}
        return new Expr.Afgerond(basis, modus, dec, bron);
    }

    /** Emit a §6.1.4 ERROR when a begrenzing bound is not order-comparable with the bounded
     *  value (a non-numeric, wrong-family, or unit-incompatible bound). Onbekend = no claim. */
    private void meldBegrenzing(Datatype basis, Datatype grens, ParserRuleContext ctx) {
        String fout = Typering.begrenzing(basis, grens);
        if (fout != null)
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(
                    RegelSpraakModel.Severity.ERROR, fout, ctx.getStart().getLine()));
    }

    /** Resolve a begrenzing into [minimum, maximum] (either may be null). */
    private Expr[] grenzen(BegrenzingContext b) {
        Expr min = null, max = null;
        if (b.begrenzingMinimum() != null) min = bouwPrimair(b.begrenzingMinimum().primaireExpressie());
        if (b.begrenzingMaximum() != null) max = bouwPrimair(b.begrenzingMaximum().primaireExpressie());        // standalone 'met een maximum van'
        else if (b.begrenzingMaximumNaEn() != null) max = bouwPrimair(b.begrenzingMaximumNaEn().primaireExpressie()); // after 'en' (bare allowed)
        return new Expr[]{ min, max };
    }

    // ---------------- §13.4.17 primaire expressie ----------------
    private Expr bouwPrimair(PrimaireExpressieContext p) {
        // GEEN GETUIGE: ANTLR kiest de primaireExpr-tak van `expressie` pas NA een geslaagde match, dus
        // een PrimaireExprContext zonder kind is niet te produceren. De zusterwacht in bouw(e) is wel
        // bereikbaar (zie StilleMarkerTest). Gemeten 2026-09-19.
        if (p == null) return onopgelost(p,
                "er is geen primaire expressie om te bouwen — de ontleding leverde op deze plaats geen boom op");
        Bron bron = bron(p);
        if (p.getalWaarde() != null) {
            GetalWaardeContext g = p.getalWaarde();
            int dec = decimalenVanLiteral(bron.snippet());    // §6.1.2: the literal's real precision, not 0
            boolean geheel = dec == 0;
            // uchar WF-081 (IR-SPEC ch. 4.1): the literal's waarde is the BARE number lexeme —
            // a '%' or unit suffix belongs in the datatype's eenheid, never in the value string.
            // The NUMBER token is that lexeme; bron keeps the full source snippet ("5 €").
            String lexeme = g.NUMBER().getText();
            if (g.PERCENT_SIGN() != null) return new Expr.Literal(lexeme, new Datatype.Percentage(geheel, dec), bron);
            if (g.eenheidExpressie() != null) {
                // §13.2.17: the literal-with-unit surface admits a composite (rate) denominator
                // ("120 EUR/jr"). Resolve it through the SAME composite-aware helper the attribute
                // datatype uses, so the denominator survives and the literal carries a full
                // Eenheid.samengesteld — otherwise a rate literal is typed by its numerator alone and
                // both §4 unit checking and the exacte cross-unit restatement operate on the wrong unit.
                Eenheid e = DefinitionResolver.eenheidVan(model, g.eenheidExpressie());
                return new Expr.Literal(lexeme, new Datatype.Numeriek(geheel, dec, Datatype.Teken.ONBEPAALD, e), bron);
            }
            return new Expr.Literal(bron.snippet(), new Datatype.Numeriek(geheel, dec, Datatype.Teken.ONBEPAALD, null), bron);
        }
        if (p.tekstWaarde() != null) {
            // §5.4/§13.4.17.9: the lexer splits a "…" into literal STRING_TEXT chunks and «expressie» escapes.
            // A PLAIN string (no «…») builds an Expr.Literal(Tekst); an interpolated string ("…«expressie»…")
            // builds an Expr.TekstReeks whose «…» parts are each a full, recursively-resolved expressie (any
            // datatype, coerced to Tekst at evaluation, §5.4).
            java.util.List<Expr.TekstReeks.Deel> delen = new ArrayList<>();
            boolean heeftExpressie = false;
            for (var deel : p.tekstWaarde().tekstReeksDeel()) {
                if (deel.expressie() != null) {
                    heeftExpressie = true;
                    delen.add(new Expr.TekstReeks.Ingevoegd(bouw(deel.expressie())));
                } else if (deel.STRING_TEXT() != null) {
                    delen.add(new Expr.TekstReeks.Letterlijk(deel.STRING_TEXT().getText()));
                }
            }
            if (!heeftExpressie) {
                // uchar, maintainer decision 2026-09-18: a Tekst literal's waarde is the PARSED string — the
                // karakterreeks BETWEEN the delimiters — not the source snippet with its own outer '"' still on.
                // Emitting the snippet gave v0 two lexeme families for one text value (a hand-authored wire's
                // `vondst` beside an emitted `"vondst"`), and since all four consumers compare Tekst VERBATIM
                // (Literalen.tekstOfEnumLabel) those two documents say the same thing and are not equal — an
                // emitted golden then only played when its case input carried the delimiters too. Third in the
                // row of bare-lexeme fixes beside WF-081 (number) and WF-084 (date): waarde is the lexeme the
                // executors read, bron keeps the full source snippet.
                // The unquoting rule, measured at the grammar rather than guessed: tekstWaarde is
                // STRING_START tekstReeksDeel* STRING_END, and a literal chunk is STRING_TEXT `~["«»\r\n]+`
                // (RegelSpraakLexer.g4). There is NO escape sequence, so a chunk can never hold a '"' and the
                // delimiters are separate tokens — taking the chunks IS stripping the outer delimiters, total
                // and lossless, with every embedded character kept verbatim and nothing unescaped. Taking the
                // TOKENS rather than bron.snippet() minus its first and last char also keeps the interior's own
                // whitespace: DefinitionResolver.src collapses every whitespace run to one space, which a
                // snippet-derived value silently carried into the text ("a  b" became "a b").
                StringBuilder tekst = new StringBuilder();
                for (Expr.TekstReeks.Deel deel : delen) tekst.append(((Expr.TekstReeks.Letterlijk) deel).tekst());
                return new Expr.Literal(tekst.toString(), new Datatype.Tekst(), bron);
            }
            return new Expr.TekstReeks(delen, bron);
        }
        if (p.booleanWaarde() != null)  return new Expr.Literal(bron.snippet(), new Datatype.Booleaan(), bron);
        if (p.deDato() != null) {
            // §13.2.19/.21: a dedato WITH a time component (hh:mm:ss[.mmm]) is a Datum-tijd (millisecond
            // precision), not a Datum in dagen — typing it as a bare date drops the time and lowers a
            // LocalDate into a LocalDateTime target (a Drools type error / silent wrong value).
            boolean metTijd = bron.snippet().matches(".*\\d{1,2}:\\d{2}:\\d{2}.*");
            if (metTijd) return new Expr.Literal(bron.snippet(), new Datatype.DatumTijd(), bron);
            // uchar WF-084 (IR-SPEC ch. 4.1): a date literal's waarde is the BARE ISO lexeme the
            // executors parse (yyyy-mm-dd, fixed field widths) — NOT the Dutch day-first source
            // snippet ("31-01-2024"), which both executors read as ISO and so silently misparse as
            // year 31. The shared reader already holds the parsed date; bron keeps the full source
            // snippet. Mirror of the numeric-literal fix above (bare lexeme in waarde, snippet in bron).
            String iso = Literalen.parseDatum(bron.snippet()).toString();
            return new Expr.Literal(iso, new Datatype.DatumInDagen(), bron);
        }
        if (p.ENUM_LITERAL() != null)   return enumWaarde(p, p.ENUM_LITERAL().getText());
        if (p.rekenConstante() != null) {
            boolean jaar = p.rekenConstante().REKENJAAR() != null;
            Datatype t = jaar ? new Datatype.Numeriek(true, 0, Datatype.Teken.ONBEPAALD, null) : new Datatype.DatumInDagen();
            return new Expr.Constante(jaar ? "Rekenjaar" : "Rekendatum", t, bron);
        }
        if (p.HIJ() != null)            return new Expr.Constante("hij", onbekend(), bron);
        // §9.8/§13.4.17.43 "de dag" — the dagsoortreferentie, the (arbitrary) day a dagsoortdefinitie is about. It IS
        // a date (Datum in dagen): typing it so lets the §8.1.5 "<datum> is een <dagsoort>" recognition fire over it,
        // which is exactly the spec's central cross-dagsoort case (a feestdag definitie tests "de dag is een kerstdag").
        if (p.DE() != null && p.DAG() != null) return new Expr.Constante("de dag", new Datatype.DatumInDagen(), bron);
        if (p.functieAanroep() != null) return bouwFunctie(p.functieAanroep());
        if (p.datumMet() != null)       return new Expr.Functie(Expr.FunctieKind.DATUM_MET, args(p.datumMet()), null, null, null, new Datatype.DatumInDagen(), bron);
        if (p.datumTijdMet() != null)   return new Expr.Functie(Expr.FunctieKind.DATUMTIJD_MET, args(p.datumTijdMet()), null, null, null, new Datatype.DatumTijd(), bron);
        if (p.attribuutVanOnderwerp() != null) return bouwAttribuut(p.attribuutVanOnderwerp());
        if (p.expressie() != null)      return bouw(p.expressie()); // ( expressie )
        if (p.onderwerp() != null) {
            Expr poss = possessief(p.onderwerp(), bron); // "zijn <attribuut van het subject>"
            return poss != null ? poss : naamRef(src(p.onderwerp()), false, bron);
        }
        if (p.variabeleNaam() != null)  return naamRef(src(p.variabeleNaam()), true, bron);
        return onopgelost(p,
                "deze primaire expressie bindt geen literal, geen parameter, geen variabele, geen attribuut "
                + "van een onderwerp en geen functieaanroep (§13.4.17)");
    }

    /** §13.2.2-.5: the decimal precision of a numeric literal's value — its minimal scale, since
     *  §6.1.2 treats 0,5 and 0,50 as the same precision. A §13.2.5 rational reduces to its exact
     *  value (4/2 -> 0, 3/2 -> 1); a non-terminating one (1/3) has no finite scale, so it is read at
     *  DECIMAL64 and types as "many decimals" — which §6.1.2 then rightly demands be rounded. */
    private static int decimalenVanLiteral(String bron) {
        java.math.BigDecimal[] r = io.github.yhavinga.regelspraak.model.Rationaal.van(bron);
        if (r == null) return 0;
        // the literal's exact value teller/noemer (a decimal/integer has noemer ONE, so the divide is exact);
        // a non-terminating rational (1/3) has no finite scale, so read it at DECIMAL64 — §6.1.2 then rightly
        // demands the "many decimals" result be rounded.
        java.math.BigDecimal v;
        try { v = r[0].divide(r[1]); }
        catch (ArithmeticException nietEindig) { v = r[0].divide(r[1], java.math.MathContext.DECIMAL64); }
        return Math.max(0, v.stripTrailingZeros().scale());
    }

    // ---------------- §13.4.17 functies / aggregaties ----------------
    private Expr bouwFunctie(FunctieAanroepContext f) {
        Bron bron = bron(f);
        Expr.FunctieKind kind = functieKind(f);
        // §11/ch. 4.10 an AGGREGATION is a structure-sensitive position through and through: its element type, its
        // set-ness, its dimensieselectie, its "gedurende" clip and its "die …" subselectie are all read off the
        // SHAPE of the resolved operand (and of the subselectie's right-hand side), never off a value. Resolve the
        // whole construct with variable reads SUBSTITUTED — see {@link #ingelijnd}. A non-aggregating functie
        // (wortel, absolute waarde, a date component, a tijdsduur) decides on TYPES only, which a BinderRef carries
        // faithfully, so it keeps the sharing a Let gives it.
        if (binderModus && aggregeert(kind)) return ingelijnd(() -> bouwFunctie(f));
        List<Expr> argumenten = args(f);
        Objecttype elem = aggregatieElement(kind, argumenten);
        // §13.4.17.47 telling vs a declared parameter whose name begins with "aantal": the spec
        // gives no syntactic tie-break for "het aantal <phrase>" (a count of <phrase>, or a
        // reference to a declared name "aantal <phrase>"). Resolve it by countability: a count
        // ranges over a countable element, so when "het aantal <phrase>" binds none AND the whole
        // phrase is a declared parameter, read it as that parameter — real casus source declares
        // parameters whose names begin with "aantal" and reads them back with the article
        // ("het aantal …"). In a well-formed model a genuine count always binds an
        // element (so is never reinterpreted); only a count phrase that cannot resolve a countable
        // element falls back to a same-named declared parameter, where the parameter is the
        // concrete reading and the count reading is meaningless.
        if (kind == Expr.FunctieKind.AANTAL && elem == null) {
            Expr ref = naamRef(bron.snippet(), false, bron);
            if (!(ref instanceof Expr.Onopgelost)) return ref;
        }
        // §5.7 the CONCATENATION of subject expressions under a count (IR-SPEC ch. 4.3, WF-110;
        // maintainer decision 2026-09-20). Tried BEFORE meldAggregatie, because a concatenation that
        // resolves is not the listed WAARDE-telling that arm refuses: it counts INSTANCES and reaches
        // the wire as an InstanceSetUnion — ONE argument — so WF-037's arity arm never sees it.
        if (f instanceof AantalFuncContext && argumenten.size() >= 2) {
            Expr unie = bouwInstantieUnie(f);
            if (unie != null)
                return new Expr.Functie(Expr.FunctieKind.AANTAL, java.util.List.of(unie), null, null,
                        null, functieType(Expr.FunctieKind.AANTAL, argumenten), bron);
        }
        meldAggregatie(kind, argumenten, f);
        Datatype type = f instanceof TijdsduurFuncContext t ? tijdsduurType(t) : functieType(kind, argumenten);
        Expr leegwaarde = leegwaardeVan(f);
        // §4.10/§5.8.2: the fallback substitutes for the sum's RESULT, so it must be numeriek
        // (a Tekst/Datum fallback would otherwise emit a type-mixed DRL ternary that throws at
        // fire time). Onbekend = no claim, so it is not flagged.
        if (leegwaarde != null && !leegwaarde.type().isNumeriek() && !leegwaarde.type().isOnbekend())
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.ERROR,
                    "leegwaarde-alternatief van een sommatie moet numeriek zijn, kreeg "
                            + leegwaarde.type() + " (§5.8.2)", f.getStart().getLine()));
        // declared-names-only IR: an aggregation over a SET read ("de <x> van alle <elem>") whose written form binds NO
        // declared member of the (known) element type is Onopgelost — with a WARNING — so both back-ends
        // marker it instead of accumulating over a getter that does not exist (an uncompilable DRL package /
        // a fire-time reflection abort). The plural-binding in attribuutRef already mapped a written plural
        // onto its declared singular, so only a genuinely unknown member reaches this.
        boolean aggregatieKind = switch (kind) {
            case AANTAL, SOM, TOTAAL, MINIMUM, MAXIMUM, EERSTE, LAATSTE, AANTAL_GEVULDE -> true;
            default -> false;
        };
        // WF-037's filled-count shape (IR-SPEC ch. 4.3): the single argument MUST be a SET reading.
        // Refused HERE, at the operator, because every other kind admits a non-set source for a reason
        // of its own — the §7.1 timeline fold, the row-1 count wart — so the wire fence is per kind and
        // the frontend must draw the same one or it emits a document the verifier rejects. ERROR, so
        // irModus writes nothing: the dialect phrase is new, no committed expectation depends on the
        // laxer reading, and a silent marker would leave the author with a fold over nothing.
        if (kind == Expr.FunctieKind.AANTAL_GEVULDE
                && !(argumenten.size() == 1
                     && argumenten.get(0) instanceof Expr.AttribuutRef zb && zb.set())) {
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.ERROR,
                    "het aantal gevulde waarden van vereist een setlezing als bron: deze vouw leest een WAARDE "
                            + "per bijdrager, dus haar bron noemt de leden ('… van alle <meervoud> van …') — '"
                            + bron.snippet() + "' noemt er geen, en het gevulde aantal van één eigen waarde is "
                            + "0 of 1 (WF-037, IR-SPEC ch. 4.3)", f.getStart().getLine()));
            // the aggregation's OWN type, not the unknown sentinel: a refused fold must still leave a
            // well-formed marker (IR-SPEC ch. 2.4), exactly as keurVouwbron's three refusals do
            return new Expr.Onopgelost(type, bron, null);   // the ERROR above is this marker's diagnosis
        }
        if (aggregatieKind && argumenten.size() == 1
                && argumenten.get(0) instanceof Expr.AttribuutRef sa && sa.set()
                && sa.objecttype() != null && !sa.objecttype().heeftLid(sa.attribuut())) {
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                    "'" + sa.attribuut() + "' is geen (meervoud van een) gedeclareerd attribuut van "
                            + sa.objecttype().naam + " — de aggregatie blijft onopgelost (§13.4.17)",
                    f.getStart().getLine()));
            return onopgelost(f, null);   // the WARNING above is this marker's diagnosis
        }
        if (aggregatieKind && argumenten.size() == 1
                && argumenten.get(0) instanceof Expr.AttribuutRef vb && vb.set()) {
            Expr geweigerd = keurVouwbron(f, vb, type);
            if (geweigerd != null) return geweigerd;
        }
        Conditie filter = aggregatieFilter(f, elem, argumenten);
        if (filter instanceof Conditie.Onopgelost)   // a resolved kenmerk-filter is lowered; only an
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING, // unresolved one warns
                    "een 'die/dat'-subselectie op een aggregatie-operand wordt nog niet omgezet; de aggregatie "
                            + "blijft buiten beschouwing in plaats van alle instanties mee te nemen (§5.6) — '"
                            + filter.bron() + "'", f.getStart().getLine()));
        // §13.4.17.51-.54: a dimensieselectie over a gedimensioneerd attribuut ("over alle <dim>",
        // "over de <dim> vanaf … t/m …", "in {…}", or the bare "… vanaf <label> t/m <label>") aggregates
        // over a DIMENSION's label set, not over object instances. When it resolves — a single dimensioned
        // attribute operand whose dimension and labels are all known — carry it as a DimensieSelectie so the
        // transpiler folds the attribute over exactly those labels.
        Expr.DimensieSelectie dimSel = resolveerDimensieSelectie(f, argumenten);
        if (dimSel != null)
            // recomputed: the §13.4.17.53 re-split may have rebuilt the polluted operand in-place, so the
            // pre-computed type/element (derived from the then-Onopgelost argument) can be stale.
            return new Expr.Functie(kind, argumenten, aggregatieElement(kind, argumenten), leegwaarde, filter,
                    dimSel, functieType(kind, argumenten), bron);
        // §13.4.17.51-.58: a dimensieselectie that did NOT resolve, or a conditiebijexpressie ("gedurende
        // de tijd dat …") — the temporal axis is not built yet (p5) — must not be SILENTLY DROPPED, which
        // would aggregate the whole set as if unrestricted. Capture its source as an Onopgelost filter so
        // the transpiler markers the aggregation (a visible no-op), and surface a WARNING. (Reuses the
        // aggregation-restriction marker path; an already-unresolved die-filter keeps its own marker.)
        Bron dimBron = dimensieOfConditieBron(f);
        if (dimBron != null) {
            // §7.1 a "gedurende de tijd dat <temporeel kenmerk>" restriction on a totaal over a single-period
            // temporal own-attribute resolves to a temporal Conditie.Kenmerk the transpiler clips the value
            // timeline by; anything else (a dimensieselectie, a navigated/compound/non-temporal condition) stays
            // an Onopgelost marker so the aggregation is a visible no-op, not a silently-unrestricted total.
            Conditie gedurende = gedurendeKenmerkFilter(f, kind, argumenten);
            if (gedurende != null) {
                filter = gedurende;
            } else {
                model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                        "een dimensieselectie / 'gedurende de tijd dat' op een aggregatie wordt nog niet omgezet; "
                                + "de aggregatie blijft buiten beschouwing in plaats van de selectie te negeren "
                                + "(§13.4.17.51-.58) — '" + dimBron + "'", f.getStart().getLine()));
                if (!(filter instanceof Conditie.Onopgelost)) filter = new Conditie.Onopgelost(dimBron, null);
            }
        }
        // §13.4.17.57 "het aantal dagen in <de maand|het jaar> dat <conditie>": the post-"dat" condition is a
        // DIRECT grammar child (not a conditiebijexpressie), so it is otherwise dropped. Structure a simple
        // single-period subject temporal-kenmerk membership into a Conditie.Kenmerk (reusing the gedurende path's
        // resolver) so the transpiler can count true-days per period; anything else (a comparison / compound /
        // navigated / non-temporal condition) leaves the filter unset -> the transpiler markers.
        RegelSpraakModel.Granulariteit operatorGran = null;
        if (kind == Expr.FunctieKind.AANTAL_DAGEN_IN && f instanceof AantalDagenFuncContext adf) {
            // §13.4.17.57/§4.21 carry the operator's "in <de maand|het jaar>" bucket unit so the transpiler can
            // require it to MATCH the doel's tijdlijn rather than silently bucketing by the doel's unit.
            operatorGran = adf.MAAND() != null ? RegelSpraakModel.Granulariteit.MAAND
                    : (adf.JAAR() != null ? RegelSpraakModel.Granulariteit.JAAR : null);
            if (adf.toplevelElementaireVoorwaarde() != null && !(filter instanceof Conditie.Kenmerk)) {
                ToplevelElementaireVoorwaardeContext c = adf.toplevelElementaireVoorwaarde();
                Conditie.Kenmerk k = temporeelKenmerkUitOperator(c.toplevelOperator(), null, bron(adf));  // §8.3.1 toplevel = vragende only
                if (k != null) filter = k;
            } else if (adf.toplevelSamengesteldeVoorwaarde() != null && !(filter instanceof Conditie.Samengesteld)) {
                // §8.4.2 a COMPOUND "dat er aan alle / ten minste 1 van de volgende voorwaarden wordt voldaan: …" —
                // the same temporal-kenmerk compound as a gedurende; the transpiler buckets the days the COMBINED
                // condition (intersection for ALLE / union for ten-minste-1 of the bullets) holds.
                Conditie samengesteld = gedurendeSamengesteld(adf.toplevelSamengesteldeVoorwaarde(), bron(adf));
                if (samengesteld != null) filter = samengesteld;
            }
        } else if (kind == Expr.FunctieKind.TIJDSEVENREDIG_DEEL && f instanceof TijdsevenredigFuncContext tvf) {
            // §13.4.17.58/§7.3.2 carry the operator's "per <maand|jaar>" bucket unit; the transpiler requires it to
            // MATCH the doel's tijdlijn ("de knips ... moeten passen op de tijdlijn van het attribuut", §7.3.2).
            operatorGran = tvf.MAAND() != null ? RegelSpraakModel.Granulariteit.MAAND
                    : (tvf.JAAR() != null ? RegelSpraakModel.Granulariteit.JAAR : null);
        }
        Expr functie = new Expr.Functie(kind, argumenten, elem, leegwaarde, filter, null, type, bron, operatorGran);
        // §13.4.17.13: the wortel's afronding is MANDATORY and rounds the ROOT (it is a sibling of the
        // radicand, not inside it). It was parsed but dropped; wrap the function so the rounding survives.
        if (f instanceof WortelFuncContext w) return afgerond(functie, w.afronding(), bron);
        return functie;
    }

    /**
     * The three ways a MEMBER fold ("de som/minimale waarde/… van &lt;attr&gt; van alle &lt;elem&gt; van
     * &lt;onderwerp&gt;") can bind a DIFFERENT collection than the sentence names. All three end in the honest
     * marker with a WARNING — the {@link Expr.Onopgelost} idiom this resolver already uses for a fold source
     * it cannot bind — never in a fold over a guessed relation. Returns the marker, or null when the source
     * binds what it says. Each marker carries {@code reden = null}: the WARNING beside it IS its reason, and
     * the survivor report must not say it twice.
     *
     * <p>The marker carries the aggregation's OWN result type, not the unknown sentinel: IR-SPEC ch. 2.4
     * defines Onopgelost as "a well-formed marker with a reason, not a type" and WF-044's fence is
     * behavioural, so a refused fold must leave a WELL-FORMED document that faults observably (WF-044) in
     * every executor — the red-zone shape, not an ill-formed one.
     *
     * <p>(a) A NESTED set step ("… van alle X van alle Y van de Z"). The uchar wire describes a fold source
     * with ONE relation: one {@code elementObjecttype} plus one {@code bronRol} (IR-SPEC ch. 4.3). A
     * two-step written range is not expressible on it, and the emitter's derivation ranges from the RULE
     * SUBJECT to the innermost element type — so the outer step disappears without a trace and the fold
     * runs over the wrong set. Where no relation joins subject and element that is caught downstream
     * (WF-048); where one happens to exist it is verifier-CLEAN and silently wrong, which is the shape
     * this refusal exists for.
     *
     * <p>(b) An intermediate TO-ONE step ("… van alle X van de Y van de Z"), the same loss along a third
     * route: the fold source names one relation FROM THE RULE SUBJECT, so an anchor that is not the subject
     * drops its step just as silently — and here the written element name may legitimately name the
     * subject-side role, so (c) does not see it either. The anchor must therefore NAME the subject's own
     * object type, literally. An anchor that resolves to no object type at all (a role name, "alle vondsten
     * van de aanmelder") is left alone: resolving a role anchor is the wire question, not this refusal.
     *
     * <p>(c) A by-PAIR binding the written name contradicts. {@code verbindendeRolBinding} searches on
     * TYPES only; with exactly one fact type joining subject and element it always wins, whatever role the
     * sentence named. Dropping the binding does not help — measured: a bindingless fold over a uniquely
     * related pair is verifier-clean and every executor re-derives the same wrong relation — so the
     * resolution itself must decline. The escape is the case where the author claimed no role at all: a
     * written name that IS the element object type's own name.
     */
    private Expr keurVouwbron(FunctieAanroepContext f, Expr.AttribuutRef bron, Datatype type) {
        // WF-102 (2026-09-11): a range the resolver RESOLVED is carried on the wire, so there is nothing
        // left to refuse. Routes (a) and (b) below exist because a stacked range had no wire form; where
        // one is bound they would refuse an expressible sentence, and (c) has nothing to contradict — a
        // bound path makes no by-pair guess. The refusals stay for every chain that declines to resolve.
        if (bron.bereikPad() != null && !bron.bereikPad().isEmpty()) return null;
        OnderwerpContext o = vouwBronOnderwerp(f);
        if (o == null) return null;
        OnderwerpContext genest = genesteSetStap(o);
        if (genest != null) {
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                    "geneste verzamelingsstap in een vouwbron: '" + src(o) + "' stapelt de verzameling '"
                            + src(genest) + "' onder een tweede verzameling, en een vouwbron draagt precies "
                            + "één relatie (IR-SPEC ch. 4.3: één elementObjecttype plus één bronRol) — de "
                            + "buitenste stap zou spoorloos wegvallen, dus de aggregatie blijft onopgelost "
                            + "(§13.4.17)", f.getStart().getLine()));
            return new Expr.Onopgelost(type, bron(f), null);
        }
        // The emitter derives a bronRol only for a navigation-free member fold over a resolved element type
        // (IrEmitter.bronRolBinding); outside that shape there is no by-pair guess to contradict.
        if (bron.objecttype() == null || huidigSubject == null) return null;
        if (bron.navigatieRol() != null || !bron.navigatiePad().isEmpty()) return null;
        String geschreven = primaireNaam(o);
        // (b): the anchor the written chain hands the element to — the first hop past the element head,
        // read with the same name candidates the element type's own binding was tried with.
        int sp = geschreven == null ? -1 : geschreven.toLowerCase().indexOf(" van ");
        if (sp > 0)
            for (String kandidaat : RegelSpraakModel.naamKandidaten(geschreven.substring(sp + 5))) {
                Objecttype anker = model.objecttypeOpNaam(kandidaat);
                if (anker == null) {
                    // WF-102's anchor rule (2026-09-11): a role anchor is admissible only where it denotes
                    // the SUBJECT, under ch. 4.1a's order. A far role — one reachable FROM the subject —
                    // denotes another instance, so binding the fold to the subject's own relation would
                    // fold the wrong set in silence; that is the shape refused here. A SELF-DENOTING name
                    // (zero far hits, and the subject type fills a role of that name) is the subject, and
                    // the chain simply starts there. A name that resolves to nothing at all keeps its
                    // pre-existing silence: there is nothing to say about it.
                    if (model.rolTreffersNaarSubject(huidigSubject, kandidaat) == 0) continue;
                    model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                            "rolanker in een vouwbron: '" + src(o) + "' verankert de verzameling op de rol '"
                                    + kandidaat + "', die vanaf " + huidigSubject.naam + " een ANDER exemplaar "
                                    + "aanwijst — een vouwbron zonder bronPad noemt één relatie vanaf het "
                                    + "regelonderwerp zelf (IR-SPEC ch. 4.3/WF-102), dus de stap naar dat "
                                    + "exemplaar zou spoorloos wegvallen en de aggregatie blijft onopgelost "
                                    + "(§13.4.1/§13.4.17)", f.getStart().getLine()));
                    return new Expr.Onopgelost(type, bron(f), null);
                }
                if (anker == huidigSubject) break;       // the chain starts at the subject, as the wire needs
                model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                        "tussenstap in een vouwbron: '" + src(o) + "' bereikt de elementen via " + anker.naam
                                + ", maar een vouwbron draagt precies één relatie vanaf het regelonderwerp "
                                + huidigSubject.naam + " (IR-SPEC ch. 4.3: één elementObjecttype plus één "
                                + "bronRol) — de tussenstap zou spoorloos wegvallen, dus de aggregatie blijft "
                                + "onopgelost (§13.4.1/§13.4.17)", f.getStart().getLine()));
                return new Expr.Onopgelost(type, bron(f), null);
            }
        // (c): the written element name, under every form the element type's own binding was tried with, so a
        // phrase that bound its type by dropping a leading qualifier is judged by that same form.
        List<String> namen = RegelSpraakModel.naamKandidaten(geschreven);
        if (namen.isEmpty()) return null;
        if (namen.stream().anyMatch(n -> model.objecttypeOpNaam(n) != null)) return null;
        RegelSpraakModel.RolBindingRef paar = model.verbindendeRolBinding(huidigSubject, bron.objecttype());
        if (paar == null || namen.stream().anyMatch(n -> model.rolGenoemdAls(paar, n))) return null;
        model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                "vouwbron bindt niet op de geschreven naam: de zin noemt '" + namen.get(0) + "', maar het enige "
                        + "feittype dat " + huidigSubject.naam + " met " + bron.objecttype().naam
                        + " verbindt is '" + paar.feittype() + "' met de rol '" + paar.rol() + "' — de "
                        + "type-paar-afleiding zou een andere verzameling vouwen dan de zin noemt, dus de "
                        + "aggregatie blijft onopgelost (§13.4.1/§13.4.17)", f.getStart().getLine()));
        return new Expr.Onopgelost(type, bron(f), null);
    }

    /** The set-quantified onderwerp of an aggregation's OWN operand — the fold source as written. Found by
     *  descending {@code t}, stopping at a nested functieAanroep (which judges its own operand) and at a
     *  §5.8.2 leegwaardeAlternatief (the fallback is not the fold's source). Null when the operand names no
     *  set onderwerp — a count over an attribuut-position element word ("het aantal vondsten van de Jutter")
     *  reaches a to-ONE onderwerp and is left to its own derivation. */
    private OnderwerpContext vouwBronOnderwerp(ParseTree t) {
        if (t instanceof AttribuutVanOnderwerpContext a && a.onderwerp() != null && isSetOnderwerp(a.onderwerp()))
            return a.onderwerp();
        for (int i = 0; i < t.getChildCount(); i++) {
            ParseTree ch = t.getChild(i);
            if (ch instanceof FunctieAanroepContext || ch instanceof LeegwaardeAlternatiefContext) continue;
            OnderwerpContext o = vouwBronOnderwerp(ch);
            if (o != null) return o;
        }
        return null;
    }

    /** The first set-quantified onderwerp NESTED under {@code o}'s "van"-chain, or null when every inner step
     *  is a single object. Walks the whole chain rather than only the next step, so "alle X van de Y van alle
     *  Z" is caught as surely as "alle X van alle Y van de Z". */
    private static OnderwerpContext genesteSetStap(OnderwerpContext o) {
        for (OnderwerpContext in = o.onderwerp(); in != null; in = in.onderwerp())
            if (isSetOnderwerp(in)) return in;
        return null;
    }

    /** §13.4.17.51-.58: the raw source of an aggregation's dimensieselectie (som / min-max) or
     *  conditiebijexpressie (totaal / tijdsevenredig deel), or null when absent — used to mark the dropped
     *  temporal/dimensional restriction so the aggregation is not silently lowered as if unrestricted. */
    private Bron dimensieOfConditieBron(FunctieAanroepContext f) {
        DimensieSelectieContext dim = f instanceof SomFuncContext s ? s.dimensieSelectie()
                : f instanceof MinMaxFuncContext m ? m.dimensieSelectie() : null;
        if (dim != null) return bron(dim);
        ConditieBijExpressieContext cnd = f instanceof TotaalFuncContext t ? t.conditieBijExpressie()
                : f instanceof TijdsevenredigFuncContext tv ? tv.conditieBijExpressie() : null;
        return cnd != null ? bron(cnd) : null;
    }

    /** §13.4.17.51-.54: resolve a SOM/MIN/MAX dimensieselectie to the concrete dimension and the ordered
     *  list of labels it ranges over, or null when it cannot be lowered — a null leaves the honest marker in
     *  place. It only lowers a fold over a SINGLE gedimensioneerd attribuut's labels, and only when the
     *  operand is the rule subject's OWN dimensioned attribute ({@code a.objecttype() == huidigSubject} —
     *  the §5.8.5 case, "zijn betaalde belasting in jaar"). A NAVIGATED operand is deferred: a to-MANY read
     *  ("de prijs in jaar van alle deelnemers") would be a 2-level aggregation (over instances AND labels)
     *  that the single-Map fold cannot express — resolving it would drop the marker and let the instance
     *  accumulate sum Maps (a miscompile) — and a to-one navigated fold has no spec/corpus example yet. The
     *  attribute must carry exactly ONE dimension (the value space is a Map&lt;label,value&gt;; a
     *  multi-dimension attribute is keyed by label COMBINATIONS, §3.6, which the single-label fold would
     *  mis-read), the named/implied dimension must be that one, every label must be a declared value, and a
     *  "vanaf … t/m …" interval must not run backwards. (A conditiebijexpressie — temporal — never reaches
     *  here.) */
    private Expr.DimensieSelectie resolveerDimensieSelectie(FunctieAanroepContext f, List<Expr> argumenten) {
        DimensieSelectieContext dim = f instanceof SomFuncContext s ? s.dimensieSelectie()
                : f instanceof MinMaxFuncContext m ? m.dimensieSelectie() : null;
        if (dim == null) return null;
        if (argumenten.size() != 1) return null;
        // §13.4.17.53 the EXPLICIT "over de <meervoud> vanaf … t/m …" form: OVER and DE are name words
        // (attribute names like "belasting over de afgelopen vier jaar" need them), so the greedy attribute
        // kaleNaam absorbs the " over de <meervoud>" tail — the possessive operand then fails to resolve
        // AT ALL (an Expr.Onopgelost) and the explicit form reaches here as the BARE interval. Re-split by
        // declared names (the §13.4-names design, like the objectcreatie/feitcreatie re-splits): peel the
        // tail only when it is a declared dimension's meervoud AND the peeled head is the subject's OWN
        // declared attribute ("zijn/haar …") dimensioned by exactly that dimension; the fold operand is
        // rebuilt clean (declared spelling + element datatype) in the argument list.
        RegelSpraakModel.Dimensie expliciet = null;
        if (dim.VANAF() != null && argumenten.get(0) instanceof Expr.Onopgelost on && huidigSubject != null
                && on.bron() != null && on.bron().snippet().matches("(?is)(zijn|haar)\\s+.*")) {
            int snee = on.bron().snippet().toLowerCase().lastIndexOf(" over de ");
            if (snee >= 0) {
                String kop = on.bron().snippet().substring(0, snee).replaceFirst("^(?is)(zijn|haar)\\s+", "");
                String mv = on.bron().snippet().substring(snee + " over de ".length()).trim();
                RegelSpraakModel.Dimensie dd = model.dimensieOpMeervoud(mv);
                List<String> kopDims = dd == null ? List.of() : huidigSubject.dimensiesVan(kop);
                if (dd != null && kopDims.size() == 1 && kopDims.get(0).equalsIgnoreCase(dd.naam())) {
                    // the declared spelling may sit on an ancestor: kopDims came from the chain-aware
                    // dimensiesVan, so the own map alone would fall back to the WRITTEN form and put a
                    // name on the wire that no declaration carries
                    String eigen = huidigSubject.attribuutNaam(kop);
                    String declared = eigen != null ? eigen : kop;
                    argumenten.set(0, new Expr.AttribuutRef(declared, huidigSubject, "zijn", null,
                            huidigSubject.attribuut(declared), on.bron(), false));
                    expliciet = dd;
                }
            }
        }
        if (!(argumenten.get(0) instanceof Expr.AttribuutRef a) || a.objecttype() == null) return null;
        if (a.objecttype() != huidigSubject) return null;   // only the subject's OWN dimensioned attribuut folds; a navigated read is deferred
        List<String> attrDims = a.objecttype().dimensiesVan(a.attribuut());
        if (attrDims.size() != 1) return null;   // not gedimensioneerd, or multi-dimension (composite key) — deferred

        RegelSpraakModel.Dimensie d;
        List<String> labels;
        if (dim.VANAF() != null) {
            // §5.8.5 bare interval: the dimension is left implicit (unambiguous — the attribute carries
            // exactly one dimension, guaranteed above); the re-split §13.4.17.53 form carries it explicitly.
            d = expliciet != null ? expliciet : model.dimensieOpNaam(attrDims.get(0));
            if (d == null) return null;
            labels = interval(d, src(dim.dimensieWaarde(0)), src(dim.dimensieWaarde(1)));
        } else if (dim.aggregerenOverAlleDimensies() != null) {
            d = geselecteerdeDimensie(dim.aggregerenOverAlleDimensies().dimensieNaamMeervoud(), attrDims);
            if (d == null) return null;
            labels = new ArrayList<>(d.waarden());
        } else if (dim.aggregerenOverVerzameling() != null) {
            AggregerenOverVerzamelingContext v = dim.aggregerenOverVerzameling();
            d = geselecteerdeDimensie(v.dimensieNaamMeervoud(), attrDims);
            if (d == null) return null;
            labels = interval(d, src(v.dimensieWaarde(0)), src(v.dimensieWaarde(1)));
        } else if (dim.aggregerenOverBereik() != null) {
            AggregerenOverBereikContext v = dim.aggregerenOverBereik();
            d = geselecteerdeDimensie(v.dimensieNaamMeervoud(), attrDims);
            if (d == null) return null;
            labels = bereik(d, v.dimensieWaarde());
        } else {
            return null;
        }
        if (labels == null || labels.isEmpty()) return null;
        return new Expr.DimensieSelectie(d.naam(), labels, bron(dim));
    }

    /** The dimension an over-form names by its plural, but only when the operand attribute is actually
     *  gedimensioneerd with it (else summing a different axis would be silently wrong). */
    private RegelSpraakModel.Dimensie geselecteerdeDimensie(DimensieNaamMeervoudContext mv, List<String> attrDims) {
        RegelSpraakModel.Dimensie d = model.dimensieOpMeervoud(src(mv));
        if (d == null) return null;
        return attrDims.stream().anyMatch(n -> n.equalsIgnoreCase(d.naam())) ? d : null;
    }

    /** §5.8.5 the contiguous label interval [van .. t/m] in the dimension's DECLARED order (§13.3.7.5),
     *  or null when a bound is not a declared label or the interval runs backwards. */
    private static List<String> interval(RegelSpraakModel.Dimensie d, String van, String tm) {
        int i = labelIndex(d, van), j = labelIndex(d, tm);
        if (i < 0 || j < 0 || i > j) return null;
        return new ArrayList<>(d.waarden().subList(i, j + 1));
    }

    /** §13.4.17.54 the explicit "in {…}" label set, mapped to the declared label spelling, or null when
     *  any named label is not a value of the dimension. */
    private List<String> bereik(RegelSpraakModel.Dimensie d, List<DimensieWaardeContext> waarden) {
        List<String> uit = new ArrayList<>();
        for (DimensieWaardeContext w : waarden) {
            int idx = labelIndex(d, src(w));
            if (idx < 0) return null;
            uit.add(d.waarden().get(idx));
        }
        return uit;
    }

    private static int labelIndex(RegelSpraakModel.Dimensie d, String label) {
        String norm = label == null ? "" : label.trim();
        for (int i = 0; i < d.waarden().size(); i++)
            if (d.waarden().get(i).equalsIgnoreCase(norm)) return i;
        return -1;
    }

    /** The §5.6 "die/dat &lt;predicaat&gt;" subselectie filtering a SINGLE-operand set-aggregation's
     *  element ("het aantal / de som / de minimale waarde van … deelnemers van de groep die een kind
     *  zijn" — only the kind-deelnemers). Resolved against the ELEMENT type (not the rule subject): a
     *  kenmerk of the element becomes a real {@link Conditie.Kenmerk} (lowered as an accumulate-source
     *  constraint); anything else stays {@link Conditie.Onopgelost}, so the aggregation is a visible
     *  marker rather than a silently-wrong total over EVERY instance.
     *
     *  Only a single operand is considered: a multi-operand telling (the §5.8.1 union) or a min/max
     *  VALUE-fold has no single element filter — there a die belongs to one operand's own navigation
     *  (e.g. "de eerste deelnemer die …"), not the aggregation, and markering on it would kill a
     *  working accumulate (see p20b). With one operand a generic subtree scan is safe. */
    /** §7.1/§7.3.2 the "gedurende de tijd dat <voorwaarde>" restriction on "het totaal van <temporeel attribuut>"
     *  OR "het tijdsevenredig deel per <maand|jaar> van <temporeel attribuut>", when it is a SIMPLE single-period
     *  temporal-kenmerk membership of the subject ("hij rechthebbend is", or the verbatim example's bezittelijk
     *  "hij een recht op belastingvermindering heeft"), resolved to a {@link Conditie.Kenmerk} the transpiler clips
     *  the value timeline by. Tightly gated: only a TOTAAL or TIJDSEVENREDIG_DEEL over the subject's OWN single-period
     *  temporal attribute (the operand the operator ranges over — the totaled attribute, or the §7.3.2 base whose
     *  value-timeline §5.1.2 makes intrinsic), with a non-navigated "is/heeft <kenmerk>" condition naming a
     *  single-period temporal kenmerk of the subject. BOTH membership verbs bind: a bijvoeglijk/zelfstandig kenmerk
     *  reads "is <k>", a bezittelijk one "heeft (een) <k>" (§3.5) — the verb is not the discriminator; the
     *  granularity check below is (a role/possession "heeft een <rol>" names no temporal kenmerk, so it still returns
     *  null). Everything else (a navigated/compound/comparison/multi-period condition, a non-temporal or navigated/
     *  scalar operand) returns null and stays an Onopgelost marker — so a SCALAR (parameter) base, whose §5.1.2
     *  value-timeline the model cannot carry, never lowers as a silently-constant proration. */
    private Conditie gedurendeKenmerkFilter(FunctieAanroepContext f, Expr.FunctieKind kind, List<Expr> argumenten) {
        if ((kind != Expr.FunctieKind.TOTAAL && kind != Expr.FunctieKind.TIJDSEVENREDIG_DEEL)
                || argumenten.size() != 1 || huidigSubject == null) return null;
        Expr operand = argumenten.get(0);
        // The operand the operator ranges over must be a single-period TIMELINE: the subject's own temporal attribute
        // (totaal + tijdsevenredig), OR — for §7.3.2 tijdsevenredig only — a TEMPORAL PARAMETER (the spec's STANDAARD
        // BELASTINGVERMINDERING base, "kan jaarlijks wijzigen"), whose runtime value is itself a timeline (§5.1.2).
        boolean okOperand = operand instanceof Expr.AttribuutRef a && a.navigatieRol() == null && !a.set()
                && a.objecttype() == huidigSubject && huidigSubject.granulariteitVan(a.attribuut()) != null
                && huidigSubject.tijdlijnAantalVan(a.attribuut()) == null;
        if (!okOperand && kind == Expr.FunctieKind.TIJDSEVENREDIG_DEEL
                && operand instanceof Expr.ParameterRef p && p.parameter().granulariteit() != null)
            okOperand = true;
        // §7.3.2 a TO-ONE NAVIGATED tijdsevenredig base ("de X van zijn reis") — a single-period temporal attribute of
        // the RELATED type. Its value-timeline is intrinsic (§5.1.2), read root-guarded by both back-ends (navReader /
        // the DRL root-guarded getter). The gedurende condition still ranges over the rule SUBJECT, so it resolves
        // against huidigSubject below; only the base reads through the navigation. (A to-many base markers in lowering.)
        if (!okOperand && kind == Expr.FunctieKind.TIJDSEVENREDIG_DEEL
                && operand instanceof Expr.AttribuutRef a && a.navigatieRol() != null && !a.set()
                && a.objecttype() != null && a.objecttype().granulariteitVan(a.attribuut()) != null
                && a.objecttype().tijdlijnAantalVan(a.attribuut()) == null)
            okOperand = true;
        if (!okOperand) return null;
        ConditieBijExpressieContext cnd = f instanceof TotaalFuncContext t ? t.conditieBijExpressie()
                : f instanceof TijdsevenredigFuncContext tv ? tv.conditieBijExpressie() : null;
        if (cnd == null) return null;
        // §7.1 a SIMPLE (single) membership, or §8.4.2 a COMPOUND ("er aan alle/ten minste 1 van de volgende
        // voorwaarden wordt voldaan: …") of such memberships. Each bullet must be a single-period temporal kenmerk
        // of the subject; anything else (a comparison, a non-temporal/navigated kenmerk, another kwantor, a nested
        // compound) leaves the WHOLE condition an Onopgelost marker — so the spec's own mixed temporal+non-temporal
        // §8.4 example never silently drops its non-temporal predicate.
        if (cnd.toplevelElementaireVoorwaarde() != null) {
            ToplevelElementaireVoorwaardeContext c = cnd.toplevelElementaireVoorwaarde();
            // §7.1 a NAVIGATED temporal kenmerk ("zijn reis een recht op X heeft") FIRST — it inspects the onderwerp,
            // so when the navigated type shares the subject's type (a self-relation) the navigation is not shadowed by
            // the subject's own same-named kenmerk; temporeelKenmerkUitOperator (own) only sees the operator's kaleNaam.
            Conditie nav = gedurendeNavigatieKenmerk(c, bron(cnd));
            if (nav != null) return nav;
            // If the onderwerp IS a navigation that the navigated path could NOT structure (a to-many role, a negated
            // or non-temporal kenmerk), MARKER — never fall through to temporeelKenmerkUitOperator, which inspects only
            // the operator's kaleNaam and would silently bind the SUBJECT's own same-named kenmerk (a self-relation
            // shadow, a silently-wrong clip).
            if (c.expressie() != null && bouw(c.expressie()) instanceof Expr.AttribuutRef a && a.navigatieRol() != null)
                return null;
            Conditie.Kenmerk k = temporeelKenmerkUitOperator(c.toplevelOperator(), null, bron(cnd));  // §8.3.1 toplevel = vragende only
            if (k != null) return k;
            return gedurendeVergelijking(c, bron(cnd));   // §7.1 a temporal-attribute COMPARISON condition (EUR71)
        }
        if (cnd.toplevelSamengesteldeVoorwaarde() != null)
            return gedurendeSamengesteld(cnd.toplevelSamengesteldeVoorwaarde(), bron(cnd));
        return null;
    }

    /** §7.1 structure a "gedurende de tijd dat &lt;temporeel attribuut&gt; &lt;vergelijking&gt; &lt;scalar&gt;"
     *  enkelvoudige condition (the EUR71 shape, e.g. "gedurende de tijd dat het inkomen groter is dan 1000") into a
     *  {@link Conditie.Vergelijking} the transpiler clips the value timeline by (its boolean timeline). Built only
     *  when ONE operand is a single-period temporal attribute of the subject (the condition varies over time) and the
     *  form is a verb-split comparison; else null (the caller markers). The transpiler validates numeric type / units. */
    private Conditie gedurendeVergelijking(ToplevelElementaireVoorwaardeContext c, Bron bron) {
        if (c.expressie() == null || c.toplevelOperator() == null
                || c.toplevelOperator().vergelijkingsTekenGesplitst() == null || c.toplevelOperator().expressie() == null)
            return null;
        Expr lhs = bouw(c.expressie());
        Expr rhs = bouw(c.toplevelOperator().expressie());
        if (!isTemporeelSubjectAttr(lhs) && !isTemporeelSubjectAttr(rhs)) return null;   // a static single condition is no gedurende
        Conditie.Operator op = RuleChecker.naarOperator(RuleChecker.cmpOf(c.toplevelOperator().vergelijkingsTekenGesplitst()));
        return vergelijking(op, lhs, rhs, null, bron);
    }

    /** A single-period temporal attribute of the rule subject (the operand a §7.1 gedurende comparison varies over). */
    private boolean isTemporeelSubjectAttr(Expr e) {
        return huidigSubject != null && e instanceof Expr.AttribuutRef a && a.navigatieRol() == null && !a.set()
                && a.objecttype() == huidigSubject && huidigSubject.granulariteitVan(a.attribuut()) != null
                && huidigSubject.tijdlijnAantalVan(a.attribuut()) == null;
    }

    /** §7.1 structure a "gedurende de tijd dat &lt;zijn rol&gt; (een/het) &lt;temporeel kenmerk&gt; heeft/is"
     *  NAVIGATED temporal-kenmerk condition (e.g. "gedurende de tijd dat zijn reis een recht op korting heeft") into a
     *  navigated {@link Conditie.Kenmerk} the transpiler clips by the to-one role's kenmerk timeline. The onderwerp is
     *  a to-one role navigation; the kenmerk must be a SINGLE-PERIOD temporal kenmerk of the navigated type. A negated
     *  form, a to-many role, or a non-temporal kenmerk -> null (the caller markers). */
    private Conditie gedurendeNavigatieKenmerk(ToplevelElementaireVoorwaardeContext c, Bron bron) {
        ToplevelOperatorContext top = c.toplevelOperator();
        if (c.expressie() == null || top == null || top.kaleNaam() == null) return null;
        boolean membership = top.IS() != null || top.ZIJN() != null || top.HEEFT() != null || top.HEBBEN() != null;
        if (!membership) return null;                                                   // not an is/heeft kenmerkcheck
        if (!(bouw(c.expressie()) instanceof Expr.AttribuutRef a) || a.navigatieRol() == null || a.objecttype() == null)
            return null;                                                                // not a to-one role navigation
        Objecttype doel = model.rolDoeltypeNaarSubject(a.objecttype(), a.attribuut());
        if (doel == null || model.naarVeelRol(a.objecttype(), doel, a.navigatieRol())) return null;   // to-many -> marker
        String kenmerk = KenmerkMatcher.vind(doel, stripArtikel(src(top.kaleNaam())));
        if (kenmerk == null) return null;                                               // not a kenmerk of the navigated type
        if (doel.kenmerkGranulariteitVan(kenmerk) == null || doel.kenmerkTijdlijnAantalVan(kenmerk) != null)
            return null;                                                                // must be a single-period temporal kenmerk
        return new Conditie.Kenmerk(kenmerk, a.navigatieRol(), false, bron);
    }

    /** Resolve an elementaire "is/heeft (geen/niet) (een/het) &lt;kenmerk&gt;" membership operator into a
     *  single-period temporal {@link Conditie.Kenmerk} of the subject (negation + article-strip handled), or null
     *  when it is not one — a comparison, a non-temporal / multi-period / navigated kenmerk, or a role/possession.
     *  Shared by the single gedurende condition and each bullet of a compound one. */
    private Conditie.Kenmerk temporeelKenmerkUitOperator(ToplevelOperatorContext top, NonToplevelOperatorContext non, Bron bron) {
        KaleNaamContext kn; boolean isVerb, heeftVerb;
        if (top != null) {
            kn = top.kaleNaam(); isVerb = top.IS() != null || top.ZIJN() != null;
            heeftVerb = top.HEEFT() != null || top.HEBBEN() != null;
        } else if (non != null) {
            kn = non.kaleNaam(); isVerb = non.IS() != null || non.ZIJN() != null;
            heeftVerb = non.HEEFT() != null || non.HEBBEN() != null;
        } else return null;
        if (kn == null || (!isVerb && !heeftVerb)) return null;         // an "is/heeft <kenmerk>" membership (§3.5)
        // §8.1.8.1/.2 a "geen"/"niet" before the kenmerk negates the membership ("hij geen recht ... heeft" / "hij
        // niet rechthebbend is"); the negator lexes into the kaleNaam (no GEEN/NIET token here), so try the
        // verbatim name first (a kenmerk literally beginning with geen/niet still binds positively), then a stripped
        // form. A negated gedurende clips the value timeline to the kenmerk's FALSE periods (the complement).
        String geschreven = src(kn);
        boolean negatie = false;
        String kenmerk = KenmerkMatcher.vind(huidigSubject, geschreven);
        if (kenmerk == null && (geschreven.regionMatches(true, 0, "geen ", 0, 5)
                || geschreven.regionMatches(true, 0, "niet ", 0, 5))) {
            kenmerk = KenmerkMatcher.vind(huidigSubject, geschreven.substring(5).trim());
            negatie = kenmerk != null;
        }
        if (kenmerk == null) {
            // §8.1.8.2 the CANONICAL bezittelijk form is "heeft HET <k>" (the §3.5 example "heeft het recht op
            // duurzaamheidskorting"): the article is absorbed into the kaleNaam (only "een" is a separate token), so
            // a verbatim match misses it. Retry article-stripped — verbatim-first above keeps a kenmerk literally
            // named "het …" binding itself.
            String zonderArtikel = geschreven.replaceFirst("^(?i)(de|het|een)\\s+", "").trim();
            if (!zonderArtikel.equals(geschreven)) kenmerk = KenmerkMatcher.vind(huidigSubject, zonderArtikel);
        }
        if (kenmerk == null || huidigSubject.kenmerkGranulariteitVan(kenmerk) == null
                || huidigSubject.kenmerkTijdlijnAantalVan(kenmerk) != null) return null;
        return new Conditie.Kenmerk(kenmerk, null, negatie, bron);
    }

    /** §8.4.2 a COMPOUND tijdsafhankelijke conditie on a temporal totaal: an ALLE (intersect the true-period sets)
     *  or "ten minste 1" (union them) of bullets. Each bullet is a single-period temporal-kenmerk membership of the
     *  subject; an ALLE compound may ALSO mix in a NON-temporal comparison bullet (the spec's own §8.4 example,
     *  "zijn geboortedatum is eerder dan 1-1-2002") — built as a {@link Conditie.Vergelijking} — which the transpiler
     *  lowers to a value-side static guard wrapping the clipped totaal ("de samengestelde conditie is als geheel wel
     *  tijdsafhankelijk"). A "ten minste 1" (OR) compound likewise admits a non-temporal comparison bullet — but its
     *  TRUE case WIDENS the clip to the full value span (the transpiler branches on the kwantor; the WRITE producers
     *  still marker an OR-mixed). Either compound must still carry at least one temporal kenmerk (the actual clip). Any
     *  OTHER kwantor (geen van de / ten hoogste / precies / ten minste &gt;1), a nested compound bullet, or a
     *  non-comparison non-temporal bullet returns null — the compound stays an Onopgelost marker, never a dropped
     *  predicate. */
    private Conditie gedurendeSamengesteld(ToplevelSamengesteldeVoorwaardeContext sv, Bron bron) {
        VoorwaardeKwantificatieContext q = sv.voorwaardeKwantificatie();
        if (q == null || sv.samengesteldeVoorwaardeOnderdeel() == null) return null;
        Conditie.Kwantor kwantor;
        if (q.ALLE() != null) kwantor = Conditie.Kwantor.ALLE;
        else if (q.TEN_MINSTE() != null && q.telwoord() != null && telwoordWaarde(q.telwoord()) == 1)
            kwantor = Conditie.Kwantor.TEN_MINSTE;                       // "ten minste 1" = OR (union)
        else return null;                                               // de / geen van de / ten hoogste / precies / ten minste >1
        List<Conditie> delen = new ArrayList<>();
        int temporeel = 0;
        for (GenesteVoorwaardeContext g : sv.samengesteldeVoorwaardeOnderdeel().genesteVoorwaarde()) {
            if (g.elementaireVoorwaarde() == null) return null;         // a nested compound bullet -> marker
            ElementaireVoorwaardeContext ev = g.elementaireVoorwaarde();
            Conditie.Kenmerk k = temporeelKenmerkUitOperator(ev.toplevelOperator(), ev.nonToplevelOperator(), bron(ev));
            if (k != null) { delen.add(k); temporeel++; continue; }
            // §8.4 a non-temporal member (a COMPARISON) lowers to a static value-side guard. In an ALLE compound it
            // AND-gates the clipped totaal; in a "ten minste 1" (OR) compound a TRUE static member instead WIDENS the
            // result to the full value span (the transpiler branches on the kwantor). The WRITE producers still marker
            // an OR-mixed (conditieTijdreeks returns null) — only the totaal handles it.
            Conditie.Vergelijking v = nietTemporeleVergelijking(ev);
            if (v == null) return null;                                 // a non-comparison non-temporal bullet -> marker
            delen.add(v);
        }
        if (delen.size() < 2 || temporeel == 0) return null;           // a single bullet is the elementaire path; a pure-static gedurende is not a clip
        return new Conditie.Samengesteld(kwantor, 1, delen, bron);
    }

    /** §8.4 a NON-temporal COMPARISON bullet of a mixed ALLE gedurende ("zijn geboortedatum is eerder dan 1-1-2002")
     *  built as a {@link Conditie.Vergelijking} (reusing RuleChecker's shared comparison-operator mapping), or null
     *  when the bullet is not a plain comparison (a predicate / kenmerk / role membership — deferred) OR when an
     *  operand is itself a TEMPORAL attribute (§8.4 admits the static member precisely because it "heeft geen
     *  verschillende waarden op verschillende momenten"; a comparison on a List&lt;Segment&gt; read is time-varying and
     *  would lower to a List-vs-scalar term that fails the whole DRL build — reject so the compound markers). */
    private Conditie.Vergelijking nietTemporeleVergelijking(ElementaireVoorwaardeContext ev) {
        Expr links = ev.expressie() != null ? bouw(ev.expressie()) : null;
        if (links == null) return null;
        Conditie.Vergelijking v = null;
        if (ev.toplevelOperator() != null && ev.toplevelOperator().vergelijkingsTekenGesplitst() != null
                && ev.toplevelOperator().expressie() != null)
            v = vergelijking(RuleChecker.naarOperator(RuleChecker.cmpOf(ev.toplevelOperator().vergelijkingsTekenGesplitst())),
                    links, bouw(ev.toplevelOperator().expressie()), bron(ev));
        else if (ev.nonToplevelOperator() != null && ev.nonToplevelOperator().vergelijkingsTeken() != null
                && ev.nonToplevelOperator().expressie() != null)
            v = vergelijking(RuleChecker.naarOperator(RuleChecker.cmpOf(ev.nonToplevelOperator().vergelijkingsTeken())),
                    links, bouw(ev.nonToplevelOperator().expressie()), bron(ev));
        if (v == null || temporeelAttribuutOperand(v.links()) || temporeelAttribuutOperand(v.rechts())) return null;
        return v;
    }

    /** Whether an operand READS a TEMPORAL (tijdlijn) attribute ANYWHERE — its runtime value is a {@code List<Segment>},
     *  not a scalar, so it cannot be a §8.4 static comparison member. Recurses through arithmetic wrappers (a temporal
     *  read nested in "X plus 1" or an afronding/begrenzing is just as time-varying), so the whole comparison markers
     *  rather than leaking a List into a scalar guard term (which would break the DRL build). */
    private static boolean temporeelAttribuutOperand(Expr e) {
        if (e instanceof Expr.AttribuutRef a)
            return a.objecttype() != null && a.objecttype().granulariteitVan(a.attribuut()) != null;
        if (e instanceof Expr.Binair b) return temporeelAttribuutOperand(b.links()) || temporeelAttribuutOperand(b.rechts());
        if (e instanceof Expr.Afgerond af) return temporeelAttribuutOperand(af.basis());
        if (e instanceof Expr.Begrensd bg) return temporeelAttribuutOperand(bg.basis())
                || (bg.minimum() != null && temporeelAttribuutOperand(bg.minimum()))
                || (bg.maximum() != null && temporeelAttribuutOperand(bg.maximum()));
        if (e instanceof Expr.DimensieCel dc) return temporeelAttribuutOperand(dc.attribuut());
        return false;
    }

    /** The integer value of a §13.4.14 telwoord (a digit literal or een/twee/drie/vier), or -1 if unrecognised. */
    private int telwoordWaarde(TelwoordContext t) {
        if (t.NUMBER() != null) try { return Integer.parseInt(t.NUMBER().getText()); } catch (NumberFormatException e) { return -1; }
        if (t.EEN_TELWOORD() != null) return 1;
        if (t.TWEE_TELWOORD() != null) return 2;
        if (t.DRIE_TELWOORD() != null) return 3;
        if (t.VIER_TELWOORD() != null) return 4;
        return -1;
    }

    /** Every marker this method returns carries {@code reden = null}: its single caller ({@link
     *  #bouwFunctie}) tests the result for {@link Conditie.Onopgelost} and raises the WARNING, so the
     *  survivor report would only repeat it. */
    private Conditie aggregatieFilter(FunctieAanroepContext f, Objecttype elem, List<Expr> argumenten) {
        if (elem == null) return null;
        java.util.List<ParseTree> operanden = new ArrayList<>();
        for (int i = 0; i < f.getChildCount(); i++) {
            ParseTree ch = f.getChild(i);
            if (ch instanceof ExpressieContext || ch instanceof PrimaireExpressieContext) operanden.add(ch);
        }
        if (operanden.size() == 1) {
            PredicaatContext p = vindDie(operanden.get(0));
            if (p == null) return null;
            verbruikt.add(p);
            // §13.4.13.4 the COMPOUND subselectie "die aan alle volgende voorwaarden voldoen: …" — resolve its bullets
            // against the ELEMENT (via the RuleChecker callback) into a Conditie.Samengesteld on the source pattern.
            if (p.samengesteldPredicaat() != null) {
                Conditie c = samengesteldeFilter != null ? samengesteldeFilter.resolve(p.samengesteldPredicaat(), elem) : null;
                return c != null ? c : new Conditie.Onopgelost(bron(p), null);
            }
            Conditie filter = elementKenmerkFilter(p, elem);
            // §5.6 getalpredicaat "die <op> <expr>" (e.g. "die kleiner zijn dan 18"): a comparison of the AGGREGATED
            // attribute (the LHS the operator folds) -> a Conditie.Vergelijking the accumulate source pattern constrains.
            if (filter == null) filter = elementVergelijking(p, elem, argumenten);
            return filter != null ? filter : new Conditie.Onopgelost(bron(p), null);
        }
        // §5.8.1 union telling "het aantal X, Y en Z": the count is the UNION of the per-operand
        // sets — a disjunctively-filtered single accumulate. OR the per-operand kenmerk filters over
        // the shared element (an instance in several sets matches the OR once, so it is counted
        // once). Any operand without a resolvable kenmerk filter (a comparison, an unfiltered set,
        // a different element) leaves the whole union Onopgelost -> a marker, never a wrong count.
        // (A min/max value-fold reaches here too with >1 operand, but its die is on a sub-operand
        // navigation, not the element — so it is excluded; only AANTAL unions.)
        //
        // NOT the §5.7 route any more, and dead for every concatenation that RESOLVES (2026-09-20,
        // card unie-telling). A resolving concatenation becomes an InstanceSetUnion in bouwFunctie,
        // which runs BEFORE this method, so what still reaches this branch is a concatenation
        // bouwInstantieUnie REFUSED. The OR-filter form it builds is deliberately not a second
        // spelling of the union (IR-SPEC ch. 4.3); what it can still reach never reaches disk.
        //
        // The branch is not DELETED, and that was measured rather than assumed (fixronde
        // 2026-09-22): removing it shifts the marker-reason census MarkerRedenPinTest pins (3 -> 2)
        // and costs the `verbruikt` bookkeeping its consumption of the operands' subselecties,
        // which then report themselves as silently dropped — three diagnoses where the ERROR had
        // said it once. What DID move is the noise: it used to raise a "die/dat wordt nog niet
        // omgezet" WARNING on a refused concatenation that carried no die/dat at all. The guard
        // below asks the question only when an operand really writes one, so the WARNING is now
        // true wherever it fires and silent where it has nothing to report; a sentence WITH an
        // unresolvable subselectie keeps both its ERROR and its WARNING (measured, both probes).
        if (f instanceof AantalFuncContext) {
            // No operand writes a subselectie at all: there is nothing to OR and nothing to report, so
            // this branch has no business producing a marker whose only effect is a WARNING about a
            // die/dat the sentence does not carry. Null is "no filter", which is exactly true here.
            boolean heeftDie = false;
            for (ParseTree op : operanden) if (vindDie(op) != null) heeftDie = true;
            if (!heeftDie) return null;
            java.util.List<Conditie> delen = new ArrayList<>();
            // account for EVERY operand's subselectie before the first one can bail out: the marker below
            // refuses the whole union, so the later operands' subselecties are refused with it and must
            // not read as silently dropped
            for (ParseTree op : operanden) {
                PredicaatContext p = vindDie(op);
                if (p != null) verbruikt.add(p);
            }
            for (ParseTree op : operanden) {
                // Every operand must range over the SAME element (a single accumulate has one source
                // pattern) AND resolve to a kenmerk filter of it. An operand over a DIFFERENT element
                // — even when it shares a kenmerk name — would otherwise have its foreign set silently
                // dropped and its filter OR-ed onto the wrong fact, so it leaves the whole union a marker.
                PredicaatContext p = vindDie(op);
                Conditie k = p != null && operandElement(op) == elem ? elementKenmerkFilter(p, elem) : null;
                if (k == null) return new Conditie.Onopgelost(bron(f), null);
                delen.add(k);
            }
            return new Conditie.Samengesteld(Conditie.Kwantor.TEN_MINSTE, 1, delen, bron(f));
        }
        return null;
    }

    /**
     * §5.6 / IR-SPEC ch. 4.5: the "die/dat &lt;predicaat&gt;" subselectie over the range of a §8.2
     * QUANTIFIER FOLD — the same field, the same reading and the same resolution as an aggregation's
     * (see {@link #aggregatieFilter}), reached from {@link RuleChecker}'s lowering instead of from a
     * functieaanroep. Returns null when the operand writes no subselectie at all, a resolved
     * {@link Conditie} when it writes one this resolver binds, and {@link Conditie.Onopgelost} when it
     * writes one it does not — the caller then DECLINES to lower, because a fold over the full range is
     * exactly the silent wrong set the field exists to prevent.
     *
     * <p>{@code operand} is the parse subtree of the QUANTIFIED operand specifically, never the whole
     * condition: the subselectie is written after the onderwerp ("… van alle vondsten van de Jutter die
     * aangemeld zijn") but selects the quantified MEMBERS, so it must be looked for on the side the
     * quantifier is on. A die on the other operand belongs to that operand's own navigation.
     */
    /** The outcome of resolving a §5.6 subselectie on the quantifier-fold position: exactly one of the
     *  two is non-null. {@code reden} is the sentence the caller's refusal quotes, so the refusal names
     *  the axis the subselectie was declined on rather than one generic text for every shape. */
    record Deelselectie(Conditie filter, String reden) {}

    /**
     * Give a subselectie back to {@code keurDeelselecties}. {@link #deelselectie} books the predicate as
     * CONSUMED before it knows whether the fold will take it, and on the COMPARISON route that is right:
     * a declined fold leaves the set reading standing, so WF-096 raises the ERROR that blocks the
     * document and the subselectie is refused WITH it. On the MEMBERSHIP route there is no set reading
     * and hence no WF-096 — a declined fold leaves nothing but a marker — so the predicate has to go
     * back on the refusal list or the sentence falls silent, which is the one outcome
     * {@code keurDeelselecties} exists to prevent ("ERROR, not WARNING, and the difference is the
     * point"). Measured regression, fixed 2026-09-19.
     */
    void geefDeelselectieTerug(ParseTree operand) {
        if (operand == null) return;
        PredicaatContext p = vindDie(operand);
        if (p != null) verbruikt.remove(p);
    }

    Deelselectie deelselectie(ParseTree operand, Objecttype elem, Expr gekwantificeerd) {
        if (elem == null || operand == null) return null;
        PredicaatContext p = vindDie(operand);
        if (p == null) return null;
        verbruikt.add(p);
        if (p.samengesteldPredicaat() != null) {
            Conditie c = samengesteldeFilter != null ? samengesteldeFilter.resolve(p.samengesteldPredicaat(), elem) : null;
            return c != null ? new Deelselectie(c, null)
                    : new Deelselectie(null, "zij bindt geen samengestelde voorwaarde van " + elem.naam
                            + " (§13.4.13.4, IR-SPEC ch. 4.5)");
        }
        Conditie filter = elementKenmerkFilter(p, elem);
        if (filter != null) return new Deelselectie(filter, null);
        // §5.6 getalpredicaat "die <op> <expr>" names no attribute of its own, so elementVergelijking
        // re-anchors the FOLD's operand as its left side. When that operand is TIJDSAFHANKELIJK the filter
        // has no reading — a timeline in a scalar slot (ch. 3.3) — and it is refused below. That used to be
        // the case BY CONSTRUCTION on this position, because the §8.2 qualifier was what admitted the fold
        // at all and §8.2 only qualifies a temporal onderwerp; since the unqualified fold landed
        // (2026-09-19) the matrix is an ordinary condition and the operand is usually scalar, so the filter
        // binds here. The refusal was always keyed to the ATTRIBUTE's granularity rather than to the
        // position, so it needed no change — only its reason had to stop claiming the position.
        // A MEMBERSHIP fold (the kenmerkcheck over a quantified subject) quantifies no attribute at all,
        // so there is no operand for a getalpredicaat to re-anchor on; it falls into the reason below.
        filter = elementVergelijking(p, elem, gekwantificeerd == null ? List.of() : List.of(gekwantificeerd));
        if (filter == null)
            return new Deelselectie(null, "zij bindt geen kenmerk of getalpredicaat van " + elem.naam
                    + " (§5.6, IR-SPEC ch. 4.5)");
        if (gekwantificeerd instanceof Expr.AttribuutRef q && q.objecttype() != null
                && q.objecttype().granulariteitVan(q.attribuut()) != null)
            return new Deelselectie(null, "een getalpredicaat over het gekwantificeerde attribuut '"
                    + q.attribuut() + "' heeft hier geen lezing: dat attribuut is TIJDSAFHANKELIJK, en een "
                    + "tijdlijn in een scalaire sleuf heeft er geen (ch. 3.3) — de knoop draagt de vorm wel, "
                    + "over een niet-temporeel attribuut levert deze schrijfpositie hem ook");
        return new Deelselectie(filter, null);
    }

    /** The element type a count operand ("deelnemers van …") ranges over, or null when it is not an
     *  "&lt;attribuut&gt; van &lt;onderwerp&gt;" naming a countable element — used to confirm every operand
     *  of a §5.8.1 union shares one element before OR-ing their filters. */
    private Objecttype operandElement(ParseTree op) {
        if (op instanceof PrimaireExpressieContext pe && pe.attribuutVanOnderwerp() != null
                && pe.attribuutVanOnderwerp().attribuutMetLidwoord() != null
                && pe.attribuutVanOnderwerp().attribuutMetLidwoord().kaleNaam() != null)
            return aantalElement(src(pe.attribuutVanOnderwerp().attribuutMetLidwoord().kaleNaam()));
        return null;
    }

    /** Why the §5.7 union route did not open for the count being built — appended to
     *  {@link #tellingAriteitFout}'s refusal, so one sentence says both what the wire refuses (an
     *  operand list under a count) and why the form that WOULD have carried this sentence does not
     *  apply. Set by {@link #bouwInstantieUnie} on every null return and read immediately after, the
     *  {@code aggregatieReden} contract the Drools backend uses on the same kind of question. */
    private String unieWeigering;

    /** §5.7/§4.11 build the CONCATENATION of subject expressions under "het aantal" as an
     *  {@link Expr.InstanceSetUnion} — one selection per operand, each with its own population, its
     *  own one-step relation and its own die/dat subselection. Null when the route does not open,
     *  with {@link #unieWeigering} carrying the reason unless the operands are not subject
     *  expressions at all (there the arity refusal already says everything, in the words it has said
     *  since 2026-09-20).
     *
     *  <p>What it does NOT build: a union over two POPULATIONS (WF-110 (d)), a selection whose
     *  relation is not uniquely derivable (WF-110 (e) — a union names each selection's relation and
     *  never derives one from the type pair, because every selection here shares one pair), and a
     *  selection whose "die/dat" does not resolve to a characteristic of the element. That last one
     *  is a refusal rather than a dropped filter: a silently unfiltered selection counts the wrong
     *  number, and the union ABSORBS it (an unfiltered selection is a superset of every filtered
     *  one over the same relation), so the error would be invisible in the answer. */
    private Expr bouwInstantieUnie(FunctieAanroepContext f) {
        unieWeigering = null;
        java.util.List<ParseTree> operanden = new ArrayList<>();
        for (int i = 0; i < f.getChildCount(); i++) {
            ParseTree ch = f.getChild(i);
            if (ch instanceof ExpressieContext || ch instanceof PrimaireExpressieContext) operanden.add(ch);
        }
        if (operanden.size() < 2) return null;
        Objecttype gedeeld = null;
        java.util.List<Expr.InstanceSetUnion.Tak> takken = new ArrayList<>();
        java.util.List<PredicaatContext> gezien = new ArrayList<>();
        for (int i = 0; i < operanden.size(); i++) {
            ParseTree op = operanden.get(i);
            Objecttype el = operandElement(op);
            if (el == null) return null;          // a VALUE operand: the arity refusal owns this sentence
            if (gedeeld == null) gedeeld = el;
            else if (gedeeld != el) {
                unieWeigering = "de selecties tellen over verschillende populaties ('" + gedeeld.naam
                        + "' en '" + el.naam + "'); één telling antwoordt een aantal over ÉÉN gedeclareerd"
                        + " type, en of twee selecties dan een instantie kunnen delen is een vraag over de"
                        + " subtypelattice die de deduplicatie zou moeten beantwoorden (WF-110 (d))";
                return null;
            }
            String woord = geschrevenElementWoord(op);
            RegelSpraakModel.RolBindingRef rb =
                    huidigSubject == null || woord == null ? null : model.elementRolBinding(huidigSubject, woord);
            // The written name did not bind. That is not yet a refusal: elementRolBinding answers the
            // BY-NAME question and answers it narrowly on purpose — only a declared singular or a
            // declared (mv:) plural binds, never a guessed one — and the §13.3.9.1 cardinality sentence
            // ("Eén vinder meldt meerdere vondsten") writes a plural the model does NOT register as the
            // role's declared one. Measured 2026-09-22: it sets `meervoudig` and drops the spelling, and
            // registering it instead would both move a golden's bytes and destroy the WF-034 witness
            // `jutterswet-kaveltelling`, whose whole point is that such a plural does not bind.
            //
            // So fall back to the TYPE PAIR — the unique fact-type role reaching the element from the
            // subject — which is exactly the derivation the ONE-selection count has always leaned on
            // (WF-048's model, and what every executor runs for a bindingless fold). WF-110 (e) is
            // unmoved by this: it forbids the WIRE from omitting the relation, so that a consumer never
            // derives one; how the PRODUCER arrives at the relation it writes is the producer's own
            // business, and it writes the result out explicitly either way.
            if (rb == null && huidigSubject != null) rb = model.verbindendeRolBinding(huidigSubject, el);
            if (rb == null) {
                unieWeigering = "de relatie van selectie " + (i + 1) + " ('" + woord + "') is niet"
                        + " eenduidig af te leiden; elke selectie van een unie noemt haar eigen relatie,"
                        + " want ze delen alle één typenpaar en een afgeleide reikwijdte zou ze"
                        + " ononderscheidbaar maken (WF-110 (e))";
                return null;
            }
            PredicaatContext p = vindDie(op);
            Conditie filter = null;
            if (p != null) {
                filter = elementKenmerkFilter(p, el);
                if (filter == null) {
                    unieWeigering = "de 'die/dat'-subselectie van selectie " + (i + 1) + " ('" + src(p)
                            + "') lost niet op naar een kenmerk van '" + el.naam + "'; een stilzwijgend"
                            + " weggelaten filter zou een te groot aantal tellen en de unie zou dat"
                            + " opslokken (§5.6)";
                    return null;
                }
                gezien.add(p);
            }
            takken.add(new Expr.InstanceSetUnion.Tak(el, rb, filter, bron((ParserRuleContext) op)));
        }
        verbruikt.addAll(gezien);   // only once the whole union stands: a refused union hands its die back
        return new Expr.InstanceSetUnion(java.util.List.copyOf(takken), bron(f));
    }

    /** The written element word of a count operand ("aangemelde vondsten" in "aangemelde vondsten van
     *  de Jutter") — the name {@link RegelSpraakModel#elementRolBinding} resolves the selection's
     *  relation from, and the same word {@link #operandElement} resolves its population from. */
    private String geschrevenElementWoord(ParseTree op) {
        if (op instanceof PrimaireExpressieContext pe && pe.attribuutVanOnderwerp() != null
                && pe.attribuutVanOnderwerp().attribuutMetLidwoord() != null
                && pe.attribuutVanOnderwerp().attribuutMetLidwoord().kaleNaam() != null)
            return src(pe.attribuutVanOnderwerp().attribuutMetLidwoord().kaleNaam());
        return null;
    }

    /** The first "die/dat &lt;predicaat&gt;" subselectie anywhere in an operand subtree. */
    private PredicaatContext vindDie(ParseTree t) {
        if (t instanceof OnderwerpKetenContext k && k.predicaat() != null) return k.predicaat();
        for (int i = 0; i < t.getChildCount(); i++) {
            PredicaatContext r = vindDie(t.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** A "die een &lt;kenmerk&gt; zijn" subselectie resolved against the counted element type: the
     *  predicaat's name matched (case-insensitively) to a declared kenmerk of the element, as a
     *  {@link Conditie.Kenmerk}; null when the predicaat is not such a kenmerk of the element (a
     *  comparison, an objecttype/role membership, an unknown name — all left to the marker path). */
    private Conditie elementKenmerkFilter(PredicaatContext p, Objecttype elem) {
        if (elem == null) return null;
        // §13.4.13.5-.8: a kenmerk membership predicate is "(een) <kenmerk> is/zijn" — the spec's
        // verb-trailing toplevelOperator register ("die een minderjarige zijn") — OR, in the permissive
        // verb-leading register, "is/zijn (een) <kenmerk>". Either way the kaleNaam is the kenmerk and the
        // verb is is/zijn. A "heeft/hebben een <X>" predicaat is a possession/role query ("die een kind
        // hebben" / "die heeft een kind" = that HAVE a child), a different predicate class — not the
        // element's boolean kenmerk; its kaleNaam carries no is/zijn here, so it falls through to the marker.
        String naamBron = null;
        boolean heeftPeriode = false;
        if (p.toplevelOperator() != null) {                  // EEN? kaleNaam (IS | HEEFT | ZIJN | HEBBEN)
            ToplevelOperatorContext op = p.toplevelOperator();
            if (op.kaleNaam() != null && (op.IS() != null || op.ZIJN() != null)) naamBron = src(op.kaleNaam());
            heeftPeriode = op.gehelePeriodevergelijking() != null;
        } else if (p.nonToplevelOperator() != null) {        // (IS | HEEFT | ZIJN | HEBBEN) EEN? kaleNaam, or EEN kaleNaam (IS | ZIJN)
            NonToplevelOperatorContext op = p.nonToplevelOperator();
            if (op.kaleNaam() != null && (op.IS() != null || op.ZIJN() != null)) naamBron = src(op.kaleNaam());
            heeftPeriode = op.gehelePeriodevergelijking() != null;
        }
        if (naamBron == null) return null;
        // §8.2: a "(niet) gedurende de/het gehele <periode>" whole-period qualifier on a subselectie element kenmerk is
        // NOT yet lowered onto the accumulate source — MARKER it (return null -> the caller markers the whole subselectie)
        // rather than SILENTLY binding a static kenmerk check that drops the §8.2 semantics. Both back-ends decline
        // symmetrically (no-silent-drop). The unqualified kenmerk subselectie ("die een minderjarige zijn") fires as before.
        if (heeftPeriode) return null;
        // §5.6: the written form may be the kenmerk's inflected/nominalized spelling
        // ("die een minderjarige zijn" against the declared "minderjarig") — bind it morphologically.
        String kenmerk = KenmerkMatcher.vind(elem, naamBron);
        return kenmerk != null ? new Conditie.Kenmerk(kenmerk, bron(p)) : null;
    }

    /** §5.6/§13.4.13.5-.7 a getal/datum/tekst elementairpredicaat "die &lt;op&gt; &lt;expr&gt;" (the vragende-meervoud
     *  "die kleiner zijn dan 18", or the permissive verb-leading register) -> a {@link Conditie.Vergelijking} over the
     *  element's AGGREGATED attribute. The subselectie names no attribute, so the LHS is the very attribute the
     *  aggregation folds (the single AttribuutRef operand of the element, e.g. "de leeftijd van alle deelnemers"); the
     *  comparison is then constrained on the accumulate's source pattern. A count over the bare element (no folded
     *  attribute), a navigated/set-of operand, or a non-comparison predicaat returns null -> the caller markers. The
     *  RHS is resolved in the rule's subject context (a literal or a parameter-global — not an element attribute). */
    private Conditie elementVergelijking(PredicaatContext p, Objecttype elem, List<Expr> argumenten) {
        if (argumenten.size() != 1 || !(argumenten.get(0) instanceof Expr.AttribuutRef agg)
                || agg.objecttype() != elem || agg.navigatieRol() != null) return null;
        Typering.CmpOp cmp; ExpressieContext rhsCtx;
        if (p.toplevelOperator() != null && p.toplevelOperator().vergelijkingsTekenGesplitst() != null
                && p.toplevelOperator().expressie() != null) {
            cmp = RuleChecker.cmpOf(p.toplevelOperator().vergelijkingsTekenGesplitst());
            rhsCtx = p.toplevelOperator().expressie();
        } else if (p.nonToplevelOperator() != null && p.nonToplevelOperator().vergelijkingsTeken() != null
                && p.nonToplevelOperator().expressie() != null) {
            cmp = RuleChecker.cmpOf(p.nonToplevelOperator().vergelijkingsTeken());
            rhsCtx = p.nonToplevelOperator().expressie();
        } else return null;
        Expr rhs = bouw(rhsCtx);
        // §5.6: the predicate ranges over the SELECTED ELEMENT, but the RHS is bouw()'d in the rule's SUBJECT context.
        // A subject/element attribute mis-binds ("zijn drempel" -> the subject's drempel, not the element's), so reject
        // an AttribuutRef. Accept a LITERAL (both back-ends lower it as a fact-only source constraint) OR a §13.3.8
        // Parameter — the spec's worked-example RHS "die kleiner zijn dan de volwassenleeftijd". A Parameter is
        // context-independent (a session global in both back-ends), so it does not mis-bind. Anything else stays an
        // honest, symmetric marker.
        if (!(rhs instanceof Expr.Literal) && !(rhs instanceof Expr.ParameterRef)) return null;
        // WF-091 (reference-type identity, IR-SPEC ch. 4.1): the one VALUE-position reference whose type this builder
        // copies from another node instead of looking the declaration up. It invents nothing: it COPIES the fold
        // source, re-anchored on the element (onderwerpBron/bron reset, and set=false — the LHS reads ONE element,
        // not the set), carrying that node's (objecttype, attribuut, type) triple verbatim — so it carries no deviation the
        // source does not already have, and the verifier checks it at its own use site like any other reference.
        // Deliberately NOT the aggregation's own type: §4.20 strips the noemer off "het totaal van <€/mnd>" (€), while
        // the filter compares the unstripped per-element value (€/mnd). Re-looking the declaration up here would be
        // LESS safe: it could let this term and the accumulate's fold source drift apart, and both back-ends need them
        // to denote ONE read of ONE attribute.
        Expr lhs = new Expr.AttribuutRef(agg.attribuut(), elem, "", null, agg.type(), bron(p));
        // §5.6 getal/datum/tekstpredicaat: enforce the typeringen comparison legality (as the normal voorwaarde path
        // does), so a type-mismatched filter (a Numeriek attribute vs a Tekst literal) is an ERROR, not silent bad DRL.
        Typering.Resultaat tr = Typering.vergelijk(cmp, lhs.type(), rhs.type());
        if (!tr.ok()) {
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.ERROR,
                    "subselectie-predicaat: " + tr.fout(), p.getStart().getLine()));
            return null;
        }
        return vergelijking(RuleChecker.naarOperator(cmp), lhs, rhs, bron(p));
    }

    /** §4.12-4.15: type-check the operand list of a value/date aggregation. min/max demand all
     *  numeriek with inter-convertible units (Tabel 22/23); eerste/laatste demand all datum with
     *  one tijd-precisie (Tabel 24/25). Other functies (sum/total parse a single arithmetic
     *  operand, the date-component extractors, etc.) carry no such operand-list contract here. */
    private void meldAggregatie(Expr.FunctieKind kind, List<Expr> argumenten, FunctieAanroepContext f) {
        List<Datatype> typen = argumenten.stream().map(Expr::type).toList();
        String fout = switch (kind) {
            case MINIMUM -> Typering.getalAggregatie("minimale waarde van", typen);
            case MAXIMUM -> Typering.getalAggregatie("maximale waarde van", typen);
            // §4.10 Tabel 21 (som van): operands "moeten wel allemaal numeriek zijn" — the strict Numeriek
            // reading, DECIDED consistent with MIN/MAX (Tabel 22/23, identical wording): the spec adds an
            // explicit Percentage row + prose exactly where it admits one (§5.1/§5.3 comparison tables) and
            // explicitly excludes Percentage where it means to (§6.7 maal) — Tabel 21 does neither. The
            // "gespecialiseerd numeriek datatype" sentence (§3.3.5) does not override the tables (see
            // grammar-divergences). A Percentage som-operand is now a type error, no longer silently summed.
            case SOM -> Typering.getalAggregatie("som van", typen);
            // §4.6 Tabel 16 (wortel van) / §4.9 Tabel 20 (absolute waarde van): the single operand must be Numeriek.
            // WORTEL always types Numeriek regardless of its radicand, so "de wortel van <tekst>" produced a Numeriek
            // result with NO diagnostic; ABSOLUTE_WAARDE propagates the operand type, so "absolute waarde van (<datum>)"
            // leaked a datum unless a downstream assignment happened to reject it. Both now reject a concrete
            // non-numeriek operand at the operator (Onbekend skipped, no claim).
            case WORTEL -> Typering.getalAggregatie("de wortel van", typen);
            case ABSOLUTE_WAARDE -> Typering.getalAggregatie("de absolute waarde van", typen);
            // §4.19 Tabel 29 (eerste paasdag van): "één numerieke expressie zonder eenheid ... altijd een geheel getal
            // met 4 cijfers". EERSTE_PAASDAG always types Datum, so an illegal operand produced a Datum result with no
            // diagnostic.
            case EERSTE_PAASDAG -> paasdagOperandFout(typen);
            // §4.20 Tabel 30 (totaal van / aggregeren in de tijd): the operand "moet numeriek zijn met een
            // eenheid per tijdseenheid" — the NUMERIEK part via getalAggregatie (like MIN/MAX), the rate part
            // via totaalOperandFout: a unit-BEARING operand must carry a tijdseenheid denominator; a UNITLESS
            // operand is admitted, because the spec's own §7.1 worked example totals a unitless Bedrag (the
            // temporality lives in the timeline, not the unit) — see grammar-divergences.
            case TOTAAL -> totaalOperandFout(argumenten);
            case EERSTE  -> Typering.datumAggregatie("eerste van", typen);
            case LAATSTE -> Typering.datumAggregatie("laatste van", typen);
            // §6.12 / §13.4.17.21-.24: a time-of-day component is defined only on a datum-TIJD — a plain
            // "Datum in dagen" has no hour/minute/second. The DATE parts (jaar/maand/dag) accept any date, so
            // they are NOT checked. Onbekend is skipped (no claim); only a concrete DatumInDagen is a violation.
            case UUR_UIT, MINUUT_UIT, SECONDE_UIT, MILLISECONDE_UIT -> tijdComponentFout(typen);
            // §4.16/§4.17 typeringen: the two operands of a (absolute) tijdsduur must BOTH be datum AND have the
            // SAME tijd-precisie.
            case TIJDSDUUR, ABSOLUTE_TIJDSDUUR -> tijdsduurPrecisieFout(kind, typen);
            // WF-038's scope sentence, spelled out rather than left to the default arm: the filled
            // count is NOT a numeric fold. Its test is presence, so every operand type counts
            // (decision G8, RUNTIME-SEMANTICS ch. 3.4c) — a getalAggregatie check here would
            // silently undo that by refusing a tekst/datum/enumeratie/booleaan source.
            case AANTAL_GEVULDE -> null;
            // WF-037's count-arity arm (IR-SPEC ch. 4.3, maintainer decision 2026-09-20). The one arm
            // in this switch about SHAPE rather than operand TYPES, and it sits here because this is
            // the per-kind aggregation diagnosis and the arity is known at the node. It sits beside
            // the filled count's arm above and never reaches it: that kind carries its own arity law
            // (exactly ONE argument, WF-037's other new arm), so the two counts are fenced apart.
            case AANTAL -> tellingAriteitFout(argumenten, f);
            default -> null;
        };
        if (fout != null)
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(
                    RegelSpraakModel.Severity.ERROR, fout, f.getStart().getLine()));
    }

    /** WF-037's count-arity arm (IR-SPEC ch. 4.3, maintainer decision 2026-09-20): a count folds
     *  INSTANCES and has no LISTED form, so two or more operands are ill-formed on the wire.
     *
     *  <p><b>This fence closes a LEAK of this frontend's own making.</b> The source language spells no
     *  operand list for a count — §5.8.1 ranges it over instances and the typing document (§4.11)
     *  excludes the value reading — but {@code functieAanroep}'s {@code aantalFunc} alternative reads
     *  {@code primaireExpressie ( COMMA primaireExpressie )* ( EN primaireExpressie )?}, a registered
     *  divergence ({@code grammar/DIVERGENCES.md}), and {@code args} builds every child as its own
     *  argument. Measured 2026-09-20: three sentences reached the wire unblocked — the {@code en} and
     *  {@code comma} spellings with {@code diagnostics: []} and the element-binding spelling with a
     *  non-blocking WARNING — all rejected by the verifier
     *  ({@link io.github.yhavinga.regelspraak.AantalOpgesomdTest}).
     *
     *  <p>ERROR and not WARNING, deliberately: {@link Resolver#irModus} writes nothing when the model
     *  carries an error, so the fail-fast is what keeps the ill-formed document off disk. That is the
     *  level {@code AantalSetbronTest} pins for the set fence one decision earlier, and unlike there
     *  no committed expectation asserts this shape resolves clean — measured, no rgs golden and no
     *  other test carries a listed count.
     *
     *  <p>It reads the KIND and the operand COUNT and never {@code elementObjecttype}: a count that
     *  binds an element AND carries an operand list is the same listed form with a second claim on one
     *  node, and the wire rule draws its boundary in exactly that place. Below two operands it is
     *  silent, which is the wire rule's boundary too: one operand is v0's own instance count and zero
     *  is a fold without a source, a form under no decision yet. */
    private String tellingAriteitFout(List<Expr> argumenten, FunctieAanroepContext f) {
        if (argumenten.size() < 2) return null;
        return "opgesomde telling: '" + bron(f).snippet() + "' noemt " + argumenten.size()
                + " operanden — een telling vouwt de INSTANTIES waar zij over gaat en heeft geen "
                + "opgesomde vorm; de brontaal spelt er geen (§5.8.1 telt over onderwerpexpressies, "
                + "typeringen §4.11 sluit de waardelezing uit), dus er is geen operandenlijst om te "
                + "lezen. De enige lezing die een lijst een telling overlaat is 'tel de gevulde "
                + "operanden', en die betekenis draagt AANTAL_GEVULDE over een setbron — een eigen "
                + "soort op de wire (besluit 2026-09-20) (WF-037, IR-SPEC ch. 4.3)"
                + unieStaart(f);
    }

    /** The §5.7 half of the refusal above, appended so ONE diagnostic says both halves. The head
     *  refuses the operand LIST and says the source language spells none; since 2026-09-20 that is
     *  true of the list and false of the SENTENCE — §5.7 spells a concatenation of subject
     *  expressions and typeringen §4.11 admits it under this very operator — so the head would
     *  mislead on its own. The tail therefore OPENS by naming the apparent contradiction ("wat de
     *  kop over de LIJST zegt is waar; over de ZIN niet"), because a reader who meets "spelt er
     *  geen" and "spelt wél" in one sentence with nothing between them reads a bug, not a
     *  distinction. The concatenation has a wire form of its own (an InstanceSetUnion under
     *  the count, WF-110), and this tail says why that route did not open here: because the operands
     *  are not subject expressions at all, or because {@link #bouwInstantieUnie} refused them for a
     *  reason of its own. */
    private String unieStaart(FunctieAanroepContext f) {
        if (!(f instanceof AantalFuncContext)) return "";
        String staart = ". Wat de kop over de LIJST zegt is waar; over de ZIN niet — de brontaal spelt "
                + "wél een CONCATENATIE van ONDERWERPexpressies (§5.7, typeringen §4.11), en die telt "
                + "instanties in plaats van waarden, dus zij is geen operandenlijst maar een lijst "
                + "SELECTIES: zij bereikt de wire sinds 2026-09-20 als één InstanceSetUnion onder de "
                + "telling (WF-110). Die route opent hier niet — ";
        return staart + (unieWeigering != null ? unieWeigering
                : "niet elke operand is een rolselectie; een concatenatie onder 'het aantal' bestaat "
                        + "uit ONDERWERPexpressies en uit niets anders (typeringen §4.11)");
    }

    /** §4.19 Tabel 29 (eerste paasdag van): the single operand is "een numerieke expressie zonder eenheid ... altijd een
     *  geheel getal met 4 cijfers". Enforce the STATIC part — Numeriek, geheel, geen eenheid (the 4-cijfer VALUE range is
     *  a runtime concern, unknowable for a variable). EERSTE_PAASDAG always types Datum, so an illegal operand ("eerste
     *  paasdag van <tekst>", "… van <EUR getal>", "… van <decimaal>") produced a Datum result with NO diagnostic.
     *  Onbekend is skipped (no claim). "het jaar uit (…)" — the spec's canonical operand — types Numeriek geheel zonder
     *  eenheid, so it passes. */
    private static String paasdagOperandFout(List<Datatype> typen) {
        if (typen.size() != 1 || typen.get(0).isOnbekend()) return null;
        Datatype d = typen.get(0);
        if (!(d instanceof Datatype.Numeriek n))
            return "eerste paasdag van vereist een numerieke expressie (een geheel jaartal), kreeg " + d.naam() + " (§4.19 Tabel 29)";
        // Reject only a DECLARED decimal precision (getal met N decimalen) or a unit — NOT a !geheel() flag. A whole-year
        // ARITHMETIC operand ("het jaar uit X plus 1" = Easter of the next year, or "2024 plus 1") is spec-legal (§6.13:
        // "Het jaartal wordt gespecificeerd door een geheel getal"), but Typering.numeriekBinair types every plus/min as
        // geheel=false (it erases geheel-ness of integer arithmetic), so a !geheel() gate would FALSELY reject it (review
        // a29b3e97). decimalen()>0 keeps rejecting the concrete "getal met 2 decimalen" case while admitting a dec=0
        // arithmetic year (an unbounded "getal" — statically indistinguishable from arithmetic — is likewise admitted; the
        // 4-cijfer value range stays a runtime concern).
        if (n.decimalen() > 0 || n.heeftEenheid())
            return "eerste paasdag van vereist een geheel getal zonder eenheid (een 4-cijferig jaartal), kreeg " + d.naam() + " (§4.19 Tabel 29)";
        return null;
    }

    /** §4.20 Tabel 30 (totaal van): the operand is "numeriek met een eenheid per tijdseenheid". The NUMERIEK
     *  part rides {@link Typering#getalAggregatie}; the RATE part is enforced only when the operand CARRIES a
     *  unit AND is not a §5.1.4 TEMPORAL attribute — a plain unit (EUR) or a non-time denominator (EUR/km)
     *  on a NON-temporal operand is the Tabel-30 fout, while a temporal attribute's per-tijdseenheid
     *  dimension lives in its TIMELINE declaration ("voor elke maand"), not its unit: the spec's own §7.1
     *  worked example totals a timeline-declared Bedrag, so a plain-unit temporal operand must not be
     *  rejected (see grammar-divergences). A unitless operand is likewise admitted; Onbekend makes no claim. */
    private static String totaalOperandFout(List<Expr> argumenten) {
        String f = Typering.getalAggregatie("totaal van", argumenten.stream().map(Expr::type).toList());
        if (f != null) return f;
        for (Expr arg : argumenten) {
            Eenheid e = arg.type().eenheidOf();
            if (e == null || Tijdrekening.chronoUnit(e.noemer()) != null) continue;
            boolean temporeel = arg instanceof Expr.AttribuutRef a && a.objecttype() != null
                    && a.objecttype().granulariteitVan(a.attribuut()) != null;
            if (!temporeel)
                return "totaal van vereist een eenheid per tijdseenheid (§4.20 Tabel 30), kreeg '" + e.weergave() + "'";
        }
        return null;
    }

    /** §6.12 / §13.4.17.21-.24: the operand of a time-of-day component (uur/minuut/seconde/milliseconde uit)
     *  must be a datum-tijd; a plain "Datum in dagen" carries no time. Returns the fout message for a concrete
     *  DatumInDagen operand, else null (Onbekend and any non-date type are not this check's concern). */
    private static String tijdComponentFout(List<Datatype> typen) {
        if (typen.size() != 1 || !(typen.get(0) instanceof Datatype.DatumInDagen)) return null;
        return "een tijd-component (uur/minuut/seconde/milliseconde uit) vereist een datum-tijd, kreeg "
                + typen.get(0).naam() + " (§13.4.17.21-.24)";
    }

    /** §4.16/§4.17 (typeringen, normative): the two operands (van, tot) of a (absolute) tijdsduur must BOTH be a
     *  datumexpressie AND have the SAME tijd-precisie — both {@link Datatype.DatumInDagen} or both {@link Datatype.DatumTijd}.
     *  A non-datum operand (a Numeriek passed where a datum belongs) or a concrete mixed pair (Datum-in-dagen tot
     *  Datum-en-tijd) is spec-illegal but otherwise silently emits code neither back-end can run (rsTijdsduur wants a
     *  Temporal). Onbekend is skipped (no claim). Returns the diagnostic (citing §4.16 for tijdsduur, §4.17 for absolute), or
     *  null. */
    private static String tijdsduurPrecisieFout(Expr.FunctieKind kind, List<Datatype> typen) {
        String sectie = kind == Expr.FunctieKind.ABSOLUTE_TIJDSDUUR ? "§4.17" : "§4.16";
        Datatype ref = null;
        for (Datatype d : typen) {
            if (d.isOnbekend()) continue;                       // no claim
            if (!d.isDatum())                                   // §4.16/§4.17: both operands must be a datumexpressie
                return "de (absolute) tijdsduur van … tot … vereist twee datumexpressies, kreeg " + d.naam() + " (" + sectie + ")";
            if (ref == null) ref = d;
            else if (!ref.getClass().equals(d.getClass()))      // both datum but a different tijd-precisie
                return "de (absolute) tijdsduur van … tot … vereist dat beide expressies dezelfde tijd-precisie hebben, kreeg "
                        + ref.naam() + " en " + d.naam() + " (" + sectie + ")";
        }
        return null;
    }

    /** The §5.8.2 "of … als die er niet zijn" fallback expression of a sommatie, or null.
     *  Only "som van" carries it: an empty "som van" is a leegwaarde unless this overrides
     *  it (§4.10), whereas an empty "totaal van" is unconditionally 0 (§4.20, no fallback). */
    private Expr leegwaardeVan(FunctieAanroepContext f) {
        if (f instanceof SomFuncContext s && s.leegwaardeAlternatief() != null)
            return bouw(s.leegwaardeAlternatief().expressie());
        return null;
    }

    /** §13.4.17.32 "(absolute) tijdsduur van A tot B in [hele] <eenheid>": a number of
     *  that unit (whole when "hele" is present). Unit normalisation makes "jaren"
     *  convertible with a declared "jr". */
    private Datatype tijdsduurType(TijdsduurFuncContext t) {
        io.github.yhavinga.regelspraak.model.Eenheid e = t.eenheidMeervoud() != null
                ? model.resolveEenheid(src(t.eenheidMeervoud())) : null;
        return new Datatype.Numeriek(t.HELE() != null, 0, Datatype.Teken.ONBEPAALD, e);
    }

    /**
     * The object type an aggregation ranges over. For value-aggregations
     * (sum/min/max/first/last) it is the object the aggregated attribute belongs to
     * — the argument's bound AttribuutRef objecttype. For a count "het aantal
     * <rol> van <onderwerp>" the parser makes <rol> the attribute name, so the
     * counted element type is that role resolved to its object type.
     */
    private Objecttype aggregatieElement(Expr.FunctieKind kind, List<Expr> args) {
        if (args.isEmpty() || !(args.get(0) instanceof Expr.AttribuutRef a)) return null;
        return switch (kind) {
            // AANTAL_GEVULDE rides the VALUE-fold derivation, not the count's: its argument is an
            // ATTRIBUTE read over a set, so the element type is the attribute's owner — the very
            // asymmetry RuleChecker's VOUWSOORTEN note names (a count's element word is a ROLE)
            case SOM, TOTAAL, MINIMUM, MAXIMUM, EERSTE, LAATSTE, AANTAL_GEVULDE -> a.objecttype();
            case AANTAL -> aantalElement(a.attribuut());
            default -> null;
        };
    }

    /**
     * The object type a count "het aantal &lt;X&gt; van &lt;onderwerp&gt;" ranges over (§4.11
     * counts onderwerpexpressies). The naive reading — {@link RegelSpraakModel#resolveObjecttype}
     * — is subject-blind and tries the name as an objecttype FIRST, so a count element word that
     * collides with a declared objecttype name ("het aantal bestemming", where Bestemming is a
     * type AND "bestemming" a role to Luchthaven) binds the wrong type and then loses its join to
     * a comment placeholder — counting ALL instances globally. Mirror the p12 navigation
     * disambiguation EXACTLY: take the role reading when a same-named role uniquely reaches a type
     * FROM the subject and no role NAMED-as-written reaches the by-name objecttype from the subject
     * — the written reference IS that role navigation (§13.4.1.2/.3). The name-aware
     * {@link RegelSpraakModel#verbindendeRolViaNaam} (not the name-blind by-pair {@code
     * verbindendeRol}) is what makes a count and the analogous plain navigation resolve the same
     * written filler identically, even when the by-name objecttype joins the subject through a
     * DIFFERENT, unwritten role. Otherwise the by-name reading stands.
     */
    private Objecttype aantalElement(String woord) {
        Objecttype byNaam = model.resolveObjecttype(woord);
        // the count's element word is a population name too ("het aantal Munten van de boot"), and
        // it is written in the plural by construction — same fallback, same precedence as the set
        // onderwerp above (see typeViaMeervoud); the role reading below still wins where it applies.
        if (byNaam == null) byNaam = typeViaMeervoud(woord);
        if (huidigSubject == null) return byNaam;
        Objecttype viaRol = model.rolDoeltypeNaarSubject(huidigSubject, woord);
        if (viaRol != null && (byNaam == null || model.verbindendeRolViaNaam(huidigSubject, byNaam, woord) == null))
            return viaRol;
        return byNaam;
    }

    /**
     * Result datatype of an aggregation/function. The aggregations that preserve
     * their operand's type — sum/total/min/max/first/last/abs — yield the element
     * type (which now carries a real unit once the argument's subject resolves
     * through a role); a count is a non-negative integer; concatenation is Tekst;
     * the date constructors and Easter are dates. The unit/precision-sensitive
     * functions (tijdsduur's "in hele <eenheid>", wortel's unit drop, date-component
     * extraction, day counts, tijdsevenredig deel) are left Onbekend until unit
     * normalisation exists — typing them naively would mis-match "jaren" against the
     * declared "jr" and raise a false positive. Structure is captured regardless.
     */
    /** The least declared decimal count over a min/max operand list — the floor of the
     *  value-dependent result precision, so the §6.1.2 check never over-reports. */
    private static int minDecimalen(List<Expr> args) {
        return args.stream().mapToInt(a -> a.type() instanceof Datatype.Numeriek m ? m.decimalen()
                : a.type() instanceof Datatype.Percentage p ? p.decimalen() : 0).min().orElse(0);
    }

    /** Whether EVERY min/max operand is a whole number — the only case in which the winning value
     *  is provably integral. Derived from each operand's geheel flag, NOT from decimalen==0 (which an
     *  unbounded "getal" operand also carries), so a min/max of unbounded or decimal operands is not
     *  mislabelled geheel — which would wrongly admit it to the §5.13 elfproef (Tabel 55). */
    private static boolean alleGeheel(List<Expr> args) {
        return args.stream().allMatch(a -> (a.type() instanceof Datatype.Numeriek m && m.geheel())
                || (a.type() instanceof Datatype.Percentage p && p.geheel()));
    }

    /** Whether an aggregation's operand is a NON-TEMPORAL SET read — "&lt;attribuut&gt; van alle &lt;elementen&gt;"
     *  over a plain (non-timeline) attribute: the pure instance fold, which adds element values and integrates
     *  over nothing. Both halves are load-bearing. The {@code set()} flag is the same one
     *  {@link #gedurendeKenmerkFilter} tests negatively to admit the temporal clip; the granularity half is the
     *  same test {@link #totaalOperandFout} applies (a §5.1.4 timeline-declared attribute, "voor elke maand"),
     *  because a set read over a TEMPORAL attribute is the §7.1 PER-ELEMENT TIMELINE FOLD — each element's
     *  tijdreeks is summed over time FIRST (rsTijdTotaal at the attribute's granularity) and only then over the
     *  set — so that form DOES integrate over time and the §4.20 strip must keep applying to it. */
    private static boolean setLezing(List<Expr> args) {
        return args.size() == 1 && args.get(0) instanceof Expr.AttribuutRef a && a.set()
                && !(a.objecttype() != null && a.objecttype().granulariteitVan(a.attribuut()) != null);
    }

    private Datatype functieType(Expr.FunctieKind kind, List<Expr> args) {
        Datatype eerste = args.isEmpty() ? onbekend() : args.get(0).type();
        return switch (kind) {
            case SOM, EERSTE, LAATSTE, ABSOLUTE_WAARDE -> eerste;
            // §4.20 Tabel 30: totaal-van INTEGRATES a rate over time, so the result drops the /tijdseenheid —
            // row 1 result eenheid "-", row 2 "Eenheid1" (the numerator). "het totaal van <EUR/mnd>" types EUR
            // and assigns cleanly to a EUR target (a EUR/mnd target is now the unit mismatch it should be).
            // A non-rate operand type passes through unchanged (the §7.1 unitless-Bedrag path).
            // The strip is suppressed on the NON-TEMPORAL SET READING alone (maintainer decision 2026-09-04,
            // read narrowly: a non-set non-temporal rate read keeps the strip and both executors refuse it
            // loudly — the §4.20 operand contract): the SET reading
            // "het totaal van <x> van alle <elementen>" over a PLAIN (non-timeline) attribute — which the grammar
            // admits — is an ALIAS of "de som van", an instance fold that integrates over nothing, so it carries
            // the ELEMENT's type verbatim exactly as SOM does. Stripping there invented a wire type no executor
            // can produce: the fold adds EUR/mnd values and the EUR target then faults on assignment
            // ("incompatible units: EUR vs EUR"), while an honest EUR/mnd target was rejected by the §6.1.2
            // assignment check. A set read over a TEMPORAL attribute is NOT that alias — it is the §7.1
            // per-element timeline fold, a time integration, and setLezing excludes it so the strip still fires.
            // One predicate, no other behaviour moves.
            case TOTAAL -> setLezing(args) ? eerste
                    : eerste instanceof Datatype.Numeriek n && n.eenheid() != null && n.eenheid().noemer() != null
                    ? new Datatype.Numeriek(n.geheel(), n.decimalen(), n.teken(), n.eenheid().teller())
                    : eerste;
            // §4.12/§4.13: a multi-operand min/max result is the SMALLEST/LARGEST VALUE, whose
            // precision is value-dependent and not statically known. A single-operand min/max over a
            // set carries the element's precision (reliable). For the value-fold, report the LEAST
            // operand precision so the §6.1.2 check never over-reports (the exact value-dependent
            // precision is carded); the first operand's precision would be order-dependent.
            case MINIMUM, MAXIMUM -> args.size() <= 1 || !(eerste instanceof Datatype.Numeriek n) ? eerste
                    : new Datatype.Numeriek(alleGeheel(args), minDecimalen(args), n.teken(), n.eenheid());
            // both counts: a cardinality is a whole, UNITLESS number whatever the folded attribute
            // measures (RUNTIME-SEMANTICS ch. 3.4c) — the source's kg never reaches the result
            case AANTAL, AANTAL_GEVULDE -> new Datatype.Numeriek(true, 0, Datatype.Teken.NIET_NEGATIEF, null);
            // §5.7/§13.4.17.2: a concatenatie is a MULTI-VALUED set (§5.8.1 "altijd meervoudig"), NOT a scalar
            // Tekst — it carries the common type of its operands and is only meaningful once an aggregation
            // (het aantal / de som / de min-max / de eerste-laatste) consumes it (§5.8). This "de concatenatie
            // van …" primaireExpressie surface is a repo artifact (the spec keyword belongs to §13.4.8.14
            // uniciteitconcatenatie only; the real §13.4.17.2 value concatenatie has no keyword and lives inline
            // in each aggregation's operand list). As a STANDALONE value it has no scalar meaning, so both back-
            // ends marker it symmetrically; typing it Tekst fabricated a spurious "Tekst→Numeriek" mismatch that
            // MASKED that honest marker. Leave it onbekend (the not-lowered-kind convention) — see
            // docs/grammar-divergences.md "concatenatie as a standalone value".
            case CONCATENATIE -> onbekend();
            // §13.4.17.13: a square root is non-negative; its precision comes from the MANDATORY afronding that
            // wraps it (a sqrt is generally irrational), and its unit would be the operand's unit's root —
            // composite-unit territory, dropped for now (carded). The afronding's Afgerond re-derives decimals.
            case WORTEL -> new Datatype.Numeriek(false, 0, Datatype.Teken.NIET_NEGATIEF, null);
            case EERSTE_PAASDAG, DATUM_MET -> new Datatype.DatumInDagen();
            case DATUMTIJD_MET -> new Datatype.DatumTijd();
            // §13.4.17.18-.24 / typeringen Tabel 28: a date/time component (jaar/maand/dag/uur/minuut/seconde/
            // milliseconde uit) is a unitless non-negative whole Numeriek. Typing it (rather than leaving it
            // Onbekend, which makes Typering.vergelijk short-circuit OK) lets the comparison matrix REJECT an
            // illegal use — e.g. "de maand uit X eerder dan <een datum>", which Tabel 45 restricts to two dates
            // — instead of the transpiler now lowering it to an uncompilable BigDecimal-vs-LocalDate term.
            case JAAR_UIT, MAAND_UIT, DAG_UIT, UUR_UIT, MINUUT_UIT, SECONDE_UIT, MILLISECONDE_UIT ->
                    new Datatype.Numeriek(true, 0, Datatype.Teken.NIET_NEGATIEF, null);
            // §7.3.2/§13.4.17.58: the pro-rata part carries the BASE's value type — the per-bucket fraction
            // scales the base within one bucket and never changes its unit or family (a rate base is restated
            // by the transpiler BEFORE proration, §4.20-style). Typing it — instead of the not-lowered
            // onbekend — puts the concrete datatype on the emitted IR node (the uchar spec forbids the
            // unknown-type sentinel inside a structured node, its WF-034).
            case TIJDSEVENREDIG_DEEL -> eerste;
            default -> onbekend();
        };
    }

    private Expr.FunctieKind functieKind(FunctieAanroepContext f) {
        if (f instanceof SomFuncContext)        return Expr.FunctieKind.SOM;
        if (f instanceof AantalFuncContext)     return Expr.FunctieKind.AANTAL;
        if (f instanceof AantalGevuldeFuncContext) return Expr.FunctieKind.AANTAL_GEVULDE;
        if (f instanceof TotaalFuncContext)     return Expr.FunctieKind.TOTAAL;
        if (f instanceof WortelFuncContext)     return Expr.FunctieKind.WORTEL;
        if (f instanceof AbsWaardeFuncContext)  return Expr.FunctieKind.ABSOLUTE_WAARDE;
        if (f instanceof PaasdagFuncContext)    return Expr.FunctieKind.EERSTE_PAASDAG;
        if (f instanceof ConcatFuncContext)     return Expr.FunctieKind.CONCATENATIE;
        if (f instanceof AantalDagenFuncContext)   return Expr.FunctieKind.AANTAL_DAGEN_IN;
        if (f instanceof TijdsevenredigFuncContext) return Expr.FunctieKind.TIJDSEVENREDIG_DEEL;
        if (f instanceof JaarUitFuncContext)    return Expr.FunctieKind.JAAR_UIT;
        if (f instanceof MaandUitFuncContext)   return Expr.FunctieKind.MAAND_UIT;
        if (f instanceof DagUitFuncContext)     return Expr.FunctieKind.DAG_UIT;
        if (f instanceof UurUitFuncContext)     return Expr.FunctieKind.UUR_UIT;
        if (f instanceof MinuutUitFuncContext)  return Expr.FunctieKind.MINUUT_UIT;
        if (f instanceof SecondeUitFuncContext) return Expr.FunctieKind.SECONDE_UIT;
        if (f instanceof MilliUitFuncContext)   return Expr.FunctieKind.MILLISECONDE_UIT;
        if (f instanceof MinMaxFuncContext m)   return m.MAXIMALE_WAARDE_VAN() != null ? Expr.FunctieKind.MAXIMUM : Expr.FunctieKind.MINIMUM;
        if (f instanceof EersteLaatsteFuncContext el) return el.EERSTE_VAN() != null ? Expr.FunctieKind.EERSTE : Expr.FunctieKind.LAATSTE;
        if (f instanceof TijdsduurFuncContext t) return t.ABSOLUTE_TIJDSDUUR_VAN() != null ? Expr.FunctieKind.ABSOLUTE_TIJDSDUUR : Expr.FunctieKind.TIJDSDUUR;
        return Expr.FunctieKind.SOM; // unreachable: every functieAanroep alternative is covered above
    }

    /** The direct expression children of a context, built in order — an aggregation's
     *  arguments. The telling (§13.4.17.47 aantal) takes a higher-precedence
     *  primaireExpressie operand (so a trailing 'maal'/'plus' binds outside the count),
     *  so a primaireExpressie child is built as such; every other aggregation takes full
     *  expressie children. */
    private List<Expr> args(ParserRuleContext ctx) {
        List<Expr> out = new ArrayList<>();
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree ch = ctx.getChild(i);
            if (ch instanceof ExpressieContext ec) out.add(bouw(ec));
            else if (ch instanceof PrimaireExpressieContext pe) out.add(bouwPrimair(pe));
        }
        return out;
    }

    // ---------------- §13.2.12 rangtelwoord (§5.5.3) ----------------
    /** The 1-based mention position a §13.2.12 rangtelwoord denotes, or -1 for "laatstgenoemde". */
    private static int rangtelwoordPositie(RangtelwoordContext r) {
        if (r.EERSTGENOEMDE() != null) return 1;
        if (r.ALS_TWEEDE_GENOEMDE() != null) return 2;
        if (r.ALS_DERDE_GENOEMDE() != null) return 3;
        if (r.ALS_VIERDE_GENOEMDE() != null) return 4;
        if (r.LAATSTGENOEMDE() != null) return -1;
        return r.NUMBER() != null ? Integer.parseInt(r.NUMBER().getText()) : 1;   // "als <N>e genoemde"
    }

    /** The subject reference (onderwerpVerwijzing) of a reference's keten that carries a rangtelwoord, or null. */
    private OnderwerpVerwijzingContext verwijzingMetRangtelwoord(OnderwerpContext o) {
        return o == null ? null : verwijzingMetRangtelwoord(o.onderwerpKeten());
    }
    private OnderwerpVerwijzingContext verwijzingMetRangtelwoord(OnderwerpKetenContext k) {
        if (k == null) return null;
        if (k.onderwerpVerwijzing() != null)
            return k.onderwerpVerwijzing().rangtelwoord() != null ? k.onderwerpVerwijzing() : null;
        return verwijzingMetRangtelwoord(k.onderwerpKeten());
    }

    /** §5.5.3: how many universele onderwerpen ("een &lt;naam&gt;") the enclosing rule binds under {@code naam} —
     *  the mentions a rangtelwoord indexes ("de plaats van het bedoelde onderwerp in de regel"). A definite
     *  back-reference is a REFERENCE to a binding, not a new one, so only the indefinite form counts. */
    private int telBindingen(ParserRuleContext ref, String naam) {
        ParserRuleContext p = ref;
        while (p != null && !(p instanceof RegelSpraakRegelContext)) p = p.getParent();
        return p == null ? 0 : telUniverseel(p, naam.trim().replaceAll("\\s+", " "));
    }
    private int telUniverseel(org.antlr.v4.runtime.tree.ParseTree t, String naam) {
        int n = t instanceof UniverseelOnderwerpContext u && u.kaleNaam() != null
                && src(u.kaleNaam()).trim().replaceAll("\\s+", " ").equalsIgnoreCase(naam) ? 1 : 0;
        for (int i = 0; i < t.getChildCount(); i++) n += telUniverseel(t.getChild(i), naam);
        return n;
    }

    /** §5.5.3/§13.2.12 consume a rangtelwoord on a reference to the onderwerp {@code naam}. Returns null when
     *  the reference may resolve as the BARE form — the ordinal trivially selects the rule's single (or sole
     *  implied) binding — or the Expr that replaces it:
     *  - positie beyond the rule's same-named bindings: ERROR (the rule does not name that many onderwerpen);
     *  - two or more same-named bindings: the genuinely multi-bound case needs one pattern variable PER
     *    mention, which the single-subject rule IR does not carry — WARNING + honest marker, never a silent
     *    alias of one binding (which is what dropping the ordinal used to mean);
     *  - eerstgenoemde/laatstgenoemde/positie-1 over at most one binding: the bare resolution IS the answer. */
    private Expr keurRangtelwoord(RangtelwoordContext r, String naam, ParserRuleContext ref, Bron bron) {
        if (r == null) return null;
        int positie = rangtelwoordPositie(r);
        int bindingen = telBindingen(ref, naam);
        if (positie > Math.max(bindingen, 1)) {
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.ERROR,
                    "rangtelwoord: de regel noemt geen " + positie + " onderwerpen '" + naam + "' (§5.5.3)",
                    r.getStart().getLine()));
            return onopgelost(bron, null);   // the ERROR above is this marker's diagnosis
        }
        if (bindingen >= 2) {
            // §5.5.3 ¶1888 states ONLY that a rangtelwoord points out "de plaats van het bedoelde onderwerp in de
            // regel"; it pins no operational semantics — no worked multi-binding rule, no definition of what the
            // pairing/ranging over the several bindings is, and the typeringen matrix is silent. So a genuinely
            // multi-bound rule is spec-UNDERDETERMINED, not merely unimplemented: we cannot lower it soundly (which
            // mention order, which pairing). It stays a visible WARNING + honest marker in both back-ends — never a
            // silent alias of one binding (which is what dropping the ordinal used to mean).
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                    "rangtelwoord: de regel bindt " + bindingen + " onderwerpen '" + naam
                    + "'; §5.5.3 definieert geen bindings-/ordeningssemantiek voor zo'n verwijzing per vermelding — "
                    + "de verwijzing blijft een marker (wordt nog niet ondersteund)",
                    r.getStart().getLine()));
            return onopgelost(bron, null);   // the WARNING above is this marker's diagnosis
        }
        return null;
    }

    // ---------------- references ----------------
    /** `<attribuut> van <onderwerp>`: bind the attribute's datatype via the subject's object type. */
    Expr bouwAttribuut(AttribuutVanOnderwerpContext a) {
        Bron bron = bron(a);
        if (a != null && a.attribuutMetLidwoord() == null && a.rangtelwoord() != null && a.kaleNaam() != null) {
            // §13.4.2.9 the ordinal-ATTRIBUTE form "de <rangtelwoord> <attribuutnaam> van <onderwerp>". The
            // eerstgenoemde/laatstgenoemde/1st reading over a single mention is the bare attribute read; a
            // deeper position needs per-mention binding (§5.5.3) and stays an honest marker like the subject form.
            Expr geblokkeerd = keurRangtelwoord(a.rangtelwoord(), src(a.kaleNaam()), a, bron);
            return geblokkeerd != null ? geblokkeerd : attribuutRef(src(a.kaleNaam()), a.onderwerp(), bron);
        }
        // GEEN GETUIGE: de enige aanroeper geeft een gevulde context mee, `attribuutMetLidwoord` is
        // `lidwoord? kaleNaam` (de naam is er dus altijd), en de rangtelwoord-vorm is hierboven al
        // afgehandeld — er blijft geen invoer over die hier uitkomt. Gemeten 2026-09-19.
        if (a == null || a.attribuutMetLidwoord() == null || a.attribuutMetLidwoord().kaleNaam() == null)
            return onopgelost(a,
                    "de attribuutverwijzing noemt geen attribuutnaam met lidwoord (§13.4.2.9)");
        String attr = src(a.attribuutMetLidwoord().kaleNaam());
        // §13.4.17.12/§6.8 "het percentage P van V" WITHOUT the '%': our grammar requires '%' on percentageVanExpr to
        // disambiguate from a 'van' navigation (docs/grammar-divergences.md), so the no-'%' form parses HERE as a
        // navigation. But when the leftmost name P is a Percentage-typed PARAMETER (never a real attribuut of any
        // objecttype), this IS the percentage function, not a navigation — reinterpret it as PERCENTAGE_VAN(P, V) with
        // V the onderwerp built as a value (V·P/100, §4.8 Tabel 19; leeg telt als 0). A name that is no Percentage
        // parameter, or a V we cannot build, falls through to the normal navigation (an honest marker, never a wrong gate).
        // The greedy kaleNaam absorbs an inner 'van' ("korting van de prijs" -> ONE kaleNaam of two delen), so the
        // percentage P is the LEFTMOST kaleNaamDeel and V is "<remaining deel> van <onderwerp>"; with no inner 'van'
        // (one deel) V is just the onderwerp built as a value.
        java.util.List<KaleNaamDeelContext> delen = a.attribuutMetLidwoord().kaleNaam().kaleNaamDeel();
        Parameter pct = percentageParameter(src(delen.get(0)));
        if (pct != null) {
            Expr v = delen.size() == 1 ? onderwerpAlsWaarde(a.onderwerp())
                   : delen.size() == 2 ? attribuutRef(stripArtikel(src(delen.get(1))), a.onderwerp(), bron)
                   : null;   // a deeper navigation chain in the V part stays a marker (rare)
            // §4.8 Tabel 18 requires a NUMERIEK right operand: only reinterpret as the percentage function when V is
            // a getalachtig value. This guards the name collision where the leftmost name is BOTH a Percentage
            // parameter AND a real attribute reachable through the onderwerp ("de korting van zijn bestelling", korting
            // also a Bestelling attribute): there V is a to-one ROLE navigation (type onbekend), so the percentage
            // reading is rejected and the construct stays the correct attribute navigation.
            if (v != null && !(v instanceof Expr.Onopgelost) && v.type().isGetalachtig()) {
                // §4.8 Tabel 18: the result carries V's unit; §4.8 Precisie makes it an exact fraction, so drop V's
                // declared decimal count (the same typing the '%' form uses, lines above).
                Datatype t = v.type() instanceof Datatype.Numeriek n
                        ? new Datatype.Numeriek(false, 0, Datatype.Teken.ONBEPAALD, n.eenheid()) : onbekend();
                return new Expr.Binair(Expr.BinOp.PERCENTAGE_VAN, new Expr.ParameterRef(pct, bron), v, t, bron);
            }
        }
        return attribuutRef(attr, a.onderwerp(), bron);
    }

    /** Build "&lt;attr&gt; van &lt;onderwerp&gt;" into an {@link Expr.AttribuutRef} — the shared core of
     *  {@link #bouwAttribuut} and {@link #onderwerpAlsWaarde}. */
    private Expr attribuutRef(String attr, OnderwerpContext onderwerp, Bron bron) {
        String onderwerpBron = onderwerp != null ? src(onderwerp) : "";
        // §13.4.1.3/§5.5.3 a rangtelwoord on the SUBJECT reference ("de bijdrage van de als tweede genoemde
        // deelnemer"): consume the ordinal — the bare resolution when it selects the rule's single binding,
        // an ERROR/marker otherwise (keurRangtelwoord). Never silently dropped (the pre-consumption behavior
        // aliased every mention to one binding).
        OnderwerpVerwijzingContext ov = verwijzingMetRangtelwoord(onderwerp);
        if (ov != null) {
            Expr geblokkeerd = keurRangtelwoord(ov.rangtelwoord(), src(ov.kaleNaam()), ov, bron);
            if (geblokkeerd != null) return geblokkeerd;
        }
        Objecttype ot = resolveOnderwerp(onderwerp);
        // The role the author wrote to reach `ot` (the noun of a DEFINITE reference or a
        // possessive, when it names a feittype role) — threaded so the transpiler
        // navigates the exact field even when the (subject, ot) pair is connected by more
        // than one feittype/role. An indefinite "een <X>" is the universal subject, not a
        // navigation, so it is excluded (it stays an own-attribute read).
        String noun = navigatieNoun(onderwerp);
        String navRol = model.navigatieRolNaam(noun);
        // §13.4.1.2/.3: a navigation filler may name an objecttype OR a role. Take the
        // role reading only when the objecttype reading is NOT the navigation the author
        // wrote — it is not the subject's own type (a definite back-reference to the
        // subject's type reads the subject's own attribute, §5.5.3), AND no role NAMED as
        // written reaches that objecttype from the subject. Then, when a same-named role
        // uniquely reaches a type that actually CARRIES the attribute, bind the role and
        // record it for the join. Scoped to a single-object definite/possessive reference
        // (`noun` is null for an indefinite subject or a set quantifier) and an
        // unambiguous, attribute-bearing role, so an honestly unresolvable reference stays
        // a marker rather than becoming a wrong join or a getter for an absent property.
        if (noun != null && huidigSubject != null && ot != huidigSubject
                && model.verbindendeRolViaNaam(huidigSubject, ot, noun) == null) {
            Objecttype viaRol = model.rolDoeltypeNaarSubject(huidigSubject, noun);
            if (viaRol != null && viaRol.attribuut(attr) != null) { ot = viaRol; navRol = noun; }
        }
        // §8.1.5 + Tabel 16 NEGATED navigated dagsoortcontrole ("de <datum> van zijn <rol> GEEN <dagsoort> is"): the
        // greedy onderwerp absorbs the trailing "geen" into the navigation role's kaleNaam ("reis geen"), so the real
        // role fails to resolve. Recover the real role (mirror absorbedNegator) when the stripped role uniquely reaches
        // a DATUM attribute, keeping the bron's trailing " geen" so RuleChecker.kenmerkPredicaatKaal case (b) builds the
        // NEGATED dagsoort predicaat. Scoped to a datum attr (the dagsoort operand), so a navigated kenmerk negation
        // (still out of scope) stays a marker.
        if ((ot == null || ot.attribuut(attr) == null) && noun != null && huidigSubject != null
                && noun.length() > 5 && noun.regionMatches(true, noun.length() - 5, " geen", 0, 5)) {
            String echteRol = noun.substring(0, noun.length() - 5).stripTrailing();
            Objecttype viaRol = model.rolDoeltypeNaarSubject(huidigSubject, echteRol);
            Datatype rdt = viaRol != null ? viaRol.attribuut(attr) : null;
            // §8.1.5/§5.15/Tabel 57: a dagsoort operand may be ANY datum/tijd (Datum-in-dagen OR Datum-en-tijd); both
            // back-ends take the date part for the day-granular §9.8 predicate. Scoped to a datum attr, so a navigated
            // kenmerk negation (still out of scope) stays a marker.
            if (rdt != null && rdt.isDatum()) { ot = viaRol; navRol = echteRol; }
        }
        Datatype dt = ot != null && ot.attribuut(attr) != null ? ot.attribuut(attr) : null;
        if (dt == null && ot != null) {
            // §3.7/§5.1 an appositive "in <eenheid>" on an attribuut read — a §12 conditie such as
            // "de looptijd IN MINUTEN van zijn meting" re-states the attribute's own unit inline. This is not its
            // own EBNF production (the only grammar "in <eenheid>" is the §13.4.17.32 tijdsduur "in hele
            // <eenheid>"), so the unit is greedily swallowed into the kaleNaam; but the spec body writes it and
            // it merely RE-STATES the attribute's own unit (the spec always names the attribute's NATIVE unit:
            // "in minuten" on a minuut attribute). Strip the suffix and retry on the bare name, keeping the native
            // type, so BOTH back-ends lower the bare AttribuutRef identically (resolver-only fix).
            // IDENTITY-only, deliberately: a non-identity but convertible unit ("in jaren" on a mnd attribute)
            // would need a REAL conversion of the raw read (×1/12), not a name strip — dropping the qualifier and
            // reading the native magnitude would silently compare 6[mnd] to a 1[jr] literal as "6 > 1" (a
            // shared-bug both back-ends agree on). No spec example forms a non-native qualifier, so it stays an
            // honest marker. An attribute name that merely contains " in ", or a non-convertible tail, also markers.
            int sp = attr.lastIndexOf(" in ");
            if (sp > 0) {
                Datatype bareDt = ot.attribuut(attr.substring(0, sp));
                Eenheid nativeE = bareDt == null ? null : bareDt.eenheidOf();
                Eenheid named = nativeE == null ? null : model.resolveEenheid(attr.substring(sp + 4).trim());
                if (nativeE != null && nativeE.convertibleNaar(named) && !nativeE.vereistOmrekening(named)) {
                    attr = attr.substring(0, sp);
                    dt = bareDt;
                }
            }
        }
        // declared-names-only IR: a WRITTEN PLURAL of a declared attribute binds the DECLARED name at resolve time
        // ("de leeftijden van alle deelnemers" reads the attribute declared 'leeftijd'), so the IR only ever
        // carries declared members and no back-end synthesizes a getter from surface morphology. The matcher
        // is ambiguity-safe (declared "(mv: …)" or the conservative regular plural of exactly ONE attribute);
        // an unbindable written form keeps its Onbekend type and stays a downstream marker.
        if (dt == null && ot != null) {
            String enkelvoud = MeervoudMatcher.vindAttribuut(ot, attr);
            if (enkelvoud != null) {
                attr = enkelvoud;
                dt = ot.attribuut(enkelvoud);
            }
        }
        // §13.4.1 MULTI-HOP to-one navigation chain ("de X van de Y van de Z"): the greedy kaleNaam absorbs
        // every inner 'van', so a chained reference reaches here as ONE attr string ("capaciteit van het
        // vliegtuig") whose TRAILING delen are role hops beyond the onderwerp. When no direct reading above
        // bound an attribute, walk those delen outward from the onderwerp's type: each deel must resolve to a
        // to-ONE hop (ketenHop: written role name or objecttype name) and the remaining prefix must name a
        // declared attribute — checked at EVERY boundary, so a multi-word attribute name containing 'van'
        // ("datum van vertrek") wins over deeper navigation and is never split. The resolved DECLARED role
        // names (subject-first, including the onderwerp hop) thread into the IR as navigatiePad; both
        // back-ends re-validate the walk against their pattern subject (volgKeten) and emit one null-guarded
        // getter per hop. Any unresolvable/ambiguous/to-many hop declines the WHOLE chain (dt stays null ->
        // the downstream marker, never a wrong join). navigatieRol stays non-null for a chain (the AttribuutRef
        // invariant), so every own-attribute fast path routes it to the navigated branches.
        java.util.List<String> navigatiePad = java.util.List.of();
        if (dt == null && huidigSubject != null && noun != null && attr.contains(" van ")
                && !isSetOnderwerp(onderwerp)) {
            // The onderwerp anchor: the type the chain starts its delen-walk from. The 1326 fix-up above only
            // rebinds ot when the ROLE TYPE carries the (still-chained) attr — which fails for a chain by
            // construction — so recover the role reading here when ot is still unbound.
            Objecttype anker = ot != null ? ot : model.rolDoeltypeNaarSubject(huidigSubject, noun);
            java.util.List<String> pad = null;
            Objecttype cur = null;
            if (anker != null) {
                // First hop (subject -> anker), mirroring the transpiler's naviRol order: by written name,
                // then the §5.5.3 own-type back-reference (no hop), then by pair; declined when to-many.
                String rol1 = navRol != null ? model.verbindendeRolViaNaam(huidigSubject, anker, navRol) : null;
                boolean eigenStart = rol1 == null && anker == huidigSubject;
                if (rol1 == null && !eigenStart) rol1 = model.verbindendeRol(huidigSubject, anker);
                if (eigenStart || (rol1 != null && !model.naarVeelRol(huidigSubject, anker, rol1))) {
                    pad = new java.util.ArrayList<>();
                    if (!eigenStart) pad.add(rol1);
                    cur = anker;
                }
            }
            if (pad != null) {
                String[] delen = attr.split("\\s+van\\s+");
                for (int i = delen.length - 1; i >= 1; i--) {
                    RegelSpraakModel.KetenHop hop = model.ketenHop(cur, stripArtikel(delen[i]).trim());
                    if (hop == null) break;   // unresolvable / ambiguous / to-many -> decline the whole chain
                    pad.add(hop.rol());
                    cur = hop.doel();
                    String prefix = String.join(" van ", java.util.Arrays.copyOfRange(delen, 0, i));
                    Datatype d2 = cur.attribuut(prefix);
                    if (d2 == null) {
                        String ev = MeervoudMatcher.vindAttribuut(cur, prefix);
                        if (ev != null) { prefix = ev; d2 = cur.attribuut(ev); }
                    }
                    if (d2 != null) {
                        attr = prefix; ot = cur; dt = d2;
                        navigatiePad = java.util.List.copyOf(pad);
                        if (navRol == null) navRol = noun;   // invariant: a chain always reads as navigated
                        break;
                    }
                }
            }
        }
        if (dt == null) dt = onbekend();
        return new Expr.AttribuutRef(attr, ot, onderwerpBron, navRol, dt, bron, isSetOnderwerp(onderwerp),
                navigatiePad, bereikPad(onderwerp, ot), geschrevenKwantor(onderwerp),
                geschrevenDrempel(onderwerp));
    }

    /**
     * §5.8/§13.4.2 the MULTI-STEP fold-source range ("de &lt;attr&gt; van alle X van alle Y van &lt;onderwerp&gt;"),
     * resolved into the symbolic bindings the uchar wire carries as {@code bronPad} (IR-SPEC ch. 4.3,
     * WF-102). The written names are authoritative (§13.4.1: the written role IS the navigation), which is
     * the whole point — the by-PAIR derivation that used to stand in here looks only at TYPES and will bind
     * a coincidental direct subject-to-element relation, folding a different set from the one the sentence
     * names.
     *
     * <p>Empty — leaving the caller's existing behaviour untouched — for every shape this does not decide:
     * a single written step (whose range IS one relation, spelled {@code bronRol}), a non-set onderwerp, an
     * anchor that denotes neither the subject's object type nor the subject itself, a step that resolves to
     * no role or to several, a reciprocal fact type, and a walk that does not land on the element type the
     * head noun already bound. Declining the WHOLE path rather than binding part of it is what keeps the
     * two backends symmetric by construction: they consume the same path or none.
     *
     * <p>The ANCHOR (the innermost written segment) may name a ROLE instead of an object type, and the test
     * is the one ch. 4.1a already fixes rather than a new one: far-role resolution FIRST — a role reachable
     * FROM the subject is a hop whose filler is another instance, so it belongs on the path as a step and
     * never as the anchor — and the self-denoting reading only at ZERO far hits, where the subject type
     * itself fills a role of that name ("van de aanmelder", read on the Jutter who IS the aanmelder).
     */
    private java.util.List<RegelSpraakModel.RolBindingRef> bereikPad(OnderwerpContext onderwerp, Objecttype elem) {
        if (onderwerp == null || elem == null || huidigSubject == null || !isSetOnderwerp(onderwerp))
            return java.util.List.of();
        // The written segments, outside-in. Two shapes produce them, and both are walked: a nested
        // set onderwerp is its own parse node, while an inner TO-ONE step is swallowed by the greedy
        // kaleNaam (§13.4.1, the same split the navigatiePad block above performs).
        java.util.List<String> segmenten = new java.util.ArrayList<>();
        for (OnderwerpContext rest = onderwerp; rest != null; rest = rest.onderwerp()) {
            String kn = rest.kaleNaam() != null ? src(rest.kaleNaam()) : primaireNaam(rest);
            if (kn == null) return java.util.List.of();
            for (String deel : kn.split("\\s+van\\s+")) segmenten.add(stripArtikel(deel).trim());
        }
        // Two hops plus an anchor is the shortest thing this field exists for; anything shorter is the
        // one-step range and keeps its own spelling.
        if (segmenten.size() < 3) return java.util.List.of();
        if (!ankertHetOnderwerp(segmenten.get(segmenten.size() - 1))) return java.util.List.of();
        java.util.List<RegelSpraakModel.RolBindingRef> pad = new java.util.ArrayList<>();
        Objecttype houder = huidigSubject;
        for (int i = segmenten.size() - 2; i >= 0; i--) {
            RegelSpraakModel.BereikHop hop = model.bereikHop(houder, segmenten.get(i));
            if (hop == null) return java.util.List.of();
            pad.add(hop.binding());
            houder = hop.doel();
        }
        // The walk must land where the head noun already bound the element type: a mismatch means the head
        // resolved against a different fact type than the walk did, and binding it anyway would fold a set
        // the sentence does not name.
        return houder == elem ? java.util.List.copyOf(pad) : java.util.List.of();
    }

    /** Whether the written anchor segment denotes the RULE SUBJECT — literally by object-type name, or
     *  (ch. 4.1a's order: only at zero far hits) by a role the subject itself fills. */
    private boolean ankertHetOnderwerp(String anker) {
        for (String kandidaat : RegelSpraakModel.naamKandidaten(anker))
            if (model.objecttypeOpNaam(kandidaat) == huidigSubject) return true;
        return model.rolTreffersNaarSubject(huidigSubject, anker) == 0
                && model.vultZelfDeRol(huidigSubject, anker);
    }

    /** The {@link Parameter} named {@code naam} (lidwoord-stripped) when it is Percentage-typed — the §13.4.17.12
     *  "het percentage P van V" left operand. Null otherwise. */
    private Parameter percentageParameter(String naam) {
        String stripped = stripArtikel(naam);
        Parameter p = model.parameters.get(stripped);
        if (p == null) p = model.parameters.get(naam);
        return p != null && p.datatype() != null && p.datatype().isPercentage() ? p : null;
    }

    private static String stripArtikel(String naam) {
        return naam.replaceFirst("^(?i)(de|het|een)\\s+", "");
    }

    /** Build an onderwerp as a VALUE expression — the V in "het percentage P van V". Handles the
     *  "de &lt;attr&gt; van &lt;subject&gt;" navigation ({@code selector VAN onderwerp}), the possessive
     *  "zijn &lt;attr&gt;", and a bare subject-own "de &lt;attr&gt;"; otherwise null (the caller keeps the marker). */
    private Expr onderwerpAlsWaarde(OnderwerpContext o) {
        if (o == null) return null;
        OnderwerpKetenContext k = o.onderwerpKeten();
        if (k != null && k.selector() != null && k.selector().kaleNaam() != null && k.onderwerp() != null)
            return attribuutRef(src(k.selector().kaleNaam()), k.onderwerp(), bron(o));   // "de <attr> van <subject>"
        Expr poss = possessief(o, bron(o));
        if (poss != null) return poss;                                                  // "zijn <attr>"
        // §5.5.3 a bare definite subject-own attribute referring to the rule subject ("de <attr>") — either the
        // onderwerpVerwijzing form (under an onderwerpKeten) or the kwantificatie+kaleNaam form (k == null). Only the
        // bare definite (NOT a set quantifier alle/geen/…) and only an attribute the subject actually carries.
        String bare = k != null && k.onderwerpVerwijzing() != null && k.onderwerpVerwijzing().kaleNaam() != null
                ? src(k.onderwerpVerwijzing().kaleNaam())
                : (o.kaleNaam() != null && !isSetOnderwerp(o) ? src(o.kaleNaam()) : null);
        if (bare != null && huidigSubject != null) {
            Datatype dt = huidigSubject.attribuut(bare);
            if (dt != null) return new Expr.AttribuutRef(bare, huidigSubject, src(o), null, dt, bron(o));
        }
        return null;
    }

    /** §13.4.2 whether {@code o}'s top-level quantifier ranges over a SET — a kwantificatie that is NOT the bare
     *  definite "de" (alle / al / geen van de / ten minste … / ten hoogste … / precies …). A possessive, an
     *  indefinite "een &lt;X&gt;" universal subject, or an onderwerpketen carries no kwantificatie and is a single
     *  object. The structural replacement for the old onderwerpBron "alle " text scan, computed once from the parse
     *  tree (so it also catches the threshold quantifiers the text scan missed, and is immune to casing/spacing). */
    private static boolean isSetOnderwerp(OnderwerpContext o) {
        if (o == null || o.kwantificatie() == null) return false;
        KwantificatieContext q = o.kwantificatie();
        return !(q.DE() != null && q.getChildCount() == 1);     // bare "de" = one object; every other quantor = a set
    }

    /** §13.4.14.5 the WRITTEN quantifier of {@code o}, as the IR's own {@link Conditie.Kwantor} vocabulary —
     *  what {@link #isSetOnderwerp} throws away when it collapses every non-bare quantor to one bit.
     *  Recording which one the sentence used is exactly what lets a consumer (today: the §8.2
     *  {@link Conditie.Kwantorvouw} lowering) answer the quantifier that was WRITTEN, instead of reading
     *  them all as a forall. Null for the bare definite "de", which names one object and no set at all. */
    private static Conditie.Kwantor geschrevenKwantor(OnderwerpContext o) {
        return o == null ? null : geschrevenKwantor(o.kwantificatie());
    }
    private static Conditie.Kwantor geschrevenKwantor(KwantificatieContext q) {
        if (q == null) return null;
        if (q.ALLE() != null || q.AL() != null) return Conditie.Kwantor.ALLE;
        if (q.GEEN_VAN_DE() != null)            return Conditie.Kwantor.GEEN;
        if (q.TEN_MINSTE() != null)             return Conditie.Kwantor.TEN_MINSTE;
        if (q.TEN_HOOGSTE() != null)            return Conditie.Kwantor.TEN_HOOGSTE;
        if (q.PRECIES() != null)                return Conditie.Kwantor.PRECIES;
        return null;                            // bare "de"
    }

    /** §13.4.14.4 the telwoord N of a COUNTING quantifier on {@code o} ("ten minste 2 van de …"), 0 when the
     *  written quantor carries none. It travels beside {@link #geschrevenKwantor} for the same reason that
     *  one does: without it "ten minste 2 van de" and "ten minste 3 van de" reach a consumer as one and the
     *  same sentence. An unparseable or absent telwoord stays 0 and is refused downstream (WF-041), never
     *  silently read as 1. */
    private static int geschrevenDrempel(OnderwerpContext o) {
        return o == null ? 0 : geschrevenDrempel(o.kwantificatie());
    }
    private static int geschrevenDrempel(KwantificatieContext q) {
        return q == null ? 0 : RuleChecker.telwoordWaarde(q.telwoord());
    }

    /** The facts a QUANTIFIED MEMBER PHRASE carries, read off the parse tree: the written quantor, its
     *  telwoord, the element type it ranges over, and the written source (the {@code onderwerpBron} a
     *  range binding is looked up by).
     *
     *  <p>{@code reden}, when non-null, means the phrase IS a member phrase but the fold declines it, and
     *  names the axis it declined on — the {@link Deelselectie} idiom, for the same reason: one generic
     *  text for every shape tells the author nothing. The other fields are then empty. */
    record GekwantificeerdOnderwerp(Conditie.Kwantor kwantor, int drempel, Objecttype element,
                                    String onderwerpBron, String reden) {
        GekwantificeerdOnderwerp(Conditie.Kwantor kwantor, int drempel, Objecttype element, String onderwerpBron) {
            this(kwantor, drempel, element, onderwerpBron, null);
        }
        /** A declined member phrase: the written source and the axis it was declined on. */
        static GekwantificeerdOnderwerp geweigerd(String onderwerpBron, String reden) {
            return new GekwantificeerdOnderwerp(null, 0, null, onderwerpBron, reden);
        }
    }

    /**
     * §13.4.2.9 / ch. 4.5: the quantified member phrase an expression consists OF — "alle wachten van
     * de Schouwploeg" as the WHOLE left operand of a §8.1.8 kenmerkcheck. The §13.4.2.9
     * {@code kwantificatie? attribuutMetLidwoord VAN onderwerpKeten} puts the quantifier on the
     * attribuutVanOnderwerp ITSELF there, not on an inner onderwerp, so the phrase resolves as "the
     * &lt;wachten&gt; of the Schouwploeg" — a reference whose {@code set} bit is false and whose
     * {@code kwantor} is null, which is why the fold's {@link RuleChecker} operand scan finds nothing
     * to quantify. This is the parse-tree reading of the same facts, for the caller that folds over a
     * MEMBERSHIP matrix instead of a value one.
     *
     * <p>Null for everything that is not this shape AT ALL — the caller then has nothing to say about a
     * fold, because the sentence writes none. A phrase that IS the shape but declines comes back
     * {@link GekwantificeerdOnderwerp#geweigerd geweigerd}, naming its axis: the bare definite "de" over
     * a DECLARED PLURAL (one object, no set, but written as a set), a member phrase whose element type
     * does not resolve — quantifying over an unresolved population is the silent-wrong-set failure a
     * marker is preferable to — and a MULTI-STEP written range ("alle wachten van alle ploegen van de
     * Haven"), whose {@code bronPad} spelling this lowering does not carry and whose first step is not
     * the set the sentence names.
     *
     * <p>The bare "de" declines QUIETLY (null, not geweigerd) unless the member name is a declared
     * plural: "de reis van de passagier" is an ordinary to-one navigation, and telling its author that a
     * quantifier fold declined would name a reading the sentence never had.
     */
    GekwantificeerdOnderwerp gekwantificeerdOnderwerp(ExpressieContext e) {
        PrimaireExpressieContext p = e instanceof PrimaireExprContext pe ? pe.primaireExpressie() : null;
        AttribuutVanOnderwerpContext a = p == null ? null : p.attribuutVanOnderwerp();
        if (a == null || a.attribuutMetLidwoord() == null || a.attribuutMetLidwoord().kaleNaam() == null
                || a.onderwerp() == null) return null;
        Conditie.Kwantor q = geschrevenKwantor(a.kwantificatie());
        String leden = src(a.attribuutMetLidwoord().kaleNaam());
        if (q == null)
            return !isGedeclareerdMeervoud(leden) ? null : GekwantificeerdOnderwerp.geweigerd(src(a),
                    "de zin leest als een kwantificatie over '" + leden + "' maar schrijft geen kwantor: "
                    + "de kale 'de' noemt één object en geen verzameling (§13.4.14.5 kent alle | geen van de "
                    + "| ten minste/ten hoogste/precies <telwoord> van de), dus er valt niets te vouwen");
        // exactly ONE written hop: a chain quantifies a set this lowering cannot spell (see above). The
        // greedy kaleNaam swallows an inner to-one step, so a written "van" on EITHER side of the hop —
        // in the member phrase or in the anchor — is what tells the two apart (the quantifier's own "van
        // de" is not part of either, which is why this is not counted over the whole phrase).
        if (isSetOnderwerp(a.onderwerp()) || heeftVanStap(leden) || heeftVanStap(src(a.onderwerp())))
            return GekwantificeerdOnderwerp.geweigerd(src(a),
                    "een meerstapsbereik: de zin stapelt nog een verzameling onder de gekwantificeerde, en een "
                    + "kwantorvouw noemt precies één relatie vanaf het regelonderwerp (IR-SPEC ch. 4.5) — de "
                    + "eerste stap vouwen zou een andere verzameling kwantificeren dan de zin noemt");
        Objecttype elem = model.resolveObjecttype(leden);
        if (elem == null) elem = typeViaMeervoud(leden);
        return elem != null
                ? new GekwantificeerdOnderwerp(q, geschrevenDrempel(a.kwantificatie()), elem, src(a))
                : GekwantificeerdOnderwerp.geweigerd(src(a),
                    "'" + leden + "' is geen gedeclareerd objecttype en geen gedeclareerd meervoud daarvan, dus "
                    + "het bereik van de kwantor ligt niet vast — kwantificeren over een onbekende populatie "
                    + "zou een andere verzameling toetsen dan de zin noemt");
    }

    /** Whether {@code naam} is a DECLARED plural — an objecttype's "(mv: …)" or a feittype role's. It is
     *  what tells a bare "de &lt;leden&gt; van de &lt;X&gt;" written as a SET from an ordinary to-one
     *  navigation ("de reis van de passagier"), so only the former hears that a quantifier fold declined.
     *  Declared forms only: RegelSpraak defines no plural morphology (§3.1 footnote 2), and a synthesised
     *  plural would put that message on sentences that never read as a quantification. */
    private boolean isGedeclareerdMeervoud(String naam) {
        if (naam == null) return false;
        for (Objecttype ot : model.objecttypen.values())
            if (ot.meervoud != null && ot.meervoud.equalsIgnoreCase(naam)) return true;
        for (RegelSpraakModel.Feittype ft : model.feittypen.values())
            for (RegelSpraakModel.Rol r : ft.rollen)
                if (r.meervoud() != null && r.meervoud().equalsIgnoreCase(naam)) return true;
        return false;
    }

    /** Whether a written name carries a " van " navigation step of its own. */
    private static boolean heeftVanStap(String naam) {
        return naam != null && naam.toLowerCase().matches(".*\\s+van\\s+.*");
    }

    /** A possessive "zijn <kaleNaam>" resolves to the named attribute OF the rule subject,
     *  when it is one (a "zijn <rol>" navigation is not an attribute and is left to the
     *  generic path). Returns null when it is not a subject-attribute possessive. */
    private Expr possessief(OnderwerpContext o, Bron bron) {
        if (huidigSubject == null || o.onderwerpKeten() == null) return null;
        OnderwerpKetenContext k = o.onderwerpKeten();
        if (k.ZIJN() == null || k.kaleNaam() == null) return null;
        String attr = src(k.kaleNaam());
        Datatype dt = huidigSubject.attribuut(attr);
        // a subject-own attribute — no role navigation
        if (dt != null) return new Expr.AttribuutRef(attr, huidigSubject, "zijn", null, dt, bron);
        // §3.6 the bare name may be a gedimensioneerd attribuut READ with the label woven in
        // ("zijn betaalde belasting in jaar van huidig jaar"): peel "<voorzetsel> <label>" off the run. An
        // own-attribute read carries no navigation role (navRol null), exactly as the scalar branch above.
        Expr cel = dimensieCel(attr, huidigSubject, null, "zijn", bron);
        if (cel != null) return cel;
        // §8.1.7/§8.1.8 a possessive that names a feittype ROLE the subject reaches ("zijn partner een <k>
        // heeft") is a to-one navigation, not an own attribute — emit the SAME navigated AttribuutRef shape
        // bouwAttribuut produces for the written "de <rol> van <X>", so a navigated kenmerkcheck reaches
        // navigatieKenmerk and a navigated value read reaches the to-one getter (the symmetric mirror of the
        // working "de <rol> van <X>" form; without it the possessive role stayed Onopgelost and markered).
        if (model.rolDoeltypeNaarSubject(huidigSubject, attr) != null)
            return new Expr.AttribuutRef(attr, huidigSubject, "zijn", model.navigatieRolNaam(attr), onbekend(), bron);
        // §8.1.8.1 the greedy onderwerp can absorb a TRAILING "niet"/"geen" negator into the possessive role
        // ("zijn reis niet <kenmerk>" -> role "reis niet", unresolvable). Strip it to resolve the role, but keep the
        // FULL bron so navigatieKenmerk/navigatieStaart recover the negator onto the kenmerk and the §8.1.8 navigated
        // negation de-negates (instead of markering). Verbatim-first above means a role literally ending in
        // niet/geen still binds (it resolved there); only an unresolvable role reaches this split.
        for (String neg : new String[]{" niet", " geen"}) {   // SPACE-bounded (mirror bindKenmerk) — never split a glued "<rol>geen"
            if (attr.regionMatches(true, attr.length() - neg.length(), neg, 0, neg.length())) {
                String kaal = attr.substring(0, attr.length() - neg.length()).trim();
                if (kaal.isEmpty()) continue;
                if (model.rolDoeltypeNaarSubject(huidigSubject, kaal) != null)
                    return new Expr.AttribuutRef(kaal, huidigSubject, "zijn", model.navigatieRolNaam(kaal), onbekend(), bron);
                // §8.1.3/§8.1.4 a possessive ATTRIBUTE with a greedily-absorbed trailing "niet" (the vragende negated
                // elfproef / numeriek-met-exact "zijn <attr> niet aan de elfproef voldoet"): resolve the BARE
                // attribuut so the read lowers, keeping the FULL bron so the predicate builder recovers the negation.
                Datatype adt = huidigSubject.attribuut(kaal);
                if (adt != null) return new Expr.AttribuutRef(kaal, huidigSubject, "zijn", null, adt, bron);
            }
        }
        // §3.5 a possessive that names a ROLE followed by an ARTICLE-led kenmerk ("zijn reis HET recht op korting
        // heeft") absorbs "reis het recht op" into the role kaleNaam (an article is a kaleNaamWoord, unlike the EEN
        // form whose "een" stays a separate operator token). Find the LONGEST word-prefix that resolves as a role and
        // emit the navigated AttribuutRef for it, keeping the FULL bron — navigatieStaart then recovers the absorbed
        // tail ("het recht op") and navigatieKenmerk rejoins it with the operator's kaleNaam ("korting") -> the kenmerk
        // "recht op korting". Longest-first so a multi-word role wins; a non-empty remainder (n < length) means the
        // exact-role case above already handled n == length.
        String[] woorden = attr.split("\\s+");
        for (int n = woorden.length - 1; n >= 1; n--) {
            String prefix = String.join(" ", java.util.Arrays.copyOfRange(woorden, 0, n));
            if (model.rolDoeltypeNaarSubject(huidigSubject, prefix) != null)
                return new Expr.AttribuutRef(prefix, huidigSubject, "zijn", model.navigatieRolNaam(prefix), onbekend(), bron);
        }
        return null;
    }

    /** §3.6 resolve {@code kaleNaam} as a single-cell read of a gedimensioneerd attribuut of {@code ot} —
     *  the bare attribuut followed by the dimension's read preposition and a declared label
     *  ("betaalde belasting in jaar van huidig jaar" = attribuut "betaalde belasting in jaar" + jaardimensie
     *  label "huidig jaar"). Returns the {@link Expr.DimensieCel}, or null when {@code kaleNaam} is not such a
     *  read. Scope (the rest stays an honest marker): a SINGLE-dimension attribute (a multi-dimension cell
     *  needs every label and a composite-key model) read via the SUFFIX voorzetsel form (the prefix
     *  "&lt;label&gt; &lt;attribuut&gt;" form is deferred). The longest matching attribute wins, so a
     *  dimensioned name is not shadowed by a shorter scalar prefix of it. */
    private Expr dimensieCel(String kaleNaam, Objecttype ot, String navRol, String onderwerpBron, Bron bron) {
        if (ot == null || kaleNaam == null) return null;
        String beste = null; String besteLabel = null; RegelSpraakModel.Dimensie besteDim = null;
        for (var e : ot.gedimensioneerdeLeden().entrySet()) {   // own AND inherited (ch. 2.2)
            String attr = e.getKey();
            if (e.getValue().size() != 1) continue;                 // multi-dimension cell deferred
            RegelSpraakModel.Dimensie d = model.dimensieOpNaam(e.getValue().get(0));
            if (d == null || d.voorzetsel() == null) continue;      // prefix-form (no voorzetsel) deferred
            String prefix = attr + " " + d.voorzetsel() + " ";
            if (kaleNaam.length() <= prefix.length()
                    || !kaleNaam.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            String label = labelVanDimensie(d, kaleNaam.substring(prefix.length()).trim());
            if (label == null) continue;
            if (beste == null || attr.length() > beste.length()) { beste = attr; besteLabel = label; besteDim = d; }
        }
        if (beste == null) return null;
        Datatype dt = ot.attribuut(beste);
        Expr.AttribuutRef ref = new Expr.AttribuutRef(beste, ot, onderwerpBron, navRol, dt, bron);
        return new Expr.DimensieCel(ref, List.of(besteLabel), bron);
    }

    /** The declared label of {@code d} that equals {@code naam} case-insensitively (returning the declared
     *  spelling), or null. */
    private static String labelVanDimensie(RegelSpraakModel.Dimensie d, String naam) {
        for (String w : d.waarden()) if (w.equalsIgnoreCase(naam)) return w;
        return null;
    }

    /** A bare name: a known parameter binds to a ParameterRef; a variabeleNaam that is
     *  not a parameter stays a (so-far untyped) VariabeleRef; anything else is unresolved. */
    private Expr naamRef(String naam, boolean isVariabele, Bron bron) {
        // §11 resolve the captured "Daarbij geldt:" definition FIRST: a local variable reference works WITH or WITHOUT
        // a leading article (the spec explicitly allows a lidwoord at a variable reference — "de drempel" arrives via
        // the onderwerp path with isVariabele=false; variabeleSleutel strips the lidwoord so both forms hit one key),
        // and it SHADOWS a same-named global parameter (a variable "geldt alleen binnen de regel"). A name with no
        // definition (or a forward/cyclic reference, not yet in the map) falls through to the parameter / VariabeleRef.
        String sleutel = variabeleSleutel(naam);
        if (binderModus) {
            // The RESULT route: a BinderRef under the Let chain, typed with the definition's own type — the type the
            // inlined subtree would have carried, so every downstream typering claim is unchanged.
            Variabele v = binders.get(sleutel);
            if (v != null) return new Expr.BinderRef(v.naam(), v.waarde().type(), bron);
        } else {
            Expr inlined = zichtbaar.get(sleutel);
            if (inlined != null) return inlined;
        }
        String stripped = naam.replaceFirst("^(?i)(de|het|een)\\s+", "");
        Parameter p = model.parameters.get(stripped);
        if (p == null) p = model.parameters.get(naam);
        if (p != null) return new Expr.ParameterRef(p, bron);
        if (isVariabele) return new Expr.VariabeleRef(naam, onbekend(), bron);
        return onopgelost(bron,
                "de naam '" + naam + "' is geen gedeclareerde parameter, geen variabele uit een "
                + "'Daarbij geldt:'-deel en geen attribuut van het onderwerp (§11/§13.3.8)");
    }

    /**
     * The object type a subject denotes: first via its primary noun (resolved
     * against object-type names and feittype roles, §13.4.1), then — as a fallback
     * for navigation shapes the primary-noun extraction does not cover — the older
     * deep scan for a directly-named object type. Returns null when neither binds.
     */
    Objecttype resolveOnderwerp(OnderwerpContext o) {
        String n = primaireNaam(o);
        Objecttype ot = n != null ? model.resolveObjecttype(n) : null;
        if (ot == null) ot = typeViaMeervoud(n);
        return ot != null ? ot : vindObjecttype(o);
    }

    /**
     * The declared object type a written PLURAL names ("alle Hoofdbergers"), for the positions that
     * name a POPULATION rather than navigate to one: a set onderwerp and a count's element word.
     *
     * <p>{@link RegelSpraakModel#resolveObjecttype} keys object types on their declared SINGULAR and
     * otherwise reads the name as a ROLE (name or declared plural). That covers a population whose
     * name happens to be a role's — "alle bergers" binds Berger through the role {@code berger
     * (mv: bergers)} — and covers nothing else. A population named by a SUBTYPE has no role of its
     * own by construction: the relation is declared once, on the ancestor the subtype inherits from
     * (ch. 2.6 makes a filler an UPPER bound), so "alle Hoofdbergers" matched no object-type key and
     * no role and resolved to nothing at all. The reach and the population are two different
     * questions (ch. 2.2: quantification is extensional at every type-ranging position), and this
     * answers the second one; the role binding answers the first and already resolves through ⊑.
     *
     * <p>Tried after the singular and the role reading, and BEFORE the older deep parse-tree scan —
     * a written plural that names a declared type is answered here rather than by that scan's
     * deepest-match guess. Every singular and every role keeps resolving exactly as it did. The
     * matcher is ambiguity-safe on EVERY pass (a shared declared {@code (mv: …)} binds neither, and
     * the un-bound name then falls loudly on WF-034 instead of silently picking a type), so this
     * never introduces a first-hit bind.
     */
    private Objecttype typeViaMeervoud(String naam) {
        if (naam == null) return null;
        for (String kandidaat : RegelSpraakModel.naamKandidaten(naam)) {
            Objecttype ot = MeervoudMatcher.vindObjecttype(model.objecttypen.values(), kandidaat);
            if (ot != null) return ot;
        }
        return null;
    }

    /** The object type an onderwerpketen denotes (a rule subject), via its primary noun. */
    Objecttype objecttypeVanKeten(OnderwerpKetenContext k) {
        String n = primaireNaamKeten(k);
        return n != null ? model.resolveObjecttype(n) : null;
    }

    /**
     * The role noun for a navigation reference: the noun of a DEFINITE singular
     * reference ("de reis" — parsed either as a kwantificatie "de" or an
     * onderwerpVerwijzing) or a possessive ("zijn partner"). An indefinite "een &lt;X&gt;"
     * (universeelOnderwerp) is the rule's universal subject, and a set quantifier
     * (alle/geen/ten minste …) ranges over a set — neither is a single-object role
     * navigation, so both yield null and the reference stays an own-attribute read.
     */
    private String navigatieNoun(OnderwerpContext o) {
        if (o == null) return null;
        if (o.kwantificatie() != null && o.kaleNaam() != null) // alt: "de <X>" (only the bare definite, not "alle"/"ten minste … van de")
            return o.kwantificatie().DE() != null && o.kwantificatie().getChildCount() == 1 ? src(o.kaleNaam()) : null;
        return navigatieNoun(o.onderwerpKeten());
    }
    private String navigatieNoun(OnderwerpKetenContext k) {
        if (k == null) return null;
        if (k.onderwerpVerwijzing() != null) return src(k.onderwerpVerwijzing().kaleNaam()); // "de reis"
        if (k.ZIJN() != null && k.kaleNaam() != null) return src(k.kaleNaam());              // "zijn partner"
        if (k.onderwerpKeten() != null) return navigatieNoun(k.onderwerpKeten());            // … die/dat predicaat
        return null; // universeelOnderwerp ("een <X>" = subject) / selector-van: not a single role navigation
    }

    /** The primary (head) noun of a subject — used by the resolver's §13.4.7 feitcreatie
     *  back-reference check to see whether onderwerpD directly names the anker objecttype. */
    String primaireNaamVanOnderwerp(OnderwerpContext o) { return primaireNaam(o); }

    /** The subject's primary noun — the entity an attribute attaches to. */
    private String primaireNaam(OnderwerpContext o) {
        if (o == null) return null;
        if (o.kaleNaam() != null) return src(o.kaleNaam());   // kwantificatie kaleNaam (van …)?
        return primaireNaamKeten(o.onderwerpKeten());
    }
    private String primaireNaamKeten(OnderwerpKetenContext k) {
        if (k == null) return null;
        if (k.universeelOnderwerp() != null) return src(k.universeelOnderwerp().kaleNaam());
        if (k.onderwerpVerwijzing() != null) return src(k.onderwerpVerwijzing().kaleNaam());
        if (k.ZIJN() != null && k.kaleNaam() != null) return src(k.kaleNaam()); // "zijn reis"
        if (k.onderwerpKeten() != null) return primaireNaamKeten(k.onderwerpKeten());
        return null; // selector-van / other navigation: leave to the deep-scan fallback
    }

    /** Find the object type a subject denotes by matching any name in its subtree against the model. */
    private Objecttype vindObjecttype(ParseTree t) {
        if (t == null) return null;
        Objecttype found = null;
        if (t instanceof ParserRuleContext ctx) {
            Objecttype direct = model.objecttypen.get(src(ctx));
            if (direct != null) found = direct;
        }
        for (int i = 0; i < t.getChildCount(); i++) {
            Objecttype c = vindObjecttype(t.getChild(i));
            if (c != null) found = c; // prefer the deepest/last match (the base subject)
        }
        return found;
    }

    // ---------------- helpers ----------------
    private static Typering.BinOp naarTyperingOp(Expr.BinOp op) {
        return switch (op) {
            case PLUS -> Typering.BinOp.PLUS;
            case MIN -> Typering.BinOp.MIN;
            case VERMINDERD_MET -> Typering.BinOp.VERMINDERD_MET;
            case MAAL -> Typering.BinOp.MAAL;
            case GEDEELD_DOOR, GEDEELD_DOOR_ABS -> Typering.BinOp.GEDEELD_DOOR;
            case TOT_DE_MACHT -> Typering.BinOp.TOT_DE_MACHT;
            case PERCENTAGE_VAN -> Typering.BinOp.MAAL; // not reached: handled inline
        };
    }

    // ---------------- §13.2.14 enumeratiewaarde: the domain comes from the POSITION ----------------

    /** The reason an enumeratiewaarde marker carries until a position types it. One constant, because
     *  {@link #enumTegenContext} recognises the marker by it. */
    static final String REDEN_ENUM_ZONDER_DOMEIN =
            "een enumeratiewaarde ontleent haar domein aan de context (§13.3.4.2), en deze plaats wijst er "
            + "geen aan — geen schrijfdoel en geen vergelijkingszijde met een Enumeratie-datatype";

    /** §13.2.14 an enumeratiewaarde, built as a MARKER that the positions below promote to a typed
     *  {@link Expr.Literal}. Marker-first is the fail-fast half of uchar's V8 decision (2026-09-19): the
     *  literal names a LABEL and not a domain, and which domain applies is a property of where it stands,
     *  so a builder that types it here would have to guess one. What the wire admits is only the promoted
     *  form — WF-039 crosses the BARE label byte-exactly against the declared value list and WF-034 (r)
     *  refuses the unknown sentinel — so an unpromoted marker is the honest form of "no domain derivable",
     *  and {@code Resolver}'s marker discipline raises a WARNING over every one that survives.
     *
     *  <p>{@code lexeem} is the ENUM_LITERAL TOKEN text, and the bron snippet is that token rather than
     *  {@link DefinitionResolver#src}: an ENUM_LITERAL is one lexer token, so the token IS the whole source
     *  slice, while src() folds every whitespace run to a single space. The DECLARATION keeps the token text
     *  ({@code DefinitionResolver.enumeratie}), so a folded label would not equal a declared value carrying a
     *  whitespace run — before this, such a value could not be written at all: the membership check read the
     *  folded snippet and refused a correct document. The bron is also where {@link #enumTegenContext} reads
     *  the label back from, which is why the marker must carry the unfolded token. */
    private Expr enumWaarde(ParserRuleContext ctx, String lexeem) {
        return onopgelost(Bron.van(ctx, lexeem), REDEN_ENUM_ZONDER_DOMEIN);
    }

    /** Promote an enumeratiewaarde marker to the domain {@code context} names, or hand back {@code kandidaat}
     *  untouched. The two contexts that name one are the two the §13.3.4.2 membership check already reads: a
     *  WRITE TARGET's declared datatype (a rule's doel, an objectcreatie member) and the OTHER side of a
     *  comparison. The label is the source lexeme minus its delimiters, via the shared reader — total and
     *  lossless, since the grammar makes the delimiters the token's first and last character.
     *
     *  <p>The domain is taken from the context even when the label is NOT one of its values: that case is an
     *  ERROR the membership check raises, and an unresolvable model writes no IR at all, so the ill-formed
     *  wire is never reached. Typing it anyway keeps the label visible to that check, which is what reports
     *  the author's actual mistake.
     *
     *  <p>It descends through the ch. 4.10 Let chain a variabelendeel builds, because such a chain DENOTES its
     *  value (ch. 10.6): {@code Daarbij geldt: de tint is 'gaaf'} writes exactly what the bare literal writes
     *  and must reach the wire in the same shape. Rebuilding the chain is not cosmetic — the Let and its
     *  BinderRef are typed FROM the value, so an unpromoted chain carries the unknown sentinel at three
     *  positions WF-034 (r) refuses instead of one. The descent follows the shape {@code RuleChecker.afgepeld}
     *  follows, and stops where that one still resolves: a body that reads an OUTER binder past an inner Let
     *  stays a marker, loud and honest, rather than being half-promoted. */
    static Expr enumTegenContext(Expr kandidaat, Datatype context) {
        if (!(context instanceof Datatype.Enumeratie)) return kandidaat;
        if (kandidaat instanceof Expr.Onopgelost o && REDEN_ENUM_ZONDER_DOMEIN.equals(o.reden()))
            return new Expr.Literal(Literalen.enumLabel(o.bron().snippet()), context, o.bron());
        if (kandidaat instanceof Expr.Let l) {
            if (l.body() instanceof Expr.BinderRef b && b.naam().equals(l.binder())) {
                Expr waarde = enumTegenContext(l.value(), context);
                if (waarde == l.value()) return kandidaat;
                return new Expr.Let(l.binder(), waarde,
                        new Expr.BinderRef(b.naam(), context, b.bron()), context, l.bron());
            }
            Expr body = enumTegenContext(l.body(), context);
            if (body == l.body()) return kandidaat;
            return new Expr.Let(l.binder(), l.value(), body, body.type(), l.bron());
        }
        return kandidaat;
    }

    /** The label of a value that IS an enumeratiewaarde — the bare label of a promoted literal, or the lexeme
     *  of a marker no position promoted. Both arms carry: the §13.3.4.2 membership check must report an
     *  undeclared label wherever it stands, also in a shape {@link #enumTegenContext} leaves alone. null for
     *  anything else, which that check reads as "no claim". */
    static String enumLabelVanWaarde(Expr e) {
        if (e instanceof Expr.Literal l && l.type() instanceof Datatype.Enumeratie) return l.waarde();
        if (e instanceof Expr.Onopgelost o && REDEN_ENUM_ZONDER_DOMEIN.equals(o.reden()))
            return Literalen.enumLabel(o.bron().snippet());
        return null;
    }

    /** A comparison, with each operand offered the other's datatype as its enumeratiewaarde context. Every
     *  {@link Conditie.Vergelijking} the frontend builds goes through here, so the rule holds of comparisons
     *  as such rather than of the handful of shapes a card happened to exercise.
     *
     *  <p>One caller reaches it only for operands that are already narrowed: {@link #elementVergelijking}
     *  (the §5.6 die/dat subselectie on an aggregation operand) refuses any right-hand side that is neither
     *  a Literal nor a ParameterRef BEFORE building the comparison, so an enumeratiewaarde marker never
     *  arrives here and the whole predicaat is refused instead — an Onopgelost conditie or a reason-carrying
     *  deelselectie, never a silently untyped comparison. That is the pre-existing narrowing of that
     *  position, not a hole this rule opened: the position folds an AGGREGATED attribute, and no aggregation
     *  ranges over an Enumeratie, so a well-typed enum comparison cannot stand there in the first place. */
    static Conditie.Vergelijking vergelijking(Conditie.Operator op, Expr links, Expr rechts,
                                              Conditie.GeheelPeriode periode, Bron bron) {
        Expr l = enumTegenContext(links, rechts == null ? null : rechts.type());
        Expr r = enumTegenContext(rechts, links == null ? null : links.type());
        return new Conditie.Vergelijking(op, l, r, periode, bron);
    }

    static Conditie.Vergelijking vergelijking(Conditie.Operator op, Expr links, Expr rechts, Bron bron) {
        return vergelijking(op, links, rechts, null, bron);
    }

    private static String src(ParserRuleContext ctx) { return DefinitionResolver.src(ctx); }
    private static io.github.yhavinga.regelspraak.ir.Bron bron(ParserRuleContext ctx) { return DefinitionResolver.bron(ctx); }
    private static Datatype onbekend() { return new Datatype.Onbekend("niet opgelost"); }
    /** An unresolved expression marker carrying its {@code reden} — the Dutch sentence the frontend's
     *  survivor report raises over it when it reaches the finished model. {@code null} where the calling
     *  route already raises its own diagnostic (see {@link Expr.Onopgelost#reden()}). */
    private Expr onopgelost(ParserRuleContext ctx, String reden) { return new Expr.Onopgelost(onbekend(), bron(ctx), reden); }
    private Expr onopgelost(io.github.yhavinga.regelspraak.ir.Bron bron, String reden) { return new Expr.Onopgelost(onbekend(), bron, reden); }
}
