package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request → beans mapping and its typed violations (decision 4). The mapper is driven directly (no engine)
 * so a violation is observed as a {@link Violation} carrying its field path, exactly what fase 3 turns into a
 * 400 naming the field.
 */
class RequestMapperTest {

    private static RegelSpraakModel MODEL;
    private static RequestMapper MAPPER;

    @BeforeAll
    static void assemble() {
        StringBuilder source = new StringBuilder();
        for (String text : TokaSource.sourcePerFile().values()) source.append(text).append('\n');
        MODEL = Resolver.resolve(source.toString());
        MAPPER = new RequestMapper(MODEL, TokaSource.build());
    }

    private static RequestMapper.Result map(String json) {
        return MAPPER.map(new Request(parse(json)));
    }

    @Test
    void valid_request_maps_without_violation() {
        var r = map("""
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "geboortedatum": "1990-06-15" }, "roles": { "reis": "V1" } } ] }""");
        assertTrue(r.violations().isEmpty(), "onverwachte schendingen: " + r.violations());
        assertEquals(2, r.world().beans().size());
    }

    @Test
    void unknown_attribute_names_the_field() {
        var r = map("""
            { "instances": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributes": { "schoenmaat": 42 } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.UNKNOWN_FIELD
                && s.field().equals("instances[0].attributes.schoenmaat")
                && s.message().contains("schoenmaat")), r.violations().toString());
    }

    @Test
    void unknown_object_type_names_the_field() {
        var r = map("""
            { "instances": [ { "type": "Ruimteschip", "id": "R1" } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.UNKNOWN_FIELD && s.field().equals("instances[0].type")), r.violations().toString());
    }

    @Test
    void wrong_datatype_is_reported() {
        // geboortedatum is a Datum -> a number is ill-typed
        var r = map("""
            { "instances": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributes": { "geboortedatum": 12345 } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.WRONG_TYPE
                && s.field().equals("instances[0].attributes.geboortedatum")), r.violations().toString());
    }

    @Test
    void unit_bearing_number_accepts_object_with_correct_unit() {
        // afstand tot bestemming: Numeriek (geheel getal) met eenheid km
        var r = map("""
            { "instances": [ { "type": "Vlucht", "id": "V1",
                "attributes": { "afstand tot bestemming": { "value": 200, "unit": "km" } } } ] }""");
        assertTrue(r.violations().isEmpty(), "onverwacht: " + r.violations());
    }

    @Test
    void unit_mismatch_is_reported() {
        var r = map("""
            { "instances": [ { "type": "Vlucht", "id": "V1",
                "attributes": { "afstand tot bestemming": { "value": 200, "unit": "mijl" } } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.WRONG_UNIT
                && s.field().equals("instances[0].attributes.afstand tot bestemming")
                && s.message().contains("km")), r.violations().toString());
    }

    @Test
    void plain_number_on_unit_attribute_may() {
        var r = map("""
            { "instances": [ { "type": "Vlucht", "id": "V1",
                "attributes": { "afstand tot bestemming": 200 } } ] }""");
        assertTrue(r.violations().isEmpty(), "onverwacht: " + r.violations());
    }

    @Test
    void unknown_enumeration_value_is_reported() {
        // luchthaven van vertrek is a Luchthavens enumeratie
        var r = map("""
            { "instances": [ { "type": "Vlucht", "id": "V1",
                "attributes": { "luchthaven van vertrek": "Mars Base One" } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.UNKNOWN_ENUM_VALUE
                && s.field().equals("instances[0].attributes.luchthaven van vertrek")), r.violations().toString());
    }

    @Test
    void valid_enumeration_value_maps() {
        var r = map("""
            { "instances": [ { "type": "Vlucht", "id": "V1",
                "attributes": { "luchthaven van vertrek": "Amsterdam Schiphol" } } ] }""");
        assertTrue(r.violations().isEmpty(), "onverwacht: " + r.violations());
    }

    @Test
    void unknown_characteristic_is_reported() {
        var r = map("""
            { "instances": [ { "type": "Natuurlijk persoon", "id": "P1",
                "characteristics": { "onsterfelijk": true } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.UNKNOWN_FIELD
                && s.field().equals("instances[0].characteristics.onsterfelijk")), r.violations().toString());
    }

    @Test
    void unknown_role_is_reported() {
        var r = map("""
            { "instances": [ { "type": "Natuurlijk persoon", "id": "P1",
                "roles": { "ruimteschip": "X" } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.UNKNOWN_FIELD
                && s.field().contains("roles.ruimteschip")), r.violations().toString());
    }

    @Test
    void unknown_parameter_in_request_is_reported() {
        var r = map("""
            { "parameters": { "tarief op pluto": 5 },
              "instances": [ { "type": "Vlucht", "id": "V1" } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.UNKNOWN_FIELD
                && s.field().equals("parameters.tarief op pluto")), r.violations().toString());
    }

    @Test
    void valid_parameter_is_bound_by_uchars_input_step() {
        // the mapper writes the parameter into the case; uchar's input step reads it and binds it on a session of a
        // build with a rule that reads it (a build declares the global of a parameter a rule reads)
        dev.uchar.regelspraak.cases.DroolsBackend.Build reading = dev.uchar.regelspraak.toolchain.engine.UcharBuild
                .assemble(dev.uchar.regelspraak.emit.CaseEmitter.withScope(MODEL,
                        new dev.uchar.regelspraak.ir.ResolvedScenario.Scope(
                                List.of("Kenmerktoekenning persoon minderjarig"))),
                        dev.uchar.regelspraak.cases.DroolsBackend.Branch.EXEC_MODEL);
        var r = new RequestMapper(MODEL, reading).map(new Request(parse("""
            { "parameters": { "volwassenleeftijd": 18 },
              "instances": [ { "type": "Vlucht", "id": "V1" } ] }""")));
        assertTrue(r.violations().isEmpty(), "onverwacht: " + r.violations());
        org.kie.api.runtime.KieSession session = reading.kieBase().newKieSession();
        try {
            r.world().input().setGlobals(session);
            Object bound = session.getGlobal("volwassenleeftijd");
            assertNotNull(bound, "uchar bindt de parameter niet");
            assertEquals(0, new BigDecimal("18").compareTo((BigDecimal) bound), String.valueOf(bound));
        } finally {
            session.dispose();
        }
    }

    @Test
    void evaluation_date_is_adopted() {
        var r = map("""
            { "rekendatum": "2024-01-01",
              "instances": [ { "type": "Vlucht", "id": "V1" } ] }""");
        assertTrue(r.violations().isEmpty());
        assertEquals(java.time.LocalDate.of(2024, 1, 1), r.world().evaluationDate());
    }

    @Test
    void json_number_becomes_bigdecimal_never_double() {
        var r = map("""
            { "instances": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributes": { "te betalen belasting": 30.00 } } ] }""");
        assertTrue(r.violations().isEmpty(), r.violations().toString());
        Object bean = r.world().beans().get("P1");
        Object v = dev.uchar.regelspraak.runtime.RegelSpraakRuntime.get(bean, "teBetalenBelasting");
        assertTrue(v instanceof BigDecimal, "waarde moet BigDecimal zijn, was " + (v == null ? "null" : v.getClass()));
    }

    /** A key the request form does not have is refused with its path, at the top, in an instance and in a relation:
     *  the service would otherwise answer as if it were not there. The old names are such keys. */
    @Test
    void an_unknown_key_is_malformed_with_its_path() {
        var r = map("""
            { "peildatum": "2024-06-15", "relaties": [],
              "instances": [ { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } } ],
              "relations": [] }""");
        assertEquals(List.of("peildatum", "relaties", "instances[0].attributen"),
                r.violations().stream().map(Violation::field).toList(), r.violations().toString());
        assertTrue(r.violations().stream().allMatch(v -> v.kind() == Violation.Kind.MALFORMED), r.violations().toString());
    }

    /** A number is written into the case in its lexeme, digits with at most one point: a number with an exponent in its
     *  text, or one whose value has a negative scale (a JSON number such as 1E+3), is refused, and so is one whose
     *  digits written out exceed what the parser reads of a number; 12.5 written as a JSON number 1.25E1 is 12.5. */
    @Test
    void a_number_with_an_exponent_is_wrong_type() {
        for (String value : List.of("\"1.25E1\"", "1E+3", "1E-20000000")) {
            var r = map("""
                { "instances": [ { "type": "Natuurlijk persoon", "id": "P1",
                    "attributes": { "te betalen belasting": %s } } ] }""".formatted(value));
            assertEquals(List.of("instances[0].attributes.te betalen belasting"),
                    r.violations().stream().map(Violation::field).toList(), value + ": " + r.violations());
            assertEquals(Violation.Kind.WRONG_TYPE, r.violations().get(0).kind(), r.violations().toString());
        }
        var r = map("""
            { "instances": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributes": { "te betalen belasting": 3.000E1 } } ] }""");
        assertTrue(r.violations().isEmpty(), r.violations().toString());
        assertEquals(new BigDecimal("30.00"), dev.uchar.regelspraak.runtime.RegelSpraakRuntime.get(
                r.world().beans().get("P1"), "teBetalenBelasting"));
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
