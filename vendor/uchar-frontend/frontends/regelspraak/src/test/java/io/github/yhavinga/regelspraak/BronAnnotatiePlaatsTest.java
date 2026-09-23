package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a {@code // Bron:} annotation lands when it is not written directly above a rule of its own. An
 * annotation belongs to the rule directly below it ({@code bronAnnotaties}, IR-SPEC ch. 3.1). A {@code Regelgroep}
 * header is no rule, so an annotation above it reached nothing and vanished without a word — measured on the
 * frontend before this fix (df485007): {@code diagnostics: []}, the annotation nowhere in the IR. It now draws the
 * WARNING the {@code Uitzonderingen ongeordend:} header already draws. The group's own {@code bron} field is no
 * place for it: that field is the v0 source snippet of the group's name.
 *
 * <p>A {@code Beslistabel} header is no rule either, but the table IS rules: it desugars to one rule per row. An
 * annotation above it vanished the same way — measured on the same build: {@code diagnostics: []}, no
 * {@code bronAnnotaties} on either desugared rule — because each row is re-parsed in a stream of its own, where
 * no comment stands above it. It now lands on every desugared rule, the multi-version precedent of IR-SPEC
 * ch. 3.1: the field per flattened entry, stamped from the one comment block above the shared header.
 */
class BronAnnotatiePlaatsTest {

    private static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal geborgen wrakken Numeriek (niet-negatief geheel getal);
            de bergingspremie Numeriek (niet-negatief geheel getal);

        """;

    private static final String PREMIE = """
        Regel bergingspremie
            geldig altijd
                De bergingspremie van een Jutter moet berekend worden als zijn aantal geborgen wrakken maal 10.
        """;

    @Test void annotatie_boven_de_regelgroepkop_wordt_gemeld() {
        String src = MODEL + "// Bron: art. 7 Jutterswet\nRegelgroep berging\n" + PREMIE;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(1, m.diagnostics.size(), () -> "verwachtte precies één melding: " + m.diagnostics);
        Diagnostic d = m.diagnostics.get(0);
        assertEquals(Severity.WARNING, d.severity());
        assertTrue(d.message().startsWith("Regelgroep 'berging': de // Bron:-annotatie 'art. 7 Jutterswet'"), d.message());
        assertTrue(d.message().contains("hoort bij geen regel") && d.message().contains("§9.10"), d.message());
        assertTrue(m.resolvedRegels.get(0).bronAnnotaties().isEmpty(),
                "de kopannotatie wordt niet stil aan het eerste lid gehangen");
        assertEquals("berging", m.regelGroepen.get(0).bron().snippet(), "de bron van de groep blijft de naam");
    }

    @Test void annotatie_boven_een_beslistabel_landt_op_elke_rij() {
        String src = """
            Objecttype de Vondst (mv: Vondsten)
                de waarde Numeriek (geheel getal);
                het gewicht Numeriek (geheel getal);

            // Bron: art. 8 Jutterswet
            Beslistabel Vondstwaarde
                geldig altijd
            | | de waarde van een Vondst moet gesteld worden op | indien zijn gewicht groter is dan |
            |---|---|---|
            | 1 | 1 | 5 |
            | 2 | 2 | 10 |
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        assertEquals(2, m.resolvedRegels.size(), "een regel per rij");
        for (ResolvedRegel r : m.resolvedRegels)
            assertEquals(List.of("art. 8 Jutterswet"), r.bronAnnotaties(), () -> "rij '" + r.naam() + "' mist de annotatie");
    }

    @Test void annotatie_onder_de_regelgroepkop_hoort_bij_het_eerste_lid() {
        String src = MODEL + "Regelgroep berging\n// Bron: art. 7 Jutterswet\n" + PREMIE;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        assertEquals(List.of("art. 7 Jutterswet"), m.resolvedRegels.get(0).bronAnnotaties());
    }
}
