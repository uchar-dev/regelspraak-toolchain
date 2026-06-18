package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;

/**
 * The RegelSpraak v2.3.0 typeringen matrix (the "typeringen" specification),
 * encoded as pure functions over {@link Datatype}. This is the contract the
 * resolver enforces: which operand datatypes an operator admits, the result
 * datatype, and unit compatibility. Kept free of any parse-tree dependency so it
 * can be unit-tested directly against the matrix tables.
 *
 * References are to the typeringen document: §4 operators, §5 comparisons,
 * §6 resultaatdeel.
 */
public final class Typering {
    private Typering() {}

    /** Outcome of a typeringen check: a result datatype, or an error message. */
    public record Resultaat(Datatype type, String fout) {
        public boolean ok() { return fout == null; }
        static Resultaat ok(Datatype t) { return new Resultaat(t, null); }
        static Resultaat fout(String f) { return new Resultaat(null, f); }
    }

    public enum BinOp { PLUS, MIN, VERMINDERD_MET, MAAL, GEDEELD_DOOR, TOT_DE_MACHT }
    public enum CmpOp {
        KLEINER, KLEINER_OF_GELIJK, GROTER, GROTER_OF_GELIJK,   // §5.1,5.2,5.4,5.5 — getalachtig
        GELIJK, ONGELIJK,                                        // §5.3,5.6 — any, both same
        EERDER, EERDER_OF_GELIJK, LATER, LATER_OF_GELIJK         // §5.7-5.10 — datum
    }
    public enum UnairePred { LEEG, GEVULD, ELFPROEF, NUMERIEK_MET_EXACT, DAGSOORT }

    // -------- §4 arithmetic --------
    public static Resultaat reken(BinOp op, Datatype l, Datatype r) {
        if (l.isOnbekend() || r.isOnbekend()) return Resultaat.ok(new Datatype.Onbekend("operand"));
        return switch (op) {
            // §4.1 / §4.2: numeriek±numeriek (units convertible), OR datum ± a numerieke
            // tijdswaarde (a number carrying a unit) -> datum (Tabel 2/5 scenario 2).
            case PLUS, MIN -> {
                if (l.isDatum() && r instanceof Datatype.Numeriek rn && rn.heeftEenheid())
                    yield Resultaat.ok(l);
                yield numeriekBinair(l, r, op == BinOp.PLUS ? "plus" : "min");
            }
            // §4.3 verminderd met: numeriek only
            case VERMINDERD_MET -> numeriekBinair(l, r, "verminderd met");
            // §4.4/4.5: Numeriek only (Tabel 11/14 list no Percentage row). Units multiply/divide
            // into a composite the model cannot represent, so a unit-bearing result is reported as
            // Onbekend ("no claim") rather than mis-typed; a unitless * unit keeps the single unit.
            case MAAL, GEDEELD_DOOR -> {
                if (!l.isNumeriek() || !r.isNumeriek())
                    yield Resultaat.fout(naam(op) + " vereist numerieke operanden, kreeg " + l.naam() + " en " + r.naam());
                Eenheid el = l.eenheidOf(), er = r.eenheidOf();
                if (el == null && er == null)
                    yield Resultaat.ok(new Datatype.Numeriek(false, maxDec(l, r), Datatype.Teken.ONBEPAALD, null));
                if (er == null)
                    yield Resultaat.ok(new Datatype.Numeriek(false, maxDec(l, r), Datatype.Teken.ONBEPAALD, el));
                yield Resultaat.ok(new Datatype.Onbekend("samengestelde eenheid"));
            }
            // §4.7 tot de macht: Numeriek without a unit on either side; result without a unit.
            case TOT_DE_MACHT -> {
                if (!l.isNumeriek() || !r.isNumeriek())
                    yield Resultaat.fout("tot de macht vereist numerieke operanden, kreeg " + l.naam() + " en " + r.naam());
                if (l.eenheidOf() != null || r.eenheidOf() != null)
                    yield Resultaat.fout("tot de macht is alleen toegestaan op operanden zonder eenheid");
                yield Resultaat.ok(new Datatype.Numeriek(false, maxDec(l, r), Datatype.Teken.ONBEPAALD, null));
            }
        };
    }

    private static Resultaat numeriekBinair(Datatype l, Datatype r, String op) {
        if (!l.isGetalachtig() || !r.isGetalachtig())
            return Resultaat.fout("operator '" + op + "' vereist numerieke operanden, kreeg " + l.naam() + " en " + r.naam());
        String unitFout = eenhedenCompatibel(l, r, op);
        if (unitFout != null) return Resultaat.fout(unitFout);
        return Resultaat.ok(new Datatype.Numeriek(false, maxDec(l, r), Datatype.Teken.ONBEPAALD, l.eenheidOf()));
    }

