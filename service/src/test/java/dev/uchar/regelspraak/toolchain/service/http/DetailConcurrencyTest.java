package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundle;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceDescriptor;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detail of a session under contention, as {@link ConcurrencyTest} does for the answer: the firings that uchar's
 * waarnemer records on one session do not reach the trace of another. The families are those of
 * {@link ConcurrencyTest}, derived from golden TOKA scenarios with {@link SituationToRequest}, over three entry points
 * that fire different rules; every request gets ids of its own (the ids of the golden situation with a prefix per
 * request). The truth of each request is its own serial answer with {@code trace}, {@code dekking} and
 * {@code fixpunt}. Then {@value #CONCURRENT} requests over the families start at once, {@value #REPETITIONS} times,
 * and each answer must equal its serial baseline: a firing that leaked from another session would add a row to the
 * trace or raise a count, and change the coverage. The waarnemer labels a firing only with the beans of its own
 * request, so a leak would not show as a foreign label; the test checks besides that every label of a trace, and every
 * object of a fixpoint that is no made object ({@code #<n>}), is an id of the request itself.
 */
class DetailConcurrencyTest {

    private static final int CONCURRENT = 60;
    private static final int REPETITIONS = 3;
    private static final String DETAIL = "trace=true&dekking=true&fixpunt=true";

    private static ServiceHttp service;
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private static List<RequestCase> unique;

    @BeforeAll
    static void start() throws Exception {
        DecisionCore core = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        unique = requestCases(core.descriptor());
        for (RequestCase v : unique) {
            HttpResponse<String> r = post(v.path, ServiceHttp.MAPPER.writeValueAsString(v.body));
            assertEquals(200, r.statusCode(), () -> "seriële basislijn faalt voor /" + v.path + ": " + r.body());
            v.baseLine = ServiceHttp.MAPPER.readTree(r.body());
        }
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
        service = null;
    }

    @Test
    void firings_do_not_leak_between_sessions() throws Exception {
        for (RequestCase v : unique) {
            assertFalse(v.baseLine.get("trace").isEmpty(), "de basislijn van /" + v.path + " vuurt niets: " + v.baseLine);
            onlyOwnIds(v, v.baseLine, "basislijn");
        }
        for (int ronde = 1; ronde <= REPETITIONS; ronde++) {
            List<RequestCase> batch = new ArrayList<>();
            for (int i = 0; i < CONCURRENT; i++) batch.add(unique.get(i % unique.size()));
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(batch.size());
            List<AtomicReference<Throwable>> errors = new ArrayList<>();
            List<AtomicReference<JsonNode>> answers = new ArrayList<>();
            for (RequestCase v : batch) {
                AtomicReference<Throwable> error = new AtomicReference<>();
                AtomicReference<JsonNode> answer = new AtomicReference<>();
                errors.add(error);
                answers.add(answer);
                pool.submit(() -> {
                    try {
                        start.await();
                        HttpResponse<String> r = post(v.path, ServiceHttp.MAPPER.writeValueAsString(v.body));
                        if (r.statusCode() != 200)
                            throw new AssertionError("status " + r.statusCode() + " voor /" + v.path + ": " + r.body());
                        answer.set(ServiceHttp.MAPPER.readTree(r.body()));
                    } catch (Throwable t) {
                        error.set(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS), "ronde " + ronde + ": niet alle verzoeken op tijd klaar");
            pool.shutdown();
            for (int i = 0; i < batch.size(); i++) {
                Throwable t = errors.get(i).get();
                if (t != null) throw new AssertionError("ronde " + ronde + " taak " + i + " wierp: " + t, t);
                RequestCase v = batch.get(i);
                JsonNode answer = answers.get(i).get();
                assertNotNull(answer, "ronde " + ronde + " taak " + i + ": geen antwoord");
                assertEquals(v.baseLine, answer, "ronde " + ronde + " taak " + i + " (/" + v.path
                        + "): het antwoord wijkt af van de seriële basislijn");
                onlyOwnIds(v, answer, "ronde " + ronde + " taak " + i);
            }
        }
    }

    /** Every label of the trace, and every object, subject and linked object of the fixpoint that is no made object,
     *  is an id of {@code v}'s own request. */
    private static void onlyOwnIds(RequestCase v, JsonNode answer, String where) {
        for (JsonNode t : answer.get("trace"))
            for (JsonNode l : t.get("labels"))
                assertTrue(v.ids.contains(l.asText()), where + " (/" + v.path + "): de trace noemt '" + l.asText()
                        + "', geen id van dit verzoek " + v.ids + ": een vuring van een andere sessie");
        JsonNode f = answer.get("fixpunt");
        List<String> objects = new ArrayList<>();
        for (JsonNode o : f.get("objecten")) objects.add(o.get("object").asText());
        for (JsonNode k : f.get("koppelingen")) { objects.add(k.get("eerste").asText()); objects.add(k.get("tweede").asText()); }
        for (JsonNode inc : f.get("inconsistenties")) if (inc.hasNonNull("onderwerp")) objects.add(inc.get("onderwerp").asText());
        for (String o : objects)
            assertTrue(o.startsWith("#") || v.ids.contains(o), where + " (/" + v.path + "): het fixpunt noemt '" + o
                    + "', geen id van dit verzoek " + v.ids);
    }

    // ---------------- the families of ConcurrencyTest, each request with ids of its own ----------------

    private static final class RequestCase {
        final String path;
        final ObjectNode body;
        final Set<String> ids;
        JsonNode baseLine;

        RequestCase(String path, ObjectNode body) {
            this.path = path;
            this.body = body;
            this.ids = new HashSet<>();
            for (JsonNode i : body.get("instanties")) ids.add(i.get("id").asText());
        }
    }

    private static List<RequestCase> requestCases(ServiceDescriptor descriptor) throws Exception {
        List<RequestCase> sink = new ArrayList<>();
        int n = 0;
        ObjectNode a0 = situationRequest("examples/toka-scenarios/scenario-toka-17-bepaal-leeftijd.rgs");
        String pathA = pathForScope(descriptor, "bepaal leeftijd");
        for (String geboorte : new String[]{"1990-06-15", "1985-01-01", "2000-12-31", "1970-03-03"}) {
            ObjectNode body = ownIds(a0, "R" + n++ + "-");
            for (JsonNode inst : body.get("instanties"))
                if ("Natuurlijk persoon".equalsIgnoreCase(inst.path("type").asText()))
                    ((ObjectNode) inst.get("attributen")).put("geboortedatum", geboorte);
            sink.add(new RequestCase(pathA, body));
        }
        ObjectNode b0 = situationRequest("examples/toka-scenarios/scenario-toka-20-totaal-te-betalen.rgs");
        String pathB = pathForScope(descriptor, "Totaal te betalen belasting");
        for (String[] pair : new String[][]{{"30.00", "20.00"}, {"10.00", "5.00"}, {"100.00", "0.00"}, {"1.00", "2.00"}}) {
            ObjectNode body = ownIds(b0, "R" + n++ + "-");
            int p = 0;
            for (JsonNode inst : body.get("instanties"))
                if ("Natuurlijk persoon".equalsIgnoreCase(inst.path("type").asText()) && p < pair.length)
                    ((ObjectNode) inst.get("attributen")).put("te betalen belasting", new java.math.BigDecimal(pair[p++]));
            sink.add(new RequestCase(pathB, body));
        }
        ObjectNode c0 = situationRequest("examples/toka-scenarios/scenario-toka-30-geen-rondvlucht.rgs");
        String pathC = pathForScope(descriptor, "Controleer of vlucht geen rondvlucht is");
        for (String lh : new String[]{"Amsterdam Schiphol", "Parijs Charles de Gaulle", "Londen Heathrow"}) {
            ObjectNode body = ownIds(c0, "R" + n++ + "-");
            for (JsonNode inst : body.get("instanties"))
                if ("Vlucht".equalsIgnoreCase(inst.path("type").asText())) {
                    ObjectNode attr = (ObjectNode) inst.get("attributen");
                    attr.put("luchthaven van vertrek", lh);
                    attr.put("luchthaven van bestemming", lh);
                }
            sink.add(new RequestCase(pathC, body));
        }
        return sink;
    }

    /** A copy of {@code verzoek} with {@code prefix} before every id, on the instances and in their roles. */
    private static ObjectNode ownIds(ObjectNode request, String prefix) {
        ObjectNode sink = request.deepCopy();
        for (JsonNode inst : sink.get("instanties")) {
            ((ObjectNode) inst).put("id", prefix + inst.get("id").asText());
            JsonNode roles = inst.get("rollen");
            if (roles == null) continue;
            for (var e : roles.properties()) {
                if (e.getValue().isArray())
                    for (int i = 0; i < e.getValue().size(); i++)
                        ((ArrayNode) e.getValue()).set(i, TextNode.valueOf(prefix + e.getValue().get(i).asText()));
                else ((ObjectNode) roles).put(e.getKey(), prefix + e.getValue().asText());
            }
        }
        assertFalse(sink.has("relaties"), "SituatieNaarVerzoek schrijft geen relaties");
        return sink;
    }

    private static ObjectNode situationRequest(String scenarioPath) throws Exception {
        Path scenario = TokaSource.REPO.resolve(scenarioPath);
        StringBuilder source = new StringBuilder();
        for (Path c : companionsFor(scenario)) source.append(Files.readString(c)).append('\n');
        source.append(Files.readString(scenario)).append('\n');
        ScenarioBundle bundle = ScenarioResolver.resolve(source.toString());
        assertTrue(bundle.isOk() && !bundle.scenarios().isEmpty(),
                () -> "scenario resolvet niet: " + scenarioPath + " " + bundle.model().errors());
        ResolvedScenario sc = bundle.scenarios().get(0);
        return SituationToRequest.assemble(sc);
    }

    private static List<Path> companionsFor(Path scenario) throws Exception {
        for (String line : Files.readAllLines(TokaSource.REPO.resolve("examples/toka-scenarios/manifest"))) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] v = t.split("\\s+");
            if (v.length < 2 || !"0".equals(v[0])) continue;
            if (TokaSource.REPO.resolve(v[1]).equals(scenario)) {
                List<Path> sink = new ArrayList<>();
                for (int i = 2; i < v.length; i++) sink.add(TokaSource.REPO.resolve(v[i]));
                return sink;
            }
        }
        return List.of();
    }

    private static String pathForScope(ServiceDescriptor descriptor, String line) {
        for (ServiceDescriptor.Entrypoint ep : descriptor.entrypoints())
            if (ep.scope().ruleNamesOrEmpty().contains(line)) return ep.path();
        throw new AssertionError("geen entrypoint scoped regel '" + line + "'");
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + service.port() + "/" + path + "?" + DETAIL))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
