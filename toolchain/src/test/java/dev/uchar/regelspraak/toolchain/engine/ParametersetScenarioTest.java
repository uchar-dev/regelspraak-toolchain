package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
import dev.uchar.regelspraak.ir.Geldigheid;
import dev.uchar.regelspraak.model.Parameterset;
import dev.uchar.regelspraak.model.Parameterset.WaardeInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FASE 4 (card {@code 20260705-15:18-geldigheid-parametersets-peildatum.md}): de scenario-kop-peildatum drijft de
 * parameterset-vintage-selectie in de {@link ScenarioRunner}. Elke test pint een facet:
 * <ul>
 *   <li>{@code s1} — G1(a)-KERN: twee scenario's die ALLEEN in kop-peildatum verschillen (2025 vs 2026) leveren op
 *       één vintage-set (2025→18 / 2026→21) DIFFERENTE, individueel gepinde uitkomsten. De parameter staat NIET in de
 *       situatie — de sets leveren hem, dus dit test ook de parametergate-doorlaat.</li>
 *   <li>{@code s2} — geen peildatum + multi-vintage → {@link ScenarioRunner.ResolveFout} die het scenario + 'peildatum' noemt.</li>
 *   <li>{@code s3} — gap: kop-peildatum buiten alle vintages → ResolveFout die parameter + peildatum noemt.</li>
 *   <li>{@code s4} — situatie-override: het scenario zet de parameter óók in de situatie → de situatiewaarde wint.</li>
 *   <li>{@code s5} — degeneraat: één ALTIJD-waarde + scenario ZONDER peildatum → loopt (EPOCH-resolutie).</li>
 *   <li>{@code s6} — bestaand pad: {@code run(bron)} zonder sets blijft werken.</li>
 * </ul>
 */
class ParametersetScenarioTest {

    /** Een bron met een objecttype, een parameter (NIET in de situatie), een regel die de parameter kopieert, en
     *  twee scenario's die alleen in kop-peildatum verschillen. {@code peildatum1}/{@code peildatum2} zijn de
     *  dd-MM-yyyy-koppen; {@code extraSituatie} kan een situatie-regel injecteren (s4-override), of "".*/
    private static String bron(String peildatum1, String peildatum2, String extraSituatie) {
        return """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Situatie basis:
            een Persoon P1
            %s
            Scenario twee-duizend-vijfentwintig
            toetst de regel grensregel
            met peildatum %s
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is %d
            Scenario twee-duizend-zesentwintig
            toetst de regel grensregel
            met peildatum %s
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is %d
            """.formatted(extraSituatie, peildatum1, 18, peildatum2, 21);
    }

