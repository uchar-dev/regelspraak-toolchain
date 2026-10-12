package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.service.AntwoordBouwer;
import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.Evaluatiefout;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.Uitvoerder;
import dev.uchar.regelspraak.toolchain.service.Wereld;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service fills, fires and reads every session through uchar's entry ({@code DroolsBackend.voegIn}, {@code vuur},
 * {@code zetAfgeleideWaarden}): the answer carries the value a rule derived, and an evaluation fault that stands at the
 * fixpoint is a 422 that gives per fault its kind and uchar's sentence, never a stacktrace. Own material: one model of
 * finds with an entry point per rule set, so each request meets the fault it is about and no other.
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

    /** An own total without a clip over an attribute on a timeline, which reads the asOfDate-bucket scalar. */
    private static final String BEWARING_BRON = """
        Eenheidsysteem Munt
            de euro (mv: euros) EUR €

        Eenheidsysteem Bewaartijd
            de maand mnd

        Objecttype de Jutter (mv: Jutters) (bezield)
            de bewaarvergoeding per maand Numeriek (geheel getal) met eenheid €/mnd voor elke maand;
            het bewaargeld Numeriek (geheel getal) met eenheid €;

        Regel bewaargeld van een jutter
            geldig altijd
                Het bewaargeld van een Jutter moet berekend worden als het totaal van zijn bewaarvergoeding per maand.
        """;

    private static final String BEWARING_DESCRIPTOR = """
        { "dienst": {"naam":"bewaring","versie":"1"},
          "entrypoints": [ { "naam":"bewaargeld","pad":"bewaargeld",
            "scope": {"regelnamen": ["bewaargeld van een jutter"]},
            "invoer": ["Jutter"], "uitvoer": ["Jutter.bewaargeld"] } ],
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

    // ---------------- (d) an evaluation fault per kind: 422 ----------------

    @Test
    void een_deling_door_nul_is_422_deling_door_nul() throws Exception {
        JsonNode body = evaluatiefout(post("/deelgewicht", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00,"aantal delen":0}}]}"""));
        assertEquals("de regels geven op deze invoer een evaluatiefout", body.get("fout").asText(), body.toString());
        assertEquals(List.of("DELING_DOOR_NUL"), soorten(body), body.toString());
        assertEquals("deling door nul in de regel \"deelgewicht van een vondst\": 6 gedeeld door 0 — een numerieke nul"
                + " als deler is een fout, geen leeg (RUNTIME-SEMANTICS ch. 1.4, punt 4)",
                body.at("/fouten/0/fout").asText(), body.toString());
    }

    @Test
    void een_rekendatum_die_ontbreekt_is_422_rekenfout() throws Exception {
        JsonNode body = evaluatiefout(post("/vondstjaar", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{}}]}"""));
        assertEquals(List.of("REKENFOUT"), soorten(body), body.toString());
        assertTrue(body.at("/fouten/0/fout").asText().contains("vondstjaar van een vondst")
                && body.at("/fouten/0/fout").asText().contains("evaluation date"), body.toString());
    }

    @Test
    void twee_schrijvers_die_verschillen_zijn_422_schrijversconflict_met_het_id_uit_het_verzoek() throws Exception {
        JsonNode body = evaluatiefout(post("/vindersloon", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00}}]}"""));
        assertEquals(List.of("SCHRIJVERSCONFLICT"), soorten(body), body.toString());
        String zin = body.at("/fouten/0/fout").asText();
        assertTrue(zin.contains("\"loon voor een zware vondst\" schrijft 25") && zin.contains("\"loon voor een grote"
                + " vondst\" schrijft 10") && zin.contains("in het attribuut vindersloon van V1, een Vondst"), zin);
    }

    @Test
    void twee_fouten_tegelijk_zijn_een_422_met_een_element_per_fout() throws Exception {
        JsonNode body = evaluatiefout(post("/alles", """
            {"peildatum":"2026-03-15",
             "instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00,"aantal delen":0}}]}"""));
        assertEquals("de regels geven op deze invoer 2 evaluatiefouten", body.get("fout").asText(), body.toString());
        assertEquals(List.of("DELING_DOOR_NUL", "SCHRIJVERSCONFLICT"), soorten(body), body.toString());
        assertTrue(body.at("/fouten/0/fout").asText().startsWith("deling door nul in de regel \"deelgewicht van een"
                + " vondst\""), body.toString());
        assertTrue(body.at("/fouten/1/fout").asText().contains("van V1, een Vondst"), body.toString());
    }

    /** A timeline of a request is refused (NIET_ONDERSTEUND), so no request reaches an input in the other convention;
     *  the service's executor reaches it with a world whose bean holds a full timeline where the rule takes the
     *  asOfDate-bucket scalar, and the fault becomes the body both adapters send with the 422. */
    @Test
    void een_tijdlijninvoer_in_de_andere_conventie_is_een_evaluatiefout_invoervormfout() throws Exception {
        BeslisserKern kern = BeslisserKern.bouw(BEWARING_DESCRIPTOR, Map.of("bewaring.rgs", BEWARING_BRON),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
        DienstHttp bewaring = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = post(bewaring, "/bewaargeld", """
                {"peildatum":"2026-03-15",
                 "instanties":[{"type":"Jutter","id":"J1","attributen":{"bewaarvergoeding per maand":10}}]}""");
            assertEquals(400, resp.statusCode(), resp.body());
            assertEquals("NIET_ONDERSTEUND", boom(resp.body()).at("/schendingen/0/soort").asText(), resp.body());
        } finally {
            bewaring.stop(0);
        }

        BeslisserKern.Entrypoint ep = kern.entrypoint("bewaargeld");
        ClassLoader cl = ep.beanLoader();
        Object jutter = Class.forName(FactModelGenerator.PAKKET + ".Jutter", true, cl).getConstructor().newInstance();
        Object segment = Class.forName(FactModelGenerator.PAKKET + ".Segment", true, cl)
                .getConstructor(LocalDate.class, LocalDate.class, BigDecimal.class)
                .newInstance(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), new BigDecimal(10));
        jutter.getClass().getMethod("setBewaarvergoedingPerMaand", List.class).invoke(jutter, List.of(segment));
        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("J1", jutter);
        Evaluatiefout fout = assertThrows(Evaluatiefout.class, () -> new Uitvoerder(ep.build(), ep.defaultGlobals())
                .vuur(new Wereld(beans, Map.of(), LocalDate.of(2026, 3, 15))));
        JsonNode body = AntwoordBouwer.evaluatiefout(fout);
        assertEquals("EVALUATIEFOUT", body.path("status").asText(), body.toString());
        assertEquals(List.of("INVOERVORMFOUT"), soorten(body), body.toString());
        String zin = body.at("/fouten/0/fout").asText();
        assertTrue(zin.startsWith("fout in de regel \"bewaargeld van een jutter\"") && zin.contains("\"J1\"")
                && zin.contains("supplies a full timeline"), zin);
    }

    // ---------------- helpers ----------------

    /** The body of a 422: status EVALUATIEFOUT, a sentence, and per fault its kind and uchar's sentence, without a
     *  stacktrace. */
    private static JsonNode evaluatiefout(HttpResponse<String> resp) throws Exception {
        assertEquals(422, resp.statusCode(), resp.body());
        JsonNode body = boom(resp.body());
        assertEquals("EVALUATIEFOUT", body.path("status").asText(), resp.body());
        assertTrue(body.path("fouten").isArray() && !body.path("fouten").isEmpty(), resp.body());
        assertEquals(List.of("fout", "fouten", "status"), velden(body), resp.body());
        for (JsonNode f : body.get("fouten")) assertEquals(List.of("fout", "soort"), velden(f), resp.body());
        assertFalse(resp.body().contains("Exception") || resp.body().contains("\tat "), "geen stacktrace: " + resp.body());
        return body;
    }

    private static List<String> soorten(JsonNode body) {
        return body.get("fouten").valueStream().map(f -> f.get("soort").asText()).toList();
    }

    private static List<String> velden(JsonNode n) {
        List<String> uit = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(uit::add);
        return uit.stream().sorted().toList();
    }

    private static HttpResponse<String> post(String pad, String body) throws Exception {
        return post(dienst, pad, body);
    }

    private static HttpResponse<String> post(DienstHttp naar, String pad, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + naar.poort() + pad))
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
