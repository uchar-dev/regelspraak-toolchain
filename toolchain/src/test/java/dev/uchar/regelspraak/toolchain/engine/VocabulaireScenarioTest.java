package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
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
 * quotes the rule text as written; the CLI's {@code --vocabulaire} names the rules of the trace in it, and refuses a
 * vocabulary the model does not declare as a wrong invocation.
 */
class VocabulaireScenarioTest {

    private static final Path BRON = Path.of("../uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs");
    private static final Path SCENARIO = Path.of("src/test/resources/vocabulaires/jutterswet-vocabulaires.scenario.rgs");

    private static String document() throws IOException {
        return Files.readString(BRON) + "\n" + Files.readString(SCENARIO);
    }

    @Test
    void de_uitleg_noemt_de_declaraties_in_het_gekozen_vocabulaire() throws IOException {
        List<ScenarioRunner.Uitgelegd> u = ScenarioRunner.runMetUitleg(ScenarioResolver.resolve(document()), "handboek");
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).resultaat().status(), () -> u.get(0).resultaat().diagnose());
        assertNull(u.get(0).uitlegFout(), u.get(0)::uitlegFout);
        assertEquals(List.of(
                "J1 is komt in aanmerking voor premie, omdat zijn vondstbedrag (12,60) is groter dan 10",
                "V1 is van waarde, omdat zijn soort (amber) is gelijk aan amber",
                "V2 is niet van waarde, want niet geldt: zijn soort (hout van een wrak) is gelijk aan amber"),
                u.get(0).uitleg());
        // canonically the same sentences in the canonical names
        List<ScenarioRunner.Uitgelegd> c = ScenarioRunner.runMetUitleg(ScenarioResolver.resolve(document()));
        assertEquals("V1 is waardevol, omdat zijn soort (barnsteen) is gelijk aan barnsteen", c.get(0).uitleg().get(1));
    }

    @Test
    void de_cli_noemt_de_regels_van_de_trace_in_het_vocabulaire() throws IOException {
        Path doc = Files.createTempFile("vocabulaires-", ".rgs");
        try {
            Files.writeString(doc, document());
            Uitvoer u = voerUit("--trace", "--uitleg", "--vocabulaire", "cgm", doc.toString());
            assertEquals(0, u.code(), u.fout());
            assertTrue(u.uit().contains("      R_PREMIE_VONDST @ [V1, V2] x3"), u.uit());
            assertTrue(u.uit().contains("      R_PREMIEWAARDIG @ [J1] x1"), u.uit());
            assertTrue(u.uit().contains("      V1 is WAARDEVOL, omdat zijn soort (BARNSTEEN) is gelijk aan BARNSTEEN"),
                    u.uit());

            Uitvoer onbekend = voerUit("--trace", "--vocabulaire", "wetboek", doc.toString());
            assertEquals(2, onbekend.code(), onbekend.uit());
            assertTrue(onbekend.fout().contains("--vocabulaire: het vocabulaire 'wetboek' is niet gedeclareerd;"
                    + " het model declareert 'handboek' en 'cgm'"), onbekend.fout());

            Uitvoer manifest = voerUit("--vocabulaire", "cgm", "--manifest", doc.toString());
            assertEquals(2, manifest.code());
            assertTrue(manifest.fout().contains("--vocabulaire geldt voor losse .rgs-bestanden"), manifest.fout());
        } finally {
            Files.delete(doc);
        }
    }

    private record Uitvoer(int code, String uit, String fout) {}

    /** The CLI with {@code args}, its standard output and error captured. */
    private static Uitvoer voerUit(String... args) {
        PrintStream uit = System.out, fout = System.err;
        ByteArrayOutputStream u = new ByteArrayOutputStream(), f = new ByteArrayOutputStream();
        System.setOut(new PrintStream(u, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(f, true, StandardCharsets.UTF_8));
        try {
            int code = ScenarioRunnerCli.voerUit(args);
            return new Uitvoer(code, u.toString(StandardCharsets.UTF_8), f.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(uit);
            System.setErr(fout);
        }
    }
}
