package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Gate G1 — the OpenAPI contract. The TOKA {@link OpenApiEmitter} document is:
 * <ol>
 *   <li>WRITTEN at build time to {@code target/openapi/<kebab(dienst.naam)>.json} (the "write-at-build" step —
 *       done here inside the test so it needs no extra Maven plugin, keeping the build offline);</li>
 *   <li>byte-pinned against {@code src/test/resources/openapi/toka-belastingdienst.golden.json} (a mismatch
 *       fails with a compact first-difference report);</li>
 *   <li>structurally linted — assertions OVER the generated document ARE the lint (no external tool):
 *       every entrypoint has a path; every invoer objecttype and every uitvoer objecttype.attribuut has a
 *       schema property; every Enumeratie lists ALL its waarden; every eenheid-bearing attribuut of an
 *       invoer/uitvoer objecttype carries {@code x-eenheid};</li>
 *   <li>deterministic (emit twice → byte-equal).</li>
 * </ol>
 */
class OpenApiEmitterTest {

    private static DienstDescriptor DESCRIPTOR;
    private static RegelSpraakModel MODEL;
    private static ObjectNode DOC;
    private static byte[] BYTES;

    private static final Path GOLDEN =
            Path.of("src/test/resources/openapi/toka-belastingdienst.golden.json");

    @BeforeAll
    static void bouw() throws IOException {
        MODEL = TokaBron.model();
        DESCRIPTOR = DescriptorLoader.MAPPER.readValue(TokaBron.descriptorJson(), DienstDescriptor.class);
        DOC = OpenApiEmitter.emit(DESCRIPTOR, MODEL);
        BYTES = OpenApiEmitter.naarBytes(DOC);

        // write-at-build: the document lands at target/openapi/<kebab(dienst.naam)>.json during `mvn test`.
        Path doel = Path.of("target", "openapi", OpenApiEmitter.bestandsnaam(DESCRIPTOR));
        OpenApiEmitter.schrijf(DESCRIPTOR, MODEL, doel);
        assertTrue(Files.exists(doel), "het gegenereerde document hoort op " + doel.toAbsolutePath() + " te staan");
    }

    // ------------------------------------------------------------------ Test 1: byte-pin

    @Test
    void document_is_byte_identiek_aan_golden() throws IOException {
        assertTrue(Files.exists(GOLDEN), "golden ontbreekt: " + GOLDEN.toAbsolutePath());
        byte[] verwacht = Files.readAllBytes(GOLDEN);
        if (!Arrays.equals(verwacht, BYTES)) fail(eersteVerschil(verwacht, BYTES));
    }

    /** A compact first-difference report: the byte offset, the surrounding line, and a caret. */
    private static String eersteVerschil(byte[] verwacht, byte[] werkelijk) {
        int n = Math.min(verwacht.length, werkelijk.length);
        int i = 0;
        while (i < n && verwacht[i] == werkelijk[i]) i++;
        String v = new String(verwacht, StandardCharsets.UTF_8);
        String w = new String(werkelijk, StandardCharsets.UTF_8);
        int lijn = 1, kolStart = 0;
        for (int k = 0; k < i && k < v.length(); k++) {
            if (v.charAt(k) == '\n') { lijn++; kolStart = k + 1; }
        }
        int kol = i - kolStart + 1;
        String vContext = regelBij(v, i);
        String wContext = regelBij(w, i);
        return "OpenAPI-document wijkt af van de golden op byte-offset " + i
                + " (regel " + lijn + ", kolom " + kol + ").\n"
                + "  golden : " + vContext + "\n"
                + "  emitted: " + wContext + "\n"
                + "  (golden=" + verwacht.length + " bytes, emitted=" + werkelijk.length + " bytes) — "
                + "her-genereer de golden met OpenApiEmitter en commit die opnieuw als dit een bewuste wijziging is.";
    }

    private static String regelBij(String s, int offset) {
        if (offset >= s.length()) return "(einde van document — " + s.length() + " bytes)";
        int start = s.lastIndexOf('\n', offset) + 1;
        int eind = s.indexOf('\n', offset);
        if (eind < 0) eind = s.length();
        return s.substring(start, Math.min(eind, start + 200));
    }

    // ------------------------------------------------------------------ Test 2: structural lint

    @Test
    void elke_entrypoint_heeft_een_pad() {
        JsonNode paths = DOC.get("paths");
        assertNotNull(paths, "geen paths-object");
        for (DienstDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints())
            assertTrue(paths.has("/" + ep.pad()) && paths.get("/" + ep.pad()).has("post"),
                    "geen POST-pad voor entrypoint '" + ep.naam() + "' (pad '/" + ep.pad() + "')");
        assertTrue(paths.has("/info") && paths.get("/info").has("get"), "geen GET /info-pad");
    }

