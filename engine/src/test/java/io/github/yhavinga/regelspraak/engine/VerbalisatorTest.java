package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.Status;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link Verbalisator} — the UITLEG-generator — through the {@link ScenarioRunner} end to end
 * (the same wiring the {@code --uitleg} CLI and the {@code ?uitleg=true} service field drive). Each mini model
 * has a HAND-COMPUTED outcome, so the assertions are EXACT sentences, not a mere "non-empty": the verhaal must
 * justify a kenmerk with the norm AND the evaluated feiten in NL-notatie, and — for a kenmerk that was withheld
 * — name the first failing conjunct with its evaluated value.
 *
 * <p>The final test is a GOLDEN over a real TOKA scenario (toka-18 "minderjarig", loaded verbatim from the
 * golden manifest), pinning the exact verhaal the analyst set produces — the regression anchor for the feature.
 */
class VerbalisatorTest {

    private static ScenarioResultaat één(String bron) {
        List<ScenarioResultaat> r = ScenarioRunner.run(bron);
        assertEquals(1, r.size(), () -> "verwachtte precies één scenario, kreeg " + r.size());
        return r.get(0);
    }

    /** A fired bijvoeglijk kenmerk: the verhaal states the kenmerk AND justifies it with the comparison, showing
     *  the read feit (200 km) against the literal grens (100 km) — the norm and the fact, once each. */
    @Test void toegekend_kenmerk_verantwoordt_zich_met_de_feiten() {
        ScenarioResultaat r = één("""
            Objecttype de Vlucht (mv: Vluchten)
                de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
                is belast kenmerk (bijvoeglijk);
            Regel belastregel
            geldig altijd
            Een Vlucht is belast indien de afstand tot bestemming van de Vlucht groter is dan 100 km.
            Situatie basis:
            een Vlucht V1
            - de afstand tot bestemming is 200 km
            Scenario belast
            toetst de regel belastregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - V1 is een belast
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS: " + r.diagnose());
        assertEquals(List.of(
                "V1 is belast, omdat de afstand tot bestemming van de Vlucht (200 km) is groter dan 100 km"),
                r.uitleg());
    }

    /** A WITHHELD kenmerk: the guard did not hold, so the verhaal names the failing comparison with its read
     *  feit (50 km) — "niet geldt: …", not a bare "afwezig". */
    @Test void afwezig_kenmerk_noemt_de_falende_voorwaarde() {
        ScenarioResultaat r = één("""
            Objecttype de Vlucht (mv: Vluchten)
                de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;
                is belast kenmerk (bijvoeglijk);
            Regel belastregel
            geldig altijd
            Een Vlucht is belast indien de afstand tot bestemming van de Vlucht groter is dan 100 km.
            Situatie basis:
            een Vlucht V1
            - de afstand tot bestemming is 50 km
            Scenario niet belast
            toetst de regel belastregel
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - V1 is geen belast
            """);
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS: " + r.diagnose());
        assertEquals(List.of(
                "V1 is niet belast, want niet geldt: de afstand tot bestemming van de Vlucht (50 km)"
                        + " is groter dan 100 km"),
                r.uitleg());
    }

    /** A parameter operand is read from the globals and rendered with its unit (18 jr); an unresolvable operand
     *  (the §13.4.2.10 X-variabele, a tijdsduur the verbalisator does not recompute) is honestly "(onbekend)",
     *  never guessed. GOLDEN over the real toka-18 scenario, loaded verbatim from the manifest. */
    @Test void golden_toka18_minderjarig() {
        List<ScenarioResultaat> res = ScenarioRunner.run(bronVanManifestregel("scenario-toka-18"));
        ScenarioResultaat r = res.stream().filter(s -> s.naam().equals("jonge passagier is minderjarig"))
                .findFirst().orElseThrow(() -> new AssertionError("scenario niet gevonden"));
        assertEquals(Status.PASS, r.status(), () -> "verwacht PASS: " + r.diagnose());
        assertEquals(List.of(
                "P1 is minderjarig, omdat de tijdsduur van zijn geboortedatum tot de vluchtdatum van zijn reis"
                        + " in hele jaren (onbekend) is kleiner dan de volwassenleeftijd (18 jr)"),
                r.uitleg());
    }

    /** Concatenate the .rgs files of the golden-manifest line whose scenario file matches {@code merk}, in
     *  argument order (the runner's own CLI concatenation). Paths are repo-root-relative; the engine test runs
     *  from {@code engine/}, so they resolve under {@code ../}. */
    private static String bronVanManifestregel(String merk) {
        Path manifest = Path.of("../examples/toka-scenarios/manifest");
        try {
            String regel = Files.readAllLines(manifest).stream()
                    .filter(l -> !l.isBlank() && !l.startsWith("#") && l.contains(merk))
                    .findFirst().orElseThrow(() -> new AssertionError("geen manifestregel met '" + merk + "'"));
            String[] velden = regel.trim().split("\\s+");
            return java.util.Arrays.stream(velden, 1, velden.length)   // veld 0 = verwachte exitcode
                    .map(p -> {
                        try { return Files.readString(Path.of("..").resolve(p)); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    })
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
