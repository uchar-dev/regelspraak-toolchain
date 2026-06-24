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
