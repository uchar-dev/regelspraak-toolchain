package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The explanation of a quantifier fold names the members that carry its outcome, with their number (card
 * explanation-fold-members), on the scenario route. One rule per quantifier: "alle" names the members for which the
 * matrix does not hold where the fold does not hold, "geen van de" those for which it holds, a counting quantifier
 * ("ten minste", "ten hoogste", "precies") those for which it holds either way, and all members where none does. J1
 * has two of four vondsten waardevol and two heavier than 4 kg, J2 one vondst that is both, J3 two that are neither, J4
 * none, J5 seven, entered out of the code-unit order of their labels, of which the sentence names the first five. The
 * verdict per member comes from the engine's reading. The decision service gives the same sentences on both its routes
 * ({@code ExplanationFoldMembersServiceTest}).
 */
class ExplanationFoldMembersTest {

    static final String MODEL = """
            Eenheidsysteem Gewicht
                het kilogram (mv: kilogrammen) kg

            Objecttype de Jutter (mv: Jutters)
                is gelukkig kenmerk (bijvoeglijk);
                is onvermogend kenmerk (bijvoeglijk);
                is vermogend kenmerk (bijvoeglijk);
                is zwaarbeladen kenmerk (bijvoeglijk);
                is lichtbeladen kenmerk (bijvoeglijk);
                is enkelbeladen kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal) met eenheid kg;
                is waardevol kenmerk (bijvoeglijk);

            Feittype vondst van een jutter
                de jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén jutter heeft meerdere vondsten

            Regel gelukkig
                geldig altijd
                    Een Jutter is gelukkig
                    indien alle vondsten van de Jutter waardevol zijn.

            Regel onvermogend
                geldig altijd
                    Een Jutter is onvermogend
                    indien geen van de vondsten van de Jutter waardevol is.

            Regel vermogend
                geldig altijd
                    Een Jutter is vermogend
                    indien ten minste één van de vondsten van de Jutter waardevol is.

            Regel zwaarbeladen
                geldig altijd
                    Een Jutter is zwaarbeladen
                    indien het gewicht van ten minste 2 van de vondsten van de Jutter groter is dan 4 kg.

            Regel lichtbeladen
                geldig altijd
                    Een Jutter is lichtbeladen
                    indien het gewicht van ten hoogste 1 van de vondsten van de Jutter groter is dan 4 kg.

            Regel enkelbeladen
                geldig altijd
                    Een Jutter is enkelbeladen
                    indien het gewicht van precies 1 van de vondsten van de Jutter groter is dan 4 kg.
            """;

    private static final String SITUATION = """

            Situatie basis:
            een Jutter J1
            een Jutter J2
            een Jutter J3
            een Jutter J4
            een Jutter J5
            een Vondst V1
            - het gewicht is 3 kg
            - is waardevol
            - de jutter is J1
            een Vondst V2
            - het gewicht is 5 kg
            - de jutter is J1
            een Vondst V3
            - het gewicht is 2 kg
            - is waardevol
            - de jutter is J1
            een Vondst V4
            - het gewicht is 6 kg
            - de jutter is J1
            een Vondst V5
            - het gewicht is 5 kg
            - is waardevol
            - de jutter is J2
            een Vondst V6
            - het gewicht is 3 kg
            - de jutter is J3
            een Vondst V7
            - het gewicht is 2 kg
            - de jutter is J3
            een Vondst X20
            - het gewicht is 5 kg
            - de jutter is J5
            een Vondst X3
            - het gewicht is 5 kg
            - de jutter is J5
            een Vondst X10
            - het gewicht is 5 kg
            - de jutter is J5
            een Vondst X1
            - het gewicht is 5 kg
            - de jutter is J5
            een Vondst X12
            - het gewicht is 5 kg
            - de jutter is J5
            een Vondst X2
            - het gewicht is 5 kg
            - de jutter is J5
            een Vondst X11
            - het gewicht is 5 kg
            - de jutter is J5
            Scenario vijf jutters
            met peildatum 01-06-2026
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - J1 is niet gelukkig
            - J1 is zwaarbeladen
            - J2 is gelukkig
            - J3 is onvermogend
            - J4 is gelukkig
            - J5 is niet vermogend
            """;

