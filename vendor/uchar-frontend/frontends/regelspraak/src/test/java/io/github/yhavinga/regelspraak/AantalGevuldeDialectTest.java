package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The FILLED-VALUE COUNT's producer route — the uchar dialect extension
 * "het aantal gevulde waarden van &lt;setlezing&gt;" (decision G9, 2026-09-19;
 * {@code grammar/DIVERGENCES.md} §6). The kind landed on the wire, in the verifier, in {@code ref},
 * in the ASP backend and in both Drools branches before any frontend could write it; this is the
 * sentence that closes that gap.
 *
 * <p><b>Measured before the extension, on the very sentence the corpus golden now carries.</b> It
 * PARSED — that is the danger, and it is why the card's inertness demand is a measurement and not a
 * reassurance. "het aantal" bound as the INSTANCE count and swallowed "gevulde waarden van het
 * gewicht" as its element word; the count bound no element, the aggregation became an
 * {@link Expr.Onopgelost} marker with a WARNING, and the document still reached disk with a marker
 * where the author wrote a fold. The new whole-phrase token takes the longer match, so the same
 * sentence now builds the fold it names.
 *
 * <p><b>Why a whole-phrase token reserves nothing.</b> Unlike {@code Uitzonderingen}/{@code
 * ongeordend}, this extension adds no name word: 'gevulde' and 'waarden' keep lexing as LOWER_ID
 * everywhere, and only the exact five-word sequence changes meaning. The one shape that DOES shift
 * is pinned below, and it shifts to a LOUD refusal rather than to a different answer.
 */
class AantalGevuldeDialectTest {

    /** The corpus golden's model (corpus/golden/jutterswet-gevuldetelling): one Jutter to many
     *  Vondsten, the folded attribute carrying a unit so the unitless RESULT is a real claim. */
    static final String MODEL = """
        Eenheidsysteem Gewicht
            het kilogram (mv: kilogrammen) kg

        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal aangemelde vondsten Numeriek (niet-negatief geheel getal);
            het aantal gewogen vondsten Numeriek (niet-negatief geheel getal);

        Objecttype de Vondst (mv: Vondsten)
            het gewicht (mv: gewichten) Numeriek (getal met 2 decimalen) met eenheid kg;
            de vinder Tekst;

        Feittype vondstaanmelding
            de aanmelder Jutter
            de vondst (mv: vondsten) Vondst
        Eén aanmelder heeft meerdere vondsten
        """;

    private static RegelSpraakModel resolve(String regels) { return Resolver.resolve(MODEL + regels); }

    private static Expr.Functie vouw(RegelSpraakModel m) {
        return (Expr.Functie) m.resolvedRegels.get(0).waarde();
    }

    // ---------------- the producer route ----------------

    @Test void de_dialectzin_bouwt_de_gevulde_telling() {
        RegelSpraakModel m = resolve("""

            Regel aantal gewogen vondsten
                geldig altijd
                    Het aantal gewogen vondsten van een Jutter moet berekend worden als het aantal gevulde waarden van het gewicht van alle vondsten van de Jutter.
            """);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        Expr.Functie f = vouw(m);
        assertEquals(Expr.FunctieKind.AANTAL_GEVULDE, f.kind());
        assertEquals(1, f.argumenten().size(), "de vouw draagt precies één argument (WF-037)");
        Expr.AttribuutRef bron = (Expr.AttribuutRef) f.argumenten().get(0);
        assertTrue(bron.set(), "de bron is geen setlezing");
        assertEquals("gewicht", bron.attribuut());
        assertEquals("Vondst", f.elementObjecttype().naam);
        // no facet is even writable: the grammar gives the phrase one operand and no slot
        assertNull(f.leegwaarde(), "een telling draagt nooit een leegwaarde (WF-037, 'sum only')");
        assertNull(f.dimensieSelectie(), "dimensieselectie is ill-formed op deze soort (besluit G7)");
        assertNull(f.granulariteit());
    }

    @Test void de_telling_erft_de_eenheid_van_haar_bron_niet() {
        // RUNTIME-SEMANTICS ch. 3.4c: the result is a whole, UNITLESS cardinality whatever the
        // folded attribute measures. The source here is kg, so a pass-through would show.
        RegelSpraakModel m = resolve("""

            Regel aantal gewogen vondsten
                geldig altijd
                    Het aantal gewogen vondsten van een Jutter moet berekend worden als het aantal gevulde waarden van het gewicht van alle vondsten van de Jutter.
            """);
        Datatype.Numeriek t = (Datatype.Numeriek) vouw(m).type();
        assertTrue(t.geheel(), "een kardinaliteit is een geheel getal");
        assertEquals(0, t.decimalen());
        assertEquals(Datatype.Teken.NIET_NEGATIEF, t.teken());
        assertNull(t.eenheid(), "de telling gaf de kg van haar bron door");
        assertEquals("kg", ((Datatype.Numeriek) ((Expr.AttribuutRef) vouw(m).argumenten().get(0)).type())
                .eenheid().afkorting(), "de BRON hoort de eenheid wel te dragen");
    }

