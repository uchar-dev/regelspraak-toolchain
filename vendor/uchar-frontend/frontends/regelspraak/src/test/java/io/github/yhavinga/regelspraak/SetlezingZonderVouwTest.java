package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WF-096 (`spec/IR-SPEC.md` ch. 4.3, maintainer decision 2026-09-09): an {@code AttribuutRef} with
 * {@code set: true} is admissible ONLY as the source of a single-argument instance fold. A §13.4.2
 * set quantifier ("alle &lt;mv&gt;") with no fold around it therefore names a collection in a slot
 * that takes one value, and the document is ill-formed on the wire.
 *
 * <p>Until this suite the frontend emitted exactly that node with {@code diagnostics: []} — measured
 * on five routes, all from the one sentence fragment "het gewicht van alle vondsten van de Jutter":
 * as a whole rule value, as an operand of a composite node, as an initialisatie value, inside a
 * voorwaarde, and inside a consistentieregel. Each is pinned below, together with the two
 * counter-proofs that keep the check off the FOLDED reading, which stays legitimate, and the
 * severity, which is the one knob the check has.
 */
class SetlezingZonderVouwTest {

    /** Two object types related by one fact type (one aanmelder to many vondsten) — the model both
     *  card witnesses are written against — plus the Rapport the §13.4.6 objectcreatie creates, so the
     *  toekenning's generic expressie RHS can be exercised on the same sentence fragment. */
    static final String MODEL = """
        Eenheidsysteem Gewicht
            het kilogram (mv: kilogrammen) kg

        Objecttype de Jutter (mv: Jutters) (bezield)
            het maatgevend gewicht Numeriek (getal met 2 decimalen) met eenheid kg;
            het drempelgewicht Numeriek (getal met 2 decimalen) met eenheid kg;
            de peildatum Datum in dagen;

        Objecttype de Vondst (mv: Vondsten)
            het gewicht (mv: gewichten) Numeriek (getal met 2 decimalen) met eenheid kg;
            de vondstdatum (mv: vondstdata) Datum in dagen;

        Objecttype het Rapport (mv: Rapporten)
            het totaalgewicht Numeriek (getal met 2 decimalen) met eenheid kg;

        Feittype vondstaanmelding
            de aanmelder Jutter
            de vondst (mv: vondsten) Vondst
        Eén aanmelder heeft meerdere vondsten

        Feittype rapportage
            de rapporteur Jutter
            het rapport Rapport
        Eén rapporteur heeft één rapport
        """;

    /** The literal message the check raises on the two card witnesses (both read the same fragment). */
    static final String MELDING =
            "setlezing buiten een vouwbron: 'het gewicht van alle vondsten van de Jutter' noemt de waarde "
            + "van elk lid van de verzameling — alleen toegestaan als bron van één vouw (som, totaal, "
            + "minimum, maximum, eerste, laatste, aantal); hier wordt één waarde verwacht "
            + "(WF-096, IR-SPEC ch. 4.3)";

    private static List<Diagnostic> setlezingDiagnoses(String regels) {
        RegelSpraakModel m = Resolver.resolve(MODEL + regels);
        return m.diagnostics.stream().filter(d -> d.message().contains("WF-096")).toList();
    }

    private static List<String> meldingen(String regels) {
        return setlezingDiagnoses(regels).stream().map(Diagnostic::message).toList();
    }

    private static void geenEnkeleDiagnose(String regels) {
        RegelSpraakModel m = Resolver.resolve(MODEL + regels);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
    }

    static final String REGELWAARDE = """
            Regel maatgevend gewicht
                geldig altijd
                    Het maatgevend gewicht van een Jutter moet berekend worden als het gewicht van alle vondsten van de Jutter.
            """;

    @Test void setlezing_als_hele_regelwaarde_wordt_benoemd() {
        // The card's first witness, verbatim: corpus/negatief/wf096-setlezing-regelwaarde is this
        // sentence's emission, byte for byte, from before the check existed.
        assertEquals(List.of(MELDING), meldingen(REGELWAARDE));
    }

    @Test void setlezing_als_operand_van_een_samengestelde_knoop_wordt_benoemd() {
        // The card's second witness (corpus/negatief/wf096-setlezing-operand): the SAME node, now the
        // left operand of a Binair PLUS. One rule for both positions — they measure identically, and
        // the frontend has no reason to split them that the IR does not have.
        assertEquals(List.of(MELDING), meldingen("""
                Regel maatgevend gewicht
                    geldig altijd
                        Het maatgevend gewicht van een Jutter moet berekend worden als het gewicht van alle vondsten van de Jutter plus het drempelgewicht van de Jutter.
                """));
    }

    @Test void setlezing_als_gelijkstellingswaarde_wordt_benoemd() {
        // The same value slot reached by the §13.4.4 gelijkstelling ("moet gesteld worden op", soort
        // GELIJKSTELLING) instead of the §13.4.2 berekening — measured WF-096 FAIL at the same pointer.
        assertEquals(List.of(MELDING), meldingen("""
                Regel maatgevend gewicht
                    geldig altijd
                        Het maatgevend gewicht van een Jutter moet gesteld worden op het gewicht van alle vondsten van de Jutter.
                """));
    }

    @Test void setlezing_als_initialisatiewaarde_wordt_benoemd() {
        // And by the §13.4.9 initialisatie proper ("moet geïnitialiseerd worden op", soort
        // INITIALISATIE) — the third assignment form into the same slot, measured separately.
        assertEquals(List.of(MELDING), meldingen("""
                Regel maatgevend gewicht
                    geldig altijd
                        Het maatgevend gewicht van een Jutter moet geïnitialiseerd worden op het gewicht van alle vondsten van de Jutter.
                """));
    }

