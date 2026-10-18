package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.toolchain.engine.ParametersetLader.ParametersetFout;
import dev.uchar.regelspraak.model.Parameterset;
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
class ParametersetLaderTest {

    // Twee parameters: één met eenheid jr, één zonder — genoeg voor eenheid-objectvorm en type-checks.
    private static final String BRON = """
            Parameter de volwassenleeftijd : Numeriek (geheel getal) met eenheid jr
            Parameter het tarief : Numeriek (geheel getal)
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal) met eenheid jr;
            """;

    private static RegelSpraakModel model() {
        return Resolver.resolve(BRON);
    }

    // ---- happy ----

    @Test void happy_named_sets_eenheid_objectvorm_open_bounds_en_canonieke_naam() {
        // mixed-case parameternaam ("Volwassenleeftijd") moet naar de canonieke "volwassenleeftijd" normaliseren.
        String json = """
            { "parametersets": [
                { "naam": "2025", "waarden": {
                    "Volwassenleeftijd": [ { "waarde": {"waarde": 18, "eenheid": "jr"}, "vanaf": "2025-01-01", "tot": "2025-12-31" } ],
                    "tarief": [ { "waarde": 100 } ]
                }},
                { "naam": "2026", "waarden": {
                    "volwassenleeftijd": [ { "waarde": {"waarde": 21, "eenheid": "jr"}, "vanaf": "2026-01-01" } ]
                }}
            ]}
            """;
        List<Parameterset> sets = ParametersetLader.laad(json, model());
        assertEquals(2, sets.size());
        assertEquals("2025", sets.get(0).naam());

        // canonieke key: "volwassenleeftijd" (niet "Volwassenleeftijd"); tarief open (ALTIJD) is degenererend.
        assertTrue(sets.get(0).waarden().containsKey("volwassenleeftijd"), () -> sets.get(0).waarden().keySet().toString());

        // vintage-selectie over de UNIE: 2025 -> 18, 2026 -> 21.
        List<String> fouten = new ArrayList<>();
        Map<String, Object> op2025 = Parameterset.waardenOp(sets, LocalDate.parse("2025-06-01"), fouten);
        Map<String, Object> op2026 = Parameterset.waardenOp(sets, LocalDate.parse("2026-06-01"), fouten);
        assertTrue(fouten.isEmpty(), fouten::toString);
        assertEquals(new BigDecimal(18), op2025.get("volwassenleeftijd"));
        assertEquals(new BigDecimal(21), op2026.get("volwassenleeftijd"));
        assertEquals(new BigDecimal(100), op2025.get("tarief"));
    }

    // ---- negatief ----

    @Test void malformed_json_faalt() {
        ParametersetFout f = assertThrows(ParametersetFout.class,
                () -> ParametersetLader.laad("{ niet json", model()));
        assertTrue(f.getMessage().toLowerCase().contains("json"), f::getMessage);
    }

    @Test void onbekende_parameter_noemt_de_overtreder() {
        String json = """
            { "parametersets": [ { "naam": "s", "waarden": {
                "spookparameter": [ { "waarde": 1, "vanaf": "2025-01-01", "tot": "2025-12-31" } ] } } ] }
            """;
        ParametersetFout f = assertThrows(ParametersetFout.class, () -> ParametersetLader.laad(json, model()));
        assertTrue(f.fouten().stream().anyMatch(m -> m.contains("spookparameter")), f::getMessage);
    }

    @Test void ill_typed_waarde_noemt_het_veld() {
        // tarief is Numeriek maar krijgt tekst
        String json = """
            { "parametersets": [ { "naam": "s", "waarden": {
                "tarief": [ { "waarde": "geen getal", "vanaf": "2025-01-01", "tot": "2025-12-31" } ] } } ] }
            """;
        ParametersetFout f = assertThrows(ParametersetFout.class, () -> ParametersetLader.laad(json, model()));
        assertTrue(f.fouten().stream().anyMatch(m -> m.contains("tarief") && m.contains("getal")), f::getMessage);
    }

    @Test void verkeerde_eenheid_noemt_de_gedeclareerde() {
        String json = """
            { "parametersets": [ { "naam": "s", "waarden": {
                "volwassenleeftijd": [ { "waarde": {"waarde": 18, "eenheid": "mnd"}, "vanaf": "2025-01-01", "tot": "2025-12-31" } ] } } ] }
            """;
        ParametersetFout f = assertThrows(ParametersetFout.class, () -> ParametersetLader.laad(json, model()));
        assertTrue(f.fouten().stream().anyMatch(m -> m.contains("mnd") && m.contains("jr")), f::getMessage);
    }

    @Test void overlap_noemt_de_parameter() {
        String json = """
            { "parametersets": [ { "naam": "s", "waarden": {
                "tarief": [
                    { "waarde": 1, "vanaf": "2025-01-01", "tot": "2025-12-31" },
                    { "waarde": 2, "vanaf": "2025-06-01", "tot": "2026-06-30" } ] } } ] }
            """;
        ParametersetFout f = assertThrows(ParametersetFout.class, () -> ParametersetLader.laad(json, model()));
        assertTrue(f.fouten().stream().anyMatch(m -> m.contains("tarief") && m.toLowerCase().contains("overlap")),
                f::getMessage);
    }

    @Test void vanaf_na_tot_noemt_beide() {
        String json = """
            { "parametersets": [ { "naam": "s", "waarden": {
                "tarief": [ { "waarde": 1, "vanaf": "2026-01-01", "tot": "2025-12-31" } ] } } ] }
            """;
        ParametersetFout f = assertThrows(ParametersetFout.class, () -> ParametersetLader.laad(json, model()));
        assertTrue(f.fouten().stream().anyMatch(m -> m.contains("2026-01-01") && m.contains("2025-12-31")),
                f::getMessage);
    }
}
