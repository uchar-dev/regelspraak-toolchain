package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An absence marker on a cell through the service (uchar's RUNTIME-SEMANTICS ch. 9.5, a dimensioned attribute with
 * markers), on uchar's golden jutterswet-afwezigheidsmarkers-cel: a request gives a cell a value of the datatype or a
 * marker, {@code {"marker": "onbekend"}}; the answer and the fixpoint of the detail give each cell in the same form; the
 * explanation names a marker on a cell by its name, as the value a rule wrote into the cell and as the value a condition
 * read there; and a marker the attribute does not declare is a 400 on the value of the cell. The cases are those of the
 * golden, with uchar's pinned values.
 */
class AbsenceMarkerCellHttpTest {

    private static final Path GOLDEN = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-afwezigheidsmarkers-cel");

    private static final String DESCRIPTOR = """
        { "service": {"name":"markers-cel","version":"1"},
          "entrypoints": [ { "name":"markers","path":"markers",
            "scope": {"ruleNames": ["najaarsgewicht uit schatting", "voorjaarsgewicht onbekend", "najaarsloon",
                                    "voorjaarstoeslag", "zware vondst"]},
            "input": [],
            "output": ["Vondst.gewicht", "Vondst.najaarsloon", "Vondst.voorjaarstoeslag", "Vondst:zwaar"] } ],
          "parameters": {} }""";