    @Test void de_setlezing_onder_de_telling_is_geen_setlezingfout() {
        // The coupling with RuleChecker.VOUWSOORTEN, stated as a property: the source is a SET
        // reading, and the set fence admits one on a value fold's source alone. Were the kind missing from
        // that set, this very sentence would raise the fence's ERROR and never reach disk — which is
        // the divergence VouwsoortenGrensTest exists to catch.
        RegelSpraakModel m = resolve("""

            Regel aantal gewogen vondsten
                geldig altijd
                    Het aantal gewogen vondsten van een Jutter moet berekend worden als het aantal gevulde waarden van het gewicht van alle vondsten van de Jutter.
            """);
        assertTrue(m.diagnostics.stream().noneMatch(d -> d.message().startsWith("setlezing buiten een vouwbron")),
                () -> "de vouwbron wordt als setlezing buiten een vouw gelezen: " + m.diagnostics);
        assertTrue(IrEmitter.emit(m).contains("\"kind\": \"AANTAL_GEVULDE\""), "de soort bereikt de wire niet");
    }

    @Test void de_toets_is_typeloos_en_telt_ook_teksten() {
        // Decision G8: the test is presence and presence is typeless. A getalAggregatie check on this
        // kind would refuse the sentence below and undo the decision in silence.
        RegelSpraakModel m = resolve("""

            Regel aantal gewogen vondsten
                geldig altijd
                    Het aantal gewogen vondsten van een Jutter moet berekend worden als het aantal gevulde waarden van de vinder van alle vondsten van de Jutter.
            """);
        assertTrue(m.diagnostics.isEmpty(), () -> "een tekstbron wordt geweigerd: " + m.diagnostics);
        assertEquals(Expr.FunctieKind.AANTAL_GEVULDE, vouw(m).kind());
        assertEquals("vinder", ((Expr.AttribuutRef) vouw(m).argumenten().get(0)).attribuut());
    }

    @Test void een_rekenkundige_staart_bindt_buiten_de_telling() {
        // primaireExpressie, for the same precedence reason the §13.4.17.47 telling gives: the author
        // means (count) + 1, never a count of an arithmetic expression.
        RegelSpraakModel m = resolve("""

            Regel aantal gewogen vondsten
                geldig altijd
                    Het aantal gewogen vondsten van een Jutter moet berekend worden als het aantal gevulde waarden van het gewicht van alle vondsten van de Jutter plus 1.
            """);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        Expr.Binair b = (Expr.Binair) m.resolvedRegels.get(0).waarde();
        assertEquals(Expr.BinOp.PLUS, b.op());
        assertEquals(Expr.FunctieKind.AANTAL_GEVULDE, ((Expr.Functie) b.links()).kind());
    }

    @Test void de_die_subselectie_versmalt_de_bijdragers() {
        // ch. 3.4a's per-contributor narrowing reaches this kind through the shared aggregatieFilter
        // path; without it a subselectie would be dropped and the count would answer over everything.
        RegelSpraakModel m = resolve("""

            Objecttype de Vondst (mv: Vondsten)
                is gekeurd kenmerk (bijvoeglijk);

            Regel aantal gewogen vondsten
                geldig altijd
                    Het aantal gewogen vondsten van een Jutter moet berekend worden als het aantal gevulde waarden van het gewicht van alle vondsten van de Jutter die gekeurd zijn.
            """);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        assertNotNull(vouw(m).filter(), "de 'die'-subselectie is stil weggevallen");
    }

    // ---------------- refusal fixtures ----------------

    /** WF-037's filled-count shape, in the frontend's own words. Pinned whole: the sentence names the
     *  CONSTRUCT, not just the position, so it stays a witness of this kind and no other. */
    static final String NIET_SET =
            "het aantal gevulde waarden van vereist een setlezing als bron: deze vouw leest een WAARDE per "
            + "bijdrager, dus haar bron noemt de leden ('… van alle <meervoud> van …') — 'het aantal gevulde "
            + "waarden van het gewicht van de Vondst' noemt er geen, en het gevulde aantal van één eigen "
            + "waarde is 0 of 1 (WF-037, IR-SPEC ch. 4.3)";

    @Test void een_niet_set_bron_wordt_luid_geweigerd() {
        RegelSpraakModel m = resolve("""

            Regel aantal gewogen vondsten
                geldig altijd
                    Het aantal gewogen vondsten van een Jutter moet berekend worden als het aantal gevulde waarden van het gewicht van de Vondst.
            """);
        List<Diagnostic> ds = m.diagnostics.stream().filter(d -> d.message().contains("WF-037")).toList();
        assertEquals(1, ds.size(), () -> "verwacht één WF-037-melding, kreeg: " + m.diagnostics);
        assertEquals(NIET_SET, ds.get(0).message());
        assertEquals(Severity.ERROR, ds.get(0).severity());
        // fail-fast: an ERROR stops irModus writing, so the document the verifier rejects under
        // WF-037 is never produced at all
        assertFalse(m.errors().isEmpty(), "de weigering is geen fout en het document bereikt de schijf");
    }

