package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defaults-group half of {@link ScopeCompletenessTest}: a defaults group the scope carries reads what it reads
 * in the scope too. A rule with an {@code Uitzondering op}, an {@code Afwijking van} or an {@code Uitzonderingen
 * ongeordend} block above it is no ordinary rule after the lowering but a defaults group, and the opgave marker and
 * an exception for a subtype generate one. When such a group reads an attribute or characteristic that only a form
 * outside the scope writes, the service used to start and decide the group on an empty reading: 10 where the whole
 * model gives 5, {@code null} where it gives a date. {@link DecisionCore#assemble} now refuses that start, naming the
 * group as its author wrote it (the rule the group belongs to, or the generated group by its name), the element, the
 * default that reads it (its path in the group and the text of the part that reads) and the writers outside the scope;
 * an {@code inputElements} declaration and {@code scopeCheckOptOut} still opt out.
 *
 * <p>Every source is self-contained Jutterswet material, so the core compiles its beans in memory.
 */
class ScopeDefaultsGroupTest {

    private static final String TIME = """
        Eenheidsysteem Tijd
            de dag dg
            de week wk = 7 dg
            de maand mnd
            het jaar jr = 12 mnd

        """;

    private static final String BERGDAG = """

        Regel bergdag
            geldig altijd
                De bergdag van een Jutter moet berekend worden als zijn vinddag plus 3 dg.
        """;

    /** R1/R2: the exception's justification reads 'bergdag', which an ordinary rule writes. */
    private static final String EXCEPTION = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de premie Numeriek (geheel getal);
        """ + BERGDAG + """

        Regel premie
            geldig altijd
                De premie van een Jutter moet gesteld worden op 10.

        Uitzondering op premie: late berging
            geldig altijd
                De premie van een Jutter moet gesteld worden op 5
                indien zijn bergdag later is dan 01-06-2026.
        """;

    /** R3: the base default's consequence reads 'bergdag'. */
    private static final String DEVIATION = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de gemelde meldtermijn Datum in dagen;
            de meldtermijn Datum in dagen;
        """ + BERGDAG + """

        Regel meldtermijn
            geldig altijd
                De meldtermijn van een Jutter moet berekend worden als zijn bergdag plus 6 mnd.

        Afwijking van meldtermijn: gemelde meldtermijn gaat voor
            geldig altijd
                De meldtermijn van een Jutter moet gesteld worden op zijn gemelde meldtermijn
                indien zijn gemelde meldtermijn gevuld is.
        """;

    /** R4: the opgave marker generates 'opgave boven berekening meldtermijn', which reads 'berekende meldtermijn'. */
    private static final String INPUT = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de meldtermijn Datum in dagen (mag opgegeven worden);
        """ + BERGDAG + """

        Regel meldtermijn
            geldig altijd
                De meldtermijn van een Jutter moet berekend worden als zijn bergdag plus 6 mnd.
        """;

    /** R5: the exception checks the characteristic 'laatgeborgen', which an ordinary rule attributes. */
    private static final String KENMERK = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de premie Numeriek (geheel getal);
            laatgeborgen kenmerk (bijvoeglijk);

        Regel laatgeborgen
            geldig altijd
                Een Jutter is laatgeborgen
                indien zijn vinddag later is dan 01-06-2026.

        Regel premie
            geldig altijd
                De premie van een Jutter moet gesteld worden op 10.

        Uitzondering op premie: late berging
            geldig altijd
                De premie van een Jutter moet gesteld worden op 5
                indien hij laatgeborgen is.
        """;

    /** R6 and R7: 'bergdag' is a group of its own; the reader of it is a group (R6) or an ordinary rule (R7). */
    private static final String BERGDAG_GROUP = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de gemelde bergdag Datum in dagen;
            de bergdag Datum in dagen;
            de gemelde meldtermijn Datum in dagen;
            de meldtermijn Datum in dagen;
        """ + BERGDAG + """

        Afwijking van bergdag: gemelde bergdag gaat voor
            geldig altijd
                De bergdag van een Jutter moet gesteld worden op zijn gemelde bergdag
                indien zijn gemelde bergdag gevuld is.

        Regel meldtermijn
            geldig altijd
                De meldtermijn van een Jutter moet berekend worden als zijn bergdag plus 6 mnd.
        """;
    private static final String GROUP_READS_GROUP = BERGDAG_GROUP + """

        Afwijking van meldtermijn: gemelde meldtermijn gaat voor
            geldig altijd
                De meldtermijn van een Jutter moet gesteld worden op zijn gemelde meldtermijn
                indien zijn gemelde meldtermijn gevuld is.
        """;

    /** R7: the same chain with an ordinary rule as the reader, which the check always refused. */
    private static final String ORDINARY = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de meldtermijn Datum in dagen;
        """ + BERGDAG + """

        Regel meldtermijn
            geldig altijd
                De meldtermijn van een Jutter moet berekend worden als zijn bergdag plus 6 mnd.
        """;

    /** Every form of the dialect that lowers to a group, on the Jutter of R1: the reader is the group. */
    private static final String JUTTER = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de premie Numeriek (geheel getal);
        """ + BERGDAG;
    private static final String PREMIE_10 = """

        Regel premie
            geldig altijd
                De premie van een Jutter moet gesteld worden op 10.
        """;
    private static final String UNORDERED_ON = JUTTER + PREMIE_10 + """

        Uitzonderingen ongeordend op premie:
        Regel premie bij late berging
            geldig altijd
                De premie van een Jutter moet gesteld worden op 5
                indien zijn bergdag later is dan 01-06-2026.

        Regel premie bij oude vinddag
            geldig altijd
                De premie van een Jutter moet gesteld worden op 12
                indien zijn vinddag eerder is dan 01-01-2026.
        """;
    private static final String UNORDERED_WITHOUT_CARRIER = JUTTER + """

        Uitzonderingen ongeordend:
        Regel premie bij late berging
            geldig altijd
                De premie van een Jutter moet gesteld worden op 5
                indien zijn bergdag later is dan 01-06-2026.

        Regel premie bij vroege berging
            geldig altijd
                De premie van een Jutter moet gesteld worden op 10
                indien zijn bergdag eerder is dan 01-06-2026.
        """;
    private static final String SUBTYPE = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de premie Numeriek (geheel getal);

        Objecttype de Strandjutter is een Jutter (mv: Strandjutters)
            de strook Tekst;
        """ + BERGDAG + PREMIE_10 + """

        Uitzondering op premie: strandberging
            geldig altijd
                De premie van een Strandjutter moet gesteld worden op 7
                indien zijn bergdag later is dan 01-06-2026.
        """;
    private static final String NESTED = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de windkracht Numeriek (geheel getal);
            de premie Numeriek (geheel getal);
            is stormberger kenmerk (bijvoeglijk);
        """ + BERGDAG + """

        Regel stormberger
            geldig altijd
                Een Jutter is stormberger
                indien zijn windkracht groter is dan 8.
        """ + PREMIE_10 + """

        Uitzondering op premie: late berging
            geldig altijd
                De premie van een Jutter moet gesteld worden op 5
                indien zijn bergdag later is dan 01-06-2026.

        Uitzondering op late berging: stormberging
            geldig altijd
                De premie van een Jutter moet gesteld worden op 8
                indien hij stormberger is.
        """;
    private static final String DECISION_TABLE = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergklasse Tekst;
            de premie Numeriek (geheel getal);

        Beslistabel bergklasse
            geldig altijd
        | | de bergklasse van een Jutter moet gesteld worden op | indien zijn vinddag later is dan |
        |---|---|---|
        | 1 | "laat" | 28-05-2026 |
        """ + PREMIE_10 + """

        Uitzondering op premie: late klasse
            geldig altijd
                De premie van een Jutter moet gesteld worden op 5
                indien zijn bergklasse gelijk is aan "laat".
        """;
    private static final String CONSEQUENCE = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergloon Numeriek (geheel getal);
            de premie Numeriek (geheel getal);

        Regel bergloon
            geldig altijd
                De bergloon van een Jutter moet gesteld worden op 7.
        """ + PREMIE_10 + """

        Afwijking van premie: premie naar bergloon
            geldig altijd
                De premie van een Jutter moet gesteld worden op zijn bergloon
                indien zijn vinddag gevuld is.
        """;

    /** Reads over roles: a group on Vondst reads a member of the Jutter of the find, or of that Jutter's Haven. */
    private static final String VONDST = TIME + """
        Objecttype de Haven (mv: Havens)
            de aanvraagdag Datum in dagen;
            de openingsdag Datum in dagen;
            is gesloten kenmerk (bijvoeglijk);

        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            is laatgeborgen kenmerk (bijvoeglijk);

        Objecttype de Vondst (mv: Vondsten)
            de premie Numeriek (geheel getal);

        Feittype JutterHeeftVondst
            de jutter Jutter
            de vondst (mv: vondsten) Vondst
        Eén jutter heeft meerdere vondsten

        Feittype HavenHeeftJutter
            de haven Haven
            de havenjutter (mv: havenjutters) Jutter
        Eén haven heeft meerdere havenjutters
        """ + BERGDAG + """

        Regel laatgeborgen
            geldig altijd
                Een Jutter is laatgeborgen
                indien zijn vinddag later is dan 01-06-2026.

        Regel openingsdag
            geldig altijd
                De openingsdag van een Haven moet berekend worden als zijn aanvraagdag plus 1 mnd.

        Regel sluiting
            geldig altijd
                Een Haven is gesloten
                indien zijn aanvraagdag later is dan 01-05-2026.
        """;
    private static final String VONDSTPREMIE = """

        Regel premie van een vondst
            geldig altijd
                De premie van een Vondst moet gesteld worden op 10.

        Uitzondering op premie van een vondst: late vondst
            geldig altijd
                De premie van een Vondst moet gesteld worden op 5
                indien %s.
        """;

    /** One base on Jutter and the own exceptions of three subtypes, a grandchild among them, each reading only request
     *  input. uchar lowers them to the groups 'premie voor Strandjutter', 'premie voor Barnsteenjutter' and 'premie
     *  voor Wrakjutter', each over the inherited base (RUNTIME-SEMANTICS ch. 7.10). */
    private static final String INHERITANCE_LINE = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de premie Numeriek (geheel getal);

        Objecttype de Strandjutter is een Jutter (mv: Strandjutters)
            de strook Tekst;

        Objecttype de Barnsteenjutter is een Strandjutter (mv: Barnsteenjutters)
            de vindplaats Tekst;

        Objecttype de Wrakjutter is een Jutter (mv: Wrakjutters)
            de wrak Tekst;
        """ + PREMIE_10 + """

        Uitzondering op premie: strandberging
            geldig altijd
                De premie van een Strandjutter moet gesteld worden op 7
                indien zijn vinddag later is dan 28-05-2026.

        Uitzondering op premie: barnsteenberging
            geldig altijd
                De premie van een Barnsteenjutter moet gesteld worden op 9
                indien zijn vinddag later is dan 28-05-2026.

        Uitzondering op premie: wrakberging
            geldig altijd
                De premie van een Wrakjutter moet gesteld worden op 6
                indien zijn vinddag later is dan 28-05-2026.
        """;

    /** What the whole model gives each type, found on 30-05-2026 (an own exception decides) and on 01-03-2026 (the
     *  base decides). */
    private static final Map<String, Integer> INHERITANCE_LINE_FULL = Map.of("J1", 10, "S1", 7, "B1", 9, "W1", 6,
            "J2", 10, "S2", 10, "B2", 10, "W2", 10);

    /** The base reads 'bergdag'; the Strandjutter's own exception (an Afwijking, which keeps only its own condition)
     *  reads request input. */
    private static final String BASE_READS = TIME + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de premie Numeriek (geheel getal);

        Objecttype de Strandjutter is een Jutter (mv: Strandjutters)
            de strook Tekst;
        """ + BERGDAG + """

        Regel premie
            geldig altijd
                De premie van een Jutter moet gesteld worden op 10
                indien zijn bergdag gevuld is.

        Afwijking van premie: strandberging
            geldig altijd
                De premie van een Strandjutter moet gesteld worden op 7
                indien zijn vinddag later is dan 28-05-2026.
        """;

    /** Where the groups above read 'bergdag' and 'stormberger', up to the path of the default. */
    private static final String BERGDAG_LATER = "in de rechtvaardiging 'zijn bergdag later is dan 01-06-2026' van default ";
    private static final String STORM =
            "in de rechtvaardiging 'zijn bergdag later is dan 01-06-2026 en hij stormberger is' van default ";

    private static final String OUTSIDE_THE_SCOPE =
            " vallen buiten de scope en het element is niet als invoerElement gedeclareerd";

    // ---------------- R1, R3, R4, R5, R6: a group the scope carries reads what only a form outside it writes ----------------

    @Test
    void r1_the_justification_of_an_exception_reads_an_attribute_from_outside_the_scope() {
        assertEquals(List.of("defaults-groep van regel 'premie' in entrypoint 'premie' leest attribuut 'bergdag' van "
                        + "'jutter' in de rechtvaardiging 'zijn bergdag later is dan 01-06-2026' van default [0], maar de "
                        + "enige producent(en) [bergdag]" + OUTSIDE_THE_SCOPE),
                scopeViolations(EXCEPTION, descriptor("premie", List.of("premie"), "Jutter.premie", "")));
    }

    @Test
    void r3_the_consequence_of_the_base_under_a_deviation_reads_from_outside_the_scope() {
        assertEquals(List.of("defaults-groep van regel 'meldtermijn' in entrypoint 'meldtermijn' leest attribuut "
                        + "'bergdag' van 'jutter' in de consequentie 'zijn bergdag plus 6 mnd' van default [1], maar de "
                        + "enige producent(en) [bergdag]" + OUTSIDE_THE_SCOPE),
                scopeViolations(DEVIATION, descriptor("meldtermijn", List.of("meldtermijn"), "Jutter.meldtermijn", "")));
    }

    @Test
    void r4_the_group_of_the_input_marking_names_itself_by_its_generated_name() {
        assertEquals(List.of("gegenereerde defaults-groep 'opgave boven berekening meldtermijn' in entrypoint "
                        + "'meldtermijn' leest attribuut 'berekende meldtermijn' van 'jutter' in de consequentie 'zijn "
                        + "berekende meldtermijn' van default [1], maar de enige producent(en) [meldtermijn]"
                        + OUTSIDE_THE_SCOPE),
                scopeViolations(INPUT, descriptor("meldtermijn", List.of("opgave boven berekening meldtermijn"),
                        "Jutter.meldtermijn", "")));
    }

    @Test
    void r5_a_characteristic_check_in_the_justification_reads_a_characteristic_from_outside_the_scope() {
        assertEquals(List.of("defaults-groep van regel 'premie' in entrypoint 'premie' leest kenmerk 'laatgeborgen' "
                        + "van 'jutter' in de rechtvaardiging 'hij laatgeborgen is' van default [0], maar de enige "
                        + "producent(en) [laatgeborgen]" + OUTSIDE_THE_SCOPE),
                scopeViolations(KENMERK, descriptor("premie", List.of("premie"), "Jutter.premie", "")));
    }

    @Test
    void r6_a_group_reads_what_another_group_writes_outside_the_scope() {
        assertEquals(List.of("defaults-groep van regel 'meldtermijn' in entrypoint 'meldtermijn' leest attribuut "
                        + "'bergdag' van 'jutter' in de consequentie 'zijn bergdag plus 6 mnd' van default [1], maar de "
                        + "enige producent(en) [bergdag]" + OUTSIDE_THE_SCOPE),
                scopeViolations(GROUP_READS_GROUP,
                        descriptor("meldtermijn", List.of("meldtermijn"), "Jutter.meldtermijn", "")));
    }

    // ---------------- the other forms of a group, and the reads over roles ----------------

    @Test
    void a_member_of_unordered_exceptions_on_a_base_rule() {
        assertEquals(List.of(group("defaults-groep van regel 'premie'", "attribuut 'bergdag' van 'jutter'",
                        BERGDAG_LATER + "[0, 0, 0]", "[bergdag]")),
                scopeViolations(UNORDERED_ON, descriptor("premie", List.of("premie"), "Jutter.premie", "")));
    }

    @Test
    void exceptions_unordered_without_carrier_names_the_group_of_the_member() {
        // every member is a group of its own; the element is reported once, at the first group that reads it
        assertEquals(List.of(group("defaults-groep van regel 'premie bij late berging'", "attribuut 'bergdag' van 'jutter'",
                        BERGDAG_LATER + "[0]", "[bergdag]")),
                scopeViolations(UNORDERED_WITHOUT_CARRIER, descriptor("premie",
                        List.of("premie bij late berging", "premie bij vroege berging"), "Jutter.premie", "")));
    }

    @Test
    void the_own_exception_of_a_subtype_is_a_generated_group() {
        assertEquals(List.of(group("gegenereerde defaults-groep 'premie voor Strandjutter'",
                        "attribuut 'bergdag' van 'jutter'", BERGDAG_LATER + "[0, 0, 0]", "[bergdag]")),
                scopeViolations(SUBTYPE, descriptor("premie", List.of("premie", "premie voor Strandjutter"),
                        List.of("Strandjutter"), "Jutter.premie", "")));
        DecisionCore core = assemble(SUBTYPE, descriptor("premie", List.of("bergdag", "premie", "premie voor Strandjutter"),
                List.of("Strandjutter"), "Jutter.premie", ""));
        assertEquals(7, premie(core, "Strandjutter", "2026-05-30", null), "de eigen uitzondering van de Strandjutter");
    }

    @Test
    void a_nested_exception_reads_a_characteristic_from_outside_the_scope() {
        assertEquals(List.of(group("defaults-groep van regel 'premie'", "kenmerk 'stormberger' van 'jutter'",
                        STORM + "[0, 0, 0]", "[stormberger]")),
                scopeViolations(NESTED, descriptor("premie", List.of("bergdag", "premie"), "Jutter.premie", "")));
    }

    @Test
    void an_element_that_two_defaults_read_names_them_both() {
        assertEquals(List.of(
                        group("defaults-groep van regel 'premie'", "attribuut 'bergdag' van 'jutter'",
                                BERGDAG_LATER + "[0] en " + STORM + "[0, 0, 0]", "[bergdag]"),
                        group("defaults-groep van regel 'premie'", "kenmerk 'stormberger' van 'jutter'",
                                STORM + "[0, 0, 0]", "[stormberger]")),
                scopeViolations(NESTED, descriptor("premie", List.of("premie"), "Jutter.premie", "")));
    }

    @Test
    void the_producer_outside_the_scope_is_a_decision_table() {
        assertEquals(List.of(group("defaults-groep van regel 'premie'", "attribuut 'bergklasse' van 'jutter'",
                        "in de rechtvaardiging 'zijn bergklasse gelijk is aan \"laat\"' van default [0]", "[bergklasse 1]")),
                scopeViolations(DECISION_TABLE, descriptor("premie", List.of("premie"), "Jutter.premie", "")));
    }

    @Test
    void the_consequence_of_a_deviation_reads_from_outside_the_scope() {
        assertEquals(List.of(group("defaults-groep van regel 'premie'", "attribuut 'bergloon' van 'jutter'",
                        "in de consequentie 'zijn bergloon' van default [0]", "[bergloon]")),
                scopeViolations(CONSEQUENCE, descriptor("premie", List.of("premie"), "Jutter.premie", "")));
    }

    @Test
    void a_read_one_role_far() {
        assertEquals(List.of(group("defaults-groep van regel 'premie van een vondst'", "attribuut 'bergdag' van 'jutter'",
                        "in de rechtvaardiging 'de bergdag van de jutter van de Vondst later is dan 01-06-2026' van default "
                                + "[0]", "[bergdag]")),
                scopeViolations(VONDST + VONDSTPREMIE.formatted("de bergdag van de jutter van de Vondst later is dan "
                        + "01-06-2026"), vondstdescriptor()));
    }

    @Test
    void a_read_two_roles_far() {
        assertEquals(List.of(group("defaults-groep van regel 'premie van een vondst'", "attribuut 'openingsdag' van 'haven'",
                        "in de rechtvaardiging 'de openingsdag van de haven van de jutter van de Vondst later is dan "
                                + "01-06-2026' van default [0]", "[openingsdag]")),
                scopeViolations(VONDST + VONDSTPREMIE.formatted("de openingsdag van de haven van de jutter van de "
                        + "Vondst later is dan 01-06-2026"), vondstdescriptor()));
    }

    @Test
    void a_characteristic_check_one_role_far() {
        assertEquals(List.of(group("defaults-groep van regel 'premie van een vondst'", "kenmerk 'laatgeborgen' van 'jutter'",
                        "in de rechtvaardiging 'zijn jutter laatgeborgen is' van default [0]", "[laatgeborgen]")),
                scopeViolations(VONDST + VONDSTPREMIE.formatted("zijn jutter laatgeborgen is"), vondstdescriptor()));
    }

    @Test
    void a_read_that_uchar_does_not_carry_is_a_marker_and_no_stack_trace() {
        // A check two roles away that names only its last role: uchar's emitter refuses it, and its rule-graph walk
        // throws on it. The check runs after the build, so the entry point is a marker with uchar's sentence, as it
        // is for a group with this condition; it used to be an IllegalStateException from the walk.
        String source = VONDST + """

            Regel premie van een vondst
                geldig altijd
                    De premie van een Vondst moet gesteld worden op 5
                    indien de haven van zijn jutter gesloten is.
            """;
        ServiceError f = assertThrows(ServiceError.class, () -> assemble(source, vondstdescriptor()));
        assertTrue(f.violations().stream().allMatch(s -> s.kind() == Violation.Kind.MARKER),
                () -> "verwacht een marker: " + f.violations());
        assertTrue(f.violations().get(0).message().contains("navigeert via 'haven'"), () -> "" + f.violations());
    }

    // ---------------- a subtype's own exceptions go with their base (RUNTIME-SEMANTICS ch. 7.10) ----------------

    @Test
    void the_base_rule_carries_the_groups_of_its_subtypes() {
        assertEquals(INHERITANCE_LINE_FULL, erflijnPremies(List.of("premie")));
    }

    @Test
    void a_group_of_a_subtype_carries_its_base_and_the_other_groups_of_that_base() {
        assertEquals(INHERITANCE_LINE_FULL, erflijnPremies(List.of("premie voor Strandjutter")));
        assertEquals(INHERITANCE_LINE_FULL, erflijnPremies(List.of("premie voor Barnsteenjutter")));
    }

    @Test
    void who_names_the_generated_groups_nevertheless_gets_the_same() {
        assertEquals(INHERITANCE_LINE_FULL, erflijnPremies(List.of("premie", "premie voor Strandjutter",
                "premie voor Barnsteenjutter", "premie voor Wrakjutter")));
    }

    @Test
    void the_base_rule_also_carries_what_the_group_of_the_subtype_reads() {
        assertEquals(List.of(group("gegenereerde defaults-groep 'premie voor Strandjutter'",
                        "attribuut 'bergdag' van 'jutter'", BERGDAG_LATER + "[0, 0, 0]", "[bergdag]")),
                scopeViolations(SUBTYPE, descriptor("premie", List.of("premie"), List.of("Strandjutter"),
                        "Jutter.premie", "")));
    }

    @Test
    void the_group_of_the_subtype_also_carries_what_its_base_reads() {
        assertEquals(List.of(group("defaults-groep van regel 'premie'", "attribuut 'bergdag' van 'jutter'",
                        "in de rechtvaardiging 'zijn bergdag gevuld is' van default [0]", "[bergdag]")),
                scopeViolations(BASE_READS, descriptor("premie", List.of("premie voor Strandjutter"),
                        List.of("Strandjutter"), "Jutter.premie", "")));
    }

    // ---------------- the flanks: R2, R7 and the opt-outs ----------------

    @Test
    void r2_with_the_writer_in_the_scope_starts_the_service_and_decides_the_exception() {
        DecisionCore core = assemble(EXCEPTION,
                descriptor("premie", List.of("bergdag", "premie"), "Jutter.premie", ""));
        assertEquals(5, premie(core, "2026-05-30", null), "de bergdag 02-06-2026 valt na 01-06-2026");
        assertEquals(10, premie(core, "2026-03-01", null));
    }

    @Test
    void r7_an_ordinary_rule_as_reader_refuses_as_formerly() {
        String expected = "regel 'meldtermijn' in entrypoint 'meldtermijn' leest attribuut 'bergdag' van 'jutter', "
                + "maar de enige producent(en) [bergdag]" + OUTSIDE_THE_SCOPE;
        assertEquals(List.of(expected), scopeViolations(ORDINARY,
                descriptor("meldtermijn", List.of("meldtermijn"), "Jutter.meldtermijn", "")));
        // the producer index counts a group as a writer, so an ordinary rule that reads a group's outcome refuses too
        assertEquals(List.of(expected), scopeViolations(BERGDAG_GROUP,
                descriptor("meldtermijn", List.of("meldtermijn"), "Jutter.meldtermijn", "")));
    }

    @Test
    void the_input_element_lets_the_caller_supply_the_read_of_the_group() {
        DecisionCore core = assemble(EXCEPTION, descriptor("premie", List.of("premie"), "Jutter.premie",
                ", \"inputElements\": [\"Jutter.bergdag\"]"));
        assertEquals(5, premie(core, "2026-05-30", "2026-06-02"));
        assertEquals(10, premie(core, "2026-05-30", "2026-05-31"));
    }

    @Test
    void scopeCheckOptOut_lets_the_group_start_untested() {
        DecisionCore core = assemble(EXCEPTION, descriptor("premie", List.of("premie"), "Jutter.premie",
                ", \"scopeCheckOptOut\": true"));
        assertNotNull(core.entrypoint("premie"));
    }

    // ---------------- helpers ----------------

    private static String descriptor(String path, List<String> ruleNames, String output, String extra) {
        return descriptor(path, ruleNames, List.of("Jutter"), output, extra);
    }

    private static String descriptor(String path, List<String> ruleNames, List<String> input, String output,
                                     String extra) {
        return """
            { "service": {"name":"Jutterswet scopeproef","version":"1"},
              "entrypoints": [ { "name":"%s","path":"%s",
                "scope": {"ruleNames": [%s]},
                "input": [%s], "output": ["%s"]%s } ],
              "parameters": {} }""".formatted(path, path, list(ruleNames), list(input), output, extra);
    }

    private static String vondstdescriptor() {
        return descriptor("premie", List.of("premie van een vondst"), List.of("Haven", "Jutter", "Vondst"),
                "Vondst.premie", "");
    }

    private static String list(List<String> names) {
        return names.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(", "));
    }

    /** The violation of a group in the entry point 'premie'. */
    private static String group(String reader, String element, String where, String producers) {
        return reader + " in entrypoint 'premie' leest " + element + " " + where + ", maar de enige producent(en) "
                + producers + OUTSIDE_THE_SCOPE;
    }

    private static DecisionCore assemble(String source, String descriptor) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("jutterswet.rgs", source);
        return DecisionCore.assemble(descriptor, m, CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
    }

    /** The messages of the refusal, which must consist of scope violations only. */
    private static List<String> scopeViolations(String source, String descriptor) {
        ServiceError f = assertThrows(ServiceError.class, () -> assemble(source, descriptor),
                "de dienst hoort niet te starten op een scope die een lezing mist");
        assertTrue(f.violations().stream().allMatch(s -> s.kind() == Violation.Kind.INCOMPLETE_SCOPE),
                () -> "verwacht alleen INCOMPLETE_SCOPE: " + f.violations());
        return f.violations().stream().map(Violation::message).toList();
    }

    /** The premie the entry point 'premie' gives one Jutter with this vinddag (and bergdag, when given). */
    private static int premie(DecisionCore core, String vinddag, String bergdag) {
        return premie(core, "Jutter", vinddag, bergdag);
    }

    private static int premie(DecisionCore core, String type, String vinddag, String bergdag) {
        String attributes = "\"vinddag\": \"" + vinddag + "\""
                + (bergdag == null ? "" : ", \"bergdag\": \"" + bergdag + "\"");
        ObjectNode a = core.process("premie", parse("{ \"instances\": [ { \"type\": \"" + type
                + "\", \"id\": \"J1\", \"attributes\": { " + attributes + " } } ] }"));
        JsonNode premie = a.path("instances").path(0).path("attributes").get("premie");
        assertNotNull(premie, () -> "antwoord: " + a);
        assertTrue(premie.isNumber(), () -> "antwoord: " + a);
        return premie.intValue();
    }

    /** The premie of each Jutter of {@link #INHERITANCE_LINE}, one of every type found on 30-05-2026 and one on 01-03-2026,
     *  through an entry point whose scope names {@code ruleNames}; null where the answer has none. */
    private static Map<String, Integer> erflijnPremies(List<String> ruleNames) {
        DecisionCore core = assemble(INHERITANCE_LINE, descriptor("premie", ruleNames,
                List.of("Jutter", "Strandjutter", "Barnsteenjutter", "Wrakjutter"), "Jutter.premie", ""));
        StringBuilder instances = new StringBuilder();
        for (String type : List.of("Jutter", "Strandjutter", "Barnsteenjutter", "Wrakjutter"))
            for (String[] day : new String[][] { {"1", "2026-05-30"}, {"2", "2026-03-01"} })
                instances.append(instances.length() == 0 ? "" : ", ").append("{ \"type\": \"").append(type)
                        .append("\", \"id\": \"").append(type.charAt(0)).append(day[0])
                        .append("\", \"attributes\": { \"vinddag\": \"").append(day[1]).append("\" } }");
        ObjectNode a = core.process("premie", parse("{ \"instances\": [ " + instances + " ] }"));
        Map<String, Integer> sink = new LinkedHashMap<>();
        for (JsonNode i : a.path("instances")) {
            JsonNode premie = i.path("attributes").get("premie");
            sink.put(i.path("id").asText(), premie == null || premie.isNull() ? null : premie.intValue());
        }
        return sink;
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
