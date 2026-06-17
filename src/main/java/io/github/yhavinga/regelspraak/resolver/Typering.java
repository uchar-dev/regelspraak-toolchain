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
            // §4.1 / §4.2: numeriek±numeriek (units convertible), OR datum ± numerieke tijdswaarde -> datum
            case PLUS, MIN -> {
                if (l.isDatum() && r.isNumeriek()) yield Resultaat.ok(l); // §4.1/4.2 scenario 2
                yield numeriekBinair(l, r, op == BinOp.PLUS ? "plus" : "min");
            }
            // §4.3 verminderd met: numeriek only
            case VERMINDERD_MET -> numeriekBinair(l, r, "verminderd met");
            // §4.4/4.5/4.7: numeriek; units multiply/divide (modelled loosely: result keeps left's unit)
            case MAAL, GEDEELD_DOOR, TOT_DE_MACHT -> {
                if (!l.isGetalachtig() || !r.isGetalachtig())
                    yield Resultaat.fout(naam(op) + " vereist numerieke operanden, kreeg " + l.naam() + " en " + r.naam());
                yield Resultaat.ok(new Datatype.Numeriek(false, maxDec(l, r), Datatype.Teken.ONBEPAALD, l.eenheidOf()));
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

    /** §5.11-5.16 one-sided predicates. */
    public static Resultaat predicaat(UnairePred p, Datatype operand) {
        if (operand.isOnbekend()) return Resultaat.ok(new Datatype.Booleaan());
        return switch (p) {
            case LEEG, GEVULD -> Resultaat.ok(new Datatype.Booleaan()); // §5.11/5.12 any datatype
            case ELFPROEF, NUMERIEK_MET_EXACT -> operand.isTekst()       // §5.13/5.16 Tekst only
                    ? Resultaat.ok(new Datatype.Booleaan())
                    : Resultaat.fout("elfproef / numeriek-met-exact is alleen toegestaan op Tekst, kreeg " + operand.naam());
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

    /** Same datatype family for gelijk/ongelijk/toekenning (numeriek and percentage count as one). */
    private static boolean zelfdeFamilie(Datatype a, Datatype b) {
        if (a.isGetalachtig() && b.isGetalachtig()) return true;
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
