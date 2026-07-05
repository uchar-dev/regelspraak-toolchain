package io.github.yhavinga.regelspraak.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.yhavinga.regelspraak.ir.ResolvedScenario;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import io.github.yhavinga.regelspraak.service.BeslisserKern;
import io.github.yhavinga.regelspraak.service.DienstDescriptor;
import io.github.yhavinga.regelspraak.service.KernConfig;
import io.github.yhavinga.regelspraak.service.TokaBron;

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
 * Three DISTINCT request families are derived MECHANICALLY from golden scenarios via {@link SituatieNaarVerzoek}:
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
 * {@code inconsistenties} must appear ONLY in its own responses (never in an A/B response — the no-leakage
 * witness). The whole concurrent batch is repeated {@value #HERHALINGEN}× for flake resistance.
 */
class ConcurrencyTest {

    /** >= 50 required by G7; use 60 to clear the floor with margin. */
    private static final int CONCURRENT = 60;
    private static final int HERHALINGEN = 3;

    private static DienstHttp dienst;
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    private static List<Verzoekje> uniek;   // the distinct concrete requests (each with its serial baseline)

    @BeforeAll
    static void start() throws Exception {
        BeslisserKern kern = BeslisserKern.bouw(TokaBron.descriptorJson(), TokaBron.bronPerBestand(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
        dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        uniek = bouwVerzoekjes(kern.descriptor());
        // record every unique request's SERIAL baseline (the oracle — no hand-computed truth).
        for (Verzoekje v : uniek) {
            HttpResponse<String> resp = post(v.pad, DienstHttp.MAPPER.writeValueAsString(v.body));
            assertEquals(200, resp.statusCode(), () -> "serieel baseline faalt voor /" + v.pad + ": " + resp.body());
            v.baseline = DienstHttp.MAPPER.readTree(resp.body());
        }
    }

    @AfterAll
    static void stop() {
        if (dienst != null) dienst.stop(0);
        dienst = null;
    }

    // ---------------- the concurrency gate ----------------

    @Test
    void isolatie_onder_contentie() throws Exception {
        // sanity: 3 distinct families, an inconsistent one present, and each baseline sane.
        assertTrue(uniek.size() >= 3, "verwacht minstens 3 distincte verzoeken, kreeg " + uniek.size());
        assertTrue(uniek.stream().anyMatch(v -> v.inconsistent),
                "de rondvlucht-familie hoort inconsistent te zijn (leakage-detector)");
        for (Verzoekje v : uniek) {
            assertNotNull(v.baseline, "geen baseline voor /" + v.pad);
            List<String> inc = inconsistenties(v.baseline);
            if (v.inconsistent)
                assertTrue(!inc.isEmpty(),
                        "de rondvlucht-baseline hoort niet-lege inconsistenties te tonen: " + v.baseline);
            else
                assertTrue(inc.isEmpty(),
                        "een niet-inconsistente baseline (/" + v.pad + ") hoort lege inconsistenties te tonen: "
                                + v.baseline);
        }

        for (int ronde = 1; ronde <= HERHALINGEN; ronde++) {
            final int r = ronde;
            // build CONCURRENT requests, mixing the families round-robin over the distinct requests.
            List<Verzoekje> batch = new ArrayList<>();
            for (int i = 0; i < CONCURRENT; i++) batch.add(uniek.get(i % uniek.size()));

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch start = new CountDownLatch(1);   // the start gate — every task blocks until released
            CountDownLatch klaar = new CountDownLatch(batch.size());
            List<AtomicReference<Throwable>> fouten = new ArrayList<>();
            List<AtomicReference<JsonNode>> antwoorden = new ArrayList<>();

            for (Verzoekje v : batch) {
                AtomicReference<Throwable> fout = new AtomicReference<>();
                AtomicReference<JsonNode> antwoord = new AtomicReference<>();
                fouten.add(fout);
                antwoorden.add(antwoord);
                pool.submit(() -> {
                    try {
                        start.await();   // genuinely overlap: all tasks fire after the single release
                        HttpResponse<String> resp = post(v.pad, DienstHttp.MAPPER.writeValueAsString(v.body));
                        if (resp.statusCode() != 200)
                            throw new AssertionError("status " + resp.statusCode() + " voor /" + v.pad + ": " + resp.body());
                        antwoord.set(DienstHttp.MAPPER.readTree(resp.body()));
                    } catch (Throwable t) {
                        fout.set(t);
                    } finally {
                        klaar.countDown();
                    }
                });
            }
            start.countDown();                        // release the herd
            assertTrue(klaar.await(120, TimeUnit.SECONDS), "ronde " + r + ": niet alle verzoeken op tijd klaar");
            pool.shutdown();

            for (int i = 0; i < batch.size(); i++) {
                Throwable t = fouten.get(i).get();
                if (t != null) throw new AssertionError("ronde " + r + " taak " + i + " wierp: " + t, t);
                Verzoekje v = batch.get(i);
                JsonNode antwoord = antwoorden.get(i).get();
                assertNotNull(antwoord, "ronde " + r + " taak " + i + ": geen antwoord");
                // byte-for-byte deterministic: the concurrent response equals ITS OWN serial baseline exactly.
                assertEquals(v.baseline, antwoord,
                        "ronde " + r + " taak " + i + " (/" + v.pad + "): concurrent-antwoord wijkt af van de "
                                + "seriële baseline — sessie-lek of race\nbaseline: " + v.baseline + "\nkreeg: " + antwoord);
                // no-leakage: inconsistenties appear ONLY in the inconsistent family's own responses.
                List<String> inc = inconsistenties(antwoord);
                if (v.inconsistent)
                    assertTrue(!inc.isEmpty(), "ronde " + r + " taak " + i + ": rondvlucht-antwoord hoort inconsistent te blijven");
                else
                    assertTrue(inc.isEmpty(), "ronde " + r + " taak " + i + " (/" + v.pad
                            + "): een niet-rondvlucht-antwoord LEKTE inconsistenties: " + inc + " — cross-session lek");
            }
        }
    }

    // ---------------- request families (mechanically derived, inputs varied) ----------------

    /** One concrete request: its path, its (mutated-input) body, whether it's the inconsistent family, and its
     *  serial baseline (filled in {@link #start}). */
    private static final class Verzoekje {
        final String pad;
        final ObjectNode body;
        final boolean inconsistent;
        JsonNode baseline;
        Verzoekje(String pad, ObjectNode body, boolean inconsistent) {
            this.pad = pad; this.body = body; this.inconsistent = inconsistent;
        }
    }

    private static List<Verzoekje> bouwVerzoekjes(DienstDescriptor descriptor) throws IOException {
        List<Verzoekje> uit = new ArrayList<>();

        // Family A — bepaal-leeftijd, from scenario-toka-17. Vary geboortedatum + id per index.
        ObjectNode a0 = situatieVerzoek("examples/toka-scenarios/scenario-toka-17-bepaal-leeftijd.rs");
        String padA = padVoorScope(descriptor, "bepaal leeftijd");
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
            uit.add(new Verzoekje(padA, body, false));
        }

        // Family B — totaal-te-betalen-belasting, from scenario-toka-20. Vary the passenger bedragen per index.
        ObjectNode b0 = situatieVerzoek("examples/toka-scenarios/scenario-toka-20-totaal-te-betalen.rs");
        String padB = padVoorScope(descriptor, "Totaal te betalen belasting");
        String[][] bedragen = {{"30.00", "20.00"}, {"10.00", "5.00"}, {"100.00", "0.00"}};
        for (String[] paar : bedragen) {
            ObjectNode body = b0.deepCopy();
            int p = 0;
            for (JsonNode inst : body.get("instanties"))
                if ("Natuurlijk persoon".equalsIgnoreCase(inst.path("type").asText()) && p < paar.length) {
                    ((ObjectNode) inst.get("attributen")).put("te betalen belasting", new java.math.BigDecimal(paar[p]));
                    p++;
                }
            uit.add(new Verzoekje(padB, body, false));
        }

        // Family C — controleer-of-vlucht-geen-rondvlucht-is (§9.5), from scenario-toka-30. The golden is
        // CONSISTENT; make it INCONSISTENT by setting bestemming = vertrek (a rondvlucht), varying which airport
        // per index. Inputs only; the inconsistentie is the emergent truth, checked against its serial baseline.
        ObjectNode c0 = situatieVerzoek("examples/toka-scenarios/scenario-toka-30-geen-rondvlucht.rs");
        String padC = padVoorScope(descriptor, "Controleer of vlucht geen rondvlucht is");
        String[] luchthavens = {"Amsterdam Schiphol", "Parijs Charles de Gaulle", "Londen Heathrow"};
        for (String lh : luchthavens) {
            ObjectNode body = c0.deepCopy();
            for (JsonNode inst : body.get("instanties"))
                if ("Vlucht".equalsIgnoreCase(inst.path("type").asText())) {
                    ObjectNode attr = (ObjectNode) inst.get("attributen");
                    attr.put("luchthaven van vertrek", lh);
                    attr.put("luchthaven van bestemming", lh);   // rondvlucht -> inconsistent
                }
            uit.add(new Verzoekje(padC, body, true));
        }

        return uit;
    }

    /** Resolve a golden scenario file (+ its manifest companions) and mechanically derive the request body. */
    private static ObjectNode situatieVerzoek(String scenarioPad) throws IOException {
        Path scenario = TokaBron.REPO.resolve(scenarioPad);
        List<Path> companions = companionsVoor(scenario);
        StringBuilder bron = new StringBuilder();
        for (Path c : companions) bron.append(Files.readString(c)).append('\n');
        bron.append(Files.readString(scenario)).append('\n');
        ScenarioBundel bundel = ScenarioResolver.resolve(bron.toString());
        assertTrue(bundel.isOk() && !bundel.scenarios().isEmpty(),
                () -> "scenario resolvet niet: " + scenarioPad + " " + bundel.model().errors());
        ResolvedScenario sc = bundel.scenarios().get(0);
        return SituatieNaarVerzoek.bouw(sc);
    }

    /** The manifest companions for a scenario file (the exit-0 line whose scenario is this file). */
    private static List<Path> companionsVoor(Path scenario) throws IOException {
        Path manifest = TokaBron.REPO.resolve("examples/toka-scenarios/manifest");
        for (String regel : Files.readAllLines(manifest)) {
            String t = regel.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] v = t.split("\\s+");
            if (v.length < 2 || !"0".equals(v[0])) continue;
            if (TokaBron.REPO.resolve(v[1]).equals(scenario)) {
                List<Path> comps = new ArrayList<>();
                for (int i = 2; i < v.length; i++) comps.add(TokaBron.REPO.resolve(v[i]));
                return comps;
            }
        }
        return Collections.emptyList();
    }

    /** The descriptor entrypoint pad whose scope names exactly {@code regel}. */
    private static String padVoorScope(DienstDescriptor descriptor, String regel) {
        for (DienstDescriptor.Entrypoint ep : descriptor.entrypoints())
            if (ep.scope().regelnamenOfLeeg().contains(regel)) return ep.pad();
        throw new AssertionError("geen entrypoint scoped regel '" + regel + "'");
    }

    private static List<String> inconsistenties(JsonNode antwoord) {
        List<String> uit = new ArrayList<>();
        JsonNode inc = antwoord.get("inconsistenties");
        if (inc != null) for (JsonNode n : inc) uit.add(n.isNull() ? null : n.asText());
        return uit;
    }

    // ---------------- HTTP ----------------

    private static HttpResponse<String> post(String pad, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + dienst.poort() + "/" + pad))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
