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
 *   <li>WRITTEN at build time to {@code target/openapi/<kebab(service.name)>.json} (the "write-at-build" step —
 *       done here inside the test so it needs no extra Maven plugin, keeping the build offline);</li>
 *   <li>byte-pinned against {@code src/test/resources/openapi/toka-belastingdienst.golden.json} (a mismatch
 *       fails with a compact first-difference report);</li>
 *   <li>structurally linted — assertions OVER the generated document ARE the lint (no external tool):
 *       every entrypoint has a path; every invoer objecttype and every uitvoer objectType.attribute has a
 *       schema property; every Enumeratie lists ALL its waarden; every eenheid-bearing attribuut of an
 *       invoer/uitvoer objecttype carries {@code x-eenheid};</li>
 *   <li>deterministic (emit twice → byte-equal).</li>
 * </ol>
 */
class OpenApiEmitterTest {

    private static ServiceDescriptor DESCRIPTOR;
    private static RegelSpraakModel MODEL;
    private static ObjectNode DOC;
    private static byte[] BYTES;

    private static final Path GOLDEN =
            Path.of("src/test/resources/openapi/toka-belastingdienst.golden.json");

    @BeforeAll
    static void assemble() throws IOException {
        MODEL = TokaSource.model();
        DESCRIPTOR = DescriptorLoader.MAPPER.readValue(TokaSource.descriptorJson(), ServiceDescriptor.class);
        DOC = OpenApiEmitter.emit(DESCRIPTOR, MODEL);
        BYTES = OpenApiEmitter.toBytes(DOC);

        // write-at-build: the document lands at target/openapi/<kebab(service.name)>.json during `mvn test`.
        Path target = Path.of("target", "openapi", OpenApiEmitter.fileName(DESCRIPTOR));
        OpenApiEmitter.write(DESCRIPTOR, MODEL, target);
        assertTrue(Files.exists(target), "het gegenereerde document hoort op " + target.toAbsolutePath() + " te staan");
    }

    // ------------------------------------------------------------------ Test 1: byte-pin

    @Test
    void document_is_byte_identical_to_golden() throws IOException {
        assertTrue(Files.exists(GOLDEN), "golden ontbreekt: " + GOLDEN.toAbsolutePath());
        byte[] expected = Files.readAllBytes(GOLDEN);
        if (!Arrays.equals(expected, BYTES)) fail(firstDifference(expected, BYTES));
    }

    /** A compact first-difference report: the byte offset, the surrounding line, and a caret. */
    private static String firstDifference(byte[] expected, byte[] actual) {
        int n = Math.min(expected.length, actual.length);
        int i = 0;
        while (i < n && expected[i] == actual[i]) i++;
        String v = new String(expected, StandardCharsets.UTF_8);
        String w = new String(actual, StandardCharsets.UTF_8);
        int line = 1, kolStart = 0;
        for (int k = 0; k < i && k < v.length(); k++) {
            if (v.charAt(k) == '\n') { line++; kolStart = k + 1; }
        }
        int kol = i - kolStart + 1;
        String vContext = lineAt(v, i);
        String wContext = lineAt(w, i);
        return "OpenAPI-document wijkt af van de golden op byte-offset " + i
                + " (regel " + line + ", kolom " + kol + ").\n"
                + "  golden : " + vContext + "\n"
                + "  emitted: " + wContext + "\n"
                + "  (golden=" + expected.length + " bytes, emitted=" + actual.length + " bytes) — "
                + "her-genereer de golden met OpenApiEmitter en commit die opnieuw als dit een bewuste wijziging is.";
    }

    private static String lineAt(String s, int offset) {
        if (offset >= s.length()) return "(einde van document — " + s.length() + " bytes)";
        int start = s.lastIndexOf('\n', offset) + 1;
        int end = s.indexOf('\n', offset);
        if (end < 0) end = s.length();
        return s.substring(start, Math.min(end, start + 200));
    }

    // ------------------------------------------------------------------ Test 2: structural lint

    @Test
    void every_entrypoint_has_a_path() {
        JsonNode paths = DOC.get("paths");
        assertNotNull(paths, "geen paths-object");
        for (ServiceDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints())
            assertTrue(paths.has("/" + ep.path()) && paths.get("/" + ep.path()).has("post"),
                    "geen POST-pad voor entrypoint '" + ep.name() + "' (pad '/" + ep.path() + "')");
        assertTrue(paths.has("/info") && paths.get("/info").has("get"), "geen GET /info-pad");
    }

