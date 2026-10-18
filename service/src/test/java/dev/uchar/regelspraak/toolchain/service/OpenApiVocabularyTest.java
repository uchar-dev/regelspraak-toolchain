package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.toolchain.engine.Naming;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The OpenAPI document per vocabulary ({@link OpenApiEmitter#emit(ServiceDescriptor, RegelSpraakModel, String)}) over
 * uchar's golden {@code jutterswet-vocabulaires}: the canonical document and those of {@code handboek} and {@code cgm}
 * are byte-pinned against goldens under {@code src/test/resources/openapi/} and written to {@code target/openapi/},
 * each property, value and entry point lists its other spellings, and a model without vocabularies gets none of the
 * extension (the TOKA golden of {@link OpenApiEmitterTest} pins that byte for byte; here an own model without
 * vocabularies is searched for any trace of it).
 */
class OpenApiVocabularyTest {

    private static final Path GOLDENS = Path.of("src/test/resources/openapi");
    private static ServiceDescriptor DESCRIPTOR;
    private static RegelSpraakModel MODEL;

    @BeforeAll
    static void assemble() throws IOException {
        MODEL = VocabularySource.model();
        DESCRIPTOR = DescriptorLoader.MAPPER.readValue(VocabularySource.descriptorJson(), ServiceDescriptor.class);
        for (String v : new String[]{null, "handboek", "cgm"})
            OpenApiEmitter.write(DESCRIPTOR, MODEL, v, Path.of("target", "openapi", OpenApiEmitter.fileName(DESCRIPTOR, v)));
    }

    private static void byteEqual(String vocabulary) throws IOException {
        String name = OpenApiEmitter.fileName(DESCRIPTOR, vocabulary).replace(".json", ".golden.json");
        byte[] expected = Files.readAllBytes(GOLDENS.resolve(name));
        byte[] actual = OpenApiEmitter.toBytes(OpenApiEmitter.emit(DESCRIPTOR, MODEL, vocabulary));
        if (Arrays.equals(expected, actual)) return;
        String v = new String(expected, StandardCharsets.UTF_8), w = new String(actual, StandardCharsets.UTF_8);
        int i = 0;
        while (i < Math.min(v.length(), w.length()) && v.charAt(i) == w.charAt(i)) i++;
        fail(name + " wijkt af op teken " + i + ": golden '" + v.substring(i, Math.min(v.length(), i + 120))
                + "', emitted '" + w.substring(i, Math.min(w.length(), i + 120)) + "'; is dit een bewuste wijziging,"
                + " schrijf de golden dan opnieuw uit target/openapi/");
    }

    @Test
    void the_canonical_document_is_byte_equal_to_the_golden() throws IOException { byteEqual(null); }

    @Test
    void the_document_of_the_handbook_is_byte_equal_to_the_golden() throws IOException { byteEqual("handboek"); }

    @Test
    void the_document_of_cgm_is_byte_equal_to_the_golden() throws IOException { byteEqual("cgm"); }

    @Test
    void every_property_value_and_entrypoint_names_its_other_spellings() {
        ObjectNode doc = OpenApiEmitter.emit(DESCRIPTOR, MODEL, "cgm");
        assertEquals("cgm", doc.at("/info/x-vocabulary").asText());
        JsonNode vondst = doc.at("/components/schemas/Vondst");
        JsonNode kind = vondst.at("/properties/VONDST_SOORT");
        assertEquals("[\"WRAKHOUT\",\"BARNSTEEN\",\"FLESSENPOST\"]", kind.get("enum").toString());
        assertEquals("soort", kind.at("/x-spellings/canonical").asText());
        assertEquals("soort vondst", kind.at("/x-spellings/handboek").asText());
        assertFalse(kind.get("x-spellings").has("cgm"), "het eigen vocabulaire is geen andere spelling");
        assertEquals("amber", kind.at("/x-valueSpellings/BARNSTEEN/handboek").asText());
        assertEquals("barnsteen", kind.at("/x-valueSpellings/BARNSTEEN/canonical").asText());
        assertEquals("De dienst neemt ook 'soort' (canonical) en 'soort vondst' (handboek) aan.",
                kind.get("description").asText());
        assertEquals("aangespoeld voorwerp", vondst.at("/x-spellings/handboek").asText());
        JsonNode post = doc.at("/paths/~1premie/post");
        assertEquals("[\"Jutter\",\"Vondst\"]", post.at("/x-spellings/canonical/input").toString());
        assertEquals("strandjutter:komt in aanmerking voor premie", post.at("/x-spellings/handboek/output/5").asText());
        assertEquals("vocabulary", post.at("/parameters/0/name").asText());
        assertEquals("[\"canonical\",\"handboek\",\"cgm\"]", post.at("/parameters/1/schema/enum").toString());
        assertTrue(doc.at("/paths/~1premie-cgm/post/parameters/0/description").asText().contains("'cgm', de standaard"),
                doc.at("/paths/~1premie-cgm/post/parameters/0/description").asText());

        ObjectNode canonical = OpenApiEmitter.emit(DESCRIPTOR, MODEL, "canonical");
        assertEquals(OpenApiEmitter.emit(DESCRIPTOR, MODEL), canonical);
        assertEquals("VONDST_SOORT", canonical.at("/components/schemas/Vondst/properties/soort/x-spellings/cgm").asText());
    }

    @Test
    void a_model_without_vocabularies_gets_no_extension() throws IOException {
        ServiceDescriptor d = DescriptorLoader.MAPPER.readValue(ChainSource.descriptorJson(), ServiceDescriptor.class);
        String doc = new String(OpenApiEmitter.toBytes(OpenApiEmitter.emit(d, ChainSource.model())), StandardCharsets.UTF_8);
        for (String trace : new String[]{"x-spellings", "x-valueSpellings", "x-vocabulary", "inputVocabulary"})
            assertFalse(doc.contains(trace), "'" + trace + "' in een document zonder vocabulaires");
        assertThrows(Naming.VocabularyError.class, () -> OpenApiEmitter.emit(d, ChainSource.model(), "cgm"));
        assertThrows(Naming.VocabularyError.class, () -> OpenApiEmitter.emit(DESCRIPTOR, MODEL, "wetboek"));
    }
}
