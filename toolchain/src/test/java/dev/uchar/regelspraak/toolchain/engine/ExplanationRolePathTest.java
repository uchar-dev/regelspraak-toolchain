package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An operand that reads over a role, "de herlevingsgrens van het recht van het Maandinkomen", stands on another object
 * than the subject. The explanation names the value on that object, the one the engine derived and wrote back, reached
 * as uchar's branch reads the operand: the walk of the read and the object it reaches come from uchar
 * ({@code TmsTranspiler.navigationBindings}, {@code DroolsBackend.reached}). Before, every such operand stood there as
 * "(onbekend)", also where the engine had derived it: of a characteristic the engine assigned, the explanation said
 * that it held because an unknown value was filled, and of one it withheld, that a filled value was not filled. Where
 * the value on the reached object is leeg, or no object is reached, "(onbekend)" stays.
 *
 * <p>The scenario route plays uchar's IR document, whose reads carry their walk beside them, in the build, and not on
 * them. Before, the explanation read such a read as the subject's own member: where the subject declares a member of
 * that name, the value of the subject stood in the sentence. The decision service, which explains on the model the
 * frontend resolved, is pinned in {@code ExplanationRolePathServiceTest}.
 */
class ExplanationRolePathTest {

    static final String MODEL = """
            Objecttype het Recht (mv: Rechten)
                het maandloon Numeriek (getal met 2 decimalen);
                de herlevingsgrens Numeriek (getal met 2 decimalen);

            Objecttype het Dagloon (mv: Daglonen)
                de maximeringsfactor Numeriek (getal);

            Objecttype het Maandinkomen (mv: Maandinkomens)
                het inkomen Numeriek (getal met 2 decimalen);
                de herlevingsgrens overschreden kenmerk (bezittelijk);

            Feittype maandinkomen van een recht
                het recht Recht
                het maandinkomen (mv: maandinkomens) Maandinkomen
            Eén recht heeft meerdere maandinkomens

            Feittype dagloon van een recht
                het recht Recht
                het dagloon Dagloon
            Eén recht heeft één dagloon

            Regel herlevingsgrens
                geldig altijd
                    De herlevingsgrens van een Recht moet berekend worden als (het maandloon van het Recht maal 0,875)
                    naar beneden afgerond op 2 decimalen
                    indien zijn maandloon gevuld is.

            Regel maximeringsfactor
                geldig altijd
                    De maximeringsfactor van een Dagloon moet gesteld worden op 1.

            Regel herlevingsgrens overschreden
                geldig altijd
                    Een Maandinkomen heeft de herlevingsgrens overschreden
                    indien hij aan alle volgende voorwaarden voldoet:
                    - zijn inkomen is gevuld
                    - de herlevingsgrens van het recht van het Maandinkomen is gevuld
                    - zijn inkomen maal de maximeringsfactor van het dagloon van het recht van het Maandinkomen is groter
                    dan de herlevingsgrens van het recht van het Maandinkomen.
            """;

    /** R1 has a herlevingsgrens of 2.625,00 and a dagloon D1 with factor 1; R2 has no maandloon, so no herlevingsgrens
     *  (the condition of the rule keeps a leeg maandloon from counting as 0). M1 and M2 are of R1, M3 of R2, and M4 is
     *  of no recht. */
    private static final String SITUATION = """

            Scenario vier maandinkomens
            Gegeven is de volgende situatie:
            een Recht R1
            - het maandloon is 3000,00
            een Dagloon D1
            - het recht is R1
            een Recht R2
            een Maandinkomen M1
            - het inkomen is 2800,00
            - het recht is R1
            een Maandinkomen M2
            - het inkomen is 1354,84
            - het recht is R1
            een Maandinkomen M3
            - het inkomen is 2800,00
            - het recht is R2
            een Maandinkomen M4
            - het inkomen is 2800,00
            Met als verwacht resultaat:
            - de herlevingsgrens van R1 is 2625,00
            - M1 heeft de herlevingsgrens overschreden
            - M2 heeft geen herlevingsgrens overschreden
            - M3 heeft geen herlevingsgrens overschreden
            - M4 heeft geen herlevingsgrens overschreden
            """;

    /** The first three sentences explain the computed values (card explanation-computed-attributes). */
    static final List<String> EXPECTED = List.of(
            "R1 heeft als herlevingsgrens 2.625,00 volgens de regel 'herlevingsgrens': (het maandloon van het Recht maal"
                    + " 0,875) naar beneden afgerond op 2 decimalen, met het maandloon van het Recht (3.000,00); vóór"
                    + " de afronding 2.625, omdat zijn maandloon (3.000,00) gevuld is",
            "D1 heeft als maximeringsfactor 1 volgens de regel 'maximeringsfactor' (onvoorwaardelijk): 1",
            "R2 heeft geen herlevingsgrens, want geen regel gold; de regel 'herlevingsgrens' gold niet, want niet geldt:"
                    + " zijn maandloon (onbekend) gevuld is",
            "M1 heeft de herlevingsgrens overschreden, omdat zijn inkomen (2.800,00) gevuld is en de herlevingsgrens van"
                    + " het recht van het Maandinkomen (2.625,00) gevuld is en zijn inkomen maal de maximeringsfactor van"
                    + " het dagloon van het recht van het Maandinkomen (2.800,00) is groter dan de herlevingsgrens van het"
                    + " recht van het Maandinkomen (2.625,00)",
            "M2 heeft geen herlevingsgrens overschreden, want niet geldt: zijn inkomen maal de maximeringsfactor van het"
                    + " dagloon van het recht van het Maandinkomen (1.354,84) is groter dan de herlevingsgrens van het"
                    + " recht van het Maandinkomen (2.625,00)",
            "M3 heeft geen herlevingsgrens overschreden, want niet geldt: de herlevingsgrens van het recht van het"
                    + " Maandinkomen (onbekend) gevuld is",
            "M4 heeft geen herlevingsgrens overschreden, want niet geldt: de herlevingsgrens van het recht van het"
                    + " Maandinkomen (onbekend) gevuld is");

    private static List<String> explanation(String document) {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(document));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        return u.get(0).explanation();
    }

    @Test
    void an_operand_over_a_role_names_the_value_the_engine_derived_on_the_object_it_reaches() {
        assertEquals(EXPECTED, explanation(MODEL + SITUATION));
    }

    /** The Maandinkomen declares a herlevingsgrens of its own, and M2 has one of 1,00. The operand reads the one of the
     *  recht: before, the explanation read M2's own and said of M2 that the comparison could not be determined. */
    @Test
    void an_operand_over_a_role_does_not_read_the_member_of_the_same_name_on_the_subject() {
        String model = MODEL.replace("""
                    het inkomen Numeriek (getal met 2 decimalen);
                """, """
                    het inkomen Numeriek (getal met 2 decimalen);
                    de herlevingsgrens Numeriek (getal met 2 decimalen);
                """);
        String situation = SITUATION.replace("""
                - het inkomen is 1354,84
                """, """
                - het inkomen is 1354,84
                - de herlevingsgrens is 1,00
                """);
        assertNotEquals(MODEL, model);
        assertNotEquals(SITUATION, situation);
        assertEquals(EXPECTED, explanation(model + situation));
    }
}
