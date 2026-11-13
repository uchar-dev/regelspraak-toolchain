package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        assertEquals(24, d.entrypoints().size(), "de gepinde TOKA-descriptor hoort 24 entrypoints te hebben "
                + "— de 29 exit-0 golden scenariotoetsen zonder de vijf verdelingen naar 'treinmiles' die het "
                + "gediende model weglaat (toka-33 tot en met toka-37, WF-026, zie TokaSource), inclusief toka-29 "
                + "'passagier met recht op "
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

    /** A key the descriptor form does not have, at any level, refuses the start with its path: an entry point with
     *  {@code outputs} for {@code output} would otherwise start and answer nothing. */
    @Test
    void an_unknown_key_refuses_the_start_with_its_path() {
        String template = """
            { %s"service": {"name":"t","version":"1"%s},
              "entrypoints": [ { %s"name":"x","path":"x",
                "scope": {"ruleNames": ["bepaal leeftijd"]%s}, "input": [], "output": [] } ] }""";
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put(template.formatted("\"dienst\": {}, ", "", "", ""), "dienst");
        cases.put(template.formatted("", ", \"versie\": \"1\"", "", ""), "service.versie");
        cases.put(template.formatted("", "", "\"outputs\": [], ", ""), "entrypoints[0].outputs");
        cases.put(template.formatted("", "", "", ", \"regelnamen\": []"), "entrypoints[0].scope.regelnamen");
        cases.forEach((json, field) -> {
            ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load(json, MODEL), json);
            assertEquals(List.of(field), f.violations().stream().map(Violation::field).toList(), f.getMessage());
            assertEquals(Violation.Kind.MALFORMED, f.violations().get(0).kind(), f.getMessage());
        });
    }

    /** A path is one segment of the URL that no other route has: not twice, not {@code info}, {@code openapi} or
     *  {@code playground}, no slash. */
    @Test
    void a_path_twice_a_reserved_path_and_a_slash_refuse_the_start() {
        String json = """
            { "service": {"name":"t","version":"1"},
              "entrypoints": [
                { "name":"a","path":"x", "scope": {"ruleNames": ["bepaal leeftijd"]}, "input": [], "output": [] },
                { "name":"b","path":"x", "scope": {"ruleNames": ["bepaal leeftijd"]}, "input": [], "output": [] },
                { "name":"c","path":"info", "scope": {"ruleNames": ["bepaal leeftijd"]}, "input": [], "output": [] },
                { "name":"d","path":"playground", "scope": {"ruleNames": ["bepaal leeftijd"]}, "input": [], "output": [] },
                { "name":"e","path":"/y", "scope": {"ruleNames": ["bepaal leeftijd"]}, "input": [], "output": [] },
                { "name":"f","path":"openapi", "scope": {"ruleNames": ["bepaal leeftijd"]}, "input": [], "output": [] } ] }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load(json, MODEL));
        assertEquals(List.of("entrypoints[1].path", "entrypoints[2].path", "entrypoints[3].path", "entrypoints[4].path",
                        "entrypoints[5].path"),
                f.violations().stream().map(Violation::field).toList(), f.getMessage());
        assertTrue(f.violations().stream().allMatch(v -> v.kind() == Violation.Kind.MALFORMED), f.getMessage());
        // the route of the service itself, refused with the one sentence that names every such route
        assertEquals(f.violations().get(1).message().replace("'info'", "'openapi'"), f.violations().get(4).message(),
                f.getMessage());
    }

    @Test
    void malformed_json_gives_MALFORMED_violation() {
        ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load("{ niet echt json", MODEL));
        assertEquals(Violation.Kind.MALFORMED, f.violations().get(0).kind());
    }
}
