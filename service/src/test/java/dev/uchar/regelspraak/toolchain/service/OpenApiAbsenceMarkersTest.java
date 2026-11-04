package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The OpenAPI contract of an attribute with absence markers (uchar's RUNTIME-SEMANTICS ch. 9.5), on uchar's golden
 * jutterswet-afwezigheidsmarkers: its value is {@code oneOf} the schema of its datatype and the marker object
 * {@code {"marker": …}}, whose {@code marker} is an {@code enum} of the markers the attribute declares; on a timeline
 * that is the value of each segment. The model of the source and the model of the golden's {@code ir.json}, where the
 * attribute is uchar's sum type, give the same contract.
 */
class OpenApiAbsenceMarkersTest {

    private static final Path GOLDEN = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-afwezigheidsmarkers");

    private static final String DESCRIPTOR = """
        { "service": {"name":"markers","version":"1"},
          "entrypoints": [ { "name":"markers","path":"markers",
            "scope": {"ruleNames": ["taxatie uit schatting"]},
            "input": ["Vondst"], "output": ["Vondst.taxatie"] } ],
          "parameters": {} }""";

    private static final String MARKER = """
        {"type":"object","required":["marker"],"additionalProperties":false,
         "properties":{"marker":{"type":"string","enum":["onbekend","niet van toepassing"]}}}""";

    @Test
    void an_attribute_with_markers_is_one_of_its_datatype_and_the_marker_object() throws Exception {
        JsonNode vondst = vondst(Resolver.resolve(Files.readString(GOLDEN.resolve("source.rgs"))));
        JsonNode taxatie = vondst.at("/properties/taxatie");
        assertEquals(2, taxatie.at("/oneOf").size(), taxatie.toString());
        assertEquals("number", taxatie.at("/oneOf/0/type").asText(), taxatie.toString());
        assertEquals(DescriptorLoader.MAPPER.readTree(MARKER), taxatie.at("/oneOf/1"), taxatie.toString());
        JsonNode segment = vondst.at("/properties/opslagkosten/properties/timeline/items/properties/value");
        assertEquals("integer", segment.at("/oneOf/0/type").asText(), segment.toString());
        assertEquals(DescriptorLoader.MAPPER.readTree(MARKER), segment.at("/oneOf/1"), segment.toString());
        assertFalse(vondst.at("/properties/schatting").has("oneOf"), vondst.toString());
    }

    @Test
    void the_model_of_the_document_gives_the_same_contract() throws Exception {
        RegelSpraakModel source = Resolver.resolve(Files.readString(GOLDEN.resolve("source.rgs")));
        RegelSpraakModel document = IrLoader.loadAll(DescriptorLoader.MAPPER.readTree(
                Files.readString(GOLDEN.resolve("ir.json")))).model();
        assertEquals(vondst(source).at("/properties/taxatie"), vondst(document).at("/properties/taxatie"));
        assertEquals(vondst(source).at("/properties/opslagkosten"), vondst(document).at("/properties/opslagkosten"));
    }

    private static JsonNode vondst(RegelSpraakModel model) throws Exception {
        ServiceDescriptor descriptor = DescriptorLoader.MAPPER.readValue(DESCRIPTOR, ServiceDescriptor.class);
        return OpenApiEmitter.emit(descriptor, model).at("/components/schemas/Vondst");
    }
}
