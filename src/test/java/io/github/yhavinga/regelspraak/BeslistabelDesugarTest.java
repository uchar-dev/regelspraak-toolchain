package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.BeslistabelDesugar;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §12 beslistabel desugar (piece 1: the semantic core, no grammar change). Asserts a captured decision-table
 * desugars to the canonical one-regel-per-row source, and that the output resolves through the EXISTING pipeline.
 * The Woonregio factor table is the spec's worked example (main-spec §12, L4692) whose hand-written equivalent
 * regels the spec gives at L4654-4678.
 */
class BeslistabelDesugarTest {

    @Test void woonregio_factor_table_desugars_to_the_spec_hand_written_rows() {
        List<String> headers = List.of("",
                "de woonregio factor van een Natuurlijk persoon moet gesteld worden op",
                "indien zijn woonprovincie gelijk is aan");
        List<List<String>> rows = List.of(
                List.of("1", "1", "'Friesland', 'Groningen', 'Drenthe', 'Zeeland' of 'Limburg'"),
                List.of("2", "2", "'Noord-Brabant', 'Gelderland', 'Overijssel' of 'Flevoland'"),
                List.of("3", "3", "'Noord-Holland', 'Zuid-Holland' of 'Utrecht'"));
        List<String> regels = BeslistabelDesugar.desugar("Woonregio factor", "altijd", headers, rows);

        assertEquals(3, regels.size());
        assertEquals("Regel Woonregio factor 1\n  geldig altijd\n    "
                + "de woonregio factor van een Natuurlijk persoon moet gesteld worden op 1 "
                + "indien zijn woonprovincie gelijk is aan 'Friesland', 'Groningen', 'Drenthe', 'Zeeland' of 'Limburg'.\n",
                regels.get(0));
        assertEquals("Regel Woonregio factor 3\n  geldig altijd\n    "
                + "de woonregio factor van een Natuurlijk persoon moet gesteld worden op 3 "
                + "indien zijn woonprovincie gelijk is aan 'Noord-Holland', 'Zuid-Holland' of 'Utrecht'.\n",
                regels.get(2));
    }

    @Test void multi_conditie_kolom_folds_to_alle_volgende_voorwaarden_and_omits_nvt() {
        List<String> headers = List.of("",
                "de belasting op basis van reisduur van een passagier moet gesteld worden op",
                "indien de reisduur per trein in minuten van zijn reis groter is dan",
                "indien de reisduur per trein in minuten van zijn reis kleiner of gelijk is aan");
        List<List<String>> rows = List.of(
                List.of("1", "het percentage eerste schijf", "n.v.t.", "de bovengrens eerste schijf"),
                List.of("2", "het percentage tweede schijf", "de bovengrens eerste schijf", "de bovengrens tweede schijf"));
        List<String> regels = BeslistabelDesugar.desugar("Belasting op basis van reisduur", "altijd", headers, rows);

        // row 1: the "n.v.t." condition is OMITTED -> a single (the second) condition.
        assertEquals("Regel Belasting op basis van reisduur 1\n  geldig altijd\n    "
                + "de belasting op basis van reisduur van een passagier moet gesteld worden op het percentage eerste schijf "
                + "indien de reisduur per trein in minuten van zijn reis kleiner of gelijk is aan de bovengrens eerste schijf.\n",
                regels.get(0));
        // row 2: both conditions -> AND via "alle volgende voorwaarden", "indien" stripped per bullet.
        assertEquals("Regel Belasting op basis van reisduur 2\n  geldig altijd\n    "
                + "de belasting op basis van reisduur van een passagier moet gesteld worden op het percentage tweede schijf "
                + "indien hij aan alle volgende voorwaarden voldoet:\n"
                + "      • de reisduur per trein in minuten van zijn reis groter is dan de bovengrens eerste schijf\n"
                + "      • de reisduur per trein in minuten van zijn reis kleiner of gelijk is aan de bovengrens tweede schijf.\n",
                regels.get(1));
    }

    @Test void conclusie_kolom_after_conditie_kolom_is_classified_by_header_not_position() {
        // §12: conclusie-kolommen may sit AFTER conditie-kolommen — detected by the header text, not the column index.
        List<String> headers = List.of("",
                "indien zijn leeftijd kleiner is dan",
                "de korting van een Passagier moet gesteld worden op");
        List<List<String>> rows = List.of(List.of("1", "18", "10"));
        List<String> regels = BeslistabelDesugar.desugar("Korting", "altijd", headers, rows);
        assertEquals("Regel Korting 1\n  geldig altijd\n    "
                + "de korting van een Passagier moet gesteld worden op 10 indien zijn leeftijd kleiner is dan 18.\n",
                regels.get(0));
    }

    @Test void desugared_rows_resolve_through_the_existing_pipeline() {
        List<String> headers = List.of("",
                "de korting van een Passagier moet gesteld worden op",
                "indien zijn leeftijd kleiner is dan");
        List<List<String>> rows = List.of(List.of("1", "10", "18"), List.of("2", "0", "65"));
        List<String> regels = BeslistabelDesugar.desugar("Korting", "altijd", headers, rows);

        String src = "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
                + "    de leeftijd Numeriek (geheel getal);\n"
                + "    de korting Numeriek (geheel getal);\n"
                + String.join("", regels);
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "resolver-fout: " + m.errors());
        assertEquals(2, m.resolvedRegels.size());
        for (ResolvedRegel r : m.resolvedRegels) {
            assertEquals(ResolvedRegel.Soort.GELIJKSTELLING, r.soort());
            assertTrue(r.doel() instanceof Expr.AttribuutRef a && a.attribuut().equals("korting"),
                    () -> "doel niet de korting: " + r.doel());
            assertNotNull(r.voorwaarde(), "voorwaarde ontbreekt");
        }
    }
}
