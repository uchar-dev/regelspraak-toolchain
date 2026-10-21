package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A defaults group ({@code Uitzondering op}, {@code Afwijking van}; uchar's RUNTIME-SEMANTICS ch. 7) gets one sentence
 * per object in the explanation: the variant that decided, by the name of its block, with its condition as the IR
 * carries it (an exception adds the condition of the layer it stands on, ch. 7.9), every variant that came before it
 * with the reason it did not decide, and the variants after it by name, as displaced. A do-not-attribute decision is a
 * decision ("heeft geen recht op de penning volgens …"), and where no variant decided the sentence says so, with what
 * the target holds. Before, the explanation went over the ordinary rules alone and said nothing of a group.
 *
 * <p>Which variant decided comes from the engine ({@code DroolsBackend.decisionsOf}), never from the explanation's own
 * evaluation; that evaluation only says why a variant did not decide. The scenario route plays uchar's IR document,
 * and the sentences are those of the decision service, from sources and from the document
 * ({@code ExplanationDefaultsServiceTest}).
 */
class ExplanationDefaultsTest {

    /** One characteristic group: the base 'penning', the exception 'uitsluiting bij dienstverband' (a do-not-attribute
     *  decision, with a statute annotation), the exception 'herstel bij veel vondsten' nested in it, and the deviation
     *  'jeugdpenning'; on the wire [jeugdpenning, uitsluiting [herstel], penning]. One attribute group: the base
     *  'premie' and the exception 'premie uit bonus', whose value can be empty. */
    static final String MODEL = """
            Objecttype de Jutter (mv: Jutters)
                de leeftijd Numeriek (geheel getal);
                het aantal vondsten Numeriek (geheel getal);
                de bonus Numeriek (getal met 2 decimalen);
                de premie Numeriek (getal met 2 decimalen);
                is ingezetene kenmerk (bijvoeglijk);
                is in dienst kenmerk (bijvoeglijk);
                het recht op de penning kenmerk (bezittelijk);

            Regel penning
                geldig altijd
                    Een Jutter heeft recht op de penning
                    indien hij ingezetene is.

            // Bron: Jutterswet art. 3
            Uitzondering op penning: uitsluiting bij dienstverband
                geldig altijd
                    Een Jutter heeft geen recht op de penning
                    indien hij in dienst is.

            Uitzondering op uitsluiting bij dienstverband: herstel bij veel vondsten
                geldig altijd
                    Een Jutter heeft recht op de penning
                    indien zijn aantal vondsten groter is dan 10.

            Afwijking van penning: jeugdpenning
                geldig altijd
                    Een Jutter heeft recht op de penning
                    indien zijn leeftijd kleiner is dan 18.

            Regel premie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 100,00
                    indien zijn leeftijd groter is dan 17.

            Uitzondering op premie: premie uit bonus
                geldig altijd
                    De premie van een Jutter moet gesteld worden op zijn bonus
                    indien hij ingezetene is.
            """;

    /** J1: the base decides, below a deviation and an exception that did not hold. J2: the exception decides do not
     *  attribute, below the deviation and above the base. J3: the nested exception decides. J4: the deviation, the
     *  highest variant, decides. J5: no variant of the penning decides. J6: no variant of the premie decides and the
     *  premie keeps its input. Of the premie, J1 and J3 have no bonus, so the exception holds and gives no value. */
    private static final String SITUATION = """

            Scenario zes jutters
            Gegeven is de volgende situatie:
            een Jutter J1
            - de leeftijd is 30
            - het aantal vondsten is 0
            - is ingezetene
            een Jutter J2
            - de leeftijd is 30
            - het aantal vondsten is 5
            - de bonus is 20,00
            - is ingezetene
            - is in dienst
            een Jutter J3
            - de leeftijd is 30
            - het aantal vondsten is 12
            - is ingezetene
            - is in dienst
            een Jutter J4
            - de leeftijd is 16
            - het aantal vondsten is 0
            een Jutter J5
            - de leeftijd is 30
            - het aantal vondsten is 0
            een Jutter J6
            - de leeftijd is 16
            - de premie is 7,50
            Met als verwacht resultaat:
            - J1 heeft recht op de penning
            - de premie van J1 is 100,00
            - J2 heeft geen recht op de penning
            - de premie van J2 is 20,00
            - J3 heeft recht op de penning
            - de premie van J3 is 100,00
            - J4 heeft recht op de penning
            - J5 heeft geen recht op de penning
            - de premie van J5 is 100,00
            - J6 heeft recht op de penning
            - de premie van J6 is 7,50
            """;

    private static final String HERSTEL =
            "'herstel bij veel vondsten' (uitzondering op 'uitsluiting bij dienstverband' op 'penning')";

    static final List<String> EXPECTED = List.of(
            "J1 heeft recht op de penning volgens de regel 'penning', omdat het onderwerp ingezetene is; 'jeugdpenning'"
                    + " gold niet, want niet geldt: zijn leeftijd (30) is kleiner dan 18; " + HERSTEL + " gold niet,"
                    + " want niet geldt: het onderwerp in dienst is; 'uitsluiting bij dienstverband' gold niet, want"
                    + " niet geldt: het onderwerp in dienst is",
            "J1 heeft als premie 100,00 volgens de regel 'premie', omdat zijn leeftijd (30) is groter dan 17;"
                    + " 'premie uit bonus' gaf geen waarde",
            "J2 heeft geen recht op de penning volgens 'uitsluiting bij dienstverband', omdat het onderwerp ingezetene"
                    + " is en het onderwerp in dienst is; 'jeugdpenning' gold niet, want niet geldt: zijn leeftijd (30)"
                    + " is kleiner dan 18; " + HERSTEL + " gold niet, want niet geldt: zijn aantal vondsten (5) is"
                    + " groter dan 10; verdrongen: de regel 'penning'. Grondslag: Jutterswet art. 3",
            "J2 heeft als premie 20,00 volgens 'premie uit bonus', omdat zijn leeftijd (30) is groter dan 17 en het"
                    + " onderwerp ingezetene is; verdrongen: de regel 'premie'",
            "J3 heeft recht op de penning volgens " + HERSTEL + ", omdat het onderwerp ingezetene is en het onderwerp in"
                    + " dienst is en zijn aantal vondsten (12) is groter dan 10; 'jeugdpenning' gold niet, want niet"
                    + " geldt: zijn leeftijd (30) is kleiner dan 18; verdrongen: 'uitsluiting bij dienstverband', de"
                    + " regel 'penning'",
            "J3 heeft als premie 100,00 volgens de regel 'premie', omdat zijn leeftijd (30) is groter dan 17;"
                    + " 'premie uit bonus' gaf geen waarde",
            "J4 heeft recht op de penning volgens 'jeugdpenning', omdat zijn leeftijd (16) is kleiner dan 18;"
                    + " verdrongen: " + HERSTEL + ", 'uitsluiting bij dienstverband', de regel 'penning'",
            "J4 heeft geen premie, want geen variant besliste; 'premie uit bonus' gold niet, want niet geldt: zijn"
                    + " leeftijd (16) is groter dan 17; de regel 'premie' gold niet, want niet geldt: zijn leeftijd (16)"
                    + " is groter dan 17",
            "J5 heeft geen recht op de penning, want geen variant besliste; 'jeugdpenning' gold niet, want niet geldt:"
                    + " zijn leeftijd (30) is kleiner dan 18; " + HERSTEL + " gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is; 'uitsluiting bij dienstverband' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is; de regel 'penning' gold niet, want niet geldt: het onderwerp ingezetene is",
            "J5 heeft als premie 100,00 volgens de regel 'premie', omdat zijn leeftijd (30) is groter dan 17;"
                    + " 'premie uit bonus' gold niet, want niet geldt: het onderwerp ingezetene is",
            "J6 heeft recht op de penning volgens 'jeugdpenning', omdat zijn leeftijd (16) is kleiner dan 18;"
                    + " verdrongen: " + HERSTEL + ", 'uitsluiting bij dienstverband', de regel 'penning'",
            "J6 houdt als premie de invoerwaarde 7,50, want geen variant besliste; 'premie uit bonus' gold niet, want"
                    + " niet geldt: zijn leeftijd (16) is groter dan 17; de regel 'premie' gold niet, want niet geldt:"
                    + " zijn leeftijd (16) is groter dan 17");

    /** A subtype inherits the group of its supertype, and its own ordinary rule replaces the inherited base
     *  (RUNTIME-SEMANTICS ch. 7.10): on a Wrakrecht the stack is [betwiste uitkering, uitkering van een wrakrecht],
     *  composed of two declarations, and the engine names the rule by its version on the wire. */
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

            Regel uitkering van een wrakrecht
                geldig altijd
                    De uitkering van een Wrakrecht moet gesteld worden op 200
                    indien hij gezonken is.
            """;

    /** W3 is erkend, and the base that would give it 10 is not in its stack. No variant decides, and the engine's
     *  record still names the stack in its order (its rootChains), so the sentence gives the reason of each variant
     *  and does not name the inherited base, which did not take part. */
    private static final String INHERITANCE_SITUATION = """

            Scenario vier rechten
            Gegeven is de volgende situatie:
            een Recht R1
            - is erkend
            een Wrakrecht W1
            - is gezonken
            een Wrakrecht W2
            - is erkend
            - is betwist
            een Wrakrecht W3
            - is erkend
            Met als verwacht resultaat:
            - de uitkering van R1 is 10
            - de uitkering van W1 is 200
            - de uitkering van W2 is 99
            """;

    static final List<String> INHERITANCE_EXPECTED = List.of(
            "R1 heeft als uitkering 10 volgens de regel 'uitkering', omdat het onderwerp erkend is; 'betwiste"
                    + " uitkering' gold niet, want niet geldt: het onderwerp betwist is",
            "W1 heeft als uitkering 200 volgens de regel 'uitkering van een wrakrecht', omdat het onderwerp gezonken"
                    + " is; 'betwiste uitkering' gold niet, want niet geldt: het onderwerp erkend is",
            "W2 heeft als uitkering 99 volgens 'betwiste uitkering', omdat het onderwerp erkend is en het onderwerp"
                    + " betwist is; verdrongen: de regel 'uitkering van een wrakrecht'",
            "W3 heeft geen uitkering, want geen variant besliste; 'betwiste uitkering' gold niet, want niet geldt:"
                    + " het onderwerp betwist is; de regel 'uitkering van een wrakrecht' gold niet, want niet geldt:"
                    + " het onderwerp gezonken is");

    @Test
    void every_defaults_group_gets_a_sentence_from_what_the_engine_decided() {
        assertEquals(EXPECTED, explanation(MODEL + SITUATION));
    }

    @Test
    void a_stack_composed_by_inheritance_follows_the_order_the_engine_gives() {
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
