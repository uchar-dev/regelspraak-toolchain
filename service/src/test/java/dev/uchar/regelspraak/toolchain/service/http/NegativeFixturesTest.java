package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.TokaBron;

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
 * {@code src/test/resources/negatief/} is one negative case posted against ONE shared {@link DienstHttp} over
 * the full TOKA {@link BeslisserKern}. Each fixture declares its {@code pad}, its request {@code body} (a JSON
 * object, OR a raw string for the malformed-JSON / empty-body cases), the {@code verwachtStatus} it must get
 * (a 4xx), and a {@code verwachtVeldBevat} substring the response body must NAME (in a {@code veld}, a
 * {@code fout}/message, or a schending's {@code veld}/{@code fout}).
 *
 * <p><b>None of these reach fire.</b> A mapping/adapter rejection (unknown attribute/kenmerk/rol/objecttype,
 * wrong datatype, wrong eenheid-vorm, ill-typed/unknown parameter, missing required invoer instance, malformed
 * or empty body, unknown path) is raised STRICTLY BEFORE {@code Uitvoerder.vuur} — the request never opens a
 * KieSession, no rule ever fires. The 4xx status is therefore ITSELF the no-fire witness: there is no partial
 * result, no session, and no leaked state. This is the same fail-fast contract the core enforces at every
 * boundary — collect the violations, name the offending field, and refuse before any evaluation.
 *
 * <p>The floor is asserted: at least 8 fixtures are found (the card's mandatory list); floors may only grow.
 */
class NegatieveFixturesTest {

    private static DienstHttp dienst;
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void startDienst() {
        BeslisserKern kern = BeslisserKern.bouw(TokaBron.descriptorJson(), TokaBron.bronPerBestand(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
        try {
            dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void stopDienst() {
        if (dienst != null) dienst.stop(0);
        dienst = null;
    }

    @TestFactory
    Stream<DynamicTest> negatieve_fixtures() throws IOException {
        List<Path> bestanden = fixtureBestanden();
        assertTrue(bestanden.size() >= 8,
                "G4 verlangt minstens 8 negatieve fixtures; gevonden: " + bestanden.size() + " in " + bestanden);

        List<DynamicTest> tests = new ArrayList<>();
        for (Path fixture : bestanden) {
            JsonNode f = DienstHttp.MAPPER.readTree(Files.readString(fixture));
            String naam = f.path("naam").asText(fixture.getFileName().toString());
            tests.add(DynamicTest.dynamicTest(fixture.getFileName() + " — " + naam, () -> toets(f)));
        }
        return tests.stream();
    }

    /** POST the fixture body and assert the status + that the response NAMES the expected veld/pad substring. */
    private void toets(JsonNode fixture) throws Exception {
        String pad = fixture.path("pad").asText();
        int verwachtStatus = fixture.path("verwachtStatus").asInt();
        String verwachtVeldBevat = fixture.path("verwachtVeldBevat").asText();

        // body: a raw string (malformed / empty case) is sent verbatim; a JSON object is serialised exactly.
        JsonNode bodyNode = fixture.get("body");
        String body = bodyNode != null && bodyNode.isTextual()
                ? bodyNode.asText()
                : DienstHttp.MAPPER.writeValueAsString(bodyNode);

        HttpResponse<String> resp = post(pad, body);
        assertEquals(verwachtStatus, resp.statusCode(),
                () -> "verwachtte " + verwachtStatus + " voor pad /" + pad + ", kreeg " + resp.statusCode()
                        + ": " + resp.body());

        // the response NAMES the offending veld/pad: search the top-level veld/fout AND every schending's
        // veld/fout for the expected substring.
        JsonNode antwoord = DienstHttp.MAPPER.readTree(resp.body());
        assertTrue(noemt(antwoord, verwachtVeldBevat),
                () -> "de " + verwachtStatus + "-body hoort '" + verwachtVeldBevat + "' te noemen: " + resp.body());

        // 4xx is itself the no-fire witness: a rejected request never produces an instanties/inconsistenties
        // result body (those only appear on a 200 AntwoordBouwer response). Assert the negative body carries a
        // 'fout' and NO result payload — nothing fired.
        assertTrue(antwoord.has("fout"), () -> "een 4xx-body hoort een 'fout' te dragen: " + resp.body());
        assertTrue(!antwoord.has("instanties") && !antwoord.has("inconsistenties"),
                () -> "een geweigerd verzoek mag geen fire-resultaat teruggeven: " + resp.body());
    }

    /** Whether {@code antwoord} names {@code needle} anywhere a diagnostic surfaces it: the top-level
     *  {@code veld} or {@code fout}, or any schending's {@code veld} or {@code fout}. */
    private static boolean noemt(JsonNode antwoord, String needle) {
        if (bevat(antwoord.path("veld"), needle)) return true;
        if (bevat(antwoord.path("fout"), needle)) return true;
        JsonNode schendingen = antwoord.path("schendingen");
        if (schendingen.isArray())
            for (JsonNode s : schendingen)
                if (bevat(s.path("veld"), needle) || bevat(s.path("fout"), needle)) return true;
        return false;
    }

    private static boolean bevat(JsonNode n, String needle) {
        return n != null && n.isTextual() && n.asText().contains(needle);
    }

    // ---- fixture discovery ----

    private static List<Path> fixtureBestanden() throws IOException {
        Path dir = fixtureDir();
        List<Path> uit = new ArrayList<>();
        try (Stream<Path> st = Files.list(dir)) {
            st.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(uit::add);
        }
        return uit;
    }

    /** The negatief fixture directory, resolved from the test-classpath resource root (works from module or
     *  repo cwd, and from the built target/test-classes). */
    private static Path fixtureDir() {
        try {
            var url = NegatieveFixturesTest.class.getClassLoader().getResource("negatief");
            if (url != null) return Paths.get(url.toURI());
        } catch (Exception ignore) { /* fall back to the source tree */ }
        Path src = Paths.get("src/test/resources/negatief");
        if (Files.isDirectory(src)) return src;
        return Paths.get("service/src/test/resources/negatief");
    }

    // ---- HTTP ----

    private HttpResponse<String> post(String pad, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + dienst.poort() + "/" + pad))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
