package dev.uchar.regelspraak.toolchain.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A line that carries a value or sentence of uchar is labelled in uchar's language, which {@code --language} chooses;
 * the toolchain's other messages are Dutch (card uchar-text-in-uchar-language). Each run goes through
 * {@code bin/regelspraak} in its own JVM, as {@link CheckCommandTest} does: uchar fixes some sentences when a class is
 * first initialised ({@code ComputeFault}, {@code RegelSpraakRuntime}), so a run under {@code en} in the suite's JVM
 * could leave them English for every test after it.
 */
class UcharTextLanguageTest {

    private static final Path SCRIPT = Path.of("..", "bin", "regelspraak").toAbsolutePath().normalize();

    /** Z1 weighs 40, so the rule does not assign the characteristic the expectation asks for. */
    private static final String FAILED_EXPECTATION = """
            Objecttype de Zending (mv: Zendingen)
                het gewicht Numeriek (positief geheel getal);
                is zware zending kenmerk;

            Regel Kenmerktoekenning zware zending
            geldig altijd
            Een Zending is een zware zending indien het gewicht van de Zending groter is dan 100.

            Situatie licht:
            een Zending Z1
            - het gewicht is 40

            Scenario lichte zending
            toetst de regel Kenmerktoekenning zware zending
            gebaseerd op de situatie licht
            Met als verwacht resultaat:
            - Z1 is een zware zending
            """;

    /** Two rules write different values into one attribute: no fixpoint, so the case fails without a failed
     *  expectation and the report gives uchar's verdict. */
    private static final String WRITERS_CONFLICT = """
            Objecttype de Zending (mv: Zendingen)
                het gewicht Numeriek (geheel getal);
                de toeslag Numeriek (geheel getal);

            Regel gewone toeslag
            geldig altijd
            De toeslag van een Zending moet berekend worden als 5.

            Regel zware toeslag
            geldig altijd
            De toeslag van een Zending moet berekend worden als 9 indien het gewicht van de Zending groter is dan 100.

            Scenario zware zending
            Gegeven is de volgende situatie:
            een Zending Z1
            - het gewicht is 150
            Met als verwacht resultaat:
            - de toeslag van Z1 is 9
            """;

    /** The expectation on line 9 names an attribute the object type does not declare. */
    private static final String UNKNOWN_ATTRIBUTE = """
            Objecttype de Zending (mv: Zendingen)
                het gewicht Numeriek (geheel getal);

            Scenario lengte
            Gegeven is de volgende situatie:
            een Zending Z1
            - het gewicht is 150
            Met als verwacht resultaat:
            - de lengte van Z1 is 9
            """;

    /** A day count in a value, which uchar's branches compute only as the timeline of a time-dependent target. */
    private static final String NOT_CARRIED = """
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (niet-negatief geheel getal);
                is belast kenmerk (bijvoeglijk) voor elke dag;
            Regel heffing markert
            geldig altijd
            De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
            """;

    @TempDir Path dir;

    private record Run(int exit, String stdout, String stderr) {
        boolean hasLine(String stream, String line) { return stream.lines().anyMatch(line::equals); }
    }

    @Test
    void a_failed_expectation_is_labelled_in_the_language_of_its_values() throws Exception {
        Files.writeString(dir.resolve("bron.rgs"), FAILED_EXPECTATION);
        Run en = run("--language", "en", "scenario", "bron.rgs");
        assertEquals(1, en.exit(), () -> "en: " + en);
        assertTrue(en.hasLine(en.stdout(), "FAIL  lichte zending"), () -> "en: " + en);
        assertTrue(en.hasLine(en.stdout(),
                "    expectation 1 FAIL: characteristicPresent Z1 zware zending  (expected=present, actual=absent)"),
                () -> "en: " + en);
        // a line without a text of uchar stays the toolchain's own, Dutch
        assertTrue(en.hasLine(en.stdout(), "    dekking:"), () -> "en: " + en);

        Run nl = run("scenario", "bron.rgs");
        assertEquals(1, nl.exit(), () -> "nl: " + nl);
        assertTrue(nl.hasLine(nl.stdout(),
                "    verwachting 1 FOUT: characteristicPresent Z1 zware zending  (verwacht=aanwezig, werkelijk=afwezig)"),
                () -> "nl: " + nl);
    }

