package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Quarkus adapter and {@code ServiceHttp} over one kern answer a request whose session reaches no fixpoint with the
 * same 422 and the same body, byte for byte, without and with the detail flags: uchar's red golden
 * {@code jutterswet-budgetgrens-over}, whose chain from a Wachter of rang 1 passes uchar's creation budget. A plain
 * JUnit test, as {@link ErrorParityTest} and {@link DetailParityTest}: the resource is called directly with the kern
 * set, and {@code ServiceHttp} is asked over HTTP on an ephemeral port.
 */
class NoFixpointParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    private static final Path RED = TokaSource.REPO.resolve("uchar/corpus/red/jutterswet-budgetgrens-over");
    private static final String DESCRIPTOR = """
            { "service": {"name":"estafette","version":"1"},
              "entrypoints": [ { "name":"estafette","path":"estafette",
                "scope": {"ruleNames": ["opvolgerrang van een wachter", "rangplafond", "eerste keten aan het plafond",
                                         "tweede keten aan het plafond", "werf de lange keten"]},
                "input": ["Wachter"], "output": ["Wachter.opvolgerrang"] } ],
              "parameters": {} }""";
    private static final String REQUEST =
            "{\"instances\":[{\"type\":\"Wachter\",\"id\":\"W1\",\"attributes\":{\"rang\":1,\"ketennummer\":1}}]}";

    private static DecisionCore core;
    private static ServiceHttp service;
    /** The sentence uchar pins for the Drools branch on this chain, in its own red case. */
    private static String fragment;

    @BeforeAll
    static void assemble() throws Exception {
        core = DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", Files.readString(RED.resolve("source.rgs"))),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        // uchar pins the code of the fault, no longer its sentence; the sentence leads with the same words in both languages
        assertEquals("EF-015", MAPPER.readTree(Files.readString(RED.resolve("case1.json")))
                .at("/expectedFault/code").asText());
        fragment = "creation budget exhausted: ";
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
    }

    @Test
    void without_flag_the_same_422() throws Exception {
        JsonNode body = compare(null, null, null, "");
        assertEquals("NO_FIXPOINT", body.path("status").asText(), body.toString());
        assertTrue(body.path("error").asText().startsWith(fragment), body.toString());
    }

    @Test
    void with_the_detail_flags_the_same_422() throws Exception {
        JsonNode body = compare("true", "true", "true", "?trace=true&coverage=true&fixpoint=true");
        assertEquals("NO_FIXPOINT", body.path("status").asText(), body.toString());
        assertTrue(body.path("trace").size() > 0, body.toString());
        assertEquals(body.path("error"), body.path("fixpointError"), body.toString());
    }

    /** The same POST to both adapters: a 422 from each and the same body, which it returns. */
    private static JsonNode compare(String trace, String coverage, String fixpoint, String query) throws Exception {
        DecideResource resource = new DecideResource();
        resource.core = core;
        Response quarkus = resource.process("estafette", null, null, null, trace, coverage, fixpoint, null, REQUEST);
        HttpResponse<String> jdk = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + service.port() + "/estafette" + query))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(REQUEST, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(422, jdk.statusCode(), jdk.body());
        assertEquals(422, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        assertEquals(jdk.body(), quarkus.getEntity(), "JDK en Quarkus geven hetzelfde lichaam, byte voor byte");
        return MAPPER.readTree(jdk.body());
    }
}
