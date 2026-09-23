package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WF-050's single writer, producer side: a target with a defaults group has no other writer — no plain rule
 * beside the group, and no second group unless every group on the target is declared unordered. Measured on
 * the frontend before this fence (402dc130), each of the five placements below reached disk with
 * {@code diagnostics: []} and the verifier refused it under WF-050: an 'Uitzondering op' with another plain
 * rule on the base's target; an 'Uitzondering op' on one row of a decision table (outside a group — the other
 * rows go on writing); two ordered families on one target; an ordered family beside an unordered block; an
 * unordered block beside a plain rule. Two unordered blocks on one target are admitted (every group unordered)
 * and stay clean.
 */
class EnigeSchrijverTest {

    static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de premie Numeriek (geheel getal);
            het aantal geborgen wrakken Numeriek (niet-negatief geheel getal);
            is noodweer verklaard kenmerk (bijvoeglijk);
            is kustwacht kenmerk (bijvoeglijk);

        """;

    private static final String BASIS = """
        Regel basispremie
            geldig altijd
                De premie van een Jutter moet gesteld worden op 10.

        """;

    private static final String NOOD = """
        Uitzondering op basispremie: noodpremie
            geldig altijd
                De premie van een Jutter moet gesteld worden op 20
                indien hij noodweer verklaard is.

        """;

    private static Diagnostic enigeFout(String src) {
        RegelSpraakModel m = Resolver.resolve(src);
        List<Diagnostic> fouten = m.errors();
        assertEquals(1, fouten.size(), () -> "verwachtte precies één fout, kreeg: " + m.diagnostics);
        assertTrue(fouten.get(0).message().contains("WF-050"), fouten.get(0).message());
        return fouten.get(0);
    }

    @Test void een_andere_gewone_regel_op_het_doel_weigert() {
        Diagnostic d = enigeFout(MODEL + BASIS + """
            Regel andere premie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.

            """ + NOOD);
        assertTrue(d.message().contains("'andere premie'") && d.message().contains("'basispremie'")
                && d.message().contains("Jutter.premie"), d.message());
    }

    @Test void een_uitzondering_op_een_tabelrij_weigert_want_de_andere_rijen_schrijven_door() {
        Diagnostic d = enigeFout(MODEL + """
            Beslistabel Premietabel
                geldig altijd
            | | de premie van een Jutter moet gesteld worden op | indien zijn aantal geborgen wrakken groter is dan |
            |---|---|---|
            | 1 | 10 | 5 |
            | 2 | 20 | 10 |

            Uitzondering op Premietabel 1: noodpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 50
                    indien hij noodweer verklaard is.
            """);
        assertTrue(d.message().contains("'Premietabel 2' (een rij van beslistabel 'Premietabel')"), d.message());
    }

    @Test void twee_geordende_families_op_een_doel_weigeren() {
        Diagnostic d = enigeFout(MODEL + BASIS + NOOD + """
            Regel tweede basis
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 5.

            Uitzondering op tweede basis: kustpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.
            """);
        assertTrue(d.message().contains("'basispremie'") && d.message().contains("'tweede basis'")
                && d.message().contains("ongeordend"), d.message());
    }

    @Test void een_familie_naast_een_ongeordend_blok_weigert() {
        Diagnostic d = enigeFout(MODEL + BASIS + NOOD + """
            Uitzonderingen ongeordend:
            Regel kustpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.
            """);
        assertTrue(d.message().contains("'basispremie'") && d.message().contains("'kustpremie'"), d.message());
    }

    @Test void een_ongeordend_blok_naast_een_gewone_regel_weigert() {
        Diagnostic d = enigeFout(MODEL + BASIS + """
            Uitzonderingen ongeordend:
            Regel kustpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.

            Regel noodpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 20
                    indien hij noodweer verklaard is.
            """);
        assertTrue(d.message().contains("'basispremie'"), d.message());
    }

    @Test void twee_ongeordende_blokken_op_een_doel_blijven_schoon() {
        String src = MODEL + """
            Uitzonderingen ongeordend:
            Regel kustpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.

            Objecttype de Strandvoogd (mv: Strandvoogden)
                de rang Numeriek (geheel getal);

            Uitzonderingen ongeordend:
            Regel noodpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 20
                    indien hij noodweer verklaard is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
    }

    @Test void een_familie_met_een_regel_op_een_ander_doel_blijft_schoon() {
        String src = MODEL + BASIS + NOOD + """
            Regel wrakken
                geldig altijd
                    Het aantal geborgen wrakken van een Jutter moet gesteld worden op 0.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
    }
}
