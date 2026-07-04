package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.Status;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G3 mutatiegate — the RUNNING half (docs/SCENARIOTOETSEN.md, gate G3). The 11 fixtures in
 * {@code src/test/resources/scenario-mutaties/} each carry a deliberately-WRONG expectation (off-by-one value,
 * wrong-but-compatible unit, missing/unexpected kenmerk, inconsistentie verwacht-maar-afwezig / consistent-maar-
 * inconsistent, leeg-vs-gevuld, tekst/enum/bezittelijk mismatch). The jar-only {@code ScenarioMutatieTest} pins
 * that each RESOLVES cleanly (the failure belongs to the runner, not the resolver); THIS test drives the actual
 * runner and asserts each scenario FAILS with a diagnose that names the rule/expectation AND the diverging value
 * (verwacht vs werkelijk) — so the runner cannot silently pass a bad expectation.
 */
class ScenarioMutatieRunTest {

    private static final Path MAP = Path.of("../src/test/resources/scenario-mutaties");

    private static List<Path> fixtures() throws IOException {
        try (Stream<Path> s = Files.list(MAP)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".rs")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> elk_mutatiefixture_faalt_met_een_diagnose() throws IOException {
        List<Path> fixtures = fixtures();
        assertTrue(fixtures.size() >= 10, () -> "G3 vereist ≥ 10 mutatiefixtures, gevonden: " + fixtures.size());
        return fixtures.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> {
            List<ScenarioResultaat> res = ScenarioRunner.run(Files.readString(p));
            assertEquals(1, res.size(), () -> p.getFileName() + " zou precies één scenario moeten opleveren");
            ScenarioResultaat r = res.get(0);

            assertEquals(Status.FAIL, r.status(),
                    () -> p.getFileName() + " zou moeten FALEN (bewust-foute verwachting), kreeg " + r.status()
                            + " — verwachtingen: " + r.verwachtingen());

            // at least one expectation is not-ok, and the diagnose names the diverging value (verwacht + werkelijk).
            assertTrue(r.verwachtingen().stream().anyMatch(v -> !v.ok()),
                    () -> p.getFileName() + ": minstens één verwachting hoort niet-ok te zijn");
            assertFalse(r.diagnose().isBlank(), () -> p.getFileName() + ": een falend scenario moet een diagnose dragen");
            assertTrue(r.diagnose().contains("verwacht") && r.diagnose().contains("werkelijk"),
                    () -> p.getFileName() + ": de diagnose moet verwacht én werkelijk noemen: " + r.diagnose());
            assertTrue(r.diagnose().matches("(?s).*regel \\d+.*"),
                    () -> p.getFileName() + ": de diagnose moet de (verwachting)regel noemen: " + r.diagnose());
        }));
    }
}
