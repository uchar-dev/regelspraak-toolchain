package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.Geldigheid;
import io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedVerdeling;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.*;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.time.LocalDate;
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
            List<RegelVersieContext> versies = r.regelVersie();
            // §13.4.2.5 validity per version, computed up front so §4.2's non-overlap
            // invariant can be checked across the rule's versions. A version whose validity
            // bound is malformed is null here (already diagnosed) and is not emitted.
            List<Geldigheid> gelden = new ArrayList<>();
            for (RegelVersieContext v : versies) gelden.add(geldigheidVan(v.versie()));
            if (versies.size() > 1) meldOverlap(naam, gelden, r.getStart().getLine());
            for (int i = 0; i < versies.size(); i++) {
                Geldigheid geldigheid = gelden.get(i);
                if (geldigheid == null) continue; // malformed validity bound — already reported
                RegelVersieContext v = versies.get(i);
                RegelSpraakRegelContext rsr = v.regelSpraakRegel();
                // Each §13.4.2.3 version is a distinct rule selected by Rekendatum (§4.2):
                // when a rule has several, give each a unique name.
                String versieNaam = versies.size() > 1 ? naam + " #" + (i + 1) : naam;
                bouwer.setSubject(subjectVan(rsr.resultaatDeel())); // so "zijn …" resolves against the rule subject
                Conditie voorwaarde = rsr.voorwaardeDeel() != null ? bouwVoorwaarde(rsr.voorwaardeDeel()) : null;
                resultaat(naam, versieNaam, rsr.resultaatDeel(), voorwaarde, geldigheid);
                voorwaarden(rsr);   // exhaustive condition type-check (§5), unchanged
            }
        }
    }

    /** §4.2 (spec line 1387): the validity periods of a rule's versions must not overlap —
     *  otherwise more than one version applies at a Rekendatum (and, writing the same
     *  attribute, they ping-pong in the engine). Flag any intersecting pair. */
    private void meldOverlap(String naam, List<Geldigheid> gelden, int line) {
        for (int i = 0; i < gelden.size(); i++)
            for (int j = i + 1; j < gelden.size(); j++) {
                Geldigheid a = gelden.get(i), b = gelden.get(j);
                if (a != null && b != null && overlapt(a, b)) {
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "geldigheidsperioden van regelversies overlappen (regel '" + naam + "')", line));
                    return;
                }
            }
    }
    /** Two inclusive windows intersect unless one's upper bound is strictly before the
     *  other's lower bound; a null bound is an open end (±∞), so ALTIJD overlaps everything. */
    private static boolean overlapt(Geldigheid a, Geldigheid b) {
        return !voorbij(a.tot(), b.vanaf()) && !voorbij(b.tot(), a.vanaf());
    }
    private static boolean voorbij(LocalDate tot, LocalDate vanaf) {
        return tot != null && vanaf != null && tot.isBefore(vanaf);
    }

    /** §13.4.2.5 version validity, or null when a present bound is not a valid date/year
     *  (already diagnosed): such a version is dropped, not silently made always-active.
     *  "geldig altijd" is open both ends; a year widens to the whole year (vanaf -> 1 Jan,
     *  t/m -> 31 Dec), t/m being inclusive. */
    private Geldigheid geldigheidVan(VersieContext v) {
        if (v == null || v.versieGeldigheid() == null) return Geldigheid.ALTIJD;
        VersieGeldigheidContext g = v.versieGeldigheid();
        if (g.ALTIJD() != null) return Geldigheid.ALTIJD;
        int line = v.getStart().getLine();
        java.util.List<DatumOfJaarContext> data = g.datumOfJaar();
        LocalDate vanaf = null, tot = null;
        if (g.VANAF() != null) {
            vanaf = grensOfMeld(data.get(0), true, line);
            if (vanaf == null) return null;
            if (g.TM() != null && data.size() > 1) {
                tot = grensOfMeld(data.get(1), false, line);
                if (tot == null) return null;
            }
        } else if (g.TM() != null && !data.isEmpty()) {
            tot = grensOfMeld(data.get(0), false, line);
            if (tot == null) return null;
        }
        return new Geldigheid(vanaf, tot);
    }

    /** Resolve a validity bound, or report an ERROR and return null when it is neither a
     *  valid calendar date (dd-mm-jjjj) nor a four-digit year (§13.2.24). */
    private LocalDate grensOfMeld(DatumOfJaarContext d, boolean onder, int line) {
        LocalDate b = grens(d, onder);
        if (b == null)
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "ongeldige versiegeldigheidsgrens '" + (d != null ? src(d) : "")
                            + "' (verwacht een datum dd-mm-jjjj of een jaartal van vier cijfers)", line));
        return b;
    }

    /** A datumOfJaar to a concrete date, or null when it is not a real calendar date or a
     *  four-digit year. Never throws — an out-of-range day/month/year yields null. */
    private static LocalDate grens(DatumOfJaarContext d, boolean onder) {
        if (d == null) return null;
        if (d.DATE() != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})-(\\d{4})").matcher(d.DATE().getText());
            if (!m.find()) return null;
            try {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            } catch (java.time.DateTimeException e) { return null; }
        }
        // §13.2.24: a bare year is exactly four digits — reject a decimal/fraction/signed/
        // out-of-range NUMBER the grammar's generic token would otherwise admit.
        if (d.NUMBER() != null && d.NUMBER().getText().matches("\\d{4}")) {
            try {
                int jaar = Integer.parseInt(d.NUMBER().getText());
                return onder ? LocalDate.of(jaar, 1, 1) : LocalDate.of(jaar, 12, 31);
            } catch (NumberFormatException | java.time.DateTimeException e) { return null; }
        }
        return null;
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
     *  and type-check the value against the target (§6 toekenning). The assignment kinds
     *  carry the version's {@code geldigheid} and its per-version {@code versieNaam}; the
     *  other resultaatdeel kinds keep the base {@code naam} and (for now) no validity guard. */
    private void resultaat(String naam, String versieNaam, ResultaatDeelContext rd,
                           Conditie voorwaarde, Geldigheid geldigheid) {
        if (rd == null) return;
        if (rd.gelijkstelling() != null) {
            GelijkstellingContext g = rd.gelijkstelling();
            ResolvedRegel.Soort soort = g.WORDT_BEREKEND_ALS() != null
                    ? ResolvedRegel.Soort.BEREKENING : ResolvedRegel.Soort.GELIJKSTELLING;
            registreer(versieNaam, soort, g.attribuutVanOnderwerp(), g.expressie(), g.getStart().getLine(), voorwaarde, geldigheid);
        } else if (rd.initialisatie() != null) {
            InitialisatieContext i = rd.initialisatie();
            registreer(versieNaam, ResolvedRegel.Soort.INITIALISATIE, i.attribuutVanOnderwerp(),
                       i.expressie(), i.getStart().getLine(), voorwaarde, geldigheid);
        } else if (rd.kenmerkToekenning() != null) {
            kenmerk(versieNaam, rd.kenmerkToekenning(), voorwaarde, geldigheid);
        } else if (rd.objectCreatie() != null) {
            objectcreatie(versieNaam, rd.objectCreatie(), voorwaarde, geldigheid);
        } else if (rd.verdeling() != null) {
            verdeling(versieNaam, rd.verdeling(), voorwaarde, geldigheid);
        } else if (rd.consistentieRegel() != null) {
            consistentie(versieNaam, rd.consistentieRegel(), geldigheid);
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
    private void consistentie(String naam, ConsistentieRegelContext cr, Geldigheid geldigheid) {
        EnkelvoudigeConsistentieRegelContext e = cr.enkelvoudigeConsistentieRegel();
        if (e != null && e.expressie() != null && e.toplevelOperator() != null) {
            Expr lhs = bouwer.bouw(e.expressie());
            Conditie assertie = uitToplevel(lhs, e.toplevelOperator(), src(e));
            Objecttype subject = lhs instanceof Expr.AttribuutRef a ? a.objecttype() : null;
            if (subject != null && transpileerbaar(assertie)) {
                model.consistenties.add(new io.github.yhavinga.regelspraak.ir.ResolvedConsistentie(naam, subject, assertie, src(cr), geldigheid));
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
    private void verdeling(String naam, VerdelingContext v, Conditie voorwaarde, Geldigheid geldigheid) {
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
                d.objecttype(), d.attribuut(), methode, rato, voorwaarde, geldigheid));
    }

    /** §13.4.6 objectcreatie: "<subject> heeft <het rol> met <attribuut> gelijk aan <expr>
     *  (, <attribuut> gelijk aan <expr>)*". The first kaleNaam carries "<rol> met
     *  <attribuut>" (the 'met' fold the grammar leaves to the resolver); the rest are
     *  bare attribute names. */
    private void objectcreatie(String naam, ObjectCreatieContext oc, Conditie voorwaarde, Geldigheid geldigheid) {
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

        model.objectCreaties.add(new ResolvedObjectCreatie(naam, nieuwType, subject, toek, voorwaarde, geldigheid));
    }

    private static String stripArtikel(String s) { return s.replaceFirst("^(?i)(de|het|een)\\s+", "").trim(); }

    /** §13.4.5 kenmerktoekenning: the subject acquires a kenmerk (a boolean property).
     *  Modelled as setting that boolean attribute to true. */
    private void kenmerk(String naam, KenmerkToekenningContext kt, Conditie voorwaarde, Geldigheid geldigheid) {
        Objecttype subject = bouwer.objecttypeVanKeten(kt.onderwerpKeten());
        String kenmerk = src(kt.kaleNaam()).replaceFirst("^(?i)(de|het|een)\\s+", "");
        Expr doel = new Expr.AttribuutRef(kenmerk, subject, src(kt.onderwerpKeten()),
                null, new Datatype.Booleaan(), src(kt));
        Expr waarde = new Expr.Literal("waar", new Datatype.Booleaan(), "waar");
        model.resolvedRegels.add(new ResolvedRegel(naam, ResolvedRegel.Soort.KENMERKTOEKENNING, doel, waarde, voorwaarde, geldigheid));
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
                            ExpressieContext waarde, int line, Conditie voorwaarde, Geldigheid geldigheid) {
        Expr doelExpr = bouwer.bouwAttribuut(doel);
        Expr waardeExpr = bouwer.bouw(waarde);
        model.resolvedRegels.add(new ResolvedRegel(naam, soort, doelExpr, waardeExpr, voorwaarde, geldigheid));
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
        Conditie.Kwantor kwantor = kwantorVan(k);
        int drempel = drempelVan(k);
        // A counting quantifier's telwoord is a positive whole number (§13.4.14.4 /
        // §13.4.14.5: één/twee/… or a written getal). A 0, negative, decimal, fraction or
        // unparseable value all reach drempelVan as < 1; flag it rather than let it collapse
        // the rule to always-true (minstens(0) is vacuously true). An over-size N is fine —
        // it is a faithfully unsatisfiable "false", so it is not an error.
        if ((kwantor == Conditie.Kwantor.TEN_MINSTE || kwantor == Conditie.Kwantor.TEN_HOOGSTE
                || kwantor == Conditie.Kwantor.PRECIES) && drempel < 1)
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "telwoord van een kwantificatie moet een positief geheel getal (>= 1) zijn",
                    (k != null ? k : heel).getStart().getLine()));
        return new Conditie.Samengesteld(kwantor, drempel, onderdelen, src(heel));
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
        Conditie kenmerk = kenmerkPredicaat(lhs, op.kaleNaam(), op.EEN() != null, bron); // EEN? kaleNaam (is|heeft): §8.1.8
        return kenmerk != null ? kenmerk : new Conditie.Onopgelost(bron); // else objecttype/navigation membership
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
        Conditie kenmerk = kenmerkPredicaat(lhs, op.kaleNaam(), op.EEN() != null, bron); // (is|heeft) een? kaleNaam: §8.1.8
        return kenmerk != null ? kenmerk : new Conditie.Onopgelost(bron); // else objecttype/navigation membership
    }

    /** §8.1.8: "&lt;subject&gt; is/heeft &lt;kenmerk&gt;" -> a {@link Conditie.Kenmerk}, when the operand
     *  denotes the rule subject and the (reconstructed) name is a declared kenmerk of it. The leading
     *  EEN article is NOT a kenmerk reference: per §13.4.15.44/.45 "is een &lt;naam&gt;" is an
     *  objecttype/role-membership test, so it is left Onopgelost (carded) — this also stops a name
     *  that is both a kenmerk and an objecttype/role from being silently gated as the kenmerk. */
    private Conditie kenmerkPredicaat(Expr lhs, KaleNaamContext naamCtx, boolean metEen, String bron) {
        Objecttype subject = bouwer.subject();
        if (metEen || naamCtx == null || subject == null) return null;
        String kop = subjectKop(lhs, subject);
        if (kop == null) return null;
        // The greedy onderwerp parse absorbs a multi-word kenmerk's leading words into the operand
        // ("de Meting bereikbaar per" + operator "trein"), so the kenmerk is that absorbed kop plus
        // the operator's kaleNaam; for "hij"/a bare subject the kop is empty and the name is the kaleNaam.
        String staart = stripArtikel(src(naamCtx));
        String kenmerk = kop.isEmpty() ? staart : kop + " " + staart;
        String gevonden = KenmerkMatcher.vind(subject, kenmerk);
        return gevonden != null ? new Conditie.Kenmerk(gevonden, bron) : null;
    }

    /** When the condition operand denotes the rule subject, the part of a multi-word kenmerk the
     *  greedy onderwerp parse absorbed into the operand (empty for the bezielde "hij" or a bare
     *  subject reference); null when the operand is not the subject. A navigation ("de partner van
     *  …") names a different filler, so it is excluded — its kenmerk would be on another object. */
    private String subjectKop(Expr lhs, Objecttype subject) {
        if (lhs instanceof Expr.Constante c) return c.naam().equalsIgnoreCase("hij") ? "" : null;
        String b = lhs.bron() == null ? "" : stripArtikel(lhs.bron());
        if (b.equalsIgnoreCase(subject.naam)) return "";                              // "de Meting"
        if (b.regionMatches(true, 0, subject.naam + " ", 0, subject.naam.length() + 1)) // "de Meting bereikbaar per"
            return b.substring(subject.naam.length()).trim();
        return null;
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
