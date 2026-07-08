package io.github.yhavinga.regelspraak.service;

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
 *   <li>{@code t2} — G2: a gap-peildatum → 4xx (DienstFout) naming the parameter AND the date on veld
 *       {@code peildatum}; overlapping sets → a startup DienstFout.</li>
 *   <li>{@code t3} — G3: a rule valid 2024-2026 referencing a parameter valued only for 2025 →
 *       periodeWaarschuwingen (citing both periods) on the kern accessor and on /info.</li>
 *   <li>{@code t4} — G4 one-date-authority: a rule reading Rekendatum (het jaar uit de Rekendatum) AND a
 *       vintage parameter — one request peildatum drives BOTH (both output values pinned).</li>
 *   <li>{@code t5} — G6 rest: peildatum absent + multi-vintage → 4xx veld {@code peildatum}; peildatum and
 *       rekendatum both present and unequal → 4xx naming both; default+set conflict → startup fout naming the
 *       parameter; degenerate single-ALTIJD set with no peildatum → 200.</li>
 *   <li>{@code t6} — NO-REGRESSION: a descriptor WITHOUT parametersets is the byte-identical fase 1-4 path
 *       (empty parametersets() + empty periodeWaarschuwingen()).</li>
 * </ul>
 */
class ParametersetServiceTest {

    // ---- shared fixtures ----

    /** A minimal service: a Persoon with a 'grens' computed from the vintage parameter volwassenleeftijd. */
    private static final String VINTAGE_BRON = """
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

    private static Map<String, String> bron(String tekst) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bron.rgs", tekst);
        return m;
    }

    private static BeslisserKern bouw(String descriptor, String bronTekst, String setsJson) {
        return BeslisserKern.bouw(descriptor, bron(bronTekst), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader(), setsJson);
    }

    // ---------------- t1 : G1(b) two vintages, two peildatums ----------------

    @Test
    void t1_twee_peildatums_leveren_verschillende_uitkomsten() {
        BeslisserKern kern = bouw(VINTAGE_DESCRIPTOR, VINTAGE_BRON, VINTAGE_SETS);
        // 2025 -> 18
        ObjectNode a2025 = kern.verwerk("grens", parse("""
            { "peildatum": "2025-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("18").compareTo(grens(a2025)), a2025.toString());
        // 2026 -> 21
        ObjectNode a2026 = kern.verwerk("grens", parse("""
            { "peildatum": "2026-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("21").compareTo(grens(a2026)), a2026.toString());
    }

    // ---------------- t2 : G2 gap + overlap ----------------

    @Test
    void t2a_gap_peildatum_is_4xx_die_parameter_en_datum_noemt() {
        BeslisserKern kern = bouw(VINTAGE_DESCRIPTOR, VINTAGE_BRON, VINTAGE_SETS);
        // 2024 is before the first vintage interval -> gap.
        DienstFout fout = assertThrows(DienstFout.class, () -> kern.verwerk("grens", parse("""
            { "peildatum": "2024-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }""")));
        assertEquals(1, fout.schendingen().size(), fout.getMessage());
        Schending s = fout.schendingen().get(0);
        assertEquals("peildatum", s.veld(), fout.getMessage());
        assertTrue(s.bericht().contains("volwassenleeftijd"), () -> "noemt de parameter: " + s.bericht());
        assertTrue(s.bericht().contains("2024-06-01"), () -> "noemt de peildatum: " + s.bericht());
    }

    @Test
    void t2b_overlappende_sets_is_startup_dienstfout() {
        String overlap = """
            { "parametersets": [
                { "naam": "a",
                  "waarden": { "volwassenleeftijd": [
                      { "waarde": 18, "vanaf": "2025-01-01", "tot": "2025-12-31" },
                      { "waarde": 21, "vanaf": "2025-06-01", "tot": "2026-12-31" } ] } } ] }""";
        DienstFout fout = assertThrows(DienstFout.class, () -> bouw(VINTAGE_DESCRIPTOR, VINTAGE_BRON, overlap));
        assertTrue(fout.getMessage().contains("overlappende") || fout.getMessage().contains("valideren niet"),
                fout.getMessage());
        boolean noemtParam = fout.schendingen().stream().anyMatch(s -> s.bericht().contains("volwassenleeftijd"));
        assertTrue(noemtParam, "de startup-fout hoort de parameter te noemen: " + fout.getMessage());
    }

