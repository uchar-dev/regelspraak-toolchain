package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.DienstFout;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.Schending;
import dev.uchar.regelspraak.toolchain.service.TokaBron;
import dev.uchar.regelspraak.toolchain.service.VormIndex;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /playground/analyse?via=dienst}: the scenarios of a document computed by the service the process runs,
 * the verdict uchar's comparison over the session of a request ({@code BeslisserKern.beoordeel}). On a document within
 * the scope of an entry point the verdict is that of the ordinary route, in the same shape, with the {@code pad} it ran
 * on. A source that is not the service's own, a service without {@code DETAIL} and another value of {@code via} are a
 * 400; a scenario no entry point carries, and a situation a request cannot carry, are a MARKER with the sentence; an
 * evaluation fault is a FAIL with the diagnose of uchar's report.
 */
class PlaygroundViaDienstTest {

    /** The playground's own material ({@code DetailHttpTest}): a division by the helpers that is a fault at zero. */
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
    /** An entry point that carries one of the two rules: a scenario without a scope runs on both. */
    private static final String PREMIE_DESCRIPTOR = """
            { "dienst": {"naam":"premie","versie":"1"},
              "entrypoints": [ { "naam":"premie","pad":"premie",
                "scope": {"regelnamen": ["premie van een berging"]},
                "invoer": ["Berging"], "uitvoer": ["Berging.premie"] } ],
              "parameters": {} }""";
    private static final String SCENARIO = """
            Situatie basis:
            een Berging B
            - de opbrengst is 100 €
            - het helpersaantal is %s
            de parameter het premiepercentage is 20 %%
            Scenario berging
            met peildatum 01-01-2026
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de premie van B is %s €
            """;
    private static final String DAGENTELLING_DESCRIPTOR = """
            { "dienst": {"naam":"dagentelling","versie":"1"},
              "entrypoints": [ { "naam":"dagen","pad":"dagen",
                "scope": {"regelnamen": ["borgdagen van een jutter", "jaardagen van een jutter"]},
                "invoer": ["Jutter"], "uitvoer": ["Jutter.borgdagen"] } ],
              "parameters": {} }""";

    /** The golden TOKA scenarios uchar's scenario resolver refuses ({@code GoldenOverHttpTest.UCHAR_WEIGERT}). */
    private static final Set<String> UCHAR_WEIGERT = Set.of("scenario-toka-29-passagier-met-recht.rgs",
            "scenario-toka-43-startpunt-vertrek.rgs", "scenario-toka-44-startpunt-min-reserveringstermijn.rgs");

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static BeslisserKern berging;
    private static DienstHttp metDetail;
    private static DienstHttp zonderDetail;

