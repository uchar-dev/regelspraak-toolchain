package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.resolver.ScenarioResolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G3 mutatiegate (RED-first) voor de scenariotoetsen-extensie (docs/SCENARIOTOETSEN.md).
 *
 * <p>De fixtures in {@code src/test/resources/scenario-mutaties/} bevatten elk een bewust-FOUTE verwachting
 * (off-by-one waarde, verkeerde maar verenigbare eenheid, ontbrekend/onverwacht kenmerk, Inconsistentie
 * verwacht-maar-afwezig / consistent-maar-inconsistent, leeg-vs-gevuld, tekst/enum-mismatch, bezittelijk
 * kenmerk). De kop van elk fixture documenteert WAT er fout is en welke regel + waarde de runner-diagnose moet
 * noemen.
 *
 * <p>Dit is de RED-helft: elk fixture MOET SCHOON RESOLVEN — de {@link ScenarioResolver} accepteert hem (de
 * types kloppen; alleen de VERWACHTING is fout). Het FALEN hoort pas bij de runner (die nog niet bestaat). Deze
 * test pint dus (a) dat élk mutatie-fixture schoon resolvet — anders zou het falen al bij het resolven landen en
 * niet bij de runner —, en (b) dat er ≥ 10 zulke fixtures zijn.
 */
class ScenarioMutatieTest {

    private static final Path MAP = Path.of("src/test/resources/scenario-mutaties");

    private static List<Path> fixtures() throws IOException {
        try (Stream<Path> s = Files.list(MAP)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".rgs")).sorted().toList();
        }
    }

    @Test void er_zijn_minstens_tien_mutatiefixtures() throws IOException {
        int n = fixtures().size();
        assertTrue(n >= 10, () -> "G3 vereist ≥ 10 mutatiefixtures, gevonden: " + n);
    }

    @TestFactory
    Stream<DynamicTest> elk_mutatiefixture_resolvet_schoon() throws IOException {
        return fixtures().stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> {
            ScenarioBundel b = ScenarioResolver.resolve(Files.readString(p));
            assertTrue(b.isOk(),
                    () -> p.getFileName() + " zou SCHOON moeten resolven (het falen hoort bij de runner, niet bij "
                            + "het resolven), maar de resolver meldt: " + b.model().errors());
            assertEquals(1, b.scenarios().size(),
                    () -> p.getFileName() + " zou precies één scenario moeten opleveren");
        }));
    }
}
