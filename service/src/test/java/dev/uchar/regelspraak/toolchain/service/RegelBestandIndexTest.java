package dev.uchar.regelspraak.toolchain.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The regelnaam→bronbestand index over the real TOKA sources (decision 2b). */
class RegelBestandIndexTest {

    @Test
    void foundation_bestanden_zijn_prelude_rule_bestanden_dragen_een_regel() {
        Map<String, String> bron = TokaBron.bronPerBestand();
        RegelBestandIndex index = RegelBestandIndex.bouw(bron);

        // every toka-01..16 + reconciliatie + model-extensie declares NO rule -> prelude
        for (String id : index.preludeBestanden())
            assertTrue(index.regelsPerBestand().get(id).isEmpty(), id + " zou prelude moeten zijn");
        // the foundation files are all in prelude
        assertTrue(index.preludeBestanden().stream().anyMatch(p -> p.contains("toka-01-")));
        assertTrue(index.preludeBestanden().stream().anyMatch(p -> p.contains("casus-omissies")));
        assertTrue(index.preludeBestanden().stream().anyMatch(p -> p.contains("scenario-model-extensie")));
    }

    @Test
    void bekende_regels_wijzen_naar_het_juiste_bestand() {
        RegelBestandIndex index = RegelBestandIndex.bouw(TokaBron.bronPerBestand());
        assertTrue(index.bestandVoorRegel("bepaal leeftijd").contains("toka-17-"));
        assertTrue(index.bestandVoorRegel("Totaal te betalen belasting").contains("toka-20-"));
        // toka-45 is NOT in the served set since besluit 16Y (see TokaBron), so its rule is not indexable:
        // assert the absence rather than dropping the case, so a silent re-admission fails here.
        assertNull(index.bestandVoorRegel("belasting op basis van afstand"),
                "toka-45 hoort sinds besluit 16Y niet in de geserveerde bronset te zitten");
        // beslistabel rows: three distinct rules all declared by the ONE beslistabel file
        String f1 = index.bestandVoorRegel("Woonregio factor 1");
        String f3 = index.bestandVoorRegel("Woonregio factor 3");
        assertTrue(f1.contains("toka-46-"), f1);
        assertEquals(f1, f3, "de drie beslistabel-rijen horen uit hetzelfde bestand te komen");
    }

    @Test
    void een_construct_per_bestand() {
        RegelBestandIndex index = RegelBestandIndex.bouw(TokaBron.bronPerBestand());
        // no rule file (non-prelude) declares more than one rule EXCEPT the beslistabel (3 rows)
        for (var e : index.regelsPerBestand().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (e.getKey().contains("toka-46-")) { assertEquals(3, e.getValue().size(), e.getKey()); continue; }
            assertEquals(1, e.getValue().size(), e.getKey() + " declareert " + e.getValue());
        }
    }

    @Test
    void onbekende_regel_geeft_null() {
        RegelBestandIndex index = RegelBestandIndex.bouw(TokaBron.bronPerBestand());
        assertFalse(index.regelNaarBestand().containsKey("bestaat niet"));
    }
}
