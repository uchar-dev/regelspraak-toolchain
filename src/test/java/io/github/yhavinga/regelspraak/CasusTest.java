package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end on the real TOKA casus: the whole document resolves to a faithful
 * model AND raises no ERROR diagnostics — the resolver makes no false claim on the
 * constructs it cannot fully resolve. (It does raise WARNINGs for resultaatdeel
 * kinds not yet lifted into the IR — feitcreatie, startpuntbepaling,
 * dagsoortresultaat — so those rules are visible as gaps rather than silently
 * dropped.) Reads the committed fixtures under examples/toka.
 */
class CasusTest {

    @Test void volledige_toka_casus_resolveert_zonder_fouten() throws IOException {
        Path dir = Path.of("examples/toka");
        assumeTrue(Files.isDirectory(dir), "TOKA fixtures absent (run tools/extract_corpus.py)");

        String bron;
        try (Stream<Path> files = Files.list(dir)) {
            bron = files.filter(p -> p.toString().endsWith(".rgs")).sorted()
                    .map(CasusTest::read)
                    .collect(Collectors.joining("\n"));
        }

        RegelSpraakModel m = Resolver.resolve(bron);
        // §11 "Daarbij geldt:" variabelen now inline into the rule, so the casus' variable DEFINITIONS get
        // type-checked (they were invisible while the references markered). This surfaces TWO genuine
        // casus-internal inconsistencies — a reason-carrying, fails-when-stale list mirroring uchar's gate-11
        // holdout list (decision 16Y, 2026-08-29): each must still error, and NO other error may appear.
        //   toka-23: "A is het uiterste boekingstijdstip … plus bevestigingsinterval" adds a Datum-tijd
        //            PARAMETER (a "Datum en tijd in millisecondes" used as a duration) to a Datum-tijd —
        //            typeringen §4.1 Tabel 2 forbids it.
        //   toka-45: "de belasting op basis van afstand" computes a unitless Bedrag MIN a km-typed product.
        //            The eenheid-identiteit typing (vendored frontend, decision 16Y) types the product
        //            (unitless × km = km), so the min fails the §4 unit rule — the casus is dimensionally
        //            inconsistent as written; the old Onbekend degradation masked it.
        var datumQuirk = (java.util.function.Predicate<String>) msg -> msg.contains("kreeg Datum-tijd en Datum-tijd");
        var afstandQuirk = (java.util.function.Predicate<String>) msg ->
                msg.contains("min:") && msg.contains("moeten beide er een hebben");
        var onverwacht = m.errors().stream()
                .filter(d -> !datumQuirk.test(d.message()) && !afstandQuirk.test(d.message()))
                .toList();
        assertTrue(onverwacht.isEmpty(), () -> "false positives op de TOKA casus: " + onverwacht);
        assertTrue(m.errors().stream().anyMatch(d -> datumQuirk.test(d.message())),
                () -> "verwachtte de bekende §4.1 datum+datum casus-quirk (toka-23 bevestigingsinterval): " + m.errors());
        assertTrue(m.errors().stream().anyMatch(d -> afstandQuirk.test(d.message())),
                () -> "verwachtte de dimensioneel-inconsistente afstand-quirk (toka-45, besluit 16Y): " + m.errors());

        // the casus' GegevensSpraak, faithfully modelled
        assertEquals(3, m.objecttypen.size(), "Natuurlijk persoon, Vlucht, Contingent treinmiles");
        assertEquals(4, m.feittypen.size());
        assertTrue(m.parameters.size() >= 15, () -> "parameters: " + m.parameters.size());
        assertTrue(m.objecttypen.get("Natuurlijk persoon").attributen.containsKey("leeftijd"));
        assertTrue(m.regels.size() >= 20, () -> "regels: " + m.regels.size());

        // §2.7 the named timeline with a variable start point
        assertEquals(1, m.tijdlijnen.size());
        assertTrue(m.tijdlijnen.get(0).variabelStartpunt());

        // §3.11 the consistency rule ("Controleer of vlucht geen rondvlucht is") now
        // lifts into IR rather than being warned-and-dropped.
        assertFalse(m.consistenties.isEmpty(), "de rondvlucht-consistentieregel moet IR opleveren");
        assertTrue(m.consistenties.stream().anyMatch(c -> c.subject() != null && c.assertie() != null),
                () -> "consistentie zonder subject/assertie: " + m.consistenties);
    }

    private static String read(Path p) {
        try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