    private static ServiceHttp service;
    private static ServiceHttp detail;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Map<String, String> source = Map.of("source.rgs", Files.readString(GOLDEN.resolve("source.rgs")));
        service = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR, source, CoreConfig.strict(), cl), "127.0.0.1", 0)
                .start();
        detail = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR, source, CoreConfig.strict().withDetail(), cl),
                "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
        if (detail != null) detail.stop(0);
    }

    // ---------------- the request and the answer ----------------

    /** case4: a marker in each cell of the request stands where no rule decides the cell, and the rules read it
     *  (uchar's pins: najaarsloon leeg, voorjaarstoeslag 7,00, not zwaar). */
    @Test
    void a_marker_in_a_cell_of_the_request_stands_and_the_rules_read_it() throws Exception {
        JsonNode answer = ok(post(service, "", vondst("""
                "attributes":{"gewicht":{"cells":[{"labels":["najaar"],"value":{"marker":"onbekend"}},
                                                  {"labels":["voorjaar"],"value":{"marker":"onbekend"}}]}}""")));
        assertEquals(List.of("voorjaar=onbekend", "najaar=onbekend"), cells(attribute(answer, "V1", "gewicht")),
                answer.toString());
        assertTrue(attribute(answer, "V1", "najaarsloon").isNull(), answer.toString());
        assertNumber("7.00", attribute(answer, "V1", "voorjaarstoeslag"), answer.toString());
        assertFalse(answer.at("/instances/0/characteristics/zwaar").asBoolean(true), answer.toString());
    }

    /** case2: the marker a rule writes into a cell displaces the value of the request in that cell (uchar's pins:
     *  gewicht in voorjaar onbekend, voorjaarstoeslag 7,00). */
    @Test
    void a_marker_a_rule_writes_into_a_cell_displaces_the_value_of_the_request() throws Exception {
        JsonNode answer = ok(post(service, "", vondst("""
                "attributes":{"gewicht":{"cells":[{"labels":["voorjaar"],"value":10.00}]}},
                "characteristics":{"onleesbaar":true}""")));
        assertEquals(List.of("voorjaar=onbekend", "najaar=null"), cells(attribute(answer, "V1", "gewicht")),
                answer.toString());
        assertNumber("7.00", attribute(answer, "V1", "voorjaarstoeslag"), answer.toString());
    }

    /** case1: the value a rule writes into a cell is its payload in the answer (uchar's pins: gewicht in najaar 60,00,
     *  najaarsloon 120,00). */
    @Test
    void a_value_in_a_cell_is_answered_as_its_payload() throws Exception {
        JsonNode answer = ok(post(service, "", vondst("\"attributes\":{\"schatting\":60.00}")));
        assertEquals(List.of("voorjaar=null", "najaar=60"), cells(attribute(answer, "V1", "gewicht")),
                answer.toString());
        assertNumber("120.00", attribute(answer, "V1", "najaarsloon"), answer.toString());
    }

    /** The fixpoint of the detail gives a marker in a cell as a marker, on the row of that cell. */
    @Test
    void the_fixpoint_gives_a_marker_in_a_cell_as_a_marker() throws Exception {
        JsonNode answer = ok(post(detail, "fixpoint=true", vondst("\"characteristics\":{\"onleesbaar\":true}")));
        List<String> rows = new ArrayList<>();
        for (JsonNode o : answer.at("/fixpoint/objects"))
            for (JsonNode a : o.path("values"))
                if ("gewicht".equals(a.path("attribute").asText()))
                    rows.add(a.path("labels") + "=" + a.path("value") + " " + a.path("origin").asText());
        assertEquals(List.of("[\"voorjaar\"]={\"marker\":\"onbekend\"} derived"), rows, answer.toString());
    }

    /** A marker the dimensioned attribute does not declare is a 400 on the value of its cell. */
    @Test
    void a_marker_the_attribute_does_not_declare_in_a_cell_is_400_unknown_marker() throws Exception {
        JsonNode v = violation(post(service, "", vondst("""
                "attributes":{"gewicht":{"cells":[{"labels":["najaar"],"value":{"marker":"niet van toepassing"}}]}}""")));
        assertEquals("UNKNOWN_MARKER", v.get("kind").asText(), v.toString());
        assertEquals("instances[0].attributes.gewicht.cells[0].value", v.get("field").asText(), v.toString());
        assertTrue(v.get("error").asText().contains("'niet van toepassing'")
                && v.get("error").asText().contains("(toegestaan: onbekend)"), v.toString());
    }

    // ---------------- the explanation ----------------

    /** case2: the marker a rule wrote into a cell is named by its name in the sentence of that cell. */
    @Test
    void the_explanation_names_a_marker_a_rule_wrote_into_a_cell() throws Exception {
        List<String> explanation = explanation(post(service, "explanation=true", vondst("""
                "attributes":{"gewicht":{"cells":[{"labels":["voorjaar"],"value":10.00}]}},
                "characteristics":{"onleesbaar":true}""")));
        assertContains(explanation, "V1 heeft als gewicht in voorjaar onbekend volgens de regel 'voorjaarsgewicht"
                + " onbekend': onbekend, omdat het onderwerp onleesbaar is");
        assertFalse(String.join("\n", explanation).contains("waarde("), String.join("\n", explanation));
    }

    /** case4: a marker in a cell of the request is named by its name where a condition read that cell, in the test of a
     *  value and in a comparison. */
    @Test
    void the_explanation_names_a_marker_a_condition_read_in_a_cell() throws Exception {
        List<String> explanation = explanation(post(service, "explanation=true", vondst("""
                "attributes":{"gewicht":{"cells":[{"labels":["najaar"],"value":{"marker":"onbekend"}},
                                                  {"labels":["voorjaar"],"value":{"marker":"onbekend"}}]}}""")));
        assertContains(explanation, "zijn gewicht in najaar (onbekend) een waarde heeft");
        assertContains(explanation, "zijn gewicht in voorjaar (onbekend) is gelijk aan onbekend");
        assertFalse(String.join("\n", explanation).contains("waarde("), String.join("\n", explanation));
    }

    // ---------------- helpers ----------------

    private static String vondst(String members) {
        return "{\"instances\":[{\"type\":\"Vondst\",\"id\":\"V1\"" + (members.isBlank() ? "" : "," + members) + "}]}";
    }

    /** The cells of {@code actual} as {@code label=value}, in the order of the answer: a number without trailing zeros,
     *  a marker by its name, {@code null} for an empty cell. */
    private static List<String> cells(JsonNode actual) {
        assertTrue(actual != null && actual.isObject() && actual.has("cells"), String.valueOf(actual));
        List<String> out = new ArrayList<>();
        for (JsonNode c : actual.get("cells")) {
            JsonNode v = c.get("value");
            String value = v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString()
                    : v.isObject() && v.size() == 1 && v.get("marker") != null ? v.get("marker").asText() : v.toString();
            out.add(String.join(",", c.get("labels").valueStream().map(JsonNode::asText).toList()) + "=" + value);
        }
        return out;
    }

    private static JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return tree(r.body());
    }

    private static List<String> explanation(HttpResponse<String> r) throws Exception {
        JsonNode sentences = ok(r).get("explanation");
        assertTrue(sentences != null && sentences.isArray(), r.body());
        List<String> out = new ArrayList<>();
        sentences.forEach(s -> out.add(s.asText()));
        return out;
    }

    private static void assertContains(List<String> explanation, String fragment) {
        assertTrue(explanation.stream().anyMatch(s -> s.contains(fragment)),
                "geen zin bevat \"" + fragment + "\":\n" + String.join("\n", explanation));
    }

    private static void assertNumber(String expected, JsonNode actual, String context) {
        assertTrue(actual != null && actual.isNumber(), context);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), context);
    }

    private static JsonNode violation(HttpResponse<String> r) throws Exception {
        assertEquals(400, r.statusCode(), r.body());
        JsonNode violations = tree(r.body()).get("violations");
        assertEquals(1, violations.size(), r.body());
        return violations.get(0);
    }

    private static JsonNode attribute(JsonNode answer, String id, String attribute) {
        for (JsonNode inst : answer.get("instances"))
            if (id.equals(inst.path("id").asText())) return inst.path("attributes").get(attribute);
        return null;
    }

    private static HttpResponse<String> post(ServiceHttp to, String query, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + "/markers"
                                + (query.isEmpty() ? "" : "?" + query)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode tree(String json) throws Exception {
        return ServiceHttp.MAPPER.readTree(json);
    }
}
