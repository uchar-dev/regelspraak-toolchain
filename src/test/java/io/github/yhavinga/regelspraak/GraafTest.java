package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.graaf.RegelGraaf;
import io.github.yhavinga.regelspraak.graaf.RegelGraaf.Node;
import io.github.yhavinga.regelspraak.graaf.RegelGraaf.NodeKind;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The regelgraaf extraction: a resolved model's producer/consumer facts become a bipartite dataflow graph whose
 * node kinds and edges are the SHARED {@link io.github.yhavinga.regelspraak.graaf.ProducentConsument} analysis (also the
 * authority behind the service's scope-completeness gate). The mini-model below produces exactly one of each
 * node kind, so a coloring/direction regression is caught at the extraction level, before any rendering.
 *
 * <p>Chain: "basisprijs" is read but written by no rule (a request INVOER); "bepaal duur" writes the KENMERK
 * "duur", which "bepaal korting" consumes to write the ATTRIBUUT "korting"; "bepaal totaal" reads both
 * "basisprijs" and "korting" to write "totaal", which no rule consumes (a product-less eind-output).
 */
class GraafTest {

    static final String SRC = """
        Objecttype de Reis
            de basisprijs Numeriek (geheel getal);
            de korting Numeriek (geheel getal);
            het totaal Numeriek (geheel getal);
            is duur kenmerk (bijvoeglijk);
        Regel bepaal duur
            geldig altijd
                De Reis is duur indien de basisprijs van een Reis groter is dan 100.
        Regel bepaal korting
            geldig altijd
                De korting van een Reis moet gesteld worden op 10 indien de Reis duur is.
        Regel bepaal totaal
            geldig altijd
                Het totaal van een Reis moet berekend worden als de basisprijs van een Reis min de korting van een Reis.
        """;

    private static RegelGraaf graaf() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        return RegelGraaf.bouw(m);
    }

    /** A label lookup so assertions read on the human name, not the allocated node id. */
    private static Node byLabel(RegelGraaf g, String label) {
        return g.nodes().stream().filter(n -> n.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("geen knoop met label '" + label + "' in " + g.nodes()));
    }

    @Test void knopen_krijgen_de_juiste_soort() {
        RegelGraaf g = graaf();
        // three rules + four data elements = seven nodes.
        assertEquals(7, g.nodes().size(), () -> "knopen: " + g.nodes());
        assertEquals(3, g.nodes().stream().filter(n -> n.kind() == NodeKind.REGEL).count());

        assertEquals(NodeKind.INVOER, byLabel(g, "basisprijs · reis").kind(),
                "een datum die geen regel produceert is een request-invoer");
        assertEquals(NodeKind.KENMERK, byLabel(g, "duur · reis").kind(),
                "'is duur' is een §13.4.5 kenmerk, niet een gewoon attribuut");
        assertEquals(NodeKind.ATTRIBUUT, byLabel(g, "korting · reis").kind());
        assertEquals(NodeKind.ATTRIBUUT, byLabel(g, "totaal · reis").kind());
    }

    @Test void productloze_eind_output_wordt_gemarkeerd() {
        RegelGraaf g = graaf();
        // "totaal" is produced by "bepaal totaal" but consumed by no rule: the sole eind-output.
        assertTrue(byLabel(g, "totaal · reis").eindOutput(), "totaal wordt door niets geconsumeerd");
        assertEquals(1, g.nodes().stream().filter(Node::eindOutput).count());
        // an INVOER is never an eind-output, and a consumed attribuut ("korting") is not either.
        assertFalse(byLabel(g, "basisprijs · reis").eindOutput());
        assertFalse(byLabel(g, "korting · reis").eindOutput());
    }

    @Test void kanten_volgen_de_dataflow_richting() {
        RegelGraaf g = graaf();
        // edges reference ids; resolve them back to label->label for a readable assertion.
        Map<String, String> labelVan = g.nodes().stream().collect(Collectors.toMap(Node::id, Node::label));
        Set<String> kanten = g.edges().stream()
                .map(e -> labelVan.get(e.van()) + " -> " + labelVan.get(e.naar()))
                .collect(Collectors.toSet());

        // producer edges: rule -> the datum it writes.
        assertTrue(kanten.contains("bepaal duur -> duur · reis"), () -> kanten.toString());
        assertTrue(kanten.contains("bepaal korting -> korting · reis"), () -> kanten.toString());
        assertTrue(kanten.contains("bepaal totaal -> totaal · reis"), () -> kanten.toString());
        // consumer edges: the datum -> the rule that reads it.
        assertTrue(kanten.contains("basisprijs · reis -> bepaal duur"), () -> kanten.toString());
        assertTrue(kanten.contains("duur · reis -> bepaal korting"), () -> kanten.toString());
        assertTrue(kanten.contains("basisprijs · reis -> bepaal totaal"), () -> kanten.toString());
        assertTrue(kanten.contains("korting · reis -> bepaal totaal"), () -> kanten.toString());
        assertEquals(7, kanten.size(), () -> "precies de zeven dataflow-kanten: " + kanten);
    }

    @Test void een_regel_leest_zijn_eigen_doel_niet_als_afhankelijkheid() {
        // "bepaal totaal" writes "totaal" and reads "basisprijs"+"korting"; it must NOT gain a self-edge on
        // "totaal" (a rule reading what it writes is not a cross-rule dependency — the verbruikt() exclusion).
        RegelGraaf g = graaf();
        Map<String, String> labelVan = g.nodes().stream().collect(Collectors.toMap(Node::id, Node::label));
        boolean zelfLus = g.edges().stream().anyMatch(e ->
                "totaal · reis".equals(labelVan.get(e.van())) && "bepaal totaal".equals(labelVan.get(e.naar())));
        assertFalse(zelfLus, "een regel mag geen consument-kant naar zijn eigen productie hebben");
    }

    @Test void mermaid_bevat_alle_knopen_en_klassen() {
        // A light render-shape check: the bare Mermaid text is a flowchart carrying every node id and the four
        // class definitions. (The exact styling is not asserted — that is the render's concern, not extraction's.)
        String mmd = graaf().mermaid();
        assertTrue(mmd.startsWith("flowchart LR"), () -> mmd);
        assertTrue(mmd.contains("classDef invoer"), "invoer-klasse ontbreekt");
        assertTrue(mmd.contains("classDef kenmerk"), "kenmerk-klasse ontbreekt");
        assertTrue(mmd.contains("eindoutput"), "eind-output-markering ontbreekt");
        for (Node n : graaf().nodes())
            assertTrue(mmd.contains("  " + n.id()), () -> "knoop " + n.id() + " (" + n.label() + ") niet in mermaid");
    }
}
