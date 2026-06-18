package io.github.yhavinga.regelspraak.resolver;

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

    // ---------------- §13.4.16/.17 expressie ----------------
    Expr bouw(ExpressieContext e) {
        if (e == null) return onopgelost(e);
        String bron = src(e);
        if (e instanceof PrimaireExprContext p) return bouwPrimair(p.primaireExpressie());
        if (e instanceof SomExprContext s) {
            Expr.BinOp op = s.PLUS() != null ? Expr.BinOp.PLUS
                    : s.MIN() != null ? Expr.BinOp.MIN : Expr.BinOp.VERMINDERD_MET;
            return binair(op, s.expressie(0), s.expressie(1), bron);
        }
        if (e instanceof ProductExprContext s) {
            Expr.BinOp op = s.MAAL() != null ? Expr.BinOp.MAAL
                    : s.GEDEELD_DOOR_ABS() != null ? Expr.BinOp.GEDEELD_DOOR_ABS : Expr.BinOp.GEDEELD_DOOR;
            return binair(op, s.expressie(0), s.expressie(1), bron);
        }
        if (e instanceof MachtExprContext s) {
            Expr basis = binair(Expr.BinOp.TOT_DE_MACHT, s.expressie(0), s.expressie(1), bron);
            return s.afronding() != null ? afgerond(basis, s.afronding(), bron) : basis;
        }
        if (e instanceof PercentageVanExprContext s) {
            // §4.8 Tabel 18: "X% van Y" has the datatype (incl. the unit) of Y. Do not
            // fabricate a unitless Numeriek — that is a positive false claim; carry Y's
            // type, or Onbekend when Y is not numeric (no claim).
            Expr links = bouw(s.expressie(0)), rechts = bouw(s.expressie(1));
            Datatype t = rechts.type().isNumeriek() ? rechts.type() : onbekend();
            return new Expr.Binair(Expr.BinOp.PERCENTAGE_VAN, links, rechts, t, bron);
        }
        if (e instanceof AfgerondeExprContext s) return afgerond(bouw(s.expressie()), s.afronding(), bron);
        if (e instanceof BegrensdeExprContext s) {
            Expr basis = bouw(s.expressie());
            Expr[] mm = grenzen(s.begrenzing());
            return new Expr.Begrensd(basis, mm[0], mm[1], bron);
        }
        return onopgelost(e);
    }

    /** Arithmetic node whose result datatype is taken from the typeringen matrix
     *  (a type error there yields Onbekend, matching the checker's no-claim path). */
    private Expr binair(Expr.BinOp op, ExpressieContext l, ExpressieContext r, String bron) {
        Expr links = bouw(l), rechts = bouw(r);
        Typering.Resultaat res = Typering.reken(naarTyperingOp(op), links.type(), rechts.type());
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

    /** Resolve a begrenzing into [minimum, maximum] (either may be null). */
    private Expr[] grenzen(BegrenzingContext b) {
        Expr min = null, max = null;
        if (b.begrenzingMinimum() != null) min = bouwPrimair(b.begrenzingMinimum().primaireExpressie());
        if (b.begrenzingMaximum() != null) max = bouwPrimair(b.begrenzingMaximum().primaireExpressie());
        return new Expr[]{ min, max };
    }

    // ---------------- §13.4.17 primaire expressie ----------------
    private Expr bouwPrimair(PrimaireExpressieContext p) {
        if (p == null) return onopgelost(p);
        String bron = src(p);
        if (p.getalWaarde() != null) {
            GetalWaardeContext g = p.getalWaarde();
            if (g.PERCENT_SIGN() != null) return new Expr.Literal(bron, new Datatype.Percentage(false, 0), bron);
            if (g.eenheidExpressie() != null) {
                String afk = src(g.eenheidExpressie().eenheidMacht(0).eenheidAfkorting());
                return new Expr.Literal(bron, new Datatype.Numeriek(false, 0, Datatype.Teken.ONBEPAALD, model.resolveEenheid(afk)), bron);
            }
            return new Expr.Literal(bron, new Datatype.Numeriek(false, 0, Datatype.Teken.ONBEPAALD, null), bron);
        }
        if (p.tekstWaarde() != null)    return new Expr.Literal(bron, new Datatype.Tekst(), bron);
        if (p.booleanWaarde() != null)  return new Expr.Literal(bron, new Datatype.Booleaan(), bron);
        if (p.deDato() != null)         return new Expr.Literal(bron, new Datatype.DatumInDagen(), bron);
        if (p.ENUM_LITERAL() != null)   return new Expr.Literal(bron, onbekend(), bron); // which Enumeratie is context-dependent
        if (p.rekenConstante() != null) {
            boolean jaar = p.rekenConstante().REKENJAAR() != null;
            Datatype t = jaar ? new Datatype.Numeriek(true, 0, Datatype.Teken.ONBEPAALD, null) : new Datatype.DatumInDagen();
            return new Expr.Constante(jaar ? "Rekenjaar" : "Rekendatum", t, bron);
        }
        if (p.HIJ() != null)            return new Expr.Constante("hij", onbekend(), bron);
        if (p.DE() != null && p.DAG() != null) return new Expr.Constante("de dag", onbekend(), bron);
        if (p.functieAanroep() != null) return bouwFunctie(p.functieAanroep());
        if (p.datumMet() != null)       return new Expr.Functie(Expr.FunctieKind.DATUM_MET, args(p.datumMet()), null, null, new Datatype.DatumInDagen(), bron);
        if (p.datumTijdMet() != null)   return new Expr.Functie(Expr.FunctieKind.DATUMTIJD_MET, args(p.datumTijdMet()), null, null, new Datatype.DatumTijd(), bron);
        if (p.attribuutVanOnderwerp() != null) return bouwAttribuut(p.attribuutVanOnderwerp());
        if (p.expressie() != null)      return bouw(p.expressie()); // ( expressie )
        if (p.onderwerp() != null) {
            Expr poss = possessief(p.onderwerp(), bron); // "zijn <attribuut van het subject>"
            return poss != null ? poss : naamRef(src(p.onderwerp()), false, bron);
        }
        if (p.variabeleNaam() != null)  return naamRef(src(p.variabeleNaam()), true, bron);
        return onopgelost(p);
    }

    // ---------------- §13.4.17 functies / aggregaties ----------------
    private Expr bouwFunctie(FunctieAanroepContext f) {
        String bron = src(f);
        Expr.FunctieKind kind = functieKind(f);
        List<Expr> argumenten = args(f);
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
        if (kind == Expr.FunctieKind.AANTAL && aggregatieElement(kind, argumenten) == null) {
            Expr ref = naamRef(bron, false, bron);
            if (!(ref instanceof Expr.Onopgelost)) return ref;
        }
        Datatype type = f instanceof TijdsduurFuncContext t ? tijdsduurType(t) : functieType(kind, argumenten);
        Expr leegwaarde = leegwaardeVan(f);
        // §4.10/§5.8.2: the fallback substitutes for the sum's RESULT, so it must be numeriek
        // (a Tekst/Datum fallback would otherwise emit a type-mixed DRL ternary that throws at
        // fire time). Onbekend = no claim, so it is not flagged.
        if (leegwaarde != null && !leegwaarde.type().isNumeriek() && !leegwaarde.type().isOnbekend())
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.ERROR,
                    "leegwaarde-alternatief van een sommatie moet numeriek zijn, kreeg "
                            + leegwaarde.type() + " (§5.8.2)", f.getStart().getLine()));
        return new Expr.Functie(kind, argumenten, aggregatieElement(kind, argumenten), leegwaarde, type, bron);
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
            case AANTAL -> model.resolveObjecttype(a.attribuut());
            default -> null;
        };
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
    private Datatype functieType(Expr.FunctieKind kind, List<Expr> args) {
        Datatype eerste = args.isEmpty() ? onbekend() : args.get(0).type();
        return switch (kind) {
            case SOM, TOTAAL, MINIMUM, MAXIMUM, EERSTE, LAATSTE, ABSOLUTE_WAARDE -> eerste;
            case AANTAL -> new Datatype.Numeriek(true, 0, Datatype.Teken.NIET_NEGATIEF, null); // a count
            case CONCATENATIE -> new Datatype.Tekst();
            case EERSTE_PAASDAG, DATUM_MET -> new Datatype.DatumInDagen();
            case DATUMTIJD_MET -> new Datatype.DatumTijd();
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
        return new Expr.AttribuutRef(attr, ot, onderwerpBron, navRol, dt, bron);
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
        return dt != null ? new Expr.AttribuutRef(attr, huidigSubject, "zijn", null, dt, bron) : null;
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
    private Objecttype resolveOnderwerp(OnderwerpContext o) {
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