    /** De vintage-set: volwassenleeftijd = 18 in 2025, 21 vanaf 2026 (aangrenzend, geen overlap). */
    private static List<Parameterset> vintageSet() {
        return List.of(new Parameterset("leeftijden", Map.of(
                "volwassenleeftijd", List.of(
                        new WaardeInterval(new BigDecimal("18"),
                                new Geldigheid(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))),
                        new WaardeInterval(new BigDecimal("21"),
                                new Geldigheid(LocalDate.of(2026, 1, 1), null))))));
    }

    // ---------------- s1 : G1(a) kern ----------------

    @Test void s1_kop_peildatum_drijft_vintage_verschillende_uitkomsten() {
        String bron = bron("01-06-2025", "01-06-2026", "");
        List<ScenarioResultaat> r = ScenarioRunner.run(bron, vintageSet());
        assertEquals(2, r.size());
        // scenario 2025 -> volwassenleeftijd 18 ; scenario 2026 -> 21 ; beide PASS.
        assertEquals(Status.PASS, r.get(0).status(),
                () -> "verwacht PASS (2025-vintage = 18): " + r.get(0).diagnose() + " / " + r.get(0).verwachtingen());
        assertEquals(Status.PASS, r.get(1).status(),
                () -> "verwacht PASS (2026-vintage = 21): " + r.get(1).diagnose() + " / " + r.get(1).verwachtingen());
    }

    // ---------------- s1b : G4 op het scenario-transport (Rekendatum + vintage, één kop-peildatum) ----------------

    @Test void s1b_een_kop_peildatum_drijft_rekendatum_en_vintage() {
        // G4: een regel die de Rekendatum leest ÉN een vintage-geselecteerde parameter — één kop-peildatum drijft
        // beide (de één-datumautoriteit op het scenario-transport; de service-kant pint dit in ParametersetServiceTest).
        String bron = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
                het geboortejaar Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Regel jaarregel
            geldig altijd
            Het geboortejaar van een Persoon moet berekend worden als het jaar uit de Rekendatum.
            Situatie basis:
            een Persoon P1
            Scenario een datum drijft beide
            toetst de regels grensregel en jaarregel
            met peildatum 01-06-2026
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is 21
            - het geboortejaar van P1 is 2026
            """;
        List<ScenarioResultaat> r = ScenarioRunner.run(bron, vintageSet());
        assertEquals(1, r.size());
        assertEquals(Status.PASS, r.get(0).status(),
                () -> "één peildatum moet zowel de 2026-vintage (21) als Rekenjaar 2026 dragen: "
                        + r.get(0).diagnose() + " / " + r.get(0).verwachtingen());
    }

    // ---------------- s2 : geen peildatum + multi-vintage ----------------

    @Test void s2_geen_peildatum_multi_vintage_is_resolvefout() {
        // Eén scenario zonder 'met peildatum', op de multi-vintage set.
        String bron = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Situatie basis:
            een Persoon P1
            Scenario zonder peildatum
            toetst de regel grensregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is 18
            """;
        var fout = assertThrows(ScenarioRunner.ResolveFout.class, () -> ScenarioRunner.run(bron, vintageSet()));
        String bericht = String.join("\n", fout.diagnostiek());
        assertTrue(bericht.contains("zonder peildatum"), () -> "noemt het scenario: " + bericht);
        assertTrue(bericht.contains("peildatum"), () -> "noemt 'peildatum': " + bericht);
    }

    // ---------------- s3 : gap ----------------

    @Test void s3_peildatum_buiten_alle_vintages_is_resolvefout() {
        // peildatum in 2024 — vóór het eerste interval (2025) -> gap.
        String bron = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Situatie basis:
            een Persoon P1
            Scenario twee-duizend-vierentwintig
            toetst de regel grensregel
            met peildatum 01-06-2024
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is 18
            """;
        var fout = assertThrows(ScenarioRunner.ResolveFout.class, () -> ScenarioRunner.run(bron, vintageSet()));
        String bericht = String.join("\n", fout.diagnostiek());
        assertTrue(bericht.contains("volwassenleeftijd"), () -> "noemt de parameter: " + bericht);
        assertTrue(bericht.contains("2024-06-01"), () -> "noemt de peildatum: " + bericht);
    }

    // ---------------- s4 : situatie-override ----------------

    @Test void s4_situatie_override_wint_van_sets() {
        // Het scenario zet de parameter óók in de situatie op 40 -> de situatiewaarde wint, sets-waarde genegeerd.
        String bron = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Situatie basis:
            een Persoon P1
            de parameter de volwassenleeftijd is 40
            Scenario override
            toetst de regel grensregel
            met peildatum 01-06-2025
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is 40
            """;
        List<ScenarioResultaat> r = ScenarioRunner.run(bron, vintageSet());
        assertEquals(1, r.size());
        assertEquals(Status.PASS, r.get(0).status(),
                () -> "verwacht PASS (situatie 40 overridet sets-vintage 18): "
                        + r.get(0).diagnose() + " / " + r.get(0).verwachtingen());
    }

    // ---------------- s5 : degeneraat (één ALTIJD-waarde, geen peildatum) ----------------

    @Test void s5_degeneraat_altijd_waarde_zonder_peildatum_loopt() {
        List<Parameterset> altijd = List.of(new Parameterset("enkel", Map.of(
                "volwassenleeftijd", List.of(new WaardeInterval(new BigDecimal("18"), Geldigheid.ALTIJD)))));
        String bron = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Situatie basis:
            een Persoon P1
            Scenario zonder peildatum degeneraat
            toetst de regel grensregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is 18
            """;
        List<ScenarioResultaat> r = ScenarioRunner.run(bron, altijd);
        assertEquals(1, r.size());
        assertEquals(Status.PASS, r.get(0).status(),
                () -> "verwacht PASS (EPOCH-resolutie, datum-onafhankelijk = 18): "
                        + r.get(0).diagnose() + " / " + r.get(0).verwachtingen());
    }

    // ---------------- s6 : bestaand pad zonder sets ----------------

    @Test void s6_bestaand_pad_zonder_sets_blijft_werken() {
        // De parameter staat in de situatie (geen sets nodig) — het bestaande run(bron)-pad, byte-identiek.
        String bron = """
            Objecttype de Persoon (mv: Personen)
                de grens Numeriek (geheel getal);
            Parameter de volwassenleeftijd : Numeriek (geheel getal);
            Regel grensregel
            geldig altijd
            De grens van een Persoon moet berekend worden als de volwassenleeftijd.
            Situatie basis:
            een Persoon P1
            de parameter de volwassenleeftijd is 18
            Scenario situatie levert de parameter
            toetst de regel grensregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de grens van P1 is 18
            """;
        List<ScenarioResultaat> r = ScenarioRunner.run(bron);
        assertEquals(1, r.size());
        assertEquals(Status.PASS, r.get(0).status(),
                () -> "verwacht PASS (situatie-waarde 18, geen sets): "
                        + r.get(0).diagnose() + " / " + r.get(0).verwachtingen());
    }
}
