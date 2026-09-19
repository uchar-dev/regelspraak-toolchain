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
 * WF-096's KIND half (`spec/IR-SPEC.md` ch. 4.3, maintainer decision 2026-09-19): a set reading is
 * admissible as the source of a single-argument VALUE fold and nowhere else. A count folds the
 * CONTRIBUTORS themselves — `RUNTIME-SEMANTICS.md` ch. 3.4a says so in as many words, ch. 3.4b
 * restates it for the union — so under {@code AANTAL} a set reading names a collection the node does
 * not consume, and the wire refuses the form.
 *
 * <p><b>This suite exists because the frontend's own fence was WIDER than the wire's, and the gap was
 * a LEAK rather than a stale comment.</b> {@code RuleChecker.VOUWSOORTEN} kept {@code AANTAL} among
 * the exempt kinds, so a count whose single argument is a set reading passed the naloop untouched and
 * was emitted with {@code diagnostics: []} — a document the verifier rejects under WF-096. The
 * sentence below is that route, measured 2026-09-19 before the narrowing landed.
 *
 * <p><b>Why the route needs a name collision, and why that is the honest witness.</b> For a count the
 * element type is derived from the argument's WRITTEN WORD ({@code ExpressieBouwer.aantalElement}),
 * not from the argument's object type as it is for the six value folds. So a set reading only reaches
 * the exemption when that word also names a type or a role — here the fact type's role to Vondst is
 * itself called "gewicht", which the repository's own namespace decision (CONSTRUCTS row 4,
 * 2026-09-10: role names and member names are separate namespaces) makes a well-formed model. Without
 * such a collision the count binds no element, falls through to the parameter reading, and the set
 * reading is refused by the POSITION half of the rule — which is why the four natural sentences
 * measured on the landing card already refused and this one did not.
 */
class AantalSetbronTest {

    /** One Jutter to many Vondsten, related by a fact type whose far role is written "gewicht" — the
     *  same word as Vondst's own attribute. That collision is what lets "het aantal gewichten …" bind
     *  Vondst as its element type and so reach the fold-source exemption at all. */
    static final String MODEL = """
        Eenheidsysteem Gewicht
            het kilogram (mv: kilogrammen) kg

        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal aangemelde vondsten Numeriek (niet-negatief geheel getal);
            het aangemeld gewicht Numeriek (getal met 2 decimalen) met eenheid kg;

        Objecttype de Vondst (mv: Vondsten)
            het gewicht (mv: gewichten) Numeriek (getal met 2 decimalen) met eenheid kg;

        Feittype weging
            de weger Jutter
            het gewicht (mv: gewichten) Vondst
        Eén weger heeft meerdere gewichten
        """;

    /** The leak: a COUNT over a set reading of the element attribute. */
    static final String TELLING = """

        Regel aantal aangemelde vondsten
            geldig altijd
                Het aantal aangemelde vondsten van een Jutter moet berekend worden als het aantal gewichten van alle gewichten van de Jutter.
        """;

    /** The counter-proof: the SAME set source under a value fold, which stays exempt. */
    static final String SOM = """

        Regel aangemeld gewicht
            geldig altijd
                Het aangemeld gewicht van een Jutter moet berekend worden als de som van het gewicht van alle gewichten van de Jutter of 0 als die er niet zijn.
        """;

    /** The other counter-proof: the row-1 wart, v0's own spelling of a count source, which the
     *  narrowing must leave alone — the four zones hold 81 of them. */
    static final String WRAT = """

        Regel aantal aangemelde vondsten
            geldig altijd
                Het aantal aangemelde vondsten van een Jutter moet berekend worden als het aantal gewichten van de Jutter.
        """;

    /** The literal message the narrowed check raises. Pinned whole: the list of admissible kinds IS
     *  the boundary, so a kind silently rejoining it would otherwise pass unnoticed. */
    static final String MELDING =
            "setlezing buiten een vouwbron: 'gewichten van alle gewichten van de Jutter' noemt de waarde "
            + "van elk lid van de verzameling — alleen toegestaan als bron van één waardevouw (som, "
            + "totaal, minimum, maximum, eerste, laatste); een telling vouwt de leden zelf en leest "
            + "geen waarde per lid, dus onder 'aantal' ook niet (besluit 2026-09-19); hier wordt één "
            + "waarde verwacht (WF-096, IR-SPEC ch. 4.3)";

    private static List<Diagnostic> wf096(String regels) {
        RegelSpraakModel m = Resolver.resolve(MODEL + regels);
        return m.diagnostics.stream().filter(d -> d.message().contains("WF-096")).toList();
    }

    @Test void de_telling_over_een_setlezing_wordt_geweigerd() {
        List<Diagnostic> ds = wf096(TELLING);
        assertEquals(1, ds.size(), () -> "verwacht één WF-096-melding, kreeg: " + ds);
        assertEquals(MELDING, ds.get(0).message());
        assertEquals(Severity.ERROR, ds.get(0).severity());
    }

    @Test void de_weigering_valt_op_de_zin_zelf() {
        // the line is the sentence's own, so a reader finds the setlezing by the number alone —
        // the same contract SetlezingZonderVouwTest pins for the POSITION half
        Diagnostic d = wf096(TELLING).get(0);
        assertEquals("Het aantal aangemelde vondsten van een Jutter moet berekend worden als het aantal "
                        + "gewichten van alle gewichten van de Jutter.",
                (MODEL + TELLING).lines().toList().get(d.line() - 1).strip());
    }

    @Test void de_ill_gevormde_telling_bereikt_de_wire_niet() {
        // The leak, stated as the property that closes it: an ERROR stops irModus writing at all, so
        // the document the verifier rejects under WF-096 is never produced. Before the narrowing this
        // same source emitted with `diagnostics: []`.
        RegelSpraakModel m = Resolver.resolve(MODEL + TELLING);
        assertFalse(m.errors().isEmpty(), "de telling met setbron levert geen fout meer op");
        String json = IrEmitter.emit(m);
        assertTrue(json.contains("setlezing buiten een vouwbron"), "geen WF-096-melding op de wire:\n" + json);
    }

    @Test void de_waardevouw_over_dezelfde_setbron_blijft_schoon() {
        // The narrowing removes ONE kind from the exemption and must leave the other six where they
        // were: the same set source under a SOM is the legitimate fold source WF-096 protects.
        RegelSpraakModel m = Resolver.resolve(MODEL + SOM);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek op de waardevouw: " + m.diagnostics);
        Expr.Functie f = (Expr.Functie) m.resolvedRegels.get(0).waarde();
        assertEquals(Expr.FunctieKind.SOM, f.kind());
        assertTrue(((Expr.AttribuutRef) f.argumenten().get(0)).set(), "de SOM-bron is geen setlezing meer");
    }

    @Test void de_kale_telwrat_blijft_schoon() {
        // v0 spells a count source with the unknown sentinel and `set: false` — the row-1 wart, which
        // WF-093 governs and this narrowing does not reach. Refusing it here would refuse 81 positions
        // across the four zones.
        RegelSpraakModel m = Resolver.resolve(MODEL + WRAT);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek op de wrat: " + m.diagnostics);
        Expr.Functie f = (Expr.Functie) m.resolvedRegels.get(0).waarde();
        assertEquals(Expr.FunctieKind.AANTAL, f.kind());
        assertFalse(((Expr.AttribuutRef) f.argumenten().get(0)).set(), "de wrat draagt een setlezing");
    }
}
