package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.BeslistabelDesugar;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §12 beslistabel desugar (piece 1: the semantic core, no grammar change). Asserts a captured decision-table
 * desugars to the canonical one-regel-per-row source, and that the output resolves through the EXISTING pipeline.
 * The Woonregio factor table is the spec's worked example (main-spec §12, L4692) whose hand-written equivalent
 * regels the spec gives at L4654-4678.
 */
class BeslistabelDesugarTest {

    @Test void woonregio_factor_table_desugars_to_the_spec_hand_written_or_list() {
        // §12 main-spec L4654-4678: the spec presents the SAME Woonregio rules hand-written with the multi-value
        // "is gelijk aan A, B of C" cell expanded to an "er aan tenminste één van de volgende voorwaarden" §13.4.14
        // OR-list. The desugar reproduces that (the resolver lowers the OR-list, not a verbatim value-series).
        List<String> headers = List.of("",
                "de woonregio factor van een Natuurlijk persoon moet gesteld worden op",
                "indien zijn woonprovincie gelijk is aan");
        List<List<String>> rows = List.of(
                List.of("1", "1", "\"Friesland\", \"Groningen\", \"Drenthe\", \"Zeeland\" of \"Limburg\""),
                List.of("2", "2", "\"Noord-Brabant\", \"Gelderland\", \"Overijssel\" of \"Flevoland\""),
                List.of("3", "3", "\"Noord-Holland\", \"Zuid-Holland\" of \"Utrecht\""));
        List<String> regels = BeslistabelDesugar.desugar("Woonregio factor", "altijd", headers, rows);

        assertEquals(3, regels.size());
        assertEquals("Regel Woonregio factor 1\n  geldig altijd\n    "
                + "de woonregio factor van een Natuurlijk persoon moet gesteld worden op 1 "
                + "indien er aan tenminste één van de volgende voorwaarden wordt voldaan:\n"
                + "      • zijn woonprovincie gelijk is aan \"Friesland\"\n"
                + "      • zijn woonprovincie gelijk is aan \"Groningen\"\n"
                + "      • zijn woonprovincie gelijk is aan \"Drenthe\"\n"
                + "      • zijn woonprovincie gelijk is aan \"Zeeland\"\n"
                + "      • zijn woonprovincie gelijk is aan \"Limburg\".\n",
                regels.get(0));
        assertEquals("Regel Woonregio factor 3\n  geldig altijd\n    "
                + "de woonregio factor van een Natuurlijk persoon moet gesteld worden op 3 "
                + "indien er aan tenminste één van de volgende voorwaarden wordt voldaan:\n"
                + "      • zijn woonprovincie gelijk is aan \"Noord-Holland\"\n"
                + "      • zijn woonprovincie gelijk is aan \"Zuid-Holland\"\n"
                + "      • zijn woonprovincie gelijk is aan \"Utrecht\".\n",
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

    @Test void kenmerk_conditie_waar_is_the_check_itself_not_an_appended_value() {
        // §12 L4766: a "waar" cell in a kenmerk CONDITIE-kolom means the check holds — the predicate kop IS the
        // condition; appending "waar" would mis-parse and silently drop sibling AND conditions (a real bug, fixed).
        List<String> headers = List.of("",
                "de belasting van een Passagier moet gesteld worden op",
                "indien hij een recht op duurzaamheidskorting heeft",
                "indien zijn reisduur kleiner of gelijk is aan");
        List<List<String>> rows = List.of(List.of("1", "10", "waar", "60"));
        List<String> regels = BeslistabelDesugar.desugar("Belasting", "altijd", headers, rows);
        assertEquals("Regel Belasting 1\n  geldig altijd\n    "
                + "de belasting van een Passagier moet gesteld worden op 10 indien hij aan alle volgende voorwaarden voldoet:\n"
                + "      • hij een recht op duurzaamheidskorting heeft\n"
                + "      • zijn reisduur kleiner of gelijk is aan 60.\n",
                regels.get(0));
    }

    @Test void kenmerk_conditie_onwaar_negates_the_bezittelijk_check() {
        List<String> headers = List.of("",
                "de belasting van een Passagier moet gesteld worden op",
                "indien hij een recht op duurzaamheidskorting heeft");
        List<List<String>> rows = List.of(List.of("1", "10", "onwaar"));
        List<String> regels = BeslistabelDesugar.desugar("Belasting", "altijd", headers, rows);
        assertEquals("Regel Belasting 1\n  geldig altijd\n    "
                + "de belasting van een Passagier moet gesteld worden op 10 "
                + "indien hij geen recht op duurzaamheidskorting heeft.\n",
                regels.get(0));
    }

    @Test void multi_value_cell_under_equality_becomes_an_or_list_nested_last_inside_an_and() {
        // A §5.7 disjunction cell combined with another (AND) condition: the disjunction is reordered LAST and nested
        // as a §13.4.14.2 "er voldoet aan tenminste één …" sub-list (so its greedy bullet capture is harmless).
        List<String> headers = List.of("",
                "de korting van een Passagier moet gesteld worden op",
                "indien zijn leeftijd groter is dan",
                "indien zijn klasse gelijk is aan");
        List<List<String>> rows = List.of(List.of("1", "5", "18", "\"eerste\" of \"tweede\""));
        List<String> regels = BeslistabelDesugar.desugar("Korting", "altijd", headers, rows);
        assertEquals("Regel Korting 1\n  geldig altijd\n    "
                + "de korting van een Passagier moet gesteld worden op 5 indien hij aan alle volgende voorwaarden voldoet:\n"
                + "      • zijn leeftijd groter is dan 18\n"
                + "      • er voldoet aan tenminste één van de volgende voorwaarden:\n"
                + "        •• zijn klasse gelijk is aan \"eerste\"\n"
                + "        •• zijn klasse gelijk is aan \"tweede\".\n",
                regels.get(0));
    }

    @Test void multi_value_cell_split_is_quote_aware() {
        // §5.4: a tekstwaarde may legally contain a comma, the word "of", or '|'; none of those split the value.
        List<String> headers = List.of("",
                "de woonregio factor van een Natuurlijk persoon moet gesteld worden op",
                "indien zijn woonprovincie gelijk is aan");
        List<List<String>> rows = List.of(List.of("1", "1", "\"Holland, Noord\", \"A of B\" of \"Fries|land\""));
        List<String> regels = BeslistabelDesugar.desugar("W", "altijd", headers, rows);
        assertEquals(1, regels.size());
        String r = regels.get(0);
        assertTrue(r.contains("• zijn woonprovincie gelijk is aan \"Holland, Noord\""), r);
        assertTrue(r.contains("• zijn woonprovincie gelijk is aan \"A of B\""), r);
        assertTrue(r.contains("• zijn woonprovincie gelijk is aan \"Fries|land\""), r);
    }

    @Test void ongelijk_series_uses_en_and_becomes_an_and_of_inequalities() {
        // §12-note L4702: a concatenatie under "is ongelijk aan" uses "en" and means !=A AND !=B AND !=C.
        List<String> headers = List.of("",
                "de woonregio factor van een Natuurlijk persoon moet gesteld worden op",
                "indien zijn woonprovincie ongelijk is aan");
        List<List<String>> rows = List.of(List.of("1", "1", "\"Friesland\", \"Groningen\" en \"Drenthe\""));
        List<String> regels = BeslistabelDesugar.desugar("W", "altijd", headers, rows);
        assertEquals("Regel W 1\n  geldig altijd\n    "
                + "de woonregio factor van een Natuurlijk persoon moet gesteld worden op 1 indien hij aan alle volgende voorwaarden voldoet:\n"
                + "      • zijn woonprovincie ongelijk is aan \"Friesland\"\n"
                + "      • zijn woonprovincie ongelijk is aan \"Groningen\"\n"
                + "      • zijn woonprovincie ongelijk is aan \"Drenthe\".\n",
                regels.get(0));
    }

    @Test void more_than_one_disjunction_in_one_and_row_is_skipped_with_a_warning() {
        List<String> headers = List.of("",
                "de woonregio factor van een Natuurlijk persoon moet gesteld worden op",
                "indien zijn woonprovincie gelijk is aan",
                "indien zijn taal gelijk is aan");
        List<List<String>> rows = List.of(List.of("1", "1",
                "\"Friesland\" of \"Groningen\"", "\"Fries\" of \"Nedersaksisch\""));
        List<String> warns = new ArrayList<>();
        List<String> regels = BeslistabelDesugar.desugar("W", "altijd", headers, rows, warns::add);
        assertTrue(regels.isEmpty(), () -> "verwacht geen regel (niet stil renderen): " + regels);
        assertEquals(1, warns.size(), () -> "" + warns);
        assertTrue(warns.get(0).contains("meer dan één concatenatie-cel"), warns.get(0));
    }

    @Test void kenmerk_conclusie_onwaar_is_skipped_with_a_warning_not_silently() {
        List<String> headers = List.of("", "een passagier is minderjarig", "indien zijn leeftijd kleiner is dan");
        List<List<String>> rows = List.of(List.of("1", "waar", "18 jr"), List.of("2", "onwaar", "65 jr"));
        List<String> warns = new ArrayList<>();
        List<String> regels = BeslistabelDesugar.desugar("M", "altijd", headers, rows, warns::add);
        assertEquals(1, regels.size(), () -> "alleen rij 1 (waar) levert een regel: " + regels);
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("onwaar"), warns.get(0));
    }

    @Test void kenmerk_conditie_onwaar_negates_each_kenmerk_shape() {
        // bezittelijk "een"->"geen"; trailing-verb "X is"->"is niet X" (NOT a silently-positive "X is niet").
        List<String> headers = List.of("", "de belasting van een Passagier moet gesteld worden op",
                "indien hij een recht op duurzaamheidskorting heeft", "indien hij gepensioneerd is");
        List<List<String>> rows = List.of(List.of("1", "10", "onwaar", "onwaar"));
        String r = BeslistabelDesugar.desugar("X", "altijd", headers, rows).get(0);
        assertTrue(r.contains("hij geen recht op duurzaamheidskorting heeft"), r);
        assertTrue(r.contains("hij is niet gepensioneerd"), r);
    }

    @Test void forbidden_conclusies_are_rejected_with_a_warning_not_mis_rendered() {
        // §12 C-6/C-7/C-8/C-9: only gelijkstelling ("moet gesteld worden op") + kenmerktoekenning ("waar") are
        // allowed; "berekend als", any other resultaatdeel, an empty conclusie cell, and an inline "indien" are out.
        List<String> warns = new ArrayList<>();
        java.util.function.BiFunction<String, String, List<String>> run = (conclHeader, conclCell) ->
                BeslistabelDesugar.desugar("T", "altijd",
                        List.of("", conclHeader, "indien zijn leeftijd kleiner is dan"),
                        List.of(List.of("1", conclCell, "18")), warns::add);
        assertTrue(run.apply("de korting van een Passagier wordt berekend als", "5").isEmpty());          // C-7
        assertTrue(run.apply("de korting van een Passagier wordt verdeeld over de passagiers", "x").isEmpty()); // C-6
        assertTrue(run.apply("de korting van een Passagier moet gesteld worden op", "").isEmpty());        // C-8
        assertTrue(run.apply("de korting van een Passagier moet gesteld worden op", "5 indien iets").isEmpty()); // C-9
        assertEquals(4, warns.size(), () -> "elke verboden rij waarschuwt: " + warns);
        assertTrue(warns.get(0).contains("berekend als"));
        assertTrue(warns.get(1).contains("toegestaan"));
        assertTrue(warns.get(2).contains("lege conclusie-cel"));
        assertTrue(warns.get(3).contains("indien"));
    }

    @Test void c9_indien_rejection_is_token_and_quote_aware() {
        // §12 C-9 must reject only a STANDALONE "indien" OUTSIDE quotes — not the substring in an attribute name
        // ("indientermijn") nor an "indien" inside a Tekst literal ("indien nodig") (review false-positives).
        List<String> warns = new ArrayList<>();
        java.util.function.Function<String, List<String>> conclusie = cell ->
                BeslistabelDesugar.desugar("T", "altijd",
                        List.of("", "de korting van een Passagier moet gesteld worden op", "indien zijn leeftijd kleiner is dan"),
                        List.of(List.of("1", cell, "18")), warns::add);
        assertEquals(1, conclusie.apply("zijn indientermijn").size(), () -> "indientermijn afgewezen: " + warns);
        assertEquals(1, conclusie.apply("\"indien nodig\"").size(), () -> "quoted indien afgewezen: " + warns);
        assertTrue(conclusie.apply("10 indien iets").isEmpty());   // a genuine standalone inline indien -> rejected
        assertTrue(warns.stream().anyMatch(w -> w.contains("indien")));
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
