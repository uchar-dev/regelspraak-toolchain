package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.Violation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timelines over all time through the service (uchar's RUNTIME-SEMANTICS ch. 3.1, 3.4, 3.6): a time-dependent parameter
 * comes in as its timeline, which uchar's Drools branch reads as a {@code List<Period>} global; a segment may leave out
 * {@code from} or {@code to}, an open bound, in the request and in the answer, as uchar's case format writes it; a
 * timeline attribute of {@code output} is answered as its segments. The faults of a timeline each have their kind and
 * their HTTP status: a total over an open segment that is not 0 (EF-036), periods of a characteristic with an open
 * bound (EF-023), and a segment of the unbounded form whose value does not terminate, a limit of uchar's branch
 * (DROOLS-312). Own material: a harbour levy per month times a number of boats.
 */
class TimelineHttpTest {

    private static final String SOURCE = """
        Parameter de havenheffing : Numeriek (getal met 2 decimalen) voor elke maand;
        Parameter de havendrempel : Numeriek (geheel getal);

        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal boten Numeriek (geheel getal);
            de bootheffing Numeriek (getal met 2 decimalen) voor elke maand;
            de ruwe bootheffing Numeriek (getal) voor elke maand;
            de totale bootheffing Numeriek (getal met 2 decimalen);
            is havenplichtig kenmerk (bijvoeglijk) voor elke dag;
            de havendagen Numeriek (niet-negatief geheel getal) voor elke maand;

        Regel bootheffing
            geldig altijd
                De bootheffing van een Jutter moet berekend worden als de havenheffing maal zijn aantal boten.

        Regel ruwe bootheffing
            geldig altijd
                De ruwe bootheffing van een Jutter moet berekend worden als de havenheffing gedeeld door zijn aantal boten.

        Regel totale bootheffing
            geldig altijd
                De totale bootheffing van een Jutter moet berekend worden als (het totaal van zijn bootheffing) rekenkundig afgerond op 2 decimalen.

        Regel havenplicht
            geldig altijd
                Een Jutter is havenplichtig vanaf 01-01-2024.

        Regel havendagen
            geldig altijd
                De havendagen van een Jutter moet berekend worden als het aantal dagen in de maand dat hij havenplichtig is.
        """;

    private static final String ENTRY_POINTS = """
            { "name":"heffing","path":"heffing",
              "scope": {"ruleNames": ["bootheffing"]},
              "input": ["Jutter"], "output": ["Jutter.bootheffing"] },
            { "name":"totaal","path":"totaal",
              "scope": {"ruleNames": ["bootheffing", "totale bootheffing"]},
              "input": ["Jutter"], "output": ["Jutter.bootheffing", "Jutter.totale bootheffing"] },
            { "name":"ruw","path":"ruw",
              "scope": {"ruleNames": ["ruwe bootheffing"]},
              "input": ["Jutter"], "output": ["Jutter.ruwe bootheffing"] },
            { "name":"haven","path":"haven",
              "scope": {"ruleNames": ["havenplicht", "havendagen"]},
              "input": ["Jutter"], "output": ["Jutter.havendagen"] }""";

    private static final String DESCRIPTOR = """
        { "service": {"name":"haven","version":"1"},
          "entrypoints": [ %s ],
          "parameters": {} }""".formatted(ENTRY_POINTS);

    /** The levy 2,50 until 2024 and 3,00 from 2024 on, both segments open at their far end. */
    private static final String OPEN_LEVY = """
        {"timeline":[{"to":"2024-01-01","value":2.50},{"from":"2024-01-01","value":3.00}]}""";

    private static final String CLOSED_LEVY = """
        {"timeline":[{"from":"2023-12-01","to":"2024-01-01","value":2.50},
                     {"from":"2024-01-01","to":"2024-03-01","value":3.00}]}""";

    private static ServiceHttp service;
    private static ServiceHttp detail;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        service = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR, Map.of("haven.rgs", SOURCE), CoreConfig.strict(), cl),
                "127.0.0.1", 0).start();
        detail = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR, Map.of("haven.rgs", SOURCE),
                CoreConfig.strict().withDetail(), cl), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
        if (detail != null) detail.stop(0);
    }

    // ---------------- the parameter as its timeline, open bounds in the request and the answer ----------------

    @Test
    void a_time_dependent_parameter_takes_its_timeline_and_an_open_bound_stays_open_in_the_answer() throws Exception {
        HttpResponse<String> r = post(service, "heffing", "", request(OPEN_LEVY, 2));
        assertEquals(200, r.statusCode(), r.body());
        assertTimeline("""
                {"timeline":[{"to":"2024-01-01","value":5.00},{"from":"2024-01-01","value":6.00}]}""",
                attribute(tree(r.body()), "J1", "bootheffing"), r.body());
    }

    /** A gap in the parameter's timeline reads leeg, and the product over all time gives 0 there (ch. 3.4): the
     *  answer has the segments of 0 open before and after, and the total counts them as 0 (ch. 3.6). */
    @Test
    void a_closed_parameter_timeline_gives_open_zeros_and_a_total() throws Exception {
        HttpResponse<String> r = post(service, "totaal", "", request(CLOSED_LEVY, 2));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode answer = tree(r.body());
        assertTimeline("""
                {"timeline":[{"to":"2023-12-01","value":0},{"from":"2023-12-01","to":"2024-01-01","value":5.00},
                             {"from":"2024-01-01","to":"2024-03-01","value":6.00},{"from":"2024-03-01","value":0}]}""",
                attribute(answer, "J1", "bootheffing"), r.body());
        assertEquals(0, new java.math.BigDecimal("17.00").compareTo(
                attribute(answer, "J1", "totale bootheffing").decimalValue()), r.body());
    }

    @Test
    void the_fixpoint_writes_an_open_bound_as_no_bound() throws Exception {
        HttpResponse<String> r = post(detail, "heffing", "fixpoint=true", request(OPEN_LEVY, 2));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode row = null;
        for (JsonNode o : tree(r.body()).at("/fixpoint/objects"))
            for (JsonNode a : o.path("values"))
                if ("bootheffing".equals(a.path("attribute").asText())) row = a;
        assertTrue(row != null, r.body());
        JsonNode timeline = row.get("timeline");
        assertEquals(2, timeline.size(), r.body());
        assertFalse(timeline.get(0).has("from"), r.body());
        assertEquals("2024-01-01", timeline.get(0).get("to").asText(), r.body());
        assertEquals("2024-01-01", timeline.get(1).get("from").asText(), r.body());
        assertFalse(timeline.get(1).has("to"), r.body());
    }

    @Test
    void a_descriptor_default_of_a_time_dependent_parameter_is_its_timeline() throws Exception {
        String descriptor = """
            { "service": {"name":"haven","version":"1"},
              "entrypoints": [ %s ],
              "parameters": {"havenheffing": %s} }""".formatted(ENTRY_POINTS, OPEN_LEVY);
        ServiceHttp withDefault = ServiceHttp.bind(DecisionCore.assemble(descriptor, Map.of("haven.rgs", SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            HttpResponse<String> r = post(withDefault, "heffing", "", """
                {"instances":[{"type":"Jutter","id":"J1","attributes":{"aantal boten":1}}]}""");
            assertEquals(200, r.statusCode(), r.body());
            assertTimeline(OPEN_LEVY, attribute(tree(r.body()), "J1", "bootheffing"), r.body());
        } finally {
            withDefault.stop(0);
        }
    }

    // ---------------- what the parameter does not take: a 400 naming the field ----------------

    @Test
    void a_scalar_on_a_time_dependent_parameter_is_400_wrong_type() throws Exception {
        JsonNode v = violation(post(service, "heffing", "", request("2.50", 2)));
        assertEquals("WRONG_TYPE", v.get("kind").asText(), v.toString());
        assertEquals("parameters.havenheffing", v.get("field").asText(), v.toString());
        assertTrue(v.get("error").asText().contains("tijdsafhankelijk"), v.toString());
    }

    @Test
    void a_timeline_on_a_parameter_that_is_not_time_dependent_is_400_wrong_type() throws Exception {
        JsonNode v = violation(post(service, "heffing", "", """
            {"parameters":{"havenheffing":%s,"havendrempel":{"timeline":[{"value":3}]}},
             "instances":[{"type":"Jutter","id":"J1","attributes":{"aantal boten":1}}]}""".formatted(OPEN_LEVY)));
        assertEquals("WRONG_TYPE", v.get("kind").asText(), v.toString());
        assertEquals("parameters.havendrempel", v.get("field").asText(), v.toString());
    }

    @Test
    void a_segment_open_where_another_follows_or_off_the_grid_is_400() throws Exception {
        JsonNode open = violation(post(service, "heffing", "", request("""
            {"timeline":[{"to":"2024-01-01","value":2.50},{"value":3.00}]}""", 1)));
        assertEquals("MALFORMED", open.get("kind").asText(), open.toString());
        assertEquals("parameters.havenheffing.timeline[1]", open.get("field").asText(), open.toString());
        JsonNode reversed = violation(post(service, "heffing", "", request("""
            {"timeline":[{"from":"2024-03-01","to":"2024-01-01","value":2.50}]}""", 1)));
        assertEquals("MALFORMED", reversed.get("kind").asText(), reversed.toString());
        JsonNode offGrid = violation(post(service, "heffing", "", request("""
            {"timeline":[{"from":"2024-01-15","value":2.50}]}""", 1)));
        assertEquals("WRONG_TYPE", offGrid.get("kind").asText(), offGrid.toString());
        assertEquals("parameters.havenheffing.timeline[0].from", offGrid.get("field").asText(), offGrid.toString());
    }

    /** A segment value its declaration does not hold is uchar's EF-004, a 422, as for a scalar parameter. */
    @Test
    void a_segment_value_with_too_many_decimals_is_422_input_precision_fault() throws Exception {
        HttpResponse<String> r = post(service, "heffing", "", request("""
            {"timeline":[{"value":2.505}]}""", 1));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = tree(r.body());
        assertEquals("INPUT_PRECISION_FAULT", body.at("/faults/0/kind").asText(), r.body());
    }

    // ---------------- the faults of a timeline: each with its kind and status ----------------

    @Test
    void a_total_over_an_open_segment_that_is_not_zero_is_422_open_segment_fault() throws Exception {
        HttpResponse<String> r = post(service, "totaal", "", request(OPEN_LEVY, 2));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = tree(r.body());
        assertEquals("EVALUATION_FAULT", body.get("status").asText(), r.body());
        assertEquals(1, body.get("faults").size(), r.body());
        assertEquals("OPEN_SEGMENT_FAULT", body.at("/faults/0/kind").asText(), r.body());
        String sentence = body.at("/faults/0/error").asText();
        assertTrue(sentence.contains("bootheffing") && sentence.contains("J1"), sentence);
    }

    @Test
    void periods_of_a_characteristic_with_an_open_bound_are_422_characteristic_periods_fault() throws Exception {
        HttpResponse<String> r = post(service, "haven", "", """
            {"asOfDate":"2024-06-15","instances":[{"type":"Jutter","id":"J1"}]}""");
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = tree(r.body());
        assertEquals("CHARACTERISTIC_PERIODS_FAULT", body.at("/faults/0/kind").asText(), r.body());
        String sentence = body.at("/faults/0/error").asText();
        assertTrue(sentence.contains("havenplichtig") && sentence.contains("J1"), sentence);
    }

    /** 2,50 over three boats is 5/6 on every day before 2024, a value of the unbounded form that does not terminate,
     *  which a segment of uchar's Drools branch cannot hold: no fault of the rules, but a limit of the branch
     *  (DROOLS-312), answered with its code and uchar's sentence, and no value. */
    @Test
    void a_segment_that_does_not_terminate_is_422_limit_with_its_code() throws Exception {
        HttpResponse<String> r = post(service, "ruw", "", request(OPEN_LEVY, 3));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = tree(r.body());
        assertEquals("LIMIT", body.get("status").asText(), r.body());
        assertEquals("DROOLS-312", body.get("code").asText(), r.body());
        assertTrue(body.get("reason").asText().contains("ruwe bootheffing"), r.body());
        assertFalse(body.has("faults"), r.body());
        assertFalse(r.body().contains("Exception") || r.body().contains("\tat "), r.body());
        HttpResponse<String> terminating = post(service, "ruw", "", request(OPEN_LEVY, 2));
        assertEquals(200, terminating.statusCode(), terminating.body());
    }

    @Test
    void a_limit_with_the_detail_flags_gives_the_session() throws Exception {
        HttpResponse<String> r = post(detail, "ruw", "trace=true&coverage=true&fixpoint=true", request(OPEN_LEVY, 3));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = tree(r.body());
        assertEquals("LIMIT", body.get("status").asText(), r.body());
        assertTrue(body.get("trace").size() > 0, r.body());
        assertTrue(body.has("coverage") && body.has("fixpoint"), r.body());
    }

    // ---------------- a timeline characteristic is no output ----------------

    @Test
    void a_time_dependent_characteristic_in_output_refuses_the_start() {
        String descriptor = """
            { "service": {"name":"haven","version":"1"},
              "entrypoints": [ { "name":"plicht","path":"plicht",
                "scope": {"ruleNames": ["havenplicht"]},
                "input": ["Jutter"], "output": ["Jutter:havenplichtig"] } ],
              "parameters": {} }""";
        ServiceError e = assertThrows(ServiceError.class, () -> DecisionCore.assemble(descriptor,
                Map.of("haven.rgs", SOURCE), CoreConfig.strict(), Thread.currentThread().getContextClassLoader()));
        Violation v = e.violations().get(0);
        assertEquals(Violation.Kind.UNSUPPORTED, v.kind(), e.violations().toString());
        assertEquals("entrypoints[0].output[0]", v.field(), e.violations().toString());
    }

    // ---------------- helpers ----------------

    private static String request(String levy, int boats) {
        return """
            {"parameters":{"havenheffing":%s},
             "instances":[{"type":"Jutter","id":"J1","attributes":{"aantal boten":%d}}]}""".formatted(levy, boats);
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

    private static HttpResponse<String> post(ServiceHttp to, String path, String query, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + "/" + path
                                + (query.isEmpty() ? "" : "?" + query)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode tree(String json) throws Exception {
        return ServiceHttp.MAPPER.readTree(json);
    }

    /** {@code actual} is the timeline {@code expected}: the same segments, each with the same bounds, an open one
     *  absent on both, and a number of the same value whatever its scale. */
    private static void assertTimeline(String expected, JsonNode actual, String context) throws Exception {
        JsonNode want = tree(expected).get("timeline");
        assertTrue(actual != null && actual.isObject() && actual.size() == 1 && actual.has("timeline"), context);
        JsonNode got = actual.get("timeline");
        assertEquals(want.size(), got.size(), context);
        for (int k = 0; k < want.size(); k++) {
            for (String bound : List.of("from", "to")) {
                assertEquals(want.get(k).has(bound), got.get(k).has(bound), context);
                if (want.get(k).has(bound)) assertEquals(want.get(k).get(bound), got.get(k).get(bound), context);
            }
            assertEquals(0, want.get(k).get("value").decimalValue().compareTo(got.get(k).get("value").decimalValue()),
                    context);
            assertEquals(List.of("from", "to", "value").stream().filter(want.get(k)::has).toList(),
                    names(got.get(k)), context);
        }
    }

    private static List<String> names(JsonNode n) {
        List<String> sink = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(sink::add);
        return sink;
    }
}
