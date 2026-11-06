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
            { "service": {"name":"scope-test","version":"1"},
              "entrypoints": [ { "name":"bepaal korting","path":"bepaal-korting",
                "scope": {"ruleNames": ["bepaal korting"]},
                "input": ["Persoon"], "output": ["Persoon.korting"]%s } ],
              "parameters": {} }""".formatted(scopeExtra);
    }

    @Test
    void producer_outside_scope_refuses_start_and_names_the_characteristic() {
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(
                descriptor(""), source(), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
        assertTrue(f.violations().stream().anyMatch(s -> s.kind() == Violation.Kind.INCOMPLETE_SCOPE),
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
            { "service": {"name":"scope-ok","version":"1"},
              "entrypoints": [ { "name":"bepaal korting","path":"bepaal-korting",
                "scope": {"ruleNames": ["bepaal korting", "bepaal volwassen"]},
                "input": ["Persoon"], "output": ["Persoon.korting"] } ],
              "parameters": {} }""";
        DecisionCore core = DecisionCore.assemble(d, source(), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        assertTrue(core.entrypoint("bepaal-korting") != null);
    }

    @Test
    void input_element_declaration_starts_clean() {
        // declare the consumed kenmerk as a caller-supplied invoerElement -> the missing producer is intentional.
        DecisionCore core = DecisionCore.assemble(
                descriptor(", \"inputElements\": [\"Persoon:volwassen\"]"), source(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        assertTrue(core.entrypoint("bepaal-korting") != null);
    }

    /** A cell is an element of its own: the sum over the seasons reads the cell najaar, which only the rule outside
     *  the scope writes, and the start is refused naming that cell; declaring the dimensioned attribute as an
     *  {@code inputElements} caller-input declares each of its cells. */
    @Test
    void a_cell_written_outside_the_scope_is_named_and_the_attribute_declares_its_cells() {
        String source = """
            Dimensie het seizoen, bestaande uit de seizoenen (na het attribuut met voorzetsel in):
                1. voorjaar
                2. najaar
            Objecttype de Jutter (mv: Jutters) (bezield)
                het vondstgewicht Numeriek (getal met 2 decimalen) gedimensioneerd met seizoen;
                het jaargewicht Numeriek (getal met 2 decimalen);
            Regel najaarsgewicht van een jutter
            geldig altijd
            Het vondstgewicht in najaar van een Jutter moet berekend worden als zijn vondstgewicht in voorjaar maal 2.
            Regel jaargewicht van een jutter
            geldig altijd
            Het jaargewicht van een Jutter moet berekend worden als de som van zijn vondstgewicht over alle seizoenen.
            """;
        String descriptor = """
            { "service": {"name":"cel","version":"1"},
              "entrypoints": [ { "name":"jaargewicht","path":"jaargewicht",
                "scope": {"ruleNames": ["jaargewicht van een jutter"]},
                "input": ["Jutter"], "output": ["Jutter.jaargewicht"]%s } ],
              "parameters": {} }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(descriptor.formatted(""),
                Map.of("cel.rgs", source), CoreConfig.strict(), Thread.currentThread().getContextClassLoader()));
        assertTrue(f.violations().size() == 1 && f.violations().get(0).kind() == Violation.Kind.INCOMPLETE_SCOPE
                && f.violations().get(0).message().contains("attribuut 'vondstgewicht[najaar]'")
                && f.violations().get(0).message().contains("najaarsgewicht van een jutter"), f.violations().toString());
        DecisionCore core = DecisionCore.assemble(descriptor.formatted(", \"inputElements\": [\"Jutter.vondstgewicht\"]"),
                Map.of("cel.rgs", source), CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        assertTrue(core.entrypoint("jaargewicht") != null);
    }

    /** A consistency reads as a rule does: with the writer of 'bergdag' outside the scope, the scoped build would
     *  check an empty 'bergdag' and report every Jutter inconsistent, where the full source reports none. */
    private static final String CONSISTENCY_SOURCE = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddatum Datum in dagen;
            de aanmelddatum Datum in dagen;
            de bergdag Datum in dagen;
        Regel bepaal bergdag
        geldig altijd
        De bergdag van een Jutter moet berekend worden als de vinddatum van de Jutter plus 2 dagen.
        Regel bergdagcontrole
        geldig altijd
        De bergdag van een Jutter moet later of gelijk zijn aan de aanmelddatum van de Jutter.
        """;

    private static String consistencyDescriptor(String extra) {
        return """
            { "service": {"name":"consistentie","version":"1"},
              "entrypoints": [ { "name":"bergdagcontrole","path":"bergdagcontrole",
                "scope": {"ruleNames": ["bergdagcontrole"]},
                "input": ["Jutter"], "output": []%s } ],
              "parameters": {} }""".formatted(extra);
    }

    private static DecisionCore consistencyCore(String extra) {
        return DecisionCore.assemble(consistencyDescriptor(extra), Map.of("consistentie.rgs", CONSISTENCY_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
    }

    @Test
    void a_consistency_reading_what_only_a_rule_outside_the_scope_writes_refuses_start() {
        ServiceError f = assertThrows(ServiceError.class, () -> consistencyCore(""));
        assertTrue(f.violations().size() == 1 && f.violations().get(0).kind() == Violation.Kind.INCOMPLETE_SCOPE,
                f.violations().toString());
        String message = f.violations().get(0).message();
        assertTrue(message.startsWith("consistentie 'bergdagcontrole' in entrypoint 'bergdagcontrole' leest attribuut"
                + " 'bergdag'") && message.contains("[bepaal bergdag]"), message);
    }

    @Test
    void the_opt_outs_hold_for_a_consistency() {
        assertTrue(consistencyCore(", \"inputElements\": [\"Jutter.bergdag\"]").entrypoint("bergdagcontrole") != null);
        assertTrue(consistencyCore(", \"scopeCheckOptOut\": true").entrypoint("bergdagcontrole") != null);
    }
}
