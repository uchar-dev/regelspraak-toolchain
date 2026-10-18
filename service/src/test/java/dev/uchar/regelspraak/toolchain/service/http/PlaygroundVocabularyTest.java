package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

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
 * block, analysed in {@code cgm}. {@code graphJson} labels the form nodes and the element nodes (member and declaring
 * type) by their names there, the scenario's trace, coverage and explanation name the rules and declarations there,
 * and the Mermaid graph, the IR and the verdict stay uchar's. An unknown vocabulary is refused, over HTTP with 400.
 */
class PlaygroundVocabularyTest {

    private static String SOURCE;

    @BeforeAll
    static void read() throws IOException {
        SOURCE = Files.readString(TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs"))
                + "\n" + Files.readString(Path.of("src/test/resources/vocabularies/jutterswet-vocabulaires.scenario.rgs"));
    }

    private static List<String> labels(ObjectNode a) {
        List<String> sink = new ArrayList<>();
        for (JsonNode n : a.at("/graphJson/nodes")) sink.add(n.get("label").asText());
        return sink;
    }

    @Test
    void graph_trace_and_uitleg_in_the_vocabulary() {
        ObjectNode cgm = Playground.analyseer(SOURCE, "cgm");
        ObjectNode canonical = Playground.analyseer(SOURCE);
        List<String> labels = labels(cgm);
        assertTrue(labels.contains("R_PREMIE_VONDST"), labels.toString());
        assertTrue(labels.contains("VONDST_BEDRAG · JUTTER"), labels.toString());
        assertTrue(labels(canonical).contains("vondstbedrag · jutter"), labels(canonical).toString());
        assertEquals(labels(canonical).size(), labels.size());
        assertEquals(canonical.get("graph"), cgm.get("graph"), "de Mermaid-graaf is uchars eigen weergave");
        assertEquals(canonical.get("ir"), cgm.get("ir"));

        JsonNode scenario = cgm.at("/scenario/results/0");
        assertEquals("PASS", scenario.get("status").asText(), scenario.toString());
        assertEquals("R_VONDSTBEDRAG", scenario.at("/trace/0/rule").asText(), scenario.toString());
        assertTrue(scenario.get("coverage").toString().contains("\"R_PREMIEWAARDIG\""), scenario.toString());
        assertTrue(scenario.get("explanation").toString().contains("V1 is WAARDEVOL, omdat zijn soort (BARNSTEEN) is gelijk"
                + " aan BARNSTEEN"), scenario.get("explanation").toString());
        assertEquals("vondstbedrag van een jutter", canonical.at("/scenario/results/0/trace/0/rule").asText());
    }

    @Test
    void an_unknown_vocabulary_is_refused() throws Exception {
        assertThrows(Naming.VocabularyError.class, () -> Playground.analyseer(SOURCE, "wetboek"));
        String descriptor = """
            { "service": {"name":"playground","version":"1"}, "entrypoints": [ { "name":"premie","path":"premie",
              "scope": {"ruleGroups": ["premieberekening"]}, "input": ["Jutter"], "output": ["Jutter.vondstbedrag"] } ],
              "parameters": {} }""";
        DecisionCore core = DecisionCore.assemble(descriptor, Map.of("b.rgs", SOURCE.substring(0, SOURCE.indexOf("Scenario"))),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        ServiceHttp service = ServiceHttp.bindWith(core, core::process, "127.0.0.1", 0, true).start();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + service.port() + "/playground/analyse?vocabulary=wetboek"))
                    .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(SOURCE,
                            StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, r.statusCode(), r.body());
            JsonNode body = new ObjectMapper().readTree(r.body());
            assertEquals("UNKNOWN_VOCABULARY", body.at("/violations/0/kind").asText(), r.body());
            assertEquals("vocabulary", body.at("/violations/0/field").asText(), r.body());

            HttpResponse<String> ok = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + service.port() + "/playground/analyse?vocabulary=handboek"))
                    .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(SOURCE,
                            StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(ok.body().contains("\"premie per vondst\""), ok.body());
        } finally {
            service.stop(0);
        }
    }
}
