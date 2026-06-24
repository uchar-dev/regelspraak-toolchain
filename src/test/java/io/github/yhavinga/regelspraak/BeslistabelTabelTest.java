package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * §12 beslistabel END-TO-END through the GRAMMAR: the lexer's TABEL_MODE captures each inline markdown pipe line as
 * a TABEL_RIJ token, the parser's {@code beslistabel} rule collects them, and {@link Resolver} desugars + re-parses
 * each data row into a {@link ResolvedRegel}. (The pure desugar logic is unit-tested in {@link BeslistabelDesugarTest},
 * which bypasses the lexer by passing pre-split cells; this test exercises the capture + wiring that piece feeds.)
 */
class BeslistabelTabelTest {

    @Test void woonregio_or_table_captures_and_desugars_through_the_grammar() {
        String src = "Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)\n"
                + "    de woonregio factor Numeriek (geheel getal);\n"
                + "    de woonprovincie Tekst;\n\n"
                + "Beslistabel Woonregio factor\n"
                + "  geldig altijd\n"
                + "  |   | de woonregio factor van een Natuurlijk persoon moet gesteld worden op | indien zijn woonprovincie gelijk is aan |\n"
                + "  |---|---|---|\n"
                + "  | 1 | 1 | \"Friesland\", \"Groningen\" of \"Limburg\" |\n"
                + "  | 2 | 2 | \"Utrecht\" |\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        assertEquals(2, m.resolvedRegels.size());
        for (ResolvedRegel r : m.resolvedRegels) {
            assertEquals(ResolvedRegel.Soort.GELIJKSTELLING, r.soort());
            assertNotNull(r.voorwaarde(), "elke rij heeft een voorwaarde");
        }
    }

    @Test void and_table_with_nvt_omits_the_open_band_bound() {
        String src = "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
                + "    de reisduur Numeriek (geheel getal);\n"
                + "    de belasting Numeriek (geheel getal);\n\n"
                + "Beslistabel Belasting op basis van reisduur\n"
                + "  geldig altijd\n"
                + "  |   | de belasting van een Passagier moet gesteld worden op | indien zijn reisduur groter is dan | indien zijn reisduur kleiner of gelijk is aan |\n"
                + "  |---|---|---|---|\n"
                + "  | 1 | 10 | n.v.t. | 60 |\n"
                + "  | 2 | 20 | 60 | 120 |\n"
                + "  | 3 | 30 | 120 | n.v.t. |\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        assertEquals(3, m.resolvedRegels.size());
    }

    @Test void geldig_vanaf_jaar_is_not_swallowed_into_the_table_name() {
        // the §12 reisduur table uses "geldig vanaf 2018"; the beslistabel name must NOT eat the versie (it did
        // before beslistabelNaam excluded GELDIG — "altijd" stopped it as a keyword, "vanaf <jaar>" did not).
        String src = "Objecttype de Passagier (mv: Passagiers) (bezield)\n"
                + "    de belasting Numeriek (geheel getal);\n"
                + "    de basis Numeriek (geheel getal);\n\n"
                + "Beslistabel Belasting op basis van reisduur\n"
                + "  geldig vanaf 2018\n"
                + "  |   | de belasting van een Passagier moet gesteld worden op | indien zijn basis groter is dan |\n"
                + "  |---|---|---|\n"
                + "  | 1 | 10 | 5 |\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        assertEquals(1, m.resolvedRegels.size());
    }

    @Test void single_quoted_enumeratiewaarde_cells_resolve_through_the_grammar() {
        // §13.2.14: the §12 conditie values are single-quoted enumeratiewaarden over an enumeratie domein, not
        // double-quoted Tekst. The quote-aware split must protect them, and the comparison lowers as value equality.
        String src = "Domein Provincie is van het type Enumeratie\n    'Friesland'\n    'Groningen'\n    'Utrecht'\n"
                + "Objecttype de Persoon (mv: Personen) (bezield)\n"
                + "    de factor Numeriek (geheel getal);\n"
                + "    de woonprovincie Provincie;\n\n"
                + "Beslistabel Woonregio\n  geldig altijd\n"
                + "  |   | de factor van een Persoon moet gesteld worden op | indien zijn woonprovincie gelijk is aan |\n"
                + "  |---|---|---|\n"
                + "  | 1 | 1 | 'Friesland' of 'Groningen' |\n"
                + "  | 2 | 2 | 'Utrecht' |\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        assertEquals(2, m.resolvedRegels.size());
    }

