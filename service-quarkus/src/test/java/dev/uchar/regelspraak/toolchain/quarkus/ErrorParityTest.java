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
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that the Quarkus adapter answers a request it refuses with the SAME status and body as {@code ServiceHttp},
 * over the SAME {@link DecisionCore}: the 400 of a request that does not validate (each violation with its
 * {@code veld}, {@code soort} and message), the 400 of an empty or malformed body, the 404 of an unknown path, the
 * 503 MARKER answer of an entry point uchar does not carry, and the 422 of a request on which the rules give an
 * evaluation fault. The 400s include a to-one role that gets a second, different target through {@code relaties}; the
 * 422s include a fault at the fixpoint of another type than the four uchar's README names, {@code ANDERE}, alone and
 * beside a division by zero. {@link DecideResource} builds the first bodies by hand, as {@code ServiceHttp}'s builders
 * are package-private in the service module, and the 422 body with the service core's builder, as {@code ServiceHttp}
 * does; this test catches a drift in either adapter, as {@link InfoParityTest} does for {@code /info}.
 *
 * <p>A plain JUnit test, like {@link InfoParityTest}: the resource is called directly with the kern set, and
 * {@code ServiceHttp} is asked over HTTP on an ephemeral port.
 */
class ErrorParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    /** An entry point whose one rule uchar does not carry, served with markers allowed (as ServiceHttpTest does). */
    private static final String MARKER_SOURCE = """
        Objecttype de Vlucht (mv: Vluchten)
            de heffing Numeriek (niet-negatief geheel getal);
            is belast kenmerk (bijvoeglijk) voor elke dag;
        Regel heffing markert
        geldig altijd
        De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
        """;
    private static final String MARKER_DESCRIPTOR = """
        { "dienst": {"naam":"marker-test","versie":"1"},
          "entrypoints": [ { "naam":"heffing markert","pad":"heffing-markert",
            "scope": {"regelnamen": ["heffing markert"]},
            "invoer": ["Vlucht"], "uitvoer": ["Vlucht.heffing"] } ],
          "parameters": {} }""";

    /** Own material for an id of another objecttype on a rol: no feittype relates a Jutter to a Strandpaal. */
    private static final String ROLE_SOURCE = """
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
    private static final String ROLE_DESCRIPTOR = """
        { "dienst": {"naam":"leerlingschap","versie":"1"},
          "entrypoints": [ { "naam":"leerlingaantal","pad":"leerlingaantal",
            "scope": {"regelnamen": ["leerlingaantal van een jutter"]},
            "invoer": ["Jutter"], "uitvoer": ["Jutter.leerlingaantal"] } ],
          "parameters": {} }""";

    /** Own material for two evaluation faults at once: a division by a numeric zero, and two writers of one attribute
     *  whose conditions both hold. */
    private static final String EVALUATION_SOURCE = """
        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (getal met 2 decimalen);
            het aantal delen Numeriek (geheel getal);
            het deelgewicht Numeriek (getal met 2 decimalen);
            het vindersloon Numeriek (getal met 2 decimalen);

        Regel deelgewicht van een vondst
            geldig altijd
                Het deelgewicht van een Vondst moet berekend worden als zijn gewicht gedeeld door zijn aantal delen.

        Regel loon voor een zware vondst
            geldig altijd
                Het vindersloon van een Vondst moet gesteld worden op 25,00
                indien zijn gewicht groter is dan 5,00.

        Regel loon voor een grote vondst
            geldig altijd
                Het vindersloon van een Vondst moet gesteld worden op 10,00
                indien zijn gewicht groter is dan 3,00.
        """;
    private static final String EVALUATION_DESCRIPTOR = """
        { "dienst": {"naam":"vondsten","versie":"1"},
          "entrypoints": [ { "naam":"vondst","pad":"vondst",
            "scope": {"regelnamen": ["deelgewicht van een vondst", "loon voor een zware vondst",
                                     "loon voor een grote vondst"]},
            "invoer": ["Vondst"], "uitvoer": ["Vondst.deelgewicht", "Vondst.vindersloon"] } ],
          "parameters": {} }""";

    /** Own material for a second, different target on a to-one role: a Vondst has one aanmelder, which a request can
     *  name by the Vondst's own role and by a relation of {@code relaties}. */
    private static final String AANMELDING_SOURCE = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de zware vondsten Numeriek (geheel getal);

        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (geheel getal);
            de deelnemers Numeriek (geheel getal);

        Feittype aanmelding
            de aanmelder Jutter
            de vondst (mv: vondsten) Vondst
        Eén aanmelder heeft meerdere vondsten

        Regel zware vondsten
            geldig altijd
                De zware vondsten van een Jutter moet berekend worden als het aantal vondsten van de Jutter
                die aan de volgende voorwaarde voldoen: zijn gewicht gedeeld door zijn deelnemers is groter dan 2.
        """;
    private static final String AANMELDING_DESCRIPTOR = """
        { "dienst": {"naam":"vouw","versie":"1"},
          "entrypoints": [ { "naam":"zware vondsten","pad":"zware-vondsten",
            "scope": {"regelnamen": ["zware vondsten"]},
            "invoer": ["Jutter"], "uitvoer": ["Jutter.zware vondsten"] } ],
          "parameters": {} }""";

    /** Own material for a fault at the fixpoint of another type than the four uchar's README names: the model of
     *  uchar's witness of a second filling ({@code TmsCreatieWitnessTest}, document D), in which a fact creation gives
     *  a to-one role two different fillers, with a division by a numeric zero beside it. */
    private static final String FILLING_SOURCE = """
        Objecttype de Kustwacht (mv: Kustwachten)

        Objecttype de Haven (mv: Havens)
            het jutteraantal Numeriek (geheel getal);
            de ligplaatsen Numeriek (geheel getal);
            het ligplaatsdeel Numeriek (geheel getal);

        Objecttype de Jutter (mv: Jutters) (bezield)

        Feittype bewaking
            de kustwacht Kustwacht
            de bewaakte haven (mv: bewaakte havens) Haven
        Eén kustwacht bewaakt meerdere bewaakte havens

        Feittype werving
            de werver Kustwacht
            de geworven jutter (mv: geworven jutters) Jutter
        Eén werver heeft meerdere geworven jutters

        Feittype havenlid
            de haven Haven
            de havenjutter (mv: havenjutters) Jutter
        Eén haven heeft meerdere havenjutters

        Regel jutteraantal van een haven
            geldig altijd
                Het jutteraantal van een Haven moet berekend worden als het aantal havenjutters van de Haven.

        Regel werving van havenjutters
            geldig altijd
                Een havenjutter van een Haven is een geworven jutter van de kustwacht van de Haven.

        Regel ligplaatsdeel van een haven
            geldig altijd
                Het ligplaatsdeel van een Haven moet berekend worden als 100 gedeeld door zijn ligplaatsen.
        """;
    private static final String FILLING_DESCRIPTOR = """
        { "dienst": {"naam":"vulling","versie":"1"},
          "entrypoints": [
            { "naam":"vulling","pad":"vulling",
              "scope": {"regelnamen": ["jutteraantal van een haven", "werving van havenjutters"]},
              "invoer": ["Kustwacht", "Haven", "Jutter"], "uitvoer": ["Haven.jutteraantal"] },
            { "naam":"mengsel","pad":"mengsel",
              "scope": {"regelnamen": ["jutteraantal van een haven", "werving van havenjutters",
                                       "ligplaatsdeel van een haven"]},
              "invoer": ["Kustwacht", "Haven", "Jutter"], "uitvoer": ["Haven.jutteraantal", "Haven.ligplaatsdeel"] } ],
          "parameters": {} }""";

    private static DecisionCore toka;
    private static DecisionCore marker;
    private static DecisionCore role;
    private static DecisionCore evaluation;
    private static DecisionCore aanmelding;
    private static DecisionCore filling;
    private static ServiceHttp tokaService;
    private static ServiceHttp markerService;
    private static ServiceHttp roleService;
    private static ServiceHttp evaluationService;
    private static ServiceHttp submissionService;
    private static ServiceHttp fillingService;

    @BeforeAll
    static void assemble() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        toka = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(), CoreConfig.strict(), cl);
        Map<String, String> source = new LinkedHashMap<>();
        source.put("marker.rgs", MARKER_SOURCE);
        marker = DecisionCore.assemble(MARKER_DESCRIPTOR, source, CoreConfig.withMarkers(), cl);
        role = DecisionCore.assemble(ROLE_DESCRIPTOR, Map.of("leerlingschap.rgs", ROLE_SOURCE), CoreConfig.strict(), cl);
        evaluation = DecisionCore.assemble(EVALUATION_DESCRIPTOR, Map.of("vondsten.rgs", EVALUATION_SOURCE),
                CoreConfig.strict(), cl);
        aanmelding = DecisionCore.assemble(AANMELDING_DESCRIPTOR, Map.of("vouw.rgs", AANMELDING_SOURCE), CoreConfig.strict(),
                cl);
        filling = DecisionCore.assemble(FILLING_DESCRIPTOR, Map.of("vulling.rgs", FILLING_SOURCE), CoreConfig.strict(), cl);
        tokaService = ServiceHttp.bind(toka, "127.0.0.1", 0).start();
        markerService = ServiceHttp.bind(marker, "127.0.0.1", 0).start();
        roleService = ServiceHttp.bind(role, "127.0.0.1", 0).start();
        evaluationService = ServiceHttp.bind(evaluation, "127.0.0.1", 0).start();
        submissionService = ServiceHttp.bind(aanmelding, "127.0.0.1", 0).start();
        fillingService = ServiceHttp.bind(filling, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (tokaService != null) tokaService.stop(0);
        if (markerService != null) markerService.stop(0);
        if (roleService != null) roleService.stop(0);
        if (evaluationService != null) evaluationService.stop(0);
        if (submissionService != null) submissionService.stop(0);
        if (fillingService != null) fillingService.stop(0);
    }

    @Test
    void request_that_does_not_validate_400_with_kind_per_violation() throws Exception {
        JsonNode fixture = MAPPER.readTree(Files.readString(TokaSource.REPO.resolve(
                "service/src/test/resources/negative/01-unknown-attribute.json")));
        compare(toka, tokaService, fixture.get("pad").asText(), fixture.get("body").toString(), 400);
    }

    @Test
    void id_of_another_object_type_on_a_role_400_wrong_type() throws Exception {
        JsonNode body = compare(role, roleService, "leerlingaantal", """
            {"instanties":[{"type":"Jutter","id":"J1","rollen":{"leerling":["P1"]}},{"type":"Strandpaal","id":"P1"}]}""",
                400);
        assertEquals("VERKEERD_TYPE", body.at("/schendingen/0/soort").asText(), body.toString());
    }

    @Test
    void second_other_target_via_relations_400_wrong_type() throws Exception {
        JsonNode body = compare(aanmelding, submissionService, "zware-vondsten", """
            {"instanties":[{"type":"Jutter","id":"J1"},{"type":"Jutter","id":"J2"},
              {"type":"Vondst","id":"V1","attributen":{"gewicht":10,"deelnemers":2},"rollen":{"aanmelder":"J1"}}],
             "relaties":[{"feittype":"aanmelding","rollen":{"aanmelder":"J2","vondst":"V1"}}]}""", 400);
        assertEquals("VERKEERD_TYPE", body.at("/schendingen/0/soort").asText(), body.toString());
        assertEquals("relaties[0].rollen.aanmelder", body.at("/schendingen/0/veld").asText(), body.toString());
    }

    @Test
    void empty_request_400() throws Exception {
        compare(toka, tokaService, aPath(toka), "", 400);
    }

    @Test
    void no_valid_json_400() throws Exception {
        compare(toka, tokaService, aPath(toka), "{\"instanties\": [", 400);
    }

    @Test
    void json_null_400() throws Exception {
        compare(toka, tokaService, aPath(toka), "null", 400);
    }

    @Test
    void unknown_path_404() throws Exception {
        compare(toka, tokaService, "bestaat-niet", "{\"instanties\": []}", 404);
    }

    @Test
    void entrypoint_that_uchar_does_not_carry_503_marker() throws Exception {
        compare(marker, markerService, "heffing-markert",
                "{\"instanties\":[{\"type\":\"Vlucht\",\"id\":\"V1\",\"attributen\":{}}]}", 503);
    }

    @Test
    void evaluation_fault_422_with_kind_and_sentence_per_fault() throws Exception {
        JsonNode body = compare(evaluation, evaluationService, "vondst",
                "{\"instanties\":[{\"type\":\"Vondst\",\"id\":\"V1\",\"attributen\":{\"gewicht\":6.00,\"aantal delen\":0}}]}",
                422);
        assertEquals("EVALUATIEFOUT", body.path("status").asText(), body.toString());
        assertEquals(2, body.path("fouten").size(), body.toString());
    }

    @Test
    void second_filling_from_a_to_a_role_422_other() throws Exception {
        JsonNode body = compare(filling, fillingService, "vulling", """
            {"instanties":[{"type":"Kustwacht","id":"K1",
              "rollen":{"bewaakte havens":["H1","H2"],"geworven jutters":["J1"]}},
              {"type":"Haven","id":"H1"},{"type":"Haven","id":"H2"},{"type":"Jutter","id":"J1"}]}""", 422);
        assertEquals(List.of("ANDERE"), kinds(body), body.toString());
    }

    @Test
    void second_filling_beside_a_division_by_zero_422_in_uchar_order() throws Exception {
        JsonNode body = compare(filling, fillingService, "mengsel", """
            {"instanties":[{"type":"Kustwacht","id":"K1",
              "rollen":{"bewaakte havens":["H1","H2"],"geworven jutters":["J1"]}},
              {"type":"Haven","id":"H1","attributen":{"ligplaatsen":0}},
              {"type":"Haven","id":"H2","attributen":{"ligplaatsen":4}},{"type":"Jutter","id":"J1"}]}""", 422);
        assertEquals(List.of("ANDERE", "DELING_DOOR_NUL"), kinds(body), body.toString());
    }

    private static List<String> kinds(JsonNode body) {
        return body.path("fouten").valueStream().map(f -> f.path("soort").asText()).toList();
    }

    private static String aPath(DecisionCore core) {
        return core.entrypoints().keySet().iterator().next();
    }

    /** The same POST to both adapters over the same kern: the same status ({@code verwacht}) and the same body, which
     *  it returns. */
    private static JsonNode compare(DecisionCore core, ServiceHttp service, String path, String body, int expected)
            throws Exception {
        DecideResource resource = new DecideResource();
        resource.core = core;
        Response quarkus = resource.process(path, null, body);

        HttpResponse<String> jdk = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(expected, jdk.statusCode(), jdk.body());
        assertEquals(expected, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        JsonNode jdkBody = MAPPER.readTree(jdk.body());
        JsonNode quarkusBody = MAPPER.readTree((String) quarkus.getEntity());
        assertEquals(jdkBody, quarkusBody, "het foutlichaam van de Quarkus-adapter wijkt af van dat van DienstHttp");
        return jdkBody;
    }
}
