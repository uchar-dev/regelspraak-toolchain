package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.ir.PrioritizedDefaults;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code // Bron:} statute annotations of the rules the defaults extension (dialect §4) lifts. A lifted rule
 * leaves {@code resolvedRegels}, and with it the {@code bronAnnotaties} list that carried its annotation; the
 * lowering set {@code bron: null} on the defaults group and on every default. Measured on the pre-card frontend
 * (3a140fe4), the M6 document of {@code spec/proposals/DIALECT-DEFAULTS-NIET-EN-NESTING.md} — an annotated base and
 * its annotated {@code Uitzondering op} — emitted an IR in which neither annotation text occurred anywhere, with
 * {@code diagnostics: []} and a verifier PASS.
 *
 * <p>The placement, with no new wire field: a lifted rule's annotation lands in the {@code Default.bron} of the
 * default it becomes; the base rule's lands in {@code PrioritizedDefaults.bron} as well — the group is named after
 * the base, and in every hand-written defaults anchor each default, the lowest included, carries its own
 * provenance beside the group's. Several annotations on one block are joined with {@code "; "}.
 */
class DefaultsBronAnnotatieTest {

    private static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            is bergingsbevoegd kenmerk (bijvoeglijk);
            is noodweer verklaard kenmerk (bijvoeglijk);
            is kustwacht kenmerk (bijvoeglijk);
            is havenmeester kenmerk (bijvoeglijk);
            het aantal geborgen wrakken Numeriek (niet-negatief geheel getal);
            het wachtgeld Numeriek (geheel getal);

        """;

    private static final String BASIS = """
        Regel bergingsbevoegd bij quotum
            geldig altijd
                Een Jutter is bergingsbevoegd
                indien zijn aantal geborgen wrakken groter of gelijk is aan 20.

        """;

    private static final String UITZONDERING = """
        Uitzondering op bergingsbevoegd bij quotum: bergingsbevoegd bij noodweer
            geldig altijd
                Een Jutter is bergingsbevoegd
                indien hij aan alle volgende voorwaarden voldoet:
                - hij is noodweer verklaard
                - zijn aantal geborgen wrakken is groter of gelijk aan 8.

        """;

    /** The M6 document of the proposal: an annotated base and its annotated exception. */
    static final String M6 = MODEL
            + "// Bron: art. 4 lid 1 Jutterswet\n" + BASIS
            + "// Bron: art. 4 lid 2 Jutterswet\n" + UITZONDERING;

    private static PrioritizedDefaults enigeGroep(RegelSpraakModel m) {
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(1, m.prioritizedDefaults.size(), "verwachtte precies één defaults-groep");
        return m.prioritizedDefaults.get(0);
    }

    /** Every {@code "bron": "<text>"} value on the wire that carries an "art." citation, in emission order. */
    private static List<String> artikelBronnen(String ir) {
        Matcher k = Pattern.compile("\"bron\": \"(art\\.[^\"]*)\"").matcher(ir);
        List<String> uit = new ArrayList<>();
        while (k.find()) uit.add(k.group(1));
        return uit;
    }

    // -------------------------------------------------------------------- the placement

    @Test void m6_basis_en_uitzondering_dragen_hun_annotatie_op_de_wire() {
        RegelSpraakModel m = Resolver.resolve(M6);
        PrioritizedDefaults g = enigeGroep(m);
        assertEquals("art. 4 lid 2 Jutterswet", g.defaults().get(0).bron(), "de uitzonderingsdefault (hoogste) draagt haar eigen annotatie");
        assertEquals("art. 4 lid 1 Jutterswet", g.defaults().get(1).bron(), "de basisdefault (laagste) draagt de annotatie van de basisregel");
        assertEquals("art. 4 lid 1 Jutterswet", g.bron(), "de groep, genoemd naar de basis, draagt de annotatie van de basisregel");
        // On the wire, in emission order: the defaults (highest first), then the group's own bron after the array.
        assertEquals(List.of("art. 4 lid 2 Jutterswet", "art. 4 lid 1 Jutterswet", "art. 4 lid 1 Jutterswet"),
                artikelBronnen(IrEmitter.emit(m)));
    }

    @Test void meerdere_annotaties_op_een_blok_worden_samengevoegd() {
        String src = MODEL + "// Bron: art. 3 Jutterswet\n// Bron: art. 5 Jutterswet\n" + BASIS + UITZONDERING;
        PrioritizedDefaults g = enigeGroep(Resolver.resolve(src));
        assertEquals("art. 3 Jutterswet; art. 5 Jutterswet", g.defaults().get(1).bron());
        assertEquals("art. 3 Jutterswet; art. 5 Jutterswet", g.bron());
        assertNull(g.defaults().get(0).bron(), "een uitzondering zonder annotatie erft die van de basis niet");
    }

    @Test void ongeordend_lid_draagt_zijn_annotatie_op_groep_en_default() {
        String src = MODEL + """
            Uitzonderingen ongeordend:
            // Bron: art. 9 lid 1 Jutterswet
            Regel wachtgeld voor kustwachters
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.

            // Bron: art. 9 lid 2 Jutterswet
            Regel wachtgeld voor havenmeesters
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 45
                    indien hij havenmeester is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        assertEquals(2, m.prioritizedDefaults.size());
        assertEquals("art. 9 lid 1 Jutterswet", m.prioritizedDefaults.get(0).bron());
        assertEquals("art. 9 lid 1 Jutterswet", m.prioritizedDefaults.get(0).defaults().get(0).bron());
        assertEquals("art. 9 lid 2 Jutterswet", m.prioritizedDefaults.get(1).bron());
        assertEquals("art. 9 lid 2 Jutterswet", m.prioritizedDefaults.get(1).defaults().get(0).bron());
    }

    @Test void een_annotatie_boven_de_ongeordende_kop_hoort_bij_geen_regel_en_wordt_gemeld() {
        // The block header is no rule: its annotation has no default to land on, and it used to vanish without
        // a word. A WARNING, because the document itself is sound — the author learns where the citation goes.
        String src = MODEL + """
            // Bron: art. 9 Jutterswet
            Uitzonderingen ongeordend:
            Regel wachtgeld voor kustwachters
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(1, m.diagnostics.size(), () -> "verwachtte precies één melding: " + m.diagnostics);
        Diagnostic d = m.diagnostics.get(0);
        assertEquals(Severity.WARNING, d.severity());
        assertTrue(d.message().contains("art. 9 Jutterswet") && d.message().contains("Uitzonderingen ongeordend"),
                () -> "de melding noemt de annotatie of de kop niet: " + d);
        assertNull(m.prioritizedDefaults.get(0).bron(), "de kopannotatie wordt niet stil aan een lid gehangen");
    }

    // -------------------------------------------------------------------- what stays as it was

    @Test void zonder_annotatie_blijft_elke_bron_null() {
        // The three extension goldens carry no annotation; this is their shape, and it must not move.
        PrioritizedDefaults g = enigeGroep(Resolver.resolve(MODEL + BASIS + UITZONDERING));
        assertNull(g.bron());
        g.defaults().forEach(d -> assertNull(d.bron()));
    }

    @Test void de_regelstub_staat_niet_op_de_wire() {
        // The Regel stubs left the wire at M1 (delta B3), so the annotation reaches the wire through the defaults
        // only; the model still keeps its stubs, the emitter no longer writes them.
        String c = IrEmitter.emit(Resolver.resolve(M6)).replaceAll("\\s+", "");
        assertFalse(c.contains("\"node\":\"Regel\""), c);
        assertFalse(c.contains("\"regels\":"), c);
    }
}
