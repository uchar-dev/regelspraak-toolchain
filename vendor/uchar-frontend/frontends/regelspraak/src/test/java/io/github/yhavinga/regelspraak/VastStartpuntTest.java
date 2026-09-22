package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixed start point of a timeline (§13.3.6.3–.7) at the wire boundary.
 *
 * <p><b>Red first, measured 2026-09-22 on the commit before this card.</b> Every refused source below
 * emitted with exit 0 and {@code diagnostics: []}; the start point left no trace on the wire, so
 * "voor elk jaar startend op dag 15 van maand 10" emitted byte-identically to "voor elk jaar".
 *
 * <p><b>Why a refusal and not a field.</b> The v0 wire has no field for a fixed start point: a
 * {@code Tijdlijn} carries name, {@code variabelStartpunt}, granularity and count; the attribute,
 * characteristic and parameter side-maps carry granularity and count; {@code ResolvedStartpunt} is
 * the VARIABLE-start anchor (IR-SPEC ch. 3.8) and holds no date. Both executors read an anchorless
 * timeline on the calendar grid (RUNTIME-SEMANTICS ch. 3.2), so a start point off that grid changes
 * the meaning: the period the author declared is refused as off-grid input by the reference
 * interpreter, and the calendar period it did not declare is accepted.
 *
 * <p><b>Why the model still resolves.</b> The model layer records granularity and count correctly;
 * only the wire cannot carry the start point. The refusal therefore sits at the emission boundary —
 * {@code Resolver --ir} and {@link IrEmitter#emit} — and {@link Resolver#resolve} stays error-free.
 *
 * <p><b>The lossless omission.</b> Day 1 of the month and day 1 of month 1 coincide with the month
 * and year buckets the executors use; day 1 of months 1, 4, 7 and 10 is the calendar quarter, which
 * the anchorless quarter timeline already means (no quarter bucket exists to disagree with). Leaving
 * these three off is not a loss. That omission is pinned byte for byte below.
 */
class VastStartpuntTest {

    private static final String KOP = """
            Domein Bedrag is van het type Numeriek (geheel getal)
            Objecttype de Vlucht (mv: Vluchten)
            """;

    private static String attribuut(String tijdlijn) {
        return KOP + "    de waarde Bedrag " + tijdlijn + ";\n";
    }

    private record Uitkomst(int rc, boolean geschreven, String stdout) {}

    /** The real CLI path: resolve + emit into a temp file, stdout captured. */
    private static Uitkomst irModus(String bron) throws Exception {
        Path dir = Files.createTempDirectory("vaststartpunt");
        Path rgs = dir.resolve("bron.rgs");
        Path out = dir.resolve("ir.json");
        Files.writeString(rgs, bron, StandardCharsets.UTF_8);
        PrintStream oud = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int rc;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            rc = Resolver.irModus(new java.util.ArrayList<>(List.of(out.toString(), rgs.toString())));
        } finally {
            System.setOut(oud);
        }
        return new Uitkomst(rc, Files.exists(out), buf.toString(StandardCharsets.UTF_8));
    }

    /** The source must be refused by name on BOTH emission routes, and nowhere earlier. */
    private static void weigert(String bron, String sectie, String fragment) throws Exception {
        RegelSpraakModel m = Resolver.resolve(bron);
        assertTrue(m.isOk(), () -> "het model zelf lost op; alleen de wire weigert: " + m.errors());

        Uitkomst u = irModus(bron);
        assertEquals(1, u.rc(), () -> "--ir moet weigeren (exit 1), kreeg " + u.rc() + ":\n" + u.stdout());
        assertFalse(u.geschreven(), "een geweigerd document wordt niet geschreven");
        assertTrue(u.stdout().contains("ERROR") && u.stdout().contains(sectie) && u.stdout().contains(fragment),
                () -> "de weigering noemt sectie " + sectie + " en '" + fragment + "':\n" + u.stdout());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> IrEmitter.emit(m),
                "IrEmitter.emit mag het startpunt niet stil laten vallen");
        assertTrue(e.getMessage().contains(sectie) && e.getMessage().contains(fragment),
                () -> "de emitterweigering noemt de zin: " + e.getMessage());
    }

    // ---------------- the date form, §13.3.6.3 ----------------

    @Test void week_startend_op_een_datum_wordt_geweigerd() throws Exception {
        weigert(attribuut("voor elke week startend op 5-1-2026"), "§13.3.6.3", "startend op 5-1-2026");
    }

    @Test void week_startend_op_een_datum_met_tijdcomponent_wordt_geweigerd() throws Exception {
        weigert(attribuut("voor elke week startend op 5-1-2026 12:00:00.000"), "§13.3.6.3",
                "startend op 5-1-2026 12:00:00.000");
    }

    @Test void meervoudige_periode_startend_op_een_datum_wordt_geweigerd() throws Exception {
        // §13.3.6.7 makes the date mandatory for a multi-period grid; the wire keeps the count (5) and
        // drops the date the grid is laid from.
        weigert(attribuut("voor elke 5 maanden startend op 1-4-2026"), "§13.3.6.3", "startend op 1-4-2026");
    }

    // ---------------- the day-of forms off the calendar, §13.3.6.4–.6 ----------------

    @Test void maand_startend_op_een_andere_dag_dan_1_wordt_geweigerd() throws Exception {
        weigert(attribuut("voor elke maand startend op dag 15 van de maand"), "§13.3.6.4",
                "startend op dag 15 van de maand");
    }

    @Test void kwartaal_buiten_de_kalenderkwartalen_wordt_geweigerd() throws Exception {
        weigert(attribuut("voor elk kwartaal startend op dag 1 van maand 2, 5, 8 en 11"), "§13.3.6.5",
                "startend op dag 1 van maand 2, 5, 8 en 11");
    }

    @Test void kwartaalgroep_die_pas_op_een_later_element_afwijkt_wordt_geweigerd() throws Exception {
        // not a valid source-language group, but the grammar admits it; the whole group is compared,
        // so a deviation after the first element is caught too
        weigert(attribuut("voor elk kwartaal startend op dag 1 van maand 1, 5, 8 en 11"), "§13.3.6.5",
                "startend op dag 1 van maand 1, 5, 8 en 11");
    }

    @Test void kwartaal_op_een_andere_dag_dan_1_wordt_geweigerd() throws Exception {
        weigert(attribuut("voor elk kwartaal startend op dag 10 van maand 1, 4, 7 en 10"), "§13.3.6.5",
                "startend op dag 10 van maand 1, 4, 7 en 10");
    }

    @Test void jaar_startend_buiten_1_januari_wordt_geweigerd() throws Exception {
        weigert(attribuut("voor elk jaar startend op dag 15 van maand 10"), "§13.3.6.6",
                "startend op dag 15 van maand 10");
        // the DAY dimension on its own: month 1 is the calendar month, only the day deviates
        weigert(attribuut("voor elk jaar startend op dag 15 van maand 1"), "§13.3.6.6",
                "startend op dag 15 van maand 1");
    }

    // ---------------- the other attachment positions ----------------

    @Test void kenmerk_met_een_vast_startpunt_wordt_geweigerd() throws Exception {
        weigert(KOP + "    is duurzaam kenmerk (bijvoeglijk) voor elke week startend op 5-1-2026;\n",
                "§13.3.6.3", "startend op 5-1-2026");
    }

    @Test void parameter_met_een_vast_startpunt_wordt_geweigerd() throws Exception {
        weigert(KOP + "Parameter het weektarief : Numeriek (geheel getal) voor elke week startend op 5-1-2026\n",
                "§13.3.6.3", "startend op 5-1-2026");
    }

    @Test void benoemde_tijdlijn_met_een_vast_startpunt_wordt_geweigerd() throws Exception {
        weigert("Tijdlijn de schoft heeft tijdvakken van een week startend op 5-1-2026;\n" + KOP
                + "    de waarde Bedrag voor elke schoft;\n", "§13.3.6.3", "startend op 5-1-2026");
        weigert("Tijdlijn het boekjaar heeft tijdvakken van een jaar startend op dag 1 van maand 7;\n" + KOP
                + "    de waarde Bedrag voor elke boekjaar;\n", "§13.3.6.6", "startend op dag 1 van maand 7");
        weigert("Tijdlijn het blok heeft tijdvakken van 5 maanden startend op 1-4-2026;\n" + KOP
                + "    de waarde Bedrag voor elke blok;\n", "§13.3.6.3", "startend op 1-4-2026");
    }

    // ---------------- the lossless omission: the calendar start point ----------------

    /** Emitted through the real CLI path; the bytes of the document it wrote. */
    private static String emissie(String bron) throws Exception {
        Path dir = Files.createTempDirectory("vaststartpunt");
        Path rgs = dir.resolve("bron.rgs");
        Path out = dir.resolve("ir.json");
        Files.writeString(rgs, bron, StandardCharsets.UTF_8);
        PrintStream oud = System.out;
        int rc;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            rc = Resolver.irModus(new java.util.ArrayList<>(List.of(out.toString(), rgs.toString())));
        } finally {
            System.setOut(oud);
        }
        assertEquals(0, rc, "het kalenderstartpunt emitteert");
        return Files.readString(out, StandardCharsets.UTF_8)
                // the Bron coordinates carry the file name, which differs per temp dir
                .replace(rgs.toString(), "bron.rgs");
    }

    @Test void het_kalenderstartpunt_valt_bewust_en_verliesvrij_weg() throws Exception {
        assertEquals(emissie(attribuut("voor elke maand")),
                emissie(attribuut("voor elke maand startend op dag 1 van de maand")));
        assertEquals(emissie(attribuut("voor elk kwartaal")),
                emissie(attribuut("voor elk kwartaal startend op dag 1 van maand 1, 4, 7 en 10")));
        assertEquals(emissie(attribuut("voor elk jaar")),
                emissie(attribuut("voor elk jaar startend op dag 1 van maand 1")));
    }
}
