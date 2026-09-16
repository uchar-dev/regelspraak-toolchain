package io.github.yhavinga.regelspraak.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import io.github.yhavinga.regelspraak.ir.ResolvedScenario;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.ScopeRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Verwachting;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Waarde;
import io.github.yhavinga.regelspraak.quarkus.TokaTestProfile;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import io.github.yhavinga.regelspraak.service.DienstDescriptor;
import io.github.yhavinga.regelspraak.service.TokaBron;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Q1 GOLDEN PARITY over the QUARKUS transport. The SAME "one truth, two transports" gate as
 * {@code GoldenOverHttpTest}, but the third transport is the Quarkus {@link BeslisResource} endpoint booted by
 * {@code @QuarkusTest}. For each of the 29 golden TOKA scenariotoetsen it:
 * <ol>
 *   <li>resolves the scenario (companions + scenario) → the typed {@link ResolvedScenario} IR (the single oracle);</li>
 *   <li>MECHANICALLY derives the request via {@link SituatieNaarVerzoek#bouw} (reused from the service test-jar —
 *       no expected value, input, label or scope is hand-typed);</li>
 *   <li>routes scope→entrypoint against the descriptor's {@code regelnamen} (exactly {@code GoldenOverHttpTest});</li>
 *   <li>POSTs to the Quarkus URL (RestAssured) and asserts EVERY verwachting with the SAME comparison
 *       {@code GoldenOverHttpTest.gelijk} uses.</li>
 * </ol>
 * The final assertion pins 29/29 reproduced over Quarkus (30 until besluit 16Y — see TokaBron).
 */
@QuarkusTest
@TestProfile(TokaTestProfile.class)
class GoldenOverQuarkusTest {

    /** Exact-render mapper: a request/response number is never a double (mirror of {@code DienstHttp.MAPPER}). */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static DienstDescriptor descriptor;

