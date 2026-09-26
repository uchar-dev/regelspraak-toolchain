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
 * so a violation is observed as a {@link Schending} carrying its field path, exactly what fase 3 turns into a
 * 400 naming the field.
 */
class VerzoekMapperTest {

    private static RegelSpraakModel MODEL;
    private static VerzoekMapper MAPPER;

    @BeforeAll
    static void bouw() {
        StringBuilder bron = new StringBuilder();
        for (String tekst : TokaBron.bronPerBestand().values()) bron.append(tekst).append('\n');
        MODEL = Resolver.resolve(bron.toString());
        MAPPER = new VerzoekMapper(MODEL, TokaBron.beanLoader());
    }

    private static VerzoekMapper.Resultaat map(String json) {
        return MAPPER.map(new Verzoek(parse(json)));
    }

    @Test
    void geldig_verzoek_mapt_zonder_schending() {
        var r = map("""
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "1990-06-15" }, "rollen": { "reis": "V1" } } ] }""");
        assertTrue(r.schendingen().isEmpty(), "onverwachte schendingen: " + r.schendingen());
        assertEquals(2, r.wereld().beans().size());
    }

    @Test
    void onbekend_attribuut_noemt_het_veld() {
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributen": { "schoenmaat": 42 } } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.ONBEKEND_VELD
                && s.veld().equals("instanties[0].attributen.schoenmaat")
                && s.bericht().contains("schoenmaat")), r.schendingen().toString());
    }

    @Test
    void onbekend_objecttype_noemt_het_veld() {
        var r = map("""
            { "instanties": [ { "type": "Ruimteschip", "id": "R1" } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.ONBEKEND_VELD && s.veld().equals("instanties[0].type")), r.schendingen().toString());
    }

    @Test
    void verkeerd_datatype_wordt_gemeld() {
        // geboortedatum is a Datum -> a number is ill-typed
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributen": { "geboortedatum": 12345 } } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.VERKEERD_TYPE
                && s.veld().equals("instanties[0].attributen.geboortedatum")), r.schendingen().toString());
    }

    @Test
    void eenheid_bearing_getal_accepteert_object_met_juiste_eenheid() {
        // afstand tot bestemming: Numeriek (geheel getal) met eenheid km
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "afstand tot bestemming": { "waarde": 200, "eenheid": "km" } } } ] }""");
        assertTrue(r.schendingen().isEmpty(), "onverwacht: " + r.schendingen());
    }

    @Test
    void eenheid_mismatch_wordt_gemeld() {
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "afstand tot bestemming": { "waarde": 200, "eenheid": "mijl" } } } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.VERKEERDE_EENHEID
                && s.veld().equals("instanties[0].attributen.afstand tot bestemming")
                && s.bericht().contains("km")), r.schendingen().toString());
    }

    @Test
    void plain_getal_op_eenheid_attribuut_mag() {
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "afstand tot bestemming": 200 } } ] }""");
        assertTrue(r.schendingen().isEmpty(), "onverwacht: " + r.schendingen());
    }

    @Test
    void onbekende_enumeratie_waarde_wordt_gemeld() {
        // luchthaven van vertrek is a Luchthavens enumeratie
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "luchthaven van vertrek": "Mars Base One" } } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.ONBEKENDE_ENUM_WAARDE
                && s.veld().equals("instanties[0].attributen.luchthaven van vertrek")), r.schendingen().toString());
    }

    @Test
    void geldige_enumeratie_waarde_mapt() {
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1",
                "attributen": { "luchthaven van vertrek": "Amsterdam Schiphol" } } ] }""");
        assertTrue(r.schendingen().isEmpty(), "onverwacht: " + r.schendingen());
    }

    @Test
    void onbekend_kenmerk_wordt_gemeld() {
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "kenmerken": { "onsterfelijk": true } } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.ONBEKEND_VELD
                && s.veld().equals("instanties[0].kenmerken.onsterfelijk")), r.schendingen().toString());
    }

    @Test
    void onbekende_rol_wordt_gemeld() {
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "rollen": { "ruimteschip": "X" } } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.ONBEKEND_VELD
                && s.veld().contains("rollen.ruimteschip")), r.schendingen().toString());
    }

    @Test
    void onbekende_parameter_in_verzoek_wordt_gemeld() {
        var r = map("""
            { "parameters": { "tarief op pluto": 5 },
              "instanties": [ { "type": "Vlucht", "id": "V1" } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.ONBEKEND_VELD
                && s.veld().equals("parameters.tarief op pluto")), r.schendingen().toString());
    }

    @Test
    void geldige_parameter_wordt_global() {
        var r = map("""
            { "parameters": { "volwassenleeftijd": 18 },
              "instanties": [ { "type": "Vlucht", "id": "V1" } ] }""");
        assertTrue(r.schendingen().isEmpty(), "onverwacht: " + r.schendingen());
        assertNotNull(r.wereld().globals().get("volwassenleeftijd"));
        assertEquals(0, new BigDecimal("18").compareTo((BigDecimal) r.wereld().globals().get("volwassenleeftijd")));
    }

    @Test
    void rekendatum_wordt_overgenomen() {
        var r = map("""
            { "rekendatum": "2024-01-01",
              "instanties": [ { "type": "Vlucht", "id": "V1" } ] }""");
        assertTrue(r.schendingen().isEmpty());
        assertEquals(java.time.LocalDate.of(2024, 1, 1), r.wereld().rekendatum());
    }

    @Test
    void json_getal_wordt_bigdecimal_nooit_double() {
        var r = map("""
            { "instanties": [ { "type": "Natuurlijk persoon", "id": "P1",
                "attributen": { "te betalen belasting": 30.00 } } ] }""");
        assertTrue(r.schendingen().isEmpty(), r.schendingen().toString());
        Object bean = r.wereld().beans().get("P1");
        Object v = dev.uchar.regelspraak.runtime.RegelSpraakRuntime.get(bean, "teBetalenBelasting");
        assertTrue(v instanceof BigDecimal, "waarde moet BigDecimal zijn, was " + (v == null ? "null" : v.getClass()));
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
