package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The env-gated wiring of the dev playground over a real {@link ServiceHttp}. With the workbench flag ON,
 * {@code GET /playground} serves the HTML page and {@code POST /playground/analyse} returns the analyzer tree; with
 * the flag OFF (the production default) both paths 404 through the catch-all — proving the surface is truly
 * dev-only. The bind flag is forced via the {@link ServiceHttp#bindWith} test seam, so no process env is touched.
 */
class PlaygroundHttpTest {

    private ServiceHttp service;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void stop() {
        if (service != null) service.stop(0);
        service = null;
    }

    /** A trivial one-entrypoint kern; the playground routes are orthogonal to it, it only exists to bind a server. */
    private static DecisionCore core() {
        String source = """
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (geheel getal);
            Regel heffing
            geldig altijd
            De heffing van een Vlucht moet berekend worden als 10.
            """;
        String descriptor = """
            { "service": {"name":"playground-wiring","version":"1"},
              "entrypoints": [ { "name":"heffing","path":"heffing",
                "scope": {"ruleNames": ["heffing"]},
                "input": ["Vlucht"], "output": ["Vlucht.heffing"] } ],
              "parameters": {} }""";
        Map<String, String> b = new LinkedHashMap<>();
        b.put("m.rgs", source);
        return DecisionCore.assemble(descriptor, b, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private void serveer(boolean playground) throws IOException {
        service = ServiceHttp.bindWith(core(), core()::process, "127.0.0.1", 0, playground).start();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + path))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void analyse_route_gives_the_analyzer_tree() throws Exception {
        serveer(true);
        HttpResponse<String> resp = post("/playground/analyse",
                "Objecttype de Persoon (mv: Personen)\n    de leeftijd Numeriek (geheel getal);\n");
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                resp.headers().map().toString());
        JsonNode a = ServiceHttp.MAPPER.readTree(resp.body());
        assertTrue(a.has("parse") && a.has("resolver"), resp.body());
        assertTrue(a.get("parse").get("ok").asBoolean(), resp.body());
    }

    @Test
    void page_route_serves_html() throws Exception {
        serveer(true);
        HttpResponse<String> resp = get("/playground");
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/html"),
                resp.headers().map().toString());
        assertTrue(resp.body().contains("RegelSpraak-playground"), "de pagina hoort de titel te dragen");
        assertTrue(resp.body().contains("/playground/analyse"), "de pagina hoort de analyse-endpoint te callen");
    }

    @Test
    void routes_are_404_when_playground_off() throws Exception {
        serveer(false);
        // both dev paths fall through to the catch-all 404 when the workbench is off (the production default).
        assertEquals(404, get("/playground").statusCode());
        assertEquals(404, post("/playground/analyse", "Objecttype de Persoon (mv: Personen)").statusCode());
    }
}
