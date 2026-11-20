package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A marker as opgave through the service, on uchar's golden jutterswet-opgave-afwezigheidsmarkers: an attribute X with
 * absence markers and the opgave marker ({@code (of onbekend of niet van toepassing) (mag opgegeven worden)}, uchar's
 * grammar/DIVERGENCES.md §7 and RUNTIME-SEMANTICS ch. 9.5). {@code opgegeven X} and {@code berekende X} carry X's
 * markers, so a request gives {@code opgegeven X} a value or {@code {"marker": <naam>}}, also as the value of a segment,
 * and the answer gives X, {@code berekende X} and {@code opgegeven X} in that form. The opgave decides above the
 * computation, a marker included; an opgave that differs from a filled computation attributes
 * {@code afwijkende opgave X} and deposits {@code controle opgave X}, also where one of the two is a marker, and two
 * equal markers do not differ. The explanation names a marker by its name in the sentence of the default that decides
 * and in that of the deviation. The cases are those of the golden, with uchar's pinned values.
 */
class MarkerAsOpgaveHttpTest {

    private static final Path GOLDENS = TokaSource.REPO.resolve("uchar/corpus/golden");
    private static final Path GOLDEN = GOLDENS.resolve("jutterswet-opgave-afwezigheidsmarkers");

    private static final String DESCRIPTOR = """
        { "service": {"name":"opgave","version":"1"},
          "entrypoints": [ { "name":"opgave","path":"opgave",
            "scope": {"ruleNames": ["teruggave na de claimdatum", "geen teruggave van drijfhout",
                                    "teruggave bij een onleesbaar etiket", "afhaal na de claimdatum",
                                    "opslag onbekend in de winter", "opslag in het voorjaar",
                                    "opgave boven berekening teruggavedatum", "afwijking opgave teruggavedatum",
                                    "controle opgave teruggavedatum", "opgave boven berekening afhaaldatum",
                                    "afwijking opgave afhaaldatum", "controle opgave afhaaldatum",
                                    "bergloon naar gewicht en schade", "opgave boven berekening bergloon",
                                    "afwijking opgave bergloon", "controle opgave bergloon",
                                    "opgave boven berekening opslagkosten"]},
            "input": [],
            "output": ["Vondst.teruggavedatum", "Vondst.berekende teruggavedatum", "Vondst.opgegeven teruggavedatum",
                       "Vondst:afwijkende opgave teruggavedatum", "Vondst.afhaaldatum",
                       "Vondst.berekende afhaaldatum", "Vondst.opslagkosten", "Vondst.opgegeven opslagkosten"] } ],
          "parameters": {"teruggavetermijn": 14} }""";

    /** The golden jutterswet-opgave-datum: the opgave marker on a date without absence markers. */
    private static final String PLAIN = """
        { "service": {"name":"opgave","version":"1"},
          "entrypoints": [ { "name":"opgave","path":"opgave",
            "scope": {"ruleNames": ["melding na de vinddag"]},
            "input": [], "output": ["Vondst.meldingsdatum"], "scopeCheckOptOut": true } ],
          "parameters": {} }""";

