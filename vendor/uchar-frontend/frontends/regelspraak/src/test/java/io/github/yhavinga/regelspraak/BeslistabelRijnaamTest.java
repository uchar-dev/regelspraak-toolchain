package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A decision-table row becomes a rule named after the table and the row's number, the first cell ("Bonustabel
 * 1"). Rows without a number, or with the same number, therefore get the SAME rule name. Measured on the frontend
 * before this fix (e67fc386): such a table reached disk with {@code diagnostics: []} and two rules of one name,
 * which the verifier refused under WF-020 (duplicate name) and WF-021 (overlapping versions). It is an ERROR now,
 * naming the duplicate, and a group holding the table lists each name once.
 */
class BeslistabelRijnaamTest {

    static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de bonus Numeriek (geheel getal);
            het aantal geborgen wrakken Numeriek (niet-negatief geheel getal);

        """;

    private static String tabel(String nummer1, String nummer2) {
        return """
            Beslistabel Bonustabel
                geldig altijd
            | | de bonus van een Jutter moet gesteld worden op | indien zijn aantal geborgen wrakken groter is dan |
            |---|---|---|
            | %s | 10 | 5 |
            | %s | 20 | 10 |
            """.formatted(nummer1, nummer2);
    }

    private static Diagnostic enigeFout(RegelSpraakModel m) {
        List<Diagnostic> fouten = m.errors();
        assertEquals(1, fouten.size(), () -> "verwachtte precies één fout, kreeg: " + m.diagnostics);
        return fouten.get(0);
    }

    @Test void rijen_zonder_nummer_weigeren_bij_naam() {
        Diagnostic d = enigeFout(Resolver.resolve(MODEL + tabel(" ", " ")));
        assertTrue(d.message().contains("'Bonustabel'") && d.message().contains("WF-020"), d.message());
    }

    @Test void rijen_met_hetzelfde_nummer_weigeren_bij_naam() {
        Diagnostic d = enigeFout(Resolver.resolve(MODEL + tabel("1", "1")));
        assertTrue(d.message().contains("'Bonustabel 1'") && d.message().contains("WF-020"), d.message());
    }

    @Test void de_groep_noemt_een_dubbele_rijnaam_een_keer() {
        RegelSpraakModel m = Resolver.resolve(MODEL + "Regelgroep bonus\n" + tabel("1", "1"));
        enigeFout(m);
        assertEquals(List.of("Bonustabel 1"), m.regelGroepen.get(0).leden());
    }

    @Test void genummerde_rijen_blijven_schoon() {
        RegelSpraakModel m = Resolver.resolve(MODEL + tabel("1", "2"));
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
    }
}
