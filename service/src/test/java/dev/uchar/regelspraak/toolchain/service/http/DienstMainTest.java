package dev.uchar.regelspraak.toolchain.service.http;

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.DienstFout;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.Schending;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link DienstMain} CLI classification — arg parsing and the exit-code discipline, tested DIRECTLY (never by
 * forking a JVM). The exit-3 MARKER path is pinned two ways: (1) the real {@link BeslisserKern} startup over a
 * marker scope WITHOUT {@code --allow-markers} throws a {@link DienstFout} whose schendingen are all MARKER and
 * whose message NAMES the rule; (2) {@link DienstMain#exitCodeVoor} maps exactly that fout to exit 3, and any
 * other fout to exit 1.
 */
class DienstMainTest {

    // the same self-contained marker construct MarkerFailFastTest uses.
    private static final String MARKER_BRON = """
        Objecttype de Vlucht (mv: Vluchten)
            de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
        Regel heffing markert
        geldig altijd
        De heffing van een Vlucht moet berekend worden als het absolute tijdsevenredig deel per maand van 10,00 EUR.
        """;
    private static final String MARKER_DESCRIPTOR = """
        { "dienst": {"naam":"marker-test","versie":"1"},
          "entrypoints": [ { "naam":"heffing markert","pad":"heffing-markert",
            "scope": {"regelnamen": ["heffing markert"]},
            "invoer": ["Vlucht"], "uitvoer": ["Vlucht.heffing"] } ],
          "parameters": {} }""";

    private static Map<String, String> markerBron() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("marker.rgs", MARKER_BRON);
        return m;
    }

    // ---------------- exit-3 marker discipline (no JVM fork) ----------------

    @Test
    void marker_startup_zonder_allow_markers_classificeert_als_exit_3_en_noemt_de_regel() {
        DienstFout fout = assertThrows(DienstFout.class, () -> BeslisserKern.bouw(
                MARKER_DESCRIPTOR, markerBron(), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader()));
        // the fout the CLI main would catch: named rule, all-MARKER schendingen
        assertTrue(fout.getMessage().contains("heffing markert"), fout.getMessage());
        assertTrue(fout.schendingen().stream().allMatch(s -> s.soort() == Schending.Soort.MARKER),
                fout.getMessage());
        // ...and the CLI maps it to exit 3.
        assertEquals(DienstMain.EXIT_MARKER, DienstMain.exitCodeVoor(fout));
    }

    @Test
    void een_niet_marker_startfout_classificeert_als_exit_1() {
        DienstFout ander = new DienstFout("descriptor valideert niet", List.of(
                Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING, "entrypoints[0].scope.regelnamen[0]",
                        "onbekende regel 'nergens'")));
        assertEquals(DienstMain.EXIT_ANDER, DienstMain.exitCodeVoor(ander));
    }

    @Test
    void gemengde_schendingen_zijn_geen_zuivere_marker_dus_exit_1() {
        DienstFout gemengd = new DienstFout("gemengd", List.of(
                Schending.van(Schending.Soort.MARKER, "entrypoint:x", "marker 'a'"),
                Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING, "y", "onbekend 'b'")));
        assertEquals(DienstMain.EXIT_ANDER, DienstMain.exitCodeVoor(gemengd));
    }

    @Test
    void met_allow_markers_start_de_marker_dienst_wel() {
        // the exit-3 path is exactly what --allow-markers clears: the same descriptor now starts and exposes it.
        BeslisserKern kern = BeslisserKern.bouw(MARKER_DESCRIPTOR, markerBron(), KernConfig.metMarkers(),
                Thread.currentThread().getContextClassLoader());
        assertTrue(kern.entrypoint("heffing-markert").markers().contains("heffing markert"));
    }

    // ---------------- arg parsing ----------------

    @Test
    void args_parseren_descriptor_bronnen_flags() {
        DienstMain.Args a = DienstMain.parseArgs(new String[]{
                "toka.dienst.json", "a.rgs", "b.rgs", "--allow-markers", "--poort", "9090"});
        assertEquals(Path.of("toka.dienst.json"), a.descriptor());
        assertEquals(List.of(Path.of("a.rgs"), Path.of("b.rgs")), a.bronnen());
        assertTrue(a.allowMarkers());
        assertEquals(9090, a.poort());
    }

    @Test
    void args_default_poort_8080_geen_allow_markers() {
        DienstMain.Args a = DienstMain.parseArgs(new String[]{"d.json", "x.rgs"});
        assertEquals(8080, a.poort());
        assertTrue(!a.allowMarkers());
        assertEquals("0.0.0.0", a.host());
        // a local caller on a free port binds the loopback address: a port free on every interface can still be
        // bound on 127.0.0.1 alone by another process, which would then answer
        DienstMain.Args lokaal = DienstMain.parseArgs(new String[]{"d.json", "ir.json", "--host", "127.0.0.1", "--poort", "0"});
        assertEquals("127.0.0.1", lokaal.host());
        assertEquals(0, lokaal.poort());
    }

    @Test
    void args_zonder_bron_of_descriptor_falen() {
        assertThrows(IllegalArgumentException.class, () -> DienstMain.parseArgs(new String[]{}));
        assertThrows(IllegalArgumentException.class, () -> DienstMain.parseArgs(new String[]{"alleen-descriptor.json"}));
        assertThrows(IllegalArgumentException.class,
                () -> DienstMain.parseArgs(new String[]{"d.json", "x.rgs", "--poort"}));
    }
}
