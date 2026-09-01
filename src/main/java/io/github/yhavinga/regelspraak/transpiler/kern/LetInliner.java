package io.github.yhavinga.regelspraak.transpiler.kern;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Default;
import io.github.yhavinga.regelspraak.ir.ExceptionGroup;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.PrioritizedDefaults;
import io.github.yhavinga.regelspraak.ir.ResolvedConsistentie;
import io.github.yhavinga.regelspraak.ir.ResolvedDagsoort;
import io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie;
import io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedVerdeling;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The toolchain-local Let-normaliser: it rewrites a resolved model into the equivalent Let-FREE model both
 * back-ends were written against, and then proves the rewrite was total.
 *
 * <p><b>Why it exists.</b> Since uchar's let-lowering (IR-SPEC ch. 4.10 / RUNTIME-SEMANTICS ch. 10.6) the
 * frontend keeps a rule's §13.4.2.10 variabelendeel as a call-by-need {@link Expr.Let} chain around the
 * RESULT part, with each read an {@link Expr.BinderRef}. That is the wire uchar's spec, verifier, reference
 * interpreter and Clingo backend agree on. This toolchain's two Drools back-ends — and the eighteen-odd
 * auxiliary Expr walks around them (stratification, dagsoorten, periodedekking, producent/consument, the
 * verbalisator) — predate it and read a BinderRef as an opaque leaf: an {@code instanceof Expr.AttribuutRef}
 * shape test fails, the construct degrades to an honest MARKER and the rule stops firing. Inlining restores
 * EXACTLY the v0 tree those walks were written for, because inlining is literally what the resolver's
 * voorwaarde route (and every aggregation subtree, boundary c) still does today.
 *
 * <p><b>What is lost, and why it is nothing.</b> Substitution replaces sharing by re-evaluation: a variable
 * read twice is evaluated twice. Under ch. 10.6 the values agree — the frontend's own
 * {@code ExpressieBouwer.zetVariabelen} javadoc records the same trade for the voorwaarde route — so no case
 * observes the difference. Porting the Let cluster into the two back-ends proper is the separate R1 card.
 *
 * <p><b>The Let-free contract.</b> {@link #normaliseer} rewrites the slots that can carry a Let and then
 * SCANS THE WHOLE MODEL. A surviving Let or BinderRef anywhere throws, naming the construct: the rewrite is
 * never partially applied and a slot the frontend starts lowering tomorrow surfaces as a loud failure here
 * rather than as a silently inert rule in the DRL.
 */
public final class LetInliner {
    private LetInliner() {}

    /**
     * Rewrite {@code m} in place into its Let-free equivalent, then enforce the Let-free contract over the
     * whole model. Idempotent (a Let-free model is returned untouched, node identity included) and safe to
     * call from both back-ends' constructors on a shared model.
     *
     * <p>The rewritten slots are every one the RegelSpraak frontend can lower a Let INTO:
     * <ul>
     *   <li>{@code resolvedRegels} — a rule's {@code waarde}, the one slot {@code bouwResultaat} builds the
     *       chain for. Its {@code doel} and {@code voorwaarde} ride along: they are substitution-route today,
     *       but they are components of the same record this pass rebuilds anyway. The §9.9 startpuntbepaling
     *       anchor needs no slot of its own — {@code RuleChecker} registers it AS a rule's {@code waarde}.</li>
     *   <li>{@code prioritizedDefaults} — the second wire place. The §4 Uitzondering-op pass lifts a base
     *       rule out of {@code resolvedRegels} into a {@link Default}, copying {@code r.waarde()} verbatim
     *       into {@code consequentie}; the Let travels with it and the rule that carried it is gone from the
     *       list above. Nested {@link ExceptionGroup}s are covered recursively.</li>
     * </ul>
     */
    public static void normaliseer(RegelSpraakModel m) {
        for (int i = 0; i < m.resolvedRegels.size(); i++) {
            ResolvedRegel r = m.resolvedRegels.get(i);
            Expr doel = inl(r.doel(), Map.of());
            Expr waarde = inl(r.waarde(), Map.of());
            Conditie voorwaarde = inl(r.voorwaarde(), Map.of());
            if (doel != r.doel() || waarde != r.waarde() || voorwaarde != r.voorwaarde())
                m.resolvedRegels.set(i, new ResolvedRegel(r.naam(), r.soort(), doel, waarde, voorwaarde,
                        r.geldigheid(), r.bronAnnotaties()));
        }
        for (int i = 0; i < m.prioritizedDefaults.size(); i++) {
            PrioritizedDefaults p = m.prioritizedDefaults.get(i);
            List<Default> ds = inlDefaults(p.defaults());
            if (ds != p.defaults())
                m.prioritizedDefaults.set(i, new PrioritizedDefaults(p.naam(), p.onderwerp(), p.kenmerk(),
                        p.attribuut(), p.ongeordend(), ds, p.bron(), p.geldigheid()));
        }
        eisLetVrij(m);
    }

    // ---------------- the Let-free contract ----------------

    /** A Let or a BinderRef — the two nodes no back-end walk understands. An UNBOUND BinderRef is caught by
     *  the same test on purpose: substitution leaves it in place, so a malformed chain fails loudly here
     *  instead of degrading to a marker somewhere downstream. */
    private static final java.util.function.Predicate<Expr> LET_CLUSTER =
            x -> x instanceof Expr.Let || x instanceof Expr.BinderRef;

    /** Scan EVERY Expr- and Conditie-carrying slot the resolved model has — not only the rewritten ones, so a
     *  slot this pass does not cover is a hard failure rather than a silent inert construct. */
    private static void eisLetVrij(RegelSpraakModel m) {
        for (ResolvedRegel r : m.resolvedRegels) {
            eis(r.doel(), "regel '" + r.naam() + "' (doel)");
            eis(r.waarde(), "regel '" + r.naam() + "' (waarde)");
            eis(r.voorwaarde(), "regel '" + r.naam() + "' (voorwaarde)");
        }
        for (ResolvedDagsoort d : m.resolvedDagsoorten)
            eis(d.voorwaarde(), "dagsoortdefinitie '" + d.naam() + "'");
        for (ResolvedObjectCreatie oc : m.objectCreaties) {
            eis(oc.voorwaarde(), "objectcreatie '" + oc.naam() + "' (voorwaarde)");
            for (ResolvedObjectCreatie.Toekenning t : oc.toekenningen())
                eis(t.waarde(), "objectcreatie '" + oc.naam() + "' (toekenning '" + t.attribuut() + "')");
        }
        for (ResolvedVerdeling v : m.verdelingen)
            eis(v.voorwaarde(), "verdeling '" + v.naam() + "' (voorwaarde)");
        for (ResolvedConsistentie c : m.consistenties)
            eis(c.assertie(), "consistentieregel '" + c.naam() + "' (assertie)");
        for (ResolvedFeitCreatie f : m.feitCreaties)
            eis(f.voorwaarde(), "feitcreatie '" + f.naam() + "' (voorwaarde)");
        for (PrioritizedDefaults p : m.prioritizedDefaults)
            eisDefaults(p.defaults(), "defaults-groep '" + p.naam() + "' op '" + p.doel() + "'");
    }

    private static void eisDefaults(List<Default> ds, String waar) {
        if (ds == null) return;
        for (int i = 0; i < ds.size(); i++) {
            Default d = ds.get(i);
            eis(d.voorwaarde(), waar + " (default " + i + ", voorwaarde)");
            eis(d.consequentie(), waar + " (default " + i + ", consequentie)");
            if (d.excepties() != null)
                for (ExceptionGroup g : d.excepties().leden())
                    eisDefaults(g.defaults(), waar + " (default " + i + ", exceptiegroep)");
        }
    }

    private static void eis(Expr e, String waar) {
        if (Expr.bevat(e, LET_CLUSTER)) throw fout(waar);
    }

    private static void eis(Conditie c, String waar) {
        if (Conditie.bevatOperand(c, LET_CLUSTER)) throw fout(waar);
    }

    private static IllegalStateException fout(String waar) {
        return new IllegalStateException("Let-vrij-contract geschonden: " + waar
                + " draagt na LetInliner.normaliseer nog een Expr.Let of Expr.BinderRef. "
                + "De back-ends lezen die als een ondoorzichtig blad en degraderen de constructie stil naar "
                + "een MARKER — dek deze slot in LetInliner.normaliseer, of port het Let-cluster (kaart R1).");
    }

    // ---------------- the rewrite ----------------
    // Mirrors Expr.bevat's exhaustive walk position for position: whatever that scan reaches, this rewrite
    // reaches too, so the contract above can never fail on a position this pass simply forgot to visit.
    // Every arm returns the ORIGINAL node when no child changed, making the pass a true no-op — identity
    // included — on a model without a variabelendeel.

    /** @param env the binders in scope, keyed by the name {@code Expr.Let.binder} and every
     *             {@code Expr.BinderRef.naam} carry; the frontend derives both from the SAME declared
     *             spelling ({@code ExpressieBouwer.binderNaam}), so plain string equality is the binder
     *             identity. A name it cannot resolve is left standing for {@link #eisLetVrij} to report. */
    private static Expr inl(Expr e, Map<String, Expr> env) {
        if (e == null) return null;
        switch (e) {
            case Expr.Let l -> {
                Map<String, Expr> in = new HashMap<>(env);
                // WF-076: the binder is visible only in the body, so its value resolves in the ENCLOSING scope.
                in.put(l.binder(), inl(l.value(), env));
                return inl(l.body(), in);
            }
            case Expr.BinderRef b -> {
                Expr v = env.get(b.naam());
                return v != null ? v : b;
            }
            case Expr.Binair b -> {
                Expr li = inl(b.links(), env), re = inl(b.rechts(), env);
                return li == b.links() && re == b.rechts() ? b
                        : new Expr.Binair(b.op(), li, re, b.type(), b.bron());
            }
            case Expr.Afgerond a -> {
                Expr basis = inl(a.basis(), env);
                return basis == a.basis() ? a : new Expr.Afgerond(basis, a.modus(), a.decimalen(), a.bron());
            }
            case Expr.Begrensd g -> {
                Expr basis = inl(g.basis(), env), min = inl(g.minimum(), env), max = inl(g.maximum(), env);
                return basis == g.basis() && min == g.minimum() && max == g.maximum() ? g
                        : new Expr.Begrensd(basis, min, max, g.bron());
            }
            case Expr.DimensieCel d -> {
                Expr basis = inl(d.attribuut(), env);
                return basis == d.attribuut() ? d
                        : new Expr.DimensieCel((Expr.AttribuutRef) basis, d.labels(), d.bron());
            }
            case Expr.Functie f -> {
                List<Expr> args = inlLijst(f.argumenten(), env);
                Expr leeg = inl(f.leegwaarde(), env);
                Conditie filter = inl(f.filter(), env);
                return args == f.argumenten() && leeg == f.leegwaarde() && filter == f.filter() ? f
                        : new Expr.Functie(f.kind(), args, f.elementObjecttype(), leeg, filter,
                                f.dimensieSelectie(), f.type(), f.bron(), f.granulariteit());
            }
            case Expr.TekstReeks t -> {
                List<Expr.TekstReeks.Deel> delen = new ArrayList<>(t.delen().size());
                boolean gewijzigd = false;
                for (Expr.TekstReeks.Deel d : t.delen()) {
                    if (d instanceof Expr.TekstReeks.Ingevoegd i) {
                        Expr x = inl(i.expr(), env);
                        if (x != i.expr()) { gewijzigd = true; delen.add(new Expr.TekstReeks.Ingevoegd(x)); continue; }
                    }
                    delen.add(d);
                }
                return gewijzigd ? new Expr.TekstReeks(delen, t.bron()) : t;
            }
            // The leaves, spelled out for the same reason Expr.bevat spells them out: no default arm, so the
            // next node the sealed union grows is a compile error here and not a silently un-inlined subtree.
            case Expr.Literal ignored      -> { return e; }
            case Expr.AttribuutRef ignored -> { return e; }
            case Expr.ParameterRef ignored -> { return e; }
            case Expr.VariabeleRef ignored -> { return e; }
            case Expr.Constante ignored    -> { return e; }
            case Expr.Onopgelost ignored   -> { return e; }
        }
    }

    private static List<Expr> inlLijst(List<Expr> xs, Map<String, Expr> env) {
        List<Expr> uit = new ArrayList<>(xs.size());
        boolean gewijzigd = false;
        for (Expr x : xs) {
            Expr y = inl(x, env);
            gewijzigd |= y != x;
            uit.add(y);
        }
        return gewijzigd ? uit : xs;
    }

    private static Conditie inl(Conditie c, Map<String, Expr> env) {
        if (c == null) return null;
        if (c instanceof Conditie.Vergelijking v) {
            Expr li = inl(v.links(), env), re = inl(v.rechts(), env);
            return li == v.links() && re == v.rechts() ? v
                    : new Conditie.Vergelijking(v.op(), li, re, v.periode(), v.bron());
        }
        if (c instanceof Conditie.Predicaat p) {
            Expr op = inl(p.operand(), env);
            return op == p.operand() ? p
                    : new Conditie.Predicaat(p.soort(), op, p.dagsoort(), p.negatie(), p.cijfers(),
                            p.periode(), p.bron());
        }
        if (c instanceof Conditie.Periode p) {
            Expr vanaf = inl(p.vanaf(), env), tot = inl(p.tot(), env);
            return vanaf == p.vanaf() && tot == p.tot() ? p
                    : new Conditie.Periode(vanaf, tot, p.totInclusief(), p.bron());
        }
        if (c instanceof Conditie.Samengesteld s) {
            List<Conditie> onderdelen = new ArrayList<>(s.onderdelen().size());
            boolean gewijzigd = false;
            for (Conditie d : s.onderdelen()) {
                Conditie y = inl(d, env);
                gewijzigd |= y != d;
                onderdelen.add(y);
            }
            return gewijzigd ? new Conditie.Samengesteld(s.kwantor(), s.drempel(), onderdelen, s.bron()) : s;
        }
        return c;   // Kenmerk, Rollidmaatschap, VerreRollidmaatschap, Regelversie, Onopgelost: no Expr operands
    }

    private static List<Default> inlDefaults(List<Default> ds) {
        if (ds == null) return null;
        List<Default> uit = new ArrayList<>(ds.size());
        boolean gewijzigd = false;
        for (Default d : ds) {
            Conditie vw = inl(d.voorwaarde(), Map.of());
            Expr cons = inl(d.consequentie(), Map.of());
            Default.Excepties ex = d.excepties();
            if (ex != null) {
                List<ExceptionGroup> leden = new ArrayList<>(ex.leden().size());
                boolean exGewijzigd = false;
                for (ExceptionGroup g : ex.leden()) {
                    List<Default> gd = inlDefaults(g.defaults());
                    exGewijzigd |= gd != g.defaults();
                    leden.add(gd == g.defaults() ? g : new ExceptionGroup(gd, g.bron()));
                }
                if (exGewijzigd) ex = new Default.Excepties(ex.ongeordend(), leden);
            }
            if (vw == d.voorwaarde() && cons == d.consequentie() && ex == d.excepties()) { uit.add(d); continue; }
            gewijzigd = true;
            uit.add(new Default(d.besluit(), vw, cons, d.initialisatie(), ex, d.bron()));
        }
        return gewijzigd ? uit : ds;
    }
}
