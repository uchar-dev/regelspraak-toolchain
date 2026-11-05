package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A writer of a timeline target whose condition per period (uchar's RUNTIME-SEMANTICS ch. 3.10) holds on no day has the
 * reading {@code HOLDS} with a condition that reads no period (ch. 5.12). It decided no day because its condition held
 * nowhere, not because it gave leeg where it held: the explanation names it as a scalar rule that did not hold, "de
 * regel '…' gold op geen dag, want niet geldt: <the condition with the periods the engine read>", and "gaf geen waarde"
 * stays for a writer that held on some day and gave leeg there. A rule and an initialisation are the same kind of
 * writer here. Own material after uchar's golden {@code jutterswet-initialisatie-per-dag}.
 */
class ExplanationHeldOnNoDayTest {

    /** J1 is never stormwacht and kustwacht from March to May; an input 7 on the stormloon in January and February, no
     *  toeslag. The stormloon has one rule, under stormwacht; the kustloon a rule under kustwacht and an initialisation
     *  under stormwacht; the havenloon a rule that gives the empty toeslag under kustwacht and one under stormwacht; the
     *  wachtloon only an initialisation under stormwacht. */
    private static final String SOURCE = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                het stormloon Numeriek (geheel getal) voor elke maand;
                het kustloon Numeriek (geheel getal) voor elke maand;
                het havenloon Numeriek (geheel getal) voor elke maand;
                het wachtloon Numeriek (geheel getal) voor elke maand;
                de toeslag Numeriek (geheel getal) voor elke maand;
                is stormwacht kenmerk (bijvoeglijk) voor elke maand;
                is kustwacht kenmerk (bijvoeglijk) voor elke maand;

            Regel stormloon
                geldig altijd
                    Het stormloon van een Jutter moet gesteld worden op 20 gedurende de tijd dat hij stormwacht is.

            Regel kustloon
                geldig altijd
                    Het kustloon van een Jutter moet gesteld worden op 30 gedurende de tijd dat hij kustwacht is.

            Regel standaardkustloon
                geldig altijd
                    Het kustloon van een Jutter moet geïnitialiseerd worden op 5 gedurende de tijd dat hij stormwacht is.

            Regel havenloon
                geldig altijd
                    Het havenloon van een Jutter moet gesteld worden op zijn toeslag gedurende de tijd dat hij kustwacht is.

            Regel havenloon in de storm
                geldig altijd
                    Het havenloon van een Jutter moet gesteld worden op 40 gedurende de tijd dat hij stormwacht is.

            Regel standaardwachtloon
                geldig altijd
                    Het wachtloon van een Jutter moet geïnitialiseerd worden op 5 gedurende de tijd dat hij stormwacht is.

            Scenario nooit stormwacht
            Gegeven is de volgende situatie:
            een Jutter J1
            - het stormloon is 7 van 01-01-2025 tot 01-03-2025
            - is kustwacht van 01-03-2025 tot 01-06-2025
            Met als verwacht resultaat:
            - het stormloon van J1 is 7 van 01-01-2025 tot 01-03-2025
            - het kustloon van J1 is 30 van 01-03-2025 tot 01-06-2025
            """;

    /** The stormloon: the only rule held on no day, so no rule held and the input stands; the kustloon: the rule
     *  decided, the initialisation beside it held on no day; the havenloon: the rule under kustwacht held and gave
     *  leeg, the rule under stormwacht held on no day; the wachtloon: the only initialisation held on no day. */
    @Test
    void a_writer_whose_condition_holds_on_no_day_did_not_hold_and_one_that_gave_leeg_gave_no_value() {
        assertEquals(List.of(
                "J1 houdt als stormloon de invoerwaarde 7 van 01-01-2025 tot 01-03-2025, want geen regel gold; de regel"
                        + " 'stormloon' gold op geen dag, want niet geldt: hij stormwacht is (altijd leeg)",
                "J1 heeft als kustloon 30 van 01-03-2025 tot 01-06-2025 volgens de regel 'kustloon', omdat hij kustwacht"
                        + " is (van 01-03-2025 tot 01-06-2025); de regel 'standaardkustloon' gold op geen dag, want niet"
                        + " geldt: hij stormwacht is (altijd leeg)",
                "J1 heeft geen havenloon, want de regel 'havenloon' gaf geen waarde, omdat hij kustwacht is (van"
                        + " 01-03-2025 tot 01-06-2025); de regel 'havenloon in de storm' gold op geen dag, want niet"
                        + " geldt: hij stormwacht is (altijd leeg)",
                "J1 heeft geen wachtloon, want geen regel gold; de regel 'standaardwachtloon' gold op geen dag, want"
                        + " niet geldt: hij stormwacht is (altijd leeg)"),
                explained(SOURCE).explanation());
    }

    /** The writer that held on no day is a group VARIANT that did not decide, with the connectives of a scalar rule
     *  that did not hold and its condition as rule text with the periods the engine read, as a READING. */
    @Test
    void the_writer_that_held_on_no_day_is_a_variant_with_its_condition_and_its_reading() {
        Sentence kustloon = explained(SOURCE).sentences().get(1);
        Part variant = kustloon.parts().stream().filter(p -> p.kind() == Part.Kind.VARIANT).findFirst().orElseThrow();
        assertEquals(Part.meta("decided", false), variant.meta(), variant.toString());
        List<String> leaves = new ArrayList<>();
        for (Part p : variant.parts())
            leaves.add(p.kind() + (p.kind().isGroup() ? "[" + p.parts().stream().map(q -> q.kind() + ":" + q.text())
                    .toList() + "]" : ":" + p.text()));
        assertEquals(List.of("TEXT:; de regel '", "RULE:standaardkustloon", "TEXT:' ", "CONNECTIVE:gold op geen dag",
                "TEXT:, ", "CONNECTIVE:want", "TEXT: ", "CONNECTIVE:niet geldt", "TEXT:: ", "QUOTE:hij stormwacht is",
                "READING[[TEXT: (, VALUE:altijd leeg, TEXT:)]]"), leaves);
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
