package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;

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
     *  {@code status} EVALUATION_FAULT, the sentence {@code error}, and {@code faults}, each with the {@code code} of its
     *  fault kind (EF-###, nullable), its {@code params} (an object of strings) and uchar's sentence {@code error}; and,
     *  not required, the detail a request may ask ({@code trace}, {@code coverage}, {@code fixpoint} or
     *  {@code fixpointError}). */
    @Test
    void the_422_describes_the_body_of_an_evaluation_fault() {
        for (ServiceDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints())
            assertEquals("#/components/schemas/EvaluationFault", DOC.get("paths").get("/" + ep.path())
                    .at("/post/responses/422/content/application~1json/schema/oneOf/0/$ref").asText(), "de 422 van '/" + ep.path() + "'");
        JsonNode s = DOC.at("/components/schemas/EvaluationFault");
        assertEquals("[\"status\",\"error\",\"faults\"]", s.path("required").toString(), s::toString);
        assertEquals(java.util.List.of("status", "error", "faults", "trace", "coverage", "fixpoint", "fixpointError"),
                fields(s.path("properties")), s::toString);
        assertEquals("[\"EVALUATION_FAULT\"]", s.at("/properties/status/enum").toString(), s::toString);
        assertEquals("string", s.at("/properties/error/type").asText(), s::toString);
        assertEquals("array", s.at("/properties/faults/type").asText(), s::toString);
        JsonNode error = s.at("/properties/faults/items");
        assertEquals("[\"code\",\"params\",\"error\"]", error.path("required").toString(), s::toString);
        assertEquals(java.util.List.of("code", "params", "error"), fields(error.path("properties")), s::toString);
        assertEquals("^EF-[0-9]{3}$", error.at("/properties/code/pattern").asText(), s::toString);
        assertTrue(error.at("/properties/code/nullable").asBoolean(), s::toString);
        assertEquals("string", error.at("/properties/params/additionalProperties/type").asText(), s::toString);
        assertEquals("string", error.at("/properties/error/type").asText(), s::toString);
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
                ObjectType ot = DescriptorLoader.objectType(MODEL, inv);
                assertNotNull(ot, "onbekend invoer-objecttype '" + inv + "'");
                assertTrue(schemas.has(component(ot)),
                        "geen schema voor invoer-objecttype '" + inv + "' (component '" + component(ot) + "')");
            }
            // every uitvoer selector's objectType.member has a schema property
            for (String sel : ep.output() == null ? java.util.List.<String>of() : ep.output()) {
                OutputSelector u = OutputSelector.parse(sel);
                assertNotNull(u, "onontleedbare uitvoer-selector '" + sel + "'");
                ObjectType ot = DescriptorLoader.objectType(MODEL, u.objectType());
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
        for (ObjectType ot : MODEL.objectTypes.values()) {
            JsonNode props = schemas.get(component(ot)).get("properties");
            for (var e : ot.attributes.entrySet()) {
                if (!(e.getValue() instanceof Datatype.Enumeration enm)) continue;
                if (!ot.dimensionsOf(e.getKey()).isEmpty()) continue;   // dimensie: the enum is on the value of a cell
                JsonNode prop = property(props, e.getKey());
                assertNotNull(prop, "geen eigenschap voor enumeratie-attribuut '" + e.getKey() + "'");
                JsonNode enumN = prop.get("enum");
                assertNotNull(enumN, "geen enum-lijst op '" + e.getKey() + "'");
                java.util.List<String> found = new java.util.ArrayList<>();
                enumN.forEach(w -> found.add(w.asText()));
                assertEquals(enm.values(), found,
                        "enum-waarden van '" + e.getKey() + "' (" + enm.domain() + ") onvolledig/afwijkend");
            }
        }
    }

    @Test
    void each_unit_carrying_attribute_of_input_output_carries_x_unit() {
        JsonNode schemas = DOC.get("components").get("schemas");
        java.util.Set<ObjectType> relevant = new java.util.LinkedHashSet<>();
        for (ServiceDescriptor.Entrypoint ep : DESCRIPTOR.entrypoints()) {
            for (String inv : ep.input() == null ? java.util.List.<String>of() : ep.input()) {
                ObjectType ot = DescriptorLoader.objectType(MODEL, inv);
                if (ot != null) relevant.add(ot);
            }
            for (String sel : ep.output() == null ? java.util.List.<String>of() : ep.output()) {
                OutputSelector u = OutputSelector.parse(sel);
                if (u == null) continue;
                ObjectType ot = DescriptorLoader.objectType(MODEL, u.objectType());
                if (ot != null) relevant.add(ot);
            }
        }
        for (ObjectType ot : relevant) {
            JsonNode props = schemas.get(component(ot)).get("properties");
            for (var e : ot.attributes.entrySet()) {
                Datatype dt = e.getValue();
                var unit = dt.unitOf();
                if (unit == null) continue;
                if (!ot.dimensionsOf(e.getKey()).isEmpty()) continue;
                if (ot.attributeGranularity.containsKey(e.getKey())
                        || ot.attributeTimelineCount.containsKey(e.getKey())) continue;
                // A bare Percentage's implicit '%' unit still documents as x-eenheid "%".
                JsonNode prop = property(props, e.getKey());
                assertNotNull(prop, "geen eigenschap voor eenheid-dragend attribuut '" + e.getKey() + "'");
                assertTrue(prop.has("x-unit"),
                        "geen x-eenheid op eenheid-dragend attribuut '" + e.getKey() + "' van '" + ot.name + "'");
                assertEquals(unit.rendering(), prop.get("x-unit").asText(),
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

    /** A dimensioned attribute is a property of its cells, as the service reads it in a request and gives it in an
     *  answer: {@code cells}, each with one label per dimension and a value of the attribute's datatype; uchar's golden
     *  {@code jutterswet-strandseizoen} has one dimension and one of two. */
    @Test
    void a_dimensioned_attribute_is_a_property_of_its_cells() throws IOException {
        RegelSpraakModel model = dev.uchar.regelspraak.resolver.Resolver.resolve(Files.readString(
                TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-strandseizoen/source.rgs")));
        assertEquals(java.util.List.of(), model.errors());
        ServiceDescriptor descriptor = DescriptorLoader.MAPPER.readValue("""
                { "service": {"name":"strandseizoen","version":"1"},
                  "entrypoints": [ { "name":"seizoen","path":"seizoen",
                    "scope": {"ruleNames": ["jaargewicht van een jutter"]},
                    "input": ["Jutter"], "output": ["Jutter.vondstgewicht"] } ],
                  "parameters": {} }""", ServiceDescriptor.class);
        JsonNode props = OpenApiEmitter.emit(descriptor, model).at("/components/schemas/Jutter/properties");
        for (String[] attribute : new String[][]{{"vondstgewicht", "2"}, {"aanvoer", "2"}}) {
            JsonNode property = property(props, attribute[0]);
            assertNotNull(property, props::toString);
            assertEquals("[\"cells\"]", property.get("required").toString(), property::toString);
            JsonNode cell = property.at("/properties/cells/items");
            assertEquals("[\"labels\",\"value\"]", cell.get("required").toString(), cell::toString);
            assertEquals(Integer.parseInt(attribute[1]), cell.at("/properties/labels/minItems").asInt(), cell::toString);
            assertEquals(Integer.parseInt(attribute[1]), cell.at("/properties/labels/maxItems").asInt(), cell::toString);
            assertEquals("number", cell.at("/properties/value/type").asText(), cell::toString);
            assertEquals("kg", cell.at("/properties/value/x-unit").asText(), cell::toString);
            assertTrue(cell.at("/properties/value/nullable").asBoolean(), cell::toString);
        }
        assertTrue(property(props, "vondstgewicht").get("description").asText()
                .startsWith("gedimensioneerd met seizoen (voorjaar, zomer, najaar), strand (noord, zuid): "), props::toString);
    }

    // ------------------------------------------------------------------ helpers

    private static String component(ObjectType ot) {
        return dev.uchar.regelspraak.transpiler.core.Namen.ident(ot.name, true);
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