    /** The 422 of every entry point names the body {@link AnswerBuilder#evaluationFault} builds, and no other fields:
     *  {@code status} EVALUATIEFOUT, the sentence {@code fout}, and {@code fouten}, each with its {@code soort}, one of
     *  the kinds of {@link EvaluationFault.Kind}, and uchar's sentence {@code fout}; and, not required, the detail a
     *  request may ask ({@code trace}, {@code dekking}, {@code fixpunt} or {@code fixpointError}). */
    @Test
    void the_422_describes_the_body_of_an_evaluation_fault() {
        for (ServiceDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints())
            assertEquals("#/components/schemas/Evaluatiefout", DOC.get("paths").get("/" + ep.path())
                    .at("/post/responses/422/content/application~1json/schema/oneOf/0/$ref").asText(), "de 422 van '/" + ep.path() + "'");
        JsonNode s = DOC.at("/components/schemas/Evaluatiefout");
        assertEquals("[\"status\",\"fout\",\"fouten\"]", s.path("required").toString(), s::toString);
        assertEquals(java.util.List.of("status", "fout", "fouten", "trace", "dekking", "fixpunt", "fixpuntFout"),
                fields(s.path("properties")), s::toString);
        assertEquals("[\"EVALUATIEFOUT\"]", s.at("/properties/status/enum").toString(), s::toString);
        assertEquals("string", s.at("/properties/fout/type").asText(), s::toString);
        assertEquals("array", s.at("/properties/fouten/type").asText(), s::toString);
        JsonNode error = s.at("/properties/fouten/items");
        assertEquals("[\"soort\",\"fout\"]", error.path("required").toString(), s::toString);
        assertEquals(java.util.List.of("soort", "fout"), fields(error.path("properties")), s::toString);
        assertEquals(Arrays.stream(EvaluationFault.Kind.values()).map(Enum::name).toList(),
                error.at("/properties/soort/enum").valueStream().map(JsonNode::asText).toList(), s::toString);
        assertEquals("string", error.at("/properties/fout/type").asText(), s::toString);
    }

    private static java.util.List<String> fields(JsonNode n) {
        java.util.List<String> sink = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(sink::add);
        return sink;
    }

    @Test
    void every_input_and_output_object_type_has_a_schema_property() {
        JsonNode schemas = DOC.get("components").get("schemas");
        for (ServiceDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints()) {
            // every invoer objecttype has a component schema
            for (String inv : ep.input() == null ? java.util.List.<String>of() : ep.input()) {
                Objecttype ot = DescriptorLoader.objectType(MODEL, inv);
                assertNotNull(ot, "onbekend invoer-objecttype '" + inv + "'");
                assertTrue(schemas.has(component(ot)),
                        "geen schema voor invoer-objecttype '" + inv + "' (component '" + component(ot) + "')");
            }
            // every uitvoer selector's objectType.member has a schema property
            for (String sel : ep.output() == null ? java.util.List.<String>of() : ep.output()) {
                OutputSelector u = OutputSelector.parse(sel);
                assertNotNull(u, "onontleedbare uitvoer-selector '" + sel + "'");
                Objecttype ot = DescriptorLoader.objectType(MODEL, u.objectType());
                assertNotNull(ot, "onbekend uitvoer-objecttype in '" + sel + "'");
                JsonNode props = schemas.get(component(ot)).get("properties");
                assertNotNull(props, "geen properties op schema '" + component(ot) + "'");
                assertTrue(hasProperty(props, u.member()),
                        "geen schema-eigenschap '" + u.member() + "' op '" + component(ot) + "' voor uitvoer '" + sel + "'");
            }
        }
    }

    @Test
    void every_enumeration_lists_all_its_values() {
        JsonNode schemas = DOC.get("components").get("schemas");
        for (Objecttype ot : MODEL.objecttypen.values()) {
            JsonNode props = schemas.get(component(ot)).get("properties");
            for (var e : ot.attributen.entrySet()) {
                if (!(e.getValue() instanceof Datatype.Enumeratie enm)) continue;
                if (!ot.dimensiesVan(e.getKey()).isEmpty()) continue;   // dimensie: not emitted (rejected)
                JsonNode prop = property(props, e.getKey());
                assertNotNull(prop, "geen eigenschap voor enumeratie-attribuut '" + e.getKey() + "'");
                JsonNode enumN = prop.get("enum");
                assertNotNull(enumN, "geen enum-lijst op '" + e.getKey() + "'");
                java.util.List<String> found = new java.util.ArrayList<>();
                enumN.forEach(w -> found.add(w.asText()));
                assertEquals(enm.waarden(), found,
                        "enum-waarden van '" + e.getKey() + "' (" + enm.domein() + ") onvolledig/afwijkend");
            }
        }
    }

