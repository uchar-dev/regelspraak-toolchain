package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.RequestOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vocabularies over HTTP: the query parameters {@code vocabulaire} and {@code invoervocabulaire} as the adapter
 * reads them ({@link ServiceHttp#options}), the answer and the refusals they give, and the {@code vocabularies} block of
 * {@code /info} — per vocabulary whether it is complete and which declarations have no designation — which a model
 * without vocabularies does not get. Served: uchar's golden {@code jutterswet-vocabulaires} (the submodule).
 */
class VocabularyHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN = dev.uchar.regelspraak.toolchain.service.TokaSource.REPO
            .resolve("uchar/corpus/golden/jutterswet-vocabulaires");
    private static final String DESCRIPTOR = """
            { "dienst": {"naam":"vocabulaires","versie":"1"},
              "entrypoints": [
                { "naam":"premie","pad":"premie","scope": {"regelgroepen": ["premieberekening"]},
                  "invoer": ["Jutter"], "uitvoer": ["Jutter.vondstbedrag"] },
                { "naam":"premie cgm","pad":"premie-cgm","scope": {"regelgroepen": ["premieberekening"]},
                  "invoer": ["Jutter"], "uitvoer": ["Jutter.vondstbedrag"], "vocabulaire": "cgm" } ],
              "parameters": {} }""";
    private static final String INCOMPLETE = """
            Vocabulaire handboek (taal: nl)
            Vocabulaire cgm (standaard)
            Objecttype de Jutter (mv: Jutters) (bezield) (cgm: JUTTER)
                het vondstbedrag (handboek: totale premie) (cgm: VONDST_BEDRAG) Numeriek (getal met 2 decimalen);
                de premie (cgm: PREMIE) Numeriek (getal met 2 decimalen);
            Regel premie (cgm: R_PREMIE)
                geldig altijd
                    De premie van een Jutter moet berekend worden als zijn vondstbedrag.
            """;

    private static ServiceHttp golden, incomplete, without;

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        golden = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", Files.readString(GOLDEN.resolve("bron.rgs"))),
                CoreConfig.strict(), cl), "127.0.0.1", 0).start();
        incomplete = ServiceHttp.bind(DecisionCore.assemble("""
                { "dienst": {"naam":"onvolledig","versie":"1"}, "entrypoints": [ { "naam":"premie","pad":"premie",
                  "scope": {"regelnamen": ["premie"]}, "invoer": ["Jutter"], "uitvoer": ["Jutter.premie"] } ],
                  "parameters": {} }""", Map.of("o.rgs", INCOMPLETE), CoreConfig.strict(), cl), "127.0.0.1", 0).start();
        without = ServiceHttp.bind(DecisionCore.assemble("""
                { "dienst": {"naam":"zonder","versie":"1"}, "entrypoints": [ { "naam":"premie","pad":"premie",
                  "scope": {"regelnamen": ["premie"]}, "invoer": ["Jutter"], "uitvoer": ["Jutter.premie"] } ],
                  "parameters": {} }""", Map.of("z.rgs", """
                Objecttype de Jutter (mv: Jutters) (bezield)
                    het vondstbedrag Numeriek (getal met 2 decimalen);
                    de premie Numeriek (getal met 2 decimalen);
                Regel premie
                    geldig altijd
                        De premie van een Jutter moet berekend worden als zijn vondstbedrag.
                """), CoreConfig.strict(), cl), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        for (ServiceHttp d : new ServiceHttp[]{golden, incomplete, without}) if (d != null) d.stop(0);
    }

    private record Answer(int status, JsonNode body) {}

    private static Answer post(ServiceHttp service, String path, String body) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + service.port() + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Answer(r.statusCode(), MAPPER.readTree(r.body()));
    }

    private static JsonNode info(ServiceHttp service) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + service.port() + "/info")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return MAPPER.readTree(r.body());
    }

    private static final String REQUEST = """
            { "instanties": [ { "type": "strandjutter", "id": "J1", "rollen": { "GEMELDE_VONDST": ["V1"] } },
                              { "type": "Vondst", "id": "V1", "attributen": { "soort": "amber", "gewicht": 2 } } ],
              "parameters": { "premietarief": 5 } }""";

    @Test
    void the_query_parameters_choose_the_vocabulary() throws Exception {
        Answer a = post(golden, "/premie?vocabulaire=handboek", REQUEST);
        assertEquals(200, a.status(), a.body().toString());
        assertEquals("strandjutter", a.body().at("/instanties/0/type").asText());
        assertEquals(10, a.body().at("/instanties/0/attributen/totale premie").asInt(), a.body().toString());

        Answer defaultAnswer = post(golden, "/premie-cgm", REQUEST);
        assertEquals(10, defaultAnswer.body().at("/instanties/0/attributen/VONDST_BEDRAG").asInt(), defaultAnswer.body().toString());

        Answer unknown = post(golden, "/premie?vocabulaire=wetboek&invoervocabulaire=cgm", REQUEST);
        assertEquals(400, unknown.status());
        assertEquals("ONBEKEND_VOCABULAIRE", unknown.body().at("/schendingen/0/soort").asText());
        assertEquals("vocabulaire", unknown.body().at("/schendingen/0/veld").asText());
        assertEquals(1, unknown.body().get("schendingen").size(), "het invoervocabulaire cgm bestaat");

        Answer fixed = post(golden, "/premie?invoervocabulaire=cgm", REQUEST);
        assertEquals(400, fixed.status(), fixed.body().toString());
        assertTrue(fixed.body().get("schendingen").toString()
                .contains("\"onbekend objecttype 'strandjutter' (invoervocabulaire 'cgm')\""), fixed.body().toString());
    }

    @Test
    void the_query_is_read_like_uitleg() {
        assertEquals(RequestOptions.DEFAULT, ServiceHttp.options(null));
        assertEquals(RequestOptions.DEFAULT, ServiceHttp.options("uitleg=false&iets=anders"));
        assertEquals(new RequestOptions(true, "cgm", "hand boek"), ServiceHttp.options("uitleg&Vocabulaire=cgm&invoervocabulaire=hand%20boek"));
        assertEquals("", ServiceHttp.queryValue("vocabulaire", "vocabulaire"), "een kale parameter is de lege naam");
        assertNull(ServiceHttp.queryValue("uitleg=true", "vocabulaire"));
    }

    @Test
    void info_names_the_completeness_per_vocabulary() throws Exception {
        JsonNode vocabularies = info(golden).get("vocabulaires");
        assertEquals(2, vocabularies.size(), vocabularies.toString());
        assertEquals("handboek", vocabularies.at("/0/naam").asText());
        assertEquals("nl", vocabularies.at("/0/taal").asText());
        assertFalse(vocabularies.at("/0/standaard").asBoolean());
        assertTrue(vocabularies.at("/0/volledig").asBoolean());
        assertEquals(0, vocabularies.at("/0/zonderBenaming").size());
        assertTrue(vocabularies.at("/1/standaard").asBoolean());
        assertEquals("cgm", info(golden).at("/entrypoints/1/vocabulaire").asText());
        assertFalse(info(golden).at("/entrypoints/0").has("vocabulaire"));

        JsonNode onv = info(incomplete).get("vocabulaires");
        assertFalse(onv.at("/0/volledig").asBoolean(), onv.toString());
        assertEquals(MAPPER.readTree("""
                [ {"soort":"OBJECTTYPE","naam":"Jutter"},
                  {"soort":"ATTRIBUUT","eigenaar":"Jutter","naam":"premie"},
                  {"soort":"VORM","naam":"premie"} ]"""), onv.at("/0/zonderBenaming"));
        assertTrue(onv.at("/1/volledig").asBoolean(), "het standaardvocabulaire is volledig (WF-119)");
        assertFalse(onv.at("/1").has("taal"), "zonder taal geen taal");

        assertFalse(info(without).has("vocabulaires"), "een model zonder vocabulaires krijgt geen blok");
    }
}
