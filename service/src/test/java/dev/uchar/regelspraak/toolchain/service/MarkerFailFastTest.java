package dev.uchar.regelspraak.toolchain.service;

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

    /** A refusal of uchar's backend that names no construct: a reciprocal self-relation whose role shares its
     *  name with a declared attribute, which the bean model cannot hold. Until uchar carried the build this was a
     *  startup error even with markers allowed; it is uchar not carrying the entry point, like a marker. */
    private static final String NAAMDELING_BRON = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het strandtegoed Numeriek (geheel getal);
            de strandmaat Numeriek (geheel getal);
            de maatdeling Numeriek (geheel getal);

        Wederkerig feittype strandmaatschap
            de strandmaat (mv: strandmaten) Jutter
        Eén strandmaat heeft één strandmaat

        Regel maatdeling van een jutter
            geldig altijd
                De maatdeling van een Jutter moet berekend worden als het strandtegoed van de Jutter plus 1.
        """;

    private static final String NAAMDELING_DESCRIPTOR = """
        { "dienst": {"naam":"naamdeling","versie":"1"},
          "entrypoints": [ { "naam":"maatdeling","pad":"maatdeling",
            "scope": {"regelnamen": ["maatdeling van een jutter"]},
            "invoer": ["Jutter"], "uitvoer": ["Jutter.maatdeling"] } ],
          "parameters": {} }""";

    @Test
    void een_weigering_zonder_constructnaam_is_ook_een_marker() {
        Map<String, String> bron = Map.of("naamdeling.rgs", NAAMDELING_BRON);
        DienstFout f = assertThrows(DienstFout.class, () -> BeslisserKern.bouw(NAAMDELING_DESCRIPTOR, bron,
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader()));
        assertTrue(f.schendingen().stream().allMatch(s -> s.soort() == Schending.Soort.MARKER), f.getMessage());
        assertTrue(f.getMessage().contains("name-sharing bean limit"), "uchars zin: " + f.getMessage());

        BeslisserKern kern = BeslisserKern.bouw(NAAMDELING_DESCRIPTOR, bron, KernConfig.metMarkers(),
                Thread.currentThread().getContextClassLoader());
        BeslisserKern.Entrypoint ep = kern.entrypoint("maatdeling");
        assertFalse(ep.uitvoerbaar());
        assertTrue(ep.markers().isEmpty(), "uchars weigering noemt geen construct: " + ep.markers());
        assertTrue(ep.weigering().contains("name-sharing bean limit"), ep.weigering());
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
