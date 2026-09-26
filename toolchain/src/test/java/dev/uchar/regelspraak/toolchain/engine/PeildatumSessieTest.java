package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.ir.Geldigheid;
import dev.uchar.regelspraak.model.Parameterset;
import dev.uchar.regelspraak.model.Parameterset.WaardeInterval;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 2 van de geldigheid-parametersets-card: {@code RegelSpraakEngine.newSession(peildatum[, parametersets])} met
 * de ÉÉN-DATUMAUTORITEIT-unificatie. Bewijst op het engine-transport dat de sessie-peildatum ZOWEL de
 * parameter-vintage kiest ALS de {@code rekendatum}-global is — één datum, nooit twee parallelle noties — plus de
 * fail-fast gap/overlap-semantiek en het degenererende single-value-pad.
 *
 * <p>De feiten zijn de beans die uchars build uit het model genereert ({@link RegelSpraakEngine#nieuw}): de
 * Persoon draagt {@code grens} (= de vintage-geselecteerde parameter) en, in {@link #EEN_DATUM}, {@code prijs}
 * (= Rekenjaar).
 */
class PeildatumSessieTest {

    /** Een regel die een fact-attribuut GELIJKSTELT aan de vintage-geselecteerde parameter {@code volwassenleeftijd}. */
    static final String GRENS_UIT_PARAMETER = """
            Parameter de volwassenleeftijd : Numeriek (geheel getal) met eenheid jr
            Objecttype de Persoon (mv: Personen) (bezield)
                de grens Numeriek (geheel getal) met eenheid jr;
            Regel bepaal grens
                geldig altijd
                    De grens van een Persoon moet gesteld worden op de volwassenleeftijd.
            """;

    /** Twee vintages van één parameter: 2025 -> 18, 2026 -> 21 (aangrenzend, geen overlap). */
    private static List<Parameterset> tweeVintages() {
        var intervallen = List.of(
                new WaardeInterval(new BigDecimal(18),
                        new Geldigheid(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))),
                new WaardeInterval(new BigDecimal(21),
                        new Geldigheid(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))));
        return List.of(new Parameterset("tarieven", Map.of("volwassenleeftijd", intervallen)));
    }

    // t1 — VINTAGE: één gecompileerde engine, zelfde feiten, twee peildatums -> twee VERSCHILLENDE gepinde uitkomsten.
    @Test void t1_vintage_selectie_op_peildatum() {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(GRENS_UIT_PARAMETER);
        assertTrue(engine.overgeslagen().isEmpty(), () -> "regel moet volledig loweren: " + engine.overgeslagen());
        List<Parameterset> sets = tweeVintages();

        Object p2025 = engine.nieuw("Persoon");
        try (RegelSpraakEngine.Session s = engine.newSession(LocalDate.of(2025, 6, 1), sets)) {
            s.insert(p2025).fire();
        }
        assertEquals(new BigDecimal(18), RegelSpraakRuntime.get(p2025, "grens"), "2025-06-01 kiest de 2025-vintage (18)");

        Object p2026 = engine.nieuw("Persoon");
        try (RegelSpraakEngine.Session s = engine.newSession(LocalDate.of(2026, 6, 1), sets)) {
            s.insert(p2026).fire();
        }
        assertEquals(new BigDecimal(21), RegelSpraakRuntime.get(p2026, "grens"), "2026-06-01 kiest de 2026-vintage (21)");
    }

    // t2 — GAP: peildatum buiten elk interval -> fout die parameternaam EN peildatum noemt.
    @Test void t2_gap_noemt_parameter_en_peildatum() {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(GRENS_UIT_PARAMETER);
        LocalDate buiten = LocalDate.of(2024, 6, 1);   // vóór elk interval
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.newSession(buiten, tweeVintages()));
        assertTrue(ex.getMessage().contains("volwassenleeftijd"), () -> "moet de parameter noemen: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("2024-06-01"), () -> "moet de peildatum noemen: " + ex.getMessage());
    }

    // t3 — OVERLAP defensief: hand-gebouwde overlappende sets -> fout die de parameter noemt.
    @Test void t3_overlap_defensief_noemt_parameter() {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(GRENS_UIT_PARAMETER);
        var overlappend = List.of(new Parameterset("bug", Map.of("volwassenleeftijd", List.of(
                new WaardeInterval(new BigDecimal(18),
                        new Geldigheid(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))),
                new WaardeInterval(new BigDecimal(21),
                        new Geldigheid(LocalDate.of(2025, 6, 1), LocalDate.of(2026, 12, 31)))))));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.newSession(LocalDate.of(2025, 6, 1), overlappend));
        assertTrue(ex.getMessage().contains("volwassenleeftijd"),
                () -> "overlap-fout moet de parameter noemen: " + ex.getMessage());
    }

    /** Twee regels op één bron: {@code grens} = de vintage-parameter, {@code prijs} = Rekenjaar (de rekendatum-global).
     *  Zo dragen de UITKOMST van een Rekendatum-lezende regel en de parameter-vintage aantoonbaar dezelfde datum. */
    static final String EEN_DATUM = """
            Parameter de volwassenleeftijd : Numeriek (geheel getal) met eenheid jr
            Objecttype de Persoon (mv: Personen) (bezield)
                de grens Numeriek (geheel getal) met eenheid jr;
                de prijs Numeriek (geheel getal);
            Regel bepaal grens
                geldig altijd
                    De grens van een Persoon moet gesteld worden op de volwassenleeftijd.
            Regel bepaal prijs
                geldig altijd
                    De prijs van een Persoon moet gesteld worden op Rekenjaar.
            """;

    // t4 — ONE DATE AUTHORITY: één newSession(peildatum, sets) drijft ZOWEL de Rekenjaar-regel als de vintage.
    @Test void t4_een_datum_autoriteit() {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(EEN_DATUM);
        assertTrue(engine.overgeslagen().isEmpty(),
                () -> "beide regels (parameter + Rekenjaar) moeten loweren: " + engine.overgeslagen());

        Object p = engine.nieuw("Persoon");
        LocalDate peildatum = LocalDate.of(2026, 6, 1);
        try (RegelSpraakEngine.Session s = engine.newSession(peildatum, tweeVintages())) {
            s.insert(p).fire();
        }
        // de regel die Rekendatum leest ziet de peildatum (Rekenjaar 2026)...
        assertEquals(new BigDecimal(2026), RegelSpraakRuntime.get(p, "prijs"), "prijs = Rekenjaar van de peildatum");
        // ...en de parameter draagt de vintage van EXACT diezelfde datum (2026 -> 21) — geen tweede datumnotie.
        assertEquals(new BigDecimal(21), RegelSpraakRuntime.get(p, "grens"), "grens = de 2026-vintage van de peildatum");
    }

    // t5 — DEGENERAAT: newSession(peildatum) zonder sets zet alleen rekendatum; en het bestaande setGlobal-pad blijft.
    @Test void t5_degeneraat_alleen_datum_en_setglobal_pad() {
        // newSession(peildatum) zonder sets: zet alleen de rekendatum, tolerant voor een model zonder die global.
        RegelSpraakEngine grensEngine = RegelSpraakEngine.compile(GRENS_UIT_PARAMETER);
        // zonder parameter-global set fires niets voor grens (parameter niet gezet) -> grens blijft leeg; geen fout.
        Object leeg = grensEngine.nieuw("Persoon");
        try (RegelSpraakEngine.Session s = grensEngine.newSession(LocalDate.of(2025, 6, 1))) {
            s.insert(leeg).fire();
        }
        assertNull(RegelSpraakRuntime.get(leeg, "grens"),
                "zonder parameterset blijft grens ongezet (geen throw op de rekendatum-global)");

        // het bestaande newSession() + setGlobal-pad blijft werken (regression-pin).
        RegelSpraakEngine engine = RegelSpraakEngine.compile(RegelSpraakEngineTest.MINDERJARIG);
        Object minor = RegelSpraakEngineTest.persoon(engine, 16);
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            s.insert(minor).setGlobal("volwassenleeftijd", new BigDecimal(18)).fire();
        }
        assertEquals(Boolean.TRUE, RegelSpraakRuntime.get(minor, "minderjarig"),
                "16 < 18 -> minderjarig via het onveranderde setGlobal-pad");
    }

    // peildatum == null op het parameterset-pad is een IllegalArgumentException (de datumautoriteit is verplicht).
    @Test void peildatum_null_is_verboden_op_het_parameterset_pad() {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(GRENS_UIT_PARAMETER);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.newSession(null, tweeVintages()));
        assertTrue(ex.getMessage().contains("peildatum"), () -> "moet de peildatum-eis noemen: " + ex.getMessage());
    }
}
