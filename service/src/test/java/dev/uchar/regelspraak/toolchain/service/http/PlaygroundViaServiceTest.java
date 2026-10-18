package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.Violation;
import dev.uchar.regelspraak.toolchain.service.TokaSource;
import dev.uchar.regelspraak.toolchain.service.FormIndex;

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
 * the verdict uchar's comparison over the session of a request ({@code DecisionCore.assess}). On a document within
 * the scope of an entry point the verdict is that of the ordinary route, in the same shape, with the {@code pad} it ran
 * on. A source that is not the service's own, a service without {@code DETAIL} and another value of {@code via} are a
 * 400; a scenario no entry point carries, and a situation a request cannot carry, are a MARKER with the sentence; an
 * evaluation fault is a FAIL with the diagnose of uchar's report.
 */
class PlaygroundViaServiceTest {

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
    private static final String DAY_COUNT_DESCRIPTOR = """
            { "dienst": {"naam":"dagentelling","versie":"1"},
              "entrypoints": [ { "naam":"dagen","pad":"dagen",
                "scope": {"regelnamen": ["borgdagen van een jutter", "jaardagen van een jutter"]},
                "invoer": ["Jutter"], "uitvoer": ["Jutter.borgdagen"] } ],
              "parameters": {} }""";

    /** The golden TOKA scenarios uchar's scenario resolver refuses ({@code GoldenOverHttpTest.UCHAR_REFUSES}). */
    private static final Set<String> UCHAR_REFUSES = Set.of("scenario-toka-29-passagier-met-recht.rgs",
            "scenario-toka-43-startpunt-vertrek.rgs", "scenario-toka-44-startpunt-min-reserveringstermijn.rgs");

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static DecisionCore berging;
    private static ServiceHttp withDetail;
    private static ServiceHttp withoutDetail;

