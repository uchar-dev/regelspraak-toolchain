package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie;
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
    /** Set (to the role the doel fills) only while re-resolving a §13.4.7 feitcreatie guard against the DOEL:
     *  it makes {@link #subjectKop} also accept "de &lt;rolC&gt;" as denoting the (re-)subject, and REJECT the
     *  bezielde "hij"/bare reference — which in a feitcreatie names the anker, not the doel. null otherwise. */
    private String doelRol = null;

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
                resultaat(naam, versieNaam, rsr.resultaatDeel(), rsr.voorwaardeDeel(), voorwaarde, geldigheid);
                voorwaarden(rsr);   // exhaustive condition type-check (§5), unchanged
            }
        }
        // §13 Beslistabel (Ch.12 decision tables): the grammar parses only a name+versie shell, and the loop
        // above visits ONLY doc.regel(), so a beslistabel would otherwise vanish with NO IR, NO DRL, and NOT
        // EVEN a diagnostic — the project's one true SILENT drop (toka-46/47, spec-main-133-139). Surface it
        // as an honest WARNING until decision-table semantics are implemented, restoring the never-silently-
        // dropped invariant. (The table BODY is not in the grammar yet; the name shell is all we can name.)
        for (BeslistabelContext b : doc.beslistabel())
            model.diagnostics.add(new Diagnostic(Severity.WARNING,
                    "beslistabel '" + DefinitionResolver.src(b.vrijeNaam()) + "' wordt nog niet naar IR/DRL omgezet "
                            + "(beslistabel-semantiek, Ch.12, ontbreekt)", b.getStart().getLine()));
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
                           VoorwaardeDeelContext vd, Conditie voorwaarde, Geldigheid geldigheid) {
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
            feitCreatie(versieNaam, rd.feitCreatie(), vd, voorwaarde, geldigheid);
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

        // The bare "in gelijke delen" / "naar rato van" names the method directly. The bulleted
        // meervoudigCriterium (§9.7.7) wraps that same method with optional modifiers; only the subset
        // WITHOUT group ordering has a single forward-rule form, so a present verdelenOverGroepen keeps the
        // whole verdeling COMPLEX (a marker). The §9.7.3 maximum (naar-rato only), the §9.7.4 afronding (floor)
        // and the §9.7.5 onverdeelde rest ride on the equal-shares / naar-rato spine.
        VerdelenZonderGroepenContext z = v.verdelenZonderGroepen();
        Integer afrDec = null;
        String restAttr = null, maxAttr = null, groepeerAttr = null;
        boolean afnemend = false;
        MeervoudigCriteriumContext mc = v.meervoudigCriterium();
        // The §9.7.3/§9.7.4/§9.7.5 modifiers (maximum, afronding, onverdeelde rest) attach to BOTH the
        // bare-method form and the op-volgorde form (the grammar lets them co-occur with verdelenOverGroepen),
        // so capture them whenever a meervoudigCriterium is present; only the bare-method `z` is branch-specific.
        if (z == null && mc != null) {
            if (mc.verdelenOverGroepen() == null) z = mc.verdelenZonderGroepen();
            if (mc.verdeelAfronding() != null)
                try { afrDec = Integer.valueOf(mc.verdeelAfronding().NUMBER().getText()); }
                catch (NumberFormatException ignore) {}
            if (mc.maximumAanspraak() != null && mc.maximumAanspraak().attribuutMetLidwoord() != null
                    && mc.maximumAanspraak().attribuutMetLidwoord().kaleNaam() != null)
                maxAttr = src(mc.maximumAanspraak().attribuutMetLidwoord().kaleNaam());   // a recipient (doel) attribute
            if (mc.onverdeeldeRest() != null && b.objecttype() != null
                    && bouwer.bouwAttribuut(mc.onverdeeldeRest().attribuutVanOnderwerp()) instanceof Expr.AttribuutRef ra
                    && ra.objecttype() == b.objecttype())   // the remainder lives on the verdeler (the source)
                restAttr = ra.attribuut();
            if ((afrDec != null || maxAttr != null) && restAttr == null && b.objecttype() != null)
                // §9.7.3/§9.7.4: a maximum OR an afronding can leave the plafond under-distributed, so the spec
                // REQUIRES an onverdeelde rest. Without a captured one the leftover is silently lost — surface it.
                // Only when the bron resolves (an unresolved-verdeler fragment markers anyway, so warning is noise).
                model.diagnostics.add(new Diagnostic(Severity.WARNING,
                        "verdeling met afronding/maximum zonder herkenbare onverdeelde rest (§9.7.3/§9.7.4): het "
                        + "restant wordt niet vastgelegd (regel '" + naam + "')", v.getStart().getLine()));
        }

        ResolvedVerdeling.Methode methode = ResolvedVerdeling.Methode.COMPLEX;
        String rato = null;
        if (z != null && z.IN_GELIJKE_DELEN() != null) methode = ResolvedVerdeling.Methode.GELIJKE_DELEN;
        else if (z != null && z.NAAR_RATO_VAN() != null) {
            methode = ResolvedVerdeling.Methode.NAAR_RATO;
            // the BARE weight name (kaleNaam, lidwoord stripped) so it maps straight to a recipient getter,
            // exactly like b.attribuut()/d.attribuut() — "de woonregio factor" -> "woonregio factor".
            rato = z.attribuutMetLidwoord() != null && z.attribuutMetLidwoord().kaleNaam() != null
                    ? src(z.attribuutMetLidwoord().kaleNaam()) : null;
        }
        // §9.7.2 op volgorde van: partition the recipients by an ordering attribute and deplete the pot
        // group-by-group; within a tie-group the share is "in gelijke delen" or "naar rato van <gewicht>"
        // (the same role as `rato`, so reuse it). The whole allocation lowers via rsVerdeelOpVolgorde — it
        // stays COMPLEX (a marker) only when the ordering key or the tie weight is missing or non-getalachtig
        // (the helper compareTo-sorts the key and sums the weight, so both must be numeric to lower).
        VerdelenOverGroepenContext og = mc != null ? mc.verdelenOverGroepen() : null;
        if (og != null) {
            afnemend = og.AFNEMENDE() != null;
            groepeerAttr = og.attribuutMetLidwoord() != null && og.attribuutMetLidwoord().kaleNaam() != null
                    ? src(og.attribuutMetLidwoord().kaleNaam()) : null;
            CriteriumBijGelijkeVolgordeContext crit = og.criteriumBijGelijkeVolgorde();
            if (crit != null && crit.NAAR_RATO_VAN() != null && crit.attribuutMetLidwoord() != null
                    && crit.attribuutMetLidwoord().kaleNaam() != null)
                rato = src(crit.attribuutMetLidwoord().kaleNaam());   // gelijke-delen tie leaves rato null
            Datatype groepType = groepeerAttr != null && d.objecttype() != null ? d.objecttype().attribuut(groepeerAttr) : null;
            Datatype ratoType = rato != null && d.objecttype() != null ? d.objecttype().attribuut(rato) : null;
            boolean tieOk = rato == null || (ratoType != null && ratoType.isGetalachtig());
            if (groepType != null && groepType.isGetalachtig() && tieOk)
                methode = ResolvedVerdeling.Methode.OP_VOLGORDE;
        }
        // §9.7.3: a maximale aanspraak is valid ONLY for a naar-rato split ("niet mogelijk bij gelijke verdeling"
        // — a gelijke verdeling met maximum is voor meerdere uitleg vatbaar), i.e. top-level naar rato OR an
        // op-volgorde with a naar-rato tie, and only when it names a getalachtig recipient attribute (else the
        // cap getter would not compile). Otherwise the verdeling falls back to COMPLEX (a marker).
        if (maxAttr != null) {
            Datatype maxType = d.objecttype() != null ? d.objecttype().attribuut(maxAttr) : null;
            boolean ratoTie = methode == ResolvedVerdeling.Methode.NAAR_RATO
                    || (methode == ResolvedVerdeling.Methode.OP_VOLGORDE && rato != null);
            if (!ratoTie || maxType == null || !maxType.isGetalachtig()) {
                methode = ResolvedVerdeling.Methode.COMPLEX;
                maxAttr = null;
            }
        }
        // §6.1.2 precision, consistent with the ordinary `regel` assignment: a share whose decimal count
        // exceeds the target attribute's must be explicitly rounded (the §9.7.4 afronding). Run it only when
        // the verdeling actually ASSIGNS a share — a COMPLEX marker assigns nothing, and a naar-rato whose
        // weight is not getalachtig is lowered to an honest no-op (so flagging the precision of a share that is
        // never computed would be a false positive). (No-op for all-integer TOKA verdelingen.)
        if (methode != ResolvedVerdeling.Methode.COMPLEX && b.objecttype() != null && d.objecttype() != null) {
            Datatype gewType = (methode == ResolvedVerdeling.Methode.NAAR_RATO
                    || methode == ResolvedVerdeling.Methode.OP_VOLGORDE) && rato != null
                    ? d.objecttype().attribuut(rato) : null;   // op-volgorde naar-rato tie weighs like NAAR_RATO
            boolean lowert = methode == ResolvedVerdeling.Methode.GELIJKE_DELEN
                    || methode == ResolvedVerdeling.Methode.OP_VOLGORDE     // gated above to always lower
                    || (gewType != null && gewType.isGetalachtig());   // mirror the transpiler's lowering gate
            if (lowert) {
                String fout = Typering.verdeelToekenning(d.objecttype().attribuut(d.attribuut()),
                        b.objecttype().attribuut(b.attribuut()), gewType, afrDec);
                if (fout != null)
                    model.diagnostics.add(new Diagnostic(Severity.ERROR, "verdeling: " + fout, v.getStart().getLine()));
            }
        }
        model.verdelingen.add(new ResolvedVerdeling(naam, b.objecttype(), b.attribuut(),
                d.objecttype(), d.attribuut(), methode, rato, maxAttr, afrDec, restAttr,
                groepeerAttr, afnemend, voorwaarde, geldigheid));
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
    private void feitCreatie(String naam, FeitCreatieContext fc, VoorwaardeDeelContext vd, Conditie voorwaarde, Geldigheid geldigheid) {
        String rolB = stripArtikel(src(fc.kaleNaam(0)));               // LHS role — the relation being created
        Objecttype anker = bouwer.resolveOnderwerp(fc.onderwerp(0));   // the "one" side / iterated subject
        KaleNaamContext rhs = fc.kaleNaam(1);
        java.util.List<KaleNaamDeelContext> delen = rhs.kaleNaamDeel();
        String rolC = stripArtikel(src(delen.get(0)));                 // RHS role — the FINAL hop onto the doel
        // onderwerpD must be a back-reference to the iterated anker ITSELF, not a different object of the
        // same type reached by a role: "de vlucht" lifts, but "de aansluitvlucht" must WARN, not silently
        // reuse the anker's own join. It denotes the anker when its written noun matches the ANKER's own
        // written noun (the anker may itself be named by a role — "een vastgestelde contingent treinmiles" —
        // so a type-name-only test is too strict); the strict type match is kept as an additional path.
        String ankerNoun = bouwer.primaireNaamVanOnderwerp(fc.onderwerp(0));
        String terugNoun = bouwer.primaireNaamVanOnderwerp(fc.onderwerp(1));
        boolean directeAnker = anker != null && terugNoun != null
                && ((ankerNoun != null && stripArtikel(terugNoun).equalsIgnoreCase(stripArtikel(ankerNoun)))
                    || model.objecttypeOpNaam(stripArtikel(terugNoun)) == anker);

        String reden = null;
        if (anker == null) reden = "anker-objecttype niet herleidbaar";
        else if (!directeAnker) reden = "doelketen verwijst niet rechtstreeks naar de anker (geen back-reference)";
        else {
            // Walk the §9.4 navigation chain anker -> … -> doel-parent. The written kaleNaamDeel are outside-in
            // (rolC at index 0, the innermost hop last), so the hops from the anker are the segments in REVERSE
            // (index size-1 down to 1); segment 0 (rolC) is the final hop onto the doel. The reference field
            // lives on the many side: a to-many hop keeps it on the child (joined `child(veld == $prev)`), a
            // to-one hop on the parent (joined `child(this == $prev.veld)`) — the side objectcreatie set it on.
            java.util.List<ResolvedFeitCreatie.Tussenstap> stappen = new ArrayList<>();
            Objecttype cur = anker;
            for (int i = delen.size() - 1; i >= 1; i--) {
                String role = stripArtikel(src(delen.get(i)));
                Objecttype next = model.rolDoeltypeNaarSubject(cur, role);
                if (next == null) { reden = "tussenstap-rol '" + role + "' niet eenduidig te herleiden"; break; }
                ResolvedFeitCreatie.Tussenstap stap = tussenstap(cur, next, role);
                if (stap == null) { reden = "referentieveld-zijde voor tussenstap '" + role + "' niet af te leiden"; break; }
                stappen.add(stap);
                cur = next;
            }
            if (reden == null) {
                Objecttype doel = model.rolDoeltypeNaarSubject(cur, rolC);
                String joinVeld = model.verbindendeRolViaElementnaam(cur, rolC); // existing rolC relation's field on doel
                String zetVeld  = model.verbindendeRolViaElementnaam(anker, rolB); // the NEW relation's field on doel -> anker
                if (doel == null || joinVeld == null || zetVeld == null)
                    reden = "rol of feittype niet eenduidig te herleiden";
                else if (joinVeld.equalsIgnoreCase(zetVeld))
                    reden = "de nieuwe en de bestaande relatie vallen samen (zelfde referentieveld)";
                // The reference field lives on the MANY side, pointing at the ONE side (§13.3.9.1 cardinaliteit;
                // the convention objectCreatie links by). Both the final join (rolC, from the last hop) and the
                // set (rolB, from the anker) field exist on doel only when doel is the MANY side of BOTH — else
                // the field belongs on the other side and a setter would be rejected or link the wrong way. WARN.
                else if (!(model.naarVeelRol(cur, doel, rolC) && model.naarVeelRol(anker, doel, rolB)))
                    reden = "referentieveld hoort aan de meervoudige kant; het doel is hier niet de meervoudige kant";
                else {
                    Conditie effectief = doelVoorwaarde(voorwaarde, vd, anker, doel, rolC);
                    model.feitCreaties.add(new io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie(
                            naam, anker, doel, zetVeld, joinVeld, stappen, effectief, geldigheid));
                    return;
                }
            }
        }
        model.diagnostics.add(new Diagnostic(Severity.WARNING,
                "feitcreatie wordt nog niet naar IR/DRL omgezet (regel '" + naam + "'): " + reden,
                fc.getStart().getLine()));
    }

    /** One §9.4 chain hop cur -&gt; next via {@code role}: which side holds the reference field, and the join
     *  form, or null when undeterminable. The field lives on the MANY side (§13.3.9.1). A TO-MANY hop keeps it
     *  on the CHILD (next) -&gt; {@code next(veld == $prev)}. A TO-ONE hop keeps it on the PARENT (cur) only when
     *  cur is genuinely the many side (the reverse role is meervoudig) or an objectcreatie placed the link on
     *  cur -&gt; {@code next(this == $prev.veld)}; it is on the CHILD when an objectcreatie placed it on next. A
     *  bare one-to-one with NEITHER signal is not derivable from the model — return null (honest WARN), never a
     *  guessed side that may emit an uncompilable wrong-side join. */
    private ResolvedFeitCreatie.Tussenstap tussenstap(Objecttype cur, Objecttype next, String role) {
        if (model.naarVeelRol(cur, next, role)) {                            // to-many: field on the child
            String veld = model.verbindendeRolViaElementnaam(cur, role);
            return veld == null ? null : new ResolvedFeitCreatie.Tussenstap(next, veld, false);
        }
        String curRol = model.verbindendeRolViaElementnaam(cur, role);       // cur's own role in this feittype
        boolean curIsVeel = curRol != null && model.naarVeelRol(next, cur, curRol); // reverse to-many -> cur is many
        if (curIsVeel || aangemaaktNaar(cur, next)) {                        // field on cur (parent)
            String veld = model.verbindendeRol(cur, next);
            return veld == null ? null : new ResolvedFeitCreatie.Tussenstap(next, veld, true);
        }
        if (aangemaaktNaar(next, cur)) {                                     // 1-1: objectcreatie put it on next
            String veld = model.verbindendeRolViaElementnaam(cur, role);
            return veld == null ? null : new ResolvedFeitCreatie.Tussenstap(next, veld, false);
        }
        return null;                                                        // bare 1-1: side not derivable
    }

    /** True when an objectcreatie creates {@code houder} linked to {@code doelwit} ("Een doelwit heeft een
     *  houder"), so — by the convention objectCreatie sets the reference on the new object — {@code houder}
     *  holds the reference field to {@code doelwit}. (Source-order: an objectcreatie earlier in the document.) */
    private boolean aangemaaktNaar(Objecttype houder, Objecttype doelwit) {
        return model.objectCreaties.stream().anyMatch(oc -> oc.nieuwType() == houder && oc.subject() == doelwit);
    }

    /** §13.4.7 voorwaarde hosting. A feitcreatie guard is built with the ANKER as subject; a guard that
     *  names a kenmerk of the DOEL ("indien de passagier vip is") is then an unresolved marker, because the
     *  single-subject §8.1.8 path refuses a to-many navigation from the anker (it would need a separate
     *  pattern). The feitcreatie HAS that pattern ($doel), so re-resolve the guard with the doel as subject;
     *  if it is then a single BARE doel-kenmerk, tag it with {@code eigenaar = doel} so the transpiler hosts
     *  it on the $doel pattern (and never the anker — even under a same-named anker kenmerk). Restricted to
     *  the simple single-kenmerk guard and to {@code doel != anker}: an anker guard that already resolved, a
     *  compound, a self-relation, or a still-unresolved guard is returned unchanged — an un-lowerable guard
     *  stays an honest inert no-op, never a wrong link. */
    private Conditie doelVoorwaarde(Conditie ankerVoorwaarde, VoorwaardeDeelContext vd, Objecttype anker, Objecttype doel, String rolC) {
        if (!(ankerVoorwaarde instanceof Conditie.Onopgelost) || vd == null || doel == anker) return ankerVoorwaarde;
        Conditie doelV;
        int diagSnapshot = model.diagnostics.size();
        doelRol = rolC;                                    // accept "de <rolC>" as the doel; reject "hij"/anker
        try {
            bouwer.setSubject(doel);
            doelV = bouwVoorwaarde(vd);
        } finally {
            doelRol = null;
            bouwer.setSubject(anker);                       // restore: the anker is the iterated subject
            // This second build is SPECULATIVE — the anker build and the separate voorwaarden() type-check own
            // the diagnostics; drop any this pass appended so a flawed guard is never reported twice.
            model.diagnostics.subList(diagSnapshot, model.diagnostics.size()).clear();
        }
        if (doelV instanceof Conditie.Kenmerk k && k.navigatie() == null && k.eigenaar() == null)
            return new Conditie.Kenmerk(k.kenmerk(), null, k.negatie(), doel, k.bron());
        return ankerVoorwaarde;
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
        Conditie kenmerk = kenmerkPredicaat(lhs, op.kaleNaam(), op.EEN() != null,
                op.HEEFT() != null || op.HEBBEN() != null, bron); // EEN? kaleNaam (is|heeft): §8.1.8
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
        Conditie kenmerk = kenmerkPredicaat(lhs, op.kaleNaam(), op.EEN() != null,
                op.HEEFT() != null || op.HEBBEN() != null, bron); // (is|heeft) een? kaleNaam: §8.1.8
        return kenmerk != null ? kenmerk : new Conditie.Onopgelost(bron); // else objecttype/navigation membership
    }

    /** §8.1.7/§8.1.8: "&lt;subject&gt; is/heeft (een)? &lt;naam&gt;" -> a {@link Conditie.Kenmerk} (the
     *  §8.1.8 kenmerkcheck) or a {@link Conditie.Rollidmaatschap} (the §8.1.7 rolcheck), when the operand
     *  denotes the rule subject (or a to-one navigation from it). WITHOUT "een" the name is the subject's
     *  kenmerk (own or navigated). WITH "een" the grammar conflates a kenmerknaam and a rolnaam, so the
     *  resolver disambiguates by lookup, kenmerk-first (Tabel 16): a ZELFSTANDIG kenmerk (§8.1.8.3 "is een
     *  &lt;k&gt;") binds first, otherwise a role the subject fills is the §8.1.7 rolcheck. A bijvoeglijk /
     *  bezittelijk kenmerk is NOT written with "een", so the EEN-form never binds it. A name that resolves to
     *  neither stays Onopgelost (an honest marker), never a silently-true gate. */
    private Conditie kenmerkPredicaat(Expr lhs, KaleNaamContext naamCtx, boolean metEen, boolean heeftWerkwoord, String bron) {
        Objecttype subject = bouwer.subject();
        if (naamCtx == null || subject == null) return null;
        String staart = stripArtikel(src(naamCtx));
        String kop = subjectKop(lhs, subject);
        // not the subject itself -> a navigated kenmerk ("zijn reis is duurzaam"), in the non-EEN form only.
        // The EEN-form navigated kenmerkcheck and a navigated role membership are out of scope (the former
        // needs the kenmerk soort to avoid binding a bijvoeglijk kenmerk to the "een" form, §8.1.8.1) -> carded.
        if (kop == null) return metEen ? null : navigatieKenmerk(lhs, staart, heeftWerkwoord, bron);
        // The greedy onderwerp parse absorbs a multi-word kenmerk's leading words into the operand
        // ("de Meting bereikbaar per" + operator "trein"), so the kenmerk is that absorbed kop plus
        // the operator's kaleNaam; for "hij"/a bare subject the kop is empty and the name is the kaleNaam.
        String naam = kop.isEmpty() ? staart : kop + " " + staart;
        if (!metEen) return kenmerkConditie(subject, naam, null, heeftWerkwoord, bron);   // §8.1.8 is/heeft <kenmerk>
        // EEN-form: the grammar conflates a kenmerknaam and a rolnaam after "een", so disambiguate by lookup,
        // kenmerk-first (Tabel 16). §8.1.8.3: "is een <zelfstandig kenmerk>" binds a kenmerk ONLY when it is
        // ZELFSTANDIG (a bijvoeglijk kenmerk is written "is <k>", a bezittelijk one "heeft het <k>", so neither
        // takes "een"). Otherwise it is the §8.1.7 rolcheck "is een <rolnaam>".
        Conditie k = zelfstandigKenmerk(subject, naam, bron);
        return k != null ? k : rolLidmaatschap(subject, naam, bron);
    }

    /** §8.1.8.3 "&lt;subject&gt; is een &lt;zelfstandig kenmerk&gt;" -> a {@link Conditie.Kenmerk} ("k == true"),
     *  but ONLY for a {@link Objecttype.KenmerkSoort#ZELFSTANDIG} kenmerk: a bijvoeglijk kenmerk ("is &lt;k&gt;")
     *  and a bezittelijk one ("heeft het &lt;k&gt;") are not written with "een", so the EEN-form must not bind
     *  them (that mis-binding was the reason the EEN-form kenmerkcheck was deferred). Positive only — the "is
     *  geen" negation lexes into the kaleNaam with no "een" and reaches the non-EEN path (carded). Null when no
     *  ZELFSTANDIG kenmerk of {@code subject} bears the name, so the caller falls back to the §8.1.7 rolcheck. */
    private Conditie zelfstandigKenmerk(Objecttype subject, String naam, String bron) {
        String g = KenmerkMatcher.vind(subject, naam);
        return g != null && subject.kenmerkSoort(g) == Objecttype.KenmerkSoort.ZELFSTANDIG
                ? new Conditie.Kenmerk(g, bron) : null;
    }

    /** §8.1.7 rolcheck: "&lt;subject&gt; is een &lt;rolnaam&gt;" — true when the subject fills that role.
     *  Resolves to a {@link Conditie.Rollidmaatschap} carrying the OTHER end of the feittype (so the
     *  transpiler can null-test the subject's reference to it). Null when no role the subject fills bears
     *  that name (-> Onopgelost marker, never a wrong gate). The negated "is geen" form is carded — its
     *  "geen" parses into the kaleNaam with no "een", so it does not reach this EEN-form path. */
    private Conditie rolLidmaatschap(Objecttype subject, String naam, String bron) {
        Objecttype ander = model.rolSiblingType(subject, naam);
        return ander != null ? new Conditie.Rollidmaatschap(naam, ander, bron) : null;
    }

    /** Resolve {@code kenmerk} on {@code type} into a {@link Conditie.Kenmerk}, honouring a leading "niet"
     *  or "geen" as NEGATION — but each only after the WHOLE name fails, so a declared "niet-…" kenmerk
     *  still binds positively. {@code navigatie} is the to-one navigation role (null for the subject's own).
     *  {@code heeftWerkwoord} is true when the predicate verb is hebben (§8.1.8: see the "geen" branch).
     *  Null when neither the whole name nor a de-negated form is a declared kenmerk of {@code type}. */
    private static Conditie kenmerkConditie(Objecttype type, String kenmerk, String navigatie, boolean heeftWerkwoord, String bron) {
        // Match the written name VERBATIM first. A bijvoeglijk kenmerk may be declared WITH a leading article
        // as part of its name ("is de minimis kenmerk" -> stored "de minimis"): its grammar rule has no
        // separate bepaaldLidwoord? and naamWoordLower admits de/het, so the article is part of the stored
        // name — the false-invariant the first cut tripped on (a blind strip dropped it to a wrong/no match).
        Conditie c = bindKenmerk(type, kenmerk, navigatie, heeftWerkwoord, bron);
        if (c != null) return c;
        // Only if the verbatim name binds NOTHING, retry with a leading de/het/een stripped — recovering the
        // bezittelijk/noch-noch case where the article is the kenmerk's OWN (stored article-stripped) and the
        // greedy onderwerp parse left it on the reconstructed name ("het recht op duurzaamheidskorting").
        // Verbatim-first guarantees a kenmerk whose name really begins with an article binds itself, never a
        // shorter sibling.
        String gestript = stripArtikel(kenmerk);
        return gestript.equals(kenmerk) ? null : bindKenmerk(type, gestript, navigatie, heeftWerkwoord, bron);
    }

    /** Bind one candidate name to a declared kenmerk of {@code type}, honouring a leading "niet" or, under
     *  the hebben-verb, "geen" as NEGATION — but each only after the WHOLE name fails, so a kenmerk declared
     *  starting with niet/geen still binds positively (never silently inverted). Null when nothing binds. */
    private static Conditie bindKenmerk(Objecttype type, String naam, String navigatie, boolean heeftWerkwoord, String bron) {
        String g = KenmerkMatcher.vind(type, naam);
        if (g != null) return new Conditie.Kenmerk(g, navigatie, false, bron);
        // §8.1.8.1 bijvoeglijk negation "is niet <k>" -> "== false" (verb-agnostic).
        if (naam.regionMatches(true, 0, "niet ", 0, 5)) {
            g = KenmerkMatcher.vind(type, naam.substring(5).trim());
            if (g != null) return new Conditie.Kenmerk(g, navigatie, true, bron);
        }
        // §8.1.8.2 bezittelijk negation "heeft geen <k>" -> "== false". Gated on the hebben-verb: the
        // noch-noch "is geen <k>" (§8.1.8.3) is the negation of "is een <k>", itself left a marker for the
        // §13.4.15.44/.45 objecttype/role-membership ambiguity (p28) — lowering only its negation would gate
        // one of a complementary pair while the other stays an inert no-op. "geen" carries no token (only
        // GEEN_VAN_DE), so it lexes as LOWER_ID and rides inside the kaleNaam into this reconstructed name.
        if (heeftWerkwoord && naam.regionMatches(true, 0, "geen ", 0, 5)) {
            g = KenmerkMatcher.vind(type, naam.substring(5).trim());
            if (g != null) return new Conditie.Kenmerk(g, navigatie, true, bron);
        }
        return null;
    }

    /** §8.1.8 navigated: "de &lt;rol&gt; van X is &lt;kenmerk&gt;" — the kenmerk is on the object the rol
     *  reaches, not the subject. Lowered when the rol is a TO-ONE navigation from the subject (so the
     *  property path {@code rol.kenmerk} reads one object); a TO-MANY navigation needs an exists/forall and
     *  stays a marker, as does an unresolvable rol or a kenmerk the navigated type does not declare. The rol
     *  is normalised to its declared name (a plural "de passagiers" is the same role as "de passagier"), so
     *  the to-many test and the emitted reference field are the canonical one. */
    private Conditie navigatieKenmerk(Expr lhs, String staart, boolean heeftWerkwoord, String bron) {
        if (!(lhs instanceof Expr.AttribuutRef a) || a.objecttype() == null) return null;
        Objecttype subject = a.objecttype();                                       // the onderwerp the navigation is from
        Objecttype doel = model.rolDoeltypeNaarSubject(subject, a.attribuut());
        if (doel == null) return null;
        String rol = model.verbindendeRolViaNaam(subject, doel, a.attribuut());     // canonical role / reference field
        if (rol == null) rol = a.attribuut();
        if (model.naarVeelRol(subject, doel, rol)) return null;                     // to-many -> marker (needs exists/forall)
        // The greedy onderwerp parse can absorb a multi-word kenmerk's leading words — or a "niet" — into
        // the operand AFTER the navigated onderwerp. Reconstruct the full kenmerk = that absorbed tail + the
        // operator's kaleNaam, so a multi-word kenmerk binds and an unsupported "niet …" simply fails to
        // match the declared kenmerk and stays a marker (never a silently-inverted positive constraint).
        String tail = navigatieStaart(a);
        String kenmerk = tail.isEmpty() ? staart : tail + " " + staart;
        return kenmerkConditie(doel, kenmerk, rol, heeftWerkwoord, bron);
    }

    /** The words the greedy operand parse absorbed AFTER the navigated "de &lt;rol&gt; van &lt;onderwerp&gt;"
     *  (the leading words of a multi-word kenmerk, or a "niet") — empty for a clean navigation. Found as the
     *  operand text following the onderwerp's object-type name. */
    private static String navigatieStaart(Expr.AttribuutRef a) {
        String b = a.bron() == null ? "" : a.bron();
        // The onderwerp type name as a WHOLE word (so a kenmerk word merely containing it as a substring
        // does not mis-split); take the text after its last occurrence.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b" + java.util.regex.Pattern.quote(a.objecttype().naam) + "\\b").matcher(b);
        int eind = -1;
        while (m.find()) eind = m.end();
        return eind < 0 ? "" : b.substring(eind).trim();
    }

    /** When the condition operand denotes the rule subject, the part of a multi-word kenmerk the
     *  greedy onderwerp parse absorbed into the operand (empty for the bezielde "hij" or a bare
     *  subject reference); null when the operand is not the subject. A navigation ("de partner van
     *  …") names a different filler, so it is excluded — its kenmerk would be on another object. */
    private String subjectKop(Expr lhs, Objecttype subject) {
        // In a feitcreatie doel re-resolution (doelRol != null) the bezielde "hij"/bare reference names the
        // ANKER, not the doel (§13.4.7: the doel is written with a definite article, "de <rolC>"), so it must
        // NOT latch onto the doel — reject it. Outside that mode "hij" denotes the single subject as before.
        if (lhs instanceof Expr.Constante c)
            return (doelRol == null && c.naam().equalsIgnoreCase("hij")) ? "" : null;
        String b = lhs.bron() == null ? "" : stripArtikel(lhs.bron());
        if (b.equalsIgnoreCase(subject.naam)) return "";                              // "de Meting"
        if (b.regionMatches(true, 0, subject.naam + " ", 0, subject.naam.length() + 1)) // "de Meting bereikbaar per"
            return b.substring(subject.naam.length()).trim();
        // The doel is named by the ROLE it fills ("de passagier"), which need not equal its type name
        // ("Natuurlijk persoon"); accept that role too, with the same multi-word-kenmerk kop reconstruction.
        if (doelRol != null) {
            if (b.equalsIgnoreCase(doelRol)) return "";
            if (b.regionMatches(true, 0, doelRol + " ", 0, doelRol.length() + 1))
                return b.substring(doelRol.length()).trim();
        }
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

    // §13.4.15: the toplevel/non-toplevel split is word-order, not structure, so BOTH operator families
    // are admitted in BOTH voorwaarde positions (grammar: toplevelElementaireVoorwaarde and
    // elementaireVoorwaarde each allow `toplevelOperator | nonToplevelOperator`). The type-check must
    // therefore dispatch on whichever operator is actually present, not on the voorwaarde's name —
    // otherwise an operator in the "other" position (e.g. the verb-leading "voldoet aan de elfproef" under
    // INDIEN) is silently unchecked, letting an ill-typed predicate through (p34).
    private void checkTopVoorwaarde(ToplevelElementaireVoorwaardeContext c) {
        if (c.expressie() == null) return;
        Datatype lhs = infer(c.expressie());
        int line = c.getStart().getLine();
        checkToplevelOp(lhs, c.toplevelOperator(), line);
        checkNonToplevelOp(lhs, c.nonToplevelOperator(), line);
    }

    private void checkElemVoorwaarde(ElementaireVoorwaardeContext c) {
        if (c.expressie() == null) return;
        Datatype lhs = infer(c.expressie());
        int line = c.getStart().getLine();
        checkNonToplevelOp(lhs, c.nonToplevelOperator(), line);
        checkToplevelOp(lhs, c.toplevelOperator(), line);
    }

    /** Type-check a verb-split / trailing operator (vergelijking, elfproef, numeriek-met-exact). */
    private void checkToplevelOp(Datatype lhs, ToplevelOperatorContext op, int line) {
        if (op == null) return;
        if (op.vergelijkingsTekenGesplitst() != null && op.expressie() != null)
            meldVergelijk(cmpOf(op.vergelijkingsTekenGesplitst()), lhs, infer(op.expressie()), line);
        else if (op.AAN_DE_ELFPROEF() != null)
            meldPredicaat(Typering.UnairePred.ELFPROEF, lhs, line);
        else if (op.NUMERIEK_IS_MET_EXACT() != null || op.NUMERIEK_ZIJN_MET_EXACT() != null)
            meldPredicaat(Typering.UnairePred.NUMERIEK_MET_EXACT, lhs, line);
    }

    /** Type-check a verb-leading operator (vergelijking, elfproef, numeriek-met-exact). */
    private void checkNonToplevelOp(Datatype lhs, NonToplevelOperatorContext op, int line) {
        if (op == null) return;
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
