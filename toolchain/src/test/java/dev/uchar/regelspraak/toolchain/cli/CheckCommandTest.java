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
 * {@code bin/regelspraak check} and {@code drl} on a source with a name the resolver binds to no declaration: uchar
 * leaves an {@code Unresolved} node in the document with a warning, and its executors fault on it only when a
 * request reaches the rule (WF-044). The distribution refuses the source before that, as it refuses a resolver
 * error: exit 1, with the name, the rule and the line. A form the IR carries without a norm reaches the document as
 * the same node, with RGS-092; there {@code check} names the form and exits 3. The test runs the script on {@code REGELSPRAAK_JAR}, a jar
 * whose manifest carries this module's test class path, as {@link TokensCommandTest} does.
 */
class CheckCommandTest {

    private static final Path SCRIPT = Path.of("..", "bin", "regelspraak").toAbsolutePath().normalize();

    /** Rule bijvoeglijk writes the een-form over the adjectival characteristic {@code minderjarig}, on line 7. */
    private static final String SOURCE = """
            Objecttype de Passagier
                is student kenmerk;
                is minderjarig kenmerk (bijvoeglijk);
                is kind kenmerk;
            Regel bijvoeglijk
                geldig altijd
                    Een passagier is kind indien hij een minderjarig is.
            """;

    private static final String REFUSAL =
            "ERROR (line 7): onopgeloste naam 'hij een minderjarig is' in de regel 'bijvoeglijk'";

    @TempDir Path dir;

    private record Run(int exit, String stdout, String stderr) {}

    @Test
    void check_refuses_an_unresolved_name_with_exit_1() throws Exception {
        Files.writeString(dir.resolve("bron.rgs"), SOURCE);
        Run r = run("check", "bron.rgs");
        assertEquals(1, r.exit(), () -> "check: " + r);
        assertTrue(r.stdout().contains(REFUSAL), () -> "check: " + r);
    }

    @Test
    void drl_refuses_an_unresolved_name_with_exit_1() throws Exception {
        Files.writeString(dir.resolve("bron.rgs"), SOURCE);
        Run r = run("drl", "bron.rgs");
        assertEquals(1, r.exit(), () -> "drl: " + r);
        assertTrue(r.stderr().contains(REFUSAL) && r.stdout().isEmpty(), () -> "drl: " + r);
    }

    /** A model for the three forms the IR carries without a norm; the rule under test starts on line 11, its sentence
     *  stands on line 13. */
    private static final String FORMS_WITHOUT_NORM = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de naam Tekst;
                het vondstmoment Datum en tijd in millisecondes;
                de uitkomst Numeriek (geheel getal);
                is waardevol kenmerk (bijvoeglijk);

            Regel controle uitkomst
                geldig altijd
                    De uitkomst van een Jutter moet groter zijn dan 0.

            """;

    /** uchar writes an {@code Unresolved} node with RGS-092 for these forms; the name is resolved, so the refusal
     *  names the form and its reason, and {@code check} exits 3: uchar does not carry the model. */
    private void refusesFormWithoutNorm(String rule, String expected) throws Exception {
        Files.writeString(dir.resolve("bron.rgs"), FORMS_WITHOUT_NORM + rule);
        Run r = run("check", "bron.rgs");
        assertEquals(3, r.exit(), () -> "check: " + r);
        assertTrue(r.stdout().contains(expected), () -> "check: " + r);
        assertTrue(!r.stdout().contains("onopgeloste naam"), () -> "check: " + r);
    }

    @Test
    void check_refuses_the_eleven_test_as_a_form_without_norm() throws Exception {
        refusesFormWithoutNorm("""
                Regel waardevolle naam
                    geldig altijd
                        Een Jutter is waardevol indien zijn naam aan de elfproef voldoet.
                """, "ERROR (line 13): vorm zonder norm: het IR draagt de elfproef ('voldoet aan de elfproef') niet:"
                + " de vorm heeft geen norm (RUNTIME-SEMANTICS geeft hem geen betekenis)"
                + " — 'zijn naam aan de elfproef voldoet'");
    }

    @Test
    void check_refuses_a_time_of_day_part_as_a_form_without_norm() throws Exception {
        refusesFormWithoutNorm("""
                Regel uur van de vondst
                    geldig altijd
                        De uitkomst van een Jutter moet gesteld worden op het uur uit zijn vondstmoment.
                """, "ERROR (line 13): vorm zonder norm: het IR draagt het uur, de minuut, de seconde of de"
                + " milliseconde van een datum-tijd niet: de vorm heeft geen norm (RUNTIME-SEMANTICS geeft hem geen"
                + " betekenis) — 'het uur uit zijn vondstmoment'");
    }

    @Test
    void check_refuses_a_fired_rule_version_as_a_form_without_norm() throws Exception {
        refusesFormWithoutNorm("""
                Regel waardevolle uitkomst
                    geldig altijd
                        Een Jutter is waardevol indien regelversie controle uitkomst is gevuurd.
                """, "ERROR (line 13): vorm zonder norm: het IR draagt 'regelversie X is gevuurd' niet: de vorm"
                + " heeft geen norm (RUNTIME-SEMANTICS geeft hem geen betekenis); van de regelstatuscontroles draagt"
                + " het IR alleen 'regelversie X is inconsistent' — 'regelversie controle uitkomst is gevuurd'");
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
