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
 * The absence markers through the service (uchar's RUNTIME-SEMANTICS ch. 9.5, the dialect's "(of onbekend of niet van
 * toepassing)"), on uchar's golden jutterswet-afwezigheidsmarkers: a request gives an attribute with markers a plain
 * value, which is the value {@code waarde(…)}, or a marker, {@code {"marker": "onbekend"}}, also as the value of a
 * segment of a timeline; the answer gives such an attribute in the same form; the explanation names a marker by its
 * name, also per period; and a marker the attribute does not declare, or one on an attribute without markers, is a 400
 * that names it. The cases are those of the golden, with uchar's pinned values.
 */
class AbsenceMarkerHttpTest {

    private static final Path GOLDEN = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-afwezigheidsmarkers");

    private static final String DESCRIPTOR = """
        { "service": {"name":"markers","version":"1"},
          "entrypoints": [ { "name":"markers","path":"markers",
            "scope": {"ruleNames": ["taxatie uit schatting", "taxatie onbekend", "taxatie niet van toepassing",
                                    "vindloon naar taxatie", "forfaitair vindloon", "geen vindloon", "kostbare vondst",
                                    "getaxeerd totaal", "opslag onbekend in de winter",
                                    "opslag niet van toepassing na verkoop", "opslagtoeslag"]},
            "input": [],
            "output": ["Vondst.taxatie", "Vondst.vindloon", "Vondst:kostbaar", "Vondst.opslagkosten",
                       "Vondst.opslagtoeslag", "Jutter.getaxeerd totaal"] } ],
          "parameters": {} }""";

