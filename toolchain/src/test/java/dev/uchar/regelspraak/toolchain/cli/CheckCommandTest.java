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
 * error: exit 1, with the name, the rule and the line. The test runs the script on {@code REGELSPRAAK_JAR}, a jar
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