    // ---------------- t3 : G3 periode-consistentie ----------------

    @Test
    void t3_periode_waarschuwing_op_kern_en_info() {
        // A rule valid 2024-2026 referencing a parameter valued only for 2025 -> a WARNING citing both periods.
        String bron = """
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
        BeslisserKern kern = bouw(VINTAGE_DESCRIPTOR, bron, sets);
        assertFalse(kern.periodeWaarschuwingen().isEmpty(), "een periode-gat hoort een waarschuwing te geven");
        String tekst = String.join("\n", kern.periodeWaarschuwingen());
        assertTrue(tekst.contains("volwassenleeftijd"), () -> "noemt de parameter: " + tekst);
        // the message cites the regelperiode (2024..2026) AND the uncovered gap periods (2024 en 2026, the
        // parameter is only valued in 2025) — both dimensions are visible.
        assertTrue(tekst.contains("2024-01-01") && tekst.contains("2026-12-31"),
                () -> "citeert de regelperiode: " + tekst);
        assertTrue(tekst.contains("2024-12-31") && tekst.contains("2026-01-01"),
                () -> "citeert de ongedekte gat-periodes rond het 2025-only-venster: " + tekst);
    }

    // ---------------- t4 : G4 one date authority (Rekendatum + vintage) ----------------

    @Test
    void t4_een_peildatum_drijft_rekendatum_en_vintage() {
        // The rule reads BOTH the vintage parameter AND the Rekendatum year — one peildatum drives both.
        String bron = """
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
        BeslisserKern kern = bouw(descriptor, bron, VINTAGE_SETS);

        // peildatum 2026-06-01: vintage=21 AND het jaar uit de rekendatum=2026 — both from the ONE date.
        ObjectNode a = kern.verwerk("grens-en-jaar", parse("""
            { "peildatum": "2026-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("21").compareTo(waarde(a, "grens")), a.toString());
        assertEquals(0, new BigDecimal("2026").compareTo(waarde(a, "geboortejaar")), a.toString());

        // 2025-06-01: vintage=18 AND year=2025.
        ObjectNode b = kern.verwerk("grens-en-jaar", parse("""
            { "peildatum": "2025-06-01", "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("18").compareTo(waarde(b, "grens")), b.toString());
        assertEquals(0, new BigDecimal("2025").compareTo(waarde(b, "geboortejaar")), b.toString());
    }

    // ---------------- t5 : G6 rest ----------------

    @Test
    void t5a_geen_peildatum_multi_vintage_is_4xx_veld_peildatum() {
        BeslisserKern kern = bouw(VINTAGE_DESCRIPTOR, VINTAGE_BRON, VINTAGE_SETS);
        DienstFout fout = assertThrows(DienstFout.class, () -> kern.verwerk("grens", parse("""
            { "instanties": [ { "type": "Persoon", "id": "P1" } ] }""")));
        Schending s = fout.schendingen().get(0);
        assertEquals("peildatum", s.veld(), fout.getMessage());
        assertTrue(s.bericht().contains("vintage") || s.bericht().contains("verplicht"), s.bericht());
    }

    @Test
    void t5b_peildatum_en_rekendatum_ongelijk_is_4xx_die_beide_noemt() {
        BeslisserKern kern = bouw(VINTAGE_DESCRIPTOR, VINTAGE_BRON, VINTAGE_SETS);
        DienstFout fout = assertThrows(DienstFout.class, () -> kern.verwerk("grens", parse("""
            { "peildatum": "2025-06-01", "rekendatum": "2026-06-01",
              "instanties": [ { "type": "Persoon", "id": "P1" } ] }""")));
        boolean noemtBeide = fout.schendingen().stream().anyMatch(s ->
                s.veld().equals("peildatum")
                        && s.bericht().contains("2025-06-01") && s.bericht().contains("2026-06-01"));
        assertTrue(noemtBeide, "de 4xx hoort beide velden+waarden te noemen: " + fout.getMessage());
    }

    @Test
    void t5c_default_en_set_conflict_is_startup_fout_die_de_parameter_noemt() {
        // The descriptor gives volwassenleeftijd as a default AND the sets version it -> ambiguous authority.
        String descriptor = """
            { "dienst": {"naam":"conflict","versie":"1"},
              "entrypoints": [ { "naam":"grens","pad":"grens",
                "scope": {"regelnamen": ["grensregel"]},
                "invoer": ["Persoon"], "uitvoer": ["Persoon.grens"] } ],
              "parameters": { "volwassenleeftijd": 40 },
              "parametersets": "vintage.parameters.json" }""";
        DienstFout fout = assertThrows(DienstFout.class, () -> bouw(descriptor, VINTAGE_BRON, VINTAGE_SETS));
        boolean noemtParam = fout.schendingen().stream().anyMatch(s -> s.bericht().contains("volwassenleeftijd"));
        assertTrue(noemtParam, "de conflict-fout hoort de parameter te noemen: " + fout.getMessage());
    }

    @Test
    void t5d_degeneraat_altijd_set_zonder_peildatum_is_200() {
        String sets = """
            { "parametersets": [
                { "naam": "enkel",
                  "waarden": { "volwassenleeftijd": [ { "waarde": 18 } ] } } ] }""";
        BeslisserKern kern = bouw(VINTAGE_DESCRIPTOR, VINTAGE_BRON, sets);
        // no peildatum, single ALTIJD-value -> EPOCH-resolution, computes 18.
        ObjectNode a = kern.verwerk("grens", parse("""
            { "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("18").compareTo(grens(a)), a.toString());
    }

    // ---------------- t6 : NO-REGRESSION (no parametersets) ----------------

    @Test
    void t6_geen_parametersets_is_leeg_pad() {
        // A descriptor WITHOUT parametersets: the parameter comes from the request, no sidecar, no warnings.
        String descriptor = """
            { "dienst": {"naam":"geen-sets","versie":"1"},
              "entrypoints": [ { "naam":"grens","pad":"grens",
                "scope": {"regelnamen": ["grensregel"]},
                "invoer": ["Persoon"], "uitvoer": ["Persoon.grens"] } ],
              "parameters": {} }""";
        BeslisserKern kern = BeslisserKern.bouw(descriptor, bron(VINTAGE_BRON), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader());
        assertTrue(kern.parametersets().isEmpty(), "geen sets geladen");
        assertTrue(kern.periodeWaarschuwingen().isEmpty(), "geen periode-waarschuwingen");
        // the request supplies the parameter directly (the fase 1-4 path).
        ObjectNode a = kern.verwerk("grens", parse("""
            { "parameters": { "volwassenleeftijd": 30 },
              "instanties": [ { "type": "Persoon", "id": "P1" } ] }"""));
        assertEquals(0, new BigDecimal("30").compareTo(grens(a)), a.toString());
    }

    // ---- helpers ----

    private static BigDecimal grens(ObjectNode antwoord) { return waarde(antwoord, "grens"); }

    private static BigDecimal waarde(ObjectNode antwoord, String attribuut) {
        JsonNode n = vind(antwoord, "P1", "attributen", attribuut);
        assertNotNull(n, "attribuut '" + attribuut + "' ontbreekt: " + antwoord);
        return n.decimalValue();
    }

    private static JsonNode vind(ObjectNode antwoord, String id, String groep, String lid) {
        for (JsonNode inst : antwoord.get("instanties"))
            if (id.equals(inst.path("id").asText()))
                return inst.path(groep).get(lid);
        return null;
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
