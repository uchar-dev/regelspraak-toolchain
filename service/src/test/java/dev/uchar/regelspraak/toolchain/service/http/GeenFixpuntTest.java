package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.GeenFixpunt;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.OpenApiEmitter;
import dev.uchar.regelspraak.toolchain.service.TokaBron;

import org.junit.jupiter.api.AfterAll;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run without a fixpoint, on uchar's red golden {@code jutterswet-budgetgrens-over}: from a Wachter of rang 1 its
 * recursive creation would make a Wachter at creation depth 101, one past uchar's creation budget, and uchar's
 * {@code DroolsBackend.vuur} ends the run there with its sentence ({@code Vuring.geenFixpunt}). From rang 50 the chain
 * ends at depth 52 and the run reaches its fixpoint. The core throws {@link GeenFixpunt} with uchar's sentence; the
 * decision route answers it with a 422 {@code GEEN_FIXPUNT}; {@code ?via=dienst} makes that scenario a FAIL in the shape
 * of uchar's report and computes the other scenario of the document.
 */
class GeenFixpuntTest {

    private static final Path ROOD = TokaBron.REPO.resolve("uchar/corpus/rood/jutterswet-budgetgrens-over");
    private static final String DESCRIPTOR = """
            { "dienst": {"naam":"estafette","versie":"1"},
              "entrypoints": [ { "naam":"estafette","pad":"estafette",
                "scope": {"regelnamen": ["opvolgerrang van een wachter", "rangplafond", "eerste keten aan het plafond",
                                         "tweede keten aan het plafond", "werf de lange keten"]},
                "invoer": ["Wachter"], "uitvoer": ["Wachter.opvolgerrang"] } ],
              "parameters": {} }""";
    private static final String SCENARIO = """
            Scenario keten vanaf rang %1$s
            Gegeven is de volgende situatie:
            een Wachter W1
            - de rang is %1$s
            - het ketennummer is 1
            Met als verwacht resultaat:
            - de opvolgerrang van W1 is %2$s
            """;
    private static final String VERZOEK =
            "{\"instanties\":[{\"type\":\"Wachter\",\"id\":\"W1\",\"attributen\":{\"rang\":%s,\"ketennummer\":1}}]}";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static String bron;
    /** The sentence uchar pins for the Drools branch on this chain, in its own red case. */
    private static String fragment;
    private static BeslisserKern kern;
    private static DienstHttp dienst;

    @BeforeAll
    static void start() throws IOException {
        bron = Files.readString(ROOD.resolve("bron.rgs"));
        fragment = DienstHttp.MAPPER.readTree(Files.readString(ROOD.resolve("case1.json")))
                .at("/expectedFault/drools").asText();
        assertTrue(fragment.startsWith("creation budget exhausted: "), fragment);
        kern = BeslisserKern.bouw(DESCRIPTOR, Map.of("bron.rgs", bron), KernConfig.strikt().metDetail(),
                Thread.currentThread().getContextClassLoader());
        dienst = DienstHttp.bindMet(kern, kern::verwerk, "127.0.0.1", 0, true).start();
    }

    @AfterAll
    static void stop() {
        if (dienst != null) dienst.stop(0);
    }

    // ---------------- the core ----------------

    /** The core throws uchar's sentence as its own type, not as an internal fault; from rang 50 it answers. */
    @Test
    void de_kern_werpt_GeenFixpunt_met_uchars_zin() throws IOException {
        GeenFixpunt fout = assertThrows(GeenFixpunt.class,
                () -> kern.verwerk("estafette", DienstHttp.MAPPER.readTree(VERZOEK.formatted(1))));
        assertTrue(fout.getMessage().startsWith(fragment), fout.getMessage());
        JsonNode antwoord = kern.verwerk("estafette", DienstHttp.MAPPER.readTree(VERZOEK.formatted(50)));
        assertEquals(51, antwoord.at("/instanties/0/attributen/opvolgerrang").asInt(), antwoord.toString());
    }

    // ---------------- the decision route ----------------

    /** A 422 with status {@code GEEN_FIXPUNT} and uchar's sentence, and nothing else without a flag. */
    @Test
    void de_beslisroute_geeft_een_422_met_uchars_zin() throws Exception {
        HttpResponse<String> r = post("/estafette", VERZOEK.formatted(1));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = DienstHttp.MAPPER.readTree(r.body());
        assertEquals(List.of("status", "fout"), sleutels(body));
        assertEquals("GEEN_FIXPUNT", body.path("status").asText());
        assertTrue(body.path("fout").asText().startsWith(fragment), body.toString());
    }

