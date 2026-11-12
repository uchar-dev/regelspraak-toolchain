package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A date-time leaves the service in the lexeme of {@code uchar-case-v0}, {@code yyyy-MM-ddTHH:mm:ss} with a fraction of
 * whole milliseconds only where there is one: the seconds stay where they are zero, in the answer, the fixpoint, the
 * parameter sets of {@code /info} and the situation and fixpoint of the playground. A caller that reads the answer
 * against the schema, or writes it back as a case, reads the value it gave.
 */
class DateTimeSecondsHttpTest {

    private static final String SOURCE = """
            Parameter de openingsstip : Datum en tijd in millisecondes;

            Objecttype de Vondst (mv: Vondsten)
                de afgiftestip Datum en tijd in millisecondes;
                de sluitstip Datum en tijd in millisecondes;
                is tijdig kenmerk (bijvoeglijk);

            Regel Sluitstip
                geldig altijd
                    De sluitstip van een Vondst moet gesteld worden op dd. 1-7-2026 08:30:00.000.

            Regel Kenmerktoekenning tijdig
                geldig altijd
                    Een Vondst is tijdig
                    indien zijn afgiftestip later is dan de openingsstip.
            """;

    private static final String DESCRIPTOR = """
            { "service": {"name":"sluitstip","version":"1"},
              "entrypoints": [ { "name":"stip","path":"stip",
                "scope": {"ruleNames": ["Sluitstip","Kenmerktoekenning tijdig"]},
                "input": ["Vondst"], "output": ["Vondst.sluitstip","Vondst.afgiftestip","Vondst:tijdig"] } ],
              "parameterSets": "stip.parameters.json" }""";

    private static final String SETS = """
            { "parameterSets": [ { "name": "opening",
                "values": { "openingsstip": [ { "value": "2026-07-01T08:00:00" } ] } } ] }""";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static ServiceHttp service;

    @BeforeAll
    static void start() throws Exception {
        service = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR, Map.of("stip.rgs", SOURCE),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader(), SETS),
                "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
    }

    @Test
    void the_answer_and_the_fixpoint_keep_the_seconds() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/stip?fixpoint=true"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"instances":[{"type":"Vondst","id":"V1","attributes":{"afgiftestip":"2026-07-01T08:29:00"}},
                          {"type":"Vondst","id":"V2","attributes":{"afgiftestip":"2026-07-01T08:29:00.500"}}]}""",
                        StandardCharsets.UTF_8)).build());
        assertEquals(200, r.statusCode(), r.body());
        JsonNode a = ServiceHttp.MAPPER.readTree(r.body());
        assertEquals("2026-07-01T08:29:00", a.at("/instances/0/attributes/afgiftestip").asText(), r.body());
        assertEquals("2026-07-01T08:30:00", a.at("/instances/0/attributes/sluitstip").asText(), r.body());
        assertEquals("2026-07-01T08:29:00.500", a.at("/instances/1/attributes/afgiftestip").asText(), r.body());

        List<String> fixpoint = values(a.at("/fixpoint/objects/0/values"));
        assertEquals(List.of("afgiftestip=2026-07-01T08:29:00", "sluitstip=2026-07-01T08:30:00"), fixpoint, r.body());
    }

    @Test
    void a_parameter_set_on_info_keeps_the_seconds() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/info")).GET().build());
        assertEquals(200, r.statusCode(), r.body());
        JsonNode info = ServiceHttp.MAPPER.readTree(r.body());
        assertEquals("2026-07-01T08:00:00", info.at("/parameterSets/0/values/openingsstip/0/value").asText(), r.body());
    }

    @Test
    void the_situation_and_fixpoint_of_the_playground_keep_the_seconds() {
        String source = SOURCE + """

                Situatie basis:
                een Vondst V
                - de afgiftestip is dd. 01-07-2026 08:29:00.000
                de parameter de openingsstip is dd. 01-07-2026 08:00:00.000
                Scenario op tijd
                met peildatum 01-07-2026
                gebaseerd op de situatie basis
                Met als verwacht resultaat:
                - V is tijdig
                """;
        JsonNode s = Playground.analyze(source).at("/scenario/results/0");
        assertEquals("PASS", s.get("status").asText(), s.toString());
        List<String> texts = new ArrayList<>();
        collect(s, texts);
        List<String> withTime = texts.stream().filter(t -> t.matches("\\d{4}-\\d{2}-\\d{2}T.*")).toList();
        assertFalse(withTime.isEmpty(), s.toString());
        for (String t : withTime)
            assertEquals(true, t.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?"),
                    "'" + t + "' draagt geen seconden: " + s);
        assertEquals(true, withTime.contains("2026-07-01T08:29:00"), s.toString());
        assertEquals(true, withTime.contains("2026-07-01T08:30:00"), s.toString());
    }

    private static List<String> values(JsonNode values) {
        List<String> rows = new ArrayList<>();
        for (JsonNode w : values)
            if (!w.get("value").isNull() && w.get("value").isTextual())
                rows.add(w.get("attribute").asText() + "=" + w.get("value").asText());
        return rows;
    }

    private static void collect(JsonNode n, List<String> sink) {
        if (n.isTextual()) sink.add(n.asText());
        for (JsonNode c : n) collect(c, sink);
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + service.port() + path);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
