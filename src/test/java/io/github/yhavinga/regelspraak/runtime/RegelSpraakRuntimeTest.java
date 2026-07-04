package io.github.yhavinga.regelspraak.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit pins on the runtime — since single-runtime principle the ONE implementation of every rs* helper (the DRL back-end
 * imports these via {@code import function}; the exec back-end calls them directly). These pins cover the
 * fail-fast contracts that no firing test reaches (the transpilers only ever emit known units).
 */
class RegelSpraakRuntimeTest {

    /** A minimal Segment-shaped bean (the facts POJO lives in src/it, out of this suite's reach). */
    public static class Seg {
        private final LocalDate van, tot; private final BigDecimal waarde;
        public Seg(LocalDate van, LocalDate tot, BigDecimal waarde) { this.van = van; this.tot = tot; this.waarde = waarde; }
        public LocalDate getVan() { return van; }
        public LocalDate getTot() { return tot; }
        public BigDecimal getWaarde() { return waarde; }
    }

    @Test void rsTijdTotaal_faalt_hard_op_een_onbekende_granulariteit() {
        // an unknown granularity used to weight the segment by a silent 0 —
        // a wrong total with no trace. Now it fails fast, in the single shared implementation.
        Object seg = RegelSpraakRuntime.newSegment(Seg.class,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 1), new BigDecimal("10"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RegelSpraakRuntime.rsTijdTotaal(List.of(seg), "FORTNIGHT"));
        assertTrue(e.getMessage().contains("FORTNIGHT"), () -> "de fout moet de eenheid noemen: " + e.getMessage());
        // the five known granularities keep totalling (MAAND over 2 whole months = 20)
        assertEquals(0, new BigDecimal("20").compareTo(RegelSpraakRuntime.rsTijdTotaal(List.of(seg), "MAAND")));
    }

    @Test void rsDatumReken_faalt_hard_op_een_onbekende_eenheid() {
        // ChronoUnit.valueOf throws on anything the transpilers did not map (KWARTAAL is the one pseudo-unit).
        assertThrows(IllegalArgumentException.class,
                () -> RegelSpraakRuntime.rsDatumReken(LocalDate.of(2024, 1, 1), BigDecimal.ONE, "FORTNIGHT", true));
        assertEquals(LocalDate.of(2024, 4, 1),
                RegelSpraakRuntime.rsDatumReken(LocalDate.of(2024, 1, 1), BigDecimal.ONE, "KWARTAAL", true),
                "de pseudo-eenheid KWARTAAL blijft 3 maanden");
    }

    @Test void rsRelGetal_leest_null_veilig_door_een_afwezige_relatie() {
        assertNull(RegelSpraakRuntime.rsRelGetal(null, "waarde"), "een afwezige relatie leest leeg, geen NPE");
    }

    @Test void rsHerschaal_en_rsHerschaalExact_herschalen_leeg_naar_leeg() {
        assertNull(RegelSpraakRuntime.rsHerschaal(null, new BigDecimal("12")));
        assertNull(RegelSpraakRuntime.rsHerschaalExact(null, new BigDecimal("12"), BigDecimal.ONE));
        assertEquals(0, new BigDecimal("2").compareTo(
                (BigDecimal) RegelSpraakRuntime.rsHerschaalExact(new BigDecimal("24"), BigDecimal.ONE, new BigDecimal("12"))),
                "24 mnd ->  2 jr via de EXACTE ×fv ÷fn (nooit de afgeknotte 1/12-ratio)");
    }
}
