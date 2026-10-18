package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate G4 — NEGATIVE HTTP fixtures. A file-driven {@link TestFactory}: every JSON fixture under
 * {@code src/test/resources/negatief/} is one negative case posted against ONE shared {@link ServiceHttp} over
 * the full TOKA {@link DecisionCore}. Each fixture declares its {@code path}, its request {@code body} (a JSON
 * object, OR a raw string for the malformed-JSON / empty-body cases), the {@code expectedStatus} it must get
 * (a 4xx), and a {@code expectedFieldContains} substring the response body must NAME (in a {@code veld}, a
 * {@code fout}/message, or a schending's {@code veld}/{@code fout}).
 *
 * <p><b>None of these reach fire.</b> A mapping/adapter rejection (unknown attribute/kenmerk/rol/objecttype,
 * wrong datatype, wrong eenheid-vorm, ill-typed/unknown parameter, missing required invoer instance, malformed
 * or empty body, unknown path) is raised STRICTLY BEFORE {@code Executor.fire} — the request never opens a
 * KieSession, no rule ever fires. The 4xx status is therefore ITSELF the no-fire witness: there is no partial
 * result, no session, and no leaked state. This is the same fail-fast contract the core enforces at every
 * boundary — collect the violations, name the offending field, and refuse before any evaluation.
 *
 * <p>The floor is asserted: at least 8 fixtures are found (the card's mandatory list); floors may only grow.
 */
class NegativeFixturesTest {

    private static ServiceHttp service;
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void startService() {
        DecisionCore core = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        try {
            service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void stopService() {
        if (service != null) service.stop(0);
        service = null;
    }

    @TestFactory
    Stream<DynamicTest> negative_fixtures() throws IOException {
        List<Path> files = fixtureFiles();
        assertTrue(files.size() >= 8,
                "G4 verlangt minstens 8 negatieve fixtures; gevonden: " + files.size() + " in " + files);

        List<DynamicTest> tests = new ArrayList<>();
        for (Path fixture : files) {
            JsonNode f = ServiceHttp.MAPPER.readTree(Files.readString(fixture));
            String name = f.path("naam").asText(fixture.getFileName().toString());
            tests.add(DynamicTest.dynamicTest(fixture.getFileName() + " — " + name, () -> test(f)));
        }
        return tests.stream();
    }

    /** POST the fixture body and assert the status + that the response NAMES the expected veld/pad substring. */
    private void test(JsonNode fixture) throws Exception {
        String path = fixture.path("pad").asText();
        int expectedStatus = fixture.path("verwachtStatus").asInt();
        String expectedFieldContains = fixture.path("verwachtVeldBevat").asText();

        // body: a raw string (malformed / empty case) is sent verbatim; a JSON object is serialised exactly.
        JsonNode bodyNode = fixture.get("body");
        String body = bodyNode != null && bodyNode.isTextual()
                ? bodyNode.asText()
                : ServiceHttp.MAPPER.writeValueAsString(bodyNode);

        HttpResponse<String> resp = post(path, body);
        assertEquals(expectedStatus, resp.statusCode(),
                () -> "verwachtte " + expectedStatus + " voor pad /" + path + ", kreeg " + resp.statusCode()
                        + ": " + resp.body());

        // the response NAMES the offending veld/pad: search the top-level veld/fout AND every schending's
        // veld/fout for the expected substring.
        JsonNode answer = ServiceHttp.MAPPER.readTree(resp.body());
        assertTrue(names(answer, expectedFieldContains),
                () -> "de " + expectedStatus + "-body hoort '" + expectedFieldContains + "' te noemen: " + resp.body());

        // 4xx is itself the no-fire witness: a rejected request never produces an instanties/inconsistenties
        // result body (those only appear on a 200 AnswerBuilder response). Assert the negative body carries a
        // 'fout' and NO result payload — nothing fired.
        assertTrue(answer.has("fout"), () -> "een 4xx-body hoort een 'fout' te dragen: " + resp.body());
        assertTrue(!answer.has("instanties") && !answer.has("inconsistenties"),
                () -> "een geweigerd verzoek mag geen fire-resultaat teruggeven: " + resp.body());
    }

    /** Whether {@code answer} names {@code needle} anywhere a diagnostic surfaces it: the top-level
     *  {@code veld} or {@code fout}, or any schending's {@code veld} or {@code fout}. */
    private static boolean names(JsonNode answer, String needle) {
        if (contains(answer.path("veld"), needle)) return true;
        if (contains(answer.path("fout"), needle)) return true;
        JsonNode violations = answer.path("schendingen");
        if (violations.isArray())
            for (JsonNode s : violations)
                if (contains(s.path("veld"), needle) || contains(s.path("fout"), needle)) return true;
        return false;
    }

    private static boolean contains(JsonNode n, String needle) {
        return n != null && n.isTextual() && n.asText().contains(needle);
    }

    // ---- fixture discovery ----

    private static List<Path> fixtureFiles() throws IOException {
        Path dir = fixtureDir();
        List<Path> sink = new ArrayList<>();
        try (Stream<Path> st = Files.list(dir)) {
            st.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(sink::add);
        }
        return sink;
    }

    /** The negatief fixture directory, resolved from the test-classpath resource root (works from module or
     *  repo cwd, and from the built target/test-classes). */
    private static Path fixtureDir() {
        try {
            var url = NegativeFixturesTest.class.getClassLoader().getResource("negatief");
            if (url != null) return Paths.get(url.toURI());
        } catch (Exception ignore) { /* fall back to the source tree */ }
        Path src = Paths.get("src/test/resources/negative");
        if (Files.isDirectory(src)) return src;
        return Paths.get("service/src/test/resources/negative");
    }

    // ---- HTTP ----

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + "/" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
