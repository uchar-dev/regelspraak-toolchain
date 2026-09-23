package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WF-037's COUNT-ARITY arm ({@code spec/IR-SPEC.md} ch. 4.3, maintainer decision 2026-09-20): a count
 * folds INSTANCES and has no LISTED form, so an {@code AANTAL} carrying two or more operands is
 * ill-formed on the wire.
 *
 * <p><b>This suite exists because the frontend's grammar spells a form the source language does not,
 * and the gap was a LEAK rather than a harmless superset.</b> {@code functieAanroep}'s
 * {@code aantalFunc} alternative reads
 * {@code HET AANTAL_WORD primaireExpressie ( COMMA primaireExpressie )* ( EN primaireExpressie )?} —
 * an operand LIST, where the EBNF (§13.4.17.46) gives the count one operand and §5.8.1 ranges it over
 * instances. {@code ExpressieBouwer.args} builds every child as a separate argument, so the list
 * reached the wire. Measured 2026-09-20, before the arm below landed, three sentences emitted with no
 * error at all: the {@code en} spelling and the {@code comma} spelling both with
 * {@code diagnostics: []}, and the element-binding spelling with a WARNING that does not block —
 * documents the verifier rejects under WF-037 (the third also under WF-034).
 *
 * <p><b>Why the grammar keeps the list.</b> The alternative is a registered divergence
 * ({@code grammar/DIVERGENCES.md}) and a MIRRORED fact: deleting it would change what the parser
 * accepts, and the {@code ( EN … )?} tail participates in how a trailing conjunction binds inside a
 * larger sentence — a parse change, not a diagnostic change, with 64 byte-identical emitter goldens
 * downstream of it. The fence therefore stands where the node is BUILT, which is where the arity is
 * known, and the grammar keeps mirroring what it mirrors. This follows {@link AantalSetbronTest}
 * exactly: there too the frontend's fence moved and its grammar did not.
 *
 * <p><b>What stays clean</b> is pinned here too, because the arm's whole risk is over-reach: the
 * one-argument instance count (109 of them across the four corpus zones) and the LISTED spelling of a
 * VALUE fold, which is legitimate v0 (§5.8.3) and keeps its operand list.
 */
class AantalOpgesomdTest {

    static final String MODEL = """
        Eenheidsysteem Gewicht
            het kilogram (mv: kilogrammen) kg

        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal gewogen posten Numeriek (niet-negatief geheel getal);
            de zwaarste post Numeriek (getal met 2 decimalen) met eenheid kg;
            de gewogen vangst Numeriek (getal met 2 decimalen) met eenheid kg;
            de gewogen bijvangst Numeriek (getal met 2 decimalen) met eenheid kg;
            het gewogen wrakhout Numeriek (getal met 2 decimalen) met eenheid kg;

        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (getal met 2 decimalen) met eenheid kg;

        Objecttype het Kavel (mv: Kavels)
            de grootte Numeriek (getal met 2 decimalen);

        Feittype vondstaanmelding
            de aanmelder Jutter
            de vondst (mv: vondsten) Vondst
        Eén aanmelder heeft meerdere vondsten

        Feittype kaveltoewijzing
            de kavelhouder Jutter
            het kavel (mv: kavels) Kavel
        Eén kavelhouder heeft meerdere kavels
        """;

    /** The leak, {@code en} spelling: two ordinary own reads under a count. */
    static final String EN_SPELLING = """

        Regel aantal gewogen posten
            geldig altijd
                Het aantal gewogen posten van een Jutter moet berekend worden als het aantal de gewogen vangst van de Jutter en de gewogen bijvangst van de Jutter.
        """;

    /** The same arm at the open end of the bound: the comma spelling, three operands. */
    static final String KOMMA_SPELLING = """

        Regel aantal gewogen posten
            geldig altijd
                Het aantal gewogen posten van een Jutter moet berekend worden als het aantal de gewogen vangst van de Jutter, de gewogen bijvangst van de Jutter en het gewogen wrakhout van de Jutter.
        """;

    /** The third measured route, and the one that shows the arm must not read the element type: the
     *  first operand BINDS an element ("vondsten"), so the node carries an {@code elementObjecttype}
     *  beside its operand list. Before this arm it emitted with a WARNING only — which does not block
     *  — although the verifier rejects it under WF-037 and WF-034 both. */
    static final String ELEMENT_SPELLING = """

        Regel aantal gewogen posten
            geldig altijd
                Het aantal gewogen posten van een Jutter moet berekend worden als het aantal vondsten van de Jutter en kavels van de Jutter.
        """;

    /** The counter-proof the arm must NOT touch: v0's own one-argument instance count. */
    static final String GEWONE_TELLING = """

        Regel aantal gewogen posten
            geldig altijd
                Het aantal gewogen posten van een Jutter moet berekend worden als het aantal vondsten van de Jutter.
        """;

