package io.github.yhavinga.regelspraak.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.yhavinga.regelspraak.ir.ResolvedScenario;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.ScopeRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Verwachting;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Waarde;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import io.github.yhavinga.regelspraak.service.BeslisserKern;
import io.github.yhavinga.regelspraak.service.DienstDescriptor;
import io.github.yhavinga.regelspraak.service.KernConfig;
import io.github.yhavinga.regelspraak.service.TokaBron;

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
 * Gate G2 — ONE TRUTH, TWO TRANSPORTS. For each of the 30 golden TOKA scenariotoetsen (the exit-0 lines of
 * {@code examples/toka-scenarios/manifest}) this harness:
 *
 * <ol>
 *   <li>resolves the scenario ({@link ScenarioResolver#resolve} over the concatenated companions + scenario),
 *       yielding the SAME typed IR ({@link ResolvedScenario}) the in-process {@code ScenarioRunner} consumes;</li>
 *   <li>MECHANICALLY derives the HTTP request from the resolved situatie ({@link SituatieNaarVerzoek}) — no
 *       expected value, input value, label or scope is hand-typed;</li>
 *   <li>looks the entrypoint up by matching the scenario's scope (a single {@code toetst de regel X}) to the
 *       descriptor entrypoint whose {@code scope.regelnamen} names X (loaded with {@link DescriptorLoader#MAPPER});</li>
 *   <li>POSTs to ONE {@link DienstHttp} over ONE {@link BeslisserKern} (the full TOKA service, DRL back-end,
 *       strict) and asserts the response reproduces EVERY verwachting, comparing with the SAME semantics
 *       {@code ScenarioRunner.vergelijk} uses (BigDecimal.compareTo for numeriek, equals-after-normalisation for
 *       datum/datumtijd, equals for tekst/enum/bool, boolean read for kenmerk, empty/non-empty inconsistenties
 *       for CONSISTENT/INCONSISTENT).</li>
 * </ol>
 *
 * The 30 scenarios are dynamic tests (so one failure names its scenario) plus a final count assertion that the
 * harness saw exactly 30 golden scenarios and all reproduced — "G2: 30/30 golden over HTTP".
 */
class GoldenOverHttpTest {

    private static DienstHttp dienst;
    private static DienstDescriptor descriptor;
    private static String baseUrl;
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void startDienst() throws IOException {
        // dienst.url override (chunk B, the third transport): when set — system property `dienst.url` or env
        // DIENST_URL — the IDENTICAL 30 golden assertions run against an already-running service (e.g. the
        // in-cluster pod) instead of an in-process DienstHttp. The descriptor is the same toka.dienst.json,
        // parsed standalone for scope→entrypoint ROUTING only; every expected value still comes from the
        // scenario fixtures, so nothing is duplicated or re-typed.
        String override = System.getProperty("dienst.url", System.getenv("DIENST_URL"));
        if (override != null && !override.isBlank()) {
            baseUrl = override.endsWith("/") ? override.substring(0, override.length() - 1) : override;
            descriptor = DienstHttp.MAPPER.readValue(TokaBron.descriptorJson(), DienstDescriptor.class);
            return;
        }
        BeslisserKern kern = BeslisserKern.bouw(TokaBron.descriptorJson(), TokaBron.bronPerBestand(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
        dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        baseUrl = "http://127.0.0.1:" + dienst.poort();
        // the kern already parsed the descriptor with DescriptorLoader.MAPPER at startup; reuse that resolved
        // descriptor so the scope→entrypoint lookup is over the very same entry set the routes were built from.
        descriptor = kern.descriptor();
    }

    @AfterAll
    static void stopDienst() {
        if (dienst != null) dienst.stop(0);
        dienst = null;
    }

    // ---------------- the 30 golden scenarios ----------------

    @TestFactory
    Stream<DynamicTest> golden_over_http() throws IOException {
        List<Golden> golden = leesManifest();
        assertEquals(30, golden.size(), "de manifest hoort precies 30 exit-0 golden scenariotoetsen te tellen");

        AtomicInteger geslaagd = new AtomicInteger();
        List<DynamicTest> tests = new ArrayList<>();
        for (Golden g : golden)
            tests.add(DynamicTest.dynamicTest(g.scenarioBestand.getFileName().toString(), () -> {
                toetsScenario(g);
                geslaagd.incrementAndGet();
            }));
        // final aggregating assertion: all 30 reproduced.
        tests.add(DynamicTest.dynamicTest("G2: 30/30 golden over HTTP", () ->
                assertEquals(30, geslaagd.get(),
                        "G2 verlangt 30/30 golden reproducties over HTTP; gereproduceerd: " + geslaagd.get())));
        return tests.stream();
    }

    /** Resolve one golden scenario, derive its request, POST it, and assert every verwachting. */
    private void toetsScenario(Golden g) throws Exception {
        // 1. resolve (companions first, then the scenario file) — the resolver output is the single oracle.
        StringBuilder bron = new StringBuilder();
        for (Path c : g.companions) bron.append(Files.readString(c)).append('\n');
        bron.append(Files.readString(g.scenarioBestand)).append('\n');
        ScenarioBundel bundel = ScenarioResolver.resolve(bron.toString());
        assertTrue(bundel.isOk(), () -> "scenario resolvet niet: " + bundel.model().errors());
        assertEquals(1, bundel.scenarios().size(),
                "een golden bestand hoort precies één scenario te dragen: " + g.scenarioBestand);
        ResolvedScenario scenario = bundel.scenarios().get(0);

        // 2. entrypoint by scope — never guessed by kebab-case; matched against the descriptor's regelnamen.
        String pad = padVoorScope(scenario);
        assertNotNull(pad, () -> "geen descriptor-entrypoint scoped de regel(s) van scenario '" + scenario.naam()
                + "' (" + scopeNamen(scenario) + ")");

        // 3. derive the request mechanically + POST.
        com.fasterxml.jackson.databind.node.ObjectNode verzoek = SituatieNaarVerzoek.bouw(scenario);
        HttpResponse<String> resp = post(pad, DienstHttp.MAPPER.writeValueAsString(verzoek));
        assertEquals(200, resp.statusCode(),
                () -> "POST /" + pad + " gaf " + resp.statusCode() + ": " + resp.body()
                        + "\nverzoek: " + verzoek);
        JsonNode antwoord = DienstHttp.MAPPER.readTree(resp.body());

        // 4. every verwachting, mirroring ScenarioRunner.vergelijk exactly.
        for (Verwachting v : scenario.verwachtingen()) vergelijk(v, antwoord, pad, verzoek);
    }

    // ---------------- verwachting comparison (mirrors ScenarioRunner.vergelijk) ----------------

    private void vergelijk(Verwachting v, JsonNode antwoord, String pad, JsonNode verzoek) {
        String ctx = "\nscope-pad /" + pad + "\nverwachting: '" + v.bron().trim() + "'"
                + "\nverzoek: " + verzoek + "\nantwoord: " + antwoord;
        switch (v.soort()) {
            case ATTRIBUUT_WAARDE -> {
                JsonNode w = attribuut(antwoord, v.label(), v.attribuut());
                assertNotNull(w, () -> "geen attribuut '" + v.attribuut() + "' op instantie '" + v.label() + "'" + ctx);
                assertTrue(gelijk(v.verwachteWaarde(), w),
                        () -> "verwacht " + renderWaarde(v.verwachteWaarde()) + ", kreeg " + w + ctx);
            }
            case ATTRIBUUT_LEEG -> {
                // AntwoordBouwer renders an unresolved value as JSON null (rend(null) -> nullNode). So an empty
                // attribute is either JSON null OR the attribute node absent for that instance — assert THAT.
                JsonNode w = attribuut(antwoord, v.label(), v.attribuut());
                assertTrue(w == null || w.isNull(),
                        () -> "verwacht leeg, kreeg " + w + ctx);
            }
            case KENMERK_AANWEZIG -> assertTrue(kenmerkWaar(antwoord, v.label(), v.kenmerk()),
                    () -> "verwacht kenmerk '" + v.kenmerk() + "' AANWEZIG op '" + v.label() + "'" + ctx);
            case KENMERK_AFWEZIG -> assertTrue(!kenmerkWaar(antwoord, v.label(), v.kenmerk()),
                    () -> "verwacht kenmerk '" + v.kenmerk() + "' AFWEZIG op '" + v.label() + "'" + ctx);
            case CONSISTENT -> assertTrue(inconsistenties(antwoord).isEmpty(),
                    () -> "verwacht consistent (lege inconsistenties), kreeg " + inconsistenties(antwoord) + ctx);
            case INCONSISTENT -> {
                List<String> inc = inconsistenties(antwoord);
                assertTrue(!inc.isEmpty(), () -> "verwacht inconsistent (niet-lege inconsistenties)" + ctx);
                // if the verwachting names a regel, it must be listed (mirrors the runner's "and if named" clause).
                String regel = v.attribuut();
                if (regel != null && !regel.isBlank())
                    assertTrue(inc.contains(regel),
                            () -> "verwacht dat regel '" + regel + "' in de inconsistenties staat, kreeg " + inc + ctx);
            }
        }
    }

    /** Mirror of {@code ScenarioRunner.gelijk}: numeriek via BigDecimal.compareTo, datum/datumtijd/bool/enum/tekst
     *  via equals-after-normalisation (a datetime response is the LocalDateTime.toString() form, so parse it back). */
    private static boolean gelijk(Waarde verw, JsonNode werkelijk) {
        if (verw == null || verw.leeg()) return werkelijk == null || werkelijk.isNull();
        if (werkelijk == null || werkelijk.isNull()) return false;
        if (verw.getal() != null)
            return werkelijk.isNumber() && verw.getal().compareTo(werkelijk.decimalValue()) == 0;
        if (verw.datum() != null)
            return werkelijk.isTextual() && verw.datum().equals(LocalDate.parse(werkelijk.asText()));
        if (verw.datumTijd() != null)
            return werkelijk.isTextual() && verw.datumTijd().equals(LocalDateTime.parse(werkelijk.asText()));
        if (verw.bool() != null)
            return werkelijk.isBoolean() && verw.bool() == werkelijk.booleanValue();
        if (verw.enumWaarde() != null)
            return werkelijk.isTextual() && verw.enumWaarde().equals(werkelijk.asText());
        if (verw.tekst() != null)
            return werkelijk.isTextual() && verw.tekst().equals(werkelijk.asText());
        return werkelijk.isNull();
    }

    private static String renderWaarde(Waarde w) {
        if (w == null || w.leeg()) return "leeg";
        if (w.getal() != null) return w.getal().toPlainString();
        if (w.datum() != null) return w.datum().toString();
        if (w.datumTijd() != null) return w.datumTijd().toString();
        if (w.bool() != null) return w.bool().toString();
        if (w.enumWaarde() != null) return "'" + w.enumWaarde() + "'";
        return "\"" + w.tekst() + "\"";
    }

    // ---------------- response readers ----------------

    private static JsonNode attribuut(JsonNode antwoord, String id, String naam) {
        JsonNode inst = instantie(antwoord, id);
        return inst == null ? null : inst.path("attributen").get(naam);
    }

    private static boolean kenmerkWaar(JsonNode antwoord, String id, String naam) {
        JsonNode inst = instantie(antwoord, id);
        if (inst == null) return false;
        JsonNode k = inst.path("kenmerken").get(naam);
        return k != null && k.isBoolean() && k.booleanValue();
    }

    private static JsonNode instantie(JsonNode antwoord, String id) {
        JsonNode insts = antwoord.get("instanties");
        if (insts == null) return null;
        for (JsonNode inst : insts) if (id.equals(inst.path("id").asText())) return inst;
        return null;
    }

    private static List<String> inconsistenties(JsonNode antwoord) {
        List<String> uit = new ArrayList<>();
        JsonNode inc = antwoord.get("inconsistenties");
        if (inc != null) for (JsonNode n : inc) uit.add(n.isNull() ? null : n.asText());
        return uit;
    }

    // ---------------- entrypoint lookup by scope ----------------

    /** The descriptor entrypoint path whose {@code scope.regelnamen} names every scope rule of the scenario. A
     *  single-rule scope (all golden scenarios) matches the entrypoint whose regelnamen contains that rule. */
    private String padVoorScope(ResolvedScenario scenario) {
        List<String> namen = scopeNamen(scenario);
        if (namen.isEmpty()) return null;
        for (DienstDescriptor.Entrypoint ep : descriptor.entrypoints()) {
            List<String> epNamen = ep.scope().regelnamenOfLeeg();
            if (epNamen.containsAll(namen) && namen.containsAll(epNamen)) return ep.pad();
        }
        // fall back: the entrypoint that names ALL of the scope rules (superset), for a multi-rule scope.
        for (DienstDescriptor.Entrypoint ep : descriptor.entrypoints())
            if (ep.scope().regelnamenOfLeeg().containsAll(namen)) return ep.pad();
        return null;
    }

    private static List<String> scopeNamen(ResolvedScenario scenario) {
        List<String> uit = new ArrayList<>();
        for (ScopeRegel r : scenario.scope().regels()) uit.add(r.naam());
        return uit;
    }

    // ---------------- manifest ----------------

    /** One exit-0 golden line: the scenario file and its companion files. */
    private record Golden(Path scenarioBestand, List<Path> companions) {}

    private static List<Golden> leesManifest() throws IOException {
        Path manifest = TokaBron.REPO.resolve("examples/toka-scenarios/manifest");
        List<Golden> uit = new ArrayList<>();
        for (String regel : Files.readAllLines(manifest)) {
            String t = regel.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] velden = t.split("\\s+");
            if (velden.length < 2) continue;
            if (!"0".equals(velden[0])) continue;   // exit-0 lines only
            Path scenario = TokaBron.REPO.resolve(velden[1]);
            List<Path> companions = new ArrayList<>();
            for (int i = 2; i < velden.length; i++) companions.add(TokaBron.REPO.resolve(velden[i]));
            uit.add(new Golden(scenario, companions));
        }
        return uit;
    }

    // ---------------- HTTP ----------------

    private HttpResponse<String> post(String pad, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + pad))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
