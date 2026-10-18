package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.NoFixpoint;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.OpenApiEmitter;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run without a fixpoint, on uchar's red golden {@code jutterswet-budgetgrens-over}: from a Wachter of rang 1 its
 * recursive creation would make a Wachter at creation depth 101, one past uchar's creation budget, and uchar's
 * {@code DroolsBackend.fire} ends the run there with its sentence ({@code Firing.noFixpoint}). From rang 50 the chain
 * ends at depth 52 and the run reaches its fixpoint. The core throws {@link NoFixpoint} with uchar's sentence; the
 * decision route answers it with a 422 {@code GEEN_FIXPUNT}; {@code ?via=dienst} makes that scenario a FAIL in the shape
 * of uchar's report and computes the other scenario of the document.
 */
class NoFixpointTest {

    private static final Path RED = TokaSource.REPO.resolve("uchar/corpus/rood/jutterswet-budgetgrens-over");
    private static final String DESCRIPTOR = """
            { "dienst": {"naam":"estafette","versie":"1"},
              "entrypoints": [ { "naam":"estafette","pad":"estafette",
                "scope": {"regelnamen": ["opvolgerrang van een wachter", "rangplafond", "eerste keten aan het plafond",
                                         "tweede keten aan het plafond", "werf de lange keten"]},
                "invoer": ["Wachter"], "uitvoer": ["Wachter.opvolgerrang"] } ],
              "parameters": {} }""";
    private static final String SCENARIO = """
            Scenario keten vanaf rang %1$s
            Gegeven is de volgende situatie:
            een Wachter W1
            - de rang is %1$s
            - het ketennummer is 1
            Met als verwacht resultaat:
            - de opvolgerrang van W1 is %2$s
            """;
    private static final String REQUEST =
            "{\"instanties\":[{\"type\":\"Wachter\",\"id\":\"W1\",\"attributen\":{\"rang\":%s,\"ketennummer\":1}}]}";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static String source;
    /** The sentence uchar pins for the Drools branch on this chain, in its own red case. */
    private static String fragment;
    private static DecisionCore core;
    private static ServiceHttp service;

    @BeforeAll
    static void start() throws IOException {
        source = Files.readString(RED.resolve("bron.rgs"));
        fragment = ServiceHttp.MAPPER.readTree(Files.readString(RED.resolve("case1.json")))
                .at("/expectedFault/drools").asText();
        assertTrue(fragment.startsWith("creation budget exhausted: "), fragment);
        core = DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", source), CoreConfig.strict().withDetail(),
                Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bindWith(core, core::process, "127.0.0.1", 0, true).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
    }

    // ---------------- the core ----------------

    /** The core throws uchar's sentence as its own type, not as an internal fault; from rang 50 it answers. */
    @Test
    void the_core_throws_NoFixpoint_with_the_uchar_sentence() throws IOException {
        NoFixpoint error = assertThrows(NoFixpoint.class,
                () -> core.process("estafette", ServiceHttp.MAPPER.readTree(REQUEST.formatted(1))));
        assertTrue(error.getMessage().startsWith(fragment), error.getMessage());
        JsonNode answer = core.process("estafette", ServiceHttp.MAPPER.readTree(REQUEST.formatted(50)));
        assertEquals(51, answer.at("/instanties/0/attributen/opvolgerrang").asInt(), answer.toString());
    }

    // ---------------- the decision route ----------------

    /** A 422 with status {@code GEEN_FIXPUNT} and uchar's sentence, and nothing else without a flag. */
    @Test
    void the_decide_route_gives_a_422_with_the_uchar_sentence() throws Exception {
        HttpResponse<String> r = post("/estafette", REQUEST.formatted(1));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = ServiceHttp.MAPPER.readTree(r.body());
        assertEquals(List.of("status", "fout"), keys(body));
        assertEquals("GEEN_FIXPUNT", body.path("status").asText());
        assertTrue(body.path("fout").asText().startsWith(fragment), body.toString());
    }

