package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 5 (card {@code 20260705-15:18-geldigheid-parametersets-peildatum.md}) — the SERVICE surface of
 * validity-versioned parameter values, resolved per request against the ONE date authority (the peildatum, which
 * IS the rekendatum global). Each test pins a gate:
 * <ul>
 *   <li>{@code t1} — G1(b) KERN: one service, a two-vintage parameterset (18 in 2025 / 21 from 2026); two
 *       requests differing ONLY in {@code peildatum} yield two DIFFERENT pinned answers.</li>
 *   <li>{@code t2} — G2: a gap-peildatum → 4xx (ServiceError) naming the parameter AND the date on veld
 *       {@code peildatum}; overlapping sets → a startup ServiceError.</li>
 *   <li>{@code t3} — G3: a rule valid 2024-2026 referencing a parameter valued only for 2025 →
 *       periodWarnings (citing both periods) on the kern accessor and on /info.</li>
 *   <li>{@code t4} — G4 one-date-authority: a rule reading Rekendatum (het jaar uit de Rekendatum) AND a
 *       vintage parameter — one request peildatum drives BOTH (both output values pinned).</li>
 *   <li>{@code t5} — G6 rest: peildatum absent + multi-vintage → 4xx veld {@code peildatum}; peildatum and
 *       rekendatum both present and unequal → 4xx naming both; default+set conflict → startup fout naming the
 *       parameter; degenerate single-ALTIJD set with no peildatum → 200.</li>
 *   <li>{@code t6} — NO-REGRESSION: a descriptor WITHOUT parametersets is the byte-identical fase 1-4 path
 *       (empty parametersets() + empty periodWarnings()).</li>
 * </ul>
 */
class ParameterSetServiceTest {

    // ---- shared fixtures ----

    /** A minimal service: a Persoon with a 'grens' computed from the vintage parameter volwassenleeftijd. */
    private static final String VINTAGE_SOURCE = """
        Objecttype de Persoon (mv: Personen)
            de grens Numeriek (geheel getal);
        Parameter de volwassenleeftijd : Numeriek (geheel getal);
        Regel grensregel
        geldig altijd
        De grens van een Persoon moet berekend worden als de volwassenleeftijd.
        """;

    /** The descriptor for the vintage service, naming a parametersets sidecar (String-form supplies its JSON). */
    private static final String VINTAGE_DESCRIPTOR = """
        { "dienst": {"naam":"vintage-test","versie":"1"},
          "entrypoints": [ { "naam":"grens","pad":"grens",
            "scope": {"regelnamen": ["grensregel"]},
            "invoer": ["Persoon"], "uitvoer": ["Persoon.grens"] } ],
          "parameters": {},
          "parametersets": "vintage.parameters.json" }""";

    /** The two-vintage sidecar: volwassenleeftijd = 18 in 2025, 21 from 2026 (adjacent, no overlap). */
    private static final String VINTAGE_SETS = """
        { "parametersets": [
            { "naam": "leeftijden",
              "waarden": { "volwassenleeftijd": [
                  { "waarde": 18, "vanaf": "2025-01-01", "tot": "2025-12-31" },
                  { "waarde": 21, "vanaf": "2026-01-01" } ] } } ] }""";

    private static Map<String, String> source(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bron.rgs", text);
        return m;
    }