    @Test void setlezing_in_een_voorwaarde_wordt_benoemd() {
        // WF-096 names the condition explicitly; measured verifier-FAIL at
        // /resolvedRegels/0/voorwaarde/links before the check existed.
        assertEquals(1, meldingen("""
                Regel maatgevend gewicht
                    geldig altijd
                        Het maatgevend gewicht van een Jutter moet berekend worden als het drempelgewicht van de Jutter
                        indien het gewicht van alle vondsten van de Jutter gelijk is aan 5 kg.
                """).size());
    }

    @Test void setlezing_als_objectcreatie_toekenning_wordt_benoemd() {
        // The §13.4.6.3 waardetoekenning is a generic expressie RHS and the third (and last) VALUE slot
        // this IR has — a live producer route of its own: before the check the verifier reported
        // WF-096 FAIL /objectCreaties/0/toekenningen/0/waarde as the document's ONLY defect.
        assertEquals(List.of(MELDING), meldingen("""
                Regel nieuw rapport
                    geldig altijd
                        Een Jutter heeft een Rapport met het totaalgewicht gelijk aan het gewicht van alle vondsten van de Jutter.
                """));
    }

    @Test void de_som_in_een_objectcreatie_toekenning_blijft_een_geldige_vouwbron() {
        // The counter-proof in that same slot: under a fold the reading is the source the fold consumes.
        geenEnkeleDiagnose("""
                Regel nieuw rapport
                    geldig altijd
                        Een Jutter heeft een Rapport met het totaalgewicht gelijk aan de som van het gewicht van alle vondsten van de Jutter.
                """);
    }

    @Test void setlezing_in_de_voorwaarde_van_een_objectcreatie_wordt_benoemd() {
        // An objectcreatie never becomes a ResolvedRegel (measured: resolvedRegels 0, objectCreaties 1),
        // so its voorwaarde is the witness that the condition check sits where EVERY resultaatdeel
        // kind's voorwaarde passes through — not on the rule carrier alone. The same holds for a
        // verdeling, a feitcreatie, a dagsoortdefinitie and a startpuntbepaling.
        assertEquals(List.of(MELDING), meldingen("""
                Regel nieuwe vondst
                    geldig altijd
                        Een Jutter heeft een Vondst met het gewicht gelijk aan 3 kg
                        indien het gewicht van alle vondsten van de Jutter gelijk is aan 5 kg.
                """));
    }

    @Test void setlezing_in_een_consistentieregel_wordt_benoemd() {
        // The §13.4.8 assertion is a Conditie in its own carrier; measured verifier-FAIL at
        // /consistenties/0/assertie/links before the check existed.
        assertEquals(1, meldingen("""
                Regel gewichtscontrole
                    geldig altijd
                        Het gewicht van alle vondsten van de Jutter moet gelijk zijn aan het drempelgewicht van de Jutter.
                """).size());
    }

    @Test void de_som_van_een_setlezing_blijft_een_geldige_vouwbron() {
        // The counter-proof: WF-096 fences the POSITION, not the set reading. Under a fold the same
        // node is the source the fold consumes, and it stays verifier-clean.
        geenEnkeleDiagnose("""
                Regel maatgevend gewicht
                    geldig altijd
                        Het maatgevend gewicht van een Jutter moet berekend worden als de som van het gewicht van alle vondsten van de Jutter.
                """);
    }

    @Test void de_eerste_van_een_setlezing_blijft_een_geldige_vouwbron() {
        // The second counter-proof, on the fold kind the executors disagree about. That divergence sits
        // INSIDE WF-096's exemption and is a separate, still-open question; here the form only has to
        // keep passing the frontend unchanged.
        geenEnkeleDiagnose("""
                Regel peildatum
                    geldig altijd
                        De peildatum van een Jutter moet berekend worden als de eerste van de vondstdatum van alle vondsten van de Jutter.
                """);
    }

    @Test void de_diagnose_is_een_waarschuwing_en_geen_fout() {
        // The one knob, pinned so that raising it is a deliberate, visible act. WARNING keeps the
        // document on disk — it is the emitted IR that now states the defect (see the wire test
        // below). ERROR is the fail-fast level: irModus writes nothing when the model carries an
        // error, so the ill-formed document would not be produced at all. It is not taken here
        // because IrTest.referenties_lossen_op_via_feittype_rollen asserts that a rule value of
        // exactly this shape resolves error-free.
        List<Diagnostic> ds = setlezingDiagnoses(REGELWAARDE);
        assertEquals(1, ds.size());
        assertEquals(Severity.WARNING, ds.get(0).severity());
        // the line is the sentence's own, so a reader finds the setlezing by the number alone
        assertEquals("Het maatgevend gewicht van een Jutter moet berekend worden als het gewicht van alle vondsten van de Jutter.",
                (MODEL + REGELWAARDE).lines().toList().get(ds.get(0).line() - 1).strip());
    }

    @Test void de_emissie_draagt_de_diagnose_op_de_wire() {
        // The defect the card names is a document that says NOTHING about itself: the frontend emitted
        // this node with `diagnostics: []`. The emitter writes every diagnostic, so the wire now carries
        // the WF-096 sentence — where a consumer reads it, beside the shape it describes.
        RegelSpraakModel m = Resolver.resolve(MODEL + REGELWAARDE);
        String json = IrEmitter.emit(m);
        assertFalse(json.contains("\"diagnostics\": []"), "de emissie is nog diagnosticloos:\n" + json);
        assertTrue(json.contains("\"severity\": \"WARNING\""), "geen severity op de wire:\n" + json);
        assertTrue(json.contains("setlezing buiten een vouwbron"), "geen WF-096-melding op de wire:\n" + json);
    }
}
