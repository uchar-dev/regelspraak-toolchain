package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

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

/**
 * The fixpoint of the detail gives a time-dependent characteristic with the periods in which it holds, as the
 * request gives them ({@code "periods": [{"from", "to"}]}): one a rule writes within a window, which uchar's fixpoint
 * gives among the derived values as its true periods (uchar's {@code Fixpoint.DerivedValue}, RUNTIME-SEMANTICS ch.
 * 3.10, 3.11), with origin {@code derived}, on uchar's golden jutterswet-verzuimrente-30-360-afgerond, where an
 * Afdracht is rentedragend from the day after its vervaldatum up to and including its betaaldatum; one only the input
 * gives, with origin {@code input}, on uchar's golden anchor/jutterswet-borgdeel, and in the 422 uchar gives on input
 * periods of a characteristic a rule writes, where the rule writes nothing.
 */
class FixpointCharacteristicPeriodsHttpTest {

    private static final Path GOLDEN = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-verzuimrente-30-360-afgerond");

    private static final String DESCRIPTOR = """
        { "service": {"name":"verzuimrente","version":"1"},
          "entrypoints": [ { "name":"verzuimrente","path":"verzuimrente",
            "scope": {"ruleNames": ["rentedragend", "rentedagen in een gehele maand",
                                    "rentedagen in een gedeeltelijke maand", "maandrente", "verzuimrente"]},
            "input": [], "output": ["Afdracht.verzuimrente"] } ],
          "parameters": {} }""";

    private static final String PARAMETERS = """
        "rekendatum": "2026-03-12",
        "parameters": {"verzuimrentevoet": {"timeline": [{"to": "2026-02-01", "value": "0.04"},
                                                         {"from": "2026-02-01", "value": "0.05"}]},
                       "rentejaar": "360"}""";

    private static final Path BORGDEEL = TokaSource.REPO.resolve("uchar/corpus/golden/anchor/jutterswet-borgdeel");

    private static final String BORGDEEL_DESCRIPTOR = """
        { "service": {"name":"borgdeel","version":"1"},
          "entrypoints": [ { "name":"borgdeel","path":"borgdeel",
            "scope": {"ruleNames": ["borgdeel van een jutter", "jaardeel van een jutter"]},
            "input": ["Jutter"], "output": ["Jutter.borgdeel"] } ],
          "parameters": {} }""";

    private static ServiceHttp detail;
    private static ServiceHttp borgdeel;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        detail = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR,
                Map.of("source.rgs", Files.readString(GOLDEN.resolve("source.rgs"))), CoreConfig.strict().withDetail(),
                cl), "127.0.0.1", 0).start();
        borgdeel = ServiceHttp.bind(DecisionCore.buildFromDocument(BORGDEEL_DESCRIPTOR,
                Files.readString(BORGDEEL.resolve("ir.json")), CoreConfig.strict().withDetail(), cl, null), "127.0.0.1", 0)
                .start();
    }

    @AfterAll
    static void stop() {
        if (detail != null) detail.stop(0);
        if (borgdeel != null) borgdeel.stop(0);
    }

    /** case1 of the golden: the rule writes rentedragend on [2025-11-16, 2026-03-13). */
    @Test
    void a_characteristic_a_rule_writes_within_a_window_stands_with_its_periods() throws Exception {
        JsonNode answer = fixpoint(detail, "verzuimrente", 200, """
            {%s, "instances": [{"type": "Afdracht", "id": "A1",
              "attributes": {"afdrachtbedrag": "10000", "vervaldatum": "2025-11-15", "betaaldatum": "2026-03-12"}}]}"""
                .formatted(PARAMETERS));
        assertEquals(tree("""
            [{"characteristic": "rentedragend", "origin": "derived",
              "periods": [{"from": "2025-11-16", "to": "2026-03-13"}]}]"""), characteristics(answer, "A1"),
                answer.toString());
    }

    /** case6 of the golden borgdeel: the input gives borgplichtig in January and February 2024, and no rule writes it. */
    @Test
    void a_characteristic_only_the_input_gives_stands_with_the_periods_of_the_input() throws Exception {
        JsonNode answer = fixpoint(borgdeel, "borgdeel", 200, """
            {"instances":[{"type":"Jutter","id":"J1",
              "attributes":{
                "borgtarief":{"timeline":[{"from":"2024-01-01","to":"2025-01-01","value":12}]},
                "borgdeel":{"timeline":[{"from":"2024-03-01","to":"2024-04-01","value":99}]}},
              "characteristics":{"borgplichtig":{"periods":[{"from":"2024-01-01","to":"2024-03-01"}]}}}]}""");
        assertEquals(tree("""
            [{"characteristic": "borgplichtig", "origin": "input",
              "periods": [{"from": "2024-01-01", "to": "2024-03-01"}]}]"""), characteristics(answer, "J1"),
                answer.toString());
    }

    /** Input periods on a characteristic a rule writes are a fault of uchar (CHARACTERISTIC_PERIODS_FAULT, a 422):
     *  the rule writes nothing, and the fixpoint of the 422 gives the periods of the input. */
    @Test
    void in_the_fault_of_input_periods_beside_a_rule_the_periods_of_the_input_stand() throws Exception {
        JsonNode answer = fixpoint(detail, "verzuimrente", 422, """
            {%s, "instances": [{"type": "Afdracht", "id": "A1",
              "attributes": {"afdrachtbedrag": "10000", "vervaldatum": "2025-11-15", "betaaldatum": "2026-03-12"},
              "characteristics": {"rentedragend": {"periods": [{"from": "2025-01-01", "to": "2025-02-01"}]}}}]}"""
                .formatted(PARAMETERS));
        assertEquals("CHARACTERISTIC_PERIODS_FAULT", answer.at("/faults/0/kind").asText(), answer.toString());
        assertEquals(tree("""
            [{"characteristic": "rentedragend", "origin": "input",
              "periods": [{"from": "2025-01-01", "to": "2025-02-01"}]}]"""), characteristics(answer, "A1"),
                answer.toString());
    }

    private static JsonNode fixpoint(ServiceHttp to, String path, int status, String body) throws Exception {
        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port()
                                + "/" + path + "?fixpoint=true"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(status, r.statusCode(), r.body());
        return tree(r.body());
    }

    private static JsonNode characteristics(JsonNode answer, String object) {
        for (JsonNode o : answer.at("/fixpoint/objects"))
            if (object.equals(o.path("object").asText())) return o.get("characteristics");
        throw new AssertionError("het fixpunt heeft geen object " + object + ": " + answer);
    }

    private static JsonNode tree(String json) throws Exception {
        return ServiceHttp.MAPPER.readTree(json);
    }
}
