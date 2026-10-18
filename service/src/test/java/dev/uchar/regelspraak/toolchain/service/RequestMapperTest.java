package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "1990-06-15" }, "rollen": { "reis": "V1" } } ] }""");
        assertTrue(r.violations().isEmpty(), "onverwachte schendingen: " + r.violations());
        assertEquals(2, r.world().beans().size());
    }

    @Test
    void unknown_attribute_names_the_field() {
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributen": { "schoenmaat": 42 } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.ONBEKEND_VELD
                && s.field().equals("instanties[0].attributen.schoenmaat")
                && s.message().contains("schoenmaat")), r.violations().toString());
    }

    @Test
    void unknown_object_type_names_the_field() {
        var r = map("""
            { "instanties": [ { "type": "Ruimteschip", "id": "R1" } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.ONBEKEND_VELD && s.field().equals("instanties[0].type")), r.violations().toString());
    }

    @Test
    void wrong_datatype_is_reported() {
        // geboortedatum is a Datum -> a number is ill-typed
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributen": { "geboortedatum": 12345 } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.VERKEERD_TYPE
                && s.field().equals("instanties[0].attributen.geboortedatum")), r.violations().toString());
    }

    @Test
    void unit_bearing_number_accepts_object_with_correct_unit() {
        // afstand tot bestemming: Numeriek (geheel getal) met eenheid km
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "afstand tot bestemming": { "waarde": 200, "eenheid": "km" } } } ] }""");
        assertTrue(r.violations().isEmpty(), "onverwacht: " + r.violations());
    }

    @Test
    void unit_mismatch_is_reported() {
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "afstand tot bestemming": { "waarde": 200, "eenheid": "mijl" } } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.VERKEERDE_EENHEID
                && s.field().equals("instanties[0].attributen.afstand tot bestemming")
                && s.message().contains("km")), r.violations().toString());
    }

    @Test
    void plain_number_on_unit_attribute_may() {
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "afstand tot bestemming": 200 } } ] }""");
        assertTrue(r.violations().isEmpty(), "onverwacht: " + r.violations());
    }

    @Test
    void unknown_enumeration_value_is_reported() {
        // luchthaven van vertrek is a Luchthavens enumeratie
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "luchthaven van vertrek": "Mars Base One" } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.ONBEKENDE_ENUM_WAARDE
                && s.field().equals("instanties[0].attributen.luchthaven van vertrek")), r.violations().toString());
    }

    @Test
    void valid_enumeration_value_maps() {
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "luchthaven van vertrek": "Amsterdam Schiphol" } } ] }""");
        assertTrue(r.violations().isEmpty(), "onverwacht: " + r.violations());
    }

    @Test
    void unknown_characteristic_is_reported() {
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "kenmerken": { "onsterfelijk": true } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.ONBEKEND_VELD
                && s.field().equals("instanties[0].kenmerken.onsterfelijk")), r.violations().toString());
    }

    @Test
    void unknown_role_is_reported() {
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "rollen": { "ruimteschip": "X" } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.ONBEKEND_VELD
                && s.field().contains("rollen.ruimteschip")), r.violations().toString());
    }

    @Test
    void unknown_parameter_in_request_is_reported() {
        var r = map("""
            { "parameters": { "tarief op pluto": 5 },
              "instanties": [ { "type": "Vlucht", "id": "V1" } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.ONBEKEND_VELD
                && s.field().equals("parameters.tarief op pluto")), r.violations().toString());
    }

    @Test
    void valid_parameter_becomes_global() {
        var r = map("""
            { "parameters": { "volwassenleeftijd": 18 },
              "instanties": [ { "type": "Vlucht", "id": "V1" } ] }""");
        assertTrue(r.violations().isEmpty(), "onverwacht: " + r.violations());
        assertNotNull(r.world().globals().get("volwassenleeftijd"));
        assertEquals(0, new BigDecimal("18").compareTo((BigDecimal) r.world().globals().get("volwassenleeftijd")));
    }

    @Test
    void evaluation_date_is_adopted() {
        var r = map("""
            { "rekendatum": "2024-01-01",
              "instanties": [ { "type": "Vlucht", "id": "V1" } ] }""");
        assertTrue(r.violations().isEmpty());
        assertEquals(java.time.LocalDate.of(2024, 1, 1), r.world().evaluationDate());
    }

    @Test
    void json_number_becomes_bigdecimal_never_double() {
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributen": { "te betalen belasting": 30.00 } } ] }""");
        assertTrue(r.violations().isEmpty(), r.violations().toString());
        Object bean = r.world().beans().get("P1");
        Object v = dev.uchar.regelspraak.runtime.RegelSpraakRuntime.get(bean, "teBetalenBelasting");
        assertTrue(v instanceof BigDecimal, "waarde moet BigDecimal zijn, was " + (v == null ? "null" : v.getClass()));
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
