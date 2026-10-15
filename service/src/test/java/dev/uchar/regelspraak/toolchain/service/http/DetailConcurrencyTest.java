package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.DienstDescriptor;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.TokaBron;

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
 * {@link ConcurrencyTest}, derived from golden TOKA scenarios with {@link SituatieNaarVerzoek}, over three entry points
 * that fire different rules; every request gets ids of its own (the ids of the golden situation with a prefix per
 * request). The truth of each request is its own serial answer with {@code trace}, {@code dekking} and
 * {@code fixpunt}. Then {@value #CONCURRENT} requests over the families start at once, {@value #HERHALINGEN} times,
 * and each answer must equal its serial baseline: a firing that leaked from another session would add a row to the
 * trace or raise a count, and change the coverage. The waarnemer labels a firing only with the beans of its own
 * request, so a leak would not show as a foreign label; the test checks besides that every label of a trace, and every
 * object of a fixpoint that is no made object ({@code #<n>}), is an id of the request itself.
 */
class DetailConcurrencyTest {

    private static final int CONCURRENT = 60;
    private static final int HERHALINGEN = 3;
    private static final String DETAIL = "trace=true&dekking=true&fixpunt=true";

    private static DienstHttp dienst;
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private static List<Verzoekje> uniek;

    @BeforeAll
    static void start() throws Exception {
        BeslisserKern kern = BeslisserKern.bouw(TokaBron.descriptorJson(), TokaBron.bronPerBestand(),
                KernConfig.strikt().metDetail(), Thread.currentThread().getContextClassLoader());
        dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        uniek = verzoekjes(kern.descriptor());
        for (Verzoekje v : uniek) {
            HttpResponse<String> r = post(v.pad, DienstHttp.MAPPER.writeValueAsString(v.body));
            assertEquals(200, r.statusCode(), () -> "seriële basislijn faalt voor /" + v.pad + ": " + r.body());
            v.basislijn = DienstHttp.MAPPER.readTree(r.body());
        }
    }

    @AfterAll
    static void stop() {
        if (dienst != null) dienst.stop(0);
        dienst = null;
    }

    @Test
    void vuringen_lekken_niet_tussen_sessies() throws Exception {
        for (Verzoekje v : uniek) {
            assertFalse(v.basislijn.get("trace").isEmpty(), "de basislijn van /" + v.pad + " vuurt niets: " + v.basislijn);
            alleenEigenIds(v, v.basislijn, "basislijn");
        }
        for (int ronde = 1; ronde <= HERHALINGEN; ronde++) {
            List<Verzoekje> batch = new ArrayList<>();
            for (int i = 0; i < CONCURRENT; i++) batch.add(uniek.get(i % uniek.size()));
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch start = new CountDownLatch(1);
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
                        start.await();
                        HttpResponse<String> r = post(v.pad, DienstHttp.MAPPER.writeValueAsString(v.body));
                        if (r.statusCode() != 200)
                            throw new AssertionError("status " + r.statusCode() + " voor /" + v.pad + ": " + r.body());
                        antwoord.set(DienstHttp.MAPPER.readTree(r.body()));
                    } catch (Throwable t) {
                        fout.set(t);
                    } finally {
                        klaar.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(klaar.await(120, TimeUnit.SECONDS), "ronde " + ronde + ": niet alle verzoeken op tijd klaar");
            pool.shutdown();
            for (int i = 0; i < batch.size(); i++) {
                Throwable t = fouten.get(i).get();
                if (t != null) throw new AssertionError("ronde " + ronde + " taak " + i + " wierp: " + t, t);
                Verzoekje v = batch.get(i);
                JsonNode antwoord = antwoorden.get(i).get();
                assertNotNull(antwoord, "ronde " + ronde + " taak " + i + ": geen antwoord");
                assertEquals(v.basislijn, antwoord, "ronde " + ronde + " taak " + i + " (/" + v.pad
                        + "): het antwoord wijkt af van de seriële basislijn");
                alleenEigenIds(v, antwoord, "ronde " + ronde + " taak " + i);
            }
        }
    }

    /** Every label of the trace, and every object, subject and linked object of the fixpoint that is no made object,
     *  is an id of {@code v}'s own request. */
    private static void alleenEigenIds(Verzoekje v, JsonNode antwoord, String waar) {
        for (JsonNode t : antwoord.get("trace"))
            for (JsonNode l : t.get("labels"))
                assertTrue(v.ids.contains(l.asText()), waar + " (/" + v.pad + "): de trace noemt '" + l.asText()
                        + "', geen id van dit verzoek " + v.ids + ": een vuring van een andere sessie");
        JsonNode f = antwoord.get("fixpunt");
        List<String> objecten = new ArrayList<>();
        for (JsonNode o : f.get("objecten")) objecten.add(o.get("object").asText());
        for (JsonNode k : f.get("koppelingen")) { objecten.add(k.get("eerste").asText()); objecten.add(k.get("tweede").asText()); }
        for (JsonNode inc : f.get("inconsistenties")) if (inc.hasNonNull("onderwerp")) objecten.add(inc.get("onderwerp").asText());
        for (String o : objecten)
            assertTrue(o.startsWith("#") || v.ids.contains(o), waar + " (/" + v.pad + "): het fixpunt noemt '" + o
                    + "', geen id van dit verzoek " + v.ids);
    }

    // ---------------- the families of ConcurrencyTest, each request with ids of its own ----------------

    private static final class Verzoekje {
        final String pad;
        final ObjectNode body;
        final Set<String> ids;
        JsonNode basislijn;

        Verzoekje(String pad, ObjectNode body) {
            this.pad = pad;
            this.body = body;
            this.ids = new HashSet<>();
            for (JsonNode i : body.get("instanties")) ids.add(i.get("id").asText());
        }
    }

    private static List<Verzoekje> verzoekjes(DienstDescriptor descriptor) throws Exception {
        List<Verzoekje> uit = new ArrayList<>();
        int n = 0;
        ObjectNode a0 = situatieVerzoek("examples/toka-scenarios/scenario-toka-17-bepaal-leeftijd.rgs");
        String padA = padVoorScope(descriptor, "bepaal leeftijd");
        for (String geboorte : new String[]{"1990-06-15", "1985-01-01", "2000-12-31", "1970-03-03"}) {
            ObjectNode body = eigenIds(a0, "R" + n++ + "-");
            for (JsonNode inst : body.get("instanties"))
                if ("Natuurlijk persoon".equalsIgnoreCase(inst.path("type").asText()))
                    ((ObjectNode) inst.get("attributen")).put("geboortedatum", geboorte);
            uit.add(new Verzoekje(padA, body));
        }
        ObjectNode b0 = situatieVerzoek("examples/toka-scenarios/scenario-toka-20-totaal-te-betalen.rgs");
        String padB = padVoorScope(descriptor, "Totaal te betalen belasting");
        for (String[] paar : new String[][]{{"30.00", "20.00"}, {"10.00", "5.00"}, {"100.00", "0.00"}, {"1.00", "2.00"}}) {
            ObjectNode body = eigenIds(b0, "R" + n++ + "-");
            int p = 0;
            for (JsonNode inst : body.get("instanties"))
                if ("Natuurlijk persoon".equalsIgnoreCase(inst.path("type").asText()) && p < paar.length)
                    ((ObjectNode) inst.get("attributen")).put("te betalen belasting", new java.math.BigDecimal(paar[p++]));
            uit.add(new Verzoekje(padB, body));
        }
        ObjectNode c0 = situatieVerzoek("examples/toka-scenarios/scenario-toka-30-geen-rondvlucht.rgs");
        String padC = padVoorScope(descriptor, "Controleer of vlucht geen rondvlucht is");
        for (String lh : new String[]{"Amsterdam Schiphol", "Parijs Charles de Gaulle", "Londen Heathrow"}) {
            ObjectNode body = eigenIds(c0, "R" + n++ + "-");
            for (JsonNode inst : body.get("instanties"))
                if ("Vlucht".equalsIgnoreCase(inst.path("type").asText())) {
                    ObjectNode attr = (ObjectNode) inst.get("attributen");
                    attr.put("luchthaven van vertrek", lh);
                    attr.put("luchthaven van bestemming", lh);
                }
            uit.add(new Verzoekje(padC, body));
        }
        return uit;
    }

    /** A copy of {@code verzoek} with {@code voorvoegsel} before every id, on the instances and in their roles. */
    private static ObjectNode eigenIds(ObjectNode verzoek, String voorvoegsel) {
        ObjectNode uit = verzoek.deepCopy();
        for (JsonNode inst : uit.get("instanties")) {
            ((ObjectNode) inst).put("id", voorvoegsel + inst.get("id").asText());
            JsonNode rollen = inst.get("rollen");
            if (rollen == null) continue;
            for (var e : rollen.properties()) {
                if (e.getValue().isArray())
                    for (int i = 0; i < e.getValue().size(); i++)
                        ((ArrayNode) e.getValue()).set(i, TextNode.valueOf(voorvoegsel + e.getValue().get(i).asText()));
                else ((ObjectNode) rollen).put(e.getKey(), voorvoegsel + e.getValue().asText());
            }
        }
        assertFalse(uit.has("relaties"), "SituatieNaarVerzoek schrijft geen relaties");
        return uit;
    }

    private static ObjectNode situatieVerzoek(String scenarioPad) throws Exception {
        Path scenario = TokaBron.REPO.resolve(scenarioPad);
        StringBuilder bron = new StringBuilder();
        for (Path c : companionsVoor(scenario)) bron.append(Files.readString(c)).append('\n');
        bron.append(Files.readString(scenario)).append('\n');
        ScenarioBundel bundel = ScenarioResolver.resolve(bron.toString());
        assertTrue(bundel.isOk() && !bundel.scenarios().isEmpty(),
                () -> "scenario resolvet niet: " + scenarioPad + " " + bundel.model().errors());
        ResolvedScenario sc = bundel.scenarios().get(0);
        return SituatieNaarVerzoek.bouw(sc);
    }

    private static List<Path> companionsVoor(Path scenario) throws Exception {
        for (String regel : Files.readAllLines(TokaBron.REPO.resolve("examples/toka-scenarios/manifest"))) {
            String t = regel.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] v = t.split("\\s+");
            if (v.length < 2 || !"0".equals(v[0])) continue;
            if (TokaBron.REPO.resolve(v[1]).equals(scenario)) {
                List<Path> uit = new ArrayList<>();
                for (int i = 2; i < v.length; i++) uit.add(TokaBron.REPO.resolve(v[i]));
                return uit;
            }
        }
        return List.of();
    }

    private static String padVoorScope(DienstDescriptor descriptor, String regel) {
        for (DienstDescriptor.Entrypoint ep : descriptor.entrypoints())
            if (ep.scope().regelnamenOfLeeg().contains(regel)) return ep.pad();
        throw new AssertionError("geen entrypoint scoped regel '" + regel + "'");
    }

    private static HttpResponse<String> post(String pad, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + dienst.poort() + "/" + pad + "?" + DETAIL))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
