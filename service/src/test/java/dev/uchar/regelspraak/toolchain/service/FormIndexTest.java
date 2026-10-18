package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The forms of a model by the names a descriptor scope uses: over the real TOKA sources, and over a small model with
 *  a decision table, a rule group and an exception block (a defaults group). */
class VormIndexTest {

    private static final String KLEIN = """
        Objecttype het Aanspoelsel (mv: Aanspoelsels)
            de soort Tekst;
            de rubriek Tekst;
            de stuks Numeriek (geheel getal);
            is gekeurd kenmerk (bijvoeglijk);
            is afgekeurd kenmerk (bijvoeglijk);
            is uitgesloten kenmerk (bijvoeglijk);
            is weegplichtig kenmerk (bijvoeglijk);

        Beslistabel rubriek van een aanspoelsel
            geldig altijd
        | | de rubriek van een Aanspoelsel moet gesteld worden op | indien zijn soort gelijk is aan |
        |---|---|---|
        | 1 | "organisch" | "zeewier" |
        | 2 | "overig" | "wrakhout" |

        Regel uitsluiting
            geldig altijd
                Een Aanspoelsel is uitgesloten
                indien hij afgekeurd is.

        Uitzondering op uitsluiting: gekeurd
            geldig altijd
                Een Aanspoelsel is niet uitgesloten
                indien hij gekeurd is.

        Regelgroep weging
        Regel weegplicht
            geldig altijd
                Een Aanspoelsel is weegplichtig
                indien zijn stuks groter is dan 10.
        """;

    private static VormIndex klein() {
        RegelSpraakModel m = Resolver.resolve(KLEIN);
        assertTrue(m.isOk(), m.errors().toString());
        return VormIndex.van(m, m.prioritizedDefaults);
    }

    @Test
    void toka_regels_dragen_hun_vorm() {
        RegelSpraakModel m = TokaBron.model();
        VormIndex index = VormIndex.van(m, m.prioritizedDefaults);
        assertEquals(1, index.vormenVan("bepaal leeftijd").size(), index.alle().toString());
        assertEquals(1, index.vormenVan("Totaal te betalen belasting").size(), index.alle().toString());
        // toka-45 is NOT in the served set since besluit 16Y (see TokaBron), so its rule is not nameable
        assertFalse(index.bestaat("belasting op basis van afstand"),
                "toka-45 hoort sinds besluit 16Y niet in de geserveerde bronset te zitten");
        // a decision table's rows are forms of their own, and the table's name carries all three
        assertEquals(1, index.vormenVan("Woonregio factor 1").size());
        assertEquals(3, index.vormenVan("Woonregio factor").size(), index.alle().toString());
        assertFalse(index.bestaat("bestaat niet"));
    }

    @Test
    void een_tabelnaam_draagt_haar_rijen_en_een_rij_zichzelf() {
        VormIndex index = klein();
        assertEquals(List.of("rubriek van een aanspoelsel 1", "rubriek van een aanspoelsel 2"),
                index.vormenVan("rubriek van een aanspoelsel"));
        assertEquals(List.of("rubriek van een aanspoelsel 2"), index.vormenVan("Rubriek van een  aanspoelsel 2"),
                "een naam vergelijkt zoals uchars scenariolaag: spaties samengevoegd, hoofdletters gelijk");
    }

    @Test
    void een_basisregel_met_uitzondering_is_een_defaultsgroep_en_de_uitzondering_heeft_geen_vorm() {
        VormIndex index = klein();
        assertEquals(List.of("uitsluiting"), index.vormenVan("uitsluiting"));
        assertTrue(index.bestaat("gekeurd"), "de uitzondering is een gedeclareerde regel");
        assertTrue(index.vormenVan("gekeurd").isEmpty(), "zij gaat op in de defaultsgroep van haar basisregel");
        VormIndex.Keuze keuze = index.scope(new DienstDescriptor.Scope(List.of("gekeurd"), null));
        assertTrue(keuze.vormen().isEmpty(), keuze.toString());
        assertEquals(List.of("gekeurd"), keuze.zonderVorm());
    }

    @Test
    void een_regelgroep_draagt_de_vormen_van_haar_leden() {
        VormIndex.Keuze keuze = klein().scope(new DienstDescriptor.Scope(null, List.of("weging")));
        assertEquals(List.of("weegplicht"), keuze.vormen());
        assertTrue(keuze.zonderVorm().isEmpty(), keuze.toString());
    }
}
