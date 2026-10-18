package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scenario route in a vocabulary: uchar's golden {@code jutterswet-vocabulaires} (the submodule) with an own
 * scenario block. The explanation names the characteristics and the enumeration values in the chosen vocabulary and
 * quotes the rule text as written; the CLI's {@code --vocabulary} names the rules of the trace in it, and refuses a
 * vocabulary the model does not declare as a wrong invocation.
 */
class VocabularyScenarioTest {

    private static final Path SOURCE = Path.of("../uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs");
    private static final Path SCENARIO = Path.of("src/test/resources/vocabularies/jutterswet-vocabulaires.scenario.rgs");

    private static String document() throws IOException {
        return Files.readString(SOURCE) + "\n" + Files.readString(SCENARIO);
    }

    @Test
    void the_uitleg_names_the_declarations_in_the_chosen_vocabulary() throws IOException {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(document()), "handboek");
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        assertEquals(List.of(
                "J1 is komt in aanmerking voor premie, omdat zijn vondstbedrag (12,60) is groter dan 10",
                "V1 is van waarde, omdat zijn soort (amber · barnsteen) is gelijk aan amber · barnsteen",
                "V2 is niet van waarde, want niet geldt: zijn soort (hout van een wrak · wrakhout) is gelijk aan"
                        + " amber · barnsteen"),
                u.get(0).explanation());
        // canonically the same sentences in the canonical names
        List<ScenarioRunner.Explained> c = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(document()));
        assertEquals("V1 is waardevol, omdat zijn soort (barnsteen) is gelijk aan barnsteen", c.get(0).explanation().get(1));
    }

    @Test
    void the_cli_names_the_rules_of_the_trace_in_the_vocabulary() throws IOException {
        Path doc = Files.createTempFile("vocabulaires-", ".rgs");
        try {
            Files.writeString(doc, document());
            Output u = run("--trace", "--explanation", "--vocabulary", "cgm", doc.toString());
            assertEquals(0, u.code(), u.error());
            assertTrue(u.sink().contains("      R_PREMIE_VONDST @ [V1, V2] x2"), u.sink());
            assertTrue(u.sink().contains("      R_PREMIEWAARDIG @ [J1] x1"), u.sink());
            assertTrue(u.sink().contains("      V1 is WAARDEVOL, omdat zijn soort (BARNSTEEN) is gelijk aan BARNSTEEN"),
                    u.sink());

            Output unknown = run("--trace", "--vocabulary", "wetboek", doc.toString());
            assertEquals(2, unknown.code(), unknown.sink());
            assertTrue(unknown.error().contains("--vocabulary: het vocabulaire 'wetboek' is niet gedeclareerd;"
                    + " het model declareert 'handboek' en 'cgm'"), unknown.error());

            Output manifest = run("--vocabulary", "cgm", "--manifest", doc.toString());
            assertEquals(2, manifest.code());
            assertTrue(manifest.error().contains("--vocabulary geldt voor losse .rgs-bestanden"), manifest.error());
        } finally {
            Files.delete(doc);
        }
    }

    private record Output(int code, String sink, String error) {}

    /** The CLI with {@code args}, its standard output and error captured. */
    private static Output run(String... args) {
        PrintStream sink = System.out, error = System.err;
        ByteArrayOutputStream u = new ByteArrayOutputStream(), f = new ByteArrayOutputStream();
        System.setOut(new PrintStream(u, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(f, true, StandardCharsets.UTF_8));
        try {
            int code = ScenarioRunnerCli.run(args);
            return new Output(code, u.toString(StandardCharsets.UTF_8), f.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(sink);
            System.setErr(error);
        }
    }
}
