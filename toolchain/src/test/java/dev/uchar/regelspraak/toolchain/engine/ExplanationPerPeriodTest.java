package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A defaults group on a time-dependent attribute decides per day (uchar's RUNTIME-SEMANTICS ch. 7.11): on every day the
 * highest default that decides that day, a conflict of the members it reconciles, or no default, and then the input of
 * that day stands. uchar names that per period ({@code DroolsBackend.periodDecisionsOf}), and the explanation gives a
 * sentence per period with a decider, a conflict or an input: the default that decided with its value and its
 * reason, the conflict with its members, or the input that stood. Own material after uchar's fixture
 * {@code ref/test/fixtures/defaults-per-day/ongeordend} and its golden {@code jutterswet-gevolg-buiten-de-beurt}.
 */
class ExplanationPerPeriodTest {

    /** Two unordered exceptions on a toelage per month, beside an input of 5 over 2025. J1 is kustwacht from January to
     *  April and stormwacht from March to June: the kustwacht decides January and February, the two conflict in March
     *  and April, where the input stands, the stormwacht decides May and June, and the input stands from July. J2 is
     *  neither and has no input: no default decides any day. */
    private static final String UNORDERED = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                is kustwacht kenmerk (bijvoeglijk) voor elke maand;
                is stormwacht kenmerk (bijvoeglijk) voor elke maand;
                de toelage Numeriek (geheel getal) voor elke maand;

            Uitzonderingen ongeordend:
            Regel toelage voor kustwacht
                geldig altijd
                    De toelage van een Jutter moet gesteld worden op 10
                    indien hij kustwacht is.
            Regel toelage voor stormwacht
                geldig altijd
                    De toelage van een Jutter moet gesteld worden op 20
                    indien hij stormwacht is.

            Scenario kust en storm
            Gegeven is de volgende situatie:
            een Jutter J1
            - de toelage is 5 van 01-01-2025 tot 01-01-2026
            - is kustwacht van 01-01-2025 tot 01-05-2025
            - is stormwacht van 01-03-2025 tot 01-07-2025
            een Jutter J2
            Met als verwacht resultaat:
            - de toelage van J1 is 10 van 01-01-2025 tot 01-03-2025
            - de toelage van J1 is 5 van 01-03-2025 tot 01-05-2025
            - de toelage van J1 is 20 van 01-05-2025 tot 01-07-2025
            - de toelage van J1 is 5 van 01-07-2025 tot 01-01-2026
            - de gegevens zijn inconsistent volgens "Jutter.toelage" voor J1
            """;

    @Test
    void a_period_names_its_decider_its_conflict_or_the_input_that_stood() {
        ScenarioRunner.Explained u = explained(UNORDERED);
        assertEquals(List.of(
                "J1 heeft als toelage 10 van 01-01-2025 tot 01-03-2025 volgens de regel 'toelage voor kustwacht': 10,"
                        + " omdat hij kustwacht is (van 01-01-2025 tot 01-05-2025)",
                "J1 houdt als toelage de invoerwaarde 5 van 01-03-2025 tot 01-05-2025, want er is een conflict: de regel"
                        + " 'toelage voor kustwacht' besliste 'heeft als toelage 10' en de regel 'toelage voor"
                        + " stormwacht' besliste 'heeft als toelage 20', dus de groep schrijft niets",
                "J1 heeft als toelage 20 van 01-05-2025 tot 01-07-2025 volgens de regel 'toelage voor stormwacht': 20,"
                        + " omdat hij stormwacht is (van 01-03-2025 tot 01-07-2025)",
                "J1 houdt als toelage de invoerwaarde 5 van 01-07-2025 tot 01-01-2026, want geen variant besliste",
                "J2 heeft geen toelage, want geen variant besliste"),
                u.explanation());
        // the value of a period is the value of a timeline target: a VALUE with timeline: true
        Part value = u.sentences().get(0).parts().stream().filter(p -> p.kind() == Part.Kind.VALUE).findFirst()
                .orElseThrow();
        assertEquals("10 van 01-01-2025 tot 01-03-2025", value.text(), value.toString());
        assertEquals(Boolean.TRUE, value.meta().get("timeline"), value.toString());
    }

    /** uchar's golden jutterswet-gevolg-buiten-de-beurt with the kustloon as a timeline: the base 5 with two exceptions,
     *  each with a condition per period. J2 is kustwacht and winterwacht in April; the winter exception decides April,
     *  and the base the days before and after it, each period its own sentence. */
    private static final String CHAIN = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                is kustwacht kenmerk (bijvoeglijk) voor elke maand;
                is winterwacht kenmerk (bijvoeglijk) voor elke maand;
                het strandloon Numeriek (geheel getal) voor elke maand;
                het kustloon Numeriek (geheel getal) voor elke maand;

            Regel strandloon
                geldig altijd
                    Het strandloon van een Jutter moet gesteld worden op 5.

            Uitzondering op strandloon: strandloon voor kustwachters
                geldig altijd
                    Het strandloon van een Jutter moet berekend worden als zijn kustloon
                    indien hij kustwacht is.

            Uitzondering op strandloon: strandloon in de winter
                geldig altijd
                    Het strandloon van een Jutter moet gesteld worden op 60
                    indien hij winterwacht is.

            Scenario winter boven kust
            Gegeven is de volgende situatie:
            een Jutter J2
            - het kustloon is 40 van 01-01-2025 tot 01-01-2026
            - is kustwacht van 01-04-2025 tot 01-05-2025
            - is winterwacht van 01-04-2025 tot 01-05-2025
            Met als verwacht resultaat:
            - het strandloon van J2 is 5 tot 01-04-2025
            - het strandloon van J2 is 60 van 01-04-2025 tot 01-05-2025
            - het strandloon van J2 is 5 vanaf 01-05-2025
            """;

    @Test
    void one_default_that_decides_two_periods_has_a_sentence_for_each() {
        assertEquals(List.of(
                "J2 heeft als strandloon 5 tot 01-04-2025 volgens de regel 'strandloon' (onvoorwaardelijk): 5",
                "J2 heeft als strandloon 60 van 01-04-2025 tot 01-05-2025 volgens 'strandloon in de winter': 60, omdat"
                        + " hij winterwacht is (van 01-04-2025 tot 01-05-2025)",
                "J2 heeft als strandloon 5 vanaf 01-05-2025 volgens de regel 'strandloon' (onvoorwaardelijk): 5"),
                explained(CHAIN).explanation());
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
