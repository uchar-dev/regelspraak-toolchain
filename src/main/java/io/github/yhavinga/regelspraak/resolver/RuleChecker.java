package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.Geldigheid;
import io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedVerdeling;
import io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie.Bron;
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
        } else if (rd.feitCreatie() != null) {
            feitCreatie(versieNaam, rd.feitCreatie(), voorwaarde, geldigheid);
        } else {
            // §13.4.11/.12 — parsed but not yet lifted into the IR. Surface it as
            // a diagnostic rather than letting the rule silently disappear from the model.
            String soort = rd.startpuntBepaling() != null ? "startpuntbepaling"
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
                model.consistenties.add(new io.github.yhavinga.regelspraak.ir.ResolvedConsistentie(naam, subject, assertie, src(cr), cr.getStart().getLine(), geldigheid));
                return;
            }
        }
        if (cr.uniciteitsControle() != null && uniciteit(naam, cr.uniciteitsControle(), geldigheid)) return;
        // §13.4.8 samengestelde consistentieregel — quantified bullet criteria, in either the
        // subject-led ("<onderwerp> moet voldoen aan alle volgende criteria: …") or the impersonal
        // ("er moet worden voldaan aan …") form. Build a Conditie.Samengesteld over the criteria; the
        // subject is the type the criteria are about (the first criterion's left operand).
        ConsistentieKwantificatieContext kw = null;
        SamengesteldCriteriumOnderdeelContext crit = null;
        if (cr.samengesteldeConsistentie() != null) {
            kw = cr.samengesteldeConsistentie().consistentieKwantificatie();
            crit = cr.samengesteldeConsistentie().samengesteldCriteriumOnderdeel();
        } else if (cr.toplevelSamengesteldCriterium() != null) {
            kw = cr.toplevelSamengesteldCriterium().consistentieKwantificatie();   // null for the single "volgend criterium"
            crit = cr.toplevelSamengesteldCriterium().samengesteldCriteriumOnderdeel();
        }
        if (crit != null) {
            Conditie assertie = bouwCriteria(kw, crit, src(cr));
            Objecttype subject = subjectVanAssertie(assertie);
            // All criteria must be about the SAME subject: the rule lowers to one subject pattern, so a
            // criterion over a different object type would emit that type's attribute on the wrong fact
            // (an uncompilable property that sinks the whole DRL package). Mixed subjects -> WARNING.
            if (subject != null && transpileerbaar(assertie) && eenSubject(assertie, subject)) {
                model.consistenties.add(new io.github.yhavinga.regelspraak.ir.ResolvedConsistentie(naam, subject, assertie, src(cr), cr.getStart().getLine(), geldigheid));
                return;
            }
        }
        // A membership criterion ("moet een <kenmerk|rol> zijn"), elfproef /
        // numeriek-met-exact, a cross-type "verenigd met" uniqueness, or a computed/unresolved
        // subject: forms whose "inconsistent" we cannot yet express as a faithful violation rule.
        // Surface the gap as a WARNING rather than lift it into a silent inert rule (which would
        // both drop the check and lose the diagnostic the §13.4.7/.11/.12 path still gives).
        model.diagnostics.add(new Diagnostic(Severity.WARNING,
                "consistentieregel wordt nog niet (volledig) naar IR/DRL omgezet (regel '" + naam + "')",
                cr.getStart().getLine()));
    }

    /** §8.1.6 uniciteitscontrole — either "de &lt;attribuut&gt; van alle &lt;Type&gt; moeten uniek zijn"
     *  or a §5.7 concatenatie "de concatenatie van &lt;attribuut&gt; en … van alle &lt;Type&gt; …". Resolves
     *  the object type (by name, declared meervoudsvorm, or regular plural) and the key attribute(s)
     *  over which the value — or the COMBINATION of values — must be unique, into a
     *  {@link io.github.yhavinga.regelspraak.ir.ResolvedUniciteit}. Returns false — leaving the WARNING — for the
     *  cross-type "verenigd met" union, a uniqueness scoped within a parent ("… van alle X van &lt;Y&gt;"),
     *  or any name that does not resolve; the transpiler then never silently drops or mis-checks. */
    private boolean uniciteit(String naam, UniciteitsControleContext u, Geldigheid geldigheid) {
        // §8.1.6 parent-scoped "de X van alle Y van <parent>": unique within each parent, not globally.
        if (u.vereniging().isEmpty() && u.alleAttribuutVanOnderwerp() != null) {
            var scoped = parentScoped(naam, u.alleAttribuutVanOnderwerp(), src(u), geldigheid);
            if (scoped != null) { model.uniciteiten.add(scoped); return true; }
        }
        Bron eerste = bronVan(u.alleAttribuutVanOnderwerp(), u.uniciteitConcatenatie());
        if (eerste == null) return false;
        if (u.vereniging().isEmpty()) {                              // the common single-source form
            model.uniciteiten.add(new io.github.yhavinga.regelspraak.ir.ResolvedUniciteit(
                    naam, eerste.type(), eerste.sleutels(), null, src(u), geldigheid));
            return true;
        }
        // §8.1.6 "verenigd met": uniqueness across the UNION of two or more sources' key spaces. Every
        // source must resolve and carry the SAME key arity (the values union positionally — "attributen
        // met hetzelfde datatype"); any unresolved/mismatched source leaves the whole rule a WARNING.
        java.util.List<Bron> bronnen = new ArrayList<>();
        bronnen.add(eerste);
        for (VerenigingContext v : u.vereniging()) {
            Bron b = bronVan(v.alleAttribuutVanOnderwerp(), v.uniciteitConcatenatie());
            // §8.1.6: "een vereniging is de combinatie van twee attributen met HETZELFDE datatype". The
            // values join one space, so each source's key parts must match the first's in arity AND
            // datatype-kind; otherwise the union is ill-formed (Tekst vs Numeriek would cross-match
            // under mvel's coercive ==) — leave it a WARNING, like an arity mismatch.
            if (b == null || b.sleutels().size() != eerste.sleutels().size() || !zelfdeDatatypes(eerste, b))
                return false;
            bronnen.add(b);
        }
        model.uniciteitUnies.add(new io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie(naam, bronnen, src(u), geldigheid));
        return true;
    }

    /** One uniqueness source — "de &lt;attribuut&gt; van alle &lt;Type&gt;" or a §5.7 concatenatie — resolved
     *  to its object type and key attribute(s), or null when it does not resolve (an unknown name, a
     *  parent-scoped navigation, a nested-"van" attribute). Exactly one of the two contexts is non-null. */
    private Bron bronVan(AlleAttribuutVanOnderwerpContext a, UniciteitConcatenatieContext c) {
        if (a != null) {
            if (!a.onderwerpKeten().isEmpty()) return null;          // scoped within a parent: not lifted
            Objecttype type = MeervoudMatcher.vindObjecttype(model.objecttypen.values(), src(a.kaleNaam(1)));
            if (type == null) return null;
            String attr = MeervoudMatcher.vindAttribuut(type, src(a.kaleNaam(0)));  // "DE attr VAN ALLE": no absorbed article
            return attr == null ? null : new Bron(type, java.util.List.of(attr));
        }
        if (c != null) {
            if (!c.onderwerpKeten().isEmpty()) return null;
            // CONCATENATIE_VAN kaleNaam (COMMA kaleNaam)* EN kaleNaam VAN ALLE <type>: the last kaleNaam
            // is the type, the rest are the key attributes (each absorbs its leading article — strip it);
            // a nested-"van" part does not resolve, leaving a WARNING rather than a partial key.
            java.util.List<KaleNaamContext> namen = c.kaleNaam();
            Objecttype type = MeervoudMatcher.vindObjecttype(model.objecttypen.values(), src(namen.get(namen.size() - 1)));
            if (type == null) return null;
            java.util.List<String> sleutels = new ArrayList<>();
            for (int i = 0; i < namen.size() - 1; i++) {
                String attr = MeervoudMatcher.vindAttribuut(type, stripArtikel(src(namen.get(i))));
                if (attr == null) return null;
                sleutels.add(attr);
            }
            return new Bron(type, sleutels);
        }
        return null;
    }

    /** §8.1.6 parent-scoped uniqueness "de X van alle Y van &lt;parent&gt;" — unique within each parent.
     *  Resolves the element Y, its key attribute X, and the reference field on a Y that points at its
     *  scope owner &lt;parent&gt; (so the count is restricted per-parent). Null when it is not the scoped
     *  shape — then the global single-source path applies — or when the scope does not resolve. */
    private io.github.yhavinga.regelspraak.ir.ResolvedUniciteit parentScoped(String naam, AlleAttribuutVanOnderwerpContext a,
                                                                 String bron, Geldigheid g) {
        if (!a.onderwerpKeten().isEmpty()) return null;
        java.util.List<KaleNaamDeelContext> delen = a.kaleNaam(1).kaleNaamDeel();
        if (delen.size() != 2) return null;                          // element + exactly one parent navigation
        // The element is an object type ("alle Vluchten") or, more often when scoped, a role name
        // ("alle passagiers van de reis") — the role's plural matches via the feittype, not the
        // conservative object-type plural.
        Objecttype elem = MeervoudMatcher.vindObjecttype(model.objecttypen.values(), src(delen.get(0)));
        if (elem == null) elem = model.rolDoeltypeNaarSubject(null, src(delen.get(0)));
        if (elem == null) return null;
        String attr = MeervoudMatcher.vindAttribuut(elem, src(a.kaleNaam(0)));
        if (attr == null) return null;
        String parentNav = stripArtikel(src(delen.get(1)));
        Objecttype parentType = model.rolDoeltypeNaarSubject(elem, parentNav);  // the role's object type
        if (parentType == null) return null;
        String joinVeld = model.verbindendeRol(elem, parentType);    // field on a Y pointing at its <parent>
        if (joinVeld == null) return null;
        return new io.github.yhavinga.regelspraak.ir.ResolvedUniciteit(naam, elem, java.util.List.of(attr), joinVeld, bron, g);
    }

    /** §8.1.6: two union sources may be combined only when their key parts share a datatype. Compared
     *  by datatype KIND positionally (Tekst vs Numeriek differ; a finer unit/precision check is carded).
     *  A non-matching pair is ill-formed — the caller leaves it a WARNING. */
    private static boolean zelfdeDatatypes(Bron a, Bron b) {
        for (int i = 0; i < a.sleutels().size(); i++) {
            Datatype da = a.type().attribuut(a.sleutels().get(i));
            Datatype db = b.type().attribuut(b.sleutels().get(i));
            if (da == null || db == null || da.getClass() != db.getClass()) return false;
            // §8.1.6: the sources combine "attributen met HETZELFDE datatype". For Numeriek the unit IS
            // part of the datatype, and units do not survive into the fact model (values are bare
            // BigDecimals), so a union of a EUR attribute and a jr attribute would cross-match "5 EUR" and
            // "5 jr" as one value — a silently-wrong false duplicate. Require the SAME eenheid (both unitless
            // counts too); a unit mismatch is as ill-formed as a Tekst∪Numeriek one and leaves a WARNING.
            if (da instanceof Datatype.Numeriek na && db instanceof Datatype.Numeriek nb
                    && !java.util.Objects.equals(na.eenheid(), nb.eenheid())) return false;
        }
        return true;
    }

    /** The single-criterion assertions whose negation the transpiler can emit as a
     *  faithful violation rule: a comparison, or an emptiness predicate. Everything else
     *  (membership, elfproef, numeriek-met-exact, unstructured) is left to a WARNING. */
    private static boolean transpileerbaar(Conditie c) {
        if (c instanceof Conditie.Vergelijking) return true;
        if (c instanceof Conditie.Predicaat p)
            return p.soort() == Conditie.Predicaatsoort.LEEG || p.soort() == Conditie.Predicaatsoort.GEVULD;
        // An ALLE compound is the violation = OR of the criteria's violations (transpiler), lowerable
        // when every criterion is. GEEN and the counting kwantoren (ten minste/hoogste/precies) need a
        // satisfied-criterion count the single violation rule cannot express yet — left to a WARNING.
        if (c instanceof Conditie.Samengesteld s)
            return s.kwantor() == Conditie.Kwantor.ALLE && !s.onderdelen().isEmpty()
                    && s.onderdelen().stream().allMatch(RuleChecker::transpileerbaar);
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

    /** §13.4.7 feitcreatie: "Een &lt;rolB&gt; van een &lt;anker&gt; is een &lt;rolC&gt; van
     *  &lt;onderwerpD&gt;" creates an instance of the feittype that {@code rolB} names, relating each
     *  {@code anker} to the {@code doel} objects that fill {@code rolC} of it (§9.4). The grammar's
     *  greedy kaleNaam splits the RHS at the LAST "van" (the rule's trailing {@code VAN onderwerp}),
     *  so the role part is the FIRST kaleNaam segment and any further segment is a navigation; this
     *  resolver re-splits per the §13.4-names design rather than changing the grammar.
     *  <p>Lifts only the 1-hop shape — {@code onderwerpD} is the anker itself, reached by the single
     *  role {@code rolC} — into a {@link ResolvedFeitCreatie}. A multi-hop navigation (the TOKA
     *  "verdeling contingent treinmiles over passagiers" example) or an unresolvable role/onderwerp
     *  keeps an honest WARNING rather than a silent wrong link. */
    private void feitCreatie(String naam, FeitCreatieContext fc, Conditie voorwaarde, Geldigheid geldigheid) {
        String rolB = stripArtikel(src(fc.kaleNaam(0)));               // LHS role — the relation being created
        Objecttype anker = bouwer.resolveOnderwerp(fc.onderwerp(0));   // the "one" side / iterated subject
        KaleNaamContext rhs = fc.kaleNaam(1);
        String rolC = stripArtikel(src(rhs.kaleNaamDeel(0)));          // RHS role — the existing navigation
        boolean meerHops = rhs.kaleNaamDeel().size() > 1;             // role part itself navigates further -> multi-hop
        // onderwerpD must DIRECTLY name the anker objecttype (a back-reference to the iterated subject),
        // not merely resolve to the same type through a role: "de vlucht" lifts, but "de aansluitvlucht"
        // (a different flight of the same type reached by a role) must WARN, not silently reuse the
        // anker's own join. A type-identity test cannot tell them apart, so check the written noun.
        String terugNoun = bouwer.primaireNaamVanOnderwerp(fc.onderwerp(1));
        boolean directeAnker = anker != null && terugNoun != null
                && model.objecttypeOpNaam(stripArtikel(terugNoun)) == anker;

        String reden;
        if (anker == null) reden = "anker-objecttype niet herleidbaar";
        else if (meerHops) reden = "meerstaps-navigatie in de doelketen";
        else if (!directeAnker) reden = "doelketen verwijst niet rechtstreeks naar de anker (geen back-reference)";
        else {
            Objecttype doel = model.rolDoeltypeNaarSubject(anker, rolC);
            String joinVeld = model.verbindendeRolViaElementnaam(anker, rolC); // existing rolC relation's field on doel
            String zetVeld  = model.verbindendeRolViaElementnaam(anker, rolB); // the NEW relation's field on doel
            if (doel == null || joinVeld == null || zetVeld == null)
                reden = "rol of feittype niet eenduidig te herleiden";
            else if (joinVeld.equalsIgnoreCase(zetVeld))
                reden = "de nieuwe en de bestaande relatie vallen samen (zelfde referentieveld)";
            // The reference field lives on the MANY side, pointing at the ONE side (§13.3.9.1 cardinaliteit;
            // the convention objectCreatie links by). So both the join (rolC) and the set (rolB) field exist
            // on doel only when doel is the MANY side of BOTH relations. If doel is the "één" side, the field
            // belongs on the anker instead — emit nothing (Drools would reject the setter, or worse link the
            // wrong way) and WARN. This also covers the under-determined non-wederkerig self-relation.
            else if (!(model.naarVeelRol(anker, doel, rolC) && model.naarVeelRol(anker, doel, rolB)))
                reden = "referentieveld hoort aan de meervoudige kant; het doel is hier niet de meervoudige kant";
            else {
                model.feitCreaties.add(new io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie(
                        naam, anker, doel, zetVeld, joinVeld, voorwaarde, geldigheid));
                return;
            }
        }
        model.diagnostics.add(new Diagnostic(Severity.WARNING,
                "feitcreatie wordt nog niet naar IR/DRL omgezet (regel '" + naam + "'): " + reden,
                fc.getStart().getLine()));
    }

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
        else if (rd.feitCreatie() != null) return bouwer.resolveOnderwerp(rd.feitCreatie().onderwerp(0)); // the anker — so its voorwaarde (incl. a kenmerk) resolves
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
        return k == null ? 0 : telwoordWaarde(k.telwoord());
    }

    /** A telwoord (§13.4.14.4): a written één/twee/drie/vier or a NUMBER, else 0 (no/unparseable count). */
    private static int telwoordWaarde(TelwoordContext t) {
        if (t == null) return 0;
        if (t.NUMBER() != null) try { return Integer.parseInt(t.NUMBER().getText()); } catch (NumberFormatException e) { return 0; }
        if (t.EEN_TELWOORD() != null)  return 1;
        if (t.TWEE_TELWOORD() != null) return 2;
        if (t.DRIE_TELWOORD() != null) return 3;
        if (t.VIER_TELWOORD() != null) return 4;
        return 0;
    }

    /** §13.4.8 samengestelde consistentie-criteria → a {@link Conditie.Samengesteld} over the criteria. */
    private Conditie bouwCriteria(ConsistentieKwantificatieContext k, SamengesteldCriteriumOnderdeelContext onderdeel, String bron) {
        List<Conditie> onderdelen = new ArrayList<>();
        for (GenestCriteriumContext g : onderdeel.genestCriterium()) onderdelen.add(bouwCriterium(g));
        return new Conditie.Samengesteld(kwantorVanConsistentie(k), k == null ? 0 : telwoordWaarde(k.telwoord()), onderdelen, bron);
    }

    private Conditie bouwCriterium(GenestCriteriumContext g) {
        if (g.samengesteldCriterium() != null) {
            SamengesteldCriteriumContext c = g.samengesteldCriterium();
            return bouwCriteria(c.consistentieKwantificatie(), c.samengesteldCriteriumOnderdeel(), src(c));
        }
        if (g.elementaireVoorwaarde() != null) return bouwElementair(g.elementaireVoorwaarde());
        return new Conditie.Onopgelost(src(g));
    }

    /** §13.4.14.3 consistentie-kwantor; null (the single "het volgende criterium") is the trivial ALLE. */
    private static Conditie.Kwantor kwantorVanConsistentie(ConsistentieKwantificatieContext k) {
        if (k == null || k.ALLE() != null) return Conditie.Kwantor.ALLE;
        if (k.GEEN_VAN_DE() != null)  return Conditie.Kwantor.GEEN;
        if (k.TEN_MINSTE() != null)   return Conditie.Kwantor.TEN_MINSTE;
        if (k.TEN_HOOGSTE() != null)  return Conditie.Kwantor.TEN_HOOGSTE;
        if (k.PRECIES() != null)      return Conditie.Kwantor.PRECIES;
        return Conditie.Kwantor.ALLE;
    }

    /** Every criterion's left operand is about {@code subject} (a literal/parameter left side is fine):
     *  the rule lowers to one subject pattern, so a criterion over another object type cannot be lifted. */
    private static boolean eenSubject(Conditie c, Objecttype subject) {
        if (c instanceof Conditie.Vergelijking v)
            return !(v.links() instanceof Expr.AttribuutRef a) || a.objecttype() == subject;
        if (c instanceof Conditie.Predicaat p)
            return !(p.operand() instanceof Expr.AttribuutRef a) || a.objecttype() == subject;
        if (c instanceof Conditie.Samengesteld s)
            return s.onderdelen().stream().allMatch(d -> eenSubject(d, subject));
        return true;
    }

    /** The type the criteria are about — the object type of the first criterion's left operand. */
    private static Objecttype subjectVanAssertie(Conditie c) {
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef a) return a.objecttype();
        if (c instanceof Conditie.Predicaat p && p.operand() instanceof Expr.AttribuutRef a) return a.objecttype();
        if (c instanceof Conditie.Samengesteld s)
            for (Conditie d : s.onderdelen()) { Objecttype t = subjectVanAssertie(d); if (t != null) return t; }
        return null;
    }

    private Conditie bouwGenest(GenesteVoorwaardeContext g) {
        if (g.genesteSamengesteldeVoorwaarde() != null) {
            GenesteSamengesteldeVoorwaardeContext c = g.genesteSamengesteldeVoorwaarde();
            return bouwSamengesteld(c.voorwaardeKwantificatie(), c.samengesteldeVoorwaardeOnderdeel(), c);
        }
        if (g.elementaireVoorwaarde() != null) return bouwElementair(g.elementaireVoorwaarde());
        return new Conditie.Onopgelost(src(g));
    }

    /** An §13.4.15 elementaire voorwaarde / consistentie-criterium ("&lt;expressie&gt; &lt;operator&gt;") to a
     *  {@link Conditie}; shared by the samengestelde voorwaarde and the samengestelde consistentieregel. */
    private Conditie bouwElementair(ElementaireVoorwaardeContext c) {
        if (c.expressie() != null && c.nonToplevelOperator() != null)
            return uitNonToplevel(bouwer.bouw(c.expressie()), c.nonToplevelOperator(), src(c));
        if (c.expressie() != null && c.toplevelOperator() != null)
            return uitToplevel(bouwer.bouw(c.expressie()), c.toplevelOperator(), src(c));
        return new Conditie.Onopgelost(src(c));
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
        String staart = stripArtikel(src(naamCtx));
        String kop = subjectKop(lhs, subject);
        if (kop == null) return navigatieKenmerk(lhs, staart, bron); // not the subject -> maybe a navigated kenmerk
        // The greedy onderwerp parse absorbs a multi-word kenmerk's leading words into the operand
        // ("de Meting bereikbaar per" + operator "trein"), so the kenmerk is that absorbed kop plus
        // the operator's kaleNaam; for "hij"/a bare subject the kop is empty and the name is the kaleNaam.
        String kenmerk = kop.isEmpty() ? staart : kop + " " + staart;
        String gevonden = KenmerkMatcher.vind(subject, kenmerk);
        return gevonden != null ? new Conditie.Kenmerk(gevonden, bron) : null;
    }

    /** §8.1.8 navigated: "de &lt;rol&gt; van X is &lt;kenmerk&gt;" — the kenmerk is on the object the rol
     *  reaches, not the subject. Lowered when the rol is a TO-ONE navigation from the subject (so the
     *  property path {@code rol.kenmerk} reads one object); a TO-MANY navigation needs an exists/forall and
     *  stays a marker, as does an unresolvable rol or a kenmerk the navigated type does not declare. */
    private Conditie navigatieKenmerk(Expr lhs, String kenmerkNaam, String bron) {
        if (!(lhs instanceof Expr.AttribuutRef a) || a.objecttype() == null) return null;
        String rol = a.attribuut();
        Objecttype doel = model.rolDoeltypeNaarSubject(a.objecttype(), rol);
        if (doel == null || model.naarVeelRol(a.objecttype(), doel, rol)) return null; // unknown / to-many -> marker
        String gevonden = KenmerkMatcher.vind(doel, kenmerkNaam);
        return gevonden != null ? new Conditie.Kenmerk(gevonden, rol, bron) : null;
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