    private static DienstDescriptor descriptor() {
        if (descriptor == null) {
            // A plain mapper suffices for scope→pad ROUTING (the descriptor is a POJO; no float handling needed);
            // every expected value still comes from the scenario fixtures, so nothing is re-typed.
            try {
                descriptor = new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .readValue(TokaBron.descriptorJson(), DienstDescriptor.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return descriptor;
    }

    @TestFactory
    Stream<DynamicTest> golden_over_quarkus() throws IOException {
        List<Golden> golden = leesManifest();
        assertEquals(29, golden.size(), "de manifest hoort precies 29 exit-0 golden scenariotoetsen te tellen");

        AtomicInteger geslaagd = new AtomicInteger();
        List<DynamicTest> tests = new ArrayList<>();
        for (Golden g : golden)
            tests.add(DynamicTest.dynamicTest(g.scenarioBestand.getFileName().toString(), () -> {
                toetsScenario(g);
                geslaagd.incrementAndGet();
            }));
        tests.add(DynamicTest.dynamicTest("Q1: 29/29 golden over Quarkus", () ->
                assertEquals(29, geslaagd.get(),
                        "Q1 verlangt 29/29 golden reproducties over Quarkus; gereproduceerd: " + geslaagd.get())));
        return tests.stream();
    }

    private void toetsScenario(Golden g) throws Exception {
        StringBuilder bron = new StringBuilder();
        for (Path c : g.companions) bron.append(Files.readString(c)).append('\n');
        bron.append(Files.readString(g.scenarioBestand)).append('\n');
        ScenarioBundel bundel = ScenarioResolver.resolve(bron.toString());
        assertTrue(bundel.isOk(), () -> "scenario resolvet niet: " + bundel.model().errors());
        assertEquals(1, bundel.scenarios().size(),
                "een golden bestand hoort precies één scenario te dragen: " + g.scenarioBestand);
        ResolvedScenario scenario = bundel.scenarios().get(0);

        String pad = padVoorScope(scenario);
        assertNotNull(pad, () -> "geen descriptor-entrypoint scoped de regel(s) van scenario '" + scenario.naam()
                + "' (" + scopeNamen(scenario) + ")");

        ObjectNode verzoek = deriveVerzoek(scenario);
        Response resp = RestAssured.given()
                .contentType("application/json")
                .body(MAPPER.writeValueAsString(verzoek))
                .post("/" + pad);
        assertEquals(200, resp.statusCode(),
                () -> "POST /" + pad + " gaf " + resp.statusCode() + ": " + resp.body().asString()
                        + "\nverzoek: " + verzoek);
        JsonNode antwoord = MAPPER.readTree(resp.body().asString());

        for (Verwachting v : scenario.verwachtingen()) vergelijk(v, antwoord, pad, verzoek);
    }

    /**
     * Invoke the REUSED {@code SituatieNaarVerzoek.bouw} (from the service test-jar) to derive the request
     * MECHANICALLY — nothing is hand-typed. It is package-private static, and under @QuarkusTest the test-jar
     * class is loaded by a DIFFERENT QuarkusClassLoader than this test, which breaks same-package access; a
     * reflective {@code setAccessible} call over the SAME method bridges that classloader split without copying
     * or re-deriving any value.
     */
    private static ObjectNode deriveVerzoek(ResolvedScenario scenario) throws Exception {
        // Resolve the class BY NAME through the scenario object's OWN classloader so the ResolvedScenario
        // parameter type matches (the @QuarkusTest test class and the test-jar dependency live in DIFFERENT
        // QuarkusClassLoaders). No compile-time symbolic reference to SituatieNaarVerzoek — that would trigger a
        // cross-loader IllegalAccessError on the package-private class before reflection even runs.
        ClassLoader cl = scenario.getClass().getClassLoader();
        Class<?> snv = Class.forName("io.github.yhavinga.regelspraak.service.http.SituatieNaarVerzoek", true, cl);
        Class<?> rsClass = Class.forName("io.github.yhavinga.regelspraak.ir.ResolvedScenario", true, cl);
        var m = snv.getDeclaredMethod("bouw", rsClass);
        m.setAccessible(true);
        return (ObjectNode) m.invoke(null, scenario);
    }

    // ---------------- verwachting comparison (mirrors GoldenOverHttpTest.vergelijk exactly) ----------------

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
                JsonNode w = attribuut(antwoord, v.label(), v.attribuut());
                assertTrue(w == null || w.isNull(), () -> "verwacht leeg, kreeg " + w + ctx);
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
                String regel = v.attribuut();
                if (regel != null && !regel.isBlank())
                    assertTrue(inc.contains(regel),
                            () -> "verwacht dat regel '" + regel + "' in de inconsistenties staat, kreeg " + inc + ctx);
            }
        }
    }

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

    // ---------------- entrypoint lookup by scope (mirrors GoldenOverHttpTest) ----------------

    private String padVoorScope(ResolvedScenario scenario) {
        List<String> namen = scopeNamen(scenario);
        if (namen.isEmpty()) return null;
        for (DienstDescriptor.Entrypoint ep : descriptor().entrypoints()) {
            List<String> epNamen = ep.scope().regelnamenOfLeeg();
            if (epNamen.containsAll(namen) && namen.containsAll(epNamen)) return ep.pad();
        }
        for (DienstDescriptor.Entrypoint ep : descriptor().entrypoints())
            if (ep.scope().regelnamenOfLeeg().containsAll(namen)) return ep.pad();
        return null;
    }

    private static List<String> scopeNamen(ResolvedScenario scenario) {
        List<String> uit = new ArrayList<>();
        for (ScopeRegel r : scenario.scope().regels()) uit.add(r.naam());
        return uit;
    }

    // ---------------- manifest ----------------

    private record Golden(Path scenarioBestand, List<Path> companions) {}

    private static List<Golden> leesManifest() throws IOException {
        Path manifest = TokaBron.REPO.resolve("examples/toka-scenarios/manifest");
        List<Golden> uit = new ArrayList<>();
        for (String regel : Files.readAllLines(manifest)) {
            String t = regel.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] velden = t.split("\\s+");
            if (velden.length < 2) continue;
            if (!"0".equals(velden[0])) continue;
            Path scenario = TokaBron.REPO.resolve(velden[1]);
            List<Path> companions = new ArrayList<>();
            for (int i = 2; i < velden.length; i++) companions.add(TokaBron.REPO.resolve(velden[i]));
            uit.add(new Golden(scenario, companions));
        }
        return uit;
    }
}
