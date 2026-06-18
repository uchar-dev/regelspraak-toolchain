package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.*;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.*;
import io.github.yhavinga.regelspraak.parser.RegelSpraak;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;

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
        for (DefinitieContext d : definities(doc)) {        // pass 1: domains + units
            if (d.domeinDefinition() != null) domein(d.domeinDefinition());
            if (d.eenheidsysteemDefinition() != null) eenheidsysteem(d.eenheidsysteemDefinition());
        }
        for (DefinitieContext d : definities(doc)) {        // pass 2: everything that references them
            if (d.objectTypeDefinition() != null) objecttype(d.objectTypeDefinition());
            if (d.objectTypeExtensie() != null) extensie(d.objectTypeExtensie());
            if (d.parameterDefinition() != null) parameter(d.parameterDefinition());
            if (d.feitTypeDefinition() != null) feittype(d.feitTypeDefinition());
            if (d.dagsoortDefinition() != null) model.dagsoorten.add(naam(d.dagsoortDefinition().lowerNaam()));
            if (d.tijdlijnDefinitie() != null) tijdlijn(d.tijdlijnDefinitie());
            if (d.dimensieDefinition() != null) dimensie(d.dimensieDefinition());
        }
        for (RegelContext r : doc.regel()) {
            model.regels.add(new Regel(src(r.regelNaam()), "regel"));
        }
        return model;
    }

    private static List<DefinitieContext> definities(RegelSpraakDocumentContext doc) {
        return doc.definitie();
    }

    // ---------------- §13.3.1 object types ----------------
    private void objecttype(ObjectTypeDefinitionContext ctx) {
        Objecttype ot = model.objecttypen.computeIfAbsent(src(ctx.objectTypeNaam()), Objecttype::new);
        if (ctx.BEZIELD() != null) ot.bezield = true;
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
            ot.attributen.put(naam(a.lowerNaam()), datatypeVan(a.datatype(), a.domeinNaam()));
        } else if (m.kenmerkSpecificatie() != null) {
            ot.kenmerken.add(kenmerkNaam(m.kenmerkSpecificatie()));
        }
        // koptekst carries no model information
    }

    private String kenmerkNaam(KenmerkSpecificatieContext k) {
        if (k instanceof BijvoeglijkKenmerkContext b) return naam(b.lowerNaam());
        if (k instanceof NaamwoordKenmerkContext n) return naam(n.lowerNaam());
        return src(k);
    }

    // ---------------- §13.3.3/§13.3.4 datatypes & domains ----------------
    private void domein(DomeinDefinitionContext ctx) {
        Datatype dt = ctx.enumeratieSpecificatie() != null
                ? enumeratie(ctx.enumeratieSpecificatie())
                : datatypeVan(ctx.datatype(), null);
        model.domeinen.put(src(ctx.domeinNaam()), dt);
    }

    private Datatype enumeratie(EnumeratieSpecificatieContext ctx) {
        List<String> waarden = ctx.ENUM_LITERAL().stream()
                .map(t -> unquote(t.getText())).toList();
        return new Datatype.Enumeratie(waarden);
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
            GetalInfo g = getalInfo(n.getalSpecificatie());
            Eenheid e = n.eenheidExpressie() != null ? eenheidVan(n.eenheidExpressie()) : null;
            return new Datatype.Numeriek(g.geheel, g.decimalen, g.teken, e);
        }
        if (dt.percentageDatatype() != null) {
            GetalInfo g = getalInfo(dt.percentageDatatype().getalSpecificatie());
            return new Datatype.Percentage(g.geheel, g.decimalen);
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
        Datatype.Teken teken = Datatype.Teken.ONBEPAALD;
        if (g.NEGATIEF() != null) teken = Datatype.Teken.NEGATIEF;
        else if (g.NIET_NEGATIEF() != null) teken = Datatype.Teken.NIET_NEGATIEF;
        else if (g.POSITIEF() != null) teken = Datatype.Teken.POSITIEF;
        boolean geheel = g.GEHEEL_GETAL() != null;
        int decimalen = 0;
        if (g.DECIMALEN() != null && g.NUMBER() != null) {
            try { decimalen = Integer.parseInt(g.NUMBER().getText()); } catch (NumberFormatException ignore) {}
        }
        return new GetalInfo(geheel, decimalen, teken);
    }

    private Eenheid eenheidVan(EenheidExpressieContext e) {
        // first unit-power's abbreviation; resolved against the declared unit systems
        if (e.eenheidMacht().isEmpty()) return null;
        String afk = src(e.eenheidMacht(0).eenheidAfkorting());
        return model.resolveEenheid(afk);
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
            if (u.omrekenSpecificatie() != null) {                       // "= 12 mnd"
                try { n = new java.math.BigDecimal(u.omrekenSpecificatie().NUMBER().getText().replace(',', '.')); }
                catch (NumberFormatException ignore) {}
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
        model.parameters.put(naam, new Parameter(naam, datatypeVan(ctx.datatype(), ctx.domeinNaam())));
    }

    // ---------------- §13.3.6 timelines ----------------
    private void tijdlijn(TijdlijnDefinitieContext ctx) {
        boolean variabel = src(ctx).toLowerCase().contains("met variabel startpunt");
        model.tijdlijnen.add(new Tijdlijn(src(ctx.tijdlijnNaam()), variabel));
    }

    // ---------------- §13.3.7 dimensions ----------------
    private void dimensie(DimensieDefinitionContext ctx) {
        List<String> waarden = ctx.labelWaardeSpecificatie().stream()
                .map(l -> src(l.dimensieWaarde())).toList();
        model.dimensies.put(src(ctx.dimensieNaam()),
                new Dimensie(src(ctx.dimensieNaam()), src(ctx.dimensieNaamMeervoud()), waarden));
    }

    // ---------------- §13.3.9 fact types ----------------
    private void feittype(FeitTypeDefinitionContext ctx) {
        Feittype ft = new Feittype(src(ctx.feitTypeNaam()));
        ft.wederkerig = ctx.WEDERKERIG_FEITTYPE() != null;
        for (RolSpecificatieContext rs : ctx.rolSpecificatie()) {
            String meervoud = rs.meervoudsvorm() != null ? src(rs.meervoudsvorm()) : null; // declared "(mv: …)"
            ft.rollen.add(new Rol(src(rs.rolNaam()), src(rs.objectTypeNaam()), meervoud));
        }
        model.feittypen.put(ft.naam, ft);
    }

    // ---------------- helpers ----------------
    private static String naam(LowerNaamContext ctx) { return src(ctx); }

    /** The original source slice of a context, with internal whitespace collapsed. */
    static String src(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null || ctx.getStop() == null) return "";
        if (ctx.getStop().getStopIndex() < ctx.getStart().getStartIndex()) return ""; // empty match
        String t = ctx.getStart().getInputStream()
                .getText(Interval.of(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex()));
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String unquote(String s) {
        if (s.length() >= 2) return s.substring(1, s.length() - 1);
        return s;
    }
}
