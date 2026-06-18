package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedVerdeling;
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
        } else if (rd.kenmerkToekenning() != null) {
            kenmerk(naam, rd.kenmerkToekenning(), voorwaarde);
        } else if (rd.objectCreatie() != null) {
            objectcreatie(naam, rd.objectCreatie(), voorwaarde);
        } else if (rd.verdeling() != null) {
            verdeling(naam, rd.verdeling(), voorwaarde);
        } else if (rd.consistentieRegel() != null) {
            consistentie(naam, rd.consistentieRegel());
        } else {
            // §13.4.7/.11/.12 — parsed but not yet lifted into the IR. Surface it as
            // a diagnostic rather than letting the rule silently disappear from the model.
            String soort = rd.feitCreatie() != null ? "feitcreatie"
                    : rd.startpuntBepaling() != null ? "startpuntbepaling"
                    : rd.dagsoortResultaat() != null ? "dagsoortresultaat" : "resultaatdeel";
            model.diagnostics.add(new Diagnostic(Severity.WARNING,
                    "resultaatdeel '" + soort + "' wordt nog niet naar IR/DRL omgezet (regel '" + naam + "')",
                    rd.getStart().getLine()));
        }
    }

    /** §13.4.8 consistentieregel (single-criterion form): "&lt;expressie&gt; moet
     *  &lt;operator&gt;". The assertion is built with the same operator machinery as a
     *  voorwaarde (uitToplevel), so the operand types and operator legality are the
     *  §5 ones; the subject is the object type the asserted expression is about. Compound
     *  criteria and uniciteit (different shapes) are not yet lifted — they keep a WARNING. */
    private void consistentie(String naam, ConsistentieRegelContext cr) {
        EnkelvoudigeConsistentieRegelContext e = cr.enkelvoudigeConsistentieRegel();
        if (e != null && e.expressie() != null && e.toplevelOperator() != null) {
            Expr lhs = bouwer.bouw(e.expressie());
            Conditie assertie = uitToplevel(lhs, e.toplevelOperator(), src(e));
            Objecttype subject = lhs instanceof Expr.AttribuutRef a ? a.objecttype() : null;
            if (subject != null && transpileerbaar(assertie)) {
                model.consistenties.add(new io.github.yhavinga.regelspraak.ir.ResolvedConsistentie(naam, subject, assertie, src(cr)));
                return;
            }
        }
        // Compound criteria / uniciteit, a membership criterion ("moet een <kenmerk|rol>
        // zijn"), elfproef / numeriek-met-exact, or a computed/unresolved subject: forms
        // whose "inconsistent" we cannot yet express as a faithful violation rule. Surface
        // the gap as a WARNING rather than lift it into a silent inert rule (which would
        // both drop the check and lose the diagnostic the §13.4.7/.11/.12 path still gives).
        model.diagnostics.add(new Diagnostic(Severity.WARNING,
                "consistentieregel wordt nog niet (volledig) naar IR/DRL omgezet (regel '" + naam + "')",
                cr.getStart().getLine()));
    }

    /** The single-criterion assertions whose negation the transpiler can emit as a
     *  faithful violation rule: a comparison, or an emptiness predicate. Everything else
     *  (membership, elfproef, numeriek-met-exact, unstructured) is left to a WARNING. */
    private static boolean transpileerbaar(Conditie c) {
        if (c instanceof Conditie.Vergelijking) return true;
        if (c instanceof Conditie.Predicaat p)
            return p.soort() == Conditie.Predicaatsoort.LEEG || p.soort() == Conditie.Predicaatsoort.GEVULD;
        return false;
    }

    /** §13.4.10 verdeling: distribute a source amount over a recipient attribute. The
     *  method (equal shares / proportional / a group-and-remainder scheme) is captured;
     *  only equal shares has a single-rule Drools form (the transpiler marks the rest). */
    private void verdeling(String naam, VerdelingContext v, Conditie voorwaarde) {
        if (v.attribuutVanOnderwerp().size() < 2) return;
        Expr bron = bouwer.bouwAttribuut(v.attribuutVanOnderwerp(0));
        Expr doel = bouwer.bouwAttribuut(v.attribuutVanOnderwerp(1));
        if (!(bron instanceof Expr.AttribuutRef b) || !(doel instanceof Expr.AttribuutRef d)) return;

        ResolvedVerdeling.Methode methode = ResolvedVerdeling.Methode.COMPLEX;
        String rato = null;
        VerdelenZonderGroepenContext z = v.verdelenZonderGroepen();
        if (z != null && z.IN_GELIJKE_DELEN() != null) methode = ResolvedVerdeling.Methode.GELIJKE_DELEN;
        else if (z != null && z.NAAR_RATO_VAN() != null) {
            methode = ResolvedVerdeling.Methode.NAAR_RATO;
            rato = z.attribuutMetLidwoord() != null ? src(z.attribuutMetLidwoord()) : null;
        }
        model.verdelingen.add(new ResolvedVerdeling(naam, b.objecttype(), b.attribuut(),
                d.objecttype(), d.attribuut(), methode, rato, voorwaarde));
    }

    /** §13.4.6 objectcreatie: "<subject> heeft <het rol> met <attribuut> gelijk aan <expr>
     *  (, <attribuut> gelijk aan <expr>)*". The first kaleNaam carries "<rol> met
     *  <attribuut>" (the 'met' fold the grammar leaves to the resolver); the rest are
     *  bare attribute names. */
    private void objectcreatie(String naam, ObjectCreatieContext oc, Conditie voorwaarde) {
        Objecttype subject = bouwer.objecttypeVanKeten(oc.onderwerpKeten());
        bouwer.setSubject(subject);
        List<KaleNaamContext> namen = oc.kaleNaam();
        List<ExpressieContext> waarden = oc.expressie();
        if (namen.isEmpty()) return;

        String[] rolEnAttr = src(namen.get(0)).split("(?i)\\s+met\\s+", 2);
        Objecttype nieuwType = bouwer != null
                ? model.resolveObjecttype(stripArtikel(rolEnAttr[0])) : null;

        List<ResolvedObjectCreatie.Toekenning> toek = new ArrayList<>();
        if (rolEnAttr.length == 2 && !waarden.isEmpty())
            toek.add(new ResolvedObjectCreatie.Toekenning(stripArtikel(rolEnAttr[1]), bouwer.bouw(waarden.get(0))));
        for (int i = 1; i < namen.size() && i < waarden.size(); i++)
            toek.add(new ResolvedObjectCreatie.Toekenning(stripArtikel(src(namen.get(i))), bouwer.bouw(waarden.get(i))));

        model.objectCreaties.add(new ResolvedObjectCreatie(naam, nieuwType, subject, toek, voorwaarde));
    }

    private static String stripArtikel(String s) { return s.replaceFirst("^(?i)(de|het|een)\\s+", "").trim(); }

    /** §13.4.5 kenmerktoekenning: the subject acquires a kenmerk (a boolean property).
     *  Modelled as setting that boolean attribute to true. */
    private void kenmerk(String naam, KenmerkToekenningContext kt, Conditie voorwaarde) {
        Objecttype subject = bouwer.objecttypeVanKeten(kt.onderwerpKeten());
        String kenmerk = src(kt.kaleNaam()).replaceFirst("^(?i)(de|het|een)\\s+", "");
        Expr doel = new Expr.AttribuutRef(kenmerk, subject, src(kt.onderwerpKeten()),
                null, new Datatype.Booleaan(), src(kt));
        Expr waarde = new Expr.Literal("waar", new Datatype.Booleaan(), "waar");
        model.resolvedRegels.add(new ResolvedRegel(naam, ResolvedRegel.Soort.KENMERKTOEKENNING, doel, waarde, voorwaarde));
    }

    /** The object type a rule is about — the object type of its assignment target. */
    private Objecttype subjectVan(ResultaatDeelContext rd) {
        AttribuutVanOnderwerpContext doel = null;
        if (rd == null) return null;
        if (rd.gelijkstelling() != null) doel = rd.gelijkstelling().attribuutVanOnderwerp();
        else if (rd.initialisatie() != null) doel = rd.initialisatie().attribuutVanOnderwerp();
        else if (rd.kenmerkToekenning() != null) return bouwer.objecttypeVanKeten(rd.kenmerkToekenning().onderwerpKeten());
        else if (rd.objectCreatie() != null) return bouwer.objecttypeVanKeten(rd.objectCreatie().onderwerpKeten());
        else if (rd.consistentieRegel() != null) {
            EnkelvoudigeConsistentieRegelContext e = rd.consistentieRegel().enkelvoudigeConsistentieRegel();
            return e != null && e.expressie() != null
                    && bouwer.bouw(e.expressie()) instanceof Expr.AttribuutRef a ? a.objecttype() : null;
        }
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
        return new Conditie.Samengesteld(kwantorVan(k), drempelVan(k), onderdelen, src(heel));
    }

    /** The threshold count N of a ten-minste/ten-hoogste/precies quantifier (§13.4.14.4),
     *  or 0 when the quantifier carries no count (alle / geen / de). */
    private int drempelVan(VoorwaardeKwantificatieContext k) {
        if (k == null || k.telwoord() == null) return 0;
        TelwoordContext t = k.telwoord();
        if (t.NUMBER() != null) try { return Integer.parseInt(t.NUMBER().getText()); } catch (NumberFormatException e) { return 0; }
        if (t.EEN_TELWOORD() != null)  return 1;
        if (t.TWEE_TELWOORD() != null) return 2;
        if (t.DRIE_TELWOORD() != null) return 3;
        if (t.VIER_TELWOORD() != null) return 4;
        return 0;
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
        else if (t instanceof EnkelvoudigeConsistentieRegelContext c) checkConsistentieCriterium(c);
        for (int i = 0; i < t.getChildCount(); i++) voorwaarden(t.getChild(i));
    }

    /** A §13.4.8 single criterion ("&lt;expressie&gt; moet &lt;operator&gt;") is the same
     *  predicate over the same onderwerpexpressie as the matching §5 voorwaarde (§8.3.1),
     *  so its operands get the identical typeringen check — the lifted criterion now
     *  transpiles, which would otherwise let an ill-typed comparison through silently. */
    private void checkConsistentieCriterium(EnkelvoudigeConsistentieRegelContext c) {
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
