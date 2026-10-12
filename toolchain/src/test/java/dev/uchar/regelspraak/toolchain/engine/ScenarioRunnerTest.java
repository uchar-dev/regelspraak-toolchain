package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
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

    private static ScenarioResultaat één(String bron) {
        List<ScenarioResultaat> r = ScenarioRunner.run(bron);
        assertEquals(1, r.size(), () -> "verwachtte precies één scenario, kreeg " + r.size());
        return r.get(0);
    }

    /** The contract's worked example, shrunk: heffing = afstand × tarief = 200 × 0,30 = 60,00 EUR. */
    @Test void attribuutwaarde_en_kenmerk_en_consistent() {
        ScenarioResultaat r = één("""
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
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS: " + r.diagnose() + " / " + r.verwachtingen());
    }

    /** A subtype instance in a role whose declared filler is its supertype: the bean's setter is typed as the filler
     *  (uchar's bean model since 2026-09-28), and the role writer finds it along the value's class line. Before that
     *  lookup the session failed with "kon setVondst(Wrakvondst) niet aanroepen op Melding" while uchar's own replay
     *  played the same case. */
    @Test void subtype_in_de_rol_van_zijn_supertype() {
        ScenarioResultaat r = één("""
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
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS: " + r.diagnose() + " / " + r.verwachtingen());
    }

    /** The explanation of a played scenario: its own session fires what uchar's replay reported, and the verbaliser
     *  justifies the characteristic with the operand values the condition read. The rule with a parameter shows that
     *  the session carries the situation's parameters as globals. */
    @Test void uitleg_van_een_gespeeld_scenario() {
        List<ScenarioRunner.Uitgelegd> u = ScenarioRunner.runMetUitleg(dev.uchar.regelspraak.resolver.ScenarioResolver.resolve("""
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
        assertEquals(Status.PASS, u.get(0).resultaat().status(), () -> u.get(0).resultaat().diagnose());
        assertNull(u.get(0).uitlegFout(), () -> "de eigen sessie hoort uchars replay te volgen: " + u.get(0).uitlegFout());
        assertEquals(1, u.get(0).uitleg().size(), u.get(0).uitleg()::toString);
        String zin = u.get(0).uitleg().get(0);
        assertTrue(zin.startsWith("V1 is belast, omdat") && zin.contains("200"), zin);
    }

    /** The explanation of a characteristic whose condition reads a derived value. The session of the explanation fills,
     *  fires and writes back through uchar's entry, so it fires what uchar's replay reported, row for row under the
     *  name of the form, and the verbaliser reads the derived retourafstand on the bean. */
    @Test void uitleg_leest_een_afgeleide_waarde_zonder_afwijkend_spoor() {
        List<ScenarioRunner.Uitgelegd> u = ScenarioRunner.runMetUitleg(dev.uchar.regelspraak.resolver.ScenarioResolver.resolve("""
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
        ScenarioResultaat r = u.get(0).resultaat();
        assertEquals(Status.PASS, r.status(), r::diagnose);
        assertEquals(List.of("retourafstandregel", "belastregel"),
                r.trace().vuringen().stream().map(ScenarioResultaat.Vuring::regel).toList(), r.trace()::toString);
        assertNull(u.get(0).uitlegFout(), () -> "de eigen sessie hoort uchars replay te volgen: " + u.get(0).uitlegFout());
        assertEquals(List.of("V1 is belast, omdat de retourafstand van de Vlucht (400) is groter dan 300"),
                u.get(0).uitleg());
    }

    /** A leeg expectation: a rule whose guard does not match leaves the doel unset → the attribuut reads leeg. */
    @Test void leeg_verwachting() {
        ScenarioResultaat r = één("""
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
                + r.diagnose() + " / " + r.verwachtingen());
    }

    /** A §9.5 consistency rule (a "moet"-vergelijking): the two ports differ → CONSISTENT; equal → INCONSISTENT. */
    @Test void consistent_en_inconsistent_paar() {
        String model = """
            Objecttype de Vlucht (mv: Vluchten)
                de vertrekcode Tekst;
                de bestemmingscode Tekst;
            Regel geen rondvlucht
            geldig altijd
            De vertrekcode van een Vlucht moet ongelijk zijn aan de bestemmingscode van de Vlucht.
            """;
        // consistent: AMS != BRU
        ScenarioResultaat ok = één(model + """
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
        assertEquals(Status.PASS, ok.status(), () -> "verwacht PASS (AMS != BRU): " + ok.diagnose() + " / " + ok.verwachtingen());

        // inconsistent: AMS == AMS (a rondvlucht)
        ScenarioResultaat fout = één(model + """
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
        assertEquals(Status.PASS, fout.status(), () -> "verwacht PASS (AMS == AMS is inconsistent): "
                + fout.diagnose() + " / " + fout.verwachtingen());
    }

    /** A to-many aggregation (som van alle deelnemers), wired from the MANY side's to-one reference: each
     *  Deelnemer's {@code contingent} points at C1, and the som-van-alle over that rol totals 30 + 12 = 42. */
    @Test void naar_veel_rol() {
        ScenarioResultaat r = één("""
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
                + r.diagnose() + " / " + r.verwachtingen());
    }

    /** The to-many {@code zijn}-list form on the ONE side: {@code de deelnemer zijn D1 en D2} — the runner wires
     *  each MANY-side target's reverse to-one reference back to the ONE-side subject. Same total 42. */
    @Test void naar_veel_rol_zijn_lijst() {
        ScenarioResultaat r = één("""
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
                + r.diagnose() + " / " + r.verwachtingen());
    }

    /** A later parameterwaarde overrides an earlier one (the "forceer parameterwaarde" analog). */
    @Test void parameter_override() {
        ScenarioResultaat r = één("""
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
                + r.diagnose() + " / " + r.verwachtingen());
    }

    /** A §4.2 geldig-vanaf rule: the peildatum sets the rekendatum so the version window is open. The scenario is
     *  not named "met peildatum": in uchar's grammar that phrase is the header token that sets the peildatum, and a
     *  scenario name cannot contain it. */
    @Test void peildatum_geldig_vanaf() {
        ScenarioResultaat r = één("""
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
                + r.diagnose() + " / " + r.verwachtingen());
    }

    /** A LOWERED rule whose emitted DRL carries a Tekst literal that CONTAINS the marker sentinel ("/* TODO") must
     *  NOT be mistaken for a marker and stripped — the strip blanks string literals first (as the manifest does). */
    @Test void tekstliteral_met_marker_sentinel_wordt_niet_gestript() {
        ScenarioResultaat r = één("""
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
                + "worden ook al bevat de tekst-literal '/* TODO'): " + r.diagnose() + " / " + r.verwachtingen());
    }

    /** A MARKER scope: a value the transpiler cannot lower stays an honest marker → status MARKER (exit-3 path).
     *  uchar's replay refuses the document before a case plays, so the report names the rule in its reason and
     *  carries no coverage entry for it. */
    @Test void marker_scope() {
        ScenarioResultaat r = één("""
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
        assertEquals(Status.MARKER, r.status(), () -> "verwacht MARKER: " + r.diagnose());
        assertTrue(r.diagnose().contains("heffingsregel"), () -> "de reden hoort de regel te noemen: " + r.diagnose());
        assertTrue(r.dekking().isEmpty(), () -> "een niet-gespeeld document draagt geen dekking: " + r.dekking());
    }
}
