package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bin/regelspraak playground} ({@code ServiceMain --playground}): the playground without a descriptor and a
 * source. It analyses the source a request posts as the playground of {@code serve} does; it has no served model, so
 * {@code ?via=service} is a 400 that says why, the page says the same, and the routes of a service are not there.
 */
class PlaygroundCommandTest {

    private static final String SOURCE = """
            Objecttype de Persoon (mv: Personen)
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);

            Regel bepaal minderjarigheid
            geldig altijd
            Een Persoon is minderjarig indien de leeftijd van de Persoon kleiner is dan 18.

            Scenario een kind
            Gegeven is de volgende situatie:
            een Persoon P
            - de leeftijd is 12
            Met als verwacht resultaat:
            - P is minderjarig
            """;

    private static ServiceHttp playground;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws IOException {
        ServiceMain.PlaygroundArgs args = ServiceMain.parsePlaygroundArgs(new String[]{});
        assertEquals(new ServiceMain.PlaygroundArgs("0.0.0.0", 8080), args);
        playground = ServiceHttp.playground("127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (playground != null) playground.stop(0);
    }

    @Test
    void it_takes_no_descriptor_and_no_source() {
        assertEquals(new ServiceMain.PlaygroundArgs("127.0.0.1", 0),
                ServiceMain.parsePlaygroundArgs(new String[]{"--host", "127.0.0.1", "--port", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceMain.parsePlaygroundArgs(new String[]{"d.service.json", "x.rgs"}));
        assertThrows(IllegalArgumentException.class, () -> ServiceMain.parsePlaygroundArgs(new String[]{"--detail"}));
    }

    @Test
    void it_analyses_a_source() throws Exception {
        HttpResponse<String> r = post("/playground/analyse", SOURCE);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode answer = ServiceHttp.MAPPER.readTree(r.body());
        assertTrue(answer.get("executable").asBoolean(), r.body());
        assertEquals("PASS", answer.at("/scenario/results/0/status").asText(), r.body());
    }

    @Test
    void it_offers_no_comparison_with_a_service_and_says_why() throws Exception {
        HttpResponse<String> r = post("/playground/analyse?via=service", SOURCE);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode violation = ServiceHttp.MAPPER.readTree(r.body()).at("/violations/0");
        assertEquals("via", violation.get("field").asText(), r.body());
        assertEquals(ServiceHttp.NO_SERVICE, violation.get("error").asText(), r.body());
        HttpResponse<String> page = get("/playground");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<span class=\"hint\" id=\"via\">" + ServiceHttp.NO_SERVICE + "</span>"),
                page.body());
    }

    @Test
    void it_has_no_routes_of_a_service() throws Exception {
        assertEquals(404, get("/info").statusCode());
        assertEquals(404, get("/openapi").statusCode());
    }

    /** The page the playground of {@code serve} sends as it is keeps the place empty: it has a service. */
    @Test
    void the_page_of_serve_keeps_the_place_empty() throws Exception {
        try (var in = ServiceHttp.class.getResourceAsStream("playground.html")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains(ServiceHttp.VIA_PLACE));
        }
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + playground.port() + path))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + playground.port() + path)).GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
