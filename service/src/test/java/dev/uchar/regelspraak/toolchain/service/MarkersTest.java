package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which attribute has absence markers ({@link Markers}), on uchar's golden jutterswet-afwezigheidsmarkers: in the model
 * of its source the markers stand beside the attribute, in the model of its {@code ir.json} the attribute holds uchar's
 * sum type; both give the payload datatype and the declared markers. A sum type of another shape, here the golden's
 * document with the constructor {@code waarde} named {@code bedrag}, is no attribute with markers: the service does not
 * start with it in {@code output}, and the OpenAPI contract has no form for it.
 */
class MarkersTest {

    private static final Path GOLDEN = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-afwezigheidsmarkers");

    private static final String DESCRIPTOR = """
        { "service": {"name":"markers","version":"1"},
          "entrypoints": [ { "name":"markers","path":"markers",
            "scope": {"ruleNames": ["taxatie uit schatting", "taxatie onbekend", "taxatie niet van toepassing",
                                    "vindloon naar taxatie", "forfaitair vindloon", "geen vindloon", "kostbare vondst",
                                    "getaxeerd totaal", "opslag onbekend in de winter",
                                    "opslag niet van toepassing na verkoop", "opslagtoeslag"]},
            "input": [], "output": ["Vondst.taxatie"] } ],
          "parameters": {} }""";

    private static final Datatype TWO_DECIMALS = new Datatype.Numeric(false, 2, Datatype.Sign.ONBEPAALD, null);

    @Test
    void the_source_and_the_document_give_the_same_markers() throws Exception {
        RegelSpraakModel source = Resolver.resolve(Files.readString(GOLDEN.resolve("source.rgs")));
        RegelSpraakModel document = IrLoader.loadAll(DescriptorLoader.MAPPER.readTree(
                Files.readString(GOLDEN.resolve("ir.json")))).model();
        for (RegelSpraakModel model : List.of(source, document)) {
            Markers markers = Markers.of(model.objectTypeByName("Vondst"), "taxatie");
            assertEquals(new Markers(TWO_DECIMALS, List.of("onbekend", "niet van toepassing")), markers);
            assertNull(Markers.of(model.objectTypeByName("Vondst"), "schatting"));
        }
    }

    @Test
    void another_sum_type_is_no_attribute_with_markers_and_has_no_form() throws Exception {
        String other = Files.readString(GOLDEN.resolve("ir.json")).replace("\"waarde\"", "\"bedrag\"");
        RegelSpraakModel model = IrLoader.loadAll(DescriptorLoader.MAPPER.readTree(other)).model();
        assertTrue(model.objectTypeByName("Vondst").attribute("taxatie") instanceof Datatype.SumType);
        assertNull(Markers.of(model.objectTypeByName("Vondst"), "taxatie"));

        ServiceError e = assertThrows(ServiceError.class, () -> DecisionCore.buildFromDocument(DESCRIPTOR, other,
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null));
        Violation v = e.violations().get(0);
        assertEquals(Violation.Kind.UNSUPPORTED, v.kind(), e.violations().toString());
        assertEquals("entrypoints[0].output[0]", v.field(), e.violations().toString());
        assertTrue(v.message().contains("taxatie van een Vondst"), v.message());

        ServiceDescriptor descriptor = DescriptorLoader.MAPPER.readValue(DESCRIPTOR, ServiceDescriptor.class);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> OpenApiEmitter.emit(descriptor, model));
        assertTrue(refused.getMessage().contains("taxatie van een Vondst"), refused.getMessage());
    }
}
