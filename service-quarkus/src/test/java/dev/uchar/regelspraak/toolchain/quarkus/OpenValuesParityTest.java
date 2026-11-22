package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.OpenValuesSource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Quarkus adapter and {@code ServiceHttp} over one core of a rule set that infers nothing from an empty value
 * ({@link OpenValuesSource}) give the same answers, with the states of the members, the same 400 for a bare false and the
 * same {@code /info} and OpenAPI document.
 */
class OpenValuesParityTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static DecisionCore core;
    private static ServiceHttp service;

    @BeforeAll
    static void assemble() throws Exception {
        core = OpenValuesSource.core(CoreConfig.strict().withDetail());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
    }

    @Test
    void the_states_of_the_members_are_equal() throws Exception {
        JsonNode open = compare("vondst", "", 200);
        assertEquals("open", open.at("/instances/0/attributes/vindloon/state").asText());
        assertEquals("empty", open.at("/instances/0/attributes/toeslag/state").asText());
        assertEquals("open", open.at("/instances/0/characteristics/betwist/state").asText());
        JsonNode given = compare("vondst", ", \"attributes\": {\"taxatie\": 50}, \"characteristics\": {\"betwist\":"
                + " {\"state\": \"holdsNot\"}, \"onleesbaar\": true}", 200);
        assertEquals("{\"state\":\"filled\",\"value\":60}", given.at("/instances/0/attributes/vindloon").toString());
        assertEquals("{\"state\":\"marker\",\"marker\":\"onbekend\"}",
                given.at("/instances/0/attributes/herkomst").toString());
        assertEquals("{\"state\":\"holdsNot\"}", given.at("/instances/0/characteristics/betwist").toString());
        assertEquals("{\"state\":\"holds\"}", given.at("/instances/0/characteristics/onleesbaar").toString());
    }

    @Test
    void a_bare_false_is_the_same_400() throws Exception {
        JsonNode body = compare("vondst", ", \"characteristics\": {\"betwist\": false}", 400);
        assertEquals("WRONG_TYPE", body.at("/violations/0/kind").asText());
    }

    @Test
    void a_422_is_equal() throws Exception {
        assertEquals("EF-014", compare("vracht", ", \"attributes\": {\"taxatie\": 5}", 422).at("/faults/0/code").asText());
    }

    @Test
    void info_and_openapi_are_equal() throws Exception {
        DecideResource resource = new DecideResource();
        resource.core = core;
        JsonNode info = ServiceHttp.MAPPER.readTree(get("info"));
        assertEquals(info, ServiceHttp.MAPPER.readTree((String) resource.info().getEntity()));
        assertEquals(false, info.get("inferFromEmpty").asBoolean(true));
        assertEquals(get("openapi"), new String((byte[]) resource.openapi(null).getEntity(), StandardCharsets.UTF_8));
    }

    private static JsonNode compare(String path, String given, int expected) throws Exception {
        String request = "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"" + given + "}]}";
        DecideResource resource = new DecideResource();
        resource.core = core;
        Response quarkus = resource.process(path, null, null, null, request);
        HttpResponse<String> jdk = CLIENT.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + service.port() + "/" + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, jdk.statusCode(), jdk.body());
        assertEquals(expected, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        JsonNode body = ServiceHttp.MAPPER.readTree(jdk.body());
        assertEquals(body, ServiceHttp.MAPPER.readTree((String) quarkus.getEntity()));
        return body;
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + service.port() + "/" + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }
}
