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
import dev.uchar.regelspraak.toolchain.service.DienstDescriptor;
import dev.uchar.regelspraak.toolchain.service.TokaBron;

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
 * The final assertion pins that every golden scenario was reproduced over Quarkus or refused by uchar's scenario
 * resolver as its entry in {@link #UCHAR_WEIGERT} (the same list as {@code GoldenOverHttpTest}) says.
 */
@QuarkusTest
@TestProfile(TokaTestProfile.class)
class GoldenOverQuarkusTest {

    /** Exact-render mapper: a request/response number is never a double (mirror of {@code DienstHttp.MAPPER}). */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The golden scenario files uchar's scenario resolver refuses, each with a fragment of the refusal; the same
     *  list as {@code GoldenOverHttpTest.UCHAR_WEIGERT}. Measured on uchar d67f5da1. */
    private static final Map<String, String> UCHAR_WEIGERT = Map.of(
            "scenario-toka-29-passagier-met-recht.rgs",
                    "uit de scope bestaat niet in het document",
            "scenario-toka-43-startpunt-vertrek.rgs",
                    "een scope in een document met een startpuntbepaling wordt geweigerd",
            "scenario-toka-44-startpunt-min-reserveringstermijn.rgs",
                    "een scope in een document met een startpuntbepaling wordt geweigerd");

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
        AtomicInteger geweigerd = new AtomicInteger();
        List<DynamicTest> tests = new ArrayList<>();
        for (Golden g : golden) {
            String naam = g.scenarioBestand.getFileName().toString();
            String weigering = UCHAR_WEIGERT.get(naam);
            tests.add(DynamicTest.dynamicTest(naam, () -> {
                if (weigering != null) {
                    toetsWeigering(g, weigering);
                    geweigerd.incrementAndGet();
                } else {
                    toetsScenario(g);
                    geslaagd.incrementAndGet();
                }
            }));
        }
        tests.add(DynamicTest.dynamicTest("29 golden: over Quarkus gereproduceerd of door uchar geweigerd", () -> {
            assertEquals(UCHAR_WEIGERT.size(), geweigerd.get(), "elke weigering in UCHAR_WEIGERT hoort op te treden");
            assertEquals(29 - UCHAR_WEIGERT.size(), geslaagd.get(),
                    "golden reproducties over Quarkus; gereproduceerd: " + geslaagd.get());
        }));
        return tests.stream();
    }

    /** A listed golden scenario: uchar's scenario resolver refuses it with the listed fragment. */
    private static void toetsWeigering(Golden g, String fragment) throws IOException {
        StringBuilder bron = new StringBuilder();
        for (Path c : g.companions) bron.append(Files.readString(c)).append('\n');
        bron.append(Files.readString(g.scenarioBestand)).append('\n');
        ScenarioBundel bundel = ScenarioResolver.resolve(bron.toString());
        assertTrue(!bundel.isOk() && bundel.model().errors().stream().anyMatch(d -> d.message().contains(fragment)),
                () -> g.scenarioBestand.getFileName() + " staat in UCHAR_WEIGERT met '" + fragment + "', maar uchar "
                        + "weigert het zo niet (meer); fouten: " + bundel.model().errors());
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
        Class<?> snv = Class.forName("dev.uchar.regelspraak.toolchain.service.http.SituatieNaarVerzoek", true, cl);
        Class<?> rsClass = Class.forName("dev.uchar.regelspraak.ir.ResolvedScenario", true, cl);
        var m = snv.getDeclaredMethod("bouw", rsClass);
        m.setAccessible(true);
        return (ObjectNode) m.invoke(null, scenario);
    }

    // ---------------- verwachting comparison (mirrors GoldenOverHttpTest.vergelijk exactly) ----------------

    private void vergelijk(Verwachting v, JsonNode antwoord, String pad, JsonNode verzoek) {
        String ctx = "\nscope-pad /" + pad + "\nverwachting op regel " + v.line() + " (" + v.soort() + ")"
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
                String regel = v.regel();
                if (regel != null && !regel.isBlank())
                    assertTrue(inc.contains(regel),
                            () -> "verwacht dat regel '" + regel + "' in de inconsistenties staat, kreeg " + inc + ctx);
            }
            case ATTRIBUUT_TIJDLIJN -> throw new AssertionError(
                    "het antwoord van de dienst draagt geen tijdlijn, dus deze verwachting is over HTTP niet te toetsen" + ctx);
        }
    }

    private static boolean gelijk(Waarde verw, JsonNode werkelijk) {
        if (werkelijk == null || werkelijk.isNull()) return false;
        if (verw.getal() != null)
            return werkelijk.isNumber() && getal(verw.getal()).compareTo(werkelijk.decimalValue()) == 0;
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

    /** A case number lexeme as an exact BigDecimal: a decimal, or a fraction {@code p/q} whose quotient terminates
     *  (the same reading as the service test-jar's {@code SituatieNaarVerzoek.getal}, which the Quarkus class
     *  loader split keeps out of direct reach). */
    private static BigDecimal getal(String lexeem) {
        int streep = lexeem.indexOf('/');
        if (streep < 0) return new BigDecimal(lexeem);
        return new BigDecimal(lexeem.substring(0, streep)).divide(new BigDecimal(lexeem.substring(streep + 1)));
    }

    private static String renderWaarde(Waarde w) {
        if (w.getal() != null) return w.getal();
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
        if (scenario.scope() == null) return uit;
        for (String vorm : scenario.scope().vormen()) {
            String naam = CaseEmitter.basisNaam(vorm);
            if (!uit.contains(naam)) uit.add(naam);
        }
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