    /** The 422 of every entry point names the body {@link AntwoordBouwer#evaluatiefout} builds, and no other fields:
     *  {@code status} EVALUATIEFOUT, the sentence {@code fout}, and {@code fouten}, each with its {@code soort}, one of
     *  the kinds of {@link Evaluatiefout.Soort}, and uchar's sentence {@code fout}; and, not required, the detail a
     *  request may ask ({@code trace}, {@code dekking}, {@code fixpunt} or {@code fixpuntFout}). */
    @Test
    void de_422_beschrijft_het_lichaam_van_een_evaluatiefout() {
        for (DienstDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints())
            assertEquals("#/components/schemas/Evaluatiefout", DOC.get("paths").get("/" + ep.pad())
                    .at("/post/responses/422/content/application~1json/schema/oneOf/0/$ref").asText(), "de 422 van '/" + ep.pad() + "'");
        JsonNode s = DOC.at("/components/schemas/Evaluatiefout");
        assertEquals("[\"status\",\"fout\",\"fouten\"]", s.path("required").toString(), s::toString);
        assertEquals(java.util.List.of("status", "fout", "fouten", "trace", "dekking", "fixpunt", "fixpuntFout"),
                velden(s.path("properties")), s::toString);
        assertEquals("[\"EVALUATIEFOUT\"]", s.at("/properties/status/enum").toString(), s::toString);
        assertEquals("string", s.at("/properties/fout/type").asText(), s::toString);
        assertEquals("array", s.at("/properties/fouten/type").asText(), s::toString);
        JsonNode fout = s.at("/properties/fouten/items");
        assertEquals("[\"soort\",\"fout\"]", fout.path("required").toString(), s::toString);
        assertEquals(java.util.List.of("soort", "fout"), velden(fout.path("properties")), s::toString);
        assertEquals(Arrays.stream(Evaluatiefout.Soort.values()).map(Enum::name).toList(),
                fout.at("/properties/soort/enum").valueStream().map(JsonNode::asText).toList(), s::toString);
        assertEquals("string", fout.at("/properties/fout/type").asText(), s::toString);
    }

    private static java.util.List<String> velden(JsonNode n) {
        java.util.List<String> uit = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(uit::add);
        return uit;
    }

    @Test
    void elke_invoer_en_uitvoer_objecttype_heeft_een_schema_eigenschap() {
        JsonNode schemas = DOC.get("components").get("schemas");
        for (DienstDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints()) {
            // every invoer objecttype has a component schema
            for (String inv : ep.invoer() == null ? java.util.List.<String>of() : ep.invoer()) {
                Objecttype ot = DescriptorLoader.objecttype(MODEL, inv);
                assertNotNull(ot, "onbekend invoer-objecttype '" + inv + "'");
                assertTrue(schemas.has(component(ot)),
                        "geen schema voor invoer-objecttype '" + inv + "' (component '" + component(ot) + "')");
            }
            // every uitvoer selector's objecttype.lid has a schema property
            for (String sel : ep.uitvoer() == null ? java.util.List.<String>of() : ep.uitvoer()) {
                Uitvoerselector u = Uitvoerselector.ontleed(sel);
                assertNotNull(u, "onontleedbare uitvoer-selector '" + sel + "'");
                Objecttype ot = DescriptorLoader.objecttype(MODEL, u.objecttype());
                assertNotNull(ot, "onbekend uitvoer-objecttype in '" + sel + "'");
                JsonNode props = schemas.get(component(ot)).get("properties");
                assertNotNull(props, "geen properties op schema '" + component(ot) + "'");
                assertTrue(heeftEigenschap(props, u.lid()),
                        "geen schema-eigenschap '" + u.lid() + "' op '" + component(ot) + "' voor uitvoer '" + sel + "'");
            }
        }
    }

