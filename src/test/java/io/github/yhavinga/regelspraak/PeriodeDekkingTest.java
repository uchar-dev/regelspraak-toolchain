package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.Geldigheid;
import io.github.yhavinga.regelspraak.model.Parameterset;
import io.github.yhavinga.regelspraak.model.Parameterset.WaardeInterval;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.kern.PeriodeDekking;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unittests voor {@link PeriodeDekking}: de compile-time consistentiecheck regel-geldigheid × parameter-geldigheid
 * (card fase 3, gate G3). Model via {@link Resolver#resolve} over een klein inline .rgs-bronnetje; dekking via
 * puur-Java {@link Parameterset}en (geen Jackson). WARNING niet ERROR, per-periode, gededupliceerd.
 */
class PeriodeDekkingTest {

    private static Geldigheid g(String vanaf, String tot) {
        return new Geldigheid(vanaf == null ? null : LocalDate.parse(vanaf),
                              tot == null ? null : LocalDate.parse(tot));
    }

    private static Parameterset set(String naam, String param, WaardeInterval... intervallen) {
        return new Parameterset(naam, Map.of(param, List.of(intervallen)));
    }

    /** Een model met één regelversie 'r' geldig in {@code versie}, die de parameter 'de tarief' leest. */
    private static RegelSpraakModel model(String versie) {
        String src = ""
                + "Objecttype de Heffing\n"
                + "    de prijs Numeriek (geheel getal);\n"
                + "Parameter de tarief : Numeriek (geheel getal)\n"
                + "Regel r\n"
                + "    " + versie + "\n"
                + "        De prijs van een Heffing moet gesteld worden op de tarief.\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte resolve-fouten: " + m.errors());
        assertEquals(1, m.resolvedRegels.size(), "verwacht precies één regelversie");
        return m;
    }

    private static long warnings(RegelSpraakModel m, String bevat) {
        return m.diagnostics.stream()
                .filter(d -> d.severity() == RegelSpraakModel.Severity.WARNING)
                .filter(d -> d.message().startsWith("periodedekking:"))
                .filter(d -> d.message().contains(bevat))
                .count();
    }

    // ---- g1: card-G3-vorm — regel 2024..2026, parameter alleen 2025 -> twee gaten (2024 en 2026) ----

    @Test void g1_regel_2024_2026_parameter_alleen_2025_twee_gaten_met_warning() {
        RegelSpraakModel m = model("geldig vanaf 01-01-2024 t/m 31-12-2026");
        var sets = List.of(set("2025", "tarief",
                new WaardeInterval(new BigDecimal(50), g("2025-01-01", "2025-12-31"))));

        List<PeriodeDekking> bev = PeriodeDekking.controleer(m, sets);
        assertEquals(1, bev.size(), bev::toString);
        PeriodeDekking pd = bev.get(0);
        assertEquals("tarief", pd.parameter());
        assertEquals(2, pd.ontbrekend().size(), () -> "twee gaten (2024, 2026): " + pd.ontbrekend());
        assertEquals(g("2024-01-01", "2024-12-31"), pd.ontbrekend().get(0));
        assertEquals(g("2026-01-01", "2026-12-31"), pd.ontbrekend().get(1));

        // meld zet één WARNING die BEIDE periodes citeert.
        PeriodeDekking.meldAlle(bev, m);
        assertEquals(1, warnings(m, "tarief"));
        String w = m.diagnostics.stream()
                .filter(d -> d.message().startsWith("periodedekking:")).findFirst().orElseThrow().message();
        assertTrue(w.contains("regel 'r'"), w);
        assertTrue(w.contains("(geldig [2024-01-01 t/m 2026-12-31])"), w);
        assertTrue(w.contains("[2024-01-01 t/m 2024-12-31]"), w);
        assertTrue(w.contains("[2026-01-01 t/m 2026-12-31]"), w);
    }

    // ---- g2: volledige dekking -> lege lijst, geen WARNING ----

    @Test void g2_volledige_dekking_geen_bevinding_geen_warning() {
        RegelSpraakModel m = model("geldig vanaf 01-01-2024 t/m 31-12-2026");
        var sets = List.of(set("alles", "tarief",
                new WaardeInterval(new BigDecimal(10), g("2024-01-01", "2024-12-31")),
                new WaardeInterval(new BigDecimal(20), g("2025-01-01", "2025-12-31")),
                new WaardeInterval(new BigDecimal(30), g("2026-01-01", "2026-12-31"))));

        List<PeriodeDekking> bev = PeriodeDekking.controleer(m, sets);
        assertTrue(bev.isEmpty(), bev::toString);
        PeriodeDekking.meldAlle(bev, m);
        assertEquals(0, warnings(m, "tarief"));
    }

    // ---- g3: regel 'geldig altijd' × begrensde parameter -> twee OPEN gaten ----

    @Test void g3_geldig_altijd_versus_begrensde_parameter_twee_open_gaten() {
        RegelSpraakModel m = model("geldig altijd");
        var sets = List.of(set("2025", "tarief",
                new WaardeInterval(new BigDecimal(50), g("2025-01-01", "2025-12-31"))));

        List<PeriodeDekking> bev = PeriodeDekking.controleer(m, sets);
        assertEquals(1, bev.size(), bev::toString);
        List<Geldigheid> gaten = bev.get(0).ontbrekend();
        assertEquals(2, gaten.size(), gaten::toString);
        assertEquals(g(null, "2024-12-31"), gaten.get(0));   // open onderkant
        assertEquals(g("2026-01-01", null), gaten.get(1));   // open bovenkant

        PeriodeDekking.meldAlle(bev, m);
        String w = m.diagnostics.stream()
                .filter(d -> d.message().startsWith("periodedekking:")).findFirst().orElseThrow().message();
        assertTrue(w.contains("(geldig [(open) t/m (open)])"), w);
        assertTrue(w.contains("[(open) t/m 2024-12-31]"), w);
        assertTrue(w.contains("[2026-01-01 t/m (open)]"), w);
    }

    // ---- g4: parameter niet in de sets -> geen melding (het andere kanaal) ----

    @Test void g4_parameter_niet_in_sets_geen_melding() {
        RegelSpraakModel m = model("geldig vanaf 01-01-2024 t/m 31-12-2026");
        // de sets bevatten een ANDERE parameter; 'de tarief' zit er niet in.
        var sets = List.of(set("ander", "heffingsvoet",
                new WaardeInterval(new BigDecimal(1), g("2025-01-01", "2025-12-31"))));

        List<PeriodeDekking> bev = PeriodeDekking.controleer(m, sets);
        assertTrue(bev.isEmpty(), bev::toString);
        PeriodeDekking.meldAlle(bev, m);
        assertEquals(0, warnings(m, "tarief"));

        // en met lege sets draait de check vacuüm leeg.
        assertTrue(PeriodeDekking.controleer(m, List.of()).isEmpty());
    }

    // ---- g5: twee regelversies met verschillende vensters -> alleen de ongedekte versie meldt ----

    @Test void g5_twee_versies_alleen_ongedekte_versie_meldt() {
        String src = ""
                + "Objecttype de Heffing\n"
                + "    de prijs Numeriek (geheel getal);\n"
                + "Parameter de tarief : Numeriek (geheel getal)\n"
                + "Regel r\n"
                + "    geldig vanaf 01-01-2025 t/m 31-12-2025\n"
                + "        De prijs van een Heffing moet gesteld worden op de tarief.\n"
                + "    geldig vanaf 01-01-2027 t/m 31-12-2027\n"
                + "        De prijs van een Heffing moet gesteld worden op de tarief.\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(2, m.resolvedRegels.size());

        // parameter alleen 2025: versie #1 (2025) volledig gedekt, versie #2 (2027) ongedekt.
        var sets = List.of(set("2025", "tarief",
                new WaardeInterval(new BigDecimal(50), g("2025-01-01", "2025-12-31"))));
        List<PeriodeDekking> bev = PeriodeDekking.controleer(m, sets);
        assertEquals(1, bev.size(), bev::toString);
        assertEquals("r #2", bev.get(0).regel());
        assertEquals(1, bev.get(0).ontbrekend().size());
        assertEquals(g("2027-01-01", "2027-12-31"), bev.get(0).ontbrekend().get(0));

        PeriodeDekking.meldAlle(bev, m);
        assertEquals(1, warnings(m, "tarief"));
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("regel 'r #2'")));
        assertFalse(m.diagnostics.stream().anyMatch(d -> d.message().contains("regel 'r #1'")));
    }

    // ---- g7: open-eind-dekking — een dekking die open doorloopt dekt de open regelperiode zonder spookgat ----
    // (review-fix: de MAX-sentinel liep na een open-eind-dekking als cursor door en de staart-check voegde een
    //  onzin-gat [MAX t/m (open)] toe — zelfs voor het degeneratieve altijd × ALTIJD-geval.)

    @Test void g7_altijd_regel_met_altijd_dekking_is_volledig_gedekt() {
        RegelSpraakModel m = model("geldig altijd");
        var sets = List.of(set("s", "tarief", new WaardeInterval(new BigDecimal(50), Geldigheid.ALTIJD)));
        assertTrue(PeriodeDekking.controleer(m, sets).isEmpty(),
                () -> "altijd × ALTIJD-dekking mag geen gat melden: " + PeriodeDekking.controleer(m, sets));
    }

    @Test void g7_altijd_regel_met_open_eind_dekking_geeft_alleen_het_ondergat() {
        RegelSpraakModel m = model("geldig altijd");
        var sets = List.of(set("s", "tarief", new WaardeInterval(new BigDecimal(50), g("2025-01-01", null))));
        List<PeriodeDekking> bev = PeriodeDekking.controleer(m, sets);
        assertEquals(1, bev.size(), bev::toString);
        assertEquals(List.of(g(null, "2024-12-31")), bev.get(0).ontbrekend(),
                () -> "alleen het open ondergat, geen spookgat aan de bovenkant: " + bev.get(0).ontbrekend());
    }

    // ---- g6: dedup — controleer+meld twee keer -> diagnostic staat er één keer ----

    @Test void g6_dedup_twee_keer_melden_is_een_diagnostic() {
        RegelSpraakModel m = model("geldig vanaf 01-01-2024 t/m 31-12-2026");
        var sets = List.of(set("2025", "tarief",
                new WaardeInterval(new BigDecimal(50), g("2025-01-01", "2025-12-31"))));

        PeriodeDekking.meldAlle(PeriodeDekking.controleer(m, sets), m);
        PeriodeDekking.meldAlle(PeriodeDekking.controleer(m, sets), m);

        assertEquals(1, warnings(m, "tarief"), () -> "dedup faalt: " + m.diagnostics);
    }
}
