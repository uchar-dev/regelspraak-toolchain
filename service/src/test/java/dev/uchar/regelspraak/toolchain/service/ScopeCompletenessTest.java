package dev.uchar.regelspraak.toolchain.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scope-completeness validation at service build (the systemic defect: a descriptor allow-list scopes rules
 * without transitive expansion, so a scoped rule whose condition reads a kenmerk that ONLY a non-scoped rule
 * assigns evaluates that kenmerk silently as false — semantically diverging from the source concatenation the
 * scenariotoetsen run on). {@link DecisionCore#assemble} must refuse startup naming the gap, unless the entry point
 * declares the consumed element as an {@code inputElements} caller-input or opts the check out entirely.
 *
 * <p>The model is self-contained (its own Objecttype), so the core compiles its beans in memory and needs no
 * external fact classes.
 */
class ScopeCompletenessTest {

    /** Two rules: "bepaal volwassen" ASSIGNS the kenmerk 'volwassen'; "bepaal korting" READS it in its
     *  condition and writes 'korting'. Scoping only the reader leaves the kenmerk's sole producer out of scope. */
    private static final String SOURCE = """
        Objecttype de Persoon (mv: Personen)
            de leeftijd Numeriek (geheel getal);
            de korting Numeriek (getal met 2 decimalen) met eenheid EUR;
            het volwassen kenmerk (bijvoeglijk);
        Regel bepaal volwassen
        geldig altijd
        Een Persoon is volwassen indien de leeftijd van de Persoon groter is dan 17.
        Regel bepaal korting
        geldig altijd
        De korting van een Persoon moet gesteld worden op 5,00 EUR indien de Persoon volwassen is.
        """;

    private static Map<String, String> source() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("scope.rgs", SOURCE);
        return m;
    }

    private static String descriptor(String scopeExtra) {
        return """
            { "dienst": {"naam":"scope-test","versie":"1"},
              "entrypoints": [ { "naam":"bepaal korting","pad":"bepaal-korting",
                "scope": {"regelnamen": ["bepaal korting"]},
                "invoer": ["Persoon"], "uitvoer": ["Persoon.korting"]%s } ],
              "parameters": {} }""".formatted(scopeExtra);
    }

    @Test
    void producer_outside_scope_refuses_start_and_names_the_characteristic() {
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(
                descriptor(""), source(), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
        assertTrue(f.violations().stream().anyMatch(s -> s.kind() == Violation.Kind.SCOPE_ONVOLLEDIG),
                f.getMessage() + " / " + f.violations());
        assertTrue(f.violations().stream().anyMatch(s -> s.message().contains("volwassen")),
                f.violations().toString());
        assertTrue(f.violations().stream().anyMatch(s -> s.message().contains("bepaal volwassen")),
                f.violations().toString());
    }

    @Test
    void producer_in_scope_starts_clean() {
        // scope BOTH rules -> the kenmerk's producer is in scope -> no violation.
        String d = """
            { "dienst": {"naam":"scope-ok","versie":"1"},
              "entrypoints": [ { "naam":"bepaal korting","pad":"bepaal-korting",
                "scope": {"regelnamen": ["bepaal korting", "bepaal volwassen"]},
                "invoer": ["Persoon"], "uitvoer": ["Persoon.korting"] } ],
              "parameters": {} }""";
        DecisionCore core = DecisionCore.assemble(d, source(), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        assertTrue(core.entrypoint("bepaal-korting") != null);
    }

    @Test
    void input_element_declaration_starts_clean() {
        // declare the consumed kenmerk as a caller-supplied invoerElement -> the missing producer is intentional.
        DecisionCore core = DecisionCore.assemble(
                descriptor(", \"invoerElementen\": [\"Persoon:volwassen\"]"), source(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        assertTrue(core.entrypoint("bepaal-korting") != null);
    }
}
