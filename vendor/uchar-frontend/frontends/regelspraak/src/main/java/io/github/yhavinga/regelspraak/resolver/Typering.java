package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.kern.Tijdrekening;

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
        return reken(op, l, r, false);
    }

    /** {@code unitlessLinksLiteraal}: the LEFT operand is a unitless LITERAL/constant. Then §4.4 Tabel 11 ((none) ×
     *  Eenheid1 -> Eenheid1) propagates the RIGHT operand's unit for MAAL ("2 maal de looptijd(mnd)" = a mnd value),
     *  instead of the deliberate Onbekend degradation that tolerates an attribute × attribute unit under-model (a known
     *  casus shape: a unitless-declared bedrag × km). Narrowing to a LITERAL avoids surfacing that casus inconsistency:
     *  its unitless operand is an ATTRIBUTE, never a literal. Only MAAL — a unitless / unit GEDEELD is a 1/unit rate the Eenheid
     *  model cannot represent (stays Onbekend). */
    public static Resultaat reken(BinOp op, Datatype l, Datatype r, boolean unitlessLinksLiteraal) {
        return reken(op, l, r, unitlessLinksLiteraal, false);
    }

    /** {@code dagsoortEenheidRechts}: the right operand's eenheid names a DECLARED §3.12 dagsoort (the caller
     *  holds the model, this matrix does not) — §6.11 then admits {@code datum ± N <dagsoort>} as a valid
     *  day-by-day date shift alongside the Tabel 2/5 tijdseenheden. */
    public static Resultaat reken(BinOp op, Datatype l, Datatype r, boolean unitlessLinksLiteraal, boolean dagsoortEenheidRechts) {
        if (l.isOnbekend() || r.isOnbekend()) return Resultaat.ok(new Datatype.Onbekend("operand"));
        return switch (op) {
            // §4.1 / §4.2: numeriek±numeriek (units convertible), OR datum ± a numerieke
            // tijdsWAARDE -> datum (Tabel 2/5 scenario 2): the right operand must carry a TIJDseenheid
            // (or a §6.11 dagsoort) — "datum plus 5 km" is a typing ERROR, not a valid date shift (the
            // kern's chronoUnit is the one time-unit classifier, so the resolver rejects exactly what the
            // back-ends cannot execute). The result stays the LEFT type; the §4.1 "datum + sub-day unit ->
            // Datum-tijd" precision nuance is deliberately not modeled here (carded separately if it bites).
            case PLUS, MIN -> {
                String opNaam = op == BinOp.PLUS ? "plus" : "min";
                if (l.isDatum() && r instanceof Datatype.Numeriek rn && rn.heeftEenheid()) {
                    if (dagsoortEenheidRechts || Tijdrekening.chronoUnit(r) != null) yield Resultaat.ok(l);
                    yield Resultaat.fout("datum " + opNaam + " vereist een tijdseenheid, kreeg '"
                            + rn.eenheidOf().afkorting() + "'");
                }
                yield numeriekBinair(l, r, opNaam);
            }
            // §4.3 verminderd met: numeriek only
            case VERMINDERD_MET -> numeriekBinair(l, r, "verminderd met");
            // §4.4/4.5: Numeriek only (Tabel 11/14 list no Percentage row). A unitless * unit keeps the
            // single unit; two units multiply/divide into a COMPOSITE, which the Eenheid model represents
            // only as a numerator over one simple denominator (a rate "€/jr"). The representable combinations
            // are synthesized; the rest (a two-numerator product like "€·m", a "1/jr") stay Onbekend ("no claim").
            case MAAL, GEDEELD_DOOR -> {
                if (!l.isNumeriek() || !r.isNumeriek())
                    yield Resultaat.fout(naam(op) + " vereist numerieke operanden, kreeg " + l.naam() + " en " + r.naam());
                // §4.4: a product gets the SUM of the operands' decimals ("X,X maal X,XX = X,XXX"),
                // not the max — so a 2dec × 2dec product is 4dec and §6.1.2 rightly demands an
                // afronding before it lands in a narrower attribute. §4.5 gedeeld door is an exact
                // fraction (no decimal precision of its own); maxDec under-reports it, but that errs
                // safe (a false negative, never a false positive) and is carded separately.
                int dec = op == BinOp.MAAL ? decimalenVan(l) + decimalenVan(r) : maxDec(l, r);
                Eenheid el = l.eenheidOf(), er = r.eenheidOf();
                if (el == null && er == null)
                    yield Resultaat.ok(new Datatype.Numeriek(false, dec, Datatype.Teken.ONBEPAALD, null));
                if (er == null)
                    yield Resultaat.ok(new Datatype.Numeriek(false, dec, Datatype.Teken.ONBEPAALD, el));
                if (el != null)   // both operands carry a unit -> synthesize the representable composite
                    yield Resultaat.ok(samengesteldeEenheid(op, dec, el, er));
                // el == null, er != null: a unitless × unit. §4.4 Tabel 11 says (none) × Eenheid1 -> Eenheid1, but
                // propagating er for an ATTRIBUTE operand surfaces the known casus unit under-model (carded), so it is
                // done ONLY for a unitless LITERAL left ("2 maal de looptijd(mnd)" = mnd). MAAL only — a unitless / unit
                // GEDEELD is a 1/unit rate the Eenheid model cannot represent (Onbekend). Otherwise Onbekend ("no claim").
                if (op == BinOp.MAAL && unitlessLinksLiteraal)
                    yield Resultaat.ok(new Datatype.Numeriek(false, dec, Datatype.Teken.ONBEPAALD, er));
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
        // §4.1/§4.2/§4.3 (plus/min/verminderd met — the only callers): geheel ± geheel IS geheel, so the
        // result keeps geheel-ness instead of erasing it — a geheel-demanding gate downstream (§5.13
        // elfproef, §4.19 paasdag, min/max alleGeheel) must not falsely reject whole-number arithmetic.
        // An unbounded "getal" operand (geheel=false, dec=0) correctly yields a non-geheel result.
        // decimalen stays maxDec per Tabellen 4/7/10; §6.1.2 checks read decimalen(), never geheel().
        return Resultaat.ok(new Datatype.Numeriek(geheelVan(l) && geheelVan(r), maxDec(l, r), Datatype.Teken.ONBEPAALD, l.eenheidOf()));
    }

    private static boolean geheelVan(Datatype t) {
        return t instanceof Datatype.Numeriek n ? n.geheel()
             : t instanceof Datatype.Percentage p && p.geheel();
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
            // The ordering comparisons deliberately do NOT require equal tijd-precisie: Tabellen 45-52 list
            // only "Datum-tijd" operands with no precision restriction (unlike tijdsduur/eerste/laatste,
            // whose prose demands "dezelfde tijd-precisie") — the asymmetry is the spec's, kept as-is.
            case EERDER, EERDER_OF_GELIJK, LATER, LATER_OF_GELIJK -> {
                if (!l.isDatum() || !r.isDatum())
                    yield Resultaat.fout("eerder/later vereist datum-operanden, kreeg " + l.naam() + " en " + r.naam());
                yield Resultaat.ok(new Datatype.Booleaan());
            }
            case GELIJK, ONGELIJK -> {
                if (!zelfdeFamilie(l, r))
                    yield Resultaat.fout("gelijk/ongelijk vereist operanden van hetzelfde datatype, kreeg " + l.naam() + " en " + r.naam());
                String p = datumPrecisieFout(l, r, "gelijk/ongelijk vereist");
                if (p != null) yield Resultaat.fout(p);
                String e = enumDomeinFout(l, r, "gelijk/ongelijk vereist");
                if (e != null) yield Resultaat.fout(e);
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
     *  because the spec sanctions it, but to accommodate a known casus inconsistency:
     *  a bedrag declared unitless yet bounded by "0 €" (a convertible pair like mnd/jr is
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
            return "begrenzing: eenheden '" + eb.weergave() + "' en '" + eg.weergave()
                    + "' zijn niet in elkaar om te rekenen";
        return null;
    }

    /** §4.12/4.13 Tabel 22/23 — minimale/maximale waarde van (getalaggregatiefunctie): every
     *  operand must be a numerieke expressie, and "wanneer eenheden worden gebruikt, deze in
     *  elkaar om te rekenen zijn." Strictly Numeriek, NOT getalachtig: Tabel 22/23 carry only a
     *  Numeriek operand row, whereas the §5.1-5.4 ordinal-comparison tables (Tabel 32/34/39/41)
     *  carry an explicit Percentage row — the spec admits Percentage exactly where it lists it, so
     *  min/max excludes it (a Percentage min/max is a missed-diagnostic false-negative otherwise).
     *  The unit rule is the STRICT §4 one (unlike the lenient {@link #begrenzing}): all operands
     *  unitless, or all unit-bearing and pairwise inter-convertible — no casus quirk forces leniency
     *  here (its lone max is single-operand). Onbekend operands make no claim (skipped); a single
     *  operand is trivially well-typed. Returns the diagnostic, or null when ok. */
    public static String getalAggregatie(String functie, java.util.List<Datatype> operanden) {
        Eenheid ref = null; boolean refGezet = false;
        for (Datatype d : operanden) {
            if (d.isOnbekend()) continue;
            if (!d.isNumeriek())
                return functie + " vereist numerieke operanden, kreeg " + d.naam();
            Eenheid e = d.eenheidOf();
            if (!refGezet) { ref = e; refGezet = true; continue; }
            if ((ref == null) != (e == null))
                return functie + ": als één operand een eenheid heeft, moeten alle operanden er een hebben";
            if (ref != null && !ref.convertibleNaar(e))
                return functie + ": eenheden '" + ref.weergave() + "' en '" + e.weergave()
                        + "' zijn niet in elkaar om te rekenen";
        }
        return null;
    }

    /** §4.14/4.15 Tabel 24/25 — eerste/laatste van (datumaggregatiefunctie): every operand must
     *  be a datumexpressie, and "alle expressies dezelfde tijd-precisie bevatten" — modelled as
     *  the same concrete date kind (all {@link Datatype.DatumInDagen} or all {@link
     *  Datatype.DatumTijd}). The equal-precision rule is normative and twice-stated (§4.14/4.15
     *  prose AND §5.8.4: "Deze moeten namelijk allemaal gelijk zijn") with no coercion path — so
     *  this is strict equality. NOTE a spec tension: the §5.8.4 "Bevestigingstijdstip" worked
     *  example mixes a Datum-tijd operand with a Datum-in-dagen one, violating its own rule; we
     *  follow the normative prose over the inconsistent illustration (the example's standalone
     *  corpus form resolves clean only because its operands are Onbekend "Daarbij geldt"
     *  variables). Onbekend = no claim. Returns the diagnostic, or null when ok. */
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
        String p = datumPrecisieFout(doel, waarde, "toekenning vereist");
        if (p != null) return p;
        String e = enumDomeinFout(doel, waarde, "toekenning vereist");
        if (e != null) return e;
        if (!doel.isGetalachtig()) return null;
        String eenheidFout = eenhedenCompatibel(doel, waarde, "toekenning");
        if (eenheidFout != null) return eenheidFout;
        // §6.1.2: a result may be assigned only when its decimal count does not exceed the
        // attribute's; a higher-precision result must first be explicitly rounded (§6.1.3). The
        // value's precision already reflects any afronding wrapper (Expr.Afgerond.type()).
        // An UNBOUNDED target (§13.3.3 form (c) "getal") has no precision ceiling — it accepts any
        // number of decimals — so the check does not apply. Without this, an unbounded target reads
        // decimalenVan == 0 (the sentinel) and is wrongly treated like a "geheel getal" (cap 0),
        // rejecting any decimal value as over-precise (the false positive p33 reproduced).
        if (doel.onbeperkt()) return null;
        int dv = decimalenVan(waarde), dd = decimalenVan(doel);
        if (dv > dd)
            return "het toe te kennen resultaat heeft meer decimalen (" + dv + ") dan het attribuut toelaat ("
                    + dd + "); rond het resultaat eerst expliciet af (§6.1.2)";
        return null;
    }

    /** §6.1.2 for a §13.4.10 verdeling share, consistent with {@link #toekenning} (a verdeling assigns a share
     *  to an attribute, so the same precision rule governs it). The share's decimal count follows the same
     *  rekentabel model as the equivalent expression: equal-shares is {@code total / count} — a division, and
     *  {@code count} is a whole count, so {@code maxDec} = the total's decimals; naar rato is
     *  {@code total · weight / Σweight} — the multiply contributes {@code total+weight} decimals and the
     *  division by {@code Σweight} does not raise that. An afronding to {@code afrDec} decimals caps the share
     *  at {@code afrDec} (exactly as an {@code Expr.Afgerond} wrapper caps the value for {@link #toekenning}),
     *  so a rounded verdeling fits a bounded target. An unbounded ({@code getal}) target has no ceiling.
     *  Returns the §6.1.2 message when the share is over-precise, else null. For all-integer operands
     *  the share is 0 decimals and this is a no-op — it only flags a decimal-typed bron/weight
     *  distributed into a narrower target, the case the ordinary {@code regel} assignment already flags. */
    public static String verdeelToekenning(Datatype doel, Datatype bron, Datatype gewicht, Integer afrDec) {
        // An Onbekend bron/doel carries no type claim — the assignment check no-ops.
        if (doel == null || bron == null || doel.isOnbekend() || bron.isOnbekend())
            return null;
        // §13.4.10 the share is assigned to the doel like a regel assignment, so it must satisfy the SAME
        // family + unit rule as toekenning. The FAMILY check runs first, BEFORE the getalachtig guard, so a
        // numeric verdeelplafond distributed over a non-numeric recipient (a Numeriek bron into a Tekst/Datum
        // doel) is flagged as the family mismatch it is — exactly the fout the ordinary regel raises — instead
        // of slipping through to an uncompilable numeric setter.
        if (!zelfdeFamilie(doel, bron))
            return "kan een waarde van type " + bron.naam() + " niet verdelen over een " + doel.naam();
        // Same family but not getalachtig (e.g. both Datum/Tekst) — no unit/precision rule applies.
        if (!doel.isGetalachtig()) return null;
        // The share carries the BRON's eenheid: equal-shares (total/count) and naar rato (total·w/Σw) both keep
        // it — the count is a plain number and the weight ratio w/Σw is dimensionless, so the weight's unit
        // cancels. An inconvertible unit (€ distributed over a metre target) is the same fout regel raises.
        String eenheidFout = eenhedenCompatibel(doel, bron, "");   // RuleChecker prepends "verdeling: "
        if (eenheidFout != null) return eenheidFout;
        if (doel.onbeperkt()) return null;   // an unbounded (getal) target has no precision ceiling
        int share = afrDec != null ? afrDec
                : decimalenVan(bron) + (gewicht != null ? decimalenVan(gewicht) : 0);
        int dd = decimalenVan(doel);
        return share > dd
                ? "het toe te kennen aandeel heeft meer decimalen (" + share + ") dan het attribuut toelaat ("
                  + dd + "); rond de verdeling eerst expliciet af (§6.1.2/§9.7.4)"
                : null;
    }

    // -------- helpers --------

    /** §4.4/4.5 result datatype for a maal/gedeeld of two unit-bearing operands, in the cases the Eenheid
     *  model can represent (a numerator over one simple denominator); the rest yield Onbekend ("no claim").
     *  MAAL: a rate times its (convertible) denominator-unit cancels to the numerator ("€/jr maal jr = €");
     *  two plain numerators ("€ maal m") are not representable. GEDEELD: two plain units divide to a rate
     *  ("€ gedeeld door jr = €/jr"), or to a dimensionless number when the units are convertible ("€ gedeeld
     *  door € = een getal"); a rate divisor (its result needs a second denominator or "1/jr") stays Onbekend. */
    private static Datatype samengesteldeEenheid(BinOp op, int dec, Eenheid el, Eenheid er) {
        if (op == BinOp.MAAL) {
            if (el.noemer() != null && er.noemer() == null && er.convertibleNaar(el.noemer()))
                return new Datatype.Numeriek(false, dec, Datatype.Teken.ONBEPAALD, el.teller());
            if (er.noemer() != null && el.noemer() == null && el.convertibleNaar(er.noemer()))
                return new Datatype.Numeriek(false, dec, Datatype.Teken.ONBEPAALD, er.teller());
            // §4.4: two plain units multiply to a PRODUCT unit ("€ maal m = €·m"). An opaque composite (no
            // canonical), so it is convertible only to an identical product — a €·m result assigned to a € target
            // now flags a unit mismatch (it used to degrade to Onbekend and pass silently). A rate factor (a
            // second denominator / "€·m/jr") is not representable, so it stays Onbekend.
            if (el.noemer() == null && er.noemer() == null)
                return new Datatype.Numeriek(false, dec, Datatype.Teken.ONBEPAALD, Eenheid.product(el, er));
            return new Datatype.Onbekend("samengestelde eenheid");
        }
        if (el.noemer() == null && er.noemer() == null)   // GEDEELD: only two plain units are representable
            return el.convertibleNaar(er)
                    ? new Datatype.Numeriek(false, dec, Datatype.Teken.ONBEPAALD, null)                       // €/€ -> getal
                    : new Datatype.Numeriek(false, dec, Datatype.Teken.ONBEPAALD, Eenheid.samengesteld(el, er)); // €/jr
        return new Datatype.Onbekend("samengestelde eenheid");
    }

    /** Same datatype family for gelijk/ongelijk/toekenning. Numeriek and Percentage are
     *  distinct here (the §5.3/5.6 and §6 tables list them as separate rows); the two date
     *  kinds count as one (the spec models a single date/time datatype with precision). */
    private static boolean zelfdeFamilie(Datatype a, Datatype b) {
        if (a.isNumeriek() && b.isNumeriek()) return true;
        if (a.isPercentage() && b.isPercentage()) return true;
        if (a.isDatum() && b.isDatum()) return true;
        return a.getClass().equals(b.getClass());
    }

    /** Mixed tijd-precisie (Datum-in-dagen vs Datum-en-tijd) in gelijk/ongelijk/toekenning — a CHOSEN
     *  strictness, not a table fact: Tabel 36/61 rij 4 list only "Datum-tijd" with no precision split,
     *  but a mixed toekenning generates a type-broken setter (a LocalDateTime into a LocalDate property)
     *  and a mixed gelijk compares values that can never be equal — both silently-dead outcomes. The
     *  language's own conversion forms are the escape hatch (documented in grammar-divergences). The
     *  precision test is the same getClass() equality tijdsduur/eerste/laatste already use. */
    private static String datumPrecisieFout(Datatype a, Datatype b, String wat) {
        if (a.isDatum() && b.isDatum() && !a.getClass().equals(b.getClass()))
            return wat + " dezelfde tijd-precisie, kreeg " + a.naam() + " en " + b.naam()
                    + "; converteer expliciet (bijv. 'de datum met jaar, maand en dag uit …')";
        return null;
    }

    /** Tabel 36/43 rij 7 (vergelijking) and Tabel 61 rij 7 (toekenning): both sides must be the SAME
     *  enumeration — two attributes of DIFFERENT declared domains used to pass silently on class equality
     *  (a comparison that could only be label-coincidence, an assignment that puts a foreign label in). */
    private static String enumDomeinFout(Datatype a, Datatype b, String wat) {
        if (a instanceof Datatype.Enumeratie ea && b instanceof Datatype.Enumeratie eb
                && !ea.domein().equals(eb.domein()))
            return wat + " dezelfde enumeratie, kreeg " + ea.naam() + " en " + eb.naam() + " (rij 7)";
        return null;
    }

    /** Unit rule (§4 note): both unitless, or both present and inter-convertible. {@code waar} is the
     *  diagnostic context prefix; an EMPTY {@code waar} omits it (for a caller that adds its own prefix,
     *  e.g. the verdeling check whose RuleChecker wrapper already prepends "verdeling: "). */
    private static String eenhedenCompatibel(Datatype l, Datatype r, String waar) {
        Eenheid el = l.eenheidOf(), er = r.eenheidOf();
        if (el == null && er == null) return null;
        String pre = waar.isEmpty() ? "" : waar + ": ";
        if (el == null || er == null)
            return pre + "als één operand een eenheid heeft, moeten beide er een hebben";
        if (!el.convertibleNaar(er))
            return pre + "eenheden '" + el.weergave() + "' en '" + er.weergave() + "' zijn niet in elkaar om te rekenen";
        return null;
    }

    /** The declared decimal count of a getalachtig datatype (0 for anything else). */
    private static int decimalenVan(Datatype t) {
        if (t instanceof Datatype.Numeriek n) return n.decimalen();
        if (t instanceof Datatype.Percentage p) return p.decimalen();
        return 0;
    }

    private static int maxDec(Datatype l, Datatype r) {
        return Math.max(decimalenVan(l), decimalenVan(r));
    }

    private static String naam(BinOp op) {
        return switch (op) {
            case PLUS -> "plus"; case MIN -> "min"; case VERMINDERD_MET -> "verminderd met";
            case MAAL -> "maal"; case GEDEELD_DOOR -> "gedeeld door"; case TOT_DE_MACHT -> "tot de macht";
        };
    }
}
