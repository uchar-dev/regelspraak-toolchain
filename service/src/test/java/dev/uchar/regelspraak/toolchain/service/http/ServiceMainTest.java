package dev.uchar.regelspraak.toolchain.service.http;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.Violation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ServiceMain} CLI classification — arg parsing and the exit-code discipline, tested DIRECTLY (never by
 * forking a JVM). The exit-3 MARKER path is pinned two ways: (1) the real {@link DecisionCore} startup over a
 * marker scope WITHOUT {@code --allow-markers} throws a {@link ServiceError} whose schendingen are all MARKER and
 * whose message NAMES the rule; (2) {@link ServiceMain#exitCodeFor} maps exactly that fout to exit 3, and any
 * other fout to exit 1.
 */
class ServiceMainTest {

    // the same self-contained marker construct MarkerFailFastTest uses.
    private static final String MARKER_SOURCE = """
        Objecttype de Vlucht (mv: Vluchten)
            de heffing Numeriek (niet-negatief geheel getal);
            is belast kenmerk (bijvoeglijk) voor elke dag;
        Regel heffing markert
        geldig altijd
        De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
        """;
    private static final String MARKER_DESCRIPTOR = """
        { "service": {"name":"marker-test","version":"1"},
          "entrypoints": [ { "name":"heffing markert","path":"heffing-markert",
            "scope": {"ruleNames": ["heffing markert"]},
            "input": ["Vlucht"], "output": ["Vlucht.heffing"] } ],
          "parameters": {} }""";

    private static Map<String, String> markerSource() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("marker.rgs", MARKER_SOURCE);
        return m;
    }

    // ---------------- exit-3 marker discipline (no JVM fork) ----------------

    @Test
    void marker_startup_without_allow_markers_classifies_as_exit_3_and_names_the_rule() {
        ServiceError error = assertThrows(ServiceError.class, () -> DecisionCore.assemble(
                MARKER_DESCRIPTOR, markerSource(), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
        // the fout the CLI main would catch: named rule, all-MARKER schendingen
        assertTrue(error.getMessage().contains("heffing markert"), error.getMessage());
        assertTrue(error.violations().stream().allMatch(s -> s.kind() == Violation.Kind.MARKER),
                error.getMessage());
        // ...and the CLI maps it to exit 3.
        assertEquals(ServiceMain.EXIT_MARKER, ServiceMain.exitCodeFor(error));
    }

    @Test
    void a_non_marker_startup_error_classifies_as_exit_1() {
        ServiceError other = new ServiceError("descriptor valideert niet", List.of(
                Violation.van(Violation.Kind.UNKNOWN_REFERENCE, "entrypoints[0].scope.ruleNames[0]",
                        "onbekende regel 'nergens'")));
        assertEquals(ServiceMain.EXIT_OTHER, ServiceMain.exitCodeFor(other));
    }

    @Test
    void mixed_violations_are_no_pure_marker_so_exit_1() {
        ServiceError mixed = new ServiceError("gemengd", List.of(
                Violation.van(Violation.Kind.MARKER, "entrypoint:x", "marker 'a'"),
                Violation.van(Violation.Kind.UNKNOWN_REFERENCE, "y", "onbekend 'b'")));
        assertEquals(ServiceMain.EXIT_OTHER, ServiceMain.exitCodeFor(mixed));
    }

    @Test
    void with_allow_markers_the_service_indeed_starts_the_marker() {
        // the exit-3 path is exactly what --allow-markers clears: the same descriptor now starts and exposes it.
        DecisionCore core = DecisionCore.assemble(MARKER_DESCRIPTOR, markerSource(), CoreConfig.withMarkers(),
                Thread.currentThread().getContextClassLoader());
        assertTrue(core.entrypoint("heffing-markert").markers().contains("heffing markert"));
    }

    // ---------------- arg parsing ----------------

    @Test
    void args_parse_descriptor_sources_flags() {
        ServiceMain.Args a = ServiceMain.parseArgs(new String[]{
                "toka.service.json", "a.rgs", "b.rgs", "--allow-markers", "--port", "9090"});
        assertEquals(Path.of("toka.service.json"), a.descriptor());
        assertEquals(List.of(Path.of("a.rgs"), Path.of("b.rgs")), a.sources());
        assertTrue(a.allowMarkers());
        assertEquals(9090, a.port());
    }

    @Test
    void args_default_gate_8080_no_allow_markers() {
        ServiceMain.Args a = ServiceMain.parseArgs(new String[]{"d.json", "x.rgs"});
        assertEquals(8080, a.port());
        assertTrue(!a.allowMarkers());
        assertEquals("0.0.0.0", a.host());
        // a local caller on a free port binds the loopback address: a port free on every interface can still be
        // bound on 127.0.0.1 alone by another process, which would then answer
        ServiceMain.Args local = ServiceMain.parseArgs(new String[]{"d.json", "ir.json", "--host", "127.0.0.1", "--port", "0"});
        assertEquals("127.0.0.1", local.host());
        assertEquals(0, local.port());
    }

    @Test
    void args_without_source_or_descriptor_fail() {
        assertThrows(IllegalArgumentException.class, () -> ServiceMain.parseArgs(new String[]{}));
        assertThrows(IllegalArgumentException.class, () -> ServiceMain.parseArgs(new String[]{"alleen-descriptor.json"}));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceMain.parseArgs(new String[]{"d.json", "x.rgs", "--port"}));
    }
}
