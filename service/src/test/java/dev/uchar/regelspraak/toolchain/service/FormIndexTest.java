package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The forms of a model by the names a descriptor scope uses: over the real TOKA sources, and over a small model with
 *  a decision table, a rule group and an exception block (a defaults group). */
class FormIndexTest {

    private static final String KLEIN = """
        Objecttype het Aanspoelsel (mv: Aanspoelsels)
            de soort Tekst;
            de rubriek Tekst;
            de stuks Numeriek (geheel getal);
            is gekeurd kenmerk (bijvoeglijk);
            is afgekeurd kenmerk (bijvoeglijk);
            is uitgesloten kenmerk (bijvoeglijk);
            is weegplichtig kenmerk (bijvoeglijk);

        Beslistabel rubriek van een aanspoelsel
            geldig altijd
        | | de rubriek van een Aanspoelsel moet gesteld worden op | indien zijn soort gelijk is aan |
        |---|---|---|
        | 1 | "organisch" | "zeewier" |
        | 2 | "overig" | "wrakhout" |

        Regel uitsluiting
            geldig altijd
                Een Aanspoelsel is uitgesloten
                indien hij afgekeurd is.

        Uitzondering op uitsluiting: gekeurd
            geldig altijd
                Een Aanspoelsel is niet uitgesloten
                indien hij gekeurd is.

        Regelgroep weging
        Regel weegplicht
            geldig altijd
                Een Aanspoelsel is weegplichtig
                indien zijn stuks groter is dan 10.
        """;

    /** The forms a scope of the one rule name {@code name} carries. */
    private static List<String> formsOf(FormIndex index, String name) {
        return index.scope(new ServiceDescriptor.Scope(List.of(name), null)).forms();
    }

    private static FormIndex klein() {
        RegelSpraakModel m = Resolver.resolve(KLEIN);
        assertTrue(m.isOk(), m.errors().toString());
        return FormIndex.of(m, m.prioritizedDefaults);
    }

    @Test
    void toka_rules_carry_their_form() {
        RegelSpraakModel m = TokaSource.model();
        FormIndex index = FormIndex.of(m, m.prioritizedDefaults);
        assertEquals(1, formsOf(index, "bepaal leeftijd").size(), index.all().toString());
        assertEquals(1, formsOf(index, "Totaal te betalen belasting").size(), index.all().toString());
        // toka-45 is NOT in the served set since besluit 16Y (see TokaSource), so its rule is not nameable
        assertFalse(index.exists("belasting op basis van afstand"),
                "toka-45 hoort sinds besluit 16Y niet in de geserveerde bronset te zitten");
        // a decision table's rows are forms of their own, and the table's name carries all three
        assertEquals(1, formsOf(index, "Woonregio factor 1").size());
        assertEquals(3, formsOf(index, "Woonregio factor").size(), index.all().toString());
        assertFalse(index.exists("bestaat niet"));
    }

    @Test
    void a_table_name_carries_its_rows_and_a_row_itself() {
        FormIndex index = klein();
        assertEquals(List.of("rubriek van een aanspoelsel 1", "rubriek van een aanspoelsel 2"),
                formsOf(index, "rubriek van een aanspoelsel"));
        assertEquals(List.of("rubriek van een aanspoelsel 2"), formsOf(index, "Rubriek van een  aanspoelsel 2"),
                "een naam vergelijkt zoals uchars scenariolaag: spaties samengevoegd, hoofdletters gelijk");
    }

    @Test
    void a_base_rule_with_exception_is_a_defaults_group_and_the_exception_has_no_form() {
        FormIndex index = klein();
        assertEquals(List.of("uitsluiting"), formsOf(index, "uitsluiting"));
        assertTrue(index.exists("gekeurd"), "de uitzondering is een gedeclareerde regel");
        assertTrue(formsOf(index, "gekeurd").isEmpty(), "zij gaat op in de defaultsgroep van haar basisregel");
        ScenarioResolver.ScopeForms choice = index.scope(new ServiceDescriptor.Scope(List.of("gekeurd"), null));
        assertTrue(choice.forms().isEmpty(), choice.toString());
        assertEquals(List.of("gekeurd"), choice.withoutForm());
    }

    @Test
    void a_rule_group_carries_the_forms_of_its_members() {
        ScenarioResolver.ScopeForms choice = klein().scope(new ServiceDescriptor.Scope(null, List.of("weging")));
        assertEquals(List.of("weegplicht"), choice.forms());
        assertTrue(choice.withoutForm().isEmpty(), choice.toString());
    }

    @Test
    void a_rule_group_is_named_case_insensitively() {
        FormIndex index = klein();
        ScenarioResolver.ScopeForms choice = index.scope(new ServiceDescriptor.Scope(null, List.of("Weging")));
        assertEquals(List.of("weegplicht"), choice.forms());
        assertTrue(choice.unknownGroups().isEmpty(), choice.toString());
        RegelSpraakModel m = Resolver.resolve(KLEIN);
        ServiceDescriptor d = new ServiceDescriptor(new ServiceDescriptor.Service("klein", "1"), List.of(
                new ServiceDescriptor.Entrypoint("w", "w", new ServiceDescriptor.Scope(null, List.of("WEGING")),
                        List.of("Aanspoelsel"), List.of("Aanspoelsel:weegplichtig"), null, false, null, null)),
                null, null);
        assertEquals(List.of(), DescriptorLoader.validate(d, m, index));
    }

    /** Rules named 'premie 1' and 'premie 2' are no decision table: a scope that names 'premie' names no rule, as
     *  uchar's scenario resolver reads it. */
    @Test
    void numbered_rules_are_no_table_rows() {
        RegelSpraakModel m = Resolver.resolve("""
            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (getal met 2 decimalen);
                de premie Numeriek (getal met 2 decimalen);
                de toeslag Numeriek (getal met 2 decimalen);

            Regel premie 1
                geldig altijd
                    De premie van een Vondst moet berekend worden als zijn gewicht maal 2.

            Regel premie 2
                geldig altijd
                    De toeslag van een Vondst moet berekend worden als zijn gewicht maal 3.
            """);
        assertTrue(m.isOk(), m.errors().toString());
        FormIndex index = FormIndex.of(m, m.prioritizedDefaults);
        assertFalse(index.exists("premie"));
        assertEquals(List.of("premie"), index.scope(new ServiceDescriptor.Scope(List.of("premie"), null)).withoutForm());
        assertEquals(List.of("premie 1"), formsOf(index, "premie 1"));
        ServiceDescriptor d = new ServiceDescriptor(new ServiceDescriptor.Service("rows", "1"), List.of(
                new ServiceDescriptor.Entrypoint("p", "p", new ServiceDescriptor.Scope(List.of("premie"), null),
                        List.of("Vondst"), List.of("Vondst.premie", "Vondst.toeslag"), null, false, null, null)),
                null, null);
        List<Violation> violations = DescriptorLoader.validate(d, m, index);
        assertEquals(1, violations.size(), violations.toString());
        assertEquals(Violation.Kind.UNKNOWN_REFERENCE, violations.get(0).kind());
        assertEquals("entrypoints[0].scope.ruleNames[0]", violations.get(0).field());
    }
}