    @Test void een_ongedeclareerd_lid_blijft_de_eerlijke_marker() {
        // The declared-names-only rule the other six value folds already carry, now reached by the seventh:
        // a source naming no declared member markers with a WARNING instead of folding a getter that
        // does not exist.
        RegelSpraakModel m = resolve("""

            Regel aantal gewogen vondsten
                geldig altijd
                    Het aantal gewogen vondsten van een Jutter moet berekend worden als het aantal gevulde waarden van de kleur van alle vondsten van de Jutter.
            """);
        assertEquals(List.of(Severity.WARNING), m.diagnostics.stream().map(Diagnostic::severity).toList());
        assertTrue(m.diagnostics.get(0).message().contains("gedeclareerd attribuut van Vondst"),
                () -> m.diagnostics.toString());
        assertInstanceOf(Expr.Onopgelost.class, m.resolvedRegels.get(0).waarde());
    }

    @Test void het_meerstapsbereik_wordt_een_bronpad_en_valt_niet_stil_weg() {
        // MEASURED rather than assumed: the stacked range does NOT hit keurVouwbron's nested-step
        // refusal, because WF-102's resolution runs first and carries the range as a bronPad. That is
        // the wire form ch. 3.4b's flat union needs, and `corpus/golden/jutterswet-gevuldepad` pins
        // what it means; what matters here is that the SENTENCE reaches it, and that the outer step
        // never disappears into a one-relation fold in silence.
        RegelSpraakModel m = resolve("""

            Objecttype de Boot (mv: Boten)
                het aantal gewogen vondsten Numeriek (niet-negatief geheel getal);

            Feittype bevrachting
                de vrachtvaarder Boot
                de bevrachter (mv: bevrachters) Jutter
            Eén vrachtvaarder heeft meerdere bevrachters

            Regel aantal gewogen vondsten
                geldig altijd
                    Het aantal gewogen vondsten van een Boot moet berekend worden als het aantal gevulde waarden van het gewicht van alle vondsten van alle bevrachters van de Boot.
            """);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        Expr.Functie f = vouw(m);
        assertEquals(Expr.FunctieKind.AANTAL_GEVULDE, f.kind());
        String json = IrEmitter.emit(m);
        assertTrue(json.contains("\"bronPad\""), "het meerstapsbereik gaat niet als bronPad de wire op:\n" + json);
        assertFalse(json.contains("\"bronRol\""), "het bereik gaat als bronRol EN bronPad de wire op");
    }

    // ---------------- the maximal-munch risk, pinned ----------------

    /** The adversarial name the card's risk note describes: a ROLE whose plural is literally
     *  "gevulde waarden". On such a document the extension DOES shift the reading — measured
     *  2026-09-20, the sentence below emitted a clean instance count before it and refuses after.
     *
     *  <p>That shift is the whole reason the inertness is MEASURED over every reachable flat corpus
     *  ({@code grammar/DIVERGENCES.md} §6) instead of argued. What this fixture adds is the bound on
     *  the damage: the shifted sentence does not answer a different number, it stops — the operand
     *  the phrase leaves behind ("de Jutter") is not a set reading, so WF-037's arm refuses it at the
     *  ERROR level and the document never reaches disk. A silent re-reading is the outcome the
     *  measurement rules out; a loud one is the outcome the grammar bounds. */
    static final String BOTSENDE_NAAM = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal peilingen Numeriek (niet-negatief geheel getal);

        Objecttype de Waarde (mv: Waarden)
            de meting Numeriek (getal met 2 decimalen);

        Feittype waardering
            de waardeerder Jutter
            de gevulde waarde (mv: gevulde waarden) Waarde
        Eén waardeerder heeft meerdere gevulde waarden

        Regel aantal peilingen
            geldig altijd
                Het aantal peilingen van een Jutter moet berekend worden als het aantal gevulde waarden van de Jutter.
        """;

    @Test void een_botsende_rolnaam_verschuift_luid_en_niet_stil() {
        RegelSpraakModel m = Resolver.resolve(BOTSENDE_NAAM);
        List<Diagnostic> ds = m.diagnostics.stream().filter(d -> d.message().contains("WF-037")).toList();
        assertEquals(1, ds.size(), () -> "de botsing verschuift zonder melding: " + m.diagnostics);
        assertEquals(Severity.ERROR, ds.get(0).severity());
        assertTrue(ds.get(0).message().contains("'het aantal gevulde waarden van de Jutter'"),
                () -> ds.get(0).message());
        assertFalse(m.errors().isEmpty(), "het verschoven document bereikt de schijf");
    }
}
