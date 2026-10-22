package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The sentence of a characteristic assignment ends with ". Grondslag: <tekst>" when the rule carries {@code // Bron:}
 * annotations (uchar's {@code ResolvedRule.statuteAnnotations}, on the wire {@code statuteAnnotations}, IR-SPEC
 * ch. 3.1, WF-082): the non-blank annotations in source order, joined with "; ", as the ground of a default joins
 * those of its block ({@code Default.statuteAnnotations}). That holds for an attributed and for a withheld
 * characteristic. A rule without annotations, or with a blank one, gets no Grondslag. Before, the
 * explanation gave a Grondslag to a default and never to a rule.
 *
 * <p>The scenario route plays uchar's IR document, so the annotations come from what uchar's loader read; the
 * decision service from sources reads them from the model the frontend resolved, which keeps a blank annotation the
 * wire drops ({@code ExplanationStatuteAnnotationServiceTest}, the same model and sentences).
 */
class ExplanationStatuteAnnotationTest {

    /** 'meerderjarigheid' carries one annotation, 'recht op strandgoed' two, 'aanmelding' a blank one. */
    static final String MODEL = """
            Objecttype de Jutter (mv: Jutters)
                de leeftijd Numeriek (geheel getal);
                is ingezetene kenmerk (bijvoeglijk);
                is meerderjarig kenmerk (bijvoeglijk);
                het recht op strandgoed kenmerk (bezittelijk);
                is aangemeld kenmerk (bijvoeglijk);

            // Bron: Jutterswet art. 2
            Regel meerderjarigheid
                geldig altijd
                    Een Jutter is meerderjarig
                    indien zijn leeftijd groter is dan 17.

            // Bron: Jutterswet art. 4 lid 1
            // Bron: Jutterswet art. 4 lid 3
            Regel recht op strandgoed
                geldig altijd
                    Een Jutter heeft recht op strandgoed
                    indien hij ingezetene is.

            //Bron:
            Regel aanmelding
                geldig altijd
                    Een Jutter is aangemeld
                    indien hij ingezetene is.
            """;

    private static final String SITUATION = """

            Scenario twee jutters
            Gegeven is de volgende situatie:
            een Jutter J1
            - de leeftijd is 30
            - is ingezetene
            een Jutter J2
            - de leeftijd is 16
            Met als verwacht resultaat:
            - J1 is meerderjarig
            - J1 heeft recht op strandgoed
            - J1 is aangemeld
            - J2 is niet meerderjarig
            - J2 heeft geen recht op strandgoed
            - J2 is niet aangemeld
            """;

    static final List<String> EXPECTED = List.of(
            "J1 is meerderjarig, omdat zijn leeftijd (30) is groter dan 17. Grondslag: Jutterswet art. 2",
            "J1 heeft recht op strandgoed, omdat het onderwerp ingezetene is. Grondslag: Jutterswet art. 4 lid 1;"
                    + " Jutterswet art. 4 lid 3",
            "J1 is aangemeld, omdat het onderwerp ingezetene is",
            "J2 is niet meerderjarig, want niet geldt: zijn leeftijd (16) is groter dan 17. Grondslag: Jutterswet"
                    + " art. 2",
            "J2 heeft geen recht op strandgoed, want niet geldt: het onderwerp ingezetene is. Grondslag: Jutterswet"
                    + " art. 4 lid 1; Jutterswet art. 4 lid 3",
            "J2 is niet aangemeld, want niet geldt: het onderwerp ingezetene is");

    /** The ordinary rule of a subtype is the base of the stack it inherits (uchar's RUNTIME-SEMANTICS ch. 7.10), and
     *  where it decides, the sentence of the group names its annotation as the Grondslag of the decider, as for a
     *  default. Where the exception decides, the rule is displaced and its annotation is not named. */
    static final String INHERITANCE = """
            Objecttype het Recht (mv: Rechten)
                de uitkering Numeriek (geheel getal);
                is betwist kenmerk (bijvoeglijk);
                is erkend kenmerk (bijvoeglijk);

            Objecttype het Wrakrecht is een Recht (mv: Wrakrechten)
                is gezonken kenmerk (bijvoeglijk);

            Regel uitkering
                geldig altijd
                    De uitkering van een Recht moet gesteld worden op 10
                    indien hij erkend is.

            Uitzondering op uitkering: betwiste uitkering
                geldig altijd
                    De uitkering van een Recht moet gesteld worden op 99
                    indien hij betwist is.

            // Bron: Jutterswet art. 9
            Regel uitkering van een wrakrecht
                geldig altijd
                    De uitkering van een Wrakrecht moet gesteld worden op 200
                    indien hij gezonken is.
            """;

    private static final String INHERITANCE_SITUATION = """

            Scenario twee wrakrechten
            Gegeven is de volgende situatie:
            een Wrakrecht W1
            - is gezonken
            een Wrakrecht W2
            - is erkend
            - is betwist
            Met als verwacht resultaat:
            - de uitkering van W1 is 200
            - de uitkering van W2 is 99
            """;

    static final List<String> INHERITANCE_EXPECTED = List.of(
            "W1 heeft als uitkering 200 volgens de regel 'uitkering van een wrakrecht': 200, omdat het onderwerp"
                    + " gezonken is; 'betwiste uitkering' gold niet, want niet geldt: het onderwerp erkend is."
                    + " Grondslag: Jutterswet art. 9",
            "W2 heeft als uitkering 99 volgens 'betwiste uitkering': 99, omdat het onderwerp erkend is en het"
                    + " onderwerp betwist is; verdrongen: de regel 'uitkering van een wrakrecht'");

    @Test
    void a_characteristic_assignment_names_the_statute_annotations_of_its_rule() {
        assertEquals(EXPECTED, explanation(MODEL + SITUATION));
    }

    @Test
    void a_rule_that_decides_a_stack_names_its_statute_annotation() {
        assertEquals(INHERITANCE_EXPECTED, explanation(INHERITANCE + INHERITANCE_SITUATION));
    }

    private static List<String> explanation(String document) {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(document));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        return u.get(0).explanation();
    }
}
