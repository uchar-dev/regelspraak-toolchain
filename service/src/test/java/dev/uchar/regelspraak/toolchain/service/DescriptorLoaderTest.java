package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Descriptor parsing + validation against the resolved TOKA model (decision 1). */
class DescriptorLoaderTest {

    private static RegelSpraakModel MODEL;

    @BeforeAll
    static void resolve() {
        StringBuilder source = new StringBuilder();
        for (String text : TokaSource.sourcePerFile().values()) source.append(text).append('\n');
        MODEL = Resolver.resolve(source.toString());
        assertTrue(MODEL.errors().isEmpty(), "TOKA-bron hoort schoon te resolven: " + MODEL.errors());
    }

    @Test
    void toka_descriptor_validates_clean() {
        ServiceDescriptor d = DescriptorLoader.load(TokaSource.descriptorJson(), MODEL);
        assertEquals("TOKA belastingdienst", d.service().name());
        assertEquals(29, d.entrypoints().size(), "de gepinde TOKA-descriptor hoort 29 entrypoints te hebben "
                + "— precies de 29 exit-0 golden scenariotoetsen (30 tot besluit 16Y toka-45 liet vervallen, "
                + "zie TokaBron), inclusief toka-29 'passagier met recht op "
                + "treinmiles' (een §13.4.7 feitcreatie: nu benoembaar via de RAW model.regels, ook al levert "
                + "hij geen resolved-collectie-regel op)");
        // no violations
        assertTrue(DescriptorLoader.validate(d, MODEL).isEmpty());
    }

    @Test
    void unknown_rule_is_named() {
        String json = """
            { "service": {"name":"t","version":"1"},
              "entrypoints": [ { "name":"x","path":"x",
                "scope": {"ruleNames": ["deze regel bestaat niet"]},
                "input": [], "output": [] } ], "parameters": {} }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load(json, MODEL));
        assertTrue(f.getMessage().contains("deze regel bestaat niet"), f.getMessage());
        assertEquals(Violation.Kind.UNKNOWN_REFERENCE, f.violations().get(0).kind());
        assertTrue(f.violations().get(0).field().contains("scope.ruleNames[0]"));
    }

    @Test
    void unknown_object_type_in_input_is_named() {
        String json = """
            { "service": {"name":"t","version":"1"},
              "entrypoints": [ { "name":"x","path":"x",
                "scope": {"ruleNames": ["bepaal leeftijd"]},
                "input": ["Ruimteschip"], "output": [] } ], "parameters": {} }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load(json, MODEL));
        assertTrue(f.getMessage().contains("Ruimteschip"), f.getMessage());
        assertTrue(f.violations().stream().anyMatch(s -> s.field().equals("entrypoints[0].input[0]")));
    }

    @Test
    void unknown_attribute_in_output_is_named() {
        String json = """
            { "service": {"name":"t","version":"1"},
              "entrypoints": [ { "name":"x","path":"x",
                "scope": {"ruleNames": ["bepaal leeftijd"]},
                "input": [], "output": ["Natuurlijk persoon.schoenmaat"] } ], "parameters": {} }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load(json, MODEL));
        assertTrue(f.getMessage().contains("schoenmaat"), f.getMessage());
    }

    @Test
    void unknown_characteristic_in_output_is_named() {
        String json = """
            { "service": {"name":"t","version":"1"},
              "entrypoints": [ { "name":"x","path":"x",
                "scope": {"ruleNames": ["bepaal leeftijd"]},
                "input": [], "output": ["Natuurlijk persoon:onsterfelijk"] } ], "parameters": {} }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load(json, MODEL));
        assertTrue(f.getMessage().contains("onsterfelijk"), f.getMessage());
    }

    @Test
    void unknown_parameter_is_named() {
        String json = """
            { "service": {"name":"t","version":"1"},
              "entrypoints": [ { "name":"x","path":"x",
                "scope": {"ruleNames": ["bepaal leeftijd"]},
                "input": [], "output": [] } ],
              "parameters": { "belastingtarief van pluto": 5 } }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load(json, MODEL));
        assertTrue(f.getMessage().contains("belastingtarief van pluto"), f.getMessage());
        assertTrue(f.violations().stream().anyMatch(s -> s.field().equals("parameters.belastingtarief van pluto")));
    }

    @Test
    void all_violations_are_collected_not_one_by_one() throws Exception {
        String json = """
            { "service": {"name":"t","version":"1"},
              "entrypoints": [ { "name":"x","path":"x",
                "scope": {"ruleNames": ["nep regel"]},
                "input": ["Nep type"], "output": ["Nep type.nep attribuut"] } ],
              "parameters": { "nep param": 1 } }""";
        ServiceDescriptor d = DescriptorLoader.MAPPER.readValue(json, ServiceDescriptor.class);
        List<Violation> s = DescriptorLoader.validate(d, MODEL);
        // 4 distinct unknown references collected together (regel, invoer-type, uitvoer-type, parameter)
        assertTrue(s.size() >= 4, "verwacht >= 4 verzamelde schendingen, kreeg " + s);
        assertFalse(s.isEmpty());
    }

    @Test
    void malformed_json_gives_MALFORMED_violation() {
        ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load("{ niet echt json", MODEL));
        assertEquals(Violation.Kind.MALFORMED, f.violations().get(0).kind());
    }
}
