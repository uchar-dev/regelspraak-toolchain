package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

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
 * The Quarkus adapter and {@code ServiceHttp} over one kern with vocabularies — uchar's golden
 * {@code jutterswet-vocabulaires} (the submodule) — give the same {@code /info}, with its {@code vocabulaires} block
 * and an entry point's default vocabulary, the same answer in a chosen vocabulary, and the same 400 for an ambiguous
 * reading of an input vocabulary pinned elsewhere and for an unknown vocabulary; a plain JUnit test, as
 * {@link InfoParityTest} and {@link ErrorParityTest}.
 */
class VocabularyParityTest {

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
    private static final String REQUEST = """
            { "instanties": [ { "type": "strandjutter", "id": "J1", "rollen": { "GEMELDE_VONDST": ["V1"] } },
                              { "type": "Vondst", "id": "V1", "attributen": { "soort": "amber", "gewicht": 2 } } ],
              "parameters": { "premietarief": 5 } }""";

    private static DecisionCore core;
    private static ServiceHttp service;

    @BeforeAll
    static void assemble() throws Exception {
        String source = Files.readString(TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs"));
        core = DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", source), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
    }

    @Test
    void info_with_vocabularies_is_equal() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + service.port() + "/info")).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode jdk = MAPPER.readTree(r.body());
        assertEquals(jdk, InfoBuilder.assemble(core));
        assertEquals(2, jdk.get("vocabulaires").size(), r.body());
        assertEquals("cgm", jdk.at("/entrypoints/1/vocabulaire").asText(), r.body());
    }

    @Test
    void an_answer_in_a_vocabulary_is_equal() throws Exception {
        JsonNode body = compare("premie", "handboek", null, 200);
        assertEquals("amber", body.at("/instanties/1/attributen/soort vondst").asText(), body.toString());
        compare("premie-cgm", null, null, 200);
    }

    @Test
    void the_refusals_of_vocabularies_are_equal() throws Exception {
        assertEquals("ONBEKEND_VOCABULAIRE", compare("premie", "wetboek", null, 400).at("/schendingen/0/soort").asText());
        JsonNode fixed = compare("premie", null, "cgm", 400);
        assertTrue(fixed.get("schendingen").toString().contains("(invoervocabulaire 'cgm')"), fixed.toString());
    }

    /** The same POST to both adapters: the same status ({@code verwacht}) and body, which it returns. */
    private static JsonNode compare(String path, String vocabulary, String invoervocabulaire, int expected)
            throws Exception {
        DecideResource resource = new DecideResource();
        resource.core = core;
        Response quarkus = resource.process(path, null, vocabulary, invoervocabulaire, REQUEST);
        String query = (vocabulary == null ? "" : "vocabulaire=" + vocabulary)
                + (invoervocabulaire == null ? "" : (vocabulary == null ? "" : "&") + "invoervocabulaire=" + invoervocabulaire);
        HttpResponse<String> jdk = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + service.port() + "/" + path + (query.isEmpty() ? "" : "?" + query)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(REQUEST)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, jdk.statusCode(), jdk.body());
        assertEquals(expected, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        JsonNode body = MAPPER.readTree(jdk.body());
        assertEquals(body, MAPPER.readTree((String) quarkus.getEntity()));
        return body;
    }
}
