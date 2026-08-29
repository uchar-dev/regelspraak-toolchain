package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.*;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.*;
import io.github.yhavinga.regelspraak.parser.RegelSpraak;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the typed {@link RegelSpraakModel} from a parsed GegevensSpraak document
 * (§13.3). Two passes: domains and unit systems first (they are referenced by
 * attributes/parameters and may be declared after their use), then object types,
 * parameters, fact types, day types, and rule shells.
 *
 * Multi-word names — which the parser deliberately leaves as token runs — are
 * reconstructed here from the original source slice, with whitespace normalised.
 * This is the post-parse name resolution CLAUDE.md assigns to the resolver.
 */
public final class DefinitionResolver {
    private final RegelSpraakModel model = new RegelSpraakModel();

    public RegelSpraakModel build(RegelSpraakDocumentContext doc) {
        for (DefinitieContext d : definities(doc)) {        // pass 1: domains + units + timelines
            if (d.domeinDefinition() != null) domein(d.domeinDefinition());
            if (d.eenheidsysteemDefinition() != null) eenheidsysteem(d.eenheidsysteemDefinition());
            // a named timeline must be registered before pass 2, so an attribute's "voor elke <tijdlijn>"
            // reference can adopt its granularity regardless of declaration order (§13.3.6.9). It reads only
            // its own tokens, so it has no dependency on objecttypes/feittypes processed in pass 2.
            if (d.tijdlijnDefinitie() != null) tijdlijn(d.tijdlijnDefinitie());
        }
        for (DefinitieContext d : definities(doc)) {        // pass 2: everything that references them
            if (d.objectTypeDefinition() != null) objecttype(d.objectTypeDefinition());
            if (d.objectTypeExtensie() != null) extensie(d.objectTypeExtensie());
            if (d.parameterDefinition() != null) parameter(d.parameterDefinition());
            if (d.feitTypeDefinition() != null) feittype(d.feitTypeDefinition());
            if (d.dagsoortDefinition() != null) dagsoort(d.dagsoortDefinition());
            if (d.dimensieDefinition() != null) dimensie(d.dimensieDefinition());
        }
        for (RegelContext r : alleRegels(doc)) {
            model.regels.add(new Regel(src(r.regelNaam()), "regel"));
        }
        // §4 (LADDER-EVOLUTIE) the ordered 'Uitzondering op' exception blocks carry their own name too — a Regel
        // stub in document position, so each ladder-producing rule appears in `regels` exactly as the flat rules
        // and the group members do (their RESOLVED form lives in the ladder, not resolvedRegels).
        for (UitzonderingRegelContext u : alleUitzonderingen(doc)) {
            model.regels.add(new Regel(src(u.regelNaam()), "regel"));
        }
        return model;
    }

    /** Every §13.4.2 regel of the document — the top-level ones AND the members of a §9.10 {@link RegelGroepContext}
     *  (a DOCUMENTED EXTENSION; see docs/grammar-divergences.md). A grouped rule resolves EXACTLY as an ungrouped
     *  one — the group is namespacing only — so the whole rule pipeline walks this flattened list. Order is
     *  preserved (a group's members appear where the group's header sits). */
    static List<RegelContext> alleRegels(RegelSpraakDocumentContext doc) {
        List<RegelContext> uit = new ArrayList<>();
        for (ParseTree kind : doc.children == null ? List.<ParseTree>of() : doc.children) {
            if (kind instanceof RegelContext r) {
                uit.add(r);
            } else if (kind instanceof RegelGroepContext g) {
                for (GroepLidContext lid : g.groepLid())
                    if (lid.regel() != null) uit.add(lid.regel());
            } else if (kind instanceof UitzonderingGroepContext ug) {
                // §4 (LADDER-EVOLUTIE) an unordered exception group's members are ordinary regels: they resolve
                // through the WHOLE rule pipeline (type-checks included), and the ladder pass then LIFTS each into a
                // sibling declared-unordered DefaultLadder (removing it from resolvedRegels — the ladder is the writer).
                uit.addAll(ug.regel());
            }
        }
        return uit;
    }

    /** §4 (LADDER-EVOLUTIE) every ordered 'Uitzondering op &lt;basis&gt;: &lt;naam&gt;' block of the document, in
     *  document order. Each resolves like a §13.4.2 regel (its regelNaam + regelVersie+ body) and is then lifted,
     *  with its named base rule, into one {@link io.github.yhavinga.regelspraak.ir.DefaultLadder}. */
    static List<UitzonderingRegelContext> alleUitzonderingen(RegelSpraakDocumentContext doc) {
        List<UitzonderingRegelContext> uit = new ArrayList<>();
        for (ParseTree kind : doc.children == null ? List.<ParseTree>of() : doc.children)
            if (kind instanceof UitzonderingRegelContext u) uit.add(u);
        return uit;
    }

    /** §4 (LADDER-EVOLUTIE) every unordered exception group of the document, in document order. */
    static List<UitzonderingGroepContext> alleUitzonderingGroepen(RegelSpraakDocumentContext doc) {
        List<UitzonderingGroepContext> uit = new ArrayList<>();
        for (ParseTree kind : doc.children == null ? List.<ParseTree>of() : doc.children)
            if (kind instanceof UitzonderingGroepContext g) uit.add(g);
        return uit;
    }

