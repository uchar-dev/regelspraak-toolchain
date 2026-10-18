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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Quarkus adapter and {@code ServiceHttp} over one kern give the same body with the detail flags {@code trace},
 * {@code dekking} and {@code fixpunt}: for an answer, for a 422 whose fixpoint carries the fault, for an answer in a
 * vocabulary (uchar's golden {@code jutterswet-vocabulaires}), and for the 400 of a kern started without detail. A
 * plain JUnit test, as {@link ErrorParityTest}: the resource is called directly with the kern set, and
 * {@code ServiceHttp} is asked over HTTP on an ephemeral port.
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
    private static final String VOCABULARY_DESCRIPTOR = """
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

    private static final List<ServiceHttp> SERVICES = new ArrayList<>();
    private static Pair berging;
    private static Pair withoutDetail;
    private static Pair vocabularies;

    private record Pair(DecisionCore core, ServiceHttp service) {}

    @BeforeAll
    static void assemble() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        berging = pair(DecisionCore.assemble(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                CoreConfig.strict().withDetail(), cl));
        withoutDetail = pair(DecisionCore.assemble(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                CoreConfig.strict(), cl));
        String source = Files.readString(TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs"));
        vocabularies = pair(DecisionCore.assemble(VOCABULARY_DESCRIPTOR, Map.of("bron.rgs", source),
                CoreConfig.strict().withDetail(), cl));
    }

    private static Pair pair(DecisionCore core) throws Exception {
        ServiceHttp service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        SERVICES.add(service);
        return new Pair(core, service);
    }

    @AfterAll
    static void stop() {
        for (ServiceHttp d : SERVICES) d.stop(0);
    }

    @Test
    void an_answer_with_the_flags_is_equal() throws Exception {
        JsonNode body = compare(berging, "berging", ALLE, request(2), 200);
        assertEquals("B", body.at("/trace/0/labels/0").asText(), body.toString());
        assertEquals("GEVUURD", body.at("/dekking/0/soort").asText(), body.toString());
        assertEquals("afgeleid", body.at("/fixpunt/objecten/0/waarden/2/herkomst").asText(), body.toString());
    }

    @Test
    void a_422_with_the_fixpoint_is_equal() throws Exception {
        JsonNode body = compare(berging, "berging", "fixpunt", request(0), 422);
        assertEquals("DELING_DOOR_NUL", body.at("/fixpunt/fouten/0/soort").asText(), body.toString());
    }

    @Test
    void an_answer_in_a_vocabulary_with_the_flags_is_equal() throws Exception {
        JsonNode body = compare(vocabularies, "premie-cgm", ALLE, VONDST, 200);
        assertTrue(body.get("fixpunt").toString().contains("\"objecttype\":\"VONDST\",\"objecttypeCanoniek\":\"Vondst\""),
                body.toString());
    }

    @Test
    void without_detail_the_400_is_equal() throws Exception {
        JsonNode body = compare(withoutDetail, "berging", "dekking=true&fixpunt", request(2), 400);
        assertEquals("dekking", body.at("/schendingen/0/veld").asText(), body.toString());
        assertEquals("fixpunt", body.at("/schendingen/1/veld").asText(), body.toString());
    }

    private static String request(int helpers) {
        return """
                { "instanties": [ { "type": "Berging", "id": "B",
                                    "attributen": { "opbrengst": 100, "helpersaantal": %d } } ] }""".formatted(helpers);
    }

    /** The same POST to both adapters: the same status ({@code verwacht}) and body, which it returns. The query holds
     *  only detail flags, each {@code naam=true} or bare. */
    private static JsonNode compare(Pair p, String path, String query, String body, int expected) throws Exception {
        DecideResource resource = new DecideResource();
        resource.core = p.core();
        String trace = null, coverage = null, fixpoint = null;
        for (String part : query.split("&")) {
            String name = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
            String value = part.contains("=") ? part.substring(part.indexOf('=') + 1) : "";
            switch (name) {
                case "trace" -> trace = value;
                case "dekking" -> coverage = value;
                case "fixpunt" -> fixpoint = value;
                default -> throw new IllegalArgumentException("geen detailvlag: " + part);
            }
        }
        Response quarkus = resource.process(path, null, null, null, trace, coverage, fixpoint, body);
        HttpResponse<String> jdk = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + p.service().port() + "/" + path + "?" + query))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(expected, jdk.statusCode(), jdk.body());
        assertEquals(expected, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        assertEquals(jdk.body(), quarkus.getEntity(), "JDK en Quarkus geven hetzelfde lichaam, byte voor byte");
        return MAPPER.readTree(jdk.body());
    }
}
