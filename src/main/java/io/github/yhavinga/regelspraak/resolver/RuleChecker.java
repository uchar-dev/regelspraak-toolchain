package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.*;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Type-checks rules against the typeringen matrix (Phase: rule checking). It
 * infers an expression's datatype where the references resolve against the model
 * — literals, parameters, arithmetic, and attribute-of-subject references whose
 * subject is a plain object type — and applies {@link Typering}. References it
 * cannot resolve yield {@link Datatype.Onbekend}, which {@link Typering} treats
 * as "no claim", so the checker never raises a false positive on a construct it
 * does not yet fully resolve (e.g. deep van-chains or aggregations). The result
 * type rules are the spec's; the coverage is honest about its limits.
 */
final class RuleChecker {
    private final RegelSpraakModel model;
    private final ExpressieBouwer bouwer;

    RuleChecker(RegelSpraakModel model) {
        this.model = model;
        this.bouwer = new ExpressieBouwer(model);
    }

    void check(RegelSpraakDocumentContext doc) {
        domeinReferenties(doc);
        for (RegelContext r : doc.regel()) {
            String naam = DefinitionResolver.src(r.regelNaam());
            for (RegelVersieContext v : r.regelVersie()) {
                RegelSpraakRegelContext rsr = v.regelSpraakRegel();
                bouwer.setSubject(subjectVan(rsr.resultaatDeel())); // so "zijn …" resolves against the rule subject
                Conditie voorwaarde = rsr.voorwaardeDeel() != null ? bouwVoorwaarde(rsr.voorwaardeDeel()) : null;
                resultaat(naam, rsr.resultaatDeel(), voorwaarde);
                voorwaarden(rsr);   // exhaustive condition type-check (§5), unchanged
            }
        }
    }

