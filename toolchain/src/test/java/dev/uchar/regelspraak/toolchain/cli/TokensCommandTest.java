package dev.uchar.regelspraak.toolchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code bin/regelspraak tokens <out.json> <source.rgs...>}: the semantic tokens of uchar's frontend
 * ({@code Resolver.tokens}; uchar {@code frontends/regelspraak/grammar/DIVERGENCES.md}, "Semantic tokens") written
 * as JSON, as {@code ir} writes the IR. The test runs the script itself, so what it checks is the command a
 * consumer calls: the arguments, the exit code, the file names as given and the form of the document. The script
 * runs on {@code REGELSPRAAK_JAR}, here a jar whose manifest carries this module's test class path, so it builds
 * nothing.
 */
class TokensCommandTest {

    private static final Path SCRIPT = Path.of("..", "bin", "regelspraak").toAbsolutePath().normalize();

    /** The declarations: the parameter on line 1, the attribute {@code lengte} on line 4, column 7. */
    private static final String BOATS = """
            Parameter de lengtegrens : Numeriek (getal met 2 decimalen)

            Objecttype de Boot (mv: Boten)
                de lengte Numeriek (getal met 2 decimalen);
                het havengeld Numeriek (getal met 2 decimalen);
                is groot kenmerk (bijvoeglijk);
            """;

    /** The rules, in a second file: {@code lengte} and {@code lengtegrens} are declared in the first one, and
     *  {@code liggeldtarief} on line 8 is declared nowhere. */
    private static final String RULES = """
            Regel grote boot
                geldig altijd
                    Een Boot is groot
                    indien zijn lengte groter is dan de lengtegrens.

            Regel havengeld van een boot
                geldig altijd
                    Het havengeld van een Boot moet berekend worden als zijn lengte maal het liggeldtarief.
            """;

    @TempDir Path dir;

    private record Run(int exit, String stdout, String stderr) {}

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

    private void write(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    /** The first span of {@code file} in the document whose text is {@code text}. */
    private static JsonNode span(JsonNode document, String file, String text) {
        for (JsonNode f : document.get("files"))
            if (f.get("file").asText().equals(file))
                for (JsonNode s : f.get("spans"))
                    if (s.get("text").asText().equals(text)) return s;
        return fail("geen span '" + text + "' in " + file + ": " + document);
    }

    /** The span of {@code file} that starts at {@code line} and {@code column}. */
    private static JsonNode spanAt(JsonNode document, String file, int line, int column) {
        for (JsonNode f : document.get("files"))
            if (f.get("file").asText().equals(file))
                for (JsonNode s : f.get("spans"))
                    if (s.get("line").asInt() == line && s.get("column").asInt() == column) return s;
        return fail("geen span op " + file + ":" + line + ":" + column);
    }

    @Test
    void tokens_of_two_files_link_a_reference_across_the_file_boundary_and_mark_an_undeclared_name()
            throws Exception {
        write("boten.rgs", BOATS);
        write("regels/havengeld.rgs", RULES);
        Run r = run("tokens", "tokens.json", "boten.rgs", "regels/havengeld.rgs");
        assertEquals(0, r.exit(), () -> "stdout: " + r.stdout() + "\nstderr: " + r.stderr());
        JsonNode document = new ObjectMapper().readTree(dir.resolve("tokens.json").toFile());

        // one entry per file, in the order given, named as given
        List<String> files = new ArrayList<>();
        document.get("files").forEach(f -> files.add(f.get("file").asText()));
        assertEquals(List.of("boten.rgs", "regels/havengeld.rgs"), files);

        // the declaration in the first file, the reference in the second, linked over the boundary
        JsonNode declaration = spanAt(document, "boten.rgs", 4, 7);
        assertEquals("lengte", declaration.get("text").asText());
        assertEquals("attribute", declaration.get("kind").asText());
        assertTrue(declaration.path("declaration").asBoolean(), declaration::toString);
        JsonNode reference = span(document, "regels/havengeld.rgs", "lengte");
        assertEquals("attribute", reference.get("kind").asText());
        assertEquals(4, reference.get("line").asInt());
        JsonNode at = reference.get("declaredAt");
        assertNotNull(at, reference::toString);
        assertEquals("boten.rgs", at.get("file").asText());
        assertEquals(4, at.get("line").asInt());
        assertEquals(7, at.get("column").asInt());

        JsonNode parameter = span(document, "regels/havengeld.rgs", "lengtegrens");
        assertEquals("parameter", parameter.get("kind").asText());
        assertEquals("boten.rgs", parameter.get("declaredAt").get("file").asText());
        assertEquals(1, parameter.get("declaredAt").get("line").asInt());

        // a name that names nothing: an unresolved span without a link, and a diagnostic on its line in its file
        JsonNode undeclared = span(document, "regels/havengeld.rgs", "liggeldtarief");
        assertEquals("unresolved", undeclared.get("kind").asText());
        assertEquals(8, undeclared.get("line").asInt());
        assertFalse(undeclared.has("declaredAt"), undeclared::toString);
        boolean diagnosed = false;
        for (JsonNode d : document.get("diagnostics"))
            diagnosed |= d.path("file").asText().equals("regels/havengeld.rgs") && d.path("line").asInt() == 8
                    && d.path("code").asText().startsWith("RGS-");
        assertTrue(diagnosed, () -> "geen diagnose met code op regels/havengeld.rgs:8: " + document.get("diagnostics"));
    }

    @Test
    void tokens_writes_the_document_also_after_a_syntax_error() throws Exception {
        write("boten.rgs", BOATS);
        write("kapot.rgs", "Regel kapot\n    geldig altijd\n        Het havengeld van een Boot moet berekend worden als\n");
        Run r = run("tokens", "tokens.json", "boten.rgs", "kapot.rgs");
        assertEquals(0, r.exit(), () -> "stdout: " + r.stdout() + "\nstderr: " + r.stderr());
        JsonNode document = new ObjectMapper().readTree(dir.resolve("tokens.json").toFile());
        assertEquals(2, document.get("files").size());
        boolean error = false;
        for (JsonNode d : document.get("diagnostics")) error |= d.get("severity").asText().equals("ERROR");
        assertTrue(error, () -> "geen ERROR in " + document.get("diagnostics"));
        assertTrue(r.stderr().contains("ERROR"), r.stderr());
    }

    @Test
    void tokens_exits_2_on_wrong_usage_and_writes_nothing() throws Exception {
        write("boten.rgs", BOATS);
        assertEquals(2, run("tokens").exit(), "geen uitvoerbestand");
        assertEquals(2, run("tokens", "tokens.json").exit(), "geen bron");
        assertEquals(2, run("tokens", "tokens.json", "ontbreekt.rgs").exit(), "een bron die niet bestaat");
        assertEquals(2, run("tokens", "--ir", "boten.rgs").exit(), "een optie als uitvoerbestand");
        assertEquals(2, run("tokens", "tokens.json", "--ir", "boten.rgs").exit(), "een optie tussen de bronnen");
        Run missingDir = run("tokens", "geen-map/tokens.json", "boten.rgs");
        assertEquals(2, missingDir.exit(), "een uitvoerbestand in een map die niet bestaat");
        assertTrue(missingDir.stderr().contains("de map bestaat niet: geen-map"), missingDir.stderr());
        assertFalse(Files.exists(dir.resolve("tokens.json")), "verkeerd gebruik schrijft niets");
    }

    @Test
    void the_usage_names_the_command() throws Exception {
        Run r = run("help");
        assertEquals(0, r.exit());
        assertTrue(r.stdout().contains("tokens <out.json> <source.rgs...>"), r.stdout());
    }
}
