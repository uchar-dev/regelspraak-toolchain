package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dev-only playground analyzer, exercised for its answer shapes (the payload {@code POST /playground/analyse}
 * returns): a parse error (position + expected tokens), a resolver error (diagnostics and nothing else), a clean
 * model with its IR, the verdict of the branch that fires, the DRL view and the rule graph, a clean model uchar does
 * not carry, and a scenario outcome with its trace and uitleg. These pin the core {@link Playground#analyseer}
 * contract; the wiring over HTTP (env-gated route) is covered by {@link PlaygroundHttpTest}.
 */
class PlaygroundTest {

    /** A clean mini-casus (model + two rules) — the DRL-ok shape. A boolean attribute is declared as a
     *  {@code kenmerk} (§13.3.2.1); the korting rule uses the {@code als … indien …} conditional-value form. */
    private static final String CLEAN = """
        Objecttype de Persoon (mv: Personen)
            de leeftijd Numeriek (geheel getal);
            de korting Numeriek (geheel getal);
            is minderjarig kenmerk (bijvoeglijk);

        Regel bepaal minderjarigheid
        geldig altijd
        Een Persoon is minderjarig indien de leeftijd van de Persoon kleiner is dan 18.

        Regel bepaal korting
        geldig altijd
        De korting van een Persoon moet berekend worden als 25 indien de Persoon minderjarig is.
        """;

    /** The mini-casus PLUS a Situatie + Scenario block (the scenariotoetsen extension surface) — the
     *  scenario-outcome shape. */
    private static final String WITH_SCENARIO = CLEAN + """

        Situatie jonge-persoon:
        een Persoon P1
        - de leeftijd is 12

        Scenario Jonge persoon krijgt korting
        toetst de regels bepaal minderjarigheid en bepaal korting
        gebaseerd op de situatie jonge-persoon
        Met als verwacht resultaat:
        - P1 is minderjarig
        - de korting van P1 is 25
        """;

    // ---------------- shape 1: parse error ----------------

    @Test
    void parse_error_gives_position_and_expected_tokens() {
        // A truncated objecttype header: the closing ')' / member list is missing, so the parser errors with a
        // position and a follow set.
        ObjectNode a = Playground.analyseer("Objecttype de Persoon (mv:");
        JsonNode parse = a.get("parse");
        assertFalse(parse.get("ok").asBoolean(), a.toString());
        assertTrue(parse.get("errors").size() >= 1, a.toString());
        JsonNode f = parse.get("errors").get(0);
        assertTrue(f.get("line").asInt() >= 1, f.toString());
        assertTrue(f.has("column"), f.toString());
        assertTrue(f.get("expected").isArray(), f.toString());
        // a broken model has no DRL and no graaf
        assertFalse(a.has("drl"), "een parse-fout mag geen DRL opleveren: " + a);
        assertFalse(a.has("graph"), "een parse-fout mag geen graaf opleveren: " + a);
    }

    // ---------------- shape 2: resolver error ----------------

    @Test
    void resolve_error_gives_resolver_diagnostics() {
        // Parses fine, but the rule assigns a Tekst literal to a Numeriek attribute -> a typeringen ERROR.
        String source = """
            Objecttype de Persoon (mv: Personen)
                de leeftijd Numeriek (geheel getal);
            Regel fout
            geldig altijd
            De leeftijd van een Persoon moet berekend worden als "twaalf".
            """;
        ObjectNode a = Playground.analyseer(source);
        assertTrue(a.get("parse").get("ok").asBoolean(), "syntactisch geldig: " + a);
        JsonNode resolver = a.get("resolver");
        assertFalse(resolver.get("ok").asBoolean(), "type-mismatch hoort een resolver-fout te zijn: " + a);
        assertTrue(resolver.get("errors").size() >= 1, a.toString());
        assertFalse(a.has("drl"), "een resolver-fout mag geen DRL opleveren: " + a);
        assertFalse(a.has("graph"), "een resolver-fout mag geen graaf opleveren: " + a);
        assertFalse(a.has("ir") || a.has("executable"), "een resolver-fout heeft geen document: " + a);
    }

    @Test
    void resolve_error_in_a_scenario_source_gives_only_the_diagnostics() {
        // the scenario's expectation names an attribute the model does not declare: a resolver error, so nothing
        // fires; until the playground ran on uchar, the scenario pane ran anyway and reported the refusal as its own
        String source = WITH_SCENARIO.replace("- de korting van P1 is 25", "- de schoenmaat van P1 is 25");
        ObjectNode a = Playground.analyseer(source);
        assertFalse(a.get("resolver").get("ok").asBoolean(), a.toString());
        assertTrue(a.get("resolver").get("errors").size() >= 1, a.toString());
        for (String field : new String[]{"ir", "executable", "drl", "graph", "graphJson", "scenario"})
            assertFalse(a.has(field), "bij een resolver-fout geen '" + field + "': " + a);
    }

    // ---------------- shape 3: DRL ok ----------------

    @Test
    void clean_model_yields_drl_text() {
        ObjectNode a = Playground.analyseer(CLEAN);
        assertTrue(a.get("parse").get("ok").asBoolean(), a.toString());
        assertTrue(a.get("resolver").get("ok").asBoolean(), a.toString());
        assertTrue(a.has("drl"), "een schoon model hoort DRL te leveren: " + a);
        String drl = a.get("drl").asText();
        assertTrue(drl.contains("rule "), "de DRL hoort minstens één rule te bevatten: " + drl);
        // a clean model also yields a graaf: the dataflow projection, as bare Mermaid flowchart source carrying
        // the rule nodes (bepaal minderjarigheid writes the kenmerk, bepaal korting reads it and writes korting).
        assertTrue(a.has("graph"), "een schoon model hoort een graaf te leveren: " + a);
        assertFalse(a.has("graphError"), "geen graaf-fout verwacht bij een schoon model: " + a);
        String graph = a.get("graph").asText();
        assertTrue(graph.startsWith("flowchart LR"), "de graaf is Mermaid-flowchart-bron: " + graph);
        assertTrue(graph.contains("bepaal minderjarigheid"), "regel-knoop ontbreekt: " + graph);
        assertTrue(graph.contains("bepaal korting"), "regel-knoop ontbreekt: " + graph);
        // no scenario block -> no scenario section
        assertFalse(a.has("scenario"), "zonder Scenario-blok geen scenario-sectie: " + a);
        // the IR document uchar's emitter writes, and the verdict of the branch that fires
        assertEquals("uchar-ir-v1", a.get("ir").get("format").asText(), a.toString());
        assertTrue(a.get("executable").asBoolean(), a.toString());
        assertEquals(2, a.get("coverage").size(), a.toString());
        for (JsonNode d : a.get("coverage")) assertEquals("LOWERED", d.get("verdict").asText(), d.toString());
        // the graph as nodes and edges beside the Mermaid text: two rule nodes, edges between known ids
        JsonNode g = a.get("graphJson");
        java.util.Set<String> ids = new java.util.HashSet<>();
        int lines = 0;
        for (JsonNode n : g.get("nodes")) {
            ids.add(n.get("id").asText());
            if ("RULE".equals(n.get("kind").asText())) lines++;
            assertTrue(n.has("label") && n.has("endOutput"), n.toString());
        }
        assertEquals(2, lines, g.toString());
        assertTrue(g.get("edges").size() >= 2, g.toString());
        for (JsonNode e : g.get("edges"))
            assertTrue(ids.contains(e.get("from").asText()) && ids.contains(e.get("to").asText()), e.toString());
    }

    @Test
    void a_clean_model_that_uchar_does_not_carry_gets_the_uchar_refusal() {
        // resolves cleanly; uchar's exec-model branch does not lower the rule, so the verdict is 'niet uitvoerbaar'
        // with uchar's sentence (the DRL view is a view, not the verdict)
        ObjectNode a = Playground.analyseer("""
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (niet-negatief geheel getal);
                is belast kenmerk (bijvoeglijk) voor elke dag;
            Regel heffing markert
            geldig altijd
            De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
            """);
        assertTrue(a.get("resolver").get("ok").asBoolean(), a.toString());
        assertFalse(a.get("executable").asBoolean(), a.toString());
        assertTrue(a.get("refusal").asText().contains("heffing markert"), a.toString());
        assertFalse(a.has("coverage"), a.toString());
    }

    // ---------------- shape 4: scenario outcome ----------------

    @Test
    void scenario_block_yields_outcome_per_expectation() {
        ObjectNode a = Playground.analyseer(WITH_SCENARIO);
        assertTrue(a.get("resolver").get("ok").asBoolean(), a.toString());
        JsonNode scenario = a.get("scenario");
        assertTrue(scenario != null && scenario.has("results"), "scenario-sectie verwacht: " + a);
        assertEquals(1, scenario.get("results").size(), a.toString());
        JsonNode r = scenario.get("results").get(0);
        assertEquals("PASS", r.get("status").asText(), a.toString());
        assertEquals(2, r.get("expectations").size(), a.toString());
        for (JsonNode v : r.get("expectations"))
            assertTrue(v.get("ok").asBoolean(), "elke verwachting hoort te slagen: " + v);
        // the trace: both rules fired on P1, as uchar's replay counts it
        assertEquals(2, r.get("trace").size(), r.toString());
        for (JsonNode t : r.get("trace")) {
            assertTrue(t.get("count").asInt() >= 1, t.toString());
            assertEquals("P1", t.get("labels").get(0).asText(), t.toString());
        }
        // the uitleg: the characteristic, justified with the value its condition read
        assertFalse(r.has("explanationError"), r.toString());
        assertEquals(1, r.get("explanation").size(), r.toString());
        String sentence = r.get("explanation").get(0).asText();
        assertTrue(sentence.startsWith("P1 is minderjarig, omdat") && sentence.contains("12"), sentence);
    }
}