    @Test
    void elke_enumeratie_lijst_al_haar_waarden() {
        JsonNode schemas = DOC.get("components").get("schemas");
        for (Objecttype ot : MODEL.objecttypen.values()) {
            JsonNode props = schemas.get(component(ot)).get("properties");
            for (var e : ot.attributen.entrySet()) {
                if (!(e.getValue() instanceof Datatype.Enumeratie enm)) continue;
                if (!ot.dimensiesVan(e.getKey()).isEmpty()) continue;   // dimensie: not emitted (rejected)
                JsonNode prop = eigenschap(props, e.getKey());
                assertNotNull(prop, "geen eigenschap voor enumeratie-attribuut '" + e.getKey() + "'");
                JsonNode enumN = prop.get("enum");
                assertNotNull(enumN, "geen enum-lijst op '" + e.getKey() + "'");
                java.util.List<String> gevonden = new java.util.ArrayList<>();
                enumN.forEach(w -> gevonden.add(w.asText()));
                assertEquals(enm.waarden(), gevonden,
                        "enum-waarden van '" + e.getKey() + "' (" + enm.domein() + ") onvolledig/afwijkend");
            }
        }
    }

    @Test
    void elk_eenheid_dragend_attribuut_van_invoer_uitvoer_draagt_x_eenheid() {
        JsonNode schemas = DOC.get("components").get("schemas");
        java.util.Set<Objecttype> relevant = new java.util.LinkedHashSet<>();
        for (DienstDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints()) {
            for (String inv : ep.invoer() == null ? java.util.List.<String>of() : ep.invoer()) {
                Objecttype ot = DescriptorLoader.objecttype(MODEL, inv);
                if (ot != null) relevant.add(ot);
            }
            for (String sel : ep.uitvoer() == null ? java.util.List.<String>of() : ep.uitvoer()) {
                Uitvoerselector u = Uitvoerselector.ontleed(sel);
                if (u == null) continue;
                Objecttype ot = DescriptorLoader.objecttype(MODEL, u.objecttype());
                if (ot != null) relevant.add(ot);
            }
        }
        for (Objecttype ot : relevant) {
            JsonNode props = schemas.get(component(ot)).get("properties");
            for (var e : ot.attributen.entrySet()) {
                Datatype dt = e.getValue();
                var eenheid = dt.eenheidOf();
                if (eenheid == null) continue;
                if (!ot.dimensiesVan(e.getKey()).isEmpty()) continue;
                if (ot.attribuutGranulariteit.containsKey(e.getKey())
                        || ot.attribuutTijdlijnAantal.containsKey(e.getKey())) continue;
                // A bare Percentage's implicit '%' unit still documents as x-eenheid "%".
                JsonNode prop = eigenschap(props, e.getKey());
                assertNotNull(prop, "geen eigenschap voor eenheid-dragend attribuut '" + e.getKey() + "'");
                assertTrue(prop.has("x-eenheid"),
                        "geen x-eenheid op eenheid-dragend attribuut '" + e.getKey() + "' van '" + ot.naam + "'");
                assertEquals(eenheid.weergave(), prop.get("x-eenheid").asText(),
                        "x-eenheid van '" + e.getKey() + "' wijkt af");
            }
        }
    }

    // ------------------------------------------------------------------ Test 3: determinism

    @Test
    void document_is_deterministisch() {
        byte[] a = OpenApiEmitter.naarBytes(OpenApiEmitter.emit(DESCRIPTOR, MODEL));
        byte[] b = OpenApiEmitter.naarBytes(OpenApiEmitter.emit(DESCRIPTOR, MODEL));
        assertTrue(Arrays.equals(a, b), "twee emissies moeten byte-identiek zijn");
        assertTrue(Arrays.equals(BYTES, a), "de @BeforeAll-emissie moet gelijk zijn aan een verse emissie");
    }

    @Test
    void openapi_kop_en_info_kloppen() {
        assertEquals("3.0.3", DOC.get("openapi").asText());
        assertEquals("TOKA belastingdienst", DOC.get("info").get("title").asText());
        assertEquals("2.3.0", DOC.get("info").get("version").asText());
        // no double leaks into the document (nothing numeric is a schema value, but assert the invariant anyway)
        assertFalse(bevatDouble(DOC), "geen enkele waarde mag een double zijn");
    }

    // ------------------------------------------------------------------ helpers

    private static String component(Objecttype ot) {
        return dev.uchar.regelspraak.transpiler.kern.Namen.ident(ot.naam, true);
    }

    /** A property lookup tolerant of the resolver's case-insensitive attribuut/kenmerk naming. */
    private static boolean heeftEigenschap(JsonNode props, String naam) {
        return eigenschap(props, naam) != null;
    }

    private static JsonNode eigenschap(JsonNode props, String naam) {
        if (props.has(naam)) return props.get(naam);
        var it = props.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equalsIgnoreCase(naam)) return props.get(k);
        }
        return null;
    }

    private static boolean bevatDouble(JsonNode n) {
        if (n.isDouble() || n.isFloat()) return true;
        for (JsonNode c : n) if (bevatDouble(c)) return true;
        return false;
    }
}
