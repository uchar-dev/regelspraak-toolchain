package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.kern.Dekking;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the TOKA lowering scoreboard: the whole 47-file casus transpiled in one model, with the
 * per-construct {@link Dekking} verdict asserted EXACTLY. This is the net under every later
 * refactor milestone — a construct that silently regresses from LOWERED to MARKER (or silently
 * starts lowering, invalidating documented divergences) trips this test by name, not by count.
 * It also pins the no-silent-inertness discipline on the casus: every non-LOWERED verdict has a WARNING, so
 * accepted-input-with-inert-output is never diagnostics-silent.
 */
class TokaDekkingTest {

    /** The constructs the back-end honestly does NOT lower today, keyed by manifest sleutel.
     *  Measured at the coverage-manifest baseline (post-f5cdde7): 19 of 29 constructs functional; markered are
     *  toka-22/23 (eerste/laatste over genavigeerde datum-tijd-operanden), toka-41/45 (the two
     *  double-nested compound voorwaarden) and ALL SIX verdelingen toka-32…37 — including
     *  toka-34 (op volgorde) and toka-36 (naar rato met afronding), which corrects the former
     *  docs/grammar-divergences.md claim that those two lower on the corpus. */
    private static final Map<String, String> VERWACHT_MARKER = Map.of(
        "regel: Datum-tijd voor het berekenen van de belasting op basis van afstand", "TODO aggregatie EERSTE",
        "regel: Bevestigingstijdstip vlucht", "TODO aggregatie LAATSTE",
        "regel: Recht op Duurzaamheidskorting alternatief", "TODO voorwaarde",
        "regel: belasting op basis van afstand", "TODO voorwaarde",
        "verdeling: verdeling treinmiles in gelijke delen", "onopgelost verdeling (gelijke_delen)",
        "verdeling: verdeling treinmiles op basis van woonregio factor", "onopgelost verdeling (naar_rato)",
        "verdeling: Verdeling treinmiles op basis van leeftijd en woonregio factor", "onopgelost verdeling (op_volgorde)",
        "verdeling: verdeling treinmiles op basis van woonregio factor en met maximum waarde", "onopgelost verdeling (complex)",
        "verdeling: verdeling treinmiles op basis van woonregio factor met afronding", "onopgelost verdeling (naar_rato)",
        "verdeling: Verdeling treinmiles op basis van leeftijd, woonregio factor, met maximum waarde en afronding", "onopgelost verdeling (complex)");

    @Test void toka_scorebord_19_van_29_lowered_en_geen_stille_markers() throws IOException {
        Path dir = Path.of("examples/toka");
        assumeTrue(Files.isDirectory(dir), "TOKA fixtures absent (run tools/extract_corpus.py)");
        RegelSpraakModel m = Resolver.resolve(lees(dir));
        DrlTranspiler t = DrlTranspiler.build(m);

        assertEquals(29, t.dekking().size(), () -> "TOKA-constructaantal verschoven: " + t.dekking());
        for (Dekking d : t.dekking()) {
            String verwachtePrefix = VERWACHT_MARKER.get(d.sleutel());
            if (verwachtePrefix == null) {
                assertEquals(Dekking.Verdict.LOWERED, d.verdict(),
                        () -> d.sleutel() + " regresseerde van LOWERED naar " + d.verdict() + " — " + d.reden());
            } else {
                assertEquals(Dekking.Verdict.MARKER, d.verdict(),
                        () -> d.sleutel() + " is niet langer MARKER (" + d.verdict() + ") — als dit een bewuste "
                            + "lowering is: verwijder de verwachting hier én werk docs/grammar-divergences.md bij");
                assertTrue(d.reden().startsWith(verwachtePrefix),
                        () -> d.sleutel() + " markert om een ANDERE reden: " + d.reden());
            }
        }
        long lowered = t.dekking().stream().filter(Dekking::lowered).count();
        assertEquals(19, lowered, () -> "TOKA-scorebord verschoven: " + lowered + "/29 functioneel");

        // geen stille inertie — elke MARKER heeft een dekking-WARNING op het model
        for (Dekking d : t.dekking()) {
            if (d.lowered()) continue;
            assertTrue(m.diagnostics.stream().anyMatch(w ->
                            w.severity() == RegelSpraakModel.Severity.WARNING
                            && w.message().startsWith("dekking:") && w.message().contains("'" + d.naam() + "'")),
                    () -> "MARKER zonder WARNING (stille inertie): " + d.sleutel());
        }

        // toka-43/44 (startpuntbepaling) halen de IR niet en staan dus buiten het manifest;
        // hun zichtbaarheid is de bestaande resolver-WARNING — pin die hier mee.
        assertEquals(2, m.diagnostics.stream().filter(d ->
                        d.message().contains("'startpuntbepaling' wordt nog niet")).count(),
                "de twee startpuntbepalingen (toka-43/44) moeten als WARNING zichtbaar blijven");
    }

    /** The datum-plus-afstand probe: an accepted `datum plus <afstand in km>` lowers to an honest
     *  marker, but used to do so with ZERO diagnostics and exit 0. Now: MARKER verdict + WARNING
     *  (the CLI turns this into exit 3 without --allow-markers). */
    @Test void datum_plus_afstand_markert_niet_stil() {
        RegelSpraakModel m = Resolver.resolve("""
                Objecttype het Ding
                    de datum Datum in dagen;
                    de afstand Numeriek (geheel getal) met eenheid km;
                    de uitkomst Datum in dagen;

                Regel bepaal uitkomst
                    geldig altijd
                        De uitkomst van een Ding moet berekend worden als de datum van het Ding plus de afstand van het Ding.
                """);
        assertTrue(m.errors().isEmpty(), () -> "T4-probe hoort te resolven (resolver-gat #6): " + m.errors());
        DrlTranspiler t = DrlTranspiler.build(m);
        assertEquals(1, t.dekking().size());
        Dekking d = t.dekking().get(0);
        assertEquals(Dekking.Verdict.MARKER, d.verdict(),
                () -> "datum+km hoort een eerlijke marker te blijven: " + d);
        assertTrue(m.diagnostics.stream().anyMatch(w -> w.severity() == RegelSpraakModel.Severity.WARNING
                        && w.message().startsWith("dekking: regel 'bepaal uitkomst' MARKER")),
                () -> "de marker moet een WARNING dragen, kreeg: " + m.diagnostics);
    }

    private static String lees(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".rs")).sorted()
                    .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                    .collect(Collectors.joining("\n"));
        }
    }
}
