package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end on the real TOKA casus: the whole document resolves to a faithful
 * model AND raises no diagnostics — the resolver makes no false claim on the
 * constructs it cannot fully resolve. Reads the committed fixtures under
 * examples/toka (skips if absent).
 */
class CasusTest {

    @Test void volledige_toka_casus_resolveert_zonder_fouten() throws IOException {
        Path dir = Path.of("examples/toka");
        assumeTrue(Files.isDirectory(dir), "TOKA fixtures absent (run tools/extract_corpus.py)");

        String bron;
        try (Stream<Path> files = Files.list(dir)) {
            bron = files.filter(p -> p.toString().endsWith(".rs")).sorted()
                    .map(CasusTest::read)
                    .collect(Collectors.joining("\n"));
        }

        RegelSpraakModel m = Resolver.resolve(bron);
        assertTrue(m.errors().isEmpty(), () -> "false positives op de TOKA casus: " + m.errors());

        // the casus' GegevensSpraak, faithfully modelled
        assertEquals(3, m.objecttypen.size(), "Natuurlijk persoon, Vlucht, Contingent treinmiles");
        assertEquals(4, m.feittypen.size());
        assertTrue(m.parameters.size() >= 15, () -> "parameters: " + m.parameters.size());
        assertTrue(m.objecttypen.get("Natuurlijk persoon").attributen.containsKey("leeftijd"));
        assertTrue(m.regels.size() >= 20, () -> "regels: " + m.regels.size());
    }

    private static String read(Path p) {
        try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