    /** A domain-typed attribute/parameter must reference a declared domain (§13.3.4). */
    private void domeinReferenties(RegelSpraakDocumentContext doc) {
        model.objecttypen.values().forEach(ot -> ot.attributen.forEach((naam, dt) -> meldOnbekendDomein(dt, naam)));
        model.parameters.values().forEach(p -> meldOnbekendDomein(p.datatype(), p.naam()));
    }
    private void meldOnbekendDomein(Datatype dt, String waar) {
        if (dt instanceof Datatype.Onbekend o && o.reden().startsWith("domein ")) {
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "ongedefinieerd " + o.reden() + " (gebruikt door '" + waar + "')", 0));
        }
    }

    /** Resolve an assignment resultaatdeel into a {@link ResolvedRegel} (the transpiler IR)
     *  and type-check the value against the target (§6 toekenning). */
    private void resultaat(String naam, ResultaatDeelContext rd, Conditie voorwaarde) {
        if (rd == null) return;
        if (rd.gelijkstelling() != null) {
            GelijkstellingContext g = rd.gelijkstelling();
            ResolvedRegel.Soort soort = g.WORDT_BEREKEND_ALS() != null
                    ? ResolvedRegel.Soort.BEREKENING : ResolvedRegel.Soort.GELIJKSTELLING;
            registreer(naam, soort, g.attribuutVanOnderwerp(), g.expressie(), g.getStart().getLine(), voorwaarde);
        } else if (rd.initialisatie() != null) {
            InitialisatieContext i = rd.initialisatie();
            registreer(naam, ResolvedRegel.Soort.INITIALISATIE, i.attribuutVanOnderwerp(),
                       i.expressie(), i.getStart().getLine(), voorwaarde);
        }
    }

    /** The object type a rule is about — the object type of its assignment target. */
    private Objecttype subjectVan(ResultaatDeelContext rd) {
        AttribuutVanOnderwerpContext doel = null;
        if (rd == null) return null;
        if (rd.gelijkstelling() != null) doel = rd.gelijkstelling().attribuutVanOnderwerp();
        else if (rd.initialisatie() != null) doel = rd.initialisatie().attribuutVanOnderwerp();
        return doel != null && bouwer.bouwAttribuut(doel) instanceof Expr.AttribuutRef a ? a.objecttype() : null;
    }

    private void registreer(String naam, ResolvedRegel.Soort soort, AttribuutVanOnderwerpContext doel,
                            ExpressieContext waarde, int line, Conditie voorwaarde) {
        Expr doelExpr = bouwer.bouwAttribuut(doel);
        Expr waardeExpr = bouwer.bouw(waarde);
        model.resolvedRegels.add(new ResolvedRegel(naam, soort, doelExpr, waardeExpr, voorwaarde));
        Datatype doelType = doelExpr.type(), waardeType = waardeExpr.type();
        if (doelType.isOnbekend() || waardeType.isOnbekend()) return; // not enough resolved to judge
        String fout = Typering.toekenning(doelType, waardeType);
        if (fout != null)
            model.diagnostics.add(new Diagnostic(Severity.ERROR, soort.name().toLowerCase() + ": " + fout, line));
    }

    // ---- §13.4.13/.14/.15 condition IR (structure for the transpiler "when" side) ----
    // Built alongside (not instead of) the exhaustive type-check walk below, so it
    // adds structure without changing any diagnostic. Forms not yet structured —
    // kenmerk/objecttype membership tests, period comparisons — become Onopgelost.
    private Conditie bouwVoorwaarde(VoorwaardeDeelContext v) {
        if (v.toplevelSamengesteldeVoorwaarde() != null) return bouwSamengesteld(
                v.toplevelSamengesteldeVoorwaarde().voorwaardeKwantificatie(),
                v.toplevelSamengesteldeVoorwaarde().samengesteldeVoorwaardeOnderdeel(), v.toplevelSamengesteldeVoorwaarde());
        if (v.toplevelElementaireVoorwaarde() != null) {
            ToplevelElementaireVoorwaardeContext c = v.toplevelElementaireVoorwaarde();
            if (c.expressie() != null && c.toplevelOperator() != null)
                return uitToplevel(bouwer.bouw(c.expressie()), c.toplevelOperator(), src(c));
            if (c.expressie() != null && c.nonToplevelOperator() != null)
                return uitNonToplevel(bouwer.bouw(c.expressie()), c.nonToplevelOperator(), src(c));
        }
        return new Conditie.Onopgelost(src(v));
    }

    private Conditie bouwSamengesteld(VoorwaardeKwantificatieContext k,
                                      SamengesteldeVoorwaardeOnderdeelContext onderdeel, ParserRuleContext heel) {
        List<Conditie> onderdelen = new ArrayList<>();
        if (onderdeel != null)
            for (GenesteVoorwaardeContext g : onderdeel.genesteVoorwaarde()) onderdelen.add(bouwGenest(g));
        return new Conditie.Samengesteld(kwantorVan(k), onderdelen, src(heel));
    }

    private Conditie bouwGenest(GenesteVoorwaardeContext g) {
        if (g.genesteSamengesteldeVoorwaarde() != null) {
            GenesteSamengesteldeVoorwaardeContext c = g.genesteSamengesteldeVoorwaarde();
            return bouwSamengesteld(c.voorwaardeKwantificatie(), c.samengesteldeVoorwaardeOnderdeel(), c);
        }
        if (g.elementaireVoorwaarde() != null) {
            ElementaireVoorwaardeContext c = g.elementaireVoorwaarde();
            if (c.expressie() != null && c.nonToplevelOperator() != null)
                return uitNonToplevel(bouwer.bouw(c.expressie()), c.nonToplevelOperator(), src(c));
            if (c.expressie() != null && c.toplevelOperator() != null)
                return uitToplevel(bouwer.bouw(c.expressie()), c.toplevelOperator(), src(c));
        }
        return new Conditie.Onopgelost(src(g));
    }

    private Conditie uitToplevel(Expr lhs, ToplevelOperatorContext op, String bron) {
        if (op.vergelijkingsTekenGesplitst() != null && op.expressie() != null)
            return new Conditie.Vergelijking(naarOperator(cmpOf(op.vergelijkingsTekenGesplitst())),
                    lhs, bouwer.bouw(op.expressie()), bron);
        if (op.LEEG() != null)   return new Conditie.Predicaat(Conditie.Predicaatsoort.LEEG, lhs, bron);
        if (op.GEVULD() != null) return new Conditie.Predicaat(Conditie.Predicaatsoort.GEVULD, lhs, bron);
        if (op.AAN_DE_ELFPROEF() != null) return new Conditie.Predicaat(Conditie.Predicaatsoort.ELFPROEF, lhs, bron);
        if (op.NUMERIEK_IS_MET_EXACT() != null || op.NUMERIEK_ZIJN_MET_EXACT() != null)
            return new Conditie.Predicaat(Conditie.Predicaatsoort.NUMERIEK_MET_EXACT, lhs, bron);
        return new Conditie.Onopgelost(bron); // EEN? kaleNaam (is|heeft) — kenmerk/objecttype membership
    }

    private Conditie uitNonToplevel(Expr lhs, NonToplevelOperatorContext op, String bron) {
        if (op.vergelijkingsTeken() != null && op.expressie() != null)
            return new Conditie.Vergelijking(naarOperator(cmpOf(op.vergelijkingsTeken())),
                    lhs, bouwer.bouw(op.expressie()), bron);
        if (op.LEEG() != null)   return new Conditie.Predicaat(Conditie.Predicaatsoort.LEEG, lhs, bron);
        if (op.GEVULD() != null) return new Conditie.Predicaat(Conditie.Predicaatsoort.GEVULD, lhs, bron);
        if (op.AAN_DE_ELFPROEF() != null) return new Conditie.Predicaat(Conditie.Predicaatsoort.ELFPROEF, lhs, bron);
        if (op.NUMERIEK_MET_EXACT() != null)
            return new Conditie.Predicaat(Conditie.Predicaatsoort.NUMERIEK_MET_EXACT, lhs, bron);
        return new Conditie.Onopgelost(bron); // (is|heeft) een? kaleNaam — kenmerk/objecttype membership
    }

    private Conditie.Kwantor kwantorVan(VoorwaardeKwantificatieContext k) {
        if (k == null) return Conditie.Kwantor.DE;
        if (k.ALLE() != null)        return Conditie.Kwantor.ALLE;
        if (k.GEEN_VAN_DE() != null) return Conditie.Kwantor.GEEN;
        if (k.TEN_MINSTE() != null)  return Conditie.Kwantor.TEN_MINSTE;
        if (k.TEN_HOOGSTE() != null) return Conditie.Kwantor.TEN_HOOGSTE;
        if (k.PRECIES() != null)     return Conditie.Kwantor.PRECIES;
        return Conditie.Kwantor.DE;
    }

    private static Conditie.Operator naarOperator(Typering.CmpOp op) {
        if (op == null) return Conditie.Operator.GELIJK;
        return switch (op) {
            case KLEINER -> Conditie.Operator.KLEINER;
            case KLEINER_OF_GELIJK -> Conditie.Operator.KLEINER_OF_GELIJK;
            case GROTER -> Conditie.Operator.GROTER;
            case GROTER_OF_GELIJK -> Conditie.Operator.GROTER_OF_GELIJK;
            case GELIJK -> Conditie.Operator.GELIJK;
            case ONGELIJK -> Conditie.Operator.ONGELIJK;
            case EERDER -> Conditie.Operator.EERDER;
            case EERDER_OF_GELIJK -> Conditie.Operator.EERDER_OF_GELIJK;
            case LATER -> Conditie.Operator.LATER;
            case LATER_OF_GELIJK -> Conditie.Operator.LATER_OF_GELIJK;
        };
    }

    private static String src(ParserRuleContext c) { return DefinitionResolver.src(c); }

    // ---- §5 condition type-checking (comparisons + predicates) ----
    private void voorwaarden(ParseTree t) {
        if (t == null) return;
        if (t instanceof ToplevelElementaireVoorwaardeContext c) checkTopVoorwaarde(c);
        else if (t instanceof ElementaireVoorwaardeContext c) checkElemVoorwaarde(c);
        for (int i = 0; i < t.getChildCount(); i++) voorwaarden(t.getChild(i));
    }

    private void checkTopVoorwaarde(ToplevelElementaireVoorwaardeContext c) {
        if (c.expressie() == null || c.toplevelOperator() == null) return;
        Datatype lhs = infer(c.expressie());
        ToplevelOperatorContext op = c.toplevelOperator();
        int line = c.getStart().getLine();
        if (op.vergelijkingsTekenGesplitst() != null && op.expressie() != null)
            meldVergelijk(cmpOf(op.vergelijkingsTekenGesplitst()), lhs, infer(op.expressie()), line);
        else if (op.AAN_DE_ELFPROEF() != null)
            meldPredicaat(Typering.UnairePred.ELFPROEF, lhs, line);
        else if (op.NUMERIEK_IS_MET_EXACT() != null || op.NUMERIEK_ZIJN_MET_EXACT() != null)
            meldPredicaat(Typering.UnairePred.NUMERIEK_MET_EXACT, lhs, line);
    }

    private void checkElemVoorwaarde(ElementaireVoorwaardeContext c) {
        if (c.expressie() == null || c.nonToplevelOperator() == null) return;
        Datatype lhs = infer(c.expressie());
        NonToplevelOperatorContext op = c.nonToplevelOperator();
        int line = c.getStart().getLine();
        if (op.vergelijkingsTeken() != null && op.expressie() != null)
            meldVergelijk(cmpOf(op.vergelijkingsTeken()), lhs, infer(op.expressie()), line);
        else if (op.AAN_DE_ELFPROEF() != null)
            meldPredicaat(Typering.UnairePred.ELFPROEF, lhs, line);
        else if (op.NUMERIEK_MET_EXACT() != null)
            meldPredicaat(Typering.UnairePred.NUMERIEK_MET_EXACT, lhs, line);
    }

    private void meldVergelijk(Typering.CmpOp op, Datatype l, Datatype r, int line) {
        if (op == null) return;
        var res = Typering.vergelijk(op, l, r);   // Onbekend operands -> ok, so no false positive
        if (!res.ok()) model.diagnostics.add(new Diagnostic(Severity.ERROR, "voorwaarde: " + res.fout(), line));
    }
    private void meldPredicaat(Typering.UnairePred p, Datatype operand, int line) {
        var res = Typering.predicaat(p, operand);
        if (!res.ok()) model.diagnostics.add(new Diagnostic(Severity.ERROR, "voorwaarde: " + res.fout(), line));
    }

    private Typering.CmpOp cmpOf(VergelijkingsTekenContext c) {
        return cmpOf(c.ONGELIJK() != null, c.GROTER() != null, c.KLEINER() != null,
                     c.LATER() != null, c.EERDER() != null, c.GELIJK() != null, c.OF() != null);
    }
    private Typering.CmpOp cmpOf(VergelijkingsTekenGesplitstContext c) {
        return cmpOf(c.ONGELIJK() != null, c.GROTER() != null, c.KLEINER() != null,
                     c.LATER() != null, c.EERDER() != null, c.GELIJK() != null, c.OF() != null);
    }
    private Typering.CmpOp cmpOf(boolean ong, boolean gr, boolean kl, boolean la, boolean ee, boolean ge, boolean of) {
        if (ong) return Typering.CmpOp.ONGELIJK;
        if (gr)  return of ? Typering.CmpOp.GROTER_OF_GELIJK  : Typering.CmpOp.GROTER;
        if (kl)  return of ? Typering.CmpOp.KLEINER_OF_GELIJK : Typering.CmpOp.KLEINER;
        if (la)  return of ? Typering.CmpOp.LATER_OF_GELIJK   : Typering.CmpOp.LATER;
        if (ee)  return of ? Typering.CmpOp.EERDER_OF_GELIJK  : Typering.CmpOp.EERDER;
        if (ge)  return Typering.CmpOp.GELIJK;
        return null;
    }

    // ---- expression datatype, via the resolved IR ----
    // The condition checks need an operand's datatype; that is now the type the
    // ExpressieBouwer computes for the resolved Expr, so inference and IR can never
    // diverge — they are the same code path.
    private Datatype infer(ExpressieContext e) { return bouwer.bouw(e).type(); }
}
