package dev.uchar.regelspraak.toolchain.cli;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CLI reads several sources as one model (bin/regelspraak check, drl, facts and graaf take a chain). A line of
 * the concatenation is a line no file has, so a diagnostic in a chain names the file and the line in that file; one
 * file keeps uchar's own diagnostic text.
 */
class ChainTest {

    private static final String BOOT = "Objecttype de Boot (mv: Boten)\n    de lengte Numeriek (getal met 2 decimalen);\n";
    private static final String HAVEN = "Objecttype de Haven (mv: Havens)\n    de naam Tekst;\n";
    /** Line 3 writes an attribute the object type does not declare: an ERROR on that line. */
    private static final String WRONG_LINE =
            "Regel breedte\ngeldig altijd\nDe breedte van een Boot moet berekend worden als 3.\n";

    @TempDir Path map;

    private String write(String name, String text) throws IOException {
        Path p = map.resolve(name);
        Files.writeString(p, text);
        return p.toString();
    }

    @Test
    void a_diagnostic_in_a_chain_names_file_and_line_in_that_file() throws IOException {
        String boot = write("boot.rgs", BOOT);
        String line = write("regel.rgs", WRONG_LINE);
        var chain = RegelSpraakCli.Chain.read(List.of(boot, line));
        RegelSpraakModel m = Resolver.resolve(chain.source());
        var error = m.diagnostics.stream().filter(RegelSpraakModel.Diagnostic::isError).findFirst().orElseThrow();
        assertEquals(6, error.line(), "regel 3 van het tweede bestand is regel 6 van de keten");
        assertTrue(chain.message(error).startsWith("ERROR (" + line + ":3): "), chain.message(error));
    }

    @Test
    void a_single_file_keeps_the_diagnostic_of_uchar() throws IOException {
        String line = write("regel.rgs", BOOT + WRONG_LINE);
        var chain = RegelSpraakCli.Chain.read(List.of(line));
        RegelSpraakModel m = Resolver.resolve(chain.source());
        var error = m.diagnostics.stream().filter(RegelSpraakModel.Diagnostic::isError).findFirst().orElseThrow();
        assertEquals(error.toString(), chain.message(error));
    }

    @Test
    void facts_over_a_chain_writes_the_beans_of_each_file() throws IOException {
        String boot = write("boot.rgs", BOOT);
        String haven = write("haven.rgs", HAVEN);
        Path sink = map.resolve("uit");
        assertEquals(0, RegelSpraakCli.factsMode(List.of(sink.toString(), boot, haven)));
        Path pkg = sink.resolve(FactModelGenerator.PAKKET.replace('.', '/'));
        assertTrue(Files.isRegularFile(pkg.resolve("Boot.java")), "Boot.java uit het eerste bestand");
        assertTrue(Files.isRegularFile(pkg.resolve("Haven.java")), "Haven.java uit het tweede bestand");
    }
}
