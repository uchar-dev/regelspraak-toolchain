package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.Geldigheid;
import io.github.yhavinga.regelspraak.model.Parameterset;
import io.github.yhavinga.regelspraak.model.Parameterset.WaardeInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Puur-Java unittests voor de vintage-selectie / fail-fast semantiek van {@link Parameterset}: overlap-detectie,
 * peildatum-selectie met gap-fout, en de meer-vintage-vraag. Geen Jackson, geen model — alleen de kern.
 */
class ParametersetTest {

    private static Geldigheid g(String vanaf, String tot) {
        return new Geldigheid(vanaf == null ? null : LocalDate.parse(vanaf),
                              tot == null ? null : LocalDate.parse(tot));
    }

    private static Parameterset set(String naam, String param, WaardeInterval... intervallen) {
        return new Parameterset(naam, Map.of(param, List.of(intervallen)));
    }

    // ---- overlap ----

    @Test void begrensd_begrensd_die_een_dag_delen_is_overlap() {
        var sets = List.of(set("s", "tarief",
                new WaardeInterval(new BigDecimal(1), g("2025-01-01", "2025-12-31")),
                new WaardeInterval(new BigDecimal(2), g("2025-12-31", "2026-06-30"))));
        List<String> fouten = Parameterset.overlapFouten(sets);
        assertEquals(1, fouten.size(), fouten::toString);
        assertTrue(fouten.get(0).contains("tarief"), fouten::toString);
    }

    @Test void aangrenzend_niet_overlappend_is_ok() {
        var sets = List.of(set("s", "tarief",
                new WaardeInterval(new BigDecimal(1), g("2025-01-01", "2025-12-31")),
                new WaardeInterval(new BigDecimal(2), g("2026-01-01", "2026-12-31"))));
        assertTrue(Parameterset.overlapFouten(sets).isEmpty());
    }

    @Test void open_einde_versus_begrensd_overlapt() {
        var sets = List.of(set("s", "tarief",
                new WaardeInterval(new BigDecimal(1), g(null, "2025-12-31")),
                new WaardeInterval(new BigDecimal(2), g("2025-06-01", "2026-06-30"))));
        assertEquals(1, Parameterset.overlapFouten(sets).size());
    }

    @Test void overlap_over_twee_sets_heen() {
        var a = set("2025", "tarief", new WaardeInterval(new BigDecimal(1), g("2025-01-01", "2025-12-31")));
        var b = set("2025b", "tarief", new WaardeInterval(new BigDecimal(2), g("2025-06-01", "2026-06-30")));
        List<String> fouten = Parameterset.overlapFouten(List.of(a, b));
        assertEquals(1, fouten.size(), fouten::toString);
        assertTrue(fouten.get(0).contains("tarief"));
    }

    // ---- waardenOp ----

    @Test void waardenOp_happy_kiest_het_bevattende_interval() {
        var sets = List.of(set("s", "tarief",
                new WaardeInterval(new BigDecimal(1), g("2025-01-01", "2025-12-31")),
                new WaardeInterval(new BigDecimal(2), g("2026-01-01", "2026-12-31"))));
        List<String> fouten = new ArrayList<>();
        Map<String, Object> uit = Parameterset.waardenOp(sets, LocalDate.parse("2026-03-01"), fouten);
        assertTrue(fouten.isEmpty(), fouten::toString);
        assertEquals(new BigDecimal(2), uit.get("tarief"));
    }

    @Test void waardenOp_open_bounds_bevat_alles() {
        var sets = List.of(set("s", "tarief",
                new WaardeInterval(new BigDecimal(7), Geldigheid.ALTIJD)));
        List<String> fouten = new ArrayList<>();
        Map<String, Object> uit = Parameterset.waardenOp(sets, LocalDate.parse("1999-01-01"), fouten);
        assertTrue(fouten.isEmpty());
        assertEquals(new BigDecimal(7), uit.get("tarief"));
    }

    @Test void waardenOp_gap_noemt_parameter_en_datum() {
        var sets = List.of(set("s", "tarief",
                new WaardeInterval(new BigDecimal(1), g("2025-01-01", "2025-12-31"))));
        List<String> fouten = new ArrayList<>();
        Map<String, Object> uit = Parameterset.waardenOp(sets, LocalDate.parse("2027-05-05"), fouten);
        assertTrue(uit.isEmpty());
        assertEquals(1, fouten.size());
        assertTrue(fouten.get(0).contains("tarief"), fouten::toString);
        assertTrue(fouten.get(0).contains("2027-05-05"), fouten::toString);
    }

    // ---- meerVintage ----

    @Test void meerVintage_single_altijd_is_false() {
        var sets = List.of(set("s", "tarief", new WaardeInterval(new BigDecimal(1), Geldigheid.ALTIJD)));
        assertFalse(Parameterset.meerVintage(sets));
    }

    @Test void meerVintage_twee_intervallen_is_true() {
        var sets = List.of(set("s", "tarief",
                new WaardeInterval(new BigDecimal(1), g("2025-01-01", "2025-12-31")),
                new WaardeInterval(new BigDecimal(2), g("2026-01-01", "2026-12-31"))));
        assertTrue(Parameterset.meerVintage(sets));
    }

    @Test void meerVintage_een_begrensd_interval_is_true() {
        var sets = List.of(set("s", "tarief",
                new WaardeInterval(new BigDecimal(1), g("2025-01-01", "2025-12-31"))));
        assertTrue(Parameterset.meerVintage(sets));
    }
}