    @BeforeAll
    static void start() throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        berging = BeslisserKern.bouw(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING), KernConfig.strikt().metDetail(),
                cl);
        metDetail = DienstHttp.bindMet(berging, berging::verwerk, "127.0.0.1", 0, true).start();
        BeslisserKern zonder = BeslisserKern.bouw(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                KernConfig.strikt(), cl);
        zonderDetail = DienstHttp.bindMet(zonder, zonder::verwerk, "127.0.0.1", 0, true).start();
    }

    @AfterAll
    static void stop() {
        if (metDetail != null) metDetail.stop(0);
        if (zonderDetail != null) zonderDetail.stop(0);
    }

    // ---------------- the verdict of the ordinary route ----------------

    /**
     * Every golden TOKA scenario that uchar's scenario resolver takes (the exit-0 lines of
     * {@code examples/toka-scenarios/manifest}), each on a service of its own companions, with the entry points of
     * {@code examples/toka/toka.dienst.json} whose rules those carry: the document is the companions as the service
     * reads them and the scenario after them. Via the service every result has the verdict of the ordinary route
     * (status, diagnose, and per expectation what was expected, what was there and whether it held, with its line),
     * the same situation, the keys of the ordinary result with {@code pad} beside them, and the entry point
     * {@code GoldenOverHttpTest} maps the scenario's scope to.
     */
    @TestFactory
    Stream<DynamicTest> via_dienst_geeft_het_oordeel_van_de_gewone_route() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        AtomicInteger vergeleken = new AtomicInteger();
        AtomicInteger pass = new AtomicInteger();
        for (String regel : Files.readAllLines(TokaBron.REPO.resolve("examples/toka-scenarios/manifest"))) {
            String[] velden = regel.strip().split("\\s+");
            if (velden.length < 2 || !"0".equals(velden[0])) continue;
            Path scenario = TokaBron.REPO.resolve(velden[1]);
            if (UCHAR_WEIGERT.contains(scenario.getFileName().toString())) continue;
            List<Path> companions = new ArrayList<>();
            for (int i = 2; i < velden.length; i++) companions.add(TokaBron.REPO.resolve(velden[i]));
            tests.add(DynamicTest.dynamicTest(scenario.getFileName().toString(), () -> {
                Map<String, String> bronnen = new LinkedHashMap<>();
                StringBuilder document = new StringBuilder();
                for (Path c : companions) {
                    String tekst = Files.readString(c);
                    bronnen.put(c.toString(), tekst);
                    document.append(tekst).append('\n');
                }
                document.append(Files.readString(scenario)).append('\n');
                BeslisserKern kern = BeslisserKern.bouw(tokaDescriptor(bronnen), bronnen,
                        KernConfig.strikt().metDetail(), Thread.currentThread().getContextClassLoader());
                ObjectNode gewoon = Playground.analyseer(document.toString(), null);
                ObjectNode dienst = Playground.analyseer(document.toString(), null, kern);
                JsonNode rg = gewoon.path("scenario").path("resultaten");
                JsonNode rd = dienst.path("scenario").path("resultaten");
                assertTrue(rg.size() > 0, () -> "de gewone route gaf geen scenario: " + gewoon.path("resolver"));
                assertEquals(rg.size(), rd.size());
                for (int i = 0; i < rg.size(); i++) {
                    JsonNode g = rg.get(i), d = rd.get(i);
                    String ctx = scenario.getFileName() + " / " + g.path("naam").asText();
                    assertEquals(g.path("status"), d.path("status"), ctx + "\ngewoon: " + g + "\ndienst: " + d);
                    assertEquals(g.path("diagnose"), d.path("diagnose"), ctx);
                    assertEquals(g.path("verwachtingen"), d.path("verwachtingen"), ctx);
                    assertEquals(g.path("situatie"), d.path("situatie"), ctx);
                    List<String> sleutels = new ArrayList<>();
                    d.fieldNames().forEachRemaining(sleutels::add);
                    sleutels.remove("pad");
                    List<String> gewoneSleutels = new ArrayList<>();
                    g.fieldNames().forEachRemaining(gewoneSleutels::add);
                    assertEquals(gewoneSleutels, sleutels, ctx);
                    assertEquals(padVoorScope(kern, g.path("naam").asText(), document.toString()),
                            d.path("pad").asText(), ctx);
                    vergeleken.incrementAndGet();
                    if ("PASS".equals(d.path("status").asText())) pass.incrementAndGet();
                }
            }));
        }
        tests.add(DynamicTest.dynamicTest("26 golden scenario's vergeleken, alle PASS", () -> {
            assertEquals(26, vergeleken.get());
            assertEquals(26, pass.get());
        }));
        return tests.stream();
    }

    /** {@code examples/toka/toka.dienst.json} with the entry points whose rules {@code bronnen} declare. */
    private static String tokaDescriptor(Map<String, String> bronnen) throws IOException {
        StringBuilder tekst = new StringBuilder();
        for (String t : bronnen.values()) tekst.append(t).append('\n');
        RegelSpraakModel model = Resolver.resolve(tekst.toString());
        VormIndex vormen = VormIndex.van(model, model.prioritizedDefaults);
        ObjectNode descriptor = (ObjectNode) DienstHttp.MAPPER.readTree(TokaBron.descriptorJson());
        ArrayNode gehouden = DienstHttp.MAPPER.createArrayNode();
        for (JsonNode ep : descriptor.get("entrypoints")) {
            boolean alle = true;
            for (JsonNode naam : ep.path("scope").path("regelnamen")) alle &= vormen.bestaat(naam.asText());
            if (alle) gehouden.add(ep);
        }
        descriptor.set("entrypoints", gehouden);
        return DienstHttp.MAPPER.writeValueAsString(descriptor);
    }

    /** The entry point whose rule names are the scenario's scope, as {@code GoldenOverHttpTest.padVoorScope} finds it. */
    private static String padVoorScope(BeslisserKern kern, String scenarionaam, String document) {
        var bundel = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(document);
        var scenario = bundel.scenarios().stream().filter(s -> s.naam().equals(scenarionaam)).findFirst().orElseThrow();
        List<String> namen = new ArrayList<>();
        for (String vorm : scenario.scope().vormen()) {
            String naam = dev.uchar.regelspraak.emit.CaseEmitter.basisNaam(vorm);
            if (!namen.contains(naam)) namen.add(naam);
        }
        for (var ep : kern.descriptor().entrypoints())
            if (ep.scope().regelnamenOfLeeg().containsAll(namen) && namen.containsAll(ep.scope().regelnamenOfLeeg()))
                return ep.pad();
        throw new AssertionError("geen entrypoint met de scope " + namen);
    }

    /** A FAIL keeps uchar's diagnose and its expectations; the situation and the fixpoint are those of the playground,
     *  the input amount with the scale the situation writes. */
    @Test
    void een_fail_heeft_de_diagnose_van_de_gewone_route() {
        String bron = BERGING + "\n" + SCENARIO.formatted("4", "30,00");
        JsonNode g = Playground.analyseer(bron, null).path("scenario").path("resultaten").get(0);
        JsonNode d = Playground.analyseer(bron, null, berging).path("scenario").path("resultaten").get(0);
        assertEquals("FAIL", d.path("status").asText());
        assertEquals(g.path("diagnose"), d.path("diagnose"));
        assertTrue(d.path("diagnose").asText().startsWith("case1-berging.json: "), d.path("diagnose").asText());
        assertEquals(g.path("verwachtingen"), d.path("verwachtingen"));
        assertFalse(d.path("verwachtingen").get(0).path("ok").asBoolean());
        assertEquals("berging", d.path("pad").asText());
        assertEquals(g.path("fixpunt"), d.path("fixpunt"));
        assertEquals(g.path("trace"), d.path("trace"));
        assertEquals(g.path("dekking"), d.path("dekking"));
    }

    // ---------------- what the service does not compute ----------------

    /** A division by zero is the service's 422: FAIL with the line of uchar's report, the line the ordinary route gives,
     *  and the faults as data in the fixpoint of the 422, beside its trace. */
    @Test
    void een_evaluatiefout_is_een_fail_met_de_diagnose_van_uchars_rapport() {
        String bron = BERGING + "\n" + SCENARIO.formatted("0", "20,00");
        JsonNode g = Playground.analyseer(bron, null).path("scenario").path("resultaten").get(0);
        JsonNode d = Playground.analyseer(bron, null, berging).path("scenario").path("resultaten").get(0);
        assertEquals("FAIL", g.path("status").asText());
        assertEquals("FAIL", d.path("status").asText());
        assertEquals(g.path("diagnose"), d.path("diagnose"));
        assertTrue(d.path("diagnose").asText().startsWith("case1-berging.json: ERROR DelingDoorNul: "),
                d.path("diagnose").asText());
        assertEquals(0, d.path("verwachtingen").size());
        assertEquals(1, d.path("fixpunt").path("fouten").size(), d.toString());
        assertTrue(d.path("trace").size() > 0, d.toString());
        assertTrue(d.has("uitlegFout"), d.toString());
    }

    /** A characteristic with periods has no request form: MARKER with the sentence of {@code SituatieNaarVerzoek}, where
     *  the ordinary route plays the timeline. */
    @Test
    void een_tijdlijn_in_de_situatie_is_een_marker() throws IOException {
        Path golden = TokaBron.REPO.resolve("uchar/corpus/golden/jutterswet-dagentelling-rgs");
        String rgs = Files.readString(golden.resolve("bron.rgs"));
        BeslisserKern kern = BeslisserKern.bouw(DAGENTELLING_DESCRIPTOR, Map.of("bron.rgs", rgs),
                KernConfig.strikt().metDetail(), Thread.currentThread().getContextClassLoader());
        String bron = rgs + "\n" + Files.readString(golden.resolve("scenario.rgs"));
        JsonNode g = Playground.analyseer(bron, null).path("scenario").path("resultaten").get(0);
        JsonNode d = Playground.analyseer(bron, null, kern).path("scenario").path("resultaten").get(0);
        assertEquals("voorbeeld", d.path("naam").asText());
        assertEquals("PASS", g.path("status").asText());
        assertEquals("MARKER", d.path("status").asText());
        assertEquals("kenmerk 'borgplichtig' van J1 is tijdsafhankelijk; het verzoek van de dienst draagt geen perioden",
                d.path("diagnose").asText());
        assertEquals("dagen", d.path("pad").asText());
        assertEquals(0, d.path("verwachtingen").size());
    }

    /** A scenario without a scope runs on every form of the model; an entry point that carries one of the two is no
     *  match, and the result says so, with no {@code pad}. */
    @Test
    void zonder_ingangspunt_een_zichtbare_weigering() {
        BeslisserKern premie = BeslisserKern.bouw(PREMIE_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                KernConfig.strikt().metDetail(), Thread.currentThread().getContextClassLoader());
        JsonNode d = Playground.analyseer(BERGING + "\n" + SCENARIO.formatted("4", "20,00"), null, premie)
                .path("scenario").path("resultaten").get(0);
        assertEquals("MARKER", d.path("status").asText());
        assertTrue(d.path("pad").isNull(), d.toString());
        assertEquals("het scenario noemt geen scope en rekent dus met alle 2 vormen van het model; geen ingangspunt van"
                + " de dienst draagt ze alle: 'premie' mist [helpersdeel van een berging]", d.path("diagnose").asText());
        assertTrue(d.has("situatie"));
    }

    // ---------------- the request as a whole ----------------

    /** Sources that declare the same names in the same number, one rule comparing otherwise, are not the service's: a
     *  400 naming the first difference of the documents. The service's own sources get the analysis. */
    @Test
    void andere_bronnen_dan_die_van_de_dienst_een_400() throws Exception {
        String anders = BERGING.replace("gedeeld door", "maal") + "\n" + SCENARIO.formatted("4", "20,00");
        HttpResponse<String> r = post(metDetail, "?via=dienst", anders);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode s = DienstHttp.MAPPER.readTree(r.body()).path("schendingen").get(0);
        assertEquals("bron", s.path("veld").asText());
        assertEquals("NIET_ONDERSTEUND", s.path("soort").asText());
        assertTrue(s.path("fout").asText().startsWith("via=dienst rekent met de bronnen waarmee de dienst gestart is, en"
                + " het uchar-ir-v1-document van de geposte bronnen is niet dat van de dienst; eerste verschil /rules/"),
                s.path("fout").asText());

        HttpResponse<String> eigen = post(metDetail, "?via=dienst", BERGING + "\n" + SCENARIO.formatted("4", "20,00"));
        assertEquals(200, eigen.statusCode(), eigen.body());
        JsonNode resultaat = DienstHttp.MAPPER.readTree(eigen.body()).path("scenario").path("resultaten").get(0);
        assertEquals("PASS", resultaat.path("status").asText(), resultaat.toString());
        assertEquals("berging", resultaat.path("pad").asText());

        // the core refuses the same, without HTTP
        DienstFout fout = assertThrows(DienstFout.class, () -> Playground.analyseer(anders, null, berging));
        assertEquals(Schending.Soort.NIET_ONDERSTEUND, fout.schendingen().get(0).soort());
    }

    /** The difference is named by a JSON pointer, a missing key and the length of an array as well. */
    @Test
    void het_eerste_verschil() throws Exception {
        JsonNode a = DienstHttp.MAPPER.readTree("{\"x\": [1, {\"y\": \"a\"}], \"z\": 1}");
        assertEquals(null, Playground.verschil(a, a.deepCopy(), ""));
        assertEquals("/x/1/y: de geposte bronnen hebben \"a\", de dienst \"b\"", Playground.verschil(a,
                DienstHttp.MAPPER.readTree("{\"x\": [1, {\"y\": \"b\"}], \"z\": 1}"), ""));
        assertEquals("/x: 2 elementen in de geposte bronnen, 1 in de dienst", Playground.verschil(a,
                DienstHttp.MAPPER.readTree("{\"x\": [1], \"z\": 1}"), ""));
        assertEquals("/z: de geposte bronnen hebben 1, de dienst niets", Playground.verschil(a,
                DienstHttp.MAPPER.readTree("{\"x\": [1, {\"y\": \"a\"}]}"), ""));
    }

    /** A service started without detail answers {@code via=dienst} with a 400 that names {@code DETAIL}; its
     *  playground without the flag answers as before. */
    @Test
    void zonder_detail_een_400_die_DETAIL_noemt() throws Exception {
        String bron = BERGING + "\n" + SCENARIO.formatted("4", "20,00");
        HttpResponse<String> r = post(zonderDetail, "?via=dienst", bron);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode s = DienstHttp.MAPPER.readTree(r.body()).path("schendingen").get(0);
        assertEquals("via", s.path("veld").asText());
        assertEquals("NIET_ONDERSTEUND", s.path("soort").asText());
        assertTrue(s.path("fout").asText().contains("DETAIL=true"), s.path("fout").asText());
        assertEquals(200, post(zonderDetail, "", bron).statusCode());
    }

    /** {@code via} names the service and nothing else. */
    @Test
    void een_andere_waarde_van_via_is_een_400() throws Exception {
        HttpResponse<String> r = post(metDetail, "?via=playground", BERGING);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode s = DienstHttp.MAPPER.readTree(r.body()).path("schendingen").get(0);
        assertEquals("via", s.path("veld").asText());
        assertEquals("via kent alleen de waarde 'dienst', niet 'playground'", s.path("fout").asText());
    }

    /** Over HTTP the answer of {@code via=dienst} has the keys of the ordinary one, the scenario part included. */
    @Test
    void dezelfde_vorm_over_http() throws Exception {
        String bron = BERGING + "\n" + SCENARIO.formatted("4", "20,00");
        JsonNode gewoon = DienstHttp.MAPPER.readTree(post(metDetail, "", bron).body());
        JsonNode dienst = DienstHttp.MAPPER.readTree(post(metDetail, "?via=dienst", bron).body());
        assertEquals(sleutels(gewoon), sleutels(dienst));
        JsonNode g = gewoon.path("scenario").path("resultaten").get(0);
        JsonNode d = dienst.path("scenario").path("resultaten").get(0);
        List<String> metPad = sleutels(g);
        metPad.add(metPad.indexOf("regel") + 1, "pad");
        assertEquals(metPad, sleutels(d));
        assertEquals(g.path("status"), d.path("status"));
        assertNotNull(d.get("fixpunt"));
    }

    private static List<String> sleutels(JsonNode n) {
        List<String> uit = new ArrayList<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) uit.add(it.next());
        return uit;
    }

    private static HttpResponse<String> post(DienstHttp dienst, String query, String bron) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + dienst.poort() + "/playground/analyse" + query))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(bron, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
