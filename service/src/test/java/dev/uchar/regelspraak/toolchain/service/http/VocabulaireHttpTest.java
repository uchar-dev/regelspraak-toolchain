package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.VerzoekOpties;

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
 * reads them ({@link DienstHttp#opties}), the answer and the refusals they give, and the {@code vocabulaires} block of
 * {@code /info} — per vocabulary whether it is complete and which declarations have no designation — which a model
 * without vocabularies does not get. Served: uchar's golden {@code jutterswet-vocabulaires} (the submodule).
 */
class VocabulaireHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN = dev.uchar.regelspraak.toolchain.service.TokaBron.REPO
            .resolve("uchar/corpus/golden/jutterswet-vocabulaires");
    private static final String DESCRIPTOR = """
            { "dienst": {"naam":"vocabulaires","versie":"1"},
              "entrypoints": [
                { "naam":"premie","pad":"premie","scope": {"regelgroepen": ["premieberekening"]},
                  "invoer": ["Jutter"], "uitvoer": ["Jutter.vondstbedrag"] },
                { "naam":"premie cgm","pad":"premie-cgm","scope": {"regelgroepen": ["premieberekening"]},
                  "invoer": ["Jutter"], "uitvoer": ["Jutter.vondstbedrag"], "vocabulaire": "cgm" } ],
              "parameters": {} }""";
    private static final String ONVOLLEDIG = """
            Vocabulaire handboek (taal: nl)
            Vocabulaire cgm (standaard)
            Objecttype de Jutter (mv: Jutters) (bezield) (cgm: JUTTER)
                het vondstbedrag (handboek: totale premie) (cgm: VONDST_BEDRAG) Numeriek (getal met 2 decimalen);
                de premie (cgm: PREMIE) Numeriek (getal met 2 decimalen);
            Regel premie (cgm: R_PREMIE)
                geldig altijd
                    De premie van een Jutter moet berekend worden als zijn vondstbedrag.
            """;

    private static DienstHttp golden, onvolledig, zonder;

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        golden = DienstHttp.bind(BeslisserKern.bouw(DESCRIPTOR, Map.of("bron.rgs", Files.readString(GOLDEN.resolve("bron.rgs"))),
                KernConfig.strikt(), cl), "127.0.0.1", 0).start();
        onvolledig = DienstHttp.bind(BeslisserKern.bouw("""
                { "dienst": {"naam":"onvolledig","versie":"1"}, "entrypoints": [ { "naam":"premie","pad":"premie",
                  "scope": {"regelnamen": ["premie"]}, "invoer": ["Jutter"], "uitvoer": ["Jutter.premie"] } ],
                  "parameters": {} }""", Map.of("o.rgs", ONVOLLEDIG), KernConfig.strikt(), cl), "127.0.0.1", 0).start();
        zonder = DienstHttp.bind(BeslisserKern.bouw("""
                { "dienst": {"naam":"zonder","versie":"1"}, "entrypoints": [ { "naam":"premie","pad":"premie",
                  "scope": {"regelnamen": ["premie"]}, "invoer": ["Jutter"], "uitvoer": ["Jutter.premie"] } ],
                  "parameters": {} }""", Map.of("z.rgs", """
                Objecttype de Jutter (mv: Jutters) (bezield)
                    het vondstbedrag Numeriek (getal met 2 decimalen);
                    de premie Numeriek (getal met 2 decimalen);
                Regel premie
                    geldig altijd
                        De premie van een Jutter moet berekend worden als zijn vondstbedrag.
                """), KernConfig.strikt(), cl), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        for (DienstHttp d : new DienstHttp[]{golden, onvolledig, zonder}) if (d != null) d.stop(0);
    }

    private record Antwoord(int status, JsonNode body) {}

    private static Antwoord post(DienstHttp dienst, String pad, String body) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + dienst.poort() + pad))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Antwoord(r.statusCode(), MAPPER.readTree(r.body()));
    }

    private static JsonNode info(DienstHttp dienst) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + dienst.poort() + "/info")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return MAPPER.readTree(r.body());
    }

    private static final String VERZOEK = """
            { "instanties": [ { "type": "strandjutter", "id": "J1", "rollen": { "GEMELDE_VONDST": ["V1"] } },
                              { "type": "Vondst", "id": "V1", "attributen": { "soort": "amber", "gewicht": 2 } } ],
              "parameters": { "premietarief": 5 } }""";

    @Test
    void de_queryparameters_kiezen_het_vocabulaire() throws Exception {
        Antwoord a = post(golden, "/premie?vocabulaire=handboek", VERZOEK);
        assertEquals(200, a.status(), a.body().toString());
        assertEquals("strandjutter", a.body().at("/instanties/0/type").asText());
        assertEquals(10, a.body().at("/instanties/0/attributen/totale premie").asInt(), a.body().toString());

        Antwoord standaard = post(golden, "/premie-cgm", VERZOEK);
        assertEquals(10, standaard.body().at("/instanties/0/attributen/VONDST_BEDRAG").asInt(), standaard.body().toString());

        Antwoord onbekend = post(golden, "/premie?vocabulaire=wetboek&invoervocabulaire=cgm", VERZOEK);
        assertEquals(400, onbekend.status());
        assertEquals("ONBEKEND_VOCABULAIRE", onbekend.body().at("/schendingen/0/soort").asText());
        assertEquals("vocabulaire", onbekend.body().at("/schendingen/0/veld").asText());
        assertEquals(1, onbekend.body().get("schendingen").size(), "het invoervocabulaire cgm bestaat");

        Antwoord vast = post(golden, "/premie?invoervocabulaire=cgm", VERZOEK);
        assertEquals(400, vast.status(), vast.body().toString());
        assertTrue(vast.body().get("schendingen").toString()
                .contains("\"onbekend objecttype 'strandjutter' (invoervocabulaire 'cgm')\""), vast.body().toString());
    }

    @Test
    void de_query_wordt_gelezen_zoals_uitleg() {
        assertEquals(VerzoekOpties.STANDAARD, DienstHttp.opties(null));
        assertEquals(VerzoekOpties.STANDAARD, DienstHttp.opties("uitleg=false&iets=anders"));
        assertEquals(new VerzoekOpties(true, "cgm", "hand boek"), DienstHttp.opties("uitleg&Vocabulaire=cgm&invoervocabulaire=hand%20boek"));
        assertEquals("", DienstHttp.queryWaarde("vocabulaire", "vocabulaire"), "een kale parameter is de lege naam");
        assertNull(DienstHttp.queryWaarde("uitleg=true", "vocabulaire"));
    }

    @Test
    void info_noemt_per_vocabulaire_de_volledigheid() throws Exception {
        JsonNode vocabulaires = info(golden).get("vocabulaires");
        assertEquals(2, vocabulaires.size(), vocabulaires.toString());
        assertEquals("handboek", vocabulaires.at("/0/naam").asText());
        assertEquals("nl", vocabulaires.at("/0/taal").asText());
        assertFalse(vocabulaires.at("/0/standaard").asBoolean());
        assertTrue(vocabulaires.at("/0/volledig").asBoolean());
        assertEquals(0, vocabulaires.at("/0/zonderBenaming").size());
        assertTrue(vocabulaires.at("/1/standaard").asBoolean());
        assertEquals("cgm", info(golden).at("/entrypoints/1/vocabulaire").asText());
        assertFalse(info(golden).at("/entrypoints/0").has("vocabulaire"));

        JsonNode onv = info(onvolledig).get("vocabulaires");
        assertFalse(onv.at("/0/volledig").asBoolean(), onv.toString());
        assertEquals(MAPPER.readTree("""
                [ {"soort":"OBJECTTYPE","naam":"Jutter"},
                  {"soort":"ATTRIBUUT","eigenaar":"Jutter","naam":"premie"},
                  {"soort":"VORM","naam":"premie"} ]"""), onv.at("/0/zonderBenaming"));
        assertTrue(onv.at("/1/volledig").asBoolean(), "het standaardvocabulaire is volledig (WF-119)");
        assertFalse(onv.at("/1").has("taal"), "zonder taal geen taal");

        assertFalse(info(zonder).has("vocabulaires"), "een model zonder vocabulaires krijgt geen blok");
    }
}
