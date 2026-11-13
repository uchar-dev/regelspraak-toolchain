package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stack of a target per day (uchar's RUNTIME-SEMANTICS ch. 7.8): the writers decide their days, the input stands on
 * the other days, and the initialisation decides the days that leaves leeg, over all time. On an object a creation
 * made, the place of the input is the value the creation assigned: the member value the creation gave it (rank 2 of the stack),
 * a scalar on every day or a timeline per period. uchar names who decided per period
 * ({@code DroolsBackend.periodDecisionsOf}), the initialisation as one of the rules, and the explanation names the
 * initialisation with the days it decided, and on a made object the value the creation assigned by that name, never as an input
 * value: a request gives a made object nothing. Own material after uchar's goldens
 * {@code jutterswet-initialisatie-per-dag} and {@code jutterswet-creatie-per-dag}.
 */
class ExplanationStackPerDayTest {

    /** A rule decides the voorjaarsloon from March to May, the input 10 stands from January to March, and the
     *  initialisation decides every other day; the stormloon is initialised only while the jutter is stormwacht; a rule
     *  decides the wachtloon on every day, so its initialisation has none left. */
    private static final String INITIALISATION = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                het voorjaarsloon Numeriek (geheel getal) voor elke maand;
                het stormloon Numeriek (geheel getal) voor elke maand;
                het wachtloon Numeriek (geheel getal) voor elke maand;
                is stormwacht kenmerk (bijvoeglijk) voor elke maand;

            Regel voorjaarsloon
                geldig altijd
                    Het voorjaarsloon van een Jutter moet gesteld worden op 20 van dd. 01-03-2025 tot dd. 01-06-2025.

            Regel standaardvoorjaarsloon
                geldig altijd
                    Het voorjaarsloon van een Jutter moet geïnitialiseerd worden op 5.

            Regel standaardstormloon
                geldig altijd
                    Het stormloon van een Jutter moet geïnitialiseerd worden op 3 gedurende de tijd dat hij stormwacht is.

            Regel wachtloon
                geldig altijd
                    Het wachtloon van een Jutter moet gesteld worden op 20.

            Regel standaardwachtloon
                geldig altijd
                    Het wachtloon van een Jutter moet geïnitialiseerd worden op 5.

            Scenario invoer naast regel en initialisatie
            Gegeven is de volgende situatie:
            een Jutter J2
            - het voorjaarsloon is 10 van 01-01-2025 tot 01-04-2025
            - is stormwacht van 01-02-2025 tot 01-04-2025
            Met als verwacht resultaat:
            - het voorjaarsloon van J2 is 5 tot 01-01-2025
            - het voorjaarsloon van J2 is 10 van 01-01-2025 tot 01-03-2025
            - het voorjaarsloon van J2 is 20 van 01-03-2025 tot 01-06-2025
            - het voorjaarsloon van J2 is 5 vanaf 01-06-2025
            - het stormloon van J2 is 3 van 01-02-2025 tot 01-04-2025
            """;

    @Test
    void an_initialisation_decides_per_period_the_days_the_rules_and_the_input_leave() {
        assertEquals(List.of(
                "J2 heeft als voorjaarsloon 20 van 01-03-2025 tot 01-06-2025 volgens de regel 'voorjaarsloon': 20, omdat van"
                        + " dd. 01-03-2025 tot dd. 01-06-2025 (van 01-03-2025 tot 01-06-2025), en 5 tot 01-01-2025 en 5"
                        + " vanaf 01-06-2025 volgens de regel 'standaardvoorjaarsloon': 5, omdat de voorjaarsloon in de invoer"
                        + " leeg was; de invoerwaarde 10 van 01-01-2025 tot 01-03-2025 bleef staan",
                "J2 heeft als stormloon 3 van 01-02-2025 tot 01-04-2025 volgens de regel 'standaardstormloon': 3, omdat hij"
                        + " stormwacht is (van 01-02-2025 tot 01-04-2025)",
                "J2 heeft als wachtloon altijd 20 volgens de regel 'wachtloon' (onvoorwaardelijk): 20; verdrongen: de regel"
                        + " 'standaardwachtloon'"),
                explained(INITIALISATION).explanation());
    }

    /** A creation gives a made melding its seizoensloon, aanvulloon and meldloon from the strandloon of the jutter, 10
     *  from January to June. A rule decides the seizoensloon from March to August, the initialisation the aanvulloon
     *  on the days the creation left empty, and the rule of the meldloon reads an empty toeslag and decides no day. */
    private static final String MADE_TIMELINE = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                het strandloon Numeriek (geheel getal) voor elke maand;
                het seizoensloontotaal Numeriek (geheel getal) voor elke maand;

            Objecttype de Melding (mv: Meldingen)
                het seizoensloon Numeriek (geheel getal) voor elke maand;
                het aanvulloon Numeriek (geheel getal) voor elke maand;
                het meldloon Numeriek (geheel getal) voor elke maand;
                de toeslag Numeriek (geheel getal) voor elke maand;

            Feittype jutmelding
                de melder Jutter
                de melding (mv: meldingen) Melding
            Eén melder heeft meerdere meldingen

            Regel meld de jutter
                geldig altijd
                    Een Jutter heeft een melding met seizoensloon gelijk aan zijn strandloon en aanvulloon gelijk aan zijn strandloon en meldloon gelijk aan zijn strandloon.

            Regel seizoensloon
                geldig altijd
                    Het seizoensloon van een Melding moet gesteld worden op 20 van dd. 01-03-2025 tot dd. 01-09-2025.

            Regel standaardaanvulloon
                geldig altijd
                    Het aanvulloon van een Melding moet geïnitialiseerd worden op 5.

            Regel meldloon uit toeslag
                geldig altijd
                    Het meldloon van een Melding moet gesteld worden op zijn toeslag.

            Regel seizoensloontotaal
                geldig altijd
                    Het seizoensloontotaal van een Jutter moet berekend worden als de som van het seizoensloon van alle meldingen van de Jutter.

            Scenario strandloon tot juli
            Gegeven is de volgende situatie:
            een Jutter J1
            - het strandloon is 10 van 01-01-2025 tot 01-07-2025
            Met als verwacht resultaat:
            - het seizoensloontotaal van J1 is 10 van 01-01-2025 tot 01-03-2025
            - het seizoensloontotaal van J1 is 20 van 01-03-2025 tot 01-09-2025
            """;

    @Test
    void on_a_made_object_the_value_of_the_making_stands_per_period_and_the_initialisation_fills_what_it_left() {
        List<String> sentences = explained(MADE_TIMELINE).explanation();
        String made = "«\"meld de jutter\"»(J1)#1";
        assertEquals(List.of(
                "J1 heeft als seizoensloontotaal 10 van 01-01-2025 tot 01-03-2025 en 20 van 01-03-2025 tot 01-09-2025"
                        + " volgens de regel 'seizoensloontotaal' (onvoorwaardelijk): de som van het seizoensloon van"
                        + " alle meldingen van de Jutter (10 van 01-01-2025 tot 01-03-2025 en 20 van 01-03-2025 tot"
                        + " 01-09-2025): over " + made + " (10 van 01-01-2025 tot 01-03-2025 en 20 van 01-03-2025 tot"
                        + " 01-09-2025), het enige lid met een waarde",
                "J1 heeft een Melding " + made + " volgens de regel 'meld de jutter' (onvoorwaardelijk): met als"
                        + " seizoensloon zijn strandloon (10 van 01-01-2025 tot 01-07-2025), als aanvulloon zijn"
                        + " strandloon (10 van 01-01-2025 tot 01-07-2025) en als meldloon zijn strandloon (10 van"
                        + " 01-01-2025 tot 01-07-2025)",
                made + " heeft als seizoensloon 20 van 01-03-2025 tot 01-09-2025 volgens de regel 'seizoensloon': 20, omdat van"
                        + " dd. 01-03-2025 tot dd. 01-09-2025 (van 01-03-2025 tot 01-09-2025); de bij de objectcreatie"
                        + " toegekende waarde 10 van 01-01-2025 tot 01-03-2025 bleef staan",
                made + " heeft als aanvulloon 5 tot 01-01-2025 en 5 vanaf 01-07-2025 volgens de regel"
                        + " 'standaardaanvulloon': 5, omdat de aanvulloon bij de objectcreatie leeg was; de bij de"
                        + " objectcreatie toegekende waarde 10 van 01-01-2025 tot 01-07-2025 bleef staan",
                made + " houdt als meldloon de bij de objectcreatie toegekende waarde 10 van 01-01-2025 tot 01-07-2025,"
                        + " want de regel 'meldloon uit toeslag' gaf geen waarde (onvoorwaardelijk) en besliste niets:"
                        + " zijn toeslag (altijd leeg)"),
                sentences);
    }

    /** The same on a target with one value: the creation gives the melding the vangst of the jutter as its
     *  meldwaarde, which a rule that reads an empty toeslag leaves standing, and its opslag, which an initialisation
     *  fills where the creation left it empty. */
    private static final String MADE_SCALAR = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de vangst Numeriek (geheel getal);
                de borg Numeriek (geheel getal);
                de terug Numeriek (geheel getal);

            Objecttype de Melding (mv: Meldingen)
                de meldwaarde Numeriek (geheel getal);
                de toeslag Numeriek (geheel getal);
                de opslag Numeriek (geheel getal);

            Feittype jutmelding
                de melder Jutter
                de melding (mv: meldingen) Melding
            Eén melder heeft meerdere meldingen

            Regel meld de vangst
                geldig altijd
                    Een Jutter heeft een melding met meldwaarde gelijk aan zijn vangst en opslag gelijk aan zijn borg.

            Regel meldwaarde uit toeslag
                geldig altijd
                    De meldwaarde van een Melding moet gesteld worden op zijn toeslag.

            Regel standaardopslag
                geldig altijd
                    De opslag van een Melding moet geïnitialiseerd worden op 3.

            Regel terug van een jutter
                geldig altijd
                    De terug van een Jutter moet berekend worden als de som van de opslag van alle meldingen van de Jutter.

            Scenario vangst zonder borg
            Gegeven is de volgende situatie:
            een Jutter J1
            - de vangst is 7
            een Jutter J2
            - de vangst is 4
            - de borg is 9
            Met als verwacht resultaat:
            - de terug van J1 is 3
            - de terug van J2 is 9
            """;

    @Test
    void on_a_made_object_with_one_value_the_value_of_the_making_is_named_so() {
        List<String> sentences = explained(MADE_SCALAR).explanation();
        assertEquals(List.of(
                "J1 heeft als terug 3 volgens de regel 'terug van een jutter' (onvoorwaardelijk): de som van de opslag van"
                        + " alle meldingen van de Jutter (3): over «\"meld de vangst\"»(J1)#1 (3), het enige lid met een waarde",
                "J1 heeft een Melding «\"meld de vangst\"»(J1)#1 volgens de regel 'meld de vangst' (onvoorwaardelijk):"
                        + " met als meldwaarde zijn vangst (7) en als opslag zijn borg (leeg)",
                "J2 heeft als terug 9 volgens de regel 'terug van een jutter' (onvoorwaardelijk): de som van de opslag van"
                        + " alle meldingen van de Jutter (9): over «\"meld de vangst\"»(J2)#1 (9), het enige lid met een waarde",
                "J2 heeft een Melding «\"meld de vangst\"»(J2)#1 volgens de regel 'meld de vangst' (onvoorwaardelijk):"
                        + " met als meldwaarde zijn vangst (4) en als opslag zijn borg (9)",
                "«\"meld de vangst\"»(J1)#1 houdt als meldwaarde de bij de objectcreatie toegekende waarde 7, want de"
                        + " regel 'meldwaarde uit toeslag' gaf geen waarde (onvoorwaardelijk) en besliste niets: zijn"
                        + " toeslag (leeg)",
                "«\"meld de vangst\"»(J1)#1 heeft als opslag 3 volgens de regel 'standaardopslag': 3, omdat de opslag"
                        + " bij de objectcreatie leeg was",
                "«\"meld de vangst\"»(J2)#1 houdt als meldwaarde de bij de objectcreatie toegekende waarde 4, want de"
                        + " regel 'meldwaarde uit toeslag' gaf geen waarde (onvoorwaardelijk) en besliste niets: zijn"
                        + " toeslag (leeg)",
                "«\"meld de vangst\"»(J2)#1 houdt als opslag de bij de objectcreatie toegekende waarde 9, want geen"
                        + " regel gold; verdrongen: de regel 'standaardopslag'"),
                sentences);
    }

    /** A cell of a made melding: the creation gives it the vangst of the jutter as its meldwaarde in najaar, which a
     *  rule that reads an empty toeslag leaves standing. */
    private static final String MADE_CELL = """
            Dimensie het seizoen, bestaande uit de seizoenen (na het attribuut met voorzetsel in):
                1. voorjaar
                2. najaar

            Objecttype de Jutter (mv: Jutters) (bezield)
                de vangst Numeriek (geheel getal);
                de terug Numeriek (geheel getal);

            Objecttype de Melding (mv: Meldingen)
                de meldwaarde Numeriek (geheel getal) gedimensioneerd met seizoen;
                de toeslag Numeriek (geheel getal);

            Feittype jutmelding
                de melder Jutter
                de melding (mv: meldingen) Melding
            Eén melder heeft meerdere meldingen

            Regel meld de vangst
                geldig altijd
                    Een Jutter heeft een melding met meldwaarde in najaar gelijk aan zijn vangst.

            Regel najaarswaarde uit toeslag
                geldig altijd
                    De meldwaarde in najaar van een Melding moet gesteld worden op zijn toeslag.

            Regel terug van een jutter
                geldig altijd
                    De terug van een Jutter moet berekend worden als de som van de meldwaarde in najaar van alle meldingen van de Jutter.

            Scenario vangst in het najaar
            Gegeven is de volgende situatie:
            een Jutter J1
            - de vangst is 7
            Met als verwacht resultaat:
            - de terug van J1 is 7
            """;

    @Test
    void on_a_made_object_a_cell_keeps_the_value_of_the_making() {
        String made = "«\"meld de vangst\"»(J1)#1";
        assertEquals(List.of(made + " houdt als meldwaarde in najaar de bij de objectcreatie toegekende waarde 7, want"
                        + " de regel 'najaarswaarde uit toeslag' gaf geen waarde (onvoorwaardelijk) en besliste niets: zijn"
                        + " toeslag (leeg)"),
                explained(MADE_CELL).explanation().stream().filter(s -> s.startsWith(made)).toList());
    }

    /** A defaults group on a made melding: two unordered exceptions on its toelage per month, which the creation gives
     *  the strandloon of the jutter, 20 in January and February and 10 from March to June. The high exception decides
     *  January and February; from March no exception decides, and the value the creation assigned stands. */
    private static final String MADE_DEFAULTS = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                het strandloon Numeriek (geheel getal) voor elke maand;

            Objecttype de Melding (mv: Meldingen)
                de toelage Numeriek (geheel getal) voor elke maand;
                het meldloon Numeriek (geheel getal) voor elke maand;

            Feittype jutmelding
                de melder Jutter
                de melding (mv: meldingen) Melding
            Eén melder heeft meerdere meldingen

            Regel meld de jutter
                geldig altijd
                    Een Jutter heeft een melding met toelage gelijk aan zijn strandloon en meldloon gelijk aan zijn strandloon.

            Uitzonderingen ongeordend:
            Regel toelage bij hoog meldloon
                geldig altijd
                    De toelage van een Melding moet gesteld worden op 50
                    indien zijn meldloon groter is dan 15.
            Regel toelage bij laag meldloon
                geldig altijd
                    De toelage van een Melding moet gesteld worden op 1
                    indien zijn meldloon kleiner is dan 5.

            Scenario hoog en laag
            Gegeven is de volgende situatie:
            een Jutter J1
            - het strandloon is 20 van 01-01-2025 tot 01-03-2025
            - het strandloon is 10 van 01-03-2025 tot 01-07-2025
            Met als verwacht resultaat:
            - het strandloon van J1 is 20 van 01-01-2025 tot 01-03-2025
            - het strandloon van J1 is 10 van 01-03-2025 tot 01-07-2025
            """;

    @Test
    void a_defaults_group_on_a_made_object_names_the_value_of_the_making_in_the_period_no_variant_decided() {
        List<String> sentences = explained(MADE_DEFAULTS).explanation();
        String made = "«\"meld de jutter\"»(J1)#1";
        assertEquals(List.of(
                "J1 heeft een Melding " + made + " volgens de regel 'meld de jutter' (onvoorwaardelijk): met als toelage"
                        + " zijn strandloon (20 van 01-01-2025 tot 01-03-2025 en 10 van 01-03-2025 tot 01-07-2025) en als"
                        + " meldloon zijn strandloon (20 van 01-01-2025 tot 01-03-2025 en 10 van 01-03-2025 tot"
                        + " 01-07-2025)",
                made + " heeft als toelage 50 van 01-01-2025 tot 01-03-2025 volgens de regel 'toelage bij hoog meldloon':"
                        + " 50,"
                        + " omdat zijn meldloon groter is dan 15 (van 01-01-2025 tot 01-03-2025)",
                made + " houdt als toelage de bij de objectcreatie toegekende waarde 10 van 01-03-2025 tot 01-07-2025,"
                        + " want geen variant besliste"),
                sentences);
    }

    /** The opgave marker on the toelage of a made melding: the stack of the opgave over the computed toelage, with
     *  the initialisation at its bottom. The creation gives the melding a meldwaarde and no toelage, which the opgave
     *  marker forbids it (WF-050); the rule does not hold for a meldwaarde of 10, so the initialisation decides where
     *  the creation left the toelage empty. */
    private static final String MADE_STACK_SCALAR = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de vangst Numeriek (geheel getal);
                de terug Numeriek (geheel getal);

            Objecttype de Melding (mv: Meldingen)
                de meldwaarde Numeriek (geheel getal);
                de toelage Numeriek (geheel getal) (mag opgegeven worden);

            Feittype jutmelding
                de melder Jutter
                de melding (mv: meldingen) Melding
            Eén melder heeft meerdere meldingen

            Regel meld de vangst
                geldig altijd
                    Een Jutter heeft een melding met meldwaarde gelijk aan zijn vangst.

            Regel toelage bij hoge meldwaarde
                geldig altijd
                    De toelage van een Melding moet gesteld worden op 50
                    indien zijn meldwaarde groter is dan 15.

            Regel standaardtoelage
                geldig altijd
                    De toelage van een Melding moet geïnitialiseerd worden op 3.

            Regel terug van een jutter
                geldig altijd
                    De terug van een Jutter moet berekend worden als de som van de toelage van alle meldingen van de Jutter.

            Scenario zonder toelage bij de objectcreatie
            Gegeven is de volgende situatie:
            een Jutter J1
            - de vangst is 10
            Met als verwacht resultaat:
            - de terug van J1 is 3
            """;

    @Test
    void the_initialisation_of_a_stack_on_a_made_object_names_the_making() {
        String made = "«\"meld de vangst\"»(J1)#1";
        assertEquals(List.of(
                "J1 heeft als terug 3 volgens de regel 'terug van een jutter' (onvoorwaardelijk): de som van de toelage"
                        + " van alle meldingen van de Jutter (3): over " + made + " (3), het enige lid met een waarde",
                "J1 heeft een Melding " + made + " volgens de regel 'meld de vangst' (onvoorwaardelijk): met als"
                        + " meldwaarde zijn vangst (10)",
                made + " heeft geen afwijkende opgave toelage, want niet geldt: zijn opgegeven toelage (leeg) gevuld is",
                made + " heeft als toelage 3 volgens 'standaardtoelage': 3, omdat de toelage bij de objectcreatie leeg"
                        + " was; 'opgegeven toelage' gaf geen waarde; 'berekende toelage' gaf geen waarde",
                made + " heeft geen berekende toelage, want geen regel gold; de regel 'toelage bij hoge meldwaarde' gold"
                        + " niet, want niet geldt: zijn meldwaarde (10) is groter dan 15"),
                explained(MADE_STACK_SCALAR).explanation());
    }

    private static ScenarioRunner.Explained explained(String source) {
        var bundle = ScenarioResolver.resolve(source);
        assertTrue(bundle.isOk(), () -> bundle.model().errors().toString());
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(bundle);
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic() + " "
                + u.get(0).result().expectations());
        assertNull(u.get(0).explanationError(), u.get(0).explanationError());
        return u.get(0);
    }
}
