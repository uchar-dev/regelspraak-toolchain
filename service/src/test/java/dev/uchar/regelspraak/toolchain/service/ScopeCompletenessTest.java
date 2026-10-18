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
 * scenariotoetsen run on). {@link BeslisserKern#bouw} must refuse startup naming the gap, unless the entry point
 * declares the consumed element as an {@code invoerElementen} caller-input or opts the check out entirely.
 *
 * <p>The model is self-contained (its own Objecttype), so the core compiles its beans in memory and needs no
 * external fact classes.
 */
class ScopeVolledigheidTest {

    /** Two rules: "bepaal volwassen" ASSIGNS the kenmerk 'volwassen'; "bepaal korting" READS it in its
     *  condition and writes 'korting'. Scoping only the reader leaves the kenmerk's sole producer out of scope. */
    private static final String BRON = """
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

    private static Map<String, String> bron() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("scope.rgs", BRON);
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
    void producent_buiten_scope_weigert_start_en_noemt_het_kenmerk() {
        DienstFout f = assertThrows(DienstFout.class, () -> BeslisserKern.bouw(
                descriptor(""), bron(), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader()));
        assertTrue(f.schendingen().stream().anyMatch(s -> s.soort() == Schending.Soort.SCOPE_ONVOLLEDIG),
                f.getMessage() + " / " + f.schendingen());
        assertTrue(f.schendingen().stream().anyMatch(s -> s.bericht().contains("volwassen")),
                f.schendingen().toString());
        assertTrue(f.schendingen().stream().anyMatch(s -> s.bericht().contains("bepaal volwassen")),
                f.schendingen().toString());
    }

    @Test
    void producent_in_scope_start_schoon() {
        // scope BOTH rules -> the kenmerk's producer is in scope -> no violation.
        String d = """
            { "dienst": {"naam":"scope-ok","versie":"1"},
              "entrypoints": [ { "naam":"bepaal korting","pad":"bepaal-korting",
                "scope": {"regelnamen": ["bepaal korting", "bepaal volwassen"]},
                "invoer": ["Persoon"], "uitvoer": ["Persoon.korting"] } ],
              "parameters": {} }""";
        BeslisserKern kern = BeslisserKern.bouw(d, bron(), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader());
        assertTrue(kern.entrypoint("bepaal-korting") != null);
    }

    @Test
    void invoerElement_declaratie_start_schoon() {
        // declare the consumed kenmerk as a caller-supplied invoerElement -> the missing producer is intentional.
        BeslisserKern kern = BeslisserKern.bouw(
                descriptor(", \"invoerElementen\": [\"Persoon:volwassen\"]"), bron(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
        assertTrue(kern.entrypoint("bepaal-korting") != null);
    }
}
