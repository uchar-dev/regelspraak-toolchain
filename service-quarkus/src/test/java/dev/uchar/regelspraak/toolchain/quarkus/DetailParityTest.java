package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.TokaBron;
import dev.uchar.regelspraak.toolchain.service.http.DienstHttp;

import jakarta.ws.rs.core.Response;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Quarkus adapter and {@code DienstHttp} over one kern give the same body with the detail flags {@code trace},
 * {@code dekking} and {@code fixpunt}: for an answer, for a 422 whose fixpoint carries the fault, for an answer in a
 * vocabulary (uchar's golden {@code jutterswet-vocabulaires}), and for the 400 of a kern started without detail. A
 * plain JUnit test, as {@link FoutParityTest}: the resource is called directly with the kern set, and
 * {@code DienstHttp} is asked over HTTP on an ephemeral port.
 */
class DetailParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    /** A division by the helpers, a fault at zero. */
    private static final String BERGING = """
            Objecttype de Berging (mv: Bergingen)
                de opbrengst Numeriek (geheel getal);
                het helpersaantal Numeriek (geheel getal);
                het helpersdeel Numeriek (geheel getal);
            Regel helpersdeel van een berging
            geldig altijd
            Het helpersdeel van een Berging moet berekend worden als de opbrengst van de Berging gedeeld door het helpersaantal van de Berging.
            """;
    private static final String BERGING_DESCRIPTOR = """
            { "dienst": {"naam":"berging","versie":"1"},
              "entrypoints": [ { "naam":"berging","pad":"berging",
                "scope": {"regelnamen": ["helpersdeel van een berging"]},
                "invoer": ["Berging"], "uitvoer": ["Berging.helpersdeel"] } ],
              "parameters": {} }""";
    private static final String VOCABULAIRE_DESCRIPTOR = """
            { "dienst": {"naam":"vocabulaires","versie":"1"},
              "entrypoints": [
                { "naam":"premie cgm","pad":"premie-cgm","scope": {"regelgroepen": ["premieberekening"]},
                  "invoer": ["Jutter"], "uitvoer": ["Jutter.vondstbedrag"], "vocabulaire": "cgm" } ],
              "parameters": {} }""";
    private static final String VONDST = """
            { "instanties": [ { "type": "strandjutter", "id": "J1", "rollen": { "GEMELDE_VONDST": ["V1"] } },
                              { "type": "Vondst", "id": "V1", "attributen": { "soort": "amber", "gewicht": 2 } } ],
              "parameters": { "premietarief": 5 } }""";
    private static final String ALLE = "trace=true&dekking=true&fixpunt=true";

    private static final List<DienstHttp> DIENSTEN = new ArrayList<>();
    private static Paar berging;
    private static Paar zonderDetail;
    private static Paar vocabulaires;

    private record Paar(BeslisserKern kern, DienstHttp dienst) {}

    @BeforeAll
    static void bouw() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        berging = paar(BeslisserKern.bouw(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                KernConfig.strikt().metDetail(), cl));
        zonderDetail = paar(BeslisserKern.bouw(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                KernConfig.strikt(), cl));
        String bron = Files.readString(TokaBron.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs"));
        vocabulaires = paar(BeslisserKern.bouw(VOCABULAIRE_DESCRIPTOR, Map.of("bron.rgs", bron),
                KernConfig.strikt().metDetail(), cl));
    }

    private static Paar paar(BeslisserKern kern) throws Exception {
        DienstHttp dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        DIENSTEN.add(dienst);
        return new Paar(kern, dienst);
    }

    @AfterAll
    static void stop() {
        for (DienstHttp d : DIENSTEN) d.stop(0);
    }

    @Test
    void een_antwoord_met_de_vlaggen_is_gelijk() throws Exception {
        JsonNode body = vergelijk(berging, "berging", ALLE, verzoek(2), 200);
        assertEquals("B", body.at("/trace/0/labels/0").asText(), body.toString());
        assertEquals("GEVUURD", body.at("/dekking/0/soort").asText(), body.toString());
        assertEquals("afgeleid", body.at("/fixpunt/objecten/0/waarden/2/herkomst").asText(), body.toString());
    }

    @Test
    void een_422_met_het_fixpunt_is_gelijk() throws Exception {
        JsonNode body = vergelijk(berging, "berging", "fixpunt", verzoek(0), 422);
        assertEquals("DELING_DOOR_NUL", body.at("/fixpunt/fouten/0/soort").asText(), body.toString());
    }

    @Test
    void een_antwoord_in_een_vocabulaire_met_de_vlaggen_is_gelijk() throws Exception {
        JsonNode body = vergelijk(vocabulaires, "premie-cgm", ALLE, VONDST, 200);
        assertTrue(body.get("fixpunt").toString().contains("\"objecttype\":\"VONDST\",\"objecttypeCanoniek\":\"Vondst\""),
                body.toString());
    }

    @Test
    void zonder_detail_is_de_400_gelijk() throws Exception {
        JsonNode body = vergelijk(zonderDetail, "berging", "dekking=true&fixpunt", verzoek(2), 400);
        assertEquals("dekking", body.at("/schendingen/0/veld").asText(), body.toString());
        assertEquals("fixpunt", body.at("/schendingen/1/veld").asText(), body.toString());
    }

    private static String verzoek(int helpers) {
        return """
                { "instanties": [ { "type": "Berging", "id": "B",
                                    "attributen": { "opbrengst": 100, "helpersaantal": %d } } ] }""".formatted(helpers);
    }

    /** The same POST to both adapters: the same status ({@code verwacht}) and body, which it returns. The query holds
     *  only detail flags, each {@code naam=true} or bare. */
    private static JsonNode vergelijk(Paar p, String pad, String query, String body, int verwacht) throws Exception {
        BeslisResource resource = new BeslisResource();
        resource.kern = p.kern();
        String trace = null, dekking = null, fixpunt = null;
        for (String deel : query.split("&")) {
            String naam = deel.contains("=") ? deel.substring(0, deel.indexOf('=')) : deel;
            String waarde = deel.contains("=") ? deel.substring(deel.indexOf('=') + 1) : "";
            switch (naam) {
                case "trace" -> trace = waarde;
                case "dekking" -> dekking = waarde;
                case "fixpunt" -> fixpunt = waarde;
                default -> throw new IllegalArgumentException("geen detailvlag: " + deel);
            }
        }
        Response quarkus = resource.verwerk(pad, null, null, null, trace, dekking, fixpunt, body);
        HttpResponse<String> jdk = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + p.dienst().poort() + "/" + pad + "?" + query))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(verwacht, jdk.statusCode(), jdk.body());
        assertEquals(verwacht, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        assertEquals(jdk.body(), quarkus.getEntity(), "JDK en Quarkus geven hetzelfde lichaam, byte voor byte");
        return MAPPER.readTree(jdk.body());
    }
}
