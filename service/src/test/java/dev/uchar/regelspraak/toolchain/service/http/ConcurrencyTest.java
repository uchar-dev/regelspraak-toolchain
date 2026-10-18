package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceDescriptor;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate G7 — ISOLATIE under contention. Proves the session-per-request core gives per-request DETERMINISTIC
 * results with NO cross-session fact leakage when many requests over mixed entry points overlap in flight.
 *
 * <h2>How the oracle is built (no hand-computed truth)</h2>
 * Three DISTINCT request families are derived MECHANICALLY from golden scenarios via {@link SituationToRequest}:
 * <ul>
 *   <li><b>bepaal-leeftijd</b> (from scenario-toka-17) — an attribuut computation;</li>
 *   <li><b>totaal-te-betalen-belasting</b> (from scenario-toka-20) — a sum over multiple passenger instances;</li>
 *   <li><b>controleer-of-vlucht-geen-rondvlucht-is</b> (from scenario-toka-30) — a §9.5 consistentieregel. The
 *       golden situatie is CONSISTENT (different airports); to make the family reliably INCONSISTENT — so any
 *       inconsistentie-LEAKAGE across sessions would be visible — its input is varied to set the bestemming EQUAL
 *       to the vertrek (a rondvlucht). This varies ONLY inputs, never expectations.</li>
 * </ul>
 * Each family is turned into several concrete requests by varying ONLY inputs per request index (a different
 * geboortedatum + id for A, different bedragen for B, a different — but equal-to-vertrek — airport for C). No
 * expectation is ever varied or hand-typed.
 *
 * <p>The truth for each concrete request is its OWN SERIAL response: every unique request is run once serially
 * first and its full response body recorded as the baseline. Serial==concurrent parity is the oracle — a leak or
 * a race would make a concurrent response differ from its serial baseline. Then {@value #CONCURRENT} requests
 * (mixing the three families, genuinely overlapping via a shared {@link CountDownLatch} start gate over a
 * fixed-size executor) each assert its response equals ITS OWN baseline (JSON-equal). The inconsistent family's
 * {@code inconsistencies} must appear ONLY in its own responses (never in an A/B response — the no-leakage
 * witness). The whole concurrent batch is repeated {@value #REPETITIONS}× for flake resistance.
 */
class ConcurrencyTest {

    /** >= 50 required by G7; use 60 to clear the floor with margin. */
    private static final int CONCURRENT = 60;
    private static final int REPETITIONS = 3;

    private static ServiceHttp service;
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    private static List<RequestCase> unique;   // the distinct concrete requests (each with its serial baseline)

    @BeforeAll
    static void start() throws Exception {
        DecisionCore core = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        unique = buildRequestCases(core.descriptor());
        // record every unique request's SERIAL baseline (the oracle — no hand-computed truth).
        for (RequestCase v : unique) {
            HttpResponse<String> resp = post(v.path, ServiceHttp.MAPPER.writeValueAsString(v.body));
            assertEquals(200, resp.statusCode(), () -> "serieel baseline faalt voor /" + v.path + ": " + resp.body());
            v.baseline = ServiceHttp.MAPPER.readTree(resp.body());
        }
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
        service = null;
    }

    // ---------------- the concurrency gate ----------------

    @Test
    void isolation_under_contention() throws Exception {
        // sanity: 3 distinct families, an inconsistent one present, and each baseline sane.
        assertTrue(unique.size() >= 3, "verwacht minstens 3 distincte verzoeken, kreeg " + unique.size());
        assertTrue(unique.stream().anyMatch(v -> v.inconsistent),
                "de rondvlucht-familie hoort inconsistent te zijn (leakage-detector)");
        for (RequestCase v : unique) {
            assertNotNull(v.baseline, "geen baseline voor /" + v.path);
            List<String> inc = inconsistencies(v.baseline);
            if (v.inconsistent)
                assertTrue(!inc.isEmpty(),
                        "de rondvlucht-baseline hoort niet-lege inconsistenties te tonen: " + v.baseline);
            else
                assertTrue(inc.isEmpty(),
                        "een niet-inconsistente baseline (/" + v.path + ") hoort lege inconsistenties te tonen: "
                                + v.baseline);
        }

        for (int ronde = 1; ronde <= REPETITIONS; ronde++) {
            final int r = ronde;
            // build CONCURRENT requests, mixing the families round-robin over the distinct requests.
            List<RequestCase> batch = new ArrayList<>();
            for (int i = 0; i < CONCURRENT; i++) batch.add(unique.get(i % unique.size()));

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch start = new CountDownLatch(1);   // the start gate — every task blocks until released
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
                        start.await();   // genuinely overlap: all tasks fire after the single release
                        HttpResponse<String> resp = post(v.path, ServiceHttp.MAPPER.writeValueAsString(v.body));
                        if (resp.statusCode() != 200)
                            throw new AssertionError("status " + resp.statusCode() + " voor /" + v.path + ": " + resp.body());
                        answer.set(ServiceHttp.MAPPER.readTree(resp.body()));
                    } catch (Throwable t) {
                        error.set(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();                        // release the herd
            assertTrue(done.await(120, TimeUnit.SECONDS), "ronde " + r + ": niet alle verzoeken op tijd klaar");
            pool.shutdown();

            for (int i = 0; i < batch.size(); i++) {
                Throwable t = errors.get(i).get();
                if (t != null) throw new AssertionError("ronde " + r + " taak " + i + " wierp: " + t, t);
                RequestCase v = batch.get(i);
                JsonNode answer = answers.get(i).get();
                assertNotNull(answer, "ronde " + r + " taak " + i + ": geen antwoord");
                // byte-for-byte deterministic: the concurrent response equals ITS OWN serial baseline exactly.
                assertEquals(v.baseline, answer,
                        "ronde " + r + " taak " + i + " (/" + v.path + "): concurrent-antwoord wijkt af van de "
                                + "seriële baseline — sessie-lek of race\nbaseline: " + v.baseline + "\nkreeg: " + answer);
                // no-leakage: inconsistenties appear ONLY in the inconsistent family's own responses.
                List<String> inc = inconsistencies(answer);
                if (v.inconsistent)
                    assertTrue(!inc.isEmpty(), "ronde " + r + " taak " + i + ": rondvlucht-antwoord hoort inconsistent te blijven");
                else
                    assertTrue(inc.isEmpty(), "ronde " + r + " taak " + i + " (/" + v.path
                            + "): een niet-rondvlucht-antwoord LEKTE inconsistenties: " + inc + " — cross-session lek");
            }
        }
    }

    // ---------------- request families (mechanically derived, inputs varied) ----------------

    /** One concrete request: its path, its (mutated-input) body, whether it's the inconsistent family, and its
     *  serial baseline (filled in {@link #start}). */
    private static final class RequestCase {
        final String path;
        final ObjectNode body;
        final boolean inconsistent;
        JsonNode baseline;
        RequestCase(String path, ObjectNode body, boolean inconsistent) {
            this.path = path; this.body = body; this.inconsistent = inconsistent;
        }
    }

    private static List<RequestCase> buildRequestCases(ServiceDescriptor descriptor) throws IOException {
        List<RequestCase> sink = new ArrayList<>();

        // Family A — bepaal-leeftijd, from scenario-toka-17. Vary geboortedatum + id per index.
        ObjectNode a0 = situationRequest("examples/toka-scenarios/scenario-toka-17-bepaal-leeftijd.rgs");
        String pathA = pathForScope(descriptor, "bepaal leeftijd");
        String[] geboortes = {"1990-06-15", "1985-01-01", "2000-12-31"};
        for (int i = 0; i < geboortes.length; i++) {
            ObjectNode body = a0.deepCopy();
            // vary the person's geboortedatum + id (inputs only)
            for (JsonNode inst : body.get("instanties"))
                if ("Natuurlijk persoon".equalsIgnoreCase(inst.path("type").asText())) {
                    ((ObjectNode) inst.get("attributen")).put("geboortedatum", geboortes[i]);
                    ((ObjectNode) inst).put("id", "P-A" + i);
                    // re-point the reis rol at the (unchanged) Vlucht id
                }
            sink.add(new RequestCase(pathA, body, false));
        }

        // Family B — totaal-te-betalen-belasting, from scenario-toka-20. Vary the passenger bedragen per index.
        ObjectNode b0 = situationRequest("examples/toka-scenarios/scenario-toka-20-totaal-te-betalen.rgs");
        String pathB = pathForScope(descriptor, "Totaal te betalen belasting");
        String[][] bedragen = {{"30.00", "20.00"}, {"10.00", "5.00"}, {"100.00", "0.00"}};
        for (String[] pair : bedragen) {
            ObjectNode body = b0.deepCopy();
            int p = 0;
            for (JsonNode inst : body.get("instanties"))
                if ("Natuurlijk persoon".equalsIgnoreCase(inst.path("type").asText()) && p < pair.length) {
                    ((ObjectNode) inst.get("attributen")).put("te betalen belasting", new java.math.BigDecimal(pair[p]));
                    p++;
                }
            sink.add(new RequestCase(pathB, body, false));
        }

        // Family C — controleer-of-vlucht-geen-rondvlucht-is (§9.5), from scenario-toka-30. The golden is
        // CONSISTENT; make it INCONSISTENT by setting bestemming = vertrek (a rondvlucht), varying which airport
        // per index. Inputs only; the inconsistentie is the emergent truth, checked against its serial baseline.
        ObjectNode c0 = situationRequest("examples/toka-scenarios/scenario-toka-30-geen-rondvlucht.rgs");
        String pathC = pathForScope(descriptor, "Controleer of vlucht geen rondvlucht is");
        String[] luchthavens = {"Amsterdam Schiphol", "Parijs Charles de Gaulle", "Londen Heathrow"};
        for (String lh : luchthavens) {
            ObjectNode body = c0.deepCopy();
            for (JsonNode inst : body.get("instanties"))
                if ("Vlucht".equalsIgnoreCase(inst.path("type").asText())) {
                    ObjectNode attr = (ObjectNode) inst.get("attributen");
                    attr.put("luchthaven van vertrek", lh);
                    attr.put("luchthaven van bestemming", lh);   // rondvlucht -> inconsistent
                }
            sink.add(new RequestCase(pathC, body, true));
        }

        return sink;
    }

    /** Resolve a golden scenario file (+ its manifest companions) and mechanically derive the request body. */
    private static ObjectNode situationRequest(String scenarioPath) throws IOException {
        Path scenario = TokaSource.REPO.resolve(scenarioPath);
        List<Path> companions = companionsFor(scenario);
        StringBuilder source = new StringBuilder();
        for (Path c : companions) source.append(Files.readString(c)).append('\n');
        source.append(Files.readString(scenario)).append('\n');
        ScenarioBundel bundle = ScenarioResolver.resolve(source.toString());
        assertTrue(bundle.isOk() && !bundle.scenarios().isEmpty(),
                () -> "scenario resolvet niet: " + scenarioPath + " " + bundle.model().errors());
        ResolvedScenario sc = bundle.scenarios().get(0);
        return SituationToRequest.assemble(sc);
    }

    /** The manifest companions for a scenario file (the exit-0 line whose scenario is this file). */
    private static List<Path> companionsFor(Path scenario) throws IOException {
        Path manifest = TokaSource.REPO.resolve("examples/toka-scenarios/manifest");
        for (String line : Files.readAllLines(manifest)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] v = t.split("\\s+");
            if (v.length < 2 || !"0".equals(v[0])) continue;
            if (TokaSource.REPO.resolve(v[1]).equals(scenario)) {
                List<Path> comps = new ArrayList<>();
                for (int i = 2; i < v.length; i++) comps.add(TokaSource.REPO.resolve(v[i]));
                return comps;
            }
        }
        return Collections.emptyList();
    }

    /** The descriptor entrypoint pad whose scope names exactly {@code line}. */
    private static String pathForScope(ServiceDescriptor descriptor, String line) {
        for (ServiceDescriptor.Entrypoint ep : descriptor.entrypoints())
            if (ep.scope().ruleNamesOrEmpty().contains(line)) return ep.path();
        throw new AssertionError("geen entrypoint scoped regel '" + line + "'");
    }

    private static List<String> inconsistencies(JsonNode answer) {
        List<String> sink = new ArrayList<>();
        JsonNode inc = answer.get("inconsistenties");
        if (inc != null) for (JsonNode n : inc) sink.add(n.isNull() ? null : n.asText());
        return sink;
    }

    // ---------------- HTTP ----------------

    private static HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + service.port() + "/" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
