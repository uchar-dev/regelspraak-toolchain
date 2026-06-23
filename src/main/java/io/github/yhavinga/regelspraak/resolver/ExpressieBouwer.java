package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

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
 * transpiler something to walk instead of a bare datatype. Aggregation result
 * typing is deliberately left at Onbekend for now (a separate, test-guarded step);
 * the structure is captured regardless.
 */
final class ExpressieBouwer {
    private final RegelSpraakModel model;
    // The object type the current rule is about, so a possessive "zijn <attribuut>"
    // (and "hij") resolve against the rule subject. Set per rule by the checker.
    private Objecttype huidigSubject;

    ExpressieBouwer(RegelSpraakModel model) { this.model = model; }

    void setSubject(Objecttype subject) { this.huidigSubject = subject; }
    Objecttype subject() { return huidigSubject; }

    // ---------------- §13.4.16/.17 expressie ----------------
    Expr bouw(ExpressieContext e) {
        if (e == null) return onopgelost(e);
        String bron = src(e);
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
        return onopgelost(e);
    }

    /** Arithmetic node whose result datatype is taken from the typeringen matrix
     *  (a type error there yields Onbekend, matching the checker's no-claim path). */
    private Expr binair(Expr.BinOp op, ExpressieContext l, ExpressieContext r, String bron, int line) {
        return binairExpr(op, bouw(l), bouw(r), bron, line);
    }

