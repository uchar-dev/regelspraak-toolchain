package io.github.yhavinga.regelspraak.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.yhavinga.regelspraak.service.BeslisserKern;
import io.github.yhavinga.regelspraak.service.KernConfig;
import io.github.yhavinga.regelspraak.service.TokaBron;

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
 * Fase-3 HTTP adapter tests: a real {@link DienstHttp} on an ephemeral 127.0.0.1 port driven by
 * {@link java.net.http.HttpClient}. Covers the happy round-trip over HTTP, the {@code /info} shape (per-regel
 * verdict + markers list when built with allow-markers), and the negative HTTP surface (400 naming the veld, 400
 * malformed / empty body, 404 unknown path, 405 wrong method, and the 500-never-leaks guarantee). Every server
 * is stopped in {@link AfterEach}.
 */
class DienstHttpTest {

    private DienstHttp dienst;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void stop() {
        if (dienst != null) dienst.stop(0);
        dienst = null;
    }

    // ---- fixtures ----

    /** The full TOKA kern over the DRL back-end (strict; every golden scope lowers). */
    private static BeslisserKern tokaKern() {
        return BeslisserKern.bouw(TokaBron.descriptorJson(), TokaBron.bronPerBestand(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
    }

    /** A self-contained marker service (mirrors MarkerFailFastTest), built with markers ALLOWED so /info lists
     *  the marker rule "het absolute tijdsevenredig deel per maand" scope. */
    private static final String MARKER_BRON = """
        Objecttype de Vlucht (mv: Vluchten)
            de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
        Regel heffing markert
        geldig altijd
        De heffing van een Vlucht moet berekend worden als het absolute tijdsevenredig deel per maand van 10,00 EUR.
        """;
    private static final String MARKER_DESCRIPTOR = """
        { "dienst": {"naam":"marker-test","versie":"1"},
          "entrypoints": [ { "naam":"heffing markert","pad":"heffing-markert",
            "scope": {"regelnamen": ["heffing markert"]},
            "invoer": ["Vlucht"], "uitvoer": ["Vlucht.heffing"] } ],
          "parameters": {} }""";

    private static BeslisserKern markerKern() {
        Map<String, String> bron = new LinkedHashMap<>();
        bron.put("marker.rs", MARKER_BRON);
        return BeslisserKern.bouw(MARKER_DESCRIPTOR, bron, KernConfig.metMarkers(),
                Thread.currentThread().getContextClassLoader());
    }

    /** A self-contained VINTAGE service (fase 5): a two-vintage parameterset (volwassenleeftijd 18 in 2025 / 21
     *  from 2026) driving a rule, built over the String-form with an in-memory parametersets sidecar. */
    private static final String VINTAGE_BRON = """
        Objecttype de Persoon (mv: Personen)
            de grens Numeriek (geheel getal);
        Parameter de volwassenleeftijd : Numeriek (geheel getal);
        Regel grensregel
        geldig altijd
        De grens van een Persoon moet berekend worden als de volwassenleeftijd.
        """;
    private static final String VINTAGE_DESCRIPTOR = """
        { "dienst": {"naam":"vintage-http","versie":"1"},
          "entrypoints": [ { "naam":"grens","pad":"grens",
            "scope": {"regelnamen": ["grensregel"]},
            "invoer": ["Persoon"], "uitvoer": ["Persoon.grens"] } ],
          "parameters": {},
          "parametersets": "vintage.parameters.json" }""";
    private static final String VINTAGE_SETS = """
        { "parametersets": [
            { "naam": "leeftijden",
              "waarden": { "volwassenleeftijd": [
                  { "waarde": 18, "vanaf": "2025-01-01", "tot": "2025-12-31" },
                  { "waarde": 21, "vanaf": "2026-01-01" } ] } } ] }""";

    private static BeslisserKern vintageKern() {
        Map<String, String> bron = new LinkedHashMap<>();
        bron.put("vintage.rs", VINTAGE_BRON);
        return BeslisserKern.bouw(VINTAGE_DESCRIPTOR, bron, KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader(), VINTAGE_SETS);
    }

    private void serveer(BeslisserKern kern) throws IOException {
        dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
    }

    // ---- HTTP helpers ----

    private HttpResponse<String> post(String pad, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + dienst.poort() + pad))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String pad) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + dienst.poort() + pad))
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode boom(String json) throws Exception {
        return DienstHttp.MAPPER.readTree(json);
    }

    // ---------------- happy round-trip ----------------

    @Test
    void happy_round_trip_bepaal_leeftijd_over_http() throws Exception {
        serveer(tokaKern());
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "1990-06-15" },
                  "rollen": { "reis": "V1" } } ] }""";
        HttpResponse<String> resp = post("/bepaal-leeftijd", verzoek);
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                resp.headers().map().toString());
        JsonNode antwoord = boom(resp.body());
        JsonNode leeftijd = vind(antwoord, "P1", "attributen", "leeftijd");
        assertNotNull(leeftijd, resp.body());
        assertEquals(0, new BigDecimal("34").compareTo(leeftijd.decimalValue()), resp.body());
    }

    @Test
    void happy_round_trip_totaal_te_betalen_over_http() throws Exception {
        serveer(tokaKern());
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "te betalen belasting": 30.00 }, "rollen": { "reis": "V1" } },
                { "type": "Natuurlijk persoon", "id": "P2",
                  "attributen": { "te betalen belasting": 20.00 }, "rollen": { "reis": "V1" } } ] }""";
        HttpResponse<String> resp = post("/totaal-te-betalen-belasting", verzoek);
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode totaal = vind(boom(resp.body()), "V1", "attributen", "totaal te betalen belasting");
        assertNotNull(totaal, resp.body());
        assertEquals(0, new BigDecimal("50.00").compareTo(totaal.decimalValue()), resp.body());
        // exact BigDecimal render, never a double: the value round-trips as a JSON number, never a double.
        assertTrue(totaal.isNumber(), resp.body());
        assertFalse(totaal.isDouble(), "een som mag NOOIT als double renderen: " + resp.body());
    }

    // ---------------- fase 5: vintage over HTTP (G1(b)) ----------------

    @Test
    void g1b_twee_peildatums_over_http_leveren_verschillende_uitkomsten() throws Exception {
        serveer(vintageKern());
        // two requests differing ONLY in the peildatum field -> two different pinned outcomes.
        HttpResponse<String> r2025 = post("/grens",
                "{ \"peildatum\": \"2025-06-01\", \"instanties\": [ { \"type\": \"Persoon\", \"id\": \"P1\" } ] }");
        assertEquals(200, r2025.statusCode(), r2025.body());
        assertEquals(0, new BigDecimal("18").compareTo(vind(boom(r2025.body()), "P1", "attributen", "grens").decimalValue()),
                r2025.body());

        HttpResponse<String> r2026 = post("/grens",
                "{ \"peildatum\": \"2026-06-01\", \"instanties\": [ { \"type\": \"Persoon\", \"id\": \"P1\" } ] }");
        assertEquals(200, r2026.statusCode(), r2026.body());
        assertEquals(0, new BigDecimal("21").compareTo(vind(boom(r2026.body()), "P1", "attributen", "grens").decimalValue()),
                r2026.body());
    }

    @Test
    void g6_geen_peildatum_multi_vintage_over_http_is_400_veld_peildatum() throws Exception {
        serveer(vintageKern());
        HttpResponse<String> resp = post("/grens",
                "{ \"instanties\": [ { \"type\": \"Persoon\", \"id\": \"P1\" } ] }");
        assertEquals(400, resp.statusCode(), resp.body());
        boolean noemtPeildatum = false;
        for (JsonNode s : boom(resp.body()).get("schendingen"))
            if ("peildatum".equals(s.path("veld").asText())) noemtPeildatum = true;
        assertTrue(noemtPeildatum, "de 400 hoort veld 'peildatum' te noemen: " + resp.body());
    }

    @Test
    void info_toont_parametersets_met_geldigheidsranges() throws Exception {
        serveer(vintageKern());
        JsonNode info = boom(get("/info").body());
        assertTrue(info.get("parametersets").isArray(), info.toString());
        assertEquals(1, info.get("parametersets").size(), info.toString());
        JsonNode set = info.get("parametersets").get(0);
        assertEquals("leeftijden", set.get("naam").asText(), set.toString());
        JsonNode intervallen = set.get("waarden").get("volwassenleeftijd");
        assertEquals(2, intervallen.size(), set.toString());
        // first interval: 18, 2025-01-01..2025-12-31 ; second: 21, 2026-01-01..open
        assertEquals("2025-01-01", intervallen.get(0).get("vanaf").asText(), set.toString());
        assertEquals("2025-12-31", intervallen.get(0).get("tot").asText(), set.toString());
        assertEquals(0, new BigDecimal("18").compareTo(intervallen.get(0).get("waarde").decimalValue()), set.toString());
        assertEquals("2026-01-01", intervallen.get(1).get("vanaf").asText(), set.toString());
        assertTrue(intervallen.get(1).get("tot").isNull(), "open tot: " + set);
        // grensregel is 'geldig altijd' but the parameter is only valued from 2025 -> a pre-2025 coverage gap
        // WARNING is present and names the parameter (gate G3 visible on /info).
        assertTrue(info.get("periodeWaarschuwingen").isArray(), info.toString());
        assertFalse(info.get("periodeWaarschuwingen").isEmpty(),
                "geldig altijd × vanaf-2025 = een pre-2025 gat: " + info.get("periodeWaarschuwingen"));
        assertTrue(info.get("periodeWaarschuwingen").get(0).asText().contains("volwassenleeftijd"),
                info.get("periodeWaarschuwingen").toString());
    }

    // ---------------- /info ----------------

    @Test
    void info_shape_over_toka_met_per_regel_verdict() throws Exception {
        serveer(tokaKern());
        HttpResponse<String> resp = get("/info");
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        JsonNode info = boom(resp.body());

        assertTrue(info.get("dienst").has("naam"), resp.body());
        assertTrue(info.get("dienst").has("versie"), resp.body());
        assertEquals("TOKA belastingdienst", info.get("dienst").get("naam").asText(), resp.body());
        assertEquals("2.3.0", info.get("dienst").get("versie").asText(), resp.body());
        assertEquals(30, info.get("entrypoints").size(), "30 golden entrypoints");
        assertTrue(info.get("parameters").isObject(), resp.body());
        assertTrue(info.get("overgeslagen").isArray(), resp.body());
        // strict TOKA: nothing overgeslagen
        assertTrue(info.get("overgeslagen").isEmpty(), "strikte TOKA heeft geen markers: " + info.get("overgeslagen"));

        // find the bepaal-leeftijd entrypoint and assert its dekking carries a per-regel LOWERED verdict
        JsonNode ep = entrypointMet(info, "bepaal-leeftijd");
        assertNotNull(ep, resp.body());
        assertTrue(ep.get("dekking").isArray(), resp.body());
        assertTrue(ep.get("markers").isArray(), resp.body());
        assertTrue(ep.get("markers").isEmpty(), "bepaal-leeftijd lowert: " + ep.get("markers"));
        boolean heeftLowered = false;
        for (JsonNode d : ep.get("dekking")) {
            assertTrue(d.has("soort") && d.has("naam") && d.has("verdict"), d.toString());
            if ("LOWERED".equals(d.get("verdict").asText())) heeftLowered = true;
        }
        assertTrue(heeftLowered, "bepaal-leeftijd dekking hoort een LOWERED-verdict te tonen: " + ep.get("dekking"));
    }

    @Test
    void info_feitcreatie_toont_lege_dekking() throws Exception {
        serveer(tokaKern());
        JsonNode info = boom(get("/info").body());
        JsonNode ep = entrypointMet(info, "passagier-met-recht-op-treinmiles");
        assertNotNull(ep, info.toString());
        assertTrue(ep.get("dekking").isArray(), ep.toString());
        assertTrue(ep.get("dekking").isEmpty(), "een feitcreatie heeft geen dekking-entry: " + ep.get("dekking"));
        assertTrue(ep.get("markers").isEmpty(), ep.toString());
    }

    @Test
    void info_lijst_de_markers_bij_allow_markers() throws Exception {
        serveer(markerKern());
        JsonNode info = boom(get("/info").body());
        JsonNode ep = entrypointMet(info, "heffing-markert");
        assertNotNull(ep, info.toString());
        assertTrue(contains(ep.get("markers"), "heffing markert"),
                "markers hoort de marker-regel te noemen: " + ep.get("markers"));
        // and the top-level overgeslagen lists it too
        assertTrue(contains(info.get("overgeslagen"), "heffing markert"),
                "overgeslagen hoort de marker-regel te noemen: " + info.get("overgeslagen"));
        // its dekking entry carries a MARKER verdict with a reden
        boolean markerVerdict = false;
        for (JsonNode d : ep.get("dekking"))
            if ("MARKER".equals(d.path("verdict").asText())) {
                markerVerdict = true;
                assertTrue(d.has("reden"), "een MARKER-verdict hoort een reden te dragen: " + d);
            }
        assertTrue(markerVerdict, "de marker-scope hoort een MARKER-dekking te tonen: " + ep.get("dekking"));
    }

    // ---------------- negative HTTP ----------------

    @Test
    void _400_noemt_het_veld_bij_onbekend_attribuut() throws Exception {
        serveer(tokaKern());
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "bestaat-niet": 3 } } ] }""";
        HttpResponse<String> resp = post("/bepaal-leeftijd", verzoek);
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode body = boom(resp.body());
        assertEquals("verzoek valideert niet", body.get("fout").asText());
        assertTrue(body.get("schendingen").isArray() && !body.get("schendingen").isEmpty(), resp.body());
        boolean noemtVeld = false;
        for (JsonNode s : body.get("schendingen")) {
            assertTrue(s.has("veld"), s.toString());
            assertTrue(s.has("fout"), s.toString());
            if (s.get("veld").asText().contains("bestaat-niet")
                    || s.get("fout").asText().contains("bestaat-niet")) noemtVeld = true;
        }
        assertTrue(noemtVeld, "de 400 hoort het onbekende veld te noemen: " + resp.body());
    }

    @Test
    void _400_bij_malformed_json_body() throws Exception {
        serveer(tokaKern());
        HttpResponse<String> resp = post("/bepaal-leeftijd", "{ this is not json ");
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode body = boom(resp.body());
        assertEquals("body", body.get("veld").asText(), resp.body());
    }

    @Test
    void _400_bij_lege_body() throws Exception {
        serveer(tokaKern());
        HttpResponse<String> resp = post("/bepaal-leeftijd", "");
        assertEquals(400, resp.statusCode(), resp.body());
        assertEquals("body", boom(resp.body()).get("veld").asText(), resp.body());
    }

    @Test
    void _404_bij_onbekend_pad() throws Exception {
        serveer(tokaKern());
        // an unknown path -> the catch-all context 404s with a JSON body naming the pad.
        HttpResponse<String> resp = post("/dit-pad-bestaat-niet", "{ \"instanties\": [] }");
        assertEquals(404, resp.statusCode(), resp.body());
        assertTrue(boom(resp.body()).get("fout").asText().contains("dit-pad-bestaat-niet"), resp.body());
    }

    @Test
    void _405_bij_verkeerde_methode() throws Exception {
        serveer(tokaKern());
        // GET on a POST-only entry point path -> 405 naming the method.
        HttpResponse<String> resp = get("/bepaal-leeftijd");
        assertEquals(405, resp.statusCode(), resp.body());
        JsonNode body = boom(resp.body());
        assertTrue(body.get("fout").asText().contains("GET"), resp.body());
    }

    @Test
    void _500_lekt_nooit_een_stacktrace_of_geheim() throws Exception {
        // Force an internal error via a verwerk delegate that throws a RuntimeException carrying a secret. The
        // adapter must answer 500 with {"fout":"interne fout"} and NEVER echo the message or a class name.
        BeslisserKern kern = tokaKern();
        DienstHttp.Verwerker exploderend = (pad, boom) -> {
            throw new RuntimeException("geheim");
        };
        dienst = DienstHttp.bindMet(kern, exploderend, "127.0.0.1", 0).start();
        HttpResponse<String> resp = post("/bepaal-leeftijd", "{ \"instanties\": [] }");
        assertEquals(500, resp.statusCode(), resp.body());
        assertEquals("interne fout", boom(resp.body()).get("fout").asText(), resp.body());
        assertFalse(resp.body().contains("geheim"), "het geheim mag NOOIT in de body lekken: " + resp.body());
        assertFalse(resp.body().contains("RuntimeException"), "geen class-naam in de body: " + resp.body());
        assertFalse(resp.body().contains("Exception"), "geen class-naam in de body: " + resp.body());
    }

    // ---- helpers ----

    private static JsonNode vind(JsonNode antwoord, String id, String groep, String lid) {
        for (JsonNode inst : antwoord.get("instanties"))
            if (id.equals(inst.path("id").asText()))
                return inst.path(groep).get(lid);
        return null;
    }

    private static JsonNode entrypointMet(JsonNode info, String pad) {
        for (JsonNode ep : info.get("entrypoints"))
            if (pad.equals(ep.path("pad").asText())) return ep;
        return null;
    }

    private static boolean contains(JsonNode array, String waarde) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode n : array) if (waarde.equals(n.asText())) return true;
        return false;
    }
}
