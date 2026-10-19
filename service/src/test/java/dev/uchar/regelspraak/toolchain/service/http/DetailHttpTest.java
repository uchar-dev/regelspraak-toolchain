package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;
import dev.uchar.regelspraak.toolchain.service.RequestOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detail of a request's session on {@code POST /<pad>}: {@code ?trace}, {@code ?dekking} and {@code ?fixpunt} on a
 * service started with detail ({@link CoreConfig#detail()}). The fixpoint has the form the playground gives a scenario,
 * with the instances of the request as its input: on the same situation the two give the same {@code fixpunt}, and the
 * same {@code trace} and {@code dekking}. A 422 carries the asked detail beside its faults. A service started without
 * detail refuses each flag with a 400 naming it, and a request without a flag gets the answer of a service without
 * detail, byte for byte.
 */
class DetailHttpTest {

    /** The playground's own material ({@code PlaygroundScenarioDataTest}): a division by the helpers, and a second
     *  writer of the helpersdeel that disagrees with it from five helpers on. */
    private static final String BERGING = """
            Eenheidsysteem Valuta
                de euro (mv: euros) EUR €
            Objecttype de Berging (mv: Bergingen)
                de opbrengst Numeriek (geheel getal) met eenheid €;
                het helpersaantal Numeriek (geheel getal);
                het helpersdeel Numeriek (geheel getal) met eenheid €;
                de premie Numeriek (getal met 2 decimalen) met eenheid €;
            Parameter het premiepercentage : Percentage (geheel getal)
            Regel premie van een berging
            geldig altijd
            De premie van een Berging moet berekend worden als het premiepercentage van de opbrengst van de Berging.
            Regel helpersdeel van een berging
            geldig altijd
            Het helpersdeel van een Berging moet berekend worden als de opbrengst van de Berging gedeeld door het helpersaantal van de Berging.
            Regel helpersdeel bij veel helpers
            geldig altijd
            Het helpersdeel van een Berging moet gesteld worden op 7 € indien het helpersaantal van de Berging groter is dan 4.
            """;
    private static final String BERGING_DESCRIPTOR = """
            { "service": {"name":"berging","version":"1"},
              "entrypoints": [ { "name":"berging","path":"berging",
                "scope": {"ruleNames": ["premie van een berging", "helpersdeel van een berging", "helpersdeel bij veel helpers"]},
                "input": ["Berging"], "output": ["Berging.premie"] } ],
              "parameters": {} }""";
    private static final String SITUATION = """
            Situatie basis:
            een Berging B
            - de opbrengst is 100 €
            - het helpersaantal is %s
            de parameter het premiepercentage is 20 %%
            Scenario berging
            met peildatum 01-01-2026
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de premie van B is 20,00 €
            """;

    /** uchar's golden {@code jutterswet-strandmelding}: an object creation per Melding and the links it lays. The
     *  amounts are whole: the service reads a JSON number such as {@code 20.00} as {@code 20}, so a fixpoint of the
     *  service shows an input amount without the scale the situation writes, where the playground keeps it. */
    private static final String STRANDMELDING_DESCRIPTOR = """
            { "service": {"name":"strandmelding","version":"1"},
              "entrypoints": [ { "name":"melding","path":"melding",
                "scope": {"ruleNames": ["maak een strandrecht bij elke melding", "uitkering van een wrakrecht",
                                         "lees de gemelde uitkering"]},
                "input": ["Melding"], "output": ["Melding.gemelde uitkering"] } ],
              "parameters": {} }""";
    private static final String MELDINGEN = """

            Scenario twee meldingen
            Gegeven is de volgende situatie:
            een Melding M2
            - de omvang is 20
            een Melding M1
            - de omvang is 10
            Met als verwacht resultaat:
            - de gemelde uitkering van M1 is 10
            """;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static ServiceHttp withDetail;
    private static ServiceHttp withoutDetail;
    private static ServiceHttp strandmelding;
    private static DecisionCore strandmeldingKern;
    private static String strandmeldingSource;

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        withDetail = ServiceHttp.bind(DecisionCore.assemble(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                CoreConfig.strict().withDetail(), cl), "127.0.0.1", 0).start();
        withoutDetail = ServiceHttp.bind(DecisionCore.assemble(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                CoreConfig.strict(), cl), "127.0.0.1", 0).start();
        strandmeldingSource = Files.readString(
                TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-strandmelding/source.rgs"));
        strandmeldingKern = DecisionCore.assemble(STRANDMELDING_DESCRIPTOR, Map.of("bron.rgs", strandmeldingSource),
                CoreConfig.strict().withDetail(), cl);
        strandmelding = ServiceHttp.bind(strandmeldingKern, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        for (ServiceHttp d : new ServiceHttp[]{withDetail, withoutDetail, strandmelding}) if (d != null) d.stop(0);
    }

    // ---------------- the detail of a 200 and a 422 ----------------

    /** All three on a 200: the trace per form with the ids of the request, the coverage of the entry point, the
     *  fixpoint with the input and the derived values; the answer itself is the one without flags, the detail after
     *  it. */
    @Test
    void trace_coverage_and_fixpoint_at_an_answer() throws Exception {
        String request = bergingRequest("B7", 2);
        HttpResponse<String> without = post(withDetail, "berging", "", request);
        HttpResponse<String> extended = post(withDetail, "berging", "trace=true&coverage=true&fixpoint=true", request);
        assertEquals(200, extended.statusCode(), extended.body());
        ObjectNode a = (ObjectNode) ServiceHttp.MAPPER.readTree(extended.body());
        assertEquals(List.of("instances", "inconsistencies", "inconsistencyDetails", "trace", "coverage", "fixpoint"),
                fields(a));

        ObjectNode bare = a.deepCopy();
        bare.remove(List.of("trace", "coverage", "fixpoint"));
        assertEquals(ServiceHttp.MAPPER.readTree(without.body()), bare, "het antwoord zelf blijft gelijk");

        // the trace in the order a rule of a form first fired, the coverage in the order of the manifest
        JsonNode trace = a.get("trace");
        assertEquals(Set.of("premie van een berging", "helpersdeel van een berging"), Set.copyOf(text(trace, "rule")));
        assertEquals(2, trace.size(), trace.toString());
        for (JsonNode t : trace) {
            assertEquals("[\"B7\"]", t.get("labels").toString(), t.toString());
            assertEquals(1, t.get("count").asInt(), t.toString());
        }
        JsonNode coverage = a.get("coverage");
        assertEquals(Set.of("premie van een berging", "helpersdeel van een berging", "helpersdeel bij veel helpers"),
                Set.copyOf(text(coverage, "rule")));
        assertEquals(List.of("FIRED", "FIRED", "NOT_FIRED"), text(coverage, "kind"));

        JsonNode b = a.at("/fixpoint/objects/0");
        assertEquals("B7", b.get("object").asText());
        assertEquals("Berging", b.get("objectType").asText());
        List<String> rows = new ArrayList<>();
        for (JsonNode w : b.get("values"))
            rows.add(w.get("attribute").asText() + "=" + w.get("value").asText() + " " + w.get("origin").asText());
        assertEquals(List.of("opbrengst=100 input", "helpersaantal=2 input", "helpersdeel=50 derived",
                "premie=20 derived"), rows);
        assertEquals("EUR", b.at("/values/0/unit").asText(), b.toString());
    }

    /** Only what is asked: the trace alone, after the explanation. */
    @Test
    void only_what_is_asked() throws Exception {
        HttpResponse<String> r = post(withDetail, "berging", "explanation=true&trace", bergingRequest("B", 2));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(List.of("instances", "inconsistencies", "inconsistencyDetails", "explanation", "trace"),
                fields(ServiceHttp.MAPPER.readTree(r.body())));
    }

    /** Two writers that disagree: the 422 keeps its faults and gets the asked detail beside them, the fault as data in
     *  the fixpoint with the object by its id. */
    @Test
    void a_422_gets_the_fixpoint() throws Exception {
        String request = bergingRequest("B", 5);
        HttpResponse<String> without = post(withDetail, "berging", "", request);
        HttpResponse<String> extended = post(withDetail, "berging", "fixpoint=true&trace=true", request);
        assertEquals(422, extended.statusCode(), extended.body());
        ObjectNode a = (ObjectNode) ServiceHttp.MAPPER.readTree(extended.body());
        assertEquals(List.of("status", "error", "faults", "trace", "fixpoint"), fields(a));
        ObjectNode bare = a.deepCopy();
        bare.remove(List.of("trace", "fixpoint"));
        assertEquals(ServiceHttp.MAPPER.readTree(without.body()), bare, "de fouten blijven gelijk");
        JsonNode error = a.at("/fixpoint/faults/0");
        assertEquals("WRITERS_CONFLICT", error.get("kind").asText(), a.toString());
        assertEquals("helpersdeel van een berging", error.get("rule").asText());
        assertEquals("B", error.get("subject").asText());
        assertEquals(a.at("/faults/0/error").asText(), error.get("sentence").asText());
        assertEquals(1, a.at("/fixpoint/faults").size());
    }

    // ---------------- the form of the playground ----------------

    /** On the same situation the service and the playground give the same fixpoint, trace and coverage: the request is
     *  the situation, derived mechanically ({@link SituationToRequest}), and the entry point scopes every rule. */
    @Test
    void the_same_form_as_the_playground() throws Exception {
        String source = BERGING + SITUATION.formatted("2");
        JsonNode playground = Playground.analyze(source).at("/scenario/results/0");
        JsonNode service = detailOf(withDetail, "berging", source);
        assertEquals(playground.get("fixpoint"), service.get("fixpoint"));
        assertEquals(playground.get("trace"), service.get("trace"));
        assertEquals(playground.get("coverage"), service.get("coverage"));
    }

    /** The fault as data: the playground's fixpoint of a case uchar's replay does not play out, and the service's of
     *  its 422, are the same. */
    @Test
    void the_same_fault_as_the_playground() throws Exception {
        String source = BERGING + SITUATION.formatted("5");
        JsonNode playground = Playground.analyze(source).at("/scenario/results/0");
        JsonNode service = detailOf(withDetail, "berging", source);
        assertEquals(playground.get("fixpoint"), service.get("fixpoint"));
    }

    /** Made objects and the links of their creation, as the playground names them: after the input, by their
     *  subject, {@code #<n>}. */
    @Test
    void made_objects_like_the_playground() throws Exception {
        String source = strandmeldingSource + MELDINGEN;
        JsonNode playground = Playground.analyze(source).at("/scenario/results/0");
        JsonNode service = detailOf(strandmelding, "melding", source);
        assertEquals(List.of("M2", "M1", "#1", "#2"), text(service.at("/fixpoint/objects"), "object"));
        assertEquals(2, service.at("/fixpoint/links").size(), service.toString());
        assertEquals(playground.get("fixpoint"), service.get("fixpoint"));
        assertEquals(playground.get("trace"), service.get("trace"));
    }

    /** The kern itself, without HTTP: the request tree of the situation, with its numbers exact, gives the playground's
     *  fixpoint and trace, the scale of an amount included ({@code 20.00}), which a JSON body read over HTTP loses. */
    @Test
    void the_core_without_http_gives_the_form_of_the_playground() {
        String source = strandmeldingSource + MELDINGEN.replace(" is 20", " is 20,00").replace(" is 10", " is 10,00");
        JsonNode playground = Playground.analyze(source).at("/scenario/results/0");
        var bundle = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(source);
        ObjectNode service = strandmeldingKern.process("melding", SituationToRequest.assemble(bundle.scenarios().get(0)),
                new RequestOptions(false, null, null, true, true, true));
        assertEquals("20.00", service.at("/fixpoint/objects/0/values/0/value").asText(), service.toString());
        assertEquals(playground.get("fixpoint"), service.get("fixpoint"));
        assertEquals(playground.get("trace"), service.get("trace"));
    }

    // ---------------- without DETAIL, and without a flag ----------------

    /** Without detail every flag asked is a 400 naming it, and the body is not read; a flag set to another value than
     *  true asks nothing. */
    @Test
    void without_detail_a_400_with_the_name_of_the_flag() throws Exception {
        HttpResponse<String> r = post(withoutDetail, "berging", "fixpoint=true&trace&coverage=TRUE", "{}");
        assertEquals(400, r.statusCode(), r.body());
        JsonNode s = ServiceHttp.MAPPER.readTree(r.body()).get("violations");
        assertEquals(List.of("trace", "coverage", "fixpoint"), text(s, "field"));
        for (JsonNode n : s) {
            assertEquals("UNSUPPORTED", n.get("kind").asText(), n.toString());
            assertTrue(n.get("error").asText().contains("DETAIL"), n.toString());
        }
        HttpResponse<String> sink = post(withoutDetail, "berging", "trace=false", bergingRequest("B", 2));
        assertEquals(200, sink.statusCode(), sink.body());
        assertArrayEquals(post(withoutDetail, "berging", "", bergingRequest("B", 2)).body()
                .getBytes(StandardCharsets.UTF_8), sink.body().getBytes(StandardCharsets.UTF_8));
    }

    /** A request without a flag gets from a service with detail the bytes a service without detail gives, for an
     *  answer and for a 422. */
    @Test
    void without_flag_byte_equal() throws Exception {
        for (String request : List.of(bergingRequest("B", 2), bergingRequest("B", 5))) {
            HttpResponse<String> extended = post(withDetail, "berging", "", request);
            HttpResponse<String> without = post(withoutDetail, "berging", "", request);
            assertEquals(without.statusCode(), extended.statusCode(), extended.body());
            assertArrayEquals(without.body().getBytes(StandardCharsets.UTF_8), extended.body().getBytes(StandardCharsets.UTF_8));
            assertFalse(extended.body().contains("\"trace\""), extended.body());
        }
    }

    // ---------------- the switches ----------------

    @Test
    void the_flags_and_the_startup_setting() {
        RequestOptions o = ServiceHttp.options("Trace=TRUE&coverage&fixpoint=nee&explanation=true");
        assertEquals(new RequestOptions(true, null, null, true, true, false), o);
        assertEquals(List.of("trace", "coverage"), o.detailFlags());
        assertEquals(RequestOptions.DEFAULT, ServiceHttp.options("trace=false"));
        assertTrue(ServiceMain.parseArgs(new String[]{"d.json", "x.rgs", "--detail"}).detail());
        assertFalse(ServiceMain.parseArgs(new String[]{"d.json", "x.rgs"}).detail());
        assertEquals(new CoreConfig(true, true), CoreConfig.withMarkers().withDetail());
        assertFalse(CoreConfig.strict().detail());
    }

    // ---------------- helpers ----------------

    private static String bergingRequest(String id, int helpers) {
        return """
                { "instances": [ { "type": "Berging", "id": "%s",
                                    "attributes": { "opbrengst": 100, "helpersaantal": %d } } ],
                  "parameters": { "premiepercentage": 20 }, "rekendatum": "2026-01-01" }""".formatted(id, helpers);
    }

    /** The answer of {@code service} with all three flags to the situation of the first scenario of {@code source}. */
    private static JsonNode detailOf(ServiceHttp service, String path, String source) throws Exception {
        var bundle = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(source);
        assertTrue(bundle.isOk(), () -> String.valueOf(bundle.model().errors()));
        String request = ServiceHttp.MAPPER.writeValueAsString(SituationToRequest.assemble(bundle.scenarios().get(0)));
        HttpResponse<String> r = post(service, path, "trace=true&coverage=true&fixpoint=true", request);
        assertTrue(Set.of(200, 422).contains(r.statusCode()), r.body());
        return ServiceHttp.MAPPER.readTree(r.body());
    }

    private static HttpResponse<String> post(ServiceHttp service, String path, String query, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/" + path
                        + (query.isEmpty() ? "" : "?" + query)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static List<String> fields(JsonNode n) {
        List<String> sink = new ArrayList<>();
        n.fieldNames().forEachRemaining(sink::add);
        return sink;
    }

    private static List<String> text(JsonNode list, String key) {
        List<String> sink = new ArrayList<>();
        for (JsonNode n : list) sink.add(n.get(key).asText());
        return sink;
    }
}
