package io.github.yhavinga.regelspraak.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-fast on marker rules (decision 3, the exit-3 discipline at the service boundary). Under the DRL back-end
 * a MARKER is a construct the DRL transpiler emits as an accepted-but-inert no-op ({@code /* TODO} placeholder,
 * stripped before compile). The Paaskorting rule the earlier exec-model version used LOWERS under DRL, so this
 * test drives the DRL-MARKER construct instead: "het absolute tijdsevenredig deel per maand" (the same
 * construct the golden {@code scenario-marker-exit3.rgs} scopes for its exit-3 reading). A descriptor scoping it
 * must refuse startup naming the rule when markers are disallowed, and start + expose it when allowed.
 *
 * <p>The source is self-contained (its own Objecttype), so {@link BeslisserKern} compiles its beans in memory
 * and needs no external fact classes.
 */
class MarkerFailFastTest {

    /** A self-contained model whose one rule lowers to a DRL marker (mirrors scenario-marker-exit3.rgs). */
    private static final String MARKER_BRON = """
        Objecttype de Vlucht (mv: Vluchten)
            de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
        Regel heffing markert
        geldig altijd
        De heffing van een Vlucht moet berekend worden als het absolute tijdsevenredig deel per maand van 10,00 EUR.
        """;

    private static final String MARKER_DESCRIPTOR = """
        { "dienst": {"naam":"marker-test","versie":"1"},
          "entrypoints": [ { "naam":"heffing markert","pad":"heffing-markert",
            "scope": {"regelnamen": ["heffing markert"]},
            "invoer": ["Vlucht"], "uitvoer": ["Vlucht.heffing"] } ],
          "parameters": {} }""";

    private static Map<String, String> bron() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("marker.rgs", MARKER_BRON);
        return m;
    }

    @Test
    void strikt_weigert_start_en_noemt_de_marker_regel() {
        DienstFout f = assertThrows(DienstFout.class, () -> BeslisserKern.bouw(
                MARKER_DESCRIPTOR, bron(), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader()));
        assertTrue(f.getMessage().contains("heffing markert"), f.getMessage());
        assertTrue(f.schendingen().stream().anyMatch(s -> s.soort() == Schending.Soort.MARKER), f.getMessage());
    }

    @Test
    void met_markers_start_en_stelt_de_marker_bloot() {
        BeslisserKern kern = BeslisserKern.bouw(
                MARKER_DESCRIPTOR, bron(), KernConfig.metMarkers(),
                Thread.currentThread().getContextClassLoader());
        BeslisserKern.Entrypoint ep = kern.entrypoint("heffing-markert");
        assertFalse(ep.markers().isEmpty());
        assertTrue(ep.markers().contains("heffing markert"), ep.markers().toString());
    }

    @Test
    void een_gelowerde_regel_start_ook_strikt() {
        // 'bepaal leeftijd' lowers cleanly under DRL -> no marker -> strict start succeeds.
        String descriptor = """
            { "dienst": {"naam":"ok","versie":"1"},
              "entrypoints": [ { "naam":"bepaal-leeftijd","pad":"bepaal-leeftijd",
                "scope": {"regelnamen": ["bepaal leeftijd"]},
                "invoer": ["Vlucht","Natuurlijk persoon"], "uitvoer": ["Natuurlijk persoon.leeftijd"] } ],
              "parameters": {} }""";
        BeslisserKern kern = BeslisserKern.bouw(descriptor, TokaBron.bronPerBestand(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
        assertTrue(kern.entrypoint("bepaal-leeftijd").markers().isEmpty());
    }
}