    // -------- §5 comparison (result is always Boolean) --------
    public static Resultaat vergelijk(CmpOp op, Datatype l, Datatype r) {
        if (l.isOnbekend() || r.isOnbekend()) return Resultaat.ok(new Datatype.Booleaan());
        return switch (op) {
            case KLEINER, KLEINER_OF_GELIJK, GROTER, GROTER_OF_GELIJK -> {
                if (!l.isGetalachtig() || !r.isGetalachtig())
                    yield Resultaat.fout("ordeningsvergelijking vereist numerieke operanden, kreeg " + l.naam() + " en " + r.naam());
                String u = eenhedenCompatibel(l, r, "vergelijking");
                yield u != null ? Resultaat.fout(u) : Resultaat.ok(new Datatype.Booleaan());
            }
            case EERDER, EERDER_OF_GELIJK, LATER, LATER_OF_GELIJK -> {
                if (!l.isDatum() || !r.isDatum())
                    yield Resultaat.fout("eerder/later vereist datum-operanden, kreeg " + l.naam() + " en " + r.naam());
                yield Resultaat.ok(new Datatype.Booleaan());
            }
            case GELIJK, ONGELIJK -> {
                if (!zelfdeFamilie(l, r))
                    yield Resultaat.fout("gelijk/ongelijk vereist operanden van hetzelfde datatype, kreeg " + l.naam() + " en " + r.naam());
                if (l.isGetalachtig()) {
                    String u = eenhedenCompatibel(l, r, "gelijk");
                    if (u != null) yield Resultaat.fout(u);
                }
                yield Resultaat.ok(new Datatype.Booleaan());
            }
        };
    }

    /** §6.1.4 begrenzing: the bounded value must be Numeriek/Percentage and the bound "een
     *  numerieke expressie" — both getalachtig, but NOT necessarily the same family (§6.1.4 lets
     *  a Percentage value take a plain numeric-literal bound). The unit rule is intentionally
     *  looser than {@link #eenhedenCompatibel}: only two PRESENT-but-inconvertible units (km vs €)
     *  are flagged, while a unit-bearing literal against a unitless numeric is tolerated — not
     *  because the spec sanctions it, but to accommodate a known inconsistency in the TOKA casus,
     *  which declares Bedrag unitless yet bounds it by "0 €" (a convertible pair like mnd/jr is
     *  restated at transpile time). Returns the diagnostic, or null when ok / either side is
     *  Onbekend (no claim). */
    public static String begrenzing(Datatype basis, Datatype grens) {
        if (basis.isOnbekend() || grens.isOnbekend()) return null;
        if (!basis.isGetalachtig())
            return "begrenzing is alleen toegestaan op een numerieke waarde, kreeg " + basis.naam();
        if (!grens.isGetalachtig())
            return "begrenzing vereist een numerieke grens, kreeg " + grens.naam();
        Eenheid eb = basis.eenheidOf(), eg = grens.eenheidOf();
        if (eb != null && eg != null && !eb.convertibleNaar(eg))
            return "begrenzing: eenheden '" + eb.afkorting() + "' en '" + eg.afkorting()
                    + "' zijn niet in elkaar om te rekenen";
        return null;
    }

    /** §4.12/4.13 Tabel 22/23 — minimale/maximale waarde van (getalaggregatiefunctie): every
     *  operand must be a numerieke expressie, and "wanneer eenheden worden gebruikt, deze in
     *  elkaar om te rekenen zijn." Read getalachtig (Numeriek OR Percentage) rather than strictly
     *  Numeriek, mirroring the §5.1-5.4 ordinal comparison — min/max is repeated ordinal selection,
     *  and that comparison admits a Percentage. The unit rule is the STRICT §4 one (unlike the
     *  lenient {@link #begrenzing}): all operands unitless, or all unit-bearing and pairwise
     *  inter-convertible — no TOKA quirk forces leniency here (its lone max is single-operand).
     *  Onbekend operands make no claim (skipped); a single operand is trivially well-typed.
     *  Returns the diagnostic, or null when ok. */
    public static String getalAggregatie(String functie, java.util.List<Datatype> operanden) {
        Eenheid ref = null; boolean refGezet = false;
        for (Datatype d : operanden) {
            if (d.isOnbekend()) continue;
            if (!d.isGetalachtig())
                return functie + " vereist numerieke operanden, kreeg " + d.naam();
            Eenheid e = d.eenheidOf();
            if (!refGezet) { ref = e; refGezet = true; continue; }
            if ((ref == null) != (e == null))
                return functie + ": als één operand een eenheid heeft, moeten alle operanden er een hebben";
            if (ref != null && !ref.convertibleNaar(e))
                return functie + ": eenheden '" + ref.afkorting() + "' en '" + e.afkorting()
                        + "' zijn niet in elkaar om te rekenen";
        }
        return null;
    }

