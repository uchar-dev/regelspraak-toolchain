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
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Quarkus adapter and {@code DienstHttp} over one kern answer a request whose session reaches no fixpoint with the
 * same 422 and the same body, byte for byte, without and with the detail flags: uchar's red golden
 * {@code jutterswet-budgetgrens-over}, whose chain from a Wachter of rang 1 passes uchar's creation budget. A plain
 * JUnit test, as {@link FoutParityTest} and {@link DetailParityTest}: the resource is called directly with the kern
 * set, and {@code DienstHttp} is asked over HTTP on an ephemeral port.
 */
class GeenFixpuntParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    private static final Path ROOD = TokaBron.REPO.resolve("uchar/corpus/rood/jutterswet-budgetgrens-over");
    private static final String DESCRIPTOR = """
            { "dienst": {"naam":"estafette","versie":"1"},
              "entrypoints": [ { "naam":"estafette","pad":"estafette",
                "scope": {"regelnamen": ["opvolgerrang van een wachter", "rangplafond", "eerste keten aan het plafond",
                                         "tweede keten aan het plafond", "werf de lange keten"]},
                "invoer": ["Wachter"], "uitvoer": ["Wachter.opvolgerrang"] } ],
              "parameters": {} }""";
    private static final String VERZOEK =
            "{\"instanties\":[{\"type\":\"Wachter\",\"id\":\"W1\",\"attributen\":{\"rang\":1,\"ketennummer\":1}}]}";

    private static BeslisserKern kern;
    private static DienstHttp dienst;
    /** The sentence uchar pins for the Drools branch on this chain, in its own red case. */
    private static String fragment;

    @BeforeAll
    static void bouw() throws Exception {
        kern = BeslisserKern.bouw(DESCRIPTOR, Map.of("bron.rgs", Files.readString(ROOD.resolve("bron.rgs"))),
                KernConfig.strikt().metDetail(), Thread.currentThread().getContextClassLoader());
        dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        fragment = MAPPER.readTree(Files.readString(ROOD.resolve("case1.json"))).at("/expectedFault/drools").asText();
    }

    @AfterAll
    static void stop() {
        if (dienst != null) dienst.stop(0);
    }

    @Test
    void zonder_vlag_dezelfde_422() throws Exception {
        JsonNode body = vergelijk(null, null, null, "");
        assertEquals("GEEN_FIXPUNT", body.path("status").asText(), body.toString());
        assertTrue(body.path("fout").asText().startsWith(fragment), body.toString());
    }

    @Test
    void met_de_detailvlaggen_dezelfde_422() throws Exception {
        JsonNode body = vergelijk("true", "true", "true", "?trace=true&dekking=true&fixpunt=true");
        assertEquals("GEEN_FIXPUNT", body.path("status").asText(), body.toString());
        assertTrue(body.path("trace").size() > 0, body.toString());
        assertEquals(body.path("fout"), body.path("fixpuntFout"), body.toString());
    }

    /** The same POST to both adapters: a 422 from each and the same body, which it returns. */
    private static JsonNode vergelijk(String trace, String dekking, String fixpunt, String query) throws Exception {
        BeslisResource resource = new BeslisResource();
        resource.kern = kern;
        Response quarkus = resource.verwerk("estafette", null, null, null, trace, dekking, fixpunt, VERZOEK);
        HttpResponse<String> jdk = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + dienst.poort() + "/estafette" + query))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(VERZOEK, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(422, jdk.statusCode(), jdk.body());
        assertEquals(422, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        assertEquals(jdk.body(), quarkus.getEntity(), "JDK en Quarkus geven hetzelfde lichaam, byte voor byte");
        return MAPPER.readTree(jdk.body());
    }
}
