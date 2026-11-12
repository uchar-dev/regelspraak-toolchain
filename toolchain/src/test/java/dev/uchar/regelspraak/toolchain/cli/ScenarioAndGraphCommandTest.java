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
 * The exit codes of {@code bin/regelspraak scenario} where there is nothing to run: a source without a
 * Scenario block tests nothing and is an error (exit 1), as {@code cases} refuses it, also on a manifest line; a source
 * that cannot be read is a wrong invocation (exit 2), as for the other commands. The test runs the
 * script on {@code REGELSPRAAK_JAR}, a jar whose manifest carries this module's test class path, as
 * {@link TokensCommandTest} does.
 */
class ScenarioAndGraphCommandTest {

    private static final Path SCRIPT = Path.of("..", "bin", "regelspraak").toAbsolutePath().normalize();

    private static final String MODEL = """
            Objecttype de Vondst (mv: Vondsten)
                de waarde Numeriek (geheel getal);
                is kostbaar kenmerk (bijvoeglijk);

            Regel kostbaar
                geldig altijd
                    Een Vondst is kostbaar indien zijn waarde groter is dan 100.
            """;

    @TempDir Path dir;

    private record Run(int exit, String stdout, String stderr) {}

    @Test
    void scenario_on_a_source_without_a_scenario_is_exit_1() throws Exception {
        Files.writeString(dir.resolve("model.rgs"), MODEL);
        Run r = run("scenario", "model.rgs");
        assertEquals(1, r.exit(), r::toString);
        assertTrue(r.stderr().contains("geen Scenario"), r::toString);
    }

    @Test
    void a_manifest_line_without_a_scenario_is_exit_1() throws Exception {
        Files.writeString(dir.resolve("model.rgs"), MODEL);
        Files.writeString(dir.resolve("manifest.txt"), "1 model.rgs\n");
        Run r = run("scenario", "--manifest", "manifest.txt");
        assertEquals(0, r.exit(), r::toString);
        assertTrue(r.stdout().contains("OK (exit 1)  model.rgs"), r::toString);
    }

    @Test
    void scenario_on_a_source_it_cannot_read_is_exit_2() throws Exception {
        Run r = run("scenario", "ontbreekt.rgs");
        assertEquals(2, r.exit(), r::toString);
        assertTrue(r.stderr().contains("ontbreekt.rgs"), r::toString);
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
