package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase-3 HTTP adapter tests: a real {@link ServiceHttp} on an ephemeral 127.0.0.1 port driven by
 * {@link java.net.http.HttpClient}. Covers the happy round-trip over HTTP, the {@code /info} shape (per-regel
 * verdict + markers list when built with allow-markers), and the negative HTTP surface (400 naming the veld, 400
 * malformed / empty body, 404 unknown path, 405 wrong method, and the 500-never-leaks guarantee). Every server
 * is stopped in {@link AfterEach}.
 */
class ServiceHttpTest {

    private ServiceHttp service;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void stop() {
        if (service != null) service.stop(0);
        service = null;
    }

    // ---- fixtures ----

    /** The full TOKA kern, strict: uchar carries every golden scope. */
    private static DecisionCore tokaCore() {
        return DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
    }

    /** A self-contained marker service (mirrors MarkerFailFastTest), built with markers ALLOWED so /info lists
     *  the marker rule, a day count as the value of a target that is not time-dependent. */
    private static final String MARKER_SOURCE = """
        Objecttype de Vlucht (mv: Vluchten)
            de heffing Numeriek (niet-negatief geheel getal);
            is belast kenmerk (bijvoeglijk) voor elke dag;
        Regel heffing markert
        geldig altijd
        De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
        """;
    private static final String MARKER_DESCRIPTOR = """
        { "service": {"name":"marker-test","version":"1"},
          "entrypoints": [ { "name":"heffing markert","path":"heffing-markert",
            "scope": {"ruleNames": ["heffing markert"]},
            "input": ["Vlucht"], "output": ["Vlucht.heffing"] } ],
          "parameters": {} }""";

