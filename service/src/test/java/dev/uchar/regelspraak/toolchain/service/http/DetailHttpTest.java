package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.TokaBron;
import dev.uchar.regelspraak.toolchain.service.VerzoekOpties;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detail of a request's session on {@code POST /<pad>}: {@code ?trace}, {@code ?dekking} and {@code ?fixpunt} on a
 * service started with detail ({@link KernConfig#detail()}). The fixpoint has the form the playground gives a scenario,
 * with the instances of the request as its input: on the same situation the two give the same {@code fixpunt}, and the
 * same {@code trace} and {@code dekking}. A 422 carries the asked detail beside its faults. A service started without
 * detail refuses each flag with a 400 naming it, and a request without a flag gets the answer of a service without
 * detail, byte for byte.
 */
class DetailHttpTest {

    /** The playground's own material ({@code PlaygroundScenarioGegevensTest}): a division by the helpers that is a
     *  fault at zero. */
    private static final String BERGING = """
            Eenheidsysteem Valuta
                de euro (mv: euros) EUR €
            Objecttype de Berging (mv: Bergingen)
                de opbrengst Numeriek (geheel getal) met eenheid €;
                het helpersaantal Numeriek (geheel getal);
                het helpersdeel Numeriek (geheel getal) met eenheid €;
                de premie Numeriek (getal met 2 decimalen) met eenheid €;
            Parameter het premiepercentage : Percentage (geheel getal)
            Regel premie van een berging
            geldig altijd
            De premie van een Berging moet berekend worden als het premiepercentage van de opbrengst van de Berging.
            Regel helpersdeel van een berging
            geldig altijd
            Het helpersdeel van een Berging moet berekend worden als de opbrengst van de Berging gedeeld door het helpersaantal van de Berging.
            """;
    private static final String BERGING_DESCRIPTOR = """
            { "dienst": {"naam":"berging","versie":"1"},
              "entrypoints": [ { "naam":"berging","pad":"berging",
                "scope": {"regelnamen": ["premie van een berging", "helpersdeel van een berging"]},
                "invoer": ["Berging"], "uitvoer": ["Berging.premie"] } ],
              "parameters": {} }""";
    private static final String SITUATIE = """
            Situatie basis:
            een Berging B
            - de opbrengst is 100 €
            - het helpersaantal is %s
            de parameter het premiepercentage is 20 %%
            Scenario berging
            met peildatum 01-01-2026
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de premie van B is 20,00 €
            """;

    /** uchar's golden {@code jutterswet-strandmelding}: an object creation per Melding and the links it lays. The
     *  amounts are whole: the service reads a JSON number such as {@code 20.00} as {@code 20}, so a fixpoint of the
     *  service shows an input amount without the scale the situation writes, where the playground keeps it. */
    private static final String STRANDMELDING_DESCRIPTOR = """
            { "dienst": {"naam":"strandmelding","versie":"1"},
              "entrypoints": [ { "naam":"melding","pad":"melding",
                "scope": {"regelnamen": ["maak een strandrecht bij elke melding", "uitkering van een wrakrecht",
                                         "lees de gemelde uitkering"]},
                "invoer": ["Melding"], "uitvoer": ["Melding.gemelde uitkering"] } ],
              "parameters": {} }""";
    private static final String MELDINGEN = """

            Scenario twee meldingen
            Gegeven is de volgende situatie:
            een Melding M2
            - de omvang is 20
            een Melding M1
            - de omvang is 10
            Met als verwacht resultaat:
            - de gemelde uitkering van M1 is 10
            """;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static DienstHttp metDetail;
    private static DienstHttp zonderDetail;
    private static DienstHttp strandmelding;
    private static BeslisserKern strandmeldingKern;
    private static String strandmeldingBron;

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        metDetail = DienstHttp.bind(BeslisserKern.bouw(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                KernConfig.strikt().metDetail(), cl), "127.0.0.1", 0).start();
        zonderDetail = DienstHttp.bind(BeslisserKern.bouw(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                KernConfig.strikt(), cl), "127.0.0.1", 0).start();
        strandmeldingBron = Files.readString(
                TokaBron.REPO.resolve("uchar/corpus/golden/jutterswet-strandmelding/bron.rgs"));
        strandmeldingKern = BeslisserKern.bouw(STRANDMELDING_DESCRIPTOR, Map.of("bron.rgs", strandmeldingBron),
                KernConfig.strikt().metDetail(), cl);
        strandmelding = DienstHttp.bind(strandmeldingKern, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        for (DienstHttp d : new DienstHttp[]{metDetail, zonderDetail, strandmelding}) if (d != null) d.stop(0);
    }

    // ---------------- the detail of a 200 and a 422 ----------------

    /** All three on a 200: the trace per form with the ids of the request, the coverage of the entry point, the
     *  fixpoint with the input and the derived values; the answer itself is the one without flags, the detail after
     *  it. */
    @Test
    void trace_dekking_en_fixpunt_bij_een_antwoord() throws Exception {
        String verzoek = bergingVerzoek("B7", 2);
        HttpResponse<String> zonder = post(metDetail, "berging", "", verzoek);
        HttpResponse<String> met = post(metDetail, "berging", "trace=true&dekking=true&fixpunt=true", verzoek);
        assertEquals(200, met.statusCode(), met.body());
        ObjectNode a = (ObjectNode) DienstHttp.MAPPER.readTree(met.body());
        assertEquals(List.of("instanties", "inconsistenties", "inconsistentieDetails", "trace", "dekking", "fixpunt"),
                velden(a));

        ObjectNode kaal = a.deepCopy();
        kaal.remove(List.of("trace", "dekking", "fixpunt"));
        assertEquals(DienstHttp.MAPPER.readTree(zonder.body()), kaal, "het antwoord zelf blijft gelijk");

        // the trace in the order a rule of a form first fired, the coverage in the order of the manifest
        JsonNode trace = a.get("trace");
        assertEquals(Set.of("premie van een berging", "helpersdeel van een berging"), Set.copyOf(tekst(trace, "regel")));
        assertEquals(2, trace.size(), trace.toString());
        for (JsonNode t : trace) {
            assertEquals("[\"B7\"]", t.get("labels").toString(), t.toString());
            assertEquals(1, t.get("aantal").asInt(), t.toString());
        }
        JsonNode dekking = a.get("dekking");
        assertEquals(Set.of("premie van een berging", "helpersdeel van een berging"),
                Set.copyOf(tekst(dekking, "regel")));
        assertEquals(List.of("GEVUURD", "GEVUURD"), tekst(dekking, "soort"));

        JsonNode b = a.at("/fixpunt/objecten/0");
        assertEquals("B7", b.get("object").asText());
        assertEquals("Berging", b.get("objecttype").asText());
        List<String> rijen = new ArrayList<>();
        for (JsonNode w : b.get("waarden"))
            rijen.add(w.get("attribuut").asText() + "=" + w.get("waarde").asText() + " " + w.get("herkomst").asText());
        assertEquals(List.of("opbrengst=100 invoer", "helpersaantal=2 invoer", "helpersdeel=50 afgeleid",
                "premie=20 afgeleid"), rijen);
        assertEquals("EUR", b.at("/waarden/0/eenheid").asText(), b.toString());
    }

    /** Only what is asked: the trace alone, after the explanation. */
    @Test
    void alleen_wat_gevraagd_is() throws Exception {
        HttpResponse<String> r = post(metDetail, "berging", "uitleg=true&trace", bergingVerzoek("B", 2));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(List.of("instanties", "inconsistenties", "inconsistentieDetails", "uitleg", "trace"),
                velden(DienstHttp.MAPPER.readTree(r.body())));
    }

    /** A division by zero: the 422 keeps its faults and gets the asked detail beside them, the fault as data in the
     *  fixpoint with the object by its id. */
    @Test
    void een_422_krijgt_het_fixpunt() throws Exception {
        String verzoek = bergingVerzoek("B", 0);
        HttpResponse<String> zonder = post(metDetail, "berging", "", verzoek);
        HttpResponse<String> met = post(metDetail, "berging", "fixpunt=true&trace=true", verzoek);
        assertEquals(422, met.statusCode(), met.body());
        ObjectNode a = (ObjectNode) DienstHttp.MAPPER.readTree(met.body());
        assertEquals(List.of("status", "fout", "fouten", "trace", "fixpunt"), velden(a));
        ObjectNode kaal = a.deepCopy();
        kaal.remove(List.of("trace", "fixpunt"));
        assertEquals(DienstHttp.MAPPER.readTree(zonder.body()), kaal, "de fouten blijven gelijk");
        JsonNode fout = a.at("/fixpunt/fouten/0");
        assertEquals("DELING_DOOR_NUL", fout.get("soort").asText(), a.toString());
        assertEquals("helpersdeel van een berging", fout.get("regel").asText());
        assertEquals("B", fout.get("onderwerp").asText());
        assertEquals(a.at("/fouten/0/fout").asText(), fout.get("zin").asText());
        assertEquals(1, a.at("/fixpunt/fouten").size());
    }

    // ---------------- the form of the playground ----------------

    /** On the same situation the service and the playground give the same fixpoint, trace and coverage: the request is
     *  the situation, derived mechanically ({@link SituatieNaarVerzoek}), and the entry point scopes every rule. */
    @Test
    void dezelfde_vorm_als_de_playground() throws Exception {
        String bron = BERGING + SITUATIE.formatted("2");
        JsonNode playground = Playground.analyseer(bron).at("/scenario/resultaten/0");
        JsonNode dienst = detailVan(metDetail, "berging", bron);
        assertEquals(playground.get("fixpunt"), dienst.get("fixpunt"));
        assertEquals(playground.get("trace"), dienst.get("trace"));
        assertEquals(playground.get("dekking"), dienst.get("dekking"));
    }

    /** The fault as data: the playground's fixpoint of a case uchar's replay does not play out, and the service's of
     *  its 422, are the same. */
    @Test
    void dezelfde_fout_als_de_playground() throws Exception {
        String bron = BERGING + SITUATIE.formatted("0");
        JsonNode playground = Playground.analyseer(bron).at("/scenario/resultaten/0");
        JsonNode dienst = detailVan(metDetail, "berging", bron);
        assertEquals(playground.get("fixpunt"), dienst.get("fixpunt"));
    }

    /** Made objects and the links of their creation, as the playground names them: after the input, by their
     *  subject, {@code #<n>}. */
    @Test
    void gemaakte_objecten_zoals_de_playground() throws Exception {
        String bron = strandmeldingBron + MELDINGEN;
        JsonNode playground = Playground.analyseer(bron).at("/scenario/resultaten/0");
        JsonNode dienst = detailVan(strandmelding, "melding", bron);
        assertEquals(List.of("M2", "M1", "#1", "#2"), tekst(dienst.at("/fixpunt/objecten"), "object"));
        assertEquals(2, dienst.at("/fixpunt/koppelingen").size(), dienst.toString());
        assertEquals(playground.get("fixpunt"), dienst.get("fixpunt"));
        assertEquals(playground.get("trace"), dienst.get("trace"));
    }

    /** The kern itself, without HTTP: the request tree of the situation, with its numbers exact, gives the playground's
     *  fixpoint and trace, the scale of an amount included ({@code 20.00}), which a JSON body read over HTTP loses. */
    @Test
    void de_kern_zonder_http_geeft_de_vorm_van_de_playground() {
        String bron = strandmeldingBron + MELDINGEN.replace(" is 20", " is 20,00").replace(" is 10", " is 10,00");
        JsonNode playground = Playground.analyseer(bron).at("/scenario/resultaten/0");
        var bundel = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(bron);
        ObjectNode dienst = strandmeldingKern.verwerk("melding", SituatieNaarVerzoek.bouw(bundel.scenarios().get(0)),
                new VerzoekOpties(false, null, null, true, true, true));
        assertEquals("20.00", dienst.at("/fixpunt/objecten/0/waarden/0/waarde").asText(), dienst.toString());
        assertEquals(playground.get("fixpunt"), dienst.get("fixpunt"));
        assertEquals(playground.get("trace"), dienst.get("trace"));
    }

    // ---------------- without DETAIL, and without a flag ----------------

    /** Without detail every flag asked is a 400 naming it, and the body is not read; a flag set to another value than
     *  true asks nothing. */
    @Test
    void zonder_detail_een_400_met_de_naam_van_de_vlag() throws Exception {
        HttpResponse<String> r = post(zonderDetail, "berging", "fixpunt=true&trace&dekking=TRUE", "{}");
        assertEquals(400, r.statusCode(), r.body());
        JsonNode s = DienstHttp.MAPPER.readTree(r.body()).get("schendingen");
        assertEquals(List.of("trace", "dekking", "fixpunt"), tekst(s, "veld"));
        for (JsonNode n : s) {
            assertEquals("NIET_ONDERSTEUND", n.get("soort").asText(), n.toString());
            assertTrue(n.get("fout").asText().contains("DETAIL"), n.toString());
        }
        HttpResponse<String> uit = post(zonderDetail, "berging", "trace=false", bergingVerzoek("B", 2));
        assertEquals(200, uit.statusCode(), uit.body());
        assertArrayEquals(post(zonderDetail, "berging", "", bergingVerzoek("B", 2)).body()
                .getBytes(StandardCharsets.UTF_8), uit.body().getBytes(StandardCharsets.UTF_8));
    }

    /** A request without a flag gets from a service with detail the bytes a service without detail gives, for an
     *  answer and for a 422. */
    @Test
    void zonder_vlag_bytegelijk() throws Exception {
        for (String verzoek : List.of(bergingVerzoek("B", 2), bergingVerzoek("B", 0))) {
            HttpResponse<String> met = post(metDetail, "berging", "", verzoek);
            HttpResponse<String> zonder = post(zonderDetail, "berging", "", verzoek);
            assertEquals(zonder.statusCode(), met.statusCode(), met.body());
            assertArrayEquals(zonder.body().getBytes(StandardCharsets.UTF_8), met.body().getBytes(StandardCharsets.UTF_8));
            assertFalse(met.body().contains("\"trace\""), met.body());
        }
    }

    // ---------------- the switches ----------------

    @Test
    void de_vlaggen_en_de_startinstelling() {
        VerzoekOpties o = DienstHttp.opties("Trace=TRUE&dekking&fixpunt=nee&uitleg=true");
        assertEquals(new VerzoekOpties(true, null, null, true, true, false), o);
        assertEquals(List.of("trace", "dekking"), o.detailvlaggen());
        assertEquals(VerzoekOpties.STANDAARD, DienstHttp.opties("trace=false"));
        assertTrue(DienstMain.parseArgs(new String[]{"d.json", "x.rgs", "--detail"}).detail());
        assertFalse(DienstMain.parseArgs(new String[]{"d.json", "x.rgs"}).detail());
        assertEquals(new KernConfig(true, true), KernConfig.metMarkers().metDetail());
        assertFalse(KernConfig.strikt().detail());
    }

    // ---------------- helpers ----------------

    private static String bergingVerzoek(String id, int helpers) {
        return """
                { "instanties": [ { "type": "Berging", "id": "%s",
                                    "attributen": { "opbrengst": 100, "helpersaantal": %d } } ],
                  "parameters": { "premiepercentage": 20 }, "rekendatum": "2026-01-01" }""".formatted(id, helpers);
    }

    /** The answer of {@code dienst} with all three flags to the situation of the first scenario of {@code bron}. */
    private static JsonNode detailVan(DienstHttp dienst, String pad, String bron) throws Exception {
        var bundel = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(bron);
        assertTrue(bundel.isOk(), () -> String.valueOf(bundel.model().errors()));
        String verzoek = DienstHttp.MAPPER.writeValueAsString(SituatieNaarVerzoek.bouw(bundel.scenarios().get(0)));
        HttpResponse<String> r = post(dienst, pad, "trace=true&dekking=true&fixpunt=true", verzoek);
        assertTrue(Set.of(200, 422).contains(r.statusCode()), r.body());
        return DienstHttp.MAPPER.readTree(r.body());
    }

    private static HttpResponse<String> post(DienstHttp dienst, String pad, String query, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + dienst.poort() + "/" + pad
                        + (query.isEmpty() ? "" : "?" + query)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static List<String> velden(JsonNode n) {
        List<String> uit = new ArrayList<>();
        n.fieldNames().forEachRemaining(uit::add);
        return uit;
    }

    private static List<String> tekst(JsonNode lijst, String sleutel) {
        List<String> uit = new ArrayList<>();
        for (JsonNode n : lijst) uit.add(n.get(sleutel).asText());
        return uit;
    }
}
