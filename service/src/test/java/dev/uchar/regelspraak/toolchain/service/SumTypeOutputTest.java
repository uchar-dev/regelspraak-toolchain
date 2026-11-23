package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An attribute of a sum type, which only a {@code uchar-ir-v1} document of another producer declares, has no form in the
 * answer of the service: with it in {@code output} the service does not start, and the OpenAPI contract has no form for
 * it. The document is that of a Jutter with a meldtermijn and its rule, to which the meldpositie of uchar's golden
 * jutterswet-meldplichtspoor is added with its sum type {@code meldstatus}; the golden itself has a parameter of a list
 * type, which uchar's loader refuses.
 */
class SumTypeOutputTest {

    private static final Path GOLDEN = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-meldplichtspoor");

    private static final String DESCRIPTOR = """
        { "service": {"name":"meldplicht","version":"1"},
          "entrypoints": [ { "name":"meldplicht","path":"meldplicht", "scope": {"ruleNames": ["meldtermijn"]},
            "input": [], "output": ["Jutter.meldpositie"] } ],
          "parameters": {} }""";

    @Test
    void a_sum_type_in_output_refuses_the_start_and_has_no_form() throws Exception {
        ObjectNode tree = (ObjectNode) DescriptorLoader.MAPPER.readTree(UcharBuild.document(Resolver.resolve("""
            Objecttype de Jutter (mv: Jutters) (bezield)
                de meldtermijn Numeriek (geheel getal);

            Regel meldtermijn
                geldig altijd
                    De meldtermijn van een Jutter moet gesteld worden op 3.
            """)));
        JsonNode golden = DescriptorLoader.MAPPER.readTree(Files.readString(GOLDEN.resolve("ir.json")));
        ((ArrayNode) tree.at("/model/objectTypes/0/attributes")).add(golden.at("/model/objectTypes/0/attributes/0"));
        ((ObjectNode) tree.get("model")).putArray("domains").add(golden.at("/model/domains/0"));
        String document = tree.toString();
        RegelSpraakModel model = IrLoader.loadAll(tree).model();
        assertTrue(model.objectTypeByName("Jutter").attribute("meldpositie") instanceof Datatype.SumType);

        ServiceError e = assertThrows(ServiceError.class, () -> DecisionCore.buildFromDocument(DESCRIPTOR, document,
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null));
        Violation v = e.violations().get(0);
        assertEquals(Violation.Kind.UNSUPPORTED, v.kind(), e.violations().toString());
        assertEquals("entrypoints[0].output[0]", v.field(), e.violations().toString());
        assertTrue(v.message().contains("sum type 'meldstatus'"), v.message());

        ServiceDescriptor descriptor = DescriptorLoader.MAPPER.readValue(DESCRIPTOR, ServiceDescriptor.class);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> OpenApiEmitter.emit(descriptor, model));
        assertTrue(refused.getMessage().contains("'meldstatus'"), refused.getMessage());
    }
}