    private static ServiceHttp service;
    private static ServiceHttp detail;
    private static ServiceHttp document;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Map<String, String> source = Map.of("source.rgs", Files.readString(GOLDEN.resolve("source.rgs")));
        service = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR, source, CoreConfig.strict(), cl), "127.0.0.1", 0)
                .start();
        detail = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR, source, CoreConfig.strict().withDetail(), cl),
                "127.0.0.1", 0).start();
        document = ServiceHttp.bind(DecisionCore.buildFromDocument(DESCRIPTOR, Files.readString(GOLDEN.resolve("ir.json")),
                CoreConfig.strict(), cl, null), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
        if (detail != null) detail.stop(0);
        if (document != null) document.stop(0);
    }

    // ---------------- the request and the answer ----------------

    /** case5: a marker in the request stands where no rule decides, and the rules read it (uchar's pin: onbekend,
     *  25,00). */
    @Test
    void a_marker_in_the_request_stands_and_the_rules_read_it() throws Exception {
        JsonNode answer = ok(post(service, "", vondst("\"attributes\":{\"taxatie\":{\"marker\":\"onbekend\"}}")));
        assertEquals(marker("onbekend"), attribute(answer, "V1", "taxatie"), answer.toString());
        assertNumber("25.00", attribute(answer, "V1", "vindloon"), answer.toString());
    }

    /** The same request on a service started from the golden's {@code ir.json}, whose model declares the attribute as
     *  uchar's sum type. */
    @Test
    void a_marker_in_the_request_reads_the_same_on_a_service_from_the_document() throws Exception {
        JsonNode answer = ok(post(document, "", vondst("\"attributes\":{\"taxatie\":{\"marker\":\"onbekend\"}}")));
        assertEquals(marker("onbekend"), attribute(answer, "V1", "taxatie"), answer.toString());
        assertNumber("25.00", attribute(answer, "V1", "vindloon"), answer.toString());
    }

    /** case2: the marker a rule writes displaces a value of the request (uchar's pin: onbekend, 25,00). */
    @Test
    void a_marker_a_rule_writes_displaces_a_value_of_the_request() throws Exception {
        JsonNode answer = ok(post(service, "", vondst(
                "\"attributes\":{\"taxatie\":40.00},\"characteristics\":{\"onleesbaar\":true}")));
        assertEquals(marker("onbekend"), attribute(answer, "V1", "taxatie"), answer.toString());
        assertNumber("25.00", attribute(answer, "V1", "vindloon"), answer.toString());
    }

    /** A plain value of the request is the value {@code waarde(40.00)}: it stands where no rule decides, and the rule
     *  that reads the value computes with it. */
    @Test
    void a_plain_value_of_the_request_is_the_value_of_the_attribute() throws Exception {
        JsonNode answer = ok(post(service, "", vondst("\"attributes\":{\"taxatie\":40.00}")));
        assertNumber("40.00", attribute(answer, "V1", "taxatie"), answer.toString());
        assertNumber("4.00", attribute(answer, "V1", "vindloon"), answer.toString());
    }

    /** case1 and case3: the answer gives the value as its payload and a marker as {@code {"marker": …}} (uchar's pins:
     *  waarde(300.00) and 30,00; niet van toepassing and 0,00). */
    @Test
    void the_answer_gives_a_value_as_its_payload_and_a_marker_as_a_marker() throws Exception {
        JsonNode value = ok(post(service, "", vondst("\"attributes\":{\"schatting\":300.00}")));
        assertNumber("300.00", attribute(value, "V1", "taxatie"), value.toString());
        assertNumber("30.00", attribute(value, "V1", "vindloon"), value.toString());
        JsonNode none = ok(post(service, "", vondst("\"characteristics\":{\"drijfhout\":true}")));
        assertEquals(marker("niet van toepassing"), attribute(none, "V1", "taxatie"), none.toString());
        assertNumber("0.00", attribute(none, "V1", "vindloon"), none.toString());
    }

    /** case10: a timeline with markers, from the request and from the rules (uchar's pin: onbekend in January and
     *  February, waarde(10) from March to May, niet van toepassing from June; opslagtoeslag 7 in January and
     *  February). */
    @Test
    void a_timeline_with_markers_is_answered_per_segment() throws Exception {
        JsonNode answer = ok(post(service, "", vondst("""
                "attributes":{"opslagkosten":{"timeline":[{"from":"2025-01-01","to":"2026-01-01","value":10}]}},
                "characteristics":{"verkocht":{"periods":[{"from":"2025-06-01","to":"2026-01-01"}]}}""")));
        assertEquals(List.of("2025-01-01..2025-03-01=onbekend", "2025-03-01..2025-06-01=10",
                        "2025-06-01..2026-01-01=niet van toepassing"),
                canonical(attribute(answer, "V1", "opslagkosten")), answer.toString());
        assertEquals(List.of("2025-01-01..2025-03-01=7"), canonical(attribute(answer, "V1", "opslagtoeslag")),
                answer.toString());
    }

    /** A marker as the value of a segment of the request stands on its days where no rule decides them. */
    @Test
    void a_marker_as_the_value_of_a_segment_stands_on_its_days() throws Exception {
        JsonNode answer = ok(post(service, "", vondst("""
                "attributes":{"opslagkosten":{"timeline":[
                  {"from":"2025-03-01","to":"2025-05-01","value":{"marker":"niet van toepassing"}}]}}""")));
        assertEquals(List.of("2025-01-01..2025-03-01=onbekend", "2025-03-01..2025-05-01=niet van toepassing"),
                canonical(attribute(answer, "V1", "opslagkosten")), answer.toString());
    }

    /** The fixpoint of the detail gives a marker in the form of the answer. */
    @Test
    void the_fixpoint_gives_a_marker_as_a_marker() throws Exception {
        JsonNode answer = ok(post(detail, "fixpoint=true", vondst("\"characteristics\":{\"onleesbaar\":true}")));
        JsonNode row = null;
        for (JsonNode o : answer.at("/fixpoint/objects"))
            for (JsonNode a : o.path("values"))
                if ("taxatie".equals(a.path("attribute").asText())) row = a;
        assertTrue(row != null, answer.toString());
        assertEquals(marker("onbekend"), row.get("value"), answer.toString());
    }

    // ---------------- what the request does not take: a 400 naming the marker ----------------

    @Test
    void a_marker_the_attribute_does_not_declare_is_400_unknown_marker() throws Exception {
        JsonNode v = violation(post(service, "", vondst("\"attributes\":{\"taxatie\":{\"marker\":\"misschien\"}}")));
        assertEquals("UNKNOWN_MARKER", v.get("kind").asText(), v.toString());
        assertEquals("instances[0].attributes.taxatie", v.get("field").asText(), v.toString());
        assertTrue(v.get("error").asText().contains("'misschien'")
                && v.get("error").asText().contains("onbekend, niet van toepassing"), v.toString());
    }

    @Test
    void a_marker_the_attribute_does_not_declare_in_a_segment_is_400_unknown_marker() throws Exception {
        JsonNode v = violation(post(service, "", vondst("""
                "attributes":{"opslagkosten":{"timeline":[
                  {"from":"2025-03-01","to":"2025-05-01","value":{"marker":"misschien"}}]}}""")));
        assertEquals("UNKNOWN_MARKER", v.get("kind").asText(), v.toString());
        assertEquals("instances[0].attributes.opslagkosten.timeline[0].value", v.get("field").asText(), v.toString());
    }

    @Test
    void a_marker_on_an_attribute_without_markers_is_400_wrong_type() throws Exception {
        JsonNode v = violation(post(service, "", vondst("\"attributes\":{\"schatting\":{\"marker\":\"onbekend\"}}")));
        assertEquals("WRONG_TYPE", v.get("kind").asText(), v.toString());
        assertEquals("instances[0].attributes.schatting", v.get("field").asText(), v.toString());
        assertTrue(v.get("error").asText().contains("'schatting'")
                && v.get("error").asText().contains("geen markers"), v.toString());
    }

    @Test
    void a_marker_in_a_segment_of_a_timeline_without_markers_is_400_wrong_type() throws Exception {
        JsonNode v = violation(post(service, "", vondst("""
                "attributes":{"opslagtoeslag":{"timeline":[
                  {"from":"2025-01-01","to":"2025-02-01","value":{"marker":"onbekend"}}]}}""")));
        assertEquals("WRONG_TYPE", v.get("kind").asText(), v.toString());
        assertEquals("instances[0].attributes.opslagtoeslag.timeline[0].value", v.get("field").asText(), v.toString());
        assertTrue(v.get("error").asText().contains("'opslagtoeslag'")
                && v.get("error").asText().contains("geen markers"), v.toString());
    }

    @Test
    void a_marker_object_with_another_key_is_400_malformed() throws Exception {
        JsonNode v = violation(post(service, "",
                vondst("\"attributes\":{\"taxatie\":{\"marker\":\"onbekend\",\"value\":40.00}}")));
        assertEquals("MALFORMED", v.get("kind").asText(), v.toString());
        assertEquals("instances[0].attributes.taxatie", v.get("field").asText(), v.toString());
    }

    // ---------------- the explanation ----------------

    /** case1: the value is its payload in the explanation, never the text of uchar's constructor value. */
    @Test
    void the_explanation_writes_a_value_as_its_payload() throws Exception {
        List<String> explanation = explanation(post(service, "explanation=true",
                vondst("\"attributes\":{\"schatting\":300.00}")));
        assertContains(explanation, "V1 heeft als taxatie 300,00 volgens de regel 'taxatie uit schatting'");
        assertContains(explanation, "V1 heeft als vindloon 30,00 volgens de regel 'vindloon naar taxatie': zijn taxatie"
                + " gedeeld door 10 rekenkundig afgerond op 2 decimalen, met zijn taxatie (300,00)");
        assertContains(explanation, "omdat zijn taxatie (300,00) een waarde heeft");
        assertFalse(String.join("\n", explanation).contains("waarde("), String.join("\n", explanation));
    }

    /** case3: a marker by its name, as the value a rule wrote and as the value a condition read. */
    @Test
    void the_explanation_names_a_marker_by_its_name() throws Exception {
        List<String> explanation = explanation(post(service, "explanation=true",
                vondst("\"characteristics\":{\"drijfhout\":true}")));
        assertContains(explanation, "V1 heeft als taxatie niet van toepassing volgens de regel 'taxatie niet van"
                + " toepassing'");
        assertContains(explanation, "V1 heeft als vindloon 0,00 volgens de regel 'geen vindloon': 0,00, omdat zijn"
                + " taxatie (niet van toepassing) is gelijk aan niet van toepassing; de regel 'vindloon naar taxatie'");
        assertContains(explanation, "de regel 'vindloon naar taxatie' gold niet, want niet geldt: zijn taxatie (niet van"
                + " toepassing) een waarde heeft");
    }

    /** case4: an empty attribute with markers is leeg in the explanation, so that it reads apart from the marker
     *  onbekend. */
    @Test
    void the_explanation_tells_an_empty_value_from_the_marker_onbekend() throws Exception {
        List<String> explanation = explanation(post(service, "explanation=true", vondst("\"attributes\":{}")));
        assertContains(explanation, "de regel 'forfaitair vindloon' gold niet, want niet geldt: zijn taxatie (leeg) is"
                + " gelijk aan onbekend");
        assertFalse(String.join("\n", explanation).contains("(onbekend) is gelijk aan onbekend"),
                String.join("\n", explanation));
    }

    /** case10: the markers per period, and the value of the request between them. */
    @Test
    void the_explanation_names_a_marker_per_period() throws Exception {
        List<String> explanation = explanation(post(service, "explanation=true", vondst("""
                "attributes":{"opslagkosten":{"timeline":[{"from":"2025-01-01","to":"2026-01-01","value":10}]}},
                "characteristics":{"verkocht":{"periods":[{"from":"2025-06-01","to":"2026-01-01"}]}}""")));
        assertContains(explanation, "V1 heeft als opslagkosten onbekend van 01-01-2025 tot 01-03-2025 volgens de regel"
                + " 'opslag onbekend in de winter'");
        assertContains(explanation, "niet van toepassing van 01-06-2025 tot 01-01-2026 volgens de regel 'opslag niet van"
                + " toepassing na verkoop'");
        assertContains(explanation, "de invoerwaarde 10 van 01-03-2025 tot 01-06-2025 bleef staan");
        assertFalse(String.join("\n", explanation).contains("waarde("), String.join("\n", explanation));
    }

    // ---------------- helpers ----------------

    private static String vondst(String members) {
        return "{\"instances\":[{\"type\":\"Vondst\",\"id\":\"V1\"" + (members.isBlank() ? "" : "," + members) + "}]}";
    }

    private static JsonNode marker(String name) throws Exception {
        return tree("{\"marker\":\"" + name + "\"}");
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

    /** The timeline {@code actual} of an answer in its canonical form (uchar's RUNTIME-SEMANTICS ch. 3.1): its segments
     *  in order, two adjacent ones with equal values joined, each as {@code from..to=value}, a number without trailing
     *  zeros and a marker by its name. */
    private static List<String> canonical(JsonNode actual) {
        assertTrue(actual != null && actual.isObject() && actual.has("timeline"), String.valueOf(actual));
        List<String[]> joined = new ArrayList<>();
        for (JsonNode s : actual.get("timeline")) {
            String from = s.has("from") ? s.get("from").asText() : "", to = s.has("to") ? s.get("to").asText() : "";
            JsonNode v = s.get("value");
            String value = v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString()
                    : v.isObject() && v.size() == 1 && v.get("marker") != null ? v.get("marker").asText() : v.toString();
            String[] last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
            if (last != null && last[1].equals(from) && !from.isEmpty() && last[2].equals(value)) last[1] = to;
            else joined.add(new String[]{from, to, value});
        }
        return joined.stream().map(s -> s[0] + ".." + s[1] + "=" + s[2]).toList();
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