    @BeforeAll
    static void start() throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        berging = DecisionCore.assemble(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING), CoreConfig.strict().withDetail(),
                cl);
        withDetail = ServiceHttp.bindWith(berging, berging::process, "127.0.0.1", 0, true).start();
        DecisionCore without = DecisionCore.assemble(BERGING_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                CoreConfig.strict(), cl);
        withoutDetail = ServiceHttp.bindWith(without, without::process, "127.0.0.1", 0, true).start();
    }

    @AfterAll
    static void stop() {
        if (withDetail != null) withDetail.stop(0);
        if (withoutDetail != null) withoutDetail.stop(0);
    }

    // ---------------- the verdict of the ordinary route ----------------

    /**
     * Every golden TOKA scenario that uchar's scenario resolver takes (the exit-0 lines of
     * {@code examples/toka-scenarios/manifest}), each on a service of its own companions, with the entry points of
     * {@code examples/toka/toka.service.json} whose rules those carry: the document is the companions as the service
     * reads them and the scenario after them. Via the service every result has the verdict of the ordinary route
     * (status, diagnose, and per expectation what was expected, what was there and whether it held, with its line),
     * the same situation, the keys of the ordinary result with {@code pad} beside them, and the entry point
     * {@code GoldenOverHttpTest} maps the scenario's scope to.
     */
    @TestFactory
    Stream<DynamicTest> via_service_gives_the_verdict_of_the_ordinary_route() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        AtomicInteger compared = new AtomicInteger();
        AtomicInteger pass = new AtomicInteger();
        for (String line : Files.readAllLines(TokaSource.REPO.resolve("examples/toka-scenarios/manifest"))) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length < 2 || !"0".equals(fields[0])) continue;
            Path scenario = TokaSource.REPO.resolve(fields[1]);
            if (UCHAR_REFUSES.contains(scenario.getFileName().toString())) continue;
            List<Path> companions = new ArrayList<>();
            for (int i = 2; i < fields.length; i++) companions.add(TokaSource.REPO.resolve(fields[i]));
            tests.add(DynamicTest.dynamicTest(scenario.getFileName().toString(), () -> {
                Map<String, String> sources = new LinkedHashMap<>();
                StringBuilder document = new StringBuilder();
                for (Path c : companions) {
                    String text = Files.readString(c);
                    sources.put(c.toString(), text);
                    document.append(text).append('\n');
                }
                document.append(Files.readString(scenario)).append('\n');
                DecisionCore core = DecisionCore.assemble(tokaDescriptor(sources), sources,
                        CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
                ObjectNode ordinary = Playground.analyseer(document.toString(), null);
                ObjectNode service = Playground.analyseer(document.toString(), null, core);
                JsonNode rg = ordinary.path("scenario").path("resultaten");
                JsonNode rd = service.path("scenario").path("resultaten");
                assertTrue(rg.size() > 0, () -> "de gewone route gaf geen scenario: " + ordinary.path("resolver"));
                assertEquals(rg.size(), rd.size());
                for (int i = 0; i < rg.size(); i++) {
                    JsonNode g = rg.get(i), d = rd.get(i);
                    String ctx = scenario.getFileName() + " / " + g.path("naam").asText();
                    assertEquals(g.path("status"), d.path("status"), ctx + "\ngewoon: " + g + "\ndienst: " + d);
                    assertEquals(g.path("diagnose"), d.path("diagnose"), ctx);
                    assertEquals(g.path("verwachtingen"), d.path("verwachtingen"), ctx);
                    assertEquals(g.path("situatie"), d.path("situatie"), ctx);
                    List<String> keys = new ArrayList<>();
                    d.fieldNames().forEachRemaining(keys::add);
                    keys.remove("pad");
                    List<String> ordinaryKeys = new ArrayList<>();
                    g.fieldNames().forEachRemaining(ordinaryKeys::add);
                    assertEquals(ordinaryKeys, keys, ctx);
                    assertEquals(pathForScope(core, g.path("naam").asText(), document.toString()),
                            d.path("pad").asText(), ctx);
                    compared.incrementAndGet();
                    if ("PASS".equals(d.path("status").asText())) pass.incrementAndGet();
                }
            }));
        }
        tests.add(DynamicTest.dynamicTest("26 golden scenario's vergeleken, alle PASS", () -> {
            assertEquals(26, compared.get());
            assertEquals(26, pass.get());
        }));
        return tests.stream();
    }

    /** {@code examples/toka/toka.service.json} with the entry points whose rules {@code sources} declare. */
    private static String tokaDescriptor(Map<String, String> sources) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String t : sources.values()) text.append(t).append('\n');
        RegelSpraakModel model = Resolver.resolve(text.toString());
        FormIndex forms = FormIndex.van(model, model.prioritizedDefaults);
        ObjectNode descriptor = (ObjectNode) ServiceHttp.MAPPER.readTree(TokaSource.descriptorJson());
        ArrayNode kept = ServiceHttp.MAPPER.createArrayNode();
        for (JsonNode ep : descriptor.get("entrypoints")) {
            boolean all = true;
            for (JsonNode name : ep.path("scope").path("regelnamen")) all &= forms.exists(name.asText());
            if (all) kept.add(ep);
        }
        descriptor.set("entrypoints", kept);
        return ServiceHttp.MAPPER.writeValueAsString(descriptor);
    }

    /** The entry point whose rule names are the scenario's scope, as {@code GoldenOverHttpTest.pathForScope} finds it. */
    private static String pathForScope(DecisionCore core, String scenarioName, String document) {
        var bundle = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(document);
        var scenario = bundle.scenarios().stream().filter(s -> s.name().equals(scenarioName)).findFirst().orElseThrow();
        List<String> names = new ArrayList<>();
        for (String form : scenario.scope().forms()) {
            String name = dev.uchar.regelspraak.emit.CaseEmitter.basisNaam(form);
            if (!names.contains(name)) names.add(name);
        }
        for (var ep : core.descriptor().entrypoints())
            if (ep.scope().ruleNamesOrEmpty().containsAll(names) && names.containsAll(ep.scope().ruleNamesOrEmpty()))
                return ep.path();
        throw new AssertionError("geen entrypoint met de scope " + names);
    }

    /** A FAIL keeps uchar's diagnose and its expectations; the situation and the fixpoint are those of the playground,
     *  the input amount with the scale the situation writes. */
    @Test
    void a_fail_has_the_diagnostic_of_the_ordinary_route() {
        String source = BERGING + "\n" + SCENARIO.formatted("4", "30,00");
        JsonNode g = Playground.analyseer(source, null).path("scenario").path("resultaten").get(0);
        JsonNode d = Playground.analyseer(source, null, berging).path("scenario").path("resultaten").get(0);
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
    void an_evaluation_fault_is_a_fail_with_the_diagnostic_of_the_uchar_report() {
        String source = BERGING + "\n" + SCENARIO.formatted("0", "20,00");
        JsonNode g = Playground.analyseer(source, null).path("scenario").path("resultaten").get(0);
        JsonNode d = Playground.analyseer(source, null, berging).path("scenario").path("resultaten").get(0);
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

    /** A characteristic with periods has no request form: MARKER with the sentence of {@code SituationToRequest}, where
     *  the ordinary route plays the timeline. */
    @Test
    void a_timeline_in_the_situation_is_a_marker() throws IOException {
        Path golden = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-dagentelling-rgs");
        String rgs = Files.readString(golden.resolve("bron.rgs"));
        DecisionCore core = DecisionCore.assemble(DAY_COUNT_DESCRIPTOR, Map.of("bron.rgs", rgs),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
        String source = rgs + "\n" + Files.readString(golden.resolve("scenario.rgs"));
        JsonNode g = Playground.analyseer(source, null).path("scenario").path("resultaten").get(0);
        JsonNode d = Playground.analyseer(source, null, core).path("scenario").path("resultaten").get(0);
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
    void without_an_entry_point_a_visible_refusal() {
        DecisionCore premie = DecisionCore.assemble(PREMIE_DESCRIPTOR, Map.of("berging.rgs", BERGING),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
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
    void other_sources_than_that_of_the_service_a_400() throws Exception {
        String different = BERGING.replace("gedeeld door", "maal") + "\n" + SCENARIO.formatted("4", "20,00");
        HttpResponse<String> r = post(withDetail, "?via=dienst", different);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode s = ServiceHttp.MAPPER.readTree(r.body()).path("schendingen").get(0);
        assertEquals("bron", s.path("veld").asText());
        assertEquals("NIET_ONDERSTEUND", s.path("soort").asText());
        assertTrue(s.path("fout").asText().startsWith("via=dienst rekent met de bronnen waarmee de dienst gestart is, en"
                + " het uchar-ir-v1-document van de geposte bronnen is niet dat van de dienst; eerste verschil /rules/"),
                s.path("fout").asText());

        HttpResponse<String> own = post(withDetail, "?via=dienst", BERGING + "\n" + SCENARIO.formatted("4", "20,00"));
        assertEquals(200, own.statusCode(), own.body());
        JsonNode result = ServiceHttp.MAPPER.readTree(own.body()).path("scenario").path("resultaten").get(0);
        assertEquals("PASS", result.path("status").asText(), result.toString());
        assertEquals("berging", result.path("pad").asText());

        // the core refuses the same, without HTTP
        ServiceError error = assertThrows(ServiceError.class, () -> Playground.analyseer(different, null, berging));
        assertEquals(Violation.Kind.NIET_ONDERSTEUND, error.violations().get(0).kind());
    }

    /** The difference is named by a JSON pointer, a missing key and the length of an array as well. */
    @Test
    void the_first_difference() throws Exception {
        JsonNode a = ServiceHttp.MAPPER.readTree("{\"x\": [1, {\"y\": \"a\"}], \"z\": 1}");
        assertEquals(null, Playground.difference(a, a.deepCopy(), ""));
        assertEquals("/x/1/y: de geposte bronnen hebben \"a\", de dienst \"b\"", Playground.difference(a,
                ServiceHttp.MAPPER.readTree("{\"x\": [1, {\"y\": \"b\"}], \"z\": 1}"), ""));
        assertEquals("/x: 2 elementen in de geposte bronnen, 1 in de dienst", Playground.difference(a,
                ServiceHttp.MAPPER.readTree("{\"x\": [1], \"z\": 1}"), ""));
        assertEquals("/z: de geposte bronnen hebben 1, de dienst niets", Playground.difference(a,
                ServiceHttp.MAPPER.readTree("{\"x\": [1, {\"y\": \"a\"}]}"), ""));
    }

    /** A service started without detail answers {@code via=dienst} with a 400 that names {@code DETAIL}; its
     *  playground without the flag answers as before. */
    @Test
    void without_detail_a_400_that_names_DETAIL() throws Exception {
        String source = BERGING + "\n" + SCENARIO.formatted("4", "20,00");
        HttpResponse<String> r = post(withoutDetail, "?via=dienst", source);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode s = ServiceHttp.MAPPER.readTree(r.body()).path("schendingen").get(0);
        assertEquals("via", s.path("veld").asText());
        assertEquals("NIET_ONDERSTEUND", s.path("soort").asText());
        assertTrue(s.path("fout").asText().contains("DETAIL=true"), s.path("fout").asText());
        assertEquals(200, post(withoutDetail, "", source).statusCode());
    }

    /** {@code via} names the service and nothing else. */
    @Test
    void another_value_of_via_is_a_400() throws Exception {
        HttpResponse<String> r = post(withDetail, "?via=playground", BERGING);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode s = ServiceHttp.MAPPER.readTree(r.body()).path("schendingen").get(0);
        assertEquals("via", s.path("veld").asText());
        assertEquals("via kent alleen de waarde 'dienst', niet 'playground'", s.path("fout").asText());
    }

    /** Over HTTP the answer of {@code via=dienst} has the keys of the ordinary one, the scenario part included. */
    @Test
    void the_same_form_over_http() throws Exception {
        String source = BERGING + "\n" + SCENARIO.formatted("4", "20,00");
        JsonNode ordinary = ServiceHttp.MAPPER.readTree(post(withDetail, "", source).body());
        JsonNode service = ServiceHttp.MAPPER.readTree(post(withDetail, "?via=dienst", source).body());
        assertEquals(keys(ordinary), keys(service));
        JsonNode g = ordinary.path("scenario").path("resultaten").get(0);
        JsonNode d = service.path("scenario").path("resultaten").get(0);
        List<String> withPath = keys(g);
        withPath.add(withPath.indexOf("regel") + 1, "pad");
        assertEquals(withPath, keys(d));
        assertEquals(g.path("status"), d.path("status"));
        assertNotNull(d.get("fixpunt"));
    }

    private static List<String> keys(JsonNode n) {
        List<String> sink = new ArrayList<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) sink.add(it.next());
        return sink;
    }

    private static HttpResponse<String> post(ServiceHttp service, String query, String source) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + "/playground/analyse" + query))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(source, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
