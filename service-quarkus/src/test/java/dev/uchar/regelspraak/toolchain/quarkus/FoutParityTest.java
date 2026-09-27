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
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that the Quarkus adapter answers a request it refuses with the SAME status and body as {@code DienstHttp},
 * over the SAME {@link BeslisserKern}: the 400 of a request that does not validate (each violation with its
 * {@code veld}, {@code soort} and message), the 400 of an empty or malformed body, the 404 of an unknown path, and
 * the 503 MARKER answer of an entry point uchar does not carry. {@link BeslisResource} builds these bodies by hand,
 * as {@code DienstHttp}'s builders are package-private in the service module; this test catches a drift in either
 * copy, as {@link InfoParityTest} does for {@code /info}.
 *
 * <p>A plain JUnit test, like {@link InfoParityTest}: the resource is called directly with the kern set, and
 * {@code DienstHttp} is asked over HTTP on an ephemeral port.
 */
class FoutParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    /** An entry point whose one rule uchar does not carry, served with markers allowed (as DienstHttpTest does). */
    private static final String MARKER_BRON = """
        Objecttype de Vlucht (mv: Vluchten)
            de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
        Regel heffing markert
        geldig altijd
        De heffing van een Vlucht moet berekend worden als het absolute tijdsevenredig deel per maand van 10,00 EUR.
        """;
    private static final String MARKER_DESCRIPTOR = """
        { "dienst": {"naam":"marker-test","versie":"1"},
          "entrypoints": [ { "naam":"heffing markert","pad":"heffing-markert",
            "scope": {"regelnamen": ["heffing markert"]},
            "invoer": ["Vlucht"], "uitvoer": ["Vlucht.heffing"] } ],
          "parameters": {} }""";

    /** Own material for an id of another objecttype on a rol: no feittype relates a Jutter to a Strandpaal. */
    private static final String ROL_BRON = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het leerlingaantal Numeriek (niet-negatief geheel getal);

        Objecttype de Strandpaal (mv: Strandpalen)
            het paalnummer Numeriek (niet-negatief geheel getal);

        Feittype leerlingschap
            de leermeester (mv: leermeesters) Jutter
            de leerling (mv: leerlingen) Jutter
        Eén leermeester heeft meerdere leerlingen

        Regel leerlingaantal van een jutter
            geldig altijd
                Het leerlingaantal van een Jutter moet berekend worden als het aantal leerlingen van de Jutter.
        """;
    private static final String ROL_DESCRIPTOR = """
        { "dienst": {"naam":"leerlingschap","versie":"1"},
          "entrypoints": [ { "naam":"leerlingaantal","pad":"leerlingaantal",
            "scope": {"regelnamen": ["leerlingaantal van een jutter"]},
            "invoer": ["Jutter"], "uitvoer": ["Jutter.leerlingaantal"] } ],
          "parameters": {} }""";

    private static BeslisserKern toka;
    private static BeslisserKern marker;
    private static BeslisserKern rol;
    private static DienstHttp tokaDienst;
    private static DienstHttp markerDienst;
    private static DienstHttp rolDienst;

    @BeforeAll
    static void bouw() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        toka = BeslisserKern.bouw(TokaBron.descriptorJson(), TokaBron.bronPerBestand(), KernConfig.strikt(), cl);
        Map<String, String> bron = new LinkedHashMap<>();
        bron.put("marker.rgs", MARKER_BRON);
        marker = BeslisserKern.bouw(MARKER_DESCRIPTOR, bron, KernConfig.metMarkers(), cl);
        rol = BeslisserKern.bouw(ROL_DESCRIPTOR, Map.of("leerlingschap.rgs", ROL_BRON), KernConfig.strikt(), cl);
        tokaDienst = DienstHttp.bind(toka, "127.0.0.1", 0).start();
        markerDienst = DienstHttp.bind(marker, "127.0.0.1", 0).start();
        rolDienst = DienstHttp.bind(rol, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (tokaDienst != null) tokaDienst.stop(0);
        if (markerDienst != null) markerDienst.stop(0);
        if (rolDienst != null) rolDienst.stop(0);
    }

    @Test
    void verzoek_dat_niet_valideert_400_met_soort_per_schending() throws Exception {
        JsonNode fixture = MAPPER.readTree(Files.readString(TokaBron.REPO.resolve(
                "service/src/test/resources/negatief/01-onbekend-attribuut.json")));
        vergelijk(toka, tokaDienst, fixture.get("pad").asText(), fixture.get("body").toString(), 400);
    }

    @Test
    void id_van_een_ander_objecttype_op_een_rol_400_verkeerd_type() throws Exception {
        JsonNode body = vergelijk(rol, rolDienst, "leerlingaantal", """
            {"instanties":[{"type":"Jutter","id":"J1","rollen":{"leerling":["P1"]}},{"type":"Strandpaal","id":"P1"}]}""",
                400);
        assertEquals("VERKEERD_TYPE", body.at("/schendingen/0/soort").asText(), body.toString());
    }

    @Test
    void leeg_verzoek_400() throws Exception {
        vergelijk(toka, tokaDienst, eenPad(toka), "", 400);
    }

    @Test
    void geen_geldige_json_400() throws Exception {
        vergelijk(toka, tokaDienst, eenPad(toka), "{\"instanties\": [", 400);
    }

    @Test
    void json_null_400() throws Exception {
        vergelijk(toka, tokaDienst, eenPad(toka), "null", 400);
    }

    @Test
    void onbekend_pad_404() throws Exception {
        vergelijk(toka, tokaDienst, "bestaat-niet", "{\"instanties\": []}", 404);
    }

    @Test
    void entrypoint_dat_uchar_niet_draagt_503_marker() throws Exception {
        vergelijk(marker, markerDienst, "heffing-markert",
                "{\"instanties\":[{\"type\":\"Vlucht\",\"id\":\"V1\",\"attributen\":{}}]}", 503);
    }

    private static String eenPad(BeslisserKern kern) {
        return kern.entrypoints().keySet().iterator().next();
    }

    /** The same POST to both adapters over the same kern: the same status ({@code verwacht}) and the same body, which
     *  it returns. */
    private static JsonNode vergelijk(BeslisserKern kern, DienstHttp dienst, String pad, String body, int verwacht)
            throws Exception {
        BeslisResource resource = new BeslisResource();
        resource.kern = kern;
        Response quarkus = resource.verwerk(pad, null, body);

        HttpResponse<String> jdk = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + dienst.poort() + "/" + pad))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(verwacht, jdk.statusCode(), jdk.body());
        assertEquals(verwacht, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        JsonNode jdkBody = MAPPER.readTree(jdk.body());
        JsonNode quarkusBody = MAPPER.readTree((String) quarkus.getEntity());
        assertEquals(jdkBody, quarkusBody, "het foutlichaam van de Quarkus-adapter wijkt af van dat van DienstHttp");
        return jdkBody;
    }
}
