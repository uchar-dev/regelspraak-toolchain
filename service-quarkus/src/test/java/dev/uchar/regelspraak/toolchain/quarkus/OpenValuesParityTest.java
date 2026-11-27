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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Quarkus adapter and {@code ServiceHttp} over one core of a rule set that infers nothing from an empty value
 * ({@link OpenValuesSource}) give the same answers, with the states of the members, the explanation with its parts and the open
 * inputs, the same 400s and the same {@code /info} and OpenAPI document.
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
                + " {\"state\": \"holdsNot\"}, \"onleesbaar\": {\"state\": \"holds\"}}", 200);
        assertEquals("{\"state\":\"filled\",\"value\":60}", given.at("/instances/0/attributes/vindloon").toString());
        assertEquals("{\"state\":\"filled\",\"value\":0}", given.at("/instances/0/attributes/herkomst").toString());
        assertEquals("{\"state\":\"holdsNot\"}", given.at("/instances/0/characteristics/betwist").toString());
        assertEquals("{\"state\":\"holds\"}", given.at("/instances/0/characteristics/onleesbaar").toString());
        JsonNode empty = compare(core, service, "vondst", ", \"attributes\": {\"taxatie\": {\"state\": \"empty\"},"
                + " \"herkomst\": {\"state\": \"empty\"}}, \"characteristics\": {\"onleesbaar\": {\"state\": \"holds\"},"
                + " \"betwist\": {\"state\": \"holdsNot\"}}", "true", "true", "true", 200);
        assertEquals("{\"state\":\"empty\"}", empty.at("/instances/0/attributes/taxatie").toString());
        assertEquals("{\"state\":\"filled\",\"value\":10}", empty.at("/instances/0/attributes/vindloon").toString());
        assertEquals("{\"state\":\"empty\"}", empty.at("/instances/0/attributes/bergloon").toString());
        assertEquals("{\"state\":\"filled\",\"value\":0}", empty.at("/instances/0/attributes/herkomst").toString());
        assertEquals("[]", empty.get("openInputs").toString());
        assertTrue(empty.at("/fixpoint/objects/0/values").toString()
                .contains("{\"attribute\":\"herkomst\",\"value\":\"0\",\"origin\":\"derived\",\"input\":{\"state\":\"empty\"}}"),
                empty.toString());
    }

    @Test
    void invalid_attribute_states_are_the_same_400() throws Exception {
        for (String[] bad : new String[][]{
                {"{\"state\": \"open\"}", "WRONG_TYPE"},
                {"{\"state\": \"filled\", \"value\": 50}", "WRONG_TYPE"},
                {"{\"state\": \"empty\", \"extra\": 1}", "MALFORMED"},
                {"{\"state\": 123}", "MALFORMED"}}) {
            JsonNode body = compare("vondst", ", \"attributes\": {\"taxatie\": " + bad[0] + "}", 400);
            assertEquals(1, body.get("violations").size(), body.toString());
            assertEquals(bad[1], body.at("/violations/0/kind").asText(), body.toString());
            assertEquals("instances[0].attributes.taxatie", body.at("/violations/0/field").asText(), body.toString());
        }
    }

    @Test
    void a_bare_boolean_is_the_same_400() throws Exception {
        for (String bare : new String[]{"true", "false"}) {
            JsonNode body = compare("vondst", ", \"characteristics\": {\"betwist\": " + bare + "}", 400);
            assertEquals(1, body.get("violations").size(), body.toString());
            assertEquals("WRONG_TYPE", body.at("/violations/0/kind").asText());
            assertEquals("instances[0].characteristics.betwist", body.at("/violations/0/field").asText());
            assertEquals("kenmerk 'betwist' is " + bare + ", en een regelset die uit leeg niets afleidt, vraagt een stand:"
                    + " {\"state\": \"holds\"} waar het geldt, {\"state\": \"holdsNot\"} waar vaststaat dat het niet geldt;"
                    + " waar het niet gegeven is, ontbreekt het of is het null", body.at("/violations/0/error").asText());
        }
    }

    @Test
    void a_422_is_equal() throws Exception {
        assertEquals("EF-014", compare("vracht", ", \"attributes\": {\"taxatie\": 5}", 422).at("/faults/0/code").asText());
        String ef049 = """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": ["V1"]}},
                           {"type": "Vondst", "id": "V1", "roles": {"jutter": {"state": "empty"}}}]}""";
        JsonNode conflict = compareRaw(core, service, "vondst", ef049, null, null, null, 422);
        assertEquals("EF-049", conflict.at("/faults/0/code").asText(), conflict.toString());
        assertEquals("V1", conflict.at("/faults/0/params/label").asText(), conflict.toString());
        assertEquals("jutter", conflict.at("/faults/0/params/role").asText(), conflict.toString());
    }

    @Test
    void role_states_and_invalid_role_states_are_equal() throws Exception {
        JsonNode emptyRole = compare(core, service, "vondst", ", \"roles\": {\"jutter\": {\"state\": \"empty\"}}",
                null, null, "true", 200);
        assertFalse(emptyRole.at("/fixpoint/objects/0/open").toString().contains("\"role\":\"jutter\""),
                emptyRole.toString());
        JsonNode nullRole = compare(core, service, "vondst", ", \"roles\": {\"jutter\": null}",
                null, null, "true", 200);
        assertTrue(nullRole.at("/fixpoint/objects/0/open").toString()
                .contains("{\"role\":\"jutter\",\"factType\":\"vangst\"}"),
                nullRole.toString());
        for (String[] bad : new String[][]{
                {"{\"state\": \"open\"}", "WRONG_TYPE"},
                {"{\"state\": \"filled\", \"value\": \"J1\"}", "WRONG_TYPE"},
                {"{\"state\": \"empty\", \"extra\": 1}", "MALFORMED"},
                {"{\"state\": 123}", "MALFORMED"}}) {
            JsonNode body = compare("vondst", ", \"roles\": {\"jutter\": " + bad[0] + "}", 400);
            assertEquals(1, body.get("violations").size(), body.toString());
            assertEquals(bad[1], body.at("/violations/0/kind").asText(), body.toString());
            assertEquals("instances[0].roles.jutter", body.at("/violations/0/field").asText(), body.toString());
        }
        String toManyEmpty = "{\"instances\": [{\"type\": \"Jutter\", \"id\": \"J1\","
                + " \"roles\": {\"vondsten\": {\"state\": \"empty\"}}}]}";
        JsonNode many = compareRaw(core, service, "vondst", toManyEmpty, null, null, null, 400);
        assertEquals("WRONG_TYPE", many.at("/violations/0/kind").asText(), many.toString());
        assertEquals("instances[0].roles.vondsten", many.at("/violations/0/field").asText(), many.toString());

        DecisionCore wrakken = OpenValuesSource.wrak(CoreConfig.strict().withDetail());
        ServiceHttp wrakHttp = ServiceHttp.bind(wrakken, "127.0.0.1", 0).start();
        try {
            JsonNode wrakOpen = compareRaw(wrakken, wrakHttp, "wrak",
                    "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"attributes\": {\"lengte\": 30}}]}",
                    "true", "true", "true", 200);
            assertTrue(wrakOpen.get("openInputs").toString()
                    .contains("\"kind\":\"role\",\"instance\":\"W1\",\"factType\":\"wrakvondst\",\"name\":\"vinder\""),
                    wrakOpen.toString());
            assertTrue(wrakOpen.get("explanation").toString().contains("want zijn vinder is niet gegeven"),
                    wrakOpen.toString());
        } finally {
            wrakHttp.stop(0);
        }
    }

    @Test
    void the_explanation_and_the_open_inputs_are_equal() throws Exception {
        JsonNode open = compare(core, service, "vondst", "", "true", "true", 200);
        assertTrue(open.get("explanation").toString().contains("V1: zijn vindloon staat niet vast, want zijn taxatie is niet"
                + " gegeven"), open.get("explanation").toString());
        assertEquals(open.get("explanation").size(), open.get("explanationParts").size());
        assertEquals("taxatie", open.at("/openInputs/0/name").asText());
        JsonNode one = compare(core, service, "vondst", "", null, "true", 200);
        assertEquals(open.get("openInputs"), one.get("openInputs"));
        assertFalse(one.has("explanation"));
        assertFalse(compare(core, service, "vondst", "", null, "false", 200).has("openInputs"));
    }

    /** A rule set that is not strict refuses the open inputs, and an entry point that does not explain refuses both. */
    @Test
    void the_refusals_of_the_open_inputs_are_equal() throws Exception {
        String source = OpenValuesSource.SOURCE.replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", "");
        String without = OpenValuesSource.DESCRIPTOR.replace("\"input\": [],", "\"input\": [], \"explanation\": false,");
        for (String[] c : new String[][]{{OpenValuesSource.DESCRIPTOR, source, null, "openInputs"},
                {without, OpenValuesSource.SOURCE, null, "openInputs"},
                {without, OpenValuesSource.SOURCE, "true", "explanation"}}) {
            DecisionCore other = DecisionCore.assemble(c[0], java.util.Map.of("b.rgs", c[1]), CoreConfig.strict(),
                    Thread.currentThread().getContextClassLoader());
            ServiceHttp jdk = ServiceHttp.bind(other, "127.0.0.1", 0).start();
            try {
                JsonNode body = compare(other, jdk, "vondst", "", c[2], "true", 400);
                assertEquals("UNSUPPORTED", body.at("/violations/0/kind").asText());
                assertEquals(c[3], body.at("/violations/0/field").asText());
            } finally {
                jdk.stop(0);
            }
        }
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
        return compare(core, service, path, given, null, null, expected);
    }

    /** {@code parts} and {@code openInputs}: the values of their query parameters, null where the request has none. */
    private static JsonNode compare(DecisionCore core, ServiceHttp service, String path, String given, String parts,
                                    String openInputs, int expected) throws Exception {
        return compare(core, service, path, given, parts, openInputs, null, expected);
    }

    private static JsonNode compare(DecisionCore core, ServiceHttp service, String path, String given, String parts,
                                    String openInputs, String fixpoint, int expected) throws Exception {
        String request = "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"" + given + "}]}";
        return compareRaw(core, service, path, request, parts, openInputs, fixpoint, expected);
    }

    private static JsonNode compareRaw(DecisionCore core, ServiceHttp service, String path, String request, String parts,
                                       String openInputs, String fixpoint, int expected) throws Exception {
        DecideResource resource = new DecideResource();
        resource.core = core;
        Response quarkus = resource.process(path, null, null, null, null, null, fixpoint, parts, openInputs, request);
        String query = (parts == null ? "" : "&explanationParts=" + parts)
                + (openInputs == null ? "" : "&openInputs=" + openInputs)
                + (fixpoint == null ? "" : "&fixpoint=" + fixpoint);
        HttpResponse<String> jdk = CLIENT.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + service.port() + "/" + path + (query.isEmpty() ? "" : "?"
                                + query.substring(1))))
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
