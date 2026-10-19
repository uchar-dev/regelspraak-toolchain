package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Positive end-to-end tests for the {@link ScenarioRunner}: a small self-contained model
 * plus a scenario whose expectations are computed BY HAND from the rule, asserting the runner reaches PASS (and, for
 * the marker case, MARKER). Each covers one facet of the contract: an attribuut value, a kenmerk, a leeg expectation,
 * a CONSISTENT/INCONSISTENT pair, a to-many role, a parameter override, a peildatum-gated (geldig vanaf) rule, and a
 * MARKER scope (the exit-3 path).
 */
class ScenarioRunnerTest {

    private static ScenarioResult één(String source) {
        List<ScenarioResult> r = ScenarioRunner.run(source);
        assertEquals(1, r.size(), () -> "verwachtte precies één scenario, kreeg " + r.size());
        return r.get(0);
    }

    /** The contract's worked example, shrunk: heffing = afstand × tarief = 200 × 0,30 = 60,00 EUR. */
    @Test void attribute_value_and_characteristic_and_consistent() {
        ScenarioResult r = één("""
            Objecttype de Vlucht (mv: Vluchten)
                de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
                de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
                is belast kenmerk (bijvoeglijk);
            Parameter het tarief per km : Numeriek (getal met 2 decimalen) met eenheid EUR/km;
            Regel heffingsregel
            geldig altijd
            De heffing van een Vlucht moet berekend worden als de afstand tot bestemming van de Vlucht maal het tarief per km.
            Regel belastregel
            geldig altijd
            Een Vlucht is belast indien de afstand tot bestemming van de Vlucht groter is dan 100 km.
            Situatie basis:
            een Vlucht V1
            - de afstand tot bestemming is 200 km
            de parameter het tarief per km is 0,30 EUR/km
            Scenario heffing en belast
            toetst de regels heffingsregel en belastregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de heffing van V1 is 60,00 EUR
            - V1 is een belast
            - de gegevens zijn consistent
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS: " + r.diagnostic() + " / " + r.expectations());
    }

    /** A subtype instance in a role whose declared filler is its supertype: the bean's setter is typed as the filler
     *  (uchar's bean model since 2026-09-28), and the role writer finds it along the value's class line. Before that
     *  lookup the session failed with "kon setVondst(Wrakvondst) niet aanroepen op Melding" while uchar's own replay
     *  played the same case. */
    @Test void subtype_in_the_role_of_its_supertype() {
        ScenarioResult r = één("""
            Objecttype de Vondst (mv: Vondsten)
                de waarde Numeriek (geheel getal);
            Objecttype de Wrakvondst is een Vondst (mv: Wrakvondsten)
                het aantal meldingen Numeriek (geheel getal);
            Objecttype de Melding (mv: Meldingen)
                de gemelde waarde Numeriek (geheel getal);
            Feittype VondstHeeftMelding
                de vondst Vondst
                de melding (mv: meldingen) Melding
            Eén vondst heeft meerdere meldingen
            Regel gemelde waarde
            geldig altijd
            De gemelde waarde van een Melding moet gesteld worden op de waarde van de vondst van de Melding.
            Scenario subtype in de rol
            Gegeven is de volgende situatie:
            een Wrakvondst W
            - de waarde is 5
            een Melding M
            - de vondst is W
            Met als verwacht resultaat:
            - de gemelde waarde van M is 5
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS: " + r.diagnostic() + " / " + r.expectations());
    }

    /** The explanation of a played scenario: its own session fires what uchar's replay reported, and the verbaliser
     *  justifies the characteristic with the operand values the condition read. The rule with a parameter shows that
     *  the session carries the situation's parameters as globals. */
    @Test void uitleg_of_a_played_scenario() {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(dev.uchar.regelspraak.resolver.ScenarioResolver.resolve("""
            Objecttype de Vlucht (mv: Vluchten)
                de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
                de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
                is belast kenmerk (bijvoeglijk);
            Parameter het tarief per km : Numeriek (getal met 2 decimalen) met eenheid EUR/km;
            Regel heffingsregel
            geldig altijd
            De heffing van een Vlucht moet berekend worden als de afstand tot bestemming van de Vlucht maal het tarief per km.
            Regel belastregel
            geldig altijd
            Een Vlucht is belast indien de afstand tot bestemming van de Vlucht groter is dan 100 km.
            Situatie basis:
            een Vlucht V1
            - de afstand tot bestemming is 200 km
            de parameter het tarief per km is 0,30 EUR/km
            Scenario heffing en belast
            toetst de regels heffingsregel en belastregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de heffing van V1 is 60,00 EUR
            - V1 is een belast
            """));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), () -> "de eigen sessie hoort uchars replay te volgen: " + u.get(0).explanationError());
        assertEquals(1, u.get(0).explanation().size(), u.get(0).explanation()::toString);
        String sentence = u.get(0).explanation().get(0);
        assertTrue(sentence.startsWith("V1 is belast, omdat") && sentence.contains("200"), sentence);
    }

    /** The explanation of a characteristic whose condition reads a derived value. The session of the explanation fills,
     *  fires and writes back through uchar's entry, so it fires what uchar's replay reported, row for row under the
     *  name of the form, and the verbaliser reads the derived retourafstand on the bean. */
    @Test void uitleg_reads_a_derived_value_without_deviating_trace() {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(dev.uchar.regelspraak.resolver.ScenarioResolver.resolve("""
            Objecttype de Vlucht (mv: Vluchten)
                de afstand tot bestemming Numeriek (positief geheel getal);
                de retourafstand Numeriek (positief geheel getal);
                is belast kenmerk (bijvoeglijk);
            Regel retourafstandregel
            geldig altijd
            De retourafstand van een Vlucht moet berekend worden als de afstand tot bestemming van de Vlucht maal 2.
            Regel belastregel
            geldig altijd
            Een Vlucht is belast indien de retourafstand van de Vlucht groter is dan 300.
            Situatie basis:
            een Vlucht V1
            - de afstand tot bestemming is 200
            Scenario belast door de retourafstand
            toetst de regels retourafstandregel en belastregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de retourafstand van V1 is 400
            - V1 is een belast
            """));
        assertEquals(1, u.size());
        ScenarioResult r = u.get(0).result();
        assertEquals(Status.PASS, r.status(), r::diagnostic);
        assertEquals(List.of("retourafstandregel", "belastregel"),
                r.trace().firings().stream().map(ScenarioResult.Firing::rule).toList(), r.trace()::toString);
        assertNull(u.get(0).explanationError(), () -> "de eigen sessie hoort uchars replay te volgen: " + u.get(0).explanationError());
        assertEquals(List.of("V1 is belast, omdat de retourafstand van de Vlucht (400) is groter dan 300"),
                u.get(0).explanation());
    }

    /** The model of uchar's {@code SubtypeRoleFillerWitnessTest.the_reciprocal_role_also_mirrors_for_a_subtype}: W, a
     *  Wrakvondst, writes the one link of a reciprocal role filled by Vondst. uchar's replay mirrors it along the chain
     *  of W's types, and so does the explanation's session, which shares the role writer with the service: it reads
     *  V's maatwaarde as 5 and gives its explanation. Asked of W's own type alone, the session read V's maatwaarde
     *  empty, and the explanation was withheld. */
    @Test void uitleg_with_a_reciprocal_role_on_a_subtype_is_not_withheld() {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(dev.uchar.regelspraak.resolver.ScenarioResolver.resolve("""
            Objecttype de Vondst (mv: Vondsten)
                de waarde Numeriek (geheel getal);
                de maatwaarde Numeriek (geheel getal);
            Objecttype de Wrakvondst is een Vondst (mv: Wrakvondsten)
            Wederkerig feittype vondstpaar
                de maat (mv: maten) Vondst
            Eén maat heeft één maat
            Regel maatwaarde van een vondst
            geldig altijd
            De maatwaarde van een Vondst moet gesteld worden op de waarde van de maat van de Vondst.
            Scenario maat van een wrakvondst
            Gegeven is de volgende situatie:
            een Vondst V
            - de waarde is 2
            een Wrakvondst W
            - de waarde is 5
            - de maat is V
            Met als verwacht resultaat:
            - de maatwaarde van V is 5
            - de maatwaarde van W is 2
            """));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), () -> "de eigen sessie hoort uchars replay te volgen: " + u.get(0).explanationError());
    }

    /** A leeg expectation: a rule whose guard does not match leaves the doel unset → the attribuut reads leeg. */
    @Test void empty_expectation() {
        ScenarioResult r = één("""
            Objecttype de Vlucht (mv: Vluchten)
                de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
                de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
            Regel heffingsregel
            geldig altijd
            De heffing van een Vlucht moet berekend worden als 10,00 EUR indien de afstand tot bestemming van de Vlucht groter is dan 500 km.
            Situatie basis:
            een Vlucht V1
            - de afstand tot bestemming is 200 km
            Scenario heffing blijft leeg
            toetst de regel heffingsregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de heffing van V1 is leeg
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS (200 km < 500 km, dus geen heffing): "
                + r.diagnostic() + " / " + r.expectations());
    }

    /** A §9.5 consistency rule (a "moet"-vergelijking): the two ports differ → CONSISTENT; equal → INCONSISTENT. */
    @Test void consistent_and_inconsistent_pair() {
        String model = """
            Objecttype de Vlucht (mv: Vluchten)
                de vertrekcode Tekst;
                de bestemmingscode Tekst;
            Regel geen rondvlucht
            geldig altijd
            De vertrekcode van een Vlucht moet ongelijk zijn aan de bestemmingscode van de Vlucht.
            """;
        // consistent: AMS != BRU
        ScenarioResult ok = één(model + """
            Situatie basis:
            een Vlucht V1
            - de vertrekcode is "AMS"
            - de bestemmingscode is "BRU"
            Scenario verschillend is consistent
            toetst de regel geen rondvlucht
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de gegevens zijn consistent
            """);
        assertEquals(Status.PASS, ok.status(), () -> "verwacht PASS (AMS != BRU): " + ok.diagnostic() + " / " + ok.expectations());

        // inconsistent: AMS == AMS (a rondvlucht)
        ScenarioResult error = één(model + """
            Situatie rondvlucht:
            een Vlucht V1
            - de vertrekcode is "AMS"
            - de bestemmingscode is "AMS"
            Scenario gelijk is inconsistent
            toetst de regel geen rondvlucht
            gebaseerd op de situatie rondvlucht
            Met als verwacht resultaat:
            - de gegevens zijn inconsistent
            """);
        assertEquals(Status.PASS, error.status(), () -> "verwacht PASS (AMS == AMS is inconsistent): "
                + error.diagnostic() + " / " + error.expectations());
    }

    /** A to-many aggregation (som van alle deelnemers), wired from the MANY side's to-one reference: each
     *  Deelnemer's {@code contingent} points at C1, and the som-van-alle over that rol totals 30 + 12 = 42. */
    @Test void to_many_role() {
        ScenarioResult r = één("""
            Objecttype het Contingent (mv: Contingenten)
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer (mv: Deelnemers)
                de inbreng Numeriek (geheel getal);
            Feittype Contingent met deelnemers
                het contingent Contingent
                de deelnemer Deelnemer
            Eén contingent betreft meerdere deelnemers
            Regel totaalregel
            geldig altijd
            De totaal van een Contingent moet berekend worden als de som van de inbreng van alle deelnemers van het Contingent.
            Situatie basis:
            een Contingent C1
            - de totaal is 0
            een Deelnemer D1
            - de inbreng is 30
            - de contingent is C1
            een Deelnemer D2
            - de inbreng is 12
            - de contingent is C1
            Scenario som over de rol
            toetst de regel totaalregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaal van C1 is 42
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS (30 + 12 = 42): "
                + r.diagnostic() + " / " + r.expectations());
    }

    /** The to-many {@code zijn}-list form on the ONE side: {@code de deelnemer zijn D1 en D2} — the runner wires
     *  each MANY-side target's reverse to-one reference back to the ONE-side subject. Same total 42. */
    @Test void to_many_role_its_list() {
        ScenarioResult r = één("""
            Objecttype het Contingent (mv: Contingenten)
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer (mv: Deelnemers)
                de inbreng Numeriek (geheel getal);
            Feittype Contingent met deelnemers
                het contingent Contingent
                de deelnemer Deelnemer
            Eén contingent betreft meerdere deelnemers
            Regel totaalregel
            geldig altijd
            De totaal van een Contingent moet berekend worden als de som van de inbreng van alle deelnemers van het Contingent.
            Situatie basis:
            een Contingent C1
            - de totaal is 0
            - de deelnemer zijn D1 en D2
            een Deelnemer D1
            - de inbreng is 30
            een Deelnemer D2
            - de inbreng is 12
            Scenario som over de zijn-lijst
            toetst de regel totaalregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de totaal van C1 is 42
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS (30 + 12 = 42 via zijn-lijst): "
                + r.diagnostic() + " / " + r.expectations());
    }

    /** A later parameterwaarde overrides an earlier one (the "forceer parameterwaarde" analog). */
    @Test void parameter_override() {
        ScenarioResult r = één("""
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
            Parameter het basistarief : Numeriek (getal met 2 decimalen) met eenheid EUR;
            Regel heffingsregel
            geldig altijd
            De heffing van een Vlucht moet berekend worden als het basistarief.
            Situatie basis:
            de parameter het basistarief is 10,00 EUR
            Scenario override
            toetst de regel heffingsregel
            gebaseerd op de situatie basis
            Gegeven is de volgende situatie:
            een Vlucht V1
            - de heffing is 0,00 EUR
            de parameter het basistarief is 25,00 EUR
            Met als verwacht resultaat:
            - de heffing van V1 is 25,00 EUR
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS (latere parameter 25,00 wint): "
                + r.diagnostic() + " / " + r.expectations());
    }

    /** A §4.2 geldig-vanaf rule: the peildatum sets the rekendatum so the version window is open. The scenario is
     *  not named "met peildatum": in uchar's grammar that phrase is the header token that sets the peildatum, and a
     *  scenario name cannot contain it. */
    @Test void as_of_date_valid_from() {
        ScenarioResult r = één("""
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
            Regel heffingsregel
            geldig vanaf 01-01-2020
            De heffing van een Vlucht moet berekend worden als 20,00 EUR.
            Situatie basis:
            een Vlucht V1
            - de heffing is 0,00 EUR
            Scenario venster open op de peildatum
            toetst de regel heffingsregel
            met peildatum 01-06-2024
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de heffing van V1 is 20,00 EUR
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS (peildatum 2024 na 2020): "
                + r.diagnostic() + " / " + r.expectations());
    }

    /** A LOWERED rule whose emitted DRL carries a Tekst literal that CONTAINS the marker sentinel ("/* TODO") must
     *  NOT be mistaken for a marker and stripped — the strip blanks string literals first (as the manifest does). */
    @Test void text_literal_with_marker_sentinel_is_not_stripped() {
        ScenarioResult r = één("""
            Objecttype de Vlucht (mv: Vluchten)
                de omschrijving Tekst;
                is bijzonder kenmerk (bijvoeglijk);
            Regel merkregel
            geldig altijd
            Een Vlucht is bijzonder indien de omschrijving van de Vlucht gelijk is aan "/* TODO afhandelen".
            Situatie basis:
            een Vlucht V1
            - de omschrijving is "/* TODO afhandelen"
            Scenario todo-in-literal
            toetst de regel merkregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - V1 is een bijzonder
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS (de lowered regel mag niet als marker gestript "
                + "worden ook al bevat de tekst-literal '/* TODO'): " + r.diagnostic() + " / " + r.expectations());
    }

    /** A MARKER scope: a value the transpiler cannot lower stays an honest marker → status MARKER (exit-3 path).
     *  uchar's replay refuses the document before a case plays, so the report names the rule in its reason and
     *  carries no coverage entry for it. */
    @Test void marker_scope() {
        ScenarioResult r = één("""
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (niet-negatief geheel getal);
                is belast kenmerk (bijvoeglijk) voor elke dag;
            Regel heffingsregel
            geldig altijd
            De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
            Situatie basis:
            een Vlucht V1
            - de heffing is 5
            Scenario marker-oefening
            toetst de regel heffingsregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de heffing van V1 is 5
            """);
        assertEquals(Status.MARKER, r.status(), () -> "verwacht MARKER: " + r.diagnostic());
        assertTrue(r.diagnostic().contains("heffingsregel"), () -> "de reden hoort de regel te noemen: " + r.diagnostic());
        assertTrue(r.coverage().isEmpty(), () -> "een niet-gespeeld document draagt geen dekking: " + r.coverage());
    }
}