    private static DecisionCore assemble(String descriptor, String sourceText, String setsJson) {
        return DecisionCore.assemble(descriptor, source(sourceText), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), setsJson);
    }

    // ---------------- t1 : G1(b) two vintages, two peildatums ----------------

    @Test
    void t1_two_as_of_dates_yield_different_outcomes() {
        DecisionCore core = assemble(VINTAGE_DESCRIPTOR, VINTAGE_SOURCE, VINTAGE_SETS);
        // 2025 -> 18
        ObjectNode a2025 = core.process("grens", parse("""
            { "peildatum": "2025-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("18").compareTo(bound(a2025)), a2025.toString());
        // 2026 -> 21
        ObjectNode a2026 = core.process("grens", parse("""
            { "peildatum": "2026-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("21").compareTo(bound(a2026)), a2026.toString());
    }

    // ---------------- t2 : G2 gap + overlap ----------------

    @Test
    void t2a_gap_as_of_date_is_4xx_that_names_parameter_and_date() {
        DecisionCore core = assemble(VINTAGE_DESCRIPTOR, VINTAGE_SOURCE, VINTAGE_SETS);
        // 2024 is before the first vintage interval -> gap.
        ServiceError error = assertThrows(ServiceError.class, () -> core.process("grens", parse("""
            { "peildatum": "2024-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }""")));
        assertEquals(1, error.violations().size(), error.getMessage());
        Violation s = error.violations().get(0);
        assertEquals("peildatum", s.field(), error.getMessage());
        assertTrue(s.message().contains("volwassenleeftijd"), () -> "noemt de parameter: " + s.message());
        assertTrue(s.message().contains("2024-06-01"), () -> "noemt de peildatum: " + s.message());
    }

    @Test
    void t2b_overlapping_sets_is_startup_service_error() {
        String overlap = """
            { "parametersets": [
                { "naam": "a",
                  "waarden": { "volwassenleeftijd": [
                      { "waarde": 18, "vanaf": "2025-01-01", "tot": "2025-12-31" },
                      { "waarde": 21, "vanaf": "2025-06-01", "tot": "2026-12-31" } ] } } ] }""";
        ServiceError error = assertThrows(ServiceError.class, () -> assemble(VINTAGE_DESCRIPTOR, VINTAGE_SOURCE, overlap));
        assertTrue(error.getMessage().contains("overlappende") || error.getMessage().contains("valideren niet"),
                error.getMessage());
        boolean namesParam = error.violations().stream().anyMatch(s -> s.message().contains("volwassenleeftijd"));
        assertTrue(namesParam, "de startup-fout hoort de parameter te noemen: " + error.getMessage());
    }

    // ---------------- t3 : G3 periode-consistentie ----------------

    @Test
    void t3_period_warning_on_core_and_info() {
        // A rule valid 2024-2026 referencing a parameter valued only for 2025 -> a WARNING citing both periods.
        String source = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig vanaf 01-01-2024 t/m 31-12-2026
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            """;
        String sets = """
            { "parametersets": [
                { "naam": "alleen-2025",
                  "waarden": { "volwassenleeftijd": [
                      { "waarde": 18, "vanaf": "2025-01-01", "tot": "2025-12-31" } ] } } ] }""";
        DecisionCore core = assemble(VINTAGE_DESCRIPTOR, source, sets);
        assertFalse(core.periodWarnings().isEmpty(), "een periode-gat hoort een waarschuwing te geven");
        String text = String.join("\n", core.periodWarnings());
        assertTrue(text.contains("volwassenleeftijd"), () -> "noemt de parameter: " + text);
        // the message cites the regelperiode (2024..2026) AND the uncovered gap periods (2024 en 2026, the
        // parameter is only valued in 2025) — both dimensions are visible.
        assertTrue(text.contains("2024-01-01") && text.contains("2026-12-31"),
                () -> "citeert de regelperiode: " + text);
        assertTrue(text.contains("2024-12-31") && text.contains("2026-01-01"),
                () -> "citeert de ongedekte gat-periodes rond het 2025-only-venster: " + text);
    }

    // ---------------- t4 : G4 one date authority (Rekendatum + vintage) ----------------

    @Test
    void t4_an_as_of_date_drives_evaluation_date_and_vintage() {
        // The rule reads BOTH the vintage parameter AND the Rekendatum year — one peildatum drives both.
        String source = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
                het geboortejaar Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Regel jaarregel
            geldig altijd
            Het geboortejaar van een Persoon moet berekend worden als het jaar uit de Rekendatum.
            """;
        String descriptor = """
            { "dienst": {"naam":"vintage-datum","versie":"1"},
              "entrypoints": [ { "naam":"grens-en-jaar","pad":"grens-en-jaar",
                "scope": {"regelnamen": ["grensregel", "jaarregel"]},
                "invoer": ["Persoon"], "uitvoer": ["Persoon.grens", "Persoon.geboortejaar"] } ],
              "parameters": {},
              "parametersets": "vintage.parameters.json" }""";
        DecisionCore core = assemble(descriptor, source, VINTAGE_SETS);

        // peildatum 2026-06-01: vintage=21 AND het jaar uit de rekendatum=2026 — both from the ONE date.
        ObjectNode a = core.process("grens-en-jaar", parse("""
            { "peildatum": "2026-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("21").compareTo(value(a, "grens")), a.toString());
        assertEquals(0, new BigDecimal("2026").compareTo(value(a, "geboortejaar")), a.toString());

        // 2025-06-01: vintage=18 AND year=2025.
        ObjectNode b = core.process("grens-en-jaar", parse("""
            { "peildatum": "2025-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("18").compareTo(value(b, "grens")), b.toString());
        assertEquals(0, new BigDecimal("2025").compareTo(value(b, "geboortejaar")), b.toString());
    }

    // ---------------- t5 : G6 rest ----------------

    @Test
    void t5a_no_as_of_date_multi_vintage_is_4xx_field_as_of_date() {
        DecisionCore core = assemble(VINTAGE_DESCRIPTOR, VINTAGE_SOURCE, VINTAGE_SETS);
        ServiceError error = assertThrows(ServiceError.class, () -> core.process("grens", parse("""
            { "instanties": [ { "type": "Persoon", "id": "P1" } ] }""")));
        Violation s = error.violations().get(0);
        assertEquals("peildatum", s.field(), error.getMessage());
        assertTrue(s.message().contains("vintage") || s.message().contains("verplicht"), s.message());
    }

    @Test
    void t5b_as_of_date_and_evaluation_date_not_equal_is_4xx_that_names_both() {
        DecisionCore core = assemble(VINTAGE_DESCRIPTOR, VINTAGE_SOURCE, VINTAGE_SETS);
        ServiceError error = assertThrows(ServiceError.class, () -> core.process("grens", parse("""
            { "peildatum": "2025-06-01", "rekendatum": "2026-06-01",
              "instanties": [ { "type": "Persoon", "id": "P1" } ] }""")));
        boolean namesBoth = error.violations().stream().anyMatch(s ->
                s.field().equals("peildatum")
                        && s.message().contains("2025-06-01") && s.message().contains("2026-06-01"));
        assertTrue(namesBoth, "de 4xx hoort beide velden+waarden te noemen: " + error.getMessage());
    }

    @Test
    void t5c_default_and_set_conflict_is_startup_error_that_names_the_parameter() {
        // The descriptor gives volwassenleeftijd as a default AND the sets version it -> ambiguous authority.
        String descriptor = """
            { "dienst": {"naam":"conflict","versie":"1"},
              "entrypoints": [ { "naam":"grens","pad":"grens",
                "scope": {"regelnamen": ["grensregel"]},
                "invoer": ["Persoon"], "uitvoer": ["Persoon.grens"] } ],
              "parameters": { "volwassenleeftijd": 40 },
              "parametersets": "vintage.parameters.json" }""";
        ServiceError error = assertThrows(ServiceError.class, () -> assemble(descriptor, VINTAGE_SOURCE, VINTAGE_SETS));
        boolean namesParam = error.violations().stream().anyMatch(s -> s.message().contains("volwassenleeftijd"));
        assertTrue(namesParam, "de conflict-fout hoort de parameter te noemen: " + error.getMessage());
    }

    @Test
    void t5d_degenerate_always_set_without_as_of_date_is_200() {
        String sets = """
            { "parametersets": [
                { "naam": "enkel",
                  "waarden": { "volwassenleeftijd": [ { "waarde": 18 } ] } } ] }""";
        DecisionCore core = assemble(VINTAGE_DESCRIPTOR, VINTAGE_SOURCE, sets);
        // no peildatum, single ALTIJD-value -> EPOCH-resolution, computes 18.
        ObjectNode a = core.process("grens", parse("""
            { "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("18").compareTo(bound(a)), a.toString());
    }

    // ---------------- t6 : NO-REGRESSION (no parametersets) ----------------

    @Test
    void t6_no_parameter_sets_is_empty_path() {
        // A descriptor WITHOUT parametersets: the parameter comes from the request, no sidecar, no warnings.
        String descriptor = """
            { "dienst": {"naam":"geen-sets","versie":"1"},
              "entrypoints": [ { "naam":"grens","pad":"grens",
                "scope": {"regelnamen": ["grensregel"]},
                "invoer": ["Persoon"], "uitvoer": ["Persoon.grens"] } ],
              "parameters": {} }""";
        DecisionCore core = DecisionCore.assemble(descriptor, source(VINTAGE_SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        assertTrue(core.parameterSets().isEmpty(), "geen sets geladen");
        assertTrue(core.periodWarnings().isEmpty(), "geen periode-waarschuwingen");
        // the request supplies the parameter directly (the fase 1-4 path).
        ObjectNode a = core.process("grens", parse("""
            { "parameters": { "volwassenleeftijd": 30 },
              "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("30").compareTo(bound(a)), a.toString());
    }

    // ---- helpers ----

    private static BigDecimal bound(ObjectNode answer) { return value(answer, "grens"); }

    private static BigDecimal value(ObjectNode answer, String attribute) {
        JsonNode n = find(answer, "P1", "attributen", attribute);
        assertNotNull(n, "attribuut '" + attribute + "' ontbreekt: " + answer);
        return n.decimalValue();
    }

    private static JsonNode find(ObjectNode answer, String id, String group, String member) {
        for (JsonNode inst : answer.get("instanties"))
            if (id.equals(inst.path("id").asText()))
                return inst.path(group).get(member);
        return null;
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