    /** §4.14/4.15 Tabel 24/25 — eerste/laatste van (datumaggregatiefunctie): every operand must
     *  be a datumexpressie, and "alle expressies dezelfde tijd-precisie bevatten" — modelled as
     *  the same concrete date kind (all {@link Datatype.DatumInDagen} or all {@link
     *  Datatype.DatumTijd}). Onbekend = no claim. Returns the diagnostic, or null when ok. */
    public static String datumAggregatie(String functie, java.util.List<Datatype> operanden) {
        Datatype ref = null;
        for (Datatype d : operanden) {
            if (d.isOnbekend()) continue;
            if (!d.isDatum())
                return functie + " vereist datumexpressies, kreeg " + d.naam();
            if (ref == null) { ref = d; continue; }
            if (!ref.getClass().equals(d.getClass()))
                return functie + ": alle datumexpressies moeten dezelfde tijd-precisie hebben, kreeg "
                        + ref.naam() + " en " + d.naam();
        }
        return null;
    }

    /** §5.11-5.16 one-sided predicates. */
    public static Resultaat predicaat(UnairePred p, Datatype operand) {
        if (operand.isOnbekend()) return Resultaat.ok(new Datatype.Booleaan());
        return switch (p) {
            case LEEG, GEVULD -> Resultaat.ok(new Datatype.Booleaan()); // §5.11/5.12 any datatype
            case ELFPROEF -> // §5.13 Tabel 55: Numeriek (geheel getal, geen eenheid) OR Tekst
                    operand.isTekst()
                        || (operand instanceof Datatype.Numeriek n && n.geheel() && !n.heeftEenheid())
                    ? Resultaat.ok(new Datatype.Booleaan())
                    : Resultaat.fout("voldoet aan de elfproef is alleen toegestaan op Tekst of Numeriek (geheel getal), kreeg " + operand.naam());
            case NUMERIEK_MET_EXACT -> operand.isTekst()                 // §5.16 Tekst only
                    ? Resultaat.ok(new Datatype.Booleaan())
                    : Resultaat.fout("numeriek met exact is alleen toegestaan op Tekst, kreeg " + operand.naam());
            case DAGSOORT -> operand.isDatum()                           // §5.15 Datum only
                    ? Resultaat.ok(new Datatype.Booleaan())
                    : Resultaat.fout("'is een <dagsoort>' is alleen toegestaan op een datum, kreeg " + operand.naam());
        };
    }

    /** §6.1/§6.2 assignment / initialisation: value must fit the target attribute. */
    public static String toekenning(Datatype doel, Datatype waarde) {
        if (doel.isOnbekend() || waarde.isOnbekend()) return null;
        if (!zelfdeFamilie(doel, waarde))
            return "kan een waarde van type " + waarde.naam() + " niet toekennen aan een " + doel.naam();
        if (doel.isGetalachtig()) return eenhedenCompatibel(doel, waarde, "toekenning");
        return null;
    }

    // -------- helpers --------

    /** Same datatype family for gelijk/ongelijk/toekenning. Numeriek and Percentage are
     *  distinct here (the §5.3/5.6 and §6 tables list them as separate rows); the two date
     *  kinds count as one (the spec models a single date/time datatype with precision). */
    private static boolean zelfdeFamilie(Datatype a, Datatype b) {
        if (a.isNumeriek() && b.isNumeriek()) return true;
        if (a.isPercentage() && b.isPercentage()) return true;
        if (a.isDatum() && b.isDatum()) return true;
        return a.getClass().equals(b.getClass());
    }

    /** Unit rule (§4 note): both unitless, or both present and inter-convertible. */
    private static String eenhedenCompatibel(Datatype l, Datatype r, String waar) {
        Eenheid el = l.eenheidOf(), er = r.eenheidOf();
        if (el == null && er == null) return null;
        if (el == null || er == null)
            return waar + ": als één operand een eenheid heeft, moeten beide er een hebben";
        if (!el.convertibleNaar(er))
            return waar + ": eenheden '" + el.afkorting() + "' en '" + er.afkorting() + "' zijn niet in elkaar om te rekenen";
        return null;
    }

    private static int maxDec(Datatype l, Datatype r) {
        int dl = (l instanceof Datatype.Numeriek n) ? n.decimalen() : (l instanceof Datatype.Percentage p ? p.decimalen() : 0);
        int dr = (r instanceof Datatype.Numeriek n) ? n.decimalen() : (r instanceof Datatype.Percentage p ? p.decimalen() : 0);
        return Math.max(dl, dr);
    }

    private static String naam(BinOp op) {
        return switch (op) {
            case PLUS -> "plus"; case MIN -> "min"; case VERMINDERD_MET -> "verminderd met";
            case MAAL -> "maal"; case GEDEELD_DOOR -> "gedeeld door"; case TOT_DE_MACHT -> "tot de macht";
        };
    }
}
