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
    private void eenheidsysteem(EenheidsysteemDefinitionContext ctx) {
        Eenheidstelsel stelsel = new Eenheidstelsel(src(ctx.eenheidsysteemNaam()));
        for (EenheidDefinitieContext u : ctx.eenheidDefinitie()) {
            for (var afk : u.eenheidAfkorting()) {
                String a = src(afk);
                stelsel.eenheden.put(a, new Eenheid(a, stelsel.naam));
            }
        }
        model.eenheidstelsels.put(stelsel.naam, stelsel);
    }

    // ---------------- §13.3.8 parameters ----------------
    private void parameter(ParameterDefinitionContext ctx) {
        String naam = src(ctx.parameterNaam());
        model.parameters.put(naam, new Parameter(naam, datatypeVan(ctx.datatype(), ctx.domeinNaam())));
    }

    // ---------------- §13.3.9 fact types ----------------
    private void feittype(FeitTypeDefinitionContext ctx) {
        Feittype ft = new Feittype(src(ctx.feitTypeNaam()));
        ft.wederkerig = ctx.WEDERKERIG_FEITTYPE() != null;
        for (RolSpecificatieContext rs : ctx.rolSpecificatie()) {
            ft.rollen.add(new Rol(src(rs.rolNaam()), src(rs.objectTypeNaam())));
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
