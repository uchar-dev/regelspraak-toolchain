package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.ir.ResolvedScenario.Expectation;
import dev.uchar.regelspraak.ir.ResolvedScenario.ScenarioValue;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundle;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceDescriptor;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONE TRUTH, TWO TRANSPORTS. For each of the 29 golden TOKA scenariotoetsen (the exit-0 lines of
 * {@code examples/toka-scenarios/manifest}) this harness:
 *
 * <ol>
 *   <li>resolves the scenario (uchar's {@link ScenarioResolver#resolve} over the concatenated companions +
 *       scenario), yielding the SAME typed scenario ({@link ResolvedScenario}) the scenario runner lowers;</li>
 *   <li>MECHANICALLY derives the HTTP request from the resolved situatie ({@link SituationToRequest}) — no
 *       expected value, input value, label or scope is hand-typed;</li>
 *   <li>looks the entrypoint up by matching the scenario's scope (the rules it tests) to the descriptor entrypoint
 *       whose {@code scope.ruleNames} names them;</li>
 *   <li>POSTs to ONE {@link ServiceHttp} over ONE {@link DecisionCore} (the full TOKA service, strict) and asserts
 *       the response reproduces EVERY verwachting (BigDecimal.compareTo for numeriek, equals-after-normalisation
 *       for datum/datumtijd, equals for tekst/enum/bool, boolean read for kenmerk, empty/non-empty inconsistenties
 *       for CONSISTENT/INCONSISTENT).</li>
 * </ol>
 *
 * The 29 scenarios are dynamic tests (so one failure names its scenario) plus a final count assertion that the
 * harness saw exactly 29 golden scenarios and all were accounted for. A scenario uchar's scenario resolver refuses
 * cannot serve as an oracle: it is listed by file name in {@link #UCHAR_REFUSES} with a fragment of the refusal, and
 * its dynamic test asserts exactly that refusal, so an entry fails the day uchar resolves the scenario again.
 */
class GoldenOverHttpTest {

    /** The golden scenario files uchar's scenario resolver refuses (its own {@code Resolver --ir --cases} refuses
     *  them alike), each with a fragment of the refusal sentence. Measured on uchar d67f5da1. */
    private static final java.util.Map<String, String> UCHAR_REFUSES = java.util.Map.of(
            "scenario-toka-29-passagier-met-recht.rgs",
                    "uit de scope bestaat niet in het document",
            "scenario-toka-43-startpunt-vertrek.rgs",
                    "een scope in een document met een startpuntbepaling wordt geweigerd",
            "scenario-toka-44-startpunt-min-reserveringstermijn.rgs",
                    "een scope in een document met een startpuntbepaling wordt geweigerd");

    private static ServiceDescriptor descriptor;
    private static ServiceHttp service;
    private static String baseUrl;
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void startService() throws IOException {
        // service.url override (chunk B, the third transport): when set — system property `service.url` or env
        // SERVICE_URL — the IDENTICAL 29 golden assertions run against an already-running service (e.g. the
        // in-cluster pod) instead of an in-process ServiceHttp. The descriptor is the same toka.service.json,
        // parsed standalone for scope→entrypoint ROUTING only; every expected value still comes from the
        // scenario fixtures, so nothing is duplicated or re-typed.
        String override = System.getProperty("service.url", System.getenv("SERVICE_URL"));
        if (override != null && !override.isBlank()) {
            baseUrl = override.endsWith("/") ? override.substring(0, override.length() - 1) : override;
            descriptor = ServiceHttp.MAPPER.readValue(TokaSource.descriptorJson(), ServiceDescriptor.class);
            return;
        }
        DecisionCore core = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        baseUrl = "http://127.0.0.1:" + service.port();
        // the kern already parsed the descriptor with DescriptorLoader.MAPPER at startup; reuse that resolved
        // descriptor so the scope→entrypoint lookup is over the very same entry set the routes were built from.
        descriptor = core.descriptor();
    }

    @AfterAll
    static void stopService() {
        if (service != null) service.stop(0);
        service = null;
    }

    // ---------------- the 29 golden scenarios ----------------

    @TestFactory
    Stream<DynamicTest> golden_over_http() throws IOException {
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
        // final aggregating assertion: every golden reproduced, or refused as its entry in UCHAR_REFUSES says.
        tests.add(DynamicTest.dynamicTest("29 golden: over HTTP gereproduceerd of door uchar geweigerd", () -> {
            assertEquals(UCHAR_REFUSES.size(), refused.get(), "elke weigering in UCHAR_WEIGERT hoort op te treden");
            assertEquals(29 - UCHAR_REFUSES.size(), succeeded.get(),
                    "golden reproducties over HTTP; gereproduceerd: " + succeeded.get());
        }));
        return tests.stream();
    }

    /** A listed golden scenario: uchar's scenario resolver refuses it with the listed fragment. */
    private static void testRefusal(Golden g, String fragment) throws IOException {
        StringBuilder source = new StringBuilder();
        for (Path c : g.companions) source.append(Files.readString(c)).append('\n');
        source.append(Files.readString(g.scenarioFile)).append('\n');
        ScenarioBundle bundle = ScenarioResolver.resolve(source.toString());
        assertTrue(!bundle.isOk() && bundle.model().errors().stream().anyMatch(d -> d.message().contains(fragment)),
                () -> g.scenarioFile.getFileName() + " staat in UCHAR_WEIGERT met '" + fragment + "', maar uchar "
                        + "weigert het zo niet (meer); fouten: " + bundle.model().errors());
    }

    /** Resolve one golden scenario, derive its request, POST it, and assert every verwachting. */
    private void testScenario(Golden g) throws Exception {
        // 1. resolve (companions first, then the scenario file) — the resolver output is the single oracle.
        StringBuilder source = new StringBuilder();
        for (Path c : g.companions) source.append(Files.readString(c)).append('\n');
        source.append(Files.readString(g.scenarioFile)).append('\n');
        ScenarioBundle bundle = ScenarioResolver.resolve(source.toString());
        assertTrue(bundle.isOk(), () -> "scenario resolvet niet: " + bundle.model().errors());
        assertEquals(1, bundle.scenarios().size(),
                "een golden bestand hoort precies één scenario te dragen: " + g.scenarioFile);
        ResolvedScenario scenario = bundle.scenarios().get(0);

        // 2. entrypoint by scope — never guessed by kebab-case; matched against the descriptor's regelnamen.
        String path = pathForScope(scenario);
        assertNotNull(path, () -> "geen descriptor-entrypoint scoped de regel(s) van scenario '" + scenario.naam()
                + "' (" + scopeNames(scenario) + ")");

        // 3. derive the request mechanically + POST.
        com.fasterxml.jackson.databind.node.ObjectNode request = SituationToRequest.assemble(scenario);
        HttpResponse<String> resp = post(path, ServiceHttp.MAPPER.writeValueAsString(request));
        assertEquals(200, resp.statusCode(),
                () -> "POST /" + path + " gaf " + resp.statusCode() + ": " + resp.body()
                        + "\nverzoek: " + request);
        JsonNode answer = ServiceHttp.MAPPER.readTree(resp.body());

        // 4. every verwachting against the answer on the wire. uchar's DroolsBackend.vergelijk judges a session in
        //    memory, which an HTTP answer does not carry; this test checks what the service sends, so it reads it.
        for (Expectation v : scenario.expectations()) compare(v, answer, path, request);
    }

    // ---------------- verwachting comparison ----------------

    private void compare(Expectation v, JsonNode answer, String path, JsonNode request) {
        String ctx = "\nscope-pad /" + path + "\nverwachting op regel " + v.line() + " (" + v.category() + ")"
                + "\nverzoek: " + request + "\nantwoord: " + answer;
        switch (v.category()) {
            case ATTRIBUTE_VALUE -> {
                JsonNode w = attribute(answer, v.label(), v.attribute());
                assertNotNull(w, () -> "geen attribuut '" + v.attribute() + "' op instantie '" + v.label() + "'" + ctx);
                assertTrue(equal(v.expectedValue(), w),
                        () -> "verwacht " + renderValue(v.expectedValue()) + ", kreeg " + w + ctx);
            }
            case ATTRIBUTE_EMPTY -> {
                // AnswerBuilder renders an unresolved value as JSON null (rend(null) -> nullNode). So an empty
                // attribute is either JSON null OR the attribute node absent for that instance — assert THAT.
                JsonNode w = attribute(answer, v.label(), v.attribute());
                assertTrue(w == null || w.isNull(),
                        () -> "verwacht leeg, kreeg " + w + ctx);
            }
            case CHARACTERISTIC_PRESENT -> assertTrue(characteristicTrue(answer, v.label(), v.characteristic()),
                    () -> "verwacht kenmerk '" + v.characteristic() + "' AANWEZIG op '" + v.label() + "'" + ctx);
            case CHARACTERISTIC_ABSENT -> assertTrue(!characteristicTrue(answer, v.label(), v.characteristic()),
                    () -> "verwacht kenmerk '" + v.characteristic() + "' AFWEZIG op '" + v.label() + "'" + ctx);
            case CONSISTENT -> assertTrue(inconsistencies(answer).isEmpty(),
                    () -> "verwacht consistent (lege inconsistenties), kreeg " + inconsistencies(answer) + ctx);
            case INCONSISTENT -> {
                List<String> inc = inconsistencies(answer);
                assertTrue(!inc.isEmpty(), () -> "verwacht inconsistent (niet-lege inconsistenties)" + ctx);
                // if the verwachting names a regel, it must be listed.
                String line = v.regel();
                if (line != null && !line.isBlank())
                    assertTrue(inc.contains(line),
                            () -> "verwacht dat regel '" + line + "' in de inconsistenties staat, kreeg " + inc + ctx);
            }
            case ATTRIBUTE_TIMELINE -> throw new AssertionError(
                    "het antwoord van de dienst draagt geen tijdlijn, dus deze verwachting is over HTTP niet te toetsen" + ctx);
        }
    }

    /** Numeriek via BigDecimal.compareTo, datum/datumtijd/bool/enum/tekst via equals-after-normalisation (a
     *  datetime response is the LocalDateTime.toString() form, so parse it back). */
    private static boolean equal(ScenarioValue expected, JsonNode actual) {
        if (actual == null || actual.isNull()) return false;
        if (expected.number() != null)
            return actual.isNumber() && SituationToRequest.number(expected.number()).compareTo(actual.decimalValue()) == 0;
        if (expected.date() != null)
            return actual.isTextual() && expected.date().equals(LocalDate.parse(actual.asText()));
        if (expected.dateTime() != null)
            return actual.isTextual() && expected.dateTime().equals(LocalDateTime.parse(actual.asText()));
        if (expected.bool() != null)
            return actual.isBoolean() && expected.bool() == actual.booleanValue();
        if (expected.enumValue() != null)
            return actual.isTextual() && expected.enumValue().equals(actual.asText());
        if (expected.tekst() != null)
            return actual.isTextual() && expected.tekst().equals(actual.asText());
        return actual.isNull();
    }

    private static String renderValue(ScenarioValue w) {
        if (w.number() != null) return w.number();
        if (w.date() != null) return w.date().toString();
        if (w.dateTime() != null) return w.dateTime().toString();
        if (w.bool() != null) return w.bool().toString();
        if (w.enumValue() != null) return "'" + w.enumValue() + "'";
        return "\"" + w.tekst() + "\"";
    }

    // ---------------- response readers ----------------

    private static JsonNode attribute(JsonNode answer, String id, String name) {
        JsonNode inst = instance(answer, id);
        return inst == null ? null : inst.path("attributes").get(name);
    }

    private static boolean characteristicTrue(JsonNode answer, String id, String name) {
        JsonNode inst = instance(answer, id);
        if (inst == null) return false;
        JsonNode k = inst.path("characteristics").get(name);
        return k != null && k.isBoolean() && k.booleanValue();
    }

    private static JsonNode instance(JsonNode answer, String id) {
        JsonNode insts = answer.get("instances");
        if (insts == null) return null;
        for (JsonNode inst : insts) if (id.equals(inst.path("id").asText())) return inst;
        return null;
    }

    private static List<String> inconsistencies(JsonNode answer) {
        List<String> sink = new ArrayList<>();
        JsonNode inc = answer.get("inconsistencies");
        if (inc != null) for (JsonNode n : inc) sink.add(n.isNull() ? null : n.asText());
        return sink;
    }

    // ---------------- entrypoint lookup by scope ----------------

    /** The descriptor entrypoint path whose {@code scope.ruleNames} names every scope rule of the scenario. A
     *  single-rule scope (all golden scenarios) matches the entrypoint whose regelnamen contains that rule. The
     *  scope names the forms as the model spells them, a versioned rule once per version, so it is read as base
     *  names. */
    private String pathForScope(ResolvedScenario scenario) {
        List<String> names = scopeNames(scenario);
        if (names.isEmpty()) return null;
        for (ServiceDescriptor.Entrypoint ep : descriptor.entrypoints()) {
            List<String> epNames = ep.scope().ruleNamesOrEmpty();
            if (epNames.containsAll(names) && names.containsAll(epNames)) return ep.path();
        }
        // fall back: the entrypoint that names ALL of the scope rules (superset), for a multi-rule scope.
        for (ServiceDescriptor.Entrypoint ep : descriptor.entrypoints())
            if (ep.scope().ruleNamesOrEmpty().containsAll(names)) return ep.path();
        return null;
    }

    private static List<String> scopeNames(ResolvedScenario scenario) {
        List<String> sink = new ArrayList<>();
        if (scenario.scope() == null) return sink;
        for (String form : scenario.scope().forms()) {
            String name = CaseEmitter.baseName(form);
            if (!sink.contains(name)) sink.add(name);
        }
        return sink;
    }

    // ---------------- manifest ----------------

    /** One exit-0 golden line: the scenario file and its companion files. */
    private record Golden(Path scenarioFile, List<Path> companions) {}

    private static List<Golden> readManifest() throws IOException {
        Path manifest = TokaSource.REPO.resolve("examples/toka-scenarios/manifest");
        List<Golden> sink = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] fields = t.split("\\s+");
            if (fields.length < 2) continue;
            if (!"0".equals(fields[0])) continue;   // exit-0 lines only
            Path scenario = TokaSource.REPO.resolve(fields[1]);
            List<Path> companions = new ArrayList<>();
            for (int i = 2; i < fields.length; i++) companions.add(TokaSource.REPO.resolve(fields[i]));
            sink.add(new Golden(scenario, companions));
        }
        return sink;
    }

    // ---------------- HTTP ----------------

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