    /** Arithmetic node over already-built operands (used where the right operand is a
     *  primaireExpressie, e.g. the exponent of "tot de macht"). A typeringen error (a
     *  family mismatch or incompatible/inconvertible units) is SURFACED as a diagnostic
     *  before degrading the result to Onbekend — the comparison and begrenzing paths
     *  already do; the arithmetic path used to swallow it (p45). An Onbekend operand makes
     *  reken return ok (no claim), so no false positive on an unresolved sub-expression. */
    private Expr binairExpr(Expr.BinOp op, Expr links, Expr rechts, String bron, int line) {
        Typering.Resultaat res = Typering.reken(naarTyperingOp(op), links.type(), rechts.type());
        if (!res.ok())
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(
                    RegelSpraakModel.Severity.ERROR, res.fout(), line));
        Datatype t = res.ok() ? res.type() : onbekend();
        return new Expr.Binair(op, links, rechts, t, bron);
    }

    private Expr afgerond(Expr basis, AfrondingContext a, String bron) {
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
        if (p == null) return onopgelost(p);
        String bron = src(p);
        if (p.getalWaarde() != null) {
            GetalWaardeContext g = p.getalWaarde();
            int dec = decimalenVanLiteral(bron);    // §6.1.2: the literal's real precision, not 0
            boolean geheel = dec == 0;
            if (g.PERCENT_SIGN() != null) return new Expr.Literal(bron, new Datatype.Percentage(geheel, dec), bron);
            if (g.eenheidExpressie() != null) {
                String afk = src(g.eenheidExpressie().eenheidMacht(0).eenheidAfkorting());
                return new Expr.Literal(bron, new Datatype.Numeriek(geheel, dec, Datatype.Teken.ONBEPAALD, model.resolveEenheid(afk)), bron);
            }
            return new Expr.Literal(bron, new Datatype.Numeriek(geheel, dec, Datatype.Teken.ONBEPAALD, null), bron);
        }
        if (p.tekstWaarde() != null) {
            // §13.4.17.9 tekstreeks: a "…«expressie»…" string SUBSTITUTES its «…» parts at evaluation time.
            // The STRING_LITERAL lexer rule swallows the whole quoted span — guillemets and the inner
            // reference — as one opaque token, so a verbatim literal would bake the «…» source into the
            // value (a silent WRONG runtime result, not an error). Flag it honestly until the feature lands;
            // an ordinary "…" literal has no guillemet and is untouched.
            if (bron.indexOf('«') >= 0 || bron.indexOf('»') >= 0)
                model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.ERROR,
                        "tekstreeks (reeks van teksten en waarden, §5.4/§13.4.17.9) wordt nog niet ondersteund; "
                                + "de «…»-delen worden niet gesubstitueerd", p.getStart().getLine()));
            return new Expr.Literal(bron, new Datatype.Tekst(), bron);
        }
        if (p.booleanWaarde() != null)  return new Expr.Literal(bron, new Datatype.Booleaan(), bron);
        if (p.deDato() != null) {
            // §13.2.19/.21: a dedato WITH a time component (hh:mm:ss[.mmm]) is a Datum-tijd (millisecond
            // precision), not a Datum in dagen — typing it as a bare date drops the time and lowers a
            // LocalDate into a LocalDateTime target (a Drools type error / silent wrong value).
            boolean metTijd = bron.matches(".*\\d{1,2}:\\d{2}:\\d{2}.*");
            return new Expr.Literal(bron, metTijd ? new Datatype.DatumTijd() : new Datatype.DatumInDagen(), bron);
        }
        if (p.ENUM_LITERAL() != null)   return new Expr.Literal(bron, onbekend(), bron); // which Enumeratie is context-dependent
        if (p.rekenConstante() != null) {
            boolean jaar = p.rekenConstante().REKENJAAR() != null;
            Datatype t = jaar ? new Datatype.Numeriek(true, 0, Datatype.Teken.ONBEPAALD, null) : new Datatype.DatumInDagen();
            return new Expr.Constante(jaar ? "Rekenjaar" : "Rekendatum", t, bron);
        }
        if (p.HIJ() != null)            return new Expr.Constante("hij", onbekend(), bron);
        if (p.DE() != null && p.DAG() != null) return new Expr.Constante("de dag", onbekend(), bron);
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
        return onopgelost(p);
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
        String bron = src(f);
        Expr.FunctieKind kind = functieKind(f);
        List<Expr> argumenten = args(f);
        Objecttype elem = aggregatieElement(kind, argumenten);
        // §13.4.17.47 telling vs a declared parameter whose name begins with "aantal": the spec
        // gives no syntactic tie-break for "het aantal <phrase>" (a count of <phrase>, or a
        // reference to a declared name "aantal <phrase>"). Resolve it by countability: a count
        // ranges over a countable element, so when "het aantal <phrase>" binds none AND the whole
        // phrase is a declared parameter, read it as that parameter — TOKA declares the parameter
        // "aantal treinmiles per passagier voor contingent", written "het aantal treinmiles per
        // passagier voor contingent". In a well-formed model a genuine count always binds an
        // element (so is never reinterpreted); only a count phrase that cannot resolve a countable
        // element falls back to a same-named declared parameter, where the parameter is the
        // concrete reading and the count reading is meaningless.
        if (kind == Expr.FunctieKind.AANTAL && elem == null) {
            Expr ref = naamRef(bron, false, bron);
            if (!(ref instanceof Expr.Onopgelost)) return ref;
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
        Conditie filter = aggregatieFilter(f, elem);
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
            return new Expr.Functie(kind, argumenten, elem, leegwaarde, filter, dimSel, type, bron);
        // §13.4.17.51-.58: a dimensieselectie that did NOT resolve, or a conditiebijexpressie ("gedurende
        // de tijd dat …") — the temporal axis is not built yet (p5) — must not be SILENTLY DROPPED, which
        // would aggregate the whole set as if unrestricted. Capture its source as an Onopgelost filter so
        // the transpiler markers the aggregation (a visible no-op), and surface a WARNING. (Reuses the
        // aggregation-restriction marker path; an already-unresolved die-filter keeps its own marker.)
        String dimBron = dimensieOfConditieBron(f);
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
                if (!(filter instanceof Conditie.Onopgelost)) filter = new Conditie.Onopgelost(dimBron);
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
                Conditie.Kenmerk k = temporeelKenmerkUitOperator(c.toplevelOperator(), c.nonToplevelOperator(), src(adf));
                if (k != null) filter = k;
            } else if (adf.toplevelSamengesteldeVoorwaarde() != null && !(filter instanceof Conditie.Samengesteld)) {
                // §8.4.2 a COMPOUND "dat er aan alle / ten minste 1 van de volgende voorwaarden wordt voldaan: …" —
                // the same temporal-kenmerk compound as a gedurende; the transpiler buckets the days the COMBINED
                // condition (intersection for ALLE / union for ten-minste-1 of the bullets) holds.
                Conditie samengesteld = gedurendeSamengesteld(adf.toplevelSamengesteldeVoorwaarde(), src(adf));
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

    /** §13.4.17.51-.58: the raw source of an aggregation's dimensieselectie (som / min-max) or
     *  conditiebijexpressie (totaal / tijdsevenredig deel), or null when absent — used to mark the dropped
     *  temporal/dimensional restriction so the aggregation is not silently lowered as if unrestricted. */
    private String dimensieOfConditieBron(FunctieAanroepContext f) {
        DimensieSelectieContext dim = f instanceof SomFuncContext s ? s.dimensieSelectie()
                : f instanceof MinMaxFuncContext m ? m.dimensieSelectie() : null;
        if (dim != null) return src(dim);
        ConditieBijExpressieContext cnd = f instanceof TotaalFuncContext t ? t.conditieBijExpressie()
                : f instanceof TijdsevenredigFuncContext tv ? tv.conditieBijExpressie() : null;
        return cnd != null ? src(cnd) : null;
    }

    /** §13.4.17.51-.54: resolve a SOM/MIN/MAX dimensieselectie to the concrete dimension and the ordered
     *  list of labels it ranges over, or null when it cannot be lowered — a null leaves the honest marker in
     *  place. It only lowers a fold over a SINGLE gedimensioneerd attribuut's labels, and only when the
     *  operand is the rule subject's OWN dimensioned attribute ({@code a.objecttype() == huidigSubject} —
     *  the §5.8.5 case, "zijn betaalde belasting in jaar"). A NAVIGATED operand is deferred: a to-MANY read
     *  ("de prijs in jaar van alle passagiers") would be a 2-level aggregation (over instances AND labels)
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
        if (argumenten.size() != 1 || !(argumenten.get(0) instanceof Expr.AttribuutRef a) || a.objecttype() == null)
            return null;
        if (a.objecttype() != huidigSubject) return null;   // only the subject's OWN dimensioned attribuut folds; a navigated read is deferred
        List<String> attrDims = a.objecttype().dimensiesVan(a.attribuut());
        if (attrDims.size() != 1) return null;   // not gedimensioneerd, or multi-dimension (composite key) — deferred

        RegelSpraakModel.Dimensie d;
        List<String> labels;
        if (dim.VANAF() != null) {
            // §5.8.5 bare interval: the dimension is left implicit; unambiguous because the attribute carries
            // exactly one dimension (guaranteed above).
            d = model.dimensieOpNaam(attrDims.get(0));
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
        return new Expr.DimensieSelectie(d.naam(), labels, src(dim));
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
     *  element ("het aantal / de som / de minimale waarde van … passagiers van de reis die een kind
     *  zijn" — only the kind-passagiers). Resolved against the ELEMENT type (not the rule subject): a
     *  kenmerk of the element becomes a real {@link Conditie.Kenmerk} (lowered as an accumulate-source
     *  constraint); anything else stays {@link Conditie.Onopgelost}, so the aggregation is a visible
     *  marker rather than a silently-wrong total over EVERY instance.
     *
     *  Only a single operand is considered: a multi-operand telling (the §5.8.1 union) or a min/max
     *  VALUE-fold has no single element filter — there a die belongs to one operand's own navigation
     *  (e.g. "de eerste passagier die …"), not the aggregation, and markering on it would kill a
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
        if (!(argumenten.get(0) instanceof Expr.AttribuutRef a) || a.navigatieRol() != null || a.set()
                || a.objecttype() != huidigSubject || !huidigSubject.attribuutGranulariteit.containsKey(a.attribuut())
                || huidigSubject.attribuutTijdlijnAantal.containsKey(a.attribuut())) return null;
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
            return temporeelKenmerkUitOperator(c.toplevelOperator(), c.nonToplevelOperator(), src(cnd));
        }
        if (cnd.toplevelSamengesteldeVoorwaarde() != null)
            return gedurendeSamengesteld(cnd.toplevelSamengesteldeVoorwaarde(), src(cnd));
        return null;
    }

    /** Resolve an elementaire "is/heeft (geen/niet) (een/het) &lt;kenmerk&gt;" membership operator into a
     *  single-period temporal {@link Conditie.Kenmerk} of the subject (negation + article-strip handled), or null
     *  when it is not one — a comparison, a non-temporal / multi-period / navigated kenmerk, or a role/possession.
     *  Shared by the single gedurende condition and each bullet of a compound one. */
    private Conditie.Kenmerk temporeelKenmerkUitOperator(ToplevelOperatorContext top, NonToplevelOperatorContext non, String bron) {
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
        if (kenmerk == null || !huidigSubject.kenmerkGranulariteit.containsKey(kenmerk)
                || huidigSubject.kenmerkTijdlijnAantal.containsKey(kenmerk)) return null;
        return new Conditie.Kenmerk(kenmerk, null, negatie, bron);
    }

    /** §8.4.2 a COMPOUND tijdsafhankelijke conditie on a temporal totaal: an ALLE (intersect the true-period sets)
     *  or "ten minste 1" (union them) of bullets. Each bullet is a single-period temporal-kenmerk membership of the
     *  subject; an ALLE compound may ALSO mix in a NON-temporal comparison bullet (the spec's own §8.4 example,
     *  "zijn geboortedatum is eerder dan 1-1-2002") — built as a {@link Conditie.Vergelijking} — which the transpiler
     *  lowers to a value-side static guard wrapping the clipped totaal ("de samengestelde conditie is als geheel wel
     *  tijdsafhankelijk"). An ALLE compound must still carry at least one temporal kenmerk (the actual clip). Any
     *  OTHER kwantor (geen van de / ten hoogste / precies / ten minste &gt;1), a nested compound bullet, a non-temporal
     *  bullet in a "ten minste 1" (a mixed OR is deferred), or a non-comparison non-temporal bullet returns null — the
     *  whole compound stays an Onopgelost marker, never a silently-dropped predicate. */
    private Conditie gedurendeSamengesteld(ToplevelSamengesteldeVoorwaardeContext sv, String bron) {
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
            Conditie.Kenmerk k = temporeelKenmerkUitOperator(ev.toplevelOperator(), ev.nonToplevelOperator(), src(ev));
            if (k != null) { delen.add(k); temporeel++; continue; }
            // §8.4 only an ALLE compound admits a non-temporal member, lowered as a static value-side guard (an OR
            // with a static member has different, deferred semantics). The non-temporal member must be a COMPARISON.
            Conditie.Vergelijking v = kwantor == Conditie.Kwantor.ALLE ? nietTemporeleVergelijking(ev) : null;
            if (v == null) return null;                                 // a non-comparison / OR non-temporal bullet -> marker
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
            v = new Conditie.Vergelijking(RuleChecker.naarOperator(RuleChecker.cmpOf(ev.toplevelOperator().vergelijkingsTekenGesplitst())),
                    links, bouw(ev.toplevelOperator().expressie()), src(ev));
        else if (ev.nonToplevelOperator() != null && ev.nonToplevelOperator().vergelijkingsTeken() != null
                && ev.nonToplevelOperator().expressie() != null)
            v = new Conditie.Vergelijking(RuleChecker.naarOperator(RuleChecker.cmpOf(ev.nonToplevelOperator().vergelijkingsTeken())),
                    links, bouw(ev.nonToplevelOperator().expressie()), src(ev));
        if (v == null || temporeelAttribuutOperand(v.links()) || temporeelAttribuutOperand(v.rechts())) return null;
        return v;
    }

    /** Whether an operand READS a TEMPORAL (tijdlijn) attribute — its runtime value is a {@code List<Segment>}, not a
     *  scalar, so it cannot be a §8.4 static comparison member. */
    private static boolean temporeelAttribuutOperand(Expr e) {
        return e instanceof Expr.AttribuutRef a && a.objecttype() != null
                && a.objecttype().attribuutGranulariteit.containsKey(a.attribuut());
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

    private Conditie aggregatieFilter(FunctieAanroepContext f, Objecttype elem) {
        if (elem == null) return null;
        java.util.List<ParseTree> operanden = new ArrayList<>();
        for (int i = 0; i < f.getChildCount(); i++) {
            ParseTree ch = f.getChild(i);
            if (ch instanceof ExpressieContext || ch instanceof PrimaireExpressieContext) operanden.add(ch);
        }
        if (operanden.size() == 1) {
            PredicaatContext p = vindDie(operanden.get(0));
            if (p == null) return null;
            Conditie filter = elementKenmerkFilter(p, elem);
            return filter != null ? filter : new Conditie.Onopgelost(src(p));
        }
        // §5.8.1 union telling "het aantal X, Y en Z": the count is the UNION of the per-operand
        // sets — a disjunctively-filtered single accumulate. OR the per-operand kenmerk filters over
        // the shared element (an instance in several sets matches the OR once, so it is counted
        // once). Any operand without a resolvable kenmerk filter (a comparison, an unfiltered set,
        // a different element) leaves the whole union Onopgelost -> a marker, never a wrong count.
        // (A min/max value-fold reaches here too with >1 operand, but its die is on a sub-operand
        // navigation, not the element — so it is excluded; only AANTAL unions.)
        if (f instanceof AantalFuncContext) {
            java.util.List<Conditie> delen = new ArrayList<>();
            for (ParseTree op : operanden) {
                // Every operand must range over the SAME element (a single accumulate has one source
                // pattern) AND resolve to a kenmerk filter of it. An operand over a DIFFERENT element
                // — even when it shares a kenmerk name — would otherwise have its foreign set silently
                // dropped and its filter OR-ed onto the wrong fact, so it leaves the whole union a marker.
                PredicaatContext p = vindDie(op);
                Conditie k = p != null && operandElement(op) == elem ? elementKenmerkFilter(p, elem) : null;
                if (k == null) return new Conditie.Onopgelost(src(f));
                delen.add(k);
            }
            return new Conditie.Samengesteld(Conditie.Kwantor.TEN_MINSTE, 1, delen, src(f));
        }
        return null;
    }

    /** The element type a count operand ("passagiers van …") ranges over, or null when it is not an
     *  "&lt;attribuut&gt; van &lt;onderwerp&gt;" naming a countable element — used to confirm every operand
     *  of a §5.8.1 union shares one element before OR-ing their filters. */
    private Objecttype operandElement(ParseTree op) {
        if (op instanceof PrimaireExpressieContext pe && pe.attribuutVanOnderwerp() != null
                && pe.attribuutVanOnderwerp().attribuutMetLidwoord() != null
                && pe.attribuutVanOnderwerp().attribuutMetLidwoord().kaleNaam() != null)
            return aantalElement(src(pe.attribuutVanOnderwerp().attribuutMetLidwoord().kaleNaam()));
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
        NonToplevelOperatorContext op = p.nonToplevelOperator();
        if (elem == null || op == null || op.kaleNaam() == null) return null;
        // §8.1.8: a kenmerk membership is "is/zijn (een) <kenmerk>". A "heeft/hebben een <X>" predicaat
        // is a possession/role query ("die een kind hebben" = that HAVE a child), a different predicate
        // class — not the element's boolean kenmerk; leave it to the marker path.
        if (op.IS() == null && op.ZIJN() == null) return null;
        // §5.6: the written form may be the kenmerk's inflected/nominalized spelling
        // ("die een minderjarige zijn" against the declared "minderjarig") — bind it morphologically.
        String kenmerk = KenmerkMatcher.vind(elem, src(op.kaleNaam()));
        return kenmerk != null ? new Conditie.Kenmerk(kenmerk, src(p)) : null;
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
            case EERSTE  -> Typering.datumAggregatie("eerste van", typen);
            case LAATSTE -> Typering.datumAggregatie("laatste van", typen);
            // §6.12 / §13.4.17.21-.24: a time-of-day component is defined only on a datum-TIJD — a plain
            // "Datum in dagen" has no hour/minute/second. The DATE parts (jaar/maand/dag) accept any date, so
            // they are NOT checked. Onbekend is skipped (no claim); only a concrete DatumInDagen is a violation.
            case UUR_UIT, MINUUT_UIT, SECONDE_UIT, MILLISECONDE_UIT -> tijdComponentFout(typen);
            default -> null;
        };
        if (fout != null)
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(
                    RegelSpraakModel.Severity.ERROR, fout, f.getStart().getLine()));
    }

    /** §6.12 / §13.4.17.21-.24: the operand of a time-of-day component (uur/minuut/seconde/milliseconde uit)
     *  must be a datum-tijd; a plain "Datum in dagen" carries no time. Returns the fout message for a concrete
     *  DatumInDagen operand, else null (Onbekend and any non-date type are not this check's concern). */
    private static String tijdComponentFout(List<Datatype> typen) {
        if (typen.size() != 1 || !(typen.get(0) instanceof Datatype.DatumInDagen)) return null;
        return "een tijd-component (uur/minuut/seconde/milliseconde uit) vereist een datum-tijd, kreeg "
                + typen.get(0).naam() + " (§13.4.17.21-.24)";
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
            case SOM, TOTAAL, MINIMUM, MAXIMUM, EERSTE, LAATSTE -> a.objecttype();
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

    private Datatype functieType(Expr.FunctieKind kind, List<Expr> args) {
        Datatype eerste = args.isEmpty() ? onbekend() : args.get(0).type();
        return switch (kind) {
            case SOM, TOTAAL, EERSTE, LAATSTE, ABSOLUTE_WAARDE -> eerste;
            // §4.12/§4.13: a multi-operand min/max result is the SMALLEST/LARGEST VALUE, whose
            // precision is value-dependent and not statically known. A single-operand min/max over a
            // set carries the element's precision (reliable). For the value-fold, report the LEAST
            // operand precision so the §6.1.2 check never over-reports (the exact value-dependent
            // precision is carded); the first operand's precision would be order-dependent.
            case MINIMUM, MAXIMUM -> args.size() <= 1 || !(eerste instanceof Datatype.Numeriek n) ? eerste
                    : new Datatype.Numeriek(alleGeheel(args), minDecimalen(args), n.teken(), n.eenheid());
            case AANTAL -> new Datatype.Numeriek(true, 0, Datatype.Teken.NIET_NEGATIEF, null); // a count
            case CONCATENATIE -> new Datatype.Tekst();
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
            default -> onbekend();
        };
    }

    private Expr.FunctieKind functieKind(FunctieAanroepContext f) {
        if (f instanceof SomFuncContext)        return Expr.FunctieKind.SOM;
        if (f instanceof AantalFuncContext)     return Expr.FunctieKind.AANTAL;
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

    // ---------------- references ----------------
    /** `<attribuut> van <onderwerp>`: bind the attribute's datatype via the subject's object type. */
    Expr bouwAttribuut(AttribuutVanOnderwerpContext a) {
        String bron = src(a);
        if (a == null || a.attribuutMetLidwoord() == null || a.attribuutMetLidwoord().kaleNaam() == null)
            return onopgelost(a);
        String attr = src(a.attribuutMetLidwoord().kaleNaam());
        String onderwerpBron = a.onderwerp() != null ? src(a.onderwerp()) : "";
        Objecttype ot = resolveOnderwerp(a.onderwerp());
        // The role the author wrote to reach `ot` (the noun of a DEFINITE reference or a
        // possessive, when it names a feittype role) — threaded so the transpiler
        // navigates the exact field even when the (subject, ot) pair is connected by more
        // than one feittype/role. An indefinite "een <X>" is the universal subject, not a
        // navigation, so it is excluded (it stays an own-attribute read).
        String noun = navigatieNoun(a.onderwerp());
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
        Datatype dt = ot != null && ot.attribuut(attr) != null ? ot.attribuut(attr) : onbekend();
        return new Expr.AttribuutRef(attr, ot, onderwerpBron, navRol, dt, bron, isSetOnderwerp(a.onderwerp()));
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

    /** A possessive "zijn <kaleNaam>" resolves to the named attribute OF the rule subject,
     *  when it is one (a "zijn <rol>" navigation is not an attribute and is left to the
     *  generic path). Returns null when it is not a subject-attribute possessive. */
    private Expr possessief(OnderwerpContext o, String bron) {
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
    private Expr dimensieCel(String kaleNaam, Objecttype ot, String navRol, String onderwerpBron, String bron) {
        if (ot == null || kaleNaam == null) return null;
        String beste = null; String besteLabel = null; RegelSpraakModel.Dimensie besteDim = null;
        for (var e : ot.attribuutDimensies.entrySet()) {
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
    private Expr naamRef(String naam, boolean isVariabele, String bron) {
        String stripped = naam.replaceFirst("^(?i)(de|het|een)\\s+", "");
        Parameter p = model.parameters.get(stripped);
        if (p == null) p = model.parameters.get(naam);
        if (p != null) return new Expr.ParameterRef(p, bron);
        if (isVariabele) return new Expr.VariabeleRef(naam, onbekend(), bron);
        return onopgelost(bron);
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
        return ot != null ? ot : vindObjecttype(o);
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

    private static String src(ParserRuleContext ctx) { return DefinitionResolver.src(ctx); }
    private static Datatype onbekend() { return new Datatype.Onbekend("niet opgelost"); }
    private Expr onopgelost(ParserRuleContext ctx) { return new Expr.Onopgelost(onbekend(), ctx == null ? "" : src(ctx)); }
    private Expr onopgelost(String bron) { return new Expr.Onopgelost(onbekend(), bron); }
}
