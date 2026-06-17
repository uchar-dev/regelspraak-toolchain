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
        for (RegelContext r : doc.regel())
            for (RegelVersieContext v : r.regelVersie())
                resultaat(v.regelSpraakRegel().resultaatDeel());
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
        if (p.REKENDATUM() != null)     return new Datatype.DatumInDagen();
        if (p.REKENJAAR() != null)      return new Datatype.Numeriek(true, 0, Datatype.Teken.ONBEPAALD, null);
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
