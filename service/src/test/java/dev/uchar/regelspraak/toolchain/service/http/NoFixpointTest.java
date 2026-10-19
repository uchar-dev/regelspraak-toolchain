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
 * decision route answers it with a 422 {@code NO_FIXPOINT}; {@code ?via=service} makes that scenario a FAIL in the shape
 * of uchar's report and computes the other scenario of the document.
 */
class NoFixpointTest {

    private static final Path RED = TokaSource.REPO.resolve("uchar/corpus/red/jutterswet-budgetgrens-over");
    private static final String DESCRIPTOR = """
            { "service": {"name":"estafette","version":"1"},
              "entrypoints": [ { "name":"estafette","path":"estafette",
                "scope": {"ruleNames": ["opvolgerrang van een wachter", "rangplafond", "eerste keten aan het plafond",
                                         "tweede keten aan het plafond", "werf de lange keten"]},
                "input": ["Wachter"], "output": ["Wachter.opvolgerrang"] } ],
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
            "{\"instances\":[{\"type\":\"Wachter\",\"id\":\"W1\",\"attributes\":{\"rang\":%s,\"ketennummer\":1}}]}";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static String source;
    /** The sentence uchar pins for the Drools branch on this chain, in its own red case. */
    private static String fragment;
    private static DecisionCore core;
    private static ServiceHttp service;

    @BeforeAll
    static void start() throws IOException {
        source = Files.readString(RED.resolve("source.rgs"));
        // uchar pins the code of the fault, no longer its sentence; the sentence leads with the same words in both languages
        assertEquals("EF-015", ServiceHttp.MAPPER.readTree(Files.readString(RED.resolve("case1.json")))
                .at("/expectedFault/code").asText());
        fragment = "creation budget exhausted: ";
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
        assertEquals(51, answer.at("/instances/0/attributes/opvolgerrang").asInt(), answer.toString());
    }

    // ---------------- the decision route ----------------

    /** A 422 with status {@code NO_FIXPOINT} and uchar's sentence, and nothing else without a flag. */
    @Test
    void the_decide_route_gives_a_422_with_the_uchar_sentence() throws Exception {
        HttpResponse<String> r = post("/estafette", REQUEST.formatted(1));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = ServiceHttp.MAPPER.readTree(r.body());
        assertEquals(List.of("status", "error"), keys(body));
        assertEquals("NO_FIXPOINT", body.path("status").asText());
        assertTrue(body.path("error").asText().startsWith(fragment), body.toString());
    }

    /** With the detail flags the 422 carries the trace and the coverage of what fired, and for the fixpoint the reason
     *  there is none, uchar's sentence. */
    @Test
    void with_the_detail_flags_trace_coverage_and_fixpoint_fault() throws Exception {
        HttpResponse<String> r = post("/estafette?trace=true&coverage=true&fixpoint=true", REQUEST.formatted(1));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = ServiceHttp.MAPPER.readTree(r.body());
        assertEquals(List.of("status", "error", "trace", "coverage", "fixpointError"), keys(body));
        assertEquals(body.path("error"), body.path("fixpointError"));
        boolean werving = false;
        for (JsonNode v : body.path("trace"))
            werving |= v.path("rule").asText().equals("werf de lange keten") && v.path("count").asInt() == 100;
        assertTrue(werving, body.path("trace").toString());
        boolean fired = false;
        for (JsonNode d : body.path("coverage"))
            fired |= d.path("rule").asText().equals("werf de lange keten") && d.path("kind").asText().equals("FIRED");
        assertTrue(fired, body.path("coverage").toString());
    }

    /** The contract names the body: the 422 is one of {@code EvaluationFault} and {@code NoFixpoint}. */
    @Test
    void the_openapi_422_names_the_body_of_NoFixpoint() {
        JsonNode doc = OpenApiEmitter.emit(core.descriptor(), core.model());
        JsonNode schema = doc.at("/paths/~1estafette/post/responses/422/content/application~1json/schema");
        assertEquals("[{\"$ref\":\"#/components/schemas/EvaluationFault\"},{\"$ref\":\"#/components/schemas/NoFixpoint\"}]",
                schema.path("oneOf").toString(), schema::toString);
        JsonNode s = doc.at("/components/schemas/NoFixpoint");
        assertEquals("[\"status\",\"error\"]", s.path("required").toString(), s::toString);
        assertEquals(List.of("status", "error", "trace", "coverage", "fixpointError"), keys(s.path("properties")));
        assertEquals("[\"NO_FIXPOINT\"]", s.at("/properties/status/enum").toString(), s::toString);
    }

    // ---------------- via=service ----------------

    /** The scenario without a fixpoint is a FAIL as uchar's report gives it, the same as the ordinary route; the other
     *  scenario of the document is computed and passes. */
    @Test
    void via_service_the_scenario_without_fixpoint_is_a_FAIL_and_the_other_is_a_PASS() {
        String document = source + "\n" + SCENARIO.formatted(1, 2) + "\n" + SCENARIO.formatted(50, 51);
        JsonNode g = Playground.analyseer(document, null).path("scenario").path("results");
        JsonNode d = Playground.analyseer(document, null, core).path("scenario").path("results");
        assertEquals(2, d.size(), d.toString());
        assertEquals("FAIL", g.get(0).path("status").asText(), g.toString());
        assertEquals("FAIL", d.get(0).path("status").asText(), d.toString());
        assertEquals("PASS", g.get(1).path("status").asText(), g.toString());
        assertEquals("PASS", d.get(1).path("status").asText(), d.toString());

        JsonNode error = d.get(0);
        assertEquals("estafette", error.path("path").asText());
        assertEquals(g.get(0).path("diagnosis"), error.path("diagnosis"));
        assertTrue(error.path("diagnosis").asText().startsWith("case1-keten-vanaf-rang-1.json: " + fragment),
                error.path("diagnosis").asText());
        for (String field : List.of("expectations", "trace", "coverage"))
            assertEquals(g.get(0).path(field), error.path(field), field);
        assertEquals(0, error.path("expectations").size());
        assertTrue(error.path("trace").size() > 0, error.toString());
        assertTrue(error.has("explanationError"), error.toString());
        assertTrue(error.path("fixpointError").asText().startsWith(fragment), error.toString());

        JsonNode pass = d.get(1);
        assertEquals(g.get(1).path("expectations"), pass.path("expectations"));
        assertEquals("51", pass.at("/expectations/0/actual").asText(), pass.toString());
    }

    /** Over HTTP the analysis as a whole is a 200. */
    @Test
    void via_service_over_http_a_200() throws Exception {
        String document = source + "\n" + SCENARIO.formatted(1, 2) + "\n" + SCENARIO.formatted(50, 51);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + "/playground/analyse?via=service"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(document, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode results = ServiceHttp.MAPPER.readTree(r.body()).path("scenario").path("results");
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