    @Test
    void the_verdict_of_a_case_without_a_failed_expectation_is_labelled_in_its_language() throws Exception {
        Files.writeString(dir.resolve("bron.rgs"), WRITERS_CONFLICT);
        Run en = run("--language", "en", "scenario", "bron.rgs");
        assertEquals(1, en.exit(), () -> "en: " + en);
        assertTrue(en.stdout().lines().anyMatch(l -> l.startsWith("    FAIL: ")
                && l.contains("WritersConflict: no fixpoint: two writers")), () -> "en: " + en);

        Run nl = run("scenario", "bron.rgs");
        assertEquals(1, nl.exit(), () -> "nl: " + nl);
        assertTrue(nl.stdout().lines().anyMatch(l -> l.startsWith("    FOUT: ")
                && l.contains("WritersConflict: geen fixpunt: twee schrijvers")), () -> "nl: " + nl);
    }

    @Test
    void a_diagnostic_of_a_source_that_does_not_resolve_is_labelled_in_its_language() throws Exception {
        Files.writeString(dir.resolve("bron.rgs"), UNKNOWN_ATTRIBUTE);
        Run en = run("--language", "en", "scenario", "bron.rgs");
        assertEquals(1, en.exit(), () -> "en: " + en);
        assertTrue(en.hasLine(en.stderr(), "de bron resolvet niet — 1 diagnose(s):"), () -> "en: " + en);
        assertTrue(en.stderr().lines().anyMatch(l -> l.startsWith("  line 9: scenario: the object type 'Zending'")),
                () -> "en: " + en);

        Run nl = run("scenario", "bron.rgs");
        assertEquals(1, nl.exit(), () -> "nl: " + nl);
        assertTrue(nl.stderr().lines().anyMatch(l -> l.startsWith("  regel 9: scenario: het objecttype 'Zending'")),
                () -> "nl: " + nl);
    }

    @Test
    void the_refusal_of_uchar_in_check_and_drl_is_labelled_in_its_language() throws Exception {
        Files.writeString(dir.resolve("bron.rgs"), NOT_CARRIED);
        String en = "  not executable in uchar (exit 3): transpiler SKIP: regel: heffing markert — a BuiltinCall";
        String nl = "  niet uitvoerbaar in uchar (exit 3): transpiler SKIP: regel: heffing markert — een BuiltinCall";

        Run checkEn = run("--language", "en", "check", "bron.rgs");
        assertEquals(3, checkEn.exit(), () -> "check en: " + checkEn);
        assertTrue(checkEn.stdout().contains(en), () -> "check en: " + checkEn);
        Run checkNl = run("check", "bron.rgs");
        assertEquals(3, checkNl.exit(), () -> "check nl: " + checkNl);
        assertTrue(checkNl.stdout().contains(nl), () -> "check nl: " + checkNl);

        Run drlEn = run("--language", "en", "drl", "bron.rgs");
        assertEquals(3, drlEn.exit(), () -> "drl en: " + drlEn);
        assertTrue(drlEn.stderr().contains(en) && drlEn.stdout().isEmpty(), () -> "drl en: " + drlEn);
        Run drlNl = run("drl", "bron.rgs");
        assertEquals(3, drlNl.exit(), () -> "drl nl: " + drlNl);
        assertTrue(drlNl.stderr().contains(nl) && drlNl.stdout().isEmpty(), () -> "drl nl: " + drlNl);
    }

    /** {@code bin/regelspraak} with {@code args}, in {@link #dir}, on a jar that carries this JVM's class path. */
    private Run run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        Path out = Files.createTempFile(dir, "stdout", ".txt");
        Path err = Files.createTempFile(dir, "stderr", ".txt");
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().put("REGELSPRAAK_JAR", classPathJar().toString());
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.environment().remove("JAVA_OPTS");
        Process p = pb.start();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "bin/regelspraak liep niet binnen 120 s af");
        return new Run(p.exitValue(), Files.readString(out), Files.readString(err));
    }

    /** A jar with only a manifest, whose Class-Path is this JVM's class path: what {@code java -cp <jar>} runs on. */
    private Path classPathJar() throws IOException {
        Path jar = dir.resolve("classpath.jar");
        if (Files.exists(jar)) return jar;
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH,
                Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                        .map(entry -> Path.of(entry).toAbsolutePath().toUri().toString())
                        .collect(Collectors.joining(" ")));
        try (var ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            return jar;
        }
    }
}
