package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A number in the answer stands in exactly the decimals of its type, as uchar writes a number in text (RUNTIME-SEMANTICS
 * ch. 1.2, "Text coercion"), whatever calculation gave the value and whatever scale the request wrote: in
 * {@code instances}, in a segment of a timeline, in a cell, and as the text of a value in {@code fixpoint}. A number of
 * the unbounded form keeps its own scale. The numbers are compared with {@link BigDecimal#equals}, which compares the
 * scale. Own material: a find whose yield is shared.
 */
class NumberScaleHttpTest {

    private static final String SOURCE = """
        Dimensie het seizoen, bestaande uit de seizoenen (na het attribuut met voorzetsel in):
            1. voorjaar
            2. najaar

        Parameter de havenheffing : Numeriek (geheel getal) voor elke maand;

        Objecttype de Vondst (mv: Vondsten)
            de opbrengst Numeriek (geheel getal);
            de vondstwaarde Numeriek (geheel getal);
            de taxatie Numeriek (getal met 2 decimalen);
            het aantal deelnemers Numeriek (geheel getal);
            het bergloon Numeriek (getal met 2 decimalen);
            het kwart Numeriek (getal met 2 decimalen);
            de taxatiewaarde Numeriek (geheel getal);
            het ruwe aandeel Numeriek (getal);
            het gewicht Numeriek (getal met 2 decimalen) gedimensioneerd met seizoen;
            de maandheffing Numeriek (getal met 2 decimalen) voor elke maand;

        Regel bergloon
            geldig altijd
                Het bergloon van een Vondst moet berekend worden als zijn vondstwaarde maal 0,25.

        Regel kwart
            geldig altijd
                Het kwart van een Vondst moet berekend worden als zijn opbrengst gedeeld door 4.

        Regel taxatiewaarde
            geldig altijd
                De taxatiewaarde van een Vondst moet berekend worden als zijn taxatie maal 100.

        Regel ruw aandeel
            geldig altijd
                Het ruwe aandeel van een Vondst moet berekend worden als zijn opbrengst gedeeld door zijn aantal deelnemers.

        Regel najaarsgewicht
            geldig altijd
                Het gewicht in najaar van een Vondst moet berekend worden als zijn opbrengst gedeeld door 4.

        Regel maandheffing
            geldig altijd
                De maandheffing van een Vondst moet berekend worden als de havenheffing gedeeld door 4.
        """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"schaal","version":"1"},
          "entrypoints": [ { "name":"vondst","path":"vondst",
            "scope": {"ruleNames": ["bergloon", "kwart", "taxatiewaarde", "ruw aandeel", "najaarsgewicht",
                                    "maandheffing"]},
            "input": ["Vondst"],
            "output": ["Vondst.opbrengst", "Vondst.vondstwaarde", "Vondst.taxatie", "Vondst.bergloon", "Vondst.kwart",
                       "Vondst.taxatiewaarde", "Vondst.ruwe aandeel", "Vondst.gewicht", "Vondst.maandheffing"] } ],
          "parameters": {} }""";

    /** An opbrengst of 100 written as 100.0, a vondstwaarde of 240, a taxatie of 240 written without decimals, a
     *  voorjaar cell of 10 written without decimals; a levy of 100 in January 2024, open before and after. */
    private static final String REQUEST = """
        {"parameters":{"havenheffing":{"timeline":[{"from":"2024-01-01","to":"2024-02-01","value":100}]}},
         "instances":[{"type":"Vondst","id":"V1","attributes":{
            "opbrengst":100.0, "vondstwaarde":240, "taxatie":240, "aantal deelnemers":4,
            "gewicht":{"cells":[{"labels":["voorjaar"],"value":10}]}}}]}""";

    private static ServiceHttp service;
    private static ServiceHttp detail;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Map<String, String> source = Map.of("schaal.rgs", SOURCE);
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

    /** The product with a literal (240 × 0,25) and the quotient by a literal (100 / 4) into a type of 2 decimals both
     *  stand as {@code 60.00} and {@code 25.00}, and the input of that type written without decimals as
     *  {@code 240.00}. */
    @Test
    void a_number_of_two_decimals_has_two_decimals_whatever_calculation_gave_it() throws Exception {
        JsonNode answer = ok(post(service, ""));
        assertScale("60.00", attribute(answer, "bergloon"), answer);
        assertScale("25.00", attribute(answer, "kwart"), answer);
        assertScale("240.00", attribute(answer, "taxatie"), answer);
    }

    /** A whole type has no decimals: the input written as 100.0 stands as {@code 100}, the product 240,00 × 100 as
     *  {@code 24000}. */
    @Test
    void a_whole_number_has_no_decimals() throws Exception {
        JsonNode answer = ok(post(service, ""));
        assertScale("100", attribute(answer, "opbrengst"), answer);
        assertScale("24000", attribute(answer, "taxatiewaarde"), answer);
    }

    /** The unbounded form keeps the scale of its value: 100 / 4 is {@code 25}. */
    @Test
    void a_number_of_the_unbounded_form_keeps_its_scale() throws Exception {
        JsonNode answer = ok(post(service, ""));
        assertScale("25", attribute(answer, "ruwe aandeel"), answer);
    }

    /** Every segment of a timeline of 2 decimals has 2 decimals, the segments of 0 where the levy is leeg included. */
    @Test
    void a_segment_of_a_timeline_has_the_decimals_of_its_type() throws Exception {
        JsonNode answer = ok(post(service, ""));
        JsonNode timeline = attribute(answer, "maandheffing").get("timeline");
        assertEquals(3, timeline.size(), answer.toString());
        assertScale("0.00", timeline.get(0).get("value"), answer);
        assertScale("25.00", timeline.get(1).get("value"), answer);
        assertScale("0.00", timeline.get(2).get("value"), answer);
    }

    /** A cell of 2 decimals has 2 decimals: the one a rule wrote (100 / 4) and the one the request wrote as 10. */
    @Test
    void a_cell_has_the_decimals_of_its_type() throws Exception {
        JsonNode answer = ok(post(service, ""));
        JsonNode cells = attribute(answer, "gewicht").get("cells");
        assertEquals("[\"voorjaar\"]", cells.get(0).get("labels").toString(), answer.toString());
        assertScale("10.00", cells.get(0).get("value"), answer);
        assertEquals("[\"najaar\"]", cells.get(1).get("labels").toString(), answer.toString());
        assertScale("25.00", cells.get(1).get("value"), answer);
    }

    /** The fixpoint writes a number as its text in the decimals of its type too: a derived value, an input value, a
     *  segment and a cell; the unbounded form with its own scale. */
    @Test
    void the_fixpoint_writes_a_number_in_the_decimals_of_its_type() throws Exception {
        JsonNode answer = ok(post(detail, "fixpoint=true"));
        List<String> rows = new ArrayList<>();
        for (JsonNode o : answer.at("/fixpoint/objects"))
            for (JsonNode a : o.path("values")) {
                String value = a.has("timeline") ? a.get("timeline").findValuesAsText("value").toString()
                        : a.get("value").asText();
                rows.add(a.get("attribute").asText() + (a.has("labels") ? " " + a.get("labels") : "") + " = "
                        + value + " " + a.get("origin").asText());
            }
        assertEquals(List.of(
                "opbrengst = 100 input",
                "vondstwaarde = 240 input",
                "taxatie = 240.00 input",
                "aantal deelnemers = 4 input",
                "bergloon = 60.00 derived",
                "kwart = 25.00 derived",
                "taxatiewaarde = 24000 derived",
                "ruwe aandeel = 25 derived",
                "gewicht [\"voorjaar\"] = 10.00 input",
                "gewicht [\"najaar\"] = 25.00 derived",
                "maandheffing = [0.00, 25.00, 0.00] derived"), rows, answer.toString());
    }

    /** An input with more decimals than its type is uchar's EF-004 before any session, so no value that does not fit
     *  reaches the answer or the fixpoint, which would fail on it: a 422, also when the detail is asked. */
    @Test
    void an_input_that_does_not_fit_is_ef004_and_reaches_no_fixpoint() throws Exception {
        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + detail.port()
                                + "/vondst?fixpoint=true"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(REQUEST.replace("\"taxatie\":240", "\"taxatie\":240.001"),
                                StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(422, r.statusCode(), r.body());
        assertEquals("EF-004", ServiceHttp.MAPPER.readTree(r.body()).at("/faults/0/code").asText(), r.body());
    }

    // ---------------- helpers ----------------

    /** {@code actual} is the JSON number {@code expected} with its scale: {@link BigDecimal#equals}. */
    private static void assertScale(String expected, JsonNode actual, JsonNode answer) {
        assertTrue(actual != null && actual.isNumber(), answer.toString());
        assertEquals(new BigDecimal(expected), actual.decimalValue(), answer.toString());
    }

    private static JsonNode attribute(JsonNode answer, String attribute) {
        JsonNode a = answer.at("/instances/0/attributes").get(attribute);
        assertTrue(a != null, answer.toString());
        return a;
    }

    private static JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return ServiceHttp.MAPPER.readTree(r.body());
    }

    private static HttpResponse<String> post(ServiceHttp to, String query) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + "/vondst"
                                + (query.isEmpty() ? "" : "?" + query)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(REQUEST, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
