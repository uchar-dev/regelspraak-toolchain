package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.ir.ResolvedScenario.Verwachting;
import dev.uchar.regelspraak.ir.ResolvedScenario.Waarde;
import dev.uchar.regelspraak.toolchain.quarkus.TokaTestProfile;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import dev.uchar.regelspraak.toolchain.service.ServiceDescriptor;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q1 GOLDEN PARITY over the QUARKUS transport. The SAME "one truth, two transports" gate as
 * {@code GoldenOverHttpTest}, but the third transport is the Quarkus {@link DecideResource} endpoint booted by
 * {@code @QuarkusTest}. For each of the 29 golden TOKA scenariotoetsen it:
 * <ol>
 *   <li>resolves the scenario (companions + scenario) → the typed {@link ResolvedScenario} IR (the single oracle);</li>
 *   <li>MECHANICALLY derives the request via {@link SituationToRequest#assemble} (reused from the service test-jar —
 *       no expected value, input, label or scope is hand-typed);</li>
 *   <li>routes scope→entrypoint against the descriptor's {@code regelnamen} (exactly {@code GoldenOverHttpTest});</li>
 *   <li>POSTs to the Quarkus URL (RestAssured) and asserts EVERY verwachting with the SAME comparison
 *       {@code GoldenOverHttpTest.equal} uses.</li>
 * </ol>
 * The final assertion pins that every golden scenario was reproduced over Quarkus or refused by uchar's scenario
 * resolver as its entry in {@link #UCHAR_REFUSES} (the same list as {@code GoldenOverHttpTest}) says.
 */
@QuarkusTest
@TestProfile(TokaTestProfile.class)
class GoldenOverQuarkusTest {

    /** Exact-render mapper: a request/response number is never a double (mirror of {@code ServiceHttp.MAPPER}). */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The golden scenario files uchar's scenario resolver refuses, each with a fragment of the refusal; the same
     *  list as {@code GoldenOverHttpTest.UCHAR_REFUSES}. Measured on uchar d67f5da1. */
    private static final Map<String, String> UCHAR_REFUSES = Map.of(
            "scenario-toka-29-passagier-met-recht.rgs",
                    "uit de scope bestaat niet in het document",
            "scenario-toka-43-startpunt-vertrek.rgs",
                    "een scope in een document met een startpuntbepaling wordt geweigerd",
            "scenario-toka-44-startpunt-min-reserveringstermijn.rgs",
                    "een scope in een document met een startpuntbepaling wordt geweigerd");

    private static ServiceDescriptor descriptor;

    private static ServiceDescriptor descriptor() {
        if (descriptor == null) {
            // A plain mapper suffices for scope→pad ROUTING (the descriptor is a POJO; no float handling needed);
            // every expected value still comes from the scenario fixtures, so nothing is re-typed.
            try {
                descriptor = new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .readValue(TokaSource.descriptorJson(), ServiceDescriptor.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return descriptor;
    }

    @TestFactory
    Stream<DynamicTest> golden_over_quarkus() throws IOException {
        List<Golden> golden = readManifest();
        assertEquals(29, golden.size(), "de manifest hoort precies 29 exit-0 golden scenariotoetsen te tellen");

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<DynamicTest> tests = new ArrayList<>();
        for (Golden g : golden) {
            String name = g.scenarioFile.getFileName().toString();
            String refusal = UCHAR_REFUSES.get(name);
            tests.add(DynamicTest.dynamicTest(name, () -> {
                if (refusal != null) {
                    testRefusal(g, refusal);
                    refused.incrementAndGet();
                } else {
                    testScenario(g);
                    succeeded.incrementAndGet();
                }
            }));
        }
        tests.add(DynamicTest.dynamicTest("29 golden: over Quarkus gereproduceerd of door uchar geweigerd", () -> {
            assertEquals(UCHAR_REFUSES.size(), refused.get(), "elke weigering in UCHAR_WEIGERT hoort op te treden");
            assertEquals(29 - UCHAR_REFUSES.size(), succeeded.get(),
                    "golden reproducties over Quarkus; gereproduceerd: " + succeeded.get());
        }));
        return tests.stream();
    }

    /** A listed golden scenario: uchar's scenario resolver refuses it with the listed fragment. */
    private static void testRefusal(Golden g, String fragment) throws IOException {
        StringBuilder source = new StringBuilder();
        for (Path c : g.companions) source.append(Files.readString(c)).append('\n');
        source.append(Files.readString(g.scenarioFile)).append('\n');
        ScenarioBundel bundle = ScenarioResolver.resolve(source.toString());
        assertTrue(!bundle.isOk() && bundle.model().errors().stream().anyMatch(d -> d.message().contains(fragment)),
                () -> g.scenarioFile.getFileName() + " staat in UCHAR_WEIGERT met '" + fragment + "', maar uchar "
                        + "weigert het zo niet (meer); fouten: " + bundle.model().errors());
    }

    private void testScenario(Golden g) throws Exception {
        StringBuilder source = new StringBuilder();
        for (Path c : g.companions) source.append(Files.readString(c)).append('\n');
        source.append(Files.readString(g.scenarioFile)).append('\n');
        ScenarioBundel bundle = ScenarioResolver.resolve(source.toString());
        assertTrue(bundle.isOk(), () -> "scenario resolvet niet: " + bundle.model().errors());
        assertEquals(1, bundle.scenarios().size(),
                "een golden bestand hoort precies één scenario te dragen: " + g.scenarioFile);
        ResolvedScenario scenario = bundle.scenarios().get(0);

        String path = pathForScope(scenario);
        assertNotNull(path, () -> "geen descriptor-entrypoint scoped de regel(s) van scenario '" + scenario.naam()
                + "' (" + scopeNames(scenario) + ")");

        ObjectNode request = deriveRequest(scenario);
        Response resp = RestAssured.given()
                .contentType("application/json")
                .body(MAPPER.writeValueAsString(request))
                .post("/" + path);
        assertEquals(200, resp.statusCode(),
                () -> "POST /" + path + " gaf " + resp.statusCode() + ": " + resp.body().asString()
                        + "\nverzoek: " + request);
        JsonNode answer = MAPPER.readTree(resp.body().asString());

        for (Verwachting v : scenario.verwachtingen()) compare(v, answer, path, request);
    }

    /**
     * Invoke the REUSED {@code SituationToRequest.assemble} (from the service test-jar) to derive the request
     * MECHANICALLY — nothing is hand-typed. It is package-private static, and under @QuarkusTest the test-jar
     * class is loaded by a DIFFERENT QuarkusClassLoader than this test, which breaks same-package access; a
     * reflective {@code setAccessible} call over the SAME method bridges that classloader split without copying
     * or re-deriving any value.
     */
    private static ObjectNode deriveRequest(ResolvedScenario scenario) throws Exception {
        // Resolve the class BY NAME through the scenario object's OWN classloader so the ResolvedScenario
        // parameter type matches (the @QuarkusTest test class and the test-jar dependency live in DIFFERENT
        // QuarkusClassLoaders). No compile-time symbolic reference to SituationToRequest — that would trigger a
        // cross-loader IllegalAccessError on the package-private class before reflection even runs.
        ClassLoader cl = scenario.getClass().getClassLoader();
        Class<?> snv = Class.forName("dev.uchar.regelspraak.toolchain.service.http.SituationToRequest", true, cl);
        Class<?> rsClass = Class.forName("dev.uchar.regelspraak.ir.ResolvedScenario", true, cl);
        var m = snv.getDeclaredMethod("bouw", rsClass);
        m.setAccessible(true);
        return (ObjectNode) m.invoke(null, scenario);
    }

    // ---------------- verwachting comparison (mirrors GoldenOverHttpTest.compare exactly) ----------------

    private void compare(Verwachting v, JsonNode answer, String path, JsonNode request) {
        String ctx = "\nscope-pad /" + path + "\nverwachting op regel " + v.line() + " (" + v.soort() + ")"
                + "\nverzoek: " + request + "\nantwoord: " + answer;
        switch (v.soort()) {
            case ATTRIBUUT_WAARDE -> {
                JsonNode w = attribute(answer, v.label(), v.attribuut());
                assertNotNull(w, () -> "geen attribuut '" + v.attribuut() + "' op instantie '" + v.label() + "'" + ctx);
                assertTrue(equal(v.verwachteWaarde(), w),
                        () -> "verwacht " + renderValue(v.verwachteWaarde()) + ", kreeg " + w + ctx);
            }
            case ATTRIBUUT_LEEG -> {
                JsonNode w = attribute(answer, v.label(), v.attribuut());
                assertTrue(w == null || w.isNull(), () -> "verwacht leeg, kreeg " + w + ctx);
            }
            case KENMERK_AANWEZIG -> assertTrue(characteristicTrue(answer, v.label(), v.kenmerk()),
                    () -> "verwacht kenmerk '" + v.kenmerk() + "' AANWEZIG op '" + v.label() + "'" + ctx);
            case KENMERK_AFWEZIG -> assertTrue(!characteristicTrue(answer, v.label(), v.kenmerk()),
                    () -> "verwacht kenmerk '" + v.kenmerk() + "' AFWEZIG op '" + v.label() + "'" + ctx);
            case CONSISTENT -> assertTrue(inconsistencies(answer).isEmpty(),
                    () -> "verwacht consistent (lege inconsistenties), kreeg " + inconsistencies(answer) + ctx);
            case INCONSISTENT -> {
                List<String> inc = inconsistencies(answer);
                assertTrue(!inc.isEmpty(), () -> "verwacht inconsistent (niet-lege inconsistenties)" + ctx);
                String line = v.regel();
                if (line != null && !line.isBlank())
                    assertTrue(inc.contains(line),
                            () -> "verwacht dat regel '" + line + "' in de inconsistenties staat, kreeg " + inc + ctx);
            }
            case ATTRIBUUT_TIJDLIJN -> throw new AssertionError(
                    "het antwoord van de dienst draagt geen tijdlijn, dus deze verwachting is over HTTP niet te toetsen" + ctx);
        }
    }

    private static boolean equal(Waarde expected, JsonNode actual) {
        if (actual == null || actual.isNull()) return false;
        if (expected.getal() != null)
            return actual.isNumber() && number(expected.getal()).compareTo(actual.decimalValue()) == 0;
        if (expected.datum() != null)
            return actual.isTextual() && expected.datum().equals(LocalDate.parse(actual.asText()));
        if (expected.datumTijd() != null)
            return actual.isTextual() && expected.datumTijd().equals(LocalDateTime.parse(actual.asText()));
        if (expected.bool() != null)
            return actual.isBoolean() && expected.bool() == actual.booleanValue();
        if (expected.enumWaarde() != null)
            return actual.isTextual() && expected.enumWaarde().equals(actual.asText());
        if (expected.tekst() != null)
            return actual.isTextual() && expected.tekst().equals(actual.asText());
        return actual.isNull();
    }

    /** A case number lexeme as an exact BigDecimal: a decimal, or a fraction {@code p/q} whose quotient terminates
     *  (the same reading as the service test-jar's {@code SituationToRequest.number}, which the Quarkus class
     *  loader split keeps out of direct reach). */
    private static BigDecimal number(String lexeme) {
        int streep = lexeme.indexOf('/');
        if (streep < 0) return new BigDecimal(lexeme);
        return new BigDecimal(lexeme.substring(0, streep)).divide(new BigDecimal(lexeme.substring(streep + 1)));
    }

    private static String renderValue(Waarde w) {
        if (w.getal() != null) return w.getal();
        if (w.datum() != null) return w.datum().toString();
        if (w.datumTijd() != null) return w.datumTijd().toString();
        if (w.bool() != null) return w.bool().toString();
        if (w.enumWaarde() != null) return "'" + w.enumWaarde() + "'";
        return "\"" + w.tekst() + "\"";
    }

    // ---------------- response readers ----------------

    private static JsonNode attribute(JsonNode answer, String id, String name) {
        JsonNode inst = instance(answer, id);
        return inst == null ? null : inst.path("attributen").get(name);
    }

    private static boolean characteristicTrue(JsonNode answer, String id, String name) {
        JsonNode inst = instance(answer, id);
        if (inst == null) return false;
        JsonNode k = inst.path("kenmerken").get(name);
        return k != null && k.isBoolean() && k.booleanValue();
    }

    private static JsonNode instance(JsonNode answer, String id) {
        JsonNode insts = answer.get("instanties");
        if (insts == null) return null;
        for (JsonNode inst : insts) if (id.equals(inst.path("id").asText())) return inst;
        return null;
    }

    private static List<String> inconsistencies(JsonNode answer) {
        List<String> sink = new ArrayList<>();
        JsonNode inc = answer.get("inconsistenties");
        if (inc != null) for (JsonNode n : inc) sink.add(n.isNull() ? null : n.asText());
        return sink;
    }

    // ---------------- entrypoint lookup by scope (mirrors GoldenOverHttpTest) ----------------

    private String pathForScope(ResolvedScenario scenario) {
        List<String> names = scopeNames(scenario);
        if (names.isEmpty()) return null;
        for (ServiceDescriptor.Entrypoint ep : descriptor().entrypoints()) {
            List<String> epNames = ep.scope().ruleNamesOrEmpty();
            if (epNames.containsAll(names) && names.containsAll(epNames)) return ep.path();
        }
        for (ServiceDescriptor.Entrypoint ep : descriptor().entrypoints())
            if (ep.scope().ruleNamesOrEmpty().containsAll(names)) return ep.path();
        return null;
    }

    private static List<String> scopeNames(ResolvedScenario scenario) {
        List<String> sink = new ArrayList<>();
        if (scenario.scope() == null) return sink;
        for (String form : scenario.scope().vormen()) {
            String name = CaseEmitter.basisNaam(form);
            if (!sink.contains(name)) sink.add(name);
        }
        return sink;
    }

    // ---------------- manifest ----------------

    private record Golden(Path scenarioFile, List<Path> companions) {}

    private static List<Golden> readManifest() throws IOException {
        Path manifest = TokaSource.REPO.resolve("examples/toka-scenarios/manifest");
        List<Golden> sink = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] fields = t.split("\\s+");
            if (fields.length < 2) continue;
            if (!"0".equals(fields[0])) continue;
            Path scenario = TokaSource.REPO.resolve(fields[1]);
            List<Path> companions = new ArrayList<>();
            for (int i = 2; i < fields.length; i++) companions.add(TokaSource.REPO.resolve(fields[i]));
            sink.add(new Golden(scenario, companions));
        }
        return sink;
    }
}
