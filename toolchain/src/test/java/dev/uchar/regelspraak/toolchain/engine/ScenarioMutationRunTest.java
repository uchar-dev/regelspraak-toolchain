package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mutation gate, running half. The 11 fixtures in {@code src/test/resources/scenario-mutaties/} each carry a
 * deliberately-WRONG expectation (off-by-one value, wrong-but-compatible unit, missing/unexpected kenmerk,
 * inconsistentie verwacht-maar-afwezig / consistent-maar-inconsistent, leeg-vs-gevuld, tekst/enum/bezittelijk
 * mismatch); uchar's frontend suite pins that they resolve cleanly. THIS test drives the runner and asserts each
 * scenario FAILS on a named expectation with a diagnose that carries the diverging value (verwacht vs werkelijk) —
 * so the runner cannot silently pass a bad expectation.
 */
class ScenarioMutationRunTest {

    private static final Path MAP = Path.of("src/test/resources/scenario-mutations");

    private static List<Path> fixtures() throws IOException {
        try (Stream<Path> s = Files.list(MAP)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".rgs")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> each_mutation_fixture_fails_with_a_diagnostic() throws IOException {
        List<Path> fixtures = fixtures();
        assertTrue(fixtures.size() >= 10, () -> "G3 vereist ≥ 10 mutatiefixtures, gevonden: " + fixtures.size());
        return fixtures.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> {
            List<ScenarioResultaat> res = ScenarioRunner.run(Files.readString(p));
            assertEquals(1, res.size(), () -> p.getFileName() + " zou precies één scenario moeten opleveren");
            ScenarioResultaat r = res.get(0);

            assertEquals(Status.FAIL, r.status(),
                    () -> p.getFileName() + " zou moeten FALEN (bewust-foute verwachting), kreeg " + r.status()
                            + " — verwachtingen: " + r.verwachtingen());

            // at least one expectation is not-ok, named by its position in the scenario, and the diagnose names the
            // diverging value (verwacht + werkelijk). uchar's report names an expectation by its position in the
            // case; the scenario's source line does not reach the case file.
            ScenarioResultaat.VerwachtingResultaat error = r.verwachtingen().stream().filter(v -> !v.ok()).findFirst()
                    .orElseThrow(() -> new AssertionError(p.getFileName() + ": minstens één verwachting hoort niet-ok te zijn"));
            assertTrue(error.positie() >= 1, () -> p.getFileName() + ": de falende verwachting hoort haar positie te dragen");
            assertFalse(r.diagnose().isBlank(), () -> p.getFileName() + ": een falend scenario moet een diagnose dragen");
            assertTrue(r.diagnose().contains("verwacht") && r.diagnose().contains("werkelijk"),
                    () -> p.getFileName() + ": de diagnose moet verwacht én werkelijk noemen: " + r.diagnose());
        }));
    }
}
