package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Card explanation-defaults-computation on the scenario route: the variant that decides in a defaults group on an
 * attribute gets, after its name, the same computation an ordinary rule gets (card explanation-computed-attributes),
 * from the engine's reading of that variant: the nodes of its value follow those of its condition (uchar's
 * RUNTIME-SEMANTICS ch. 5.12). The other variants of the stack stay as they were. The model and the sentences are those
 * of {@code ExplanationDefaultsComputationServiceTest}, which gives the same sentences on both starts of the decision
 * service; here the situation and the parameter stand in the scenario.
 */
class ExplanationDefaultsComputationTest {

    /** Three groups on an attribute. The premie of a Jutter: the base 'premie' without a condition, and the exception
     *  'premie uit bonus' with an expression of its own (a percentage, rounded down). The waarde of a Vondst carries
     *  the opgave marker: the opgave, the computation 'berekende waarde' that the rule 'waarde' writes, and the
     *  initialisation, without a condition. The uitkering of a Wrakrecht: its own rule without a condition replaces the
     *  inherited base below the exception 'betwiste uitkering' (ch. 7.10), so it decides as a rule. */
    static final String MODEL = """
            Parameter het percentage premie : Percentage (getal met 1 decimalen)

            Objecttype de Jutter (mv: Jutters)
                het aantal vondsten Numeriek (geheel getal);
                de bonus Numeriek (getal met 2 decimalen);
                de premie Numeriek (getal met 2 decimalen);
                is ingezetene kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                de taxatie Numeriek (getal met 2 decimalen);
                de bergingskosten Numeriek (getal met 2 decimalen);
                de waarde Numeriek (getal met 2 decimalen) (mag opgegeven worden);

            Objecttype het Recht (mv: Rechten)
                de grondslag Numeriek (getal met 2 decimalen);
                de uitkering Numeriek (getal met 2 decimalen);
                is betwist kenmerk (bijvoeglijk);

            Objecttype het Wrakrecht is een Recht (mv: Wrakrechten)
                is gezonken kenmerk (bijvoeglijk);

            // Bron: Jutterswet art. 12
            Regel premie
                geldig altijd
                    De premie van een Jutter moet berekend worden als zijn aantal vondsten maal 10,00.

            Uitzondering op premie: premie uit bonus
                geldig altijd
                    De premie van een Jutter moet berekend worden als (het percentage premie van zijn bonus) naar beneden afgerond op 2 decimalen
                    indien hij ingezetene is.

            Regel waarde
                geldig altijd
                    De waarde van een Vondst moet berekend worden als zijn taxatie plus zijn bergingskosten
                    indien zijn taxatie gevuld is.

            Regel waarde bij onbekende taxatie
                geldig altijd
                    De waarde van een Vondst moet geïnitialiseerd worden op 25,00.

            Regel uitkering
                geldig altijd
                    De uitkering van een Recht moet gesteld worden op zijn grondslag.

            Uitzondering op uitkering: betwiste uitkering
                geldig altijd
                    De uitkering van een Recht moet gesteld worden op 0,00
                    indien hij betwist is.

            Regel uitkering van een wrakrecht
                geldig altijd
                    De uitkering van een Wrakrecht moet berekend worden als zijn grondslag maal 2.
            """;

    /** J1: the base decides, J2 the exception. V1: the computation decides, V2 the opgave, V3 the initialisation. W1:
     *  the rule of the subtype decides, W2 the exception. */
    private static final String SCENARIO = """

            Parameterset premie:
            de parameter het percentage premie is 12,5 %

            Scenario drie groepen
            met parameterset premie
            Gegeven is de volgende situatie:
            een Jutter J1
            - het aantal vondsten is 3
            een Jutter J2
            - het aantal vondsten is 3
            - de bonus is 123,45
            - is ingezetene
            een Vondst V1
            - de taxatie is 100,00
            - de bergingskosten is 10,00
            een Vondst V2
            - de opgegeven waarde is 80,00
            een Vondst V3
            een Wrakrecht W1
            - de grondslag is 10,00
            een Wrakrecht W2
            - de grondslag is 10,00
            - is betwist
            Met als verwacht resultaat:
            - de premie van J1 is 30,00
            - de premie van J2 is 15,43
            - de waarde van V1 is 110,00
            - de waarde van V2 is 80,00
            - de waarde van V3 is 25,00
            - de uitkering van W1 is 20,00
            - de uitkering van W2 is 0,00
            """;

