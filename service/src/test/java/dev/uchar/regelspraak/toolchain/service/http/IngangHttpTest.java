package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.KernConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service fills, fires and reads every session through uchar's entry ({@code DroolsBackend.voegIn}, {@code vuur},
 * {@code zetAfgeleideWaarden}): the answer carries the value a rule derived. Own material: one model of finds with an
 * entry point per rule set.
 */
class IngangHttpTest {

    private static final String BRON = """
        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (getal met 2 decimalen);
            het aantal delen Numeriek (geheel getal);
            het deelgewicht Numeriek (getal met 2 decimalen);
            het vindersloon Numeriek (getal met 2 decimalen);
            het vondstjaar Numeriek (geheel getal);

        Regel deelgewicht van een vondst
            geldig altijd
                Het deelgewicht van een Vondst moet berekend worden als zijn gewicht gedeeld door zijn aantal delen.

        Regel loon voor een zware vondst
            geldig altijd
                Het vindersloon van een Vondst moet gesteld worden op 25,00
                indien zijn gewicht groter is dan 5,00.

        Regel loon voor een grote vondst
            geldig altijd
                Het vindersloon van een Vondst moet gesteld worden op 10,00
                indien zijn gewicht groter is dan 3,00.

        Regel vondstjaar van een vondst
            geldig altijd
                Het vondstjaar van een Vondst moet gesteld worden op Rekenjaar.
        """;

    private static final String DESCRIPTOR = """
        { "dienst": {"naam":"vondsten","versie":"1"},
          "entrypoints": [
            { "naam":"deelgewicht","pad":"deelgewicht",
              "scope": {"regelnamen": ["deelgewicht van een vondst"]},
              "invoer": ["Vondst"], "uitvoer": ["Vondst.deelgewicht"] },
            { "naam":"vindersloon","pad":"vindersloon",
              "scope": {"regelnamen": ["loon voor een zware vondst", "loon voor een grote vondst"]},
              "invoer": ["Vondst"], "uitvoer": ["Vondst.vindersloon"] },
            { "naam":"vondstjaar","pad":"vondstjaar",
              "scope": {"regelnamen": ["vondstjaar van een vondst"]},
              "invoer": ["Vondst"], "uitvoer": ["Vondst.vondstjaar"] },
            { "naam":"alles","pad":"alles",
              "scope": {"regelnamen": ["deelgewicht van een vondst", "loon voor een zware vondst",
                                       "loon voor een grote vondst", "vondstjaar van een vondst"]},
              "invoer": ["Vondst"], "uitvoer": ["Vondst.deelgewicht", "Vondst.vindersloon", "Vondst.vondstjaar"] } ],
          "parameters": {} }""";

    private static DienstHttp dienst;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        BeslisserKern kern = BeslisserKern.bouw(DESCRIPTOR, Map.of("vondsten.rgs", BRON), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader());
        dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (dienst != null) dienst.stop(0);
    }

    // ---------------- (c) an answer with a derived value ----------------

    @Test
    void het_antwoord_geeft_de_afgeleide_waarde() throws Exception {
        HttpResponse<String> resp = post("/deelgewicht", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00,"aantal delen":3}}]}""");
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode deelgewicht = vind(boom(resp.body()), "V1", "deelgewicht");
        assertNotNull(deelgewicht, resp.body());
        assertTrue(deelgewicht.isNumber(), resp.body());
        assertEquals(0, new BigDecimal("2.00").compareTo(deelgewicht.decimalValue()), resp.body());
    }

    // ---------------- helpers ----------------

    private static HttpResponse<String> post(String pad, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + dienst.poort() + pad))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode boom(String json) throws Exception {
        return DienstHttp.MAPPER.readTree(json);
    }

    private static JsonNode vind(JsonNode antwoord, String id, String attribuut) {
        for (JsonNode inst : antwoord.get("instanties"))
            if (id.equals(inst.path("id").asText())) return inst.path("attributen").get(attribuut);
        return null;
    }
}