    /** With the detail flags the 422 carries the trace and the coverage of what fired, and for the fixpoint the reason
     *  there is none, uchar's sentence. */
    @Test
    void met_de_detailvlaggen_trace_dekking_en_fixpuntFout() throws Exception {
        HttpResponse<String> r = post("/estafette?trace=true&dekking=true&fixpunt=true", VERZOEK.formatted(1));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = DienstHttp.MAPPER.readTree(r.body());
        assertEquals(List.of("status", "fout", "trace", "dekking", "fixpuntFout"), sleutels(body));
        assertEquals(body.path("fout"), body.path("fixpuntFout"));
        boolean werving = false;
        for (JsonNode v : body.path("trace"))
            werving |= v.path("regel").asText().equals("werf de lange keten") && v.path("aantal").asInt() == 100;
        assertTrue(werving, body.path("trace").toString());
        boolean gevuurd = false;
        for (JsonNode d : body.path("dekking"))
            gevuurd |= d.path("regel").asText().equals("werf de lange keten") && d.path("soort").asText().equals("GEVUURD");
        assertTrue(gevuurd, body.path("dekking").toString());
    }

    /** The contract names the body: the 422 is one of {@code Evaluatiefout} and {@code GeenFixpunt}. */
    @Test
    void de_openapi_422_noemt_het_lichaam_van_GeenFixpunt() {
        JsonNode doc = OpenApiEmitter.emit(kern.descriptor(), kern.model());
        JsonNode schema = doc.at("/paths/~1estafette/post/responses/422/content/application~1json/schema");
        assertEquals("[{\"$ref\":\"#/components/schemas/Evaluatiefout\"},{\"$ref\":\"#/components/schemas/GeenFixpunt\"}]",
                schema.path("oneOf").toString(), schema::toString);
        JsonNode s = doc.at("/components/schemas/GeenFixpunt");
        assertEquals("[\"status\",\"fout\"]", s.path("required").toString(), s::toString);
        assertEquals(List.of("status", "fout", "trace", "dekking", "fixpuntFout"), sleutels(s.path("properties")));
        assertEquals("[\"GEEN_FIXPUNT\"]", s.at("/properties/status/enum").toString(), s::toString);
    }

    // ---------------- via=dienst ----------------

    /** The scenario without a fixpoint is a FAIL as uchar's report gives it, the same as the ordinary route; the other
     *  scenario of the document is computed and passes. */
    @Test
    void via_dienst_het_scenario_zonder_fixpunt_is_een_FAIL_en_het_andere_een_PASS() {
        String document = bron + "\n" + SCENARIO.formatted(1, 2) + "\n" + SCENARIO.formatted(50, 51);
        JsonNode g = Playground.analyseer(document, null).path("scenario").path("resultaten");
        JsonNode d = Playground.analyseer(document, null, kern).path("scenario").path("resultaten");
        assertEquals(2, d.size(), d.toString());
        assertEquals("FAIL", g.get(0).path("status").asText(), g.toString());
        assertEquals("FAIL", d.get(0).path("status").asText(), d.toString());
        assertEquals("PASS", g.get(1).path("status").asText(), g.toString());
        assertEquals("PASS", d.get(1).path("status").asText(), d.toString());

        JsonNode fout = d.get(0);
        assertEquals("estafette", fout.path("pad").asText());
        assertEquals(g.get(0).path("diagnose"), fout.path("diagnose"));
        assertTrue(fout.path("diagnose").asText().startsWith("case1-keten-vanaf-rang-1.json: " + fragment),
                fout.path("diagnose").asText());
        for (String veld : List.of("verwachtingen", "trace", "dekking"))
            assertEquals(g.get(0).path(veld), fout.path(veld), veld);
        assertEquals(0, fout.path("verwachtingen").size());
        assertTrue(fout.path("trace").size() > 0, fout.toString());
        assertTrue(fout.has("uitlegFout"), fout.toString());
        assertTrue(fout.path("fixpuntFout").asText().startsWith(fragment), fout.toString());

        JsonNode pass = d.get(1);
        assertEquals(g.get(1).path("verwachtingen"), pass.path("verwachtingen"));
        assertEquals("51", pass.at("/verwachtingen/0/werkelijk").asText(), pass.toString());
    }

    /** Over HTTP the analysis as a whole is a 200. */
    @Test
    void via_dienst_over_http_een_200() throws Exception {
        String document = bron + "\n" + SCENARIO.formatted(1, 2) + "\n" + SCENARIO.formatted(50, 51);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + dienst.poort() + "/playground/analyse?via=dienst"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(document, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode resultaten = DienstHttp.MAPPER.readTree(r.body()).path("scenario").path("resultaten");
        List<String> statussen = new ArrayList<>();
        for (JsonNode n : resultaten) statussen.add(n.path("status").asText());
        assertEquals(List.of("FAIL", "PASS"), statussen);
    }

    private static List<String> sleutels(JsonNode n) {
        List<String> uit = new ArrayList<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) uit.add(it.next());
        return uit;
    }

    private static HttpResponse<String> post(String pad, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + dienst.poort() + pad))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
