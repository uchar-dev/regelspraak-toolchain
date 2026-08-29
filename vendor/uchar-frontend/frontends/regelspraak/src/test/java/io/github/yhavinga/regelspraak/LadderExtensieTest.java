package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §4 (LADDER-EVOLUTIE) the uchar-dialect ladder extension: the ordered {@code Uitzondering op} block and the
 * unordered {@code Uitzonderingen ongeordend} group lower to {@link RegelSpraakModel#defaultLadders}. These
 * tests pin BOTH the rood-eerst refusals (a malformed block fails loudly in the resolver) AND the happy-path
 * parse+resolve+emit wire shape, plus the additivity invariant (a flat document emits no ladder key).
 */
class LadderExtensieTest {

    /** The emitted v0 IR JSON with all whitespace removed — an exact compacted fragment pins the wire shape. */
    private static String emitCompact(RegelSpraakModel m) {
        return IrEmitter.emit(m).replaceAll("\\s+", "");
    }

    /** The single error text, asserting exactly one error was raised (so a test pins the intended refusal, not
     *  some unrelated diagnostic). */
    private static String enigeFout(RegelSpraakModel m) {
        assertEquals(1, m.errors().size(), () -> "verwachtte precies één fout, kreeg: " + m.errors());
        return m.errors().get(0).message();
    }

    private static final String OBJ_ORDERED = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            is bergingsbevoegd kenmerk (bijvoeglijk);
            is noodweer verklaard kenmerk (bijvoeglijk);
            het aantal geborgen wrakken Numeriek (niet-negatief geheel getal);
        """;

    // -------------------------------------------------------------------- rood-eerst

    @Test void onbekende_basisregel_weigert_luid() {
        // An 'Uitzondering op' naming a base rule that does not exist must fail fast in the resolver.
        String src = OBJ_ORDERED + """
            Uitzondering op onbekende basis: bergingsbevoegd bij noodweer
                geldig altijd
                    Een Jutter is bergingsbevoegd
                    indien zijn aantal geborgen wrakken groter of gelijk is aan 8.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een onbekende basisregel hoort de resolver te laten falen");
        assertTrue(enigeFout(m).contains("geen basisregel met die naam gevonden"),
                () -> "diagnostiek noemt de onbekende basisregel niet: " + m.errors());
    }

    @Test void uitzondering_op_ander_doel_weigert_luid() {
        // The exception body must write the SAME target as the base rule (WF-050): a different kenmerk refuses.
        String src = OBJ_ORDERED.replace("het aantal geborgen wrakken",
                "is sloopbevoegd kenmerk (bijvoeglijk);\n    het aantal geborgen wrakken") + """
            Regel bergingsbevoegd bij quotum
                geldig altijd
                    Een Jutter is bergingsbevoegd
                    indien zijn aantal geborgen wrakken groter of gelijk is aan 20.

            Uitzondering op bergingsbevoegd bij quotum: sloopuitzondering
                geldig altijd
                    Een Jutter is sloopbevoegd
                    indien zijn aantal geborgen wrakken groter of gelijk is aan 8.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een uitzondering op een ander doel hoort de resolver te laten falen");
        String fout = enigeFout(m);
        assertTrue(fout.contains("ander doel") && fout.contains("sloopbevoegd"),
                () -> "diagnostiek benoemt het afwijkende doel niet: " + m.errors());
    }

    @Test void basis_is_initialisatie_weigert_luid() {
        // A ladder is never built over an initialisation base (§4).
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                is kustwacht kenmerk (bijvoeglijk);
                het wachtgeld Numeriek (geheel getal);

            Regel wachtgeld basis
                geldig altijd
                    Het wachtgeld van een Jutter moet geïnitialiseerd worden op 0.

            Uitzondering op wachtgeld basis: wachtgeld voor kustwachters
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een initialisatie-basis hoort de resolver te laten falen");
        assertTrue(enigeFout(m).contains("initialisatie"),
                () -> "diagnostiek benoemt de initialisatie-basis niet: " + m.errors());
    }

    @Test void uitzondering_is_initialisatie_weigert_luid() {
        // The mirror of the base guard: an exception whose body is an initialisation would lower to a rung
        // the verifier rejects (WF-050) — the resolver must refuse it locally instead (lens catch 2026-08-29).
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                is kustwacht kenmerk (bijvoeglijk);
                het wachtgeld Numeriek (geheel getal);

            Regel wachtgeld basis
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.

            Uitzondering op wachtgeld basis: wachtgeld begin
                geldig altijd
                    Het wachtgeld van een Jutter moet geïnitialiseerd worden op 0.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een initialisatie-uitzondering hoort de resolver te laten falen");
        assertTrue(enigeFout(m).contains("initialisatie"),
                () -> "diagnostiek benoemt de initialisatie-uitzondering niet: " + m.errors());
    }

    @Test void afwijkend_geldigheidsvenster_weigert_luid() {
        // Rung carries no per-rung geldigheid: a diverging validity window on the exception is
        // unrepresentable and must refuse instead of silently riding the base window (lens catch 2026-08-29 —
        // the amendment-adds-an-exception-effective-later pattern the differential square can never see).
        String src = OBJ_ORDERED + """
            Regel bergingsbevoegd bij drempel
                geldig altijd
                    Een Jutter is bergingsbevoegd
                    indien zijn aantal geborgen wrakken groter of gelijk is aan 20.

            Uitzondering op bergingsbevoegd bij drempel: bergingsbevoegd bij noodweer
                geldig vanaf 2022
                    Een Jutter is bergingsbevoegd
                    indien zijn aantal geborgen wrakken groter of gelijk is aan 8.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een afwijkend geldigheidsvenster hoort de resolver te laten falen");
        assertTrue(enigeFout(m).contains("geldigheidsvenster"),
                () -> "diagnostiek benoemt het afwijkende venster niet: " + m.errors());
    }

    @Test void groepslid_op_ander_doel_weigert_luid() {
        // An unordered group is over ONE shared target: a member writing a different attribute refuses.
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                is kustwacht kenmerk (bijvoeglijk);
                is havenmeester kenmerk (bijvoeglijk);
                het wachtgeld Numeriek (geheel getal);
                het toeslag Numeriek (geheel getal);

            Uitzonderingen ongeordend:
            Regel wachtgeld voor kustwachters
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.

            Regel toeslag voor havenmeesters
                geldig altijd
                    Het toeslag van een Jutter moet gesteld worden op 45
                    indien hij havenmeester is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een groepslid op een ander doel hoort de resolver te laten falen");
        assertTrue(enigeFout(m).contains("ander doel"),
                () -> "diagnostiek benoemt het afwijkende groepsdoel niet: " + m.errors());
    }

    // -------------------------------------------------------------------- happy path

    @Test void geordende_uitzondering_bouwt_een_ladder() {
        String src = OBJ_ORDERED + """
            Regel bergingsbevoegd bij quotum
                geldig altijd
                    Een Jutter is bergingsbevoegd
                    indien zijn aantal geborgen wrakken groter of gelijk is aan 20.

            Uitzondering op bergingsbevoegd bij quotum: bergingsbevoegd bij noodweer
                geldig altijd
                    Een Jutter is bergingsbevoegd
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij is noodweer verklaard
                    - zijn aantal geborgen wrakken is groter of gelijk aan 8.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        // one ladder, the base rule lifted OUT of resolvedRegels (WF-050 single-writer)
        assertEquals(1, m.defaultLadders.size(), "verwachtte precies één ladder");
        assertEquals(0, m.resolvedRegels.size(), "basis + uitzondering horen uit resolvedRegels te zijn gelicht");
        var l = m.defaultLadders.get(0);
        assertEquals("Jutter", l.onderwerp());
        assertEquals("bergingsbevoegd", l.kenmerk());
        assertNull(l.attribuut());
        assertFalse(l.ongeordend());
        assertEquals(2, l.rungs().size(), "verwachtte een uitzonderingssport boven een basissport");
        // the exception rung (a Samengesteld voorwaarde) sits ABOVE the base rung (a bare Vergelijking)
        assertInstanceOf(io.github.yhavinga.regelspraak.ir.Conditie.Samengesteld.class, l.rungs().get(0).voorwaarde());
        assertInstanceOf(io.github.yhavinga.regelspraak.ir.Conditie.Vergelijking.class, l.rungs().get(1).voorwaarde());
        assertEquals(Boolean.TRUE, l.rungs().get(0).besluit());
        assertEquals(Boolean.TRUE, l.rungs().get(1).besluit());
    }

    @Test void ongeordende_groep_bouwt_zusterladders() {
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                is kustwacht kenmerk (bijvoeglijk);
                is havenmeester kenmerk (bijvoeglijk);
                het wachtgeld Numeriek (geheel getal);

            Uitzonderingen ongeordend:
            Regel wachtgeld voor kustwachters
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.

            Regel wachtgeld voor havenmeesters
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 45
                    indien hij havenmeester is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(2, m.defaultLadders.size(), "verwachtte twee zusterladders");
        assertEquals(0, m.resolvedRegels.size(), "alle groepsleden horen uit resolvedRegels te zijn gelicht");
        for (var l : m.defaultLadders) {
            assertEquals("wachtgeld", l.attribuut(), "elke zusterladder schrijft het gedeelde attribuut");
            assertNull(l.kenmerk());
            assertTrue(l.ongeordend(), "elke zusterladder van de groep is declared-unordered (WF-050)");
            assertEquals(1, l.rungs().size());
            assertNull(l.rungs().get(0).besluit(), "een attribuutsport heeft geen besluit");
            assertNotNull(l.rungs().get(0).consequentie(), "een attribuutsport draagt zijn waarde als consequentie");
        }
        // the §9.5 conflict channel is only reachable via this form: distinct written values on one target
        String c = emitCompact(m);
        assertTrue(c.contains("\"waarde\":\"30\"") && c.contains("\"waarde\":\"45\""),
                () -> "de twee ladderwaarden ontbreken:\n" + c);
    }

    @Test void plat_document_emitteert_geen_ladder_sleutel() {
        // Additivity / v0-mirror: a document WITHOUT the extension emits no defaultLadders key at all.
        String src = OBJ_ORDERED + """
            Regel bergingsbevoegd bij quotum
                geldig altijd
                    Een Jutter is bergingsbevoegd
                    indien zijn aantal geborgen wrakken groter of gelijk is aan 20.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(0, m.defaultLadders.size());
        assertFalse(emitCompact(m).contains("defaultLadders"),
                "een plat document mag de defaultLadders-sleutel niet emitteren (v0-mirror byte-identiteit)");
    }
}
