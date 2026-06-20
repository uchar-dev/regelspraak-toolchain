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
        } else if (m.kenmerkSpecificatie() != null) {
            String naam = kenmerkNaam(m.kenmerkSpecificatie());
            meldHoofdletterBotsing(ot, naam, ot.kenmerken, m.getStart().getLine());
            ot.kenmerken.add(naam);
        }
        // koptekst carries no model information
    }

    /** §13.2.30: a naam is NOT hoofdletter-onderscheidend, so two members of one object type OF
     *  THE SAME KIND that differ ONLY in case are the same name declared twice — ambiguous (a
     *  third casing would bind whichever was declared first, see {@link Objecttype#attribuut}).
     *  Warn at registration. Scoped to one kind: an attribute and a kenmerk MAY legitimately
     *  share a name (TOKA's Vlucht has both a "bereikbaar per trein" kenmerk and attribute) — a
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
        return new GetalInfo(geheel, decimalen, teken);
    }

    private Eenheid eenheidVan(EenheidExpressieContext e) {
        // §13.3.3.2: <eenheidexpressie> ::= <eenheidmacht> ("/" <eenheidmacht>)* — the numerator
        // is the first power; a single trailing "/<unit>" is the denominator of a rate unit
        // (€/jr). A second denominator (€/u/s) is not yet modelled — keep the numerator only.
        if (e.eenheidMacht().isEmpty()) return null;
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
        List<Rol> rollen = new ArrayList<>();
        for (RolSpecificatieContext rs : ctx.rolSpecificatie()) {
            String rolNaam = src(rs.rolNaam());
            String meervoud = rs.meervoudsvorm() != null ? src(rs.meervoudsvorm()) : null; // declared "(mv: …)"
            // §13.3.9.3: feitTypeNaam excludes 'de'/'het' (it stops at the role's article), so a feittype
            // name that itself contains an article ("bonus van DE vlucht") truncates there and the leftover
            // "de <woord>" is absorbed by this greedy rolNaam — corrupting it to "<woord> de <echte rol>"
            // with no error node. A role name MAY legitimately contain an article, but only after a
            // preposition ("eigenaar van de woning", "behandelaar van het dossier"); the corruption's article
            // instead follows a bare noun ("vlucht de bonusvlucht"). So flag a 'de'/'het' that has a word
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
     *  name whole ("passagier met recht op treinmiles") and never marks a role that a trailing
     *  relatiebeschrijving merely names ("… boeken de reis voor …" leaves the reis role untouched)
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