    /** With the detail flags the 422 carries the trace and the coverage of what fired, and for the fixpoint the reason
     *  there is none, uchar's sentence. */
    @Test
    void with_the_detail_flags_trace_coverage_and_fixpoint_fault() throws Exception {
        HttpResponse<String> r = post("/estafette?trace=true&dekking=true&fixpunt=true", REQUEST.formatted(1));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = ServiceHttp.MAPPER.readTree(r.body());
        assertEquals(List.of("status", "fout", "trace", "dekking", "fixpuntFout"), keys(body));
        assertEquals(body.path("fout"), body.path("fixpuntFout"));
        boolean werving = false;
        for (JsonNode v : body.path("trace"))
            werving |= v.path("regel").asText().equals("werf de lange keten") && v.path("aantal").asInt() == 100;
        assertTrue(werving, body.path("trace").toString());
        boolean fired = false;
        for (JsonNode d : body.path("dekking"))
            fired |= d.path("regel").asText().equals("werf de lange keten") && d.path("soort").asText().equals("GEVUURD");
        assertTrue(fired, body.path("dekking").toString());
    }

    /** The contract names the body: the 422 is one of {@code EvaluationFault} and {@code NoFixpoint}. */
    @Test
    void the_openapi_422_names_the_body_of_NoFixpoint() {
        JsonNode doc = OpenApiEmitter.emit(core.descriptor(), core.model());
        JsonNode schema = doc.at("/paths/~1estafette/post/responses/422/content/application~1json/schema");
        assertEquals("[{\"$ref\":\"#/components/schemas/Evaluatiefout\"},{\"$ref\":\"#/components/schemas/GeenFixpunt\"}]",
                schema.path("oneOf").toString(), schema::toString);
        JsonNode s = doc.at("/components/schemas/GeenFixpunt");
        assertEquals("[\"status\",\"fout\"]", s.path("required").toString(), s::toString);
        assertEquals(List.of("status", "fout", "trace", "dekking", "fixpuntFout"), keys(s.path("properties")));
        assertEquals("[\"GEEN_FIXPUNT\"]", s.at("/properties/status/enum").toString(), s::toString);
    }

    // ---------------- via=dienst ----------------

    /** The scenario without a fixpoint is a FAIL as uchar's report gives it, the same as the ordinary route; the other
     *  scenario of the document is computed and passes. */
    @Test
    void via_service_the_scenario_without_fixpoint_is_a_FAIL_and_the_other_is_a_PASS() {
        String document = source + "\n" + SCENARIO.formatted(1, 2) + "\n" + SCENARIO.formatted(50, 51);
        JsonNode g = Playground.analyseer(document, null).path("scenario").path("resultaten");
        JsonNode d = Playground.analyseer(document, null, core).path("scenario").path("resultaten");
        assertEquals(2, d.size(), d.toString());
        assertEquals("FAIL", g.get(0).path("status").asText(), g.toString());
        assertEquals("FAIL", d.get(0).path("status").asText(), d.toString());
        assertEquals("PASS", g.get(1).path("status").asText(), g.toString());
        assertEquals("PASS", d.get(1).path("status").asText(), d.toString());

        JsonNode error = d.get(0);
        assertEquals("estafette", error.path("pad").asText());
        assertEquals(g.get(0).path("diagnose"), error.path("diagnose"));
        assertTrue(error.path("diagnose").asText().startsWith("case1-keten-vanaf-rang-1.json: " + fragment),
                error.path("diagnose").asText());
        for (String field : List.of("verwachtingen", "trace", "dekking"))
            assertEquals(g.get(0).path(field), error.path(field), field);
        assertEquals(0, error.path("verwachtingen").size());
        assertTrue(error.path("trace").size() > 0, error.toString());
        assertTrue(error.has("uitlegFout"), error.toString());
        assertTrue(error.path("fixpuntFout").asText().startsWith(fragment), error.toString());

        JsonNode pass = d.get(1);
        assertEquals(g.get(1).path("verwachtingen"), pass.path("verwachtingen"));
        assertEquals("51", pass.at("/verwachtingen/0/werkelijk").asText(), pass.toString());
    }

    /** Over HTTP the analysis as a whole is a 200. */
    @Test
    void via_service_over_http_a_200() throws Exception {
        String document = source + "\n" + SCENARIO.formatted(1, 2) + "\n" + SCENARIO.formatted(50, 51);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + "/playground/analyse?via=dienst"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(document, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode results = ServiceHttp.MAPPER.readTree(r.body()).path("scenario").path("resultaten");
        List<String> statuses = new ArrayList<>();
        for (JsonNode n : results) statuses.add(n.path("status").asText());
        assertEquals(List.of("FAIL", "PASS"), statuses);
    }

    private static List<String> keys(JsonNode n) {
        List<String> sink = new ArrayList<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) sink.add(it.next());
        return sink;
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