    @Test
    void each_unit_carrying_attribute_of_input_output_carries_x_unit() {
        JsonNode schemas = DOC.get("components").get("schemas");
        java.util.Set<Objecttype> relevant = new java.util.LinkedHashSet<>();
        for (ServiceDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints()) {
            for (String inv : ep.input() == null ? java.util.List.<String>of() : ep.input()) {
                Objecttype ot = DescriptorLoader.objectType(MODEL, inv);
                if (ot != null) relevant.add(ot);
            }
            for (String sel : ep.output() == null ? java.util.List.<String>of() : ep.output()) {
                OutputSelector u = OutputSelector.parse(sel);
                if (u == null) continue;
                Objecttype ot = DescriptorLoader.objectType(MODEL, u.objectType());
                if (ot != null) relevant.add(ot);
            }
        }
        for (Objecttype ot : relevant) {
            JsonNode props = schemas.get(component(ot)).get("properties");
            for (var e : ot.attributen.entrySet()) {
                Datatype dt = e.getValue();
                var unit = dt.eenheidOf();
                if (unit == null) continue;
                if (!ot.dimensiesVan(e.getKey()).isEmpty()) continue;
                if (ot.attribuutGranulariteit.containsKey(e.getKey())
                        || ot.attribuutTijdlijnAantal.containsKey(e.getKey())) continue;
                // A bare Percentage's implicit '%' unit still documents as x-eenheid "%".
                JsonNode prop = property(props, e.getKey());
                assertNotNull(prop, "geen eigenschap voor eenheid-dragend attribuut '" + e.getKey() + "'");
                assertTrue(prop.has("x-eenheid"),
                        "geen x-eenheid op eenheid-dragend attribuut '" + e.getKey() + "' van '" + ot.naam + "'");
                assertEquals(unit.weergave(), prop.get("x-eenheid").asText(),
                        "x-eenheid van '" + e.getKey() + "' wijkt af");
            }
        }
    }

    // ------------------------------------------------------------------ Test 3: determinism

    @Test
    void document_is_deterministic() {
        byte[] a = OpenApiEmitter.toBytes(OpenApiEmitter.emit(DESCRIPTOR, MODEL));
        byte[] b = OpenApiEmitter.toBytes(OpenApiEmitter.emit(DESCRIPTOR, MODEL));
        assertTrue(Arrays.equals(a, b), "twee emissies moeten byte-identiek zijn");
        assertTrue(Arrays.equals(BYTES, a), "de @BeforeAll-emissie moet gelijk zijn aan een verse emissie");
    }

    @Test
    void openapi_head_and_info_match() {
        assertEquals("3.0.3", DOC.get("openapi").asText());
        assertEquals("TOKA belastingdienst", DOC.get("info").get("title").asText());
        assertEquals("2.3.0", DOC.get("info").get("version").asText());
        // no double leaks into the document (nothing numeric is a schema value, but assert the invariant anyway)
        assertFalse(containsDouble(DOC), "geen enkele waarde mag een double zijn");
    }

    // ------------------------------------------------------------------ helpers

    private static String component(Objecttype ot) {
        return dev.uchar.regelspraak.transpiler.kern.Namen.ident(ot.naam, true);
    }

    /** A property lookup tolerant of the resolver's case-insensitive attribuut/kenmerk naming. */
    private static boolean hasProperty(JsonNode props, String name) {
        return property(props, name) != null;
    }

    private static JsonNode property(JsonNode props, String name) {
        if (props.has(name)) return props.get(name);
        var it = props.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equalsIgnoreCase(name)) return props.get(k);
        }
        return null;
    }

    private static boolean containsDouble(JsonNode n) {
        if (n.isDouble() || n.isFloat()) return true;
        for (JsonNode c : n) if (containsDouble(c)) return true;
        return false;
    }
}
