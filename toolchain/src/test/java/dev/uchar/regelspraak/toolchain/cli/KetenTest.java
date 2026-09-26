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
class KetenTest {

    private static final String BOOT = "Objecttype de Boot (mv: Boten)\n    de lengte Numeriek (getal met 2 decimalen);\n";
    private static final String HAVEN = "Objecttype de Haven (mv: Havens)\n    de naam Tekst;\n";
    /** Line 3 writes an attribute the object type does not declare: an ERROR on that line. */
    private static final String FOUTE_REGEL =
            "Regel breedte\ngeldig altijd\nDe breedte van een Boot moet berekend worden als 3.\n";

    @TempDir Path map;

    private String schrijf(String naam, String tekst) throws IOException {
        Path p = map.resolve(naam);
        Files.writeString(p, tekst);
        return p.toString();
    }

    @Test
    void een_diagnose_in_een_keten_noemt_bestand_en_regel_in_dat_bestand() throws IOException {
        String boot = schrijf("boot.rgs", BOOT);
        String regel = schrijf("regel.rgs", FOUTE_REGEL);
        var keten = RegelSpraakCli.Keten.lees(List.of(boot, regel));
        RegelSpraakModel m = Resolver.resolve(keten.bron());
        var fout = m.diagnostics.stream().filter(RegelSpraakModel.Diagnostic::isError).findFirst().orElseThrow();
        assertEquals(6, fout.line(), "regel 3 van het tweede bestand is regel 6 van de keten");
        assertTrue(keten.melding(fout).startsWith("ERROR (" + regel + ":3): "), keten.melding(fout));
    }

    @Test
    void een_enkel_bestand_houdt_de_diagnose_van_uchar() throws IOException {
        String regel = schrijf("regel.rgs", BOOT + FOUTE_REGEL);
        var keten = RegelSpraakCli.Keten.lees(List.of(regel));
        RegelSpraakModel m = Resolver.resolve(keten.bron());
        var fout = m.diagnostics.stream().filter(RegelSpraakModel.Diagnostic::isError).findFirst().orElseThrow();
        assertEquals(fout.toString(), keten.melding(fout));
    }

    @Test
    void facts_over_een_keten_schrijft_de_beans_van_elk_bestand() throws IOException {
        String boot = schrijf("boot.rgs", BOOT);
        String haven = schrijf("haven.rgs", HAVEN);
        Path uit = map.resolve("uit");
        assertEquals(0, RegelSpraakCli.factsModus(List.of(uit.toString(), boot, haven)));
        Path pakket = uit.resolve(FactModelGenerator.PAKKET.replace('.', '/'));
        assertTrue(Files.isRegularFile(pakket.resolve("Boot.java")), "Boot.java uit het eerste bestand");
        assertTrue(Files.isRegularFile(pakket.resolve("Haven.java")), "Haven.java uit het tweede bestand");
    }
}
