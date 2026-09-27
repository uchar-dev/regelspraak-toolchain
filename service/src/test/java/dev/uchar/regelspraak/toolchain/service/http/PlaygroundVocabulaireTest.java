package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.engine.Naamgeving;
import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.TokaBron;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The playground in a vocabulary: uchar's golden {@code jutterswet-vocabulaires} (the submodule) with an own scenario
 * block, analysed in {@code cgm}. {@code graafJson} labels the form nodes and the element nodes (member and declaring
 * type) by their names there, the scenario's trace, coverage and explanation name the rules and declarations there,
 * and the Mermaid graph, the IR and the verdict stay uchar's. An unknown vocabulary is refused, over HTTP with 400.
 */
class PlaygroundVocabulaireTest {

    private static String BRON;

    @BeforeAll
    static void lees() throws IOException {
        BRON = Files.readString(TokaBron.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs"))
                + "\n" + Files.readString(Path.of("src/test/resources/vocabulaires/jutterswet-vocabulaires.scenario.rgs"));
    }

    private static List<String> labels(ObjectNode a) {
        List<String> uit = new ArrayList<>();
        for (JsonNode n : a.at("/graafJson/nodes")) uit.add(n.get("label").asText());
        return uit;
    }

    @Test
    void graaf_trace_en_uitleg_in_het_vocabulaire() {
        ObjectNode cgm = Playground.analyseer(BRON, "cgm");
        ObjectNode canoniek = Playground.analyseer(BRON);
        List<String> labels = labels(cgm);
        assertTrue(labels.contains("R_PREMIE_VONDST"), labels.toString());
        assertTrue(labels.contains("VONDST_BEDRAG · JUTTER"), labels.toString());
        assertTrue(labels(canoniek).contains("vondstbedrag · jutter"), labels(canoniek).toString());
        assertEquals(labels(canoniek).size(), labels.size());
        assertEquals(canoniek.get("graaf"), cgm.get("graaf"), "de Mermaid-graaf is uchars eigen weergave");
        assertEquals(canoniek.get("ir"), cgm.get("ir"));

        JsonNode scenario = cgm.at("/scenario/resultaten/0");
        assertEquals("PASS", scenario.get("status").asText(), scenario.toString());
        assertEquals("R_PREMIE_VONDST", scenario.at("/trace/0/regel").asText(), scenario.toString());
        assertTrue(scenario.get("dekking").toString().contains("\"R_PREMIEWAARDIG\""), scenario.toString());
        assertTrue(scenario.get("uitleg").toString().contains("V1 is WAARDEVOL, omdat zijn soort (BARNSTEEN) is gelijk"
                + " aan BARNSTEEN"), scenario.get("uitleg").toString());
        assertEquals("premie van een vondst", canoniek.at("/scenario/resultaten/0/trace/0/regel").asText());
    }

    @Test
    void een_onbekend_vocabulaire_wordt_geweigerd() throws Exception {
        assertThrows(Naamgeving.Vocabulairefout.class, () -> Playground.analyseer(BRON, "wetboek"));
        String descriptor = """
            { "dienst": {"naam":"playground","versie":"1"}, "entrypoints": [ { "naam":"premie","pad":"premie",
              "scope": {"regelgroepen": ["premieberekening"]}, "invoer": ["Jutter"], "uitvoer": ["Jutter.vondstbedrag"] } ],
              "parameters": {} }""";
        BeslisserKern kern = BeslisserKern.bouw(descriptor, Map.of("b.rgs", BRON.substring(0, BRON.indexOf("Scenario"))),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
        DienstHttp dienst = DienstHttp.bindMet(kern, kern::verwerk, "127.0.0.1", 0, true).start();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + dienst.poort() + "/playground/analyse?vocabulaire=wetboek"))
                    .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(BRON,
                            StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, r.statusCode(), r.body());
            JsonNode body = new ObjectMapper().readTree(r.body());
            assertEquals("ONBEKEND_VOCABULAIRE", body.at("/schendingen/0/soort").asText(), r.body());
            assertEquals("vocabulaire", body.at("/schendingen/0/veld").asText(), r.body());

            HttpResponse<String> ok = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + dienst.poort() + "/playground/analyse?vocabulaire=handboek"))
                    .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(BRON,
                            StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(ok.body().contains("\"premie per vondst\""), ok.body());
        } finally {
            dienst.stop(0);
        }
    }
}
