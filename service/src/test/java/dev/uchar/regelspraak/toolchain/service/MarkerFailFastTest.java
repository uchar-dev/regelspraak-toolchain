package dev.uchar.regelspraak.toolchain.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-fast on marker rules (decision 3, the exit-3 discipline at the service boundary): a construct uchar's
 * exec-model branch refuses by name. This test drives a day count as the value of a target that is not
 * time-dependent, which that branch computes only as the timeline of a time-dependent target; the unresolved name
 * of the form it drove before uchar b536bbc9 now lowers to a rule that faults where the evaluation reaches it
 * (WF-044). A descriptor scoping it must refuse startup naming the rule when markers are disallowed, and start +
 * expose it when allowed.
 *
 * <p>The source is self-contained (its own Objecttype), so {@link DecisionCore} compiles its beans in memory
 * and needs no external fact classes.
 */
class MarkerFailFastTest {

    /** A self-contained model whose one rule uchar's exec-model branch refuses by name. */
    private static final String MARKER_SOURCE = """
        Objecttype de Vlucht (mv: Vluchten)
            de heffing Numeriek (niet-negatief geheel getal);
            is belast kenmerk (bijvoeglijk) voor elke dag;
        Regel heffing markert
        geldig altijd
        De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
        """;

    private static final String MARKER_DESCRIPTOR = """
        { "service": {"name":"marker-test","version":"1"},
          "entrypoints": [ { "name":"heffing markert","path":"heffing-markert",
            "scope": {"ruleNames": ["heffing markert"]},
            "input": ["Vlucht"], "output": ["Vlucht.heffing"] } ],
          "parameters": {} }""";

    private static Map<String, String> source() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("marker.rgs", MARKER_SOURCE);
        return m;
    }

    @Test
    void strictly_refuses_start_and_names_the_marker_rule() {
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(
                MARKER_DESCRIPTOR, source(), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
        assertTrue(f.getMessage().contains("heffing markert"), f.getMessage());
        assertTrue(f.violations().stream().anyMatch(s -> s.kind() == Violation.Kind.MARKER), f.getMessage());
    }

    @Test
    void with_markers_starts_and_exposes_the_marker() {
        DecisionCore core = DecisionCore.assemble(
                MARKER_DESCRIPTOR, source(), CoreConfig.withMarkers(),
                Thread.currentThread().getContextClassLoader());
        DecisionCore.Entrypoint ep = core.entrypoint("heffing-markert");
        assertFalse(ep.markers().isEmpty());
        assertTrue(ep.markers().contains("heffing markert"), ep.markers().toString());
    }

    /** A refusal of uchar's backend that names no construct: a reciprocal self-relation whose role shares its
     *  name with a declared attribute, which the bean model cannot hold. Until uchar carried the build this was a
     *  startup error even with markers allowed; it is uchar not carrying the entry point, like a marker. */
    private static final String NAME_SHARING_SOURCE = """
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

    private static final String NAME_SHARING_DESCRIPTOR = """
        { "service": {"name":"naamdeling","version":"1"},
          "entrypoints": [ { "name":"maatdeling","path":"maatdeling",
            "scope": {"ruleNames": ["maatdeling van een jutter"]},
            "input": ["Jutter"], "output": ["Jutter.maatdeling"] } ],
          "parameters": {} }""";

    @Test
    void a_refusal_without_construct_name_is_also_a_marker() {
        Map<String, String> source = Map.of("naamdeling.rgs", NAME_SHARING_SOURCE);
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(NAME_SHARING_DESCRIPTOR, source,
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()));
        assertTrue(f.violations().stream().allMatch(s -> s.kind() == Violation.Kind.MARKER), f.getMessage());
        assertTrue(f.getMessage().contains("name-sharing bean limit"), "uchars zin: " + f.getMessage());

        DecisionCore core = DecisionCore.assemble(NAME_SHARING_DESCRIPTOR, source, CoreConfig.withMarkers(),
                Thread.currentThread().getContextClassLoader());
        DecisionCore.Entrypoint ep = core.entrypoint("maatdeling");
        assertFalse(ep.executable());
        assertTrue(ep.markers().isEmpty(), "uchars weigering noemt geen construct: " + ep.markers());
        assertTrue(ep.refusal().contains("name-sharing bean limit"), ep.refusal());
    }

    @Test
    void a_lowered_rule_also_starts_strictly() {
        // 'bepaal leeftijd' lowers cleanly under DRL -> no marker -> strict start succeeds.
        String descriptor = """
            { "service": {"name":"ok","version":"1"},
              "entrypoints": [ { "name":"bepaal-leeftijd","path":"bepaal-leeftijd",
                "scope": {"ruleNames": ["bepaal leeftijd"]},
                "input": ["Vlucht","Natuurlijk persoon"], "output": ["Natuurlijk persoon.leeftijd"] } ],
              "parameters": {} }""";
        DecisionCore core = DecisionCore.assemble(descriptor, TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        assertTrue(core.entrypoint("bepaal-leeftijd").markers().isEmpty());
    }
}