    private static final String PREMIE = "zijn aantal vondsten maal 10,00, met zijn aantal vondsten";
    private static final String UIT_BONUS = "(het percentage premie van zijn bonus) naar beneden afgerond op 2"
            + " decimalen, met percentage premie (12,5 %) en zijn bonus";

    /** The sentences of {@code ExplanationDefaultsComputationServiceTest}. */
    static final List<String> EXPECTED = List.of(
            "J1 heeft als premie 30,00 volgens de regel 'premie' (onvoorwaardelijk): " + PREMIE + " (3); 'premie uit"
                    + " bonus' gold niet, want niet geldt: het onderwerp ingezetene is. Grondslag: Jutterswet art. 12",
            "J2 heeft als premie 15,43 volgens 'premie uit bonus': " + UIT_BONUS + " (123,45); vóór de afronding"
                    + " 15,43125, omdat het onderwerp ingezetene is; verdrongen: de regel 'premie'",
            "V1 heeft geen afwijkende opgave waarde, want niet geldt: zijn opgegeven waarde (onbekend) gevuld is",
            "V1 heeft als waarde 110,00 volgens 'berekende waarde' (onvoorwaardelijk): zijn berekende waarde (110,00);"
                    + " 'opgegeven waarde' gaf geen waarde; verdrongen: 'waarde bij onbekende taxatie'",
            "V1 heeft als berekende waarde 110,00 volgens de regel 'waarde': zijn taxatie plus zijn bergingskosten, met"
                    + " zijn taxatie (100,00) en zijn bergingskosten (10,00), omdat zijn taxatie (100,00) gevuld is",
            "V2 heeft geen afwijkende opgave waarde, want niet geldt: zijn berekende waarde (onbekend) gevuld is",
            "V2 heeft als waarde 80,00 volgens 'opgegeven waarde' (onvoorwaardelijk): zijn opgegeven waarde (80,00);"
                    + " verdrongen: 'berekende waarde', 'waarde bij onbekende taxatie'",
            "V2 heeft geen berekende waarde, want geen regel gold; de regel 'waarde' gold niet, want niet geldt: zijn"
                    + " taxatie (onbekend) gevuld is",
            "V3 heeft geen afwijkende opgave waarde, want niet geldt: zijn opgegeven waarde (onbekend) gevuld is",
            "V3 heeft als waarde 25,00 volgens 'waarde bij onbekende taxatie': 25,00, omdat de waarde in de invoer leeg"
                    + " was; 'opgegeven waarde' gaf geen waarde; 'berekende waarde' gaf geen waarde",
            "V3 heeft geen berekende waarde, want geen regel gold; de regel 'waarde' gold niet, want niet geldt: zijn"
                    + " taxatie (onbekend) gevuld is",
            "W1 heeft als uitkering 20,00 volgens de regel 'uitkering van een wrakrecht' (onvoorwaardelijk): zijn"
                    + " grondslag maal 2, met zijn grondslag (10,00); 'betwiste uitkering' gold niet, want niet geldt:"
                    + " het onderwerp betwist is",
            "W2 heeft als uitkering 0,00 volgens 'betwiste uitkering': 0,00, omdat het onderwerp betwist is;"
                    + " verdrongen: de regel 'uitkering van een wrakrecht'");

    @Test
    void the_deciding_variant_gets_its_computation() {
        assertEquals(EXPECTED, explanation(MODEL + SCENARIO));
    }

    private static List<String> explanation(String document) {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(document));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        return u.get(0).explanation();
    }
}
