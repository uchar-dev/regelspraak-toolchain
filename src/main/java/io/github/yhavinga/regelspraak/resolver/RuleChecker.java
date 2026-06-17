package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.*;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

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

    RuleChecker(RegelSpraakModel model) { this.model = model; }

    void check(RegelSpraakDocumentContext doc) {
        domeinReferenties(doc);
        for (RegelContext r : doc.regel())
            for (RegelVersieContext v : r.regelVersie()) {
                resultaat(v.regelSpraakRegel().resultaatDeel());
                voorwaarden(v.regelSpraakRegel());   // type-check the conditions (§5)
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

    private void resultaat(ResultaatDeelContext rd) {
        if (rd == null) return;
        if (rd.gelijkstelling() != null) {
            GelijkstellingContext g = rd.gelijkstelling();
            toets(g.attribuutVanOnderwerp(), g.expressie(), g.getStart().getLine(),
                  g.WORDT_BEREKEND_ALS() != null ? "berekening" : "gelijkstelling");
        } else if (rd.initialisatie() != null) {
            InitialisatieContext i = rd.initialisatie();
            toets(i.attribuutVanOnderwerp(), i.expressie(), i.getStart().getLine(), "initialisatie");
        }
    }

    private void toets(AttribuutVanOnderwerpContext doel, ExpressieContext waarde, int line, String wat) {
        Datatype doelType = attribuutType(doel);
        Datatype waardeType = infer(waarde);
        if (doelType.isOnbekend() || waardeType.isOnbekend()) return; // not enough resolved to judge
        String fout = Typering.toekenning(doelType, waardeType);
        if (fout != null)
            model.diagnostics.add(new Diagnostic(Severity.ERROR, wat + ": " + fout, line));
    }

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

    // ---- expression datatype inference (resolvable subset) ----
    private Datatype infer(ExpressieContext e) {
        if (e == null) return onbekend();
        if (e instanceof PrimaireExprContext p) return inferPrimair(p.primaireExpressie());
        if (e instanceof SomExprContext s) {
            Typering.BinOp op = s.PLUS() != null ? Typering.BinOp.PLUS
                    : s.MIN() != null ? Typering.BinOp.MIN : Typering.BinOp.VERMINDERD_MET;
            return Typering.reken(op, infer(s.expressie(0)), infer(s.expressie(1))).type();
        }
        if (e instanceof ProductExprContext s) {
            Typering.BinOp op = s.MAAL() != null ? Typering.BinOp.MAAL : Typering.BinOp.GEDEELD_DOOR;
            return result(Typering.reken(op, infer(s.expressie(0)), infer(s.expressie(1))));
        }
        if (e instanceof MachtExprContext s)
            return result(Typering.reken(Typering.BinOp.TOT_DE_MACHT, infer(s.expressie(0)), infer(s.expressie(1))));
        if (e instanceof AfgerondeExprContext s) return infer(s.expressie());
        if (e instanceof BegrensdeExprContext s) return infer(s.expressie());
        if (e instanceof PercentageVanExprContext)  return new Datatype.Numeriek(false, 0, Datatype.Teken.ONBEPAALD, null);
        return onbekend();
    }

    private Datatype inferPrimair(PrimaireExpressieContext p) {
        if (p == null) return onbekend();
        if (p.getalWaarde() != null) {
            GetalWaardeContext g = p.getalWaarde();
            if (g.PERCENT_SIGN() != null) return new Datatype.Percentage(false, 0);
            if (g.eenheidExpressie() != null) {
                String afk = DefinitionResolver.src(g.eenheidExpressie().eenheidMacht(0).eenheidAfkorting());
                return new Datatype.Numeriek(false, 0, Datatype.Teken.ONBEPAALD, model.resolveEenheid(afk));
            }
            return new Datatype.Numeriek(false, 0, Datatype.Teken.ONBEPAALD, null);
        }
        if (p.tekstWaarde() != null)    return new Datatype.Tekst();
        if (p.booleanWaarde() != null)  return new Datatype.Booleaan();
        if (p.deDato() != null)         return new Datatype.DatumInDagen();
        if (p.rekenConstante() != null) {
            // "de Rekendatum" is a date; "het Rekenjaar" is an integer year (§13.4.17)
            return p.rekenConstante().REKENJAAR() != null
                    ? new Datatype.Numeriek(true, 0, Datatype.Teken.ONBEPAALD, null)
                    : new Datatype.DatumInDagen();
        }
        if (p.attribuutVanOnderwerp() != null) return attribuutType(p.attribuutVanOnderwerp());
        if (p.expressie() != null)      return infer(p.expressie()); // ( expressie )
        if (p.onderwerp() != null)      return naamReferentie(DefinitionResolver.src(p.onderwerp()));
        if (p.variabeleNaam() != null)  return naamReferentie(DefinitionResolver.src(p.variabeleNaam()));
        return onbekend();
    }

    /** A bare name reference: resolve as a parameter if known, else unknown. */
    private Datatype naamReferentie(String naam) {
        // strip a leading article for parameter lookup ("de volwassenleeftijd")
        String stripped = naam.replaceFirst("^(?i)(de|het|een)\\s+", "");
        Parameter p = model.parameters.get(stripped);
        if (p == null) p = model.parameters.get(naam);
        return p != null ? p.datatype() : onbekend();
    }

    /** `<attribuut> van <onderwerp>`: resolve the attribute's datatype via the subject object type. */
    private Datatype attribuutType(AttribuutVanOnderwerpContext a) {
        if (a == null || a.attribuutMetLidwoord() == null || a.attribuutMetLidwoord().kaleNaam() == null)
            return onbekend();
        String attr = DefinitionResolver.src(a.attribuutMetLidwoord().kaleNaam());
        Objecttype ot = vindObjecttype(a.onderwerp());
        if (ot == null) return onbekend();
        Datatype dt = ot.attributen.get(attr);
        return dt != null ? dt : onbekend();
    }

    /** Find the object type a subject denotes by matching any name in its subtree against the model. */
    private Objecttype vindObjecttype(ParseTree t) {
        if (t == null) return null;
        Objecttype found = null;
        if (t instanceof ParserRuleContext ctx) {
            String s = DefinitionResolver.src(ctx);
            Objecttype direct = model.objecttypen.get(s);
            if (direct != null) found = direct;
        }
        for (int i = 0; i < t.getChildCount(); i++) {
            Objecttype c = vindObjecttype(t.getChild(i));
            if (c != null) found = c; // prefer the deepest/last match (the base subject)
        }
        return found;
    }

    private static Datatype onbekend() { return new Datatype.Onbekend("niet opgelost"); }
    private static Datatype result(Typering.Resultaat r) { return r.ok() ? r.type() : onbekend(); }
}