    @Test void forbidden_conclusie_is_warned_and_dropped_through_the_grammar() {
        // §12 C-6: a non-gelijkstelling/non-kenmerk conclusie must surface a WARNING, never resolve to a rule.
        RegelSpraakModel m = Resolver.resolve(
                "Objecttype de Passagier (mv: Passagiers) (bezield)\n    de belasting Numeriek (geheel getal);\n"
                + "    de basis Numeriek (geheel getal);\n\n"
                + "Beslistabel T\n  geldig altijd\n"
                + "  |   | de belasting van een Passagier wordt berekend als | indien zijn basis groter is dan |\n"
                + "  |---|---|---|\n"
                + "  | 1 | 5 | 10 |\n");
        assertTrue(m.resolvedRegels.isEmpty(), () -> "verwacht geen regel: " + m.resolvedRegels);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("berekend als")),
                () -> "verwacht een 'berekend als' waarschuwing: " + m.diagnostics);
    }

    @Test void an_unbalanced_apostrophe_in_a_cell_does_not_merge_cells() {
        // the quote-aware split must only treat a BALANCED quote char as a delimiter — a stray apostrophe ("1's")
        // must stay a literal and NOT swallow the following '|' (which silently merged columns before the fix).
        String src = "Objecttype de Bestemming (mv: Bestemmingen)\n    de naam Tekst;\n    de rang Numeriek (geheel getal);\n\n"
                + "Beslistabel T\n  geldig altijd\n"
                + "  |   | de rang van een Bestemming moet gesteld worden op | indien zijn naam gelijk is aan |\n"
                + "  |---|---|---|\n"
                + "  | 1's | 1 | \"Amsterdam\" |\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        assertEquals(1, m.resolvedRegels.size());
    }

    @Test void the_spec_exact_reisduur_table_parses_desugars_and_resolves() {
        // the spec's flagship §12 table (main-spec L4744) VERBATIM — incl. the "in minuten" unit qualifier and the
        // "het percentage <schijf> van …" percentage-function-without-'%'. The beslistabel parses, desugars (one
        // regel/row), and RESOLVES with no errors: navigation through the feittype, n.v.t., AND, params, geldig-vanaf.
        // (Those two cells marker only at TRANSPILE — known general grammar/resolver limitations, beslistabel-
        // transparent: a hand-written rule markers them identically. The desugar adds no gap.)
        String src = "Domein Bedrag is van het type Numeriek (getal met 2 decimalen)\n"
                + "Objecttype de Vlucht (mv: Vluchten)\n    de reisduur per trein Numeriek (geheel getal) met eenheid minuut;\n"
                + "Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)\n"
                + "    de belasting op basis van afstand Bedrag;\n    de belasting op basis van reisduur Bedrag;\n"
                + "Feittype Vlucht van natuurlijke personen\n    de reis Vlucht\n    de passagier Natuurlijk persoon\n"
                + "Eén reis betreft de verplaatsing van meerdere passagiers\n"
                + "Parameter het percentage reisduur eerste schijf : Percentage (geheel getal)\n"
                + "Parameter het percentage reisduur tweede schijf : Percentage (geheel getal)\n"
                + "Parameter het percentage reisduur derde schijf : Percentage (geheel getal)\n"
                + "Parameter de bovengrens reisduur eerste schijf : Numeriek (geheel getal) met eenheid minuut\n"
                + "Parameter de bovengrens reisduur tweede schijf : Numeriek (geheel getal) met eenheid minuut\n\n"
                + "Beslistabel Belasting op basis van reisduur\n  geldig vanaf 2018\n"
                + "  |   | de belasting op basis van reisduur van een Natuurlijk persoon moet gesteld worden op | indien de reisduur per trein in minuten van zijn reis groter is dan | indien de reisduur per trein in minuten van zijn reis kleiner of gelijk is aan |\n"
                + "  |---|---|---|---|\n"
                + "  | 1 | het percentage reisduur eerste schijf van zijn belasting op basis van afstand naar beneden afgerond op 0 decimalen | n.v.t. | de bovengrens reisduur eerste schijf |\n"
                + "  | 2 | het percentage reisduur tweede schijf van zijn belasting op basis van afstand naar beneden afgerond op 0 decimalen | de bovengrens reisduur eerste schijf | de bovengrens reisduur tweede schijf |\n"
                + "  | 3 | het percentage reisduur derde schijf van zijn belasting op basis van afstand naar beneden afgerond op 0 decimalen | de bovengrens reisduur tweede schijf | n.v.t. |\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        assertEquals(3, m.resolvedRegels.size());
    }

    @Test void header_only_beslistabel_warns_it_is_empty_rather_than_silently_dropping() {
        // the pre-body corpus shape (a bare "Beslistabel … / geldig …" shell) must still parse AND surface a warning,
        // never vanish without IR or diagnostic.
        RegelSpraakModel m = Resolver.resolve("Beslistabel Woonregio factor\n  geldig altijd\n");
        assertTrue(m.resolvedRegels.isEmpty());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("geen tabelinhoud")),
                () -> "verwacht een 'geen tabelinhoud' waarschuwing: " + m.diagnostics);
    }

    @Test void the_committed_beslistabel_corpus_fixtures_resolve_without_errors() {
        Path dir = Path.of("examples/beslistabel");
        assumeTrue(Files.isDirectory(dir), "beslistabel-fixtures afwezig");
        try (var files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".rs")).sorted().forEach(p -> {
                RegelSpraakModel m = Resolver.resolve(read(p));
                assertTrue(m.errors().isEmpty(), () -> p.getFileName() + " fouten: " + m.errors());
                assertFalse(m.resolvedRegels.isEmpty(), () -> p.getFileName() + " leverde geen regels op");
            });
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static String read(Path p) {
        try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