    private static final String ALL = "aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\"";
    private static final String NONE = "aan de voorwaarde \"geen van de vondsten van de Jutter waardevol is\"";
    private static final String AT_LEAST_ONE =
            "aan de voorwaarde \"ten minste één van de vondsten van de Jutter waardevol is\"";
    private static final String AT_LEAST =
            "aan de voorwaarde \"het gewicht van ten minste 2 van de vondsten van de Jutter groter is dan 4 kg\"";
    private static final String AT_MOST =
            "aan de voorwaarde \"het gewicht van ten hoogste 1 van de vondsten van de Jutter groter is dan 4 kg\"";
    private static final String EXACTLY =
            "aan de voorwaarde \"het gewicht van precies 1 van de vondsten van de Jutter groter is dan 4 kg\"";
    private static final String SEVEN = "X1, X10, X11, X12, X2 en nog 2";

    static final List<String> EXPECTED = List.of(
            "J1 is niet gelukkig, want " + ALL + " is niet voldaan: niet voor V2 en V4 (2 van de 4)",
            "J1 is niet onvermogend, want " + NONE + " is niet voldaan: wel voor V1 en V3 (2 van de 4)",
            "J1 is vermogend, omdat " + AT_LEAST_ONE + " is voldaan: voor 2 van de 4 (V1 en V3)",
            "J1 is zwaarbeladen, omdat " + AT_LEAST + " is voldaan: voor 2 van de 4 (V2 en V4)",
            "J1 is niet lichtbeladen, want " + AT_MOST + " is niet voldaan: voor 2 van de 4 (V2 en V4)",
            "J1 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: voor 2 van de 4 (V2 en V4)",
            "J2 is gelukkig, omdat " + ALL + " is voldaan: voor alle 1",
            "J2 is niet onvermogend, want " + NONE + " is niet voldaan: wel voor V5 (1 van de 1)",
            "J2 is vermogend, omdat " + AT_LEAST_ONE + " is voldaan: voor 1 van de 1 (V5)",
            "J2 is niet zwaarbeladen, want " + AT_LEAST + " is niet voldaan: voor 1 van de 1 (V5)",
            "J2 is lichtbeladen, omdat " + AT_MOST + " is voldaan: voor 1 van de 1 (V5)",
            "J2 is enkelbeladen, omdat " + EXACTLY + " is voldaan: voor 1 van de 1 (V5)",
            "J3 is niet gelukkig, want " + ALL + " is niet voldaan: niet voor V6 en V7 (2 van de 2)",
            "J3 is onvermogend, omdat " + NONE + " is voldaan: voor geen van de 2",
            "J3 is niet vermogend, want " + AT_LEAST_ONE + " is niet voldaan: voor geen van de 2 (V6 en V7)",
            "J3 is niet zwaarbeladen, want " + AT_LEAST + " is niet voldaan: voor geen van de 2 (V6 en V7)",
            "J3 is lichtbeladen, omdat " + AT_MOST + " is voldaan: voor geen van de 2 (V6 en V7)",
            "J3 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: voor geen van de 2 (V6 en V7)",
            "J4 is gelukkig, omdat " + ALL + " is voldaan: (geen enkel lid)",
            "J4 is onvermogend, omdat " + NONE + " is voldaan: (geen enkel lid)",
            "J4 is niet vermogend, want " + AT_LEAST_ONE + " is niet voldaan: (geen enkel lid)",
            "J4 is niet zwaarbeladen, want " + AT_LEAST + " is niet voldaan: (geen enkel lid)",
            "J4 is lichtbeladen, omdat " + AT_MOST + " is voldaan: (geen enkel lid)",
            "J4 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: (geen enkel lid)",
            "J5 is niet gelukkig, want " + ALL + " is niet voldaan: niet voor " + SEVEN + " (7 van de 7)",
            "J5 is onvermogend, omdat " + NONE + " is voldaan: voor geen van de 7",
            "J5 is niet vermogend, want " + AT_LEAST_ONE + " is niet voldaan: voor geen van de 7 (" + SEVEN + ")",
            "J5 is zwaarbeladen, omdat " + AT_LEAST + " is voldaan: voor 7 van de 7 (" + SEVEN + ")",
            "J5 is niet lichtbeladen, want " + AT_MOST + " is niet voldaan: voor 7 van de 7 (" + SEVEN + ")",
            "J5 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: voor 7 van de 7 (" + SEVEN + ")");

