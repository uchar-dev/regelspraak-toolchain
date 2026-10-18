package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.toolchain.engine.ParameterSetLoader.ParameterSetError;
import dev.uchar.regelspraak.model.ParameterSet;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Laadt parameterset-sidecars tegen een geresolveerd model: happy path (named sets, eenheid-objectvorm, open bounds,
 * canonieke-naam-normalisatie) en de negatieve oppervlakte (malformed, onbekende parameter, ill-typed, verkeerde
 * eenheid, overlap, vanaf&gt;tot) — elk asserteert dat de fout de overtreder noemt.
 */
class ParameterSetLoaderTest {

    // Twee parameters: één met eenheid jr, één zonder — genoeg voor eenheid-objectvorm en type-checks.
    private static final String SOURCE = """
            Parameter de volwassenleeftijd : Numeriek (geheel getal) met eenheid jr
            Parameter het tarief : Numeriek (geheel getal)
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal) met eenheid jr;
            """;

    private static RegelSpraakModel model() {
        return Resolver.resolve(SOURCE);
    }

    // ---- happy ----

    @Test void happy_named_sets_unit_object_form_open_bounds_and_canonical_name() {
        // mixed-case parameternaam ("Volwassenleeftijd") moet naar de canonieke "volwassenleeftijd" normaliseren.
        String json = """
            { "parameterSets": [
                { "name": "2025", "values": {
                    "Volwassenleeftijd": [ { "value": {"value": 18, "unit": "jr"}, "from": "2025-01-01", "to": "2025-12-31" } ],
                    "tarief": [ { "value": 100 } ]
                }},
                { "name": "2026", "values": {
                    "volwassenleeftijd": [ { "value": {"value": 21, "unit": "jr"}, "from": "2026-01-01" } ]
                }}
            ]}
            """;
        List<ParameterSet> sets = ParameterSetLoader.load(json, model());
        assertEquals(2, sets.size());
        assertEquals("2025", sets.get(0).naam());

        // canonieke key: "volwassenleeftijd" (niet "Volwassenleeftijd"); tarief open (ALTIJD) is degenererend.
        assertTrue(sets.get(0).waarden().containsKey("volwassenleeftijd"), () -> sets.get(0).waarden().keySet().toString());

        // vintage-selectie over de UNIE: 2025 -> 18, 2026 -> 21.
        List<String> errors = new ArrayList<>();
        Map<String, Object> op2025 = ParameterSet.valuesOn(sets, LocalDate.parse("2025-06-01"), errors);
        Map<String, Object> op2026 = ParameterSet.valuesOn(sets, LocalDate.parse("2026-06-01"), errors);
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(new BigDecimal(18), op2025.get("volwassenleeftijd"));
        assertEquals(new BigDecimal(21), op2026.get("volwassenleeftijd"));
        assertEquals(new BigDecimal(100), op2025.get("tarief"));
    }

    // ---- negatief ----

    @Test void malformed_json_fails() {
        ParameterSetError f = assertThrows(ParameterSetError.class,
                () -> ParameterSetLoader.load("{ niet json", model()));
        assertTrue(f.getMessage().toLowerCase().contains("json"), f::getMessage);
    }

    @Test void unknown_parameter_names_the_offender() {
        String json = """
            { "parameterSets": [ { "name": "s", "values": {
                "spookparameter": [ { "value": 1, "from": "2025-01-01", "to": "2025-12-31" } ] } } ] }
            """;
        ParameterSetError f = assertThrows(ParameterSetError.class, () -> ParameterSetLoader.load(json, model()));
        assertTrue(f.errors().stream().anyMatch(m -> m.contains("spookparameter")), f::getMessage);
    }

    @Test void ill_typed_value_names_the_field() {
        // tarief is Numeriek maar krijgt tekst
        String json = """
            { "parameterSets": [ { "name": "s", "values": {
                "tarief": [ { "value": "geen getal", "from": "2025-01-01", "to": "2025-12-31" } ] } } ] }
            """;
        ParameterSetError f = assertThrows(ParameterSetError.class, () -> ParameterSetLoader.load(json, model()));
        assertTrue(f.errors().stream().anyMatch(m -> m.contains("tarief") && m.contains("getal")), f::getMessage);
    }

    @Test void wrong_unit_names_the_declared_one() {
        String json = """
            { "parameterSets": [ { "name": "s", "values": {
                "volwassenleeftijd": [ { "value": {"value": 18, "unit": "mnd"}, "from": "2025-01-01", "to": "2025-12-31" } ] } } ] }
            """;
        ParameterSetError f = assertThrows(ParameterSetError.class, () -> ParameterSetLoader.load(json, model()));
        assertTrue(f.errors().stream().anyMatch(m -> m.contains("mnd") && m.contains("jr")), f::getMessage);
    }

    @Test void overlap_names_the_parameter() {
        String json = """
            { "parameterSets": [ { "name": "s", "values": {
                "tarief": [
                    { "value": 1, "from": "2025-01-01", "to": "2025-12-31" },
                    { "value": 2, "from": "2025-06-01", "to": "2026-06-30" } ] } } ] }
            """;
        ParameterSetError f = assertThrows(ParameterSetError.class, () -> ParameterSetLoader.load(json, model()));
        assertTrue(f.errors().stream().anyMatch(m -> m.contains("tarief") && m.toLowerCase().contains("overlap")),
                f::getMessage);
    }

    @Test void from_after_to_names_both() {
        String json = """
            { "parameterSets": [ { "name": "s", "values": {
                "tarief": [ { "value": 1, "from": "2026-01-01", "to": "2025-12-31" } ] } } ] }
            """;
        ParameterSetError f = assertThrows(ParameterSetError.class, () -> ParameterSetLoader.load(json, model()));
        assertTrue(f.errors().stream().anyMatch(m -> m.contains("2026-01-01") && m.contains("2025-12-31")),
                f::getMessage);
    }
}