    private static DecisionCore markerCore() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("marker.rgs", MARKER_SOURCE);
        return DecisionCore.assemble(MARKER_DESCRIPTOR, source, CoreConfig.withMarkers(),
                Thread.currentThread().getContextClassLoader());
    }

    /** Two layers of exclusion in the exception dialect: the base rule and its exception form one defaults group. */
    private static final String EXCLUSION_SOURCE = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            is vergunning geweigerd kenmerk (bijvoeglijk);
            is weigering herroepen kenmerk (bijvoeglijk);
            is uitgesloten kenmerk (bijvoeglijk);
            is penninggerechtigd kenmerk (bijvoeglijk);
        Regel uitsluiting van het penningrecht
            geldig altijd
                Een Jutter is uitgesloten
                indien hij vergunning geweigerd is.
        Uitzondering op uitsluiting van het penningrecht: herroepen weigering
            geldig altijd
                Een Jutter is niet uitgesloten
                indien hij weigering herroepen is.
        Regel penninggerechtigd tenzij uitgesloten
            geldig altijd
                Een Jutter is penninggerechtigd
                indien hij niet uitgesloten is.
        """;

    /** A self-contained VINTAGE service (fase 5): a two-vintage parameterset (volwassenleeftijd 18 in 2025 / 21
     *  from 2026) driving a rule, built over the String-form with an in-memory parametersets sidecar. */
    private static final String VINTAGE_SOURCE = """
        Objecttype de Persoon (mv: Personen)
            de grens Numeriek (geheel getal);
        Parameter de volwassenleeftijd : Numeriek (geheel getal);
        Regel grensregel
        geldig altijd
        De grens van een Persoon moet berekend worden als de volwassenleeftijd.
        """;
    private static final String VINTAGE_DESCRIPTOR = """
        { "service": {"name":"vintage-http","version":"1"},
          "entrypoints": [ { "name":"grens","path":"grens",
            "scope": {"ruleNames": ["grensregel"]},
            "input": ["Persoon"], "output": ["Persoon.grens"] } ],
          "parameters": {},
          "parameterSets": "vintage.parameters.json" }""";
    private static final String VINTAGE_SETS = """
        { "parameterSets": [
            { "name": "leeftijden",
              "values": { "volwassenleeftijd": [
                  { "value": 18, "from": "2025-01-01", "to": "2025-12-31" },
                  { "value": 21, "from": "2026-01-01" } ] } } ] }""";

    private static DecisionCore vintageCore() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("vintage.rgs", VINTAGE_SOURCE);
        return DecisionCore.assemble(VINTAGE_DESCRIPTOR, source, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), VINTAGE_SETS);
    }

    private void serveer(DecisionCore core) throws IOException {
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
    }

    // ---- HTTP helpers ----

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + path))
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode tree(String json) throws Exception {
        return ServiceHttp.MAPPER.readTree(json);
    }

    // ---------------- happy round-trip ----------------

    @Test
    void happy_round_trip_bepaal_leeftijd_over_http() throws Exception {
        serveer(tokaCore());
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "geboortedatum": "1990-06-15" },
                  "roles": { "reis": "V1" } } ] }""";
        HttpResponse<String> resp = post("/bepaal-leeftijd", request);
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                resp.headers().map().toString());
        JsonNode answer = tree(resp.body());
        JsonNode leeftijd = find(answer, "P1", "attributes", "leeftijd");
        assertNotNull(leeftijd, resp.body());
        assertEquals(0, new BigDecimal("34").compareTo(leeftijd.decimalValue()), resp.body());
    }

    @Test
    void happy_round_trip_total_to_betalen_over_http() throws Exception {
        serveer(tokaCore());
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "te betalen belasting": 30.00 }, "roles": { "reis": "V1" } },
                { "type": "Natuurlijk persoon", "id": "P2",
                  "attributes": { "te betalen belasting": 20.00 }, "roles": { "reis": "V1" } } ] }""";
        HttpResponse<String> resp = post("/totaal-te-betalen-belasting", request);
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode total = find(tree(resp.body()), "V1", "attributes", "totaal te betalen belasting");
        assertNotNull(total, resp.body());
        assertEquals(0, new BigDecimal("50.00").compareTo(total.decimalValue()), resp.body());
        // exact BigDecimal render, never a double: the value round-trips as a JSON number, never a double.
        assertTrue(total.isNumber(), resp.body());
        assertFalse(total.isDouble(), "een som mag NOOIT als double renderen: " + resp.body());
    }

    // ---------------- fase 5: vintage over HTTP (G1(b)) ----------------

    @Test
    void g1b_two_as_of_dates_over_http_yield_different_outcomes() throws Exception {
        serveer(vintageCore());
        // two requests differing ONLY in the peildatum field -> two different pinned outcomes.
        HttpResponse<String> r2025 = post("/grens",
                "{ \"asOfDate\": \"2025-06-01\", \"instances\": [ { \"type\": \"Persoon\", \"id\": \"P1\" } ] }");
        assertEquals(200, r2025.statusCode(), r2025.body());
        assertEquals(0, new BigDecimal("18").compareTo(find(tree(r2025.body()), "P1", "attributes", "grens").decimalValue()),
                r2025.body());

        HttpResponse<String> r2026 = post("/grens",
                "{ \"asOfDate\": \"2026-06-01\", \"instances\": [ { \"type\": \"Persoon\", \"id\": \"P1\" } ] }");
        assertEquals(200, r2026.statusCode(), r2026.body());
        assertEquals(0, new BigDecimal("21").compareTo(find(tree(r2026.body()), "P1", "attributes", "grens").decimalValue()),
                r2026.body());
    }

    @Test
    void g6_no_as_of_date_multi_vintage_over_http_is_400_field_as_of_date() throws Exception {
        serveer(vintageCore());
        HttpResponse<String> resp = post("/grens",
                "{ \"instances\": [ { \"type\": \"Persoon\", \"id\": \"P1\" } ] }");
        assertEquals(400, resp.statusCode(), resp.body());
        boolean namesAsOfDate = false;
        for (JsonNode s : tree(resp.body()).get("violations"))
            if ("asOfDate".equals(s.path("field").asText())) namesAsOfDate = true;
        assertTrue(namesAsOfDate, "de 400 hoort veld 'peildatum' te noemen: " + resp.body());
    }

    @Test
    void info_shows_parameter_sets_with_validity_ranges() throws Exception {
        serveer(vintageCore());
        JsonNode info = tree(get("/info").body());
        assertTrue(info.get("parameterSets").isArray(), info.toString());
        assertEquals(1, info.get("parameterSets").size(), info.toString());
        JsonNode set = info.get("parameterSets").get(0);
        assertEquals("leeftijden", set.get("name").asText(), set.toString());
        JsonNode intervals = set.get("values").get("volwassenleeftijd");
        assertEquals(2, intervals.size(), set.toString());
        // first interval: 18, 2025-01-01..2025-12-31 ; second: 21, 2026-01-01..open
        assertEquals("2025-01-01", intervals.get(0).get("from").asText(), set.toString());
        assertEquals("2025-12-31", intervals.get(0).get("to").asText(), set.toString());
        assertEquals(0, new BigDecimal("18").compareTo(intervals.get(0).get("value").decimalValue()), set.toString());
        assertEquals("2026-01-01", intervals.get(1).get("from").asText(), set.toString());
        assertTrue(intervals.get(1).get("to").isNull(), "open tot: " + set);
        // grensregel is 'geldig altijd' but the parameter is only valued from 2025 -> a pre-2025 coverage gap
        // WARNING is present and names the parameter (gate G3 visible on /info).
        assertTrue(info.get("periodWarnings").isArray(), info.toString());
        assertFalse(info.get("periodWarnings").isEmpty(),
                "geldig altijd × vanaf-2025 = een pre-2025 gat: " + info.get("periodWarnings"));
        assertTrue(info.get("periodWarnings").get(0).asText().contains("volwassenleeftijd"),
                info.get("periodWarnings").toString());
    }

    // ---------------- /info ----------------

    @Test
    void info_shape_over_toka_with_per_rule_verdict() throws Exception {
        serveer(tokaCore());
        HttpResponse<String> resp = get("/info");
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        JsonNode info = tree(resp.body());

        assertTrue(info.get("service").has("name"), resp.body());
        assertTrue(info.get("service").has("version"), resp.body());
        assertEquals("TOKA belastingdienst", info.get("service").get("name").asText(), resp.body());
        assertEquals("2.3.0", info.get("service").get("version").asText(), resp.body());
        assertEquals(29, info.get("entrypoints").size(), "29 golden entrypoints");
        assertTrue(info.get("parameters").isObject(), resp.body());
        assertTrue(info.get("skipped").isArray(), resp.body());
        // strict TOKA: nothing overgeslagen
        assertTrue(info.get("skipped").isEmpty(), "strikte TOKA heeft geen markers: " + info.get("skipped"));

        // find the bepaal-leeftijd entrypoint and assert its dekking carries a per-regel LOWERED verdict
        JsonNode ep = entrypointWith(info, "bepaal-leeftijd");
        assertNotNull(ep, resp.body());
        assertTrue(ep.get("coverage").isArray(), resp.body());
        assertTrue(ep.get("markers").isArray(), resp.body());
        assertTrue(ep.get("markers").isEmpty(), "bepaal-leeftijd lowert: " + ep.get("markers"));
        assertTrue(ep.get("executable").asBoolean(), ep.toString());
        assertFalse(ep.has("refusal"), ep.toString());
        boolean hasLowered = false;
        for (JsonNode d : ep.get("coverage")) {
            assertTrue(d.has("kind") && d.has("name") && d.has("verdict"), d.toString());
            if ("LOWERED".equals(d.get("verdict").asText())) hasLowered = true;
        }
        assertTrue(hasLowered, "bepaal-leeftijd dekking hoort een LOWERED-verdict te tonen: " + ep.get("coverage"));
    }

    @Test
    void info_fact_creation_shows_its_verdict() throws Exception {
        // The entry point's document carries the feitcreatie form of the full model, and the exec-model branch lowers
        // it. Until the scope was taken per form, the entry point was built from the foundation plus the toka-29 file
        // alone, where uchar's frontend makes no form of it, and /info showed an empty dekking for a build without
        // the rule.
        serveer(tokaCore());
        JsonNode info = tree(get("/info").body());
        JsonNode ep = entrypointWith(info, "passagier-met-recht-op-treinmiles");
        assertNotNull(ep, info.toString());
        assertTrue(ep.get("executable").asBoolean(), ep.toString());
        assertEquals(1, ep.get("coverage").size(), ep.toString());
        assertEquals("feitcreatie", ep.get("coverage").get(0).get("kind").asText(), ep.toString());
        assertEquals("LOWERED", ep.get("coverage").get(0).get("verdict").asText(), ep.toString());
        assertTrue(ep.get("markers").isEmpty(), ep.toString());
    }

    @Test
    void a_scope_without_form_is_a_marker_with_uchar_reason() throws Exception {
        // an exception rule goes up in the defaults group of its base rule and has no form of its own: an entry point
        // that scopes it alone would carry nothing, so it is a marker and a request gets the MARKER answer
        Map<String, String> source = new LinkedHashMap<>();
        source.put("uitsluiting.rgs", EXCLUSION_SOURCE);
        String descriptor = """
            { "service": {"name":"uitsluiting","version":"1"},
              "entrypoints": [ { "name":"herroeping","path":"herroeping",
                "scope": {"ruleNames": ["herroepen weigering"]},
                "input": ["Jutter"], "output": ["Jutter:uitgesloten"] } ],
              "parameters": {} }""";
        serveer(DecisionCore.assemble(descriptor, source, CoreConfig.withMarkers(),
                Thread.currentThread().getContextClassLoader()));
        JsonNode ep = entrypointWith(tree(get("/info").body()), "herroeping");
        assertNotNull(ep);
        assertFalse(ep.get("executable").asBoolean(), ep.toString());
        assertTrue(ep.get("refusal").asText().contains("geen vorm"), ep.toString());
        HttpResponse<String> resp = post("/herroeping", """
            { "instances": [ { "type": "Jutter", "id": "M" } ] }""");
        assertEquals(503, resp.statusCode(), resp.body());
        assertEquals("MARKER", tree(resp.body()).get("status").asText(), resp.body());
    }

    @Test
    void info_coverage_shows_the_defaults_group_of_the_scope() throws Exception {
        // a defaults group is a form: an entry point that scopes it shows its verdict in the dekking
        Map<String, String> source = new LinkedHashMap<>();
        source.put("uitsluiting.rgs", EXCLUSION_SOURCE);
        String descriptor = """
            { "service": {"name":"uitsluiting","version":"1"},
              "entrypoints": [ { "name":"penningrecht","path":"penningrecht",
                "scope": {"ruleNames": ["uitsluiting van het penningrecht", "penninggerechtigd tenzij uitgesloten"]},
                "input": ["Jutter"], "output": ["Jutter:uitgesloten", "Jutter:penninggerechtigd"] } ],
              "parameters": {} }""";
        serveer(DecisionCore.assemble(descriptor, source, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
        JsonNode ep = entrypointWith(tree(get("/info").body()), "penningrecht");
        assertNotNull(ep);
        java.util.Set<String> kinds = new java.util.TreeSet<>();
        for (JsonNode d : ep.get("coverage")) {
            kinds.add(d.get("kind").asText());
            assertEquals("LOWERED", d.get("verdict").asText(), d.toString());
        }
        assertEquals(java.util.Set.of("regel", "defaults"), kinds, ep.get("coverage").toString());

        // and it fires: a refusal that is withdrawn leaves the Jutter penninggerechtigd
        HttpResponse<String> resp = post("/penningrecht", """
            { "instances": [ { "type": "Jutter", "id": "M",
                "characteristics": { "vergunning geweigerd": true, "weigering herroepen": true } } ] }""");
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode characteristics = tree(resp.body()).get("instances").get(0).get("characteristics");
        assertFalse(characteristics.get("uitgesloten").asBoolean(), resp.body());
        assertTrue(characteristics.get("penninggerechtigd").asBoolean(), resp.body());
    }

    @Test
    void info_lists_the_markers_at_allow_markers() throws Exception {
        serveer(markerCore());
        JsonNode info = tree(get("/info").body());
        JsonNode ep = entrypointWith(info, "heffing-markert");
        assertNotNull(ep, info.toString());
        assertTrue(contains(ep.get("markers"), "heffing markert"),
                "markers hoort de marker-regel te noemen: " + ep.get("markers"));
        // and the top-level overgeslagen lists it too
        assertTrue(contains(info.get("skipped"), "heffing markert"),
                "overgeslagen hoort de marker-regel te noemen: " + info.get("skipped"));
        // uchar builds no KieBase that lacks a construct, so the marker entrypoint has no build and no dekking;
        // until the move to uchar its dekking listed the rule with a MARKER verdict
        assertTrue(ep.get("coverage").isEmpty(), "een niet-gebouwd entrypoint draagt geen dekking: " + ep.get("coverage"));
    }

    @Test
    void a_request_to_a_marker_entrypoint_is_503() throws Exception {
        // started with markers allowed, the entrypoint exists but cannot fire: every request is refused by name,
        // never answered as if the marker rule did not exist
        serveer(markerCore());
        HttpResponse<String> resp = post("/heffing-markert", """
            { "instances": [ { "type": "Vlucht", "id": "V1" } ] }""");
        assertEquals(503, resp.statusCode(), resp.body());
        JsonNode error = tree(resp.body());
        assertEquals("MARKER", error.get("status").asText(), resp.body());
        assertTrue(error.get("error").asText().contains("niet uitvoerbaar"), resp.body());
        assertTrue(error.get("reason").asText().contains("transpiler"), "de reden is uchars weigering: " + resp.body());
        assertTrue(contains(error.get("markers"), "heffing markert"), resp.body());
    }

    // ---------------- negative HTTP ----------------

    @Test
    void _400_names_the_field_at_unknown_attribute() throws Exception {
        serveer(tokaCore());
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": { "bestaat-niet": 3 } } ] }""";
        HttpResponse<String> resp = post("/bepaal-leeftijd", request);
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode body = tree(resp.body());
        assertEquals("verzoek valideert niet", body.get("error").asText());
        assertTrue(body.get("violations").isArray() && !body.get("violations").isEmpty(), resp.body());
        boolean namesField = false;
        for (JsonNode s : body.get("violations")) {
            assertTrue(s.has("field"), s.toString());
            assertTrue(s.has("error"), s.toString());
            if (s.get("field").asText().contains("bestaat-niet")
                    || s.get("error").asText().contains("bestaat-niet")) namesField = true;
        }
        assertTrue(namesField, "de 400 hoort het onbekende veld te noemen: " + resp.body());
    }

    @Test
    void _400_at_malformed_json_body() throws Exception {
        serveer(tokaCore());
        HttpResponse<String> resp = post("/bepaal-leeftijd", "{ this is not json ");
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode body = tree(resp.body());
        assertEquals("body", body.get("field").asText(), resp.body());
    }

    @Test
    void _400_at_empty_body() throws Exception {
        serveer(tokaCore());
        HttpResponse<String> resp = post("/bepaal-leeftijd", "");
        assertEquals(400, resp.statusCode(), resp.body());
        assertEquals("body", tree(resp.body()).get("field").asText(), resp.body());
    }

    @Test
    void _404_at_unknown_path() throws Exception {
        serveer(tokaCore());
        // an unknown path -> the catch-all context 404s with a JSON body naming the pad.
        HttpResponse<String> resp = post("/dit-pad-bestaat-niet", "{ \"instances\": [] }");
        assertEquals(404, resp.statusCode(), resp.body());
        assertTrue(tree(resp.body()).get("error").asText().contains("dit-pad-bestaat-niet"), resp.body());
    }

    @Test
    void _405_at_wrong_method() throws Exception {
        serveer(tokaCore());
        // GET on a POST-only entry point path -> 405 naming the method.
        HttpResponse<String> resp = get("/bepaal-leeftijd");
        assertEquals(405, resp.statusCode(), resp.body());
        JsonNode body = tree(resp.body());
        assertTrue(body.get("error").asText().contains("GET"), resp.body());
    }

    @Test
    void _500_never_leaks_a_stacktrace_or_secret() throws Exception {
        // Force an internal error via a verwerk delegate that throws a RuntimeException carrying a secret. The
        // adapter must answer 500 with {"error":"interne fout"} and NEVER echo the message or a class name.
        DecisionCore core = tokaCore();
        ServiceHttp.Processor exploding = (path, tree) -> {
            throw new RuntimeException("geheim");
        };
        service = ServiceHttp.bindWith(core, exploding, "127.0.0.1", 0).start();
        HttpResponse<String> resp = post("/bepaal-leeftijd", "{ \"instances\": [] }");
        assertEquals(500, resp.statusCode(), resp.body());
        assertEquals("interne fout", tree(resp.body()).get("error").asText(), resp.body());
        assertFalse(resp.body().contains("geheim"), "het geheim mag NOOIT in de body lekken: " + resp.body());
        assertFalse(resp.body().contains("RuntimeException"), "geen class-naam in de body: " + resp.body());
        assertFalse(resp.body().contains("Exception"), "geen class-naam in de body: " + resp.body());
    }

    // ---- helpers ----

    private static JsonNode find(JsonNode answer, String id, String group, String member) {
        for (JsonNode inst : answer.get("instances"))
            if (id.equals(inst.path("id").asText()))
                return inst.path(group).get(member);
        return null;
    }

    private static JsonNode entrypointWith(JsonNode info, String path) {
        for (JsonNode ep : info.get("entrypoints"))
            if (path.equals(ep.path("path").asText())) return ep;
        return null;
    }

    private static boolean contains(JsonNode array, String value) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode n : array) if (value.equals(n.asText())) return true;
        return false;
    }
}