    /** Every §12 beslistabel of the document — top-level ones AND the members of a §9.10 {@link RegelGroepContext}
     *  (a grouped beslistabel is namespacing only; it desugars to grouped regels exactly like an ungrouped one). */
    static List<BeslistabelContext> alleBeslistabellen(RegelSpraakDocumentContext doc) {
        List<BeslistabelContext> uit = new ArrayList<>();
        for (ParseTree kind : doc.children == null ? List.<ParseTree>of() : doc.children) {
            if (kind instanceof BeslistabelContext b) uit.add(b);
            else if (kind instanceof RegelGroepContext g)
                for (GroepLidContext lid : g.groepLid())
                    if (lid.beslistabel() != null) uit.add(lid.beslistabel());
        }
        return uit;
    }

    private static List<DefinitieContext> definities(RegelSpraakDocumentContext doc) {
        return doc.definitie();
    }

    // ---------------- §13.3.1 object types ----------------
    private void objecttype(ObjectTypeDefinitionContext ctx) {
        Objecttype ot = model.objecttypen.computeIfAbsent(src(ctx.objectTypeNaam()), Objecttype::new);
        if (ctx.BEZIELD() != null) ot.bezield = true;
        if (ctx.meervoudsvorm() != null) ot.meervoud = src(ctx.meervoudsvorm()); // §13.2.29, for §8.1.6 plural refs
        for (ObjectTypeMemberContext m : ctx.objectTypeMember()) member(ot, m);
    }

    private void extensie(ObjectTypeExtensieContext ctx) {
        // an extensie adds members to an existing (or new) object type — merge, do not redeclare
        Objecttype ot = model.objecttypen.computeIfAbsent(src(ctx.objectTypeNaam()), Objecttype::new);
        for (ObjectTypeMemberContext m : ctx.objectTypeMember()) member(ot, m);
    }

    private void member(Objecttype ot, ObjectTypeMemberContext m) {
        if (m.attribuutSpecificatie() != null) {
            AttribuutSpecificatieContext a = m.attribuutSpecificatie();
            String naam = naam(a.lowerNaam());
            meldHoofdletterBotsing(ot, naam, ot.attributen.keySet(), a.getStart().getLine());
            ot.attributen.put(naam, datatypeVan(a.datatype(), a.domeinNaam()));
            if (a.meervoudsvorm() != null) ot.attribuutMeervoud.put(naam, src(a.meervoudsvorm())); // §3.3, for §8.1.6
            // §13.3.7 "gedimensioneerd met <dimensie> (en <dimensie>)*": record the attribute's dimensions
            // in declared order so a reference / aggregation can range over their label sets (§3.6).
            if (!a.dimensieNaam().isEmpty())
                ot.attribuutDimensies.put(naam, a.dimensieNaam().stream().map(dn -> src(dn)).toList());
            // §13.3.6 a temporal attribute carries a tijdlijn ("voor elk jaar") — record its granularity so
            // the Phase-7 value model can later lower the periods; absent for an ordinary single-value attribute.
            Granulariteit g = granulariteitVan(a.tijdlijn());
            if (g != null) {
                ot.attribuutGranulariteit.put(naam, g);
                int aantal = aantalVan(a.tijdlijn());   // §13.3.6.7 the "5" in "voor elke 5 maanden"; 1 otherwise
                if (aantal != 1) ot.attribuutTijdlijnAantal.put(naam, aantal);
                // §13.3.6.9 the NAMED form: keep the tijdlijn name so §9.9 can route a variable
                // startpunt to its anchor (the inline "voor elke maand" forms record none).
                if (a.tijdlijn().ELKE() != null && a.tijdlijn().tijdlijnNaam() != null)
                    ot.attribuutTijdlijnNaam.put(naam, normaliseerTijdlijnNaam(src(a.tijdlijn().tijdlijnNaam())));
            }
        } else if (m.kenmerkSpecificatie() != null) {
            String naam = kenmerkNaam(m.kenmerkSpecificatie());
            meldHoofdletterBotsing(ot, naam, ot.kenmerken, m.getStart().getLine());
            ot.kenmerken.add(naam);
            ot.kenmerkSoorten.put(naam, kenmerkSoortVan(m.kenmerkSpecificatie()));   // §3.5 drives the §8.1.8 predicate form
            TijdlijnContext kt = tijdlijnVan(m.kenmerkSpecificatie());
            Granulariteit gk = granulariteitVan(kt);
            if (gk != null) {
                ot.kenmerkGranulariteit.put(naam, gk);
                int aantal = aantalVan(kt);
                if (aantal != 1) ot.kenmerkTijdlijnAantal.put(naam, aantal);
            }
        }
        // koptekst carries no model information
    }