    @Test
    void the_sentence_of_a_quantifier_fold_names_the_members_that_carry_its_outcome() {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(MODEL + SITUATION));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        assertEquals(EXPECTED, u.get(0).explanation());
    }

    /** A matrix over a timeline can fault, so the engine reads the members in their order and stops "geen van de" at
     *  the first that holds. The second holds as well, and the sentence names it by the verdict of its reading,
     *  although the engine did not reach it (owner's decision 2026-10-21). */
    @Test
    void a_member_after_the_stop_is_named_by_the_verdict_of_its_reading() {
        String model = """
                Objecttype de Bergingsploeg (mv: Bergingsploegen)
                    is wisselvallig kenmerk (bijvoeglijk);

                Objecttype de Berger (mv: Bergers) (bezield)
                    de dagvergoeding Numeriek (geheel getal) voor elke maand;

                Feittype ploegindeling
                    de ploeg Bergingsploeg
                    de berger (mv: bergers) Berger
                Eén ploeg omvat meerdere bergers

                Regel wisselvallig
                    geldig altijd
                        Een Bergingsploeg is wisselvallig indien de dagvergoeding van geen van de bergers van de Bergingsploeg gedurende het gehele jaar groter is dan 40.

                Situatie ploeg:
                een Bergingsploeg P1
                een Berger B1
                - de dagvergoeding is 50 van 01-01-2026 tot 01-01-2027
                - de ploeg is P1
                een Berger B2
                - de dagvergoeding is 60 van 01-01-2026 tot 01-01-2027
                - de ploeg is P1
                Scenario een ploeg
                met peildatum 15-03-2026
                gebaseerd op de situatie ploeg
                Met als verwacht resultaat:
                - P1 is niet wisselvallig
                """;
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(model));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        List<Boolean> reached = u.get(0).session().readings().stream()
                .flatMap(r -> r.nodes().stream()).flatMap(n -> n.contributors().stream())
                .map(ConditionReading.Contributor::reached).toList();
        assertEquals(List.of(true, false), reached, "de motor stopt na het eerste lid");
        assertEquals(List.of("P1 is niet wisselvallig, want aan de voorwaarde \"de dagvergoeding van geen van de bergers"
                + " van de Bergingsploeg gedurende het gehele jaar groter is dan 40\" is niet voldaan: wel voor B1 en B2"
                + " (2 van de 2)"), u.get(0).explanation());
    }

    /** B1 has no dagvergoeding, so the whole-period matrix holds on it and "geen van de" stops there. B2 gives its
     *  dagvergoeding as the value at the evaluation date, not as a timeline, and a matrix that reads the timeline whole
     *  faults on that input in the reading. The session never reached B2 and has no fault. A member without a verdict
     *  counts neither in m nor in k and is not judged (provisional decision of the main session 2026-10-21, after
     *  Fable's advice 2 §3). */
    @Test
    void a_member_whose_matrix_faults_after_the_stop_is_not_judged() {
        String model = """
                Objecttype de Bergingsploeg (mv: Bergingsploegen)
                    is wisselvallig kenmerk (bijvoeglijk);

                Objecttype de Berger (mv: Bergers) (bezield)
                    de dagvergoeding Numeriek (geheel getal) voor elke maand;

                Feittype ploegindeling
                    de ploeg Bergingsploeg
                    de berger (mv: bergers) Berger
                Eén ploeg omvat meerdere bergers

                Regel wisselvallig
                    geldig altijd
                        Een Bergingsploeg is wisselvallig indien de dagvergoeding van geen van de bergers van de Bergingsploeg gedurende het gehele jaar groter is dan 40.

                Situatie ploeg:
                een Bergingsploeg P1
                een Berger B1
                - de ploeg is P1
                een Berger B2
                - de dagvergoeding is 50
                - de ploeg is P1
                Scenario een ploeg
                met peildatum 15-03-2026
                gebaseerd op de situatie ploeg
                Met als verwacht resultaat:
                - P1 is niet wisselvallig
                """;
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(model));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        List<ConditionReading.Contributor> contributors = u.get(0).session().readings().stream()
                .flatMap(r -> r.nodes().stream()).flatMap(n -> n.contributors().stream()).toList();
        assertEquals(List.of(true, false), contributors.stream().map(ConditionReading.Contributor::reached).toList(),
                "de motor stopt na het eerste lid");
        assertNull(contributors.get(0).fault(), "het eerste lid heeft een oordeel");
        assertNotNull(contributors.get(1).fault(),
                () -> "de matrix faalt op het tweede lid: " + contributors + " " + u.get(0).explanation());
        assertEquals(List.of("P1 is niet wisselvallig, want aan de voorwaarde \"de dagvergoeding van geen van de bergers"
                + " van de Bergingsploeg gedurende het gehele jaar groter is dan 40\" is niet voldaan: wel voor B1"
                + " (1 van de 1) (1 lid niet beoordeeld)"), u.get(0).explanation());
    }
}