    /** The other counter-proof: the LISTED spelling of a VALUE fold, legitimate v0 (§5.8.3). The arm
     *  is keyed on the count's kind alone, so this operand list stays where it is. */
    static final String OPGESOMDE_WAARDEVOUW = """

        Regel zwaarste post
            geldig altijd
                De zwaarste post van een Jutter moet berekend worden als de maximale waarde van de gewogen vangst van de Jutter en de gewogen bijvangst van de Jutter.
        """;

    private static List<Diagnostic> wf037(String regels) {
        RegelSpraakModel m = Resolver.resolve(MODEL + regels);
        return m.diagnostics.stream().filter(d -> d.message().contains("WF-037")).toList();
    }

    @Test void de_opgesomde_telling_met_en_wordt_geweigerd() {
        List<Diagnostic> ds = wf037(EN_SPELLING);
        assertEquals(1, ds.size(), () -> "verwacht één WF-037-melding, kreeg: " + ds);
        assertEquals(Severity.ERROR, ds.get(0).severity());
        // the operand count is INTERPOLATED, so a bound that silently became an equality would be
        // visible here rather than only at three operands
        assertTrue(ds.get(0).message().contains("noemt 2 operanden"),
                "de melding noemt het operandental niet: " + ds.get(0).message());
        assertTrue(ds.get(0).message().contains("geen opgesomde vorm"),
                "de melding noemt de geweigerde vorm niet: " + ds.get(0).message());
    }

    @Test void de_komma_spelling_valt_op_dezelfde_arm() {
        List<Diagnostic> ds = wf037(KOMMA_SPELLING);
        assertEquals(1, ds.size(), () -> "verwacht één WF-037-melding, kreeg: " + ds);
        assertEquals(Severity.ERROR, ds.get(0).severity());
        assertTrue(ds.get(0).message().contains("noemt 3 operanden"),
                "de melding telt de komma-operanden niet mee: " + ds.get(0).message());
    }

    @Test void de_spelling_met_elementtype_valt_ook() {
        // The arm reads the KIND and the operand count, never elementObjecttype: an element type
        // beside an operand list is the same listed form with a second claim on one node. The wire
        // rule draws the boundary in exactly this place, and so does this fence.
        List<Diagnostic> ds = wf037(ELEMENT_SPELLING);
        assertEquals(1, ds.size(), () -> "verwacht één WF-037-melding, kreeg: " + ds);
        assertEquals(Severity.ERROR, ds.get(0).severity());
    }

    @Test void de_weigering_valt_op_de_zin_zelf() {
        // the line is the sentence's own, so a reader finds the count by the number alone — the same
        // contract AantalSetbronTest pins for the set fence
        Diagnostic d = wf037(EN_SPELLING).get(0);
        assertEquals("Het aantal gewogen posten van een Jutter moet berekend worden als het aantal "
                        + "de gewogen vangst van de Jutter en de gewogen bijvangst van de Jutter.",
                (MODEL + EN_SPELLING).lines().toList().get(d.line() - 1).strip());
    }

    @Test void de_ill_gevormde_telling_bereikt_de_wire_niet() {
        // The leak, stated as the property that closes it: an ERROR stops irModus writing at all, so
        // the document the verifier rejects under WF-037 is never produced. Before this arm the same
        // source emitted with `diagnostics: []`. Since WF-113 (a) the emitter refuses the model by
        // itself as well, so no emission route reaches the wire with it.
        RegelSpraakModel m = Resolver.resolve(MODEL + EN_SPELLING);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("geen opgesomde vorm")),
                () -> "de opgesomde telling levert geen WF-037-fout op: " + m.diagnostics);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> IrEmitter.emit(m));
        assertTrue(e.getMessage().contains("geen opgesomde vorm"), e::getMessage);
    }

    @Test void de_gewone_telling_blijft_schoon() {
        // The one-argument instance count is the form the arm exists to leave alone: 109 of them sit
        // in the four corpus zones and every rgs golden that counts writes this shape.
        RegelSpraakModel m = Resolver.resolve(MODEL + GEWONE_TELLING);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek op de gewone telling: " + m.diagnostics);
        Expr.Functie f = (Expr.Functie) m.resolvedRegels.get(0).waarde();
        assertEquals(Expr.FunctieKind.AANTAL, f.kind());
        assertEquals(1, f.argumenten().size());
        assertNotNull(f.elementObjecttype(), "de gewone telling bindt geen elementtype meer");
    }

    @Test void de_opgesomde_waardevouw_blijft_schoon() {
        // §5.8.3: a VALUE fold's operand list is legitimate and stays. The arm keys on the count's
        // kind alone, which is what makes this the sharpest counter-proof — same sentence shape,
        // same two operands, a different kind.
        RegelSpraakModel m = Resolver.resolve(MODEL + OPGESOMDE_WAARDEVOUW);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek op de waardevouw: " + m.diagnostics);
        Expr.Functie f = (Expr.Functie) m.resolvedRegels.get(0).waarde();
        assertEquals(Expr.FunctieKind.MAXIMUM, f.kind());
        assertEquals(2, f.argumenten().size(), "de opgesomde waardevouw verloor haar operandenlijst");
    }
}