    /** §13.2.30: a naam is NOT hoofdletter-onderscheidend, so two members of one object type OF
     *  THE SAME KIND that differ ONLY in case are the same name declared twice — ambiguous (a
     *  third casing would bind whichever was declared first, see {@link Objecttype#attribuut}).
     *  Warn at registration. Scoped to one kind: an attribute and a kenmerk MAY legitimately
     *  share a name — a
     *  kenmerk carries no datatype and never enters {@link Objecttype#attribuut}, so there is no
     *  cross-kind ambiguity. An exact-case redeclaration is left to the collection. */
    private void meldHoofdletterBotsing(Objecttype ot, String naam, java.util.Collection<String> bestaande, int regel) {
        if (bestaande.contains(naam)) return; // exact duplicate of the same kind: not a case botsing
        if (bestaande.stream().anyMatch(k -> k.equalsIgnoreCase(naam)))
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                    "lid '" + naam + "' van objecttype '" + ot.naam + "' verschilt alleen in hoofdletters van een eerder lid; "
                            + "namen zijn niet hoofdletter-onderscheidend (§13.2.30), dus de verwijzing is dubbelzinnig", regel));
    }

    private String kenmerkNaam(KenmerkSpecificatieContext k) {
        if (k instanceof BijvoeglijkKenmerkContext b) return naam(b.lowerNaam());
        if (k instanceof NaamwoordKenmerkContext n) return naam(n.lowerNaam());
        return src(k);
    }

    /** §3.5 kenmerk soort from the declaration: the optional {@code (bijvoeglijk)}/{@code (bezittelijk)} marker
     *  classifies it; an UNTAGGED kenmerk is ZELFSTANDIG (the "is een &lt;k&gt;" noch-noch form — including the
     *  casus shapes the grammar admits via the IS-form without the tag). */
    private static Objecttype.KenmerkSoort kenmerkSoortVan(KenmerkSpecificatieContext k) {
        if (k instanceof BijvoeglijkKenmerkContext b)
            return b.BIJVOEGLIJK() != null ? Objecttype.KenmerkSoort.BIJVOEGLIJK : Objecttype.KenmerkSoort.ZELFSTANDIG;
        if (k instanceof NaamwoordKenmerkContext n)
            return n.BEZITTELIJK() != null ? Objecttype.KenmerkSoort.BEZITTELIJK
                 : n.BIJVOEGLIJK() != null ? Objecttype.KenmerkSoort.BIJVOEGLIJK
                 : Objecttype.KenmerkSoort.ZELFSTANDIG;
        return Objecttype.KenmerkSoort.ZELFSTANDIG;
    }

    // ---------------- §13.3.3/§13.3.4 datatypes & domains ----------------
    private void domein(DomeinDefinitionContext ctx) {
        Datatype dt = ctx.enumeratieSpecificatie() != null
                ? enumeratie(src(ctx.domeinNaam()), ctx.enumeratieSpecificatie())
                : datatypeVan(ctx.datatype(), null);
        model.domeinen.put(src(ctx.domeinNaam()), dt);
    }

    private Datatype enumeratie(String domein, EnumeratieSpecificatieContext ctx) {
        List<String> waarden = ctx.ENUM_LITERAL().stream()
                .map(t -> unquote(t.getText())).toList();
        return new Datatype.Enumeratie(domein, waarden);
    }

    /** Resolve an attribute/parameter type: a concrete datatype, or a (possibly forward) domain reference. */
    private Datatype datatypeVan(DatatypeContext dt, DomeinNaamContext dom) {
        if (dt != null) return datatype(dt);
        if (dom != null) {
            Datatype resolved = model.domeinen.get(src(dom));
            return resolved != null ? resolved : new Datatype.Onbekend("domein " + src(dom));
        }
        return new Datatype.Onbekend("geen datatype");
    }

    private Datatype datatype(DatatypeContext dt) {
        if (dt.numeriekDatatype() != null) {
            NumeriekDatatypeContext n = dt.numeriekDatatype();
            // §13.3.3: the getalspecificatie is normatively mandatory ("Numeriek (" <getalspecificatie> ")"),
            // but a worked example (§4.4) omits it ("Numeriek met eenheid jaren"), so the grammar tolerates
            // its absence. Degrade to the least-restrictive reading — equal to a plain "getal" (onbeperkt
            // aantal decimalen, geen tekenbeperking) — and flag the spec-incomplete declaration.
            if (n.getalSpecificatie() == null)
                model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                        "Numeriek datatype zonder verplichte getalspecificatie (§13.3.3); onbeperkte decimalen aangenomen",
                        n.getStart().getLine()));
            GetalInfo g = getalInfo(n.getalSpecificatie());
            Eenheid e = n.eenheidExpressie() != null ? eenheidVan(n.eenheidExpressie()) : null;
            return new Datatype.Numeriek(g.geheel, g.decimalen, g.teken, e);
        }
        if (dt.percentageDatatype() != null) {
            var pd = dt.percentageDatatype();
            GetalInfo g = getalInfo(pd.getalSpecificatie());
            // §3.3.5: a Percentage is always "%", optionally specialized to a rate "%/<eenheid>"
            // (grammar: "met eenheid % ( / <eenheidAfkorting> )?"); a bare or absent clause is "%".
            Eenheid e = pd.SLASH() != null
                    ? Eenheid.samengesteld(Datatype.Percentage.PROCENT, model.resolveEenheid(src(pd.eenheidAfkorting())))
                    : Datatype.Percentage.PROCENT;
            return new Datatype.Percentage(g.geheel, g.decimalen, e);
        }
        if (dt.tekstDatatype() != null) return new Datatype.Tekst();
        if (dt.booleanDatatype() != null) return new Datatype.Booleaan();
        if (dt.datumTijdDatatype() != null) {
            return dt.datumTijdDatatype().DATUM_IN_DAGEN() != null
                    ? new Datatype.DatumInDagen() : new Datatype.DatumTijd();
        }
        return new Datatype.Onbekend("datatype");
    }

    private record GetalInfo(boolean geheel, int decimalen, Datatype.Teken teken) {}

    private GetalInfo getalInfo(GetalSpecificatieContext g) {
        // No specification given: same degenerate reading as a bare "getal" — unbounded decimals
        // (decimalen 0 is the codebase's unbounded sentinel), not a whole number, no sign restriction.
        if (g == null) return new GetalInfo(false, 0, Datatype.Teken.ONBEPAALD);
        Datatype.Teken teken = Datatype.Teken.ONBEPAALD;
        if (g.NEGATIEF() != null) teken = Datatype.Teken.NEGATIEF;
        else if (g.NIET_NEGATIEF() != null) teken = Datatype.Teken.NIET_NEGATIEF;
        else if (g.POSITIEF() != null) teken = Datatype.Teken.POSITIEF;
        boolean geheel = g.GEHEEL_GETAL() != null;
        int decimalen = 0;
        if (g.DECIMALEN() != null && g.NUMBER() != null) {
            try { decimalen = Integer.parseInt(g.NUMBER().getText()); } catch (NumberFormatException ignore) {}
        }
        // §13.3.3.7: "getal met x decimalen" requires x >= 1 (aantaldecimalen is a positiefgeheelgetal).
        // A degenerate "getal met 0 decimalen" denotes a whole number — form (a) "geheel getal", capped
        // at 0 — NOT the unbounded form (c) "getal". Normalize it to geheel so it is not encoded as the
        // (geheel=false, decimalen=0) unbounded sentinel, which would silently drop its §6.1.2 ceiling.
        if (!geheel && g.DECIMALEN() != null && decimalen == 0) {
            geheel = true;
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                    "'getal met 0 decimalen' is geen geldige getalspecificatie (§13.3.3.7 vereist >= 1 decimaal);"
                    + " gelezen als 'geheel getal'", g.getStart().getLine()));
        }
        return new GetalInfo(geheel, decimalen, teken);
    }

    private Eenheid eenheidVan(EenheidExpressieContext e) { return eenheidVan(model, e); }

    /** §13.3.3.2: {@code <eenheidexpressie> ::= <eenheidmacht> ("/" <eenheidmacht>)*} — the numerator
     *  is the first power; a single trailing {@code "/<unit>"} is the denominator of a rate unit
     *  (€/jr). A second denominator (€/u/s) is not yet modelled — keep the numerator only.
     *  Package-static so the attribute-datatype path (here) and the §13.2.17 numeric LITERAL path
     *  ({@link ExpressieBouwer}) resolve a composite (rate) unit IDENTICALLY — a literal "120 EUR/jr"
     *  must carry the same {@code Eenheid.samengesteld(EUR, jr)} an attribute "met eenheid EUR/jr"
     *  does, so §4 unit checking and the exacte cross-unit restatement apply to both alike. */
    static Eenheid eenheidVan(RegelSpraakModel model, EenheidExpressieContext e) {
        if (e == null || e.eenheidMacht().isEmpty()) return null;
        Eenheid teller = model.resolveEenheid(src(e.eenheidMacht(0).eenheidAfkorting()));
        if (e.eenheidMacht().size() == 2)
            return Eenheid.samengesteld(teller, model.resolveEenheid(src(e.eenheidMacht(1).eenheidAfkorting())));
        return teller;
    }

    // ---------------- §13.3.5 unit systems ----------------
    /** A unit's raw declaration before its conversion factor is resolved. */
    private record EenheidRuw(java.util.List<String> afkortingen, String woord, String meervoud,
                              java.math.BigDecimal omrekenN, String omrekenNaar) {}

    private void eenheidsysteem(EenheidsysteemDefinitionContext ctx) {
        Eenheidstelsel stelsel = new Eenheidstelsel(src(ctx.eenheidsysteemNaam()));
        // pass 1: collect raw definitions keyed by primary abbreviation (or word)
        java.util.Map<String, EenheidRuw> ruw = new java.util.LinkedHashMap<>();
        for (EenheidDefinitieContext u : ctx.eenheidDefinitie()) {
            java.util.List<String> afk = u.eenheidAfkorting().stream().map(DefinitionResolver::src).toList();
            String primair = !afk.isEmpty() ? afk.get(0) : (u.eenheidWoord() != null ? src(u.eenheidWoord()) : null);
            if (primair == null) continue;
            java.math.BigDecimal n = null; String naar = null;
            if (u.omrekenSpecificatie() != null) {                       // "= 12 mnd" or "= 1/1000 m"
                n = rationeel(u.omrekenSpecificatie().NUMBER().getText(), primair);
                naar = src(u.omrekenSpecificatie().eenheidAfkorting());
            }
            ruw.put(primair, new EenheidRuw(afk, u.eenheidWoord() != null ? src(u.eenheidWoord()) : null,
                    u.meervoudsvorm() != null ? src(u.meervoudsvorm()) : null, n, naar));
        }
        // pass 2: resolve each unit's canonical + factor, then register every spelling
        java.util.Map<String, Eenheid> opgelost = new java.util.HashMap<>();
        for (String primair : ruw.keySet()) {
            Eenheid e = resolveFactor(primair, ruw, stelsel.naam, opgelost, new java.util.HashSet<>());
            EenheidRuw r = ruw.get(primair);
            for (String a : r.afkortingen()) stelsel.eenheden.put(a, e);
            if (r.woord() != null) stelsel.aliassen.put(RegelSpraakModel.normaliseerTijdseenheid(r.woord()), e);
            if (r.meervoud() != null) stelsel.aliassen.put(RegelSpraakModel.normaliseerTijdseenheid(r.meervoud()), e);
        }
        model.eenheidstelsels.put(stelsel.naam, stelsel);
    }

    /** Parse an omrekenfactor, which the lexer hands over as a single rational NUMBER
     *  token: a whole number ("12"), a decimal ("1,5"), or the §13.3.5.2 "1/N" form
     *  ("1/1000"). A malformed factor is reported, not silently swallowed. */
    private java.math.BigDecimal rationeel(String tekst, String eenheid) {
        try {
            int slash = tekst.indexOf('/');
            if (slash >= 0)
                return new java.math.BigDecimal(tekst.substring(0, slash))
                        .divide(new java.math.BigDecimal(tekst.substring(slash + 1)), java.math.MathContext.DECIMAL64);
            return new java.math.BigDecimal(tekst.replace(',', '.'));
        } catch (RuntimeException e) {
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                    "onleesbare omrekenfactor '" + tekst + "' bij eenheid '" + eenheid + "'", 0));
            return null;
        }
    }

    /** Reduce a unit to its system canonical and the factor to reach it: a base unit
     *  is its own canonical (factor 1); "<u> = N <v>" inherits v's canonical with
     *  factor N·factor(v). Memoised; a cycle or external target stops at a base. */
    private Eenheid resolveFactor(String abbrev, java.util.Map<String, EenheidRuw> ruw, String stelsel,
                                  java.util.Map<String, Eenheid> memo, java.util.Set<String> bezig) {
        if (memo.containsKey(abbrev)) return memo.get(abbrev);
        EenheidRuw r = ruw.get(abbrev);
        Eenheid e;
        if (r == null || r.omrekenNaar() == null || r.omrekenN() == null || !bezig.add(abbrev)) {
            e = new Eenheid(abbrev, stelsel, abbrev, java.math.BigDecimal.ONE);
        } else {
            Eenheid basis = resolveFactor(r.omrekenNaar(), ruw, stelsel, memo, bezig);
            bezig.remove(abbrev);
            e = new Eenheid(abbrev, stelsel, basis.canoniek(), r.omrekenN().multiply(basis.factorNaarCanoniek()));
        }
        memo.put(abbrev, e);
        return e;
    }

    // ---------------- §13.3.8 parameters ----------------
    private void parameter(ParameterDefinitionContext ctx) {
        String naam = src(ctx.parameterNaam());
        // §13.3.6/§13.3.8 a parameter may carry a tijdlijn ("voor elk jaar") -> its runtime value is a timeline.
        Granulariteit g = ctx.tijdlijn() != null ? granulariteitVan(ctx.tijdlijn()) : null;
        model.parameters.put(naam, new Parameter(naam, datatypeVan(ctx.datatype(), ctx.domeinNaam()), g));
    }

    // ---------------- §13.3.6 timelines ----------------
    private void tijdlijn(TijdlijnDefinitieContext ctx) {
        boolean variabel = src(ctx).toLowerCase().contains("met variabel startpunt");
        String naam = src(ctx.tijdlijnNaam());
        Granulariteit gran = granulariteitVanDefinitie(ctx);
        int aantal = aantalVanDefinitie(ctx);
        // §13.3.6.10: a "voor elke <tijdlijn>" reference adopts the FIRST timeline of that (normalised) name via
        // findFirst. Warn only when an earlier same-name timeline declares a DIFFERENT periodicity (granularity
        // or count) — then the reference silently disagrees; an identical redeclaration is harmless.
        String genormaliseerd = normaliseerTijdlijnNaam(naam);
        if (model.tijdlijnen.stream().anyMatch(tl -> normaliseerTijdlijnNaam(tl.naam()).equals(genormaliseerd)
                && (tl.granulariteit() != gran || tl.aantal() != aantal)))
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                    "tijdlijn '" + naam + "' is meermaals met verschillende periodiciteit gedefinieerd; een 'voor "
                            + "elke <naam>' verwijzing neemt die van de eerste (§13.3.6.10)",
                    ctx.tijdlijnNaam().getStart().getLine()));
        model.tijdlijnen.add(new Tijdlijn(naam, variabel, gran, aantal));
    }

    /** §13.3.6 the {@link Granulariteit} of a tijdlijn ATTACHMENT on an attribute/kenmerk. The single-period
     *  form carries it in the token ("voor elke dag" → DAG), the multi-period form in the plural unit ("voor
     *  elke 5 maanden" → MAAND). The named form ("voor elke &lt;tijdlijn&gt;") adopts its definition's
     *  granularity, resolved against {@link RegelSpraakModel#tijdlijnen} elsewhere — null here. */
    private Granulariteit granulariteitVan(TijdlijnContext t) {
        if (t == null) return null;
        EnkelvoudigePeriodeContext e = t.enkelvoudigePeriode();
        if (e != null) {
            if (e.ELKE_DAG() != null)     return Granulariteit.DAG;
            if (e.ELKE_WEEK() != null)    return Granulariteit.WEEK;
            if (e.ELKE_MAAND() != null)   return Granulariteit.MAAND;
            if (e.ELK_KWARTAAL() != null) return Granulariteit.KWARTAAL;
            if (e.ELK_JAAR() != null)     return Granulariteit.JAAR;
        }
        if (t.meervoudigePeriode() != null)
            return granulariteitVanMeervoud(t.meervoudigePeriode().tijdseenhedenMeervoud());
        // §13.3.6.9 the named form "voor elke <tijdlijn>" adopts the granularity of the standalone Tijdlijn
        // definition of that name (registered in pass 1). The reference drops the leading lidwoord the
        // definition carries ("elke periode …" vs "Tijdlijn de periode …") and may wrap across lines, so
        // match on a whitespace-collapsed, lidwoord-stripped name. An unresolved name (e.g. the definition
        // lives in another file) leaves the attribute untagged — no granularity claimed, not faked.
        if (t.ELKE() != null && t.tijdlijnNaam() != null) {
            String ref = normaliseerTijdlijnNaam(src(t.tijdlijnNaam()));
            return model.tijdlijnen.stream()
                    .filter(tl -> normaliseerTijdlijnNaam(tl.naam()).equals(ref))
                    .map(Tijdlijn::granulariteit).findFirst().orElse(null);
        }
        return null;
    }

    /** Normalise a tijdlijn name for cross-reference matching — the shared model implementation
     *  (§9.9 startpuntbepaling resolution matches by the same rule). */
    private static String normaliseerTijdlijnNaam(String s) {
        return RegelSpraakModel.normaliseerTijdlijnNaam(s);
    }

    /** §13.3.6.10 the {@link Granulariteit} a standalone tijdlijn DEFINITION declares ("heeft tijdvakken van
     *  een jaar" → JAAR), from the singular unit token or the plural-period unit. */
    private static Granulariteit granulariteitVanDefinitie(TijdlijnDefinitieContext ctx) {
        if (ctx.DAG() != null)      return Granulariteit.DAG;
        if (ctx.WEEK() != null)     return Granulariteit.WEEK;
        if (ctx.MAAND() != null)    return Granulariteit.MAAND;
        if (ctx.KWARTAAL() != null) return Granulariteit.KWARTAAL;
        if (ctx.JAAR() != null)     return Granulariteit.JAAR;
        return granulariteitVanMeervoud(ctx.tijdseenhedenMeervoud());
    }

    /** §13.3.6.7 the period count of a tijdlijn attachment: the "5" in "voor elke 5 maanden". A single-period
     *  form ("voor elke maand") carries none (1); the named form "voor elke &lt;tijdlijn&gt;" adopts its
     *  definition's count (so "heeft tijdvakken van 5 maanden" → 5), mirroring {@link #granulariteitVan}. */
    private int aantalVan(TijdlijnContext t) {
        if (t == null) return 1;
        if (t.meervoudigePeriode() != null) return aantalVanMeervoud(t.meervoudigePeriode().tijdseenhedenMeervoud());
        if (t.ELKE() != null && t.tijdlijnNaam() != null) {
            String ref = normaliseerTijdlijnNaam(src(t.tijdlijnNaam()));
            return model.tijdlijnen.stream()
                    .filter(tl -> normaliseerTijdlijnNaam(tl.naam()).equals(ref))
                    .map(Tijdlijn::aantal).findFirst().orElse(1);
        }
        return 1;
    }

    /** §13.3.6.10 the period count a standalone tijdlijn DEFINITION declares ("heeft tijdvakken van 5 maanden" →
     *  5), or 1 for the singular "een &lt;unit&gt;" form. */
    private static int aantalVanDefinitie(TijdlijnDefinitieContext ctx) {
        return aantalVanMeervoud(ctx.tijdseenhedenMeervoud());
    }

    /** The NUMBER of a plural period unit ("5 maanden" → 5), or 1 when absent. */
    private static int aantalVanMeervoud(TijdseenhedenMeervoudContext m) {
        if (m == null || m.NUMBER() == null) return 1;
        try { return Integer.parseInt(m.NUMBER().getText()); } catch (NumberFormatException e) { return 1; }
    }

    /** §13.3.6.8 the plural time-unit of a multi-period tijdlijn → its singular {@link Granulariteit}. */
    private static Granulariteit granulariteitVanMeervoud(TijdseenhedenMeervoudContext m) {
        if (m == null) return null;
        if (m.DAGEN() != null)     return Granulariteit.DAG;
        if (m.WEKEN() != null)     return Granulariteit.WEEK;
        if (m.MAANDEN() != null)   return Granulariteit.MAAND;
        if (m.KWARTALEN() != null) return Granulariteit.KWARTAAL;
        if (m.JAREN() != null)     return Granulariteit.JAAR;
        return null;
    }

    /** The optional tijdlijn attachment on a kenmerk declaration (both the bijvoeglijk and naamwoord forms
     *  carry one), or null. */
    private static TijdlijnContext tijdlijnVan(KenmerkSpecificatieContext k) {
        if (k instanceof BijvoeglijkKenmerkContext b) return b.tijdlijn();
        if (k instanceof NaamwoordKenmerkContext n) return n.tijdlijn();
        return null;
    }

    // ---------------- §13.3.7 dimensions ----------------
    private void dimensie(DimensieDefinitionContext ctx) {
        List<String> waarden = ctx.labelWaardeSpecificatie().stream()
                .map(l -> src(l.dimensieWaarde())).toList();
        // §3.6/§13.3.7.2: "na het attribuut met voorzetsel <vz>" gives the suffix-read preposition; the
        // "voor het attribuut zonder voorzetsel" form has no voorzetsel (null) — a prefix (bijvoeglijk) read.
        VoorzetselSpecificatieContext vzs = ctx.voorzetselSpecificatie();
        String voorzetsel = vzs != null && vzs.voorzetsel() != null ? src(vzs.voorzetsel()) : null;
        model.dimensies.put(src(ctx.dimensieNaam()),
                new Dimensie(src(ctx.dimensieNaam()), src(ctx.dimensieNaamMeervoud()), voorzetsel, waarden));
    }

    // ---------------- §13.3.9 fact types ----------------
    private void feittype(FeitTypeDefinitionContext ctx) {
        Feittype ft = new Feittype(src(ctx.feitTypeNaam()));
        ft.wederkerig = ctx.WEDERKERIG_FEITTYPE() != null;
        List<Rol> rollen = new ArrayList<>();
        for (RolSpecificatieContext rs : ctx.rolSpecificatie()) {
            String rolNaam = src(rs.rolNaam());
            String meervoud = rs.meervoudsvorm() != null ? src(rs.meervoudsvorm()) : null; // declared "(mv: …)"
            // §13.3.9.3: feitTypeNaam excludes 'de'/'het' (it stops at the role's article), so a feittype
            // name that itself contains an article ("bonus van DE groep") truncates there and the leftover
            // "de <woord>" is absorbed by this greedy rolNaam — corrupting it to "<woord> de <echte rol>"
            // with no error node. A role name MAY legitimately contain an article, but only after a
            // preposition ("eigenaar van de woning", "behandelaar van het dossier"); the corruption's article
            // instead follows a bare noun ("groep de bonusgroep"). So flag a 'de'/'het' that has a word
            // before AND after it and is NOT preceded by a preposition — the tell-tale of a swallowed
            // name+article boundary — without warning on spec-legal "… van de/het …" names.
            if (lidwoordNaBareWoord(rolNaam))
                model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                        "feittype '" + ft.naam + "': de rolnaam '" + rolNaam + "' bevat een lidwoord (de/het) na een"
                        + " zelfstandig woord — waarschijnlijk bevat de feittypenaam een 'de'/'het' die de naam"
                        + " afkapt en in de rol wordt geabsorbeerd (§13.3.9.3); hernoem het feittype zonder lidwoord",
                        ctx.feitTypeNaam().getStart().getLine()));
            rollen.add(new Rol(rolNaam, src(rs.objectTypeNaam()), meervoud));
        }
        // §13.3.9.1 cardinaliteit: the line "<q1> <rol1> … <q2> <rol2>" marks each role one or
        // meerdere. A wederkerig role is enkelvoudig (§3.11), so only a normal feittype's roles
        // take a 'meerdere' mark; an unmatched/absent line leaves a role single (fail-open).
        if (!ft.wederkerig && ctx.feitTypeCardinaliteit() != null)
            rollen = markeerKardinaliteit(rollen, ctx.feitTypeCardinaliteit());
        ft.rollen.addAll(rollen);
        model.feittypen.put(ft.naam, ft);
    }

    /** The prepositions a role name may carry before an internal article (the §13.4 name words: a role
     *  like "eigenaar van de woning" / "deel in het geheel" is legitimate). */
    private static final java.util.Set<String> VOORZETSELS =
            java.util.Set.of("van", "op", "in", "voor", "over", "bij", "tot", "per", "met");

    /** Whether {@code rolNaam} contains a 'de'/'het' that has a word both before and after it and is NOT
     *  preceded by a preposition — the signature of a feittype-name article (§13.3.9.3) swallowed into the
     *  role, as opposed to a legitimate "… van de/het …" role name. */
    private static boolean lidwoordNaBareWoord(String rolNaam) {
        String[] w = rolNaam.toLowerCase().split("\\s+");
        for (int i = 1; i + 1 < w.length; i++)            // an article with a word before AND after it
            if ((w[i].equals("de") || w[i].equals("het")) && !VOORZETSELS.contains(w[i - 1]))
                return true;
        return false;
    }

    /** Tag each role single/meerdere from the cardinaliteit line. The line alternates a
     *  quantifier terminal (één | meerdere) with a noun phrase; the quantifier qualifies the
     *  phrase that follows it. Per the §13.3.9.1 EBNF a segment is
     *  {@code (één|meerdere) <rolnaam> <relatiebeschrijving>}: the rolnaam is the LEADING phrase
     *  and the free-text relatiebeschrijving prose follows it. So a role is matched against the
     *  longest LEADING sub-phrase of the deel (not word-by-word). This binds a multi-word role
     *  name whole ("deelnemer met recht op korting") and never marks a role that a trailing
     *  relatiebeschrijving merely names ("… behoren de groep toe aan …" leaves the groep role untouched)
     *  — the two failure modes word-by-word matching had. */
    private List<Rol> markeerKardinaliteit(List<Rol> rollen, FeitTypeCardinaliteitContext card) {
        List<Rol> uit = new ArrayList<>(rollen);
        // Collect the (meervoudig, head-words) segments in order — there are exactly two for a
        // non-wederkerig feittype (the grammar fixes it), one per role.
        List<Boolean> meervoud = new ArrayList<>();
        List<String[]> segmenten = new ArrayList<>();
        boolean meervoudigVolgend = false;
        for (int i = 0; i < card.getChildCount(); i++) {
            ParseTree ch = card.getChild(i);
            if (ch instanceof TerminalNode t) {
                meervoudigVolgend = t.getSymbol().getType() == RegelSpraak.MEERDERE;
            } else if (ch instanceof CardinaliteitDeelContext deel) {
                segmenten.add(src(deel).split("\\s+"));
                meervoud.add(meervoudigVolgend);
            }
        }
        // Bind each segment to a role by NAME first (longest leading sub-phrase). A segment whose noun
        // matches no role — an UNDECLARED irregular plural the language does not synthesise (§footnote 2:
        // "vakken"/"pupillen" need consonant doubling, refused by both the best-effort and MeervoudMatcher)
        // — is then assigned by ELIMINATION: a non-wederkerig feittype has exactly two roles, so if the
        // other segment named one role, this segment must be the remaining one. No plural guessing, just
        // the 2-role structure. (When BOTH segments fail to name a role there is no signal, so both stay.)
        int[] segRol = new int[segmenten.size()];
        boolean[] rolBezet = new boolean[uit.size()];
        for (int s = 0; s < segmenten.size(); s++) {
            int j = vindKop(uit, segmenten.get(s), rolBezet);
            segRol[s] = j;
            if (j >= 0) rolBezet[j] = true;
        }
        for (int s = 0; s < segmenten.size(); s++) {
            if (segRol[s] >= 0) continue;
            int rest = -1, n = 0;
            for (int j = 0; j < uit.size(); j++) if (!rolBezet[j]) { rest = j; n++; }
            if (n == 1) { segRol[s] = rest; rolBezet[rest] = true; }
        }
        for (int s = 0; s < segmenten.size(); s++) {
            int j = segRol[s];
            if (j >= 0 && uit.get(j).meervoudig() != meervoud.get(s))
                uit.set(j, new Rol(uit.get(j).naam(), uit.get(j).objecttypeNaam(), uit.get(j).meervoud(), meervoud.get(s)));
        }
        return uit;
    }

    /** The index of the role named by the LEADING sub-phrase of {@code woorden} (the rolnaam head),
     *  or -1 when none matches: try the longest leading phrase first so a multi-word role binds whole
     *  and a more specific role wins over a shorter prefix; stop at the first match so trailing
     *  relatiebeschrijving words never name another role. An already-bound role is skipped so the two
     *  segments cannot both claim the same role. */
    private int vindKop(List<Rol> uit, String[] woorden, boolean[] rolBezet) {
        for (int len = woorden.length; len >= 1; len--) {
            String kandidaat = String.join(" ", java.util.Arrays.copyOfRange(woorden, 0, len));
            for (int j = 0; j < uit.size(); j++)
                if (!rolBezet[j] && RegelSpraakModel.rolNaamMatcht(uit.get(j), kandidaat)) return j;
        }
        return -1;
    }

    // ---------------- helpers ----------------
    /** §3.12/§13.3.10 register a dagsoort: its singular name, and the unit→dagsoort map for BOTH the singular
     *  and (when declared) the meervoud — a §6 duration unit "5 werkdagen" arrives as the plural eenheidsafkorting. */
    private void dagsoort(DagsoortDefinitionContext d) {
        String enkel = naam(d.lowerNaam());
        model.dagsoorten.add(enkel);
        registreerDagsoortEenheid(enkel, enkel);
        // §3.12 a duration unit is naturally the PLURAL ("5 werkdagen"); register the declared meervoud, or — when
        // none is declared — the derived "-en" plural ("werkdag" -> "werkdagen"), so the unit still resolves.
        if (d.meervoudsvorm() != null) registreerDagsoortEenheid(src(d.meervoudsvorm()).trim(), enkel);
        else registreerDagsoortEenheid(enkel + "en", enkel);
    }

    /** A dagsoort unit spelling that collides with a built-in tijdseenheid (dag/week/maand/…) is NOT registered — it
     *  must not shadow the calendar arithmetic of {@code datum ± N dagen}; warn instead. */
    private static final java.util.Set<String> INGEBOUWDE_EENHEDEN = java.util.Set.of(
            "dag", "dagen", "week", "weken", "maand", "maanden", "kwartaal", "kwartalen", "jaar", "jaren",
            "uur", "uren", "minuut", "minuten", "seconde", "seconden", "milliseconde", "milliseconden");
    private void registreerDagsoortEenheid(String spelling, String dagsoort) {
        String k = spelling.trim().toLowerCase();
        if (INGEBOUWDE_EENHEDEN.contains(k)) {
            model.diagnostics.add(new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                    "dagsoort-eenheid '" + spelling + "' valt samen met een ingebouwde tijdseenheid en wordt niet als dagsoort-eenheid geregistreerd", 0));
            return;
        }
        model.dagsoortPerEenheid.putIfAbsent(k, dagsoort);
    }

    private static String naam(LowerNaamContext ctx) { return src(ctx); }

    /** The original source slice of a context, with internal whitespace collapsed. */
    static String src(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null || ctx.getStop() == null) return "";
        if (ctx.getStop().getStopIndex() < ctx.getStart().getStartIndex()) return ""; // empty match
        String t = ctx.getStart().getInputStream()
                .getText(Interval.of(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex()));
        return t.replaceAll("\\s+", " ").trim();
    }

    /** The structured source anchor of {@code ctx}: {@link #src} as the snippet, plus file/line/column
     *  from the start token (the file being the char stream's source name, set at the front end). A null
     *  ctx yields a synthetic empty anchor, mirroring {@link #src}'s null handling. */
    static io.github.yhavinga.regelspraak.ir.Bron bron(ParserRuleContext ctx) {
        return ctx == null ? io.github.yhavinga.regelspraak.ir.Bron.synthetisch("")
                           : io.github.yhavinga.regelspraak.ir.Bron.van(ctx, src(ctx));
    }

    private static String unquote(String s) {
        if (s.length() >= 2) return s.substring(1, s.length() - 1);
        return s;
    }
}
