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
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Quarkus adapter and {@code DienstHttp} over one kern with vocabularies — uchar's golden
 * {@code jutterswet-vocabulaires} (the submodule) — give the same {@code /info}, with its {@code vocabulaires} block
 * and an entry point's default vocabulary, the same answer in a chosen vocabulary, and the same 400 for an ambiguous
 * reading of an input vocabulary pinned elsewhere and for an unknown vocabulary; a plain JUnit test, as
 * {@link InfoParityTest} and {@link FoutParityTest}.
 */
class VocabulaireParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
    private static final String DESCRIPTOR = """
            { "dienst": {"naam":"vocabulaires","versie":"1"},
              "entrypoints": [
                { "naam":"premie","pad":"premie","scope": {"regelgroepen": ["premieberekening"]},
                  "invoer": ["Jutter"], "uitvoer": ["Jutter.vondstbedrag", "Vondst.soort"] },
                { "naam":"premie cgm","pad":"premie-cgm","scope": {"regelgroepen": ["premieberekening"]},
                  "invoer": ["Jutter"], "uitvoer": ["Jutter.vondstbedrag"], "vocabulaire": "cgm" } ],
              "parameters": {} }""";
    private static final String VERZOEK = """
            { "instanties": [ { "type": "strandjutter", "id": "J1", "rollen": { "GEMELDE_VONDST": ["V1"] } },
                              { "type": "Vondst", "id": "V1", "attributen": { "soort": "amber", "gewicht": 2 } } ],
              "parameters": { "premietarief": 5 } }""";

    private static BeslisserKern kern;
    private static DienstHttp dienst;

    @BeforeAll
    static void bouw() throws Exception {
        String bron = Files.readString(TokaBron.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs"));
        kern = BeslisserKern.bouw(DESCRIPTOR, Map.of("bron.rgs", bron), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader());
        dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (dienst != null) dienst.stop(0);
    }

    @Test
    void info_met_vocabulaires_is_gelijk() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + dienst.poort() + "/info")).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode jdk = MAPPER.readTree(r.body());
        assertEquals(jdk, InfoBouwer.bouw(kern));
        assertEquals(2, jdk.get("vocabulaires").size(), r.body());
        assertEquals("cgm", jdk.at("/entrypoints/1/vocabulaire").asText(), r.body());
    }

    @Test
    void een_antwoord_in_een_vocabulaire_is_gelijk() throws Exception {
        JsonNode body = vergelijk("premie", "handboek", null, 200);
        assertEquals("amber", body.at("/instanties/1/attributen/soort vondst").asText(), body.toString());
        vergelijk("premie-cgm", null, null, 200);
    }

    @Test
    void de_weigeringen_van_vocabulaires_zijn_gelijk() throws Exception {
        assertEquals("ONBEKEND_VOCABULAIRE", vergelijk("premie", "wetboek", null, 400).at("/schendingen/0/soort").asText());
        JsonNode vast = vergelijk("premie", null, "cgm", 400);
        assertTrue(vast.get("schendingen").toString().contains("(invoervocabulaire 'cgm')"), vast.toString());
    }

    /** The same POST to both adapters: the same status ({@code verwacht}) and body, which it returns. */
    private static JsonNode vergelijk(String pad, String vocabulaire, String invoervocabulaire, int verwacht)
            throws Exception {
        BeslisResource resource = new BeslisResource();
        resource.kern = kern;
        Response quarkus = resource.verwerk(pad, null, vocabulaire, invoervocabulaire, VERZOEK);
        String query = (vocabulaire == null ? "" : "vocabulaire=" + vocabulaire)
                + (invoervocabulaire == null ? "" : (vocabulaire == null ? "" : "&") + "invoervocabulaire=" + invoervocabulaire);
        HttpResponse<String> jdk = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + dienst.poort() + "/" + pad + (query.isEmpty() ? "" : "?" + query)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(VERZOEK)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(verwacht, jdk.statusCode(), jdk.body());
        assertEquals(verwacht, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        JsonNode body = MAPPER.readTree(jdk.body());
        assertEquals(body, MAPPER.readTree((String) quarkus.getEntity()));
        return body;
    }
}