    private static ServiceHttp service;
    private static ServiceHttp document;
    private static ServiceHttp plain;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        service = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR,
                Map.of("source.rgs", Files.readString(GOLDEN.resolve("source.rgs"))), CoreConfig.strict(), cl),
                "127.0.0.1", 0).start();
        document = ServiceHttp.bind(DecisionCore.buildFromDocument(DESCRIPTOR, Files.readString(GOLDEN.resolve("ir.json")),
                CoreConfig.strict(), cl, null), "127.0.0.1", 0).start();
        plain = ServiceHttp.bind(DecisionCore.assemble(PLAIN, Map.of("source.rgs",
                Files.readString(GOLDENS.resolve("jutterswet-opgave-datum/source.rgs"))), CoreConfig.strict(), cl),
                "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
        if (document != null) document.stop(0);
        if (plain != null) plain.stop(0);
    }

    // ---------------- a marker above a date, a date above a marker ----------------

    /** case3: the opgave niet van toepassing decides above the computed date 24-03-2026, and deviates from it. */
    @Test
    void a_supplied_marker_above_a_computed_date_decides_and_deviates() throws Exception {
        String body = vondst("\"claimdatum\":\"2026-03-10\",\"opgegeven teruggavedatum\":"
                + "{\"marker\":\"niet van toepassing\"}", "");
        JsonNode answer = ok(post(service, "explanation=true&explanationParts=true", body));
        assertEquals(marker("niet van toepassing"), attribute(answer, "teruggavedatum"), answer.toString());
        assertEquals("2026-03-24", attribute(answer, "berekende teruggavedatum").asText(), answer.toString());
        assertEquals(marker("niet van toepassing"), attribute(answer, "opgegeven teruggavedatum"), answer.toString());
        assertDeviates(answer, true);
        assertContains(answer, "V heeft als teruggavedatum niet van toepassing volgens 'opgegeven teruggavedatum'"
                + " (onvoorwaardelijk): zijn opgegeven teruggavedatum (niet van toepassing); verdrongen: 'berekende"
                + " teruggavedatum' (onvoorwaardelijk, 24-03-2026)");
        assertContains(answer, "V heeft afwijkende opgave teruggavedatum, omdat zijn opgegeven teruggavedatum (niet van"
                + " toepassing) gevuld is en zijn berekende teruggavedatum (24-03-2026) gevuld is en zijn opgegeven"
                + " teruggavedatum (niet van toepassing) is ongelijk aan zijn berekende teruggavedatum (24-03-2026)");
        // the marker the opgave read is a VALUE with its name, in the head and as the value read
        assertEquals(List.of("niet van toepassing=niet van toepassing", "niet van toepassing=niet van toepassing",
                "24-03-2026="), values(answer, "V heeft als teruggavedatum "));
    }

    /** case2: the opgave 12-03-2026 decides above the computed marker niet van toepassing, and deviates from it. */
    @Test
    void a_supplied_date_above_a_computed_marker_decides_and_deviates() throws Exception {
        JsonNode answer = ok(post(service, "explanation=true",
                vondst("\"opgegeven teruggavedatum\":\"2026-03-12\"", "\"drijfhout\":true")));
        assertEquals("2026-03-12", attribute(answer, "teruggavedatum").asText(), answer.toString());
        assertEquals(marker("niet van toepassing"), attribute(answer, "berekende teruggavedatum"), answer.toString());
        assertEquals("2026-03-12", attribute(answer, "opgegeven teruggavedatum").asText(), answer.toString());
        assertDeviates(answer, true);
        assertContains(answer, "V heeft als teruggavedatum 12-03-2026 volgens 'opgegeven teruggavedatum'"
                + " (onvoorwaardelijk): zijn opgegeven teruggavedatum (12-03-2026); verdrongen: 'berekende"
                + " teruggavedatum' (onvoorwaardelijk, niet van toepassing)");
        assertContains(answer, "V heeft afwijkende opgave teruggavedatum, omdat zijn opgegeven teruggavedatum"
                + " (12-03-2026) gevuld is en zijn berekende teruggavedatum (niet van toepassing) gevuld is en zijn"
                + " opgegeven teruggavedatum (12-03-2026) is ongelijk aan zijn berekende teruggavedatum (niet van"
                + " toepassing)");
    }

    /** case1: without an opgave the computed marker decides, and the sentence names it. */
    @Test
    void a_computed_marker_decides_without_an_opgave() throws Exception {
        JsonNode answer = ok(post(service, "explanation=true", vondst("", "\"drijfhout\":true")));
        assertEquals(marker("niet van toepassing"), attribute(answer, "teruggavedatum"), answer.toString());
        assertEquals(marker("niet van toepassing"), attribute(answer, "berekende teruggavedatum"), answer.toString());
        assertTrue(attribute(answer, "opgegeven teruggavedatum").isNull(), answer.toString());
        assertDeviates(answer, false);
        assertContains(answer, "V heeft als teruggavedatum niet van toepassing volgens 'berekende teruggavedatum'"
                + " (onvoorwaardelijk): zijn berekende teruggavedatum (niet van toepassing); 'opgegeven teruggavedatum'"
                + " gaf geen waarde");
    }

    // ---------------- two markers ----------------

    /** case4: the same marker given and computed does not deviate. */
    @Test
    void the_same_marker_supplied_and_computed_does_not_deviate() throws Exception {
        JsonNode answer = ok(post(service, "explanation=true", vondst(
                "\"opgegeven teruggavedatum\":{\"marker\":\"niet van toepassing\"}", "\"drijfhout\":true")));
        assertEquals(marker("niet van toepassing"), attribute(answer, "teruggavedatum"), answer.toString());
        assertEquals(marker("niet van toepassing"), attribute(answer, "berekende teruggavedatum"), answer.toString());
        assertDeviates(answer, false);
        assertContains(answer, "V heeft geen afwijkende opgave teruggavedatum, want niet geldt: zijn opgegeven"
                + " teruggavedatum (niet van toepassing) is ongelijk aan zijn berekende teruggavedatum (niet van"
                + " toepassing)");
        assertContains(answer, "V heeft als teruggavedatum niet van toepassing volgens 'opgegeven teruggavedatum'"
                + " (onvoorwaardelijk): zijn opgegeven teruggavedatum (niet van toepassing); verdrongen: 'berekende"
                + " teruggavedatum' (onvoorwaardelijk, niet van toepassing)");
    }

    /** case5: two different markers deviate. */
    @Test
    void two_different_markers_deviate() throws Exception {
        JsonNode answer = ok(post(service, "explanation=true", vondst(
                "\"opgegeven teruggavedatum\":{\"marker\":\"niet van toepassing\"}", "\"onleesbaar\":true")));
        assertEquals(marker("niet van toepassing"), attribute(answer, "teruggavedatum"), answer.toString());
        assertEquals(marker("onbekend"), attribute(answer, "berekende teruggavedatum"), answer.toString());
        assertDeviates(answer, true);
        assertContains(answer, "zijn opgegeven teruggavedatum (niet van toepassing) is ongelijk aan zijn berekende"
                + " teruggavedatum (onbekend)");
    }

    // ---------------- the initialisation, a timeline ----------------

    /** case13: an opgave marker decides above the initialisation, without a computation and without deviation. */
    @Test
    void a_supplied_marker_decides_above_the_initialisation() throws Exception {
        JsonNode answer = ok(post(service, "explanation=true",
                vondst("\"opgegeven afhaaldatum\":{\"marker\":\"niet van toepassing\"}", "")));
        assertEquals(marker("niet van toepassing"), attribute(answer, "afhaaldatum"), answer.toString());
        assertTrue(attribute(answer, "berekende afhaaldatum").isNull(), answer.toString());
        assertContains(answer, "V heeft als afhaaldatum niet van toepassing volgens 'opgegeven afhaaldatum'"
                + " (onvoorwaardelijk): zijn opgegeven afhaaldatum (niet van toepassing); verdrongen: 'berekende"
                + " afhaaldatum' (onvoorwaardelijk, gaf geen waarde), 'afhaal zonder claim' (onvoorwaardelijk,"
                + " 01-01-2026)");
        assertContains(answer, "V heeft geen afwijkende opgave afhaaldatum, want niet geldt: zijn berekende afhaaldatum"
                + " (leeg) gevuld is");
    }

    /** case10: an opgave per month, a value and a marker, decides the days on which it holds one and the computation
     *  the others. */
    @Test
    void a_supplied_marker_per_month_decides_its_days() throws Exception {
        JsonNode answer = ok(post(service, "explanation=true", vondst("""
                "opgegeven opslagkosten":{"timeline":[{"from":"2025-02-01","to":"2025-05-01","value":4},
                  {"from":"2025-06-01","to":"2025-08-01","value":{"marker":"onbekend"}}]}""", "")));
        assertEquals(List.of("2025-01-01..2025-02-01=onbekend", "2025-02-01..2025-05-01=4", "2025-05-01..2025-06-01=10",
                        "2025-06-01..2025-08-01=onbekend"),
                segments(attribute(answer, "opslagkosten")), answer.toString());
        assertEquals(List.of("2025-02-01..2025-05-01=4", "2025-06-01..2025-08-01=onbekend"),
                segments(attribute(answer, "opgegeven opslagkosten")), answer.toString());
        assertContains(answer, "V heeft als opslagkosten onbekend van 01-06-2025 tot 01-08-2025 volgens 'opgegeven"
                + " opslagkosten' (onvoorwaardelijk): zijn opgegeven opslagkosten (4 van 01-02-2025 tot 01-05-2025 en"
                + " onbekend van 01-06-2025 tot 01-08-2025)");
        assertContains(answer, "V heeft als opslagkosten onbekend van 01-01-2025 tot 01-02-2025 volgens 'berekende"
                + " opslagkosten' (onvoorwaardelijk)");
    }

    // ---------------- a service from the document ----------------

    /** case3 on a service started from the golden's {@code ir.json}, whose model gives the three members X's sum type:
     *  the same answer and the same explanation. */
    @Test
    void a_marker_as_opgave_reads_the_same_on_a_service_from_the_document() throws Exception {
        String body = vondst("\"claimdatum\":\"2026-03-10\",\"opgegeven teruggavedatum\":"
                + "{\"marker\":\"niet van toepassing\"}", "");
        JsonNode source = ok(post(service, "explanation=true", body));
        JsonNode ir = ok(post(document, "explanation=true", body));
        assertEquals(source, ir);
        assertEquals(marker("niet van toepassing"), attribute(ir, "teruggavedatum"), ir.toString());
    }

    // ---------------- what the request does not take ----------------

    /** A marker the attribute does not declare is no marker of its opgave either: 400 UNKNOWN_MARKER, scalar and in a
     *  segment, naming the markers the opgave takes. */
    @Test
    void a_marker_the_attribute_does_not_declare_is_400_unknown_marker_on_its_opgave() throws Exception {
        JsonNode v = violation(post(service, "",
                vondst("\"opgegeven afhaaldatum\":{\"marker\":\"onbekend\"}", "")));
        assertEquals("UNKNOWN_MARKER", v.get("kind").asText(), v.toString());
        assertEquals("instances[0].attributes.opgegeven afhaaldatum", v.get("field").asText(), v.toString());
        assertTrue(v.get("error").asText().contains("'onbekend'")
                && v.get("error").asText().contains("(toegestaan: niet van toepassing)"), v.toString());
        JsonNode s = violation(post(service, "", vondst("""
                "opgegeven opslagkosten":{"timeline":[
                  {"from":"2025-02-01","to":"2025-05-01","value":{"marker":"niet van toepassing"}}]}""", "")));
        assertEquals("UNKNOWN_MARKER", s.get("kind").asText(), s.toString());
        assertEquals("instances[0].attributes.opgegeven opslagkosten.timeline[0].value", s.get("field").asText(),
                s.toString());
    }

    /** The opgave of an attribute without markers takes none: 400 WRONG_TYPE naming the member. */
    @Test
    void a_marker_on_an_opgave_without_markers_is_400_wrong_type() throws Exception {
        JsonNode v = violation(post(plain, "", "{\"instances\":[{\"type\":\"Vondst\",\"id\":\"V\",\"attributes\":"
                + "{\"opgegeven meldingsdatum\":{\"marker\":\"onbekend\"}}}]}"));
        assertEquals("WRONG_TYPE", v.get("kind").asText(), v.toString());
        assertEquals("instances[0].attributes.opgegeven meldingsdatum", v.get("field").asText(), v.toString());
        assertTrue(v.get("error").asText().contains("'opgegeven meldingsdatum' heeft geen markers"), v.toString());
    }

    // ---------------- helpers ----------------

    private static String vondst(String attributes, String characteristics) {
        return "{\"instances\":[{\"type\":\"Vondst\",\"id\":\"V\",\"attributes\":{" + attributes + "}"
                + ",\"characteristics\":{" + characteristics + "}}]}";
    }

    private static JsonNode marker(String name) throws Exception {
        return ServiceHttp.MAPPER.readTree("{\"marker\":\"" + name + "\"}");
    }

    private static void assertDeviates(JsonNode answer, boolean deviates) {
        JsonNode instance = answer.get("instances").get(0);
        assertEquals(deviates, instance.at("/characteristics/afwijkende opgave teruggavedatum").asBoolean(),
                answer.toString());
        assertEquals(deviates ? "[\"controle opgave teruggavedatum\"]" : "[]", answer.get("inconsistencies").toString(),
                answer.toString());
    }

    private static void assertContains(JsonNode answer, String fragment) {
        List<String> sentences = new ArrayList<>();
        answer.path("explanation").forEach(s -> sentences.add(s.asText()));
        assertTrue(sentences.stream().anyMatch(s -> s.contains(fragment)),
                "geen zin bevat \"" + fragment + "\":\n" + String.join("\n", sentences));
    }

    /** The VALUE leaves of the sentence that starts with {@code start}, each as {@code text=marker}. */
    private static List<String> values(JsonNode answer, String start) {
        JsonNode sentences = answer.get("explanation"), parts = answer.get("explanationParts");
        for (int i = 0; i < sentences.size(); i++)
            if (sentences.get(i).asText().startsWith(start)) {
                List<String> out = new ArrayList<>();
                collectValues(parts.get(i), out);
                return out;
            }
        throw new AssertionError("geen zin begint met \"" + start + "\": " + sentences);
    }

    private static void collectValues(JsonNode parts, List<String> out) {
        for (JsonNode p : parts) {
            if ("VALUE".equals(p.path("kind").asText()))
                out.add(p.path("text").asText() + "=" + p.path("marker").asText(""));
            if (p.has("parts")) collectValues(p.get("parts"), out);
        }
    }

    /** The segments of a timeline of the answer, each as {@code from..to=value}, a marker by its name. */
    private static List<String> segments(JsonNode timeline) {
        assertTrue(timeline != null && timeline.has("timeline"), String.valueOf(timeline));
        List<String> out = new ArrayList<>();
        for (JsonNode s : timeline.get("timeline")) {
            JsonNode v = s.get("value");
            out.add(s.path("from").asText() + ".." + s.path("to").asText() + "="
                    + (v.isObject() ? v.path("marker").asText() : v.asText()));
        }
        return out;
    }

    private static JsonNode attribute(JsonNode answer, String attribute) {
        return answer.get("instances").get(0).path("attributes").get(attribute);
    }

    private static JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return ServiceHttp.MAPPER.readTree(r.body());
    }

    private static JsonNode violation(HttpResponse<String> r) throws Exception {
        assertEquals(400, r.statusCode(), r.body());
        JsonNode violations = ServiceHttp.MAPPER.readTree(r.body()).get("violations");
        assertEquals(1, violations.size(), r.body());
        return violations.get(0);
    }

    private static HttpResponse<String> post(ServiceHttp to, String query, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + "/opgave"
                                + (query.isEmpty() ? "" : "?" + query)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
