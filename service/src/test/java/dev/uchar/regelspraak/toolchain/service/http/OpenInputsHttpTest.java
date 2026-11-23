package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.OpenValuesSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ?openInputs=true} of a strict rule set ({@link OpenValuesSource}): the input members that keep an open outcome of
 * {@code output} open now, each once, with the outcomes it keeps open. The lists are written by hand from the rules.
 */
class OpenInputsHttpTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static ServiceHttp service;
    private static ServiceHttp wrakken;

    @BeforeAll
    static void start() throws Exception {
        service = ServiceHttp.bind(OpenValuesSource.core(CoreConfig.strict()), "127.0.0.1", 0).start();
        wrakken = ServiceHttp.bind(OpenValuesSource.wrak(CoreConfig.strict()), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        for (ServiceHttp s : new ServiceHttp[]{service, wrakken}) if (s != null) s.stop(0);
    }

    /** vindloon, uitbetaling and bergloon read the taxatie, and bewakingstoeslag the characteristic kostbaar that a rule
     *  derives from it; taxatie and betwist are in {@code output} themselves. One member for many outcomes stands once. */
    @Test
    void an_outcome_open_by_an_input_and_by_a_derived_member() throws Exception {
        assertEquals(json("""
            [{"kind": "attribute", "instance": "V1", "name": "taxatie", "forOutputs": [
                {"instance": "V1", "name": "taxatie", "kind": "attribute"},
                {"instance": "V1", "name": "vindloon", "kind": "attribute"},
                {"instance": "V1", "name": "uitbetaling", "kind": "attribute"},
                {"instance": "V1", "name": "bewakingstoeslag", "kind": "attribute"},
                {"instance": "V1", "name": "bergloon", "kind": "attribute"},
                {"instance": "V1", "name": "kostbaar", "kind": "characteristic"}]},
             {"kind": "characteristic", "instance": "V1", "name": "betwist", "forOutputs": [
                {"instance": "V1", "name": "premie", "kind": "attribute"},
                {"instance": "V1", "name": "betwist", "kind": "characteristic"}]}]"""),
                open("vondst", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"}]}"));
    }

    /** With the taxatie given only betwist is left; with both given nothing is, and the list is there and empty. */
    @Test
    void what_is_given_leaves_the_list() throws Exception {
        JsonNode left = open("vondst", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\": {\"taxatie\":"
                + " 50}}]}");
        assertEquals(json("[\"betwist\"]"), names(left));
        assertEquals(json("[]"), open("vondst", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\":"
                + " {\"taxatie\": 50}, \"characteristics\": {\"betwist\": {\"state\": \"holdsNot\"}}}]}"));
    }

    /** wrakloon reads the ladingwaarde and then the lengte: without a lading it stops at the ladingwaarde, which the lading
     *  keeps open; once the lading is given, the lengte is the next question. */
    @Test
    void the_next_question_after_the_first_answer() throws Exception {
        assertEquals(json("[\"lading\"]"), namesFor(wrak(""), "W1", "wrakloon"));
        assertEquals(json("[\"lengte\"]"), namesFor(wrak("\"attributes\": {\"lading\": 5}"), "W1", "wrakloon"));
        assertEquals(json("[]"), namesFor(wrak("\"attributes\": {\"lading\": 5, \"lengte\": 3}"), "W1", "wrakloon"));
    }

    /** The sum over three wrecks breaks off at the first without a lading, W2; given that, at W3. */
    @Test
    void a_fold_over_three_objects() throws Exception {
        assertEquals(json("[\"W2 lading\"]"), membersFor(jutter("\"lading\": 5", "", ""), "J1", "ladingsom"));
        assertEquals(json("[\"W3 lading\"]"), membersFor(jutter("\"lading\": 5", "\"lading\": 7", ""), "J1", "ladingsom"));
        assertEquals(json("[]"), membersFor(jutter("\"lading\": 5", "\"lading\": 7", "\"lading\": 9"), "J1", "ladingsom"));
    }

    /** A composite is read past its first undetermined member, so all of its open members are named; one that is decided
     *  has none. */
    @Test
    void a_composite_names_every_member_that_is_undetermined() throws Exception {
        assertEquals(json("[\"diepte\", \"lengte\"]"), namesFor(wrak("\"attributes\": {\"lading\": 5}"), "W1", "gevaarlijk"));
        assertEquals(json("[\"diepte\"]"), namesFor(wrak("\"attributes\": {\"lading\": 5, \"lengte\": 5}"), "W1", "gevaarlijk"));
        assertEquals(json("[]"), namesFor(wrak("\"attributes\": {\"lengte\": 30}"), "W1", "gevaarlijk"));
        // the default that is open keeps the stack open, and not those below it
        assertEquals(json("[\"betwist\"]"), namesFor(wrak(""), "W1", "wrakpremie"));
        // two exceptions without an order between them, both open
        assertEquals(json("[\"betwist\", \"beschadigd\"]"), namesFor(wrak("\"attributes\": {\"lengte\": 30}"), "W1",
                "bergingsloon"));
    }

    /** gepeild and ongepeild are open by the test on gevuld and on leeg over the diepte; volledig gepeild, the aantal gepeilde
     *  wrakken and the gepeilde ladingsom by their fold, which breaks off at the diepte of the first wreck without one. */
    @Test
    void an_outcome_that_a_test_on_gevuld_or_a_fold_leaves_open() throws Exception {
        assertEquals(json("[\"diepte\"]"), namesFor(wrak(""), "W1", "gepeild"));
        assertEquals(json("[\"diepte\"]"), namesFor(wrak(""), "W1", "ongepeild"));
        JsonNode open = jutter("\"lading\": 5", "\"lading\": 7, \"diepte\": 3", "");
        assertEquals(json("[\"W1 diepte\"]"), membersFor(open, "J1", "volledig gepeild"));
        assertEquals(json("[\"W1 diepte\"]"), membersFor(open, "J1", "aantal gepeilde wrakken"));
        assertEquals(json("[\"W1 diepte\"]"), membersFor(jutter("\"lading\": 5", "", ""), "J1", "gepeilde ladingsom"));
    }

    /** A read over a role: the bonus of the vinder, asked of J1. */
    @Test
    void a_member_of_another_instance() throws Exception {
        assertEquals(json("[\"J1 bonus\"]"), membersFor(open("wrak", """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1"]}},
                           {"type": "Wrak", "id": "W1", "attributes": {"lengte": 30}}]}"""), "W1", "vindpremie"));
    }

    /** It stands beside the explanation and without it, and the answer is the same otherwise. */
    @Test
    void the_answer_is_the_same_beside_it() throws Exception {
        String request = "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"}]}";
        JsonNode plain = ok(post("wrak", request));
        JsonNode with = ok(post("wrak?openInputs=true", request));
        JsonNode both = ok(post("wrak?openInputs=true&explanation=true", request));
        assertEquals(plain, ((ObjectNode) with.deepCopy()).without(List.of("openInputs")));
        assertEquals(with.get("openInputs"), both.get("openInputs"));
        assertEquals(plain, ((ObjectNode) both.deepCopy()).without(List.of("openInputs", "explanation")));
        assertFalse(plain.has("openInputs"));
    }

    @Test
    void the_openapi_document_has_the_flag_and_the_field() throws Exception {
        JsonNode doc = json(get("openapi"));
        assertTrue(doc.at("/paths/~1vondst/post/parameters").toString().contains("\"name\":\"openInputs\""));
        JsonNode item = doc.at("/components/schemas/Answer/properties/openInputs/items/properties");
        assertEquals(json("[\"attribute\", \"characteristic\"]"), item.at("/kind/enum"));
        assertEquals(json("[\"attribute\", \"characteristic\"]"), item.at("/forOutputs/items/properties/kind/enum"));
        JsonNode part = doc.at("/components/schemas/ExplanationPart/properties");
        assertEquals("boolean", part.at("/undetermined/type").asText());
        assertTrue(part.at("/closure").isMissingNode(), part.toString());
        assertEquals("integer", part.at("/unsettled/type").asText());
        assertTrue(part.at("/open/properties").has("canonical"));
    }

    // ---------------- an entry point that does not give it ----------------

    /** The rule set that infers from empty has no open outcome: a 400 on openInputs, and none of it in its OpenAPI document. */
    @Test
    void an_ordinary_rule_set_refuses_it() throws Exception {
        String source = OpenValuesSource.SOURCE.replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", "");
        ServiceHttp ordinary = ServiceHttp.bind(DecisionCore.assemble(OpenValuesSource.DESCRIPTOR, Map.of("b.rgs", source),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            JsonNode v = refused(ordinary, "vondst?openInputs=true", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"}]}");
            assertEquals("openInputs", v.get("field").asText());
            assertEquals("openInputs noemt wat een open uitkomst openhoudt, en alleen een regelset die uit leeg niets afleidt"
                    + " (Afleiden uit leeg: nee) heeft open uitkomsten", v.get("error").asText());
            HttpResponse<String> doc = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ordinary.port()
                    + "/openapi")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(doc.body().contains("openInputs") || doc.body().contains("undetermined"));
        } finally {
            ordinary.stop(0);
        }
    }

    @Test
    void an_entry_point_that_does_not_explain_refuses_it() throws Exception {
        String descriptor = """
            { "service": {"name":"zonder","version":"1"},
              "entrypoints": [ { "name":"vracht","path":"vracht", "scope": {"ruleNames": ["vrachtprijs"]},
                                 "input": ["Vondst"], "output": ["Vondst.vrachtprijs"], "explanation": false } ],
              "parameters": {} }""";
        ServiceHttp without = ServiceHttp.bind(DecisionCore.assemble(descriptor, Map.of("b.rgs", OpenValuesSource.SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            JsonNode v = refused(without, "vracht?openInputs=true", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"}]}");
            assertEquals("openInputs", v.get("field").asText());
            assertEquals("het entrypoint 'vracht' geeft geen openInputs: de descriptor zet explanation op false",
                    v.get("error").asText());
        } finally {
            without.stop(0);
        }
    }

    /** A fold that can be open over a relation both of whose roles are to-many: uchar carries the document without readings
     *  and refuses it with them, so the entry point answers and refuses what the readings give, with uchar's sentence. */
    @Test
    void a_document_that_uchar_refuses_with_readings_answers_without_them() throws Exception {
        String source = """
            Afleiden uit leeg: nee

            Objecttype de Jutter (mv: Jutters) (bezield)
                de hulpsom Numeriek (geheel getal);

            Objecttype het Wrak (mv: Wrakken)
                de lading Numeriek (geheel getal);

            Feittype hulp
                de helper (mv: helpers) Jutter
                het geholpen wrak (mv: geholpen wrakken) Wrak
                meerdere helpers bergen meerdere geholpen wrakken

            Regel hulpsom
                geldig altijd
                    De hulpsom van een Jutter moet berekend worden als de som van de lading van alle geholpen wrakken van de Jutter.
            """;
        String descriptor = """
            { "service": {"name":"hulp","version":"1"},
              "entrypoints": [ { "name":"hulp","path":"hulp", "scope": {"ruleNames": ["hulpsom"]}, "input": ["Jutter"],
                                 "output": ["Jutter.hulpsom"] } ],
              "parameters": {} }""";
        String request = """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"geholpen wrakken": ["W1", "W2"]}},
              {"type": "Wrak", "id": "W1", "attributes": {"lading": 5}}, {"type": "Wrak", "id": "W2"%s}]}""";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String document = dev.uchar.regelspraak.toolchain.engine.UcharBuild.document(
                dev.uchar.regelspraak.resolver.Resolver.resolve(source));
        for (DecisionCore core : List.of(
                DecisionCore.assemble(descriptor, Map.of("b.rgs", source), CoreConfig.strict(), cl),
                DecisionCore.buildFromDocument(descriptor, document, CoreConfig.strict(), cl, null))) {
            ServiceHttp hulp = ServiceHttp.bind(core, "127.0.0.1", 0).start();
            try {
                assertEquals("open", ok(post(hulp, "hulp", request.formatted(""))).at("/instances/0/attributes/hulpsom/state")
                        .asText());
                assertEquals(json("{\"state\": \"filled\", \"value\": 12}"), ok(post(hulp, "hulp",
                        request.formatted(", \"attributes\": {\"lading\": 7}"))).at("/instances/0/attributes/hulpsom"));
                for (String flag : List.of("explanation", "openInputs")) {
                    JsonNode v = refused(hulp, "hulp?" + flag + "=true", request.formatted(""));
                    assertEquals(flag, v.get("field").asText());
                    assertTrue(v.get("error").asText().contains("een vouw over 'hulp', een relatie waarvan beide rollen"
                            + " meervoudig zijn"), v.toString());
                }
            } finally {
                hulp.stop(0);
            }
        }
    }

    // ---------------- helpers ----------------

    private static JsonNode wrak(String given) throws Exception {
        return open("wrak", "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"" + (given.isEmpty() ? "" : ", " + given) + "}]}");
    }

    private static JsonNode jutter(String w1, String w2, String w3) throws Exception {
        return open("wrak", """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1", "W2", "W3"]}},
              {"type": "Wrak", "id": "W1", "attributes": {%s}}, {"type": "Wrak", "id": "W2", "attributes": {%s}},
              {"type": "Wrak", "id": "W3", "attributes": {%s}}]}""".formatted(w1, w2, w3));
    }

    private static JsonNode open(String path, String request) throws Exception {
        return ok(post(path + "?openInputs=true", request)).get("openInputs");
    }

    private static JsonNode names(JsonNode inputs) {
        var out = ServiceHttp.MAPPER.createArrayNode();
        for (JsonNode i : inputs) out.add(i.get("name").asText());
        return out;
    }

    /** The names of the inputs that keep {@code output} of {@code instance} open. */
    private static JsonNode namesFor(JsonNode inputs, String instance, String output) {
        return of(inputs, instance, output, false);
    }

    private static JsonNode membersFor(JsonNode inputs, String instance, String output) {
        return of(inputs, instance, output, true);
    }

    private static JsonNode of(JsonNode inputs, String instance, String output, boolean withInstance) {
        var out = ServiceHttp.MAPPER.createArrayNode();
        for (JsonNode i : inputs)
            for (JsonNode o : i.get("forOutputs"))
                if (o.get("instance").asText().equals(instance) && o.get("name").asText().equals(output))
                    out.add((withInstance ? i.get("instance").asText() + " " : "") + i.get("name").asText());
        return out;
    }

    private static JsonNode refused(ServiceHttp s, String path, String request) throws Exception {
        HttpResponse<String> r = post(s, path, request);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode violations = json(r.body()).get("violations");
        assertEquals(1, violations.size(), r.body());
        assertEquals("UNSUPPORTED", violations.get(0).get("kind").asText(), r.body());
        return violations.get(0);
    }

    private static JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return json(r.body());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return post(path.startsWith("wrak") ? wrakken : service, path, body);
    }

    private static HttpResponse<String> post(ServiceHttp s, String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/" + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/" + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static JsonNode json(String text) throws Exception {
        return ServiceHttp.MAPPER.readTree(text);
    }
}
