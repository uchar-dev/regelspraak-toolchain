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

    /** The constructs the back-end honestly does NOT lower today, keyed by manifest sleutel. The former
     *  ten-marker tail (toka-22, toka-41/45, all six verdelingen) was traced to CASUS OMISSIONS — the
     *  rules reference members the object model never declares ('de treinmiles', the toka-35/37 cap,
     *  kenmerk 'passagier jonger dan 18 jaar', 'daadwerkelijke datum-tijd van vertrek'); with the
     *  examples/toka-reconciliatie extensie supplying exactly those members (see grammar-divergences),
     *  all nine lower and fire. The one honest residual is toka-23 — NOT a missing feature: §11 "Daarbij
     *  geldt" variables ARE inlined (declaration-order, DrlTest-pinned), which is exactly what surfaces
     *  its A-operand's casus-own datum-tijd PLUS datum-tijd type quirk (§4.1 Tabel 2 forbids it — the
     *  single expected CasusTest error); the errored operand degrades to a marker Frag and the LAATSTE
     *  fold honestly declines. Lowering it would require contradicting the typeringen. */
    private static final Map<String, String> VERWACHT_MARKER = Map.of(
        "regel: Bevestigingstijdstip vlucht", "TODO aggregatie LAATSTE");

    @Test void toka_scorebord_28_van_29_lowered_en_geen_stille_markers() throws IOException {
        Path dir = Path.of("examples/toka");
        assumeTrue(Files.isDirectory(dir), "TOKA fixtures absent (run tools/extract_corpus.py)");
        RegelSpraakModel m = Resolver.resolve(RECONCILIATIE + lees(dir));
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
        assertEquals(28, lowered, () -> "TOKA-scorebord verschoven: " + lowered + "/29 functioneel");

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

    /** The datum-plus-afstand probe (T4). Formerly: resolved clean, honest MARKER + dekking-WARNING was
     *  the only signal. Now Tabel 2/5 scenario 2 is enforced at resolve time: `datum plus <afstand in km>`
     *  is a resolver ERROR — the author gets a diagnostic, not a dead rule. */
    @Test void datum_plus_afstand_is_een_resolver_fout() {
        RegelSpraakModel m = Resolver.resolve("""
                Objecttype het Ding
                    de datum Datum in dagen;
                    de afstand Numeriek (geheel getal) met eenheid km;
                    de uitkomst Datum in dagen;

                Regel bepaal uitkomst
                    geldig altijd
                        De uitkomst van een Ding moet berekend worden als de datum van het Ding plus de afstand van het Ding.
                """);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("tijdseenheid")),
                () -> "datum plus km moet een tijdseenheid-ERROR geven (Tabel 2/5 scenario 2), kreeg: " + m.errors());
    }

    /** The examples/toka-reconciliatie supplement: the four members the casus's own rules reference but
     *  its object model never declares (see that directory's README + grammar-divergences). */
    static final String RECONCILIATIE;
    static {
        try { RECONCILIATIE = Files.readString(Path.of("examples/toka-reconciliatie/casus-omissies.rs")) + "\n"; }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static String lees(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".rs")).sorted()
                    .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                    .collect(Collectors.joining("\n"));
        }
    }
}
